package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.screens.BetaTests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Labs › Things to test: the shared checklist (code/shared/beta-tests.json) and how its ticks are kept. */
class BetaTestsTest {
    private fun shared(): String =
        (File("../../shared/${BetaTests.ASSET}").takeIf { it.isFile } ?: File("../shared/${BetaTests.ASSET}")).readText(Charsets.UTF_8)

    @Test fun theSharedFileReadsWithEveryGroupAndCheck() {
        val t = BetaTests.parse(shared())
        assertNotNull(t)
        t!!
        assertTrue(t.beta.isNotBlank())
        assertTrue(t.about.isNotBlank())
        assertTrue(t.groups.isNotEmpty())
        assertEquals(t.groups.sumOf { it.checks.size }, t.total)
        // Ids are unique, so a tick never counts for two checks.
        val ids = t.groups.flatMap { g -> g.checks.map { it.id } }
        assertEquals(ids.distinct(), ids)
        assertEquals(t.groups.map { it.id }.distinct(), t.groups.map { it.id })
        for (g in t.groups) {
            assertTrue(g.title.isNotBlank())
            assertTrue(g.checks.isNotEmpty())
            for (c in g.checks + listOf(BetaTests.Check("", g.title))) assertFalse(c.text, c.text.contains('\u2014') || c.text.contains('\u2013'))
        }
    }

    @Test fun ticksStartAgainWhenTheBetaChanges() {
        val t = BetaTests.parse("""{"beta":"1.42","about":"a","groups":[{"id":"t1","title":"One","checks":[{"id":"t1.1","text":"x"},{"id":"t1.2","text":"y"}]}]}""")!!
        assertEquals(setOf("t1.1"), BetaTests.kept("1.42", setOf("t1.1"), t))
        assertEquals(emptySet<String>(), BetaTests.kept("1.41", setOf("t1.1"), t))
        // A check the file no longer has isn't counted.
        assertEquals(setOf("t1.2"), BetaTests.kept("1.42", setOf("t1.2", "t9.9"), t))
        assertEquals("1 of 2 done", BetaTests.done(1, t.total))
    }

    @Test fun reportAProblemStartsWithTheGroup() {
        val g = BetaTests.Group("t1", "Natural voices", "", emptyList())
        assertEquals("Beta test t1 Natural voices: ", BetaTests.reportStart(g))
    }

    @Test fun aBrokenFileIsNull() {
        assertNull(BetaTests.parse("not json"))
        assertNull(BetaTests.parse("""{"beta":"1"}"""))
    }
}
