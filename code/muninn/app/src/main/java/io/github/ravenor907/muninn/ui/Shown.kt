package io.github.ravenor907.muninn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * Runs [block] now and then every [everyMs], but only while Muninn is on screen: it rests while Muninn is in the
 * background and runs again at once on the way back. Starts over when any of [keys] changes.
 */
@Composable
fun EveryWhileShown(everyMs: Long, vararg keys: Any?, block: suspend () -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner, *keys) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { block(); delay(everyMs) }
        }
    }
}
