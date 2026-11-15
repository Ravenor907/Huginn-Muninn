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
import android.os.PowerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val KEEP_FRAMES = 64
private const val MAX_RESEND = 120
private const val KEY_GAP_MS = 300L

/**
 * The media side of one call: sealed packets (5.4) on every live path at once, the audio (5.5), and between two c2
 * apps the receiver reports, video fragments and their repairs (5.8).
 *
 * Paths, all in parallel; the receiver's replay window drops the copies (5.4):
 *  - RELAY: the internet through this phone's TURN allocation to the peer's relayed address.
 *  - WIFI: straight to the peer's address on the same Wi-Fi (from Chat's Wi-Fi discovery), own UDP socket.
 *  - DIRECT: only when both people trust each other (5.8.2): straight to the peer's internet address from the same
 *    socket, after both pinged each other's candidates (hole punching).
 *  - BLUETOOTH: through [CallBluetooth] when someone provides it.
 * The relay rests (pings only) while Wi-Fi or Direct carried something within 750 ms (5.8.3); video never goes
 * over Bluetooth.
 */
class CallMedia(
    private val context: Context,
    /** True when this phone made the call: it sends offerer-to-answerer. */
    private val offerer: Boolean,
    callId: String,
    private val peerKey: String,
    private val bluetooth: CallBluetooth?,
    private val events: Events,
) {
    interface Events {
        /** The first authentic packet arrived (the callee stops resending its answer). */
        fun onFirstMedia()
        fun onPaths(relay: Boolean, wifi: Boolean, bluetooth: Boolean)
        fun onSpeaking(peer: Boolean)
        fun onDirect(live: Boolean) {}
        /** A whole VP8 frame from the peer, in order (any thread). */
        fun onVideoFrame(stream: Int, data: ByteArray, key: Boolean, rotation: Int) {}
        /** The peer lost something: the next frame of [stream] should be a keyframe. */
        fun onKeyframeWanted(stream: Int) {}
        /** The peer's camera or screen share went on or off (from its reports). */
        fun onPeerVideo(camera: Boolean, screen: Boolean) {}
        /** A free path came or went: the video may use another size (5.8.6). */
        fun onProfile() {}
    }

    private val tag = CallCrypto.tag(callId)
    // Set by begin() once both ephemeral keys are known; until then nothing is sent or opened.
    @Volatile private var keys: CallCrypto.Keys? = null
    @Volatile private var receiver: CallCrypto.Receiver? = null
    private val random = SecureRandom()
    private val jitter = Jitter()
    private val speaking = CallLogic.Speaking()
    private val paths = CopyOnWriteArrayList<CallLogic.Path>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-call-net").apply { isDaemon = true } }
    private var ticker: ScheduledFuture<*>? = null
    @Volatile private var seq = 0L
    private val started = System.currentTimeMillis()
    @Volatile private var stopped = false
    @Volatile private var gotFirst = false
    @Volatile var muted = false
    /** The sound comes out of the phone's own speaker (see [AudioRoute]). */
    val speaker: Boolean get() = AudioRoute.onSpeaker
    private val pings = HashMap<String, Long>()
    @Volatile var rttMs = 0; private set

    // ------------------------------------------------------------------ c2 (5.8)

    /** Both apps speak c2: receiver reports, Opus at 24 or 32 kbps (16 in a [room]), video. */
    @Volatile var c2 = false; private set
    /** Group video (rooms): video goes only on Wi-Fi or Direct, never through the relay. */
    @Volatile var videoNoRelay = false
    @Volatile var room = false; private set
    fun setC2(on: Boolean, inRoom: Boolean) { c2 = on; room = inRoom }
    @Volatile var peerLoss = 0; private set
    private var lastReport = 0L
    private var lastSlot = -1L
    private var audioDue = 0; private var audioLost = 0; private var videoDue = 0; private var videoLost = 0
    private var bytesIn = 0L
    private val counters = Any()

    // ------------------------------------------------------------------ transports

    @Volatile private var turn: TurnClient? = null
    /** What this call moved through the relay, both ways: it counts toward the same free month as relayed files. */
    private val relayBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val peerRelay = CallLogic.RelayPeer()
    /** The Wi-Fi (and Direct) socket: open for the whole call, bound to a random port on all interfaces. */
    val lan: DatagramSocket? = try { DatagramSocket(0) } catch (e: Exception) { null }
    val lanPort: Int? get() = lan?.localPort

    /** Allocates on the relay (blocking). The relayed address, or null if no relay could be had. A second call replaces the first. */
    fun startRelay(login: TurnCreds.Login, resolve: (String) -> java.net.InetAddress?): InetSocketAddress? {
        if (stopped) return null
        // Only peers we gave a permission can reach our allocation, and every packet must still open
        // (tag, AEAD) before it counts, so the source isn't matched here: a relay between sites may
        // translate the peer's address (see CallLogic.RelayPeer).
        val self = arrayOfNulls<TurnClient>(1)
        val t = TurnClient(login, resolve) { from, data -> if (turn === self[0]) incoming(data, CallLogic.Kind.RELAY, from) }
        self[0] = t
        if (!t.start()) { t.close(); return null }
        if (stopped) { t.close(); return null }
        val old = turn
        turn = t
        old?.let { try { it.close() } catch (_: Exception) {} }
        renewing?.let { renewing = null; try { it.close() } catch (_: Exception) {} }   // a renewal underway is no longer needed
        peerRelay.forgetSeen()
        peerRelay.advertised?.let { t.permit(it); addPath(CallLogic.Kind.RELAY, it) }
        return t.relayed
    }

    /** The allocation a renewal made (relay.md "Renewal"): it takes media from the peer at once, and over from [turn] in [finishRenew]. */
    @Volatile private var renewing: TurnClient? = null

    /**
     * Renewal (blocking): a second allocation with a fresh login, before the one in use ends. Media still goes out through
     * the old one until [finishRenew], while the peer learns the new address; what arrives on either counts. Its relayed
     * address, or null.
     */
    fun renewRelay(login: TurnCreds.Login, resolve: (String) -> java.net.InetAddress?): InetSocketAddress? {
        if (stopped || turn == null) return null
        val self = arrayOfNulls<TurnClient>(1)
        val t = TurnClient(login, resolve) { from, data -> if (turn === self[0] || renewing === self[0]) incoming(data, CallLogic.Kind.RELAY, from) }
        self[0] = t
        if (!t.start()) { t.close(); return null }
        if (stopped) { t.close(); return null }
        peerRelay.advertised?.let { t.permit(it) }
        renewing?.let { try { it.close() } catch (_: Exception) {} }
        renewing = t
        // Round 18 (R1): the peer's address may have changed while this allocation was being made (both renewing at
        // about the same time): let the newest one in too.
        peerRelay.advertised?.let { t.permit(it) }
        return t.relayed
    }

    /** The renewal's allocation now carries the media; the old one is let go. */
    fun finishRenew() {
        val t = renewing ?: return
        // Round 18 (R1): before it carries media, the new allocation lets in every relayed address of the peer, the
        // one that came during the handover included (it was permitted then too, see setPeerRelay).
        for (a in CallLogic.handoverPermits(peerRelay)) t.permit(a)
        renewing = null
        val old = turn
        turn = t
        old?.let { try { it.close() } catch (_: Exception) {} }
        peerRelay.forgetSeen()
        // And after: the advertised address again on the allocation now in use (a no-op when it already has it).
        peerRelay.advertised?.let { t.permit(it) }
    }

    /** The peer's relayed address (from their offer, answer or cands). Never anything but a relay. */
    fun setPeerRelay(a: InetSocketAddress?) {
        if (!peerRelay.advertise(a) || a == null) return
        paths.removeAll { it.kind == CallLogic.Kind.RELAY }
        // Round 18 (R1): the allocation in use and a renewal's new one both let it in, so a handover on both sides at
        // about the same time never leaves the new allocation without a permission for the peer's new address.
        for (t in CallLogic.permitOn(turn, renewing)) t.permit(a)
        if (turn != null) addPath(CallLogic.Kind.RELAY, a)
    }

    /** Round 18 (relay.md "On demand"): when the relay last carried media (not pings or pongs); 0 never. */
    @Volatile var relayUsedAt = 0L; private set

    /** Round 18: the login wasn't renewed (the relay wasn't carrying the call): both allocations are let go before it ends. */
    fun dropRelay() {
        val t = turn; val r = renewing
        turn = null; renewing = null
        paths.removeAll { it.kind == CallLogic.Kind.RELAY }
        peerRelay.forgetSeen()
        t?.let { try { it.close() } catch (_: Exception) {} }
        r?.let { try { it.close() } catch (_: Exception) {} }
    }

    val hasRelay: Boolean get() = turn != null

    /** The peer on the same Wi-Fi: its discovered address and the port it put in "lp". */
    fun setPeerLan(a: InetSocketAddress?) {
        if (a == null || paths.any { it.kind == CallLogic.Kind.WIFI && it.addr == a }) return
        addPath(CallLogic.Kind.WIFI, a)
    }

    // ---- Direct (5.8.2)

    @Volatile var directOn = false; private set
    @Volatile private var directSince = 0L

    /** Both trust each other: ping [cands] (their srflx and host candidates) from the call socket. More may follow. */
    /** "Direct calls" turned off mid-call: nothing more goes to their address, nothing from it counts. */
    fun disableDirect() {
        directOn = false
        paths.removeAll { it.kind == CallLogic.Kind.DIRECT }
    }

    fun enableDirect(cands: List<InetSocketAddress>) {
        if (!directOn) directSince = System.currentTimeMillis()
        directOn = true
        for (a in cands) if (paths.count { it.kind == CallLogic.Kind.DIRECT } < 4 && paths.none { it.kind == CallLogic.Kind.DIRECT && it.addr == a })
            paths += CallLogic.Path(CallLogic.Kind.DIRECT, a)
    }

    @Volatile private var stunServer: InetSocketAddress? = null
    @Volatile private var stunTid: ByteArray? = null
    @Volatile private var stunGot: InetSocketAddress? = null
    private val stunLock = Object()

    /**
     * Asks the STUN server what address the call socket has on the internet (blocking, at most about 3 s): one Binding
     * request, resent after 300, 900 and 1800 ms. Null when there was no usable answer. Only in a call with someone trusted.
     * Each server in the list gets that long; the next one is asked if one gives no answer.
     */
    fun probeStun(servers: List<Pair<String, Int>> = Stun.SERVERS, resolve: (String) -> java.net.InetAddress?): InetSocketAddress? {
        for ((host, port) in servers) {
            if (stopped) break
            probeOne(host, port, resolve)?.let { return it }
        }
        return null
    }

    private fun probeOne(host: String, port: Int, resolve: (String) -> java.net.InetAddress?): InetSocketAddress? {
        val s = lan ?: return null
        val ip = try { resolve(host) } catch (e: Exception) { null } ?: return null
        val server = InetSocketAddress(ip, port)
        val tid = Stun.newTid()
        synchronized(stunLock) { stunGot = null; stunServer = server; stunTid = tid }
        val req = Stun.encode(Stun.type(Stun.BINDING, Stun.REQUEST), tid, emptyList(), fingerprint = true)
        val deadline = System.currentTimeMillis() + 3_000
        for (wait in longArrayOf(300, 600, 900, 1200)) {
            if (stopped) break
            try { s.send(DatagramPacket(req, req.size, server)) } catch (_: Exception) {}
            synchronized(stunLock) {
                val until = minOf(deadline, System.currentTimeMillis() + wait)
                while (stunGot == null && System.currentTimeMillis() < until) stunLock.wait(maxOf(1, until - System.currentTimeMillis()))
                stunGot?.let { stunTid = null; return it }
            }
            if (System.currentTimeMillis() >= deadline) break
        }
        synchronized(stunLock) { stunTid = null }
        return null
    }

    private fun onStun(b: ByteArray, len: Int, from: InetSocketAddress?) {
        val server = stunServer ?: return
        if (from == null || from.port != server.port || !sameIp(from.address, server.address)) return
        val m = Stun.decode(b, len) ?: return
        synchronized(stunLock) {
            val tid = stunTid ?: return
            if (!m.tid.contentEquals(tid) || Stun.classOf(m.type) != Stun.SUCCESS || Stun.methodOf(m.type) != Stun.BINDING) return
            val a = m.xorAddress(Stun.A_XOR_MAPPED_ADDRESS) ?: return
            if (!CallLogic.usable(a.address)) return
            stunGot = a
            stunLock.notifyAll()
        }
    }

    private fun sameIp(a: java.net.InetAddress, b: java.net.InetAddress): Boolean = v4(a).contentEquals(v4(b))
    private fun v4(a: java.net.InetAddress): ByteArray {
        val b = a.address
        // ::ffff:a.b.c.d is the IPv4 address a.b.c.d (a dual-stack socket reports it that way)
        if (b.size == 16 && (0 until 10).all { b[it].toInt() == 0 } && b[10].toInt() == -1 && b[11].toInt() == -1) return b.copyOfRange(12, 16)
        return b
    }

    private fun addPath(kind: CallLogic.Kind, a: InetSocketAddress?) {
        // One relay path: its sends go to every relayed address in peerRelay.targets().
        if (kind == CallLogic.Kind.RELAY && paths.any { it.kind == kind }) return
        paths += CallLogic.Path(kind, a)
    }

    /** Both ephemeral keys are known: start sending and receiving. */
    fun begin(k: CallCrypto.Keys) {
        if (keys != null || stopped) return
        keys = k
        receiver = CallCrypto.Receiver(k, !offerer, tag)
        bluetooth?.let { bt ->
            bt.onMedia { from, p -> if (from == peerKey) incoming(p, CallLogic.Kind.BLUETOOTH, null) }
            addPath(CallLogic.Kind.BLUETOOTH, null)
        }
        lastReport = System.currentTimeMillis()
        ticker = timer.scheduleWithFixedDelay({ try { tick() } catch (_: Exception) {} }, 0, 25, TimeUnit.MILLISECONDS)
    }

    init { start() }

    private fun start() {
        lan?.let { s -> Thread({ lanLoop(s) }, "muninn-call-lan").apply { isDaemon = true; start() } }
    }

    private fun lanLoop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        while (!stopped) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: Exception) { if (stopped) return else continue }
            if (p.length < 1) continue
            val from = p.socketAddress as? InetSocketAddress
            if ((buf[0].toInt() and 0xFF) != CallCrypto.VERSION) { if (Stun.isStun(buf, p.length)) onStun(buf, p.length, from); continue }
            // The peer's Wi-Fi address (or a second interface on the same network) is Wi-Fi; any other source is the
            // Direct path, only when both trust each other.
            val kind = if (directOn && from != null && paths.none { it.kind == CallLogic.Kind.WIFI && it.addr?.address?.let { a -> sameIp(a, from.address) } == true } &&
                CallLogic.usable(from.address)) CallLogic.Kind.DIRECT else CallLogic.Kind.WIFI
            incoming(buf.copyOf(p.length), kind, from)
        }
    }

    /** Every packet from every path comes through here; the receiver drops copies and forgeries. */
    private fun incoming(data: ByteArray, kind: CallLogic.Kind, from: InetSocketAddress?) {
        if (stopped) return
        val rx = receiver ?: return
        val o = try { rx.open(data) } catch (e: CallCrypto.Dropped) {
            // A copy that already came on another path still proves this path works.
            // Only on a path we already have: a copy replayed from elsewhere teaches no address (5.8.2).
            if (e.why == "duplicate") markLive(kind, from, fresh = false)
            return
        }
        markLive(kind, from, fresh = true)
        // Authentic, relayed from an address the peer didn't advertise: answer there too.
        if (kind == CallLogic.Kind.RELAY && peerRelay.heard(from)) from?.let { turn?.permit(it); renewing?.permit(it) }
        if (!gotFirst) { gotFirst = true; firstMediaAt = System.currentTimeMillis(); events.onFirstMedia() }
        synchronized(counters) { bytesIn += data.size }
        if (kind == CallLogic.Kind.RELAY) relayBytes.addAndGet(data.size + RELAY_OVERHEAD.toLong())
        when (o.type) {
            CallCrypto.AUDIO -> { health.came.incrementAndGet(); countAudio(o.ts); jitter.put(o.ts, o.payload) }
            CallCrypto.PING -> path(kind, from)?.let { send(CallCrypto.PONG, o.payload, only = it) }
            CallCrypto.PONG -> synchronized(pings) { pings.remove(CallCrypto.hex(o.payload)) }?.let { sentAt ->
                val r = System.currentTimeMillis() - sentAt
                path(kind, from)?.let { it.rttMs = r; it.lastPong = System.currentTimeMillis() }
                rttMs = r.toInt()
            }
            CallCrypto.VIDEO -> if (c2) onVideo(o.payload)
            CallCrypto.REPORT -> if (c2) VideoWire.decodeReport(o.payload)?.let { onReport(it) }
            // A keyframe that just went out answers it already (requests cross on the way).
            CallCrypto.KEY_REQUEST -> if (c2 && o.payload.isNotEmpty() && (o.payload[0].toInt() and 0xFF) <= 1 &&
                System.currentTimeMillis() - keySentAt[o.payload[0].toInt()] >= KEY_GAP_MS) {
                val s = o.payload[0].toInt()
                if (s == VideoWire.CAMERA && sending[s]) onCameraKeyAsked()
                events.onKeyframeWanted(s)
            }
            CallCrypto.NACK -> if (c2) VideoWire.decodeNack(o.payload)?.let { onNack(it) }
        }
        forgetOldKeys()
    }

    /** Loss for the report (5.8.4): gaps of up to 10 frames are lost packets, longer ones a DTX pause. */
    private fun countAudio(ts: Long) = synchronized(counters) {
        val slot = (ts + 10) / 20
        if (lastSlot >= 0) {
            val d = slot - lastSlot
            when {
                d in 1..10 -> { audioDue += d.toInt(); audioLost += (d - 1).toInt() }
                d > 10 -> audioDue += 1
                audioLost > 0 -> audioLost--   // one counted lost came late after all
            }
        }
        if (slot > lastSlot) lastSlot = slot
    }

    private fun path(kind: CallLogic.Kind, from: InetSocketAddress?) =
        paths.firstOrNull { it.kind == kind && ((kind != CallLogic.Kind.WIFI && kind != CallLogic.Kind.DIRECT) || it.addr == from) }

    private fun markLive(kind: CallLogic.Kind, from: InetSocketAddress?, fresh: Boolean) {
        CallLogic.markLive(paths, kind, from, fresh, System.currentTimeMillis())
    }

    /** 5.3: drop keys and chain values the window has moved past in both directions. */
    private fun forgetOldKeys() {
        val k = keys ?: return; val rx = receiver ?: return
        k.forgetBefore(minOf(CallCrypto.epochOf(seq), rx.neededEpoch()))
    }

    private var lastReportText = ""
    @Volatile private var wasFree = false
    private var wasDirect = false

    private fun tick() {
        if (stopped) return
        val now = System.currentTimeMillis()
        for (p in paths) {
            // Direct candidates are pinged from when the direct path was turned on, not from the start of the call.
            val due = if (p.kind == CallLogic.Kind.DIRECT && p.lastIn == 0L) {
                val age = now - maxOf(p.added, directSince)
                now - p.lastPing >= when { age < 2_000 -> 50L; age < 15_000 -> 250L; else -> 2_000L }
            } else CallLogic.pingDue(p, now)
            if (!due) continue
            p.lastPing = now
            val nonce = ByteArray(8).also { random.nextBytes(it) }
            synchronized(pings) { if (pings.size > 64) pings.clear(); pings[CallCrypto.hex(nonce)] = now }
            send(CallCrypto.PING, nonce, only = p)
        }
        val r = paths.any { it.kind == CallLogic.Kind.RELAY && it.live(now) }
        val w = paths.any { it.kind == CallLogic.Kind.WIFI && it.live(now) }
        val b = paths.any { it.kind == CallLogic.Kind.BLUETOOTH && it.live(now) }
        val d = paths.any { it.kind == CallLogic.Kind.DIRECT && it.live(now) }
        val report = "$r$w$b"
        if (report != lastReportText) { lastReportText = report; events.onPaths(r, w, b) }
        if (d != wasDirect) { wasDirect = d; events.onDirect(d) }
        // The video size follows a free path that answered pings within 3 s (steadier than the 750 ms rule).
        val free = paths.any { (it.kind == CallLogic.Kind.WIFI || it.kind == CallLogic.Kind.DIRECT) && it.lastPong > 0 && now - it.lastPong < 3_000 }
        var changed = false
        if (free != wasFree) { wasFree = free; changed = true }
        // The battery, every 10 seconds: low (or Android's Battery Saver) makes the camera's video lighter.
        if (now - lowPowerAt >= 10_000) {
            lowPowerAt = now
            val lp = lowPowerNow()
            if (lp != lowPower) { lowPower = lp; changed = true }
        }
        if (changed) {
            // A better path (or the battery back up): the size ladder starts again from the top for it.
            val capWas = rate[0].cap
            rung = 0
            rate[0].setCap(profile(VideoWire.CAMERA).capKbps); rate[1].setCap(profile(VideoWire.SCREEN).capKbps)
            if (rate[0].cap < capWas) rung = VideoWire.rung(rate[0].kbps, rate[0].cap, 0)
            events.onProfile()
        }
        if (c2 && gotFirst && now - lastReport >= 1_000) sendReport()
        if (c2) pollVideo(now)
        watchAudio(now)
    }

    fun anyLive(): Boolean { val now = System.currentTimeMillis(); return paths.any { it.live(now) } }

    /** Labs, Voice over two paths, read once per call. Never on a Kids' TV, which has no Labs. */
    private val twoPaths = try {
        val p = context.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE)
        // Only while Labs is on (its switch is out of sight otherwise).
        p.getBoolean("labs", false) && p.getBoolean(CallLogic.TWO_PATHS, false) && !io.github.ravenor907.muninn.tv.KidMode.isTv(context)
    } catch (_: Exception) { false }

    /** A free path (Wi-Fi or Direct) answered a ping within 750 ms (so it works both ways): the relay rests (5.8.3). */
    fun freeFresh(now: Long = System.currentTimeMillis()) =
        paths.any { (it.kind == CallLogic.Kind.WIFI || it.kind == CallLogic.Kind.DIRECT) && it.lastPong > 0 && now - it.lastPong < CallLogic.FRESH_MS }


    /**
     * Seals with the next seq and sends on [only], or on every live path (every known one while none is live). The relay
     * is left out while a free path is fresh, Bluetooth for [video].
     */
    @Synchronized
    private fun send(type: Int, payload: ByteArray, only: CallLogic.Path? = null, video: Boolean = false) {
        if (stopped) return
        val k = keys ?: return
        val s = seq++
        val p = try { CallCrypto.seal(k, offerer, tag, type, s, System.currentTimeMillis() - started, payload) } catch (e: Exception) { return }
        if (type == CallCrypto.AUDIO) health.sent.incrementAndGet()
        val now = System.currentTimeMillis()
        val targets = only?.let { listOf(it) } ?: paths.filter { it.live(now) }.ifEmpty { if (video) emptyList() else paths.filter { it.kind != CallLogic.Kind.DIRECT || directOn } }
        // Up to 3 UDP paths (Wi-Fi first, then Direct, then the lowest round trip) plus Bluetooth; each distinct
        // destination gets the packet once (the relay once per relayed address, not once per relay path).
        // Labs, Voice over two paths: voice also on the relay while it's allocated, next to Wi-Fi or Direct (the receiver
        // keeps the first copy). Off by default.
        val dual = twoPaths && !anyTv && only == null && CallLogic.dualVoice(type == CallCrypto.AUDIO, turn != null, io.github.ravenor907.muninn.net.UltraPrivacy.on)
        val rest = only == null && freeFresh(now) && !dual
        for (dst in CallLogic.udpSends(targets, peerRelay.targets(), restRelay = rest, alsoRelay = dual)) when (dst.kind) {
            // Group video goes peer to peer only, never through the relay (it stays on voice there).
            CallLogic.Kind.RELAY -> if (!(video && videoNoRelay)) turn?.let { t ->
                // With copies on, nothing waits on the relay here: over TCP or TLS a slow write would hold up this lock, and
                // so the free path, the pings and the sound coming in.
                // Video never goes through the small drop queue: a keyframe burst would lose its start.
                // With two paths on (Labs), nothing waits on the relay under this lock; otherwise video never meets the drop queue.
                if (twoPaths && type == CallCrypto.AUDIO) t.sendSoon(dst.addr, p) else t.send(dst.addr, p)
                relayBytes.addAndGet(p.size + RELAY_OVERHEAD.toLong())
                // Pings and pongs keep a standby relay alive; only what else it carries counts as the relay in use.
                if (only == null) relayUsedAt = now
            }
            CallLogic.Kind.WIFI, CallLogic.Kind.DIRECT -> try { lan?.send(DatagramPacket(p, p.size, dst.addr)) } catch (_: Exception) {}
            else -> {}
        }
        if (!video && targets.any { it.kind == CallLogic.Kind.BLUETOOTH } && bluetooth?.canCarry(peerKey) == true) bluetooth.send(peerKey, p)
    }

    // ------------------------------------------------------------------ reports (5.8.4)

    private fun sendReport() {
        val now = System.currentTimeMillis()
        val r = synchronized(counters) {
            val span = maxOf(1L, now - lastReport)
            val due = audioDue + videoDue; val lost = audioLost + videoLost
            val rep = VideoWire.Report(if (due > 0) (lost * 100 / due).coerceIn(0, 100) else 0, sending[0], sending[1], jitter.targetMs.toInt(),
                (bytesIn * 8 / span).toInt(), rttMs, screenPaused)
            audioDue = 0; audioLost = 0; videoDue = 0; videoLost = 0; bytesIn = 0
            rep
        }
        lastReport = now
        send(CallCrypto.REPORT, VideoWire.encodeReport(r))
    }

    private fun onReport(r: VideoWire.Report) {
        peerLoss = r.lossPct
        if (r.camera != peerSending[0] || r.screen != peerSending[1]) {
            if (!r.camera) synchronized(asm[0]) { asm[0].reset() }
            if (!r.screen) synchronized(asm[1]) { asm[1].reset() }
            peerSending[0] = r.camera; peerSending[1] = r.screen
            events.onPeerVideo(r.camera, r.screen)
        }
        val now = System.currentTimeMillis()
        for (s in 0..1) if (sending[s]) rate[s].onReport(r.lossPct, now)
        // The camera's size and frame rate follow the bitrate down a ladder, and back up once the path recovers (at most
        // every 10 s, so the picture doesn't keep changing).
        if (sending[0] && now - rungAt >= 10_000L) {
            val next = VideoWire.rung(rate[0].kbps, rate[0].cap, rung)
            if (next != rung) { rung = next; rungAt = now; post { events.onProfile() } }
        }
    }

    /** The call's sound, counted: a Kids' TV tells its parents when a call had none (CallHealth). */
    private val health = CallHealth()

    /**
     * Kids' TV, after a call that connected (it was answered, its sound started, and sound packets came): the sound
     * check, to its parents, once. Timed from when it connected, never the ringing. Sent when something looks wrong, and
     * for the first few connected calls of each version even when it looks right (so a test call always says).
     */
    private fun healthReport(): String? {
        if (!io.github.ravenor907.muninn.tv.TvMic.active || externalAudio || audioStartedAt == 0L || firstMediaAt == 0L) return null
        val secs = (System.currentTimeMillis() - maxOf(firstMediaAt, audioStartedAt)) / 1000
        val t = track
        val route = if (t == null) "none" else try {
            t.routedDevice?.let { d -> AudioRoute.typeName(d.type) } ?: "unknown"
        } catch (_: Throwable) { "unknown" }
        val mode = when (try { audio.mode } catch (_: Exception) { -9 }) {
            AudioManager.MODE_NORMAL -> "normal"; AudioManager.MODE_IN_COMMUNICATION -> "call"; AudioManager.MODE_IN_CALL -> "phone call"
            AudioManager.MODE_RINGTONE -> "ringing"; else -> "other"
        }
        val setup = CallHealth.Setup(mode, usage, route, trackRate, try { t?.playState ?: -1 } catch (_: Exception) { -1 })
        val prefs = try { context.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE) } catch (_: Exception) { null }
        val ver = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
        val n = if (prefs?.getString("soundCheckVer", null) == ver) prefs.getInt("soundCheckN", 0) else 0
        val text = health.report(secs, setup, always = n < ALWAYS_REPORTS) ?: return null
        try { prefs?.edit()?.putString("soundCheckVer", ver)?.putInt("soundCheckN", n + 1)?.apply() } catch (_: Exception) {}
        return text
    }

    private fun sayHealth(text: String?) {
        if (text == null) return
        try {
            val app = context.applicationContext as? io.github.ravenor907.muninn.Muninn ?: return
            for (k in io.github.ravenor907.muninn.tv.KidTv.state.value.parents) app.chat.sendPrivate(k, text)
        } catch (_: Exception) {}
    }

    @Volatile private var rung = 0
    private var rungAt = 0L

    /** The Opus bitrate and expected loss now (5.8.4): 32 kbps on a free path, 24 over the relay, 16 in a room or without c2. */
    fun opusTuning(): Pair<Int, Int> {
        if (!c2 || room) return Opus.BITRATE to 10
        val l = peerLoss
        return (if (freeFresh()) 32_000 else 24_000) to (l + l / 2 + 3).coerceIn(5, 30)
    }

    // ------------------------------------------------------------------ video (5.8.5 to 5.8.7)

    private val sending = booleanArrayOf(false, false)
    private val peerSending = booleanArrayOf(false, false)
    private val frameNo = intArrayOf(0, 0)
    private val sent = arrayOf(LinkedHashMap<Int, List<ByteArray>>(), LinkedHashMap<Int, List<ByteArray>>())
    private val asm = arrayOf(VideoWire.Assembler(VideoWire.CAMERA), VideoWire.Assembler(VideoWire.SCREEN))
    val rate = arrayOf(VideoWire.RateControl().apply { setCap(VideoWire.cameraProfile(false).capKbps) },
        VideoWire.RateControl().apply { setCap(VideoWire.screenProfile(false).capKbps) })
    /** Unit-test style knob for the field: leave out every Nth video packet (resends get through). */
    @Volatile var dropVideoEvery = 0
    private var videoCount = 0

    fun peerSending(stream: Int) = peerSending[stream and 1]

    /**
     * The size, frame rate and bitrate cap for [stream] right now: 480p over the relay, up to 720p on a free path,
     * and the camera at 360p and 15 frames a second while the battery is low.
     */
    fun profile(stream: Int): VideoWire.Profile {
        val free = wasFree
        if ((stream and 1) == VideoWire.SCREEN) return VideoWire.screenProfile(free)
        var p = VideoWire.cameraProfile(free)
        if (lowPower) p = VideoWire.lowPower(p)
        if (peerTv || strain.strained) p = VideoWire.light(p)
        return VideoWire.onRung(p, rung)
    }

    /**
     * The peer is a Kids' TV this phone manages: a TV box's weak decoder gets the light camera from the start
     * (VideoWire.light). Known only here, so nothing new goes on the wire.
     */
    private val peerTv: Boolean by lazy { try { io.github.ravenor907.muninn.tv.TvParent.isManaged(peerKey) } catch (e: Throwable) { false } }

    /**
     * Any TV, not only one this phone looks after (its app says so with its version, "Muninn 1.48 · Android TV"). Only
     * for Labs' voice over two paths, never toward a TV; the camera's size still follows [peerTv], as Huginn's does.
     */
    private val anyTv: Boolean by lazy {
        peerTv || try {
            (context.applicationContext as? io.github.ravenor907.muninn.Muninn)?.chat?.contacts?.get(peerKey)?.app
                ?.let { io.github.ravenor907.muninn.net.AppVer.parse(it)?.platform == "Android TV" } == true
        } catch (e: Throwable) { false }
    }

    /** Keyframe requests for the camera: a receiver that keeps asking can't keep up, and gets the light camera too. */
    private val strain = VideoWire.Strain()

    private fun onCameraKeyAsked() {
        val became = synchronized(strain) { strain.keyAsked(System.currentTimeMillis()) }
        if (became && !peerTv) post {
            rate[0].setCap(profile(VideoWire.CAMERA).capKbps)
            events.onProfile()
        }
    }

    @Volatile private var lowPower = false
    private var lowPowerAt = 0L

    /** Android's Battery Saver is on, or the battery is under 20% and not charging. */
    private fun lowPowerNow(): Boolean = try {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm?.isPowerSaveMode == true) true else {
            val b = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = b?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val plugged = (b?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            level >= 0 && scale > 0 && level * 100 / scale < 20 && !plugged
        }
    } catch (e: Exception) { false }

    /** Not watching the peer's screen share (cap c3, 5.8.7): said in every report, and at once when it changes. */
    @Volatile var screenPaused = false; private set

    /** Watching the peer's screen share or not; only for a peer with cap c3 (others don't know report bit 2). */
    fun pauseScreen(on: Boolean) {
        if (screenPaused == on) return
        screenPaused = on
        if (c2 && gotFirst) post { sendReport() }
        if (!on) requestKeyframe(VideoWire.SCREEN)
    }

    /** What this side says it sends (in every report, so the other side shows or hides the picture). */
    fun setVideoSending(stream: Int, on: Boolean) {
        val s = stream and 1
        if (sending[s] == on) return
        sending[s] = on
        if (on && s == VideoWire.CAMERA) synchronized(strain) { strain.start(System.currentTimeMillis()) }
        if (!on) synchronized(sent[s]) { sent[s].clear() }
        if (c2 && gotFirst) post { sendReport() }
    }

    /** Runs [f] on the media's timer thread, unless the call has stopped (then the timer is gone). */
    private fun post(f: () -> Unit) {
        if (stopped) return
        try { timer.execute { if (!stopped) f() } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private val keyAskedAt = longArrayOf(-100_000, -100_000)
    private val keySentAt = longArrayOf(-100_000, -100_000)

    /**
     * Asks the peer for a keyframe (the screen came back, or the decoder lost its place). At most one per
     * max(300 ms, 2 round trips): a keyframe takes that long to come back.
     */
    fun requestKeyframe(stream: Int) {
        val s = stream and 1
        if (!c2 || stopped) return
        synchronized(keyAskedAt) {
            val now = System.currentTimeMillis()
            if (now - keyAskedAt[s] < maxOf(KEY_GAP_MS, 2L * rttMs)) return
            keyAskedAt[s] = now
        }
        post { send(CallCrypto.KEY_REQUEST, byteArrayOf(s.toByte())) }
    }

    /** One encoded VP8 frame: cut into packets and sent. False when video can't go now. */
    fun sendVideo(stream: Int, key: Boolean, rotation: Int, vp8: ByteArray): Boolean {
        val s = stream and 1
        if (!c2 || stopped || keys == null || !sending[s]) return false
        val n = frameNo[s] and 0xFFFF
        frameNo[s]++
        if (key) keySentAt[s] = System.currentTimeMillis()
        val parts = VideoWire.split(s, n, key, rotation, vp8)
        if (parts.isEmpty()) return false
        synchronized(sent[s]) {
            sent[s][n] = parts
            while (sent[s].size > KEEP_FRAMES) sent[s].remove(sent[s].keys.first())
        }
        for (p in parts) {
            videoCount++
            if (dropVideoEvery > 0 && videoCount % dropVideoEvery == 0) continue
            send(CallCrypto.VIDEO, p, video = true)
        }
        return true
    }

    private fun onVideo(payload: ByteArray) {
        val f = VideoWire.decode(payload) ?: return
        synchronized(counters) { videoDue++ }
        if (!peerSending[f.stream]) { peerSending[f.stream] = true; events.onPeerVideo(peerSending[0], peerSending[1]) }
        val a = asm[f.stream]
        // Handed on under the assembler's lock: packets come on several threads (relay, Wi-Fi, timer), and the
        // decoder must get the frames in order.
        synchronized(a) {
            a.add(f, System.currentTimeMillis())
            while (true) { val fr = a.next() ?: break; events.onVideoFrame(f.stream, fr.data, fr.key, fr.rotation) }
        }
    }

    private fun pollVideo(now: Long) {
        for (s in 0..1) {
            if (!peerSending[s]) continue
            val a = asm[s]
            val ask = synchronized(a) {
                a.poll(now, rttMs).also { while (true) { val fr = a.next() ?: break; events.onVideoFrame(s, fr.data, fr.key, fr.rotation) } }
            }
            for (n in ask.nacks) {
                if (n.first) synchronized(counters) { videoLost += maxOf(1, n.indexes.size) }
                send(CallCrypto.NACK, VideoWire.encodeNack(n))
            }
            if (ask.keyframe) requestKeyframe(s)
        }
    }

    private fun onNack(n: VideoWire.Nack) {
        val parts = synchronized(sent[n.stream]) { sent[n.stream][n.frame] } ?: return   // too old: their keyframe request covers it
        val which = n.indexes.ifEmpty { parts.indices.toList() }
        for (i in which.take(MAX_RESEND)) if (i in parts.indices) send(CallCrypto.VIDEO, parts[i], video = true)
    }

    // ------------------------------------------------------------------ audio

    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var aec: AcousticEchoCanceler? = null
    @Volatile private var ns: NoiseSuppressor? = null
    private var proximity: PowerManager.WakeLock? = null
    companion object {
        /** TURN and UDP headers on each relayed packet, close enough for the month's count. */
        const val RELAY_OVERHEAD = 40
        /** Calls holding the phone in call mode now; read aloud puts the mode back to normal when this is 0. */
        @JvmField val liveCalls = java.util.concurrent.atomic.AtomicInteger(0)
        /**
         * 1.54: every call that starts its sound takes the next number. A microphone or speaker thread of an older call
         * (one still retrying, or stuck in Android's audio) stops by itself and never touches the newer call's sound.
         */
        private val audioGen = java.util.concurrent.atomic.AtomicLong(0)
        /** How many connected TV calls per version send the sound check even when it looks fine (the next test calls). */
        private const val ALWAYS_REPORTS = 3
        private const val STUCK_MS = 3_000L
    }
    private var oldMode = AudioManager.MODE_NORMAL
    @Volatile private var counted = false
    private var mic: Thread? = null
    private var player: Thread? = null
    /** Kids' TV only: the TV's microphone and speaker sound (TvMic, TvVoice). Null on a phone. */
    @Volatile private var tvGain: io.github.ravenor907.muninn.tv.TvMic.CallAudio? = null
    /** The rates the microphone and the speaker run at: the call's own on a phone, the TV's own on a Kids' TV. */
    @Volatile private var micRate = Opus.RATE
    @Volatile private var playRate = Opus.RATE
    /** The rate the speaker really runs at (a fallback speaker may run at the call's own rate). */
    @Volatile private var trackRate = Opus.RATE
    @Volatile private var minIn = 0
    /** This call's number ([audioGen]), 0 until its sound starts. */
    @Volatile private var gen = 0L
    /** 1.54, a TV (Kids' or not): media routing and normal audio mode, so the call plays where the TV plays (see AudioRoute.tv). */
    @Volatile private var tvRoute = false
    @Volatile private var audioStartedAt = 0L
    @Volatile private var firstMediaAt = 0L
    /** When the microphone and the speaker last came back from read() and write(): a stuck one is unblocked and made again. */
    @Volatile private var lastReadAt = 0L
    @Volatile private var lastWriteAt = 0L
    @Volatile private var micStuck = false
    @Volatile private var speakerStuck = false
    private var lastAudioWatch = 0L

    private fun live(g: Long) = !stopped && g == gen && g == audioGen.get()

    // ------------------------------------------------------------------ voice rooms (section 6)

    /** In a voice room the room owns the microphone and the speaker for every peer: this media only seals and opens. */
    @Volatile var externalAudio = false
    /** One encoded 20 ms frame from the room's microphone (nothing while muted). */
    fun sendOpus(opus: ByteArray) { if (!muted) send(CallCrypto.AUDIO, opus) }
    /** What the room's player plays next for this peer. */
    fun nextPlay(): Play = jitter.next()

    /**
     * Starts the microphone and the speaker. False if the call's sound can't run here at all (Opus didn't load).
     * Never throws: a speaker or microphone that won't open is tried again while the call goes on, and the TV call
     * sound check says so. [video]: a video call, which starts on the speaker unless a headset is connected (AudioRoute).
     */
    @SuppressLint("MissingPermission")
    fun startAudio(video: Boolean = false): Boolean {
        if (externalAudio) return true
        if (Opus.loadError != null) return false
        if (stopped || gen != 0L) return true
        val g = audioGen.incrementAndGet()
        gen = g
        audioStartedAt = System.currentTimeMillis()
        tvRoute = try { io.github.ravenor907.muninn.tv.KidMode.isTv(context) } catch (_: Throwable) { false }
        try {
            oldMode = audio.mode
            // A TV plays the call through the media path in normal mode (see AudioRoute.tv); a phone in call mode.
            audio.mode = if (tvRoute) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {}
        if (!counted) { counted = true; liveCalls.incrementAndGet() }
        try { AudioRoute.start(audio, video = video, tv = tvRoute) } catch (_: Exception) {}
        // Kids' TV: the microphone and speaker at the TV's own rate (TvVoice converts); a phone: the call's 16 kHz.
        val tv = io.github.ravenor907.muninn.tv.TvMic.active
        try { io.github.ravenor907.muninn.tv.TvMic.callRates(context, Opus.RATE).let { (m, p) -> micRate = m; playRate = p } } catch (_: Exception) {}
        minIn = AudioRecord.getMinBufferSize(micRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // A phone has one source; a Kids' TV tries the next one when a source won't open or start.
        val rec = try { openFrom(0, g)?.first } catch (_: Exception) { null }
        record = rec
        // No microphone now (another app holds it, as a TV's voice search or camera can): the call's sound still plays
        // here, at the call's own rate (Android converts it), and the microphone is tried again every second, quietly.
        if (rec == null && tv) playRate = Opus.RATE
        track = buildTrack()
        tvGain?.delay(echoDelay())
        player = Thread({ playLoop(g) }, "muninn-call-play").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
        mic = Thread({ micLoop(rec, g) }, "muninn-call-mic").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
        // Against the ear only when it isn't on the speaker (a video call usually is); never on a TV.
        proximityOn(!tvRoute && !AudioRoute.onSpeaker)
        return true
    }

    /** The echo canceller's first guess at the speaker-to-microphone delay (it finds the real one itself). */
    private fun echoDelay(): Int = 20 + outBytes(trackRate) / 2 * 1000 / maxOf(1, trackRate)

    /** The speaker's buffer: a TV's audio is slower to wake and its CPU weaker, so a 100 ms cushion there. */
    private fun outBytes(rate: Int): Int {
        val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(0)
        return if (io.github.ravenor907.muninn.tv.TvMic.active || tvRoute) maxOf(minOut * 2, rate / 10 * 2) else maxOf(minOut, Opus.FRAME * 2 * 3)
    }

    /** "media" or "voice": what the speaker plays as. A TV plays the call as media (see AudioRoute.tv). */
    @Volatile private var usage = "voice"

    /**
     * The call's speaker, never throwing: as planned (media on a TV, a voice call on a phone), else the other kind at the
     * same rate, else plain media at the call's own rate. Null (and the sound check says so) when none can be made.
     */
    private fun buildTrack(): AudioTrack? {
        val first = if (tvRoute) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION
        val other = if (tvRoute) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA
        val plans = listOf(first to playRate, other to playRate, AudioAttributes.USAGE_MEDIA to Opus.RATE).distinct()
        for ((i, p) in plans.withIndex()) {
            val (u, rate) = p
            val t = try {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(u).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(outBytes(rate))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Throwable) { null }
            if (t == null) continue
            if (t.state != AudioTrack.STATE_INITIALIZED) { try { t.release() } catch (_: Exception) {}; continue }
            trackRate = rate
            usage = if (u == AudioAttributes.USAGE_MEDIA) "media" else "voice"
            health.speakerFallback = i
            return t
        }
        health.speakerFallback = -1
        return null
    }

    /**
     * Opens the first source of the call's list that records, starting at [start] (wrapping round). (recorder, its index)
     * or null when none would.
     */
    private fun openFrom(start: Int, g: Long): Pair<AudioRecord, Int>? {
        val srcs = io.github.ravenor907.muninn.tv.TvMic.callSources()
        for (k in srcs.indices) {
            if (!live(g)) return null
            val i = (start + k) % srcs.size
            openMic(srcs[i], g)?.let { return it to i }
        }
        return null
    }

    /**
     * Opens and starts the microphone on [src] with the echo canceller, noise suppressor and (Kids' TV)
     * gain and AGC where the device has them. Null, with everything released, if it doesn't record, or once the call
     * (generation [g]) is over: an older call's retry never opens a sound stage over a newer call's.
     */
    @SuppressLint("MissingPermission")
    private fun openMic(src: Int, g: Long): AudioRecord? {
        if (!live(g)) return null
        // A TV gets a roomy 200 ms buffer: a weak CPU that falls behind for a moment loses nothing.
        val inBytes = if (io.github.ravenor907.muninn.tv.TvMic.active) maxOf(minIn * 2, micRate / 5 * 2) else maxOf(minIn, Opus.FRAME * 2 * 4)
        // As in 1.44, the last version whose TV calls had sound: the plain recorder (no "private" capture).
        val rec = try {
            AudioRecord(src, micRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inBytes)
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { health.tried(src, "no open"); try { rec?.release() } catch (_: Exception) {}; return null }
        if (!live(g)) { try { rec.release() } catch (_: Exception) {}; return null }
        // Kids' TV: echo canceller, noise suppression and gain (TvVoice), or a louder microphone without it.
        val a = io.github.ravenor907.muninn.tv.TvMic.forCall(rec.audioSessionId, micRate, trackRate, Opus.RATE, g)
        if (!live(g)) {
            io.github.ravenor907.muninn.tv.TvMic.release(a)
            try { rec.release() } catch (_: Exception) {}
            return null
        }
        tvGain = a
        if (io.github.ravenor907.muninn.tv.TvMic.platformEffects()) {
            if (AcousticEchoCanceler.isAvailable()) aec = try { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } } catch (e: Exception) { null }
            // Off in Settings: switched off rather than left alone, as the call microphone has Android's own on already.
            if (NoiseSuppressor.isAvailable()) ns = try { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = CallManager.noiseRemoval(context) && io.github.ravenor907.muninn.tv.TvMic.platformNoise() } } catch (e: Exception) { null }
        }
        val ok = try { rec.startRecording(); rec.recordingState == AudioRecord.RECORDSTATE_RECORDING } catch (e: Exception) { false }
        health.tried(src, if (ok) "ok" else "no start")
        if (ok) { health.mic = src; lastReadAt = System.currentTimeMillis(); return rec }
        dropMicStage()
        try { rec.release() } catch (_: Exception) {}
        return null
    }

    /** The microphone's effects and sound stage go (its recorder is let go by whoever holds it). */
    private fun dropMicStage() {
        try { aec?.release(); ns?.release() } catch (_: Exception) {}
        aec = null; ns = null
        tvGain?.let { io.github.ravenor907.muninn.tv.TvMic.release(it) }
        tvGain = null
    }

    private enum class MicEnd { STOPPED, ERRORS, ZEROS, STUCK }

    /**
     * The microphone for the whole call: records, and when it fails (read errors: a dead recorder after an input switch
     * or the audio server restarting; a stuck read; on a TV, several seconds of exact zeros: another app holds it) it is
     * let go and opened again, the next source first for zeros. Without one, tried again every second.
     */
    private fun micLoop(first: AudioRecord?, g: Long) {
        var rec = first
        var idx = 0
        try {
            while (live(g)) {
                if (rec == null) {
                    try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
                    val got = openFrom(idx, g) ?: continue
                    rec = got.first; idx = got.second
                    record = rec
                    tvGain?.delay(echoDelay())
                }
                val why = captureLoop(rec, g)
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
                if (record === rec) record = null
                rec = null
                dropMicStage()
                health.mic = -1
                if (why == MicEnd.STOPPED || !live(g)) return
                health.micReopens.incrementAndGet()
                // Zeros: another app may hold this source; the next one is tried first. Errors or a stuck read: the same.
                if (why == MicEnd.ZEROS) idx = (idx + 1) % maxOf(1, io.github.ravenor907.muninn.tv.TvMic.callSources().size)
                val got = openFrom(idx, g)
                if (got != null) { rec = got.first; idx = got.second; record = rec; tvGain?.delay(echoDelay()) }
            }
        } finally {
            rec?.let { r -> try { r.stop() } catch (_: Exception) {}; try { r.release() } catch (_: Exception) {}; if (record === r) record = null }
            if (gen == g && stopped) dropMicStage()
        }
    }

    private fun captureLoop(rec: AudioRecord, g: Long): MicEnd {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val enc = try { Opus.Encoder() } catch (e: Throwable) { return MicEnd.STOPPED }
        val pcm = ShortArray(Opus.FRAME)
        var lastSent = 0L
        var frames = 0
        var zeroFrames = 0
        micStuck = false
        lastReadAt = System.currentTimeMillis()
        // The microphone's 20 ms at its own rate (a Kids' TV may run it at 48 kHz).
        val tv = tvGain
        val tvMic = tv != null || io.github.ravenor907.muninn.tv.TvMic.active || tvRoute
        val raw = if (tv != null && tv.captureFrame != Opus.FRAME) ShortArray(tv.captureFrame) else pcm
        val errors = CallLogic.MicErrors()
        val zeros = CallLogic.ZeroRun()
        try {
            // Already recording: openMic started it to know the source works.
            while (live(g)) {
                var got = 0
                var failed = 0
                while (got < raw.size && live(g)) { val n = rec.read(raw, got, raw.size - got); if (n <= 0) { failed = n; break }; got += n }
                val now = System.currentTimeMillis()
                lastReadAt = now
                if (!live(g)) break
                if (micStuck) return MicEnd.STUCK
                if (got < raw.size) {
                    if (failed < 0) health.readErr = failed
                    // A read error returns at once: a short pause so this loop never spins at audio priority, and after
                    // about half a second of nothing but errors the recorder is made again (a dead one never recovers).
                    if (errors.read(false, now)) return MicEnd.ERRORS
                    try { Thread.sleep(20) } catch (_: InterruptedException) { break }
                    continue
                }
                errors.read(true, now)
                health.micFrames.incrementAndGet()
                val allZero = raw.all { it.toInt() == 0 }
                if (allZero) health.micZero.incrementAndGet() else health.micReal.incrementAndGet()
                if (tvMic) {
                    // A TV: several seconds of exact zeros (another app, such as the assistant or voice search, holds
                    // the microphone; Android hands this app zeros instead of an error): opened again, the next source first.
                    val reopen = zeros.frame(allZero, now)
                    if (zeros.seen) health.zerosSeen = true
                    if (reopen) return MicEnd.ZEROS
                } else if (allZero) {
                    // A phone: after about 250 ms of exact zeros, quietly take the call's audio mode back and restart the
                    // same recorder. No message: it must not interrupt.
                    if (++zeroFrames == 12) try {
                        audio.mode = AudioManager.MODE_IN_COMMUNICATION
                        rec.stop(); rec.startRecording()
                    } catch (_: Exception) {}
                    if (zeroFrames > 12) zeroFrames = 0
                } else zeroFrames = 0
                if (tv != null && !tv.capture(raw, pcm)) continue
                // A video shared into the call: its sound goes out under the voice (lower while this side talks).
                val shared = CallShare.sendQ.poll()
                if (muted && shared == null) continue   // pings go on; no audio while muted (5.5)
                if (shared != null) { if (muted) pcm.fill(0); CallShare.mix(pcm, shared, CallShare.talking(pcm)) }
                if (frames++ % 25 == 0) opusTuning().let { (b, l) -> enc.tune(b, l) }   // twice a second
                val p = enc.encode(pcm, keepSilence = true) ?: continue
                // DTX: one silence packet every 400 ms, so the other side's decoder makes comfort noise (5.5).
                if (Opus.isSilence(p) && now - lastSent < 400) continue
                lastSent = now
                send(CallCrypto.AUDIO, p)
            }
        } catch (_: Exception) {
            if (live(g)) return MicEnd.ERRORS
        } finally {
            enc.close()
        }
        return MicEnd.STOPPED
    }

    private fun playLoop(g: Long) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val dec = try { Opus.Decoder() } catch (e: Throwable) { return }
        val pcm = ShortArray(Opus.FRAME)
        // Kids' TV: what goes to the speaker, at its own rate; the echo canceller hears it on the way. Read for every
        // frame, so a microphone that opens late (or again) gets its echo canceller fed from then on.
        val out = ShortArray(maxOf(48_000 / 50, Opus.FRAME))
        var wasSpeaking = false
        var t: AudioTrack? = track
        val errors = CallLogic.WriteErrors()
        var rebuild = false
        var frames = 0
        try {
            if (t != null) try { t.play() } catch (_: Exception) { rebuild = true }
            lastWriteAt = System.currentTimeMillis()
            while (live(g)) {
                if (t == null || rebuild || speakerStuck) {
                    // A dead or stuck speaker (an input switch, a soundbar or HDMI-CEC change, the audio server restarting):
                    // made again. None to be had: tried again every second.
                    if (t != null) {
                        try { t.stop() } catch (_: Exception) {}; try { t.release() } catch (_: Exception) {}
                        health.speakerRebuilds.incrementAndGet()
                    }
                    rebuild = false; speakerStuck = false
                    t = buildTrack()
                    track = t
                    if (t == null) { try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }; continue }
                    try { t.play() } catch (_: Exception) {}
                    tvGain?.delay(echoDelay())
                    lastWriteAt = System.currentTimeMillis()
                }
                val f = jitter.next()
                val n = when (f) {
                    is Play.Packet -> dec.decode(f.data, pcm)
                    is Play.Fec -> dec.decode(f.next, pcm, fec = true)
                    Play.Conceal -> dec.decode(null, pcm)
                    Play.Silence -> { pcm.fill(0); Opus.FRAME }
                }
                if (n <= 0) pcm.fill(0)
                val real = f !is Play.Silence && n > 0
                val s = speaking.feed(pcm, Opus.FRAME, System.currentTimeMillis())
                if (s != wasSpeaking) { wasSpeaking = s; events.onSpeaking(s) }
                // Their volume here (cap g2: 0 to 200 %), read for every frame after the speaking ring. Mute for me is for
                // rooms only: a 1:1 call always plays them.
                io.github.ravenor907.muninn.net.ModLogic.applyGain(pcm, Opus.FRAME, io.github.ravenor907.muninn.net.PeopleSound.gainOf(peerKey, room = false))
                // The video this side shares plays here too, through the call's own sound (so the echo canceller knows it),
                // lower while they talk.
                CallShare.localQ.poll()?.let { CallShare.mix(pcm, it, s) }
                val rate = trackRate
                val want = rate / 50
                val tv = tvGain
                var len = if (tv != null && tv.playRate == rate) tv.render(pcm, out) else 0
                // No processor (or it closed, or runs at another rate): the plain frame, stretched to the speaker's rate.
                if (len <= 0) {
                    if (tv != null) tv.render(pcm, ShortArray(tv.playFrame))   // the echo canceller still hears it
                    len = stretch(pcm, out, want)
                }
                val w = try { t.write(out, 0, len) } catch (_: Exception) { AudioTrack.ERROR_DEAD_OBJECT }   // blocking: the audio clock paces this loop
                val now = System.currentTimeMillis()
                lastWriteAt = now
                if (w > 0) {
                    errors.write(true, now)
                    if (real) health.playedReal.incrementAndGet() else health.playedSilence.incrementAndGet()
                } else {
                    if (w < 0) health.writeErr = w
                    if (errors.write(false, now)) rebuild = true
                    else try { Thread.sleep(20) } catch (_: InterruptedException) { break }   // never spin at audio priority
                }
                // Now and then: a speaker Android stopped by itself is started again, or made again if it won't.
                if (++frames % 50 == 0 && !rebuild) try {
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) { t.play(); if (t.playState != AudioTrack.PLAYSTATE_PLAYING) rebuild = true }
                } catch (_: Exception) { rebuild = true }
            }
        } catch (_: Exception) {
        } finally {
            dec.close()
            // This thread's own speaker goes with it (stop() only lets go of one whose thread has left write()).
            t?.let { x -> try { x.stop() } catch (_: Exception) {}; if (stopped || !live(g)) try { x.release() } catch (_: Exception) {} }
        }
    }

    /** [pcm] (one call frame) into [out] at [want] samples: copied, or picked and repeated (nearest sample). */
    private fun stretch(pcm: ShortArray, out: ShortArray, want: Int): Int {
        val m = minOf(want, out.size)
        if (m == pcm.size) { System.arraycopy(pcm, 0, out, 0, m); return m }
        for (i in 0 until m) out[i] = pcm[(i.toLong() * pcm.size / m).toInt().coerceAtMost(pcm.size - 1)]
        return m
    }

    /**
     * Twice a second (the media timer): a read() or write() that hasn't come back for [STUCK_MS] is unblocked (the
     * recorder or speaker stopped), and its thread makes it again.
     */
    private fun watchAudio(now: Long) {
        if (audioStartedAt == 0L || externalAudio || now - lastAudioWatch < 500) return
        lastAudioWatch = now
        val r = record
        if (r != null && lastReadAt > 0 && now - lastReadAt > STUCK_MS && !micStuck) {
            micStuck = true
            try { r.stop() } catch (_: Exception) {}
        }
        val t = track
        if (t != null && lastWriteAt > 0 && now - lastWriteAt > STUCK_MS && !speakerStuck) {
            speakerStuck = true
            try { t.pause(); t.flush(); t.stop() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ routing

    /** Speaker on or off. Off goes back to the usual order: a Bluetooth headset, wired headphones, else the earpiece. */
    fun setSpeaker(on: Boolean) = setOutput(if (on) "speaker" else AudioRoute.AUTOMATIC)

    /** The camera came on mid-call: the sound moves to the speaker, unless a headset is connected or the person chose. */
    fun videoStarted() {
        if (externalAudio) return
        AudioRoute.videoCall()
        proximityOn(!tvRoute && !AudioRoute.onSpeaker)
    }

    /** The sound goes to [id], one of [AudioRoute.outputs] (the call screen's Audio button). */
    fun setOutput(id: String) {
        AudioRoute.choose(id)
        proximityOn(!tvRoute && !AudioRoute.onSpeaker)
    }

    /** Proximity: the screen goes dark against the ear (not on speaker). */
    private fun proximityOn(on: Boolean) {
        try {
            if (on) {
                if (proximity == null) {
                    val pm = context.getSystemService(PowerManager::class.java)
                    if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK))
                        proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "muninn:call")
                }
                proximity?.takeIf { !it.isHeld }?.acquire(4 * 60 * 60_000L)
            } else proximity?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ end

    fun stop() {
        if (stopped) return
        // What the sound was, read before anything is let go (the speaker's device and state).
        val report = try { healthReport() } catch (_: Exception) { null }
        stopped = true
        synchronized(stunLock) { stunLock.notifyAll() }
        synchronized(sent[0]) { sent[0].clear() }; synchronized(sent[1]) { sent[1].clear() }
        ticker?.cancel(false)
        timer.shutdownNow()
        try { lan?.close() } catch (_: Exception) {}
        // Never waits on the network (TurnClient lets go of its link on a thread of its own).
        try { turn?.close() } catch (_: Exception) {}
        try { renewing?.close() } catch (_: Exception) {}
        sayHealth(report)
        relayBytes.getAndSet(0).takeIf { it > 0 }?.let { n -> try { (context.applicationContext as io.github.ravenor907.muninn.Muninn).chat.meter.add(n) } catch (_: Exception) {} }
        bluetooth?.onMedia(null)
        // Let the audio threads leave read() and write(): stopping (not releasing) the recorder and the speaker from here
        // is safe and brings a stuck read() or write() back; the retry's sleep is interrupted.
        try { record?.stop() } catch (_: Exception) {}
        try { track?.let { it.pause(); it.flush(); it.stop() } } catch (_: Exception) {}
        try { mic?.interrupt() } catch (_: Exception) {}
        try { mic?.join(500); player?.join(500) } catch (_: InterruptedException) {}
        // Released here only once its thread has left read() or write(); a thread still in there releases it itself
        // on the way out. Releasing under a running read is a native crash on some phones (a hang-up race).
        if (mic?.isAlive != true) {
            try { record?.release() } catch (_: Exception) {}
            try { aec?.release(); ns?.release() } catch (_: Exception) {}
            // Only this call's own stage (TvMic.release(a)): never one a newer call has opened since.
            tvGain?.let { io.github.ravenor907.muninn.tv.TvMic.release(it) }
        }
        if (player?.isAlive != true) try { track?.release() } catch (_: Exception) {}
        proximityOn(false)
        if (!externalAudio && gen != 0L) try {
            // Only the call that holds the sound now puts it back (an older call ending late never undoes a newer one's).
            if (gen == audioGen.get()) {
                AudioRoute.stop()
                // Never hand back call mode: a mode left over from an earlier call would keep the phone in it, and every
                // sound afterwards (read aloud, videos) would play through call processing and sound robotic.
                audio.mode = if (oldMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else oldMode
            }
        } catch (_: Exception) {}
        if (counted) { counted = false; liveCalls.decrementAndGet() }
        keys?.wipe()
    }
}
