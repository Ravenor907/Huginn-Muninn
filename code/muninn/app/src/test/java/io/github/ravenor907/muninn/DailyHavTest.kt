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
        // 1.43: all 165 stanzas, numbered 1 to 165, each marked for Kids' Devices or not.
        assertEquals(165, verses.size)
        assertEquals((1..165).toList(), verses.map { it.n })
        val a = o.getJSONArray("verses")
        assertTrue((0 until a.length()).all { a.getJSONObject(it).get("kids") is Boolean })
        assertTrue(Havamal.forKids(verses).size in 1 until 165)
        // The same verse all day, every verse in turn: what Huginn's self-test checks too.
        assertEquals(verses[Havamal.index(day, verses.size)], verses[Havamal.index(day, verses.size)])
        assertEquals((0 until 165).toSet(), (day until day + 165).map { Havamal.index(it, verses.size) }.toSet())
        assertEquals(0, Havamal.index(0, verses.size))
        assertEquals(164, Havamal.index(-1, verses.size))
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
        assertEquals("Imported: 2 of 165 verses matched", Havamal.matchedLine(map.size, verses.size))
        // Any of the 165 numbers matches.
        val last = Havamal.parseImport("1. one\n100. a hundred\n165. the last\n", Havamal.numbers(verses))
        assertEquals(setOf(1, 100, 165), last.keys)
    }

    @Test fun randomShowsEveryVerseOnceBeforeAnyComesAgain() {
        val size = 165
        val seed = 1234567L
        val start = 20_295L - Math.floorMod(20_295L, size.toLong())
        val round = (start until start + size).map { Havamal.pick(it, size, true, seed) }
        assertEquals((0 until size).toSet(), round.toSet())
        // Seeded per device: the same seed gives the same order, another seed another.
        assertEquals(round, (start until start + size).map { Havamal.pick(it, size, true, seed) })
        assertTrue(round != (start until start + size).map { Havamal.pick(it, size, true, seed + 1) })
        // Not just the plain order.
        assertTrue(round != (0 until size).toList())
        // Where one round meets the next, never the same verse twice in a row.
        for (r in 0 until 50L) {
            val end = start + r * size
            assertTrue(Havamal.pick(end - 1, size, true, seed) != Havamal.pick(end, size, true, seed))
        }
        // In order is the plain cycle.
        assertEquals(Havamal.index(day, size), Havamal.pick(day, size, false, seed))
        // The setting.
        assertFalse(own.random)
        own.random = true
        assertTrue(own.random)
        assertEquals(seed, own.seed { seed })
        assertEquals(seed, own.seed { 99L })
        // Earlier verses follow the order picked.
        own.setOn(true, day)
        val e = Havamal.earlier(day + 3, own.start(day + 3), size) { d -> own.dayPick(d, size, seed) }
        assertEquals(listOf(day + 2, day + 1, day).map { Havamal.pick(it, size, true, seed) }, e)
    }

    @Test fun nextVerseIsForTodayOnly() {
        own.setOn(true, day)
        val size = 165
        val seed = 42L
        val first = own.shownOn(day, size, seed)
        assertEquals(Havamal.index(day, size), first)
        own.next(day)
        assertEquals(Havamal.index(day + 1, size), own.shownOn(day, size, seed))
        own.next(day)
        assertEquals(2, own.skips(day))
        assertEquals(Havamal.index(day + 2, size), own.shownOn(day, size, seed))
        // Tomorrow: back to the day's own verse.
        assertEquals(0, own.skips(day + 1))
        assertEquals(Havamal.index(day + 1, size), own.shownOn(day + 1, size, seed))
        // Random: the next one in the shuffle.
        own.random = true
        assertEquals(Havamal.pick(day + 2, size, true, seed), own.shownOn(day, size, seed))
        // Bad values read as none.
        assertEquals(0, Havamal.skipsOn("x:y", day))
        assertEquals(0, Havamal.skipsOn(null, day))
        assertEquals(3, Havamal.skipsOn(Havamal.skipsKept(day, 3), day))
    }

    @Test fun kidsDevicesShowOnlyKidsVerses() {
        val verses = Havamal.parse(shared().readText(Charsets.UTF_8))
        val kids = Havamal.forKids(verses)
        assertTrue(kids.all { it.kids })
        assertEquals(verses.count { it.kids }, kids.size)
        // A file without "kids" (an older one) reads every verse as for children.
        val old = Havamal.parse("""{"verses":[{"n":1,"verse":"v","means":"m"}]}""")
        assertTrue(old.single().kids)
        // Each "means" line is one sentence: read with a pause between.
        assertTrue(verses.all { v -> v.means.lines().all { it.isNotBlank() } })
    }
}
