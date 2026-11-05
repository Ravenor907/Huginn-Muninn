package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.update.FsiRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Full-screen calls" kept through updates: the session's ask, the after-update card and the local record. */
class FullScreenKeepTest {
    @Test
    fun sessionGrantsOnlyWhenOnNow() {
        assertTrue(FsiRules.sessionGrants(true))
        assertFalse(FsiRules.sessionGrants(false))
        assertFalse(FsiRules.sessionGrants(null))
    }

    @Test
    fun updatedByVersionOrFirstTimeByInstallTimes() {
        assertTrue(FsiRules.updated("1.41", "1.42", 0, 0))
        assertFalse(FsiRules.updated("1.42", "1.42", 0, 99))
        // Nothing seen yet: an update when Android's update time is after the first install.
        assertTrue(FsiRules.updated(null, "1.42", 100, 200))
        assertFalse(FsiRules.updated(null, "1.42", 100, 100))
    }

    @Test
    fun howComesFromTheNoteForThisVersionOnly() {
        assertEquals(FsiRules.HOW_SESSION, FsiRules.how(FsiRules.note("1.42", FsiRules.HOW_SESSION), "1.42"))
        assertEquals(FsiRules.HOW_OTHER, FsiRules.how(FsiRules.note("1.41", FsiRules.HOW_VIEW), "1.42"))
        assertEquals(FsiRules.HOW_OTHER, FsiRules.how(null, "1.42"))
        assertEquals(FsiRules.HOW_OTHER, FsiRules.how("garbage", "1.42"))
    }

    @Test
    fun cardOnlyAfterAnUpdateThatTurnedItOff() {
        assertTrue(FsiRules.showCard(34, false, true, true, false, null, "1.42"))
        // Before unknown (the first update with this check): shown.
        assertTrue(FsiRules.showCard(35, false, true, null, false, "1.41", "1.42"))
        // Off before: the person's choice, no card.
        assertFalse(FsiRules.showCard(34, false, true, false, false, null, "1.42"))
        // Still on, older Android, Kids' Device, no update, already shown for this version.
        assertFalse(FsiRules.showCard(34, false, true, true, true, null, "1.42"))
        assertFalse(FsiRules.showCard(33, false, true, true, false, null, "1.42"))
        assertFalse(FsiRules.showCard(34, true, true, true, false, null, "1.42"))
        assertFalse(FsiRules.showCard(34, false, false, true, false, null, "1.42"))
        assertFalse(FsiRules.showCard(34, false, true, true, false, "1.42", "1.42"))
        assertFalse(FsiRules.showCard(34, false, true, true, null, null, "1.42"))
    }

    @Test
    fun lineRoundTripsAndLogKeepsTen() {
        val l = FsiRules.line(1234L, "1.41", "1.42", FsiRules.HOW_VIEW, true, false)
        val e = FsiRules.parse(l)!!
        assertEquals(1234L, e.at); assertEquals("1.41", e.from); assertEquals("1.42", e.to)
        assertEquals(FsiRules.HOW_VIEW, e.how); assertEquals(true, e.before); assertEquals(false, e.after)
        val u = FsiRules.parse(FsiRules.line(1L, null, "1.42", FsiRules.HOW_OTHER, null, null))!!
        assertEquals("?", u.from); assertNull(u.before); assertNull(u.after)
        assertNull(FsiRules.parse("not a line"))
        var log: String? = null
        for (i in 1..15) log = FsiRules.add(log, FsiRules.line(i.toLong(), "a", "b", "c", true, true))
        val lines = log!!.split('\n')
        assertEquals(FsiRules.KEEP, lines.size)
        assertEquals(15L, FsiRules.parse(lines.last())!!.at)
        // Tabs or line breaks in a version never break the record.
        assertEquals(6, FsiRules.line(1L, "x\ty", "1\n2", "h", true, true).split('\t').size)
    }

    @Test
    fun describeSaysWhatHappened() {
        val lost = FsiRules.parse(FsiRules.line(1L, "1.41", "1.42", FsiRules.HOW_VIEW, true, false))!!
        assertEquals("Last update (1.41 to 1.42, Android's installer): turned full-screen calls off.", FsiRules.describe(lost))
        val kept = FsiRules.parse(FsiRules.line(1L, "1.42", "1.43", FsiRules.HOW_SESSION, true, true))!!
        assertEquals("Last update (1.42 to 1.43, Muninn's installer): kept full-screen calls on.", FsiRules.describe(kept))
    }
}
