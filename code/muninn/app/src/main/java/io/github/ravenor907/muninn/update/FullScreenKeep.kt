package io.github.ravenor907.muninn.update

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File

/**
 * The rules for keeping "Full-screen calls" (Android's USE_FULL_SCREEN_INTENT switch) on through an update. Plain Kotlin,
 * tested on the computer (FullScreenKeepTest).
 *
 * Why it turned off: Android 14 and newer's own app installer (the screen that opens when an APK file is opened) asks
 * for full-screen to be DENIED on every install it does, updates included, for any app not from an app store. An
 * installer that doesn't ask for that leaves the switch as it was. So Muninn installs its own updates with a
 * PackageInstaller session that asks for it to stay as it is ([sessionGrants]), and says so once when it was lost anyway.
 */
object FsiRules {
    /** Muninn's own installer (a PackageInstaller session that keeps the switch). */
    const val HOW_SESSION = "session"
    /** Android's installer, opened with the APK by Muninn (older Android, or the session couldn't start). */
    const val HOW_VIEW = "view"
    /** Anything else: the APK opened by hand from a file manager or browser, adb, another store. */
    const val HOW_OTHER = "other"
    /** Lines kept in the local diagnostic. */
    const val KEEP = 10

    /** The session asks Android to grant full-screen only when it's on now: off stays off (the person's choice). */
    fun sessionGrants(allowedNow: Boolean?): Boolean = allowedNow == true

    /**
     * Whether Muninn was updated since it last looked: by the version it saw last, or, the first time (nothing seen yet,
     * the update that brings this check), by Android's install and update times.
     */
    fun updated(seenVersion: String?, current: String, firstInstall: Long, lastUpdate: Long): Boolean =
        if (seenVersion != null) seenVersion != current else lastUpdate > firstInstall

    /** How the update to [current] went in: what Muninn noted when it started the install ("version|how"), else [HOW_OTHER]. */
    fun how(note: String?, current: String): String {
        val parts = note?.split('|') ?: return HOW_OTHER
        return if (parts.size == 2 && parts[0] == current && parts[1].isNotEmpty()) parts[1] else HOW_OTHER
    }

    fun note(version: String, how: String) = "$version|$how"

    /**
     * The one card after an update: Android 14 and newer, not a Kids' Device, just updated, full-screen off now, not
     * known to have been off before (then it was the person's choice), and not shown for this version yet.
     */
    fun showCard(sdk: Int, kid: Boolean, updated: Boolean, before: Boolean?, after: Boolean?, shownFor: String?, current: String): Boolean =
        sdk >= 34 && !kid && updated && after == false && before != false && shownFor != current

    /** One diagnostic line: time, versions, how it was installed, before and after ("on", "off" or "?"). */
    fun line(at: Long, from: String?, to: String, how: String, before: Boolean?, after: Boolean?): String =
        listOf(at.toString(), clean(from ?: "?"), clean(to), clean(how), word(before), word(after)).joinToString("\t")

    class Entry(val at: Long, val from: String, val to: String, val how: String, val before: Boolean?, val after: Boolean?)

    fun parse(line: String): Entry? {
        val p = line.split('\t')
        if (p.size != 6) return null
        val at = p[0].toLongOrNull() ?: return null
        return Entry(at, p[1], p[2], p[3], state(p[4]), state(p[5]))
    }

    /** The log with [line] added, newest last, at most [KEEP] lines. */
    fun add(log: String?, line: String): String =
        ((log?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()) + line).takeLast(KEEP).joinToString("\n")

    /** The newest entry, said in a few words for Settings › Notifications. */
    fun describe(e: Entry): String {
        val by = when (e.how) { HOW_SESSION -> "Muninn's installer"; HOW_VIEW -> "Android's installer"; else -> "an APK opened by hand" }
        val what = when {
            e.before == true && e.after == false -> "turned full-screen calls off"
            e.before == true && e.after == true -> "kept full-screen calls on"
            e.after == true -> "full-screen calls on"
            e.after == false -> "full-screen calls off"
            else -> "full-screen calls unknown"
        }
        return "Last update (${e.from} to ${e.to}, $by): $what."
    }

    private fun word(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "?" }
    private fun state(s: String): Boolean? = when (s) { "on" -> true; "off" -> false; else -> null }
    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('|', ' ').take(40)
}

/**
 * The Android side of [FsiRules]: the session install, the before and after record (kept on this phone only, in
 * Muninn's own settings file; never sent anywhere), and the one card after an update.
 */
object FullScreenKeep {
    private const val SEEN_VER = "fsiSeenVer"
    private const val SEEN_ON = "fsiSeenOn"      // 1 on, 0 off; missing: unknown
    private const val HOW = "fsiHow"             // "version|how" of the install Muninn started
    private const val CARD_FOR = "fsiCardFor"    // the version the card is waiting for
    private const val SHOWN_FOR = "fsiShownFor"  // the version the card was answered for
    private const val LOG = "fsiLog"

    private fun prefs(c: Context) = c.getSharedPreferences("muninn", Context.MODE_PRIVATE)

    /** Android 14 and newer: whether Muninn may fill the screen for a call. Null on older Android (always allowed there). */
    fun allowed(c: Context): Boolean? = if (Build.VERSION.SDK_INT < 34) null else
        try { c.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() } catch (_: Exception) { null }

    private fun version(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun times(c: Context): Pair<Long, Long> = try {
        val p = c.packageManager.getPackageInfo(c.packageName, 0); p.firstInstallTime to p.lastUpdateTime
    } catch (_: Exception) { 0L to 0L }

    /** Right before Muninn starts installing [version]: how, and the switch as it is now. */
    fun beforeInstall(c: Context, version: String, how: String) {
        val e = prefs(c).edit().putString(HOW, FsiRules.note(version, how))
        allowed(c)?.let { e.putInt(SEEN_ON, if (it) 1 else 0) }
        // Round 19: written now (commit, not apply): the install ends this process right away.
        e.commit()
    }

    /**
     * Looks once more (on the chat list, every time it shows): after an update, writes the before and after line and
     * maybe asks for the card; otherwise just remembers the switch as it is now, as the next update's "before".
     */
    @Synchronized fun look(c: Context, kid: Boolean) {
        val p = prefs(c)
        val now = version(c)
        val after = allowed(c)
        val seen = p.getString(SEEN_VER, null)
        val (first, last) = times(c)
        val e = p.edit()
        if (FsiRules.updated(seen, now, first, last)) {
            val before = if (seen != null && p.contains(SEEN_ON)) p.getInt(SEEN_ON, 0) == 1 else null
            val how = FsiRules.how(p.getString(HOW, null), now)
            e.putString(LOG, FsiRules.add(p.getString(LOG, null), FsiRules.line(System.currentTimeMillis(), seen, now, how, before, after)))
            if (FsiRules.showCard(Build.VERSION.SDK_INT, kid, true, before, after, p.getString(SHOWN_FOR, null), now)) e.putString(CARD_FOR, now)
            e.remove(HOW)
        }
        e.putString(SEEN_VER, now)
        if (after != null) e.putInt(SEEN_ON, if (after) 1 else 0) else e.remove(SEEN_ON)
        e.apply()
    }

    /** True while the card should show: asked for this version, not answered, and full-screen still off. */
    fun card(c: Context): Boolean {
        val p = prefs(c)
        val now = version(c)
        return p.getString(CARD_FOR, null) == now && p.getString(SHOWN_FOR, null) != now && allowed(c) == false
    }

    /** The card was answered (tapped or closed), or the switch is back on: never again for this version. */
    fun cardDone(c: Context) {
        prefs(c).edit().putString(SHOWN_FOR, version(c)).remove(CARD_FOR).apply()
    }

    /** The newest diagnostic line in words, or null before the first update with this check. */
    fun last(c: Context): String? =
        prefs(c).getString(LOG, null)?.split('\n')?.lastOrNull { it.isNotBlank() }?.let { FsiRules.parse(it) }?.let { FsiRules.describe(it) }

    /**
     * Installs [apk] ([version]) with Muninn's own PackageInstaller session, which keeps full-screen as it is. Android
     * still asks the person to tap Install ([PhoneInstallResult]). [failed] runs on a background thread when the session
     * couldn't start: then the caller opens Android's installer the old way.
     */
    @RequiresApi(34)
    fun sessionInstall(c: Context, apk: File, version: String, failed: () -> Unit) {
        Thread {
            var id = -1
            val pi = c.packageManager.packageInstaller
            try {
                val on = allowed(c)
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(c.packageName)
                    setInstallReason(PackageManager.INSTALL_REASON_USER)
                    setSize(apk.length())
                    // Round 19: Android still asks before installing, also once Muninn is the installer of record (the
                    // manifest's UPDATE_PACKAGES_WITHOUT_USER_ACTION would otherwise let it install and close with no question).
                    if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    // Android's own installer asks for DENIED here on every install; Muninn asks for it to stay on.
                    if (FsiRules.sessionGrants(on))
                        setPermissionState(Manifest.permission.USE_FULL_SCREEN_INTENT, PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED)
                }
                id = pi.createSession(params)
                beforeInstall(c, version, FsiRules.HOW_SESSION)
                pi.openSession(id).use { s ->
                    s.openWrite("Muninn.apk", 0, apk.length()).use { os -> apk.inputStream().use { it.copyTo(os) }; s.fsync(os) }
                    val done = PendingIntent.getBroadcast(c, id, Intent(c, PhoneInstallResult::class.java).setAction(ACTION).setPackage(c.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    s.commit(done.intentSender)
                }
            } catch (e: Exception) {
                if (id >= 0) try { pi.abandonSession(id) } catch (_: Exception) {}
                failed()
            }
        }.start()
    }

    const val ACTION = "io.github.ravenor907.muninn.PHONE_INSTALL_RESULT"
}

/** Android's answer to Muninn's own update session (phones and tablets): opens Android's Install question. */
class PhoneInstallResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != FullScreenKeep.ACTION) return
        val updater = try { (c.applicationContext as? io.github.ravenor907.muninn.Muninn)?.updater } catch (_: Exception) { null }
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val ask: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else i.getParcelableExtra(Intent.EXTRA_INTENT)
                val front = try {
                    ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance <=
                        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                } catch (_: Exception) { true }
                val opened = ask != null && front && try { c.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
                if (!opened) updater?.say("Muninn is downloaded. Tap Update now to install it.")
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater?.say("The update wasn't installed. Tap Update now to try again.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> updater?.say("Not enough space on this device for the update.")
            else -> updater?.say("The update couldn't be installed. Tap Update now to try again.")
        }
    }
}
