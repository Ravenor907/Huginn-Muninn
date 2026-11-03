package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ProfileWire
import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.TurnCreds
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.TimeAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** Round 18: the pure parts of the fixes (TURN handover permissions, the relay on demand, Leave retries, minors). */
class Round18Test {
    private fun addr(last: Int, port: Int) = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(104, 30, 1, last.toByte())), port)

    /** Stands in for a TURN allocation: only what it was told to let in. */
    private class Alloc { val permits = LinkedHashSet<InetSocketAddress>() }

    // ---- R1: both ends renewing at about the same time

    @Test fun handoverKeepsThePeersNewAddress() {
        val peer = CallLogic.RelayPeer()
        val old = Alloc()
        var live: Alloc? = old
        var renewing: Alloc? = null
        // The call runs: the peer's first relayed address is let in on the allocation in use.
        val b1 = addr(2, 61000)
        assertTrue(peer.advertise(b1))
        CallLogic.permitOn(live, renewing).forEach { it.permits += b1 }
        // This side renews: the new allocation lets in what is known now.
        val fresh = Alloc()
        peer.advertised?.let { fresh.permits += it }
        renewing = fresh
        // Five seconds later the peer renews too, and its new address comes during this side's handover.
        val b2 = addr(3, 61002)
        assertTrue(peer.advertise(b2))
        val targets = CallLogic.permitOn(live, renewing)
        assertEquals(listOf(old, fresh), targets)
        targets.forEach { it.permits += b2 }
        // The handover ends: the new allocation lets every address of the peer in before it carries media.
        CallLogic.handoverPermits(peer).forEach { fresh.permits += it }
        live = fresh; renewing = null
        assertTrue(b2 in live.permits)
        // Without a renewal underway only the allocation in use is asked, once.
        assertEquals(listOf(fresh), CallLogic.permitOn(live, renewing))
        assertEquals(listOf(fresh), CallLogic.permitOn(fresh, fresh))
        assertTrue(CallLogic.permitOn<Alloc>(null, null).isEmpty())
    }

    @Test fun handoverPermitsIncludeTheSeenAddress() {
        val peer = CallLogic.RelayPeer()
        val a = addr(2, 61000); val seen = addr(9, 61010)
        peer.advertise(a)
        assertTrue(peer.heard(seen))
        assertEquals(listOf(a, seen), CallLogic.handoverPermits(peer))
    }

    // ---- The relay on demand

    @Test fun relayOnlyWhenNothingFreeCanCarryTheCall() {
        assertTrue(CallLogic.relayAtStart(lanClose = false, bluetooth = false, directMaybe = false))
        assertFalse(CallLogic.relayAtStart(lanClose = true, bluetooth = false, directMaybe = false))
        assertFalse(CallLogic.relayAtStart(lanClose = false, bluetooth = true, directMaybe = false))
        assertFalse(CallLogic.relayAtStart(lanClose = false, bluetooth = false, directMaybe = true))
    }

    @Test fun relayAskedOnceNoFreePathIsFresh() {
        val began = 100_000L
        // Before the media: never.
        assertFalse(CallLogic.relayNeeded(0, 0, began + 60_000))
        // The free path gets 2.5 s to prove itself.
        assertFalse(CallLogic.relayNeeded(began, 0, began + CallLogic.RELAY_NEED_MS - 1))
        assertTrue(CallLogic.relayNeeded(began, 0, began + CallLogic.RELAY_NEED_MS))
        // A free path fresh a moment ago: wait again from then.
        val fresh = began + 30_000
        assertFalse(CallLogic.relayNeeded(began, fresh, fresh + 1_000))
        assertTrue(CallLogic.relayNeeded(began, fresh, fresh + CallLogic.RELAY_NEED_MS))
    }

    @Test fun renewedOnlyWhileTheRelayCarriesTheCall() {
        val now = 10_000_000L
        assertFalse(CallLogic.renewRelayNow(0, now))
        assertTrue(CallLogic.renewRelayNow(now - 1_000, now))
        assertTrue(CallLogic.renewRelayNow(now - CallLogic.RELAY_IN_USE_MS + 1, now))
        assertFalse(CallLogic.renewRelayNow(now - CallLogic.RELAY_IN_USE_MS, now))
    }

    @Test fun lateRenewalHandsOverBeforeTheOldLoginEnds() {
        val now = 1_000_000L
        assertEquals(TurnCreds.HANDOVER_MS, CallLogic.handoverMs(now + TurnCreds.RENEW_BEFORE_MS, now))
        assertEquals(25_000L, CallLogic.handoverMs(now + 40_000, now))
        assertEquals(5_000L, CallLogic.handoverMs(now + 10_000, now))
    }

    // ---- R2: Leave finished at the next start

    @Test fun leaveRetriesAreBounded() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (0..4).map { KidRules.leaveRetryGap(it) })
        assertEquals(10_000L, KidRules.leaveRetryGap(50))
        assertEquals(listOf("b"), KidRules.leaveStillLeft(listOf("a", "b", "c"), setOf("a", "c")))
        assertTrue(KidRules.leaveStillLeft(listOf("a"), setOf("a")).isEmpty())
        assertTrue(KidRules.LEAVE_SEND_MS < KidRules.LEAVE_READY_MS)
    }

    // ---- Minors

    @Test fun anOlderReadingNeverReplacesANewerOne() {
        val t0 = 1_800_000_000_000L
        var r = KidRules.addReading(emptyMap(), "parent:p", TimeAnchor(t0, 1_000))
        // A config signed an hour earlier arrives later (over the Wi-Fi): kept as it was.
        val older = KidRules.addReading(r, "parent:p", TimeAnchor(t0 - 3_600_000, 5_000))
        assertEquals(TimeAnchor(t0, 1_000), older["parent:p"])
        // A newer one does replace it.
        r = KidRules.addReading(r, "parent:p", TimeAnchor(t0 + 60_000, 61_000))
        assertEquals(TimeAnchor(t0 + 60_000, 61_000), r["parent:p"])
        // Once the newer one is too old to be used, any reading from the source is taken again.
        val late = KidRules.addReading(r, "parent:p", TimeAnchor(t0 - 1_000, 61_000 + KidRules.READING_MS + 1))
        assertEquals(t0 - 1_000, late["parent:p"]!!.wall)
    }

    @Test fun theirNameIsKeptUnderANickname() {
        // Shown (and said) only without a nickname; kept either way.
        assertNull(ProfileWire.renameTo("Ann", "Mom", "Eve"))
        assertEquals("Eve", ProfileWire.keepName("Ann", "Eve"))
        assertNull(ProfileWire.keepName("Ann", "Ann"))
        assertNull(ProfileWire.keepName("Ann", null))
    }
}
