package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.Link
import io.github.ravenor907.muninn.net.Trust
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** shared/vectors/i1f1-vectors.json (sections 13 to 15): the same vectors Huginn checks, so both apps agree byte for byte. */
class I1F1VectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("i1f1-vectors.json")!!.readText())

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun shared(): ByteArray {
        val p = v.getJSONObject("pair")
        return ChatCrypto.sharedKey(ChatCrypto.privateFrom(p.getString("aPriv")), ChatCrypto.publicFrom(p.getString("aPub")), ChatCrypto.publicFrom(p.getString("bPub")))
    }

    @Test
    fun emojiAndGroupIds() {
        assertEquals(v.getJSONObject("pair").getString("sharedHex"), hex(shared()))
        val e = v.getJSONArray("emoji")
        for (i in 0 until e.length()) { val c = e.getJSONObject(i); assertEquals(c.getString("emoji"), ChatCrypto.emojiCode(unhex(c.getString("keyHex")))) }
        val g = v.getJSONArray("groupId")
        for (i in 0 until g.length()) { val c = g.getJSONObject(i); assertEquals(c.getString("id"), Trust.groupId(c.getString("g"), c.getString("a"), c.getString("b"))) }
    }

    @Test
    fun introsAreChecked() {
        val o = v.getJSONObject("intro")
        val good = o.getJSONArray("good")
        for (i in 0 until good.length()) assertTrue(good.get(i).toString(), Trust.ok(good.getJSONObject(i)))
        val bad = o.getJSONArray("bad")
        for (i in 0 until bad.length()) assertFalse(bad.get(i).toString(), Trust.ok(bad.getJSONObject(i)))
    }

    @Test
    fun filePacketsAndAcks() {
        val f = v.getJSONObject("file")
        val id = f.getString("id")
        val key = FileLink.key(shared(), id)
        assertEquals(f.getString("keyHex"), hex(key))
        val ps = f.getJSONArray("pieces")
        for (i in 0 until ps.length()) assertEquals(ps.getJSONArray(i).getInt(1), FileLink.pieces(ps.getJSONArray(i).getLong(0)))
        val pk = f.getJSONArray("packets")
        for (i in 0 until pk.length()) {
            val c = pk.getJSONObject(i)
            val payload = unhex(c.getString("payloadHex"))
            val p = FileLink.seal(key, id, c.getInt("type").toByte(), c.getInt("dir"), c.getLong("seq"), payload)
            assertEquals(c.getString("packetHex"), hex(p))
            val (t, back) = FileLink.open(key, id, c.getInt("dir"), p)!!
            assertEquals(c.getInt("type").toByte(), t)
            assertArrayEquals(payload, back)
            assertNull(FileLink.open(key, id, 1 - c.getInt("dir"), p))
        }
        val acks = f.getJSONArray("acks")
        for (i in 0 until acks.length()) {
            val c = acks.getJSONObject(i)
            val r = FileLink.Receiver(c.getInt("n"))
            val have = c.getJSONArray("have")
            for (j in 0 until have.length()) r.take(have.getInt(j))
            assertEquals(c.getInt("below") to c.getLong("bits"), r.ack())
        }
        val good = f.getJSONArray("good")
        for (i in 0 until good.length()) assertTrue(good.get(i).toString(), FileLink.ok(good.getJSONObject(i)))
        val bad = f.getJSONArray("bad")
        for (i in 0 until bad.length()) assertFalse(bad.get(i).toString(), FileLink.ok(bad.getJSONObject(i)))
    }

    @Test
    fun linkCodesCarryKeyMailboxNameAndToken() {
        val good = v.getJSONObject("link").getJSONArray("good")
        for (i in 0 until good.length()) {
            val c = good.getJSONObject(i)
            val code = c.getString("code")
            val body = code.substring(code.indexOf(Link.PREFIX) + Link.PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            val o = JSONObject(String(java.util.Base64.getUrlDecoder().decode(body)))
            val r = c.getJSONObject("read")
            for (k in listOf("k", "p", "n", "i")) assertEquals(r.getString(k), o.getString(k))
        }
    }
}
