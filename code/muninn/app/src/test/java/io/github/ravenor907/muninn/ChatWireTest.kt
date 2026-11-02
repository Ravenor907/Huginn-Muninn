package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.Outbox
import io.github.ravenor907.muninn.net.Ratchet
import io.github.ravenor907.muninn.net.Ratchets
import io.github.ravenor907.muninn.net.Seen
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Envelopes (v1 and ratchet), the outbox, duplicates, capability, nicknames and key changes. */
class ChatWireTest {
    private val a = ChatCrypto.newKeyPair()
    private val b = ChatCrypto.newKeyPair()
    private val aPub = ChatCrypto.b64(a.public.encoded)
    private val bPub = ChatCrypto.b64(b.public.encoded)
    private val skA = ChatCrypto.sharedKey(a.private, a.public, b.public)
    private val skB = ChatCrypto.sharedKey(b.private, b.public, a.public)
    private val newKey = { ChatCrypto.newKeyPair() }

    private fun base(room: String = "private", id: String = "m1") = ChatWire.base(room, aPub, "Alice", "fp", "f".repeat(64), id)
    private fun tmp(): File = Files.createTempDirectory("chatwire").toFile()
    private fun mem(): Pair<(File) -> String?, (File, String) -> Unit> {
        val disk = HashMap<String, String>()
        // Files are really created (so delete and listFiles work) but the text lives here, like the vault.
        return { f: File -> if (f.exists()) disk[f.path] else null } to { f: File, t: String -> f.writeText("x"); disk[f.path] = t }
    }

    /** Section 9: what is sent is cut at a whole character (code point), never over the limit and never half an emoji. */
    @Test
    fun clipKeepsWholeCharacters() {
        assertEquals("abc", ChatWire.clip("abc", 2000))
        assertEquals("ab", ChatWire.clip("abc", 2))
        val emoji = "\uD83E\uDD8A"   // one character, two UTF-16 units
        val text = "a".repeat(1999) + emoji + emoji
        val cut = ChatWire.clip(text, 2000)
        assertEquals(2000, cut.codePointCount(0, cut.length))
        assertTrue(cut.endsWith(emoji))
        assertEquals(2000, ChatWire.clip(emoji.repeat(2001), 2000).codePointCount(0, 4000))
    }

    @Test
    fun v1EnvelopeHasCapAndOpens() {
        val o = ChatWire.seal(base(), "hi".toByteArray(), skA, bPub, null, newKey)
        assertEquals("r1", o.getString("cap"))
        assertTrue(o.has("nonce")); assertTrue(o.has("data")); assertFalse(o.has("rh"))
        val back = JSONObject(o.toString())
        assertTrue(ChatWire.capable(back))
        assertEquals("hi", String(ChatWire.open(back, skB, b, null, newKey)!!))
        // Same bytes as the v1 text seal.
        assertEquals("hi", ChatCrypto.open(skB, ChatCrypto.Sealed(back.getString("nonce"), back.getString("data"))))
    }

    @Test
    fun ratchetEnvelopeShape() {
        val pa = Ratchet.Peer()
        val o = JSONObject(ChatWire.seal(base(), "hello".toByteArray(), skA, bPub, pa, newKey).toString())
        assertFalse("no v1 ciphertext", o.has("nonce"))
        val rh = o.getJSONObject("rh")
        assertEquals(setOf("b", "d", "pn", "n"), rh.keys().asSequence().toSet())
        assertEquals(0, rh.getInt("pn")); assertEquals(0, rh.getInt("n"))
        assertEquals(rh.getString("b"), rh.getString("d"))   // first message of a new session
        val data = o.getString("data")
        assertTrue("standard base64, padded, no wraps", data.matches(Regex("^[A-Za-z0-9+/]+={0,2}$")) && data.length % 4 == 0)
        assertEquals(Ratchet.Header.fromJson(rh), Ratchet.Header(rh.getString("b"), rh.getString("d"), 0, 0))
        val pb = Ratchet.Peer()
        assertEquals("hello", String(ChatWire.open(o, skB, b, pb, newKey)!!))
    }

    @Test
    fun ratchetBothWaysAndPhotoBytes() {
        val pa = Ratchet.Peer(); val pb = Ratchet.Peer()
        val piece = ByteArray(16_384) { (it * 7).toByte() }
        val e1 = JSONObject(ChatWire.seal(base("photo").put("i", 0).put("n", 1), piece, skA, bPub, pa, newKey).toString())
        assertArrayEquals(piece, ChatWire.open(e1, skB, b, pb, newKey))
        val reply = JSONObject(ChatWire.seal(ChatWire.base("receipt", bPub, "Bob", "fp", "e".repeat(64), "r1"),
            "{\"ids\":[\"m1\"],\"s\":\"delivered\"}".toByteArray(), skB, aPub, pb, newKey).toString())
        assertEquals("{\"ids\":[\"m1\"],\"s\":\"delivered\"}", String(ChatWire.open(reply, skA, a, pa, newKey)!!))
        // Out of order still opens (skipped keys).
        val x = JSONObject(ChatWire.seal(base(id = "x"), "x".toByteArray(), skA, bPub, pa, newKey).toString())
        val y = JSONObject(ChatWire.seal(base(id = "y"), "y".toByteArray(), skA, bPub, pa, newKey).toString())
        assertEquals("y", String(ChatWire.open(y, skB, b, pb, newKey)!!))
        assertEquals("x", String(ChatWire.open(x, skB, b, pb, newKey)!!))
    }

    @Test
    fun v1StillAcceptedAndBadRatchetDropsWithoutHarm() {
        val pa = Ratchet.Peer(); val pb = Ratchet.Peer()
        // v1 from a ratchet-capable contact: still opens, peer untouched.
        val v1 = JSONObject(ChatWire.seal(base(), "old".toByteArray(), skA, bPub, null, newKey).toString())
        assertEquals("old", String(ChatWire.open(v1, skB, b, pb, newKey)!!))
        assertTrue(pb.bases.isEmpty())
        // "rh" with no peer to open it: not authentic.
        val r = JSONObject(ChatWire.seal(base(), "new".toByteArray(), skA, bPub, pa, newKey).toString())
        assertNull(ChatWire.open(r, skB, b, null, newKey))
        // Tampered: null, and the real one still opens afterwards.
        val bad = JSONObject(r.toString()).put("data", ChatCrypto.b64(ByteArray(40)))
        assertNull(ChatWire.open(bad, skB, b, pb, newKey))
        val badHeader = JSONObject(r.toString()).put("rh", JSONObject().put("b", "x"))
        assertNull(ChatWire.open(badHeader, skB, b, pb, newKey))
        assertEquals("new", String(ChatWire.open(r, skB, b, pb, newKey)!!))
        // Missing nonce on a v1 envelope: not authentic, no crash.
        assertNull(ChatWire.open(JSONObject().put("data", "AAAA"), skB, b, null, newKey))
    }

    @Test
    fun capabilityUpgrade() {
        val c = Contact(bPub, "Bob", null, true)
        assertFalse(ChatWire.useRatchet("private", c))
        assertFalse(ChatWire.useRatchet("private", null))
        // An authenticated message with cap r1 marks them: then private payloads use the ratchet, Nearby never does.
        val up = c.copy(ratchet = true)
        for (room in listOf("private", "receipt", "unsend", "keep", "photo")) assertTrue(room, ChatWire.useRatchet(room, up))
        assertFalse(ChatWire.useRatchet("nearby", up))
        assertFalse(ChatWire.capable(JSONObject().put("cap", "r2")))
        assertFalse(ChatWire.capable(JSONObject()))
        // Adding the same person again never turns it off.
        assertTrue(ChatWire.merge(up, Contact(bPub, "Bob", null, true)).ratchet)
    }

    @Test
    fun retrySendsTheSameBytes() {
        val dir = tmp()
        val (r, w) = mem()
        val box = Outbox(dir, r, w)
        val pa = Ratchet.Peer()
        var made = 0
        val make = { made++; listOf(ChatWire.seal(base(), "once".toByteArray(), skA, bPub, pa, newKey).toString()) }
        val first = box.getOrMake("m1", 1000, make)!!
        val again = box.getOrMake("m1", 2000, make)!!
        assertEquals(1, made)
        assertEquals(first, again)
        // After a restart (nothing in memory) it comes back from the encrypted store, still the same.
        val box2 = Outbox(dir, r, w)
        assertEquals(first, box2.getOrMake("m1", 3000, make))
        assertEquals(1, made)
        // Delivered: dropped. Expired: dropped too.
        box2.drop("m1")
        assertNull(box2.get("m1", 3000))
        box2.getOrMake("m2", 0) { listOf("e") }
        assertNull(Outbox(dir, r, w).get("m2", ChatWire.DAY + 1))
        assertEquals(listOf("e2"), box2.getOrMake("m3", 0) { listOf("e2") })
        assertNull(box2.get("m3", ChatWire.DAY + 1))
        dir.deleteRecursively()
    }

    @Test
    fun duplicateDroppedBeforeRatchet() {
        val pa = Ratchet.Peer(); val pb = Ratchet.Peer()
        val e = JSONObject(ChatWire.seal(base("unsend", "u1"), "m9".toByteArray(), skA, bPub, pa, newKey).toString())
        val seen = Seen()
        val key = "$aPub|u1"
        assertNull(seen.get(key, 0))
        assertEquals("m9", String(ChatWire.open(e, skB, b, pb, newKey)!!))
        seen.add(key, "m9", 0)
        // The copy is caught by id (and says which message, to confirm again) without opening it...
        assertEquals("m9", seen.get(key, 1))
        // ...which matters: the ratchet can't open the same message twice.
        assertNull(ChatWire.open(JSONObject(e.toString()), skB, b, pb, newKey))
        assertNull(seen.get(key, 3 * ChatWire.DAY))
    }

    @Test
    fun ratchetStateSavedOnlyWhenKept() {
        val dir = tmp()
        val (r, w) = mem()
        val store = Ratchets(dir, r, w)
        val pa = Ratchet.Peer()
        val e1 = JSONObject(ChatWire.seal(base(id = "1"), "1".toByteArray(), skA, bPub, pa, newKey).toString())
        val e2 = JSONObject(ChatWire.seal(base(id = "2"), "2".toByteArray(), skA, bPub, pa, newKey).toString())
        val p = store.get(aPub)
        assertNotNull(ChatWire.open(e1, skB, b, p, newKey))
        store.save(aPub, p)
        // Opened but turned away (too many at once): forget, and it opens again later.
        assertNotNull(ChatWire.open(e2, skB, b, store.get(aPub), newKey))
        store.forget(aPub)
        assertEquals("2", String(ChatWire.open(e2, skB, b, store.get(aPub), newKey)!!))
        // A fresh store (restart) reads what was saved.
        assertEquals(1, Ratchets(dir, r, w).get(aPub).bases.size)
        store.drop(aPub)
        assertTrue(Ratchets(dir, r, w).get(aPub).bases.isEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun codeFromTheAddPageLink() {
        val x = (System.currentTimeMillis() / 1000 + 3600) * 1000
        val code = ChatWire.code("Bob", bPub, "e".repeat(64), "tok", x)
        val link = io.github.ravenor907.muninn.net.Contacts.link(code)
        assertTrue(link.startsWith("https://") && link.contains("#" + ChatWire.PREFIX))
        // The bare code, the link, the link in a message, and a link with ":" written as "%3A" all read the same.
        for (t in listOf(code, link, "Message me on Muninn:\n$link", link.replace("MUNINN1:", "MUNINN1%3a"))) {
            val c = io.github.ravenor907.muninn.net.Contacts.parse(t)
            assertNotNull(t, c); assertEquals(bPub, c!!.chatKey); assertEquals("tok", c.invite)
            assertEquals(x, io.github.ravenor907.muninn.net.Contacts.expires(t))
        }
        assertNull(io.github.ravenor907.muninn.net.Contacts.parse(io.github.ravenor907.muninn.net.Contacts.PAGE))
    }

    @Test
    fun nicknameNeverSent() {
        val c = Contact(bPub, "Bob", "e".repeat(64), true, nickname = "Grandpa Zed")
        assertEquals("Grandpa Zed", c.shown)
        val env = ChatWire.seal(base(), "hi".toByteArray(), skA, bPub, null, newKey).toString() +
            ChatWire.seal(base(), "hi".toByteArray(), skA, bPub, Ratchet.Peer(), newKey).toString()
        assertFalse(env.contains("Grandpa"))
        val code = ChatWire.code("Bob", bPub, "e".repeat(64), "tok", 0)
        assertFalse(String(java.util.Base64.getUrlDecoder().decode(code.removePrefix(ChatWire.PREFIX))).contains("Grandpa"))
        // Kept on this phone with the contact.
        assertEquals(c, ChatWire.contactFromJson(JSONObject(ChatWire.contactToJson(c).toString())))
        assertNull(ChatWire.nickname("   "))
        assertEquals("Zed", ChatWire.nickname(" Zed "))
        assertTrue(ChatWire.matches(c, "grandpa")); assertTrue(ChatWire.matches(c, "bob")); assertFalse(ChatWire.matches(c, "alice"))
        assertEquals("Grandpa Zed (Bob)", ChatWire.listName(c))
        assertEquals("Bob", ChatWire.listName(c.copy(nickname = null)))
    }

    @Test
    fun keyChange() {
        val bob = Contact(bPub, "Bob", "e".repeat(64), true, ratchet = true, nickname = "Dad")
        val other = Contact(aPub, "Sam", null, true)
        val all = listOf(bob, other)
        val newKey = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        // Same key: not a change.
        assertNull(ChatWire.keyChangeFor(all, bob.copy(verified = false)))
        // Same mailbox, new key.
        assertEquals(bob, ChatWire.keyChangeFor(all, Contact(newKey, "Robert", "e".repeat(64), true)))
        // Same name, other key: a new, separate person (names are spoofable), with or without a mailbox.
        assertNull(ChatWire.keyChangeFor(all, Contact(newKey, "Bob", "d".repeat(64), true)))
        assertNull(ChatWire.keyChangeFor(all, Contact(newKey, "bob", null, true)))
        assertNull(ChatWire.keyChangeFor(all, Contact(newKey, "Carol", null, true)))
        val after = ChatWire.afterKeyChange(bob, Contact(newKey, "Bob", "d".repeat(64), true, invite = "t"))
        assertEquals(newKey, after.chatKey)
        assertFalse(after.verified); assertFalse(after.ratchet); assertTrue(after.keyChanged)
        assertEquals("Dad", after.nickname)
        // Re-adding the same code doesn't quietly verify it: only Check code does.
        val again = ChatWire.merge(after, Contact(newKey, "Bob", "d".repeat(64), true))
        assertFalse(again.verified); assertTrue(again.keyChanged)
        assertEquals("Dad has a new phone or reinstalled Muninn. Compare 4 emoji with them to be sure it's really Dad.", ChatWire.keyChangedText(after.shown))
    }
}
