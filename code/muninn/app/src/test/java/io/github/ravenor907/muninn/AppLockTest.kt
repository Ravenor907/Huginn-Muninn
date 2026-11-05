package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lock comes back after Muninn was away (15 minutes unless chosen otherwise), even when its window was closed in between. */
class AppLockTest {
    @Test
    fun locksAfterTheChosenTime() {
        assertTrue(AppLock.due(true, awaySince = 1_000, now = 61_000, lockAfter = 60_000))
        assertFalse(AppLock.due(true, awaySince = 1_000, now = 30_000, lockAfter = 60_000))
        // "Right away".
        assertTrue(AppLock.due(true, awaySince = 1_000, now = 1_000, lockAfter = 0))
    }

    @Test
    fun fifteenMinutesAwayByDefault() {
        val after = AppLock.DEFAULT_AFTER
        assertEquals(15 * 60_000L, after)
        assertTrue(AppLock.AFTERS.contains(after))
        // Not sooner: 14 minutes away stays open; 15 locks.
        assertFalse(AppLock.due(true, awaySince = 1_000, now = 1_000 + 14 * 60_000L, lockAfter = after))
        assertTrue(AppLock.due(true, awaySince = 1_000, now = 1_000 + 15 * 60_000L, lockAfter = after))
        // In use (on screen): never, however long.
        assertFalse(AppLock.due(true, awaySince = 0, now = 10 * 3_600_000L, lockAfter = after))
    }

    @Test
    fun hoursLaterInANewWindow() {
        // The window went away, the process stayed (message service), a new window opens much later.
        assertTrue(AppLock.due(true, awaySince = 5_000, now = 5_000 + 6 * 3_600_000L, lockAfter = 60_000))
    }

    @Test
    fun nothingToLock() {
        assertFalse(AppLock.due(false, awaySince = 1, now = 10_000_000, lockAfter = 0))   // no password
        assertFalse(AppLock.due(true, awaySince = 0, now = 10_000_000, lockAfter = 0))    // still on screen
    }

    @Test
    fun notificationReplyNeverGetsPastTheLock() {
        assertTrue(AppLock.canReply(ready = true, panic = false, kidsTv = false, lockDue = false))
        assertFalse(AppLock.canReply(ready = false, panic = false, kidsTv = false, lockDue = false))   // vault not open
        assertFalse(AppLock.canReply(ready = true, panic = true, kidsTv = false, lockDue = false))    // panic-locked
        assertFalse(AppLock.canReply(ready = true, panic = false, kidsTv = true, lockDue = false))    // Kids' TV
        // Away longer than the lock time: the lock would show on opening, so no reply from the notification.
        val due = AppLock.due(true, awaySince = 1_000, now = 61_000, lockAfter = 60_000)
        assertFalse(AppLock.canReply(ready = true, panic = false, kidsTv = false, lockDue = due))
    }

    // ------------------------------------------------------------------ the lock's state, across new windows

    private val after = 60_000L

    @Test
    fun aNewWindowNeverSkipsTheLock() {
        AppLock.reset()
        try {
            // Away long enough: the lock goes up when the window comes back.
            AppLock.onHide(true, now = 1_000, lockAfter = after, changing = false)
            assertTrue(AppLock.onShow(true, now = 70_000, lockAfter = after))
            // Dark mode, font size, a keyboard, DeX: the window is made again. Still locked.
            AppLock.onHide(true, now = 70_100, lockAfter = after, changing = true)
            assertTrue(AppLock.onShow(true, now = 70_200, lockAfter = after))
            // Swiped away in Recents (or Back on Android 8 to 11) and opened again at once: still locked.
            AppLock.onHide(true, now = 71_000, lockAfter = after, changing = false)
            assertTrue(AppLock.onShow(true, now = 72_000, lockAfter = after))
            assertTrue(AppLock.isLocked)
            // Only the right lock takes it down.
            AppLock.opened()
            assertFalse(AppLock.isLocked)
            assertFalse(AppLock.onShow(true, now = 73_000, lockAfter = after))
            assertEquals(0L, AppLock.awaySince)
        } finally { AppLock.reset() }
    }

    @Test
    fun lockNowAndRightAwayStayUp() {
        AppLock.reset()
        try {
            // Lock now, then the phone is handed over and Muninn swiped away and reopened within the lock time.
            AppLock.raise()
            AppLock.onHide(true, now = 10_000, lockAfter = AppLock.DEFAULT_AFTER, changing = false)
            assertTrue(AppLock.onShow(true, now = 11_000, lockAfter = AppLock.DEFAULT_AFTER))
            AppLock.opened()
            // "Right away": leaving puts it up at once.
            AppLock.onHide(true, now = 20_000, lockAfter = 0L, changing = false)
            assertTrue(AppLock.isLocked)
            AppLock.opened()
            // A new window for a changed setting isn't leaving.
            AppLock.onHide(true, now = 30_000, lockAfter = 0L, changing = true)
            assertFalse(AppLock.isLocked)
        } finally { AppLock.reset() }
    }

    @Test
    fun noLockSetNothingGoesUp() {
        AppLock.reset()
        try {
            AppLock.onHide(false, now = 1_000, lockAfter = 0L, changing = false)
            assertFalse(AppLock.onShow(false, now = 10_000_000, lockAfter = 0L))
            assertFalse(AppLock.isLocked)
        } finally { AppLock.reset() }
    }

    @Test
    fun everyDialogClosesWhenTheLockGoesUp() {
        AppLock.reset()
        try {
            var closed = 0
            AppLock.onRaise = { closed++ }
            AppLock.raise()
            AppLock.onHide(true, now = 1_000, lockAfter = 0L, changing = false)
            assertEquals(2, closed)
            // A broken closer never stops the lock.
            AppLock.onRaise = { throw IllegalStateException("closer") }
            AppLock.opened()
            AppLock.raise()
            assertTrue(AppLock.isLocked)
        } finally { AppLock.reset() }
    }

    @Test
    fun replyWaitsWhileTheLockIsUp() {
        // Opened (so not "due") but the lock is showing: Reply must open the lock first.
        assertFalse(AppLock.canReply(ready = true, panic = false, kidsTv = false, lockDue = false, locked = true))
        assertTrue(AppLock.canReply(ready = true, panic = false, kidsTv = false, lockDue = false, locked = false))
    }
}
