package io.github.ravenor907.muninn.net

import org.json.JSONObject

/**
 * Message limits (cap d1, shared/docs/protocol.md section 16), matching shared/tools/d1_ref.py; shared/vectors/d1-vectors.json checks it.
 * Every chat deletes messages older than its limit: 7 days, or up to 30 for a Family or Work group, and for two people
 * who keep each other in their Family or Work folder (the shorter of their two choices wins). No Android in here.
 */
object Limits {
    const val CAP = "d1"
    const val DEFAULT = 7
    const val LONGEST = 30
    const val DAY = ChatWire.DAY
    val KINDS = listOf("family", "work")
    /** The choices offered on screen. */
    val CHOICES = listOf(7, 14, 30)
    /** The folders whose people may keep messages longer (the starting folders of the same names). */
    val CLOSE_FOLDERS = setOf("Family", "Work")

    /** A group's limit: its days, 7 for a Standard group. */
    fun groupDays(s: Proto06.State): Int = if (s.kind.isEmpty()) DEFAULT else if (s.days == 0) DEFAULT else s.days

    /** "Standard", "Family" or "Work". */
    fun kindName(kind: String) = when (kind) { "family" -> "Family"; "work" -> "Work"; else -> "Standard" }

    /** The limit an envelope says its sender keeps, or null when it says nothing usable (the old value stays). */
    fun envelopeDays(o: JSONObject): Int? = when (val d = o.opt("days")) {
        is Int -> d.takeIf { it in DEFAULT..LONGEST }
        is Long -> d.takeIf { it in DEFAULT..LONGEST }?.toInt()
        else -> null
    }

    /** What I keep a chat for: my choice only for someone in my Family or Work folder, else 7. */
    fun myDays(chosen: Int?, close: Boolean): Int = if (!close || chosen == null) DEFAULT else chosen.coerceIn(DEFAULT, LONGEST)

    /** A 1:1 chat's limit: the shorter of the two; an app without d1 doesn't count (it keeps everything until it updates). */
    fun pairDays(mine: Int, hasD1: Boolean, theirs: Int?): Int = if (!hasD1) mine else minOf(mine, theirs ?: DEFAULT)

    /** The oldest time that stays: anything before it goes. A message exactly at the limit stays. */
    fun cutoff(now: Long, days: Int): Long = now - days * DAY

    /** The same group state as a Family, Work or Standard group with [days] (0 or 7: none written). */
    fun withLimit(s: Proto06.State, kind: String, days: Int): Proto06.State =
        if (kind.isEmpty()) s.copy(kind = "", days = 0) else s.copy(kind = kind, days = if (days <= DEFAULT) 0 else days.coerceAtMost(LONGEST))

    /** How the limit reads on screen. */
    fun words(days: Int) = if (days == DEFAULT) "Messages disappear after 7 days" else "Messages disappear after $days days"
}
