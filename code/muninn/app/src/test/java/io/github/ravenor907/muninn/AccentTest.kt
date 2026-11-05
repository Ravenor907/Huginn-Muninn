package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.Accents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Look › Accent: the same colors as Huginn's Theme.qml, with its seasonal variants. */
class AccentTest {
    @Test
    fun namedAndAnyColor() {
        assertEquals("gold", Accents.choice(null))
        assertEquals("gold", Accents.choice("neon"))
        assertEquals("jade", Accents.choice("jade"))
        assertEquals("#4F8FDE", Accents.choice("#4f8fde"))
        assertEquals(0xFFC2A25E, Accents.base("gold"))
        assertEquals(0xFFE9B56A, Accents.base("gold", autumn = true))
        assertEquals(0xFFE3C27A, Accents.base("gold", snow = true))
        assertEquals(0xFFD9853B, Accents.base("copper", autumn = true))
        assertEquals(0xFFCF6F54, Accents.base("ember"))
        assertEquals(0xFF4F8FDE, Accents.base("#4F8FDE"))
        assertEquals(Accents.NAMES.size, Accents.TITLES.size)
        assertEquals(Accents.NAMES.size, Accents.CHAT.size)
        // On Light it's darker, so it reads on parchment.
        assertTrue(Accents.luminance(Accents.darker(Accents.base("gold"))) < Accents.luminance(Accents.base("gold")))
    }
}
