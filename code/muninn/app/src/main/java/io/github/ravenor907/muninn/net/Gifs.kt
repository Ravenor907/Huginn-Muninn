package io.github.ravenor907.muninn.net

import java.io.ByteArrayOutputStream

/**
 * Animated GIFs in chats (cap a1, protocol section 25). A GIF goes as photo pieces with "kind":"gif", only to
 * someone whose app says a1; anyone else gets its first frame as an ordinary photo.
 *
 * [clean] runs before sending and again on every GIF received, so only the picture survives: the logical screen
 * and its colors, every frame with the timing block just before it, and the loop count. Comments, plain text,
 * XMP, ICC profiles and every other application block go, and so does anything after the end. The limits keep a
 * hostile GIF from freezing or crashing the phone when it's shown. Mirrors code/shared/tools/gif_ref.py.
 */
object Gifs {
    const val CAP = "a1"
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_FRAMES = 400
    const val MAX_SIDE = 2048
    const val MAX_AREA = 160L * 1024 * 1024
    /** Pieces of one GIF (8 MB), close by and over the internet. */
    const val PIECES = MAX_BYTES / Photos.PIECE

    /** A clean GIF and how many frames it has, or why not: "gif" (not a well-formed GIF), "big" or "frames". */
    class Result(val bytes: ByteArray?, val frames: Int, val why: String)

    private class Bad(val why: String) : Exception(why)

    fun isGif(b: ByteArray?): Boolean {
        if (b == null || b.size < 6) return false
        val h = String(b, 0, 6, Charsets.ISO_8859_1)
        return h == "GIF87a" || h == "GIF89a"
    }

    /** The cleaned GIF, or null when it isn't one within the limits. */
    fun cleanOrNull(b: ByteArray): ByteArray? = clean(b).bytes

    /** A received GIF: the pieces joined and cleaned again, or null. */
    fun join(parts: List<ByteArray>): ByteArray? {
        if (parts.sumOf { it.size } > MAX_BYTES) return null
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return cleanOrNull(out.toByteArray())
    }

    fun clean(b: ByteArray): Result = try {
        val (bytes, frames) = cleanOrThrow(b)
        Result(bytes, frames, "")
    } catch (e: Bad) { Result(null, 0, e.why) }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /** Skips the sub-blocks starting at [from]: where the block after them starts, and the data of the first two. */
    private fun blocks(b: ByteArray, from: Int, firsts: MutableList<ByteArray>): Int {
        var at = from
        while (true) {
            if (at >= b.size) throw Bad("gif")
            val n = u8(b, at++)
            if (n == 0) return at
            if (at + n > b.size) throw Bad("gif")
            if (firsts.size < 2) firsts += b.copyOfRange(at, at + n)
            at += n
        }
    }

    private fun cleanOrThrow(b: ByteArray): Pair<ByteArray, Int> {
        if (b.size > MAX_BYTES) throw Bad("big")
        if (!isGif(b) || b.size < 13) throw Bad("gif")
        val w = u16(b, 6); val h = u16(b, 8)
        if (w == 0 || h == 0) throw Bad("gif")
        if (w > MAX_SIDE || h > MAX_SIDE) throw Bad("big")
        val packed = u8(b, 10)
        var i = 13
        if (packed and 0x80 != 0) {
            i += 3 shl ((packed and 7) + 1)
            if (i > b.size) throw Bad("gif")
        }
        val head = ByteArrayOutputStream()
        head.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        head.write(b, 6, i - 6)
        val body = ByteArrayOutputStream(b.size)
        var gce: ByteArray? = null
        var loop = -1
        var frames = 0
        var area = 0L
        while (i < b.size) {
            val t = u8(b, i)
            if (t == 0x3B) break
            when (t) {
                0x2C -> {
                    if (i + 10 > b.size) throw Bad("gif")
                    val x = u16(b, i + 1); val y = u16(b, i + 3); val fw = u16(b, i + 5); val fh = u16(b, i + 7)
                    if (fw == 0 || fh == 0 || x + fw > w || y + fh > h) throw Bad("gif")
                    val fp = u8(b, i + 9)
                    var j = i + 10
                    if (fp and 0x80 != 0) j += 3 shl ((fp and 7) + 1)
                    if (j >= b.size) throw Bad("gif")
                    if (u8(b, j) !in 2..8) throw Bad("gif")
                    val end = blocks(b, j + 1, ArrayList())
                    if (++frames > MAX_FRAMES) throw Bad("frames")
                    area += fw.toLong() * fh
                    if (area > MAX_AREA) throw Bad("big")
                    gce?.let { body.write(it) }
                    gce = null
                    body.write(b, i, end - i)
                    i = end
                }
                0x21 -> {
                    if (i + 2 > b.size) throw Bad("gif")
                    val label = u8(b, i + 1)
                    val subs = ArrayList<ByteArray>(2)
                    var count = 0
                    // Count every sub-block for the timing block (it must be exactly one of 4 bytes).
                    var at = i + 2
                    while (true) {
                        if (at >= b.size) throw Bad("gif")
                        val n = u8(b, at++)
                        if (n == 0) break
                        if (at + n > b.size) throw Bad("gif")
                        if (subs.size < 2) subs += b.copyOfRange(at, at + n)
                        count++
                        at += n
                    }
                    val end = at
                    when (label) {
                        0xF9 -> if (count == 1 && subs[0].size == 4) gce = b.copyOfRange(i, end)
                        0xFF -> if (loop < 0 && subs.size >= 2 && String(subs[0], Charsets.ISO_8859_1).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }
                            && subs[1].size == 3 && subs[1][0].toInt() == 1) loop = u16(subs[1], 1)
                        0x01 -> gce = null   // its timing block went with it
                    }
                    i = end
                }
                else -> throw Bad("gif")
            }
        }
        if (frames == 0) throw Bad("gif")
        val out = ByteArrayOutputStream(head.size() + body.size() + 20)
        head.writeTo(out)
        if (loop >= 0) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
            out.write("NETSCAPE2.0".toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf(0x03, 0x01, (loop and 0xFF).toByte(), (loop shr 8).toByte(), 0x00))
        }
        body.writeTo(out)
        out.write(0x3B)
        return out.toByteArray() to frames
    }

    /** What to tell the person when a GIF can't go animated. */
    fun why(r: Result): String = when (r.why) {
        "frames" -> "That GIF has more than $MAX_FRAMES frames."
        "big" -> "That GIF is over 8 MB or larger than $MAX_SIDE px."
        else -> "That GIF can't be read."
    }
}
