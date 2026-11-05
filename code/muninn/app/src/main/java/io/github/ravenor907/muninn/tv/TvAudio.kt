package io.github.ravenor907.muninn.tv

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Microphones and speech to text on the TV (the remote's mic, a USB or Bluetooth mic, a camera's mic). */
object TvAudio {
    /** Inputs that aren't a microphone someone can talk into. */
    private val NOT_MICS = buildSet {
        add(AudioDeviceInfo.TYPE_REMOTE_SUBMIX); add(AudioDeviceInfo.TYPE_TELEPHONY); add(AudioDeviceInfo.TYPE_FM_TUNER)
        add(AudioDeviceInfo.TYPE_TV_TUNER); add(AudioDeviceInfo.TYPE_HDMI); add(AudioDeviceInfo.TYPE_HDMI_ARC)
        add(AudioDeviceInfo.TYPE_LINE_ANALOG); add(AudioDeviceInfo.TYPE_LINE_DIGITAL); add(AudioDeviceInfo.TYPE_AUX_LINE)
        if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_HDMI_EARC) }
    }

    /** True if some microphone is connected right now. */
    fun hasMic(context: Context): Boolean = try {
        context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.any { it.isSource && it.type !in NOT_MICS } == true
    } catch (e: Exception) { false }

    /** Only with an on-device recognizer (Android 12 and up), so a child's voice never leaves the TV. Without one, "Say it as text" is hidden. */
    fun canDictate(context: Context): Boolean = try {
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } catch (e: Exception) { false }
}

/**
 * One "Say something": listens once, reports what it heard so far and the final words. Only ever the on-device
 * recognizer (nothing leaves the TV), never the TV's usual speech service, which may send the voice away.
 * Create and use it on the main thread; [close] when the screen goes.
 */
class TvSpeech(private val context: Context) {
    private var rec: SpeechRecognizer? = null
    /** Android 13+: the recognizer listens to our own microphone stream, made louder by [TvMic]. */
    private var feed: Feed? = null

    fun start(partial: (String) -> Unit, done: (String) -> Unit, fail: (String) -> Unit) = start(partial, done, fail, boost = true)

    private fun start(partial: (String) -> Unit, done: (String) -> Unit, fail: (String) -> Unit, boost: Boolean) {
        close()
        val f = if (boost && Build.VERSION.SDK_INT >= 33 && TvMic.active) Feed.open() else null
        feed = f
        var heard = false
        val r = try {
            if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else null
        } catch (e: Exception) { null }
        if (r == null) { f?.close(); feed = null; fail(TvWords.NO_TYPING); return }
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { heard = true; partial(it) }
            }
            override fun onResults(results: Bundle?) {
                feed?.close()
                val t = first(results)
                if (t.isNullOrBlank()) fail(TvWords.NOT_HEARD) else done(t)
            }
            override fun onError(error: Int) {
                feed?.close()
                // A recognizer that can't take our stream: once more with the TV's own microphone path.
                val plain = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_NETWORK ||
                    error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                if (f != null && !heard && !plain) { android.os.Handler(android.os.Looper.getMainLooper()).post { start(partial, done, fail, boost = false) }; return }
                fail(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> TvWords.NOT_HEARD
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> TvWords.ASK_PARENT
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> TvWords.NO_INTERNET
                    else -> TvWords.NO_MIC
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        if (f != null && Build.VERSION.SDK_INT >= 33) {
            i.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, f.readSide)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Feed.RATE)
            f.start()
        }
        try { r.startListening(i) } catch (e: Exception) { feed?.close(); fail(TvWords.NO_MIC) }
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    /** "Done": with our own stream, ending it is what tells the recognizer the person stopped. */
    fun stop() { feed?.close(); try { rec?.stopListening() } catch (_: Exception) {} }

    fun close() {
        feed?.close(); feed = null
        try { rec?.cancel(); rec?.destroy() } catch (_: Exception) {}
        rec = null
    }

    /** 16 kHz mono 16-bit PCM from [TvMic], cleaned up by [TvVoice] and louder, into a pipe the recognizer reads. */
    private class Feed(private val record: android.media.AudioRecord, val readSide: android.os.ParcelFileDescriptor,
                       private val writeSide: android.os.ParcelFileDescriptor) {
        @Volatile private var stopped = false
        private var thread: Thread? = null

        fun start() {
            thread = Thread({
                TvMic.audioThread()
                val agc = TvMic.clipAgc(record.audioSessionId)
                val proc = TvMic.clipProcessor(RATE)
                val gain = TvMic.gainFor(proc)
                val raw = ShortArray(FRAME)
                val pcm = ShortArray(FRAME)
                val bytes = ByteArray(FRAME * 2)
                try {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { os ->
                        record.startRecording()
                        while (!stopped) {
                            val n = TvMic.readFully(record, raw, FRAME)
                            if (n <= 0) break
                            if (proc != null && n == FRAME) proc.capture(raw, n, pcm) else System.arraycopy(raw, 0, pcm, 0, n)
                            gain.process(pcm, n)
                            for (i in 0 until n) { bytes[2 * i] = pcm[i].toInt().toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
                            os.write(bytes, 0, n * 2)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { record.stop() } catch (_: Exception) {}
                    record.release()
                    try { agc?.release() } catch (_: Exception) {}
                    proc?.close()
                }
            }, "muninn-tv-speech").apply { isDaemon = true; start() }
        }

        fun close() {
            stopped = true
            if (thread == null) { try { writeSide.close() } catch (_: Exception) {}; record.release() }
            try { readSide.close() } catch (_: Exception) {}
        }

        companion object {
            const val RATE = 16_000
            private const val FRAME = 320

            fun open(): Feed? {
                val rec = TvMic.open(RATE, FRAME) ?: return null
                return try {
                    val p = android.os.ParcelFileDescriptor.createPipe()
                    Feed(rec, p[0], p[1])
                } catch (e: Exception) { rec.release(); null }
            }
        }
    }
}
