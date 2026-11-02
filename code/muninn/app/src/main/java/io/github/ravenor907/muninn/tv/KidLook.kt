package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.ui.Accents
import io.github.ravenor907.muninn.ui.Looks
import io.github.ravenor907.muninn.ui.Seasons
import org.json.JSONObject

/** A Kids' Device's colors: the look, the Seasonal choice and the accent, the same choices as Settings › Look. */
data class KidTheme(val look: String, val season: String, val accent: String) {
    /** The three as one line, to tell whether a parent changed them. */
    val key: String get() = "$look|$season|$accent"
}

/**
 * The Kids' Device look (cap k13, shared/docs/protocol.md 7.18): the colors a parent picks for the device, whether the
 * kid may pick their own, and the kid's name for the greeting. Each field is optional; absent keeps what the device has.
 * Kept free of Android so the unit tests run it.
 */
object KidLook {
    const val CAP = "k13"
    val FIELDS = setOf("look", "season", "accent", "lookpick", "kname")
    private val WORD = Regex("[a-z]{1,16}")

    /** The usual colors: Dark, Seasonal off, gold. */
    val DEFAULT = KidTheme(Looks.DARK, Seasons.OFF, Accents.DEFAULT)

    /** Throws IllegalArgumentException with the field's name if config [c]'s k13 fields aren't well-formed. */
    fun check(c: JSONObject) {
        // A look or season this app doesn't know is still well-formed: it shows as Dark or Seasonal off.
        if (c.has("look")) require((c.get("look") as? String)?.let { WORD.matches(it) } == true) { "look" }
        if (c.has("season")) require((c.get("season") as? String)?.let { WORD.matches(it) } == true) { "season" }
        if (c.has("accent")) require((c.get("accent") as? String)?.let { it in Accents.NAMES || Regex("#[0-9A-F]{6}").matches(it) } == true) { "accent" }
        if (c.has("lookpick")) require(c.get("lookpick") is Boolean) { "lookpick" }
        if (c.has("kname")) {
            val n = c.get("kname")
            require(n is String && n.isNotBlank() && runCatching { Canon.text(n, TvRules.MAX_NAME) }.isSuccess) { "kname" }
        }
    }

    /** The accent as it travels: a name, or "#RRGGBB" in capitals. */
    fun accentOut(v: String): String = Accents.choice(v)

    /** True when config [c] carries any k13 field. */
    fun has(c: JSONObject): Boolean = FIELDS.any { c.has(it) }

    /** The colors a parent set in [c], or null when it carries none. */
    fun parentTheme(c: TvConfig): KidTheme? = c.look?.let { KidTheme(Looks.of(it), Seasons.choice(c.season), Accents.choice(c.accent)) }

    /**
     * What a device puts on when its config is [c]: the parent's colors when they are new to it ([taken]: the key of the
     * last ones it put on) or the kid may not pick their own (then always, whatever [showing] is). Null: keep what shows,
     * the kid's own choice included.
     */
    fun toApply(c: TvConfig, taken: String, showing: KidTheme): KidTheme? {
        val p = parentTheme(c) ?: return null
        if (!c.lookPick) return if (p != showing) p else null
        return if (p.key != taken) p else null
    }

    /** Whether the kid's "My colors" shows: unless a parent turned Let the kid choose off. */
    fun kidMayPick(c: TvConfig): Boolean = c.lookPick

    /** [new] with the k13 values of [cur] for each field [new]'s config lacks (an older parent app, or one without k13). */
    fun keep(new: TvConfig, cur: TvConfig): TvConfig {
        val r = new.raw
        return new.copy(
            look = if (r.has("look")) new.look else cur.look,
            season = if (r.has("season")) new.season else cur.season,
            accent = if (r.has("accent")) new.accent else cur.accent,
            lookPick = if (r.has("lookpick")) new.lookPick else cur.lookPick,
            kname = if (r.has("kname")) new.kname else cur.kname,
        )
    }

    /** True when config [c] lacks a k13 field, so the device keeps its values beside it. */
    fun needsKept(c: JSONObject): Boolean = FIELDS.any { !c.has(it) }

    /** Puts the k13 values of [c] in [o] (only those set). */
    fun keptInto(o: JSONObject, c: TvConfig): JSONObject = o.apply {
        c.look?.let { put("look", it) }
        c.season?.let { put("season", it) }
        c.accent?.let { put("accent", it) }
        put("lookpick", c.lookPick)
        if (c.kname.isNotEmpty()) put("kname", c.kname)
    }

    /** [cfg] with the k13 values saved in [saved] for the fields its own config lacks. A damaged value is skipped. */
    fun keptFrom(cfg: TvConfig, saved: JSONObject): TvConfig {
        val r = cfg.raw
        var c = cfg
        fun word(k: String) = (saved.opt(k) as? String)?.takeIf { WORD.matches(it) }
        if (!r.has("look")) word("look")?.let { c = c.copy(look = it) }
        if (!r.has("season")) word("season")?.let { c = c.copy(season = it) }
        if (!r.has("accent")) (saved.opt("accent") as? String)?.takeIf { it in Accents.NAMES || Regex("#[0-9A-F]{6}").matches(it) }?.let { c = c.copy(accent = it) }
        if (!r.has("lookpick")) (saved.opt("lookpick") as? Boolean)?.let { c = c.copy(lookPick = it) }
        if (!r.has("kname")) (saved.opt("kname") as? String)?.takeIf { it.isNotBlank() && runCatching { Canon.text(it, TvRules.MAX_NAME) }.isSuccess }
            ?.let { c = c.copy(kname = it) }
        return c
    }
}

/**
 * What the kids' screens on a tablet or phone offer: the bottom tabs, the filter chips, the composer for each chatting
 * level and the greeting. Kept free of Android so the unit tests check it.
 */
object KidUi {
    /** The bottom tabs: (id, label). Nothing to add, scan or share: a parent sets everything from their phone. */
    val TABS = listOf("chats" to "Chats", "call" to "Call", "people" to "People")

    /** The filter chips over the chat list: (id, label). People: everyone on the list; Groups: the groups a parent put it in. */
    val FILTERS = listOf("all" to "All", "family" to "People", "groups" to "Groups")

    /** The buttons on the greeting card: My colors (unless a parent locked it) and For a parent. */
    val CARD_ACTIONS = listOf("colors" to "My colors", "parent" to "For a parent")

    /** Words that would mean adding someone or reaching outside the list. None of them may be offered. */
    val NEVER = listOf("add", "scan", "qr", "code", "invite", "share", "nearby", "new", "pair", "settings")

    /** Kids' text is this much bigger than the usual, on top of Settings › Look's text size and Android's font size. */
    const val TEXT_BOOST = 1.1f

    /**
     * The composer at a chatting [level] on a device with or without a microphone ([mic]): what it shows, and whether
     * the quick messages tray is open by itself with big chips (Quick messages only, where it is the composer).
     */
    data class Compose(val text: Boolean, val photo: Boolean, val voice: Boolean, val trayOpen: Boolean, val bigChips: Boolean)

    fun compose(level: Int, mic: Boolean): Compose {
        val text = KidRules.canSend(level, "text")
        return Compose(text = text, photo = KidRules.canSend(level, "photo"), voice = mic && KidRules.canSend(level, "voice"),
            trayOpen = !text, bigChips = !text)
    }

    /** Whether filter chip [chip] keeps conversation [k] (a group's key starts "g:"). */
    fun keeps(chip: String, k: String): Boolean = when (chip) {
        "family" -> !k.startsWith("g:")
        "groups" -> k.startsWith("g:")
        else -> true
    }

    /**
     * The chat list's order, the same as Muninn's: unread ones first, newest first, then the rest newest first ([last]:
     * a conversation's newest message time). Conversations with nothing yet keep [keys]' order (parents first, by name).
     */
    fun order(keys: List<String>, unread: Set<String>, last: (String) -> Long): List<String> =
        io.github.ravenor907.muninn.net.ChatOrder.unreadUp(keys.sortedByDescending { last(it) }, emptyList(), unread, last)

    private val MADE_UP = Regex("""Raven \d{4}""")
    private val DEVICE_NAMES = setOf("Kids' Tablet", "Kids' Phone", "Kids' TV", "Kids' Device")

    /**
     * "Hi, Ava" with the child's name a parent set (k14 "child"); "Hi!" without one. Never the device's name: a name that is
     * one of the names a device starts with, or a made-up "Raven 1234", greets as "Hi!" too.
     */
    fun greeting(child: String): String {
        val n = child.trim()
        return if (n.isEmpty() || n in DEVICE_NAMES || MADE_UP.matches(n)) "Hi!" else "Hi, $n"
    }
}
