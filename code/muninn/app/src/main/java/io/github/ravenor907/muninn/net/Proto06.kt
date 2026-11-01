package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature

/**
 * The deterministic parts of the 0.6 protocol that groups need (shared/docs/protocol.md, sections 1 to 3),
 * matching shared/tools/proto06_ref.py byte for byte; shared/vectors/proto06-vectors.json checks it.
 * No Android here, so all of it runs in unit tests.
 */
object Proto06 {
    const val D_GROUP = "huginn-muninn group v1"
    const val MAX_MEMBERS = 50
    const val MAX_CHANNELS = 20
    const val MAX_NAME = 40
    const val MAX_CHANNEL_NAME = 32
    const val MAX_STATE_BYTES = 24 * 1024
    const val MAX_TEXT = 2000
    const val MAX_RECEIPT_IDS = 40
    const val HISTORY = 32
    const val STATES_ANSWER = 16
    const val HELD_PER_GROUP = 50
    const val CODE_MS = 60 * 60_000L
    const val MAIN = "main"
    const val CODE_PREFIX = "MUNINNG1:"
    private val random = SecureRandom()

    // ------------------------------------------------------------------ canonical JSON (2.1)

    class Refused(why: String) : IllegalArgumentException(why)

    /** Text fields: no U+0000..U+001F or U+007F, and at most [limit] code points. */
    fun checkText(s: String, limit: Int? = null): String {
        if (s.any { it.code < 0x20 || it.code == 0x7F }) throw Refused("control character")
        if (limit != null && s.codePointCount(0, s.length) > limit) throw Refused("too long")
        return s
    }

    fun chars(s: String) = s.codePointCount(0, s.length)

    /** Canonical JSON of a JSONObject/JSONArray/Map/List/String/integer/Boolean tree. Throws [Refused]. */
    fun canon(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private val MAX_INT = BigInteger.ONE.shiftLeft(53)

    private fun write(b: StringBuilder, v: Any?) {
        when (v) {
            null -> throw Refused("null")
            is Boolean -> b.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> {
                val n = (v as Number).toLong()
                if (n < 0 || n >= (1L shl 53)) throw Refused("integer out of range")
                b.append(n)
            }
            is BigInteger -> { if (v.signum() < 0 || v >= MAX_INT) throw Refused("integer out of range"); b.append(v.toString()) }
            is String -> { checkText(v); b.append('"').append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append('"') }
            is JSONObject -> writeObject(b, v.keys().asSequence().map { it to v.opt(it) }.toList())
            is Map<*, *> -> writeObject(b, v.entries.map { (k, x) -> (k as? String ?: throw Refused("key")) to x })
            is JSONArray -> { b.append('['); for (i in 0 until v.length()) { if (i > 0) b.append(','); write(b, v.opt(i)) }; b.append(']') }
            is List<*> -> { b.append('['); v.forEachIndexed { i, x -> if (i > 0) b.append(','); write(b, x) }; b.append(']') }
            else -> throw Refused(if (v === JSONObject.NULL) "null" else "type not allowed")
        }
    }

    private fun writeObject(b: StringBuilder, members: List<Pair<String, Any?>>) {
        for ((k, _) in members) if (k.any { it.code > 0x7F }) throw Refused("non-ASCII key")
        b.append('{')
        // Keys are ASCII, so String order is byte order.
        members.sortedBy { it.first }.forEachIndexed { i, (k, x) -> if (i > 0) b.append(','); write(b, k); b.append(':'); write(b, x) }
        b.append('}')
    }

    // ------------------------------------------------------------------ signed objects (2.2)

    fun signedBytes(domain: String, canonWithoutSig: String) = (domain + "\n" + canonWithoutSig).toByteArray(Charsets.UTF_8)

    fun sign(priv: PrivateKey, domain: String, canonWithoutSig: String): String =
        ChatCrypto.b64(Signature.getInstance("SHA256withECDSA").apply { initSign(priv); update(signedBytes(domain, canonWithoutSig)) }.sign())

    fun verify(pubB64: String, domain: String, canonWithoutSig: String, sig: String): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(ChatCrypto.publicFrom(pubB64)); update(signedBytes(domain, canonWithoutSig)); verify(ChatCrypto.b64d(sig))
        }
    } catch (e: Exception) { false }

    fun sha256Hex(s: String) = ChatWire.hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))

    // ------------------------------------------------------------------ ids (2.3)

    fun b64url(b: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun b64urlD(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s)
    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }
    fun newGroupId() = b64url(bytes(16))
    fun newChannelId() = b64url(bytes(8))
    fun newToken() = b64url(bytes(16))
    /** Logical ids of group messages: 32 lowercase hex (Huginn only keeps file-safe Outbox ids). */
    fun newLogicalId() = ChatWire.hex(bytes(16))
    fun isLogicalId(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }

    /** Each member's copy of one group message has its own envelope id. */
    fun envelopeId(gid: String, recipientKey: String) = gid + "-" + sha256Hex(recipientKey).take(8)

    fun lenB64url(s: String): Int = try { b64urlD(s).size } catch (e: Exception) { -1 }
    fun isMailbox(p: String) = p.length == 64 && p.all { it in '0'..'9' || it in 'a'..'f' }

    // ------------------------------------------------------------------ capabilities (1.1)

    /**
     * What this app says it speaks, next to cap:"r1" (which must stay exactly that). Groups and
     * the kids' TV are here; "c1" joins once calls do, so no app offers a call that would only ring out.
     */
    // Tokens Huginn reads come first and the Kids' TV ones last: a Huginn may keep only the first 16.
    const val CAPS = "r1 g1 c1 c2 t1 v1 m1 m2 i1 f1 d1 j1 x1 s1 b1 e1 a1 k1 k2 k3 k4 k5 k6 k7 k8 k9 k10 p1"
    /** Cap j1: group invite links and "Members can invite" (section 17). */
    const val J1 = "j1"
    fun caps(s: String): Set<String> = s.split(' ').filter { it.isNotEmpty() }.toSet()

    // ------------------------------------------------------------------ group state (3.2, 3.3)

    data class Member(val k: String, val n: String, val p: String)
    data class Channel(val id: String, val n: String, val t: String)

    data class State(
        val g: String, val v: Long, val owner: String, val by: String, val name: String,
        val admins: List<String>, val members: List<Member>, val channels: List<Channel>, val ts: Long, val sig: String = "",
        /** Cap d1 (section 16.2): "family" or "work", empty for a Standard group; [days] 0 when it has none (7). */
        val kind: String = "", val days: Int = 0,
        /** Cap j1 (section 17): members (not only admins) may make invite links. Left out when off. */
        val mi: Boolean = false,
    ) {
        private fun tree(withSig: Boolean): Map<String, Any> = LinkedHashMap<String, Any>().apply {
            put("g", g); put("v", v); put("owner", owner); put("by", by); put("name", name); put("admins", admins)
            put("members", members.map { mapOf("k" to it.k, "n" to it.n, "p" to it.p) })
            put("channels", channels.map { mapOf("id" to it.id, "n" to it.n, "t" to it.t) })
            put("ts", ts)
            if (kind.isNotEmpty()) put("kind", kind)
            if (days > 0) put("days", days)
            if (mi) put("mi", true)
            if (withSig) put("sig", sig)
        }
        fun canonUnsigned() = canon(tree(false))
        fun canonSigned() = canon(tree(true))
        val hash: String get() = sha256Hex(canonSigned())
        fun signedText() = D_GROUP + "\n" + canonUnsigned()
        fun json(): JSONObject = JSONObject(canonSigned())
        fun member(k: String) = members.firstOrNull { it.k == k }
        fun has(k: String) = members.any { it.k == k }
        fun isAdmin(k: String) = k in admins
        fun channel(id: String) = channels.firstOrNull { it.id == id }

        /** Signed by [priv] as [by]. */
        fun signedBy(priv: PrivateKey, by: String): State = copy(by = by).let { s -> s.copy(sig = sign(priv, D_GROUP, s.canonUnsigned())) }
        fun verifies() = verify(by, D_GROUP, canonUnsigned(), sig)
    }

    private fun str(o: JSONObject, k: String): String = o.opt(k) as? String ?: throw Refused("$k not text")
    private fun int(o: JSONObject, k: String): Long = when (val x = o.opt(k)) {
        is Int -> x.toLong(); is Long -> x; is BigInteger -> if (x.bitLength() < 63) x.toLong() else throw Refused("$k too big")
        else -> throw Refused("$k not an integer")
    }
    private fun arr(o: JSONObject, k: String): JSONArray = o.opt(k) as? JSONArray ?: throw Refused("$k not a list")
    private fun fields(o: JSONObject, need: Set<String>, what: String) { if (o.keys().asSequence().toSet() != need) throw Refused(what) }

    private val STATE_FIELDS = setOf("g", "v", "owner", "by", "name", "admins", "members", "channels", "ts", "sig")
    /** Cap d1: a Family or Work group's type and limit (section 16.2). */
    private val LIMIT_FIELDS = setOf("kind", "days")
    /** Cap j1: "Members can invite" (section 17.1). */
    private val LINK_FIELDS = setOf("mi")

    /** Reads a state exactly as it came. Throws [Refused] on wrong fields or types (shape and limits: [check]). */
    fun parseState(o: JSONObject): State {
        val have = o.keys().asSequence().toSet()
        if (!have.containsAll(STATE_FIELDS) || !(STATE_FIELDS + LIMIT_FIELDS + LINK_FIELDS).containsAll(have)) throw Refused("fields")
        // Only ever true: off is left out, so a group that never turned it on keeps exactly the state apps without j1 take.
        if (o.has("mi") && o.opt("mi") != true) throw Refused("mi")
        val admins = arr(o, "admins").let { a -> (0 until a.length()).map { a.opt(it) as? String ?: throw Refused("admins") } }
        val members = arr(o, "members").let { a -> (0 until a.length()).map {
            val m = a.opt(it) as? JSONObject ?: throw Refused("member fields")
            fields(m, setOf("k", "n", "p"), "member fields")
            Member(str(m, "k"), str(m, "n"), str(m, "p"))
        } }
        val channels = arr(o, "channels").let { a -> (0 until a.length()).map {
            val c = a.opt(it) as? JSONObject ?: throw Refused("channel fields")
            fields(c, setOf("id", "n", "t"), "channel fields")
            Channel(str(c, "id"), str(c, "n"), str(c, "t"))
        } }
        val kind = if (o.has("kind")) str(o, "kind").also { if (it.isEmpty()) throw Refused("kind") } else ""
        val days = if (o.has("days")) int(o, "days").also { if (it !in 1..Limits.LONGEST) throw Refused("days") }.toInt() else 0
        return State(str(o, "g"), int(o, "v"), str(o, "owner"), str(o, "by"), str(o, "name"), admins, members, channels, int(o, "ts"), str(o, "sig"),
            kind, days, o.has("mi"))
    }

    /** Shape and limits (reference check_state); throws [Refused]. Does not check the signature. */
    fun check(s: State) {
        if (s.g.length != 22 || lenB64url(s.g) != 16) throw Refused("group id")
        if (s.v < 1) throw Refused("version")
        if (s.ts < 0) throw Refused("ts")
        checkText(s.name, MAX_NAME)
        if (s.name.isBlank()) throw Refused("empty name")
        val keys = s.members.map { it.k }
        if (keys != keys.sorted() || keys.toSet().size != keys.size) throw Refused("members not sorted/unique")
        if (keys.size !in 1..MAX_MEMBERS) throw Refused("member count")
        for (m in s.members) {
            checkText(m.k); checkText(m.n, MAX_NAME)
            if (m.p.isNotEmpty() && !isMailbox(m.p)) throw Refused("mailbox")
        }
        if (s.admins != s.admins.toSortedSet().toList() || !keys.containsAll(s.admins)) throw Refused("admins")
        if (s.owner !in s.admins) throw Refused("owner not admin")
        if (s.by !in s.admins) throw Refused("signer not admin in the new state")
        val ids = s.channels.map { it.id }
        if (ids.toSet().size != ids.size || ids.size !in 1..MAX_CHANNELS) throw Refused("channels")
        if (s.channels[0].id != MAIN || s.channels[0].t != "text") throw Refused("main channel first")
        for (c in s.channels) {
            if (c.t != "text" && c.t != "voice") throw Refused("channel fields")
            if (c.id != MAIN && lenB64url(c.id) != 8) throw Refused("channel id")
            checkText(c.n, MAX_CHANNEL_NAME)
        }
        if (s.kind.isNotEmpty() && s.kind !in Limits.KINDS) throw Refused("kind")
        if (s.days != 0 && (s.kind.isEmpty() || s.days !in Limits.DEFAULT..Limits.LONGEST)) throw Refused("days")
        if (s.canonSigned().toByteArray(Charsets.UTF_8).size > MAX_STATE_BYTES) throw Refused("too big")
    }

    /** (accepted, why): reference accept_state. [cur] null means first sight (joining). */
    fun accept(cur: State?, new: State): Pair<Boolean, String> {
        try { check(new) } catch (e: IllegalArgumentException) { return false to "malformed: ${e.message}" }
        if (!new.verifies()) return false to "bad signature"
        if (cur == null) return true to "first state, signed by one of its own admins"
        if (new.g != cur.g) return false to "other group"
        if (new.by !in cur.admins) return false to "signer is not an admin in the current state"
        if (new.owner != cur.owner && new.by != cur.owner) return false to "only the owner can hand over ownership"
        // Cap s1 (section 21): never more than 1000 versions ahead, so no admin can push v out of everyone else's reach.
        if (new.v > cur.v + MAX_AHEAD) return false to "too far ahead"
        if (new.v > cur.v) return true to "higher version"
        if (new.v == cur.v) {
            // At the same version a state its owner signed wins, so the owner can always settle things.
            if (byOwner(new) != byOwner(cur)) return if (byOwner(new)) true to "same version, signed by the owner" else false to "the owner's stands"
            if (new.hash < cur.hash) return true to "same version, lower hash"
        }
        return false to "not newer"
    }

    /** A group state may be at most this many versions ahead of the one it replaces (cap s1). */
    const val MAX_AHEAD = 1000L
    private fun byOwner(s: State) = s.by == s.owner

    /** The conflict rule on its own: higher v, then the one its owner signed, then the lower hash. */
    fun winner(a: State, b: State): State = when {
        a.v != b.v -> if (a.v > b.v) a else b
        byOwner(a) != byOwner(b) -> if (byOwner(a)) a else b
        else -> if (a.hash <= b.hash) a else b
    }

    fun sortedMembers(l: List<Member>) = l.sortedBy { it.k }

    // ------------------------------------------------------------------ group codes (3.5)

    data class Code(val g: String, val gn: String, val a: String, val an: String, val p: String, val i: String, val x: Long)

    fun code(c: Code): String = CODE_PREFIX + b64url(JSONObject().put("g", c.g).put("gn", c.gn).put("a", c.a).put("an", c.an)
        .put("p", c.p).put("i", c.i).put("x", c.x).toString().toByteArray(Charsets.UTF_8))

    /**
     * Cap j1 (section 17): an invite link is a group code ([code]) in the add page's address after "#", which browsers
     * never send to the server. [Code.a] is the admin who counts its uses; a link that never expires says so in 100 years.
     */
    fun link(c: Code): String = Contacts.PAGE + code(c)

    /** A link's id: shown in lists and used to revoke it, so the token itself never goes past the counting admin. */
    fun linkId(token: String): String = sha256Hex("link|$token").take(16)

    const val MAX_LINK_USES = 100
    const val MAX_LINKS = 50

    /**
     * Whether a link still lets someone in (17.3), decided only on the admin who counts it: not revoked, not past [x]
     * (ms, 0 never), fewer than [max] uses (0 no limit), and its maker still in the group and allowed to invite.
     */
    fun linkOk(x: Long, max: Int, uses: Int, off: Boolean, makerIn: Boolean, makerMay: Boolean, now: Long): Boolean =
        !off && (x == 0L || x > now) && (max == 0 || uses < max) && makerIn && makerMay

    /** Expiry choices for a link (ms; 0 never) and use limits (0 no limit). */
    val LINK_TTL = longArrayOf(30 * 60_000L, 3_600_000L, 6 * 3_600_000L, 12 * 3_600_000L, 86_400_000L, 7 * 86_400_000L, 0L)
    const val LINK_TTL_DEFAULT = 7 * 86_400_000L
    val LINK_MAX = intArrayOf(1, 5, 10, 25, 50, 100, 0)
    /** "Never" in a code's x (seconds): 100 years on, so no app joining by it calls it expired. */
    const val NEVER_S = 100L * 365 * 86_400

    /** Finds a group code anywhere in [text] (a pasted link too). Null if there is none or it's broken (expiry is the caller's). */
    fun parseCode(raw: String): Code? {
        val text = raw.replace(Regex("MUNINNG1%3A", RegexOption.IGNORE_CASE), CODE_PREFIX)
        val at = text.indexOf(CODE_PREFIX)
        if (at < 0) return null
        val body = text.substring(at + CODE_PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return try {
            val o = JSONObject(String(b64urlD(body), Charsets.UTF_8))
            val c = Code(o.getString("g"), o.optString("gn").take(MAX_NAME), o.getString("a"), o.optString("an").take(MAX_NAME),
                o.optString("p"), o.getString("i"), o.optLong("x"))
            ChatCrypto.publicFrom(c.a)
            if (lenB64url(c.g) != 16 || lenB64url(c.i) != 16 || (c.p.isNotEmpty() && !isMailbox(c.p))) null else c
        } catch (e: Exception) { null }
    }
}
