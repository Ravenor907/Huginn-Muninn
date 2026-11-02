package io.github.ravenor907.muninn.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a parent set for a Kids' Device's accessibility (cap k14, shared/docs/protocol.md 7.18): Muninn's text size and
 * size of everything (in percent), high contrast, color vision and less motion. Null: not set, the device keeps its own.
 */
data class KidAccess(val text: Int? = null, val size: Int? = null, val hc: Boolean? = null, val cv: String? = null, val still: Boolean? = null) {
    /** All five as one line, to tell whether a parent changed them. */
    val key: String get() = "$text|$size|$hc|$cv|$still"
    val any: Boolean get() = text != null || size != null || hc != null || cv != null || still != null
}

/** A finished voice message ([kind] "voice", [ms] long) or photo ("photo") held for [k] during a quiet time, since [at]. */
data class Held(val id: String, val k: String, val kind: String, val ms: Int, val at: Long)

/** A Kids' Device asks its parents about [k] ([n]), who posts in group [g] ([gn]) but isn't on its list. */
data class HoldAsk(val g: String, val k: String, val n: String, val gn: String)

/** One parent asks another to put Kids' Device [k] ([n]: its kid's name) on their Kids' Device [tv]; [code]: [k]'s contact code. */
data class KidAdd(val tv: String, val k: String, val n: String, val code: String)

/**
 * Cap k14 (7.18) and the kids' screens on a tablet or phone: the accessibility fields, the child's name, the group members
 * a parent approved, other families' Kids' Devices still waiting, pictures on quick messages, and the pure rules the screens
 * follow (the tray's size, when the composer folds its buttons, what a bubble says to TalkBack, what a quiet time holds).
 * Kept free of Android so the unit tests run it.
 */
object KidK14 {
    const val CAP = "k14"
    /** The config fields of k14. Each is optional; absent keeps what the device has. */
    val FIELDS = setOf("tsz", "dsz", "hc", "cv", "still", "child", "gok", "wait")
    /** Settings › Look's text sizes and sizes of everything, in percent. */
    val TEXT_SIZES = listOf(100, 115, 130, 150)
    val DISPLAY_SIZES = listOf(85, 100, 115, 130)
    /** Settings › Look › Color vision's keys. */
    val VISIONS = listOf("typical", "protan", "deutan", "tritan", "mono")
    const val MAX_GOK = 200
    /** An emoji on a quick message: at most this many code points (a flag or a family is several). */
    const val MAX_EMOJI = 8
    private val GID = Regex("[A-Za-z0-9_-]{1,64}")

    // ---- the config fields

    /** A chat key as configs carry it: not empty, at most 200 characters, no spaces or control characters. */
    fun keyOk(k: Any?): Boolean = k is String && k.isNotEmpty() && k.length <= 200 && k.none { it.code <= 0x20 || it.code == 0x7F }

    /** A name ("child"): 1 to 40 characters, not only spaces, no control characters. */
    fun nameOk(n: Any?): Boolean = n is String && n.isNotBlank() && runCatching { Canon.text(n, TvRules.MAX_NAME) }.isSuccess

    /** An emoji for a quick message: 1 to [MAX_EMOJI] code points, no letters, digits, spaces or control characters. */
    fun emojiOk(e: Any?): Boolean = e is String && e.isNotEmpty() && e.codePointCount(0, e.length) <= MAX_EMOJI &&
        e.none { it.isLetterOrDigit() || it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }

    private fun intIn(v: Any?, allowed: List<Int>): Boolean = Canon.isInt(v) && (v as Number).toLong() in allowed.map { it.toLong() }

    /** Throws IllegalArgumentException with the field's name if config [c]'s k14 fields aren't well-formed. */
    fun check(c: JSONObject) {
        if (c.has("tsz")) require(intIn(c.get("tsz"), TEXT_SIZES)) { "tsz" }
        if (c.has("dsz")) require(intIn(c.get("dsz"), DISPLAY_SIZES)) { "dsz" }
        if (c.has("hc")) require(c.get("hc") is Boolean) { "hc" }
        if (c.has("cv")) require((c.get("cv") as? String) in VISIONS) { "cv" }
        if (c.has("still")) require(c.get("still") is Boolean) { "still" }
        if (c.has("child")) require(nameOk(c.get("child"))) { "child" }
        if (c.has("gok")) gokIn(c.get("gok"))
        if (c.has("wait")) waitIn(c.get("wait"))
    }

    /** "gok": the group members a parent approved, each for one group: (group id, chat key), sorted, each once. */
    fun gokIn(v: Any?): List<Pair<String, String>> {
        val a = v as? JSONArray ?: throw IllegalArgumentException("gok")
        require(a.length() <= MAX_GOK) { "gok" }
        val out = (0 until a.length()).map { i ->
            val o = a.get(i) as? JSONObject ?: throw IllegalArgumentException("gok")
            require(Canon.keys(o) == setOf("g", "k")) { "gok" }
            val g = o.get("g")
            val k = o.get("k")
            require(g is String && GID.matches(g) && keyOk(k)) { "gok" }
            g as String to k as String
        }
        require(out == out.distinct().sortedWith(compareBy({ it.first }, { it.second }))) { "gok" }
        return out
    }

    fun gokJson(list: List<Pair<String, String>>): JSONArray = JSONArray().apply {
        list.filter { GID.matches(it.first) && keyOk(it.second) }.distinct().sortedWith(compareBy({ it.first }, { it.second })).take(MAX_GOK)
            .forEach { put(JSONObject().put("g", it.first).put("k", it.second)) }
    }

    /** "wait": the other families' Kids' Devices on the list whose parents haven't added this one yet: chat keys, sorted, each once. */
    fun waitIn(v: Any?): List<String> {
        val a = v as? JSONArray ?: throw IllegalArgumentException("wait")
        require(a.length() <= TvRules.MAX_CONTACTS) { "wait" }
        val out = (0 until a.length()).map { i -> a.get(i).also { require(keyOk(it)) { "wait" } } as String }
        require(out == out.distinct().sorted()) { "wait" }
        return out
    }

    fun waitJson(list: List<String>): JSONArray = JSONArray().apply { list.filter { keyOk(it) }.distinct().sorted().take(TvRules.MAX_CONTACTS).forEach { put(it) } }

    /** The accessibility config [c] sets (each null when absent). */
    fun accessIn(c: JSONObject): KidAccess = KidAccess(
        text = if (c.has("tsz")) (c.get("tsz") as Number).toInt() else null,
        size = if (c.has("dsz")) (c.get("dsz") as Number).toInt() else null,
        hc = if (c.has("hc")) c.getBoolean("hc") else null,
        cv = if (c.has("cv")) c.getString("cv") else null,
        still = if (c.has("still")) c.getBoolean("still") else null,
    )

    /** [base] (read from config [c], which passed [check]) with the k14 values. */
    fun parse(c: JSONObject, base: TvConfig): TvConfig = base.copy(
        access = accessIn(c),
        child = if (c.has("child")) c.getString("child") else "",
        gok = if (c.has("gok")) gokIn(c.get("gok")) else emptyList(),
        wait = if (c.has("wait")) waitIn(c.get("wait")) else emptyList(),
    )

    /** Puts the k14 fields of [e] in config [o] (only those set). */
    fun putInto(o: JSONObject, e: TvExtras) {
        e.access?.let { a ->
            a.text?.takeIf { it in TEXT_SIZES }?.let { o.put("tsz", it) }
            a.size?.takeIf { it in DISPLAY_SIZES }?.let { o.put("dsz", it) }
            a.hc?.let { o.put("hc", it) }
            a.cv?.takeIf { it in VISIONS }?.let { o.put("cv", it) }
            a.still?.let { o.put("still", it) }
        }
        e.child?.let { n -> TvRules.clean(n, TvRules.MAX_NAME).takeIf { it.isNotEmpty() }?.let { o.put("child", it) } }
        e.gok?.let { o.put("gok", gokJson(it)) }
        e.wait?.let { o.put("wait", waitJson(it)) }
    }

    /** [new] with [cur]'s k14 values for each field [new]'s config lacks (a parent app without k14, or one that left it out). */
    fun keep(new: TvConfig, cur: TvConfig): TvConfig {
        val r = new.raw
        val a = new.access
        val b = cur.access
        return new.copy(
            access = KidAccess(
                if (r.has("tsz")) a.text else b.text, if (r.has("dsz")) a.size else b.size, if (r.has("hc")) a.hc else b.hc,
                if (r.has("cv")) a.cv else b.cv, if (r.has("still")) a.still else b.still),
            child = if (r.has("child")) new.child else cur.child,
            gok = if (r.has("gok")) new.gok else cur.gok,
            wait = if (r.has("wait")) new.wait else cur.wait,
        )
    }

    /** True when config [c] lacks a k14 field, so the device keeps its values beside it. */
    fun needsKept(c: JSONObject): Boolean = FIELDS.any { !c.has(it) }

    /** Puts the k14 values of [c] in [o] (only those set). */
    fun keptInto(o: JSONObject, c: TvConfig): JSONObject = o.apply {
        c.access.text?.let { put("tsz", it) }
        c.access.size?.let { put("dsz", it) }
        c.access.hc?.let { put("hc", it) }
        c.access.cv?.let { put("cv", it) }
        c.access.still?.let { put("still", it) }
        if (c.child.isNotEmpty()) put("child", c.child)
        put("gok", gokJson(c.gok))
        put("wait", waitJson(c.wait))
    }

    /** [cfg] with the k14 values saved in [saved] for the fields its own config lacks. A damaged value is skipped. */
    fun keptFrom(cfg: TvConfig, saved: JSONObject): TvConfig {
        val r = cfg.raw
        var c = cfg
        var a = c.access
        if (!r.has("tsz")) saved.opt("tsz").takeIf { intIn(it, TEXT_SIZES) }?.let { a = a.copy(text = (it as Number).toInt()) }
        if (!r.has("dsz")) saved.opt("dsz").takeIf { intIn(it, DISPLAY_SIZES) }?.let { a = a.copy(size = (it as Number).toInt()) }
        if (!r.has("hc")) (saved.opt("hc") as? Boolean)?.let { a = a.copy(hc = it) }
        if (!r.has("cv")) (saved.opt("cv") as? String)?.takeIf { it in VISIONS }?.let { a = a.copy(cv = it) }
        if (!r.has("still")) (saved.opt("still") as? Boolean)?.let { a = a.copy(still = it) }
        c = c.copy(access = a)
        if (!r.has("child")) (saved.opt("child") as? String)?.takeIf { nameOk(it) }?.let { c = c.copy(child = it) }
        if (!r.has("gok") && saved.has("gok")) runCatching { gokIn(saved.get("gok")) }.getOrNull()?.let { c = c.copy(gok = it) }
        if (!r.has("wait") && saved.has("wait")) runCatching { waitIn(saved.get("wait")) }.getOrNull()?.let { c = c.copy(wait = it) }
        return c
    }

    /**
     * What a device puts on for [a]: everything set when it's new to the device ([taken]: the key of the last one put on) or
     * the kid may not pick their own ([lookPick] false). Null: keep what shows, the kid's own text size included.
     */
    fun accessToApply(a: KidAccess, taken: String, lookPick: Boolean): KidAccess? {
        if (!a.any) return null
        return if (!lookPick || a.key != taken) a else null
    }

    /** "Hi, Ava" with the child's name a parent set; "Hi!" without one. Never the device's name. */
    fun greeting(child: String): String = KidUi.greeting(child)

    // ---- pictures on quick messages

    /**
     * What a quick message's chip shows: (picture, words). The emoji a parent picked ([Quick.e]); else an emoji the text
     * starts with ("🍎 I'm hungry"); else none. The words never repeat the picture.
     */
    fun picture(q: Quick): Pair<String, String> {
        val t = q.text.trim()
        val first = t.substringBefore(' ')
        // An emoji, then a space, then words.
        val lead = if (first.length < t.length && emojiOk(first)) first else ""
        val pic = if (emojiOk(q.e)) q.e else lead
        val words = if (lead.isNotEmpty()) t.removePrefix(lead).trim() else t
        return pic to words.ifEmpty { t }
    }

    /** What reading a quick message out loud says: its words, without the picture. */
    fun spoken(q: Quick): String = picture(q).second

    /** Quick messages a kid sends: at most one every [CHIP_GAP_MS], whichever chip ([last] 0: none yet). */
    const val CHIP_GAP_MS = 1_500L
    fun chipOk(last: Long, now: Long): Boolean = last <= 0L || now - last !in 0L until CHIP_GAP_MS

    // ---- the layout on a tablet or phone (sizes in dp)

    const val TOUCH = 56f
    const val MIN_TRAY = 120f
    const val TRAY_SHARE = 0.4f
    const val MIN_LIST = 120f
    /** The composer row's padding, the box's margin and its inner padding, beside the 56 dp buttons. */
    const val ROW_PAD = 16f
    const val FIELD_MARGIN = 12f
    const val FIELD_PAD = 36f
    /** Less room than this to type in folds the composer's buttons into "+". */
    const val MIN_TYPING = 160f

    /**
     * The quick messages tray's tallest, in a conversation [h] tall: 40% of it, at least [MIN_TRAY]; never more than leaves
     * [MIN_LIST] for the messages, and never more than half on a screen too short for both. It scrolls inside that.
     */
    fun trayMax(h: Float): Float {
        val want = maxOf(MIN_TRAY, h * TRAY_SHARE)
        val room = maxOf(h - MIN_LIST, h * 0.5f)
        return minOf(want, room).coerceAtLeast(0f)
    }

    /** A phone held sideways ([w] wider than [h]; not a tablet): the tray is one scrolling row of chips. */
    fun trayOneRow(w: Float, h: Float, tablet: Boolean): Boolean = !tablet && w > h

    /** Whether the tray shows: always at Quick messages only; with typing, when the kid opened it and isn't typing. */
    fun trayShown(quickOnly: Boolean, opened: Boolean, typing: Boolean): Boolean = quickOnly || (opened && !typing)

    /** The room to type in, in a composer [w] wide with [icons] round buttons left of the box (Send is on its right). */
    fun typingWidth(w: Float, icons: Int): Float = w - ROW_PAD - TOUCH * (icons + 1) - FIELD_MARGIN - FIELD_PAD

    /** The smile and photo fold into one "+" when there are two or more and the room to type would be under [MIN_TYPING]. */
    fun collapse(w: Float, icons: Int): Boolean = icons >= 2 && typingWidth(w, icons) < MIN_TYPING

    /**
     * The conversation's Call button is only its icon at a large text size ([fontScale], the kids' boost included, 1.5 or
     * more) or when the name would get under 96 dp beside the whole button. [back]: a Back button is on the left (a phone).
     */
    fun callIconOnly(w: Float, fontScale: Float, back: Boolean): Boolean {
        if (fontScale >= 1.5f) return true
        val pill = 66f + 40f * fontScale
        val name = w - (if (back) TOUCH else 12f) - 50f - 24f - 14f - pill
        return name < 96f
    }

    // ---- what a bubble says, and how a sent message is doing

    /** "4:05", a voice message's or video's length. */
    fun length(ms: Int): String { val s = (ms + 500) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    /**
     * What TalkBack says for a bubble: "Mom: I'm on my way, 4:12 PM", "You: Photo, 4:12 PM", "Dad: Voice message 0:12, 4:12 PM".
     * [who]: their name ("You" for [mine]). A group's text that starts with the name doesn't say it twice.
     */
    fun bubbleLabel(who: String, mine: Boolean, text: String, voiceMs: Int, videoMs: Int, photo: Boolean, gif: Boolean,
                    file: Boolean, poll: String?, time: String): String {
        val name = if (mine) "You" else who.ifBlank { "Someone" }
        val what = when {
            voiceMs > 0 -> "Voice message ${length(voiceMs)}"
            videoMs > 0 -> "Video ${length(videoMs)}"
            poll != null -> "Poll: $poll"
            file -> "File"
            gif -> "GIF"
            photo -> "Photo"
            else -> text.removePrefix("$name: ").replace('\n', ' ')
        }
        return if (time.isEmpty()) "$name: $what" else "$name: $what, $time"
    }

    /**
     * A sent message's state as the kids read it: "Sent" only once a relay or a direct route took it ([state] "sent",
     * "delivered" or "read"); "Sending" until then; "Waiting" once it's been a minute without ([failed]), or while the
     * other family's parent hasn't added this device yet ([waiting]). "" for someone else's message.
     */
    fun sendState(mine: Boolean, state: String, failed: Boolean, waiting: Boolean): String = when {
        !mine || state.isEmpty() -> ""
        waiting -> "Waiting"
        state == "sent" || state == "delivered" || state == "read" -> "Sent"
        failed -> "Waiting"
        else -> "Sending"
    }

    // ---- groups: members no parent approved are held

    /**
     * Whether a Kids' Device holds [from]'s messages in group [g]: anyone who isn't the device itself ([me]), a parent, on
     * its list ([contacts]) or approved by a parent for that group ([gok]). Held messages aren't shown, and the parents are asked.
     */
    fun held(from: String, g: String, me: String, parents: Collection<String>, contacts: Collection<String>, gok: List<Pair<String, String>>): Boolean =
        from != me && from !in parents && from !in contacts && (g to from) !in gok

    /** Device to parents (room "tvconfig"): someone in a group isn't on its list. */
    fun holdMessage(a: HoldAsk): String =
        JSONObject().put("t", "ghold").put("g", a.g).put("k", a.k).put("n", TvRules.clean(a.n, TvRules.MAX_NAME).ifEmpty { "Someone" })
            .put("gn", TvRules.clean(a.gn, TvRules.MAX_NAME).ifEmpty { "Group" }).toString()

    /** A well-formed "ghold" (exactly t, g, k, n, gn), else null. */
    fun holdIn(o: JSONObject): HoldAsk? {
        if (o.opt("t") != "ghold" || Canon.keys(o) != setOf("t", "g", "k", "n", "gn")) return null
        val g = o.opt("g") as? String ?: return null
        val k = o.opt("k")
        if (!GID.matches(g) || !keyOk(k) || !nameOk(o.opt("n")) || !nameOk(o.opt("gn"))) return null
        return HoldAsk(g, k as String, o.getString("n"), o.getString("gn"))
    }

    /** "Approve Sam in Soccer for Ava?" */
    fun approveText(n: String, gn: String, child: String): String = "Approve $n in $gn for $child?"

    /** What the kid reads in a group where some messages are held. */
    const val HELD_LINE = "Some messages are waiting for a parent"

    // ---- another family's Kids' Device

    /** Parent to parent (room "tvconfig"): put Kids' Device [KidAdd.k] on your Kids' Device [KidAdd.tv]. */
    fun kidAddMessage(a: KidAdd): String = JSONObject().put("t", "kidadd").put("tv", a.tv).put("k", a.k)
        .put("n", TvRules.clean(a.n, TvRules.MAX_NAME).ifEmpty { "A Kids' Device" }).put("code", a.code).toString()

    /** A well-formed "kidadd" (exactly t, tv, k, n, code; code at most 2000 characters), else null. */
    fun kidAddIn(o: JSONObject): KidAdd? {
        if (o.opt("t") != "kidadd" || Canon.keys(o) != setOf("t", "tv", "k", "n", "code")) return null
        val tv = o.opt("tv"); val k = o.opt("k"); val code = o.opt("code") as? String
        if (!keyOk(tv) || !keyOk(k) || tv == k || !nameOk(o.opt("n")) || code == null || code.isEmpty() || code.length > 2000) return null
        return KidAdd(tv as String, k as String, o.getString("n"), code)
    }

    /** Parent to parent: my Kids' Device [tv] has your Kids' Device [k] on its list now. */
    fun kidOkMessage(tv: String, k: String): String = JSONObject().put("t", "kidok").put("tv", tv).put("k", k).toString()

    /** A well-formed "kidok" (exactly t, tv, k): (tv, k), else null. */
    fun kidOkIn(o: JSONObject): Pair<String, String>? {
        if (o.opt("t") != "kidok" || Canon.keys(o) != setOf("t", "tv", "k")) return null
        val tv = o.opt("tv"); val k = o.opt("k")
        if (!keyOk(tv) || !keyOk(k)) return null
        return tv as String to k as String
    }

    /** "Waiting for Leo's parent to add Ava" on the parent's page. */
    fun waitingText(other: String, child: String): String = "Waiting for $other's parent to add $child"

    /** What the kid reads in a conversation with another family's Kids' Device still waiting. */
    const val WAIT_LINE = "Waiting for a parent to say yes"

    // ---- a quiet time holds a finished voice message or photo; a draft stays in the box

    const val MAX_HELD = 10

    /** Whether something for [k] waits now: a quiet time is on and [k] isn't a parent. */
    fun holds(k: String, parents: Collection<String>, quiet: QuietTime?): Boolean = !KidRules.passes(k, parents, quiet)

    fun addHeld(list: List<Held>, h: Held): List<Held> = (list.filter { it.id != h.id } + h).takeLast(MAX_HELD)

    fun cancelHeld(list: List<Held>, id: String): List<Held> = list.filter { it.id != id }

    /**
     * What the held list does at a quiet time [quiet] (null: none on): (send now, keep waiting). Something for someone no
     * longer on the list ([onList] false) is dropped.
     */
    fun release(list: List<Held>, parents: Collection<String>, quiet: QuietTime?, onList: (String) -> Boolean): Pair<List<Held>, List<Held>> {
        val live = list.filter { onList(it.k) }
        return live.filter { !holds(it.k, parents, quiet) } to live.filter { holds(it.k, parents, quiet) }
    }

    /** "Will send at 7:00 AM": when the quiet time [q] ends, on the device's clock ([h24]). */
    fun willSend(q: QuietTime, h24: Boolean): String = "Will send at ${KidRules.clock(q.e, h24)}"

    fun heldJson(list: List<Held>): JSONArray = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("k", it.k).put("kind", it.kind).put("ms", it.ms).put("at", it.at)) }
    }

    /** The held list saved by [heldJson]; a damaged entry is skipped. */
    fun heldIn(a: JSONArray?): List<Held> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        try {
            val o = a.getJSONObject(i)
            val kind = o.getString("kind")
            if (kind != "voice" && kind != "photo") null
            else Held(o.getString("id").takeIf { Regex("[0-9a-f]{32}").matches(it) } ?: return@mapNotNull null,
                o.getString("k").takeIf { keyOk(it) } ?: return@mapNotNull null, kind, o.optInt("ms").coerceAtLeast(0), o.optLong("at"))
        } catch (_: Exception) { null }
    }.takeLast(MAX_HELD)
}
