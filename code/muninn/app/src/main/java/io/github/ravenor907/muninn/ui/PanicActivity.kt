package io.github.ravenor907.muninn.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** The "Lock now" launcher shortcut: locks Muninn and closes without showing anything. */
class PanicActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = Panic.lock(this)
        // A Kids' Device has no Settings to point at.
        val kids = io.github.ravenor907.muninn.tv.KidMode.saved(this)
        // No lock set: nothing to lock with, so offer to set one (Settings › Set a lock), not a lock screen with no password.
        val noLock = !ok && !kids && Panic.needsLock(this)
        Toast.makeText(applicationContext, when {
            ok -> "Muninn locked."
            kids -> "Ask a parent."
            noLock -> "No lock set yet. Set one to use Lock now."
            else -> "Panic lock is off. Turn it on in Muninn's Settings."
        }, Toast.LENGTH_SHORT).show()
        if (noLock) try {
            startActivity(android.content.Intent(this, io.github.ravenor907.muninn.MainActivity::class.java)
                .putExtra(Panic.SET_LOCK, true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {}
        finish()
    }
}
