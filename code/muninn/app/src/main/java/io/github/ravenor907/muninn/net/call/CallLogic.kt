package io.github.ravenor907.muninn.net.call

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The parts of calling that don't need Android, so unit tests cover them: the signaling messages of room
 * "call" (shared/docs/protocol-0.6.md 5.2), the speaking indicator and path liveness (5.6). The jitter buffer is [Jitter].
 *
 * Internet calls: audio goes only through the TURN relay, unless both people trust each other
 * (shared/docs/protocol-0.6.md 5.8.1: the Family folder or the "Direct calls" switch). So for anyone else an offer or
 * answer carries only `relay` candidates (the relay's address, never this phone's), and their `srflx`/`host`
 * candidates are never used: nothing is sent straight to their internet address. Two devices on the same
 * Wi-Fi also send to each other directly: the call socket's port goes out twice, as a `lan` candidate and as
 * the top-level `lp` (both apps send both and accept either); the address comes from the Wi-Fi discovery
 * Chat already does, never from the message.
 */
object CallLogic {
    const val OFFER_EVERY_MS = 3_000L
    const val RING_MS = 45_000L
    const val ANSWER_EVERY_MS = 2_000L
    const val ANSWER_FOR_MS = 15_000L
    const val NO_PATH_MS = 15_000L
    const val DROPPED_MS = 20_000L
    const val PATH_DEAD_MS = 4_000L
    const val MAX_CANDS = 8
    val TYPES = setOf("offer", "ring", "answer", "decline", "busy", "cands", "mute", "hangup")
    val WHY = setOf("hangup", "noanswer", "nopath", "dropped", "error")

    // ------------------------------------------------------------------ messages

    /** A direct internet candidate (5.8.2): "srflx" (what the STUN server saw) or "host" (a global IPv6 address). Only to someone trusted. */
    data class Direct(val type: String, val addr: InetSocketAddress)

    fun cands(relay: InetSocketAddress?, lanPort: Int? = null, direct: List<Direct> = emptyList()): JSONArray = JSONArray().apply {
        if (relay != null) put(JSONObject().put("t", "relay").put("a", relay.address.hostAddress).put("p", relay.port))
        if (lanPort != null) put(JSONObject().put("t", "lan").put("p", lanPort))
        for (d in direct.take(4)) put(JSONObject().put("t", d.type).put("a", d.addr.address.hostAddress?.substringBefore('%')).put("p", d.addr.port))
    }

    /** What a c2 app adds to its offer or answer (5.8): video codec, camera on, direct path wanted. Null: say nothing (no c2). */
    data class Extras(val vp8: Boolean, val video: Boolean, val p2p: Boolean)

    private fun JSONObject.extras(x: Extras?): JSONObject {
        if (x == null) return this
        put("c2", true); if (x.vp8) put("vc", "vp8"); put("vid", x.video); if (x.p2p) put("p2p", true)
        return this
    }

    fun offer(callId: String, eph: String, sig: String, tsMs: Long, relay: InetSocketAddress?, lanPort: Int?, ble: Boolean,
              extras: Extras? = null, direct: List<Direct> = emptyList()): JSONObject =
        JSONObject().put("t", "offer").put("call", callId).put("eph", eph).put("sig", sig).put("ts", tsMs)
            .put("cands", cands(relay, lanPort, direct)).put("ble", ble).put("codec", CallCrypto.CODEC).apply { if (lanPort != null) put("lp", lanPort) }
            .extras(extras)

    fun answer(callId: String, eph: String, sig: String, relay: InetSocketAddress?, lanPort: Int?, ble: Boolean,
               extras: Extras? = null, direct: List<Direct> = emptyList()): JSONObject =
        JSONObject().put("t", "answer").put("call", callId).put("eph", eph).put("sig", sig)
            .put("cands", cands(relay, lanPort, direct)).put("ble", ble).apply { if (lanPort != null) put("lp", lanPort) }.extras(extras)

    fun candsMsg(callId: String, relay: InetSocketAddress?, lanPort: Int?, direct: List<Direct> = emptyList()): JSONObject =
        JSONObject().put("t", "cands").put("call", callId).put("cands", cands(relay, lanPort, direct)).apply { if (lanPort != null) put("lp", lanPort) }

    fun simple(t: String, callId: String): JSONObject = JSONObject().put("t", t).put("call", callId)
    fun mute(callId: String, on: Boolean): JSONObject = simple("mute", callId).put("on", on)
    fun hangup(callId: String, why: String): JSONObject = simple("hangup", callId).put("why", why)

    /** A checked incoming message. [relay]: the peer's relayed address, if it sent one. */
    data class Signal(
        val t: String, val call: String, val eph: String = "", val sig: String = "", val ts: Long = 0,
        val relay: InetSocketAddress? = null, val lanPort: Int? = null, val ble: Boolean = false, val codec: String = "",
        val on: Boolean = false, val why: String = "", val hasCands: Boolean = false, val voiceRoom: Boolean = false,
        // 5.8, only from a c2 app: it speaks c2, can do VP8 video, has its camera on, offers the direct path; its direct candidates.
        val c2: Boolean = false, val vp8: Boolean = false, val video: Boolean = false, val p2p: Boolean = false,
        val direct: List<InetSocketAddress> = emptyList(),
    )

    class Bad(why: String) : IllegalArgumentException(why)

    /**
     * Parses and checks one plaintext of room "call" from [fromKey]. Throws [Bad] when it is malformed or an
     * offer/answer signature fails (5.2: refuse it). Voice room messages (call "-") come back with voiceRoom set.
     */
    fun parse(fromKey: String, text: String): Signal {
        val o = try { JSONObject(text) } catch (e: Exception) { throw Bad("not JSON") }
        val t = o.opt("t") as? String ?: throw Bad("t")
        val call = o.opt("call") as? String ?: throw Bad("call")
        if (call == "-" || o.has("g")) return Signal(t, call, voiceRoom = true)
        if (t !in TYPES) throw Bad("type")
        if (!CallCrypto.isCallId(call)) throw Bad("call id")
        var s = Signal(t, call)
        if (t == "offer" || t == "answer") {
            val eph = o.opt("eph") as? String ?: throw Bad("eph")
            val sig = o.opt("sig") as? String ?: throw Bad("sig")
            if (!CallCrypto.verify(fromKey, t, call, eph, sig)) throw Bad("bad signature")
            s = s.copy(eph = eph, sig = sig, ble = o.optBoolean("ble", false))
            if (o.optBoolean("c2", false)) s = s.copy(c2 = true, vp8 = o.optString("vc") == "vp8", video = o.optBoolean("vid", false), p2p = o.optBoolean("p2p", false))
            if (t == "offer") s = s.copy(ts = (o.opt("ts") as? Number)?.toLong() ?: throw Bad("ts"), codec = o.optString("codec"))
        }
        if (t == "offer" || t == "answer" || t == "cands") {
            val a = o.optJSONArray("cands")
            if (a != null && a.length() > MAX_CANDS) throw Bad("too many candidates")
            val lp = (o.opt("lp") as? Number)?.toInt()?.takeIf { it in 1..65535 }
            s = s.copy(relay = a?.let(::relayOf), hasCands = a != null, lanPort = lp ?: a?.let(::lanOf), direct = a?.let(::directOf) ?: emptyList())
        }
        if (t == "mute") s = s.copy(on = o.optBoolean("on", false))
        if (t == "hangup") s = s.copy(why = o.optString("why").takeIf { it in WHY } ?: "hangup")
        return s
    }

    /** The first `relay` candidate with a literal IP address (never looked up by name). Others are ignored. */
    fun relayOf(a: JSONArray): InetSocketAddress? {
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            if (c.optString("t") != "relay") continue
            val addr = literal(c.optString("a")) ?: continue
            val p = (c.opt("p") as? Number)?.toInt() ?: continue
            if (p in 1..65535) return InetSocketAddress(addr, p)
        }
        return null
    }

    /**
     * Up to 3 `srflx`/`host` candidates (5.8.2): literal IPs only, never a local address (a peer can't make this phone
     * send to its LAN). Used only when both sides trust each other.
     */
    fun directOf(a: JSONArray): List<InetSocketAddress> {
        val out = ArrayList<InetSocketAddress>()
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            val t = c.optString("t")
            if (t != "srflx" && t != "host") continue
            val addr = literal(c.optString("a")) ?: continue
            val p = (c.opt("p") as? Number)?.toInt() ?: continue
            if (p !in 1..65535 || !usable(addr)) continue
            val sa = InetSocketAddress(addr, p)
            if (sa !in out) out += sa
            if (out.size >= 3) break
        }
        return out
    }

    /** Unit tests only: loopback counts as an internet address. */
    @Volatile var testLocal = false

    /** True for addresses that never leave the local network (private, shared, loopback, link-local, ULA, multicast). */
    fun isLocal(a: InetAddress): Boolean {
        val b = a.address
        if (b.size == 4) {
            val b0 = b[0].toInt() and 0xFF; val b1 = b[1].toInt() and 0xFF
            return b0 == 10 || b0 == 127 || b0 == 0 || (b0 == 172 && (b1 and 0xF0) == 16) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254) ||
                (b0 == 100 && (b1 and 0xC0) == 64) || b0 >= 224
        }
        return (b[0].toInt() and 0xE0) != 0x20   // only 2000::/3 is global unicast
    }

    fun usable(a: InetAddress) = testLocal || !isLocal(a)

    /** The port of the first `lan` candidate (the peer's call socket on the shared Wi-Fi), or null. */
    fun lanOf(a: JSONArray): Int? {
        for (i in 0 until a.length()) {
            val c = a.optJSONObject(i) ?: continue
            if (c.optString("t") != "lan") continue
            val p = (c.opt("p") as? Number)?.toInt() ?: continue
            if (p in 1..65535) return p
        }
        return null
    }

    /** An IPv4 or IPv6 literal, or null for anything else (a peer's candidate never makes this phone look a name up). */
    fun literal(s: String): InetAddress? {
        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(s)
        if (v4 != null) {
            val b = v4.groupValues.drop(1).map { it.toInt() }
            if (b.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { b[it].toByte() })
        }
        if (s.contains(':') && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' })
            return try { InetAddress.getByName(s) } catch (e: Exception) { null }
        return null
    }

    /** 5.2: an offer is fresh if its ts is at most 60 s old and at most 120 s ahead. */
    fun fresh(tsMs: Long, nowMs: Long) = tsMs >= nowMs - 60_000 && tsMs <= nowMs + 120_000

    /**
     * Answered on the locked ringing screen: the offer, opened after unlocking, is answered only while the caller is
     * still ringing ([RING_MS] from its time). Older, it's a missed call.
     */
    fun lockedAnswerOk(tsMs: Long, nowMs: Long) = nowMs - tsMs <= RING_MS && tsMs <= nowMs + 120_000

    // The jitter buffer (5.5) is Jitter.kt.

    // ------------------------------------------------------------------ speaking indicator (5.5)

    /** Speaking while the 20 ms level is above -45 dBFS for 2 frames in a row, held 300 ms after it drops. */
    class Speaking {
        private var loud = 0
        private var until = 0L
        fun feed(pcm: ShortArray, n: Int, nowMs: Long): Boolean {
            if (dbfs(pcm, n) > -45.0) { if (++loud >= 2) until = nowMs + 300 } else loud = 0
            return nowMs < until
        }
    }

    fun dbfs(pcm: ShortArray, n: Int): Double {
        if (n <= 0) return -120.0
        var sum = 0.0
        for (i in 0 until n) { val x = pcm[i] / 32768.0; sum += x * x }
        val rms = sqrt(sum / n)
        return if (rms <= 0) -120.0 else 20 * log10(rms)
    }

    // ------------------------------------------------------------------ paths (5.6)

    /**
     * Where the peer is on the relay (5.6). [advertised] is the relayed address from its offer, answer or
     * cands. Some relays translate addresses between their sites (Cloudflare anchors each allocation at
     * the nearest data center), so the peer's packets can arrive from a different port, or even a
     * different address, than the one it advertised. Media is authenticated end to end, so the first
     * authentic packet from such an address makes it [seen], and media goes to both from then on.
     */
    class RelayPeer {
        @Volatile var advertised: InetSocketAddress? = null; private set
        @Volatile var seen: InetSocketAddress? = null; private set

        /** A new advertised address (from signaling). True when it changed. */
        fun advertise(a: InetSocketAddress?): Boolean {
            if (a == null || a == advertised) return false
            advertised = a
            if (seen == a) seen = null
            return true
        }

        /** An authentic packet came relayed from [from]. True when it is a new address to permit and send to. */
        fun heard(from: InetSocketAddress?): Boolean {
            if (from == null || from == advertised || from == seen) return false
            seen = from
            return true
        }

        /** A new allocation of ours: what the old one heard is gone. */
        fun forgetSeen() { seen = null }

        /** Every relayed address to send to: the advertised one, then the one its packets come from. */
        fun targets(): List<InetSocketAddress> = listOfNotNull(advertised, seen?.takeIf { it != advertised })
    }

    enum class Kind { RELAY, WIFI, BLUETOOTH, DIRECT }

    /** A free path (Wi-Fi or Direct) that answered a ping this recently rests the relay (5.8.3). */
    const val FRESH_MS = 750L

    /** One way to reach the peer. Live while an authentic packet came on it within 4 s. */
    class Path(val kind: Kind, val addr: InetSocketAddress?) {
        @Volatile var lastIn = 0L
        @Volatile var lastPing = 0L
        /** The last pong on this path: it works both ways (not only towards us past a one-way firewall). */
        @Volatile var lastPong = 0L
        @Volatile var rttMs = -1L
        val added = System.currentTimeMillis()
        fun live(now: Long) = lastIn > 0 && now - lastIn < PATH_DEAD_MS
    }

    /**
     * An authentic packet came on [kind] from [from]: that path is live now. A [fresh] packet (it opened as new) may
     * also teach a path: a Wi-Fi address on a second interface (up to 3), the Direct address the peer's NAT picked (up
     * to 6), the relay before its cands arrived. A duplicate only refreshes a path that is already there: someone who
     * replays a copy from their own address gains nothing (5.8.2).
     */
    fun markLive(paths: MutableList<Path>, kind: Kind, from: InetSocketAddress?, fresh: Boolean, now: Long): Path? {
        fun find() = paths.firstOrNull { it.kind == kind && ((kind != Kind.WIFI && kind != Kind.DIRECT) || it.addr == from) }
        var p = find()
        if (p == null && fresh && from != null) {
            when (kind) {
                Kind.WIFI -> if (paths.count { it.kind == kind } < 3) p = Path(kind, from).also { paths += it }
                Kind.DIRECT -> if (paths.count { it.kind == kind } < 6) p = Path(kind, from).also { paths += it }
                Kind.RELAY -> if (paths.none { it.kind == kind }) p = Path(kind, from).also { paths += it }
                else -> {}
            }
        }
        p?.lastIn = now
        return p
    }

    /** Where one sealed packet goes: [kind] says which socket (the TURN allocation or the Wi-Fi one). */
    data class Dest(val kind: Kind, val addr: InetSocketAddress)

    /**
     * The UDP sends for one packet over [targets] (paths, best first): up to [max] paths, Wi-Fi first then
     * the lowest round trip. The relay counts as one path however many relay paths are known, and each
     * distinct relayed address in [relayTargets] gets the packet once; each Wi-Fi address once too.
     */
    fun udpSends(targets: List<Path>, relayTargets: List<InetSocketAddress>, max: Int = 3, restRelay: Boolean = false): List<Dest> {
        val chosen = targets.filter { it.kind != Kind.BLUETOOTH && !(restRelay && it.kind == Kind.RELAY) }
            .sortedWith(compareBy({ when (it.kind) { Kind.WIFI -> 0; Kind.DIRECT -> 1; else -> 2 } }, { if (it.rttMs < 0) Long.MAX_VALUE else it.rttMs }))
            .distinctBy { if (it.kind == Kind.RELAY) "relay" else "${it.kind}|${it.addr}" }
            .take(max)
        val out = LinkedHashSet<Dest>()
        for (p in chosen) when (p.kind) {
            Kind.RELAY -> for (a in relayTargets) out += Dest(Kind.RELAY, a)
            Kind.WIFI, Kind.DIRECT -> p.addr?.let { out += Dest(p.kind, it) }
            else -> {}
        }
        return out.toList()
    }

    /**
     * When to ping a path: while punching (the first 15 s, never live yet) every 50 ms for 2 s then every
     * 250 ms; once live every 1 s (keeps NAT and relay mappings open), Wi-Fi and Direct every 250 ms so the relay
     * takes over within a second when they stop (5.8.3); dead ones every 2 s for revival.
     */
    fun pingDue(p: Path, now: Long): Boolean {
        val gap = when {
            p.live(now) -> if (p.kind == Kind.WIFI || p.kind == Kind.DIRECT) 250L else 1_000L
            p.lastIn == 0L && now - p.added < 2_000 -> 50L
            p.lastIn == 0L && now - p.added < 15_000 -> 250L
            else -> 2_000L
        }
        return now - p.lastPing >= gap
    }
}
