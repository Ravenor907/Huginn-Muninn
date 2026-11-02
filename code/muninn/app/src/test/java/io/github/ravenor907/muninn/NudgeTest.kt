package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.LegacyPeers
import io.github.ravenor907.muninn.net.NudgeWire
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.RingKey
import io.github.ravenor907.muninn.net.RingWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cap n1 (shared/docs/protocol.md 23.3): nudges to the ring mailbox, so a locked phone notices only real messages. */
class NudgeTest {
    @Test fun capsSayN1Last() {
        assertTrue(NudgeWire.CAP in Proto06.caps(Proto06.CAPS))
        assertEquals("n1", Proto06.CAPS.split(' ').last())
    }

    /** Only real messages nudge: not receipts, typing, live, profile, link frames, presence, control or state. */
    @Test fun onlyRealMessagesNudge() {
        for (room in listOf("private", "rich", "photo", "gmsg", "gphoto")) assertTrue(room, NudgeWire.counts(room, ""))
        assertTrue(NudgeWire.counts("file", """{"t":"offer","id":"x"}"""))
        assertTrue(NudgeWire.counts("group", """{"t":"invite","g":"x"}"""))
        assertFalse(NudgeWire.counts("file", """{"t":"accept","id":"x"}"""))
        assertFalse(NudgeWire.counts("file", """{"t":"decline","id":"x"}"""))
        assertFalse(NudgeWire.counts("file", "not json"))
        for (t in listOf("state", "join", "leave", "decline", "react", "edit", "vote", "mod", "link", "sync", "joinno"))
            assertFalse(t, NudgeWire.counts("group", """{"t":"$t","g":"x"}"""))
        for (room in listOf("receipt", "greceipt", "live", "profile", "link", "call", "mx", "unsend", "keep", "intro", "tvconfig", "nearby", ""))
            assertFalse(room, NudgeWire.counts(room, """{"t":"offer"}"""))
    }

    /** At most one nudge per recipient per minute; each recipient on their own. */
    @Test fun coalescing() {
        val g = NudgeWire.Gate()
        assertTrue(g.take("A", 1_000_000))
        assertFalse(g.take("A", 1_000_001))
        assertFalse(g.take("A", 1_000_000 + NudgeWire.EVERY_MS - 1))
        assertTrue(g.take("B", 1_000_001))
        assertTrue(g.take("A", 1_000_000 + NudgeWire.EVERY_MS))
        assertFalse(g.take("A", 1_000_000 + NudgeWire.EVERY_MS + 5))
        assertEquals(60_000L, NudgeWire.EVERY_MS)
    }

    /** The plaintext holds t and ts only, and it is sealed and padded like a ring. */
    @Test fun nudgeShape() {
        val p = JSONObject(NudgeWire.plain(1761500130000L))
        assertEquals(setOf("t", "ts"), p.keys().asSequence().toSet())
        assertEquals("nudge", p.getString("t"))
        assertTrue(NudgeWire.ok(p))
        assertFalse(NudgeWire.ok(JSONObject("""{"t":"nudge"}""")))
        assertFalse(NudgeWire.ok(JSONObject("""{"t":"nudge","ts":-1}""")))
        assertFalse(NudgeWire.ok(JSONObject("""{"t":"ring","ts":1}""")))
    }

    /** Locked, a nudge sealed to my ring key puts up the notice; unlocked it doesn't; a ring isn't a nudge, nor a nudge a ring. */
    @Test fun lockedNudgeNotices() {
        val f = java.io.File.createTempFile("ring", ".json").also { it.delete() }
        val other = java.io.File.createTempFile("ring2", ".json").also { it.delete() }
        try {
            val ring = RingKey(f)
            val nudge = NudgeWire.content(ring.publicKey, 42L)
            assertEquals(1024, nudge.length)   // padded to the smallest size, like a ring (section 20)
            assertEquals(42L, ring.nudge(nudge))
            assertNull(ring.open(nudge))
            assertTrue(NudgeWire.ringMailboxNotices(true, nudge, ring))
            assertFalse(NudgeWire.ringMailboxNotices(false, nudge, ring))
            val rang = RingWire.ringContent(ring.publicKey, "AAECAwQFBgcICQoLDA0ODw", 42L)
            assertNull(ring.nudge(rang))
            assertFalse(NudgeWire.ringMailboxNotices(true, rang, ring))
            // Sealed to someone else's ring key: nothing.
            val notMine = NudgeWire.content(RingKey(other).publicKey, 42L)
            assertFalse(NudgeWire.ringMailboxNotices(true, notMine, ring))
            assertFalse(NudgeWire.ringMailboxNotices(true, "junk", ring))
        } finally { f.delete(); other.delete() }
    }

    /** A sealed inbox event (a receipt, say) never notifies while everyone nudges; it does while someone may not. */
    @Test fun receiptNeverNotifiesWithoutLegacyPeers() {
        val ours = Proto06.caps(Proto06.CAPS)
        assertFalse(NudgeWire.inboxNotices(ours, legacyPeers = false))
        assertTrue(NudgeWire.inboxNotices(ours, legacyPeers = true))
        assertTrue(NudgeWire.inboxNotices(ours - NudgeWire.CAP, legacyPeers = false))
        val f = java.io.File.createTempFile("legacy", ".json").also { it.delete() }
        try {
            assertTrue(LegacyPeers(f).read())   // missing: as before n1
            LegacyPeers(f).write(false)
            assertFalse(LegacyPeers(f).read())
            assertFalse(NudgeWire.inboxNotices(ours, LegacyPeers(f).read()))
            assertEquals(false, JSONObject(f.readText()).getBoolean("legacyPeers"))
            val l = LegacyPeers(f)
            l.write(true)
            assertTrue(LegacyPeers(f).read())
            l.forget()
            assertTrue(LegacyPeers(f).read())
            f.writeText("broken")
            assertTrue(LegacyPeers(f).read())
        } finally { f.delete(); java.io.File(f.path + ".bak").delete() }
    }

    /** Who may write without nudging: a contact without n1 (or unknown caps), a group-only peer, or a list that's empty. */
    @Test fun legacyPeers() {
        val new = Contact("a", "A", null, true, caps = setOf("r1", "b1", "n1"))
        assertFalse(NudgeWire.legacy(listOf(new)))
        assertTrue(NudgeWire.legacy(listOf(new, Contact("b", "B", null, true, caps = setOf("r1", "b1")))))
        assertTrue(NudgeWire.legacy(listOf(new, Contact("c", "C", null, true))))
        assertTrue(NudgeWire.legacy(listOf(new, Contact("d", "D", null, false, groupOnly = true, caps = setOf("r1", "g1", "c1", "n1")))))
        assertTrue(NudgeWire.legacy(emptyList()))
    }
}
