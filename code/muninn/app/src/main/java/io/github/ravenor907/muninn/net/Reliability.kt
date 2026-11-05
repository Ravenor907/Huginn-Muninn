package io.github.ravenor907.muninn.net

import java.security.MessageDigest

/**
 * Small pure helpers for staying connected and quick: no Android in here, so the JVM tests check them as they are.
 */

/**
 * A least-recently-used cache holding at most [maxWeight] in all ([weigh] gives each value's weight; 1 by default,
 * so it then counts entries). Thread safe. A value heavier than the whole cache is never kept.
 */
class Lru<K : Any, V : Any>(private val maxWeight: Long, private val weigh: (V) -> Long = { 1L }) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var total = 0L

    @Synchronized fun get(key: K): V? = map[key]

    @Synchronized fun put(key: K, value: V) {
        val w = weigh(value).coerceAtLeast(0L)
        map.remove(key)?.let { total -= weigh(it) }
        if (w > maxWeight) return
        map[key] = value
        total += w
        val it = map.entries.iterator()
        while (total > maxWeight && it.hasNext()) { val e = it.next(); total -= weigh(e.value); it.remove() }
    }

    /** The cached value, or [make]'s (kept when not null). [make] runs outside the lock. */
    fun getOrPut(key: K, make: () -> V?): V? {
        get(key)?.let { return it }
        val v = make() ?: return null
        put(key, v)
        return v
    }

    @Synchronized fun remove(key: K): V? = map.remove(key)?.also { total -= weigh(it) }
    /** Empties the cache; [wipe] sees every value first (to zero a key). */
    @Synchronized fun clear(wipe: ((V) -> Unit)? = null) { if (wipe != null) map.values.forEach(wipe); map.clear(); total = 0L }
    @Synchronized fun size(): Int = map.size
    @Synchronized fun weight(): Long = total
}

/** Picture caches sized by how much memory Android gives Muninn (ActivityManager.memoryClass, in MB). */
object MemoryBudget {
    /** 1/[part] of the app's memory, at least 4 MB and at most 64 MB. */
    fun bytes(memoryClassMb: Int, part: Int): Long =
        (memoryClassMb.toLong() * 1024 * 1024 / part.coerceAtLeast(1)).coerceIn(4L * 1024 * 1024, 64L * 1024 * 1024)
}

/** How long the outgoing retry loop sleeps: until the next thing is due, never spinning and never too long. */
object RetryClock {
    /** Never sooner than this between rounds (a burst of wake-ups costs one round). */
    const val MIN_MS = 200L
    /** Never longer than this while something waits, so a try still under way is looked at again. */
    const val MAX_MS = 15_000L

    /**
     * Milliseconds to sleep before the next round, with [nextDue] the earliest time anything is due (Long.MAX_VALUE:
     * nothing scheduled). Null when nothing waits at all ([busy] false): sleep until something is queued.
     */
    fun sleepMs(busy: Boolean, nextDue: Long, now: Long): Long? {
        if (!busy) return null
        if (nextDue == Long.MAX_VALUE) return MAX_MS
        return (nextDue - now).coerceIn(MIN_MS, MAX_MS)
    }
}

/** Reconnecting after the network changed: at once the first time, then slower while it keeps changing. */
object NetBackoff {
    /** Changes counted within this window. */
    const val WINDOW_MS = 60_000L
    const val CAP_MS = 30_000L

    /** Wait before reconnecting, with [changes] network changes (this one included) within the last [WINDOW_MS]. */
    fun waitMs(changes: Int): Long = if (changes <= 1) 0L else minOf(CAP_MS, 1000L shl minOf(changes - 2, 5))

    /** The same, but in a call or a voice room ([inCall]) every change reconnects at once: its signals can't wait. */
    fun waitMs(changes: Int, inCall: Boolean): Long = if (inCall) 0L else waitMs(changes)
}

/**
 * Asking a relay for the inbox in pages, as Huginn's nostr.cpp does: a relay hands over at most its own limit of
 * stored events, so a full answer (at least [full] events before EOSE) is followed by a request for the ones before
 * its oldest (`until`), until a page comes back short. Only then is the relay's EOSE cursor moved on. Never a
 * plain `limit`, which would silently drop the rest.
 */
class InboxPaging(private val full: Int = PAGE_FULL) {
    private class Page { var count = 0; var oldest = 0L; var until = 0L }
    private val pages = HashMap<String, Page>()
    /** Relays whose inbox request finished handing over what it held (live from then on). */
    private val live = HashSet<String>()

    /** A new inbox request (or the socket ended): count from nothing. */
    @Synchronized fun reset(url: String) { pages.remove(url); live.remove(url) }

    /** An event on the inbox request ([page] false) or the page request: only stored ones (before EOSE) count. */
    @Synchronized fun stored(url: String, page: Boolean, createdAt: Long) {
        if (if (!page) url in live else (pages[url]?.until ?: 0L) == 0L) return
        val p = pages.getOrPut(url) { Page() }
        p.count++
        if (createdAt > 0 && (p.oldest <= 0 || createdAt < p.oldest)) p.oldest = createdAt
    }

    /**
     * EOSE on the inbox request or the page request. Returns the `until` for the next page, or null when everything
     * has come (then the page request is closed, if it was one, and the cursor may move).
     */
    @Synchronized fun end(url: String, page: Boolean): Long? {
        if (!page) live.add(url)
        val p = pages.getOrPut(url) { Page() }
        val more = p.count >= full && p.oldest > 0 && (p.until == 0L || p.oldest < p.until)
        if (more) { p.until = p.oldest; p.count = 0; return p.until }
        pages.remove(url)
        return null
    }

    /** An EOSE counts: always on the inbox request; on the page request only while a page was asked for (`until` set). */
    @Synchronized fun asked(url: String, page: Boolean): Boolean = !page || (pages[url]?.until ?: 0L) != 0L

    /** A page request is going on this relay (its cursor waits). */
    @Synchronized fun paging(url: String): Boolean = (pages[url]?.until ?: 0L) != 0L

    companion object { const val PAGE_FULL = 100 }
}

/**
 * Rapid notifications for one chat become one: the first in a quiet spell goes at once (nothing is delayed), and
 * what comes within [windowMs] after it waits and goes as one update when the window ends, with the newest content.
 */
class NotifyCoalescer<T>(private val windowMs: Long = WINDOW_MS) {
    private class Slot<T>(var until: Long, var latest: T? = null, var count: Int = 0)
    private val slots = HashMap<String, Slot<T>>()

    sealed class Offer {
        object Now : Offer()
        /** Held: post [flushAt]'s update then (schedule [flush] once; [first] true only for the first one held). */
        class Later(val flushAt: Long, val first: Boolean) : Offer()
    }

    @Synchronized fun offer(key: String, item: T, now: Long): Offer {
        val s = slots[key]
        if (s == null || now >= s.until) {
            slots[key] = Slot(now + windowMs)
            return Offer.Now
        }
        val first = s.latest == null
        s.latest = item; s.count++
        return Offer.Later(s.until, first)
    }

    /** The window for [key] ended: the newest held item and how many were held, or null for none. */
    @Synchronized fun flush(key: String, now: Long): Pair<T, Int>? {
        val s = slots[key] ?: return null
        val item = s.latest ?: run { if (now >= s.until) slots.remove(key); return null }
        val n = s.count
        // The update itself opens a new window, so a steady stream still posts at most once per window.
        slots[key] = Slot(now + windowMs)
        return item to n
    }

    /** Opened, recalled or read: nothing held for [key] goes out. */
    @Synchronized fun drop(key: String) { slots.remove(key) }
    @Synchronized fun clear() = slots.clear()

    companion object { const val WINDOW_MS = 2_000L }
}

/** Writes a named file only when what's in it changed since the last write that went through. */
class ChangedOnly {
    private val last = HashMap<String, String>()

    /** True when [text] differs from what was last [done] for [name]. */
    @Synchronized fun changed(name: String, text: String): Boolean = last[name] != digest(text)

    /** [text] was written for [name]. */
    @Synchronized fun done(name: String, text: String) { last[name] = digest(text) }

    @Synchronized fun forget() = last.clear()

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
