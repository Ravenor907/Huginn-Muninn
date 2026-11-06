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

/** Cap v3, picked videos (protocol.md 29, 1.42 rules: never cut): size, bitrate and length rules, who gets what, pacing. As Huginn's --video-selftest. */
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
        // 1.42: direct has no length or size cap beyond the 4 GB transfer limit.
        assertTrue(GroupVideo.fitsFull(info(1920, 1080, ms = 30 * 60_000, bytes = 900_000_000)))
        assertEquals(4L * 1024 * 1024 * 1024, GroupVideo.FULL_MAX_BYTES)
        // The 720p copy: plain 720p30 up to 5 minutes goes as it is; bigger asks to shrink.
        assertTrue(GroupVideo.fitsSmall(info(1280, 720, ms = 5 * 60_000)))
        assertTrue(GroupVideo.fitsSmall(info(720, 1280, ms = 5 * 60_000 + 400)))
        assertFalse(GroupVideo.fitsSmall(info(1280, 720, ms = 5 * 60_000 + 1_000)))
        assertFalse(GroupVideo.fitsSmall(info(1920, 1080)))
        assertFalse(GroupVideo.fitsSmall(info(1280, 720, fps = 60f)))
        assertFalse(GroupVideo.fitsSmall(Info(1280, 720, 0, 30f, 10_000, 5_000_000, plain = false)))
        assertTrue(GroupVideo.biggerThan720(info(1920, 1080)))
        assertTrue(GroupVideo.biggerThan720(info(1080, 1920)))
        assertFalse(GroupVideo.biggerThan720(info(1280, 720)))
        assertFalse(GroupVideo.biggerThan720(info(720, 1280)))
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
        // The full copy: 4.5 Mbps whatever its length (an hour is about 2 GB); a minute still arrives by itself on an
        // older app (under its 50 MB).
        assertEquals(GroupVideo.FULL_VIDEO_BITRATE, GroupVideo.fullBitrate(20_000))
        assertEquals(GroupVideo.FULL_VIDEO_BITRATE, GroupVideo.fullBitrate(60 * 60_000))
        val full60 = (GroupVideo.fullBitrate(60_000) + GroupVideo.FULL_AUDIO_BITRATE).toLong() * 60 / 8
        assertTrue(full60 < 48L * 1024 * 1024)
        // Very long: lowered to stay inside 4 GB, never below the floor.
        val tenHours = 10 * 3_600_000
        assertTrue((GroupVideo.fullBitrate(tenHours) + GroupVideo.FULL_AUDIO_BITRATE).toLong() * (tenHours / 1000) / 8 <= GroupVideo.FULL_MAX_BYTES)
        // The 720p copy: 5 minutes is well under 100 MB.
        assertTrue((GroupVideo.SMALL_VIDEO_BITRATE + GroupVideo.FULL_AUDIO_BITRATE).toLong() * 300 / 8 < 100L * 1024 * 1024)
    }

    @Test
    fun durationRules() {
        assertEquals(GroupVideo.SHORT, GroupVideo.length(20_000))
        assertEquals(GroupVideo.SHORT, GroupVideo.length(20_400))
        assertEquals(GroupVideo.LONG, GroupVideo.length(21_000))
        assertEquals(GroupVideo.LONG, GroupVideo.length(61_000))
        assertEquals(GroupVideo.LONG, GroupVideo.length(5 * 60_000))
        assertEquals(GroupVideo.TOO_LONG, GroupVideo.length(5 * 60_000 + 1_000))
        assertEquals("1:05", GroupVideo.clock(65_000))
        assertEquals("0:20", GroupVideo.clock(19_600))
        assertEquals("1:02:05", GroupVideo.clock(3_725_000))
        assertEquals("Video too long", GroupVideo.TOO_LONG_TITLE)
        assertEquals("Videos over the internet can be up to 5 minutes.", GroupVideo.TOO_LONG_TEXT)
        assertEquals("Shrink to 720p to send?", GroupVideo.SHRINK_TITLE)
        listOf(GroupVideo.SHRINK_TEXT, GroupVideo.TOO_LONG_TEXT, GroupVideo.NOTE).forEach { assertFalse(it, it.contains('\u2014') || it.contains('\u2013')) }
    }

    @Test
    fun deliveryChoice() {
        val min = 60_000
        val wifi = Member("a", sameWifi = true, trusted = false, files = true, v3 = true, newPath = true)
        val trusted = Member("b", sameWifi = false, trusted = true, files = true, v3 = true, newPath = true)
        val far = Member("c", sameWifi = false, trusted = false, files = true, v3 = true, newPath = true)
        val kid = Member("e", sameWifi = true, trusted = true, files = true, v3 = true, kid = true, newPath = true)
        val oldNear = Member("d", sameWifi = true, trusted = true, files = true, v3 = true)
        val oldFar = Member("h", sameWifi = false, trusted = false, files = true, v3 = true)
        val noV3 = Member("g", sameWifi = true, trusted = true, files = true, v3 = false)
        // Direct: full size, any length.
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(wifi, unmetered = false, ms = 10_000))
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(wifi, unmetered = false, ms = 45 * min))
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(trusted, unmetered = true, ms = 45 * min))
        // Not direct (or trusted over mobile data): the 720p copy through FileLink, up to 5 minutes, never the relays.
        assertEquals(GroupVideo.SMALL, GroupVideo.delivery(trusted, unmetered = false, ms = 10_000))
        assertEquals(GroupVideo.SMALL, GroupVideo.delivery(far, unmetered = true, ms = 5 * min))
        assertEquals(GroupVideo.NONE, GroupVideo.delivery(far, unmetered = true, ms = 6 * min))
        // No way to them now (Tor is Ultra privacy's alone): the short relay copy, else nothing.
        assertEquals(GroupVideo.RELAY, GroupVideo.delivery(far.copy(reach = false), unmetered = true, ms = 15_000))
        assertEquals(GroupVideo.NONE, GroupVideo.delivery(far.copy(reach = false), unmetered = true, ms = min))
        // A kids' device gets the 720p path, even close by.
        assertEquals(GroupVideo.SMALL, GroupVideo.delivery(kid, unmetered = true, ms = 10_000))
        // Older apps: full size up to a minute when direct, the relay copy up to 20 s, else nothing.
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(oldNear, unmetered = true, ms = min))
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(oldNear, unmetered = true, ms = 15_000))
        assertEquals(GroupVideo.NONE, GroupVideo.delivery(oldNear, unmetered = true, ms = 2 * min))
        assertEquals(GroupVideo.RELAY, GroupVideo.delivery(oldFar, unmetered = true, ms = 20_000))
        assertEquals(GroupVideo.NONE, GroupVideo.delivery(oldFar, unmetered = true, ms = 30_000))
        assertEquals(GroupVideo.RELAY, GroupVideo.delivery(noV3, unmetered = true, ms = 10_000))
        // A group: each member their best path.
        val plan = GroupVideo.plan(listOf(wifi, trusted, far, oldFar), unmetered = false, ms = 15_000)
        assertEquals(mapOf("a" to GroupVideo.FULL, "b" to GroupVideo.SMALL, "c" to GroupVideo.SMALL, "h" to GroupVideo.RELAY), plan)
        val said = GroupVideo.summary(plan) { it.uppercase() }
        assertTrue(said, said.contains("Full quality to A") && said.contains("720p copy to B and C.") && said.contains("to H, whose app is older"))
        assertEquals("Nobody else is here yet.", GroupVideo.summary(emptyMap()) { it })
        assertEquals("A, B and 3 more", GroupVideo.names(listOf("A", "B", "C", "D", "E")))
        // Never cut: over 5 minutes asks only when someone isn't direct; bigger than 720p asks only when someone gets a copy.
        val long = 6 * min
        assertTrue(GroupVideo.tooLong(long, GroupVideo.plan(listOf(wifi, far), true, long)))
        assertFalse(GroupVideo.tooLong(long, GroupVideo.plan(listOf(wifi, trusted), true, long)))
        assertFalse(GroupVideo.tooLong(5 * min, GroupVideo.plan(listOf(far), true, 5 * min)))
        val hd = info(1920, 1080)
        assertTrue(GroupVideo.needsShrink(hd, GroupVideo.plan(listOf(wifi, far), true, 10_000)))
        assertTrue(GroupVideo.needsShrink(hd, GroupVideo.plan(listOf(oldFar), true, 10_000)))
        assertFalse(GroupVideo.needsShrink(hd, GroupVideo.plan(listOf(wifi), true, 10_000)))
        assertFalse(GroupVideo.needsShrink(info(1280, 720), GroupVideo.plan(listOf(far), true, 10_000)))
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
        assertTrue(GroupVideo.vidOk(45 * 60_000))   // 1.42: longer direct videos
        assertTrue(GroupVideo.vidOk(GroupVideo.VID_MAX_MS))
        assertFalse(GroupVideo.vidOk(GroupVideo.VID_MAX_MS + 1))
        assertFalse(GroupVideo.vidOk(0))
        // An older app takes only up to a minute: its "vid" is kept within that.
        assertEquals(60_000, GroupVideo.vidFor(45 * 60_000, newPath = false))
        assertEquals(45 * 60_000, GroupVideo.vidFor(45 * 60_000, newPath = true))
        assertFalse(GroupVideo.vidOk(1.5))
        assertFalse(GroupVideo.vidOk("15000"))
        assertTrue(GroupVideo.CAP in Proto06.caps(Proto06.CAPS))
        // The 720p copy's path is FileLink's Tor cap (f4).
        assertEquals(io.github.ravenor907.muninn.net.TorFiles.CAP, GroupVideo.NEW_PATH_CAP)
    }

    @Test
    fun familyGetsFullQualityAnyLengthOnAnyNetwork() {
        val fam = GroupVideo.Member("a", sameWifi = false, trusted = false, files = true, v3 = true, newPath = true, family = true)
        assertEquals(GroupVideo.FULL, GroupVideo.delivery(fam, false, 3 * 3_600_000))
        // A Kids' Device stays on its own copy, whoever it is.
        assertEquals(GroupVideo.SMALL, GroupVideo.delivery(fam.copy(kid = true), false, 60_000))
        // Past the relay's month "family" is off: the 720p copy, as for anyone not direct.
        assertEquals(GroupVideo.SMALL, GroupVideo.delivery(fam.copy(family = false), false, 60_000))
        assertFalse(GroupVideo.tooLong(3 * 3_600_000, mapOf("a" to GroupVideo.FULL)))
    }
}
