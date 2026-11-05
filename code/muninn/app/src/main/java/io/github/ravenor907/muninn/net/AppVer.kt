package io.github.ravenor907.muninn.net

import io.github.ravenor907.muninn.update.Versions

/**
 * Someone's app version (1.45): "av" in an empty receipt (a caps probe, its answer and key announcements), inside the
 * sealed payload, so only their own app can say it. An older app ignores the field. Kept per contact, never for
 * anyone else. Huginn builds and reads the same.
 *
 * Form: "Muninn 1.45 · Android TV", "Muninn · Android phone/tablet" (version unknown), "Huginn 1.45 · Windows/Linux".
 */
object AppVer {
    const val FIELD = "av"
    const val MAX = 48
    private const val DOT = " · "
    private val FORM = Regex("""^(Muninn|Huginn)(?: (\d+\.\d+(?: (?:beta|Test) \d+)?))?$""")
    private val PLATFORMS = setOf("Android TV", "Android phone/tablet", "Windows/Linux", "Windows", "Linux")

    fun make(app: String, version: String, platform: String): String {
        val v = version.trim().takeIf { Versions.key(it) != Versions.key("") }
        return (if (v != null) "$app $v" else app) + DOT + platform
    }

    data class Parsed(val app: String, val version: String, val platform: String) {
        val shown: String get() = (if (version.isEmpty()) app else "$app $version") + DOT + platform
    }

    /** What a peer said, checked: only the known form, so nothing else they send is ever shown. */
    fun parse(s: String?): Parsed? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || t.length > MAX) return null
        val i = t.indexOf(DOT)
        if (i <= 0) return null
        val head = t.substring(0, i)
        val plat = t.substring(i + DOT.length)
        if (plat !in PLATFORMS) return null
        val m = FORM.find(head) ?: return null
        return Parsed(m.groupValues[1], m.groupValues[2], plat)
    }

    /** Clean form to store, or "" for anything not in it. */
    fun clean(s: String?): String = parse(s)?.shown.orEmpty()

    /** Their version is known and lower than [mine]. */
    fun older(theirs: String, mine: String): Boolean {
        val p = parse(theirs) ?: return false
        if (p.version.isEmpty()) return false
        return Versions.compare(p.version, mine) < 0
    }

    /** Their version is known and at least [min] (1.45: voice over two paths). */
    fun atLeast(theirs: String, min: String): Boolean {
        val p = parse(theirs) ?: return false
        return p.version.isNotEmpty() && Versions.compare(p.version, min) >= 0
    }

    fun askText(theirs: String): String {
        val app = parse(theirs)?.app ?: "Muninn"
        return "Could you update $app? You're on an older version."
    }

    /** User-facing features both apps have, by cap, in plain words. Kids' TV caps are Muninn only, so never listed. */
    val FEATURES: List<Pair<String, String>> = listOf(
        "g1" to "group chats", "c1" to "calls", "c2" to "video calls", "v1" to "voice rooms",
        "m1" to "replies and reactions", "m2" to "albums, polls and video messages", "i1" to "introductions",
        "f1" to "files over the internet", "j1" to "group invite links", "x1" to "moving to another device",
        "p1" to "profile pictures", "a1" to "GIFs", "f2" to "file sharing", "f3" to "resuming files",
        "g2" to "group timeouts and bans", "n1" to "nudges", "f4" to "encrypted file sharing", "v3" to "picked videos",
    )

    /** What I have and their app doesn't, in plain words. Empty when their caps aren't known. */
    fun missing(theirCaps: Set<String>, myCaps: Set<String>): List<String> =
        if (theirCaps.isEmpty()) emptyList() else FEATURES.filter { (c, _) -> c in myCaps && c !in theirCaps }.map { it.second }
}
