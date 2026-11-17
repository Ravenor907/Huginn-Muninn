package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Nostr
import io.github.ravenor907.muninn.net.Schnorr
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checked against BIP-340's official values and events made by the BIP's reference code. */
class NostrTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("nostr-vectors.json")!!.readText())

    @Test
    fun officialBip340Vector() {
        val o = v.getJSONObject("official")
        val sk = Schnorr.unhex(o.getString("seckey"))
        assertEquals(o.getString("pubkey"), Schnorr.hex(Schnorr.publicKey(sk)))
        val sig = Schnorr.sign(Schnorr.unhex(o.getString("msg")), sk, Schnorr.unhex(o.getString("aux")))
        assertEquals(o.getString("sig"), Schnorr.hex(sig))
        assertTrue(Schnorr.verify(Schnorr.unhex(o.getString("msg")), Schnorr.unhex(o.getString("pubkey")), sig))
    }

    @Test
    fun signsNostrEventsExactlyLikeTheReference() {
        val tags = JSONArray().put(JSONArray().put("p").put(v.getString("recipient")))
        assertEquals(v.getString("serialized"), Nostr.serialize(v.getString("pubkey"), v.getLong("created_at"), v.getInt("kind"), tags, v.getString("content")))
        val ev = Nostr.event(v.getInt("kind"), tags, v.getString("content"), Schnorr.unhex(v.getString("seckey")), v.getLong("created_at"), Schnorr.unhex(v.getString("aux")))
        assertEquals(v.getString("pubkey"), ev.getString("pubkey"))
        assertEquals(v.getString("id"), ev.getString("id"))
        assertEquals(v.getString("sig"), ev.getString("sig"))
        assertEquals(v.getString("id"), Nostr.idOf(ev))
    }

    @Test
    fun rejectsForgedSignatures() {
        val sk = Schnorr.newSecret()
        val msg = ByteArray(32) { 1 }
        val sig = Schnorr.sign(msg, sk)
        assertTrue(Schnorr.verify(msg, Schnorr.publicKey(sk), sig))
        sig[5] = (sig[5].toInt() xor 1).toByte()
        assertFalse(Schnorr.verify(msg, Schnorr.publicKey(sk), sig))
    }

    /** A linked device's frames go to at most 2 relays, the open ones first (protocol.md section 15). */
    @Test
    fun linkFramesGoToTwoRelaysOpenOnesFirst() {
        val all = Nostr.DEFAULT_RELAYS
        assertEquals(all, Nostr.pickRelays(all, emptySet(), 0))
        assertEquals(all.take(2), Nostr.pickRelays(all, emptySet(), 2))
        assertEquals(listOf(all[3], all[0]), Nostr.pickRelays(all, setOf(all[3]), 2))
        assertEquals(listOf(all[2], all[4]), Nostr.pickRelays(all, setOf(all[4], all[2]), 2))
    }

    /** A queued linked-device frame goes out again on a relay that reconnects, as in Huginn's nostr.cpp. */
    @Test
    fun queuedLinkFrameResentOnReconnect() {
        val all = Nostr.DEFAULT_RELAYS
        val to = listOf(all[0], all[1])
        assertTrue(Nostr.resendLow(all[0], to, setOf(all[0])))                 // one of its relays
        assertTrue(Nostr.resendLow(all[3], to, setOf(all[3])))                 // its relays are all down: any relay takes it
        assertFalse(Nostr.resendLow(all[3], to, setOf(all[3], all[1])))        // one of its own relays is open
        assertTrue(Nostr.resendLow(all[4], emptyList(), setOf(all[4], all[0]))) // meant for every relay
    }

    /** relay.nostr.band stopped answering: the default has another in its place, and an old saved default follows. */
    @Test
    fun theDeadRelayIsReplacedAndOnlyTheOldDefaultFollows() {
        val now = Nostr.DEFAULT_RELAYS
        val old = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.nostr.band")
        assertEquals(5, now.size)
        assertFalse("wss://relay.nostr.band" in now)
        // The other four and their order stay, so older apps still meet newer ones on them.
        assertEquals(old.take(4), now.take(4))
        assertEquals(old.take(4), now.filter { it !in Nostr.NEWER_RELAYS })
        // Nothing saved: the default. The old default saved (an older install, a backup): the new one.
        assertEquals(now, Nostr.relaysFrom(null))
        assertEquals(now, Nostr.relaysFrom(old.joinToString("\n")))
        // The same five in another order: only the dead one changes, in its place.
        val shuffled = listOf(old[4], old[2], old[0], old[3], old[1])
        assertEquals(listOf(now[4], old[2], old[0], old[3], old[1]), Nostr.relaysFrom(shuffled.joinToString("\n")))
        // Anyone's own list stays exactly as it is, the dead relay too.
        val own = listOf("wss://relay.damus.io", "wss://relay.nostr.band", "wss://my.relay.example")
        assertEquals(own, Nostr.relaysFrom(own.joinToString("\n")))
        assertEquals(old.take(4), Nostr.relaysFrom(old.take(4).joinToString("\n")))
        assertEquals(old + "wss://my.relay.example", Nostr.relaysFrom((old + "wss://my.relay.example").joinToString("\n")))
    }

    @Test
    fun theStateFileReadsBackTheSame() {
        val seen = io.github.ravenor907.muninn.net.SeenIds()
        val ids = listOf("00000000000000ff", "ffffffffffffffff", "0123456789abcdef").map { it + "cd".repeat(24) }
        ids.forEach { seen.add(it) }
        val eose = mapOf("wss://relay.one" to 1_761_500_000L, "wss://two.example" to 1_761_500_123L)
        val o = JSONObject(String(Nostr.stateJson(seen, eose, "ab".repeat(32))))
        val a = o.getJSONArray("seen")
        assertEquals(seen.toList(), List(a.length()) { a.getString(it) })
        assertEquals(1_761_500_000L, o.getJSONObject("eose").getLong("wss://relay.one"))
        assertEquals(1_761_500_123L, o.getJSONObject("eose").getLong("wss://two.example"))
        assertEquals("ab".repeat(32), o.getString("pub"))
        // Nothing seen yet, no mailbox yet.
        val empty = JSONObject(String(Nostr.stateJson(io.github.ravenor907.muninn.net.SeenIds(), emptyMap(), null)))
        assertEquals(0, empty.getJSONArray("seen").length())
        assertEquals(0, empty.getJSONObject("eose").length())
        assertFalse(empty.has("pub"))
    }

    @Test
    fun anOutboxEntrysIdIsReadTheSameWayEitherWay() {
        val id = "0123456789abcdef".repeat(4)
        val big = "x".repeat(70_000)
        // As made here (the id first): read off the front.
        assertEquals(id, Nostr.msgId("[\"EVENT\",{\"id\":\"$id\",\"pubkey\":\"${"ab".repeat(32)}\",\"content\":\"$big\"}]"))
        // Any other order is parsed as before.
        assertEquals(id, Nostr.msgId("[\"EVENT\",{\"kind\":1059,\"id\":\"$id\"}]"))
        // Not an id, or not an event: never a wrong id.
        assertEquals("zz", Nostr.msgId("[\"EVENT\",{\"id\":\"zz\"}]"))
        assertEquals("", Nostr.msgId("[\"EVENT\",{\"id\":\"$id"))
        assertEquals("", Nostr.msgId("not json"))
        val upper = id.uppercase()
        assertEquals(upper, Nostr.msgId("[\"EVENT\",{\"id\":\"$upper\"}]"))
    }

    @Test
    fun theStateFileIsReadQuicklyAndTheSameAsPlainJson() {
        val seen = io.github.ravenor907.muninn.net.SeenIds()
        val r = java.util.Random(7)
        repeat(3000) { seen.add("%016x".format(r.nextLong()) + "cd".repeat(24)) }
        val eose = mapOf("wss://relay.one" to 1_761_500_000L)
        val st = Nostr.readState(Nostr.stateJson(seen, eose, "ab".repeat(32)))
        val back = io.github.ravenor907.muninn.net.SeenIds().apply { addKeys(st.seen, st.count) }
        assertEquals(seen.toList(), back.toList())
        assertEquals(1_761_500_000L, st.eose!!.getLong("wss://relay.one"))
        assertEquals("ab".repeat(32), st.pub)
        // Older files: full 64-character ids, no mailbox; or the parts in another order (read as plain JSON).
        val full = listOf("ff".repeat(32), "0123456789abcdef".repeat(4))
        for (text in listOf("{\"seen\":[\"${full[0]}\",\"${full[1]}\"],\"eose\":{\"wss://a\":5}}",
                            "{\"eose\":{\"wss://a\":5},\"seen\":[\"${full[0]}\",\"${full[1]}\"]}",
                            "{\"seen\":[\"${full[0]}\", \"${full[1]}\"],\"eose\":{\"wss:\\/\\/a\":5}}")) {
            val o = Nostr.readState(text.toByteArray())
            assertEquals(2, o.count)
            assertEquals(listOf(-1L, 0x0123456789abcdefL), o.seen.take(2))
            assertEquals(5L, o.eose!!.optLong("wss://a"))
            assertEquals("", o.pub)
        }
        // Something that isn't an id is skipped, as before; nothing seen at all; a damaged file throws (its copy is read).
        assertEquals(1, Nostr.readState("{\"seen\":[\"zz\",\"${full[0]}\"]}".toByteArray()).count)
        assertEquals(0, Nostr.readState("{\"seen\":[],\"eose\":{}}".toByteArray()).count)
        assertTrue(try { Nostr.readState("{\"seen\":[\"${full[0]}".toByteArray()); false } catch (_: Exception) { true })
    }
}
