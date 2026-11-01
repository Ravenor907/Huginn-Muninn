package io.github.ravenor907.muninn.net.call

import io.github.ravenor907.muninn.net.Proto06
import org.json.JSONObject
import java.security.PrivateKey

/**
 * The deterministic parts of voice rooms and group calls (shared/docs/protocol.md section 6), free of Android
 * so unit tests check them against shared/vectors/proto06-vectors.json, as Huginn's call self-test does:
 *  - room messages {"t","call":"-","g","ch","ts","sig"}, signed with the chat key (domain
 *    "huginn-muninn vroom v1"), and the receiver's checks in the spec's order (6.2)
 *  - presence: who is in which room; the newest ts per sender wins, 25 minutes without news is gone (6.4)
 *  - the glare rule (the lower chat key's offer wins) and the room size, 8 people (6.3)
 * shared/tools/proto06_ref.py is the reference; when this and it disagree, it wins.
 */
object RoomLogic {
    const val DOMAIN = "huginn-muninn vroom v1"
    const val MAX_PEOPLE = 8
    const val FRESH_PAST_MS = 60_000L
    const val FRESH_AHEAD_MS = 120_000L
    const val PRESENCE_TTL_MS = 25 * 60_000L
    const val ANNOUNCE_MS = 10 * 60_000L
    const val RING_MS = 45_000L
    val TYPES = setOf("vjoin", "vleave", "vring", "vdecline", "full")
    private val FIELDS = setOf("t", "call", "g", "ch", "ts", "sig")

    /** Exactly [bytes] raw bytes as canonical base64url without padding. */
    fun isId(s: String, bytes: Int): Boolean {
        if (s.length != (4 * bytes + 2) / 3) return false
        if (!s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return false
        val raw = try { Proto06.b64urlD(s) } catch (e: Exception) { return false }
        return raw.size == bytes && Proto06.b64url(raw) == s
    }

    /** A voice channel id (8 bytes) or a group call id (16 bytes). */
    fun isRoomId(ch: String) = isId(ch, 8) || isId(ch, 16)
    fun isGroupCall(ch: String) = isId(ch, 16)
    fun newGroupCallId() = Proto06.newGroupId()

    /** A signed room message from this device. */
    fun make(priv: PrivateKey, t: String, g: String, ch: String, ts: Long): JSONObject {
        val m = linkedMapOf<String, Any>("t" to t, "call" to "-", "g" to g, "ch" to ch, "ts" to ts)
        val sig = Proto06.sign(priv, DOMAIN, Proto06.canon(m))
        return JSONObject().put("t", t).put("call", "-").put("g", g).put("ch", ch).put("ts", ts).put("sig", sig)
    }

    private fun unsignedCanon(m: JSONObject): String =
        Proto06.canon(m.keys().asSequence().filter { it != "sig" }.toList().associateWith { m.opt(it) })

    fun verify(from: String, m: JSONObject): Boolean = try {
        val sig = m.opt("sig") as? String ?: return false
        Proto06.verify(from, DOMAIN, unsignedCanon(m), sig)
    } catch (e: Exception) { false }

    /** Null when well-formed, otherwise the reason (as the reference words it). */
    fun check(m: JSONObject): String? {
        if (m.keys().asSequence().toSet() != FIELDS) return "fields"
        if ((m.opt("t") as? String) !in TYPES) return "type"
        if (m.opt("call") as? String != "-") return "call"
        if (!((m.opt("g") as? String)?.let { isId(it, 16) } ?: false)) return "group id"
        if (!((m.opt("ch") as? String)?.let { isRoomId(it) } ?: false)) return "room id"
        val ts = when (val x = m.opt("ts")) { is Int -> x.toLong(); is Long -> x; else -> -1L }
        if (ts < 0 || ts >= (1L shl 53)) return "ts"
        return null
    }

    fun ts(m: JSONObject): Long = (m.opt("ts") as? Number)?.toLong() ?: 0L

    /**
     * 6.2: "ok" (act on it), "stale" (presence only; a stale vring is a missed group call) or why it was
     * refused. [state]: this device's current state of m's group, null when it has none. [me]: my chat key.
     */
    fun accept(state: Proto06.State?, me: String, from: String, m: JSONObject, now: Long): String {
        check(m)?.let { return "malformed: $it" }
        if (!verify(from, m)) return "bad signature"
        if (state == null || state.g != m.getString("g")) return "unknown group"
        if (from == me) return "from myself"
        if (!state.has(from)) return "sender not a member"
        if (!state.has(me)) return "not a member"
        val ch = m.getString("ch")
        if (ch.length == 11 && state.channel(ch)?.t != "voice") return "not a voice room"
        val ts = ts(m)
        if (ts < now - FRESH_PAST_MS || ts > now + FRESH_AHEAD_MS) return "stale"
        return "ok"
    }

    /** 6.3: whose offer wins when two cross (chat key text, byte order; base64 is ASCII). */
    fun offerWins(a: String, b: String) = if (a < b) a else b
    fun full(peers: Int) = peers >= MAX_PEOPLE - 1

    /** 6.4, for one device. Thread-safe. */
    class Presence {
        private data class Entry(val inRoom: Boolean, val ts: Long, val seen: Long)
        private val entries = HashMap<Triple<String, String, String>, Entry>()

        /** An accepted ("ok" or "stale") vjoin or vleave. True if it changed anything. */
        @Synchronized fun apply(from: String, m: JSONObject, now: Long): Boolean {
            val t = m.optString("t")
            if (t != "vjoin" && t != "vleave") return false
            val k = Triple(m.optString("g"), m.optString("ch"), from)
            val ts = ts(m)
            val cur = entries[k]
            if (cur != null && ts <= cur.ts) return false
            entries[k] = Entry(t == "vjoin", ts, now)
            return true
        }

        /** Who is in (g, ch), sorted. */
        @Synchronized fun inRoom(g: String, ch: String, now: Long): List<String> =
            entries.filter { (k, e) -> k.first == g && k.second == ch && e.inRoom && now - e.seen <= PRESENCE_TTL_MS }.map { it.key.third }.sorted()

        /** Rooms of g with anyone in them: ch to sorted keys. */
        @Synchronized fun rooms(g: String, now: Long): Map<String, List<String>> =
            entries.filter { (k, e) -> k.first == g && e.inRoom && now - e.seen <= PRESENCE_TTL_MS }.entries
                .groupBy({ it.key.second }, { it.key.third }).mapValues { it.value.sorted() }

        @Synchronized fun clear() = entries.clear()
    }
}
