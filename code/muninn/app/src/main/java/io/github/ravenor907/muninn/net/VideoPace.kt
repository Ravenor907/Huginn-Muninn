package io.github.ravenor907.muninn.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cap v3 (protocol.md 29.4): a video's relay pieces leave this phone paced, [GroupVideo.PACE_PER_SECOND] a second in
 * all, so a group video (a sealed copy per member) never floods the public relays and gets the account blocked. A relay
 * that answers "rate-limited" stops everything for [GroupVideo.BACKOFF_MS]. One job per member and message ([go]'s key):
 * a retry while its pieces are still queued adds nothing. [sent] says how far each one got, for the message's Info.
 */
object VideoPace {
    private val pacer = GroupVideo.Pacer()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-video-pace").apply { isDaemon = true } }
    private val queued: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _sent = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())
    /** Key ("member|envelope") to pieces handed to the relays and how many there are. */
    val sent: StateFlow<Map<String, Pair<Int, Int>>> = _sent.asStateFlow()

    /** The key for a member's copy of a message (a group's envelope id, or the private message's id). */
    fun key(member: String, env: String) = "$member|$env"

    /** One relay piece waiting its turn. */
    private class Piece(val key: String, val i: Int, val n: Int, val publish: () -> Unit)

    // Round 19: one queue, drained one piece at a time by the timer, so a "rate-limited" pause holds back everything
    // after it. (Booking every piece's time up front let pieces booked after the pause fire on time next to the held
    // ones, so for a while more than [GroupVideo.PACE_PER_SECOND] a second went out.)
    private val queue = ArrayDeque<Piece>()
    private var draining = false

    /** Queues [publish], one call a piece, under [key]. False when that key is already on its way (nothing added). */
    fun go(key: String, publish: List<() -> Unit>): Boolean {
        if (publish.isEmpty() || !queued.add(key)) return false
        progress(key, 0, publish.size)
        synchronized(queue) {
            publish.forEachIndexed { i, p -> queue.addLast(Piece(key, i, publish.size, p)) }
            if (!draining) { draining = true; timer.execute { next() } }
        }
        return true
    }

    /** Takes the next piece and books its slot; only this one piece waits on the timer at a time. */
    private fun next() {
        val piece = synchronized(queue) { queue.removeFirstOrNull().also { if (it == null) draining = false } } ?: return
        val now = System.currentTimeMillis()
        val at = synchronized(pacer) { pacer.take(now) }
        timer.schedule({ fire(piece) }, (at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun fire(piece: Piece) {
        // Paused by a relay's "rate-limited" since its slot was booked: it waits for the pause to end.
        val now = System.currentTimeMillis()
        val wait = synchronized(pacer) { if (pacer.pausedAt(now)) pacer.take(now) - now else 0L }
        if (wait > 0) { timer.schedule({ fire(piece) }, wait, TimeUnit.MILLISECONDS); return }
        try { piece.publish() } catch (_: Exception) {} finally {
            progress(piece.key, piece.i + 1, piece.n)
            if (piece.i + 1 >= piece.n) queued.remove(piece.key)
            next()
        }
    }

    @Synchronized private fun progress(key: String, done: Int, n: Int) {
        val old = _sent.value[key]
        if (old != null && old.second == n && old.first >= done && done != 0) return
        _sent.value = (_sent.value + (key to (done to n))).let { m -> if (m.size > 400) m.entries.drop(m.size - 400).associate { it.key to it.value } else m }
    }

    /** A relay answered "rate-limited" (Nostr's sending socket). */
    fun limited() { synchronized(pacer) { pacer.limited(System.currentTimeMillis()) } }
}
