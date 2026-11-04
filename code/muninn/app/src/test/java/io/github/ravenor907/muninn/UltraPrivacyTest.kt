package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.LinkPreview
import io.github.ravenor907.muninn.net.Net
import io.github.ravenor907.muninn.net.UltraPrivacy
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ultra privacy (1.42): the switch gates every feature that would show the address, and every internet connection
 * goes to Tor's SOCKS5 port with the name looked up by Tor, never here; with Tor not ready, nothing leaves directly.
 */
class UltraPrivacyTest {
    @After fun off() = UltraPrivacy.use(false)

    @Test
    fun offByDefaultChangesNothing() {
        UltraPrivacy.use(false)
        assertFalse(UltraPrivacy.on)
        assertNull(UltraPrivacy.proxy())
        assertEquals(listOf(Proxy.NO_PROXY), UltraPrivacy.selector.select(URI("wss://relay.damus.io")))
        assertTrue(UltraPrivacy.directAllowed && UltraPrivacy.videoSendAllowed && UltraPrivacy.previewsAllowed && UltraPrivacy.gifSearchAllowed)
        assertFalse(UltraPrivacy.blocksFile("holiday.mp4"))
        assertEquals(UltraPrivacy.Tor.OFF, UltraPrivacy.state.value)
    }

    @Test
    fun onGatesEveryFeature() {
        UltraPrivacy.use(true)
        assertTrue(UltraPrivacy.on)
        assertFalse("direct calls, files and photos", UltraPrivacy.directAllowed)
        assertFalse("sending videos", UltraPrivacy.videoSendAllowed)
        assertFalse("link previews", UltraPrivacy.previewsAllowed)
        assertFalse("GIF search", UltraPrivacy.gifSearchAllowed)
        // Videos as files too, by name or type; other files still go.
        listOf("a.mp4", "B.MOV", "clip.webm", "x.mkv", "y.3gp").forEach { assertTrue(it, UltraPrivacy.blocksFile(it)) }
        assertTrue(UltraPrivacy.blocksFile("noext", "video/mp4"))
        listOf("photo.jpg", "notes.txt", "song.mp3", "mp4").forEach { assertFalse(it, UltraPrivacy.blocksFile(it)) }
        // A link preview isn't even tried (no network here: it would fail differently).
        assertNull(LinkPreview.fetch("https://example.com/"))
        assertEquals("Off while Ultra privacy is on", UltraPrivacy.OFF_NOTE)
        assertTrue(UltraPrivacy.VIDEO_OFF.contains(UltraPrivacy.OFF_NOTE))
    }

    @Test
    fun failsClosedUntilTorIsReady() {
        UltraPrivacy.use(true)
        assertEquals(UltraPrivacy.Tor.STARTING, UltraPrivacy.state.value)
        val p = UltraPrivacy.proxy()!!
        assertEquals(Proxy.Type.SOCKS, p.type())
        val a = p.address() as InetSocketAddress
        assertEquals("127.0.0.1", a.address.hostAddress)
        assertEquals(UltraPrivacy.CLOSED_PORT, a.port)
        // Never Proxy.NO_PROXY while it's on.
        assertTrue(UltraPrivacy.selector.select(URI("https://api.github.com/")).none { it.type() == Proxy.Type.DIRECT })
        // Names are never looked up here.
        try { UltraPrivacy.dns.lookup("relay.damus.io"); fail("looked up locally") } catch (_: java.net.UnknownHostException) {}
        var told = 0
        UltraPrivacy.onChange { told++ }
        UltraPrivacy.ready(9150)
        assertEquals(UltraPrivacy.Tor.READY, UltraPrivacy.state.value)
        assertEquals(9150, (UltraPrivacy.proxy()!!.address() as InetSocketAddress).port)
        assertEquals(1, told)
        UltraPrivacy.ready(9150)
        assertEquals("ready again on the same port changes nothing", 1, told)
        // Tor lost: closed again until it's back.
        UltraPrivacy.starting(50, 1_000)
        assertEquals(UltraPrivacy.CLOSED_PORT, (UltraPrivacy.proxy()!!.address() as InetSocketAddress).port)
        UltraPrivacy.use(false)
        assertNull(UltraPrivacy.proxy())
    }

    @Test
    fun slowAndBlockedShowTheBanner() {
        assertEquals(UltraPrivacy.Tor.STARTING, UltraPrivacy.startState(10_000, false))
        assertEquals(UltraPrivacy.Tor.SLOW, UltraPrivacy.startState(UltraPrivacy.SLOW_MS, false))
        assertEquals(UltraPrivacy.Tor.BLOCKED, UltraPrivacy.startState(UltraPrivacy.SLOW_MS, true))
        assertEquals(UltraPrivacy.Tor.BLOCKED, UltraPrivacy.startState(UltraPrivacy.BLOCKED_MS, false))
        assertFalse(UltraPrivacy.bannerShows(UltraPrivacy.Tor.STARTING))
        assertFalse(UltraPrivacy.bannerShows(UltraPrivacy.Tor.READY))
        assertTrue(UltraPrivacy.bannerShows(UltraPrivacy.Tor.SLOW))
        assertTrue(UltraPrivacy.bannerShows(UltraPrivacy.Tor.BLOCKED))
        assertEquals(45, UltraPrivacy.percentOf("NOTICE BOOTSTRAP PROGRESS=45 TAG=loading_descriptors SUMMARY=\"x\""))
        assertEquals(0, UltraPrivacy.percentOf(""))
    }

    @Test
    fun wordingHasNoDashesAndListsEveryDownside() {
        val all = UltraPrivacy.WHAT_IT_DOES + UltraPrivacy.DOWNSIDES + UltraPrivacy.SETUP_LINES + UltraPrivacy.SETUP_ULTRA + UltraPrivacy.SETUP_TITLE
        all.forEach { assertFalse(it, it.contains('\u2014') || it.contains('\u2013')) }
        val d = UltraPrivacy.DOWNSIDES.joinToString(" ").lowercase()
        listOf("slower", "relay sees your address", "direct", "sending videos", "link previews", "gif search", "update", "battery",
            "bigger", "blocked", "volunteers", "no encryption").forEach { assertTrue(it, d.contains(it)) }
        assertTrue(UltraPrivacy.SETUP_ULTRA.contains("a few trade-offs, explained before you turn it on"))
        assertEquals("Your privacy, built in", UltraPrivacy.SETUP_TITLE)
    }

    @Test
    fun relaySocketsGoThroughTorWithTheNameNotAnAddress() {
        // A pretend Tor: takes the SOCKS5 greeting and request, notes the host asked for, then refuses.
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val asked = AtomicReference<String>()
        val done = CountDownLatch(1)
        Thread {
            try {
                server.accept().use { s ->
                    val i = s.getInputStream(); val o = s.getOutputStream()
                    val ver = i.read(); val n = i.read(); repeat(n) { i.read() }
                    if (ver != 5) return@Thread
                    o.write(byteArrayOf(5, 0)); o.flush()
                    i.read(); i.read(); i.read()                     // 5, CONNECT, 0
                    val atyp = i.read()
                    asked.set(if (atyp == 3) { val len = i.read(); String(ByteArray(len) { i.read().toByte() }, Charsets.US_ASCII) } else "address type $atyp")
                    o.write(byteArrayOf(5, 1, 0, 1, 0, 0, 0, 0, 0, 0)); o.flush()   // general failure
                }
            } catch (_: Exception) {} finally { done.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            UltraPrivacy.use(true)
            UltraPrivacy.ready(server.localPort)
            val ok = try {
                Net.client.newBuilder().connectTimeout(5, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
                    .newCall(Request.Builder().url("http://relay.example.invalid/").build()).execute().use { true }
            } catch (_: java.io.IOException) { false }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertFalse("Tor refused, so the request fails rather than going another way", ok)
            assertEquals("relay.example.invalid", asked.get())
        } finally { server.close() }
    }

    @Test
    fun theOneClientUsesTheGate() {
        assertTrue(Net.client.proxySelector === UltraPrivacy.selector)
        assertTrue(Net.client.dns === UltraPrivacy.dns)
        // Variants made from it (the relays' own, the updater's, the call relay's) keep it.
        val v = Net.client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
        assertTrue(v.proxySelector === UltraPrivacy.selector)
    }
}
