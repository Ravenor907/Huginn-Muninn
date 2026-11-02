package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.PowerLogic
import io.github.ravenor907.muninn.net.PowerLogic.Check
import io.github.ravenor907.muninn.net.PowerLogic.Health
import io.github.ravenor907.muninn.net.PowerLogic.Inputs
import io.github.ravenor907.muninn.net.PowerLogic.Radio
import io.github.ravenor907.muninn.net.PowerLogic.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLogicTest {
    private val phoneAway = Inputs(foreground = false, screenOn = false, onWifi = true)

    @Test
    fun openOnScreenIsConnectedThroughTheHealthiestThree() {
        val p = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true))
        assertEquals(Why.OPEN, p.why)
        assertEquals(PowerLogic.OPEN_RELAYS, p.relays)
        assertEquals(PowerLogic.FAST_PING_MS, p.pingMs)
        assertEquals(Radio.FULL, p.radio)
        // Wi-Fi discovery is slow unless files are being shared.
        assertEquals(Radio.LOW, p.wifi)
        assertEquals(Radio.FULL, PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true, sharing = true)).wifi)
    }

    @Test
    fun screenOffKeepsOneLightConnection() {
        val p = PowerLogic.plan(phoneAway)
        assertEquals(Why.SCREEN_OFF, p.why)
        // One relay socket, not two, checked about every 5 minutes (just under typical mobile network timeouts).
        assertEquals(1, p.relays)
        assertEquals(300_000L, p.pingMs)
        assertTrue(PowerLogic.PING_WINDOW_MS in 1..PowerLogic.AWAY_PING_MS / 4)
        // Close-by scanning and Wi-Fi discovery rest unless switched on or sharing files.
        assertEquals(Radio.OFF, p.radio)
        assertEquals(Radio.OFF, p.wifi)
        assertEquals(Radio.LOW, PowerLogic.plan(phoneAway.copy(closeBy = true)).radio)
        assertEquals(Radio.FULL, PowerLogic.plan(phoneAway.copy(sharing = true)).wifi)
        assertEquals("Saving battery while the screen is off", p.label)
    }

    @Test
    fun backgroundWithScreenOnScansOnlyWhenAsked() {
        val p = PowerLogic.plan(phoneAway.copy(screenOn = true))
        assertEquals(Why.BACKGROUND, p.why)
        assertEquals(1, p.relays)
        assertEquals(Radio.OFF, p.radio)
        assertEquals(Radio.FULL, PowerLogic.plan(phoneAway.copy(screenOn = true, closeBy = true)).radio)
    }

    @Test
    fun noWifiDiscoveryOffWifi() {
        assertEquals(Radio.OFF, PowerLogic.plan(phoneAway.copy(onWifi = false)).wifi)
        assertEquals(Radio.OFF, PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = false)).wifi)
    }

    @Test
    fun batterySaverSavesLikeTheScreenOffButCallsStillRing() {
        for (screen in listOf(false, true)) for (wifi in listOf(false, true)) {
            // Screen on with Muninn in the background, or screen off: one relay, the 5-minute check, radios low.
            val p = PowerLogic.plan(phoneAway.copy(screenOn = screen, onWifi = wifi, batterySaver = true))
            assertEquals(Why.BATTERY_SAVER, p.why)
            assertEquals(PowerLogic.AWAY_RELAYS, p.relays)
            assertEquals(PowerLogic.AWAY_PING_MS, p.pingMs)
            assertEquals(Radio.OFF, p.radio)
            assertEquals(Radio.OFF, p.wifi)
            assertEquals(Radio.LOW, PowerLogic.plan(phoneAway.copy(screenOn = screen, onWifi = wifi, batterySaver = true, closeBy = true)).radio)
        }
        assertEquals(1, PowerLogic.plan(phoneAway.copy(batterySaver = true, unrestricted = true)).relays)
        // Battery Saver and Doze without the exemption: Android cuts the network, so no live sockets.
        assertEquals(Why.DOZE, PowerLogic.plan(phoneAway.copy(batterySaver = true, doze = true)).why)
        assertEquals(0, PowerLogic.plan(phoneAway.copy(batterySaver = true, doze = true)).relays)
        assertEquals(1, PowerLogic.plan(phoneAway.copy(batterySaver = true, doze = true, unrestricted = true)).relays)
        // Open on screen it still works normally.
        assertEquals(PowerLogic.OPEN_RELAYS, PowerLogic.plan(Inputs(foreground = true, screenOn = true, batterySaver = true)).relays)
    }

    @Test
    fun dozeDropsConnectionsOnlyWhenAndroidWouldCutThem() {
        assertEquals(Why.DOZE, PowerLogic.plan(phoneAway.copy(doze = true)).why)
        assertEquals(0, PowerLogic.plan(phoneAway.copy(doze = true)).relays)
        val allowed = PowerLogic.plan(phoneAway.copy(doze = true, unrestricted = true))
        assertEquals(Why.SCREEN_OFF, allowed.why)
        assertEquals(1, allowed.relays)
    }

    @Test
    fun callsKidsTvChargingAndSwitchOffStayFull() {
        val worst = phoneAway.copy(batterySaver = true, doze = true)
        assertEquals(Why.CALL, PowerLogic.plan(worst.copy(inCall = true)).why)
        assertEquals(Why.KIDS_TV, PowerLogic.plan(worst.copy(kidsTv = true)).why)
        assertEquals(Why.ALWAYS, PowerLogic.plan(worst.copy(alwaysFull = true)).why)
        assertEquals(Why.PLUGGED, PowerLogic.plan(phoneAway.copy(plugged = true)).why)
        for (i in listOf(worst.copy(inCall = true), worst.copy(kidsTv = true), worst.copy(alwaysFull = true), phoneAway.copy(plugged = true))) {
            val p = PowerLogic.plan(i)
            assertTrue(p.full)
            assertEquals(Radio.FULL, p.radio)
            assertEquals(PowerLogic.FAST_PING_MS, p.pingMs)
        }
    }

    @Test
    fun callsStillRingInNormalBackground() {
        // Battery Saver or not, at least one relay socket is always live in the background (unless Doze cuts the network).
        for (screen in listOf(true, false)) for (wifi in listOf(true, false)) for (doze in listOf(false, true)) for (saver in listOf(false, true)) {
            val p = PowerLogic.plan(Inputs(screenOn = screen, onWifi = wifi, doze = doze && !screen, unrestricted = true, batterySaver = saver))
            assertTrue(p.relays == null || p.relays!! >= 1)
        }
        for (saver in listOf(false, true)) assertTrue(PowerLogic.plan(Inputs(screenOn = true, batterySaver = saver)).relays!! >= 1)
    }

    @Test
    fun settingsLineDescribesTheScreenOff() {
        val open = Inputs(foreground = true, screenOn = true)
        assertEquals("Saving battery while the screen is off", PowerLogic.whenAway(open).label)
        assertEquals(Why.ALWAYS, PowerLogic.whenAway(open.copy(alwaysFull = true)).why)
        assertEquals(Why.BATTERY_SAVER, PowerLogic.whenAway(open.copy(batterySaver = true)).why)
        assertEquals(Why.KIDS_TV, PowerLogic.whenAway(open.copy(kidsTv = true)).why)
        // A call ending is what matters for the line, not the call itself.
        assertEquals(Why.SCREEN_OFF, PowerLogic.whenAway(open.copy(inCall = true)).why)
    }

    @Test
    fun savingWaitsButWakingUpIsImmediate() {
        val full = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true))
        val off = PowerLogic.plan(phoneAway)
        val saver = PowerLogic.plan(phoneAway.copy(batterySaver = true))
        val doze = PowerLogic.plan(phoneAway.copy(doze = true))
        val bg = PowerLogic.plan(phoneAway.copy(screenOn = true))
        assertTrue(PowerLogic.saves(full, off))
        assertTrue(PowerLogic.saves(full, bg))
        assertFalse(PowerLogic.saves(off, full))
        assertFalse(PowerLogic.saves(off, off))
        // Nothing scanning and no Wi-Fi discovery: the screen off, Battery Saver and the background cost the same.
        assertFalse(PowerLogic.saves(off, saver)); assertFalse(PowerLogic.saves(saver, off))
        assertFalse(PowerLogic.saves(bg, off)); assertFalse(PowerLogic.saves(off, bg))
        // With close-by scanning on, the screen being on scans more than off.
        val bgScan = PowerLogic.plan(phoneAway.copy(screenOn = true, closeBy = true))
        val offScan = PowerLogic.plan(phoneAway.copy(closeBy = true))
        assertTrue(PowerLogic.saves(bgScan, offScan))
        assertFalse(PowerLogic.saves(offScan, bgScan))
        assertTrue(PowerLogic.saves(saver, doze))
        assertFalse(PowerLogic.saves(doze, saver))
    }

    private val relays = listOf("wss://a", "wss://b", "wss://c", "wss://d", "wss://e")

    @Test
    fun picksAllOrNone() {
        assertEquals(relays, PowerLogic.pick(relays, emptyMap(), null))
        assertEquals(relays, PowerLogic.pick(relays, emptyMap(), 9))
        assertEquals(emptyList<String>(), PowerLogic.pick(relays, emptyMap(), 0))
    }

    @Test
    fun picksTheHealthiestTwo() {
        // Nothing known yet: the first two in the list.
        assertEquals(listOf("wss://a", "wss://b"), PowerLogic.pick(relays, emptyMap(), 2))
        val h = mapOf(
            "wss://a" to Health(failures = 3),
            "wss://b" to Health(connectMs = 900, live = true),
            "wss://c" to Health(connectMs = 200, live = true),
            "wss://d" to Health(connectMs = 100),
            "wss://e" to Health(failures = 1, connectMs = 50, live = true),
        )
        // Fewest failures, then already open, then quickest; kept in list order.
        assertEquals(listOf("wss://b", "wss://c"), PowerLogic.pick(relays, h, 2))
        val h2 = h + ("wss://b" to Health(failures = 2, live = true))
        assertEquals(listOf("wss://c", "wss://d"), PowerLogic.pick(relays, h2, 2))
    }

    @Test
    fun silentSocketsAreFound() {
        assertFalse(PowerLogic.silent(0, 0))           // never asked
        assertTrue(PowerLogic.silent(1_000, 500))      // asked, no answer since
        assertFalse(PowerLogic.silent(1_000, 1_200))   // answered
    }

    @Test
    fun oneMissedCheckReplacesASocket() {
        val a = PowerLogic.ANSWER_MS
        assertTrue(a <= 15_000L)
        // Asked at 1000, opened before, no answer: dead once the answer time has passed, not a whole period later.
        assertTrue(PowerLogic.dead(probedAt = 1_000, heardAt = 500, openedAt = 0, now = 1_000 + a))
        assertFalse(PowerLogic.dead(probedAt = 1_000, heardAt = 500, openedAt = 0, now = 1_000 + a - 1))   // still waiting
        assertFalse(PowerLogic.dead(probedAt = 1_000, heardAt = 1_100, openedAt = 0, now = 1_000 + a))    // answered
        assertFalse(PowerLogic.dead(probedAt = 1_000, heardAt = 0, openedAt = 1_500, now = 1_000 + a))    // opened after the check
        assertFalse(PowerLogic.dead(probedAt = 0, heardAt = 0, openedAt = 0, now = a))                    // never asked
        // A dead socket is found within one check period plus the answer time: under 5 minutes and a bit.
        assertTrue(PowerLogic.AWAY_PING_MS + a <= 310_000L)
        // A very low battery or a quiet spell: still found within about 10 minutes, never an hour.
        assertTrue(PowerLogic.LONG_PING_MS + a <= 610_000L)
    }

    @Test
    fun dutyCycleAddsUpToThePeriod() {
        assertEquals(PowerLogic.DUTY_ON_MS, PowerLogic.dutyNext(true))
        assertEquals(PowerLogic.DUTY_PERIOD_MS, PowerLogic.dutyNext(true) + PowerLogic.dutyNext(false))
        assertTrue(PowerLogic.DUTY_ON_MS * 4 <= PowerLogic.DUTY_PERIOD_MS)
    }

    @Test
    fun scheduledChecksKeepNothingLiveWhileClosed() {
        for ((c, why) in listOf(Check.QUARTER to Why.EVERY_15, Check.HOUR to Why.EVERY_HOUR)) {
            val p = PowerLogic.plan(phoneAway.copy(check = c))
            assertEquals(why, p.why)
            assertEquals(0, p.relays)
            assertEquals(Radio.OFF, p.radio)
            assertEquals(Radio.OFF, p.wifi)
            // Open, in a call or charging: fully connected as always.
            assertEquals(Why.OPEN, PowerLogic.plan(phoneAway.copy(check = c, foreground = true, screenOn = true)).why)
            assertEquals(Why.CALL, PowerLogic.plan(phoneAway.copy(check = c, inCall = true)).why)
            assertEquals(Why.PLUGGED, PowerLogic.plan(phoneAway.copy(check = c, plugged = true)).why)
            // Maximum speed belongs to "Right away" only.
            assertEquals(why, PowerLogic.plan(phoneAway.copy(check = c, alwaysFull = true)).why)
        }
        assertEquals(15L, PowerLogic.checkEveryMinutes(Check.QUARTER))
        assertEquals(60L, PowerLogic.checkEveryMinutes(Check.HOUR))
    }

    @Test
    fun onlyWhenOpenIsQuietEvenOnTheCharger() {
        val p = PowerLogic.plan(phoneAway.copy(check = Check.OPEN, plugged = true, alwaysFull = true))
        assertEquals(Why.ONLY_OPEN, p.why)
        assertEquals(0, p.relays)
        assertEquals(0L, PowerLogic.checkEveryMinutes(Check.OPEN))
        // Opened, or a call it started: fully connected.
        assertEquals(Why.OPEN, PowerLogic.plan(phoneAway.copy(check = Check.OPEN, foreground = true, screenOn = true)).why)
        assertEquals(Why.CALL, PowerLogic.plan(phoneAway.copy(check = Check.OPEN, inCall = true)).why)
    }

    @Test
    fun rightAwayIsUnchangedAndKidsTvStaysLive() {
        assertEquals(Why.SCREEN_OFF, PowerLogic.plan(phoneAway.copy(check = Check.LIVE)).why)
        assertEquals(Why.ALWAYS, PowerLogic.plan(phoneAway.copy(check = Check.LIVE, alwaysFull = true)).why)
        for (c in Check.entries) assertEquals(Why.KIDS_TV, PowerLogic.plan(phoneAway.copy(check = c, kidsTv = true)).why)
        assertEquals(Check.HOUR, Check.of("hour"))
        assertNull(Check.of("weekly"))
    }

    @Test
    fun aLowBatteryNeverDropsTheConnection() {
        val away = phoneAway.copy(check = Check.LIVE, closeBy = true)
        // The mode stays "Right away" at any level: no hourly (or 15-minute) floor.
        for (b in listOf(100, 30, 29, 15, 14, 5, 1)) {
            assertEquals(Check.LIVE, PowerLogic.effective(away.copy(battery = b)))
            val p = PowerLogic.plan(away.copy(battery = b))
            assertEquals(Why.SCREEN_OFF, p.why)
            assertEquals(1, p.relays)
        }
        // Below 30%: close-by scanning rests. Below 15%: the connection is checked every 10 minutes.
        assertEquals(Radio.LOW, PowerLogic.plan(away.copy(battery = 30)).radio)
        assertEquals(Radio.OFF, PowerLogic.plan(away.copy(battery = 29)).radio)
        assertEquals(PowerLogic.AWAY_PING_MS, PowerLogic.plan(away.copy(battery = 15)).pingMs)
        val low = PowerLogic.plan(away.copy(battery = 14))
        assertEquals(PowerLogic.LONG_PING_MS, low.pingMs)
        assertTrue(low.label.contains("checked every 10 minutes"))
        assertEquals(1, PowerLogic.low(away.copy(battery = 29)))
        assertEquals(2, PowerLogic.low(away.copy(battery = 14)))
        // Charging, or switched off: nothing saved for the battery.
        assertEquals(0, PowerLogic.low(away.copy(battery = 5, plugged = true)))
        assertEquals(0, PowerLogic.low(away.copy(battery = 5, lowBatterySave = false)))
        assertEquals(PowerLogic.AWAY_PING_MS, PowerLogic.plan(away.copy(battery = 5, lowBatterySave = false)).pingMs)
        // Someone on the Always right away list: the 5-minute check stays, however low the battery.
        assertEquals(PowerLogic.AWAY_PING_MS, PowerLogic.plan(away.copy(battery = 5, rightAway = true)).pingMs)
        // The person's own choice still wins when it saves more.
        assertEquals(Check.OPEN, PowerLogic.effective(away.copy(battery = 20, check = Check.OPEN)))
        assertEquals(Why.EVERY_15, PowerLogic.plan(away.copy(battery = 20, check = Check.QUARTER)).why)
        // Open or in a call it still connects fully.
        assertEquals(Why.OPEN, PowerLogic.plan(away.copy(battery = 5, foreground = true, screenOn = true)).why)
        assertEquals(Why.CALL, PowerLogic.plan(away.copy(battery = 5, inCall = true)).why)
    }

    @Test
    fun noHourlyFloorWithRightAway() {
        val h = 3_600_000L
        // Any battery, any quiet spell, Battery Saver or not: one live connection checked at most every 10 minutes.
        for (b in listOf(100, 25, 10)) for (idle in listOf(0L, 4 * h, 12 * h)) for (saver in listOf(false, true)) for (ra in listOf(false, true)) {
            val p = PowerLogic.plan(phoneAway.copy(battery = b, idleMs = idle, batterySaver = saver, rightAway = ra))
            assertEquals(1, p.relays)
            assertTrue(p.pingMs <= PowerLogic.LONG_PING_MS)
            if (ra) assertEquals(PowerLogic.AWAY_PING_MS, p.pingMs)
            assertEquals(15L, PowerLogic.everyMinutes(phoneAway.copy(battery = b, idleMs = idle, rightAway = ra)))
        }
    }

    @Test
    fun quietHoursUseTheirModeWhileTheyLast() {
        assertTrue(PowerLogic.inQuiet(23, 7, 23))
        assertTrue(PowerLogic.inQuiet(23, 7, 3))
        assertTrue(!PowerLogic.inQuiet(23, 7, 7))
        assertTrue(!PowerLogic.inQuiet(23, 7, 12))
        assertTrue(PowerLogic.inQuiet(13, 15, 14))
        assertTrue(!PowerLogic.inQuiet(9, 9, 9))
        val p = PowerLogic.plan(phoneAway.copy(quiet = Check.OPEN, plugged = true))
        assertEquals(Why.ONLY_OPEN, p.why)
        assertTrue(p.label.endsWith("(quiet hours)"))
        assertEquals(Why.EVERY_HOUR, PowerLogic.plan(phoneAway.copy(quiet = Check.HOUR)).why)
        // The battery being low too: whichever saves most.
        assertEquals(Check.OPEN, PowerLogic.effective(phoneAway.copy(quiet = Check.OPEN, battery = 10)))
        // The battery being low doesn't save more than quiet hours do.
        assertEquals(Check.HOUR, PowerLogic.effective(phoneAway.copy(quiet = Check.HOUR, battery = 10)))
        // A Kids' TV ignores both.
        assertEquals(Why.KIDS_TV, PowerLogic.plan(phoneAway.copy(quiet = Check.OPEN, battery = 5, kidsTv = true)).why)
    }

    @Test
    fun quietHoursKeepTheConnectionForTheRightAwayList() {
        for (q in listOf(Check.HOUR, Check.OPEN)) {
            val i = phoneAway.copy(quiet = q, rightAway = true)
            assertTrue(PowerLogic.quietHeld(i))
            assertEquals(Check.LIVE, PowerLogic.effective(i))
            val p = PowerLogic.plan(i)
            assertEquals(Why.SCREEN_OFF, p.why)
            assertEquals(1, p.relays)
            assertEquals(PowerLogic.AWAY_PING_MS, p.pingMs)
            assertTrue(p.label.contains("only people on your Always right away list ring"))
            // Their messages ring; everyone else's arrive quietly ("Every hour") or wait for Muninn ("Only when open").
            assertEquals(PowerLogic.Alert.LOUD, PowerLogic.alert(i, fromRightAway = true))
            assertEquals(if (q == Check.OPEN) PowerLogic.Alert.NONE else PowerLogic.Alert.QUIET, PowerLogic.alert(i, fromRightAway = false))
            // A Kids' TV always rings.
            assertEquals(PowerLogic.Alert.LOUD, PowerLogic.alert(i.copy(kidsTv = true), fromRightAway = false))
        }
        // Nobody on the list: quiet hours use their own mode, and whatever arrives notifies as usual.
        val none = phoneAway.copy(quiet = Check.HOUR)
        assertFalse(PowerLogic.quietHeld(none))
        assertEquals(Why.EVERY_HOUR, PowerLogic.plan(none).why)
        assertEquals(PowerLogic.Alert.LOUD, PowerLogic.alert(none, fromRightAway = false))
        // Outside quiet hours everything rings.
        assertEquals(PowerLogic.Alert.LOUD, PowerLogic.alert(phoneAway.copy(rightAway = true), fromRightAway = false))
        // The person chose a scheduled check: kept as it is, list or not.
        val quarter = phoneAway.copy(check = Check.QUARTER, quiet = Check.HOUR, rightAway = true)
        assertFalse(PowerLogic.quietHeld(quarter))
        assertEquals(Check.HOUR, PowerLogic.effective(quarter))
        assertEquals(Why.EVERY_HOUR, PowerLogic.plan(quarter).why)
    }

    @Test
    fun savingMoreStillWaitsAMoment() {
        val open = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true))
        val away = PowerLogic.plan(phoneAway)
        assertTrue(PowerLogic.saves(open, away))
        assertTrue(!PowerLogic.saves(away, open))
        // Fully connected (every relay) ranks above open on screen (three).
        assertTrue(PowerLogic.saves(PowerLogic.plan(phoneAway.copy(plugged = true)), open))
    }

    @Test
    fun checksLessWhenItsBeenQuiet() {
        val h = 3_600_000L
        // Right away: still one relay (calls ring), checked every 10 minutes instead of 5 after 3 quiet hours.
        assertEquals(PowerLogic.AWAY_PING_MS, PowerLogic.plan(phoneAway.copy(idleMs = 2 * h)).pingMs)
        val drowsy = PowerLogic.plan(phoneAway.copy(idleMs = 3 * h))
        assertEquals(1, drowsy.relays)
        assertEquals(PowerLogic.LONG_PING_MS, drowsy.pingMs)
        assertTrue(drowsy.label.contains("for now"))
        // Never longer: not an hour, however long it's been.
        assertEquals(PowerLogic.LONG_PING_MS, PowerLogic.plan(phoneAway.copy(idleMs = 30 * h)).pingMs)
        // The Always right away list keeps the 5-minute check.
        assertEquals(PowerLogic.AWAY_PING_MS, PowerLogic.plan(phoneAway.copy(idleMs = 30 * h, rightAway = true)).pingMs)
        // Every 15 minutes: every 30 after 3 hours, every hour after 6.
        val quarter = phoneAway.copy(check = Check.QUARTER)
        assertEquals(15L, PowerLogic.everyMinutes(quarter))
        assertEquals(30L, PowerLogic.everyMinutes(quarter.copy(idleMs = 3 * h)))
        assertEquals(60L, PowerLogic.everyMinutes(quarter.copy(idleMs = 6 * h)))
        assertTrue(PowerLogic.plan(quarter.copy(idleMs = 6 * h)).label.contains("every hour for now"))
        // Every hour and Only when open don't change.
        assertEquals(60L, PowerLogic.everyMinutes(phoneAway.copy(check = Check.HOUR, idleMs = 9 * h)))
        assertEquals(0L, PowerLogic.everyMinutes(phoneAway.copy(check = Check.OPEN, idleMs = 9 * h)))
        // Never while charging, on a Kids' TV, or with the switch off.
        assertEquals(15L, PowerLogic.everyMinutes(quarter.copy(idleMs = 9 * h, adaptive = false)))
        assertEquals(0, PowerLogic.drowsy(phoneAway.copy(idleMs = 9 * h, plugged = true)))
        assertEquals(0, PowerLogic.drowsy(phoneAway.copy(idleMs = 9 * h, kidsTv = true)))
        // A low battery doesn't stretch the person's own 15 minutes.
        assertEquals(15L, PowerLogic.everyMinutes(quarter.copy(battery = 10)))
    }

    @Test
    fun aConversationIsFullyConnectedForAFewMinutes() {
        assertTrue(PowerLogic.talking(0))
        assertTrue(PowerLogic.talking(PowerLogic.TALK_MS - 1))
        assertFalse(PowerLogic.talking(PowerLogic.TALK_MS))
        assertFalse(PowerLogic.talking(-1))
        assertEquals(5 * 60_000L, PowerLogic.TALK_MS)
        val p = PowerLogic.plan(phoneAway.copy(talking = true))
        assertEquals(Why.TALKING, p.why)
        assertTrue(p.full)
        // A quick check, on an alarm (longer than the timer-only check) so it still runs while the phone sleeps.
        assertEquals(PowerLogic.TALK_PING_MS, p.pingMs)
        assertTrue(p.pingMs > PowerLogic.FAST_PING_MS && p.pingMs < PowerLogic.AWAY_PING_MS)
        assertEquals(Radio.OFF, p.radio)
        assertEquals(Radio.OFF, p.wifi)
        // With a low battery, Battery Saver or after a quiet spell too.
        for (i in listOf(phoneAway.copy(battery = 5), phoneAway.copy(batterySaver = true), phoneAway.copy(idleMs = 9 * 3_600_000L)))
            assertEquals(Why.TALKING, PowerLogic.plan(i.copy(talking = true)).why)
        // Not when the person chose scheduled checks, nor when Android cuts the network anyway.
        assertEquals(Why.EVERY_15, PowerLogic.plan(phoneAway.copy(talking = true, check = Check.QUARTER)).why)
        assertEquals(Why.DOZE, PowerLogic.plan(phoneAway.copy(talking = true, doze = true)).why)
        // Open, it's the open plan; the Settings line is about after the conversation.
        assertEquals(Why.OPEN, PowerLogic.plan(Inputs(foreground = true, screenOn = true, talking = true)).why)
        assertEquals(Why.SCREEN_OFF, PowerLogic.whenAway(Inputs(foreground = true, screenOn = true, talking = true)).why)
        // When it ends, back to one connection after the short wait.
        assertTrue(PowerLogic.saves(p, PowerLogic.plan(phoneAway)))
        assertFalse(PowerLogic.saves(PowerLogic.plan(phoneAway), p))
    }

    @Test
    fun theRightAwayListStartsAsFamily() {
        val family = listOf("anna", "g:home")
        assertEquals(setOf("anna", "g:home"), PowerLogic.rightAway(family, emptySet(), emptySet()))
        assertEquals(setOf("anna", "g:home", "bo"), PowerLogic.rightAway(family, setOf("bo"), emptySet()))
        assertEquals(setOf("g:home"), PowerLogic.rightAway(family, emptySet(), setOf("anna")))
        // It follows the folder: someone new in Family is on it, someone moved out is off it (unless added).
        assertEquals(setOf("cy"), PowerLogic.rightAway(listOf("cy"), emptySet(), emptySet()))
        // Adding and taking off.
        var a = emptySet<String>(); var r = emptySet<String>()
        PowerLogic.setRightAway("bo", true, inFamily = false, added = a, removed = r).let { a = it.first; r = it.second }
        assertEquals(setOf("bo"), a); assertEquals(emptySet<String>(), r)
        PowerLogic.setRightAway("anna", false, inFamily = true, added = a, removed = r).let { a = it.first; r = it.second }
        assertEquals(setOf("anna"), r)
        assertEquals(setOf("g:home", "bo"), PowerLogic.rightAway(family, a, r))
        PowerLogic.setRightAway("anna", true, inFamily = true, added = a, removed = r).let { a = it.first; r = it.second }
        assertEquals(emptySet<String>(), r)
        assertFalse("anna" in a)   // Family already has her
        PowerLogic.setRightAway("bo", false, inFamily = false, added = a, removed = r).let { a = it.first; r = it.second }
        assertEquals(emptySet<String>(), a); assertEquals(emptySet<String>(), r)
        assertEquals(setOf("anna", "g:home"), PowerLogic.rightAway(family, a, r))
    }

    @Test
    fun aSingleRelayFailsOverAtOnce() {
        assertEquals(1, PowerLogic.swapAfter(1))
        assertEquals(2, PowerLogic.swapAfter(3))
        assertEquals(2, PowerLogic.swapAfter(null))
        // After one failure the healthiest other relay is picked.
        val h = mapOf("wss://a" to Health(failures = 1, live = false), "wss://b" to Health(connectMs = 400))
        assertEquals(listOf("wss://b"), PowerLogic.pick(relays, h, 1))
    }

    @Test
    fun theConnectionsCostIsEstimated() {
        val h = 3_600_000L
        assertEquals(0.0, PowerLogic.costPercent(0, 0, 0, 0), 1e-9)
        // A whole day on the one light connection, waking every 5 minutes: a few percent at most.
        val day = PowerLogic.costPercent(0, 24 * h, (24 * h / PowerLogic.AWAY_PING_MS).toInt(), 10)
        assertTrue(day > 0.5 && day < 5.0)
        // Fully connected costs much more than the light connection, and each wake-up or reconnect adds.
        assertTrue(PowerLogic.costPercent(h, 0, 0, 0) > 10 * PowerLogic.costPercent(0, h, 0, 0))
        assertTrue(PowerLogic.costPercent(0, h, 13, 0) > PowerLogic.costPercent(0, h, 12, 0))
        assertTrue(PowerLogic.costPercent(0, h, 12, 2) > PowerLogic.costPercent(0, h, 12, 1))
    }

    @Test
    fun extremeBatterySaverRunsNothingInTheBackground() {
        val away = phoneAway.copy(extreme = true)
        val p = PowerLogic.plan(away)
        assertEquals(Why.EXTREME, p.why)
        assertEquals(0, p.relays)
        assertEquals(Radio.OFF, p.radio)
        assertEquals(Radio.OFF, p.wifi)
        // Not even on the charger, sharing files, in a conversation or with close-by scanning switched on.
        assertEquals(Why.EXTREME, PowerLogic.plan(away.copy(plugged = true, sharing = true, talking = true, closeBy = true)).why)
        // No inbox job and no Listening service: the mode in use is "Only when open".
        assertEquals(Check.OPEN, PowerLogic.effective(away))
        assertEquals(0L, PowerLogic.jobMinutes(away))
        assertEquals(0L, PowerLogic.jobMinutes(away.copy(quiet = Check.HOUR)))
        assertEquals("", PowerLogic.because(away.copy(quiet = Check.HOUR)))
        assertTrue(PowerLogic.label(Why.EXTREME).contains("No notifications"))
    }

    @Test
    fun extremeBatterySaverStillFetchesWhileOpenAndKeepsACall() {
        val open = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true, extreme = true))
        assertEquals(Why.OPEN, open.why)
        assertEquals(PowerLogic.OPEN_RELAYS, open.relays)
        assertEquals(Why.CALL, PowerLogic.plan(phoneAway.copy(extreme = true, inCall = true)).why)
        // A Kids' TV always listens.
        assertEquals(Why.KIDS_TV, PowerLogic.plan(phoneAway.copy(extreme = true, kidsTv = true)).why)
        assertEquals(Check.LIVE, PowerLogic.effective(phoneAway.copy(extreme = true, kidsTv = true)))
    }

    @Test
    fun leavingExtremeBringsTheUsualConnectionBack() {
        assertEquals(Why.SCREEN_OFF, PowerLogic.plan(phoneAway.copy(extreme = false)).why)
        assertEquals(PowerLogic.checkEveryMinutes(Check.LIVE), PowerLogic.jobMinutes(phoneAway))
        // Quiet hours using "Only when open" still wake hourly to see whether they've ended.
        assertEquals(60L, PowerLogic.jobMinutes(phoneAway.copy(quiet = Check.OPEN)))
        assertEquals(0L, PowerLogic.jobMinutes(phoneAway.copy(check = Check.OPEN)))
    }

    @Test
    fun leavingExtremeSaverRestoresWhatItTurnedOff() {
        for (c in listOf(true, false)) for (p in listOf(true, false))
            assertEquals(c to p, PowerLogic.extremeRestore(PowerLogic.extremeSnapshot(c, p)))
        assertNull(PowerLogic.extremeRestore(null))
        assertNull(PowerLogic.extremeRestore("x"))
    }

    @Test
    fun aVoiceRoomKeepsEveryRelayButRestsTheOtherRadiosWithTheScreenOff() {
        val on = PowerLogic.plan(phoneAway.copy(screenOn = true, inRoom = true))
        assertEquals(Why.ROOM, on.why)
        assertEquals(Radio.FULL, on.radio)
        val off = PowerLogic.plan(phoneAway.copy(inRoom = true))
        assertEquals(Why.ROOM, off.why)
        assertNull(off.relays)
        assertEquals(PowerLogic.FAST_PING_MS, off.pingMs)
        assertEquals(Radio.OFF, off.radio)
        assertEquals(Radio.OFF, off.wifi)
        // A 1:1 call still wins.
        assertEquals(Why.CALL, PowerLogic.plan(phoneAway.copy(inRoom = true, inCall = true)).why)
    }
}
