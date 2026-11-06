package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.MediaRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Send media, what the call relay may carry, and the month's limit (MediaRoute; Huginn's filelink.cpp has the same rules). */
class MediaRouteTest {
    @Test
    fun aChatsOwnChoiceComesFirst() {
        assertEquals(MediaRoute.AUTO, MediaRoute.chosen(null, null))
        assertEquals(MediaRoute.TOR, MediaRoute.chosen(null, MediaRoute.TOR))
        assertEquals(MediaRoute.FASTEST, MediaRoute.chosen(MediaRoute.FASTEST, MediaRoute.TOR))
        assertEquals(MediaRoute.AUTO, MediaRoute.chosen("nonsense", "nonsense"))
    }

    @Test
    fun theRelayIsForFamilyAndTrustedOnly() {
        assertTrue(MediaRoute.relayMay(MediaRoute.AUTO, ultra = false, family = true, peerCan = true))
        assertFalse("anyone else", MediaRoute.relayMay(MediaRoute.AUTO, false, false, true))
        assertFalse("an older app", MediaRoute.relayMay(MediaRoute.AUTO, false, true, false))
        assertFalse("Ultra privacy", MediaRoute.relayMay(MediaRoute.AUTO, true, true, true))
        assertFalse("Tor chosen", MediaRoute.relayMay(MediaRoute.TOR, false, true, true))
    }

    @Test
    fun torIsNeverPickedSilentlyForFamily() {
        assertFalse(MediaRoute.torMay(MediaRoute.AUTO, false, true))
        assertFalse(MediaRoute.torMay(MediaRoute.FASTEST, false, true))
        assertTrue(MediaRoute.torMay(MediaRoute.TOR, false, true))
        assertTrue(MediaRoute.torMay(MediaRoute.AUTO, true, true))
        // Someone outside Family and trusted contacts has no other way over the internet; the card says Tor.
        assertTrue(MediaRoute.torMay(MediaRoute.AUTO, false, false))
        assertFalse(MediaRoute.directMay(MediaRoute.TOR, false))
        assertFalse(MediaRoute.directMay(MediaRoute.AUTO, true))
    }

    @Test
    fun theMonthsLimitIsWellUnderTheFreeTerabyteAndSaidCalmly() {
        assertTrue(MediaRoute.STOP_BYTES < 1000L * 1_000_000_000L && MediaRoute.LIMIT_BYTES < MediaRoute.STOP_BYTES)
        assertFalse(MediaRoute.over(MediaRoute.LIMIT_BYTES - 1, 0))
        assertTrue(MediaRoute.over(0, MediaRoute.LIMIT_BYTES))
        assertFalse(MediaRoute.stopped(0, MediaRoute.LIMIT_BYTES))
        assertTrue(MediaRoute.stopped(MediaRoute.STOP_BYTES, 0))
        assertEquals("", MediaRoute.meterNote(1000, 0))
        assertEquals("Relay this month: 12 GB of 800 GB.", MediaRoute.meterNote(12_000_000_000L, 3))
        assertTrue(MediaRoute.meterNote(0, MediaRoute.LIMIT_BYTES).contains("720p"))
        assertEquals("2026-11", MediaRoute.month(1_794_000_000_000L))
    }

    @Test
    fun aRelayMessageIsANumberNeverAName() {
        fun m(a: String, p: Int) = JSONObject().put("t", "relay").put("id", "0123456789abcdef0123456789abcdef").put("a", a).put("p", p)
        assertTrue(FileLink.relayOk(m("104.30.1.2", 40000)))
        assertTrue(FileLink.relayOk(m("2606:4700::1", 40000)))
        assertFalse(FileLink.relayOk(m("evil.example.com", 40000)))
        assertFalse(FileLink.relayOk(m("abcd.de", 40000)))
        assertFalse(FileLink.relayOk(m("104.30.1.2", 0)))
        assertFalse(FileLink.relayOk(m("104.30.1.2", 70000)))
    }

    @Test
    fun stoppedSendsGoAgainAfterAGrowingWait() {
        assertEquals(60_000L, MediaRoute.retryAfter(0))
        assertEquals(3_600_000L, MediaRoute.retryAfter(99))
    }
}
