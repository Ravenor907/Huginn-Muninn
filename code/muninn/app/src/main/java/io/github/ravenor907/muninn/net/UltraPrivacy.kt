package io.github.ravenor907.muninn.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Ultra privacy (Settings › Privacy and lock, off by default): the internet address stays hidden.
 *
 * While it's on, everything Muninn does on the internet through [Net.client] (the Nostr relays, the call relay's Worker,
 * update checks and downloads, and the voices someone else may download) goes through Tor, built into Muninn
 * ([TorRunner]), as SOCKS5 with the names looked up by Tor, never here. Anything that would still show the address is
 * off, each with [OFF_NOTE]: calls (Tor can't carry a call's sound, and the call relay would see the address, see
 * [CALLS_OFF]), direct files and photos, sending videos (receiving still works), link previews and GIF search. Nearby and
 * the same Wi-Fi stay available: they stay on the Wi-Fi the phone is on.
 *
 * Fails closed: on, with Tor not ready yet, every connection is sent to a port where nothing listens, so it fails
 * instead of going out the normal way. The rules here are pure, so they're tested off the phone (UltraPrivacyTest).
 */
object UltraPrivacy {
    /** What every feature that's off says. */
    const val OFF_NOTE = "Off while Ultra privacy is on"
    /** What a video send says while it's on (videos are too big to send through Tor). */
    const val VIDEO_OFF = "Sending videos: $OFF_NOTE. You can still receive them."
    /** Said under Nearby and the same Wi-Fi, which stay on. */
    const val NEARBY_NOTE = "Still on with Ultra privacy: Nearby and the same Wi-Fi. They stay on this Wi-Fi, so on a public Wi-Fi others there can see Nearby."
    /** Owner decision (round 19): calls are off while it's on, both ways. What a call that can't start says. */
    const val CALLS_OFF = "Calls are off while Ultra privacy is on."
    /** The missed-call note for a call that came while it's on ("<name> called ..."). */
    const val CALLS_OFF_MISSED = "while Ultra privacy was on. Calls are off until you turn it off."
    /** The saved switch, in Muninn's own settings (outside the vault: the relays connect before it's unlocked). */
    const val PREF = "ultraPrivacy"
    /** Where nothing listens: with Tor not ready, a connection fails here instead of leaving directly. */
    const val CLOSED_PORT = 1

    /** Tor while Ultra privacy is on. */
    enum class Tor { OFF, STARTING, READY, SLOW, BLOCKED, MISSING }

    /** Slow after this long without a circuit, blocked after [BLOCKED_MS] (the banner offers Wait or Turn off). */
    const val SLOW_MS = 45_000L
    const val BLOCKED_MS = 120_000L

    @Volatile private var enabled = false
    @Volatile private var port = 0

    /** Ultra privacy is on. */
    val on: Boolean get() = enabled

    private val _state = MutableStateFlow(Tor.OFF)
    /** Tor's state, for the banner and Settings. */
    val state: StateFlow<Tor> = _state.asStateFlow()
    private val _progress = MutableStateFlow(0)
    /** Tor's start, 0 to 100. */
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** Run when Ultra privacy turns on or off, or Tor's port changes: sockets open now go, the next ones use the new way. */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onChange(f: () -> Unit) { listeners += f }
    /** Run once each time it turns on: whatever already goes straight to someone (a call, a file offer) stops. */
    private val onListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onTurnedOn(f: () -> Unit) { onListeners += f }
    /** Run when Tor has a circuit again on the same port: what isn't connected tries again now (nothing is dropped). */
    private val readyListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onReadyAgain(f: () -> Unit) { readyListeners += f }
    /**
     * Where the listeners run. Null (the tests): right away, on the caller's thread. The app sets a background thread
     * ([TorRunner.init]), so closing sockets never runs on the main thread when the switch is tapped.
     */
    @Volatile var listenerThread: java.util.concurrent.Executor? = null
    private fun fire(list: List<() -> Unit>) {
        val all = list.toList()
        if (all.isEmpty()) return
        val go = Runnable { for (f in all) try { f() } catch (_: Exception) {} }
        val e = listenerThread
        if (e == null) go.run() else try { e.execute(go) } catch (_: Exception) { go.run() }
    }
    private fun changed() = fire(listeners)

    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    /**
     * The proxy for anything on the internet: null while Ultra privacy is off (connect the normal way); Tor's SOCKS5
     * port on 127.0.0.1 while it's on, or [CLOSED_PORT] until Tor is ready, so nothing leaves directly meanwhile.
     */
    fun proxy(): Proxy? {
        if (!enabled) return null
        val p = port
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress(loopback, if (p in 1..65535) p else CLOSED_PORT))
    }

    /** For OkHttp (Net.client): every connection asks this, so turning it on or off takes effect on the next one. */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy() ?: Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {}
    }

    /**
     * Names are never looked up here while Ultra privacy is on: through SOCKS5 Tor looks them up (OkHttp hands it the
     * name), and anything that would look one up here fails instead.
     */
    val dns: okhttp3.Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (enabled) throw java.net.UnknownHostException("Ultra privacy: names are looked up through Tor only")
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }
    }

    // ------------------------------------------------------------------ what's off while it's on

    /** Straight over the internet to someone (the direct call path, direct files and photos, STUN). */
    val directAllowed: Boolean get() = !enabled
    /** Calls and voice rooms, in and out (owner decision, round 19): off while it's on. */
    val callsAllowed: Boolean get() = !enabled
    /** Sending videos (video messages, group videos, video files). Receiving still works. */
    val videoSendAllowed: Boolean get() = !enabled
    /** Link previews (the site would see the address). */
    val previewsAllowed: Boolean get() = !enabled
    /** GIF search (the keyboard's search reaches the internet outside Muninn). */
    val gifSearchAllowed: Boolean get() = !enabled

    private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "3g2", "avi", "wmv", "flv", "mpg", "mpeg", "ogv", "hevc", "h264", "h265")

    /** [name] is a video file (by its ending). */
    fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** A file that may not be sent now: a video while Ultra privacy is on. */
    fun blocksFile(name: String, mime: String = ""): Boolean = enabled && (isVideoName(name) || mime.lowercase().startsWith("video/"))

    /** The same for a file on disk: by its name, or by its first bytes (a video renamed .bin still goes nowhere). */
    fun blocksFile(file: java.io.File, name: String): Boolean = enabled && (isVideoName(name) || looksLikeVideo(head(file)))

    private fun head(file: java.io.File): ByteArray = try {
        file.inputStream().use { s -> val b = ByteArray(64); var n = 0
            while (n < b.size) { val r = s.read(b, n, b.size - n); if (r <= 0) break; n += r }
            b.copyOf(n) }
    } catch (_: Exception) { ByteArray(0) }

    /** Round 19 (Windows m11): the first bytes of a file look like a video. The same rule as Huginn's UltraPrivacy::looksLikeVideo. */
    fun looksLikeVideo(h: ByteArray): Boolean {
        if (h.size < 12) return false
        fun at(i: Int, s: String) = i + s.length <= h.size && s.indices.all { h[i + it] == s[it].code.toByte() }
        fun starts(vararg b: Int) = b.size <= h.size && b.indices.all { h[it] == b[it].toByte() }
        val box = String(h, 4, 4, Charsets.ISO_8859_1)
        if (box == "ftyp") {
            // Photos (HEIC, AVIF) and sound (M4A) use the same box: only those aren't videos.
            return String(h, 8, 4, Charsets.ISO_8859_1) !in NOT_VIDEO_BRANDS
        }
        if (box == "moov" || box == "mdat" || box == "wide") return true   // MP4, MOV, 3GP
        if (box == "free" || box == "skip") {
            val all = String(h, Charsets.ISO_8859_1)
            if (all.contains("moov") || all.contains("mdat") || all.contains("ftyp")) return true   // an older QuickTime file
        }
        if (starts(0x1A, 0x45, 0xDF, 0xA3)) return true   // Matroska, WebM
        if (at(0, "RIFF") && at(8, "AVI ")) return true
        if (starts(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11)) return true   // ASF, WMV
        if (starts(0x46, 0x4C, 0x56, 0x01)) return true   // FLV
        if (starts(0x00, 0x00, 0x01, 0xBA) || starts(0x00, 0x00, 0x01, 0xB3)) return true   // MPEG program or video stream
        return false
    }
    private val NOT_VIDEO_BRANDS = setOf("heic", "heix", "heim", "heis", "mif1", "msf1", "avif", "M4A ", "M4B ", "M4P ", "F4A ", "F4B ")

    // ------------------------------------------------------------------ the switch and Tor

    /** Turns it on or off in memory (and starts or stops nothing): [TorRunner] and Settings call [set]. Tests use this. */
    fun use(on: Boolean) {
        if (enabled == on) return
        enabled = on
        port = 0
        _progress.value = 0
        _state.value = if (on) Tor.STARTING else Tor.OFF
        // Turned on: what goes straight to someone stops first, then the connections are made again the new way.
        if (on) fire(onListeners)
        changed()
    }

    /** Tor is ready on [socksPort]: the next connections go through it, and the ones open now are made again. */
    fun ready(socksPort: Int) {
        if (!enabled || socksPort !in 1..65535) return
        // Only a new port makes the open connections again (they already go through Tor on the same one).
        val again = port != socksPort
        val wasReady = _state.value == Tor.READY
        port = socksPort
        _progress.value = 100
        _state.value = Tor.READY
        if (again) changed() else if (!wasReady) fire(readyListeners)
    }

    /**
     * Tor is up on [socksPort] but has no circuit yet (starting, or after the phone slept). Connections go to its port
     * anyway: it's Tor, so nothing leaves directly, and asking it is what makes Tor build a circuit again. The ones open
     * now are only made again when the port itself changes, never on a ready, waiting, ready flap.
     */
    fun waiting(socksPort: Int, percent: Int, ms: Long, warned: Boolean = false) {
        if (!enabled || socksPort !in 1..65535) return
        val again = port != socksPort
        port = socksPort
        _progress.value = percent.coerceIn(0, 99)
        _state.value = startState(ms, warned)
        if (again) changed()
    }

    /** Tor's start, while it isn't ready: [percent] and how long it has taken ([ms]) decide slow or blocked. */
    fun starting(percent: Int, ms: Long, warned: Boolean = false) {
        if (!enabled) return
        if (port != 0) { port = 0; changed() }   // Tor went away: fail closed until it's back
        _progress.value = percent.coerceIn(0, 99)
        _state.value = startState(ms, warned)
    }

    /** Tor isn't in this build or couldn't start at all: nothing goes out until it's turned off. */
    fun missing() { if (enabled) { port = 0; _state.value = Tor.MISSING } }

    /** STARTING, SLOW or BLOCKED for a start that has taken [ms] ([warned]: Tor said it can't reach the network). */
    fun startState(ms: Long, warned: Boolean): Tor = when {
        ms >= BLOCKED_MS || (warned && ms >= SLOW_MS) -> Tor.BLOCKED
        ms >= SLOW_MS -> Tor.SLOW
        else -> Tor.STARTING
    }

    /** Tor's "status/bootstrap-phase" ("NOTICE BOOTSTRAP PROGRESS=45 TAG=...") to 45. */
    fun percentOf(phase: String): Int = Regex("""PROGRESS=(\d+)""").find(phase)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** The banner shows: Tor is slow, blocked or missing while Ultra privacy is on. */
    fun bannerShows(t: Tor): Boolean = t == Tor.SLOW || t == Tor.BLOCKED || t == Tor.MISSING

    /** The banner's words for [t]. */
    fun bannerText(t: Tor): String = when (t) {
        Tor.SLOW -> "Tor is slow to connect. Messages wait until it does."
        Tor.BLOCKED -> "Tor can't connect here. It may be blocked on this network. Messages wait until it connects."
        Tor.MISSING -> "Tor couldn't start on this device. Nothing goes out until you turn Ultra privacy off."
        else -> ""
    }

    /** The line under the switch in Settings. */
    fun statusLine(t: Tor, percent: Int): String = when (t) {
        Tor.OFF -> "Off. Hides your internet address using Tor."
        Tor.STARTING -> "Connecting to Tor… $percent%"
        Tor.READY -> "On. Your internet address is hidden."
        Tor.SLOW -> "Tor is slow to connect… $percent%"
        Tor.BLOCKED -> "Tor can't connect on this network."
        Tor.MISSING -> "Tor couldn't start."
    }

    /** What it does: the first screen before turning it on. */
    val WHAT_IT_DOES = listOf(
        "Hides your internet address from the relays, from GitHub and from everyone you message.",
        "Messages, update checks and downloads go through Tor, a volunteer network that hides where they come from.",
        "Calls are off while it's on: Tor can't carry a call, and the call relay would see your address.",
        "Anything that would still show your address is turned off while it's on.",
        "Nearby and the same Wi-Fi keep working: they stay on this Wi-Fi.",
    )

    /** Every downside, the second screen, before Confirm. */
    val DOWNSIDES = listOf(
        "Messages are slower, and may arrive much later while your phone is idle.",
        "Calls are off, both ways, because the call relay sees your address. Calls to you show as missed.",
        "Direct file and photo transfers are off.",
        "Sending videos is off. You can still receive them.",
        "Link previews and GIF search are off.",
        "Update checks and downloads are slower.",
        "Uses more battery.",
        "Tor is built in, which makes Muninn's download about 5 to 8 MB bigger.",
        "Tor is blocked in some places. If it can't connect, you can wait or turn Ultra privacy off.",
        "Tor is run by volunteers, so its speed is out of our hands.",
        "It adds no encryption. Messages are already end-to-end encrypted; Ultra privacy hides where they come from, not who you talk to.",
    )

    /** First setup's "Your privacy, built in" page. */
    const val SETUP_TITLE = "Your privacy, built in"
    val SETUP_LINES = listOf(
        "Messages and calls are end-to-end encrypted. Only the people in the chat can read or hear them.",
        "Relays only carry sealed messages. They can't read them or see who sent them.",
        "Photos are sent without their location or camera details.",
        "No phone number, no account.",
    )
    const val SETUP_ULTRA = "Want more? Ultra privacy hides your internet address too, using Tor. It's in Settings › Privacy and lock, " +
        "off until you turn it on. It has a few trade-offs, explained before you turn it on."
}
