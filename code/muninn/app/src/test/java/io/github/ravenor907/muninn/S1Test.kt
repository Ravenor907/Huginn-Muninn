package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.ChatWire
import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.Failures
import io.github.ravenor907.muninn.net.Lan
import io.github.ravenor907.muninn.net.Proof
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.Ratchet
import io.github.ravenor907.muninn.net.Seen
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyPair

/** shared/vectors/s1-vectors.json (cap s1: proven routes and sealed clear fields, shared/docs/protocol.md section 21). */
class S1Test {
    private val v = JSONObject(javaClass.classLoader!!.getResource("s1-vectors.json")!!.readText())
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText())
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun routeProofs() {
        val a = v.getJSONObject("route").getJSONArray("checks")
        for (i in 0 until a.length()) {
            val r = a.getJSONObject(i)
            val got = if (r.getString("kind") == "wifi") Proof.routeOk(r.getString("key"), r.getString("n"), r.getString("sig"))
                else Proof.closeOk(r.getString("key"), r.getString("n"), r.getString("sig"))
            assertEquals(r.getString("what"), r.getBoolean("ok"), got)
        }
    }

    @Test
    fun myOwnProofsCheckOut() {
        val priv = ChatCrypto.privateFrom(chat.getString("privA"))
        val key = chat.getString("pubA")
        val fp = v.getJSONObject("route").getString("fingerprint")
        val n = Proof.newNonce()
        assertTrue(Proof.isNonce(n))
        assertTrue(Proof.routeOk(key, fp, Proof.route(priv, key, fp)!!))
        assertTrue(Proof.closeOk(key, n, Proof.close(priv, key, n)!!))
        // Never a signature over something that isn't a number (a fingerprint passed off as one, say).
        assertNull(Proof.close(priv, key, fp))
        assertFalse(Proof.closeOk(chat.getString("pubB"), n, Proof.close(priv, key, n)!!))
    }

    @Test
    fun hiddenHints() {
        val h = v.getJSONObject("hint")
        val days = h.getJSONArray("days")
        for (i in 0 until days.length()) days.getJSONObject(i).let { assertEquals(it.getString("hint"), Proof.hint(it.getString("key"), it.getLong("day"))) }
        val m = h.getJSONArray("match")
        for (i in 0 until m.length()) m.getJSONObject(i).let {
            assertEquals("match $i", it.getBoolean("ok"), Proof.hintMatches(it.getString("key"), it.getString("hint"), it.getLong("now")))
        }
        val key = chat.getString("pubA")
        val now = h.getLong("now")
        assertEquals(key, Proof.keyForHint(Proof.hint(key, now / Proof.DAY), listOf(chat.getString("pubB"), key), now))
        assertNull(Proof.keyForHint(Proof.hint(key, now / Proof.DAY), listOf(chat.getString("pubB")), now))
    }

    @Test
    fun boundFields() {
        val a = v.getJSONArray("bound")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val want = if (c.isNull("bound")) null else c.getString("bound")
            assertEquals("bound $i", want, Proof.bound(c.getJSONObject("envelope"), c.getString("toKey")))
        }
    }

    @Test
    fun boundV1() {
        val x = v.getJSONObject("v1")
        val sk = hex(x.getString("pairKey"))
        val me = ChatCrypto.newKeyPair()
        val env = x.getJSONObject("envelope")
        val to = x.getString("toKey")
        assertEquals(x.getString("bound"), Proof.bound(env, to))
        assertArrayEquals(x.getString("aad").toByteArray(), Proof.aad(x.getString("bound")))
        assertEquals(x.getString("plain"), String(ChatWire.open(env, sk, me, null, ChatCrypto::newKeyPair, to)!!))
        // Opened as if it were for someone else: no.
        assertNull(ChatWire.open(env, sk, me, null, ChatCrypto::newKeyPair, chat.getString("pubA")))
        val r = x.getJSONArray("refuse")
        for (i in 0 until r.length()) r.getJSONObject(i).let {
            assertNull(it.getString("what"), ChatWire.open(it.getJSONObject("envelope"), sk, me, null, ChatCrypto::newKeyPair, to))
        }
        // The same bytes from the nonce in the vectors.
        val sealed = ChatCrypto.sealBytes(sk, x.getString("plain").toByteArray(), Proof.aad(x.getString("bound")), hex(x.getString("nonce")))
        assertEquals(env.getString("data"), sealed.data)
    }

    @Test
    fun sealAndOpenBothWays() {
        val sk = hex(chat.getString("key"))
        val me = ChatCrypto.newKeyPair()
        val to = chat.getString("pubB")
        fun env() = ChatWire.base("private", chat.getString("pubA"), "Raven", "x", "a1".repeat(32), "id-1").put("days", 30)
        val bound = ChatWire.seal(env(), "hi".toByteArray(), sk, to, null, ChatCrypto::newKeyPair, bind = true)
        assertTrue(Proof.isBound(bound))
        assertEquals("hi", String(ChatWire.open(bound, sk, me, null, ChatCrypto::newKeyPair, to)!!))
        assertNull(ChatWire.open(JSONObject(bound.toString()).put("days", 7), sk, me, null, ChatCrypto::newKeyPair, to))
        // Unbound still opens too.
        val old = ChatWire.seal(env(), "hi".toByteArray(), sk, to, null, ChatCrypto::newKeyPair)
        assertFalse(Proof.isBound(old))
        assertEquals("hi", String(ChatWire.open(old, sk, me, null, ChatCrypto::newKeyPair, to)!!))
        // The ratchet, both ends, bound.
        val a = Ratchet.Peer(); val b = Ratchet.Peer()
        val bStatic = KeyPair(ChatCrypto.publicFrom(to), ChatCrypto.privateFrom(chat.getString("privB")))
        val r = ChatWire.seal(env(), "ratchet".toByteArray(), sk, to, a, ChatCrypto::newKeyPair, bind = true)
        assertNull(ChatWire.open(JSONObject(r.toString()).put("room", "live"), sk, bStatic, b, ChatCrypto::newKeyPair, to))
        assertEquals("ratchet", String(ChatWire.open(r, sk, bStatic, b, ChatCrypto::newKeyPair, to)!!))
    }

    @Test
    fun boundRatchetFromTheVectors() {
        val x = v.getJSONObject("ratchet")
        val env = x.getJSONObject("envelope")
        val to = x.getString("toKey")
        assertEquals(x.getString("bound"), Proof.bound(env, to))
        val h = Ratchet.Header.fromJson(env.getJSONObject("rh")).copy(bind = x.getString("bound"))
        assertArrayEquals(x.getString("aad").toByteArray(), h.aad())
        val bStatic = KeyPair(ChatCrypto.publicFrom(to), ChatCrypto.privateFrom(chat.getString("privB")))
        val sk = hex(chat.getString("key"))
        assertNull(ChatWire.open(JSONObject(env.toString()).put("days", 7), sk, bStatic, Ratchet.Peer(), ChatCrypto::newKeyPair, to))
        assertEquals(x.getString("plain"), String(ChatWire.open(env, sk, bStatic, Ratchet.Peer(), ChatCrypto::newKeyPair, to)!!))
    }

    @Test
    fun groupStateRules() {
        val g = v.getJSONObject("group")
        val st = g.getJSONObject("states")
        fun s(k: String) = Proto06.parseState(st.getJSONObject(k))
        val a = g.getJSONArray("accept")
        for (i in 0 until a.length()) a.getJSONObject(i).let {
            val (ok, why) = Proto06.accept(s(it.getString("current")), s(it.getString("new")))
            assertEquals("accept $i", it.getBoolean("accept"), ok)
            assertEquals("why $i", it.getString("why"), why)
        }
        val w = g.getJSONArray("winner")
        for (i in 0 until w.length()) w.getJSONObject(i).let {
            val x = s(it.getString("a")); val y = s(it.getString("b"))
            assertEquals("winner $i", if (it.getString("wins") == "a") x.hash else y.hash, Proto06.winner(x, y).hash)
        }
    }

    @Test
    fun seenIsKeptAcrossARestart() {
        var disk: String? = null
        val a = Seen(read = { disk }, write = { disk = it })
        a.add("k|1", "note")
        a.save()
        assertNotNull(disk)
        assertTrue(disk!!.indexOf("k|1") < 0)   // only a hash of who and which goes on disk
        val b = Seen(read = { disk }, write = { disk = it })
        assertEquals("note", b.get("k|1"))
        assertNull(b.get("k|2"))
    }

    @Test
    fun failuresWaitAWhile() {
        val f = Failures(max = 3, window = 1000)
        repeat(3) { assertTrue(f.ok("k", 0)); f.add("k", 0) }
        assertFalse(f.ok("k", 10))
        assertTrue(f.ok("other", 10))
        assertTrue(f.ok("k", 2000))
    }

    @Test
    fun homeNetworkAddresses() {
        fun p(s: String) = Lan.isPrivate(InetAddress.getByName(s))
        assertTrue(p("10.0.0.1") && p("172.16.0.1") && p("172.31.255.254") && p("192.168.1.20"))
        assertFalse(p("8.8.8.8") || p("100.64.0.1") || p("127.0.0.1") || p("172.15.255.255") || p("172.32.0.0") || p("192.169.0.1"))
        assertTrue(p("fe80::1") && p("fd12:3456::1"))
        assertFalse(p("fc00::1") || p("2001:db8::1") || p("::1"))
        fun b(s: String) = InetAddress.getByName(s).address
        assertTrue(Lan.inSubnet(b("192.168.1.77"), b("192.168.1.5"), 24))
        assertFalse(Lan.inSubnet(b("192.168.2.77"), b("192.168.1.5"), 24))
        assertTrue(Lan.inSubnet(b("10.1.2.3"), b("10.200.0.1"), 8))
        assertFalse(Lan.inSubnet(b("10.1.2.3"), b("10.200.0.1"), 9))
        assertFalse(Lan.inSubnet(b("10.1.2.3"), b("fe80::1"), 8))
    }

    @Test
    fun boundSendersGetADayOfGrace() {
        val t = 1_762_000_000_000L
        assertFalse(Proof.strictSince(0, t))
        assertFalse(Proof.strictSince(t, t + Proof.DAY))
        assertTrue(Proof.strictSince(t, t + Proof.DAY + 1))
        assertEquals(t, Proof.firstBound(t, t + 5))
        assertEquals(t, Proof.firstBound(0, t))
        // Kept with the contact, and never lost when they're added again.
        val c = Contact("k", "Anna", null, false, boundAt = t)
        assertEquals(t, ChatWire.contactFromJson(ChatWire.contactToJson(c)).boundAt)
        assertEquals(t, ChatWire.merge(c, Contact("k", "Anna", null, false)).boundAt)
        assertEquals(0L, ChatWire.contactFromJson(ChatWire.contactToJson(Contact("k", "Anna", null, false))).boundAt)
    }
}
