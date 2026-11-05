package io.github.ravenor907.muninn.net

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

/**
 * Voice messages, built to carry nothing but the voice:
 *  - Plain AAC frames (ADTS): no file container, so no date, place, device or app name in it.
 *  - Recorded through a pipe straight into memory, and played back from memory: an unencrypted
 *    copy never touches the phone's storage. Only the sealed copy in the vault is ever saved.
 *  - The voice-call microphone mode, so the phone's noise filtering cuts background sound
 *    (which could hint at where someone is).
 * About 4 KB a second; up to 5 minutes.
 */
object Voice {
    const val MAX_MS = 5 * 60_000
    private const val MAX_BYTES = 2 * 1024 * 1024

    // ------------------------------------------------------------------ recording
    private var recorder: MediaRecorder? = null
    private var pipe: android.os.ParcelFileDescriptor? = null
    private var captured: ByteArrayOutputStream? = null
    private var reader: Thread? = null
    private var started = 0L
    /** Recording right now. */
    val recording = MutableStateFlow(false)

    /** Called when the 5-minute limit stops the recording by itself (the screen then sends it). */
    var onLimit: (() -> Unit)? = null
    @Volatile private var limitHit = false

    fun start(context: Context): Boolean {
        stopPlaying()
        cancel()
        limitHit = false
        var r: MediaRecorder? = null
        var readSide: android.os.ParcelFileDescriptor? = null
        var writeSide: android.os.ParcelFileDescriptor? = null
        return try {
            val p = android.os.ParcelFileDescriptor.createPipe()
            readSide = p[0]; writeSide = p[1]
            r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MAX_MS)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) { limitHit = true; onLimit?.invoke() } }
            r.setOutputFile(writeSide.fileDescriptor)
            r.prepare()
            val buf = ByteArrayOutputStream()
            val rs = readSide
            val t = Thread {
                try { android.os.ParcelFileDescriptor.AutoCloseInputStream(rs).use { ins ->
                    val b = ByteArray(8192)
                    while (true) { val n = ins.read(b); if (n < 0) break; synchronized(buf) { if (buf.size() < MAX_BYTES) buf.write(b, 0, n) } }
                } } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            readSide = null   // the thread owns it now
            r.start()
            recorder = r; pipe = writeSide; captured = buf; reader = t
            started = System.currentTimeMillis(); recording.value = true
            true
        } catch (e: Exception) {
            // Let go of the microphone and the pipe, or later recordings fail.
            try { r?.release() } catch (_: Exception) {}
            try { writeSide?.close() } catch (_: Exception) {}
            try { readSide?.close() } catch (_: Exception) {}
            recorder = null; pipe = null; recording.value = false
            false
        }
    }

    fun elapsed(): Long = if (recording.value) System.currentTimeMillis() - started else 0

    /** Stops and returns (sound, length in ms), or null if it was too short or failed. The file is wiped. */
    fun finish(): Pair<ByteArray, Int>? {
        val r = recorder ?: return null
        val ms = (System.currentTimeMillis() - started).toInt().coerceAtMost(MAX_MS)
        val ok = try { r.stop(); true } catch (e: Exception) { limitHit }   // already stopped at the limit: keep it
        r.release(); recorder = null; recording.value = false
        try { pipe?.close() } catch (_: Exception) {}
        pipe = null
        reader?.join(2000); reader = null
        val buf = captured; captured = null
        val bytes = if (ok && ms >= 700 && buf != null) synchronized(buf) { buf.toByteArray() } else null
        return bytes?.let { check(listOf(it)) }?.let { it to ms }
    }

    fun cancel() {
        recorder?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        recorder = null; recording.value = false
        try { pipe?.close() } catch (_: Exception) {}
        pipe = null; reader = null; captured = null
    }

    /** Joins received pieces; null unless it's AAC frames of a sensible size. */
    fun check(parts: List<ByteArray>): ByteArray? {
        val out = ByteArrayOutputStream(); parts.forEach { out.write(it) }
        val b = out.toByteArray()
        val adts = b.size in 7..MAX_BYTES && b[0] == 0xFF.toByte() && (b[1].toInt() and 0xF0) == 0xF0
        return if (adts) b else null
    }

    // ------------------------------------------------------------------ playing
    private var player: MediaPlayer? = null
    /** The message playing now ("" for none), and how far along (0..1). */
    val playing = MutableStateFlow("")
    val progress = MutableStateFlow(0f)

    /** Plays a voice message straight from memory. */
    fun play(msgId: String, sound: ByteArray) {
        stopPlaying()
        try {
            val p = MediaPlayer()
            p.setDataSource(object : android.media.MediaDataSource() {
                override fun getSize(): Long = sound.size.toLong()
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position >= sound.size) return -1
                    val n = minOf(size, sound.size - position.toInt())
                    System.arraycopy(sound, position.toInt(), buffer, offset, n)
                    return n
                }
                override fun close() {}
            })
            p.setOnCompletionListener { stopPlaying() }
            p.prepare()
            p.start()
            player = p; playing.value = msgId
        } catch (e: Exception) { stopPlaying() }
    }

    fun tick() {
        val p = player ?: return
        try { if (p.duration > 0) progress.value = p.currentPosition.toFloat() / p.duration } catch (_: Exception) {}
    }

    fun stopPlaying() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null; playing.value = ""; progress.value = 0f
    }

    fun length(ms: Int): String { val s = (ms + 500) / 1000; return "%d:%02d".format(s / 60, s % 60) }
}
