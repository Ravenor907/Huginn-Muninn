package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChangedOnly
import io.github.ravenor907.muninn.net.InboxPaging
import io.github.ravenor907.muninn.net.SeenIds
import io.github.ravenor907.muninn.net.Lru
import io.github.ravenor907.muninn.net.MemoryBudget
import io.github.ravenor907.muninn.net.NetBackoff
import io.github.ravenor907.muninn.net.NotifyCoalescer
import io.github.ravenor907.muninn.net.RetryClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReliabilityTest {
    // ------------------------------------------------------------------ LRU

    @Test
    fun lruDropsLeastRecentlyUsed() {
        val c = Lru<String, String>(2)
        c.put("a", "1"); c.put("b", "2")
        assertEquals("1", c.get("a"))   // a is now the newest
        c.put("c", "3")
        assertNull(c.get("b"))
        assertEquals("1", c.get("a"))
        assertEquals("3", c.get("c"))
        assertEquals(2, c.size())
    }

    @Test
    fun lruByWeight() {
        val c = Lru<String, ByteArray>(10) { it.size.toLong() }
        c.put("a", ByteArray(4)); c.put("b", ByteArray(4))
        c.put("c", ByteArray(4))   // 12 > 10: a goes
        assertNull(c.get("a"))
        assertEquals(8L, c.weight())
        c.put("huge", ByteArray(11))   // heavier than the whole cache: never kept
        assertNull(c.get("huge"))
        assertEquals(8L, c.weight())
        c.put("b", ByteArray(1))   // replacing counts the new weight only
        assertEquals(5L, c.weight())
        c.clear()
        assertEquals(0, c.size()); assertEquals(0L, c.weight())
    }

    @Test
    fun lruGetOrPutKeepsOnlyRealValues() {
        val c = Lru<String, String>(4)
        var made = 0
        assertEquals("x", c.getOrPut("k") { made++; "x" })
        assertEquals("x", c.getOrPut("k") { made++; "y" })
        assertEquals(1, made)
        assertNull(c.getOrPut("n") { null })
        assertEquals(1, c.size())
    }

    @Test
    fun pictureBudgetFollowsMemoryClass() {
        val mb = 1024L * 1024
        assertEquals(32 * mb, MemoryBudget.bytes(256, 8))
        assertEquals(4 * mb, MemoryBudget.bytes(16, 8))      // at least 4 MB
        assertEquals(64 * mb, MemoryBudget.bytes(1024, 8))   // at most 64 MB
    }

    // ------------------------------------------------------------------ retry loop

    @Test
    fun retrySleepsUntilDue() {
        assertNull(RetryClock.sleepMs(false, 5_000, 0))   // nothing waits: sleep until woken
        assertEquals(4_000L, RetryClock.sleepMs(true, 5_000, 1_000))
        assertEquals(RetryClock.MIN_MS, RetryClock.sleepMs(true, 900, 1_000))   // overdue: soon, not a spin
        assertEquals(RetryClock.MAX_MS, RetryClock.sleepMs(true, 10 * 60_000L, 0))
        assertEquals(RetryClock.MAX_MS, RetryClock.sleepMs(true, Long.MAX_VALUE, 0))
    }

    // ------------------------------------------------------------------ network changes

    @Test
    fun networkBackoffCapped() {
        assertEquals(0L, NetBackoff.waitMs(1))
        assertEquals(1_000L, NetBackoff.waitMs(2))
        assertEquals(2_000L, NetBackoff.waitMs(3))
        assertEquals(NetBackoff.CAP_MS, NetBackoff.waitMs(50))
        // In a call or a voice room: at once, however often the network changes.
        assertEquals(0L, NetBackoff.waitMs(50, inCall = true))
        assertEquals(NetBackoff.CAP_MS, NetBackoff.waitMs(50, inCall = false))
    }

    // ------------------------------------------------------------------ inbox pages

    @Test
    fun emptyAnswerIsAllThereIs() {
        val p = InboxPaging()
        p.reset("r")
        assertNull(p.end("r", false))
        assertFalse(p.paging("r"))
        // Live events after EOSE don't count towards a page.
        repeat(5) { p.stored("r", false, 200) }
        assertFalse(p.backlog("r", false))
        assertNull(p.end("r", false))
    }

    @Test
    fun anyAnswerAsksForTheOnesBeforeWhateverThePageSize() {
        // A relay that caps its pages at 2 (well below 100) still hands over everything.
        val p = InboxPaging()
        p.reset("r")
        assertTrue(p.backlog("r", false))
        listOf(100L, 95L).forEach { p.stored("r", false, it) }
        assertEquals(95L, p.end("r", false))
        assertTrue(p.paging("r"))
        assertTrue(p.backlog("r", true))
        listOf(95L, 85L).forEach { p.stored("r", true, it) }
        assertEquals(85L, p.end("r", true))     // older ones came: one more page
        listOf(85L).forEach { p.stored("r", true, it) }
        assertNull(p.end("r", true))            // nothing older: everything has come
        assertFalse(p.paging("r"))
        assertFalse(p.backlog("r", true))
    }

    @Test
    fun pageWithNoProgressStops() {
        // Every event at the same second: asking until that second again would loop forever.
        val p = InboxPaging()
        p.reset("r")
        p.stored("r", false, 50); p.stored("r", false, 50)
        assertEquals(50L, p.end("r", false))
        p.stored("r", true, 50); p.stored("r", true, 50)
        assertNull(p.end("r", true))
    }

    @Test
    fun relaysPageOnTheirOwn() {
        val p = InboxPaging()
        p.reset("a"); p.reset("b")
        p.stored("a", false, 10); p.stored("a", false, 9)
        assertEquals(9L, p.end("a", false))
        assertNull(p.end("b", false))
        // A page event on a relay with no page going is ignored.
        p.stored("b", true, 5); p.stored("b", true, 4)
        assertNull(p.end("b", true))
        p.reset("a")
        assertFalse(p.paging("a"))
    }

    @Test
    fun pageEoseWithNoPageAskedIsIgnored() {
        val p = InboxPaging()
        p.reset("r")
        assertTrue(p.asked("r", page = false))    // the inbox request's EOSE always counts
        assertFalse(p.asked("r", page = true))    // no page asked for: a page EOSE is ignored
        p.stored("r", false, 10); p.stored("r", false, 9)
        assertEquals(9L, p.end("r", false))
        assertTrue(p.asked("r", page = true))     // a page is going now
        assertNull(p.end("r", true))
        assertFalse(p.asked("r", page = true))
    }

    @Test
    fun seenIdsKeepTheNewest() {
        val s = SeenIds(max = 3)
        val ids = (1..5).map { "%016x".format(it) + "ab".repeat(24) }
        assertFalse(s.contains(ids[0]))
        assertTrue(s.add(ids[0])); assertFalse(s.add(ids[0]))
        ids.drop(1).forEach { s.add(it) }
        assertEquals(3, s.size())
        assertFalse(s.contains(ids[0])); assertFalse(s.contains(ids[1]))
        assertTrue(s.contains(ids[4]))
        s.remove(ids[4]); assertFalse(s.contains(ids[4]))
        // Not an id at all: never matched, never kept.
        assertFalse(s.add("zz")); assertFalse(s.contains("not-hex-at-all-xx"))
        assertEquals(listOf("%016x".format(3), "%016x".format(4)), s.toList())
        assertEquals(20_000, SeenIds.MAX)
    }

    // ------------------------------------------------------------------ notifications

    @Test
    fun rapidNotificationsBecomeOne() {
        val c = NotifyCoalescer<String>(windowMs = 1_000)
        assertTrue(c.offer("k", "a", 0) is NotifyCoalescer.Offer.Now)
        val b = c.offer("k", "b", 100) as NotifyCoalescer.Offer.Later
        assertTrue(b.first); assertEquals(1_000L, b.flushAt)
        val d = c.offer("k", "c", 200) as NotifyCoalescer.Offer.Later
        assertFalse(d.first)
        // Another chat is on its own.
        assertTrue(c.offer("other", "x", 300) is NotifyCoalescer.Offer.Now)
        assertEquals("c" to 2, c.flush("k", 1_000))
        // Nothing held since: nothing to post, and a later message goes at once.
        assertNull(c.flush("k", 2_000))
        assertTrue(c.offer("k", "e", 2_100) is NotifyCoalescer.Offer.Now)
    }

    @Test
    fun droppedChatPostsNothing() {
        val c = NotifyCoalescer<String>(windowMs = 1_000)
        c.offer("k", "a", 0); c.offer("k", "b", 10)
        c.drop("k")
        assertNull(c.flush("k", 1_000))
        assertTrue(c.offer("k", "c", 20) is NotifyCoalescer.Offer.Now)
    }

    // ------------------------------------------------------------------ store writes

    @Test
    fun writesOnlyWhatChanged() {
        val w = ChangedOnly()
        assertTrue(w.changed("f", "one"))
        w.done("f", "one")
        assertFalse(w.changed("f", "one"))
        assertTrue(w.changed("f", "two"))
        assertTrue(w.changed("g", "one"))
        w.forget()
        assertTrue(w.changed("f", "one"))
    }
}
