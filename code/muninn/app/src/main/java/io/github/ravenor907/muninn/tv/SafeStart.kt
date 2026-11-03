package io.github.ravenor907.muninn.tv

import android.content.Context
import io.github.ravenor907.muninn.Muninn
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A release that crashes as it starts must never cut a family off from their Kids' Device. Each start of Muninn on a Kids'
 * Device that crashes within a minute is counted (in plain prefs, outside the vault). After [LIMIT] in a row with the same
 * install, in the same boot, Muninn starts in a safe mode: no vault, no chats and no screens beyond one note, only the
 * updater, which looks for a newer release now and every hour and installs it with the device's own session
 * ([TvUpdate.install]); an install that needs a press on the device can be confirmed from the note.
 *
 * The safe mode never traps a device on the same version. It ends:
 * - when a new install arrives (a newer release, or Muninn installed again: the install time changes),
 * - when the device restarts (Android's boot count changes),
 * - every [TRIAL_MS] by itself, and at once from the note's Try again: one normal start with [LIMIT] - 1 crashes kept,
 *   so a single crash goes straight back to the safe mode,
 * - after [HEALTHY_MS] of running normally, or as soon as a parent's settings or update arrive ([healthy]).
 * The counting rules are free of Android for the unit tests.
 */
object SafeStart {
    /** Crashes in a row, with the same install, before the safe mode. */
    const val LIMIT = 3
    /** A crash this soon after the start counts. */
    const val WINDOW_MS = 60_000L
    /** Running normally this long clears the count. */
    const val HEALTHY_MS = 3 * 60_000L
    /** The safe mode tries one normal start this often. */
    const val TRIAL_MS = 6 * 60 * 60_000L
    private const val PREFS = "muninn-safe"
    private const val CRASHES = "crashes"
    private const val VERSION = "version"
    private const val BOOT = "boot"
    private const val SINCE = "since"
    private const val HOUR = 60 * 60_000L
    /** The broadcast that starts Muninn again after the safe mode let go of the process ([StartAtBoot]). */
    const val TRY_AGAIN = "io.github.ravenor907.muninn.SAFE_TRY_AGAIN"

    /** True while this start runs in the safe mode. */
    @Volatile var active = false
        private set

    /** The crashes that still count for [current]: a different install than the one that crashed starts over. */
    fun carried(crashes: Int, crashedVersion: String, current: String): Int = if (crashedVersion == current) crashes.coerceAtLeast(0) else 0

    /**
     * The crashes this start goes on from: [carried], and none after a restart of the device ([crashedBoot] and [boot]
     * differ, both known). After [TRIAL_MS] in the safe mode (since [safeSince]), [LIMIT] - 1: one normal try.
     */
    fun startFrom(crashes: Int, crashedVersion: String, current: String, crashedBoot: Int, boot: Int, safeSince: Long, now: Long): Int {
        val c = carried(crashes, crashedVersion, current)
        if (crashedBoot >= 0 && boot >= 0 && crashedBoot != boot) return 0
        return if (safe(c) && trialDue(safeSince, now)) LIMIT - 1 else c
    }

    /** Whether the safe mode, in since [safeSince] (0: not known), is due a normal try at [now]. */
    fun trialDue(safeSince: Long, now: Long): Boolean = safeSince > 0 && (now - safeSince >= TRIAL_MS || now < safeSince)

    /** Whether [crashes] in a row mean the safe mode. */
    fun safe(crashes: Int): Boolean = crashes >= LIMIT

    /** Whether a crash [aliveMs] after the start counts. */
    fun counts(aliveMs: Long): Boolean = aliveMs in 0 until WINDOW_MS

    /** The install this is: its version and when it went in (installing the same version again counts as new). */
    private fun installKey(c: Context): String = try {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        (pi.versionName ?: "") + "@" + pi.lastUpdateTime
    } catch (_: Exception) { "" }

    private fun bootCount(c: Context): Int =
        try { android.provider.Settings.Global.getInt(c.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * First thing as Muninn starts, on a Kids' Device only: decides the safe mode, and counts a crash in the next minute.
     */
    fun begin(c: Context) {
        try {
            if (!KidMode.saved(c)) return
            val p = prefs(c)
            val version = installKey(c)
            val boot = bootCount(c)
            val now = System.currentTimeMillis()
            val since = p.getLong(SINCE, 0L)
            val crashes = startFrom(p.getInt(CRASHES, 0), p.getString(VERSION, null) ?: "", version, p.getInt(BOOT, -1), boot, since, now)
            active = safe(crashes)
            // The count this start goes on from is written now, so a trial start that crashes again lands straight back.
            val e = p.edit().putInt(CRASHES, crashes).putString(VERSION, version).putInt(BOOT, boot)
            if (active) { if (since <= 0L || trialDue(since, now)) e.putLong(SINCE, now) } else e.remove(SINCE)
            e.commit()
            val started = android.os.SystemClock.elapsedRealtime()
            val before = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, ex ->
                try {
                    if (counts(android.os.SystemClock.elapsedRealtime() - started))
                        p.edit().putInt(CRASHES, crashes + 1).putString(VERSION, version).putInt(BOOT, boot).commit()
                } catch (_: Exception) {}
                before?.uncaughtException(t, ex)
            }
            // A few minutes running normally: this install starts normally again.
            if (!active) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ healthy(c) }, HEALTHY_MS)
        } catch (_: Exception) {}
    }

    /** Muninn runs normally (a few minutes went by, or a parent's settings or update arrived): the count starts over. */
    fun healthy(c: Context) {
        if (active) return
        try { prefs(c).edit().putInt(CRASHES, 0).remove(SINCE).apply() } catch (_: Exception) {}
    }

    /** Whether the safe mode is due its normal try now. */
    fun trialDueNow(c: Context): Boolean = try { trialDue(prefs(c).getLong(SINCE, 0L), System.currentTimeMillis()) } catch (_: Exception) { false }

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safe-update").apply { isDaemon = true } }

    /**
     * The safe mode's one job: a newer Muninn, now and every hour, installed as a Kids' Device installs its updates.
     * Every [TRIAL_MS] (looked at hourly), while nothing is on screen, it starts again normally once.
     */
    fun run(app: Muninn) {
        app.updater.installer = { apk -> TvUpdate.install(app, apk); true }
        timer.scheduleWithFixedDelay({ try { app.updater.check(installAfter = true) } catch (_: Exception) {} }, 20_000L, HOUR, TimeUnit.MILLISECONDS)
        timer.scheduleWithFixedDelay({
            try {
                if (!app.visible && trialDueNow(app)) tryAgain(app, null)
            } catch (_: Exception) {}
        }, HOUR, HOUR, TimeUnit.MILLISECONDS)
    }

    /**
     * One normal start now: [LIMIT] - 1 crashes kept (one more goes straight back to the safe mode), then Muninn starts
     * again: on screen through [activity] (the note's Try again), else in the background through [StartAtBoot].
     */
    fun tryAgain(c: Context, activity: android.app.Activity?) {
        val app = c.applicationContext
        try { prefs(app).edit().putInt(CRASHES, LIMIT - 1).remove(SINCE).commit() } catch (_: Exception) {}
        try {
            val am = app.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val i = android.content.Intent(app, io.github.ravenor907.muninn.net.StartAtBoot::class.java).setAction(TRY_AGAIN)
            val pi = android.app.PendingIntent.getBroadcast(app, 7_101, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2_000L, pi)
        } catch (_: Exception) {}
        if (activity != null) try {
            val main = android.content.ComponentName(app, io.github.ravenor907.muninn.MainActivity::class.java)
            activity.startActivity(android.content.Intent.makeRestartActivityTask(main))
        } catch (_: Exception) {}
        Runtime.getRuntime().exit(0)
    }
}
