package io.github.ravenor907.muninn.net

import org.json.JSONObject

/**
 * Cap m1 (shared/docs/protocol.md, section 11): replies, reactions, edits, typing and active status, the
 * parts both apps must agree on. shared/tools/m1_ref.py is the reference and writes shared/vectors/m1-vectors.json.
 *
 *  - room "rich":  a 1:1 message with a reply, {text, re}. Handled like "private" in every other way.
 *  - room "mx":    {t:"react", msg, e, ts} and {t:"edit", msg, text, ts} for a 1:1 message.
 *  - room "live":  {t:"typing"|"active", on, ts}, sealed with v1, never retried. Cap p1 (section 22) adds "st"
 *                  (online, away or dnd) and "cs" (a custom status, at most 80 characters) to "active".
 *  - gmsg "re", and room "group" t "react" and "edit" for group messages.
 */
object Rich {
    const val CAP = "m1"
    const val MAX_TEXT = 2000
    const val MAX_EMOJI = 16
    const val MAX_ID = 64
    const val LIVE_WINDOW_MS = 60_000L
    /** Typing shows this long after the last "on" (the sender says it again every few seconds while typing). */
    const val TYPING_MS = 6_000L
    /** Active shows this long after the last "on" (sent on opening the app and every 2 minutes while it's open). */
    const val ACTIVE_MS = 5 * 60_000L
    const val ACTIVE_EVERY_MS = 2 * 60_000L
    const val TYPING_EVERY_MS = 3_000L
    /** Cap p1: the statuses that go on the wire in "st" (invisible is sent as not active, with no "st"). */
    val STATUSES = setOf("online", "away", "dnd")
    const val MAX_CUSTOM = 80
    /** The five quick reactions, Messenger style; anything else comes from the emoji picker. */
    val QUICK = listOf("❤️", "😂", "👍", "😮", "😢")

    class Refused(why: String) : Exception(why)

    private fun cp(s: String) = s.codePointCount(0, s.length)

    fun isMsgId(s: Any?): Boolean = s is String && s.length in 1..MAX_ID &&
        s.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

    fun isTs(x: Any?): Boolean = (x is Int && x >= 0) || (x is Long && x >= 0 && x < (1L shl 53))

    fun ts(p: JSONObject): Long = (p.opt("ts") as Number).toLong()

    fun textOk(t: Any?): Boolean = t is String && cp(t) <= MAX_TEXT && t.isNotBlank()

    /** "" takes a reaction away; otherwise 1 to 16 code points, not blank, no control characters. */
    fun emojiOk(e: Any?): Boolean {
        if (e !is String) return false
        if (e.isEmpty()) return true
        return cp(e) <= MAX_EMOJI && e.isNotBlank() && e.none { it.code < 0x20 || it.code == 0x7F }
    }

    /** Throws [Refused] unless [p] is well-formed for [room] ("rich", "mx", "live", "gmsg" or "group"). */
    fun check(room: String, p: JSONObject) {
        when (room) {
            "rich" -> {
                if (!textOk(p.opt("text"))) throw Refused("text")
                if (p.has("re") && !isMsgId(p.opt("re"))) throw Refused("re")
            }
            "mx" -> {
                if (!isMsgId(p.opt("msg"))) throw Refused("msg")
                if (!isTs(p.opt("ts"))) throw Refused("ts")
                when (p.opt("t")) {
                    "react" -> if (!emojiOk(p.opt("e"))) throw Refused("e")
                    "edit" -> if (!textOk(p.opt("text"))) throw Refused("text")
                    else -> throw Refused("t")
                }
            }
            "live" -> {
                val t = p.opt("t")
                if (t != "typing" && t != "active") throw Refused("t")
                if (p.opt("on") !is Boolean) throw Refused("on")
                if (!isTs(p.opt("ts"))) throw Refused("ts")
                // Cap p1: a status and a custom status text, when given.
                if (p.has("st") && p.opt("st") !in STATUSES) throw Refused("st")
                if (p.has("cs") && !customOk(p.opt("cs"))) throw Refused("cs")
            }
            "gmsg" -> if (p.has("re") && !Proto06.isLogicalId(p.opt("re") as? String ?: "")) throw Refused("re")
            "group" -> {
                val t = p.opt("t")
                if (t != "react" && t != "edit") throw Refused("t")
                val g = p.opt("g")
                if (g !is String || g.length !in 1..40) throw Refused("g")
                if (!Proto06.isLogicalId(p.opt("id") as? String ?: "")) throw Refused("id")
                if (!Proto06.isLogicalId(p.opt("msg") as? String ?: "")) throw Refused("msg")
                if (!isTs(p.opt("ts"))) throw Refused("ts")
                if (t == "react") { if (!emojiOk(p.opt("e"))) throw Refused("e") }
                else if (!textOk(p.opt("text"))) throw Refused("text")
            }
            else -> throw Refused("room")
        }
    }

    fun ok(room: String, p: JSONObject) = try { check(room, p); true } catch (e: Refused) { false }

    /** Cap p1: a custom status is text of at most 80 code points with no control characters ("" is none). */
    fun customOk(c: Any?): Boolean = c is String && cp(c) <= MAX_CUSTOM && c.none { it.code < 0x20 || it.code == 0x7F }

    /** A typing or active signal counts only if the sender's clock is within a minute of ours. */
    fun fresh(ts: Long, now: Long) = ts in (now - LIVE_WINDOW_MS)..(now + LIVE_WINDOW_MS)

    /** One reaction per person: a later ts replaces it ("" takes it away); older or equal changes nothing. */
    data class Reaction(val e: String, val ts: Long)

    /** [all]: who to their latest reaction (an empty one stays as a marker so an older copy can't come back). */
    fun react(all: Map<String, Reaction>, from: String, e: String, ts: Long): Map<String, Reaction>? {
        val cur = all[from]
        if (cur != null && ts <= cur.ts) return null
        return all + (from to Reaction(e, ts))
    }

    /** The reactions that stand: who to emoji. */
    fun shown(all: Map<String, Reaction>): Map<String, String> = all.filterValues { it.e.isNotEmpty() }.mapValues { it.value.e }

    /** Emoji with how many chose each, most first (for the chips under a bubble). */
    fun counts(all: Map<String, Reaction>): List<Pair<String, Int>> =
        shown(all).values.groupingBy { it }.eachCount().entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key to it.value }

    /** An edit is applied only from the message's author and only if it's newer than the edit shown. */
    fun editWins(author: String, from: String, ts: Long, shownTs: Long) = from == author && ts > shownTs

    fun reactionsToJson(all: Map<String, Reaction>): JSONObject = JSONObject().apply { all.forEach { (k, r) -> put(k, JSONObject().put("e", r.e).put("t", r.ts)) } }
    fun reactionsFromJson(o: JSONObject?): Map<String, Reaction> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Reaction(it.optString("e"), it.optLong("t")) } }
    }

    // ------------------------------------------------------------------ building payloads

    fun richText(text: String, re: String): String = JSONObject().put("text", text).apply { if (re.isNotEmpty()) put("re", re) }.toString()
    fun reactMsg(msg: String, e: String, ts: Long): String = JSONObject().put("t", "react").put("msg", msg).put("e", e).put("ts", ts).toString()
    fun editMsg(msg: String, text: String, ts: Long): String = JSONObject().put("t", "edit").put("msg", msg).put("text", text).put("ts", ts).toString()
    fun liveMsg(t: String, on: Boolean, ts: Long): String = JSONObject().put("t", t).put("on", on).put("ts", ts).toString()

    /**
     * Cap p1: "active" with my status. Invisible (or not active) goes as not active with no "st", so an app
     * without p1 sees exactly what it always did; [custom] is cut to 80 characters and left out when empty.
     */
    fun activeMsg(on: Boolean, ts: Long, status: String, custom: String): String {
        if (!on || status == "invisible") return liveMsg("active", false, ts)
        val o = JSONObject().put("t", "active").put("on", true).put("ts", ts).put("st", if (status in STATUSES) status else "online")
        val c = cut(custom, MAX_CUSTOM)
        if (c.isNotEmpty()) o.put("cs", c)
        return o.toString()
    }

    /** The first [n] code points of [s] (never half a surrogate pair). */
    fun cut(s: String, n: Int): String = if (cp(s) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    /** @mentions: does [text] name [me] as "@name" (any case), followed by the end or a non-letter? */
    fun mentions(text: String, me: String): Boolean {
        val n = me.trim()
        if (n.isEmpty()) return false
        var i = text.indexOf("@$n", ignoreCase = true)
        while (i >= 0) {
            val end = i + 1 + n.length
            val before = if (i == 0) ' ' else text[i - 1]
            if ((end >= text.length || !text[end].isLetterOrDigit()) && !before.isLetterOrDigit()) return true
            i = text.indexOf("@$n", i + 1, ignoreCase = true)
        }
        return false
    }
}
