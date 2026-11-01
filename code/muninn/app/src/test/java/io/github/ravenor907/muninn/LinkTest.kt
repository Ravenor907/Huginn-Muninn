package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Link
import io.github.ravenor907.muninn.net.LinkWire
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Linking by QR (protocol.md section 15): history pages and merging, the media checks, the LAN-only rule, code refresh. */
class LinkTest {
    private fun item(id: String, t: Long, x: String = "hi") = JSONObject().put("id", id).put("x", x).put("me", false).put("t", t)

    @Test
    fun historyGoesInPagesOfAtMost100OldestFirst() {
        val items = (0 until 250).map { item("m$it", it.toLong()) }
        val pages = LinkWire.pages(items)
        assertEquals(listOf(100, 100, 50), pages.map { it.size })
        assertEquals("m0", pages[0][0].getString("id"))
        assertEquals("m249", pages[2].last().getString("id"))
        // Long messages make smaller pages, never one too big for an envelope.
        val long = (0 until 100).map { item("l$it", it.toLong(), "x".repeat(LinkWire.TEXT_MAX)) }
        val lp = LinkWire.pages(long)
        assertTrue(lp.size > 1)
        assertTrue(lp.all { p -> p.sumOf { it.toString().length } <= LinkWire.PAGE_CHARS })
        assertEquals(100, lp.sumOf { it.size })
        assertTrue(LinkWire.pages(emptyList()).isEmpty())
    }

    @Test
    fun pagesMergeByIdAndNeverWipe() {
        val a = listOf(Link.M("1", "one", false, 1), Link.M("2", "two", true, 2))
        val b = listOf(Link.M("3", "three", false, 3))
        val merged = LinkWire.merge(LinkWire.merge(emptyList(), a), b)
        assertEquals(listOf("1", "2", "3"), merged.map { it.id })
        // A later copy of a message replaces it; an empty page changes nothing.
        val again = LinkWire.merge(merged, listOf(Link.M("2", "Message deleted", true, 2)))
        assertEquals("Message deleted", again.first { it.id == "2" }.text)
        assertEquals(3, LinkWire.merge(again, emptyList()).size)
    }

    @Test
    fun mediaIsCheckedBySha256AndSize() {
        val b = ByteArray(500_000) { (it * 7).toByte() }
        val h = LinkWire.sha256Hex(b)
        assertEquals(64, h.length)
        assertTrue(LinkWire.verified(b, h, b.size.toLong()))
        assertFalse(LinkWire.verified(b, h, b.size + 1L))
        val bad = b.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFalse(LinkWire.verified(bad, h, b.size.toLong()))
        // Pieces of 192 KB put back together.
        val parts = LinkWire.chunks(b)
        assertEquals(LinkWire.pieces(b.size.toLong()), parts.size)
        assertTrue(parts.all { it.size <= LinkWire.CHUNK })
        val f = Link.Media(h, b.size.toLong(), "photo", "photo.jpg")
        val a = LinkWire.Assembly(f)
        parts.withIndex().reversed().forEach { (i, p) -> assertTrue(a.add(i, parts.size, p)) }
        assertArrayEquals(b, a.result())
        // A piece that doesn't belong, or a changed one, never makes a file.
        assertFalse(LinkWire.Assembly(f).add(0, parts.size + 1, parts[0]))
        val wrong = LinkWire.Assembly(f)
        parts.forEachIndexed { i, p -> wrong.add(i, parts.size, if (i == 0) p.copyOf().also { it[0] = (it[0] + 1).toByte() } else p) }
        assertNull(wrong.result())
    }

    @Test
    fun mediaFieldsAreValidated() {
        val h = "a".repeat(64)
        assertNotNull(LinkWire.media(JSONObject().put("h", h).put("s", 10).put("k", "photo").put("nm", "photo.jpg")))
        assertNull(LinkWire.media(JSONObject().put("h", "A".repeat(64)).put("s", 10).put("k", "photo")))
        assertNull(LinkWire.media(JSONObject().put("h", h).put("s", LinkWire.MAX_MEDIA + 1).put("k", "photo")))
        assertNull(LinkWire.media(JSONObject().put("h", h).put("s", 10).put("k", "exe")))
        assertNull(LinkWire.media(null))
    }

    @Test
    fun mediaOnlyOnTheHomeWifi() {
        assertTrue(LinkWire.lanOk("wifi", directOn = false))
        assertFalse(LinkWire.lanOk("wifi", directOn = true))
        assertFalse(LinkWire.lanOk("internet", directOn = false))
        assertFalse(LinkWire.lanOk("close", directOn = false))
        assertFalse(LinkWire.lanOk("", directOn = false))
    }

    @Test
    fun codeIsMadeAnewBeforeItRunsOut() {
        val t0 = 1_000_000L
        assertFalse(LinkWire.refreshDue(t0, t0))
        assertFalse(LinkWire.refreshDue(t0, t0 + Link.TOKEN_MS - LinkWire.REFRESH_EARLY_MS - 1))
        assertTrue(LinkWire.refreshDue(t0, t0 + Link.TOKEN_MS - LinkWire.REFRESH_EARLY_MS))
        assertTrue(LinkWire.REFRESH_EARLY_MS in 1 until Link.TOKEN_MS)
    }

    private class Fake : Link.Host {
        val key: String = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val files = HashMap<String, String>()
        val bytes = HashMap<String, ByteArray>()
        val sent = java.util.Collections.synchronizedList(ArrayList<String>())
        val lanSent = java.util.Collections.synchronizedList(ArrayList<String>())
        @Volatile var lan = true
        override fun myKey() = key
        override fun myMailbox() = "ab".repeat(32)
        override fun myName() = "Tablet"
        override fun send(d: Link.Device, json: String) { sent += json }
        override fun chats(): Map<String, Pair<String, List<Chat.Msg>>> = emptyMap()
        override fun sendAsMe(peer: String, text: String) = false
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun notice(text: String) {}
        override fun onLan(key: String) = lan
        override fun sendLan(d: Link.Device, json: String): Boolean { if (lan) lanSent += json; return lan }
        override fun readBytes(name: String) = bytes[name]
        override fun writeBytes(name: String, b: ByteArray) { bytes[name] = b }
        override fun delete(name: String) { bytes.remove(name) }
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(what())
    }

    @Test
    fun aTabletLinksMergesPagesAndTakesMediaOnlyOnTheLan() {
        val host = Fake()
        Link.host = host
        val code = Link.myLinkCode()
        assertTrue(Link.isCode(code))
        val token = JSONObject(String(java.util.Base64.getUrlDecoder().decode(code.removePrefix(Link.PREFIX)))).getString("i")
        // A newer code doesn't end the one already on screen.
        Link.myLinkCode()
        val phone = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        assertFalse(Link.receive(phone, JSONObject().put("t", "hello").put("i", "wrong")))
        assertTrue(Link.receive(phone, JSONObject().put("t", "hello").put("i", token).put("n", "Phone")))
        assertEquals(phone, Link.phone.value?.key)
        assertFalse(Link.phone.value!!.ok)
        // The token is used up.
        val other = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        assertFalse(Link.receive(other, JSONObject().put("t", "hello").put("i", token)))

        val now = System.currentTimeMillis()
        fun page(i: Int, n: Int, vararg ids: String) = JSONObject().put("t", "chat").put("peer", "P").put("n", "Astrid").put("d", 7).put("pg", i).put("pgs", n)
            .put("msgs", JSONArray().apply { ids.forEachIndexed { k, id -> put(item(id, now - 1000 + k + i * 10)) } })
        // Nothing shows before the emoji were checked.
        Link.receive(phone, page(0, 1, "a"))
        assertTrue(Link.mirror.value.isEmpty())
        Link.confirm(phone)
        assertTrue(Link.phone.value!!.ok)
        assertTrue(host.sent.any { JSONObject(it).optString("t") == "sync" })
        Link.receive(phone, page(0, 2, "a", "b"))
        Link.receive(phone, page(1, 2, "c"))
        assertEquals(listOf("a", "b", "c"), Link.mirror.value["P"]!!.msgs.map { it.id })

        // A photo: asked for on the LAN, its pieces taken only from the LAN, kept once its SHA-256 checks out.
        val photo = ByteArray(300_000) { (it % 251).toByte() }
        val h = LinkWire.sha256Hex(photo)
        val m = item("p", now).put("x", "Photo").put("f", JSONObject().put("h", h).put("s", photo.size).put("k", "photo").put("nm", "photo.jpg"))
        Link.receive(phone, JSONObject().put("t", "msg").put("peer", "P").put("n", "Astrid").put("m", m).put("d", 7))
        waitFor { host.lanSent.any { JSONObject(it).optString("t") == "get" && JSONObject(it).optString("h") == h } }
        val parts = LinkWire.chunks(photo)
        fun part(i: Int) = JSONObject().put("t", "part").put("h", h).put("i", i).put("n", parts.size).put("b", java.util.Base64.getEncoder().encodeToString(parts[i]))
        parts.indices.forEach { Link.receive(phone, part(it), lan = false) }
        assertFalse(h in Link.stored.value)
        parts.indices.forEach { Link.receive(phone, part(it), lan = true) }
        assertTrue(h in Link.stored.value)
        assertArrayEquals(photo, Link.mediaBytes(h))
        assertEquals(4, Link.mirror.value["P"]!!.msgs.size)

        // Unlinking takes the phone's chats and media away.
        Link.unlink(phone)
        assertNull(Link.phone.value)
        assertTrue(Link.mirror.value.isEmpty())
        assertTrue(Link.stored.value.isEmpty())
        assertTrue(host.bytes.isEmpty())
        Link.host = null
    }
}
