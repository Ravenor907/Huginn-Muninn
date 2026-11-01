package io.github.ravenor907.muninn.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Cap p1 (shared/docs/protocol.md, section 22): profile pictures and status, the parts both apps must agree on.
 * shared/tools/p1_ref.py is the reference and writes shared/vectors/p1-vectors.json. No Android here, so all of it
 * runs in unit tests.
 *
 *  - room "profile": {t:"profile", v, pic, col, [nm]}, through the ratchet, once per peer per version. A picture is a
 *    256x256 JPEG; a receiver takes at most 24 KB, a sender keeps it at 12 KB or less so one relay event carries it.
 *  - room "live" "active" carries "st" (online, away or dnd) and "cs" (custom status, at most 80 characters);
 *    invisible goes as not active with no "st". Rich.check knows the fields.
 */
object ProfileWire {
    const val CAP = "p1"
    const val MAX_BYTES = 24 * 1024
    const val SEND_MAX_BYTES = 12 * 1024
    const val SIDE = 256
    /** JPEG qualities tried in turn until the picture fits [SEND_MAX_BYTES]. */
    val QUALITIES = intArrayOf(80, 70, 60, 50, 40, 30, 20)
    /** One profile message per sender in this long. */
    const val RATE_MS = 10_000L
    val MY_STATUSES = setOf("online", "away", "dnd", "invisible")
    /** A person's colour when none came from them: one of these, picked by the first byte of SHA-256 of their key. */
    val PALETTE = listOf("#d9853b", "#3fb27f", "#4f8fde", "#b86ad9", "#e0b44c", "#d9534f", "#2fa9a0", "#c96b8e",
        "#7a9b3c", "#e07b39", "#5c7cfa", "#8d6e63")

    fun colourOk(c: Any?): Boolean = c is String && c.length == 7 && c[0] == '#' &&
        c.substring(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** (width, height) from a JPEG's frame header, or null if it isn't a JPEG. Reads only the markers before the scan. */
    fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 <= b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xFF) { i++; continue }
            if (m == 0xD8 || m == 0x01 || m in 0xD0..0xD7) { i += 2; continue }
            if (m == 0xD9 || m == 0xDA) return null
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            if (m in setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)) {
                if (len < 7) return null
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return w to h
            }
            i += 2 + len
        }
        return null
    }

    /** A received picture: at most [MAX_BYTES], a JPEG, exactly [SIDE] x [SIDE]. */
    fun pictureOk(b: ByteArray): Boolean = b.size <= MAX_BYTES && jpegSize(b) == (SIDE to SIDE)

    fun b64d(s: String): ByteArray? = try { java.util.Base64.getDecoder().decode(s) } catch (e: Exception) { null }

    /** Well-formed for room "profile": t, v (1 <= v < 2^53), col and pic (base64, "" clears). Other fields are ignored. */
    fun ok(p: JSONObject): Boolean {
        if (p.opt("t") != "profile") return false
        if (!Rich.isTs(p.opt("v")) || (p.opt("v") as Number).toLong() < 1) return false
        if (!colourOk(p.opt("col"))) return false
        val pic = p.opt("pic") as? String ?: return false
        if (pic.isEmpty()) return true
        // Over the cap is refused before anything is decoded.
        if (pic.length > (MAX_BYTES + 2) / 3 * 4) return false
        return pictureOk(b64d(pic) ?: return false)
    }

    /**
     * [name]: my chosen name, as "nm", so a new name reaches everyone who already has me (apps that don't know
     * "nm" ignore it, as they do any other field). Empty: left out.
     */
    fun msg(v: Long, pic: String, col: String, name: String = ""): String = JSONObject().put("t", "profile").put("v", v).put("pic", pic).put("col", col)
        .apply { if (name.isNotBlank()) put("nm", name.trim().take(MAX_NAME)) }.toString()

    const val MAX_NAME = 40

    /** The chosen name a profile message carries ("nm"), or null when it has none or it isn't usable. */
    fun nameOf(p: JSONObject): String? {
        val n = (p.opt("nm") as? String)?.trim() ?: return null
        if (n.isEmpty() || n.length > MAX_NAME || n.any { it.code < 0x20 || it.code == 0x7F }) return null
        return n
    }

    fun defaultColour(key: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return PALETTE[(h[0].toInt() and 0xFF) % PALETTE.size]
    }

    /** A receiver keeps the highest version per sender. */
    fun takes(have: Long, new: Long) = new > have

    /**
     * What is shown for someone: [active] (a fresh "on" in the last 5 minutes) shows their [st] (online when their app
     * has no p1); otherwise "offline" for someone ever [heard] from, and "" for anyone else.
     */
    fun statusOf(active: Boolean, st: String, heard: Boolean): String = when {
        active -> if (st in Rich.STATUSES) st else "online"
        heard -> "offline"
        else -> ""
    }

    /** A custom status as typed: control characters out, 80 code points at most. */
    fun cleanCustom(s: String): String = Rich.cut(s.filter { it.code >= 0x20 && it.code != 0x7F }.trim(), Rich.MAX_CUSTOM)
}

/**
 * My profile (picture, colour, status, custom status) and everyone else's, kept encrypted in files under
 * profile/ (the vault key), so a backup and a move carry them and Erase everything wipes them.
 *
 *  - [pictures]: who has a picture here, key to its version ("me" for mine); [picture] decodes one (cached).
 *  - [statuses]: whose app is open right now and what they chose: key to (status, custom text). Fed by Chat from
 *    room "live", trimmed as Chat's active map runs out. [statusOf] adds "offline" and "" for everyone else.
 *  - My picture and colour go, sealed through the ratchet, to every contact and everyone in a group with me whose
 *    app said p1, once per person per version ([reconcile]); never to anyone else.
 */
class Profile(context: Context, private val vault: Vault, private val contacts: Contacts, private val chat: Chat) {
    /** v, colour and the JPEG (empty when none) of one person. */
    private class Entry(val v: Long, val col: String, val pic: ByteArray)

    private val dir = File(context.filesDir, "profile")
    private val meFile = File(dir, "me.json")
    private val sentFile = File(dir, "sent.json")
    private val lock = Any()
    private val peers = ConcurrentHashMap<String, Entry>()
    private val sent = ConcurrentHashMap<String, Long>()
    @Volatile private var me = Entry(0, "", ByteArray(0))
    private val bitmaps = LruCache<String, Bitmap>(48)
    /** Keys a live active message ever came from (so they show offline, not nothing, once it runs out). */
    private val heard: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _myStatus = MutableStateFlow("online")
    val myStatus: StateFlow<String> = _myStatus.asStateFlow()
    private val _myCustomStatus = MutableStateFlow("")
    val myCustomStatus: StateFlow<String> = _myCustomStatus.asStateFlow()
    private val _myColour = MutableStateFlow("")
    val myColour: StateFlow<String> = _myColour.asStateFlow()
    private val _statuses = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    val statuses: StateFlow<Map<String, Pair<String, String>>> = _statuses.asStateFlow()
    private val _pictures = MutableStateFlow<Map<String, Int>>(emptyMap())
    val pictures: StateFlow<Map<String, Int>> = _pictures.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile private var due = false

    init {
        load()
        // Someone new (a contact, a group member, an app that now says p1): my picture goes to them.
        scope.launch { contacts.all.collect { reconcileSoon() } }
        scope.launch { chat.groups.all.collect { reconcileSoon() } }
        // Their active status ran out: nothing to show but offline.
        scope.launch { chat.active.collect { a -> _statuses.update { m -> if (m.keys.all { it in a }) m else m.filterKeys { it in a } } } }
        scope.launch { while (true) { delay(60_000); reconcileSoon() } }
    }

    // ------------------------------------------------------------------ mine

    fun setStatus(s: String) {
        if (s !in ProfileWire.MY_STATUSES || s == _myStatus.value) return
        _myStatus.value = s
        saveMe()
        chat.announceStatus()
    }

    fun setCustomStatus(s: String) {
        val c = ProfileWire.cleanCustom(s)
        if (c == _myCustomStatus.value) return
        _myCustomStatus.value = c
        saveMe()
        chat.announceStatus()
    }

    /** My colour for the initial-and-colour fallback ("#rrggbb"); goes out with the picture. */
    fun setMyColour(hex: String) {
        val c = hex.trim().lowercase()
        if (!ProfileWire.colourOk(c) || c == me.col) return
        change(Entry(me.v + 1, c, me.pic))
    }

    /** My chosen name changed: a new version goes to everyone who has me, carrying it ([ProfileWire.msg]). */
    fun nameChanged() {
        change(Entry(me.v + 1, me.col.ifEmpty { ProfileWire.defaultColour(chat.myKey()) }, me.pic))
    }

    /** Crops [b] to its centre square, scales it to 256x256 and encodes it small enough to send. False if it can't be. */
    fun setMyPicture(b: Bitmap): Boolean {
        val bytes = encode(b) ?: return false
        change(Entry(me.v + 1, me.col.ifEmpty { ProfileWire.defaultColour(chat.myKey()) }, bytes))
        return true
    }

    fun clearMyPicture() {
        if (me.pic.isEmpty()) return
        change(Entry(me.v + 1, me.col, ByteArray(0)))
    }

    private fun change(e: Entry) {
        synchronized(lock) {
            me = e
            bitmaps.remove("me")
            saveMe()
        }
        _myColour.value = e.col
        publishPictures()
        reconcileSoon()
    }

    /** The JPEG to send: the centre square at 256x256, the first quality of the ladder that fits, or null. */
    private fun encode(b: Bitmap): ByteArray? {
        val s = minOf(b.width, b.height)
        if (s < 1) return null
        val square = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        val small = Bitmap.createScaledBitmap(square, ProfileWire.SIDE, ProfileWire.SIDE, true)
        for (q in ProfileWire.QUALITIES) {
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, q, out)
            val bytes = Photos.scrub(out.toByteArray()) ?: continue
            if (bytes.size <= ProfileWire.SEND_MAX_BYTES && ProfileWire.pictureOk(bytes)) return bytes
        }
        return null
    }

    // ------------------------------------------------------------------ everyone

    /** The status to show for [key]: online, away, dnd, offline or "" when nothing is known. "me" is my own. */
    fun statusOf(key: String): String {
        if (key == "me") return _myStatus.value
        if (!chat.activeStatus()) return ""
        val s = _statuses.value[key]
        return ProfileWire.statusOf(chat.active.value.containsKey(key), s?.first ?: "", key in heard || contacts.get(key)?.caps?.contains(Rich.CAP) == true)
    }

    /** The colour for [key]'s initial disc: the one they sent, else one from their key. */
    fun colourOf(key: String): String = if (key == "me") me.col.ifEmpty { ProfileWire.defaultColour(chat.myKey()) }
        else peers[key]?.col?.ifEmpty { null } ?: ProfileWire.defaultColour(key)

    /** The picture of [key] ("me" for mine), decoded once and kept, or null when there is none. */
    fun picture(key: String): Bitmap? {
        bitmaps.get(key)?.let { return it }
        val bytes = (if (key == "me") me else peers[key])?.pic?.takeIf { it.isNotEmpty() } ?: return null
        val b = try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null } ?: return null
        bitmaps.put(key, b)
        return b
    }

    /** Chat took a fresh "active" from [key] (room "live"): what their app says they chose. */
    fun tookLive(key: String, on: Boolean, p: JSONObject) {
        heard.add(key)
        if (!on) { _statuses.update { it - key }; return }
        val st = p.optString("st").takeIf { it in Rich.STATUSES } ?: "online"
        val cs = (p.opt("cs") as? String)?.takeIf { Rich.customOk(it) } ?: ""
        _statuses.update { it + (key to (st to cs)) }
    }

    /**
     * A room "profile" message from [fromKey] (a contact or someone in a group with me, Chat checked). False when it's
     * malformed; an older or same version is taken and changes nothing.
     */
    fun receive(fromKey: String, p: JSONObject): Boolean {
        if (!ProfileWire.ok(p)) return false
        val v = (p.opt("v") as Number).toLong()
        val pic = p.getString("pic")
        val bytes = if (pic.isEmpty()) ByteArray(0) else (ProfileWire.b64d(pic)?.let { Photos.scrub(it) } ?: return false)
        if (bytes.isNotEmpty() && !ProfileWire.pictureOk(bytes)) return false
        synchronized(lock) {
            val cur = peers[fromKey]
            if (cur != null && !ProfileWire.takes(cur.v, v)) return true
            // A new chosen name: the contact's name here follows (a nickname set on this phone still shows first).
            ProfileWire.nameOf(p)?.let { n -> contacts.get(fromKey)?.let { c -> if (c.name != n) contacts.set(c.copy(name = n)) } }
            val e = Entry(v, p.getString("col").lowercase(), bytes)
            peers[fromKey] = e
            bitmaps.remove(fromKey)
            try { dir.mkdirs(); vault.write(file(fromKey), toJson(fromKey, e).toString()) } catch (_: Exception) {}
        }
        publishPictures()
        return true
    }

    /** Someone removed for good: their picture goes. */
    fun forget(key: String) {
        synchronized(lock) {
            peers.remove(key)
            sent.remove(key)
            bitmaps.remove(key)
            file(key).delete()
            saveSent()
        }
        heard.remove(key)
        _statuses.update { it - key }
        publishPictures()
    }

    // ------------------------------------------------------------------ sending

    private fun reconcileSoon() {
        if (due) return
        due = true
        sender.execute { try { Thread.sleep(1500); due = false; reconcile() } catch (e: Exception) { due = false } }
    }

    /**
     * My picture and colour to everyone who should have this version and doesn't yet: contacts, and members of my
     * groups (who are group peers in Contacts), only those whose app said p1, never anyone blocked or in the trash.
     * One is marked sent only once a route took it, so someone out of reach gets it on a later pass.
     */
    private fun reconcile() {
        if (Move.locked) return
        val mine = me
        if (mine.v == 0L) return
        val plain = ProfileWire.msg(mine.v, if (mine.pic.isEmpty()) "" else java.util.Base64.getEncoder().encodeToString(mine.pic), mine.col, chat.chosenName())
        val blocked = chat.blocked.value
        var changed = false
        for (c in contacts.all.value) {
            val k = c.chatKey
            if (k in blocked || chat.trash.has(k) || ProfileWire.CAP !in c.caps) continue
            // A group peer gets it only while we're in a group together; once that's over, their picture goes.
            if (c.groupOnly && !chat.groups.sharesGroup(k)) { if (peers.containsKey(k)) forget(k); continue }
            if (sent[k] == mine.v) continue
            if (chat.sendProfile(k, plain)) { sent[k] = mine.v; changed = true }
        }
        if (changed) synchronized(lock) { saveSent() }
    }

    // ------------------------------------------------------------------ files

    private fun file(key: String) = File(dir, ChatWire.fileName(key) + ".json")

    private fun toJson(key: String, e: Entry): JSONObject = JSONObject().put("k", key).put("v", e.v).put("col", e.col)
        .put("pic", if (e.pic.isEmpty()) "" else java.util.Base64.getEncoder().encodeToString(e.pic))

    private fun fromJson(o: JSONObject): Entry? {
        val pic = o.optString("pic")
        val bytes = if (pic.isEmpty()) ByteArray(0) else ProfileWire.b64d(pic) ?: return null
        return Entry(o.optLong("v"), o.optString("col"), bytes)
    }

    private fun saveMe() {
        try {
            dir.mkdirs()
            vault.write(meFile, toJson("me", me).put("st", _myStatus.value).put("cs", _myCustomStatus.value).toString())
        } catch (_: Exception) {}
    }

    private fun saveSent() {
        try { dir.mkdirs(); vault.write(sentFile, JSONObject().apply { sent.forEach { (k, v) -> put(k, v) } }.toString()) } catch (_: Exception) {}
    }

    private fun load() {
        try {
            vault.read(meFile)?.let { JSONObject(it) }?.let { o ->
                fromJson(o)?.let { me = it }
                _myStatus.value = o.optString("st").takeIf { it in ProfileWire.MY_STATUSES } ?: "online"
                _myCustomStatus.value = ProfileWire.cleanCustom(o.optString("cs"))
                _myColour.value = me.col
            }
            vault.read(sentFile)?.let { JSONObject(it) }?.let { o -> o.keys().forEach { sent[it] = o.optLong(it) } }
            dir.listFiles()?.forEach { f ->
                if (f == meFile || f == sentFile || !f.name.endsWith(".json")) return@forEach
                val o = try { vault.read(f)?.let { JSONObject(it) } } catch (e: Exception) { null } ?: return@forEach
                val k = o.optString("k")
                if (k.isNotEmpty()) fromJson(o)?.let { peers[k] = it }
            }
        } catch (_: Exception) {}
        publishPictures()
    }

    private fun publishPictures() {
        val m = HashMap<String, Int>()
        peers.forEach { (k, e) -> if (e.pic.isNotEmpty()) m[k] = e.v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        if (me.pic.isNotEmpty()) m["me"] = me.v.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        _pictures.value = m
    }
}
