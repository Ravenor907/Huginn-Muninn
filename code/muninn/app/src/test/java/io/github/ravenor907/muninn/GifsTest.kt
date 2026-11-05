package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Gifs
import io.github.ravenor907.muninn.net.More
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.net.Proto06
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Animated GIFs (cap a1, protocol section 25): shared/vectors/gif-vectors.json and the limits. */
class GifsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("gif-vectors.json")!!.readText())
    private fun b64(s: String) = Base64.getDecoder().decode(s)

    @Test
    fun vectorsCleanTheSameWay() {
        val a = v.getJSONArray("good")
        assertTrue(a.length() >= 8)
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val r = Gifs.clean(b64(c.getString("in")))
            assertArrayEquals(c.getString("name"), b64(c.getString("out")), r.bytes)
            assertEquals(c.getString("name"), c.getInt("frames"), r.frames)
        }
    }

    @Test
    fun vectorsRefuseTheSameWay() {
        val a = v.getJSONArray("refuse")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val r = Gifs.clean(b64(c.getString("in")))
            assertNull(c.getString("name"), r.bytes)
            assertEquals(c.getString("name"), c.getString("why"), r.why)
        }
    }

    @Test
    fun limitsMatchTheReference() {
        val l = v.getJSONObject("limits")
        assertEquals(l.getInt("bytes"), Gifs.MAX_BYTES)
        assertEquals(l.getInt("frames"), Gifs.MAX_FRAMES)
        assertEquals(l.getInt("side"), Gifs.MAX_SIDE)
        assertEquals(l.getLong("area"), Gifs.MAX_AREA)
        assertEquals(512, Gifs.PIECES)
        assertTrue(Gifs.PIECES * Photos.PIECE == Gifs.MAX_BYTES)
    }

    // ------------------------------------------------------------ GIFs built here

    private fun sub(data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        var k = 0
        while (k < data.size) { val n = minOf(255, data.size - k); o.write(n); o.write(data, k, n); k += n }
        o.write(0)
        return o.toByteArray()
    }
    private fun le(x: Int) = byteArrayOf((x and 0xFF).toByte(), (x shr 8).toByte())
    private fun screen(w: Int, h: Int) = "GIF89a".toByteArray() + le(w) + le(h) + byteArrayOf(0x81.toByte(), 0, 0) + ByteArray(12)
    /** One pixel's worth of LZW at code size 2 (clear, color 0, end): a frame every decoder reads. */
    private fun frame(w: Int = 1, h: Int = 1) = byteArrayOf(0x2C) + le(0) + le(0) + le(w) + le(h) + byteArrayOf(0, 2) + sub(byteArrayOf(0x44, 0x01))
    private val gce = byteArrayOf(0x21, 0xF9.toByte(), 4, 4, 10, 0, 0, 0)
    private val loop = byteArrayOf(0x21, 0xFF.toByte(), 11) + "NETSCAPE2.0".toByteArray() + byteArrayOf(3, 1, 0, 0, 0)
    private fun comment(t: String) = byteArrayOf(0x21, 0xFE.toByte()) + sub(t.toByteArray())
    private fun app(id: String, data: String) = byteArrayOf(0x21, 0xFF.toByte(), 11) + id.toByteArray() + sub(data.toByteArray())
    private fun ByteArray.has(s: String) = String(this, Charsets.ISO_8859_1).contains(s)

    @Test
    fun cleaningRemovesCommentsAndXmpAndKeepsAValidGif() {
        val dirty = screen(1, 1) + comment("taken at home, 37.7N") + loop + app("XMP DataXMP", "<x:xmpmeta>GPS 37.7N</x:xmpmeta>") +
            gce + frame() + app("ICCRGBG1012", "profile") + gce + frame() + comment("bye") + byteArrayOf(0x3B) + "GPS after the end".toByteArray()
        val r = Gifs.clean(dirty)
        val out = r.bytes
        assertNotNull(out); out!!
        assertEquals(2, r.frames)
        assertTrue(Gifs.isGif(out))
        assertFalse(out.has("37.7N")); assertFalse(out.has("XMP")); assertFalse(out.has("ICCRGBG")); assertFalse(out.has("bye")); assertFalse(out.has("GPS"))
        assertTrue("the loop stays", out.has("NETSCAPE2.0"))
        assertEquals(0x3B, out.last().toInt())
        // Exactly the picture: screen, loop, then each timing block and frame.
        assertArrayEquals(screen(1, 1) + loop + gce + frame() + gce + frame() + byteArrayOf(0x3B), out)
        // Clean already: cleaning again changes nothing, and the receiving side takes it.
        assertArrayEquals(out, Gifs.cleanOrNull(out))
        assertArrayEquals(out, Gifs.join(Photos.split(out)))
    }

    @Test
    fun framesOverTheLimitAreRefused() {
        fun anim(n: Int): ByteArray { val o = ByteArrayOutputStream(); o.write(screen(1, 1)); repeat(n) { o.write(gce); o.write(frame()) }; o.write(0x3B); return o.toByteArray() }
        assertEquals(Gifs.MAX_FRAMES, Gifs.clean(anim(Gifs.MAX_FRAMES)).frames)
        assertEquals("frames", Gifs.clean(anim(Gifs.MAX_FRAMES + 1)).why)
    }

    @Test
    fun sizeOverTheLimitIsRefused() {
        val big = screen(1, 1) + frame() + comment("x".repeat(Gifs.MAX_BYTES)) + byteArrayOf(0x3B)
        assertEquals("big", Gifs.clean(big).why)
        assertNull(Gifs.join(Photos.split(big)))
        assertEquals("big", Gifs.clean(screen(Gifs.MAX_SIDE + 1, 1) + frame() + byteArrayOf(0x3B)).why)
        assertEquals(Gifs.MAX_SIDE, Gifs.clean(screen(Gifs.MAX_SIDE, 1) + frame() + byteArrayOf(0x3B)).bytes!!.let { (it[6].toInt() and 0xFF) or ((it[7].toInt() and 0xFF) shl 8) })
    }

    @Test
    fun framesBeyondTheScreenOrBrokenAreRefused() {
        assertEquals("gif", Gifs.clean(screen(1, 1) + frame(2, 1) + byteArrayOf(0x3B)).why)
        assertEquals("gif", Gifs.clean(screen(1, 1) + byteArrayOf(0x3B)).why)
        assertEquals("gif", Gifs.clean(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())).why)
        assertEquals("gif", Gifs.clean(screen(1, 1) + comment("x").copyOf(3)).why)
    }

    @Test
    fun aGifPieceIsWellFormedOnlyOutsideAnAlbum() {
        assertTrue(More.ok("piece", JSONObject().put("kind", "gif")))
        assertFalse(More.ok("piece", JSONObject().put("kind", "gif").put("al", "abc").put("ai", 0).put("an", 2)))
    }

    @Test
    fun capsSayA1() = assertTrue(Gifs.CAP in Proto06.caps(Proto06.CAPS))
}
