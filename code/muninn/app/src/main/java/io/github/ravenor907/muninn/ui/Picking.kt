package io.github.ravenor907.muninn.ui

import androidx.activity.result.ActivityResultLauncher

/**
 * A photo, file or folder picker Muninn opened itself. While one is open Muninn's window stops, and with the
 * lock set to "Right away" that put the lock up, threw the picked result away and sent Restore round in a loop.
 * A picker opened from inside Muninn gets [GRACE] before the lock is due; anything longer locks as usual.
 */
object Picking {
    const val GRACE = 5 * 60_000L

    /** When a picker was opened (SystemClock.elapsedRealtime), 0 for none. Cleared once Muninn is back (onResume). */
    @Volatile var since = 0L

    /** Opens [launcher] as a picker Muninn asked for. */
    fun <I> launch(launcher: ActivityResultLauncher<I>, input: I) {
        since = android.os.SystemClock.elapsedRealtime().coerceAtLeast(1L)
        // No picker on this device (or it wouldn't open): no grace is left behind for the next trip away.
        try { launcher.launch(input) } catch (e: Exception) { since = 0L; throw e }
    }

    /** The lock time to use for an absence that began at [awaySince]: at least [GRACE] when a picker opened just before. */
    fun lockAfter(lockAfter: Long, since: Long, awaySince: Long): Long =
        if (since > 0 && awaySince >= since && awaySince - since < GRACE) maxOf(lockAfter, GRACE) else lockAfter
}
