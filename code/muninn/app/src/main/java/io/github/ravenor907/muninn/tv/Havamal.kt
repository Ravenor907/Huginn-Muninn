package io.github.ravenor907.muninn.tv

import org.json.JSONObject
import java.security.MessageDigest

/** One verse of the Hávamál: its stanza number [n], the [verse] (lines separated by newlines) and [means], for a child. */
data class HavVerse(val n: Int, val verse: String, val means: String)

/** One part of a translation on its way from a parent's phone to a Kids' Device. */
data class HavPart(val h: String, val i: Int, val of: Int, val d: String)

/**
 * Daily Hávamál on a Kids' Device (cap k12, shared/docs/protocol.md 7.18): one verse a day by the device's local date,
 * the same verse all day, cycling through the list in assets/havamal.json (Henry Adams Bellows' translation, 1923,
 * public domain; built from code/shared/havamal.json, the one file Huginn builds in too). A parent switches it on per device and picks the wording: Bellows, or a translation they imported
 * on their phone, which only their own Kids' Devices get. Kept free of Android so the unit tests run it.
 */
object Havamal {
    const val CAP = "k12"
    const val ASSET = "havamal.json"

    /** The wordings: Bellows (built in), or the parent's own imported translation. */
    const val BELLOWS = "b"
    const val MINE = "y"
    val WORDINGS = listOf(BELLOWS, MINE)
    const val BELLOWS_NAME = "Bellows (1923)"
    const val MINE_NAME = "Your translation"

    /** How many verses "Earlier verses" lists at most. */
    const val EARLIER = 30

    /** An imported translation: at most this many stanzas, each at most [MAX_STANZA] characters. */
    const val MAX_STANZAS = 200
    const val MAX_STANZA = 1500
    /** The most a picked file may be, before reading it. */
    const val MAX_FILE = 2L * 1024 * 1024
    /** A translation travels in parts of at most [PART] characters, at most [MAX_PARTS] of them. */
    const val PART = 8000
    const val MAX_PARTS = 40

    private val HEX64 = Regex("[0-9a-f]{64}")

    // ---- the verses

    /** Reads assets/havamal.json: {"translation", "note", "verses": [{"n", "verse", "means"}]}. Throws if it isn't well-formed. */
    fun parse(json: String): List<HavVerse> {
        val a = JSONObject(json).getJSONArray("verses")
        val out = (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            HavVerse(o.getInt("n"), o.getString("verse").trim(), o.getString("means").trim())
        }
        require(out.isNotEmpty() && out.all { it.n > 0 && it.verse.isNotEmpty() && it.means.isNotEmpty() }) { "verses" }
        require(out.map { it.n }.distinct().size == out.size) { "verses" }
        return out
    }

    /** Which verse shows on [day] (days since 1970-01-01 by the local date): the same all day, cycling through [size]. */
    fun index(day: Long, size: Int): Int = if (size <= 0) 0 else Math.floorMod(day, size.toLong()).toInt()

    /**
     * The verses shown before today ([today]), newest first: from the day Daily Hávamál came on ([start]), at most
     * [EARLIER], today's own left out.
     */
    fun earlier(today: Long, start: Long, size: Int, max: Int = EARLIER): List<Int> {
        if (size <= 0) return emptyList()
        val now = index(today, size)
        val out = LinkedHashSet<Int>()
        var d = today - 1
        while (d >= start && d >= today - max && out.size < max) { index(d, size).takeIf { it != now }?.let { out.add(it) }; d-- }
        return out.toList()
    }

    /** The text a child sees for [v]: the parent's translation of that stanza when the wording is theirs and it has one. */
    fun wordingOf(v: HavVerse, wording: String, mine: Map<Int, String>?): String =
        if (wording == MINE) mine?.get(v.n)?.takeIf { it.isNotBlank() } ?: v.verse else v.verse

    // ---- the config fields (7.18, cap k12)

    /** True when config [c] carries Daily Hávamál's switch. Absent: the device keeps its own. */
    fun hasOn(c: JSONObject): Boolean = c.has("hav")

    /** True when config [c] carries the wording (and with it the translation's hash). Absent: the device keeps both. */
    fun hasWording(c: JSONObject): Boolean = c.has("havw")

    /** Throws IllegalArgumentException with the field's name if config [c]'s k12 fields aren't well-formed. */
    fun check(c: JSONObject) {
        if (c.has("hav")) require(c.get("hav") is Boolean) { "hav" }
        if (c.has("havw")) require((c.get("havw") as? String)?.let { it in WORDINGS } == true) { "havw" }
        if (c.has("havh")) { val h = c.get("havh"); require(h is String && HEX64.matches(h) && c.opt("havw") == MINE) { "havh" } }
    }

    // ---- an imported translation

    /**
     * The stanzas of a translation in plain text, by stanza number: a number at the start of a line ("1", "1.", "1)",
     * "St. 1", "Stanza 1"), the stanza's text after it on that line or on the lines below, until the next number.
     * Numbers must go up, so a number inside a verse doesn't start a stanza. Only the numbers in [wanted] are kept.
     */
    fun parseImport(text: String, wanted: Set<Int>): Map<Int, String> {
        val mark = Regex("""^\s*(?:(?:st|stanza|str|strophe)\.?\s*)?(\d{1,3})\s*[.):]?(?:\s+(.*))?$""", RegexOption.IGNORE_CASE)
        val out = LinkedHashMap<Int, String>()
        var cur = -1
        var last = 0
        val lines = ArrayList<String>()
        fun close() {
            if (cur in wanted) {
                val t = lines.joinToString("\n").trim().replace(Regex("\n{2,}"), "\n")
                if (t.isNotEmpty() && cur !in out) out[cur] = t.take(MAX_STANZA)
            }
            lines.clear()
        }
        for (raw in text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = raw.filter { it.code >= 0x20 && it.code != 0x7F || it == '\t' }.replace('\t', ' ').trimEnd()
            val m = mark.find(line)
            val n = m?.groupValues?.get(1)?.toIntOrNull()
            if (n != null && n > last && n <= 999) {
                close()
                cur = n
                last = n
                m!!.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { lines.add(it) }
            } else if (cur > 0) lines.add(line.trim())
        }
        close()
        return out.entries.sortedBy { it.key }.take(MAX_STANZAS).associate { it.key to it.value }
    }

    /** A translation as it travels and is kept: {"1": "text", ...}. */
    fun textJson(stanzas: Map<Int, String>): String =
        JSONObject().apply { stanzas.entries.sortedBy { it.key }.forEach { (n, t) -> put(n.toString(), t) } }.toString()

    /** Reads [textJson]'s form back. Null if it isn't well-formed. */
    fun textIn(json: String): Map<Int, String>? = try {
        val o = JSONObject(json)
        val keys = o.keys().asSequence().toList()
        if (keys.size > MAX_STANZAS) null
        else keys.associate { k ->
            val n = k.toInt()
            require(n in 1..999 && k == n.toString())
            val t = o.get(k) as? String ?: throw IllegalArgumentException("text")
            require(t.length <= MAX_STANZA)
            n to t
        }
    } catch (e: Exception) { null }

    /** Lowercase hex SHA-256 of [s] as UTF-8: which translation a config names ("havh"). */
    fun hash(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Parent to device, room "tvconfig": the translation [json] in parts, each {"t":"havtext","h","i","of","d"}. */
    fun partMessages(json: String): List<String> {
        val h = hash(json)
        // Never split between the two halves of a character outside the BMP (an emoji), so each part is valid text.
        val parts = ArrayList<String>()
        var at = 0
        while (at < json.length) {
            var end = minOf(at + PART, json.length)
            if (end < json.length && Character.isHighSurrogate(json[end - 1])) end--
            parts.add(json.substring(at, end))
            at = end
        }
        return parts.mapIndexed { i, d -> JSONObject().put("t", "havtext").put("h", h).put("i", i).put("of", parts.size).put("d", d).toString() }
    }

    /** A well-formed "havtext" (exactly t, h, i, of, d), else null. */
    fun partIn(o: JSONObject): HavPart? {
        if (o.opt("t") != "havtext" || Canon.keys(o) != setOf("t", "h", "i", "of", "d")) return null
        val h = o.opt("h") as? String ?: return null
        val i = o.opt("i"); val of = o.opt("of"); val d = o.opt("d") as? String ?: return null
        if (!HEX64.matches(h) || !Canon.isInt(i) || !Canon.isInt(of)) return null
        val ii = (i as Number).toInt(); val nn = (of as Number).toInt()
        if (nn !in 1..MAX_PARTS || ii !in 0 until nn || d.length > PART) return null
        return HavPart(h, ii, nn, d)
    }

    /** Puts a translation back together from its parts, one translation at a time (a new one drops the last). */
    class Assembler {
        private var h = ""
        private var parts = arrayOfNulls<String>(0)

        /** Adds [p]; the whole translation once every part is in and its hash matches, else null. */
        fun add(p: HavPart): String? {
            if (p.h != h || parts.size != p.of) { h = p.h; parts = arrayOfNulls(p.of) }
            parts[p.i] = p.d
            if (parts.any { it == null }) return null
            val json = parts.joinToString("")
            h = ""; parts = arrayOfNulls(0)
            return json.takeIf { hash(it) == p.h && textIn(it) != null }
        }
    }

    // ---- what a device keeps beside its signed config (KidRules.keptJson)

    /** Puts the k12 values of [c] in [o]. */
    fun keptInto(o: JSONObject, c: TvConfig): JSONObject =
        o.put("hav", c.hav).put("havw", c.havw).apply { c.havh?.let { put("havh", it) } }

    /** [cfg] with the k12 values saved in [saved] for the fields its own config lacks. */
    fun keptFrom(cfg: TvConfig, saved: JSONObject): TvConfig {
        var c = cfg
        if (!hasOn(cfg.raw) && saved.opt("hav") is Boolean) c = c.copy(hav = saved.getBoolean("hav"))
        if (!hasWording(cfg.raw)) {
            val w = saved.opt("havw") as? String
            if (w != null && w in WORDINGS) {
                val h = (saved.opt("havh") as? String)?.takeIf { w == MINE && HEX64.matches(it) }
                c = c.copy(havw = w, havh = h)
            }
        }
        return c
    }

    /** The [n]s of [verses], for an import to match against. */
    fun numbers(verses: List<HavVerse>): Set<Int> = verses.map { it.n }.toSet()

    /** "Imported: 42 of 60 verses matched". */
    fun matchedLine(matched: Int, of: Int): String = "Imported: $matched of $of verses matched"

    /** For the "Earlier verses" list and tests: the verses at [indexes]. */
    fun at(verses: List<HavVerse>, indexes: List<Int>): List<HavVerse> = indexes.mapNotNull { verses.getOrNull(it) }
}
