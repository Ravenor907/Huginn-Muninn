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
}
