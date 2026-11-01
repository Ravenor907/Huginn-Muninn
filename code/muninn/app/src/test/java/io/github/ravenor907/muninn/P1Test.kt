package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.ProfileWire
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.Rich
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** shared/vectors/p1-vectors.json (section 22, cap p1: profile pictures and status), the same vectors Huginn checks. */
class P1Test {
    private val v = JSONObject(javaClass.classLoader!!.getResource("p1-vectors.json")!!.readText())

    @Test
    fun capAndRooms() {
        assertTrue(ProfileWire.CAP in Proto06.caps(Proto06.CAPS))
        assertEquals("p1", Proto06.CAPS.split(' ').last())
        assertTrue("profile" in ChatWire.RATCHET_ROOMS)
        assertTrue("profile" in ChatWire.SEEN_ROOMS)
        val l = v.getJSONObject("limits")
        assertEquals(l.getInt("maxBytes"), ProfileWire.MAX_BYTES)
        assertEquals(l.getInt("sendMaxBytes"), ProfileWire.SEND_MAX_BYTES)
        assertEquals(l.getInt("side"), ProfileWire.SIDE)
        assertEquals(l.getInt("maxCustom"), Rich.MAX_CUSTOM)
        assertEquals(l.getInt("ratePer10s"), 1)
        val q = l.getJSONArray("qualities")
        assertEquals((0 until q.length()).map { q.getInt(it) }, ProfileWire.QUALITIES.toList())
        val pal = l.getJSONArray("palette")
        assertEquals((0 until pal.length()).map { pal.getString(it) }, ProfileWire.PALETTE)
    }

    @Test
    fun picturesSizeAndShape() {
        val pics = v.getJSONObject("pictures")
        for (name in pics.keys()) {
            val c = pics.getJSONArray(name)
            val bytes = Base64.getDecoder().decode(c.getString(0))
            assertEquals(name, c.getInt(3), bytes.size)
            val size = ProfileWire.jpegSize(bytes)
            if (c.getInt(1) == 0) assertNull(name, size) else assertEquals(name, c.getInt(1) to c.getInt(2), size)
            assertEquals(name, c.getBoolean(4), ProfileWire.pictureOk(bytes))
        }
    }

    @Test
    fun profileMessages() {
        val p = v.getJSONObject("profile")
        val good = p.getJSONArray("good")
        for (i in 0 until good.length()) assertTrue("good $i", ProfileWire.ok(good.getJSONObject(i)))
        val bad = p.getJSONArray("refuse")
        for (i in 0 until bad.length()) assertFalse("refuse ${bad.getJSONObject(i).toString().take(80)}", ProfileWire.ok(bad.getJSONObject(i)))
        // The pictures over the cap, as a message.
        val pics = v.getJSONObject("pictures")
        for (name in listOf("tooBig", "atCapNotJpeg", "small"))
            assertFalse(name, ProfileWire.ok(JSONObject(ProfileWire.msg(1, pics.getJSONArray(name).getString(0), "#3a7bd5"))))
        val mine = JSONObject(ProfileWire.msg(4, pics.getJSONArray("ok").getString(0), "#3a7bd5"))
        assertTrue(ProfileWire.ok(mine))
        assertEquals("profile", mine.getString("t")); assertEquals(4, mine.getInt("v"))
        assertTrue(ProfileWire.ok(JSONObject(ProfileWire.msg(5, "", "#3a7bd5"))))
        val vers = p.getJSONArray("versions")
        for (i in 0 until vers.length()) vers.getJSONArray(i).let { c -> assertEquals(c.toString(), c.getBoolean(2), ProfileWire.takes(c.getLong(0), c.getLong(1))) }
    }

    @Test
    fun statusOnTheLiveMessage() {
        val l = v.getJSONObject("live")
        val good = l.getJSONArray("good")
        for (i in 0 until good.length()) assertTrue("good $i", Rich.ok("live", good.getJSONObject(i)))
        val bad = l.getJSONArray("refuse")
        for (i in 0 until bad.length()) assertFalse("refuse ${bad.getJSONObject(i)}", Rich.ok("live", bad.getJSONObject(i)))
        val sent = l.getJSONArray("sent")
        for (i in 0 until sent.length()) {
            val c = sent.getJSONArray(i)
            val want = c.getJSONObject(3)
            val got = JSONObject(Rich.activeMsg(c.getBoolean(0), 1, c.getString(1), c.getString(2)))
            assertEquals(c.toString(), want.keys().asSequence().toSet(), got.keys().asSequence().toSet())
            for (k in want.keys()) assertEquals(c.toString() + " " + k, want.get(k), got.get(k))
            assertTrue(Rich.ok("live", got))
        }
        val shown = l.getJSONArray("shown")
        for (i in 0 until shown.length()) shown.getJSONArray(i).let { c ->
            assertEquals(c.toString(), c.getString(3), ProfileWire.statusOf(c.getBoolean(0), c.getString(1), c.getBoolean(2)))
        }
        // An app without p1 reads the same message as before: t, on and ts are all it looks at.
        val plain = JSONObject(Rich.liveMsg("active", true, 7))
        assertEquals(setOf("t", "on", "ts"), plain.keys().asSequence().toSet())
        assertEquals("Lunch", ProfileWire.cleanCustom("  Lunch\u0007 "))
        assertEquals(80, ProfileWire.cleanCustom("é".repeat(100)).length)
    }

    @Test
    fun coloursAndTheFallback() {
        val c = v.getJSONObject("colours")
        val good = c.getJSONArray("good")
        for (i in 0 until good.length()) assertTrue(good.getString(i), ProfileWire.colourOk(good.getString(i)))
        val bad = c.getJSONArray("refuse")
        for (i in 0 until bad.length()) assertFalse(bad.getString(i), ProfileWire.colourOk(bad.getString(i)))
        assertFalse(ProfileWire.colourOk(null)); assertFalse(ProfileWire.colourOk(7))
        val d = c.getJSONArray("default")
        for (i in 0 until d.length()) d.getJSONArray(i).let { x -> assertEquals(x.getString(0), x.getString(1), ProfileWire.defaultColour(x.getString(0))) }
    }

    @Test
    fun aNewNameGoesWithTheProfile() {
        val with = JSONObject(ProfileWire.msg(2, "", "#c9a964", "  Astrid  "))
        assertTrue(ProfileWire.ok(with))
        assertEquals("Astrid", ProfileWire.nameOf(with))
        // No name: the message is exactly what it was before names went with it.
        assertEquals(JSONObject().put("t", "profile").put("v", 2).put("pic", "").put("col", "#c9a964").toString(), ProfileWire.msg(2, "", "#c9a964"))
        assertNull(ProfileWire.nameOf(JSONObject(ProfileWire.msg(2, "", "#c9a964"))))
        assertNull(ProfileWire.nameOf(JSONObject().put("nm", "   ")))
        assertNull(ProfileWire.nameOf(JSONObject().put("nm", "a".repeat(41))))
        assertNull(ProfileWire.nameOf(JSONObject().put("nm", "bad\u0001name")))
        assertNull(ProfileWire.nameOf(JSONObject().put("nm", 5)))
    }
}
