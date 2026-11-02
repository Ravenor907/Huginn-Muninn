package io.github.ravenor907.muninn.net.call

import io.github.ravenor907.muninn.net.ChatCrypto
import org.json.JSONObject
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.PrivateKey
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One 1:1 call from start to end (shared/docs/protocol.md 5.2 timing, 5.6 "no path"). Every method runs on
 * [CallManager]'s single call thread ([exec]), so the state needs no locks; slow work (the relay login,
 * the TURN allocation) runs on [work] and hops back.
 */
class CallSession(
    val callId: String,
    val peerKey: String,
    val outgoing: Boolean,
    private val host: Host,
    private val exec: ScheduledExecutorService,
    private val work: java.util.concurrent.ExecutorService,
) {
    /** What a session needs from the app. */
    interface Host {
        val chatPrivate: PrivateKey
        fun send(peerKey: String, json: JSONObject)
        /** The peer's address on this Wi-Fi from Chat's discovery, or null. Never shown anywhere. */
        fun lanAddressOf(peerKey: String): java.net.InetAddress?
        /** A relay login (blocking), or null. */
        fun relayLogin(): TurnCreds.Login?
        fun resolve(host: String): java.net.InetAddress?
        fun internetAllowed(): Boolean
        /** This phone trusts [peerKey] for direct calls (5.8.1): the Family folder, or the per-contact switch. */
        fun trusted(peerKey: String): Boolean = false
        /** This phone can send and show VP8 video (5.8.5). */
        fun videoCodec(): Boolean = false
        fun newMedia(s: CallSession): CallMedia
        fun changed(s: CallSession)
        fun ended(s: CallSession)
    }

    enum class Phase { CALLING, RINGING, INCOMING, CONNECTING, LIVE, RECONNECTING, ENDED }

    @Volatile var phase = if (outgoing) Phase.CALLING else Phase.INCOMING; private set
    @Volatile var connectedAt = 0L; private set
    val startedAt = System.currentTimeMillis()
    @Volatile var muted = false; private set
    @Volatile var peerMuted = false; private set
    @Volatile var peerSpeaking = false; private set
    @Volatile var relay = false; private set
    @Volatile var wifi = false; private set
    @Volatile var bluetooth = false; private set
    /** Why it ended, for the screen and the history: "", "declined", "busy", "noanswer", "nopath", "dropped", "hangup", "missed", "error". */
    @Volatile var endWhy = ""; private set
    /** Something to tell the person while the call goes on (relay trouble), never an address. */
    @Volatile var note = ""; private set
    @Volatile var answered = false; private set
    /** Added to the offer: a voice room's "g" and "ch" (section 6). Set before [startOutgoing]. */
    var extra: JSONObject? = null
    /** Part of a voice room (set by RoomManager): Opus stays at 16 kbps (5.8.4). */
    var inRoom = false
    /** Start with the camera on (a video call). Set before [startOutgoing] or [answer]. */
    @Volatile var wantVideo = false
    /** This side speaks c2 (5.8). */
    var c2Local = true

    // What the other side said (5.8): it speaks c2, has VP8, its camera is on, it offers the direct path.
    @Volatile var peerC2 = false; private set
    @Volatile var peerVp8 = false; private set
    @Volatile var peerVideo = false; private set
    private var peerP2p = false
    private var trusted = false
    /** Both trust each other: the direct path is tried (5.8.2). */
    @Volatile var directOn = false; private set
    @Volatile var direct = false; private set
    private var peerDirect: List<InetSocketAddress> = emptyList()
    private var myDirect: List<CallLogic.Direct> = emptyList()
    val bothC2: Boolean get() = c2Local && peerC2
    /** Video can go both ways: both c2, both VP8. */
    val videoBoth: Boolean get() = bothC2 && peerVp8 && host.videoCodec()
    /** media() for the video engine; null before the keys exist and after the end. */
    val mediaNow: CallMedia? get() = media

    private var eph: KeyPair? = null
    private var myEph = ""
    private var theirEph = ""
    private var offerJson: JSONObject? = null
    private var answerJson: JSONObject? = null
    private var peerRelay: InetSocketAddress? = null
    private var peerLanPort: Int? = null
    private var myRelay: InetSocketAddress? = null
    private var media: CallMedia? = null
    private var gotMedia = false
    private var answeredAt = 0L
    private var deadSince = 0L
    private var lastCands = 0L
    private val timers = ArrayList<ScheduledFuture<*>>()

    private fun later(ms: Long, f: () -> Unit) { timers += exec.schedule(Runnable { if (phase != Phase.ENDED) f() }, ms, TimeUnit.MILLISECONDS) }
    private fun every(ms: Long, f: () -> Unit) { timers += exec.scheduleWithFixedDelay({ if (phase != Phase.ENDED) f() }, ms, ms, TimeUnit.MILLISECONDS) }
    private fun set(p: Phase) { if (phase != Phase.ENDED) { phase = p; host.changed(this) } }

    private fun lanPortIfClose(): Int? = if (host.lanAddressOf(peerKey) != null) media?.lanPort else null

    /** Put this phone's internet addresses in what it sends: only to someone trusted, and as the callee only when asked (5.8.1). */
    private fun shareDirect() = c2Local && trusted && host.internetAllowed() && (outgoing || peerP2p)
    private fun extras() = if (!c2Local) null else CallLogic.Extras(host.videoCodec(), wantVideo, shareDirect())
    private fun directCands() = if (shareDirect()) myDirect else emptyList()

    private fun takeC2(s: CallLogic.Signal) {
        peerC2 = s.c2; peerVp8 = s.c2 && s.vp8; peerVideo = s.c2 && s.video; peerP2p = s.c2 && s.p2p
    }

    // ------------------------------------------------------------------ caller

    fun startOutgoing() {
        val kp = CallCrypto.newEphemeral(); eph = kp
        myEph = ChatCrypto.b64(kp.public.encoded)
        trusted = host.trusted(peerKey)
        if (extra?.has("g") == true) inRoom = true
        media = host.newMedia(this)
        val sig = CallCrypto.sign(host.chatPrivate, "offer", callId, myEph)
        offerJson = CallLogic.offer(callId, myEph, sig, System.currentTimeMillis(), null, lanPortIfClose(), host.bluetoothFor(), extras())
        extra?.let { e -> for (k in e.keys()) offerJson!!.put(k, e.get(k)) }
        host.send(peerKey, offerJson!!)
        // Resend the offer every 3 s until the other side says anything; give up after 45 s.
        val resend = exec.scheduleWithFixedDelay({ if (phase == Phase.CALLING) offerJson?.let { host.send(peerKey, it) } },
            CallLogic.OFFER_EVERY_MS, CallLogic.OFFER_EVERY_MS, TimeUnit.MILLISECONDS)
        timers += resend
        later(CallLogic.RING_MS) { if (phase == Phase.CALLING || phase == Phase.RINGING) end("noanswer", send = true) }
        startRelay()
        startProbe()
        every(500) { watch() }
    }

    // ------------------------------------------------------------------ callee

    private var offerTs = 0L
    private var theirBle = false

    /** First sight of an offer that is already checked (signature, freshness). Starts ringing. */
    fun startIncoming(s: CallLogic.Signal) {
        theirEph = s.eph; offerTs = s.ts; theirBle = s.ble
        peerRelay = s.relay; peerLanPort = s.lanPort
        takeC2(s); peerDirect = s.direct
        trusted = host.trusted(peerKey)
        host.send(peerKey, CallLogic.simple("ring", callId))
        later(CallLogic.RING_MS) { if (phase == Phase.INCOMING) end("missed", send = false) }
        // Get the relay (and, for someone trusted, the STUN answer) ready while it rings, so answering doesn't wait
        // for it. Nothing is sent to them until the person answers; declining or missing the call releases it.
        if (host.internetAllowed()) { media = host.newMedia(this); startRelay(); startProbe() }
    }

    /** The person tapped Answer. */
    fun answer() {
        if (phase != Phase.INCOMING) return
        answered = true
        val kp = CallCrypto.newEphemeral(); eph = kp
        myEph = ChatCrypto.b64(kp.public.encoded)
        val root = try { CallCrypto.root(kp.private, theirEph, callId, theirEph, myEph) } catch (e: Exception) { end("error", send = true); return }
        val m = media ?: host.newMedia(this).also { media = it }
        m.setC2(bothC2, inRoom)
        directOn = shareDirect() && peerC2 && peerP2p
        m.begin(CallCrypto.Keys(root)); root.fill(0)
        applyPeer()
        val sig = CallCrypto.sign(host.chatPrivate, "answer", callId, myEph)
        // The relay is usually up by now (it started while ringing): the answer carries it.
        answerJson = CallLogic.answer(callId, myEph, sig, myRelay, lanPortIfClose(), host.bluetoothFor(), extras(), directCands())
        host.send(peerKey, answerJson!!)
        answeredAt = System.currentTimeMillis()
        // Resend the answer every 2 s until the first authentic media packet, for up to 15 s.
        val resend = exec.scheduleWithFixedDelay({ if (!gotMedia && System.currentTimeMillis() - answeredAt < CallLogic.ANSWER_FOR_MS) answerJson?.let { host.send(peerKey, it) } },
            CallLogic.ANSWER_EVERY_MS, CallLogic.ANSWER_EVERY_MS, TimeUnit.MILLISECONDS)
        timers += resend
        set(Phase.CONNECTING)
        if (!m.startAudio(video = wantVideo || peerVideo)) note = "The microphone isn't available"
        startRelay()
        every(500) { watch() }
    }

    fun decline() { if (phase == Phase.INCOMING) end("declined", send = true, t = "decline") }

    // ------------------------------------------------------------------ both

    /** A checked message for this call from the peer. */
    fun onSignal(s: CallLogic.Signal) {
        when (s.t) {
            "offer" -> if (!outgoing) {
                // A retry: ring again (or repeat the answer), and take any newer candidates.
                if (s.relay != null) peerRelay = s.relay
                if (s.lanPort != null) peerLanPort = s.lanPort
                if (s.direct.isNotEmpty()) peerDirect = (peerDirect + s.direct).distinct().take(4)
                if (answered) { applyPeer(); answerJson?.let { host.send(peerKey, it) } } else host.send(peerKey, CallLogic.simple("ring", callId))
            }
            "ring" -> if (outgoing && phase == Phase.CALLING) set(Phase.RINGING)
            "answer" -> if (outgoing && (phase == Phase.CALLING || phase == Phase.RINGING)) onAnswer(s)
                // A resent answer: the same keys, maybe with the relay that wasn't up the first time.
                else if (outgoing && answered && s.eph == theirEph) { takeCands(s); applyPeer() }
            "cands" -> { takeCands(s); applyPeer() }
            "mute" -> { peerMuted = s.on; host.changed(this) }
            "decline" -> if (outgoing) end("declined", send = false)
            "busy" -> if (outgoing && !answered) end("busy", send = false)
            "hangup" -> end(if (phase == Phase.INCOMING) "missed" else if (s.why == "hangup") "hangup" else s.why, send = false)
        }
    }

    private fun takeCands(s: CallLogic.Signal) {
        if (s.relay != null) peerRelay = s.relay
        if (s.lanPort != null) peerLanPort = s.lanPort
        if (s.direct.isNotEmpty()) peerDirect = (peerDirect + s.direct).distinct().take(4)
    }

    private fun onAnswer(s: CallLogic.Signal) {
        val kp = eph ?: return
        theirEph = s.eph
        val root = try { CallCrypto.root(kp.private, theirEph, callId, myEph, theirEph) } catch (e: Exception) { end("error", send = true); return }
        answered = true
        s.relay?.let { peerRelay = it }; s.lanPort?.let { peerLanPort = it }; theirBle = s.ble
        takeC2(s); peerDirect = s.direct
        // Direct only when both trust each other: we asked (p2p in the offer), they answered with theirs.
        directOn = shareDirect() && peerC2 && peerP2p
        val m = media ?: return
        m.setC2(bothC2, inRoom)
        m.begin(CallCrypto.Keys(root)); root.fill(0)
        applyPeer()
        answeredAt = System.currentTimeMillis()
        set(Phase.CONNECTING)
        if (!m.startAudio(video = wantVideo || peerVideo)) note = "The microphone isn't available"
    }

    /** Tells media where the peer is: its relayed address, and its Wi-Fi address when close by. */
    private fun applyPeer() {
        val m = media ?: return
        if (host.internetAllowed()) m.setPeerRelay(peerRelay)
        val lanIp = host.lanAddressOf(peerKey)
        val lp = peerLanPort
        if (lanIp != null && lp != null) m.setPeerLan(InetSocketAddress(lanIp, lp))
        if (directOn) m.enableDirect(peerDirect)
    }

    private var relayStarted = false
    private var relayBusy = false
    private var relayTries = 0
    private var restarts = 0
    private var restartedAt = 0L
    private var candsQueued = false
    private var relayOkAt = 0L

    private fun startRelay(again: Boolean = false) {
        if (!host.internetAllowed() || (relayStarted && !again) || relayBusy) return
        val m = media ?: return
        relayStarted = true
        relayBusy = true
        relayTries++
        val askedAt = System.currentTimeMillis()
        work.execute {
            val login = try { host.relayLogin() } catch (e: Exception) { null }
            // Why the login fetch failed, read now (before another fetch can change it).
            val loginProblem = if (login == null) TurnCreds.lastProblem else ""
            val r = login?.let { m.startRelay(it, host::resolve) }
            exec.execute {
                relayBusy = false
                if (phase == Phase.ENDED) return@execute
                if (r == null) {
                    note = if (login == null) loginProblem.ifEmpty { "The internet relay isn't available" } else TurnClient.lastProblem.ifEmpty { "Couldn't reach the internet relay" }
                    host.changed(this)
                    // One more try a little later (a busy network, a server that didn't answer in time), also when the
                    // login fetch failed, unless the relay is resting or used up for today.
                    // (After a restart the old relay may still be known: what counts is that nothing worked since this try.)
                    if (relayTries < 2 && (login != null || CallLogic.retryLogin(loginProblem))) later(2_000) { if (relayOkAt < askedAt) startRelay(again = true) }
                    return@execute
                }
                if (note.isNotEmpty()) { note = ""; host.changed(this) }
                myRelay = r
                relayOkAt = System.currentTimeMillis()
                refreshCands()
            }
        }
    }

    /** The STUN answer for the direct path (5.8.2), only in a call with someone trusted. */
    private fun startProbe() {
        if (!shareDirect()) return
        val m = media ?: return
        work.execute {
            val mapped = m.probeStun(resolve = host::resolve)
            val port = m.lanPort
            val list = ArrayList<CallLogic.Direct>()
            if (mapped != null) list += CallLogic.Direct("srflx", mapped)
            if (port != null) for (a in globalIpv6()) list += CallLogic.Direct("host", InetSocketAddress(a, port))
            exec.execute {
                if (phase == Phase.ENDED || list.isEmpty()) return@execute
                myDirect = list
                refreshCands()
            }
        }
    }

    /** Puts what is ready now in the resent offer or answer, and tells the peer if it has heard from us already. */
    private fun refreshCands() {
        val lp = lanPortIfClose()
        val c = CallLogic.cands(myRelay, lp, directCands())
        offerJson?.put("cands", c)
        answerJson?.put("cands", c)
        if (lp != null) { offerJson?.put("lp", lp); answerJson?.put("lp", lp) }
        // Still ringing here: the answer will carry it. Otherwise tell the peer now.
        if (outgoing || answered) sendCands()
    }

    /** At most one "cands" a second; a later one waits instead of being lost (5.8.8). */
    private fun sendCands() {
        val now = System.currentTimeMillis()
        if (now - lastCands < 1_000) {
            if (!candsQueued) { candsQueued = true; later(1_000 - (now - lastCands)) { candsQueued = false; sendCands() } }
            return
        }
        lastCands = now
        host.send(peerKey, CallLogic.candsMsg(callId, myRelay, lanPortIfClose(), directCands()))
    }

    /** The "Direct calls" switch went off during the call: no more addresses out, the direct path dropped now. */
    fun untrust() {
        if (!trusted) return
        trusted = false
        directOn = false
        peerDirect = emptyList()
        media?.disableDirect()
        if (outgoing || answered) refreshCands()   // the next cands and resends carry no srflx or host
        host.changed(this)
    }

    /** Every path dead for 4 s: the network may have changed. A new relay allocation and STUN answer (5.8.2). */
    private fun restart() {
        val now = System.currentTimeMillis()
        if (restarts >= 3 || relayBusy || now - restartedAt < 6_000) return
        restarts++; restartedAt = now
        relayTries = 0   // startRelay counts this one, so a failure of it gets its own second try
        startRelay(again = true)
        startProbe()
    }

    private fun globalIpv6(): List<java.net.InetAddress> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet6Address>()
            .filter { !CallLogic.isLocal(it) }.map { java.net.InetAddress.getByAddress(it.address) }.distinct().take(2)
    } catch (e: Exception) { emptyList() }

    // Events from media (any thread): hop onto the call thread.
    fun mediaFirst() = exec.execute { if (!gotMedia) { gotMedia = true; connectedAt = System.currentTimeMillis(); set(Phase.LIVE) } }
    fun mediaPaths(r: Boolean, w: Boolean, b: Boolean) = exec.execute { relay = r; wifi = w; bluetooth = b; host.changed(this) }
    fun mediaDirect(on: Boolean) = exec.execute { direct = on; host.changed(this) }
    fun mediaSpeaking(on: Boolean) = exec.execute { peerSpeaking = on; host.changed(this) }

    private fun watch() {
        val m = media ?: return
        val now = System.currentTimeMillis()
        when (phase) {
            Phase.CONNECTING -> if (!m.anyLive() && answeredAt > 0 && now - answeredAt > CallLogic.NO_PATH_MS) end("nopath", send = true)
            Phase.LIVE -> if (!m.anyLive()) { deadSince = now; set(Phase.RECONNECTING) }
            Phase.RECONNECTING -> if (m.anyLive()) { deadSince = 0; set(Phase.LIVE) } else if (now - deadSince > CallLogic.DROPPED_MS) end("dropped", send = true)
                else if (now - deadSince > 4_000) restart()
            else -> {}
        }
    }

    fun setMuted(on: Boolean) {
        muted = on; media?.muted = on
        if (answered) host.send(peerKey, CallLogic.mute(callId, on))
        host.changed(this)
    }

    fun setSpeaker(on: Boolean) { media?.setSpeaker(on); host.changed(this) }
    fun setOutput(id: String) { media?.setOutput(id); host.changed(this) }
    val speaker: Boolean get() = media?.speaker == true

    fun hangup() = end(if (phase == Phase.INCOMING) "declined" else "hangup", send = true, t = if (phase == Phase.INCOMING) "decline" else "hangup")

    /**
     * Ends the call. [send]: tell the peer, 3 times over 2 s (5.2). [t]: "hangup", or "decline"/"busy".
     */
    fun end(why: String, send: Boolean, t: String = "hangup") {
        if (phase == Phase.ENDED) return
        endWhy = why
        phase = Phase.ENDED
        timers.forEach { it.cancel(false) }; timers.clear()
        if (send) {
            val msg = if (t == "hangup") CallLogic.hangup(callId, if (why in CallLogic.WHY) why else "hangup") else CallLogic.simple(t, callId)
            host.send(peerKey, msg)
            exec.schedule(Runnable { host.send(peerKey, msg) }, 1, TimeUnit.SECONDS)
            exec.schedule(Runnable { host.send(peerKey, msg) }, 2, TimeUnit.SECONDS)
        }
        media?.stop(); media = null
        eph = null
        host.ended(this)
    }

    private fun Host.bluetoothFor(): Boolean = CallManager.bluetooth?.canCarry(peerKey) == true
}
