package io.github.ravenor907.muninn.net

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures on secp256k1: what Nostr relays require on every event. Written
 * straight from the BIP's reference code; the tests check it against the BIP's official values.
 * Every event that arrives is checked, so the point math uses Jacobian coordinates (see [Jac]) to keep it quick.
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )
    private val random = SecureRandom()
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)
    private val TWO = BigInteger.valueOf(2)
    private val EIGHT = BigInteger.valueOf(8)

    private data class Point(val x: BigInteger, val y: BigInteger)

    /**
     * A point in Jacobian coordinates (x = X/Z², y = Y/Z³): adding and doubling need no division, so a
     * whole multiplication costs one inversion at the end instead of one per step. Z = 0 is "no point".
     */
    private class Jac(val x: BigInteger, val y: BigInteger, val z: BigInteger) {
        val none get() = z.signum() == 0
    }

    private val NONE = Jac(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun jac(a: Point) = Jac(a.x, a.y, BigInteger.ONE)

    private fun double(a: Jac): Jac {
        if (a.none || a.y.signum() == 0) return NONE
        val xx = a.x * a.x % P
        val yy = a.y * a.y % P
        val yyyy = yy * yy % P
        val d = (TWO * ((a.x + yy).pow(2) - xx - yyyy)).mod(P)
        val e = THREE * xx % P
        val x3 = (e * e - TWO * d).mod(P)
        val y3 = (e * (d - x3) - EIGHT * yyyy).mod(P)
        val z3 = TWO * a.y * a.z % P
        return Jac(x3, y3, z3)
    }

    /** [a] plus the plain point [b] (its Z is 1, which saves a few multiplications). */
    private fun addPoint(a: Jac, b: Point): Jac {
        if (a.none) return jac(b)
        val zz = a.z * a.z % P
        val u2 = b.x * zz % P
        val s2 = b.y * zz % P * a.z % P
        if (u2 == a.x) return if (s2 == a.y) double(a) else NONE
        val h = (u2 - a.x).mod(P)
        val r = (s2 - a.y).mod(P)
        val hh = h * h % P
        val hhh = hh * h % P
        val v = a.x * hh % P
        val x3 = (r * r - hhh - TWO * v).mod(P)
        val y3 = (r * (v - x3) - a.y * hhh).mod(P)
        val z3 = a.z * h % P
        return Jac(x3, y3, z3)
    }

    /** Back to a plain point: the one inversion. */
    private fun affine(a: Jac): Point? {
        if (a.none) return null
        val zi = a.z.modInverse(P)
        val zi2 = zi * zi % P
        return Point(a.x * zi2 % P, a.y * zi2 % P * zi % P)
    }

    private fun mul(point: Point, k: BigInteger): Point? {
        var r = NONE
        for (i in k.bitLength() - 1 downTo 0) {
            r = double(r)
            if (k.testBit(i)) r = addPoint(r, point)
        }
        return affine(r)
    }

    /** [a] times [p] plus [b] times [q] in one pass (Shamir's trick): what checking a signature needs. */
    private fun mul2(p: Point, a: BigInteger, q: Point, b: BigInteger): Point? {
        var r = NONE
        for (i in maxOf(a.bitLength(), b.bitLength()) - 1 downTo 0) {
            r = double(r)
            if (a.testBit(i)) r = addPoint(r, p)
            if (b.testBit(i)) r = addPoint(r, q)
        }
        return affine(r)
    }

    private fun bytes32(x: BigInteger): ByteArray {
        val b = x.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val t = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        return MessageDigest.getInstance("SHA-256").digest(t + t + msg)
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= P) return null
        val ySq = (x.modPow(THREE, P) + SEVEN).mod(P)
        val y = ySq.modPow((P + BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != ySq) return null
        return Point(x, if (!y.testBit(0)) y else P - y)
    }

    fun newSecret(): ByteArray {
        while (true) {
            val b = ByteArray(32).also { random.nextBytes(it) }
            val d = int(b)
            if (d.signum() > 0 && d < N) return b
        }
    }

    /** x-only public key, as Nostr uses it. */
    fun publicKey(secret: ByteArray): ByteArray = bytes32(mul(G, int(secret))!!.x)

    fun sign(msg: ByteArray, secret: ByteArray, aux: ByteArray = ByteArray(32).also { random.nextBytes(it) }): ByteArray {
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "bad secret key" }
        val pt = mul(G, d0)!!
        val d = if (!pt.y.testBit(0)) d0 else N - d0
        val t = bytes32(d).zip(taggedHash("BIP0340/aux", aux)) { a, b -> (a.toInt() xor b.toInt()).toByte() }.toByteArray()
        val k0 = int(taggedHash("BIP0340/nonce", t + bytes32(pt.x) + msg)).mod(N)
        require(k0.signum() != 0)
        val r = mul(G, k0)!!
        val k = if (!r.y.testBit(0)) k0 else N - k0
        val e = int(taggedHash("BIP0340/challenge", bytes32(r.x) + bytes32(pt.x) + msg)).mod(N)
        return bytes32(r.x) + bytes32((k + e * d).mod(N))
    }

    fun verify(msg: ByteArray, pubkey: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != 64 || pubkey.size != 32) return false
        val pt = liftX(int(pubkey)) ?: return false
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32) + pubkey + msg)).mod(N)
        val rr = mul2(G, s, pt, N - e) ?: return false
        return !rr.y.testBit(0) && rr.x == r
    }

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) { val v = b[i].toInt() and 0xFF; c[i * 2] = HEX[v ushr 4]; c[i * 2 + 1] = HEX[v and 15] }
        return String(c)
    }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
