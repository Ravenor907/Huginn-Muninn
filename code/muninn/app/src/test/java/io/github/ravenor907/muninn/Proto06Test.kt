package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.Proto06
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/** The group part of shared/vectors/proto06-vectors.json, which Huginn checks too: both apps must agree byte for byte. */
class Proto06Test {
    private val v = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText())
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText())
    private val group = v.getJSONObject("group")
    private val states = group.getJSONObject("states")
    private fun state(name: String) = Proto06.parseState(states.getJSONObject(name).getJSONObject("state"))

    @Test
    fun canonicalGood() {
        val good = v.getJSONObject("canonical").getJSONArray("good")
        for (i in 0 until good.length()) {
            val c = good.getJSONObject(i)
            assertEquals(c.getString("canonical"), Proto06.canon(c.getJSONObject("value")))
        }
    }

    @Test
    fun canonicalRefuse() {
        val bad = v.getJSONObject("canonical").getJSONArray("refuse")
        for (i in 0 until bad.length()) {
            val text = bad.getString(i)
            try { Proto06.canon(JSONObject(text)); fail("should refuse $text") } catch (e: Proto06.Refused) {}
        }
        // Built in code too (the parser may give other number types).
        for (x in listOf<Any?>(mapOf("n" to -1L), mapOf("f" to 1.5), mapOf("x" to null), mapOf("n" to (1L shl 53)),
                mapOf("n" to BigInteger.ONE.shiftLeft(60)), mapOf("é" to 1), mapOf("t" to "a\u007Fb")))
            try { Proto06.canon(x); fail("should refuse $x") } catch (e: Proto06.Refused) {}
        assertEquals("{\"a\":\"a/b\",\"b\":[1,true]}", Proto06.canon(mapOf("b" to listOf(1, true), "a" to "a/b")))
    }

    @Test
    fun statesSignedHashedAndVerified() {
        val names = states.keys().asSequence().toList()
        assertEquals(9, names.size)
        for (k in names) {
            val x = states.getJSONObject(k)
            val s = state(k)
            assertEquals("signed $k", x.getString("signed"), s.signedText())
            assertEquals("hash $k", x.getString("hash"), s.hash)
            // The canonical text is also exactly what canon() gives for the state as it came off the wire.
            assertEquals("canon $k", Proto06.canon(x.getJSONObject("state")), s.canonSigned())
            assertEquals("signature $k", k != "tampered_s2", s.verifies())
        }
    }

    @Test
    fun acceptInOrder() {
        val acc = group.getJSONArray("accept")
        assertEquals(14, acc.length())
        for (i in 0 until acc.length()) {
            val a = acc.getJSONObject(i)
            val cur = if (a.isNull("current")) null else state(a.getString("current"))
            val (ok, why) = Proto06.accept(cur, state(a.getString("new")))
            assertEquals("accept $i", a.getBoolean("accept"), ok)
            assertEquals("why $i", a.getString("why"), why)
        }
    }

    @Test
    fun conflictWinner() {
        val c = group.getJSONObject("conflict")
        val a = state(c.getString("a")); val b = state(c.getString("b"))
        assertEquals(state(c.getString("winner")), Proto06.winner(a, b))
        assertEquals(state(c.getString("winner")), Proto06.winner(b, a))
    }

    @Test
    fun envelopeIds() {
        val ids = group.getJSONArray("envelopeIds")
        assertEquals(3, ids.length())
        for (i in 0 until ids.length()) {
            val e = ids.getJSONObject(i)
            assertEquals(e.getString("id"), Proto06.envelopeId(e.getString("gid"), e.getString("to")))
        }
    }

    @Test
    fun signsWithRandomKAndVerifies() {
        val a = java.security.KeyPair(ChatCrypto.publicFrom(chat.getString("pubA")), ChatCrypto.privateFrom(chat.getString("privA")))
        val s1 = state("s1")
        val mine = s1.copy(v = 2, name = "Hall ᚺ \"quoted\"", sig = "").signedBy(a.private, chat.getString("pubA"))
        assertTrue(mine.verifies())
        assertTrue(Proto06.accept(s1, mine).first)
        // Over the wire and back: still the same bytes.
        val back = Proto06.parseState(JSONObject(mine.json().toString()))
        assertEquals(mine, back)
        assertTrue(back.verifies())
        assertFalse(back.copy(name = "Hall").verifies())
    }

    @Test
    fun malformedStatesAreRefused() {
        val s2 = state("s2")
        fun refused(s: Proto06.State) = try { Proto06.check(s); false } catch (e: Proto06.Refused) { true }
        assertFalse(refused(s2))
        assertTrue(refused(s2.copy(members = s2.members.reversed())))
        assertTrue(refused(s2.copy(admins = s2.admins.reversed())))
        assertTrue(refused(s2.copy(name = " ")))
        assertTrue(refused(s2.copy(name = "x".repeat(41))))
        assertFalse(refused(s2.copy(name = "ᚺ".repeat(40))))
        assertTrue(refused(s2.copy(channels = s2.channels.reversed())))
        assertTrue(refused(s2.copy(channels = s2.channels + Proto06.Channel("short", "x", "text"))))
        assertTrue(refused(s2.copy(channels = s2.channels + Proto06.Channel(Proto06.newChannelId(), "x", "video"))))
        assertTrue(refused(s2.copy(owner = s2.members.first { it.k !in s2.admins }.k)))
        assertTrue(refused(s2.copy(g = "short")))
        // Extra or missing fields, or the wrong types, never parse.
        val o = s2.json()
        for (bad in listOf(JSONObject(o.toString()).put("extra", 1), JSONObject(o.toString()).apply { remove("ts") },
                JSONObject(o.toString()).put("v", "2"), JSONObject(o.toString()).put("v", true)))
            try { Proto06.parseState(bad); fail("parsed $bad") } catch (e: Proto06.Refused) {}
    }

    @Test
    fun groupCodes() {
        val s1 = state("s1")
        val c = Proto06.Code(s1.g, "Family", chat.getString("pubA"), "Raven", "a1".repeat(32), Proto06.newToken(), 1761500000)
        val text = "Join us: " + Proto06.code(c) + " see you"
        assertEquals(c, Proto06.parseCode(text))
        assertNull(Proto06.parseCode("MUNINNG1:nonsense"))
        assertNull(Proto06.parseCode(Proto06.code(c.copy(p = "zz"))))
        assertNotNull(Proto06.parseCode(Proto06.code(c.copy(p = ""))))
    }

    @Test
    fun noIpAddressOnScreen() {
        assertEquals("Couldn't reach a device", ChatWire.noIp("Couldn't reach 192.168.1.20"))
        assertEquals("failed to connect to a device after 6000ms", ChatWire.noIp("failed to connect to /192.168.1.20:53317 after 6000ms"))
        assertEquals("Phone", ChatWire.noIp("Phone"))
        assertEquals("from a device", ChatWire.noIp("from fe80::1%wlan0"))
        assertEquals("from a device", ChatWire.noIp("from 2001:db8::5"))
        assertEquals("from a device", ChatWire.noIp("from [2001:db8::5]:40312"))
        assertEquals("from a device", ChatWire.noIp("from 2001:0db8:0000:0000:0000:ff00:0042:8329"))
        // Not addresses: times, versions, lengths.
        assertEquals("Voice message · 0:12", ChatWire.noIp("Voice message · 0:12"))
        assertEquals("Muninn 0.6 at 2:41 PM", ChatWire.noIp("Muninn 0.6 at 2:41 PM"))
        assertEquals("v1.2.3", ChatWire.noIp("v1.2.3"))
    }

    @Test
    fun contactKeepsCapsAndGroupOnly() {
        val onion = "a".repeat(56)
        val c = Contact(chat.getString("pubB"), "Moose", "b2".repeat(32), false, groupOnly = true, caps = setOf("r1", "g1"))
        assertEquals(c, ChatWire.contactFromJson(JSONObject(ChatWire.contactToJson(c).toString())))
        assertFalse(ChatWire.contactToJson(c).has("o"))
        // A saved contact may have an onion address ("o"): it reads the same, the address ignored.
        assertEquals(c, ChatWire.contactFromJson(ChatWire.contactToJson(c).put("o", onion)))
        // Files without these fields read too.
        val old = ChatWire.contactFromJson(JSONObject().put("k", c.chatKey).put("n", "Moose").put("p", "").put("v", true))
        assertFalse(old.groupOnly); assertTrue(old.caps.isEmpty())
        // Adding a group peer as a contact clears groupOnly; caps only ever add up.
        val added = ChatWire.merge(c, Contact(c.chatKey, "Moose", null, true, caps = setOf("k1")))
        assertFalse(added.groupOnly); assertEquals(setOf("r1", "g1", "k1"), added.caps)
        // A group peer with a contact's mailbox is not a key change of that contact.
        assertNull(ChatWire.keyChangeFor(listOf(c), Contact(chat.getString("pubA"), "Other", c.mailbox, true)))
        assertNotNull(ChatWire.keyChangeFor(listOf(c.copy(groupOnly = false)), Contact(chat.getString("pubA"), "Other", c.mailbox, true)))
        // The contact code carries no onion address.
        val code = ChatWire.code("Raven", chat.getString("pubA"), "a1".repeat(32), "tok", 3_600_000)
        val o = JSONObject(String(java.util.Base64.getUrlDecoder().decode(code.removePrefix(ChatWire.PREFIX))))
        assertFalse(o.has("o")); assertEquals("tok", o.getString("i"))
        // Envelopes say what they speak, and cap stays exactly "r1" for apps that only read cap.
        val e = ChatWire.base("private", chat.getString("pubA"), "Raven", "fp", "a1".repeat(32), "m1")
        assertEquals("r1", e.getString("cap"))
        assertEquals(Proto06.CAPS, e.getString("caps"))
        assertEquals(setOf("r1", "g1", "c1", "c2", "k1", "k2", "k3", "k4", "k5", "t1", "v1", "m1", "m2", "i1", "f1", "d1", "k6", "k7", "k8", "k9", "k10", "j1", "x1", "s1", "p1", "b1", "e1", "a1", "v2", "f2", "g2", "f3", "g3", "g4", "n1", "n2", "u1", "k11", "k12", "k13", "k14", "p2"), ChatWire.capsOf(e))
        assertEquals(setOf("r1"), ChatWire.capsOf(JSONObject().put("cap", "r1")))
        assertTrue("group" in ChatWire.RATCHET_ROOMS && "gmsg" in ChatWire.RATCHET_ROOMS && "greceipt" in ChatWire.RATCHET_ROOMS)
    }

    @Test
    fun caps() {
        assertEquals(setOf("r1", "g1", "c1", "c2", "k1", "k2", "k3", "k4", "k5", "t1", "v1", "v2", "m1", "m2", "i1", "f1", "d1", "k6", "k7", "k8", "k9", "k10", "j1", "x1", "s1", "b1", "e1", "a1", "g2", "p1", "f2", "f3", "g3", "g4", "n1", "n2", "u1", "k11", "k12", "k13", "k14", "p2"), Proto06.caps(Proto06.CAPS))
        assertEquals(setOf("r1", "zz"), Proto06.caps(" r1  zz "))
        assertTrue(Proto06.isLogicalId(Proto06.newLogicalId()))
        assertEquals(22, Proto06.newGroupId().length)
        assertEquals(11, Proto06.newChannelId().length)
    }
}
