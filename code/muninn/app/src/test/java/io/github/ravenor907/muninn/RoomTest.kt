package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.RoomLogic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** The room part of shared/vectors/proto06-vectors.json (voice rooms and group calls), which Huginn checks too. */
class RoomTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText())
    private val r = v.getJSONObject("room")
    private val keys = v.getJSONObject("keys")
    private val msgs = r.getJSONObject("messages")
    private fun state(name: String) = Proto06.parseState(v.getJSONObject("group").getJSONObject("states").getJSONObject(name).getJSONObject("state"))
    private fun msg(name: String) = msgs.getJSONObject(name).getJSONObject("msg")

    @Test
    fun limitsAndDomain() {
        assertEquals(RoomLogic.DOMAIN, v.getJSONObject("domains").getString("room"))
        assertEquals(RoomLogic.MAX_PEOPLE, r.getInt("maxPeople"))
        assertEquals(RoomLogic.FRESH_PAST_MS, r.getLong("freshPastMs"))
        assertEquals(RoomLogic.FRESH_AHEAD_MS, r.getLong("freshAheadMs"))
        assertEquals(RoomLogic.PRESENCE_TTL_MS, r.getLong("presenceTtlMs"))
        assertTrue(RoomLogic.isId(r.getString("voiceRoom"), 8))
        assertTrue(RoomLogic.isGroupCall(r.getString("groupCall")))
        assertFalse(RoomLogic.isRoomId("main"))
        assertTrue(RoomLogic.isGroupCall(RoomLogic.newGroupCallId()))
        assertFalse(RoomLogic.full(6))
        assertTrue(RoomLogic.full(7))
    }

    @Test
    fun signedBytes() {
        for (k in msgs.keys()) {
            val x = msgs.getJSONObject(k)
            val m = x.getJSONObject("msg")
            val canon = Proto06.canon(m.keys().asSequence().filter { it != "sig" }.toList().associateWith { m.opt(it) })
            assertEquals("signed $k", x.getString("signed"), RoomLogic.DOMAIN + "\n" + canon)
        }
    }

    @Test
    fun accept() {
        val a = r.getJSONArray("accept")
        assertTrue(a.length() > 0)
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val st = if (c.isNull("state")) null else state(c.getString("state"))
            val got = RoomLogic.accept(st, keys.getString(c.getString("me")), keys.getString(c.getString("from")), msg(c.getString("msg")), c.getLong("now"))
            assertEquals("accept ${c.getString("msg")} at ${c.getString("me")}", c.getString("result"), got)
        }
    }

    @Test
    fun presence() {
        val p = RoomLogic.Presence()
        val g = r.getString("g")
        val ch = r.getString("voiceRoom")
        val steps = r.getJSONArray("presence")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val now = s.getLong("now")
            var changed = false
            if (!s.isNull("msg")) changed = p.apply(keys.getString(s.getString("from")), msg(s.getString("msg")), now)
            assertEquals("changed, step $i", s.getBoolean("changed"), changed)
            val want = s.getJSONArray("in").let { a -> (0 until a.length()).map { keys.getString(a.getString(it)) }.sorted() }
            assertEquals("in, step $i", want, p.inRoom(g, ch, now))
        }
    }

    @Test
    fun glare() {
        val gl = r.getJSONArray("glare")
        for (i in 0 until gl.length()) {
            val x = gl.getJSONObject(i)
            assertEquals(keys.getString(x.getString("wins")), RoomLogic.offerWins(keys.getString(x.getString("a")), keys.getString(x.getString("b"))))
        }
    }

    @Test
    fun madeHereVerifies() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val me = ChatCrypto.b64(kp.public.encoded)
        val m = RoomLogic.make(kp.private, "vjoin", r.getString("g"), r.getString("groupCall"), 1761500400000L)
        assertEquals(null, RoomLogic.check(m))
        assertTrue(RoomLogic.verify(me, m))
        assertFalse(RoomLogic.verify(keys.getString("A"), m))
    }

    @Test
    fun roomOfferParses() {
        // An offer inside a room carries g and ch; Muninn hands those to the room, never rings for them.
        val s = CallLogic.parse("x", """{"t":"vring","call":"-","g":"a","ch":"b","ts":1,"sig":"x"}""")
        assertTrue(s.voiceRoom)
    }

    /** Cap v2 (6.8): heartbeats every 10 s; Reconnecting after 20 s without news, gone after 60 s. Without v2: 25 minutes. */
    @Test
    fun heartbeats() {
        val join = msg("joinB")
        val who = keys.getString("B")
        val g = join.getString("g"); val ch = join.getString("ch"); val t0 = RoomLogic.ts(join)
        val p = RoomLogic.Presence()
        assertTrue(p.apply(who, join, t0, beats = true, fresh = true))
        assertEquals(1, p.state(g, ch, who, t0 + 10_000))
        assertEquals(2, p.state(g, ch, who, t0 + RoomLogic.QUIET_MS + 1))
        assertEquals(listOf(who), p.inRoom(g, ch, t0 + RoomLogic.QUIET_MS + 1))
        assertEquals(0, p.state(g, ch, who, t0 + RoomLogic.GONE_MS + 1))
        assertTrue(p.inRoom(g, ch, t0 + RoomLogic.GONE_MS + 1).isEmpty())
        assertTrue(p.rooms(g, t0 + RoomLogic.GONE_MS + 1).isEmpty())
        // The next heartbeat keeps them in.
        assertTrue(p.apply(who, JSONObject(join.toString()).put("ts", t0 + RoomLogic.HEARTBEAT_MS), t0 + 55_000, beats = true))
        assertEquals(1, p.state(g, ch, who, t0 + 70_000))
        // A late copy brings nobody back.
        val late = RoomLogic.Presence()
        assertTrue(late.apply(who, join, t0, beats = true, fresh = false))
        assertEquals(0, late.state(g, ch, who, t0))
        // An app without v2: 25 minutes, as before.
        val old = RoomLogic.Presence()
        old.apply(who, join, t0)
        assertEquals(1, old.state(g, ch, who, t0 + 20 * 60_000L))
        assertEquals(0, old.state(g, ch, who, t0 + 26 * 60_000L))
        assertEquals(1, RoomLogic.heard(5_000, true)); assertEquals(2, RoomLogic.heard(25_000, true))
        assertEquals(0, RoomLogic.heard(61_000, true)); assertEquals(1, RoomLogic.heard(61_000, false))
        assertTrue(RoomLogic.HEARTBEAT_CAP in Proto06.caps(Proto06.CAPS))
    }
}

