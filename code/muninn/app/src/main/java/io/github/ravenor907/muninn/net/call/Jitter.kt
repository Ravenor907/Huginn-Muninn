package io.github.ravenor907.muninn.net.call

import java.util.TreeMap

/** What to play for one 20 ms slot. */
sealed class Play {
    class Packet(val data: ByteArray) : Play()
    /** This slot was lost but the next packet is here: rebuild it from that packet's in-band FEC. */
    class Fec(val next: ByteArray) : Play()
    /** Lost with nothing to rebuild it from: Opus packet loss concealment. */
    object Conceal : Play()
    /** Nothing to play: before the first packet, or a long DTX pause. */
    object Silence : Play()
}

/**
 * Plays a call's audio in order at a steady pace (shared/docs/protocol.md 5.5), the same algorithm as Huginn's
 * JitterBuffer (src/call/jitterbuffer.cpp), so both apps sound the same. Packets come in with the sender's
 * ts (ms since its first packet, 20 ms per frame); [next] is called once per 20 ms by the player.
 *
 * The delay adapts between [minMs] and [maxMs] from how much the arrival times wander (the 95th percentile
 * over the last 3 s), starting at [startMs]. It grows at once when the network gets rougher (and by a frame
 * for every packet that came too late to play), but shrinks only one frame every 2 s, so one calm moment
 * doesn't pull it back under the next burst. Being too far behind drops a frame, too far ahead plays one
 * concealed frame, at most once every 100 ms, so it follows a sender whose clock runs a little fast or slow.
 * [put] runs on the network thread, [next] on the player.
 */
class Jitter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minMs: Int = 40,
    private val maxMs: Int = 200,
    private val startMs: Int = 60,
) {
    class Stats { var played = 0; var fec = 0; var concealed = 0; var late = 0; var skipped = 0; var stretched = 0; var duplicate = 0 }

    @Volatile var targetMs = startMs.toLong(); private set
    val stats = Stats()
    private val packets = TreeMap<Long, ByteArray>()            // slot (ts / 20) -> Opus packet
    private val transit = ArrayDeque<LongArray>()                // (arrival ms, arrival - ts)
    private var started = false
    private var playing = false
    private var play = 0L          // the next slot to play
    private var offset = 0L        // local ms minus sender ts at which a frame is due
    private var cooldown = 0       // pops until the next drop or stretch is allowed
    private var quiet = 0          // concealed frames in a row
    private var late = 0           // frames of extra delay owed to packets that came too late
    private var shrunk = 0L        // when the delay last went down

    val bufferedMs: Int @Synchronized get() = packets.size * FRAME_MS

    @Synchronized fun put(ts: Long, data: ByteArray) {
        val now = clock()
        val slot = (ts + FRAME_MS / 2) / FRAME_MS
        val tr = now - ts
        transit.addLast(longArrayOf(now, tr))
        while (transit.size > MAX_SAMPLES || (transit.isNotEmpty() && now - transit.first()[0] > WINDOW_MS)) transit.removeFirst()
        if (!started) { started = true; play = slot; offset = tr + startMs }
        if (playing && slot < play) {
            // Came after its turn: hold one more frame from now on (until calmer times shrink it).
            stats.late++
            late = minOf(late + 1, 4)
            shrunk = now
            return
        }
        if (packets.containsKey(slot)) { stats.duplicate++; return }
        packets[slot] = data
        while (packets.size > MAX_PACKETS) packets.pollFirstEntry()
    }

    private fun adapt(now: Long) {
        while (transit.isNotEmpty() && now - transit.first()[0] > WINDOW_MS) transit.removeFirst()
        if (transit.size < 10) return
        val t = LongArray(transit.size) { transit[it][1] }
        t.sort()
        val lo = t[0]
        val p95 = t[minOf(t.size - 1, ((t.size - 1) * 0.95 + 0.5).toInt())]
        val spread = p95 - lo
        val want = ((((spread + FRAME_MS - 1) / FRAME_MS) * FRAME_MS + FRAME_MS) + late * FRAME_MS).coerceIn(minMs.toLong(), maxMs.toLong())
        if (want > targetMs) { targetMs = want; shrunk = now }
        else if (want < targetMs && now - shrunk >= SHRINK_EVERY_MS) {
            targetMs -= FRAME_MS; shrunk = now
            if (late > 0) late--
        }
        offset = lo + targetMs
    }

    /** The next 20 ms to play. Call it once per 20 ms of playback. */
    @Synchronized fun next(): Play {
        if (!started) return Play.Silence
        val now = clock()
        adapt(now)
        if (cooldown > 0) cooldown--
        val desired = Math.floorDiv(now - offset, FRAME_MS.toLong())
        if (!playing) {
            if (desired < play) return Play.Silence
            playing = true
        }
        var diff = desired - play
        if (diff > RESYNC_SLOTS || diff < -RESYNC_SLOTS) {
            // After a long pause or a jump in either clock: start over at the right place.
            play = desired
            while (packets.isNotEmpty() && packets.firstKey() < play) packets.pollFirstEntry()
            diff = 0
        }
        if (diff >= 2 && cooldown == 0) {
            // Behind: leave one frame out.
            packets.remove(play)
            play++
            stats.skipped++
            cooldown = 5
        } else if (diff <= -2 && cooldown == 0) {
            // Ahead: play one made-up frame and stay on this slot.
            cooldown = 5
            stats.stretched++
            return if (quiet >= QUIET_AFTER) Play.Silence else Play.Conceal
        }
        // Packets older than the play position can't be used any more.
        while (packets.isNotEmpty() && packets.firstKey() < play) packets.pollFirstEntry()
        val slot = play++
        packets.remove(slot)?.let { stats.played++; quiet = 0; return Play.Packet(it) }
        packets[slot + 1]?.let { stats.fec++; quiet = 0; return Play.Fec(it) }
        if (quiet < QUIET_AFTER) { stats.concealed++; quiet++; return Play.Conceal }
        return Play.Silence
    }

    @Synchronized fun reset() {
        packets.clear(); transit.clear()
        started = false; playing = false; play = 0; offset = 0; cooldown = 0; quiet = 0; late = 0; shrunk = 0
        targetMs = startMs.toLong()
    }

    companion object {
        const val FRAME_MS = 20
        private const val WINDOW_MS = 3_000L
        private const val MAX_SAMPLES = 200
        private const val MAX_PACKETS = 100
        private const val RESYNC_SLOTS = 50
        private const val QUIET_AFTER = 5
        private const val SHRINK_EVERY_MS = 2_000L
    }
}
