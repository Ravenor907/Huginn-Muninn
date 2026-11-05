package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Ticks
import org.junit.Assert.assertEquals
import org.junit.Test

class TicksTest {
    @Test
    fun privateTicksSayWhoseTheyWaitFor() {
        assertEquals("Sending", Ticks.private("sending", "Anna"))
        // A relay took it, but her phone hasn't said it has it yet.
        assertEquals("Waiting for Anna's phone", Ticks.private("sent", "Anna"))
        assertEquals("Waiting for their phone", Ticks.private("sent", null))
        assertEquals("Waiting for their phone", Ticks.private("sent", " "))
        assertEquals("Delivered", Ticks.private("delivered", "Anna"))
        assertEquals("Read", Ticks.private("read", "Anna"))
        assertEquals("", Ticks.private("", "Anna"))
    }

    @Test
    fun groupTicksArePerMember() {
        val names = mapOf("a" to "Anna", "b" to "Bo", "c" to "Cy", "d" to "Dan")
        val n: (String) -> String = { names[it] ?: "Someone" }
        assertEquals("Sent", Ticks.group(emptySet(), emptySet(), n))
        assertEquals("Delivered", Ticks.group(setOf("a", "b"), setOf("a", "b"), n))
        assertEquals("Waiting for Bo's phone", Ticks.group(setOf("a", "b"), setOf("a"), n))
        assertEquals("Waiting for Anna's and Bo's phones", Ticks.group(setOf("a", "b"), emptySet(), n))
        assertEquals("Waiting for 3 phones", Ticks.group(setOf("a", "b", "c"), emptySet(), n))
        assertEquals("Delivered to 2 of 4", Ticks.group(setOf("a", "b", "c", "d"), setOf("a", "b"), n))
        assertEquals(listOf("Delivered to Anna, Bo", "Waiting for the phones of Cy, Dan"),
            Ticks.groupLines(setOf("a", "b", "c", "d"), setOf("b", "a"), n))
        assertEquals(listOf("Waiting for Cy's phone"), Ticks.groupLines(setOf("c"), emptySet(), n))
        assertEquals(listOf("Delivered to Anna"), Ticks.groupLines(setOf("a"), setOf("a"), n))
    }
}
