package io.github.ravenor907.muninn.net.call

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * TURN logins from the relay Worker (shared/docs/relay.md), fetched over HTTPS. Cached in memory until 5 minutes before they expire; after a 429 nothing is asked until
 * the next UTC day, after a 5xx not for 60 s. The credential is never logged.
 */
object TurnCreds {
    data class Login(val urls: List<String>, val username: String, val credential: String, val expiresMs: Long) {
        fun usable(nowMs: Long) = nowMs < expiresMs - 300_000
        override fun toString() = "Login(${urls.size} urls)"
    }

    /**
     * The request body: {"id", "t", "sig"} with sig = hex HMAC-SHA256(APP_SECRET, id + "." + t), and the install token [tok] when there is one.
     * [renew]: "rn": 1, this app renews its relay before the login ends (relay.md "Renewal"), so the Worker gives a 10 minute login.
     */
    fun body(identityDer: ByteArray, tSec: Long, secret: String, tok: String? = null, renew: Boolean = false): String {
        val id = idOf(identityDer)
        val sig = CallCrypto.hex(CallCrypto.hmac256(secret.toByteArray(Charsets.UTF_8), "$id.$tSec".toByteArray(Charsets.UTF_8)))
        return JSONObject().put("id", id).put("t", tSec).put("sig", sig).apply { if (tok != null) put("tok", tok); if (renew) put("rn", 1) }.toString()
    }

    /** A call renews its relay this long before the login ends (relay.md "Renewal"). */
    const val RENEW_BEFORE_MS = 120_000L
    /** After a renewal the old allocation still carries media this long, until the peer has the new address. */
    const val HANDOVER_MS = 45_000L

    /** How long a 429 says to wait ("retry", seconds), at least a minute and at most until the next UTC day; null without it (older Workers). */
    fun retryMs(text: String, nowMs: Long): Long? = try {
        val o = JSONObject(text)
        if (o.opt("retry") is Number) (o.getLong("retry") * 1000).coerceIn(60_000L, nextUtcDayMs(nowMs) - nowMs) else null
    } catch (e: Exception) { null }

    /** The relay id the Worker counts: lowercase hex SHA-256 of [identityDer]. */
    fun idOf(identityDer: ByteArray): String = CallCrypto.hex(CallCrypto.sha256(identityDer))

    // ---- Install tokens (relay.md "Install tokens"): registered once with proof of work, renewed by every login.

    /** Leading zero bits the registration hash needs (the Worker's POW_BITS). */
    const val POW_BITS = 18
    /** A stored token is used while more than this is left of it; with less, the install registers again first. */
    const val TOKEN_SPARE_S = 86_400L

    /** SHA-256 of "huginn-relay pow v1:" + id + ":" + t + ":" + pow starts with [bits] zero bits. */
    fun powOk(id: String, tSec: Long, pow: Long, bits: Int = POW_BITS): Boolean {
        if (pow < 0) return false
        val d = CallCrypto.sha256("huginn-relay pow v1:$id:$tSec:$pow".toByteArray(Charsets.UTF_8))
        var left = bits
        for (b in d) {
            if (left <= 0) return true
            val v = b.toInt() and 0xFF
            if (left >= 8) { if (v != 0) return false; left -= 8 } else return (v shr (8 - left)) == 0
        }
        return left <= 0
    }

    /** The smallest proof of work for [id] at [tSec] (about 260,000 tries on average), or -1 after [maxTries]. */
    fun solve(id: String, tSec: Long, bits: Int = POW_BITS, maxTries: Long = 1L shl 26): Long {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val head = "huginn-relay pow v1:$id:$tSec:".toByteArray(Charsets.UTF_8)
        var n = 0L
        while (n < maxTries) {
            md.reset(); md.update(head); val d = md.digest(n.toString().toByteArray(Charsets.UTF_8))
            var left = bits; var ok = true; var i = 0
            while (left > 0) {
                val v = d[i++].toInt() and 0xFF
                if (left >= 8) { if (v != 0) { ok = false; break }; left -= 8 } else { ok = (v shr (8 - left)) == 0; break }
            }
            if (ok) return n
            n++
        }
        return -1
    }

    /** The registration body: {"id", "t", "sig", "pow"}. */
    fun regBody(identityDer: ByteArray, tSec: Long, secret: String, pow: Long): String =
        JSONObject(body(identityDer, tSec, secret)).put("pow", pow).toString()

    /** Where the install token is kept (outside the vault is fine: it only spends this install's own daily logins). */
    interface TokenStore { fun load(): String?; fun save(value: String?) }
    @Volatile var store: TokenStore? = null

    /** A stored token: "<id>|<tok>|<exp>". Only this id's, and only while more than [TOKEN_SPARE_S] is left. */
    fun tokenFor(stored: String?, id: String, nowS: Long): String? {
        val p = stored?.split('|') ?: return null
        if (p.size != 3 || p[0] != id) return null
        val exp = p[2].toLongOrNull() ?: return null
        return p[1].takeIf { it.isNotEmpty() && exp - nowS > TOKEN_SPARE_S }
    }

    /** {"tok", "exp"} in an answer, as kept by [store]; null when there's none. */
    fun tokenOf(text: String, id: String): String? = try {
        val o = JSONObject(text)
        val tok = o.optString("tok"); val exp = o.optLong("exp", 0)
        if (tok.isEmpty() || tok.contains('|') || exp <= 0) null else "$id|$tok|$exp"
    } catch (e: Exception) { null }

    /** The Worker's time in a 401 "stale" answer ({"error":"stale","now":...}), or null. */
    fun staleNow(text: String): Long? = try {
        val o = JSONObject(text)
        if (o.optString("error") == "stale" && o.opt("now") is Number) o.getLong("now") else null
    } catch (e: Exception) { null }

    private fun errorOf(text: String): String = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }

    /** Reads a 200 answer; null if anything is missing. Only turn: and turns: URLs are kept. */
    fun parse(text: String, nowMs: Long): Login? = try {
        val o = JSONObject(text)
        val a = o.getJSONArray("urls")
        val urls = (0 until a.length()).mapNotNull { a.opt(it) as? String }.filter { it.startsWith("turn:") || it.startsWith("turns:") }
        val user = o.getString("username"); val pass = o.getString("credential")
        val ttl = o.optLong("ttl", 0)
        if (urls.isEmpty() || user.isEmpty() || pass.isEmpty() || ttl <= 0) null else Login(urls, user, pass, nowMs + ttl * 1000)
    } catch (e: Exception) { null }

    @Volatile private var cached: Login? = null
    @Volatile private var notBefore = 0L
    /** No registration tried before this (ms): after a 429 the next UTC day, after anything else an hour. */
    @Volatile private var regNotBefore = 0L
    /** The Worker's clock less this device's (s), learned from a "stale" answer. */
    @Volatile private var offsetS = 0L

    /** Why the last fetch failed, for the call screen ("" when it worked). Never a credential or an address. */
    @Volatile var lastProblem = ""; private set

    fun forget() { cached = null }

    /** Registers [id] (proof of work first): the token as [store] keeps it, or null (the login goes without one). */
    private fun register(identityDer: ByteArray, id: String, post: (url: String, body: String) -> Pair<Int, String>?, nowMs: Long): String? {
        if (nowMs < regNotBefore) return null
        val t = nowMs / 1000 + offsetS
        val pow = solve(id, t)
        if (pow < 0) return null
        val r = post(RelayConfig.WORKER_URL.trimEnd('/') + "/register", regBody(identityDer, t, RelayConfig.APP_SECRET, pow))
        val kept = if (r?.first == 200) tokenOf(r.second, id) else null
        if (kept == null) regNotBefore = if (r?.first == 429) nextUtcDayMs(nowMs) else nowMs + 3_600_000L
        else store?.save(kept)
        return kept
    }

    /**
     * A usable login, from the cache or the Worker. Blocks (call it off the main thread). [post] does the
     * HTTPS POST and returns (status, body) or null if it couldn't connect. With an install token (registered first
     * when there's none), else without one as before. A token the Worker refuses is dropped and registered again once;
     * a "stale" answer corrects the clock for one more try.
     */
    @Synchronized
    fun get(identityDer: ByteArray, post: (url: String, body: String) -> Pair<Int, String>?, nowMs: Long = System.currentTimeMillis()): Login? {
        cached?.let { if (it.usable(nowMs)) return it }
        if (!RelayConfig.configured) { lastProblem = "The internet relay isn't set up yet"; return null }
        if (nowMs < notBefore) { lastProblem = "The internet relay is resting"; return null }
        val id = idOf(identityDer)
        var tok = tokenFor(try { store?.load() } catch (_: Exception) { null }, id, nowMs / 1000 + offsetS)
        if (tok == null) tok = register(identityDer, id, post, nowMs)?.let { tokenFor(it, id, nowMs / 1000 + offsetS) }
        var tries = 0
        var r: Pair<Int, String>?
        while (true) {
            r = post(RelayConfig.WORKER_URL.trimEnd('/') + "/turn", body(identityDer, nowMs / 1000 + offsetS, RelayConfig.APP_SECRET, tok, renew = true))
            if (r == null || r.first != 401 || tries++ > 1) break
            val err = errorOf(r.second)
            val now = staleNow(r.second)
            if (now != null) { offsetS = now - nowMs / 1000; continue }
            if (tok != null && (err == "bad_token" || err == "token_expired")) {
                store?.save(null)
                tok = register(identityDer, id, post, nowMs)?.let { tokenFor(it, id, nowMs / 1000 + offsetS) }
                continue
            }
            break
        }
        if (r == null) { lastProblem = "Couldn't reach the internet relay"; return null }
        val (status, text) = r
        when {
            status == 200 -> parse(text, System.currentTimeMillis())?.let { login ->
                tokenOf(text, id)?.let { kept -> try { store?.save(kept) } catch (_: Exception) {} }
                cached = login; lastProblem = ""; return login
            }
            status == 429 -> notBefore = nowMs + (retryMs(text, nowMs) ?: (nextUtcDayMs(nowMs) - nowMs))
            status >= 500 -> notBefore = nowMs + 60_000
        }
        lastProblem = when (status) {
            401 -> "The phone's clock looks wrong, so the internet relay said no"
            429 -> if (notBefore < nextUtcDayMs(nowMs)) "The internet relay is busy right now" else "The internet relay is used up for today"
            else -> "The internet relay didn't answer properly"
        }
        return null
    }

    fun nextUtcDayMs(nowMs: Long): Long {
        val day = java.time.Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
        return LocalDate.from(day).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
