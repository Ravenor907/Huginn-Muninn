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
    /**
     * Crashes in a row, with the same install, before the safe mode. Round 17: 2, since Android itself stops starting an
     * app in the background after it crashed twice quickly.
     */
    const val LIMIT = 2
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
    /** Round 17: this start hasn't been healthy yet, and when it began (the clock, to find its exit). */
    private const val PENDING = "pending"
    private const val STARTED = "started"
    @Volatile private var startedAt = 0L
    @Volatile private var healthyNow = false
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

    /** Android's reasons a process ended (ApplicationExitInfo): a crash in Java or Kotlin, a native crash, or not responding. */
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6

    /**
     * Round 17 (Android 11 and newer): the last start counts as a crash when it never ran long enough to be [healthy]
     * ([reachedHealthy] false) and Android says it ended in a crash, a native crash (opus, voice, WebRTC) or not
     * responding, at any time before it was healthy. Being stopped for memory, or by the person, never counts.
     */
    fun exitCounts(reason: Int, reachedHealthy: Boolean): Boolean =
        !reachedHealthy && (reason == REASON_CRASH || reason == REASON_CRASH_NATIVE || reason == REASON_ANR)

    /** Before Android 11 (only Java and Kotlin crashes are seen): a crash counts until the start is healthy. */
    fun countsUntilHealthy(aliveMs: Long, healthy: Boolean): Boolean = !healthy && aliveMs in 0 until HEALTHY_MS

    /** When a parent's frame makes the start healthy: [AFTER_PARENT_MS] after it, and never before [HEALTHY_MS] from the start. */
    fun healthyIn(aliveMs: Long): Long = maxOf(AFTER_PARENT_MS, HEALTHY_MS - aliveMs.coerceAtLeast(0L))
    const val AFTER_PARENT_MS = 60_000L

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
            val exits = android.os.Build.VERSION.SDK_INT >= 30
            // Round 17: the last start never became healthy and Android says how it ended: a native crash, a crash after the
            // first minute and not responding count too (Android 11 and newer).
            var stored = p.getInt(CRASHES, 0)
            if (exits && p.getBoolean(PENDING, false) && exitCounts(lastExit(c, p.getLong(STARTED, 0L)), false)) stored += 1
            val crashes = startFrom(stored, p.getString(VERSION, null) ?: "", version, p.getInt(BOOT, -1), boot, since, now)
            active = safe(crashes)
            // The count this start goes on from is written now, so a trial start that crashes again lands straight back.
            val e = p.edit().putInt(CRASHES, crashes).putString(VERSION, version).putInt(BOOT, boot)
            if (active) { if (since <= 0L || trialDue(since, now)) e.putLong(SINCE, now) } else e.remove(SINCE)
            e.putBoolean(PENDING, !active).putLong(STARTED, now)
            e.commit()
            val started = android.os.SystemClock.elapsedRealtime()
            startedAt = started
            healthyNow = false
            // Before Android 11 only Java and Kotlin crashes can be seen: each one counts until the start is healthy.
            if (!exits) {
                val before = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { t, ex ->
                    try {
                        if (countsUntilHealthy(android.os.SystemClock.elapsedRealtime() - started, healthyNow))
                            p.edit().putInt(CRASHES, crashes + 1).putString(VERSION, version).putInt(BOOT, boot).commit()
                    } catch (_: Exception) {}
                    before?.uncaughtException(t, ex)
                }
            }
            // A few minutes running normally: this install starts normally again.
            if (!active) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ healthy(c) }, HEALTHY_MS)
        } catch (_: Exception) {}
    }

    /** Muninn runs normally (a few minutes went by, or a parent's settings or update arrived): the count starts over. */
    fun healthy(c: Context) {
        if (active) return
        healthyNow = true
        try { prefs(c).edit().putInt(CRASHES, 0).remove(SINCE).putBoolean(PENDING, false).apply() } catch (_: Exception) {}
    }

    /**
     * Round 17: a parent's settings or update arrived. The count starts over only once Muninn has kept running a while
     * after it ([healthyIn]): a crash loop a parent's Update now lands in still counts.
     */
    fun parentHeard(c: Context) {
        if (active || healthyNow) return
        val app = c.applicationContext
        val alive = android.os.SystemClock.elapsedRealtime() - startedAt
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ healthy(app) }, healthyIn(alive))
    }

    /** Why the start that began at [since] (the clock) ended, from Android (0 when not known). */
    private fun lastExit(c: Context, since: Long): Int = try {
        if (android.os.Build.VERSION.SDK_INT < 30) 0 else {
            val am = c.getSystemService(android.app.ActivityManager::class.java)
            am?.getHistoricalProcessExitReasons(c.packageName, 0, 5).orEmpty()
                .firstOrNull { it.processName == c.packageName && it.timestamp >= since }?.reason ?: 0
        }
    } catch (_: Exception) { 0 }

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
