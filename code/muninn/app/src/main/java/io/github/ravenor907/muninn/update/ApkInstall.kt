package io.github.ravenor907.muninn.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.github.ravenor907.muninn.net.AppFiles
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Installs apps that came to a TV as files (AppFiles): all the parts of one app in one PackageInstaller
 * session. Android always asks on the TV first (its own Install box). On a TV used normally, Muninn asks
 * before that ("Install … from …?"); on a Kids' TV a parent already chose the app on their phone (TvApps).
 * The files are deleted once Android has them, or when the person says no.
 */
object ApkInstall {
    /** An app that arrived and waits for the person's answer. */
    data class Offer(val from: String, val name: String, val files: List<File>)
    /** Apps waiting to be offered, oldest first (MainActivity asks about the first). */
    val offers = MutableStateFlow<List<Offer>>(emptyList())
    private const val ACTION = "io.github.ravenor907.muninn.APP_INSTALLED"
    private const val EXTRA_NAME = "app"

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** A transfer brought these files. [kids]: a parent sent them to a Kids' TV, so no question first. */
    fun arrived(c: Context, from: String, files: List<File>, kids: Boolean) {
        for ((name, parts) in AppFiles.group(files) { it.name }) {
            if (kids) install(c, name, parts) else offers.value = offers.value + Offer(from, name, parts)
        }
    }

    /** The person said Install to the first offer. */
    fun accept(c: Context, o: Offer) {
        if (!canInstall(c)) {
            // Stays offered: back from the setting, Install goes through.
            Ui.say("Allow Muninn to install apps, then choose Install again.", true)
            try { c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return
        }
        offers.value = offers.value - o
        install(c, o.name, o.files)
    }

    /** The person said no: the files go. */
    fun decline(o: Offer) {
        offers.value = offers.value - o
        o.files.forEach { it.delete() }
    }

    private fun install(c: Context, name: String, parts: List<File>) {
        if (!canInstall(c)) {
            parts.forEach { it.delete() }
            // Only an app a parent sent gets here (a Kids' TV): the kids read the short words; the parent's phone shows
            // Install updates as off under TV permissions.
            Ui.say(io.github.ravenor907.muninn.tv.TvWords.ASK_PARENT, true)
            return
        }
        Thread {
            try {
                val pi = c.packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    parts.forEachIndexed { i, f ->
                        s.openWrite("part$i.apk", 0, f.length()).use { os -> f.inputStream().use { it.copyTo(os) }; s.fsync(os) }
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val done = PendingIntent.getBroadcast(c, id, Intent(c, ApkInstallResult::class.java).setAction(ACTION)
                        .setPackage(c.packageName).putExtra(EXTRA_NAME, name), flags)
                    s.commit(done.intentSender)
                }
                Ui.say("Installing $name…")
            } catch (e: Exception) {
                Ui.say("$name couldn't be installed. The file may be damaged or not an app.", true)
            } finally {
                // Android keeps its own copy in the session.
                parts.forEach { it.delete() }
            }
        }.start()
    }

    fun onResult(c: Context, i: Intent) {
        val name = i.getStringExtra(EXTRA_NAME) ?: "The app"
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                // Android's own Install box. A Kids' TV may show it over a show (it may draw over other apps).
                try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> Ui.say("$name is installed.")
            PackageInstaller.STATUS_FAILURE_ABORTED -> Ui.say("$name wasn't installed.")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Ui.say("$name doesn't run on this TV. Apps made for phones don't always work on a TV.", true)
            else -> Ui.say("$name couldn't be installed${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { ": $it" } ?: "."}", true)
        }
    }
}

/** The PackageInstaller session's answer for a sent app. */
class ApkInstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = ApkInstall.onResult(context, intent)
}
