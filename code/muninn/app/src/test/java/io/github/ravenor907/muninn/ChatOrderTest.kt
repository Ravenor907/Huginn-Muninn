package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat list's order (pinned, then unread newest first, then read) and the "New from" bar's words. */
class ChatOrderTest {
    private val times = mapOf("mom" to 50L, "taylor" to 40L, "sam" to 30L, "pin" to 10L, "old" to 5L, "work" to 60L)
    private fun last(k: String) = times[k] ?: 0L

    @Test
    fun unreadUnderPinnedNewestFirst() {
        // As arranged: pinned first, then by time or by hand ("old" was moved up by hand).
        val shown = listOf("pin", "old", "work", "mom", "taylor", "sam")
        val order = ChatOrder.unreadUp(shown, listOf("pin"), setOf("taylor", "mom", "old"), ::last)
        assertEquals(listOf("pin", "mom", "taylor", "old", "work", "sam"), order)
    }

    @Test
    fun pinnedStayFirstEvenWhenUnread() {
        val order = ChatOrder.unreadUp(listOf("pin", "work", "mom"), listOf("pin"), setOf("pin", "mom"), ::last)
        assertEquals(listOf("pin", "mom", "work"), order)
    }

    @Test
    fun nothingUnreadKeepsTheOrder() {
        val shown = listOf("pin", "old", "work", "mom")
        assertEquals(shown, ChatOrder.unreadUp(shown, listOf("pin"), emptySet(), ::last))
        assertEquals(emptyList<String>(), ChatOrder.unreadUp(emptyList(), emptyList(), setOf("mom"), ::last))
    }

    @Test
    fun newFromWords() {
        assertEquals("New from Mom, Taylor +3", ChatOrder.newFrom(listOf("Mom", "Taylor", "Sam", "Alex", "Jo"), true))
        assertEquals("New from Mom, Taylor", ChatOrder.newFrom(listOf("Mom", "Taylor"), true))
        assertEquals("New from Mom", ChatOrder.newFrom(listOf("Mom"), true))
        assertEquals("", ChatOrder.newFrom(emptyList(), true))
        assertEquals("New messages", ChatOrder.newFrom(listOf("Mom", "Taylor", "Sam"), false))
    }

    @Test
    fun nothingChosenHidesNamesOnlyOnTheLockScreen() {
        // "Nothing" chosen for notifications: no names over the lock screen, names inside the unlocked app.
        assertEquals("New messages", ChatOrder.newFrom(listOf("Mom", "Taylor"), ChatOrder.namesShown(overLockScreen = true, notifyNames = false)))
        assertEquals("New from Mom, Taylor", ChatOrder.newFrom(listOf("Mom", "Taylor"), ChatOrder.namesShown(overLockScreen = false, notifyNames = false)))
        // Name only or Name and message: names everywhere.
        assertTrue(ChatOrder.namesShown(overLockScreen = true, notifyNames = true))
    }

    @Test
    fun closedBarComesBackWithTheNextMessage() {
        assertTrue(ChatOrder.barShown(100L, 0L))
        assertFalse(ChatOrder.barShown(100L, 100L))   // X pressed after the newest one
        assertTrue(ChatOrder.barShown(150L, 100L))    // a new message since
        assertFalse(ChatOrder.barShown(0L, 0L))       // nothing unread
    }
}
