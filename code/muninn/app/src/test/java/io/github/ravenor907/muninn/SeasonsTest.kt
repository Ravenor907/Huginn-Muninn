package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.Looks
import io.github.ravenor907.muninn.ui.Seasons
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Seasonal look: which season shows on which date, the season cycle, and saved settings opening as the right one. */
class SeasonsTest {
    @Test
    fun snowFromDecemberToFebruaryAutumnOtherwise() {
        // Autumn up to the end of November, Snow from December 1.
        assertEquals(Seasons.AUTUMN, Seasons.at(11, 30))
        assertEquals(Seasons.SNOW, Seasons.at(12, 1))
        assertEquals(Seasons.SNOW, Seasons.at(12, 31))
        // Across the new year and to the last day of February, leap years too.
        assertEquals(Seasons.SNOW, Seasons.at(1, 1))
        assertEquals(Seasons.SNOW, Seasons.at(2, 28))
        assertEquals(Seasons.SNOW, Seasons.at(2, 29))
        assertEquals(Seasons.AUTUMN, Seasons.at(3, 1))
        for (m in 3..11) for (d in listOf(1, 15, 28)) assertEquals(Seasons.AUTUMN, Seasons.at(m, d))
        for (m in listOf(12, 1, 2)) for (d in listOf(1, 15, 28)) assertEquals(Seasons.SNOW, Seasons.at(m, d))
    }

    @Test
    fun aPickedSeasonWinsOverTheDate() {
        assertEquals(Seasons.SNOW, Seasons.shown(Seasons.AUTO, 1, 10))
        assertEquals(Seasons.AUTUMN, Seasons.shown(Seasons.AUTO, 10, 31))
        assertEquals(Seasons.SNOW, Seasons.shown(Seasons.SNOW, 7, 4))
        assertEquals(Seasons.AUTUMN, Seasons.shown(Seasons.AUTUMN, 12, 25))
        // A saved choice that isn't a season follows the date.
        assertEquals(Seasons.SNOW, Seasons.shown("spring", 2, 1))
    }

    @Test
    fun tappingSeasonalAgainCyclesTheSeasons() {
        assertEquals(Seasons.AUTUMN, Seasons.next(Seasons.AUTO))
        assertEquals(Seasons.SNOW, Seasons.next(Seasons.AUTUMN))
        assertEquals(Seasons.AUTO, Seasons.next(Seasons.SNOW))
        // Every season is in the cycle once, after Auto.
        var k = Seasons.AUTO
        val seen = mutableListOf<String>()
        repeat(Seasons.CHOICES.size) { seen += k; k = Seasons.next(k) }
        assertEquals(Seasons.AUTO, k)
        assertEquals(listOf(Seasons.AUTO) + Seasons.NAMES.map { it.first }, seen)
        assertEquals("Snow", Seasons.name(Seasons.SNOW))
        assertEquals("Auto", Seasons.name(Seasons.AUTO))
    }

    @Test
    fun savedSeasonSettingsFallBackSafely() {
        assertEquals(Seasons.AUTO, Seasons.choice(null))
        assertEquals(Seasons.AUTO, Seasons.choice("summer"))
        assertEquals(Seasons.SNOW, Seasons.choice("snow"))
        assertEquals("", Seasons.ground("#123456"))
        assertEquals("", Seasons.ground(null))
        assertEquals(Seasons.GROUNDS[0], Seasons.ground(Seasons.GROUNDS[0]))
        assertEquals(Seasons.GROUNDS.size, Seasons.GROUND_NAMES.size)
        assertEquals(Seasons.FULL, Seasons.anim("nonsense"))
        // The older Light choice reads as on; only Off stops the fall.
        assertEquals(Seasons.FULL, Seasons.anim(Seasons.LIGHT))
        assertEquals(false, Seasons.falls(Seasons.OFF))
        assertEquals(true, Seasons.falls(Seasons.LIGHT))
        assertEquals(true, Seasons.falls(Seasons.FULL))
        assertEquals(true, Seasons.falls(null))
    }

    @Test
    fun newSeasonsSlotInByDate() {
        val spans = listOf(
            Seasons.Span("winter", 12, 21, 3, 19),   // runs past the new year
            Seasons.Span("spring", 3, 20, 6, 20),
            Seasons.Span(Seasons.AUTUMN, 9, 22, 12, 20),
        )
        assertEquals("winter", Seasons.at(12, 25, spans))
        assertEquals("winter", Seasons.at(1, 10, spans))
        assertEquals("winter", Seasons.at(3, 19, spans))
        assertEquals("spring", Seasons.at(3, 20, spans))
        assertEquals(Seasons.AUTUMN, Seasons.at(10, 31, spans))
        // A date no span covers gets the first season.
        assertEquals("winter", Seasons.at(7, 4, spans))
    }

    @Test
    fun savedAutumnOpensAsSeasonal() {
        assertEquals(Looks.SEASONAL, Looks.of("autumn"))
        assertEquals(Looks.SEASONAL, Looks.of("seasonal"))
        assertEquals(Looks.SEASONAL, Looks.of("snow"))
        assertEquals(Looks.DARK, Looks.of("something else"))
        assertEquals("Seasonal", Looks.name(Looks.SEASONAL))
    }
}
