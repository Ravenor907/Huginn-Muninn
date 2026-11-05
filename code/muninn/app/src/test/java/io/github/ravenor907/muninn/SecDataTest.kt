package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.EnvelopePad
import io.github.ravenor907.muninn.net.LinkPreview
import io.github.ravenor907.muninn.net.LockWait
import io.github.ravenor907.muninn.net.Vault
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** Data privacy: envelope padding (shared/vectors/pad-vectors.json), the lock's rules and wait, link preview addresses. */
class SecDataTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("pad-vectors.json")!!.readText())

    private fun wrapping(d: Int) = "{\"e\":\"" + "A".repeat(60) + "\",\"n\":\"" + "B".repeat(16) + "\",\"d\":\"" + "C".repeat(d) + "\"}"

    @Test fun bucketsMatchTheReference() {
        val a = v.getJSONArray("bucket")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            assertEquals("n=${c.getInt("n")}", c.getInt("bucket"), EnvelopePad.bucket(c.getInt("n")))
        }
    }

    @Test fun paddingMatchesTheReference() {
        val a = v.getJSONArray("pad")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val js = wrapping(c.getInt("d"))
            val out = EnvelopePad.pad(js)
            val content = java.util.Base64.getEncoder().encodeToString(out.toByteArray())
            assertEquals("d=${c.getInt("d")}", c.getInt("content"), content.length)
            val o = JSONObject(out)
            assertEquals(c.getInt("zeros"), o.getString("z").length)
            // Everything else is untouched, so apps without padding read it the same.
            assertEquals("C".repeat(c.getInt("d")), o.getString("d"))
            assertEquals(setOf("e", "n", "d", "z"), o.keys().asSequence().toSet())
        }
    }

    @Test fun expirationMatchesTheReference() {
        assertEquals(v.getLong("blur"), io.github.ravenor907.muninn.net.Nostr.BLUR_S)
        val a = v.getJSONArray("expiry")
        assertTrue(a.length() > 0)
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val e = io.github.ravenor907.muninn.net.Nostr.expiration(c.getLong("created"))
            assertEquals("created=${c.getLong("created")}", c.getLong("expiration"), e)
            assertEquals(0L, e % 86_400L)
        }
    }

    @Test fun newLocksNeedSixDotsOrCharacters() {
        assertFalse(Vault.LockRules.strong("pattern:0,1,2,5,8"))
        assertTrue(Vault.LockRules.strong("pattern:0,1,2,5,8,7"))
        assertFalse(Vault.LockRules.strong("abcde"))
        assertTrue(Vault.LockRules.strong("abcdef"))
    }

    @Test fun waitGrowsEveryFiveTries() {
        // 1.42: 5 minutes, 1 hour, 12 hours, then 24 hours again and again (LockRulesTest has the rest).
        assertEquals(0L, LockWait.after(4))
        assertEquals(300_000L, LockWait.after(5))
        assertEquals(0L, LockWait.after(6))
        assertEquals(3_600_000L, LockWait.after(10))
        assertEquals(86_400_000L, LockWait.after(100))
    }

    @Test fun waitCantBeSkipped() {
        val s = LockWait.State(until = 100_000L, wait = 60_000L, boot = 7)
        assertEquals(30_000L, LockWait.left(s, 70_000L, 7))
        assertEquals(0L, LockWait.left(s, 100_000L, 7))
        // A restart (the time since start-up is back near zero): the whole wait again, not nothing.
        assertEquals(60_000L, LockWait.left(s, 5_000L, 8))
        // Never longer than the wait itself, whatever is stored.
        assertEquals(60_000L, LockWait.left(LockWait.State(10_000_000L, 60_000L, 7), 0L, 7))
        assertEquals(0L, LockWait.left(LockWait.State(0L, 0L, 7), 0L, 8))
    }

    @Test fun previewsOnlyReachPublicAddresses() {
        for (a in listOf("10.0.0.1", "172.16.5.4", "192.168.1.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "0.0.0.0",
                "::1", "fe80::1", "fd00::1", "fc12::5", "::ffff:192.168.0.1", "224.0.0.1"))
            assertFalse(a, LinkPreview.publicAddress(InetAddress.getByName(a)))
        for (a in listOf("1.1.1.1", "93.184.216.34", "2606:4700::1111", "172.32.0.1"))
            assertTrue(a, LinkPreview.publicAddress(InetAddress.getByName(a)))
        assertTrue(LinkPreview.allowedUrl("https://example.com/a"))
        assertFalse(LinkPreview.allowedUrl("http://example.com/a"))
        assertFalse(LinkPreview.allowedUrl("https://192.168.1.1/admin"))
        assertFalse(LinkPreview.allowedUrl("https://[fd00::1]/"))
        assertTrue(LinkPreview.allowedUrl("https://1.1.1.1/"))
    }
}
