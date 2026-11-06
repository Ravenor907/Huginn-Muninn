package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Photos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.42: the Send sheet's Saved media picker and the tap or hold camera (owner, 2026-11-04). */
class SendMenuTest {
    private val pick = Photos.Pick

    @Test fun upToTenTogether() {
        assertEquals(10, pick.MAX)
        var order = emptyList<Int>()
        (0 until 12).forEach { order = pick.toggle(order, it) }
        assertEquals((0 until 10).toList(), order)
    }

    @Test fun unpickingMovesTheRestUp() {
        val order = listOf(4, 1, 7)
        assertEquals(listOf(4, 7), pick.toggle(order, 1))
        assertEquals(listOf(4, 7, 1), pick.toggle(pick.toggle(order, 1), 1))
    }

    @Test fun filters() {
        val kinds = listOf(pick.PHOTO, pick.VIDEO, pick.GIF, pick.PHOTO, pick.VIDEO)
        assertEquals(listOf("All", "Photos", "Videos", "GIFs"), pick.FILTERS.map { it.second })
        assertEquals(listOf(0, 1, 2, 3, 4), pick.shown(kinds, "all"))
        assertEquals(listOf(0, 3), pick.shown(kinds, pick.PHOTO))
        assertEquals(listOf(1, 4), pick.shown(kinds, pick.VIDEO))
        assertEquals(listOf(2), pick.shown(kinds, pick.GIF))
    }

    @Test fun bottomBarWords() {
        assertEquals("3 selected", pick.selected(3))
        assertEquals("2 photos, 1 video", pick.summary(2, 1, 0))
        assertEquals("1 photo, 2 videos, 1 GIF", pick.summary(1, 2, 1))
        assertEquals("3 GIFs", pick.summary(0, 0, 3))
        assertEquals("", pick.summary(0, 0, 0))
        assertEquals("Full quality for Family and trusted people. Others get up to 5 minutes.", pick.NOTE)
    }

    @Test fun videoLengths() {
        assertEquals("0:18", pick.length(18_000))
        assertEquals("2:14", pick.length(134_000))
        assertEquals("1:02:09", pick.length(3_729_000))
        assertEquals("0:00", pick.length(-5))
    }

    @Test fun shutter() {
        assertEquals("Tap for a photo, hold for video", Photos.Shutter.HINT)
        assertEquals("Recording. Let go to stop.", Photos.Shutter.RECORDING)
        assertEquals("0:12 / 1:00", Photos.Shutter.clock(12_700))
        assertEquals("1:00 / 1:00", Photos.Shutter.clock(75_000))
        assertEquals(0.5f, Photos.Shutter.ring(30_000), 0.001f)
        assertEquals(1f, Photos.Shutter.ring(90_000), 0.001f)
        assertTrue(Photos.Shutter.HOLD_MS in 200..600)
    }

    @Test fun noDashesInTheWords() {
        val words = listOf(pick.NOTE, Photos.Shutter.HINT, Photos.Shutter.RECORDING) + pick.FILTERS.map { it.second }
        words.forEach { w -> assertTrue(w, '\u2014' !in w && '\u2013' !in w) }
    }
}
