package io.github.ravenor907.muninn.tv

import android.content.Context
import io.github.ravenor907.muninn.Muninn
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A release that crashes as it starts must never cut a family off from their Kids' Device. Each start of Muninn on a Kids'
 * Device that crashes within a minute is counted (in plain prefs, outside the vault). After [LIMIT] in a row with the same
 * version, Muninn starts in a safe mode: no vault, no chats and no screens beyond one line, only the updater, which looks
 * for a newer release now and every hour and installs it with the device's own session ([TvUpdate.install]). A newer
 * version (or a minute without a crash) starts normally again. The counting rules are free of Android for the unit tests.
 */
object SafeStart {
    /** Crashes in a row, with the same version, before the safe mode. */
    const val LIMIT = 3
    /** A crash this soon after the start counts. */
    const val WINDOW_MS = 60_000L
    private const val PREFS = "muninn-safe"
    private const val CRASHES = "crashes"
    private const val VERSION = "version"
    private const val HOUR = 60 * 60_000L

    /** True while this start runs in the safe mode. */
    @Volatile var active = false
        private set

    /** The crashes that still count for [current]: a different version than the one that crashed starts over. */
    fun carried(crashes: Int, crashedVersion: String, current: String): Int = if (crashedVersion == current) crashes.coerceAtLeast(0) else 0

    /** Whether [crashes] in a row mean the safe mode. */
    fun safe(crashes: Int): Boolean = crashes >= LIMIT

    /** Whether a crash [aliveMs] after the start counts. */
    fun counts(aliveMs: Long): Boolean = aliveMs in 0 until WINDOW_MS

    /**
     * First thing as Muninn starts, on a Kids' Device only: decides the safe mode, and counts a crash in the next minute.
     */
    fun begin(c: Context) {
        try {
            if (!KidMode.saved(c)) return
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val version = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
            val crashes = carried(p.getInt(CRASHES, 0), p.getString(VERSION, null) ?: "", version)
            active = safe(crashes)
            val started = android.os.SystemClock.elapsedRealtime()
            val before = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    if (counts(android.os.SystemClock.elapsedRealtime() - started))
                        p.edit().putInt(CRASHES, crashes + 1).putString(VERSION, version).commit()
                } catch (_: Exception) {}
                before?.uncaughtException(t, e)
            }
            // A minute without a crash: this version starts normally (in the safe mode too, it stays until a newer one).
            if (!active) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { p.edit().putInt(CRASHES, 0).apply() } catch (_: Exception) {}
            }, WINDOW_MS)
        } catch (_: Exception) {}
    }

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safe-update").apply { isDaemon = true } }

    /** The safe mode's one job: a newer Muninn, now and every hour, installed as a Kids' Device installs its updates. */
    fun run(app: Muninn) {
        app.updater.installer = { apk -> TvUpdate.install(app, apk); true }
        timer.scheduleWithFixedDelay({ try { app.updater.check(installAfter = true) } catch (_: Exception) {} }, 20_000L, HOUR, TimeUnit.MILLISECONDS)
    }
}
