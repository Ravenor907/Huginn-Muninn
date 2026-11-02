package io.github.ravenor907.muninn.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * Apps on a Kids' TV install only when a parent says so (cap k9, protocol.md 7.15). An app that comes to the TV is
 * saved in its Downloads; the TV asks its parents' phones ("appask"), a parent's Install answers "appok" naming the
 * file's SHA-256, and only then does the TV install exactly that file; it says how that went ("appres"). Plain
 * rules only (TvInstallsTest); TvAppInstall does the Android part on the TV, TvParent on the phone.
 */
object TvInstalls {
    const val CAP = "k9"
    /** How long a TV keeps an app waiting for a parent's answer. */
    const val KEEP_MS = 7 * 24 * 3_600_000L
    /** Results: installed; perm (Install updates is off on the TV); confirm (Android asks on the TV); failed. */
    val RESULTS = setOf("installed", "perm", "confirm", "failed")
    private val HEX = Regex("^[0-9a-f]{64}$")
    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** One app waiting on the TV: [h] the SHA-256 of its main file, and every part's place (content URI) and SHA-256. */
    data class Ask(val h: String, val name: String, val size: Long, val pkg: String = "", val ver: String = "",
                   val parts: List<Pair<String, String>> = emptyList(), val at: Long = 0L)

    fun nameOk(n: String) = n.isNotBlank() && n.length <= 200 && n.none { it.code < 0x20 || it == '/' || it == '\\' }

    /** TV to parents: an app is in its Downloads, waiting for a parent's Install. */
    fun askMessage(a: Ask): String = JSONObject().put("t", "appask").put("h", a.h).put("name", a.name).put("size", a.size)
        .apply { if (a.pkg.isNotEmpty()) put("pkg", a.pkg); if (a.ver.isNotEmpty()) put("ver", a.ver) }.toString()

    /** An "appask" as a parent's phone takes it, or null when it's malformed. */
    fun parseAsk(o: JSONObject): Ask? {
        if (o.opt("t") != "appask") return null
        val h = o.opt("h") as? String ?: return null
        val name = o.opt("name") as? String ?: return null
        val size = (o.opt("size") as? Number)?.toLong() ?: return null
        if (!HEX.matches(h) || !nameOk(name) || size <= 0 || size >= (1L shl 53)) return null
        val pkg = (o.opt("pkg") as? String)?.takeIf { it.length <= 255 && PKG.matches(it) } ?: ""
        val ver = (o.opt("ver") as? String)?.takeIf { it.length <= 40 && it.none { c -> c.code < 0x20 } } ?: ""
        return Ask(h, name, size, pkg, ver)
    }

    /** Parent to TV: Install ([yes]) or Not now, for the app whose main file has SHA-256 [h]. */
    fun answerMessage(h: String, yes: Boolean): String = JSONObject().put("t", if (yes) "appok" else "appno").put("h", h).toString()

    /** A parent's answer: (h, yes), or null when it's malformed. */
    fun parseAnswer(o: JSONObject): Pair<String, Boolean>? {
        val t = o.opt("t")
        if (t != "appok" && t != "appno") return null
        val h = o.opt("h") as? String ?: return null
        return if (HEX.matches(h)) h to (t == "appok") else null
    }

    /** TV to parents: how the install went. [why]: a short reason for "failed". */
    fun resultMessage(h: String, r: String, why: String = ""): String = JSONObject().put("t", "appres").put("h", h).put("r", r)
        .apply { if (r == "failed" && why.isNotEmpty()) put("why", why.take(120)) }.toString()

    /** (h, result, why), or null when it's malformed. */
    fun parseResult(o: JSONObject): Triple<String, String, String>? {
        if (o.opt("t") != "appres") return null
        val h = o.opt("h") as? String ?: return null
        val r = o.opt("r") as? String ?: return null
        if (!HEX.matches(h) || r !in RESULTS) return null
        val why = (o.opt("why") as? String)?.take(120)?.filter { it.code >= 0x20 } ?: ""
        return Triple(h, r, why)
    }

    /** What the parent's phone shows for a result. */
    fun resultText(r: String, why: String = ""): String = when (r) {
        "installed" -> "Installed"
        "perm" -> "Needs Install updates permission"
        "confirm" -> "Waiting for Install on the device"
        else -> if (why.isEmpty()) "Failed" else "Failed: $why"
    }

    /** The TV takes an answer only from a current parent, and only for an app it's still keeping. */
    fun approved(asks: List<Ask>, h: String, from: String, parents: List<String>, now: Long): Ask? =
        if (from !in parents) null else asks.firstOrNull { it.h == h && now - it.at < KEEP_MS }

    /** The TV's waiting apps, newest kept, none past [KEEP_MS], at most 20. */
    fun tidy(asks: List<Ask>, now: Long): List<Ask> = asks.filter { now - it.at < KEEP_MS }.distinctBy { it.h }.takeLast(20)

    fun toJson(asks: List<Ask>): String = JSONArray().apply {
        asks.forEach { a ->
            put(JSONObject().put("h", a.h).put("name", a.name).put("size", a.size).put("pkg", a.pkg).put("ver", a.ver).put("at", a.at)
                .put("parts", JSONArray().apply { a.parts.forEach { (uri, sha) -> put(JSONObject().put("u", uri).put("h", sha)) } }))
        }
    }.toString()

    fun fromJson(s: String?): List<Ask> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val p = o.optJSONArray("parts") ?: JSONArray()
            Ask(o.optString("h"), o.optString("name"), o.optLong("size"), o.optString("pkg"), o.optString("ver"),
                (0 until p.length()).mapNotNull { j -> p.optJSONObject(j)?.let { it.optString("u") to it.optString("h") } }, o.optLong("at"))
                .takeIf { HEX.matches(it.h) && nameOk(it.name) && it.parts.isNotEmpty() }
        }
    } catch (_: Exception) { emptyList() }
}
