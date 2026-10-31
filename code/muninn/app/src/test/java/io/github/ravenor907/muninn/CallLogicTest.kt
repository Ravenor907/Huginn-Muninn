package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.call.CallCrypto
import io.github.ravenor907.muninn.net.call.CallLogic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Call signaling, the relay-only candidate rule and the speaking indicator (the jitter buffer: JitterTest). */
class CallLogicTest {
    private val me = ChatCrypto.newKeyPair()
    private val myKey = ChatCrypto.b64(me.public.encoded)
    private val relay = InetSocketAddress(InetAddress.getByName("104.30.1.2"), 61000)

    @Test
    fun offerAnswerRoundTrip() {
        val id = CallCrypto.newCallId()
        val eph = ChatCrypto.b64(CallCrypto.newEphemeral().public.encoded)
        val o = CallLogic.offer(id, eph, CallCrypto.sign(me.private, "offer", id, eph), 1234, relay, 40000, false)
        val s = CallLogic.parse(myKey, o.toString())
        assertEquals("offer", s.t); assertEquals(id, s.call); assertEquals(eph, s.eph); assertEquals(1234L, s.ts)
        assertEquals(relay, s.relay); assertEquals(40000, s.lanPort); assertEquals("opus16", s.codec)
        // Signed by someone else: refused.
        val other = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        try { CallLogic.parse(other, o.toString()); fail() } catch (e: CallLogic.Bad) { assertEquals("bad signature", e.message) }
        // An answer signature can't be passed off as an offer.
        val a = CallLogic.answer(id, eph, CallCrypto.sign(me.private, "answer", id, eph), null, null, true)
        assertTrue(CallLogic.parse(myKey, a.toString()).ble)
        try { CallLogic.parse(myKey, a.put("t", "offer").put("ts", 1).toString()); fail() } catch (e: CallLogic.Bad) {}
    }

    @Test
    fun onlyRelayCandidates() {
        val id = CallCrypto.newCallId()
        val msg = JSONObject().put("t", "cands").put("call", id).put("cands", org.json.JSONArray()
            .put(JSONObject().put("t", "host").put("a", "192.168.1.20").put("p", 40312))
            .put(JSONObject().put("t", "srflx").put("a", "203.0.113.7").put("p", 51820))
            .put(JSONObject().put("t", "relay").put("a", "evil.example").put("p", 3478))
            .put(JSONObject().put("t", "relay").put("a", "104.30.1.2").put("p", 61000)))
        assertEquals(relay, CallLogic.parse(myKey, msg.toString()).relay)
        // Host and srflx alone give no internet path at all.
        val noRelay = JSONObject().put("t", "cands").put("call", id).put("cands", org.json.JSONArray()
            .put(JSONObject().put("t", "srflx").put("a", "203.0.113.7").put("p", 51820)))
        assertNull(CallLogic.parse(myKey, noRelay.toString()).relay)
        // What we send carries only the relay.
        val ours = CallLogic.candsMsg(id, relay, null).getJSONArray("cands")
        assertEquals(1, ours.length()); assertEquals("relay", ours.getJSONObject(0).getString("t"))
        assertEquals(0, CallLogic.cands(null).length())
        assertNull(CallLogic.literal("turn.cloudflare.com")); assertNull(CallLogic.literal("300.1.1.1"))
        assertEquals(InetAddress.getByName("2001:db8::1"), CallLogic.literal("2001:db8::1"))
    }

    /** The Wi-Fi port goes out as a `lan` candidate and as `lp`; Huginn's messages and the single-field forms are read. */
    @Test
    fun crossApp() {
        val id = "wMHCw8TFxsfIycrLzM3Ozw"
        // Exactly what Huginn's callselftest (crossApp) reads as Muninn's.
        val muninn = JSONObject("""{"t":"cands","call":"$id","cands":[{"t":"relay","a":"104.30.1.2","p":61000},{"t":"lan","p":40000}],"lp":40000}""")
        val ours = CallLogic.candsMsg(id, relay, 40000)
        assertEquals(plain(muninn), plain(ours))
        // An offer says it the same way.
        val eph = ChatCrypto.b64(CallCrypto.newEphemeral().public.encoded)
        val o = CallLogic.offer(id, eph, CallCrypto.sign(me.private, "offer", id, eph), 1234, relay, 40000, false)
        assertEquals(plain(muninn.getJSONArray("cands")), plain(o.getJSONArray("cands"))); assertEquals(40000, o.getInt("lp"))
        // Huginn as Qt writes it (keys sorted): relay, lan and lp.
        val huginn = """{"call":"$id","cands":[{"a":"104.30.1.2","p":61000,"t":"relay"},{"p":40003,"t":"lan"}],"lp":40003,"t":"cands"}"""
        CallLogic.parse(myKey, huginn).let { assertEquals(relay, it.relay); assertEquals(40003, it.lanPort) }
        // Single-field forms: a lan candidate alone (Huginn), lp alone (Muninn). lp wins when both differ.
        assertEquals(40002, CallLogic.parse(myKey, """{"t":"cands","call":"$id","cands":[{"t":"lan","p":40002}]}""").lanPort)
        assertEquals(40001, CallLogic.parse(myKey, """{"t":"cands","call":"$id","cands":[],"lp":40001}""").lanPort)
        assertEquals(40001, CallLogic.parse(myKey, """{"t":"cands","call":"$id","cands":[{"t":"lan","p":40002}],"lp":40001}""").lanPort)
        assertNull(CallLogic.parse(myKey, """{"t":"cands","call":"$id","cands":[{"t":"lan","p":0}]}""").lanPort)
        // An offer from Huginn (ts as a plain integer, Qt's key order) parses with every field.
        val sig = CallCrypto.sign(me.private, "offer", id, eph)
        val hOffer = """{"ble":false,"call":"$id","cands":[{"a":"104.30.1.2","p":61000,"t":"relay"},{"p":40003,"t":"lan"}],"codec":"opus16","eph":"$eph","lp":40003,"sig":"$sig","t":"offer","ts":1761500000000}"""
        CallLogic.parse(myKey, hOffer).let {
            assertEquals("offer", it.t); assertEquals(1761500000000L, it.ts); assertEquals("opus16", it.codec)
            assertEquals(relay, it.relay); assertEquals(40003, it.lanPort); assertFalse(it.ble)
        }
    }

    @Test
    fun malformedAndVoiceRoom() {
        for (bad in listOf("nope", "{}", """{"t":"ring"}""", """{"t":"dance","call":"wMHCw8TFxsfIycrLzM3Ozw"}""", """{"t":"ring","call":"short"}"""))
            try { CallLogic.parse(myKey, bad); fail(bad) } catch (e: CallLogic.Bad) {}
        assertTrue(CallLogic.parse(myKey, """{"t":"vjoin","g":"x","ch":"y","call":"-"}""").voiceRoom)
        val h = CallLogic.parse(myKey, CallLogic.hangup("wMHCw8TFxsfIycrLzM3Ozw", "weird").toString())
        assertEquals("hangup", h.why)
        assertTrue(CallLogic.parse(myKey, CallLogic.mute("wMHCw8TFxsfIycrLzM3Ozw", true).toString()).on)
        assertTrue(CallLogic.fresh(1_000_000, 1_000_000 + 60_000)); assertFalse(CallLogic.fresh(1_000_000, 1_000_000 + 60_001))
        assertTrue(CallLogic.fresh(1_120_000, 1_000_000)); assertFalse(CallLogic.fresh(1_120_001, 1_000_000))
    }

    /** 5.8: c2 fields in offer and answer, and direct candidates only with internet addresses. */
    @Test
    fun c2AndDirectCandidates() {
        val id = CallCrypto.newCallId()
        val eph = ChatCrypto.b64(CallCrypto.newEphemeral().public.encoded)
        val srflx = InetSocketAddress(InetAddress.getByName("203.0.113.7"), 51820)
        val o = CallLogic.offer(id, eph, CallCrypto.sign(me.private, "offer", id, eph), 1234, relay, null, false,
            CallLogic.Extras(vp8 = true, video = true, p2p = true), listOf(CallLogic.Direct("srflx", srflx)))
        assertTrue(o.getBoolean("c2")); assertEquals("vp8", o.getString("vc")); assertTrue(o.getBoolean("vid")); assertTrue(o.getBoolean("p2p"))
        val s = CallLogic.parse(myKey, o.toString())
        assertTrue(s.c2 && s.vp8 && s.video && s.p2p)
        assertEquals(relay, s.relay); assertEquals(listOf(srflx), s.direct)
        // Without c2: none of it, and its srflx/host are never used.
        val plain = CallLogic.offer(id, eph, CallCrypto.sign(me.private, "offer", id, eph), 1234, relay, null, false)
        assertFalse(plain.has("c2") || plain.has("p2p") || plain.has("vc"))
        assertFalse(CallLogic.parse(myKey, plain.toString()).c2)
        // Local addresses are never direct candidates: a peer can't make this phone send into its LAN.
        val msg = JSONObject().put("t", "cands").put("call", id).put("cands", org.json.JSONArray()
            .put(JSONObject().put("t", "host").put("a", "192.168.1.20").put("p", 40312))
            .put(JSONObject().put("t", "host").put("a", "fe80::1").put("p", 40312))
            .put(JSONObject().put("t", "host").put("a", "fd00::5").put("p", 40312))
            .put(JSONObject().put("t", "srflx").put("a", "127.0.0.1").put("p", 40312))
            .put(JSONObject().put("t", "srflx").put("a", "evil.example").put("p", 40312))
            .put(JSONObject().put("t", "host").put("a", "2001:db8::17").put("p", 40312))
            .put(JSONObject().put("t", "srflx").put("a", "203.0.113.7").put("p", 51820)))
        assertEquals(listOf(InetSocketAddress(InetAddress.getByName("2001:db8::17"), 40312), srflx), CallLogic.parse(myKey, msg.toString()).direct)
        assertTrue(CallLogic.isLocal(InetAddress.getByName("100.64.1.1")) && !CallLogic.isLocal(InetAddress.getByName("8.8.8.8")))
        // The relay rests while a free path is fresh (5.8.3): no relay destination then.
        val wifi = CallLogic.Path(CallLogic.Kind.WIFI, InetSocketAddress(InetAddress.getByName("192.168.1.9"), 4000))
        val r = CallLogic.Path(CallLogic.Kind.RELAY, relay)
        assertEquals(listOf(CallLogic.Kind.WIFI), CallLogic.udpSends(listOf(r, wifi), listOf(relay), restRelay = true).map { it.kind })
        assertEquals(2, CallLogic.udpSends(listOf(r, wifi), listOf(relay)).size)
        val d = CallLogic.Path(CallLogic.Kind.DIRECT, srflx)
        d.lastIn = System.currentTimeMillis(); d.lastPing = d.lastIn
        assertFalse(CallLogic.pingDue(d, d.lastIn + 200)); assertTrue(CallLogic.pingDue(d, d.lastIn + 250))
    }

    /** 5.8.2: a replayed duplicate from another address never becomes a path; a new authentic packet may. */
    @Test
    fun duplicatesTeachNoAddress() {
        val real = InetSocketAddress(InetAddress.getByName("203.0.113.7"), 51820)
        val thief = InetSocketAddress(InetAddress.getByName("198.51.100.9"), 40000)
        val paths = java.util.concurrent.CopyOnWriteArrayList(listOf(CallLogic.Path(CallLogic.Kind.DIRECT, real)))
        assertNull(CallLogic.markLive(paths, CallLogic.Kind.DIRECT, thief, fresh = false, now = 1000))
        assertEquals(1, paths.size)
        assertNull(CallLogic.markLive(paths, CallLogic.Kind.WIFI, thief, fresh = false, now = 1000))
        assertEquals(1, paths.size)
        assertEquals(1000L, CallLogic.markLive(paths, CallLogic.Kind.DIRECT, real, fresh = false, now = 1000)!!.lastIn)
        // A packet that opened as new: their NAT really sends from there.
        assertEquals(thief, CallLogic.markLive(paths, CallLogic.Kind.DIRECT, thief, fresh = true, now = 2000)!!.addr)
        assertEquals(2, paths.size)
    }

    @Test
    fun speaking() {
        val s = CallLogic.Speaking()
        val quiet = ShortArray(320); val loud = ShortArray(320) { (if (it % 2 == 0) 3000 else -3000).toShort() }
        assertFalse(s.feed(loud, 320, 0))      // one loud frame isn't enough
        assertTrue(s.feed(loud, 320, 20))
        assertTrue(s.feed(quiet, 320, 300))   // held 300 ms
        assertFalse(s.feed(quiet, 320, 321))
        assertTrue(CallLogic.dbfs(loud, 320) > -45); assertTrue(CallLogic.dbfs(quiet, 320) < -100)
    }

    @Test
    fun pings() {
        val p = CallLogic.Path(CallLogic.Kind.RELAY, relay)
        val t0 = p.added
        assertTrue(CallLogic.pingDue(p, t0 + 50))
        p.lastPing = t0 + 50
        assertFalse(CallLogic.pingDue(p, t0 + 60)); assertTrue(CallLogic.pingDue(p, t0 + 100))
        p.lastPing = t0 + 3_000
        assertFalse(CallLogic.pingDue(p, t0 + 3_200)); assertTrue(CallLogic.pingDue(p, t0 + 3_250))
        p.lastIn = t0 + 3_300; p.lastPing = t0 + 3_300
        assertTrue(p.live(t0 + 7_299)); assertFalse(p.live(t0 + 7_300))
        assertFalse(CallLogic.pingDue(p, t0 + 4_000)); assertTrue(CallLogic.pingDue(p, t0 + 4_300))
    }

    /** JSON as plain maps and lists, so two objects compare by content whatever their key order (Android's org.json has no toMap). */
    private fun plain(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is org.json.JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        is Number -> v.toLong()
        else -> v
    }

    /** Answered on the locked ringing screen: the offer is answered only within the caller's 45 s ring, else missed. */
    @Test fun lockedAnswerOnlyWhileTheCallerRings() {
        val now = 1_761_500_000_000L
        assertTrue(CallLogic.lockedAnswerOk(now, now))
        assertTrue(CallLogic.lockedAnswerOk(now - CallLogic.RING_MS, now))
        assertFalse(CallLogic.lockedAnswerOk(now - CallLogic.RING_MS - 1, now))
        assertFalse(CallLogic.lockedAnswerOk(now - 90_000, now))
        assertFalse(CallLogic.lockedAnswerOk(now + 121_000, now))
    }
}
