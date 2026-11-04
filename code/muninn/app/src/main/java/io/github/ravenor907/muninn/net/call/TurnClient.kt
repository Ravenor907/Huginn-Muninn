package io.github.ravenor907.muninn.net.call

import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A TURN client (RFC 8656) for one call: one allocation on Cloudflare's relay, long-term credentials with
 * MESSAGE-INTEGRITY, Refresh, CreatePermission, ChannelBind and ChannelData. Over the internet, call audio
 * goes only through this relay: the other phone sees the relay's address, never this phone's.
 *
 * UDP first (turn.cloudflare.com:3478), then TLS on 443, then whatever else the Worker listed.
 * [onData] gets every packet a permitted peer sent to our relayed address.
 */
class TurnClient(
    private val login: TurnCreds.Login,
    private val resolve: (String) -> InetAddress?,
    /** The IPv6 address of a name, tried when its IPv4 address didn't work. */
    private val resolve6: (String) -> InetAddress? = { lookup6(it) },
    private val onData: (peer: InetSocketAddress, data: ByteArray) -> Unit,
) : Closeable {
    /** One TURN server address from the Worker's list. */
    data class Server(val host: String, val port: Int, val tls: Boolean, val udp: Boolean)

    private interface Link : Closeable {
        val reliable: Boolean
        fun write(b: ByteArray)
        /** One whole message into [buf]; returns its length, or -1 when the link is closed. */
        fun read(buf: ByteArray): Int
    }

    private class UdpLink(server: InetSocketAddress) : Link {
        val socket = DatagramSocket().apply { connect(server); soTimeout = 0 }
        override val reliable = false
        override fun write(b: ByteArray) = socket.send(DatagramPacket(b, b.size))
        override fun read(buf: ByteArray): Int = try {
            val p = DatagramPacket(buf, buf.size); socket.receive(p); p.length
        } catch (e: Exception) { -1 }
        override fun close() = socket.close()
    }

    /** TCP or TLS: STUN messages and ChannelData (padded to 4) back to back on one stream. */
    private class StreamLink(val socket: Socket) : Link {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output: OutputStream = socket.getOutputStream()
        override val reliable = true
        override fun write(b: ByteArray) = synchronized(output) { output.write(b); output.flush() }
        override fun read(buf: ByteArray): Int = try {
            input.readFully(buf, 0, 4)
            val l = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (Stun.isChannelData(buf, 4)) {
                val padded = l + (4 - l % 4) % 4
                if (4 + padded > buf.size) -1 else { input.readFully(buf, 4, padded); 4 + l }
            } else {
                if (Stun.HEADER + l > buf.size) -1 else { input.readFully(buf, 4, Stun.HEADER - 4 + l); Stun.HEADER + l }
            }
        } catch (e: Exception) { -1 }
        override fun close() = socket.close()
    }

    @Volatile private var link: Link? = null
    @Volatile var relayed: InetSocketAddress? = null; private set
    @Volatile var closed = false; private set
    /** "udp", "tcp" or "tls" once allocated. */
    @Volatile var transport = ""; private set
    @Volatile private var realm = ""
    @Volatile private var nonce = ""
    @Volatile private var key = ByteArray(0)
    private val pending = ConcurrentHashMap<String, LinkedBlockingQueue<Stun.Message>>()
    private val channels = ConcurrentHashMap<InetSocketAddress, Int>()
    private val bound = ConcurrentHashMap<Int, InetSocketAddress>()
    private val ready = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    private var nextChannel = 0x4000
    private val timers = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "muninn-turn").apply { isDaemon = true } }
    private var refresher: ScheduledFuture<*>? = null
    private var reader: Thread? = null

    /** Allocates a relayed address, trying each server in turn. Blocks; false if none worked. */
    fun start(): Boolean {
        // Each name is looked up once; a name that can't be looked up isn't asked again in this call.
        val ips = HashMap<String, InetAddress?>()
        var unresolved = false
        val servers = order(login.urls.mapNotNull(::parseUrl))
        val v4Failed = ArrayList<Server>()
        for (s in servers) {
            if (closed) return false
            val ip = ips.getOrPut(s.host) { try { resolve(s.host) } catch (e: Exception) { null } }
            if (ip == null) { unresolved = true; continue }
            if (tryAllocate(s, ip)) { lastProblem = ""; return true }
            if (ip is Inet4Address) v4Failed += s
        }
        // Nothing worked over IPv4 (a network whose IPv4 is broken): the same servers over their IPv6 addresses.
        val ips6 = HashMap<String, InetAddress?>()
        for (s in v4Failed) {
            if (closed) return false
            val ip6 = ips6.getOrPut(s.host) { try { resolve6(s.host) } catch (e: Exception) { null } } ?: continue
            if (tryAllocate(s, ip6)) { lastProblem = ""; return true }
        }
        lastProblem = if (unresolved && ips.values.all { it == null }) CANT_REACH else ""
        return false
    }

    /** One server at one address; false (and the link closed) if it didn't allocate. */
    private fun tryAllocate(s: Server, ip: InetAddress): Boolean {
        try {
            if (allocate(s, ip)) return true
        } catch (e: Exception) { /* next server */ }
        link?.close(); link = null; reader = null
        return false
    }

    private fun allocate(s: Server, ip: InetAddress): Boolean {
        val addr = InetSocketAddress(ip, s.port)
        val l: Link = if (s.udp) UdpLink(addr) else {
            val plain = Socket().apply { tcpNoDelay = true; connect(addr, 5000); soTimeout = 0 }
            if (!s.tls) StreamLink(plain) else {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, s.host, s.port, true) as SSLSocket
                tls.soTimeout = 8000
                tls.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(s.host, tls.session)) { tls.close(); return false }
                tls.soTimeout = 0
                StreamLink(tls)
            }
        }
        link = l
        reader = Thread({ readLoop(l) }, "muninn-turn-read").apply { isDaemon = true; start() }
        // First try without credentials: the server answers 401 with its realm and a nonce.
        val first = request(Stun.ALLOCATE, listOf(Stun.Attr(Stun.A_REQUESTED_TRANSPORT, Stun.UDP_TRANSPORT)), auth = false) ?: return false
        val ok = if (first.cls == Stun.SUCCESS) first else {
            if (first.error()?.first != 401) return false
            realm = first.text(Stun.A_REALM) ?: return false
            nonce = first.text(Stun.A_NONCE) ?: return false
            key = Stun.longTermKey(login.username, realm, login.credential)
            request(Stun.ALLOCATE, listOf(Stun.Attr(Stun.A_REQUESTED_TRANSPORT, Stun.UDP_TRANSPORT)), auth = true)
                ?.takeIf { it.cls == Stun.SUCCESS } ?: return false
        }
        val r = ok.xorAddress(Stun.A_XOR_RELAYED_ADDRESS) ?: return false
        relayed = r
        transport = if (s.udp) "udp" else if (s.tls) "tls" else "tcp"
        val life = (ok.int(Stun.A_LIFETIME) ?: 600L).coerceIn(120L, 3600L)
        refresher = timers.scheduleWithFixedDelay({ refresh() }, life - 60, life - 60, TimeUnit.SECONDS)
        return true
    }

    private fun refresh() {
        if (closed) return
        request(Stun.REFRESH, listOf(Stun.Attr(Stun.A_LIFETIME, Stun.u32(600))), auth = true)
    }

    /**
     * Lets [peer] (the other phone's relayed address) send to us and binds a channel to it, then keeps
     * both fresh (permissions last 5 minutes, channels 10). Runs in the background.
     */
    fun permit(peer: InetSocketAddress) {
        if (closed || channels.containsKey(peer)) return
        val ch = synchronized(this) { nextChannel++.also { if (nextChannel > 0x4FFF) nextChannel = 0x4000 } }
        channels[peer] = ch
        val job = Runnable {
            if (closed) return@Runnable
            val xpeer = { tid: ByteArray -> Stun.encodeXorAddress(peer, tid) }
            requestWith(Stun.CREATE_PERMISSION) { tid -> listOf(Stun.Attr(Stun.A_XOR_PEER_ADDRESS, xpeer(tid))) }
            val b = requestWith(Stun.CHANNEL_BIND) { tid ->
                listOf(Stun.Attr(Stun.A_CHANNEL_NUMBER, Stun.channelNumber(ch)), Stun.Attr(Stun.A_XOR_PEER_ADDRESS, xpeer(tid)))
            }
            if (b?.cls == Stun.SUCCESS) { bound[ch] = peer; ready += peer }
        }
        timers.execute(job)
        timers.scheduleWithFixedDelay(job, 240, 240, TimeUnit.SECONDS)
    }

    /** Sends [data] to [peer] through the relay: ChannelData once bound, a Send indication until then. */
    fun send(peer: InetSocketAddress, data: ByteArray): Boolean {
        val l = link ?: return false
        if (closed) return false
        val ch = channels[peer]
        return try {
            if (ch != null && peer in ready) l.write(Stun.channelData(ch, data, l.reliable))
            else {
                val tid = Stun.newTid()
                l.write(Stun.encode(Stun.type(Stun.SEND, Stun.INDICATION), tid,
                    listOf(Stun.Attr(Stun.A_XOR_PEER_ADDRESS, Stun.encodeXorAddress(peer, tid)), Stun.Attr(Stun.A_DATA, data))))
            }
            true
        } catch (e: Exception) { false }
    }

    private fun request(method: Int, attrs: List<Stun.Attr>, auth: Boolean) = requestWith(method, auth) { attrs }

    /** One transaction, with stale-nonce retries. [attrs] gets the transaction id (XOR addresses need it). */
    private fun requestWith(method: Int, auth: Boolean = true, attrs: (ByteArray) -> List<Stun.Attr>): Stun.Message? {
        repeat(3) {
            val l = link ?: return null
            val tid = Stun.newTid()
            val all = if (!auth) attrs(tid) else attrs(tid) + listOf(
                Stun.Attr(Stun.A_USERNAME, login.username.toByteArray(Charsets.UTF_8)),
                Stun.Attr(Stun.A_REALM, realm.toByteArray(Charsets.UTF_8)),
                Stun.Attr(Stun.A_NONCE, nonce.toByteArray(Charsets.UTF_8)))
            val bytes = Stun.encode(Stun.type(method, Stun.REQUEST), tid, all, if (auth) key else null, fingerprint = true)
            val resp = roundTrip(l, tid, bytes) ?: return null
            if (resp.cls == Stun.ERROR && resp.error()?.first == 438) {
                resp.text(Stun.A_NONCE)?.let { nonce = it }
                return@repeat
            }
            // A success answer to an authenticated request must carry a valid MESSAGE-INTEGRITY.
            if (auth && resp.cls == Stun.SUCCESS && !Stun.checkIntegrity(resp, key)) return null
            return resp
        }
        return null
    }

    private fun roundTrip(l: Link, tid: ByteArray, bytes: ByteArray): Stun.Message? {
        val id = CallCrypto.hex(tid)
        val q = LinkedBlockingQueue<Stun.Message>()
        pending[id] = q
        try {
            if (l.reliable) { l.write(bytes); return q.poll(6, TimeUnit.SECONDS) }
            // 250 ms, doubling: under 4 s before UDP counts as blocked and TLS on 443 is tried.
            var wait = 250L
            repeat(4) {
                l.write(bytes)
                q.poll(wait, TimeUnit.MILLISECONDS)?.let { return it }
                wait *= 2
            }
            return null
        } catch (e: Exception) { return null } finally { pending.remove(id) }
    }

    private fun readLoop(l: Link) {
        val buf = ByteArray(65_600)
        while (!closed && link === l) {
            val n = l.read(buf)
            if (n < 0) break
            if (Stun.isChannelData(buf, n)) {
                val (ch, data) = Stun.parseChannelData(buf, n) ?: continue
                bound[ch]?.let { peer -> try { onData(peer, data) } catch (_: Exception) {} }
                continue
            }
            val m = Stun.decode(buf, n) ?: continue
            if (m.cls == Stun.INDICATION && m.method == Stun.DATA) {
                val peer = m.xorAddress(Stun.A_XOR_PEER_ADDRESS) ?: continue
                val data = m.attr(Stun.A_DATA) ?: continue
                try { onData(peer, data) } catch (_: Exception) {}
            } else if (m.cls == Stun.SUCCESS || m.cls == Stun.ERROR) {
                pending[CallCrypto.hex(m.tid)]?.offer(m)
            }
        }
    }

    /** Releases the allocation (Refresh with lifetime 0) and closes the link. */
    override fun close() {
        if (closed) return
        val l = link
        if (l != null && relayed != null) try {
            val tid = Stun.newTid()
            l.write(Stun.encode(Stun.type(Stun.REFRESH, Stun.REQUEST), tid, listOf(Stun.Attr(Stun.A_LIFETIME, Stun.u32(0)),
                Stun.Attr(Stun.A_USERNAME, login.username.toByteArray(Charsets.UTF_8)),
                Stun.Attr(Stun.A_REALM, realm.toByteArray(Charsets.UTF_8)),
                Stun.Attr(Stun.A_NONCE, nonce.toByteArray(Charsets.UTF_8))), key, fingerprint = true))
        } catch (_: Exception) {}
        closed = true
        refresher?.cancel(false)
        timers.shutdownNow()
        try { l?.close() } catch (_: Exception) {}
        link = null
        key.fill(0)
    }

    companion object {
        /** "turn:host:port?transport=udp", "turns:host:443?transport=tcp". Null for anything else. */
        fun parseUrl(u: String): Server? {
            val tls = u.startsWith("turns:")
            if (!tls && !u.startsWith("turn:")) return null
            val rest = u.substringAfter(':')
            val hostPort = rest.substringBefore('?')
            val query = rest.substringAfter('?', "")
            val transport = query.split('&').firstOrNull { it.startsWith("transport=") }?.substringAfter('=')?.lowercase()
                ?: if (tls) "tcp" else "udp"
            val (host, portText) = if (hostPort.startsWith("[")) hostPort.substringAfter('[').substringBefore(']') to hostPort.substringAfter("]:", "")
                else hostPort.substringBefore(':') to hostPort.substringAfter(':', "")
            val port = portText.toIntOrNull() ?: if (tls) 5349 else 3478
            if (host.isEmpty() || port !in 1..65535) return null
            return when (transport) {
                "udp" -> if (tls) null else Server(host, port, tls = false, udp = true)
                "tcp" -> Server(host, port, tls = tls, udp = false)
                else -> null
            }
        }

        /** UDP first, then TLS on 443 (gets through most firewalls), then the rest in the Worker's order. */
        fun order(list: List<Server>): List<Server> {
            val udp = list.filter { it.udp }
            val tls443 = list.filter { it.tls && it.port == 443 }
            return (udp + tls443 + list).distinct()
        }

        /** What the call screen says when the relay's name couldn't be looked up. */
        const val CANT_REACH = "Can't reach the call relay right now"

        /** Why the last [start] failed ([CANT_REACH] or ""), for the call screen. Never an address. */
        @Volatile var lastProblem = ""; private set

        /**
         * Looks [host] up with the system resolver (IPv4 first), [tries] times a second apart; null if
         * it can't. An IP literal is used as it is (no lookup at all).
         */
        fun lookup(host: String, tries: Int = 3): InetAddress? {
            if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return CallLogic.literal(host)
            // Ultra privacy: names are never looked up with the system resolver (calls are off then anyway).
            if (io.github.ravenor907.muninn.net.UltraPrivacy.on) return null
            repeat(tries) { n ->
                if (n > 0) try { Thread.sleep(1_000) } catch (e: InterruptedException) { return null }
                try {
                    val all = InetAddress.getAllByName(host)
                    (all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull())?.let { return it }
                } catch (e: Exception) {}
            }
            return null
        }

        /** The IPv6 address of [host] (one lookup), or null. An IP literal has no other address: null. */
        fun lookup6(host: String): InetAddress? {
            if (host.all { it.isDigit() || it == '.' } || host.contains(':')) return null
            if (io.github.ravenor907.muninn.net.UltraPrivacy.on) return null
            return try { InetAddress.getAllByName(host).firstOrNull { it is Inet6Address } } catch (e: Exception) { null }
        }
    }
}
