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
 */
object FileLink {
    const val CAP = "f1"
    const val CHUNK = 1100
    const val WINDOW = 96
    const val RTO_MS = 500L
    const val PUNCH_MS = 20_000L
    const val IDLE_MS = 30_000L
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    /** From someone trusted, a file smaller than this arrives by itself; a bigger one asks Accept or Decline first. */
    const val AUTO_BYTES = 50L * 1024 * 1024
    /** How long an offer waits for an answer: longer for a file the other phone asks about first. */
    const val ANSWER_MS = 120_000L
    const val ASK_ANSWER_MS = 10 * 60_000L

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
    fun answerMs(size: Long): Long = if (size <= FileText.RELAY_MAX) RELAY_AFTER_MS else ASK_ANSWER_MS

    const val PROBE: Byte = 1
    const val DATA: Byte = 2
    const val ACK: Byte = 3
    const val DONE: Byte = 4

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
            while (next < pieces && inflight < WINDOW) { out += next; next++; inflight++ }
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
     * One transfer, as the chat shows it. [state]: "offered", "asking" (a big file: Accept or Decline), "waiting" (data
     * saver), "connecting", "sending", "done", "failed".
     */
    data class Progress(val id: String, val peer: String, val name: String, val size: Long, val mine: Boolean, val state: String,
                        val done: Long = 0, val note: String = "",
                        /** Theirs, once saved: where it is in Downloads (a content URI), for Open. */
                        val uri: String = "")

    /** A file saved in Downloads: the name it got and its content URI. */
    data class Saved(val name: String, val uri: String)

    private val _all = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val all: StateFlow<Map<String, Progress>> = _all.asStateFlow()
    private fun put(p: Progress) { _all.value = _all.value + (p.id to p) }

    interface Host {
        /** Seal [json] (room "file") for [peer] and send it on every route. */
        fun signal(peer: String, json: String)
        fun trusted(peer: String): Boolean
        fun hasF1(peer: String): Boolean
        /** Their app waits for a big file to be accepted ([ASK_CAP]). */
        fun waitsForAsk(peer: String): Boolean = true
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
    }

    @Volatile var host: Host? = null
    private val work = Executors.newCachedThreadPool { r -> Thread(r, "muninn-file").apply { isDaemon = true } }
    private class Out(val peer: String, val file: File, val name: String, val size: Long, val sha: String, val keys: java.security.KeyPair,
                      val sock: DatagramSocket, val cands: List<InetSocketAddress>)
    private class In(val peer: String, val name: String, val size: Long, val sha: String, val theirKey: String, val theirCands: List<InetSocketAddress>)
    private val outs = ConcurrentHashMap<String, Out>()
    private val ins = ConcurrentHashMap<String, In>()
    /** Stopped with Cancel: its threads end quietly. */
    private val stopped: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Mine, while it can be sent again (Retry): who to, the file and its name. */
    private val again = ConcurrentHashMap<String, Triple<String, File, String>>()

    /** A file of mine being made ready (copied in): its card shows at once. Returns its id for [send]. */
    fun preparing(peer: String, name: String, size: Long): String {
        val id = Proto06.newLogicalId()
        put(Progress(id, peer, name, size, true, "connecting"))
        return id
    }

    /** A file of mine that can't go at all: its card says why. */
    fun failed(peer: String, name: String, size: Long, why: String, id: String = Proto06.newLogicalId()) {
        put(Progress(id, peer, name, size, true, "failed", note = why))
    }

    fun isStopped(id: String) = id in stopped

    /** Cancel: a file still on its way stops (the other side stops after 30 s of silence). */
    fun cancel(id: String) {
        val p = _all.value[id] ?: return
        if (!FileText.running(p.state) && p.state != "asking" && p.state != "waiting") return
        stopped.add(id)
        if (!p.mine) ins.remove(id)?.let { host?.signal(it.peer, JSONObject().put("t", "decline").put("id", id).toString()) }
        put(p.copy(state = "failed", note = "Stopped"))
    }

    /** Close: a finished or failed card goes. */
    fun dismiss(id: String) {
        val p = _all.value[id] ?: return
        if (FileText.running(p.state)) return
        again.remove(id)?.second?.delete()
        _all.value = _all.value - id
    }

    /** A card of mine whose file went over the internet route instead: the chat's own message takes over from here. */
    fun handedOff(id: String) {
        again.remove(id)
        _all.value = _all.value - id
    }

    /** Cancel shows: mine while it runs, theirs while it comes straight (one over the internet route just arrives). */
    fun canCancel(id: String) = _all.value[id]?.let { FileText.running(it.state) && (it.mine || ins.containsKey(id)) } == true

    fun canRetry(id: String) = again.containsKey(id) && _all.value[id]?.state == "failed"

    /** Retry: the card goes and its file comes back to be sent again (Chat.sendFile). */
    fun takeRetry(id: String): Triple<String, File, String>? {
        if (!canRetry(id)) return null
        val t = again.remove(id) ?: return null
        _all.value = _all.value - id
        return t.takeIf { it.second.isFile }
    }

    /** A file of theirs coming over the internet route ([have] of [n] pieces): its card shows how far. */
    fun relayIn(msgId: String, peer: String, name: String, have: Int, n: Int) {
        put(Progress(msgId, peer, name, n.toLong() * Photos.PIECE, false, "sending", have.toLong() * Photos.PIECE))
    }

    /** It arrived (the chat's message takes over), or half of it was let go. A copy offered straight goes too. */
    fun relayInDone(msgId: String, peer: String = "", name: String = "") {
        _all.value = _all.value.filterValues { p ->
            p.id != msgId && !(name.isNotEmpty() && !p.mine && p.peer == peer && p.name == name && p.state in setOf("asking", "waiting", "failed"))
        }
        if (name.isNotEmpty()) ins.entries.removeIf { it.value.peer == peer && it.value.name == name && _all.value[it.key] == null }
    }

    /** No straight path for a file of mine: a small one goes over the internet route instead, else its card says [why]. */
    private fun fallBack(id: String, peer: String, file: File, name: String, why: String) {
        val h = host
        if (h != null && file.isFile && file.length() <= FileText.RELAY_MAX) {
            again.remove(id)
            _all.value = _all.value - id   // the chat's own message takes over from here
            h.relay(peer, file, name)?.let { failed(peer, name, file.length(), it, id) }
            return
        }
        fail(id, why)
    }

    /** Sends [file] to [peer] directly. Null when it started, else why not (then Chat may use the relays for a small one). */
    fun send(peer: String, file: File, name: String, id: String = Proto06.newLogicalId()): String? {
        val h = host ?: return "Not ready."
        if (!h.hasF1(peer)) return "needs a newer Muninn"
        if (!h.trusted(peer)) return "not trusted"
        if (id in stopped) { file.delete(); return null }   // Cancel while it was being made ready
        val size = file.length()
        again[id] = Triple(peer, file, name)
        put(Progress(id, peer, name, size, true, "connecting"))
        work.execute {
            try {
                val sha = sha256(file)
                if (id in stopped) return@execute
                val sock = DatagramSocket()
                val cands = candidates(sock)
                if (cands.isEmpty()) { sock.close(); fallBack(id, peer, file, name, "No internet connection that can go direct. Try on the same Wi-Fi."); return@execute }
                val keys = ChatCrypto.newKeyPair()
                outs[id] = Out(peer, file, name, size, sha, keys, sock, cands)
                h.signal(peer, JSONObject().put("t", "offer").put("id", id).put("name", name).put("size", size).put("sha", sha)
                    .put("e", ChatCrypto.b64(keys.public.encoded)).put("c", candsJson(cands)).toString())
                put(Progress(id, peer, name, size, true, "offered"))
                // Nothing back in time: a small file goes over the internet route (it arrives when they're back); a
                // bigger one waits as long as the other phone may ask first.
                val until = System.currentTimeMillis() + answerMs(size)
                while (System.currentTimeMillis() < until && _all.value[id]?.state == "offered" && id !in stopped) Thread.sleep(250)
                if (id in stopped) { outs.remove(id)?.sock?.close(); return@execute }
                if (_all.value[id]?.state == "offered") {
                    outs.remove(id)?.sock?.close()
                    fallBack(id, peer, file, name, "They're offline. Try again when they're back.")
                }
            } catch (e: Exception) { if (id !in stopped) fail(id, "That file couldn't be sent.") }
        }
        return null
    }

    /** A `file` message from [from] (already opened and checked: ok()). */
    fun onSignal(from: String, p: JSONObject) {
        val h = host ?: return
        val id = p.getString("id")
        when (p.getString("t")) {
            "offer" -> {
                if (!h.trusted(from)) { h.signal(from, JSONObject().put("t", "decline").put("id", id).toString()); return }
                val inc = In(from, p.getString("name"), p.getLong("size"), p.getString("sha"), p.getString("e"), CallLogic.directOf(p.getJSONArray("c")))
                ins[id] = inc
                val waits = h.waitsForAsk(from)
                when {
                    arrivesByItself(inc.size, h.mayDownload(), waits) -> accept(id)
                    asksFirst(inc.size, waits) -> {
                        put(Progress(id, from, inc.name, inc.size, false, "asking", note = "Wants to send you a big file. Accept or decline"))
                        h.notice("A big file is waiting for you to accept: ${inc.name}")
                    }
                    else -> put(Progress(id, from, inc.name, inc.size, false, "waiting", note = "On mobile data: tap to download"))
                }
            }
            "accept" -> {
                val o = outs[id] ?: return
                if (o.peer != from) return
                val theirs = try { ChatCrypto.publicFrom(p.getString("e")) } catch (e: Exception) { return }
                val key = key(ChatCrypto.sharedKey(o.keys.private, o.keys.public, theirs), id)
                work.execute { runSender(id, o, key, CallLogic.directOf(p.getJSONArray("c"))) }
            }
            "decline" -> outs[id]?.takeIf { it.peer == from }?.let {
                outs.remove(id)
                if (_all.value[id]?.state == "offered") {
                    it.sock.close()
                    fallBack(id, it.peer, it.file, it.name, "They declined, or Direct calls is off on their side.")
                } else {
                    // Stopped on their side while it was on its way.
                    _all.value[id]?.let { p -> put(p.copy(state = "failed", note = "They stopped it")) }
                    stopped.add(id)
                    it.sock.close()
                }
            }
        }
    }

    /** No to a waiting file: the sender hears so, and it's gone from the chat. */
    fun decline(id: String) {
        val inc = ins.remove(id) ?: return
        host?.signal(inc.peer, JSONObject().put("t", "decline").put("id", id).toString())
        _all.value = _all.value - id
    }

    /** Download a waiting file now (tapped, or on Wi-Fi). */
    fun accept(id: String) {
        val inc = ins[id] ?: return
        val h = host ?: return
        put(Progress(id, inc.peer, inc.name, inc.size, false, "connecting"))
        work.execute {
            try {
                val sock = DatagramSocket()
                val cands = candidates(sock)
                val keys = ChatCrypto.newKeyPair()
                val key = key(ChatCrypto.sharedKey(keys.private, keys.public, ChatCrypto.publicFrom(inc.theirKey)), id)
                h.signal(inc.peer, JSONObject().put("t", "accept").put("id", id).put("e", ChatCrypto.b64(keys.public.encoded)).put("c", candsJson(cands)).toString())
                runReceiver(id, inc, key, sock)
            } catch (e: Exception) { fail(id, "That file didn't arrive. Ask them to send it again.") }
        }
    }

    private fun fail(id: String, why: String) {
        if (id in stopped) return
        _all.value[id]?.let { put(it.copy(state = "failed", note = why)) }
        host?.notice(why)
    }

    // ------------------------------------------------------------------ the two ends

    private fun runSender(id: String, o: Out, key: ByteArray, theirs: List<InetSocketAddress>) {
        val sock = o.sock
        try {
            put(Progress(id, o.peer, o.name, o.size, true, "connecting"))
            val peer = punch(sock, key, id, 0, theirs) ?: run {
                if (id !in stopped) fallBack(id, o.peer, o.file, o.name, "Couldn't connect straight to them. Try on the same Wi-Fi.")
                return
            }
            val n = pieces(o.size)
            val s = Sender(n)
            var seq = 1000L
            sock.soTimeout = 50
            val buf = ByteArray(2048)
            var lastHeard = System.currentTimeMillis()
            var sent = 0L
            RandomAccessFile(o.file, "r").use { raf ->
                val chunk = ByteArray(CHUNK)
                put(Progress(id, o.peer, o.name, o.size, true, "sending"))
                while (!s.done) {
                    if (id in stopped) return
                    val now = System.currentTimeMillis()
                    if (now - lastHeard > IDLE_MS) { fail(id, "The connection dropped. Try again."); return }
                    for (i in s.due(now)) {
                        raf.seek(i.toLong() * CHUNK)
                        val len = minOf(CHUNK.toLong(), o.size - i.toLong() * CHUNK).toInt()
                        raf.readFully(chunk, 0, len)
                        val payload = ByteBuffer.allocate(4 + len).putInt(i).put(chunk, 0, len).array()
                        val pk = seal(key, id, DATA, 0, seq++, payload)
                        sock.send(DatagramPacket(pk, pk.size, peer))
                    }
                    try {
                        val dp = DatagramPacket(buf, buf.size)
                        while (true) {
                            sock.receive(dp)
                            val (t, pl) = open(key, id, 1, dp.data, dp.length) ?: continue
                            lastHeard = System.currentTimeMillis()
                            if (t == ACK && pl.size >= 12) {
                                val bb = ByteBuffer.wrap(pl)
                                val below = bb.int
                                s.ack(below, bb.long)
                                sent = minOf(o.size, below.toLong() * CHUNK)
                                put(Progress(id, o.peer, o.name, o.size, true, "sending", sent))
                            }
                            if (t == DONE) { s.ack(n, 0); break }
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                }
            }
            put(Progress(id, o.peer, o.name, o.size, true, "done", o.size))
            again.remove(id)?.second?.delete()
        } catch (e: Exception) { fail(id, "That file couldn't be sent.") } finally { outs.remove(id); sock.close() }
    }

    private fun runReceiver(id: String, inc: In, key: ByteArray, sock: DatagramSocket) {
        val h = host ?: return
        val tmp = File(h.cacheDir(), "incoming-$id")
        try {
            val peer = punch(sock, key, id, 1, inc.theirCands) ?: run { fail(id, "Couldn't connect straight to them. It may come another way."); return }
            val n = pieces(inc.size)
            val r = Receiver(n)
            var seq = 1000L
            sock.soTimeout = 50
            val buf = ByteArray(2048)
            var lastHeard = System.currentTimeMillis()
            var lastAck = 0L
            var fresh = 0
            put(Progress(id, inc.peer, inc.name, inc.size, false, "sending"))
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(inc.size)
                while (!r.done) {
                    if (id in stopped) return
                    val now = System.currentTimeMillis()
                    if (now - lastHeard > IDLE_MS) { fail(id, "The connection dropped. Ask them to send it again."); return }
                    try {
                        val dp = DatagramPacket(buf, buf.size)
                        sock.receive(dp)
                        val (t, pl) = open(key, id, 0, dp.data, dp.length) ?: continue
                        lastHeard = System.currentTimeMillis()
                        if (t == DATA && pl.size > 4) {
                            val i = ByteBuffer.wrap(pl).int
                            if (i in 0 until n && pl.size - 4 == minOf(CHUNK.toLong(), inc.size - i.toLong() * CHUNK).toInt() && r.take(i)) {
                                raf.seek(i.toLong() * CHUNK); raf.write(pl, 4, pl.size - 4); fresh++
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                    val t = System.currentTimeMillis()
                    if (fresh >= 16 || t - lastAck >= 40) {
                        val (below, bits) = r.ack()
                        val pk = seal(key, id, ACK, 1, seq++, ByteBuffer.allocate(12).putInt(below).putLong(bits).array())
                        sock.send(DatagramPacket(pk, pk.size, peer))
                        lastAck = t; fresh = 0
                        put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", minOf(inc.size, below.toLong() * CHUNK)))
                    }
                }
            }
            repeat(3) { val pk = seal(key, id, DONE, 1, seq++, ByteArray(0)); sock.send(DatagramPacket(pk, pk.size, peer)) }
            put(Progress(id, inc.peer, inc.name, inc.size, false, "sending", inc.size, note = "Checking it"))
            if (sha256(tmp) != inc.sha) { fail(id, "${inc.name} arrived damaged, so it was thrown away."); return }
            val saved = h.save(inc.name, tmp) ?: run { fail(id, "${inc.name} couldn't be saved. Is the storage full?"); return }
            put(Progress(id, inc.peer, saved.name, inc.size, false, "done", inc.size, uri = saved.uri))
            h.notice("File from ${h.nameOf(inc.peer)}: ${saved.name} · Saved to Downloads")
        } catch (e: Exception) { fail(id, "That file didn't arrive. Ask them to send it again.") } finally { ins.remove(id); sock.close(); tmp.delete() }
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
