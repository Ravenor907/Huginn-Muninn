package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.screens.SheetAction
import io.github.ravenor907.muninn.screens.SheetLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/** The long-press sheet: main rows, folders, red ones last, and nothing dropped. */
class MessageSheetTest {
    @Test
    fun foldersAndDangerAtTheBottom() {
        val all = listOf(
            SheetAction("Reply", "reply"), SheetAction("Copy", "copy"),
            SheetAction("Edit", "edit", folder = "Message"), SheetAction("Sent 9:00", "clock", folder = "Message", info = true),
            SheetAction("Unsend", "undo", danger = true), SheetAction("Delete for me", "delete", danger = true),
        )
        val p = SheetLayout.plan(all)
        assertEquals(listOf("Reply", "Copy"), p.primary.map { it.label })
        assertEquals(listOf("Message" to listOf("Edit", "Sent 9:00")), p.folders.map { (n, l) -> n to l.map { it.label } })
        assertEquals(listOf("Unsend", "Delete for me"), p.danger.map { it.label })
        assertEquals(all.size, p.primary.size + p.folders.sumOf { it.second.size } + p.danger.size)
    }

    @Test
    fun aFolderWithOneActionIsAMainRow() {
        val p = SheetLayout.plan(listOf(SheetAction("Reply", "reply"), SheetAction("Edit", "edit", folder = "Message")))
        assertEquals(listOf("Reply", "Edit"), p.primary.map { it.label })
        assertEquals(0, p.folders.size)
        // Facts alone still get their folder.
        assertEquals(1, SheetLayout.plan(listOf(SheetAction("Sent 9:00", "clock", folder = "Message", info = true))).folders.size)
    }
}
