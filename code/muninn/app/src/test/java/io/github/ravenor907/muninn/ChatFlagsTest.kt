package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mute, archive and mark as unread: kept on this phone, across a restart. */
class ChatFlagsTest {
    @Test
    fun muteRunsOutOrStaysUntilTurnedOff() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.mute("k", 1_000)
        assertTrue(f.muted("k", 999))
        assertFalse(f.muted("k", 1_000))
        f.mute("k", ChatFlags.FOREVER)
        assertTrue(f.muted("k", Long.MAX_VALUE - 1))
        f.mute("k", 0)
        assertFalse(f.muted("k", 0))
    }

    @Test
    fun anArchivedChatComesBackWhenAMessageArrivesUnlessMuted() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.archive("a", true)
        f.archive("b", true)
        f.mute("b", ChatFlags.FOREVER)
        assertTrue(f.arrived("a", 0))
        assertFalse(f.archived("a"))
        assertFalse(f.arrived("b", 0))
        assertTrue(f.archived("b"))
    }

    @Test
    fun markedUnreadUntilOpenedAndKeptAcrossARestart() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.markUnread("g:x", true)
        f.archive("p", true)
        f.mute("p", 5_000)
        val again = ChatFlags({ stored }, { stored = it })
        assertEquals(setOf("g:x"), again.all.value.unread)
        assertEquals(setOf("p"), again.all.value.archived)
        assertEquals(5_000L, again.all.value.muted["p"])
        again.read("g:x")
        assertTrue(again.all.value.unread.isEmpty())
        again.forget("p")
        assertTrue(again.all.value.archived.isEmpty() && again.all.value.muted.isEmpty())
    }

    @Test
    fun mutedText() {
        assertEquals("Muted", ChatFlags.mutedText(ChatFlags.FOREVER, 0))
        assertEquals("Muted for 8 more hours", ChatFlags.mutedText(8 * ChatFlags.HOUR, 0))
        assertEquals("", ChatFlags.mutedText(10, 20))
    }

    @Test
    fun chatColorsAreKeptAndUnknownOnesRefused() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.color("k", "green")
        f.color("j", "neon")
        assertEquals(mapOf("k" to "green"), ChatFlags({ stored }, { stored = it }).all.value.colors)
        f.color("k", "")
        assertTrue(f.all.value.colors.isEmpty())
    }

    @Test
    fun chatColorIsAnAccentHex() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.color("k", "#6FAE8C")
        f.color("j", "#12345")
        assertEquals(mapOf("k" to "#6FAE8C"), ChatFlags({ stored }, { stored = it }).all.value.colors)
        // A bubble color saved by name before shows as its nearest accent.
        assertEquals("#6FAE8C", io.github.ravenor907.muninn.ui.Accents.chatHex("green"))
        assertEquals("#6E9CC8", io.github.ravenor907.muninn.ui.Accents.chatHex("#6e9cc8"))
        assertEquals("", io.github.ravenor907.muninn.ui.Accents.chatHex("neon"))
        assertEquals("", io.github.ravenor907.muninn.ui.Accents.chatHex(null))
    }

    @Test
    fun reduceNotificationsMakesOnePerChatUntilItIsOpened() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        // Off (the default): every message notifies.
        assertTrue(f.alert("a", reduce = false, toMe = false))
        assertTrue(f.alert("a", reduce = false, toMe = false))
        // On: the first one only, until the chat is opened.
        assertTrue(f.alert("b", reduce = true, toMe = false))
        assertFalse(f.alert("b", reduce = true, toMe = false))
        assertTrue(f.alert("c", reduce = true, toMe = false))
        f.read("b")
        assertTrue(f.alert("b", reduce = true, toMe = false))
        // A chat's own choice wins over Settings, both ways.
        f.setNotify("d", ChatFlags.EVERY)
        assertTrue(f.alert("d", reduce = true, toMe = false))
        assertTrue(f.alert("d", reduce = true, toMe = false))
        f.setNotify("e", ChatFlags.ONE)
        assertTrue(f.alert("e", reduce = false, toMe = false))
        assertFalse(f.alert("e", reduce = false, toMe = false))
        assertEquals(ChatFlags.ONE, ChatFlags({ stored }, { stored = it }).notifyMode("e", reduce = false))
        f.setNotify("e", "")
        assertEquals(ChatFlags.EVERY, f.notifyMode("e", reduce = false))
    }

    @Test
    fun aGroupCanNotifyOnlyForMentionsAndRepliesToMe() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.setNotify("g:x", ChatFlags.MENTIONS)
        assertFalse(f.alert("g:x", reduce = false, toMe = false))
        assertTrue(f.alert("g:x", reduce = false, toMe = true))
        assertTrue(f.alert("g:x", reduce = true, toMe = true))
        // Only groups have it: a person's chat set that way notifies as usual.
        assertEquals(ChatFlags.EVERY, ChatFlags.modeFor(ChatFlags.MENTIONS, reduce = false, group = false))
        assertEquals(ChatFlags.ONE, ChatFlags.modeFor(null, reduce = true, group = true))
        assertEquals(ChatFlags.EVERY, ChatFlags.modeFor("nonsense", reduce = false, group = true))
    }

    @Test
    fun hiddenNearbyAndDeletedChatsAreKeptAcrossARestartAndForgotten() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.hide("n", true)
        f.clear("k", true)
        val again = ChatFlags({ stored }, { stored = it })
        assertTrue(again.hidden("n"))
        assertEquals(setOf("k"), again.all.value.cleared)
        again.clear("k", false)
        assertTrue(again.all.value.cleared.isEmpty())
        again.forget("n")
        assertFalse(again.hidden("n"))
        assertFalse(ChatFlags({ stored }, { stored = it }).hidden("n"))
    }

    @Test
    fun aQuarterHourMuteRunsOut() {
        var stored: String? = null
        val f = ChatFlags({ stored }, { stored = it })
        f.mute("k", 15 * ChatFlags.MINUTE)
        assertTrue(f.muted("k", 15 * ChatFlags.MINUTE - 1))
        assertFalse(f.muted("k", 15 * ChatFlags.MINUTE))
        assertEquals("Muted for less than an hour", ChatFlags.mutedText(15 * ChatFlags.MINUTE, 0))
    }
}
