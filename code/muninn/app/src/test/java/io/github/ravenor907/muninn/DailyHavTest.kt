package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.tv.HavOwn
import io.github.ravenor907.muninn.tv.Havamal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Daily Hávamál in the standard app (plan 1.42, section 1): off by default, X hides it for today only, the wording, the
 * day it came on, and one verses file shared with Huginn (code/shared/havamal.json).
 */
class DailyHavTest {
    private val kept = HashMap<String, String>()
    private val own = HavOwn({ kept[it] }, { k, v -> if (v == null) kept.remove(k) else kept.put(k, v); Unit })
    private val day = 20_400L

    private fun shared(): File = File("../../shared/havamal.json").takeIf { it.isFile } ?: File("../shared/havamal.json")

    @Test fun offUntilItIsTurnedOn() {
        assertFalse(HavOwn.DEFAULT_ON)
        assertFalse(own.on)
        assertFalse(own.cardShown(day))
        own.setOn(true, day)
        assertTrue(own.on)
        assertTrue(own.cardShown(day))
    }

    @Test fun xHidesItForTodayOnly() {
        own.setOn(true, day)
        own.hideToday(day)
        assertFalse(own.cardShown(day))
        assertTrue(own.cardShown(day + 1))
        // Turned off and on again: today's X is forgotten.
        own.setOn(false, day)
        assertNull(kept[HavOwn.HIDDEN])
        own.setOn(true, day)
        assertTrue(own.cardShown(day))
    }

    @Test fun earlierVersesCountFromTheDayItCameOn() {
        own.setOn(true, day)
        assertEquals(day, own.start(day + 5))
        assertTrue(Havamal.earlier(day, own.start(day), 60).isEmpty())
        assertEquals(3, Havamal.earlier(day + 3, own.start(day + 3), 60).size)
        // Off: forgotten, so it starts again from the next day it comes on.
        own.setOn(false, day + 3)
        own.setOn(true, day + 9)
        assertEquals(day + 9, own.start(day + 9))
        // A clock set back before the start: starts again from today.
        assertEquals(day + 2, own.start(day + 2))
    }

    @Test fun theWordingIsBellowsUnlessYourOwnIsPicked() {
        assertEquals(Havamal.BELLOWS, own.wording)
        own.wording = Havamal.MINE
        assertEquals(Havamal.MINE, own.wording)
        own.wording = "anything else"
        assertEquals(Havamal.BELLOWS, own.wording)
        kept[HavOwn.WORDING] = "z"
        assertEquals(Havamal.BELLOWS, own.wording)
        // Your translation shows where it has the stanza; Bellows elsewhere.
        val verses = Havamal.parse(shared().readText(Charsets.UTF_8))
        val v = verses.first()
        assertEquals("mine", Havamal.wordingOf(v, Havamal.MINE, mapOf(v.n to "mine")))
        assertEquals(v.verse, Havamal.wordingOf(v, Havamal.MINE, emptyMap()))
        assertEquals(v.verse, Havamal.wordingOf(v, Havamal.BELLOWS, mapOf(v.n to "mine")))
    }

    @Test fun oneVersesFileForBothApps() {
        val f = shared()
        assertTrue(f.isFile)
        // No second copy kept in Muninn's own assets (the build copies the shared one in).
        assertFalse(File("src/main/assets/havamal.json").isFile)
        val o = JSONObject(f.readText(Charsets.UTF_8))
        assertTrue(o.getString("translation").contains("Bellows"))
        val verses = Havamal.parse(f.readText(Charsets.UTF_8))
        assertEquals(60, verses.size)
        // The same verse all day, every verse in turn: what Huginn's self-test checks too.
        assertEquals(verses[Havamal.index(day, verses.size)], verses[Havamal.index(day, verses.size)])
        assertEquals((0 until 60).toSet(), (day until day + 60).map { Havamal.index(it, verses.size) }.toSet())
        assertEquals(0, Havamal.index(0, verses.size))
        assertEquals(59, Havamal.index(-1, verses.size))
    }

    @Test fun anImportKeepsOnlyNumberedStanzasOnTheList() {
        val verses = Havamal.parse(shared().readText(Charsets.UTF_8))
        val first = verses[0].n
        val second = verses[1].n
        val text = "Title line\n$first. One line\nand another\n\nSt. $second\nThe second one\n999. Not on the list\n"
        val map = Havamal.parseImport(text, Havamal.numbers(verses))
        assertEquals("One line\nand another", map[first])
        assertEquals("The second one", map[second])
        assertFalse(map.containsKey(999))
        assertEquals("Imported: 2 of 60 verses matched", Havamal.matchedLine(map.size, verses.size))
    }
}
