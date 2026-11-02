package io.github.ravenor907.muninn

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Direct
import io.github.ravenor907.muninn.net.Identity
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Nostr
import io.github.ravenor907.muninn.net.Vault
import io.github.ravenor907.muninn.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * One of each for the whole app. Everything that reads saved data starts only once the vault is
 * open: right away without a password, or after the person types it.
 */
class Muninn : Application() {
    lateinit var vault: Vault
    lateinit var identity: Identity
    lateinit var nostr: Nostr
    lateinit var net: LocalSend
    lateinit var ble: io.github.ravenor907.muninn.net.Ble
    lateinit var contacts: Contacts
    lateinit var chat: Chat
    /** Your status, custom status, picture and colour, and your contacts' (see net/Profile). */
    lateinit var profile: io.github.ravenor907.muninn.net.Profile
    lateinit var direct: Direct
    lateinit var updater: Updater
    /** Saving battery while the screen is off (see net/PowerLogic). */
    lateinit var power: io.github.ravenor907.muninn.net.Power
    /** True once everything is running (the vault is open). */
    val ready = MutableStateFlow(false)
    @Volatile var visible = false
    /** The conversation on screen right now ("" for none). */
    @Volatile var openChat = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** A locked nudge's notification was tapped: its sender and group, for the chat to open once unlocked ([openNudge]). */
    @Volatile var nudgeOpen: Pair<String, String>? = null

    override fun onCreate() {
        super.onCreate()
        vault = Vault(this)
        identity = Identity(this, vault)
        io.github.ravenor907.muninn.ui.Access.apply {
            size = identity.uiSize; text = identity.textSize; contrast = identity.highContrast
            colorVision = identity.colorVision; reduceMotion = identity.reduceMotion; look = io.github.ravenor907.muninn.ui.Looks.of(identity.look)
            season = io.github.ravenor907.muninn.ui.Seasons.choice(identity.season)
            seasonGround = io.github.ravenor907.muninn.ui.Seasons.ground(identity.seasonGround)
            seasonAnim = io.github.ravenor907.muninn.ui.Seasons.anim(identity.seasonAnim)
            accent = identity.accent
        }
        io.github.ravenor907.muninn.ui.PrivateKeyboard.on = identity.privateKeyboard
        nostr = Nostr(this) { if (vault.locked.value) null else identity.nostrSecret() }
        direct = Direct(this)
        updater = Updater(this, identity)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).apply {
            deleteNotificationChannel("messages")   // unused: no vibration set
            deleteNotificationChannel("chat")       // test builds: sound not set
            createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages. Pops up on screen, vibrates and plays a sound."
                enableVibration(true); vibrationPattern = BUZZ
                // The raven call: Muninn's own message sound.
                setSound(android.net.Uri.parse("android.resource://$packageName/raw/raven_message"),
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableLights(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
            // Quiet hours: messages from people not on the "Always right away" list, with no sound or pop-up.
            createNotificationChannel(NotificationChannel(QUIET, "Messages during quiet hours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Messages from people not on your Always right away list while quiet hours last. No sound or pop-up."
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
        }
        // Until the chats are open, what arrives waits (on the relays, or sealed in the locked store, see Nostr).
        // While locked, nothing in the inbox notifies (it may be a receipt, a check-in or presence): only a nudge does.
        nostr.onEnvelope = { false }
        // legacy-peers.json: nothing reads it, so any copy in Muninn's files is deleted.
        try { java.io.File(filesDir, "legacy-peers.json").delete() } catch (_: Exception) {}
        // Cap b1: the ring key lives outside the vault (app-private files, never an OS key store), so a call rings even
        // while Muninn is locked. Only "Incoming call", no name: answering opens the lock screen, and the call itself
        // (kept sealed with everything else that came while locked) is picked up once Muninn is unlocked.
        val ring = io.github.ravenor907.muninn.net.RingKey(java.io.File(filesDir, "ring-key.json"))
        nostr.ring = ring
        nostr.onRing = { content ->
            val rang = ring.open(content)
            if (rang != null) {
                io.github.ravenor907.muninn.net.call.CallManager.ringLocked(this, rang.first, rang.second)
            } else {
                // Caps n1 and n2: a nudge says a real message came. Unlocked, it's ignored (the message itself notifies).
                io.github.ravenor907.muninn.net.NudgeWire.ringMailbox(vault.locked.value, content, ring)?.let { lockedNudge(it) }
            }
        }
        // Cap n2: the nudge key, kept like the ring key, for contacts and group peers. Its mailbox only ever nudges.
        val nudgeKey = io.github.ravenor907.muninn.net.NudgeKey(java.io.File(filesDir, "nudge-key.json"))
        nostr.nudgeKey = nudgeKey
        nostr.onNudge = { content -> io.github.ravenor907.muninn.net.NudgeWire.nudgeMailbox(vault.locked.value, content, nudgeKey)?.let { lockedNudge(it) } }
        // Erase everything and Move: the ring and nudge keys, the day keys and what was kept while locked all go.
        vault.onErase = {
            ring.forget()
            nudgeKey.forget()
            try { java.io.File(filesDir, "legacy-peers.json").delete() } catch (_: Exception) {}
            nostr.forgetHeld()
            if (ready.value) try { chat.forgetKeys(); chat.forgetKeyCache() } catch (_: Exception) {}
            io.github.ravenor907.muninn.ui.PhotoCache.clear()
        }
        // Screen, charging, Battery Saver, Doze, calls and the network decide how connected to stay.
        // (It also reconnects to the relays at once when the network comes back.)
        power = io.github.ravenor907.muninn.net.Power(this)
        power.install()
        // Moving to another device (cap x1): one under way goes on, and this device stays locked until it's done.
        io.github.ravenor907.muninn.net.Move.init(this)
        // Reading the stores takes a moment: off the main thread, with the window showing "Opening" meanwhile.
        if (vault.openDefault()) startAsync()
        io.github.ravenor907.muninn.net.MessageService.start(this)
        // Check the internet inbox even when Muninn is closed: every 15 minutes (Android's shortest) or every
        // hour, as Settings › Check for messages says; never for "Only when open".
        power.schedule()
    }

    /**
     * [start] on a background thread (app start, and right after unlocking), so the window never waits on the stores.
     * [then] runs on the main thread once everything is running.
     */
    fun startAsync(then: (() -> Unit)? = null) {
        if (ready.value) { then?.invoke(); return }
        scope.launch {
            kotlinx.coroutines.withContext(Dispatchers.Default) { start() }
            then?.invoke()
        }
    }

    /** Everything running now if the vault is open (waits for a start under way). For a reply right after a restart. */
    fun ensureStarted(): Boolean {
        if (!ready.value && !vault.locked.value) start()
        return ready.value
    }

    /** Called once the vault is open. */
    @Synchronized
    fun start() {
        if (ready.value) return
        net = LocalSend(this, identity)
        net.mailbox = nostr.publicKey
        ble = io.github.ravenor907.muninn.net.Ble(this) { chat.hello() }
        // Hidden from people nearby: Bluetooth is silent too (section 21.3).
        ble.hidden = { !identity.visibleNearby }
        // A Kids' TV keeps its own nearby behavior: the Nearby switch is for everyone else.
        identity.kidsTv = { try { io.github.ravenor907.muninn.tv.KidMode.on(this) } catch (_: Exception) { false } }
        contacts = Contacts(this, vault)
        chat = Chat(this, net, ble, nostr, contacts, vault)
        chat.ringKey = nostr.ring
        chat.nudgeKey = nostr.nudgeKey
        profile = io.github.ravenor907.muninn.net.Profile(this, vault, contacts, chat)
        chat.profile = profile
        io.github.ravenor907.muninn.tv.Tv.install(this)
        io.github.ravenor907.muninn.net.call.CallManager.init(this, chat)
        io.github.ravenor907.muninn.net.call.RoomManager.init(this)
        io.github.ravenor907.muninn.net.Received.load(this)   // Settings › Files › Save files to
        // A notification for every message, unless that very chat is on screen: then just a buzz.
        scope.launch { chat.arrivals.collect { (name, text, key) ->
            power.activity()   // Check less when it's quiet starts over
            // A 1:1 message opens the conversation window, so the next one comes at once too. Group messages don't.
            if (io.github.ravenor907.muninn.net.NetLogic.opensTalk(key)) power.talk()
            // Muted chats stay quiet; an archived chat comes back to the list (unless muted).
            if (!chat.flags.arrived(key)) return@collect
            // Someone nearby put away with Hide (not a contact) stays quiet too.
            if (chat.flags.hidden(key) && !chat.isContact(key)) return@collect
            if (visible && openChat == key && !io.github.ravenor907.muninn.ui.Panic.locked.value) { coalesce.drop(key); buzz(); return@collect }
            // Reduce notifications: one per chat until it's opened; a group can also be set to mentions and replies only.
            // A group of more than 10 starts on mentions and replies only, until it's set otherwise.
            val big = key.startsWith("g:") && (try { chat.groups.group(key.removePrefix("g:"))?.state?.members?.size } catch (_: Exception) { null } ?: 0) > io.github.ravenor907.muninn.net.ChatFlags.BIG_GROUP
            if (!chat.flags.alert(key, identity.reduceNotifications, toMe(key), big)) return@collect
            // Extreme battery saver: no notifications; the chat list shows what's new.
            if (power.extreme) return@collect
            // Quiet hours kept open for the "Always right away" list: everyone else's messages wait quietly.
            when (power.alert(key)) {
                io.github.ravenor907.muninn.net.PowerLogic.Alert.LOUD -> notifySoon(name, text, key, quiet = doNotDisturb())
                io.github.ravenor907.muninn.net.PowerLogic.Alert.QUIET -> notifySoon(name, text, key, quiet = true)
                io.github.ravenor907.muninn.net.PowerLogic.Alert.NONE -> {}
            }
        } }
        // Unsent: take its notification down too, so the text doesn't linger there (and nothing held goes out after).
        scope.launch { chat.recalls.collect { key -> coalesce.drop(key); NotificationManagerCompat.from(this@Muninn).cancel(key.hashCode()) } }
        ready.value = true
        // Kids' TV: a file on its way shows over other apps too (TvRoot shows it in Muninn).
        if (io.github.ravenor907.muninn.tv.KidMode.isTv(this)) scope.launch { io.github.ravenor907.muninn.tv.TvBanner.watch(this@Muninn) }
        io.github.ravenor907.muninn.net.Move.ready(this)
        // A window already on screen (onStart ran before the vault opened): say so now, with Active status on.
        chat.foreground(visible)
        power.ready()
        watchIdle()
        // Unlocked again later (the lock came due while Muninn was running): the same catching up.
        scope.launch { vault.locked.drop(1).collect { locked -> if (!locked) unlocked() else lockedNow() } }
        unlocked()
        // The message service asked to listen before everything was running: the Wi-Fi side starts now too.
        if (nostr.keepAlive) listen()
    }

    /** Locked: the pair keys and decoded pictures kept in memory go, and nothing held back is posted. */
    private fun lockedNow() {
        try { chat.forgetKeyCache() } catch (_: Exception) {}
        io.github.ravenor907.muninn.ui.PhotoCache.clear()
        coalesce.clear()
    }

    /** Rapid messages in one chat make one notification ([io.github.ravenor907.muninn.net.NotifyCoalescer]). */
    private val coalesce = io.github.ravenor907.muninn.net.NotifyCoalescer<Held>()
    private class Held(val name: String?, val text: String?, val quiet: Boolean)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The first message in a quiet spell notifies at once; more in the same chat right after it make one update. */
    private fun notifySoon(name: String?, text: String?, key: String, quiet: Boolean) {
        val now = android.os.SystemClock.elapsedRealtime()
        when (val o = coalesce.offer(key, Held(name, text, quiet), now)) {
            is io.github.ravenor907.muninn.net.NotifyCoalescer.Offer.Now -> notify(name, text, key, quiet)
            is io.github.ravenor907.muninn.net.NotifyCoalescer.Offer.Later ->
                if (o.first) mainHandler.postDelayed({ flushNotice(key) }, (o.flushAt - now).coerceAtLeast(0L))
        }
    }

    /** The window for [key] ended: the newest message held goes out as one update, unless that chat is open now. */
    private fun flushNotice(key: String) {
        val (h, _) = coalesce.flush(key, android.os.SystemClock.elapsedRealtime()) ?: return
        if (visible && openChat == key && !io.github.ravenor907.muninn.ui.Panic.locked.value) return
        if (power.extreme || vault.locked.value) return
        notify(h.name, h.text, key, h.quiet)
    }

    /**
     * Just unlocked: the notifications nudges put up while locked go (the messages themselves notify now), what was
     * kept sealed while locked is opened (and deleted), and the relays are asked again for anything that had to stay
     * with them. A locked notification tapped on the way here opens its chat.
     */
    private fun unlocked() {
        cancelLockedNudges()
        // Any notification still under the inbox id goes too.
        NotificationManagerCompat.from(this).cancel(OLD_LOCKED_ID)
        getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE).edit().remove("lockedAlerted").apply()
        Thread {
            try { nostr.openHeld() } catch (_: Exception) {}
            nostr.resubscribe()
        }.start()
        mainHandler.post { openNudge() }
    }

    /** How many nudges each locked notification ([NudgeWire.groupKey]) counted since the last unlock. */
    private val lockedCounts = HashMap<String, Int>()

    /**
     * Caps n1 and n2: a nudge came while locked, so a real message is waiting. What it shows follows Settings ›
     * Notifications › What notifications show: Name only and Name and message say who and what kind ("Photo"), never
     * the message (it can't be opened while locked); Nothing says "Muninn", "New message". One notification per sender
     * and chat, counting up as more come, and alerting only the first time. Quiet hours and Extreme battery saver as
     * for any message.
     */
    @Synchronized
    fun lockedNudge(n: io.github.ravenor907.muninn.net.NudgeWire.Nudge) {
        if (power.extreme) return
        val alert = try { power.alert("") } catch (_: Exception) { io.github.ravenor907.muninn.net.PowerLogic.Alert.LOUD }
        if (alert == io.github.ravenor907.muninn.net.PowerLogic.Alert.NONE) return
        val quiet = alert == io.github.ravenor907.muninn.net.PowerLogic.Alert.QUIET || doNotDisturb()
        val show = lockedShow()
        val key = io.github.ravenor907.muninn.net.NudgeWire.groupKey(show, n)
        val count = (lockedCounts[key] ?: 0) + 1
        lockedCounts[key] = count
        val id = key.hashCode()
        val intent = Intent(this, MainActivity::class.java).putExtra("chat", "inbox").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (show != io.github.ravenor907.muninn.net.NudgeWire.Show.NOTHING) intent.putExtra(NUDGE_FROM, n.from).putExtra(NUDGE_CHAT, n.chat)
        val open = PendingIntent.getActivity(this, NUDGE_TAG.hashCode() + id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, if (quiet) QUIET else MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(io.github.ravenor907.muninn.net.NudgeWire.title(show, n))
            .setContentText(io.github.ravenor907.muninn.net.NudgeWire.text(show, n.kind, count))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (quiet) setSilent(true) else setVibrate(BUZZ) }.setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
        if (count > 1) b.setNumber(count)
        try { NotificationManagerCompat.from(this).notify(NUDGE_TAG, id, b.build()) } catch (_: SecurityException) {}
    }

    /** What locked notifications show: the same choice as every notification (kept outside the vault); never a name while panic-locked. */
    private fun lockedShow(): io.github.ravenor907.muninn.net.NudgeWire.Show = when {
        io.github.ravenor907.muninn.ui.Panic.locked.value -> io.github.ravenor907.muninn.net.NudgeWire.Show.NOTHING
        identity.showPreview -> io.github.ravenor907.muninn.net.NudgeWire.Show.MESSAGE
        identity.showSender -> io.github.ravenor907.muninn.net.NudgeWire.Show.NAME
        else -> io.github.ravenor907.muninn.net.NudgeWire.Show.NOTHING
    }

    /** Unlocked: every notification a nudge put up goes, and the counts start over. */
    @Synchronized
    private fun cancelLockedNudges() {
        lockedCounts.clear()
        try {
            val nm = NotificationManagerCompat.from(this)
            getSystemService(NotificationManager::class.java).activeNotifications.filter { it.tag == NUDGE_TAG }.forEach { nm.cancel(NUDGE_TAG, it.id) }
        } catch (_: Exception) {}
    }

    /** A tapped locked notification ([nudgeOpen]), once Muninn is unlocked: its chat when one matches, else the chat list. */
    fun openNudge() {
        val (from, where) = nudgeOpen ?: return
        if (!ready.value || vault.locked.value) return
        nudgeOpen = null
        val key = try {
            io.github.ravenor907.muninn.net.NudgeWire.match(from, where,
                contacts.all.value.filter { !it.groupOnly }.map { it.chatKey to it.name },
                chat.groups.all.value.values.map { io.github.ravenor907.muninn.net.Folders.groupKey(it.g) to it.state.name })
        } catch (_: Exception) { null } ?: return
        io.github.ravenor907.muninn.screens.SettingsNav.goChat = key
    }

    // ---- status: Away on its own after 10 minutes without a touch, and back to Online at the next one.
    @Volatile private var lastTouch = android.os.SystemClock.elapsedRealtime()
    /** True while Muninn itself set Away; a status the person picked is never changed. */
    @Volatile private var autoAway = false

    /** The window saw a touch or a key: no longer idle. If Muninn had set Away, Online again. */
    fun touched() {
        lastTouch = android.os.SystemClock.elapsedRealtime()
        if (autoAway) { autoAway = false; runCatching { if (profile.myStatus.value == "away") profile.setStatus("online") } }
    }

    private fun watchIdle() = scope.launch {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            val idle = android.os.SystemClock.elapsedRealtime() - lastTouch >= IDLE_MS
            runCatching {
                if (idle && !autoAway && profile.myStatus.value == "online") { autoAway = true; profile.setStatus("away") }
            }
        }
    }

    /** Extreme battery saver with Muninn not on screen: no ringing and no notifications, only the call history. */
    fun quietUntilOpen(): Boolean = !visible && try { power.manual() } catch (_: Exception) { false }

    /** Do not disturb is picked on Your profile: no message sound, and calls don't ring out loud. */
    fun doNotDisturb(): Boolean = ready.value && runCatching { profile.myStatus.value == "dnd" }.getOrDefault(false)

    private var listening = false
    /** The one sweep of the Wi-Fi network for devices (done once Muninn is on screen). */
    private var swept = false

    /** Stay reachable: the relays always (even locked, to say "New message"), and the Wi-Fi side once open. */
    /** The relays don't wait for a [start] under way (start calls this again once ready, for the Wi-Fi side). */
    fun listen() {
        nostr.keepAlive = true
        nostr.connect()
        if (!ready.value) return
        synchronized(this) {
            if (!listening) { listening = true; net.start() }
            // Asking every address on the Wi-Fi only once Muninn is on screen, never from the background.
            if (visible && !swept) { swept = true; net.refresh() }
        }
    }

    /** The refresh button on Chats: every relay checked again now, and anything not yet delivered sent again. */
    fun refreshChats() {
        if (!ready.value) return
        nostr.reconnectNow()
        nostr.resubscribe()
        nostr.checkInbox()
        chat.retryAllNow()
        net.announce()
    }

    /** A group message for me: an @mention of me, or a reply to one of my messages (the newest one there). */
    private fun toMe(key: String): Boolean {
        // Read from the message itself: a name or group name with "mentioned you in" in it doesn't get through.
        if (!key.startsWith("g:")) return false
        val g = key.removePrefix("g:")
        // Cap g3: a report to me as an admin, newer than the last message, counts as for me, like an @mention.
        val report = chat.groups.reportsByGroup.value[g]?.firstOrNull()
        val lastTime = chat.groups.messages.value[g]?.lastOrNull { !it.mine }?.time ?: 0L
        if (report != null && !report.read && report.time >= lastTime) return true
        val msgs = chat.groups.messages.value[g] ?: return false
        val last = msgs.lastOrNull { !it.mine } ?: return false
        if (chat.groups.mentionsMe(g, last)) return true
        return last.re.isNotEmpty() && msgs.any { it.id == last.re && it.mine }
    }

    /** The refresh button on Nearby: look again for phones close by, Huginn over Bluetooth, and devices on the Wi-Fi. */
    fun refreshNearby() {
        if (!ready.value) return
        ble.start()
        net.refresh()
    }

    /** Privacy first: unless the person allows names or messages (Settings › Notifications), a notification says only "New message". */
    fun notify(name: String?, text: String?, key: String, quiet: Boolean = false) {
        // A linked tablet's own chats are put away while its phone's chats show: nothing about who or what, and
        // tapping it doesn't open the chat.
        val putAway = ownChatsPutAway()
        // Panic-locked: never a name or text on the notification.
        val preview = identity.showPreview && name != null && !io.github.ravenor907.muninn.ui.Panic.locked.value && !putAway
        // "Name only": who it's from, never what they said.
        val nameOnly = !preview && identity.showSender && name != null && !io.github.ravenor907.muninn.ui.Panic.locked.value && !putAway
        // A group's: the channel the newest message is in, so the tap opens there (not the last one looked at).
        val ch = if (key.startsWith("g:")) try { chat.groups.messages.value[key.removePrefix("g:")]?.lastOrNull { !it.mine }?.ch.orEmpty() } catch (_: Exception) { "" } else ""
        val open = PendingIntent.getActivity(this, key.hashCode(), Intent(this, MainActivity::class.java).apply { if (!putAway) { putExtra("chat", key); putExtra("ch", ch) } }
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Previews off: a group still says which group and channel ("Gaming · #memes"), never who or what.
        val where = if (!preview && !putAway && !io.github.ravenor907.muninn.ui.Panic.locked.value) groupWhere(key) else null
        val n = NotificationCompat.Builder(this, if (quiet) QUIET else MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (preview || nameOnly) name else where ?: "Muninn").setContentText(if (preview) text else "New message")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (quiet) setSilent(true) else setVibrate(BUZZ) }.setOnlyAlertOnce(key == "inbox")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
            .apply { if (!putAway) replyAction(key)?.let { addAction(it) } }.build()
        try { NotificationManagerCompat.from(this).notify(key.hashCode(), n) } catch (_: SecurityException) {}
        // Kids' TV: also a short banner over the show or game, when a parent allowed "Show over other apps".
        if (!visible) try {
            if (io.github.ravenor907.muninn.tv.KidMode.on(this))
                io.github.ravenor907.muninn.tv.TvBanner.show(this, if (preview || nameOnly) name!! else "Muninn", if (preview) text ?: "New message" else "New message", key)
        } catch (_: Exception) {}
    }

    /** A group chat's name and the channel its newest message is in, or null for anything else. */
    private fun groupWhere(key: String): String? {
        if (!key.startsWith("g:")) return null
        return try {
            val g = key.removePrefix("g:")
            val s = chat.groups.group(g)?.state ?: return null
            val ch = chat.groups.messages.value[g]?.lastOrNull { !it.mine }?.ch
            s.name + (ch?.let { s.channel(it)?.n }?.let { " · #$it" } ?: "")
        } catch (_: Exception) { null }
    }

    /** Never: a linked tablet's own chats stay on show beside its phone's (protocol.md section 15). */
    private fun ownChatsPutAway(): Boolean = false   // this device's own chats stay on show beside a linked phone's

    /**
     * Reply from the notification is allowed now: Muninn open, not panic-locked, not a Kids' TV, and the
     * app lock isn't due (the same check MainActivity makes before showing anything).
     */
    fun canReply(): Boolean {
        val kids = try { io.github.ravenor907.muninn.tv.KidMode.on(this) } catch (_: Exception) { true }
        val lockDue = try {
            io.github.ravenor907.muninn.ui.AppLock.due(vault.hasPassword, io.github.ravenor907.muninn.ui.AppLock.awaySince,
                android.os.SystemClock.elapsedRealtime(), identity.lockAfter)
        } catch (_: Exception) { true }
        return io.github.ravenor907.muninn.ui.AppLock.canReply(ready.value, io.github.ravenor907.muninn.ui.Panic.locked.value, kids, lockDue)
    }

    /** Reply is held back only by the app lock (not panic, not a Kids' TV, not still starting). */
    private fun lockDueOnly(): Boolean {
        val kids = try { io.github.ravenor907.muninn.tv.KidMode.on(this) } catch (_: Exception) { true }
        return ready.value && !io.github.ravenor907.muninn.ui.Panic.locked.value && !kids
    }

    /** Reply from the notification (a contact's chat or a group), when [canReply]; else Unlock to reply. */
    private fun replyAction(key: String): NotificationCompat.Action? {
        if (!key.startsWith("g:") && !chat.isContact(key)) return null
        // The app lock is due: Unlock to reply opens Muninn's lock, then the chat.
        if (!canReply()) {
            if (!lockDueOnly()) return null
            val open = PendingIntent.getActivity(this, key.hashCode() + 1, Intent(this, MainActivity::class.java).putExtra("chat", key)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Action.Builder(R.drawable.ic_notify, "Unlock to reply", open).setShowsUserInterface(true).build()
        }
        val input = androidx.core.app.RemoteInput.Builder(io.github.ravenor907.muninn.net.ReplyReceiver.TEXT).setLabel("Reply").build()
        val intent = Intent(this, io.github.ravenor907.muninn.net.ReplyReceiver::class.java)
            .setAction(io.github.ravenor907.muninn.net.ReplyReceiver.ACTION).putExtra(io.github.ravenor907.muninn.net.ReplyReceiver.KEY, key)
        // Android fills the typed text into the intent, so it has to be mutable.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(this, key.hashCode(), intent, flags)
        return NotificationCompat.Action.Builder(R.drawable.ic_notify, "Reply", pi).addRemoteInput(input).setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).build()
    }

    /** A short double buzz: a message arrived in the chat you're looking at. */
    fun buzz() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createWaveform(BUZZ, -1))
        } catch (_: Exception) {}
    }

    companion object {
        const val MESSAGES = "raven_messages"
        const val QUIET = "raven_quiet"
        /** The inbox notification id: anything under it is taken down at the next unlock. */
        private val OLD_LOCKED_ID = "inbox".hashCode()
        /** Notifications put up for nudges while locked carry this tag (all taken down at the next unlock). */
        const val NUDGE_TAG = "locked-nudge"
        /** A locked notification's sender and group, for [openNudge]. */
        const val NUDGE_FROM = "nudgeFrom"
        const val NUDGE_CHAT = "nudgeChat"
        val BUZZ = longArrayOf(0, 140, 90, 140)
        /** Without a touch this long while Online, the status goes to Away. */
        const val IDLE_MS = 10 * 60_000L
    }
}
