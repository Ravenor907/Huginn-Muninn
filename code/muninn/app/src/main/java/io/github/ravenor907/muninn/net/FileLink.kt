package io.github.ravenor907.muninn.net

import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.Stun
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Files over the internet, straight from one phone to the other (cap f1, shared/docs/protocol.md section 14). Never
 * through Cloudflare's call relay: both phones learn their own internet address from a STUN server, swap it in a sealed
 * `file` message, and punch through their routers by sending to each other at once, as the Direct path of calls does
 * (5.8.2). Only between two people who trust each other with their addresses ("Direct calls" on, as for Family).
 * Then the file goes in numbered pieces, each sealed with AES-256-GCM under a key only the two phones know, and the
 * receiver says which pieces it has; lost ones go again. Small files that can't go direct use the relays (Chat).
 * Cap f2 (section 27): the same Wi-Fi first (a "lan" candidate with no address), group files, why a file was refused,
 * and Pause.
 */
object FileLink {
    const val CAP = "f1"
    const val CHUNK = 1100
    const val WINDOW = 96
    const val RTO_MS = 500L
    const val PUNCH_MS = 20_000L
    const val IDLE_MS = 30_000L
    /**
     * Cap f3 (27.7): resume and long waits. Silence ends a transfer only after [LONG_IDLE_MS]; after [QUIET_MS] of it each
     * side sends one packet a second (a probe or an ack) until it hears the other again.
     */
    const val RESUME_CAP = "f3"
    const val LONG_IDLE_MS = 120_000L
    const val QUIET_MS = 2_000L
    /** Cap f3: a stopped file of theirs is kept to go on from for 7 days, across a restart too (as Huginn's kKeepDays). */
    const val KEEP_DAYS = 7
    const val KEEP_MS = KEEP_DAYS * 24 * 3_600_000L
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    /** From someone trusted, a file smaller than this arrives by itself; a bigger one asks Accept or Decline first. */
    const val AUTO_BYTES = 50L * 1024 * 1024
    /** How long an offer waits for an answer: longer for a file the other phone asks about first. */
    const val ANSWER_MS = 120_000L
    const val ASK_ANSWER_MS = 10 * 60_000L
    /** Cap f3: a big file waits a day for Accept or Decline (both apps say f3). */
    const val LONG_ANSWER_MS = 24 * 3_600_000L

    /**
     * A sender whose app says this cap waits [ASK_ANSWER_MS] for a big file, so the receiver may ask first. An older
     * sender gives up after [ANSWER_MS]: its big files are taken as before, without asking.
     */
    const val ASK_CAP = RingWire.WRAP_CAP

    /** A file of [size] asks Accept or Decline first: big, and the sender waits for that ([senderWaits]). */
    fun asksFirst(size: Long, senderWaits: Boolean): Boolean = size >= AUTO_BYTES && senderWaits

    /** An offered file of [size] downloads by itself (small enough or from an older sender, and Data saver doesn't say wait). */
    fun arrivesByItself(size: Long, mayDownload: Boolean, senderWaits: Boolean = true): Boolean = !asksFirst(size, senderWaits) && mayDownload

    /** A file small enough for the internet route goes that way when no answer comes this soon (the other phone may be off). */
    const val RELAY_AFTER_MS = 30_000L

    /**
     * How long the sender waits for an answer to a file of [size]. One small enough for the internet route then goes
     * that way (it arrives when they're back); a bigger one waits as long as the other side may ask first (Data
     * saver, or a big file).
     */
    fun answerMs(size: Long, long: Boolean = false): Long = if (size <= FileText.RELAY_MAX) RELAY_AFTER_MS else if (long) LONG_ANSWER_MS else ASK_ANSWER_MS

    const val PROBE: Byte = 1
    const val DATA: Byte = 2
    const val ACK: Byte = 3
    const val DONE: Byte = 4
    /** Cap f2 (27.5): 1 byte, 1 paused or 0 going on. Either side sends it every second while paused. */
    const val HOLD: Byte = 5

    // ------------------------------------------------------------------ the parts that are the same everywhere (tested)

    /** What the sender keeps: which pieces went when, which are confirmed. */
    class Sender(val pieces: Int) {
        private val acked = BitSet(pieces)
        private val sentAt = HashMap<Int, Long>()
        private var next = 0
        val done get() = acked.cardinality() == pieces

        /** The pieces to send now: lost ones again first (older than [RTO_MS]), then new ones while the window has room. */
        @Synchronized fun due(now: Long, rto: Long = RTO_MS): List<Int> {
            val out = ArrayList<Int>()
            for ((i, t) in sentAt) if (!acked[i] && now - t >= rto) out += i
            out.sort()
            var inflight = sentAt.count { !acked[it.key] }
            while (next < pieces && inflight < WINDOW) {
                // Cap f3: pieces the receiver already had from an earlier try are acked before they go.
                if (acked[next]) { next++; continue }
                out += next; next++; inflight++
            }
            out.forEach { sentAt[it] = now }
            return out
        }

        /** The receiver has everything below [below], and of the 64 after it the ones set in [bits]. */
        @Synchronized fun ack(below: Int, bits: Long) {
            for (i in 0 until minOf(below, pieces)) if (!acked[i]) { acked.set(i); sentAt.remove(i) }
            for (b in 0 until 64) if ((bits ushr b) and 1L == 1L) { val i = below + b; if (i in 0 until pieces) { acked.set(i); sentAt.remove(i) } }
        }
    }

    /** What the receiver keeps: which pieces are in. */
    class Receiver(val pieces: Int) {
        private val got = BitSet(pieces)
        val done get() = got.cardinality() == pieces
        /** True when it's new. */
        @Synchronized fun take(i: Int): Boolean { if (i !in 0 until pieces || got[i]) return false; got.set(i); return true }
        /** Cap f3: the first [below] pieces are already on disk from a try that stopped. */
        @Synchronized fun preload(below: Int) { got.set(0, below.coerceIn(0, pieces)) }
        /** Everything below the first gap, and which of the 64 after it are in. */
        @Synchronized fun ack(): Pair<Int, Long> {
            val below = got.nextClearBit(0).coerceAtMost(pieces)
            var bits = 0L
            for (b in 0 until 64) if (below + b < pieces && got[below + b]) bits = bits or (1L shl b)
            return below to bits
        }
    }

    fun pieces(size: Long) = ((size + CHUNK - 1) / CHUNK).toInt().coerceAtLeast(1)

    /** The transfer's key, from both one-off keys and its id: only these two phones can work it out. */
    fun key(shared: ByteArray, id: String): ByteArray = MessageDigest.getInstance("SHA-256").digest("muninn file v1".toByteArray() + shared + id.toByteArray())

    /** A packet: type, sequence number, then the sealed payload. [dir] 0 from the sender, 1 from the receiver. */
    fun seal(key: ByteArray, id: String, type: Byte, dir: Int, seq: Long, payload: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(dir, seq)))
        c.updateAAD(byteArrayOf(type) + id.toByteArray())
        return byteArrayOf(type) + ByteBuffer.allocate(8).putLong(seq).array() + c.doFinal(payload)
    }

    /** (type, payload), or null when it isn't ours or was changed on the way. */
    fun open(key: ByteArray, id: String, dir: Int, p: ByteArray, len: Int = p.size): Pair<Byte, ByteArray>? {
        if (len < 9 + 16) return null
        val type = p[0]
        val seq = ByteBuffer.wrap(p, 1, 8).long
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce(dir, seq)))
            c.updateAAD(byteArrayOf(type) + id.toByteArray())
            type to c.doFinal(p, 9, len - 9)
        } catch (e: Exception) { null }
    }

    private fun nonce(dir: Int, seq: Long) = ByteBuffer.allocate(12).putInt(dir).putLong(seq).array()

    /** An offer or accept is well-formed (section 14). */
    fun ok(p: JSONObject): Boolean {
        val id = p.opt("id") as? String ?: return false
        if (!Proto06.isLogicalId(id)) return false
        // Cap f2 (27.2): a group's file names its group.
        if (p.has("g") && (p.opt("g") as? String)?.let { it.length == 22 && Proto06.lenB64url(it) == 16 } != true) return false
        return when (p.opt("t")) {
            "offer" -> (p.opt("name") as? String)?.let { it.isNotBlank() && it.length <= 200 && it.none { c -> c.code < 0x20 || c == '/' || c == '\\' } } == true &&
                (p.opt("size") as? Number)?.toLong()?.let { it in 1..MAX_BYTES } == true &&
                (p.opt("sha") as? String)?.let { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } } == true &&
                p.opt("e") is String && p.opt("c") is JSONArray
            "accept" -> p.opt("e") is String && p.opt("c") is JSONArray
            "decline", "done" -> true
            else -> false
        }
    }

    // ------------------------------------------------------------------ transfers

    /**
     * One transfer, as the chat and Transfers show it. [state]: "offered", "asking" (Accept or Decline), "waiting" (data
     * saver), "connecting", "sending", "done", "failed". [group]: a group's file (cap f2). [route]: "Same Wi-Fi", "Straight to them" or "Over the
     * internet" (empty until known). [pace]: "2.1 MB/s · 12 s left". [folder]: where theirs goes, as words. [relay]: a
     * file of mine going as a message over the internet route (its message shows it in the chat).
     */
    data class Progress(val id: String, val peer: String, val name: String, val size: Long, val mine: Boolean, val state: String,
                        val done: Long = 0, val note: String = "",
                        /** Theirs, once saved: where it is in Downloads (a content URI), for Open. */
                        val uri: String = "",
                        val group: String = "", val route: String = "", val pace: String = "", val paused: Boolean = false,
                        val held: Boolean = false, val folder: String = "", val relay: Boolean = false,
                        val time: Long = System.currentTimeMillis()) {
        /** The chat it belongs to: their key, or "g:<group>". */
        val where: String get() = if (group.isEmpty()) peer else "g:$group"
        val kind: String get() = Sharing.kindOf(name)
    }

    /** A file saved in Downloads: the name it got and its content URI. */
    data class Saved(val name: String, val uri: String)

    private val _all = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val all: StateFlow<Map<String, Progress>> = _all.asStateFlow()

    /** Paused here, held by them (27.5), moving, when bytes began to move and for how long it was paused. */
    private val pausedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val heldIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val movingIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val resumed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val started = ConcurrentHashMap<String, Long>()
    private val pausedAt = ConcurrentHashMap<String, Long>()
    private val pausedFor = ConcurrentHashMap<String, Long>()
    /** Cap f3: bytes already there when it began (a resumed file), left out of the speed. */
    private val base = ConcurrentHashMap<String, Long>()

    /** What a card keeps from before (its chat, route, folder, when it began) and what it shows now (paused, pace). */
    @Synchronized private fun put(p: Progress) {
        val old = _all.value[p.id]
        val merged = if (old == null) p else p.copy(group = p.group.ifEmpty { old.group }, route = p.route.ifEmpty { old.route },
            folder = p.folder.ifEmpty { old.folder }, relay = p.relay || old.relay, time = old.time)
        val paused = p.id in pausedIds
        val held = p.id in heldIds
        val since = started[p.id]
        val pace = if (merged.state == "sending" && since != null && !paused && !held)
            (base[p.id] ?: 0L).let { b -> FileText.pace(merged.done - b, merged.size - b, System.currentTimeMillis() - since - (pausedFor[p.id] ?: 0L)) } else ""
        _all.value = _all.value + (p.id to merged.copy(paused = paused, held = held, pace = pace))
    }
    private fun refresh(id: String) { _all.value[id]?.let { put(it) } }

    interface Host {
        /** Seal [json] (room "file") for [peer] and send it on every route. */
        fun signal(peer: String, json: String)
        fun trusted(peer: String): Boolean
        fun hasF1(peer: String): Boolean
        /** Their app waits for a big file to be accepted ([ASK_CAP]). */
        fun waitsForAsk(peer: String): Boolean = true
        /** In one of my Family groups (protocol.md 16.2): every file of theirs arrives by itself, whatever its size. */
        fun allArrive(peer: String): Boolean = false
        /** A whole file arrived and checked out: put it in Downloads. */
        fun save(name: String, file: File): Saved?
        /** No straight path: send a small [file] over the internet route instead (it then shows as a message). Null when on its way. */
        fun relay(peer: String, file: File, name: String): String? = "Not ready."
        fun cacheDir(): File
        fun notice(text: String)
        /** Their name as this phone shows it. */
        fun nameOf(peer: String): String = "Someone"
        /** May it download now (Data saver on mobile data says wait)? */
        fun mayDownload(): Boolean
        // Cap f2 (section 27).
        /** Where they are on this Wi-Fi (a device whose chat key proved its certificate): never shown or sent. */
        fun lanAddress(peer: String): InetAddress? = null
        fun hasF2(peer: String): Boolean = false
        /** Empty when a file of [size] from [from] is welcome in group [g] (its file rules), else why not. */
        fun groupRefusal(g: String, from: String, size: Long): String = "Refused: you aren't in that group."
        /** A group's own file arrives by itself (its type: Folk small ones, Family all). */
        fun groupArrives(g: String, size: Long): Boolean = false
        fun groupName(g: String): String = "Group"
        /** "Always from this person": their files arrive without asking. */
        fun alwaysFrom(peer: String): Boolean = false
        fun setAlways(peer: String) {}
        fun isContact(peer: String): Boolean = true
        /** Free space where received files go. */
        fun freeBytes(): Long = Long.MAX_VALUE
        /** Saves into Download/Muninn/[folder] (Sharing.folderFor). */
        fun saveIn(name: String, file: File, folder: String): Saved? = save(name, file)
        /** The chat's Files list: a file sent or received there. */
        fun logFile(where: String, name: String, size: Long, uri: String, mine: Boolean) {}
        /** Cap f3 (27.7): their app keeps a stopped file to go on from, and waits a day for a big file's answer. */
        fun resumes(peer: String): Boolean = false
        /** A file that asks Accept or Decline first: a notification, as a message gets ([where]: its chat). */
        fun offered(where: String, from: String, text: String) {}
        /** Cap f3: the kept parts' list (keptparts.json in the vault), so they last across a restart. Null: none. */
        fun readKept(): String? = null
        fun writeKept(text: String) {}
    }

    @Volatile var host: Host? = null
    private val work = Executors.newCachedThreadPool { r -> Thread(r, "muninn-file").apply { isDaemon = true } }
    private class Out(val peer: String, val file: File, val name: String, val size: Long, val sha: String, val keys: java.security.KeyPair,
                      val sock: DatagramSocket, val cands: List<InetSocketAddress>, val group: String = "", val lanOnly: Boolean = false)
    private class In(val peer: String, val name: String, val size: Long, val sha: String, val theirKey: String, val theirCands: List<InetSocketAddress>,
                     val group: String = "", val lanOnly: Boolean = false, val lan: InetSocketAddress? = null)
    private val outs = ConcurrentHashMap<String, Out>()
    private val ins = ConcurrentHashMap<String, In>()
    /** Stopped with Cancel: its threads end quietly. */
    private val stopped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Mine, while it can be sent again (Retry): who to, the file and its name, and its group. */
    private val again = ConcurrentHashMap<String, Triple<String, File, String>>()
    private val againGroup = ConcurrentHashMap<String, String>()
    /** Cap f3: a file of theirs that stopped part way, kept to go on from for [KEEP_DAYS] (across a restart): by its card's id. */
    private class Partial(val peer: String, val sha: String, val size: Long, val group: String, val file: File, val below: Int,
                          val name: String = "", val time: Long = System.currentTimeMillis())
    private val partials = ConcurrentHashMap<String, Partial>()
    private fun partialFor(peer: String, sha: String, size: Long, group: String): String? =
        if (host?.resumes(peer) != true) null
        else partials.entries.firstOrNull { (_, p) -> p.peer == peer && p.sha == sha && p.size == size && p.group == group && p.file.isFile }?.key
    private fun stoppedNote(p: Partial, who: String) =
        "Stopped at ${FileText.percent(minOf(p.size, p.below.toLong() * CHUNK), p.size)}% (no answer from $who). It goes on from there if they send it again in the next $KEEP_DAYS days."

    /**
     * Cap f3: the kept parts from before a restart (and their cards), read once the vault is open. Older than
     * [KEEP_DAYS], or with the part file gone: dropped.
     */
    fun loadKept() {
        val h = host ?: return
        val a = try { h.readKept()?.let { JSONObject(it).optJSONArray("parts") } } catch (_: Exception) { null } ?: return
        val oldest = System.currentTimeMillis() - KEEP_MS
        var dropped = false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("file")
            // Only a part file of ours, by its plain name in the cache: nothing in the list can point elsewhere.
            val f = if (name.startsWith("incoming-") && !name.contains('/') && !name.contains("..")) File(h.cacheDir(), name) else null
            if (id.isEmpty() || partials.containsKey(id)) continue
            if (f == null || !f.isFile || o.optLong("time") < oldest) { f?.delete(); dropped = true; continue }
            val p = Partial(o.optString("peer"), o.optString("sha"), o.optLong("size"), o.optString("group"), f, o.optInt("below"),
                o.optString("name"), o.optLong("time"))
            if (p.peer.isEmpty() || p.sha.isEmpty() || p.size <= 0) { f.delete(); dropped = true; continue }
            partials[id] = p
            if (_all.value[id] == null) _all.value = _all.value + (id to Progress(id, p.peer, p.name.ifEmpty { "A file" }, p.size, false, "failed",
                minOf(p.size, p.below.toLong() * CHUNK), note = stoppedNote(p, h.nameOf(p.peer)), group = p.group, time = p.time))
        }
        if (dropped) saveKept()
    }

    /** Cap f3: writes the kept parts' list (when one comes or goes). */
    private fun saveKept() {
        val h = host ?: return
        val a = JSONArray()
        for ((id, p) in partials) a.put(JSONObject().put("id", id).put("peer", p.peer).put("sha", p.sha).put("size", p.size).put("group", p.group)
            .put("file", p.file.name).put("below", p.below).put("name", p.name).put("time", p.time))
        try { h.writeKept(JSONObject().put("parts", a).toString()) } catch (_: Exception) {}
    }

    /** A file of mine already hashed (path, size, last change): Retry doesn't read it all again. */
    private val hashed = ConcurrentHashMap<String, Triple<Long, Long, String>>()

    /** The chat's name for a card: the person, or the group. */
    fun chatOf(p: Progress): String = if (p.group.isEmpty()) host?.nameOf(p.peer) ?: "Someone" else host?.groupName(p.group) ?: "Group"

    /** A file of mine being made ready (copied in): its card shows at once. Returns its id for [send]. */
    fun preparing(peer: String, name: String, size: Long, group: String = ""): String {
        val id = Proto06.newLogicalId()
        put(Progress(id, peer, name, size, true, "connecting", group = group))
        return id
    }

    /**
     * A file of mine that can't go at all: its card says why, and so does a notice (never silent). [direct]: it would go
     * with Direct calls and files on for each other (its card offers that). [retry]: the file, kept for Retry.
     */
    fun failed(peer: String, name: String, size: Long, why: String, id: String = Proto06.newLogicalId(), group: String = "",
               direct: Boolean = false, retry: File? = null) {
        if (direct) directIds.add(id)
        if (retry != null) { again[id] = Triple(peer, retry, name); if (group.isNotEmpty()) againGroup[id] = group }
        put(Progress(id, peer, name, size, true, "failed", note = why, group = group))
        host?.notice("$name didn't go. $why")
    }

    /** Failed cards of mine that need Direct calls and files with them (no shared Wi-Fi, too big for the relays). */
    private val directIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun needsDirect(id: String) = id in directIds && _all.value[id]?.state == "failed"

    /** A file of mine that went as a message over the internet route: its card follows the message ([relayed]). */
    fun relayOut(msgId: String, peer: String, name: String, size: Long) {
        put(Progress(msgId, peer, name, size, true, "sending", route = Sharing.routeLabel("relay"), relay = true))
        host?.logFile(peer, name, size, "", true)
    }

    /** How far a file of mine going as a message got ([fraction], null once it left), or that it [failed]. */
    fun relayed(msgId: String, fraction: Float?, failed: Boolean) {
        val p = _all.value[msgId]?.takeIf { it.relay && it.state == "sending" } ?: return
        put(when {
            failed -> p.copy(state = "failed", note = "${host?.nameOf(p.peer) ?: "They"} can't be reached right now. Retry when they're online.")
            fraction == null -> p.copy(state = "done", done = p.size, note = "Sent · Arrives when they're online")
            else -> p.copy(done = (p.size * fraction.coerceIn(0f, 1f)).toLong())
        })
    }

    fun isStopped(id: String) = id in stopped

    /** Files moving straight now (closing or updating Muninn would stop them). */
    fun movingCount() = _all.value.values.count { !it.relay && (it.state == "connecting" || it.state == "sending") }

    /** A transfer of mine or theirs is with [peer] (their answers come in even when they aren't a contact). */
    fun expects(peer: String, id: String) = outs[id]?.peer == peer || ins[id]?.peer == peer

    /** Cancel: a file still on its way stops (the other side stops after 30 s of silence). */
    fun cancel(id: String) {
        val p = _all.value[id] ?: return
        if (!FileText.running(p.state) && p.state != "asking" && p.state != "waiting") return
        stopped.add(id)
        pausedIds.remove(id)
        if (!p.mine) ins.remove(id)?.let { host?.signal(it.peer, JSONObject().put("t", "decline").put("id", id).toString()) }
        put(p.copy(state = "failed", note = "Stopped"))
    }

    /** Pause and go on (cap f2, 27.5): either side, while it moves. */
    fun canPause(id: String) = id in movingIds && id !in pausedIds
    fun canResume(id: String) = id in pausedIds
    fun pause(id: String) {
        if (id !in movingIds || !pausedIds.add(id)) return
        pausedAt[id] = System.currentTimeMillis()
        refresh(id)
    }
    fun resume(id: String) {
        if (!pausedIds.remove(id)) return
        pausedAt.remove(id)?.let { at -> pausedFor[id] = (pausedFor[id] ?: 0L) + System.currentTimeMillis() - at }
        resumed.add(id)
        refresh(id)
    }

    /** Close: a finished or failed card goes. */
    fun dismiss(id: String) {
        val p = _all.value[id] ?: return
        if (FileText.running(p.state)) return
        partials.remove(id)?.let { it.file.delete(); saveKept() }   // closing a stopped file of theirs lets go of what came of it
        directIds.remove(id)
        again.remove(id)?.second?.delete()
        againGroup.remove(id)
        _all.value = _all.value - id
    }

    /** A card of mine whose file went over the internet route instead: the chat's own message takes over from here. */
    fun handedOff(id: String) {
        again.remove(id)
        againGroup.remove(id)
        _all.value = _all.value - id
    }

    /** Cancel shows: mine while it runs, theirs while it comes straight (one over the internet route just arrives). */
    fun canCancel(id: String) = _all.value[id]?.let { FileText.running(it.state) && !it.relay && (it.mine || ins.containsKey(id)) } == true

    fun canRetry(id: String) = again.containsKey(id) && _all.value[id]?.state == "failed"

    /** Retry: the card goes and its file comes back to be sent again (Chat.sendFile), with its group. */
    fun takeRetry(id: String): Triple<String, File, String>? {
        if (!canRetry(id)) return null
        val t = again.remove(id) ?: return null
        directIds.remove(id)
        _all.value = _all.value - id
        return t.takeIf { it.second.isFile }
    }
    fun retryGroup(id: String): String = againGroup.remove(id).orEmpty()

    /** A file of theirs coming over the internet route ([have] of [n] pieces): its card shows how far. */
    fun relayIn(msgId: String, peer: String, name: String, have: Int, n: Int) {
        put(Progress(msgId, peer, name, n.toLong() * Photos.PIECE, false, "sending", have.toLong() * Photos.PIECE, route = Sharing.routeLabel("relay")))
    }

    /** It arrived (the chat's message takes over), or half of it was let go. A copy offered straight goes too. */
    fun relayInDone(msgId: String, peer: String = "", name: String = "") {
        _all.value = _all.value.filterValues { p ->
            p.id != msgId && !(name.isNotEmpty() && !p.mine && p.peer == peer && p.name == name && p.state in setOf("asking", "waiting", "failed"))
        }
        if (name.isNotEmpty()) ins.entries.removeIf { it.value.peer == peer && it.value.name == name && _all.value[it.key] == null }
    }

    /** No straight path for a file of mine: a small one goes over the internet route instead, else its card says [why]. */
    private fun fallBack(id: String, peer: String, file: File, name: String, why: String, group: String = "") {
        val h = host
        // A group's file goes over the relays only to a contact, into the chat with them.
        if (h != null && file.isFile && file.length() <= FileText.RELAY_MAX && (group.isEmpty() || h.isContact(peer))) {
            again.remove(id)
            againGroup.remove(id)
            _all.value = _all.value - id   // the chat's own message takes over from here
            h.relay(peer, file, name)?.let { failed(peer, name, file.length(), it, id) }
            return
        }
        fail(id, why)
    }

    /** The "lan" candidate (27.1): only the port, never an address. */
    private fun lanCand(port: Int) = JSONObject().put("t", "lan").put("p", port)

    /** Their "lan" candidate paired with where they already are on this Wi-Fi ([at]); null when either is missing. */
    fun lanTarget(at: InetAddress?, c: JSONArray): InetSocketAddress? {
        if (at == null) return null
        for (i in 0 until c.length()) {
            val o = c.optJSONObject(i) ?: continue
            if (o.optString("t") != "lan") continue
            val p = o.opt("p") as? Number ?: continue
            if (p.toDouble() != p.toInt().toDouble() || p.toInt() !in 1..65535) continue
            return InetSocketAddress(at, p.toInt())
        }
        return null
    }

    /**
     * Sends [file] to [peer] directly: on the same Wi-Fi, or straight over the internet with trust (Sharing.route). Null
     * when it started, else why not ("not trusted": Chat may use the relays for a small one). [group]: a group's file.
     */
    fun send(peer: String, file: File, name: String, id: String = Proto06.newLogicalId(), group: String = ""): String? {
        val h = host ?: return "Not ready."
        if (!h.hasF1(peer)) return "needs a newer Muninn"
        if (group.isNotEmpty() && !h.hasF2(peer)) return "needs a newer Muninn"
        val lan = h.lanAddress(peer)?.takeIf { h.hasF2(peer) }
        val trust = h.trusted(peer)
        val route = Sharing.route(lan != null, trust, file.length(), FileText.RELAY_MAX)
        if (route != "direct" && route != "internet") return "not trusted"
        val lanOnly = route == "direct" && !trust
        if (id in stopped) { file.delete(); return null }   // Cancel while it was being made ready
        val size = file.length()
        again[id] = Triple(peer, file, name)
        if (group.isNotEmpty()) againGroup[id] = group
        put(Progress(id, peer, name, size, true, "connecting", group = group, route = if (lanOnly) Sharing.routeLabel("direct") else ""))
        work.execute {
            try {
                val known = hashed[file.absolutePath]
                val sha = known?.takeIf { it.first == size && it.second == file.lastModified() }?.third
                    ?: sha256(file).also { hashed[file.absolutePath] = Triple(size, file.lastModified(), it) }
                if (id in stopped) return@execute
                val sock = DatagramSocket()
                // Close by without trust: only the "lan" candidate, and no STUN server is asked.
                val cands = if (lanOnly) emptyList() else candidates(sock)
                if (cands.isEmpty() && lan == null) { sock.close(); fallBack(id, peer, file, name, "No internet connection that can go direct. Try on the same Wi-Fi.", group); return@execute }
                val keys = ChatCrypto.newKeyPair()
                outs[id] = Out(peer, file, name, size, sha, keys, sock, cands, group, lanOnly)
                val c = candsJson(cands)
                val cs = JSONArray()
                if (lan != null) cs.put(lanCand(sock.localPort))
                for (i in 0 until c.length()) cs.put(c.get(i))
                val offer = JSONObject().put("t", "offer").put("id", id).put("name", name).put("size", size).put("sha", sha)
                    .put("e", ChatCrypto.b64(keys.public.encoded)).put("c", cs)
                if (group.isNotEmpty()) offer.put("g", group)
                h.signal(peer, offer.toString())
                put(Progress(id, peer, name, size, true, "offered", group = group))
                // Nothing back in time: a small file goes over the internet route (it arrives when they're back); a
                // bigger one waits as long as the other phone may ask first.
                val long = h.resumes(peer)
                val until = System.currentTimeMillis() + answerMs(size, long)
                // Cap f3: a big file may wait a day. A STUN ask a minute keeps this socket's way through the router
                // open, so the address in the offer still works when they accept.
                var lastStun = System.currentTimeMillis()
                while (System.currentTimeMillis() < until && _all.value[id]?.state == "offered" && id !in stopped) {
                    Thread.sleep(250)
                    if (long && !lanOnly && size > FileText.RELAY_MAX && System.currentTimeMillis() - lastStun >= 60_000L) {
                        lastStun = System.currentTimeMillis()
                        if (_all.value[id]?.state == "offered") stunKeep(sock)
                    }
                }
                if (id in stopped) { outs.remove(id)?.sock?.close(); return@execute }
                if (_all.value[id]?.state == "offered") {
                    outs.remove(id)?.sock?.close()
                    fallBack(id, peer, file, name, if (long && size > FileText.RELAY_MAX) "Not accepted within a day. Try again." else "They're offline. Try again when they're back.", group)
                }
            } catch (e: Exception) { if (id !in stopped) fail(id, "That file couldn't be sent.") }
        }
        return null
    }

    private fun decline(to: String, id: String, why: String = "") {
        val o = JSONObject().put("t", "decline").put("id", id)
        if (why.isNotEmpty()) o.put("why", why)
        host?.signal(to, o.toString())
    }

    /** A card of theirs refused before anything moved (group rules, no space): it says why. */
    private fun refused(from: String, id: String, name: String, size: Long, group: String, why: String) {
        put(Progress(id, from, name, size, false, "failed", note = why, group = group))
    }

    /** A `file` message from [from] (already opened and checked: ok()). */
    fun onSignal(from: String, p: JSONObject) {
        val h = host ?: return
        val id = p.getString("id")
        when (p.getString("t")) {
            "offer" -> {
                if (ins.containsKey(id)) return
                val name = p.getString("name")
                val size = p.getLong("size")
                val g = p.optString("g")
                val c = p.getJSONArray("c")
                val trust = h.trusted(from)
                val lan = lanTarget(h.lanAddress(from), c)
                // A group's file (27.2, 27.3): only for a group we're both in, within its file rules. Dropped, and said.
                if (g.isNotEmpty()) {
                    val why = h.groupRefusal(g, from, size)
                    if (why.isNotEmpty()) {
                        decline(from, id, "rules")
                        refused(from, id, name, size, g, why)
                        h.notice("A file from ${h.nameOf(from)} in ${h.groupName(g)} was dropped. $why")
                        return
                    }
                }
                // Addresses are only swapped with someone trusted; on the same Wi-Fi nothing new is learned.
                if (!trust && lan == null) { decline(from, id); return }
                if (h.freeBytes() < size + 64L * 1024 * 1024) {
                    decline(from, id, "space")
                    refused(from, id, name, size, g, "Not enough space for it on this phone (${FileText.size(h.freeBytes())} free).")
                    h.notice("${h.nameOf(from)} tried to send you $name, but there isn't enough space.")
                    return
                }
                val theirs = listOfNotNull(lan) + (if (trust) CallLogic.directOf(c) else emptyList())
                val inc = In(from, name, size, p.getString("sha"), p.getString("e"), theirs, g, !trust, lan)
                ins[id] = inc
                val folder = Sharing.folderFor("Muninn", if (g.isEmpty()) h.nameOf(from) else h.groupName(g))
                // Someone in one of my Family groups, "Always from this person", or a group's own file the group lets
                // through (16.2): no asking first, whatever the size (Data saver still waits).
                val free = h.alwaysFrom(from) || (if (g.isEmpty()) h.allArrive(from) else h.groupArrives(g, size))
                // Cap f3: the rest of a file already accepted (it stopped part way) doesn't ask again.
                val resuming = partialFor(from, inc.sha, size, g) != null
                val waits = h.waitsForAsk(from) && !free && !resuming
                val routeShown = if (!trust) Sharing.routeLabel("direct") else ""
                when {
                    arrivesByItself(inc.size, h.mayDownload(), waits) -> accept(id)
                    asksFirst(inc.size, waits) -> {
                        // Cap f3: it waits a day (their app waits as long).
                        val day = h.resumes(from)
                        put(Progress(id, from, inc.name, inc.size, false, "asking",
                            note = if (day) "Wants to send you this. Waits a day for your answer" else "Wants to send you this. Accept or decline", group = g,
                            folder = Received.shown(folder), route = routeShown))
                        val text = "${h.nameOf(from)} wants to send you ${inc.name} (${FileText.size(inc.size)}). Accept or decline it in the chat or Transfers."
                        h.notice(text)
                        h.offered(if (g.isEmpty()) from else "g:$g", from, text)
                        expireAsk(id, if (day) LONG_ANSWER_MS else ASK_ANSWER_MS)
                    }
                    else -> put(Progress(id, from, inc.name, inc.size, false, "waiting", note = "On mobile data: tap to download", group = g,
                        folder = Received.shown(folder), route = routeShown))
                }
            }
            "accept" -> {
                val o = outs[id] ?: return
                if (o.peer != from) return
                val theirs = try { ChatCrypto.publicFrom(p.getString("e")) } catch (e: Exception) { return }
                val key = key(ChatCrypto.sharedKey(o.keys.private, o.keys.public, theirs), id)
                val c = p.getJSONArray("c")
                val lan = lanTarget(h.lanAddress(from), c)
                val targets = listOfNotNull(lan) + (if (o.lanOnly) emptyList() else CallLogic.directOf(c))
                work.execute { runSender(id, o, key, targets, lan) }
            }
            "decline" -> outs[id]?.takeIf { it.peer == from }?.let {
                outs.remove(id)
                if (_all.value[id]?.state == "offered") {
                    it.sock.close()
                    // Refused (group rules, no space, they said no: 27.4) ends it; otherwise a small one can still go
                    // over the internet route.
                    val why = p.optString("why")
                    if (why.isNotEmpty()) fail(id, Sharing.declineText(why))
                    else if (!it.lanOnly) { directIds.add(id); fallBack(id, it.peer, it.file, it.name, Sharing.declineText(""), it.group) }
                    else fallBack(id, it.peer, it.file, it.name, "Couldn't reach them on this Wi-Fi.", it.group)
                } else {
                    // Stopped on their side while it was on its way.
                    _all.value[id]?.let { p -> put(p.copy(state = "failed", note = "They stopped it")) }
                    stopped.add(id)
                    it.sock.close()
                }
            }
        }
    }

    /** An ask nobody answered in time: its card says so (the sender gave up by then too). */
    private fun expireAsk(id: String, ms: Long) {
        work.execute {
            val until = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < until && _all.value[id]?.state == "asking") Thread.sleep(1000)
            if (_all.value[id]?.state == "asking") {
                ins.remove(id)
                _all.value[id]?.let { put(it.copy(state = "failed", note = "Not taken in time. Ask them to send it again.")) }
            }
        }
    }

    /** No to a waiting file: the sender hears so, and it's gone from the chat. */
    fun decline(id: String) {
        val inc = ins.remove(id) ?: return
        decline(inc.peer, id, "no")
        _all.value = _all.value - id
    }

    /** Accept, and from now on their files arrive without asking ("Always from this person"). */
    fun acceptAlways(id: String) {
        val inc = ins[id] ?: return
        host?.let { if (it.isContact(inc.peer)) it.setAlways(inc.peer) }
        accept(id)
    }

    /** Download a waiting file now (tapped, or on Wi-Fi). */
    fun accept(id: String) {
        val inc = ins[id] ?: return
        val h = host ?: return
        put(Progress(id, inc.peer, inc.name, inc.size, false, "connecting", group = inc.group))
        work.execute {
            try {
                val sock = DatagramSocket()
                val cands = if (inc.lanOnly) emptyList() else candidates(sock)
                val cs = JSONArray()
                if (inc.lan != null || h.lanAddress(inc.peer) != null) cs.put(lanCand(sock.localPort))
                candsJson(cands).let { c -> for (i in 0 until c.length()) cs.put(c.get(i)) }
                val keys = ChatCrypto.newKeyPair()
                val key = key(ChatCrypto.sharedKey(keys.private, keys.public, ChatCrypto.publicFrom(inc.theirKey)), id)
                h.signal(inc.peer, JSONObject().put("t", "accept").put("id", id).put("e", ChatCrypto.b64(keys.public.encoded)).put("c", cs).toString())
                runReceiver(id, inc, key, sock)
            } catch (e: Exception) { fail(id, "That file didn't arrive. Ask them to send it again.") }
        }
    }

    private fun fail(id: String, why: String) {
        if (id in stopped) return
        _all.value[id]?.let { put(it.copy(state = "failed", note = why)) }
        host?.notice(why)
    }

    private fun ended(id: String) {
        movingIds.remove(id); pausedIds.remove(id); heldIds.remove(id); resumed.remove(id)
        started.remove(id); pausedAt.remove(id); pausedFor.remove(id); base.remove(id)
    }

    /** Hold (27.5): [on] paused, else going on. */
    private fun hold(sock: DatagramSocket, key: ByteArray, id: String, dir: Int, seq: Long, on: Boolean, to: InetSocketAddress) {
        val pk = seal(key, id, HOLD, dir, seq, byteArrayOf(if (on) 1 else 0))
        try { sock.send(DatagramPacket(pk, pk.size, to)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ the two ends

    private fun runSender(id: String, o: Out, key: ByteArray, theirs: List<InetSocketAddress>, lan: InetSocketAddress?) {
        val sock = o.sock
        try {
            put(Progress(id, o.peer, o.name, o.size, true, "connecting", group = o.group))
            val peer = punch(sock, key, id, 0, theirs) ?: run {
                if (id !in stopped) fallBack(id, o.peer, o.file, o.name,
                    if (o.lanOnly) "Couldn't reach them on this Wi-Fi." else "Couldn't connect straight to them. Try on the same Wi-Fi.", o.group)
                return
            }
            val route = Sharing.routeLabel(if (lan != null && peer == lan) "direct" else "internet")
            val n = pieces(o.size)
            val s = Sender(n)
            var seq = 1000L
            sock.soTimeout = 50
            val buf = ByteArray(2048)
            var lastHeard = System.currentTimeMillis()
            var lastKeep = 0L
            var sent = 0L
            var firstAck = true
            var quiet = false
            val f3 = host?.resumes(o.peer) == true
            val who = host?.nameOf(o.peer) ?: "They"
            movingIds.add(id)
            started[id] = System.currentTimeMillis()
            RandomAccessFile(o.file, "r").use { raf ->
                val chunk = ByteArray(CHUNK)
                put(Progress(id, o.peer, o.name, o.size, true, "sending", route = route, group = o.group))
                while (!s.done) {
                    if (id in stopped) return
                    val now = System.currentTimeMillis()
                    if (now - lastHeard > (if (f3) LONG_IDLE_MS else IDLE_MS)) {
                        fail(id, if (f3) "Stopped at ${FileText.percent(sent, o.size)}% (no answer from $who). Retry goes on from there." else "Stopped (no answer from them). Try again.")
                        return
                    }
                    // Cap f3: quiet for a moment, a probe a second instead of data until they're heard again.
                    if (f3 && (now - lastHeard > QUIET_MS) != quiet) {
                        quiet = !quiet
                        put(Progress(id, o.peer, o.name, o.size, true, "sending", sent, note = if (quiet) "Waiting for $who to come back" else "", route = route, group = o.group))
                    }
                    if (resumed.remove(id)) repeat(3) { hold(sock, key, id, 0, seq++, false, peer) }
                    when {
                        quiet && id !in pausedIds -> if (now - lastKeep >= 1000) {
                            val pk = seal(key, id, PROBE, 0, seq++, byteArrayOf(1)); sock.send(DatagramPacket(pk, pk.size, peer)); lastKeep = now
                        }
                        // Paused here: Hold every second, no data. Held by them: a probe a second, no data.
                        id in pausedIds -> if (now - lastKeep >= 1000) { hold(sock, key, id, 0, seq++, true, peer); lastKeep = now }
                        id in heldIds -> if (now - lastKeep >= 1000) {
                            val pk = seal(key, id, PROBE, 0, seq++, byteArrayOf(1)); sock.send(DatagramPacket(pk, pk.size, peer)); lastKeep = now
                        }
                        else -> for (i in s.due(now)) {
                            raf.seek(i.toLong() * CHUNK)
                            val len = minOf(CHUNK.toLong(), o.size - i.toLong() * CHUNK).toInt()
                            raf.readFully(chunk, 0, len)
                            val payload = ByteBuffer.allocate(4 + len).putInt(i).put(chunk, 0, len).array()
                            val pk = seal(key, id, DATA, 0, seq++, payload)
                            sock.send(DatagramPacket(pk, pk.size, peer))
                        }
                    }
                    try {
                        val dp = DatagramPacket(buf, buf.size)
                        while (true) {
                            sock.receive(dp)
                            val (t, pl) = open(key, id, 1, dp.data, dp.length) ?: continue
                            lastHeard = System.currentTimeMillis()
                            if (t == HOLD && pl.isNotEmpty()) {
                                if (pl[0].toInt() == 1) heldIds.add(id) else heldIds.remove(id)
                                refresh(id)
                                continue
                            }
                            if (t == ACK && heldIds.remove(id)) refresh(id)
                            if (t == ACK && pl.size >= 12) {
                                val bb = ByteBuffer.wrap(pl)
                                val below = bb.int
                                s.ack(below, bb.long)
                                sent = minOf(o.size, below.toLong() * CHUNK)
                                // Cap f3: what they already had (a resumed file) doesn't count in the speed.
                                if (firstAck) { firstAck = false; base[id] = sent }
                                put(Progress(id, o.peer, o.name, o.size, true, "sending", sent, note = if (quiet) "Waiting for $who to come back" else "", route = route, group = o.group))
                            }
                            if (t == DONE) { s.ack(n, 0); break }
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            }
            put(Progress(id, o.peer, o.name, o.size, true, "done", o.size, route = route, group = o.group))
            host?.logFile(if (o.group.isEmpty()) o.peer else "g:${o.group}", o.name, o.size, "", true)
            again.remove(id)?.second?.delete()
            againGroup.remove(id)
        } catch (e: Exception) { fail(id, "That file couldn't be sent.") } finally { outs.remove(id); sock.close(); ended(id) }
    }

    private fun runReceiver(id: String, inc: In, key: ByteArray, sock: DatagramSocket) {
        val h = host ?: return
        // Cap f3: the same file from the same person stopped part way before. It goes on from there.
        val kept = partialFor(inc.peer, inc.sha, inc.size, inc.group)?.let { k -> partials.remove(k)?.also { _all.value = _all.value - k; saveKept() } }
        val tmp = kept?.file ?: File(h.cacheDir(), "incoming-$id")
        val f3 = h.resumes(inc.peer)
        val who = h.nameOf(inc.peer)
        var keep = false
        try {
            val peer = punch(sock, key, id, 1, inc.theirCands) ?: run {
                // The sender's fallback over the relays (fallBack) only takes a small file, and a group's only to a contact.
                val another = inc.size <= FileText.RELAY_MAX && (inc.group.isEmpty() || h.isContact(inc.peer))
                fail(id, when {
                    inc.lanOnly -> "Couldn't reach them on this Wi-Fi. Ask them to send it again."
                    another -> "Couldn't connect straight to them. It may come another way."
                    else -> "Couldn't connect to $who. Ask them to send it again when you're on the same Wi-Fi."
                })
                return
            }
            val route = Sharing.routeLabel(if (inc.lan != null && peer == inc.lan) "direct" else "internet")
            val n = pieces(inc.size)
            val r = Receiver(n)
            // Cap f3: what an earlier try left is in; the first ack tells the sender to go on from there.
            val from = if (kept != null) { r.preload(kept.below); minOf(inc.size, kept.below.toLong() * CHUNK) } else 0L
            base[id] = from
            var seq = 1000L
            sock.soTimeout = 50
            val buf = ByteArray(2048)
            var lastHeard = System.currentTimeMillis()
            var lastAck = 0L
            var fresh = 0
            var quiet = false
            movingIds.add(id)
            started[id] = System.currentTimeMillis()
            put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", from, route = route, group = inc.group))
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(inc.size)
                while (!r.done) {
                    if (id in stopped) return
                    val now = System.currentTimeMillis()
                    if (now - lastHeard > (if (f3) LONG_IDLE_MS else IDLE_MS)) {
                        if (f3) {
                            // Kept: when they send it again it goes on from here.
                            val below = r.ack().first
                            val part = Partial(inc.peer, inc.sha, inc.size, inc.group, tmp, below, inc.name)
                            partials[id] = part
                            saveKept()
                            keep = true
                            fail(id, stoppedNote(part, who))
                        } else fail(id, "Stopped (no answer from them). Ask them to send it again.")
                        return
                    }
                    // Cap f3: quiet for a moment, an ack a second until they're heard again.
                    if (f3 && (now - lastHeard > QUIET_MS) != quiet) {
                        quiet = !quiet
                        val (below, _) = r.ack()
                        put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", minOf(inc.size, below.toLong() * CHUNK),
                            note = if (quiet) "Waiting for $who to come back" else "", route = route, group = inc.group))
                    }
                    try {
                        val dp = DatagramPacket(buf, buf.size)
                        sock.receive(dp)
                        val (t, pl) = open(key, id, 0, dp.data, dp.length) ?: continue
                        lastHeard = System.currentTimeMillis()
                        if (t == HOLD && pl.isNotEmpty()) { if (pl[0].toInt() == 1) heldIds.add(id) else heldIds.remove(id); refresh(id) }
                        if (t == DATA && pl.size > 4) {
                            val i = ByteBuffer.wrap(pl).int
                            if (i in 0 until n && pl.size - 4 == minOf(CHUNK.toLong(), inc.size - i.toLong() * CHUNK).toInt() && r.take(i)) {
                                raf.seek(i.toLong() * CHUNK); raf.write(pl, 4, pl.size - 4); fresh++
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                    val t = System.currentTimeMillis()
                    if (resumed.remove(id)) { repeat(3) { hold(sock, key, id, 1, seq++, false, peer) }; lastAck = 0L }
                    if (id in pausedIds) {
                        // Paused here: Hold every second instead of acks.
                        if (t - lastAck >= 1000) { hold(sock, key, id, 1, seq++, true, peer); lastAck = t }
                    } else if (fresh >= 16 || t - lastAck >= (if (id in heldIds || quiet) 1000L else 40L)) {
                        val (below, bits) = r.ack()
                        val pk = seal(key, id, ACK, 1, seq++, ByteBuffer.allocate(12).putInt(below).putLong(bits).array())
                        sock.send(DatagramPacket(pk, pk.size, peer))
                        lastAck = t; fresh = 0
                        put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", minOf(inc.size, below.toLong() * CHUNK),
                            note = if (quiet) "Waiting for $who to come back" else "", route = route, group = inc.group))
                    }
                }
            }
            repeat(3) { val pk = seal(key, id, DONE, 1, seq++, ByteArray(0)); sock.send(DatagramPacket(pk, pk.size, peer)) }
            put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", inc.size, note = "Checking it", route = route, group = inc.group))
            if (sha256(tmp) != inc.sha) { fail(id, "${inc.name} arrived damaged, so it was thrown away."); return }
            // In Download/Muninn/<chat name>, never a hidden app folder.
            val chat = if (inc.group.isEmpty()) h.nameOf(inc.peer) else h.groupName(inc.group)
            val folder = Sharing.folderFor("Muninn", chat)
            val saved = h.saveIn(inc.name, tmp, folder) ?: run { fail(id, "Not enough space to save ${inc.name}."); return }
            put(Progress(id, inc.peer, saved.name, inc.size, false, "done", inc.size, uri = saved.uri, route = route, group = inc.group, folder = Received.shown(folder)))
            h.logFile(if (inc.group.isEmpty()) inc.peer else "g:${inc.group}", saved.name, inc.size, saved.uri, false)
            h.notice("File from ${h.nameOf(inc.peer)}: ${saved.name} · Saved in ${Received.shown(folder)}")
        } catch (e: Exception) { fail(id, "That file didn't arrive. Ask them to send it again.") } finally { ins.remove(id); sock.close(); if (!keep) tmp.delete(); ended(id) }
    }

    /**
     * Both sides send probes to every candidate of the other at once, which opens both routers. The first authentic
     * probe that comes back says which address works. Null after [PUNCH_MS].
     */
    private fun punch(sock: DatagramSocket, key: ByteArray, id: String, dir: Int, theirs: List<InetSocketAddress>): InetSocketAddress? {
        if (theirs.isEmpty()) return null
        sock.soTimeout = 100
        val until = System.currentTimeMillis() + PUNCH_MS
        val buf = ByteArray(2048)
        var seq = 1L
        var found: InetSocketAddress? = null
        var heardBack = 0
        while (System.currentTimeMillis() < until && id !in stopped) {
            val targets = found?.let { listOf(it) } ?: theirs
            for (a in targets) { val pk = seal(key, id, PROBE, dir, seq++, byteArrayOf(1)); try { sock.send(DatagramPacket(pk, pk.size, a)) } catch (_: Exception) {} }
            try {
                val dp = DatagramPacket(buf, buf.size)
                sock.receive(dp)
                val (t, _) = open(key, id, 1 - dir, dp.data, dp.length) ?: continue
                found = dp.socketAddress as InetSocketAddress
                // Data or an ack already means the other side found this one too.
                if (t != PROBE || ++heardBack >= 3) return found
            } catch (_: java.net.SocketTimeoutException) {}
        }
        return found
    }

    /** This socket's address on the internet (from STUN) and up to 2 global IPv6 addresses. */
    private fun candidates(sock: DatagramSocket): List<InetSocketAddress> {
        val out = ArrayList<InetSocketAddress>()
        stun(sock)?.let { out += it }
        try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet6Address>()
                .filter { !CallLogic.isLocal(it) }.map { InetAddress.getByAddress(it.address) }.distinct().take(2)
                .forEach { out += InetSocketAddress(it, sock.localPort) }
        } catch (_: Exception) {}
        return out
    }

    /** Cap f3: a STUN ask from [sock] that keeps its way through the router open. Only sent; the answer is ignored. */
    private fun stunKeep(sock: DatagramSocket) = try {
        val (server, port) = Stun.SERVERS.first()
        val req = Stun.encode(Stun.type(Stun.BINDING, Stun.REQUEST), Stun.newTid(), emptyList(), fingerprint = true)
        sock.send(DatagramPacket(req, req.size, InetSocketAddress(InetAddress.getByName(server), port)))
    } catch (_: Exception) {}

    /** Asks each server in [Stun.SERVERS] in turn until one answers. */
    private fun stun(sock: DatagramSocket): InetSocketAddress? {
        for ((host, port) in Stun.SERVERS) stunOne(sock, host, port)?.let { return it }
        return null
    }

    private fun stunOne(sock: DatagramSocket, host: String, port: Int): InetSocketAddress? = try {
        val server = InetSocketAddress(InetAddress.getByName(host), port)
        val tid = Stun.newTid()
        val req = Stun.encode(Stun.type(Stun.BINDING, Stun.REQUEST), tid, emptyList(), fingerprint = true)
        sock.soTimeout = 300
        val buf = ByteArray(1024)
        var got: InetSocketAddress? = null
        for (i in 0 until 6) {
            sock.send(DatagramPacket(req, req.size, server))
            try {
                val dp = DatagramPacket(buf, buf.size)
                sock.receive(dp)
                val m = Stun.decode(dp.data, dp.length)
                if (m != null && m.tid.contentEquals(tid) && Stun.classOf(m.type) == Stun.SUCCESS) {
                    got = m.xorAddress(Stun.A_XOR_MAPPED_ADDRESS)?.takeIf { CallLogic.usable(it.address) }
                    if (got != null) break
                }
            } catch (_: java.net.SocketTimeoutException) {}
        }
        got
    } catch (e: Exception) { null }

    private fun candsJson(c: List<InetSocketAddress>) = JSONArray().apply {
        c.forEachIndexed { i, a -> put(JSONObject().put("t", if (i == 0 && a.address is java.net.Inet4Address) "srflx" else if (a.address is java.net.Inet6Address) "host" else "srflx")
            .put("a", a.address.hostAddress?.substringBefore('%')).put("p", a.port)) }
    }

    fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val b = ByteArray(1 shl 16); while (true) { val n = ins.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
