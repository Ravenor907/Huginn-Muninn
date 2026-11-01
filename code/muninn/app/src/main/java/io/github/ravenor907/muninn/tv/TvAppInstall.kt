package io.github.ravenor907.muninn.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.AppFiles
import io.github.ravenor907.muninn.ui.Ui
import java.io.File
import java.security.MessageDigest

/**
 * A Kids' TV's side of parent-approved installs (cap k9, protocol.md 7.15). An app a parent sent is saved in the
 * TV's Downloads; the TV asks every parent's phone, and installs it only when a parent's Install names exactly that
 * file's SHA-256. Nothing installs without that, and the kids never see Android's installer unless Android itself
 * asks to confirm (then the TV says "Ask a parent" beside it).
 */
object TvAppInstall {
    /** One received file: its name, its place in Downloads (content URI), its SHA-256 and size. */
    data class Got(val name: String, val uri: String, val sha: String, val size: Long)

    private const val ACTION = "io.github.ravenor907.muninn.TV_APP_INSTALLED"
    private const val EXTRA_H = "h"
    private const val KEY = "appAsks"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("muninn-tv", Context.MODE_PRIVATE)

    /** The apps waiting for a parent's answer on this TV. */
    fun asks(c: Context): List<TvInstalls.Ask> = TvInstalls.tidy(TvInstalls.fromJson(prefs(c).getString(KEY, null)), System.currentTimeMillis())

    private fun save(c: Context, l: List<TvInstalls.Ask>) = prefs(c).edit().putString(KEY, TvInstalls.toJson(l)).apply()

    /** Apps a parent sent arrived (all their parts, saved in Downloads): every parent's phone is asked. */
    fun arrived(app: Muninn, files: List<Got>) {
        Thread {
            try {
                val now = System.currentTimeMillis()
                val fresh = AppFiles.group(files) { it.name }.map { (name, parts) ->
                    // The main file first (AppFiles.group puts it there); its SHA-256 names the app.
                    val main = parts.first()
                    val (pkg, ver) = archiveInfo(app, main.uri)
                    TvInstalls.Ask(main.sha, main.name.substringAfterLast('/').ifEmpty { "$name.apk" }, parts.sumOf { it.size }, pkg, ver, parts.map { it.uri to it.sha }, now)
                }
                if (fresh.isEmpty()) return@Thread
                save(app, TvInstalls.tidy(asks(app).filter { a -> fresh.none { it.h == a.h } } + fresh, now))
                fresh.forEach { tellParents(app, TvInstalls.askMessage(it)) }
            } catch (_: Exception) {}
        }.start()
    }

    /** A parent's answer (KidTv checked it's room tvconfig from a current parent). */
    fun answer(app: Muninn, from: String, h: String, yes: Boolean, parents: List<String>) {
        val now = System.currentTimeMillis()
        val a = TvInstalls.approved(asks(app), h, from, parents, now) ?: return
        if (!yes) { save(app, asks(app).filter { it.h != h }); return }
        if (!TvUpdate.canInstall(app)) {
            Ui.say(TvWords.ASK_PARENT, true)
            tell(app, from, TvInstalls.resultMessage(h, "perm"))
            return
        }
        install(app, a, from)
    }

    private fun install(app: Muninn, a: TvInstalls.Ask, from: String) {
        Thread {
            var id = -1
            val pi = app.packageManager.packageInstaller
            try {
                id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { s ->
                    a.parts.forEachIndexed { i, (uri, sha) ->
                        // Exactly the file the parent approved: its SHA-256 is checked again as it goes in.
                        val md = MessageDigest.getInstance("SHA-256")
                        app.contentResolver.openInputStream(Uri.parse(uri))!!.use { ins ->
                            s.openWrite("part$i.apk", 0, -1).use { os ->
                                val buf = ByteArray(1 shl 18)
                                while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n); os.write(buf, 0, n) }
                                s.fsync(os)
                            }
                        }
                        if (md.digest().joinToString("") { "%02x".format(it) } != sha) throw SecurityException("The file in Downloads changed")
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val done = PendingIntent.getBroadcast(app, id, Intent(app, TvAppInstallResult::class.java).setAction(ACTION)
                        .setPackage(app.packageName).putExtra(EXTRA_H, a.h), flags)
                    s.commit(done.intentSender)
                }
            } catch (e: Exception) {
                if (id >= 0) try { pi.abandonSession(id) } catch (_: Exception) {}
                val why = when (e) { is SecurityException -> e.message ?: "The file changed"; is java.io.FileNotFoundException, is NullPointerException -> "It's not in Downloads any more"; else -> "It couldn't be read" }
                tell(app, from, TvInstalls.resultMessage(a.h, "failed", why))
            }
        }.start()
    }

    fun onResult(c: Context, i: Intent) {
        val app = c.applicationContext as? Muninn ?: return
        val h = i.getStringExtra(EXTRA_H) ?: return
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else i.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                // Android itself wants Install pressed on the TV: its box, with "Ask a parent" beside it.
                Ui.say(TvWords.ASK_PARENT)
                try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                tellParents(app, TvInstalls.resultMessage(h, "confirm"))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                save(app, asks(app).filter { it.h != h })
                tellParents(app, TvInstalls.resultMessage(h, "installed"))
            }
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> tellParents(app, TvInstalls.resultMessage(h, "failed", "It doesn't run on this TV"))
            else -> tellParents(app, TvInstalls.resultMessage(h, "failed",
                i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(80) ?: "Android didn't take it"))
        }
    }

    /** The package name and version inside an APK, when Android can read them (a copy in Muninn's cache, then gone). */
    private fun archiveInfo(c: Context, uri: String): Pair<String, String> {
        val tmp = File(c.cacheDir, "app-info-${System.nanoTime()}.apk")
        return try {
            c.contentResolver.openInputStream(Uri.parse(uri))?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            @Suppress("DEPRECATION") val info = c.packageManager.getPackageArchiveInfo(tmp.absolutePath, 0)
            (info?.packageName ?: "") to (info?.versionName ?: "")
        } catch (_: Exception) { "" to "" } finally { tmp.delete() }
    }

    private fun tellParents(app: Muninn, msg: String) {
        val parents = KidTv.state.value.parents
        for (k in parents) if (TvInstalls.CAP in (app.contacts.get(k)?.caps ?: emptySet())) tell(app, k, msg)
    }

    private fun tell(app: Muninn, to: String, msg: String) { try { Tv.bridge(app)?.sendTvRoom(to, msg) } catch (_: Exception) {} }
}

/** The PackageInstaller session's answer for an app a parent approved. */
class TvAppInstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TvAppInstall.onResult(context, intent)
}
