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

    @Test
    fun onlySmallFilesFromTrustedPeopleArriveByThemselves() {
        val mb = 1024L * 1024
        assertTrue(FileLink.arrivesByItself(10 * mb, true))
        assertTrue(FileLink.arrivesByItself(50 * mb - 1, true))
        // 50 MB and bigger asks Accept or Decline; Data saver on mobile data waits for a tap.
        assertFalse(FileLink.arrivesByItself(50 * mb, true))
        assertFalse(FileLink.arrivesByItself(2_000 * mb, true))
        assertFalse(FileLink.arrivesByItself(1 * mb, false))
        // A file small enough for the internet route goes that way when no answer comes soon; a bigger one waits as
        // long as the other side may ask first (Data saver, or 50 MB and more).
        assertEquals(FileLink.RELAY_AFTER_MS, FileLink.answerMs(1 * mb))
        assertEquals(FileLink.RELAY_AFTER_MS, FileLink.answerMs(io.github.ravenor907.muninn.net.FileText.RELAY_MAX))
        assertEquals(FileLink.ASK_ANSWER_MS, FileLink.answerMs(io.github.ravenor907.muninn.net.FileText.RELAY_MAX + 1))
        assertEquals(FileLink.ASK_ANSWER_MS, FileLink.answerMs(60 * mb))
    }

    /** An older sender (no [FileLink.ASK_CAP]) gives up after 2 minutes: its big files arrive as before, without asking. */
    @Test
    fun bigFilesFromOlderSendersArriveAsBefore() {
        val mb = 1024L * 1024
        assertTrue(FileLink.asksFirst(60 * mb, true))
        assertFalse(FileLink.asksFirst(60 * mb, false))
        assertFalse(FileLink.asksFirst(10 * mb, true))
        assertTrue(FileLink.arrivesByItself(60 * mb, true, senderWaits = false))
        assertFalse(FileLink.arrivesByItself(60 * mb, false, senderWaits = false))
        assertFalse(FileLink.arrivesByItself(60 * mb, true, senderWaits = true))
    }
}
