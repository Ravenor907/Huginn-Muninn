package io.github.ravenor907.muninn.net

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Read aloud (code/shared/docs/voices.md): every message's press-and-hold menu, a Kids' Device's messages and quick
 * messages, and Daily Hávamál read through [speak], [stop] and [available].
 *
 * It speaks with the natural voice picked in Settings › Look › Read aloud (Female, Alba, the default; or Male, northern
 * English; on a Kids' Device the one a parent picked, cap k15), synthesized on the phone by the bundled speech engine
 * (sherpa-onnx). Only the chosen voice's data is downloaded, once, from the voices-1 release, checked against
 * code/shared/voices.sha256 (built in as assets/voices.sha256), and kept in Muninn's private files across updates. It
 * downloads in the background ([VoiceWorker]) the first time something is read, on Wi-Fi unless "Allow on mobile data"
 * is on, through Tor while Ultra privacy is on (it waits for Tor and says so). Until it's ready the phone's own voice
 * reads, only one that works offline (never one that sends the text away). It stops when the lock goes up, when a call
 * takes the sound, and when asked.
 */
object ReadAloud {
    private const val PREFS = "muninn-voice"
    private const val WORK = "readaloud-voice"
    /** Room for the download, unpacking and the rest of the phone. */
    private const val ROOM = 300L * 1024 * 1024

    @Volatile private var appContext: Context? = null
    private val token = AtomicLong(1)
    private val _status = MutableStateFlow("")
    /** One line for Settings: "Ready", "Downloading, 40%", "Waits for Wi-Fi"... */
    val status: StateFlow<String> = _status.asStateFlow()
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    /** Bumped when a voice is downloaded or deleted (or the switch or speed changes), so Settings reads them again. */
    private val _files = MutableStateFlow(0)
    val files: StateFlow<Int> = _files.asStateFlow()

    // The natural voice: made and spoken on its own threads, let go after a minute with nothing to read.
    private val synth = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "readaloud").apply { isDaemon = true } }
    private val player = Executors.newSingleThreadExecutor { r -> Thread(r, "readaloud-play").apply { isDaemon = true } }
    @Volatile private var engine: Any? = null
    @Volatile private var engineDir: String = ""
    @Volatile private var idle: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var focus: AudioFocusRequest? = null

    // The phone's own voice, until the natural one is in.
    @Volatile private var device: TextToSpeech? = null
    @Volatile private var deviceOk = false
    @Volatile private var deviceWaiting: List<ReadAloudText.Phrase>? = null
    // The phone's own voice failed to start (no offline voice): Read aloud isn't offered on it until Settings is opened
    // again or Muninn restarts, and the next read tries it afresh.
    @Volatile private var deviceBad = false
    @Volatile private var focusDevice: AudioFocusRequest? = null

    // A press of Read aloud on the same text while it's still being made (nothing heard yet) is ignored, so presses on
    // a slow TV don't pile up behind a sentence that can't be cut.
    @Volatile private var lastText: String? = null
    @Volatile private var heard = false

    // The natural voice stopped Muninn mid-sentence (a mark is left in the voices folder): twice and the phone's own
    // voice reads, until the voices are deleted or downloaded again.
    @Volatile private var markChecked = false
    @Volatile private var broken = false
    /** The voice files are changed (unpacked, put in place, deleted) one at a time. */
    private val filesLock = Any()

    /** The speech engine is in this build (it is in every release; a missing one falls back to the phone's own voice). */
    private val engineThere: Boolean by lazy {
        try { Class.forName("com.k2fsa.sherpa.onnx.OfflineTts", false, ReadAloud::class.java.classLoader); true } catch (_: Throwable) { false }
    }

    /**
     * The natural voice is offered in this build: the engine is here and the voice in use has its line in
     * voices.sha256 (or is already in). Until the voices are published Settings says "Natural voices aren't ready yet"
     * and nothing downloads.
     */
    fun natural(context: Context): Boolean {
        val app = context.applicationContext
        checkMark(app)
        return engineThere && !broken && (ready(app) || hashes(app).containsKey(ReadAloudText.fileFor(voice(app))))
    }

    /**
     * Something can read: Read aloud is on (Settings; always on a Kids' Device), and the natural voice (in, or on its
     * way) or the phone's own voice unless it failed to start.
     */
    val available: Boolean get() {
        val app = appContext
        if (app != null && !isOn(app)) return false
        return (app?.let { natural(it) && !useDevice(it) } ?: false) || !deviceBad
    }

    /** Settings' on/off switch (on until turned off). Always on for a Kids' Device (it has a parent's voice or speed). */
    fun isOn(context: Context): Boolean = prefs(context).let { it.getBoolean("on", true) || it.contains("kidSpeed") || it.contains("kid") }

    fun setOn(context: Context, on: Boolean) {
        val app = context.applicationContext
        appContext = app
        prefs(app).edit().putBoolean("on", on).apply()
        if (!on) stop()
        _files.value++
    }

    /**
     * The phone's own voice was picked in Settings (Device): the natural voice never reads or downloads. Not on a Kids'
     * Device whose parent picked a voice.
     */
    fun useDevice(context: Context): Boolean = prefs(context).let { it.getString("kid", null) == null && it.getString("voice", null) == ReadAloudText.DEVICE }

    // ---- the speed and cadence (1.43): speed 70 to 130 (100 until moved), cadence 50 to 200 (100 until moved).

    /** The speed and cadence the sliders show and set, live while they move (saved when the drag ends). */
    @Volatile private var liveSpeed: Float? = null
    @Volatile private var liveCadence: Float? = null

    /** The speed in use: a parent's on a Kids' Device (100 when the parent sent none), else this device's. */
    fun speed(context: Context): Float {
        val p = prefs(context)
        if (p.contains("kidSpeed")) return ReadAloudText.kidSpeed(p.getFloat("kidSpeed", 100f))
        return liveSpeed ?: ownSpeed(context)
    }

    /**
     * This device's own speed (Settings). Saved as a float; nothing saved reads 100. A speed saved before 1.43 is moved
     * once: 80% (1.42's default) becomes 100%, anything else is kept, held to 70 to 130.
     */
    fun ownSpeed(context: Context): Float {
        val p = prefs(context)
        // An int saved by a test build reads the same.
        val v: Number? = try { if (p.contains("speed")) p.getFloat("speed", ReadAloudText.SPEED_DEFAULT) else null }
            catch (_: ClassCastException) { p.getInt("speed", ReadAloudText.SPEED_DEFAULT.toInt()) }
        if (!p.getBoolean("speed143", false)) {
            val moved = ReadAloudText.migrateSpeed(v)
            p.edit().apply { if (v != null) { remove("speed"); putFloat("speed", moved) } }.putBoolean("speed143", true).apply()
            return moved
        }
        return ReadAloudText.speedOf(v)
    }

    /** While the slider moves: the next phrase read (and Hear a sample) uses [pct] at once, nothing is saved. */
    fun previewSpeed(context: Context, pct: Float) {
        appContext = context.applicationContext
        liveSpeed = ReadAloudText.speedOf(pct)
        applyDeviceRate(context.applicationContext)
    }

    /** The drag ended (or a key was let go): [pct] is saved on this device. */
    fun setSpeed(context: Context, pct: Float) {
        val app = context.applicationContext
        appContext = app
        val v = ReadAloudText.speedOf(pct)
        prefs(app).edit().putFloat("speed", v).putBoolean("speed143", true).apply()
        liveSpeed = null
        applyDeviceRate(app)
        _files.value++   // the Read aloud row's line shows the new speed
    }

    /** The cadence in use: 100 on a Kids' Device (no cap for it), else this device's (live while its slider moves). */
    fun cadence(context: Context): Float {
        val p = prefs(context)
        if (p.contains("kidSpeed") || p.contains("kid")) return ReadAloudText.CADENCE_DEFAULT
        return liveCadence ?: ownCadence(context)
    }

    /** This device's own cadence (Settings); nothing saved reads 100. */
    fun ownCadence(context: Context): Float {
        val p = prefs(context)
        return ReadAloudText.cadenceOf(if (p.contains("cadence")) p.getFloat("cadence", ReadAloudText.CADENCE_DEFAULT) else null)
    }

    /** While the Cadence slider moves: the next pause uses [pct], nothing is saved. */
    fun previewCadence(context: Context, pct: Float) {
        appContext = context.applicationContext
        liveCadence = ReadAloudText.cadenceOf(pct)
    }

    /** The Cadence drag ended (or a key was let go): [pct] is saved on this device. */
    fun setCadence(context: Context, pct: Float) {
        val app = context.applicationContext
        appContext = app
        prefs(app).edit().putFloat("cadence", ReadAloudText.cadenceOf(pct)).apply()
        liveCadence = null
        _files.value++
    }

    /**
     * A parent's speed on this Kids' Device (cap k16, field "speed", a whole number from 50 to 150 on the wire, mapped
     * into 70 to 130 by [ReadAloudText.kidSpeed]); null when the config has none: 100.
     */
    fun parentSpeed(context: Context, wire: Int?) {
        val v = (wire ?: 100).coerceIn(ReadAloudText.WIRE_MIN, ReadAloudText.WIRE_MAX).toFloat()
        val p = prefs(context)
        if (p.contains("kidSpeed") && p.getFloat("kidSpeed", -1f) == v) return
        p.edit().putFloat("kidSpeed", v).apply()
        applyDeviceRate(context.applicationContext)
    }

    // ---- per-voice tuning (1.43, Labs › Voice tuning): each natural voice's noise scale and width, A until picked.

    /** The preset in use for natural voice [v] ("f" or "m"). */
    fun tune(context: Context, v: String): ReadAloudText.Tune = ReadAloudText.tuneOf(prefs(context).getString("tune_" + ReadAloudText.voiceOf(v), null))

    /** "Use this": [name] is kept on this device for voice [v]; the engine opens with it from the next phrase. */
    fun setTune(context: Context, v: String, name: String) {
        prefs(context).edit().putString("tune_" + ReadAloudText.voiceOf(v), ReadAloudText.tuneOf(name).name).apply()
        _files.value++
    }

    /** Labs: reads [verse] and [means] with voice [v] and preset [t] now, nothing saved (the voice must be downloaded). */
    fun tryTune(context: Context, v: String, t: ReadAloudText.Tune, verse: String, means: String) {
        val app = context.applicationContext
        appContext = app
        stop()
        if (!engineThere || !hasVoice(app, v)) return
        if (io.github.ravenor907.muninn.net.call.CallManager.call.value != null || io.github.ravenor907.muninn.ui.AppLock.isLocked) return
        val parts = ReadAloudText.verse(verse, means, natural = true)
        if (parts.isEmpty()) return
        lastText = null
        heard = false
        _speaking.value = true
        takeFocus(app)
        speakNatural(app, token.get(), voiceDir(app, v), parts, t, ReadAloudText.verse(verse, means, natural = false))
    }

    private fun applyDeviceRate(app: Context) {
        val t = device ?: return
        if (deviceOk) try { t.setSpeechRate(ReadAloudText.deviceRate(speed(app))) } catch (_: Exception) {}
    }

    /** Settings' "Hear a sample": a short line at the speed and with the voice picked now. */
    fun sample(context: Context) {
        val app = context.applicationContext
        appContext = app
        lastText = null
        speak(app, ReadAloudText.SAMPLE + "\n" + ReadAloudText.SAMPLE_PAUSES)
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The voice in use: a parent's pick on a Kids' Device (k15), else the one picked in Settings. */
    fun voice(context: Context): String {
        val p = prefs(context)
        return ReadAloudText.voiceOf(p.getString("kid", null) ?: p.getString("voice", ReadAloudText.FEMALE))
    }

    /** The voice picked in Settings (not a parent's): "f", "m" or "d" (the device's own). */
    fun ownVoice(context: Context): String = ReadAloudText.choiceOf(prefs(context).getString("voice", ReadAloudText.FEMALE))

    fun setVoice(context: Context, v: String) {
        val pick = ReadAloudText.choiceOf(v)
        if (pick == ownVoice(context)) return
        prefs(context).edit().putString("voice", pick).apply()
        switched(context)
    }

    /** k15: the voice a parent picked on this Kids' Device's page, kept until another config carries one. */
    fun parentPicked(context: Context, v: String) {
        if (v !in ReadAloudText.VOICES) return
        val p = prefs(context)
        if (p.getString("kid", null) == v) return
        p.edit().putString("kid", v).apply()
        switched(context)
    }

    /**
     * Left kids' mode: the phone's own pick again. Nothing downloads now and no voice is deleted: the next read decides
     * (a device paired again to a family that picks the same voice keeps it).
     */
    fun parentForgot(context: Context) {
        val app = context.applicationContext
        if (!prefs(app).contains("kid") && !prefs(app).contains("kidSpeed")) return
        prefs(app).edit().remove("kid").remove("kidSpeed").apply()
        stop()
        synth.execute { unload() }
        try { WorkManager.getInstance(app).cancelUniqueWork(WORK) } catch (_: Exception) {}
        WorkState.waiting = false
        _status.value = ""
        refresh(app)
    }

    fun mobileData(context: Context): Boolean = prefs(context).getBoolean("mobile", false)

    fun setMobileData(context: Context, on: Boolean) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean("mobile", on).apply()
        // A download waiting for Wi-Fi goes now (or waits again). WorkManager's own record says whether one waits, so
        // this works after a restart too.
        if (ready(app) || !natural(app) || useDevice(app)) return
        Thread {
            val waiting = try {
                WorkManager.getInstance(app).getWorkInfosForUniqueWork(WORK).get().any { !it.state.isFinished }
            } catch (_: Exception) { WorkState.waiting }
            if (waiting) enqueue(app, ExistingWorkPolicy.REPLACE, asked = prefs(app).getBoolean("asked", false))
        }.apply { isDaemon = true }.start()
    }

    private fun voicesDir(context: Context) = File(context.applicationContext.noBackupFilesDir, "voices")
    private fun voiceDir(context: Context, v: String) = File(voicesDir(context), ReadAloudText.folderFor(v))

    /** The voice in use is downloaded and checked. */
    fun ready(context: Context): Boolean = ReadAloudText.complete(voiceDir(context, voice(context)))

    /** Natural voice [v] ("f" or "m") is downloaded and checked (Settings asks before a switch that downloads). */
    fun hasVoice(context: Context, v: String): Boolean = v in ReadAloudText.VOICES && ReadAloudText.complete(voiceDir(context, v))

    /** Any voice files are kept ("Delete voices" shows). */
    fun hasFiles(context: Context): Boolean = voicesDir(context).list()?.isNotEmpty() == true

    @Volatile private var hashCache: Map<String, String>? = null

    /** The hashes built into this build (assets/voices.sha256), read once. */
    private fun hashes(context: Context): Map<String, String> {
        hashCache?.let { return it }
        val read = try {
            context.applicationContext.assets.open("voices.sha256").bufferedReader(Charsets.UTF_8).use { ReadAloudText.hashes(it.readText()) }
        } catch (_: Exception) { emptyMap() }
        hashCache = read
        return read
    }

    private const val NOT_YET = "Natural voices aren't ready yet. The phone's own voice reads"
    private const val BROKEN = "The natural voice closed Muninn twice, so the phone's own voice reads. Delete voices to try it again"

    /** Settings shows the status as it is now (and the phone's own voice gets another chance). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        appContext = app
        deviceBad = false
        if (useDevice(app)) _status.value = DEVICE_READS
        else if (!natural(app)) _status.value = if (broken) BROKEN else NOT_YET
        else if (ready(app)) _status.value = "Ready"
        else if (_status.value.isEmpty() || _status.value == "Ready" || _status.value == NOT_YET)
            _status.value = "Downloads the first time something is read aloud"
    }

    /** A mark left in the voices folder means the engine stopped Muninn mid-sentence last time. Checked once a run. */
    private fun checkMark(app: Context) {
        if (markChecked) return
        markChecked = true
        val p = prefs(app)
        val mark = File(voicesDir(app), "reading")
        if (mark.exists()) { mark.delete(); p.edit().putInt("cutShort", p.getInt("cutShort", 0) + 1).apply() }
        broken = p.getInt("cutShort", 0) >= 2
    }

    private fun freshStart(app: Context) {
        prefs(app).edit().putInt("cutShort", 0).apply()
        broken = false
    }

    // ---- reading

    /** Reads the plain text of [text] (links say "link", no emoji or marks), in phrases with pauses. Stops whatever was reading first. */
    fun speak(context: Context, text: String) = speakScript(context, text) { natural -> ReadAloudText.phrases(text, natural) }

    /** A Hávamál verse: the verse, 900 ms, "What it means", then the meaning (each line a pause). */
    fun speakVerse(context: Context, verse: String, means: String) =
        speakScript(context, verse + "\u0000" + means) { natural -> ReadAloudText.verse(verse, means, natural) }

    /** Several messages one after the other, 700 ms between them. */
    fun speakMessages(context: Context, texts: List<String>) =
        speakScript(context, texts.joinToString("\u0000")) { natural -> ReadAloudText.messages(texts, natural) }

    private fun speakScript(context: Context, key: String, script: (Boolean) -> List<ReadAloudText.Phrase>) {
        val app = context.applicationContext
        appContext = app
        // The same text pressed again while it's still being made (nothing heard yet): left to finish.
        if (_speaking.value && !heard && key == lastText) return
        stop()
        // Never while a call has the sound, and never on a locked Muninn.
        if (io.github.ravenor907.muninn.net.call.CallManager.call.value != null) return
        if (io.github.ravenor907.muninn.ui.AppLock.isLocked) return
        val naturalNow = !useDevice(app) && natural(app) && ready(app)
        // The respelled names only for the natural voices; the device's own voice gets the words as written.
        val parts = script(naturalNow)
        if (parts.isEmpty()) { io.github.ravenor907.muninn.ui.Ui.say("Nothing to read in this message."); return }
        lastText = key
        heard = false
        val my = token.get()
        // Text with nothing the engine reads (another script only) goes to the phone's own voice.
        if (naturalNow && parts.any { ReadAloudText.forEngine(it.text).isNotEmpty() }) {
            _speaking.value = true
            takeFocus(app)
            val v = voice(app)
            speakNatural(app, my, voiceDir(app, v), parts, tune(app, v), script(false))
            return
        }
        // Not ready yet: the download starts (on the network rules) and the phone's own voice reads meanwhile.
        if (!useDevice(app)) prepare(app)
        speakDevice(app, if (naturalNow) script(false) else parts)
    }

    /** Stops reading, at once. */
    fun stop() {
        token.incrementAndGet()
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        try { device?.stop() } catch (_: Exception) {}
        deviceWaiting = null
        if (_speaking.value) _speaking.value = false
        dropFocus()
    }

    private fun speakNatural(app: Context, my: Long, dir: File, parts: List<ReadAloudText.Phrase>, tune: ReadAloudText.Tune,
                             fallback: List<ReadAloudText.Phrase>) {
        // No call running but the phone still in call mode (left over from a call): put it back, or the voice plays
        // through call processing and sounds robotic.
        try {
            val am = app.getSystemService(android.media.AudioManager::class.java)
            if (am != null && io.github.ravenor907.muninn.net.call.CallMedia.liveCalls.get() <= 0 &&
                am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) am.mode = android.media.AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
        val queue = LinkedBlockingQueue<Pair<FloatArray, Int>>()
        val end = FloatArray(0)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        synth.execute {
            idle?.cancel(false)
            // Marked while the engine works: a mark left at the next start means it stopped Muninn mid-sentence.
            val mark = File(dir.parentFile, "reading")
            try {
                val tts = open(dir, tune)
                try { mark.createNewFile() } catch (_: Exception) {}
                // One phrase at a time, each given whole (so it keeps its rise and fall), the first played as soon as
                // it's made while the next is made. Our own pause (scaled by Cadence) goes after each as silence.
                for (p in parts) {
                    if (token.get() != my) break
                    // Only plain letters, digits and simple punctuation reach the engine (ReadAloudText.forEngine).
                    val clean = ReadAloudText.forEngine(p.text)
                    if (clean.isEmpty()) continue
                    // The speed picked (read for each phrase, so a move of the slider is heard in the next one).
                    val a = tts.generate(clean, 0, ReadAloudText.engineSpeed(speed(app)))
                    if (a.samples.isEmpty()) continue
                    val said = ReadAloudText.trimSilence(a.samples, a.sampleRate)
                    queue.put(ReadAloudText.withPause(said, a.sampleRate, ReadAloudText.pauseMs(p.pause, cadence(app))) to a.sampleRate)
                }
                mark.delete()
                // It finished, so it didn't stop Muninn.
                if (prefs(app).getInt("cutShort", 0) != 0) prefs(app).edit().putInt("cutShort", 0).apply()
            } catch (e: Throwable) {
                mark.delete()
                // The engine or the voice wouldn't start: say so, and the phone's own voice reads this time.
                android.util.Log.w("Muninn", "Read aloud: natural voice failed: ${e.javaClass.simpleName}")
                _status.value = "The natural voice couldn't start: the phone's own voice reads"
                // Not tried again this run (the phone's own voice reads).
                broken = true
                failed.set(true)
            } finally {
                queue.put(end to 0)
                idle = synth.schedule(Runnable { unload() }, 60, TimeUnit.SECONDS)
            }
        }
        player.execute {
            try {
                while (true) {
                    val (samples, rate) = queue.poll(120, TimeUnit.SECONDS) ?: break
                    if (samples === end || token.get() != my) break
                    heard = true
                    play(samples, rate, my)
                }
                // What's still in the speaker plays out before the reading counts as done, then the speaker is let go.
                if (token.get() == my) {
                    Thread.sleep(500)
                    if (token.get() == my) try { track?.stop() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                if (token.get() == my) {
                    dropFocus()
                    // The device's own voice gets the words as written (no respelling).
                    if (failed.get()) android.os.Handler(android.os.Looper.getMainLooper()).post { if (token.get() == my) speakDevice(app, fallback) }
                    else _speaking.value = false
                }
            }
        }
    }

    /** The engine with [dir]'s voice, made once and kept while it's in use. Runs on [synth]. */
    private fun open(dir: File, tune: ReadAloudText.Tune): com.k2fsa.sherpa.onnx.OfflineTts {
        // The engine is made again when the voice or its tuning changes.
        val key = dir.path + "|" + tune.name
        (engine as? com.k2fsa.sherpa.onnx.OfflineTts)?.takeIf { engineDir == key }?.let { return it }
        unload()
        val vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
            model = File(dir, "model.onnx").path, tokens = File(dir, "tokens.txt").path, dataDir = sharedSpeech(dir).path,
            noiseScale = tune.noise, noiseScaleW = tune.width, lengthScale = 1.0f)
        val cfg = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(vits = vits,
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4), debug = false, provider = "cpu"),
            maxNumSentences = 1, silenceScale = 0.2f)
        val tts = com.k2fsa.sherpa.onnx.OfflineTts(config = cfg)
        engine = tts
        engineDir = key
        return tts
    }

    /**
     * The speech rules' folder for every voice. The engine keeps the first folder it's given for as long as the app runs, so
     * it must never be one voice's own: with that voice's folder gone or changed, the next voice read words as nonsense. The
     * folder is the same in every voice; one copy stays beside them, outside any voice's folder.
     */
    private fun sharedSpeech(dir: File): File {
        val shared = File(dir.parentFile, ReadAloudText.SPEECH_FOLDER)
        if (!File(shared, "phontab").isFile) synchronized(filesLock) {
            val fresh = File(dir.parentFile, ReadAloudText.SPEECH_FOLDER + ".new")
            fresh.deleteRecursively()
            File(dir, "espeak-ng-data").copyRecursively(fresh, overwrite = true)
            shared.deleteRecursively()
            fresh.renameTo(shared)
        }
        return shared
    }

    /** Lets go of the model (and its memory), and of the speaker. Runs on [synth]. */
    private fun unload() {
        val e = engine as? com.k2fsa.sherpa.onnx.OfflineTts
        engine = null
        engineDir = ""
        try { e?.release() } catch (_: Throwable) {}
        player.execute { if (!_speaking.value) { try { track?.release() } catch (_: Exception) {}; track = null } }
    }

    /** Plays one piece, blocking until it's queued in the speaker. Runs on [player]. */
    private fun play(samples: FloatArray, rate: Int, my: Long) {
        var t = track
        if (t == null || t.sampleRate != rate) {
            try { t?.release() } catch (_: Exception) {}
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, rate * 4 / 2))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = t
        }
        if (token.get() != my) return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        var at = 0
        while (at < samples.size && token.get() == my) {
            val n = t.write(samples, at, minOf(4096, samples.size - at), AudioTrack.WRITE_BLOCKING)
            if (n <= 0) break
            at += n
        }
    }

    private fun takeFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            // A call (or anything else that takes the sound) stops the reading.
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop() }
            .build()
        focus = req
        try { am.requestAudioFocus(req) } catch (_: Exception) {}
    }

    private fun dropFocus() {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        focus?.let { req -> focus = null; try { am?.abandonAudioFocusRequest(req) } catch (_: Exception) {} }
        focusDevice?.let { req -> focusDevice = null; try { am?.abandonAudioFocusRequest(req) } catch (_: Exception) {} }
    }

    /** The phone's own voice holds the sound too, so a call (Muninn's or the phone's) stops it as it does the natural one. */
    private fun takeDeviceFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop() }
            .build()
        focusDevice = req
        try { am.requestAudioFocus(req) } catch (_: Exception) {}
    }

    // ---- the phone's own voice

    private fun speakDevice(app: Context, parts: List<ReadAloudText.Phrase>) {
        val t = device
        if (t == null) {
            deviceWaiting = parts
            _speaking.value = true
            device = try { TextToSpeech(app) { st -> deviceStarted(app, st) } } catch (_: Exception) { null }
            if (device == null) { _speaking.value = false; deviceBad = true; io.github.ravenor907.muninn.ui.Ui.say(cantYet(app), true) }
            return
        }
        if (!deviceOk) {
            if (deviceWaiting != null) { deviceWaiting = parts; return }
            _speaking.value = false
            io.github.ravenor907.muninn.ui.Ui.say(cantYet(app), true)
            return
        }
        sayDevice(app, t, parts)
    }

    /** Nothing reads right now: the natural voice is on its way only when it can download (its line is there). */
    private fun cantYet(app: Context): String = if (natural(app) && !useDevice(app)) WAIT else NONE

    private fun deviceStarted(app: Context, st: Int) {
        val t = device ?: return
        deviceOk = st == TextToSpeech.SUCCESS && pickOffline(t)
        val waiting = deviceWaiting
        deviceWaiting = null
        if (!deviceOk) {
            // Let go of it: the next read tries again (after an offline voice is installed, say).
            try { t.shutdown() } catch (_: Exception) {}
            device = null
            deviceBad = true
        }
        if (waiting == null) return
        if (deviceOk) sayDevice(app, t, waiting)
        else {
            _speaking.value = false
            io.github.ravenor907.muninn.ui.Ui.say(cantYet(app), true)
        }
    }

    /** Only a voice that works offline: some phones' voices send the text to a server. False when there's none. */
    private fun pickOffline(t: TextToSpeech): Boolean = try {
        val offline = t.voices.orEmpty().filter { !it.isNetworkConnectionRequired && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) }
        val pick = offline.filter { it.locale.language == "en" }.let { en -> en.firstOrNull { it.locale.country == "GB" } ?: en.firstOrNull() }
            ?: offline.firstOrNull { it.locale.language == Locale.getDefault().language }
        if (pick != null) { t.setVoice(pick); true } else false
    } catch (_: Exception) { false }

    private fun sayDevice(app: Context, t: TextToSpeech, parts: List<ReadAloudText.Phrase>) {
        val my = token.get()
        _speaking.value = true
        takeDeviceFocus(app)
        try { t.setSpeechRate(ReadAloudText.deviceRate(speed(app))) } catch (_: Exception) {}
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { heard = true }
            override fun onDone(id: String?) { if (id == "end-$my" && token.get() == my) { _speaking.value = false; dropFocus() } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (token.get() == my) { _speaking.value = false; dropFocus() } }
        })
        // Each phrase, then our own pause (scaled by Cadence) as the engine's silence.
        val cadence = cadence(app)
        parts.forEachIndexed { i, p ->
            try { t.speak(p.text, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.size - 1) "end-$my" else "p$i-$my") } catch (_: Exception) {}
            val ms = ReadAloudText.pauseMs(p.pause, cadence)
            if (i < parts.size - 1 && ms > 0) try { t.playSilentUtterance(ms.toLong(), TextToSpeech.QUEUE_ADD, "s$i-$my") } catch (_: Exception) {}
        }
    }

    /** The speech engines are let go (left kids' mode). */
    fun release() {
        stop()
        try { device?.shutdown() } catch (_: Exception) {}
        device = null; deviceOk = false
        synth.execute { unload() }
        player.execute { try { track?.release() } catch (_: Exception) {}; track = null }
    }

    const val NONE = "This device can't read out loud yet"
    /** Settings' line with Device picked. */
    const val DEVICE_READS = "The phone's own voice reads"
    const val WAIT = "The natural voice is on its way. Read aloud works once it's in"

    // ---- the download

    /**
     * Starts the voice in use's download now, on the network rules (Settings and the first read). Under Ultra privacy a
     * read doesn't start it: 65 MB through Tor is slow, so it goes only when asked in Settings ([asked]).
     */
    fun prepare(context: Context, asked: Boolean = false) {
        val app = context.applicationContext
        appContext = app
        if (useDevice(app)) { _status.value = DEVICE_READS; return }
        if (ready(app)) { _status.value = "Ready"; return }
        if (!natural(app)) { _status.value = if (broken) BROKEN else NOT_YET; return }
        if (asked) prefs(app).edit().putBoolean("asked", true).apply()
        val go = asked || prefs(app).getBoolean("asked", false)
        if (UltraPrivacy.on && !go) {
            _status.value = "With Ultra privacy on, the voice (about 65 MB) downloads through Tor only when you tap Download now"
            return
        }
        enqueue(app, if (asked) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, go)
    }

    private fun enqueue(app: Context, policy: ExistingWorkPolicy, asked: Boolean = false) {
        val net = if (mobileData(app)) NetworkType.CONNECTED else NetworkType.UNMETERED
        val req = OneTimeWorkRequestBuilder<VoiceWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).setRequiresStorageNotLow(true).build())
            .setInputData(workDataOf("voice" to voice(app), "asked" to asked))
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        try {
            WorkManager.getInstance(app).enqueueUniqueWork(WORK, policy, req)
            WorkState.waiting = true
            if (_status.value.isEmpty() || !_status.value.startsWith("Downloading"))
                _status.value = if (net == NetworkType.UNMETERED && unmeteredNow(app) != true) "Waits for Wi-Fi (or allow mobile data)" else "Downloading the natural voice"
        } catch (_: Exception) {}
    }

    private fun unmeteredNow(app: Context): Boolean? = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { null }

    /** The voice changed (Settings or a parent): the new one downloads; the old one goes once it's in. */
    private fun switched(context: Context) {
        val app = context.applicationContext
        stop()
        synth.execute { unload() }
        if (useDevice(app)) {
            // The device's own voice: nothing downloads (the voice kept stays until Delete voices).
            try { WorkManager.getInstance(app).cancelUniqueWork(WORK) } catch (_: Exception) {}
            WorkState.waiting = false
            _status.value = DEVICE_READS
            _files.value++
        }
        else if (ready(app)) { cleanOther(app); _status.value = "Ready"; _files.value++ }
        else if (!natural(app)) {
            try { WorkManager.getInstance(app).cancelUniqueWork(WORK) } catch (_: Exception) {}
            _status.value = if (broken) BROKEN else NOT_YET
        }
        else if (hasFiles(app)) enqueue(app, ExistingWorkPolicy.REPLACE, prefs(app).getBoolean("asked", false))
        else {
            try { WorkManager.getInstance(app).cancelUniqueWork(WORK) } catch (_: Exception) {}
            _status.value = "Downloads the first time something is read aloud"
        }
    }

    /**
     * Only the one natural voice stays (the retired Male voice and leftovers from another voice release go). A download's
     * part and its unpack folder stay.
     */
    private fun cleanOther(app: Context) = synchronized(filesLock) {
        val keep = listOf(ReadAloudText.folderFor(ReadAloudText.FEMALE), ReadAloudText.SPEECH_FOLDER)
        voicesDir(app).listFiles()?.forEach { f ->
            if (f.name !in keep && f.name != "reading" && !f.name.endsWith(".part") && !f.name.endsWith(".new")) f.deleteRecursively()
        }
    }

    /** Deletes every downloaded voice; the phone's own voice reads until one downloads again. */
    fun deleteVoices(context: Context) {
        val app = context.applicationContext
        stop()
        try { WorkManager.getInstance(app).cancelUniqueWork(WORK) } catch (_: Exception) {}
        WorkState.waiting = false
        synth.execute { unload(); synchronized(filesLock) { voicesDir(app).deleteRecursively() }; freshStart(app); _files.value++ }
        _status.value = if (useDevice(app)) DEVICE_READS else if (engineThere && hashes(app).containsKey(ReadAloudText.fileFor(voice(app)))) "Deleted. Downloads again the next time something is read aloud" else NOT_YET
    }

    private object WorkState { @Volatile var waiting = false }

    /**
     * The download, in the background (WorkManager keeps it through restarts and waits for the network asked for): the
     * part kept is picked up where it stopped, the whole file is checked against its SHA-256, unpacked beside its place
     * and put there in one step.
     */
    class VoiceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val app = applicationContext
            appContext = app
            val v = ReadAloudText.voiceOf(inputData.getString("voice"))
            if (v != voice(app) || useDevice(app)) return@withContext Result.success()   // switched meanwhile; that one has its own run
            if (ready(app)) { WorkState.waiting = false; cleanOther(app); _status.value = "Ready"; _files.value++; return@withContext Result.success() }
            val file = ReadAloudText.fileFor(v)
            val want = hashes(app)[file] ?: return@withContext Result.success()
            // Ultra privacy: only when asked in Settings (65 MB through Tor is slow), and only through Tor (Net.client goes
            // through it while it's on); until Tor is ready, wait and say so.
            if (UltraPrivacy.on && !inputData.getBoolean("asked", false) && !prefs(app).getBoolean("asked", false)) {
                WorkState.waiting = false
                _status.value = "With Ultra privacy on, the voice (about 65 MB) downloads through Tor only when you tap Download now"
                return@withContext Result.success()
            }
            if (UltraPrivacy.on && UltraPrivacy.state.value != UltraPrivacy.Tor.READY) {
                _status.value = "Waits for Ultra privacy's Tor connection. The phone's own voice reads meanwhile"
                return@withContext Result.retry()
            }
            val dir = voicesDir(app).apply { mkdirs() }
            if (dir.usableSpace < ROOM) { _status.value = "Not enough free space for the natural voice (about 300 MB needed)"; return@withContext Result.retry() }
            val part = File(dir, "$file.part")
            val sha = MessageDigest.getInstance("SHA-256")
            var got = 0L
            if (part.isFile && part.length() in 1 until ReadAloudText.MAX_DOWNLOAD) {
                part.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; sha.update(b, 0, n) } }
                got = part.length()
            } else part.delete()
            _status.value = "Downloading the natural voice"
            try {
                val req = Request.Builder().url(ReadAloudText.DOWNLOAD + file).header("User-Agent", "Mozilla/5.0")
                    .apply { if (got > 0) header("Range", "bytes=$got-") }.build()
                Net.client.newBuilder().readTimeout(2, TimeUnit.MINUTES).build().newCall(req).execute().use { r ->
                    if (r.code == 404) { part.delete(); _status.value = "The natural voice isn't published yet: the phone's own voice reads"; return@withContext Result.retry() }
                    // 416: the part kept was already the whole file (Muninn stopped before checking it): checked below.
                    if (r.code == 416 && got > 0) return@use
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    val append = r.code == 206 && got > 0
                    if (!append) { got = 0; sha.reset() }
                    val total = (r.body?.contentLength() ?: -1L).let { if (it > 0) it + got else -1L }
                    val body = r.body ?: throw java.io.IOException("no body")
                    java.io.FileOutputStream(part, append).use { out ->
                        val s = body.byteStream()
                        val b = ByteArray(1 shl 16)
                        var shown = -1
                        while (true) {
                            if (isStopped) return@withContext Result.retry()
                            val n = s.read(b)
                            if (n < 0) break
                            got += n
                            if (got > ReadAloudText.MAX_DOWNLOAD) { part.delete(); throw java.io.IOException("too big") }
                            out.write(b, 0, n)
                            sha.update(b, 0, n)
                            if (total > 0) {
                                val pc = (got * 100 / total).toInt().coerceIn(0, 100)
                                if (pc != shown) { shown = pc; _status.value = "Downloading the natural voice, $pc%" }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _status.value = "The natural voice couldn't download. It tries again later"
                return@withContext Result.retry()
            }
            val sum = sha.digest().joinToString("") { "%02x".format(it) }
            if (sum != want) {
                part.delete()
                _status.value = "The natural voice didn't match its check, so it wasn't used. It tries again later"
                return@withContext Result.retry()
            }
            // Unpacked and put in place under the files lock (Delete voices and another voice's clean-up wait), and only
            // while this is still the voice wanted: a quick switch never has two workers in the same folder.
            val placed = synchronized(filesLock) {
                if (isStopped || v != voice(app) || !part.isFile) return@synchronized null
                val final = voiceDir(app, v)
                val fresh = File(dir, final.name + ".new")
                fresh.deleteRecursively()
                val why = try { part.inputStream().buffered().use { ReadAloudText.unpack(it, fresh) } } catch (e: Exception) { "can't read it" }
                if (why != null || !ReadAloudText.complete(fresh)) {
                    fresh.deleteRecursively(); part.delete()
                    return@synchronized "The natural voice couldn't be unpacked (${why ?: "parts missing"}). It tries again later"
                }
                if (isStopped || v != voice(app)) { fresh.deleteRecursively(); return@synchronized null }
                // The old folder moves aside first, so a crash in between never leaves neither.
                val old = File(dir, final.name + ".old")
                old.deleteRecursively()
                if (final.exists() && !final.renameTo(old)) final.deleteRecursively()
                if (!fresh.renameTo(final)) {
                    fresh.deleteRecursively()
                    if (old.exists() && !final.exists()) old.renameTo(final)
                    return@synchronized "Can't save the natural voice"
                }
                old.deleteRecursively()
                part.delete()
                ""
            }
            when {
                placed == null -> return@withContext Result.success()   // switched or deleted meanwhile; that has its own run
                !placed.isNullOrEmpty() -> { _status.value = placed; return@withContext Result.retry() }
            }
            WorkState.waiting = false
            freshStart(app)
            cleanOther(app)
            _status.value = "Ready"
            _files.value++
            Result.success()
        }
    }
}
