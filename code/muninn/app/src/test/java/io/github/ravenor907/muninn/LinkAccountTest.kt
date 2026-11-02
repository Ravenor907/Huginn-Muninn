package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.LinkAccount
import io.github.ravenor907.muninn.net.LinkAccount.Item
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cap l1 (protocol.md 15.1): the whole account on a linked computer, merged item by item, the later one winning. */
class LinkAccountTest {
    private fun contact(key: String, ts: Long, nick: String = "", bl: Boolean = false) =
        Item("contact", key, ts, false, JSONObject().put("n", "Name $key").put("vf", true).put("bl", bl).apply { if (nick.isNotEmpty()) put("nn", nick) })
    private fun me(name: String, ts: Long) = Item("me", "me", ts, false, JSONObject().put("n", name).put("st", "online"))
    private fun set(vararg i: Item) = i.associateBy { it.key }

    @Test
    fun mergeTakesEverythingNewAndKeepsWhatsHere() {
        val local = set(contact("a", 10), contact("b", 10))
        val (out, won) = LinkAccount.merge(local, listOf(contact("c", 5), Item("folder", "f1", 7, false, JSONObject().put("n", "Family").put("k", JSONArray()))), winTies = true)
        assertEquals(setOf("contact|a", "contact|b", "contact|c", "folder|f1"), out.keys)
        assertEquals(listOf("contact|c", "folder|f1"), won.map { it.key })
    }

    @Test
    fun lastWriterWinsPerItem() {
        val local = set(contact("a", 100, nick = "Ma"), contact("b", 100, nick = "Bee"))
        val (out, won) = LinkAccount.merge(local, listOf(contact("a", 200, nick = "Mom"), contact("b", 50, nick = "Old")), winTies = false)
        assertEquals("Mom", out["contact|a"]!!.v!!.optString("nn"))
        assertEquals("Bee", out["contact|b"]!!.v!!.optString("nn"))
        assertEquals(listOf("contact|a"), won.map { it.key })
        // A tie goes to the phone's copy: the companion takes it, the phone keeps its own.
        val tie = contact("b", 100, nick = "Phone's")
        assertEquals("Phone's", LinkAccount.merge(local, listOf(tie), winTies = true).first["contact|b"]!!.v!!.optString("nn"))
        assertEquals("Bee", LinkAccount.merge(local, listOf(tie), winTies = false).first["contact|b"]!!.v!!.optString("nn"))
        // The same copy again changes nothing.
        assertTrue(LinkAccount.merge(local, listOf(contact("a", 100, nick = "Ma")), winTies = true).second.isEmpty())
    }

    @Test
    fun deletesAreTombstonesThatStay() {
        val local = set(contact("a", 100))
        val gone = Item("contact", "a", 150, true)
        val (out, won) = LinkAccount.merge(local, listOf(gone), winTies = false)
        assertTrue(out["contact|a"]!!.del)
        assertEquals(1, won.size)
        // An older copy coming in late never brings it back.
        assertTrue(LinkAccount.merge(out, listOf(contact("a", 120)), winTies = true).first["contact|a"]!!.del)
        // A newer one does (added again after the delete).
        assertFalse(LinkAccount.merge(out, listOf(contact("a", 160)), winTies = false).first["contact|a"]!!.del)
        // The phone's side: something that's gone from the account becomes a tombstone with a new ts.
        val (seen, changed) = LinkAccount.observe(set(contact("a", 100), contact("b", 100)), mapOf("contact|b" to contact("b", 1).v!!), 500)
        assertTrue(seen["contact|a"]!!.del && seen["contact|a"]!!.ts == 500L)
        assertEquals(listOf("contact|a"), changed.map { it.key })
        // Tombstones go after 30 days, nothing else does.
        val pruned = LinkAccount.prune(seen, 500 + LinkAccount.TOMB_MS + 1)
        assertNull(pruned["contact|a"])
        assertEquals(setOf("contact|b"), pruned.keys)
        // A tombstone reads back as one.
        assertTrue(LinkAccount.read(gone.json())!!.del)
    }

    @Test
    fun observeStampsOnlyWhatChanged() {
        val v = JSONObject().put("n", "Raven").put("st", "online")
        val (first, c1) = LinkAccount.observe(emptyMap(), mapOf("me|me" to v), 1000)
        assertEquals(1, c1.size)
        // The same value written in another key order is the same.
        val (_, c2) = LinkAccount.observe(first, mapOf("me|me" to JSONObject().put("st", "online").put("n", "Raven")), 2000)
        assertTrue(c2.isEmpty())
        // A change made within the same millisecond still gets a later ts.
        val (_, c3) = LinkAccount.observe(first, mapOf("me|me" to JSONObject().put("n", "Huginn").put("st", "online")), 1000)
        assertEquals(1001L, c3.single().ts)
    }

    @Test
    fun linkingReplacesTheNameEntirely() {
        // The computer had its own name, written later than the phone's: linking takes the phone's anyway.
        val pc = set(me("Old computer name", 9_000), contact("x", 9_000))
        val phone = listOf(me("Raven", 10), contact("a", 10))
        val linked = LinkAccount.adopt(phone)
        assertEquals("Raven", linked["me|me"]!!.v!!.optString("n"))
        assertNull(linked["contact|x"])
        assertFalse(linked.values.any { it.v?.toString()?.contains("Old computer name") == true })
        // Plain merging would have kept the old name: that's why the first full set is taken whole.
        assertEquals("Old computer name", LinkAccount.merge(pc, phone, winTies = true).first["me|me"]!!.v!!.optString("n"))
        // From then on, the later change wins either way.
        val (after, _) = LinkAccount.merge(linked, listOf(me("Raven K", 20)), winTies = false)
        assertEquals("Raven K", after["me|me"]!!.v!!.optString("n"))
    }

    @Test
    fun framesAndCaps() {
        assertTrue(LinkAccount.says("l1"))
        assertTrue(LinkAccount.says("r1 l1 m1"))
        assertFalse(LinkAccount.says("l10 r1"))
        assertFalse(LinkAccount.says(""))
        val small = JSONObject().put("t", "acct").put("items", JSONArray().put(contact("a", 1).json()))
        assertTrue(LinkAccount.internetOk(small))
        assertFalse(LinkAccount.internetOk(JSONObject(small.toString()).put("full", true)))
        val pic = JSONObject().put("t", "acct").put("items", JSONArray().put(Item("pic", "a", 1, false, JSONObject().put("pic", "AAAA")).json()))
        assertFalse(LinkAccount.internetOk(pic))
        // Bad items are dropped: an unknown kind, no id, no ts, no value.
        val bad = JSONArray().put(JSONObject().put("k", "car").put("id", "a").put("ts", 1).put("v", JSONObject()))
            .put(JSONObject().put("k", "me").put("id", "").put("ts", 1).put("v", JSONObject()))
            .put(JSONObject().put("k", "me").put("id", "me").put("v", JSONObject()))
            .put(JSONObject().put("k", "me").put("id", "me").put("ts", 1))
        assertTrue(LinkAccount.readAll(bad).isEmpty())
        // Pages stay under their size, and none is lost.
        val many = (0 until 300).map { contact("k$it", 1).json() }
        val pages = LinkAccount.pages(many, 4096)
        assertTrue(pages.size > 1 && pages.all { JSONArray(it).toString().toByteArray().size <= 4096 })
        assertEquals(300, pages.sumOf { it.size })
        // What's saved loads the same.
        val s = set(contact("a", 3), Item("folder", "f", 4, true))
        assertEquals(s.keys, LinkAccount.load(LinkAccount.save(s)).keys)
        assertTrue(LinkAccount.load(LinkAccount.save(s))["folder|f"]!!.del)
    }
}
