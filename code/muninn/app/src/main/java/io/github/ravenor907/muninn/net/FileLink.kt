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
 * Encrypted file sharing (caps f1, f2, f3, f4 and f5; shared/docs/protocol.md sections 14, 27, 30 and 32). Never through
 * the message relays. In this order: Nearby (the same Wi-Fi, a "lan" candidate with no address), Trusted direct (straight
 * over the internet with someone trusted with this phone's address: both learn their own address from a STUN server, swap
 * it in a sealed `file` message and punch through their routers, as the Direct path of calls does, 5.8.2), then for Family
 * and trusted contacts the call relay (cap f5: each side's TURN allocation, the same sealed pieces), or Tor (cap f4: a
 * one-time onion service for this one file, TorFiles) as [MediaRoute] says. The file goes in numbered pieces, each sealed
 * with AES-256-GCM under a key only the two phones know (from the one-off keys of the offer and the accept), and the
 * receiver says which pieces it has; a transfer can change way part way and goes on from the last piece.
 * Family and trusted contacts' files arrive by themselves; everyone else's ask Accept or Decline first (the receiving
 * card). Cap f2 (section 27): group files, why a file was refused, Pause. Cap f3: resume and long waits.
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
    /** Cap f4: Encrypted file sharing over Tor too, and every file asks first (TorFiles.CAP). */
    const val TOR_CAP = TorFiles.CAP
    /** How long an offer waits for an answer: longer for a file the other phone asks about first. */
    const val ANSWER_MS = 120_000L
    const val ASK_ANSWER_MS = 10 * 60_000L
    /** Cap f3: a big file waits a day for Accept or Decline (both apps say f3). Cap f4: every file is queued this long. */
    const val LONG_ANSWER_MS = 24 * 3_600_000L
    /** Cap f4: a queued offer goes again this often while it waits (relays may drop an old one). */
    const val REOFFER_MS = 2 * 3_600_000L
    /** Cap f4: quiet this long on Nearby or Trusted direct, it goes on over Tor. */
    const val SWITCH_MS = 10_000L

    /**
     * A sender whose app says this cap waits [ASK_ANSWER_MS] for a big file, so the receiver may ask first. An older
     * sender gives up after [ANSWER_MS].
     */
    const val ASK_CAP = RingWire.WRAP_CAP

    /**
     * An offered file or video downloads by itself from Family and trusted contacts ([always]), and the rest of a file
     * already accepted ([resuming]). Everyone else's asks first. Data saver on mobile data still asks ([mayDownload]).
     */
    fun arrivesByItself(always: Boolean, resuming: Boolean, mayDownload: Boolean): Boolean = mayDownload && (always || resuming)

    /** A file small enough for the old relay route waited this long (older peers and full-size videos only). */
    const val RELAY_AFTER_MS = 30_000L

    /**
     * How long the sender waits for an answer to a file of [size]. Cap f4 ([f4]): a day, queued ("Sends when Jo's phone
     * is online"). Older apps: a full-size video only [RELAY_AFTER_MS] (then its smaller copy goes), a file as long as
     * the other side may ask first.
     */
    fun answerMs(size: Long, long: Boolean = false, f4: Boolean = false): Long =
        if (f4) LONG_ANSWER_MS else if (size <= FileText.RELAY_MAX) RELAY_AFTER_MS else if (long) LONG_ANSWER_MS else ASK_ANSWER_MS

    // ------------------------------------------------------------------ the receiving card (both apps say the same)

    /** Files that can run as a program or install something: the card adds [RISKY_NOTE]. */
    val RISKY = setOf("zip", "apk", "exe", "bat", "msi", "js", "scr", "cmd", "ps1", "vbs", "jar", "sh", "appimage", "deb", "rpm")
    const val RISKY_NOTE = "Only open this if you were expecting it."
    fun risky(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in RISKY

    /** "a ZIP file", "an APK file", "a file": what the card says they want to send. */
    fun typeWord(name: String): String {
        val ext = name.substringAfterLast('.', "").takeIf { name.contains('.') && it.length in 1..8 && it.all { c -> c.isLetterOrDigit() } }?.uppercase()
            ?: return "a file"
        return (if (ext.first() in "AEFHILMNORSX") "an " else "a ") + ext + " file"
    }

    /** The trust badges: Trusted, Family, Contact, Not trusted. Only the first two may be set to Always. */
    const val BADGE_TRUSTED = "Trusted"
    const val BADGE_FAMILY = "Family"
    const val BADGE_CONTACT = "Contact"
    const val BADGE_NOT = "Not trusted"
    fun mayAlways(badge: String) = badge == BADGE_TRUSTED || badge == BADGE_FAMILY

    /** What the relay leg's card says. */
    const val VIA_RELAY = "Encrypted relay"

    /** What a sender's card says while its offer waits: "Sends when Jo's phone is online" ([device]: "phone", "computer" or ""). */
    fun queuedNote(who: String, device: String): String =
        if (device.isEmpty()) "Sends when $who is online" else "Sends when $who's $device is online"
    const val NOT_SENT = "Didn't send. Try again."
    const val DECLINED = "Declined"
    const val UPDATE = "Ask them to update"
    const val NO_PATH = "Can't reach them right now"
    /** 1.42: an older app's file over the relays, turned down (the receiving card's line). */
    const val OLD_RELAY = "Sent from an older app. Ask them to update."

    /** The one short message an older app's sender gets when its relay file is turned down. */
    fun oldRelayReply(name: String): String =
        "I couldn't take \"$name\": files now go only with Encrypted file sharing. Please update your app and send it again."

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
        /** Cap f4 (a Tor stream): piece [i] is confirmed; everything below [below] is. */
        @Synchronized fun has(i: Int): Boolean = acked[i]
        @Synchronized fun below(): Int = acked.nextClearBit(0).coerceAtMost(pieces)

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
                p.opt("e") is String && p.opt("c") is JSONArray &&
                // Cap v3 (29.3): a full-size video's length.
                GroupVideo.vidOk(p.opt("vid"))
            "accept" -> p.opt("e") is String && p.opt("c") is JSONArray
            "decline", "done" -> true
            // Cap f4 (section 30): the card asks them now; the sender's one-time onion service.
            "wait" -> true
            "tor" -> TorFiles.torOk(p)
            "relay" -> relayOk(p)
            else -> false
        }
    }

    /** A `relay` message: their relayed address, numbers only (never a name to look up). */
    fun relayOk(p: JSONObject): Boolean = (p.opt("a") as? String)?.let { CallLogic.literal(it) != null } == true &&
        (p.opt("p") as? Number)?.toInt()?.let { it in 1..65535 } == true

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
                        val time: Long = System.currentTimeMillis(),
                        /** Theirs asking first: their trust badge (Trusted, Family, Contact, Not trusted). */
                        val badge: String = "",
                        /** Theirs asking first: how it comes ("Nearby", "Trusted direct", "Tor, slower (about 20 min)"). */
                        val method: String = "",
                        /** Cap v3: a full-size video this many ms long (its own card, not the receiving card). */
                        val vid: Int = 0) {
        /** The chat it belongs to: their key, or "g:<group>". */
        val where: String get() = if (group.isEmpty()) peer else "g:$group"
        val kind: String get() = Sharing.kindOf(name)
        /** The receiving card: "wants to send you a ZIP file", the risky note, and whether "Always accept from" shows. */
        val typeWord: String get() = FileLink.typeWord(name)
        val risky: Boolean get() = FileLink.risky(name)
        val canAlways: Boolean get() = mayAlways(badge)
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
        if (old == null || old.state != p.state || old.route != p.route && p.route.isNotEmpty())
            ActivityLog.add("file", "${ActivityLog.id(p.id)} ${if (p.mine) "out" else "in"} ${p.state} ${p.route} ${FileText.size(p.size)}" +
                (if (p.state == "failed") " (${ActivityLog.reason(p.note)})" else ""))
        val merged = if (old == null) p else p.copy(group = p.group.ifEmpty { old.group }, route = p.route.ifEmpty { old.route },
            folder = p.folder.ifEmpty { old.folder }, relay = p.relay || old.relay, time = old.time)
        val paused = p.id in pausedIds
        val held = p.id in heldIds
        val since = started[p.id]
        // Cap v3: a full-size video that got there needs no relay copy any more.
        if (p.state == "done" || p.id in stopped) videoBack.remove(p.id)
        val pace = if (merged.state == "sending" && since != null && !paused && !held)
            (base[p.id] ?: 0L).let { b -> FileText.pace(merged.done - b, merged.size - b, System.currentTimeMillis() - since - (pausedFor[p.id] ?: 0L)) } else ""
        // A copy of my big video stopped: its bubble says Not sent (the card shows, with Retry).
        if (p.state == "failed" && p.mine && old?.state != "failed") bigOf[p.id]?.let { (chat, msgId) ->
            againBig[p.id] = chat to msgId
            try { host?.bigVideoFailed(chat, msgId) } catch (_: Exception) {}
        }
        // A copy of my big video (in the chat like a photo) arrived: the bubble says Sent, and the card goes.
        if (p.state == "done" && p.mine) bigOf.remove(p.id)?.let { (chat, msgId) ->
            try { host?.bigVideoSent(chat, msgId) } catch (_: Exception) {}
            _all.value = _all.value - p.id
            return
        }
        _all.value = _all.value + (p.id to merged.copy(paused = paused, held = held, pace = pace))
    }

    /** My big videos' cards (BigVideo): card id to the chat ("g:" and a group, or a key) and the bubble's message id. */
    private val bigOf = ConcurrentHashMap<String, Pair<String, String>>()
    /** Kept (Retry) cards: a video's length, and my big video's bubble, so Retry sends it the same way (saved with them). */
    private val againVid = ConcurrentHashMap<String, Int>()
    private val againBig = ConcurrentHashMap<String, Pair<String, String>>()
    /** Taken for Retry, waiting for [send]: by the file's path. */
    private val carry = ConcurrentHashMap<String, Pair<Int, Pair<String, String>?>>()
    /** Card [card] carries a copy of my big video [msgId] in [chat]: the bubble shows it, so the card stays out of the chat unless it fails. */
    fun linkBig(card: String, chat: String, msgId: String) { bigOf[card] = chat to msgId; refresh(card) }
    /** A card the chat doesn't show (its big video's bubble does), while it hasn't failed. */
    fun inBubble(p: Progress) = p.mine && bigOf.containsKey(p.id) && p.state != "failed"
    private fun refresh(id: String) { _all.value[id]?.let { put(it) } }

    interface Host {
        /** Seal [json] (room "file") for [peer] and send it on every route. */
        fun signal(peer: String, json: String)
        fun trusted(peer: String): Boolean
        fun hasF1(peer: String): Boolean
        /** Their app waits for a big file to be accepted ([ASK_CAP]). */
        fun waitsForAsk(peer: String): Boolean = true
        /** In one of my Family groups (protocol.md 16.2). 1.42: only for the badge; their files ask first too. */
        fun allArrive(peer: String): Boolean = false
        /** A whole file arrived and checked out: put it in Downloads. */
        fun save(name: String, file: File): Saved?
        /** 1.42: files never go through the relays; kept for older callers only, never used for a file. */
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
        /** "Always accept from <name>": their files arrive without asking (only someone trusted or Family). */
        fun alwaysFrom(peer: String): Boolean = false
        fun setAlways(peer: String) {}
        // Cap f4 (section 30).
        /** Their app says f4 (Encrypted file sharing over Tor, asking first). */
        fun hasF4(peer: String): Boolean = false
        /** Tor may start for a file here (not on a TV short of space; a Kids' Device within its parent's rules). */
        fun torAllowed(): Boolean = true
        /** Their trust badge: [BADGE_TRUSTED], [BADGE_FAMILY], [BADGE_CONTACT] or [BADGE_NOT]. */
        fun badge(peer: String): String = if (trusted(peer)) BADGE_TRUSTED else if (isContact(peer)) BADGE_CONTACT else BADGE_NOT
        /** "phone" or "computer" when it's known what their app runs on, else "". */
        fun deviceWord(peer: String): String = ""
        /** This phone has an internet connection now. */
        fun online(): Boolean = true
        /** The foreground "Sending file" notification while a file moves ([on]), and how far ([TransferHold]). */
        fun keepGoing(tag: String, on: Boolean, text: String = "") {}
        fun progressNote(text: String, percent: Int) {}
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
        /** This is a Kids' Device: a full-size video (cap v3) is declined at once, so the sender sends the relay copy. */
        fun isKidDevice(): Boolean = false
        /** Full-size videos waiting to go (noBackupFilesDir/videos-out, Chat.sendVideoFull): they come back after a restart too. */
        fun videosOutDir(): File? = null
        /** Cap f5: what the call relay needs (MediaRoute). */
        fun hasF5(peer: String): Boolean = false
        fun routeFor(peer: String, group: String): String = MediaRoute.AUTO
        fun relayLogin(): io.github.ravenor907.muninn.net.call.TurnCreds.Login? = null
        fun relayOver(): Boolean = false
        fun relayStopped(): Boolean = false
        fun relayUsed(bytes: Long) {}
        /** A video from Family or trusted (up to 3 minutes) into the chat like a photo; false keeps it a file (BigVideo). */
        fun inlineVideo(peer: String, group: String, file: File, ms: Int): Boolean = false
        /** A copy of my big video [msgId] (in [chat]: a key, or "g:" and a group) arrived. */
        fun bigVideoSent(chat: String, msgId: String) {}
        /** A copy of my big video [msgId] stopped (its card has Retry): the bubble says Not sent. */
        fun bigVideoFailed(chat: String, msgId: String) {}
        /** Family or trusted, but set to Ask in their details. */
        fun asksFirst(peer: String): Boolean = false
    }

    @Volatile var host: Host? = null
    private val work = Executors.newCachedThreadPool { r -> Thread(r, "muninn-file").apply { isDaemon = true } }
    private class Out(val peer: String, val file: File, val name: String, val size: Long, val sha: String, val keys: java.security.KeyPair,
                      val sock: DatagramSocket, val cands: List<InetSocketAddress>, val group: String = "", val lanOnly: Boolean = false,
                      /** Cap f4: Tor may carry it (both say f4 and Tor may start here). */
                      val tor: Boolean = false,
                      /** Cap f5: the call relay may carry it, after [punchMs] on the direct way. */
                      val relay: Boolean = false, val punchMs: Long = PUNCH_MS) {
        /** Cap f4: packets on a Tor stream count from here, across every stream of this transfer. */
        val torSeq = java.util.concurrent.atomic.AtomicLong(TorFiles.SEQ_BASE)
        /** Its "wait" came: they're there and the card asks them. */
        @Volatile var seen = false
    }
    private class In(val peer: String, val name: String, val size: Long, val sha: String, val theirKey: String, val theirCands: List<InetSocketAddress>,
                     val group: String = "", val lanOnly: Boolean = false, val lan: InetSocketAddress? = null,
                     /** Cap f4: Tor may carry it. */
                     val tor: Boolean = false,
                     /** Cap f5: the call relay may carry it. */
                     val relay: Boolean = false, val punchMs: Long = PUNCH_MS,
                     /** A video's length in ms (its offer's "vid"), 0 for any other file. */
                     val vid: Int = 0) {
        val torSeq = java.util.concurrent.atomic.AtomicLong(TorFiles.SEQ_BASE)
        /** Cap f4: the sender's one-time onion service, once its `tor` message came. */
        @Volatile var onion: Pair<String, Int>? = null
    }
    private val outs = ConcurrentHashMap<String, Out>()
    private val ins = ConcurrentHashMap<String, In>()
    /** Stopped with Cancel: its threads end quietly. */
    private val stopped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Mine that trying again can't change (declined, rules, space, Ultra privacy): never sent again by itself, even after a restart. */
    private val noRetry: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Mine, while it can be sent again (Retry): who to, the file and its name, and its group. */
    private val again = ConcurrentHashMap<String, Triple<String, File, String>>()
    private val againGroup = ConcurrentHashMap<String, String>()
    /** Cap f3: a file of theirs that stopped part way, kept to go on from for [KEEP_DAYS] (across a restart): by its card's id. */
    private class Partial(val peer: String, val sha: String, val size: Long, val group: String, val file: File, val below: Int,
                          val name: String = "", val time: Long = System.currentTimeMillis())
    private val partials = ConcurrentHashMap<String, Partial>()
    /** Kept parts whose card was closed: the part stays (it goes on from there), its card doesn't come back on a restart. */
    private val closedKept: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** A stopped file of theirs whose part is kept: its card offers Close (keeps it) and Discard (asks, then deletes it). */
    fun isKept(id: String) = partials.containsKey(id)
    /** How much of a kept file came (bytes), for Discard's question. */
    fun keptBytes(id: String): Long = partials[id]?.let { minOf(it.size, it.below.toLong() * CHUNK) } ?: 0L
    private fun partialFor(peer: String, sha: String, size: Long, group: String): String? =
        if (host?.resumes(peer) != true) null
        else partials.entries.firstOrNull { (_, p) -> p.peer == peer && p.sha == sha && p.size == size && p.group == group && p.file.isFile }?.key
    private fun stoppedNote(p: Partial, who: String) =
        "Stopped at ${FileText.percent(minOf(p.size, p.below.toLong() * CHUNK), p.size)}% (no answer from $who). It goes on from there if they send it again in the next $KEEP_DAYS days."

    /**
     * Cap f3: the kept parts from before a restart (and their cards), read once the vault is open. Older than
     * [KEEP_DAYS], or with the part file gone: dropped. My own files that can be sent again (Retry) come back too, as
     * stopped cards: Android may close Muninn in the middle of a big one.
     */
    fun loadKept() {
        val h = host ?: return
        val saved = try { h.readKept()?.let { JSONObject(it) } } catch (_: Exception) { null } ?: return
        val a = saved.optJSONArray("parts") ?: JSONArray()
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
            if (o.optBoolean("closed")) { closedKept.add(id); continue }
            if (_all.value[id] == null) _all.value = _all.value + (id to Progress(id, p.peer, p.name.ifEmpty { "A file" }, p.size, false, "failed",
                minOf(p.size, p.below.toLong() * CHUNK), note = stoppedNote(p, h.nameOf(p.peer)), group = p.group, time = p.time))
        }
        val outs = saved.optJSONArray("outs") ?: JSONArray()
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val fileName = o.optString("file")
            // Only a copy of ours, by its plain name in the cache (as sendFileFrom makes them), or a full-size video's in
            // videos-out (Round 19: "Send in full later" survives Muninn closing).
            val plainName = fileName.isNotEmpty() && !fileName.contains('/') && !fileName.contains('\\') && !fileName.contains("..")
            val vids = h.videosOutDir()
            val f = when {
                !plainName -> null
                o.optString("dir") == "videos-out" -> vids?.let { File(it, fileName) }
                fileName.startsWith("out-") -> File(h.cacheDir(), fileName)
                else -> null
            }
            if (id.isEmpty() || again.containsKey(id) || _all.value[id] != null) continue
            val peer = o.optString("peer")
            // Stopped by hand before stopped sends stayed stopped: dropped, never sent again by itself.
            if (f == null || !f.isFile || peer.isEmpty() || o.optLong("time") < oldest || o.optString("note") == "Stopped") { f?.delete(); dropped = true; continue }
            val name = o.optString("name").ifEmpty { "file" }
            if (o.optBoolean("noRetry")) noRetry.add(id)
            o.optInt("vid", 0).takeIf { it > 0 }?.let { againVid[id] = it }
            val bc = o.optString("bc"); val bm = o.optString("bm")
            // My big video's copy was on its way when Muninn closed: its bubble says Not sent, and Retry sends it the same way.
            if (bc.isNotEmpty() && bm.isNotEmpty()) { againBig[id] = bc to bm; try { host?.bigVideoFailed(bc, bm) } catch (_: Exception) {} }
            o.optInt("tries", 0).takeIf { it > 0 }?.let { retries[f.path] = it }
            val g = o.optString("group")
            again[id] = Triple(peer, f, name)
            if (g.isNotEmpty()) againGroup[id] = g
            _all.value = _all.value + (id to Progress(id, peer, name, f.length(), true, "failed",
                note = o.optString("note").ifEmpty { "Stopped when Muninn closed. Retry sends it again." }, group = g, time = o.optLong("time")))
        }
        if (dropped) saveKept()
    }

    /** My own cards that can be sent again (Retry): their copy in the cache, who to and the group, for [saveKept]. */
    private fun againJson(h: Host): JSONArray {
        val dir = h.cacheDir().absolutePath
        val vids = h.videosOutDir()?.absolutePath
        val a = JSONArray()
        noRetry.retainAll(again.keys)
        for ((id, t) in again) {
            val f = t.second
            val parent = f.parentFile?.absolutePath
            val inVids = vids != null && parent == vids
            if (!f.isFile || !(inVids || (parent == dir && f.name.startsWith("out-")))) continue
            val p = _all.value[id]
            a.put(JSONObject().put("id", id).put("peer", t.first).put("file", f.name).put("name", t.third).put("group", againGroup[id].orEmpty())
                .put("time", p?.time ?: f.lastModified()).put("note", if (p?.state == "failed") p.note else "")
                .apply { if (inVids) put("dir", "videos-out"); if (id in noRetry) put("noRetry", true); retries[f.path]?.let { put("tries", it) }
                    againVid[id]?.let { put("vid", it) }; (bigOf[id] ?: againBig[id])?.let { (c, m) -> put("bc", c).put("bm", m) } })
        }
        return a
    }

    /** After a restart: stopped sends to Family and trusted contacts go on by themselves. */
    fun resumeStopped() { for (id in again.keys.toList()) if (_all.value[id]?.state == "failed" && id !in noRetry) autoRetry(id) }

    /** Cap f3: writes the kept parts' list (when one comes or goes), with my own files that can be sent again. */
    private fun saveKept() {
        val h = host ?: return
        val a = JSONArray()
        closedKept.retainAll(partials.keys)
        for ((id, p) in partials) a.put(JSONObject().put("id", id).put("peer", p.peer).put("sha", p.sha).put("size", p.size).put("group", p.group)
            .put("file", p.file.name).put("below", p.below).put("name", p.name).put("time", p.time).apply { if (id in closedKept) put("closed", true) })
        try { h.writeKept(JSONObject().put("parts", a).put("outs", againJson(h)).toString()) } catch (_: Exception) {}
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
     * with Trusted direct on for each other (its card offers that). [retry]: the file, kept for Retry.
     */
    fun failed(peer: String, name: String, size: Long, why: String, id: String = Proto06.newLogicalId(), group: String = "",
               direct: Boolean = false, retry: File? = null) {
        if (direct) directIds.add(id)
        if (retry != null) { again[id] = Triple(peer, retry, name); if (group.isNotEmpty()) againGroup[id] = group }
        put(Progress(id, peer, name, size, true, "failed", note = why, group = group))
        if (retry != null) saveKept()
        host?.notice("$name didn't go. $why")
    }

    /** Failed cards of mine that need Trusted direct with them (no shared Wi-Fi, too big for the relays). */
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
        // Stopped by hand stays stopped: nothing sends it again by itself, now or after a restart.
        if (p.mine) { again.remove(id)?.second?.delete(); againGroup.remove(id); saveKept() }
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

    /**
     * Close: a finished or failed card goes. A stopped file of theirs keeps what came (as Huginn does): it still goes on
     * from there if they send it again within [KEEP_DAYS] days. [discard] throws it away.
     */
    fun dismiss(id: String) {
        val p = _all.value[id] ?: return
        if (FileText.running(p.state)) return
        if (partials.containsKey(id)) closedKept.add(id)
        directIds.remove(id)
        val wasAgain = again.remove(id)?.second?.delete() != null
        againGroup.remove(id)
        _all.value = _all.value - id
        if (wasAgain || id in closedKept) saveKept()
    }

    /** Discard: a kept part is deleted (it starts over if they send it again), and its card goes. Asked first by the card. */
    fun discard(id: String) {
        partials.remove(id)?.let { it.file.delete() }
        closedKept.remove(id)
        saveKept()
        dismiss(id)
    }

    /** A card of mine whose file went over the internet route instead: the chat's own message takes over from here. */
    fun handedOff(id: String) {
        val had = again.remove(id) != null
        againGroup.remove(id)
        _all.value = _all.value - id
        if (had) saveKept()
    }

    /** Cancel shows: mine while it runs, theirs while it comes straight (one over the internet route just arrives). */
    fun canCancel(id: String) = _all.value[id]?.let { FileText.running(it.state) && !it.relay && (it.mine || ins.containsKey(id)) } == true

    fun canRetry(id: String) = again.containsKey(id) && _all.value[id]?.state == "failed"

    /** Retry: the card goes and its file comes back to be sent again (Chat.sendFile), with its group. */
    fun takeRetry(id: String): Triple<String, File, String>? {
        if (!canRetry(id)) return null
        val t = again.remove(id) ?: return null
        directIds.remove(id)
        val v = againVid.remove(id) ?: 0
        val b = bigOf.remove(id) ?: againBig.remove(id)
        if (v > 0 || b != null) carry[t.second.path] = v to b
        _all.value = _all.value - id
        saveKept()
        return t.takeIf { it.second.isFile }
    }
    fun retryGroup(id: String): String = againGroup.remove(id).orEmpty()

    /** 1.42: an older app's relay file, turned down: a failed card says why (nothing was kept). */
    fun oldRelayFile(msgId: String, peer: String, name: String) {
        put(Progress(msgId, peer, name, 0L, false, "failed", note = OLD_RELAY))
    }

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

    /**
     * Cap v3 (29.3): a full-size video's copy for one member. If it can't go straight (no answer within [RELAY_AFTER_MS],
     * no way through, refused by rules or space, or it broke off), its card goes and [back] sends the relay copy instead.
     */
    private val videoBack = ConcurrentHashMap<String, () -> Unit>()
    fun onNoPath(id: String, back: () -> Unit) { videoBack[id] = back }

    /** True when [id] was a full-size video: its card is gone and its relay copy is on its way instead (also when send() said no). */
    fun videoFellBack(id: String): Boolean {
        val back = videoBack.remove(id) ?: return false
        if (id in stopped) return true
        again.remove(id)?.second?.delete()
        againGroup.remove(id)
        _all.value = _all.value - id
        saveKept()
        try { back() } catch (_: Exception) {}
        return true
    }

    /**
     * No way through for a file of mine: its card says [why] and keeps Retry (1.42: files never go through the relays).
     * A full-size video sends its smaller copy instead (cap v3, older apps).
     */
    @Suppress("UNUSED_PARAMETER")
    private fun fallBack(id: String, peer: String, file: File, name: String, why: String, group: String = "") {
        if (videoFellBack(id)) return
        fail(id, why)
    }

    /** What a file going straight over the internet says when Ultra privacy turns on (mine, then theirs). */
    const val ULTRA_STOPPED = "Stopped: direct files are off while Ultra privacy is on. It can go on the same Wi-Fi."
    const val ULTRA_STOPPED_IN = "Stopped: direct files are off while Ultra privacy is on. Ask them to send it on the same Wi-Fi."

    /** Interop: the "tried to send you" notice under Ultra privacy, at most once an hour per person. */
    private val ultraTold = ConcurrentHashMap<String, Long>()
    private fun ultraToldNow(peer: String): Boolean {
        val now = System.currentTimeMillis()
        val last = ultraTold[peer] ?: 0L
        if (last != 0L && now - last < 3_600_000L) return false
        ultraTold[peer] = now
        return true
    }

    /** A file of theirs that would have to come straight over the internet, refused under Ultra privacy: they hear so. */
    private fun refuseForUltra(id: String, inc: In) {
        if (ins.remove(id) == null) return
        decline(inc.peer, id)   // no "why": a small one then comes over the relays instead
        _all.value[id]?.let { put(it.copy(state = "failed", note = ULTRA_STOPPED_IN)) }
    }

    /**
     * Ultra privacy just turned on (TorRunner): files of theirs waiting for an answer that could only come straight
     * over the internet are refused now, so nothing is accepted that way later. Mine stop by themselves (in [send]
     * and while they move), within a quarter of a second.
     */
    fun ultraOn() {
        val h = host
        for ((id, inc) in ins.entries.toList()) {
            // Cap f4: Tor still carries it.
            if (inc.lanOnly || inc.lan != null || h?.lanAddress(inc.peer) != null || inc.tor) continue
            val st = _all.value[id]?.state
            if (st == "asking" || st == "waiting") refuseForUltra(id, inc)
        }
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
     * Whether a file can reach [peer] right now (the Send menu's "Encrypted file sharing" row): Nearby, Trusted direct or
     * Tor. False only when none can: no internet and not on the same Wi-Fi, or their app is too old ([reachNote] says
     * which).
     */
    fun canReach(peer: String): Boolean = reachNote(peer).isEmpty()

    /** "" when a file can reach [peer], else what the greyed row says: [UPDATE] or [NO_PATH]. */
    fun reachNote(peer: String): String {
        val h = host ?: return NO_PATH
        if (!h.hasF4(peer)) return UPDATE
        if (h.lanAddress(peer) != null && h.hasF2(peer)) return ""
        if (!h.online()) return NO_PATH
        val direct = h.trusted(peer) && UltraPrivacy.directAllowed
        return if (direct || (!UltraPrivacy.directAllowed && h.torAllowed()) || relayPlan(peer, "")) "" else noPathWhy(peer)
    }

    // Tor is Ultra privacy's alone (1.48): when nothing else reaches someone, the words say why and what to do.
    const val OUTSIDE = "Files reach people outside Family and trusted contacts on the same Wi-Fi, or with Trusted direct on for each other."
    const val OLDER_FAMILY = "Ask them to update, or send on the same Wi-Fi."
    const val MONTH_USED = "The relay's free limit is used this month. Send on the same Wi-Fi."

    /** Why nothing reaches [peer] now (online, not on this Wi-Fi): one of the lines above, else [NO_PATH]. */
    fun noPathWhy(peer: String): String {
        val h = host ?: return NO_PATH
        if (!UltraPrivacy.directAllowed) return NO_PATH
        if (!mayAlways(h.badge(peer))) return OUTSIDE
        if (!h.hasF5(peer)) return OLDER_FAMILY
        if (h.relayStopped()) return MONTH_USED
        return NO_PATH
    }

    /** Cap f5: the call relay may carry a file to [peer] now. */
    private fun relayPlan(peer: String, group: String): Boolean {
        val h = host ?: return false
        return MediaRoute.relayMay(h.routeFor(peer, group), !UltraPrivacy.directAllowed, mayAlways(h.badge(peer)), h.hasF5(peer)) && !h.relayStopped()
    }

    /**
     * Before sending [size] bytes to [peer]: the question to ask when it will most likely go over Tor and is over 1 GB
     * (TorFiles.WARN_BYTES), else null.
     */
    fun torWarning(peer: String, size: Long): String? {
        val h = host ?: return null
        val nearby = h.lanAddress(peer) != null && h.hasF2(peer)
        val direct = h.trusted(peer) && UltraPrivacy.directAllowed
        return TorFiles.warning(size, h.hasF4(peer) && !nearby && !direct && !relayPlan(peer, ""))
    }

    /** What a file's card says for each path ("lan", "direct", "tor"). */
    fun methodLabel(path: String, size: Long = 0): String = when (path) {
        "lan" -> TorFiles.NEARBY
        "direct", "internet" -> TorFiles.TRUSTED
        "relay" -> VIA_RELAY
        else -> TorFiles.torLabel(size)
    }

    /**
     * Sends [file] to [peer]: Nearby, Trusted direct or Tor (cap f4); never through the relays. Null when it started,
     * else why not: "update" (their app is too old: [UPDATE]) or "no path" ([NO_PATH]). [group]: a group's file. [vid]: a
     * full-size video (cap v3; agent V's video path), which also goes to older apps on Nearby or Trusted direct.
     */
    fun send(peer: String, file: File, name: String, id: String = Proto06.newLogicalId(), group: String = "", vid0: Int = 0): String? {
        val h = host ?: return "Not ready."
        // Retry of a video (or of my big video's copy): it goes again as the video, linked to its bubble.
        val carried = carry.remove(file.path)
        val vid = if (vid0 == 0 && carried != null) carried.first else vid0
        carried?.second?.let { (chat, msgId) -> bigOf[id] = chat to msgId }
        if (!h.hasF1(peer)) return "update"
        if (group.isNotEmpty() && !h.hasF2(peer)) return "update"
        val f4 = h.hasF4(peer)
        // 1.42: a file (not a full-size video) goes only to an app that says f4.
        if (vid == 0 && !f4) return "update"
        val lan = h.lanAddress(peer)?.takeIf { h.hasF2(peer) }
        // Ultra privacy: never straight over the internet (no STUN, no address swapped); Nearby and Tor still work.
        val choice = h.routeFor(peer, group)
        val ultra = !UltraPrivacy.directAllowed
        val family = mayAlways(h.badge(peer))
        val peerCan = h.hasF5(peer)
        val trust = h.trusted(peer) && MediaRoute.directMay(choice, ultra)
        val relay = MediaRoute.relayMay(choice, ultra, family, peerCan) && !h.relayStopped()
        // Tor only with Ultra privacy on.
        val tor = f4 && h.torAllowed() && MediaRoute.torMay(ultra)
        if (lan == null && !trust && !tor && !relay) return "no path"
        val lanOnly = !trust
        if (id in stopped) { file.delete(); return null }   // Cancel while it was being made ready
        val size = file.length()
        again[id] = Triple(peer, file, name)
        if (group.isNotEmpty()) againGroup[id] = group
        if (vid > 0) againVid[id] = vid
        put(Progress(id, peer, name, size, true, "connecting", group = group, route = if (lan != null && lanOnly) methodLabel("lan") else ""))
        saveKept()   // closed by Android part way, it comes back as a stopped card with Retry
        work.execute {
            try {
                val known = hashed[file.absolutePath]
                val sha = known?.takeIf { it.first == size && it.second == file.lastModified() }?.third
                    ?: sha256(file).also { hashed[file.absolutePath] = Triple(size, file.lastModified(), it) }
                if (id in stopped) return@execute
                val sock = DatagramSocket()
                // Not trusted with addresses: only the "lan" candidate (or none: Tor), and no STUN server is asked.
                val cands = if (lanOnly) emptyList() else candidates(sock)
                if (cands.isEmpty() && lan == null && !tor && !relay) { sock.close(); fallBack(id, peer, file, name, "No internet connection that can go direct. Try on the same Wi-Fi.", group); return@execute }
                val keys = ChatCrypto.newKeyPair()
                val out = Out(peer, file, name, size, sha, keys, sock, cands, group, lanOnly, tor, relay, MediaRoute.punchMs(choice, relay))
                outs[id] = out
                val c = candsJson(cands)
                val cs = JSONArray()
                if (lan != null) cs.put(lanCand(sock.localPort))
                for (i in 0 until c.length()) cs.put(c.get(i))
                val offer = JSONObject().put("t", "offer").put("id", id).put("name", name).put("size", size).put("sha", sha)
                    .put("e", ChatCrypto.b64(keys.public.encoded)).put("c", cs)
                if (group.isNotEmpty()) offer.put("g", group)
                if (vid > 0) offer.put("vid", vid)
                if (relay) offer.put("rl", 1)
                h.signal(peer, offer.toString())
                put(Progress(id, peer, name, size, true, "offered", group = group))
                val long = h.resumes(peer)
                // Cap f4: queued for a day ("Sends when Jo's phone is online"), the offer sent again now and then. Cap v3
                // to an older app: a full-size video waits only as long as a small file; then its smaller copy goes.
                // Family and trusted contacts: a phone that's offline gets it when it's back, up to a week.
                val wait = if (videoBack.containsKey(id) && !f4) RELAY_AFTER_MS else if (family) MediaRoute.FAMILY_WAIT_MS else answerMs(size, long, f4)
                val began = System.currentTimeMillis()
                val until = began + wait
                // A STUN ask a minute keeps this socket's way through the router open, so the address in the offer still
                // works when they accept.
                var lastStun = began
                var lastOffer = began
                var queued = false
                // Ultra privacy turned on while it waits: an offer that carried this phone's internet address stops now
                // (no more STUN, no accept straight over the internet later). Cap f4: it goes on, by Nearby or Tor.
                var ultraCut = false
                while (System.currentTimeMillis() < until && _all.value[id]?.state == "offered" && id !in stopped) {
                    Thread.sleep(250)
                    val now = System.currentTimeMillis()
                    if (!lanOnly && !UltraPrivacy.directAllowed && !tor) { ultraCut = true; break }
                    if (relay && !UltraPrivacy.directAllowed && lan == null && !tor) { ultraCut = true; break }
                    if (long && !lanOnly && now - lastStun >= 60_000L && UltraPrivacy.directAllowed) {
                        lastStun = now
                        if (_all.value[id]?.state == "offered") stunKeep(sock)
                    }
                    if (f4 && !queued && !out.seen && now - began >= RELAY_AFTER_MS) {
                        queued = true
                        _all.value[id]?.takeIf { it.state == "offered" }?.let { put(it.copy(note = queuedNote(h.nameOf(peer), h.deviceWord(peer)))) }
                    }
                    if (f4 && !out.seen && now - lastOffer >= (if (family) REOFFER_MS / 4 else REOFFER_MS)) { lastOffer = now; h.signal(peer, offer.toString()) }
                }
                if (id in stopped) { outs.remove(id)?.sock?.close(); return@execute }
                if (ultraCut && _all.value[id]?.state == "offered") {
                    outs.remove(id)?.sock?.close()
                    fallBack(id, peer, file, name, ULTRA_STOPPED, group)
                    return@execute
                }
                if (_all.value[id]?.state == "offered") {
                    outs.remove(id)?.sock?.close()
                    fallBack(id, peer, file, name, if (f4) NOT_SENT else if (long && size > FileText.RELAY_MAX) "Not accepted within a day. Try again." else "They're offline. Try again when they're back.", group)
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

    private fun g0(p: JSONObject) = p.optString("g")


    /** Offers of theirs already answered here (declined, taken, or gone): a queued offer sent again doesn't ask twice. */
    private val answered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A `file` message from [from] (already opened and checked: ok()). */
    fun onSignal(from: String, p: JSONObject) {
        val h = host ?: return
        val id = p.getString("id")
        when (p.getString("t")) {
            "offer" -> {
                if (ins.containsKey(id) || id in answered) return
                // Round 19 (interop 3, K19-4): a Kids' Device takes no full-size video. "rules" at once, so the sender's
                // app sends the smaller relay copy now instead of waiting for an answer.
                if (p.has("vid") && h.isKidDevice()) { decline(from, id, "rules"); return }
                val name = p.getString("name")
                val size = p.getLong("size")
                val g = p.optString("g")
                val c = p.getJSONArray("c")
                val vid = p.optInt("vid", 0)
                val here = h.routeFor(from, g0(p))
                val trust = h.trusted(from) && MediaRoute.directMay(here, !UltraPrivacy.directAllowed)   // Ultra privacy: Nearby or Tor only
                val lan = lanTarget(h.lanAddress(from), c)
                // Cap f5: they want the call relay. Taken when they're Family or trusted here too, and this side allows it.
                val wants = p.optInt("rl") == 1
                val relay = wants && h.hasF5(from) && MediaRoute.relayMay(here, !UltraPrivacy.directAllowed, mayAlways(h.badge(from)), true)
                // Cap f4: Tor can carry it (both say f4, and Tor may start here). A sender asking for the relay serves no Tor.
                val tor = h.hasF4(from) && h.torAllowed() && !wants
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
                // Addresses are only swapped with someone trusted; on the same Wi-Fi nothing new is learned; over Tor
                // neither side learns the other's address.
                if (!trust && lan == null && !tor && !relay) {
                    // Cap f5: this side only takes Tor (Ultra privacy, or chosen): their card says what to do.
                    if (wants) { decline(from, id, "tor"); return }
                    // "why" stays empty: an older sender then says its own words.
                    decline(from, id)
                    if (h.trusted(from) && !UltraPrivacy.directAllowed && ultraToldNow(from))
                        h.notice("${h.nameOf(from)} tried to send you $name (${FileText.size(size)}). With Ultra privacy on it can come on the same Wi-Fi. Ask them to update to send over Tor.")
                    return
                }
                // What's already on its way here counts too, so many offers at once can't each pass alone.
                if (h.freeBytes() < size + 64L * 1024 * 1024 + ins.values.sumOf { it.size }) {
                    decline(from, id, "space")
                    refused(from, id, name, size, g, "Not enough space for it on this phone (${FileText.size(h.freeBytes())} free).")
                    h.notice("${h.nameOf(from)} tried to send you $name, but there isn't enough space.")
                    return
                }
                val direct = if (trust) CallLogic.directOf(c) else emptyList()
                val theirs = listOfNotNull(lan) + direct
                val inc = In(from, name, size, p.getString("sha"), p.getString("e"), theirs, g, !trust, lan, tor, relay, MediaRoute.punchMs(here, relay), vid)
                ins[id] = inc
                val folder = Sharing.folderFor("Muninn", if (g.isEmpty()) h.nameOf(from) else h.groupName(g))
                val badge = h.badge(from)
                // Family and trusted contacts' files arrive by themselves; a Kids' Device asks unless set to Always.
                val always = mayAlways(badge) && !h.asksFirst(from) && (!h.isKidDevice() || h.alwaysFrom(from))
                // Cap f3: the rest of a file already accepted (it stopped part way) doesn't ask again.
                val resuming = partialFor(from, inc.sha, size, g) != null
                val method = methodLabel(if (lan != null) "lan" else if (direct.isNotEmpty()) "direct" else if (relay) "relay" else "tor", size)
                if (arrivesByItself(always, resuming, h.mayDownload())) {
                    if (h.hasF4(from)) h.signal(from, JSONObject().put("t", "wait").put("id", id).toString())
                    accept(id); return
                }
                // Cap f4: the sender hears the card is up ("Waiting for Ana to accept", not "Sends when...").
                if (h.hasF4(from)) h.signal(from, JSONObject().put("t", "wait").put("id", id).toString())
                val day = h.resumes(from)
                put(Progress(id, from, inc.name, inc.size, false, "asking",
                    note = if (day) "Wants to send you this. Waits a day for your answer" else "Wants to send you this. Accept or decline", group = g,
                    folder = Received.shown(folder), route = method, badge = badge, method = method, vid = vid))
                val text = "${h.nameOf(from)} wants to send you ${typeWord(inc.name)}: ${inc.name} (${FileText.size(inc.size)}). Accept or decline it in the chat or Transfers."
                h.notice(text)
                h.offered(if (g.isEmpty()) from else "g:$g", from, text)
                expireAsk(id, if (day) LONG_ANSWER_MS else ASK_ANSWER_MS)
            }
            // Cap f4: their card asks them now.
            "wait" -> outs[id]?.takeIf { it.peer == from }?.let { o ->
                o.seen = true
                _all.value[id]?.takeIf { it.state == "offered" }?.let { put(it.copy(note = "")) }
            }
            // Cap f5: their relayed address for this file. Only for a transfer with them, only where the relay may carry it.
            "relay" -> if ((outs[id]?.takeIf { it.peer == from }?.relay == true || ins[id]?.takeIf { it.peer == from }?.relay == true) && UltraPrivacy.directAllowed)
                CallLogic.literal(p.getString("a"))?.let { relayTo[id] = InetSocketAddress(it, p.getInt("p")) }
            // Cap f5: they already have this file. Only from the person it was offered to.
            "done" -> outs[id]?.takeIf { it.peer == from }?.let { o ->
                outs.remove(id)
                o.sock.close()
                put(Progress(id, o.peer, o.name, o.size, true, "done", o.size, group = o.group))
                again.remove(id)?.second?.delete()
                againGroup.remove(id)
                saveKept()
            }
            // Cap f4: the sender's one-time onion service for this file.
            "tor" -> ins[id]?.takeIf { it.peer == from && it.tor }?.let { inc ->
                if (!TorFiles.torOk(p)) return
                inc.onion = p.getString("tor") to p.getInt("p")
            }
            "accept" -> {
                val o = outs[id] ?: return
                if (o.peer != from) return
                val theirs = try { ChatCrypto.publicFrom(p.getString("e")) } catch (e: Exception) { return }
                val key = key(ChatCrypto.sharedKey(o.keys.private, o.keys.public, theirs), id)
                val c = p.getJSONArray("c")
                val lan = lanTarget(h.lanAddress(from), c)
                // Ultra privacy (on since the offer went): never straight over the internet, only Nearby or Tor.
                val direct = !o.lanOnly && UltraPrivacy.directAllowed
                val targets = listOfNotNull(lan) + (if (direct) CallLogic.directOf(c) else emptyList())
                if (targets.isEmpty() && !o.tor && !o.relay) return   // the offer's own wait ends it and says so
                put(Progress(id, o.peer, o.name, o.size, true, "connecting", group = o.group))
                work.execute { runSender(id, o, key, targets, lan) }
            }
            "decline" -> outs[id]?.takeIf { it.peer == from }?.let {
                outs.remove(id)
                if (_all.value[id]?.state == "offered") {
                    it.sock.close()
                    // Refused (group rules, no space, they said no: 27.4) ends it. 1.42: "no" says "Declined".
                    val why = p.optString("why")
                    if (why == "no") { videoBack.remove(id); again.remove(id)?.second?.delete(); againGroup.remove(id); fail(id, DECLINED, retry = false); saveKept() }
                    else if (why.isNotEmpty()) fail(id, Sharing.declineText(why), retry = false)
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
                answered.add(id)
                _all.value[id]?.let { put(it.copy(state = "failed", note = "Not taken in time. Ask them to send it again.")) }
            }
        }
    }

    /** Decline: the sender's card says "Declined", and nothing is left behind here. */
    fun decline(id: String) {
        val inc = ins.remove(id) ?: return
        answered.add(id)
        decline(inc.peer, id, "no")
        _all.value = _all.value - id
    }

    /**
     * "Always accept from <name>": accept, and from now on their files arrive without asking. Only for someone trusted or
     * Family (the card shows it only for them); for anyone else it just accepts.
     */
    fun acceptAlways(id: String) {
        val inc = ins[id] ?: return
        host?.let { if (mayAlways(it.badge(inc.peer))) it.setAlways(inc.peer) }
        accept(id)
    }

    /** Download a waiting file now (tapped, or on Wi-Fi). */
    fun accept(id: String) {
        val inc = ins[id] ?: return
        val h = host ?: return
        if (_all.value[id]?.state?.let { it == "connecting" || it == "sending" } == true) return   // tapped twice
        answered.add(id)
        // Ultra privacy, turned on since it was offered: only Nearby or Tor, never straight over the internet.
        val direct = !inc.lanOnly && UltraPrivacy.directAllowed
        if (!direct && !inc.lanOnly && inc.lan == null && h.lanAddress(inc.peer) == null && !inc.tor && !inc.relay) { refuseForUltra(id, inc); return }
        put(Progress(id, inc.peer, inc.name, inc.size, false, "connecting", group = inc.group,
            note = if (inc.lan == null && !direct) "Connecting privately…" else ""))
        work.execute {
            try {
                val sock = DatagramSocket()
                val cands = if (!direct) emptyList() else candidates(sock)
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

    /** [retry]: false for what trying again can't change (they said no, group rules, no space there). */
    private fun fail(id: String, why: String, retry: Boolean = true) {
        if (id in stopped) return
        if (videoFellBack(id)) return
        _all.value[id]?.let { put(it.copy(state = "failed", note = why)) }
        if (!retry) noRetry.add(id)
        if (again.containsKey(id)) saveKept()   // its Retry card, with why, comes back after a restart
        if (!retry || !autoRetry(id)) host?.notice(why)
    }

    /** Sends a stopped card's file again: who, the file, its name and group (Chat sends it the usual way). */
    @Volatile var resend: ((String, File, String, String) -> Unit)? = null
    /** By-itself tries so far, by the file's path: each resend is a new transfer of the same file. */
    private val retries = ConcurrentHashMap<String, Int>()

    /**
     * A send to Family or a trusted contact that stopped is never a dead end: it goes again by itself, after a wait that
     * grows, while its card still says what's happening. True when one is set.
     */
    private fun autoRetry(id: String): Boolean {
        val h = host ?: return false
        val t = again[id] ?: return false
        if (!mayAlways(h.badge(t.first)) || resend == null) return false
        val key = t.second.path
        val n = retries.merge(key, 1) { a, b -> a + b } ?: 1
        if (n > MediaRoute.AUTO_RETRY_MAX) return false
        work.execute {
            val until = System.currentTimeMillis() + MediaRoute.retryAfter(n - 1)
            while (System.currentTimeMillis() < until) {
                if (_all.value[id]?.state != "failed" || !again.containsKey(id)) return@execute
                Thread.sleep(1000)
            }
            if (!h.online()) { autoRetry(id); return@execute }
            val group = retryGroup(id)
            takeRetry(id)?.let { (p, file, name) -> resend?.invoke(p, file, name, group) }
        }
        return true
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

    /** The foreground notification and how far a file is, while it moves (Muninn keeps going with the screen off). */
    private fun noteProgress(mine: Boolean, done: Long, size: Long) =
        host?.progressNote(if (mine) "Sending file" else "Receiving file", FileText.percent(done, size))

    private fun runSender(id: String, o: Out, key: ByteArray, theirs: List<InetSocketAddress>, lan: InetSocketAddress?) {
        var sock = o.sock
        val h = host
        h?.keepGoing(id, true, "Sending file")
        try {
            put(Progress(id, o.peer, o.name, o.size, true, "connecting", group = o.group))
            // Cap f4: no Nearby, Trusted direct may not get through: Tor starts now, so switching to it is quick.
            if (o.tor && lan == null) TorRunner.need(id)
            var peer = if (theirs.isEmpty()) null else punch(sock, key, id, 0, theirs, o.punchMs)
            val n = pieces(o.size)
            val s = Sender(n)
            // Cap f5: nothing got through: the call relay, when it may carry this one.
            var leg = if (peer == null && o.relay) relayLeg(id, o.peer, key, 0) else null
            if (leg != null) { sock = leg.sock; peer = leg.theirs }
            if (peer == null && !o.tor) {
                if (id !in stopped) fallBack(id, o.peer, o.file, o.name,
                    if (o.lanOnly) "Couldn't reach them on this Wi-Fi." else "Couldn't connect straight to them. Try on the same Wi-Fi.", o.group)
                return
            }
            var seq = 1000L
            sock.soTimeout = 50
            val buf = ByteArray(2048)
            var lastHeard = System.currentTimeMillis()
            var lastKeep = 0L
            var sent = 0L
            var firstAck = true
            var quiet = false
            val f3 = h?.resumes(o.peer) == true
            val who = h?.nameOf(o.peer) ?: "They"
            movingIds.add(id)
            started[id] = System.currentTimeMillis()
            val viaLan = lan != null && peer == lan
            var route = methodLabel(if (viaLan) "lan" else if (leg != null) "relay" else "direct")
            var toTor = peer == null
            var relayTried = 0L
            RandomAccessFile(o.file, "r").use { raf ->
                val chunk = ByteArray(CHUNK)
                if (peer != null) put(Progress(id, o.peer, o.name, o.size, true, "sending", route = route, group = o.group))
                while (peer != null && !s.done) {
                    if (id in stopped) return
                    // Ultra privacy turned on: a file going straight over the internet stops now (cap f4: on over Tor).
                    if ((!viaLan || leg != null) && !UltraPrivacy.directAllowed) {
                        legs.remove(id)?.close(); leg = null
                        if (o.tor) { toTor = true; break }
                        fail(id, ULTRA_STOPPED, retry = false); return
                    }
                    val now = System.currentTimeMillis()
                    // Cap f5: the direct way went quiet, or they moved to the relay: on through it. On it already: a new
                    // allocation before the login ends, and their new address when they renewed theirs.
                    if (o.relay && id !in pausedIds && id !in heldIds) {
                        val l = leg
                        val mayTry = now - relayTried >= MediaRoute.RELAY_TRY_EVERY_MS
                        if (l == null && (now - lastHeard > MediaRoute.SWITCH_MS || relayTo.containsKey(id)) && mayTry) {
                            relayTried = now
                            relayLeg(id, o.peer, key, 0)?.let { leg = it; sock = it.sock; peer = it.theirs; sock.soTimeout = 50
                                route = methodLabel("relay"); lastHeard = System.currentTimeMillis() }
                        } else if (l != null) {
                            val to = relayTo[id]
                            if (to != null && to != l.theirs) { l.retarget(to); peer = to; lastHeard = now }
                            if ((l.dueToRenew(now) || now - lastHeard > LONG_IDLE_MS / 2) && mayTry) {
                                relayTried = now
                                relayLeg(id, o.peer, key, 0, l)?.let { leg = it; sock = it.sock; peer = it.theirs; sock.soTimeout = 50 }
                            }
                        }
                    }
                    // Cap f4: quiet too long (they left the Wi-Fi, a router dropped the way through): on over Tor.
                    if (o.tor && now - lastHeard > SWITCH_MS && id !in pausedIds && id !in heldIds) { toTor = true; break }
                    if (now - lastHeard > (if (f3) LONG_IDLE_MS else IDLE_MS)) {
                        fail(id, if (f3) "Stopped at ${FileText.percent(sent, o.size)}% (no answer from $who). Retry goes on from there." else "Stopped (no answer from them). Try again.")
                        return
                    }
                    // Cap f3: quiet for a moment, a probe a second instead of data until they're heard again.
                    if (f3 && (now - lastHeard > QUIET_MS) != quiet) {
                        quiet = !quiet
                        put(Progress(id, o.peer, o.name, o.size, true, "sending", sent, note = if (quiet) "Waiting for $who to come back" else "", route = route, group = o.group))
                    }
                    val to = peer ?: break
                    if (resumed.remove(id)) repeat(3) { hold(sock, key, id, 0, seq++, false, to) }
                    when {
                        quiet && id !in pausedIds -> if (now - lastKeep >= 1000) {
                            val pk = seal(key, id, PROBE, 0, seq++, byteArrayOf(1)); sock.send(DatagramPacket(pk, pk.size, to)); lastKeep = now
                        }
                        // Paused here: Hold every second, no data. Held by them: a probe a second, no data.
                        id in pausedIds -> if (now - lastKeep >= 1000) { hold(sock, key, id, 0, seq++, true, to); lastKeep = now }
                        id in heldIds -> if (now - lastKeep >= 1000) {
                            val pk = seal(key, id, PROBE, 0, seq++, byteArrayOf(1)); sock.send(DatagramPacket(pk, pk.size, to)); lastKeep = now
                        }
                        else -> for (i in s.due(now)) {
                            raf.seek(i.toLong() * CHUNK)
                            val len = minOf(CHUNK.toLong(), o.size - i.toLong() * CHUNK).toInt()
                            raf.readFully(chunk, 0, len)
                            val payload = ByteBuffer.allocate(4 + len).putInt(i).put(chunk, 0, len).array()
                            val pk = seal(key, id, DATA, 0, seq++, payload)
                            sock.send(DatagramPacket(pk, pk.size, to))
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
                                noteProgress(true, sent, o.size)
                            }
                            if (t == DONE) { s.ack(n, 0); break }
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                }
                if (toTor && !s.done && !serveTor(id, o, key, s, raf)) return
            }
            put(Progress(id, o.peer, o.name, o.size, true, "done", o.size, route = _all.value[id]?.route.orEmpty(), group = o.group))
            host?.logFile(if (o.group.isEmpty()) o.peer else "g:${o.group}", o.name, o.size, "", true)
            again.remove(id)?.second?.delete()
            againGroup.remove(id)
            retries.remove(o.file.path)
            saveKept()
        } catch (e: Exception) { fail(id, "That file couldn't be sent.") } finally {
            outs.remove(id); o.sock.close(); legs.remove(id)?.close(); relayTo.remove(id); ended(id); TorRunner.release(id); h?.keepGoing(id, false)
        }
    }

    /**
     * Cap f4: the rest of a file of mine over Tor, from what [s] already has confirmed. Tor starts if it must, a one-time
     * onion service opens, its address goes in a sealed `tor` message, and every stream the receiver opens goes on from
     * its first ack. Closed when done, or after [TorFiles.IDLE_MS] with nothing moving (Retry goes on from there). True
     * when it all arrived.
     */
    private fun serveTor(id: String, o: Out, key: ByteArray, s: Sender, raf: RandomAccessFile): Boolean {
        val h = host ?: return false
        val who = h.nameOf(o.peer)
        val label = methodLabel("tor", o.size)
        put(Progress(id, o.peer, o.name, o.size, true, "connecting", minOf(o.size, s.below().toLong() * CHUNK), note = "Connecting privately…", route = label, group = o.group))
        val port = TorFiles.waitReady(id) { id in stopped }
        if (id in stopped) return false
        if (port == 0) { fail(id, "Couldn't connect over Tor. Retry goes on from where it stopped."); return false }
        val svc = TorFiles.publish() ?: run { fail(id, "Couldn't connect over Tor. Retry goes on from where it stopped."); return false }
        try {
            h.signal(o.peer, TorFiles.torMessage(id, svc.host, svc.port).toString())
            svc.server.soTimeout = 1000
            var lastMoved = System.currentTimeMillis()
            var gotFirst = false
            movingIds.add(id)
            put(Progress(id, o.peer, o.name, o.size, true, "sending", minOf(o.size, s.below().toLong() * CHUNK), route = label, group = o.group))
            while (id !in stopped) {
                if (System.currentTimeMillis() - lastMoved > TorFiles.IDLE_MS) {
                    fail(id, "Stopped at ${FileText.percent(s.below().toLong() * CHUNK, o.size)}% (no answer from $who). Retry goes on from there.")
                    return false
                }
                val c = try { svc.server.accept() } catch (_: java.net.SocketTimeoutException) { continue }
                c.use { conn ->
                    conn.tcpNoDelay = true
                    val end = TorFiles.serve(conn.getInputStream(), conn.getOutputStream(), key, id, o.size, s, raf, o.torSeq, object : TorFiles.Ctl {
                        override fun stopped() = id in stopped
                        override fun paused() = id in pausedIds
                        override fun held(on: Boolean) { if (on) heldIds.add(id) else heldIds.remove(id); refresh(id) }
                        override fun progress(done: Long) {
                            lastMoved = System.currentTimeMillis()
                            if (!gotFirst) { gotFirst = true; base[id] = done; started[id] = System.currentTimeMillis() }
                            put(Progress(id, o.peer, o.name, o.size, true, "sending", done, route = label, group = o.group))
                            noteProgress(true, done, o.size)
                        }
                    })
                    if (end == TorFiles.End.DONE) return true
                    if (end == TorFiles.End.STOPPED) return false
                }
            }
            return false
        } finally { svc.close() }
    }

    private fun runReceiver(id: String, inc: In, key: ByteArray, sock0: DatagramSocket) {
        var sock = sock0
        val h = host ?: return
        // Cap f3: the same file from the same person stopped part way before. It goes on from there.
        val kept = partialFor(inc.peer, inc.sha, inc.size, inc.group)?.let { k -> partials.remove(k)?.also { _all.value = _all.value - k; saveKept() } }
        val tmp = kept?.file ?: File(h.cacheDir(), "incoming-$id")
        val f3 = h.resumes(inc.peer)
        val who = h.nameOf(inc.peer)
        var keep = false
        h.keepGoing(id, true, "Receiving file")
        try {
            val theirs = if (!inc.lanOnly && !UltraPrivacy.directAllowed) listOfNotNull(inc.lan) else inc.theirCands
            var peer = if (theirs.isEmpty()) null else punch(sock, key, id, 1, theirs, inc.punchMs)
            var leg = if (peer == null && inc.relay) relayLeg(id, inc.peer, key, 1) else null
            if (leg != null) { sock = leg.sock; peer = leg.theirs }
            if (peer == null && !inc.tor) {
                fail(id, when {
                    inc.lanOnly -> "Couldn't reach them on this Wi-Fi. Ask them to send it again."
                    else -> "Couldn't connect to $who. Ask them to send it again when you're on the same Wi-Fi."
                })
                return
            }
            val viaLan = inc.lan != null && peer == inc.lan
            var route = methodLabel(if (viaLan) "lan" else if (leg != null) "relay" else "direct")
            var relayTried = 0L
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
            if (peer != null) put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", from, route = route, group = inc.group))
            var toTor = peer == null
            /** Kept to go on from (cap f3), with [why] on its card. */
            fun keepPart(why: (Partial) -> String) {
                val part = Partial(inc.peer, inc.sha, inc.size, inc.group, tmp, r.ack().first, inc.name)
                partials[id] = part
                saveKept()
                keep = true
                fail(id, why(part))
            }
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(inc.size)
                while (peer != null && !r.done) {
                    if (id in stopped) return
                    // Cap f4: the sender went on over Tor (its `tor` message came, and nothing comes here any more).
                    if (inc.onion != null && System.currentTimeMillis() - lastHeard > QUIET_MS) { toTor = true; break }
                    // Ultra privacy turned on: a file coming straight over the internet stops now (cap f4: on over Tor).
                    // The sender hears so; with cap f3 what came is kept, and it goes on from there when it's sent again.
                    if ((!viaLan || leg != null) && !UltraPrivacy.directAllowed) {
                        legs.remove(id)?.close(); leg = null
                        if (inc.tor) { toTor = true; break }
                        decline(inc.peer, id)
                        if (f3) keepPart { ULTRA_STOPPED_IN } else fail(id, ULTRA_STOPPED_IN)
                        return
                    }
                    val now = System.currentTimeMillis()
                    // Cap f5: as the sender does (runSender): on through the call relay, or a new allocation before the login ends.
                    if (inc.relay && id !in pausedIds && id !in heldIds) {
                        val l = leg
                        val mayTry = now - relayTried >= MediaRoute.RELAY_TRY_EVERY_MS
                        if (l == null && (now - lastHeard > MediaRoute.SWITCH_MS || relayTo.containsKey(id)) && mayTry) {
                            relayTried = now
                            relayLeg(id, inc.peer, key, 1)?.let { leg = it; sock = it.sock; peer = it.theirs; sock.soTimeout = 50
                                route = methodLabel("relay"); lastHeard = System.currentTimeMillis() }
                        } else if (l != null) {
                            val to = relayTo[id]
                            if (to != null && to != l.theirs) { l.retarget(to); peer = to; lastHeard = now }
                            if ((l.dueToRenew(now) || now - lastHeard > LONG_IDLE_MS / 2) && mayTry) {
                                relayTried = now
                                relayLeg(id, inc.peer, key, 1, l)?.let { leg = it; sock = it.sock; peer = it.theirs; sock.soTimeout = 50 }
                            }
                        }
                    }
                    // Cap f4: quiet this long, the sender goes on over Tor; waiting for its address is the next step.
                    if (inc.tor && now - lastHeard > SWITCH_MS && id !in pausedIds && id !in heldIds) { toTor = true; break }
                    if (now - lastHeard > (if (f3) LONG_IDLE_MS else IDLE_MS)) {
                        if (f3) keepPart { stoppedNote(it, who) } else fail(id, "Stopped (no answer from them). Ask them to send it again.")
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
                    val to = peer ?: break
                    if (resumed.remove(id)) { repeat(3) { hold(sock, key, id, 1, seq++, false, to) }; lastAck = 0L }
                    if (id in pausedIds) {
                        // Paused here: Hold every second instead of acks.
                        if (t - lastAck >= 1000) { hold(sock, key, id, 1, seq++, true, to); lastAck = t }
                    } else if (fresh >= 16 || t - lastAck >= (if (id in heldIds || quiet) 1000L else 40L)) {
                        val (below, bits) = r.ack()
                        val pk = seal(key, id, ACK, 1, seq++, ByteBuffer.allocate(12).putInt(below).putLong(bits).array())
                        sock.send(DatagramPacket(pk, pk.size, to))
                        lastAck = t; fresh = 0
                        put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", minOf(inc.size, below.toLong() * CHUNK),
                            note = if (quiet) "Waiting for $who to come back" else "", route = route, group = inc.group))
                        noteProgress(false, minOf(inc.size, below.toLong() * CHUNK), inc.size)
                    }
                }
                peer?.let { to -> if (r.done) repeat(3) { i ->
                    val pk = seal(key, id, DONE, 1, seq++, ByteArray(0)); sock.send(DatagramPacket(pk, pk.size, to))
                    if (leg != null && i < 2) Thread.sleep(150)   // the relay drops a burst more easily than a line
                } }
                if (toTor && !r.done) {
                    route = methodLabel("tor", inc.size)
                    when (receiveTor(id, inc, key, r, raf, route)) {
                        true -> {}
                        false -> { if (id !in stopped) { if (f3) keepPart { stoppedNote(it, who) } else fail(id, "Couldn't connect over Tor. Ask them to send it again.") }; return }
                        null -> return
                    }
                }
            }
            put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", inc.size, note = "Checking it", route = route, group = inc.group))
            if (sha256(tmp) != inc.sha) { fail(id, "${inc.name} arrived damaged, so it was thrown away."); return }
            // A video from Family or trusted, up to 3 minutes: in the chat like a photo, and its card goes.
            if (BigVideo.inline(inc.vid, mayAlways(h.badge(inc.peer))) && !h.isKidDevice() && h.inlineVideo(inc.peer, inc.group, tmp, inc.vid)) {
                _all.value = _all.value - id
                return
            }
            // In Download/Muninn/<chat name>, never a hidden app folder.
            val chat = if (inc.group.isEmpty()) h.nameOf(inc.peer) else h.groupName(inc.group)
            val folder = Sharing.folderFor("Muninn", chat)
            val saved = h.saveIn(inc.name, tmp, folder) ?: run { fail(id, "Not enough space to save ${inc.name}."); return }
            put(Progress(id, inc.peer, saved.name, inc.size, false, "done", inc.size, uri = saved.uri, route = route, group = inc.group, folder = Received.shown(folder)))
            h.logFile(if (inc.group.isEmpty()) inc.peer else "g:${inc.group}", saved.name, inc.size, saved.uri, false)
            h.notice("File from ${h.nameOf(inc.peer)}: ${saved.name} · Saved in ${Received.shown(folder)}" + if (risky(saved.name)) ". $RISKY_NOTE" else "")
        } catch (e: Exception) { fail(id, "That file didn't arrive. Ask them to send it again.") } finally {
            ins.remove(id); sock0.close(); legs.remove(id)?.close(); relayTo.remove(id); if (!keep) tmp.delete(); ended(id); TorRunner.release(id); h.keepGoing(id, false)
        }
    }

    /**
     * Cap f4: the rest of a file of theirs over Tor. Waits for the sender's `tor` message (Tor starts here meanwhile),
     * then connects through this phone's Tor and goes on from what [r] has; a stream that breaks is opened again. True
     * when it all arrived, false when it couldn't (kept to go on from, cap f3), null when stopped here.
     */
    private fun receiveTor(id: String, inc: In, key: ByteArray, r: Receiver, raf: RandomAccessFile, label: String): Boolean? {
        val h = host ?: return false
        put(Progress(id, inc.peer, inc.name, inc.size, false, "connecting", minOf(inc.size, r.ack().first.toLong() * CHUNK),
            note = "Connecting privately…", route = label, group = inc.group))
        val port = TorFiles.waitReady(id) { id in stopped }
        if (id in stopped) return null
        if (port == 0) return false
        var lastMoved = System.currentTimeMillis()
        var gotFirst = false
        movingIds.add(id)
        while (id !in stopped) {
            if (System.currentTimeMillis() - lastMoved > TorFiles.IDLE_MS) return false
            val onion = inc.onion
            if (onion == null) { Thread.sleep(500); continue }
            val c = try { TorFiles.connect(TorRunner.filesPort.takeIf { it > 0 } ?: port, onion.first, onion.second) }
                catch (_: Exception) { Thread.sleep(3000); continue }
            c.use { conn ->
                val end = TorFiles.receive(conn.getInputStream(), conn.getOutputStream(), key, id, inc.size, r, raf, inc.torSeq, object : TorFiles.Ctl {
                    override fun stopped() = id in stopped
                    override fun paused() = id in pausedIds
                    override fun held(on: Boolean) { if (on) heldIds.add(id) else heldIds.remove(id); refresh(id) }
                    override fun progress(done: Long) {
                        lastMoved = System.currentTimeMillis()
                        if (!gotFirst) { gotFirst = true; base[id] = done; started[id] = System.currentTimeMillis() }
                        put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", done, route = label, group = inc.group))
                        noteProgress(false, done, inc.size)
                    }
                })
                if (end == TorFiles.End.DONE) return true
                if (end == TorFiles.End.STOPPED) return null
            }
            Thread.sleep(2000)
        }
        return null
    }

    /**
     * Both sides send probes to every candidate of the other at once, which opens both routers. The first authentic
     * probe that comes back says which address works. Null after [PUNCH_MS].
     */
    private fun punch(sock: DatagramSocket, key: ByteArray, id: String, dir: Int, theirs: List<InetSocketAddress>, ms: Long = PUNCH_MS): InetSocketAddress? {
        if (theirs.isEmpty()) return null
        sock.soTimeout = 100
        val until = System.currentTimeMillis() + ms
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

    // ------------------------------------------------------------------ cap f5: the call relay

    /** Each transfer's relayed address of the other side, once its `relay` message came. */
    private val relayTo = ConcurrentHashMap<String, InetSocketAddress>()
    private val legs = ConcurrentHashMap<String, Leg>()

    /**
     * One side's way through the call relay: looks like a UDP socket to the transfer's loops, so everything sealed, acked and
     * resumed as on the direct way goes through a TURN allocation. The relay sees only sealed pieces.
     */
    private class RelaySock(private val turn: io.github.ravenor907.muninn.net.call.TurnClient, val box: java.util.concurrent.LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>,
                            private val used: (Long) -> Unit) : DatagramSocket(null as java.net.SocketAddress?) {
        @Volatile private var wait = 50
        override fun setSoTimeout(timeout: Int) { wait = timeout }
        override fun getSoTimeout() = wait
        override fun send(p: DatagramPacket) {
            turn.send(p.socketAddress as InetSocketAddress, p.data.copyOfRange(p.offset, p.offset + p.length))
            used(p.length.toLong())
        }
        override fun receive(p: DatagramPacket) {
            val (from, b) = box.poll(wait.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: throw java.net.SocketTimeoutException()
            val n = minOf(b.size, p.data.size)
            System.arraycopy(b, 0, p.data, 0, n)
            p.length = n
            p.socketAddress = from
            used(n.toLong())
        }
        override fun close() { try { turn.close() } catch (_: Exception) {}; super.close() }
    }

    private class Leg(val turn: io.github.ravenor907.muninn.net.call.TurnClient, val sock: RelaySock, @Volatile var theirs: InetSocketAddress, val expiresMs: Long) {
        fun dueToRenew(now: Long) = expiresMs - now < MediaRoute.RENEW_BEFORE_MS
        fun retarget(to: InetSocketAddress) { turn.permit(to); theirs = to }
        fun close() = try { sock.close() } catch (_: Exception) {}
    }

    /**
     * An allocation for this transfer: my relayed address goes in a sealed `relay` message (again until theirs comes), and
     * both prove they hear each other with the file's own probes. [old]: a leg being renewed: it keeps reading into the same
     * queue for [MediaRoute.HANDOVER_MS], until the other side has the new address. Null when the relay can't be had; the
     * transfer's own wait then decides what's next.
     */
    private fun relayLeg(id: String, peer: String, key: ByteArray, dir: Int, old: Leg? = null): Leg? {
        val h = host ?: return null
        if (!UltraPrivacy.directAllowed || id in stopped) return null
        val login = try { h.relayLogin() } catch (_: Exception) { null } ?: return null
        if (old == null) _all.value[id]?.let { put(it.copy(state = "connecting", note = "Connecting through the relay…", route = VIA_RELAY)) }
        val box = old?.sock?.box ?: java.util.concurrent.LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>(4096)
        val turn = io.github.ravenor907.muninn.net.call.TurnClient(login, { io.github.ravenor907.muninn.net.call.TurnClient.lookup(it) }) { from, data -> box.offer(from to data) }
        try {
            if (!turn.start()) { turn.close(); return null }
            val meter = java.util.concurrent.atomic.AtomicLong()
            val sock = RelaySock(turn, box) { n -> if (meter.addAndGet(n) >= 1_000_000L) h.relayUsed(meter.getAndSet(0)) }
            val mineAt = turn.relayed ?: run { sock.close(); return null }
            val message = JSONObject().put("t", "relay").put("id", id).put("a", mineAt.address.hostAddress?.substringBefore('%')).put("p", mineAt.port).toString()
            h.signal(peer, message)
            val until = System.currentTimeMillis() + MediaRoute.WAIT_MS
            var last = System.currentTimeMillis()
            while (relayTo[id] == null && System.currentTimeMillis() < until && id !in stopped && UltraPrivacy.directAllowed) {
                Thread.sleep(200)
                if (System.currentTimeMillis() - last >= 8_000L) { last = System.currentTimeMillis(); h.signal(peer, message) }
            }
            val theirs = relayTo[id]
            if (theirs == null || !UltraPrivacy.directAllowed) { sock.close(); return null }
            turn.permit(theirs)
            if (old == null && punch(sock, key, id, dir, listOf(theirs)) == null) { sock.close(); relayTo.remove(id); return null }
            val leg = Leg(turn, sock, theirs, login.expiresMs)
            legs[id] = leg
            if (old != null) work.execute {
                // Said once more, and the old allocation lets go once the other side has moved.
                Thread.sleep(8_000L)
                if (legs[id] === leg) h.signal(peer, message)
                Thread.sleep(MediaRoute.HANDOVER_MS - 8_000L)
                old.close()
            }
            _all.value[id]?.let { put(it.copy(state = "sending", note = "", route = VIA_RELAY)) }
            return leg
        } catch (e: Exception) { try { turn.close() } catch (_: Exception) {}; return null }
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
        if (!UltraPrivacy.directAllowed) throw IllegalStateException("Ultra privacy")
        val (server, port) = Stun.SERVERS.first()
        val req = Stun.encode(Stun.type(Stun.BINDING, Stun.REQUEST), Stun.newTid(), emptyList(), fingerprint = true)
        sock.send(DatagramPacket(req, req.size, InetSocketAddress(InetAddress.getByName(server), port)))
    } catch (_: Exception) {}

    /** Asks each server in [Stun.SERVERS] in turn until one answers. */
    private fun stun(sock: DatagramSocket): InetSocketAddress? {
        for ((host, port) in Stun.SERVERS) stunOne(sock, host, port)?.let { return it }
        return null
    }

    private fun stunOne(sock: DatagramSocket, host: String, port: Int): InetSocketAddress? = if (!UltraPrivacy.directAllowed) null else try {
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
