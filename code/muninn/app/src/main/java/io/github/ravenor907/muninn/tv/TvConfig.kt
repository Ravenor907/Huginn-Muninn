package io.github.ravenor907.muninn.tv

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Canonical JSON (shared/docs/protocol.md, section 2.1) and signed objects (2.2), for the kids' TV.
 * Kept free of Android so the unit tests run it against shared/vectors/proto06-vectors.json.
 *
 * Objects: members sorted by key (ASCII), no spaces. Arrays keep their order. Strings: raw UTF-8 with
 * only `\` and `"` escaped; control characters are refused. Integers 0 <= n < 2^53. true, false. No null,
 * no fractions.
 */
object Canon {
    private const val LIMIT = 1L shl 53

    fun of(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> {
                val n = (v as Number).toLong()
                require(n in 0 until LIMIT) { "integer out of range" }
                sb.append(n)
            }
            is BigInteger -> {
                require(v.signum() >= 0 && v.bitLength() <= 53) { "integer out of range" }
                sb.append(v.toString())
            }
            is String -> {
                text(v)
                sb.append('"')
                for (ch in v) { if (ch == '\\' || ch == '"') sb.append('\\'); sb.append(ch) }
                sb.append('"')
            }
            is JSONArray -> {
                sb.append('[')
                for (i in 0 until v.length()) { if (i > 0) sb.append(','); write(sb, v.get(i)) }
                sb.append(']')
            }
            is JSONObject -> {
                val keys = keys(v).sorted()
                for (k in keys) require(k.all { it.code < 128 }) { "non-ASCII key" }
                sb.append('{')
                keys.forEachIndexed { i, k -> if (i > 0) sb.append(','); write(sb, k); sb.append(':'); write(sb, v.get(k)) }
                sb.append('}')
            }
            else -> throw IllegalArgumentException("type not allowed")
        }
    }

    /** A text field: no control characters (U+0000..U+001F, U+007F), at most [limit] characters (code points). */
    fun text(s: Any?, limit: Int = -1): String {
        require(s is String) { "not text" }
        require(s.none { it.code < 0x20 || it.code == 0x7F }) { "control character" }
        require(limit < 0 || s.codePointCount(0, s.length) <= limit) { "too long" }
        return s
    }

    fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    fun isInt(v: Any?): Boolean = v is Int || v is Long || v is Short || v is Byte || v is BigInteger

    fun unsigned(o: JSONObject): JSONObject = JSONObject().also { u -> keys(o).forEach { if (it != "sig") u.put(it, o.get(it)) } }

    fun signedBytes(domain: String, o: JSONObject): ByteArray = (domain + "\n" + of(unsigned(o))).toByteArray(Charsets.UTF_8)

    /** Lowercase hex SHA-256 of the canonical form with "sig" included. */
    fun hash(o: JSONObject): String =
        MessageDigest.getInstance("SHA-256").digest(of(o).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** A copy of [o] with "sig" (base64 DER ECDSA P-256 SHA-256) set. */
    fun sign(priv: PrivateKey, domain: String, o: JSONObject): JSONObject {
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(signedBytes(domain, o)) }.sign()
        return unsigned(o).put("sig", Base64.getEncoder().encodeToString(s))
    }

    fun verify(pubB64: String, domain: String, o: JSONObject): Boolean = try {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
        val sig = Base64.getDecoder().decode(o.getString("sig"))
        Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(signedBytes(domain, o)) }.verify(sig)
    } catch (e: Exception) { false }
}

/**
 * What the kids' screens say when something can't be done: a few words, each with its icon.
 * Kept free of Android so the unit tests check them.
 */
object TvWords {
    const val TOO_SHORT = "Too short"
    const val CANT_CALL = "Can't call now"
    const val NO_MIC = "No microphone"
    const val ASK_PARENT = "Ask a parent"
    const val NOT_HEARD = "Didn't hear you"
    const val NO_TYPING = "Can't type here"
    const val NO_INTERNET = "No internet"
    val ALL = listOf(TOO_SHORT, CANT_CALL, NO_MIC, ASK_PARENT, NOT_HEARD, NO_TYPING, NO_INTERNET)

    /** The icon beside [words] (a name from the app's icon set). */
    fun icon(words: String): String = when (words) {
        TOO_SHORT -> "clock"
        CANT_CALL -> "call"
        NO_MIC, NOT_HEARD -> "mic"
        ASK_PARENT -> "user"
        NO_TYPING -> "chat"
        NO_INTERNET -> "wifi"
        else -> "info"
    }
}

/** A parent-defined button on the TV. [id]: 8 random bytes, base64url (11 characters). */
data class Quick(val id: String, val text: String)

/** Someone the TV may talk to: chat key, name shown on the TV, Nostr mailbox (hex, or empty). */
data class TvPerson(val k: String, val n: String, val p: String)

/** A checked config, read into plain values. [raw] is the signed object exactly as it came. */
data class TvConfig(
    val tv: String, val v: Long, val by: String, val parents: List<String>, val quick: List<Quick>,
    val contacts: List<TvPerson>, val dictation: Boolean, val ts: Long, val raw: JSONObject,
    /** k2 (7.5): calls with the people on the list (absent: on), and the microphone level 0..100 (absent: 50). */
    val calls: Boolean = true, val mic: Int = TvRules.DEFAULT_MIC,
    /** k5 (7.10): the TV's noise removal, [TvRules.NR_NONE] to [TvRules.NR_BOTH] (absent: both). */
    val nr: Int = TvRules.NR_BOTH,
    /** k7 (7.12): whether the TV checks for updates by itself, as the parent set it (absent: on). */
    val upd: Boolean = true,
    /** k8 (7.13): days messages stay on the TV, 1 to 7 (absent: null, the TV keeps its own setting). */
    val keep: Int? = null,
)

/** The k2 fields (7.5), only put in a config for a TV whose app said "k2". */
data class TvExtras(val calls: Boolean = true, val mic: Int = TvRules.DEFAULT_MIC, /** k5: only for a TV whose app said "k5". */ val nr: Int? = null,
                     /** k7: only for a TV whose app said "k7". */ val upd: Boolean? = null,
                     /** k8: only for a TV whose app said "k8". */ val keep: Int? = null)

/**
 * The kids' TV rules (shared/docs/protocol.md, section 7): what a well-formed config is, when a TV takes a new
 * config or a disconnect, and how a parent's app builds them. Matches check_tvconfig, accept_tvconfig and
 * accept_disconnect in shared/tools/proto06_ref.py, reasons included.
 */
object TvRules {
    const val CONFIG = "huginn-muninn tvconfig v1"
    const val DISCONNECT = "huginn-muninn tvdisconnect v1"
    const val MAX_QUICK = 24
    const val MAX_QUICK_TEXT = 80
    /** The most a parent can type for a quick message: what a tile on the TV shows whole. Configs still take up to [MAX_QUICK_TEXT]. */
    const val QUICK_TILE = 30
    const val MAX_CONTACTS = 50
    const val MAX_NAME = 40
    const val MAX_BYTES = 16 * 1024
    const val DEFAULT_MIC = 50
    const val MAX_MIC = 100
    /** k5 (7.10): noise removal on the TV. None, Standard (WebRTC's), Strong (RNNoise) or both, the usual. */
    const val NR_NONE = 0
    const val NR_STANDARD = 1
    const val NR_STRONG = 2
    const val NR_BOTH = 3
    /** k8 (7.13): the fewest and most days messages stay on the TV. */
    const val MIN_KEEP = 1
    const val MAX_KEEP = 7
    /** k8 (7.13): the TV permissions a parent can ask the TV to turn on, and that the TV's status lists while off. */
    val PERMS = listOf("mic", "notify", "overlay", "install")
    /** Quick messages the TV's conversation screen shows (the first ones in the parent's order). */
    const val QUICK_SHOWN = 4
    /** The recording screen takes OK only after this long, so the press that started it can't send it. */
    const val ARM_MS = 2_000L
    val DEFAULT_QUICK = listOf("I need you", "Please come here", "I'm hungry", "I love you")

    private val FIELDS = setOf("tv", "v", "by", "parents", "quick", "contacts", "dictation", "ts", "sig")
    /** k2 (7.5): may be there or not; a TV without k2 never gets a config that has them. */
    private val OPTIONAL = setOf("calls", "mic", "nr", "upd", "keep")
    private val DISCONNECT_FIELDS = setOf("tv", "v", "by", "ts", "sig")
    private val random = SecureRandom()

    /** Throws IllegalArgumentException with the reference's reason if [c] isn't a well-formed config. */
    fun check(c: JSONObject) {
        val ks0 = Canon.keys(c)
        require(ks0.containsAll(FIELDS) && (ks0 - FIELDS).all { it in OPTIONAL }) { "fields" }
        if (c.has("calls")) require(c.get("calls") is Boolean) { "calls" }
        if (c.has("mic")) { val m = c.get("mic"); require(Canon.isInt(m) && (m as Number).toLong() in 0..MAX_MIC.toLong()) { "mic" } }
        if (c.has("nr")) { val n = c.get("nr"); require(Canon.isInt(n) && (n as Number).toLong() in NR_NONE.toLong()..NR_BOTH.toLong()) { "nr" } }
        if (c.has("upd")) require(c.get("upd") is Boolean) { "upd" }
        if (c.has("keep")) { val d = c.get("keep"); require(Canon.isInt(d) && (d as Number).toLong() in MIN_KEEP.toLong()..MAX_KEEP.toLong()) { "keep" } }
        val v = c.get("v")
        require(Canon.isInt(v) && (v as Number).toLong() >= 1) { "version" }
        val parents = strings(c.get("parents"), "parents")
        val by = c.get("by")
        require(parents.isNotEmpty() && parents == parents.distinct().sorted() && by is String && by in parents) { "parents" }
        require(c.get("dictation") is Boolean) { "dictation" }
        val quick = c.get("quick") as? JSONArray ?: throw IllegalArgumentException("quick fields")
        require(quick.length() <= MAX_QUICK) { "too many quick messages" }
        val qs = (0 until quick.length()).map { quick.get(it) as? JSONObject ?: throw IllegalArgumentException("quick fields") }
        val ids = qs.map { it.opt("id") }
        require(ids.distinct().size == ids.size) { "quick ids" }
        for (q in qs) {
            require(Canon.keys(q) == setOf("id", "text") && quickIdOk(q.get("id"))) { "quick fields" }
            Canon.text(q.get("text"), MAX_QUICK_TEXT)
            require(q.getString("text").isNotBlank()) { "empty quick message" }
        }
        val contacts = c.get("contacts") as? JSONArray ?: throw IllegalArgumentException("contacts")
        val cs = (0 until contacts.length()).map { contacts.get(it) as? JSONObject ?: throw IllegalArgumentException("contact fields") }
        val ks = cs.map { it.opt("k") as? String ?: throw IllegalArgumentException("contacts") }
        require(ks == ks.distinct().sorted() && ks.size <= MAX_CONTACTS) { "contacts" }
        for (x in cs) {
            require(Canon.keys(x) == setOf("k", "n", "p")) { "contact fields" }
            Canon.text(x.get("n"), MAX_NAME)
        }
        require(Canon.of(c).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "too big" }
    }

    private fun strings(v: Any?, why: String): List<String> {
        val a = v as? JSONArray ?: throw IllegalArgumentException(why)
        return (0 until a.length()).map { a.get(it) as? String ?: throw IllegalArgumentException(why) }
    }

    private fun quickIdOk(id: Any?): Boolean = id is String && try { Base64.getUrlDecoder().decode(id).size == 8 } catch (e: Exception) { false }

    /**
     * Whether a TV with chat key [tvKey], current parent keys [parents] and current config [cur] takes [new].
     * Returns (accepted, reason), the reasons worded like the reference.
     */
    fun accept(tvKey: String, parents: Collection<String>, cur: JSONObject?, new: JSONObject): Pair<Boolean, String> {
        try { check(new) } catch (e: IllegalArgumentException) { return false to "malformed: ${e.message}" }
        if (new.opt("tv") != tvKey) return false to "for another TV"
        if (new.getString("by") !in parents) return false to "not from a parent"
        if (!Canon.verify(new.getString("by"), CONFIG, new)) return false to "bad signature"
        if (cur == null || new.getLong("v") > cur.getLong("v")) return true to "newer"
        if (new.getLong("v") == cur.getLong("v") && Canon.hash(new) < Canon.hash(cur)) return true to "same version, lower hash"
        return false to "not newer"
    }

    /** Whether a TV at config version [curV] with parents [parents] takes the disconnect [d]. */
    fun acceptDisconnect(tvKey: String, parents: Collection<String>, curV: Long, d: JSONObject): Pair<Boolean, String> {
        if (Canon.keys(d) != DISCONNECT_FIELDS) return false to "fields"
        if (d.opt("tv") != tvKey) return false to "for another TV"
        val by = d.opt("by") as? String ?: return false to "not from a parent"
        if (by !in parents) return false to "not from a parent"
        val v = d.opt("v")
        if (!Canon.isInt(v) || (v as Number).toLong() != curV) return false to "not for the current config"
        if (!Canon.verify(by, DISCONNECT, d)) return false to "bad signature"
        return true to "ok"
    }

    /** Reads a config that passed [check]. */
    fun parse(c: JSONObject): TvConfig {
        val q = c.getJSONArray("quick")
        val p = c.getJSONArray("contacts")
        val ps = c.getJSONArray("parents")
        return TvConfig(
            c.getString("tv"), c.getLong("v"), c.getString("by"), (0 until ps.length()).map { ps.getString(it) },
            (0 until q.length()).map { q.getJSONObject(it).let { o -> Quick(o.getString("id"), o.getString("text")) } },
            (0 until p.length()).map { p.getJSONObject(it).let { o -> TvPerson(o.getString("k"), o.getString("n"), o.getString("p")) } },
            c.getBoolean("dictation"), c.getLong("ts"), c,
            c.optBoolean("calls", true), if (c.has("mic")) c.getInt("mic") else DEFAULT_MIC,
            if (c.has("nr")) c.getInt("nr") else NR_BOTH,
            c.optBoolean("upd", true),
            if (c.has("keep")) c.getInt("keep") else null,
        )
    }

    /** A signed config from a parent's app. Sorts [parents] and [contacts] the way [check] wants them. */
    fun config(priv: PrivateKey, tv: String, v: Long, by: String, parents: Collection<String>, quick: List<Quick>,
               contacts: List<TvPerson>, dictation: Boolean, ts: Long, extras: TvExtras? = null): JSONObject {
        val o = JSONObject().put("tv", tv).put("v", v).put("by", by)
            .put("parents", JSONArray().apply { (parents + by).distinct().sorted().forEach { put(it) } })
            .put("quick", JSONArray().apply { quick.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } })
            .put("contacts", JSONArray().apply { contacts.distinctBy { it.k }.sortedBy { it.k }.forEach { put(JSONObject().put("k", it.k).put("n", it.n).put("p", it.p)) } })
            .put("dictation", dictation).put("ts", ts)
        extras?.let { o.put("calls", it.calls).put("mic", it.mic.coerceIn(0, MAX_MIC)); it.nr?.let { n -> o.put("nr", n.coerceIn(NR_NONE, NR_BOTH)) }; it.upd?.let { u -> o.put("upd", u) }; it.keep?.let { d -> o.put("keep", d.coerceIn(MIN_KEEP, MAX_KEEP)) } }
        return Canon.sign(priv, CONFIG, o)
    }

    fun disconnect(priv: PrivateKey, tv: String, v: Long, by: String, ts: Long): JSONObject =
        Canon.sign(priv, DISCONNECT, JSONObject().put("tv", tv).put("v", v).put("by", by).put("ts", ts))

    fun quickId(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(8).also { random.nextBytes(it) })

    /** Text a person typed, made safe for a config: no control characters, trimmed, at most [max] characters. */
    fun clean(s: String, max: Int): String {
        val t = s.filter { it.code >= 0x20 && it.code != 0x7F }.trim()
        return if (t.codePointCount(0, t.length) <= max) t else t.substring(0, t.offsetByCodePoints(0, max)).trim()
    }

    // ---- the plaintexts in room "tvconfig"
    fun configMessage(cfg: JSONObject): String = JSONObject().put("t", "config").put("cfg", cfg).toString()
    /** [app]: the TV's Muninn version (k2, so a parent's phone can show it and offer Update TV). */
    /**
     * [cfg]: the TV's signed config as it runs now, so a parent whose phone is behind (another parent's
     * change, a restored backup) catches up. Apps that don't read it ignore it.
     */
    fun appliedMessage(v: Long, app: String? = null, cfg: JSONObject? = null): String =
        JSONObject().put("t", "applied").put("v", v).apply { if (!app.isNullOrEmpty()) put("app", app); cfg?.let { put("cfg", it) } }.toString()
    /** k2: a parent asks the TV to look for a new Muninn now and install it. */
    fun updateMessage(): String = JSONObject().put("t", "update").toString()
    /** t1 (7.6): a parent hands someone they invited the TV's contact code, so their app adds the TV. */
    fun cardMessage(code: String, cfg: JSONObject? = null): String =
        JSONObject().put("t", "card").put("code", code).apply { cfg?.let { put("cfg", it) } }.toString()

    /**
     * A config a TV or a fellow parent handed this phone ([me]), read only if it is a well-formed config for
     * [tvKey], lists [me] as a parent and is signed by one of its parents. Null otherwise.
     */
    fun shared(tvKey: String, me: String, c: JSONObject?): TvConfig? {
        if (c == null) return null
        try { check(c) } catch (e: IllegalArgumentException) { return null }
        if (c.opt("tv") != tvKey) return null
        val cfg = try { parse(c) } catch (e: Exception) { return null }
        if (me !in cfg.parents || !Canon.verify(cfg.by, CONFIG, c)) return null
        return cfg
    }
    /** [free]: the parent lets the TV be used as a normal Muninn afterwards (its pairing screen offers it again). */
    fun disconnectMessage(d: JSONObject, free: Boolean = false): String =
        JSONObject().put("t", "disconnect").put("d", d).apply { if (free) put("free", true) }.toString()

    // ---- k3 (7.7): the TV's microphone level set on the TV, its storage, and Clear history
    /**
     * TV to parents: the level in use and the bytes Muninn takes on the TV; [ts] when the level last changed
     * (TV clock). [own]: the level was set on the TV itself ("own":true), so a parent's phone takes it
     * without comparing clocks.
     */
    fun statusMessage(mic: Int, used: Long, ts: Long, own: Boolean = false, off: List<String>? = null, update: String? = null): String =
        JSONObject().put("t", "status").put("mic", mic.coerceIn(0, MAX_MIC)).put("used", used.coerceIn(0L, MAX_INT)).put("ts", ts.coerceIn(0L, MAX_INT))
            .apply {
                if (own) put("own", true); off?.let { put("off", JSONArray(it.filter { p -> p in PERMS }.distinct())) }
                update?.takeIf { it in UPDATE_STATES }?.let { put("update", it) }
            }.toString()
    /** Parent to TV: "send me your status". */
    fun usageMessage(): String = JSONObject().put("t", "usage").toString()
    /** Parent to TV: erase the messages, photos, voice messages and caches (pairing and contacts stay). */
    fun clearMessage(): String = JSONObject().put("t", "clear").toString()

    /**
     * A TV's status (7.7). [own]: the level was set on the TV (absent: false). [off]: k8 (7.13), the
     * permissions still off on the TV (null: the TV didn't say).
     */
    data class Status(val mic: Int, val used: Long, val ts: Long, val own: Boolean = false, val off: List<String>? = null, val update: String? = null)

    /** A TV's status, or null if it isn't well-formed (7.7). "own", when present, must be a boolean. */
    fun status(o: JSONObject): Status? {
        if (o.optString("t") != "status") return null
        val mic = o.opt("mic"); val used = o.opt("used"); val ts = o.opt("ts")
        if (!Canon.isInt(mic) || !Canon.isInt(used) || !Canon.isInt(ts)) return null
        val m = (mic as Number).toLong(); val u = (used as Number).toLong(); val t = (ts as Number).toLong()
        if (m !in 0..MAX_MIC.toLong() || u !in 0..MAX_INT || t !in 0..MAX_INT) return null
        val own = if (o.has("own")) (o.get("own") as? Boolean ?: return null) else false
        val off = if (o.has("off")) {
            val a = o.get("off") as? JSONArray ?: return null
            (0 until a.length()).map { a.get(it) as? String ?: return null }.filter { it in PERMS }.distinct()
        } else null
        // The TV's last update ("update"): an app that doesn't know a state ignores it.
        val update = (o.opt("update") as? String)?.takeIf { it in UPDATE_STATES }
        return Status(m.toInt(), u, t, own, off, update)
    }

    // ---- the TV's own updates, in its status: what a parent's phone shows under Apps and updates
    /**
     * "needs": a new Muninn is downloaded and waits for Install updates; "ready": it waits for Install on the TV;
     * "installing"; "done": the TV runs the newest; "failed": it didn't go in (the TV tries again the next day).
     */
    val UPDATE_STATES = listOf("needs", "ready", "installing", "done", "failed")

    /** What a parent's phone says for the TV's update [state] ("" when there's nothing to say). */
    fun updateText(state: String?): String = when (state) {
        "needs" -> "Waiting for Install updates. Turn it on in TV permissions."
        "ready" -> "Waiting for Install on the TV"
        "installing" -> "Installing"
        "done" -> "Up to date"
        "failed" -> "The update didn't install. The TV tries again tomorrow."
        else -> ""
    }

    // ---- a parent taken off the TV: their phone stops looking after it
    /**
     * The TV (or the parent who did it) hands a removed parent the signed config that no longer lists them,
     * so their phone stops managing the TV. Apps that don't know "removed" ignore it.
     */
    fun removedMessage(cfg: JSONObject): String = JSONObject().put("t", "removed").put("cfg", cfg).toString()

    /**
     * The config in a "removed" [o], read only if it is a well-formed config for [tvKey], doesn't list [me] as a
     * parent, is signed by one of its parents (one this phone knew, [known], unless null: the TV itself sent it) and
     * isn't older than [minV]. Null otherwise.
     */
    fun removedFrom(tvKey: String, me: String, known: Collection<String>?, minV: Long, o: JSONObject): TvConfig? {
        if (o.optString("t") != "removed") return null
        val c = o.optJSONObject("cfg") ?: return null
        try { check(c) } catch (e: IllegalArgumentException) { return null }
        if (c.opt("tv") != tvKey) return null
        val cfg = try { parse(c) } catch (e: Exception) { return null }
        if (me in cfg.parents || (known != null && cfg.by !in known) || cfg.v < minV) return null
        if (!Canon.verify(cfg.by, CONFIG, c)) return null
        return cfg
    }

    /** Parents in [old] that [new] no longer lists: their phones are told. */
    fun dropped(old: Collection<String>, new: Collection<String>): List<String> = old.filter { it !in new }.distinct()

    /**
     * The TV's permissions to ask for as soon as a parent pairs it, while they're at the TV: the microphone (when
     * it has one) and notifications (Android 13 and newer), only those still off.
     */
    fun setupAsks(hasMic: Boolean, micOn: Boolean, notifyPrompt: Boolean, notifyOn: Boolean): List<String> = buildList {
        if (hasMic && !micOn) add("mic")
        if (notifyPrompt && !notifyOn) add("notify")
    }

    /** What a parent may type for a quick message: anything up to [QUICK_TILE], and an older, longer one only shorter. */
    fun quickEdit(old: String, new: String, max: Int = QUICK_TILE): String {
        val n = new.codePointCount(0, new.length)
        return if (n <= max || n < old.codePointCount(0, old.length)) new else old
    }

    /**
     * A parent's phone takes the TV's level (7.7) when it differs from its own and: the TV says it was set
     * there ([Status.own]) and the TV already has this phone's latest config ([synced], so a change made
     * here that is still on its way wins); or, from a TV without "own", its [Status.ts] is newer than
     * [myMicAt].
     */
    fun adoptMic(s: Status, myMic: Int, myMicAt: Long, synced: Boolean): Boolean =
        s.mic != myMic && (if (s.own) synced else s.ts > myMicAt)

    /**
     * Android won't show its "Allow" box for a permission any more: it was asked for before ([askedBefore])
     * and Android no longer offers a second chance ([rationale] false). Only its app page can turn it on then.
     */
    fun settingsOnly(askedBefore: Boolean, rationale: Boolean): Boolean = askedBefore && !rationale

    // ---- k8 (7.13): a parent asks the TV to turn on one of its permissions
    /** Parent to TV: show Android's prompt (or settings page) for [perm], one of [PERMS]. */
    fun allowMessage(perm: String): String {
        require(perm in PERMS) { "perm" }
        return JSONObject().put("t", "allow").put("p", perm).toString()
    }

    /** The permission an "allow" asks for, or null if [o] isn't a well-formed one. */
    fun allowIn(o: JSONObject): String? {
        if (Canon.keys(o) != setOf("t", "p") || o.opt("t") != "allow") return null
        return (o.opt("p") as? String)?.takeIf { it in PERMS }
    }

    /** Milliseconds a "keep" of [days] means on the TV: 0 for the most (every chat's own limit), else that many days. */
    fun keepMs(days: Int): Long = if (days >= MAX_KEEP) 0L else days.coerceAtLeast(MIN_KEEP) * 86_400_000L

    /**
     * The big buttons in a conversation, left to right: Call (when a parent allows calls), Voice message
     * and Say it (when the TV has a microphone, and a parent turned the remote mic on).
     */
    fun talkButtons(calls: Boolean, mic: Boolean, dictate: Boolean): List<String> = buildList {
        if (calls) add("call")
        if (mic) add("rec")
        if (dictate) add("say")
    }

    /** The button the remote lands on when a conversation opens: the first big button, else the first quick message. */
    fun firstTalkFocus(calls: Boolean, mic: Boolean, dictate: Boolean, quick: List<String>): String? =
        talkButtons(calls, mic, dictate).firstOrNull() ?: quick.firstOrNull()

    /** The recording screen's Send and Stop take OK once [elapsed] ms have gone by. */
    fun recordingArmed(elapsed: Long): Boolean = elapsed >= ARM_MS

    private const val MAX_INT = (1L shl 53) - 1
}
