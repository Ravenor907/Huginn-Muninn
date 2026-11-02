package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.tv.TvUpd
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cap u1 (shared/docs/protocol.md 7.17): the Kids' TV updates by itself, Update now from a parent's phone, and its steps. */
class TvUpdTest {
    private fun o(s: String) = JSONObject(s)

    @Test fun capsSayU1() {
        assertTrue(TvUpd.CAP in Proto06.caps(Proto06.CAPS))
        assertEquals("u1", TvUpd.CAP)
    }

    @Test fun upnowFrame() {
        val m = o(TvUpd.nowMessage())
        assertEquals("upnow", m.getString("t"))
        assertTrue(TvUpd.isNow(m))
        assertFalse(TvUpd.isNow(o("""{"t":"upnow","x":1}""")))
        assertFalse(TvUpd.isNow(o("""{"t":"update"}""")))
    }

    @Test fun onlyParentsMayAsk() {
        val parents = listOf("mom", "dad")
        assertTrue(TvUpd.fromParent("mom", parents, hasConfig = true))
        assertFalse(TvUpd.fromParent("grandma", parents, hasConfig = true))
        assertFalse(TvUpd.fromParent("mom", parents, hasConfig = false))
        assertFalse(TvUpd.fromParent("mom", emptyList(), hasConfig = true))
    }

    @Test fun gatedOnU1() {
        assertTrue(TvUpd.canAsk(setOf("k1", "k2", "u1")))
        assertFalse(TvUpd.canAsk(setOf("k1", "k2", "k10")))
        assertFalse(TvUpd.canAsk(emptySet()))
        assertEquals("This TV needs one update on the TV first", TvUpd.NEEDS_FIRST)
    }

    @Test fun upstateRoundTrips() {
        for (st in listOf(
            TvUpd.St("1.33", TvUpd.CHECKING, at = 5),
            TvUpd.St("1.33", TvUpd.DOWNLOADING, p = 42, at = 5),
            TvUpd.St("1.33", TvUpd.INSTALLING, at = 5),
            TvUpd.St("1.34", TvUpd.CURRENT, at = 1_761_500_100_000),
            TvUpd.St("1.33", TvUpd.TAP, at = 0),
            TvUpd.St("1.33", TvUpd.FAILED, why = "No internet", at = 7),
            TvUpd.St("1.33", TvUpd.FAILED, at = 7),
        )) assertEquals(st, TvUpd.stateIn(o(TvUpd.stateMessage(st))))
    }

    @Test fun upstateFieldsOnlyWhereTheyBelong() {
        // p and why are dropped from a state they don't belong to.
        val m = o(TvUpd.stateMessage(TvUpd.St("1.33", TvUpd.CURRENT, p = 50, why = "x", at = 1)))
        assertFalse(m.has("p")); assertFalse(m.has("why"))
        // Downloading always carries p; the percent stays in 0..100.
        assertEquals(100, o(TvUpd.stateMessage(TvUpd.St("1.33", TvUpd.DOWNLOADING, p = 250))).getInt("p"))
        assertEquals(0, o(TvUpd.stateMessage(TvUpd.St("1.33", TvUpd.DOWNLOADING))).getInt("p"))
    }

    @Test fun malformedUpstatesAreIgnored() {
        for (bad in listOf(
            """{"t":"upstate","app":"1.33","s":"current"}""",                          // no at
            """{"t":"upstate","app":"1.33","s":"done","at":1}""",                      // unknown state
            """{"t":"upstate","app":"1.33","s":"downloading","at":1}""",               // no p
            """{"t":"upstate","app":"1.33","s":"downloading","p":101,"at":1}""",       // p too big
            """{"t":"upstate","app":"1.33","s":"downloading","p":"5","at":1}""",       // p not a number
            """{"t":"upstate","app":"1.33","s":"current","p":5,"at":1}""",             // p with another state
            """{"t":"upstate","app":"1.33","s":"current","why":"x","at":1}""",         // why with another state
            """{"t":"upstate","app":"1.33","s":"failed","why":"","at":1}""",           // empty why
            """{"t":"upstate","app":"1.33","s":"current","at":-1}""",                  // negative at
            """{"t":"upstate","app":"1.33","s":"current","at":true}""",                // at not a number
            """{"t":"upstate","app":"","s":"current","at":1}""",                       // empty app
            """{"t":"upstate","app":"12345678901234567","s":"current","at":1}""",      // app too long
            """{"t":"upstate","app":"1.3\u00013","s":"current","at":1}""",             // control character
            """{"t":"upstate","app":"1.33","s":"current","at":1,"x":1}""",             // extra field
            """{"t":"status","app":"1.33","s":"current","at":1}""",                    // another message
        )) assertNull(bad, TvUpd.stateIn(o(bad)))
        assertNotNull(TvUpd.stateIn(o("""{"t":"upstate","app":"1.33","s":"failed","why":"No internet","at":1}""")))
    }

    @Test fun statusTexts() {
        assertEquals("Checking", TvUpd.text(TvUpd.St("1.33", TvUpd.CHECKING)))
        assertEquals("Downloading 42%", TvUpd.text(TvUpd.St("1.33", TvUpd.DOWNLOADING, p = 42)))
        assertEquals("Installing", TvUpd.text(TvUpd.St("1.33", TvUpd.INSTALLING)))
        assertEquals("Up to date", TvUpd.text(TvUpd.St("1.33", TvUpd.CURRENT)))
        assertEquals("Needs a tap on the TV", TvUpd.text(TvUpd.St("1.33", TvUpd.TAP)))
        assertEquals("Failed: No internet", TvUpd.text(TvUpd.St("1.33", TvUpd.FAILED, why = "No internet")))
        assertEquals("Failed", TvUpd.text(TvUpd.St("1.33", TvUpd.FAILED)))
        assertEquals("", TvUpd.text(null))
        assertEquals("Ask a grown-up: press OK to update", TvUpd.TV_PROMPT)
    }

    @Test fun theTvsLine() {
        assertEquals("Muninn 1.33 · Up to date", TvUpd.line("1.33", TvUpd.St("1.33", TvUpd.CURRENT), behind = false))
        // Behind: said unless the TV is busy with something.
        assertEquals("Muninn 1.32 · Update available", TvUpd.line("1.32", TvUpd.St("1.32", TvUpd.CURRENT), behind = true))
        assertEquals("Muninn 1.32 · Downloading 10%", TvUpd.line("1.32", TvUpd.St("1.32", TvUpd.DOWNLOADING, p = 10), behind = true))
        // An older TV (no upstate): what its status said.
        assertEquals("Muninn 1.30 · Installing", TvUpd.line("1.30", null, behind = false, legacy = "Installing"))
        assertEquals("Muninn on the TV", TvUpd.line("", null, behind = false))
    }

    @Test fun behindCountsOnlyStableVersions() {
        assertTrue(TvUpd.behind("1.32", "1.33"))
        assertFalse(TvUpd.behind("1.33", "1.33"))
        assertFalse(TvUpd.behind("1.34", "1.33"))
        assertTrue(TvUpd.behind("1.33", "1.33", phoneLatest = "1.34"))
        // A phone on a pre-release doesn't call a TV on the latest release behind.
        assertFalse(TvUpd.behind("1.33", "1.4 beta 2"))
        assertFalse(TvUpd.behind("", "1.33"))
    }

    @Test fun lastCheckText() {
        val now = 10L * 86_400_000
        assertEquals("Last check: not yet", TvUpd.lastCheck(0, now))
        assertEquals("Last check: just now", TvUpd.lastCheck(now - 10_000, now))
        assertEquals("Last check: 5 min ago", TvUpd.lastCheck(now - 5 * 60_000, now))
        assertEquals("Last check: 3 h ago", TvUpd.lastCheck(now - 3 * 3_600_000, now))
        assertEquals("Last check: 3 days ago", TvUpd.lastCheck(now - 3 * 86_400_000, now))
    }

    @Test fun retryBacksOff() {
        assertEquals(5 * 60_000L, TvUpd.retryMs(1))
        assertEquals(15 * 60_000L, TvUpd.retryMs(2))
        assertEquals(3_600_000L, TvUpd.retryMs(3))
        assertEquals(4 * 3_600_000L, TvUpd.retryMs(4))
        assertEquals(12 * 3_600_000L, TvUpd.retryMs(5))
        assertEquals(12 * 3_600_000L, TvUpd.retryMs(50))
    }

    @Test fun framesOnlyWhenWorthIt() {
        val a = TvUpd.St("1.33", TvUpd.DOWNLOADING, p = 11, at = 1)
        assertTrue(TvUpd.worthSending(null, a))
        assertFalse(TvUpd.worthSending(a, a.copy(p = 19)))
        assertTrue(TvUpd.worthSending(a, a.copy(p = 20)))
        assertTrue(TvUpd.worthSending(a, a.copy(s = TvUpd.INSTALLING, p = null)))
        val f = TvUpd.St("1.33", TvUpd.FAILED, why = "No internet", at = 1)
        assertFalse(TvUpd.worthSending(f, f))
        assertTrue(TvUpd.worthSending(f, f.copy(why = "Not signed")))
    }

    /** The Updater's own lines (update/Updater.kt), read into the TV's states. */
    @Test fun updaterLinesMap() {
        assertEquals(TvUpd.CHECKING to null, TvUpd.fromUpdater("Checking for updates…", true, 0f))
        assertEquals(TvUpd.DOWNLOADING to null, TvUpd.fromUpdater("Downloading Muninn 1.34…", true, 0.4f))
        assertEquals(TvUpd.DOWNLOADING to null, TvUpd.fromUpdater("Installs when the file on its way is done", true, 1f))
        assertEquals(TvUpd.CURRENT to null, TvUpd.fromUpdater("Muninn is up to date", false, 0f))
        assertEquals(TvUpd.FAILED to "No internet", TvUpd.fromUpdater("Couldn't check for updates. Check your internet.", false, 0f))
        assertEquals(TvUpd.FAILED to "Download stopped", TvUpd.fromUpdater("The download stopped. Try again.", false, 0.3f))
        assertEquals(TvUpd.FAILED to "Download didn't match", TvUpd.fromUpdater("The download didn't match the release, so it wasn't installed.", false, 1f))
        assertEquals(TvUpd.FAILED to "Not signed", TvUpd.fromUpdater("This update isn't signed with the project's key, so it wasn't installed.", false, 1f))
        assertEquals(TvUpd.FAILED to "Not signed", TvUpd.fromUpdater("This update's version isn't signed with the project's key, so it wasn't installed.", false, 1f))
        // Handed to the TV's installer, or about to download: the install reports from there.
        assertNull(TvUpd.fromUpdater("Tap Install when Android asks", false, 1f))
        assertNull(TvUpd.fromUpdater("Muninn 1.34 is ready to install", false, 0f))
    }

    @Test fun wordingHasNoLongDashes() {
        val texts = listOf(TvUpd.NEEDS_FIRST, TvUpd.TV_PROMPT) + TvUpd.STATES.map { TvUpd.text(TvUpd.St("1.33", it, p = 5, why = "x")) }
        for (t in texts) assertFalse(t, t.contains('\u2014') || t.contains('\u2013'))
    }
}
