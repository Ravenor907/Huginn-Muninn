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
}
