package io.github.ravenor907.muninn.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Photos for chat. Re-encoding drops everything but the pixels, so location and camera details
 * never leave the phone.
 *  - Normal: 640 px on the long side, at most 60 KB. Arrives in a second or two on any route.
 *  - High quality: 2048 px, at most 2 MB. Quick close by or on Wi-Fi; slower over the internet.
 */
object Photos {
    const val PIECE = 16 * 1024
    const val MAX_PIECES = 200                   // 3.2 MB: more than the largest photo we make
    const val INTERNET_PIECES = 130              // 2 MB over the relays at most
    private const val NORMAL_SIDE = 640
    private const val NORMAL_BYTES = 60 * 1024
    private const val HD_SIDE = 2048
    private const val HD_BYTES = 2 * 1024 * 1024

    /** Null if it isn't a picture Android can open. */
    fun prepare(context: Context, uri: Uri, hd: Boolean): ByteArray? = try {
        val side = if (hd) HD_SIDE else NORMAL_SIDE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            raw?.let { scrub(encode(upright(context, uri, scale(it, side)), if (hd) HD_BYTES else NORMAL_BYTES)) }
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    /**
     * A GIF's first frame as an ordinary photo (640 px, at most 60 KB, no metadata): what someone whose app doesn't
     * show GIFs gets, and what a GIF too big to go animated can go as. Null if it can't be read.
     */
    fun still(gif: ByteArray): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(gif, 0, gif.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= NORMAL_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(gif, 0, gif.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let { scrub(encode(opaque(scale(it, NORMAL_SIDE)), NORMAL_BYTES)) }
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    /** Clear parts of a GIF frame on white, as a JPEG has no transparency. */
    private fun opaque(b: Bitmap): Bitmap {
        if (!b.hasAlpha()) return b
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        return out
    }

    /** True if [uri] holds a GIF (by its first bytes, whatever its name or type says). */
    fun isGif(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { s -> val head = ByteArray(6); var n = 0
            while (n < 6) { val r = s.read(head, n, 6 - n); if (r < 0) break; n += r }
            n == 6 && Gifs.isGif(head) } ?: false
    } catch (e: Exception) { false }

    /**
     * A GIF the person picked, read and cleaned (Gifs.clean). Reads at most 8 MB and a byte: anything longer is
     * "big" without reading the rest. Null if it can't be read at all.
     */
    fun readGif(context: Context, uri: Uri): Gifs.Result? = try {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var over = false
            while (true) {
                val r = s.read(buf)
                if (r < 0) break
                out.write(buf, 0, r)
                if (out.size() > Gifs.MAX_BYTES) { over = true; break }
            }
            if (over) Gifs.Result(null, 0, "big") else Gifs.clean(out.toByteArray())
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    /** A Labs screenshot: 1280 px on the long side, at most 300 KB, a plain JPEG with no metadata. Null if it can't be made. */
    fun screenshot(b: Bitmap): ByteArray? = try { scrub(encode(scale(b, 1280), 300 * 1024)) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    private fun scale(b: Bitmap, side: Int): Bitmap {
        val long = maxOf(b.width, b.height)
        if (long <= side) return b
        val f = side.toFloat() / long
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    /** Phones store photos sideways and note the turn in the file; apply it, since that note is dropped. */
    private fun upright(context: Context, uri: Uri, b: Bitmap): Bitmap {
        // Round 17 (m12): read from a file descriptor first, which can be searched: a HEIC photo read from a plain stream
        // may give no turn on some Android versions and stay sideways. The stream stays as the fallback.
        val fromFd = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                ExifInterface(fd.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
            }
        } catch (e: Exception) { null }
        val turn = if (fromFd != null && fromFd != 0) ExifTurn.of(fromFd) else try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifTurn.of(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL))
            } ?: ExifTurn.of(1)
        } catch (e: Exception) { ExifTurn.of(1) }
        if (turn.degrees == 0f && !turn.mirror) return b
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply {
            postRotate(turn.degrees)
            if (turn.mirror) postScale(-1f, 1f)
        }, true)
    }

    /** Best quality that fits the size limit; shrinks further if even low quality doesn't fit. */
    private fun encode(b: Bitmap, limit: Int): ByteArray {
        var bmp = b
        while (true) {
            for (q in intArrayOf(85, 75, 65, 55, 45, 35)) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= limit) return out.toByteArray()
            }
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.75f).toInt().coerceAtLeast(1), (bmp.height * 0.75f).toInt().coerceAtLeast(1), true)
        }
    }

    /**
     * Removes every metadata block from a JPEG without touching the picture: EXIF (location, camera,
     * time), XMP, ICC and maker notes (APP1 to APP15) and comments. Run on photos we send and on
     * every photo we receive, so nothing extra survives even if the other phone didn't scrub it.
     * Null if it isn't a well-formed JPEG.
     */
    fun scrub(b: ByteArray): ByteArray? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }                       // fill byte
            if (marker == 0xDA) { out.write(b, i, b.size - i); return out.toByteArray() }   // the picture itself, to the end
            if (marker == 0xD9) { out.write(0xFF); out.write(0xD9); return out.toByteArray() }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            val metadata = marker in 0xE1..0xEF || marker == 0xFE
            if (!metadata) out.write(b, i, 2 + len)
            i += 2 + len
        }
        return null
    }

    fun split(data: ByteArray): List<ByteArray> = (data.indices step PIECE).map { data.copyOfRange(it, minOf(it + PIECE, data.size)) }

    /** Null unless it's a JPEG of a sensible size. */
    fun join(parts: List<ByteArray>): ByteArray? {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        val b = out.toByteArray()
        return if (b.size in 3..(MAX_PIECES * PIECE)) scrub(b) else null
    }

    /** What a picked item is (Saved media): [Pick.PHOTO], [Pick.VIDEO] or [Pick.GIF]. A GIF is known by its bytes. */
    fun kindOf(context: Context, uri: Uri): String {
        val type = try { context.contentResolver.getType(uri).orEmpty() } catch (_: Exception) { "" }
        return when {
            type.startsWith("video/") -> Pick.VIDEO
            type == "image/gif" || isGif(context, uri) -> Pick.GIF
            else -> Pick.PHOTO
        }
    }

    /** A video's length in ms, 0 if it can't be read. */
    fun videoMs(context: Context, uri: Uri): Long {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) { 0L } finally { try { r.release() } catch (_: Exception) {} }
    }

    /** A small square picture of a picked photo, GIF or video (its first frame) for the Saved media grid. Null if none. */
    fun thumb(context: Context, uri: Uri, kind: String, side: Int = 240): Bitmap? = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, android.util.Size(side, side), null)
        else if (kind == Pick.VIDEO) {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(context, uri); r.getFrameAtTime(0)?.let { scale(it, side) } } finally { try { r.release() } catch (_: Exception) {} }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    /**
     * Saved media (the Send sheet's one picker for photos, videos and GIFs): the plain rules, kept free of Android so the
     * JVM tests run them. Items are picked in an order (the numbers on the tiles), at most [MAX] at once.
     */
    object Pick {
        const val PHOTO = "photo"
        const val VIDEO = "video"
        const val GIF = "gif"
        /** Photos, videos and GIFs together, the same as an album. */
        const val MAX = More.ALBUM_MAX
        /** The filters over the grid, in order: (key, label). */
        val FILTERS = listOf("all" to "All", PHOTO to "Photos", VIDEO to "Videos", GIF to "GIFs")
        /** Under the grid, about what each video becomes (owner wording, 2026-11-04). */
        const val NOTE = "Full size close by. Others get up to 5 minutes."

        /** The pick order after tapping item [i]: taken out if picked (the rest move up), else added last while there's room. */
        fun toggle(order: List<Int>, i: Int, max: Int = MAX): List<Int> = when {
            i in order -> order - i
            order.size >= max -> order
            else -> order + i
        }

        /** Which items (indices into [kinds]) show under filter [f]. */
        fun shown(kinds: List<String>, f: String): List<Int> = kinds.indices.filter { f == "all" || kinds[it] == f }

        /** "3 selected". */
        fun selected(n: Int): String = "$n selected"

        /** "2 photos, 1 video" (a GIF part only when there are GIFs); empty for none. */
        fun summary(photos: Int, videos: Int, gifs: Int): String = listOfNotNull(
            count(photos, "photo"), count(videos, "video"), count(gifs, "GIF"),
        ).joinToString(", ")

        private fun count(n: Int, one: String): String? = if (n <= 0) null else "$n $one" + if (n == 1) "" else "s"

        /** A video's length on its tile: "0:18", "2:14", "1:02:09". */
        fun length(ms: Long): String {
            val s = (ms.coerceAtLeast(0) + 500) / 1000
            val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
        }
    }

    /**
     * The in-app camera: tap the shutter for a photo, hold it for a video of up to [VIDEO_MAX_MS] (owner, 2026-11-04).
     * Plain rules for the JVM tests.
     */
    object Shutter {
        /** How long a press must last to become a video rather than a photo. */
        const val HOLD_MS = 350L
        const val VIDEO_MAX_MS = 60_000L
        const val HINT = "Tap for a photo, hold for video"
        const val RECORDING = "Recording. Let go to stop."

        /** "0:12 / 1:00" over the preview while recording. */
        fun clock(elapsedMs: Long): String = Pick.length(elapsedMs.coerceIn(0, VIDEO_MAX_MS).let { it - it % 1000 }) + " / " + Pick.length(VIDEO_MAX_MS)

        /** How full the ring round the shutter is, 0 to 1. */
        fun ring(elapsedMs: Long): Float = (elapsedMs.toFloat() / VIDEO_MAX_MS).coerceIn(0f, 1f)
    }
}
