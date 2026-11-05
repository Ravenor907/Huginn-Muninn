package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.net.NetLogic
import io.github.ravenor907.muninn.net.PowerLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetLogicTest {
    private val hour = 3_600_000L
    private val t0 = 1_761_500_000_000L

    @Test
    fun retriesQuicklyUntilRelaysHoldIt() {
        assertEquals(t0 + 5_000, NetLogic.next(1, t0, 0))
        assertEquals(t0 + 10_000, NetLogic.next(2, t0, 0))
        assertEquals(t0 + 20_000, NetLogic.next(3, t0, 0))
        assertEquals(t0 + 60_000, NetLogic.next(4, t0, 0))
        assertEquals(t0 + 300_000, NetLogic.next(5, t0, 0))
        assertEquals(t0 + 300_000, NetLogic.next(50, t0, 0))   // every 5 minutes after that
        assertEquals(t0 + 5_000, NetLogic.next(0, t0, 0))      // never out of range
    }

    @Test
    fun onceHeldOnlyAtOneHourAndSixHours() {
        val held = t0
        assertEquals(held + hour, NetLogic.next(3, held, held))
        assertEquals(held + hour, NetLogic.next(3, held + 30 * 60_000, held))
        assertEquals(held + 6 * hour, NetLogic.next(4, held + hour, held))
        assertEquals(held + 6 * hour, NetLogic.next(4, held + 2 * hour, held))
        assertEquals(NetLogic.NEVER, NetLogic.next(5, held + 6 * hour, held))
    }

    @Test
    fun publishesOnlyWhenDue() {
        // Not held yet: every try goes to the relays.
        assertTrue(NetLogic.publish(0, t0, t0 + 5_000))
        val held = t0
        // Close by again 10 minutes later: sent close by, not to the relays.
        assertFalse(NetLogic.publish(held, held, held + 10 * 60_000))
        // The 1 hour try goes to the relays, once.
        assertTrue(NetLogic.publish(held, held, held + hour))
        assertFalse(NetLogic.publish(held, held + hour, held + hour + 60_000))
        // Then the 6 hour one, once.
        assertTrue(NetLogic.publish(held, held + hour, held + 6 * hour + 5))
        assertFalse(NetLogic.publish(held, held + 6 * hour + 5, held + 7 * hour))
        // A phone that slept past both still sends just the one try.
        assertTrue(NetLogic.publish(held, held, held + 8 * hour))
    }

    @Test
    fun oneReceiptPerMessageEveryFewMinutes() {
        assertTrue(NetLogic.receiptDue(null, t0))
        assertFalse(NetLogic.receiptDue(t0, t0))                       // another piece of the same photo
        assertFalse(NetLogic.receiptDue(t0, t0 + 60_000))              // a resend a minute later
        assertTrue(NetLogic.receiptDue(t0, t0 + NetLogic.RECEIPT_GAP_MS))
        assertTrue(NetLogic.receiptDue(t0, t0 - 1))                    // the clock went back: don't stay silent
    }

    @Test
    fun reconnectsBackOff() {
        assertEquals(2_000, NetLogic.retryMs(1))
        assertEquals(4_000, NetLogic.retryMs(2))
        assertEquals(32_000, NetLogic.retryMs(5))
        assertEquals(60_000, NetLogic.retryMs(6))
        assertEquals(60_000, NetLogic.retryMs(40))
        // The first swap to another relay is at once; failures in a row everywhere slow it down.
        assertEquals(0, NetLogic.swapMs(0))
        assertEquals(0, NetLogic.swapMs(1))
        assertEquals(2_000, NetLogic.swapMs(2))
        assertEquals(4_000, NetLogic.swapMs(3))
        assertEquals(60_000, NetLogic.swapMs(100))
        // No hot loop: with many failures everywhere even a fresh relay waits.
        assertEquals(60_000, NetLogic.reconnectMs(1, 20))
        assertEquals(2_000, NetLogic.reconnectMs(1, 1))
    }

    @Test
    fun refusedHoldsOnlyThatRelaysCursor() {
        val refused = setOf("wss://a")
        assertFalse(NetLogic.cursorMoves("wss://a", true, refused, false))   // it held something back
        assertTrue(NetLogic.cursorMoves("wss://b", true, refused, false))    // the others move on
        assertFalse(NetLogic.cursorMoves("wss://b", false, refused, false))  // inbox request not finished
        assertFalse(NetLogic.cursorMoves("wss://b", true, refused, true))    // older pages still coming
    }

    @Test
    fun asksRelaysOnlyForWhatsNew() {
        val now = 1_761_500_000L
        val most = (Limits.LONGEST + 1) * 86_400L
        assertEquals(31 * 86_400L, NetLogic.SINCE_MAX_S)
        assertEquals(now - most, NetLogic.since(0, now))                         // never finished: the longest limit back
        assertEquals(now - 600 - 2_400, NetLogic.since(now - 600, now))         // 40 minutes before the last finish
        // Offline for more than two days: still asked from 40 minutes before the last finish.
        assertEquals(now - 5 * 86_400 - 2_400, NetLogic.since(now - 5 * 86_400, now))
        assertEquals(now - most, NetLogic.since(now - 40 * 86_400, now))        // never more than the longest limit
        assertTrue(NetLogic.since(now, now) <= now - 600)                       // always past the 10-minute blur
        assertEquals(0, NetLogic.since(0, 100))
        // Round 14: a sender 11 minutes slow, with the full 10-minute blur, is still asked for.
        val sent = now - 11 * 60 - 599
        assertTrue(sent >= NetLogic.since(now, now))
        // The cursor is kept in the relay's time: a receiver 11 minutes fast doesn't move it ahead.
        assertEquals(now, NetLogic.cursor(now + 660, -660))
        assertEquals(now, NetLogic.cursor(now, null))
        assertEquals(now, NetLogic.cursor(now, 3 * 86_400L))                    // a nonsense Date is ignored
    }

    @Test
    fun keepaliveSkipsSocketsHeardJustNow() {
        assertTrue(NetLogic.aliveRecently(0))
        assertTrue(NetLogic.aliveRecently(59_999))
        assertFalse(NetLogic.aliveRecently(60_000))
        assertFalse(NetLogic.aliveRecently(-1))
    }

    @Test
    fun batteryOnlyMattersAtItsSteps() {
        assertEquals(0, NetLogic.batteryStep(100, false))
        assertEquals(0, NetLogic.batteryStep(30, false))
        assertEquals(1, NetLogic.batteryStep(29, false))
        assertEquals(1, NetLogic.batteryStep(15, false))
        assertEquals(2, NetLogic.batteryStep(14, false))
        assertEquals(-1, NetLogic.batteryStep(5, true))
        assertEquals(NetLogic.batteryStep(80, false), NetLogic.batteryStep(79, false))   // 80 to 79: nothing to do
    }

    @Test
    fun groupMessagesDontOpenTheConversationWindow() {
        assertTrue(NetLogic.opensTalk("abc123"))
        assertFalse(NetLogic.opensTalk("g:family"))
    }

    @Test
    fun prefersRelaysThatPingLess() {
        assertEquals(0, NetLogic.perHour(5, 0))
        assertEquals(120, NetLogic.perHour(60, 30 * 60_000))
        val relays = listOf("a", "b", "c")
        val health = mapOf(
            "a" to PowerLogic.Health(live = true, pingsPerHour = 120),
            "b" to PowerLogic.Health(live = true, pingsPerHour = 2),
            "c" to PowerLogic.Health(live = true, pingsPerHour = 60),
        )
        assertEquals(listOf("b"), PowerLogic.pick(relays, health, 1))
        // Fewer failures and staying open still come first.
        assertEquals(listOf("a"), PowerLogic.pick(relays, health + ("b" to PowerLogic.Health(failures = 1, pingsPerHour = 0)) +
            ("c" to PowerLogic.Health(live = false)), 1))
    }

    @Test
    fun lockedStoreStaysBounded() {
        assertTrue(NetLogic.mayHold(0, 0, 1_000, 500, 4_000_000))
        assertTrue(NetLogic.mayHold(499, 3_999_000, 1_000, 500, 4_000_000))
        // Full by count or by size: it stays on the relays instead.
        assertFalse(NetLogic.mayHold(500, 0, 10, 500, 4_000_000))
        assertFalse(NetLogic.mayHold(10, 3_999_500, 1_000, 500, 4_000_000))
        assertFalse(NetLogic.mayHold(0, 0, 5_000_000, 500, 4_000_000))
    }
}
