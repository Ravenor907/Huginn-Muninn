package io.github.ravenor907.muninn.net

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest

/**
 * Apps sent as files, to a TV with Muninn (or anything with LocalSend). An app on this phone goes out as
 * its APK and, for an app Android keeps in parts, one file per part: "<Name>.apk", then
 * "<Name>.split.<part>.apk". A Muninn TV installs the parts of one app together; an .apk file picked by
 * hand goes the same way as a one-part app.
 */
object AppFiles {
    /** An app on this phone that can be sent. [size]: all its parts together. */
    data class Installed(val pkg: String, val label: String, val size: Long)

    fun isApk(name: String) = name.endsWith(".apk", ignoreCase = true)

    /** Which app a received file belongs to: its name without folders, ".split.<part>" and ".apk". */
    fun appOf(name: String): String {
        val n = name.substringAfterLast('/').substringAfterLast('\\')
        val bare = if (isApk(n)) n.dropLast(4) else n
        return bare.substringBefore(".split.").ifEmpty { "App" }
    }

    /** The APKs among [items], grouped by app in the order they came, each app's main file first. */
    fun <T> group(items: List<T>, name: (T) -> String): List<Pair<String, List<T>>> =
        items.filter { isApk(name(it)) }.groupBy { appOf(name(it)) }.map { (app, parts) ->
            app to parts.sortedBy { if (name(it).substringAfterLast('/').contains(".split.")) 1 else 0 }
        }

    /** An app's name made safe for a file name. */
    fun clean(label: String): String =
        label.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), " ").replace(Regex("""\s+"""), " ").trim().take(60).ifEmpty { "App" }

    /** The hex SHA-256 of [f]. */
    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins -> val buf = ByteArray(1 shl 18); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Apps on this phone someone opens from a launcher (phone or TV), not Muninn, by name. */
    fun installed(c: Context): List<Installed> {
        val pm = c.packageManager
        val launchers = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER).flatMap { cat ->
            try { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0) } catch (e: Exception) { emptyList() }
        }
        return launchers.map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
            .filter { it.packageName != c.packageName }
            // Apps that came with the phone (unless updated) are the phone maker's and won't install elsewhere.
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }
            .map { ai ->
                val size = (listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())).sumOf { p -> try { File(p).length() } catch (e: Exception) { 0L } }
                Installed(ai.packageName, try { ai.loadLabel(pm).toString() } catch (e: Exception) { ai.packageName }, size)
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Copies [pkg]'s APK and its parts into Muninn's outgoing folder, named as above. Empty if it can't be read. */
    fun copyOut(c: Context, pkg: String): List<File> = try {
        val pm = c.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        val name = clean(ai.loadLabel(pm).toString())
        val dir = File(File(c.cacheDir, "outgoing"), "app-${System.currentTimeMillis()}").also { it.mkdirs() }
        val parts = listOf(ai.sourceDir to "$name.apk") + (ai.splitSourceDirs?.map { p ->
            p to "$name.split.${File(p).name.removeSuffix(".apk")}.apk"
        } ?: emptyList())
        parts.map { (from, to) -> File(dir, to).also { out -> File(from).inputStream().use { ins -> out.outputStream().use { ins.copyTo(it) } } } }
    } catch (e: Exception) { emptyList() }
}
