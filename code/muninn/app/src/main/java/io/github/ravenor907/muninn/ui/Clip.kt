package io.github.ravenor907.muninn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** Copies text marked as sensitive, so keyboards and the clipboard preview don't show or keep it. */
object Clip {
    /** [sensitive]: kept out of clipboard previews (messages, codes). Invites are made to be shared, so they aren't. */
    fun copy(context: Context, label: String, text: String, sensitive: Boolean = true) {
        val clip = ClipData.newPlainText(label, text)
        // ClipDescription.EXTRA_IS_SENSITIVE (Android 13); keyboards on earlier Android versions that know the name honour it too.
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        try { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip) } catch (_: Exception) {}
    }
}
