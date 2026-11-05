package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.HoldTimer
import io.github.ravenor907.muninn.ui.HoldTimer.Release
import io.github.ravenor907.muninn.ui.holdLabel
import io.github.ravenor907.muninn.ui.holdSpoken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hold to confirm: 5 seconds held confirms once; letting go early does nothing. */
class HoldTimerTest {
    @Test
    fun fiveSecondsConfirmsOnce() {
        val t = HoldTimer()
        assertEquals(5000L, t.durationMs)
        t.press(1000)
        assertTrue(t.holding)
        assertFalse(t.tick(1000))
        assertEquals(0.5f, t.progress(3500), 0.001f)
        assertFalse(t.tick(5999))
        assertTrue(t.tick(6000))
        // Still held: never twice.
        assertFalse(t.tick(7000))
        assertEquals(1f, t.progress(9000), 0.001f)
        assertEquals(Release.NONE, t.release(9000))
        assertFalse(t.holding)
    }

    @Test
    fun earlyReleaseCancels() {
        val t = HoldTimer()
        t.press(0)
        assertFalse(t.tick(4000))
        assertEquals(Release.EARLY, t.release(4999))
        assertFalse(t.holding)
        assertEquals(0f, t.progress(10_000), 0.001f)
        // A new hold starts from zero.
        t.press(20_000)
        assertFalse(t.tick(24_000))
        assertTrue(t.tick(25_000))
    }

    @Test
    fun keyRepeatsDoNotRestart() {
        val t = HoldTimer()
        t.press(0)
        t.press(3000)   // a held key's repeat
        t.press(4000)
        assertTrue(t.tick(5000))
    }

    @Test
    fun letGoRightAtTheEndStillConfirms() {
        // No tick saw the time pass (a slow frame): letting go after it confirms.
        val t = HoldTimer()
        t.press(0)
        assertEquals(Release.CONFIRMED, t.release(5200))
    }

    @Test
    fun releaseWithoutPressDoesNothing() {
        val t = HoldTimer()
        assertEquals(Release.NONE, t.release(100))
        assertFalse(t.tick(100_000))
        assertEquals(0f, t.progress(100_000), 0.001f)
    }

    @Test
    fun customDuration() {
        val t = HoldTimer(1000)
        t.press(0)
        assertEquals(0.25f, t.progress(250), 0.001f)
        assertTrue(t.tick(1000))
    }

    @Test
    fun words() {
        assertEquals("Hold to block", holdLabel("Block"))
        assertEquals("Hold to delete chat", holdLabel("Delete chat"))
        assertEquals("Hold to erase", holdLabel("Hold to erase"))
        assertEquals("Hold for 5 seconds to block Mom", holdSpoken("Block Mom"))
    }
}
