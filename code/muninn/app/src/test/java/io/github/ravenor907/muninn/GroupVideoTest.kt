package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.GroupVideo
import io.github.ravenor907.muninn.net.GroupVideo.Info
import io.github.ravenor907.muninn.net.GroupVideo.Member
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.VideoPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cap v3, picked videos (protocol.md 29): size, bitrate and length rules, who gets what, pacing. As Huginn's --video-selftest. */
class GroupVideoTest {
    private fun info(w: Int, h: Int, fps: Float = 30f, ms: Int = 10_000, bytes: Long = 5_000_000, rot: Int = 0) = Info(w, h, rot, fps, ms, bytes)

    @Test
    fun sizeRules() {
        assertTrue(GroupVideo.fitsFull(info(1920, 1080)))
        assertTrue(GroupVideo.fitsFull(info(1080, 1920)))                  // upright phone video
        assertTrue(GroupVideo.fitsFull(info(1080, 1920, rot = 90)))
        assertFalse(GroupVideo.fitsFull(info(3840, 2160)))                  // 4K is converted
        assertFalse(GroupVideo.fitsFull(info(1920, 1080, fps = 60f)))       // 60 fps is converted
        assertTrue(GroupVideo.fitsFull(info(1920, 1080, fps = 30.3f)))      // 29.97 or a little over still counts as 30
        assertFalse(GroupVideo.fitsFull(info(1920, 1080, bytes = GroupVideo.FULL_MAX_BYTES + 1)))
        assertFalse(GroupVideo.fitsFull(info(1920, 1080, ms = 61_000)))
        assertTrue(GroupVideo.needsConvert(info(3840, 2160)))
        assertTrue(GroupVideo.needsConvert(info(1280, 720, fps = 60f)))
        assertFalse(GroupVideo.needsConvert(info(1280, 720)))
        assertTrue(GroupVideo.fitsRelay(info(1280, 720, bytes = 2_000_000)))
        assertFalse(GroupVideo.fitsRelay(info(1920, 1080, bytes = 2_000_000)))
        assertFalse(GroupVideo.fitsRelay(info(1280, 720, bytes = GroupVideo.RELAY_BYTES + 1L)))
        assertFalse(GroupVideo.fitsRelay(info(1280, 720, ms = 25_000, bytes = 2_000_000)))
        assertEquals(1080 to 1920, GroupVideo.upright(info(1920, 1080, rot = 90)))
        // Shrinking keeps the shape, never grows, and gives even sides.
        assertEquals(1280 to 720, GroupVideo.scaled(3840, 2160, GroupVideo.RELAY_LONG, GroupVideo.RELAY_SHORT))
        assertEquals(720 to 1280, GroupVideo.scaled(1080, 1920, GroupVideo.RELAY_LONG, GroupVideo.RELAY_SHORT))
        assertEquals(640 to 480, GroupVideo.scaled(640, 480, GroupVideo.RELAY_LONG, GroupVideo.RELAY_SHORT))
        val (w, h) = GroupVideo.scaled(1001, 777, GroupVideo.RELAY_LONG, GroupVideo.RELAY_SHORT)
        assertEquals(0, w % 2); assertEquals(0, h % 2)
        // The relay copy fits every app since m2 (200 pieces at most), and the pieces are the same size as photos'.
        assertEquals(Photos.PIECE, GroupVideo.PIECE)
        assertTrue(GroupVideo.RELAY_PIECES <= Photos.MAX_PIECES)
        assertTrue(GroupVideo.relayFits(GroupVideo.RELAY_BYTES.toLong()))
        assertFalse(GroupVideo.relayFits(GroupVideo.RELAY_BYTES + 1L))
        assertFalse(GroupVideo.relayFits(0))
    }

    @Test
    fun bitrateRules() {
        // A 20 s clip: video and sound together stay inside the pieces, with room to spare.
        val b20 = GroupVideo.relayBitrate(20_000)
        val bytes20 = (b20 + GroupVideo.RELAY_AUDIO_BITRATE).toLong() * 20 / 8
        assertTrue("20 s fits: $bytes20", bytes20 <= GroupVideo.RELAY_BYTES)
        assertTrue("720p needs about a megabit: $b20", b20 >= 900_000)
        // Shorter clips get more, up to the cap; never below the floor.
        assertTrue(GroupVideo.relayBitrate(10_000) > b20)
        assertEquals(GroupVideo.MAX_RELAY_VIDEO_BITRATE, GroupVideo.relayBitrate(2_000))
        assertEquals(GroupVideo.MIN_VIDEO_BITRATE, GroupVideo.relayBitrate(10 * 60_000))
        // A file that says its sound is bigger leaves less for the picture.
        assertTrue(GroupVideo.relayBitrate(20_000, audioBps = 256_000) < b20)
        // Fewer pieces (an older limit) means a lower bitrate.
        assertTrue(GroupVideo.relayBitrate(20_000, Photos.INTERNET_PIECES) < b20)
        // A copy that ran over is made again lower, by at least the share.
        val again = GroupVideo.retryBitrate(b20, GroupVideo.RELAY_BYTES + 400_000L, 20_000)
        assertTrue(again <= (b20 * GroupVideo.RETRY_SHARE).toInt())
        assertTrue(again >= GroupVideo.MIN_VIDEO_BITRATE)
        // The full copy: 4.5 Mbps, lowered so a minute stays under the limit.
        assertEquals(GroupVideo.FULL_VIDEO_BITRATE, GroupVideo.fullBitrate(20_000))
        val full60 = (GroupVideo.fullBitrate(60_000) + GroupVideo.FULL_AUDIO_BITRATE).toLong() * 60 / 8
        assertTrue(full60 <= GroupVideo.FULL_MAX_BYTES)
        assertTrue(GroupVideo.FULL_MAX_BYTES < 50L * 1024 * 1024)   // under FileLink.AUTO_BYTES: arrives by itself
    }

    @Test
    fun durationRules() {
        assertEquals(GroupVideo.SHORT, GroupVideo.length(20_000))
        assertEquals(GroupVideo.SHORT, GroupVideo.length(20_400))
        assertEquals(GroupVideo.LONG, GroupVideo.length(21_000))
        assertEquals(GroupVideo.LONG, GroupVideo.length(60_000))
        assertEquals(GroupVideo.TOO_LONG, GroupVideo.length(61_000))
        assertEquals(40_000, GroupVideo.lastStart(60_000))
        assertEquals(0, GroupVideo.lastStart(5_000))
        assertEquals(10_000 to 30_000, GroupVideo.trim(60_000, 10_000))
        assertEquals(40_000 to 60_000, GroupVideo.trim(60_000, 55_000))   // kept inside the video
        assertEquals(0 to 20_000, GroupVideo.trim(60_000, -5))
        assertEquals("1:05", GroupVideo.clock(65_000))
        assertEquals("0:20", GroupVideo.clock(19_600))
    }

    @Test
    fun deliveryChoice() {
        val wifi = Member("a", sameWifi = true, trusted = false, files = true, v3 = true)
        val trusted = Member("b", sameWifi = false, trusted = true, files = true, v3 = true)
        val far = Member("c", sameWifi = false, trusted = false, files = true, v3 = true)
        val old = Member("d", sameWifi = true, trusted = true, files = true, v3 = false)
        val kid = Member("e", sameWifi = true, trusted = true, files = true, v3 = true, kid = true)
        val ultra = Member("f", sameWifi = true, trusted = true, files = true, v3 = true, relayOnly = true)
        val noFiles = Member("g", sameWifi = true, trusted = true, files = false, v3 = true)
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(wifi, unmetered = false))
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(trusted, unmetered = true))
        assertEquals(GroupVideo.RELAY, GroupVideo.delivery(trusted, unmetered = false))   // not over mobile data
        assertEquals(GroupVideo.RELAY, GroupVideo.delivery(far, unmetered = true))
        for (m in listOf(old, kid, ultra, noFiles)) assertEquals(m.k, GroupVideo.RELAY, GroupVideo.delivery(m, unmetered = true))
        // A long video in full: waits for those it can reach straight, nothing for the rest.
        assertEquals(GroupVideo.FULL, GroupVideo.later(trusted))
        assertEquals(GroupVideo.FULL, GroupVideo.later(wifi))
        assertEquals(GroupVideo.NONE, GroupVideo.later(far))
        assertEquals(GroupVideo.NONE, GroupVideo.later(kid))
        val plan = GroupVideo.plan(listOf(wifi, trusted, far), unmetered = false, inFull = false)
        assertEquals(mapOf("a" to GroupVideo.FULL, "b" to GroupVideo.RELAY, "c" to GroupVideo.RELAY), plan)
        val said = GroupVideo.summary(plan) { it.uppercase() }
        assertTrue(said, said.contains("straight to A") && said.contains("internet to B and C"))
        assertEquals("Nobody else is here yet.", GroupVideo.summary(emptyMap()) { it })
        assertEquals("A, B and 3 more", GroupVideo.names(listOf("A", "B", "C", "D", "E")))
        assertTrue(GroupVideo.progressLine("Anna", GroupVideo.RELAY, 48, 192, false).endsWith("25% sent"))
        assertTrue(GroupVideo.progressLine("Anna", GroupVideo.RELAY, 192, 192, false).endsWith("sent"))
    }

    @Test
    fun pacing() {
        val p = GroupVideo.Pacer(3)
        val t0 = 1_000_000L
        val slots = (0 until 9).map { p.take(t0) }
        // Three a second, evenly, never two at once.
        assertEquals(t0, slots[0])
        assertTrue(slots.zipWithNext().all { (a, b) -> b - a == p.gapMs })
        assertTrue(slots.last() - slots.first() >= 2_600)
        // A relay's "rate-limited" stops everything for a minute.
        p.limited(t0 + 5_000)
        assertTrue(p.pausedAt(t0 + 5_001))
        assertTrue(p.take(t0 + 5_001) >= t0 + 5_000 + GroupVideo.BACKOFF_MS)
        assertFalse(p.pausedAt(t0 + 5_000 + GroupVideo.BACKOFF_MS))
        // A group video to six people at the cap: well inside the 15 minutes a half-arrived video is kept.
        assertTrue(GroupVideo.paceMs(GroupVideo.RELAY_PIECES * 6) < 15 * 60_000L)
        assertTrue(GroupVideo.isRateLimit("rate-limited: slow down"))
        assertFalse(GroupVideo.isRateLimit("blocked: no"))
        // One job per member and message: a retry while it's queued adds nothing.
        val k = VideoPace.key("member", "env-" + System.nanoTime())
        assertTrue(VideoPace.go(k, listOf({ }, { })))
        assertFalse(VideoPace.go(k, listOf({ })))
        assertFalse(VideoPace.go(VideoPace.key("member", "empty"), emptyList()))
    }

    @Test
    fun offerField() {
        assertTrue(GroupVideo.vidOk(null))
        assertTrue(GroupVideo.vidOk(15_000))
        assertTrue(GroupVideo.vidOk(60_000L))
        assertFalse(GroupVideo.vidOk(0))
        assertFalse(GroupVideo.vidOk(60_001))
        assertFalse(GroupVideo.vidOk(1.5))
        assertFalse(GroupVideo.vidOk("15000"))
        assertTrue(GroupVideo.CAP in Proto06.caps(Proto06.CAPS))
    }
}
