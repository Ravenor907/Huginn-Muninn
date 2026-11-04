package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.HiddenTls
import io.github.ravenor907.muninn.net.LinkReplay
import io.github.ravenor907.muninn.net.LinkWire
import io.github.ravenor907.muninn.net.LocalSendRules
import io.github.ravenor907.muninn.net.PeerKeys
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.RingKey
import io.github.ravenor907.muninn.net.RingTags
import io.github.ravenor907.muninn.net.RingWire
import io.github.ravenor907.muninn.net.call.LockedRingLimit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * 1.42 package D (shared/docs/protocol.md 15, 21.3, 23.2): caps h1 (hidden certificates named over the ratchet),
 * r2 (a ring tag per caller) and l5 (the phone pings its companions; pings more than 36 hours off are answered).
 * Huginn's --stability-selftest checks the same shapes.
 */
class Fix142DTest {
    private val fpA = "A".repeat(64)
    private val fpB = "0123456789ABCDEF".repeat(4)

    @Test fun capsSayH1AndR2() {
        val caps = Proto06.CAPS.split(' ')
        assertTrue(RingWire.HIDDEN_CAP in caps && RingWire.TAG_CAP in caps)
        // Appended at the end, after p2.
        assertTrue(caps.indexOf(RingWire.HIDDEN_CAP) > caps.indexOf("p2") && caps.indexOf(RingWire.TAG_CAP) > caps.indexOf("p2"))
        assertEquals("l5", LinkWire.L5)
    }

    // ---- r2: the ring tag

    @Test fun ringCarriesItsTag() {
        val ring = ChatCrypto.newKeyPair()
        val pub = ChatCrypto.b64(ring.public.encoded)
        val tag = RingWire.newTag()
        assertTrue(RingWire.tagOk(tag))
        val content = RingWire.ringContent(pub, "AAECAwQFBgcICQoLDA0ODw", 42L, tag)
        val r = RingWire.openRingTagged(content, ring)!!
        assertEquals("AAECAwQFBgcICQoLDA0ODw", r.call)
        assertEquals(42L, r.ts)
        assertEquals(tag, r.rt)
        assertFalse(r.old)
        // Older callers send none; a malformed one counts as none; the plain open still works for either.
        assertEquals("", RingWire.openRingTagged(RingWire.ringContent(pub, "AAECAwQFBgcICQoLDA0ODw", 42L), ring)!!.rt)
        assertEquals("", RingWire.openRingTagged(RingWire.ringContent(pub, "AAECAwQFBgcICQoLDA0ODw", 42L, "XYZ"), ring)!!.rt)
        assertEquals("AAECAwQFBgcICQoLDA0ODw" to 42L, RingWire.openRingContent(content, ring))
        assertFalse(JSONObject(RingWire.ringPlain("AAECAwQFBgcICQoLDA0ODw", 1L)).has("rt"))
        assertEquals(tag, JSONObject(RingWire.ringPlain("AAECAwQFBgcICQoLDA0ODw", 1L, tag)).getString("rt"))
        // The hash kept outside the vault: 32 hex, the same for the same tag, never the tag itself.
        assertEquals(32, RingWire.tagHash(tag).length)
        assertEquals(RingWire.tagHash(tag), RingWire.tagHash(tag))
        assertNotEquals(tag, RingWire.tagHash(tag))
    }

    @Test fun replacedRingKeyOpensAsOld() {
        val f = java.io.File.createTempFile("ring", ".json").also { it.delete() }
        try {
            val k = RingKey(f)
            val before = k.publicKey
            k.renew()
            val tag = RingWire.newTag()
            val viaOld = RingWire.ringContent(before, "AAECAwQFBgcICQoLDA0ODw", 7L, tag)
            val r = k.openTagged(viaOld)!!
            assertTrue(r.old)
            assertEquals(tag, r.rt)
            // Round 17's plain open still takes only the current key.
            assertNull(k.open(viaOld))
            assertFalse(k.openTagged(RingWire.ringContent(k.publicKey, "AAECAwQFBgcICQoLDA0ODw", 7L))!!.old)
            // Past the 2 days the old key is gone.
            assertNull(k.openTagged(viaOld, System.currentTimeMillis() + RingKey.OLD_MS + 1))
        } finally { f.delete() }
    }

    @Test fun ringTagsAllowAndTakeBack() {
        val allow = java.io.File.createTempFile("ring-allow", ".json").also { it.delete() }
        try {
            var vault: String? = null
            val tags = RingTags(allow, { vault }, { vault = it })
            assertEquals(RingTags.Who.NONE, tags.judge(""))
            val a = tags.tagFor("A")
            assertEquals(a, tags.tagFor("A"))
            val b = tags.tagFor("B")
            assertNotEquals(a, b)
            assertEquals(RingTags.Who.ALLOWED, tags.judge(a))
            assertEquals(RingTags.Who.REVOKED, tags.judge(RingWire.newTag()))
            // Locked: a fresh instance reads only the hashes outside the vault (the vault can't be read).
            val locked = RingTags(allow, { null }, { throw IllegalStateException("locked") })
            assertEquals(RingTags.Who.ALLOWED, locked.judge(a))
            assertFalse(allow.readText().contains(a))
            // Blocked: their tag stops at once; the other stays; a later tag for them is new.
            tags.revoke("A")
            assertEquals(RingTags.Who.REVOKED, tags.judge(a))
            assertEquals(RingTags.Who.REVOKED, RingTags(allow, { null }, { }).judge(a))
            assertEquals(RingTags.Who.ALLOWED, tags.judge(b))
            assertNotEquals(a, tags.tagFor("A"))
            // A lost allowed file is written again from the vault once it's open.
            allow.delete()
            val again = RingTags(allow, { vault }, { vault = it })
            again.sync()
            assertEquals(RingTags.Who.ALLOWED, RingTags(allow, { null }, { }).judge(b))
        } finally { allow.delete() }
    }

    @Test fun lockedRingLimitIsPerCaller() {
        val l = LockedRingLimit()
        val t = 1_000_000L
        repeat(3) { assertTrue(l.take(t + it, "a")) }
        assertFalse(l.take(t + 5, "a"))
        // Another caller, and untagged rings, still ring.
        assertTrue(l.take(t + 6, "b"))
        assertTrue(l.take(t + 7))
        assertTrue(l.take(t + 10 * 60_000L, "a"))
    }

    @Test fun announceCarriesTagAndFingerprints() {
        val pub = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val tag = RingWire.newTag()
        val r = JSONObject().put("rk", pub).put("rp", "ab".repeat(32)).put("rt", tag).put("hf", JSONArray(listOf(fpA, fpB)))
        val a = RingWire.announceOf(r, 1_761_500_000L)
        assertEquals(tag, a.rt)
        assertEquals(listOf(fpA, fpB), a.hf)
        // A tag without a ring key is ignored; bad fingerprints or too many: "hf" ignored as a whole.
        assertNull(RingWire.announceOf(JSONObject().put("rt", tag), 0).rt)
        assertNull(RingWire.hfOf(JSONArray(listOf(fpA.lowercase()))))
        assertNull(RingWire.hfOf(JSONArray(listOf(fpA, fpB, fpA, fpB, fpA))))
        assertNull(RingWire.hfOf(JSONArray()))
        assertNull(RingWire.hfOf("x"))
        assertEquals(listOf(fpA), RingWire.hfOf(JSONArray(listOf(fpA, fpA))))
        // Kept per contact; a ring key that comes without a tag means none; "hf" alone replaces the list.
        var stored: String? = null
        val keys = PeerKeys({ stored }, { stored = it })
        keys.take("C", a)
        assertEquals(tag, PeerKeys({ stored }, { stored = it }).get("C")!!.rt)
        assertEquals(listOf(fpA, fpB), PeerKeys({ stored }, { stored = it }).get("C")!!.hf)
        keys.take("C", RingWire.announceOf(JSONObject().put("hf", JSONArray(listOf(fpB))), 0))
        assertEquals(listOf(fpB), keys.get("C")!!.hf)
        assertEquals(tag, keys.get("C")!!.rt)
        keys.take("C", RingWire.announceOf(JSONObject().put("rk", pub).put("rp", "ab".repeat(32)), 0))
        assertNull(keys.get("C")!!.rt)
    }

    // ---- h1: hidden certificates

    @Test fun pinsFromSavedAndNamed() {
        assertEquals(fpA, LocalSendRules.pins(fpA.lowercase(), emptyList()))
        // An address kept before 1.41 has no fingerprint: asked once the contact named one.
        assertEquals("", LocalSendRules.pins("", emptyList()))
        assertFalse(LocalSendRules.mayAsk("https", LocalSendRules.pins("", emptyList())))
        assertEquals("$fpB,$fpA", LocalSendRules.pins("", listOf(fpB, fpA)))
        assertTrue(LocalSendRules.mayAsk("https", LocalSendRules.pins("", listOf(fpB))))
        assertEquals("$fpA,$fpB", LocalSendRules.pins(fpA, listOf(fpA, fpB, "short")))
    }

    @Test fun hiddenCertificateRotation() {
        val h = LocalSendRules.HOUR
        val d = LocalSendRules.DAY
        val t = 1_761_500_000_000L
        assertTrue(LocalSendRules.rotateDue(0L, t, false))
        assertFalse(LocalSendRules.rotateDue(t - h + 1, t, true))
        assertTrue(LocalSendRules.rotateDue(t - h, t, true))
        assertFalse(LocalSendRules.rotateDue(t - d + 1, t, false))
        assertTrue(LocalSendRules.rotateDue(t - d, t, false))
        // The clock went back more than a day: a new one.
        assertTrue(LocalSendRules.rotateDue(t + d + 1, t, false))
    }

    @Test fun hiddenNextBecomesNow() {
        val dir = java.nio.file.Files.createTempDirectory("tls-hidden").toFile()
        try {
            val h = HiddenTls(dir)
            val now = h.current()
            assumeNotNull(now)
            val next = h.upcoming()!!
            assertNotEquals(now!!.fingerprint, next.fingerprint)
            // Kept between runs.
            assertEquals(next.fingerprint, HiddenTls(dir).upcoming()!!.fingerprint)
            assertTrue(h.rotate(42L))
            assertEquals(next.fingerprint, h.current()!!.fingerprint)
            assertNotEquals(next.fingerprint, h.upcoming()!!.fingerprint)
            assertEquals(42L, h.since())
            assertEquals(next.fingerprint, HiddenTls(dir).current()!!.fingerprint)
        } finally { dir.deleteRecursively() }
    }

    // ---- l5: clocks more than 36 hours apart

    private fun stamped(t: String, lts: Long, pn: String? = null): JSONObject =
        JSONObject(LinkReplay.stamp(JSONObject().put("t", t).apply { if (pn != null) put("pn", pn) }.toString(), lts))

    @Test fun offsetOnlyFromOwnChallenge() {
        val r = LinkReplay()
        val now = 1_761_500_000_000L
        val off = 3 * 86_400_000L   // the companion is 3 days ahead
        // Its frames are too far off, and an unasked pong teaches nothing.
        assertFalse(r.take("PC", stamped("sync", now + off), now))
        assertTrue(r.skewed("PC", stamped("sync", now + off), now))
        assertFalse(r.take("PC", stamped("pong", now + off, "0123456789abcdef"), now))
        assertEquals(0L, r.offsetOf("PC"))
        // A pong with the wrong pn, or over a relay, or too late: nothing.
        r.pinged("PC", "0123456789abcdef", now)
        assertFalse(r.take("PC", stamped("pong", now + off, "fedcba9876543210"), now + 1_000))
        assertFalse(r.take("PC", stamped("pong", now + off, "0123456789abcdef"), now + 1_000, counts = false))
        assertFalse(r.take("PC", stamped("pong", now + off, "0123456789abcdef"), now + LinkReplay.CHALLENGE_MS + 1))
        assertEquals(0L, r.offsetOf("PC"))
        // Its own pong, straight, in time: learned, and its frames are taken.
        r.pinged("PC", "0123456789abcdef", now)
        assertTrue(r.take("PC", stamped("pong", now + off + 2_000, "0123456789abcdef"), now + 2_000))
        assertTrue(kotlin.math.abs(r.offsetOf("PC") - off) < 1_000)
        assertTrue(r.take("PC", stamped("sync", now + off + 3_000), now + 3_000))
        assertFalse(r.skewed("PC", stamped("sync", now + off + 3_000), now + 3_000))
        // Still more than 36 hours from both: dropped (an old frame handed over on the LAN).
        assertFalse(r.take("PC", stamped("sync", now - 2 * 86_400_000L), now + 4_000))
        // Kept between runs, and forgotten with the device.
        val back = LinkReplay().apply { load(r.save()) }
        assertEquals(r.offsetOf("PC"), back.offsetOf("PC"))
        back.forget("PC")
        assertEquals(0L, back.offsetOf("PC"))
    }

    @Test fun skewedPingsAnsweredOnceAMinute() {
        val r = LinkReplay()
        val now = 1_761_500_000_000L
        assertTrue(r.answerDue("phone", now))
        assertFalse(r.answerDue("phone", now + 30_000))
        assertTrue(r.answerDue("phone", now + LinkReplay.ANSWER_MS))
        assertTrue(r.answerDue("tablet", now))
        assertTrue(r.pingDue("PC", now))
        assertFalse(r.pingDue("PC", now + 60_000))
        assertTrue(r.pingDue("PC", now + LinkReplay.PROBE_MS))
        // The pong says the ping's pn back.
        assertEquals("0123456789abcdef", LinkWire.pong(stamped("ping", now, "0123456789abcdef")).getString("pn"))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(LinkWire.pingNonce()))
        assertNotNull(LinkWire.pingNonce())
    }
}
