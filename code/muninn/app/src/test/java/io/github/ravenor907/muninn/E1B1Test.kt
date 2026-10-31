package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.PeerKeys
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.RingKey
import io.github.ravenor907.muninn.net.RingWire
import io.github.ravenor907.muninn.net.WrapKeys
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair

/** Caps b1 and e1 (shared/docs/protocol-0.6.md section 23) against shared/vectors/e1b1-vectors.json. */
class E1B1Test {
    private val v = JSONObject(javaClass.classLoader!!.getResource("e1b1-vectors.json")!!.readText())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun pair(pub: String, priv: String) = KeyPair(ChatCrypto.publicFrom(pub), ChatCrypto.privateFrom(priv))

    @Test fun capsSayB1AndE1() {
        assertTrue(RingWire.RING_CAP in Proto06.caps(Proto06.CAPS))
        assertTrue(RingWire.WRAP_CAP in Proto06.caps(Proto06.CAPS))
    }

    @Test fun constantsMatch() {
        val c = v.getJSONObject("constants")
        assertEquals(c.getLong("wrapUseS"), RingWire.WRAP_USE_S)
        assertEquals(c.getLong("wrapKeepS"), RingWire.WRAP_KEEP_S)
        assertEquals(c.getLong("wrapNewS"), RingWire.WRAP_NEW_S)
        assertEquals(c.getLong("wrapAheadS"), RingWire.WRAP_AHEAD_S)
        assertEquals(c.getLong("ringOldMs"), RingWire.RING_OLD_MS)
        assertEquals(c.getLong("ringAheadMs"), RingWire.RING_AHEAD_MS)
    }

    @Test fun wrapping() {
        val w = v.getJSONObject("wrap")
        val eph = pair(w.getString("ephPub"), w.getString("ephPriv"))
        val me = pair(w.getString("recipientPub"), w.getString("recipientPriv"))
        val day = pair(w.getString("dayPub"), w.getString("dayPriv"))
        val other = pair(w.getString("otherDayPub"), w.getString("otherDayPriv"))
        val k1 = ChatCrypto.sharedKey(eph.private, eph.public, me.public)
        val k2 = ChatCrypto.sharedKey(eph.private, eph.public, day.public)
        assertEquals(w.getString("k1"), hex(k1))
        assertEquals(w.getString("k2"), hex(k2))
        assertEquals(w.getString("key"), hex(RingWire.wrapKey(k1, k2)))
        assertEquals(w.getString("dayId"), RingWire.wrapId(w.getString("dayPub")))
        assertEquals(w.getString("otherDayId"), RingWire.wrapId(w.getString("otherDayPub")))
        // Sealing with the same one-off key and nonce gives the reference's wrapping, field for field.
        val sealed = RingWire.sealWrap(w.getString("recipientPub"), w.getString("dayPub"), w.getString("body").toByteArray(), eph,
            ChatCrypto.b64d(w.getString("nonce")))
        val outer = w.getJSONObject("outer")
        for (k in listOf("e", "w", "n", "d")) assertEquals(k, outer.getString(k), sealed.getString(k))
        val days = mapOf(w.getString("dayId") to day, w.getString("otherDayId") to other)
        assertArrayEquals(w.getString("body").toByteArray(), RingWire.openWrap(outer, me) { days[it] })
        // Once that day key is gone it doesn't open; nor does a changed one, or one naming another day key.
        assertNull(RingWire.openWrap(outer, me) { if (it == w.getString("otherDayId")) other else null })
        assertNull(RingWire.openWrap(w.getJSONObject("changed"), me) { days[it] })
        assertNull(RingWire.openWrap(w.getJSONObject("otherId"), me) { days[it] })
    }

    @Test fun ringEnvelope() {
        val r = v.getJSONObject("ring")
        val ring = pair(r.getString("ringPub"), r.getString("ringPriv"))
        val eph = pair(r.getString("ephPub"), r.getString("ephPriv"))
        val k = ChatCrypto.sharedKey(eph.private, eph.public, ring.public)
        assertEquals(r.getString("shared"), hex(k))
        assertEquals(r.getString("key"), hex(RingWire.ringKey(k)))
        val sealed = RingWire.sealRing(r.getString("ringPub"), r.getString("plain").toByteArray(), eph, ChatCrypto.b64d(r.getString("nonce")))
        val outer = r.getJSONObject("outer")
        for (f in listOf("e", "n", "d")) assertEquals(f, outer.getString(f), sealed.getString(f))
        assertArrayEquals(r.getString("plain").toByteArray(), RingWire.openRing(outer, ring))
        assertNull(RingWire.openRing(outer, pair(r.getString("wrongKeyPub"), r.getString("wrongKeyPriv"))))
        assertTrue(RingWire.ringOk(JSONObject(r.getString("plain"))))
        // The content a relay carries: padded, and it opens to the call id and time.
        val content = RingWire.ringContent(r.getString("ringPub"), "AAECAwQFBgcICQoLDA0ODw", 1761500130000L)
        assertEquals(0, content.length % 1024)
        assertEquals("AAECAwQFBgcICQoLDA0ODw" to 1761500130000L, RingWire.openRingContent(content, ring))
        assertNull(RingWire.openRingContent(content, pair(r.getString("wrongKeyPub"), r.getString("wrongKeyPriv"))))
    }

    @Test fun ringPlaintexts() {
        val p = v.getJSONObject("ringPlain")
        val good = p.getJSONArray("good")
        for (i in 0 until good.length()) assertTrue(good.getJSONObject(i).toString(), RingWire.ringOk(good.getJSONObject(i)))
        val refuse = p.getJSONArray("refuse")
        for (i in 0 until refuse.length()) assertFalse(refuse.getJSONObject(i).toString(), RingWire.ringOk(refuse.getJSONObject(i)))
        val fresh = v.getJSONArray("ringFresh")
        for (i in 0 until fresh.length()) {
            val c = fresh.getJSONObject(i)
            assertEquals(c.toString(), c.getBoolean("ring"), RingWire.ringFresh(c.getLong("ts"), c.getLong("now")))
        }
    }

    @Test fun announcedKeys() {
        val a = v.getJSONArray("announce")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val got = RingWire.announceOf(c.getJSONObject("fields"), c.getLong("now"))
            assertEquals(c.getString("name"), c.getBoolean("ring"), got.rk != null && got.rp != null)
            assertEquals(c.getString("name"), c.getBoolean("wrap"), got.wk != null)
        }
    }

    @Test fun dayKeyRules() {
        val use = v.getJSONArray("useWrap")
        for (i in 0 until use.length()) use.getJSONObject(i).let { assertEquals(it.toString(), it.getBoolean("use"), RingWire.mayUse(it.getLong("wt"), it.getLong("now"))) }
        val keep = v.getJSONArray("keepWrap")
        for (i in 0 until keep.length()) keep.getJSONObject(i).let { assertEquals(it.toString(), it.getBoolean("keep"), RingWire.keep(it.getLong("t"), it.getLong("now"))) }
        val rot = v.getJSONArray("rotate")
        for (i in 0 until rot.length()) rot.getJSONObject(i).let {
            val newest = if (it.isNull("newest")) null else it.getLong("newest")
            assertEquals(it.toString(), it.getBoolean("new"), RingWire.rotateDue(newest, it.getLong("now")))
        }
    }

    /** This device's day keys: one a day, the ones a sealed wrapping names open while kept, then they go. */
    @Test fun ownDayKeys() {
        var stored: String? = null
        val keys = WrapKeys({ stored }, { stored = it })
        val t0 = 1_761_500_000L
        val d0 = keys.current(t0)
        assertEquals(d0.id, keys.current(t0 + 3600).id)
        val d1 = keys.current(t0 + RingWire.DAY_S)
        assertTrue(d0.id != d1.id)
        // Written down: a new instance reads the same keys back.
        val again = WrapKeys({ stored }, { stored = it })
        assertNotNull(again.find(d0.id, t0 + RingWire.DAY_S))
        val me = ChatCrypto.newKeyPair()
        val wrapped = RingWire.sealWrap(ChatCrypto.b64(me.public.encoded), d0.pub, "hello".toByteArray())
        assertArrayEquals("hello".toByteArray(), RingWire.openWrap(wrapped, me) { again.find(it, t0 + 39 * RingWire.DAY_S) })
        assertNull(RingWire.openWrap(wrapped, me) { again.find(it, t0 + 40 * RingWire.DAY_S) })
        again.prune(t0 + 40 * RingWire.DAY_S)
        assertNull(again.find(d0.id, t0))
    }

    /** What contacts announce: the ring key as said, a day key only when newer; a sender uses one only within a week. */
    @Test fun peerKeys() {
        var stored: String? = null
        val keys = PeerKeys({ stored }, { stored = it })
        val k1 = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val k2 = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        keys.take("A", RingWire.Announce(k1, "a".repeat(64), k1, 1000))
        keys.take("A", RingWire.Announce(null, null, k2, 900))
        assertEquals(k1, keys.get("A")!!.wk)
        keys.take("A", RingWire.Announce(null, null, k2, 2000))
        assertEquals(k2, keys.get("A")!!.wk)
        assertEquals(k1, keys.get("A")!!.rk)
        assertEquals(k2, PeerKeys({ stored }, { stored = it }).wrapFor("A", 2000 + RingWire.WRAP_USE_S))
        assertNull(keys.wrapFor("A", 2001 + RingWire.WRAP_USE_S))
        keys.forget("A")
        assertNull(keys.get("A"))
    }

    /** The ring key outside the vault: made once, read back the same, and it opens what was sealed to it. */
    @Test fun ringKeyFile() {
        val f = java.io.File.createTempFile("ring", ".json").also { it.delete() }
        try {
            val a = RingKey(f)
            val pub = a.publicKey
            assertTrue(RingWire.keyOk(pub))
            assertTrue(Regex("^[0-9a-f]{64}$").matches(a.mailbox))
            val b = RingKey(f)
            assertEquals(pub, b.publicKey)
            assertEquals(a.mailbox, b.mailbox)
            val content = RingWire.ringContent(pub, "AAECAwQFBgcICQoLDA0ODw", 42L)
            assertEquals("AAECAwQFBgcICQoLDA0ODw" to 42L, b.open(content))
        } finally { f.delete() }
    }

    /** Room "file" goes through the ratchet only for a contact whose app says e1. */
    @Test fun filesRatchetWithE1() {
        val c = Contact("k", "n", null, false, ratchet = true, caps = setOf("r1", "f1"))
        assertFalse(RingWire.fileRatchet("file", c))
        assertTrue(RingWire.fileRatchet("file", c.copy(caps = c.caps + "e1")))
        assertFalse(RingWire.fileRatchet("file", c.copy(ratchet = false, caps = c.caps + "e1")))
        assertFalse(RingWire.fileRatchet("private", c.copy(caps = c.caps + "e1")))
        assertFalse(ChatWire.useRatchet("file", c.copy(caps = c.caps + "e1")))
    }

    /** A day key stamped more than a day ahead (the clock was set back) is dropped, and doesn't make a new key every call. */
    @Test fun clockSetBackMakesOneNewDayKey() {
        var stored: String? = null
        var writes = 0
        val keys = WrapKeys({ stored }, { stored = it; writes++ })
        val t0 = 1_761_500_000L
        val ahead = keys.current(t0 + 3 * RingWire.DAY_S)
        val now = keys.current(t0)
        assertTrue(now.id != ahead.id)
        assertNull(keys.find(ahead.id, t0))
        val w = writes
        assertEquals(now.id, keys.current(t0 + 60).id)
        assertEquals(now.id, keys.current(t0 + 3600).id)
        assertEquals(w, writes)
        assertFalse(RingWire.keep(t0 + RingWire.DAY_S + 1, t0))
        assertTrue(RingWire.keep(t0 + RingWire.DAY_S, t0))
        // Less than a day ahead: kept and used, still no new key every call.
        val k2 = WrapKeys({ null }, { })
        val soon = k2.current(t0 + 12 * 3600)
        assertEquals(soon.id, k2.current(t0).id)
        assertEquals(soon.id, k2.current(t0 + 1).id)
    }

    /** After a restore: a fresh day key whatever the newest's age, and the old ones still open. */
    @Test fun freshDayKey() {
        var stored: String? = null
        val keys = WrapKeys({ stored }, { stored = it })
        val t0 = 1_761_500_000L
        val d0 = keys.current(t0)
        val d1 = keys.fresh(t0 + 10)
        assertTrue(d0.id != d1.id)
        assertEquals(d1.id, keys.current(t0 + 20).id)
        assertNotNull(keys.find(d0.id, t0 + 20))
    }

    /** A wrapping for a day key not here waits only within 7 days of a restore, and only for an unknown key. */
    @Test fun waitForDayKeyAfterRestore() {
        val r = 1_761_500_000_000L
        assertTrue(RingWire.waitForDayKey(false, r, r + 1000))
        assertTrue(RingWire.waitForDayKey(false, r, r + RingWire.RESTORE_WAIT_MS - 1))
        assertFalse(RingWire.waitForDayKey(false, r, r + RingWire.RESTORE_WAIT_MS))
        assertFalse(RingWire.waitForDayKey(true, r, r + 1000))
        assertFalse(RingWire.waitForDayKey(false, 0L, r))
    }

    /** After a block: a new ring key and mailbox; the old one still opens and is listened on for 2 days, then goes. */
    @Test fun ringKeyRenewAfterBlock() {
        val f = java.io.File.createTempFile("ring", ".json").also { it.delete() }
        try {
            val a = RingKey(f)
            val oldPub = a.publicKey
            val oldBox = a.mailbox
            val t = 1_761_500_000_000L
            a.renew(t)
            assertTrue(a.publicKey != oldPub)
            assertTrue(a.mailbox != oldBox)
            assertEquals(listOf(a.mailbox, oldBox), a.mailboxes(t + 1000))
            val call = "AAECAwQFBgcICQoLDA0ODw"
            assertEquals(call to 7L, a.open(RingWire.ringContent(oldPub, call, 7L), t + 1000))
            assertEquals(call to 8L, a.open(RingWire.ringContent(a.publicKey, call, 8L), t + 1000))
            // Read back from the file the same.
            val b = RingKey(f)
            assertEquals(a.publicKey, b.publicKey)
            assertEquals(listOf(a.mailbox, oldBox), b.mailboxes(t + 1000))
            assertEquals(2, b.nostrSecrets(t + 1000).size)
            // 2 days on: only the new one.
            assertEquals(listOf(a.mailbox), b.mailboxes(t + RingKey.OLD_MS))
            assertNull(b.open(RingWire.ringContent(oldPub, call, 7L), t + RingKey.OLD_MS))
            assertFalse(b.prune(t + 1000))
            assertTrue(b.prune(t + RingKey.OLD_MS))
            assertEquals(listOf(b.mailbox), RingKey(f).mailboxes(t))
            // Erase everything: a new key next time.
            b.forget()
            assertFalse(f.exists())
        } finally { f.delete() }
    }
}
