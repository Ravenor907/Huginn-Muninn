package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ProfileWire
import io.github.ravenor907.muninn.net.Proto06
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cap p2 (shared/docs/protocol.md 22.4): pictures that always arrive. The pure rules; Huginn's self-test checks the same shapes. */
class P2Test {
    @Test
    fun capIsLastAndKeepsP1() {
        val caps = Proto06.CAPS.split(' ')
        assertEquals(ProfileWire.CAP2, caps.last())
        assertTrue(ProfileWire.CAP in caps)
    }

    @Test
    fun versions() {
        assertEquals(1L, ProfileWire.versionOf(1))
        assertEquals(7L, ProfileWire.versionOf(7L))
        assertEquals((1L shl 53) - 1, ProfileWire.versionOf((1L shl 53) - 1))
        assertNull(ProfileWire.versionOf(0))
        assertNull(ProfileWire.versionOf(-3))
        assertNull(ProfileWire.versionOf(1L shl 53))
        assertNull(ProfileWire.versionOf(2.5))
        assertNull(ProfileWire.versionOf("4"))
        assertNull(ProfileWire.versionOf(null))
        assertEquals(4L, ProfileWire.pvOf(JSONObject("""{"room":"private","pv":4}""")))
        assertNull(ProfileWire.pvOf(JSONObject("""{"room":"private"}""")))
        assertNull(ProfileWire.pvOf(JSONObject("""{"room":"private","pv":"4"}""")))
        assertNull(ProfileWire.pvOf(JSONObject("""{"room":"private","pv":0}""")))
    }

    @Test
    fun wireShapes() {
        assertEquals("""{"t":"profile?"}""", ProfileWire.askMsg())
        assertEquals(JSONObject("""{"t":"profile-ok","v":5}""").toString(), JSONObject(ProfileWire.ackMsg(5)).toString())
        assertTrue(ProfileWire.ctlOk(JSONObject(ProfileWire.askMsg())))
        assertTrue(ProfileWire.ctlOk(JSONObject(ProfileWire.ackMsg(5))))
        assertTrue(ProfileWire.ctlOk(JSONObject("""{"t":"profile?","x":1}""")))
        assertFalse(ProfileWire.ctlOk(JSONObject("""{"t":"profile-ok"}""")))
        assertFalse(ProfileWire.ctlOk(JSONObject("""{"t":"profile-ok","v":0}""")))
        assertFalse(ProfileWire.ctlOk(JSONObject("""{"t":"profile-ok","v":"5"}""")))
        assertFalse(ProfileWire.ctlOk(JSONObject("""{"t":"profile","v":5}""")))
        assertTrue(ProfileWire.isCtl(ProfileWire.askMsg()))
        assertTrue(ProfileWire.isCtl(ProfileWire.ackMsg(2)))
        assertFalse(ProfileWire.isCtl(ProfileWire.msg(2, "", "#3a7bd5")))
        assertFalse(ProfileWire.isCtl("not json"))
        // The small ones are never taken for a picture (an app with only p1 refuses them, so only p2 apps get them).
        assertFalse(ProfileWire.ok(JSONObject(ProfileWire.askMsg())))
        assertFalse(ProfileWire.ok(JSONObject(ProfileWire.ackMsg(5))))
    }

    @Test
    fun askingIsRateLimited() {
        assertTrue(ProfileWire.wants(0, 1))
        assertTrue(ProfileWire.wants(3, 4))
        assertFalse(ProfileWire.wants(4, 4))
        assertFalse(ProfileWire.wants(5, 4))
        val t = 1_000_000_000L
        assertTrue(ProfileWire.mayAsk(0, t))
        assertFalse(ProfileWire.mayAsk(t, t + ProfileWire.ASK_MS - 1))
        assertTrue(ProfileWire.mayAsk(t, t + ProfileWire.ASK_MS))
        // Refresh by hand waits only 10 s; answering an ask, a minute.
        assertFalse(ProfileWire.mayAsk(t, t + 9_999, ProfileWire.REFRESH_MS))
        assertTrue(ProfileWire.mayAsk(t, t + 10_000, ProfileWire.REFRESH_MS))
        assertFalse(ProfileWire.mayAsk(t, t + 59_999, ProfileWire.ANSWER_MS))
        assertTrue(ProfileWire.mayAsk(t, t + 60_000, ProfileWire.ANSWER_MS))
        assertEquals(5, ProfileWire.CTL_PER_10S)
    }

    @Test
    fun retriesBackOffAndStop() {
        assertEquals(2 * 60_000L, ProfileWire.retryMs(1))
        assertEquals(4 * 60_000L, ProfileWire.retryMs(2))
        assertEquals(8 * 60_000L, ProfileWire.retryMs(3))
        assertEquals(256 * 60_000L, ProfileWire.retryMs(8))
        assertEquals(6 * 3_600_000L, ProfileWire.retryMs(9))
        assertEquals(6 * 3_600_000L, ProfileWire.retryMs(1000))
        val t = 5_000_000L
        assertTrue(ProfileWire.due(0, 0, t))
        assertFalse(ProfileWire.due(1, t, t + 2 * 60_000L - 1))
        assertTrue(ProfileWire.due(1, t, t + 2 * 60_000L))
        assertFalse(ProfileWire.due(3, t, t + 7 * 60_000L))
        assertTrue(ProfileWire.due(3, t, t + 8 * 60_000L))
        assertTrue(ProfileWire.due(ProfileWire.MAX_TRIES - 1, t, t + ProfileWire.MAX_RETRY_MS))
        // After the last try only an ask ("profile?", from a "pv") or a new version sends it again.
        assertFalse(ProfileWire.due(ProfileWire.MAX_TRIES, t, t + 100 * ProfileWire.MAX_RETRY_MS))
    }

    @Test
    fun onlyTheVersionSentIsAcked() {
        assertTrue(ProfileWire.acks(4, 4))
        assertFalse(ProfileWire.acks(4, 3))
        assertFalse(ProfileWire.acks(4, 5))
        assertFalse(ProfileWire.acks(0, 0))
    }
}
