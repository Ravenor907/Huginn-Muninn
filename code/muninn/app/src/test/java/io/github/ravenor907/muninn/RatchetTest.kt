package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Ratchet
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/** The same ratchet vectors Huginn checks: both apps must agree byte for byte. */
class RatchetTest {
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText())
    private val v = JSONObject(javaClass.classLoader!!.getResource("ratchet-vectors.json")!!.readText())

    private val aStatic = KeyPair(ChatCrypto.publicFrom(chat.getString("pubA")), ChatCrypto.privateFrom(chat.getString("privA")))
    private val bStatic = KeyPair(ChatCrypto.publicFrom(chat.getString("pubB")), ChatCrypto.privateFrom(chat.getString("privB")))
    private val pubA = chat.getString("pubA")
    private val pubB = chat.getString("pubB")
    private val sk = ChatCrypto.sharedKey(aStatic.private, aStatic.public, bStatic.public)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Key pairs from fixed integers, used in order, like the reference. */
    private fun keys(start: Int): () -> KeyPair {
        var next = start
        return { P256.keyPair(BigInteger.valueOf((next++).toLong())) }
    }

    @Test
    fun skMatches() = assertEquals(v.getString("sk"), hex(sk))

    @Test
    fun reproducesTheVectors() {
        val msgs = v.getJSONArray("messages")
        val aNext = keys(0x1001)
        val bNext = keys(0x2001)
        val alice = Ratchet.Session.alice(sk, pubB, aNext())
        var bob: Ratchet.Session? = null
        val sent = arrayOfNulls<Pair<Ratchet.Header, ByteArray>>(msgs.length())

        fun send(i: Int) {
            val m = msgs.getJSONObject(i)
            val s = if (m.getString("from") == "A") alice else bob!!
            val out = s.encrypt(m.getString("text").toByteArray(Charsets.UTF_8))
            assertEquals("header $i", Ratchet.Header.fromJson(m.getJSONObject("header")), out.first)
            assertEquals("cipher $i", m.getString("c"), ChatCrypto.b64(out.second))
            sent[i] = out
        }

        fun deliver(i: Int) {
            val m = msgs.getJSONObject(i)
            // Decrypt what came over the wire (the JSON), not the in-memory copy.
            val h = Ratchet.Header.fromJson(JSONObject(m.getJSONObject("header").toString()))
            val c = ChatCrypto.b64d(m.getString("c"))
            val p = if (m.getString("from") == "A") {
                if (bob == null) {
                    assertEquals(h.b, h.d)
                    bob = Ratchet.Session.bob(sk, bStatic, h.b)
                }
                bob!!.decrypt(h, c, bNext)
            } else {
                alice.decrypt(h, c, aNext)
            }
            assertNotNull("decrypt $i", p)
            assertEquals(m.getString("text"), String(p!!, Charsets.UTF_8))
        }

        // Same order as main() in shared/tools/ratchet_ref.py.
        send(0); send(1); deliver(0); deliver(1)
        send(2); deliver(2)
        send(3); send(4); send(5); deliver(3); deliver(5); deliver(4)
        send(6); send(7); deliver(7); deliver(6)
        send(8); deliver(8)

        // The listed delivery order is the one we just used.
        val order = v.getJSONArray("deliver")
        assertEquals(listOf(0, 1, 2, 3, 5, 4, 7, 6, 8), (0 until order.length()).map { order.getJSONArray(it).getInt(1) })
        for (i in 0 until order.length()) {
            val who = order.getJSONArray(i).getString(0)
            val idx = order.getJSONArray(i).getInt(1)
            assertTrue(who != msgs.getJSONObject(idx).getString("from"))
        }
    }

    @Test
    fun tamperedReturnsNullAndKeepsState() {
        val aNext = keys(0x1001)
        val bNext = keys(0x2001)
        val alice = Ratchet.Session.alice(sk, pubB, aNext())
        val (h, c) = alice.encrypt("secret".toByteArray())
        val bob = Ratchet.Session.bob(sk, bStatic, h.b)
        val before = bob.toJson().toString()
        val bad = c.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(bob.decrypt(h, bad, bNext))
        assertEquals(before, bob.toJson().toString())
        // A tampered header fails too.
        assertNull(bob.decrypt(h.copy(n = 5), c, bNext))
        assertEquals(before, bob.toJson().toString())
        // Too many skipped keys fails without changes.
        assertNull(bob.decrypt(h.copy(n = Ratchet.MAX_SKIP + 5), c, bNext))
        assertEquals(before, bob.toJson().toString())
        assertEquals("secret", String(bob.decrypt(h, c, bNext)!!))
    }

    @Test
    fun replayFails() {
        val aNext = keys(0x1001)
        val bNext = keys(0x2001)
        val alice = Ratchet.Session.alice(sk, pubB, aNext())
        val m0 = alice.encrypt("one".toByteArray())
        val m1 = alice.encrypt("two".toByteArray())
        val bob = Ratchet.Session.bob(sk, bStatic, m0.first.b)
        assertEquals("two", String(bob.decrypt(m1.first, m1.second, bNext)!!))
        assertEquals("one", String(bob.decrypt(m0.first, m0.second, bNext)!!)) // from the skipped keys
        assertNull(bob.decrypt(m0.first, m0.second, bNext))
        assertNull(bob.decrypt(m1.first, m1.second, bNext))
    }

    @Test
    fun sessionSurvivesJson() {
        val aNext = keys(0x1001)
        val bNext = keys(0x2001)
        val alice = Ratchet.Session.alice(sk, pubB, aNext())
        val m0 = alice.encrypt("one".toByteArray())
        val m1 = alice.encrypt("two".toByteArray())
        var bob = Ratchet.Session.bob(sk, bStatic, m0.first.b)
        assertNotNull(bob.decrypt(m1.first, m1.second, bNext))
        bob = Ratchet.Session.fromJson(JSONObject(bob.toJson().toString()))
        assertEquals("one", String(bob.decrypt(m0.first, m0.second, bNext)!!))
        val r = bob.encrypt("back".toByteArray())
        val alice2 = Ratchet.Session.fromJson(JSONObject(alice.toJson().toString()))
        assertEquals("back", String(alice2.decrypt(r.first, r.second, aNext)!!))
    }

    @Test
    fun bothStartingAtOnceConverge() {
        val aNext = keys(0x3001)
        val bNext = keys(0x4001)
        var a = Ratchet.Peer()
        var b = Ratchet.Peer()
        fun aSend(t: String) = a.encrypt(t.toByteArray(), sk, pubB, aNext)
        fun bSend(t: String) = b.encrypt(t.toByteArray(), sk, pubA, bNext)
        fun toA(m: Pair<Ratchet.Header, ByteArray>) = a.decrypt(m.first, m.second, sk, aStatic, aNext)?.let { String(it) }
        fun toB(m: Pair<Ratchet.Header, ByteArray>) = b.decrypt(m.first, m.second, sk, bStatic, bNext)?.let { String(it) }

        // Both send first, before either hears from the other.
        val fromA = aSend("hi from A")
        val fromB = bSend("hi from B")
        assertTrue(fromA.first.b != fromB.first.b)
        assertEquals("hi from B", toA(fromB))
        assertEquals("hi from A", toB(fromA))
        // Then they keep talking, with a save and load in the middle.
        for (i in 0 until 5) {
            assertEquals("a$i", toB(aSend("a$i")))
            assertEquals("b$i", toA(bSend("b$i")))
            if (i == 2) {
                a = Ratchet.Peer.fromJson(JSONObject(a.toJson().toString()))
                b = Ratchet.Peer.fromJson(JSONObject(b.toJson().toString()))
            }
        }
        // Two messages in a row from each side, delivered crossed.
        val x = aSend("x"); val y = bSend("y")
        assertEquals("y", toA(y)); assertEquals("x", toB(x))
        assertEquals("z", toB(aSend("z")))
        assertEquals(a.active, b.active)
        assertTrue(a.bases.size <= 3)
    }

    @Test
    fun deterministicKeysMatchJava() {
        // Key 0x1001 must give the base in the first vector message.
        val k = P256.keyPair(BigInteger.valueOf(0x1001))
        assertEquals(v.getJSONArray("messages").getJSONObject(0).getJSONObject("header").getString("b"), Ratchet.pubB64(k.public))
    }
}

/** P-256 key pair from a private integer (tests only): public point = d * G, done with BigInteger. */
object P256 {
    private val params: ECParameterSpec = AlgorithmParameters.getInstance("EC")
        .apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
    private val p: BigInteger = (params.curve.field as java.security.spec.ECFieldFp).p
    private val a: BigInteger = params.curve.a

    private fun add(x: ECPoint, y: ECPoint): ECPoint {
        if (x == ECPoint.POINT_INFINITY) return y
        if (y == ECPoint.POINT_INFINITY) return x
        val lambda = if (x.affineX == y.affineX) {
            if ((x.affineY + y.affineY).mod(p) == BigInteger.ZERO) return ECPoint.POINT_INFINITY
            (x.affineX.pow(2).multiply(BigInteger.valueOf(3)) + a).multiply((x.affineY.shiftLeft(1)).modInverse(p)).mod(p)
        } else {
            (y.affineY - x.affineY).multiply((y.affineX - x.affineX).modInverse(p)).mod(p)
        }
        val rx = (lambda.pow(2) - x.affineX - y.affineX).mod(p)
        val ry = (lambda.multiply(x.affineX - rx) - x.affineY).mod(p)
        return ECPoint(rx, ry)
    }

    private fun mul(k: BigInteger, g: ECPoint): ECPoint {
        var r = ECPoint.POINT_INFINITY
        var q = g
        for (i in 0 until k.bitLength()) {
            if (k.testBit(i)) r = add(r, q)
            q = add(q, q)
        }
        return r
    }

    fun keyPair(d: BigInteger): KeyPair {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(ECPrivateKeySpec(d, params))
        val pub = kf.generatePublic(ECPublicKeySpec(mul(d, params.generator), params))
        return KeyPair(pub, priv)
    }
}
