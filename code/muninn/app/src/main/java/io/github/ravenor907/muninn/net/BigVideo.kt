package io.github.ravenor907.muninn.net

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream

/**
 * A longer video from Family or trusted contacts (up to [INLINE_MAX_MS]) shown in the chat like a photo. It came (or went)
 * through FileLink; here it's kept in sealed pieces of [PIECE] bytes, so it plays without ever being whole in memory or
 * in the clear on disk. Its first frame is the bubble's picture.
 */
object BigVideo {
    /** Up to 3 minutes goes in the chat; longer stays a file. */
    const val INLINE_MAX_MS = 180_000
    const val PIECE = 1024 * 1024
    const val SIZE = "size"

    fun pieceName(i: Int) = "%05d".format(i)

    /** The offer field with the sender's message id for the video in the chat, so both sides share one message. */
    const val FIELD = "bm"
    /** A message id that may name a big video: a UUID (a 1:1 chat) or a group's logical id, nothing else. */
    fun idOk(s: String): Boolean = s.length in 16..40 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Whether a video [ms] long from someone with [badge] (FileLink.mayAlways) goes in the chat. */
    fun inline(ms: Int, familyOrTrusted: Boolean) = familyOrTrusted && ms in 1..(INLINE_MAX_MS + GroupVideo.SLACK_MS)

    /** The first frame of the MP4 at [path] as a JPEG (480 on its long side), or null. */
    fun poster(path: String): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            // Decoded small (never a full 4K picture in memory just for the bubble).
            (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 480)
                else r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))?.let { b ->
                val long = maxOf(b.width, b.height)
                val s = if (long > 480) Bitmap.createScaledBitmap(b, b.width * 480 / long, b.height * 480 / long, true) else b
                ByteArrayOutputStream().also { s.compress(Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
            }
        } catch (_: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
    }

    /** A dark picture for a video whose first frame can't be read. */
    val BLANK: ByteArray by lazy {
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.RGB_565)
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
    }

    /** A JPEG (a big video's picture) rather than an MP4 (a video message). */
    fun isJpeg(b: ByteArray?) = b != null && b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    /** Reads a big video piece by piece ([piece] opens piece i), keeping the last two open. */
    class Source(private val total: Long, private val piece: (Int) -> ByteArray?) : MediaDataSource() {
        private val kept = LinkedHashMap<Int, ByteArray>()

        @Synchronized private fun get(i: Int): ByteArray? {
            kept[i]?.let { return it }
            val b = piece(i) ?: return null
            kept[i] = b
            while (kept.size > 2) kept.remove(kept.keys.first())
            return b
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= total || size <= 0) return -1
            var at = position
            var done = 0
            while (done < size && at < total) {
                val i = (at / PIECE).toInt()
                val b = get(i) ?: break
                val inPiece = (at - i.toLong() * PIECE).toInt()
                if (inPiece >= b.size) break
                val n = minOf(size - done, b.size - inPiece)
                System.arraycopy(b, inPiece, buffer, offset + done, n)
                done += n; at += n
            }
            return if (done == 0) -1 else done
        }

        override fun getSize() = total
        @Synchronized override fun close() { kept.clear() }
    }
}
