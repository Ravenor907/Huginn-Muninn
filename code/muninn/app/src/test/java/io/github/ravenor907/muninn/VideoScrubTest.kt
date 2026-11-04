package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.GroupVideo
import io.github.ravenor907.muninn.net.GroupVideo.Info
import io.github.ravenor907.muninn.net.Mp4Scrub
import io.github.ravenor907.muninn.net.VideoPace
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round 19: picked videos lose their location and camera details (Mp4Scrub), only plain H.264 and AAC in MP4 goes as it
 * is, and copies keep both sides within the limits. Huginn's --chat-selftest checks the same on its side.
 */
class VideoScrubTest {
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        body.forEach { b.write(it) }
        return u32(8L + b.size()) + type.toByteArray(Charsets.ISO_8859_1) + b.toByteArray()
    }
    private fun zeros(n: Int) = ByteArray(n)
    private fun hdlr(h: String) = box("hdlr", u32(0), u32(0), h.toByteArray(), zeros(13))
    private fun stbl(offset: Long, size: Long) = box("stbl",
        box("stsz", u32(0), u32(0), u32(1), u32(size)),
        box("stsc", u32(0), u32(1), u32(1), u32(1), u32(1)),
        box("stco", u32(0), u32(1), u32(offset)))
    private fun mvhd() = box("mvhd", u32(0), u32(0x11111111), u32(0x22222222), u32(1000), u32(10_000), zeros(80))
    private fun tkhd() = box("tkhd", u32(0), u32(0x33333333), u32(0x44444444), zeros(64), u32(1920L shl 16), u32(1080L shl 16))
    private fun mdhd() = box("mdhd", u32(0), u32(0x55555555), u32(0x66666666), u32(1000), u32(10_000), u32(0))

    private val video = "VIDEOFRAMES-0123456789".toByteArray()
    private val gps = "GPS+37.7749-122.4194".toByteArray()

    /**
     * An MP4 like a phone's: ftyp, mdat (video then a GPS sample), moov with a video track, a timed metadata track pointing
     * at the GPS sample, udta with ©xyz, and a top-level uuid (XMP). [tail]: bytes after the last box.
     */
    private fun phoneMp4(tail: ByteArray = ByteArray(0)): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray(), u32(0))
        val mdatAt = ftyp.size.toLong()
        val mdat = box("mdat", video, gps)
        val videoAt = mdatAt + 8
        val gpsAt = videoAt + video.size
        val vtrak = box("trak", tkhd(), box("mdia", mdhd(), hdlr("vide"), box("minf", stbl(videoAt, video.size.toLong()))),
            box("udta", box("©xyz", "+37.7749-122.4194/".toByteArray())))
        val mtrak = box("trak", tkhd(), box("mdia", mdhd(), hdlr("meta"), box("minf", stbl(gpsAt, gps.size.toLong()))))
        val moov = box("moov", mvhd(), vtrak, mtrak,
            box("udta", box("©xyz", "+37.7749-122.4194/".toByteArray()), box("©mak", "PhoneMaker".toByteArray())),
            box("meta", u32(0), box("keys", "com.apple.quicktime.location.ISO6709".toByteArray())))
        val xmp = box("uuid", "XMPXMPXMPXMPXMPX".toByteArray(), "<x:xmpmeta>Model</x:xmpmeta>".toByteArray())
        return ftyp + mdat + moov + xmp + tail
    }

    private fun has(b: ByteArray, s: String): Boolean = String(b, Charsets.ISO_8859_1).contains(s)

    @Test
    fun locationAndCameraDetailsGo() {
        val mp4 = phoneMp4()
        val out = Mp4Scrub.scrubBytes(mp4)
        assertNotNull(out)
        out!!
        assertEquals(mp4.size, out.size)                         // same size: every offset stays right
        for (s in listOf("37.7749", "PhoneMaker", "ISO6709", "xmpmeta", "©xyz", "udta", "uuid", "GPS")) assertFalse(s, has(out, s))
        // The picture's samples are untouched, at the same place.
        val at = String(mp4, Charsets.ISO_8859_1).indexOf("VIDEOFRAMES")
        assertArrayEquals(video, out.copyOfRange(at, at + video.size))
        // The video track is still there; the metadata track is gone (a "free" box now).
        assertTrue(has(out, "vide"))
        assertFalse(has(out, "hdlr\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000meta"))
        // Creation and change times are zeroed.
        for (t in listOf(0x11111111L, 0x22222222L, 0x33333333L, 0x44444444L, 0x55555555L, 0x66666666L))
            assertFalse(t.toString(16), has(out, String(u32(t), Charsets.ISO_8859_1)))
    }

    @Test
    fun aMakersTrailerIsCutOff() {
        val mp4 = phoneMp4("SEFHjunkSEFT".toByteArray())
        val out = Mp4Scrub.scrubBytes(mp4)!!
        assertEquals(mp4.size - 12, out.size)
        assertFalse(has(out, "SEF"))
    }

    @Test
    fun whatCantBeCleanedIsRefused() {
        val mp4 = phoneMp4()
        // Not an MP4, cut short before moov, or fragmented: refused, never a crash.
        assertNull(Mp4Scrub.scrubBytes("not a video at all".toByteArray()))
        assertNull(Mp4Scrub.scrubBytes(mp4.copyOf(60)))
        val ftyp = box("ftyp", "isom".toByteArray(), u32(0))
        assertNull(Mp4Scrub.scrubBytes(ftyp + box("moov", mvhd(), box("mvex")) + box("moof") + box("mdat", video)))
        // No picture track left: refused.
        assertNull(Mp4Scrub.scrubBytes(ftyp + box("mdat", gps) + box("moov", mvhd(), box("trak", tkhd(), box("mdia", mdhd(), hdlr("meta"))))))
        // A metadata track pointing outside the file: refused (it can't be zeroed).
        val bad = ftyp + box("mdat", video) + box("moov", mvhd(),
            box("trak", tkhd(), box("mdia", mdhd(), hdlr("vide"), box("minf", stbl(ftyp.size + 8L, video.size.toLong())))),
            box("trak", tkhd(), box("mdia", mdhd(), hdlr("meta"), box("minf", stbl(1_000_000L, 20L)))))
        assertNull(Mp4Scrub.scrubBytes(bad))
        // Every cut of a real one: no crash.
        for (n in 0..mp4.size) Mp4Scrub.scrubBytes(mp4.copyOf(n))
        // Every single damaged byte: no crash.
        for (i in mp4.indices) Mp4Scrub.scrubBytes(mp4.copyOf().also { it[i] = (it[i] + 0x5a).toByte() })
    }

    @Test
    fun aFileIsCleanedInPlace() {
        val f = File.createTempFile("scrub", ".mp4")
        try {
            f.writeBytes(phoneMp4("trailer".toByteArray()))
            assertTrue(Mp4Scrub.scrubFile(f))
            val out = f.readBytes()
            assertFalse(has(out, "37.7749") || has(out, "trailer") || has(out, "GPS"))
            assertArrayEquals(Mp4Scrub.scrubBytes(phoneMp4())!!, out)
            f.writeBytes("nope".toByteArray())
            assertFalse(Mp4Scrub.scrubFile(f))
        } finally { f.delete() }
    }

    @Test
    fun onlyPlainH264GoesAsItIs() {
        assertTrue(GroupVideo.plainCodecs(true, "video/avc", 8, 3, 1, "audio/mp4a-latm"))
        assertTrue(GroupVideo.plainCodecs(true, "video/avc", -1, -1, -1, null))         // no sound, nothing said
        assertFalse(GroupVideo.plainCodecs(false, "video/avc", 8, -1, -1, null))        // not an MP4 (WebM, MPEG-TS)
        assertFalse(GroupVideo.plainCodecs(true, "video/hevc", -1, -1, -1, null))
        assertFalse(GroupVideo.plainCodecs(true, "video/av01", -1, -1, -1, null))
        assertFalse(GroupVideo.plainCodecs(true, "video/avc", 0x10, -1, -1, null))      // High 10
        assertFalse(GroupVideo.plainCodecs(true, "video/avc", 8, 6, -1, null))          // HDR10 (PQ)
        assertFalse(GroupVideo.plainCodecs(true, "video/avc", 8, 7, -1, null))          // HLG
        assertFalse(GroupVideo.plainCodecs(true, "video/avc", 8, -1, 6, null))          // BT.2020
        assertFalse(GroupVideo.plainCodecs(true, "video/avc", 8, -1, -1, "audio/opus"))
        val hevc = Info(1280, 720, 0, 30f, 10_000, 2_000_000, plain = false)
        assertFalse(GroupVideo.fitsRelay(hevc))
        assertFalse(GroupVideo.fitsFull(hevc))
        assertTrue(GroupVideo.needsConvert(hevc))
        assertTrue(GroupVideo.fitsRelay(hevc.copy(plain = true)))
        assertTrue(GroupVideo.isMp4Head(phoneMp4()))
        assertFalse(GroupVideo.isMp4Head(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte(), 0, 0, 0, 0)))   // WebM
    }

    @Test
    fun copiesKeepBothSidesWithinTheLimits() {
        // An ultrawide clip and a tall screen recording: the long side counts too.
        assertEquals(1920 to 810, GroupVideo.target(2560, 1080, GroupVideo.FULL_LONG, GroupVideo.FULL_SHORT))
        assertEquals(1280 to 540, GroupVideo.target(2560, 1080, GroupVideo.RELAY_LONG, GroupVideo.RELAY_SHORT))
        assertEquals(1280 to 536, GroupVideo.target(3440, 1440, GroupVideo.RELAY_LONG, GroupVideo.RELAY_SHORT))
        val (w, h) = GroupVideo.target(1080, 2340, GroupVideo.FULL_LONG, GroupVideo.FULL_SHORT)!!
        assertTrue(h <= GroupVideo.FULL_LONG && w <= GroupVideo.FULL_SHORT && w % 2 == 0 && h % 2 == 0)
        assertNull(GroupVideo.target(1920, 1080, GroupVideo.FULL_LONG, GroupVideo.FULL_SHORT))
        assertNull(GroupVideo.target(720, 1280, GroupVideo.RELAY_LONG, GroupVideo.RELAY_SHORT))
    }

    @Test
    fun piecesGoOneQueueAtThePace() {
        // Two messages queued at once: one queue, every piece at least a slot after the one before, in order.
        val times = Collections.synchronizedList(ArrayList<Pair<Int, Long>>())
        val done = CountDownLatch(6)
        fun pieces(from: Int) = (0 until 3).map { i -> { times.add(from + i to System.nanoTime()); done.countDown(); Unit } }
        assertTrue(VideoPace.go(VideoPace.key("a", "pace-" + System.nanoTime()), pieces(0)))
        assertTrue(VideoPace.go(VideoPace.key("b", "pace-" + System.nanoTime()), pieces(3)))
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals((0 until 6).toList(), times.map { it.first })
        val gap = 1000L / GroupVideo.PACE_PER_SECOND
        times.zipWithNext().forEach { (a, b) -> assertTrue((b.second - a.second) / 1_000_000 >= gap - 40) }
    }
}
