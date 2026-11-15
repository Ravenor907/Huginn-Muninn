package io.github.ravenor907.muninn.net

import android.content.Context

/**
 * What the call relay carried this month for files and videos. This device's own count, and the total every device reports
 * to the Worker (relay.md "Relayed media"), so the free month is never overrun and nobody gets a surprise. Past
 * [MediaRoute.LIMIT_BYTES] relayed sends go as the 720p copy, and Settings says so calmly.
 *
 * Anyone with the app can report to the shared total, so it is only advice (as Huginn's FileLink::relayShared): it is
 * believed up to [SHARED_PER_DAY_BYTES] for each day of the month so far, and it may bring the 720p step, never the
 * stop. The 950 GB stop ([MediaRoute.STOP_BYTES]) comes only from this install's own count.
 */
class RelayMeter(context: Context) {
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-meter").apply { isDaemon = true } }
    @Volatile private var askedAt = 0L
    @Volatile private var restUntil = 0L
    private val going = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun roll() {
        val m = MediaRoute.month(System.currentTimeMillis())
        if (prefs.getString(MONTH, "") != m) prefs.edit().putString(MONTH, m).putLong(LOCAL, 0).putLong(SHARED, 0).putLong(WAITING, 0).apply()
    }

    private val local get() = prefs.getLong(LOCAL, 0)
    private val shared get() = prefs.getLong(SHARED, 0)

    @Synchronized fun add(bytes: Long) {
        roll()
        val waiting = prefs.getLong(WAITING, 0) + bytes
        prefs.edit().putLong(LOCAL, local + bytes).putLong(WAITING, waiting).apply()
        if (waiting >= MediaRoute.REPORT_EVERY_BYTES) report()
    }

    /** Over the month's limit. Asks how it stands now and then, in the background; what's known counts until then. */
    fun over(): Boolean {
        roll()
        val now = System.currentTimeMillis()
        if (now - askedAt > ASK_EVERY_MS) { askedAt = now; report() }
        return MediaRoute.over(local, believed())
    }

    /** The 950 GB stop: this install's own count only (a shared total anyone can raise never stops relayed files). */
    fun stopped(): Boolean { over(); return MediaRoute.stopped(local, 0L) }

    fun note(): String {
        roll()
        // The shared total can say "near the limit" (720p), never "used up".
        return MediaRoute.meterNote(local, if (MediaRoute.stopped(local, 0L)) believed() else believed().coerceAtMost(MediaRoute.STOP_BYTES - 1))
    }

    private fun believed(): Long = believedShared(shared, java.time.Instant.now().atZone(java.time.ZoneOffset.UTC).dayOfMonth)

    /** One report at a time, what's waiting read when it goes; after a failure not again for [ASK_EVERY_MS]. */
    private fun report() {
        if (!UltraPrivacy.directAllowed || System.currentTimeMillis() < restUntil || !going.compareAndSet(false, true)) return
        work.execute {
            try {
                val mb = (prefs.getLong(WAITING, 0) / 1_000_000L).coerceAtMost(MAX_MB)
                val total = try { io.github.ravenor907.muninn.net.call.CallManager.relayMeter(mb) } catch (_: Exception) { null }
                if (total == null) { restUntil = System.currentTimeMillis() + ASK_EVERY_MS; return@execute }
                synchronized(this) {
                    prefs.edit().putLong(SHARED, total * 1_000_000L).putLong(WAITING, (prefs.getLong(WAITING, 0) - mb * 1_000_000L).coerceAtLeast(0)).apply()
                }
            } finally { going.set(false) }
        }
    }

    companion object {
        private const val MONTH = "meterMonth"
        private const val LOCAL = "meterBytes"
        private const val SHARED = "meterShared"
        private const val WAITING = "meterWaiting"
        private const val ASK_EVERY_MS = 6 * 3_600_000L
        private const val MAX_MB = 5000L
        /** The most of the shared total believed for each day of the month so far (Huginn's kSharedPerDayBytes). */
        const val SHARED_PER_DAY_BYTES = 66L * 1_000_000_000L

        /** The shared total [shared] as far as it is believed on day [dayOfMonth] (1 to 31, UTC). */
        fun believedShared(shared: Long, dayOfMonth: Int): Long = shared.coerceIn(0L, dayOfMonth.coerceIn(1, 31) * SHARED_PER_DAY_BYTES)
    }
}
