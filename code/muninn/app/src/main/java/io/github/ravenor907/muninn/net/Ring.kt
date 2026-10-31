package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.Base64

/**
 * Caps b1 and e1 (shared/docs/protocol-0.6.md section 23). Plain Kotlin (no Android) so it can be tested off the phone;
 * shared/tools/e1b1_ref.py is the reference.
 *
 *  - e1: over the relays, an envelope is wrapped for the recipient's chat key and one of its day keys ("wrap keys",
 *    a new one every day, each kept 40 days). Once a day key is gone, a stolen chat key and saved relay traffic no
 *    longer show who sent what. Room "file" goes through the ratchet.
 *  - b1: each device has a ring key outside the vault. A caller also sends a tiny envelope sealed to it (a call id and a
 *    time, nothing more) to the ring mailbox, so a locked phone can ring "Incoming call" without opening anything else.
 *  - Both public halves travel in receipts ("rk", "rp", "wk", "wt"), only to apps whose caps say b1 or e1.
 */
object RingWire {
    const val RING_CAP = "b1"
    const val WRAP_CAP = "e1"
    private val WRAP_DOMAIN = "huginn-muninn wrap v2".toByteArray()
    private val RING_DOMAIN = "huginn-muninn ring v1".toByteArray()
    const val DAY_S = 86_400L
    /** A sender uses a day key at most this long after it was made. */
    const val WRAP_USE_S = 7 * DAY_S
    /** The recipient keeps a day key's private half this long: 7 days of use, 31 on a relay, and a margin. */
    const val WRAP_KEEP_S = 40 * DAY_S
    /** A new day key once the newest is this old. */
    const val WRAP_NEW_S = DAY_S
    /** A day key's time may be at most this far ahead of the receiver's clock. */
    const val WRAP_AHEAD_S = DAY_S
    /** A ring, like an offer (5.2): no older than this, no further ahead than [RING_AHEAD_MS]. */
    const val RING_OLD_MS = 60_000L
    const val RING_AHEAD_MS = 120_000L
    private val P256_PREFIX = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200")
    private val CALL_ID = Regex("^[A-Za-z0-9_-]{22}$")
    private val MAILBOX = Regex("^[0-9a-f]{64}$")
    private val random = SecureRandom()

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    /** A P-256 public key: standard base64 of its 91-byte SubjectPublicKeyInfo with an uncompressed point. */
    fun keyOk(text: Any?): Boolean {
        if (text !is String) return false
        val der = try { Base64.getDecoder().decode(text) } catch (e: Exception) { return false }
        if (der.size != 91 || !der.copyOfRange(0, P256_PREFIX.size).contentEquals(P256_PREFIX) || der[26].toInt() != 4) return false
        return try { (ChatCrypto.publicFrom(text) as? ECPublicKey)?.params?.curve?.field?.fieldSize == 256 } catch (e: Exception) { false }
    }

    /** A day key's id on the wire: the first 16 hex characters of SHA-256 of its SubjectPublicKeyInfo. */
    fun wrapId(pubB64: String): String = ChatWire.hex(sha256(ChatCrypto.b64d(pubB64))).take(16)

    fun wrapKey(k1: ByteArray, k2: ByteArray): ByteArray = sha256(WRAP_DOMAIN, k1, k2)

    /** The e1 wrapping {"e","w","n","d"} of [body] for [staticPub] (their chat key) and [dayPub] (one of their day keys). */
    fun sealWrap(staticPub: String, dayPub: String, body: ByteArray, eph: KeyPair = ChatCrypto.newKeyPair(),
                 nonce: ByteArray = ByteArray(12).also { random.nextBytes(it) }): JSONObject {
        val k1 = ChatCrypto.sharedKey(eph.private, eph.public, ChatCrypto.publicFrom(staticPub))
        val k2 = ChatCrypto.sharedKey(eph.private, eph.public, ChatCrypto.publicFrom(dayPub))
        val s = ChatCrypto.sealBytes(wrapKey(k1, k2), body, null, nonce)
        return JSONObject().put("e", ChatCrypto.b64(eph.public.encoded)).put("w", wrapId(dayPub)).put("n", s.nonce).put("d", s.data)
    }

    /** Opens an e1 wrapping with my chat key and the day key its "w" names ([day], null when that one is gone). */
    fun openWrap(o: JSONObject, myStatic: KeyPair, day: (String) -> KeyPair?): ByteArray? = try {
        val d = day(o.getString("w"))
        if (d == null) null else {
            val e = ChatCrypto.publicFrom(o.getString("e"))
            val k1 = ChatCrypto.sharedKey(myStatic.private, myStatic.public, e)
            val k2 = ChatCrypto.sharedKey(d.private, d.public, e)
            ChatCrypto.openBytes(wrapKey(k1, k2), ChatCrypto.Sealed(o.getString("n"), o.getString("d")))
        }
    } catch (e: Exception) { null }

    fun ringKey(shared: ByteArray): ByteArray = sha256(RING_DOMAIN, shared)

    /** The ring envelope {"e","n","d"}: [plain] sealed to a ring key. */
    fun sealRing(ringPub: String, plain: ByteArray, eph: KeyPair = ChatCrypto.newKeyPair(),
                 nonce: ByteArray = ByteArray(12).also { random.nextBytes(it) }): JSONObject {
        val k = ChatCrypto.sharedKey(eph.private, eph.public, ChatCrypto.publicFrom(ringPub))
        val s = ChatCrypto.sealBytes(ringKey(k), plain, null, nonce)
        return JSONObject().put("e", ChatCrypto.b64(eph.public.encoded)).put("n", s.nonce).put("d", s.data)
    }

    fun openRing(o: JSONObject, ring: KeyPair): ByteArray? = try {
        val k = ChatCrypto.sharedKey(ring.private, ring.public, ChatCrypto.publicFrom(o.getString("e")))
        ChatCrypto.openBytes(ringKey(k), ChatCrypto.Sealed(o.getString("n"), o.getString("d")))
    } catch (e: Exception) { null }

    /** The ring plaintext: {"t":"ring","call":<call id>,"ts":<ms>}. Nothing else: no name, no key. */
    fun ringPlain(callId: String, ts: Long): String = JSONObject().put("t", "ring").put("call", callId).put("ts", ts).toString()

    /** Well-formed: t "ring", call a call id (22 base64url characters), ts an integer 0 <= ts < 2^53. Other fields are ignored. */
    fun ringOk(p: JSONObject): Boolean {
        if (p.opt("t") != "ring") return false
        val c = p.opt("call")
        val ts = p.opt("ts")
        return c is String && CALL_ID.matches(c) && Rich.isTs(ts)
    }

    fun ringFresh(ts: Long, now: Long) = ts >= now - RING_OLD_MS && ts <= now + RING_AHEAD_MS

    /** The content of the relay event for a ring envelope: padded like every relay envelope (section 20). */
    fun ringContent(ringPub: String, callId: String, ts: Long): String =
        Base64.getEncoder().encodeToString(EnvelopePad.pad(sealRing(ringPub, ringPlain(callId, ts).toByteArray()).toString()).toByteArray())

    /** A ring envelope's content opened with [ring]: (call id, ts), or null when it isn't a good one. */
    fun openRingContent(content: String, ring: KeyPair): Pair<String, Long>? = try {
        val o = JSONObject(String(Base64.getDecoder().decode(content)))
        val p = openRing(o, ring)?.let { JSONObject(String(it, Charsets.UTF_8)) }
        if (p == null || !ringOk(p)) null else p.getString("call") to (p.get("ts") as Number).toLong()
    } catch (e: Exception) { null }

    /** What a receipt's "rk", "rp", "wk", "wt" say; each half null when absent or malformed. */
    data class Announce(val rk: String?, val rp: String?, val wk: String?, val wt: Long)

    fun announceOf(r: JSONObject, nowS: Long): Announce {
        val ringOk = keyOk(r.opt("rk")) && (r.opt("rp") as? String)?.let { MAILBOX.matches(it) } == true
        val wt = r.opt("wt")
        val wrapOk = keyOk(r.opt("wk")) && Rich.isTs(wt) && (wt as Number).toLong().let { nowS - it < WRAP_KEEP_S && it - nowS <= WRAP_AHEAD_S }
        return Announce(if (ringOk) r.getString("rk") else null, if (ringOk) r.getString("rp") else null,
            if (wrapOk) r.getString("wk") else null, if (wrapOk) (wt as Number).toLong() else 0L)
    }

    /** A sender wraps with a day key only within a week of when it was made. */
    fun mayUse(wt: Long, nowS: Long) = nowS - wt <= WRAP_USE_S && wt - nowS <= WRAP_AHEAD_S
    /** Kept while younger than [WRAP_KEEP_S]; one stamped more than [WRAP_AHEAD_S] ahead (a clock set back since) goes. */
    fun keep(t: Long, nowS: Long) = nowS - t < WRAP_KEEP_S && t - nowS <= WRAP_AHEAD_S
    fun rotateDue(newest: Long?, nowS: Long) = newest == null || nowS - newest >= WRAP_NEW_S || newest - nowS > WRAP_AHEAD_S

    /** After restoring a backup, a relay envelope for a day key not here yet waits this long for it (it may come again). */
    const val RESTORE_WAIT_MS = 7 * DAY_S * 1000

    /**
     * A wrapping for day key [known] false didn't open: true while it should stay unread on the relays (and in the
     * locked store) so a later copy can still be opened, which is only within [RESTORE_WAIT_MS] of a restore.
     * It is never shown either way.
     */
    fun waitForDayKey(known: Boolean, restoredAtMs: Long, nowMs: Long) =
        !known && restoredAtMs > 0 && nowMs - restoredAtMs < RESTORE_WAIT_MS

    /** Room "file" goes through the ratchet for a contact whose app says e1 (and ratchets). */
    fun fileRatchet(room: String, c: Contact?) = room == "file" && c?.ratchet == true && WRAP_CAP in c.caps
}

/** This device's day keys (e1): a new one every day, each kept [RingWire.WRAP_KEEP_S]. Saved (encrypted) through [write]. */
class WrapKeys(private val read: () -> String?, private val write: (String) -> Unit) {
    class Day(val id: String, val pub: String, val priv: String, val t: Long)
    private val lock = Any()
    private var days: MutableList<Day>? = null

    private fun all(): MutableList<Day> = days ?: try {
        JSONArray(read() ?: "[]").let { a -> MutableList(a.length()) { a.getJSONObject(it).let { o -> Day(o.getString("i"), o.getString("k"), o.getString("p"), o.getLong("t")) } } }
    } catch (e: Exception) { mutableListOf() }.also { days = it }

    private fun save(l: List<Day>) {
        try { write(JSONArray().apply { l.forEach { put(JSONObject().put("i", it.id).put("k", it.pub).put("p", it.priv).put("t", it.t)) } }.toString()) } catch (_: Exception) {}
    }

    /**
     * The day key to announce now: a new one when the newest is a day old. Old ones past keeping go, and so do ones
     * stamped more than a day ahead (the clock was set back), so they never count as the newest: a clock set back
     * makes at most one new key, not one on every call.
     */
    fun current(nowS: Long = System.currentTimeMillis() / 1000): Day = synchronized(lock) {
        val l = all()
        val before = l.size
        l.removeAll { !RingWire.keep(it.t, nowS) }
        val newest = l.maxByOrNull { it.t }
        if (newest != null && !RingWire.rotateDue(newest.t, nowS)) { if (l.size != before) save(l); return newest }
        add(l, nowS)
    }

    /** A new day key now, whatever the newest's age (after restoring a backup). */
    fun fresh(nowS: Long = System.currentTimeMillis() / 1000): Day = synchronized(lock) {
        val l = all()
        l.removeAll { !RingWire.keep(it.t, nowS) }
        add(l, nowS)
    }

    private fun add(l: MutableList<Day>, nowS: Long): Day {
        val kp = ChatCrypto.newKeyPair()
        val pub = ChatCrypto.b64(kp.public.encoded)
        val d = Day(RingWire.wrapId(pub), pub, ChatCrypto.b64(kp.private.encoded), nowS)
        l.add(d)
        save(l)
        return d
    }

    /** The key pair a wrapping's "w" names, while it's kept. */
    fun find(id: String, nowS: Long = System.currentTimeMillis() / 1000): KeyPair? = synchronized(lock) {
        all().firstOrNull { it.id == id && RingWire.keep(it.t, nowS) }?.let { KeyPair(ChatCrypto.publicFrom(it.pub), ChatCrypto.privateFrom(it.priv)) }
    }

    /** Day keys past keeping are deleted (the chat's minute loop). */
    fun prune(nowS: Long = System.currentTimeMillis() / 1000) = synchronized(lock) {
        val l = all()
        if (l.removeAll { !RingWire.keep(it.t, nowS) }) save(l)
    }

    fun clear() = synchronized(lock) { days = mutableListOf(); save(emptyList()) }

    /** Erase everything and Move: nothing in memory either; the file itself is deleted by the caller. */
    fun drop() = synchronized(lock) { days = mutableListOf() }
}

/** What each contact announced (b1, e1): their ring key and ring mailbox, their newest day key. Saved (encrypted) through [write]. */
class PeerKeys(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Keys(val rk: String? = null, val rp: String? = null, val wk: String? = null, val wt: Long = 0)
    private val lock = Any()
    private var map: MutableMap<String, Keys>? = null

    private fun all(): MutableMap<String, Keys> = map ?: try {
        val o = JSONObject(read() ?: "{}")
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Keys(it.optString("rk").ifEmpty { null }, it.optString("rp").ifEmpty { null },
            it.optString("wk").ifEmpty { null }, it.optLong("wt")) } }.toMutableMap()
    } catch (e: Exception) { mutableMapOf() }.also { map = it }

    private fun save(m: Map<String, Keys>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONObject().put("rk", v.rk ?: "").put("rp", v.rp ?: "").put("wk", v.wk ?: "").put("wt", v.wt))
        try { write(o.toString()) } catch (_: Exception) {}
    }

    /** Takes what an authentic receipt from [key] announced: the ring key as it says, a day key only when newer. */
    fun take(key: String, a: RingWire.Announce) = synchronized(lock) {
        if (a.rk == null && a.wk == null) return@synchronized
        val m = all()
        val old = m[key] ?: Keys()
        val now = Keys(a.rk ?: old.rk, if (a.rk != null) a.rp else old.rp,
            if (a.wk != null && a.wt > old.wt) a.wk else old.wk, if (a.wk != null && a.wt > old.wt) a.wt else old.wt)
        if (now != old) { m[key] = now; save(m) }
    }

    fun get(key: String): Keys? = synchronized(lock) { all()[key] }

    /** Their day key to wrap with now, or null (none yet, or older than a week: then the wrapping is the plain one). */
    fun wrapFor(key: String, nowS: Long = System.currentTimeMillis() / 1000): String? =
        get(key)?.takeIf { it.wk != null && RingWire.mayUse(it.wt, nowS) }?.wk

    fun forget(key: String) = synchronized(lock) { if (all().remove(key) != null) save(all()) }
}

/**
 * This device's ring key (b1): a P-256 key pair and a Nostr secret for the ring mailbox, kept in app-private storage
 * outside the vault, never in an OS key store. With it a locked phone can tell a call is coming, and nothing else:
 * someone holding the phone learns only that calls came and when.
 */
class RingKey(private val file: File) {
    private class Held(val keys: KeyPair, val pub: String, val secret: ByteArray, val mailbox: String, val until: Long = 0L)
    /** The current ring key, and the ones it replaced (after a block), each listened on until its [Held.until]. */
    private class All(val now: Held, val old: List<Held>)
    @Volatile private var held: All? = null

    private fun parse(o: JSONObject): Held {
        val pub = o.getString("k")
        val secret = Schnorr.unhex(o.getString("n"))
        return Held(KeyPair(ChatCrypto.publicFrom(pub), ChatCrypto.privateFrom(o.getString("s"))), pub, secret,
            Schnorr.hex(Schnorr.publicKey(secret)), o.optLong("u"))
    }

    private fun load(): All = held ?: synchronized(this) {
        held ?: (try {
            val o = JSONObject(file.readText())
            val old = o.optJSONArray("old")?.let { a -> (0 until a.length()).mapNotNull { try { parse(a.getJSONObject(it)) } catch (e: Exception) { null } } }.orEmpty()
            All(parse(o), old)
        } catch (e: Exception) { null } ?: All(make(), emptyList()).also { save(it) }).also { held = it }
    }

    private fun make(): Held {
        val kp = ChatCrypto.newKeyPair()
        val secret = Schnorr.newSecret()
        return Held(kp, ChatCrypto.b64(kp.public.encoded), secret, Schnorr.hex(Schnorr.publicKey(secret)))
    }

    private fun json(h: Held) = JSONObject().put("k", h.pub).put("s", ChatCrypto.b64(h.keys.private.encoded)).put("n", Schnorr.hex(h.secret))

    private fun save(a: All) {
        val o = json(a.now)
        if (a.old.isNotEmpty()) o.put("old", JSONArray().apply { a.old.forEach { put(json(it).put("u", it.until)) } })
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    /** The public half, as it goes in "rk". */
    val publicKey: String get() = load().now.pub
    /** The ring mailbox ("rp"): where callers send ring envelopes. */
    val mailbox: String get() = load().now.mailbox
    /** The ring mailbox's Nostr secret, to sign in to relays that ask (NIP-42). */
    val nostrSecret: ByteArray get() = load().now.secret

    private fun live(nowMs: Long): List<Held> = load().let { a -> listOf(a.now) + a.old.filter { it.until > nowMs } }

    /** Every ring mailbox listened on now: the current one, and a replaced one for [OLD_MS] after it was replaced. */
    fun mailboxes(nowMs: Long = System.currentTimeMillis()): List<String> = live(nowMs).map { it.mailbox }
    /** Their Nostr secrets, the same order. */
    fun nostrSecrets(nowMs: Long = System.currentTimeMillis()): List<ByteArray> = live(nowMs).map { it.secret }

    /** A ring envelope's content opened: (call id, ts), or null. */
    fun open(content: String, nowMs: Long = System.currentTimeMillis()): Pair<String, Long>? =
        live(nowMs).firstNotNullOfOrNull { RingWire.openRingContent(content, it.keys) }

    /**
     * Someone was blocked: a new ring key and ring mailbox, so what they hold no longer rings this phone. The old
     * mailbox is still listened on for [OLD_MS] (calls from the others until the new key reaches them).
     */
    fun renew(nowMs: Long = System.currentTimeMillis()) = synchronized(this) {
        val a = load()
        val prev = Held(a.now.keys, a.now.pub, a.now.secret, a.now.mailbox, nowMs + OLD_MS)
        val next = All(make(), a.old.filter { it.until > nowMs } + prev)
        save(next)
        held = next
    }

    /** Replaced ring keys past [OLD_MS] go. True when one went (listen again without it). */
    fun prune(nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(this) {
        val a = load()
        val keep = a.old.filter { it.until > nowMs }
        if (keep.size == a.old.size) return@synchronized false
        val next = All(a.now, keep)
        save(next)
        held = next
        true
    }

    /** Erase everything and Move: a new ring key next time. */
    fun forget() = synchronized(this) { held = null; file.delete(); File(file.path + ".tmp").delete() }

    companion object {
        /** How long a ring mailbox replaced after a block is still listened on. */
        const val OLD_MS = 2 * 86_400_000L
    }
}
