package io.github.ravenor907.muninn.net

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Watches the screen, charging, Android's Battery Saver and Doze, the network and calls, asks
 * [PowerLogic] what to do, and tells the relays, Bluetooth, Nearby and Wi-Fi discovery. Everything
 * runs on the main thread.
 */
class Power(private val app: Muninn) {
    private val main = Handler(Looper.getMainLooper())
    private val pm = app.getSystemService(PowerManager::class.java)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val prefs = app.getSharedPreferences("muninn", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var started = 0
    /** The battery's last step ([NetLogic.batteryStep]); -2 until the first report. */
    private var batteryStep = -2
    @Volatile private var inCall = false
    @Volatile private var onWifi = false
    private var applied: PowerLogic.Plan? = null
    private var waiting: PowerLogic.Plan? = null
    private val later = Runnable { waiting?.let { use(it) } }

    private val _plan = MutableStateFlow(PowerLogic.plan(PowerLogic.Inputs(foreground = true, screenOn = true)))
    /** What Muninn does right now. */
    val plan: StateFlow<PowerLogic.Plan> = _plan.asStateFlow()
    private val _away = MutableStateFlow(_plan.value)
    /** What Muninn does once the screen goes off: the line Settings shows. */
    val away: StateFlow<PowerLogic.Plan> = _away.asStateFlow()

    /** Muninn's own "Battery saver" switch (on unless the person wants maximum speed), for "Right away". */
    var saver: Boolean
        get() = prefs.getBoolean("batterySaver", true)
        set(v) { prefs.edit().putBoolean("batterySaver", v).apply(); refresh() }

    /**
     * Settings › Check for messages. Before it existed, "Get messages right away" off meant the 15-minute
     * check, so that is where such a phone starts. Only "Right away" keeps the Listening service.
     */
    var check: PowerLogic.Check
        get() = PowerLogic.Check.of(prefs.getString("checkFor", null))
            ?: if (prefs.getBoolean("stayConnected", true)) PowerLogic.Check.LIVE else PowerLogic.Check.QUARTER
        set(v) {
            prefs.edit().putString("checkFor", v.key).putBoolean("stayConnected", v == PowerLogic.Check.LIVE).apply()
            followed = null; followedEvery = -1L   // the schedule and the Listening service follow at once
            refresh()
        }

    /** Settings › Save more when the battery is low (on unless switched off). */
    var lowBatterySave: Boolean
        get() = prefs.getBoolean("lowBatterySave", true)
        set(v) { prefs.edit().putBoolean("lowBatterySave", v).apply(); refresh() }

    /** Settings › Quiet hours: on, from and to (hours of the day, 0 to 23), and the mode they use. */
    var quietOn: Boolean
        get() = prefs.getBoolean("quietOn", false)
        set(v) { prefs.edit().putBoolean("quietOn", v).apply(); refresh() }
    var quietFrom: Int
        get() = prefs.getInt("quietFrom", 23)
        set(v) { prefs.edit().putInt("quietFrom", Math.floorMod(v, 24)).apply(); refresh() }
    var quietTo: Int
        get() = prefs.getInt("quietTo", 7)
        set(v) { prefs.edit().putInt("quietTo", Math.floorMod(v, 24)).apply(); refresh() }
    var quietMode: PowerLogic.Check
        get() = PowerLogic.Check.of(prefs.getString("quietMode", null)) ?: PowerLogic.Check.HOUR
        set(v) { prefs.edit().putString("quietMode", v.key).apply(); refresh() }

    /** Settings › Quiet hours › Also during Do Not Disturb (on unless switched off). */
    var followDnd: Boolean
        get() = prefs.getBoolean("followDnd", true)
        set(v) { prefs.edit().putBoolean("followDnd", v).apply(); refresh() }

    /** Settings › Check less when it's quiet (on unless switched off). */
    var adaptive: Boolean
        get() = prefs.getBoolean("adaptiveCheck", true)
        set(v) { prefs.edit().putBoolean("adaptiveCheck", v).apply(); refresh() }

    /** Settings › Data saver: on mobile data, big files wait until you tap them, and updates wait for Wi-Fi (on unless switched off). */
    var dataSaver: Boolean
        get() = prefs.getBoolean("dataSaver", true)
        set(v) { prefs.edit().putBoolean("dataSaver", v).apply() }

    /**
     * Settings › Battery › Extreme battery saver: fully manual. Nothing in the background (no connection, no inbox
     * checks, no alarms, no notifications); messages arrive when Muninn is opened or refreshed, calls ring only while
     * it's open. Switching it off brings the usual connection back at once.
     */
    var extreme: Boolean
        get() = prefs.getBoolean("extremeSaver", false)
        set(v) {
            if (v == extreme) return
            prefs.edit().putBoolean("extremeSaver", v).apply()
            followed = null; followedEvery = -1L   // the schedule and the Listening service follow at once
            refresh()
            if (!v) main.post { caughtUpAt = 0L; catchUp(opened = true) }
        }

    /** Extreme battery saver is in charge right now (a Kids' TV always listens and rings): [PowerLogic.manual]. */
    fun manual(): Boolean = PowerLogic.manual(PowerLogic.Inputs(extreme = extreme,
        kidsTv = try { io.github.ravenor907.muninn.tv.KidMode.on(app) } catch (_: Exception) { false }))

    /** Something arrived or Muninn was opened: wide awake again (Check less when it's quiet starts over). */
    fun activity() {
        prefs.edit().putLong("lastActivity", System.currentTimeMillis()).apply()
        refresh()
    }

    // ------------------------------------------------------------------ Always right away, conversations, catching up

    /** The "Always right away" list; null until the vault is open. */
    var rightAway: RightAway? = null
        private set

    /** The Family folder's chats: the list starts as these and follows the folder. */
    private fun family(): List<String> = try {
        if (!app.ready.value) emptyList() else app.chat.folders.all.value.firstOrNull { it.name.equals(FAMILY, true) }?.keys.orEmpty()
    } catch (_: Exception) { emptyList() }

    /** The chats (contact keys, or "g:" and a group id) on the "Always right away" list now. */
    fun rightAwayKeys(): Set<String> = rightAway?.keys(family()).orEmpty()

    fun isRightAway(key: String): Boolean = key in rightAwayKeys()

    /** Puts [key] on the "Always right away" list, or takes it off. */
    fun setRightAway(key: String, on: Boolean) { rightAway?.set(key, on, key in family()) }

    /** When a message was last sent or received (elapsed time, so it counts while the phone sleeps); -1: not yet. */
    @Volatile private var talkedAt = -1L

    /** A message went or came: every relay for a few minutes, so the reply arrives at once (the conversation window). */
    fun talk() {
        talkedAt = android.os.SystemClock.elapsedRealtime()
        main.removeCallbacks(talkOver)
        main.postDelayed(talkOver, PowerLogic.TALK_MS + 1_000L)
        refresh()
    }

    private val talkOver = Runnable { refresh() }

    private fun talking(): Boolean = talkedAt >= 0 && PowerLogic.talking(android.os.SystemClock.elapsedRealtime() - talkedAt)

    private var caughtUpAt = 0L

    /**
     * Unlocked, back on a network, or Muninn opened: ask for what's waiting at once instead of at the next
     * check. Dead connections are found and replaced within seconds; with nothing live, the inbox is read now.
     * [opened]: Muninn itself was opened, which catches up even with "Only when open".
     */
    fun catchUp(opened: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { catchUp(opened) }; return }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - caughtUpAt < CATCH_UP_GAP_MS) return
        if (!opened && (check == PowerLogic.Check.OPEN || extreme)) return
        caughtUpAt = now
        try {
            app.nostr.reconnectNow()
            if (!app.nostr.listening()) app.nostr.checkInbox()
            // Anything of mine still waiting goes again now, so their phone says "Delivered" sooner.
            if (app.ready.value) app.chat.retryAllNow()
        } catch (_: Exception) {}
    }

    /** How a message from [key] that just arrived is shown (see [PowerLogic.alert]). */
    fun alert(key: String): PowerLogic.Alert = PowerLogic.alert(inputs(), isRightAway(key))

    /** Charging (or a TV, which always is). */
    fun charging(): Boolean {
        val battery = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
            battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) == false ||
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    /** On a network that costs by the byte (mobile data, a hotspot), or Android's Data Saver is on. */
    fun metered(): Boolean = try {
        cm.isActiveNetworkMetered || cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    } catch (_: Exception) { false }

    /** Data saver is on and this network costs: big downloads wait for a tap, or for Wi-Fi. */
    fun savingData(): Boolean = dataSaver && metered()

    /** Do Not Disturb, Bedtime mode or another quiet mode is on. */
    private fun dndOn(): Boolean = try {
        app.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter.let {
            it != android.app.NotificationManager.INTERRUPTION_FILTER_ALL && it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
    } catch (_: Exception) { false }

    /** Settings › Find phones close by in the background (off unless switched on). */
    var closeBy: Boolean
        get() = prefs.getBoolean("closeByBackground", false)
        set(v) { prefs.edit().putBoolean("closeByBackground", v).apply(); refresh() }

    /** The Files tab is the one showing (MainActivity says): Wi-Fi discovery at full while Muninn is open on it. */
    val filesOpen = MutableStateFlow(false)
    @Volatile private var sharing = false

    /** What Muninn checks by right now: the person's choice, or less for quiet hours or a low battery. */
    fun effective(): PowerLogic.Check = PowerLogic.effective(inputs())

    /**
     * The background inbox check, as often as the mode in use says. "Only when open" chosen by the person
     * checks nothing; quiet hours that use it still wake Muninn hourly, only to see whether they've ended.
     * Extreme battery saver never runs it.
     */
    fun schedule(every: Long = PowerLogic.jobMinutes(inputs())) {
        try {
            val wm = WorkManager.getInstance(app)
            if (every == 0L) wm.cancelUniqueWork("inbox")
            else wm.enqueueUniquePeriodicWork("inbox", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<InboxWorker>(every, TimeUnit.MINUTES).build())
        } catch (_: Exception) {}
    }

    fun install() {
        // The battery report counts the connection's checks, wake-ups and reconnects. An alarm that woke the
        // phone also looks whether the conversation window has ended (a timer alone stops while it sleeps).
        app.nostr.counted = { kind -> countNet(kind); if (kind == Nostr.WAKE) main.post { refresh() } }
        app.nostr.countedPings = { n -> countNet(Nostr.SERVER_PING, n) }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; activity(); catchUp(opened = true) }
            override fun onActivityStopped(a: Activity) { started = (started - 1).coerceAtLeast(0); refresh() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED); addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            // The battery level, for saving more when it's low (a cheap check each time it changes).
            addAction(Intent.ACTION_BATTERY_CHANGED)
            // Do Not Disturb turned on or off: quiet hours follow it.
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            // Unlocked: catch up at once.
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.action == Intent.ACTION_USER_PRESENT) catchUp()
                    if (i?.action == Intent.ACTION_SCREEN_ON) saveCounts()
                    // Android says the battery level often: only crossing 15% or 30%, or the plug, changes anything.
                    if (i?.action == Intent.ACTION_BATTERY_CHANGED) {
                        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
                        val step = NetLogic.batteryStep(percent, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
                        if (step == batteryStep) return
                        batteryStep = step
                    }
                    refresh()
                }
            }, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (_: Exception) {}
        // Network changes: reconnect to the relays at once, and know whether this is Wi-Fi.
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // Back on a network: connect again once (catching up does it, or only that with "Only when open").
                override fun onAvailable(network: Network) {
                    main.post { caughtUpAt = 0L; if (check == PowerLogic.Check.OPEN || extreme) app.nostr.reconnectNow() else catchUp() }
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val w = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (w != onWifi) { onWifi = w; main.post { refresh() } }
                }
                override fun onLost(network: Network) { if (onWifi) { onWifi = false; main.post { refresh() } } }
            })
        } catch (_: Exception) {}
        refresh()
    }

    /** Called once the vault is open: calls are known from now on, and Bluetooth, Nearby and Wi-Fi exist. */
    fun ready() {
        scope.launch {
            combine(io.github.ravenor907.muninn.net.call.CallManager.call, io.github.ravenor907.muninn.net.call.RoomManager.room) { c, r -> c != null || r != null }
                .collect { inCall = it; refresh() }
        }
        // Sharing files: a transfer or Direct connect keeps Wi-Fi discovery at full (and the Files tab, while open).
        scope.launch {
            combine(app.net.transfer, app.direct.state) { t, d -> t.active || d.on }
                .collect { sharing = it; refresh() }
        }
        scope.launch { filesOpen.collect { refresh() } }
        // The "Always right away" list: whether anyone is on it is kept outside the vault too, so the connection
        // stays right while Muninn is locked.
        val ra = RightAway({ app.vault.read(java.io.File(app.filesDir, "rightaway.json")) },
            { app.vault.write(java.io.File(app.filesDir, "rightaway.json"), it) })
        rightAway = ra
        scope.launch {
            combine(app.chat.folders.all, ra.all) { _, _ -> rightAwayKeys().isNotEmpty() }
                .collect { if (it != prefs.getBoolean(RIGHT_AWAY_ANY, false)) prefs.edit().putBoolean(RIGHT_AWAY_ANY, it).apply(); refresh() }
        }
        // A message sent from this phone opens the conversation window (one that arrives does so in Muninn).
        scope.launch { app.chat.sends.collect { talk() } }
        applied?.let { radios(it) }
    }

    private fun inputs(): PowerLogic.Inputs {
        val battery = try { app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0 ||
            battery?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true) == false ||
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val kids = try { io.github.ravenor907.muninn.tv.KidMode.on(app) } catch (_: Exception) { false }
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val quiet = if ((quietOn && PowerLogic.inQuiet(quietFrom, quietTo, hour)) || (followDnd && dndOn())) quietMode else null
        val idle = (System.currentTimeMillis() - prefs.getLong("lastActivity", System.currentTimeMillis())).coerceAtLeast(0)
        return PowerLogic.Inputs(
            foreground = started > 0, screenOn = pm.isInteractive, inCall = inCall, kidsTv = kids, plugged = plugged,
            alwaysFull = !saver, batterySaver = pm.isPowerSaveMode, doze = pm.isDeviceIdleMode,
            unrestricted = pm.isIgnoringBatteryOptimizations(app.packageName), onWifi = onWifi, check = check,
            battery = percent, lowBatterySave = lowBatterySave, quiet = quiet, closeBy = closeBy, sharing = sharing || (filesOpen.value && started > 0),
            idleMs = if (started > 0) 0 else idle, adaptive = adaptive,
            rightAway = prefs.getBoolean(RIGHT_AWAY_ANY, false), talking = talking(), extreme = extreme,
        )
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { refresh() }; return }
        val i = inputs()
        val next = PowerLogic.plan(i)
        _away.value = PowerLogic.whenAway(i)
        follow(PowerLogic.effective(i), i.kidsTv, PowerLogic.jobMinutes(i))
        // Quiet hours start and end on the hour, and Check less when it's quiet steps down hourly: look again then.
        main.removeCallbacks(onTheHour)
        main.postDelayed(onTheHour, 3_600_000L - System.currentTimeMillis() % 3_600_000L + 1_000L)
        val now = applied
        if (now == null || !PowerLogic.saves(now, next)) {
            main.removeCallbacks(later); waiting = null
            if (next != now) use(next) else app.nostr.plan(next.relays, next.pingMs)   // unchanged: only undo an early check
            return
        }
        // Saving more: wait a moment before closing anything, in case the screen comes straight back on. The slower
        // check is set at once though, on an alarm, so it runs even if the phone falls asleep meanwhile.
        val t = android.os.SystemClock.elapsedRealtime()
        if (waiting != next) {
            waiting = next; waitingSince = t
            main.removeCallbacks(later); main.postDelayed(later, PowerLogic.GRACE_MS)
            app.nostr.pingEarly(next.pingMs)
        } else if (t - waitingSince >= PowerLogic.GRACE_MS) use(next)   // the phone slept through the timer
    }

    private var waitingSince = 0L

    // ------------------------------------------------------------------ battery report (Settings)

    /**
     * Today so far: time open on screen or charging (costs the connection nothing worth counting), fully connected
     * in the background (a conversation, a call, Battery saver off), on the one light connection, and resting
     * (nothing live: the rest of the day); the connection's checks, the wake-ups among them and reconnects;
     * background inbox checks; a rough share of the battery; data Muninn has used since the phone started.
     */
    data class Report(val openMs: Long, val connectedMs: Long, val savingMs: Long, val restingMs: Long, val checks: Int,
                      val pings: Int, val wakeups: Int, val reconnects: Int, val costPercent: Double, val dataBytes: Long?,
                      /** Checks the message servers sent this phone on their own (each wakes the radio for a moment). */
                      val serverPings: Int = 0)

    private var countedSince = System.currentTimeMillis()
    private var countedKind = -1

    /** 0 fully connected in the background, 1 the one light connection, 2 nothing live, 3 open on screen or charging. */
    private fun kindOf(p: PowerLogic.Plan): Int = when {
        p.relays == 0 -> 2
        p.why == PowerLogic.Why.OPEN || p.why == PowerLogic.Why.PLUGGED || p.why == PowerLogic.Why.KIDS_TV -> 3
        p.why == PowerLogic.Why.SCREEN_OFF || p.why == PowerLogic.Why.BACKGROUND || p.why == PowerLogic.Why.BATTERY_SAVER -> 1
        else -> 0
    }

    /**
     * Today's counts, kept in memory and saved when the screen comes on and on the hour (not on every check, which
     * would write to storage each time the connection is checked).
     */
    private var reportDay = prefs.getString("reportDay", "") ?: ""
    private var connectedMs = prefs.getLong("reportConnected", 0)
    private var savingMs = prefs.getLong("reportSaving", 0)
    private var openMs = prefs.getLong("reportOpen", 0)
    private var checks = prefs.getInt("reportChecks", 0)
    private val netCounts = IntArray(NET_KEYS.size) { prefs.getInt(NET_KEYS[it], 0) }

    /** Adds the time since the last count to its kind, starting over each day. */
    @Synchronized private fun count(now: Long, next: Int) {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date(now))
        if (reportDay != today) {
            reportDay = today
            connectedMs = 0; savingMs = 0; openMs = 0; checks = 0
            netCounts.fill(0)
            countedSince = maxOf(countedSince, midnight(now))
        }
        val spent = (now - countedSince).coerceAtLeast(0)
        when (countedKind) { 0 -> connectedMs += spent; 1 -> savingMs += spent; 3 -> openMs += spent }
        countedSince = now; countedKind = next
    }

    /** Saves today's counts (screen on, on the hour). */
    @Synchronized private fun saveCounts() {
        count(System.currentTimeMillis(), countedKind)
        val e = prefs.edit().putString("reportDay", reportDay).putInt("reportChecks", checks)
            .putLong("reportConnected", connectedMs).putLong("reportSaving", savingMs).putLong("reportOpen", openMs)
        for (i in NET_KEYS.indices) e.putInt(NET_KEYS[i], netCounts[i])
        e.apply()
    }

    private fun midnight(now: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** A background inbox check ran (InboxWorker). */
    @Synchronized fun countCheck() {
        count(System.currentTimeMillis(), countedKind)
        checks++
    }

    /** The connection checked its relays, an alarm woke the phone for it, or it connected again ([Nostr.counted]). */
    @Synchronized private fun countNet(kind: String, n: Int = 1) {
        val i = when (kind) { Nostr.PING -> 0; Nostr.WAKE -> 1; Nostr.SERVER_PING -> 3; else -> 2 }
        count(System.currentTimeMillis(), countedKind)   // a new day starts the counts over
        netCounts[i] += n
    }

    fun report(): Report {
        try { app.nostr.countServerPings() } catch (_: Exception) {}
        val now = System.currentTimeMillis()
        return synchronized(this) {
            count(now, countedKind)
            val uid = android.os.Process.myUid()
            val rx = android.net.TrafficStats.getUidRxBytes(uid)
            val tx = android.net.TrafficStats.getUidTxBytes(uid)
            val data = if (rx < 0 || tx < 0) null else rx + tx
            Report(openMs, connectedMs, savingMs, (now - midnight(now) - openMs - connectedMs - savingMs).coerceAtLeast(0), checks,
                netCounts[0], netCounts[1], netCounts[2], PowerLogic.costPercent(connectedMs, savingMs, netCounts[1], netCounts[2]), data, netCounts[3])
        }
    }

    private val onTheHour = Runnable { saveCounts(); refresh() }
    private var followed: PowerLogic.Check? = null
    private var followedEvery = -1L

    /**
     * The mode in use changed (the person's choice, quiet hours or the battery): the background check runs as
     * often as it says, and the Listening service runs only while it's "Right away".
     */
    private fun follow(now: PowerLogic.Check, kids: Boolean, every: Long) {
        if (now == followed && every == followedEvery) return
        val modeChanged = now != followed
        followed = now
        followedEvery = every
        schedule(every)
        if (!modeChanged) return
        if (kids) return   // a Kids' TV always listens (KidMode keeps the service)
        if (now == PowerLogic.Check.LIVE) MessageService.start(app) else MessageService.stop(app)
    }

    private fun use(p: PowerLogic.Plan) {
        waiting = null
        val before = applied
        applied = p
        _plan.value = p
        count(System.currentTimeMillis(), kindOf(p))
        app.nostr.plan(p.relays, p.pingMs)
        radios(p)
        // No live connections now: check the inbox once at once, then the scheduled check carries on
        // ("Only when open" checks nothing until Muninn is opened again).
        if (p.relays == 0 && before?.relays != 0 && p.why != PowerLogic.Why.ONLY_OPEN && p.why != PowerLogic.Why.EXTREME) try {
            WorkManager.getInstance(app).enqueueUniqueWork("inbox-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<InboxWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        } catch (_: Exception) {}
    }

    companion object {
        /** The starting folder the "Always right away" list follows. */
        const val FAMILY = "Family"
        private const val RIGHT_AWAY_ANY = "rightAwayAny"
        /** Unlocking over and over doesn't catch up each time. */
        private const val CATCH_UP_GAP_MS = 20_000L
        private val NET_KEYS = listOf("reportPings", "reportWakeups", "reportReconnects", "reportServerPings")
    }

    private fun radios(p: PowerLogic.Plan) {
        if (!app.ready.value) return
        try { app.ble.power(p.radio) } catch (_: Exception) {}
        try { app.close.power(p.radio) } catch (_: Exception) {}
        try { app.net.discovery(p.wifi) } catch (_: Exception) {}
    }
}
