package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.AppVer
import org.junit.Assert.*
import org.junit.Test
/** 1.45: someone's app version, checked form only, older, and missing features. */
class AppVerTest {
    @Test fun all() {
        val tv = AppVer.make("Muninn", "1.45", "Android TV")
        assertEquals("Muninn 1.45 · Android TV", tv)
        assertEquals("Muninn · Android phone/tablet", AppVer.make("Muninn", "", "Android phone/tablet"))
        assertEquals(tv, AppVer.clean(tv))
        assertEquals("", AppVer.clean("Muninn 1.45 · http://x"))
        assertEquals("", AppVer.clean("<b>Muninn</b> · Android TV"))
        assertTrue(AppVer.older("Muninn 1.44 · Android TV", "1.45"))
        assertFalse(AppVer.older("Muninn 1.45 · Android TV", "1.45"))
        assertFalse(AppVer.older("Muninn · Android TV", "1.45"))
        assertTrue(AppVer.older("Huginn 1.45 beta 2 · Linux", "1.45"))
        assertTrue(AppVer.atLeast("Huginn 1.45 · Windows", "1.45"))
        assertEquals("Could you update Muninn? You're on an older version.", AppVer.askText("Muninn 1.4 · Android TV"))
        assertEquals(listOf("GIFs"), AppVer.missing(setOf("g1", "k1"), setOf("g1", "a1", "k2")))
        assertEquals(emptyList<String>(), AppVer.missing(emptySet(), setOf("a1")))
        // 1.55: who says a file is done only once it's saved, and who isn't known yet.
        assertEquals(true, AppVer.doneAfterSave("Muninn 1.54 · Android phone/tablet"))
        assertEquals(true, AppVer.doneAfterSave("Muninn 1.55 · Android TV"))
        assertEquals(false, AppVer.doneAfterSave("Muninn 1.53 · Android phone/tablet"))
        assertEquals(false, AppVer.doneAfterSave("Huginn 1.55 · Windows/Linux"))
        assertEquals(false, AppVer.doneAfterSave("Huginn · Linux"))
        assertNull(AppVer.doneAfterSave("Muninn · Android phone/tablet"))
        assertNull(AppVer.doneAfterSave(""))
    }
}
