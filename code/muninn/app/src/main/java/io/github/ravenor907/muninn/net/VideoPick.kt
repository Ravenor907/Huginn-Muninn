package io.github.ravenor907.muninn.net

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Cap v3 (protocol.md 29): a video picked from the phone, read ([probe]) and, when it's over the limits, converted with
 * Media3 Transformer ([fullCopy], [smallCopy], [relayCopy]), which keeps the picture upright and uses the phone's own H.264 encoder.
 * Only plain H.264 and AAC in an MP4 goes as it is; anything else (HEVC, AV1, 10-bit, HDR) is converted. Every copy has
 * its location and camera details taken out (Mp4Scrub) before it goes, as photos do.
 * Low-end phones: no 1080p encoder or little memory makes the full copy 720p; a copy that fails is tried once smaller
 * (720p, then 480p) before giving up with a plain reason. Nothing here touches the network.
 */
@OptIn(UnstableApi::class)
object VideoPick {
    /**
     * Where copies are made: a relay copy is read and deleted at once; a full or 720p copy once VideoSend has given each
     * member their own name for it in noBackupFilesDir/videos-out (kept there until FileLink is done with it, up to a day).
     */
    fun dir(context: Context): File = File(context.cacheDir, "videos").also { it.mkdirs() }

    /** Clears copies left from before (a send cut short when Muninn closed): all of them, unless a send is running ([busy]). */
    fun clearOld(context: Context, busy: Boolean) {
        if (!busy) dir(context).listFiles()?.forEach { it.delete() }
    }

    /** What [uri] is, or null if it isn't a video Android can read. */
    fun probe(context: Context, uri: Uri): GroupVideo.Info? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") null else {
                val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val ms = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                var fps = 0f
                if (Build.VERSION.SDK_INT >= 28 && ms > 0) {
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()?.let { n -> fps = n * 1000f / ms }
                }
                val t = tracks { it.setDataSource(context, uri, null) }
                if (t.fps > 0f) fps = t.fps
                val plain = t.plain && GroupVideo.isMp4Head(head(context, uri))
                if (w <= 0 || h <= 0 || ms <= 0) null else GroupVideo.Info(w, h, rot, fps, ms, size(context, uri), t.audioBps, plain)
            }
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** The file's first bytes (for its "ftyp"), or nothing. */
    private fun head(context: Context, uri: Uri): ByteArray = try {
        context.contentResolver.openInputStream(uri)?.use { i -> val b = ByteArray(12); var n = 0
            while (n < b.size) { val r = i.read(b, n, b.size - n); if (r <= 0) break; n += r }
            b.copyOf(n) } ?: ByteArray(0)
    } catch (_: Exception) { ByteArray(0) }

    /**
     * The video track's frame rate, the sound's bitrate (0 when the file doesn't say), and [plain]: GroupVideo.plainCodecs
     * for the first video track and the first sound track (the container is checked by the caller).
     */
    private class Tracks(val fps: Float, val audioBps: Int, val plain: Boolean)

    private fun intOf(f: MediaFormat, key: String): Int =
        if (!f.containsKey(key)) -1 else try { f.getInteger(key) } catch (_: Exception) { -1 }

    private fun tracks(source: (MediaExtractor) -> Unit): Tracks {
        val x = MediaExtractor()
        return try {
            source(x)
            var fps = 0f
            var audio = 0
            var video: MediaFormat? = null
            var sound: String? = null
            for (i in 0 until x.trackCount) {
                val f = x.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && video == null) {
                    video = f
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE))
                        fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (_: Exception) { try { f.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 0f } }
                }
                if (mime.startsWith("audio/") && sound == null) {
                    sound = mime
                    if (f.containsKey(MediaFormat.KEY_BIT_RATE)) audio = try { f.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { 0 }
                }
            }
            val v = video
            val plain = v != null && GroupVideo.plainCodecs(true, v.getString(MediaFormat.KEY_MIME).orEmpty(), intOf(v, MediaFormat.KEY_PROFILE),
                intOf(v, MediaFormat.KEY_COLOR_TRANSFER), intOf(v, MediaFormat.KEY_COLOR_STANDARD), sound)
            Tracks(fps, audio, plain)
        } catch (e: Exception) { Tracks(0f, 0, false) } finally {
            try { x.release() } catch (_: Exception) {}
        }
    }

    /**
     * A copy made here is ready to go: its location and camera details are out (Mp4Scrub), and it is plain H.264 and AAC
     * in an MP4 (an HDR source the phone couldn't map to SDR isn't). Deleted when it isn't.
     */
    private fun ready(out: File): Boolean {
        val ok = out.length() > 0 && Mp4Scrub.scrubFile(out) && tracks { it.setDataSource(out.absolutePath) }.plain &&
            GroupVideo.isMp4Head(try { out.inputStream().use { i -> ByteArray(12).also { b -> i.read(b) } } } catch (_: Exception) { ByteArray(0) })
        if (!ok) out.delete()
        return ok
    }

    private fun size(context: Context, uri: Uri): Long {
        val fd = try { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } } catch (_: Exception) { null }
        if (fd != null && fd > 0) return fd
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** This phone has an H.264 encoder for [w] x [h] at [fps] either way up. */
    fun canEncode(w: Int, h: Int, fps: Int): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) } &&
                info.getCapabilitiesForType(MimeTypes.VIDEO_H264).videoCapabilities?.let { c ->
                    c.areSizeAndRateSupported(w, h, fps.toDouble()) || c.areSizeAndRateSupported(h, w, fps.toDouble())
                } == true
        }
    } catch (_: Exception) { false }

    /** A phone Android calls low on memory: full copies are made at 720p there. */
    fun lowEnd(context: Context): Boolean = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    /** Copies [uri] as it is into [out] (a video already within the limits). False if it can't be read. */
    fun copy(context: Context, uri: Uri, out: File): Boolean = try {
        context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } != null && out.length() > 0
    } catch (_: Exception) { out.delete(); false }

    // The sizes copies are tried at (long side, short side): 1080p, 720p, and the old 480p video message size.
    private val P1080 = GroupVideo.FULL_LONG to GroupVideo.FULL_SHORT
    private val P720 = GroupVideo.SMALL_LONG to GroupVideo.SMALL_SHORT
    private val P480 = 854 to Videos.SIDE

    /**
     * The full copy (1080p30 at most, any length) of [uri] into [out]: as it is (without its location and camera details)
     * when it's plain H.264 within the limits, else converted. Never cut. Null when made, else what to say.
     */
    suspend fun fullCopy(context: Context, uri: Uri, info: GroupVideo.Info, out: File, progress: (Float) -> Unit): String? {
        if (GroupVideo.fitsFull(info)) {
            if (!copy(context, uri, out)) return "That video can't be read."
            if (ready(out) && out.length() in 1..GroupVideo.FULL_MAX_BYTES) return null
            out.delete()   // its details couldn't be taken out as it is: converted below instead
        }
        val sizes = if (lowEnd(context) || !canEncode(GroupVideo.FULL_LONG, GroupVideo.FULL_SHORT, GroupVideo.FULL_FPS))
            listOf(P720) else listOf(P1080, P720)
        for ((long, short) in sizes) {
            if (convert(context, uri, info, out, long, short, GroupVideo.fullBitrate(info.ms), progress) && ready(out) &&
                out.length() in 1..GroupVideo.FULL_MAX_BYTES) return null
            out.delete()
        }
        return "This phone couldn't convert that video."
    }

    /**
     * The 720p copy (up to 5 minutes, for those not direct: FileLink over Tor) into [out]: as it is when it's plain H.264
     * at 720p30 at most, else converted with Media3 (the sheet asked first when it's bigger than 720p). Never cut: a video
     * over 5 minutes isn't made at all. Null when made, else what to say.
     */
    suspend fun smallCopy(context: Context, uri: Uri, info: GroupVideo.Info, out: File, progress: (Float) -> Unit): String? {
        if (info.ms > GroupVideo.SMALL_MAX_MS + GroupVideo.SLACK_MS) return GroupVideo.TOO_LONG_TEXT
        if (GroupVideo.fitsSmall(info)) {
            if (!copy(context, uri, out)) return "That video can't be read."
            if (ready(out)) return null
            out.delete()
        }
        // 720p, then 480p if the phone couldn't.
        for ((long, short) in listOf(P720, P480)) {
            if (convert(context, uri, info, out, long, short, GroupVideo.SMALL_VIDEO_BITRATE, progress) && ready(out)) return null
            out.delete()
        }
        return "This phone couldn't convert that video."
    }

    /**
     * The relay copy for older apps (720p, at most 20 s, fits the relays) into [out]. Never cut: a longer video gets no
     * relay copy. Null when made, else what to say.
     */
    suspend fun relayCopy(context: Context, uri: Uri, info: GroupVideo.Info, out: File, progress: (Float) -> Unit): String? {
        if (GroupVideo.fitsRelay(info)) {
            if (!copy(context, uri, out)) return "That video can't be read."
            if (ready(out) && GroupVideo.relayFits(out.length())) return null
            out.delete()
        }
        val ms = info.ms
        if (ms > GroupVideo.RELAY_MAX_MS + GroupVideo.SLACK_MS) return "Older apps get videos up to 20 seconds."
        var bitrate = GroupVideo.relayBitrate(ms, audioBps = info.audioBps)
        // 720p, once more at a lower bitrate if it ran over, then 480p. A 720p try that failed outright goes straight to
        // 480p: the same try again would only fail the same way.
        val sizes = listOf(P720, P720, P480)
        var i = 0
        while (i < sizes.size) {
            val (long, short) = sizes[i]
            if (convert(context, uri, info, out, long, short, bitrate, progress) && ready(out)) {
                val got = out.length()
                if (GroupVideo.relayFits(got)) return null
                bitrate = GroupVideo.retryBitrate(bitrate, got, ms)
                i++
            } else i = if (i < sizes.size - 1) sizes.size - 1 else sizes.size
            out.delete()
        }
        return "This phone couldn't make a small enough copy for older apps."
    }

    /**
     * Converts [uri] to an H.264 and AAC MP4 in [out]: within [long] x [short] either way up (both sides, never made
     * bigger, its shape kept), at most 30 frames a second, video at [bitrate], HDR mapped to SDR (Android 10 and up).
     * False if the phone couldn't (the reason isn't shown: the caller tries smaller).
     */
    private suspend fun convert(context: Context, uri: Uri, info: GroupVideo.Info, out: File, long: Int, short: Int, bitrate: Int,
                                progress: (Float) -> Unit): Boolean = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        out.delete()
        main.post {
            try {
                // The whole video, never clipped (1.42: videos are never cut).
                val item = MediaItem.Builder().setUri(uri).build()
                val effects = ArrayList<Effect>()
                if (!GroupVideo.withinFps(info.fps, GroupVideo.FULL_FPS)) effects.add(FrameDropEffect.createDefaultFrameDropEffect(GroupVideo.FULL_FPS.toFloat()))
                // The frames reach the effects upright, so the target is worked out on the upright size.
                val (uw, uh) = GroupVideo.upright(info)
                GroupVideo.target(uw, uh, long, short)?.let { (tw, th) -> effects.add(Presentation.createForWidthAndHeight(tw, th, Presentation.LAYOUT_SCALE_TO_FIT)) }
                val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).build()
                @Suppress("DEPRECATION")
                val sequence = EditedMediaItemSequence(edited)
                val comp = Composition.Builder(sequence).apply {
                    if (Build.VERSION.SDK_INT >= 29) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                }.build()
                val encoders = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setEnableFallback(true)
                    .build()
                var over = false
                val holder = ProgressHolder()
                lateinit var t: Transformer
                val tick = object : Runnable {
                    override fun run() {
                        if (over) return
                        try { if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f) } catch (_: Exception) {}
                        main.postDelayed(this, 300)
                    }
                }
                t = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoders)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            over = true
                            if (cont.isActive) cont.resume(out.length() > 0)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            over = true
                            out.delete()
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
                cont.invokeOnCancellation { main.post { over = true; try { t.cancel() } catch (_: Exception) {}; out.delete() } }
                t.start(comp, out.absolutePath)
                main.postDelayed(tick, 300)
            } catch (e: Exception) {
                out.delete()
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
