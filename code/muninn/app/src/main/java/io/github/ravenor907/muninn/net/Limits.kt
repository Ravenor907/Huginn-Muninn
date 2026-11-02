package io.github.ravenor907.muninn.net

import org.json.JSONObject

/**
 * Message limits (cap d1, shared/docs/protocol.md section 16), matching shared/tools/d1_ref.py; shared/vectors/d1-vectors.json checks it.
 * Every chat deletes messages older than its limit: 7 days, or up to 30 for a Family group, and for two people
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

    /** A group's limit: its days, 7 for a Folk group (an old "work" group keeps its days). */
    fun groupDays(s: Proto06.State): Int = if (s.kind.isEmpty()) DEFAULT else if (s.days == 0) DEFAULT else s.days

    /** "Family" for a Family group, else "Folk" (an old "work" group is Folk). */
    fun kindName(kind: String) = typeName(if (kind == FAMILY) FAMILY else FOLK)

    // ---- group types (16.2): Folk and Family. On the wire a Family group has "kind":"family"; a Folk group has no
    // "kind" (an old "work" group is shown and treated as Folk and keeps its days).
    const val FOLK = "folk"
    const val FAMILY = "family"
    val TYPES = listOf(FOLK, FAMILY)

    fun groupType(s: Proto06.State) = if (s.kind == FAMILY) FAMILY else FOLK
    fun typeName(type: String) = if (type == FAMILY) "Family" else "Folk"
    fun typeLine(type: String) = if (type == FAMILY) "Your closest people. Admins add each person by QR or a code, no links. Kept up to 30 days."
        else "Friends and everyday groups. Anyone can invite, by a link or a code. Kept 7 days."
    /** "What's the difference?" */
    fun typeRules(type: String): List<String> = if (type == FAMILY) listOf(
        "Only admins add people, face to face or by a code they hand over", "No invite links",
        "Each new member's safety code is checked once", "Messages stay up to 30 days (admins pick 7 to 30)",
        "Files and photos from members arrive by themselves", "Members are in your Family folder")
        else listOf("Anyone in the group can invite, by a link or a code", "Messages delete after 7 days",
        "Small files arrive by themselves, big ones ask first", "Members aren't added to your contacts")

    /** Family groups have no invite links: only admins add people, by a code or face to face. */
    fun linksAllowed(s: Proto06.State) = groupType(s) != FAMILY
    /** Members (not only admins) may make invite links: a Folk group with "mi". */
    fun membersMayInvite(s: Proto06.State) = linksAllowed(s) && s.mi
    /** A file of [size] from a member arrives by itself: Folk small ones, Family all of them. */
    fun filesArrive(type: String, size: Long, autoBytes: Long) = type == FAMILY || size < autoBytes

    /**
     * The same state as a group of [type]. Family: kind "family", [days] 7 to 30, no "mi". Folk: no kind or days, and
     * "mi" on when [miOk] (everyone's app takes it); an old "work" group already is Folk and stays as it is.
     */
    fun withType(s: Proto06.State, type: String, days: Int, miOk: Boolean): Proto06.State = when {
        type == FAMILY -> withLimit(s.copy(mi = false), FAMILY, days.coerceIn(DEFAULT, LONGEST))
        s.kind == "work" -> s
        else -> withLimit(s, "", DEFAULT).let { if (miOk) it.copy(mi = true) else it }
    }

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
    fun words(days: Int) = if (days == DEFAULT) "Messages delete after 7 days" else "Messages delete after $days days"
}
