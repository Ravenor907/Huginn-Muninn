package io.github.ravenor907.muninn.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import io.github.ravenor907.muninn.ui.Access
import kotlin.math.roundToInt
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.PowerLogic.Check
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Framed
import io.github.ravenor907.muninn.ui.NavRow
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Palette
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DAY = 86_400_000L

/** Which Settings page is open: "" the short list, else a page's id (see [PAGES]). CI opens pages by setting it. */
object SettingsNav {
    var folder by mutableStateOf("")
    /** A chat page Settings asks for (My code from Your profile): MainActivity opens it on the Chats tab and clears this. */
    var goChat by mutableStateOf("")
    /** A Settings page a chat page asks for (Linked devices from the chat list): MainActivity opens it and clears this. */
    var goSettings by mutableStateOf("")
}

/** One page of Settings: its title and the page Back goes to. */
private class Page(val id: String, val title: String, val parent: String = "")

/** Everything under More, in the order shown. */
private val MORE = listOf(
    Page("calls", "Calls", "more"),
    Page("nearby", "Close by", "more"),
    Page("link", "Linked devices", "more"),
    Page("backup", "Backup", "more"),
    Page("tv", "Kids' TV", "more"),
    Page("access", "Accessibility", "more"),
    Page("about", "Updates and about", "more"),
    Page("erase", "Erase everything", "more"),
)

private val PAGES = listOf(
    Page("profile", "Profile"), Page("notify", "Notifications"), Page("look", "Look"), Page("lock", "Lock and privacy"),
    Page("battery", "Battery"), Page("more", "More"), Page("lockset", "Change lock", "lock"),
) + MORE

@Composable
fun SettingsScreen(app: Muninn, onSecure: () -> Unit) {
    val folder = SettingsNav.folder
    val page = PAGES.firstOrNull { it.id == folder }
    androidx.activity.compose.BackHandler(enabled = folder.isNotEmpty()) { SettingsNav.folder = page?.parent ?: "" }
    // A Kids' TV's settings are a whole screen of their own: its menu, then each section.
    if (folder.startsWith("tv:")) {
        val tvCount = io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState().value.values.count { !it.disconnecting }
        io.github.ravenor907.muninn.tv.ParentTvScreen(app, folder.removePrefix("tv:"), section = null, onBack = { SettingsNav.folder = if (tvCount > 1) "tv" else "" })
        return
    }
    if (folder == "lockset") { ChangeLock(app); return }
    androidx.compose.runtime.key(folder) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        if (page != null) FolderHeader(page.title) { SettingsNav.folder = page.parent }
        when (folder) {
            "" -> ShortList(app)
            "profile" -> ProfileScreen(app)
            "notify" -> NotifyPage(app)
            "look" -> LookPage(app)
            "lock" -> LockPage(app, onSecure)
            "more" -> MorePage()
            "battery" -> BatteryPage(app)
            "calls" -> CallsPage()
            "nearby" -> NearbyPage(app)
            "link" -> LinkedDevices(app)
            "backup" -> BackupPanel(app)
            "tv" -> TvPage(app)
            "access" -> AccessPage(app)
            "about" -> AboutPage(app)
            "erase" -> ErasePage(app)
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** The short list: Kids' TV when there is one, then six rows that open a page each. */
@Composable
private fun ShortList(app: Muninn) {
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val mine = tvs.values.filter { !it.disconnecting }
    if (mine.isNotEmpty()) io.github.ravenor907.muninn.ui.Framed {
        NavRow("Kids' TV", if (mine.size == 1) app.chat.nameOf(mine[0].key) else "${mine.size} TVs", icon = "tv") {
            SettingsNav.folder = if (mine.size == 1) "tv:" + mine[0].key else "tv"
        }
    }
    io.github.ravenor907.muninn.ui.Framed {
        MeRow(app)
        NavRow("Notifications", icon = "info") { SettingsNav.folder = "notify" }
        NavRow("Look", icon = "smile") { SettingsNav.folder = "look" }
        NavRow("Lock and privacy", icon = "lock") { SettingsNav.folder = "lock" }
        NavRow("Battery", BatteryChoice.of(app)?.title ?: "Your own mix", icon = "clock") { SettingsNav.folder = "battery" }
        NavRow("More", icon = "gear") { SettingsNav.folder = "more" }
    }
    if (Labs.on(app)) LabsSection(app)
}

/** Your picture, name and status at the top of Settings; tap for Your profile. */
@Composable
private fun MeRow(app: Muninn) {
    val status by app.profile.myStatus.collectAsState()
    val custom by app.profile.myCustomStatus.collectAsState()
    val name = app.identity.username
    val line = Status.of(status).title + if (custom.isNotBlank()) " · $custom" else ""
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { SettingsNav.folder = "profile" }
        .semantics(mergeDescendants = true) { contentDescription = "Your profile: $name, $line" }
        .padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        io.github.ravenor907.muninn.ui.Avatar("me", name, 48.dp, showDot = true)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, style = Type.big, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text("Profile · $line", style = Type.small, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        MIcon("forward", Palette.textMuted, 18.dp)
    }
}

/** A row that opens a page: its name, what it's set to, and an arrow. */
@Composable
private fun PageRow(title: String, value: String, onClick: () -> Unit) = NavRow(title, value, onClick = onClick)

/** One short help line under something that isn't obvious. */
@Composable
private fun Help(text: String) = Text(text, style = Type.small, modifier = Modifier.padding(top = 4.dp))

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun posName(p: String) = when (p) { "top" -> "Top"; "middle" -> "Middle"; else -> "Lower" }

/**
 * The one battery choice. Messages still arrive on time with the first two; the switches each one sets are all
 * under More settings › Battery details, for anyone who wants their own mix.
 */
private enum class BatteryChoice(val title: String, val help: String) {
    FASTEST("Fastest", "Arrives in seconds. Uses the most."),
    BALANCED("Balanced", "Arrives in seconds. Saves battery."),
    EXTREME("Extreme saver", "No notifications. Saves most.");

    fun apply(app: Muninn) {
        val p = app.power
        when (this) {
            FASTEST -> { p.check = Check.LIVE; p.saver = false; p.lowBatterySave = false; p.adaptive = false; p.dataSaver = false; p.extreme = false }
            BALANCED -> { p.check = Check.LIVE; p.saver = true; p.lowBatterySave = true; p.adaptive = true; p.dataSaver = true; p.extreme = false }
            EXTREME -> { p.check = Check.LIVE; p.saver = true; p.lowBatterySave = true; p.adaptive = true; p.dataSaver = true; p.extreme = true
                p.closeBy = false; app.identity.linkPreviews = false }
        }
        p.refresh()
    }

    companion object {
        /** The choice the current switches match, or null for someone's own mix. */
        fun of(app: Muninn): BatteryChoice? {
            val p = app.power
            if (p.check != Check.LIVE) return null
            return when {
                !p.saver && !p.lowBatterySave && !p.adaptive && !p.dataSaver -> FASTEST
                p.saver && p.lowBatterySave && p.adaptive && p.dataSaver -> if (p.extreme) EXTREME else BALANCED
                else -> null
            }
        }
    }
}

@Composable
private fun NotifyPage(app: Muninn) {
    val id = app.identity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var preview by remember { mutableStateOf(id.showPreview) }
    if (app.power.extreme) Framed { Text("Extreme saver: no notifications.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(vertical = 10.dp)) }
    // Android 14 and newer: without "Full-screen notifications" an incoming call is only a notification.
    var fullOff by remember { mutableStateOf(fullScreenOff(ctx)) }
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { fullOff = fullScreenOff(ctx) }
    Framed {
        val on = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        PageRow("Notifications", if (on) "On" else "Off") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            } catch (_: Exception) {}
        }
        if (fullOff) PageRow("Full-screen calls", "Off. Tap to turn on") { openFullScreenSetting(ctx) }
        ToggleRow("Show the message", "Off: just “New message”", preview) { preview = it; id.showPreview = it }
        PageRow("Sound and vibration", "") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Muninn.MESSAGES))
            } catch (_: Exception) {}
        }
    }
    Framed("Always right away") { RightAwayRow(app) }
    Framed("Quiet hours") { QuietHours(app) }
}

@Composable
private fun LookPage(app: Muninn) {
    val id = app.identity
    Framed("Look") {
        val looks = io.github.ravenor907.muninn.ui.Looks.ALL
        val seasons = io.github.ravenor907.muninn.ui.Seasons
        fun saveSeason() { id.season = Access.season; id.seasonGround = Access.seasonGround; id.seasonAnim = Access.seasonAnim }
        // Two a line; one a line at a large text size, so no name breaks mid-word.
        ChoiceRow(looks.size, Modifier.padding(vertical = 8.dp), minWidth = 140.dp, max = 2) { i, m ->
            val (k, n) = looks[i]
            val seasonal = k == io.github.ravenor907.muninn.ui.Looks.SEASONAL
            // Seasonal names its season ("Seasonal: Snow"); tapping it again steps to the next season.
            val label = if (seasonal) "$n: ${seasons.name(seasons.choice(Access.season))}" else n
            MButton(label, m, if (Access.look == k) Kind.Accent else Kind.Normal) {
                if (seasonal && Access.look == k) { Access.season = seasons.next(seasons.choice(Access.season)); saveSeason() }
                else { Access.look = k; id.look = k }
            }
        }
        if (Access.look == io.github.ravenor907.muninn.ui.Looks.SEASONAL) io.github.ravenor907.muninn.ui.SeasonControls { saveSeason() }
    }
    Framed("Size of everything") {
        val sizes = listOf(0.85f, 1f, 1.15f, 1.3f)
        ChoiceRow(sizes.size, Modifier.padding(vertical = 8.dp)) { i, m ->
            val v = sizes[i]; MButton(pct(v), m, if (Access.size == v) Kind.Accent else Kind.Normal) { Access.size = v; id.uiSize = v }
        }
    }
    Framed("Text size") {
        val texts = listOf(1f, 1.15f, 1.3f, 1.5f)
        ChoiceRow(texts.size, Modifier.padding(top = 8.dp)) { i, m ->
            val v = texts[i]; MButton(pct(v), m, if (Access.text == v) Kind.Accent else Kind.Normal) { Access.text = v; id.textSize = v }
        }
        Help("Adds to your phone's font size.")
        Spacer(Modifier.height(8.dp))
    }
    Framed { PageRow("Accessibility", "") { SettingsNav.folder = "access" } }
}

@Composable
private fun AccessPage(app: Muninn) {
    val id = app.identity
    Framed {
        ToggleRow("High contrast", "Stronger outlines", Access.contrast) { Access.contrast = it; id.highContrast = it }
        ToggleRow("Color-blind friendly", "Amber warnings, status in words", Access.colorBlind) { Access.colorBlind = it; id.colorBlind = it }
        ToggleRow("Reduce motion", "No sliding or fading", Access.reduceMotion) { Access.reduceMotion = it; id.reduceMotion = it }
    }
}

@Composable
private fun LockPage(app: Muninn, onSecure: () -> Unit) {
    val id = app.identity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val onTv = io.github.ravenor907.muninn.tv.KidMode.isTv(ctx)
    var lockAfter by remember { mutableStateOf(id.lockAfter) }
    var pos by remember { mutableStateOf(id.patternPos) }
    var receipts by remember { mutableStateOf(id.readReceipts) }
    var activeOn by remember { mutableStateOf(id.activeStatus) }
    var secure by remember { mutableStateOf(id.secureScreen) }
    var previews by remember { mutableStateOf(id.linkPreviews) }
    var trail by remember { mutableStateOf(id.patternTrail) }
    var privateKeys by remember { mutableStateOf(id.privateKeyboard) }
    var hasLock by remember { mutableStateOf(app.vault.hasPassword) }
    Framed("Lock") {
        if (hasLock) PageRow("Change lock", if (app.vault.usesPattern) "Pattern" else "Password") { SettingsNav.folder = "lockset" }
        else {
            Text("No lock set.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 10.dp))
            MButton("Set a lock", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent) { SettingsNav.folder = "lockset" }
        }
        if (app.vault.usesPattern) {
            Text("Pattern position", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
            val places = listOf("top", "middle", "lower")
            ChoiceRow(places.size, Modifier.padding(top = 6.dp, bottom = 4.dp)) { i, m ->
                val p = places[i]; MButton(posName(p), m, if (pos == p) Kind.Accent else Kind.Normal) { pos = p; id.patternPos = p }
            }
            ToggleRow("Hide the pattern as I draw", "", !trail) { trail = !it; id.patternTrail = !it }
        }
        if (app.vault.hasPassword) {
            Text("Lock again after", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
            val afters = listOf(0L to "Right away", 60_000L to "1 min", 5 * 60_000L to "5 min", 30 * 60_000L to "30 min")
            ChoiceRow(afters.size, Modifier.padding(top = 6.dp, bottom = 4.dp)) { i, m ->
                val (ms, label) = afters[i]
                MButton(label, m, if (lockAfter == ms) Kind.Accent else Kind.Normal) { lockAfter = ms; id.lockAfter = ms }
            }
            var panic by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.enabled(ctx)) }
            var shake by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.shakeOn(ctx)) }
            ToggleRow("Panic lock", if (onTv) "Lock instantly from the icon" else "Lock instantly from tile or icon", panic) {
                panic = it; io.github.ravenor907.muninn.ui.Panic.setEnabled(ctx, it)
            }
            if (panic && !onTv && io.github.ravenor907.muninn.ui.Panic.canShake(ctx))
                ToggleRow("Shake to lock", "Shake hard 3 times", shake) { shake = it; io.github.ravenor907.muninn.ui.Panic.setShake(ctx, it) }
        }
        // Destructive, so last, red, and asked first.
        if (hasLock) io.github.ravenor907.muninn.ui.MenuRow("lock", "Turn off the lock", danger = true) {
            Ui.sure(Ask("Turn off the lock?", "Anyone with this phone can read your chats.", "Turn off", danger = true) {
                try { app.vault.setPassword(null) } catch (_: Exception) {}
                hasLock = app.vault.hasPassword
                if (!hasLock) { io.github.ravenor907.muninn.screens.skipLock(app); Ui.say("The lock is off.") }
            })
        }
    }
    Framed("Privacy") {
        ToggleRow("Send read receipts", "Delivered still shows", receipts) { receipts = it; id.readReceipts = it }
        ToggleRow("Active status", "Off: nobody sees it, both ways", activeOn) { activeOn = it; app.chat.setActiveStatus(it) }
        ToggleRow("Block screenshots", "Also hides it in recents", secure) { secure = it; id.secureScreen = it; onSecure() }
        ToggleRow("Link previews", "The site sees your address", previews) { previews = it; id.linkPreviews = it }
        ToggleRow("Private keyboard", "Keyboard doesn't learn your typing", privateKeys) {
            privateKeys = it; id.privateKeyboard = it; io.github.ravenor907.muninn.ui.PrivateKeyboard.on = it
        }
    }
    Framed("Messages") {
        var shorter by remember { mutableStateOf(id.deleteAfter in 1 until 7 * DAY) }
        ToggleRow("Delete after 1 day", "Every chat, on both sides", shorter) {
            shorter = it; val ms = if (it) DAY else 0L; id.deleteAfter = ms; app.chat.pruneSoon()
        }
    }
}

/** Settings › Lock and privacy › Change lock: a page of its own, so the pattern sits where it's set to. */
@Composable
private fun ChangeLock(app: Muninn) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FolderHeader(if (app.vault.hasPassword) "Change lock" else "Set a lock") { SettingsNav.folder = "lock" }
        LockSetup(app, onCancel = { SettingsNav.folder = "lock" }, fill = true) { SettingsNav.folder = "lock" }
    }
}

@Composable
private fun MorePage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Framed {
        MORE.filter { it.id != "erase" }.forEach { p -> PageRow(p.title, "") { SettingsNav.folder = p.id } }
        // Hands Muninn itself to a phone close by (Quick Share or Bluetooth), no internet needed.
        PageRow("Share the app", "") { io.github.ravenor907.muninn.ui.ShareApp.share(ctx) }
        MORE.filter { it.id == "erase" }.forEach { p -> PageRow(p.title, "") { SettingsNav.folder = p.id } }
    }
}

@Composable
private fun BatteryPage(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var choice by remember { mutableStateOf(BatteryChoice.of(app)) }
    var check by remember { mutableStateOf(app.power.check) }
    var saver by remember { mutableStateOf(app.power.saver) }
    var low by remember { mutableStateOf(app.power.lowBatterySave) }
    var adaptive by remember { mutableStateOf(app.power.adaptive) }
    var data by remember { mutableStateOf(app.power.dataSaver) }
    fun reread() { choice = BatteryChoice.of(app); check = app.power.check; saver = app.power.saver; low = app.power.lowBatterySave
        adaptive = app.power.adaptive; data = app.power.dataSaver }
    val stay = check == Check.LIVE
    var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
    // Only while this page is on screen.
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) }
    Framed("Battery") {
        ChoiceRow(BatteryChoice.entries.size, Modifier.padding(top = 8.dp), minWidth = 96.dp) { i, m ->
            val c = BatteryChoice.entries[i]
            MButton(c.title, m, if (choice == c) Kind.Accent else Kind.Normal) { c.apply(app); reread() }
        }
        Help(choice?.help ?: "Your own mix, below.")
        Spacer(Modifier.height(8.dp))
    }
    if (stay && !free) Framed {
        Text("Android may pause Muninn.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 10.dp))
        MButton("Allow background use", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent) { io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx) }
    }
    Framed("Check for messages") {
        ChoiceRow(Check.entries.size, Modifier.padding(top = 8.dp), minWidth = 140.dp, max = 2) { i, m ->
            val c = Check.entries[i]; MButton(c.title, m, if (check == c) Kind.Accent else Kind.Normal) { check = c; app.power.check = c; choice = BatteryChoice.of(app) }
        }
        Help(when (check) {
            Check.LIVE -> "Arrives in seconds"
            Check.QUARTER -> "Within 15 minutes. Calls missed."
            Check.HOUR -> "Within an hour. Calls missed."
            Check.OPEN -> "Only while Muninn is open."
        })
        Spacer(Modifier.height(8.dp))
    }
    Framed("Saving") {
        if (stay) ToggleRow("Save when the screen is off", "Saves battery", saver) { saver = it; app.power.saver = it; choice = BatteryChoice.of(app) }
        ToggleRow("Save more on low battery", "Below 30%", low) { low = it; app.power.lowBatterySave = it; choice = BatteryChoice.of(app) }
        ToggleRow("Check less when quiet", "After 3 quiet hours", adaptive) { adaptive = it; app.power.adaptive = it; choice = BatteryChoice.of(app) }
        ToggleRow("Data saver", "Big files wait for Wi-Fi", data) { data = it; app.power.dataSaver = it; choice = BatteryChoice.of(app) }
        if (stay && Build.VERSION.SDK_INT >= 26) PageRow("Hide the Listening notification", "") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, io.github.ravenor907.muninn.net.MessageService.CHANNEL))
            } catch (_: Exception) {}
        }
    }
    androidx.compose.runtime.LaunchedEffect(check) { app.power.refresh() }
    Framed("Today") { BatteryReport(app) }
}

@Composable
private fun CallsPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var noise by remember { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.noiseRemoval(ctx)) }
    Framed {
        ToggleRow("Noise removal", "Takes out background noise", noise) {
            noise = it; io.github.ravenor907.muninn.net.call.CallManager.setNoiseRemoval(ctx, it)
        }
    }
}

@Composable
private fun NearbyPage(app: Muninn) {
    val id = app.identity
    var visible by remember { mutableStateOf(id.visibleNearby) }
    var nearby by remember { mutableStateOf(id.nearbyChat) }
    var keep by remember { mutableStateOf(id.keepNearbyHistory) }
    var device by remember { mutableStateOf(id.deviceName) }
    var closeBy by remember { mutableStateOf(app.power.closeBy) }
    Framed {
        ToggleRow("Visible to people nearby", "Off: only contacts can reach you", visible) {
            visible = it; id.visibleNearby = it; app.net.announce(); app.close.readvertise(); app.ble.hiddenChanged()
        }
        if (visible) ToggleRow("Nearby room", "A chat for everyone close by", nearby) {
            nearby = it; id.nearbyChat = it; app.net.announce()
        }
        ToggleRow("Look in the background", "Uses more battery", closeBy) { closeBy = it; app.power.closeBy = it }
        ToggleRow("Keep Nearby messages", "Off: cleared when Muninn closes", keep) { keep = it; id.keepNearbyHistory = it; if (!it) app.chat.clearNearby() }
    }
    Framed("This phone's name") {
        Row(Modifier.padding(vertical = 8.dp)) {
            Field(device, { device = it.take(40) }, "Muninn on my phone", Modifier.weight(1f))
            MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = device.isNotBlank() && device != id.deviceName) { id.deviceName = device; app.net.announce(); Ui.say("Saved.") }
        }
        Help("Files go to ${io.github.ravenor907.muninn.net.Received.place(androidx.compose.ui.platform.LocalContext.current)}")
        Spacer(Modifier.height(8.dp))
    }
}

/** More › Kids' TV: every TV this phone looks after, each opening its own settings. */
@Composable
private fun TvPage(app: Muninn) {
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val mine = tvs.values.filter { !it.disconnecting }
    Framed {
        if (mine.isEmpty()) Text("No Kids' TV yet. Scan the TV's code.", style = Type.normal, modifier = Modifier.padding(vertical = 10.dp))
        mine.forEach { t -> PageRow(app.chat.nameOf(t.key), "") { SettingsNav.folder = "tv:" + t.key } }
    }
}

@Composable
private fun AboutPage(app: Muninn) {
    val up by app.updater.state.collectAsState()
    var auto by remember { mutableStateOf(app.identity.autoUpdate) }
    Framed("Updates") {
        Text("Muninn ${app.updater.current}. ${up.status}", style = Type.normal, modifier = Modifier.padding(top = 10.dp))
        if (up.busy && up.progress > 0f) Bar(up.progress, Modifier.padding(top = 6.dp))
        MButton(if (up.ready) "Install ${up.latest}" else "Check now", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent, enabled = !up.busy) {
            if (up.ready) app.updater.download() else app.updater.check()
        }
        ToggleRow("Check automatically", "Your Kids' TV follows this", auto) {
            auto = it; app.identity.autoUpdate = it; io.github.ravenor907.muninn.tv.TvParent.autoUpdateChanged(app)
        }
    }
    Framed("About") {
        Text("Private chat and file sharing. Made by Ravenor907. GPL-3.0.", style = Type.small, modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun ErasePage(app: Muninn) {
    Framed {
        io.github.ravenor907.muninn.ui.MenuRow("delete", "Delete all messages", danger = true) {
            Ui.sure(Ask("Delete all messages?", "Contacts stay. This can't be undone.", "Hold to delete", danger = true, hold = true) {
                app.chat.deleteAll(); Ui.say("All messages deleted.")
            })
        }
        io.github.ravenor907.muninn.ui.MenuRow("close", "Erase everything", danger = true) {
            Ui.sure(Ask("Erase everything?", "Messages, contacts, keys and settings. This can't be undone.",
                "Hold to erase", danger = true, hold = true) { app.vault.eraseAll(); android.os.Process.killProcess(android.os.Process.myPid()) })
        }
    }
}

/**
 * The "Always right away" list in one row ("Always right away: Family + 2 people"): whose messages a low battery,
 * quiet hours and checking less never hold back. It starts as the Family folder. Tap to see it, add or take off
 * anyone (a chat's menu does the same).
 */
@Composable
private fun RightAwayRow(app: Muninn) {
    val ready by app.ready.collectAsState()
    val ra = app.power.rightAway
    if (!ready || ra == null) return
    val st by ra.all.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    val contacts by app.chat.contacts.all.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val keys = remember(st, folders) { app.power.rightAwayKeys() }
    val family = remember(folders) { folders.firstOrNull { it.name.equals(io.github.ravenor907.muninn.net.Power.FAMILY, true) }?.keys.orEmpty().toSet() }
    fun name(k: String): String = if (k.startsWith("g:")) groups[k.removePrefix("g:")]?.state?.name ?: "A group" else app.chat.nameOf(k)
    val extra = keys.count { it !in family }
    val summary = when {
        keys.isEmpty() -> "Nobody yet"
        keys.none { it in family } -> if (extra == 1) "1 person" else "$extra people"
        extra == 0 -> "Family"
        else -> "Family + $extra " + if (extra == 1) "person" else "people"
    }
    var open by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(summary, style = Type.normal.copy(color = Palette.text))
            Text("Even when saving battery", style = Type.small)
        }
        Text(if (open) "Hide" else "Change", style = Type.small.copy(color = Palette.accent), modifier = Modifier.padding(start = 8.dp))
    }
    if (!open) return
    if (keys.isEmpty()) Text("Family is on it by default.", style = Type.small.copy(color = Palette.textMuted))
    keys.sortedBy { name(it).lowercase() }.forEach { k ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(name(k) + if (k in family) " (Family)" else "", style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
            io.github.ravenor907.muninn.ui.IconAction("close", "Take ${name(k)} off", Palette.textMuted, size = 18.dp) { app.power.setRightAway(k, false) }
        }
    }
    MButton(if (adding) "Done adding" else "Add someone", Modifier.fillMaxWidth().padding(vertical = 8.dp)) { adding = !adding }
    if (adding) {
        val others = (contacts.filter { !it.groupOnly }.map { it.chatKey } + groups.filterValues { !it.readOnly }.keys.map { "g:$it" })
            .filter { it !in keys }.distinct().sortedBy { name(it).lowercase() }
        if (others.isEmpty()) Text("Everyone is on the list.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        others.forEach { k ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(name(k), style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                MButton("Add", kind = Kind.Accent) { app.power.setRightAway(k, true) }
            }
        }
    }
}

/** Today's battery report: what Muninn kept running, what the connection cost, how often it checked, and the data it used. */
@Composable
private fun BatteryReport(app: Muninn) {
    var r by remember { mutableStateOf(app.power.report()) }
    io.github.ravenor907.muninn.ui.EveryWhileShown(30_000) { r = app.power.report() }
    fun time(ms: Long): String { val m = ms / 60_000; return if (m < 60) "$m min" else "${m / 60} h ${m % 60} min" }
    Column(Modifier.padding(vertical = 8.dp)) {
        Text("On screen or charging: ${time(r.openMs)}", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        Text("Fully connected: ${time(r.connectedMs)}", style = Type.small)
        Text("Light connection: ${time(r.savingMs)}", style = Type.small)
        Text("Resting: ${time(r.restingMs)}", style = Type.small)
        Text("Checks: ${r.pings}, ${r.wakeups} woke the phone", style = Type.small)
        Text("Reconnects: ${r.reconnects}", style = Type.small)
        Text("Server checks: ${r.serverPings}", style = Type.small)
        Text("Background checks: ${r.checks}", style = Type.small)
        Text("Cost: about ${"%.1f".format(java.util.Locale.US, r.costPercent)}% battery", style = Type.small)
        r.dataBytes?.let { Text("Data used: ${io.github.ravenor907.muninn.net.LocalSend.size(it)}", style = Type.small) }
    }
}

/** Quiet hours: from and to on the hour, and how Muninn checks while they last (it saves the most then). */
@Composable
private fun QuietHours(app: Muninn) {
    var on by remember { mutableStateOf(app.power.quietOn) }
    var from by remember { mutableStateOf(app.power.quietFrom) }
    var to by remember { mutableStateOf(app.power.quietTo) }
    var mode by remember { mutableStateOf(app.power.quietMode) }
    fun hour(h: Int) = when (h) { 0 -> "12 AM"; 12 -> "12 PM"; in 1..11 -> "$h AM"; else -> "${h - 12} PM" }
    var dnd by remember { mutableStateOf(app.power.followDnd) }
    ToggleRow("Quiet hours", if (on) "${hour(from)} to ${hour(to)}" else "Check less overnight", on) {
        on = it; app.power.quietOn = it
    }
    ToggleRow("Also during Do Not Disturb", "", dnd) { dnd = it; app.power.followDnd = it }
    if (!on) {
        if (dnd) QuietMode(mode) { mode = it; app.power.quietMode = it }
        return
    }
    @Composable fun Stepper(label: String, h: Int, set: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
            MButton("−") { set(Math.floorMod(h - 1, 24)) }
            Text(hour(h), style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(horizontal = 12.dp))
            MButton("+") { set(Math.floorMod(h + 1, 24)) }
        }
    }
    Stepper("From", from) { from = it; app.power.quietFrom = it }
    Stepper("To", to) { to = it; app.power.quietTo = it }
    QuietMode(mode) { mode = it; app.power.quietMode = it }
}

/** How Muninn checks during quiet hours and Do Not Disturb. */
@Composable
private fun QuietMode(mode: Check, set: (Check) -> Unit) {
    val modes = listOf(Check.HOUR, Check.OPEN)
    ChoiceRow(modes.size, Modifier.padding(top = 6.dp), minWidth = 140.dp) { i, m ->
        val c = modes[i]; MButton(c.title, m, if (mode == c) Kind.Accent else Kind.Normal) { set(c) }
    }
    Spacer(Modifier.height(8.dp))
}

/** A folder's title, with a way back to the list (Back does the same). */
@Composable
private fun FolderHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Box(Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Back") { onBack() }.padding(12.dp)) {
            MIcon("back", Palette.text, 24.dp)
        }
        Text(title.uppercase(), style = Type.title.copy(fontSize = 17.sp, letterSpacing = 2.sp), modifier = Modifier.weight(1f).padding(start = 4.dp))
    }
}

/**
 * Linked devices, companion style: a tablet or second phone shows this phone's chats and sends through it while this
 * phone is online. Contacts see nothing new. One device shows a code, the other takes it.
 */
@Composable
private fun LinkedDevices(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val companions by io.github.ravenor907.muninn.net.Link.companions.collectAsState()
    val phone by io.github.ravenor907.muninn.net.Link.phone.collectAsState()
    var code by remember { mutableStateOf<String?>(null) }
    var pasted by remember { mutableStateOf("") }
    Help("Show these chats on another device.")
    phone?.let { p ->
        Section("This device mirrors")
        Panel {
            Text(p.name, style = Type.big)
            MButton("Unlink", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Danger) {
                Ui.sure(Ask("Unlink ${p.name}?", "This device stops showing its chats.", "Unlink", danger = true) { io.github.ravenor907.muninn.net.Link.unlink(p.key) })
            }
        }
    }
    if (companions.isNotEmpty()) {
        Section("Linked to this phone")
        Panel {
            companions.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(d.name, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                    MButton("Unlink", kind = Kind.Danger) {
                        Ui.sure(Ask("Unlink ${d.name}?", "Its copy of your chats is removed.", "Unlink", danger = true) { io.github.ravenor907.muninn.net.Link.unlink(d.key) })
                    }
                }
            }
        }
    }
    // Use on both (linked devices, below), or Move here (cap x1).
    MoveSection(app)
    Section("Link a device to this phone")
    Panel {
        Text("Scan the other device's link code.", style = Type.small)
        var scanning by remember { mutableStateOf(false) }
        if (scanning) QrScanner(Modifier.fillMaxWidth().padding(top = 8.dp).height(260.dp)) { c ->
            scanning = false
            val err = io.github.ravenor907.muninn.net.Link.link(c)
            io.github.ravenor907.muninn.ui.Ui.say(err ?: "Now check the 4 emoji on both screens.", err != null)
        }
        MButton(if (scanning) "Stop scanning" else "Scan the code", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, icon = "qr") { scanning = !scanning }
        io.github.ravenor907.muninn.ui.Field(pasted, { pasted = it }, "Paste the link code", Modifier.padding(top = 8.dp), single = false)
        MButton("Link", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = pasted.isNotBlank()) {
            val err = io.github.ravenor907.muninn.net.Link.link(pasted)
            io.github.ravenor907.muninn.ui.Ui.say(err ?: "Now check the 4 emoji on both screens.", err != null)
            if (err == null) pasted = ""
        }
    }
    if (phone == null) {
        Section("Use this device as a companion")
        Panel {
            Text("Scan with your phone. Works 10 minutes.", style = Type.small)
            val c = code
            if (c == null) MButton("Show the link code", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { code = io.github.ravenor907.muninn.net.Link.myLinkCode() }
            else {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    io.github.ravenor907.muninn.screens.Qr(c, 220.dp)
                }
                MButton("Copy", Modifier.fillMaxWidth(), icon = "copy") {
                    io.github.ravenor907.muninn.ui.Clip.copy(ctx, "Muninn link code", c)
                    io.github.ravenor907.muninn.ui.Ui.say("Code copied. Paste it on your phone.")
                }
                MButton("Share", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "send") {
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, c), "Send the link code to your phone"))
                }
            }
        }
    }
}
