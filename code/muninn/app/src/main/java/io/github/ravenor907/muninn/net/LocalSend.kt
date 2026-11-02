package io.github.ravenor907.muninn.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.webkit.MimeTypeMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * LocalSend protocol v2 (github.com/localsend/protocol), so Muninn works with Huginn and with the
 * LocalSend apps: multicast discovery on 224.0.0.167:53317 (shared, so the LocalSend app runs beside
 * Muninn), an HTTPS server on the first free port from 53318 (announced as "port"), and uploads to
 * other devices. Every incoming transfer waits for the person to accept it.
 *
 * Encryption is LocalSend's HTTPS mode: our server uses our own self-signed certificate (see
 * [Tls]) and we announce its SHA-256 as our fingerprint. When sending, the other device's
 * certificate must have the fingerprint it announced, or nothing is sent.
 */
class LocalSend(private val context: Context, val identity: Identity) {
    /** Our certificate, made once and kept in the app's private files. Null only if it can't be made: then plain HTTP. */
    private val tls: Tls? by lazy { Tls.load(File(context.filesDir, "tls")) }

    /** What other devices know us by: our certificate's hash, or (without one) the random id. */
    val fingerprint: String get() = tls?.fingerprint ?: identity.fingerprint

    data class Device(
        val id: String, val alias: String, val model: String, val type: String,
        val ip: String, val port: Int, val protocol: String,
        /** Huginn and Muninn add these; plain LocalSend apps don't chat. */
        val chatKey: String?, val chatName: String?, val app: String?,
        val seen: Long = System.currentTimeMillis(),
        /** Cap s1: its chat key signed this certificate, so it really is that person's. Without s1, only an extra route. */
        val proven: Boolean = false,
    ) {
        val canChat get() = chatKey != null
    }

    data class Request(val from: String, val count: Int, val size: Long, val names: String)

    /** A transfer in the Files tab's Recent list. [sent]: going out (else coming in). [uri]: a received file's place in Downloads, for Open. */
    data class Item(val name: String, val status: String, val fraction: Float, val failed: Boolean, val sent: Boolean = false, val uri: String = "")

    /**
     * The transfer under way: overall, and each file ([files]). [started]: when bytes began to move, for speed and time
     * left. [id]: this transfer's own. [preparing]: still being copied and checked before it goes. [peer]: the other side.
     */
    data class Transfer(
        val active: Boolean = false, val sending: Boolean = false, val status: String = "",
        val done: Long = 0, val total: Long = 0, val started: Long = 0, val files: List<Item> = emptyList(),
        val id: String = "", val preparing: Boolean = false, val peer: String = "",
    ) {
        val fraction get() = if (total > 0) done.toFloat() / total else 0f
        /** "2.1 MB/s · 12 s left", or "" before there's enough to tell. */
        fun pace(now: Long = System.currentTimeMillis()) = if (started == 0L) "" else FileText.pace(done, total, now - started)
    }

    private val _finished = MutableStateFlow<Pair<Boolean, String>?>(null)
    /** How the last transfer ended (shown in Files until the next one starts). */
    val finished: StateFlow<Pair<Boolean, String>?> = _finished.asStateFlow()
    /** The [Transfer.id] that [finished] is about. */
    @Volatile var finishedId = ""
        private set

    /** This phone's Nostr mailbox, so Wi-Fi contacts can keep chatting from anywhere. */
    var mailbox: String? = null

    /** Extra routes (chat) served on the same port. Returns (status, json body) or null. */
    var extraRoute: ((method: String, path: String, body: String, ip: String) -> Pair<Int, String>?)? = null

    /** Cap s1: the contact's chat key a hidden device's hint stands for, if it's one of mine. */
    var hintKey: ((String) -> String?)? = null

    /** Whether a chat key is one of my contacts: with Nearby off only they are listed and answered. */
    var isContact: ((String) -> Boolean)? = null

    /**
     * Nearby off: where contacts were last reached on the home Wi-Fi, so they're asked straight there instead of
     * anything being announced. Chat keeps it sealed in the vault ([loadPeers], [keepPeers]); never shown.
     */
    var keepPeers: ((String) -> Unit)? = null
    private val peers = ConcurrentHashMap<String, KnownPeer>()
    private data class KnownPeer(val ip: String, val port: Int, val protocol: String, val seen: Long)

    /** Our own server's port: the first free one from [SERVER_FIRST]. LocalSend's own app keeps 53317. */
    @Volatile var serverPort = SERVER_FIRST
        private set

    /** Cap s1: my chat key vouching for my certificate, made once (the certificate never changes). */
    private val proof: String? by lazy { tls?.let { Proof.route(identity.keys.private, identity.publicKey, it.fingerprint) } }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()
    private val _transfer = MutableStateFlow(Transfer())
    val transfer: StateFlow<Transfer> = _transfer.asStateFlow()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** One-line results for a toast ("Received 3 files from…"). */
    val messages = MutableSharedFlow<Pair<Boolean, String>>(extraBufferCapacity = 16)
    /** A device asked for a PIN when sending to it. */
    val pinNeeded = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 4)

    private val found = ConcurrentHashMap<String, Device>()
    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    private var udp: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    @Volatile private var running = false

    // Receiving: the one transfer at a time LocalSend allows.
    private class InFile(val name: String, val size: Long, val sha: String?) {
        val token: String = UUID.randomUUID().toString()
        @Volatile var received = 0L
        @Volatile var done = false
        @Volatile var failed = false
        @Volatile var savedAs: Received.Target? = null
    }
    /** [kids]: an app a parent sent to this Kids' TV (TvApps). */
    private class Session(val id: String, val from: String, val ip: String, val files: Map<String, InFile>, val kids: Boolean = false) {
        @Volatile var touched = System.currentTimeMillis()
    }
    @Volatile private var session: Session? = null
    private var pendingFiles: Map<String, InFile>? = null
    private var pendingFrom = ""
    private var pendingIp = ""
    private val decision = Object()
    @Volatile private var answer: Boolean? = null

    // Sending.
    private val stopSend = AtomicBoolean(false)
    @Volatile private var sendConn: HttpURLConnection? = null
    /** [send] took it over (from [preparing]): its own thread ends it. */
    @Volatile private var sendBusy = false

    /**
     * Hidden from people nearby: no name, chat key or mailbox goes out, only a hint that changes every day, which only
     * contacts (who have the key) can match (cap s1). Either way the chat key's proof says this certificate is mine.
     */
    fun info(): JSONObject = JSONObject()
        .put("alias", if (identity.visibleNearby) identity.deviceName else Identity.HIDDEN_NAME)
        .put("version", "2.1")
        .put("deviceModel", "Muninn")
        .put("deviceType", "mobile")
        .put("fingerprint", fingerprint)
        .put("port", serverPort)
        .put("protocol", if (tls != null) "https" else "http")
        .put("download", false)
        .put("chatApp", "muninn")
        .apply {
            if (identity.visibleNearby) {
                put("chatKey", identity.publicKey)
                mailbox?.let { put("mailbox", it) }
            } else put("hint", Proof.hintNow(identity.publicKey))
            proof?.let { put("chatProof", it) }
            if (identity.nearbyRoom) put("chatName", identity.username)
        }

    // ------------------------------------------------------------------ start and stop

    fun start() {
        if (running) return
        running = true
        _error.value = null
        // Our server takes the first free port from 53318, so the LocalSend app (53317) can run beside Muninn.
        server = null
        for (port in SERVER_FIRST until SERVER_FIRST + SERVER_TRIES) {
            server = try { listen(port) } catch (e: Exception) { null }
            if (server != null) { serverPort = port; break }
        }
        if (server == null) {
            _error.value = "Nearby sharing couldn't start. Restart Muninn and try again."
            running = false
            return
        }
        pool.execute { acceptLoop() }
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("muninn").apply { setReferenceCounted(false); if (wifiLevel == PowerLogic.Radio.FULL) acquire() }
            // Shared with the LocalSend app and Huginn on the same device: the address is reused, then bound.
            udp = MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                for (nif in lanInterfaces()) {
                    try { joinGroup(InetSocketAddress(GROUP, PORT), nif) } catch (_: Exception) {}
                }
            }
            pool.execute { udpLoop() }
        } catch (e: Exception) {
            // Discovery falls back to scanning the network.
        }
        announce()
        askContacts()
        pool.execute {
            var quietFor = 0
            while (running) {
                Thread.sleep(60_000)
                if (!running) break
                // A sender that went quiet ends its session, whatever the Wi-Fi discovery is doing.
                val s = session
                if (s != null && System.currentTimeMillis() - s.touched > 120_000) endSession(false, "${s.from} stopped sending.")
                // Saving battery: off Wi-Fi nothing is announced; with the screen off only every 5 minutes.
                val w = wifiLevel
                if (w == PowerLogic.Radio.OFF || (w == PowerLogic.Radio.LOW && ++quietFor < 5)) continue
                quietFor = 0
                announce()
                askContacts()
                val cutoff = System.currentTimeMillis() - 10 * 60_000
                found.entries.removeIf { it.value.seen < cutoff }
                publish()
            }
        }
    }

    /**
     * Wi-Fi discovery (see PowerLogic). FULL: always on. LOW and OFF let go of the multicast lock, which
     * otherwise keeps the Wi-Fi chip waking for every multicast packet on the network; messages sent
     * straight to this phone still arrive.
     */
    @Volatile private var wifiLevel = PowerLogic.Radio.FULL

    fun discovery(r: PowerLogic.Radio) {
        if (r == wifiLevel) return
        val was = wifiLevel
        wifiLevel = r
        try {
            if (r == PowerLogic.Radio.FULL) { lock?.let { if (!it.isHeld) it.acquire() }; if (running) announce() }
            else lock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        if (was == PowerLogic.Radio.OFF && r != PowerLogic.Radio.OFF && running) { announce(); askContacts() }   // back on Wi-Fi: say hello now
    }

    /** Our HTTPS server (plain HTTP without a certificate) on [port]. */
    private fun listen(port: Int): ServerSocket {
        val t = tls
        return if (t != null) {
            (t.serverContext.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                // Ask for the sender's certificate but don't require it (some Huginn and Muninn apps have
                // none): when one is shown, the fingerprint the sender claims must match it.
                wantClientAuth = true
                reuseAddress = true
                try { bind(InetSocketAddress(port)) } catch (e: Exception) { try { close() } catch (_: Exception) {}; throw e }
            }
        } else ServerSocket().apply { reuseAddress = true; try { bind(InetSocketAddress(port)) } catch (e: Exception) { try { close() } catch (_: Exception) {}; throw e } }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        lock?.let { if (it.isHeld) it.release() }
    }

    // ------------------------------------------------------------------ discovery

    /**
     * Hidden from people nearby: nothing at all goes out about this phone on the Wi-Fi (no announcement, no answer to
     * a scan, no certificate shown when asking others). It still finds others and can send to them.
     */
    private fun silent(): Boolean = !identity.visibleNearby

    /** Nearby off ([Identity.nearbyOff]): no scans, nobody but contacts listed or answered. */
    private fun off(): Boolean = identity.nearbyOff

    /** The contact a device's info stands for (its chat key, or the hint of a hidden one), or null. */
    private fun contactIn(o: JSONObject): String? {
        val k = o.optString("chatKey").ifEmpty { null } ?: o.optString("hint").ifEmpty { null }?.let { hintKey?.invoke(it) } ?: return null
        return k.takeIf { isContact?.invoke(it) == true }
    }

    /**
     * Nearby was turned on or off. Off: everyone who isn't a contact leaves the list at once, and contacts are asked
     * straight where they were last seen. On: announced again.
     */
    fun nearbyChanged() {
        if (off()) {
            found.entries.removeIf { e -> e.value.chatKey?.let { isContact?.invoke(it) } != true }
            publish()
            askContacts()
        } else announce()
    }

    /**
     * Nearby off: each contact last seen on this Wi-Fi (and not heard from in the last 2 minutes) is asked straight at
     * that address, with this phone's info (its hint, never its name). Its answer only counts if it proves a contact.
     */
    fun askContacts() {
        if (!running || !off() || wifiLevel == PowerLogic.Radio.OFF) return
        val fresh = System.currentTimeMillis() - 120_000
        for ((k, p) in peers.entries.toList()) {
            if (found.values.any { it.chatKey == k && it.seen > fresh } || !asking(p.ip)) continue
            pool.execute { register(p.ip, p.port, p.protocol, scan = false) }
        }
    }

    /** The contacts' last addresses as Chat kept them (JSON from [keepPeers]). */
    fun loadPeers(json: String?) {
        val o = try { JSONObject(json ?: return) } catch (_: Exception) { return }
        for (k in o.keys()) {
            val p = o.optJSONObject(k) ?: continue
            val ip = p.optString("ip")
            if (ip.isEmpty() || !isNeighbourText(ip)) continue
            peers[k] = KnownPeer(ip, p.optInt("port", SERVER_FIRST), p.optString("protocol", "https"), p.optLong("seen"))
        }
        askContacts()
    }

    /** A contact was reached at this address: kept for when Nearby is off (at most [MAX_PEERS], the newest). */
    private fun keepPeer(key: String, d: Device) {
        if (isContact?.invoke(key) != true) return
        val old = peers[key]
        if (old != null && old.ip == d.ip && old.port == d.port && old.protocol == d.protocol && d.seen - old.seen < 6 * 3_600_000L) return
        peers[key] = KnownPeer(d.ip, d.port, d.protocol, d.seen)
        while (peers.size > MAX_PEERS) peers.entries.minByOrNull { it.value.seen }?.let { peers.remove(it.key) } ?: break
        val o = JSONObject()
        peers.forEach { (k, p) -> o.put(k, JSONObject().put("ip", p.ip).put("port", p.port).put("protocol", p.protocol).put("seen", p.seen)) }
        try { keepPeers?.invoke(o.toString()) } catch (_: Exception) {}
    }

    /** A kept address that is still a private one (anything else was never this home network). */
    private fun isNeighbourText(ip: String): Boolean = try { Lan.isPrivate(InetAddress.getByName(ip)) && ip.none { it.isLetter() } } catch (_: Exception) { false }

    fun announce() {
        if (silent()) return
        val msg = info().put("announce", true).toString().toByteArray()
        pool.execute {
            // Send on each Wi-Fi interface: left alone, Android sends multicast out the mobile-data
            // connection, where nobody is listening.
            val sock = udp ?: return@execute
            for (nif in lanInterfaces()) {
                try {
                    sock.networkInterface = nif
                    sock.send(DatagramPacket(msg, msg.size, InetAddress.getByName(GROUP), PORT))
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Multicast plus asking every address on this network, for routers that drop multicast: LocalSend apps on 53317,
     * Huginn and Muninn on 53318 (the first port they try). Nearby off: no scan, only the contacts asked straight.
     */
    fun refresh() {
        if (off()) { askContacts(); return }
        announce()
        if (_scanning.value) return
        val me = localAddress() ?: return
        _scanning.value = true
        val prefix = me.substringBeforeLast('.') + "."
        val scan = Executors.newFixedThreadPool(48)
        val left = AtomicLong(253)
        for (i in 1..254) {
            val ip = prefix + i
            if (ip == me) continue
            scan.execute {
                register(ip, PORT, "https", scan = true) || register(ip, PORT, "http", scan = true) || register(ip, SERVER_FIRST, "https", scan = true)
                if (left.decrementAndGet() <= 0L) _scanning.value = false
            }
        }
        scan.shutdown()
        pool.execute { scan.awaitTermination(30, TimeUnit.SECONDS); _scanning.value = false }
    }

    private fun udpLoop() {
        val buf = ByteArray(8192)
        while (running) {
            try {
                val p = DatagramPacket(buf, buf.size)
                udp?.receive(p) ?: return
                // Only devices on this very network (not routed in from somewhere farther away).
                if (!isNeighbour(p.address)) continue
                val o = JSONObject(String(p.data, 0, p.length))
                val fp = o.optString("fingerprint")
                if (Tls.same(fp, fingerprint)) continue   // our own announcement
                val ip = p.address.hostAddress ?: continue
                val announce = o.optBoolean("announce", o.optBoolean("announcement", false))
                // A multicast message proves nothing: a device is only listed once its certificate answered (register).
                if (!announce && known(ip, fp)) continue
                // Nearby off: only a contact's own announcement is answered, straight to it (never on multicast).
                if (off() && (!announce || contactIn(o) == null)) continue
                if (!asking(ip)) continue
                pool.execute {
                    if (!register(ip, o.optInt("port", PORT), o.optString("protocol", "https"), scan = false, expected = fp) && announce && !silent()) {
                        val reply = info().put("announce", false).toString().toByteArray()
                        try { udp?.send(DatagramPacket(reply, reply.size, InetAddress.getByName(GROUP), PORT)) } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                if (!running) return
            }
        }
    }

    /**
     * [expected]: the fingerprint the device announced; its certificate must match. Empty for a
     * scan, which can't know it yet: then the certificate it shows becomes its identity.
     */
    private fun register(ip: String, port: Int, protocol: String, scan: Boolean, expected: String = ""): Boolean = try {
        // Nearby off: only ever sent to a contact (their announcement or their last address), so it says who this is
        // (the hint, never the name) and shows the certificate the contact checks. A Kids' TV hidden from people
        // nearby only asks.
        val quiet = silent() && !off()
        // Hidden: only asks for their info, without saying who this phone is or showing its certificate.
        val c = open("$protocol://$ip:$port$API/" + (if (quiet) "info" else "register"), if (scan) 1500 else 5000, expected.ifEmpty { null }, anonymous = quiet)
        if (quiet) c.requestMethod = "GET"
        else {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(info().toString().toByteArray()) }
        }
        val ok = c.responseCode == 200
        if (ok) {
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            if (!o.has("port")) o.put("port", port)
            // Over HTTPS the certificate is the device's identity, whatever the answer claims.
            val proven = (c as? HttpsURLConnection)?.let { h ->
                (h.serverCertificates.firstOrNull() as? X509Certificate)?.let { Tls.fingerprintOf(it) }
            }
            remember(o, ip, protocol, proven)
        }
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    /**
     * [proven]: the fingerprint of a certificate the device actually showed us; it wins over what the message claims.
     * Over HTTPS nothing is learned without one (a spoofed announce can't take over another device's entry).
     */
    private fun remember(o: JSONObject, ip: String, fallback: String, proven: String? = null) {
        val protocol = o.optString("protocol", fallback)
        if (protocol == "https" && proven == null) return
        var id = proven ?: o.optString("fingerprint")
        if (id.isEmpty() || Tls.same(id, fingerprint)) return
        if (protocol == "https") id = id.uppercase()   // certificate hashes: one spelling, so a device is listed once
        // A fingerprint seen over HTTPS is a certificate hash. Nobody else may take it over by
        // claiming it with encryption off, which would skip the certificate check when sending.
        if (protocol != "https" && found[id]?.protocol == "https") return
        // Cap s1: whose chat it is. A hidden device sends a hint only a contact can match; its proof must check out
        // against this certificate. A wrong proof means it isn't theirs; no proof at all is an app without s1 (not proven).
        val claimed = o.optString("chatKey").ifEmpty { null } ?: o.optString("hint").ifEmpty { null }?.let { hintKey?.invoke(it) }
        val sig = o.optString("chatProof")
        val ok = claimed != null && proven != null && sig.isNotEmpty() && Proof.routeOk(claimed, proven, sig)
        val key = if (ok || sig.isEmpty()) claimed else null
        // Nearby off: nobody but contacts is listed.
        if (off() && (key == null || isContact?.invoke(key) != true)) return
        val d = Device(
            id, deviceName(o), o.optString("deviceModel", ""), o.optString("deviceType", "mobile"),
            ip, o.optInt("port", PORT), protocol,
            key, o.optString("chatName").ifEmpty { null }, o.optString("chatApp").ifEmpty { null }, proven = ok,
        )
        found[id] = d
        if (key != null && ok) keepPeer(key, d)   // only a route its chat key proved is asked again with Nearby off
        publish()
    }

    /** Already listed at that address with that certificate. */
    private fun known(ip: String, fp: String) = found[fp.uppercase()]?.let { it.ip == ip } == true

    /** At most one register to an address every 10 s, whatever floods the multicast group. */
    private val asked = ConcurrentHashMap<String, Long>()
    private fun asking(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        if (asked.size > 512) asked.entries.removeIf { now - it.value > 10_000 }
        val last = asked[ip]
        if (last != null && now - last < 10_000) return false
        asked[ip] = now
        return true
    }

    private fun publish() {
        _devices.value = found.values.sortedBy { it.alias.lowercase() }
    }

    fun device(id: String): Device? = found[id]

    /**
     * Where to reach [chatKey] on the Wi-Fi: a device whose chat key proved this certificate, newest first; one that
     * couldn't (no s1) only when not [strict]. Never by name order, which anyone can game.
     */
    fun deviceFor(chatKey: String, strict: Boolean = false): Device? {
        val all = found.values.filter { it.chatKey == chatKey }
        return all.filter { it.proven }.maxByOrNull { it.seen } ?: if (strict) null else all.maxByOrNull { it.seen }
    }

    // ------------------------------------------------------------------ server

    /** At most this many connections at once: anything past it is closed straight away (nobody can pile them up). */
    private val slots = java.util.concurrent.Semaphore(MAX_CONNECTIONS)

    private fun acceptLoop() {
        while (running) {
            val s: Socket = (try { server?.accept() } catch (e: Exception) { null }) ?: if (running) continue else return
            // Only devices on this very network: not over mobile data, a VPN or anything routed in from farther away.
            if (!isNeighbour(s.inetAddress) || !slots.tryAcquire()) { try { s.close() } catch (_: Exception) {}; continue }
            pool.execute { try { handle(s) } catch (_: Exception) {} finally { try { s.close() } catch (_: Exception) {}; slots.release() } }
        }
    }

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun readRequest(input: InputStream): Req? {
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) break
            if (head.length > 64 * 1024) return null
        }
        val lines = head.toString().split("\r\n")
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val target = first[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val headers = lines.drop(1).filter { it.contains(':') }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return Req(first[0], path, query, headers, input)
    }

    private fun reply(out: OutputStream, code: Int, body: String = "") {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; else -> "Error" }
        out.write("HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun handle(s: Socket) {
        s.soTimeout = 15_000   // the request itself must come quickly; a transfer gets longer below
        // The certificate the sender showed, if any (LocalSend apps and Huginn and Muninn with a certificate do).
        val peerFp: String? = if (s is SSLSocket) {
            s.soTimeout = 15_000   // a stalled handshake shouldn't hold a thread for a minute
            s.startHandshake()
            s.soTimeout = 60_000
            // Throws when the sender showed no certificate.
            try { (s.session.peerCertificates.firstOrNull() as? X509Certificate)?.let { Tls.fingerprintOf(it) } } catch (_: Exception) { null }
        } else null
        // A sender that showed a certificate must claim that certificate's fingerprint, or it is
        // pretending to be another device: answer, but don't list it (LocalSend does the same).
        // One that showed none proves nothing: it's asked for its certificate instead (register), never just believed.
        val learn = { o: JSONObject, ip: String ->
            when {
                // Nearby off: nobody is asked back on their word alone.
                peerFp == null && off() -> {}
                peerFp == null -> if (o.optString("protocol", "https") == "https" && asking(ip))
                    pool.execute { register(ip, o.optInt("port", PORT), "https", scan = false, expected = o.optString("fingerprint")) }
                    else remember(o, ip, "http")
                Tls.same(o.optString("fingerprint"), peerFp) -> remember(o, ip, "https", peerFp)
                else -> {}
            }
        }
        val input = BufferedInputStream(s.getInputStream(), 1 shl 16)
        val out = s.getOutputStream()
        val r = readRequest(input) ?: return
        val ip = (s.inetAddress as? Inet4Address)?.hostAddress ?: s.inetAddress.hostAddress ?: ""
        val length = r.headers["content-length"]?.toLongOrNull() ?: 0L
        s.soTimeout = 60_000

        if (r.method == "POST" && r.path == "$API/upload") {
            upload(r, length, ip, out)
            return
        }
        // Chat envelopes are at most 64 KB (protocol 9); other requests a little JSON. Read as it arrives, never all at once up front.
        // A linked device's photo and file pieces are bigger: Chat refuses anything that big in another room.
        val most = if (r.path == "/api/huginn/v1/message") MAX_LINK_BODY else MAX_BODY
        if (length < 0 || length > most) { reply(out, 413); return }
        val body = java.io.ByteArrayOutputStream(minOf(length, 64L * 1024).toInt()).also { b ->
            val buf = ByteArray(16 * 1024)
            var left = length
            while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; b.write(buf, 0, n); left -= n }
        }.toByteArray()
        val text = String(body)

        extraRoute?.invoke(r.method, r.path, text, ip)?.let { (code, json) -> reply(out, code, json); return }

        when (r.path) {
            "$API/register", "/api/localsend/v1/register" -> {
                val o = try { JSONObject(text) } catch (_: Exception) { null }
                // Nearby off: answered only for a contact whose chat key proved the certificate it showed here.
                val friend = off() && peerFp != null && o != null && contactIn(o)?.let { k ->
                    Tls.same(o.optString("fingerprint"), peerFp) && Proof.routeOk(k, peerFp, o.optString("chatProof"))
                } == true
                if (o != null && (!off() || friend)) learn(o, ip)
                // Hidden: no answer that says anything about this phone.
                if (silent() && !friend) reply(out, 404) else reply(out, 200, info().toString())
            }
            "$API/info", "/api/localsend/v1/info" -> if (silent()) reply(out, 404) else reply(out, 200, info().toString())
            "$API/prepare-upload" -> prepare(text, ip, out, learn)
            "$API/cancel" -> {
                val sess = session
                if (sess != null && r.query["sessionId"] == sess.id) endSession(false, "${sess.from} cancelled sending.")
                reply(out, 200)
            }
            else -> reply(out, 404)
        }
    }

    /** "Wants to send" boxes turned down (or left unanswered) per address: past [PROMPTS] in 10 minutes, it has to wait. */
    private val prompts = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun mayPrompt(ip: String, now: Long = System.currentTimeMillis()): Boolean {
        val q = prompts[ip] ?: return true
        synchronized(q) { while (q.isNotEmpty() && now - q.first() > 600_000) q.removeFirst(); return q.size < PROMPTS }
    }

    private fun turnedDown(ip: String, now: Long = System.currentTimeMillis()) {
        if (prompts.size > 256) prompts.entries.removeIf { q -> synchronized(q.value) { q.value.all { now - it > 600_000 } } }
        val q = prompts.getOrPut(ip) { ArrayDeque() }
        synchronized(q) { q.addLast(now); while (q.size > PROMPTS) q.removeFirst() }
    }

    private fun prepare(text: String, ip: String, out: OutputStream, learn: (JSONObject, String) -> Unit) {
        if (session != null || pendingFiles != null) { reply(out, 409); return }
        val o = try { JSONObject(text) } catch (e: Exception) { reply(out, 400); return }
        val from = o.optJSONObject("info") ?: JSONObject()
        learn(from, ip)
        val files = o.optJSONObject("files") ?: run { reply(out, 400); return }
        val map = LinkedHashMap<String, InFile>()
        var total = 0L
        for (k in files.keys()) {
            val f = files.getJSONObject(k)
            val inf = InFile(f.optString("fileName", "file"), f.optLong("size"), f.optString("sha256").ifEmpty { null }.takeUnless { it == "null" })
            total += inf.size
            map[k] = inf
        }
        val alias = deviceName(from)
        val free = Received.freeBytes(context)
        if (total + 64L * 1024 * 1024 > free) {
            reply(out, 403)
            messages.tryEmit(false to "$alias tried to send ${size(total)}, but there's only ${size(free)} free. Free up space and try again.")
            return
        }
        // A Kids' TV shows no "wants to send" box and takes nothing from anyone, except an app a parent said is
        // coming, file for file by SHA-256 and size (TvApps, 7.9). The parent's word may come a moment after the files.
        if (io.github.ravenor907.muninn.tv.KidMode.saved(context)) {
            val parts = map.values.map { io.github.ravenor907.muninn.tv.TvApps.Part(it.sha?.lowercase() ?: "", it.size) }
            val apps = map.values.all { AppFiles.isApk(it.name) && it.sha != null }
            if (!apps || io.github.ravenor907.muninn.tv.TvApps.await(parts, 10_000) == null) { reply(out, 403); return }
            synchronized(decision) {
                if (session != null || pendingFiles != null) { reply(out, 409); return }
                open(map, alias, ip, total, out, kids = true)
            }
            return
        }
        // Asking again and again from one address would keep a box up for a minute each time: past a few, wait.
        if (!mayPrompt(ip)) { reply(out, 429); return }
        synchronized(decision) {
            pendingFiles = map; pendingFrom = alias; pendingIp = ip; answer = null
            val names = map.values.map { it.name.substringAfterLast('/') }.sorted()
            _request.value = Request(alias, map.size, total, names.take(3).joinToString(", ") + if (names.size > 3) " and ${names.size - 3} more" else "")
            val deadline = System.currentTimeMillis() + 60_000
            while (answer == null && System.currentTimeMillis() < deadline) (decision as Object).wait(500)
            val yes = answer == true
            _request.value = null
            pendingFiles = null
            if (!yes) {
                turnedDown(ip)
                if (answer == null) messages.tryEmit(false to "No answer in a minute, so the files from $alias were turned down.")
                reply(out, 403)
                return
            }
            open(map, alias, ip, total, out, kids = false)
        }
    }

    /** The files are accepted: the session starts and the sender gets a token per file. */
    private fun open(map: Map<String, InFile>, alias: String, ip: String, total: Long, out: OutputStream, kids: Boolean) {
        val sess = Session(UUID.randomUUID().toString(), alias, ip, map, kids)
        session = sess
        _finished.value = null
        _transfer.value = Transfer(true, false, "Receiving from $alias…", 0, total, System.currentTimeMillis(),
            files = map.values.map { it.toItem() }, id = UUID.randomUUID().toString(), peer = alias)
        NearbyService.hold(context, true)
        val tokens = JSONObject().apply { map.forEach { (k, f) -> put(k, f.token) } }
        reply(out, 200, JSONObject().put("sessionId", sess.id).put("files", tokens).toString())
    }

    fun accept() = synchronized(decision) { answer = true; (decision as Object).notifyAll() }
    fun decline() = synchronized(decision) { answer = false; (decision as Object).notifyAll() }

    private fun upload(r: Req, length: Long, ip: String, out: OutputStream) {
        val sess = session ?: run { reply(out, 409); return }
        val f = sess.files[r.query["fileId"]]
        if (r.query["sessionId"] != sess.id) { reply(out, 409); return }
        if (f == null || f.token != r.query["token"] || ip != sess.ip) { reply(out, 403); return }
        // Never more than the size it was accepted at: a sender can't fill the storage past what the person agreed to.
        if (length > f.size) { reply(out, 413); return }
        // Saved into Downloads (MediaStore, where any Files app sees it), keeping folders the sender included
        // ("Photos/a.jpg"). Every file, an app too, on a phone or a TV: nothing received is opened or installed by itself.
        // Cap f2: in Download/Muninn/<the device's name>, like a chat's files (a Kids' TV keeps its own place for apps).
        val target = try { Received.create(context, f.name, if (sess.kids) "" else Sharing.folderFor("Muninn", sess.from)) } catch (e: Exception) { null }
        f.savedAs = target
        f.received = 0
        f.failed = false
        val md = if (f.sha != null) MessageDigest.getInstance("SHA-256") else null
        var ok = false
        if (target != null) try {
            target.out.use { fo ->
                val buf = ByteArray(1 shl 18)
                var left = length
                while (left > 0) {
                    val n = r.body.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    fo.write(buf, 0, n)
                    md?.update(buf, 0, n)
                    left -= n
                    f.received += n
                    sess.touched = System.currentTimeMillis()
                    progressIn(sess)
                }
                ok = left == 0L
            }
        } catch (e: Exception) {
            ok = false
        }
        if (target == null) {
            f.failed = true; reply(out, 500)
        } else if (ok && md != null && md.digest().joinToString("") { "%02x".format(it) } != f.sha!!.lowercase()) {
            target.discard(); f.failed = true; reply(out, 422)
        } else if (!ok) {
            target.discard(); f.failed = true; f.received = 0
        } else {
            target.finish(); f.done = true; reply(out, 200)
        }
        progressIn(sess)
        if (sess.files.values.all { it.done || it.failed }) {
            val done = sess.files.values.count { it.done }
            val failed = sess.files.values.any { it.failed }
            val first = sess.files.values.firstOrNull { it.done }?.let { it.savedAs?.name ?: it.name.substringAfterLast('/') } ?: ""
            endSession(!failed, when {
                failed -> "$done of ${sess.files.size} files from ${sess.from} arrived. Send the rest again."
                done == 1 -> "File from ${sess.from}: $first · Saved to ${Received.place(context)}"
                else -> "$done files from ${sess.from} · Saved to ${Received.place(context)}"
            })
        }
    }

    private fun progressIn(s: Session) {
        val total = s.files.values.sumOf { it.size }
        val got = s.files.values.sumOf { if (it.done) it.size else it.received }
        val now = s.files.values.map { it.toItem() }
        _transfer.update { it.copy(done = got, total = total, files = now) }
        NearbyService.progress()
        NearbyService.note(context, "Receiving ${TransferView.what(now.map { it.name })}", FileText.percent(got, total))
        _items.value = now + history
    }

    private fun InFile.toItem() = Item(
        savedAs?.name ?: name.substringAfterLast('/'),
        when { done -> "Saved to ${Received.place(context)}"; failed -> "Didn't arrive"; received == 0L -> "Waiting"
            else -> "Receiving ${FileText.percent(received, size)}% · ${size(received)} of ${size(size)}" },
        if (size > 0) received.toFloat() / size else if (done) 1f else 0f, failed,
        uri = if (done) savedAs?.let { Received.shareUri(context, it) } ?: "" else "",
    )

    private val history = mutableListOf<Item>()

    private fun endSession(ok: Boolean, message: String) {
        val s = session ?: return
        session = null
        s.files.values.forEach { f ->
            val item = f.toItem()   // before a discard, while it still knows where it was saved
            if (!f.done) f.savedAs?.discard()
            history.add(0, item.copy(fraction = if (f.done) 1f else 0f, failed = !f.done,
                status = if (f.done) "From ${s.from} · Saved to ${Received.place(context)}" else "From ${s.from} · Didn't arrive"))
        }
        // An app a parent sent to this Kids' TV: saved in Downloads like any file; it installs only once a parent
        // says Install on their phone (TvAppInstall, 7.15).
        if (ok && s.kids) {
            val apps = s.files.values.filter { it.done && it.sha != null }.mapNotNull { f ->
                f.savedAs?.let { t -> io.github.ravenor907.muninn.tv.TvAppInstall.Got(t.name, Received.shareUri(context, t), f.sha!!.lowercase(), f.size) }
            }
            (context.applicationContext as? io.github.ravenor907.muninn.Muninn)?.let { a -> if (apps.isNotEmpty()) io.github.ravenor907.muninn.tv.TvAppInstall.arrived(a, apps) }
        }
        while (history.size > 30) history.removeAt(history.size - 1)
        _items.value = history.toList()
        finishedId = _transfer.value.id
        _transfer.value = Transfer()
        _finished.value = ok to message
        NearbyService.hold(context, false)
        messages.tryEmit(ok to message)
    }

    // ------------------------------------------------------------------ sending

    /**
     * Files about to go to [peer] that are still being copied in and checked (an app sent to a TV): the strip and the
     * Kids' TV screen say "Getting ready" at once. Returns its id for [send], or null when another transfer runs.
     */
    fun preparing(peer: String, names: List<String>): String? {
        synchronized(this) {
            if (_transfer.value.active) return null
            val id = UUID.randomUUID().toString()
            _finished.value = null
            _transfer.value = Transfer(true, true, "Getting ready…", id = id, preparing = true, peer = peer,
                files = names.map { Item(it, "Getting ready", 0f, false, sent = true) })
            return id
        }
    }

    /** What [preparing] showed couldn't go after all (or was cancelled): [why] shows as how it ended. */
    fun notSent(id: String, why: String) {
        synchronized(this) {
            if (_transfer.value.id != id || !_transfer.value.preparing) return
            finishedId = id
            _transfer.value = Transfer()
        }
        if (why.isNotEmpty()) { _finished.value = false to why; messages.tryEmit(false to why) }
    }

    /** [prepared]: the id [preparing] gave; when that was cancelled meanwhile, nothing goes. */
    fun send(deviceId: String, files: List<File>, pin: String? = null, prepared: String? = null) {
        if (prepared != null && _transfer.value.id != prepared) return   // cancelled while it was made ready
        fun no(why: String) { if (prepared != null) notSent(prepared, why) else messages.tryEmit(false to why) }
        val dev = found[deviceId] ?: run { no("That device isn't nearby anymore. Look again and pick it."); return }
        val list = files.filter { it.isFile }
        if (list.isEmpty()) { no("Pick some files to send first."); return }
        val id = synchronized(this) {
            val now = _transfer.value
            if (now.active && !(now.preparing && now.id == prepared)) return
            stopSend.set(false)
            sendBusy = true
            val total = list.sumOf { it.length() }
            _finished.value = null
            // Apps are checked (SHA-256) before anything goes: "Getting ready" until then.
            (prepared ?: UUID.randomUUID().toString()).also { newId ->
                _transfer.value = Transfer(true, true, "Getting ready…", 0, total,
                    files = list.map { Item(it.name, "Waiting", 0f, false, sent = true) }, id = newId, preparing = true, peer = dev.alias)
            }
        }
        NearbyService.hold(context, true)
        // Over HTTPS, every request checks the device's certificate against the fingerprint it announced.
        val expected = if (dev.protocol == "https") dev.id else null
        pool.execute {
            val base = "${dev.protocol}://${dev.ip}:${dev.port}$API"
            try {
                // Apps go with their SHA-256: a Kids' TV takes only the exact files a parent named (TvApps).
                val shas = list.map { f -> if (AppFiles.isApk(f.name)) try { AppFiles.sha256(f) } catch (e: Exception) { null } else null }
                if (stopSend.get()) throw InterruptedException()
                _transfer.update { if (it.id == id) it.copy(preparing = false, status = "Waiting for ${dev.alias} to accept…") else it }
                val meta = JSONObject()
                list.forEachIndexed { i, f ->
                    val ext = f.extension.lowercase()
                    meta.put("f$i", JSONObject().put("id", "f$i").put("fileName", f.name).put("size", f.length())
                        .put("fileType", MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream")
                        .put("sha256", shas[i] ?: JSONObject.NULL).put("preview", JSONObject.NULL))
                }
                val c = open("$base/prepare-upload" + (pin?.let { "?pin=" + URLEncoder.encode(it, "UTF-8") } ?: ""), 5 * 60_000, expected)
                sendConn = c
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("info", info()).put("files", meta).toString().toByteArray()) }
                when (val code = c.responseCode) {
                    200 -> {
                        val o = JSONObject(c.inputStream.bufferedReader().readText())
                        val sid = o.getString("sessionId")
                        val tokens = o.getJSONObject("files")
                        val accepted = list.indices.filter { tokens.has("f$it") }
                        var sent = 0L
                        val rows = accepted.map { Item(list[it].name, "Waiting", 0f, false, sent = true) }.toMutableList()
                        _transfer.update { it.copy(started = System.currentTimeMillis(), files = rows.toList()) }
                        for ((n, i) in accepted.withIndex()) {
                            if (stopSend.get()) throw InterruptedException()
                            val f = list[i]
                            _transfer.update { it.copy(status = "Sending ${f.name} to ${dev.alias}…", total = accepted.sumOf { j -> list[j].length() }) }
                            var mineSent = 0L
                            val u = open("$base/upload?sessionId=${enc(sid)}&fileId=f$i&token=${enc(tokens.getString("f$i"))}", 60_000, expected)
                            sendConn = u
                            u.requestMethod = "POST"; u.doOutput = true
                            u.setFixedLengthStreamingMode(f.length())
                            u.setRequestProperty("Content-Type", "application/octet-stream")
                            u.outputStream.use { os ->
                                f.inputStream().use { ins ->
                                    val buf = ByteArray(1 shl 18)
                                    while (true) {
                                        if (stopSend.get()) throw InterruptedException()
                                        val r = ins.read(buf); if (r < 0) break
                                        os.write(buf, 0, r); sent += r; mineSent += r
                                        rows[n] = rows[n].copy(status = "Sending ${FileText.percent(mineSent, f.length())}%",
                                            fraction = if (f.length() > 0) mineSent.toFloat() / f.length() else 1f)
                                        _transfer.update { it.copy(done = sent, files = rows.toList()) }
                                        NearbyService.progress()
                                        NearbyService.note(context, "Sending ${TransferView.what(rows.map { it.name })}", FileText.percent(sent, _transfer.value.total))
                                    }
                                }
                            }
                            if (u.responseCode != 200) {
                                val left = accepted.drop(n).map { list[it].name }
                                throw java.io.IOException("${dev.alias} didn't take ${f.name}. Sent $n of ${accepted.size}. Not sent: ${left.take(4).joinToString(", ")}.")
                            }
                            u.disconnect()
                            rows[n] = rows[n].copy(status = "Sent", fraction = 1f)
                            _transfer.update { it.copy(files = rows.toList()) }
                        }
                        logSent(accepted.map { list[it].name }, dev.alias)
                        // Muninn and Huginn answer only once the file is saved in Downloads.
                        done(true, "Sent ${if (accepted.size == 1) "1 file" else "${accepted.size} files"} to ${dev.alias}" +
                            if (dev.app == "muninn" || dev.app == "huginn") " · Saved in their Downloads" else ".")
                    }
                    204 -> done(true, "${dev.alias} already has these files.")
                    401 -> { done(null, ""); pinNeeded.tryEmit(deviceId to (pin != null)) }
                    // A Kids' Device takes no files over the Wi-Fi (only apps its parents send): say that, not that someone said no.
                    403 -> done(false, if (dev.chatKey?.let { io.github.ravenor907.muninn.tv.TvParent.isKidDevice(it) } == true)
                        "Kids' Devices take photos, videos, voice messages and small files sent in a chat, not files over the Wi-Fi."
                        else "${dev.alias} turned the files down.")
                    409 -> done(false, "${dev.alias} is busy with another transfer. Try again in a moment.")
                    429 -> done(false, "${dev.alias} asked to wait a bit before sending again.")
                    else -> done(false, "${dev.alias} couldn't take the files (error $code).")
                }
            } catch (e: InterruptedException) {
                done(false, "Sending stopped.")
            } catch (e: java.io.IOException) {
                // The certificate check failing shows up as a handshake error caused by our CertificateException.
                if (generateSequence<Throwable>(e) { it.cause }.any { it is java.security.cert.CertificateException }) {
                    done(false, "Didn't send: whoever answered isn't the ${dev.alias} that was found earlier. Look again and pick it.")
                    return@execute
                }
                done(false, e.message?.takeIf { it.contains(dev.alias) }?.let { ChatWire.noIp(it) } ?: "Couldn't reach ${dev.alias}. Is it on the same Wi-Fi with LocalSend, Huginn or Muninn open?")
            } catch (e: Exception) {
                done(false, "Sending to ${dev.alias} failed. Check it's on the same Wi-Fi and try again.")
            }
        }
    }

    /** Files that went out, for the Recent list. */
    fun logSent(names: List<String>, to: String) {
        names.forEach { history.add(0, Item(it, "To $to · sent", 1f, false, sent = true)) }
        while (history.size > 30) history.removeAt(history.size - 1)
        _items.value = history.toList()
    }

    fun clearFinished() { _finished.value = null }

    /** Cancel on the receiving side: what came so far is thrown away, and the sender is told no more. */
    fun cancelReceive() { if (session != null) endSession(false, "Receiving stopped.") }

    fun cancelSend() {
        // Still being made ready (not yet handed to send): it just stops here.
        _transfer.value.takeIf { it.preparing && !sendBusy }?.let { notSent(it.id, "Sending stopped.") }
        stopSend.set(true)
        try { sendConn?.disconnect() } catch (_: Exception) {}
    }

    private fun done(ok: Boolean?, msg: String) {
        sendBusy = false
        finishedId = _transfer.value.id
        _transfer.value = Transfer()
        if (ok != null) _finished.value = ok to msg
        sendConn = null
        NearbyService.hold(context, false)
        if (ok != null) messages.tryEmit(ok to msg)
    }

    /** A JSON POST to a device's extra routes (chat). Returns the status code, or -1. */
    fun post(dev: Device, path: String, json: String): Int = try {
        val c = open("${dev.protocol}://${dev.ip}:${dev.port}$path", 6000, if (dev.protocol == "https") dev.id else null)
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(json.toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        code
    } catch (e: Exception) {
        -1
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * A connection to another device. Over HTTPS it shows our certificate (some LocalSend apps
     * require one) and only goes ahead if the device's certificate has the [expected] fingerprint
     * (any certificate when null, for a scan).
     */
    /** [anonymous]: no client certificate (hidden from people nearby: nothing that names this phone). */
    private fun open(url: String, timeout: Int, expected: String?, anonymous: Boolean = false): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = (if (anonymous) null else tls?.client(expected)) ?: Tls.anonymousClient(expected)
            c.hostnameVerifier = anyHost
        }
        c.connectTimeout = minOf(timeout, 4000)
        c.readTimeout = timeout
        return c
    }

    companion object {
        /** LocalSend's port: multicast discovery (shared), and where plain LocalSend apps serve. */
        const val PORT = 53317
        /** Where Huginn's and Muninn's own server starts looking for a free port (up to [SERVER_TRIES] of them). */
        const val SERVER_FIRST = 53318
        const val SERVER_TRIES = 20
        /** Contacts' last addresses kept for Nearby off. */
        const val MAX_PEERS = 64
        const val GROUP = "224.0.0.167"
        const val API = "/api/localsend/v2"
        /** A chat envelope (64 KB, protocol 9) with room to spare; and any other request's JSON (a long file list). */
        const val MAX_CHAT_BODY = 96 * 1024L
        /** A 192 KB piece of a photo or file for a linked device (room "link"), base64 and sealed. */
        const val MAX_LINK_BODY = 512 * 1024L
        const val MAX_BODY = 2 * 1024 * 1024L
        const val MAX_CONNECTIONS = 32
        const val PROMPTS = 3

        /** On this very home network: a private address inside the subnet of one of this phone's own private addresses (Huginn's Lan::isNeighbour). */
        fun isNeighbour(peer: InetAddress?): Boolean {
            val p0 = peer ?: return false
            val b = p0.address
            val p = if (Lan.mapped(b)) InetAddress.getByAddress(b.copyOfRange(12, 16)) else p0
            if (!Lan.isPrivate(p)) return false
            return try {
                NetworkInterface.getNetworkInterfaces().toList().any { nif ->
                    nif.isUp && !nif.isLoopback && MOBILE.none { nif.name.startsWith(it) } &&
                        nif.interfaceAddresses.any { e -> e.address != null && Lan.isPrivate(e.address) && Lan.inSubnet(p.address, e.address.address, e.networkPrefixLength.toInt()) }
                }
            } catch (e: Exception) { false }
        }

        /** Devices are reached by IP and certificates carry no names: the fingerprint check replaces the name check. */
        private val anyHost = HostnameVerifier { _, _ -> true }

        /**
         * A JSON POST straight to [addr] ("ip:port") on the same network (moving to another device, cap x1): HTTPS with
         * any certificate first (everything inside is sealed), then HTTP. (status, body), or null when it can't connect.
         */
        fun postTo(addr: String, path: String, json: String): Pair<Int, String>? {
            for (scheme in listOf("https", "http")) try {
                val c = URL("$scheme://$addr$path").openConnection() as HttpURLConnection
                if (c is HttpsURLConnection) { c.sslSocketFactory = Tls.anonymousClient(null); c.hostnameVerifier = anyHost }
                c.connectTimeout = 4000; c.readTimeout = 30_000; c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(json.toByteArray()) }
                val code = c.responseCode
                val text = (if (code < 400) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
                c.disconnect()
                return code to text
            } catch (e: Exception) {}
            return null
        }

        /** Mobile-data interfaces (often on 10.x addresses, which look like a home network). */
        private val MOBILE = listOf("rmnet", "ccmni", "pdp", "clat", "v4-", "tun", "dummy", "ifb", "r_rmnet", "seth", "usb")

        /** Wi-Fi, a hotspot or wired: the interfaces where Huginn, Muninn and LocalSend can be. */
        fun lanInterfaces(): List<NetworkInterface> = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
                nif.isUp && !nif.isLoopback && nif.supportsMulticast() && MOBILE.none { nif.name.startsWith(it) } &&
                    nif.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
            }.sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        } catch (e: Exception) { emptyList() }

        fun localAddress(): String? = lanInterfaces().firstNotNullOfOrNull { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }

        /** What a device is called on screen: its own name (or model), never its network address. */
        fun deviceName(o: JSONObject): String {
            val alias = ChatWire.noIp(o.optString("alias").trim(), "").trim()
            val model = ChatWire.noIp(o.optString("deviceModel").trim(), "").trim()
            return alias.ifEmpty { model }.ifEmpty { "A nearby device" }.take(60)
        }

        fun size(b: Long): String = when {
            b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
            b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
            b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
            else -> "$b bytes"
        }
    }
}
