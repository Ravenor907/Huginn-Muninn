package io.github.ravenor907.muninn.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.selected
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
    Page("nearby", "Nearby", "more"),
    Page("files", "Files", "more"),
    Page("backup", "Backup", "more"),
    Page("tv", "Kids' TV", "more"),
    Page("access", "Accessibility", "more"),
    Page("about", "Updates and about", "more"),
    Page("erase", "Erase everything", "more"),
)

private val PAGES = listOf(
    Page("profile", "Profile"), Page("notify", "Notifications"), Page("look", "Look"), Page("lock", "Lock and privacy"),
    Page("battery", "Battery"), Page("link", "Linked devices"), Page("more", "More"), Page("lockset", "Change lock", "lock"),
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
            "calls" -> CallsPage(app)
            "files" -> FilesPage()
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

/** The short list: Kids' TV when there is one, then eight rows that open a page each (Backup is also under More). */
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
        NavRow("Notifications", icon = "bell") { SettingsNav.folder = "notify" }
        NavRow("Look", icon = "palette") { SettingsNav.folder = "look" }
        NavRow("Lock and privacy", icon = "lock") { SettingsNav.folder = "lock" }
        NavRow("Battery", BatteryChoice.of(app)?.title ?: "Your own mix", icon = "battery") { SettingsNav.folder = "battery" }
        NavRow("Backup", "Keep your chats and contacts on a new phone", icon = "download") { SettingsNav.folder = "backup" }
        NavRow("Linked devices", "Scan to link a computer or tablet", icon = "qr") { SettingsNav.folder = "link" }
        NavRow("More", icon = "more") { SettingsNav.folder = "more" }
    }
    if (Labs.on(app)) LabsSection(app)
}

/** Your picture, name and status at the top of Settings; tap for Your profile. */
@Composable
private fun MeRow(app: Muninn) {
    val ownStatus by app.profile.myStatus.collectAsState()
    val ownCustom by app.profile.myCustomStatus.collectAsState()
    // A linked tablet: the phone is the account, so its name and status are "you" here.
    val phoneMe by io.github.ravenor907.muninn.net.Link.me.collectAsState()
    val linkedPhone by io.github.ravenor907.muninn.net.Link.phone.collectAsState()
    val me = phoneMe?.takeIf { linkedPhone?.ok == true }
    val status = me?.status ?: ownStatus
    val custom = me?.custom ?: ownCustom
    val name = me?.name ?: app.identity.username
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

/** A row for something that's off and should be on: in red, with what it costs, and a tap to fix it. */
@Composable
private fun WarnRow(title: String, value: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        .padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        MIcon("info", Palette.danger, 20.dp)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = Type.normal.copy(color = Palette.danger))
            Text(value, style = Type.small)
        }
        MIcon("forward", Palette.textMuted, 18.dp)
    }
}

/**
 * "What notifications show": Name and message, Name only or Nothing, one line each on what it means. The same choice
 * on first setup's Allow page and in Settings › Notifications. A ringing call shows the name with either of the first two.
 */
@Composable
internal fun NotifyShowsChoice(now: io.github.ravenor907.muninn.net.Identity.Shows, onPick: (io.github.ravenor907.muninn.net.Identity.Shows) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text("What notifications show", style = Type.big.copy(fontSize = 16.sp))
        io.github.ravenor907.muninn.net.Identity.Shows.entries.forEach { o ->
            val on = o == now
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                .border(1.dp, if (on) Palette.accent else Palette.line, io.github.ravenor907.muninn.ui.Corner)
                .background(if (on) Palette.accentFill else androidx.compose.ui.graphics.Color.Transparent)
                .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { onPick(o) }
                .semantics(mergeDescendants = true) { selected = on }
                .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.title, style = Type.normal.copy(color = Palette.text))
                    Text(o.help, style = Type.small)
                }
                if (on) MIcon("check", Palette.accent, 18.dp, weight = 2.2f)
            }
        }
        Text("On a locked phone a call says “Incoming call”, with the name unless you pick Nothing.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
    }
}

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
    EXTREME("Extreme saver", "Only while Muninn is open. No notifications.");

    fun apply(app: Muninn) {
        val p = app.power
        when (this) {
            FASTEST -> { p.leaveExtreme(); p.check = Check.LIVE; p.saver = false; p.lowBatterySave = false; p.adaptive = false; p.dataSaver = false }
            BALANCED -> { p.leaveExtreme(); p.check = Check.LIVE; p.saver = true; p.lowBatterySave = true; p.adaptive = true; p.dataSaver = true }
            EXTREME -> { p.check = Check.LIVE; p.saver = true; p.lowBatterySave = true; p.adaptive = true; p.dataSaver = true; p.enterExtreme() }
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

/** Extreme saver is on: said at the top of Battery and Notifications (and the chat list), with a way out. */
@Composable
internal fun ExtremeBanner(button: String = "Turn off", onOff: () -> Unit) {
    Panel(Modifier.padding(top = 10.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            MIcon("clock", Palette.danger, 20.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Extreme saver is on", style = Type.normal.copy(color = Palette.text))
                Text("Messages come only while Muninn is open. No notifications.", style = Type.small)
            }
            MButton(button) { onOff() }
        }
    }
}

@Composable
private fun NotifyPage(app: Muninn) {
    val id = app.identity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var shows by remember { mutableStateOf(id.notifyShows) }
    var extreme by remember { mutableStateOf(app.power.extreme) }
    if (extreme) ExtremeBanner { BatteryChoice.BALANCED.apply(app); extreme = false }
    // Android 14 and newer: without "Full-screen notifications" an incoming call is only a notification.
    var fullOff by remember { mutableStateOf(fullScreenOff(ctx)) }
    // Without unrestricted battery use, Android pauses Muninn in the background: messages and calls come late.
    fun isBatteryOff() = id.stayConnected && !io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)
    var batteryOff by remember { mutableStateOf(isBatteryOff()) }
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { fullOff = fullScreenOff(ctx); batteryOff = isBatteryOff() }
    Framed {
        val on = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        PageRow("Notifications", if (on) "On" else "Off") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            } catch (_: Exception) {}
        }
        if (batteryOff) WarnRow("Run in the background", "Off: messages and calls can come late. Tap to allow") {
            io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx)
        }
        if (fullOff) WarnRow("Full-screen calls", "Off: calls won't show on the lock screen. Tap to allow") { openFullScreenSetting(ctx) }
        NotifyShowsChoice(shows) { shows = it; id.notifyShows = it }
        var reduce by remember { mutableStateOf(id.reduceNotifications) }
        ToggleRow("Reduce notifications", "One per chat until you open it", reduce) { reduce = it; id.reduceNotifications = it }
        // Mentions: "@" and my name, or one of these nicknames, in a group.
        var nick by remember { mutableStateOf(id.mentionNames) }
        Text("Also mention me as", style = Type.big.copy(fontSize = 16.sp), modifier = Modifier.padding(top = 10.dp))
        // Saved as it's typed: nothing is lost by leaving.
        Field(nick, { nick = it.take(80); id.mentionNames = nick }, "Nicknames, like tay, T", Modifier.padding(vertical = 6.dp))
        Help("Groups with more than 10 people notify only for mentions and replies, unless you pick otherwise in the group.")
        PageRow("Sound and vibration", "") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Muninn.MESSAGES))
            } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= 26) PageRow("Hide the waiting notification", "Messages still arrive") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, io.github.ravenor907.muninn.net.MessageService.CHANNEL))
            } catch (_: Exception) {}
        }
    }
    Framed("Always right away") { RightAwayRow(app) }
    Framed("Quiet hours") { QuietHours(app) }
}

/**
 * Settings › Look › Accent: gold (the default), iron, copper, jade, fjord, heather, ember, or any color as "#RRGGBB".
 * The look darkens it on Light and makes it white in high contrast.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccentChoices(app: Muninn) {
    // Only the accents that suit the Color vision picked; the saved one shows as the nearest of them.
    val all = io.github.ravenor907.muninn.ui.Accents.NAMES
    val names = io.github.ravenor907.muninn.ui.ColorVision.accents(Access.colorVision)
    val titles = names.map { io.github.ravenor907.muninn.ui.Accents.TITLES[all.indexOf(it)] }
    var custom by remember { mutableStateOf(if (io.github.ravenor907.muninn.ui.Accents.valid(Access.accent)) Access.accent else "") }
    fun pick(v: String) { Access.accent = io.github.ravenor907.muninn.ui.Accents.choice(v); app.identity.accent = Access.accent }
    androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        names.forEachIndexed { i, n ->
            val on = Palette.accentKey == n
            val c = Palette.accentOf(n)
            Column(Modifier.clip(io.github.ravenor907.muninn.ui.Corner).clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { pick(n) }
                .semantics(mergeDescendants = true) { selected = on; contentDescription = titles[i] }.padding(4.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)
                    .border(if (on) 3.dp else 1.dp, if (on) Palette.text else Palette.line, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center) { if (on) MIcon("check", Palette.onAccentOf(c), 22.dp, weight = 2.2f) }
                Text(titles[i], style = Type.small.copy(color = if (on) Palette.text else Palette.textMuted), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    // No color: gray is the one accent, so no other color to type.
    if (Access.colorVision != io.github.ravenor907.muninn.ui.ColorVision.MONO)
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        val ok = io.github.ravenor907.muninn.ui.Accents.valid(custom)
        Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .background(if (ok) Palette.accentOf(custom.trim()) else Palette.raised).border(1.dp, Palette.line, androidx.compose.foundation.shape.CircleShape))
        Field(custom, { custom = it.take(7) }, "Any color, like #4F8FDE", Modifier.weight(1f).padding(horizontal = 10.dp))
        MButton("Use", kind = if (ok && Access.accent == custom.trim().uppercase()) Kind.Accent else Kind.Normal, enabled = ok) { pick(custom) }
    }
}

@Composable
private fun LookPage(app: Muninn) {
    val id = app.identity
    Framed("Look") {
        val looks = io.github.ravenor907.muninn.ui.Looks.ALL
        fun saveSeason() { id.season = Access.season; id.seasonGround = Access.seasonGround; id.seasonAnim = Access.seasonAnim }
        // All three on a line; fewer a line at a large text size, so no name breaks mid-word.
        ChoiceRow(looks.size, Modifier.padding(vertical = 8.dp), minWidth = 100.dp) { i, m ->
            val (k, n) = looks[i]
            MButton(n, m, if (Access.look == k) Kind.Accent else Kind.Normal) { Access.look = k; id.look = k }
        }
        // Seasonal, over any look: the season, and what falls.
        io.github.ravenor907.muninn.ui.SeasonControls { saveSeason() }
    }
    Framed("Accent") { AccentChoices(app) }
    Framed("Color vision") { io.github.ravenor907.muninn.ui.ColorVisionControls { id.colorVision = Access.colorVision } }
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
    var nearbyOn by remember { mutableStateOf(id.visibleNearby) }
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
            val afters = io.github.ravenor907.muninn.ui.AppLock.AFTERS
            ChoiceRow(afters.size, Modifier.padding(top = 6.dp, bottom = 4.dp)) { i, m ->
                val ms = afters[i]
                val label = if (ms == 0L) "Right away" else "${ms / 60_000L} min"
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
        // One switch for everything nearby: off, nothing is announced or scanned for and nobody nearby is listed.
        ToggleRow("Nearby", "Off: contacts only, nothing announced", nearbyOn) {
            nearbyOn = it; id.visibleNearby = it; app.net.nearbyChanged(); app.ble.hiddenChanged()
        }
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
    // Any change by hand ends Extreme saver (and brings back what it turned off).
    fun byHand() { app.power.leaveExtreme(); choice = BatteryChoice.of(app) }
    val stay = check == Check.LIVE
    var free by remember { mutableStateOf(io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx)) }
    // Only while this page is on screen.
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { free = io.github.ravenor907.muninn.net.MessageService.unrestricted(ctx) }
    if (choice == BatteryChoice.EXTREME) ExtremeBanner { BatteryChoice.BALANCED.apply(app); reread() }
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
            val c = Check.entries[i]; MButton(c.title, m, if (check == c) Kind.Accent else Kind.Normal) { check = c; app.power.check = c; byHand() }
        }
        Help(when {
            choice == BatteryChoice.EXTREME -> "Only while Muninn is open."
            else -> when (check) {
            Check.LIVE -> "Arrives in seconds"
            Check.QUARTER -> "Within 15 minutes. Calls missed."
            Check.HOUR -> "Within an hour. Calls missed."
            Check.OPEN -> "Only while Muninn is open."
        } })
        // A linked computer sends and reads through this phone: anything but Right away makes it wait.
        val linked by io.github.ravenor907.muninn.net.Link.companions.collectAsState()
        linked.firstOrNull { it.ok }?.takeIf { check != Check.LIVE || choice == BatteryChoice.EXTREME || adaptive }?.let { c ->
            Help("${c.name} sends and gets messages through this phone. Choose ${Check.LIVE.title}" +
                (if (adaptive) " and turn off Check less when quiet" else "") + " so it doesn't wait.")
        }
        Spacer(Modifier.height(8.dp))
    }
    Framed("Saving") {
        if (stay) ToggleRow("Save when the screen is off", "Saves battery", saver) { saver = it; app.power.saver = it; byHand() }
        ToggleRow("Save more on low battery", "Below 30%", low) { low = it; app.power.lowBatterySave = it; byHand() }
        ToggleRow("Check less when quiet", "After 3 quiet hours", adaptive) { adaptive = it; app.power.adaptive = it; byHand() }
        ToggleRow("Data saver", "Big files wait for Wi-Fi", data) { data = it; app.power.dataSaver = it; byHand() }
        if (Build.VERSION.SDK_INT >= 26) PageRow("Hide the waiting notification", "Messages still arrive") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, io.github.ravenor907.muninn.net.MessageService.CHANNEL))
            } catch (_: Exception) {}
        }
    }
    androidx.compose.runtime.LaunchedEffect(check) { app.power.refresh() }
    Framed("Today") { BatteryReport(app) }
}

/** More › Files: where received files are saved (Android's folder picker; Download/Muninn by default). */
@Composable
private fun FilesPage() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // chosenTree first: a picked folder that went away resets the name shownRoot gives.
    var picked by remember { mutableStateOf(io.github.ravenor907.muninn.net.Received.chosenTree(ctx) != null) }
    var where by remember { mutableStateOf(io.github.ravenor907.muninn.net.Received.shownRoot()) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            io.github.ravenor907.muninn.net.Received.choose(ctx, uri)
            where = io.github.ravenor907.muninn.net.Received.shownRoot(); picked = io.github.ravenor907.muninn.net.Received.chosenTree(ctx) != null
        }
    }
    Framed {
        PageRow("Save files to", where) { try { pick.launch(null) } catch (_: Exception) { Ui.say("This phone has no folder picker.", true) } }
        if (picked) PageRow("Use Download/Muninn again", "") {
            io.github.ravenor907.muninn.net.Received.choose(ctx, null)
            where = io.github.ravenor907.muninn.net.Received.shownRoot(); picked = false
        }
        Help("Each chat gets its own folder inside it.")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CallsPage(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ask by remember { mutableStateOf(app.identity.roomAsk) }
    var noise by remember { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.noiseRemoval(ctx)) }
    var internet by remember { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.internetCalls(ctx)) }
    Framed {
        ToggleRow("Internet calls", "Off: only close by and on the same Wi-Fi", internet) {
            internet = it; io.github.ravenor907.muninn.net.call.CallManager.setInternetCalls(ctx, it)
        }
        ToggleRow("Noise removal", "Takes out background noise", noise) {
            noise = it; io.github.ravenor907.muninn.net.call.CallManager.setNoiseRemoval(ctx, it)
        }
        ToggleRow("Ask before joining a voice room", "Mic on or off, and where the sound goes", ask) { ask = it; app.identity.roomAsk = it }
        Help("Voice rooms use more battery than chat. With the screen off, Muninn keeps only what the sound needs.")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NearbyPage(app: Muninn) {
    val id = app.identity
    val visible = id.visibleNearby
    var nearby by remember { mutableStateOf(id.nearbyChat) }
    var keep by remember { mutableStateOf(id.keepNearbyHistory) }
    var device by remember { mutableStateOf(id.deviceName) }
    var closeBy by remember { mutableStateOf(app.power.closeBy) }
    Framed {
        // The switch itself is in Lock and privacy.
        if (!visible) NavRow("Nearby is off", "Lock and privacy", icon = "lock") { SettingsNav.folder = "lock" }
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
            auto = it; app.identity.autoUpdate = it
        }
    }
    Framed("About") {
        Text("Private chat and file sharing. Made by Ravenor907. GPL-3.0.", style = Type.small, modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
private fun ErasePage(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var erased by remember { mutableStateOf(false) }
    if (erased) {
        // "Erased", then Muninn starts again as new.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { try { app.vault.eraseAll() } catch (_: Exception) {} }
            kotlinx.coroutines.delay(1500)
            restartApp(ctx)
        }
        Framed {
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                MIcon("check", Palette.accent, 22.dp)
                Text("Erased", style = Type.big, modifier = Modifier.padding(start = 12.dp))
            }
            Text("Muninn starts again.", style = Type.small, modifier = Modifier.padding(bottom = 12.dp))
        }
        return
    }
    Framed {
        io.github.ravenor907.muninn.ui.MenuRow("delete", "Delete all messages", danger = true) {
            Ui.sure(Ask("Delete all messages?", "Contacts stay. This can't be undone.", "Hold to delete", danger = true, hold = true) {
                app.chat.deleteAll(); Ui.say("All messages deleted.")
            })
        }
        io.github.ravenor907.muninn.ui.MenuRow("close", "Erase everything", danger = true) {
            Ui.sure(Ask("Erase everything?", "Messages, contacts, keys and settings. This can't be undone.",
                "Hold to erase", danger = true, hold = true) { erased = true })
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
    var details by remember { mutableStateOf(false) }
    Column(Modifier.padding(vertical = 8.dp)) {
        // One plain line first; the counts are behind Details for anyone who wants them.
        Text("Today Muninn used about ${"%.1f".format(java.util.Locale.US, r.costPercent)}% battery.", style = Type.normal.copy(color = Palette.text))
        Text(if (details) "Hide details" else "Details", style = Type.small.copy(color = Palette.accent),
            modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { details = !details }.padding(vertical = 8.dp))
        if (!details) return@Column
        Text("On screen or charging: ${time(r.openMs)}", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        Text("Fully connected: ${time(r.connectedMs)}", style = Type.small)
        Text("Light connection: ${time(r.savingMs)}", style = Type.small)
        Text("Resting: ${time(r.restingMs)}", style = Type.small)
        Text("Checks: ${r.pings}, ${r.wakeups} woke the phone", style = Type.small)
        Text("Reconnects: ${r.reconnects}", style = Type.small)
        Text("Server checks: ${r.serverPings}", style = Type.small)
        Text("Background checks: ${r.checks}", style = Type.small)
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
 * Linked devices, companion style: a tablet or computer shows this phone's chats and sends through it while this
 * phone is online. Contacts see nothing new. The tablet shows a QR, the phone scans it. Moving to another device below.
 */
@Composable
private fun LinkedDevices(app: Muninn) {
    LinkedDevicesPanel(app)
    // Move here (cap x1).
    MoveSection(app)
}

/** Muninn opens again from the start, in a fresh process (after Erase everything). */
private fun restartApp(ctx: android.content.Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component
    if (launch != null) try { ctx.startActivity(android.content.Intent.makeRestartActivityTask(launch)) } catch (_: Exception) {}
    Runtime.getRuntime().exit(0)
}
