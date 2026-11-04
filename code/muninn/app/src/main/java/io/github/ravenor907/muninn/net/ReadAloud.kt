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
    /** Bumped when a voice is downloaded or deleted, so Settings reads [ready] and [hasFiles] again. */
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
    @Volatile private var deviceWaiting: List<String>? = null

    /** The speech engine is in this build (it is in every release; a missing one falls back to the phone's own voice). */
    private val engineThere: Boolean by lazy {
        try { Class.forName("com.k2fsa.sherpa.onnx.OfflineTts", false, ReadAloud::class.java.classLoader); true } catch (_: Throwable) { false }
    }

    /** Something can read now, or will once the natural voice is in. */
    val available: Boolean get() = engineThere || deviceOk

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The voice in use: a parent's pick on a Kids' Device (k15), else the one picked in Settings. */
    fun voice(context: Context): String {
        val p = prefs(context)
        return ReadAloudText.voiceOf(p.getString("kid", null) ?: p.getString("voice", ReadAloudText.FEMALE))
    }

    /** The voice picked in Settings (not a parent's). */
    fun ownVoice(context: Context): String = ReadAloudText.voiceOf(prefs(context).getString("voice", ReadAloudText.FEMALE))

    fun setVoice(context: Context, v: String) {
        val pick = ReadAloudText.voiceOf(v)
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

    /** Left kids' mode: the phone's own pick again. */
    fun parentForgot(context: Context) {
        if (prefs(context).contains("kid")) { prefs(context).edit().remove("kid").apply(); switched(context) }
    }

    fun mobileData(context: Context): Boolean = prefs(context).getBoolean("mobile", false)

    fun setMobileData(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("mobile", on).apply()
        // A download waiting for Wi-Fi goes now (or waits again).
        if (!ready(context) && WorkState.waiting) enqueue(context, ExistingWorkPolicy.REPLACE)
    }

    private fun voicesDir(context: Context) = File(context.applicationContext.noBackupFilesDir, "voices")
    private fun voiceDir(context: Context, v: String) = File(voicesDir(context), ReadAloudText.folderFor(v))

    /** The voice in use is downloaded and checked. */
    fun ready(context: Context): Boolean = ReadAloudText.complete(voiceDir(context, voice(context)))

    /** Any voice files are kept ("Delete voices" shows). */
    fun hasFiles(context: Context): Boolean = voicesDir(context).list()?.isNotEmpty() == true

    /** The hashes built into this build (assets/voices.sha256). */
    private fun hashes(context: Context): Map<String, String> = try {
        context.assets.open("voices.sha256").bufferedReader(Charsets.UTF_8).use { ReadAloudText.hashes(it.readText()) }
    } catch (_: Exception) { emptyMap() }

    /** Settings shows the status as it is now. */
    fun refresh(context: Context) {
        appContext = context.applicationContext
        if (ready(context)) _status.value = "Ready"
        else if (_status.value.isEmpty() || _status.value == "Ready")
            _status.value = if (hashes(context).containsKey(ReadAloudText.fileFor(voice(context)))) "Downloads the first time something is read aloud"
                else "Not available yet: the phone's own voice reads"
    }

    // ---- reading

    /** Reads the plain text of [text] (links say "link", no emoji or marks). Stops whatever was reading first. */
    fun speak(context: Context, text: String) {
        val app = context.applicationContext
        appContext = app
        stop()
        // Never while a call has the sound, and never on a locked Muninn.
        if (io.github.ravenor907.muninn.net.call.CallManager.call.value != null) return
        if (io.github.ravenor907.muninn.ui.AppLock.isLocked) return
        val parts = ReadAloudText.pieces(ReadAloudText.plain(text))
        if (parts.isEmpty()) return
        val my = token.get()
        if (engineThere && ready(app)) {
            _speaking.value = true
            takeFocus(app)
            val dir = voiceDir(app, voice(app))
            speakNatural(app, my, dir, parts)
            return
        }
        // Not ready yet: the download starts (on the network rules) and the phone's own voice reads meanwhile.
        prepare(app)
        speakDevice(app, parts)
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

    private fun speakNatural(app: Context, my: Long, dir: File, parts: List<String>) {
        val queue = LinkedBlockingQueue<Pair<FloatArray, Int>>()
        val end = FloatArray(0)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        synth.execute {
            idle?.cancel(false)
            try {
                val tts = open(dir)
                for (p in parts) {
                    if (token.get() != my) break
                    val a = tts.generate(p, 0, 1.0f)
                    if (a.samples.isNotEmpty()) queue.put(a.samples to a.sampleRate)
                }
            } catch (e: Throwable) {
                // The engine or the voice wouldn't start: say so, and the phone's own voice reads this time.
                android.util.Log.w("Muninn", "Read aloud: natural voice failed: ${e.javaClass.simpleName}")
                _status.value = "The natural voice couldn't start: the phone's own voice reads"
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
                    play(samples, rate, my)
                }
                // What's still in the speaker plays out before the reading counts as done.
                if (token.get() == my) Thread.sleep(500)
            } catch (_: Exception) {
            } finally {
                if (token.get() == my) {
                    dropFocus()
                    if (failed.get()) android.os.Handler(android.os.Looper.getMainLooper()).post { if (token.get() == my) speakDevice(app, parts) }
                    else _speaking.value = false
                }
            }
        }
    }

    /** The engine with [dir]'s voice, made once and kept while it's in use. Runs on [synth]. */
    private fun open(dir: File): com.k2fsa.sherpa.onnx.OfflineTts {
        (engine as? com.k2fsa.sherpa.onnx.OfflineTts)?.takeIf { engineDir == dir.path }?.let { return it }
        unload()
        val vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
            model = File(dir, "model.onnx").path, tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path,
            noiseScale = 0.667f, noiseScaleW = 0.8f, lengthScale = 1.0f)
        val cfg = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
            model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(vits = vits,
                numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4), debug = false, provider = "cpu"),
            maxNumSentences = 1, silenceScale = 0.2f)
        val tts = com.k2fsa.sherpa.onnx.OfflineTts(config = cfg)
        engine = tts
        engineDir = dir.path
        return tts
    }

    /** Lets go of the model (and its memory). Runs on [synth]. */
    private fun unload() {
        val e = engine as? com.k2fsa.sherpa.onnx.OfflineTts
        engine = null
        engineDir = ""
        try { e?.release() } catch (_: Throwable) {}
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
        val req = focus ?: return
        focus = null
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try { am.abandonAudioFocusRequest(req) } catch (_: Exception) {}
    }

    // ---- the phone's own voice

    private fun speakDevice(app: Context, parts: List<String>) {
        val t = device
        if (t == null) {
            deviceWaiting = parts
            _speaking.value = true
            device = try { TextToSpeech(app) { st -> deviceStarted(app, st) } } catch (_: Exception) { null }
            if (device == null) { _speaking.value = false; io.github.ravenor907.muninn.ui.Ui.say(NONE, true) }
            return
        }
        if (!deviceOk) {
            if (deviceWaiting != null) { deviceWaiting = parts; return }
            _speaking.value = false
            io.github.ravenor907.muninn.ui.Ui.say(if (engineThere) WAIT else NONE, true)
            return
        }
        sayDevice(t, parts)
    }

    private fun deviceStarted(app: Context, st: Int) {
        val t = device ?: return
        deviceOk = st == TextToSpeech.SUCCESS && pickOffline(t)
        val waiting = deviceWaiting
        deviceWaiting = null
        if (waiting == null) return
        if (deviceOk) sayDevice(t, waiting)
        else {
            _speaking.value = false
            io.github.ravenor907.muninn.ui.Ui.say(if (engineThere) WAIT else NONE, true)
        }
    }

    /** Only a voice that works offline: some phones' voices send the text to a server. False when there's none. */
    private fun pickOffline(t: TextToSpeech): Boolean = try {
        val offline = t.voices.orEmpty().filter { !it.isNetworkConnectionRequired && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) }
        val pick = offline.filter { it.locale.language == "en" }.let { en -> en.firstOrNull { it.locale.country == "GB" } ?: en.firstOrNull() }
            ?: offline.firstOrNull { it.locale.language == Locale.getDefault().language }
        if (pick != null) { t.setVoice(pick); true } else false
    } catch (_: Exception) { false }

    private fun sayDevice(t: TextToSpeech, parts: List<String>) {
        val my = token.get()
        _speaking.value = true
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == "end-$my" && token.get() == my) _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { if (token.get() == my) _speaking.value = false }
        })
        parts.forEachIndexed { i, p ->
            try { t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.size - 1) "end-$my" else "p$i-$my") } catch (_: Exception) {}
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
    const val WAIT = "The natural voice is on its way. Read aloud works once it's in"

    // ---- the download

    /** Starts the voice in use's download now, on the network rules (Settings and the first read). */
    fun prepare(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (ready(app)) { _status.value = "Ready"; return }
        if (!hashes(app).containsKey(ReadAloudText.fileFor(voice(app)))) { _status.value = "Not available yet: the phone's own voice reads"; return }
        enqueue(app, ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(app: Context, policy: ExistingWorkPolicy) {
        val net = if (mobileData(app)) NetworkType.CONNECTED else NetworkType.UNMETERED
        val req = OneTimeWorkRequestBuilder<VoiceWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).setRequiresStorageNotLow(true).build())
            .setInputData(workDataOf("voice" to voice(app)))
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
        if (ready(app)) { cleanOther(app); _status.value = "Ready"; _files.value++ }
        else if (hasFiles(app)) enqueue(app, ExistingWorkPolicy.REPLACE)
        else {
            try { WorkManager.getInstance(app).cancelUniqueWork(WORK) } catch (_: Exception) {}
            _status.value = "Downloads the first time something is read aloud"
        }
    }

    /** Only the voice in use is kept: the other one (and leftovers) go once this one is in. */
    private fun cleanOther(app: Context) {
        val keep = ReadAloudText.folderFor(voice(app))
        voicesDir(app).listFiles()?.forEach { f -> if (f.name != keep && !f.name.endsWith(".part")) f.deleteRecursively() }
    }

    /** Deletes every downloaded voice; the phone's own voice reads until one downloads again. */
    fun deleteVoices(context: Context) {
        val app = context.applicationContext
        stop()
        try { WorkManager.getInstance(app).cancelUniqueWork(WORK) } catch (_: Exception) {}
        WorkState.waiting = false
        synth.execute { unload(); voicesDir(app).deleteRecursively(); _files.value++ }
        _status.value = "Deleted. Downloads again the next time something is read aloud"
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
            if (v != voice(app)) return@withContext Result.success()   // switched meanwhile; that one has its own run
            if (ready(app)) { WorkState.waiting = false; cleanOther(app); _status.value = "Ready"; _files.value++; return@withContext Result.success() }
            val file = ReadAloudText.fileFor(v)
            val want = hashes(app)[file] ?: return@withContext Result.success()
            // Ultra privacy: only through Tor (Net.client goes through it while it's on); until Tor is ready, wait and say so.
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
            val final = voiceDir(app, v)
            val fresh = File(dir, final.name + ".new")
            fresh.deleteRecursively()
            val why = try { part.inputStream().buffered().use { ReadAloudText.unpack(it, fresh) } } catch (e: Exception) { "can't read it" }
            if (why != null || !ReadAloudText.complete(fresh)) {
                fresh.deleteRecursively(); part.delete()
                _status.value = "The natural voice couldn't be unpacked (${why ?: "parts missing"}). It tries again later"
                return@withContext Result.retry()
            }
            final.deleteRecursively()
            if (!fresh.renameTo(final)) { fresh.deleteRecursively(); _status.value = "Can't save the natural voice"; return@withContext Result.retry() }
            part.delete()
            WorkState.waiting = false
            cleanOther(app)
            _status.value = "Ready"
            _files.value++
            Result.success()
        }
    }
}
