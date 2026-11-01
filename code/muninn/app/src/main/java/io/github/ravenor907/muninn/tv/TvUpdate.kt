package io.github.ravenor907.muninn.tv

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Muninn keeping itself up to date on a Kids' TV: it looks for a new release when it starts and once a
 * day (and when a parent presses Update TV on their phone), downloads it with the usual [Updater], and
 * installs it with a PackageInstaller session. Android asks once on the TV (Install, with the remote);
 * after Muninn has installed itself once, Android 12 and newer let later updates go in without asking.
 */
object TvUpdate {
    /**
     * What the TV screen shows: a confirm waiting for OK on the remote. [needsParent]: a new Muninn waits
     * until a parent asks the TV, from their phone, to allow installs. [code]: the same for the parents' phones
     * (one of [TvRules.UPDATE_STATES], null when nothing happened since Muninn started), sent in the TV's status.
     */
    data class State(val confirm: Intent? = null, val needsParent: Boolean = false, val status: String = "", val code: String? = null)
    val state = MutableStateFlow(State())
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-update").apply { isDaemon = true } }
    @Volatile private var started = false
    @Volatile private var waiting: File? = null
    private const val ACTION = "io.github.ravenor907.muninn.tv.INSTALLED"
    private const val DAY = 24 * 60 * 60_000L
    private const val HOUR = 60 * 60_000L

    /** Kids' mode only, once: hooks the installer, checks a minute after start, then every day. */
    fun start(app: Muninn) {
        if (started) return
        started = true
        // Muninn came back up as a newer version: the update went in, and the parents' phones hear so.
        try {
            val now = app.updater.current
            val before = prefs(app).getString("runs", "") ?: ""
            if (before != now) {
                prefs(app).edit().putString("runs", now).apply()
                if (before.isNotEmpty()) post(app, State(status = "Muninn is up to date", code = "done"))
            }
        } catch (_: Exception) {}
        app.updater.installer = { apk -> install(app, apk); true }
        // Once a day, and only while it's plugged in (a TV always is; a tablet in kids' mode waits for its charger).
        timer.scheduleWithFixedDelay({
            try {
                val now = System.currentTimeMillis()
                val p = prefs(app)
                // k7 (7.12): a parent who turned automatic checks off turns them off here too; otherwise they stay on.
                val auto = KidTv.state.value.cfg?.upd ?: true
                if (auto && now - p.getLong("lastDailyCheck", 0L) >= DAY && app.power.charging()) {
                    p.edit().putLong("lastDailyCheck", now).apply()
                    checkNow(app)
                }
            } catch (_: Exception) {}
        }, 60_000, HOUR, TimeUnit.MILLISECONDS)
    }

    /** A parent asked (room tvconfig "update"), or the daily check. */
    fun checkNow(app: Muninn) { app.updater.check(installAfter = true) }

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** Installs [apk] with a session; without the "install unknown apps" permission it waits for a parent. */
    fun install(c: Context, apk: File) {
        if (!canInstall(c)) { waiting = apk; post(c, State(needsParent = true, status = TvWords.ASK_PARENT, code = "needs")); return }
        waiting = null
        Thread {
            try {
                val pi = c.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(c.packageName)
                    // Muninn stays the installer of record, so every later update can skip the prompt.
                    if (Build.VERSION.SDK_INT >= 30) setInstallerPackageName(c.packageName)
                    if (Build.VERSION.SDK_INT >= 31) {
                        // Needs UPDATE_PACKAGES_WITHOUT_USER_ACTION (manifest). Android still asks once
                        // (STATUS_PENDING_USER_ACTION below) while another app is the installer of record.
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("Muninn.apk", 0, apk.length()).use { os -> apk.inputStream().use { it.copyTo(os) }; s.fsync(os) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val done = PendingIntent.getBroadcast(c, id, Intent(c, TvInstallResult::class.java).setAction(ACTION).setPackage(c.packageName), flags)
                    s.commit(done.intentSender)
                }
                post(c, State(status = "Installing the new Muninn…", code = "installing"))
            } catch (e: Exception) {
                post(c, State(status = "The update couldn't be installed. It tries again tomorrow.", code = "failed"))
            }
        }.start()
    }

    /** Back from the "install unknown apps" setting with it allowed: install what was waiting. */
    fun resume(c: Context) {
        val apk = waiting ?: return
        if (canInstall(c) && apk.exists()) install(c, apk)
    }

    fun onResult(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                post(c, State(confirm = confirm, status = "A new Muninn is ready", code = "ready"))
                // On screen now: Android's Install box comes straight up. Otherwise the TV screen offers it.
                try { if ((c.applicationContext as? Muninn)?.visible == true) c.startActivity(confirm) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> post(c, State(status = "Muninn is up to date", code = "done"))
            else -> post(c, State(status = "The update didn't go in. It tries again tomorrow.", code = "failed"))
        }
    }

    /** A new state; the parents' phones get it in the TV's status when it changed. */
    private fun post(c: Context, st: State) {
        val was = state.value.code
        state.value = st
        if (st.code != was) (c.applicationContext as? Muninn)?.let { try { KidTv.updateChanged(it) } catch (_: Exception) {} }
    }

    /** The remote's OK on "Install the update": Android's own Install box. */
    fun confirm(c: Context) {
        val i = state.value.confirm ?: return
        state.value = state.value.copy(confirm = null)
        try { c.startActivity(i) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ permissions a parent asks for from their phone

    /** One thing that is still off on the TV: "mic", "notify", "overlay", "fullscreen" or "install". */
    data class Need(val id: String)

    /**
     * What's still off. Microphone and notifications are Android's "Allow" prompts; the rest are Android
     * settings pages. A parent's phone sees the list in the TV's status (7.13) and asks the TV for each.
     */
    fun needs(c: Context): List<Need> = buildList {
        if (TvAudio.hasMic(c) && ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            add(Need("mic"))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            add(Need("notify"))
        if (!Settings.canDrawOverlays(c))
            add(Need("overlay"))
        // "Show over other apps" already lets the call screen open by itself, and many TVs (Google TV) have no
        // full-screen calls screen at all: only needed when the overlay isn't allowed either.
        if (Build.VERSION.SDK_INT >= 34 && !Settings.canDrawOverlays(c) && c.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == false)
            add(Need("fullscreen"))
        if (!canInstall(c))
            add(Need("install"))
    }

    /**
     * Opens the Android setting for [id]. False if this TV has no such screen (some TVs hide them). Not
     * Muninn's own app page: that page has "Clear storage", which would start the TV over, so it only
     * opens through [openAppPage] for a permission Android won't ask for any more.
     */
    fun open(c: Context, id: String): Boolean {
        val pkg = Uri.parse("package:${c.packageName}")
        val tries = when (id) {
            "overlay" -> listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)) else emptyList()
            "install" -> listOf(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg), Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            else -> emptyList()
        }
        for (i in tries) try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: Exception) {}
        return false
    }

    /** The permission Muninn asks for in the app for the need [id] ("mic", "notify"), else null. */
    fun permission(id: String): String? = when (id) {
        "mic" -> Manifest.permission.RECORD_AUDIO
        "notify" -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        else -> null
    }

    /** The one line the TV shows while it asks for [id] (Android's prompt, or its settings page). */
    fun line(id: String, settingsPage: Boolean): String = when {
        id == "mic" && settingsPage -> "Permissions, Microphone, Allow"
        id == "notify" && settingsPage -> "Permissions, Notifications, Allow"
        id == "mic" || id == "notify" -> "Press Allow"
        else -> "Turn on Muninn"
    }

    /** Android's "Allow" box for [perm] came up (or was asked for) once on this TV. */
    fun markAsked(c: Context, perm: String) = prefs(c).edit().putBoolean("asked:$perm", true).apply()

    /** [perm] is off and Android won't show its "Allow" box any more: only Muninn's app page can turn it on. */
    fun settingsOnly(c: Context, activity: Activity?, perm: String): Boolean {
        if (ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED) return false
        val rationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        return TvRules.settingsOnly(prefs(c).getBoolean("asked:$perm", false), rationale)
    }

    /**
     * Muninn's own app page in Android's settings (Permissions is on it), for a permission Android won't ask
     * for any more. Only when a parent asks from their phone. False if the TV has no such page.
     */
    fun openAppPage(c: Context): Boolean = try {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) { false }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("muninn-tv", Context.MODE_PRIVATE)
}

/** The PackageInstaller session's answer. */
class TvInstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TvUpdate.onResult(context, intent)
}
