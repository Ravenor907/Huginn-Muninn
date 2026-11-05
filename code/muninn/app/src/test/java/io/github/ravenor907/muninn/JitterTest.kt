package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.Jitter
import io.github.ravenor907.muninn.net.call.Play
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The jitter buffer (shared/docs/protocol.md 5.5) on a simulated network: the same runs, random numbers and
 * expectations as Huginn's JitterBuffer::selfTest, so both apps' buffers behave alike.
 */
class JitterTest {
    private var rand = 12345L
    private fun rnd(n: Int): Int { rand = (rand * 1103515245L + 12345L) and 0xFFFFFFFFL; return ((rand ushr 16) % n).toInt() }

    class Run(val stats: Jitter.Stats, val maxTarget: Long, val minTarget: Long, val maxBuffered: Int)

    /** [jitter]: extra random delay up to this many ms; [lossPct] lost; [drift] receiver clock speed; frames [pauseFrom, pauseTo) are DTX silence. */
    private fun simulate(frames: Int, jitter: Int, lossPct: Int, drift: Double, pauseFrom: Int, pauseTo: Int): Run {
        var clock = 0.0
        val jb = Jitter({ clock.toLong() })
        val inflight = java.util.TreeMap<Long, MutableList<Pair<Long, ByteArray>>>()
        for (i in 0 until frames) {
            if (i in pauseFrom until pauseTo) continue
            if (rnd(100) < lossPct) continue
            val at = i * 20L + 30 + (if (jitter > 0) rnd(jitter + 1) else 0)
            inflight.getOrPut(at) { ArrayList() } += (i * 20L) to byteArrayOf((i and 0x7F).toByte())
        }
        val end = frames * 20L + 500
        var nextPop = 0L
        var maxT = 0L; var minT = 1000L; var maxB = 0
        for (t in 0 until end) {
            while (inflight.isNotEmpty() && inflight.firstKey() <= t) inflight.pollFirstEntry()!!.value.forEach { jb.put(it.first, it.second) }
            if (clock.toLong() >= nextPop) {
                nextPop += 20
                jb.next()
                maxT = maxOf(maxT, jb.targetMs); minT = minOf(minT, jb.targetMs); maxB = maxOf(maxB, jb.bufferedMs)
            }
            clock += drift
        }
        return Run(jb.stats, maxT, minT, maxB)
    }

    @Test
    fun steadyNetwork() {
        val r = simulate(500, 0, 0, 1.0, -1, -1)
        assertTrue(r.stats.played == 500 && r.stats.concealed <= 5 && r.stats.late == 0 && r.stats.skipped == 0 && r.minTarget == 40L)
    }

    @Test
    fun jitterGrowsTheDelay() {
        simulate(500, 0, 0, 1.0, -1, -1)
        val wobbly = simulate(1500, 60, 0, 1.0, -1, -1)
        assertTrue("max ${wobbly.maxTarget} late ${wobbly.stats.late}", wobbly.maxTarget in 60..200 && wobbly.stats.late < 1500 / 40)
        val wild = simulate(1500, 400, 0, 1.0, -1, -1)
        assertEquals(200L, wild.maxTarget)
    }

    @Test
    fun lossIsRebuilt() {
        simulate(500, 0, 0, 1.0, -1, -1); simulate(1500, 60, 0, 1.0, -1, -1); simulate(1500, 400, 0, 1.0, -1, -1)
        val lossy = simulate(1500, 10, 10, 1.0, -1, -1)
        assertTrue(lossy.stats.fec > 50 && lossy.stats.concealed > 0 && lossy.stats.played > 1200)
    }

    @Test
    fun clockDrift() {
        val fast = simulate(3000, 10, 0, 1.002, -1, -1)
        val slow = simulate(3000, 10, 0, 0.998, -1, -1)
        assertTrue(fast.stats.stretched > 0 && fast.maxBuffered <= 200)
        assertTrue(slow.stats.skipped > 0 && slow.maxBuffered <= 200)
    }

    @Test
    fun dtxPause() {
        val pause = simulate(1000, 10, 0, 1.0, 300, 600)
        assertTrue(pause.stats.played >= 680 && pause.stats.concealed < 20)
    }

    /** A phone on a busy radio: every 2 s packets bunch up for up to 120 ms. The delay holds between bursts. */
    @Test
    fun radioBursts() {
        var now = 0L
        val jb = Jitter({ now })
        val inflight = java.util.TreeMap<Long, MutableList<Long>>()
        for (i in 0 until 1500) {
            val sent = i * 20L
            val inBurst = (sent % 2000).toInt()
            val extra = if (inBurst < 120) 120L - inBurst else rnd(8).toLong()
            inflight.getOrPut(sent + 30 + extra) { ArrayList() } += sent
        }
        var maxT = 0L
        for (t in 0 until 1500 * 20L + 500) {
            now = t
            while (inflight.isNotEmpty() && inflight.firstKey() <= t) inflight.pollFirstEntry()!!.value.forEach { jb.put(it, byteArrayOf(1)) }
            if (t % 20 == 0L) { jb.next(); maxT = maxOf(maxT, jb.targetMs) }
        }
        assertTrue("late ${jb.stats.late}", jb.stats.late < 1500 / 50 && maxT <= 200)
    }

    /** In order, a lost packet rebuilt from the next one's FEC, and silence before anything arrives. */
    @Test
    fun orderAndFec() {
        var now = 0L
        val j = Jitter({ now })
        assertTrue(j.next() is Play.Silence)
        val out = ArrayList<Play>()
        for (t in 0..600 step 5) {
            now = t.toLong()
            if (t % 20 == 5 && t / 20 != 4) j.put((t / 20) * 20L, byteArrayOf((t / 20).toByte()))
            if (t % 20 == 10) out += j.next()
        }
        val played = out.dropWhile { it is Play.Silence }
        assertEquals(0, (played[0] as Play.Packet).data[0].toInt())
        assertEquals(3, (played[3] as Play.Packet).data[0].toInt())
        assertEquals(5, (played[4] as Play.Fec).next[0].toInt())     // 4 rebuilt from 5's FEC
        assertEquals(5, (played[5] as Play.Packet).data[0].toInt())
        assertTrue(j.targetMs in 40..200)
    }
}
