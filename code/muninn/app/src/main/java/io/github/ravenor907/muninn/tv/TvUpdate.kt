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
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Muninn keeping itself up to date on a Kids' TV: it looks for a new release a minute after it starts and once a
 * day (and when a parent presses Update now on their phone), downloads it with the usual [Updater] (the same SHA-256
 * and signature checks), and installs it with a PackageInstaller session that asks for no tap. Android asks once on
 * the TV (the first update, while another app is Muninn's installer of record): the TV then shows "Ask a grown-up:
 * press OK to update". After Muninn has installed itself once, Android 12 and newer let later updates go in without
 * asking. Never during a call. A failed try comes back after 5 min, 15 min, 1 h, 4 h, then every 12 h.
 * The parents' phones hear how it goes (cap u1, [TvUpd]): on start, every day, and at each step.
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
    /** u1: what the parents' phones were last told (or would be). */
    val remote = MutableStateFlow<TvUpd.St?>(null)
    @Volatile private var fails = 0
    @Volatile private var retry: java.util.concurrent.ScheduledFuture<*>? = null
    /** A downloaded update held back while a call is on. */
    @Volatile private var afterCall: File? = null
    private const val ACTION = "io.github.ravenor907.muninn.tv.INSTALLED"
    private const val DAY = 24 * 60 * 60_000L
    private const val HOUR = 60 * 60_000L

    @Volatile private var daily: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var follow: kotlinx.coroutines.Job? = null

    /** Kids' mode only, once: hooks the installer, checks a minute after start, then every day. */
    fun start(app: Muninn) {
        if (started) return
        started = true
        val p0 = prefs(app)
        val version = try { app.updater.current } catch (_: Exception) { "" }
        // Install sessions left from an earlier run (a reboot or a kill mid-write, or a failed try) take room: gone now.
        abandonOld(app)
        // u1: the last state, as the TV last said it; a newer Muninn running now means the update went in. "Installing" or
        // "Needs a tap" from before Muninn restarted is over: an install that went in shows as a newer version below, and
        // one that didn't is tried again by the next check.
        remote.value = TvUpd.St(version, TvUpd.onStart(p0.getString("uState", null)), why = p0.getString("uWhy", null), at = p0.getLong("lastCheck", 0L))
        // Muninn came back up as a newer version: the update went in, and the parents' phones hear so.
        try {
            val before = p0.getString("runs", "") ?: ""
            if (before != version) {
                p0.edit().putString("runs", version).apply()
                if (before.isNotEmpty()) { post(app, State(status = "Muninn is up to date", code = "done")); setRemote(app, TvUpd.CURRENT) }
            }
        } catch (_: Exception) {}
        app.updater.installer = { apk -> install(app, apk); true }
        // The Updater's steps (checking, downloading, done, failed) go to the parents' phones (u1).
        follow = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob()).launch {
            app.updater.state.collect { st -> try { fromUpdater(app, st) } catch (_: Exception) {} }
        }
        // A minute after start, then once a day, and only while it's plugged in (a TV always is; a tablet in kids' mode
        // waits for its charger). The parents' phones hear the TV's version on start and every day.
        var first = true
        daily = timer.scheduleWithFixedDelay({
            try {
                val now = System.currentTimeMillis()
                val p = prefs(app)
                // k7 (7.12): a parent who turned automatic checks off turns them off here too; otherwise they stay on.
                val auto = KidTv.state.value.cfg?.upd ?: true
                if (auto && (first || now - p.getLong("lastDailyCheck", 0L) >= DAY) && app.power.charging()) {
                    p.edit().putLong("lastDailyCheck", now).apply()
                    checkNow(app)
                }
                if (first || now - p.getLong("lastReport", 0L) >= DAY) {
                    p.edit().putLong("lastReport", now).apply()
                    report(app, null)
                }
                first = false
            } catch (_: Exception) {}
        }, 60_000, HOUR, TimeUnit.MILLISECONDS)
    }

    /**
     * The device left kids' mode: no more silent checks or installs, and the normal Updater installs again (Android's own
     * Install box, and Settings' "Check for updates automatically"). What it saved for the parents goes too.
     */
    fun stop(app: Muninn) {
        daily?.cancel(false); daily = null
        retry?.cancel(false); retry = null
        follow?.cancel(); follow = null
        app.updater.installer = null
        waiting = null; afterCall = null; fails = 0
        remote.value = null
        state.value = State()
        started = false
        forget(app)
    }

    /** What this saved for the parents (the state they saw, the permissions asked for): a new family starts clean. */
    fun forget(c: Context) {
        val p = prefs(c)
        val e = p.edit()
        p.all.keys.filter { it.startsWith("asked:") || it in SAVED }.forEach { e.remove(it) }
        e.apply()
    }
    private val SAVED = setOf("uState", "uWhy", "lastCheck", "lastDailyCheck", "lastReport")

    /** Install sessions this app left open (a failed or cut-off try): abandoned, so their partial files go. */
    private fun abandonOld(c: Context) {
        try {
            val pi = c.packageManager.packageInstaller
            // Round 17: only Muninn's own update sessions that aren't going in now; an app a parent approved (k9) that is
            // still installing after Muninn restarted is left to finish.
            pi.mySessions.filter { s -> (s.appPackageName == null || s.appPackageName == c.packageName) && !s.isActive }
                .forEach { s -> try { pi.abandonSession(s.sessionId) } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    /** A parent asked (room tvconfig "update", or u1 "upnow"), the check on start, the daily check, or a try again. */
    fun checkNow(app: Muninn) { app.updater.check(installAfter = true) }

    /** u1: a parent's Update now. They hear where the TV is at once, then each step. */
    fun remoteNow(app: Muninn, parent: String) {
        report(app, listOf(parent))
        checkNow(app)
    }

    /** u1: the TV's state to [to] (every parent with u1 when null), whether or not it changed. */
    private fun report(app: Muninn, to: List<String>?) {
        val st = remote.value ?: TvUpd.St(try { app.updater.current } catch (_: Exception) { "" }, TvUpd.CURRENT, at = prefs(app).getLong("lastCheck", 0L))
        KidTv.sendUpd(app, TvUpd.stateMessage(st), to)
    }

    /** u1: a new state; the parents' phones hear it when it's worth a frame ([TvUpd.worthSending]). */
    private fun setRemote(c: Context, s: String, p: Int? = null, why: String? = null) {
        val app = c.applicationContext as? Muninn ?: return
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        // A check that ended (up to date, or failed) is the last check.
        if (s == TvUpd.CURRENT || s == TvUpd.FAILED) prefs.edit().putLong("lastCheck", now).apply()
        prefs.edit().putString("uState", s).putString("uWhy", why).apply()
        val next = TvUpd.St(try { app.updater.current } catch (_: Exception) { "" }, s, p, why, prefs.getLong("lastCheck", 0L))
        val prev = remote.value
        remote.value = next
        if (TvUpd.worthSending(prev, next)) KidTv.sendUpd(app, TvUpd.stateMessage(next), null)
        when (s) {
            TvUpd.CURRENT -> { fails = 0; retry?.cancel(false); retry = null }
            // Once per failure: the Updater may say the same line again.
            TvUpd.FAILED -> if (prev?.s != TvUpd.FAILED || prev?.why != why) tryAgain(app)
        }
    }

    /** After a failure: try again later, sooner at first (5 min, 15 min, 1 h, 4 h, then every 12 h), unless a parent turned Auto-update off. */
    private fun tryAgain(app: Muninn) {
        if (KidTv.state.value.cfg?.upd == false) return
        fails++
        retry?.cancel(false)
        retry = timer.schedule(Runnable { try { checkNow(app) } catch (_: Exception) {} }, TvUpd.retryMs(fails), TimeUnit.MILLISECONDS)
    }

    private fun fromUpdater(app: Muninn, st: io.github.ravenor907.muninn.update.Updater.State) {
        val (s, why) = TvUpd.fromUpdater(st.status, st.busy, st.progress) ?: return
        // An install on its way (or waiting for OK on the TV) isn't undone by the Updater's last line.
        val cur = remote.value?.s
        if ((cur == TvUpd.INSTALLING || cur == TvUpd.TAP) && s == TvUpd.DOWNLOADING) return
        setRemote(app, s, if (s == TvUpd.DOWNLOADING) (st.progress * 100).toInt().coerceIn(0, 100) else null, why)
    }

    /** A call is on: an update never goes in then (installing closes Muninn). */
    private fun inCall(): Boolean = io.github.ravenor907.muninn.net.call.CallManager.call.value != null ||
        // Round 17: a group call (voice room) too, or one ringing.
        io.github.ravenor907.muninn.net.call.RoomManager.busy || io.github.ravenor907.muninn.net.call.RoomManager.ring.value != null

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /**
     * Installs [apk] with a session; without the "install unknown apps" permission it waits for someone at the TV
     * (the TV's prompt opens that setting). During a call it waits for the call to end.
     */
    fun install(c: Context, apk: File) {
        if (inCall()) {
            val first = afterCall == null
            afterCall = apk
            if (first) timer.schedule(object : Runnable {
                override fun run() {
                    val held = afterCall ?: return
                    if (inCall()) { timer.schedule(this, 30, TimeUnit.SECONDS); return }
                    afterCall = null
                    if (held.exists()) install(c, held)
                }
            }, 30, TimeUnit.SECONDS)
            return
        }
        if (!canInstall(c)) { waiting = apk; post(c, State(needsParent = true, status = TvWords.ASK_PARENT, code = "needs")); setRemote(c, TvUpd.TAP); return }
        waiting = null
        // Not enough room for the install (Android copies the file, then unpacks it): said as it is, and tried again later.
        if (!TvUpd.roomFor(apk.length(), try { c.filesDir.usableSpace } catch (_: Exception) { Long.MAX_VALUE })) {
            post(c, State(status = "There isn't enough space for the update. It tries again later.", code = "failed"))
            setRemote(c, TvUpd.FAILED, why = TvUpd.NO_SPACE)
            return
        }
        Thread {
            var id = -1
            val pi = c.packageManager.packageInstaller
            try {
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
                id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("Muninn.apk", 0, apk.length()).use { os -> apk.inputStream().use { it.copyTo(os) }; s.fsync(os) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val done = PendingIntent.getBroadcast(c, id, Intent(c, TvInstallResult::class.java).setAction(ACTION).setPackage(c.packageName), flags)
                    s.commit(done.intentSender)
                }
                post(c, State(status = "Installing the new Muninn…", code = "installing"))
                setRemote(c, TvUpd.INSTALLING)
            } catch (e: Exception) {
                // The half-written session goes at once, so its partial file doesn't eat the room the next try needs.
                if (id >= 0) try { pi.abandonSession(id) } catch (_: Exception) {}
                val space = TvUpd.isNoSpace(e.message)
                post(c, State(status = if (space) "There isn't enough space for the update. It tries again later."
                    else "The update couldn't be installed. It tries again later.", code = "failed"))
                setRemote(c, TvUpd.FAILED, why = if (space) TvUpd.NO_SPACE else "Couldn't start the install")
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
                setRemote(c, TvUpd.TAP)
                // The TV screen shows "Ask a grown-up: press OK to update"; OK brings up Android's Install box.
            }
            PackageInstaller.STATUS_SUCCESS -> { post(c, State(status = "Muninn is up to date", code = "done")); setRemote(c, TvUpd.CURRENT) }
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                post(c, State(status = "There isn't enough space for the update. It tries again later.", code = "failed"))
                setRemote(c, TvUpd.FAILED, why = TvUpd.NO_SPACE)
            }
            else -> {
                post(c, State(status = "The update didn't go in. It tries again later.", code = "failed"))
                setRemote(c, TvUpd.FAILED, why = "Android didn't install it")
            }
        }
    }

    /** A new state; the parents' phones get it in the TV's status when it changed. */
    private fun post(c: Context, st: State) {
        val was = state.value.code
        state.value = st
        if (st.code != was) (c.applicationContext as? Muninn)?.let { try { KidTv.updateChanged(it) } catch (_: Exception) {} }
    }

    /**
     * The remote's OK on "Ask a grown-up: press OK to update": Android's own Install box, or, while Muninn may not
     * install apps yet, the Android setting that allows it (the update goes in once it's on, see [resume]).
     */
    fun confirm(c: Context) {
        val i = state.value.confirm
        if (i == null) { if (state.value.needsParent) open(c, "install"); return }
        state.value = state.value.copy(confirm = null)
        try { c.startActivity(i) } catch (_: Exception) {}
    }

    /** The TV's full-screen update prompt shows: a confirm or a permission is waiting, and no call is on. */
    fun prompting(st: State, inCall: Boolean): Boolean = !inCall && (st.confirm != null || st.needsParent)

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
