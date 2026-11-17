package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.EnvelopePad
import io.github.ravenor907.muninn.net.LinkPreview
import io.github.ravenor907.muninn.net.More
import io.github.ravenor907.muninn.net.MxPending
import io.github.ravenor907.muninn.net.NetLogic
import io.github.ravenor907.muninn.net.RingKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** 1.54 relay delivery fixes (Muninn): ordering, early edits, clock, sizes, unknown caps, calls-off nudges. */
class DeliveryTest {
    private fun theirs(id: String, chain: String, seq: Int, time: Long = 1_000L) =
        Chat.Msg(id, "ann", "Ann", id, false, time, chain = chain, seq = seq)

    /** Messages that waited come newest first: they're put back in the order their sender's ratchet numbered them. */
    @Test fun waitingMessagesTakeTheSendersOrder() {
        val now = 1_761_500_000_000L
        var l = emptyList<Chat.Msg>()
        for ((id, n) in listOf("c" to 2, "a" to 0, "b" to 1)) l = Chat.placePart(l, theirs(id, "d1", n, now), now)
        assertEquals(listOf("a", "b", "c"), l.map { it.id })
        // Another chain (they had a reply in between) or no ratchet number: goes last, as it came.
        l = Chat.placePart(l, theirs("x", "d2", 0, now), now)
        l = Chat.placePart(l, Chat.Msg("y", "ann", "Ann", "y", false, now), now)
        assertEquals(listOf("a", "b", "c", "x", "y"), l.map { it.id })
        // Mine are never moved, nor placed by their numbers.
        val mine = Chat.Msg("m", "ann", "Me", "m", true, now, chain = "d1", seq = 0)
        assertEquals("m", Chat.placePart(l, mine, now).last().id)
        // A later one that came over an hour ago stays where it is.
        val old = Chat.placePart(listOf(theirs("late", "d3", 5, now)), theirs("early", "d3", 1, now + 2 * 3_600_000L), now + 2 * 3_600_000L)
        assertEquals(listOf("late", "early"), old.map { it.id })
    }

    @Test fun chatsKeep2000() {
        var l = emptyList<Chat.Msg>()
        repeat(Chat.KEEP_MAX + 5) { l = Chat.placePart(l, Chat.Msg("m$it", "ann", "Ann", "t", false, it.toLong())) }
        assertEquals(Chat.KEEP_MAX, l.size)
        assertEquals("m5", l.first().id)
    }

    /** An edit or reaction before its message is held, handed back once when it lands, and forgotten after a while. */
    @Test fun earlyEditsWait() {
        val p = MxPending(holdMs = 1_000, max = 3)
        val edit = JSONObject().put("t", "edit").put("msg", "m1").put("text", "meet at 6").put("ts", 5)
        p.hold("ann", "m1", "Ann", edit, 0)
        assertTrue(p.take("bob", "m1", 10).isEmpty())
        assertEquals("meet at 6", p.take("ann", "m1", 10).single().second.getString("text"))
        assertTrue(p.take("ann", "m1", 20).isEmpty())          // once only
        p.hold("ann", "m2", "Ann", edit, 0)
        assertTrue(p.take("ann", "m2", 2_000).isEmpty())       // too late
        repeat(5) { p.hold("ann", "k$it", "Ann", edit, 3_000) }
        assertEquals(3, p.size())                               // bounded
    }

    @Test fun clockAgainstTheRelays() {
        assertEquals(0L, NetLogic.clockOffset(emptyList()))
        assertEquals(0L, NetLogic.clockOffset(listOf(5L, -3L, 20L)))         // a few seconds' drift: left alone
        assertEquals(3_000L, NetLogic.clockOffset(listOf(3_000L, 2_990L, 3_010L, 999_999_999L)))
        assertTrue(NetLogic.timeRefused("invalid: created_at too far in the future"))
        assertTrue(NetLogic.timeRefused("invalid: event creation date is too far off from the current time (timestamp)"))
        assertFalse(NetLogic.timeRefused("blocked: not allowed"))
        assertFalse(NetLogic.timeRefused("rate-limited: slow down"))
    }

    /** 1.55: a relay's reading follows this device's clock being set right afterwards (no stale hour on every event). */
    @Test fun clockReadingFollowsAClockSetRight() {
        val relayNow = 1_800_000_000_000L
        val upAtReading = 50_000L
        val localThen = relayNow - 3_600_000L                   // this device an hour behind
        val base = relayNow - upAtReading                      // what's kept
        assertEquals(3_600L, NetLogic.offsetNow(base, upAtReading, localThen))
        // A minute later the clock is set right: the offset is gone at once.
        assertEquals(0L, NetLogic.offsetNow(base, upAtReading + 60_000L, relayNow + 60_000L))
        // Not set right: still an hour, a minute later.
        assertEquals(3_600L, NetLogic.offsetNow(base, upAtReading + 60_000L, localThen + 60_000L))
    }

    /** The biggest preview that may go with the longest text still pads to 48 KB at most, sealed and wrapped twice. */
    @Test fun previewsStayUnder64K() {
        val text = "😀".repeat(2000)
        val url = "https://example.com/" + "a".repeat(1900)
        val big = More.Preview(url, "t".repeat(200), "d".repeat(300), ByteArray(LinkPreview.THUMB_BYTES))
        val fitted = LinkPreview.fit(text, "", big)
        assertNull(fitted.thumb)
        val small = More.Preview("https://example.com/x", "Title", "", ByteArray(LinkPreview.THUMB_BYTES))
        assertNotNull(LinkPreview.fit("Look", "", small).thumb)
        for ((t, lp) in listOf(text to fitted, "Look" to small, "x".repeat(4_000) to LinkPreview.fit("x".repeat(4_000), "", small))) {
            val payload = JSONObject().put("text", t).put("lp", More.previewJson(lp)).toString()
            assertTrue(EnvelopePad.fits(relayContent(payload)))
        }
        // A payload right at the limit too.
        assertTrue(EnvelopePad.fits(relayContent("x".repeat(EnvelopePad.PAYLOAD_MAX))))
    }

    /** Roughly what goes to a relay for [payload]: a sealed envelope with its clear fields, wrapped and padded as Chat does. */
    private fun relayContent(payload: String): String {
        val me = ChatCrypto.newKeyPair(); val them = ChatCrypto.newKeyPair()
        val theirKey = ChatCrypto.b64(them.public.encoded)
        val sk = ChatCrypto.sharedKey(me.private, me.public, them.public)
        val o = ChatWire.base("rich", ChatCrypto.b64(me.public.encoded), "N".repeat(40), "f".repeat(64), "a".repeat(64), java.util.UUID.randomUUID().toString())
            .put("days", 30).put("pv", 3).put("rh", JSONObject().put("b", "x".repeat(124)).put("d", "y".repeat(124)).put("pn", 999).put("n", 999))
        val sealed = ChatWire.seal(o, payload.toByteArray(), sk, theirKey, null, ChatCrypto::newKeyPair)
        val eph = ChatCrypto.newKeyPair()
        val s = ChatCrypto.sealBytes(ChatCrypto.sharedKey(eph.private, eph.public, them.public), sealed.toString().toByteArray())
        val w = JSONObject().put("e", ChatCrypto.b64(eph.public.encoded)).put("w", "0".repeat(16)).put("x", "z".repeat(124)).put("n", s.nonce).put("d", s.data)
        return Base64.getEncoder().encodeToString(EnvelopePad.pad(w.toString()).toByteArray())
    }

    /** A group peer stored with only the assumed caps counts as unknown: included and asked. */
    @Test fun assumedCapsAreUnknown() {
        assertTrue(Contacts.unknownCaps(emptySet()))
        assertTrue(Contacts.unknownCaps(setOf("r1", "g1", "c1")))
        assertFalse(Contacts.unknownCaps(setOf("r1", "g1", "c1", "m1")))
    }

    /** Calls off for someone: the key they hold is read for their nudges past 2 days, never rings, and goes once nobody has calls off. */
    @Test fun callsOffKeepsNudgesComing() {
        val f = java.io.File.createTempFile("ring", ".json").also { it.delete() }
        try {
            val r = RingKey(f)
            val t0 = 1_761_500_000_000L
            val old = r.mailbox
            r.renew(t0, nudges = true)
            val later = t0 + 10 * 86_400_000L
            assertFalse(r.prune(later))
            assertTrue(old in r.mailboxes(later))
            assertTrue(old in RingKey(f).mailboxes(later))    // saved
            assertTrue(r.release(later))
            assertTrue(old in r.mailboxes(later + 1))
            assertTrue(r.prune(later + RingKey.OLD_MS + 1))
            assertFalse(old in r.mailboxes(later + RingKey.OLD_MS + 1))
            // A block's renewal still lets the old one go after 2 days.
            val b = r.mailbox
            r.renew(later)
            assertTrue(r.prune(later + RingKey.OLD_MS + 1))
            assertFalse(b in r.mailboxes(later + RingKey.OLD_MS + 1))
        } finally { f.delete() }
    }
}
