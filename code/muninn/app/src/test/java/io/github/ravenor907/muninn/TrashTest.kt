package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contacts trash: local only, kept across restarts, and emptied of anyone there 30 days. */
class TrashTest {
    private val day = 86_400_000L

    @Test
    fun keptAcrossARestart() {
        var stored: String? = null
        val t = Trash({ stored }, { stored = it })
        assertTrue(t.all.value.isEmpty())
        t.put("keyA", 1_000L)
        t.put("keyB", 2_000L)
        t.put("keyA", 5_000L)   // already in: keeps when it went in
        val again = Trash({ stored }, { stored = it })
        assertEquals(mapOf("keyA" to 1_000L, "keyB" to 2_000L), again.all.value)
        again.drop("keyA")
        assertFalse(Trash({ stored }, { stored = it }).has("keyA"))
        assertTrue(Trash({ stored }, { stored = it }).has("keyB"))
        // Something unreadable is an empty trash, not a crash.
        assertTrue(Trash({ "not json" }, {}).all.value.isEmpty())
    }

    @Test
    fun goesForGoodAfterThirtyDays() {
        val now = 100 * day
        val all = mapOf("old" to now - 30 * day, "older" to now - 45 * day, "new" to now - 29 * day, "today" to now)
        assertEquals(setOf("old", "older"), Trash.expired(all, now).toSet())
        assertTrue(Trash.expired(emptyMap(), now).isEmpty())
        assertEquals(30, Trash.daysLeft(now, now))
        assertEquals(1, Trash.daysLeft(now - 29 * day - 1, now))
        assertEquals(1, Trash.daysLeft(now - 31 * day, now))   // overdue: goes on the next check
        val t = Trash({ null }, {})
        t.put("a", now - 31 * day)
        t.put("b", now)
        assertEquals(listOf("a"), t.expired(now))
    }
}
