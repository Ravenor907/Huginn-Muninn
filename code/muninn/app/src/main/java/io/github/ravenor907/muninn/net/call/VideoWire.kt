package io.github.ravenor907.muninn.net.call

import java.util.TreeMap

/**
 * Video and screen share between two c2 apps (shared/docs/protocol.md 5.8.5 to 5.8.7), the parts without sockets,
 * codecs or cameras, the same as Huginn's VideoWire (src/call/videowire.cpp) so unit tests cover them:
 *  - a VP8 frame cut into fragments, each the plaintext of one media packet of type 3, sealed with the call's
 *    per-direction keys like audio (so video is end-to-end encrypted too)
 *  - the receiver putting fragments back together, asking again for lost ones (NACK, type 6) and for a fresh
 *    keyframe (type 5) when a frame can't be had
 *  - the receiver report (type 4) both sides send every second
 *  - the sender's bitrate, which follows the reports
 */
object VideoWire {
    const val HEADER = 6
    const val MAX_FRAGMENT = 1200 - 28 - HEADER      // 1166 bytes of VP8 per packet
    const val MAX_FRAGMENTS = 255
    const val CAMERA = 0
    const val SCREEN = 1

    class Fragment(
        val stream: Int, val key: Boolean, val rotation: Int, val frame: Int, val index: Int, val count: Int, val data: ByteArray,
    )

    fun encode(f: Fragment): ByteArray {
        val b = ByteArray(HEADER + f.data.size)
        b[0] = f.stream.toByte()
        b[1] = ((if (f.key) 1 else 0) or (((f.rotation / 90) and 3) shl 1)).toByte()
        b[2] = (f.frame shr 8).toByte(); b[3] = f.frame.toByte()
        b[4] = f.index.toByte(); b[5] = f.count.toByte()
        System.arraycopy(f.data, 0, b, HEADER, f.data.size)
        return b
    }

    /** Null when malformed. */
    fun decode(p: ByteArray): Fragment? {
        if (p.size < HEADER + 1 || p.size > HEADER + MAX_FRAGMENT) return null
        val stream = p[0].toInt() and 0xFF
        val flags = p[1].toInt() and 0xFF
        val index = p[4].toInt() and 0xFF
        val count = p[5].toInt() and 0xFF
        if ((stream != CAMERA && stream != SCREEN) || (flags and 0xF8) != 0 || count < 1 || index >= count) return null
        return Fragment(stream, flags and 1 != 0, ((flags shr 1) and 3) * 90, ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF),
            index, count, p.copyOfRange(HEADER, p.size))
    }

    /** One frame as fragment payloads, in order. Empty when it would need more than 255. */
    fun split(stream: Int, frame: Int, key: Boolean, rotation: Int, vp8: ByteArray): List<ByteArray> {
        val n = (vp8.size + MAX_FRAGMENT - 1) / MAX_FRAGMENT
        if (vp8.isEmpty() || n > MAX_FRAGMENTS) return emptyList()
        // Even pieces, so the last packet isn't a sliver.
        val each = (vp8.size + n - 1) / n
        return (0 until n).map { i ->
            encode(Fragment(stream, key, rotation, frame and 0xFFFF, i, n, vp8.copyOfRange(i * each, minOf(vp8.size, (i + 1) * each))))
        }
    }

    // ------------------------------------------------------------------ report and nack

    /**
     * Receiver report, 8 bytes (type 4). [screenPaused]: flag bit 2, "not watching your screen share" (cap c3,
     * 5.8.7), sent only to a peer whose caps say c3.
     */
    data class Report(val lossPct: Int = 0, val camera: Boolean = false, val screen: Boolean = false, val jitterMs: Int = 0, val kbps: Int = 0, val rttMs: Int = 0,
                      val screenPaused: Boolean = false)

    private fun c(v: Int, hi: Int) = v.coerceIn(0, hi)

    fun encodeReport(r: Report): ByteArray = byteArrayOf(
        c(r.lossPct, 100).toByte(), ((if (r.camera) 1 else 0) or (if (r.screen) 2 else 0) or (if (r.screenPaused) 4 else 0)).toByte(),
        (c(r.jitterMs, 65535) shr 8).toByte(), c(r.jitterMs, 65535).toByte(),
        (c(r.kbps, 65535) shr 8).toByte(), c(r.kbps, 65535).toByte(),
        (c(r.rttMs, 65535) shr 8).toByte(), c(r.rttMs, 65535).toByte(),
    )

    fun decodeReport(p: ByteArray): Report? {
        if (p.size < 8 || (p[0].toInt() and 0xFF) > 100) return null
        fun u16(at: Int) = ((p[at].toInt() and 0xFF) shl 8) or (p[at + 1].toInt() and 0xFF)
        val f = p[1].toInt() and 0xFF
        return Report(p[0].toInt() and 0xFF, f and 1 != 0, f and 2 != 0, u16(2), u16(4), u16(6), f and 4 != 0)
    }

    /** Resend request (type 6): these fragments of one frame; no indexes means the whole frame. [first]: not on the wire. */
    class Nack(val stream: Int, val frame: Int, val indexes: List<Int>, val first: Boolean = false)

    fun encodeNack(n: Nack): ByteArray {
        val k = minOf(n.indexes.size, MAX_FRAGMENTS)
        val b = ByteArray(4 + k)
        b[0] = n.stream.toByte(); b[1] = (n.frame shr 8).toByte(); b[2] = n.frame.toByte(); b[3] = k.toByte()
        for (i in 0 until k) b[4 + i] = n.indexes[i].toByte()
        return b
    }

    fun decodeNack(p: ByteArray): Nack? {
        if (p.size < 4) return null
        val k = p[3].toInt() and 0xFF
        val s = p[0].toInt() and 0xFF
        if (p.size != 4 + k || (s != CAMERA && s != SCREEN)) return null
        return Nack(s, ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF), (0 until k).map { p[4 + it].toInt() and 0xFF })
    }

    // ------------------------------------------------------------------ assembler

    /**
     * Puts one stream's frames back together and hands them out in order, ready for the decoder. After a loss it
     * can't repair, it waits for the next keyframe (VP8 frames build on the ones before). Not thread safe: one thread.
     */
    class Assembler(val stream: Int = CAMERA) {
        class Frame(val data: ByteArray, val key: Boolean, val rotation: Int, val frame: Int)
        class Ask(val nacks: List<Nack>, val keyframe: Boolean)
        class Stats { var frames = 0; var keyframes = 0; var nacked = 0; var abandoned = 0; var keyRequests = 0 }

        private class Pending {
            var count = 0
            val parts = TreeMap<Int, ByteArray>()
            var key = false
            var rotation = 0
            var seen = 0L
            var last = 0L
            var nackAt = 0L
            var nacks = 0
            fun complete() = count > 0 && parts.size == count
        }

        val stats = Stats()
        private val frames = TreeMap<Long, Pending>()
        private var next = -1L
        private var high = -1L
        private var needKey = true
        private var keyAsked = -1_000_000L

        fun reset() { frames.clear(); next = -1; high = -1; needKey = true; keyAsked = -1_000_000L }

        private fun extend(f: Int): Long {
            if (high < 0) return f.toLong()
            val delta = ((f - (high and 0xFFFF).toInt()) and 0xFFFF).toShort().toLong()
            return high + delta
        }

        private fun dropBefore(ext: Long) { while (frames.isNotEmpty() && frames.firstKey() < ext) frames.pollFirstEntry() }

        fun add(f: Fragment, now: Long) {
            val ext = extend(f.frame)
            if (next >= 0 && ext < next) return   // already handed out or given up on
            if (high >= 0 && ext > high + 1000) { reset(); add(f, now); return }   // a jump: start over at the next keyframe
            val p = frames.getOrPut(ext) { Pending() }
            if (p.count == 0) {
                p.count = f.count; p.key = f.key; p.rotation = f.rotation
                if (p.seen == 0L) p.seen = now
            }
            if (f.count != p.count || p.parts.containsKey(f.index)) return   // a copy, or doesn't fit what came before
            p.parts[f.index] = f.data
            p.last = now
            if (ext > high) high = ext
            while (frames.size > MAX_PENDING) { frames.pollFirstEntry(); needKey = true }
        }

        fun next(): Frame? {
            val cur = if (next >= 0) frames[next] else null
            val e: Map.Entry<Long, Pending> = if (needKey || next < 0 || cur?.complete() != true) {
                // A complete keyframe ahead of a hole: jump to it.
                frames.entries.firstOrNull { it.value.key && it.value.complete() } ?: return null
            } else java.util.AbstractMap.SimpleEntry(next, cur)
            val ext = e.key; val p = e.value
            dropBefore(ext + 1)
            next = ext + 1
            needKey = false
            val out = java.io.ByteArrayOutputStream()
            for (part in p.parts.values) out.write(part)
            stats.frames++
            if (p.key) stats.keyframes++
            return Frame(out.toByteArray(), p.key, p.rotation, (ext and 0xFFFF).toInt())
        }

        /** What to ask the sender for now; call every 20 to 50 ms. */
        fun poll(now: Long, rttMs: Int): Ask {
            val rtt = rttMs.coerceIn(20, 1000)
            val nackGap = maxOf(40, rtt * 6 / 5).toLong()
            val giveUp = maxOf(250, 2 * rtt + 120).toLong()
            val nacks = ArrayList<Nack>()
            if (!needKey && next >= 0 && high - next >= MAX_PENDING) {
                // A gap too wide to fill (or to hold placeholders for): wait for a keyframe instead.
                stats.abandoned++
                needKey = true
                dropBefore(high)
            }
            if (!needKey && next >= 0) {
                var ext = next
                while (ext <= high) {
                    val p = frames.getOrPut(ext) { Pending().also { it.seen = now } }   // nothing came of it, later ones did
                    if (!p.complete()) {
                        val stalled = ext < high || now - p.last > 30
                        if (stalled) {
                            if (now - p.seen > giveUp) {
                                // Can't be had in time: everything after it needs a keyframe.
                                stats.abandoned++
                                needKey = true
                                break
                            }
                            if (p.nacks < 3 && now - p.nackAt >= nackGap) {
                                val missing = (0 until p.count).filter { !p.parts.containsKey(it) }
                                nacks += Nack(stream, (ext and 0xFFFF).toInt(), missing, p.nacks == 0)
                                p.nackAt = now; p.nacks++
                                stats.nacked++
                                if (nacks.size >= 8) break
                            }
                        }
                    }
                    ext++
                }
            }
            val keyWaiting = frames.values.any { it.key }
            var key = false
            if (needKey && !keyWaiting && now - keyAsked >= maxOf(300, rtt * 2)) {
                keyAsked = now; stats.keyRequests++; key = true
            }
            return Ask(nacks, key)
        }

        companion object { private const val MAX_PENDING = 120 }
    }

    // ------------------------------------------------------------------ rate

    /** The sender's video bitrate (kbps): starts at 60 % of the cap, backs off on loss, climbs slowly while clean. */
    class RateControl {
        var cap = 700; private set
        @Volatile var kbps = 420; private set
        private var raised = 0L
        private var lowered = 0L

        fun setCap(k: Int) {
            val first = raised == 0L && lowered == 0L && kbps == 420
            cap = maxOf(MIN, k)
            if (first) kbps = maxOf(MIN, cap * 6 / 10)
            kbps = minOf(kbps, cap)
        }

        fun onReport(loss: Int, now: Long) {
            when {
                loss >= 10 -> { kbps = maxOf(MIN, kbps * 3 / 4); lowered = now }
                loss >= 4 -> { kbps = maxOf(MIN, kbps * 92 / 100); lowered = now }
                loss < 2 && now - lowered >= 3_000 && now - raised >= 1_000 -> { kbps = minOf(cap, kbps * 108 / 100 + 10); raised = now }
            }
        }

        companion object { const val MIN = 120 }
    }

    /** 5.8.6: over the relay 480p (about 0.4 GB an hour with the sound), on a free path (Wi-Fi or direct) up to 720p. */
    data class Profile(val width: Int, val height: Int, val fps: Int, val capKbps: Int)

    fun cameraProfile(freePath: Boolean) = if (freePath) Profile(1280, 720, 30, 2000) else Profile(640, 480, 24, 700)
    fun screenProfile(freePath: Boolean) = if (freePath) Profile(1920, 1080, 10, 2500) else Profile(1280, 720, 8, 800)
    /** The camera on a low battery or Android's Battery Saver: 360p at 15 frames a second at most, so encoding and the radio cost less. */
    fun lowPower(p: Profile) = Profile(minOf(p.width, 480), minOf(p.height, 360), minOf(p.fps, 15), minOf(p.capKbps, 400))
}
