package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

/**
 * Muninn's messaging. Three ways to reach someone, picked automatically, best first:
 *  1. Close by: Bluetooth / Wi-Fi Direct (Close), no network needed.
 *  2. Same Wi-Fi: the LocalSend port (also how Huginn chats).
 *  3. Anywhere: Nostr relays, for contacts (people met nearby or who shared a code).
 * Rooms:
 *  - Nearby: public, for everyone close by or on the same Wi-Fi. Usernames, no codes.
 *  - Private: one person. Sealed end to end with the chat keys whichever way it travels.
 *    Anyone close by can message straight away, no asking. After 10 messages, or once they're
 *    out of range, both get "Keep in touch?"; when both say yes they become contacts and can
 *    message from anywhere (the internet route is only for contacts).
 * One message format everywhere: { room, fromKey, fromName, mailbox, cap, caps, nonce, data } (v1), or with
 * "rh" in place of "nonce" when it went through the message ratchet (ChatWire has the details).
 * Groups (protocol 0.6, section 3) live in [groups]: each group message is sealed to every member
 * separately and goes out the same ways. Over the internet everything goes through the
 * Nostr relays, which only ever see a sealed envelope for a mailbox.
 */
class Chat(private val context: Context, private val net: LocalSend, private val close: Close, private val ble: Ble, private val nostr: Nostr, val contacts: Contacts,
           private val vault: Vault) : io.github.ravenor907.muninn.tv.TvBridge, io.github.ravenor907.muninn.net.call.CallSignal {
    private val id get() = net.identity
    /** Cap p1: profile pictures and status (net/Profile.kt), set by the app once both exist. */
    @Volatile var profile: Profile? = null
    fun myKey(): String = id.publicKey
    /** Settings › Active status: with it off nobody's dot shows and none is sent. */
    fun activeStatus(): Boolean = id.activeStatus
    /**
     * A call signal (offer, answer, hang up...): sealed like a private message, sent now on every route at
     * once (shared/docs/protocol-0.6.md 5.1, as Huginn does), never kept in the Outbox. Sealed once: the other side
     * keeps the first copy and drops the rest.
     */
    override fun send(contactKey: String, json: String) {
        if (Move.locked) return
        bg {
            val body = envelope("call", contactKey, json, UUID.randomUUID().toString()).toString()
            closeTo(contactKey)?.let { close.send(it, JSONObject(body).put("t", "msg")) }
            ble.send(contactKey, body, strict(contactKey))
            wifiTo(contactKey)?.let { net.post(it, PATH, body) }
            internet(contactKey, listOf(body), contacts.get(contactKey)?.mailbox)
            ring(contactKey, json)
        }
    }

    /**
     * Cap b1: a 1:1 offer also goes as a ring envelope to their ring mailbox, sealed to their ring key (section 23), so
     * their phone rings even while Muninn is locked there. Once per call.
     */
    private fun ring(contactKey: String, json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        if (o.optString("t") != "offer" || o.has("g")) return
        val c = contacts.get(contactKey) ?: return
        if (RingWire.RING_CAP !in c.caps) return
        val k = peerKeys.get(contactKey) ?: return
        val rk = k.rk ?: return
        val rp = k.rp ?: return
        val call = o.optString("call")
        val ts = o.optLong("ts", System.currentTimeMillis())
        if (call.isEmpty() || !rung.add(call)) return
        if (rung.size > 200) rung.clear()
        nostr.publish(rp, RingWire.ringContent(rk, call, ts), days = Limits.LONGEST)
    }
    @Volatile override var tvHandler: io.github.ravenor907.muninn.tv.TvHandler? = null
    override fun sendTvRoom(peerKey: String, plain: String) {
        bg { deliver(peerKey, envelope("tvconfig", peerKey, plain, UUID.randomUUID().toString()), contacts.get(peerKey)?.mailbox) }
    }
    override fun sendTvRoomEverywhere(peerKey: String, plain: String) {
        bg { spread(peerKey, listOf(envelope("tvconfig", peerKey, plain, UUID.randomUUID().toString()).toString()), false, 2, contacts.get(peerKey)?.mailbox) }
    }

    data class Msg(val id: String, val peerKey: String, val name: String, val text: String, val mine: Boolean, val time: Long,
                   val failed: Boolean = false, val via: String = "",
                   /** Mine: "sending", "sent", "delivered", "read". Theirs: "read" once a read receipt went back. */
                   val state: String = "",
                   /** A photo's id (stored encrypted in photos/); text is empty then. */
                   val photo: String = "",
                   /** Unsent: who took it back ("You" on the sender's phone, their name on the other). */
                   val unsent: String = "",
                   /** A voice message: its length in ms (the sound itself is stored like a photo, under [photo]). */
                   val voice: Int = 0,
                   /** A reply: the id of the message it quotes (cap m1). */
                   val re: String = "",
                   /** Reactions (cap m1): who to their latest one; an empty one is a removed reaction. */
                   val reactions: Map<String, Rich.Reaction> = emptyMap(),
                   /** Edited (cap m1): when the text shown was written (the editor's clock), 0 if never edited. */
                   val edited: Long = 0,
                   /** A video message (cap m2): its length in ms (stored like a photo, under [photo]). */
                   val video: Int = 0,
                   /** Part of an album (cap m2): its id, this photo's place and how many there are. */
                   val album: String = "", val albumAt: Int = 0, val albumOf: Int = 0,
                   /** A poll (cap m2), and who voted for what. */
                   val poll: More.Poll? = null, val votes: Map<String, More.Vote> = emptyMap(),
                   /** A link preview the sender's phone made (cap m2). */
                   val preview: More.Preview? = null,
                   /** A small file that came over the relays (cap f1): its name (the bytes are stored like a photo). */
                   val file: String = "")
    /** Someone met close by who isn't a contact (yet). */
    data class Met(val name: String, val mailbox: String?, val iSaid: Boolean = false, val theySaid: Boolean = false, val dismissed: Boolean = false,
                   val verified: Boolean = false,
                   /** What their app speaks, from their authentic envelopes (empty until one came). */
                   val caps: Set<String> = emptySet())
    /** [replaces]: they say they're this contact with a new security key (they carry that contact's mailbox): warned, never silent. */
    data class Request(val peerKey: String, val name: String, val code: String, val mailbox: String?, val viaInternet: Boolean, val replaces: String = "")

    private val _nearby = MutableStateFlow<List<Msg>>(emptyList())
    val nearby: StateFlow<List<Msg>> = _nearby.asStateFlow()
    private val _private = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<Msg>>> = _private.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    private val _met = MutableStateFlow<Map<String, Met>>(emptyMap())
    val met: StateFlow<Map<String, Met>> = _met.asStateFlow()
    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unread: StateFlow<Set<String>> = _unread.asStateFlow()
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** A private message arrived (name, text, key): for notifications while Muninn is in the background. */
    val arrivals = MutableSharedFlow<Triple<String, String, String>>(extraBufferCapacity = 16)
    /** I sent a message (chat key, or "g:" and a group id): the conversation window opens (Power). */
    val sends = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Someone unsent a message in this chat (key): clear its notification. */
    val recalls = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Cap m1: who is typing to me, until when (ms). */
    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())
    val typing: StateFlow<Map<String, Long>> = _typing.asStateFlow()
    /** Cap m1: whose app said it's open, until when (ms). Empty while my own Active status is off. */
    private val _active = MutableStateFlow<Map<String, Long>>(emptyMap())
    val active: StateFlow<Map<String, Long>> = _active.asStateFlow()
    /** Cap m1: reactions and edits on their way (envelope id to who and when), until a receipt names them. */
    private val mxOut = ConcurrentHashMap<String, Pair<String, Long>>()
    private val mxStore = File(context.filesDir, "mxout.json")
    private val liveTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    /** Cap p1: profile messages per sender (one in 10 s). */
    private val profileTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val typingSent = ConcurrentHashMap<String, Long>()
    @Volatile private var foreground = false
    @Volatile private var activeSent = 0L

    /** Contacts who said yes to Keep in touch and got my yes back (once each while Muninn runs). */
    private val keptBack: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** "Yes" to Keep in touch that didn't get there yet, by person: the same sealed envelope goes again. */
    private val unsentKeeps = ConcurrentHashMap<String, String>()
    /** Messages from someone asking to chat, by id (resends don't pile up), until accepted. */
    private val held = ConcurrentHashMap<String, MutableMap<String, Msg>>()
    /** One lock for every change to the chats, so copies arriving at once on different routes can't race. */
    private val lock = Any()
    /** Ids deleted on this phone in the last two days: a late copy won't bring them back. */
    private val deleted = ConcurrentHashMap<String, Long>()
    private val deletedStore = File(context.filesDir, "deleted.json")
    private val recent = ConcurrentHashMap<String, ArrayDeque<Long>>()
    /** Nearby room messages per address or connection (see roomRate). */
    private val roomTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val tries = ConcurrentHashMap<String, Tries>()
    /** When "delivered" last went back for each message ("key|id"). */
    private val receipted = ConcurrentHashMap<String, Long>()

    /** Phones close by or on the same Wi-Fi at the last look, so one that just showed up is noticed. */
    private var near = emptySet<String>()

    /** The chats are written on their own thread, a moment after a change: many changes, one write, never on the screen's thread. */
    private val writer = Executors.newSingleThreadScheduledExecutor()
    private val saveDue = java.util.concurrent.atomic.AtomicBoolean(false)

    private val pool = Executors.newFixedThreadPool(8)
    /** Background work that can't take the app down: a failed send just stays unsent and is retried. */
    private fun bg(f: () -> Unit) = pool.execute { try { f() } catch (e: Exception) { android.util.Log.w("Muninn", "chat", e) } }
    private val retrier = Executors.newSingleThreadExecutor()
    private val retryScope = kotlinx.coroutines.CoroutineScope(retrier.asCoroutineDispatcher() + kotlinx.coroutines.SupervisorJob())
    /** Wakes the retry loop when something is queued while it sleeps. */
    private val retryWake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    /** My messages still on their way (id to chat key): the only ones the retry loop looks at. */
    private val pending = ConcurrentHashMap<String, String>()

    /** [msgId] in the chat with [peerKey] waits for a receipt (or an unsend to be confirmed): the retry loop looks after it. */
    private fun queue(peerKey: String, msgId: String) { pending[msgId] = peerKey; wakeRetry() }

    private fun wakeRetry() { retryWake.trySend(Unit) }

    /** Anything for the retry loop to do: messages, reactions or group messages on their way, or something that runs out. */
    private fun retryBusy(): Boolean = pending.isNotEmpty() || mxOut.isNotEmpty() || groups.busy() || incoming.isNotEmpty() ||
        _typing.value.isNotEmpty() || _active.value.isNotEmpty() || (foreground && id.activeStatus)

    /** My messages that still need the retry loop (after a restart, or when chats are loaded again). */
    private fun queueWaiting() {
        val now = System.currentTimeMillis()
        for ((k, list) in _private.value) for (m in list)
            if (m.mine && (m.state == "unsending" || (m.state.isNotEmpty() && m.unsent.isEmpty() && rank(m.state) < 3 && now - m.time <= DAY))) pending[m.id] = k
        wakeRetry()
    }
    private val store = File(context.filesDir, "chats.json")
    private val nearbyStore = File(context.filesDir, "nearby.json")
    private val metStore = File(context.filesDir, "met.json")
    private val photosDir = File(context.filesDir, "photos")
    /** Invites handed out in my codes (token to expiry ms): declared before init, which loads them. */
    private val invites = HashMap<String, Long>()
    private val invitesStore = File(context.filesDir, "invites.json")
    /** Cap i1: introductions and group members asking to be contacts, until both say yes or one says no. */
    private val _intros = MutableStateFlow<List<Trust.Intro>>(emptyList())
    val intros: StateFlow<List<Trust.Intro>> = _intros.asStateFlow()
    private val introsStore = File(context.filesDir, "intros.json")
    /** The tokens of one-time invite links (inviteLink), still unused. */
    private val once = HashSet<String>()
    private val onceStore = File(context.filesDir, "invites-once.json")
    /** Sealed envelopes per message id, so every retry and route sends the very same bytes. */
    private val outbox = Outbox(File(context.filesDir, "outbox"), { vault.read(it) }, { f, t -> vault.write(f, t) })
    /** Message ratchet state per contact. Erase all wipes it with everything else. */
    private val ratchets = Ratchets(File(context.filesDir, "ratchet"), { vault.read(it) }, { f, t -> vault.write(f, t) })
    /** Kept on disk (a restart doesn't let an old copy in again), written at most once a minute. */
    private val seen = Seen(read = { vault.read(File(context.filesDir, "seen.json")) }, write = { vault.write(File(context.filesDir, "seen.json"), it) })
    /** Ratchet envelopes that didn't open, per sender: too many and the next wait (cap s1). */
    private val failures = Failures()
    /** Cap e1: this phone's day keys for the relay wrapping (section 23), kept in the vault. */
    private val wrapKeys = WrapKeys({ vault.read(File(context.filesDir, "wrapkeys.json")) }, { vault.write(File(context.filesDir, "wrapkeys.json"), it) })
    /** Caps b1 and e1: the ring key, ring mailbox and newest day key each contact announced. */
    private val peerKeys = PeerKeys({ vault.read(File(context.filesDir, "peerkeys.json")) }, { vault.write(File(context.filesDir, "peerkeys.json"), it) })
    /** Cap b1: this device's ring key, outside the vault (set by the app). */
    @Volatile var ringKey: RingKey? = null
    /** Call ids a ring envelope already went out for (one per call). */
    private val rung: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Contacts my keys went to when their app first said b1 or e1 (once each while Muninn runs). */
    private val keysSent: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Cap s1 for someone who isn't a contact (a linked device, someone met close by): what their app said, and that it seals. */
    private val s1Said: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val s1Bound = ConcurrentHashMap<String, Long>()
    private val s1Store = File(context.filesDir, "s1.json")

    /** Caps probes sent (key to when): at most one per contact an hour. */
    private val probed = ConcurrentHashMap<String, Long>()
    private val groupDir = File(context.filesDir, "groups")
    /** Group chats: state, members, channels and messages. Chat is how its envelopes travel. */
    val groups: Groups = Groups(id.keys, id.publicKey, GroupHost(), { vault.read(File(groupDir, it)) }, { f, t -> groupDir.mkdirs(); vault.write(File(groupDir, f), t) })
    /** Contact folders: local only. */
    val folders = Folders({ vault.read(File(context.filesDir, "folders.json")) }, { vault.write(File(context.filesDir, "folders.json"), it) })
    /** Muted, archived and marked unread: on this phone only. */
    val flags = ChatFlags({ vault.read(File(context.filesDir, "chatflags.json")) }, { vault.write(File(context.filesDir, "chatflags.json"), it) })
    /** Contacts removed from the list, kept 30 days so they can come back: local only. */
    /** Labs reports to this phone, when its chat key is the Labs key ([LabsWire]). */
    val labs = LabsInbox(context, vault)
    /** This phone is the Labs contact: it takes Labs reports. */
    fun isLabs() = id.publicKey == LabsWire.KEY
    val trash = Trash({ vault.read(File(context.filesDir, "trash.json")) }, { vault.write(File(context.filesDir, "trash.json"), it) })
    private val groupTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val callTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    init {
        load()
        queueWaiting()
        try { vault.read(s1Store)?.let { JSONObject(it) }?.let { o -> o.keys().forEach { s1Bound[it] = o.optLong(it) } } } catch (_: Exception) {}
        FileLink.host = object : FileLink.Host {
            override fun signal(peer: String, json: String) {
                bg {
                    val body = envelope("file", peer, json, UUID.randomUUID().toString()).toString()
                    spread(peer, listOf(body), false, 1, contacts.get(peer)?.mailbox)
                }
            }
            override fun trusted(peer: String) = io.github.ravenor907.muninn.net.call.CallManager.trusted(peer)
            override fun hasF1(peer: String) = this@Chat.hasF1(peer)
            override fun waitsForAsk(peer: String) = contacts.get(peer)?.caps?.contains(FileLink.ASK_CAP) == true
            override fun save(name: String, file: File): String? = try {
                val t = Received.create(context, name)
                file.inputStream().use { it.copyTo(t.out) }
                t.finish(); t.name
            } catch (e: Exception) { null }
            override fun cacheDir(): File = File(context.cacheDir, "files").also { it.mkdirs() }
            override fun notice(text: String) { notices.tryEmit(text) }
            override fun mayDownload() = (context.applicationContext as? io.github.ravenor907.muninn.Muninn)?.power?.savingData() != true
        }
        Link.host = object : Link.Host {
            override fun myKey() = id.publicKey
            override fun myMailbox() = nostr.publicKey
            override fun myName() = id.deviceName.ifBlank { id.username }
            override fun send(d: Link.Device, json: String) {
                bg {
                    val body = envelope("link", d.key, json, UUID.randomUUID().toString()).toString()
                    spread(d.key, listOf(body), false, 1, d.mailbox.takeIf { it.length == 64 })
                }
            }
            override fun chats(): Map<String, Pair<String, List<Msg>>> =
                _private.value.filter { (k, v) -> v.isNotEmpty() && isContact(k) && !trash.has(k) }.mapValues { (k, v) -> nameOf(k) to v }
            override fun sendAsMe(peer: String, text: String): Boolean { if (!isContact(peer)) return false; sendPrivate(peer, text); return true }
            override fun read(name: String) = vault.read(File(context.filesDir, name))
            override fun write(name: String, text: String) { try { vault.write(File(context.filesDir, name), text) } catch (_: Exception) {} }
            override fun notice(text: String) { notices.tryEmit(text) }
            override fun days(peer: String) = chatDays(peer)
            override fun emoji(key: String) = try { emojiWith(key) } catch (e: Exception) { "" }
        }
        Link.load()
        // Devices trusted before contacts existed become contacts.
        // Never one that would stand in for a contact already here (that would be a key change, not an old friend).
        id.trusted().forEach { (k, n) ->
            val c = Contact(k, n, null, true)
            if (contacts.get(k) == null && ChatWire.keyChangeFor(contacts.all.value, c) == null) contacts.put(c)
        }
        id.dropTrusted()
        outbox.sweep()
        net.extraRoute = { method, path, body, ip ->
            when {
                method == "POST" && path == PATH -> receive(body, "wifi", "ip:$ip")
                // Cap x1: moving to or from this device, over the Wi-Fi only.
                method == "POST" && path == MoveWire.PATH -> Move.handle(body)
                else -> null
            }
        }
        close.onMessage = { ep, o -> if (o.optString("t") == "msg") receive(o.toString(), "close", "ep:$ep") }
        // Huginn close by, over Bluetooth LE (it can't use Nearby): the same envelopes.
        ble.onMessage = { k, json -> receive(json, "close", "ble:$k") }
        // Cap s1: proving my routes, and matching a hidden contact's daily hint.
        val hintKey = { h: String -> Proof.keyForHint(h, contacts.all.value.filter { !it.groupOnly }.map { it.chatKey }) }
        net.hintKey = hintKey
        close.hintKey = hintKey
        close.prove = { n -> Proof.close(id.keys.private, id.publicKey, n) }
        ble.hintKey = hintKey
        ble.prove = { n -> Proof.close(id.keys.private, id.publicKey, n) }
        // Someone said yes while out of range: say it again when they're back.
        bg { while (true) { Thread.sleep(30_000); try { resendKeeps() } catch (_: Exception) {} } }
        // The retry loop: once a second while anything waits to go (or runs out), asleep while nothing does.
        retryScope.launch {
            while (true) {
                val busy = try { retryBusy() } catch (_: Exception) { true }
                if (busy) kotlinx.coroutines.delay(1_000) else retryWake.receive()
                try { retryLoop() } catch (_: Exception) {}
            }
        }
        nostr.onEnvelope = { content ->
            try {
                val plain = unwrap(content)
                if (plain != null) { receive(plain, "internet"); true }
                // Cap e1 after a restore: for a day key not here yet, not taken (so not marked seen) for 7 days.
                else !waitsForDayKey(content)
            } catch (_: Exception) { true }
        }
        // A backup was restored: a new day key, announced to every e1 contact now.
        if (prefs.getBoolean(Backup.RESTORE_ANNOUNCE, false)) {
            prefs.edit().remove(Backup.RESTORE_ANNOUNCE).apply()
            bg { try { wrapKeys.fresh(); announceKeys { RingWire.WRAP_CAP in it.caps } } catch (_: Exception) {} }
        }
        // Auto-delete: check every minute for messages past the chosen age, and for contacts 30 days in the trash.
        bg { emptyOldTrash() }
        bg { while (true) { Thread.sleep(60_000); try { prune(); outbox.sweep(); emptyOldTrash(); sweepCache(); seen.save(); wrapKeys.prune()
            // A ring mailbox replaced after a block: 2 days later it isn't asked for any more.
            if (ringKey?.prune() == true) nostr.resubscribe() } catch (_: Exception) {} } }
    }

    /**
     * What close-by phones learn about this one. Hidden from people nearby, no name, chat key or mailbox goes out:
     * only a hint that changes every day, which contacts (who have the key) can match (cap s1). So a stranger can't
     * follow this phone from place to place.
     */
    fun hello(): JSONObject = JSONObject().put("name", if (id.visibleNearby) id.username else Identity.HIDDEN_NAME)
        .apply { if (id.visibleNearby) put("chatKey", id.publicKey).put("mailbox", nostr.publicKey) else put("hint", Proof.hintNow(id.publicKey)) }
        .put("device", if (id.visibleNearby) id.deviceName else Identity.HIDDEN_NAME)

    /** A new code each time it's shown, with an invite good for an hour. */
    fun myCode(): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val until = System.currentTimeMillis() + INVITE_MS
        synchronized(invites) {
            invites[token] = until
            invites.entries.removeIf { it.value < System.currentTimeMillis() }
            saveInvites()
        }
        return Contacts.code(id.username, id.publicKey, nostr.publicKey, token, until)
    }

    /**
     * A one-time invite link, good for [hours]: the first person to add it becomes a contact here as soon as their
     * first message arrives, no request to accept. Then the link is used up.
     */
    fun inviteLink(hours: Int): String {
        val token = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        val until = System.currentTimeMillis() + hours * 3_600_000L
        synchronized(invites) {
            invites[token] = until
            once.add(token)
            invites.entries.removeIf { it.value < System.currentTimeMillis() }
            once.retainAll(invites.keys)
            saveInvites()
        }
        return Contacts.link(ChatWire.code(id.username, id.publicKey, nostr.publicKey, token, until, once = true))
    }

    // ------------------------------------------------------------------ cap i1: introductions (shared/docs/protocol-0.6.md, section 13)

    fun hasI1(peerKey: String) = contacts.get(peerKey)?.caps?.contains(Trust.CAP) == true

    private fun saveIntros() { try { vault.write(introsStore, JSONArray().apply { _intros.value.forEach { put(Trust.toJson(it)) } }.toString()) } catch (_: Exception) {} }

    private fun putIntro(i: Trust.Intro) = synchronized(lock) {
        _intros.update { l -> l.filterNot { it.id == i.id } + i }
        saveIntros()
    }

    /** Introduces two of my contacts to each other. Null when sent, else what to say. */
    fun introduce(a: String, b: String): String? {
        val ca = contacts.contact(a) ?: return "Pick two of your contacts."
        val cb = contacts.contact(b) ?: return "Pick two of your contacts."
        if (a == b) return "Pick two different people."
        listOf(ca, cb).firstOrNull { !hasI1(it.chatKey) }?.let { probeCaps(it.chatKey); return "${it.shown} needs a newer Muninn for introductions." }
        val introId = Proto06.newLogicalId()
        bg {
            for ((to, other) in listOf(ca to cb, cb to ca)) {
                val plain = JSONObject().put("t", "intro").put("id", introId).put("k", other.chatKey).put("n", other.name).put("p", other.mailbox ?: "").toString()
                deliver(to.chatKey, envelope("intro", to.chatKey, plain, UUID.randomUUID().toString()), to.mailbox)
            }
        }
        return null
    }

    /** Yes or no to an introduction, or to someone in a group asking to be contacts. */
    fun answerIntro(introId: String, yes: Boolean) {
        val i = _intros.value.firstOrNull { it.id == introId } ?: return
        if (!yes) { putIntro(i.copy(declined = true)); return }
        val now = i.copy(iSaid = true, declined = false)
        putIntro(now)
        bg {
            val plain = if (i.by.startsWith("g:")) JSONObject().put("t", "add").put("g", i.by.removePrefix("g:")).toString()
                else JSONObject().put("t", "yes").put("id", i.id).toString()
            deliver(i.k, envelope("intro", i.k, plain, UUID.randomUUID().toString()), i.p.ifEmpty { contacts.get(i.k)?.mailbox })
        }
        if (now.theySaid) introDone(now)
    }

    /** Someone in group [g] I'm not in touch with yet: ask to be contacts (they say yes on their phone too). */
    fun askToAdd(g: String, k: String, name: String, mailbox: String) {
        val id = addId(g, this.id.publicKey, k)
        val i = _intros.value.firstOrNull { it.id == id && it.by == "g:$g" && it.k == k } ?: Trust.Intro(id, "g:$g", k, name, mailbox, at = System.currentTimeMillis())
        putIntro(i)
        answerIntro(id, true)
    }

    /**
     * The local id of a group member's "add" (13): kept apart from introductions' ids (an introducer picks those), so
     * nobody can plant one that a later "add" would match.
     */
    private fun addId(g: String, a: String, b: String) = "add:" + Trust.groupId(g, a, b)

    /** Both said yes: contacts on this phone (theirs does the same). */
    private fun introDone(i: Trust.Intro) {
        synchronized(lock) { _intros.update { l -> l.filterNot { it.id == i.id } }; saveIntros() }
        if (isContact(i.k)) return
        putContact(Contact(i.k, i.n, i.p.ifEmpty { null }, verified = false))
        notices.tryEmit("You and ${i.n} are in touch now. Check the 4 pictures with them to be sure.")
    }

    /** Room "intro", already checked (Trust.ok). False: not from someone allowed to send it. */
    private fun takeIntro(fromKey: String, name: String, mailbox: String?, p: JSONObject): Boolean {
        val me = id.publicKey
        when (p.getString("t")) {
            "intro" -> {
                val by = contacts.contact(fromKey) ?: return false
                val k = p.getString("k")
                if (k == me || isContact(k) || _intros.value.any { it.id == p.getString("id") }) return true
                putIntro(Trust.Intro(p.getString("id"), fromKey, k, p.getString("n"), p.getString("p"), at = System.currentTimeMillis()))
                arrivals.tryEmit(Triple(by.shown, "Wants to introduce you to ${p.getString("n")}", fromKey))
            }
            "yes" -> {
                val i = _intros.value.firstOrNull { it.id == p.getString("id") && it.k == fromKey && !it.by.startsWith("g:") } ?: return isContact(fromKey)
                val now = i.copy(theySaid = true, p = i.p.ifEmpty { mailbox ?: "" })
                if (now.iSaid) introDone(now) else putIntro(now)
            }
            "add" -> {
                val g = p.getString("g")
                val grp = groups.group(g) ?: return false
                if (grp.readOnly || !grp.state.has(fromKey) || !grp.state.has(me)) return false
                if (isContact(fromKey)) return true
                val id = addId(g, me, fromKey)
                val member = grp.state.member(fromKey)
                val i = _intros.value.firstOrNull { it.id == id && it.by == "g:$g" && it.k == fromKey }
                    ?: Trust.Intro(id, "g:$g", fromKey, member?.n?.ifBlank { null } ?: name, mailbox ?: member?.p ?: "", at = System.currentTimeMillis())
                val now = i.copy(theySaid = true, p = i.p.ifEmpty { mailbox ?: "" })
                if (now.iSaid) introDone(now) else {
                    putIntro(now)
                    if (!i.theySaid) arrivals.tryEmit(Triple(now.n, "From ${grp.state.name}: wants to be in touch", fromKey))
                }
            }
        }
        return true
    }

    /**
     * People in my Family groups (named Family, or in my Family folder) I'm not in touch with yet and haven't been
     * asked about: offered on Requests as "Add as a contact". (group, key, name, mailbox)
     */
    fun familyOffers(): List<Trust.Intro> {
        val me = id.publicKey
        val familyFolder = folders.all.value.firstOrNull { it.name.equals("Family", ignoreCase = true) }?.keys.orEmpty()
        val known = _intros.value.map { it.k }.toSet()
        return groups.all.value.values.filter { !it.readOnly && (it.state.name.contains("family", ignoreCase = true) || Folders.groupKey(it.g) in familyFolder) }
            .flatMap { grp -> grp.state.members.filter { it.k != me && !isContact(it.k) && it.k !in known && hasCapsI1(it.k) }
                .map { Trust.Intro(addId(grp.g, me, it.k), "g:${grp.g}", it.k, it.n, it.p) } }
            .distinctBy { it.k }
    }

    /** A group peer's app takes "add" only with i1: unknown for group peers, so only ones whose caps said so. */
    private fun hasCapsI1(k: String) = contacts.get(k)?.caps?.contains(Trust.CAP) == true

    /** Not now to a family offer: kept as declined, so it isn't offered again. */
    fun dismissOffer(o: Trust.Intro) = putIntro(o.copy(declined = true, at = System.currentTimeMillis()))

    /** A one-time invite still good: used up now. */
    private fun useOnce(token: String): Boolean = token.isNotEmpty() && synchronized(invites) {
        if (token !in once || (invites[token] ?: 0L) <= System.currentTimeMillis()) return@synchronized false
        invites.remove(token); once.remove(token); saveInvites(); true
    }

    private fun saveInvites() {
        try {
            vault.write(invitesStore, JSONObject(invites as Map<*, *>).toString())
            vault.write(onceStore, JSONArray(once.toList()).toString())
        } catch (_: Exception) {}
    }
    private fun inviteOk(token: String): Boolean = token.isNotEmpty() && synchronized(invites) { (invites[token] ?: 0L) > System.currentTimeMillis() }

    /** Over the internet the whole message is sealed to their chat key with a one-off key, so relays
     *  see only which inbox it's for: not who sent it, not their name. */
    private fun wrap(peerKey: String, body: String): String {
        // Cap e1: sealed to one of their day keys too, so their chat key alone never opens it once that day key is gone.
        val day = if (contacts.get(peerKey)?.caps?.contains(RingWire.WRAP_CAP) == true) peerKeys.wrapFor(peerKey) else null
        if (day != null) return Base64.getEncoder().encodeToString(EnvelopePad.pad(RingWire.sealWrap(peerKey, day, body.toByteArray()).toString()).toByteArray())
        val eph = ChatCrypto.newKeyPair()
        val key = ChatCrypto.sharedKey(eph.private, eph.public, ChatCrypto.publicFrom(peerKey))
        val s = ChatCrypto.sealBytes(key, body.toByteArray())
        val o = JSONObject().put("e", ChatCrypto.b64(eph.public.encoded)).put("n", s.nonce).put("d", s.data)
        return Base64.getEncoder().encodeToString(EnvelopePad.pad(o.toString()).toByteArray())
    }

    private fun unwrap(content: String): String? {
        val raw = String(Base64.getDecoder().decode(content))
        val o = JSONObject(raw)
        if (!o.has("e")) return raw   // not sealed
        // Cap e1: wrapped for one of my day keys as well; one that's gone (or never was) doesn't open.
        if (o.has("w")) return RingWire.openWrap(o, id.keys) { wrapKeys.find(it) }?.let { String(it) }
        val key = ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(o.getString("e")))
        return ChatCrypto.openBytes(key, ChatCrypto.Sealed(o.getString("n"), o.getString("d")))?.let { String(it) }
    }

    private val prefs get() = context.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE)

    /** An e1 wrapping that didn't open names a day key not here, within 7 days of a restore ([RingWire.waitForDayKey]). */
    private fun waitsForDayKey(content: String): Boolean = try {
        val o = JSONObject(String(Base64.getDecoder().decode(content)))
        o.has("w") && RingWire.waitForDayKey(wrapKeys.find(o.getString("w")) != null, prefs.getLong(Backup.RESTORED_AT, 0L), System.currentTimeMillis())
    } catch (e: Exception) { false }

    fun codeWith(peerKey: String): String =
        ChatCrypto.pairingCode(ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(peerKey)))

    /** The 4 emoji both phones show for this pair: the same on each side means nobody is in between. */
    fun emojiWith(peerKey: String): String =
        ChatCrypto.emojiCode(ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(peerKey)))

    /** A real contact (a group peer isn't one: they're only reachable for group things). */
    fun isContact(peerKey: String) = contacts.contact(peerKey) != null
    private fun contact(peerKey: String) = contacts.contact(peerKey)

    /** Whether they can be put in a group: their app said "g1". Unknown yet: ask (at most once an hour). */
    fun canGroup(peerKey: String): Boolean {
        val c = contacts.get(peerKey) ?: return false
        if ("g1" in c.caps) return true
        probeCaps(peerKey)
        return false
    }

    /** A receipt with no ids that asks for caps (1.2): an app with caps answers, one without does nothing. */
    fun probeCaps(peerKey: String) {
        val c = contacts.get(peerKey)
        if (c == null && !_met.value.containsKey(peerKey)) return
        if (c != null && "g1" in c.caps) return
        val now = System.currentTimeMillis()
        if (now - (probed[peerKey] ?: 0L) < 3_600_000L) return
        probed[peerKey] = now
        bg {
            val body = JSONObject().put("ids", JSONArray()).put("s", "delivered").put("q", "caps").toString()
            deliver(peerKey, envelope("receipt", peerKey, body, UUID.randomUUID().toString()), c?.mailbox ?: _met.value[peerKey]?.mailbox)
        }
    }

    /** The name this phone shows for them: a contact's nickname if one was set, else the name they chose. */
    fun nameOf(peerKey: String): String = contacts.get(peerKey)?.shown ?: _met.value[peerKey]?.name
        ?: close.list.value.firstOrNull { it.chatKey == peerKey }?.name
        ?: ble.peers.value.firstOrNull { it.chatKey == peerKey }?.name
        ?: net.deviceFor(peerKey)?.let { it.chatName ?: it.alias } ?: "Someone"

    /** Opening a chat with someone close by: no asking, just start. */
    fun meet(peerKey: String, name: String) {
        if (isContact(peerKey) || _met.value.containsKey(peerKey)) return
        putMet(peerKey, Met(name, close.list.value.firstOrNull { it.chatKey == peerKey }?.mailbox
            ?: ble.peers.value.firstOrNull { it.chatKey == peerKey }?.mailbox))
    }

    /** "Keep in touch?" should show: 10 messages in, or they've gone out of range. */
    fun shouldAskKeep(peerKey: String): Boolean {
        val m = _met.value[peerKey] ?: return false
        if (isContact(peerKey) || m.dismissed || m.iSaid) return false
        return m.theySaid || _private.value[peerKey].orEmpty().size >= KEEP_AFTER || route(peerKey).isEmpty()
    }

    fun keepInTouch(peerKey: String) {
        val m = _met.value[peerKey] ?: return
        putMet(peerKey, m.copy(iSaid = true, dismissed = false))
        bg {
            val body = envelope("keep", peerKey, "yes", UUID.randomUUID().toString())
            if (deliver(peerKey, body, m.mailbox)) return@bg
            unsentKeeps[peerKey] = body.toString()
            notices.tryEmit("Couldn't reach ${m.name} yet. Muninn will say yes again when you're close.")
        }
        if (m.theySaid) becomeContacts(peerKey)
        else notices.tryEmit("Asked ${m.name}. Once they say yes too, you can message from anywhere.")
    }

    /** Both screens showed the same code. */
    fun verify(peerKey: String, name: String) {
        contacts.get(peerKey)?.let { contacts.set(it.copy(verified = true, keyChanged = false)); return }
        putMet(peerKey, (_met.value[peerKey] ?: Met(name, null)).copy(verified = true))
    }

    fun isVerified(peerKey: String) = contacts.get(peerKey)?.verified == true || _met.value[peerKey]?.verified == true

    // ------------------------------------------------------------------ what their app speaks, as the screens see it

    /** Their caps: a contact's or group peer's, or someone met close by. Empty until their app said. */
    fun capsOf(peerKey: String): Set<String> = contacts.get(peerKey)?.caps ?: _met.value[peerKey]?.caps.orEmpty()

    /** Whether their app said what it speaks yet. */
    fun capsKnown(peerKey: String) = capsOf(peerKey).isNotEmpty()

    /**
     * For the screens: unknown caps count as a current app (Call, Video and video messages show), and a probe asks.
     * The wire still only sends a room to an app whose caps list it, and falls back once they're known.
     */
    fun mayHave(peerKey: String, cap: String): Boolean {
        val caps = capsOf(peerKey)
        if (caps.isEmpty()) { probeCaps(peerKey); return true }
        return Permissions.has(caps, cap)
    }

    /** Call button: a contact or group peer; someone met only while close by or on the same Wi-Fi. */
    fun mayCall(peerKey: String): Boolean {
        if (peerKey in id.muted() || trash.has(peerKey)) return false
        val c = contacts.get(peerKey)
        if (c != null) return c.groupOnly || mayHave(peerKey, "c1")
        if (!_met.value.containsKey(peerKey)) return false
        val r = route(peerKey)
        return (r == "wifi" || r == "close") && mayHave(peerKey, "c1")
    }

    /** Video call button (c2, 5.8.5). */
    fun mayVideo(peerKey: String) = mayCall(peerKey) && mayHave(peerKey, "c2")

    /** Video message button (m2). */
    fun mayVideoMessage(peerKey: String) = (isContact(peerKey) || _met.value.containsKey(peerKey)) && mayHave(peerKey, More.CAP)

    // ------------------------------------------------------------------ per person, set on the press-and-hold sheet (local only)

    /** They may call me (voice and video). Default yes. */
    fun callsAllowed(peerKey: String) = contacts.get(peerKey)?.noCalls != true
    /** They may send me files. Default yes. */
    fun filesAllowed(peerKey: String) = contacts.get(peerKey)?.noFiles != true
    /** They see when I'm active (with Active status on). Default yes. */
    fun showsActive(peerKey: String) = contacts.get(peerKey)?.hideActive != true

    fun setCallsAllowed(peerKey: String, on: Boolean) { contacts.get(peerKey)?.let { contacts.set(it.copy(noCalls = !on)) } }
    fun setFilesAllowed(peerKey: String, on: Boolean) { contacts.get(peerKey)?.let { contacts.set(it.copy(noFiles = !on)) } }
    fun setShowsActive(peerKey: String, on: Boolean) {
        val c = contacts.get(peerKey) ?: return
        contacts.set(c.copy(hideActive = !on))
        // Hidden now: their green dot for me goes at once.
        if (!on && id.activeStatus && Rich.CAP in c.caps) sendLive(peerKey, Rich.liveMsg("active", false, System.currentTimeMillis()))
    }

    /** A nickname for a contact, on this phone only (never sent). Empty goes back to their own name. */
    fun rename(peerKey: String, nickname: String) = contacts.rename(peerKey, nickname)

    fun notNow(peerKey: String) { _met.value[peerKey]?.let { putMet(peerKey, it.copy(dismissed = true)) } }

    /** Nothing from them arrives, here or in the Nearby room. Their chat is hidden until unblocked. */
    fun block(peerKey: String, on: Boolean = true) {
        val was = peerKey in id.muted()
        _requests.update { l -> l.filterNot { it.peerKey == peerKey } }
        held.remove(peerKey)
        mute(peerKey, on)
        _blocked.value = id.muted()
        // Cap b1: what they hold no longer rings this phone. A new ring key and ring mailbox go to the other b1
        // contacts; the old mailbox is listened on 2 more days.
        if (on && !was) ringKey?.let { r ->
            bg {
                try {
                    r.renew()
                    nostr.resubscribe()
                    announceKeys { RingWire.RING_CAP in it.caps && it.chatKey != peerKey }
                } catch (_: Exception) {}
            }
        }
    }

    private val _blocked = MutableStateFlow(id.muted())
    val blocked: StateFlow<Set<String>> = _blocked.asStateFlow()

    /** Removes a contact (and anyone met close by) for good; the chat stays unless deleted too. */
    fun removeContact(peerKey: String) {
        val was = contacts.get(peerKey)
        contacts.remove(peerKey)
        folders.forget(peerKey)
        trash.drop(peerKey)
        // Still in a group together: they stay reachable for the group, as a group peer.
        if (was != null && groups.sharesGroup(peerKey)) contacts.peer(peerKey, was.name, was.mailbox ?: "")
        synchronized(ratchets.lock(peerKey)) { ratchets.drop(peerKey) }
        if (contacts.get(peerKey) == null) peerKeys.forget(peerKey)
        synchronized(lock) { _met.update { it - peerKey }; saveMet() }
        // Not in touch any way now: their profile picture goes too.
        if (contacts.get(peerKey) == null) profile?.forget(peerKey)
    }

    /**
     * Removing a contact puts them in the trash: hidden with their chat, and nothing from them gets through
     * (dropped quietly, like blocked). Restore brings everything back; after 30 days they go for good.
     */
    fun trashContact(peerKey: String) {
        trash.put(peerKey, System.currentTimeMillis())
        _unread.update { it - peerKey }
        _typing.update { it - peerKey }
        _active.update { it - peerKey }
    }

    fun restoreContact(peerKey: String) = trash.drop(peerKey)

    /** Out of the trash for good: the contact and the chat with them. */
    fun deleteForGood(peerKey: String) {
        deleteConversation(peerKey)
        removeContact(peerKey)
        flags.forget(peerKey)
    }

    fun emptyTrash() = trash.all.value.keys.toList().forEach { deleteForGood(it) }

    private fun emptyOldTrash() = trash.expired(System.currentTimeMillis()).forEach { deleteForGood(it) }

    /** Deletes one message on this phone. */
    fun deleteMessage(peerKey: String, msgId: String) {
        forget(listOf(msgId))
        outbox.drop(msgId); outbox.drop(UNSEND + msgId); tries.remove(msgId)
        edit { map ->
            map[peerKey]?.firstOrNull { it.id == msgId }?.photo?.takeIf { it.isNotEmpty() }?.let { File(photosDir, it).delete() }
            map[peerKey] = map[peerKey].orEmpty().filterNot { it.id == msgId }
            true
        }
    }

    /** Takes a message back from both phones. Both show "Message deleted by ...". Sent until they confirm. */
    fun unsend(peerKey: String, msgId: String) {
        var target: Msg? = null
        edit { map ->
            val list = map[peerKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.mine && it.unsent.isEmpty() } ?: return@edit false
            if (m.photo.isNotEmpty()) File(photosDir, m.photo).delete()
            target = m
            map[peerKey] = list.map { if (it.id == msgId) it.copy(text = "", photo = "", voice = 0, unsent = "You", state = "unsending", failed = false) else it }
            true
        }
        val m = target ?: return
        outbox.drop(m.id)   // the message itself isn't sent again
        tries[m.id] = Tries(0, 0)
        queue(peerKey, m.id)
        bg { attemptUnsend(m) }
    }

    private fun attemptUnsend(m: Msg) {
        if (Move.locked) return
        val t = tries.getOrPut(m.id) { Tries(0, 0) }
        t.count++
        schedule(t)
        // One envelope for the whole unsend: every retry sends the same bytes.
        val body = outbox.fresh(UNSEND + m.id, stale(m.peerKey)) { listOf(envelope("unsend", m.peerKey, m.id, UUID.randomUUID().toString()).toString()) }
            ?.firstOrNull() ?: return
        closeTo(m.peerKey)?.let { close.send(it, JSONObject(body).put("t", "msg")) }
        ble.send(m.peerKey, body, strict(m.peerKey))
        wifiTo(m.peerKey)?.let { d -> bg { net.post(d, PATH, body) } }
        if (publishNow(t)) internet(m.peerKey, listOf(body), contact(m.peerKey)?.mailbox ?: _met.value[m.peerKey]?.mailbox, held = heldBy(t))
    }

    fun deleteNearbyMessage(msgId: String) {
        forget(listOf(msgId))
        synchronized(lock) { _nearby.update { l -> l.filterNot { it.id == msgId } }; if (id.keepNearbyHistory) saveNearby() }
    }

    private fun forget(ids: List<String>) {
        val now = System.currentTimeMillis()
        ids.forEach { deleted[it] = now }
        deleted.entries.removeIf { now - it.value > 2 * DAY }
        try { vault.write(deletedStore, JSONObject(HashMap<String, Long>(deleted) as Map<*, *>).toString()) } catch (_: Exception) {}
    }

    /** Already here (or deleted here): a copy from another route, or a resend. */
    private fun known(peerKey: String, msgId: String) =
        deleted.containsKey(msgId) || held[peerKey]?.containsKey(msgId) == true || _private.value[peerKey].orEmpty().any { it.id == msgId }

    private fun becomeContacts(peerKey: String) {
        val m = _met.value[peerKey] ?: return
        synchronized(lock) { _met.update { it - peerKey }; saveMet() }
        putContact(Contact(peerKey, m.name, m.mailbox, verified = m.verified, caps = m.caps))
        notices.tryEmit(if (m.verified) "You and ${m.name} are in touch. Message each other from anywhere."
            else "You and ${m.name} are in touch. Check the 4 pictures with them to be sure.")
    }

    private fun putMet(k: String, m: Met) = synchronized(lock) { _met.update { it + (k to m) }; saveMet() }

    /** How a contact can be reached right now: "close", "wifi", "internet" or "". */
    fun route(peerKey: String): String = when {
        closeTo(peerKey) != null || ble.has(peerKey, strict(peerKey)) -> "close"
        wifiTo(peerKey) != null -> "wifi"
        contact(peerKey)?.mailbox != null -> "internet"
        else -> ""
    }

    // ------------------------------------------------------------------ receiving

    /** [from]: the address or connection it came over (close by and Wi-Fi), so one device can't flood the Nearby room under many keys. */
    private fun receive(body: String, via: String, from: String = ""): Pair<Int, String> {
        // Moving to another device: nothing comes in here any more (it waits for the new device).
        if (Move.locked) return 503 to "{}"
        val o = try { JSONObject(body) } catch (e: Exception) { return 400 to "{}" }
        val fromKey = o.optString("fromKey")
        val name = o.optString("fromName").take(40).ifEmpty { "Someone" }
        if (fromKey.isEmpty() || fromKey == id.publicKey) return 400 to "{}"
        if (fromKey in id.muted() || trash.has(fromKey)) return 200 to "{}"   // blocked or in the trash: dropped quietly
        val room = o.optString("room")
        // A Labs report: only the Labs contact takes it; everyone else drops it quietly.
        if (o.has(LabsWire.FIELD)) return if (isLabs()) receiveLabs(o, room, fromKey, name) else 200 to "{}"
        // A Kids' TV only talks to the people its parents listed, and only about what it allows.
        // With no TV handler set, a Kids' TV takes nothing but configs (which it checks itself).
        val tv = tvHandler
        if (if (tv != null) !tv.allow(fromKey, room) else room != "tvconfig" && io.github.ravenor907.muninn.tv.KidMode.saved(context)) return 403 to "{}"
        if (room == "photo") return receivePiece(o, fromKey, name, via)
        if (room == "gphoto") return receiveGroupPiece(o, fromKey, name)
        val mailbox = o.optString("mailbox").takeIf { it.length == 64 }
        if (room in GROUP_ROOMS) return receiveGroup(o, room, fromKey, name, mailbox)
        val sentId = o.optString("id")
        val dup = { duplicate(room, fromKey, sentId, mailbox) }
        // Copies (another route, or a resend) are dropped before anything is opened.
        dup()?.let { return it }
        if (room == "nearby" && from.isNotEmpty() && !roomRate(from)) return 429 to "{}"
        return opened(o, fromKey, dup) { plain -> handle(o, room, fromKey, name, mailbox, sentId, via, String(plain, Charsets.UTF_8)) }
    }

    /** The Nearby room: at most 10 messages in 10 seconds from one address or connection, whatever keys they use. */
    private fun roomRate(from: String): Boolean {
        val now = System.currentTimeMillis()
        if (roomTimes.size > 256) roomTimes.entries.removeIf { e -> synchronized(e.value) { e.value.all { now - it > 10_000 } } }
        val q = roomTimes.getOrPut(from) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= 10) return false
            q.addLast(now)
            return true
        }
    }

    /** Already handled: a copy from another route, or a resend. Confirms again where that helps. */
    private fun duplicate(room: String, fromKey: String, sentId: String, mailbox: String?): Pair<Int, String>? {
        if (sentId.isEmpty()) return null
        if ((room == "private" || room == "rich") && known(fromKey, sentId)) {
            if (held[fromKey]?.containsKey(sentId) != true) receipt(fromKey, listOf(sentId), "delivered", mailbox)
            return 200 to "{}"
        }
        if (room == "nearby" && (deleted.containsKey(sentId) || _nearby.value.any { it.id == sentId })) return 200 to "{}"
        if (room == "gmsg") groups.knownEnvelope(sentId)?.let { g ->
            if (g.isNotEmpty()) groups.ack(fromKey, g, listOf(sentId.substringBefore('-')), "delivered")
            return 200 to "{}"
        }
        if (room in ChatWire.SEEN_ROOMS) seen.get("$fromKey|$sentId")?.let { note ->
            if (room == "unsend" && note.isNotEmpty()) receipt(fromKey, listOf(note), "unsent", mailbox)
            // A reaction or edit sent again: my receipt didn't get there.
            if (room == "mx") receipt(fromKey, listOf(sentId), "delivered", mailbox)
            // A group message they sent again: they didn't get my receipt, so confirm once more.
            if (room == "group" && note.isNotEmpty()) groups.ack(fromKey, note, listOf(sentId.substringBefore('-')), "delivered")
            return 200 to "{}"
        }
        return null
    }

    private fun sharedKey(theirKey: String) = ChatCrypto.sharedKey(id.keys.private, id.keys.public, ChatCrypto.publicFrom(theirKey))

    /**
     * Opens an envelope (v1, or the ratchet when it has "rh") and hands the plaintext to [then].
     * Ratchet state is kept only when [then] took the message (200): one turned away (too many at once)
     * can come again and still open. [dup] is checked again once this contact's ratchet is ours.
     */
    private fun opened(o: JSONObject, fromKey: String, dup: () -> Pair<Int, String>?, then: (ByteArray) -> Pair<Int, String>): Pair<Int, String> {
        // Cap s1: once someone's envelopes came sealed with their clear fields, an unsealed one (a copy from before,
        // changed and sent again) is refused, and so is v1 in a room their app always ratchets.
        val c = contacts.get(fromKey)
        if ((c?.bound() == true || Proof.strictSince(s1Bound[fromKey] ?: 0L, System.currentTimeMillis())) && (!Proof.isBound(o) || (!ChatWire.isRatchet(o) && ChatWire.useRatchet(o.optString("room"), c)))) return 403 to "{}"
        val sk = try { sharedKey(fromKey) } catch (e: Exception) { return 400 to "{}" }
        if (!ChatWire.isRatchet(o)) {
            val plain = ChatWire.open(o, sk, id.keys, null, ChatCrypto::newKeyPair, id.publicKey) ?: return 403 to "{}"
            authentic(o, fromKey)
            return then(plain)
        }
        // Many that don't open from one key: stop trying for a while before any ratchet work (key making, ECDH).
        if (!failures.ok(fromKey)) return 429 to "{}"
        synchronized(ratchets.lock(fromKey)) {
            dup()?.let { return it }
            val peer = ratchets.get(fromKey)
            // Fails quietly like a bad v1 message; the ratchet is left as it was.
            val plain = ChatWire.open(o, sk, id.keys, peer, ChatCrypto::newKeyPair, id.publicKey) ?: run { failures.add(fromKey); return 403 to "{}" }
            val r = try { authentic(o, fromKey); then(plain) } catch (e: Exception) { ratchets.forget(fromKey); throw e }
            if (r.first == 200) try { ratchets.save(fromKey, peer) } catch (e: Exception) { ratchets.forget(fromKey) }
            else ratchets.forget(fromKey)
            return r
        }
    }

    /** Proven to be from them: a contact whose app speaks the ratchet gets it from now on. */
    private fun authentic(o: JSONObject, fromKey: String) {
        val c = contacts.get(fromKey) ?: run {
            otherS1(o, fromKey)
            // Someone met close by: what their app speaks, so calls and video work before they're a contact.
            val m = _met.value[fromKey] ?: return
            val caps = m.caps + ChatWire.capsOf(o)
            if (caps != m.caps) putMet(fromKey, m.copy(caps = caps))
            return
        }
        // What their app speaks: tokens only ever add up (a changed key starts a new contact with none).
        val caps = c.caps + ChatWire.capsOf(o)
        val mailbox = if (c.groupOnly) o.optString("mailbox").takeIf { it.length == 64 } ?: c.mailbox else c.mailbox
        // Cap d1: how long their app keeps our chat; anything that isn't a usable value leaves the old one.
        val days = (if (Limits.CAP in caps) Limits.envelopeDays(o) else null) ?: c.days
        // Cap s1: sealed with its clear fields, so from now on only such envelopes are taken from them.
        val now = c.copy(ratchet = c.ratchet || ChatWire.capable(o), caps = caps, mailbox = mailbox, days = days,
            boundAt = if (c.boundAt == 0L && Proof.isBound(o)) System.currentTimeMillis() else c.boundAt)
        if (now != c) contacts.set(now)
        if (days != c.days) bg { prune() }
        // Caps b1 and e1: their app says so now, so my ring key and day key go to them.
        if ((RingWire.RING_CAP in caps && RingWire.RING_CAP !in c.caps) || (RingWire.WRAP_CAP in caps && RingWire.WRAP_CAP !in c.caps)) sendKeys(fromKey)
    }

    private fun handle(o: JSONObject, envRoom: String, fromKey: String, name: String, mailbox: String?, sentId: String, via: String,
                       plain: String): Pair<Int, String> {
        val now = System.currentTimeMillis()
        // Cap m1: a reply ("rich") is a private message with the id it quotes; everything else is the same.
        var opened = plain
        var re = ""
        var poll: More.Poll? = null
        var lp: More.Preview? = null
        if (envRoom == "rich") {
            val p = try { JSONObject(plain) } catch (e: Exception) { return 400 to "{}" }
            if (!Rich.ok("rich", p)) return 400 to "{}"
            opened = p.getString("text"); re = p.optString("re")
            // Cap m2: a poll or a link preview. A malformed one is left out; the text still shows.
            if (More.ok("rich", p)) { poll = More.pollOf(p); lp = More.previewOf(p) }
        }
        val room = if (envRoom == "rich") "private" else envRoom
        // No more than 5 messages in 10 seconds from anyone: nobody can flood a room. Counted only once
        // a message proved it's really from them, so nobody can use it to block a real contact.
        // Only those quiet for 10 s go: clearing them all would let a flood of new keys wipe everyone's count.
        if (recent.size > 500) recent.entries.removeIf { e -> synchronized(e.value) { e.value.all { now - it > 10_000 } } }
        val q = recent.getOrPut(fromKey) { ArrayDeque() }
        if (room == "tvconfig" || room == "call" || room == "mx" || room == "live" || room == "intro" || room == "file" || room == "profile") { if (!groupRate(room, fromKey)) return 429 to "{}" }
        // Linked devices: only a paired device (or one answering my link code) gets past Link.receive, and a sync sends many at once.
        else if (room != "receipt" && room != "link") synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= 5) return 429 to "{}"
            q.addLast(now)
        }
        // Section 9: text over the limit is malformed and refused, never cut.
        if ((room == "private" || room == "nearby") && Proto06.chars(opened) > MAX) return 400 to "{}"
        val text = opened
        val msgId = sentId.ifEmpty { UUID.randomUUID().toString() }
        val msg = Msg(msgId, fromKey, name, text, false, now, via = via, re = re, poll = poll, preview = lp)
        // A message from them ends their "typing".
        if (room == "private" && fromKey in _typing.value) _typing.update { it - fromKey }
        when (room) {
            "mx" -> {
                if (contacts.contact(fromKey) == null && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                // Cap m2: a vote on a poll; everything else is m1.
                if (p.opt("t") == "vote") { if (!More.ok("mx", p)) return 400 to "{}"; takeVote(fromKey, p) }
                else if (!Rich.ok("mx", p)) return 400 to "{}"
                else takeMx(fromKey, name, p)
                // Confirmed by its envelope id, so the sender stops resending it.
                receipt(fromKey, listOf(sentId), "delivered", mailbox)
            }
            "live" -> {
                if (contacts.contact(fromKey) == null && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                if (!Rich.ok("live", p)) return 400 to "{}"
                if (Rich.fresh(Rich.ts(p), now)) {
                    val on = p.getBoolean("on")
                    if (p.getString("t") == "typing") { _typing.update { if (on) it + (fromKey to now + Rich.TYPING_MS) else it - fromKey }; wakeRetry() }
                    else if (id.activeStatus) {
                        _active.update { if (on) it + (fromKey to now + Rich.ACTIVE_MS) else it - fromKey }
                        wakeRetry()
                        // Cap p1: the status they chose rides on the same message.
                        profile?.tookLive(fromKey, on, p)
                    }
                }
            }
            "profile" -> {
                // Cap p1: a picture and colour, only from a contact or someone in a group with me right now.
                val c = contacts.get(fromKey)
                if (c == null || (c.groupOnly && !groups.sharesGroup(fromKey))) return 403 to "{}"
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                if (profile?.receive(fromKey, p) != true) return 400 to "{}"
            }
            "call" -> {
                // Contacts and group peers; someone met only while close by or on the same Wi-Fi.
                if (contacts.get(fromKey) == null && !(via != "internet" && _met.value.containsKey(fromKey))) return 403 to "{}"
                // Calls turned off for them (the press-and-hold sheet): their ring never reaches this phone.
                if (!callsAllowed(fromKey) && Permissions.isRing(opened)) return 200 to "{}"
                onSignal(fromKey, opened); return 200 to "{}"
            }
            "tvconfig" -> if (tvHandler?.onTvRoom(fromKey, mailbox, opened, inviteOk(o.optString("invite"))) != true) return 403 to "{}"
            "nearby" -> {
                if (!id.nearbyRoom || via == "internet") return 403 to "{}"
                addNearby(msg)
            }
            "private" -> {
                val c = contact(fromKey)
                if (c != null) {
                    if (mailbox != null && c.mailbox != mailbox) contacts.put(c.copy(mailbox = mailbox))
                    if (addPrivate(fromKey, msg)) {
                        _unread.update { it + fromKey }
                        arrivals.tryEmit(Triple(c.shown, text, fromKey))
                    }
                    receipt(fromKey, listOf(msgId), "delivered", mailbox)
                } else if (fromKey in id.muted()) {
                    return 200 to "{}"
                } else if (ChatWire.keyChangeFor(contacts.all.value, Contact(fromKey, name, mailbox, false)) != null) {
                    // A new key with a contact's inbox: maybe that contact with a new security key, maybe not. Never
                    // taken on quietly (not even close by): held until the person accepts, and warned (as Huginn does).
                    held.getOrPut(fromKey) { java.util.Collections.synchronizedMap(LinkedHashMap()) }.putIfAbsent(msgId, msg)
                    askReplace(Contact(fromKey, name, mailbox, false), via == "internet")
                } else if (via != "internet" && !id.visibleNearby) {
                    // Hidden from people nearby: only contacts get through close by or on the Wi-Fi.
                    return 403 to "{}"
                } else if (via != "internet" || _met.value.containsKey(fromKey)) {
                    // Close by or on the same Wi-Fi (or someone met that way): no asking first.
                    val m = _met.value[fromKey]
                    putMet(fromKey, (m ?: Met(name, mailbox)).copy(name = name, mailbox = mailbox ?: m?.mailbox))
                    if (addPrivate(fromKey, msg)) {
                        _unread.update { it + fromKey }
                        // Not a contact: the name they chose never shows as if it were one.
                        arrivals.tryEmit(Triple(stranger(name), text, fromKey))
                    }
                    receipt(fromKey, listOf(msgId), "delivered", mailbox)
                } else if (useOnce(o.optString("invite"))) {
                    // My one-time invite link: whoever used it first is a contact now, no asking. The link is used up.
                    putContact(Contact(fromKey, name, mailbox, verified = false))
                    if (addPrivate(fromKey, msg)) {
                        _unread.update { it + fromKey }
                        arrivals.tryEmit(Triple(name, text, fromKey))
                    }
                    notices.tryEmit("$name added you with your invite link.")
                    receipt(fromKey, listOf(msgId), "delivered", mailbox)
                } else if (!inviteOk(o.optString("invite")) && !(fromKey == LabsWire.KEY && id.labsReported)) {
                    // (A reply from the Labs contact to a report sent from here goes on to be a request.)
                    // From far away with no valid invite (an old or leaked code): dropped quietly.
                    return 403 to "{}"
                } else {
                    // A code shared some other way: they already have this phone's code, so ask.
                    held.getOrPut(fromKey) { java.util.Collections.synchronizedMap(LinkedHashMap()) }.putIfAbsent(msgId, msg)
                    var isNew = false
                    _requests.update { list -> if (list.any { it.peerKey == fromKey }) list else { isNew = true; list + Request(fromKey, name, ChatCrypto.pairingCode(sharedKey(fromKey)), mailbox, via == "internet") } }
                    if (isNew) {
                        notices.tryEmit("$name wants to chat")
                        arrivals.tryEmit(Triple(name, "Wants to chat with you", fromKey))
                    }
                }
            }
            "receipt" -> {
                // Group peers send receipts too (a caps probe, or its answer).
                if (contacts.get(fromKey) == null && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val r = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                // Section 9: at most 40 ids a receipt; more is malformed and refused.
                val a = r.optJSONArray("ids") ?: return 400 to "{}"
                if (a.length() > Proto06.MAX_RECEIPT_IDS) return 400 to "{}"
                // A caps probe (1.2): the answer's envelope carries my caps.
                if (r.optString("q") == "caps") bg {
                    deliver(fromKey, envelope("receipt", fromKey, withKeys(fromKey, JSONObject().put("ids", JSONArray()).put("s", "delivered")).toString(),
                        UUID.randomUUID().toString()), mailbox ?: contacts.get(fromKey)?.mailbox)
                }
                // Caps b1 and e1: their ring key and newest day key, from a contact or group peer.
                if (contacts.get(fromKey) != null) peerKeys.take(fromKey, RingWire.announceOf(r, System.currentTimeMillis() / 1000))
                val ids = (0 until a.length()).map { a.optString(it) }.toSet()
                val st = r.optString("s")
                if (st != "delivered" && st != "read" && st != "unsent") return 400 to "{}"
                // A reaction or edit (cap m1) got there: stop resending it.
                if (st == "delivered" && ids.any { mxOut[it]?.first == fromKey }) {
                    ids.forEach { if (mxOut[it]?.first == fromKey) { mxOut.remove(it); outbox.drop(it); tries.remove(it) } }
                    saveMx()
                }
                // Got there: my sealed envelopes to them needn't be kept for resending any more.
                _private.value[fromKey].orEmpty().forEach { m ->
                    if (m.mine && m.id in ids) { if (st == "unsent") outbox.drop(UNSEND + m.id) else outbox.drop(m.id) }
                }
                edit { map ->
                    val before = map[fromKey] ?: return@edit false
                    map[fromKey] = before.map { m ->
                        when {
                            !m.mine || m.id !in ids -> m
                            st == "unsent" -> if (m.state == "unsending") m.copy(state = "unsent").also { tries.remove(m.id) } else m
                            m.unsent.isEmpty() && rank(st) > rank(m.state) -> m.copy(state = st, failed = false)
                            else -> m
                        }
                    }
                    true
                }
            }
            "unsend" -> {
                if (!isContact(fromKey) && !_met.value.containsKey(fromKey)) return 403 to "{}"
                val gone = text.trim()
                var found = false
                edit { map ->
                    val list = map[fromKey] ?: return@edit false
                    val m = list.firstOrNull { it.id == gone && !it.mine } ?: return@edit false
                    found = true
                    if (m.unsent.isNotEmpty()) return@edit false
                    if (m.photo.isNotEmpty()) File(photosDir, m.photo).delete()
                    map[fromKey] = list.map { if (it.id == gone) it.copy(text = "", photo = "", voice = 0, unsent = name) else it }
                    true
                }
                // Not here yet (still on its way): make sure it never shows.
                if (!found) { forget(listOf(gone)); held[fromKey]?.remove(gone) }
                recalls.tryEmit(fromKey)
                receipt(fromKey, listOf(gone), "unsent", mailbox)
            }
            "link" -> {
                // Linked devices (companion style): only from a device paired with this one, or answering my link code.
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                if (!Link.receive(fromKey, p)) return 403 to "{}"
            }
            "file" -> {
                if (contacts.contact(fromKey) == null) return 403 to "{}"
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                if (!FileLink.ok(p)) return 400 to "{}"
                // Files turned off for them: their offers are dropped quietly.
                if (!filesAllowed(fromKey)) return 200 to "{}"
                FileLink.onSignal(fromKey, p)
            }
            "intro" -> {
                val p = try { JSONObject(text) } catch (e: Exception) { return 400 to "{}" }
                if (!Trust.ok(p)) return 400 to "{}"
                if (!takeIntro(fromKey, name, mailbox, p)) return 403 to "{}"
            }
            "keep" -> {
                if (isContact(fromKey)) {
                    // Already a contact here, so my own yes went missing (or they were added another way) and they wait
                    // forever: say yes back, once, and they become a contact too.
                    if (text == "yes" && keptBack.add(fromKey)) bg {
                        deliver(fromKey, envelope("keep", fromKey, "yes", UUID.randomUUID().toString()), mailbox ?: contacts.get(fromKey)?.mailbox)
                    }
                    return 200 to "{}"
                }
                val m = _met.value[fromKey] ?: if (via != "internet") Met(name, mailbox) else return 403 to "{}"
                if (text != "yes" || m.theySaid) return 200 to "{}"
                putMet(fromKey, m.copy(theySaid = true, mailbox = mailbox ?: m.mailbox))
                if (m.iSaid) becomeContacts(fromKey)
                else { notices.tryEmit("${stranger(name)} wants to keep in touch"); arrivals.tryEmit(Triple(stranger(name), "Wants to keep in touch", fromKey)) }
            }
            else -> return 400 to "{}"
        }
        if (room in ChatWire.SEEN_ROOMS && sentId.isNotEmpty()) seen.add("$fromKey|$sentId", if (room == "unsend") text.trim() else "")
        return 200 to "{}"
    }

    fun acceptRequest(r: Request) {
        // Accepting is the person's own say-so, also for "says it replaces" (they saw the warning).
        putContact(Contact(r.peerKey, r.name, r.mailbox, verified = !r.viaInternet && r.replaces.isEmpty()), confirmed = true)
        _requests.update { l -> l.filterNot { it.peerKey == r.peerKey } }
        held.remove(r.peerKey)?.let { m ->
            val list = synchronized(m) { m.values.toList() }
            list.forEach { addPrivate(r.peerKey, it) }
            receipt(r.peerKey, list.map { it.id }, "delivered", r.mailbox)
        }
    }

    // ------------------------------------------------------------------ Labs reports (protocol-0.6.md, "Labs")

    /**
     * A Labs report or one of its screenshot's pieces, to this phone (the Labs contact). Taken from anyone, with no
     * contact code, but always sealed v1 to the Labs key by the sender's own chat key, and at most [LabsWire.PER_DAY] a day each.
     */
    private fun receiveLabs(o: JSONObject, room: String, fromKey: String, name: String): Pair<Int, String> {
        val lb = o.optJSONObject(LabsWire.FIELD)
        if (!LabsWire.ok(lb) || lb == null) return 400 to "{}"
        val msgId = o.optString("id")
        if (msgId.isEmpty() || msgId.length > 64) return 400 to "{}"
        val now = System.currentTimeMillis()
        if (room == "private" && labs.known(msgId)) return 200 to "{}"
        if (!labs.allowed(fromKey, now)) return 429 to "{}"
        if (room != "private" && room != "photo") return 400 to "{}"
        val sk = try { sharedKey(fromKey) } catch (e: Exception) { return 400 to "{}" }
        // Always v1 (never the ratchet), so a report opens whether or not the sender is a contact here.
        val plain = ChatWire.open(o, sk, id.keys, null, ChatCrypto::newKeyPair, id.publicKey) ?: return 403 to "{}"
        if (room == "photo") return labs.piece(fromKey, msgId, o.optInt("i", -1), o.optInt("n"), plain, now) to "{}"
        val text = String(plain, Charsets.UTF_8).trim()
        if (!LabsWire.textOk(text)) return 400 to "{}"
        val mailbox = o.optString("mailbox").takeIf { it.length == 64 }
        val r = LabsInbox.Report(msgId, fromKey, name, mailbox, lb.optString("app"), lb.optString("v"), lb.optString("os"), lb.optString("model"),
            text, now, lb.optString("s"))
        val code = labs.add(r)
        if (code == 200) arrivals.tryEmit(Triple(LabsWire.NAME, "Bug report from $name", LABS_PAGE))
        return code to "{}"
    }

    /**
     * Sends a Labs report: [text], and [jpeg] (a screenshot already stripped of metadata) if given. Sealed v1 to the
     * Labs key like a first message, to its mailbox over the internet only. [done]: true once relays hold all of it.
     */
    fun sendLabsReport(text: String, jpeg: ByteArray?, info: JSONObject, done: (Boolean) -> Unit) {
        val t = text.trim()
        if (!LabsWire.textOk(t) || isLabs()) { done(false); return }
        bg {
            if (Move.locked) { done(false); return@bg }
            val sk = sharedKey(LabsWire.KEY)
            val shotId = if (jpeg != null) UUID.randomUUID().toString() else ""
            fun base(room: String, msgId: String, lb: JSONObject) =
                ChatWire.base(room, id.publicKey, id.username, id.fingerprint, nostr.publicKey, msgId).put(LabsWire.FIELD, lb)
            val bodies = ArrayList<String>()
            if (jpeg != null) {
                val parts = Photos.split(jpeg)
                if (parts.size > LabsWire.MAX_SHOT_PIECES) { done(false); return@bg }
                parts.forEachIndexed { i, part ->
                    bodies += ChatWire.seal(base("photo", shotId, JSONObject(info.toString())).put("i", i).put("n", parts.size),
                        part, sk, LabsWire.KEY, null, ChatCrypto::newKeyPair).toString()
                }
            }
            val lb = JSONObject(info.toString()).apply { if (shotId.isNotEmpty()) put("s", shotId) }
            bodies += ChatWire.seal(base("private", UUID.randomUUID().toString(), lb), t.toByteArray(Charsets.UTF_8), sk, LabsWire.KEY, null,
                ChatCrypto::newKeyPair).toString()
            id.labsReported = true
            val once = java.util.concurrent.atomic.AtomicBoolean(false)
            val finish: (Boolean) -> Unit = { ok -> if (once.compareAndSet(false, true)) done(ok) }
            // A relay that never answers: give up after a minute.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish(false) }, 60_000)
            if (!internet(LabsWire.KEY, bodies, LabsWire.MAILBOX, stored = { finish(true) }, days = 7)) finish(false)
        }
    }

    /** Labs: answer a report. Its sender becomes a contact here and their chat opens; the first message goes as a request to them. */
    fun labsReply(r: LabsInbox.Report) {
        if (!isContact(r.from)) putContact(Contact(r.from, r.name, r.mailbox, verified = false), confirmed = true)
    }

    // ------------------------------------------------------------------ photos
    // A photo is shrunk on the sending phone (Photos.kt), cut into pieces of 16 KB, and each piece is
    // sealed and sent like a message (room "photo"). The other phone puts them back together and
    // answers "delivered" once it has them all. Pieces fit every route: Nearby's 32 KB limit and relays'.

    private class Incoming(val from: String, val parts: Array<ByteArray?>, val started: Long)
    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val pieceTimes = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun photo(photoId: String): ByteArray? = vault.readBytes(File(photosDir, photoId))

    fun sendPhoto(peerKey: String, jpeg: ByteArray) = sendBlob(peerKey, jpeg, 0)

    /** Several photos at once: an album (cap m2; an app without m2 shows them one by one). */
    fun sendPhotos(peerKey: String, jpegs: List<ByteArray>) {
        if (jpegs.size == 1) { sendPhoto(peerKey, jpegs[0]); return }
        jpegs.chunked(More.ALBUM_MAX).forEach { part ->
            val album = UUID.randomUUID().toString()
            part.forEachIndexed { i, j -> sendBlob(peerKey, j, 0, album = Triple(album, i, part.size)) }
        }
    }

    /** A recorded voice message (AAC, no container, so nothing but sound). */
    fun sendVoice(peerKey: String, aac: ByteArray, ms: Int) = sendBlob(peerKey, aac, ms.coerceAtLeast(1))

    /** Their app plays video messages, polls and albums (cap m2). */
    fun hasM2(peerKey: String) = contacts.get(peerKey)?.caps?.contains(More.CAP) == true

    /** A video message (cap m2): an MP4 of at most a minute. Null when sent, else what to say. */
    fun sendVideo(peerKey: String, mp4: ByteArray, ms: Int): String? {
        if (!mayHave(peerKey, More.CAP)) return "${nameOf(peerKey)} needs a newer Muninn to get video messages."
        if (!More.isVideo(mp4) || mp4.size > Photos.MAX_PIECES * Photos.PIECE) return "That video is too big to send."
        sendBlob(peerKey, mp4, 0, videoMs = ms.coerceIn(1, More.VIDEO_MAX_MS))
        return null
    }

    /** Their app takes files over the internet (cap f1). */
    fun hasF1(peerKey: String) = contacts.get(peerKey)?.caps?.contains(FileLink.CAP) == true

    /**
     * A file to [peerKey] over the internet: straight to their phone when both trust each other (never through
     * Cloudflare), or through the relays when it's small. Null when it's on its way, else what to say.
     */
    fun sendFile(peerKey: String, file: File, name: String): String? {
        val who = nameOf(peerKey)
        if (!hasF1(peerKey)) { probeCaps(peerKey); file.delete(); return "$who needs a newer Muninn to get files over the internet." }
        val clean = name.replace('/', '_').replace('\\', '_').filter { it.code >= 0x20 }.take(200).ifBlank { "file" }
        val direct = FileLink.send(peerKey, file, clean)
        if (direct == null) return null
        if (file.length() <= Photos.INTERNET_PIECES.toLong() * Photos.PIECE) {
            val bytes = file.readBytes(); file.delete()
            sendBlob(peerKey, bytes, 0, fileName = clean)
            return null
        }
        file.delete()
        return "Big files go straight between phones, which needs Direct calls on for each other (in the chat's menu). Or send it on the same Wi-Fi from Files."
    }

    private fun sendBlob(peerKey: String, jpeg: ByteArray, voiceMs: Int, videoMs: Int = 0, album: Triple<String, Int, Int>? = null, fileName: String = "") {
        val name = nameOf(peerKey)
        if (!isContact(peerKey)) meet(peerKey, name)
        val r = route(peerKey)
        val msgId = UUID.randomUUID().toString()
        vault.writeBytes(File(photosDir, msgId), jpeg)
        val mine = Msg(msgId, peerKey, id.username, "", true, System.currentTimeMillis(), via = r, state = "sending", photo = msgId, voice = voiceMs,
            video = videoMs, album = album?.first ?: "", albumAt = album?.second ?: 0, albumOf = album?.third ?: 0, file = fileName)
        tries[mine.id] = Tries(0, System.currentTimeMillis() + RETRY[0])
        addPrivate(peerKey, mine)
        sends.tryEmit(peerKey)
        if (r.isEmpty() && backupMailbox(mine) == null) {
            replace(peerKey, mine.copy(failed = true))
            notices.tryEmit("$name is out of range. Say yes to Keep in touch to send from anywhere.")
            return
        }
        bg { attempt(mine) }
    }

    /** The photo's sealed pieces: made (and encrypted) once, then the same bytes on every try and route. */
    private fun pieces(m: Msg): List<String>? = outbox.fresh(m.id, stale(m.peerKey)) {
        val bytes = photo(m.photo) ?: return@fresh null
        val parts = Photos.split(bytes)
        seal("photo", m.peerKey, parts.mapIndexed { i, part ->
            ChatWire.base("photo", id.publicKey, id.username, id.fingerprint, nostr.publicKey, m.id).put("i", i).put("n", parts.size)
                .apply {
                    if (m.voice > 0) put("kind", "voice").put("ms", m.voice)
                    if (m.video > 0) put("kind", "video").put("ms", m.video)
                    if (m.file.isNotEmpty()) put("kind", "file").put("fn", m.file)
                    if (m.album.isNotEmpty()) put("al", m.album).put("ai", m.albumAt).put("an", m.albumOf)
                } to part
        }).map { it.toString() }
    }

    private fun receivePiece(o: JSONObject, fromKey: String, name: String, via: String): Pair<Int, String> {
        val contact = contacts.contact(fromKey)
        if (contact == null && via == "internet" && !_met.value.containsKey(fromKey)) return 403 to "{}"
        val msgId = o.optString("id")
        val n = o.optInt("n"); val i = o.optInt("i", -1)
        if (msgId.isEmpty() || n < 1 || n > Photos.MAX_PIECES || i !in 0 until n) return 400 to "{}"
        // Cap m2: album fields and video messages must be well-formed.
        if (!More.ok("piece", o)) return 400 to "{}"
        // Files turned off for them: a file's pieces are dropped quietly.
        if (o.optString("kind") == "file" && !filesAllowed(fromKey)) return 200 to "{}"
        val mailbox = o.optString("mailbox").takeIf { it.length == 64 }
        // Already have it (a copy from another route, or a resend): confirm again, or just drop a piece already here.
        val dup = {
            if (known(fromKey, msgId)) { receipt(fromKey, listOf(msgId), "delivered", mailbox); 200 to "{}" }
            else if (incoming[msgId]?.let { it.from == fromKey && it.parts.size == n && it.parts[i] != null } == true) 200 to "{}"
            else null
        }
        dup()?.let { return it }
        val now = System.currentTimeMillis()
        val q = pieceTimes.getOrPut(fromKey) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= 1000) return 429 to "{}"   // about 15 MB a minute at most
            q.addLast(now)
        }
        if (incoming.size > 20 && !incoming.containsKey(msgId)) return 429 to "{}"
        return opened(o, fromKey, dup) { part -> takePiece(o, fromKey, name, via, msgId, n, i, mailbox, part) }
    }

    private fun takePiece(o: JSONObject, fromKey: String, name: String, via: String, msgId: String, n: Int, i: Int, mailbox: String?,
                          part: ByteArray): Pair<Int, String> {
        val contact = contacts.contact(fromKey)
        val inc = incoming.getOrPut(msgId) { Incoming(fromKey, arrayOfNulls(n), System.currentTimeMillis()) }
        wakeRetry()   // the loop clears half a photo that never finishes
        if (inc.from != fromKey || inc.parts.size != n) return 400 to "{}"
        synchronized(inc) {
            inc.parts[i] = part
            if (inc.parts.any { it == null }) return 200 to "{}"
            incoming.remove(msgId)
        }
        val voiceMs = if (o.optString("kind") == "voice") o.optInt("ms").coerceIn(1, Voice.MAX_MS) else 0
        val videoMs = if (o.optString("kind") == "video") o.optInt("ms").coerceIn(1, More.VIDEO_MAX_MS) else 0
        val fileName = if (o.optString("kind") == "file") o.optString("fn") else ""
        val joined = inc.parts.map { it!! }
        val jpeg = (when {
            voiceMs > 0 -> Voice.check(joined)
            videoMs > 0 -> Videos.join(joined)
            fileName.isNotEmpty() -> java.io.ByteArrayOutputStream().also { b -> joined.forEach { b.write(it) } }.toByteArray()
            else -> Photos.join(joined)
        }) ?: return 400 to "{}"
        vault.writeBytes(File(photosDir, msgId), jpeg)
        if (contact == null) {
            val m = _met.value[fromKey]
            putMet(fromKey, (m ?: Met(name, mailbox)).copy(name = name, mailbox = mailbox ?: m?.mailbox))
        } else if (mailbox != null && contact.mailbox != mailbox) contacts.put(contact.copy(mailbox = mailbox))
        val album = o.optString("al")
        if (addPrivate(fromKey, Msg(msgId, fromKey, name, "", false, System.currentTimeMillis(), via = via, photo = msgId, voice = voiceMs,
                video = videoMs, album = album, albumAt = if (album.isEmpty()) 0 else o.optInt("ai"), albumOf = if (album.isEmpty()) 0 else o.optInt("an"),
                file = fileName))) {
            _unread.update { it + fromKey }
            arrivals.tryEmit(Triple(contact?.shown ?: name, when { voiceMs > 0 -> "Voice message"; videoMs > 0 -> "Video message"; fileName.isNotEmpty() -> "File: $fileName"
                album.isNotEmpty() -> "Photos"; else -> "Photo" }, fromKey))
        }
        receipt(fromKey, listOf(msgId), "delivered", mailbox)
        return 200 to "{}"
    }

    fun declineRequest(r: Request) {
        _requests.update { l -> l.filterNot { it.peerKey == r.peerKey } }
        held.remove(r.peerKey)
    }

    /** A code the person scanned, pasted or tapped themselves (the add screen warns about a key change first). */
    fun addContact(c: Contact) = putContact(c, confirmed = true)

    /** The contact [c] would replace, if adding it changes someone's security key (for a warning before adding). */
    fun keyChangeFor(c: Contact): Contact? = ChatWire.keyChangeFor(contacts.all.value, c)

    /** Someone close by or on the Wi-Fi, after both screens showed the same code. */
    fun addNearbyPerson(chatKey: String, name: String) {
        val mailbox = close.list.value.firstOrNull { it.chatKey == chatKey }?.mailbox
        putContact(Contact(chatKey, name, mailbox, verified = true))
    }

    /**
     * Every way someone becomes a contact comes through here. If they stand in for a contact already
     * here with another key (same mailbox; a name alone never counts), it's a key change: never
     * taken silently. The contact (and chat, and nickname) stays, now unverified, the ratchet starts
     * over, and both a notice and a line in the chat say so until the code is checked.
     */
    private fun putContact(c: Contact, confirmed: Boolean = false) {
        val old = ChatWire.keyChangeFor(contacts.all.value, c)
        // A key change only with the person's own say-so (a code they took, a request they accepted): otherwise a
        // warned request that says who it claims to replace.
        if (old != null && !confirmed) { askReplace(c, true); return }
        // Adding someone in the trash again brings them back.
        trash.drop(c.chatKey)
        val was = contacts.contact(c.chatKey)
        if (old == null) { contacts.put(c); if (was == null) sayHello(c.chatKey); return }
        val now = ChatWire.afterKeyChange(old, c)
        contacts.replaceKey(old.chatKey, now)
        synchronized(ratchets.lock(old.chatKey)) { ratchets.drop(old.chatKey) }
        synchronized(ratchets.lock(c.chatKey)) { ratchets.drop(c.chatKey) }
        moveChat(old.chatKey, c.chatKey)
        notices.tryEmit(ChatWire.keyChangedText(now.shown))
        sayHello(c.chatKey)
    }

    /** Someone claims to be a contact with a new key: a request, with a warning, until the person decides. */
    private fun askReplace(c: Contact, viaInternet: Boolean) {
        val old = ChatWire.keyChangeFor(contacts.all.value, c) ?: return
        synchronized(lock) { if (_met.value.containsKey(c.chatKey)) { _met.update { it - c.chatKey }; saveMet() } }
        var isNew = false
        _requests.update { list -> if (list.any { it.peerKey == c.chatKey }) list
            else { isNew = true; list + Request(c.chatKey, c.name, ChatCrypto.pairingCode(sharedKey(c.chatKey)), c.mailbox, viaInternet, replaces = old.chatKey) } }
        if (isNew) {
            notices.tryEmit("Someone says they're ${old.shown} with a new security key. Check with them before accepting.")
            arrivals.tryEmit(Triple(old.shown, "Says their security key changed", c.chatKey))
        }
    }

    /**
     * Just added (a code, an introduction, a Family offer, Keep in touch, an invite link): a small hello that
     * carries my caps and asks for theirs (the 1.2 caps probe), so Call, Video and video messages show at once on
     * both sides. Not rate limited like [probeCaps]: it goes once per new contact.
     */
    private fun sayHello(peerKey: String) {
        probed[peerKey] = System.currentTimeMillis()
        bg {
            val body = JSONObject().put("ids", JSONArray()).put("s", "delivered").put("q", "caps").toString()
            deliver(peerKey, envelope("receipt", peerKey, body, UUID.randomUUID().toString()), contacts.get(peerKey)?.mailbox ?: _met.value[peerKey]?.mailbox)
        }
    }

    /** The chat with [from] continues under their new key [to]. Anything of mine still on its way stops
     *  (it was sealed to the old key): it shows "Didn't send", and Try again sends it to the new key. */
    private fun moveChat(from: String, to: String) {
        edit { map ->
            val old = map.remove(from) ?: return@edit false
            val moved = old.map { m ->
                val pending = m.mine && m.unsent.isEmpty() && m.state.isNotEmpty() && rank(m.state) < 3
                if (pending || m.state == "unsending") { outbox.drop(m.id); outbox.drop(UNSEND + m.id); tries.remove(m.id) }
                when {
                    pending -> m.copy(peerKey = to, state = "", failed = true)
                    m.state == "unsending" -> m.copy(peerKey = to, state = "unsent")
                    else -> m.copy(peerKey = to)
                }
            }
            map[to] = (moved + map[to].orEmpty()).distinctBy { it.id }.sortedBy { it.time }.takeLast(1000)
            true
        }
        if (from in _unread.value) _unread.update { it - from + to }
        synchronized(lock) { if (_met.value.containsKey(to)) { _met.update { it - to }; saveMet() } }
    }

    /** The chat is on screen: clear the dot and, if allowed, tell them what was read. */
    fun markRead(peerKey: String) {
        _unread.update { it - peerKey }
        flags.read(peerKey)
        if (!id.readReceipts) return
        var fresh = emptyList<Msg>()
        edit { map ->
            fresh = map[peerKey].orEmpty().filter { !it.mine && it.state != "read" }
            if (fresh.isEmpty()) return@edit false
            map[peerKey] = map[peerKey].orEmpty().map { if (!it.mine && it.state != "read") it.copy(state = "read") else it }
            true
        }
        receipt(peerKey, fresh.map { it.id }, "read", null)
    }

    private fun rank(s: String) = when (s) { "sending" -> 1; "sent" -> 2; "delivered" -> 3; "read" -> 4; else -> 0 }

    /** Tells the sender their messages arrived (always) or were read (only with read receipts on). Sealed like any message. */
    private fun receipt(peerKey: String, ids0: List<String>, state: String, mailbox: String?) {
        // "Delivered" for the same message at most once every few minutes: resends and photo pieces don't each get one.
        val ids = if (state != "delivered") ids0 else {
            val now = System.currentTimeMillis()
            if (receipted.size > 2000) receipted.entries.removeIf { now - it.value > NetLogic.RECEIPT_GAP_MS }
            ids0.filter { id -> val k = "$peerKey|$id"; NetLogic.receiptDue(receipted[k], now).also { if (it) receipted[k] = now } }
        }
        if (ids.isEmpty()) return
        bg {
            // 40 ids a receipt keeps each one small on every route.
            ids.takeLast(200).chunked(40).forEach { part ->
                val body = withKeys(peerKey, JSONObject().put("ids", JSONArray(part)).put("s", state)).toString()
                deliver(peerKey, envelope("receipt", peerKey, body, UUID.randomUUID().toString()),
                    mailbox ?: contacts.get(peerKey)?.mailbox ?: _met.value[peerKey]?.mailbox)
            }
        }
    }

    /**
     * Caps b1 and e1 (section 23): a receipt to an app that says them carries my ring key and ring mailbox ("rk", "rp")
     * and my day key for the relay wrapping ("wk", "wt").
     */
    private fun withKeys(peerKey: String, o: JSONObject): JSONObject {
        val caps = capsOf(peerKey)
        // The ring key only to contacts (never a group peer or someone met close by).
        if (RingWire.RING_CAP in caps && isContact(peerKey)) ringKey?.let { r -> try { o.put("rk", r.publicKey).put("rp", r.mailbox) } catch (_: Exception) {} }
        if (RingWire.WRAP_CAP in caps) try { wrapKeys.current().let { d -> o.put("wk", d.pub).put("wt", d.t) } } catch (_: Exception) {}
        return o
    }

    /**
     * My keys, in an empty receipt, to every contact [which] picks (after a restore, or a new ring key after a block).
     * Never to anyone blocked or in the trash.
     */
    private fun announceKeys(which: (Contact) -> Boolean) {
        val muted = id.muted()
        for (c in contacts.all.value) {
            if (c.groupOnly || c.chatKey in muted || trash.has(c.chatKey) || !which(c)) continue
            val body = withKeys(c.chatKey, JSONObject().put("ids", JSONArray()).put("s", "delivered")).toString()
            try { deliver(c.chatKey, envelope("receipt", c.chatKey, body, UUID.randomUUID().toString()), c.mailbox) } catch (_: Exception) {}
        }
    }

    /** Erase everything and Move: the day keys go (the ring key and the locked store are dropped by the app). */
    fun forgetKeys() { wrapKeys.drop(); try { File(context.filesDir, "wrapkeys.json").delete() } catch (_: Exception) {} }

    /** Their app just said b1 or e1: my keys go to them at once, in an empty receipt (once each while Muninn runs). */
    private fun sendKeys(peerKey: String) {
        if (!keysSent.add(peerKey)) return
        bg {
            val body = withKeys(peerKey, JSONObject().put("ids", JSONArray()).put("s", "delivered")).toString()
            deliver(peerKey, envelope("receipt", peerKey, body, UUID.randomUUID().toString()), contacts.get(peerKey)?.mailbox)
        }
    }

    // ------------------------------------------------------------------ sending

    fun sendNearby(text: String) {
        val t = ChatWire.clip(text.trim(), MAX)
        if (t.isEmpty() || !id.nearbyRoom) return
        val mine = Msg(UUID.randomUUID().toString(), id.publicKey, id.username, t, true, System.currentTimeMillis())
        addNearby(mine)
        val muted = id.muted()
        bg {
            var reached = 0
            val done = mutableSetOf<String>()
            for (p in close.list.value) {
                val k = p.chatKey ?: continue
                if (!p.connected || k in muted || !done.add(k)) continue
                close.send(p.endpoint, envelope("nearby", k, t, mine.id).put("t", "msg")); reached++
            }
            for (p in ble.peers.value) {
                val k = p.chatKey ?: continue
                if (k in muted || !done.add(k)) continue
                if (ble.send(k, envelope("nearby", k, t, mine.id).toString())) reached++
            }
            for (d in net.devices.value) {
                val k = d.chatKey ?: continue
                if (d.chatName == null || k in muted || !done.add(k)) continue
                if (net.post(d, PATH, envelope("nearby", k, t, mine.id).toString()) == 200) reached++
            }
            if (reached == 0) notices.tryEmit("Nobody nearby got that yet. They need Muninn or Huginn open close by or on the same Wi-Fi.")
        }
    }

    /** [re]: the id of the message this answers (a reply, cap m1; to an app without m1 it goes as plain text). */
    fun sendPrivate(peerKey: String, text: String, re: String = "") {
        val t = ChatWire.clip(text.trim(), MAX)
        if (t.isEmpty()) return
        val name = nameOf(peerKey)
        if (!isContact(peerKey)) meet(peerKey, name)
        val r = route(peerKey)
        typingSent.remove(peerKey)
        val mine = Msg(UUID.randomUUID().toString(), peerKey, id.username, t, true, System.currentTimeMillis(), via = r, state = "sending",
            re = re.takeIf { Rich.isMsgId(it) } ?: "")
        send(peerKey, name, r, mine)
    }

    /** Adds [mine] to the chat and sends it; a link in a message to an m1 app gets its preview first (cap m2). */
    private fun send(peerKey: String, name: String, r: String, mine: Msg) {
        tries[mine.id] = Tries(0, System.currentTimeMillis() + RETRY[0])
        addPrivate(peerKey, mine)
        sends.tryEmit(peerKey)
        if (r.isEmpty() && backupMailbox(mine) == null) {
            replace(peerKey, mine.copy(failed = true))
            notices.tryEmit(if (isContact(peerKey)) "$name can't be reached: they're not nearby, and their phone hasn't been online with Muninn yet."
                else "$name is out of range. Say yes to Keep in touch to message from anywhere.")
            return
        }
        val link = if (id.linkPreviews && mine.poll == null && hasM1(peerKey)) More.firstLink(mine.text) else null
        if (link == null) { bg { attempt(mine) }; return }
        tries[mine.id] = Tries(0, System.currentTimeMillis() + 30_000)   // the retry loop waits for the preview
        bg {
            // Looked up once, before it's sealed: every try and route then sends the same bytes.
            LinkPreview.fetch(link)?.let { lp -> update(peerKey, mine.id) { it.copy(preview = lp) } }
            attempt(_private.value[peerKey]?.firstOrNull { it.id == mine.id } ?: mine)
        }
    }

    /** A poll (cap m2): a question and 2 to 10 answers. Null when sent, else what to say. */
    fun sendPoll(peerKey: String, question: String, options: List<String>, multi: Boolean): String? {
        if (!hasM2(peerKey)) { probeCaps(peerKey); return "${nameOf(peerKey)} needs a newer Muninn to answer polls." }
        val poll = More.Poll(question.trim(), options.map { it.trim() }.filter { it.isNotEmpty() }, multi)
        if (!More.ok("rich", JSONObject().put("text", More.fallback(poll.q, poll.options)).put("poll", More.pollJson(poll))))
            return "A poll needs a question and 2 to ${More.OPTIONS_MAX} different answers."
        val name = nameOf(peerKey)
        val r = route(peerKey)
        val mine = Msg(UUID.randomUUID().toString(), peerKey, id.username, More.fallback(poll.q, poll.options), true, System.currentTimeMillis(),
            via = r, state = "sending", poll = poll)
        send(peerKey, name, r, mine)
        return null
    }

    /** My vote on a poll in the chat with [peerKey] ([] takes it back). Null when done, else what to say. */
    fun vote(peerKey: String, msgId: String, choices: List<Int>): String? {
        val ts = System.currentTimeMillis()
        val v = choices.distinct().sorted()
        var done = false
        edit { map ->
            val list = map[peerKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@edit false
            val poll = m.poll ?: return@edit false
            val vv = if (poll.multi) v else v.take(1)
            val all = More.vote(m.votes, poll.options.size, id.publicKey, vv, ts) ?: return@edit false
            map[peerKey] = list.map { if (it.id == msgId) it.copy(votes = all) else it }
            done = true
            true
        }
        if (!done) return null
        val sent = _private.value[peerKey]?.firstOrNull { it.id == msgId }?.votes?.get(id.publicKey)?.v ?: v
        queueMx(peerKey, More.voteMsg(msgId, sent, ts))
        return null
    }

    /** A vote from them, already checked (More.check). */
    private fun takeVote(fromKey: String, p: JSONObject) {
        val msgId = p.getString("msg")
        val ts = Rich.ts(p)
        val a = p.getJSONArray("v")
        val v = (0 until a.length()).map { a.getInt(it) }
        edit { map ->
            val list = map[fromKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@edit false
            val poll = m.poll ?: return@edit false
            val all = More.vote(m.votes, poll.options.size, fromKey, if (poll.multi) v else v.take(1), ts) ?: return@edit false
            map[fromKey] = list.map { if (it.id == msgId) it.copy(votes = all) else it }
            true
        }
    }

    // ------------------------------------------------------------------ cap m1: reactions, edits, typing, active status
    // (shared/docs/protocol-0.6.md, section 11). Only ever sent to someone whose app said "m1".

    /** Their app speaks m1: replies, reactions, edits, typing and active status. */
    fun hasM1(peerKey: String) = contacts.get(peerKey)?.caps?.contains(Rich.CAP) == true

    /** My reaction to message [msgId] in the chat with [peerKey] ("" takes it away). Null when done, else what to say. */
    fun react(peerKey: String, msgId: String, e: String): String? {
        if (!Rich.emojiOk(e)) return "That reaction can't be sent."
        val ts = System.currentTimeMillis()
        var done = false
        edit { map ->
            val list = map[peerKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@edit false
            val all = Rich.react(m.reactions, id.publicKey, e, ts) ?: return@edit false
            map[peerKey] = list.map { if (it.id == msgId) it.copy(reactions = all) else it }
            done = true
            true
        }
        if (!done) return null
        if (!hasM1(peerKey)) { probeCaps(peerKey); return "${nameOf(peerKey)} needs a newer Muninn or Huginn to see reactions." }
        queueMx(peerKey, Rich.reactMsg(msgId, e, ts))
        return null
    }

    /** Changes the text of one of my messages, on both sides ("(edited)"). Null when done, else what to say. */
    fun editMessage(peerKey: String, msgId: String, text: String): String? {
        val t = ChatWire.clip(text.trim(), MAX)
        if (t.isEmpty()) return "A message can't be empty. Unsend it instead."
        if (!hasM1(peerKey)) { probeCaps(peerKey); return "${nameOf(peerKey)} needs a newer Muninn or Huginn to see edits." }
        val ts = System.currentTimeMillis()
        var done = false
        edit { map ->
            val list = map[peerKey] ?: return@edit false
            val m = list.firstOrNull { it.id == msgId && it.mine && it.unsent.isEmpty() && it.photo.isEmpty() } ?: return@edit false
            if (m.text == t) return@edit false
            map[peerKey] = list.map { if (it.id == msgId) it.copy(text = t, edited = maxOf(ts, m.edited + 1)) else it }
            done = true
            true
        }
        if (done) queueMx(peerKey, Rich.editMsg(msgId, t, _private.value[peerKey]?.firstOrNull { it.id == msgId }?.edited ?: ts))
        return null
    }

    /** A reaction or edit from them, already checked (Rich.check). */
    private fun takeMx(fromKey: String, name: String, p: JSONObject) {
        val msgId = p.getString("msg")
        val ts = Rich.ts(p)
        if (p.getString("t") == "react") {
            val e = p.getString("e")
            var mine: Msg? = null
            edit { map ->
                val list = map[fromKey] ?: return@edit false
                val m = list.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@edit false
                val all = Rich.react(m.reactions, fromKey, e, ts) ?: return@edit false
                map[fromKey] = list.map { if (it.id == msgId) it.copy(reactions = all) else it }
                if (m.mine && e.isNotEmpty()) mine = m
                true
            }
            mine?.let { m -> arrivals.tryEmit(Triple(contacts.get(fromKey)?.shown ?: name, "Reacted $e to “${preview(m)}”", fromKey)) }
        } else {
            val t = p.getString("text")
            edit { map ->
                val list = map[fromKey] ?: return@edit false
                val m = list.firstOrNull { it.id == msgId && !it.mine && it.unsent.isEmpty() && it.photo.isEmpty() } ?: return@edit false
                if (!Rich.editWins(fromKey, fromKey, ts, m.edited)) return@edit false
                map[fromKey] = list.map { if (it.id == msgId) it.copy(text = t, edited = ts) else it }
                true
            }
        }
    }

    private fun preview(m: Msg) = when { m.voice > 0 -> "Voice message"; m.video > 0 -> "Video message"; m.file.isNotEmpty() -> "File: ${m.file}"; m.photo.isNotEmpty() -> "Photo"
        m.poll != null -> "Poll: ${m.poll.q}"; else -> m.text.take(60) }

    /** Seals one reaction or edit once and keeps it until their receipt names its envelope id (a day at most). */
    private fun queueMx(peerKey: String, plain: String) {
        val env = UUID.randomUUID().toString()
        mxOut[env] = peerKey to System.currentTimeMillis()
        tries[env] = Tries(0, 0)
        wakeRetry()
        saveMx()
        bg {
            outbox.getOrMake(env) { listOf(envelope("mx", peerKey, plain, env).toString()) }
            attemptMx(env)
        }
    }

    private fun attemptMx(env: String) {
        val (k, _) = mxOut[env] ?: return
        val t = tries.getOrPut(env) { Tries(0, 0) }
        t.count++
        schedule(t)
        val body = outbox.get(env) ?: run { mxOut.remove(env); saveMx(); return }
        spread(k, body, false, t.count, contacts.get(k)?.mailbox ?: _met.value[k]?.mailbox, publishNow(t), heldBy(t))
    }

    private fun saveMx() {
        try { vault.write(mxStore, JSONObject().apply { mxOut.forEach { (e, v) -> put(e, JSONObject().put("k", v.first).put("t", v.second)) } }.toString()) } catch (_: Exception) {}
    }

    /** Typing in the chat with [peerKey]: "on" at most every 3 s while the text changes, "off" once it's cleared. */
    fun typingTo(peerKey: String, on: Boolean) {
        if (!hasM1(peerKey) || peerKey in id.muted()) return
        val now = System.currentTimeMillis()
        val last = typingSent[peerKey] ?: 0L
        if (on && now - last < Rich.TYPING_EVERY_MS) return
        if (!on && (last == 0L || now - last > Rich.TYPING_MS)) { typingSent.remove(peerKey); return }
        if (on) typingSent[peerKey] = now else typingSent.remove(peerKey)
        sendLive(peerKey, Rich.liveMsg("typing", on, now))
    }

    /** Muninn came on screen or went away: say so to recent contacts (only with Active status on). */
    fun foreground(on: Boolean) {
        if (foreground == on) return
        foreground = on
        wakeRetry()
        if (!id.activeStatus) return
        bg { sendActive(on) }
    }

    /** Active status switched in Settings: off tells everyone once and stops; nobody's dot shows either. */
    fun setActiveStatus(on: Boolean) {
        if (id.activeStatus == on) return
        id.activeStatus = on
        if (!on) { _active.value = emptyMap(); bg { sendActive(false) } }
        else if (foreground) { wakeRetry(); bg { sendActive(true) } }
    }

    /** Cap p1: my status or custom status changed: everyone who sees my active status hears it now. */
    fun announceStatus() {
        if (!id.activeStatus) return
        bg { sendActive(foreground) }
    }

    /** Cap p1: a sent "active" says I'm away while my status is invisible (so nothing of mine shows). */
    private fun invisible() = profile?.myStatus?.value == "invisible"

    private fun sendActive(on: Boolean) {
        val now = System.currentTimeMillis()
        if (on) activeSent = now
        // Cap p1: my status and custom status ride on it; invisible goes as not active, as an app without p1 reads it.
        val plain = profile?.let { Rich.activeMsg(on, now, it.myStatus.value, it.myCustomStatus.value) } ?: Rich.liveMsg("active", on, now)
        val muted = id.muted()
        // Contacts with m1, the ones chatted with most lately first, at most 20 (each is a relay event).
        val recentFirst = contacts.all.value.filter { !it.groupOnly && !it.hideActive && Rich.CAP in it.caps && it.chatKey !in muted && !trash.has(it.chatKey) }
            .sortedByDescending { _private.value[it.chatKey]?.lastOrNull()?.time ?: 0L }.take(20)
        recentFirst.forEach { c -> sendLive(c.chatKey, plain) }
    }

    /**
     * Cap p1: my profile picture and colour to one contact or group peer, sealed through the ratchet, once on every
     * route (the relays hold it for them). True when a route took it. Never to anyone blocked or in the trash.
     */
    fun sendProfile(peerKey: String, plain: String): Boolean {
        if (Move.locked) return false
        val c = contacts.get(peerKey) ?: return false
        if (peerKey in id.muted() || trash.has(peerKey)) return false
        if (c.groupOnly && !groups.sharesGroup(peerKey)) return false
        return deliverVia(peerKey, envelope("profile", peerKey, plain, UUID.randomUUID().toString()), c.mailbox).isNotEmpty()
    }

    /** Typing and active status: sealed with v1 (never the ratchet), sent once on every route, never retried. */
    private fun sendLive(peerKey: String, plain: String) = bg {
        val body = envelope("live", peerKey, plain, UUID.randomUUID().toString()).toString()
        spread(peerKey, listOf(body), false, 1, contacts.get(peerKey)?.mailbox)
    }

    /** Replying from a notification: a chat ([key] a contact) or a group ("g:<id>", into the channel of its last message). */
    fun replyFromNotification(key: String, text: String) {
        val g = if (key.startsWith("g:")) key.removePrefix("g:") else null
        if (g != null) {
            val last = groups.messages.value[g]?.lastOrNull { !it.mine }
            groups.markRead(g)
            sendGroupText(g, last?.ch ?: Proto06.MAIN, text)
        } else {
            markRead(key)
            sendPrivate(key, text)
        }
    }

    // ------------------------------------------------------------------ getting it there
    // Every message goes out on every route at once (close by, Wi-Fi and the internet); the other
    // phone keeps the first copy and drops the rest. Until a "delivered" receipt comes back it is
    // sent again after 5 s, then less often, for a day. So someone walking out of range still gets it.

    /**
     * Tries so far and when the next is due. [heldAt]: when relays first held it (0: not yet); after that it goes
     * to them again only at 1 hour and 6 hours ([NetLogic]). [publishedAt]: when it last went to the relays.
     */
    private class Tries(@Volatile var count: Int, @Volatile var next: Long) {
        @Volatile var heldAt = 0L
        @Volatile var publishedAt = 0L
    }

    /** After a try: when the next one is due. */
    private fun schedule(t: Tries) { t.next = NetLogic.next(t.count, System.currentTimeMillis(), t.heldAt) }

    /** Relays hold it now: from here on it waits there, sent to them again only at 1 hour and 6 hours. */
    private fun heldBy(t: Tries): () -> Unit = {
        if (t.heldAt == 0L) { t.heldAt = System.currentTimeMillis(); schedule(t) }
    }

    /** Whether this try also goes to the relays (always until they hold it, see [NetLogic.publish]). */
    private fun publishNow(t: Tries): Boolean {
        val now = System.currentTimeMillis()
        if (!NetLogic.publish(t.heldAt, t.publishedAt, now)) return false
        t.publishedAt = now
        return true
    }

    /** Their internet mailbox, if this message may use it: contacts always; someone met close by
     *  only for messages sent while they were in range (a backup as they walk away). */
    private fun backupMailbox(m: Msg): String? =
        contact(m.peerKey)?.mailbox ?: _met.value[m.peerKey]?.mailbox?.takeIf { m.via == "close" || m.via == "wifi" }

    private fun attempt(m: Msg) {
        // Moving to another device: nothing more is sealed here (the ratchets went with the move).
        if (Move.locked) return
        val t = tries.getOrPut(m.id) { Tries(0, 0) }
        t.count++
        schedule(t)
        if (m.photo.isNotEmpty()) { attemptPhoto(m, t); return }
        // Sealed once; every try and every route sends these same bytes.
        val body = outbox.fresh(m.id, stale(m.peerKey)) {
            // A reply, poll or link preview goes as "rich" to an app that has m1; the same text without them to any other.
            val rich = (m.re.isNotEmpty() || m.poll != null || m.preview != null) && hasM1(m.peerKey)
            listOf(envelope(if (rich) "rich" else "private", m.peerKey, if (rich) richPayload(m) else m.text, m.id).toString())
        }?.firstOrNull() ?: return
        var any = false
        // Cap s1: only a route that proved it's them counts as sent; one that can't (no s1) is just extra.
        val k = m.peerKey
        closeTo(k)?.let { close.send(it, JSONObject(body).put("t", "msg")); if (close.proven(k)) { any = true; markSent(m, "close") } }
        if (ble.send(k, body, strict(k)) && ble.proven(k)) { any = true; markSent(m, "close") }
        wifiTo(k)?.let { d ->
            if (d.proven) any = true
            bg { if (net.post(d, PATH, body) == 200 && d.proven) update(m.peerKey, m.id) { if (it.unsent.isEmpty() && rank(it.state) < 2) it.copy(state = "sent", via = "wifi", failed = false) else it } }
            // "Sent" only: a 200 just means some device answered; "Delivered" comes from their sealed receipt.
        }
        // Honest ticks: "Waiting for their phone" once a relay has stored it ("Delivered" comes from their receipt).
        // Once relays hold it, it isn't sent to them again every few minutes (see NetLogic).
        val mailbox = backupMailbox(m)
        if (mailbox != null && !publishNow(t)) any = true
        else if (internet(m.peerKey, listOf(body), mailbox, stored = { markSent(m, "internet") }, held = heldBy(t))) any = true
        if (!any && t.count == 1) update(m.peerKey, m.id) { it.copy(failed = true) }
    }

    /** Like a message, but in pieces. The internet only carries it if the close-by and Wi-Fi routes
     *  haven't delivered it within 5 s (so a big photo isn't sent twice for nothing). */
    private fun attemptPhoto(m: Msg, t: Tries) {
        val parts = pieces(m) ?: return
        // Don't start again while a big photo is still on its way (about 200 ms a piece close by).
        t.next = maxOf(t.next, System.currentTimeMillis() + parts.size * 200L)
        var any = false
        val k = m.peerKey
        // Cap s1: a route that can't prove it's them is only extra, so the internet still carries it.
        closeTo(k)?.let { ep -> parts.forEach { close.send(ep, JSONObject(it.toString()).put("t", "msg")) }; if (close.proven(k)) { any = true; markSent(m, "close") } }
        // Bluetooth LE is slow (about 22 KB a piece): give it time before trying again.
        if (ble.has(k, strict(k)) && parts.all { ble.send(k, it.toString(), strict(k)) } && ble.proven(k)) {
            any = true; markSent(m, "close"); t.next = maxOf(t.next, System.currentTimeMillis() + parts.size * 3000L)
        }
        wifiTo(k)?.let { d ->
            if (d.proven) any = true
            bg { if (parts.all { net.post(d, PATH, it.toString()) == 200 } && d.proven) update(m.peerKey, m.id) { if (it.unsent.isEmpty() && rank(it.state) < 2) it.copy(state = "sent", via = "wifi", failed = false) else it } }
        }
        val mailbox = backupMailbox(m)
        if ((!any || t.count >= 2) && parts.size <= Photos.INTERNET_PIECES && mailbox != null) {
            if (!publishNow(t)) any = true
            else if (internet(m.peerKey, parts, mailbox, stored = { markSent(m, "internet") }, held = heldBy(t))) any = true
        }
        if (!any && t.count == 1) update(m.peerKey, m.id) { it.copy(failed = true) }
    }

    private fun richPayload(m: Msg): String = JSONObject(Rich.richText(m.text, m.re)).apply {
        m.poll?.let { put("poll", More.pollJson(it)) }
        m.preview?.let { put("lp", More.previewJson(it)) }
    }.toString()

    private fun markSent(m: Msg, how: String) = update(m.peerKey, m.id) { if (it.unsent.isEmpty() && rank(it.state) < 2) it.copy(state = "sent", via = if (it.via.isEmpty()) how else it.via, failed = false) else it }

    /**
     * Runs every second while anything waits ([retryBusy]): anything of mine not yet delivered (and under a day old)
     * is tried again. Only what's [pending] is looked at, never every message.
     */
    private fun retryLoop() {
        val now = System.currentTimeMillis()
        nearbyNow()
        groups.due().forEach { o -> bg { sendGroupOut(o) } }
        incoming.entries.removeIf { now - it.value.started > 15 * 60_000 }   // half a photo that never finished
        // Cap m1: reactions and edits still on their way, typing and active status that ran out.
        for ((env, v) in mxOut) {
            if (now - v.second > DAY) { mxOut.remove(env); outbox.drop(env); tries.remove(env); saveMx(); continue }
            val t = tries[env]
            if (t == null) { tries[env] = Tries(1, now + RETRY[0]); continue }
            if (now >= t.next) { t.next = NetLogic.NEVER; bg { attemptMx(env) } }
        }
        if (_typing.value.values.any { it < now }) _typing.update { m -> m.filterValues { it >= now } }
        if (_active.value.values.any { it < now }) _active.update { m -> m.filterValues { it >= now } }
        if (foreground && id.activeStatus && !invisible() && now - activeSent > Rich.ACTIVE_EVERY_MS) { activeSent = now; bg { sendActive(true) } }
        val chats = _private.value
        for ((msgId, k) in pending) {
            val m = chats[k]?.firstOrNull { it.id == msgId }
            // Deleted, moved to a new key, or the chat is gone: nothing more to send.
            if (m == null) { pending.remove(msgId); if (tries.remove(msgId) != null) { outbox.drop(msgId); outbox.drop(UNSEND + msgId) }; continue }
            if (m.mine && m.state == "unsending") {
                val t = tries.getOrPut(m.id) { Tries(1, now + RETRY[0]) }
                // About a day of tries; after that stop (their app may not answer unsends).
                if (t.count > 300) { update(m.peerKey, m.id) { it.copy(state = "unsent") }; tries.remove(m.id); outbox.drop(UNSEND + m.id); pending.remove(m.id); continue }
                if (now >= t.next) { t.next = NetLogic.NEVER; bg { attemptUnsend(m) } }
                continue
            }
            // State "": sent without receipts, leave them be.
            if (!m.mine || m.state.isEmpty() || m.unsent.isNotEmpty() || rank(m.state) >= 3 || now - m.time > DAY) {
                // Done with it (delivered, or a day of trying): its sealed copy goes too.
                if (tries.remove(m.id) != null) outbox.drop(m.id)
                pending.remove(m.id)
                continue
            }
            val t = tries[m.id]
            if (t == null) { tries[m.id] = Tries(1, now + RETRY[0]); continue }   // after a restart: wait one round
            if (now >= t.next) { t.next = NetLogic.NEVER; bg { attempt(m) } }
        }
    }

    /**
     * The refresh button (and catching up): everything still waiting goes again on the next round, not when its
     * timer says. Not what relays already hold: it waits there for their phone.
     */
    fun retryAllNow() { for (t in tries.values) if (t.heldAt == 0L) t.next = 0L }

    /** Someone just showed up close by or on the same Wi-Fi: what's still on its way to them goes now. */
    private fun nearbyNow() {
        val now = (close.list.value.mapNotNull { p -> p.chatKey?.takeIf { p.connected && p.proven } } +
            ble.peers.value.mapNotNull { p -> p.chatKey?.takeIf { p.proven } } + net.devices.value.mapNotNull { d -> d.chatKey?.takeIf { d.proven } }).toSet()
        val came = now - near
        near = now
        if (came.isEmpty()) return
        for ((k, list) in _private.value) if (k in came) for (m in list) if (m.mine) tries[m.id]?.next = 0L
        for ((env, v) in mxOut) if (v.first in came) tries[env]?.next = 0L
        came.forEach { groups.nudge(it) }
    }

        /** Tap a message that didn't send: try again now. */
    fun retry(peerKey: String, msgId: String) {
        val m = _private.value[peerKey]?.firstOrNull { it.id == msgId } ?: return
        tries.remove(m.id)
        update(peerKey, msgId) { it.copy(failed = false, state = "sending", time = System.currentTimeMillis()) }
        bg { attempt(m) }
    }

    /** Every route, like a message (protocol 0). The internet needs a mailbox: contacts, or a "keep in touch" answer. */
    private fun deliver(peerKey: String, body: JSONObject, mailbox: String?): Boolean = deliverVia(peerKey, body, mailbox).isNotEmpty()

    /**
     * Sends on every route at once and always to the internet too, so nobody on the Wi-Fi or close by can swallow it.
     * Returns the best route that took it ("close", "wifi", "internet"), or "" if none could. A route that didn't prove
     * it's them (cap s1) never counts on its own.
     */
    private fun deliverVia(peerKey: String, body: JSONObject, mailbox: String?): String {
        if (Move.locked) return ""
        val json = body.toString()
        var how = ""
        closeTo(peerKey)?.let { close.send(it, JSONObject(json).put("t", "msg")); if (close.proven(peerKey)) how = "close" }
        if (ble.send(peerKey, json, strict(peerKey)) && ble.proven(peerKey)) how = "close"
        val online = internet(peerKey, listOf(json), mailbox)
        wifiTo(peerKey)?.let { if (net.post(it, PATH, json) == 200 && it.proven && how.isEmpty()) how = "wifi" }
        return how.ifEmpty { if (online) "internet" else "" }
    }

    /** Cap s1: someone whose app proves its routes. A route that can't prove it's them is never used for them. */
    private fun strict(k: String) = contacts.get(k)?.caps?.contains(Proof.CAP) == true || k in s1Said ||
        s1Bound.containsKey(k) || _met.value[k]?.caps?.contains(Proof.CAP) == true

    private fun otherS1(o: JSONObject, fromKey: String) {
        if (s1Said.size > 2000) s1Said.clear()
        if (Proof.CAP in ChatWire.capsOf(o)) s1Said.add(fromKey)
        if (Proof.isBound(o) && s1Bound.size < 2000 && s1Bound.putIfAbsent(fromKey, System.currentTimeMillis()) == null)
            try { vault.write(s1Store, JSONObject().apply { s1Bound.forEach { (k, t) -> put(k, t) } }.toString()) } catch (_: Exception) {}
    }
    /** A cached copy sealed unbound (or without the ratchet) for an app that speaks s1 (or the ratchet): made again. */
    private fun stale(k: String): (String) -> Boolean = { body ->
        try {
            val o = JSONObject(body)
            (strict(k) && !Proof.isBound(o)) || (!ChatWire.isRatchet(o) && (ChatWire.useRatchet(o.optString("room"), contacts.get(k)) ||
                RingWire.fileRatchet(o.optString("room"), contacts.get(k))))
        } catch (e: Exception) { false }
    }

    /** Their Nearby endpoint: one that proved it's them first; one that couldn't (no s1) only as an extra. */
    private fun closeTo(k: String) = close.endpointFor(k, strict(k))
    /** The same on the Wi-Fi. */
    private fun wifiTo(k: String) = net.deviceFor(k, strict(k))

    /** The internet: Nostr, their mailbox, which waits for them while they're offline. False without a mailbox. */
    private fun internet(peerKey: String, bodies: List<String>, mailbox: String?, stored: (() -> Unit)? = null, held: (() -> Unit)? = null, days: Int = 0): Boolean {
        if (mailbox == null || Move.locked) return false
        // Relays may drop it after the longest message limit (Nostr.expiration): it's gone from the phones by then.
        val keep = if (days > 0) days else try { chatDays(peerKey) } catch (_: Exception) { Limits.LONGEST }
        // [stored]: once a relay has said OK to every one of them. [held]: once enough relays have (NetLogic.HELD_RELAYS).
        val left = java.util.concurrent.atomic.AtomicInteger(bodies.size)
        val ok: (() -> Unit)? = stored?.let { done -> { if (left.decrementAndGet() == 0) done() } }
        val heldLeft = java.util.concurrent.atomic.AtomicInteger(bodies.size)
        val all: (() -> Unit)? = held?.let { done -> { if (heldLeft.decrementAndGet() == 0) done() } }
        bodies.forEach { nostr.publish(mailbox, wrap(peerKey, it), ok, all, keep) }
        return true
    }

    /** Changes one message; false (and nothing saved) when that changed nothing. */
    private fun update(peer: String, msgId: String, f: (Msg) -> Msg): Boolean = edit { map ->
        val before = map[peer] ?: return@edit false
        var changed = false
        var again = false
        val after = before.map { if (it.id == msgId) f(it).also { n -> if (n != it) changed = true; again = n.mine && (n.state == "sending" || n.state == "unsending") } else it }
        if (changed) map[peer] = after
        if (changed && again) queue(peer, msgId)
        changed
    }

    /** Every change to the chats goes through here: one at a time, then saved. Returns whether it changed. */
    private fun edit(f: (MutableMap<String, List<Msg>>) -> Boolean): Boolean = synchronized(lock) {
        val map = _private.value.toMutableMap()
        if (!f(map)) return@synchronized false
        _private.value = map
        save()
        true
    }

    private fun envelope(room: String, theirKey: String, text: String, msgId: String): JSONObject {
        val o = ChatWire.base(room, id.publicKey, id.username, id.fingerprint, nostr.publicKey, msgId)
            // The invite from their code, until they accept: proves they handed it out within the hour.
            .apply { contacts.get(theirKey)?.invite?.let { put("invite", it) } }
        return seal(room, theirKey, listOf(o to text.toByteArray(Charsets.UTF_8))).first()
    }

    /**
     * Seals each (envelope, plaintext) for [theirKey]: through the message ratchet when they're a contact
     * whose app has it and the room allows (never Nearby), else v1. The ratchet is saved before any of it
     * can leave, so a message key is never used twice.
     */
    private fun seal(room: String, theirKey: String, items: List<Pair<JSONObject, ByteArray>>): List<JSONObject> {
        val sk = sharedKey(theirKey)
        // Cap d1: how long I keep our chat, next to "caps" (only to an app that reads it).
        if (hasD1(theirKey)) myDays(theirKey).let { d -> items.forEach { it.first.put("days", d) } }
        // Cap s1: the clear fields are sealed in too, for an app that reads them.
        val bind = strict(theirKey)
        // Cap e1: room "file" goes through the ratchet too, for an app that says e1.
        if (!ChatWire.useRatchet(room, contacts.get(theirKey)) && !RingWire.fileRatchet(room, contacts.get(theirKey)))
            return items.map { (o, plain) -> ChatWire.seal(o, plain, sk, theirKey, null, ChatCrypto::newKeyPair, bind) }
        synchronized(ratchets.lock(theirKey)) {
            val peer = ratchets.get(theirKey)
            try {
                val out = items.map { (o, plain) -> ChatWire.seal(o, plain, sk, theirKey, peer, ChatCrypto::newKeyPair, bind) }
                ratchets.save(theirKey, peer)
                return out
            } catch (e: Exception) {
                ratchets.forget(theirKey)
                throw e
            }
        }
    }

    // ------------------------------------------------------------------ groups (protocol 0.6, section 3)

    private inner class GroupHost : Groups.Host {
        override val myName get() = id.username
        override val myMailbox get() = nostr.publicKey
        override val readReceipts get() = id.readReceipts
        override fun send(o: Groups.Out) { wakeRetry(); bg { sendGroupOut(o) } }
        override fun drop(env: String) = outbox.drop(env)
        override fun isContact(k: String) = this@Chat.isContact(k)
        override fun peer(k: String, name: String, mailbox: String) {
            contacts.peer(k, name, mailbox)
            // A contact in a group's state runs an app with groups and calls (3.7): what it speaks is known from that too.
            contacts.contact(k)?.let { c -> if (!c.caps.containsAll(Permissions.GROUP_CAPS)) contacts.set(c.copy(caps = c.caps + Permissions.GROUP_CAPS)) }
        }
        override fun notice(text: String) { notices.tryEmit(text) }
        // Same flow as private messages, keyed "g:<group id>": the notification opens the group.
        override fun arrived(g: String, title: String, text: String) { arrivals.tryEmit(Triple(title, text, Folders.groupKey(g))) }
        override fun deleteBlob(id: String) { File(photosDir, id).delete() }
        override fun hasM1(k: String) = this@Chat.hasM1(k)
        override fun hasM2(k: String) = this@Chat.hasM2(k)
        override fun hasD1(k: String) = this@Chat.hasD1(k) || envCaps[k]?.contains(Limits.CAP) == true
        override fun hasJ1(k: String) = Proto06.J1 in capsOf(k) || envCaps[k]?.contains(Proto06.J1) == true
    }

    /** Invites a contact to group [g]. Null when sent, else what to tell the person. */
    fun inviteToGroup(g: String, peerKey: String): String? {
        if (!canGroup(peerKey)) return "${nameOf(peerKey)} needs Muninn or Huginn 0.6 to join groups."
        return groups.invite(g, peerKey)
    }

    /** A link gets its preview first (cap m2), made on this phone only when Settings › Privacy allows it. */
    fun sendGroupText(g: String, ch: String, text: String, re: String = ""): Boolean {
        val t = ChatWire.clip(text.trim(), MAX)
        sends.tryEmit(Folders.groupKey(g))
        val link = if (id.linkPreviews) More.firstLink(t) else null
        if (link == null) return groups.sendText(g, ch, t, re)
        if (t.isEmpty()) return false
        bg { groups.sendText(g, ch, t, re, preview = LinkPreview.fetch(link)) }
        return true
    }

    /** A poll in group [g] (cap m2; members without m2 see its text). Null when sent, else what to say. */
    fun sendGroupPoll(g: String, ch: String, question: String, options: List<String>, multi: Boolean): String? {
        val poll = More.Poll(question.trim(), options.map { it.trim() }.filter { it.isNotEmpty() }, multi)
        if (!More.ok("rich", JSONObject().put("text", More.fallback(poll.q, poll.options)).put("poll", More.pollJson(poll))))
            return "A poll needs a question and 2 to ${More.OPTIONS_MAX} different answers."
        return if (groups.sendText(g, ch, More.fallback(poll.q, poll.options), poll = poll)) null else "That poll didn't send."
    }

    fun groupVote(g: String, msgId: String, choices: List<Int>) = groups.vote(g, msgId, choices)

    /**
     * Messenger style: a new group with [keys] (contacts) in it, named after them. Contacts whose app has m1 go
     * straight in; others with groups (g1) get an invite; anyone else is left out. Returns the group (null if
     * none could be made) and what to tell the person.
     */
    fun startGroup(keys: List<String>): Pair<String?, String> {
        val people = keys.distinct().mapNotNull { contacts.contact(it) }
        if (people.isEmpty()) return null to "Pick at least one contact."
        // Cut to the 40 characters a group name may have (by code points, in Groups.create).
        val name = (listOf(id.username) + people.map { it.shown }).joinToString(", ")
        val g = groups.create(name) ?: return null to "That group couldn't be made."
        var invited = 0
        val left = mutableListOf<String>()
        for (c in people) {
            when {
                Rich.CAP in c.caps -> if (groups.addDirect(g, c.chatKey, c.name, c.mailbox ?: "") != null) left += c.shown
                "g1" in c.caps -> if (groups.invite(g, c.chatKey) == null) invited++ else left += c.shown
                else -> { probeCaps(c.chatKey); left += c.shown }
            }
        }
        val parts = buildList {
            if (invited > 0) add("$invited invited (they join once they accept)")
            if (left.isNotEmpty()) add("${left.joinToString(", ")} need${if (left.size == 1) "s" else ""} a newer Muninn or Huginn")
        }
        return g to (if (parts.isEmpty()) "Group made. Rename it any time in its settings." else parts.joinToString(". ") + ".")
    }

    fun sendGroupPhoto(g: String, ch: String, jpeg: ByteArray) = sendGroupBlob(g, ch, jpeg, 0)
    fun sendGroupVoice(g: String, ch: String, aac: ByteArray, ms: Int) = sendGroupBlob(g, ch, aac, ms.coerceAtLeast(1))

    /** Several photos at once: albums of up to 10. */
    fun sendGroupPhotos(g: String, ch: String, jpegs: List<ByteArray>) {
        if (jpegs.size == 1) { sendGroupPhoto(g, ch, jpegs[0]); return }
        jpegs.chunked(More.ALBUM_MAX).forEach { part ->
            val album = UUID.randomUUID().toString()
            part.forEachIndexed { i, j -> sendGroupBlob(g, ch, j, 0, album = Triple(album, i, part.size)) }
        }
    }

    /** A video message to everyone in [g] whose app plays it (cap m2). Null when sent, else what to say. */
    fun sendGroupVideo(g: String, ch: String, mp4: ByteArray, ms: Int): String? {
        if (!More.isVideo(mp4) || mp4.size > Photos.MAX_PIECES * Photos.PIECE) return "That video is too big to send."
        val s = groups.group(g)?.state ?: return "That group isn't here any more."
        val older = s.members.filter { it.k != id.publicKey && !hasM2(it.k) }
        if (!sendGroupBlob(g, ch, mp4, 0, videoMs = ms.coerceIn(1, More.VIDEO_MAX_MS))) return "That video didn't send."
        return if (older.isEmpty()) null else "${older.size} ${if (older.size == 1) "person needs" else "people need"} a newer Muninn to see video messages."
    }

    private fun sendGroupBlob(g: String, ch: String, bytes: ByteArray, voiceMs: Int, videoMs: Int = 0, album: Triple<String, Int, Int>? = null): Boolean {
        val blob = "g-" + UUID.randomUUID().toString()
        vault.writeBytes(File(photosDir, blob), bytes)
        val ok = groups.sendBlob(g, ch, blob, voiceMs, videoMs, album)
        if (!ok) File(photosDir, blob).delete() else sends.tryEmit(Folders.groupKey(g))
        return ok
    }

    /** Leaves the group or, once out of it, deletes it here; its folder entries go too. */
    fun deleteGroup(g: String): Boolean = groups.delete(g).also { if (it) folders.forget(Folders.groupKey(g)) }

    /** Seals one group envelope (or a photo's pieces) for its member and sends it on every route. */
    private fun sendGroupOut(o: Groups.Out) {
        if (Move.locked) return
        val c = contacts.get(o.to) ?: return
        // An app without g1 answers 400 to group rooms: only ever to someone whose app said g1 (group peers always can).
        if (!c.groupOnly && "g1" !in c.caps) { probeCaps(o.to); return }
        val bodies = (if (!o.retry) listOf(envelope(o.room, o.to, o.plain, o.env).toString())
            else outbox.fresh(o.env, stale(o.to)) { if (o.room == "gphoto") groupPieces(o) else listOf(envelope(o.room, o.to, o.plain, o.env).toString()) }) ?: return
        spread(o.to, bodies, o.room == "gphoto", o.tries, c.mailbox, o.net, if (o.retry) ({ groups.relaysHold(o.env) }) else null, groupDays(o.g))
    }

    /** A group photo or voice message: the usual photo pieces plus g, ch and gid in the clear (3.9). */
    private fun groupPieces(o: Groups.Out): List<String>? {
        val bytes = photo(o.photo) ?: return null
        val parts = Photos.split(bytes)
        return seal("gphoto", o.to, parts.mapIndexed { i, part ->
            ChatWire.base("gphoto", id.publicKey, id.username, id.fingerprint, nostr.publicKey, o.env).put("i", i).put("n", parts.size)
                .put("g", o.g).put("ch", o.ch).put("gid", o.id)
                .apply {
                    if (o.voice > 0) put("kind", "voice").put("ms", o.voice)
                    if (o.video > 0) put("kind", "video").put("ms", o.video)
                    if (o.album.isNotEmpty()) put("al", o.album).put("ai", o.albumAt).put("an", o.albumOf)
                } to part
        }).map { it.toString() }
    }

    /**
     * Every route at once. A photo goes over the internet only when nothing close by took it (or on a later try).
     * [publish]: false while relays already hold it (it isn't sent to them again yet); [held] runs once they do.
     */
    private fun spread(k: String, bodies: List<String>, big: Boolean, tries: Int, mailbox: String?, publish: Boolean = true, held: (() -> Unit)? = null, days: Int = 0) {
        if (Move.locked) return
        var local = false
        // Cap s1: only a route that proved it's them keeps a big photo off the internet.
        closeTo(k)?.let { ep -> bodies.forEach { close.send(ep, JSONObject(it).put("t", "msg")) }; if (close.proven(k)) local = true }
        if (ble.has(k, strict(k)) && bodies.all { ble.send(k, it, strict(k)) } && ble.proven(k)) local = true
        wifiTo(k)?.let { d -> if (d.proven) local = true; bg { bodies.forEach { net.post(d, PATH, it) } } }
        if (publish && (!big || ((!local || tries >= 2) && bodies.size <= Photos.INTERNET_PIECES))) internet(k, bodies, mailbox, held = held, days = days)
    }

    private fun receiveGroup(o: JSONObject, room: String, fromKey: String, name: String, mailbox: String?): Pair<Int, String> {
        val sentId = o.optString("id")
        val dup = { duplicate(room, fromKey, sentId, mailbox) }
        dup()?.let { return it }
        // Who may send what (contacts, members, a join with a live token) is decided by Groups once it's open.
        return opened(o, fromKey, dup) { plain ->
            val p = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (e: Exception) { return@opened 400 to "{}" }
            if (!groupRate(room, fromKey)) return@opened 429 to "{}"
            // Someone joining by code isn't a contact yet: what their app speaks decides if they may join a Family or Work group.
            if (contacts.get(fromKey) == null) envCaps[fromKey] = ChatWire.capsOf(o)
            val r = groups.receive(room, fromKey, name, mailbox, p)
            if (r == 200 && room in ChatWire.SEEN_ROOMS && sentId.isNotEmpty()) seen.add("$fromKey|$sentId", if (room == "group") p.optString("g") else "")
            r to "{}"
        }
    }

    /** gmsg: 5 in 10 s per sender, like private messages. group: 20 in 10 s. greceipt: not limited (2.4). */
    private fun groupRate(room: String, k: String): Boolean {
        val (map, max) = when (room) { "gmsg" -> recent to 5; "group", "tvconfig", "mx", "intro" -> groupTimes to 20; "call", "file" -> callTimes to 30; "live" -> liveTimes to 30
            "profile" -> profileTimes to 1; else -> return true }
        val now = System.currentTimeMillis()
        if (map.size > 500) map.clear()
        val q = map.getOrPut(k) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 10_000) q.removeFirst()
            if (q.size >= max) return false
            q.addLast(now)
        }
        return true
    }

    private fun receiveGroupPiece(o: JSONObject, fromKey: String, name: String): Pair<Int, String> {
        val g = o.optString("g"); val ch = o.optString("ch"); val gid = o.optString("gid"); val env = o.optString("id")
        val n = o.optInt("n"); val i = o.optInt("i", -1)
        if (!Proto06.isLogicalId(gid) || env != Proto06.envelopeId(gid, id.publicKey) || n < 1 || n > Photos.MAX_PIECES || i !in 0 until n) return 400 to "{}"
        if (!More.ok("piece", o)) return 400 to "{}"
        if (!groups.isMember(g, fromKey)) return 403 to "{}"
        val key = "g|$fromKey|$gid"
        val dup = {
            if (groups.knownEnvelope(env) != null) { groups.ack(fromKey, g, listOf(gid), "delivered"); 200 to "{}" }
            else if (incoming[key]?.let { it.parts.size == n && it.parts[i] != null } == true) 200 to "{}"
            else null
        }
        dup()?.let { return it }
        val now = System.currentTimeMillis()
        val q = pieceTimes.getOrPut(fromKey) { ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= 1000) return 429 to "{}"
            q.addLast(now)
        }
        if (incoming.size > 20 && !incoming.containsKey(key)) return 429 to "{}"
        return opened(o, fromKey, dup) { part ->
            val inc = incoming.getOrPut(key) { Incoming(fromKey, arrayOfNulls(n), System.currentTimeMillis()) }
            wakeRetry()
            if (inc.from != fromKey || inc.parts.size != n) return@opened 400 to "{}"
            synchronized(inc) {
                inc.parts[i] = part
                if (inc.parts.any { it == null }) return@opened 200 to "{}"
                incoming.remove(key)
            }
            val voiceMs = if (o.optString("kind") == "voice") o.optInt("ms").coerceIn(1, Voice.MAX_MS) else 0
            val videoMs = if (o.optString("kind") == "video") o.optInt("ms").coerceIn(1, More.VIDEO_MAX_MS) else 0
            val joined = inc.parts.map { it!! }
            val bytes = (when { voiceMs > 0 -> Voice.check(joined); videoMs > 0 -> Videos.join(joined); else -> Photos.join(joined) }) ?: return@opened 400 to "{}"
            val blob = "g-$gid"
            val al = o.optString("al")
            val album = if (al.isEmpty()) null else Triple(al, o.optInt("ai"), o.optInt("an"))
            if (groups.knownEnvelope(env) == null) {
                vault.writeBytes(File(photosDir, blob), bytes)
                if (!groups.receivedBlob(fromKey, name, g, ch, gid, blob, voiceMs, videoMs, album) && groups.knownEnvelope(env) == null) File(photosDir, blob).delete()
            }
            200 to "{}"
        }
    }

    // ------------------------------------------------------------------ keeping

    fun mute(peerKey: String, on: Boolean) {
        id.setMuted(peerKey, on)
        if (on) synchronized(lock) { _nearby.update { l -> l.filterNot { it.peerKey == peerKey && !it.mine } } }
    }

    fun clearNearby() = synchronized(lock) { _nearby.value = emptyList(); nearbyStore.delete() }

    fun deleteConversation(peerKey: String) {
        edit { map ->
            val gone = map.remove(peerKey) ?: return@edit false
            forget(gone.map { it.id })
            gone.forEach { outbox.drop(it.id); outbox.drop(UNSEND + it.id) }
            gone.forEach { if (it.photo.isNotEmpty()) File(photosDir, it.photo).delete() }
            true
        }
    }

    /**
     * Message limits (cap d1, shared/docs/protocol-0.6.md section 16): every chat loses messages older than its limit, with
     * their photos, voice, videos, files, votes and previews; "Delete messages after: Day" only makes it sooner.
     * Runs every minute, when a chat opens and at start. Late copies of what went stay gone (the deleted list).
     */
    fun prune() {
        val now = System.currentTimeMillis()
        val sooner = id.deleteAfter.takeIf { it in 1 until Limits.DEFAULT * DAY }
        fun cutoff(days: Int) = maxOf(Limits.cutoff(now, days), if (sooner != null) now - sooner else 0L)
        groups.pruneOlder(::cutoff)
        // The call history goes by the same limit as each chat's messages.
        io.github.ravenor907.muninn.net.call.CallManager.pruneOlder { k -> cutoff(chatDays(k)) }
        val near = cutoff(Limits.DEFAULT)
        synchronized(lock) {
            val n = _nearby.value.filter { it.time >= near }
            if (n.size != _nearby.value.size) { _nearby.value = n; if (id.keepNearbyHistory) saveNearby() }
        }
        val days = _private.value.keys.associateWith { chatDays(it) }
        if (_private.value.none { (k, l) -> l.any { it.time < cutoff(days[k] ?: Limits.DEFAULT) } }) return
        val gone = ArrayList<String>()
        edit { map ->
            var changed = false
            for (k in map.keys.toList()) {
                val (old, keep) = map[k]!!.partition { it.time < cutoff(days[k] ?: Limits.DEFAULT) }
                if (old.isEmpty()) continue
                old.forEach { if (it.photo.isNotEmpty()) File(photosDir, it.photo).delete(); gone += it.id }
                map[k] = keep; changed = true
            }
            changed
        }
        if (gone.isNotEmpty()) { forget(gone); gone.forEach { outbox.drop(it); outbox.drop(UNSEND + it); tries.remove(it) } }
    }

    // ------------------------------------------------------------------ message limits (cap d1, section 16)

    /** A chat was opened: anything past its limit goes now, off the main thread. */
    fun pruneSoon() = bg { prune() }

    /** Someone joining a group by code, not a contact yet: the caps their envelope said. */
    private val envCaps = ConcurrentHashMap<String, Set<String>>()

    fun hasD1(peerKey: String) = contacts.get(peerKey)?.caps?.contains(Limits.CAP) == true

    /** In my Family or Work folder: I may keep our chat longer than 7 days. */
    fun isClose(peerKey: String) = folders.of(peerKey).any { it.name in Limits.CLOSE_FOLDERS }

    /** How long I keep our chat (what my envelopes tell them). */
    fun myDays(peerKey: String) = Limits.myDays(contacts.get(peerKey)?.keep?.takeIf { it > 0 }, isClose(peerKey))

    /** How long a 1:1 chat keeps messages on this phone: the shorter of their limit and mine. */
    fun chatDays(peerKey: String) = Limits.pairDays(myDays(peerKey), hasD1(peerKey), contacts.get(peerKey)?.days?.takeIf { it > 0 })

    fun groupDays(g: String) = groups.group(g)?.let { Limits.groupDays(it.state) } ?: Limits.DEFAULT

    /** My choice for a Family or Work contact (7 to 30). They learn it at once from an empty receipt. */
    fun setKeep(peerKey: String, days: Int) {
        val c = contacts.get(peerKey) ?: return
        contacts.set(c.copy(keep = days.coerceIn(Limits.DEFAULT, Limits.LONGEST)))
        bg { prune() }
        if (hasD1(peerKey)) bg {
            val r = JSONObject().put("ids", JSONArray()).put("s", "delivered").toString()
            deliver(peerKey, envelope("receipt", peerKey, r, UUID.randomUUID().toString()), c.mailbox)
        }
    }

    /** Pictures and files made for sending or opening (camera shots, files on their way): gone after 7 days. */
    @Volatile private var cacheSwept = 0L
    private fun sweepCache() {
        val now = System.currentTimeMillis()
        // Once an hour is plenty (it rides on the minute's prune).
        if (now - cacheSwept < 60 * 60_000L) return
        cacheSwept = now
        Link.prune(now)
        for (d in listOf("files", "camera")) File(context.cacheDir, d).listFiles()?.forEach { if (now - it.lastModified() > Limits.DEFAULT * DAY) it.deleteRecursively() }
        // Copies of what was shared into Muninn (Share › Muninn, or picked in Files): gone after a day.
        File(context.cacheDir, "outgoing").listFiles()?.forEach { if (now - it.lastModified() > DAY) it.deleteRecursively() }
    }

    /** Erases every conversation on this phone. */
    fun deleteAll() {
        synchronized(lock) {
            _private.value = emptyMap(); _nearby.value = emptyList()
            groups.clear()
            photosDir.deleteRecursively()
            labs.clear()
            outbox.clear()
            mxOut.clear(); mxStore.delete()
            saveNow(); nearbyStore.delete()
            _unread.value = emptySet()
        }
        flags.all.value.unread.forEach { flags.read(it) }
    }

    /**
     * Kids' TV storage cap: keeps the newest [keep] messages per person, drops voice messages older
     * than [voiceBefore] (ms since the epoch), then the oldest photos and voice messages until the stored
     * ones take at most [maxBlobBytes]. Photos bigger than [shrinkOver] bytes go through [shrink] (null
     * keeps them as they are). Returns the messages removed.
     */
    fun trimHistory(keep: Int, voiceBefore: Long, maxBlobBytes: Long, shrinkOver: Int, shrink: (ByteArray) -> ByteArray?): Int {
        var removed = 0
        val gone = ArrayList<String>()
        edit { map ->
            var changed = false
            for (k in map.keys.toList()) {
                val list = map[k]!!
                val (old, rest) = list.partition { it.voice > 0 && it.time < voiceBefore }
                val kept = rest.takeLast(keep)
                val drop = old + rest.dropLast(keep)
                if (drop.isEmpty()) continue
                drop.forEach { if (it.photo.isNotEmpty()) File(photosDir, it.photo).delete(); gone += it.id }
                map[k] = kept; changed = true; removed += drop.size
            }
            // Oldest blobs first until they fit.
            val blobs = map.values.flatten().filter { it.photo.isNotEmpty() }.sortedBy { it.time }
            var total = blobs.sumOf { File(photosDir, it.photo).length() }
            for (m in blobs) {
                if (total <= maxBlobBytes) break
                total -= File(photosDir, m.photo).length()
                File(photosDir, m.photo).delete(); gone += m.id
                map[m.peerKey] = map[m.peerKey].orEmpty().filterNot { it.id == m.id }
                changed = true; removed++
            }
            changed
        }
        if (gone.isNotEmpty()) { forget(gone); gone.forEach { outbox.drop(it); outbox.drop(UNSEND + it); tries.remove(it) } }
        // Big photos made smaller, outside the lock (decoding takes a moment).
        for (m in _private.value.values.flatten()) {
            if (m.photo.isEmpty() || m.voice > 0) continue
            val f = File(photosDir, m.photo)
            if (f.length() <= shrinkOver) continue
            val bytes = try { photo(m.photo) } catch (e: Exception) { null } ?: continue
            val small = try { shrink(bytes) } catch (e: Throwable) { null } ?: continue
            if (small.size < bytes.size) try { vault.writeBytes(f, small) } catch (_: Exception) {}
        }
        return removed
    }

    private fun addNearby(m: Msg) = synchronized(lock) {
        if (_nearby.value.any { it.id == m.id }) return@synchronized
        _nearby.value = (_nearby.value + m).takeLast(300)
        if (id.keepNearbyHistory) saveNearby()
    }

    /** False if it's already there or was deleted here: copies from other routes are dropped. */
    private fun addPrivate(peer: String, m: Msg): Boolean = edit { map ->
        val list = map[peer].orEmpty()
        if (deleted.containsKey(m.id) || list.any { it.id == m.id }) return@edit false
        map[peer] = (list + m).takeLast(1000)
        true
    }.also { added ->
        if (added && m.mine && m.state.isNotEmpty()) queue(peer, m.id)
        if (added && isContact(peer)) bg { Link.onMessage(peer, nameOf(peer), m) }
    }

    private fun replace(peer: String, m: Msg) = update(peer, m.id) { m }

    private fun toJson(list: List<Msg>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("peer", it.peerKey).put("name", it.name).put("text", it.text).put("mine", it.mine)
            .put("time", it.time).put("failed", it.failed).put("via", it.via).put("state", it.state).put("photo", it.photo).put("unsent", it.unsent).put("voice", it.voice)
            .apply { if (it.re.isNotEmpty()) put("re", it.re); if (it.reactions.isNotEmpty()) put("rx", Rich.reactionsToJson(it.reactions)); if (it.edited > 0) put("ed", it.edited) }
            .apply { m2Json(this, it) }) }
    }
    private fun fromJson(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }.map {
        Msg(it.getString("id"), it.getString("peer"), it.getString("name"), it.getString("text"), it.getBoolean("mine"), it.getLong("time"),
            it.optBoolean("failed"), it.optString("via"), it.optString("state"), it.optString("photo"), it.optString("unsent"), it.optInt("voice"),
            re = it.optString("re"), reactions = Rich.reactionsFromJson(it.optJSONObject("rx")), edited = it.optLong("ed"),
            video = it.optInt("vid"), album = it.optString("al"), albumAt = it.optInt("ai"), albumOf = it.optInt("an"),
            poll = More.pollOf(it), votes = More.votesFromJson(it.optJSONObject("vt")), preview = More.previewOf(it), file = it.optString("fn"))
    }

    /** Cap m2 parts of a stored message (only the ones it has). */
    private fun m2Json(o: JSONObject, m: Msg) {
        if (m.video > 0) o.put("vid", m.video)
        if (m.album.isNotEmpty()) o.put("al", m.album).put("ai", m.albumAt).put("an", m.albumOf)
        m.poll?.let { o.put("poll", More.pollJson(it)) }
        if (m.votes.isNotEmpty()) o.put("vt", More.votesToJson(m.votes))
        m.preview?.let { o.put("lp", More.previewJson(it)) }
        if (m.file.isNotEmpty()) o.put("fn", m.file)
    }

    private fun save() {
        if (saveDue.compareAndSet(false, true)) writer.schedule({ saveDue.set(false); saveNow() }, SAVE_AFTER_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun saveNow() {
        try {
            val chats = _private.value
            val o = JSONObject(); chats.forEach { (k, v) -> o.put(k, toJson(v)) }
            synchronized(writer) { vault.write(store, o.toString()) }
        } catch (_: Exception) {}
    }
    private fun resendKeeps() {
        unsentKeeps.toList().forEach { (k, body) ->
            val mailbox = contacts.get(k)?.mailbox ?: _met.value[k]?.mailbox
            // The same sealed "yes" as the first time, not a new one.
            if (deliver(k, JSONObject(body), mailbox)) unsentKeeps.remove(k)
        }
    }

    private fun saveMet() {
        val o = JSONObject()
        _met.value.forEach { (k, m) -> o.put(k, JSONObject().put("n", m.name).put("p", m.mailbox ?: "").put("i", m.iSaid).put("t", m.theySaid).put("d", m.dismissed).put("v", m.verified)
            .apply { if (m.caps.isNotEmpty()) put("c", m.caps.sorted().joinToString(" ")) }) }
        try { vault.write(metStore, o.toString()) } catch (_: Exception) {}
    }

    private fun saveNearby() = vault.write(nearbyStore, toJson(_nearby.value).toString())

    private fun load() {
        try {
            vault.read(store)?.let { text ->
                val o = JSONObject(text)
                _private.value = o.keys().asSequence().associateWith { fromJson(o.getJSONArray(it)) }
            }
            vault.read(invitesStore)?.let { text -> val o = JSONObject(text); o.keys().forEach { invites[it] = o.optLong(it) } }
            vault.read(introsStore)?.let { text -> val a = JSONArray(text); _intros.value = (0 until a.length()).map { Trust.fromJson(a.getJSONObject(it)) } }
            vault.read(onceStore)?.let { text -> val a = JSONArray(text); for (i in 0 until a.length()) once.add(a.getString(i)) }
            vault.read(metStore)?.let { text ->
                val o = JSONObject(text)
                _met.value = o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let {
                    Met(it.optString("n"), it.optString("p").ifEmpty { null }, it.optBoolean("i"), it.optBoolean("t"), it.optBoolean("d"), it.optBoolean("v"),
                        Proto06.caps(it.optString("c"))) } }
            }
            vault.read(deletedStore)?.let { text -> val o = JSONObject(text); o.keys().forEach { deleted[it] = o.optLong(it) } }
            vault.read(mxStore)?.let { text -> val o = JSONObject(text); o.keys().forEach { e -> o.getJSONObject(e).let { mxOut[e] = it.getString("k") to it.optLong("t") } } }
            if (id.keepNearbyHistory) vault.read(nearbyStore)?.let { _nearby.value = fromJson(JSONArray(it)) }
            // Message limits (section 16): someone who already has chats is told once, on the chat list.
            val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
            if (!prefs.contains(io.github.ravenor907.muninn.screens.LIMITS_NOTE))
                prefs.edit().putBoolean(io.github.ravenor907.muninn.screens.LIMITS_NOTE, _private.value.values.any { it.isNotEmpty() } || groups.all.value.isNotEmpty()).apply()
            if (isLabs()) labs.load()
            prune()
        } catch (_: Exception) {}
    }

    companion object {
        const val PATH = "/api/huginn/v1/message"
        /** The chat page with the Labs reports (and what a report's notification opens). */
        const val LABS_PAGE = "labs"
        const val MAX = 2000
        const val KEEP_AFTER = 10
        const val INVITE_MS = 60 * 60_000L
        const val DAY = ChatWire.DAY
        /** Outbox key for a message's unsend envelope. */
        private const val UNSEND = "unsend:"
        /** Wait before each new try: 5 s, 10 s, 20 s, 1 min, then every 5 min (until relays hold it, see NetLogic). */
        val RETRY = NetLogic.RETRY
        /** A change to the chats is written this long after it (more changes meanwhile share the write). */
        private const val SAVE_AFTER_MS = 500L
        private val GROUP_ROOMS = setOf("group", "gmsg", "greceipt")
        /** Someone who isn't a contact, in lists and notifications: the name they chose, marked so it never passes for a contact's. */
        fun stranger(name: String) = "“$name” · Not a contact · nearby"
    }
}
