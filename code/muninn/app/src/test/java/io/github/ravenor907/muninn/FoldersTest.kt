package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Folders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contact folders: local only, the three to start with can go, and they're kept. */
class FoldersTest {
    @Test
    fun startAddRenameDeleteAndKeep() {
        var stored: String? = null
        val f = Folders({ stored }, { stored = it })
        assertEquals(Folders.DEFAULTS, f.all.value.map { it.name })
        val family = f.all.value.first().id
        f.put(family, "keyA", true)
        f.put(family, "g:group1", true)
        f.put(family, "keyA", true)   // once only
        assertEquals(listOf("keyA", "g:group1"), f.all.value.first().keys)
        val mine = f.add("  Raids  ")!!
        assertNull(f.add("   "))
        f.rename(mine, "Raid night")
        f.put(mine, "keyA", true)
        assertEquals(listOf("Family", "Raid night"), f.of("keyA").map { it.name })
        f.forget("keyA")
        assertTrue(f.of("keyA").isEmpty())
        // All of them can go, and they don't come back on the next start.
        f.all.value.map { it.id }.forEach { f.delete(it) }
        assertTrue(f.all.value.isEmpty())
        assertTrue(Folders({ stored }, { stored = it }).all.value.isEmpty())
        // Kept across a restart.
        val id = f.add("Raiders")!!
        f.put(id, "keyB", true)
        val again = Folders({ stored }, { stored = it })
        assertEquals(listOf("keyB"), again.all.value.single().keys)
        assertEquals("Raiders", again.all.value.single().name)
    }

    @Test
    fun sectionsFoldMoveAndKeepTheirOrder() {
        var stored: String? = null
        val f = Folders({ stored }, { stored = it })
        val family = f.all.value[0].id
        val work = f.all.value[1].id
        f.put(family, "a", true)
        f.put(family, "b", true)
        // Folded away, and still folded on the next start.
        f.collapse(family, true)
        assertTrue(Folders({ stored }, { stored = it }).all.value.first { it.id == family }.collapsed)
        f.collapse(family, false)
        assertFalse(f.all.value.first { it.id == family }.collapsed)
        // Moving takes it out of the folder it was in and puts it in the other.
        f.move("a", family, work)
        assertEquals(listOf("b"), f.all.value.first { it.id == family }.keys)
        assertEquals(listOf("a"), f.all.value.first { it.id == work }.keys)
        // To no folder: it leaves the folder and goes at the end of the top section's order.
        f.move("a", work, Folders.NONE)
        assertTrue(f.of("a").isEmpty())
        assertEquals(listOf("a"), f.top.value)
        // And from no folder into one: it leaves the top order.
        f.move("a", Folders.NONE, family)
        assertTrue(f.top.value.isEmpty())
        assertEquals(listOf("b", "a"), f.all.value.first { it.id == family }.keys)
        // Reordered by hand; what isn't shown keeps its place after.
        f.put(family, "c", true)
        f.order(family, listOf("a", "b"))
        assertEquals(listOf("a", "b", "c"), f.all.value.first { it.id == family }.keys)
        f.order(Folders.NONE, listOf("y", "x"))
        f.forget("x")
        assertEquals(listOf("y"), f.top.value)
        // All of it is kept.
        val again = Folders({ stored }, { stored = it })
        assertEquals(listOf("a", "b", "c"), again.all.value.first { it.id == family }.keys)
        assertEquals(listOf("y"), again.top.value)
    }

    @Test
    fun arrangeShiftAndPlace() {
        // Never moved by hand: newest first, at the top. Then the ones moved, in their order.
        assertEquals(listOf("new", "b", "a"), Folders.arrange(listOf("new", "a", "b"), listOf("b", "gone", "a")))
        assertEquals(listOf("a", "b"), Folders.arrange(listOf("a", "b"), emptyList()))
        assertEquals(listOf("b", "a", "c"), Folders.shift(listOf("a", "b", "c"), "b", -1))
        assertEquals(listOf("a", "c", "b"), Folders.shift(listOf("a", "b", "c"), "b", 1))
        assertEquals(listOf("a", "b", "c"), Folders.shift(listOf("a", "b", "c"), "c", 1))   // already last
        assertEquals(listOf("a", "b", "c"), Folders.shift(listOf("a", "b", "c"), "z", 1))   // not there
        assertEquals(listOf("c", "a", "b"), Folders.place(listOf("a", "b", "c"), "c", "a"))
        assertEquals(listOf("b", "c", "a"), Folders.place(listOf("a", "b", "c"), "a", null))
    }
}
