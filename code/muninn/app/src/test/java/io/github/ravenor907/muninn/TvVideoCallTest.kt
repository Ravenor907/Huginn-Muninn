package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.TurnClient
import io.github.ravenor907.muninn.net.call.TurnCreds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** A parent's camera on the Kids' TV (the TV never films), and the long-distance call fixes: login retry, IPv6 relay. */
class TvVideoCallTest {
    @Test fun aKidsTvShowsVideoButNeverSendsIt() {
        assertTrue(CallLogic.sendsCamera(vp8 = true, kidsTv = false))
        assertFalse(CallLogic.sendsCamera(vp8 = true, kidsTv = true))
        assertFalse(CallLogic.sendsCamera(vp8 = false, kidsTv = false))
        assertFalse(CallLogic.sendsCamera(vp8 = false, kidsTv = true))
    }

    @Test fun theTvShowsTheirCameraOnlyWhileItIsOn() {
        assertTrue(CallLogic.showRemote(video = true, peerCamera = true, ended = false))
        // Their camera off (a voice call, or turned off mid-call): the avatar.
        assertFalse(CallLogic.showRemote(video = true, peerCamera = false, ended = false))
        // Video can't go both ways: never a picture.
        assertFalse(CallLogic.showRemote(video = false, peerCamera = true, ended = false))
        // The call is over: the end screen.
        assertFalse(CallLogic.showRemote(video = true, peerCamera = true, ended = true))
    }

    @Test fun theTvSaysVp8InItsAnswerButNoCamera() {
        // What the TV sends: vc=vp8 (it decodes), vid=false (its camera never turns on).
        val o = CallLogic.answer("c", "e", "s", null, null, false, CallLogic.Extras(vp8 = true, video = false, p2p = false), emptyList())
        assertEquals("vp8", o.optString("vc"))
        assertFalse(o.optBoolean("vid", true))
    }

    @Test fun aFailedLoginIsRetriedUnlessTheRelayIsRestingOrUsedUp() {
        assertTrue(CallLogic.retryLogin("Couldn't reach the internet relay"))
        assertTrue(CallLogic.retryLogin("The internet relay didn't answer properly"))
        assertTrue(CallLogic.retryLogin(""))
        assertFalse(CallLogic.retryLogin("The internet relay is resting"))
        assertFalse(CallLogic.retryLogin("The internet relay is used up for today"))
    }

    @Test fun anIpLiteralHasNoIpv6Lookup() {
        assertNull(TurnClient.lookup6("104.30.1.2"))
        assertNull(TurnClient.lookup6("2001:db8::1"))
    }

    @Test fun aFailedIpv4RelayTriesItsIpv6Address() {
        // Nothing listens on port 1: IPv4 fails at once, then the IPv6 address of the same name is asked for.
        val asked = ArrayList<String>()
        val login = TurnCreds.Login(listOf("turn:relay.example:1?transport=tcp"), "user", "secret", Long.MAX_VALUE)
        val t = TurnClient(login, { InetAddress.getByName("127.0.0.1") }, { h -> asked += h; null }) { _, _ -> }
        assertFalse(t.start()); t.close()
        assertEquals(listOf("relay.example"), asked)
    }
}
