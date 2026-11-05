package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.MoveWire
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.tv.TvReconnect
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** shared/vectors/p95-vectors.json (sections 7.11, 17 and 18): the same vectors Huginn checks, so both apps agree byte for byte. */
class P95VectorsTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("p95-vectors.json")!!.readText())
    private val m = v.getJSONObject("move")
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun key(): ByteArray = MoveWire.key(ChatCrypto.privateFrom(m.getString("newPriv")), m.getString("oldPub"),
        Base64.getUrlDecoder().decode(m.getString("secret")), m.getString("newPub"), m.getString("oldPub"))

    @Test
    fun bothSidesGetTheSameKeyAndEmoji() {
        val k = key()
        assertEquals(m.getString("key"), hex(k))
        val k2 = MoveWire.key(ChatCrypto.privateFrom(m.getString("oldPriv")), m.getString("newPub"),
            Base64.getUrlDecoder().decode(m.getString("secret")), m.getString("newPub"), m.getString("oldPub"))
        assertArrayEquals(k, k2)
        assertEquals(m.getString("emoji"), MoveWire.emoji(k))
        assertEquals(m.getString("helloMac"), MoveWire.helloMac(k, m.getString("oldPub")))
        assertEquals(m.getString("readyMac"), MoveWire.readyMac(k, m.getString("oldPub")))
        assertEquals(m.getString("statusDone"), MoveWire.statusMac(k, "done", m.getString("sha")))
    }

    @Test
    fun theCodeAndThePayload() {
        val c = MoveWire.parse("scan this: " + m.getString("code"))
        assertNotNull(c)
        val f = m.getJSONObject("codeFields")
        assertEquals(f.getString("e"), c!!.e)
        assertEquals(f.getString("n"), c.n)
        assertEquals(listOf("192.168.1.20:53317"), c.a)
        assertNull(MoveWire.parse("MUNINNM1:nothing"))
        val blob = Base64.getDecoder().decode(m.getString("payload"))
        val entries = MoveWire.unpack(blob)!!
        val want = m.getJSONArray("entries")
        assertEquals(want.length(), entries.size)
        for (i in 0 until want.length()) {
            assertEquals(want.getJSONArray(i).getString(0), entries[i].first)
            assertArrayEquals(Base64.getDecoder().decode(want.getJSONArray(i).getString(1)), entries[i].second)
        }
        assertArrayEquals(blob, MoveWire.pack(entries))
        assertEquals(m.getString("sha"), MoveWire.sha256Hex(blob))
        val lan = m.getJSONArray("lan")
        for (i in 0 until lan.length()) assertEquals(lan.getJSONArray(i).getString(0), lan.getJSONArray(i).getBoolean(1), MoveWire.lanAddr(lan.getJSONArray(i).getString(0)))
        val nets = m.getJSONArray("sameNetwork")
        for (i in 0 until nets.length()) nets.getJSONArray(i).let { c -> assertEquals(c.toString(), c.getBoolean(3), MoveWire.sameNetwork(c.getString(0), c.getString(1), c.getInt(2))) }
        val counts = m.getJSONArray("partCounts")
        for (i in 0 until counts.length()) assertEquals(counts.getJSONArray(i).getInt(1), MoveWire.parts(counts.getJSONArray(i).getLong(0)))
    }

    @Test
    fun partsAreSealedForTheirPlace() {
        val k = key()
        val blob = Base64.getDecoder().decode(m.getString("payload"))
        val nonce = Base64.getDecoder().decode(m.getString("nonce"))
        assertEquals(m.getString("part0"), MoveWire.seal(k, blob, m.getString("aad0"), nonce))
        assertArrayEquals(blob, MoveWire.open(k, m.getString("part0"), m.getString("aad0")))
        assertNull(MoveWire.open(k, m.getString("part0"), MoveWire.partAad(1, m.getInt("parts"), m.getString("sha"))))
        assertEquals(m.getString("linkText"), String(MoveWire.open(k, m.getString("link"), MoveWire.LINK_AAD)!!))
    }

    @Test
    fun linkIdsAndWhetherALinkLetsSomeoneIn() {
        val l = v.getJSONObject("links")
        val ids = l.getJSONArray("ids")
        for (i in 0 until ids.length()) assertEquals(ids.getJSONArray(i).getString(1), Proto06.linkId(ids.getJSONArray(i).getString(0)))
        val ok = l.getJSONArray("ok")
        for (i in 0 until ok.length()) {
            val c = ok.getJSONArray(i)
            assertEquals(c.toString(), c.getBoolean(7), Proto06.linkOk(c.getLong(0), c.getInt(1), c.getInt(2), c.getBoolean(3), c.getBoolean(4), c.getBoolean(5), c.getLong(6)))
        }
    }

    @Test
    fun membersCanInviteIsOnlyEverTrue() {
        val p = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText())
        val s = p.getJSONObject("group").getJSONObject("states").getJSONObject("s1").getJSONObject("state")
        val on = Proto06.parseState(JSONObject(s.toString()).put("mi", true))
        Proto06.check(on)
        assertTrue(on.mi)
        assertTrue(on.canonSigned().contains("\"mi\":true"))
        assertFalse(Proto06.parseState(s).mi)
        for (bad in listOf<Any>(false, 1, "yes")) {
            val r = runCatching { Proto06.parseState(JSONObject(s.toString()).put("mi", bad)) }
            assertTrue("mi $bad", r.isFailure)
        }
    }

    @Test
    fun kidsTvReconnectMessages() {
        val t = v.getJSONObject("tv")
        val good = t.getJSONArray("good")
        for (i in 0 until good.length()) assertTrue(good.get(i).toString(), TvReconnect.ok(good.getJSONObject(i)))
        val bad = t.getJSONArray("refuse")
        for (i in 0 until bad.length()) assertFalse(bad.get(i).toString(), TvReconnect.ok(bad.getJSONObject(i)))
        val due = t.getJSONArray("due")
        for (i in 0 until due.length()) {
            val c = due.getJSONArray(i)
            assertEquals(c.toString(), c.getBoolean(3), TvReconnect.takeoverDue(c.getLong(0), c.getLong(1), c.getBoolean(2)))
        }
    }
}
