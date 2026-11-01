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

    /** Null unless it's an MP4 of a size we send. */
    fun join(parts: List<ByteArray>): ByteArray? {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        val b = out.toByteArray()
        return if (b.size <= Photos.MAX_PIECES * Photos.PIECE && More.isVideo(b)) b else null
    }
}
