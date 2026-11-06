package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * People this phone can message from anywhere. A contact is added by meeting nearby (and checking
 * the 6-digit code) or by sharing a contact code, which carries the name, the chat key and the
 * Nostr mailbox:  MUNINN1:<base64url of {"n":name,"k":chatKey,"p":mailbox,"i":invite,"x":expires}>
 * (a code may also carry "o", an onion address; it is ignored).
 * The invite is good for an hour: after that a leaked code can't be used to reach someone
 * who isn't already in touch (their phone drops messages without a valid invite).
 */
class Contacts(context: Context, private val vault: Vault) {
    private val file = File(context.filesDir, "contacts.json")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Contact>> = _all.asStateFlow()

    fun get(chatKey: String) = _all.value.firstOrNull { it.chatKey == chatKey }

    /** A real contact: not a group peer. */
    fun contact(chatKey: String) = get(chatKey)?.takeIf { !it.groupOnly }

    /**
     * Someone in a group with me who isn't a contact: kept so they can be reached (protocol 0.6, 3.7).
     * Only apps with g1 can join a group, so they speak the ratchet, groups and calls. A contact stays a contact.
     */
    @Synchronized
    fun peer(chatKey: String, name: String, mailbox: String) {
        try { ChatCrypto.publicFrom(chatKey) } catch (e: Exception) { return }
        val old = get(chatKey)
        if (old != null && !old.groupOnly) return
        val mb = mailbox.takeIf { it.length == 64 } ?: old?.mailbox
        // Keep what was already learned about them (caps, the s1 binding, their message limit): a group change
        // must never forget it, or messages to them go out in a form their app has stopped taking.
        val c = (old ?: Contact(chatKey, "", mb, verified = false)).copy(name = name.take(40).ifBlank { old?.name ?: "Someone" },
            mailbox = mb, ratchet = true, groupOnly = true, caps = old?.caps.orEmpty() + setOf("r1", "g1", "c1"))
        if (old == c) return
        _all.value = _all.value.filterNot { it.chatKey == chatKey } + c
        save()
    }

    /** Adds or updates. Never downgrades: a verified contact stays verified, a known mailbox is kept. */
    @Synchronized
    fun put(c: Contact) {
        val old = get(c.chatKey)
        val merged = if (old == null) c else ChatWire.merge(old, c)
        _all.value = _all.value.filterNot { it.chatKey == c.chatKey } + merged
        save()
    }

    /** Stores [c] exactly as given (verify, rename, ratchet on). */
    @Synchronized
    fun set(c: Contact) {
        if (get(c.chatKey) == null) return
        _all.value = _all.value.map { if (it.chatKey == c.chatKey) c else it }
        save()
    }

    /** Their key changed: the contact at [oldKey] becomes [c] (a new key). */
    @Synchronized
    fun replaceKey(oldKey: String, c: Contact) {
        _all.value = _all.value.filterNot { it.chatKey == oldKey || it.chatKey == c.chatKey } + c
        save()
    }

    @Synchronized
    fun rename(chatKey: String, nickname: String) {
        val c = get(chatKey) ?: return
        set(c.copy(nickname = ChatWire.nickname(nickname)))
    }

    @Synchronized
    fun remove(chatKey: String) {
        _all.value = _all.value.filterNot { it.chatKey == chatKey }
        save()
    }

    private fun save() {
        val a = JSONArray()
        _all.value.forEach { a.put(ChatWire.contactToJson(it)) }
        // Refused when the saved list didn't load: the list in memory isn't the whole one, so it never replaces it.
        try { vault.write(file, a.toString()) } catch (_: VaultCore.Refused) {}
    }

    private fun load(): List<Contact> = try {
        val a = JSONArray(vault.read(file) ?: return emptyList())
        (0 until a.length()).map { ChatWire.contactFromJson(a.getJSONObject(it)) }
    } catch (e: Exception) { vault.loadFailed(file, e); emptyList() }

    companion object {
        const val PREFIX = ChatWire.PREFIX
        /** What the QR holds: a page that opens Muninn, or offers the download if it isn't installed.
         *  The code is after "#", which browsers never send to the server. */
        const val PAGE = "https://ravenor907.github.io/Huginn-Muninn/add/#"
        fun link(code: String) = PAGE + code

        /**
         * The code in a pasted link or a scanned page address, as well as a bare code. Some apps
         * write the ":" in a link as "%3A": read that the same.
         */
        fun unlink(text: String): String = text.replace(Regex("MUNINN1%3A", RegexOption.IGNORE_CASE), PREFIX)

        /** Only ever my own name and keys: a nickname is never in a code. */
        fun code(name: String, chatKey: String, mailbox: String, invite: String? = null, expires: Long = 0): String =
            ChatWire.code(name, chatKey, mailbox, invite, expires)

        /** When the code's invite runs out (ms), or 0 for codes without one. */
        fun expires(link: String): Long {
            val text = unlink(link)
            val i = text.indexOf(PREFIX)
            if (i < 0) return 0
            val body = text.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return try { JSONObject(String(java.util.Base64.getUrlDecoder().decode(body))).optLong("x") * 1000 } catch (e: Exception) { 0 }
        }

        /** An invite link that works once: whoever adds it is added back at once, no asking (the token is used up). */
        fun once(link: String): Boolean {
            val text = unlink(link)
            val i = text.indexOf(PREFIX)
            if (i < 0) return false
            val body = text.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return try { JSONObject(String(java.util.Base64.getUrlDecoder().decode(body))).optBoolean("1") } catch (e: Exception) { false }
        }

        /** Finds a contact code anywhere in pasted text (a whole message is fine). */
        fun parse(link: String): Contact? {
            val text = unlink(link)
            val i = text.indexOf(PREFIX)
            if (i < 0) return null
            val body = text.substring(i + PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            return try {
                val o = JSONObject(String(java.util.Base64.getUrlDecoder().decode(body)))
                val key = o.getString("k")
                ChatCrypto.publicFrom(key)   // must be a real key
                // Whoever gave you their code is who they say: the code itself carries their key.
                Contact(key, o.optString("n").take(40).ifBlank { "Someone" }, o.optString("p").takeIf { it.length == 64 }, verified = true,
                    invite = o.optString("i").ifEmpty { null })
            } catch (e: Exception) { null }
        }
    }
}

/**
 * Contact folders (protocol 0.6, section 4): local only, never on the wire. A folder is a name and the
 * chat keys (or "g:" + group id) in it. Family, Work and Folk are there to start with; all of them can be
 * renamed or deleted, and new ones added. Stored through [write] (the vault), so it goes in the backup too.
 * The chat list shows each folder as a section that folds away; a folder's keys are in the order shown there,
 * and [top] is the order of the chats in no folder (the ones never moved go first, newest first).
 */
class Folders(private val read: () -> String?, private val write: (String) -> Unit) {
    data class Folder(val id: String, val name: String, val keys: List<String> = emptyList(), val collapsed: Boolean = false)

    private val text = try { read() } catch (e: Exception) { null }
    private val _top = MutableStateFlow(loadTop())
    /** The chats in no folder, in the order they were put in (only the ones moved by hand). */
    val top: StateFlow<List<String>> = _top.asStateFlow()
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Folder>> = _all.asStateFlow()

    private fun load(): List<Folder> {
        if (text == null) return DEFAULTS.map { Folder(newId(), it) }.also { save(it, emptyList()) }
        return try {
            val a = JSONObject(text).getJSONArray("folders")
            val l = (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                val k = o.optJSONArray("k")
                Folder(o.getString("id"), o.getString("n"), if (k == null) emptyList() else (0 until k.length()).map { k.getString(it) },
                    o.optBoolean("c"))
            }
            // A starting folder named OLD_NAME is renamed Folk.
            if (l.none { it.name == OLD_NAME }) l
            else l.map { if (it.name == OLD_NAME) it.copy(name = "Folk") else it }.also { save(it, loadTop()) }
        } catch (e: Exception) { emptyList() }
    }

    private fun loadTop(): List<String> = try {
        val a = JSONObject(text ?: return emptyList()).optJSONArray("top")
        if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList() }

    private fun save(l: List<Folder>, top: List<String>) {
        val a = JSONArray()
        l.forEach { f -> a.put(JSONObject().put("id", f.id).put("n", f.name).put("k", JSONArray().apply { f.keys.forEach { put(it) } })
            .apply { if (f.collapsed) put("c", true) }) }
        try { write(JSONObject().put("folders", a).put("top", JSONArray().apply { top.forEach { put(it) } }).toString()) } catch (_: Exception) {}
    }

    private fun set(l: List<Folder>, top: List<String> = _top.value) { _all.value = l; _top.value = top; save(l, top) }

    /** A new folder; returns its id, or null for an empty name. */
    @Synchronized
    fun add(name: String): String? {
        val n = name.trim().take(MAX_NAME).ifEmpty { return null }
        val f = Folder(newId(), n)
        set(_all.value + f)
        return f.id
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val n = name.trim().take(MAX_NAME).ifEmpty { return }
        set(_all.value.map { if (it.id == id) it.copy(name = n) else it })
    }

    /** Deletes the folder only: the chats in it stay. */
    @Synchronized
    fun delete(id: String) = set(_all.value.filterNot { it.id == id })

    /** A linked computer's folder (cap l1): made with that id, or renamed and given those chats. */
    @Synchronized
    fun putWhole(id: String, name: String, keys: List<String>) {
        val n = name.trim().take(MAX_NAME).ifEmpty { return }
        if (id.isEmpty()) return
        val old = _all.value.firstOrNull { it.id == id }
        if (old == null) set(_all.value + Folder(id, n, keys.distinct()))
        else if (old.name != n || old.keys != keys.distinct()) set(_all.value.map { if (it.id == id) it.copy(name = n, keys = keys.distinct()) else it })
    }

    @Synchronized
    fun put(id: String, key: String, on: Boolean) = set(_all.value.map { f ->
        if (f.id != id) f else if (on) f.copy(keys = (f.keys + key).distinct()) else f.copy(keys = f.keys - key)
    })

    /** The folders [key] is in. */
    fun of(key: String) = _all.value.filter { key in it.keys }

    /** A contact or group that's gone leaves every folder. */
    @Synchronized
    fun forget(key: String) {
        if (_all.value.any { key in it.keys } || key in _top.value) set(_all.value.map { it.copy(keys = it.keys - key) }, _top.value - key)
    }

    /** Folds a folder's section away on the chat list, or opens it again. */
    @Synchronized
    fun collapse(id: String, on: Boolean) = set(_all.value.map { if (it.id == id) it.copy(collapsed = on) else it })

    /**
     * Moves [key] from the section [from] to [to] ("" is no folder): it leaves [from] and goes in [to].
     * A chat in other folders too stays in them.
     */
    @Synchronized
    fun move(key: String, from: String, to: String) {
        if (from == to) return
        val l = _all.value.map { f ->
            when (f.id) {
                from -> f.copy(keys = f.keys - key)
                to -> f.copy(keys = (f.keys - key) + key)
                else -> f
            }
        }
        set(l, if (to == NONE) (_top.value - key) + key else _top.value - key)
    }

    /** The section [id] ("" is no folder) now shows [keys] in this order; what isn't shown keeps its place after them. */
    @Synchronized
    fun order(id: String, keys: List<String>) {
        if (id == NONE) set(_all.value, keys.distinct() + _top.value.filter { it !in keys })
        else set(_all.value.map { f -> if (f.id != id) f else f.copy(keys = keys.filter { it in f.keys }.distinct() + f.keys.filter { it !in keys }) })
    }

    companion object {
        val DEFAULTS = listOf("Family", "Work", "Folk")
        private const val OLD_NAME = "Kindred"
        const val MAX_NAME = 24
        /** The section of chats in no folder. */
        const val NONE = ""
        fun groupKey(g: String) = "g:$g"
        private fun newId() = java.util.UUID.randomUUID().toString().replace("-", "").take(12)

        /**
         * A section's chats as shown: [newest] (its chats, newest first) with the ones never moved by hand at the
         * top, then the rest in [order].
         */
        fun arrange(newest: List<String>, order: List<String>): List<String> {
            val placed = order.filter { it in newest }.distinct()
            return newest.filter { it !in placed } + placed
        }

        /** [list] with [key] moved [by] places (up is negative), stopping at either end. */
        fun shift(list: List<String>, key: String, by: Int): List<String> {
            val i = list.indexOf(key)
            if (i < 0) return list
            val to = (i + by).coerceIn(0, list.size - 1)
            if (to == i) return list
            return list.toMutableList().apply { removeAt(i); add(to, key) }
        }

        /** [list] with [key] put just before [before] (at the end when null or not there). */
        fun place(list: List<String>, key: String, before: String?): List<String> {
            val rest = list - key
            val i = if (before == null) -1 else rest.indexOf(before)
            return if (i < 0) rest + key else rest.toMutableList().apply { add(i, key) }
        }
    }
}

/**
 * Contacts in the trash (local only): their key and when they went in. Nothing from them reaches this phone
 * and their chat is hidden but kept, so putting them back brings everything back. After [DAYS] days they're
 * deleted for good. Stored through [write] (the vault), like [Folders].
 */
class Trash(private val read: () -> String?, private val write: (String) -> Unit) {
    private val _all = MutableStateFlow(load())
    /** Key to when it went in the trash (ms). */
    val all: StateFlow<Map<String, Long>> = _all.asStateFlow()

    private fun load(): Map<String, Long> = try {
        val o = JSONObject(read() ?: return emptyMap()).getJSONObject("trash")
        o.keys().asSequence().associateWith { o.getLong(it) }
    } catch (e: Exception) { emptyMap() }

    private fun set(m: Map<String, Long>) {
        _all.value = m
        try { write(JSONObject().put("trash", JSONObject(m as Map<*, *>)).toString()) } catch (_: Exception) {}
    }

    fun has(key: String) = key in _all.value

    @Synchronized
    fun put(key: String, now: Long) { if (key !in _all.value) set(_all.value + (key to now)) }

    @Synchronized
    fun drop(key: String) { if (key in _all.value) set(_all.value - key) }

    /** Who has been in the trash long enough to go for good. */
    fun expired(now: Long): List<String> = expired(_all.value, now)

    companion object {
        const val DAYS = 30
        const val KEEP_MS = DAYS * 86_400_000L

        fun expired(all: Map<String, Long>, now: Long): List<String> = all.filter { (_, t) -> now - t >= KEEP_MS }.keys.toList()

        /** Whole days left before [since] (when it went in) is deleted for good: at least 1 until it goes. */
        fun daysLeft(since: Long, now: Long): Int = ((KEEP_MS - (now - since) + 86_399_999L) / 86_400_000L).toInt().coerceAtLeast(1)
    }
}

/**
 * Per-chat choices kept on this phone only (never on the wire): muted until a time, archived, and marked unread
 * by hand. A key is a contact's chat key or "g:" + group id, like [Folders]. Stored through [write] (the vault).
 * An archived chat comes back to the list when a message arrives, unless it's muted (Messenger does the same).
 */
class ChatFlags(private val read: () -> String?, private val write: (String) -> Unit) {
    data class State(val muted: Map<String, Long> = emptyMap(), val archived: Set<String> = emptySet(), val unread: Set<String> = emptySet(),
                     /** The chat's own color: "#RRGGBB" (Chat color), or a [COLORS] name saved before; none is the accent. */
                     val colors: Map<String, String> = emptyMap(),
                     /** Pinned to the top of the chat list, in the order shown there. */
                     val pinned: List<String> = emptyList(),
                     /** Someone nearby who isn't a contact, put out of the Nearby list with Hide. Stays hidden. */
                     val hidden: Set<String> = emptySet(),
                     /** Delete chat: the row stays off the chat list until a message comes or goes again. */
                     val cleared: Set<String> = emptySet(),
                     /** How a chat notifies: [EVERY], [ONE] or [MENTIONS] (groups). None: as Settings › Notifications says. */
                     val notify: Map<String, String> = emptyMap(),
                     /** Send media for this chat ([MediaRoute]); none follows Settings. */
                     val routes: Map<String, String> = emptyMap(),
                     /** Family or trusted, set to Ask: their files show the card after all. */
                     val asks: Set<String> = emptySet())

    private val _all = MutableStateFlow(load())
    val all: StateFlow<State> = _all.asStateFlow()

    private fun load(): State = try {
        val o = JSONObject(read() ?: return State())
        val m = o.optJSONObject("muted")
        fun set(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
        val c = o.optJSONObject("colors")
        val p = o.optJSONArray("pinned")
        val n = o.optJSONObject("notify")
        State(m?.keys()?.asSequence()?.associateWith { m.getLong(it) }.orEmpty(), set("archived"), set("unread"),
            c?.keys()?.asSequence()?.associateWith { c.getString(it) }?.filterValues { colorOk(it) }.orEmpty(),
            if (p == null) emptyList() else (0 until p.length()).map { p.getString(it) }.distinct(),
            set("hidden"), set("cleared"),
            n?.keys()?.asSequence()?.associateWith { n.optString(it) }?.filterValues { it in MODES }.orEmpty(),
            o.optJSONObject("routes").let { r -> r?.keys()?.asSequence()?.associateWith { r.optString(it) }?.filterValues { it in MediaRoute.CHOICES }.orEmpty() },
            set("asks"))
    } catch (e: Exception) { State() }

    private fun set(s: State) {
        _all.value = s
        try {
            write(JSONObject().put("muted", JSONObject(s.muted as Map<*, *>)).put("archived", JSONArray(s.archived.toList()))
                .put("unread", JSONArray(s.unread.toList())).put("colors", JSONObject(s.colors as Map<*, *>))
                .put("pinned", JSONArray(s.pinned)).put("hidden", JSONArray(s.hidden.toList())).put("cleared", JSONArray(s.cleared.toList()))
                .put("notify", JSONObject(s.notify as Map<*, *>)).put("routes", JSONObject(s.routes as Map<*, *>))
                .put("asks", JSONArray(s.asks.toList())).toString())
        } catch (_: Exception) {}
    }

    /** No notifications from [key] until [until] (ms; [FOREVER] until turned back on). 0 turns it back on. */
    @Synchronized
    fun mute(key: String, until: Long) = set(_all.value.let { it.copy(muted = if (until <= 0) it.muted - key else it.muted + (key to until)) })

    fun muted(key: String, now: Long = System.currentTimeMillis()): Boolean = (_all.value.muted[key] ?: 0L) > now

    @Synchronized
    fun archive(key: String, on: Boolean) = set(_all.value.let { it.copy(archived = if (on) it.archived + key else it.archived - key) })

    fun archived(key: String) = key in _all.value.archived

    /** Mark as unread: the chat keeps its dot until it's opened. */
    @Synchronized
    fun markUnread(key: String, on: Boolean) {
        val s = _all.value
        if ((key in s.unread) != on) set(s.copy(unread = if (on) s.unread + key else s.unread - key))
    }

    /** The chat was opened: its dot goes, and the next message notifies again (Reduce notifications). */
    fun read(key: String) {
        synchronized(notified) { notified.remove(key) }
        markUnread(key, false)
    }

    /** [key]'s chat color: "#RRGGBB" (or one of [COLORS]), or "" for the accent. Only I see it. */
    @Synchronized
    fun color(key: String, name: String) = set(_all.value.let { it.copy(colors = if (colorOk(name)) it.colors + (key to name.trim()) else it.colors - key) })

    /** Pins [key] to the top of the chat list (newest pin first), or unpins it. */
    @Synchronized
    fun pin(key: String, on: Boolean) = set(_all.value.let { it.copy(pinned = if (on) listOf(key) + (it.pinned - key) else it.pinned - key) })

    fun pinned(key: String) = key in _all.value.pinned

    /** The pinned chats in a new order (moving one up or down among them). */
    @Synchronized
    fun orderPinned(keys: List<String>) = set(_all.value.let { s -> s.copy(pinned = keys.filter { it in s.pinned }.distinct() + s.pinned.filter { it !in keys }) })

    /** A message from [key] arrived: an archived chat comes back unless it's muted. Returns whether to notify. */
    @Synchronized
    fun arrived(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val quiet = muted(key, now)
        if (!quiet && key in _all.value.archived) archive(key, false)
        return !quiet
    }

    /** Hide (someone nearby who isn't a contact): off the Nearby list, and no notifications from them. */
    @Synchronized
    fun hide(key: String, on: Boolean) = set(_all.value.let { it.copy(hidden = if (on) it.hidden + key else it.hidden - key) })

    fun hidden(key: String) = key in _all.value.hidden

    /** Delete chat: the row leaves the chat list until a message comes or goes again (false: it shows again). */
    @Synchronized
    fun clear(key: String, on: Boolean) {
        val s = _all.value
        if ((key in s.cleared) != on) set(s.copy(cleared = if (on) s.cleared + key else s.cleared - key))
    }

    /** [key]'s own Send media, or null to follow Settings. */
    @Synchronized
    fun setRoute(key: String, c: String?) = set(_all.value.let { it.copy(routes = if (c in MediaRoute.CHOICES) it.routes + (key to c!!) else it.routes - key) })

    /** Family or trusted [key]'s files show the card after all ([on]), or arrive by themselves. */
    @Synchronized
    fun setAsk(key: String, on: Boolean) = set(_all.value.let { it.copy(asks = if (on) it.asks + key else it.asks - key) })

    /** [key]'s own notifications ([EVERY], [ONE] or [MENTIONS]); "" follows Settings › Notifications. */
    @Synchronized
    fun setNotify(key: String, mode: String) = set(_all.value.let { it.copy(notify = if (mode in MODES) it.notify + (key to mode) else it.notify - key) })

    /** How [key] notifies now: its own choice, else [MENTIONS] for a [big] group, else [ONE] with Reduce notifications on, else [EVERY]. */
    fun notifyMode(key: String, reduce: Boolean, big: Boolean = false): String = modeFor(_all.value.notify[key], reduce, key.startsWith("g:"), big)

    /** Chats that notified since they were last opened ([ONE] makes no more until then). */
    private val notified = HashSet<String>()

    /**
     * Whether a message that got past [arrived] notifies: [ONE] once per chat until it's opened ([read]), [MENTIONS]
     * only for an @mention or a reply to me ([toMe]). Calls aren't messages: they ring as before.
     */
    fun alert(key: String, reduce: Boolean, toMe: Boolean, big: Boolean = false): Boolean {
        val mode = notifyMode(key, reduce, big)
        if (mode == MENTIONS) return toMe
        if (mode != ONE) return true
        return synchronized(notified) { notified.add(key) }
    }

    /** A chat that's gone (deleted contact, left group) loses its flags. */
    @Synchronized
    fun forget(key: String) {
        val s = _all.value
        synchronized(notified) { notified.remove(key) }
        if (key in s.routes || key in s.asks || key in s.muted || key in s.archived || key in s.unread || key in s.colors || key in s.pinned || key in s.hidden || key in s.cleared || key in s.notify)
            set(s.copy(muted = s.muted - key, archived = s.archived - key, unread = s.unread - key, colors = s.colors - key, pinned = s.pinned - key,
                hidden = s.hidden - key, cleared = s.cleared - key, notify = s.notify - key, routes = s.routes - key, asks = s.asks - key))
    }

    companion object {
        const val FOREVER = Long.MAX_VALUE
        const val HOUR = 3_600_000L
        const val MINUTE = 60_000L
        /** A notification for every message. */
        const val EVERY = "every"
        /** One notification per chat until it's opened. */
        const val ONE = "one"
        /** Groups: only @mentions and replies to my messages. */
        const val MENTIONS = "mentions"
        val MODES = setOf(EVERY, ONE, MENTIONS)

        /** A group with more people than this starts on [MENTIONS] until its own choice is made. */
        const val BIG_GROUP = 10

        /**
         * [own]: the chat's choice, if any. [MENTIONS] is for groups only (a person's chat then notifies for every
         * message), and what a [big] group (over [BIG_GROUP] people) does until its own choice.
         */
        fun modeFor(own: String?, reduce: Boolean, group: Boolean, big: Boolean = false): String = when {
            own == MENTIONS && !group -> EVERY
            own != null && own in MODES -> own
            group && big -> MENTIONS
            reduce -> ONE
            else -> EVERY
        }
        /** A chat color that can be kept: "#RRGGBB" or one of [COLORS]. */
        fun colorOk(v: String): Boolean = v in COLORS || Regex("^#[0-9A-Fa-f]{6}$").matches(v.trim())

        /** Bubble colors saved by name before Chat color took the accents (still read). */
        val COLORS = linkedMapOf(
            "gold" to (0xFF3A3220L to 0xFFE0C27FL), "green" to (0xFF2C3A2CL to 0xFFB6D6B6L), "blue" to (0xFF2D3340L to 0xFFB9C6E0L),
            "red" to (0xFF3A2724L to 0xFFE6A596L), "purple" to (0xFF33293AL to 0xFFD2BCE0L), "teal" to (0xFF24363AL to 0xFFAED4D8L))

        /** "Muted for 3 hours", "Muted until tomorrow 7:00", "Muted": what the chat says while muted. */
        fun mutedText(until: Long, now: Long): String {
            if (until == FOREVER) return "Muted"
            val left = until - now
            if (left <= 0) return ""
            val h = (left + HOUR - 1) / HOUR
            return if (h <= 1) "Muted for less than an hour" else if (h < 48) "Muted for $h more hours" else "Muted for ${(h + 23) / 24} more days"
        }
    }
}
