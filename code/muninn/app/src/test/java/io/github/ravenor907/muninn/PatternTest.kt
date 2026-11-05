package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.PatternPath
import io.github.ravenor907.muninn.ui.patternSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pattern lock's rules, the same for a finger and for a D-pad or TV remote. */
class PatternTest {
    @Test
    fun addKeepsEachDotOnceAndPicksUpTheMiddle() {
        var d = PatternPath.add(emptyList(), 0)
        d = PatternPath.add(d, 2)          // over 1 in the top row
        assertEquals(listOf(0, 1, 2), d)
        d = PatternPath.add(d, 1)          // already in: left out
        assertEquals(listOf(0, 1, 2), d)
        d = PatternPath.add(d, 6)          // the diagonal over 4
        assertEquals(listOf(0, 1, 2, 4, 6), d)
        d = PatternPath.add(d, 9)          // not a dot
        assertEquals(listOf(0, 1, 2, 4, 6), d)
        // A knight's move has no middle dot.
        assertEquals(listOf(0, 5), PatternPath.add(listOf(0), 5))
        assertEquals("pattern:0,1,2,4,6", patternSecret(d))
    }

    @Test
    fun theRingMovesOneDotAtATimeAndStopsAtTheEdge() {
        assertEquals(1, PatternPath.move(4, 0, -1))
        assertEquals(7, PatternPath.move(4, 0, 1))
        assertEquals(3, PatternPath.move(4, -1, 0))
        assertEquals(5, PatternPath.move(4, 1, 0))
        assertNull(PatternPath.move(0, -1, 0))
        assertNull(PatternPath.move(0, 0, -1))
        assertNull(PatternPath.move(8, 1, 0))
        assertNull(PatternPath.move(8, 0, 1))
        assertNull(PatternPath.move(2, 1, 0))   // no wrapping to the next row
    }

    @Test
    fun drawingWithTheRemoteFollowsTheRing() {
        // Start on 0, then right, right, down, down: the dots the ring passes, in order.
        var cursor = 0
        var dots = PatternPath.add(emptyList(), cursor)
        for ((dx, dy) in listOf(1 to 0, 1 to 0, 0 to 1, 0 to 1)) {
            cursor = PatternPath.move(cursor, dx, dy)!!
            dots = PatternPath.add(dots, cursor)
        }
        assertEquals(listOf(0, 1, 2, 5, 8), dots)
    }
}
