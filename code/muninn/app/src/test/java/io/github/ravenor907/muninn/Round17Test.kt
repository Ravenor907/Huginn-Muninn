package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.BackupFormat
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Drafts
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.net.LinkWire
import io.github.ravenor907.muninn.net.LinkReplay
import io.github.ravenor907.muninn.net.LocalSendRules
import io.github.ravenor907.muninn.net.NudgeWire
import io.github.ravenor907.muninn.net.ProfileWire
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.Videos
import io.github.ravenor907.muninn.net.call.LockedRingLimit
import io.github.ravenor907.muninn.screens.BoxEcho
import io.github.ravenor907.muninn.ui.AppLock
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.panicKeeps
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer

/** Round 17, standard Muninn: the pure parts of the fixes (the lock and questions, parts, shares, security limits). */
class Round17Test {
    // ---- The lock never answers a question; a double tap answers once

    @Test fun lockParksRealQuestions() {
        AppLock.reset(); Ui.clearParked(); Ui.ask.value = null
        try {
            var said: Boolean? = null
            val kid = Ask("Approve Riley?", "In Soccer.", "Approve", cancel = "Decline", onCancel = { said = false }) { said = true }
            Ui.sure(kid)
            assertSame(kid, Ui.ask.value)
            // The lock goes up: closed, not answered, and kept for after.
            Ui.park()
            assertNull(Ui.ask.value)
            assertNull(said)
            assertEquals(1, Ui.parkedCount())
            Ui.unpark()
            assertSame(kid, Ui.ask.value)
            assertEquals(0, Ui.parkedCount())
            // A question with nothing on Cancel just closes.
            Ui.ask.value = null
            val plain = Ask("Send as 3 messages?", "", "Send 3") {}
            Ui.sure(plain)
            Ui.park()
            assertNull(Ui.ask.value)
            assertEquals(0, Ui.parkedCount())
            // One that comes while the lock is up waits for it to be opened (never over the lock screen).
            AppLock.raise()
            Ui.sure(kid)
            assertNull(Ui.ask.value)
            assertEquals(1, Ui.parkedCount())
            // The lock screen's own questions still show.
            val erase = Ask("Erase everything?", "", "Erase") {}
            Ui.sure(erase)
            assertSame(erase, Ui.ask.value)
            Ui.ask.value = null
            AppLock.opened()
            Ui.unpark()
            assertSame(kid, Ui.ask.value)
            assertNull(said)
        } finally { AppLock.reset(); Ui.clearParked(); Ui.ask.value = null }
    }

    @Test fun parkedQuestionsAreCapped() {
        AppLock.reset(); Ui.clearParked(); Ui.ask.value = null
        try {
            repeat(Ui.MAX_PARKED + 3) { i -> Ui.ask.value = Ask("Q$i", "", "Yes", cancel = "No", onCancel = { }) {}; Ui.park() }
            assertEquals(Ui.MAX_PARKED, Ui.parkedCount())
            // The oldest went first.
            Ui.unpark()
            assertEquals("Q3", Ui.ask.value?.title)
        } finally { Ui.clearParked(); Ui.ask.value = null }
    }

    @Test fun doubleTapAnswersOnce() {
        Ui.clearParked(); Ui.ask.value = null
        var n = 0
        val a = Ask("Send as 3 messages?", "", "Send 3") { n++ }
        Ui.ask.value = a
        Ui.answer(a) { a.onConfirm() }
        Ui.answer(a) { a.onConfirm() }
        assertEquals(1, n)
        assertNull(Ui.ask.value)
    }

    // ---- Panic lock clears every channel but the listening and transfer ones

    @Test fun panicClearsEveryChannel() {
        for (c in listOf(Muninn.MESSAGES, Muninn.QUIET, "raven_calls", "raven_missed", null)) assertFalse("$c", panicKeeps(c))
        assertTrue(panicKeeps("listening_quiet"))
        assertTrue(panicKeeps("transfers"))
    }

    // ---- Long 1:1 messages keep their part order

    private fun part(id: String, set: String, i: Int, mine: Boolean = false) =
        Chat.Msg(id, "ann", "Ann", id, mine, 1000L + i, part = set, partAt = i, partOf = 3)

    @Test fun privatePartsReadInOrder() {
        val set = Proto06.newLogicalId()
        val other = Chat.Msg("x", "ann", "Ann", "hi", false, 900L)
        // Parts 2 and 3 came first, part 1 last (it waited for its link preview).
        var list = listOf(other)
        list = Chat.placePart(list, part("p2", set, 2))
        list = Chat.placePart(list, part("p3", set, 3))
        list = Chat.placePart(list, part("p1", set, 1))
        assertEquals(listOf("x", "p1", "p2", "p3"), list.map { it.id })
        // A plain message, or one of mine, goes last as always.
        assertEquals("y", Chat.placePart(list, Chat.Msg("y", "ann", "Ann", "y", false, 5000L)).last().id)
        assertEquals("m", Chat.placePart(list, part("m", set, 1, mine = true)).last().id)
        // Another set doesn't move in among these.
        assertEquals("q1", Chat.placePart(list, part("q1", Proto06.newLogicalId(), 1)).last().id)
    }

    // ---- Win m10 (as Huginn 1.41): parts are placed only among the last 200, beside parts under an hour old

    @Test fun partsPlacedOnlyNearAndFresh() {
        val now = 1_800_000_000_000L
        val set = Proto06.newLogicalId()
        fun gp(id: String, i: Int, t: Long) = Groups.GMsg(id, "g", Proto06.MAIN, "amy", "Amy", id, false, t, part = set, partAt = i)
        fun cp(id: String, i: Int, t: Long) = Chat.Msg(id, "ann", "Ann", id, false, t, part = set, partAt = i, partOf = 3)
        val fresh = now - 60_000L
        val old = now - Groups.PART_AGE_MS - 1
        // Groups: beside a fresh part, placed; beside an old one (ms or seconds), last.
        assertEquals("n1", Groups.placePart(listOf(gp("p2", 2, fresh)), gp("n1", 1, now), now).first().id)
        assertEquals("n1", Groups.placePart(listOf(gp("p2", 2, old)), gp("n1", 1, now), now).last().id)
        assertEquals("n1", Groups.placePart(listOf(gp("p2", 2, old / 1000)), gp("n1", 1, now), now).last().id)
        // Groups: a part further back than the last 200 isn't looked at.
        val filler = (1..Groups.PART_WINDOW).map { Groups.GMsg("f$it", "g", Proto06.MAIN, "bo", "Bo", "f", false, fresh) }
        assertEquals("n1", Groups.placePart(listOf(gp("p2", 2, fresh)) + filler, gp("n1", 1, now), now).last().id)
        assertEquals("n1", Groups.placePart(listOf(gp("p2", 2, fresh)) + filler.drop(1), gp("n1", 1, now), now).first().id)
        // 1:1, the same rule.
        assertEquals("n1", Chat.placePart(listOf(cp("p2", 2, fresh)), cp("n1", 1, now), now).first().id)
        assertEquals("n1", Chat.placePart(listOf(cp("p2", 2, old)), cp("n1", 1, now), now).last().id)
        val plain = (1..Groups.PART_WINDOW).map { Chat.Msg("f$it", "ann", "Ann", "f", false, fresh) }
        assertEquals("n1", Chat.placePart(listOf(cp("p2", 2, fresh)) + plain, cp("n1", 1, now), now).last().id)
        assertEquals("n1", Chat.placePart(listOf(cp("p2", 2, fresh)) + plain.drop(1), cp("n1", 1, now), now).first().id)
    }

    // ---- Security M2 (as Huginn 1.41): the phone's pong says the ping's "pn" back

    @Test fun pongEchoesPingNonce() {
        val withPn = LinkWire.pong(JSONObject().put("t", "ping").put("caps", "l4").put("pn", "0123456789abcdef"))
        assertEquals("pong", withPn.getString("t"))
        assertEquals(LinkWire.L4, withPn.getString("caps"))
        assertEquals("0123456789abcdef", withPn.getString("pn"))
        // A ping without pn (an app before round 17) gets the pong as before.
        val without = LinkWire.pong(JSONObject().put("t", "ping").put("caps", "l4"))
        assertEquals(setOf("t", "caps"), without.keys().asSequence().toSet())
        // Not a string, empty or too long: not said back.
        assertFalse(LinkWire.pong(JSONObject().put("pn", 12345)).has("pn"))
        assertFalse(LinkWire.pong(JSONObject().put("pn", "")).has("pn"))
        assertFalse(LinkWire.pong(JSONObject().put("pn", "a".repeat(33))).has("pn"))
    }

    // ---- Shared text goes into a chat's box

    @Test fun sharedText() {
        assertEquals("Raven facts\nhttps://example.org/r", Drafts.sharedText("Raven facts", "https://example.org/r"))
        assertEquals("hello", Drafts.sharedText(null, "  hello "))
        assertEquals("Look: https://x.org", Drafts.sharedText("Look", "Look: https://x.org"))
        assertEquals("Title", Drafts.sharedText("Title", null))
        assertEquals("", Drafts.sharedText(null, "   "))
        assertEquals("A B\nt", Drafts.sharedText("A\nB", "t"))
        assertEquals(Drafts.KEEP_MAX, Drafts.sharedText(null, "x".repeat(Drafts.KEEP_MAX + 10)).length)
    }

    // ---- A linked tablet's drafts (mirrored chats and the Nearby room) are never pruned as orphans

    @Test fun mirrorDraftsKept() {
        val all = setOf(Drafts.mirrorKey("phone-chat"), Drafts.NEARBY, "gone", "ann")
        assertEquals(setOf("gone"), Drafts.orphans(all, setOf("ann")))
        assertEquals("mirror:abc", Drafts.mirrorKey("abc"))
    }

    // ---- Nearby off: only a pinned contact certificate is asked

    @Test fun nearbyOffAsksOnlyPinned() {
        val fp = "AB".repeat(32)
        assertTrue(LocalSendRules.mayAsk("https", fp))
        assertFalse(LocalSendRules.mayAsk("https", ""))
        assertFalse(LocalSendRules.mayAsk("http", fp))
    }

    // ---- Locked rings: at most 3 in 10 minutes

    @Test fun lockedRingsLimited() {
        val l = LockedRingLimit()
        val t = 1_000_000L
        assertTrue(l.take(t)); assertTrue(l.take(t + 1_000)); assertTrue(l.take(t + 2_000))
        assertFalse(l.take(t + 3_000))
        assertFalse(l.take(t + 9 * 60_000))
        // Ten minutes after the first, one more may ring.
        assertTrue(l.take(t + 10 * 60_000))
        assertFalse(l.take(t + 10 * 60_000 + 1))
        l.clear()
        assertTrue(l.take(t + 10 * 60_000 + 2))
    }

    // ---- Contacts' nudges while locked: limited alerts, at most 5 named notifications

    @Test fun knownNudgesLimited() {
        val lim = NudgeWire.LockedLimit()
        val t = 5_000_000L
        val alerts = (0 until 5).map { lim.take(NudgeWire.Show.NAME, NudgeWire.Nudge(t, "Name$it", "", "msg"), t + it).alert }
        assertEquals(listOf(true, true, true, false, false), alerts)
        // Two minutes on, alerts again.
        assertTrue(lim.take(NudgeWire.Show.NAME, NudgeWire.Nudge(t, "Name0", "", "msg"), t + NudgeWire.LockedLimit.ALERT_EVERY_MS + 10).alert)
        // A sixth name: folded into one notification with no name.
        val six = lim.take(NudgeWire.Show.NAME, NudgeWire.Nudge(t, "Bank", "", "msg"), t + 10)
        assertEquals("", six.n.from)
        assertEquals("Muninn", NudgeWire.title(NudgeWire.Show.NAME, six.n))
        val seven = lim.take(NudgeWire.Show.NAME, NudgeWire.Nudge(t, "Police", "", "msg"), t + 11)
        assertEquals(six.key, seven.key)
        // A name already shown keeps its own notification.
        assertEquals("Name1", lim.take(NudgeWire.Show.NAME, NudgeWire.Nudge(t, "Name1", "", "msg"), t + 12).n.from)
        lim.clear()
        assertTrue(lim.take(NudgeWire.Show.NAME, NudgeWire.Nudge(t, "Bank", "", "msg"), t + 13).alert)
    }

    // ---- Profile names: cleaned, never over a name set here

    @Test fun profileNameRules() {
        assertEquals("Ann", ProfileWire.nameOf(JSONObject().put("nm", "Ann‮")))
        assertEquals("Ann", ProfileWire.nameOf(JSONObject().put("nm", "​Ann")))
        assertNull(ProfileWire.nameOf(JSONObject().put("nm", "‮​")))
        assertNull(ProfileWire.renameTo("Ann", "Mom", "Eve"))
        assertEquals("Eve", ProfileWire.renameTo("Ann", null, "Eve"))
        assertEquals("Eve", ProfileWire.renameTo("Ann", "", "Eve"))
        assertNull(ProfileWire.renameTo("Ann", null, "Ann"))
        assertNull(ProfileWire.renameTo("Ann", null, null))
        assertEquals("Ann is now called Eve.", ProfileWire.renamedText("Ann", "Eve"))
    }

    // ---- Restore refuses scrypt settings too heavy for a phone

    private fun header(json: String): ByteArray {
        val b = json.toByteArray()
        return "MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(b.size).array() + b + ByteArray(40)
    }

    @Test fun heavyScryptRefused() {
        val salt = java.util.Base64.getEncoder().encodeToString(ByteArray(16))
        listOf(
            """{"v":2,"salt":"$salt","kdf":"scrypt","N":1048576,"r":8,"p":1,"created":"","app":"muninn"}""",
            """{"v":2,"salt":"$salt","kdf":"scrypt","N":65536,"r":8,"p":8,"created":"","app":"muninn"}""",
            """{"v":3,"salt":"$salt","kdf":"scrypt","N":524288,"r":8,"p":2,"created":"","app":"muninn"}""",
        ).forEach { j ->
            try { BackupFormat.header(header(j)); fail(j) } catch (e: BackupFormat.NotABackup) {}
        }
        // What both apps write still opens: 64 MiB with p 2 (v3), and Huginn's older 128 MiB with p 1 (v2).
        BackupFormat.header(header("""{"v":3,"salt":"$salt","kdf":"scrypt","N":65536,"r":8,"p":2,"created":"","app":"huginn"}"""))
        BackupFormat.header(header("""{"v":2,"salt":"$salt","kdf":"scrypt","N":131072,"r":8,"p":1,"created":"","app":"huginn"}"""))
    }

    // ---- A fast first letter after an Enter send isn't erased

    @Test fun typedLetterAfterSendKept() {
        val e = BoxEcho()
        e.send("yes", 1000)
        assertEquals(BoxEcho.SKIP, e.seen("", "", 1010))
        e.typed()
        assertEquals(BoxEcho.PASS, e.seen("s", "", 1100))
        // With no key (an IME putting its letters back), it's still cleared.
        val ime = BoxEcho()
        ime.send("yes", 1000)
        ime.seen("", "", 1010)
        assertEquals(BoxEcho.CLEAR, ime.seen("s", "", 1100))
    }

    // ---- A relay replaying old frames can't make a linked device look older

    @Test fun relayedUnstampedFramesDontCount() {
        val t0 = 1_761_500_000_000L
        val r = LinkReplay()
        assertTrue(r.take("PC", JSONObject(LinkReplay.stamp("""{"t":"send","text":"hi"}""", t0)), t0))
        for (i in 1..6) assertFalse(r.take("PC", JSONObject("""{"t":"send"}"""), t0 + i * 5 * 60_000L, counts = false))
        assertTrue(r.olderOnes().isEmpty())
        for (i in 7..12) assertFalse(r.take("PC", JSONObject("""{"t":"send"}"""), t0 + i * 5 * 60_000L))
        assertEquals(setOf("PC"), r.olderOnes())
    }

    // ---- Video bubbles take the clip's shape

    @Test fun videoBubbleShape() {
        assertEquals(200 to 260, Videos.bubble(480, 640))
        assertEquals(200 to 260, Videos.bubble(0, 0))
        assertEquals(260 to 195, Videos.bubble(640, 480))
        assertEquals(260 to 146, Videos.bubble(1920, 1080))
        assertEquals(260 to 120, Videos.bubble(4000, 100))
    }
}
