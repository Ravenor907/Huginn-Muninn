package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.EnvelopePad
import io.github.ravenor907.muninn.net.NudgeKey
import io.github.ravenor907.muninn.net.NudgeWire
import io.github.ravenor907.muninn.net.NudgeWire.Nudge
import io.github.ravenor907.muninn.net.NudgeWire.Show
import io.github.ravenor907.muninn.net.PeerKeys
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.RingKey
import io.github.ravenor907.muninn.net.RingWire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Caps n1 and n2 (shared/docs/protocol.md 23.3): nudges, so a locked phone notifies only for real messages, saying who
 * and what kind (n2) as the notification setting allows. Nothing else that reaches the inbox while locked notifies.
 */
class NudgeTest {
    private fun temp(name: String) = File.createTempFile(name, ".json").also { it.delete() }

    @Test fun capsSayN1AndN2() {
        val caps = Proto06.CAPS.split(' ')
        assertTrue(NudgeWire.CAP in caps)
        assertTrue(NudgeWire.RICH_CAP in caps)
        assertTrue(caps.indexOf(NudgeWire.RICH_CAP) > caps.indexOf(NudgeWire.CAP))
    }

    @Test fun onlyRealMessagesCount() {
        for (room in listOf("private", "rich", "photo", "gmsg", "gphoto")) assertTrue(room, NudgeWire.counts(room, ""))
        assertTrue(NudgeWire.counts("file", """{"t":"offer","id":"x"}"""))
        assertTrue(NudgeWire.counts("group", """{"t":"invite","g":"x"}"""))
        assertFalse(NudgeWire.counts("file", """{"t":"accept","id":"x"}"""))
        assertFalse(NudgeWire.counts("file", """{"t":"decline","id":"x"}"""))
        assertFalse(NudgeWire.counts("file", "not json"))
        for (t in listOf("state", "react", "leave", "ask", "refuse", "keys"))
            assertFalse(t, NudgeWire.counts("group", """{"t":"$t","g":"x"}"""))
        for (room in listOf("receipt", "greceipt", "live", "profile", "link", "call", "mx", "unsend", "keep", "intro", "tvconfig"))
            assertFalse(room, NudgeWire.counts(room, """{"t":"offer"}"""))
    }

    @Test fun kinds() {
        assertEquals("msg", NudgeWire.kindOf("private"))
        assertEquals("msg", NudgeWire.kindOf("gmsg"))
        assertEquals("photo", NudgeWire.kindOf("gphoto"))
        assertEquals("file", NudgeWire.kindOf("file"))
        assertEquals("invite", NudgeWire.kindOf("group"))
        assertEquals("voice", NudgeWire.blobKind(1200, 0, ""))
        assertEquals("video", NudgeWire.blobKind(0, 3000, ""))
        assertEquals("file", NudgeWire.blobKind(0, 0, "notes.pdf"))
        assertEquals("photo", NudgeWire.blobKind(0, 0, ""))
    }

    @Test fun coalesced() {
        val g = NudgeWire.Gate()
        assertTrue(g.take("A", 1_000_000))
        assertFalse(g.take("A", 1_000_001))
        assertFalse(g.take("A", 1_000_000 + NudgeWire.EVERY_MS - 1))
        assertTrue(g.take("B", 1_000_001))
        assertTrue(g.take("A", 1_000_000 + NudgeWire.EVERY_MS))
        assertFalse(g.take("A", 1_000_000 + NudgeWire.EVERY_MS + 5))
        assertEquals(60_000L, NudgeWire.EVERY_MS)
        // n2: per person and chat, so a group's nudge isn't held back by a 1:1 one. n1: per person.
        assertEquals("A", NudgeWire.gateKey("A", false, "Gaming"))
        assertTrue(NudgeWire.gateKey("A", true, "") != NudgeWire.gateKey("A", true, "Gaming"))
    }

    /** The n1 form holds t and ts only; the n2 form adds from, chat and kind. */
    @Test fun plaintexts() {
        val p = JSONObject(NudgeWire.plain(1761500130000L))
        assertEquals(setOf("t", "ts"), p.keys().asSequence().toSet())
        assertEquals("nudge", p.getString("t"))
        assertTrue(NudgeWire.ok(p))
        assertFalse(NudgeWire.ok(JSONObject("""{"t":"nudge"}""")))
        assertFalse(NudgeWire.ok(JSONObject("""{"t":"nudge","ts":-1}""")))
        assertFalse(NudgeWire.ok(JSONObject("""{"t":"ring","ts":1}""")))
        val r = JSONObject(NudgeWire.richPlain(1761500130000L, "  Raven  ", "", "voice"))
        assertEquals(setOf("t", "ts", "from", "chat", "kind"), r.keys().asSequence().toSet())
        assertEquals("Raven", r.getString("from"))
        assertEquals("", r.getString("chat"))
        assertEquals("voice", r.getString("kind"))
        assertEquals("msg", JSONObject(NudgeWire.richPlain(1L, "A", "B", "song")).getString("kind"))
    }

    /** Receivers take both forms: an n1 nudge names nobody; an unknown kind is "msg"; names are cleaned and capped. */
    @Test fun reading() {
        assertEquals(Nudge(5L), NudgeWire.read(JSONObject("""{"t":"nudge","ts":5}""")))
        assertEquals(Nudge(5L, "Ann", "Gaming", "photo"), NudgeWire.read(JSONObject("""{"t":"nudge","ts":5,"from":"Ann","chat":"Gaming","kind":"photo"}""")))
        assertEquals("msg", NudgeWire.read(JSONObject("""{"t":"nudge","ts":5,"kind":"dance"}"""))!!.kind)
        assertEquals("msg", NudgeWire.read(JSONObject("""{"t":"nudge","ts":5,"kind":7}"""))!!.kind)
        assertEquals("", NudgeWire.read(JSONObject("""{"t":"nudge","ts":5,"from":12,"chat":{}}"""))!!.from)
        assertNull(NudgeWire.read(JSONObject("""{"t":"ring","ts":5,"from":"Ann"}""")))
        assertEquals("AnnBob", NudgeWire.clean("Ann\u0000\n‮Bob "))
        assertEquals("", NudgeWire.clean(" \t "))
        assertEquals(NudgeWire.NAME_MAX, NudgeWire.clean("x".repeat(100)).length)
        // Never half a character at the cut.
        val emoji = NudgeWire.clean("a" + "🐦".repeat(30))
        assertTrue(emoji.length <= NudgeWire.NAME_MAX)
        assertFalse(Character.isHighSurrogate(emoji.last()))
        assertEquals("", NudgeWire.clean(null))
    }

    /** An n2 nudge sealed to the nudge key opens there; the n1 form still opens with the ring key and the nudge key. */
    @Test fun richNudgeBuildAndOpen() {
        val rf = temp("ring")
        val nf = temp("nudge")
        try {
            val ring = RingKey(rf)
            val nudge = NudgeKey(nf)
            val rich = NudgeWire.richContent(nudge.publicKey, 42L, "Raven", "The Family", "video")
            assertEquals(1024, rich.length)   // padded to the smallest size, like a ring (section 20)
            assertEquals(Nudge(42L, "Raven", "The Family", "video"), nudge.nudge(rich))
            assertNull(ring.nudge(rich))
            // A contact still on n1: the plain form, opened with the ring key.
            assertEquals(Nudge(42L), ring.nudge(NudgeWire.content(ring.publicKey, 42L)))
            assertEquals(Nudge(42L), nudge.nudge(NudgeWire.content(nudge.publicKey, 42L)))
            // n2 to the ring key while the nudge key hasn't come yet.
            assertEquals(Nudge(42L, "Raven", "", "msg"), ring.nudge(NudgeWire.richContent(ring.publicKey, 42L, "Raven", "", "msg")))
            // Read back from the file: the same key.
            assertEquals(nudge.publicKey, NudgeKey(nf).publicKey)
            assertTrue(nudge.mailbox != ring.mailbox)
        } finally { rf.delete(); nf.delete() }
    }

    /** Locked, a nudge gives a notification; unlocked it doesn't; a ring isn't a nudge, nor a nudge a ring. */
    @Test fun lockedNudgeNotices() {
        val f = temp("ring")
        val other = temp("ring2")
        try {
            val ring = RingKey(f)
            val nudge = NudgeWire.content(ring.publicKey, 42L)
            assertEquals(1024, nudge.length)
            assertNull(ring.open(nudge))
            assertEquals(Nudge(42L), NudgeWire.ringMailbox(true, nudge, ring))
            assertNull(NudgeWire.ringMailbox(false, nudge, ring))
            val rang = RingWire.ringContent(ring.publicKey, "AAECAwQFBgcICQoLDA0ODw", 42L)
            assertNull(ring.nudge(rang))
            assertNull(NudgeWire.ringMailbox(true, rang, ring))
            assertNull(NudgeWire.ringMailbox(true, NudgeWire.content(RingKey(other).publicKey, 42L), ring))
            assertNull(NudgeWire.ringMailbox(true, "junk", ring))
        } finally { f.delete(); other.delete() }
    }

    /** The nudge key can only nudge: a ring sealed to it is never a call or a nudge, and nothing rings from its mailbox. */
    @Test fun nudgeKeyCannotRing() {
        val f = temp("nudge")
        try {
            val key = NudgeKey(f)
            val rang = RingWire.ringContent(key.publicKey, "AAECAwQFBgcICQoLDA0ODw", 42L)
            assertNull(key.nudge(rang))
            assertNull(NudgeWire.nudgeMailbox(true, rang, key))
            assertNull(NudgeWire.nudgeMailbox(false, NudgeWire.richContent(key.publicKey, 1L, "A", "", "msg"), key))
            assertEquals("A", NudgeWire.nudgeMailbox(true, NudgeWire.richContent(key.publicKey, 1L, "A", "", "msg"), key)!!.from)
            // NudgeKey has no way to open a ring, and the app hands its mailbox's events only to the nudge path.
            assertFalse(NudgeKey::class.java.declaredMethods.any { it.name == "open" })
            val app = source("Muninn.kt")
            assertTrue(app.contains("nostr.onNudge = { content -> io.github.ravenor907.muninn.net.NudgeWire.nudgeMailbox("))
            assertFalse(Regex("""nudgeKey\.open\(""").containsMatchIn(app))
            // A caller rings only with the ring key ("rk"), never the nudge key.
            val chat = source("Chat.kt")
            val ring = chat.substringAfter("private fun ring(contactKey: String, json: String) {").substringBefore("\n    }\n")
            assertTrue(ring.contains("RingWire.ringContent(rk,"))
            assertFalse(ring.contains(".nk"))
        } finally { f.delete() }
    }

    /** n2 goes to the nudge key (contacts and group peers); n1 only to a contact's ring key; nothing without a key. */
    @Test fun routes() {
        val k = PeerKeys.Keys(rk = "R", rp = "r".repeat(64), nk = "N", np = "n".repeat(64))
        assertEquals(NudgeWire.Route("N", "n".repeat(64), true), NudgeWire.route(setOf("b1", "n1", "n2"), false, k))
        assertEquals(NudgeWire.Route("N", "n".repeat(64), true), NudgeWire.route(setOf("g1", "n2"), true, k))
        assertEquals(NudgeWire.Route("R", "r".repeat(64), false), NudgeWire.route(setOf("b1", "n1"), false, k))
        assertNull(NudgeWire.route(setOf("b1", "n1"), true, k))   // a group peer never had the ring key
        assertNull(NudgeWire.route(setOf("b1"), false, k))
        assertNull(NudgeWire.route(setOf("b1", "n1", "n2"), false, null))
        // n2 but no nudge key yet: the n2 form to a contact's ring key; a group peer gets nothing.
        val ringOnly = PeerKeys.Keys(rk = "R", rp = "r".repeat(64))
        assertEquals(NudgeWire.Route("R", "r".repeat(64), true), NudgeWire.route(setOf("b1", "n2"), false, ringOnly))
        assertNull(NudgeWire.route(setOf("g1", "n2"), true, ringOnly))
    }

    /** Receipts announce the nudge key ("nk", "np"); it's kept like the ring key and read back. */
    @Test fun nudgeKeyAnnounced() {
        val nk = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val a = RingWire.announceOf(JSONObject().put("nk", nk).put("np", "a".repeat(64)), 0)
        assertEquals(nk, a.nk)
        assertEquals("a".repeat(64), a.np)
        assertNull(a.rk)
        assertNull(RingWire.announceOf(JSONObject().put("nk", nk).put("np", "short"), 0).nk)
        assertNull(RingWire.announceOf(JSONObject().put("nk", "junk").put("np", "a".repeat(64)), 0).nk)
        var stored: String? = null
        val keys = PeerKeys({ stored }, { stored = it })
        keys.take("G", a)
        assertEquals(nk, PeerKeys({ stored }, { stored = it }).get("G")!!.nk)
        assertEquals("a".repeat(64), PeerKeys({ stored }, { stored = it }).get("G")!!.np)
        assertNull(keys.get("G")!!.rk)
    }

    /** What a locked notification says, for each setting and kind (the message itself can't be read while locked). */
    @Test fun lockedNotificationText() {
        val oneToOne = Nudge(1L, "Ann", "", "photo")
        val group = Nudge(1L, "Ann", "Gaming", "voice")
        val n1 = Nudge(1L)
        for (show in listOf(Show.NAME, Show.MESSAGE)) {
            assertEquals("Ann", NudgeWire.title(show, oneToOne))
            assertEquals("Ann · Gaming", NudgeWire.title(show, group))
            assertEquals("Muninn", NudgeWire.title(show, n1))
            assertEquals("New message", NudgeWire.text(show, "msg", 1))
            assertEquals("Photo", NudgeWire.text(show, "photo", 1))
            assertEquals("Voice message", NudgeWire.text(show, "voice", 1))
            assertEquals("Video message", NudgeWire.text(show, "video", 1))
            assertEquals("File", NudgeWire.text(show, "file", 1))
            assertEquals("Group invite", NudgeWire.text(show, "invite", 1))
            assertEquals("3 new messages", NudgeWire.text(show, "photo", 3))
        }
        assertEquals("Muninn", NudgeWire.title(Show.NOTHING, group))
        for (kind in NudgeWire.KINDS) assertEquals("New message", NudgeWire.text(Show.NOTHING, kind, 1))
        assertEquals("2 new messages", NudgeWire.text(Show.NOTHING, "msg", 2))
    }

    /** One notification per sender and chat; with Nothing, one for all. */
    @Test fun groupedPerSenderAndChat() {
        val a = Nudge(1L, "Ann", "", "msg")
        val a2 = Nudge(2L, "Ann", "", "photo")
        val ag = Nudge(3L, "Ann", "Gaming", "msg")
        val b = Nudge(4L, "Bob", "", "msg")
        assertEquals(NudgeWire.groupKey(Show.NAME, a), NudgeWire.groupKey(Show.NAME, a2))
        assertTrue(NudgeWire.groupKey(Show.NAME, a) != NudgeWire.groupKey(Show.NAME, ag))
        assertTrue(NudgeWire.groupKey(Show.NAME, a) != NudgeWire.groupKey(Show.NAME, b))
        assertEquals(NudgeWire.groupKey(Show.NOTHING, a), NudgeWire.groupKey(Show.NOTHING, b))
        assertEquals(NudgeWire.groupKey(Show.NOTHING, a), NudgeWire.groupKey(Show.NOTHING, ag))
    }

    /** A tapped notification opens the one matching chat after unlocking, else the chat list. */
    @Test fun tapMatchesChat() {
        val contacts = listOf("k1" to "Ann", "k2" to "Bob", "k3" to "Bob")
        val groups = listOf("g:1" to "Gaming", "g:2" to "Family")
        assertEquals("k1", NudgeWire.match("Ann", "", contacts, groups))
        assertNull(NudgeWire.match("Bob", "", contacts, groups))   // two Bobs: the chat list
        assertNull(NudgeWire.match("Cat", "", contacts, groups))
        assertEquals("g:1", NudgeWire.match("Ann", "Gaming", contacts, groups))
        assertNull(NudgeWire.match("Ann", "Book club", contacts, groups))
        assertNull(NudgeWire.match("", "", contacts, groups))
    }

    /**
     * Locked, nothing in the inbox notifies (a receipt, a check-in, presence): Muninn.kt no longer listens for inbox
     * events while locked, and no file decides otherwise. An inbox-style envelope never opens as a nudge either.
     */
    @Test fun inboxEventsNeverNotifyWhileLocked() {
        val app = source("Muninn.kt")
        assertFalse(app.contains("nostr.onLocked ="))
        assertFalse(app.contains("lockedNotice"))
        assertFalse(app.contains("Unlock to get new messages"))
        assertFalse(source("Ring.kt").contains("class LegacyPeers"))
        val f = temp("ring")
        try {
            val ring = RingKey(f)
            val receipt = Base64.getEncoder().encodeToString(EnvelopePad.pad(RingWire.sealRing(ring.publicKey,
                """{"ids":[],"s":"delivered"}""".toByteArray()).toString()).toByteArray())
            assertNull(NudgeWire.ringMailbox(true, receipt, ring))
        } finally { f.delete() }
    }

    private fun source(name: String): String {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        return src.walkTopDown().first { it.isFile && it.name == name }.readText()
    }
}
