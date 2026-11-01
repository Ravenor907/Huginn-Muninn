package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.tv.TvInstalls
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parent-approved installs on a Kids' TV (k9, 7.15): the messages, and who the TV takes an answer from. */
class TvInstallsTest {
    private val h = "ab".repeat(32)
    private val ask = TvInstalls.Ask(h, "game.apk", 12_345, "com.example.game", "1.2", listOf("content://x/1" to h), at = 1_000)

    @Test
    fun anAskRoundTrips() {
        val back = TvInstalls.parseAsk(JSONObject(TvInstalls.askMessage(ask)))!!
        assertEquals(ask.copy(parts = emptyList(), at = 0), back)
        // Without package and version too.
        val bare = TvInstalls.parseAsk(JSONObject(TvInstalls.askMessage(ask.copy(pkg = "", ver = ""))))!!
        assertEquals("", bare.pkg)
    }

    @Test
    fun malformedAsksAreRefused() {
        fun bad(o: JSONObject) = assertNull(o.toString(), TvInstalls.parseAsk(o))
        val good = JSONObject(TvInstalls.askMessage(ask))
        bad(JSONObject(good.toString()).put("h", "AB".repeat(32)))
        bad(JSONObject(good.toString()).put("h", "ab"))
        bad(JSONObject(good.toString()).put("name", "../evil.apk"))
        bad(JSONObject(good.toString()).put("name", ""))
        bad(JSONObject(good.toString()).put("size", 0))
        bad(JSONObject(good.toString()).put("size", "12"))
        bad(JSONObject(good.toString()).put("t", "appok"))
        // A bad package name is dropped, not the whole ask.
        assertEquals("", TvInstalls.parseAsk(JSONObject(good.toString()).put("pkg", "not a package"))!!.pkg)
    }

    @Test
    fun answersAndResults() {
        assertEquals(h to true, TvInstalls.parseAnswer(JSONObject(TvInstalls.answerMessage(h, true))))
        assertEquals(h to false, TvInstalls.parseAnswer(JSONObject(TvInstalls.answerMessage(h, false))))
        assertNull(TvInstalls.parseAnswer(JSONObject().put("t", "appok").put("h", "x")))
        assertEquals(Triple(h, "installed", ""), TvInstalls.parseResult(JSONObject(TvInstalls.resultMessage(h, "installed"))))
        assertEquals(Triple(h, "failed", "No room"), TvInstalls.parseResult(JSONObject(TvInstalls.resultMessage(h, "failed", "No room"))))
        assertNull(TvInstalls.parseResult(JSONObject().put("t", "appres").put("h", h).put("r", "maybe")))
        assertEquals("Installed", TvInstalls.resultText("installed"))
        assertEquals("Needs Install updates permission", TvInstalls.resultText("perm"))
        assertEquals("Failed: No room", TvInstalls.resultText("failed", "No room"))
    }

    /** No approval, no install: only a current parent's answer for an app the TV still keeps. */
    @Test
    fun onlyAParentsAnswerForAKeptAppCounts() {
        val parents = listOf("dad", "mom")
        assertEquals(ask, TvInstalls.approved(listOf(ask), h, "mom", parents, 2_000))
        assertNull(TvInstalls.approved(listOf(ask), h, "kid", parents, 2_000))
        assertNull(TvInstalls.approved(listOf(ask), "cd".repeat(32), "mom", parents, 2_000))
        assertNull(TvInstalls.approved(listOf(ask), h, "mom", parents, 1_000 + TvInstalls.KEEP_MS))
        assertTrue(TvInstalls.tidy(listOf(ask), 1_000 + TvInstalls.KEEP_MS).isEmpty())
    }

    @Test
    fun theTvsListRoundTrips() {
        assertEquals(listOf(ask), TvInstalls.fromJson(TvInstalls.toJson(listOf(ask))))
        assertTrue(TvInstalls.fromJson("not json").isEmpty())
    }
}
