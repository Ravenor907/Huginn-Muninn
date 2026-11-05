package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.Stun
import io.github.ravenor907.muninn.net.call.TurnClient
import io.github.ravenor907.muninn.net.call.TurnCreds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two TURN clients on a small in-process TURN server (RFC 8656 over UDP, long-term credentials) that
 * behaves like a relay with address translation between its sites: each allocation sends from another
 * port than the relayed address it reported, the way two Cloudflare data centers can. Media must still
 * flow both ways, and a stale nonce (438 without MESSAGE-INTEGRITY) must not stop a permission.
 */
class TurnRelayTest {
    private val lo: InetAddress = InetAddress.getByName("127.0.0.1")

    /** The test relay. [translate]: relayed data leaves from a second port. [staleOnce]: the first CreatePermission gets 438. */
    inner class FakeTurn(private val translate: Boolean, private val staleOnce: Boolean) : Closeable {
        private val sock = DatagramSocket(0, lo)
        val port get() = sock.localPort
        private val realm = "test.relay"
        @Volatile private var nonce = "nonce-1"
        @Volatile private var closed = false
        val stale = AtomicInteger()
        val channelDataIn = AtomicInteger()
        val dataIndicationsOut = AtomicInteger()
        private val allocs = ConcurrentHashMap<InetSocketAddress, Alloc>()

        inner class Alloc(val client: InetSocketAddress) {
            val inSock = DatagramSocket(0, lo)
            val outSock = if (translate) DatagramSocket(0, lo) else inSock
            val perms: MutableSet<InetAddress> = ConcurrentHashMap.newKeySet()
            val chans = ConcurrentHashMap<Int, InetSocketAddress>()
            init {
                for (s in setOf(inSock, outSock)) Thread { readRelay(s) }.apply { isDaemon = true; start() }
            }
            fun out(peer: InetSocketAddress, data: ByteArray) {
                if (peer.address in perms) outSock.send(DatagramPacket(data, data.size, peer))
            }
            private fun readRelay(s: DatagramSocket) {
                val buf = ByteArray(2000)
                while (!closed) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (e: Exception) { return }
                    val from = p.socketAddress as InetSocketAddress
                    if (from.address !in perms) continue   // permissions are per IP address
                    val data = buf.copyOf(p.length)
                    val ch = chans.entries.firstOrNull { it.value == from }?.key
                    val msg = if (ch != null) Stun.channelData(ch, data, false) else {
                        dataIndicationsOut.incrementAndGet()
                        val tid = Stun.newTid()
                        Stun.encode(Stun.type(Stun.DATA, Stun.INDICATION), tid,
                            listOf(Stun.Attr(Stun.A_XOR_PEER_ADDRESS, Stun.encodeXorAddress(from, tid)), Stun.Attr(Stun.A_DATA, data)))
                    }
                    send(client, msg)
                }
            }
            fun close() { inSock.close(); outSock.close() }
        }

        init { Thread { loop() }.apply { isDaemon = true; start() } }

        private fun send(to: InetSocketAddress, b: ByteArray) = try { sock.send(DatagramPacket(b, b.size, to)) } catch (_: Exception) {}

        private fun error(m: Stun.Message, code: Int, withNonce: Boolean): ByteArray {
            val ec = byteArrayOf(0, 0, (code / 100).toByte(), (code % 100).toByte()) + "x".toByteArray()
            val attrs = mutableListOf(Stun.Attr(Stun.A_ERROR_CODE, ec))
            if (withNonce) { attrs += Stun.Attr(Stun.A_REALM, realm.toByteArray()); attrs += Stun.Attr(Stun.A_NONCE, nonce.toByteArray()) }
            return Stun.encode(Stun.type(m.method, Stun.ERROR), m.tid, attrs)
        }

        private fun loop() {
            val buf = ByteArray(2000)
            while (!closed) {
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (e: Exception) { return }
                val from = p.socketAddress as InetSocketAddress
                val n = p.length
                if (Stun.isChannelData(buf, n)) {
                    val (ch, data) = Stun.parseChannelData(buf, n) ?: continue
                    val a = allocs[from] ?: continue
                    channelDataIn.incrementAndGet()
                    a.chans[ch]?.let { a.out(it, data) }
                    continue
                }
                val m = Stun.decode(buf, n) ?: continue
                if (m.cls == Stun.INDICATION && m.method == Stun.SEND) {
                    val peer = m.xorAddress(Stun.A_XOR_PEER_ADDRESS) ?: continue
                    allocs[from]?.out(peer, m.attr(Stun.A_DATA) ?: continue)
                    continue
                }
                if (m.cls != Stun.REQUEST) continue
                val user = m.text(Stun.A_USERNAME)
                if (user == null || m.attr(Stun.A_MESSAGE_INTEGRITY) == null) { send(from, error(m, 401, true)); continue }
                val key = Stun.longTermKey(user, realm, "secret")
                if (!Stun.checkIntegrity(m, key)) { send(from, error(m, 401, true)); continue }
                if (m.text(Stun.A_NONCE) != nonce) { send(from, error(m, 438, true)); continue }
                val ok = { attrs: List<Stun.Attr> -> send(from, Stun.encode(Stun.type(m.method, Stun.SUCCESS), m.tid, attrs, key, fingerprint = true)) }
                when (m.method) {
                    Stun.ALLOCATE -> {
                        val a = allocs.getOrPut(from) { Alloc(from) }
                        ok(listOf(Stun.Attr(Stun.A_XOR_RELAYED_ADDRESS, Stun.encodeXorAddress(InetSocketAddress(lo, a.inSock.localPort), m.tid)),
                            Stun.Attr(Stun.A_XOR_MAPPED_ADDRESS, Stun.encodeXorAddress(from, m.tid)), Stun.Attr(Stun.A_LIFETIME, Stun.u32(600))))
                    }
                    Stun.REFRESH -> ok(listOf(Stun.Attr(Stun.A_LIFETIME, Stun.u32(600))))
                    Stun.CREATE_PERMISSION -> {
                        if (staleOnce && stale.get() == 0) {
                            // The nonce ran out: 438 with a new one, and no MESSAGE-INTEGRITY (as servers send it).
                            stale.incrementAndGet(); nonce = "nonce-2"; send(from, error(m, 438, true)); continue
                        }
                        val peer = m.xorAddress(Stun.A_XOR_PEER_ADDRESS) ?: continue
                        allocs[from]?.perms?.add(peer.address); ok(emptyList())
                    }
                    Stun.CHANNEL_BIND -> {
                        val peer = m.xorAddress(Stun.A_XOR_PEER_ADDRESS) ?: continue
                        val chv = m.attr(Stun.A_CHANNEL_NUMBER) ?: continue
                        val ch = ((chv[0].toInt() and 0xFF) shl 8) or (chv[1].toInt() and 0xFF)
                        val a = allocs[from] ?: continue
                        a.perms.add(peer.address); a.chans[ch] = peer; ok(emptyList())
                    }
                }
            }
        }

        override fun close() { closed = true; sock.close(); allocs.values.forEach { it.close() } }
    }

    private fun client(f: FakeTurn, got: LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>) =
        TurnClient(TurnCreds.Login(listOf("turn:127.0.0.1:${f.port}?transport=udp"), "user", "secret", Long.MAX_VALUE), { lo }) { from, d -> got.put(from to d) }

    /** Sends until something arrives (permissions are set up in the background), for up to 5 s. */
    private fun sendUntil(got: LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>, send: () -> Unit): Pair<InetSocketAddress, ByteArray>? {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            send()
            got.poll(100, TimeUnit.MILLISECONDS)?.let { return it }
        }
        return null
    }

    @Test
    fun relayToRelayThroughAddressTranslation() {
        FakeTurn(translate = true, staleOnce = true).use { f ->
            val gotA = LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>()
            val gotB = LinkedBlockingQueue<Pair<InetSocketAddress, ByteArray>>()
            val a = client(f, gotA); val b = client(f, gotB)
            try {
                assertTrue("A allocates", a.start()); assertTrue("B allocates", b.start())
                val ra = a.relayed!!; val rb = b.relayed!!
                assertEquals("udp", a.transport)
                // Each side as CallMedia keeps them: the advertised relayed address from signaling.
                val peerOfA = CallLogic.RelayPeer().apply { advertise(rb) }
                val peerOfB = CallLogic.RelayPeer().apply { advertise(ra) }
                a.permit(rb); b.permit(ra)

                val hello = "hello from A".toByteArray()
                val atB = sendUntil(gotB) { for (t in peerOfA.targets()) a.send(t, hello) }
                assertNotNull("B gets A's packet through both relays", atB)
                assertArrayEquals(hello, atB!!.second)
                // The relay translated A's address: a strict source check would have dropped this.
                assertNotEquals(ra, atB.first)
                assertEquals(ra.address, atB.first.address)
                assertTrue(peerOfB.heard(atB.first)); b.permit(atB.first)
                assertFalse("heard once is enough", peerOfB.heard(atB.first))
                assertEquals(listOf(ra, atB.first), peerOfB.targets())

                val back = "hello from B".toByteArray()
                val atA = sendUntil(gotA) { for (t in peerOfB.targets()) b.send(t, back) }
                assertNotNull("A gets B's answer", atA)
                assertArrayEquals(back, atA!!.second)
                assertEquals(1, f.stale.get())
                // ChannelData once the channels are bound (after the 438 retry).
                val end = System.currentTimeMillis() + 5_000
                while (f.channelDataIn.get() == 0 && System.currentTimeMillis() < end) { a.send(rb, hello); Thread.sleep(50) }
                assertTrue("channels bound despite the stale nonce", f.channelDataIn.get() > 0)
                assertTrue("translated sources come as Data indications", f.dataIndicationsOut.get() > 0)
            } finally { a.close(); b.close() }
        }
    }

    @Test
    fun relayPeerTargets() {
        val r1 = InetSocketAddress(InetAddress.getByName("141.101.90.1"), 50000)
        val r2 = InetSocketAddress(InetAddress.getByName("141.101.90.1"), 50002)
        val p = CallLogic.RelayPeer()
        assertTrue(p.targets().isEmpty())
        assertFalse(p.heard(null))
        assertTrue(p.advertise(r1)); assertFalse(p.advertise(r1))
        assertFalse(p.heard(r1))
        assertEquals(listOf(r1), p.targets())
        assertTrue(p.heard(r2))
        assertEquals(listOf(r1, r2), p.targets())
        // They advertise the address their packets come from: one target again.
        assertTrue(p.advertise(r2))
        assertEquals(listOf(r2), p.targets())
    }

    @Test
    fun eachRelayedAddressGetsAPacketOnce() {
        val r1 = InetSocketAddress(InetAddress.getByName("141.101.90.1"), 50000)
        val r2 = InetSocketAddress(InetAddress.getByName("141.101.90.1"), 50002)
        val lan = InetSocketAddress(InetAddress.getByName("192.168.1.20"), 40000)
        val peer = CallLogic.RelayPeer().apply { advertise(r1); heard(r2) }
        // Two relay paths (the advertised one and one added when a translated packet came in), plus Wi-Fi twice.
        val paths = listOf(CallLogic.Path(CallLogic.Kind.RELAY, r1), CallLogic.Path(CallLogic.Kind.RELAY, r2),
            CallLogic.Path(CallLogic.Kind.WIFI, lan), CallLogic.Path(CallLogic.Kind.WIFI, lan), CallLogic.Path(CallLogic.Kind.BLUETOOTH, null))
        val sends = CallLogic.udpSends(paths, peer.targets())
        assertEquals(listOf(CallLogic.Dest(CallLogic.Kind.WIFI, lan), CallLogic.Dest(CallLogic.Kind.RELAY, r1), CallLogic.Dest(CallLogic.Kind.RELAY, r2)), sends)
        // Relay only, no translation: one send.
        val one = CallLogic.RelayPeer().apply { advertise(r1) }
        assertEquals(1, CallLogic.udpSends(paths.take(2), one.targets()).size)
        // No relayed address known yet: nothing goes to the relay.
        assertTrue(CallLogic.udpSends(paths.take(2), emptyList()).isEmpty())
    }
}
