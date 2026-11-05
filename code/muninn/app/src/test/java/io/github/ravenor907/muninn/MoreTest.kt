package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.More
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** shared/vectors/m2-vectors.json (cap m2: albums, video messages, polls and link previews). */
class MoreTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("m2-vectors.json")!!.readText())

    private fun each(o: JSONObject, f: (String, JSONObject) -> Unit) {
        for (room in o.keys()) { val a = o.getJSONArray(room); for (i in 0 until a.length()) f(room, a.getJSONObject(i)) }
    }

    @Test
    fun wellFormedPayloadsPass() = each(v.getJSONObject("good")) { room, p -> assertTrue("$room $p", More.ok(room, p)) }

    @Test
    fun malformedPayloadsAreRefused() = each(v.getJSONObject("refuse")) { room, p -> assertFalse("$room $p", More.ok(room, p)) }

    @Test
    fun votesSettleInAnyOrder() {
        val a = v.getJSONArray("votes")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val n = c.getInt("n")
            var all = emptyMap<String, More.Vote>()
            val ev = c.getJSONArray("events")
            for (j in 0 until ev.length()) {
                val e = ev.getJSONObject(j)
                val vs = e.getJSONArray("v").let { x -> (0 until x.length()).map { x.getInt(it) } }
                More.vote(all, n, e.getString("from"), vs, e.getLong("ts"))?.let { all = it }
            }
            val want = c.getJSONArray("result").let { x -> (0 until x.length()).map { x.getInt(it) } }
            assertEquals(c.toString(), want, More.counts(all, n))
        }
    }

    @Test
    fun pollTextForAppsWithoutM2() {
        val a = v.getJSONArray("fallback")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val o = c.getJSONArray("a").let { x -> (0 until x.length()).map { x.getString(it) } }
            assertEquals(c.getString("text"), More.fallback(c.getString("q"), o))
        }
    }

    @Test
    fun videoIsAnMp4() {
        val a = v.getJSONArray("video")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val hex = c.getString("hex")
            val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            assertEquals(hex, c.getBoolean("ok"), More.isVideo(bytes))
        }
    }

    @Test
    fun pollAndPreviewRoundTrip() {
        val poll = More.Poll("Pizza or tacos?", listOf("Pizza", "Tacos"), multi = true)
        val p = JSONObject().put("text", More.fallback(poll.q, poll.options)).put("poll", More.pollJson(poll))
        assertTrue(More.ok("rich", p))
        assertEquals(poll, More.pollOf(p))
        val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val lp = More.Preview("https://example.com/x", "Example", "Words", thumb)
        val q = JSONObject().put("text", "see https://example.com/x").put("lp", More.previewJson(lp))
        assertTrue(More.ok("rich", q))
        val back = More.previewOf(q)!!
        assertEquals("Example", back.title)
        assertTrue(back.thumb!!.contentEquals(thumb))
        assertEquals(JSONArray(listOf(0, 2)).toString(), JSONObject(More.voteMsg("m", listOf(2, 0, 2), 5)).getJSONArray("v").toString())
    }

    @Test
    fun linksInText() {
        assertEquals("https://example.com/a?b=c", More.firstLink("Look at https://example.com/a?b=c."))
        assertEquals("http://x.org/p", More.firstLink("(see http://x.org/p)"))
        assertNull(More.firstLink("no link here"))
        assertEquals("example.com", More.host("https://www.example.com/path"))
    }
}
