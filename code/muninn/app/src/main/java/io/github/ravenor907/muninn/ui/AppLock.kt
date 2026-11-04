package io.github.ravenor907.muninn.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When Muninn's screen must ask for the lock again. Kept for the whole process, not per screen: the
 * message service keeps Muninn running after its window is closed, so a window opened hours later is
 * a new MainActivity that must still know how long Muninn was away. Times are SystemClock.elapsedRealtime
 * (counts while the phone sleeps, can't be moved by changing the clock).
 *
 * Whether the lock is up ([locked]) lives here too, for the whole process: a new window (dark mode, font size,
 * a keyboard, DeX, a swipe from Recents, Back) finds it still up. Only a right lock takes it down ([opened]).
 */
object AppLock {
    /** Lock again after this long in the background unless the person picks another time. Never while in use. */
    const val DEFAULT_AFTER = 15 * 60_000L

    /** The times Settings offers, in ms. */
    val AFTERS = listOf(0L, 60_000L, 5 * 60_000L, DEFAULT_AFTER, 30 * 60_000L)

    /** When the last Muninn screen went to the background. 0 while one is on screen, unlocked. */
    @Volatile var awaySince = 0L

    private val _locked = MutableStateFlow(false)
    /** True while the lock is up: nothing but the lock screen is drawn, and nothing gets past it (notification Reply included). */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    val isLocked: Boolean get() = _locked.value

    /** Called each time the lock goes up (MainActivity sets it): every open dialog and sheet closes. */
    @Volatile var onRaise: () -> Unit = {}

    /** The lock goes up now (it came due, "Right away", Lock now, a panic lock). Only [opened] takes it down. */
    fun raise() {
        _locked.value = true
        // Nothing is read out loud on a locked Muninn (Read aloud stops at once).
        try { io.github.ravenor907.muninn.net.ReadAloud.stop() } catch (_: Throwable) {}
        try { onRaise() } catch (_: Exception) {}
    }

    /** The right pattern or password was given on the lock screen: the only way the lock comes down. */
    fun opened() {
        _locked.value = false
        awaySince = 0L
    }

    /**
     * A Muninn window comes on screen (created, recreated or started): the lock goes up if it's due. It never comes
     * down here, so a recreated window can't skip it. Returns whether the lock is up.
     */
    fun onShow(hasPassword: Boolean, now: Long, lockAfter: Long): Boolean {
        if (due(hasPassword, awaySince, now, lockAfter)) raise()
        // On screen and open: nothing to count. While locked the time away is kept until the lock is opened.
        if (!_locked.value) awaySince = 0L
        return _locked.value
    }

    /**
     * The window leaves the screen. [changing]: only a new window for a changed setting, not leaving Muninn.
     * "Right away" ([lockAfter] 0) puts the lock up now, so nothing else is drawn on the way back.
     */
    fun onHide(hasPassword: Boolean, now: Long, lockAfter: Long, changing: Boolean) {
        if (changing) return
        awaySince = now.coerceAtLeast(1L)
        if (hasPassword && lockAfter == 0L) raise()
    }

    /** A window restored from saved state (after recreation or process death) that was locked stays locked. */
    fun restore(wasLocked: Boolean, hasPassword: Boolean) {
        if (wasLocked && hasPassword) raise()
    }

    /** Test use only: back to a fresh process. */
    internal fun reset() { _locked.value = false; awaySince = 0L; onRaise = {} }

    /**
     * True when the lock must show before anything else is drawn. [awaySince] 0 means a screen was on
     * until now (a rotation or a new window while one is up), so there's nothing to lock again.
     */
    fun due(hasPassword: Boolean, awaySince: Long, now: Long, lockAfter: Long): Boolean =
        hasPassword && awaySince > 0 && now - awaySince >= lockAfter

    /**
     * Inline reply from a notification: only while Muninn is open ([ready]), not panic-locked, not a
     * Kids' TV, the lock isn't up ([locked]) and isn't due ([lockDue], see [due]): replying must never get past the lock.
     */
    fun canReply(ready: Boolean, panic: Boolean, kidsTv: Boolean, lockDue: Boolean, locked: Boolean = false): Boolean =
        ready && !panic && !kidsTv && !lockDue && !locked
}
