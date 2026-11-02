package io.github.ravenor907.muninn.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The rules of linking by QR (shared/docs/protocol.md section 15), kept apart from the state so they can be tested
 * off the phone: history pages, merging, the media checks and the LAN-only rule.
 */
object LinkWire {
    /** At most this many messages in one "chat" page. */
    const val PAGE_MAX = 100
    /** A page's "msgs" is also at most this many bytes of UTF-8 JSON, so it fits an envelope on every route. */
    const val PAGE_BYTES = 20 * 1024
    /** A message's text in a page or a "msg", at most. */
    const val TEXT_MAX = 2000
    /** Media is sent in pieces of this size, only on the home Wi-Fi. */
    const val CHUNK = 192 * 1024
    /** The biggest file a companion takes. */
    const val MAX_MEDIA = 100L * 1024 * 1024
    /** A new link code is made this long before the old one runs out. */
    const val REFRESH_EARLY_MS = 90_000L
    val KINDS = setOf("photo", "voice", "video", "file")

    /** The phone's own profile in "me" (section 15): shown as "you" on a linked companion. [pic]: a JPEG, or empty. */
    class Me(val name: String, val status: String, val custom: String, val pic: ByteArray)

    /** A "me" (or "setme") as taken: the name cut at 40 (none: null), an unknown status online, a picture that isn't a profile picture dropped. */
    fun readMe(o: JSONObject): Me? {
        val n = o.optString("n").trim().replace(Regex("\\s+"), " ").take(40)
        if (n.isEmpty()) return null
        val st = o.optString("st").takeIf { it in ProfileWire.MY_STATUSES } ?: "online"
        val pic = o.optString("pic").takeIf { it.isNotEmpty() }?.let {
            try { java.util.Base64.getDecoder().decode(it) } catch (e: Exception) { null }
        }?.takeIf { ProfileWire.pictureOk(it) } ?: ByteArray(0)
        return Me(n, st, ProfileWire.cleanCustom(o.optString("cs")), pic)
    }

    /** UTF-8 bytes of [o] as JSON. */
    fun jsonBytes(o: JSONObject): Int = o.toString().toByteArray(Charsets.UTF_8).size

    /**
     * Message items, oldest first, in pages of at most [PAGE_MAX] and [PAGE_BYTES] of UTF-8 JSON ("[", the items with a
     * comma between, "]"). Never an empty page.
     */
    fun pages(items: List<JSONObject>): List<List<JSONObject>> {
        val out = ArrayList<List<JSONObject>>()
        var cur = ArrayList<JSONObject>()
        var bytes = 2
        for (o in items) {
            val n = jsonBytes(o)
            if (cur.isNotEmpty() && (cur.size >= PAGE_MAX || bytes + 1 + n > PAGE_BYTES)) { out += cur; cur = ArrayList(); bytes = 2 }
            bytes += (if (cur.isEmpty()) 0 else 1) + n
            cur += o
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** What the companion keeps: [old] and [new] together by message id (a later copy wins), oldest first. Nothing is wiped. */
    fun merge(old: List<Link.M>, new: List<Link.M>): List<Link.M> {
        val byId = LinkedHashMap<String, Link.M>()
        old.forEach { byId[it.id] = it }
        new.forEach { if (it.id.isNotEmpty()) byId[it.id] = it }
        return byId.values.sortedBy { it.time }
    }

    fun sha256Hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The bytes are the file the phone described: right size and the same SHA-256. */
    fun verified(b: ByteArray, h: String, s: Long): Boolean = b.size.toLong() == s && b.size <= MAX_MEDIA && sha256Hex(b) == h.lowercase()

    /** [b] in pieces of [CHUNK], in order. */
    fun chunks(b: ByteArray): List<ByteArray> =
        if (b.isEmpty()) emptyList() else (b.indices step CHUNK).map { b.copyOfRange(it, minOf(b.size, it + CHUNK)) }

    /** How many pieces a file of [s] bytes comes in. */
    fun pieces(s: Long): Int = ((s + CHUNK - 1) / CHUNK).toInt()

    /** Media may go between the two devices: it came on the LAN route (same home Wi-Fi) and Direct isn't what carries it. */
    fun lanOk(via: String, directOn: Boolean): Boolean = via == "wifi" && !directOn

    /** Refresh (a "sync" without "f") at most this often per companion. */
    const val REFRESH_EVERY_MS = 10 * 60_000L
    /** A "send" from a companion with no answer from the phone this long: "Didn't send". */
    const val ECHO_MS = 60_000L

    /**
     * A frame that may go over the internet (a small live one, to at most 2 relays): msg, send, hello, unlink, result,
     * sync, setme, and me without its picture. History ("chat"), "get", "part" and a "me" with "pic" only on the home Wi-Fi.
     */
    fun internetOk(o: JSONObject): Boolean = when (o.optString("t")) {
        "me" -> !o.has("pic")
        // Cap l1 (15.1): a small change without a picture; the full account only on the home Wi-Fi.
        "acct" -> LinkAccount.internetOk(o)
        "msg", "send", "hello", "unlink", "result", "sync", "setme" -> true
        // Cap l2 (15.2): small live frames too.
        "ping", "pong", "read" -> true
        else -> false
    }

    /** Cap l2 (15.2): read state both ways, ping and pong, the companion's device name. */
    const val L2 = "l2"
    fun saysL2(caps: String) = caps.split(' ').any { it == L2 }
    /** Cap l3 (15.3): a "msg" with an id the companion has is that message edited or deleted, and replaces it quietly. */
    const val L3 = "l3"
    fun saysL3(caps: String) = caps.split(' ').any { it == L3 }
    /** A device name from a "ping" ("dn"): one line, at most 40 characters. */
    fun deviceName(s: String) = s.trim().replace(Regex("\\s+"), " ").take(40)

    /** A Refresh last taken at [last] (ms, 0: never) may be taken at [now]. */
    fun syncDue(last: Long, now: Long): Boolean = last <= 0L || now - last >= REFRESH_EVERY_MS

    /** What a companion shows for a phone's {"t": "result", "ok": false, "err"}. */
    fun errText(err: String): String = when (err) {
        "confirm" -> "Confirm the emoji on your phone"
        "not a contact" -> "Not a contact on your phone"
        else -> "Didn't send"
    }

    /** The link code on screen made at [madeAt] should be swapped for a new one now. */
    fun refreshDue(madeAt: Long, now: Long): Boolean = now - madeAt >= Link.TOKEN_MS - REFRESH_EARLY_MS

    /** A message item's "f", or null when it has none or it isn't usable. */
    fun media(o: JSONObject?): Link.Media? {
        if (o == null) return null
        val h = o.opt("h") as? String ?: return null
        if (h.length != 64 || h.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val s = (o.opt("s") as? Number)?.toLong() ?: return null
        if (s <= 0 || s > MAX_MEDIA) return null
        val k = o.opt("k") as? String ?: return null
        if (k !in KINDS) return null
        val nm = (o.opt("nm") as? String).orEmpty().take(200)
        return Link.Media(h, s, k, nm)
    }

    fun mediaJson(f: Link.Media): JSONObject = JSONObject().put("h", f.h).put("s", f.s).put("k", f.k).put("nm", f.nm)

    /** A file put back together from its pieces, then checked against what the phone said. */
    class Assembly(val f: Link.Media) {
        private val parts = arrayOfNulls<ByteArray>(pieces(f.s))
        val started = System.currentTimeMillis()
        /** False when the piece doesn't fit this file. */
        fun add(i: Int, n: Int, b: ByteArray): Boolean {
            if (n != parts.size || i !in parts.indices || b.isEmpty() || b.size > CHUNK) return false
            parts[i] = b
            return true
        }
        fun complete(): Boolean = parts.all { it != null }
        /** The whole file when it's complete and checks out, else null. */
        fun result(): ByteArray? {
            if (!complete()) return null
            val total = parts.sumOf { it!!.size.toLong() }
            if (total != f.s) return null
            val out = java.io.ByteArrayOutputStream(total.toInt())
            parts.forEach { out.write(it!!) }
            return out.toByteArray().takeIf { verified(it, f.h, f.s) }
        }
    }
}

/**
 * Linked devices, companion style (shared/docs/protocol.md section 15): a tablet or computer shows this phone's
 * 1:1 chats and sends through it, while this phone is online. Contacts see nothing new: every message still comes from
 * and goes to this phone. Between the two devices everything is sealed like a private message.
 *
 *  - The companion shows a link code as a QR (its own chat key, mailbox and a token good for 10 minutes, made anew
 *    before it runs out); the phone scans it, then both screens show the same 4 emoji.
 *  - Room "link" from the phone: t "hello" {i} (pairing), "chat" {peer, n, msgs, d, pg, pgs} (every message still kept,
 *    in pages, oldest first, home Wi-Fi only), "msg" {peer, n, m} (one message, in or out), "result" {id, ok, err, mid}
 *    (the answer to a "send"), "part" {h, i, n, b} (a piece of a photo or file, home Wi-Fi only), "unlink".
 *  - Room "link" from the companion: t "send" {peer, text, id} (the phone sends it as its own), "sync" (all chats again,
 *    at most once in 10 minutes), "get" {h} (a photo or file, home Wi-Fi only), "unlink".
 *  - Only small live frames ([LinkWire.internetOk]) use the internet, to at most 2 relays; history waits for the home Wi-Fi.
 *  - A companion that had chats of its own keeps them, sealed away while it's linked: they show again on unlinking,
 *    or from Settings › Linked devices (after the lock).
 */
object Link {
    const val PREFIX = "MUNINNL1:"
    const val TOKEN_MS = 10 * 60_000L
    private const val SENDS = "link-sends.json"
    private const val HASHES = "link-hashes.json"
    private const val STORED = "mirror-media.json"
    private const val MEDIA_DIR = "mirror-media/"

    /** A device linked to this one: on the phone, a companion; on the companion, the phone. */
    /** [ok]: both screens showed the same 4 emoji and the person said so (nothing is mirrored before). */
    /** [l1]: on the phone, the companion's "sync" said "caps": "l1": it takes the whole account (15.1). */
    /** [l2]: on the phone, the companion's "ping" said "caps": "l2": it takes "read" frames (15.2). */
    data class Device(val key: String, val mailbox: String, val name: String, val ok: Boolean = false, val l1: Boolean = false, val l2: Boolean = false, val l3: Boolean = false)
    /** A photo, voice message, video or file on the phone: its SHA-256 (hex), size, kind and name. */
    data class Media(val h: String, val s: Long, val k: String, val nm: String)
    /** One mirrored message on the companion. [f]: its photo or file, fetched on the home Wi-Fi. */
    data class M(val id: String, val text: String, val mine: Boolean, val time: Long, val f: Media? = null)
    /** [days]: the chat's message limit on the phone (cap d1): the companion deletes after it too. */
    data class MirrorChat(val peer: String, val name: String, val msgs: List<M>, val days: Int = Limits.DEFAULT)

    interface Host {
        fun myKey(): String
        fun myMailbox(): String
        fun myName(): String
        /** Seal [json] (room "link") for [d] and send it as a small live frame: close by, the Wi-Fi and at most 2 relays. */
        fun send(d: Device, json: String)
        /** The phone's chats: peer to (name, messages), newest last. */
        fun chats(): Map<String, Pair<String, List<Chat.Msg>>>
        /** The phone sends [text] to its contact [peer] as its own message. False when [peer] isn't a contact. */
        fun sendAsMe(peer: String, text: String): Boolean
        /** The same, with the new message's id (null when [peer] isn't a contact). */
        fun sendAsMeId(peer: String, text: String): String? = if (sendAsMe(peer, text)) "" else null
        /** [peer] is one of my contacts (only they get a companion's "send"). */
        fun isContactHere(peer: String): Boolean = true
        fun read(name: String): String?
        fun write(name: String, text: String)
        fun notice(text: String)
        /** How many days the phone keeps its chat with [peer] (shared/docs/protocol.md section 16). */
        fun days(peer: String): Int = Limits.DEFAULT
        /** The 4 emoji for this device and [key] (13.5): the same on both screens means nobody is in between. */
        fun emoji(key: String): String = ""
        /** The bytes of a message's photo, voice message, video or file, or null. */
        fun media(m: Chat.Msg): ByteArray? = null
        /** [key] is on the same home Wi-Fi right now (the LAN route, not Direct). */
        fun onLan(key: String): Boolean = false
        /** Seal [json] for [d] and send it on the home Wi-Fi only. False when that didn't work. */
        fun sendLan(d: Device, json: String): Boolean = false
        fun readBytes(name: String): ByteArray? = null
        fun writeBytes(name: String, b: ByteArray) {}
        fun delete(name: String) {}
        /** On the phone: my profile for "me": {n, st, cs, pic (base64 JPEG)}, the last two only when set. */
        fun myProfile(): JSONObject = JSONObject().put("n", myName()).put("st", "online")
        /** On the phone: a companion's "setme" (an empty name or status leaves that one as it is). */
        fun setMyProfile(name: String, status: String, custom: String) {}
        /** Cap l1 (15.1), on the phone: the account as it is now, "k|id" to its value (LinkAccount.KINDS). */
        fun account(): Map<String, JSONObject> = emptyMap()
        /** Cap l1: a companion's change that won (a nickname, a block, a removed contact, a folder, my name or status). */
        fun applyAccount(item: LinkAccount.Item) {}
        /** Cap l1: the group chats, "g:" + group id to (name, items as in "chat" pages, with "fn" the sender's name), oldest first. */
        fun groupChats(): Map<String, Pair<String, List<JSONObject>>> = emptyMap()
        /**
         * Cap l1: post [text] in group [g] as me, answering message [re] when not empty, in channel [ch] (15.1: the one the
         * companion is viewing; "" or one that isn't a text channel there: the main channel). False when I can't post there.
         */
        fun sendToGroup(g: String, text: String, re: String = "", ch: String = ""): Boolean = false
        /** Cap g3: channel [ch] of group [g] takes posts only from admins, and I'm not one. */
        fun adminsOnlyFor(g: String, ch: String): Boolean = false
        /** The same as [sendAsMeId], answering message [re] when not empty. */
        fun sendAsMeReply(peer: String, text: String, re: String): String? = sendAsMeId(peer, text)
        /** Cap l1: how many days group [g] keeps its messages (section 16). */
        fun groupDays(g: String): Int = Limits.DEFAULT
        /** Cap l2: a companion read chat [peer] ("g:" + id for a group) up to [upTo] (ms): read here too, its notification goes. */
        fun readUpTo(peer: String, upTo: Long) {}
    }

    @Volatile var host: Host? = null
    private val _companions = MutableStateFlow<List<Device>>(emptyList())
    /** On the phone: the devices that mirror it. */
    val companions: StateFlow<List<Device>> = _companions.asStateFlow()
    private val _phone = MutableStateFlow<Device?>(null)
    /** On a companion: the phone it mirrors. */
    val phone: StateFlow<Device?> = _phone.asStateFlow()
    private val _mirror = MutableStateFlow<Map<String, MirrorChat>>(emptyMap())
    val mirror: StateFlow<Map<String, MirrorChat>> = _mirror.asStateFlow()
    private val _stored = MutableStateFlow<Set<String>>(emptySet())
    /** On a companion: the photos and files (by SHA-256) already here. */
    val stored: StateFlow<Set<String>> = _stored.asStateFlow()
    private val _me = MutableStateFlow<LinkWire.Me?>(null)
    /** On a companion: the phone's own profile ("me"), shown as "you" while it's linked and checked. */
    val me: StateFlow<LinkWire.Me?> = _me.asStateFlow()
    /** On the phone: the last "me" sent, so a change goes once. */
    @Volatile private var meSent = ""
    private val _ownShown = MutableStateFlow(false)
    /** On a linked companion: this device's own chats are open (after the lock) instead of the phone's. */
    val ownShown: StateFlow<Boolean> = _ownShown.asStateFlow()

    /** On a companion: sent through the phone, not answered yet. [err]: why it didn't send ("" while waiting). */
    data class Pending(val id: String, val peer: String, val text: String, val time: Long, val err: String = "")
    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()
    /** The phone's side: companions whose history, or "me" picture, waits for the home Wi-Fi; when each last asked. */
    private val owed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val meOwed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val synced = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The companion's side: when Refresh last asked. */
    @Volatile private var askedAt = 0L
    private val clock = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-link-clock").apply { isDaemon = true } }

    /** Link codes still good: token to until (the newest two, so one scanned just before a refresh still works). */
    private val tokens = ArrayList<Pair<String, Long>>()
    private val mirrorLock = Any()
    /** Long work (hashing, pages, messages) off the thread a message came in on. */
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-link").apply { isDaemon = true } }
    private fun later(f: () -> Unit) = work.execute { try { f() } catch (_: Exception) {} }
    /** Files going to a companion on their own thread, so a big one never holds up "msg" or "sync". */
    private val files = Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-link-files").apply { isDaemon = true } }
    private fun sendFiles(f: () -> Unit) = files.execute { try { f() } catch (_: Exception) {} }

    fun load() {
        val h = host ?: return
        try {
            h.read("link.json")?.let { t ->
                val o = JSONObject(t)
                _companions.value = o.optJSONArray("companions")?.let { a -> (0 until a.length()).map { dev(a.getJSONObject(it)) } }.orEmpty()
                _phone.value = o.optJSONObject("phone")?.let { dev(it) }
                _me.value = if (_phone.value != null) o.optJSONObject("me")?.let { LinkWire.readMe(it) } else null
            }
            h.read("mirror.json")?.let { t ->
                val o = JSONObject(t)
                _mirror.value = o.keys().asSequence().associateWith { k -> chatOf(o.getJSONObject(k)) }
            }
            h.read(STORED)?.let { t -> val a = JSONArray(t); _stored.value = (0 until a.length()).map { a.getString(it) }.toSet() }
        } catch (_: Exception) {}
    }

    private fun save() {
        val h = host ?: return
        h.write("link.json", JSONObject().put("companions", JSONArray().apply { _companions.value.forEach { put(devJson(it)) } })
            .apply { _phone.value?.let { put("phone", devJson(it)) } }
            .apply { if (_phone.value != null) _me.value?.let { put("me", meJson(it)) } }.toString())
    }

    private fun meJson(m: LinkWire.Me): JSONObject = JSONObject().put("t", "me").put("n", m.name).put("st", m.status).apply {
        if (m.custom.isNotEmpty()) put("cs", m.custom)
        if (m.pic.isNotEmpty()) put("pic", java.util.Base64.getEncoder().encodeToString(m.pic))
    }

    private fun saveMirror() {
        val h = host ?: return
        h.write("mirror.json", JSONObject().apply { _mirror.value.forEach { (k, c) -> put(k, chatJson(c)) } }.toString())
    }

    private fun saveStored() { host?.write(STORED, JSONArray(_stored.value.toList()).toString()) }

    // ------------------------------------------------------------------ pairing

    /** On the device that will be the companion: a code for the phone to take (a new token each time). */
    fun myLinkCode(now: Long = System.currentTimeMillis()): String {
        val h = host ?: return ""
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        synchronized(tokens) {
            tokens.removeAll { it.second < now }
            tokens += token to now + TOKEN_MS
            while (tokens.size > 2) tokens.removeAt(0)
        }
        val json = JSONObject().put("k", h.myKey()).put("p", h.myMailbox()).put("n", h.myName()).put("i", token).toString()
        return PREFIX + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    /** [i] is a link code token shown here, still in time; it's used up (with any other) when it is. */
    private fun takeToken(i: String, now: Long): Boolean = synchronized(tokens) {
        val ok = i.isNotEmpty() && tokens.any { it.first == i && now <= it.second }
        if (ok) tokens.clear()
        ok
    }

    /** The 4 emoji to check with the linked device [key], on both screens, before anything is mirrored. */
    fun emojiWith(key: String): String = host?.emoji(key).orEmpty()

    /** The text is a link code (from any scanner). */
    fun isCode(text: String): Boolean = text.contains(PREFIX)

    /**
     * On the phone: link the device whose code this is. Nothing is mirrored until both screens showed the same 4 emoji
     * and the person said so ([confirm]); [confirmed] only for a device already proven (moving, section 18). Null when
     * done, else what to say.
     */
    fun link(code: String, confirmed: Boolean = false): String? {
        val h = host ?: return "Not ready."
        val i = code.indexOf(PREFIX)
        if (i < 0) return "That isn't a link code."
        val o = try { JSONObject(String(java.util.Base64.getUrlDecoder().decode(code.substring(i + PREFIX.length).trim().takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }))) }
            catch (e: Exception) { return "That link code is damaged." }
        val k = o.optString("k")
        try { ChatCrypto.publicFrom(k) } catch (e: Exception) { return "That link code is damaged." }
        if (k == h.myKey()) return "That's this device's own code."
        val d = Device(k, o.optString("p"), o.optString("n").take(40).ifBlank { "Linked device" }, ok = confirmed)
        _companions.value = _companions.value.filterNot { it.key == k } + d
        save()
        h.send(d, JSONObject().put("t", "hello").put("i", o.optString("i")).put("n", h.myName()).put("p", h.myMailbox()).toString())
        if (confirmed) { syncAll(d); sendMe(true) }
        return null
    }

    /** The 4 emoji matched on both screens: on the phone the chats go to [key] now; on a companion, they're asked for. */
    fun confirm(key: String) {
        val h = host ?: return
        _companions.value.firstOrNull { it.key == key && !it.ok }?.let { d ->
            val now = d.copy(ok = true)
            _companions.value = _companions.value.map { if (it.key == key) now else it }
            save(); syncAll(now); sendMe(true)
        }
        _phone.value?.takeIf { it.key == key && !it.ok }?.let { p ->
            _phone.value = p.copy(ok = true); _ownShown.value = false; save()
            // "f": the first one after the emoji, which the phone takes even inside the 10 minutes.
            h.send(p, JSONObject().put("t", "sync").put("f", true).toString())
        }
    }

    fun unlink(key: String) {
        val h = host ?: return
        _companions.value.firstOrNull { it.key == key }?.let { h.send(it, JSONObject().put("t", "unlink").toString()) }
        _phone.value?.takeIf { it.key == key }?.let { h.send(it, JSONObject().put("t", "unlink").toString()); dropPhone() }
        _companions.value = _companions.value.filterNot { it.key == key }
        owed.remove(key); meOwed.remove(key); acctOwed.remove(key)
        save()
    }

    /** On a companion: the phone is gone, with its chats and media; this device's own chats are the main list again. */
    /** On the phone: my profile to every checked companion, when it changed since it last went ([force]: anyway). */
    fun sendMe(force: Boolean = false) {
        val h = host ?: return
        accountChanged()   // cap l1: my name, status or picture is in the account too
        val to = _companions.value.filter { it.ok }
        if (to.isEmpty()) return
        later {
            val me = h.myProfile().put("t", "me")
            val json = me.toString()
            if (!force && json == meSent) return@later
            meSent = json
            to.forEach { sendMeTo(h, it, me) }
        }
    }

    /**
     * "me" with its picture only on the home Wi-Fi. Away from it the rest goes now ("pk": keep the picture you have) and
     * the picture follows once both are on it again ([lanCame]).
     */
    private fun sendMeTo(h: Host, d: Device, me: JSONObject) {
        if (!me.has("pic")) { meOwed.remove(d.key); h.send(d, me.toString()); return }
        if (h.onLan(d.key) && h.sendLan(d, me.toString())) { meOwed.remove(d.key); return }
        h.send(d, JSONObject(me.toString()).apply { remove("pic"); put("pk", true) }.toString())
        meOwed.add(d.key)
    }

    /** Devices that just came onto the home Wi-Fi: history and pictures that waited for it go now. */
    fun lanCame(keys: Set<String>) {
        val h = host ?: return
        val back = _companions.value.filter { it.ok && it.key in keys && (it.key in owed || it.key in meOwed || it.key in acctOwed) }
        if (back.isEmpty()) return
        later {
            for (d in back) {
                if (d.key in owed) syncAll(d)
                if (d.key in acctOwed) sendAccount(h, d, full = true)
                if (d.key in meOwed) sendMeTo(h, d, h.myProfile().put("t", "me"))
            }
        }
    }

    /** On a companion: a new name, status or custom status for the phone (the account), shown here at once. Null when sent. */
    fun setMe(name: String, status: String, custom: String): String? {
        val h = host ?: return "Not ready."
        val p = _phone.value?.takeIf { it.ok } ?: return "No phone is linked."
        val old = _me.value
        val m = LinkWire.readMe(JSONObject().put("n", name.ifBlank { old?.name ?: "" }).put("st", status.ifBlank { old?.status ?: "" }).put("cs", custom))
            ?: return "Give it a name."
        _me.value = LinkWire.Me(m.name, m.status, m.custom, old?.pic ?: ByteArray(0))
        save()
        h.send(p, JSONObject().put("t", "setme").put("n", m.name).put("st", m.status).put("cs", m.custom).toString())
        return null
    }

    private fun dropPhone() {
        val h = host
        _phone.value = null
        _me.value = null
        synchronized(mirrorLock) { _mirror.value = emptyMap(); saveMirror() }
        _stored.value.forEach { h?.delete(MEDIA_DIR + it) }
        _stored.value = emptySet(); saveStored()
        assemblies.clear(); asked.clear()
        _pending.value = emptyList(); askedAt = 0L
        _ownShown.value = false
    }

    /** Settings › Linked devices › This device's own chats, after the lock: shown instead of the phone's. */
    fun showOwn(on: Boolean) { _ownShown.value = on && _phone.value?.ok == true }

    // ------------------------------------------------------------------ the phone's side

    /** Every message still kept in every chat, in pages, to [d] (or to every companion). */
    fun syncAll(d: Device? = null) {
        val h = host ?: return
        val to = (if (d != null) listOf(d) else _companions.value).filter { it.ok }
        if (to.isEmpty()) return
        later {
            // History only over the home Wi-Fi: a companion that isn't on it now gets it once it is ([lanCame]).
            val lan = to.filter { d -> h.onLan(d.key).also { on -> if (on) owed.remove(d.key) else owed.add(d.key) } }.toMutableList()
            if (lan.isEmpty()) return@later
            for ((peer, chat) in h.chats()) {
                val pages = LinkWire.pages(chat.second.sortedBy { it.time }.map { mJson(it) })
                val days = h.days(peer)
                pages.forEachIndexed { i, pg ->
                    val json = JSONObject().put("t", "chat").put("peer", peer).put("n", chat.first).put("msgs", JSONArray(pg))
                        .put("d", days).put("pg", i).put("pgs", pages.size).toString()
                    // Off the home Wi-Fi meanwhile: all of it again later.
                    lan.removeAll { d -> !h.sendLan(d, json).also { ok -> if (!ok) owed.add(d.key) } }
                }
                if (lan.isEmpty()) break
            }
            // Cap l1: the group chats too, to the companions that take the whole account.
            val l1 = lan.filter { it.l1 }
            if (l1.isNotEmpty()) for ((peer, chat) in h.groupChats()) {
                sendGroupPages(h, l1, peer, chat.first, chat.second)
            }
            saveHashes()
        }
    }

    /** Cap l1: one group's messages ([peer] "g:" + id) in "chat" pages, home Wi-Fi only, with the group's own limit. */
    private fun sendGroupPages(h: Host, to: List<Device>, peer: String, name: String, items: List<JSONObject>) {
        val pages = LinkWire.pages(items)
        val days = h.groupDays(peer.removePrefix("g:"))
        pages.forEachIndexed { i, pg ->
            val json = JSONObject().put("t", "chat").put("peer", peer).put("n", name).put("msgs", JSONArray(pg))
                .put("d", days).put("pg", i).put("pgs", pages.size).toString()
            to.forEach { d -> if (!h.sendLan(d, json)) owed.add(d.key) }
        }
    }

    /** A message in or out of a chat on the phone: the companions get it too. */
    fun onMessage(peer: String, name: String, m: Chat.Msg) {
        val h = host ?: return
        val to = _companions.value.filter { it.ok }
        if (to.isEmpty()) return
        later {
            val json = JSONObject().put("t", "msg").put("peer", peer).put("n", name).put("m", mJson(m)).put("d", h.days(peer)).toString()
            to.forEach { h.send(it, json) }
            saveHashes()
        }
    }

    /** Cap l3 (15.3): a message already sent was edited or deleted (by me or by them): it goes again, same id, to companions that replace it. */
    fun onChanged(peer: String, name: String, m: Chat.Msg) {
        val h = host ?: return
        val to = _companions.value.filter { it.ok && it.l3 }
        if (to.isEmpty()) return
        later {
            val json = JSONObject().put("t", "msg").put("peer", peer).put("n", name).put("m", mJson(m)).put("d", h.days(peer)).toString()
            to.forEach { h.send(it, json) }
        }
    }

    private fun mJson(m: Chat.Msg): JSONObject {
        val o = JSONObject().put("id", m.id).put("x", text(m).take(LinkWire.TEXT_MAX)).put("me", m.mine).put("t", m.time)
        mediaOf(m)?.let { o.put("f", LinkWire.mediaJson(it)) }
        // Optional (section 15 items): what it answers, and when it was edited.
        if (m.re.isNotEmpty()) o.put("re", m.re.take(200))
        if (m.edited > 0 && m.unsent.isEmpty()) o.put("ed", m.edited)
        return o
    }

    private fun text(m: Chat.Msg) = when {
        m.unsent.isNotEmpty() -> "Message deleted"
        m.voice > 0 -> "Voice message"
        m.video > 0 -> "Video message"
        m.file.isNotEmpty() -> "File: ${m.file}"
        m.photo.isNotEmpty() -> "Photo"
        else -> m.text
    }

    /** Media ids on the phone to "hash size" (worked out once: the file has to be read and decrypted for it). */
    private val hashes = HashMap<String, String>()
    @Volatile private var hashesLoaded = false
    @Volatile private var hashesChanged = false

    private fun loadHashes() {
        if (hashesLoaded) return
        hashesLoaded = true
        try { host?.read(HASHES)?.let { JSONObject(it) }?.let { o -> o.keys().forEach { hashes[it] = o.getString(it) } } } catch (_: Exception) {}
    }

    private fun saveHashes() {
        val h = host ?: return
        synchronized(hashes) {
            if (!hashesChanged) return
            hashesChanged = false
            // Only what the chats still have.
            val live = h.chats().values.flatMap { c -> c.second.map { it.photo } }.filter { it.isNotEmpty() }.toSet()
            hashes.keys.retainAll(live)
            h.write(HASHES, JSONObject().apply { hashes.forEach { (k, v) -> put(k, v) } }.toString())
        }
    }

    /** What [m] carries, as the companion is told about it; null for text, or a file too big to mirror. */
    private fun mediaOf(m: Chat.Msg): Media? {
        if (m.photo.isEmpty() || m.unsent.isNotEmpty()) return null
        val kind = when { m.voice > 0 -> "voice"; m.video > 0 -> "video"; m.file.isNotEmpty() -> "file"; else -> "photo" }
        val name = when (kind) { "voice" -> "voice.m4a"; "video" -> "video.mp4"; "file" -> m.file; else -> "photo.jpg" }
        val hs = synchronized(hashes) { loadHashes(); hashes[m.photo] } ?: run {
            // Read and hashed outside the lock, so a file being sent never waits for it.
            val b = host?.media(m) ?: return null
            if (b.isEmpty() || b.size > LinkWire.MAX_MEDIA) return null
            (LinkWire.sha256Hex(b) + " " + b.size).also { synchronized(hashes) { hashes[m.photo] = it; hashesChanged = true } }
        }
        val h = hs.substringBefore(' ')
        val s = hs.substringAfter(' ').toLongOrNull() ?: return null
        return Media(h, s, kind, name)
    }

    /** A companion asked for [hash] on the home Wi-Fi: the pieces go back the same way, never on another route. */
    private fun serve(d: Device, hash: String) {
        val h = host ?: return
        sendFiles {
            // Only a file the companion was told about: its SHA-256 is already worked out.
            val known = synchronized(hashes) { loadHashes(); hashes.filterValues { it.substringBefore(' ') == hash }.keys }
            val m = h.chats().values.flatMap { it.second }.firstOrNull { it.photo in known && it.unsent.isEmpty() } ?: return@sendFiles
            val b = h.media(m) ?: return@sendFiles
            if (LinkWire.sha256Hex(b) != hash) return@sendFiles
            val parts = LinkWire.chunks(b)
            for ((i, p) in parts.withIndex()) {
                val json = JSONObject().put("t", "part").put("h", hash).put("i", i).put("n", parts.size)
                    .put("b", java.util.Base64.getEncoder().encodeToString(p)).toString()
                if (!h.sendLan(d, json)) return@sendFiles   // off the home Wi-Fi now: the companion asks again later
            }
        }
    }

    // ------------------------------------------------------------------ room "link", either side

    /**
     * From [from], already opened. [lan]: it came on the home Wi-Fi ([LinkWire.lanOk]); "get" and "part" are only taken
     * that way. False: not from a device linked with this one.
     */
    fun receive(from: String, p: JSONObject, lan: Boolean = false): Boolean {
        val h = host ?: return false
        val t = p.optString("t")
        val comp = _companions.value.firstOrNull { it.key == from }
        val ph = _phone.value?.takeIf { it.key == from }
        when {
            // Pairing: the phone answers my link code with its token.
            t == "hello" && comp == null -> {
                if (!takeToken(p.optString("i"), System.currentTimeMillis())) return false
                val name = p.optString("n").take(40).trim()
                if (ph != null) {
                    // The same phone linked again: its chats stay, and the 4 emoji are checked again before anything shows.
                    _phone.value = ph.copy(name = name.ifBlank { ph.name }, mailbox = p.optString("p").takeIf { it.length == 64 } ?: ph.mailbox, ok = false)
                    _ownShown.value = false
                    save()
                } else {
                    // Another phone linked before: its chats and media go (this device's own chats always stay).
                    if (_phone.value != null) dropPhone()
                    _phone.value = Device(from, p.optString("p"), name.ifBlank { "Your phone" }, ok = false)
                    save()
                }
            }
            t == "unlink" && (comp != null || ph != null) -> {
                if (comp != null) { _companions.value = _companions.value - comp }
                if (ph != null) dropPhone()
                save()
            }
            // Nothing from the phone shows until this screen's emoji were checked.
            ph != null && !ph.ok && (t == "chat" || t == "msg" || t == "part" || t == "me") -> {}
            // The phone's own profile: "you" here while it's linked.
            // "pk": sent away from the home Wi-Fi without the picture: the one here stays until it comes.
            ph != null && t == "me" -> LinkWire.readMe(p)?.let { m ->
                _me.value = if (p.optBoolean("pk")) LinkWire.Me(m.name, m.status, m.custom, _me.value?.pic ?: ByteArray(0)) else m
                save()
            }
            // The phone's answer to a "send" from here: gone (it shows as the phone's own message), or why it didn't send.
            ph != null && t == "result" -> {
                val id = p.optString("id")
                val pend = _pending.value.firstOrNull { it.id == id } ?: return true
                if (p.optBoolean("ok")) {
                    _pending.value = _pending.value.filterNot { it.id == id }
                    // Its copy may not be here yet: shown under the phone's own id, which the copy then merges into.
                    val mid = p.optString("mid")
                    if (mid.isNotEmpty()) synchronized(mirrorLock) {
                        val old = _mirror.value[pend.peer]
                        if (old?.msgs?.any { it.id == mid } != true) {
                            _mirror.value = _mirror.value + (pend.peer to MirrorChat(pend.peer, old?.name ?: "Someone",
                                LinkWire.merge(old?.msgs.orEmpty(), listOf(M(mid, pend.text, true, System.currentTimeMillis()))), old?.days ?: Limits.DEFAULT))
                            saveMirror()
                        }
                    }
                } else {
                    _pending.value = _pending.value.map { if (it.id == id) it.copy(err = LinkWire.errText(p.optString("err"))) else it }
                }
            }
            ph != null && t == "chat" -> {
                val peer = p.optString("peer")
                if (peer.isEmpty()) return true
                synchronized(mirrorLock) {
                    val old = _mirror.value[peer]
                    // Pages add to what's here (by message id); a later page never wipes an earlier one.
                    val c = MirrorChat(peer, p.optString("n").take(40).ifBlank { old?.name ?: "Someone" },
                        LinkWire.merge(old?.msgs.orEmpty(), msgsOf(p.optJSONArray("msgs"))), daysOf(p))
                    _mirror.value = _mirror.value + (peer to c)
                    saveMirror()
                }
                prune(); fetchMissing()
            }
            ph != null && t == "msg" -> {
                val peer = p.optString("peer")
                val m = p.optJSONObject("m")?.let { mOf(it) } ?: return true
                synchronized(mirrorLock) {
                    val old = _mirror.value[peer]
                    _mirror.value = _mirror.value + (peer to MirrorChat(peer, p.optString("n").ifBlank { old?.name ?: "Someone" }.take(40),
                        LinkWire.merge(old?.msgs.orEmpty(), listOf(m)), if (p.has("d")) daysOf(p) else old?.days ?: Limits.DEFAULT))
                    saveMirror()
                }
                // My own message from here came back: it isn't pending any more.
                if (m.mine) _pending.value.firstOrNull { it.peer == peer && it.text.take(LinkWire.TEXT_MAX) == m.text }?.let { x ->
                    _pending.value = _pending.value.filterNot { it.id == x.id }
                }
                if (!m.mine) h.notice("${p.optString("n")}: ${m.text.take(60)}")
                fetchMissing()
            }
            ph != null && t == "part" -> if (lan) takePart(p)
            // Not checked here yet: a "send" is answered, so the companion doesn't wait for nothing.
            comp != null && !comp.ok && t == "send" -> answerSend(h, comp, p.optString("id"), false, "confirm")
            comp != null && !comp.ok && (t == "sync" || t == "get" || t == "setme" || t == "acct" || t == "ping" || t == "read") -> {}
            // Cap l2 (15.2): the companion asks whether this phone is there, says it takes "read" and gives its device name.
            comp != null && t == "ping" -> {
                val dn = LinkWire.deviceName(p.optString("dn"))
                val l2 = LinkWire.saysL2(p.optString("caps"))
                val l3 = LinkWire.saysL3(p.optString("caps"))
                if ((l2 && !comp.l2) || (l3 && !comp.l3) || (dn.isNotEmpty() && dn != comp.name)) {
                    val c = comp.copy(l2 = comp.l2 || l2, l3 = comp.l3 || l3, name = dn.ifEmpty { comp.name })
                    _companions.value = _companions.value.map { if (it.key == c.key) c else it }
                    save()
                }
                h.send(comp, JSONObject().put("t", "pong").toString())
            }
            // Cap l2: read on the companion, so read here too (receipts go from here, the notification goes).
            comp != null && t == "read" -> {
                val peer = p.optString("peer")
                val upTo = p.optLong("upTo")
                if (peer.isNotEmpty() && upTo > 0L) {
                    readSent[peer] = maxOf(readSent[peer] ?: 0L, upTo)   // not sent back as a "read" of its own
                    later { h.readUpTo(peer, upTo) }
                }
            }
            // Cap l1: a companion's account changes. The later one wins per item; what won here goes to the other companions.
            comp != null && t == "acct" -> if (comp.l1) later { takeAccount(h, comp, p) }
            // A companion changed my name or status: the phone is the account, so it changes here and goes to every companion.
            comp != null && t == "setme" -> {
                val q = JSONObject(p.toString()).apply { if (p.optString("n").isBlank()) put("n", h.myProfile().optString("n")) }
                LinkWire.readMe(q)?.let { m -> h.setMyProfile(m.name, if (p.optString("st").isEmpty()) "" else m.status, m.custom) }
                sendMe(true)
            }
            comp != null && t == "send" -> {
                val text = p.optString("text").trim()
                val id = p.optString("id")
                val re = p.optString("re").take(200)   // optional: the message this one answers
                val ch = p.optString("ch").take(40)    // optional (15.1): a group's channel, the one viewed on the companion
                // Each "send" once, by its own id: the same one again (a copy, or one sent on) never goes out twice. Always
                // answered ("result"), so the companion shows what happened.
                later {
                    val peer = p.optString("peer")
                    when {
                        text.isEmpty() -> answerSend(h, comp, id, false, "empty")
                        // Cap l1: a group chat ("g:" + its id), posted as the same member.
                        comp.l1 && peer.startsWith("g:") -> when {
                            !firstSend(id) -> answerSend(h, comp, id, true, "")
                            h.adminsOnlyFor(peer.removePrefix("g:"), ch) -> answerSend(h, comp, id, false, "admins only")
                            h.sendToGroup(peer.removePrefix("g:"), text.take(LinkWire.TEXT_MAX), re, ch) -> answerSend(h, comp, id, true, "")
                            else -> answerSend(h, comp, id, false, "not in group")
                        }
                        !h.isContactHere(peer) -> answerSend(h, comp, id, false, "not a contact")
                        !firstSend(id) -> answerSend(h, comp, id, true, "")
                        else -> {
                            val mid = if (re.isEmpty()) h.sendAsMeId(peer, text.take(LinkWire.TEXT_MAX)) else h.sendAsMeReply(peer, text.take(LinkWire.TEXT_MAX), re)
                            if (mid == null) answerSend(h, comp, id, false, "not a contact") else answerSend(h, comp, id, true, "", mid)
                        }
                    }
                }
            }
            // Refresh at most once in 10 minutes per companion; the first one after the emoji ("f") always.
            comp != null && t == "sync" -> {
                val now = System.currentTimeMillis()
                // Cap l1: "caps" with "l1" says the companion takes the whole account (an old one leaves it out).
                val caps = p.optString("caps")
                val d = if ((LinkAccount.says(caps) && !comp.l1) || (LinkWire.saysL3(caps) && !comp.l3))
                    comp.copy(l1 = comp.l1 || LinkAccount.says(caps), l3 = comp.l3 || LinkWire.saysL3(caps)).also { c ->
                        _companions.value = _companions.value.map { if (it.key == c.key) c else it }; save()
                    } else comp
                if (p.optBoolean("f") || LinkWire.syncDue(synced[d.key] ?: 0L, now)) {
                    synced[d.key] = now; syncAll(d); sendMe(true)
                    if (d.l1) later { sendAccount(h, d, full = true) }
                } else if (d.l1 && !comp.l1) later { sendAccount(h, d, full = true) }   // it just said l1: the account goes now
            }
            // Photos and files only on the home Wi-Fi: asked any other way, nothing is sent.
            comp != null && t == "get" -> if (lan) p.optString("h").takeIf { it.length == 64 }?.let { serve(comp, it) }
            else -> return comp != null || ph != null
        }
        return true
    }

    // ------------------------------------------------------------------ cap l1: the whole account (15.1), the phone's side

    private const val ACCOUNT = "link-account.json"
    private val acctLock = Any()
    @Volatile private var acct: Map<String, LinkAccount.Item>? = null
    /** Companions whose full account (or a picture in it) waits for the home Wi-Fi. */
    private val acctOwed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var acctSoon = false
    /** Per group: the message ids already sent to the companions or noted at the first look (live group messages, l1 only). */
    private val groupSent = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()
    /** Per group: what each message showed when last looked at (cap l3: a change goes again). */
    private val groupShown = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()

    private fun acctNow(h: Host): Map<String, LinkAccount.Item> = acct ?: LinkAccount.load(h.read(ACCOUNT)).also { acct = it }

    /** What the account is now against what was last said: changed items get a new ts, gone ones a tombstone. */
    private fun observe(h: Host): Pair<Map<String, LinkAccount.Item>, List<LinkAccount.Item>> = synchronized(acctLock) {
        val now = System.currentTimeMillis()
        val (set, changed) = LinkAccount.observe(acctNow(h), h.account(), now)
        val kept = LinkAccount.prune(set, now)
        if (changed.isNotEmpty() || kept.size != set.size) { acct = kept; h.write(ACCOUNT, LinkAccount.save(kept)) }
        kept to changed
    }

    /** Something in the account may have changed (contacts, folders, groups, blocks, my profile): a moment later it goes. */
    fun accountChanged() {
        if (host == null || _companions.value.none { it.ok && it.l1 }) return
        if (acctSoon) return
        acctSoon = true
        clock.schedule({
            acctSoon = false
            later {
                val h = host ?: return@later
                val to = _companions.value.filter { it.ok && it.l1 }
                if (to.isEmpty()) return@later
                val changed = observe(h).second
                if (changed.isNotEmpty()) to.forEach { sendItems(h, it, changed.map { i -> i.json() }, full = false) }
            }
        }, 1500L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** The whole account to [d] (on "They match", a sync, or once it's on the home Wi-Fi again). */
    private fun sendAccount(h: Host, d: Device, full: Boolean) {
        val set = observe(h).first
        sendItems(h, d, set.values.map { it.json() }, full)
    }

    /**
     * Items to [d]. The full set, and anything with a picture, only over the home Wi-Fi (owed until it's there); a small
     * change without one may use the internet like any live frame.
     */
    private fun sendItems(h: Host, d: Device, items: List<JSONObject>, full: Boolean) {
        if (items.isEmpty() && !full) return
        val pics = items.filter { it.optString("k") == "pic" && !it.optBoolean("del") }
        val rest = items - pics.toSet()
        if (!full) {
            for (pg in LinkAccount.pages(rest, LinkAccount.LIVE_BYTES - 512)) {
                val o = JSONObject().put("t", "acct").put("items", JSONArray(pg))
                if (LinkAccount.internetOk(o)) h.send(d, o.toString()) else if (!h.sendLan(d, o.toString())) acctOwed.add(d.key)
            }
            if (pics.isNotEmpty()) {
                if (!h.onLan(d.key)) { acctOwed.add(d.key); return }
                for (pg in LinkAccount.pages(pics)) if (!h.sendLan(d, JSONObject().put("t", "acct").put("items", JSONArray(pg)).toString())) acctOwed.add(d.key)
            }
            return
        }
        if (!h.onLan(d.key)) { acctOwed.add(d.key); return }
        val pages = LinkAccount.pages(rest + pics).ifEmpty { listOf(emptyList()) }
        var ok = true
        pages.forEachIndexed { i, pg ->
            val json = JSONObject().put("t", "acct").put("full", true).put("items", JSONArray(pg)).put("pg", i).put("pgs", pages.size).toString()
            if (ok && !h.sendLan(d, json)) ok = false
        }
        if (ok) acctOwed.remove(d.key) else acctOwed.add(d.key)
    }

    /** A companion's changes: merged (the later one wins; a tie stays with the phone), applied, and sent to the others. */
    private fun takeAccount(h: Host, from: Device, p: JSONObject) {
        val items = LinkAccount.readAll(p.optJSONArray("items")).filter { it.k != "group" && it.k != "pic" }   // groups and pictures change only here
        if (items.isEmpty()) return
        val base = observe(h).first   // what's here now first, so a change made here a moment ago counts
        val won = synchronized(acctLock) {
            val (set, w) = LinkAccount.merge(base, items, winTies = false)
            acct = set; h.write(ACCOUNT, LinkAccount.save(set))
            w
        }
        if (won.isEmpty()) return
        won.forEach { try { h.applyAccount(it) } catch (_: Exception) {} }
        // What the phone made of it (a value it can't take, like a verified mark, comes back corrected).
        val again = observe(h).second
        val json = won.map { it.json() }
        _companions.value.filter { it.ok && it.l1 && it.key != from.key }.forEach { sendItems(h, it, json, full = false) }
        if (again.isNotEmpty()) _companions.value.filter { it.ok && it.l1 }.forEach { sendItems(h, it, again.map { i -> i.json() }, full = false) }
        if (won.any { it.k == "me" }) sendMe(true)
    }

    /**
     * Cap l1: group messages changed ([all]: every group's messages now, oldest first; [item]: one as a companion shows it).
     * Messages not sent before (by id, so a late older one goes too) go to the companions: up to [LIVE_BURST] as "msg", more
     * as "chat" pages on the home Wi-Fi (else the companion is owed its history). The first look this run only notes what's
     * there, except a group that showed up since (just joined): its history goes in pages.
     */
    fun <T> onGroupMessages(all: Map<String, List<T>>, name: (String) -> String, id: (T) -> String, item: (T) -> JSONObject, shown: (T) -> String = { "" }) {
        val h = host ?: return
        val to = _companions.value.filter { it.ok && it.l1 }
        val first = !groupsPrimed
        groupsPrimed = true
        for ((g, msgs) in all) {
            val ids = msgs.map(id)
            val seen = groupSent[g]
            val known = seen ?: java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
            val fresh = msgs.filter { id(it) !in known }
            known.addAll(ids)
            if (known.size > 1000) known.retainAll(ids.toSet())
            groupSent[g] = known
            // Cap l3 (15.3): a message already sent whose text changed since (edited, or deleted for everyone).
            val was = groupShown.getOrPut(g) { java.util.concurrent.ConcurrentHashMap<String, String>() }
            val changed = msgs.filter { m -> was[id(m)].let { it != null && it != shown(m) } }
            msgs.forEach { was[id(it)] = shown(it) }
            if (was.size > 1000) was.keys.retainAll(ids.toSet())
            val l3 = to.filter { it.l3 }
            if (changed.isNotEmpty() && l3.isNotEmpty() && seen != null) {
                val n = name(g)
                later {
                    val days = h.groupDays(g)
                    for (m in changed.takeLast(LIVE_BURST)) {
                        val json = JSONObject().put("t", "msg").put("peer", "g:$g").put("n", n).put("m", item(m)).put("d", days).toString()
                        l3.forEach { h.send(it, json) }
                    }
                }
            }
            if (to.isEmpty() || fresh.isEmpty() || (seen == null && first)) continue
            val peer = "g:$g"
            val n = name(g)
            later {
                val days = h.groupDays(g)
                if (seen == null || fresh.size > LIVE_BURST) {
                    // Just joined, or a burst (back online after a while): pages on the home Wi-Fi.
                    val lan = to.filter { h.onLan(it.key) }
                    if (lan.isNotEmpty()) sendGroupPages(h, lan, peer, n, (if (seen == null) msgs else fresh).map(item))
                    (to - lan.toSet()).forEach { owed.add(it.key) }
                    if (seen == null) return@later
                }
                for (m in fresh.takeLast(LIVE_BURST)) {
                    val json = JSONObject().put("t", "msg").put("peer", peer).put("n", n).put("m", item(m)).put("d", days).toString()
                    to.forEach { h.send(it, json) }
                }
            }
        }
    }
    /** Group messages at once as "msg"; more than this go in pages (and the newest of them as "msg" too). */
    private const val LIVE_BURST = 20
    @Volatile private var groupsPrimed = false

    /** Cap l2: per chat, the newest time a "read" was sent or taken, so each goes once and none echoes back. */
    private val readSent = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val readSoon = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Cap l2: chat [peer] ("g:" + id for a group) was read on this phone up to [upTo]: the companions hear of it (a moment later). */
    fun onRead(peer: String, upTo: Long) {
        if (host == null || upTo <= 0L || upTo <= (readSent[peer] ?: 0L)) return
        if (_companions.value.none { it.ok && it.l2 }) return
        if (readSoon.put(peer, upTo) != null) return   // already on its way: the newest time goes
        clock.schedule({
            val t = readSoon.remove(peer) ?: return@schedule
            val h = host ?: return@schedule
            if (t <= (readSent[peer] ?: 0L)) return@schedule
            readSent[peer] = t
            val json = JSONObject().put("t", "read").put("peer", peer).put("upTo", t).toString()
            _companions.value.filter { it.ok && it.l2 }.forEach { h.send(it, json) }
        }, 2000L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Ids of companions' "send" already sent (kept two days, on disk), so none goes out twice. */
    private val sends = LinkedHashMap<String, Long>()
    @Volatile private var sendsLoaded = false

    private fun firstSend(id: String): Boolean {
        if (id.isEmpty()) return true   // a send without an id: nothing to check against
        val h = host ?: return false
        synchronized(sends) {
            val now = System.currentTimeMillis()
            if (!sendsLoaded) {
                sendsLoaded = true
                try { h.read(SENDS)?.let { JSONObject(it) }?.let { o -> o.keys().forEach { sends[it] = o.optLong(it) } } } catch (_: Exception) {}
            }
            sends.entries.removeIf { now - it.value > 2 * Limits.DAY }
            if (sends.containsKey(id)) return false
            sends[id] = now
            while (sends.size > 1000) sends.remove(sends.keys.first())
            h.write(SENDS, JSONObject().apply { sends.forEach { (k, t) -> put(k, t) } }.toString())
            return true
        }
    }

    // ------------------------------------------------------------------ the companion's side

    /**
     * Send [text] to [peer] through the phone. It shows at once in [pending] until the phone answers or its copy comes
     * back; no answer in a minute: "Didn't send". Null when it went, else what to say.
     */
    fun sendFromCompanion(peer: String, text: String): String? {
        if (host == null) return "Not ready."
        val ph = _phone.value ?: return "No phone is linked."
        if (!ph.ok) return "Check the emoji on both screens first."
        val t = text.trim().take(LinkWire.TEXT_MAX)
        if (t.isEmpty()) return "Write something first."
        val p = Pending(java.util.UUID.randomUUID().toString(), peer, t, System.currentTimeMillis())
        _pending.value = (_pending.value + p).takeLast(50)
        sendPending(p)
        return null
    }

    private fun sendPending(p: Pending) {
        val h = host ?: return
        val ph = _phone.value?.takeIf { it.ok } ?: return
        h.send(ph, JSONObject().put("t", "send").put("peer", p.peer).put("text", p.text).put("id", p.id).toString())
        clock.schedule({
            val now = System.currentTimeMillis()
            _pending.value = _pending.value.map { if (it.id == p.id && it.err.isEmpty() && now - it.time >= LinkWire.ECHO_MS) it.copy(err = "Didn't send") else it }
        }, LinkWire.ECHO_MS + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** One that didn't send, again: the same id, so the phone never sends it twice. */
    fun retrySend(id: String) {
        val p = _pending.value.firstOrNull { it.id == id }?.copy(err = "", time = System.currentTimeMillis()) ?: return
        _pending.value = _pending.value.map { if (it.id == id) p else it }
        sendPending(p)
    }

    private fun answerSend(h: Host, d: Device, id: String, ok: Boolean, err: String, mid: String = "") {
        if (id.isEmpty()) return
        h.send(d, JSONObject().put("t", "result").put("id", id).put("ok", ok).apply {
            if (err.isNotEmpty()) put("err", err)
            if (mid.isNotEmpty()) put("mid", mid)
        }.toString())
    }

    /** Refresh: every chat again, at most once in 10 minutes. What to say. */
    fun askSync(): String {
        val h = host ?: return "Not ready."
        val ph = _phone.value?.takeIf { it.ok } ?: return "No phone is linked."
        val now = System.currentTimeMillis()
        if (!LinkWire.syncDue(askedAt, now)) return "Asked already. You can ask again in ${maxOf(1L, (LinkWire.REFRESH_EVERY_MS - (now - askedAt) + 59_999L) / 60_000L)} min."
        askedAt = now
        h.send(ph, JSONObject().put("t", "sync").toString())
        fetchMissing()
        return if (h.onLan(ph.key)) "Asked ${ph.name} for its chats." else "Asked ${ph.name}. History comes over on home Wi-Fi."
    }

    /** Files being put back together, by hash; and when each missing one was last asked for. */
    private val assemblies = java.util.concurrent.ConcurrentHashMap<String, LinkWire.Assembly>()
    private val asked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** On a companion on the phone's home Wi-Fi: ask for the photos and files not here yet, two at a time. */
    fun fetchMissing() {
        val h = host ?: return
        val ph = _phone.value?.takeIf { it.ok } ?: return
        later {
            if (!h.onLan(ph.key)) return@later
            val now = System.currentTimeMillis()
            // A file that stopped coming (the phone left the Wi-Fi) starts over later.
            assemblies.entries.removeIf { now - it.value.started > 10 * 60_000L }
            val have = _stored.value
            val want = _mirror.value.values.flatMap { c -> c.msgs.mapNotNull { it.f } }.distinctBy { it.h }
                .filter { it.h !in have && !assemblies.containsKey(it.h) && now - (asked[it.h] ?: 0L) > 2 * 60_000L }
            for (f in want.take(maxOf(0, 2 - assemblies.size))) {
                asked[f.h] = now
                assemblies[f.h] = LinkWire.Assembly(f)
                h.sendLan(ph, JSONObject().put("t", "get").put("h", f.h).toString())
            }
        }
    }

    /** A piece of a photo or file from the phone (home Wi-Fi only): kept once whole and checked. */
    private fun takePart(p: JSONObject) {
        val h = host ?: return
        val hash = p.optString("h")
        val a = assemblies[hash] ?: return   // not asked for: dropped
        val b = try { java.util.Base64.getDecoder().decode(p.optString("b")) } catch (_: Exception) { return }
        if (!a.add(p.optInt("i", -1), p.optInt("n", -1), b)) { assemblies.remove(hash); return }
        if (!a.complete()) return
        assemblies.remove(hash)
        val whole = a.result() ?: return   // not what the phone said: asked again later
        try { h.writeBytes(MEDIA_DIR + hash, whole) } catch (_: Exception) { return }
        _stored.value = _stored.value + hash
        saveStored()
        asked.remove(hash)
        fetchMissing()
    }

    /** A photo or file on this companion, or null when it isn't here (yet). */
    fun mediaBytes(hash: String): ByteArray? = if (hash in _stored.value) host?.readBytes(MEDIA_DIR + hash) else null

    // ------------------------------------------------------------------ storage

    private fun devJson(d: Device) = JSONObject().put("k", d.key).put("p", d.mailbox).put("n", d.name).put("ok", d.ok)
        .apply { if (d.l1) put("l", true); if (d.l2) put("l2", true); if (d.l3) put("l3", true) }
    private fun dev(o: JSONObject) = Device(o.getString("k"), o.optString("p"), o.optString("n"), o.optBoolean("ok", false), o.optBoolean("l", false), o.optBoolean("l2", false), o.optBoolean("l3", false))
    private fun mOf(o: JSONObject) = M(o.optString("id"), o.optString("x").take(LinkWire.TEXT_MAX), o.optBoolean("me"), o.optLong("t"), LinkWire.media(o.optJSONObject("f")))
    private fun msgsOf(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> mOf(o) } }
    private fun daysOf(o: JSONObject) = o.optInt("d", Limits.DEFAULT).coerceIn(Limits.DEFAULT, Limits.LONGEST)

    /** On a companion: messages past each chat's limit go, as on the phone, and the photos and files only they had. */
    fun prune(now: Long = System.currentTimeMillis()) {
        synchronized(mirrorLock) {
            val old = _mirror.value
            val kept = old.mapValues { (_, c) -> c.copy(msgs = c.msgs.filter { it.time >= Limits.cutoff(now, c.days) }) }
            if (kept.values.sumOf { it.msgs.size } != old.values.sumOf { it.msgs.size }) { _mirror.value = kept; saveMirror() }
            val live = kept.values.flatMap { c -> c.msgs.mapNotNull { it.f?.h } }.toSet()
            val gone = _stored.value - live
            if (gone.isNotEmpty()) {
                gone.forEach { host?.delete(MEDIA_DIR + it) }
                _stored.value = _stored.value - gone
                saveStored()
            }
        }
    }

    private fun chatJson(c: MirrorChat) = JSONObject().put("peer", c.peer).put("n", c.name).put("d", c.days)
        .put("msgs", JSONArray().apply { c.msgs.forEach { m ->
            put(JSONObject().put("id", m.id).put("x", m.text).put("me", m.mine).put("t", m.time).apply { m.f?.let { put("f", LinkWire.mediaJson(it)) } })
        } })
    private fun chatOf(o: JSONObject) = MirrorChat(o.optString("peer"), o.optString("n"), msgsOf(o.optJSONArray("msgs")), daysOf(o))
}
