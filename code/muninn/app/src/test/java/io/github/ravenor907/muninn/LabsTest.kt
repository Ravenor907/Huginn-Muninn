package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.LabsWire
import io.github.ravenor907.muninn.update.Updater
import io.github.ravenor907.muninn.update.Versions
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Labs: pre-release ordering (shared/docs/releases.md) and the report's "lb" field (protocol.md, "Labs"). */
class LabsTest {
    @Test
    fun betasSortBetweenReleases() {
        val inOrder = listOf("0.97", "0.98", "0.99 beta 3", "0.99 beta 7", "0.99 beta 10", "0.99", "1.0 beta 1", "1.0")
        for (i in inOrder.indices) for (j in inOrder.indices)
            assertEquals("${inOrder[i]} vs ${inOrder[j]}", i.compareTo(j).coerceIn(-1, 1), Versions.compare(inOrder[i], inOrder[j]).coerceIn(-1, 1))
    }

    @Test
    fun installOnlyNewer() {
        assertTrue(Updater.mayInstall("0.99 beta 3", "0.98"))
        assertTrue(Updater.mayInstall("0.99", "0.99 beta 12"))
        assertFalse(Updater.mayInstall("0.99 beta 12", "0.99"))
        assertFalse(Updater.mayInstall("0.98", "0.99 beta 3"))
        assertFalse(Updater.mayInstall("0.99 beta 7", "0.99 beta 7"))
        assertFalse(Updater.mayInstall("nonsense", "0.98"))
        assertTrue(Updater.mayInstall("0.3", "0.3 Test 1"))
    }

    @Test
    fun labels() {
        assertTrue(Versions.isBeta("0.99 beta 12"))
        assertFalse(Versions.isBeta("0.99"))
        assertFalse(Versions.isBeta("0.99 Beta 12"))
        assertFalse(Versions.isBeta("0.99 beta"))
        assertTrue(Versions.isStable("0.98"))
        assertFalse(Versions.isStable("0.99 beta 1"))
    }

    @Test
    fun reportFieldHasOnlyItsFourParts() {
        val lb = LabsWire.info("muninn", "0.99 beta 12", "Android 15", "samsung SM-S938U")
        assertEquals(setOf("app", "v", "os", "model"), lb.keys().asSequence().toSet())
        assertEquals("muninn", lb.getString("app"))
        assertEquals("0.99 beta 12", lb.getString("v"))
        assertTrue(LabsWire.ok(lb))
        val withShot = LabsWire.info("huginn", "0.98", "Windows 11", "PC", shot = "abc-1")
        assertEquals("abc-1", withShot.getString("s"))
        assertTrue(LabsWire.ok(withShot))
    }

    @Test
    fun refusesMalformedReports() {
        assertFalse(LabsWire.ok(null))
        assertFalse(LabsWire.ok(JSONObject().put("app", "other").put("v", "1").put("os", "x").put("model", "y")))
        assertFalse(LabsWire.ok(JSONObject().put("app", "muninn").put("v", 1).put("os", "x").put("model", "y")))
        assertFalse(LabsWire.ok(JSONObject().put("app", "muninn").put("v", "1").put("os", "x")))
        assertFalse(LabsWire.ok(JSONObject().put("app", "muninn").put("v", "1").put("os", "x").put("model", "y".repeat(81))))
        assertFalse(LabsWire.ok(JSONObject().put("app", "muninn").put("v", "1").put("os", "x").put("model", "y").put("s", "")))
        assertTrue(LabsWire.textOk("It crashed"))
        assertTrue(LabsWire.textOk("a".repeat(2000)))
        assertFalse(LabsWire.textOk("a".repeat(2001)))
        assertFalse(LabsWire.textOk("   "))
    }

    @Test
    fun tenADayFromEach() {
        val now = 10L * 86_400_000L
        val nine = List(9) { now - it * 60_000L }
        assertTrue(LabsWire.allow(nine, now))
        assertFalse(LabsWire.allow(nine + (now - 1000L), now))
        // A day later they count no more.
        assertTrue(LabsWire.allow(List(10) { now - 86_400_000L - it }, now))
    }

    @Test
    fun builtInContact() {
        assertEquals("Huginn Muninn Labs", LabsWire.NAME)
        assertEquals(64, LabsWire.MAILBOX.length)
        assertTrue(LabsWire.KEY.startsWith("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE"))
    }

    @Test
    fun reportCarriesNoDeviceId() {
        val lb = LabsWire.info("muninn", "1.0", "Android 15", "Pixel 9")
        val o = LabsWire.envelope("private", "KEY", "Raven 1234", "m".repeat(64), "id1", lb)
        assertEquals(setOf("room", "fromKey", "fromName", "mailbox", "id", LabsWire.FIELD), o.keys().asSequence().toSet())
        assertFalse(o.has("fromId"))
        assertEquals(setOf("app", "v", "os", "model"), o.getJSONObject(LabsWire.FIELD).keys().asSequence().toSet())
    }

    @Test
    fun labsMayReplyFor30DaysAfterTheLastReport() {
        val day = 86_400_000L
        val sent = 1_000_000_000_000L
        assertFalse(LabsWire.replyOpen(0L, sent))
        assertTrue(LabsWire.replyOpen(sent, sent))
        assertTrue(LabsWire.replyOpen(sent, sent + 30 * day - 1))
        assertFalse(LabsWire.replyOpen(sent, sent + 30 * day))
        assertFalse(LabsWire.replyOpen(sent, sent - 1))
    }
}
