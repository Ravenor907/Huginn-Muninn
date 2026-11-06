package io.github.ravenor907.muninn.net

import android.content.Context

/**
 * What the call relay carried this month for files and videos. This device's own count, and the total every device reports
 * to the Worker (relay.md "Relayed media"), so the free month is never overrun and nobody gets a surprise. Past
 * [MediaRoute.LIMIT_BYTES] relayed sends go as the 720p copy, and Settings says so calmly.
 */
class RelayMeter(context: Context) {
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    private val work = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-meter").apply { isDaemon = true } }
    @Volatile private var askedAt = 0L

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
        if (waiting >= MediaRoute.REPORT_EVERY_BYTES) report(waiting)
    }

    /** Over the month's limit. Asks how it stands now and then, in the background; what's known counts until then. */
    fun over(): Boolean {
        roll()
        val now = System.currentTimeMillis()
        if (now - askedAt > ASK_EVERY_MS) { askedAt = now; report(0) }
        return MediaRoute.over(local, shared)
    }

    fun note(): String { roll(); return MediaRoute.meterNote(local, shared) }

    private fun report(bytes: Long) {
        work.execute {
            val mb = bytes / 1_000_000L
            val total = try { io.github.ravenor907.muninn.net.call.CallManager.relayMeter(mb) } catch (_: Exception) { null } ?: return@execute
            synchronized(this) {
                prefs.edit().putLong(SHARED, total * 1_000_000L).putLong(WAITING, (prefs.getLong(WAITING, 0) - mb * 1_000_000L).coerceAtLeast(0)).apply()
            }
        }
    }

    private companion object {
        const val MONTH = "meterMonth"
        const val LOCAL = "meterBytes"
        const val SHARED = "meterShared"
        const val WAITING = "meterWaiting"
        const val ASK_EVERY_MS = 6 * 3_600_000L
    }
}
