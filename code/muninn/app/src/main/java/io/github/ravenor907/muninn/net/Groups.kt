package io.github.ravenor907.muninn.net

import io.github.ravenor907.muninn.net.Proto06.Channel
import io.github.ravenor907.muninn.net.Proto06.MAIN
import io.github.ravenor907.muninn.net.Proto06.Member
import io.github.ravenor907.muninn.net.Proto06.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair

/** Round 16: one part of a long group message: the parts' set id (a logical id), its place [i] (1 = the first) of [n]. */
data class TextPart(val set: String, val i: Int, val n: Int)

/**
 * Group chats (shared/docs/protocol.md, section 3). No group key: every group message is sealed to each
 * member over that member's own pairwise session, by [Host.send]. The group itself is a signed
 * snapshot ([State]); every change is a whole new state with a higher version, signed by an admin.
 *
 * No Android in here: Chat is the [Host] on the phone, and the unit tests run several of these
 * against each other in memory.
 */
class Groups(
    private val keys: KeyPair, private val me: String, private val host: Host,
    private val read: (String) -> String?, private val write: (String, String) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    interface Host {
        val myName: String
        /** Nicknames that count as an @mention of me too (Settings › Notifications), comma separated. */
        val mentionNames: String get() = ""
        val myMailbox: String
        val readReceipts: Boolean
        /** Seal [o] for [o].to and send it on every route. One envelope id, one sealing: retries send the same bytes. */
        fun send(o: Out)
        /** The sealed copy of envelope [env] isn't needed any more. */
        fun drop(env: String)
        /** A real contact (not a group peer). */
        fun isContact(k: String): Boolean
        /** Someone whose invite (or who puts me straight into a group) is taken: a real contact; on a Kids' Device, only one of its parents. */
        fun mayInvite(k: String): Boolean = isContact(k)
        /** A member who isn't a contact becomes a group peer, so they can be reached (3.7). */
        fun peer(k: String, name: String, mailbox: String)
        fun notice(text: String)
        /** Something arrived in group [g] (for a notification). */
        fun arrived(g: String, title: String, text: String)
        /** A Kids' Device holds [from]'s messages in [g] (no parent approved them): kept, but not shown, counted or notified. */
        fun held(g: String, from: String): Boolean = false
        /** One of those came: the device's parents are asked about [from] ([name]) in [g] ([groupName]). */
        fun heldBy(g: String, from: String, name: String, groupName: String) {}
        fun deleteBlob(id: String)
        /** Their app said "m1": reactions and edits go to them (shared/docs/protocol.md, section 11). */
        fun hasM1(k: String): Boolean = false
        /** Their app said "m2": votes and video messages go to them (section 12). */
        fun hasM2(k: String): Boolean = false
        /** Their app said "d1": it takes a Family or Work group's state (section 16.2). */
        fun hasD1(k: String): Boolean = false
        /** Their app said "j1": invite links and "Members can invite" (section 17). */
        fun hasJ1(k: String): Boolean = false
        /** Group [g] became [type] ("folk" or "family"): the Family folder is looked at again. */
        fun typeChanged(g: String, type: String) {}
        /** Their app said "g2": timeouts and bans go to them (section 26). */
        fun hasG2(k: String): Boolean = false
        /** Their app said "f2": group files and the group's file rules (section 27). */
        fun hasF2(k: String): Boolean = false
        /** Their app said "g3": the group's about text, admin-only channels and reports (section 28). */
        fun hasG3(k: String): Boolean = false
        /** Their app said "g4": a join that isn't let in hears why (section 28.5). */
        fun hasG4(k: String): Boolean = false
    }

    /**
     * Cap j1: an invite link as the group's screens show it. [id] names it (the token stays with whoever counts it);
     * [x] when it stops working (ms, 0 never), [max] how many may join by it (0 no limit), [counter] the admin who
     * counts and takes the joins. [link] is the link itself, only on the phone that made it.
     */
    data class LinkInfo(val id: String, val g: String, val by: String, val byName: String, val x: Long, val max: Int, val uses: Int,
                        val counter: String, val link: String = "")

    /** One envelope to one member. [env] is the envelope id (2.3); [id] the logical id it's confirmed by. */
    data class Out(
        val env: String, val to: String, val room: String, val plain: String, val id: String, val g: String, val t: Long,
        val retry: Boolean = true, val photo: String = "", val voice: Int = 0, val ch: String = "", val tries: Int = 0,
        /** Cap m2, for a gphoto: a video's length, and the album it's in. */
        val video: Int = 0, val album: String = "", val albumAt: Int = 0, val albumOf: Int = 0,
        /** This try also goes to the relays (false while they hold it and it isn't time yet, see NetLogic). */
        val net: Boolean = true,
        /** Cap a1: an animated GIF (a member without a1 gets its first frame as a photo). */
        val gif: Boolean = false,
    )

    /**
     * [gone]: "" while I'm in it, "left" or "removed" after (then it's read-only). [LEAVING]: left and deleted here; only
     * the record stays, hidden, until the leave reaches everyone (or its retries give up), then it goes too.
     */
    data class Group(val state: State, val history: List<State> = emptyList(), val gone: String = "") {
        val readOnly get() = gone.isNotEmpty()
        val g get() = state.g
    }

    /**
     * A group message. Mine: [to] is who it went to, [delivered] and [read] who confirmed, [readAt] when each
     * reader read it (0: not known), [noRead] members whose app said it doesn't send read receipts (3.10).
     */
    data class GMsg(
        val id: String, val g: String, val ch: String, val from: String, val name: String, val text: String, val mine: Boolean,
        val time: Long, val photo: String = "", val voice: Int = 0, val unsent: String = "", val state: String = "",
        val to: Set<String> = emptySet(), val delivered: Set<String> = emptySet(), val read: Set<String> = emptySet(),
        val seen: Boolean = false,
        /** Cap m1: the message this one answers, its reactions (who to their latest) and when it was last edited (0: never). */
        val re: String = "", val reactions: Map<String, Rich.Reaction> = emptyMap(), val edited: Long = 0,
        /** Cap m2: a video message's length, an album (id, place, how many), a poll and its votes, a link preview. */
        val video: Int = 0, val album: String = "", val albumAt: Int = 0, val albumOf: Int = 0,
        val poll: More.Poll? = null, val votes: Map<String, More.Vote> = emptyMap(), val preview: More.Preview? = null,
        val readAt: Map<String, Long> = emptyMap(), val noRead: Set<String> = emptySet(),
        /** Cap a1: an animated GIF, stored like a photo under [photo]. */
        val gif: Boolean = false,
        /** Round 16: one part of a long message sent in parts: the parts' set id and this one's place (1 = the first). */
        val part: String = "", val partAt: Int = 0,
        /** A longer video from Family or trusted, kept in pieces (BigVideo); its first frame under [photo]. */
        val big: Boolean = false,
    )

    /** [Companion.readBy]: readers with when they read it (0: not known), then the rest by where the message stands with them. */
    data class ReadBy(val read: List<Pair<String, Long>>, val delivered: List<String>, val notShown: List<String>, val notYet: List<String>)

    /** "Raven invited you to Family": nothing changes until it's accepted. */
    data class Invite(val g: String, val name: String, val from: String, val fromName: String, val inv: String, val x: Long, val state: State)

    /** A change an admin makes, kept so it can be made again on top of a state that won a conflict. */
    data class Change(val op: String, val a: String = "", val b: String = "", val c: String = "", val v: Long = 0) {
        fun applyTo(s: State): State? = when (op) {
            "rename" -> if (a.isEmpty()) s.copy(name = b).takeIf { b != s.name }
                else s.channel(a)?.takeIf { it.n != b }?.let { s.copy(channels = s.channels.map { ch -> if (ch.id == a) ch.copy(n = b) else ch }) }
            "addch" -> if (s.channel(a) != null) null else s.copy(channels = s.channels + Channel(a, b, c))
            "rmch" -> if (a == MAIN || s.channel(a) == null) null else s.copy(channels = s.channels.filter { it.id != a })
            "admin" -> if (!s.has(a) || s.isAdmin(a)) null else s.copy(admins = (s.admins + a).sorted())
            "unadmin" -> if (!s.isAdmin(a) || a == s.owner) null else s.copy(admins = s.admins - a)
            "owner" -> if (!s.has(a) || a == s.owner) null else s.copy(owner = a, admins = (s.admins + a).toSortedSet().toList())
            "remove", "leave" -> if (!s.has(a) || a == s.owner) null else s.copy(members = s.members.filter { it.k != a }, admins = s.admins - a)
            "join", "add" -> if (s.has(a)) null else s.copy(members = Proto06.sortedMembers(s.members + Member(a, b, c)))
            // Cap d1: a = "family" or "folk" ("" and "work" are Folk too), b = the days, c = "mi" when members may invite
            // in a Folk group (everyone's app takes it).
            "limit" -> Limits.withType(s, if (a == Limits.FAMILY) Limits.FAMILY else Limits.FOLK, b.toIntOrNull() ?: Limits.DEFAULT, c == "mi").takeIf { it != s }
            // Cap j1: a = "on" lets members make invite links.
            "invites" -> s.copy(mi = a == "on").takeIf { it != s }
            // Cap f2: a = who may send files ("everyone", "admins", "nobody"), b = the size limit in MB ("0": none).
            "files" -> s.copy(fs = Sharing.whoMaySend(a).takeIf { it != Sharing.EVERYONE }.orEmpty(),
                fm = (b.toIntOrNull() ?: 0).takeIf { it in 1..Sharing.MAX_LIMIT_MB } ?: 0).takeIf { it != s }
            // Cap g3: b = the about text ("" takes it away).
            "about" -> s.copy(about = b).takeIf { it != s }
            // Cap g3: a = a text channel other than main, b = "on" when only admins post there.
            "ao" -> s.channel(a)?.takeIf { it.id != MAIN && it.t == "text" && it.ao != (b == "on") }
                ?.let { s.copy(channels = s.channels.map { ch -> if (ch.id == a) ch.copy(ao = b == "on") else ch }) }
            else -> null
        }
    }

    private data class Token(val i: String, val g: String, val x: Long, val multi: Boolean)
    /** Cap j1, on the admin who counts a link: its token, who made it, expiry (ms, 0 never), max uses (0 no limit), uses, revoked. */
    private data class LinkRec(val i: String, val g: String, val by: String, val x: Long, val max: Int, val uses: Int = 0, val off: Boolean = false)
    private val links = ArrayList<LinkRec>()
    /** The lists the counting admins sent (group to links), and the links this phone made (id to link). */
    private val linkLists = HashMap<String, List<LinkInfo>>()
    private val madeLinks = HashMap<String, String>()
    private val _links = MutableStateFlow<Map<String, List<LinkInfo>>>(emptyMap())
    /** For the screens: each group's active links (admins see all, members their own). */
    val linksByGroup: StateFlow<Map<String, List<LinkInfo>>> = _links.asStateFlow()
    private data class Held(val from: String, val name: String, val plain: JSONObject)

    private val lock = Any()
    private val groups = LinkedHashMap<String, Group>()
    private val chats = HashMap<String, List<GMsg>>()
    private val outs = ArrayList<Out>()
    private val tokens = ArrayList<Token>()
    private val invites = ArrayList<Invite>()
    /** Groups I asked to join, and the admin I asked: the first state is taken only from them (3.3 step 2). */
    private val joining = HashMap<String, String>()
    /** A join by a code or link waiting on the admin's app: what the chat list shows. */
    data class Waiting(val g: String, val name: String, val inviter: String, val refused: String = "")
    /** Cap g4: groups whose admin's app didn't let me in, and why ("full", "update", "banned" or "other"). */
    private val refusals = HashMap<String, String>()

    /**
     * Cap g3: a report that reached me as an admin (kept only on this phone, for the group's Reports list): who reported
     * whom, the message, its channel, what it said, when, and whether the list has shown it.
     */
    data class Report(val id: String, val g: String, val from: String, val fromName: String, val k: String, val kName: String,
                      val msg: String, val ch: String, val text: String, val time: Long, val read: Boolean = false)
    private val reports = HashMap<String, List<Report>>()
    private val _reports = MutableStateFlow<Map<String, List<Report>>>(emptyMap())
    /** For the screens: each group's reports, newest first (admins only ever get any). */
    val reportsByGroup: StateFlow<Map<String, List<Report>>> = _reports.asStateFlow()
    /** Names of the groups I asked to join by a code or link (only on this phone, for the waiting row). */
    private val joinNames = HashMap<String, Pair<String, String>>()
    private val _waiting = MutableStateFlow<List<Waiting>>(emptyList())
    val waitingJoins: StateFlow<List<Waiting>> = _waiting.asStateFlow()
    private val last = HashMap<String, Change>()
    private val deleted = HashMap<String, Long>()
    /** Unsent before it got here: message id to who unsent it and when. Only a later copy from that very sender is dropped. */
    private val unsentBy = HashMap<String, Pair<String, Long>>()
    private val unread = HashSet<String>()
    private val held = HashMap<String, MutableList<Held>>()
    private val tries = HashMap<String, Pair<Int, Long>>()
    /** What each store file last held, so a save that changes nothing writes nothing (see [writeIfChanged]). */
    private val written = ChangedOnly()

    /** Writes [name] only when [text] differs from what was last written there (a failed write is tried again next time). */
    private fun writeIfChanged(name: String, text: String) {
        if (!written.changed(name, text)) return
        write(name, text)
        written.done(name, text)
    }
    /** "g|key" to when: syncs asked of them (their states are welcome) and states sent back (not again at once). */
    private val asked = HashMap<String, Long>()
    private val answered = HashMap<String, Long>()
    private val queue = ArrayList<Out>()
    /** Cap g2 (section 26): group to person to slot ("ban" or "timeout") to the newest signed record taken. */
    private val mods = HashMap<String, HashMap<String, HashMap<String, JSONObject>>>()
    /** The last name seen for someone a record names (a banned person leaves the state). */
    private val modNames = HashMap<String, String>()
    private val _modSerial = MutableStateFlow(0L)
    /** Bumps whenever a timeout or ban changes, for the screens. */
    val modSerial: StateFlow<Long> = _modSerial.asStateFlow()

    private val _groups = MutableStateFlow<Map<String, Group>>(emptyMap())
    val all: StateFlow<Map<String, Group>> = _groups.asStateFlow()
    private val _chats = MutableStateFlow<Map<String, List<GMsg>>>(emptyMap())
    val messages: StateFlow<Map<String, List<GMsg>>> = _chats.asStateFlow()
    private val _invites = MutableStateFlow<List<Invite>>(emptyList())
    val pending: StateFlow<List<Invite>> = _invites.asStateFlow()
    private val _unread = MutableStateFlow<Set<String>>(emptySet())
    val unreadGroups: StateFlow<Set<String>> = _unread.asStateFlow()

    init { load(); publish() }

    // ------------------------------------------------------------------ reading

    fun group(g: String): Group? = synchronized(lock) { groups[g] }

    /** [m] mentions me: my name, a nickname of mine, or @everyone from an admin (read from the message, never a title). */
    fun mentionsMe(g: String, m: GMsg): Boolean = !m.mine && m.unsent.isEmpty() &&
        group(g)?.state?.let { s -> Rich.mentionsMe(m.text, host.myName, host.mentionNames, s.isAdmin(m.from)) } == true
    fun isMember(g: String, k: String) = synchronized(lock) { groups[g]?.let { !it.readOnly && it.state.has(k) } == true }
    /** A key I share a live group with (or am joining through): group rooms from them are welcome. */
    fun sharesGroup(k: String) = synchronized(lock) { groups.values.any { !it.readOnly && it.state.has(k) } || k in joining.values }
    /** The group of a group message whose envelope id is [env], if I have it already (a copy, or a resend). */
    fun knownEnvelope(env: String): String? {
        val id = env.substringBefore('-')
        if (!Proto06.isLogicalId(id)) return null
        return synchronized(lock) { chats.entries.firstOrNull { (_, l) -> l.any { it.id == id } }?.key ?: if (deleted.containsKey(id)) "" else null }
    }

    /**
     * Members a state envelope of [g] hasn't reached yet (no delivered receipt back): a group call made
     * with the group waits for them before it rings (see GroupRing).
     */
    fun stateWaiting(g: String): Set<String> = act {
        outs.filter { it.g == g && it.room == "group" && (try { JSONObject(it.plain).optString("t") } catch (e: Exception) { "" }) == "state" }
            .map { it.to }.toSet()
    }

    // ------------------------------------------------------------------ making and changing groups

    /** A new group: me as owner, only admin and only member, the main channel. Nothing is sent. */
    fun create(name: String, kind: String = "", days: Int = Limits.DEFAULT): String? = act {
        val n = clean(name, Proto06.MAX_NAME)
        if (n.isBlank()) return@act null
        val g = Proto06.newGroupId()
        // Folk (members can invite) or Family (only admins add people), section 16.2.
        val s = Limits.withType(State(g, 1, me, me, n, listOf(me), listOf(Member(me, clean(host.myName, Proto06.MAX_NAME), mailbox(host.myMailbox))),
            // A voice room from the start, as Huginn makes it.
            listOf(Channel(MAIN, "general", "text"), Channel(Proto06.newChannelId(), "Voice", "voice")), now()), if (kind == Limits.FAMILY) Limits.FAMILY else Limits.FOLK, days, true).signedBy(keys.private, me)
        Proto06.check(s)
        groups[g] = Group(s)
        g
    }

    fun rename(g: String, name: String) = change(g, Change("rename", "", clean(name, Proto06.MAX_NAME)))
    fun addChannel(g: String, name: String, voice: Boolean) =
        change(g, Change("addch", Proto06.newChannelId(), clean(name, Proto06.MAX_CHANNEL_NAME), if (voice) "voice" else "text"))
    fun renameChannel(g: String, ch: String, name: String) = change(g, Change("rename", ch, clean(name, Proto06.MAX_CHANNEL_NAME)))
    fun removeChannel(g: String, ch: String) = change(g, Change("rmch", ch))
    fun setAdmin(g: String, k: String, on: Boolean) = change(g, Change(if (on) "admin" else "unadmin", k))
    fun handOver(g: String, k: String) = change(g, Change("owner", k))
    fun remove(g: String, k: String) = change(g, Change("remove", k))
    /** Cap d1: the group's type ("family" or "folk") and how many days messages stay (7 to 30, Family only). */
    fun setLimit(g: String, kind: String, days: Int) = act {
        val type = if (kind == Limits.FAMILY) Limits.FAMILY else Limits.FOLK
        val cur = groups[g]?.state
        // Folk: members can invite, when every other member's app takes it (17.1) and it wasn't Folk already.
        val miOk = type == Limits.FOLK && cur != null && Limits.groupType(cur) != Limits.FOLK && cur.members.all { it.k == me || host.hasJ1(it.k) }
        changeLocked(g, Change("limit", type, days.toString(), if (miOk) "mi" else ""))
    }

    /** Cap j1: "Members can invite" on or off (an admin's signed change). */
    fun setMembersInvite(g: String, on: Boolean) = change(g, Change("invites", if (on) "on" else ""))

    /** Cap f2: who may send files in the group and the size limit (an admin's signed change, section 27.3). */
    fun setFiles(g: String, who: String, mb: Int) = change(g, Change("files", Sharing.whoMaySend(who), mb.coerceIn(0, Sharing.MAX_LIMIT_MB).toString()))

    /** Cap g3: the group's about text, 500 characters and 10 paragraphs at most ("" takes it away). See [aboutWire]. */
    fun setAbout(g: String, text: String) = change(g, Change("about", "", aboutWire(text)))
    /** Cap g3: only admins post in text channel [ch] (not the first one). */
    fun setAdminsOnly(g: String, ch: String, on: Boolean) = change(g, Change("ao", ch, if (on) "on" else ""))

    /** Cap g3 (28.2): channel [ch] of [s] takes posts only from admins, and [k] isn't one. */
    private fun adminsOnlyFor(s: State, ch: String, k: String) = s.channel(ch)?.ao == true && !s.isAdmin(k)
    /** Cap g3: channel [ch] of group [g] takes posts only from admins, and I'm not one (the message box says so). */
    fun adminsOnlyHere(g: String, ch: String): Boolean = synchronized(lock) { groups[g]?.state?.let { adminsOnlyFor(it, ch, me) } == true }
    private fun usesG3(s: State) = s.about.isNotEmpty() || s.channels.any { it.ao }

    /**
     * Cap g3 (28.3): reports message [msgId] to the group's admins, through the group, contact or not (an admin whose app
     * lacks g3 gets it in a private message by [viaPrivate], when they're a contact). How many it went to.
     */
    fun report(g: String, msgId: String, viaPrivate: (String, String) -> Unit): Int {
        val privates = ArrayList<Pair<String, String>>()
        val sent = act {
            val grp = groups[g] ?: return@act 0
            if (grp.readOnly) return@act 0
            val m = chats[g]?.firstOrNull { it.id == msgId && !it.mine && it.from.isNotEmpty() } ?: return@act 0
            val s = grp.state
            val text = m.text.take(500)
            var n = 0
            for (a in s.admins) {
                if (a == me || a == m.from) continue
                if (host.hasG3(a)) {
                    val id = Proto06.newLogicalId()
                    out(a, "group", JSONObject().put("t", "report").put("g", g).put("id", id).put("msg", msgId).put("k", m.from).put("text", text).toString(), id, g)
                } else if (host.isContact(a)) {
                    privates.add(a to "Report from ${s.name}: ${nameIn(s, m.from, m.name)} wrote: ${text.ifEmpty { "(a photo or file)" }}")
                } else continue
                n++
            }
            n
        }
        // Outside the lock: a private message goes through Chat.
        privates.forEach { (k, line) -> viaPrivate(k, line) }
        return sent
    }

    /** Cap f2: empty when a file of [size] from [from] is welcome in group [g], else why not (every member's app checks). */
    fun fileRefusal(g: String, from: String, size: Long): String = synchronized(lock) {
        val grp = groups[g]
        if (grp == null || grp.readOnly) "Refused: you aren't in that group."
        else Sharing.refusal(grp.state.members.map { it.k }, grp.state.admins, grp.state.fs, grp.state.fm, from, size)
    }

    /** Null when done (or nothing to do), else why not, in words for the person. */
    private fun change(g: String, c: Change): String? = act { changeLocked(g, c) }

    private fun changeLocked(g: String, c: Change): String? {
        val grp = groups[g] ?: return "That group isn't here any more."
        if (grp.readOnly) return "You're not in ${grp.state.name} any more."
        val cur = grp.state
        if (!cur.isAdmin(me)) return "Only admins can change ${cur.name}."
        if (c.op == "owner" && cur.owner != me) return "Only the owner can hand the group over."
        if ((c.op == "rename" && c.b.isBlank()) || (c.op == "addch" && c.b.isBlank())) return "Give it a name."
        if (c.op == "addch" && cur.channels.size >= Proto06.MAX_CHANNELS) return "A group has at most ${Proto06.MAX_CHANNELS} channels."
        if ((c.op == "join" || c.op == "add") && cur.members.size >= Proto06.MAX_MEMBERS) return "${cur.name} is full (${Proto06.MAX_MEMBERS} people)."
        // An app without d1 refuses a Family or Work group's state (section 16.2).
        if ((c.op == "join" || c.op == "add") && cur.kind.isNotEmpty() && !host.hasD1(c.a))
            return "${c.b.ifBlank { "They" }} need${if (c.b.isBlank()) "" else "s"} Muninn or Huginn 0.94 to be in a ${Limits.kindName(cur.kind)} group."
        // An app without j1 refuses a state with "Members can invite" on (section 17.1).
        if ((c.op == "join" || c.op == "add") && cur.mi && !host.hasJ1(c.a))
            return "${c.b.ifBlank { "They" }} need${if (c.b.isBlank()) "" else "s"} Muninn or Huginn 0.95 to join, because members can invite in ${cur.name}."
        // An app without f2 refuses a state with file rules (section 27.3).
        if ((c.op == "join" || c.op == "add") && (cur.fs.isNotEmpty() || cur.fm > 0) && !host.hasF2(c.a))
            return "${c.b.ifBlank { "They" }} need${if (c.b.isBlank()) "" else "s"} the latest Muninn or Huginn to join, because ${cur.name} has file rules."
        // An app without g3 refuses a state with an about text or an admin-only channel (section 28.4).
        if ((c.op == "join" || c.op == "add") && usesG3(cur) && !host.hasG3(c.a))
            return "${c.b.ifBlank { "They" }} need${if (c.b.isBlank()) "" else "s"} the latest Muninn or Huginn to join, because ${cur.name} has an about text or admin-only channels."
        if ((c.op == "about" && c.b.isNotEmpty()) || (c.op == "ao" && c.b == "on")) {
            val old = cur.members.filter { it.k != me && !host.hasG3(it.k) }
            if (old.isNotEmpty()) return needUpdate(old.map { it.n })
        }
        if (c.op == "files" && (c.a != Sharing.EVERYONE || (c.b.toIntOrNull() ?: 0) > 0)) {
            val old = cur.members.filter { it.k != me && !host.hasF2(it.k) }
            if (old.isNotEmpty()) return "Everyone needs the latest Muninn or Huginn first. Still waiting for: " + old.joinToString(", ") { it.n.ifBlank { "Someone" } } + "."
        }
        if (c.op == "invites" && c.a == "on" && !Limits.linksAllowed(cur)) return "In a Family group only admins add people."
        if (c.op == "invites" && c.a == "on" && !cur.mi) {
            val old = cur.members.filter { it.k != me && !host.hasJ1(it.k) }
            if (old.isNotEmpty()) return "Everyone needs Muninn or Huginn 0.95 first. Still waiting for: " + old.joinToString(", ") { it.n.ifBlank { "Someone" } } + "."
        }
        if (c.op == "limit" && c.a == Limits.FAMILY && cur.kind.isEmpty()) {
            val old = cur.members.filter { it.k != me && !host.hasD1(it.k) }
            if (old.isNotEmpty()) return "Everyone needs Muninn or Huginn 0.94 first. Still waiting for: " + old.joinToString(", ") { it.n.ifBlank { "Someone" } } + "."
        }
        val changed = c.applyTo(cur) ?: return null
        val next = try {
            changed.copy(v = cur.v + 1, ts = now()).signedBy(keys.private, me).also { Proto06.check(it) }
        } catch (e: IllegalArgumentException) { return "That change doesn't fit (${e.message})." }
        last[g] = c.copy(v = next.v)
        take(grp, next)
        broadcast(cur, next, c.op)
        return null
    }

    /** Sends [s] to everyone in [old] and [s] (the removed person too, so their app knows). */
    private fun broadcast(old: State, s: State, op: String) {
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "state").put("g", s.g).put("id", id).put("op", op).put("state", s.json()).toString()
        (old.members.map { it.k } + s.members.map { it.k }).toSet().filter { it != me }.forEach { k -> out(k, "group", plain, id, s.g) }
    }

    // ------------------------------------------------------------------ invites and codes (3.5)

    /** Invites a contact (the caller checks they have g1). Null when sent, else why not. */
    fun invite(g: String, k: String): String? = act {
        val grp = groups[g] ?: return@act "That group isn't here any more."
        val s = grp.state
        if (grp.readOnly || !s.isAdmin(me)) return@act "Only admins can invite people."
        if (s.has(k)) return@act null
        if (bannedLocked(g, k)) return@act "They're banned from ${s.name}. Unban them in Group settings first."
        if (s.members.size >= Proto06.MAX_MEMBERS) return@act "${s.name} is full (${Proto06.MAX_MEMBERS} people)."
        if (s.kind.isNotEmpty() && !host.hasD1(k)) return@act "They need Muninn or Huginn 0.94 to be in a ${Limits.kindName(s.kind)} group."
        val t = Token(Proto06.newToken(), g, now() / 1000 + Proto06.CODE_MS / 1000, false)
        tokens.add(t)
        val id = Proto06.newLogicalId()
        out(k, "group", JSONObject().put("t", "invite").put("g", g).put("id", id).put("state", s.json()).put("inv", t.i).put("x", t.x).toString(), id, g)
        null
    }

    /**
     * Cap m1: puts a contact straight in (Messenger style), no invite to accept. Only for someone whose app
     * has m1 (the caller checks): their app takes a first state with op "add" signed by a contact. Null when done.
     */
    fun addDirect(g: String, k: String, name: String, mailbox: String): String? = act {
        val grp = groups[g] ?: return@act "That group isn't here any more."
        if (grp.state.has(k)) return@act null
        changeLocked(g, Change("add", k, clean(name, Proto06.MAX_NAME).ifBlank { "Someone" }, mailbox(mailbox)))
    }

    /** A group code good for an hour, for anyone who has it (several people may use it). */
    fun code(g: String): String? = act {
        val grp = groups[g] ?: return@act null
        if (grp.readOnly || !grp.state.isAdmin(me)) return@act null
        val t = Token(Proto06.newToken(), g, now() / 1000 + Proto06.CODE_MS / 1000, true)
        tokens.add(t)
        Proto06.code(Proto06.Code(g, grp.state.name, me, clean(host.myName, Proto06.MAX_NAME), mailbox(host.myMailbox), t.i, t.x))
    }

    /** Stops every group code of [g] from working. */
    fun revokeCodes(g: String) = act { tokens.removeAll { it.g == g && it.multi }; Unit }

    fun acceptInvite(inv: Invite): String? = act {
        invites.removeAll { it.g == inv.g && it.inv == inv.inv }
        if (inv.x * 1000 < now()) return@act "That invite has expired. Ask ${inv.fromName} for a new one."
        joining[inv.g] = inv.from
        sendJoin(inv.g, inv.from, inv.inv)
        null
    }

    fun declineInvite(inv: Invite) = act {
        invites.removeAll { it.g == inv.g && it.inv == inv.inv }
        val id = Proto06.newLogicalId()
        out(inv.from, "group", JSONObject().put("t", "decline").put("g", inv.g).put("id", id).put("inv", inv.inv).toString(), id, inv.g)
    }

    /** Joins with a group code (the admin needn't be a contact). Null when asked, else why not. */
    fun joinByCode(c: Proto06.Code): String? = act {
        if (c.x * 1000 < now()) return@act "That invite has ended. Ask for a new one."
        if (c.a == me) return@act "That's your own group code."
        if (groups[c.g]?.let { !it.readOnly && it.state.has(me) } == true) return@act "You're already in ${c.gn}."
        if (!host.isContact(c.a)) host.peer(c.a, c.an.ifBlank { "Admin" }, c.p)
        joining[c.g] = c.a
        joinNames[c.g] = c.gn to c.an
        refusals.remove(c.g)
        sendJoin(c.g, c.a, c.i)
        null
    }


    /** Stops waiting to join [g] (a later state from that admin is no longer taken as joining). */
    fun stopWaiting(g: String) = act { joining.remove(g); joinNames.remove(g); refusals.remove(g); Unit }

    private fun sendJoin(g: String, admin: String, inv: String) {
        val id = Proto06.newLogicalId()
        out(admin, "group", JSONObject().put("t", "join").put("g", g).put("id", id).put("inv", inv)
            .put("n", clean(host.myName, Proto06.MAX_NAME)).put("p", mailbox(host.myMailbox)).toString(), id, g)
    }

    /** Leaves (the owner hands the group over first). The group stays here, read-only. */
    fun leave(g: String): String? = act {
        val grp = groups[g] ?: return@act null
        val s = grp.state
        if (grp.readOnly) return@act null
        if (s.owner == me && s.members.size > 1) return@act "You own ${s.name}. Hand it over to someone first (Group settings › People)."
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "leave").put("g", g).put("id", id).toString()
        s.members.map { it.k }.filter { it != me }.forEach { out(it, "group", plain, id, g) }
        groups[g] = grp.copy(gone = "left")
        null
    }

    /**
     * Leave and delete in one step: leaves, and its messages, photos and the rest go now, and it's hidden. The leave
     * itself keeps going until each member has it (or its retries give up): only that, and the group record it's
     * routed by, stay until then ([purgeLeft]).
     */
    fun leaveAndDelete(g: String): String? = act {
        val grp = groups[g] ?: return@act null
        val s = grp.state
        if (s.owner == me && s.members.size > 1 && !grp.readOnly) return@act "You own ${s.name}. Hand it over to someone first (Group settings › People)."
        if (!grp.readOnly) {
            val id = Proto06.newLogicalId()
            val plain = JSONObject().put("t", "leave").put("g", g).put("id", id).toString()
            s.members.map { it.k }.filter { it != me }.forEach { out(it, "group", plain, id, g) }
        }
        groups[g] = grp.copy(gone = LEAVING)
        chats.remove(g)?.forEach { if (it.photo.isNotEmpty()) host.deleteBlob(it.photo) }
        // Everything else on its way to this group stops; the leave goes on.
        outs.removeAll { o -> (o.g == g && !isLeave(o)).also { x -> if (x) { host.drop(o.env); tries.remove(o.env) } } }
        queue.removeAll { o -> o.g == g && !isLeave(o) }
        unread.remove(g); last.remove(g); held.remove(g); mods.remove(g)
        null
    }

    private fun isLeave(o: Out): Boolean = o.room == "group" && (try { JSONObject(o.plain).optString("t") } catch (e: Exception) { "" }) == "leave"

    /** Left-and-deleted groups whose leave reached everyone (or gave up): their record goes now. */
    private fun purgeLeft() {
        groups.entries.removeIf { (g, gr) -> gr.gone == LEAVING && outs.none { it.g == g } && queue.none { it.g == g } }
    }

    /** Removes the group and its messages from this phone (only once I'm out of it, or alone in it). */
    fun delete(g: String): Boolean = act {
        val grp = groups[g] ?: return@act false
        if (!grp.readOnly && grp.state.members.size > 1) return@act false
        groups.remove(g)
        chats.remove(g)?.forEach { if (it.photo.isNotEmpty()) host.deleteBlob(it.photo) }
        outs.removeAll { (it.g == g).also { gone -> if (gone) host.drop(it.env) } }
        unread.remove(g); last.remove(g); held.remove(g); mods.remove(g)
        true
    }

    // ------------------------------------------------------------------ messages (3.8, 3.9)

    /**
     * [re]: the message this answers (cap m1: "re" in the gmsg; apps without m1 just show the text). [at]: the time it
     * was sent, taken before a link preview was fetched, so the parts of a long message keep their order (0: now).
     */
    fun sendText(g: String, ch: String, text: String, re: String = "", poll: More.Poll? = null, preview: More.Preview? = null, at: Long = 0L,
                 part: TextPart? = null): Boolean = act {
        val t = text.trim()
        if (t.isEmpty() || Proto06.chars(t) > Proto06.MAX_TEXT) return@act false
        val (s, chId) = target(g, ch) ?: return@act false
        val id = Proto06.newLogicalId()
        val time = if (at > 0L) at else now()
        val to = s.members.map { it.k }.filter { it != me }.toSet()
        val quote = re.takeIf { r -> Proto06.isLogicalId(r) && chats[g].orEmpty().any { it.id == r } } ?: ""
        val made = GMsg(id, g, chId, me, host.myName, t, true, time, state = if (to.isEmpty()) "sent" else "sending", to = to, re = quote,
            poll = poll, preview = preview, part = part?.set ?: "", partAt = part?.i ?: 0)
        // Round 16: a part of a long message goes before its later parts already here (part 1 waits for its link preview).
        // Sent with an earlier time (after a link preview): placed by its time, before the parts that went meanwhile.
        if (part != null) chats[g] = placePart(chats[g].orEmpty(), made, now())
        else if (at > 0L) chats[g] = inOrder(chats[g].orEmpty(), made) else addMsg(made)
        // A poll's text says the same for apps without m2; they ignore "poll" and "lp". Apps before round 16 ignore the part.
        val plain = JSONObject().put("g", g).put("ch", chId).put("id", id).put("sv", s.v).put("text", t).put("ts", time)
            .apply { if (quote.isNotEmpty()) put("re", quote); poll?.let { put("poll", More.pollJson(it)) }; preview?.let { put("lp", More.previewJson(it)) }
                part?.let { put("ps", it.set).put("pi", it.i).put("pn", it.n) } }.toString()
        to.forEach { out(it, "gmsg", plain, id, g) }
        true
    }

    /** Cap m2: my vote on a poll in group [g] ([] takes it back), to every member whose app has m2. */
    fun vote(g: String, msgId: String, choices: List<Int>): Boolean = act {
        val grp = groups[g] ?: return@act false
        if (grp.readOnly) return@act false
        val m = chats[g]?.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@act false
        val poll = m.poll ?: return@act false
        val v = choices.distinct().sorted().let { if (poll.multi) it else it.take(1) }
        val ts = now()
        val all = More.vote(m.votes, poll.options.size, me, v, ts) ?: return@act false
        edit(g, msgId) { it.copy(votes = all) }
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "vote").put("g", g).put("id", id).put("msg", msgId).put("v", org.json.JSONArray(v)).put("ts", ts).toString()
        grp.state.members.map { it.k }.filter { it != me && host.hasM2(it) }.forEach { out(it, "group", plain, id, g) }
        true
    }

    /** A photo or voice message already stored as [blob] (Chat seals the pieces per member). */
    fun sendBlob(g: String, ch: String, blob: String, voiceMs: Int, videoMs: Int = 0, album: Triple<String, Int, Int>? = null, gif: Boolean = false,
                 only: Set<String>? = null): Boolean = sendBlobId(g, ch, blob, voiceMs, videoMs, album, gif, only) != null

    /**
     * [sendBlob], returning the message's id (null when it can't go). [only]: just these members (cap v3: a picked video's
     * relay copy goes to those not getting the full copy straight).
     */
    fun sendBlobId(g: String, ch: String, blob: String, voiceMs: Int, videoMs: Int = 0, album: Triple<String, Int, Int>? = null, gif: Boolean = false,
                   only: Set<String>? = null): String? = act {
        val (s, chId) = target(g, ch) ?: return@act null
        val id = Proto06.newLogicalId()
        // A video message goes only to members whose app plays it (m2); an album's fields are ignored by apps without m2.
        val to = s.members.map { it.k }.filter { it != me && (videoMs == 0 || host.hasM2(it)) && (only == null || it in only) }.toSet()
        addMsg(GMsg(id, g, chId, me, host.myName, "", true, now(), photo = blob, voice = voiceMs, state = if (to.isEmpty()) "sent" else "sending", to = to,
            video = videoMs, album = album?.first ?: "", albumAt = album?.second ?: 0, albumOf = album?.third ?: 0, gif = gif))
        to.forEach { out(it, "gphoto", "", id, g, photo = blob, voice = voiceMs, ch = chId, video = videoMs, album = album, gif = gif) }
        id
    }

    /**
     * Cap v3: one more member gets my video message [msgId] (its full copy couldn't go straight to them, so the relay
     * copy goes after all). False when the message or the member is gone.
     */
    fun addBlobMember(g: String, msgId: String, k: String): Boolean = act {
        val s = groups[g]?.takeIf { !it.readOnly }?.state ?: return@act false
        val m = chats[g]?.firstOrNull { it.id == msgId && it.mine && it.unsent.isEmpty() && it.photo.isNotEmpty() } ?: return@act false
        if (k == me || !s.has(k) || k in m.to || (m.video > 0 && !host.hasM2(k))) return@act false
        edit(g, msgId) { it.copy(to = it.to + k) }
        out(k, "gphoto", "", msgId, g, photo = m.photo, voice = m.voice, ch = m.ch, video = m.video,
            album = if (m.album.isEmpty()) null else Triple(m.album, m.albumAt, m.albumOf), gif = m.gif)
        true
    }

    private fun target(g: String, ch: String): Pair<State, String>? {
        val grp = groups[g] ?: return null
        if (grp.readOnly) return null
        // Cap g2: timed out or banned here, nothing goes out (no text, photos, voice or files).
        statusLocked(g).takeIf { it.isNotEmpty() }?.let { host.notice(it); return null }
        val s = grp.state
        // Cap g3: only admins post in this channel.
        if (adminsOnlyFor(s, ch, me)) { host.notice("Only admins post here."); return null }
        return s to (if (s.channel(ch)?.t == "text") ch else MAIN)
    }

    /** Takes my message back from everyone who got it. */
    fun unsend(g: String, msgId: String) = act {
        val m = chats[g]?.firstOrNull { it.id == msgId && it.mine && it.unsent.isEmpty() } ?: return@act
        if (m.photo.isNotEmpty()) host.deleteBlob(m.photo)
        edit(g, msgId) { it.copy(text = "", photo = "", voice = 0, unsent = "You") }
        outs.removeAll { (it.id == msgId).also { gone -> if (gone) host.drop(it.env) } }
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "unsend").put("g", g).put("id", id).put("msg", msgId).toString()
        val s = groups[g]?.state
        m.to.filter { s?.has(it) == true }.forEach { out(it, "group", plain, id, g) }
    }

    /** Cap m1: my reaction to a group message ("" takes it away), sent to every member whose app has m1. */
    fun react(g: String, msgId: String, e: String): Boolean = act {
        val grp = groups[g] ?: return@act false
        if (grp.readOnly || !Rich.emojiOk(e)) return@act false
        val m = chats[g]?.firstOrNull { it.id == msgId && it.unsent.isEmpty() } ?: return@act false
        val ts = now()
        val all = Rich.react(m.reactions, me, e, ts) ?: return@act false
        edit(g, msgId) { it.copy(reactions = all) }
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "react").put("g", g).put("id", id).put("msg", msgId).put("e", e).put("ts", ts).toString()
        grp.state.members.map { it.k }.filter { it != me && host.hasM1(it) }.forEach { out(it, "group", plain, id, g) }
        true
    }

    /** Cap m1: changes the text of my message for everyone who got it and has m1. Null when done, else why not. */
    fun editText(g: String, msgId: String, text: String): String? = act {
        val grp = groups[g] ?: return@act "That group isn't here any more."
        if (grp.readOnly) return@act "You're not in ${grp.state.name} any more."
        val t = text.trim()
        if (t.isEmpty() || Proto06.chars(t) > Proto06.MAX_TEXT) return@act "A message can be 1 to ${Proto06.MAX_TEXT} characters."
        val m = chats[g]?.firstOrNull { it.id == msgId && it.mine && it.unsent.isEmpty() && it.photo.isEmpty() } ?: return@act "Only your own text messages can be edited."
        if (m.text == t) return@act null
        val ts = maxOf(now(), m.edited + 1)
        edit(g, msgId) { it.copy(text = t, edited = ts) }
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "edit").put("g", g).put("id", id).put("msg", msgId).put("text", t).put("ts", ts).toString()
        val s = grp.state
        m.to.filter { s.has(it) && host.hasM1(it) }.forEach { out(it, "group", plain, id, g) }
        null
    }

    /** Deletes one message on this phone only. */
    fun deleteMessage(g: String, msgId: String) = act {
        val list = chats[g] ?: return@act
        list.firstOrNull { it.id == msgId }?.photo?.takeIf { it.isNotEmpty() }?.let { host.deleteBlob(it) }
        chats[g] = list.filterNot { it.id == msgId }
        deleted[msgId] = now()
        outs.removeAll { (it.id == msgId).also { gone -> if (gone) host.drop(it.env) } }
    }

    /** The group is on screen: clear its dot and, with read receipts on, tell each author what was read. */
    fun markRead(g: String) = act {
        unread.remove(g)
        val fresh = chats[g].orEmpty().filter { !it.mine && !it.seen && it.unsent.isEmpty() }
        if (fresh.isEmpty()) return@act
        chats[g] = chats[g].orEmpty().map { if (!it.mine && !it.seen) it.copy(seen = true) else it }
        if (host.readReceipts) fresh.groupBy { it.from }.forEach { (k, l) -> l.map { it.id }.chunked(Proto06.MAX_RECEIPT_IDS).forEach { receipt(k, g, it, "read") } }
    }

    // ------------------------------------------------------------------ timeouts and bans (cap g2, section 26)

    data class Barred(val key: String, val name: String, val until: Long = 0)

    private fun modRec(g: String, k: String, slot: String): JSONObject? = mods[g]?.get(k)?.get(slot)
    private fun bannedLocked(g: String, k: String) = ModLogic.banned(modRec(g, k, "ban"))
    private fun untilLocked(g: String, k: String): Long = modRec(g, k, "timeout").let { if (ModLogic.timedOut(it, now())) ModLogic.until(it) else 0L }
    private fun barredLocked(g: String, k: String) = bannedLocked(g, k) || untilLocked(g, k) > 0

    fun isBanned(g: String, k: String) = synchronized(lock) { bannedLocked(g, k) }
    /** When their timeout ends (ms), 0 when they aren't timed out. */
    fun timedOutUntil(g: String, k: String) = synchronized(lock) { untilLocked(g, k) }
    /** Timed out or banned: their posts are dropped, and they stay out of the group's voice rooms. */
    fun barred(g: String, k: String) = synchronized(lock) { barredLocked(g, k) }

    /** "You're timed out until 3:40 PM", "You were banned from Family", or "". */
    fun myStatus(g: String): String = synchronized(lock) { statusLocked(g) }

    private fun statusLocked(g: String): String {
        val name = groups[g]?.state?.name ?: "this group"
        if (bannedLocked(g, me)) return "You were banned from $name"
        val until = untilLocked(g, me)
        return if (until > 0) "You're timed out until ${whenText(until)}" else ""
    }

    /** I may time out or ban [k] in [g]: I'm an admin, they aren't me or the owner, and an admin only by the owner. */
    fun canModerate(g: String, k: String): Boolean = synchronized(lock) {
        val grp = groups[g] ?: return@synchronized false
        val s = grp.state
        !grp.readOnly && k.isNotEmpty() && k != me && k != s.owner && s.isAdmin(me) && (!s.isAdmin(k) || s.owner == me)
    }

    fun bannedList(g: String): List<Barred> = synchronized(lock) {
        mods[g].orEmpty().filter { (_, slots) -> ModLogic.banned(slots["ban"]) }.map { (k, _) -> Barred(k, modName(g, k)) }.sortedBy { it.name.lowercase() }
    }

    fun timeoutList(g: String): List<Barred> = synchronized(lock) {
        val t = now()
        mods[g].orEmpty().filter { (_, slots) -> ModLogic.timedOut(slots["timeout"], t) && !ModLogic.banned(slots["ban"]) }
            .map { (k, slots) -> Barred(k, modName(g, k), ModLogic.until(slots["timeout"])) }.sortedBy { it.until }
    }

    private fun modName(g: String, k: String) = groups[g]?.state?.member(k)?.n?.ifBlank { null } ?: modNames[k] ?: "Someone"

    /** 10 minutes to a week ([ms] one of [ModLogic.TIMEOUTS]). Null when done, else why not. */
    fun timeout(g: String, k: String, ms: Long): String? = act {
        if (ms <= 0 || ms > 7 * ModLogic.DAY) return@act "Pick 10 minutes, 1 hour, 1 day or 1 week."
        modLocked(g, k, "timeout", now() + ms)
    }

    fun cancelTimeout(g: String, k: String): String? = act { if (untilLocked(g, k) == 0L) null else modLocked(g, k, "untimeout", 0) }

    /** Bans [k] and takes them out of the group (a signed state, 3.4). */
    fun ban(g: String, k: String): String? = act {
        modLocked(g, k, "ban", 0) ?: if (groups[g]?.state?.has(k) == true) changeLocked(g, Change("remove", k)) else null
    }

    fun unban(g: String, k: String): String? = act { if (!bannedLocked(g, k)) null else modLocked(g, k, "unban", 0) }

    /**
     * Ban's "remove their messages": a signed remove (26.2) for each of [author]'s messages in [g] from the last [ms]
     * (at most [ModLogic.MAX_LIST], newest first), in one "mod" list to every member with g2. How many.
     */
    fun removeRecent(g: String, author: String, ms: Long): Int = act {
        val grp = groups[g] ?: return@act 0
        val s = grp.state
        if (grp.readOnly || ms <= 0 || author.isEmpty() || author == me || author == s.owner || !s.isAdmin(me) || (s.isAdmin(author) && s.owner != me)) return@act 0
        val since = now() - ms
        val recs = chats[g].orEmpty().filter { !it.mine && it.from == author && it.unsent.isEmpty() && it.time >= since && Proto06.isLogicalId(it.id) }
            .sortedByDescending { it.time }.take(ModLogic.MAX_LIST)
            .mapNotNull { m -> try { ModLogic.makeRemove(keys.private, g, author, me, now(), m.id).takeIf { ModLogic.accept(s, it) == "ok" } } catch (e: Exception) { null } }
        if (recs.isEmpty()) return@act 0
        recs.forEach { blankLocked(g, it) }
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "mod").put("g", g).put("id", id).put("list", JSONArray().apply { recs.forEach { put(it) } }).toString()
        s.members.map { it.k }.filter { it != me && host.hasG2(it) }.forEach { out(it, "group", plain, id, g) }
        recs.size
    }

    /** Members (not me) whose app has no g2: a message removed for everyone stays on their screen until they update. */
    fun withoutRemove(g: String): Int = synchronized(lock) { groups[g]?.state?.members?.count { it.k != me && !host.hasG2(it.k) } ?: 0 }

    /** Cap g3: members whose app can't take the about text or "Only admins post" yet (their names). */
    fun needG3Update(g: String): List<String> = synchronized(lock) {
        groups[g]?.state?.members?.filter { it.k != me && !host.hasG3(it.k) }?.map { it.n.ifBlank { "Someone" } }.orEmpty()
    }

    /** Opening the Reports list: they're read, and the group's dot goes when nothing else is unread there. */
    fun readReports(g: String) = act {
        val l = reports[g] ?: return@act
        if (l.any { !it.read }) reports[g] = l.map { it.copy(read = true) }
    }

    fun dismissReport(g: String, id: String) = act {
        reports[g]?.let { l -> reports[g] = l.filter { it.id != id } }
    }

    private fun modLocked(g: String, k: String, action: String, until0: Long): String? {
        val grp = groups[g] ?: return "That group isn't here any more."
        val s = grp.state
        if (grp.readOnly || k.isEmpty() || k == me || k == s.owner || !s.isAdmin(me) || (s.isAdmin(k) && s.owner != me))
            return "Only the group's admins can do that."
        val slot = if (action == "ban" || action == "unban") "ban" else "timeout"
        // Above the record it replaces, even if this phone's clock is behind the last admin's.
        val ts = maxOf(now(), (ModLogic.whole(modRec(g, k, slot)?.opt("ts")) ?: 0L) + 1)
        val until = if (action == "timeout") maxOf(until0, ts + 1) else 0L
        val rec = try { ModLogic.make(keys.private, action, g, k, me, ts, until) } catch (e: Exception) { return "That couldn't be signed." }
        if (ModLogic.accept(s, rec) != "ok") return "That couldn't be signed."
        takeMod(g, rec, announce = true)
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "mod").put("g", g).put("id", id).put("list", JSONArray().put(rec)).toString()
        (s.members.map { it.k } + k).toSet().filter { it != me && host.hasG2(it) }.forEach { out(it, "group", plain, id, g) }
        return null
    }

    /** Cap g2: an admin's Delete for everyone of [msgId], which [author] wrote (a signed "remove", 26.2). Null when done. */
    fun removeForEveryone(g: String, msgId: String, author: String): String? = act {
        val grp = groups[g] ?: return@act "That group isn't here any more."
        val s = grp.state
        if (grp.readOnly || !Proto06.isLogicalId(msgId) || author.isEmpty() || author == me || author == s.owner || !s.isAdmin(me)
            || (s.isAdmin(author) && s.owner != me)) return@act "Only the group's admins can do that."
        val rec = try { ModLogic.makeRemove(keys.private, g, author, me, now(), msgId) } catch (e: Exception) { return@act "That couldn't be signed." }
        if (ModLogic.accept(s, rec) != "ok") return@act "That couldn't be signed."
        blankLocked(g, rec)
        val id = Proto06.newLogicalId()
        val plain = JSONObject().put("t", "mod").put("g", g).put("id", id).put("list", JSONArray().put(rec)).toString()
        s.members.map { it.k }.filter { it != me && host.hasG2(it) }.forEach { out(it, "group", plain, id, g) }
        null
    }

    /** A "remove" taken: the message shows as "Removed by an admin", or never shows when it comes later. */
    private fun blankLocked(g: String, rec: JSONObject) {
        val author = rec.getString("k"); val msg = rec.getString("m")
        val m = chats[g]?.firstOrNull { it.id == msg && (if (it.mine) author == me else it.from == author) }
        if (m == null) { unsentBy[msg] = author to now(); held[g]?.removeAll { it.from == author && it.plain.optString("id") == msg }; return }
        if (m.photo.isNotEmpty()) host.deleteBlob(m.photo)
        edit(g, msg) { it.copy(text = "", photo = "", voice = 0, unsent = REMOVED) }
    }

    /** Keeps [rec] (already checked) when it's newer in its slot. Tells me when it names me. */
    private fun takeMod(g: String, rec: JSONObject, announce: Boolean): Boolean {
        val k = rec.getString("k"); val slot = ModLogic.slot(rec)
        if (!ModLogic.newer(modRec(g, k, slot), rec)) return false
        mods.getOrPut(g) { HashMap() }.getOrPut(k) { HashMap() }[slot] = rec
        groups[g]?.state?.member(k)?.n?.takeIf { it.isNotBlank() }?.let { modNames[k] = it }
        _modSerial.value = _modSerial.value + 1
        if (!announce || k != me) return true
        val name = groups[g]?.state?.name ?: "the group"
        host.notice(when (rec.getString("a")) {
            "ban" -> "You were banned from $name"
            "unban" -> "You were unbanned from $name. You can join again."
            "timeout" -> "You're timed out in $name until ${whenText(ModLogic.until(rec))}"
            else -> "Your timeout in $name ended"
        })
        return true
    }

    /** Every record still in force for [g], to [to] (after a sync, or when an admin just added them). */
    private fun sendMods(g: String, to: String) {
        if (!host.hasG2(to)) return
        val t = now()
        val list = mods[g].orEmpty().values.flatMap { slots ->
            listOfNotNull(slots["ban"]?.takeIf { ModLogic.banned(it) }, slots["timeout"]?.takeIf { ModLogic.timedOut(it, t) })
        }
        list.chunked(ModLogic.MAX_LIST).forEach { part ->
            val id = Proto06.newLogicalId()
            out(to, "group", JSONObject().put("t", "mod").put("g", g).put("id", id).put("list", JSONArray().apply { part.forEach { put(it) } }).toString(), id, g)
        }
    }

    private fun whenText(ms: Long): String {
        val d = java.util.Date(ms)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d)
        val sameDay = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).let { it.format(d) == it.format(java.util.Date(now())) }
        return if (sameDay) time else java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(d) + ", " + time
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Rooms "group", "gmsg" and "greceipt", after the envelope opened. [isContact]: a real contact.
     * Returns 200 (taken, or quietly dropped), 400 (malformed) or 403 (not from someone allowed to send it).
     */
    fun receive(room: String, from: String, fromName: String, envMailbox: String?, p: JSONObject): Int = act {
        when (room) {
            "group" -> control(from, fromName, envMailbox, p)
            "gmsg" -> gmsg(from, fromName, p, fromHeld = false)
            "greceipt" -> greceipt(from, p)
            else -> 400
        }
    }

    private fun control(from: String, fromName: String, envMailbox: String?, p: JSONObject): Int {
        val t = p.optString("t"); val g = p.optString("g"); val id = p.optString("id")
        if (!Proto06.isLogicalId(id) || g.isEmpty() || g.length > 40) return 400
        val grp = groups[g]
        val inGroup = grp != null && (grp.state.has(from) || grp.history.any { it.has(from) })
        val allowed = when (t) {
            "invite" -> host.mayInvite(from)
            "join" -> liveToken(p.optString("inv"), g) != null || liveLink(p.optString("inv"), g) != null
            // Cap j1: links (17.2).
            "link", "linkoff" -> grp != null && !grp.readOnly && grp.state.has(from)
            "links" -> grp != null && grp.state.isAdmin(from)
            "decline" -> host.isContact(from) || inGroup
            "state", "states" -> inGroup || joining[g] == from || asked.containsKey("$g|$from") ||
                // Cap m1: a contact put me straight in (op "add"), no invite to accept.
                (t == "state" && grp == null && p.optString("op") == "add" && host.mayInvite(from))
            "leave", "unsend" -> inGroup
            // Only someone in it now gets its states (a removed member doesn't keep learning who's in it).
            "sync" -> grp != null && grp.state.has(from)
            // Cap m1: reactions and edits from someone in the current state.
            "react", "edit" -> grp != null && !grp.readOnly && grp.state.has(from)
            // Cap m2: votes, likewise.
            "vote" -> grp != null && !grp.readOnly && grp.state.has(from)
            // Cap g2: timeouts and bans, each checked against the current state (an admin signed it).
            "mod" -> grp != null && inGroup
            // Cap g3: a member's report, only to an admin.
            "report" -> grp != null && !grp.readOnly && grp.state.has(from) && grp.state.isAdmin(me)
            // Cap g4: the admin I asked to let me in says why not, only while I wait for them.
            "joinno" -> joining[g] == from
            else -> return 400
        }
        if (!allowed) return 403
        when (t) {
            "invite" -> {
                val s = try { Proto06.parseState(p.getJSONObject("state")) } catch (e: Exception) { return 400 }
                val x = p.optLong("x")
                if (s.g != g || !s.isAdmin(from) || !Proto06.accept(null, s).first) return 400
                if (x * 1000 > now() && grp?.let { !it.readOnly && it.state.has(me) } != true && invites.none { it.g == g && it.from == from }) {
                    invites.add(Invite(g, s.name, from, fromName, p.optString("inv"), x, s))
                    host.notice("$fromName invited you to ${s.name}")
                    host.arrived(g, s.name, "$fromName invited you")
                }
            }
            "join" -> {
                val tok = liveToken(p.optString("inv"), g)
                val lnk = if (tok == null) liveLink(p.optString("inv"), g) else null
                val cur = grp?.state
                if (grp == null || cur == null || grp.readOnly || !cur.isAdmin(me)) return 200
                // Cap g2: someone banned doesn't get back in by a code, a link or an invite until they're unbanned.
                if (bannedLocked(g, from)) {
                    host.notice("${clean(fromName, Proto06.MAX_NAME).ifBlank { "Someone" }} tried to join ${cur.name}, but they're banned.")
                    refuseJoin(g, from, "banned")
                    receipt(from, g, listOf(id), "delivered")
                    return 200
                }
                if (tok != null && !tok.multi) tokens.remove(tok)
                val n = clean(p.optString("n"), Proto06.MAX_NAME).ifBlank { clean(fromName, Proto06.MAX_NAME) }.ifBlank { "Someone" }
                val mb = p.optString("p").takeIf { Proto06.isMailbox(it) } ?: envMailbox?.takeIf { Proto06.isMailbox(it) } ?: ""
                if (!host.isContact(from)) host.peer(from, n, mb)
                if (cur.has(from)) {
                    val rid = Proto06.newLogicalId()
                    out(from, "group", JSONObject().put("t", "state").put("g", g).put("id", rid).put("op", "join").put("state", cur.json()).toString(), rid, g)
                } else {
                    val why = changeLocked(g, Change("join", from, n, mb))
                    if (why != null) {
                        host.notice(why)
                        // Cap g4 (28.5): they hear why, instead of waiting.
                        refuseJoin(g, from, if (" is full" in why) "full" else if ("need" in why) "update" else "other")
                    }
                    else {
                        sendMods(g, from)   // cap g2: so their app knows who is timed out
                        // Cap j1: one more use, counted only here (this admin is the link's only counter).
                        if (lnk != null && groups[g]?.state?.has(from) == true) {
                            links.replaceAll { if (it.i == lnk.i) it.copy(uses = it.uses + 1) else it }
                            sendLinks(g)
                        }
                    }
                }
            }
            "link" -> {
                // A member (or another admin) made a link that names this phone as its counter.
                val cur = grp!!.state
                if (!cur.isAdmin(me) || !(cur.isAdmin(from) || Limits.membersMayInvite(cur))) return 403
                val inv = p.optString("inv"); val x = p.optLong("lx", -1); val max = p.optInt("max", -1)
                if (Proto06.lenB64url(inv) != 16 || x < 0 || max !in 0..Proto06.MAX_LINK_USES) return 400
                // A Family group takes nobody by a link (17.1): the link is never kept.
                if (Limits.linksAllowed(cur) && links.none { it.i == inv }) links.add(LinkRec(inv, g, from, x, max))
                sendLinks(g)
            }
            "linkoff" -> {
                val lid = p.optString("lid")
                val cur = grp!!.state
                val rec = links.firstOrNull { it.g == g && Proto06.linkId(it.i) == lid }
                if (rec != null && (rec.by == from || cur.isAdmin(from))) {
                    links.replaceAll { if (it.i == rec.i) it.copy(off = true) else it }
                    sendLinks(g)
                }
            }
            "links" -> {
                val a = p.optJSONArray("list") ?: return 400
                val got = try { (0 until minOf(a.length(), Proto06.MAX_LINKS)).map { a.getJSONObject(it) }.map {
                    LinkInfo(it.getString("lid"), g, it.getString("by"), nameIn(grp!!.state, it.getString("by"), "Someone"), it.getLong("x"), it.getInt("max"),
                        it.getInt("uses"), from, madeLinks[it.getString("lid")] ?: "")
                } } catch (e: Exception) { return 400 }
                linkLists[g] = linkLists[g].orEmpty().filter { it.counter != from } + got
            }
            "decline" -> tokens.removeAll { it.i == p.optString("inv") && it.g == g && !it.multi }
            "state" -> {
                val s = try { Proto06.parseState(p.getJSONObject("state")) } catch (e: Exception) { return 400 }
                if (s.g != g) return 400
                takeState(from, s, p.optString("op"))
            }
            "states" -> {
                val a = p.optJSONArray("list") ?: return 400
                val list = try { (0 until minOf(a.length(), Proto06.STATES_ANSWER)).map { Proto06.parseState(a.getJSONObject(it)) } } catch (e: Exception) { return 400 }
                list.filter { it.g == g }.sortedBy { it.v }.forEach { takeState(from, it) }
            }
            "sync" -> {
                val have = p.optLong("have")
                val list = (grp!!.history + grp.state).filter { it.v > have }.sortedBy { it.v }.take(Proto06.STATES_ANSWER)
                if (list.isNotEmpty()) {
                    val rid = Proto06.newLogicalId()
                    out(from, "group", JSONObject().put("t", "states").put("g", g).put("id", rid).put("list", JSONArray().apply { list.forEach { put(it.json()) } }).toString(), rid, g)
                }
                sendMods(g, from)   // cap g2: timeouts and bans in force
            }
            "leave" -> {
                val cur = grp!!.state
                if (!grp.readOnly && cur.isAdmin(me) && cur.has(from)) changeLocked(g, Change("leave", from))
            }
            "unsend" -> {
                val msg = p.optString("msg")
                if (!Proto06.isLogicalId(msg)) return 400
                val m = chats[g]?.firstOrNull { it.id == msg && !it.mine }
                when {
                    m == null -> { unsentBy[msg] = from to now(); held[g]?.removeAll { it.from == from && it.plain.optString("id") == msg } }
                    m.from == from && m.unsent.isEmpty() -> {
                        if (m.photo.isNotEmpty()) host.deleteBlob(m.photo)
                        edit(g, msg) { it.copy(text = "", photo = "", voice = 0, unsent = nameIn(grp!!.state, from, fromName)) }
                    }
                }
                if (m == null || m.from == from) receipt(from, g, listOf(msg), "unsent")
            }
            "react", "edit" -> {
                if (!Rich.ok("group", p)) return 400
                if (!barredLocked(g, from)) takeChange(grp!!, from, fromName, p)
            }
            "joinno" -> {
                val why = p.optString("why")
                if (why !in REFUSALS) return 400
                if (refusals[g] != why) {
                    refusals[g] = why
                    val name = joinNames[g]?.first?.ifBlank { null } ?: "the group"
                    host.notice("Not let in to $name. ${refusalText(why, name)}")
                }
            }
            "report" -> {
                val msg = p.optString("msg"); val k = p.optString("k"); val text = p.opt("text") as? String ?: return 400
                if (!Proto06.isLogicalId(msg) || k.isEmpty() || Proto06.chars(text) > 500) return 400
                val s = grp!!.state
                // Kept only on this admin's phone, in the group's Reports list (never a line in a channel), unread with a
                // notification like an @mention. The same person reporting the same message again counts once.
                if (reports[g].orEmpty().none { it.msg == msg && it.from == from }) {
                    val chId = chats[g]?.firstOrNull { it.id == msg }?.ch.orEmpty()
                    val r = Report(Proto06.newLogicalId(), g, from, nameIn(s, from, fromName), k, nameIn(s, k, modNames[k] ?: "Someone"), msg, chId, text, now())
                    reports[g] = (listOf(r) + reports[g].orEmpty()).take(100)
                    unread.add(g)
                    val chName = s.channel(chId)?.n
                    host.arrived(g, "Report in ${s.name}", "${r.fromName} reported ${r.kName}${if (chName != null) " in #$chName" else ""}: ${text.ifEmpty { "(a photo or file)" }}")
                }
            }
            "mod" -> {
                val a = p.optJSONArray("list") ?: return 400
                if (a.length() < 1 || a.length() > ModLogic.MAX_LIST) return 400
                val s = grp!!.state
                for (i in 0 until a.length()) {
                    val rec = a.optJSONObject(i) ?: continue
                    if (rec.optString("g") != g || ModLogic.accept(s, rec) != "ok") continue
                    if (ModLogic.isRemove(rec)) blankLocked(g, rec) else takeMod(g, rec, announce = true)
                }
            }
            "vote" -> {
                if (!More.ok("group", p)) return 400
                val m = chats[g]?.firstOrNull { it.id == p.getString("msg") && it.unsent.isEmpty() }?.takeIf { !barredLocked(g, from) }
                val poll = m?.poll
                if (m != null && poll != null) {
                    val a = p.getJSONArray("v")
                    val v = (0 until a.length()).map { a.getInt(it) }.let { if (poll.multi) it else it.take(1) }
                    More.vote(m.votes, poll.options.size, from, v, Rich.ts(p))?.let { all -> edit(g, m.id) { it.copy(votes = all) } }
                }
            }
        }
        // Every group message is confirmed to its sender, so they stop resending it.
        receipt(from, g, listOf(id), "delivered")
        return 200
    }

    /** Cap g4 (28.5): tells [k] their join to [g] wasn't taken, and why. Only to an app that says g4 (an older one would answer 400). */
    private fun refuseJoin(g: String, k: String, why: String) {
        if (!host.hasG4(k) || k == me) return
        val id = Proto06.newLogicalId()
        out(k, "group", JSONObject().put("t", "joinno").put("g", g).put("id", id).put("why", why).toString(), id, g)
    }

    private fun liveToken(inv: String, g: String): Token? =
        tokens.firstOrNull { it.i == inv && it.g == g && it.x * 1000 > now() }

    /**
     * Cap j1 (17.3): a link this phone counts that still lets someone in: not revoked, not expired, uses left, and
     * its maker still in the group and allowed to invite (an admin, or "Members can invite" on).
     */
    private fun liveLink(inv: String, g: String): LinkRec? {
        val rec = links.firstOrNull { it.i == inv && it.g == g } ?: return null
        val s = groups[g]?.state ?: return null
        if (!Limits.linksAllowed(s)) return null   // a Family group takes nobody by a link (17.1)
        return rec.takeIf { Proto06.linkOk(it.x, it.max, it.uses, it.off, s.has(it.by), s.isAdmin(it.by) || Limits.membersMayInvite(s), now()) }
    }

    /** The links this phone counts for [g], to every admin and to each link's maker (their own), with j1. */
    private fun sendLinks(g: String) {
        val s = groups[g]?.state ?: return
        val mine = links.filter { it.g == g && Limits.linksAllowed(s)
            && Proto06.linkOk(it.x, it.max, it.uses, it.off, s.has(it.by), s.isAdmin(it.by) || Limits.membersMayInvite(s), now()) }
        fun json(l: List<LinkRec>) = JSONArray().apply { l.forEach { put(JSONObject().put("lid", Proto06.linkId(it.i)).put("by", it.by).put("x", it.x)
            .put("max", it.max).put("uses", it.uses)) } }
        linkLists[g] = linkLists[g].orEmpty().filter { it.counter != me } + mine.map {
            LinkInfo(Proto06.linkId(it.i), g, it.by, nameIn(s, it.by, "Someone"), it.x, it.max, it.uses, me, madeLinks[Proto06.linkId(it.i)] ?: "") }
        val to = (s.admins + mine.map { it.by }).toSet().filter { it != me && s.has(it) && host.hasJ1(it) }
        for (k in to) {
            val list = if (s.isAdmin(k)) mine else mine.filter { it.by == k }
            val id = Proto06.newLogicalId()
            out(k, "group", JSONObject().put("t", "links").put("g", g).put("id", id).put("list", json(list)).toString(), id, g)
        }
    }

    /**
     * Cap j1: a new invite link for [g], good for [ttlMs] (0 never) and [maxUses] people (0 no limit). An admin's link
     * is counted on their own phone; a member's (only with "Members can invite" on) by the owner's phone. Returns
     * (link, null) or (null, why not).
     */
    fun createLink(g: String, ttlMs: Long, maxUses: Int): Pair<String?, String?> = act {
        val grp = groups[g] ?: return@act null to "That group isn't here any more."
        val s = grp.state
        if (grp.readOnly) return@act null to "You're not in ${s.name} any more."
        if (!Limits.linksAllowed(s)) return@act null to "Family groups have no links, so nobody can pass one on. Admins add people by QR in person or by sending a code."
        if (!s.isAdmin(me) && !Limits.membersMayInvite(s)) return@act null to "Only admins can make invite links in ${s.name}."
        if (ttlMs < 0 || maxUses !in 0..Proto06.MAX_LINK_USES) return@act null to "Pick how long and how many people."
        val counter = if (s.isAdmin(me)) me else s.owner
        if (counter != me && !host.hasJ1(counter)) return@act null to "${nameIn(s, counter, "The owner")} needs Muninn or Huginn 0.95 first."
        val token = Proto06.newToken()
        val x = if (ttlMs == 0L) 0L else now() + ttlMs
        val codeX = if (x == 0L) now() / 1000 + Proto06.NEVER_S else x / 1000
        val cm = s.member(counter)
        val link = Proto06.link(Proto06.Code(g, s.name, counter, cm?.n ?: "", cm?.p ?: "", token, codeX))
        madeLinks[Proto06.linkId(token)] = link
        if (counter == me) { links.add(LinkRec(token, g, me, x, maxUses)); sendLinks(g) }
        else {
            val id = Proto06.newLogicalId()
            out(counter, "group", JSONObject().put("t", "link").put("g", g).put("id", id).put("inv", token).put("lx", x).put("max", maxUses).toString(), id, g)
            linkLists[g] = linkLists[g].orEmpty() + LinkInfo(Proto06.linkId(token), g, me, nameIn(s, me, host.myName), x, maxUses, 0, counter, link)
        }
        link to null
    }

    /** Cap j1: stops a link working. Admins may revoke any link, members their own. Null when done, else why not. */
    fun revokeLink(g: String, id: String): String? = act {
        val grp = groups[g] ?: return@act "That group isn't here any more."
        val info = linkLists[g].orEmpty().firstOrNull { it.id == id } ?: return@act null
        if (info.by != me && !grp.state.isAdmin(me)) return@act "Only admins can stop someone else's link."
        if (info.counter == me) {
            links.replaceAll { if (it.g == g && Proto06.linkId(it.i) == id) it.copy(off = true) else it }
            sendLinks(g)
        } else {
            val mid = Proto06.newLogicalId()
            out(info.counter, "group", JSONObject().put("t", "linkoff").put("g", g).put("id", mid).put("lid", id).toString(), mid, g)
            linkLists[g] = linkLists[g].orEmpty().filter { it.id != id }
        }
        null
    }

    /** Cap j1: the group's active links, as the screens show them. */
    fun links(g: String): List<LinkInfo> = synchronized(lock) {
        val s = groups[g]?.state
        linkLists[g].orEmpty().filter { (it.x == 0L || it.x > now()) && (it.max == 0 || it.uses < it.max) && (s == null || s.isAdmin(me) || it.by == me) }
    }

    /** Applies 3.3 to an incoming state. [op]: its hint; "add" from a contact puts me straight in (cap m1). */
    private fun takeState(from: String, s: State, op: String = "") {
        val grp = groups[s.g]
        if (grp == null) {
            val added = op == "add" && s.by == from && host.mayInvite(from) && joining[s.g] == null
            if ((joining[s.g] != s.by && !added) || !s.has(me) || !Proto06.accept(null, s).first) return
            joining.remove(s.g)
            invites.removeAll { it.g == s.g }
            groups[s.g] = Group(s)
            peers(s)
            if (added) {
                val who = nameIn(s, from, "Someone")
                host.notice("$who added you to ${s.name}")
                host.arrived(s.g, s.name, "$who added you")
            } else host.notice("You joined ${s.name}")
            replayHeld(s.g)
            return
        }
        val (ok, why) = Proto06.accept(grp.state, s)
        if (!ok) {
            // Behind: the signer became an admin in a version I missed. Ask for the ones in between.
            if (why == "signer is not an admin in the current state" && s.v > grp.state.v) askSync(s.g, from, grp.state.v)
            return
        }
        take(grp, s)
    }

    /** [s] replaces the current state of [grp] (already checked, or made here). */
    private fun take(grp: Group, s: State) {
        val cur = grp.state
        val rejoin = joining[s.g] != null && s.has(me)
        val gone = when {
            !s.has(me) -> grp.gone.ifEmpty { "removed" }
            grp.gone == "left" && !rejoin -> "left"
            grp.gone == LEAVING && !rejoin -> LEAVING
            else -> ""
        }
        if (rejoin) joining.remove(s.g)
        groups[s.g] = Group(s, (grp.history + cur).filter { it.hash != s.hash }.takeLast(Proto06.HISTORY), gone)
        // Became Family: every link this phone counts stops, and none are shown (17.1).
        if (!Limits.linksAllowed(s) && (links.any { it.g == s.g && !it.off } || linkLists[s.g].orEmpty().isNotEmpty())) {
            links.replaceAll { if (it.g == s.g) it.copy(off = true) else it }
            linkLists.remove(s.g)
            sendLinks(s.g)
        }
        if (Limits.groupType(cur) != Limits.groupType(s)) host.typeChanged(s.g, Limits.groupType(s))
        peers(s)
        // Stop sending messages to anyone who isn't in it any more.
        val gone2 = cur.members.map { it.k }.filter { !s.has(it) }.toSet()
        outs.removeAll { (it.g == s.g && it.to in gone2 && it.room != "group").also { x -> if (x) host.drop(it.env) } }
        if (gone == "removed" && grp.gone.isEmpty()) host.notice("You were removed from ${s.name}")
        if (gone.isEmpty() && grp.gone.isNotEmpty()) host.notice("You're back in ${s.name}")
        // Two admins changed the same version and mine lost: make my change again on top of the winner.
        if (s.v == cur.v && cur.by == me && s.by != me) last[s.g]?.takeIf { it.v == cur.v }?.let { changeLocked(s.g, it) }
        replayHeld(s.g)
    }

    private fun peers(s: State) = s.members.filter { it.k != me && !host.isContact(it.k) }.forEach { host.peer(it.k, it.n, it.p) }

    private fun askSync(g: String, k: String, have: Long) {
        val key = "$g|$k"
        if (now() - (asked[key] ?: 0L) < 30_000) return
        asked[key] = now()
        val id = Proto06.newLogicalId()
        out(k, "group", JSONObject().put("t", "sync").put("g", g).put("id", id).put("have", have).toString(), id, g)
    }

    /** They're behind: send them the current state (not more than every 30 s). */
    private fun sendStateBack(g: String, k: String) {
        val key = "$g|$k"
        if (now() - (answered[key] ?: 0L) < 30_000) return
        answered[key] = now()
        val s = groups[g]?.state ?: return
        val id = Proto06.newLogicalId()
        out(k, "group", JSONObject().put("t", "state").put("g", g).put("id", id).put("op", "sync").put("state", s.json()).toString(), id, g)
    }

    private fun replayHeld(g: String) {
        val list = held.remove(g) ?: return
        list.forEach { gmsg(it.from, it.name, it.plain, fromHeld = true) }
    }

    private fun gmsg(from: String, fromName: String, p: JSONObject, fromHeld: Boolean): Int {
        val g = p.optString("g"); val ch = p.optString("ch"); val id = p.optString("id")
        val sv = (p.opt("sv") as? Number)?.toLong() ?: return 400
        val text = p.opt("text") as? String ?: return 400
        if (!Proto06.isLogicalId(id) || Proto06.chars(text) > Proto06.MAX_TEXT || text.isBlank()) return 400
        if (!Rich.ok("gmsg", p)) return 400
        val grp = groups[g] ?: return 403
        if (grp.readOnly) return 403
        if (deleted.containsKey(id) || unsentBy[id]?.first == from || chats[g]?.any { it.id == id } == true) { receipt(from, g, listOf(id), "delivered"); return 200 }
        val s = grp.state
        if (!s.has(from) || (s.channel(ch) == null && sv > s.v)) {
            // Maybe they're in a version I haven't seen yet: hold it and catch up.
            if (sv > s.v && !fromHeld) {
                val h = held.getOrPut(g) { ArrayList() }
                if (h.size < Proto06.HELD_PER_GROUP && h.none { it.from == from && it.plain.optString("id") == id }) h.add(Held(from, fromName, p))
                askSync(g, from, s.v)
            }
            return 200
        }
        // Cap g2: someone timed out or banned. Dropped, and answered so their app stops sending it again.
        // Cap g3: not an admin, in an admin-only channel: likewise.
        if (barredLocked(g, from) || adminsOnlyFor(s, ch, from)) { receipt(from, g, listOf(id), "delivered"); return 200 }
        val chId = s.channel(ch)?.takeIf { it.t == "text" }?.id ?: MAIN   // a removed channel's messages show in main
        if (sv < s.v) sendStateBack(g, from) else if (sv > s.v) askSync(g, from, s.v)
        val name = nameIn(s, from, fromName)
        // Cap m2: a poll or link preview; a malformed one is left out and the text shows.
        val m2 = More.ok("gmsg", p)
        // Round 16: a part of a long message is placed by its index among the parts already here, so every member reads
        // them in order even when part 1 came last (it waited for its link preview). A malformed part is shown as is.
        val pt = partOf(p)
        val msg = GMsg(id, g, chId, from, name, text, false, now(), re = p.optString("re"),
            poll = if (m2) More.pollOf(p) else null, preview = if (m2) More.previewOf(p) else null, part = pt?.set ?: "", partAt = pt?.i ?: 0)
        if (pt != null) chats[g] = placePart(chats[g].orEmpty(), msg, now()) else addMsg(msg)
        if (host.held(g, from)) host.heldBy(g, from, name, s.name)
        else {
            unread.add(g)
            // My name, a nickname of mine, or @everyone from an admin; the title names the channel too.
            val where = s.name + (s.channel(chId)?.n?.let { " #$it" } ?: "")
            host.arrived(g, if (Rich.mentionsMe(text, host.myName, host.mentionNames, s.isAdmin(from))) "$name mentioned you in $where" else "$name in $where", text)
        }
        receipt(from, g, listOf(id), "delivered")
        return 200
    }

    /** A photo or voice message whose pieces all came in (Chat put it back together and stored it as [blob]). */
    fun receivedBlob(from: String, fromName: String, g: String, ch: String, gid: String, blob: String, voiceMs: Int,
                     videoMs: Int = 0, album: Triple<String, Int, Int>? = null, gif: Boolean = false): Boolean = act {
        val grp = groups[g]
        if (grp == null || grp.readOnly || !grp.state.has(from) || !Proto06.isLogicalId(gid)) return@act false
        if (deleted.containsKey(gid) || unsentBy[gid]?.first == from || chats[g]?.any { it.id == gid } == true || barredLocked(g, from)
            || adminsOnlyFor(grp.state, ch, from)) {
            receipt(from, g, listOf(gid), "delivered"); return@act false
        }
        val s = grp.state
        val name = nameIn(s, from, fromName)
        val chId = s.channel(ch)?.takeIf { it.t == "text" }?.id ?: MAIN
        addMsg(GMsg(gid, g, chId, from, name, "", false, now(), photo = blob, voice = voiceMs,
            video = videoMs, album = album?.first ?: "", albumAt = album?.second ?: 0, albumOf = album?.third ?: 0, gif = gif))
        if (host.held(g, from)) host.heldBy(g, from, name, s.name)
        else {
            unread.add(g)
            // The title names the channel, as for text.
            host.arrived(g, "$name in ${s.name}" + (s.channel(chId)?.n?.let { " #$it" } ?: ""), when { voiceMs > 0 -> "Voice message"; videoMs > 0 -> "Video message"; gif -> "GIF"; album != null -> "Photos"; else -> "Photo" })
        }
        receipt(from, g, listOf(gid), "delivered")
        true
    }

    /** A reaction or edit (cap m1, already checked) from [from], a member of the current state. */
    private fun takeChange(grp: Group, from: String, fromName: String, p: JSONObject) {
        val g = grp.g
        val msg = p.getString("msg")
        val ts = Rich.ts(p)
        val m = chats[g]?.firstOrNull { it.id == msg && it.unsent.isEmpty() } ?: return
        if (p.getString("t") == "react") {
            val e = p.getString("e")
            val all = Rich.react(m.reactions, from, e, ts) ?: return
            edit(g, msg) { it.copy(reactions = all) }
            if (m.mine && e.isNotEmpty() && !host.held(g, from)) host.arrived(g, "${nameIn(grp.state, from, fromName)} in ${grp.state.name}" +
                (grp.state.channel(m.ch)?.n?.let { " #$it" } ?: ""), "Reacted $e to your message")
        } else {
            if (m.mine || m.photo.isNotEmpty() || !Rich.editWins(m.from, from, ts, m.edited)) return
            val t = p.getString("text")
            edit(g, msg) { it.copy(text = t, edited = ts) }
        }
    }

    private fun greceipt(from: String, p: JSONObject): Int {
        val g = p.optString("g")
        val a = p.optJSONArray("ids") ?: return 400
        val st = p.optString("s")
        if (st != "delivered" && st != "read" && st != "unsent") return 400
        if (a.length() > Proto06.MAX_RECEIPT_IDS) return 400
        val ids = (0 until a.length()).map { a.optString(it) }.filter { Proto06.isLogicalId(it) }.toSet()
        for (id in ids) {
            val env = Proto06.envelopeId(id, from)
            outs.removeAll { (it.env == env).also { x -> if (x) host.drop(env) } }
            tries.remove(env); heldAt.remove(env); publishedAt.remove(env)
        }
        val list = chats[g] ?: return 200
        val t = now()
        chats[g] = list.map { m ->
            if (!m.mine || m.id !in ids || from !in m.to) m
            else when (st) {
                "delivered" -> m.copy(delivered = m.delivered + from, state = "sent",
                    noRead = if (p.optInt("nr") == 1 && from !in m.read) m.noRead + from else m.noRead - from)
                "read" -> m.copy(delivered = m.delivered + from, read = m.read + from, state = "sent", noRead = m.noRead - from,
                    readAt = m.readAt + (from to (m.readAt[from] ?: readTime(p.optLong("at"), m.time, t))))
                else -> m
            }
        }
        return 200
    }

    private fun nameIn(s: State, k: String, fallback: String) = s.member(k)?.n?.ifBlank { null } ?: fallback.take(Proto06.MAX_NAME).ifBlank { "Someone" }

    // ------------------------------------------------------------------ sending and retrying

    /** Confirms again (a copy of something already handled came in: its sender missed my receipt). */
    fun ack(to: String, g: String, ids: List<String>, s: String) = act { receipt(to, g, ids.filter { Proto06.isLogicalId(it) }, s) }

    private fun receipt(to: String, g: String, ids0: List<String>, s: String) {
        // "Delivered" for the same message at most once every few minutes (resends and photo pieces don't each get one).
        val t = now()
        val ids = if (s != "delivered") ids0 else ids0.filter { id ->
            NetLogic.receiptDue(receipted["$to|$id"], t).also { if (it) receipted["$to|$id"] = t }
        }
        if (receipted.size > 2000) receipted.entries.removeIf { t - it.value > NetLogic.RECEIPT_GAP_MS }
        if (ids.isEmpty()) return
        // 3.10: "read" says when; "delivered" says if this app won't send read receipts, so the author shows "Not shown".
        val r = JSONObject().put("g", g).put("ids", JSONArray(ids)).put("s", s)
        if (s == "read") r.put("at", t)
        if (s == "delivered" && !host.readReceipts) r.put("nr", 1)
        queue.add(Out(Proto06.newLogicalId(), to, "greceipt", r.toString(), "", g, now(), retry = false))
    }

    private fun out(to: String, room: String, plain: String, id: String, g: String, photo: String = "", voice: Int = 0, ch: String = "",
                    video: Int = 0, album: Triple<String, Int, Int>? = null, gif: Boolean = false) {
        val o = Out(Proto06.envelopeId(id, to), to, room, plain, id, g, now(), photo = photo, voice = voice, ch = ch, tries = 1,
            video = video, album = album?.first ?: "", albumAt = album?.second ?: 0, albumOf = album?.third ?: 0, gif = gif)
        outs.removeAll { it.env == o.env }
        outs.add(o)
        tries[o.env] = 1 to now() + RETRY[0]
        queue.add(o)
    }

    /** When "delivered" last went back per member and message ("key|id"). */
    private val receipted = HashMap<String, Long>()
    /** Envelopes relays hold (when they first did) and when each last went to them: see [NetLogic]. */
    private val heldAt = HashMap<String, Long>()
    private val publishedAt = HashMap<String, Long>()

    /** Relays hold envelope [env] now: it goes to them again only at 1 hour and 6 hours. */
    fun relaysHold(env: String) = synchronized(lock) {
        if (heldAt.containsKey(env) || outs.none { it.env == env }) return@synchronized
        val t = now()
        heldAt[env] = t
        tries[env]?.let { (n, _) -> tries[env] = n to NetLogic.next(n, t, t) }
    }

    /** [k] just showed up close by or on the same Wi-Fi: what's still on its way to them goes now. */
    fun nudge(k: String) = synchronized(lock) {
        for (o in outs) if (o.to == k) tries[o.env]?.let { (n, _) -> tries[o.env] = n to 0L }
    }

    /** Called every second or so: what to send again now (the same sealed bytes; tries counts up). */
    fun due(): List<Out> = synchronized(lock) {
        val t = now()
        val before = outs.size
        outs.removeAll { o ->
            val stale = t - o.t > DAY || (o.room != "group" && groups[o.g]?.let { it.readOnly || !it.state.has(o.to) } != false)
            if (stale) { host.drop(o.env); tries.remove(o.env); heldAt.remove(o.env); publishedAt.remove(o.env) }
            stale
        }
        val list = outs.mapNotNull { o ->
            val (n, next) = tries[o.env] ?: (0 to 0L)
            if (next > t) null else {
                val held = heldAt[o.env] ?: 0L
                tries[o.env] = (n + 1) to NetLogic.next(n + 1, t, held)
                val net = NetLogic.publish(held, publishedAt[o.env] ?: 0L, t)
                if (net) publishedAt[o.env] = t
                o.copy(tries = n + 1, net = net)
            }
        }
        if (outs.size != before) { purgeLeft(); save(); publish() }
        list
    }

    /** Anything still waiting for a receipt: the retry loop keeps running while there is. */
    fun busy(): Boolean = synchronized(lock) { outs.isNotEmpty() }

    /** When [due] next has something to send (ms; 0: now), Long.MAX_VALUE for nothing waiting. */
    fun nextDue(): Long = synchronized(lock) { outs.minOfOrNull { tries[it.env]?.second ?: 0L } ?: Long.MAX_VALUE }

    /** Envelopes for [to] still waiting for a receipt (tests, and the "sending" state). */
    fun waiting(to: String? = null) = synchronized(lock) { outs.filter { to == null || it.to == to }.map { it.copy() } }

    private inline fun <T> act(f: () -> T): T {
        val r: T
        val send: List<Out>
        synchronized(lock) {
            r = f()
            send = queue.toList(); queue.clear()
            prune()
            purgeLeft()
            save(); publish()
        }
        send.forEach { try { host.send(it) } catch (_: Exception) {} }
        return r
    }

    private fun addMsg(m: GMsg) { chats[m.g] = (chats[m.g].orEmpty() + m).takeLast(1000) }

    /**
     * A longer video (BigVideo) shown in group [g]'s main channel: mine ([from] is me, "sending" until a copy arrives) or one
     * that came through FileLink from Family or trusted [from]. Local only (each copy went as its own file). False if gone.
     */
    fun addBigVideo(g: String, from: String, fromName: String, id: String, ms: Int): Boolean = act {
        val grp = groups[g] ?: return@act false
        if (grp.readOnly || !grp.state.has(from)) return@act false
        val mine = from == me
        val name = if (mine) host.myName else nameIn(grp.state, from, fromName)
        addMsg(GMsg(id, g, MAIN, from, name, "", mine, now(), photo = id, state = if (mine) "sending" else "", video = ms, big = true))
        if (!mine) {
            if (host.held(g, from)) host.heldBy(g, from, name, grp.state.name)
            else { unread.add(g); host.arrived(g, "$name in ${grp.state.name}", "Video") }
        }
        true
    }

    /** A copy of my big video in [g] stopped: "failed" (its card has Retry), unless another copy already arrived. */
    fun bigVideoFailed(g: String, id: String) = act {
        chats[g]?.let { l -> chats[g] = l.map { if (it.id == id && it.mine && it.state == "sending") it.copy(state = "failed") else it } }
    }

    /** A big video of mine in [g] reached someone: "sent". */
    fun bigVideoSent(g: String, id: String) = act {
        chats[g]?.let { l -> chats[g] = l.map { if (it.id == id && it.mine && (it.state == "sending" || it.state == "failed")) it.copy(state = "sent") else it } }
    }
    private fun edit(g: String, id: String, f: (GMsg) -> GMsg) { chats[g] = chats[g].orEmpty().map { if (it.id == id) f(it) else it } }

    private fun prune() {
        val t = now()
        deleted.entries.removeIf { t - it.value > 2 * DAY }
        unsentBy.entries.removeIf { t - it.value.second > 2 * DAY }
        tokens.removeAll { it.x * 1000 < t }
        // Cap j1: links done for a day (expired, used up or revoked) are forgotten.
        links.removeAll { it.off || (it.x != 0L && it.x < t - DAY) || (it.max != 0 && it.uses >= it.max) }
        invites.removeAll { it.x * 1000 < t }
        asked.entries.removeIf { t - it.value > 10 * 60_000 }
        answered.entries.removeIf { t - it.value > 10 * 60_000 }
    }

    /**
     * Messages past each group's limit go (cap d1, section 16), with their photos, voice, videos, votes and previews.
     * [cutoff] gives the oldest time that stays for a limit in days (the "delete sooner" setting may make it later).
     * Their ids are kept as deleted, so a late copy doesn't come back. Nothing if nothing is that old.
     */
    fun pruneOlder(cutoff: (Int) -> Long) {
        val due = synchronized(lock) {
            chats.any { (g, l) -> val c = cutoff(groups[g]?.let { Limits.groupDays(it.state) } ?: Limits.DEFAULT); l.any { it.time < c } }
        }
        if (!due) return
        act {
            val t = now()
            for (g in chats.keys.toList()) {
                val c = cutoff(groups[g]?.let { Limits.groupDays(it.state) } ?: Limits.DEFAULT)
                val (old, keep) = chats[g]!!.partition { it.time < c }
                if (old.isEmpty()) continue
                old.forEach { if (it.photo.isNotEmpty()) host.deleteBlob(it.photo); deleted[it.id] = t }
                val ids = old.map { it.id }.toSet()
                outs.removeAll { (it.g == g && it.id in ids).also { x -> if (x) host.drop(it.env) } }
                chats[g] = keep
            }
        }
    }

    /** Erase all. */
    fun clear() = act {
        groups.clear(); chats.clear(); outs.clear(); tokens.clear(); invites.clear(); joining.clear(); last.clear()
        links.clear(); linkLists.clear(); madeLinks.clear(); mods.clear(); modNames.clear()
        deleted.clear(); unsentBy.clear(); unread.clear(); held.clear(); tries.clear(); heldAt.clear(); publishedAt.clear(); receipted.clear()
    }

    private fun publish() {
        _groups.value = LinkedHashMap(groups.filterValues { it.gone != LEAVING })
        _chats.value = HashMap(chats)
        _invites.value = invites.toList()
        _links.value = groups.keys.associateWith { g -> linkLists[g].orEmpty().filter { (it.x == 0L || it.x > now()) && (it.max == 0 || it.uses < it.max) } }
        // Cap g3: unread reports keep the group's dot on until the Reports list shows them.
        _unread.value = unread.toSet() + reports.filterValues { l -> l.any { !it.read } }.keys
        joinNames.keys.retainAll(joining.keys)
        refusals.keys.retainAll(joining.keys)
        _waiting.value = joinNames.filterKeys { groups[it]?.let { grp -> !grp.readOnly && grp.state.has(me) } != true }
            .map { (g, n) -> Waiting(g, n.first, n.second, refusals[g].orEmpty()) }
        reports.keys.retainAll(groups.keys)
        _reports.value = HashMap(reports)
    }

    // ------------------------------------------------------------------ storage

    private fun save() {
        try {
            val o = JSONObject()
            o.put("groups", JSONArray().apply { groups.values.forEach { gr ->
                put(JSONObject().put("s", gr.state.json()).put("h", JSONArray().apply { gr.history.forEach { put(it.json()) } }).put("gone", gr.gone))
            } })
            o.put("tokens", JSONArray().apply { tokens.forEach { put(JSONObject().put("i", it.i).put("g", it.g).put("x", it.x).put("m", it.multi)) } })
            o.put("invites", JSONArray().apply { invites.forEach { put(JSONObject().put("g", it.g).put("n", it.name).put("f", it.from).put("fn", it.fromName)
                .put("i", it.inv).put("x", it.x).put("s", it.state.json())) } })
            o.put("joining", JSONObject().apply { joining.forEach { (g, k) -> put(g, k) } })
            o.put("last", JSONObject().apply { last.forEach { (g, c) -> put(g, JSONObject().put("op", c.op).put("a", c.a).put("b", c.b).put("c", c.c).put("v", c.v)) } })
            o.put("deleted", JSONObject().apply { deleted.forEach { (k, t) -> put(k, t) } })
            o.put("unsentBy", JSONObject().apply { unsentBy.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } })
            o.put("unread", JSONArray(unread.toList()))
            o.put("links", JSONArray().apply { links.forEach { put(JSONObject().put("i", it.i).put("g", it.g).put("by", it.by).put("x", it.x)
                .put("max", it.max).put("uses", it.uses).put("off", it.off)) } })
            o.put("made", JSONObject().apply { madeLinks.forEach { (k, v) -> put(k, v) } })
            o.put("mods", JSONObject().apply { mods.forEach { (g, who) -> put(g, JSONObject().apply { who.forEach { (k, slots) ->
                put(k, JSONObject().apply { slots.forEach { (slot, rec) -> put(slot, rec) } }) } }) } })
            o.put("modNames", JSONObject().apply { modNames.forEach { (k, v) -> put(k, v) } })
            o.put("refused", JSONObject().apply { refusals.forEach { (g, w) -> put(g, w) } })
            o.put("reports", JSONObject().apply { reports.forEach { (g, l) -> put(g, JSONArray().apply { l.forEach { r ->
                put(JSONObject().put("id", r.id).put("f", r.from).put("fn", r.fromName).put("k", r.k).put("kn", r.kName).put("m", r.msg)
                    .put("ch", r.ch).put("x", r.text).put("t", r.time).put("rd", r.read)) } }) } })
            o.put("lists", JSONObject().apply { linkLists.forEach { (g, l) -> put(g, JSONArray().apply { l.forEach { put(JSONObject().put("id", it.id)
                .put("by", it.by).put("bn", it.byName).put("x", it.x).put("max", it.max).put("uses", it.uses).put("c", it.counter)) } }) } })
            writeIfChanged(FILE, o.toString())
            val c = JSONObject()
            chats.forEach { (g, l) -> c.put(g, JSONArray().apply { l.forEach { put(msgJson(it)) } }) }
            writeIfChanged(CHATS, c.toString())
            writeIfChanged(OUTS, JSONArray().apply { outs.forEach { put(JSONObject().put("e", it.env).put("to", it.to).put("r", it.room).put("p", it.plain).put("id", it.id)
                .put("g", it.g).put("t", it.t).put("ph", it.photo).put("vo", it.voice).put("ch", it.ch)
                .apply { if (it.video > 0) put("vd", it.video); if (it.album.isNotEmpty()) put("al", it.album).put("ai", it.albumAt).put("an", it.albumOf)
                    if (it.gif) put("gif", true) }) } }.toString())
        } catch (_: Exception) {}
    }

    private fun msgJson(m: GMsg) = JSONObject().put("id", m.id).put("g", m.g).put("ch", m.ch).put("f", m.from).put("n", m.name).put("x", m.text)
        .put("m", m.mine).put("t", m.time).put("ph", m.photo).put("vo", m.voice).put("u", m.unsent).put("st", m.state)
        .put("to", JSONArray(m.to.toList())).put("dl", JSONArray(m.delivered.toList())).put("rd", JSONArray(m.read.toList())).put("sn", m.seen)
        .apply { if (m.re.isNotEmpty()) put("re", m.re); if (m.reactions.isNotEmpty()) put("rx", Rich.reactionsToJson(m.reactions)); if (m.edited > 0) put("ed", m.edited) }
        .apply {
            if (m.video > 0) put("vd", m.video)
            if (m.album.isNotEmpty()) put("al", m.album).put("ai", m.albumAt).put("an", m.albumOf)
            if (m.part.isNotEmpty()) put("ps", m.part).put("pi", m.partAt)
            m.poll?.let { put("poll", More.pollJson(it)) }
            if (m.votes.isNotEmpty()) put("vt", More.votesToJson(m.votes))
            m.preview?.let { put("lp", More.previewJson(it)) }
            if (m.readAt.isNotEmpty()) put("ra", JSONObject().apply { m.readAt.forEach { (k, v) -> put(k, v) } })
            if (m.noRead.isNotEmpty()) put("nrd", JSONArray(m.noRead.toList()))
            if (m.gif) put("gif", true)
            if (m.big) put("vb", true)
        }

    private fun strings(a: JSONArray?): Set<String> = if (a == null) emptySet() else (0 until a.length()).map { a.getString(it) }.toSet()

    private fun load() {
        try {
            read(FILE)?.let { text ->
                val o = JSONObject(text)
                o.optJSONArray("groups")?.let { a -> for (i in 0 until a.length()) {
                    val x = a.getJSONObject(i)
                    val s = Proto06.parseState(x.getJSONObject("s"))
                    val h = x.optJSONArray("h")?.let { hs -> (0 until hs.length()).map { Proto06.parseState(hs.getJSONObject(it)) } }.orEmpty()
                    groups[s.g] = Group(s, h, x.optString("gone"))
                } }
                o.optJSONArray("tokens")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { tokens.add(Token(it.getString("i"), it.getString("g"), it.getLong("x"), it.optBoolean("m"))) } }
                o.optJSONArray("invites")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
                    invites.add(Invite(it.getString("g"), it.optString("n"), it.getString("f"), it.optString("fn"), it.optString("i"), it.optLong("x"), Proto06.parseState(it.getJSONObject("s"))))
                } }
                o.optJSONObject("joining")?.let { j -> j.keys().forEach { joining[it] = j.getString(it) } }
                o.optJSONObject("last")?.let { j -> j.keys().forEach { g -> j.getJSONObject(g).let { c ->
                    last[g] = Change(c.getString("op"), c.optString("a"), c.optString("b"), c.optString("c"), c.optLong("v")) } } }
                o.optJSONObject("deleted")?.let { j -> j.keys().forEach { deleted[it] = j.optLong(it) } }
                o.optJSONObject("unsentBy")?.let { j -> j.keys().forEach { k -> j.optJSONArray(k)?.let { a -> unsentBy[k] = a.optString(0) to a.optLong(1) } } }
                unread.addAll(strings(o.optJSONArray("unread")))
                o.optJSONObject("refused")?.let { j -> j.keys().forEach { g -> j.optString(g).takeIf { it in REFUSALS }?.let { refusals[g] = it } } }
                o.optJSONObject("reports")?.let { j -> j.keys().forEach { g -> j.optJSONArray(g)?.let { a ->
                    reports[g] = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { r ->
                        Report(r.optString("id"), g, r.optString("f"), r.optString("fn"), r.optString("k"), r.optString("kn"), r.optString("m"),
                            r.optString("ch"), r.optString("x"), r.optLong("t"), r.optBoolean("rd")) } }
                } } }
                o.optJSONObject("made")?.let { j -> j.keys().forEach { madeLinks[it] = j.getString(it) } }
                // Cap g2: kept as they were taken (the signature was checked when each came); the shape is checked again.
                o.optJSONObject("mods")?.let { j -> j.keys().forEach { g -> val who = j.getJSONObject(g); who.keys().forEach { k ->
                    val slots = who.getJSONObject(k)
                    slots.keys().forEach { slot -> val rec = slots.getJSONObject(slot)
                        if (ModLogic.check(rec).isEmpty() && ModLogic.slot(rec) == slot && rec.getString("g") == g && rec.getString("k") == k)
                            mods.getOrPut(g) { HashMap() }.getOrPut(k) { HashMap() }[slot] = rec }
                } } }
                o.optJSONObject("modNames")?.let { j -> j.keys().forEach { modNames[it] = j.getString(it) } }
                o.optJSONArray("links")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let {
                    links.add(LinkRec(it.getString("i"), it.getString("g"), it.getString("by"), it.optLong("x"), it.optInt("max"), it.optInt("uses"), it.optBoolean("off"))) } }
                o.optJSONObject("lists")?.let { j -> j.keys().forEach { g -> val a = j.getJSONArray(g); linkLists[g] = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    LinkInfo(it.getString("id"), g, it.getString("by"), it.optString("bn"), it.optLong("x"), it.optInt("max"), it.optInt("uses"), it.getString("c"),
                        madeLinks[it.getString("id")] ?: "") } } }
            }
            read(CHATS)?.let { text ->
                val o = JSONObject(text)
                o.keys().forEach { g -> val a = o.getJSONArray(g); chats[g] = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    GMsg(it.getString("id"), it.getString("g"), it.optString("ch", MAIN), it.getString("f"), it.optString("n"), it.optString("x"), it.optBoolean("m"),
                        it.optLong("t"), it.optString("ph"), it.optInt("vo"), it.optString("u"), it.optString("st"),
                        strings(it.optJSONArray("to")), strings(it.optJSONArray("dl")), strings(it.optJSONArray("rd")), it.optBoolean("sn"),
                        it.optString("re"), Rich.reactionsFromJson(it.optJSONObject("rx")), it.optLong("ed"),
                        it.optInt("vd"), it.optString("al"), it.optInt("ai"), it.optInt("an"),
                        More.pollOf(it), More.votesFromJson(it.optJSONObject("vt")), More.previewOf(it),
                        it.optJSONObject("ra")?.let { ra -> ra.keys().asSequence().associateWith { k -> ra.optLong(k) } }.orEmpty(),
                        strings(it.optJSONArray("nrd")), gif = it.optBoolean("gif"), part = it.optString("ps"), partAt = it.optInt("pi"), big = it.optBoolean("vb"))
                } }
            }
            read(OUTS)?.let { text ->
                val a = JSONArray(text)
                for (i in 0 until a.length()) a.getJSONObject(i).let {
                    outs.add(Out(it.getString("e"), it.getString("to"), it.getString("r"), it.optString("p"), it.optString("id"), it.getString("g"), it.optLong("t"),
                        photo = it.optString("ph"), voice = it.optInt("vo"), ch = it.optString("ch"),
                        video = it.optInt("vd"), album = it.optString("al"), albumAt = it.optInt("ai"), albumOf = it.optInt("an"), gif = it.optBoolean("gif")))
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        /** [list] with [m] after the last message not newer than it (a part sent late with its earlier time). */
        /** The part a gmsg says it is ("ps", "pi", "pn"), or null when it says none or something unusable. */
        fun partOf(p: JSONObject): TextPart? {
            val set = p.opt("ps") as? String ?: return null
            val i = (p.opt("pi") as? Int) ?: return null
            val n = (p.opt("pn") as? Int) ?: return null
            if (!Proto06.isLogicalId(set) || n !in 2..Drafts.MAX_PARTS || i !in 1..n) return null
            return TextPart(set, i, n)
        }

        /** Round 17 (as Huginn 1.41): a part is placed only among this many latest messages ... */
        const val PART_WINDOW = 200
        /** ... and (a [now] given) only beside a part under this old; else it goes last. */
        const val PART_AGE_MS = 60 * 60_000L

        /** Whether a part made at [time] (ms, or seconds from an old app) is too old at [now] (0: no age check) to place beside. */
        fun partTooOld(time: Long, now: Long): Boolean {
            if (now <= 0L) return false
            val t = if (time in 1L until 100_000_000_000L) time * 1000 else time
            return t > 0L && kotlin.math.abs(now - t) > PART_AGE_MS
        }

        /**
         * [m], a part of a long message, goes before the first of the same sender's parts of that set with a higher
         * place (they came first), looked for only among the last [PART_WINDOW] messages; when that part is over
         * [PART_AGE_MS] old at [now], or there is none, [m] goes last. Everything else keeps its place.
         */
        fun placePart(list: List<GMsg>, m: GMsg, now: Long = 0L): List<GMsg> {
            val from = maxOf(0, list.size - PART_WINDOW)
            var at = -1
            if (m.part.isNotEmpty()) for (i in from until list.size) {
                val x = list[i]
                if (x.from != m.from || x.part != m.part || x.partAt <= m.partAt) continue
                at = if (partTooOld(x.time, now)) -1 else i
                break
            }
            return (if (at < 0) list + m else list.subList(0, at) + m + list.subList(at, list.size)).takeLast(1000)
        }

        fun inOrder(list: List<GMsg>, m: GMsg): List<GMsg> {
            val at = list.indexOfLast { it.time <= m.time } + 1
            return (list.subList(0, at) + m + list.subList(at, list.size)).takeLast(1000)
        }
        /** [GMsg.unsent] of a message an admin removed for everyone (no name holds a control character). */
        const val REMOVED = "\u0001admin"
        /** Cap g4 (28.5): why a join wasn't taken. */
        val REFUSALS = setOf("full", "update", "banned", "other")
        fun refusalText(why: String, group: String): String = when (why) {
            "full" -> "$group is full (${Proto06.MAX_MEMBERS} people)."
            "update" -> "Update Muninn to join: $group uses something your version doesn't have yet."
            "banned" -> "An admin of $group has stopped you from joining."
            else -> "The admin's app couldn't add you. Ask them about it."
        }
        /** "Taylor and Alex need the newest Huginn or Muninn first. Ask them to update, then try again." */
        fun needUpdate(names: List<String>): String {
            val n = names.map { it.ifBlank { "Someone" } }
            val list = if (n.size <= 1) n.joinToString("") else n.dropLast(1).joinToString(", ") + " and " + n.last()
            return "$list need${if (n.size == 1) "s" else ""} the newest Huginn or Muninn first. Ask them to update, then try again."
        }
        /** Cap g3 (28.1): the most paragraphs in a group's about text. */
        const val MAX_ABOUT_PARAGRAPHS = 10
        /**
         * Cap g3 (28.1): about text from the editor as it goes in the state. Paragraphs are joined by U+2029 PARAGRAPH
         * SEPARATOR (a line break is a control character, which canonical JSON can't hold); each is cleaned like a name.
         */
        fun aboutWire(text: String): String {
            val parts = text.replace('\u2029', '\n').replace('\u2028', '\n').replace('\r', '\n').split('\n')
                .map { clean(it.replace(Regex("\\s+"), " "), Proto06.MAX_ABOUT).trim() }.filter { it.isNotEmpty() }
            val kept = if (parts.size <= MAX_ABOUT_PARAGRAPHS) parts else parts.take(MAX_ABOUT_PARAGRAPHS - 1) + parts.drop(MAX_ABOUT_PARAGRAPHS - 1).joinToString(" ")
            return clean(kept.joinToString("\u2029"), Proto06.MAX_ABOUT).trim()
        }
        /** The about text for the screens: one paragraph a line. */
        fun aboutShown(about: String): String = about.replace('\u2029', '\n')
        const val FILE = "groups.json"
        const val CHATS = "gchats.json"
        const val OUTS = "gouts.json"
        /** [Group.gone] after Leave (one step): hidden, kept only to deliver the leave. */
        const val LEAVING = "leaving"
        const val DAY = 86_400_000L
        /** 5 s, 10 s, 20 s, 1 min, then every 5 min, like private messages (until relays hold it, see NetLogic). */
        val RETRY = NetLogic.RETRY
        /** Names and text from others: no control characters, at most [limit] characters. */
        fun clean(s: String, limit: Int): String {
            val t = s.filter { it.code >= 0x20 && it.code != 0x7F }.trim()
            return if (Proto06.chars(t) <= limit) t else t.substring(0, t.offsetByCodePoints(0, limit))
        }
        private fun mailbox(p: String) = if (Proto06.isMailbox(p)) p else ""
        /** When a "read" receipt says it was read: [at] from the reader (0 when not sent), kept between the message's time and [now]. */
        fun readTime(at: Long, sent: Long, now: Long): Long = if (at <= 0) now else at.coerceIn(minOf(sent, now), now)

        /** "Read by" for a message of mine: who read it (earliest first) and when, who has it, whose app doesn't show reads, who doesn't have it yet. */
        fun readBy(m: GMsg): ReadBy {
            val read = m.to.filter { it in m.read }.map { it to (m.readAt[it] ?: 0L) }.sortedWith(compareBy({ it.second }, { it.first }))
            val rest = m.to.filter { it !in m.read }
            val hidden = rest.filter { it in m.noRead }
            return ReadBy(read, rest.filter { it in m.delivered && it !in m.noRead }.sorted(), hidden.sorted(), rest.filter { it !in m.delivered && it !in m.noRead }.sorted())
        }

        /** Mine: "Sending", "Delivered to 4 of 6", "Read by 2". */
        fun status(m: GMsg): String = when {
            !m.mine || m.unsent.isNotEmpty() -> ""
            m.to.isEmpty() -> "Sent"
            m.read.isNotEmpty() -> if (m.read.size >= m.to.size) "Read by everyone" else "Read by ${m.read.size}"
            m.delivered.isNotEmpty() -> if (m.delivered.size >= m.to.size) "Delivered" else "Delivered to ${m.delivered.size} of ${m.to.size}"
            else -> "Sending"
        }
    }
}
