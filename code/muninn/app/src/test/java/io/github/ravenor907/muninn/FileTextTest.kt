package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.FileText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a file on its way says, and its card's states (the same words as Huginn's FileLink::statusText). */
class FileTextTest {
    private val mb = 1024L * 1024

    @Test
    fun straightTransferLines() {
        assertEquals("Getting ready", FileText.line("connecting", true, 0, 10, "", "Ana"))
        assertEquals("Waiting for Ana to accept", FileText.line("offered", true, 0, 10, "", "Ana"))
        assertEquals("Sending 42%", FileText.line("sending", true, 42, 100, "", "Ana"))
        assertEquals("Receiving 42%", FileText.line("sending", false, 42, 100, "", "Ana"))
        assertEquals("Checking it", FileText.line("sending", false, 100, 100, "Checking it", "Ana"))
        assertEquals("Sent · Saved in their Downloads", FileText.line("done", true, 10, 10, "", "Ana"))
        assertEquals("Saved to Downloads", FileText.line("done", false, 10, 10, "", "Ana"))
        assertEquals("Saved to Pictures/Ana", FileText.line("done", false, 10, 10, "", "Ana", "Pictures/Ana"))   // Save files to
        assertEquals("Wants to send you this", FileText.line("asking", false, 0, 10, "", "Ana"))
        assertEquals("They're offline. Try again when they're back.", FileText.line("failed", true, 0, 10, "They're offline. Try again when they're back.", "Ana"))
        assertEquals("Didn't go through", FileText.line("failed", true, 0, 10, "", "Ana"))
    }

    @Test
    fun percentStaysInRange() {
        assertEquals(0, FileText.percent(5, 0))
        assertEquals(0, FileText.percent(-3, 10))
        assertEquals(100, FileText.percent(20, 10))
        assertEquals(99, FileText.percent(999, 1000))
    }

    @Test
    fun internetRouteLines() {
        assertEquals("Sending", FileText.relayLine("sending", false, null))
        assertEquals("Sending 50%", FileText.relayLine("sending", false, 0.5f))
        assertEquals("Not sent · Tap to retry", FileText.relayLine("sending", true, 0.5f))
        assertEquals("Sent · Arrives when they're online", FileText.relayLine("sent", false, 1f))
        assertEquals("Sent · Saved in their Downloads", FileText.relayLine("delivered", false, null))
        assertEquals("Sent · Saved in their Downloads", FileText.relayLine("read", false, null))
        assertTrue(FileText.tooBig().contains("2.0 MB"))
    }

    @Test
    fun paceAndTimeLeft() {
        assertEquals("", FileText.pace(0, 100, 5000))
        assertEquals("", FileText.pace(10, 100, 500))
        // 10 MB in 5 s is 2 MB/s; 40 MB left is 20 s.
        assertEquals("2.0 MB/s · 20 s left", FileText.pace(10 * mb, 50 * mb, 5000))
        assertEquals("2.0 MB/s", FileText.pace(10 * mb, 10 * mb, 5000))
        assertEquals("1 s left", FileText.left(0))
        assertEquals("59 s left", FileText.left(59))
        assertEquals("1 min left", FileText.left(60))
        assertEquals("2 min left", FileText.left(61))
        assertEquals("1 h 5 min left", FileText.left(3600 + 5 * 60))
    }

    @Test
    fun iconsAndApps() {
        assertEquals("image", FileText.icon("a.JPG"))
        assertEquals("video", FileText.icon("clip.mp4"))
        assertEquals("audio", FileText.icon("song.mp3"))
        assertEquals("text", FileText.icon("notes.pdf"))
        assertEquals("file", FileText.icon("game.apk"))
        assertEquals("file", FileText.icon("noext"))
        assertTrue(FileText.isApp("game.APK"))
        assertFalse(FileText.isApp("apk.txt"))
    }

    @Test
    fun aCardShowsAtOnceAndCancelStopsIt() {
        val id = FileLink.preparing("peerA", "report.pdf", 3 * mb)
        assertEquals("connecting", FileLink.all.value[id]?.state)
        assertTrue(FileLink.canCancel(id))
        FileLink.cancel(id)
        assertEquals("failed", FileLink.all.value[id]?.state)
        assertEquals("Stopped", FileLink.all.value[id]?.note)
        assertTrue(FileLink.isStopped(id))
        assertFalse(FileLink.canCancel(id))
        FileLink.dismiss(id)
        assertNull(FileLink.all.value[id])
    }

    @Test
    fun aFileThatCantGoSaysWhy() {
        FileLink.failed("peerB", "movie.mkv", 900 * mb, FileText.tooBig(), "card-1")
        val p = FileLink.all.value["card-1"]!!
        assertEquals("failed", p.state)
        assertEquals(FileText.tooBig(), FileText.line(p.state, p.mine, p.done, p.size, p.note, "Ana"))
        assertFalse(FileLink.canRetry("card-1"))   // nothing to send again
        FileLink.dismiss("card-1")
        assertNull(FileLink.all.value["card-1"])
    }

    @Test
    fun aFileOverTheInternetRouteShowsHowFarThenGoes() {
        FileLink.relayIn("msg-1", "peerC", "photo.zip", 3, 4)
        val p = FileLink.all.value["msg-1"]!!
        assertEquals("Receiving 75%", FileText.line(p.state, p.mine, p.done, p.size, p.note, "Ana"))
        assertFalse(FileLink.canCancel("msg-1"))   // it just arrives
        // A card for the same file that couldn't come straight goes with it.
        FileLink.failed("peerC", "photo.zip", 10, "x", "card-2")
        FileLink.all.value["card-2"]?.let { assertTrue(it.mine) }
        FileLink.relayInDone("msg-1", "peerC", "photo.zip")
        assertNull(FileLink.all.value["msg-1"])
        // Mine stays (only theirs with that name goes).
        assertEquals("failed", FileLink.all.value["card-2"]?.state)
        FileLink.dismiss("card-2")
    }
}
