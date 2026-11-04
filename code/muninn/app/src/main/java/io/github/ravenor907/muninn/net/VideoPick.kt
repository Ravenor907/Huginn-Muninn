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
 * Media3 Transformer ([fullCopy], [relayCopy]), which keeps the picture upright and uses the phone's own H.264 encoder.
 * Low-end phones: no 1080p encoder or little memory makes the full copy 720p; a copy that fails is tried once smaller
 * (720p, then 480p) before giving up with a plain reason. Nothing here touches the network.
 */
@OptIn(UnstableApi::class)
object VideoPick {
    /** Where copies are made (deleted once sent; whatever is left is cleared on the next pick). */
    fun dir(context: Context): File = File(context.cacheDir, "videos").also { it.mkdirs() }

    /** Clears copies left from before (a pick that was closed part way). */
    fun clearOld(context: Context) { dir(context).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000L) it.delete() } }

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
                val (trackFps, audio) = tracks(context, uri)
                if (trackFps > 0f) fps = trackFps
                if (w <= 0 || h <= 0 || ms <= 0) null else GroupVideo.Info(w, h, rot, fps, ms, size(context, uri), audio)
            }
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** The video track's frame rate and the sound's bitrate, as the file says (0 when it doesn't). */
    private fun tracks(context: Context, uri: Uri): Pair<Float, Int> {
        val x = MediaExtractor()
        return try {
            x.setDataSource(context, uri, null)
            var fps = 0f
            var audio = 0
            for (i in 0 until x.trackCount) {
                val f = x.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE))
                    fps = try { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() } catch (_: Exception) { try { f.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { 0f } }
                if (mime.startsWith("audio/") && f.containsKey(MediaFormat.KEY_BIT_RATE))
                    audio = try { f.getInteger(MediaFormat.KEY_BIT_RATE) } catch (_: Exception) { 0 }
            }
            fps to audio
        } catch (e: Exception) { 0f to 0 } finally {
            try { x.release() } catch (_: Exception) {}
        }
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

    /**
     * The full copy (1080p30 at most) of [uri] into [out]: as it is when it's within the limits, else converted.
     * [clip]: start and end in ms, or null for all of it. Null when made, else what to say.
     */
    suspend fun fullCopy(context: Context, uri: Uri, info: GroupVideo.Info, out: File, clip: Pair<Int, Int>?, progress: (Float) -> Unit): String? {
        if (clip == null && GroupVideo.fitsFull(info)) return if (copy(context, uri, out)) null else "That video can't be read."
        val ms = clip?.let { it.second - it.first } ?: info.ms
        val shorts = if (lowEnd(context) || !canEncode(GroupVideo.FULL_LONG, GroupVideo.FULL_SHORT, GroupVideo.FULL_FPS))
            listOf(GroupVideo.RELAY_SHORT) else listOf(GroupVideo.FULL_SHORT, GroupVideo.RELAY_SHORT)
        for (short in shorts) {
            if (convert(context, uri, info, out, short, GroupVideo.fullBitrate(ms), clip, progress) && out.length() in 1..GroupVideo.FULL_MAX_BYTES) return null
            out.delete()
        }
        return "This phone couldn't convert that video. Try a shorter one."
    }

    /** The relay copy (720p, at most 20 s, fits the relays) into [out]. Null when made, else what to say. */
    suspend fun relayCopy(context: Context, uri: Uri, info: GroupVideo.Info, out: File, clip: Pair<Int, Int>?, progress: (Float) -> Unit): String? {
        if (clip == null && GroupVideo.fitsRelay(info)) return if (copy(context, uri, out)) null else "That video can't be read."
        val ms = clip?.let { it.second - it.first } ?: info.ms
        if (ms > GroupVideo.RELAY_MAX_MS + GroupVideo.SLACK_MS) return "Over the internet, videos can be up to 20 seconds. Trim it first."
        var bitrate = GroupVideo.relayBitrate(ms, audioBps = info.audioBps)
        // 720p, once more at a lower bitrate if it ran over, then the old 480p video message size.
        for (short in listOf(GroupVideo.RELAY_SHORT, GroupVideo.RELAY_SHORT, Videos.SIDE)) {
            if (convert(context, uri, info, out, short, bitrate, clip, progress)) {
                val got = out.length()
                if (GroupVideo.relayFits(got)) return null
                bitrate = GroupVideo.retryBitrate(bitrate, got, ms)
            }
            out.delete()
        }
        return "This phone couldn't make a small enough copy of that video. Try a shorter one."
    }

    /**
     * Converts [uri] to an H.264 and AAC MP4 in [out]: its short side at most [short] (never made bigger), at most 30
     * frames a second, video at [bitrate]. False if the phone couldn't (the reason isn't shown: the caller tries smaller).
     */
    private suspend fun convert(context: Context, uri: Uri, info: GroupVideo.Info, out: File, short: Int, bitrate: Int, clip: Pair<Int, Int>?,
                                progress: (Float) -> Unit): Boolean = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        out.delete()
        main.post {
            try {
                val item = MediaItem.Builder().setUri(uri).apply {
                    if (clip != null) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.first.toLong()).setEndPositionMs(clip.second.toLong()).build())
                }.build()
                val effects = ArrayList<Effect>()
                if (!GroupVideo.withinFps(info.fps, GroupVideo.FULL_FPS)) effects.add(FrameDropEffect.createDefaultFrameDropEffect(GroupVideo.FULL_FPS.toFloat()))
                if (minOf(info.w, info.h) > short) effects.add(Presentation.createForShortSide(short))
                val edited = EditedMediaItem.Builder(item).setEffects(Effects(emptyList(), effects)).build()
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
                t.start(edited, out.absolutePath)
                main.postDelayed(tick, 300)
            } catch (e: Exception) {
                out.delete()
                if (cont.isActive) cont.resume(false)
            }
        }
    }
}
