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

/** The parent PIN as a Kids' Device keeps it: [salt] (16 bytes, hex) and [h], PBKDF2-HMAC-SHA256 of the PIN (hex). */
data class KidPin(val salt: String, val h: String)

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
    const val PIN_ROUNDS = 20_000
    /** Wrong PINs before the device makes the next try wait. */
    const val FREE_TRIES = 5

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

    /** "08:30". */
    fun clock(minute: Int): String = "%02d:%02d".format(minute / 60 % 24, minute % 60)

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

    /** A new PIN as the parent's phone puts it in a config: a fresh salt and the PIN's hash. */
    fun newPin(pin: String): KidPin {
        require(pinValid(pin)) { "pin" }
        val salt = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return KidPin(salt, pinHash(pin, salt))
    }

    /** PBKDF2-HMAC-SHA256 of [pin] with [saltHex], [PIN_ROUNDS] rounds, 32 bytes, lowercase hex. */
    fun pinHash(pin: String, saltHex: String): String =
        pbkdf2(pin.toByteArray(Charsets.UTF_8), hex(saltHex), PIN_ROUNDS).joinToString("") { "%02x".format(it) }

    /** True when [pin] is the one [p] was made from. */
    fun pinOk(pin: String, p: KidPin?): Boolean {
        if (p == null || !pinValid(pin)) return false
        return MessageDigest.isEqual(pinHash(pin, p.salt).toByteArray(), p.h.toByteArray())
    }

    /** How long the next try must wait after [fails] wrong PINs in a row: none for the first 5, then 30 s, doubling, at most 15 min. */
    fun waitMs(fails: Int): Long {
        if (fails < FREE_TRIES) return 0
        return minOf(30_000L shl minOf(fails - FREE_TRIES, 5), 15 * 60_000L)
    }

    /** Reads a config's "pin". Throws IllegalArgumentException("pin") if it isn't well-formed. */
    fun pinIn(v: Any?): KidPin {
        val o = v as? JSONObject ?: throw IllegalArgumentException("pin")
        require(Canon.keys(o) == setOf("salt", "h")) { "pin" }
        val salt = o.get("salt") as? String
        val h = o.get("h") as? String
        require(salt != null && HEX32.matches(salt) && h != null && HEX64.matches(h)) { "pin" }
        return KidPin(salt!!, h!!)
    }

    fun pinJson(p: KidPin): JSONObject = JSONObject().put("salt", p.salt).put("h", p.h)

    // ---- configs from an older parent app (no k11 fields)

    /** True when config [c] carries any k11 field: it then sets the chatting level, quiet times and PIN, all three. */
    fun hasK11(c: JSONObject): Boolean = c.has("chat") || c.has("quiet") || c.has("pin")

    /**
     * The config a device runs: [new] as it came, or, when it has no k11 field (an older parent app sent it), [new] with
     * the device's current chatting level, quiet times and PIN from [cur] kept.
     */
    fun keepK11(new: TvConfig, cur: TvConfig?): TvConfig =
        if (cur == null || hasK11(new.raw)) new else new.copy(chat = cur.chat, quiet = cur.quiet, pin = cur.pin)

    /** The k11 values a device keeps beside its signed config, so a restart keeps what [keepK11] kept. */
    fun keptJson(c: TvConfig): JSONObject =
        JSONObject().put("chat", c.chat).put("quiet", quietJson(c.quiet)).apply { c.pin?.let { put("pin", pinJson(it)) } }

    /** [cfg] read back at start, with the values [keptJson] saved when its own config has no k11 field. */
    fun keptIn(cfg: TvConfig, saved: JSONObject?): TvConfig {
        if (saved == null || hasK11(cfg.raw)) return cfg
        return try {
            val l = saved.opt("chat")
            val chat = if (Canon.isInt(l)) (l as Number).toInt().coerceIn(QUICK_ONLY, EVERYTHING) else EVERYTHING
            val quiet = if (saved.has("quiet")) quietIn(saved.get("quiet")) else emptyList()
            val pin = if (saved.has("pin")) pinIn(saved.get("pin")) else null
            cfg.copy(chat = chat, quiet = quiet, pin = pin)
        } catch (e: IllegalArgumentException) { cfg }
    }

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
        fromKey in parents && asked != null && id == asked && now - askedAt in 0..ASK_MS

    /** Device to parents: it left kids' mode (someone with the PIN or a parent's phone said so). */
    fun leftMessage(): String = JSONObject().put("t", "left").toString()

    fun isLeft(o: JSONObject): Boolean = Canon.keys(o) == setOf("t") && o.opt("t") == "left"

    /** The device kind in an "applied", or "" when it says none (or one this app doesn't know). */
    fun deviceIn(o: JSONObject): String = (o.opt("dev") as? String)?.takeIf { it in DEVICES } ?: ""

    /** "TV", "tablet", "phone"; a device that says nothing is an older Kids' TV ([k11] false) or not known yet. */
    fun deviceName(dev: String, k11: Boolean): String = when (dev) {
        TV -> "TV"
        TABLET -> "tablet"
        PHONE -> "phone"
        else -> if (k11) "device" else "TV"
    }
}

/**
 * The people the kids' home shows, parents first, then everyone else, each by name: everyone on the list, or only the
 * parents during a quiet time ([quiet]).
 */
internal fun kidPeople(cfg: TvConfig, quiet: QuietTime?): List<TvPerson> =
    cfg.contacts.sortedWith(compareBy<TvPerson>({ it.k !in cfg.parents }, { it.n.lowercase() })).filter { KidRules.passes(it.k, cfg.parents, quiet) }
