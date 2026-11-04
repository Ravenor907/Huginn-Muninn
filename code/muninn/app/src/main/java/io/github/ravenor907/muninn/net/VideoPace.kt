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

    /** Queues [publish], one call a piece, under [key]. False when that key is already on its way (nothing added). */
    fun go(key: String, publish: List<() -> Unit>): Boolean {
        if (publish.isEmpty() || !queued.add(key)) return false
        progress(key, 0, publish.size)
        val now = System.currentTimeMillis()
        publish.forEachIndexed { i, p ->
            val at = synchronized(pacer) { pacer.take(now) }
            timer.schedule({ step(key, i, publish.size, p) }, (at - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
        return true
    }

    private fun step(key: String, i: Int, n: Int, p: () -> Unit) {
        // Paused by a relay's "rate-limited": this piece waits for the pause to end (in its turn).
        val wait = synchronized(pacer) { if (pacer.pausedAt(System.currentTimeMillis())) pacer.take(System.currentTimeMillis()) - System.currentTimeMillis() else 0L }
        if (wait > 0) { timer.schedule({ step(key, i, n, p) }, wait, TimeUnit.MILLISECONDS); return }
        try { p() } catch (_: Exception) {}
        progress(key, i + 1, n)
        if (i + 1 >= n) queued.remove(key)
    }

    @Synchronized private fun progress(key: String, done: Int, n: Int) {
        val old = _sent.value[key]
        if (old != null && old.second == n && old.first >= done && done != 0) return
        _sent.value = (_sent.value + (key to (done to n))).let { m -> if (m.size > 400) m.entries.drop(m.size - 400).associate { it.key to it.value } else m }
    }

    /** A relay answered "rate-limited" (Nostr's sending socket). */
    fun limited() { synchronized(pacer) { pacer.limited(System.currentTimeMillis()) } }
}
