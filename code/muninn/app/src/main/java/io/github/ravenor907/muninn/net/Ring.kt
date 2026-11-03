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
 * Caps b1 and e1 (shared/docs/protocol.md section 23). Plain Kotlin (no Android) so it can be tested off the phone;
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

    /** What a receipt's "rk", "rp", "wk", "wt" (and "nk", "np", cap n2) say; each half null when absent or malformed. */
    data class Announce(val rk: String?, val rp: String?, val wk: String?, val wt: Long, val nk: String? = null, val np: String? = null)

    fun announceOf(r: JSONObject, nowS: Long): Announce {
        val ringOk = keyOk(r.opt("rk")) && (r.opt("rp") as? String)?.let { MAILBOX.matches(it) } == true
        val wt = r.opt("wt")
        val wrapOk = keyOk(r.opt("wk")) && Rich.isTs(wt) && (wt as Number).toLong().let { nowS - it < WRAP_KEEP_S && it - nowS <= WRAP_AHEAD_S }
        val nudgeOk = keyOk(r.opt("nk")) && (r.opt("np") as? String)?.let { MAILBOX.matches(it) } == true
        return Announce(if (ringOk) r.getString("rk") else null, if (ringOk) r.getString("rp") else null,
            if (wrapOk) r.getString("wk") else null, if (wrapOk) (wt as Number).toLong() else 0L,
            if (nudgeOk) r.getString("nk") else null, if (nudgeOk) r.getString("np") else null)
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

/**
 * Caps n1 and n2 (protocol.md 23.3): nudges. Sending a real message (text, photo, voice or video message, file offer,
 * group invite) to someone whose app says n1 or n2 also sends one tiny sealed envelope that a locked phone can open
 * without its vault, at most one a minute per person (per person and chat with n2). A locked phone notifies only for
 * these: never for receipts, check-ins, presence or anything else that reaches its inbox.
 *  - n1: {"t":"nudge","ts":...} sealed to their ring key, to their ring mailbox. Contacts only.
 *  - n2: the same with "from" (the sender's name), "chat" ("" for 1:1, else the group's name) and "kind", sealed to
 *    their nudge key ([NudgeKey], which contacts and group peers get) and sent to their nudge mailbox; to a contact
 *    whose nudge key hasn't come yet, sealed to their ring key instead.
 */
object NudgeWire {
    const val CAP = "n1"
    const val RICH_CAP = "n2"
    /** At most one nudge per recipient (per recipient and chat, n2) in this long. */
    const val EVERY_MS = 60_000L
    /** "from" and "chat" are at most this many characters. */
    const val NAME_MAX = 40
    /** What a message is. Anything else reads as "msg". */
    val KINDS = listOf("msg", "photo", "voice", "video", "file", "invite")

    /** Whether something sent in [room] with plaintext [plain] is a real message (and so is worth a nudge). */
    fun counts(room: String, plain: String): Boolean = when (room) {
        "private", "rich", "photo", "gmsg", "gphoto" -> true
        "file" -> tOf(plain) == "offer"
        "group" -> tOf(plain) == "invite"
        else -> false
    }

    private fun tOf(plain: String): String = try { JSONObject(plain).optString("t") } catch (e: Exception) { "" }

    /** The kind of a real message in [room] (a photo room's own kind comes from [blobKind]). */
    fun kindOf(room: String): String = when (room) {
        "photo", "gphoto" -> "photo"
        "file" -> "file"
        "group" -> "invite"
        else -> "msg"
    }

    /** A photo room message: a voice message, a video message, a small file, or a photo (a GIF too). */
    fun blobKind(voiceMs: Int, videoMs: Int, file: String): String = when {
        voiceMs > 0 -> "voice"
        videoMs > 0 -> "video"
        file.isNotEmpty() -> "file"
        else -> "photo"
    }

    /** A name in a nudge: control and format characters taken out, trimmed, at most [NAME_MAX] characters. Not a string: "". */
    fun clean(x: Any?): String {
        if (x !is String) return ""
        val b = StringBuilder()
        var i = 0
        while (i < x.length) {
            val cp = x.codePointAt(i)
            i += Character.charCount(cp)
            val t = Character.getType(cp)
            if (Character.isISOControl(cp) || t == Character.FORMAT.toInt() || t == Character.SURROGATE.toInt() ||
                t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt()) continue
            b.appendCodePoint(cp)
        }
        var s = b.toString().trim()
        if (s.length > NAME_MAX) {
            s = s.take(NAME_MAX)
            if (Character.isHighSurrogate(s.last())) s = s.dropLast(1)
            s = s.trim()
        }
        return s
    }

    /**
     * What a nudge says. An n1 nudge names nobody and is a "msg". [known]: it opened with the ring key, which only
     * contacts get, so its "from" is a contact's own word. One that opened with the nudge key may come from any group
     * peer, so it shows no name at all, neither a person's nor a group's ([anonymous]).
     */
    data class Nudge(val ts: Long, val from: String = "", val chat: String = "", val kind: String = "msg", val known: Boolean = true)

    /**
     * Round 15: a nudge from the nudge key names nothing the sender chose: "from" and the group's name ("chat") both go,
     * so no one in a group can make a locked phone show "Mom". It reads [GROUP_TITLE], with a count.
     */
    fun anonymous(n: Nudge): Nudge = n.copy(from = "", chat = "", known = false)

    /** The fixed title of a nudge naming nobody (the nudge key). */
    const val GROUP_TITLE = "Group message"

    /** A nudge older than this (by its own ts) is a replay or long gone: dropped. */
    const val MAX_AGE_MS = 2 * 86_400_000L

    /** Fresh enough to notify: not older than [MAX_AGE_MS] by its ts (a clock ahead is fine). */
    fun fresh(n: Nudge, nowMs: Long): Boolean = n.ts >= nowMs - MAX_AGE_MS

    /** The n1 nudge plaintext: {"t":"nudge","ts":<ms>}. Nothing else. */
    fun plain(ts: Long): String = JSONObject().put("t", "nudge").put("ts", ts).toString()

    /** The n2 nudge plaintext: {"t":"nudge","ts","from","chat","kind"}, the names cleaned as a receiver cleans them. */
    fun richPlain(ts: Long, from: String, chat: String, kind: String): String = JSONObject().put("t", "nudge").put("ts", ts)
        .put("from", clean(from)).put("chat", clean(chat)).put("kind", if (kind in KINDS) kind else "msg").toString()

    /** Well-formed: t "nudge" and ts an integer 0 <= ts < 2^53. Other fields are ignored here. */
    fun ok(p: JSONObject): Boolean = p.opt("t") == "nudge" && Rich.isTs(p.opt("ts"))

    /** A well-formed nudge, n1 or n2: names cleaned, an unknown or missing kind is "msg". */
    fun read(p: JSONObject): Nudge? {
        if (!ok(p)) return null
        val k = p.opt("kind")
        return Nudge((p.get("ts") as Number).toLong(), clean(p.opt("from")), clean(p.opt("chat")), if (k is String && k in KINDS) k else "msg")
    }

    /** Sealed like a ring envelope and padded like every relay envelope (section 20). */
    private fun seal(pub: String, plain: String): String =
        Base64.getEncoder().encodeToString(EnvelopePad.pad(RingWire.sealRing(pub, plain.toByteArray()).toString()).toByteArray())

    /** An n1 nudge's relay event content, sealed to their ring key. */
    fun content(ringPub: String, ts: Long): String = seal(ringPub, plain(ts))

    /** An n2 nudge's relay event content, sealed to their nudge key (or their ring key, see [route]). */
    fun richContent(pub: String, ts: Long, from: String, chat: String, kind: String): String = seal(pub, richPlain(ts, from, chat, kind))

    /** A nudge's content opened with [keys] (a ring key or a nudge key): what it says, or null when it isn't a good nudge. */
    fun openContent(content: String, keys: KeyPair): Nudge? = try {
        val o = JSONObject(String(Base64.getDecoder().decode(content)))
        RingWire.openRing(o, keys)?.let { read(JSONObject(String(it, Charsets.UTF_8))) }
    } catch (e: Exception) { null }

    /** An event for the ring mailbox that isn't a ring: a nudge only while [locked] (unlocked, the message itself notifies). */
    fun ringMailbox(locked: Boolean, content: String, ring: RingKey, nowMs: Long = System.currentTimeMillis()): Nudge? =
        if (locked) ring.nudge(content)?.takeIf { fresh(it, nowMs) } else null

    /** An event for the nudge mailbox: only ever a nudge (never a ring), and only while [locked]. */
    fun nudgeMailbox(locked: Boolean, content: String, key: NudgeKey, nowMs: Long = System.currentTimeMillis()): Nudge? =
        if (locked) key.nudge(content)?.takeIf { fresh(it, nowMs) }?.let { anonymous(it) } else null

    /** Where a nudge goes: the key it's sealed to, the mailbox, and whether it's the n2 form. */
    data class Route(val pub: String, val mailbox: String, val rich: Boolean)

    /**
     * A contact: their ring key and ring mailbox (the n2 form when they say n2), so their phone knows the nudge came
     * from a contact and may show my name (round 14: what opens with the nudge key names nobody). A group-only peer,
     * or a contact whose ring key hasn't come yet: their nudge key and nudge mailbox (n2 only). Null: no nudge
     * (neither cap, or no key from them yet).
     */
    fun route(caps: Set<String>, groupOnly: Boolean, k: PeerKeys.Keys?): Route? {
        if (k == null) return null
        val rich = RICH_CAP in caps
        val rk = k.rk
        val rp = k.rp
        if (!groupOnly && (rich || CAP in caps) && rk != null && rp != null) return Route(rk, rp, rich)
        val nk = k.nk
        val np = k.np
        if (rich && nk != null && np != null) return Route(nk, np, true)
        return null
    }

    /** What [Gate] counts a nudge under: the person, and with n2 the chat too (a group's nudge isn't held back by a 1:1 one). */
    fun gateKey(to: String, rich: Boolean, chat: String): String = if (rich) to + "\n" + chat else to

    /** What a locked nudge's notification shows: Settings › Notifications › What notifications show. */
    enum class Show { NOTHING, NAME, MESSAGE }

    /** One notification per sender and chat; with Nothing, one for everything. Those naming nobody ([Nudge.known] false): one for all. */
    fun groupKey(show: Show, n: Nudge): String = when {
        show == Show.NOTHING -> ""
        !n.known -> "?"
        else -> n.from + "\n" + n.chat
    }

    /**
     * Its title: who it's from, and the group ("from · chat"). "Muninn" with Nothing, or for an n1 nudge naming nobody.
     * One from the nudge key ([Nudge.known] false) is always [GROUP_TITLE], whatever it claims.
     */
    fun title(show: Show, n: Nudge): String = when {
        show == Show.NOTHING -> "Muninn"
        !n.known -> GROUP_TITLE
        n.from.isNotEmpty() && n.chat.isNotEmpty() -> n.from + " · " + n.chat
        n.from.isNotEmpty() -> n.from
        n.chat.isNotEmpty() -> n.chat
        else -> "Muninn"
    }

    /** Its text: what came (the message itself can't be opened while locked), or how many once more came. */
    fun text(show: Show, kind: String, count: Int): String = when {
        count > 1 -> "$count new messages"
        show == Show.NOTHING -> "New message"
        else -> when (kind) {
            "photo" -> "Photo"
            "voice" -> "Voice message"
            "video" -> "Video message"
            "file" -> "File"
            "invite" -> "Group invite"
            else -> "New message"
        }
    }

    /**
     * The chat a tapped locked notification opens once unlocked: the one group named [chat], or for 1:1 the one contact
     * named [from] ([contacts] and [groups] are key to name). Null (the chat list) when none or more than one match.
     */
    fun match(from: String, chat: String, contacts: List<Pair<String, String>>, groups: List<Pair<String, String>>): String? = when {
        chat.isNotEmpty() -> groups.filter { clean(it.second) == chat }
        from.isNotEmpty() -> contacts.filter { clean(it.second) == from }
        else -> emptyList<Pair<String, String>>()
    }.singleOrNull()?.first

    /**
     * Round 14: what a locked device does with nudges that name nobody (from the nudge key, so maybe not a contact).
     * Round 15: they all share one "Group message" notification that counts up ([anonymous] drops every name), and
     * alert at most once per [ALERT_EVERY_MS] in all, whatever they claim to be, so no one in a group can buzz a locked
     * phone over and over. Nudges from contacts ([Nudge.known]) alert as before.
     */
    class LockedLimit(private val alertEveryMs: Long = ALERT_EVERY_MS) {
        private var lastAlert = Long.MIN_VALUE / 2
        /** What to show: [n] as it will read, the notification's key, and whether it may sound. */
        data class Take(val n: Nudge, val key: String, val alert: Boolean)
        @Synchronized
        fun take(show: Show, n: Nudge, nowMs: Long): Take {
            if (n.known) return Take(n, groupKey(show, n), true)
            // Never a name the sender chose, even one that slipped past [anonymous].
            val shown = n.copy(from = "", chat = "")
            val key = groupKey(show, shown)
            val alert = nowMs - lastAlert >= alertEveryMs
            if (alert) lastAlert = nowMs
            return Take(shown, key, alert)
        }
        /** Unlocked: start over. */
        @Synchronized
        fun clear() { lastAlert = Long.MIN_VALUE / 2 }
        companion object {
            const val ALERT_EVERY_MS = 2 * 60_000L
        }
    }

    /**
     * Round 14 (caps known to lack nudges): someone whose app can't nudge this device while it's locked. A contact
     * whose known caps have neither n1 nor n2 (an app before 1.33, an older Kids' TV), or a group-only peer without n2.
     * Unknown caps (empty) count as able: only senders known to be older count.
     */
    fun cantNudge(caps: Set<String>, groupOnly: Boolean): Boolean =
        caps.isNotEmpty() && if (groupOnly) RICH_CAP !in caps else (CAP !in caps && RICH_CAP !in caps)

    /** At most one nudge per [gateKey] per [EVERY_MS]. */
    class Gate(private val everyMs: Long = EVERY_MS) {
        private val last = HashMap<String, Long>()
        /** True when a nudge to [key] may go now (and counts it). */
        @Synchronized
        fun take(key: String, nowMs: Long): Boolean {
            val t = last[key]
            if (t != null && nowMs - t in 0 until everyMs) return false
            if (last.size > 500) last.entries.removeIf { nowMs - it.value !in 0 until everyMs }
            last[key] = nowMs
            return true
        }
    }
}

/**
 * Cap n2: this device's nudge key, a P-256 key pair and a Nostr secret for its nudge mailbox, kept like the ring key in
 * app-private storage outside the vault (`nudge-key.json`) and erased with it. Its public half goes to contacts and
 * group peers whose app says n2 ("nk", "np"), so group members can nudge too. It only ever opens nudges: a ring sealed
 * to it is never a call, so someone who got it through a group can't ring this phone. Made new with the ring key (a block).
 */
class NudgeKey(file: File) {
    private val keys = RingKey(file)
    /** The public half, as it goes in "nk". */
    val publicKey: String get() = keys.publicKey
    /** The nudge mailbox ("np"). */
    val mailbox: String get() = keys.mailbox
    /** Every nudge mailbox read now: the current one, and one replaced after a block for [RingKey.OLD_MS]. */
    fun mailboxes(nowMs: Long = System.currentTimeMillis()): List<String> = keys.mailboxes(nowMs)
    /** Their Nostr secrets, the same order (NIP-42). */
    fun nostrSecrets(nowMs: Long = System.currentTimeMillis()): List<ByteArray> = keys.nostrSecrets(nowMs)
    /** A nudge's content opened: what it says, or null. Never a ring. */
    fun nudge(content: String, nowMs: Long = System.currentTimeMillis()): NudgeWire.Nudge? = keys.nudge(content, nowMs)
    /** Someone was blocked: a new nudge key and mailbox, like the ring key. */
    fun renew(nowMs: Long = System.currentTimeMillis()) = keys.renew(nowMs)
    /** A replaced nudge key past [RingKey.OLD_MS] goes. True when one went. */
    fun prune(nowMs: Long = System.currentTimeMillis()): Boolean = keys.prune(nowMs)
    /** Erase everything and Move: a new nudge key next time. */
    fun forget() = keys.forget()
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

/**
 * What each contact or group peer announced (b1, e1, n2): their ring key and ring mailbox, their newest day key, their
 * nudge key and nudge mailbox. Saved (encrypted) through [write].
 */
class PeerKeys(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Keys(val rk: String? = null, val rp: String? = null, val wk: String? = null, val wt: Long = 0,
                    val nk: String? = null, val np: String? = null)
    private val lock = Any()
    private var map: MutableMap<String, Keys>? = null

    private fun all(): MutableMap<String, Keys> = map ?: try {
        val o = JSONObject(read() ?: "{}")
        o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { Keys(it.optString("rk").ifEmpty { null }, it.optString("rp").ifEmpty { null },
            it.optString("wk").ifEmpty { null }, it.optLong("wt"), it.optString("nk").ifEmpty { null }, it.optString("np").ifEmpty { null }) } }.toMutableMap()
    } catch (e: Exception) { mutableMapOf() }.also { map = it }

    private fun save(m: Map<String, Keys>) {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, JSONObject().put("rk", v.rk ?: "").put("rp", v.rp ?: "").put("wk", v.wk ?: "").put("wt", v.wt)
            .put("nk", v.nk ?: "").put("np", v.np ?: ""))
        try { write(o.toString()) } catch (_: Exception) {}
    }

    /** Takes what an authentic receipt from [key] announced: the ring and nudge keys as it says, a day key only when newer. */
    fun take(key: String, a: RingWire.Announce) = synchronized(lock) {
        if (a.rk == null && a.wk == null && a.nk == null) return@synchronized
        val m = all()
        val old = m[key] ?: Keys()
        val now = Keys(a.rk ?: old.rk, if (a.rk != null) a.rp else old.rp,
            if (a.wk != null && a.wt > old.wt) a.wk else old.wk, if (a.wk != null && a.wt > old.wt) a.wt else old.wt,
            a.nk ?: old.nk, if (a.nk != null) a.np else old.np)
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
        held ?: when (val r = SafeFile.read(file) { b ->
            val o = JSONObject(String(b))
            val old = o.optJSONArray("old")?.let { a -> (0 until a.length()).mapNotNull { try { parse(a.getJSONObject(it)) } catch (e: Exception) { null } } }.orEmpty()
            All(parse(o), old)
        }) {
            is SafeFile.Read.Ok -> r.value
            is SafeFile.Read.Missing -> All(make(), emptyList()).also { save(it) }
            // There but unreadable: a key for now, never saved over the one on disk.
            is SafeFile.Read.Bad -> { broken = true; All(make(), emptyList()) }
        }.also { held = it }
    }

    /** The ring key on disk couldn't be read: it's left as it is. */
    @Volatile private var broken = false

    private fun make(): Held {
        val kp = ChatCrypto.newKeyPair()
        val secret = Schnorr.newSecret()
        return Held(kp, ChatCrypto.b64(kp.public.encoded), secret, Schnorr.hex(Schnorr.publicKey(secret)))
    }

    private fun json(h: Held) = JSONObject().put("k", h.pub).put("s", ChatCrypto.b64(h.keys.private.encoded)).put("n", Schnorr.hex(h.secret))

    private fun save(a: All) {
        val o = json(a.now)
        if (a.old.isNotEmpty()) o.put("old", JSONArray().apply { a.old.forEach { put(json(it).put("u", it.until)) } })
        if (broken) return
        try { SafeFile.write(file, o.toString().toByteArray()) } catch (_: Exception) {}
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

    /** A nudge's content opened (caps n1 and n2): what it says, or null. */
    fun nudge(content: String, nowMs: Long = System.currentTimeMillis()): NudgeWire.Nudge? =
        live(nowMs).firstNotNullOfOrNull { NudgeWire.openContent(content, it.keys) }

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
    fun forget() = synchronized(this) { held = null; broken = false; SafeFile.delete(file) }

    companion object {
        /** How long a ring mailbox replaced after a block is still listened on. */
        const val OLD_MS = 2 * 86_400_000L
    }
}
