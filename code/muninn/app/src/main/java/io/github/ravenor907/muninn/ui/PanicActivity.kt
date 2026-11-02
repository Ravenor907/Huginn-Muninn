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
        Toast.makeText(applicationContext, when { ok -> "Muninn locked."; kids -> "Ask a parent."; else -> "Panic lock is off. Turn it on in Muninn's Settings." },
            Toast.LENGTH_SHORT).show()
        finish()
    }
}
