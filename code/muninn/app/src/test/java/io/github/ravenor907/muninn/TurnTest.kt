package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.CallCrypto
import io.github.ravenor907.muninn.net.call.RelayConfig
import io.github.ravenor907.muninn.net.call.Stun
import io.github.ravenor907.muninn.net.call.TurnClient
import io.github.ravenor907.muninn.net.call.TurnCreds
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/** STUN and TURN encoding for the call relay, checked against RFC 5769's test vectors. */
class TurnTest {
    private fun h(s: String) = CallCrypto.unhex(s.replace(" ", "").replace("\n", ""))

    // RFC 5769 2.1: sample request, short-term password "VOkJxbRl1RmTxUk/WvJxBt".
    private val request = h("""
        000100582112a442b7e7a701bc34d686fa87dfae802200105354554e207465737420636c69656e74
        002400046e0001ff80290008932ff9b151263b36000600096576746a3a68367659202020
        000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a280280004e57a3bcf""")
    // 2.2 and 2.3: success responses, IPv4 and IPv6.
    private val response4 = h("""
        0101003c2112a442b7e7a701bc34d686fa87dfae8022000b7465737420766563746f7220
        002000080001a147e112a643000800142b91f599fd9e90c38c7489f92af9ba53f06be7d7
        80280004c07d4c96""")
    private val response6 = h("""
        010100482112a442b7e7a701bc34d686fa87dfae8022000b7465737420766563746f7220
        002000140002a1470113a9faa5d3f179bc25f4b5bed2b9d900080014a382954e4be67bf11784c97c
        8292c275bfe3ed4180280004c8fb0b4c""")
    // 2.4: request with long-term credentials.
    private val longTerm = h("""
        000100602112a44278ad3433c6ad72c029da412e00060012e3839ee38388e383aae38383e382afe382b90000
        0015001c662f2f3439396b39353464364f4c33346f4c394653547679363473410014000b6578616d706c652e6f726700
        00080014f67024656dd64a3e02b8e0712e85c9a28ca89666""")
    private val shortTerm = "VOkJxbRl1RmTxUk/WvJxBt".toByteArray()

    @Test
    fun rfc5769Request() {
        val m = Stun.decode(request)!!
        assertEquals(Stun.BINDING, m.method); assertEquals(Stun.REQUEST, m.cls)
        assertEquals("STUN test client", m.text(Stun.A_SOFTWARE))
        assertEquals("evtj:h6vY", m.text(Stun.A_USERNAME))
        assertTrue(Stun.checkIntegrity(m, shortTerm))
        assertFalse(Stun.checkIntegrity(m, "wrong".toByteArray()))
        assertTrue(Stun.checkFingerprint(m))
        // Encoding the same attributes (padded with spaces, as the RFC did) gives the same bytes.
        val again = Stun.encode(m.type, m.tid, listOf(
            Stun.Attr(Stun.A_SOFTWARE, "STUN test client".toByteArray()),
            Stun.Attr(Stun.A_PRIORITY, h("6e0001ff")),
            Stun.Attr(Stun.A_ICE_CONTROLLED, h("932ff9b151263b36")),
            Stun.Attr(Stun.A_USERNAME, "evtj:h6vY".toByteArray())), shortTerm, fingerprint = true, pad = 0x20)
        assertArrayEquals(request, again)
    }

    @Test
    fun rfc5769Responses() {
        for ((raw, ip) in listOf(response4 to "192.0.2.1", response6 to "2001:db8:1234:5678:11:2233:4455:6677")) {
            val m = Stun.decode(raw)!!
            assertEquals(Stun.SUCCESS, m.cls); assertEquals(Stun.BINDING, m.method)
            assertEquals("test vector", m.text(Stun.A_SOFTWARE))
            assertTrue(Stun.checkIntegrity(m, shortTerm))
            assertTrue(Stun.checkFingerprint(m))
            val a = m.xorAddress(Stun.A_XOR_MAPPED_ADDRESS)!!
            assertEquals(InetAddress.getByName(ip), a.address)
            assertEquals(32853, a.port)
            // And back: the XOR address encodes to the same attribute value.
            assertArrayEquals(m.attr(Stun.A_XOR_MAPPED_ADDRESS), Stun.encodeXorAddress(a, m.tid))
        }
        // One flipped bit: the fingerprint and the integrity both fail.
        val bad = response4.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }
        val m = Stun.decode(bad)!!
        assertFalse(Stun.checkFingerprint(m)); assertFalse(Stun.checkIntegrity(m, shortTerm))
    }

    @Test
    fun rfc5769LongTerm() {
        val m = Stun.decode(longTerm)!!
        assertEquals("マトリックス", m.text(Stun.A_USERNAME))
        assertEquals("f//499k954d6OL34oL9FSTvy64sA", m.text(Stun.A_NONCE))
        assertEquals("example.org", m.text(Stun.A_REALM))
        // The password "The<U+00AD>M<U+00E6>tr<U+00E5>c" after SASLprep is "TheMatrIX".
        val key = Stun.longTermKey(m.text(Stun.A_USERNAME)!!, "example.org", "TheMatrIX")
        assertTrue(Stun.checkIntegrity(m, key))
        assertFalse(Stun.checkFingerprint(m))   // this one has none
        // Re-encoded with the same attributes, the integrity comes out identical.
        val again = Stun.encode(m.type, m.tid, m.attrs.filter { it.type != Stun.A_MESSAGE_INTEGRITY }, key)
        assertArrayEquals(longTerm, again)
    }

    @Test
    fun typesAndMethods() {
        assertEquals(0x0003, Stun.type(Stun.ALLOCATE, Stun.REQUEST))
        assertEquals(0x0103, Stun.type(Stun.ALLOCATE, Stun.SUCCESS))
        assertEquals(0x0113, Stun.type(Stun.ALLOCATE, Stun.ERROR))
        assertEquals(0x0016, Stun.type(Stun.SEND, Stun.INDICATION))
        assertEquals(0x0017, Stun.type(Stun.DATA, Stun.INDICATION))
        assertEquals(0x0008, Stun.type(Stun.CREATE_PERMISSION, Stun.REQUEST))
        assertEquals(0x0109, Stun.type(Stun.CHANNEL_BIND, Stun.SUCCESS))
        for (m in listOf(Stun.BINDING, Stun.ALLOCATE, Stun.REFRESH, Stun.SEND, Stun.DATA, Stun.CREATE_PERMISSION, Stun.CHANNEL_BIND, 0xABC))
            for (c in listOf(Stun.REQUEST, Stun.INDICATION, Stun.SUCCESS, Stun.ERROR)) {
                assertEquals(m, Stun.methodOf(Stun.type(m, c))); assertEquals(c, Stun.classOf(Stun.type(m, c)))
            }
    }

    @Test
    fun turnMessages() {
        val tid = h("0102030405060708090a0b0c")
        val peer = InetSocketAddress(InetAddress.getByName("203.0.113.9"), 50123)
        // A 401 answer to an Allocate: error code, realm and nonce come out.
        val err = Stun.encode(Stun.type(Stun.ALLOCATE, Stun.ERROR), tid, listOf(
            Stun.Attr(Stun.A_ERROR_CODE, byteArrayOf(0, 0, 4, 1) + "Unauthorized".toByteArray()),
            Stun.Attr(Stun.A_REALM, "cloudflare.com".toByteArray()), Stun.Attr(Stun.A_NONCE, "abc123".toByteArray())), fingerprint = true)
        val e = Stun.decode(err)!!
        assertEquals(401 to "Unauthorized", e.error())
        assertEquals("cloudflare.com", e.text(Stun.A_REALM)); assertEquals("abc123", e.text(Stun.A_NONCE))
        assertTrue(Stun.checkFingerprint(e))
        // A success with a relayed address and lifetime, signed with the long-term key.
        val key = Stun.longTermKey("user", "cloudflare.com", "pass")
        val ok = Stun.encode(Stun.type(Stun.ALLOCATE, Stun.SUCCESS), tid, listOf(
            Stun.Attr(Stun.A_XOR_RELAYED_ADDRESS, Stun.encodeXorAddress(peer, tid)), Stun.Attr(Stun.A_LIFETIME, Stun.u32(600))), key, fingerprint = true)
        val s = Stun.decode(ok)!!
        assertEquals(peer, s.xorAddress(Stun.A_XOR_RELAYED_ADDRESS)); assertEquals(600L, s.int(Stun.A_LIFETIME))
        assertTrue(Stun.checkIntegrity(s, key)); assertTrue(Stun.checkFingerprint(s))
        assertTrue(Stun.isStun(ok)); assertFalse(Stun.isChannelData(ok))
        // Not STUN: a media packet (0x81) or garbage.
        assertNull(Stun.decode(h("81008cc700000000000000000000000000000000000000000000")))
        assertNull(Stun.decode(ok.copyOf(ok.size - 1)))
    }

    @Test
    fun channelData() {
        val data = byteArrayOf(0x81.toByte(), 1, 2, 3, 4)
        val udp = Stun.channelData(0x4001, data, pad = false)
        assertEquals("4001000581" + "01020304", CallCrypto.hex(udp))
        val tcp = Stun.channelData(0x4001, data, pad = true)
        assertEquals(12, tcp.size)
        val (ch, back) = Stun.parseChannelData(tcp)!!
        assertEquals(0x4001, ch); assertArrayEquals(data, back)
        assertNull(Stun.parseChannelData(h("3fff0001aa")))   // not a channel number
        assertNull(Stun.parseChannelData(h("40010009aabb"))) // shorter than it says
        assertFalse(Stun.isStun(udp))
    }

    @Test
    fun urls() {
        assertEquals(TurnClient.Server("turn.cloudflare.com", 3478, tls = false, udp = true), TurnClient.parseUrl("turn:turn.cloudflare.com:3478?transport=udp"))
        assertEquals(TurnClient.Server("turn.cloudflare.com", 3478, tls = false, udp = false), TurnClient.parseUrl("turn:turn.cloudflare.com:3478?transport=tcp"))
        assertEquals(TurnClient.Server("turn.cloudflare.com", 443, tls = true, udp = false), TurnClient.parseUrl("turns:turn.cloudflare.com:443?transport=tcp"))
        assertEquals(TurnClient.Server("2001:db8::1", 3478, tls = false, udp = true), TurnClient.parseUrl("turn:[2001:db8::1]:3478"))
        assertNull(TurnClient.parseUrl("stun:stun.cloudflare.com:3478"))
        assertNull(TurnClient.parseUrl("turns:x:443?transport=udp"))
        val list = listOf("turn:turn.cloudflare.com:3478?transport=tcp", "turns:turn.cloudflare.com:5349?transport=tcp",
            "turns:turn.cloudflare.com:443?transport=tcp", "turn:turn.cloudflare.com:3478?transport=udp").mapNotNull { TurnClient.parseUrl(it) }
        val order = TurnClient.order(list)
        assertTrue(order[0].udp)
        assertEquals(443, order[1].port); assertTrue(order[1].tls)
        assertEquals(4, order.size)
    }

    @Test
    fun relayLogin() {
        val der = ByteArray(91) { it.toByte() }
        val o = JSONObject(TurnCreds.body(der, 1_800_000_000L, "abcabcabc"))
        assertEquals("58d29459b2130a2e151252d408b95e6dac424c564062eb911cc76440cb926ca0", o.getString("id"))
        assertEquals(1_800_000_000L, o.getLong("t"))
        assertTrue(o.get("t") is Number)
        assertEquals("d35e8f4c7d7ce9465ab483a031e312a35acfeadd3de7b317e4431e605adc06d4", o.getString("sig"))
        val l = TurnCreds.parse("""{"urls":["turn:turn.cloudflare.com:3478?transport=udp","stun:stun.cloudflare.com:3478"],"username":"u","credential":"c","ttl":7200}""", 1000)
        assertNotNull(l)
        assertEquals(listOf("turn:turn.cloudflare.com:3478?transport=udp"), l!!.urls)
        assertEquals(1000 + 7_200_000L, l.expiresMs)
        assertTrue(l.usable(1000 + 7_200_000L - 300_001)); assertFalse(l.usable(1000 + 7_200_000L - 300_000))
        assertNull(TurnCreds.parse("""{"error":"stale"}""", 0))
        assertEquals(86_400_000L, TurnCreds.nextUtcDayMs(1))
        // While the placeholders are in, nothing is fetched.
        if (!RelayConfig.configured) assertNull(TurnCreds.get(der, { _, _ -> error("must not post") }))
    }

    /** The relay's name is looked up with the system resolver; an IP literal needs no lookup. */
    @Test
    fun resolvesTheRelay() {
        assertTrue(TurnClient.lookup("localhost", tries = 2)!!.isLoopbackAddress)
        assertEquals(InetAddress.getByAddress(byteArrayOf(104, 30, 1, 2)), TurnClient.lookup("104.30.1.2"))
        assertNull(TurnClient.lookup("999.1.1.1"))
        // Every server's name unreachable: start() fails with the call screen's message, never tries a server.
        val login = TurnCreds.Login(listOf("turn:turn.example.com:3478?transport=udp", "turns:turn.example.com:443?transport=tcp"), "u", "p", Long.MAX_VALUE)
        val t = TurnClient(login, { null }) { _, _ -> }
        assertFalse(t.start()); t.close()
        assertEquals(TurnClient.CANT_REACH, TurnClient.lastProblem)
    }

    /** The same Worker request Huginn's --call-selftest checks: id = SHA-256 of the raw SPKI DER, sig = HMAC over id.t. */
    @Test
    fun workerBodyMatchesHuginn() {
        val der = java.util.Base64.getDecoder().decode("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAhfmF/C2RDkoJ4+WmZ5pojpPLBUr321s32bluAKC1O0ZSn3ry5dxLS3aPKhaqHZaVvRfx1hZllLyiXxlMG5XlA==")
        val o = JSONObject(TurnCreds.body(der, 1_800_000_000L, "a".repeat(64)))
        assertEquals("76805a63a6980c18075424a13f32772d02d7c71b4965754c1ce84f5841220327", o.getString("id"))
        assertEquals("a0115b4186fe1780017780b84bf5a20f0501934df65efb994e35c88f745e188c", o.getString("sig"))
        assertEquals(1_800_000_000L, (o.get("t") as Number).toLong())
    }
    /** Install tokens (relay.md): the proof of work matches the Worker's and Huginn's, and a refused token is registered again. */
    @Test
    fun installTokens() {
        val id = "a".repeat(64)
        // The same vector as the relay Worker's test and Huginn's --call-selftest: the smallest number with 18 zero bits.
        assertEquals(465_984L, TurnCreds.solve(id, 1_800_000_000L))
        assertTrue(TurnCreds.powOk(id, 1_800_000_000L, 465_984L))
        assertFalse(TurnCreds.powOk(id, 1_800_000_001L, 465_984L, 30))
        assertFalse(TurnCreds.powOk(id, 1_800_000_000L, -1))
        val reg = JSONObject(TurnCreds.regBody(ByteArray(91) { it.toByte() }, 1_800_000_000L, "abcabcabc", 7))
        assertEquals(setOf("id", "t", "sig", "pow"), reg.keys().asSequence().toSet())
        assertEquals(7L, reg.getLong("pow"))
        // A body without a token is what older Workers and apps know; with one, "tok" is added at the end.
        val der = ByteArray(91) { it.toByte() }
        assertFalse(JSONObject(TurnCreds.body(der, 1L, "s")).has("tok"))
        assertEquals("x.y", JSONObject(TurnCreds.body(der, 1L, "s", "x.y")).getString("tok"))
        // Kept as id|tok|exp: only for this id and while more than a day is left.
        val kept = TurnCreds.tokenOf("""{"urls":[],"tok":"1800604800.abc","exp":1800604800}""", id)
        assertEquals("$id|1800604800.abc|1800604800", kept)
        assertEquals("1800604800.abc", TurnCreds.tokenFor(kept, id, 1_800_000_000L))
        assertNull(TurnCreds.tokenFor(kept, "b".repeat(64), 1_800_000_000L))
        assertNull(TurnCreds.tokenFor(kept, id, 1_800_604_800L - 86_400L))
        assertNull(TurnCreds.tokenOf("""{"urls":[]}""", id))
        assertEquals(1_800_000_000L, TurnCreds.staleNow("""{"error":"stale","now":1800000000}"""))
        assertNull(TurnCreds.staleNow("""{"error":"stale"}"""))
        if (!RelayConfig.configured) return
        // The whole round: no token yet, so register; the login refuses it once (bad_token), so register again.
        var saved: String? = null
        val prev = TurnCreds.store
        TurnCreds.store = object : TurnCreds.TokenStore { override fun load() = saved; override fun save(value: String?) { saved = value } }
        try {
            TurnCreds.forget()
            val now = System.currentTimeMillis()
            val calls = mutableListOf<String>()
            var refused = false
            val login = TurnCreds.get(der, { url, body ->
                val o = JSONObject(body)
                val exp = now / 1000 + 7 * 86_400L
                calls.add(url.substringAfterLast('/'))
                when {
                    url.endsWith("/register") -> {
                        assertTrue(TurnCreds.powOk(o.getString("id"), o.getLong("t"), o.getLong("pow")))
                        200 to """{"tok":"$exp.r${calls.size}","exp":$exp}"""
                    }
                    !refused -> { refused = true; assertTrue(o.has("tok")); 401 to """{"error":"bad_token"}""" }
                    else -> 200 to """{"urls":["turn:t.example:3478?transport=udp"],"username":"u","credential":"c","ttl":1800,"tok":"$exp.n","exp":$exp}"""
                }
            }, now)
            assertNotNull(login)
            assertEquals(listOf("register", "turn", "register", "turn"), calls)
            assertTrue(saved!!.endsWith(".n|" + (now / 1000 + 7 * 86_400L)))
        } finally { TurnCreds.store = prev; TurnCreds.forget() }
    }
}
