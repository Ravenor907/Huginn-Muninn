package io.github.ravenor907.muninn.net

/**
 * Recorded videos picked from the phone (cap v3, shared/docs/protocol.md section 29), for a group or one person. The
 * rules only, the same as Huginn's GroupVideo (groupvideo.h), so both apps decide alike and JVM tests check them:
 *  - Full size: at most 1080p (1920 x 1080 either way up) and 30 frames a second, at most a minute and [FULL_MAX_BYTES].
 *    Anything bigger is converted. It goes straight (FileLink, as a file offer with "vid") to members on the same Wi-Fi,
 *    or with Direct calls and files on for each other while this phone is on Wi-Fi.
 *  - Everyone else gets a smaller copy over the relays: at most 720p, [RELAY_MAX_MS] long and [RELAY_PIECES] pieces, at
 *    a bitrate worked out to fit ([relayBitrate]), its pieces paced ([PACE_PER_SECOND]) so public relays don't block us.
 *  - Longer than [RELAY_MAX_MS]: trimmed to it, or sent in full only to those who can take it straight ("later").
 */
object GroupVideo {
    const val CAP = "v3"

    // Full size (straight, never through the relays).
    const val FULL_LONG = 1920
    const val FULL_SHORT = 1080
    const val FULL_FPS = 30
    const val FULL_VIDEO_BITRATE = 4_500_000
    const val FULL_AUDIO_BITRATE = 128_000
    /** A minute at most, as a video message ([More.VIDEO_MAX_MS]). */
    const val FULL_MAX_MS = 60_000
    /** Under FileLink.AUTO_BYTES (50 MB), so it arrives by itself without Accept or Decline. A minute at 4.6 Mbps is about 35 MB. */
    const val FULL_MAX_BYTES = 48L * 1024 * 1024

    // The relay copy.
    const val RELAY_LONG = 1280
    const val RELAY_SHORT = 720
    const val RELAY_MAX_MS = 20_000
    /** Pieces of [PIECE] (Photos.PIECE): 3 MB. Every app since m2 takes up to 200 (Photos.MAX_PIECES), so 1.41 apps take it too. */
    const val RELAY_PIECES = 192
    const val PIECE = 16 * 1024
    const val RELAY_BYTES = RELAY_PIECES * PIECE
    /** What the sound is counted as when the file doesn't say (Media3's AAC default is 128 kbps). */
    const val RELAY_AUDIO_BITRATE = 128_000
    const val MIN_VIDEO_BITRATE = 300_000
    const val MAX_RELAY_VIDEO_BITRATE = 1_500_000
    /** The share of [RELAY_BYTES] the bitrate aims at: room for the MP4's own boxes and an encoder that runs over. */
    const val RELAY_AIM = 0.90
    /** A copy that still came out too big is made once more at most at this share of the bitrate. */
    const val RETRY_SHARE = 0.85

    /** Slack on lengths read from a file (a 20.3 s clip is a 20 s clip). */
    const val SLACK_MS = 500

    // Pacing: relay pieces of videos, from this phone in all (every chat together).
    const val PACE_PER_SECOND = 3
    /** A relay said "rate-limited": nothing more goes for this long. */
    const val BACKOFF_MS = 60_000L

    /**
     * What a picked video is: its picture as stored ([rotation] 0, 90, 180 or 270), frames a second, length, size, and its
     * sound's bitrate ([audioBps], 0 when the file doesn't say).
     */
    data class Info(val w: Int, val h: Int, val rotation: Int, val fps: Float, val ms: Int, val bytes: Long, val audioBps: Int = 0)

    /** Upright width and height (a phone video stored sideways with a turn of 90 or 270). */
    fun upright(i: Info): Pair<Int, Int> = if (i.rotation % 180 == 90) i.h to i.w else i.w to i.h

    /** Its picture fits [long] x [short] either way up. */
    fun withinSize(w: Int, h: Int, long: Int, short: Int) = maxOf(w, h) <= long && minOf(w, h) <= short

    /** Frames a second within [max] (0 or unknown counts as fine: the converter is told the limit anyway). */
    fun withinFps(fps: Float, max: Int) = fps <= max + 0.5f

    /** It can go full size as it is (no converting): 1080p30 at most, small enough, and a minute at most. */
    fun fitsFull(i: Info) = i.w > 0 && i.h > 0 && withinSize(i.w, i.h, FULL_LONG, FULL_SHORT) && withinFps(i.fps, FULL_FPS) &&
        i.bytes in 1..FULL_MAX_BYTES && i.ms in 1..(FULL_MAX_MS + SLACK_MS)

    /** It must be converted before it goes full size (bigger than 1080p, faster than 30 fps, or too many bytes). */
    fun needsConvert(i: Info) = !(withinSize(i.w, i.h, FULL_LONG, FULL_SHORT) && withinFps(i.fps, FULL_FPS) && i.bytes in 1..FULL_MAX_BYTES)

    /** It can go over the relays as it is: 720p at most, short enough, within [RELAY_PIECES] pieces. */
    fun fitsRelay(i: Info) = i.w > 0 && i.h > 0 && withinSize(i.w, i.h, RELAY_LONG, RELAY_SHORT) && withinFps(i.fps, FULL_FPS) &&
        i.bytes in 1..RELAY_BYTES.toLong() && i.ms in 1..(RELAY_MAX_MS + SLACK_MS)

    /**
     * [w] x [h] shrunk (never grown) to fit [long] x [short] either way up, keeping its shape, both sides even (encoders
     * refuse odd sizes).
     */
    fun scaled(w: Int, h: Int, long: Int, short: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 0 to 0
        val f = minOf(1.0, long.toDouble() / maxOf(w, h), short.toDouble() / minOf(w, h))
        val sw = Math.round(w * f).toInt() and -2
        val sh = Math.round(h * f).toInt() and -2
        return sw.coerceAtLeast(2) to sh.coerceAtLeast(2)
    }

    /**
     * The video bitrate for a relay copy [ms] long that fits [pieces] pieces with its sound ([audioBps], or
     * [RELAY_AUDIO_BITRATE] when not known), within sensible bounds.
     */
    fun relayBitrate(ms: Int, pieces: Int = RELAY_PIECES, audioBps: Int = 0): Int {
        val s = ms.coerceAtLeast(1000) / 1000.0
        val all = pieces.toLong() * PIECE * 8 * RELAY_AIM / s
        val sound = if (audioBps > 0) audioBps else RELAY_AUDIO_BITRATE
        return (all - sound).toInt().coerceIn(MIN_VIDEO_BITRATE, MAX_RELAY_VIDEO_BITRATE)
    }

    /**
     * The bitrate for a second try after a copy [ms] long came out [gotBytes] at [was]: lowered by what it ran over, and
     * by at least a share ([RETRY_SHARE]).
     */
    fun retryBitrate(was: Int, gotBytes: Long, ms: Int): Int {
        val s = ms.coerceAtLeast(1000) / 1000.0
        val over = (gotBytes - RELAY_BYTES * RELAY_AIM) * 8 / s
        return minOf((was * RETRY_SHARE).toInt(), (was - over).toInt()).coerceAtLeast(MIN_VIDEO_BITRATE)
    }

    /** The full copy's bitrate: [FULL_VIDEO_BITRATE], or less so a long clip still stays under [FULL_MAX_BYTES]. */
    fun fullBitrate(ms: Int): Int {
        val s = ms.coerceAtLeast(1000) / 1000.0
        val most = (FULL_MAX_BYTES * 8 * RELAY_AIM / s - FULL_AUDIO_BITRATE).toInt()
        return minOf(FULL_VIDEO_BITRATE, most).coerceAtLeast(MIN_VIDEO_BITRATE)
    }

    /** A relay copy of [bytes] fits. */
    fun relayFits(bytes: Long) = bytes in 1..RELAY_BYTES.toLong()

    // ------------------------------------------------------------------ length

    /** Short enough for everyone. */
    const val SHORT = "short"
    /** Longer than [RELAY_MAX_MS], at most a minute: trim, or send in full later to those who can take it straight. */
    const val LONG = "long"
    /** Over a minute: trim only (a longer video can go as a file). */
    const val TOO_LONG = "too long"

    fun length(ms: Int): String = when {
        ms <= RELAY_MAX_MS + SLACK_MS -> SHORT
        ms <= FULL_MAX_MS + SLACK_MS -> LONG
        else -> TOO_LONG
    }

    /** The latest a [RELAY_MAX_MS] trim of a video [ms] long may start. */
    fun lastStart(ms: Int) = (ms - RELAY_MAX_MS).coerceAtLeast(0)

    /** A trim from [startMs]: start and end, kept inside the video. */
    fun trim(ms: Int, startMs: Int): Pair<Int, Int> {
        val s = startMs.coerceIn(0, lastStart(ms))
        return s to minOf(ms, s + RELAY_MAX_MS)
    }

    /** "1:05" for [ms]. */
    fun clock(ms: Int): String { val s = (ms.coerceAtLeast(0) + 500) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

    // ------------------------------------------------------------------ who gets what

    /**
     * One member (or the one person), as this phone sees them now. [sameWifi]: on this Wi-Fi now (proved). [trusted]:
     * Direct calls and files on for each other. [files]: their app takes straight files here (f1, and f2 in a group).
     * [v3]: their app takes a full-size video offer. [kid]: a kids' device (always the relay copy). [relayOnly]: their
     * app asked for nothing straight (Ultra privacy on their side).
     */
    data class Member(val k: String, val sameWifi: Boolean, val trusted: Boolean, val files: Boolean, val v3: Boolean,
                      val kid: Boolean = false, val relayOnly: Boolean = false)

    const val FULL = "full"
    const val RELAY = "relay"
    /** Gets nothing this time (a long video sent in full, and no straight way to them). */
    const val NONE = "none"

    /** May take a full copy at all. */
    fun fullOk(m: Member) = m.files && m.v3 && !m.kid && !m.relayOnly

    /**
     * Full copy or relay copy for [m] now. Full needs their app to take it, and the same Wi-Fi, or trust while this phone
     * is on an unmetered network (a minute of 1080p to several people over mobile data is hundreds of MB).
     */
    fun delivery(m: Member, unmetered: Boolean): String =
        if (fullOk(m) && (m.sameWifi || (m.trusted && unmetered))) FULL else RELAY

    /** A long video "in full, later when direct": who gets the full copy (it waits for them up to a day), who gets nothing. */
    fun later(m: Member): String = if (fullOk(m) && (m.sameWifi || m.trusted)) FULL else NONE

    /** The plan for everyone: key to [FULL], [RELAY] or [NONE]. [inFull]: the long video sent in full. */
    fun plan(members: List<Member>, unmetered: Boolean, inFull: Boolean): Map<String, String> =
        members.associate { it.k to if (inFull) later(it) else delivery(it, unmetered) }

    /** What the send sheet says about [plan] ([nameOf] each key). */
    fun summary(plan: Map<String, String>, nameOf: (String) -> String): String {
        val full = plan.filterValues { it == FULL }.keys.map(nameOf)
        val relay = plan.filterValues { it == RELAY }.keys.map(nameOf)
        val none = plan.filterValues { it == NONE }.keys.map(nameOf)
        return listOfNotNull(
            full.takeIf { it.isNotEmpty() }?.let { "Full size (1080p) straight to ${names(it)}." },
            relay.takeIf { it.isNotEmpty() }?.let { "A smaller copy (720p, up to 20 seconds) over the internet to ${names(it)}." },
            none.takeIf { it.isNotEmpty() }?.let { "Not to ${names(it)}: they need the same Wi-Fi or Direct calls and files with you, and the latest app." },
        ).joinToString(" ").ifEmpty { "Nobody else is here yet." }
    }

    /** "Anna", "Anna and Bo", "Anna, Bo and 3 more". */
    fun names(n: List<String>): String = when {
        n.isEmpty() -> ""
        n.size == 1 -> n[0]
        n.size == 2 -> "${n[0]} and ${n[1]}"
        n.size == 3 -> "${n[0]}, ${n[1]} and ${n[2]}"
        else -> "${n[0]}, ${n[1]} and ${n.size - 2} more"
    }

    // ------------------------------------------------------------------ pacing

    /**
     * When each relay piece may go: at most [perSecond] a second from this phone, and nothing while a relay's
     * "rate-limited" answer is less than [BACKOFF_MS] old. Not thread safe on its own (VideoPace holds it under a lock).
     */
    class Pacer(private val perSecond: Int = PACE_PER_SECOND) {
        private var nextAt = 0L
        private var pausedUntil = 0L
        val gapMs: Long get() = 1000L / perSecond.coerceAtLeast(1)

        /** When the next piece may go, asked at [now]; that slot is then taken. */
        fun take(now: Long): Long {
            val at = maxOf(now, nextAt, pausedUntil)
            nextAt = at + gapMs
            return at
        }

        /** A relay said "rate-limited" at [now]. */
        fun limited(now: Long) { pausedUntil = maxOf(pausedUntil, now + BACKOFF_MS) }

        fun pausedAt(now: Long) = now < pausedUntil
    }

    /** About how long [pieces] relay pieces take at [perSecond] (for "about 2 minutes"). */
    fun paceMs(pieces: Int, perSecond: Int = PACE_PER_SECOND): Long = pieces.toLong() * 1000L / perSecond.coerceAtLeast(1)

    /** A relay's NIP-01 "OK" refusal that means slow down. */
    fun isRateLimit(message: String) = message.trim().startsWith("rate-limited", ignoreCase = true)

    // ------------------------------------------------------------------ the offer field

    /** A file offer's "vid" (a full-size video's length in ms): valid when absent, or a whole number 1 to [FULL_MAX_MS]. */
    fun vidOk(v: Any?): Boolean = v == null || (v is Number && v.toDouble() == v.toLong().toDouble() && v.toLong() in 1..FULL_MAX_MS.toLong())

    /** One member's line in the message's Info: what they get and how far it is. [sent] of [total] relay pieces handed on. */
    fun progressLine(name: String, how: String, sent: Int, total: Int, failed: Boolean): String = when {
        failed -> "$name: didn't go. Tap Retry on its card, or send it again"
        how == FULL -> "$name: full size, straight (see its card)"
        how == NONE -> "$name: not sent (no straight way to them)"
        total <= 0 || sent >= total -> "$name: smaller copy, sent"
        else -> "$name: smaller copy, ${sent * 100 / total}% sent"
    }
}
