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
import io.github.ravenor907.muninn.net.Close
import io.github.ravenor907.muninn.net.CloseFiles
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
    lateinit var close: Close
    lateinit var ble: io.github.ravenor907.muninn.net.Ble
    lateinit var closeFiles: CloseFiles
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

    override fun onCreate() {
        super.onCreate()
        vault = Vault(this)
        identity = Identity(this, vault)
        io.github.ravenor907.muninn.ui.Access.apply {
            size = identity.uiSize; text = identity.textSize; contrast = identity.highContrast
            colorBlind = identity.colorBlind; reduceMotion = identity.reduceMotion; look = io.github.ravenor907.muninn.ui.Looks.of(identity.look)
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
        nostr.onEnvelope = { false }
        // While locked: one "Unlock Muninn to see new messages", with a sound only the first time.
        nostr.onLocked = { lockedNotice() }
        // Cap b1: the ring key lives outside the vault (app-private files, never an OS key store), so a call rings even
        // while Muninn is locked. Only "Incoming call", no name: answering opens the lock screen, and the call itself
        // (kept sealed with everything else that came while locked) is picked up once Muninn is unlocked.
        val ring = io.github.ravenor907.muninn.net.RingKey(java.io.File(filesDir, "ring-key.json"))
        nostr.ring = ring
        nostr.onRing = { content ->
            ring.open(content)?.let { (call, ts) -> io.github.ravenor907.muninn.net.call.CallManager.ringLocked(this, call, ts) }
        }
        // Erase everything and Move: the ring key, the day keys and what was kept while locked all go.
        vault.onErase = {
            ring.forget()
            nostr.forgetHeld()
            if (ready.value) try { chat.forgetKeys() } catch (_: Exception) {}
        }
        // Screen, charging, Battery Saver, Doze, calls and the network decide how connected to stay.
        // (It also reconnects to the relays at once when the network comes back.)
        power = io.github.ravenor907.muninn.net.Power(this)
        power.install()
        // Moving to another device (cap x1): one under way goes on, and this device stays locked until it's done.
        io.github.ravenor907.muninn.net.Move.init(this)
        if (vault.openDefault()) start()
        io.github.ravenor907.muninn.net.MessageService.start(this)
        // Check the internet inbox even when Muninn is closed: every 15 minutes (Android's shortest) or every
        // hour, as Settings › Check for messages says; never for "Only when open".
        power.schedule()
    }

    /** Called once the vault is open. */
    @Synchronized
    fun start() {
        if (ready.value) return
        net = LocalSend(this, identity)
        net.mailbox = nostr.publicKey
        close = Close(this, identity)
        ble = io.github.ravenor907.muninn.net.Ble(this) { chat.hello() }
        // Hidden from people nearby: Bluetooth is silent too (section 21.3).
        ble.hidden = { !identity.visibleNearby }
        contacts = Contacts(this, vault)
        chat = Chat(this, net, close, ble, nostr, contacts, vault)
        chat.ringKey = nostr.ring
        profile = io.github.ravenor907.muninn.net.Profile(this, vault, contacts, chat)
        chat.profile = profile
        io.github.ravenor907.muninn.tv.Tv.install(this)
        io.github.ravenor907.muninn.net.call.CallManager.init(this, chat)
        io.github.ravenor907.muninn.net.call.RoomManager.init(this)
        closeFiles = CloseFiles(this, close, net)
        // A notification for every message, unless that very chat is on screen: then just a buzz.
        scope.launch { chat.arrivals.collect { (name, text, key) ->
            power.activity()   // Check less when it's quiet starts over
            // A 1:1 message opens the conversation window, so the next one comes at once too. Group messages don't.
            if (io.github.ravenor907.muninn.net.NetLogic.opensTalk(key)) power.talk()
            // Muted chats stay quiet; an archived chat comes back to the list (unless muted).
            if (!chat.flags.arrived(key)) return@collect
            if (visible && openChat == key && !io.github.ravenor907.muninn.ui.Panic.locked.value) { buzz(); return@collect }
            // Extreme battery saver: no notifications; the chat list shows what's new.
            if (power.extreme) return@collect
            // Quiet hours kept open for the "Always right away" list: everyone else's messages wait quietly.
            when (power.alert(key)) {
                io.github.ravenor907.muninn.net.PowerLogic.Alert.LOUD -> notify(name, text, key, quiet = doNotDisturb())
                io.github.ravenor907.muninn.net.PowerLogic.Alert.QUIET -> notify(name, text, key, quiet = true)
                io.github.ravenor907.muninn.net.PowerLogic.Alert.NONE -> {}
            }
        } }
        // Unsent: take its notification down too, so the text doesn't linger there.
        scope.launch { chat.recalls.collect { key -> NotificationManagerCompat.from(this@Muninn).cancel(key.hashCode()) } }
        ready.value = true
        io.github.ravenor907.muninn.net.Move.ready(this)
        // A window already on screen (onStart ran before the vault opened): say so now, with Active status on.
        chat.foreground(visible)
        power.ready()
        watchIdle()
        // Unlocked again later (the lock came due while Muninn was running): the same catching up.
        scope.launch { vault.locked.drop(1).collect { locked -> if (!locked) unlocked() } }
        unlocked()
    }

    /**
     * Just unlocked: the "Unlock Muninn" notice goes, what was kept sealed while locked is opened (and deleted),
     * and the relays are asked again for anything that had to stay with them.
     */
    private fun unlocked() {
        getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE).edit().remove(LOCKED_ALERTED).apply()
        NotificationManagerCompat.from(this).cancel(LOCKED_ID)
        Thread {
            try { nostr.openHeld() } catch (_: Exception) {}
            nostr.resubscribe()
        }.start()
    }

    /**
     * Something arrived while locked. Nothing can be opened yet, so one notification says so: with a sound the
     * first time, then silent until Muninn is unlocked. Never during Extreme battery saver (no notifications then).
     */
    @Synchronized
    fun lockedNotice() {
        if (power.extreme) return
        val prefs = getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE)
        val alerted = prefs.getBoolean(LOCKED_ALERTED, false)
        if (alerted && Build.VERSION.SDK_INT >= 23 && try {
                getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == LOCKED_ID }
            } catch (_: Exception) { false }) return   // already showing
        val open = PendingIntent.getActivity(this, LOCKED_ID, Intent(this, MainActivity::class.java).putExtra("chat", "inbox")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, if (alerted) QUIET else MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Muninn").setContentText("Unlock Muninn to see new messages")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (alerted) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (alerted) setSilent(true) else setVibrate(BUZZ) }.setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(this).notify(LOCKED_ID, n) } catch (_: SecurityException) {}
        if (!alerted) prefs.edit().putBoolean(LOCKED_ALERTED, true).apply()
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
    @Synchronized
    fun listen() {
        nostr.keepAlive = true
        nostr.connect()
        if (!ready.value) return
        if (!listening) { listening = true; net.start() }
        // Asking every address on the Wi-Fi only once Muninn is on screen, never from the background.
        if (visible && !swept) { swept = true; net.refresh() }
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

    /** The refresh button on Nearby: look again for phones close by, Huginn over Bluetooth, and devices on the Wi-Fi. */
    fun refreshNearby() {
        if (!ready.value) return
        close.lookAgain()
        ble.start()
        net.refresh()
    }

        /** Privacy first: unless the person allows previews, a notification says only "New message". */
    fun notify(name: String?, text: String?, key: String, quiet: Boolean = false) {
        // Panic-locked: never a name or text on the notification.
        val preview = identity.showPreview && name != null && !io.github.ravenor907.muninn.ui.Panic.locked.value
        val open = PendingIntent.getActivity(this, key.hashCode(), Intent(this, MainActivity::class.java).putExtra("chat", key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, if (quiet) QUIET else MESSAGES).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (preview) name else "Muninn").setContentText(if (preview) text else "New message")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .apply { if (quiet) setSilent(true) else setVibrate(BUZZ) }.setOnlyAlertOnce(key == "inbox")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
            .apply { replyAction(key)?.let { addAction(it) } }.build()
        try { NotificationManagerCompat.from(this).notify(key.hashCode(), n) } catch (_: SecurityException) {}
        // Kids' TV: also a short banner over the show or game, when a parent allowed "Show over other apps".
        if (!visible) try {
            if (io.github.ravenor907.muninn.tv.KidMode.on(this))
                io.github.ravenor907.muninn.tv.TvBanner.show(this, if (preview) name!! else "Muninn", if (preview) text ?: "New message" else "New message", key)
        } catch (_: Exception) {}
    }

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

    /** Reply from the notification (a contact's chat or a group), only when [canReply]. */
    private fun replyAction(key: String): NotificationCompat.Action? {
        if (!canReply()) return null
        if (!key.startsWith("g:") && !chat.isContact(key)) return null
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
        /** The "Unlock Muninn to see new messages" notification (the same id the inbox notice always had). */
        val LOCKED_ID = "inbox".hashCode()
        private const val LOCKED_ALERTED = "lockedAlerted"
        val BUZZ = longArrayOf(0, 140, 90, 140)
        /** Without a touch this long while Online, the status goes to Away. */
        const val IDLE_MS = 10 * 60_000L
    }
}
