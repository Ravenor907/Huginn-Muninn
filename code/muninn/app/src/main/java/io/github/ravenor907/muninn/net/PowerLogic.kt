package io.github.ravenor907.muninn.net

/**
 * How hard Muninn works to stay reachable, decided from what the phone is doing. Plain Kotlin (no
 * Android) so the tests can check every case. [Power] feeds it and applies the answer to the
 * relays, Bluetooth, Nearby and Wi-Fi discovery.
 *
 * - Open on screen, in a call, a Kids' TV, plugged in, or Battery saver switched off: fully connected.
 * - Screen off or Muninn in the background ("Right away"): one light connection. The healthiest relay stays
 *   open, checked about every 5 minutes (just under the time a mobile network forgets a quiet connection),
 *   on an alarm Android may line up with other apps' wake-ups. A relay that stops answering is swapped for
 *   the next healthiest at once. No Google push and no periodic checks instead. Bluetooth and Nearby go low
 *   power and take turns; Wi-Fi discovery slows down (or stops off Wi-Fi).
 * - A conversation (a message sent or received in the last [TALK_MS]): every relay and the quick check, so
 *   replies arrive at once.
 * - Android's Battery Saver: like the screen being off (one connection, so calls still ring), with Wi-Fi
 *   discovery off.
 * - Doze while Android may cut Muninn's network (background use not unrestricted): no live connections; the
 *   15-minute inbox check picks messages up. The Finish setting up card asks for unrestricted use.
 * - Settings › Check for messages "Every 15 minutes" or "Every hour": nothing live while Muninn is closed
 *   (unless charging); the inbox check ([checkEveryMinutes]) picks messages up and old calls show as missed.
 *   "Only when open": nothing at all until Muninn is opened, charging or not. These are the only periodic modes.
 * - Low battery (on unless switched off, never while charging): below 30% close-by scanning rests; below 15%
 *   the one connection is checked every [LONG_PING_MS] instead of being dropped. Never an hourly floor.
 * - Quiet hours (and Do Not Disturb, unless switched off): the mode picked for them, while they last. With
 *   anyone on the "Always right away" list and "Right away" chosen, the connection stays instead, their
 *   messages ring as usual, and everyone else's arrive quietly ([alert]).
 * - Check less when it's quiet (on unless switched off, never while charging): after [DROWSY_MS] with nothing
 *   arriving and Muninn not opened, the one connection is checked every [LONG_PING_MS] (calls still ring), and
 *   "Every 15 minutes" becomes every 30, then every hour after twice as long.
 * - The "Always right away" list ([rightAway]: the Family folder to start with, plus or minus anyone): low
 *   battery, quiet hours and checking less never hold their messages or notifications back. While anyone is
 *   on it, the one connection keeps its 5-minute check.
 * - Extreme battery saver ([Inputs.extreme]): fully manual. Nothing in the background at all: no connection, no
 *   inbox checks, no alarms, so no notifications. Messages arrive when Muninn is opened or Chats is refreshed, and
 *   calls ring only while Muninn is open. A call already under way and a Kids' TV still stay connected.
 * - Bluetooth and Nearby look for phones close by only while Muninn is open, unless "Find phones close by in
 *   the background" is on. Wi-Fi discovery runs fully only on the Files tab, in a transfer or Direct connect.
 */
object PowerLogic {
    enum class Radio { FULL, LOW, OFF }

    enum class Why { OPEN, CALL, ROOM, KIDS_TV, PLUGGED, ALWAYS, TALKING, BACKGROUND, SCREEN_OFF, BATTERY_SAVER, DOZE, EVERY_15, EVERY_HOUR, ONLY_OPEN, EXTREME }

    /** Settings › Notifications › Check for messages. */
    enum class Check(val key: String, val title: String) {
        LIVE("live", "Right away"), QUARTER("15m", "Every 15 minutes"), HOUR("hour", "Every hour"), OPEN("open", "Only when open");
        companion object { fun of(key: String?): Check? = entries.firstOrNull { it.key == key } }
    }

    /**
     * How often the background inbox check runs for [c] (0: never, Muninn waits to be opened). With "Right away"
     * it only looks whether the live connection is still there (it does nothing while it is).
     */
    fun checkEveryMinutes(c: Check): Long = when (c) { Check.LIVE, Check.QUARTER -> 15; Check.HOUR -> 60; Check.OPEN -> 0 }

    data class Inputs(
        val foreground: Boolean = false,
        val screenOn: Boolean = false,
        val inCall: Boolean = false,
        /** In a voice room or group call: every relay, but the other radios rest with the screen off. */
        val inRoom: Boolean = false,
        val kidsTv: Boolean = false,
        val plugged: Boolean = false,
        /** The person switched Muninn's own "Battery saver" off. */
        val alwaysFull: Boolean = false,
        /** Android's Battery Saver. */
        val batterySaver: Boolean = false,
        val doze: Boolean = false,
        /** Muninn may run unrestricted in the background (Doze leaves its network alone then). */
        val unrestricted: Boolean = false,
        val onWifi: Boolean = false,
        /** Settings › Check for messages. */
        val check: Check = Check.LIVE,
        /** Battery level, 0 to 100. */
        val battery: Int = 100,
        /** Settings › Save more when the battery is low. */
        val lowBatterySave: Boolean = true,
        /** In quiet hours right now: the mode they use (null outside them). */
        val quiet: Check? = null,
        /** Settings › Find phones close by in the background. */
        val closeBy: Boolean = false,
        /** The Files tab is open, a transfer is running, or Direct connect is on: Wi-Fi discovery at full. */
        val sharing: Boolean = false,
        /** How long nothing has arrived and Muninn hasn't been opened (ms). */
        val idleMs: Long = 0,
        /** Settings › Check less when it's quiet. */
        val adaptive: Boolean = true,
        /** Someone is on the "Always right away" list. */
        val rightAway: Boolean = false,
        /** A message was sent or received in the last [TALK_MS]: the conversation window. */
        val talking: Boolean = false,
        /** Extreme battery saver: nothing in the background, Muninn only fetches while open (see [manual]). */
        val extreme: Boolean = false,
        /**
         * Any Kids' Device (phone, tablet or TV). Quiet hours, Android's Do Not Disturb and Extreme saver never slow it
         * down: parents' calls must always ring, so it keeps its normal "Check for messages" cadence.
         */
        val kidDevice: Boolean = false,
    )

    /** Extreme battery saver is in charge: nothing runs in the background (a Kids' TV always listens). */
    fun manual(i: Inputs): Boolean = i.extreme && !i.kidsTv && !i.kidDevice

    /** What Extreme saver turns off (looking close by in the background, link previews), kept to turn back on after. */
    fun extremeSnapshot(closeBy: Boolean, previews: Boolean): String = (if (closeBy) "c" else "") + (if (previews) "p" else "")

    /** Leaving Extreme saver: (close by in the background, link previews) as they were before it, or null when unknown. */
    fun extremeRestore(snapshot: String?): Pair<Boolean, Boolean>? =
        if (snapshot == null || snapshot.any { it != 'c' && it != 'p' }) null else ('c' in snapshot) to ('p' in snapshot)

    /** Check less when it's quiet: how drowsy Muninn is now (0 awake, 1 after [DROWSY_MS], 2 after twice that). */
    fun drowsy(i: Inputs): Int = when {
        !i.adaptive || i.plugged || i.kidsTv -> 0
        i.idleMs >= 2 * DROWSY_MS -> 2
        i.idleMs >= DROWSY_MS -> 1
        else -> 0
    }

    /** How low the battery is for saving: 0 fine (or charging, or switched off), 1 below [LOW_BATTERY], 2 below [VERY_LOW]. */
    fun low(i: Inputs): Int = when {
        !i.lowBatterySave || i.plugged -> 0
        i.battery < VERY_LOW -> 2
        i.battery < LOW_BATTERY -> 1
        else -> 0
    }

    /** How often the background inbox check runs now: [checkEveryMinutes], stretched when it's been quiet a while. */
    fun everyMinutes(i: Inputs): Long {
        val c = effective(i)
        val base = checkEveryMinutes(c)
        if (c != Check.QUARTER) return base
        return when (drowsy(i)) { 2 -> 60; 1 -> 30; else -> base }
    }

    /**
     * Quiet hours keep the connection instead of their own mode: "Right away" is chosen and someone is on the
     * "Always right away" list, so their messages must still arrive at once.
     */
    fun quietHeld(i: Inputs): Boolean = i.quiet != null && i.rightAway && i.check == Check.LIVE

    /**
     * The mode in use: the person's choice, or the quiet hours mode while they last, whichever checks least.
     * A low battery never changes it (it checks the one connection less often instead).
     */
    fun effective(i: Inputs): Check {
        if (manual(i)) return Check.OPEN
        val q = i.quiet
        if (q == null || i.kidDevice || quietHeld(i)) return i.check
        return if (q.ordinal > i.check.ordinal) q else i.check
    }

    /** Quiet hours from [from] to [to] (hours of the day; across midnight when [to] is earlier) include [hour]. */
    /** Quiet hours from [from] to [to] (hours of the day; across midnight when [to] is earlier) include [hour]. */
    fun inQuiet(from: Int, to: Int, hour: Int): Boolean = when {
        from == to -> false
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }

    /** Why [effective] isn't the person's own choice: "quiet", else "". */
    fun because(i: Inputs): String = if (!manual(i) && effective(i) != i.check) "quiet" else ""

    /**
     * How often the background inbox job runs (0: never). Quiet hours that use "Only when open" still wake Muninn
     * hourly, only to see whether they've ended; Extreme battery saver never runs it.
     */
    fun jobMinutes(i: Inputs): Long = when {
        manual(i) -> 0L
        effective(i) == Check.OPEN && i.check != Check.OPEN -> 60L
        else -> everyMinutes(i)
    }

    /**
     * [relays]: how many relay sockets stay open (null: all of them, 0: none). [pingMs]: how often
     * they are checked. [radio]: Bluetooth LE and Nearby. [wifi]: Wi-Fi discovery.
     */
    data class Plan(val why: Why, val relays: Int?, val pingMs: Long, val radio: Radio, val wifi: Radio, val because: String = "") {
        val full: Boolean get() = relays == null
        val label: String get() = label(why) + when (because) {
            "battery" -> " (the battery is low)"; "quiet" -> " (quiet hours)"
            "idle30" -> ". It's been quiet, so every 30 minutes for now"; "idle60" -> ". It's been quiet, so every hour for now"
            "lowPing" -> ". The battery is low, so the connection is checked every ${LONG_PING_MS / 60_000} minutes"
            "idlePing" -> ". It's been quiet, so the connection is checked every ${LONG_PING_MS / 60_000} minutes for now"
            "quietHeld" -> ". Quiet hours: only people on your Always right away list ring"
            else -> ""
        }
    }

    const val FAST_PING_MS = 30_000L
    /**
     * The one light connection in the background is checked this often: just under the 5 to 10 minutes after
     * which many mobile networks forget a quiet connection. Android may run it up to [PING_WINDOW_MS] early to
     * batch it with other apps' wake-ups.
     */
    const val AWAY_PING_MS = 300_000L
    const val PING_WINDOW_MS = 60_000L
    /** A very low battery or a long quiet spell: the one connection is checked this often instead of being dropped. */
    const val LONG_PING_MS = 600_000L
    /** Nothing arrived and Muninn not opened for this long: check less (see [drowsy]). */
    const val DROWSY_MS = 3 * 3_600_000L
    /** A socket that hasn't answered a check within this long is dead and is replaced at once. */
    const val ANSWER_MS = 10_000L
    /**
     * In the background: one relay socket, not two. Senders post to every relay, but a relay may not take or keep it (and
     * a sender stops once two have), so the others are still read when catching up and by the inbox check (1.47).
     */
    const val AWAY_RELAYS = 1
    /** Before 1.47, open on screen kept the healthiest three; now it keeps every relay. Kept for the battery ranking. */
    const val OPEN_RELAYS = 3
    /** The conversation window: this long after a message is sent or received, every relay and a quick check. */
    const val TALK_MS = 5 * 60_000L
    /** The quick check in a conversation: on an alarm too, so it still runs (and the window still ends) while the phone sleeps. */
    const val TALK_PING_MS = 60_000L
    /** Low battery: below this, close-by scanning rests; below [VERY_LOW], the connection is checked less. */
    const val LOW_BATTERY = 30
    const val VERY_LOW = 15
    /** Bluetooth and Nearby with the screen off: look for this long, then rest until the period is over. */
    const val DUTY_ON_MS = 15_000L
    const val DUTY_PERIOD_MS = 120_000L
    /** Wait this long before saving more, so a quick glance at the phone doesn't drop and redo connections. */
    const val GRACE_MS = 20_000L
    /** A relay not tried yet counts as this slow to connect. */
    const val UNKNOWN_CONNECT_MS = 5_000L

    fun plan(i: Inputs): Plan {
        val wifiFull = if (i.onWifi) Radio.FULL else Radio.OFF
        // Wi-Fi discovery: fully while sharing files; while open otherwise slowly (Nearby chat and contacts on the
        // Wi-Fi still show up); in the background only for a transfer or Direct connect.
        val wifiOpen = if (!i.onWifi) Radio.OFF else if (i.sharing) Radio.FULL else Radio.LOW
        val wifiAway = if (i.onWifi && i.sharing) Radio.FULL else Radio.OFF
        fun full(w: Why) = Plan(w, null, FAST_PING_MS, Radio.FULL, wifiFull)
        val check = effective(i)
        val why = because(i)
        // Close-by scanning in the background: only when switched on, and never with the battery low.
        val scan = i.closeBy && low(i) == 0
        val (ping, pingWhy) = awayPing(i)
        return when {
            i.inCall -> full(Why.CALL)
            // A voice room: every relay checked quickly so the sound keeps flowing; with the screen off, Bluetooth and
            // Wi-Fi discovery rest as they would without the room (rooms use neither).
            i.inRoom -> if (i.screenOn) full(Why.ROOM) else Plan(Why.ROOM, null, FAST_PING_MS, if (scan) Radio.LOW else Radio.OFF, wifiAway)
            i.kidsTv -> full(Why.KIDS_TV)
            // 1.47 (message RCA): every relay while open, as the label says. With three, a message stored only on the other
            // two waited until the next catch-up.
            i.foreground -> Plan(Why.OPEN, null, FAST_PING_MS, Radio.FULL, wifiOpen)
            // Fully manual: nothing at all until Muninn is opened again, charging or not, sharing or not.
            manual(i) -> Plan(Why.EXTREME, 0, AWAY_PING_MS, Radio.OFF, Radio.OFF)
            // Nothing in the background, not even on the charger: Muninn catches up when it's opened.
            check == Check.OPEN -> Plan(Why.ONLY_OPEN, 0, AWAY_PING_MS, Radio.OFF, Radio.OFF, why)
            i.alwaysFull && check == Check.LIVE -> full(Why.ALWAYS)
            i.plugged -> full(Why.PLUGGED)
            // No live connections between the scheduled inbox checks; calls that come in between show as missed.
            check == Check.QUARTER -> Plan(Why.EVERY_15, 0, AWAY_PING_MS, Radio.OFF, wifiAway,
                why.ifEmpty { when (drowsy(i)) { 2 -> "idle60"; 1 -> "idle30"; else -> "" } })
            check == Check.HOUR -> Plan(Why.EVERY_HOUR, 0, AWAY_PING_MS, Radio.OFF, wifiAway, why)
            i.doze && !i.unrestricted -> Plan(Why.DOZE, 0, AWAY_PING_MS, Radio.OFF, Radio.OFF)
            // Just talking: every relay, checked quickly, so the reply comes at once. The radios stay as they were.
            i.talking -> Plan(Why.TALKING, null, TALK_PING_MS, if (scan) Radio.LOW else Radio.OFF, wifiAway)
            // Calls must still ring: the same one connection as with the screen off.
            i.batterySaver -> Plan(Why.BATTERY_SAVER, AWAY_RELAYS, ping, if (scan) Radio.LOW else Radio.OFF, wifiAway, pingWhy)
            !i.screenOn -> Plan(Why.SCREEN_OFF, AWAY_RELAYS, ping, if (scan) Radio.LOW else Radio.OFF, wifiAway, pingWhy)
            else -> Plan(Why.BACKGROUND, AWAY_RELAYS, ping, if (scan) Radio.FULL else Radio.OFF, wifiAway, pingWhy)
        }
    }

    /**
     * How often the one connection is checked in the background, and why it's longer than usual. Anyone on the
     * "Always right away" list keeps it at [AWAY_PING_MS]: a longer check could leave a connection the network
     * has forgotten unnoticed for minutes.
     */
    fun awayPing(i: Inputs): Pair<Long, String> = when {
        quietHeld(i) -> AWAY_PING_MS to "quietHeld"
        i.rightAway -> AWAY_PING_MS to ""
        low(i) >= 2 -> LONG_PING_MS to "lowPing"
        drowsy(i) > 0 -> LONG_PING_MS to "idlePing"
        else -> AWAY_PING_MS to ""
    }

    /** What happens once the screen goes off: the line Settings shows (Settings itself is always "open"). */
    fun whenAway(i: Inputs): Plan = plan(i.copy(foreground = false, screenOn = false, inCall = false, inRoom = false, talking = false))

    fun label(w: Why): String = when (w) {
        Why.OPEN -> "Fully connected while Muninn is open"
        Why.CALL -> "In a call: fully connected"
        Why.ROOM -> "In a voice room: connected for the sound, lighter with the screen off"
        Why.KIDS_TV -> "Kids' Device: always fully connected so calls ring at once"
        Why.PLUGGED -> "Charging: fully connected"
        Why.ALWAYS -> "Always fully connected"
        Why.TALKING -> "In a conversation: fully connected for a few minutes so replies arrive at once"
        Why.BACKGROUND -> "Saving battery while Muninn is in the background"
        Why.SCREEN_OFF -> "Saving battery while the screen is off"
        Why.BATTERY_SAVER -> "Android's Battery Saver is on: saving battery, messages and calls still arrive"
        Why.DOZE -> "Phone is asleep and Android is pausing Muninn: checking for messages about every 15 minutes"
        Why.EVERY_15 -> "Checking for messages about every 15 minutes. Calls in between show as missed"
        Why.EVERY_HOUR -> "Checking for messages about once an hour. Calls in between show as missed"
        Why.ONLY_OPEN -> "Not checking until you open Muninn. No notifications or calls until then"
        Why.EXTREME -> "Extreme battery saver: nothing runs in the background. No notifications. Messages arrive when you open Muninn or refresh. Calls ring only while Muninn is open"
    }

    /** How a message that arrived is shown: with sound and on screen, quietly in the list, or not until Muninn opens. */
    enum class Alert { LOUD, QUIET, NONE }

    /**
     * [fromRightAway]: the chat is on the "Always right away" list. While quiet hours are held open for that list
     * ([quietHeld]), everyone else's messages arrive without a sound ("Every hour") or wait for Muninn to open
     * ("Only when open"), as they would have with the quiet hours mode itself.
     */
    fun alert(i: Inputs, fromRightAway: Boolean): Alert = when {
        fromRightAway || i.kidsTv || i.kidDevice || !quietHeld(i) -> Alert.LOUD
        i.quiet == Check.OPEN -> Alert.NONE
        else -> Alert.QUIET
    }

    /**
     * The "Always right away" list: the Family folder's chats ([family]) plus the ones [added], minus the ones
     * [removed]. Someone added and then taken off again is only in [removed].
     */
    fun rightAway(family: Collection<String>, added: Collection<String>, removed: Collection<String>): Set<String> =
        (family.toSet() + added) - removed.toSet()

    /** The new added and removed sets after putting [key] on the list ([on]) or taking it off. */
    fun setRightAway(key: String, on: Boolean, inFamily: Boolean, added: Set<String>, removed: Set<String>): Pair<Set<String>, Set<String>> =
        if (on) (if (inFamily) added - key else added + key) to (removed - key)
        else (added - key) to (if (inFamily) removed + key else removed - key)

    /** The conversation window is open: a message went or came [sinceMs] ago (negative: never). */
    fun talking(sinceMs: Long): Boolean = sinceMs in 0 until TALK_MS

    /**
     * A rough share of the battery (percent) the connection used: [fullMs] fully connected, [lightMs] on the one
     * light connection, [wakeups] times it woke the phone to check, [reconnects] connections made again. Based on
     * a typical 4,500 mAh phone; Android's own battery screen has the real number.
     */
    fun costPercent(fullMs: Long, lightMs: Long, wakeups: Int, reconnects: Int): Double =
        fullMs / 3_600_000.0 * FULL_PER_HOUR + lightMs / 3_600_000.0 * LIGHT_PER_HOUR + wakeups * WAKE_COST + reconnects * RECONNECT_COST

    /** About 1% an hour fully connected; an open but quiet socket costs little; each wake-up keeps the radio on a few seconds. */
    const val FULL_PER_HOUR = 1.0
    const val LIGHT_PER_HOUR = 0.02
    const val WAKE_COST = 0.01
    const val RECONNECT_COST = 0.03

    private fun rank(p: Plan): Int = (p.relays?.coerceAtMost(OPEN_RELAYS) ?: (OPEN_RELAYS + 1)) * 10 + (2 - p.radio.ordinal) * 3 + (2 - p.wifi.ordinal)

    /** True when [to] keeps less going than [from]: applied after [GRACE_MS]; anything more is applied at once. */
    fun saves(from: Plan, to: Plan): Boolean = rank(to) < rank(from)

    /**
     * What Muninn knows about a relay: failed tries in a row, how long it took to connect, open right now, and how
     * often it checks on the phone by itself (pings an hour, each waking the radio).
     */
    data class Health(val failures: Int = 0, val connectMs: Long? = null, val live: Boolean = false, val pingsPerHour: Int = 0)

    /**
     * The [n] relays to keep open (null: all), in the order they are listed. Fewest failures first,
     * then ones already open (no churn), then the fewest pings of their own, then the quickest to connect, then
     * list order.
     */
    fun pick(relays: List<String>, health: Map<String, Health>, n: Int?): List<String> {
        if (n == null || n >= relays.size) return relays
        if (n <= 0) return emptyList()
        val chosen = relays.withIndex().sortedWith(compareBy(
            { health[it.value]?.failures ?: 0 },
            { if (health[it.value]?.live == true) 0 else 1 },
            { health[it.value]?.pingsPerHour ?: 0 },
            { health[it.value]?.connectMs ?: UNKNOWN_CONNECT_MS },
            { it.index },
        )).take(n).map { it.value }.toSet()
        return relays.filter { it in chosen }
    }

    /**
     * Failed tries in a row before a relay kept open while saving battery gives its place to the next healthiest:
     * at once when it's the only one (nothing else would bring messages meanwhile), else after two.
     */
    fun swapAfter(keep: Int?): Int = if (keep != null && keep <= 1) 1 else 2

    /** A socket asked at [probedAt] that hasn't said anything since is gone (0: never asked). */
    fun silent(probedAt: Long, heardAt: Long): Boolean = probedAt > 0 && heardAt < probedAt

    /**
     * After one check: a socket opened before it ([openedAt]) that hasn't answered within [ANSWER_MS]
     * is replaced right away (a single missed check, not two).
     */
    fun dead(probedAt: Long, heardAt: Long, openedAt: Long, now: Long): Boolean =
        silent(probedAt, heardAt) && openedAt < probedAt && now - probedAt >= ANSWER_MS

    /** Bluetooth and Nearby duty cycle: how long to stay in the current half (looking or resting). */
    fun dutyNext(looking: Boolean): Long = if (looking) DUTY_ON_MS else DUTY_PERIOD_MS - DUTY_ON_MS
}
