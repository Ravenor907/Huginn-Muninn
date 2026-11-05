package io.github.ravenor907.muninn.tv

import org.json.JSONObject
import java.util.Base64

/**
 * Cap k6 (shared/docs/protocol.md 7.11), the messages only: what a well-formed rejoin, ask, approve, block,
 * swap, orphan or blocked is, and when a takeover would be due, checked against the shared vectors.
 *
 * This app neither sends nor takes them. Another parent is added from a parent's phone (TvParent.addParent),
 * and a TV whose parent phones are all lost is started over by clearing Muninn's data on the TV.
 */
object TvReconnect {
    const val CAP = "k6"
    const val WAIT_MS = 24 * 3_600_000L

    /** Well-formed for its type: exactly its fields, keys are base64 text, names 1 to 40 characters. */
    fun ok(o: JSONObject): Boolean = try {
        val t = o.getString("t")
        val keys = o.keys().asSequence().toSet()
        fun key(f: String) = o.get(f).let { it is String && it.isNotEmpty() && it.length <= 200 && Base64.getDecoder().decode(it).isNotEmpty() }
        fun name(f: String) = o.get(f).let { it is String && it.isNotBlank() && it.codePointCount(0, it.length) <= TvRules.MAX_NAME }
        when (t) {
            "rejoin" -> keys == setOf("t", "n", "r") && name("n") && o.get("r").let { it is String && it.length in 16..64 }
            "ask" -> keys == setOf("t", "k", "n") && key("k") && name("n")
            "approve", "block" -> keys == setOf("t", "k") && key("k")
            "swap" -> keys == setOf("t", "k", "n", "p") && key("k") && name("n") && o.get("p").let { it is String && (it.isEmpty() || it.length == 64) }
            "orphan" -> keys == setOf("t", "cfg") && o.get("cfg") is JSONObject
            "blocked" -> keys == setOf("t")
            else -> false
        }
    } catch (e: Exception) { false }

    /** A takeover is due only when nobody blocked and [WAIT_MS] went by since the ask. */
    fun takeoverDue(at: Long, now: Long, blocked: Boolean): Boolean = !blocked && now - at >= WAIT_MS
}
