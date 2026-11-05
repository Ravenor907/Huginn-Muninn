package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.Sharing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

/** Cap f2 (shared/docs/protocol.md section 27): the same checks as Huginn's Sharing::selfTest (--chat-selftest). */
class SharingTest {
    private val mb = 1024L * 1024
    private val relayMax = 2 * mb

    @Test
    fun routeChoice() {
        assertEquals("direct", Sharing.route(sameWifi = true, trusted = false, size = 900 * mb, relayMax = relayMax))
        assertEquals("direct", Sharing.route(sameWifi = true, trusted = true, size = 10, relayMax = relayMax))
        assertEquals("internet", Sharing.route(sameWifi = false, trusted = true, size = 900 * mb, relayMax = relayMax))
        assertEquals("relay", Sharing.route(sameWifi = false, trusted = false, size = relayMax, relayMax = relayMax))
        assertEquals("", Sharing.route(sameWifi = false, trusted = false, size = relayMax + 1, relayMax = relayMax))
        assertEquals("Same Wi-Fi", Sharing.routeLabel("direct"))
        assertEquals("Over the internet", Sharing.routeLabel("relay"))
        assertEquals("Straight to them", Sharing.routeLabel("internet"))
    }

    @Test
    fun groupRulesAreEnforced() {
        val members = listOf("A", "B")
        val admins = listOf("A")
        assertEquals("", Sharing.refusal(members, admins, "", 0, "B", 900 * mb))
        assertTrue(Sharing.refusal(members, admins, "", 0, "Z", 10).isNotEmpty())
        assertEquals("", Sharing.refusal(members, admins, "admins", 25, "A", 25 * mb))
        assertTrue(Sharing.refusal(members, admins, "admins", 25, "A", 25 * mb + 1).contains("25 MB"))
        assertTrue(Sharing.refusal(members, admins, "admins", 0, "B", 10).startsWith("Refused by the group's file rules"))
        assertTrue(Sharing.refusal(members, admins, "nobody", 0, "A", 10).isNotEmpty())
        assertEquals("Only admins can send files · Up to 25 MB", Sharing.rulesLine("admins", 25))
        assertEquals("Nobody can send files", Sharing.rulesLine("nobody", 25))
        assertEquals("Everyone can send files", Sharing.rulesLine("", 0))
        assertEquals("", Sharing.checkRules(null, null))
        assertEquals("", Sharing.checkRules("admins", 4096))
        assertEquals("fs", Sharing.checkRules("everyone", null))
        assertEquals("fm", Sharing.checkRules(null, 0))
        assertEquals("fm", Sharing.checkRules(null, 4097))
        assertEquals("Refused by the group's file rules.", Sharing.declineText("rules"))
        assertEquals("Not enough space on their device.", Sharing.declineText("space"))
    }

    @Test
    fun rulesRideInTheSignedState() {
        val v = JSONObject(javaClass.classLoader!!.getResource("d1-vectors.json")!!.readText()).getJSONObject("states")
        val base = Proto06.parseState(v.getJSONObject(v.keys().next()).getJSONObject("state"))
        val ruled = Groups.Change("files", "admins", "25").applyTo(base)!!
        assertEquals("admins", ruled.fs)
        assertEquals(25, ruled.fm)
        Proto06.check(ruled)
        val back = Proto06.parseState(ruled.json())
        assertEquals("admins", back.fs)
        assertEquals(25, back.fm)
        // Everyone, no limit: the fields go, and the state is the one an app without f2 takes.
        val open = Groups.Change("files", "everyone", "0").applyTo(ruled)!!
        assertEquals(base, open)
        assertNull(Groups.Change("files", "everyone", "0").applyTo(base))
        for ((k, bad) in listOf("fs" to "x", "fs" to "everyone", "fm" to 0, "fm" to 5000)) {
            val o = base.json().put(k, bad)
            try { Proto06.check(Proto06.parseState(o)); fail("should refuse $k=$bad") } catch (e: IllegalArgumentException) {}
        }
    }

    @Test
    fun namesNeverLeaveTheFolder() {
        assertEquals("_.._etc_passwd", Sharing.safeName("../../etc/passwd", "x"))
        assertEquals("Chat", Sharing.safeName("..", "Chat"))
        assertEquals("Chat", Sharing.safeName(".", "Chat"))
        assertEquals("C__Windows", Sharing.safeName("C:\\Windows", "x"))
        assertEquals("_CON", Sharing.safeName("CON", "x"))
        assertEquals("_nul.txt", Sharing.safeName("nul.txt", "x"))
        assertEquals("_COM3", Sharing.safeName("COM3", "x"))
        assertEquals("_lpt9.log", Sharing.safeName("lpt9.log", "x"))
        assertEquals("COM10", Sharing.safeName("COM10", "x"))
        assertEquals("Console", Sharing.safeName("Console", "x"))
        assertEquals("Ana & Bo_", Sharing.safeName("Ana & Bo?", "x"))
        assertEquals("tail", Sharing.safeName("tail. . ", "x"))
        assertEquals("a_b_c", Sharing.safeName("a\nb\tc", "x"))
        assertEquals(120, Sharing.safeName("a".repeat(300), "x").length)
        assertEquals("Muninn/_Ana", Sharing.folderFor("Muninn", "../Ana"))
        assertEquals("Muninn/Chat", Sharing.folderFor("Muninn", ".."))
        assertEquals("Muninn/_AUX", Sharing.folderFor("Muninn", "AUX"))
        assertFalse(Sharing.folderFor("Muninn", "../../x").split('/').any { it == ".." || it == "." })
        assertEquals("PDF document", Sharing.kindOf("a.PDF"))
        assertEquals("App installer", Sharing.kindOf("game.apk"))
        assertEquals("File", Sharing.kindOf("x"))
    }

    @Test
    fun lanCandidateCarriesNoAddress() {
        val c = JSONArray().put(JSONObject().put("t", "lan").put("p", 5000))
        val at = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
        val target = FileLink.lanTarget(at, c)!!
        assertEquals(5000, target.port)
        assertEquals(at, target.address)
        assertNull(FileLink.lanTarget(null, c))
        assertNull(FileLink.lanTarget(at, JSONArray()))
        assertNull(FileLink.lanTarget(at, JSONArray().put(JSONObject().put("t", "lan").put("p", 70000))))
    }

    @Test
    fun groupOffersNameTheirGroup() {
        val offer = JSONObject().put("t", "offer").put("id", Proto06.newLogicalId()).put("name", "a.txt").put("size", 10)
            .put("sha", "a".repeat(64)).put("e", "x").put("c", JSONArray().put(JSONObject().put("t", "lan").put("p", 5000)))
        assertTrue(FileLink.ok(offer))
        offer.put("g", Proto06.newGroupId())
        assertTrue(FileLink.ok(offer))
        offer.put("g", "../x")
        assertFalse(FileLink.ok(offer))
        val p = FileLink.Progress("i", "k", "a.pdf", 10, true, "sending", group = "G")
        assertEquals("g:G", p.where)
        assertEquals("PDF document", p.kind)
        assertEquals("k", FileLink.Progress("i", "k", "a", 10, true, "done").where)
    }
}
