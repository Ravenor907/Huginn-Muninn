package io.github.ravenor907.muninn.net

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Encrypted file sharing over Tor (cap f4, shared/docs/protocol.md section 30). Never through the relays.
 *
 * The sender opens a one-time onion service for one file ([publish]: ADD_ONION NEW:ED25519-V3 with Flags=DiscardPK, so
 * its key is never kept), on Tor started on demand ([waitReady], TorRunner.need), and tells the receiver its address
 * only inside the end-to-end encrypted `file` message [torMessage]. The receiver connects through its own Tor's SOCKS
 * port ([connect]). Over that stream go the same sealed packets as on the UDP path (FileLink.seal: AES-256-GCM under the
 * transfer's own key from the two one-off keys of the offer and accept), each with a 2-byte length in front ([frame]).
 * The receiver speaks first: an ack saying which pieces it already has, so a transfer that switched from Nearby or
 * Trusted direct, or a stream that broke, goes on from the last piece ([serve], [receive]). The service closes when the
 * file is done or after [IDLE_MS] with nothing moving.
 *
 * Plain JVM apart from [waitReady], [publish] and [TransferHold] (TorFilesTest runs the stream both ways on loopback).
 */
object TorFiles {
    /** The cap: Encrypted file sharing (Nearby, Trusted direct, Tor), asking first, `wait` and `tor` messages. */
    const val CAP = "f4"
    /** A one-time service with nothing moving this long is closed; so is a receiver's wait. */
    const val IDLE_MS = 10 * 60_000L
    /** Tor this long without a circuit: the file says so (Retry tries again). */
    const val READY_MS = 3 * 60_000L
    /** A Tor transfer bigger than this asks first (the sender's app). */
    const val WARN_BYTES = 1L shl 30
    /** Packets on a Tor stream count from here, so their nonces never meet the UDP path's (which start at 1). */
    const val SEQ_BASE = 1L shl 40
    /** Pieces in flight on a stream beyond the last ack (about 560 KB). */
    const val STREAM_WINDOW = 512
    /** Nothing heard on a stream this long: it's dropped (the receiver connects again). */
    const val STREAM_IDLE_MS = 120_000L
    /** The longest frame (a data packet is 9 + 4 + 1100 + 16 bytes). */
    const val MAX_FRAME = 2048
    /** About how fast Tor carries a file, for the card's estimate. */
    const val TOR_BYTES_PER_S = 1_000_000L

    /** What a card shows for each path (the `route` of FileLink.Progress, and Huginn's). */
    const val NEARBY = "Nearby"
    const val TRUSTED = "Trusted direct"
    const val TOR = "Tor, slower"

    /** A v3 onion service id: 56 base32 letters (no ".onion"), the last one "d" (version 3). */
    fun onionOk(h: String): Boolean = h.length == 56 && h.all { it in 'a'..'z' || it in '2'..'7' } && h.last() == 'd'

    /** The `tor` message (room `file`, sealed end to end like every `file` message): never anywhere else. */
    fun torMessage(id: String, host: String, port: Int): JSONObject =
        JSONObject().put("t", "tor").put("id", id).put("tor", host).put("p", port)

    /** A `tor` message is well-formed. */
    fun torOk(p: JSONObject): Boolean = (p.opt("tor") as? String)?.let { onionOk(it) } == true &&
        (p.opt("p") as? Number)?.let { it.toDouble() == it.toInt().toDouble() && it.toInt() in 1..65535 } == true

    /** "Tor, slower (about 20 min)" for a big one ([size] from 100 MB), else "Tor, slower". */
    fun torLabel(size: Long): String {
        if (size < 100L * 1024 * 1024) return TOR
        val min = (size / TOR_BYTES_PER_S + 59) / 60
        return if (min < 60) "$TOR (about $min min)" else "$TOR (about ${min / 60} h ${min % 60} min)".replace(" 0 min)", ")")
    }

    /** The 1 GB question before a file that will likely go over Tor. Null when it needn't ask. */
    fun warning(size: Long, likelyTor: Boolean): String? =
        if (!likelyTor || size <= WARN_BYTES) null
        else "It goes over Tor, which is slower. ${FileText.size(size)} may take about ${torLabel(size).substringAfter("about ").removeSuffix(")")}."

    // ------------------------------------------------------------------ Tor on demand (Android)

    /**
     * Starts Tor for [tag] if it isn't running and waits until it has a circuit: its SOCKS port, or 0 after [ms], when
     * Tor can't start, or when [stop] says so. [release] when done.
     */
    fun waitReady(tag: String, ms: Long = READY_MS, stop: () -> Boolean = { false }): Int {
        TorRunner.need(tag)
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until && !stop()) {
            val p = TorRunner.filesPort
            if (p > 0) return p
            if (TorRunner.failedNow) return 0
            Thread.sleep(500)
        }
        return 0
    }

    fun release(tag: String) = TorRunner.release(tag)

    /** One file's onion service: [host] (no ".onion") and [port], answered on [server]. [close] removes it from Tor. */
    class Service(val host: String, val port: Int, val server: ServerSocket, private val remove: () -> Unit) {
        @Volatile private var closed = false
        fun close() {
            if (closed) return
            closed = true
            try { remove() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}
        }
    }

    private val rnd = SecureRandom()

    /** A one-time onion service in front of a new socket on 127.0.0.1 (Tor must be ready). Null when Tor won't. */
    fun publish(): Service? {
        val server = try { ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) } catch (_: Exception) { return null }
        val virtual = 1024 + rnd.nextInt(64_000)
        val (host, remove) = TorRunner.addOnion(virtual, server.localPort) ?: run { try { server.close() } catch (_: Exception) {}; return null }
        return Service(host, virtual, server, remove)
    }

    // ------------------------------------------------------------------ SOCKS5 and frames (plain JVM)

    /** SOCKS5 CONNECT to [host].onion:[port] by name (Tor looks it up; nothing is looked up here). */
    fun socksRequest(host: String, port: Int): ByteArray {
        val name = "$host.onion".toByteArray(Charsets.US_ASCII)
        return byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), port.toByte())
    }

    /** Connects to [host].onion:[port] through Tor's SOCKS port on 127.0.0.1. Throws when it can't. */
    fun connect(socksPort: Int, host: String, port: Int, timeoutMs: Int = 120_000): Socket {
        require(onionOk(host))
        val s = Socket(java.net.Proxy.NO_PROXY)
        try {
            s.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), socksPort), 10_000)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            val o = s.getOutputStream()
            val i = DataInputStream(s.getInputStream())
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val hello = ByteArray(2); i.readFully(hello)
            if (hello[0].toInt() != 5 || hello[1].toInt() != 0) throw java.io.IOException("SOCKS refused")
            o.write(socksRequest(host, port)); o.flush()
            val head = ByteArray(4); i.readFully(head)
            if (head[0].toInt() != 5 || head[1].toInt() != 0) throw java.io.IOException("Tor couldn't reach it (${head[1]})")
            val skip = when (head[3].toInt()) { 1 -> 4; 4 -> 16; 3 -> i.readUnsignedByte(); else -> throw java.io.IOException("SOCKS") } + 2
            i.readFully(ByteArray(skip))
            s.soTimeout = 0
            return s
        } catch (e: Exception) { try { s.close() } catch (_: Exception) {}; throw e }
    }

    /** A frame: the packet's length (2 bytes, big endian), then the packet. */
    fun frame(packet: ByteArray): ByteArray {
        require(packet.size in 1..MAX_FRAME)
        return byteArrayOf((packet.size shr 8).toByte(), packet.size.toByte()) + packet
    }

    /** The next frame's packet, or null at the end of the stream or for a frame that can't be one. */
    fun readFrame(i: DataInputStream): ByteArray? = try {
        val n = i.readUnsignedShort()
        if (n !in 1..MAX_FRAME) null else ByteArray(n).also { i.readFully(it) }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ the two ends of a stream (plain JVM)

    /** What a stream asks of its transfer while it runs. */
    interface Ctl {
        fun stopped(): Boolean
        /** Paused here (27.5). */
        fun paused(): Boolean = false
        /** They paused, or go on. */
        fun held(on: Boolean) {}
        /** Bytes confirmed so far (the sender's view, from acks; the receiver's, from what's in). */
        fun progress(done: Long) {}
    }

    /** How a stream ended: the whole file, the stream broke (connect again), or it never proved to be theirs. */
    enum class End { DONE, BROKEN, STRANGER, STOPPED }

    /** Reads frames on its own thread, so the loops never lose half a frame to a timeout. */
    private class Reader(ins: InputStream) {
        val q = LinkedBlockingQueue<ByteArray>(4096)
        @Volatile var closed = false
        init {
            val di = DataInputStream(ins)
            Thread({
                while (true) { val f = readFrame(di) ?: break; q.put(f) }
                closed = true
            }, "muninn-tor-read").apply { isDaemon = true }.start()
        }
    }

    private fun send(o: OutputStream, key: ByteArray, id: String, type: Byte, dir: Int, seq: AtomicLong, payload: ByteArray) =
        o.write(frame(FileLink.seal(key, id, type, dir, seq.getAndIncrement(), payload)))

    /**
     * The sender's end of one stream: waits for the receiver's first ack (which proves it has the key and says what it
     * has), then sends every piece that isn't confirmed, in order, at most [STREAM_WINDOW] beyond the last ack. [s] and
     * [seq] live as long as the transfer (another stream, or the UDP path before it, goes on from them).
     */
    fun serve(ins: InputStream, out: OutputStream, key: ByteArray, id: String, size: Long, s: FileLink.Sender, raf: RandomAccessFile,
              seq: AtomicLong, ctl: Ctl, idleMs: Long = STREAM_IDLE_MS): End {
        val r = Reader(ins)
        val o = BufferedOutputStream(out, 64 * 1024)
        val n = s.pieces
        val chunk = ByteArray(FileLink.CHUNK)
        var proven = false
        var lastHeard = System.currentTimeMillis()
        var lastKeep = 0L
        var next = 0
        var lastBelow = -1
        var stuckSince = System.currentTimeMillis()
        var held = false
        while (true) {
            if (ctl.stopped()) return End.STOPPED
            // What came: acks, holds, done.
            var f = r.q.poll(if (proven && !held && !ctl.paused() && next < n) 0L else 50L, TimeUnit.MILLISECONDS)
            while (f != null) {
                val (t, pl) = FileLink.open(key, id, 1, f) ?: return if (proven) End.BROKEN else End.STRANGER
                proven = true
                lastHeard = System.currentTimeMillis()
                when (t) {
                    FileLink.ACK -> if (pl.size >= 12) {
                        val bb = ByteBuffer.wrap(pl)
                        val below = bb.int
                        s.ack(below, bb.long)
                        if (below > lastBelow) { lastBelow = below; stuckSince = lastHeard }
                        if (next < below) next = below
                        ctl.progress(minOf(size, s.below().toLong() * FileLink.CHUNK))
                        if (held) { held = false; ctl.held(false) }
                    }
                    FileLink.HOLD -> if (pl.isNotEmpty()) { held = pl[0].toInt() == 1; ctl.held(held) }
                    FileLink.DONE -> { s.ack(n, 0); ctl.progress(size); return End.DONE }
                }
                f = r.q.poll()
            }
            if (s.done) { ctl.progress(size); return End.DONE }
            val now = System.currentTimeMillis()
            if (!proven) { if (r.closed || now - lastHeard > 30_000L) return End.STRANGER; continue }
            if (r.closed || now - lastHeard > idleMs) return End.BROKEN
            try {
                when {
                    ctl.paused() -> if (now - lastKeep >= 1000) { send(o, key, id, FileLink.HOLD, 0, seq, byteArrayOf(1)); o.flush(); lastKeep = now }
                    held -> if (now - lastKeep >= 1000) { send(o, key, id, FileLink.PROBE, 0, seq, byteArrayOf(1)); o.flush(); lastKeep = now }
                    else -> {
                        // Everything went once and some still isn't confirmed after 10 s: from the first gap again.
                        if (next >= n && now - stuckSince > 10_000L) { next = s.below(); stuckSince = now }
                        var sent = 0
                        while (next < n && next - s.below() < STREAM_WINDOW && sent < 64) {
                            val i = next++
                            if (s.has(i)) continue
                            raf.seek(i.toLong() * FileLink.CHUNK)
                            val len = minOf(FileLink.CHUNK.toLong(), size - i.toLong() * FileLink.CHUNK).toInt()
                            raf.readFully(chunk, 0, len)
                            send(o, key, id, FileLink.DATA, 0, seq, ByteBuffer.allocate(4 + len).putInt(i).put(chunk, 0, len).array())
                            sent++
                        }
                        if (sent > 0) { o.flush(); lastKeep = now }
                        else if (now - lastKeep >= 5000) { send(o, key, id, FileLink.PROBE, 0, seq, byteArrayOf(1)); o.flush(); lastKeep = now }
                    }
                }
            } catch (_: java.io.IOException) { return End.BROKEN }
        }
    }

    /**
     * The receiver's end of one stream: an ack first (what it already has), then the pieces as they come, an ack every
     * 64 new ones or second, and done once all are in. [r] lives as long as the transfer.
     */
    fun receive(ins: InputStream, out: OutputStream, key: ByteArray, id: String, size: Long, r: FileLink.Receiver, raf: RandomAccessFile,
                seq: AtomicLong, ctl: Ctl, idleMs: Long = STREAM_IDLE_MS): End {
        val rd = Reader(ins)
        val o = BufferedOutputStream(out, 16 * 1024)
        val n = r.pieces
        fun ack() {
            val (below, bits) = r.ack()
            send(o, key, id, FileLink.ACK, 1, seq, ByteBuffer.allocate(12).putInt(below).putLong(bits).array())
            o.flush()
            ctl.progress(minOf(size, below.toLong() * FileLink.CHUNK))
        }
        try {
            ack()
            var lastHeard = System.currentTimeMillis()
            var lastAck = lastHeard
            var fresh = 0
            var wasPaused = false
            while (!r.done) {
                if (ctl.stopped()) return End.STOPPED
                val f = rd.q.poll(50, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (f != null) {
                    val (t, pl) = FileLink.open(key, id, 0, f) ?: return End.BROKEN
                    lastHeard = now
                    when (t) {
                        FileLink.HOLD -> if (pl.isNotEmpty()) ctl.held(pl[0].toInt() == 1)
                        FileLink.DATA -> if (pl.size > 4) {
                            val i = ByteBuffer.wrap(pl).int
                            if (i in 0 until n && pl.size - 4 == minOf(FileLink.CHUNK.toLong(), size - i.toLong() * FileLink.CHUNK).toInt() && r.take(i)) {
                                raf.seek(i.toLong() * FileLink.CHUNK); raf.write(pl, 4, pl.size - 4); fresh++
                            }
                        }
                    }
                } else if (rd.closed || now - lastHeard > idleMs) return End.BROKEN
                val paused = ctl.paused()
                if (paused) {
                    if (!wasPaused || now - lastAck >= 1000) { send(o, key, id, FileLink.HOLD, 1, seq, byteArrayOf(1)); o.flush(); lastAck = now }
                } else {
                    if (wasPaused) { send(o, key, id, FileLink.HOLD, 1, seq, byteArrayOf(0)); o.flush() }
                    if (fresh >= 64 || now - lastAck >= 1000) { ack(); lastAck = now; fresh = 0 }
                }
                wasPaused = paused
            }
            ack()
            send(o, key, id, FileLink.DONE, 1, seq, ByteArray(0)); o.flush()
            return End.DONE
        } catch (_: java.io.IOException) { return End.BROKEN }
    }
}

/**
 * Keeps Muninn going while a file moves (screen off, in the background): the foreground "Sending file" notification
 * (NearbyService), held while any transfer needs it, counted by tag, so Nearby (LocalSend) and FileLink share it.
 */
object TransferHold {
    private val tags: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Synchronized fun acquire(context: Context, tag: String, text: String) {
        val first = tags.isEmpty()
        if (!tags.add(tag)) return
        if (first) NearbyService.hold(context, true)
        NearbyService.note(context, text, -1)
    }

    @Synchronized fun release(context: Context, tag: String) {
        if (!tags.remove(tag)) return
        if (tags.isEmpty()) NearbyService.hold(context, false)
    }

    /** How far it is, on the notification too ("Sending file · 42%"), with the phone kept awake while it moves. */
    fun note(context: Context, text: String, percent: Int) {
        if (tags.isEmpty()) return
        NearbyService.progress()
        NearbyService.note(context, text, percent)
    }
}
