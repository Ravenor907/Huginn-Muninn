package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.FallScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Seasonal Amount and Size sliders: their logarithmic scales, the defaults, and older saved settings carrying over. */
class FallScaleTest {
    @Test
    fun amountRunsFromOneToFourHundred() {
        assertEquals(1, FallScale.countAt(0f))
        assertEquals(400, FallScale.countAt(100f))
        assertEquals(20, FallScale.countAt(50f))
        // Out of range positions hold at the ends.
        assertEquals(1, FallScale.countAt(-5f))
        assertEquals(400, FallScale.countAt(120f))
        // Fine-grained at the low end: the first tenth of the slider covers 1 to about 2.
        assertTrue(FallScale.countAt(10f) <= 2)
        // Never goes down as the slider goes up.
        var last = 0
        for (v in 0..100) { val n = FallScale.countAt(v.toFloat()); assertTrue(n >= last); last = n }
    }

    @Test
    fun amountPositionRoundTrips() {
        assertEquals(0f, FallScale.countPos(1), 1e-4f)
        assertEquals(100f, FallScale.countPos(400), 1e-4f)
        for (n in listOf(1, 2, 3, 7, 12, 14, 50, 240, 399, 400)) assertEquals(n, FallScale.countAt(FallScale.countPos(n)))
        // Out of range counts sit at the ends.
        assertEquals(0f, FallScale.countPos(0), 1e-4f)
        assertEquals(100f, FallScale.countPos(1000), 1e-4f)
    }

    @Test
    fun sizeRunsFromTinyToHuge() {
        assertEquals(15, FallScale.sizeAt(0f))
        assertEquals(600, FallScale.sizeAt(100f))
        assertEquals(95, FallScale.sizeAt(50f))
        var last = 0
        for (v in 0..100) { val p = FallScale.sizeAt(v.toFloat()); assertTrue(p >= last); last = p }
        for (p in listOf(15, 25, 60, 100, 200, 600)) assertEquals(p, FallScale.sizeAt(FallScale.sizePos(p)))
        assertEquals(0f, FallScale.sizePos(5), 1e-4f)
        assertEquals(100f, FallScale.sizePos(900), 1e-4f)
    }

    @Test
    fun defaultsAreTwelveOnAPhoneAtSixtyPercent() {
        assertEquals(12, FallScale.autoCount(FallScale.DEFAULT_BASE, FallScale.area(412, 915)))
        // More on a bigger screen, held between half and four times a phone's.
        assertEquals(24, FallScale.autoCount(FallScale.DEFAULT_BASE, FallScale.area(824, 915)))
        assertEquals(6, FallScale.autoCount(FallScale.DEFAULT_BASE, FallScale.area(100, 100)))
        assertEquals(48, FallScale.autoCount(FallScale.DEFAULT_BASE, FallScale.area(4000, 4000)))
        assertEquals(1, FallScale.autoCount(0.1f, 1f))
        assertEquals(400, FallScale.autoCount(1000f, 1f))
        assertEquals(60, FallScale.DEFAULT_SIZE)
    }

    @Test
    fun olderSettingsCarryOver() {
        // The old defaults (100%, Full) get the new ones.
        assertEquals(FallScale.DEFAULT_BASE, FallScale.legacyBase(100, false), 0f)
        assertEquals(FallScale.DEFAULT_SIZE, FallScale.legacySize(100))
        // A picked percent of the old 14 on a phone stays what it was.
        assertEquals(28f, FallScale.legacyBase(200, false), 1e-4f)
        assertEquals(3.5f, FallScale.legacyBase(25, false), 1e-4f)
        // Light was about half.
        assertEquals(7f, FallScale.legacyBase(100, true), 1e-4f)
        assertEquals(14f, FallScale.legacyBase(200, true), 1e-4f)
        // A picked size stays.
        assertEquals(25, FallScale.legacySize(25))
        assertEquals(200, FallScale.legacySize(200))
        assertEquals(15, FallScale.legacySize(5))
    }

    @Test
    fun seedsAreAboutThreeInFourteen() {
        assertEquals(0, FallScale.seeds(1))
        assertEquals(3, FallScale.seeds(14))
        assertEquals(86, FallScale.seeds(400))
        for (n in 1..400) assertTrue(FallScale.seeds(n) in 0..n)
    }
}
