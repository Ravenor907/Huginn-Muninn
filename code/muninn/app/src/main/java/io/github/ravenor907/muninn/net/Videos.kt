package io.github.ravenor907.muninn.net

import java.io.ByteArrayOutputStream

/**
 * Video messages (cap m2, shared/docs/protocol.md 12.2): an MP4 of at most a minute, small enough to go through the
 * relays like a photo (see [Photos.INTERNET_PIECES]). Recorded at a low size and bitrate for that.
 */
object Videos {
    /** About 2 MB a minute in all: the most the relays carry for one message. */
    const val VIDEO_BITRATE = 220_000
    const val AUDIO_BITRATE = 32_000
    const val SIDE = 480

    /** The most a video message we send may be: always small enough for the relays (no straight path needed). */
    const val SEND_MAX = Photos.INTERNET_PIECES * Photos.PIECE

    /**
     * Round 17: a video bubble's size in dp (width, height) for a first frame [w] x [h]: upright clips keep the
     * 200 x 260 box; a sideways one is 260 wide and as tall as its shape says (never under 120), so it isn't cropped
     * to its middle.
     */
    fun bubble(w: Int, h: Int): Pair<Int, Int> =
        if (w <= 0 || h <= 0 || w <= h) 200 to 260 else 260 to (260 * h / w).coerceIn(120, 260)

    /** A video of [size] bytes fits in one message. */
    fun fits(size: Int): Boolean = size in 1..SEND_MAX

    /** Null unless it's an MP4 of a size we take (an older app may have sent one up to [Photos.MAX_PIECES] pieces). */
    fun join(parts: List<ByteArray>): ByteArray? {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        val b = out.toByteArray()
        return if (b.size <= Photos.MAX_PIECES * Photos.PIECE && More.isVideo(b)) b else null
    }
}
