package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.tv.ParentTvMenu
import io.github.ravenor907.muninn.tv.TvRules
import io.github.ravenor907.muninn.tv.TvWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kids' TV screens' choices that don't need a TV: where the remote starts, the recording guard, the kids' words, the parent menu. */
class TvScreenRulesTest {
    @Test
    fun aPermissionAndroidWontAskForGoesToItsSettingsPage() {
        assertFalse(TvRules.settingsOnly(askedBefore = false, rationale = false))   // never asked: Android's box comes up
        assertFalse(TvRules.settingsOnly(askedBefore = true, rationale = true))     // said no once: Android asks again
        assertTrue(TvRules.settingsOnly(askedBefore = true, rationale = false))     // said no for good: settings only
        assertFalse(TvRules.settingsOnly(askedBefore = false, rationale = true))
    }

    @Test
    fun theRemoteStartsOnCall() {
        val quick = listOf("q1", "q2")
        assertEquals(listOf("call", "rec", "say"), TvRules.talkButtons(calls = true, mic = true, dictate = true))
        assertEquals("call", TvRules.firstTalkFocus(calls = true, mic = true, dictate = true, quick = quick))
        // Calls off: the next big button, then the first quick message, then nothing.
        assertEquals("rec", TvRules.firstTalkFocus(calls = false, mic = true, dictate = true, quick = quick))
        assertEquals(listOf("say"), TvRules.talkButtons(calls = false, mic = false, dictate = true))
        assertEquals("q1", TvRules.firstTalkFocus(calls = false, mic = false, dictate = false, quick = quick))
        assertNull(TvRules.firstTalkFocus(calls = false, mic = false, dictate = false, quick = emptyList()))
        assertEquals(4, TvRules.QUICK_SHOWN)
    }

    @Test
    fun recordingIgnoresOkForTwoSeconds() {
        assertFalse(TvRules.recordingArmed(0))
        assertFalse(TvRules.recordingArmed(1_999))
        assertTrue(TvRules.recordingArmed(2_000))
    }

    @Test
    fun kidsReadAFewWordsWithAnIcon() {
        for (w in TvWords.ALL) {
            assertTrue(w, w.split(' ').size <= 3)
            assertFalse(w, w.contains('\u2014'))
            assertNotEquals("info", TvWords.icon(w))
        }
        assertEquals("Too short", TvWords.TOO_SHORT)
        assertEquals("Can't call now", TvWords.CANT_CALL)
        assertEquals("No microphone", TvWords.NO_MIC)
        assertEquals("Ask a parent", TvWords.ASK_PARENT)
    }

    @Test
    fun theParentMenuHasEveryPartOnceWithDisconnectLast() {
        // One page per Kids' Device (settings plan 2.10): its parts by how often a parent uses them (consolidation 2.18),
        // then More with the rarely used ones, Disconnect last.
        val ids = ParentTvMenu.items.map { it.first }
        assertEquals(listOf("people", "chatting", "sound", "quick", "quiet", "apps", "look", "havamal", "parents", "lock", "updates", "more"), ids)
        assertEquals(listOf("names", "keep", "permissions", "storage", "disconnect"), ParentTvMenu.more.map { it.first })
        val all = ids + ParentTvMenu.more.map { it.first }
        assertEquals(all.distinct(), all)
        assertEquals("Who it can talk to", ParentTvMenu.label("people"))
        assertEquals("Chatting", ParentTvMenu.label("chatting"))
        assertEquals("Quiet times", ParentTvMenu.label("quiet"))
        assertEquals("Parent PIN", ParentTvMenu.label("lock"))
        assertEquals("Look", ParentTvMenu.label("look"))
        assertEquals("Calls and mic", ParentTvMenu.label("sound"))
        assertEquals("Messages stay for", ParentTvMenu.label("keep"))
        assertEquals("Disconnect", ParentTvMenu.label("disconnect"))
        assertEquals("Device permissions", ParentTvMenu.label("permissions"))
        // Read aloud opens from inside Look, with the same name as on a phone (consolidation 2.11).
        assertEquals("Read aloud", ParentTvMenu.label("aloud"))
        assertFalse("aloud" in all)
        // The TV's chat menu opens the page itself with an id the page doesn't know.
        assertNull(ParentTvMenu.label("page"))
        assertNull(ParentTvMenu.label("nope"))
        assertNull(ParentTvMenu.label(null))
    }
}
