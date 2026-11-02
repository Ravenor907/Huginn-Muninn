package io.github.ravenor907.muninn.net

import io.github.ravenor907.muninn.net.call.RoomLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.math.BigInteger
import java.security.PrivateKey

/**
 * Cap g2 (shared/docs/protocol.md section 26), the parts with no Android in them, checked against
 * shared/vectors/g2-vectors.json in ModTest (shared/tools/g2_ref.py is the reference; Huginn's twin is ModWire):
 *  - group timeouts, bans and removed messages: records an admin signs, like a group state, with their own domain
 *    {"a": "ban" | "unban" | "timeout" | "untimeout" | "remove", "g", "k" (who), "by" (the admin), "ts", ["until"],
 *    ["m" (the message, only for "remove")], "sig"}.
 *    [Groups] keeps them, sends them in room "group" (t "mod") and drops posts from whoever they name.
 *  - the per-person volume (0 to 200 %) applied to someone's decoded sound before mixing.
 */
object ModLogic {
    const val CAP = "g2"
    const val DOMAIN = "huginn-muninn gmod v1"
    const val MINUTE = 60_000L
    const val DAY = 86_400_000L
    /** The longest timeout taken: a week, and a minute of slack. */
    const val LONGEST = 7 * DAY + MINUTE
    /** Records in one "mod" control message. */
    const val MAX_LIST = 50
    const val VOLUME_MIN = 0
    const val VOLUME_MAX = 200
    const val VOLUME_DEFAULT = 100
    /** What the menus offer: 10 minutes, 1 hour, 1 day, 1 week. */
    val TIMEOUTS = listOf(10 * MINUTE, 60 * MINUTE, DAY, 7 * DAY)
    val TIMEOUT_NAMES = listOf("10 minutes", "1 hour", "1 day", "1 week")
    private val ACTIONS = setOf("ban", "unban", "timeout", "untimeout", "remove")
    private const val MAX_SAFE = 1L shl 53

    /** A JSON whole number (not a fraction, not true or false), or null. */
    fun whole(x: Any?): Long? = when (x) {
        is Int -> x.toLong()
        is Long -> x
        is Short -> x.toLong()
        is Byte -> x.toLong()
        is BigInteger -> if (x.bitLength() < 63) x.toLong() else null
        else -> null
    }

    /** "" when [r] is well-formed, otherwise why not. Does not check the signature. */
    fun check(r: JSONObject): String {
        val a = r.opt("a") as? String ?: return "fields"
        val base = setOf("a", "g", "k", "by", "ts", "sig")
        val want = if (a == "timeout") base + "until" else if (a == "remove") base + "m" else base
        if (r.keys().asSequence().toSet() != want) return "fields"
        if (a !in ACTIONS) return "action"
        val g = r.opt("g") as? String ?: return "group id"
        if (!RoomLogic.isId(g, 16)) return "group id"
        for (f in listOf("k", "by")) {
            val v = r.opt(f) as? String ?: return f
            if (v.isEmpty() || v.length > 200) return f
        }
        if (r.getString("k") == r.getString("by")) return "self"
        val ts = whole(r.opt("ts")) ?: return "ts"
        if (ts <= 0 || ts >= MAX_SAFE) return "ts"
        if (a == "timeout") {
            val u = whole(r.opt("until")) ?: return "until"
            if (u <= ts || u > ts + LONGEST) return "until"
        }
        if (a == "remove" && !Proto06.isLogicalId(r.opt("m") as? String ?: "")) return "m"
        return ""
    }

    /** The canonical text a record's signature covers (everything but "sig"), or null when it can't be canonical. */
    private fun unsigned(r: JSONObject): String? = try {
        val o = JSONObject()
        r.keys().forEach { if (it != "sig") o.put(it, r.get(it)) }
        Proto06.canon(o)
    } catch (e: Exception) { null }

    /** "ok" when [r] is taken against the receiver's current state of its group ([state] null: none), otherwise why not. */
    fun accept(state: Proto06.State?, r: JSONObject): String {
        val why = check(r)
        if (why.isNotEmpty()) return "malformed: $why"
        val by = r.getString("by"); val k = r.getString("k")
        val text = unsigned(r) ?: return "malformed: text"
        val sig = r.opt("sig") as? String ?: return "bad signature"
        if (!Proto06.verify(by, DOMAIN, text, sig)) return "bad signature"
        if (state == null || state.g != r.getString("g")) return "unknown group"
        if (!state.isAdmin(by)) return "signer is not an admin"
        if (k == state.owner) return "the owner"
        if (state.isAdmin(k) && by != state.owner) return "an admin, and the signer isn't the owner"
        return "ok"
    }

    /** A "remove": an admin's Delete for everyone of message "m" (never kept in a slot). */
    fun isRemove(r: JSONObject?): Boolean = r?.optString("a") == "remove"

    /** "ban" for ban and unban, "timeout" for timeout and untimeout: each slot keeps its newest record per person. */
    fun slot(r: JSONObject): String = if (r.optString("a") == "ban" || r.optString("a") == "unban") "ban" else "timeout"

    /** True when [next] replaces [cur] in its slot: a higher ts (the same ts keeps cur). */
    fun newer(cur: JSONObject?, next: JSONObject): Boolean = cur == null || (whole(next.opt("ts")) ?: 0L) > (whole(cur.opt("ts")) ?: 0L)

    fun banned(rec: JSONObject?): Boolean = rec?.optString("a") == "ban"

    fun timedOut(rec: JSONObject?, now: Long): Boolean = rec?.optString("a") == "timeout" && now < (whole(rec.opt("until")) ?: 0L)

    fun until(rec: JSONObject?): Long = whole(rec?.opt("until")) ?: 0L

    /** A signed record. [until] only for "timeout". */
    fun make(priv: PrivateKey, action: String, g: String, who: String, by: String, ts: Long, until: Long = 0): JSONObject {
        val r = JSONObject().put("a", action).put("g", g).put("k", who).put("by", by).put("ts", ts)
        if (action == "timeout") r.put("until", until)
        return r.put("sig", Proto06.sign(priv, DOMAIN, Proto06.canon(r)))
    }

    /** A signed "remove" of message [msg], which [author] wrote. */
    fun makeRemove(priv: PrivateKey, g: String, author: String, by: String, ts: Long, msg: String): JSONObject {
        val r = JSONObject().put("a", "remove").put("g", g).put("k", author).put("by", by).put("ts", ts).put("m", msg)
        return r.put("sig", Proto06.sign(priv, DOMAIN, Proto06.canon(r)))
    }

    /** Whole numbers 0 to 200; anything else is 100. */
    fun clampVolume(x: Any?): Int = whole(x)?.coerceIn(VOLUME_MIN.toLong(), VOLUME_MAX.toLong())?.toInt() ?: VOLUME_DEFAULT
    fun clampVolume(pct: Int): Int = pct.coerceIn(VOLUME_MIN, VOLUME_MAX)

    /** One sample at [pct] percent (clamped), truncated toward zero and clipped to 16 bits. */
    fun gain(sample: Int, pct: Int): Int = (sample * clampVolume(pct) / 100).coerceIn(-32768, 32767)

    /** The first [n] samples of [pcm] in place; 100 % leaves them as they are. */
    fun applyGain(pcm: ShortArray, n: Int, pct: Int) {
        val p = clampVolume(pct)
        if (p == 100) return
        val end = minOf(n, pcm.size)
        if (p == 0) { java.util.Arrays.fill(pcm, 0, end, 0.toShort()); return }
        for (i in 0 until end) pcm[i] = gain(pcm[i].toInt(), p).toShort()
    }
}

/**
 * Mute for me and volume per person, kept on this phone only (in the vault), never on the wire (section 26.1). The
 * same in every room and group; 1:1 calls keep only the volume (Mute for me is made in a room, never for a direct call).
 * A key is someone's chat key.
 */
class PeopleSound(private val read: () -> String?, private val write: (String) -> Unit) {
    /** [show]: their group messages aren't folded while they're muted. */
    data class Person(val muted: Boolean = false, val show: Boolean = false, val volume: Int = ModLogic.VOLUME_DEFAULT)

    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, Person>> = _all.asStateFlow()

    private fun load(): Map<String, Person> = try {
        val o = JSONObject(read() ?: return emptyMap())
        o.keys().asSequence().associateWith { k ->
            val p = o.getJSONObject(k)
            Person(p.optBoolean("m"), p.optBoolean("s"), if (p.has("v")) ModLogic.clampVolume(p.opt("v")) else ModLogic.VOLUME_DEFAULT)
        }
    } catch (e: Exception) { emptyMap() }

    private fun set(m: Map<String, Person>) {
        val kept = m.filterValues { it != Person() }
        _all.value = kept
        try {
            val o = JSONObject()
            kept.forEach { (k, p) ->
                o.put(k, JSONObject().apply { if (p.muted) put("m", true); if (p.show) put("s", true); if (p.volume != ModLogic.VOLUME_DEFAULT) put("v", p.volume) })
            }
            write(o.toString())
        } catch (_: Exception) {}
    }

    fun person(k: String): Person = _all.value[k] ?: Person()
    fun muted(k: String) = person(k).muted
    /** With Mute for me on: their group messages show as "Muted, tap to show" (on by default). */
    fun hides(k: String) = !person(k).show
    fun fold(k: String) = person(k).let { it.muted && !it.show }
    fun volume(k: String) = person(k).volume
    /** What their sound is played at: in a [room], 0 while muted for me, else their volume. Read for every 20 ms frame. */
    fun gain(k: String, room: Boolean = true): Int = person(k).let { if (it.muted && room) 0 else it.volume }

    @Synchronized fun setMuted(k: String, on: Boolean) = set(_all.value + (k to person(k).copy(muted = on)))
    @Synchronized fun setHides(k: String, on: Boolean) = set(_all.value + (k to person(k).copy(show = !on)))
    @Synchronized fun setVolume(k: String, pct: Int) = set(_all.value + (k to person(k).copy(volume = ModLogic.clampVolume(pct))))

    companion object {
        /** Set by the app: the one store, so the call code reads volumes without a Chat. */
        @Volatile var current: PeopleSound? = null
        fun gainOf(k: String, room: Boolean = true): Int = current?.gain(k, room) ?: ModLogic.VOLUME_DEFAULT
    }
}
