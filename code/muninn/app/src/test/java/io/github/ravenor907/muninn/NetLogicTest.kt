package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.net.NetLogic
import io.github.ravenor907.muninn.net.PowerLogic
import io.github.ravenor907.muninn.net.RelaySpread
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

    // ---- 1.47 message RCA: phone to phone on cellular, the receiver kept relays the message wasn't on

    @Test
    fun aPhoneListeningOnSomeRelaysReadsTheOthersToo() {
        // Background: one relay kept open of five. Catching up and the inbox check read all five.
        assertTrue(NetLogic.sweepAll(keptOpen = 1, live = 1, total = 5))
        // Before 1.47, open on screen kept three: the other two were never read while the socket answered.
        assertTrue(NetLogic.sweepAll(keptOpen = 3, live = 3, total = 5))
        // Every relay kept but one is down: read it when it's back (the sweep opens it).
        assertTrue(NetLogic.sweepAll(keptOpen = 5, live = 4, total = 5))
        // Every relay open and live: nothing more to read.
        assertFalse(NetLogic.sweepAll(keptOpen = 5, live = 5, total = 5))
        assertFalse(NetLogic.sweepAll(keptOpen = 0, live = 0, total = 0))
    }

    @Test
    fun theOpenPlanKeepsEveryRelay() {
        val open = PowerLogic.plan(PowerLogic.Inputs(foreground = true, screenOn = true))
        assertEquals(null, open.relays)
        assertFalse(NetLogic.sweepAll(keptOpen = 5, live = 5, total = 5))
    }

    @Test
    fun aClosedInboxRequestIsAskedAgain() {
        // Closed by the relay (or never finished) on a socket open long enough: asked again.
        assertTrue(NetLogic.reask(subscribed = false, openAgoMs = NetLogic.REASK_AFTER_MS, askedAgoMs = null))
        // Open: never.
        assertFalse(NetLogic.reask(subscribed = true, openAgoMs = 3_600_000L, askedAgoMs = null))
        // Just opened: its first answer may still be coming.
        assertFalse(NetLogic.reask(subscribed = false, openAgoMs = 5_000L, askedAgoMs = null))
        // Asked again a minute ago: not on every check.
        assertFalse(NetLogic.reask(subscribed = false, openAgoMs = 600_000L, askedAgoMs = 60_000L))
        assertTrue(NetLogic.reask(subscribed = false, openAgoMs = 600_000L, askedAgoMs = NetLogic.REASK_EVERY_MS))
        // A clock that went back (negative) doesn't block it.
        assertTrue(NetLogic.reask(subscribed = false, openAgoMs = 600_000L, askedAgoMs = -5L))
    }

    @Test
    fun onlyASignInCloseIsRetriedAtOnce() {
        assertTrue(NetLogic.authClosed("auth-required: we only serve gift wraps to their recipient"))
        assertFalse(NetLogic.authClosed("rate-limited: slow down"))
        assertFalse(NetLogic.authClosed(""))
    }

    // ---- the sender keeps sending the same event to every relay until each has stored it

    private val five = listOf("wss://a", "wss://b", "wss://c", "wss://d", "wss://e")

    @Test
    fun twoStoringIsNotEnoughEveryRelayStillOwesIt() {
        val s = RelaySpread()
        s.add("ev1", five)
        assertFalse(s.stored("ev1", "wss://a"))
        assertFalse(s.stored("ev1", "wss://b"))
        // Held by two (the old stopping point): the other three still get it again.
        assertEquals(setOf("wss://c", "wss://d", "wss://e"), s.missing("ev1"))
        assertFalse(s.stored("ev1", "wss://a"))   // an OK again from the same relay changes nothing
        assertFalse(s.stored("ev1", "wss://c")); assertFalse(s.stored("ev1", "wss://d"))
        assertTrue(s.stored("ev1", "wss://e"))
        assertTrue(s.missing("ev1").isEmpty())
        assertFalse(s.tracked("ev1"))
    }

    @Test
    fun refusalsDuplicatesAndRateLimits() {
        val s = RelaySpread()
        s.add("ev", listOf("wss://a", "wss://b", "wss://c", "wss://d"))
        // Rate limited: still owed, tried again later.
        assertFalse(s.refused("ev", "wss://a", "rate-limited: slow down"))
        assertTrue("wss://a" in s.missing("ev"))
        // Unknown or empty reasons may pass later too.
        assertFalse(s.refused("ev", "wss://a", ""))
        assertTrue("wss://a" in s.missing("ev"))
        // A duplicate is stored already.
        assertFalse(s.refused("ev", "wss://b", "duplicate: already have this event"))
        // Refused for good: dropped, never sent again.
        assertFalse(s.refused("ev", "wss://c", "blocked: kind 1059 not accepted"))
        assertFalse(s.refused("ev", "wss://a", "restricted: not allowed"))
        assertEquals(setOf("wss://d"), s.missing("ev"))
        assertTrue(s.refused("ev", "wss://d", "pow: difficulty 20 needed"))
        assertTrue(RelaySpread.refusedForGood("invalid: bad signature"))
        assertFalse(RelaySpread.refusedForGood("auth-required: sign in"))
    }

    @Test
    fun theRetriesEndAndOldEventsAreLetGo() {
        assertEquals(10_000L, RelaySpread.retryMs(0))
        assertEquals(900_000L, RelaySpread.retryMs(RelaySpread.RETRY_MS.size - 1))
        assertEquals(null, RelaySpread.retryMs(RelaySpread.RETRY_MS.size))
        // Every try fits inside the first hour, before the old held resend (NetLogic.HELD_AGAIN_MS).
        assertTrue(RelaySpread.RETRY_MS.sum() < NetLogic.HELD_AGAIN_MS[0])
        val s = RelaySpread(max = 2)
        s.add("1", five); s.add("2", five); s.add("3", five)
        assertFalse(s.tracked("1")); assertTrue(s.tracked("3")); assertEquals(2, s.size())
        // Untracked (or given up): nothing more to send, an OK for it is harmless.
        assertTrue(s.stored("1", "wss://a"))
        s.forget("3"); assertTrue(s.missing("3").isEmpty())
        s.add("", five); s.add("x", emptyList()); assertEquals(1, s.size())
    }

    @Test
    fun aDeadRelayRestsAndOneMessageNeverCostsItMoreConnections() {
        val s = RelaySpread()
        val t = 1_000_000L
        repeat(RelaySpread.DOWN_AFTER - 1) { s.failed("wss://e", t) }
        assertFalse(s.down("wss://e", t))
        s.failed("wss://e", t)
        assertTrue(s.down("wss://e", t))
        assertEquals(five - "wss://e", s.targets(five, t))
        s.add("ev", five)
        assertEquals((five - "wss://e").toSet(), s.due("ev", t))
        // It rests for a while, then it's tried again; an answer ends the rest at once.
        assertFalse(s.down("wss://e", t + RelaySpread.DOWN_MS))
        s.failed("wss://a", t); s.failed("wss://a", t); s.failed("wss://a", t)
        assertTrue(s.down("wss://a", t)); s.reached("wss://a"); assertFalse(s.down("wss://a", t))
        // Every relay resting: they're all tried, one may be back.
        five.forEach { u -> repeat(RelaySpread.DOWN_AFTER) { s.failed(u, t) } }
        assertEquals(five, s.targets(five, t))
        // The network came back: every relay is tried again at once.
        s.wake()
        assertFalse(s.down("wss://b", t))
    }

    @Test
    fun whatNobodyWaitsOnGetsFewerTriesAndAStoredCopyLetsTheOutboxGo() {
        val s = RelaySpread()
        s.add("receipt", five, RelaySpread.LIGHT_ROUNDS)
        assertEquals(10_000L, s.wait("receipt", 0))
        assertEquals(null, s.wait("receipt", RelaySpread.LIGHT_ROUNDS))
        assertFalse(s.anyStored("receipt"))
        s.stored("receipt", "wss://a")
        assertTrue(s.anyStored("receipt"))
        // A refusal isn't storing it.
        s.add("x", five); s.refused("x", "wss://a", "restricted: no")
        assertFalse(s.anyStored("x"))
    }

    @Test
    fun theDelayedLineIsCalm() {
        assertFalse(NetLogic.delayed(null, 1_000L))   // nothing waits: never, whatever a relay said
        assertFalse(NetLogic.delayed(NetLogic.DELAY_AFTER_MS - 1, null))
        assertTrue(NetLogic.delayed(NetLogic.DELAY_AFTER_MS, null))
        assertTrue(NetLogic.delayed(1_000L, 1_000L))
        assertFalse(NetLogic.delayed(1_000L, NetLogic.LIMIT_SHOWS_MS))
    }
}
