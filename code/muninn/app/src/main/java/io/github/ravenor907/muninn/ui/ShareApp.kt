package io.github.ravenor907.muninn.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * "Share the app": sends Muninn itself, the APK installed on this phone, through the share sheet
 * (Quick Share, Bluetooth, a cable, any app). Someone close by gets it with no internet at all.
 */
object ShareApp {
    const val MIME = "application/vnd.android.package-archive"

    /** Copies the installed APK into the cache (the share sheet can't read it where Android keeps it). */
    fun copy(context: Context): File {
        val dir = File(context.cacheDir, "app").apply { mkdirs() }
        val out = File(dir, "Muninn.apk")
        val src = File(context.applicationInfo.sourceDir)
        // Already copied from this very install: reuse it.
        if (out.length() == src.length() && out.lastModified() >= src.lastModified()) return out
        val tmp = File(dir, "Muninn.apk.part")
        src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        if (!tmp.renameTo(out)) { out.delete(); tmp.renameTo(out) }
        return out
    }

    /** What the other person does with the file, said before sharing and sent with it (where the app takes text too). */
    const val STEPS = "To install it:\n1. Open the file.\n2. If asked, allow installs from that app (Files or Quick Share).\n" +
        "3. If Play Protect warns, tap More details, then Install anyway."

    /** The install steps first (to read out or screenshot), then Share copies the app and opens the share sheet. */
    fun share(context: Context) {
        Ui.sure(Ask("Share Muninn", "Android warns about apps from outside the Play Store. Tell them:\n\n$STEPS", "Share") { send(context) })
    }

    /** Copies (off the main thread) and opens the share sheet. */
    private fun send(context: Context) {
        Ui.say("Getting Muninn ready to share…")
        Thread {
            val intent = try {
                val apk = copy(context)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
                Intent.createChooser(Intent(Intent.ACTION_SEND).setType(MIME).putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TITLE, "Muninn.apk").putExtra(Intent.EXTRA_TEXT, "Muninn, a private messenger. $STEPS").also { it.clipData = ClipData.newRawUri("Muninn.apk", uri) }
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share Muninn")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) { null }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (intent == null) Ui.say("Muninn couldn't be copied to share. Is the phone out of space?", true)
                else try { context.startActivity(intent) } catch (e: Exception) { Ui.say("Nothing on this phone can share a file.", true) }
            }
        }.start()
    }
}
