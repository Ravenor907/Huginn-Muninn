package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cap m2 (shared/docs/protocol.md, section 12): photo albums, video messages, polls and link previews, the parts
 * both apps must agree on. shared/tools/m2_ref.py is the reference and writes shared/vectors/m2-vectors.json.
 *
 *  - Albums: a photo piece's clear fields "al" (album id), "ai" (its place), "an" (how many). Anyone may get them;
 *    an app without m2 shows the photos one by one.
 *  - Video messages: a photo piece with "kind":"video" and "ms", the whole an MP4 of at most a minute. Only to m2.
 *  - Polls: "poll" {q, a, m} in a "rich" or "gmsg" payload, whose text says the same for apps without m2.
 *    Votes: room "mx" or "group", t "vote", {msg, v, ts}. Only to m2.
 *  - Link previews: "lp" {u, t, d, i} in a "rich" or "gmsg" payload, made on the sender's phone. The receiver never
 *    fetches anything; tapping asks before opening the browser.
 */
object More {
    const val CAP = "m2"
    const val ALBUM_MAX = 10
    const val VIDEO_MAX_MS = 60_000
    const val QUESTION_MAX = 200
    const val OPTION_MAX = 100
    const val OPTIONS_MIN = 2
    const val OPTIONS_MAX = 10
    const val URL_MAX = 2000
    const val TITLE_MAX = 200
    const val DESC_MAX = 300
    const val THUMB_MAX = 24_000

    class Refused(why: String) : Exception(why)

    private fun cp(s: String) = s.codePointCount(0, s.length)

    private fun intIn(x: Any?, lo: Int, hi: Int): Boolean = (x is Int && x in lo..hi) || (x is Long && x in lo.toLong()..hi.toLong())

    /** lo..hi code points, not only spaces (when lo > 0), no control characters but a newline. */
    private fun clean(s: Any?, lo: Int, hi: Int): Boolean = s is String && cp(s) in lo..hi && (lo == 0 || s.isNotBlank()) &&
        s.none { (it.code < 0x20 && it != '\n') || it.code == 0x7F }

    private fun oneLine(s: Any?, lo: Int, hi: Int) = clean(s, lo, hi) && !(s as String).contains('\n')

    private fun checkPoll(poll: Any?) {
        if (poll !is JSONObject) throw Refused("poll")
        if (!oneLine(poll.opt("q"), 1, QUESTION_MAX)) throw Refused("poll.q")
        val o = poll.opt("a") as? JSONArray ?: throw Refused("poll.a")
        if (o.length() !in OPTIONS_MIN..OPTIONS_MAX) throw Refused("poll.a")
        val seen = HashSet<String>()
        for (i in 0 until o.length()) {
            val x = o.opt(i)
            if (!oneLine(x, 1, OPTION_MAX)) throw Refused("poll.a")
            if (!seen.add((x as String).trim().lowercase())) throw Refused("poll.a same")
        }
        if (poll.has("m") && poll.opt("m") !is Boolean) throw Refused("poll.m")
    }

    private fun checkPreview(lp: Any?) {
        if (lp !is JSONObject) throw Refused("lp")
        val u = lp.opt("u")
        if (u !is String || u.length !in 8..URL_MAX || !(u.startsWith("https://") || u.startsWith("http://")) ||
            u.any { it.code <= 0x20 || it.code == 0x7F }) throw Refused("lp.u")
        if (!oneLine(lp.opt("t"), 1, TITLE_MAX)) throw Refused("lp.t")
        if (lp.has("d") && !clean(lp.opt("d"), 0, DESC_MAX)) throw Refused("lp.d")
        if (lp.has("i")) {
            val i = lp.opt("i") as? String ?: throw Refused("lp.i")
            if (i.length !in 4..THUMB_MAX) throw Refused("lp.i")
            val raw = try { java.util.Base64.getDecoder().decode(i) } catch (e: IllegalArgumentException) { throw Refused("lp.i base64") }
            if (raw.size < 3 || raw[0] != 0xFF.toByte() || raw[1] != 0xD8.toByte() || raw[2] != 0xFF.toByte()) throw Refused("lp.i jpeg")
        }
    }

    private fun checkVotes(p: JSONObject) {
        val v = p.opt("v") as? JSONArray ?: throw Refused("v")
        if (v.length() > OPTIONS_MAX) throw Refused("v")
        var last = -1
        for (i in 0 until v.length()) {
            val x = v.opt(i)
            if (!intIn(x, 0, OPTIONS_MAX - 1)) throw Refused("v")
            val n = (x as Number).toInt()
            if (n <= last) throw Refused("v order")
            last = n
        }
    }

    /**
     * Throws [Refused] unless [p] is well-formed under m2 for [room]: "rich" and "gmsg" (the fields "poll" and "lp"),
     * "mx" and "group" (t "vote"), "piece" (a photo piece's clear fields).
     */
    fun check(room: String, p: JSONObject) {
        when (room) {
            "rich", "gmsg" -> {
                if (room == "rich" && !Rich.textOk(p.opt("text"))) throw Refused("text")
                if (p.has("poll")) checkPoll(p.opt("poll"))
                if (p.has("lp")) checkPreview(p.opt("lp"))
            }
            "mx" -> {
                if (p.opt("t") != "vote") throw Refused("t")
                if (!Rich.isMsgId(p.opt("msg"))) throw Refused("msg")
                if (!Rich.isTs(p.opt("ts"))) throw Refused("ts")
                checkVotes(p)
            }
            "group" -> {
                if (p.opt("t") != "vote") throw Refused("t")
                val g = p.opt("g")
                if (g !is String || g.length !in 1..40) throw Refused("g")
                if (!Proto06.isLogicalId(p.opt("id") as? String ?: "")) throw Refused("id")
                if (!Proto06.isLogicalId(p.opt("msg") as? String ?: "")) throw Refused("msg")
                if (!Rich.isTs(p.opt("ts"))) throw Refused("ts")
                checkVotes(p)
            }
            "piece" -> {
                if (p.has("al") || p.has("ai") || p.has("an")) {
                    if (!Rich.isMsgId(p.opt("al"))) throw Refused("al")
                    if (!intIn(p.opt("an"), 1, ALBUM_MAX)) throw Refused("an")
                    if (!intIn(p.opt("ai"), 0, ALBUM_MAX - 1) || (p.opt("ai") as Number).toInt() >= (p.opt("an") as Number).toInt()) throw Refused("ai")
                }
                when (p.optString("kind", "photo")) {
                    "video" -> {
                        if (!intIn(p.opt("ms"), 1, VIDEO_MAX_MS)) throw Refused("ms")
                        if (p.has("al")) throw Refused("video in an album")
                    }
                    "photo", "voice" -> {}
                    // Cap f1 (section 14): a small file over the relays, with its name.
                    "file" -> {
                        val fn = p.opt("fn")
                        if (fn !is String || fn.isBlank() || fn.length > 200 || fn.any { it.code < 0x20 || it == '/' || it == '\\' }) throw Refused("fn")
                        if (p.has("al")) throw Refused("file in an album")
                    }
                    else -> throw Refused("kind")
                }
            }
            else -> throw Refused("room")
        }
    }

    fun ok(room: String, p: JSONObject) = try { check(room, p); true } catch (e: Refused) { false }

    /** A video message is an MP4: its first box is "ftyp". */
    fun isVideo(data: ByteArray) = data.size >= 12 && data[4] == 'f'.code.toByte() && data[5] == 't'.code.toByte() &&
        data[6] == 'y'.code.toByte() && data[7] == 'p'.code.toByte()

    /** What an app without m2 shows for a poll. */
    fun fallback(q: String, options: List<String>): String =
        "Poll: $q\n" + options.mapIndexed { i, o -> "${i + 1}. $o" }.joinToString("\n")

    // ------------------------------------------------------------------ polls

    data class Poll(val q: String, val options: List<String>, val multi: Boolean)

    fun pollOf(p: JSONObject?): Poll? {
        val o = p?.optJSONObject("poll") ?: return null
        val a = o.optJSONArray("a") ?: return null
        return Poll(o.optString("q"), (0 until a.length()).map { a.getString(it) }, o.optBoolean("m"))
    }

    fun pollJson(poll: Poll): JSONObject = JSONObject().put("q", poll.q).put("a", JSONArray(poll.options)).apply { if (poll.multi) put("m", true) }

    /** One vote per person: who to (options, ts). A later ts replaces it ([] takes it back). */
    data class Vote(val v: List<Int>, val ts: Long)

    /** [all] with [from]'s vote if it's newer than what's there and names only options the poll has; else null. */
    fun vote(all: Map<String, Vote>, n: Int, from: String, v: List<Int>, ts: Long): Map<String, Vote>? {
        if (v.any { it !in 0 until n }) return null
        val cur = all[from]
        if (cur != null && ts <= cur.ts) return null
        return all + (from to Vote(v, ts))
    }

    /** How many chose each option. */
    fun counts(all: Map<String, Vote>, n: Int): List<Int> {
        val c = IntArray(n)
        all.values.forEach { it.v.forEach { x -> if (x in 0 until n) c[x]++ } }
        return c.toList()
    }

    fun votesToJson(all: Map<String, Vote>): JSONObject = JSONObject().apply { all.forEach { (k, x) -> put(k, JSONObject().put("v", JSONArray(x.v)).put("t", x.ts)) } }
    fun votesFromJson(o: JSONObject?): Map<String, Vote> {
        if (o == null) return emptyMap()
        return o.keys().asSequence().associateWith { k ->
            o.getJSONObject(k).let { j -> val a = j.optJSONArray("v"); Vote(if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) }, j.optLong("t")) }
        }
    }

    fun voteMsg(msg: String, v: List<Int>, ts: Long): String = JSONObject().put("t", "vote").put("msg", msg).put("v", JSONArray(v.sorted().distinct())).put("ts", ts).toString()

    // ------------------------------------------------------------------ link previews

    data class Preview(val url: String, val title: String, val description: String = "", val thumb: ByteArray? = null)

    fun previewOf(p: JSONObject?): Preview? {
        val lp = p?.optJSONObject("lp") ?: return null
        val i = lp.optString("i")
        return Preview(lp.optString("u"), lp.optString("t"), lp.optString("d"),
            if (i.isEmpty()) null else try { java.util.Base64.getDecoder().decode(i) } catch (e: Exception) { null })
    }

    fun previewJson(lp: Preview): JSONObject = JSONObject().put("u", lp.url).put("t", lp.title).apply {
        if (lp.description.isNotEmpty()) put("d", lp.description)
        lp.thumb?.let { put("i", java.util.Base64.getEncoder().encodeToString(it)) }
    }

    /** The first web link in [text], if any. */
    fun firstLink(text: String): String? = Regex("""https?://[^\s<>"]+""").find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';', ':')
        ?.takeIf { it.length in 8..URL_MAX }

    /** The site a link goes to, for "Open example.com?". */
    fun host(url: String): String = try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }
}
