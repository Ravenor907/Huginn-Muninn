package io.github.ravenor907.muninn.net.call

import android.content.Context
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Net
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Calls for the whole app: at most one 1:1 call at a time, its state for the screens, the call history,
 * and the incoming ring. [init] once the vault is open (Muninn.start), with Chat as the [CallSignal].
 */
object CallManager {
    /** What the call screen shows. Never an address. */
    data class Ui(
        val callId: String, val peerKey: String, val name: String, val outgoing: Boolean, val phase: CallSession.Phase,
        val connectedAt: Long, val muted: Boolean, val speaker: Boolean, val peerMuted: Boolean, val peerSpeaking: Boolean,
        val relay: Boolean, val wifi: Boolean, val bluetooth: Boolean, val endWhy: String, val note: String,
        /** The direct internet path (both trust each other, 5.8.2): tried, and carrying the call now. */
        val directTried: Boolean = false, val direct: Boolean = false,
        /** Video can go both ways (5.8.5); the caller started it as a video call (shown while it rings). */
        val video: Boolean = false, val videoCall: Boolean = false,
    )

    /** One call, shown in its chat. [result]: "answered", "missed", "declined", "noanswer", "busy", "failed". */
    data class Log(val id: String, val peerKey: String, val name: String, val outgoing: Boolean, val at: Long, val seconds: Long, val result: String)

    private val _call = MutableStateFlow<Ui?>(null)
    /** The call going on (or just ended, for a moment), or null. */
    val call: StateFlow<Ui?> = _call.asStateFlow()
    private val _history = MutableStateFlow<List<Log>>(emptyList())
    val history: StateFlow<List<Log>> = _history.asStateFlow()

    /** A Bluetooth LE / Nearby link to carry media close by. Optional. */
    @Volatile var bluetooth: CallBluetooth? = null

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-call").apply { isDaemon = true } }
    private val work = Executors.newCachedThreadPool { r -> Thread(r, "muninn-call-work").apply { isDaemon = true } }
    @Volatile private var app: Muninn? = null
    @Volatile private var signal: CallSignal? = null
    @Volatile private var session: CallSession? = null
    /** Call ids already seen, so a late retry of an ended call doesn't ring again. */
    private val seen = LinkedHashMap<String, Long>()

    /**
     * Cap b1: a call ringing while Muninn is locked, from a ring envelope (section 23). Nothing is known but its id and
     * time: no name, no key. Only while it rings; what the person did (answer or decline) is kept in [lockedChoice] by
     * call id, and the offer itself, kept sealed while locked, is handled after unlocking and follows that.
     */
    private class LockedRing(val app: Muninn, val callId: String, val at: Long)
    @Volatile private var lockedRing: LockedRing? = null
    private val lockedSeen = LinkedHashMap<String, Long>()
    /** Call id to (answered: true, declined: false; when), for the offer to find after unlocking. */
    private val lockedChoice = LinkedHashMap<String, Pair<Boolean, Long>>()
    /** How long a choice made on the locked ringing screen waits for its offer. */
    private const val CHOICE_MS = CallLogic.RING_MS + 3 * 60_000L

    private fun choose(r: LockedRing, answered: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lockedChoice) {
            lockedChoice.entries.removeAll { now - it.value.second > CHOICE_MS }
            lockedChoice[r.callId] = answered to now
        }
        if (lockedRing === r) lockedRing = null
    }

    /** A ring envelope opened while Muninn is locked: rings as "Incoming call", with no name. */
    fun ringLocked(a: Muninn, callId: String, ts: Long) {
        exec.execute {
            val now = System.currentTimeMillis()
            // Unlocked: the offer itself rings, with the name.
            if (!a.vault.locked.value) return@execute
            // Only a call id rings once; another call while locked rings too (the newest one shows).
            if (session != null || RoomManager.busy) return@execute
            if (!io.github.ravenor907.muninn.net.RingWire.ringFresh(ts, now)) return@execute
            synchronized(lockedSeen) {
                lockedSeen.entries.removeAll { now - it.value > 10 * 60_000 }
                if (lockedSeen.containsKey(callId)) return@execute
                lockedSeen[callId] = now
            }
            // Extreme battery saver: calls ring only while Muninn is open.
            if (a.quietUntilOpen()) return@execute
            val r = LockedRing(a, callId, now)
            lockedRing = r
            _call.value = Ui(callId, "", LOCKED_NAME, false, CallSession.Phase.INCOMING, 0, false, false, false, false, false, false, false, "",
                "Unlock Muninn to answer")
            Ringer.ring(a, callId, "", "")
            // Rings as long as any call does; then quiet, and the next call may ring.
            exec.schedule(Runnable {
                if (lockedRing !== r) return@Runnable
                lockedRing = null
                Ringer.stop(a)
                if (_call.value?.callId == callId && session == null) _call.value = null
            }, CallLogic.RING_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Answered while locked: the lock screen opens, and the call is answered once the offer is opened after unlocking. */
    private fun answerLocked(r: LockedRing) {
        choose(r, true)
        Ringer.stop(r.app)
        if (_call.value?.callId == r.callId) _call.value = null
        try {
            r.app.startActivity(android.content.Intent(r.app, io.github.ravenor907.muninn.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {}
    }

    /** Declined while locked: quiet now, and a decline goes back once the offer is opened after unlocking. */
    private fun declineLocked(r: LockedRing) {
        choose(r, false)
        Ringer.stop(r.app)
        if (_call.value?.callId == r.callId) _call.value = null
    }
    private const val MAX_HISTORY = 200
    private const val PREFS = "muninn"
    /** What the ringing screen shows for a call that rang while Muninn was locked: no name is known then. */
    const val LOCKED_NAME = "Muninn call"

    fun init(app: Muninn, signal: CallSignal) {
        this.app = app; this.signal = signal
        exec.execute { _history.value = load(app); loadTrust(app) }
        work.execute { Vp8.available }   // looks the codecs up once, off the main thread
    }

    // ------------------------------------------------------------------ trusted contacts (5.8.1)

    private val trustOn = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val trustOff = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val _trust = MutableStateFlow(0)
    /** Bumps when a "Direct calls" switch changes, so screens read [trusted] again. */
    val trustChanged: StateFlow<Int> = _trust.asStateFlow()

    private fun inFamily(k: String) = try {
        app?.chat?.folders?.of(k)?.any { it.name.trim().equals("Family", ignoreCase = true) } == true
    } catch (e: Exception) { false }

    /**
     * Trusted contacts are called directly over the internet when both trust each other (saves the relay's data);
     * everyone else only through the relay, so they never learn this phone's IP address. Default: everyone in the
     * Family folder; the per-contact switch overrides it, on or off.
     */
    fun trusted(k: String) = when { k in trustOn -> true; k in trustOff -> false; else -> inFamily(k) }

    /** "On (Family folder)", "On" or "Off", for the contact menu. */
    fun trustText(k: String) = when { k in trustOn -> "On"; k in trustOff -> "Off"; inFamily(k) -> "On (Family folder)"; else -> "Off" }

    fun setTrusted(k: String, on: Boolean) {
        trustOn.remove(k); trustOff.remove(k)
        (if (on) trustOn else trustOff).add(k)
        _trust.value = _trust.value + 1
        // Off mid-call: this phone's addresses stop going out and the direct path stops at once (5.8.1).
        if (!on) exec.execute { session?.takeIf { it.peerKey == k }?.untrust() }
        if (!on) RoomManager.untrust(k)
        val a = app ?: return
        exec.execute { saveTrust(a) }
    }

    private fun trustFile(c: Context) = File(c.filesDir, "calltrust.json")

    private fun loadTrust(a: Muninn) = try {
        val o = JSONObject(a.vault.read(trustFile(a)) ?: "{}")
        o.optJSONArray("on")?.let { arr -> for (i in 0 until arr.length()) trustOn += arr.getString(i) }
        o.optJSONArray("off")?.let { arr -> for (i in 0 until arr.length()) arr.getString(i).let { if (it !in trustOn) trustOff += it } }
        _trust.value = _trust.value + 1
    } catch (_: Exception) {}

    private fun saveTrust(a: Muninn) {
        try { a.vault.write(trustFile(a), JSONObject().put("on", JSONArray(trustOn.toList())).put("off", JSONArray(trustOff.toList())).toString()) } catch (_: Exception) {}
    }

    /**
     * Can [peerKey] get calls? Apps with "c1" (1.3), and anyone whose app hasn't said yet (counted as able; an
     * app without caps just doesn't answer). Someone met close by only while on the same Wi-Fi or close by.
     */
    fun canCall(peerKey: String): Boolean = app?.let { a -> try { a.chat.mayCall(peerKey) } catch (e: Exception) { false } } == true

    /** Settings › "Internet calls" (default on). Off: only Wi-Fi and Bluetooth, the relay is never asked. */
    fun internetCalls(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("internetCalls", true)
    fun setInternetCalls(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("internetCalls", on).apply()

    /**
     * Settings › "Noise removal" (default on). Off: Android's noise suppressor is switched off on the call's
     * microphone, where the phone lets an app do that. Some phones suppress hard enough to make a voice
     * quiet. The echo canceller stays on either way, and a phone may still filter in its own hardware.
     */
    fun noiseRemoval(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("noiseRemoval", true)
    fun setNoiseRemoval(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("noiseRemoval", on).apply()

    // ------------------------------------------------------------------ actions (from the screens)

    /** Can [peerKey] get video calls? Their app speaks c2 (5.8) and this phone has VP8. */
    /** This phone sends and shows video: it has VP8 and isn't a Kids' TV (the TV call screen shows no video). */
    fun videoHere(): Boolean = Vp8.available && !io.github.ravenor907.muninn.tv.TvMic.active

    fun canVideo(peerKey: String): Boolean = videoHere() && app?.let { a -> try { a.chat.mayVideo(peerKey) } catch (e: Exception) { false } } == true

    /** Starts a call to [peerKey] ([video]: with the camera on). False if a call is already going on or calling isn't set up yet. */
    fun start(peerKey: String, video: Boolean = false): Boolean {
        val a = app ?: return false
        if (session != null || RoomManager.busy || Opus.loadError != null) return false
        val s = CallSession(CallCrypto.newCallId(), peerKey, true, host, exec, work)
        s.wantVideo = video && videoHere()
        CallVideo.attach(a, s, s.wantVideo)
        session = s
        remember(s.callId)
        publish(s)
        CallService.start(a)
        exec.execute { s.startOutgoing() }
        return true
    }

    @Volatile private var preparedAt = 0L

    /**
     * A conversation with [peerKey] is open: fetch the relay login now (it is cached for two hours), so
     * pressing Call doesn't wait for the relay Worker. At most one try a minute; nothing is fetched when
     * internet calls are off or the contact can't take calls.
     */
    fun prepare(peerKey: String) {
        val a = app ?: return
        val now = System.currentTimeMillis()
        if (now - preparedAt < 60_000 || !internetCalls(a) || !RelayConfig.configured || !canCall(peerKey)) return
        preparedAt = now
        work.execute { try { host.relayLogin() } catch (_: Exception) {} }
    }

    /** Answers the ring; [video]: with the camera on (only when they started a video call and the camera may be used). */
    fun answer(video: Boolean = false) { exec.execute { lockedRing?.takeIf { session == null }?.let { answerLocked(it); return@execute }
        session?.let { s ->
        app?.let { Ringer.stop(it) }
        s.wantVideo = video && videoHere()
        CallVideo.want(s, s.wantVideo)
        s.answer(); app?.let { CallService.start(it) }
    } } }
    fun setCamera(on: Boolean) = CallVideo.setCamera(on)
    fun switchCamera() = CallVideo.switchCamera()
    fun decline() { exec.execute { lockedRing?.takeIf { session == null }?.let { declineLocked(it); return@execute }
        session?.decline() } }
    fun hangup() { exec.execute { session?.hangup() } }
    fun setMuted(on: Boolean) { exec.execute { session?.setMuted(on) } }
    fun setSpeaker(on: Boolean) { exec.execute { session?.setSpeaker(on) } }
    /** The call's sound goes to [id], one of [AudioRoute.outputs]: the earpiece, the speaker, headphones or a Bluetooth device. */
    fun setOutput(id: String) { exec.execute { session?.setOutput(id) } }
    /** Clears an ended call from the screen. */
    fun dismiss() { if (_call.value?.phase == CallSession.Phase.ENDED) _call.value = null }

    /**
     * Calls follow their chat's message limit, like messages (Chat.prune, every minute): a call from before
     * [cutoff] for that person (ms) goes from the history.
     */
    fun pruneOlder(cutoff: (String) -> Long) = exec.execute {
        val before = _history.value
        val keep = before.filter { l -> l.at >= try { cutoff(l.peerKey) } catch (_: Exception) { 0L } }
        if (keep.size != before.size) { _history.value = keep; app?.let { save(it) } }
    }


    // ------------------------------------------------------------------ signaling (from Chat)

    /** Every opened room "call" plaintext from a contact or group peer. */
    fun onSignal(fromKey: String, json: String) {
        exec.execute {
            val a = app ?: return@execute
            val s = try { CallLogic.parse(fromKey, json) } catch (e: CallLogic.Bad) { return@execute }
            if (s.voiceRoom) return@execute   // voice rooms and group calls: RoomManager
            val cur = session
            if (cur != null && cur.callId == s.call) { if (cur.peerKey == fromKey) cur.onSignal(s); return@execute }
            if (s.t != "offer") return@execute
            if (s.codec != CallCrypto.CODEC) { send(fromKey, CallLogic.simple("decline", s.call)); return@execute }
            // Cap b1: this call already rang while Muninn was locked, and the person answered or declined it then.
            // Still ringing on the locked screen (unlocked meanwhile): the offer rings now, with the name.
            if (lockedRing?.callId == s.call) lockedRing = null
            val chose = synchronized(lockedChoice) {
                lockedChoice.remove(s.call)?.takeIf { System.currentTimeMillis() - it.second <= CHOICE_MS }?.first
            }
            if (chose == false) {
                val no = CallLogic.simple("decline", s.call)
                send(fromKey, no); exec.schedule(Runnable { send(fromKey, no) }, 1, TimeUnit.SECONDS); exec.schedule(Runnable { send(fromKey, no) }, 2, TimeUnit.SECONDS)
                if (remember(s.call)) addLog(Log(s.call, fromKey, nameOf(fromKey), false, System.currentTimeMillis(), 0, "declined"))
                return@execute
            }
            // Answered while locked: answered now only while the caller still rings (45 s); older, a missed call.
            val answeredLocked = chose == true && CallLogic.lockedAnswerOk(s.ts, System.currentTimeMillis())
            if (!answeredLocked && (chose == true || !CallLogic.fresh(s.ts, System.currentTimeMillis()))) {
                // Sat on a relay too long: a missed call, no ringing.
                if (remember(s.call)) addLog(Log(s.call, fromKey, nameOf(fromKey), false, s.ts, 0, "missed")).also { Ringer.missed(a, nameOf(fromKey), fromKey) }
                return@execute
            }
            if (cur != null || RoomManager.busy) {
                // Already in a call (or a voice room): busy, 3 times over 2 s.
                val busy = CallLogic.simple("busy", s.call)
                send(fromKey, busy); exec.schedule(Runnable { send(fromKey, busy) }, 1, TimeUnit.SECONDS); exec.schedule(Runnable { send(fromKey, busy) }, 2, TimeUnit.SECONDS)
                if (remember(s.call)) addLog(Log(s.call, fromKey, nameOf(fromKey), false, System.currentTimeMillis(), 0, "missed"))
                return@execute
            }
            if (!remember(s.call)) return@execute   // an old call's late retry
            if (a.chat.blocked.value.contains(fromKey)) return@execute
            // Extreme battery saver: calls ring only while Muninn is open. Otherwise it's in the call history as missed.
            if (a.quietUntilOpen()) { addLog(Log(s.call, fromKey, nameOf(fromKey), false, System.currentTimeMillis(), 0, "missed")); return@execute }
            val ns = CallSession(s.call, fromKey, false, host, exec, work)
            CallVideo.attach(a, ns, false)
            session = ns
            ns.startIncoming(s)
            publish(ns)
            if (answeredLocked) {
                // The person already said yes on the ringing screen: answered now (voice), and the call screen comes back.
                ns.wantVideo = false
                CallVideo.want(ns, false)
                ns.answer()
                CallService.start(a)
                try {
                    a.startActivity(android.content.Intent(a, io.github.ravenor907.muninn.screens.CallActivity::class.java)
                        .setAction(io.github.ravenor907.muninn.screens.CallActivity.SHOW)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (_: Exception) {}
                return@execute
            }
            Ringer.ring(a, ns.callId, nameOf(fromKey), fromKey)
        }
    }

    /** True the first time a call id is seen. */
    private fun remember(id: String): Boolean = synchronized(seen) {
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > 10 * 60_000 }
        if (seen.containsKey(id)) false else { seen[id] = now; true }
    }

    private fun send(peerKey: String, o: JSONObject) { try { signal?.send(peerKey, o.toString()) } catch (_: Exception) {} }

    private fun nameOf(k: String) = try { app?.chat?.nameOf(k) ?: "Someone" } catch (e: Exception) { "Someone" }

    private fun publish(s: CallSession) {
        _call.value = Ui(s.callId, s.peerKey, nameOf(s.peerKey), s.outgoing, s.phase, s.connectedAt, s.muted, s.speaker, s.peerMuted,
            s.peerSpeaking, s.relay, s.wifi, s.bluetooth, s.endWhy, s.note, s.directOn, s.direct, s.videoBoth && s.answered, s.peerVideo)
        app?.let { CallService.update(it) }
    }

    // ------------------------------------------------------------------ what a session gets from the app

    internal val host = object : CallSession.Host {
        override val chatPrivate get() = app!!.identity.keys.private
        override fun send(peerKey: String, json: JSONObject) = this@CallManager.send(peerKey, json)
        override fun lanAddressOf(peerKey: String): InetAddress? = try {
            app?.net?.deviceFor(peerKey)?.ip?.let { CallLogic.literal(it) }
        } catch (e: Exception) { null }
        override fun relayLogin(): TurnCreds.Login? {
            val a = app ?: return null
            return TurnCreds.get(a.identity.relayId(), { url, body -> post(url, body) })
        }
        override fun resolve(host: String) = TurnClient.lookup(host)
        override fun internetAllowed() = app?.let { internetCalls(it) } != false
        override fun trusted(peerKey: String) = this@CallManager.trusted(peerKey)
        // A Kids' TV says no VP8, so a parent's video call to it is a voice call (5.8.5).
        override fun videoCodec() = videoHere()
        override fun newMedia(s: CallSession) = CallMedia(app!!, s.outgoing, s.callId, s.peerKey, bluetooth, object : CallMedia.Events {
            override fun onFirstMedia() = s.mediaFirst()
            override fun onPaths(relay: Boolean, wifi: Boolean, bluetooth: Boolean) = s.mediaPaths(relay, wifi, bluetooth)
            override fun onSpeaking(peer: Boolean) = s.mediaSpeaking(peer)
            override fun onDirect(live: Boolean) = s.mediaDirect(live)
            override fun onVideoFrame(stream: Int, data: ByteArray, key: Boolean, rotation: Int) = CallVideo.onFrame(s, stream, data, key, rotation)
            override fun onKeyframeWanted(stream: Int) = CallVideo.onKeyWanted(s, stream)
            override fun onPeerVideo(camera: Boolean, screen: Boolean) = CallVideo.onPeerVideo(s, camera)
            override fun onProfile() = CallVideo.onProfile(s)
        })
        override fun changed(s: CallSession) {
            if (session !== s) return
            if (s.answered && (s.phase == CallSession.Phase.CONNECTING || s.phase == CallSession.Phase.LIVE)) CallVideo.ready(s)
            publish(s)
        }
        override fun ended(s: CallSession) {
            if (session !== s) return
            session = null
            CallVideo.detach()
            val a = app
            publish(s)
            if (a != null) {
                Ringer.stop(a)
                CallService.stop(a)
                val result = when (s.endWhy) {
                    "missed" -> "missed"
                    "declined" -> "declined"
                    "noanswer" -> "noanswer"
                    "busy" -> "busy"
                    "nopath", "error" -> if (s.connectedAt > 0) "answered" else "failed"
                    else -> if (s.connectedAt > 0) "answered" else if (s.outgoing) "noanswer" else "missed"
                }
                val secs = if (s.connectedAt > 0) (System.currentTimeMillis() - s.connectedAt) / 1000 else 0
                addLog(Log(s.callId, s.peerKey, nameOf(s.peerKey), s.outgoing, s.startedAt, secs, result))
                if (result == "missed") Ringer.missed(a, nameOf(s.peerKey), s.peerKey)
            }
            // The ended state stays on screen for a moment, then goes.
            exec.schedule(Runnable { if (_call.value?.callId == s.callId && session == null) _call.value = null }, 2500, TimeUnit.MILLISECONDS)
        }
    }

    /** HTTPS POST to the relay Worker. (status, body) or null if it couldn't connect. */
    private fun post(url: String, body: String): Pair<Int, String>? {
        return try {
            Net.client.newBuilder().callTimeout(45, TimeUnit.SECONDS).build().newCall(
                Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute().use { r -> r.code to (r.body?.string() ?: "") }
        } catch (e: Exception) { null }
    }

    // ------------------------------------------------------------------ history (sealed in the vault)

    private fun file(c: Context) = File(c.filesDir, "calls.json")

    private fun addLog(l: Log) {
        _history.value = (listOf(l) + _history.value.filterNot { it.id == l.id }).take(MAX_HISTORY)
        app?.let { save(it) }
    }

    private fun save(a: Muninn) {
        val arr = JSONArray()
        for (l in _history.value) arr.put(JSONObject().put("id", l.id).put("k", l.peerKey).put("n", l.name).put("out", l.outgoing)
            .put("at", l.at).put("s", l.seconds).put("r", l.result))
        try { a.vault.write(file(a), arr.toString()) } catch (_: Exception) {}
    }

    private fun load(a: Muninn): List<Log> = try {
        val arr = JSONArray(a.vault.read(file(a)) ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            Log(it.getString("id"), it.getString("k"), it.optString("n", "Someone"), it.optBoolean("out"), it.optLong("at"), it.optLong("s"), it.optString("r"))
        }
    } catch (e: Exception) { emptyList() }
}
