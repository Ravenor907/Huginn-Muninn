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
 *  - cap p2 (22.4): "pv" on every envelope to a p2 app, "profile?" asks, "profile-ok" answers a picture; to a p2 app
 *    a picture counts as sent only once its "profile-ok" came, and goes again with backoff until then.
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

    /**
     * The chosen name a profile message carries ("nm"), or null when it has none or it isn't usable. Round 17: cleaned
     * like a nudge's name (no control, format or direction characters, trimmed, at most [MAX_NAME]).
     */
    fun nameOf(p: JSONObject): String? {
        val raw = (p.opt("nm") as? String)?.trim() ?: return null
        if (raw.isEmpty() || raw.length > MAX_NAME || raw.any { it.code < 0x20 || it.code == 0x7F }) return null
        return NudgeWire.clean(raw).ifEmpty { null }
    }

    /**
     * Round 17: the name a contact's "nm" leaves here: [n] when it differs and no nickname was set on this phone,
     * else null (nothing changes; a name set here always wins).
     */
    fun renameTo(current: String, nickname: String?, n: String?): String? =
        if (n == null || !nickname.isNullOrEmpty() || n == current) null else n

    /** What's said when a contact's own name changes here. */
    fun renamedText(old: String, new: String): String = "$old is now called $new."

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

    // ------------------------------------------------------------------ cap p2 (22.4): pictures that always arrive

    /**
     * Cap p2: "pv" (my profile version) on every envelope to an app that says p2, "profile?" to ask for someone's
     * current one, "profile-ok" to say one came. A picture to a p2 app is marked sent only on its "profile-ok".
     */
    const val CAP2 = "p2"
    const val ASK = "profile?"
    const val ACK = "profile-ok"
    /** At most one "profile?" to a person in this long (one a "pv" or a Refresh may start). */
    const val ASK_MS = 5 * 60_000L
    /** Refresh by hand: no sooner than this after the last ask. */
    const val REFRESH_MS = 10_000L
    /**
     * Round 16: a person's "profile?" is answered at most once per version of mine in this long, and only when the
     * version they say they keep ("pv" in the ask) is older than mine. Pictures sent after a restart wait this long
     * after the last one to that person too.
     */
    const val DAY_MS = 24 * 3_600_000L
    /** At most this many pictures go out in one day (UTC), to everyone together: first sends, retries and answers. */
    const val DAY_MAX = 200
    /** "profile?" and "profile-ok" from one sender: at most this many in 10 s (apart from the picture's 1 in 10 s). */
    const val CTL_PER_10S = 5
    /** No "profile-ok": sent again after 2, 4, 8 ... minutes, at most 6 hours apart, at most this many times. */
    const val FIRST_RETRY_MS = 2 * 60_000L
    const val MAX_RETRY_MS = 6 * 3_600_000L
    const val MAX_TRIES = 10

    /** A usable version ("v" or "pv"): a whole number, 1 <= v < 2^53; null for anything else. */
    fun versionOf(x: Any?): Long? = when (x) {
        is Int -> x.toLong().takeIf { it >= 1 }
        is Long -> x.takeIf { it >= 1 && it < (1L shl 53) }
        else -> null
    }

    /** The sender's profile version an envelope carries ("pv"), or null. */
    fun pvOf(o: JSONObject): Long? = versionOf(o.opt("pv"))

    /** "profile?" saying the version of theirs I keep ([have], 0: none). */
    fun askMsg(have: Long): String = JSONObject().put("t", ASK).put("pv", have.coerceIn(0L, (1L shl 53) - 1)).toString()

    /**
     * The version of mine the asker says it keeps ("pv" in a "profile?": a whole number, 0 <= pv < 2^53). An app from
     * before round 16 doesn't say, and anything unusable counts the same: 0, so it is still answered (within the
     * once-a-day limit).
     */
    fun askedPv(p: JSONObject): Long = when (val x = p.opt("pv")) {
        is Int -> x.toLong().takeIf { it >= 0 } ?: 0L
        is Long -> x.takeIf { it >= 0 && it < (1L shl 53) } ?: 0L
        else -> 0L
    }

    /**
     * Answer a "profile?": I have a version [mine], the asker keeps an older one ([theirs]), and the last answer to
     * them ([lastV] at [lastAt], 0 0: none) was another version or at least a day ago.
     */
    fun mayAnswer(mine: Long, theirs: Long, lastV: Long, lastAt: Long, now: Long): Boolean =
        mine >= 1 && theirs < mine && (lastV != mine || lastAt == 0L || now - lastAt >= DAY_MS || now < lastAt)
    fun ackMsg(v: Long): String = JSONObject().put("t", ACK).put("v", v).toString()

    /** "profile?" or "profile-ok" (the small ones in room "profile"), from the plaintext; false for anything else. */
    fun isCtl(plain: String): Boolean = try { JSONObject(plain).opt("t").let { it == ASK || it == ACK } } catch (e: Exception) { false }

    /** Well-formed "profile?" (other fields ignored) or "profile-ok" with a usable "v". */
    fun ctlOk(p: JSONObject): Boolean = when (p.opt("t")) {
        ASK -> true
        ACK -> versionOf(p.opt("v")) != null
        else -> false
    }

    /** Someone says their version is [pv] and I keep [have] (0: nothing): ask for it. */
    fun wants(have: Long, pv: Long) = pv > have

    /** Nothing asked of them yet ([last] 0), or the last ask was at least [gap] ago. */
    fun mayAsk(last: Long, now: Long, gap: Long = ASK_MS) = last == 0L || now - last >= gap

    /** The wait after the [tries]-th send with no "profile-ok": 2 minutes doubling, at most 6 hours. */
    fun retryMs(tries: Int): Long {
        if (tries <= 1) return FIRST_RETRY_MS
        val shift = minOf(tries - 1, 20)
        return minOf(FIRST_RETRY_MS shl shift, MAX_RETRY_MS)
    }

    /**
     * Send now: never sent ([tries] 0), or not yet [MAX_TRIES] and the wait after the last send ([at]) is over. [old]:
     * that last send was before this start, so it goes again only a day after it (a restart never sends to everyone).
     */
    fun due(tries: Int, at: Long, now: Long, old: Boolean = false): Boolean = when {
        tries == 0 -> true
        tries >= MAX_TRIES -> false
        old -> now - at >= DAY_MS || now < at
        else -> now - at >= retryMs(tries)
    }

    /** Pictures sent on [day] (days since 1970, UTC): [n] of at most [DAY_MAX]. */
    class DayBudget(var day: Long = 0L, var n: Int = 0) {
        private fun roll(now: Long) { val d = Math.floorDiv(now, DAY_MS); if (d != day) { day = d; n = 0 } }
        /** One more may go now. */
        fun has(now: Long): Boolean { roll(now); return n < DAY_MAX }
        /** One went. */
        fun spend(now: Long) { roll(now); n++ }
    }

    /** A "profile-ok" for [v] marks my version [mine] as theirs only when it is that version. */
    fun acks(mine: Long, v: Long) = mine >= 1 && v == mine
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
    /**
     * Cap p2: my version [v] sent [n] times to someone with no "profile-ok" yet, the last at [at]; [old] when that was
     * before this start. Kept in p2.json with [answered] and [budget], so a restart doesn't send everything again.
     */
    private class Try(val v: Long, val n: Int, val at: Long, val old: Boolean = false)
    private val p2File = File(dir, "p2.json")
    private val tries = ConcurrentHashMap<String, Try>()
    /** Cap p2: when I last asked each person for theirs (memory only). */
    private val asked = ConcurrentHashMap<String, Long>()
    /** Cap p2: the version of mine last sent because they asked, and when. */
    private class Answer(val v: Long, val at: Long)
    private val answered = ConcurrentHashMap<String, Answer>()
    /** People who asked and are answered on the next pass, whatever the retry wait. */
    private val answerNow: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Pictures sent today, everyone together. */
    private val budget = ProfileWire.DayBudget()
    @Volatile private var me = Entry(0, "", ByteArray(0))
    /** Decoded pictures, up to a sixteenth of the memory Android gives Muninn (by bytes, not by count). */
    private val bitmaps = object : LruCache<String, Bitmap>(MemoryBudget.bytes(
        try { context.getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 64 } catch (_: Exception) { 64 }, 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
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
        Link.sendMe()   // a linked companion shows it too
    }

    fun setCustomStatus(s: String) {
        val c = ProfileWire.cleanCustom(s)
        if (c == _myCustomStatus.value) return
        _myCustomStatus.value = c
        saveMe()
        chat.announceStatus()
        Link.sendMe()
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

    /** My picture as sent (a 256x256 JPEG), empty when none. */
    fun myPicture(): ByteArray = me.pic

    /** [key]'s picture as it came (a 256x256 JPEG; "me" for mine), empty when there is none. For a linked computer (cap l1). */
    fun pictureBytes(key: String): ByteArray = (if (key == "me") me else peers[key])?.pic ?: ByteArray(0)

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
        Link.sendMe()   // name or picture: a linked companion shows it too
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
            // Round 17: never over a name set on this phone, and said out loud when it changes.
            contacts.get(fromKey)?.let { c ->
                ProfileWire.renameTo(c.name, c.nickname, ProfileWire.nameOf(p))?.let { n ->
                    contacts.set(c.copy(name = n))
                    try { chat.notices.tryEmit(ProfileWire.renamedText(c.name, n)) } catch (_: Exception) {}
                }
            }
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
            tries.remove(key)
            asked.remove(key)
            answered.remove(key)
            answerNow.remove(key)
            bitmaps.remove(key)
            file(key).delete()
            saveSent()
            saveP2()
        }
        heard.remove(key)
        _statuses.update { it - key }
        publishPictures()
    }

    // ------------------------------------------------------------------ cap p2 (22.4)

    /** My profile version, for "pv" on my envelopes (0: I have none yet, and no "pv" goes). */
    fun myVersion(): Long = me.v

    /** [key]'s app says p2. */
    fun saysP2(key: String): Boolean = contacts.get(key)?.caps?.contains(ProfileWire.CAP2) == true

    /** A contact, or a group peer while a group is shared; never anyone blocked or in the trash. */
    private fun inTouch(key: String): Boolean {
        val c = contacts.get(key) ?: return false
        if (key in chat.blocked.value || chat.trash.has(key)) return false
        return !c.groupOnly || chat.groups.sharesGroup(key)
    }

    /** Takes the slot for [key] in [map] when the last one is at least [gap] ago; false when it's too soon. */
    private fun claim(map: ConcurrentHashMap<String, Long>, key: String, gap: Long): Boolean {
        val now = System.currentTimeMillis()
        var ok = false
        map.compute(key) { _, last -> if (ProfileWire.mayAsk(last ?: 0L, now, gap)) { ok = true; now } else last }
        return ok
    }

    private fun ask(key: String) {
        val have = peers[key]?.v ?: 0L
        sender.execute { try { chat.sendProfile(key, ProfileWire.askMsg(have)) } catch (_: Exception) {} }
    }

    /** An envelope from [key] opened and says their version is [pv]: newer than the one here, so ask (once in 5 minutes). */
    fun sawVersion(key: String, pv: Long) {
        if (!ProfileWire.wants(peers[key]?.v ?: 0L, pv) || !saysP2(key) || !inTouch(key)) return
        if (claim(asked, key, ProfileWire.ASK_MS)) ask(key)
    }

    /**
     * Refresh on a person's page: ask [key] for their current picture now (no sooner than 10 s after the last ask).
     * False when their app can't be asked (no p2) or they're not someone I'm in touch with.
     */
    fun refresh(key: String): Boolean {
        if (!saysP2(key) || !inTouch(key)) return false
        if (claim(asked, key, ProfileWire.REFRESH_MS)) ask(key)
        return true
    }

    /**
     * [key] asked for mine ("profile?") and says it keeps version [theirs]: when that is older than mine, it goes once
     * now, at most once per version of mine a day. The retry count isn't reset, so asking never starts a new round.
     */
    fun askedBy(key: String, theirs: Long) {
        val mine = me.v
        if (mine == 0L || !saysP2(key) || !inTouch(key)) return
        val now = System.currentTimeMillis()
        var ok = false
        answered.compute(key) { _, a ->
            if (ProfileWire.mayAnswer(mine, theirs, a?.v ?: 0L, a?.at ?: 0L, now)) { ok = true; Answer(mine, now) } else a
        }
        if (!ok) return
        answerNow.add(key)
        synchronized(lock) { if (sent.remove(key) != null) saveSent(); saveP2() }
        sender.execute { try { reconcile() } catch (_: Exception) {} }
    }

    /** A picture from [key] was taken (200): a p2 app hears "profile-ok" with its version, so it stops sending it. */
    fun ack(key: String, v: Long) {
        if (!saysP2(key)) return
        sender.execute { try { chat.sendProfile(key, ProfileWire.ackMsg(v)) } catch (_: Exception) {} }
    }

    /** [key] says my version [v] came: only now is it marked sent to them. */
    fun acked(key: String, v: Long) {
        if (!ProfileWire.acks(me.v, v)) return
        val had = tries.remove(key) != null
        answerNow.remove(key)
        if (sent[key] == v) { if (had) synchronized(lock) { saveP2() }; return }
        sent[key] = v
        synchronized(lock) { saveSent(); saveP2() }
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
     * One is marked sent only once a route took it, so someone out of reach gets it on a later pass. Cap p2: an app
     * that says p2 has it only once its "profile-ok" came; until then it goes again after 2, 4, 8 ... minutes (after a
     * restart, only a day after the last send to them). Round 16: at most [ProfileWire.DAY_MAX] pictures a day in all.
     */
    private fun reconcile() {
        if (Move.locked) return
        val mine = me
        if (mine.v == 0L) return
        val plain = ProfileWire.msg(mine.v, if (mine.pic.isEmpty()) "" else java.util.Base64.getEncoder().encodeToString(mine.pic), mine.col, chat.chosenName())
        val blocked = chat.blocked.value
        val now = System.currentTimeMillis()
        var changed = false
        var counted = false
        for (c in contacts.all.value) {
            val k = c.chatKey
            if (k in blocked || chat.trash.has(k) || ProfileWire.CAP !in c.caps) continue
            // A group peer gets it only while we're in a group together; once that's over, their picture goes.
            if (c.groupOnly && !chat.groups.sharesGroup(k)) { if (peers.containsKey(k)) forget(k); continue }
            if (sent[k] == mine.v) continue
            if (!budget.has(now)) break
            if (ProfileWire.CAP2 in c.caps) {
                val t = tries[k]?.takeIf { it.v == mine.v }
                val wanted = k in answerNow
                if (!wanted && !ProfileWire.due(t?.n ?: 0, t?.at ?: 0L, now, t?.old == true)) continue
                if (chat.sendProfile(k, plain)) {
                    answerNow.remove(k)
                    tries[k] = Try(mine.v, (t?.n ?: 0) + 1, now); budget.spend(now); counted = true
                }
                continue
            }
            if (chat.sendProfile(k, plain)) { sent[k] = mine.v; changed = true; budget.spend(now); counted = true }
        }
        if (changed || counted) synchronized(lock) { if (changed) saveSent(); if (counted) saveP2() }
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

    /** Cap p2's retry state, answers and today's count: {"day","n","try":{key:[v,n,at]},"ans":{key:[v,at]}}. */
    private fun saveP2() {
        try {
            dir.mkdirs()
            val t = JSONObject().apply { tries.forEach { (k, x) -> put(k, org.json.JSONArray().put(x.v).put(x.n).put(x.at)) } }
            val a = JSONObject().apply { answered.forEach { (k, x) -> put(k, org.json.JSONArray().put(x.v).put(x.at)) } }
            vault.write(p2File, JSONObject().put("day", budget.day).put("n", budget.n).put("try", t).put("ans", a).toString())
        } catch (_: Exception) {}
    }

    private fun saveSent() {
        try { dir.mkdirs(); vault.write(sentFile, JSONObject().apply { sent.forEach { (k, v) -> put(k, v) } }.toString()) } catch (_: Exception) {}
    }

    private fun load() {
        // One that can't be used is never saved over (see Vault.loadFailed), and the rest still load.
        try {
            vault.read(meFile)?.let { JSONObject(it) }?.let { o ->
                fromJson(o)?.let { me = it }
                _myStatus.value = o.optString("st").takeIf { it in ProfileWire.MY_STATUSES } ?: "online"
                _myCustomStatus.value = ProfileWire.cleanCustom(o.optString("cs"))
                _myColour.value = me.col
            }
        } catch (e: Exception) { vault.loadFailed(meFile, e) }
        try {
            vault.read(sentFile)?.let { JSONObject(it) }?.let { o -> o.keys().forEach { sent[it] = o.optLong(it) } }
        } catch (e: Exception) { vault.loadFailed(sentFile, e) }
        try {
            vault.read(p2File)?.let { JSONObject(it) }?.let { o ->
                budget.day = o.optLong("day"); budget.n = o.optInt("n")
                o.optJSONObject("try")?.let { t -> t.keys().forEach { k ->
                    t.optJSONArray(k)?.let { x -> tries[k] = Try(x.optLong(0), x.optInt(1), x.optLong(2), old = true) } } }
                o.optJSONObject("ans")?.let { a -> a.keys().forEach { k ->
                    a.optJSONArray(k)?.let { x -> answered[k] = Answer(x.optLong(0), x.optLong(1)) } } }
            }
        } catch (_: Exception) {}   // only limits: one that can't be read starts them anew
        try {
            dir.listFiles()?.forEach { f ->
                if (f == meFile || f == sentFile || f == p2File || !f.name.endsWith(".json")) return@forEach
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
        if (_pictures.value != m) Link.accountChanged()   // cap l1: a linked computer shows the pictures too
        _pictures.value = m
    }
}
