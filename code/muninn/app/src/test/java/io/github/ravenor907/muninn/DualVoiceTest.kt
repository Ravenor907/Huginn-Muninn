package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.CallCrypto
import io.github.ravenor907.muninn.net.call.CallLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** 1.45: voice over two paths (the relay next to Wi-Fi or Direct) and the receiver keeping only the first copy. */
class DualVoiceTest {
    private val relay = InetSocketAddress(InetAddress.getByName("104.30.1.2"), 61000)
    private fun path(k: CallLogic.Kind, a: String, p: Int) = CallLogic.Path(k, InetSocketAddress(InetAddress.getByName(a), p))

    @Test
    fun onlyVoiceOnlyWithRelayNeverUltra() {
        assertTrue(CallLogic.dualVoice(audio = true, relayAllocated = true, ultra = false))
        assertFalse(CallLogic.dualVoice(audio = false, relayAllocated = true, ultra = false))   // video stays on one path
        assertFalse(CallLogic.dualVoice(audio = true, relayAllocated = false, ultra = false))   // no new allocation for it
        assertFalse(CallLogic.dualVoice(audio = true, relayAllocated = true, ultra = true))
    }

    @Test
    fun relayJoinsDirectEvenPastTheLimit() {
        val r = CallLogic.Path(CallLogic.Kind.RELAY, relay)
        val w1 = path(CallLogic.Kind.WIFI, "192.168.1.9", 4000)
        val w2 = path(CallLogic.Kind.WIFI, "192.168.1.10", 4000)
        val d = path(CallLogic.Kind.DIRECT, "203.0.113.7", 51820)
        val all = listOf(r, w1, w2, d)
        // Before: three UDP paths, the relay last, so it's left out.
        assertFalse(CallLogic.udpSends(all, listOf(relay)).any { it.kind == CallLogic.Kind.RELAY })
        val both = CallLogic.udpSends(all, listOf(relay), alsoRelay = true)
        assertEquals(4, both.size)
        assertEquals(1, both.count { it.kind == CallLogic.Kind.RELAY })
        // Voice isn't rested off the relay; without a relay path there's nothing to add.
        assertEquals(listOf(CallLogic.Kind.WIFI), CallLogic.udpSends(listOf(w1), listOf(relay), alsoRelay = true).map { it.kind })
    }

    @Test
    fun firstCopyPlaysSecondIsDropped() {
        val tag = CallCrypto.tag("dual-voice-test")
        val keys = CallCrypto.Keys(ByteArray(32) { (it * 7 + 1).toByte() })
        val rx = CallCrypto.Receiver(keys, true, tag)
        fun res(p: ByteArray) = try { rx.open(p); "ok" } catch (e: CallCrypto.Dropped) { e.why }
        val a = CallCrypto.seal(keys, true, tag, CallCrypto.AUDIO, 10, 200, byteArrayOf(1, 2, 3))
        val b = CallCrypto.seal(keys, true, tag, CallCrypto.AUDIO, 11, 220, byteArrayOf(4, 5, 6))
        // Packet 10: the direct copy first, then the relay's. Packet 11: the relay's first this time.
        assertEquals("ok", res(a)); assertEquals("duplicate", res(a.copyOf()))
        assertEquals("ok", res(b)); assertEquals("duplicate", res(b.copyOf()))
        val opened = rx.open(CallCrypto.seal(keys, true, tag, CallCrypto.AUDIO, 12, 240, byteArrayOf(7)))
        assertEquals(CallCrypto.AUDIO, opened.type)
    }

    // ---- the receiver on every path combination: the real replay window and jitter buffer, 2 minutes of voice

    private class Way(val base: Int, val spread: Int, val lossPct: Int, val goneFrom: Long = -1, val goneTo: Long = -1)

    /** How much of the voice was heard (played or rebuilt): [ways] carry every packet, in whichever order they land. */
    private fun heard(vararg ways: Way): Double {
        val r = java.util.Random(7)
        val keys = io.github.ravenor907.muninn.net.call.CallCrypto.Keys(ByteArray(32) { 1 })
        val tag = byteArrayOf(9, 9)
        val rx = io.github.ravenor907.muninn.net.call.CallCrypto.Receiver(keys, true, tag)
        var now = 1_000_000L
        val t0 = now
        val jb = io.github.ravenor907.muninn.net.call.Jitter({ now })
        class Arrival(val at: Long, val p: ByteArray)
        val q = java.util.PriorityQueue<Arrival>(compareBy { it.at })
        var seq = 0L
        var good = 0; var all = 0
        var t = now; var play = now
        val end = now + 120_000L
        while (t < end) {
            val p = io.github.ravenor907.muninn.net.call.CallCrypto.seal(keys, true, tag, io.github.ravenor907.muninn.net.call.CallCrypto.AUDIO, seq++, t - t0, ByteArray(60))
            for (w in ways) {
                if (r.nextInt(100) < w.lossPct || (t - t0) in w.goneFrom until w.goneTo) continue
                q.add(Arrival(t + w.base + r.nextInt(w.spread + 1), p))
            }
            t += 20
            while (play < t) {
                while (q.isNotEmpty() && q.peek().at <= play) {
                    val a = q.poll(); now = a.at
                    try { val o = rx.open(a.p); jb.put(o.ts, o.payload) } catch (_: io.github.ravenor907.muninn.net.call.CallCrypto.Dropped) {}
                }
                now = play
                when (jb.next()) {
                    is io.github.ravenor907.muninn.net.call.Play.Packet, is io.github.ravenor907.muninn.net.call.Play.Fec -> good++
                    else -> {}
                }
                all++; play += 20
            }
        }
        return 100.0 * good / all
    }

    @Test
    fun soundOnEveryPathCombinationInEitherOrder() {
        val relay = Way(80, 30, 1); val direct = Way(30, 15, 1)
        assertTrue("relay only", heard(relay) > 98)
        assertTrue("direct only", heard(direct) > 98)
        assertTrue("direct first", heard(direct, relay) > 99)
        assertTrue("relay first", heard(Way(120, 15, 1), Way(40, 10, 1)) > 99)
        assertTrue("either order", heard(Way(50, 40, 1), Way(50, 40, 1)) > 99)
        // A copy far behind the first never disturbs what's playing.
        assertTrue("far apart", heard(Way(30, 10, 0), Way(430, 50, 0)) > 99)
        // One path going quiet for 3 s: the other carries on.
        assertTrue("one goes quiet", heard(Way(30, 15, 0, 60_000, 63_000), Way(100, 30, 0)) > 98)
    }
}
