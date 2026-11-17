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
 * stored events (some cap well below 100), so any answer with events in it is followed by a request for the ones
 * before its oldest (`until`), until a page brings nothing older. Only then is the relay's EOSE cursor moved on.
 * Never a plain `limit`, which would silently drop the rest.
 */
class InboxPaging {
    private class Page { var count = 0; var oldest = 0L; var until = 0L }
    private val pages = HashMap<String, Page>()
    /** Relays whose inbox request finished handing over what it held (live from then on). */
    private val live = HashSet<String>()

    /** A new inbox request (or the socket ended): count from nothing. */
    @Synchronized fun reset(url: String) { pages.remove(url); live.remove(url) }

    /** An event on the inbox request ([page] false) or the page request: only stored ones (before EOSE) count. */
    @Synchronized fun stored(url: String, page: Boolean, createdAt: Long) {
        if (!backlog(url, page)) return
        val p = pages.getOrPut(url) { Page() }
        p.count++
        if (createdAt > 0 && (p.oldest <= 0 || createdAt < p.oldest)) p.oldest = createdAt
    }

    /**
     * An event on the inbox request ([page] false) or the page request is one the relay held (catch-up), not one sent
     * live: before the inbox request's EOSE, or on a page asked for.
     */
    @Synchronized fun backlog(url: String, page: Boolean): Boolean =
        if (!page) url !in live else (pages[url]?.until ?: 0L) != 0L

    /**
     * EOSE on the inbox request or the page request. Returns the `until` for the next page, or null when everything
     * has come (then the page request is closed, if it was one, and the cursor may move). Whatever the relay's own page
     * size: a page with anything older than the last one asks again; one with nothing older (empty, or only events at
     * the same second as `until`, which `until` takes in again) ends it, so it never loops.
     */
    @Synchronized fun end(url: String, page: Boolean): Long? {
        if (!page) live.add(url)
        val p = pages.getOrPut(url) { Page() }
        val more = p.count > 0 && p.oldest > 0 && (p.until == 0L || p.oldest < p.until)
        if (more) { p.until = p.oldest; p.count = 0; return p.until }
        pages.remove(url)
        return null
    }

    /** An EOSE counts: always on the inbox request; on the page request only while a page was asked for (`until` set). */
    @Synchronized fun asked(url: String, page: Boolean): Boolean = !page || (pages[url]?.until ?: 0L) != 0L

    /** A page request is going on this relay (its cursor waits). */
    @Synchronized fun paging(url: String): Boolean = (pages[url]?.until ?: 0L) != 0L
}

/**
 * The relay event ids already taken ([Nostr]), the newest [max] of them (as many as Huginn keeps), so a copy from
 * another relay, or the same one again, is dropped before any work. Kept as the first 64 bits of each id, so 20000 of
 * them stay small in memory and on disk; nobody can make an event with a chosen id, so a false match is as good as
 * impossible. Only ids of events checked and taken go in (never an id an event merely claims).
 */
class SeenIds(private val max: Int = MAX) {
    private val ids = LinkedHashSet<Long>()

    @Synchronized fun contains(id: String): Boolean = key(id)?.let { it in ids } ?: false
    /** False when it was there already. */
    @Synchronized fun add(id: String): Boolean {
        val k = key(id) ?: return false
        if (!ids.add(k)) return false
        while (ids.size > max) ids.remove(ids.first())
        return true
    }
    @Synchronized fun remove(id: String) { key(id)?.let { ids.remove(it) } }
    /** The first [n] of [keys] (already [key]s, oldest first), as when they were saved. */
    @Synchronized fun addKeys(keys: LongArray, n: Int = keys.size) {
        for (i in 0 until n) ids.add(keys[i])
        val it = ids.iterator()
        while (ids.size > max && it.hasNext()) { it.next(); it.remove() }
    }
    @Synchronized fun size(): Int = ids.size
    @Synchronized fun clear() { ids.clear() }
    /** For saving: oldest first, 16 hex characters each. */
    @Synchronized fun toList(): List<String> = ids.map { java.lang.Long.toHexString(it).padStart(16, '0') }

    /**
     * The same as a JSON array of strings (["0123456789abcdef",...]), written straight into one byte array between
     * [head] and [tail]: 20000 ids are about 380 KB, and making a String (and a JSON value) for each one first cost
     * some 60000 objects and a few MB on every save.
     */
    @Synchronized fun json(head: ByteArray = ByteArray(0), tail: ByteArray = ByteArray(0)): ByteArray {
        val n = ids.size
        val body = if (n == 0) 2 else n * 19 + 1   // "16 hex", each with a comma but the last, and the brackets
        val out = ByteArray(head.size + body + tail.size)
        head.copyInto(out)
        var i = head.size
        out[i++] = '['.code.toByte()
        var first = true
        for (v in ids) {
            if (!first) out[i++] = ','.code.toByte()
            first = false
            out[i++] = '"'.code.toByte()
            for (shift in 60 downTo 0 step 4) out[i++] = HEX[((v ushr shift) and 0xf).toInt()]
            out[i++] = '"'.code.toByte()
        }
        out[i++] = ']'.code.toByte()
        tail.copyInto(out, i)
        return out
    }

    companion object {
        const val MAX = 20_000
        private val HEX = "0123456789abcdef".toByteArray()
        /** The first 16 hex characters of an event id as a number, or null when it isn't one. */
        fun key(id: String): Long? =
            if (id.length < 16) null else try { java.lang.Long.parseUnsignedLong(id.substring(0, 16), 16) } catch (_: NumberFormatException) { null }
    }
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

/**
 * 1.54: reactions and edits (room "mx") that came before the message they're for. The relays hand the newest over
 * first, and a resend may beat the original, so one for a message not here yet is held [HOLD_MS] (at most [MAX] in all,
 * the oldest going first) and handed back when that message lands. Plain Kotlin, so the tests check it.
 */
class MxPending(private val holdMs: Long = HOLD_MS, private val max: Int = MAX) {
    private class Held(val from: String, val msg: String, val name: String, val p: org.json.JSONObject, val at: Long)
    private val held = ArrayList<Held>()

    @Synchronized fun hold(from: String, msg: String, name: String, p: org.json.JSONObject, now: Long) {
        held.removeAll { now - it.at > holdMs }
        held.add(Held(from, msg, name, p, now))
        while (held.size > max) held.removeAt(0)
    }

    /** What waited for [from]'s message [msg] (name, the reaction or edit), oldest first; each is handed back once. */
    @Synchronized fun take(from: String, msg: String, now: Long): List<Pair<String, org.json.JSONObject>> {
        held.removeAll { now - it.at > holdMs }
        val mine = held.filter { it.from == from && it.msg == msg }
        if (mine.isEmpty()) return emptyList()
        held.removeAll { it.from == from && it.msg == msg }
        return mine.map { it.name to it.p }
    }

    @Synchronized fun size(): Int = held.size

    companion object {
        const val HOLD_MS = 30 * 60_000L
        const val MAX = 300
    }
}
