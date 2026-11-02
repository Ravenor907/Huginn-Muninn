package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Rich
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** shared/vectors/m1-vectors.json (cap m1: replies, reactions, edits, typing), which Huginn checks too. */
class RichTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("m1-vectors.json")!!.readText())

    @Test
    fun goodAndRefused() {
        val good = v.getJSONObject("good")
        var n = 0
        for (room in good.keys()) {
            val a = good.getJSONArray(room)
            for (i in 0 until a.length()) { Rich.check(room, a.getJSONObject(i)); n++ }
        }
        val bad = v.getJSONObject("refuse")
        for (room in bad.keys()) {
            val a = bad.getJSONArray(room)
            for (i in 0 until a.length()) { assertFalse("should refuse $room ${a.getJSONObject(i)}", Rich.ok(room, a.getJSONObject(i))); n++ }
        }
        assertTrue(n > 40)
    }

    @Test
    fun reactionsSettle() {
        val a = v.getJSONArray("reactions")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            var all = emptyMap<String, Rich.Reaction>()
            val ev = c.getJSONArray("events")
            for (j in 0 until ev.length()) ev.getJSONObject(j).let { e -> Rich.react(all, e.getString("from"), e.getString("e"), e.getLong("ts"))?.let { all = it } }
            val want = c.getJSONObject("result").let { r -> r.keys().asSequence().associateWith { r.getString(it) } }
            assertEquals("case $i", want, Rich.shown(all))
        }
    }

    @Test
    fun editsSettle() {
        val a = v.getJSONArray("edits")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val author = c.getString("author")
            var text = c.getString("text"); var edited = false; var at = 0L
            val ev = c.getJSONArray("events")
            for (j in 0 until ev.length()) ev.getJSONObject(j).let { e ->
                if (Rich.editWins(author, e.getString("from"), e.getLong("ts"), at)) { text = e.getString("text"); edited = true; at = e.getLong("ts") }
            }
            val r = c.getJSONObject("result")
            assertEquals("case $i", r.getString("text"), text)
            assertEquals("case $i", r.getBoolean("edited"), edited)
            assertEquals("case $i", r.getLong("ts"), at)
        }
    }

    @Test
    fun freshness() {
        val a = v.getJSONArray("fresh")
        for (i in 0 until a.length()) a.getJSONObject(i).let { assertEquals("case $i", it.getBoolean("ok"), Rich.fresh(it.getLong("ts"), it.getLong("now"))) }
    }

    @Test
    fun mentions() {
        assertTrue(Rich.mentions("hey @Raven look", "Raven"))
        assertTrue(Rich.mentions("@raven", "Raven"))
        assertTrue(Rich.mentions("thanks @Raven!", "Raven"))
        assertFalse(Rich.mentions("@Ravenor here", "Raven"))
        assertFalse(Rich.mentions("mail@Raven.com", "Raven"))
        assertFalse(Rich.mentions("no mention", "Raven"))
    }

    @Test
    fun mentionsByNicknameAndEveryoneFromAnAdmin() {
        assertTrue(Rich.mentionsMe("hey @tay", "Taylor B", "tay, T", fromAdmin = false))
        assertTrue(Rich.mentionsMe("@T come on", "Taylor B", "tay, T", fromAdmin = false))
        assertFalse(Rich.mentionsMe("@Tom here", "Taylor B", "tay, T", fromAdmin = false))
        assertFalse(Rich.mentionsMe("hi", "Taylor B", "", fromAdmin = false))
        assertTrue(Rich.mentionsMe("@everyone game night", "Taylor B", "", fromAdmin = true))
        assertFalse(Rich.mentionsMe("@everyone game night", "Taylor B", "", fromAdmin = false))
    }
}
