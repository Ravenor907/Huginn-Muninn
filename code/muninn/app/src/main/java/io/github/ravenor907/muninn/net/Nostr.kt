package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Messages for people who aren't nearby, through public Nostr relays (no accounts, no server to
 * run). Each phone has a Nostr key; its public half is its mailbox. A message is an event of
 * kind 1059 addressed to the mailbox, signed by a throwaway key, so the event itself doesn't name its sender.
 * The content is already sealed with the chat keys (ChatCrypto), so relays only see scrambled text, padded to
 * a few fixed sizes (EnvelopePad). Sending uses its own short-lived sockets that never ask for my inbox or
 * sign in as my mailbox, so a relay can't tie what I send to who I am from the socket. The internet address
 * still can link them (the relay sees the same address on both), unless a VPN or Tor app is in use.
 */
class Nostr(private val context: Context, private val secretHex: () -> String?) {
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    // No OkHttp ping: Muninn checks every socket itself at once (keepalive), so the radio wakes once, not per relay.
    private val client = Net.client.newBuilder().connectTimeout(10, TimeUnit.SECONDS).build()
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val live = ConcurrentHashMap<String, Boolean>()
    private val seen = LinkedHashSet<String>()
    private val working = HashSet<String>()
    private val outbox = mutableListOf<String>()        // events waiting for a relay
    private val subId = "muninn-" + UUID.randomUUID().toString().take(8)
    /** Reconnect by itself when a relay drops (set while Muninn is listening for messages). */
    @Volatile var keepAlive = false
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private val retries = ConcurrentHashMap<String, Int>()
    /** How many relays stay open (null: all, 0: none; see PowerLogic), and how often they're checked. */
    @Volatile private var keep: Int? = null
    @Volatile private var pingMs = PowerLogic.FAST_PING_MS
    @Volatile private var chosen: List<String>? = null
    private val connectMs = ConcurrentHashMap<String, Long>()
    private val openedAt = ConcurrentHashMap<String, Long>()
    private val heardAt = ConcurrentHashMap<String, Long>()
    @Volatile private var probedAt = 0L
    /** Short-lived sockets opened only to send (or check the inbox), and what each still has to send. */
    private val burst = ConcurrentHashMap<String, MutableList<String>>()
    private val burstUntil = ConcurrentHashMap<String, Long>()
    private val alarms = context.getSystemService(android.app.AlarmManager::class.java)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val wake by lazy {
        context.getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "muninn:relays")
            .apply { setReferenceCounted(false) }
    }
    @Volatile private var fastTick: java.util.concurrent.ScheduledFuture<*>? = null

    /** For the battery report: a check sent ([PING]), an alarm that woke the phone ([WAKE]), a connection made again ([RECONNECT]). */
    @Volatile var counted: (String) -> Unit = {}
    /** For the battery report: checks the relays sent on their own (WebSocket pings), as they're counted. */
    @Volatile var countedPings: (Int) -> Unit = {}
    /** Failures in a row across every relay: swapping to another relay slows down too (see [NetLogic.swapMs]). */
    private val streak = java.util.concurrent.atomic.AtomicInteger()
    /** When each socket was last asked whether it's alive (the keepalive check). */
    private val askedAt = ConcurrentHashMap<String, Long>()
    /** Per relay: when it last finished sending what it held (EOSE, seconds), so a new connection asks only for what's newer. */
    private val eoseAt = ConcurrentHashMap<String, Long>()
    /** Relays whose inbox request finished at least once on the socket open now. */
    private val subscribed = ConcurrentHashMap<String, Boolean>()
    /** Something was handed over and not taken (locked with the store full): don't move [eoseAt] on until it's read. */
    @Volatile private var refused = false
    /** Relay-side pings: per socket, how many were counted already; per relay, the total and the time connected. */
    private val pingsSeen = ConcurrentHashMap<WebSocket, Int>()
    private val relayPings = ConcurrentHashMap<String, Int>()
    private val relayMs = ConcurrentHashMap<String, Long>()
    private val liveSince = ConcurrentHashMap<String, Long>()
    /** The seen list and the cursors, in their own small file, written at most every [SAVE_EVERY_S] seconds. */
    private val stateFile = java.io.File(context.filesDir, "nostr-state.json")
    private val saving = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Every envelope that arrives for this phone (content of the event). Return false to see it again later. */
    var onEnvelope: ((String) -> Boolean)? = null
    /** Something arrived while Muninn is locked (kept to open after unlocking, or left on the relays when the store is full). */
    var onLocked: (() -> Unit)? = null
    /**
     * Cap b1: this device's ring key (outside the vault). Its mailbox is asked for too, on its own inbox request, locked or
     * not, and relays that ask may get a sign-in as it (NIP-42); nothing else of the vault is needed for that.
     */
    @Volatile var ring: RingKey? = null
    /** Every event for the ring mailbox (its content): never kept, never handed to [onEnvelope]. */
    var onRing: ((String) -> Unit)? = null
    private val ringSubId = "muninn-r" + UUID.randomUUID().toString().take(8)
    /** Per relay: its last sign-in challenge (NIP-42), to sign in again once the vault opens. */
    private val challenges = ConcurrentHashMap<String, String>()

    /**
     * Envelopes that arrived while locked, kept as they came (still sealed, exactly what the relays hold) in Muninn's
     * private files, so the relays don't hand them over again at every reconnect. Opened and deleted after unlocking
     * ([openHeld]). At most [HELD_MAX] envelopes and [HELD_BYTES] in all; beyond that they stay on the relays.
     */
    private val heldFile = java.io.File(context.filesDir, "nostr-held.json")
    private val heldLock = Any()

    /** Keeps [content] for after unlocking. False when the store is full. */
    private fun hold(content: String): Boolean = synchronized(heldLock) {
        val list = readHeld()
        if (content in list) return true
        if (!NetLogic.mayHold(list.size, list.sumOf { it.length.toLong() }, content.length.toLong(), HELD_MAX, HELD_BYTES)) return false
        list.add(content)
        writeHeld(list)
    }

    private fun readHeld(): MutableList<String> = try {
        if (!heldFile.exists()) mutableListOf() else JSONArray(heldFile.readText()).let { a -> MutableList(a.length()) { a.getString(it) } }
    } catch (_: Exception) { mutableListOf() }

    private fun writeHeld(list: List<String>): Boolean = try {
        val tmp = java.io.File(heldFile.path + ".tmp")
        tmp.writeText(JSONArray(list).toString())
        tmp.renameTo(heldFile)
    } catch (_: Exception) { false }

    /**
     * Unlocked: everything kept while locked goes to [onEnvelope] now, oldest first. Only the ones it took (true)
     * are deleted; one it wants to see again later stays for the next unlock. Run off the main thread.
     */
    fun openHeld() {
        val list = synchronized(heldLock) { readHeld() }
        if (list.isEmpty()) { heldFile.delete(); return }
        val handled = HashSet<String>()
        for (content in list) try { if (onEnvelope?.invoke(content) == true) handled.add(content) } catch (_: Exception) {}
        synchronized(heldLock) {
            // Anything kept meanwhile (locked again already), or not taken yet, stays for the next unlock.
            val rest = readHeld().filter { it !in handled }
            if (rest.isEmpty()) heldFile.delete() else writeHeld(rest)
        }
    }

    /** Erase everything and Move: what was kept while locked goes, unopened. */
    fun forgetHeld() = synchronized(heldLock) { heldFile.delete(); java.io.File(heldFile.path + ".tmp").delete() }

    private val _connected = MutableStateFlow(0)
    val connected: StateFlow<Int> = _connected.asStateFlow()

    val relays: List<String>
        get() = prefs.getString("relays", null)?.split('\n')?.filter { it.startsWith("wss://") } ?: DEFAULT_RELAYS

    /** This phone's Nostr secret (in the vault); null while Muninn is locked. */
    private val secret: ByteArray? get() = secretHex()?.let { Schnorr.unhex(it) }
    /** The mailbox: readable even while locked, so the inbox can be checked. */
    val publicKey: String get() = prefs.getString("nostrPub", null) ?: secret!!.let { Schnorr.hex(Schnorr.publicKey(it)) }.also {
        prefs.edit().putString("nostrPub", it).apply()
    }

    init {
        try {
            if (stateFile.exists()) {
                val o = JSONObject(stateFile.readText())
                o.optJSONArray("seen")?.let { a -> for (i in 0 until a.length()) seen.add(a.getString(i)) }
                o.optJSONObject("eose")?.let { e -> e.keys().forEach { eoseAt[it] = e.optLong(it) } }
            } else {
                // No state file: take the seen list from the settings ("nostrSeen") and clear it there.
                prefs.getString("nostrSeen", "")!!.split(',').filter { it.isNotEmpty() }.forEach { seen.add(it) }
                prefs.edit().remove("nostrSeen").remove("nostrSince").apply()
                if (seen.isNotEmpty()) saveSoon()
            }
        } catch (_: Exception) {}
    }

    /** Writes the seen list and the cursors within [SAVE_EVERY_S] seconds (one write for many changes). */
    private fun saveSoon() {
        if (!saving.compareAndSet(false, true)) return
        timer.schedule(Runnable {
            saving.set(false)
            try {
                val o = JSONObject()
                synchronized(seen) { o.put("seen", JSONArray(seen.toList())) }
                o.put("eose", JSONObject(HashMap<String, Long>(eoseAt) as Map<*, *>))
                val tmp = java.io.File(stateFile.path + ".tmp")
                tmp.writeText(o.toString())
                tmp.renameTo(stateFile)
            } catch (_: Exception) {}
        }, SAVE_EVERY_S, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ relays

    /** The relays kept open now: all of them, or the healthiest few while saving battery. */
    private fun targets(): List<String> {
        val all = relays
        val n = keep ?: return all
        val c = chosen?.filter { it in all }
        if (c != null && c.size == minOf(n, all.size)) return c
        return PowerLogic.pick(all, health(all), n).also { chosen = it }
    }

    private fun health(all: List<String>) = all.associateWith {
        PowerLogic.Health(retries[it] ?: 0, connectMs[it], live[it] == true, NetLogic.perHour(relayPings[it] ?: 0, relayMs[it] ?: 0L))
    }

    fun connect() { for (url in targets()) open(url); tick() }

    /**
     * Saving battery or not: keep [count] relays open (null: all, 0: none) and check them every [ping] ms.
     * Relays left out close; the healthiest ones are kept.
     */
    fun plan(count: Int?, ping: Long) {
        val changed = count != keep || ping != pingMs
        keep = count; pingMs = ping
        if (!changed) return
        chosen = null
        val want = targets().toSet()
        synchronized(sockets) {
            for (url in sockets.keys.toList()) if (url !in want && !burst.containsKey(url)) {
                sockets.remove(url)?.let { ended(url, it); it.close(1000, null) }
                live.remove(url)
            }
        }
        _connected.value = live.size
        if (keepAlive) for (url in want) open(url)
        tick()
    }

    /**
     * The screen went off: check the open sockets as rarely as [ping] says from now on, on an alarm that also runs
     * while the phone sleeps. Sockets close later, after the grace ([plan]).
     */
    fun pingEarly(ping: Long) {
        if (ping == pingMs) return
        pingMs = ping
        tick()
    }

    /** Listening live right now (so a separate inbox check isn't needed). */
    fun listening(): Boolean = keepAlive && keep != 0 && targets().any { live[it] == true }

    /** The 15-minute inbox check: every relay for a short while, closing again unless it's one kept open. */
    fun checkInbox() { for (url in relays) openBurst(url, emptyList()) }

    /** Opens [url] just to send [msgs] (or read the inbox), then closes it after a short while. */
    private fun openBurst(url: String, msgs: List<String>) {
        val ws = sockets[url]
        if (ws != null && live[url] == true) msgs.forEach { ws.send(it) }
        else burst.getOrPut(url) { java.util.Collections.synchronizedList(mutableListOf()) }.addAll(msgs)   // sent once it opens
        burstUntil[url] = android.os.SystemClock.elapsedRealtime() + BURST_SECONDS * 1000 - 500
        if (ws == null) open(url)
        timer.schedule(Runnable {
            if (android.os.SystemClock.elapsedRealtime() < (burstUntil[url] ?: 0L)) return@Runnable   // used again meanwhile
            burst.remove(url); burstUntil.remove(url)
            if (keepAlive && url in targets()) return@Runnable   // one of the relays kept open anyway
            synchronized(sockets) { sockets.remove(url)?.let { ended(url, it); it.close(1000, null) }; live.remove(url); _connected.value = live.size }
        }, BURST_SECONDS, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ keepalive

    /**
     * One check for every socket together: a tiny request each relay answers at once. A socket that
     * doesn't answer within [PowerLogic.ANSWER_MS] is dropped and opened again (one missed check is
     * enough). While saving battery the check rides on an inexact alarm: Android runs it within the last
     * [PowerLogic.PING_WINDOW_MS] before it's due, together with other apps' wake-ups, so the radio wakes
     * once for all of them. A second alarm that also runs while the phone dozes backs it up, and a timer
     * checks on time while the phone is awake anyway.
     */
    private fun tick() {
        try { alarms?.cancel(alarmIntent); alarms?.cancel(idleIntent) } catch (_: Exception) {}
        fastTick?.cancel(false); fastTick = null
        if (!keepAlive || keep == 0) { probedAt = 0L; return }
        val every = pingMs
        if (every <= PowerLogic.FAST_PING_MS) {
            fastTick = timer.scheduleWithFixedDelay({ probe() }, every, every, TimeUnit.MILLISECONDS)
            return
        }
        val at = android.os.SystemClock.elapsedRealtime() + every
        val window = minOf(PowerLogic.PING_WINDOW_MS, every / 2)
        try {
            val a = alarms!!
            a.setWindow(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at - window, window, alarmIntent)
            // Dozing, the alarm above waits for Android's next maintenance window: this one still runs (a little late).
            a.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, at, idleIntent)
        } catch (_: Exception) {}
        // Awake anyway (screen on, or another app woke the phone): check on time without waiting for the alarm.
        fastTick = timer.schedule(Runnable { due() }, every, TimeUnit.MILLISECONDS)
    }

    private val idleIntent: android.app.PendingIntent by lazy {
        android.app.PendingIntent.getBroadcast(context, 1, android.content.Intent(context, RelayAlarm::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private val alarmIntent: android.app.PendingIntent by lazy {
        android.app.PendingIntent.getBroadcast(context, 0, android.content.Intent(context, RelayAlarm::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The keepalive alarm went off ([RelayAlarm]). */
    fun onAlarm() {
        counted(WAKE)
        // Long enough for the requests to go out, the answers to come back, and a dead socket to be replaced.
        try { wake.acquire(PowerLogic.ANSWER_MS + 5_000) } catch (_: Exception) {}
        timer.execute { due() }
    }

    /** The alarm or the backup timer, whichever comes first: one check, then the next one is set. */
    private fun due() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (probedAt == 0L || now - probedAt >= pingMs / 2) probe()
        main.post { tick() }
    }

    /** [all]: ask every socket, even one heard from just now (the network just changed under it). */
    private fun probe(all: Boolean = false) {
        if (!keepAlive || keep == 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        countServerPings()
        var asked = false
        for (url in targets()) {
            val ws = sockets[url]
            if (ws == null) { if (online()) open(url); continue }
            if (live[url] != true) continue
            val before = askedAt[url] ?: 0L
            val heard = heardAt[url] ?: 0L
            if (PowerLogic.silent(before, heard) && (openedAt[url] ?: 0L) < before) { replace(url, ws); continue }
            // Heard from within the last minute (a message, an answer): it's alive, no need to ask.
            if (!all && NetLogic.aliveRecently(now - heard)) continue
            val filter = JSONObject().put("kinds", JSONArray().put(KIND)).put("#p", JSONArray().put(publicKey)).put("since", System.currentTimeMillis() / 1000).put("limit", 0)   // only my mailbox: never anyone else's traffic
            askedAt[url] = now
            ws.send(JSONArray().put("REQ").put(PROBE).put(filter).toString())
            asked = true
        }
        probedAt = now
        if (!asked) { release(); return }
        counted(PING)
        // One missed check is enough: look again once the answers should be in. Usually they all are within
        // a second or two; then the phone may sleep again at once.
        timer.schedule(Runnable { if (answered(now)) release() }, 2, TimeUnit.SECONDS)
        timer.schedule(Runnable { verify(now) }, PowerLogic.ANSWER_MS, TimeUnit.MILLISECONDS)
    }

    private fun answered(at: Long) = targets().all { url -> live[url] != true || askedAt[url] != at || (heardAt[url] ?: 0L) >= at }

    private fun release() { try { if (wake.isHeld) wake.release() } catch (_: Exception) {} }

    /** Sockets that didn't answer the check made at [at] are dead (often after a network change): start over. */
    private fun verify(at: Long) {
        if (!keepAlive || keep == 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        var replaced = false
        for (url in targets()) {
            val ws = sockets[url] ?: continue
            if (live[url] != true || askedAt[url] != at) continue
            if (PowerLogic.dead(at, heardAt[url] ?: 0L, openedAt[url] ?: 0L, now)) { replace(url, ws); replaced = true }
        }
        // A replaced socket keeps the phone awake a few seconds more (the wake lock's own timeout) to connect.
        if (!replaced) release()
    }

    private fun replace(url: String, ws: WebSocket) {
        synchronized(sockets) { if (!sockets.remove(url, ws)) return; live.remove(url); _connected.value = live.size }
        ended(url, ws)
        ws.cancel()
        retries.remove(url)
        counted(RECONNECT)
        open(url)
    }

    /** One socket per relay, never two: only opens if there isn't one already. */
    private fun open(url: String) {
        synchronized(sockets) {
            if (sockets.containsKey(url)) return
            openedAt[url] = android.os.SystemClock.elapsedRealtime()
            sockets[url] = client.newWebSocket(Request.Builder().url(url).build(), Listener(url))
        }
    }

    /** The network came back: drop dead connections and connect again now. */
    fun reconnectNow() {
        if (!keepAlive && sockets.isEmpty()) return
        streak.set(0)
        for (url in targets()) if (live[url] != true) {
            synchronized(sockets) { sockets.remove(url)?.let { ended(url, it); it.cancel() } }
            retries.remove(url)
            open(url)
        }
        // Sockets that look open may have died with the old network: ask now (a socket that doesn't answer
        // within seconds is replaced) and ask again shortly, instead of waiting for the next keepalive.
        if (keepAlive && keep != 0) timer.execute {
            probe(true)
            timer.schedule(Runnable { probe(true) }, 15, TimeUnit.SECONDS)
        }
    }

    /** Asks every open relay for the inbox again: after unlocking, so what came while locked is read now. */
    fun resubscribe() {
        refused = false
        synchronized(seen) { announced.clear() }
        // A relay that asked for a sign-in while locked gets one now, as the mailbox the vault holds the key of.
        for ((url, ws) in sockets) if (live[url] == true) { challenges[url]?.let { signIn(url, ws, it) }; subscribe(url, ws) }
    }

    /** NIP-42: signs in as my mailbox (while unlocked) and as the ring mailbox (always, cap b1). */
    private fun signIn(url: String, ws: WebSocket, challenge: String) {
        val tags = JSONArray().put(JSONArray().put("relay").put(url)).put(JSONArray().put("challenge").put(challenge))
        secret?.let { ws.send(JSONArray().put("AUTH").put(event(22242, tags, "", it)).toString()) }
        try { ring?.let { r -> r.nostrSecrets().forEach { ws.send(JSONArray().put("AUTH").put(event(22242, tags, "", it)).toString()) } } } catch (_: Exception) {}
    }

    fun disconnect() {
        keepAlive = false
        tick()
        synchronized(sockets) { for ((url, ws) in sockets) { ended(url, ws); ws.close(1000, null) }; sockets.clear() }
        live.clear()
        _connected.value = 0
    }

    private fun subscribe(url: String, ws: WebSocket) {
        // Only what came since this relay last finished sending, less a margin for backdated events (see publish).
        val since = NetLogic.since(eoseAt[url] ?: 0L, System.currentTimeMillis() / 1000)
        val filter = JSONObject().put("kinds", JSONArray().put(KIND)).put("#p", JSONArray().put(publicKey)).put("since", since)
        ws.send(JSONArray().put("REQ").put(subId).put(filter).toString())
        // Cap b1: the ring mailbox, on a request of its own (a relay may answer one and not the other).
        try {
            ring?.let { r ->
                // A ring mailbox replaced after a block is still asked for, for 2 days.
                val rf = JSONObject().put("kinds", JSONArray().put(KIND)).put("#p", JSONArray(r.mailboxes())).put("since", since)
                ws.send(JSONArray().put("REQ").put(ringSubId).put(rf).toString())
            }
        } catch (_: Exception) {}
    }

    private inner class Listener(val url: String) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (sockets[url] !== webSocket) { webSocket.cancel(); return }   // replaced meanwhile
            // Only reset the back-off once it has stayed up a minute (a relay that accepts and drops at once).
            timer.schedule({ if (sockets[url] === webSocket && live[url] == true) { retries.remove(url); streak.set(0) } }, 60, java.util.concurrent.TimeUnit.SECONDS)
            val now = android.os.SystemClock.elapsedRealtime()
            connectMs[url] = now - (openedAt[url] ?: now)
            heardAt[url] = now
            live[url] = true
            liveSince[url] = now
            _connected.value = live.size
            subscribe(url, webSocket)
            // This relay is reachable again: what still waits, and what was just sent, goes to it on a sending
            // socket of its own, never on this one (relays drop an event they already have).
            val again = ArrayList<String>()
            synchronized(outbox) { again.addAll(outbox) }
            synchronized(recent) {
                val now = android.os.SystemClock.elapsedRealtime()
                recent.filter { now - it.first <= RECENT_MS }.forEach { (_, m) -> if (m !in again) again.add(m) }
            }
            burst[url]?.let { m -> synchronized(m) { m.forEach { if (it !in again) again.add(it) }; m.clear() } }
            if (again.isNotEmpty()) send(url, again)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (sockets[url] === webSocket) heardAt[url] = android.os.SystemClock.elapsedRealtime()
            val a = try { JSONArray(text) } catch (e: Exception) { return }
            when (a.optString(0)) {
                "EOSE" -> {
                    if (a.optString(1) == PROBE) webSocket.send(JSONArray().put("CLOSE").put(PROBE).toString())
                    else if (a.optString(1) == subId) subscribed[url] = true
                    // Everything this relay held for me has been handed over (and the inbox request is still open):
                    // next time ask only for what's newer. Not while something had to stay on the relays (locked, with the
                    // locked store full): it must come again after unlocking.
                    if (sockets[url] === webSocket && subscribed[url] == true && !refused) {
                        eoseAt[url] = System.currentTimeMillis() / 1000
                        saveSoon()
                    }
                }
                // The relay ended the inbox request (it may ask to sign in first): what's newer isn't known to be read.
                "CLOSED" -> if (a.optString(1) == subId) subscribed.remove(url)
                "EVENT" -> a.optJSONObject(2)?.let { handle(it) }
                // Some relays only hand out messages to the mailbox's owner: prove it (NIP-42).
                "AUTH" -> {
                    val challenge = a.optString(1)
                    challenges[url] = challenge
                    if (secret == null && ring == null) return
                    signIn(url, webSocket, challenge)
                    subscribe(url, webSocket)
                }
                "OK" -> if (a.optBoolean(2)) storedAt(url, a.optString(1))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = gone(webSocket)
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); gone(webSocket) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = gone(webSocket)

        /** Only the current socket for this relay counts; an old one closing changes nothing. */
        private fun gone(webSocket: WebSocket) {
            synchronized(sockets) { if (!sockets.remove(url, webSocket)) return }
            live.remove(url)
            _connected.value = live.size
            ended(url, webSocket)
            if (!keepAlive || url !in targets()) return
            // Try again: 2 s, 4 s, 8 s ... up to a minute between tries.
            val n = (retries[url] ?: 0) + 1
            retries[url] = n
            val s = streak.incrementAndGet()
            counted(RECONNECT)
            // No network at all: nothing to try. Muninn connects again as soon as one is back (reconnectNow).
            if (!online()) return
            // Saving battery with one relay open (or a few): one that fails gives its place to a healthier one,
            // the only one at once. Many failures in a row (a bad signal) slow the swapping down too.
            if (keep != null && n >= PowerLogic.swapAfter(keep)) {
                chosen = null
                val now = targets()
                val wait = NetLogic.swapMs(s)
                for (u in now) if (u != url) openLater(u, wait)
                if (url !in now) return
            }
            openLater(url, NetLogic.reconnectMs(n, s))
        }
    }

    /** Opens [url] after [ms] (at once for 0), if it's still wanted then. */
    private fun openLater(url: String, ms: Long) {
        if (ms <= 0L) { open(url); return }
        timer.schedule({ if (keepAlive && url in targets() && online()) open(url) }, ms, TimeUnit.MILLISECONDS)
    }

    /** Some network is there (Wi-Fi, mobile data, Ethernet). */
    private fun online(): Boolean = try {
        context.getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork != null
    } catch (_: Exception) { true }

    /** A socket ended: its relay-side pings and time connected go into that relay's totals. */
    private fun ended(url: String, ws: WebSocket) {
        countPings(url, ws)
        pingsSeen.remove(ws)
        subscribed.remove(url)
        askedAt.remove(url)
        liveSince.remove(url)?.let { since -> relayMs.merge(url, android.os.SystemClock.elapsedRealtime() - since, { a, b -> a + b }) }
    }

    /** Pings this socket's relay sent since they were last counted (OkHttp answers them by itself). */
    private fun countPings(url: String, ws: WebSocket) {
        val total = try { (ws as? okhttp3.internal.ws.RealWebSocket)?.receivedPingCount() } catch (_: Throwable) { null } ?: return
        val before = pingsSeen.put(ws, total) ?: 0
        val n = total - before
        if (n <= 0) return
        relayPings.merge(url, n, { a, b -> a + b })
        countedPings(n)
    }

    /** Counts the relay-side pings of every open socket now (for the battery report). */
    fun countServerPings() {
        val now = android.os.SystemClock.elapsedRealtime()
        for ((url, ws) in sockets) {
            countPings(url, ws)
            liveSince[url]?.let { since -> if (liveSince.replace(url, since, now)) relayMs.merge(url, now - since, { a, b -> a + b }) }
        }
    }

    /** Events left on the relays while locked (the store was full): handled once, not once per relay. */
    private val announced = HashSet<String>()

    private fun handle(ev: JSONObject) {
        val id = ev.optString("id")
        // The same event comes from every relay at once: only one copy is handled at a time.
        synchronized(seen) { if (id.isEmpty() || seen.contains(id) || !working.add(id)) return }
        // Cap b1: an event for the ring mailbox is only ever a ring envelope. Tried with the ring key, then dropped.
        val r = ring
        if (r != null && pTag(ev).let { p -> p != null && (try { p in r.mailboxes() } catch (e: Exception) { false }) }) {
            try {
                val own = try { idOf(ev) == id } catch (e: Exception) { false }
                if (own && try { Schnorr.verify(Schnorr.unhex(id), Schnorr.unhex(ev.optString("pubkey")), Schnorr.unhex(ev.optString("sig"))) } catch (e: Exception) { false })
                    try { onRing?.invoke(ev.optString("content")) } catch (_: Exception) {}
            } finally { synchronized(seen) { working.remove(id); seen.add(id); while (seen.size > 2000) seen.remove(seen.first()) } }
            saveSoon()
            return
        }
        val locked = secretHex() == null
        // Already said once that it's waiting on the relays (the store was full): not again for every relay.
        if (locked && synchronized(seen) { id in announced }) { synchronized(seen) { working.remove(id) }; return }
        try {
            // Only trust what the event's own signature covers. A broken event (keys or signature that aren't hex) is
            // dropped, never thrown up into the socket, and one whose id is its own is never looked at again.
            val own = try { idOf(ev) == id } catch (e: Exception) { false }
            val good = own && try { Schnorr.verify(Schnorr.unhex(id), Schnorr.unhex(ev.optString("pubkey")), Schnorr.unhex(ev.optString("sig"))) } catch (e: Exception) { false }
            if (!good) { if (own) synchronized(seen) { seen.add(id) }; return }
            if (locked) {
                // Kept sealed until unlocking, and counted as read here, so the relays don't send it again.
                val kept = hold(ev.optString("content"))
                try { onLocked?.invoke() } catch (_: Exception) {}
                if (!kept) { synchronized(seen) { announced.add(id) }; refused = true; return }
            } else if ((try { onEnvelope?.invoke(ev.optString("content")) } catch (e: Exception) { true }) != true) { refused = true; return }
        } finally { synchronized(seen) { working.remove(id) } }
        synchronized(seen) {
            seen.add(id)
            while (seen.size > 2000) seen.remove(seen.first())
        }
        saveSoon()
    }

    /** The mailbox an event is for (its first "p" tag), or null. */
    private fun pTag(ev: JSONObject): String? {
        val tags = ev.optJSONArray("tags") ?: return null
        for (i in 0 until tags.length()) {
            val t = tags.optJSONArray(i) ?: continue
            if (t.optString(0) == "p") return t.optString(1)
        }
        return null
    }

    // ------------------------------------------------------------------ sending

    /** A relay said it stored event [id]. */
    private fun storedAt(url: String, id: String) {
        if (id.isEmpty()) return
        synchronized(outbox) { outbox.removeAll { it.contains(id) } }
        stored[id]?.let { w ->
            // The first relay to store it: "sent". The second: it waits there safely (see NetLogic).
            val n = synchronized(w) { if (w.relays.add(url)) w.relays.size else 0 }
            if (n == 1) try { w.ok?.invoke() } catch (_: Exception) {}
            if (n == NetLogic.HELD_RELAYS) { stored.remove(id); try { w.held?.invoke() } catch (_: Exception) {} }
            if (w.held == null && n >= 1) stored.remove(id)
        }
    }

    /**
     * A socket that only sends: it never asks for an inbox (REQ) and never signs in (AUTH), so the relay can't
     * tie what it carries to my mailbox. It closes [SEND_SECONDS] after the last thing given to it.
     */
    private inner class Sender(val url: String) : WebSocketListener() {
        val queue = mutableListOf<String>()
        @Volatile var ws: WebSocket? = null
        private var open = false
        @Volatile var until = 0L

        /** Sends now if open, else once it opens. */
        fun give(msgs: List<String>) {
            synchronized(queue) { val w = ws; if (open && w != null) msgs.forEach { w.send(it) } else queue.addAll(msgs) }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (senders[url] !== this) { webSocket.cancel(); return }
            synchronized(queue) { open = true; queue.forEach { webSocket.send(it) }; queue.clear() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val a = try { JSONArray(text) } catch (e: Exception) { return }
            // "AUTH" is never answered here: signing in would name my mailbox.
            if (a.optString(0) == "OK" && a.optBoolean(2)) storedAt(url, a.optString(1))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { senders.remove(url, this) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); senders.remove(url, this) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { senders.remove(url, this) }
    }
    private val senders = ConcurrentHashMap<String, Sender>()

    /** Gives [msgs] to [url]'s sending socket, opening one if needed. */
    private fun send(url: String, msgs: List<String>) {
        val s = synchronized(senders) {
            senders[url] ?: Sender(url).also { n -> senders[url] = n; n.ws = client.newWebSocket(Request.Builder().url(url).build(), n) }
        }
        s.until = android.os.SystemClock.elapsedRealtime() + SEND_SECONDS * 1000 - 500
        s.give(msgs)
        timer.schedule(Runnable {
            if (android.os.SystemClock.elapsedRealtime() < s.until) return@Runnable   // used again meanwhile
            if (senders.remove(url, s)) s.ws?.close(1000, null)
        }, SEND_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Sends sealed content to someone's mailbox through the relays open now. Returns how many relays are
     * connected now. [ok] runs once a relay has said it stored the event (its "OK"), not before; [held] once
     * [NetLogic.HELD_RELAYS] relays have.
     *
     * Every relay gets it through a short-lived sending socket (never the one listening for my inbox), so it
     * reaches whichever relay the other phone listens on. A relay that connects again within [RECENT_MS] gets
     * it too ([recent]). [days] > 0: the event carries a NIP-40 expiration tag ([expiration], the same for every chat).
     */
    fun publish(mailbox: String, content: String, ok: (() -> Unit)? = null, held: (() -> Unit)? = null, days: Int = 0): Int {
        val tags = JSONArray().put(JSONArray().put("p").put(mailbox))
        // A new throwaway key for every message, and a slightly blurred time, so relays can't link messages to a sender.
        val createdAt = System.currentTimeMillis() / 1000 - Random.nextLong(0, BLUR_S)
        // NIP-40: relays may drop it once the longest message limit has passed (it's gone from both phones by then). Taken
        // from the blurred time and rounded up to a whole day, so it shows neither the sending time nor the chat's limit.
        if (days > 0) tags.put(JSONArray().put("expiration").put(expiration(createdAt).toString()))
        val ev = event(KIND, tags, content, Schnorr.newSecret(), createdAt)
        val msg = JSONArray().put("EVENT").put(ev).toString()
        if (ok != null || held != null) { if (stored.size > 500) stored.clear(); stored[ev.getString("id")] = Waiting(ok, held) }
        synchronized(outbox) { outbox.add(msg); while (outbox.size > 200) outbox.removeAt(0) }
        synchronized(recent) {
            val now = android.os.SystemClock.elapsedRealtime()
            recent.addLast(now to msg)
            while (recent.size > 100 || (recent.isNotEmpty() && now - recent.first().first > RECENT_MS)) recent.removeFirst()
        }
        // Every relay gets it, each on a sending socket of its own: the other phone may be listening on
        // just one, and not one of mine.
        for (url in relays) send(url, listOf(msg))
        if (sockets.isEmpty()) connect()
        return live.size
    }

    /** Events sent in the last [RECENT_MS]: a relay that connects meanwhile gets them too, even once another said OK. */
    private val recent = ArrayDeque<Pair<Long, String>>()
    /** Event id to what to do once relays have stored it, and which ones have. */
    private class Waiting(val ok: (() -> Unit)?, val held: (() -> Unit)?) { val relays = HashSet<String>() }
    private val stored = ConcurrentHashMap<String, Waiting>()

    companion object {
        const val KIND = 1059
        private const val PROBE = "muninn-alive"
        const val PING = "ping"
        const val WAKE = "wake"
        const val RECONNECT = "reconnect"
        /** A check the relay sent on its own (a WebSocket ping), for the battery report. */
        const val SERVER_PING = "serverPing"
        private const val SAVE_EVERY_S = 30L
        /** Envelopes kept while locked: at most this many, and this many characters in all. */
        const val HELD_MAX = 500
        const val HELD_BYTES = 4_000_000L
        private const val BURST_SECONDS = 20L
        /** How long a sending socket stays open after the last thing given to it. */
        private const val SEND_SECONDS = 20L
        /** How long a sent event is also given to relays that connect afterwards (the conversation window opening). */
        private const val RECENT_MS = 60_000L
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://relay.nostr.band")
        /** An event's created_at is up to this many seconds before the real time (section 20.3). */
        const val BLUR_S = 600L

        /**
         * NIP-40 expiration for an event made at [createdAt] (already blurred): that time plus [BLUR_S] plus the longest
         * message limit, rounded up to the next whole day (UTC). Never before the real time plus any chat's limit, and
         * the same for every chat, so a relay learns neither the sending time nor the chat's limit from it (section 20.3).
         */
        fun expiration(createdAt: Long): Long {
            val at = createdAt + BLUR_S + Limits.LONGEST * 86_400L
            return (at + 86_399L) / 86_400L * 86_400L
        }

        /** NIP-01: the event id is the SHA-256 of [0, pubkey, created_at, kind, tags, content] as compact JSON. */
        fun serialize(pubkey: String, createdAt: Long, kind: Int, tags: JSONArray, content: String): String =
            JSONArray().put(0).put(pubkey).put(createdAt).put(kind).put(tags).put(content).toString().replace("\\/", "/")

        fun idOf(ev: JSONObject): String = Schnorr.hex(MessageDigest.getInstance("SHA-256").digest(
            serialize(ev.optString("pubkey"), ev.optLong("created_at"), ev.optInt("kind"), ev.optJSONArray("tags") ?: JSONArray(), ev.optString("content")).toByteArray()))

        fun event(kind: Int, tags: JSONArray, content: String, secret: ByteArray, createdAt: Long = System.currentTimeMillis() / 1000,
                  aux: ByteArray? = null): JSONObject {
            val pub = Schnorr.hex(Schnorr.publicKey(secret))
            val id = MessageDigest.getInstance("SHA-256").digest(serialize(pub, createdAt, kind, tags, content).toByteArray())
            val sig = if (aux != null) Schnorr.sign(id, secret, aux) else Schnorr.sign(id, secret)
            return JSONObject().put("id", Schnorr.hex(id)).put("pubkey", pub).put("created_at", createdAt).put("kind", kind)
                .put("tags", tags).put("content", content).put("sig", Schnorr.hex(sig))
        }
    }
}

/** The relay keepalive alarm (see [Nostr.onAlarm]): a PendingIntent alarm so it also runs while the phone dozes. */
class RelayAlarm : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        (context.applicationContext as? io.github.ravenor907.muninn.Muninn)?.nostr?.onAlarm()
    }
}
