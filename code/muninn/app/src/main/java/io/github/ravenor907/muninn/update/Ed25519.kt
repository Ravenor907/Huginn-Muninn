package io.github.ravenor907.muninn.update

import java.math.BigInteger
import java.security.MessageDigest

/**
 * Ed25519 signature checks (RFC 8032, section 5.1.7) for the project's release key: CI signs every release
 * file with it (shared/docs/releases.md) and Muninn installs no update without a good signature. Android only has
 * Ed25519 built in from Android 13, and Muninn runs on 8, so this is written out from the RFC with plain
 * BigInteger math; one check per update is quick enough. The tests check it against the RFC's own values
 * (shared/vectors/release-sig-vectors.json, written by shared/tools/release_sig_ref.py). The file streams in through
 * [Check], so an APK is never held in memory whole.
 */
object Ed25519 {
    private val ONE = BigInteger.ONE
    private val TWO = BigInteger.valueOf(2)
    private val P = ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L = ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
    private val D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val SQRT_M1 = TWO.modPow(P.subtract(ONE).shiftRight(2), P)

    /** A point in extended coordinates: x = X/Z, y = Y/Z, x*y = T/Z. */
    private class Pt(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun add(p: Pt, q: Pt): Pt {
        val a = (p.y - p.x).multiply(q.y - q.x).mod(P)
        val b = (p.y + p.x).multiply(q.y + q.x).mod(P)
        val c = p.t.multiply(TWO).multiply(D).multiply(q.t).mod(P)
        val d = p.z.multiply(TWO).multiply(q.z).mod(P)
        val e = b - a; val f = d - c; val g = d + c; val h = b + a
        return Pt(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    private fun mul(s: BigInteger, point: Pt): Pt {
        var q = Pt(BigInteger.ZERO, ONE, ONE, BigInteger.ZERO)
        var p = point
        for (i in 0 until s.bitLength()) {
            if (s.testBit(i)) q = add(q, p)
            p = add(p, p)
        }
        return q
    }

    private fun same(p: Pt, q: Pt): Boolean =
        (p.x.multiply(q.z) - q.x.multiply(p.z)).mod(P).signum() == 0 && (p.y.multiply(q.z) - q.y.multiply(p.z)).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = (y.multiply(y) - ONE).multiply((D.multiply(y).multiply(y) + ONE).modInverse(P)).mod(P)
        if (x2.signum() == 0) return if (sign != 0) null else BigInteger.ZERO
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x.multiply(x) - x2).mod(P).signum() != 0) x = x.multiply(SQRT_M1).mod(P)
        if ((x.multiply(x) - x2).mod(P).signum() != 0) return null
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P - x
        return x
    }

    private val G: Pt = run {
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val x = recoverX(y, 0)!!
        Pt(x, y, ONE, x.multiply(y).mod(P))
    }

    /** 32 bytes, little-endian, as RFC 8032 stores numbers. */
    private fun le(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    private fun decode(b: ByteArray): Pt? {
        val n = le(b)
        val y = n.clearBit(255)
        val x = recoverX(y, if (n.testBit(255)) 1 else 0) ?: return null
        return Pt(x, y, ONE, x.multiply(y).mod(P))
    }

    /** One signature check: feed the signed file through [update], then ask [valid]. */
    class Check(private val publicKey: ByteArray, private val signature: ByteArray) {
        private val shapeOk = publicKey.size == 32 && signature.size == 64
        private val h = MessageDigest.getInstance("SHA-512").apply {
            if (shapeOk) { update(signature, 0, 32); update(publicKey) }
        }

        fun update(b: ByteArray, off: Int = 0, len: Int = b.size) = h.update(b, off, len)

        fun valid(): Boolean {
            if (!shapeOk) return false
            val a = decode(publicKey) ?: return false
            val r = decode(signature.copyOfRange(0, 32)) ?: return false
            val s = le(signature.copyOfRange(32, 64))
            if (s >= L) return false
            val k = le(h.digest()).mod(L)
            return same(mul(s, G), add(r, mul(k, a)))
        }
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        Check(publicKey, signature).apply { update(message) }.valid()
}
