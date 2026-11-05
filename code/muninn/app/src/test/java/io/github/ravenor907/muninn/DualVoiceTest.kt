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
}
