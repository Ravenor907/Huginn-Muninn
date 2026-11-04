package io.github.ravenor907.muninn.tv

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A quiet time on a Kids' Device (cap k11, shared/docs/protocol.md 7.18): [n] "school" or "bed", the days [d] it starts
 * on (bit 0 Monday to bit 6 Sunday), start [s] and end [e] in minutes after midnight. An end before the start runs
 * past midnight into the next day.
 */
data class QuietTime(val n: String, val d: Int, val s: Int, val e: Int)

/**
 * The parent PIN as a Kids' Device keeps it: [salt] (16 bytes, hex) and [h], PBKDF2-HMAC-SHA256 of the PIN (hex) with [r]
 * rounds. A PIN set before "r" existed has 20000 rounds; every PIN set now has [KidRules.PIN_ROUNDS].
 */
data class KidPin(val salt: String, val h: String, val r: Int = KidRules.OLD_ROUNDS)

/** A trusted time [wall] (ms since 1970: a parent's signed config, or a relay's clock) and the device's elapsedRealtime [at] when it came. */
data class TimeAnchor(val wall: Long, val at: Long)

/** Wrong PIN tries in a row on the device and when the last one was ([at], elapsedRealtime). */
data class PinTries(val fails: Int = 0, val at: Long = 0)

/** What typing the PIN did: [ok]; [checked] false when it wasn't looked at (a wait, or too few digits); [wait] ms before the next try. */
data class PinTry(val ok: Boolean, val checked: Boolean, val wait: Long)

/**
 * The Kids' Device rules of cap k11 (7.18): the chatting level, quiet times, the parent PIN, unlocking from a parent's
 * phone, and leaving kids' mode. Kept free of Android so the unit tests run them.
 */
object KidRules {
    const val CAP = "k11"

    /** The chatting levels: quick messages only, or quick messages, typing, photos and voice messages. */
    const val QUICK_ONLY = 1
    const val EVERYTHING = 2
    const val QUICK_ONLY_NAME = "Quick messages only"
    const val EVERYTHING_NAME = "Quick messages, typing, photos and voice messages"

    const val SCHOOL = "school"
    const val BED = "bed"
    val QUIET_NAMES = listOf(SCHOOL, BED)
    const val ALL_DAYS = 127
    const val WEEKDAYS = 31
    const val WEEKEND = 96
    const val DAY_MINUTES = 1440

    const val PIN_MIN = 4
    const val PIN_MAX = 8
    /** PBKDF2 rounds for every PIN set now ("r" in the config), and for one set before "r" existed. */
    const val PIN_ROUNDS = 100_000
    const val OLD_ROUNDS = 20_000
    const val MAX_ROUNDS = 1_000_000
    /** The PIN length the parent's page suggests. */
    const val PIN_GOOD = 6
    /** Wrong PINs before the device makes the next try wait. */
    const val FREE_TRIES = 5
    /** Wrong tries are forgotten an hour after the last one. */
    const val FAIL_RESET_MS = 60 * 60_000L
    /** Someone at the device can ask a parent's phone once a minute. */
    const val ASK_GAP_MS = 60_000L
    /** A parent's phone tells its person when it hasn't heard from a device for this long. */
    const val SILENT_MS = 36 * 3_600_000L
    /** A trusted time from before this (2024-01-01) is a mistake, never an anchor. */
    const val MIN_WALL = 1_704_067_200_000L

    /** How long a parent PIN or a parent's phone opens the device's parent settings, and how long an ask waits. */
    const val UNLOCK_MS = 10 * 60_000L
    const val ASK_MS = 10 * 60_000L

    /** What kind of device it is, in an "applied" (k11). */
    const val TV = "tv"
    const val TABLET = "tablet"
    const val PHONE = "phone"
    val DEVICES = listOf(TV, TABLET, PHONE)

    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX64 = Regex("[0-9a-f]{64}")
    private val random = SecureRandom()

    // ---- the chatting level

    fun levelName(level: Int): String = if (level <= QUICK_ONLY) QUICK_ONLY_NAME else EVERYTHING_NAME

    /**
     * What the kids may send at [level]: "quick" (a quick message) always; "text" (typing), "say" (speech to text),
     * "photo" and "voice" only at [EVERYTHING]. Calls follow the call settings, not this.
     */
    fun canSend(level: Int, what: String): Boolean = what == "quick" || level >= EVERYTHING

    // ---- quiet times

    /** Reads a config's "quiet". Throws IllegalArgumentException("quiet") if it isn't well-formed. */
    fun quietIn(v: Any?): List<QuietTime> {
        val a = v as? JSONArray ?: throw IllegalArgumentException("quiet")
        require(a.length() <= QUIET_NAMES.size) { "quiet" }
        val out = (0 until a.length()).map { i ->
            val o = a.get(i) as? JSONObject ?: throw IllegalArgumentException("quiet")
            require(Canon.keys(o) == setOf("n", "d", "s", "e")) { "quiet" }
            val n = o.get("n") as? String
            require(n != null && n in QUIET_NAMES) { "quiet" }
            val d = int(o.get("d"), 1, ALL_DAYS)
            val s = int(o.get("s"), 0, DAY_MINUTES - 1)
            val e = int(o.get("e"), 0, DAY_MINUTES - 1)
            require(s != e) { "quiet" }
            QuietTime(n!!, d, s, e)
        }
        require(out.map { it.n }.distinct().size == out.size) { "quiet" }
        return out
    }

    private fun int(v: Any?, min: Int, max: Int): Int {
        require(Canon.isInt(v)) { "quiet" }
        val n = (v as Number).toLong()
        require(n in min.toLong()..max.toLong()) { "quiet" }
        return n.toInt()
    }

    /** A config's "quiet", in the order of [QUIET_NAMES]. */
    fun quietJson(list: List<QuietTime>): JSONArray = JSONArray().apply {
        for (q in list.distinctBy { it.n }.sortedBy { QUIET_NAMES.indexOf(it.n) })
            put(JSONObject().put("n", q.n).put("d", q.d and ALL_DAYS).put("s", q.s.coerceIn(0, DAY_MINUTES - 1)).put("e", q.e.coerceIn(0, DAY_MINUTES - 1)))
    }

    /**
     * The quiet time on now, or null: [day] 0 Monday to 6 Sunday, [minute] after midnight. A time that runs past
     * midnight is on from its start on the days it starts, and until its end on the day after.
     */
    fun quietNow(list: List<QuietTime>, day: Int, minute: Int): QuietTime? = list.firstOrNull { q ->
        val today = q.d and (1 shl day) != 0
        val yesterday = q.d and (1 shl ((day + 6) % 7)) != 0
        if (q.s < q.e) today && minute >= q.s && minute < q.e
        else (today && minute >= q.s) || (yesterday && minute < q.e)
    }

    /** During a quiet time only the parents get through; at any other time everyone on the list does. */
    fun passes(fromKey: String, parents: Collection<String>, quiet: QuietTime?): Boolean = quiet == null || fromKey in parents

    /** "School hours" or "Bedtime". */
    fun quietName(n: String): String = if (n == SCHOOL) "School hours" else "Bedtime"

    /** "08:30", or with [h24] false "8:30 AM" (the clock the parent's phone or the device uses). */
    fun clock(minute: Int, h24: Boolean = true): String {
        val h = minute / 60 % 24
        val m = minute % 60
        return if (h24) "%02d:%02d".format(h, m) else "%d:%02d %s".format((h + 11) % 12 + 1, m, if (h < 12) "AM" else "PM")
    }

    // ---- trusted time: quiet times never follow the device's own clock or time zone alone

    private val TZ = Regex("[A-Za-z][A-Za-z0-9_+\\-/]{0,63}")

    /** Reads a config's "tz", an IANA time zone id ("America/Los_Angeles"). Throws IllegalArgumentException("tz") if it isn't one. */
    fun tzIn(v: Any?): String {
        val s = v as? String ?: throw IllegalArgumentException("tz")
        require(TZ.matches(s) && !s.contains("//") && !s.endsWith("/")) { "tz" }
        return s
    }

    /** The zone [tz] names, or null when it's empty or this device doesn't know it. */
    fun zoneOf(tz: String): java.util.TimeZone? =
        if (tz.isEmpty() || !TZ.matches(tz)) null else java.util.TimeZone.getTimeZone(tz).takeIf { it.id == tz }

    /** Round 14 (7.18): the zone a parent's config carries: the one kept for the device, or this phone's while none is. */
    fun zoneToSend(kept: String, mine: String): String = kept.ifEmpty { mine }

    /**
     * Round 14 (7.18): the zone a parent keeps for a device. A device set up before 1.35 has none kept: the first zone
     * that goes to it or that it runs is kept from then on (unless it isn't a zone this phone knows).
     */
    fun zoneToKeep(kept: String, came: String?): String =
        if (kept.isNotEmpty() || came.isNullOrEmpty() || zoneOf(came) == null) kept else came

    /** The time now by [a] (null: no trusted time yet), at the device's elapsedRealtime [elapsed]. */
    fun trustedNow(a: TimeAnchor?, elapsed: Long): Long? = a?.let { it.wall + (elapsed - it.at) }

    /**
     * The anchor after a trusted time [wall] came at elapsedRealtime [elapsed]: the later reading wins, so an older config
     * resent later never moves the time back. Nothing before [MIN_WALL] is taken.
     */
    fun anchor(cur: TimeAnchor?, wall: Long, elapsed: Long): TimeAnchor? {
        if (wall < MIN_WALL) return cur
        val now = trustedNow(cur, elapsed)
        return if (now == null || wall > now) TimeAnchor(wall, elapsed) else cur
    }

    /** Readings that agree within this are one time. */
    const val AGREE_MS = 10 * 60_000L
    /** A reading no other source backs moves the trusted time forward at most this far, and never back. */
    const val SOLO_STEP_MS = 10 * 60_000L
    /** A reading is used for this long after it came (by uptime). */
    const val READING_MS = 12 * 3_600_000L
    /** The sources kept (one reading each: every relay, every parent). */
    const val MAX_SOURCES = 12

    /**
     * [readings] with [source]'s newest reading [r]: one per source, the most recent [MAX_SOURCES]. Round 18: an older
     * reading from the same source (a config signed earlier than the one already heard, arriving late) never replaces
     * a newer one, as long as that newer one is still used.
     */
    fun addReading(readings: Map<String, TimeAnchor>, source: String, r: TimeAnchor): Map<String, TimeAnchor> {
        if (r.wall < MIN_WALL) return readings
        readings[source]?.let { had -> if (r.wall < had.wall && r.at - had.at in 0L..READING_MS) return readings }
        return (readings - source + (source to r)).entries.sortedByDescending { it.value.at }.take(MAX_SOURCES).associate { it.key to it.value }
    }

    /**
     * Round 17 (7.18): the trusted time from every source's newest reading ([readings]: a relay's Date, a parent's signed
     * config time), each carried to [elapsed] by the device's uptime. The largest group of readings that agree within
     * [AGREE_MS] wins (a tie: the group nearest the current trusted time), and its median is the time. Two or more sources
     * that agree move it either way, by any amount. One source alone moves it forward at most [SOLO_STEP_MS] and never back,
     * so one relay's clock or one parent's fast phone can't shift quiet times. Between readings the uptime counts.
     */
    fun clockFrom(cur: TimeAnchor?, readings: Map<String, TimeAnchor>, elapsed: Long, deviceWall: Long? = null): TimeAnchor? {
        val live = readings.filter { (_, r) -> r.wall >= MIN_WALL && elapsed - r.at in 0L..READING_MS }.mapValues { (_, r) -> r.wall + (elapsed - r.at) }
        val est = live.values.sorted()
        if (est.isEmpty()) return cur
        // 1.42: right after the device started (no trusted time yet) one reading alone never sets the time.
        if (cur == null) return firstClock(live, deviceWall)?.let { TimeAnchor(it, elapsed) }
        val now = trustedNow(cur, elapsed)
        var bestI = 0; var bestJ = 0
        var j = 0
        for (i in est.indices) {
            if (j < i) j = i
            while (j + 1 < est.size && est[j + 1] - est[i] <= AGREE_MS) j++
            val size = j - i + 1
            val bestSize = bestJ - bestI + 1
            val better = size > bestSize || (size == bestSize && now != null &&
                kotlin.math.abs(est[(i + j) / 2] - now) < kotlin.math.abs(est[(bestI + bestJ) / 2] - now))
            if (better) { bestI = i; bestJ = j }
        }
        val target = est[(bestI + bestJ) / 2]
        val agree = bestJ - bestI + 1
        if (now == null) return TimeAnchor(target, elapsed)   // never: no trusted time is handled above
        val d = target - now
        if (kotlin.math.abs(d) < 1_000L) return cur
        if (agree >= 2) return TimeAnchor(target, elapsed)
        if (d < 0 || d > SOLO_STEP_MS) return cur
        return TimeAnchor(target, elapsed)
    }

    /**
     * 1.42: the first trusted time after the device started, from [live] (each source's reading carried to now), or null
     * to wait (the device's own clock stands in meanwhile, as before any reading). In order:
     *  - two or more sources that agree within [AGREE_MS]: their median;
     *  - a parent's signed config ("parent:" sources; the newest) that no other source disagrees with;
     *  - a source the device's own clock ([deviceWall]) agrees with: two that agree, though the clock alone never counts
     *    (one relay is then enough on a network that reaches no other, but only when the device's clock is right).
     * One relay alone, or a parent's config another source disagrees with, waits for more.
     */
    fun firstClock(live: Map<String, Long>, deviceWall: Long?): Long? {
        val est = live.values.sorted()
        if (est.isEmpty()) return null
        var bestI = 0; var bestJ = 0
        var j = 0
        for (i in est.indices) {
            if (j < i) j = i
            while (j + 1 < est.size && est[j + 1] - est[i] <= AGREE_MS) j++
            if (j - i > bestJ - bestI) { bestI = i; bestJ = j }
        }
        if (bestJ > bestI) return est[(bestI + bestJ) / 2]
        val parent = live.filterKeys { it.startsWith("parent:") }.values.maxOrNull()
        if (parent != null && est.all { kotlin.math.abs(it - parent) <= AGREE_MS }) return parent
        if (deviceWall == null) return null
        return est.filter { kotlin.math.abs(it - deviceWall) <= AGREE_MS }.minByOrNull { kotlin.math.abs(it - deviceWall) }
    }

    /** The quiet time on at [epochMs] in zone [tz] (empty or unknown: [fallback], the device's own), or null. */
    fun quietAt(list: List<QuietTime>, epochMs: Long, tz: String, fallback: java.util.TimeZone): QuietTime? {
        if (list.isEmpty()) return null
        val c = java.util.Calendar.getInstance(zoneOf(tz) ?: fallback).apply { timeInMillis = epochMs }
        // Calendar counts from Sunday (1); the quiet days count from Monday (0).
        return quietNow(list, (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7, c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE))
    }

    /**
     * The quiet time on now on a device: by the trusted time ([anchor] and [elapsed]) in the parent's zone [tz]. Only when
     * no trusted time has come since the device started ([anchor] null) does its own clock [deviceNow] stand in.
     */
    fun quietOnDevice(list: List<QuietTime>, tz: String, anchor: TimeAnchor?, elapsed: Long, deviceNow: Long, deviceZone: java.util.TimeZone): QuietTime? =
        quietAt(list, trustedNow(anchor, elapsed) ?: deviceNow, tz, deviceZone)

    /** "Every day", "Weekdays", "Weekends", or the days: "Mon, Wed, Fri". */
    fun daysText(d: Int): String = when (d and ALL_DAYS) {
        ALL_DAYS -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKEND -> "Weekends"
        0 -> "No days"
        else -> DAY_NAMES.filterIndexed { i, _ -> d and (1 shl i) != 0 }.joinToString(", ")
    }

    val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** The quiet times a new one starts from: school 08:00 to 15:00 on weekdays, bed 20:00 to 07:00 every day. */
    fun quietDefault(n: String): QuietTime = if (n == SCHOOL) QuietTime(SCHOOL, WEEKDAYS, 8 * 60, 15 * 60) else QuietTime(BED, ALL_DAYS, 20 * 60, 7 * 60)

    // ---- the parent PIN

    /** 4 to 8 digits. */
    fun pinValid(pin: String): Boolean = pin.length in PIN_MIN..PIN_MAX && pin.all { it in '0'..'9' }

    /** A new PIN as the parent's phone puts it in a config: a fresh salt and the PIN's hash, [PIN_ROUNDS] rounds. */
    fun newPin(pin: String): KidPin {
        require(pinValid(pin)) { "pin" }
        val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return KidPin(salt, pinHash(pin, salt, PIN_ROUNDS), PIN_ROUNDS)
    }

    /** PBKDF2-HMAC-SHA256 of [pin] with [saltHex], [rounds] rounds, 32 bytes, lowercase hex. */
    fun pinHash(pin: String, saltHex: String, rounds: Int = OLD_ROUNDS): String =
        pbkdf2(pin.toByteArray(Charsets.UTF_8), hex(saltHex), rounds).joinToString("") { "%02x".format(it) }

    /** True when [pin] is the one [p] was made from. */
    fun pinOk(pin: String, p: KidPin?): Boolean {
        if (p == null || !pinValid(pin) || p.r !in OLD_ROUNDS..MAX_ROUNDS) return false
        return MessageDigest.isEqual(pinHash(pin, p.salt, p.r).toByteArray(), p.h.toByteArray())
    }

    /** Round 17: the longest wait between tries, after many wrong PINs. */
    const val MAX_WAIT_MS = 4 * 3_600_000L
    /** Round 17: a new PIN a parent sets has at least this many digits (an older, shorter one keeps working). */
    const val PIN_NEW_MIN = 6
    /** Round 17: after this many wrong PINs in a row the parents are told (in the device's status). */
    const val PIN_WARN = 10

    /** Whether a parent may set [pin] as a new PIN: [PIN_NEW_MIN] to [PIN_MAX] digits. */
    fun newPinOk(pin: String): Boolean = pinValid(pin) && pin.length >= PIN_NEW_MIN

    /** How long the next try must wait after [fails] wrong PINs in a row: none for the first 5, then 30 s, doubling, at most 4 hours. */
    fun waitMs(fails: Int): Long {
        if (fails < FREE_TRIES) return 0
        return minOf(30_000L shl minOf(fails - FREE_TRIES, 9), MAX_WAIT_MS)
    }

    /**
     * Wrong tries that still count at [now] (elapsedRealtime). A few mistakes ([FREE_TRIES] or fewer) are forgotten an hour
     * after the last one; once the waits have begun, only the right PIN starts the count over (round 17: guessing never
     * gets faster by waiting).
     */
    fun failsNow(t: PinTries, now: Long): Int = when {
        t.fails <= 0 -> 0
        t.fails < FREE_TRIES && now - t.at >= FAIL_RESET_MS -> 0
        else -> t.fails
    }

    /**
     * Round 17: the try counted before the PIN is checked (written at once), so a try cut short by stopping the app still
     * counts; the right PIN then starts the count over.
     */
    fun counted(t: PinTries, now: Long): PinTries = PinTries(failsNow(t, now) + 1, now)

    /** How long before the next try at [now] (ms, 0: now). A time before the last try (a restart) waits the whole wait. */
    fun waitLeft(t: PinTries, now: Long): Long {
        val f = failsNow(t, now)
        if (f == 0) return 0
        return (waitMs(f) - (now - t.at).coerceAtLeast(0)).coerceAtLeast(0)
    }

    /**
     * The PIN [pin] typed at [now] (elapsedRealtime) against [p], after the tries [t]. During a wait it isn't looked at;
     * fewer than [PIN_MIN] digits never count as a try. Returns what happened and the tries from then on.
     */
    fun tryPin(pin: String, p: KidPin?, t: PinTries, now: Long): Pair<PinTry, PinTries> {
        val wait = waitLeft(t, now)
        if (wait > 0) return PinTry(false, false, wait) to t
        if (pin.length < PIN_MIN) return PinTry(false, false, 0) to t
        if (pinOk(pin, p)) return PinTry(true, true, 0) to PinTries()
        val fails = failsNow(t, now) + 1
        return PinTry(false, true, waitMs(fails)) to PinTries(fails, now)
    }

    /** "Wait 30 seconds, then try again." or "Wait 2 minutes, then try again." */
    fun waitText(ms: Long): String {
        val s = ((ms + 999) / 1000).coerceAtLeast(1)
        if (s < 60) return "Wait $s ${if (s == 1L) "second" else "seconds"}, then try again."
        val m = (s + 59) / 60
        return "Wait $m ${if (m == 1L) "minute" else "minutes"}, then try again."
    }

    /** What the device's PIN screen says after [r]. */
    fun pinText(r: PinTry): String = when {
        r.ok -> ""
        !r.checked && r.wait > 0 -> waitText(r.wait)
        !r.checked -> "Type at least $PIN_MIN digits"
        r.wait > 0 -> "Wrong PIN. ${waitText(r.wait)}"
        else -> "Wrong PIN"
    }

    /** Someone at the device may ask a parent's phone again (elapsedRealtime; [last] 0: never asked). */
    fun askAgainOk(last: Long, now: Long): Boolean = last <= 0L || now - last !in 0L until ASK_GAP_MS

    /** Round 17: the gaps after asks nobody answered: 1, 2, 5 and 15 minutes, then an hour. */
    private val ASK_GAPS = longArrayOf(60_000L, 2 * 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)

    /** How long after an ask the next may go, when [unanswered] asks in a row got no answer (the first: a minute). */
    fun askGap(unanswered: Int): Long = ASK_GAPS[(unanswered - 1).coerceIn(0, ASK_GAPS.size - 1)]

    /** "You can ask again in 5 minutes." (or "in a minute"), for a wait of [ms]. */
    fun askAgainText(ms: Long): String {
        val m = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return if (m == 1L) "You can ask again in a minute." else "You can ask again in $m minutes."
    }

    /**
     * Round 17: what a key on a remote or keyboard does on the PIN keypad (Android key codes): "0" to "9" for the number
     * keys (and the number pad's), "Delete" for its delete key, else null. Enter isn't taken: on many remotes it is the OK
     * of whichever key has focus.
     */
    fun padKey(code: Int): String? = when (code) {
        in 7..16 -> (code - 7).toString()
        in 144..153 -> (code - 144).toString()
        67 -> "Delete"
        else -> null
    }

    /** Someone at the device may ask again at [now] after [unanswered] unanswered asks, the last at [last] (0: never). */
    fun askAgainOk(last: Long, now: Long, unanswered: Int): Boolean = last <= 0L || now - last !in 0L until askGap(unanswered)

    /** Reads a config's "pin". Throws IllegalArgumentException("pin") if it isn't well-formed. */
    fun pinIn(v: Any?): KidPin {
        val o = v as? JSONObject ?: throw IllegalArgumentException("pin")
        val ks = Canon.keys(o)
        require(ks == setOf("salt", "h") || ks == setOf("salt", "h", "r")) { "pin" }
        val salt = o.get("salt") as? String
        val h = o.get("h") as? String
        require(salt != null && HEX32.matches(salt) && h != null && HEX64.matches(h)) { "pin" }
        // "r": the rounds, from PIN_ROUNDS up (absent: a PIN set before, 20000 rounds).
        val r = if (o.has("r")) o.get("r").let { n -> require(Canon.isInt(n) && (n as Number).toLong() in PIN_ROUNDS.toLong()..MAX_ROUNDS.toLong()) { "pin" }; (n as Number).toInt() } else OLD_ROUNDS
        return KidPin(salt!!, h!!, r)
    }

    fun pinJson(p: KidPin): JSONObject = JSONObject().put("salt", p.salt).put("h", p.h).apply { if (p.r != OLD_ROUNDS) put("r", p.r) }

    // ---- configs from an older parent app (no k11 fields)

    /** True when config [c] carries any k11 field: it then sets the chatting level, quiet times, time zone and PIN, all four. */
    fun hasK11(c: JSONObject): Boolean = c.has("chat") || c.has("quiet") || c.has("pin") || c.has("tz")

    /**
     * An older parent app's config only adds: its people and parents, with the names it gives them, plus everyone [cur]
     * has that it doesn't list. Such an app may never have seen the newest list, so taking anyone off needs a parent app
     * with k11 (7.18). At most [TvRules.MAX_CONTACTS] people.
     */
    fun addOnly(new: TvConfig, cur: TvConfig): TvConfig {
        val people = (new.contacts + cur.contacts.filter { c -> new.contacts.none { it.k == c.k } }).sortedBy { it.k }.take(TvRules.MAX_CONTACTS)
        return new.copy(contacts = people, parents = (new.parents + cur.parents).distinct().sorted())
    }

    /**
     * The config a device runs: [new] as it came, or, when it has no k11 field (an older parent app sent it), [new] with
     * the device's current chatting level, quiet times, time zone and PIN from [cur] kept, and nobody taken off ([addOnly]).
     * k12 the same way, field by field: no "hav" keeps Daily Hávamál on or off as it was; no "havw" keeps the wording and
     * the translation's hash. k13 too, field by field: no "look", "season", "accent", "lookpick" or "kname" keeps that one;
     * and k14 ([KidK14.FIELDS]) the same way.
     */
    fun keepK11(new: TvConfig, cur: TvConfig?): TvConfig {
        if (cur == null) return new
        var c = if (hasK11(new.raw)) new else addOnly(new, cur).copy(chat = cur.chat, quiet = cur.quiet, pin = cur.pin, tz = cur.tz)
        if (!Havamal.hasOn(new.raw)) c = c.copy(hav = cur.hav)
        if (!Havamal.hasWording(new.raw)) c = c.copy(havw = cur.havw, havh = cur.havh)
        return KidK14.keep(KidLook.keep(c, cur), cur)
    }

    /** True when the device must keep values beside config [c] (it lacks a k11, k12, k13 or k14 field), so a restart keeps them. */
    fun needsKept(c: JSONObject): Boolean = !hasK11(c) || !Havamal.hasOn(c) || !Havamal.hasWording(c) || KidLook.needsKept(c) || KidK14.needsKept(c)

    /** The k11, k12, k13 and k14 values a device keeps beside its signed config, so a restart keeps what [keepK11] kept. */
    fun keptJson(c: TvConfig): JSONObject = KidK14.keptInto(KidLook.keptInto(
        Havamal.keptInto(JSONObject().put("chat", c.chat).put("quiet", quietJson(c.quiet)).apply { c.pin?.let { put("pin", pinJson(it)) } }
            // The people and parents an older parent app's config didn't take off ([addOnly]), and the time zone.
            .put("tz", c.tz).put("parents", JSONArray(c.parents))
            .put("contacts", JSONArray().apply { c.contacts.forEach { put(JSONObject().put("k", it.k).put("n", it.n).put("p", it.p)) } }), c), c), c)

    /** [cfg] read back at start, with the values [keptJson] saved for the fields its own config lacks. */
    fun keptIn(cfg: TvConfig, saved: JSONObject?): TvConfig {
        if (saved == null) return cfg
        val c = if (hasK11(cfg.raw)) cfg else try {
            val l = saved.opt("chat")
            val chat = if (Canon.isInt(l)) (l as Number).toInt().coerceIn(QUICK_ONLY, EVERYTHING) else EVERYTHING
            val quiet = if (saved.has("quiet")) quietIn(saved.get("quiet")) else emptyList()
            val pin = if (saved.has("pin")) pinIn(saved.get("pin")) else null
            val tz = saved.optString("tz").takeIf { it.isNotEmpty() }?.let { tzIn(it) } ?: ""
            // The people and parents kept from before an older parent app's config (it only adds).
            val ps = saved.optJSONArray("contacts")
            val people = if (ps == null) emptyList() else (0 until ps.length()).map { i ->
                val x = ps.getJSONObject(i)
                TvPerson(x.getString("k"), Canon.text(x.getString("n"), TvRules.MAX_NAME), x.getString("p"))
            }
            val pa = saved.optJSONArray("parents")
            val parents = if (pa == null) emptyList() else (0 until pa.length()).map { pa.getString(it) }
            addOnly(cfg, cfg.copy(contacts = people, parents = parents)).copy(chat = chat, quiet = quiet, pin = pin, tz = tz)
        } catch (e: Exception) { cfg }
        val h = try { Havamal.keptFrom(c, saved) } catch (e: Exception) { c }
        val l = try { KidLook.keptFrom(h, saved) } catch (e: Exception) { h }
        return try { KidK14.keptFrom(l, saved) } catch (e: Exception) { l }
    }

    // ---- round 17 (7.18): removals win over an older parent app, and devices that left kids' mode

    /** The most people (and parents) kept as taken off. */
    const val MAX_GONE = 200

    /**
     * People ([goneC]) and parents ([goneP]) a parent app with k11 took off, after config [new] replaced [cur]: those it
     * drops are added, those it lists again are no longer gone. Only a config with a k11 field changes them.
     */
    fun tombstones(cur: TvConfig, new: TvConfig, goneC: Set<String>, goneP: Set<String>): Pair<Set<String>, Set<String>> {
        val nc = new.contacts.map { it.k }.toSet()
        val np = new.parents.toSet()
        val c = (goneC + cur.contacts.map { it.k }.filter { it !in nc }) - nc
        val p = (goneP + cur.parents.filter { it !in np }) - np
        return c.toList().takeLast(MAX_GONE).toSet() to p.toList().takeLast(MAX_GONE).toSet()
    }

    /**
     * An older parent app's config [c] (no k11 field) can't bring back anyone a newer parent took off: people in [goneC]
     * and parents in [goneP] are left out (never its signer). Returns the config and the keys left out.
     */
    fun stripGone(c: TvConfig, goneC: Set<String>, goneP: Set<String>): Pair<TvConfig, List<String>> {
        val outC = c.contacts.filter { it.k in goneC && it.k != c.by }.map { it.k }
        val outP = c.parents.filter { it in goneP && it != c.by }
        if (outC.isEmpty() && outP.isEmpty()) return c to emptyList()
        return c.copy(contacts = c.contacts.filter { it.k !in outC }, parents = c.parents.filter { it !in outP }) to (outC + outP).distinct()
    }

    /** The people a device talks to: [c]'s, without other Kids' Devices that left kids' mode ([left]; never a parent). */
    fun withoutLeft(c: TvConfig, left: Collection<String>): TvConfig =
        if (left.isEmpty()) c else c.copy(contacts = c.contacts.filter { it.k !in left || it.k in c.parents })

    /**
     * Devices that left kids' mode ([left]: key to the trusted time it was heard) after config [c] arrived: a k11 config
     * signed after that ([c].ts) that lists one again (its parent added it back on purpose) clears it.
     */
    fun leftAfter(left: Map<String, Long>, c: TvConfig): Map<String, Long> =
        if (!hasK11(c.raw)) left else left.filter { (k, at) -> !(c.ts > at && c.contacts.any { it.k == k }) }

    /** Device to its parents (round 17): [k], another family's Kids' Device on its list, left kids' mode. */
    fun kidLeftMessage(k: String): String = JSONObject().put("t", "kidleft").put("k", k).toString()

    /** The key in a well-formed "kidleft", else null. */
    fun kidLeftIn(o: JSONObject): String? {
        if (Canon.keys(o) != setOf("t", "k") || o.opt("t") != "kidleft") return null
        return (o.opt("k") as? String)?.takeIf { KidK14.keyOk(it) }
    }

    /** Device to a parent whose config came after another parent paired it (round 17): it's taken. */
    fun takenMessage(): String = JSONObject().put("t", "taken").toString()

    fun isTaken(o: JSONObject): Boolean = Canon.keys(o) == setOf("t") && o.opt("t") == "taken"

    /** PBKDF2 (RFC 8018) with HMAC-SHA256, one 32-byte block. */
    internal fun pbkdf2(pass: ByteArray, salt: ByteArray, rounds: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pass, "HmacSHA256"))
        mac.update(salt)
        mac.update(byteArrayOf(0, 0, 0, 1))
        var u = mac.doFinal()
        val out = u.copyOf()
        repeat(rounds - 1) {
            u = mac.doFinal(u)
            for (i in out.indices) out[i] = (out[i].toInt() xor u[i].toInt()).toByte()
        }
        return out
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // ---- unlocking from a parent's phone, and leaving kids' mode (room "tvconfig")

    /** A fresh ask id: 16 random bytes, hex. */
    fun askId(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Device to parent: someone at the device asks to open its parent settings. */
    fun unlockAskMessage(id: String): String = JSONObject().put("t", "unlockask").put("id", id).toString()

    /** Parent to device: open the parent settings for the ask [id]. */
    fun unlockMessage(id: String): String = JSONObject().put("t", "unlock").put("id", id).toString()

    /** The id of a well-formed "unlockask" or "unlock" (exactly "t" and "id", id 32 hex), else null. */
    fun idIn(o: JSONObject, t: String): String? {
        if (o.opt("t") != t || Canon.keys(o) != setOf("t", "id")) return null
        return (o.opt("id") as? String)?.takeIf { HEX32.matches(it) }
    }

    /** The device takes an "unlock" only from a current parent, for the ask it has open, within [ASK_MS]. */
    fun unlockOk(fromKey: String, parents: Collection<String>, asked: String?, askedAt: Long, id: String, now: Long): Boolean =
        fromKey in parents && asked != null && id == asked && now - askedAt in 0L..ASK_MS

    /** The largest text scale "For a parent" uses on a tablet or phone, so its keypad fits at the largest sizes. */
    const val PAD_FONT_MAX = 1.5f

    /** How long a pairing code shows before a fresh one (its invite lasts an hour of the clock's time). */
    const val CODE_FRESH_MS = 50 * 60_000L

    /** A pairing code made at [madeAt] (the clock) needs remaking at [now]: near its hour, or the clock went back. */
    fun codeStale(madeAt: Long, now: Long): Boolean = now - madeAt >= CODE_FRESH_MS || now < madeAt

    /**
     * The remote's search, mic or Assistant button (Android's KEYCODE_SEARCH 84, KEYCODE_ASSIST 219, KEYCODE_VOICE_ASSIST
     * 231): a Kids' Device keeps it for a voice message, so it never leaves Muninn for the system's search.
     */
    fun isMicKey(code: Int): Boolean = code == 84 || code == 219 || code == 231

    /** Device to parents (and everyone on its list, so they stop treating it as a Kids' Device): it left kids' mode. */
    fun leftMessage(): String = JSONObject().put("t", "left").toString()

    fun isLeft(o: JSONObject): Boolean = Canon.keys(o) == setOf("t") && o.opt("t") == "left"

    // ---- Round 18 (R2): a Leave's "left" is sent until each recipient's copy is taken (a relay stored it, or the Wi-Fi or
    // Bluetooth took it), with a bounded wait, before the device wipes itself.

    /** A Leave finished at the next start waits at most this long for Muninn to be running (else it is tried next start). */
    const val LEAVE_READY_MS = 60_000L
    /** All the sending (every round, every recipient) takes at most this long; the wipe follows either way. */
    const val LEAVE_SEND_MS = 45_000L
    /** How long one send waits for a relay to say it stored it. */
    const val LEAVE_ONE_MS = 5_000L

    /** The pause after send round [round] (0 first) before the recipients still left are tried again: 2, 4, 8, then 10 s. */
    fun leaveRetryGap(round: Int): Long = minOf(10_000L, 2_000L shl round.coerceIn(0, 3))

    /** Who still needs "left" after a round: [left] less those whose copy was taken. */
    fun leaveStillLeft(left: List<String>, taken: Set<String>): List<String> = left.filter { it !in taken }

    /** The device kind in an "applied", or "" when it says none (or one this app doesn't know). */
    fun deviceIn(o: JSONObject): String = (o.opt("dev") as? String)?.takeIf { it in DEVICES } ?: ""

    /** "TV", "tablet", "phone"; a device that says nothing is an older Kids' TV ([k11] false) or not known yet. */
    fun deviceName(dev: String, k11: Boolean): String = when (dev) {
        TV -> "TV"
        TABLET -> "tablet"
        PHONE -> "phone"
        else -> if (k11) "device" else "TV"
    }

    // ---- groups and other Kids' Devices

    /** A group invite (or being put straight into a group) on a Kids' Device: only from one of its parents. */
    fun groupInviteOk(from: String, parents: Collection<String>): Boolean = from in parents

    /**
     * Rooms "group", "gmsg", "gphoto" and "greceipt" on a Kids' Device: from its parents, or from someone in a group a
     * parent put it in ([inMyGroup]). During a quiet time only the parents get through (receipts always).
     */
    fun groupRoomOk(from: String, room: String, parents: Collection<String>, inMyGroup: Boolean, quiet: QuietTime?): Boolean {
        if (from !in parents && !inMyGroup) return false
        return room == "greceipt" || passes(from, parents, quiet)
    }

    val GROUP_ROOMS = setOf("group", "gmsg", "gphoto", "greceipt")

    /**
     * Whom a parent may put on a device's "Who it can talk to": any real contact but the device itself and this phone,
     * other Kids' Devices included (this family's or another's).
     */
    fun canAdd(key: String, tvKey: String, me: String, groupOnly: Boolean, reach: Collection<String>): Boolean =
        !groupOnly && key != tvKey && key != me && key !in reach

    /** A group picker shows a Kids' Device only to one of its parents ([mine]). */
    fun groupPickOk(isKidDevice: Boolean, mine: Boolean): Boolean = !isKidDevice || mine

    /** "Last seen just now", "Last seen 4:12 PM" (today), "Last seen Tue 4:12 PM", or "Not heard from yet". */
    fun lastSeen(at: Long, now: Long, time: (Long) -> String, day: (Long) -> String): String = when {
        at <= 0L -> "Not heard from yet"
        now - at < 2 * 60_000L -> "Last seen just now"
        now - at < 20 * 3_600_000L -> "Last seen ${time(at)}"
        else -> "Last seen ${day(at)}"
    }

    /** A device not heard from for [SILENT_MS]: its parents are told once ([told]: when they last were, 0: never). */
    fun silentNotice(seen: Long, told: Long, now: Long): Boolean = seen > 0L && now - seen >= SILENT_MS && told < seen
}

/**
 * The people the kids' home shows, parents first, then everyone else, each by name: everyone on the list, or only the
 * parents during a quiet time ([quiet]).
 */
internal fun kidPeople(cfg: TvConfig, quiet: QuietTime?): List<TvPerson> =
    cfg.contacts.sortedWith(compareBy<TvPerson>({ it.k !in cfg.parents }, { it.n.lowercase() })).filter { KidRules.passes(it.k, cfg.parents, quiet) }
