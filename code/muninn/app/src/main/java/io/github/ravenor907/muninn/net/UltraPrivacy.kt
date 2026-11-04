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
 * off, each with [OFF_NOTE]: the direct call path (calls always go through the call relay), direct files and photos,
 * sending videos (receiving still works), link previews and GIF search. Nearby and the same Wi-Fi stay available:
 * they never leave the home network.
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
    const val NEARBY_NOTE = "Still on with Ultra privacy: Nearby and the same Wi-Fi never leave your home network."
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

    /** Run when Ultra privacy turns on or off, or Tor becomes ready: sockets open now go, the next ones use the new way. */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onChange(f: () -> Unit) { listeners += f }
    private fun changed() { for (f in listeners) try { f() } catch (_: Exception) {} }

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

    // ------------------------------------------------------------------ the switch and Tor

    /** Turns it on or off in memory (and starts or stops nothing): [TorRunner] and Settings call [set]. Tests use this. */
    fun use(on: Boolean) {
        if (enabled == on) return
        enabled = on
        port = 0
        _progress.value = 0
        _state.value = if (on) Tor.STARTING else Tor.OFF
        changed()
    }

    /** Tor is ready on [socksPort]: the next connections go through it, and the ones open now are made again. */
    fun ready(socksPort: Int) {
        if (!enabled || socksPort !in 1..65535) return
        val again = port != socksPort || _state.value != Tor.READY
        port = socksPort
        _progress.value = 100
        _state.value = Tor.READY
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
        "Calls always go through the call relay, never straight to the other person.",
        "Anything that would still show your address is turned off while it's on.",
        "Nearby and the same Wi-Fi keep working: they never leave your home network.",
    )

    /** Every downside, the second screen, before Confirm. */
    val DOWNSIDES = listOf(
        "Messages are slower, and may arrive much later while your phone is idle.",
        "The call relay sees your address for calls, and calls use up the relay's daily allowance faster.",
        "Direct calls and direct file and photo transfers are off.",
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
