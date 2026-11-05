package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.FileLink
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Files straight between phones (section 14): the window, the acks, and the sealing. */
class FileLinkTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun everyPieceArrivesEvenWhenSomeAreLost() {
        val n = 500
        val s = FileLink.Sender(n)
        val r = FileLink.Receiver(n)
        var now = 0L
        var rounds = 0
        while (!s.done && rounds < 10_000) {
            for (i in s.due(now)) if (i % 7 != 3 || now > 2_000) r.take(i)   // every seventh piece lost at first
            val (below, bits) = r.ack()
            s.ack(below, bits)
            now += 100; rounds++
        }
        assertTrue(s.done)
        assertTrue(r.done)
    }

    @Test
    fun theWindowLimitsWhatIsInFlight() {
        val s = FileLink.Sender(1000)
        assertEquals(FileLink.WINDOW, s.due(0).size)
        assertTrue(s.due(10).isEmpty())                         // nothing confirmed, nothing late yet
        assertEquals(FileLink.WINDOW, s.due(FileLink.RTO_MS).size)   // all of it again once it's late
    }

    @Test
    fun acksNameTheFirstGapAndWhatFollows() {
        val r = FileLink.Receiver(100)
        listOf(0, 1, 2, 4, 6).forEach { r.take(it) }
        val (below, bits) = r.ack()
        assertEquals(3, below)
        assertEquals(0b1010L, bits)
        assertFalse(r.take(4))
    }

    @Test
    fun sealedPacketsOpenOnlyWithTheRightKeyAndDirection() {
        val key = FileLink.key(ByteArray(32) { it.toByte() }, id)
        val p = FileLink.seal(key, id, FileLink.DATA, 0, 7, byteArrayOf(1, 2, 3))
        val (t, back) = FileLink.open(key, id, 0, p)!!
        assertEquals(FileLink.DATA, t)
        assertArrayEquals(byteArrayOf(1, 2, 3), back)
        assertNull(FileLink.open(key, id, 1, p))
        assertNull(FileLink.open(FileLink.key(ByteArray(32), id), id, 0, p))
        p[p.size - 1] = (p[p.size - 1] + 1).toByte()
        assertNull(FileLink.open(key, id, 0, p))
    }

    @Test
    fun offersAreChecked() {
        val ok = JSONObject().put("t", "offer").put("id", id).put("name", "a.pdf").put("size", 5).put("sha", "a".repeat(64)).put("e", "k").put("c", JSONArray())
        assertTrue(FileLink.ok(ok))
        assertFalse(FileLink.ok(JSONObject(ok.toString()).put("name", "../a")))
        assertFalse(FileLink.ok(JSONObject(ok.toString()).put("size", 0)))
        assertFalse(FileLink.ok(JSONObject(ok.toString()).put("id", "x")))
        assertEquals(3, FileLink.pieces(2 * FileLink.CHUNK + 1L))
    }

    /** 1.42: every file and video asks first. Only Always (trusted or Family) or the rest of an accepted file don't. */
    @Test
    fun everyFileAsksFirst() {
        val mb = 1024L * 1024
        assertFalse(FileLink.arrivesByItself(always = false, resuming = false, mayDownload = true))
        assertTrue(FileLink.arrivesByItself(always = true, resuming = false, mayDownload = true))
        assertTrue(FileLink.arrivesByItself(always = false, resuming = true, mayDownload = true))
        // Data saver on mobile data still asks.
        assertFalse(FileLink.arrivesByItself(always = true, resuming = false, mayDownload = false))
        // Always only for someone trusted or Family.
        assertTrue(FileLink.mayAlways(FileLink.BADGE_TRUSTED) && FileLink.mayAlways(FileLink.BADGE_FAMILY))
        assertFalse(FileLink.mayAlways(FileLink.BADGE_CONTACT) || FileLink.mayAlways(FileLink.BADGE_NOT))
        // Cap f4: queued a day. Older apps (full-size videos only) as before.
        assertEquals(FileLink.LONG_ANSWER_MS, FileLink.answerMs(1 * mb, f4 = true))
        assertEquals(FileLink.RELAY_AFTER_MS, FileLink.answerMs(1 * mb))
        assertEquals(FileLink.ASK_ANSWER_MS, FileLink.answerMs(60 * mb))
        assertEquals(FileLink.LONG_ANSWER_MS, FileLink.answerMs(60 * mb, true))
    }

    /** The receiving card's words (the same as Huginn's FileLink::typeWord and riskyName). */
    @Test
    fun receivingCardWords() {
        assertEquals("a ZIP file", FileLink.typeWord("photos-trip.zip"))
        assertEquals("an APK file", FileLink.typeWord("game.apk"))
        assertEquals("an EXE file", FileLink.typeWord("setup.EXE"))
        assertEquals("a PDF file", FileLink.typeWord("a.pdf"))
        assertEquals("a file", FileLink.typeWord("README"))
        for (n in listOf("a.zip", "a.apk", "a.exe", "a.bat", "a.msi", "a.js", "a.scr", "a.cmd", "a.ps1", "a.vbs", "a.jar", "a.sh", "a.AppImage", "a.deb", "a.rpm"))
            assertTrue(n, FileLink.risky(n))
        assertFalse(FileLink.risky("photo.jpg") || FileLink.risky("notes.txt"))
        assertEquals("Only open this if you were expecting it.", FileLink.RISKY_NOTE)
        assertEquals("Sends when Jo's phone is online", FileLink.queuedNote("Jo", "phone"))
        assertEquals("Sends when Jo's computer is online", FileLink.queuedNote("Jo", "computer"))
        assertEquals("Sends when Jo is online", FileLink.queuedNote("Jo", ""))
        assertEquals("Didn't send. Try again.", FileLink.NOT_SENT)
        assertEquals("Nearby", FileLink.methodLabel("lan"))
        assertEquals("Trusted direct", FileLink.methodLabel("direct"))
        assertEquals("Tor, slower", FileLink.methodLabel("tor", 10 * 1024 * 1024))
        assertEquals("Tor, slower (about 21 min)", FileLink.methodLabel("tor", 1_200L * 1024 * 1024))
    }

    /** Cap f3: a receiver that kept 300 pieces says so in its first ack, and the sender goes on from 300. */
    @Test fun resumeGoesOnFromTheKeptPart() {
        val r = FileLink.Receiver(500)
        r.preload(300)
        val (below, bits) = r.ack()
        assertEquals(300, below)
        val s = FileLink.Sender(500)
        s.ack(below, bits)
        assertEquals(300, s.due(0).first())
    }
}
