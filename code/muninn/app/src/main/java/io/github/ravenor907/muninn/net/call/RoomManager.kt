package io.github.ravenor907.muninn.net.call

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Proto06
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Voice rooms and group calls (shared/docs/protocol.md section 6). A room is a full mesh of 1:1 calls: one
 * [CallSession] per other person, each with its own keys, its own TURN allocation and paths, so media is
 * encrypted per recipient and nobody in the room learns anyone's IP address. This object owns the
 * microphone and the speaker for all of them: each 20 ms frame is encoded once and sealed for every peer,
 * and each peer's jitter buffer and decoder give a frame that is mixed into the speaker.
 *
 * Everything that changes state runs on [exec], the thread the room's sessions run on too.
 */
object RoomManager {
    /** One person in the room. [state]: "live", "connecting" or "reconnecting". Never an address. */
    data class Person(val key: String, val name: String, val me: Boolean, val speaking: Boolean = false, val muted: Boolean = false, val state: String = "live")

    /**
     * The room this phone is in, for the screens. [muted]: my own Mute; [deafened]: Deafen (nobody heard, mic off);
     * [held]: why the room is paused ("On a phone call"), else ""; [listenOnly]: no microphone permission;
     * [stillHere]: alone for a while, asking before leaving.
     */
    data class Ui(
        val g: String, val ch: String, val title: String, val groupName: String, val people: List<Person>, val muted: Boolean,
        val speaker: Boolean, val since: Long, val connected: Int, val groupCall: Boolean, val note: String,
        val deafened: Boolean = false, val held: String = "", val listenOnly: Boolean = false, val stillHere: Boolean = false,
    )

    /** Someone in the room sharing their screen (5.8.7), and its picture's size and turn. */
    data class Screen(val key: String, val name: String, val width: Int = 0, val height: Int = 0, val rotation: Int = 0)

    private val _screen = MutableStateFlow<Screen?>(null)
    val screen: StateFlow<Screen?> = _screen.asStateFlow()
    private val screenView = RemoteVideo("muninn-screen-dec", { if (watching) sharerMedia()?.requestKeyframe(VideoWire.SCREEN) }) { w, h, r ->
        _screen.value = _screen.value?.copy(width = w, height = h, rotation = r)
    }
    @Volatile private var sharer = ""

    /** "Direct calls" turned off for [k]: the room's call with them stops going direct. */
    fun untrust(k: String) = exec.execute { peers[k]?.s?.untrust() }

    private fun sharerMedia(): CallMedia? = if (sharer.isEmpty()) null else peers[sharer]?.s?.mediaNow

    /** The room screen's surface for the shared screen, or null when it goes away. */
    fun screenSurface(sf: android.view.Surface?) = screenView.surface(sf)

    /** Who shares their screen: the one shown stays while they share, otherwise the first who does (on [exec]). */
    private fun pickSharer() {
        val still = sharer.isNotEmpty() && sharerMedia()?.peerSending(VideoWire.SCREEN) == true
        if (still) return
        val next = peers.entries.firstOrNull { it.value.s.mediaNow?.peerSending(VideoWire.SCREEN) == true }?.key ?: ""
        if (next == sharer) return
        screenView.stop()
        sharer = next
        _screen.value = if (next.isEmpty()) null else Screen(next, nameOf(g, next))
        if (next.isNotEmpty() && watching) { screenView.start(); sharerMedia()?.requestKeyframe(VideoWire.SCREEN) }
    }

    /**
     * The room screen is on show (or in picture-in-picture) with the phone's screen on. Not watching: the shared
     * screen isn't decoded, and a sharer with cap c3 stops sending it to this phone (5.8.7).
     */
    @Volatile private var watching = false
    fun setWatching(on: Boolean) = exec.execute {
        if (watching == on) return@execute
        watching = on
        for ((k, p) in peers) if (pausesScreen(k)) p.s.mediaNow?.pauseScreen(!on)
        if (on) { if (sharer.isNotEmpty()) { screenView.start(); sharerMedia()?.requestKeyframe(VideoWire.SCREEN) } }
        else screenView.stop()
    }

    /** Cap c3: [k]'s app stops sending its screen share to someone not watching it. */
    private fun pausesScreen(k: String): Boolean = try { app?.chat?.contacts?.get(k)?.caps?.contains(SCREEN_PAUSE_CAP) == true } catch (e: Exception) { false }
    const val SCREEN_PAUSE_CAP = "c3"
    /** What the room says while a phone call has the sound. */
    const val ON_CALL = SoundHold.ON_CALL

    private var proximityLock: android.os.PowerManager.WakeLock? = null
    /** The room screen is in front (resumed): only then does the proximity sensor darken the screen. */
    private val inFront = MutableStateFlow(false)
    fun screenInFront(on: Boolean) { inFront.value = on }

    /** On the earpiece in a room: the screen goes dark against the cheek. */
    private fun proximity(c: Context, on: Boolean) {
        try {
            if (on) {
                if (proximityLock == null) {
                    val pm = c.getSystemService(android.os.PowerManager::class.java)
                    if (pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                        proximityLock = pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "muninn:room")
                }
                proximityLock?.takeIf { !it.isHeld }?.acquire(4 * 60 * 60_000L)
            } else proximityLock?.takeIf { it.isHeld }?.release(android.os.PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        } catch (_: Exception) {}
    }

    /** A group call ringing this phone. */
    data class Ring(val g: String, val ch: String, val from: String, val name: String, val group: String, val at: Long)

    private val _room = MutableStateFlow<Ui?>(null)
    val room: StateFlow<Ui?> = _room.asStateFlow()
    private val _ring = MutableStateFlow<Ring?>(null)
    val ring: StateFlow<Ring?> = _ring.asStateFlow()
    /** Bumps whenever who's in which room may have changed (for the group screens). */
    private val _presence = MutableStateFlow(0)
    val presence: StateFlow<Int> = _presence.asStateFlow()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** How a room ended or what happened, for a toast. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** In a room (or joining one): 1:1 offers get busy (6.6). */
    @Volatile var busy = false; private set

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-room").apply { isDaemon = true } }
    private val work = Executors.newCachedThreadPool { r -> Thread(r, "muninn-room-work").apply { isDaemon = true } }
    @Volatile private var app: Muninn? = null

    private class Peer(val s: CallSession, val retries: Int)
    private val peers = ConcurrentHashMap<String, Peer>()
    private val medias = ConcurrentHashMap<String, CallMedia>()    // call id -> its media
    private val speaking = ConcurrentHashMap<String, Boolean>()     // peer key -> speaking now
    private val presenceTable = RoomLogic.Presence()
    @Volatile private var g = ""
    @Volatile private var ch = ""
    @Volatile private var muted = false
    @Volatile private var deafened = false
    /** Why the room is paused (another app, usually a phone call, has the sound), else "". */
    @Volatile private var held = ""
    /** Joined without the microphone permission: listening only. */
    @Volatile private var listenOnly = false
    @Volatile private var speakerOn = true
    @Volatile private var mySpeaking = false
    /** Alone in the room since (0: someone else is here); [stillHere] once asked whether to stay. */
    private var aloneSince = 0L
    private var stillHere = false
    private var askedAt = 0L

    /** What goes out from my microphone: nothing while muted, deafened or paused for a phone call. */
    private fun micOff(): Boolean = muted || deafened || held.isNotEmpty() || listenOnly
    private var joinedAt = 0L
    private var note = ""
    private var gen = 0
    private val rung = HashSet<String>()
    private val declined = HashSet<String>()
    private val timers = ArrayList<ScheduledFuture<*>>()
    private var ringTimer: ScheduledFuture<*>? = null

    fun init(app: Muninn) {
        this.app = app
        // Left the group (or it went away) while in its room: out of the room at once, not at the next check.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            try { app.chat.groups.all.collect { if (g.isNotEmpty()) exec.execute { if (g.isNotEmpty() && state(g) == null) doLeave("You left the group, so you left its voice room") } } }
            catch (_: Exception) {}
        }
        // Proximity on the earpiece: the screen goes dark against the cheek (the speaker or a headset keeps it on).
        // Only while the room screen is in front: covering the phone's top in a chat doesn't blank it.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            combine(AudioRoute.current, _room, inFront) { out, r, front -> r != null && front && out?.kind == AudioRoute.Kind.EARPIECE }.distinctUntilChanged()
                .collect { proximity(app, it) }
        }
        // Who is in a room ages out (25 minutes, or 20 s and 60 s for an app with heartbeats): a screen showing it looks
        // again every 5 s, only while one is showing it.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default).launch {
            _presence.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                while (watched) { delay(5_000); _presence.value = _presence.value + 1 }
            }
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun me(): String = app?.identity?.publicKey ?: ""
    private fun state(group: String): Proto06.State? = try { app?.chat?.groups?.group(group)?.takeIf { !it.readOnly }?.state } catch (e: Exception) { null }
    private fun nameOf(group: String, k: String): String {
        val a = app ?: return "Someone"
        val contact = try { a.chat.contacts.contact(k)?.shown } catch (e: Exception) { null }
        return contact ?: state(group)?.member(k)?.n?.ifBlank { null } ?: try { a.chat.nameOf(k) } catch (e: Exception) { "Someone" }
    }

    /** Can [k]'s app join rooms? "v1", or caps not known yet (an app without v1 quietly drops it). Not blocked. */
    private fun canRoom(k: String): Boolean {
        val a = app ?: return false
        val c = try { a.chat.contacts.get(k) } catch (e: Exception) { null } ?: return false
        if (try { a.chat.blocked.value.contains(k) } catch (e: Exception) { false }) return false
        return c.caps.isEmpty() || "v1" in c.caps
    }

    /** Cap g2: [k] is timed out or banned in [group]. */
    private fun barred(group: String, k: String): Boolean = try { app?.chat?.groups?.barred(group, k) == true } catch (e: Exception) { false }

    /** Cap v2 (6.8): [k]'s app says "still here" every 10 s while in a room. */
    private fun beats(k: String): Boolean = try { app?.chat?.contacts?.get(k)?.caps?.contains(RoomLogic.HEARTBEAT_CAP) == true } catch (e: Exception) { false }

    /** When a heartbeat last led to an offer, per person: at most every 30 s, so a pair that can't connect doesn't retry every 10 s. */
    private val offered = HashMap<String, Long>()

    /** Every 10 s in a room: vjoin to members with v2; someone with v2 quiet for 60 s whose call isn't up leaves the room. */
    private fun heartbeat() {
        if (g.isEmpty()) return
        // Left the group: out of its room.
        if (state(g) == null) { doLeave("You left the group, so you left its voice room"); return }
        if (alone()) return
        // Cap g2: timed out or banned while in the room: out of it; anyone else timed out or banned leaves it here.
        app?.chat?.groups?.myStatus(g)?.takeIf { it.isNotEmpty() }?.let { doLeave(it); return }
        val out = peers.keys.filter { barred(g, it) }
        if (out.isNotEmpty()) { out.forEach { drop(it); speaking.remove(it) }; publish() }
        for (k in members(g)) if (beats(k)) sendRoom("vjoin", k, g, ch)
        if (now() - joinedAt < RoomLogic.GONE_MS) return
        var gone = false
        for ((k, p) in peers.entries.toList()) {
            if (!beats(k) || p.s.phase == CallSession.Phase.LIVE) continue
            if (presenceTable.state(g, ch, k, now()) == 0) { drop(k); speaking.remove(k); gone = true }
        }
        if (gone) publish()
    }

    /** Known to have an app without voice rooms. */
    fun needsUpdate(k: String): Boolean {
        val c = try { app?.chat?.contacts?.get(k) } catch (e: Exception) { null } ?: return false
        return c.caps.isNotEmpty() && "v1" !in c.caps
    }

    private fun members(group: String): List<String> {
        val mine = me()
        return state(group)?.members?.map { it.k }?.filter { it != mine && canRoom(it) } ?: emptyList()
    }

    private fun sendRoom(t: String, to: String, group: String, room: String) {
        val a = app ?: return
        try { CallManager.host.send(to, RoomLogic.make(a.identity.keys.private, t, group, room, now())) } catch (_: Exception) {}
    }

    private fun announce(onlyUnconnected: Boolean) {
        if (g.isEmpty()) return
        for (k in members(g)) if (!onlyUnconnected || !peers.containsKey(k)) sendRoom("vjoin", k, g, ch)
    }

    /**
     * Alone in the room for [RoomLogic.ALONE_MS]: "Still here?" (the room screen and a notification); no answer
     * within [RoomLogic.ASK_MS] leaves. True when it left.
     */
    private fun alone(): Boolean {
        val mine = me()
        val nobody = peers.isEmpty() && presenceTable.inRoom(g, ch, now()).none { it != mine }
        if (!nobody) {
            aloneSince = 0L
            if (stillHere) { stillHere = false; app?.let { RoomRinger.stillHereGone(it) }; publish() }
            return false
        }
        val t = now()
        if (aloneSince == 0L) aloneSince = t
        if (!stillHere && t - aloneSince >= RoomLogic.ALONE_MS) {
            stillHere = true; askedAt = t
            publish()
            app?.let { a -> _room.value?.let { RoomRinger.stillHere(a, it) } }
        } else if (stillHere && t - askedAt >= RoomLogic.ASK_MS) {
            val title = _room.value?.title ?: ""
            doLeave("Nobody else came, so you left the voice room")
            app?.let { RoomRinger.leftAlone(it, title) }   // a note that stays, even with the screen off
            return true
        }
        return false
    }

    /** "I'm still here": the alone timer starts over. */
    fun stay() = exec.execute {
        aloneSince = now(); askedAt = 0L
        if (stillHere) { stillHere = false; app?.let { RoomRinger.stillHereGone(it) }; publish() }
    }

    // ------------------------------------------------------------------ actions (from the screens)

    /**
     * Joins (g, ch). Null when joining, otherwise what to tell the person. [startMuted]: the join sheet's mic off;
     * [output]: its sound choice ([AudioRoute.AUTOMATIC] or an output id); [listen]: no microphone permission, so
     * listening only.
     */
    fun join(group: String, room: String, startMuted: Boolean = false, output: String = AudioRoute.AUTOMATIC, listen: Boolean = false): String? {
        if (app == null) return "Unlock Muninn first."
        if (Opus.loadError != null) return "Calling isn't working on this phone."
        if (CallManager.call.value?.let { it.phase != CallSession.Phase.ENDED } == true) return "Hang up the call first."
        if (!RoomLogic.isRoomId(room)) return "That isn't a voice room."
        val st = state(group) ?: return "You're not in this group anymore."
        if (!st.has(me())) return "You're not in this group anymore."
        // Cap g2: timed out or banned here.
        app?.chat?.groups?.myStatus(group)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (!RoomLogic.isGroupCall(room) && st.channel(room)?.t != "voice") return "That isn't a voice room."
        if (g == group && ch == room) return null
        busy = true
        exec.execute { doJoin(group, room, startMuted, output, listen) }
        return null
    }

    private fun doJoin(group: String, room: String, startMuted: Boolean = false, output: String = AudioRoute.AUTOMATIC, listen: Boolean = false) {
        if (g.isNotEmpty()) doLeave("")
        val a = app ?: return
        if (_ring.value?.let { it.g == group && it.ch == room } == true) stopRing()
        gen++
        g = group; ch = room; joinedAt = now(); note = ""
        busy = true
        rung.clear(); declined.clear(); speaking.clear(); mySpeaking = false
        muted = startMuted || listen; deafened = false; held = ""; listenOnly = listen
        aloneSince = 0L; stillHere = false; askedAt = 0L
        if (!RoomAudio.start(a, mic = !listen)) note = "The microphone isn't available"
        if (output != AudioRoute.AUTOMATIC) AudioRoute.choose(output)
        announce(false)
        val my = gen
        for (ms in listOf(4_000L, 12_000L)) timers += exec.schedule(Runnable { if (my == gen) announce(true) }, ms, TimeUnit.MILLISECONDS)
        timers += exec.scheduleWithFixedDelay({
            if (my != gen) return@scheduleWithFixedDelay
            if (state(g) == null) { doLeave("You're not in this group anymore"); return@scheduleWithFixedDelay }
            announce(false)
        }, RoomLogic.ANNOUNCE_MS, RoomLogic.ANNOUNCE_MS, TimeUnit.MILLISECONDS)
        // Cap v2 (6.8): "still here" every 10 s.
        timers += exec.scheduleWithFixedDelay({ if (my == gen) heartbeat() }, RoomLogic.HEARTBEAT_MS, RoomLogic.HEARTBEAT_MS, TimeUnit.MILLISECONDS)
        RoomService.start(a)
        bumpPresence()
        publish()
    }

    fun leave() = exec.execute { if (g.isEmpty()) { _room.value = null; _screen.value = null } else doLeave("") }
    /** Leaves the room, then runs [after] (on the room's thread): a 1:1 call answered from the room's soft ring. */
    fun leaveThen(after: () -> Unit) = exec.execute {
        if (g.isEmpty()) { _room.value = null; _screen.value = null } else doLeave("")
        try { after() } catch (_: Exception) {}
    }

    /** Test builds only (the CI screenshots): a made-up room with a shared screen. No sound, nothing sent. */
    fun demo(title: String, group: String) {
        _room.value = Ui("", "", title, group, listOf(Person("me", "You", true),
            Person("demo-sam", "Sam", false, speaking = true), Person("demo-jordan", "Jordan", false, muted = true),
            Person("demo-alex", "Alex", false, state = "reconnecting")), muted = false, speaker = false, since = now() - 754_000L,
            connected = 3, groupCall = false, note = "")
        _screen.value = Screen("demo-sam", "Sam", 1920, 1080, 0)
    }

    private fun doLeave(why: String) {
        if (g.isEmpty()) return
        val group = g; val room = ch
        for (k in members(group)) sendRoom("vleave", k, group, room)
        gen++
        g = ""; ch = ""
        offered.clear()
        timers.forEach { it.cancel(false) }; timers.clear()
        val all = peers.values.toList()
        peers.clear()
        for (p in all) p.s.hangup()
        RoomAudio.stop()
        medias.clear(); speaking.clear()
        deafened = false; held = ""; listenOnly = false; stillHere = false
        app?.let { RoomRinger.stillHereGone(it) }
        screenView.stop(); sharer = ""; _screen.value = null
        RoomVideo.reset()
        busy = false
        _room.value = null
        app?.let { RoomService.stop(it) }
        if (why.isNotEmpty()) _notices.tryEmit(why)
        bumpPresence()
    }

    /** Mute: my microphone off. Unmuting while deafened undeafens too. Listening only can't unmute (no microphone). */
    fun setMuted(on: Boolean) = exec.execute {
        muted = on || listenOnly
        if (!on) deafened = false
        micToPeers()
        publish()
    }

    /** Deafen: nobody in the room is heard here, and my microphone is off; others see me muted. */
    fun setDeafened(on: Boolean) = exec.execute {
        deafened = on
        micToPeers()
        publish()
    }

    /** The microphone permission was just allowed while listening only: the microphone starts, unmuted. */
    fun micAllowed() = exec.execute {
        val a = app ?: return@execute
        if (g.isEmpty() || !listenOnly) return@execute
        if (!RoomAudio.startMic(a)) { note = "The microphone isn't available"; publish(); return@execute }
        listenOnly = false; muted = false; note = ""
        micToPeers()
        publish()
        RoomService.start(a)   // the microphone joins the service's types
    }

    /**
     * Another app took the sound ([why], e.g. a phone call) or gave it back (""): the room pauses and resumes.
     * [retake]: the room's call mode, route and focus go back even when the reason doesn't change.
     */
    private fun hold(why: String, retake: Boolean = false) = exec.execute {
        if (g.isEmpty()) return@execute
        if (held == why) { if (retake) RoomAudio.resume(); return@execute }
        held = why
        if (why.isEmpty()) RoomAudio.resume()
        micToPeers()
        publish()
    }

    /** Tells each pair whether my microphone is off now (they show me muted). */
    private fun micToPeers() {
        val off = micOff()
        for (p in peers.values) if (p.s.muted != off) p.s.setMuted(off)
    }

    fun setSpeaker(on: Boolean) = exec.execute { speakerOn = on; RoomAudio.route(on); publish() }
    /** The room's sound goes to [id], one of [AudioRoute.outputs]. */
    fun setOutput(id: String) = exec.execute { AudioRoute.choose(id); speakerOn = AudioRoute.onSpeaker; publish() }

    /** Rings [keys] (every member who can join, when empty) into a new group call of [group], and joins it. */
    fun callGroup(group: String, keys: List<String>): String? {
        val room = RoomLogic.newGroupCallId()
        val all = members(group)
        val targets = (if (keys.isEmpty()) all else keys.filter { it in all }).distinct()
        if (targets.isEmpty()) return "Nobody in this group can join calls yet."
        join(group, room)?.let { return it }
        exec.execute {
            if (g != group || ch != room) return@execute
            rung.addAll(targets)
            for (k in targets) sendRoom("vring", k, group, room)
            val my = gen
            timers += exec.schedule(Runnable {
                if (my == gen) for (k in rung) if (!peers.containsKey(k) && k !in declined) sendRoom("vring", k, group, room)
            }, 15, TimeUnit.SECONDS)
            timers += exec.schedule(Runnable { if (my == gen && peers.isEmpty()) doLeave("No one joined") }, RoomLogic.RING_MS, TimeUnit.MILLISECONDS)
        }
        return null
    }

    /** Who in [group] a group call can ring now (in its current state, with an app that takes rooms). */
    fun ringable(group: String): Set<String> = members(group).toSet()

    /**
     * In a group call of [group] going on here: ring [keys] too (people the group reached late). Returns
     * who will be rung (empty when no call of [group] is going on yet).
     */
    fun ringMore(group: String, keys: Collection<String>): Set<String> {
        val room = ch
        if (g != group || !RoomLogic.isGroupCall(room)) return emptySet()
        val ok = members(group).filter { it in keys }.toSet()
        if (ok.isNotEmpty()) exec.execute {
            if (g != group || ch != room) return@execute
            for (k in ok) if (!peers.containsKey(k) && k !in declined) { rung += k; sendRoom("vring", k, group, room) }
        }
        return ok
    }

    /** Join the group call that's ringing. Null when joining, otherwise what to tell the person. */
    fun answerRing(listen: Boolean = false): String? {
        val r = _ring.value ?: return null
        stopRing()
        return join(r.g, r.ch, listen = listen)
    }

    fun declineRing() {
        val r = _ring.value ?: return
        stopRing()
        exec.execute { sendRoom("vdecline", r.from, r.g, r.ch) }
    }

    private fun stopRing() {
        ringTimer?.cancel(false); ringTimer = null
        _ring.value = null
        app?.let { RoomRinger.stop(it) }
    }

    /** Who is in a room right now (presence, plus my own calls there when I'm in it). */
    fun whoIn(group: String, room: String): List<Person> {
        val mine = g == group && ch == room
        val keys = presenceTable.inRoom(group, room, now()).toMutableList()
        if (mine) peers.keys.forEach { if (it !in keys) keys += it }
        val out = ArrayList<Person>()
        if (mine) out += Person(me(), "You", true, speaking = mySpeaking && !micOff(), muted = micOff())
        val t = now()
        for (k in keys) if (k != me()) {
            val s = if (mine) peers[k]?.s else null
            val quiet = s?.phase != CallSession.Phase.LIVE && (s?.phase == CallSession.Phase.RECONNECTING || presenceTable.state(group, room, k, t) == 2)
            out += Person(k, nameOf(group, k), false, speaking = mine && speaking[k] == true, state = if (quiet) "reconnecting" else "live")
        }
        return out
    }

    /** Group calls going on in [group]: room id to who's in it. */
    fun groupCalls(group: String): List<Pair<String, List<Person>>> {
        val rooms = presenceTable.rooms(group, now()).keys.toMutableSet()
        if (g == group && RoomLogic.isGroupCall(ch)) rooms += ch
        return rooms.filter { RoomLogic.isGroupCall(it) }.map { it to whoIn(group, it) }.filter { it.second.isNotEmpty() || (g == group && ch == it.first) }
    }

    private fun bumpPresence() { _presence.value = _presence.value + 1 }

    private fun publish() {
        if (g.isEmpty()) { _room.value = null; return }
        val st = state(g)
        val people = ArrayList<Person>()
        people += Person(me(), "You", true, speaking = mySpeaking && !micOff(), muted = micOff())
        var connected = 0
        for ((k, p) in peers) {
            val s = p.s
            if (s.phase == CallSession.Phase.ENDED) continue
            val live = s.phase == CallSession.Phase.LIVE
            if (live || s.phase == CallSession.Phase.RECONNECTING) connected++
            people += Person(k, nameOf(g, k), false, speaking = speaking[k] == true, muted = s.peerMuted,
                state = if (live) "live" else if (s.phase == CallSession.Phase.RECONNECTING) "reconnecting" else "connecting")
        }
        // Cap v2 (6.8): still saying they're here, but their call dropped: Reconnecting until it's back or they're gone.
        for (k in presenceTable.inRoom(g, ch, now())) if (k != me() && peers[k] == null && beats(k)) people += Person(k, nameOf(g, k), false, state = "reconnecting")
        val sorted = listOf(people.first()) + people.drop(1).sortedBy { it.name.lowercase() }
        val title = if (RoomLogic.isGroupCall(ch)) "Group call" else st?.channel(ch)?.n ?: "Voice room"
        // Deafened: the mic is off too, so it shows muted, and Unmute undeafens (setMuted).
        _room.value = Ui(g, ch, title, st?.name ?: "", sorted, muted || deafened, speakerOn, joinedAt, connected, RoomLogic.isGroupCall(ch), note,
            deafened, held, listenOnly, stillHere)
        // Group video goes to the people in the room whose call is up.
        app?.let { a -> RoomVideo.peers(a, peers.mapNotNull { (k, p) -> p.s.mediaNow?.let { k to it } }.toMap(), sorted.size) }
        bumpPresence()
        app?.let { RoomService.update(it) }
    }

    // ------------------------------------------------------------------ what a room's sessions get

    private val host: CallSession.Host by lazy {
        object : CallSession.Host by CallManager.host {
            override fun newMedia(s: CallSession): CallMedia =
                // No Bluetooth media in rooms: that link has one handler, for 1:1 calls.
                CallMedia(app!!, s.outgoing, s.callId, s.peerKey, null, object : CallMedia.Events {
                    override fun onFirstMedia() = s.mediaFirst()
                    override fun onPaths(relay: Boolean, wifi: Boolean, bluetooth: Boolean) = s.mediaPaths(relay, wifi, bluetooth)
                    override fun onSpeaking(peer: Boolean) {}
                    override fun onDirect(live: Boolean) = s.mediaDirect(live)
                    // Screen share (5.8.7): Muninn shows it; sharing is Huginn's.
                    override fun onPeerVideo(camera: Boolean, screen: Boolean) { exec.execute { pickSharer() }; RoomVideo.peerCamera(s.peerKey, camera) }
                    override fun onVideoFrame(stream: Int, data: ByteArray, key: Boolean, rotation: Int) {
                        if (stream == VideoWire.SCREEN && s.peerKey == sharer && watching) screenView.frame(data, key, rotation)
                        // Group video: each person's camera, peer to peer.
                        if (stream == VideoWire.CAMERA) RoomVideo.frame(s.peerKey, data, key, rotation)
                    }
                    override fun onKeyframeWanted(stream: Int) { if (stream == VideoWire.CAMERA) RoomVideo.keyWanted(s.peerKey) }
                }).also { it.externalAudio = true; it.muted = micOff(); if (!watching && pausesScreen(s.peerKey)) it.pauseScreen(true); medias[s.callId] = it }
            override fun changed(s: CallSession) {
                // Muted before the pair's call was up: tell them once it is.
                if (s.phase == CallSession.Phase.LIVE && micOff() && !mutedSent.contains(s.callId)) {
                    mutedSent += s.callId
                    send(s.peerKey, CallLogic.mute(s.callId, true))
                }
                if (peers[s.peerKey]?.s === s) publish()
            }
            override fun ended(s: CallSession) = peerEnded(s)
        }
    }
    private val mutedSent = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun newSession(callId: String, key: String, outgoing: Boolean, retries: Int): CallSession {
        val s = CallSession(callId, key, outgoing, host, exec, work)
        s.inRoom = true
        peers[key] = Peer(s, retries)
        return s
    }

    private fun offerTo(key: String, retries: Int = 0) {
        if (g.isEmpty() || peers.containsKey(key)) return
        val s = newSession(CallCrypto.newCallId(), key, true, retries)
        s.extra = JSONObject().put("g", g).put("ch", ch)
        s.startOutgoing()
        if (micOff()) s.setMuted(true)
        publish()
    }

    private fun peerEnded(s: CallSession) {
        medias.remove(s.callId)
        val p = peers[s.peerKey]
        if (p == null || p.s !== s) return
        peers.remove(s.peerKey)
        speaking.remove(s.peerKey)
        if (s.peerKey == sharer) pickSharer()
        // A pair that lost its connection while both stay: the lower key calls again (6.3 step 7).
        if (g.isNotEmpty() && (s.endWhy == "nopath" || s.endWhy == "dropped") && p.retries < 3 && RoomLogic.offerWins(me(), s.peerKey) == me()) {
            val my = gen
            timers += exec.schedule(Runnable { if (my == gen) offerTo(s.peerKey, p.retries + 1) }, 5, TimeUnit.SECONDS)
        }
        publish()
    }

    /** Drops my own call to [key] without a word (6.3 step 4: their offer won). */
    private fun drop(key: String) {
        val p = peers.remove(key) ?: return
        medias.remove(p.s.callId)?.stop()
        p.s.end("hangup", send = false)
    }

    // ------------------------------------------------------------------ signaling (from Chat)

    /** Every opened room "call" plaintext; anything not about rooms is left to [CallManager]. */
    fun onSignal(fromKey: String, json: String) {
        if (app == null) return
        exec.execute {
            val o = try { JSONObject(json) } catch (e: Exception) { return@execute }
            val call = o.opt("call") as? String ?: return@execute
            when {
                call == "-" -> roomMessage(fromKey, o)
                o.has("g") -> if (o.opt("t") == "offer") roomOffer(fromKey, o)
                else -> {
                    val p = peers[fromKey] ?: return@execute
                    if (p.s.callId != call) return@execute
                    val s = try { CallLogic.parse(fromKey, json) } catch (e: CallLogic.Bad) { return@execute }
                    p.s.onSignal(s)
                }
            }
        }
    }

    private fun roomMessage(from: String, o: JSONObject) {
        val group = o.optString("g"); val room = o.optString("ch"); val t = o.optString("t")
        val verdict = RoomLogic.accept(state(group), me(), from, o, now())
        val fresh = verdict == "ok"
        if (!fresh && verdict != "stale") return
        // Cap g2: someone timed out or banned doesn't join, ring or show up in this group's rooms (a leave still counts).
        if (t != "vleave" && barred(group, from)) return
        val here = g == group && ch == room
        when (t) {
            "vjoin", "vleave" -> {
                if (presenceTable.apply(from, o, now(), beats(from), fresh)) bumpPresence()
                if (t == "vjoin") {
                    if (fresh && here && !peers.containsKey(from) && now() - (offered[from] ?: 0L) >= 30_000) {
                        if (RoomLogic.full(peers.size)) sendRoom("full", from, group, room) else { offered[from] = now(); offerTo(from) }
                    }
                } else {
                    offered.remove(from)
                    if (here) peers.remove(from)?.let { p -> p.s.hangup(); medias.remove(p.s.callId); speaking.remove(from); publish() }
                    val r = _ring.value
                    if (r != null && r.g == group && r.ch == room && presenceTable.inRoom(group, room, now()).isEmpty()) {
                        stopRing()
                        missed(r.name, r.group, r.g)
                    }
                }
            }
            "vring" -> {
                if (here || _ring.value?.let { it.g == group && it.ch == room } == true) return   // in it, or the same ring again
                val inCall = CallManager.call.value?.let { it.phase != CallSession.Phase.ENDED } == true
                val who = nameOf(group, from); val grp = state(group)?.name ?: ""
                if (!fresh || g.isNotEmpty() || inCall || _ring.value != null) { missed(who, grp, group); return }
                val a = app ?: return
                if (try { a.chat.blocked.value.contains(from) } catch (e: Exception) { false }) return
                // Extreme battery saver: group calls ring only while Muninn is open.
                if (a.quietUntilOpen()) return
                // A muted group: no ringing and no notification; its voice rooms show who is in the call.
                if (try { a.chat.flags.muted(io.github.ravenor907.muninn.net.Folders.groupKey(group)) } catch (e: Exception) { false }) return
                _ring.value = Ring(group, room, from, who, grp, now())
                RoomRinger.ring(a, who, grp)
                ringTimer = exec.schedule(Runnable {
                    val r = _ring.value
                    if (r != null && r.g == group && r.ch == room) { stopRing(); missed(who, grp, group) }
                }, RoomLogic.RING_MS, TimeUnit.MILLISECONDS)
            }
            "vdecline" -> if (fresh && here) { declined += from; _notices.tryEmit("${nameOf(group, from)} can't join right now") }
            "full" -> if (fresh && here && now() - joinedAt < 30_000) doLeave("This room is full (${RoomLogic.MAX_PEOPLE} people)")
        }
    }

    /** A group call missed: the notification opens group [g]. */
    private fun missed(who: String, group: String, g: String) {
        val a = app ?: return
        Ringer.missed(a, if (group.isNotEmpty()) "$who · $group" else who, io.github.ravenor907.muninn.net.Folders.groupKey(g))
    }

    private fun roomOffer(from: String, o: JSONObject) {
        val group = o.opt("g") as? String ?: return
        val room = o.opt("ch") as? String ?: return
        val call = o.opt("call") as? String ?: return
        // The rest is a plain 5.2 offer: checked the same way.
        val plain = JSONObject(o.toString()).apply { remove("g"); remove("ch") }
        val s = try { CallLogic.parse(from, plain.toString()) } catch (e: CallLogic.Bad) { return }
        if (!CallLogic.fresh(s.ts, now())) return
        val st = state(group)
        if (g != group || ch != room || st == null || !st.has(from) || s.codec != CallCrypto.CODEC || barred(group, from)) {
            CallManager.host.send(from, CallLogic.simple("decline", call))
            return
        }
        val cur = peers[from]
        if (cur != null) {
            if (cur.s.callId == call) { cur.s.onSignal(s); return }   // a resent offer
            // Both offered (6.3 step 4): the lower chat key's offer wins.
            if (cur.s.outgoing && RoomLogic.offerWins(me(), from) == me()) return
            drop(from)
        }
        val ns = newSession(call, from, false, cur?.retries ?: 0)
        if (micOff()) ns.setMuted(true)
        ns.startIncoming(s)
        ns.answer()   // no ringing inside a room (6.3 step 3)
        publish()
    }

    // ------------------------------------------------------------------ audio for the whole room

    /** One microphone and one speaker for every peer: encode once, seal per peer; decode per peer, mix. */
    private object RoomAudio {
        private var record: AudioRecord? = null
        private var track: AudioTrack? = null
        private var aec: AcousticEchoCanceler? = null
        private var ns: NoiseSuppressor? = null
        private var mic: Thread? = null
        private var player: Thread? = null
        @Volatile private var running = false
        private var oldMode = AudioManager.MODE_NORMAL
        private var audio: AudioManager? = null
        private var focus: android.media.AudioFocusRequest? = null
        private var modeWatch: Any? = null   // an AudioManager.OnModeChangedListener (Android 12 and newer)
        private var recWatch: AudioManager.AudioRecordingCallback? = null
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        /** The focus went for good (music, a clip): Android sends no more focus changes, so the mode is watched. */
        @Volatile private var lostFocus = false
        @Volatile private var ownModeAt = 0L
        private var polledMode = AudioManager.MODE_IN_COMMUNICATION

        private fun setOwnMode(am: AudioManager) {
            ownModeAt = android.os.SystemClock.elapsedRealtime()
            try { am.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
        }
        private fun ours() = android.os.SystemClock.elapsedRealtime() - ownModeAt < SoundHold.OWN_MODE_MS

        /** Another app records for a call (Discord, WhatsApp): the room's own microphone doesn't count. */
        private fun otherVoip(am: AudioManager): Boolean = try {
            val mine = record?.audioSessionId
            am.activeRecordingConfigurations.any {
                it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION && it.clientAudioSessionId != mine
            }
        } catch (_: Exception) { false }

        private fun take(s: SoundHold.Step) {
            lostFocus = s.lost
            when {
                s.held != null -> hold(s.held, s.retake)
                s.retake -> hold(held, true)
            }
        }

        private fun onMode(m: Int) = take(SoundHold.mode(m, ours(), held, lostFocus, Build.VERSION.SDK_INT))

        /**
         * The room asks Android for the sound like a call. A phone call or another app's call takes it: the room pauses,
         * mic and sound, and comes back when it's given back or the mode returns to normal (see [SoundHold]). Another app
         * taking it for good (music, a clip) is let be.
         */
        private val focusChange = AudioManager.OnAudioFocusChangeListener { change ->
            val am = audio
            if (am != null) take(SoundHold.focus(change, am.mode, otherVoip(am), lostFocus, Build.VERSION.SDK_INT))
            Unit
        }

        /** Android 11 and older have no mode listener: the mode is read every second while the room is up. */
        private val modePoll = object : Runnable {
            override fun run() {
                val am = audio ?: return
                if (!running) return
                val m = try { am.mode } catch (_: Exception) { polledMode }
                if (m != polledMode) { polledMode = m; onMode(m) }
                main.postDelayed(this, SoundHold.MODE_POLL_MS)
            }
        }

        @SuppressLint("MissingPermission")
        fun start(c: Context, mic: Boolean = true): Boolean {
            stop()
            val am = c.getSystemService(AudioManager::class.java) ?: return false
            audio = am
            oldMode = am.mode
            lostFocus = false
            setOwnMode(am)
            polledMode = AudioManager.MODE_IN_COMMUNICATION
            AudioRoute.start(am, speaker = true)
            val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(focusChange, android.os.Handler(android.os.Looper.getMainLooper()))
                .build()
            focus = req
            try { am.requestAudioFocus(req) } catch (_: Exception) {}
            // Android 12 and newer also say when a phone call starts and ends, in case the focus change doesn't come.
            if (Build.VERSION.SDK_INT >= 31) try {
                val l = AudioManager.OnModeChangedListener { m -> onMode(m) }
                am.addOnModeChangedListener(c.mainExecutor, l)
                modeWatch = l
            } catch (_: Exception) {}
            // Another app starting or ending a call's recording (it may take the focus before it records).
            try {
                val r = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                        val a = audio ?: return
                        take(SoundHold.recording(otherVoip(a), held, lostFocus))
                    }
                }
                am.registerAudioRecordingCallback(r, main)
                recWatch = r
            } catch (_: Exception) {}
            running = true
            if (Build.VERSION.SDK_INT < 31) main.postDelayed(modePoll, SoundHold.MODE_POLL_MS)
            val minOut = AudioTrack.getMinBufferSize(Opus.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(Opus.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minOut, Opus.FRAME * 2 * 3))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) { null }
            track = t
            if (t != null) player = Thread({ playLoop(t) }, "muninn-room-play").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
            return if (mic) startMic(c) else true
        }

        /** The microphone, its echo canceller and noise removal (also later, once listening only gets the permission). */
        @SuppressLint("MissingPermission")
        fun startMic(c: Context): Boolean {
            if (!running || record != null) return record != null
            val minIn = AudioRecord.getMinBufferSize(Opus.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Opus.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, Opus.FRAME * 2 * 4))
            } catch (e: Exception) { null }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { rec?.release(); return false }
            record = rec
            if (AcousticEchoCanceler.isAvailable()) aec = try { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } } catch (e: Exception) { null }
            if (NoiseSuppressor.isAvailable()) ns = try { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = CallManager.noiseRemoval(c) } } catch (e: Exception) { null }
            mic = Thread({ captureLoop(rec) }, "muninn-room-mic").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
            return true
        }

        /** The phone call ended: Android reset the call audio, so the room's mode, route and focus go back. */
        fun resume() {
            val am = audio ?: return
            setOwnMode(am)
            lostFocus = false
            AudioRoute.reapply()
            focus?.let { try { am.requestAudioFocus(it) } catch (_: Exception) {} }
        }

        /** Speaker on, or the usual order (Bluetooth, wired, earpiece). Any device: [AudioRoute.choose]. */
        fun route(speaker: Boolean) {
            if (audio == null) return
            AudioRoute.choose(if (speaker) "speaker" else AudioRoute.AUTOMATIC)
        }

        private fun captureLoop(rec: AudioRecord) {
            val enc = try { Opus.Encoder() } catch (e: Throwable) { return }
            val pcm = ShortArray(Opus.FRAME)
            val me = CallLogic.Speaking()
            try {
                rec.startRecording()
                while (running) {
                    var got = 0
                    while (got < Opus.FRAME && running) { val n = rec.read(pcm, got, Opus.FRAME - got); if (n <= 0) break; got += n }
                    if (got < Opus.FRAME) continue
                    val off = micOff()
                    val talking = !off && me.feed(pcm, Opus.FRAME, System.currentTimeMillis())
                    if (talking != mySpeaking) { mySpeaking = talking; exec.execute { publish() } }
                    if (off || medias.isEmpty()) continue   // pings go on; no audio while muted, deafened or on a phone call (5.5)
                    val opus = enc.encode(pcm) ?: continue
                    for (m in medias.values) m.sendOpus(opus)
                }
            } catch (_: Exception) {
            } finally { try { rec.stop() } catch (_: Exception) {}; enc.close() }
        }

        private fun playLoop(t: AudioTrack) {
            val decoders = HashMap<String, Opus.Decoder>()
            val talk = HashMap<String, CallLogic.Speaking>()
            val one = ShortArray(Opus.FRAME)
            val mix = IntArray(Opus.FRAME)
            val out = ShortArray(Opus.FRAME)
            try {
                t.play()
                while (running) {
                    mix.fill(0)
                    val nowMs = System.currentTimeMillis()
                    for ((id, m) in medias) {
                        val dec = decoders.getOrPut(id) { try { Opus.Decoder() } catch (e: Throwable) { return } }
                        val n = when (val f = m.nextPlay()) {
                            is Play.Packet -> dec.decode(f.data, one)
                            is Play.Fec -> dec.decode(f.next, one, fec = true)
                            Play.Conceal -> dec.decode(null, one)
                            Play.Silence -> { one.fill(0); Opus.FRAME }
                        }
                        if (n <= 0) one.fill(0)
                        val key = peers.entries.firstOrNull { it.value.s.callId == id }?.key
                        if (key != null) {
                            // Their volume here (cap g2: 0 to 200 %, 0 while muted for me), before the mix. Someone you can't
                            // hear never lights up as speaking.
                            val gain = io.github.ravenor907.muninn.net.PeopleSound.gainOf(key)
                            val s = talk.getOrPut(id) { CallLogic.Speaking() }.feed(one, Opus.FRAME, nowMs) && gain > 0
                            if (s != (speaking[key] == true)) { speaking[key] = s; exec.execute { publish() } }
                            io.github.ravenor907.muninn.net.ModLogic.applyGain(one, Opus.FRAME, gain)
                        }
                        for (i in 0 until Opus.FRAME) mix[i] += one[i].toInt()
                    }
                    // Peers that left: their decoders go.
                    decoders.keys.filter { !medias.containsKey(it) }.forEach { decoders.remove(it)?.close(); talk.remove(it) }
                    for (i in 0 until Opus.FRAME) out[i] = mix[i].coerceIn(-32768, 32767).toShort()
                    // Deafened or on a phone call: nothing heard (the decoders keep going, so it comes back smoothly).
                    if (deafened || held.isNotEmpty()) out.fill(0)
                    t.write(out, 0, Opus.FRAME)   // blocking: the audio clock paces this loop
                }
            } catch (_: Exception) {
            } finally {
                try { t.stop() } catch (_: Exception) {}
                decoders.values.forEach { it.close() }
            }
        }

        fun stop() {
            running = false
            try { mic?.join(500); player?.join(500) } catch (_: InterruptedException) {}
            mic = null; player = null
            try { record?.release() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { aec?.release(); ns?.release() } catch (_: Exception) {}
            record = null; track = null; aec = null; ns = null
            audio?.let { am ->
                if (Build.VERSION.SDK_INT >= 31) (modeWatch as? AudioManager.OnModeChangedListener)?.let { try { am.removeOnModeChangedListener(it) } catch (_: Exception) {} }
                recWatch?.let { try { am.unregisterAudioRecordingCallback(it) } catch (_: Exception) {} }
                focus?.let { try { am.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
                try {
                    AudioRoute.stop()
                    am.mode = oldMode
                } catch (_: Exception) {}
            }
            modeWatch = null; focus = null; recWatch = null
            main.removeCallbacks(modePoll)
            lostFocus = false
            audio = null
        }
    }
}
