package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.tv.KidRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Round 14, between platforms and versions: Kids' Device zones (7.18) and caps after going back to stable (1.1). */
class Round14InteropTest {
    /** A Kids' Device set up before 1.35 has no zone kept: the first one sent or run is kept, then always sent. */
    @Test
    fun kidDeviceZoneIsKept() {
        assertEquals("Europe/Oslo", KidRules.zoneToSend("", "Europe/Oslo"))
        assertEquals("America/Anchorage", KidRules.zoneToSend("America/Anchorage", "Europe/Oslo"))
        assertEquals("Europe/Oslo", KidRules.zoneToKeep("", "Europe/Oslo"))
        assertEquals("America/Anchorage", KidRules.zoneToKeep("America/Anchorage", "Europe/Oslo"))
        assertEquals("", KidRules.zoneToKeep("", null))
        assertEquals("", KidRules.zoneToKeep("", ""))
        assertEquals("", KidRules.zoneToKeep("", "Not/AZone"))
        // The parent's config keeps the zone it sent, and the device's echo fills an empty one.
        val parent = source("TvParent.kt")
        assertTrue(parent.contains("tz = KidRules.zoneToKeep(t.tz, extras?.tz)"))
        assertTrue(parent.contains("tz = KidRules.zoneToKeep(t.tz, runs?.takeIf { KidRules.hasK11(it.raw) }?.tz)"))
        assertTrue(source("ParentTvScreen.kt").contains("\"Not set: uses \""))
    }

    /** Going back to stable is noticed by the caps it no longer says; an empty receipt's caps replace the known set. */
    @Test
    fun capsShrinkAfterGoingBack() {
        assertTrue(Proto06.capsDropped("r1 g1 n2 zz9", "r1 g1 n2"))
        assertFalse(Proto06.capsDropped("r1 g1", "g1 r1 n2"))
        assertFalse(Proto06.capsDropped("", Proto06.CAPS))
        assertEquals(setOf("r1", "g1"), Proto06.replacedCaps(setOf("r1", "g1", "k15"), setOf("r1", "g1"), true))
        // No caps field at all (an app without caps), or an empty one: nothing is taken away.
        assertEquals(setOf("r1", "k15"), Proto06.replacedCaps(setOf("r1", "k15"), emptySet(), false))
        assertEquals(setOf("r1", "k15"), Proto06.replacedCaps(setOf("r1", "k15"), emptySet(), true))
        val chat = source("Chat.kt")
        assertTrue(chat.contains("if (a.length() == 0) contacts.get(fromKey)?.let { c ->"))
        assertTrue(chat.contains("if (Proto06.capsDropped(lastCaps, Proto06.CAPS)) bg {"))
    }

    private fun source(name: String): String {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        return src.walkTopDown().first { it.isFile && it.name == name }.readText()
    }
}
