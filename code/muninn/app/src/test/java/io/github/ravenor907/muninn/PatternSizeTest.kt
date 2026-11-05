package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.screens.padBias
import io.github.ravenor907.muninn.ui.PadFit
import io.github.ravenor907.muninn.ui.PatternSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pattern pad fits the space it's given, from both directions: hand-sized on a tablet, drawable on a small phone. */
class PatternSizeTest {
    private val inf = Float.POSITIVE_INFINITY

    @Test
    fun cappedOnTablets() {
        // 11" landscape and upright, a Fold's inner screen: never past 320 dp.
        assertEquals(320f, PatternSize.side(1280f, 800f), 0.01f)
        assertEquals(320f, PatternSize.side(800f, 1280f), 0.01f)
        assertEquals(320f, PatternSize.side(840f, 700f), 0.01f)
    }

    @Test
    fun theSmallerSideLessThePadding() {
        assertEquals(304f, PatternSize.side(320f, 640f), 0.01f)
        assertEquals(234f, PatternSize.side(600f, 250f), 0.01f)
        // Room kept under it for the remote's hint line.
        assertEquals(220f, PatternSize.side(400f, 300f, reserve = 64f), 0.01f)
    }

    @Test
    fun neverTooSmallToDraw() {
        // A phone on its side at a large text size: the page scrolls rather than shrinking the pad to nothing.
        assertEquals(PatternSize.MIN, PatternSize.side(640f, 40f), 0.01f)
        assertEquals(PatternSize.MIN, PatternSize.side(0f, 0f), 0.01f)
    }

    @Test
    fun anUnlimitedSideDoesNotCount() {
        // In a scrolling page the height is unlimited: the width decides.
        assertEquals(184f, PatternSize.side(200f, inf), 0.01f)
        assertEquals(320f, PatternSize.side(inf, inf), 0.01f)
        assertEquals(234f, PatternSize.side(inf, 250f), 0.01f)
    }

    @Test
    fun thePageAroundItScrollsWhenItMust() {
        val least = (PatternSize.MIN + PatternSize.PAD).toInt()
        // Room to spare: the pad gets what's left between the words and the buttons.
        assertEquals(440, PadFit.padHeight(600, 100, 60, least))
        assertTrue(PadFit.fits(600, 100, 60, least))
        // Not enough: the pad keeps its smallest size and the page scrolls, so Cancel and Skip stay reachable.
        assertEquals(least, PadFit.padHeight(300, 200, 60, least))
        assertFalse(PadFit.fits(300, 200, 60, least))
    }

    @Test
    fun placesFollowTheSetting() {
        assertTrue(padBias("top") < -0.5f)
        assertEquals(0f, padBias("middle"), 0.001f)
        assertTrue(padBias("lower") > 0.5f)
    }
}
