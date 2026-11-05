package io.github.ravenor907.muninn.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Apps a parent sends to the Kids' TV (k4, 7.9). The TV takes no files from anyone, so the parent's phone
 * first says, in room tvconfig, exactly which files are coming: each one's SHA-256 and size. The files then
 * come over the Wi-Fi with LocalSend, and the TV takes them only if they are exactly those files, once,
 * within half an hour. They are saved in the TV's Downloads and install only once a parent says Install (TvAppInstall).
 */
object TvApps {
    /** One file of an app: its SHA-256 (lowercase hex) and size. */
    data class Part(val sha: String, val size: Long)
    /** What a parent said is coming, until [until] (this TV's clock). */
    data class Allow(val from: String, val name: String, val parts: List<Part>, val until: Long)

    const val KEEP_MS = 30 * 60_000L
    private const val MAX_PARTS = 64
    private val allowed = mutableListOf<Allow>()

    /** Parent to TV: the files of [name] that are on their way. */
    fun message(name: String, parts: List<Part>): String = JSONObject().put("t", "apps").put("name", name)
        .put("files", JSONArray().apply { parts.forEach { put(JSONObject().put("h", it.sha).put("s", it.size)) } }).toString()

    /** The name and files in a parent's "apps", or null if it's malformed (anything not exactly as 7.9 says). */
    fun parse(o: JSONObject): Pair<String, List<Part>>? {
        val name = o.opt("name") as? String ?: return null
        if (name.isBlank() || name.length > 100) return null
        val files = o.opt("files") as? JSONArray ?: return null
        if (files.length() !in 1..MAX_PARTS) return null
        val parts = (0 until files.length()).map { i ->
            val f = files.opt(i) as? JSONObject ?: return null
            val h = f.opt("h") as? String ?: return null
            if (!Regex("^[0-9a-f]{64}$").matches(h)) return null
            val s = when (val v = f.opt("s")) { is Int -> v.toLong(); is Long -> v; else -> return null }
            if (s <= 0 || s >= (1L shl 53)) return null
            Part(h, s)
        }
        return name to parts
    }

    /** A parent's "apps" arrived (KidTv checked it's from a parent). */
    fun allow(from: String, name: String, parts: List<Part>, now: Long = System.currentTimeMillis()) = synchronized(allowed) {
        allowed.removeAll { it.until <= now || it.from == from && it.parts.toSet() == parts.toSet() }
        allowed.add(Allow(from, name, parts, now + KEEP_MS))
    }

    /** The parent's word that covers exactly these files, used up; null if none does. */
    fun take(parts: List<Part>, now: Long = System.currentTimeMillis()): Allow? = synchronized(allowed) {
        allowed.removeAll { it.until <= now }
        val want = parts.map { it.copy(sha = it.sha.lowercase()) }
        val hit = allowed.firstOrNull { a -> a.parts.size == want.size && a.parts.toSet() == want.toSet() } ?: return null
        allowed.remove(hit)
        hit
    }

    /** As [take], waiting up to [ms] for the parent's word: it can arrive just after the files are offered. */
    fun await(parts: List<Part>, ms: Long): Allow? {
        val end = System.currentTimeMillis() + ms
        while (true) {
            take(parts)?.let { return it }
            if (System.currentTimeMillis() >= end) return null
            Thread.sleep(250)
        }
    }

    /** Disconnect or start over: nothing a former parent said still counts. */
    fun clear() = synchronized(allowed) { allowed.clear() }
}
