package io.github.ravenor907.muninn.tv

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * The microphone on a Kids' TV. TV mics (the remote's, a camera's, a USB one, the TV's own far-field
 * mics) are tuned for a voice search from across the room, come in quiet, and sit next to loud speakers.
 * Everything recorded on the TV (calls, voice messages, speech to text) goes through the same steps:
 *  - [TvVoice] where the TV can load it: WebRTC's echo canceller (calls), high-pass filter and automatic
 *    gain, then RNNoise for the room's noise. The microphone is then opened as VOICE_RECOGNITION (the
 *    clean path voice search uses) for calls too, and Android's own effects are left off so nothing
 *    is processed twice;
 *  - without it: VOICE_RECOGNITION for clips and dictation, VOICE_COMMUNICATION for calls
 *    (Android's echo canceller), each falling back to MIC, and Android's AutomaticGainControl;
 *  - [Gain]: the "Microphone level" (the parent's, in the signed config, 7.5, or set on the TV itself)
 *    with a soft limiter, so a loud voice close by bends instead of clipping. After [TvVoice]'s automatic
 *    gain it is centered on 50 (0 dB, -15 to +15 dB); without it, 0.3 dB per step.
 * Only used in kids' mode ([active]); a phone records the plain way.
 */
object TvMic {
    /** True on a Kids' TV once its config is in. */
    @Volatile var active = false
    /** The level in use, 0..100: the parent's (config "mic") or the last one set on the TV. */
    @Volatile var level = TvRules.DEFAULT_MIC

    /** 0 is the TV's own level, 100 is +30 dB; the default 50 is +15 dB (about 5.6 times louder). */
    fun gainDb(level: Int): Float = level.coerceIn(0, TvRules.MAX_MIC) * 0.3f

    /** After [TvVoice]'s automatic gain: 50 leaves it, 0 is 15 dB quieter, 100 is 15 dB louder. */
    const val PROCESSED_OFFSET_DB = -15f

    /** Whether [TvVoice] runs here (a Kids' TV whose library loaded). */
    val processing: Boolean get() = active && TvVoice.available

    /** k5 (7.10): noise removal on this Kids' TV, [TvRules.NR_NONE] to [TvRules.NR_BOTH] (both unless a parent or the TV changed it). */
    @Volatile var noise = TvRules.NR_BOTH

    /** [TvVoice]'s noise flags for [noise]: Standard is WebRTC's suppressor, Strong is RNNoise, both runs both. */
    fun noiseFlags(): Int = when (noise) {
        TvRules.NR_NONE -> 0
        TvRules.NR_STANDARD -> TvVoice.NS
        TvRules.NR_STRONG -> TvVoice.RNNOISE
        else -> TvVoice.NS or TvVoice.RNNOISE
    }

    /** Android's own noise suppressor may run (a phone always; a Kids' TV unless its noise removal is None). */
    fun platformNoise(): Boolean = !active || noise != TvRules.NR_NONE

    /**
     * Gain with a soft limiter. Below [KNEE] the signal is only made louder; above it, it curves smoothly
     * toward full scale (tanh) and never wraps around. Pure Kotlin, so the unit tests can run it.
     * [offsetDb] shifts the whole range ([PROCESSED_OFFSET_DB] after [TvVoice]).
     */
    class Gain(private val offsetDb: Float, private val level: () -> Int) {
        /** Following [level] live, no offset. */
        constructor(level: () -> Int) : this(0f, level)
        /** A fixed level (tests, a clip that is already recording). */
        constructor(level: Int, offsetDb: Float = 0f) : this(offsetDb, { level })

        private var at = Int.MIN_VALUE
        private var g = 1f

        /** The factor for the level right now: a new level applies from the next frame. */
        fun factor(): Float {
            val l = level()
            if (l != at) { at = l; val db = gainDb(l) + offsetDb; g = if (abs(db) < 0.05f) 1f else 10f.pow(db / 20f) }
            return g
        }

        fun process(pcm: ShortArray, n: Int = pcm.size) {
            val g = factor()
            if (g == 1f) return
            for (i in 0 until n) pcm[i] = (shape(pcm[i] / 32768f * g) * 32767f).toInt().toShort()
        }

        companion object {
            const val KNEE = 0.6f
            fun shape(x: Float): Float {
                val a = abs(x)
                if (a <= KNEE) return x
                val y = KNEE + (1f - KNEE) * tanh((a - KNEE) / (1f - KNEE))
                return if (x < 0) -y else y
            }
        }
    }

    /** The gain stage that goes after [p] (or alone, without it), following [level] live. */
    fun gainFor(p: TvVoice.Processor?): Gain = Gain(if (p != null && p.flags and TvVoice.AGC != 0) PROCESSED_OFFSET_DB else 0f) { level }

    /** The microphone's loudness for a level meter, 0..1 (about -60 dBFS to full scale). */
    @Volatile var meter = 0f; private set

    internal fun measure(pcm: ShortArray, n: Int) {
        if (n <= 0) return
        var sum = 0.0
        for (i in 0 until n) { val v = pcm[i].toDouble(); sum += v * v }
        val db = 10 * kotlin.math.log10(sum / n / (32768.0 * 32768.0) + 1e-12)
        meter = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    // ------------------------------------------------------------------ for calls (CallMedia asks)

    @Volatile private var callAgc: AutomaticGainControl? = null

    /**
     * The call's microphone sources, in order. A phone uses exactly VOICE_COMMUNICATION. A Kids'
     * TV with [TvVoice] starts with VOICE_RECOGNITION (the path its clips already use, with no voice-call
     * processing of the TV's own that cuts words off), then MIC, then VOICE_COMMUNICATION; without it,
     * VOICE_COMMUNICATION (Android's echo canceller) and then MIC.
     */
    fun callSources(): IntArray = callSources(active, processing)
    fun callSources(tv: Boolean, processed: Boolean = false): IntArray = when {
        !tv -> intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        processed -> intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        else -> intArrayOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)
    }

    /** False on a Kids' TV with [TvVoice]: Android's echo canceller and noise suppressor stay off (no double processing). */
    fun platformEffects(): Boolean = !processing

    /** The TV's own output rate when it's one [TvVoice] handles (most TVs run 48 kHz), else 0. */
    fun nativeRate(c: Context): Int = try {
        val r = c.getSystemService(android.media.AudioManager::class.java)
            ?.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
        if (r == 48_000 || r == 44_100) r else 0
    } catch (e: Exception) { 0 }

    /**
     * A call's microphone and speaker sound on a Kids' TV. The microphone and the speaker both run at the
     * TV's own rate ([captureRate], [playRate]: usually 48 kHz, so Android doesn't resample, which cheap
     * TVs do badly), and [TvVoice] turns it into the call's 16 kHz and back, with the echo canceller
     * hearing exactly what the speakers play. [capture] and [render] always work in 20 ms frames.
     */
    class CallAudio internal constructor(val captureRate: Int, val playRate: Int, private val callRate: Int) {
        /** Samples in one 20 ms frame at the microphone's and at the speaker's rate. */
        val captureFrame = captureRate / 50
        val playFrame = playRate / 50
        @Volatile private var proc: TvVoice.Processor? = null
        /** The level after TvVoice's automatic gain, or on its own. */
        private val afterAgc = Gain(PROCESSED_OFFSET_DB) { TvMic.level }
        private val plain = Gain { TvMic.level }
        private val gain: Gain get() = if ((proc?.flags ?: 0) and TvVoice.AGC != 0) afterAgc else plain

        internal fun start() {
            // Everything; if that can't start, at least the clean rate conversion.
            proc = TvVoice.Processor.open(captureRate, callRate, callRate, playRate, TvVoice.AEC or noiseFlags() or TvVoice.AGC)
                ?: TvVoice.Processor.open(captureRate, callRate, callRate, playRate, 0)
        }

        /** Whether [TvVoice] runs for this call (else plain gain, and the rates are the call's own). */
        val processed: Boolean get() = proc != null

        /** One 20 ms frame from the microphone ([captureFrame] samples) into [out] (the call's 20 ms). True if [out] is full. */
        fun capture(input: ShortArray, out: ShortArray): Boolean {
            val p = proc
            val n = if (p != null) p.capture(input, captureFrame, out) else convert(input, captureFrame, out)
            if (n < out.size) return false
            gain.process(out, n)
            measure(out, n)
            return true
        }

        /** One decoded 20 ms frame of the call into [out] ([playFrame] samples) for the speaker. Returns its length. */
        fun render(pcm: ShortArray, out: ShortArray): Int {
            val p = proc ?: return convert(pcm, pcm.size, out)
            return p.render(pcm, pcm.size, out)
        }

        /** Without [TvVoice]: a plain copy, or (should the library stop) picking or repeating samples. */
        private fun convert(a: ShortArray, n: Int, out: ShortArray): Int {
            val m = minOf(out.size, if (a === out) n else out.size)
            if (n == m) { if (a !== out) System.arraycopy(a, 0, out, 0, n); return n }
            for (i in 0 until m) out[i] = a[(i.toLong() * n / m).toInt().coerceAtMost(n - 1)]
            return m
        }

        /** The speaker-to-microphone delay, for the echo canceller's first guess. */
        fun delay(ms: Int) { proc?.delay(ms) }

        internal fun close() { proc?.close(); proc = null }

        /** The plain in-place call without [TvVoice]: gain only, at the call's rate. */
        fun process(pcm: ShortArray, n: Int) { gain.process(pcm, n); measure(pcm, n) }
    }

    @Volatile private var call: CallAudio? = null

    /**
     * The rates a call opens its microphone and speaker at: the TV's own with [TvVoice], else [callRate].
     * Call before opening them; [forCall] then gives the [CallAudio] that matches.
     */
    fun callRates(c: Context, callRate: Int): Pair<Int, Int> {
        if (!processing) return callRate to callRate
        val r = nativeRate(c)
        return if (r > 0) r to r else callRate to callRate
    }

    /**
     * The sound stage for a call's microphone, or null on a phone. Opened once the microphone is (its
     * [sessionId]); turns on Android's AGC only when [TvVoice] isn't there. It reads [level] for every
     * frame, so a new "Microphone level" reaches a call that is going on.
     */
    fun forCall(sessionId: Int, captureRate: Int = 16_000, playRate: Int = captureRate, callRate: Int = 16_000): CallAudio? {
        if (!active) return null
        release()
        val a = CallAudio(captureRate, playRate, callRate)
        if (processing) a.start()
        if (!a.processed) callAgc = agc(sessionId)
        call = a
        return a
    }

    fun release() {
        try { callAgc?.release() } catch (_: Exception) {}
        callAgc = null
        call?.close(); call = null
    }

    private fun agc(sessionId: Int): AutomaticGainControl? = try {
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.apply { enabled = true } else null
    } catch (e: Exception) { null }

    // ------------------------------------------------------------------ opening the microphone

    /** An AudioRecord for clips or dictation at [rate] Hz, mono 16-bit: the first source that opens. */
    @SuppressLint("MissingPermission")
    fun open(rate: Int, frame: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        for (src in intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            // A roomy buffer: a slow TV that falls behind for a moment loses nothing.
            val r = try { AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, frame * 2 * 8, rate / 5 * 2)) } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            r?.release()
        }
        return null
    }

    /** Android's AGC for a clip's recording, only without [TvVoice] (which has its own); release it when the recording ends. */
    fun clipAgc(sessionId: Int): AutomaticGainControl? = if (TvVoice.available) null else agc(sessionId)

    /**
     * Noise suppression and automatic gain for a clip or dictation ([TvVoice] without the echo canceller:
     * nothing is playing), [rate] in and out. Null without the library. Close it when the recording ends.
     */
    fun clipProcessor(rate: Int): TvVoice.Processor? = TvVoice.Processor.open(rate, rate, 16_000, 16_000, noiseFlags() or TvVoice.AGC)

    /** Reads exactly [n] samples (fewer only at the end or on an error, as AudioRecord says). */
    fun readFully(rec: AudioRecord, pcm: ShortArray, n: Int): Int {
        var got = 0
        while (got < n) { val r = rec.read(pcm, got, n - got); if (r <= 0) return if (got > 0) got else r; got += r }
        return got
    }

    /** Raises this thread to audio priority (the microphone and speaker loops). */
    fun audioThread() { try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {} }
}

/**
 * Voice messages on the TV, with the same result as [io.github.ravenor907.muninn.net.Voice] (plain AAC
 * frames in ADTS, about 4 KB a second, up to 5 minutes, kept only in memory) but recorded through
 * [TvMic]: an AudioRecord, [TvVoice] (noise suppression and automatic gain) where the TV has it, the gain
 * stage, then Android's AAC encoder.
 */
object TvRecorder {
    private const val RATE = 44_100
    /** 20 ms: [TvVoice] works in 10 ms steps. */
    private const val FRAME = 882
    private const val MAX_BYTES = 2 * 1024 * 1024
    val recording = MutableStateFlow(false)
    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** Called on the main thread when the 5-minute limit stops it; the screen then sends it. */
    var onLimit: (() -> Unit)? = null

    /** One recording: its sound so far and its thread's flags. */
    private class Take(val started: Long) {
        val buf = ByteArrayOutputStream()
        @Volatile var stop = false
        @Volatile var failed = false
        var thread: Thread? = null
    }
    /** The recording under way, until [finish] or [cancel] takes it. */
    @Volatile private var take: Take? = null

    fun start(context: Context): Boolean {
        cancel()
        val rec = TvMic.open(RATE, FRAME) ?: return false
        val codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 4)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) { rec.release(); return false }
        val t = Take(System.currentTimeMillis())
        take = t
        recording.value = true
        t.thread = Thread({ loop(rec, codec, t) }, "muninn-tv-voice").apply { isDaemon = true; start() }
        return true
    }

    private fun loop(rec: AudioRecord, codec: MediaCodec, t: Take) {
        TvMic.audioThread()
        val agc = TvMic.clipAgc(rec.audioSessionId)
        val proc = TvMic.clipProcessor(RATE)
        val gain = TvMic.gainFor(proc)
        val raw = ShortArray(FRAME)
        val pcm = ShortArray(FRAME)
        val bytes = ByteArray(FRAME * 2)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        // The 5-minute limit ended it (not OK or Back): the screen is told once the clip is complete.
        var limited = false
        try {
            rec.startRecording()
            while (true) {
                if (!t.stop && System.currentTimeMillis() - t.started >= io.github.ravenor907.muninn.net.Voice.MAX_MS) limited = true
                val end = t.stop || limited
                var n = 0
                if (!end) {
                    n = TvMic.readFully(rec, raw, FRAME); if (n < 0) { t.failed = true; break }
                    if (proc != null && n == FRAME) proc.capture(raw, n, pcm) else System.arraycopy(raw, 0, pcm, 0, n)
                }
                gain.process(pcm, n)
                TvMic.measure(pcm, n)
                for (i in 0 until n) { bytes[2 * i] = pcm[i].toInt().toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                val inIdx = codec.dequeueInputBuffer(20_000)
                var queuedEnd = false
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    ib.clear(); ib.put(bytes, 0, n * 2)
                    codec.queueInputBuffer(inIdx, 0, n * 2, samples * 1_000_000 / RATE, if (end) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    samples += n
                    queuedEnd = end
                }
                // Once the end is in, [drain] has waited for the rest: what came out is the clip.
                if (drain(codec, info, t.buf, end) || queuedEnd) break
            }
        } catch (e: Exception) { t.failed = true
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            try { agc?.release() } catch (_: Exception) {}
            proc?.close()
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
        // Only while it is still the recording under way: OK or Back may have taken it in the meantime.
        if (limited) main.post { if (take === t) onLimit?.invoke() }
    }

    /** Moves encoded frames out, each with its ADTS header. True once the end of stream came out. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo, buf: ByteArrayOutputStream, ending: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + if (ending) 2000 else 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (ending) 10_000 else 0)
            if (idx < 0) { if (!ending || System.currentTimeMillis() > deadline) return false else continue }
            val ob = codec.getOutputBuffer(idx)
            if (ob != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val frame = ByteArray(info.size)
                ob.position(info.offset); ob.get(frame)
                synchronized(buf) { if (buf.size() < MAX_BYTES - frame.size - 7) { buf.write(adts(frame.size)); buf.write(frame) } }
            }
            codec.releaseOutputBuffer(idx, false)
            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
        }
    }

    fun elapsed(): Long = take?.let { System.currentTimeMillis() - it.started } ?: 0L

    /**
     * Stops and returns (sound, length in ms), or null if it was too short or failed. The encoder's last
     * frames are waited for on an IO thread (up to 4 seconds), never on the main thread.
     */
    suspend fun finish(): Pair<ByteArray, Int>? {
        val t = take ?: return null
        take = null
        val ms = (System.currentTimeMillis() - t.started).toInt().coerceAtMost(io.github.ravenor907.muninn.net.Voice.MAX_MS)
        t.stop = true
        withContext(Dispatchers.IO) { t.thread?.join(4000) }
        if (take == null) recording.value = false
        if (t.failed || ms < 700) return null
        val bytes = synchronized(t.buf) { t.buf.toByteArray() }
        return io.github.ravenor907.muninn.net.Voice.check(listOf(bytes))?.let { it to ms }
    }

    fun cancel() {
        val t = take
        take = null
        if (t != null) { t.stop = true; t.thread?.join(2000) }
        recording.value = false
    }

    /** The 7-byte ADTS header for one AAC LC frame of [size] bytes, 44.1 kHz, mono. */
    fun adts(size: Int): ByteArray {
        val len = size + 7
        val profile = 2   // AAC LC
        val freq = 4      // 44100 Hz
        val chan = 1
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((profile - 1) shl 6) or (freq shl 2) or (chan shr 2)).toByte(),
            (((chan and 3) shl 6) or (len shr 11)).toByte(),
            ((len and 0x7FF) shr 3).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
