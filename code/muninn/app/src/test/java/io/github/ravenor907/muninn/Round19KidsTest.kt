package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.tv.KidK14
import io.github.ravenor907.muninn.tv.KidMerge
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.KidVoice
import io.github.ravenor907.muninn.tv.ManagedTv
import io.github.ravenor907.muninn.tv.TimeAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 19: the Kids' Device fixes (the voice and older co-parents, trusted time after a restart, the voice in a merge). */
class Round19KidsTest {

    // ---- K19-1: "voice" only when every co-parent reads it

    @Test fun voiceLeftOutForAnOlderCoParent() {
        val k14Only = setOf("k1", "k11", "k14")
        val k15 = setOf("k1", "k11", "k14", "k15")
        // A 1.41 co-parent (k14, no k15): the config carries no voice, so their app still reads it.
        assertNull(KidVoice.toSend("m", true, listOf(k14Only)))
        assertNull(KidVoice.toSend("m", true, listOf(k15, k14Only)))
        // A co-parent this phone doesn't know (no caps): left out too.
        assertNull(KidVoice.toSend("m", true, listOf(null)))
        // Every co-parent says k15, or there is none: it goes.
        assertEquals("m", KidVoice.toSend("m", true, listOf(k15)))
        assertEquals("f", KidVoice.toSend("f", true, emptyList()))
        // Never to a device without k15, and nothing when none was picked.
        assertNull(KidVoice.toSend("m", false, listOf(k15)))
        assertNull(KidVoice.toSend("", true, listOf(k15)))
        assertFalse(KidVoice.allRead(listOf(k14Only)))
        assertTrue(KidVoice.allRead(listOf(k15, k15)))
    }

    // ---- K19-6: the voice survives a merge

    @Test fun voicePickSurvivesASameVersionMerge() {
        val base = ManagedTv("tv", v = 10, applied = 10, voice = "f")
        val mine = base.copy(voice = "m")
        val theirs = base.copy(v = 11, applied = 11, chat = 1)
        val m = KidMerge.merge(base, mine, theirs)
        assertEquals("m", m.voice)
        assertEquals(1, m.chat)
        // Their pick stands when this phone didn't change it.
        assertEquals("m", KidMerge.merge(base, base.copy(chat = 2), base.copy(voice = "m")).voice)
    }

    // ---- K19-2: trusted time after a restart, one relay only

    private val relayAt = 1_799_100_000_000L   // Monday 2027-01-04 22:00 UTC
    private val h = 3_600_000L
    private val bed = KidRules.quietDefault(KidRules.BED)   // 20:00 to 07:00 every day

    @Test fun oneRelayIsTakenAfterTheWait() {
        val r = KidRules.addReading(emptyMap(), "relay:a", TimeAnchor(relayAt, 1_000))
        val device = relayAt - 5 * h   // the child set the clock to 17:00
        // At first it waits (the device's clock disagrees and no second source came).
        assertNull(KidRules.clockFrom(null, r, 1_000, device, heldMs = 0))
        assertNull(KidRules.clockFrom(null, r, 61_000, device + 60_000, heldMs = 60_000))
        // After the wait the relay's time is taken, never the device's.
        val el = 1_000 + KidRules.FIRST_GRACE_MS
        val a = KidRules.clockFrom(null, r, el, device + KidRules.FIRST_GRACE_MS, heldMs = KidRules.FIRST_GRACE_MS)
        assertNotNull(a)
        assertEquals(relayAt + KidRules.FIRST_GRACE_MS, a!!.wall)
        // The quiet time is on by the relay's clock, though the device's own says 17:00.
        val tz = java.util.TimeZone.getTimeZone("UTC")
        assertEquals(bed, KidRules.quietOnDevice(listOf(bed), "UTC", a, el, device + KidRules.FIRST_GRACE_MS, tz))
        assertNull(KidRules.quietOnDevice(listOf(bed), "UTC", null, el, device + KidRules.FIRST_GRACE_MS, tz))
    }

    @Test fun afterTheWaitARelayBeatsAStaleParentConfig() {
        var r = KidRules.addReading(emptyMap(), "parent:dad", TimeAnchor(relayAt - 3 * h, 1_000))
        r = KidRules.addReading(r, "relay:a", TimeAnchor(relayAt, 1_000))
        assertNull(KidRules.clockFrom(null, r, 1_000, null, heldMs = 0))
        assertEquals(TimeAnchor(relayAt, 1_000), KidRules.clockFrom(null, r, 1_000, null, heldMs = KidRules.FIRST_GRACE_MS))
        // Only a parent's config: it was already taken at once (unchanged).
        val p = KidRules.addReading(emptyMap(), "parent:dad", TimeAnchor(relayAt, 1_000))
        assertEquals(TimeAnchor(relayAt, 1_000), KidRules.clockFrom(null, p, 1_000))
        // The default (no wait given) keeps the 1.42 rule: one relay alone waits.
        assertNull(KidRules.firstClock(mapOf("relay:a" to relayAt), null))
        assertEquals(relayAt, KidRules.firstClock(mapOf("relay:a" to relayAt), null, KidRules.FIRST_GRACE_MS))
    }

    @Test fun whileTheTimeIsUnsureOnlyParentsGetThrough() {
        // KidTv.allow uses KidK14.UNSURE as the quiet time while quiet times are set and no trusted time came.
        assertFalse(KidRules.passes("friend", listOf("mom"), KidK14.UNSURE))
        assertTrue(KidRules.passes("mom", listOf("mom"), KidK14.UNSURE))
        assertFalse(KidRules.groupRoomOk("friend", "gmsg", listOf("mom"), true, KidK14.UNSURE))
        assertTrue(KidRules.groupRoomOk("friend", "greceipt", listOf("mom"), true, KidK14.UNSURE))
    }
}
