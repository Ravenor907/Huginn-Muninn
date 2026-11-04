package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.update.Versions
import org.json.JSONObject

/**
 * Cap u1 (shared/docs/protocol.md 7.17): a parent's phone tells a Kids' TV to update now, from anywhere, and the TV
 * says how its update is going. Both frames go in room "tvconfig", sealed pairwise like the rest of section 7, so they
 * reach the TV over the internet relays too. Kept free of Android so the unit tests run it.
 */
object TvUpd {
    const val CAP = "u1"

    /** The TV's update states, in "s" of an "upstate". */
    const val CHECKING = "checking"
    const val DOWNLOADING = "downloading"
    const val INSTALLING = "installing"
    const val CURRENT = "current"
    const val TAP = "tap"
    const val FAILED = "failed"
    val STATES = listOf(CHECKING, DOWNLOADING, INSTALLING, CURRENT, TAP, FAILED)

    const val MAX_APP = 16
    const val MAX_WHY = 60
    private const val MAX_INT = (1L shl 53) - 1

    /** What the Update now button says it can't do while the TV's app lacks u1. */
    const val NEEDS_FIRST = "Update this device first"

    /** What the TV shows while an update waits for someone at the TV (fallback when it can't install by itself). */
    const val TV_PROMPT = "Ask a grown-up: press OK to update"
    /** The same on a tablet or phone. */
    const val TOUCH_PROMPT = "Ask a grown-up: tap Update"

    /** One "upstate": the TV's Muninn version [app], state [s], percent [p] (only while downloading), [why] (only when failed), last check [at] (ms, 0: never). */
    data class St(val app: String, val s: String, val p: Int? = null, val why: String? = null, val at: Long = 0)

    /** Parent to TV: update now. Exactly {"t":"upnow"}. */
    fun nowMessage(): String = JSONObject().put("t", "upnow").toString()

    /** True when [o] is a well-formed "upnow" (no other fields). */
    fun isNow(o: JSONObject): Boolean = Canon.keys(o) == setOf("t") && o.opt("t") == "upnow"

    /** The TV takes "upnow" only from one of its current parents, and only once it has a config. */
    fun fromParent(fromKey: String, parents: Collection<String>, hasConfig: Boolean): Boolean = hasConfig && fromKey in parents

    /** TV to parent: how its update is going. */
    fun stateMessage(st: St): String {
        require(st.s in STATES) { "s" }
        val o = JSONObject().put("t", "upstate").put("app", clip(st.app, MAX_APP)).put("s", st.s).put("at", st.at.coerceIn(0L, MAX_INT))
        if (st.s == DOWNLOADING) o.put("p", (st.p ?: 0).coerceIn(0, 100))
        if (st.s == FAILED && !st.why.isNullOrBlank()) o.put("why", clip(st.why, MAX_WHY))
        return o.toString()
    }

    /**
     * An "upstate", or null when it isn't well-formed: "app" text 1 to 16 characters, "s" one of [STATES], "at" an integer
     * 0 <= n < 2^53, "p" an integer 0 to 100 (only with downloading, where it must be there), "why" text 1 to 60 characters
     * (only with failed, optional). Nothing else.
     */
    fun stateIn(o: JSONObject): St? {
        if (o.opt("t") != "upstate") return null
        val keys = Canon.keys(o)
        val app = o.opt("app") as? String ?: return null
        val s = o.opt("s") as? String ?: return null
        val at = o.opt("at")
        if (s !in STATES || !Canon.isInt(at)) return null
        val atL = (at as Number).toLong()
        if (atL !in 0..MAX_INT) return null
        if (!textOk(app, MAX_APP)) return null
        val allowed = setOf("t", "app", "s", "at") + (if (s == DOWNLOADING) setOf("p") else emptySet()) + (if (s == FAILED) setOf("why") else emptySet())
        if (!allowed.containsAll(keys)) return null
        val p = if (s == DOWNLOADING) {
            val v = o.opt("p")
            if (!Canon.isInt(v)) return null
            (v as Number).toLong().takeIf { it in 0L..100L }?.toInt() ?: return null
        } else null
        var why: String? = null
        if (o.has("why")) why = (o.opt("why") as? String)?.takeIf { textOk(it, MAX_WHY) } ?: return null
        return St(app, s, p, why, atL)
    }

    /** What a parent's phone says for the TV's state. */
    fun text(st: St?): String = if (st == null) "" else when (st.s) {
        CHECKING -> "Checking"
        DOWNLOADING -> "Downloading ${st.p ?: 0}%"
        INSTALLING -> "Installing"
        CURRENT -> "Up to date"
        TAP -> "Needs a tap on the device"
        FAILED -> if (st.why.isNullOrBlank()) "Failed" else "Failed: ${st.why}"
        else -> ""
    }

    /**
     * The one line for a TV: "Muninn 1.33 · Up to date". [behind]: this phone knows a newer stable Muninn than the TV runs,
     * said when the TV has nothing more urgent to report. [legacy]: what an older TV's status said, when it sends no "upstate".
     */
    fun line(app: String, st: St?, behind: Boolean, legacy: String = ""): String {
        val v = if (app.isBlank()) "Muninn on the device" else "Muninn $app"
        val busy = st != null && st.s != CURRENT
        val what = when {
            busy -> text(st)
            behind -> "Update available"
            st != null -> text(st)
            else -> legacy
        }
        return if (what.isEmpty()) v else "$v · $what"
    }

    /** "Last check: 5 min ago", from [at] (ms) and [now]; "Last check: not yet" when it never checked. */
    fun lastCheck(at: Long, now: Long): String {
        if (at <= 0) return "Last check: not yet"
        val m = ((now - at).coerceAtLeast(0)) / 60_000
        return "Last check: " + when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }

    /**
     * The TV is behind: this phone runs (or found) a newer stable Muninn than [tvApp]. Only stable versions count, so a
     * phone on the Pre-release channel doesn't call a TV on the latest release behind.
     */
    fun behind(tvApp: String, phone: String, phoneLatest: String = ""): Boolean {
        if (tvApp.isBlank()) return false
        val known = listOf(phone, phoneLatest).filter { Versions.isStable(it) }.maxWithOrNull { a, b -> Versions.compare(a, b) } ?: return false
        return Versions.newer(known, tvApp)
    }

    /** Update now on the phone: only for a TV whose app says u1. */
    fun canAsk(tvCaps: Set<String>): Boolean = CAP in tvCaps

    /**
     * Whether [next] is worth a frame after [prev]: a new state, version or reason, or the download moved on by a tenth.
     */
    fun worthSending(prev: St?, next: St): Boolean {
        if (prev == null || prev.s != next.s || prev.app != next.app || prev.why != next.why || prev.at != next.at) return true
        return next.s == DOWNLOADING && (prev.p ?: 0) / 10 != (next.p ?: 0) / 10
    }

    /** Milliseconds before the [n]th try again after a failure (n from 1): 5 min, 15 min, 1 h, 4 h, then 12 h. */
    fun retryMs(n: Int): Long = when {
        n <= 1 -> 5 * 60_000L
        n == 2 -> 15 * 60_000L
        n == 3 -> 60 * 60_000L
        n == 4 -> 4 * 60 * 60_000L
        else -> 12 * 60 * 60_000L
    }

    /**
     * The TV's state from the Updater's (update/Updater): its [status] line, whether it's [busy] and its download [progress].
     * Null: nothing to say (the install itself reports through the TV's PackageInstaller session).
     */
    fun fromUpdater(status: String, busy: Boolean, progress: Float): Pair<String, String?>? = when {
        status.startsWith("Checking for updates") -> CHECKING to null
        status.startsWith("Downloading") -> DOWNLOADING to null
        status.startsWith("Installs when the file") -> DOWNLOADING to null
        status == "Muninn is up to date" -> CURRENT to null
        status.contains("isn't newer than") -> CURRENT to null
        status.startsWith("Couldn't check") -> FAILED to "No internet"
        status.startsWith("Couldn't find") -> FAILED to "No release found"
        status.startsWith("Not enough space") -> FAILED to NO_SPACE
        status.startsWith("The download stopped") -> FAILED to "Download stopped"
        status.contains("didn't match") -> FAILED to "Download didn't match"
        status.contains("can't be checked") -> FAILED to "Download can't be checked"
        status.contains("isn't signed") -> FAILED to "Not signed"
        status.contains("downloads on Wi-Fi") -> FAILED to "Waiting for Wi-Fi"
        busy && progress > 0f -> DOWNLOADING to null
        else -> null
    }

    /** The reason a parent reads when the device has no room for an update. */
    const val NO_SPACE = "Not enough space on the device"

    /**
     * Round 17 (TV minor 9): the downloaded APK is gone or empty ([exists], [bytes]). It waits in the cache, which Android
     * empties when space runs low, so that's said as no space (and tried again later), never "Couldn't start the install".
     */
    fun apkGone(exists: Boolean, bytes: Long): Boolean = !exists || bytes <= 0L

    /** Room for an install of an APK of [apkBytes] with [free] bytes free: Android copies it and unpacks it (2.5 times). */
    fun roomFor(apkBytes: Long, free: Long): Boolean = apkBytes <= 0L || free >= apkBytes * 5 / 2

    /** True when an error's [message] says the disk is full (ENOSPC). */
    fun isNoSpace(message: String?): Boolean = message != null && (message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true))

    /**
     * The state a device starts with, from the one it saved ([saved]). Checking and downloading were cut off. Installing
     * or Needs a tap were too: an install that went in shows as a newer version, and one that didn't goes again with the
     * next check. Failed stays, with its reason.
     */
    fun onStart(saved: String?): String = saved?.takeIf { it == FAILED || it == CURRENT } ?: CURRENT

    private fun textOk(s: String, max: Int): Boolean =
        s.isNotEmpty() && s.none { it.code < 0x20 || it.code == 0x7F } && s.codePointCount(0, s.length) <= max

    private fun clip(s: String, max: Int): String {
        val t = s.filter { it.code >= 0x20 && it.code != 0x7F }.trim().ifEmpty { "?" }
        return if (t.codePointCount(0, t.length) <= max) t else t.substring(0, t.offsetByCodePoints(0, max)).trim()
    }
}
