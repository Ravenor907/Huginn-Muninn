package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.TransferView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one banner for a file on its way: the strip on phones and tablets, the banner on a Kids' TV. */
class TransferViewTest {
    private fun nearby(sending: Boolean = true, preparing: Boolean = false, started: Boolean = true, done: Long = 42, total: Long = 100,
                       names: List<String> = listOf("game.apk"), peer: String = "Kids' TV", id: String = "a") =
        TransferView.nearby(id, true, sending, preparing, started, done, total, names, peer)!!

    @Test
    fun nothingRunningShowsNothing() {
        assertNull(TransferView.nearby("a", false, true, false, true, 0, 0, emptyList(), ""))
        assertNull(TransferView.pick(null, null, emptyList(), { "Ana" }))
        assertNull(TransferView.ended("a", true, ""))
    }

    @Test
    fun senderSeesABarFromTheStart() {
        val ready = nearby(preparing = true, started = false, done = 0)
        assertEquals("Getting ready", ready.line)
        assertEquals(0f, ready.fraction)
        assertTrue(ready.running)
        val waiting = nearby(started = false, done = 0)
        assertEquals("Waiting for Kids' TV to accept", waiting.line)
        assertEquals(0f, waiting.fraction)
        assertEquals("Waiting to accept", nearby(started = false, done = 0, peer = "").line)
        val moving = nearby()
        assertEquals("Sending 42%", moving.line)
        assertEquals("game.apk", moving.title)
        assertEquals(0.42f, moving.fraction!!, 0.0001f)
    }

    @Test
    fun receiverSeesPercent() {
        val b = nearby(sending = false, done = 0)
        assertEquals("Receiving 0%", b.line)
        assertEquals("Receiving 42%", nearby(sending = false).line)
        assertEquals("Receiving 100%", nearby(sending = false, done = 500, total = 100).line)
        assertEquals(1f, nearby(sending = false, done = 500, total = 100).fraction)
        assertEquals("3 files", nearby(names = listOf("a", "b", "c")).title)
        assertEquals("Files", nearby(names = emptyList()).title)
        assertEquals(0f, nearby(total = 0, done = 0).fraction)
    }

    @Test
    fun endedSaysHowItWent() {
        val ok = TransferView.ended("a", true, "File from Dad: game.apk · Saved to Downloads")!!
        assertEquals("File from Dad: game.apk · Saved to Downloads", ok.title)
        assertFalse(ok.running)
        assertTrue(ok.ok)
        assertNull(ok.fraction)
        val bad = TransferView.ended("a", false, "Kids' TV turned the files down.")!!
        assertFalse(bad.ok)
        // One transfer running and the same one ended are different banners: hiding one doesn't hide the other.
        assertNotEquals(nearby(id = "a").key, ok.key)
        assertNotEquals(TransferView.ended("b", true, "x")!!.key, TransferView.ended("c", true, "x")!!.key)
    }

    private fun p(id: String, state: String, mine: Boolean = true, done: Long = 0, size: Long = 100, peer: String = "ana", note: String = "") =
        FileLink.Progress(id, peer, "photo.jpg", size, mine, state, done, note)

    @Test
    fun straightTransfers() {
        assertEquals("Getting ready", TransferView.link(p("1", "connecting"), "Ana")!!.line)
        assertEquals(0f, TransferView.link(p("1", "connecting"), "Ana")!!.fraction)
        assertEquals("Waiting for Ana to accept", TransferView.link(p("1", "offered"), "Ana")!!.line)
        val sending = TransferView.link(p("1", "sending", done = 42), "Ana")!!
        assertEquals("Sending 42%", sending.line)
        assertEquals(0.42f, sending.fraction!!, 0.0001f)
        assertEquals("Receiving 42%", TransferView.link(p("1", "sending", mine = false, done = 42), "Ana")!!.line)
        val sent = TransferView.link(p("1", "done", done = 100), "Ana")!!
        assertEquals("Sent · Saved in their Downloads", sent.line)
        assertFalse(sent.running)
        assertNull(sent.fraction)
        assertEquals("Saved to Downloads", TransferView.link(p("1", "done", mine = false), "Ana")!!.line)
        val failed = TransferView.link(p("1", "failed", note = "The connection dropped. Try again."), "Ana")!!
        assertFalse(failed.ok)
        assertEquals("The connection dropped. Try again.", failed.line)
        // Waiting for an answer here: the chat's own card asks, not the banner.
        assertNull(TransferView.link(p("1", "asking", mine = false), "Ana"))
        assertNull(TransferView.link(p("1", "waiting", mine = false), "Ana"))
        // Ending shows again even when the running one was hidden.
        assertNotEquals(TransferView.link(p("1", "sending"), "Ana")!!.key, sent.key)
    }

    @Test
    fun pickOrder() {
        val n = nearby()
        val e = TransferView.ended("z", true, "Sent 1 file")!!
        val running = p("1", "sending", done = 10)
        val finished = p("2", "done")
        val who = { _: String -> "Ana" }
        assertEquals(n.key, TransferView.pick(n, e, listOf(running, finished), who)!!.key)
        assertEquals(TransferView.link(running, "Ana")!!.key, TransferView.pick(null, e, listOf(finished, running), who)!!.key)
        assertEquals(e.key, TransferView.pick(null, e, listOf(finished), who)!!.key)
        assertEquals(TransferView.link(finished, "Ana")!!.key, TransferView.pick(null, null, listOf(finished), who)!!.key)
        // Hidden ones are skipped; the next one shows.
        assertEquals(TransferView.link(running, "Ana")!!.key, TransferView.pick(n, e, listOf(running), who, hidden = setOf(n.key))!!.key)
        assertNull(TransferView.pick(null, e, emptyList(), who, hidden = setOf(e.key)))
        // A chat that's open shows its own cards: its transfers stay out of the strip.
        assertNull(TransferView.pick(null, null, listOf(running, finished), who, skipPeer = "ana"))
        assertEquals(n.key, TransferView.pick(n, null, listOf(running), who, skipPeer = "ana")!!.key)
    }
}
