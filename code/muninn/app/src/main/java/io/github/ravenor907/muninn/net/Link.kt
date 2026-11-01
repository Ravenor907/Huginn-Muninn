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
 *    in pages, oldest first), "msg" {peer, n, m} (one message, in or out), "part" {h, i, n, b} (a piece of a photo or
 *    file, home Wi-Fi only), "unlink".
 *  - Room "link" from the companion: t "send" {peer, text, id} (the phone sends it as its own), "sync" (all chats again),
 *    "get" {h} (a photo or file, home Wi-Fi only), "unlink".
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
    data class Device(val key: String, val mailbox: String, val name: String, val ok: Boolean = false)
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
        /** Seal [json] (room "link") for [d] and send it on every route. */
        fun send(d: Device, json: String)
        /** The phone's chats: peer to (name, messages), newest last. */
        fun chats(): Map<String, Pair<String, List<Chat.Msg>>>
        /** The phone sends [text] to its contact [peer] as its own message. False when [peer] isn't a contact. */
        fun sendAsMe(peer: String, text: String): Boolean
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
            h.send(p, JSONObject().put("t", "sync").toString())
        }
    }

    fun unlink(key: String) {
        val h = host ?: return
        _companions.value.firstOrNull { it.key == key }?.let { h.send(it, JSONObject().put("t", "unlink").toString()) }
        _phone.value?.takeIf { it.key == key }?.let { h.send(it, JSONObject().put("t", "unlink").toString()); dropPhone() }
        _companions.value = _companions.value.filterNot { it.key == key }
        save()
    }

    /** On a companion: the phone is gone, with its chats and media; this device's own chats are the main list again. */
    /** On the phone: my profile to every checked companion, when it changed since it last went ([force]: anyway). */
    fun sendMe(force: Boolean = false) {
        val h = host ?: return
        val to = _companions.value.filter { it.ok }
        if (to.isEmpty()) return
        val json = h.myProfile().put("t", "me").toString()
        if (!force && json == meSent) return
        meSent = json
        to.forEach { h.send(it, json) }
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
            for ((peer, chat) in h.chats()) {
                val pages = LinkWire.pages(chat.second.sortedBy { it.time }.map { mJson(it) })
                val days = h.days(peer)
                pages.forEachIndexed { i, pg ->
                    val json = JSONObject().put("t", "chat").put("peer", peer).put("n", chat.first).put("msgs", JSONArray(pg))
                        .put("d", days).put("pg", i).put("pgs", pages.size).toString()
                    to.forEach { h.send(it, json) }
                }
            }
            saveHashes()
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

    private fun mJson(m: Chat.Msg): JSONObject {
        val o = JSONObject().put("id", m.id).put("x", text(m).take(LinkWire.TEXT_MAX)).put("me", m.mine).put("t", m.time)
        mediaOf(m)?.let { o.put("f", LinkWire.mediaJson(it)) }
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
            ph != null && t == "me" -> LinkWire.readMe(p)?.let { _me.value = it; save() }
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
                if (!m.mine) h.notice("${p.optString("n")}: ${m.text.take(60)}")
                fetchMissing()
            }
            ph != null && t == "part" -> if (lan) takePart(p)
            comp != null && !comp.ok && (t == "send" || t == "sync" || t == "get" || t == "setme") -> {}
            // A companion changed my name or status: the phone is the account, so it changes here and goes to every companion.
            comp != null && t == "setme" -> {
                val q = JSONObject(p.toString()).apply { if (p.optString("n").isBlank()) put("n", h.myProfile().optString("n")) }
                LinkWire.readMe(q)?.let { m -> h.setMyProfile(m.name, if (p.optString("st").isEmpty()) "" else m.status, m.custom) }
                sendMe(true)
            }
            comp != null && t == "send" -> {
                val text = p.optString("text").trim()
                // Each "send" once, by its own id: the same one again (a copy, or one sent on) never goes out twice.
                if (text.isNotEmpty() && firstSend(p.optString("id"))) h.sendAsMe(p.optString("peer"), text.take(LinkWire.TEXT_MAX))
            }
            comp != null && t == "sync" -> { syncAll(comp); sendMe(true) }
            // Photos and files only on the home Wi-Fi: asked any other way, nothing is sent.
            comp != null && t == "get" -> if (lan) p.optString("h").takeIf { it.length == 64 }?.let { serve(comp, it) }
            else -> return comp != null || ph != null
        }
        return true
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

    /** Send [text] to [peer] through the phone. False when no phone is linked. */
    fun sendFromCompanion(peer: String, text: String): Boolean {
        val h = host ?: return false
        val ph = _phone.value?.takeIf { it.ok } ?: return false
        h.send(ph, JSONObject().put("t", "send").put("peer", peer).put("text", text).put("id", java.util.UUID.randomUUID().toString()).toString())
        return true
    }

    fun askSync() { val h = host ?: return; _phone.value?.takeIf { it.ok }?.let { h.send(it, JSONObject().put("t", "sync").toString()) }; fetchMissing() }

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
    private fun dev(o: JSONObject) = Device(o.getString("k"), o.optString("p"), o.optString("n"), o.optBoolean("ok", false))
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
