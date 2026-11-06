package io.github.ravenor907.muninn.net

import org.json.JSONObject

/**
 * Labs bug reports (shared/docs/protocol.md, "Labs"). A report is a private message to the Labs contact, sealed v1
 * to its chat key and sent to its mailbox over the internet, with "lb" in the envelope: the app, its version label,
 * the system and the device model. Nothing else about the phone. A screenshot goes as photo pieces (room "photo")
 * with the same "lb"; the report's "lb" names it in "s".
 */
object LabsWire {
    /** The Labs contact, built in. Never shown in the contact list. */
    const val NAME = "Huginn Muninn Labs"
    const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5ihedM8ypmMLI817zKk9CbxL0Tl3Q67Orwj8/UUpgOH9PlIIlw7FQDuIfKBa6BuK2fjw6Iu2RKxNvLdEFfVK+w=="
    const val MAILBOX = "5ee40952b01671ffec761fc6ff65529fac098b03aed4b7a0b5d69d86e2e93529"
    /** The envelope field. */
    const val FIELD = "lb"
    const val MAX_TEXT = 2000
    /** Reports taken from one sender in a day; more are refused. */
    const val PER_DAY = 10
    /** A screenshot is at most this many photo pieces (Photos.PIECE each). */
    const val MAX_SHOT_PIECES = Photos.INTERNET_PIECES
    private const val MAX_FIELD = 80
    private const val DAY = 86_400_000L
    val APPS = setOf("muninn", "huginn")

    /** The "lb" object: exactly these four fields (and "s" for the screenshot's id, on the report itself). */
    fun info(app: String, version: String, os: String, model: String, shot: String = ""): JSONObject =
        JSONObject().put("app", app).put("v", version.take(MAX_FIELD)).put("os", os.take(MAX_FIELD)).put("model", model.take(MAX_FIELD))
            .apply { if (shot.isNotEmpty()) put("s", shot) }

    /** A well-formed "lb": a known app, and short strings. */
    fun ok(o: JSONObject?): Boolean {
        if (o == null) return false
        val app = o.opt("app") as? String ?: return false
        if (app !in APPS) return false
        for (k in listOf("v", "os", "model")) { val v = o.opt(k) as? String ?: return false; if (v.length > MAX_FIELD) return false }
        if (o.has("s")) { val s = o.opt("s") as? String ?: return false; if (s.isEmpty() || s.length > 64) return false }
        return true
    }

    /** Another report from someone who sent the ones at [times] (ms): at most [PER_DAY] in a day. */
    fun allow(times: List<Long>, now: Long): Boolean = times.count { now - it in 0 until DAY } < PER_DAY

    /** The text of a report: trimmed, at most [MAX_TEXT] characters (Proto06.chars, as the rest of chat counts them). */
    fun textOk(text: String): Boolean = text.isNotBlank() && Proto06.chars(text) <= MAX_TEXT

    /** Where a report's activity log starts (the raven held 7 seconds in Labs mode), after what the person wrote. */
    const val LOG_MARK = "\n\n[Activity log]\n"
    const val MAX_LOG = ActivityLog.MAX_CHARS

    /** What went: the person's words, and the activity log after [LOG_MARK] when there is one. */
    fun withLog(text: String, log: String): String = if (log.isBlank()) text else text + LOG_MARK + log.take(MAX_LOG)

    /** (what they wrote, the activity log or "") from a report as it came; null when either part is too long or empty. */
    fun split(full: String): Pair<String, String>? {
        val at = full.indexOf(LOG_MARK)
        val text = (if (at < 0) full else full.substring(0, at)).trim()
        val log = if (at < 0) "" else full.substring(at + LOG_MARK.length)
        return if (textOk(text) && log.length <= MAX_LOG) text to log else null
    }

    /**
     * A report's clear fields: the room, the reply route (chat key and mailbox), the name, the id and "lb".
     * Nothing else about the device (no device id, no caps list). The seal adds "cap" and the sealed text.
     */
    fun envelope(room: String, fromKey: String, fromName: String, mailbox: String, msgId: String, lb: JSONObject): JSONObject =
        JSONObject().put("room", room).put("fromKey", fromKey).put("fromName", fromName).put("mailbox", mailbox).put("id", msgId).put(FIELD, lb)

    /** How long after the last report sent from here a message from the Labs key is taken (as a request). */
    const val REPLY_MS = 30 * DAY

    /** A message from the Labs key may come in: a report went from here within [REPLY_MS] of [now]. */
    fun replyOpen(reportedAt: Long, now: Long): Boolean = reportedAt > 0 && now - reportedAt in 0 until REPLY_MS

    /** "0.99 beta 12 · Android 15 · Samsung SM-S938U". */
    fun summary(o: JSONObject): String = listOf(o.optString("v"), o.optString("os"), o.optString("model")).filter { it.isNotBlank() }.joinToString(" · ")
}
