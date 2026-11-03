package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Drafts
import io.github.ravenor907.muninn.net.ExifTurn
import io.github.ravenor907.muninn.screens.ComposerKeys
import io.github.ravenor907.muninn.screens.RoomTiles
import io.github.ravenor907.muninn.ui.Picking
import io.github.ravenor907.muninn.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 14, Muninn everyday: drafts, long messages, pickers and the lock, updates, photos, video tiles and keys. */
class EverydayTest {
    // ---- Drafts kept per chat

    @Test
    fun draftsRoundTrip() {
        val all = mapOf(
            "alice" to Drafts.Draft("half a thought", re = "m1"),
            Drafts.groupKey("g1", "main") to Drafts.Draft("hi all", edit = "m2"),
            "bob" to Drafts.Draft(),   // empty: not saved
        )
        val back = Drafts.decode(Drafts.encode(all))
        assertEquals(2, back.size)
        assertEquals(Drafts.Draft("half a thought", re = "m1"), back["alice"])
        assertEquals(Drafts.Draft("hi all", edit = "m2"), back["g:g1/main"])
    }

    @Test
    fun brokenDraftFileIsIgnored() {
        assertTrue(Drafts.decode(null).isEmpty())
        assertTrue(Drafts.decode("").isEmpty())
        assertTrue(Drafts.decode("not json").isEmpty())
        assertTrue(Drafts.decode("{\"a\":5}").isEmpty())
    }

    @Test
    fun aGroupChannelHasItsOwnDraft() {
        assertFalse(Drafts.groupKey("g", "main") == Drafts.groupKey("g", "photos"))
    }

    // ---- Long messages are never cut

    @Test
    fun shortTextIsOnePart() {
        assertEquals(listOf("hello"), Drafts.split("  hello  ", 2000))
        assertTrue(Drafts.split("   ", 2000).isEmpty())
    }

    @Test
    fun longTextSplitsWithNothingLost() {
        val words = (1..900).joinToString(" ") { "word$it" }
        val parts = Drafts.split(words, 2000)
        assertTrue(parts.size > 1)
        parts.forEach { assertTrue(it.codePointCount(0, it.length) <= 2000) }
        // Split at spaces: every word is still whole, in order.
        assertEquals(words.split(" "), parts.flatMap { it.split(" ") })
    }

    @Test
    fun splitsAtALineBreakFirst() {
        val text = "a".repeat(1500) + "\n" + "b".repeat(300) + " " + "c".repeat(1000)
        val parts = Drafts.split(text, 2000)
        assertEquals("a".repeat(1500), parts[0])
    }

    @Test
    fun oneLongWordIsCutAtTheLimitNeverInsideAnEmoji() {
        val emoji = "😀"   // one code point, two chars
        val text = emoji.repeat(2500)
        val parts = Drafts.split(text, 2000)
        assertEquals(2, parts.size)
        assertEquals(2000, parts[0].codePointCount(0, parts[0].length))
        assertEquals(text, parts.joinToString(""))
        parts.forEach { assertFalse(Character.isHighSurrogate(it.last())) }
    }

    @Test
    fun aCutNeverSplitsALetterFromItsMark() {
        // "e" and a combining acute accent show as one letter; an odd limit lands between the two.
        val text = "e\u0301".repeat(1500)
        val parts = Drafts.split(text, 2001)
        assertEquals(text, parts.joinToString(""))
        parts.forEach { assertTrue(it.length % 2 == 0 && it.first() == 'e') }
        assertEquals(2000, parts[0].length)
        // A plain cut where nothing joins.
        assertEquals(5, Drafts.graphemeCut("abcdefgh", 5))
        assertEquals(3, Drafts.graphemeCut("abce\u0301fg", 4))
        assertEquals(5, Drafts.graphemeCut("abce\u0301fg", 5))
    }

    @Test
    fun draftsGoWithTheirChat() {
        val all = setOf("alice", "bob", Drafts.groupKey("g1", "main"), Drafts.groupKey("g1", "x"), Drafts.groupKey("g2", "main"))
        assertEquals(setOf("bob", Drafts.groupKey("g2", "main")), Drafts.orphans(all, setOf("alice", "g:g1")))
        assertTrue(Drafts.orphans(all, all.map { if (it.startsWith("g:")) it.substringBefore('/') else it }.toSet()).isEmpty())
        // A refused notification reply waits in the box: a group's goes to its main channel.
        assertEquals(Drafts.groupKey("g1", "main"), Drafts.replyKey("g:g1"))
        assertEquals("alice", Drafts.replyKey("alice"))
        assertEquals("see you", Drafts.withReply(Drafts.Draft(), "see you").text)
        assertEquals("half\nsee you", Drafts.withReply(Drafts.Draft("half", re = "m1"), "see you").text)
        assertEquals("m1", Drafts.withReply(Drafts.Draft("half", re = "m1"), "see you").re)
    }

    @Test
    fun aLatePartKeepsItsPlace() {
        fun m(id: String, t: Long) = io.github.ravenor907.muninn.net.Groups.GMsg(id, "g", "main", "me", "Me", id, true, t)
        val list = listOf(m("old", 100), m("p2", 300), m("p3", 350))
        val out = io.github.ravenor907.muninn.net.Groups.inOrder(list, m("p1", 250))
        assertEquals(listOf("old", "p1", "p2", "p3"), out.map { it.id })
        assertEquals(listOf("old", "p2", "p3", "new"), io.github.ravenor907.muninn.net.Groups.inOrder(list, m("new", 400)).map { it.id })
        assertEquals("first", io.github.ravenor907.muninn.net.Groups.inOrder(list, m("first", 1)).first().id)
    }

    @Test
    fun tooLongIsMoreThanTheMostParts() {
        val text = "x ".repeat(Drafts.MAX_PARTS * 1000 + 500)
        assertTrue(Drafts.split(text, 2000).size > Drafts.MAX_PARTS)
        assertTrue(Drafts.KEEP_MAX >= Drafts.MAX_PARTS * 2000)
    }

    // ---- A picker Muninn opened doesn't put "Right away" up

    @Test
    fun pickerGetsAGrace() {
        // Right away (0), picker opened just before Muninn stopped: the grace instead.
        assertEquals(Picking.GRACE, Picking.lockAfter(0L, since = 1_000L, awaySince = 1_500L))
        // A longer lock time stays.
        assertEquals(15 * 60_000L, Picking.lockAfter(15 * 60_000L, since = 1_000L, awaySince = 1_500L))
        // No picker, or one long before: as chosen.
        assertEquals(0L, Picking.lockAfter(0L, since = 0L, awaySince = 1_500L))
        assertEquals(0L, Picking.lockAfter(0L, since = 1_000L, awaySince = 1_000L + Picking.GRACE + 1))
    }

    // ---- Auto-update: one download, kept

    @Test
    fun aCheckedDownloadIsUsedAgain() {
        val note = Updater.record("1.36", "ab12")
        assertTrue(Updater.reuse(note, "1.36", "ab12"))
        assertTrue(Updater.reuse(note + "\n", "1.36", "ab12"))
        assertFalse(Updater.reuse(note, "1.37", "ab12"))
        assertFalse(Updater.reuse(note, "1.36", "cd34"))
        assertFalse(Updater.reuse(null, "1.36", "ab12"))
    }

    // ---- Photos stand upright

    @Test
    fun everyExifOrientation() {
        assertEquals(ExifTurn.Turn(0f, false), ExifTurn.of(1))
        assertEquals(ExifTurn.Turn(0f, true), ExifTurn.of(2))
        assertEquals(ExifTurn.Turn(180f, false), ExifTurn.of(3))
        assertEquals(ExifTurn.Turn(180f, true), ExifTurn.of(4))
        assertEquals(ExifTurn.Turn(90f, true), ExifTurn.of(5))
        assertEquals(ExifTurn.Turn(90f, false), ExifTurn.of(6))
        assertEquals(ExifTurn.Turn(270f, true), ExifTurn.of(7))
        assertEquals(ExifTurn.Turn(270f, false), ExifTurn.of(8))
        assertEquals(ExifTurn.Turn(0f, false), ExifTurn.of(0))
    }

    // ---- Group video fits a landscape tablet

    @Test
    fun videoTilesFitTheWindow() {
        // 11" tablet sideways: 4 across, never taller than 60% of the window.
        assertEquals(4, RoomTiles.columns(1280f, 728f))
        val w = RoomTiles.tileWidth(1280f, 728f)
        assertTrue(w * 4f / 3f <= 728f * RoomTiles.TALL + 0.01f)
        // A phone upright: 2 across, side by side.
        assertEquals(2, RoomTiles.columns(400f, 800f))
        assertTrue(RoomTiles.tileWidth(400f, 800f) * 2 + RoomTiles.GAP <= 400f)
        // A 16:9 shared screen on a landscape tablet is at most 60% of the window high.
        assertTrue(RoomTiles.screenWidth(1280f, 728f, 16f / 9f) / (16f / 9f) <= 728f * RoomTiles.TALL + 0.01f)
    }

    // ---- Keyboard: Enter sends, Shift+Enter is a new line, Tab moves on

    @Test
    fun keys() {
        assertEquals(ComposerKeys.SEND, ComposerKeys.action(ComposerKeys.ENTER, shift = false, physical = true))
        assertEquals(ComposerKeys.SEND, ComposerKeys.action(ComposerKeys.NUMPAD_ENTER, shift = false, physical = true))
        assertEquals(ComposerKeys.NONE, ComposerKeys.action(ComposerKeys.ENTER, shift = true, physical = true))
        // The keyboard on the screen keeps Enter for new lines.
        assertEquals(ComposerKeys.NONE, ComposerKeys.action(ComposerKeys.ENTER, shift = false, physical = false))
        assertEquals(ComposerKeys.NEXT, ComposerKeys.action(ComposerKeys.TAB, shift = false, physical = true))
        assertEquals(ComposerKeys.PREV, ComposerKeys.action(ComposerKeys.TAB, shift = true, physical = true))
        assertEquals(ComposerKeys.NONE, ComposerKeys.action(29, shift = false, physical = true))
    }
}
