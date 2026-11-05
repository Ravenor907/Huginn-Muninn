package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.Accents
import io.github.ravenor907.muninn.ui.ColorVision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings › Look › Color vision: every pair of status colors, the call buttons and the safe accents stay apart under
 * that kind of sight, simulated with Machado's matrices (no color: lightness alone), by at least [APART] (deltaE 1976).
 */
class ColorVisionTest {
    private val kinds = listOf(ColorVision.PROTAN, ColorVision.DEUTAN, ColorVision.TRITAN, ColorVision.MONO)
    private fun hex(c: Long) = "#%06X".format(c and 0xFFFFFF)

    @Test
    fun statusColorsStayApartForEachKindOfSight() {
        for (kind in kinds) for (tone in 0..2) {
            val s = ColorVision.status(kind, tone)!!
            val danger = ColorVision.danger(kind, tone)!!
            // Online, away, do not disturb and offline, and online (success) against danger.
            val pairs = mutableListOf<Pair<Long, Long>>()
            for (i in s.indices) for (j in i + 1 until s.size) pairs += s[i] to s[j]
            pairs += s[0] to danger[0]
            for ((a, b) in pairs) {
                val d = ColorVision.deltaE(a, b, kind)
                assertTrue("$kind tone $tone: ${hex(a)} and ${hex(b)} only $d apart", d >= APART)
            }
        }
    }

    @Test
    fun dotsAndDangerShowOnTheirGround() {
        val grounds = longArrayOf(0xFF101112, 0xFFF3EFE6, 0xFF000000)
        for (kind in kinds) for (tone in 0..2) {
            val all = ColorVision.status(kind, tone)!!.toList() + ColorVision.danger(kind, tone)!![0]
            for (c in all) {
                val d = ColorVision.deltaE(c, grounds[tone], kind)
                assertTrue("$kind tone $tone: ${hex(c)} only $d from its ground", d >= APART)
            }
        }
    }

    @Test
    fun callButtonsStayApart() {
        for (kind in kinds) {
            val (answer, end) = ColorVision.calls(kind)!!
            assertTrue(kind, ColorVision.deltaE(answer, end, kind) >= APART)
        }
        assertNull(ColorVision.calls(ColorVision.TYPICAL))
    }

    @Test
    fun onlyAccentsThatStayApartAreOffered() {
        for (kind in kinds) {
            val safe = ColorVision.accents(kind)
            assertTrue(safe.isNotEmpty())
            for (i in safe.indices) for (j in i + 1 until safe.size) {
                val d = ColorVision.deltaE(Accents.base(safe[i]), Accents.base(safe[j]), kind)
                assertTrue("$kind: ${safe[i]} and ${safe[j]} only $d apart", d >= APART)
            }
            // Blue-weak: never blue against yellow.
            if (kind == ColorVision.TRITAN) assertTrue(!("fjord" in safe && "gold" in safe))
        }
        assertEquals(Accents.NAMES, ColorVision.accents(ColorVision.TYPICAL))
    }

    @Test
    fun theCurrentAccentMapsToTheNearestSafeOne() {
        // Typical keeps whatever was picked.
        assertEquals("ember", ColorVision.safeAccent("ember", ColorVision.TYPICAL))
        assertEquals("#4F8FDE", ColorVision.safeAccent("#4F8FDE", ColorVision.TYPICAL))
        // A safe one stays; the others go to the nearest safe one.
        assertEquals("fjord", ColorVision.safeAccent("fjord", ColorVision.DEUTAN))
        assertEquals("fjord", ColorVision.safeAccent("heather", ColorVision.DEUTAN))
        assertEquals("fjord", ColorVision.safeAccent("#4F8FDE", ColorVision.PROTAN))
        assertEquals("iron", ColorVision.safeAccent("gold", ColorVision.MONO))
        for (kind in kinds) for (n in Accents.NAMES) assertTrue(ColorVision.safeAccent(n, kind) in ColorVision.accents(kind))
    }

    @Test
    fun avatarDiscsStayApart() {
        for (kind in kinds) for (light in listOf(false, true)) {
            val tones = ColorVision.tones(kind, light)!!
            for (i in tones.indices) for (j in i + 1 until tones.size) {
                val d = ColorVision.deltaE(tones[i].first, tones[j].first, kind)
                assertTrue("$kind light $light: discs $i and $j only $d apart", d >= DISCS_APART)
            }
            assertNotNull(ColorVision.disc(0xFF4E8A4E, kind, light))
        }
        assertNull(ColorVision.tones(ColorVision.TYPICAL, false))
    }

    @Test
    fun savedChoicesAndTheOlderSwitch() {
        assertEquals(ColorVision.TYPICAL, ColorVision.of(null))
        assertEquals(ColorVision.TRITAN, ColorVision.of("tritan"))
        assertEquals(ColorVision.TYPICAL, ColorVision.of("nonsense"))
        // An older Color-blind friendly switch that was on: green-weak, the closest to what it showed.
        assertEquals(ColorVision.DEUTAN, ColorVision.of(null, oldColorBlind = true))
        assertEquals(ColorVision.MONO, ColorVision.of("mono", oldColorBlind = true))
        assertEquals(5, ColorVision.ALL.size)
    }

    @Test
    fun leavesAndSnowFollowTheLookAndSight() {
        val red = 0xFFC45A2E
        // No color: gray.
        val g = ColorVision.leafInk(red, ColorVision.MONO, false)
        assertTrue(((g shr 16) and 0xFF) == (g and 0xFF) && ((g shr 8) and 0xFF) == (g and 0xFF))
        // Light: darker than on a dark ground.
        assertTrue(Accents.luminance(ColorVision.leafInk(red, ColorVision.TYPICAL, true)) < Accents.luminance(red))
        // Red-weak: reds turn orange and lighter.
        assertTrue(Accents.luminance(ColorVision.leafInk(red, ColorVision.PROTAN, false)) > Accents.luminance(red))
        assertEquals(red, ColorVision.leafInk(red, ColorVision.TYPICAL, false))
        assertEquals(0xFFFFFFFF, ColorVision.flakeInk(ColorVision.TYPICAL, false))
        assertEquals(0xFF7F93AD, ColorVision.flakeInk(ColorVision.TYPICAL, true))
    }

    private companion object {
        const val APART = 20.0
        const val DISCS_APART = 15.0
    }
}
