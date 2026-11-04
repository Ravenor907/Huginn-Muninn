package io.github.ravenor907.muninn.net

/**
 * Recorded videos picked from the phone (cap v3, shared/docs/protocol.md section 29), for a group or one person. The
 * rules only, the same as Huginn's GroupVideo (groupvideo.h), so both apps decide alike and JVM tests check them.
 * 1.42 (owner, 2026-11-04): a video is never cut or trimmed.
 *  - Direct (the same Wi-Fi, or trusted peer to peer): full size, at most 1080p (1920 x 1080 either way up) and 30
 *    frames a second (converted if needed), with no length or size cap beyond FileLink's 4 GB ([FULL_MAX_BYTES]). It
 *    goes through FileLink as a file offer with "vid"; the receiver's own card decides whether it asks first.
 *  - Everyone else whose app has the new file paths ([NEW_PATH_CAP]): a 720p copy of up to [SMALL_MAX_MS] (5 minutes),
 *    also through FileLink, which uses Tor when there is no direct path. Never through the relays. Kids' devices get
 *    this path too ([SMALL]).
 *  - Older apps only: the old relay copy (at most 720p, [RELAY_MAX_MS] long and [RELAY_PIECES] pieces, paced), or a
 *    full copy of at most [OLD_FULL_MAX_MS] straight. A longer video doesn't reach an older app except as a file.
 *  - Bigger than 720p and not direct for someone: the sheet asks "Shrink to 720p to send?" first ([needsShrink]).
 *  - Over 5 minutes and not direct for someone: "Video too long" with Send as a file or Cancel ([tooLong]).
 */
object GroupVideo {
    const val CAP = "v3"

    /**
     * The cap of apps that take files and the 720p video copy through FileLink's new paths (Nearby, trusted direct, then
     * Tor): cap f4, the same as TorFiles.CAP and FileLink.TOR_CAP (GroupVideoTest checks). Members without it are treated
     * as older apps (relay copy).
     */
    const val NEW_PATH_CAP = "f4"

    // Full size (direct, never through the relays).
    const val FULL_LONG = 1920
    const val FULL_SHORT = 1080
    const val FULL_FPS = 30
    const val FULL_VIDEO_BITRATE = 4_500_000
    const val FULL_AUDIO_BITRATE = 128_000
    /** The only ceiling on a direct video: FileLink's transfer limit (FileLink.MAX_BYTES, 4 GB). */
    const val FULL_MAX_BYTES = 4L * 1024 * 1024 * 1024
    /** An older app (no [NEW_PATH_CAP]) takes a full copy of at most a minute: its offer check refuses a longer "vid". */
    const val OLD_FULL_MAX_MS = 60_000
    /** The old name (Chat.sendVideoFull's "vid" clamp): an older app's limit. New sends go through VideoSend.offer. */
    const val FULL_MAX_MS = OLD_FULL_MAX_MS
    /** What a new app takes as a "vid" (a video's length in ms): up to a day, far more than 4 GB holds. */
    const val VID_MAX_MS = 24 * 3_600_000

    // The 720p copy for those not direct (new apps, through FileLink: Tor when there's no direct path).
    const val SMALL_LONG = 1280
    const val SMALL_SHORT = 720
    /** Five minutes at most; a longer video goes only direct, or as a file. */
    const val SMALL_MAX_MS = 5 * 60_000
    /** About 1.6 Mbps in all: 5 minutes is about 60 MB, reasonable over Tor. */
    const val SMALL_VIDEO_BITRATE = 1_500_000

    // The relay copy (older apps only).
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
     * sound's bitrate ([audioBps], 0 when the file doesn't say). [plain]: H.264 (8-bit, not HDR) with AAC sound or none,
     * in an MP4, so every app can play it as it is (29.1); anything else is converted.
     */
    data class Info(val w: Int, val h: Int, val rotation: Int, val fps: Float, val ms: Int, val bytes: Long, val audioBps: Int = 0,
                    val plain: Boolean = true)

    /** Upright width and height (a phone video stored sideways with a turn of 90 or 270). */
    fun upright(i: Info): Pair<Int, Int> = if (i.rotation % 180 == 90) i.h to i.w else i.w to i.h

    /** Its picture fits [long] x [short] either way up. */
    fun withinSize(w: Int, h: Int, long: Int, short: Int) = maxOf(w, h) <= long && minOf(w, h) <= short

    /** Frames a second within [max] (0 or unknown counts as fine: the converter is told the limit anyway). */
    fun withinFps(fps: Float, max: Int) = fps <= max + 0.5f

    /** It can go full size as it is (no converting): plain H.264, 1080p30 at most, within 4 GB. Any length. */
    fun fitsFull(i: Info) = i.plain && i.w > 0 && i.h > 0 && withinSize(i.w, i.h, FULL_LONG, FULL_SHORT) && withinFps(i.fps, FULL_FPS) &&
        i.bytes in 1..FULL_MAX_BYTES && i.ms > 0

    /** It must be converted before it goes full size (not plain H.264, bigger than 1080p, faster than 30 fps, or over 4 GB). */
    fun needsConvert(i: Info) = !(i.plain && withinSize(i.w, i.h, FULL_LONG, FULL_SHORT) && withinFps(i.fps, FULL_FPS) && i.bytes in 1..FULL_MAX_BYTES)

    /** It can go as the 720p copy as it is: plain H.264, 720p30 at most, 5 minutes at most. */
    fun fitsSmall(i: Info) = i.plain && i.w > 0 && i.h > 0 && withinSize(i.w, i.h, SMALL_LONG, SMALL_SHORT) && withinFps(i.fps, FULL_FPS) &&
        i.bytes in 1..FULL_MAX_BYTES && i.ms in 1..(SMALL_MAX_MS + SLACK_MS)

    /** Bigger than 720p: the 720p copy shrinks it, so the sheet asks first. */
    fun biggerThan720(i: Info) = i.w > 0 && i.h > 0 && !withinSize(i.w, i.h, SMALL_LONG, SMALL_SHORT)

    /** It can go over the relays as it is (older apps): plain H.264, 720p at most, short enough, within [RELAY_PIECES] pieces. */
    fun fitsRelay(i: Info) = i.plain && i.w > 0 && i.h > 0 && withinSize(i.w, i.h, RELAY_LONG, RELAY_SHORT) && withinFps(i.fps, FULL_FPS) &&
        i.bytes in 1..RELAY_BYTES.toLong() && i.ms in 1..(RELAY_MAX_MS + SLACK_MS)

    // MediaFormat values (android.media), here so the rule is checked on the JVM.
    private const val AVC_HIGH10 = 0x10
    private const val AVC_HIGH422 = 0x20
    private const val AVC_HIGH444 = 0x40
    private const val TRANSFER_ST2084 = 6
    private const val TRANSFER_HLG = 7
    private const val STANDARD_BT2020 = 6

    /**
     * A video Muninn may send as it is (29.1: H.264 and AAC in MP4): [mp4] (the file starts with an "ftyp" box), video
     * [video] "video/avc" in 8 bits ([profile] not High 10, 4:2:2 or 4:4:4; -1 unknown) and not HDR ([transfer], [standard];
     * -1 unknown), and sound [audio] AAC or none (null).
     */
    fun plainCodecs(mp4: Boolean, video: String, profile: Int, transfer: Int, standard: Int, audio: String?): Boolean =
        mp4 && video.equals("video/avc", ignoreCase = true) && profile !in setOf(AVC_HIGH10, AVC_HIGH422, AVC_HIGH444) &&
            transfer != TRANSFER_ST2084 && transfer != TRANSFER_HLG && standard != STANDARD_BT2020 &&
            (audio == null || audio.equals("audio/mp4a-latm", ignoreCase = true))

    /** The first bytes of a file are an MP4's (or a QuickTime file's) "ftyp" box. */
    fun isMp4Head(head: ByteArray): Boolean =
        head.size >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()

    /**
     * The size a copy of an upright [w] x [h] picture is made at to fit [long] x [short] (either way up), or null when it
     * already fits. Both sides count: a 2560 x 1080 clip is 1920 x 810, not 2560 x 1080.
     */
    fun target(w: Int, h: Int, long: Int, short: Int): Pair<Int, Int>? =
        if (w <= 0 || h <= 0 || withinSize(w, h, long, short)) null else scaled(w, h, long, short)

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

    /**
     * The full copy's bitrate: [FULL_VIDEO_BITRATE], or less only when a very long video would pass 4 GB. (A minute is
     * about 35 MB, so a minute's copy still arrives by itself on an older app, under its 50 MB.)
     */
    fun fullBitrate(ms: Int): Int {
        val s = ms.coerceAtLeast(1000) / 1000.0
        val most = (FULL_MAX_BYTES * 8 * RELAY_AIM / s - FULL_AUDIO_BITRATE).toLong().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return minOf(FULL_VIDEO_BITRATE, most).coerceAtLeast(MIN_VIDEO_BITRATE)
    }

    /** A relay copy of [bytes] fits. */
    fun relayFits(bytes: Long) = bytes in 1..RELAY_BYTES.toLong()

    // ------------------------------------------------------------------ length

    /** Short enough for everyone, older apps' relay copy too. */
    const val SHORT = "short"
    /** Longer than [RELAY_MAX_MS], at most 5 minutes: the 720p copy (new apps) or full size direct. */
    const val LONG = "long"
    /** Over 5 minutes: full size direct only; otherwise "Video too long" (it can go as a file). */
    const val TOO_LONG = "too long"

    fun length(ms: Int): String = when {
        ms <= RELAY_MAX_MS + SLACK_MS -> SHORT
        ms <= SMALL_MAX_MS + SLACK_MS -> LONG
        else -> TOO_LONG
    }

    /** "1:05" for [ms]; "1:02:05" past an hour. */
    fun clock(ms: Int): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        val mm = (s / 60 % 60).toString().padStart(2, '0')
        val ss = (s % 60).toString().padStart(2, '0')
        return if (s >= 3600) "${s / 3600}:$mm:$ss" else "${s / 60}:$ss"
    }

    // ------------------------------------------------------------------ who gets what

    /**
     * One member (or the one person), as this phone sees them now. [sameWifi]: on this Wi-Fi now (proved). [trusted]:
     * Trusted direct on for each other (and allowed now: never under this phone's Ultra privacy). [files]: their
     * app takes straight files here (f1, and f2 in a group). [v3]: their app takes a video offer. [kid]: a kids' device
     * (never full size: the 720p copy, or the relay copy on an older app). [relayOnly]: their app asked for nothing
     * straight. [newPath]: their app has [NEW_PATH_CAP] (files and the 720p copy over Tor; no relay video).
     */
    data class Member(val k: String, val sameWifi: Boolean, val trusted: Boolean, val files: Boolean, val v3: Boolean,
                      val kid: Boolean = false, val relayOnly: Boolean = false, val newPath: Boolean = false)

    /** Full size, direct (FileLink with "vid"). */
    const val FULL = "full"
    /** The 720p copy through FileLink (Tor when there's no direct path). New apps only. */
    const val SMALL = "small"
    /** The old relay copy, paced. Older apps only. */
    const val RELAY = "relay"
    /** Gets nothing: an older app and a video over 20 seconds, or over 5 minutes with no direct path. */
    const val NONE = "none"

    /** May take a full copy at all. */
    fun fullOk(m: Member) = m.files && m.v3 && !m.kid && !m.relayOnly

    /** A direct way to them now: the same Wi-Fi, or trust while this phone isn't on a metered network. */
    fun direct(m: Member, unmetered: Boolean) = m.sameWifi || (m.trusted && unmetered)

    /**
     * What [m] gets of a video [ms] long. Full size when direct (an older app only up to [OLD_FULL_MAX_MS]); else the 720p
     * copy up to 5 minutes for a new app (kids' devices included); else, for an older app, the relay copy up to 20 s.
     */
    fun delivery(m: Member, unmetered: Boolean, ms: Int): String = when {
        fullOk(m) && direct(m, unmetered) && (m.newPath || ms <= OLD_FULL_MAX_MS + SLACK_MS) -> FULL
        m.newPath && m.v3 -> if (ms <= SMALL_MAX_MS + SLACK_MS) SMALL else NONE
        ms <= RELAY_MAX_MS + SLACK_MS -> RELAY
        else -> NONE
    }

    /** The plan for everyone: key to [FULL], [SMALL], [RELAY] or [NONE]. */
    fun plan(members: List<Member>, unmetered: Boolean, ms: Int): Map<String, String> =
        members.associate { it.k to delivery(it, unmetered, ms) }

    /** Over 5 minutes and someone has no direct way: "Video too long" (Send as a file, or Cancel). Never trimmed. */
    fun tooLong(ms: Int, plan: Map<String, String>) = ms > SMALL_MAX_MS + SLACK_MS && plan.values.any { it != FULL }

    /** Bigger than 720p and someone gets a 720p copy (or an older app's relay copy): ask "Shrink to 720p to send?" first. */
    fun needsShrink(i: Info, plan: Map<String, String>) = biggerThan720(i) && plan.values.any { it == SMALL || it == RELAY }

    /** What the send sheet says about [plan] ([nameOf] each key). */
    fun summary(plan: Map<String, String>, nameOf: (String) -> String): String {
        fun of(how: String) = plan.filterValues { it == how }.keys.map(nameOf)
        val full = of(FULL)
        val small = of(SMALL)
        val relay = of(RELAY)
        val none = of(NONE)
        return listOfNotNull(
            full.takeIf { it.isNotEmpty() }?.let { "Full size (1080p) to ${names(it)}, close by or direct." },
            small.takeIf { it.isNotEmpty() }?.let { "A 720p copy to ${names(it)} over Tor (slower)." },
            relay.takeIf { it.isNotEmpty() }?.let { "A smaller copy (720p, up to 20 seconds) to ${names(it)}, whose app is older." },
            none.takeIf { it.isNotEmpty() }?.let { "Not to ${names(it)}: their app is older. Ask them to update." },
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

    // The words of the two questions (the same in Huginn where it applies).
    const val SHRINK_TITLE = "Shrink to 720p to send?"
    const val SHRINK_TEXT = "Anyone not close by gets a 720p copy. Nothing is cut."
    const val TOO_LONG_TITLE = "Video too long"
    const val TOO_LONG_TEXT = "Videos over the internet can be up to 5 minutes."
    /** The Saved media note under videos. */
    const val NOTE = "Full size close by. Others get up to 5 minutes."

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

    /**
     * A file offer's "vid" (a video's length in ms): valid when absent, or a whole number 1 to [VID_MAX_MS]. (Apps before
     * 1.42 took only up to a minute, so a longer "vid" goes only to apps with [NEW_PATH_CAP].)
     */
    fun vidOk(v: Any?): Boolean = v == null || (v is Number && v.toDouble() == v.toLong().toDouble() && v.toLong() in 1..VID_MAX_MS.toLong())

    /** The "vid" for [m]: the length, kept within what their app takes. */
    fun vidFor(ms: Int, newPath: Boolean): Int = ms.coerceIn(1, if (newPath) VID_MAX_MS else OLD_FULL_MAX_MS)

    /** One member's line in the message's Info: what they get and how far it is. [sent] of [total] relay pieces handed on. */
    fun progressLine(name: String, how: String, sent: Int, total: Int, failed: Boolean): String = when {
        failed -> "$name: didn't go. Tap Retry on its card, or send it again"
        how == FULL -> "$name: full size (see its card)"
        how == SMALL -> "$name: 720p copy (see its card)"
        how == NONE -> "$name: not sent (their app is older)"
        total <= 0 || sent >= total -> "$name: smaller copy, sent"
        else -> "$name: smaller copy, ${sent * 100 / total}% sent"
    }
}


/**
 * Location and camera details out of a picked video before it goes (as Photos.scrub does for photos), changed in place so
 * the picture and sound stay exactly as they are and every sample offset stays right:
 *  - "udta", "meta" and "uuid" boxes (Android's ©xyz location, an iPhone's location keys, XMP, make and model), at the
 *    top, in "moov" and in every track, become "free" with their contents zeroed. So do "free" and "skip" contents.
 *  - Tracks other than picture and sound (timed metadata such as GPS tracks, timecode) become "free", and the samples
 *    they point at in "mdat" are zeroed too.
 *  - The creation and change times in mvhd, tkhd and mdhd are zeroed.
 *  - Anything else at the top is zeroed; bytes after the last whole box (a maker's trailer) are cut off.
 * A file it can't do that to safely (fragmented, malformed, a huge "moov") is refused (-1): it is then converted, or not
 * sent as a video. Bounds are checked everywhere: the file is the person's own, but it may come from anywhere.
 */
object Mp4Scrub {
    /** Where the bytes are: a file, or bytes in memory (tests, a relay copy). */
    interface Io {
        val size: Long
        fun read(at: Long, n: Int): ByteArray
        fun write(at: Long, b: ByteArray)
    }

    class Bytes(val b: ByteArray) : Io {
        override val size: Long get() = b.size.toLong()
        override fun read(at: Long, n: Int): ByteArray = b.copyOfRange(at.toInt(), (at + n).toInt())
        override fun write(at: Long, b: ByteArray) { b.copyInto(this.b, at.toInt()) }
    }

    /** A "moov" bigger than this is refused (a minute of video has well under a megabyte of it). */
    const val MAX_MOOV = 16 * 1024 * 1024
    /** Samples zeroed in one track at most (a minute of timed metadata is a few thousand). */
    private const val MAX_SAMPLES = 2_000_000

    private val KEEP_TOP = setOf("ftyp", "moov", "mdat")
    private val WIPE = setOf("udta", "meta", "uuid", "free", "skip", "wide")
    private val FREE = "free".toByteArray(Charsets.US_ASCII)

    private class Box(val at: Int, val head: Int, val end: Int, val type: String) { val body get() = at + head }

    private fun u32(d: ByteArray, at: Int): Long =
        ((d[at].toLong() and 0xff) shl 24) or ((d[at + 1].toLong() and 0xff) shl 16) or ((d[at + 2].toLong() and 0xff) shl 8) or (d[at + 3].toLong() and 0xff)

    private fun u64(d: ByteArray, at: Int): Long = (u32(d, at) shl 32) or u32(d, at + 4)

    /** The boxes directly inside [start, end) of [d], or null when one is malformed. */
    private fun boxes(d: ByteArray, start: Int, end: Int): List<Box>? {
        val out = ArrayList<Box>()
        var at = start
        while (at < end) {
            if (at + 8 > end) return null
            var len = u32(d, at)
            var head = 8
            if (len == 1L) {
                if (at + 16 > end) return null
                len = u64(d, at + 8)
                head = 16
            } else if (len == 0L) len = (end - at).toLong()
            if (len < head || len > end - at) return null
            out.add(Box(at, head, at + len.toInt(), String(d, at + 4, 4, Charsets.ISO_8859_1)))
            at += len.toInt()
        }
        return out
    }

    private fun wipe(d: ByteArray, b: Box) {
        FREE.copyInto(d, b.at + 4)
        d.fill(0, b.body, b.end)
    }

    /** mvhd, tkhd and mdhd: their creation and change times zeroed (version 0: 4 bytes each, version 1: 8). */
    private fun zeroTimes(d: ByteArray, b: Box) {
        if (b.body >= b.end) return
        val n = if (d[b.body].toInt() == 1) 16 else 8
        if (b.body + 4 + n <= b.end) d.fill(0, b.body + 4, b.body + 4 + n)
    }

    /** One track's samples in the file, as (offset, length) runs, from its stbl; null when its tables don't add up. */
    private fun samples(d: ByteArray, stbl: Box, fileSize: Long): List<Pair<Long, Long>>? {
        val kids = boxes(d, stbl.body, stbl.end) ?: return null
        val stsz = kids.firstOrNull { it.type == "stsz" } ?: return if (kids.any { it.type == "stz2" }) null else emptyList()
        val stsc = kids.firstOrNull { it.type == "stsc" } ?: return null
        val co = kids.firstOrNull { it.type == "stco" || it.type == "co64" } ?: return null
        if (stsz.body + 12 > stsz.end || stsc.body + 8 > stsc.end || co.body + 8 > co.end) return null
        val fixed = u32(d, stsz.body + 4)
        val count = u32(d, stsz.body + 8)
        if (count > MAX_SAMPLES || (fixed == 0L && stsz.body + 12 + count * 4 > stsz.end)) return null
        val entries = u32(d, stsc.body + 4)
        if (stsc.body + 8 + entries * 12 > stsc.end) return null
        val wide = co.type == "co64"
        val chunks = u32(d, co.body + 4)
        if (co.body + 8 + chunks * (if (wide) 8 else 4) > co.end) return null
        val out = ArrayList<Pair<Long, Long>>()
        if (entries == 0L) return if (count == 0L) out else null
        var sample = 0L
        var e = 0
        for (c in 0 until chunks.toInt()) {
            if (sample >= count) break
            while (e + 1 < entries && u32(d, stsc.body + 8 + (e + 1) * 12) <= c + 1L) e++
            val per = minOf(u32(d, stsc.body + 8 + e * 12 + 4), count - sample)
            val off = if (wide) u64(d, co.body + 8 + c * 8) else u32(d, co.body + 8 + c * 4)
            var len = 0L
            if (fixed != 0L) len = per * fixed
            else for (s in 0 until per) len += u32(d, stsz.body + 12 + ((sample + s) * 4).toInt())
            sample += per
            if (off < 0 || len < 0 || off + len > fileSize) return null
            if (len > 0) out.add(off to len)
        }
        return out
    }

    /**
     * One track: kept and cleaned (picture or sound), or dropped with its samples added to [zero]. True for a picture
     * track, false for any other, null when it's malformed.
     */
    private fun track(d: ByteArray, trak: Box, fileSize: Long, zero: MutableList<Pair<Long, Long>>): Boolean? {
        val kids = boxes(d, trak.body, trak.end) ?: return null
        val mdia = kids.firstOrNull { it.type == "mdia" } ?: return null
        val inMdia = boxes(d, mdia.body, mdia.end) ?: return null
        val hdlr = inMdia.firstOrNull { it.type == "hdlr" } ?: return null
        if (hdlr.body + 12 > hdlr.end) return null
        val handler = String(d, hdlr.body + 8, 4, Charsets.ISO_8859_1)
        if (handler != "vide" && handler != "soun") {
            val minf = inMdia.firstOrNull { it.type == "minf" }
            val stbl = minf?.let { m -> boxes(d, m.body, m.end)?.firstOrNull { it.type == "stbl" } }
            if (stbl != null) zero.addAll(samples(d, stbl, fileSize) ?: return null)
            wipe(d, trak)
            return false
        }
        for (k in kids) when (k.type) {
            "tkhd" -> zeroTimes(d, k)
            in WIPE -> wipe(d, k)
        }
        for (k in inMdia) when (k.type) {
            "mdhd" -> zeroTimes(d, k)
            in WIPE -> wipe(d, k)
        }
        return handler == "vide"
    }

    /**
     * Cleans [io] in place. Returns the length the file should be cut to (bytes after it aren't a box), or -1 when it
     * can't be done safely (then nothing should go as it is).
     */
    fun scrub(io: Io): Long {
        val size = io.size
        var at = 0L
        var moovAt = -1L
        var moovLen = 0
        var sawMdat = false
        val wipeTop = ArrayList<Pair<Long, Long>>()
        while (at + 8 <= size) {
            val h = io.read(at, minOf(16L, size - at).toInt())
            var len = u32(h, 0)
            var head = 8
            if (len == 1L) {
                if (h.size < 16) break
                len = u64(h, 8)
                head = 16
            } else if (len == 0L) len = size - at
            if (len < head || len > size - at) break
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            when {
                type == "moov" -> {
                    if (moovAt >= 0 || len > MAX_MOOV) return -1
                    moovAt = at
                    moovLen = len.toInt()
                }
                type == "mdat" -> sawMdat = true
                type == "moof" || type == "mfra" || type == "sidx" || type == "styp" -> return -1   // fragmented: not handled
                type in KEEP_TOP -> {}
                else -> {
                    io.write(at + 4, FREE)
                    wipeTop.add(at + head to len - head)
                }
            }
            at += len
        }
        val keep = at
        if (moovAt < 0 || !sawMdat) return -1
        val m = io.read(moovAt, moovLen)
        val top = boxes(m, 0, moovLen)?.singleOrNull() ?: return -1
        val kids = boxes(m, top.body, top.end) ?: return -1
        if (kids.any { it.type == "mvex" }) return -1
        val zero = ArrayList<Pair<Long, Long>>()
        var video = false
        for (k in kids) when (k.type) {
            "mvhd" -> zeroTimes(m, k)
            "trak" -> video = (track(m, k, size, zero) ?: return -1) || video
            in WIPE -> wipe(m, k)
        }
        if (!video) return -1
        // A dropped track's samples must lie in the file, outside "moov" (written back below), before the cut.
        for ((off, len) in zero) if (off + len > keep || (off < moovAt + moovLen && off + len > moovAt)) return -1
        io.write(moovAt, m)
        for ((off, len) in wipeTop + zero) zeros(io, off, len)
        return keep
    }

    private fun zeros(io: Io, at: Long, len: Long) {
        val chunk = ByteArray(64 * 1024)
        var done = 0L
        while (done < len) {
            val n = minOf(chunk.size.toLong(), len - done).toInt()
            io.write(at + done, if (n == chunk.size) chunk else ByteArray(n))
            done += n
        }
    }

    /** [b] cleaned (a copy, cut to its last whole box), or null when it can't be done safely. */
    fun scrubBytes(b: ByteArray): ByteArray? = try {
        val c = b.copyOf()
        val keep = scrub(Bytes(c))
        if (keep <= 0) null else if (keep < c.size) c.copyOf(keep.toInt()) else c
    } catch (_: Exception) { null }

    /** [f] cleaned in place (and cut to its last whole box). False when it can't be done safely: don't send it as it is. */
    fun scrubFile(f: java.io.File): Boolean = try {
        java.io.RandomAccessFile(f, "rw").use { r ->
            val io = object : Io {
                override val size: Long = r.length()
                override fun read(at: Long, n: Int): ByteArray { val b = ByteArray(n); r.seek(at); r.readFully(b); return b }
                override fun write(at: Long, b: ByteArray) { r.seek(at); r.write(b) }
            }
            val keep = scrub(io)
            if (keep > 0 && keep < io.size) r.setLength(keep)
            keep > 0
        }
    } catch (_: Exception) { false } catch (_: OutOfMemoryError) { false }
}
