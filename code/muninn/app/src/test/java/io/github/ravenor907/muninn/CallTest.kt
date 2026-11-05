package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.call.CallCrypto
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECPrivateKeySpec

/** The call part of shared/vectors/proto06-vectors.json (shared/docs/protocol.md 5.2 to 5.4), which Huginn checks too. */
class CallTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText())
    private val call = v.getJSONObject("call")
    private val keys = v.getJSONObject("keys")
    private val callId = call.getString("callId")
    private val offer = call.getJSONObject("offer")
    private val answer = call.getJSONObject("answer")

    private fun priv(hex: String): PrivateKey {
        val params = (ChatCrypto.newKeyPair().private as ECPrivateKey).params
        return KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(hex.removePrefix("0x"), 16), params))
    }
    private val offerPriv = priv(call.getString("offerEphPriv"))
    private val answerPriv = priv(call.getString("answerEphPriv"))
    private fun root() = CallCrypto.root(offerPriv, answer.getString("eph"), callId, offer.getString("eph"), answer.getString("eph"))
    private fun h(s: String) = CallCrypto.unhex(s)

    @Test
    fun signaturesVerify() {
        assertEquals(call.getString("offerSigned"), CallCrypto.D_CALL + "\n" + CallCrypto.signedPart("offer", callId, offer.getString("eph")))
        assertEquals(call.getString("answerSigned"), CallCrypto.D_CALL + "\n" + CallCrypto.signedPart("answer", callId, answer.getString("eph")))
        assertTrue(CallCrypto.verify(keys.getString("A"), "offer", callId, offer.getString("eph"), offer.getString("sig")))
        assertTrue(CallCrypto.verify(keys.getString("B"), "answer", callId, answer.getString("eph"), answer.getString("sig")))
        // Wrong signer, wrong type, other key: refused.
        assertFalse(CallCrypto.verify(keys.getString("B"), "offer", callId, offer.getString("eph"), offer.getString("sig")))
        assertFalse(CallCrypto.verify(keys.getString("A"), "answer", callId, offer.getString("eph"), offer.getString("sig")))
        assertFalse(CallCrypto.verify(keys.getString("A"), "offer", callId, answer.getString("eph"), offer.getString("sig")))
        // Our own signatures (random k) verify too.
        val me = ChatCrypto.newKeyPair()
        val sig = CallCrypto.sign(me.private, "offer", callId, offer.getString("eph"))
        assertTrue(CallCrypto.verify(ChatCrypto.b64(me.public.encoded), "offer", callId, offer.getString("eph"), sig))
    }

    @Test
    fun ecdhRootTag() {
        assertEquals(call.getString("ecdh"), CallCrypto.hex(CallCrypto.ecdh(offerPriv, answer.getString("eph"))))
        assertEquals(call.getString("ecdh"), CallCrypto.hex(CallCrypto.ecdh(answerPriv, offer.getString("eph"))))
        assertEquals(call.getString("root"), CallCrypto.hex(root()))
        // Both sides arrive at the same root.
        assertEquals(call.getString("root"), CallCrypto.hex(CallCrypto.root(answerPriv, offer.getString("eph"), callId, offer.getString("eph"), answer.getString("eph"))))
        assertEquals(call.getString("tag"), CallCrypto.hex(CallCrypto.tag(callId)))
        assertTrue(CallCrypto.isCallId(callId))
        assertTrue(CallCrypto.isCallId(CallCrypto.newCallId()))
        assertFalse(CallCrypto.isCallId("-"))
    }

    @Test
    fun epochs() {
        val k = CallCrypto.Keys(root())
        val ep = call.getJSONArray("epochs")
        assertEquals(3, ep.length())
        for (i in 0 until ep.length()) {
            val x = ep.getJSONObject(i)
            val e = x.getInt("epoch")
            assertEquals("chain $e", x.getString("chain"), CallCrypto.hex(k.chain(e)!!))
            val keys = k.epoch(e)!!
            assertEquals("o2aKey $e", x.getString("o2aKey"), CallCrypto.hex(keys.o2aKey))
            assertEquals("o2aSalt $e", x.getString("o2aSalt"), CallCrypto.hex(keys.o2aSalt))
            assertEquals("a2oKey $e", x.getString("a2oKey"), CallCrypto.hex(keys.a2oKey))
            assertEquals("a2oSalt $e", x.getString("a2oSalt"), CallCrypto.hex(keys.a2oSalt))
        }
        // Forgetting: epoch 0 and the root are gone, later epochs still come out the same.
        k.forgetBefore(1)
        assertNull(k.epoch(0)); assertNull(k.chain(0))
        assertEquals(ep.getJSONObject(2).getString("o2aKey"), CallCrypto.hex(k.epoch(2)!!.o2aKey))
        assertEquals(ep.getJSONObject(1).getString("chain"), CallCrypto.hex(k.chain(1)!!))
    }

    @Test
    fun framesSealExactly() {
        val k = CallCrypto.Keys(root())
        val tag = CallCrypto.tag(callId)
        val frames = call.getJSONArray("frames")
        for (i in 0 until frames.length()) {
            val f = frames.getJSONObject(i)
            val p = CallCrypto.seal(k, f.getString("dir") == "o2a", tag, f.getInt("type"), f.getLong("seq"), f.getLong("ts"), h(f.getString("payload")))
            assertEquals("frame $i", f.getString("packet"), CallCrypto.hex(p))
            assertEquals(CallCrypto.unhex(call.getString("tag")).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }, CallCrypto.peekTag(p))
        }
        try { CallCrypto.seal(k, true, tag, 0, 0, 0, ByteArray(1200 - 28 + 1)); fail("too big") } catch (e: IllegalArgumentException) {}
    }

    @Test
    fun receiveInOrder() {
        val frames = call.getJSONArray("frames")
        val steps = call.getJSONArray("receive")
        val tag = CallCrypto.tag(callId)
        // B (answerer) opens offerer-to-answerer packets; A opens the other direction. One receiver per side.
        val rx = mapOf("B" to CallCrypto.Receiver(CallCrypto.Keys(root()), true, tag), "A" to CallCrypto.Receiver(CallCrypto.Keys(root()), false, tag))
        assertTrue(steps.length() >= 15)
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val packet = if (s.isNull("packet")) frames.getJSONObject(s.getInt("frame")).getString("packet") else s.getString("packet")
            val result = try {
                val o = rx.getValue(s.getString("receiver")).open(h(packet))
                if (!s.isNull("frame")) {
                    val f = frames.getJSONObject(s.getInt("frame"))
                    assertEquals(f.getLong("seq"), o.seq); assertEquals(f.getLong("ts"), o.ts); assertEquals(f.getInt("type"), o.type)
                    assertArrayEquals(h(f.getString("payload")), o.payload)
                }
                "ok"
            } catch (e: CallCrypto.Dropped) { e.why }
            assertEquals("step $i", s.getString("result"), result)
        }
    }

    @Test
    fun windowEdges() {
        val tag = CallCrypto.tag(callId)
        val keys = CallCrypto.Keys(root())
        val r = CallCrypto.Receiver(keys, true, tag)
        fun pkt(seq: Long) = CallCrypto.seal(keys, true, tag, CallCrypto.AUDIO, seq, 0, byteArrayOf(1, 2, 3))
        fun res(seq: Long) = try { r.open(pkt(seq)); "ok" } catch (e: CallCrypto.Dropped) { e.why }
        assertEquals("ok", res(2000))
        assertEquals("ok", res(2000 - 1023))       // bottom of the window
        assertEquals("too old", res(2000 - 1024))
        assertEquals("duplicate", res(2000 - 1023))
        assertEquals("ok", res(1500))
        assertEquals("ok", res(3000))                 // moves the window; 1500 drops out
        assertEquals("too old", res(1500))
        assertEquals("ok", res(2500))
        assertEquals("duplicate", res(2500))
        // Short or foreign bytes.
        assertEquals("size", try { r.open(ByteArray(27)); "ok" } catch (e: CallCrypto.Dropped) { e.why })
        val wrongVersion = pkt(3001).also { it[0] = 0x80.toByte() }
        assertEquals("version", try { r.open(wrongVersion); "ok" } catch (e: CallCrypto.Dropped) { e.why })
        val wrongType = pkt(3002).also { it[1] = 7 }   // 0 to 6 are known (3 to 6 from c2, 5.8.4)
        assertEquals("type", try { r.open(wrongType); "ok" } catch (e: CallCrypto.Dropped) { e.why })
        assertEquals(epochOfBottom(3000), r.neededEpoch())
    }

    /** The c2 part of the vectors (5.8): video fragments, report, NACK and the sealed packets, as Huginn checks them. */
    @Test
    fun c2Vectors() {
        val x = call.getJSONObject("c2")
        val frags = io.github.ravenor907.muninn.net.call.VideoWire.split(x.getInt("stream"), x.getInt("frame"), x.getBoolean("key"), x.getInt("rotation"), h(x.getString("vp8")))
        assertEquals((0 until x.getJSONArray("fragments").length()).map { x.getJSONArray("fragments").getString(it) }, frags.map { CallCrypto.hex(it) })
        val r = x.getJSONObject("report")
        assertEquals(r.getString("bytes"), CallCrypto.hex(io.github.ravenor907.muninn.net.call.VideoWire.encodeReport(io.github.ravenor907.muninn.net.call.VideoWire.Report(
            r.getInt("loss"), r.getBoolean("camera"), r.getBoolean("screen"), r.getInt("jitter"), r.getInt("kbps"), r.getInt("rtt")))))
        val n = x.getJSONObject("nack")
        val idx = n.getJSONArray("indexes")
        assertEquals(n.getString("bytes"), CallCrypto.hex(io.github.ravenor907.muninn.net.call.VideoWire.encodeNack(
            io.github.ravenor907.muninn.net.call.VideoWire.Nack(n.getInt("stream"), n.getInt("frame"), (0 until idx.length()).map { idx.getInt(it) }))))
        val k = CallCrypto.Keys(root())
        val tag = CallCrypto.tag(callId)
        val toB = CallCrypto.Receiver(CallCrypto.Keys(root()), true, tag)
        val toA = CallCrypto.Receiver(CallCrypto.Keys(root()), false, tag)
        val packets = x.getJSONArray("packets")
        assertEquals(6, packets.length())
        for (i in 0 until packets.length()) {
            val f = packets.getJSONObject(i)
            val o2a = f.getString("dir") == "o2a"
            assertEquals("c2 packet $i", f.getString("packet"), CallCrypto.hex(CallCrypto.seal(k, o2a, tag, f.getInt("type"), f.getLong("seq"), f.getLong("ts"), h(f.getString("payload")))))
            val o = (if (o2a) toB else toA).open(h(f.getString("packet")))
            assertEquals(f.getInt("type"), o.type); assertArrayEquals(h(f.getString("payload")), o.payload)
        }
    }

    private fun epochOfBottom(high: Long) = CallCrypto.epochOf(high - 1023)
}
