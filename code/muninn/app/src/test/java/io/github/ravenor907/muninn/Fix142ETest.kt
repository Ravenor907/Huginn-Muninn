package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.ReplyHold
import io.github.ravenor907.muninn.net.call.CallPrivacy
import io.github.ravenor907.muninn.screens.SelfShape
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.TimeAnchor
import io.github.ravenor907.muninn.tv.TvUpd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.42 (E): the call screen under Muninn's lock, refused notification replies, kid trusted time after a restart, minors. */
class Fix142ETest {

    // ---- 1. the call screen while Muninn is locked

    @Test fun callNamesHideWhileMuninnIsLocked() {
        // Raised counts always; a vault still closed counts with a lock; due counts only while it rings.
        assertTrue(CallPrivacy.locked(raised = true, hasPassword = false, vaultClosed = false, due = false, ringing = false))
        assertTrue(CallPrivacy.locked(raised = false, hasPassword = true, vaultClosed = true, due = false, ringing = false))
        assertTrue(CallPrivacy.locked(raised = false, hasPassword = true, vaultClosed = false, due = true, ringing = true))
        assertFalse(CallPrivacy.locked(raised = false, hasPassword = true, vaultClosed = false, due = true, ringing = false))
        assertFalse(CallPrivacy.locked(raised = false, hasPassword = false, vaultClosed = true, due = true, ringing = true))
        assertFalse(CallPrivacy.locked(raised = false, hasPassword = true, vaultClosed = false, due = false, ringing = true))

        // Muninn's lock hides the name even with previews on and the phone unlocked; after unlock it shows again.
        assertTrue(CallPrivacy.hide(panic = false, muninnLocked = true, previews = true, keyguard = false))
        assertFalse(CallPrivacy.hide(panic = false, muninnLocked = false, previews = true, keyguard = false))
        // What hid it before still does.
        assertTrue(CallPrivacy.hide(panic = true, muninnLocked = false, previews = true, keyguard = false))
        assertTrue(CallPrivacy.hide(panic = false, muninnLocked = false, previews = false, keyguard = true))
        assertFalse(CallPrivacy.hide(panic = false, muninnLocked = false, previews = true, keyguard = true))
    }

    @Test fun hiddenCallTitles() {
        assertEquals("Astrid", CallPrivacy.title("Astrid", hide = false, ringing = true, video = false))
        assertEquals("Incoming call", CallPrivacy.title("Astrid", hide = true, ringing = true, video = false))
        assertEquals("Incoming video call", CallPrivacy.title("Astrid", hide = true, ringing = true, video = true))
        assertEquals("Muninn call", CallPrivacy.title("Astrid", hide = true, ringing = false, video = true))
        // The line under a hidden ringing title doesn't say "Incoming call" twice.
        assertEquals("Muninn", CallPrivacy.statusUnder("Incoming call", "Incoming call"))
        assertEquals("Ringing", CallPrivacy.statusUnder("Muninn call", "Ringing"))
        assertEquals("Incoming call", CallPrivacy.statusUnder("Astrid", "Incoming call"))
    }

    // ---- 2. a notification reply refused while locked

    @Test fun refusedReplyFate() {
        assertEquals(ReplyHold.Kept.DRAFT, ReplyHold.fate(draftsOpen = true, havePub = false, room = false))
        assertEquals(ReplyHold.Kept.SEALED, ReplyHold.fate(draftsOpen = false, havePub = true, room = true))
        assertEquals(ReplyHold.Kept.DROPPED, ReplyHold.fate(draftsOpen = false, havePub = false, room = true))
        assertEquals(ReplyHold.Kept.DROPPED, ReplyHold.fate(draftsOpen = false, havePub = true, room = false))
        assertEquals("Reply not saved: unlock to reply.", ReplyHold.note(ReplyHold.Kept.DROPPED))
        assertEquals(ReplyHold.WAITS, ReplyHold.note(ReplyHold.Kept.SEALED))
        assertEquals(ReplyHold.WAITS, ReplyHold.note(ReplyHold.Kept.DRAFT))
        assertTrue(ReplyHold.room(0, 0, 100))
        assertFalse(ReplyHold.room(ReplyHold.HELD_MAX, 0, 1))
        assertFalse(ReplyHold.room(1, ReplyHold.HELD_BYTES - 10, 11))
    }

    @Test fun sealedReplyOpensOnlyWithItsKey() {
        val keys = ChatCrypto.newKeyPair()
        val pub = ChatCrypto.b64(keys.public.encoded)
        val box = ReplyHold.seal(pub, "g:g1/main", "on my way\nsoon")
        // Nothing readable outside the vault: neither the text nor the chat.
        assertFalse(box.contains("on my way"))
        assertFalse(box.contains("g:g1"))
        assertEquals("g:g1/main" to "on my way\nsoon", ReplyHold.open(box, keys))
        assertNull(ReplyHold.open(box, ChatCrypto.newKeyPair()))
        assertNull(ReplyHold.open("not json", keys))
        // Two seals of the same reply differ (a fresh key and nonce each time).
        assertFalse(box == ReplyHold.seal(pub, "g:g1/main", "on my way\nsoon"))
    }

    // ---- 3. kid trusted time right after a restart

    private val t0 = 1_800_000_000_000L
    private val h = 3_600_000L

    @Test fun oneRelayAloneNeverSetsTheFirstTime() {
        val r = KidRules.addReading(emptyMap(), "relay:a", TimeAnchor(t0, 1_000))
        assertNull(KidRules.clockFrom(null, r, 1_000))
        // The device's own clock far off: still waits.
        assertNull(KidRules.clockFrom(null, r, 1_000, t0 + 3 * h))
        // The device's own clock agrees: two sources agree, so it's taken (the relay's time, not the device's).
        assertEquals(TimeAnchor(t0, 1_000), KidRules.clockFrom(null, r, 1_000, t0 + 60_000))
    }

    @Test fun twoAgreeingSourcesSetTheFirstTime() {
        var r = KidRules.addReading(emptyMap(), "relay:a", TimeAnchor(t0, 1_000))
        r = KidRules.addReading(r, "relay:b", TimeAnchor(t0 + 4_000, 3_000))
        // Carried to elapsed 3000: a says t0 + 2000, b says t0 + 4000; the median of the two.
        val a = KidRules.clockFrom(null, r, 3_000)!!
        assertEquals(3_000L, a.at)
        assertTrue(a.wall in t0 + 2_000..t0 + 4_000)
        // Two that disagree: wait.
        val split = KidRules.addReading(KidRules.addReading(emptyMap(), "relay:a", TimeAnchor(t0, 1_000)), "relay:b", TimeAnchor(t0 + 5 * h, 1_000))
        assertNull(KidRules.clockFrom(null, split, 1_000))
    }

    @Test fun aParentsConfigSetsTheFirstTimeUnlessContradicted() {
        val p = KidRules.addReading(emptyMap(), "parent:dad", TimeAnchor(t0 - 2 * h, 1_000))
        assertEquals(TimeAnchor(t0 - 2 * h, 1_000), KidRules.clockFrom(null, p, 1_000))
        // A relay already disagrees: wait for more (a stale config can't set it alone).
        val against = KidRules.addReading(p, "relay:a", TimeAnchor(t0, 1_000))
        assertNull(KidRules.clockFrom(null, against, 1_000))
        // ...unless the device's clock sides with the relay: two agree.
        assertEquals(TimeAnchor(t0, 1_000), KidRules.clockFrom(null, against, 1_000, t0 + 1_000))
        // A second relay that agrees with the first wins over the parent's older time.
        val two = KidRules.addReading(against, "relay:b", TimeAnchor(t0 + 1_000, 1_000))
        val a = KidRules.clockFrom(null, two, 1_000)!!
        assertTrue(a.wall in t0..t0 + 1_000)
    }

    @Test fun onceKnownTheOldRulesApply() {
        // With a trusted time already, one relay still moves it forward up to 10 minutes (unchanged).
        val cur = TimeAnchor(t0, 1_000)
        val r = KidRules.addReading(emptyMap(), "relay:a", TimeAnchor(t0 + 1_000 + 60_000, 2_000))
        assertEquals(TimeAnchor(t0 + 61_000, 2_000), KidRules.clockFrom(cur, r, 2_000))
        val firstOnly = KidRules.firstClock(mapOf("relay:a" to t0), null)
        assertNull(firstOnly)
        assertNotNull(KidRules.firstClock(mapOf("parent:p" to t0), null))
    }

    // ---- 4. minors

    @Test fun selfViewShapeFollowsTheDisplay() {
        // A phone (natural portrait) turned sideways, and a tablet (natural landscape) upright and turned.
        assertFalse(SelfShape.wide(1080, 2400, 0))
        assertTrue(SelfShape.wide(1080, 2400, 1))
        assertTrue(SelfShape.wide(2560, 1600, 0))
        assertFalse(SelfShape.wide(2560, 1600, 3))
        assertTrue(SelfShape.wide(2560, 1600, 2))
    }

    @Test fun clearedUpdateApkIsNoSpace() {
        assertTrue(TvUpd.apkGone(exists = false, bytes = 0))
        assertTrue(TvUpd.apkGone(exists = true, bytes = 0))
        assertFalse(TvUpd.apkGone(exists = true, bytes = 30_000_000))
    }
}
