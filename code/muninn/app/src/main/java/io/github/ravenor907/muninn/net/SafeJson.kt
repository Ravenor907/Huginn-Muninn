package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The one way to parse JSON text that came from outside (relays, the Wi-Fi server, Bluetooth, files, other apps).
 * Android's org.json calls itself once per nesting level with no limit, so a body of "[[[[…" overflows the stack:
 * a StackOverflowError that no catch (Exception) sees, and the app dies. Here the text is first scanned (cheaply, no
 * recursion) for nesting depth and size; anything too deep or too big throws a JSONException instead, which every
 * existing catch already handles. No Android in here.
 */
object SafeJson {
    /** Deepest nesting allowed. Real messages stay under about 10. */
    const val MAX_DEPTH = 64
    /** Largest text parsed at all (a full backup can be big; network input is capped earlier, see [MAX_RELAY_FRAME]). */
    const val MAX_CHARS = 64 * 1024 * 1024
    /** Largest single frame taken from a relay: events are at most about 64 KB (protocol 20.1), so 1 MB is plenty. */
    const val MAX_RELAY_FRAME = 1024 * 1024

    /**
     * True when [s] nests no deeper than [maxDepth] (brackets inside "strings" don't count) and is at most [maxChars]
     * long. Linear, no recursion.
     */
    fun ok(s: String, maxDepth: Int = MAX_DEPTH, maxChars: Int = MAX_CHARS): Boolean {
        if (s.length > maxChars) return false
        var depth = 0
        var inString = false
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                // org.json also takes 'single-quoted' strings and /* */, // and # comments, which would hide brackets
                // from this scan. Real JSON never has them outside a string, so such text is refused.
                '\'', '/', '#' -> return false
                '[', '{' -> if (++depth > maxDepth) return false
                ']', '}' -> if (depth > 0) depth--
            }
            i++
        }
        return true
    }

    private fun check(s: String, maxChars: Int) {
        if (!ok(s, MAX_DEPTH, maxChars)) throw JSONException("JSON too deep or too big")
    }

    /** Like JSONObject(s), but throws JSONException for text nested too deep or too big. */
    fun obj(s: String, maxChars: Int = MAX_CHARS): JSONObject { check(s, maxChars); return JSONObject(s) }

    /** Like JSONArray(s), but throws JSONException for text nested too deep or too big. */
    fun arr(s: String, maxChars: Int = MAX_CHARS): JSONArray { check(s, maxChars); return JSONArray(s) }

    /** The object, or null when [s] is not a safe JSON object. */
    fun objOrNull(s: String?): JSONObject? = if (s == null) null else try { obj(s) } catch (_: Exception) { null }
}
