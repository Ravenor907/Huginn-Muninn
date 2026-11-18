package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.CallHealth
import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.RoomLogic
import io.github.ravenor907.muninn.tv.KidVoice
import io.github.ravenor907.muninn.tv.TvRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.54: the TV call sound check, the microphone and speaker recovery rules, and the Kids' Device fixes around them. */
class TvCallFixesTest {
    private val setup = CallHealth.Setup("normal", "media", "HDMI ARC", 48_000, 3)

    private fun healthy(secs: Long) = CallHealth().apply {
        mic = 6; tried(6, "ok")
        micFrames.set(secs * 50); micReal.set(secs * 40); micZero.set(secs * 10)
        sent.set(secs * 30); came.set(secs * 30); playedReal.set(secs * 30); playedSilence.set(secs * 20)
    }

    @Test
    fun noReportUnder8ConnectedSeconds() {
        assertNull(CallHealth().report(7, setup, always = true))
    }

    @Test
    fun healthyCallReportsOnlyWhenAsked() {
        val h = healthy(60)
        assertTrue(h.problems(60).isEmpty())
        assertNull(h.report(60, setup, always = false))
        val text = h.report(60, setup, always = true)!!
        assertTrue(text.startsWith("TV call sound check (sent by Muninn): 60s connected. Looks fine."))
        assertTrue(text.contains("out HDMI ARC"))
        assertTrue(text.contains("source 6 voice recognition"))
    }

    @Test
    fun zerosAndSilenceAreTold() {
        val h = healthy(30).apply { micReal.set(0); micZero.set(1500); zerosSeen = true; playedReal.set(0); playedSilence.set(1500) }
        val p = h.problems(30)
        assertTrue(p.contains("microphone gave zeros"))
        assertTrue(p.contains("arrived but not played"))
        val text = h.report(30, setup, always = false)!!
        assertTrue(text.contains("(zero run seen)"))
    }

    @Test
    fun reportStaysShortWithManyRetries() {
        val h = CallHealth()
        repeat(500) { h.tried(6, "no open"); h.tried(1, "no open"); h.tried(7, "no start") }
        val text = h.report(600, setup, always = false)!!
        assertTrue(text.length <= CallHealth.MAX_CHARS)
        assertTrue(text.contains("6 no open x500"))
        assertTrue(text.contains("Speaker:"))   // the end is never cut off
    }

    @Test
    fun zeroRunReopensAfterFourSecondsThenBacksOff() {
        val z = CallLogic.ZeroRun()
        var t = 0L
        val at = ArrayList<Long>()
        repeat(60_000 / 20) { if (z.frame(true, t)) at += t; t += 20 }
        assertEquals(4_000L, at.first())
        assertTrue(at[1] - at[0] >= 8_000)
        assertTrue(at.size <= 4)
        assertTrue(z.seen)
    }

    @Test
    fun phoneZerosNeverRetakeInALoop() {
        // A phone microphone that gives nothing but zeros (or zeros after every restart) for a minute: a few retakes,
        // never one every 250 ms.
        val z = CallLogic.phoneZeroRun()
        var t = 0L
        val at = ArrayList<Long>()
        repeat(60_000 / 20) { if (z.frame(true, t)) at += t; t += 20 }
        assertEquals(1_500L, at.first())
        assertTrue(at.size <= 3)
        assertTrue(at.zipWithNext().all { (a, b) -> b - a >= 3_000 })
    }

    @Test
    fun phoneQuietMomentsNeverRetake() {
        // Short silences between words (zeros from a noise suppressor) never restart the microphone.
        val z = CallLogic.phoneZeroRun()
        var t = 0L
        repeat(100) { repeat(60) { assertFalse(z.frame(true, t)); t += 20 }; assertFalse(z.frame(false, t)); t += 20 }
    }

    @Test
    fun soundBreaksAZeroRun() {
        val z = CallLogic.ZeroRun()
        var t = 0L
        repeat(10) { repeat(150) { assertFalse(z.frame(true, t)); t += 20 }; assertFalse(z.frame(false, t)); t += 20 }
        assertFalse(z.seen)
    }

    @Test
    fun speakerRebuildsAfterTenFailedWrites() {
        val w = CallLogic.WriteErrors()
        var t = 0L
        repeat(9) { assertFalse(w.write(false, t)); t += 20 }
        assertTrue(w.write(false, t))
        repeat(9) { t += 20; assertFalse(w.write(false, t)) }
        t += 20; assertFalse(w.write(false, t))   // not again within a second
        assertFalse(w.write(true, t + 1))
    }

    @Test
    fun configFloorRefusesOldConfigsAfterRepairing() {
        // Paired again: runs v1, the floor is 20 from before.
        assertFalse(TvRules.overFloor(19, 1, 20))
        assertFalse(TvRules.overFloor(20, 1, 20))
        assertFalse(TvRules.overFloor(1, 1, 20))
        assertTrue(TvRules.overFloor(21, 1, 20))
        // Same version as it runs at the floor: 7.2's same-version rule still applies.
        assertTrue(TvRules.overFloor(20, 20, 20))
        assertFalse(TvRules.overFloor(19, null, 20))
    }

    @Test
    fun preReleaseCarriesWhenCoParentCapsAreUnknown() {
        assertTrue(KidVoice.preCarry(true, true, listOf(null, emptySet())))
        assertFalse(KidVoice.preCarry(true, true, listOf(setOf("k15"))))
        assertTrue(KidVoice.preCarry(true, true, listOf(setOf("k17"))))
        assertFalse(KidVoice.preCarry(false, true, listOf(null)))
        assertFalse(KidVoice.preCarry(true, false, listOf(null)))
    }

    @Test
    fun roomSkewIsSymmetricThreeMinutes() {
        val now = 1_000_000_000L
        assertTrue(RoomLogic.withinSkew(now - 300_000, now))
        assertTrue(RoomLogic.withinSkew(now + 300_000, now))
        assertFalse(RoomLogic.withinSkew(now - 300_001, now))
        assertFalse(RoomLogic.withinSkew(now + 300_001, now))
    }

    @Test
    fun lockedAnswerUsesThisClock() {
        val now = 1_000_000_000L
        assertTrue(CallLogic.lockedAnswerHere(now - 100_000, now))
        assertFalse(CallLogic.lockedAnswerHere(now - 106_000, now))
        assertNotNull(CallLogic.RING_MS)
    }
}
