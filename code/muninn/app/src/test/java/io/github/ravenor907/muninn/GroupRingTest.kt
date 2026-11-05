package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.GroupRingLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A group call made from the chat list rings once the group has reached the people picked. */
class GroupRingTest {
    @Test
    fun waitsForTheGroupToReachEveryoneThenRings() {
        val ringable = setOf("b", "c")
        assertFalse(GroupRingLogic.ready(ringable, waiting = setOf("c"), waitedMs = 1_000))
        assertTrue(GroupRingLogic.ready(ringable, waiting = emptySet(), waitedMs = 600))
        // A slow route: ring anyway once the wait is over (c is rung again when the group reaches it).
        assertTrue(GroupRingLogic.ready(ringable, waiting = setOf("c"), waitedMs = GroupRingLogic.WAIT_MS))
        // Nobody can be rung yet (only invited): keep waiting until the wait is over.
        assertFalse(GroupRingLogic.ready(emptySet(), waiting = emptySet(), waitedMs = 1_000))
        assertTrue(GroupRingLogic.WAIT_MS in 2_000L..15_000L)
    }

    @Test
    fun latecomersAreRungOnceAndTheRestAreNamed() {
        // b had it at the start; c arrives late; d accepted the invite and is a member now.
        assertEquals(emptySet<String>(), GroupRingLogic.late(setOf("b", "c"), waiting = setOf("c"), rung = setOf("b")))
        assertEquals(setOf("c", "d"), GroupRingLogic.late(setOf("b", "c", "d"), waiting = emptySet(), rung = setOf("b")))
        assertEquals(emptySet<String>(), GroupRingLogic.late(setOf("b", "c"), waiting = emptySet(), rung = setOf("b", "c")))
        // e was only invited (never a member): named as not rung, in the order picked.
        assertEquals(listOf("e", "c"), GroupRingLogic.notRung(listOf("b", "e", "c"), setOf("b")))
        assertEquals(emptyList<String>(), GroupRingLogic.notRung(listOf("b"), setOf("b")))
    }
}
