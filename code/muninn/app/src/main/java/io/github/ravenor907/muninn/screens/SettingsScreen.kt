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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.draw.rotate
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
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
    /** A tab Settings asks for (Blocked people opens Contacts): MainActivity shows it and clears this. */
    var goTab by mutableStateOf("")
}

/**
 * Round 19: a lock just confirmed for Settings › Unlock password or Change lock. A tablet or DeX window that crosses the
 * two-pane width builds the page again, which used to ask for the lock again. Kept in memory only, for a minute, for
 * that one page; cleared when the page is left and when the lock goes up (AppLock.raise).
 */
object ProvenLock {
    @Volatile private var page = ""
    @Volatile private var at = 0L
    private fun now(): Long = try { android.os.SystemClock.elapsedRealtime() } catch (_: Throwable) { System.nanoTime() / 1_000_000 }
    fun mark(p: String) { page = p; at = now() }
    fun still(p: String): Boolean = page == p && at > 0 && now() - at in 0..60_000L
    fun clear() { page = ""; at = 0L }
}

/** One page of Settings: its title and the page Back goes to. */
private class Page(val id: String, val title: String, val parent: String = "")

/** The seven sections, in the order shown, plus Profile and Change lock. */
private val PAGES = listOf(
    Page("profile", "Profile"), Page("notify", "Notifications"), Page("lock", "Privacy and lock"), Page("calls", "Calls"),
    Page("look", "Look"), Page("battery", "Battery and data"), Page("devices", "Devices and backup"),
    Page("about", "Updates and about"), Page("lockset", "Change lock", "lock"), Page("unlockpw", "Unlock password", "lock"),
    // One level down (consolidation 2.13, 2.11, 2.15): each its own page, opened from a row.
    Page("linked", "Linked devices", "devices"), Page("move", "Move to a new device", "devices"),
    Page("aloud", "Read aloud", "look"), Page("labs", "Labs"), Page("tuning", "Voice tuning", "labs"),
)

/** Older page names (the chat list's Back up and Linked devices, CI) open the page that holds them now. */
internal fun settingsPage(id: String): String = when (id) {
    "backup", "files", "erase" -> "devices"
    "link" -> "linked"
    "access" -> "look"
    "voices", "readaloud" -> "aloud"
    "nearby" -> "lock"
    "more", "tv" -> ""
    else -> id
}

@Composable
fun SettingsScreen(app: Muninn, onSecure: () -> Unit) {
    val folder = settingsPage(SettingsNav.folder)
    val page = PAGES.firstOrNull { it.id == folder }
    androidx.activity.compose.BackHandler(enabled = folder.isNotEmpty()) { SettingsNav.folder = page?.parent ?: "" }
    // A Kids' Device's settings are a whole screen of their own: its menu, then each section.
    if (folder.startsWith("tv:")) {
        io.github.ravenor907.muninn.tv.ParentTvScreen(app, folder.removePrefix("tv:"), section = null, onBack = { SettingsNav.folder = "" })
        return
    }
    if (folder == "lockset") { ChangeLock(app); return }
    if (folder == "unlockpw") { ChangeUnlockPassword(app); return }
    androidx.compose.runtime.key(folder) {
    // At most 720 dp wide, centred: on a wide tablet a switch stays near its label.
    Column(Modifier.fillMaxSize().wrapContentWidth(androidx.compose.ui.Alignment.CenterHorizontally).widthIn(max = 720.dp).fillMaxWidth()
        .padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        if (page != null) FolderHeader(page.title) { SettingsNav.folder = page.parent }
        when (folder) {
            "" -> ShortList(app)
            "profile" -> ProfileScreen(app)
            "notify" -> NotifyPage(app)
            "lock" -> LockPage(app, onSecure)
            "calls" -> CallsPage(app)
            "look" -> LookPage(app)
            "battery" -> BatteryPage(app)
            "devices" -> DevicesPage(app)
            "about" -> AboutPage(app)
            "linked" -> LinkedDevicesPanel(app)
            "move" -> MoveSection(app)
            "aloud" -> ReadAloudPage()
            "labs" -> LabsPage(app)
            "tuning" -> VoiceTuningPage()
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** The short list: your profile, one row per Kids' Device, then the seven sections (and Labs when it's on). */
@Composable
private fun ShortList(app: Muninn) {
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val mine = tvs.values.filter { !it.disconnecting }
    val up by app.updater.state.collectAsState()
    Framed { MeRow(app) }
    if (mine.isNotEmpty()) Framed {
        mine.forEach { t -> NavRow("Kids' Device: " + app.chat.nameOf(t.key), icon = "tv") { SettingsNav.folder = "tv:" + t.key } }
    }
    Framed {
        NavRow("Notifications", icon = "bell") { SettingsNav.folder = "notify" }
        NavRow("Privacy and lock", icon = "lock") { SettingsNav.folder = "lock" }
        NavRow("Calls", icon = "call") { SettingsNav.folder = "calls" }
        NavRow("Look", icon = "palette") { SettingsNav.folder = "look" }
        NavRow("Battery and data", BatteryChoice.of(app)?.title ?: "Your own mix", icon = "battery") { SettingsNav.folder = "battery" }
        NavRow("Devices and backup", icon = "phone") { SettingsNav.folder = "devices" }
        NavRow("Updates and about", if (up.ready) "Muninn ${up.latest} is ready" else "", icon = "download") { SettingsNav.folder = "about" }
    }
    // Labs (consolidation 2.15): a row to its own page, like the other sections.
    if (Labs.on(app)) Framed { NavRow("Labs", icon = "info") { SettingsNav.folder = "labs" } }
}

/** The one "More" row at the bottom of a page: the rest of its settings, folded away until it's tapped. */
@Composable
internal fun MoreFold(content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Framed {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(onClickLabel = if (open) "Hide" else "Show", role = androidx.compose.ui.semantics.Role.Button) { open = !open }
            .padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("More", style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
            MIcon("forward", Palette.textMuted, 18.dp, Modifier.rotate(if (open) 90f else 0f))
        }
        if (open) content()
    }
}

/** A choice's name above its buttons, inside a card. */
@Composable
private fun RowLabel(text: String) = Text(text, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))

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

/** "Run in the background", off: one name and one row on Notifications and Battery and data (consolidation 2.12). */
@Composable
private fun RunInBackgroundRow(ctx: android.content.Context) =
    WarnRow("Run in the background", "Off: messages may come late") { io.github.ravenor907.muninn.net.MessageService.askUnrestricted(ctx) }

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

/**
 * "Show who messages are from?", asked once on the first open after updating, of anyone who never picked what
 * notifications show and so still has Nothing. Any answer is saved, Back too (as Nothing), so it never asks again;
 * from then on the choice lives only in Settings › Notifications. Never over a call or the lock screen.
 */
@Composable
internal fun WhoFromAsk(app: Muninn) {
    val id = app.identity
    var open by remember { mutableStateOf(!id.notifyChosen && id.notifyShows == io.github.ravenor907.muninn.net.Identity.Shows.NOTHING) }
    val call by io.github.ravenor907.muninn.net.call.CallManager.call.collectAsState()
    val room by io.github.ravenor907.muninn.net.call.RoomManager.room.collectAsState()
    if (!open || call != null || room != null) return
    fun pick(s: io.github.ravenor907.muninn.net.Identity.Shows) { id.notifyShows = s; open = false }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { pick(io.github.ravenor907.muninn.net.Identity.Shows.NOTHING) },
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        containerColor = Palette.panel,
        title = { Text("Show who messages are from?", style = Type.big) },
        text = { Text("Notifications say only “New message” now. Change it any time in Settings › Notifications.", style = Type.normal) },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Name only (recommended)", Modifier.fillMaxWidth(), Kind.Accent) { pick(io.github.ravenor907.muninn.net.Identity.Shows.NAME) }
                MButton("Name and message", Modifier.fillMaxWidth()) { pick(io.github.ravenor907.muninn.net.Identity.Shows.ALL) }
                MButton("Nothing", Modifier.fillMaxWidth()) { pick(io.github.ravenor907.muninn.net.Identity.Shows.NOTHING) }
            }
        },
    )
}

/** One short help line under something that isn't obvious. */
@Composable
private fun Help(text: String) = Text(text, style = Type.small, modifier = Modifier.padding(top = 4.dp))

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun posName(p: String) = when (p) { "top" -> "Top"; "middle" -> "Middle"; else -> "Lower" }

/**
 * The one battery choice. Messages still arrive on time with the first two; the switches each one sets are all
 * under Battery and data › More (Data saver on the page itself), for anyone who wants their own mix.
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
        // Consolidation 2.8: what's off and should be on comes first, each only while it's off.
        if (batteryOff) RunInBackgroundRow(ctx)
        if (fullOff) WarnRow("Full-screen calls", "Off: calls won't fill the screen") { openFullScreenSetting(ctx) }
        // What the last update did to full-screen calls (kept on this phone only, never sent).
        val fsiLast = remember { io.github.ravenor907.muninn.update.FullScreenKeep.last(ctx) }
        if (fsiLast != null) Help(fsiLast)
        val on = androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        PageRow("Notifications", if (on) "On" else "Off") {
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            } catch (_: Exception) {}
        }
        NotifyShowsChoice(shows) { shows = it; id.notifyShows = it }
        var reduce by remember { mutableStateOf(id.reduceNotifications) }
        ToggleRow("Reduce notifications", "One per chat until you open it", reduce) { reduce = it; id.reduceNotifications = it }
    }
    // Quiet hours: on, from and to, then (consolidation 2.8, moved up from More) Do Not Disturb and the mode.
    var quietOn by remember { mutableStateOf(app.power.quietOn) }
    var dnd by remember { mutableStateOf(app.power.followDnd) }
    var mode by remember { mutableStateOf(app.power.quietMode) }
    Framed("Quiet hours") {
        QuietHours(app, quietOn) { quietOn = it; app.power.quietOn = it }
        ToggleRow("Also during Do Not Disturb", "Checks as in quiet hours", dnd) { dnd = it; app.power.followDnd = it }
        if (quietOn || dnd) {
            RowLabel("Quiet hours mode")
            QuietMode(mode) { mode = it; app.power.quietMode = it }
        }
    }
    Framed("Always right away") { RightAwayRow(app) }
    MoreFold {
        // Mentions: "@" and my name, or one of these nicknames, in a group.
        var nick by remember { mutableStateOf(id.mentionNames) }
        RowLabel("Also mention me as")
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
    Framed("Theme") {
        val looks = io.github.ravenor907.muninn.ui.Looks.ALL
        // All three on a line; fewer a line at a large text size, so no name breaks mid-word.
        ChoiceRow(looks.size, Modifier.padding(vertical = 8.dp), minWidth = 100.dp) { i, m ->
            val (k, n) = looks[i]
            MButton(n, m, if (Access.look == k) Kind.Accent else Kind.Normal) { Access.look = k; id.look = k }
        }
        // Consolidation 2.11: Theme and Accent in one frame.
        RowLabel("Accent")
        AccentChoices(app)
    }
    Framed("Size") {
        RowLabel("Text size")
        val texts = listOf(1f, 1.15f, 1.3f, 1.5f)
        ChoiceRow(texts.size, Modifier.padding(top = 8.dp)) { i, m ->
            val v = texts[i]; MButton(pct(v), m, if (Access.text == v) Kind.Accent else Kind.Normal) { Access.text = v; id.textSize = v }
        }
        Help("Adds to your phone's font size.")
        RowLabel("Size of everything")
        val sizes = listOf(0.85f, 1f, 1.15f, 1.3f)
        ChoiceRow(sizes.size, Modifier.padding(vertical = 8.dp)) { i, m ->
            val v = sizes[i]; MButton(pct(v), m, if (Access.size == v) Kind.Accent else Kind.Normal) { Access.size = v; id.uiSize = v }
        }
    }
    Framed("Accessibility") {
        ToggleRow("High contrast", "Stronger outlines", Access.contrast) { Access.contrast = it; id.highContrast = it }
        ToggleRow("Reduce motion", "No sliding or fading", Access.reduceMotion) { Access.reduceMotion = it; id.reduceMotion = it }
        RowLabel("Color vision")
        io.github.ravenor907.muninn.ui.ColorVisionControls { id.colorVision = Access.colorVision }
        // Read aloud (consolidation 2.11): one name, one place; the row opens its own page.
        PageRow("Read aloud", readAloudRowValue()) { SettingsNav.folder = "aloud" }
    }
    // Daily Hávamál: off until it's turned on here (screens/DailyHavamal.kt).
    Framed("Daily Hávamál") { DailyHavSettings(app) }
    MoreFold {
        // Seasonal, over any look: the season, and what falls.
        io.github.ravenor907.muninn.ui.SeasonControls { id.season = Access.season; id.seasonGround = Access.seasonGround; id.seasonAnim = Access.seasonAnim }
        Spacer(Modifier.height(8.dp))
    }
}

/** Settings › Look › Read aloud: the page itself (screens/ReadAloudSettings.kt). */
@Composable
private fun ReadAloudPage() = Framed { ReadAloudSettings() }

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
    var nearbyRoom by remember { mutableStateOf(id.nearbyChat) }
    var keepNearby by remember { mutableStateOf(id.keepNearbyHistory) }
    var oneDay by remember { mutableStateOf(id.deleteAfter in 1 until 7 * DAY) }
    var hasLock by remember { mutableStateOf(app.vault.hasPassword) }
    var panic by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.enabled(ctx)) }
    var shake by remember { mutableStateOf(io.github.ravenor907.muninn.ui.Panic.shakeOn(ctx)) }
    var eraseOn by remember { mutableStateOf(app.vault.tries.erase) }
    val blocked by app.chat.blocked.collectAsState()
    Framed("Lock") {
        if (hasLock) PageRow("Change lock", if (app.vault.usesPattern) "Pattern" else "Password") { SettingsNav.folder = "lockset" }
        else {
            Text("No lock set.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 10.dp))
            MButton("Set a lock", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent) { SettingsNav.folder = "lockset" }
        }
        if (app.vault.hasPassword) {
            RowLabel("Lock again after")
            val afters = io.github.ravenor907.muninn.ui.AppLock.AFTERS
            ChoiceRow(afters.size, Modifier.padding(top = 6.dp, bottom = 4.dp)) { i, m ->
                val ms = afters[i]
                val label = if (ms == 0L) "Right away" else "${ms / 60_000L} min"
                MButton(label, m, if (lockAfter == ms) Kind.Accent else Kind.Normal) { lockAfter = ms; id.lockAfter = ms }
            }
            ToggleRow("Panic lock", if (onTv) "Lock instantly from the icon" else "Lock instantly from tile or icon", panic) {
                // Turning it off takes the current lock: someone else with the phone mustn't take the owner's way to lock it.
                if (it) { panic = true; io.github.ravenor907.muninn.ui.Panic.setEnabled(ctx, true) }
                else LockGate.ask(app, "Turn off panic lock") { panic = false; io.github.ravenor907.muninn.ui.Panic.setEnabled(ctx, false) }
            }
            // 1.42: the erase after too many wrong tries. A pattern's unlock password is on the Change lock page.
            ToggleRow("Erase after too many tries", if (eraseOn) "On: erases after the last wait" else "Off: waits only", eraseOn) {
                if (!it) { app.vault.tries.erase = false; eraseOn = false; return@ToggleRow }
                Ui.sure(Ask("Erase after too many tries?", "Wrong tries already make people wait: 5 minutes, then 1 hour, 12 hours and 24 hours. " +
                    "With this on, 5 more wrong tries after the 24-hour wait erase everything on this phone at once, for good: chats, contacts and keys. " +
                    "Children or anyone guessing can set it off.\n\nMake a backup first (Settings › Devices and backup). Without one, an erase can't be undone. " +
                    "Your saved backup files are kept, and Forgot it? puts one back. Files you saved to Downloads stay.", "Turn on", danger = true) {
                    LockGate.ask(app, "Erase after too many tries") { app.vault.tries.erase = true; eraseOn = true; Ui.say("Erase after too many tries is on. Keep a recent backup.") }
                })
            }
        }
    }
    Framed("Privacy") {
        ToggleRow("Send read receipts", "Delivered still shows", receipts) { receipts = it; id.readReceipts = it }
        ToggleRow("Active status", "Off: nobody sees it, both ways", activeOn) { activeOn = it; app.chat.setActiveStatus(it) }
        // Ultra privacy: link previews are off, said on the switch (the saved choice comes back when it's off).
        val ultraTor by io.github.ravenor907.muninn.net.UltraPrivacy.state.collectAsState()
        val ultraOn = ultraTor != io.github.ravenor907.muninn.net.UltraPrivacy.Tor.OFF
        ToggleRow("Link previews", if (ultraOn) io.github.ravenor907.muninn.net.UltraPrivacy.OFF_NOTE else "The site sees your address", previews && !ultraOn) {
            if (ultraOn) Ui.say(io.github.ravenor907.muninn.net.UltraPrivacy.OFF_NOTE + ".") else { previews = it; id.linkPreviews = it }
        }
        ToggleRow("Block screenshots", "Also hides it in recents", secure) { secure = it; id.secureScreen = it; onSecure() }
        // Consolidation 2.9: Keep messages joins Privacy.
        RowLabel("Keep messages")
        ChoiceRow(2, Modifier.padding(top = 8.dp), minWidth = 120.dp) { i, m ->
            val day = i == 1
            MButton(if (day) "1 day" else "Each chat's limit", m, if (oneDay == day) Kind.Accent else Kind.Normal) {
                oneDay = day; id.deleteAfter = if (day) DAY else 0L; app.chat.pruneSoon()
            }
        }
        Help("1 day: every chat, on both sides.")
        Spacer(Modifier.height(8.dp))
    }
    Framed("Nearby") {
        // One switch for everything nearby: off, nothing is announced or scanned for and nobody nearby is listed.
        if (io.github.ravenor907.muninn.net.UltraPrivacy.on) Text(io.github.ravenor907.muninn.net.UltraPrivacy.NEARBY_NOTE, style = Type.small, modifier = Modifier.padding(top = 6.dp))
        ToggleRow("Nearby", "Off: contacts only", nearbyOn) {
            nearbyOn = it; id.visibleNearby = it; app.net.nearbyChanged(); app.ble.hiddenChanged()
        }
        if (nearbyOn) {
            ToggleRow("Nearby room", "A chat for everyone close by", nearbyRoom) { nearbyRoom = it; id.nearbyChat = it; app.net.announce() }
            ToggleRow("Keep Nearby messages", "Off: cleared when Muninn closes", keepNearby) {
                keepNearby = it; id.keepNearbyHistory = it; if (!it) app.chat.clearNearby()
            }
        }
    }
    // Ultra privacy (1.42): Tor, off by default; two screens before it turns on. Rare and advanced: last before More.
    UltraPrivacySection(app)
    MoreFold {
        if (app.vault.usesPattern) {
            RowLabel("Pattern position")
            val places = listOf("top", "middle", "lower")
            ChoiceRow(places.size, Modifier.padding(top = 6.dp, bottom = 4.dp)) { i, m ->
                val p = places[i]; MButton(posName(p), m, if (pos == p) Kind.Accent else Kind.Normal) { pos = p; id.patternPos = p }
            }
            ToggleRow("Hide the pattern as I draw", "", !trail) { trail = !it; id.patternTrail = !it }
        }
        if (app.vault.hasPassword && panic && !onTv && io.github.ravenor907.muninn.ui.Panic.canShake(ctx))
            ToggleRow("Shake to lock", "Shake hard 3 times", shake) { shake = it; io.github.ravenor907.muninn.ui.Panic.setShake(ctx, it) }
        ToggleRow("Private keyboard", "Keyboard won't learn typing", privateKeys) {
            privateKeys = it; id.privateKeyboard = it; io.github.ravenor907.muninn.ui.PrivateKeyboard.on = it
        }
        PageRow("Blocked people", if (blocked.isEmpty()) "Nobody" else "${blocked.size}") { SettingsNav.goTab = "contacts" }
        // Destructive, so last, red, and asked first.
        // Held, like Delete chat, and then the current lock: nobody else with the phone can take the lock off.
        if (hasLock) io.github.ravenor907.muninn.ui.MenuRow("lock", "Turn off the lock", danger = true) {
            Ui.sure(Ask("Turn off the lock?", "Anyone with this phone can read your chats.", "Hold to turn off", danger = true, hold = true,
                spoken = "Hold for 5 seconds to turn off the lock") {
                LockGate.ask(app, "Turn off the lock") {
                    try { app.vault.setPassword(null) } catch (_: Exception) {}
                    hasLock = app.vault.hasPassword
                    if (!hasLock) {
                        // Round 19: the erase switch and the count go with the lock, so a lock set later starts with
                        // erase off (and its own warning) and no old wrong tries.
                        try { app.vault.tries.erase = false; app.vault.tries.right() } catch (_: Exception) {}
                        eraseOn = false
                        io.github.ravenor907.muninn.screens.skipLock(app); Ui.say("The lock is off.")
                    }
                }
            })
        }
    }
}

/** Settings › Privacy and lock › Unlock password: the current lock first, then the new one typed twice and asked once more from memory. */
@Composable
private fun ChangeUnlockPassword(app: Muninn) {
    var proven by remember { mutableStateOf(ProvenLock.still("unlockPassword")) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FolderHeader("Unlock password") { ProvenLock.clear(); SettingsNav.folder = "lock" }
        if (!proven) ConfirmLock(app, "Unlock password", Modifier.fillMaxSize(), onCancel = { SettingsNav.folder = "lock" }) { proven = true; ProvenLock.mark("unlockPassword") }
        else UnlockPasswordSteps(app, recall = false) { ProvenLock.clear(); SettingsNav.folder = "lock" }
    }
}

/** Settings › Privacy and lock › Change lock: a page of its own, so the pattern sits where it's set to. */
@Composable
private fun ChangeLock(app: Muninn) {
    // A lock already set is asked for first: nobody else with the phone can put in a lock the owner doesn't know.
    var proven by remember { mutableStateOf(!app.vault.hasPassword || ProvenLock.still("changeLock")) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        FolderHeader(if (app.vault.hasPassword) "Change lock" else "Set a lock") { ProvenLock.clear(); SettingsNav.folder = "lock" }
        if (!proven) ConfirmLock(app, "Change lock", Modifier.fillMaxSize(), onCancel = { SettingsNav.folder = "lock" }) { proven = true; ProvenLock.mark("changeLock") }
        else LockSetup(app, onCancel = { ProvenLock.clear(); SettingsNav.folder = "lock" }, fill = true, header = {
            // Consolidation 2.9: a pattern's unlock password lives with the pattern, here. The lock was just proven,
            // so the Unlock password page doesn't ask for it again.
            if (app.vault.hasPassword && app.vault.usesPattern) PageRow("Unlock password", if (app.vault.hasSpare) "Change" else "Not set") {
                ProvenLock.mark("unlockPassword"); SettingsNav.folder = "unlockpw"
            }
        }) { ProvenLock.clear(); SettingsNav.folder = "lock" }
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
        Help(choice?.help ?: "Your own mix, under More.")
        Spacer(Modifier.height(8.dp))
        if (stay && !free) RunInBackgroundRow(ctx)
    }
    Framed("Data") {
        ToggleRow("Data saver", "Big files wait for Wi-Fi", data) { data = it; app.power.dataSaver = it; byHand() }
    }
    MoreFold {
        RowLabel("Check for messages")
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
                (if (adaptive) " and turn off Check less when it's quiet" else "") + " so it doesn't wait.")
        }
        if (stay) ToggleRow("Save when the screen is off", "Saves battery", saver) { saver = it; app.power.saver = it; byHand() }
        ToggleRow("Save more on low battery", "Below 30%", low) { low = it; app.power.lowBatterySave = it; byHand() }
        ToggleRow("Check less when it's quiet", "After 3 hours without messages", adaptive) { adaptive = it; app.power.adaptive = it; byHand() }
        // Moved here from Privacy and lock (consolidation 2.9): it's a battery choice.
        var closeBy by remember { mutableStateOf(app.power.closeBy) }
        ToggleRow("Look for Nearby in the background", "Uses more battery", closeBy) { closeBy = it; app.power.closeBy = it }
        RowLabel("Today")
        BatteryReport(app)
    }
    androidx.compose.runtime.LaunchedEffect(check) { app.power.refresh() }
}

@Composable
private fun CallsPage(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var ask by remember { mutableStateOf(app.identity.roomAsk) }
    var noise by remember { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.noiseRemoval(ctx)) }
    var internet by remember { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.internetCalls(ctx)) }
    Framed {
        // Owner decision (round 19): no calls at all while Ultra privacy is on, said where calls are set.
        val ultraTor by io.github.ravenor907.muninn.net.UltraPrivacy.state.collectAsState()
        if (ultraTor != io.github.ravenor907.muninn.net.UltraPrivacy.Tor.OFF)
            Text(io.github.ravenor907.muninn.net.UltraPrivacy.CALLS_OFF, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
        ToggleRow("Internet calls", "Off: close by only", internet) {
            internet = it; io.github.ravenor907.muninn.net.call.CallManager.setInternetCalls(ctx, it)
        }
        ToggleRow("Noise removal", "Takes out background noise", noise) {
            noise = it; io.github.ravenor907.muninn.net.call.CallManager.setNoiseRemoval(ctx, it)
        }
        ToggleRow("Ask before joining a voice room", "Pick mic and sound first", ask) { ask = it; app.identity.roomAsk = it }
        Help("Voice rooms use more battery than chat. With the screen off, Muninn keeps only what the sound needs.")
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Devices and backup (consolidation 2.13): Linked devices and Move to a new device (each a row to its own page) and
 * this device's name, then Backup, where files go, and App storage and erasing under More.
 */
@Composable
private fun DevicesPage(app: Muninn) {
    val id = app.identity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val companions by io.github.ravenor907.muninn.net.Link.companions.collectAsState()
    val phone by io.github.ravenor907.muninn.net.Link.phone.collectAsState()
    var device by remember { mutableStateOf(id.deviceName) }
    Framed("Devices") {
        // Linked devices, companion style: a tablet or computer shows this phone's chats and sends through it while
        // this phone is online. Contacts see nothing new.
        PageRow("Linked devices", phone?.name ?: when (companions.size) { 0 -> "None yet"; 1 -> companions[0].name; else -> "${companions.size} devices" }) {
            SettingsNav.folder = "linked"
        }
        PageRow("Move to a new device", "Move here, or move from here") { SettingsNav.folder = "move" }
        RowLabel("This device's name")
        Row(Modifier.padding(vertical = 8.dp)) {
            Field(device, { device = it.take(40) }, "Muninn on my phone", Modifier.weight(1f))
            MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = device.isNotBlank() && device != id.deviceName) { id.deviceName = device; app.net.announce(); Ui.say("Saved.") }
        }
    }
    Section("Backup")
    BackupPanel(app)
    // chosenTree first: a picked folder that went away resets the name shownRoot gives.
    var picked by remember { mutableStateOf(io.github.ravenor907.muninn.net.Received.chosenTree(ctx) != null) }
    var where by remember { mutableStateOf(io.github.ravenor907.muninn.net.Received.shownRoot()) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            io.github.ravenor907.muninn.net.Received.choose(ctx, uri)
            where = io.github.ravenor907.muninn.net.Received.shownRoot(); picked = io.github.ravenor907.muninn.net.Received.chosenTree(ctx) != null
        }
    }
    Framed("Files") {
        PageRow("Save files to", where) { try { io.github.ravenor907.muninn.ui.Picking.launch(pick, null) } catch (_: Exception) { Ui.say("This phone has no folder picker.", true) } }
        if (picked) PageRow("Use Downloads again", "") {
            io.github.ravenor907.muninn.net.Received.choose(ctx, null)
            where = io.github.ravenor907.muninn.net.Received.shownRoot(); picked = false
        }
        Help("Each chat gets its own folder inside it.")
        Spacer(Modifier.height(8.dp))
    }
    Framed("Send media") {
        // Automatic: straight to them, then the encrypted relay. Most private: over Tor, slower. Each chat can have its own.
        var route by remember { mutableStateOf(app.chat.mediaRouteAll) }
        io.github.ravenor907.muninn.ui.ChoiceRow(io.github.ravenor907.muninn.net.MediaRoute.CHOICES.size) { i, m ->
            val c = io.github.ravenor907.muninn.net.MediaRoute.CHOICES[i]
            MButton(io.github.ravenor907.muninn.net.MediaRoute.label(c), m, if (route == c) Kind.Accent else Kind.Normal) { route = c; app.chat.mediaRouteAll = c }
        }
        val note = app.chat.meter.note()
        Help(note.ifEmpty { "Family and trusted: full quality" })
        Spacer(Modifier.height(8.dp))
    }
    MoreFold {
        PageRow("App storage", "What Muninn keeps that can go") { SettingsNav.goChat = "appstorage" }
        EraseRows(app)
    }
}

/** Updates and about: the one place Muninn itself is updated. */
@Composable
private fun AboutPage(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val up by app.updater.state.collectAsState()
    var auto by remember { mutableStateOf(app.identity.autoUpdate) }
    Framed {
        Text("Muninn ${app.updater.current}", style = Type.big, modifier = Modifier.padding(top = 10.dp))
        if (up.status.isNotEmpty()) Text(up.status, style = Type.small)
        if (up.busy && up.progress > 0f) Bar(up.progress, Modifier.padding(top = 6.dp))
        MButton(if (up.ready) "Update now" else "Check now", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent, enabled = !up.busy) {
            if (up.ready) app.updater.download() else app.updater.check()
        }
        ToggleRow("Update automatically", "When Muninn opens", auto) { auto = it; app.identity.autoUpdate = it }
        // Each Kids' Device's version, with a tap to its own updates (filled in on the Kids' Device side).
        io.github.ravenor907.muninn.tv.TvUpdateStatusRow(app)
    }
    // The update channel is on the Labs page now (consolidation 2.14, 2.15).
    MoreFold {
        // Hands Muninn itself to a phone close by (Quick Share or Bluetooth), no internet needed.
        PageRow("Share the app", "") { io.github.ravenor907.muninn.ui.ShareApp.share(ctx) }
        Text("Private chat and file sharing. Made by Ravenor907. GPL-3.0.", style = Type.small, modifier = Modifier.padding(vertical = 10.dp))
        // Read aloud's voices and speech engine, and their licenses, under the same heading as Huginn's.
        RowLabel("Credits and licenses")
        VoiceCredits()
    }
}

/** Labs only: Stable or Pre-release, and Go back to stable while on Pre-release (on the Labs page). */
@Composable
internal fun UpdateChannel(app: Muninn) {
    Labs.changed
    val pre = app.identity.preRelease
    Framed("Update channel") {
        ChoiceRow(2, Modifier.padding(top = 8.dp), minWidth = 120.dp) { i, m ->
            if (i == 0) MButton("Stable", m, if (!pre) Kind.Accent else Kind.Normal) { Labs.setPreRelease(app, false) }
            else MButton("Pre-release", m, if (pre) Kind.Accent else Kind.Normal) { Labs.setPreRelease(app, true) }
        }
        Help(if (pre) "Updates include pre-release builds." else "Updates are stable releases only.")
        Spacer(Modifier.height(6.dp))
        if (pre) io.github.ravenor907.muninn.ui.MenuRow("undo", "Go back to stable") { Labs.goBack(app) }
    }
}

/** Delete all messages and Erase everything: red, last on Devices and backup › More, each held to confirm. */
@Composable
private fun EraseRows(app: Muninn) {
    var erased by remember { mutableStateOf(false) }
    if (erased) {
        // "Erased", then Muninn starts again as new. Back does nothing meanwhile: the erase and the restart run on
        // their own (EraseRun), not in this screen, so nothing can stop them halfway.
        androidx.activity.compose.BackHandler {}
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            MIcon("check", Palette.accent, 22.dp)
            Text("Erased", style = Type.big, modifier = Modifier.padding(start = 12.dp))
        }
        Text("Muninn starts again.", style = Type.small, modifier = Modifier.padding(bottom = 12.dp))
        return
    }
    io.github.ravenor907.muninn.ui.MenuRow("delete", "Delete all messages", danger = true) {
        Ui.sure(Ask("Delete all messages?", "Contacts stay. This can't be undone.", "Hold to delete", danger = true, hold = true, spoken = "Hold for 5 seconds to delete all messages") {
            app.chat.deleteAll(); Ui.say("All messages deleted.")
        })
    }
    io.github.ravenor907.muninn.ui.MenuRow("warning", "Erase everything", danger = true) {
        Ui.sure(Ask("Erase everything?", "Messages, contacts, keys and settings. This can't be undone. Files you saved to Downloads stay.",
            "Hold to erase", danger = true, hold = true, spoken = "Hold for 5 seconds to erase everything") { erased = true; EraseRun.start(app) })
    }
}

/**
 * The "Always right away" list in one row ("Family + 2 people"): whose messages a low battery, quiet hours and
 * checking less never hold back. It starts as the Family folder. Tap to see it and take anyone off; adding is in
 * each chat's menu, under Notifications.
 */
@Composable
private fun RightAwayRow(app: Muninn) {
    val ready by app.ready.collectAsState()
    val ra = app.power.rightAway
    if (!ready || ra == null) return
    val st by ra.all.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
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
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(summary, style = Type.normal.copy(color = Palette.text))
            Text("Even when saving battery", style = Type.small)
        }
        Text(if (open) "Hide" else "Show", style = Type.small.copy(color = Palette.accent), modifier = Modifier.padding(start = 8.dp))
    }
    if (!open) return
    if (keys.isEmpty()) Text("Family is on it by default.", style = Type.small.copy(color = Palette.textMuted))
    keys.sortedBy { name(it).lowercase() }.forEach { k ->
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(name(k) + if (k in family) " (Family)" else "", style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
            io.github.ravenor907.muninn.ui.IconAction("close", "Take ${name(k)} off", Palette.textMuted, size = 18.dp) { app.power.setRightAway(k, false) }
        }
    }
    Help("To add someone, open their chat's menu, then Notifications.")
    Spacer(Modifier.height(8.dp))
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

private fun hour(h: Int) = when (h) { 0 -> "12 AM"; 12 -> "12 PM"; in 1..11 -> "$h AM"; else -> "${h - 12} PM" }

/** Quiet hours: on, and from and to on the hour (Muninn saves the most then). Do Not Disturb and its mode follow in the same frame. */
@Composable
private fun QuietHours(app: Muninn, on: Boolean, setOn: (Boolean) -> Unit) {
    var from by remember { mutableStateOf(app.power.quietFrom) }
    var to by remember { mutableStateOf(app.power.quietTo) }
    ToggleRow("Quiet hours", if (on) "${hour(from)} to ${hour(to)}" else "Check less overnight", on) { setOn(it) }
    if (!on) return
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
    Spacer(Modifier.height(8.dp))
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
 * Erase everything, run to the end: on its own thread, not tied to any screen, so Back, a tab or the lock can't stop it
 * halfway (with the key gone but chats still in memory). Saves still waiting are dropped with the key (VaultCore.forget),
 * then Muninn starts again as new.
 */
private object EraseRun {
    @Volatile private var running = false

    fun start(app: Muninn) {
        if (running) return
        running = true
        val ctx = app.applicationContext
        Thread {
            try { app.vault.eraseAll() } catch (_: Exception) {}
            // A moment for "Erased" to be read.
            try { Thread.sleep(1500) } catch (_: Exception) {}
            restartApp(ctx)
        }.start()
    }
}

/** Muninn opens again from the start, in a fresh process (after Erase everything). */
private fun restartApp(ctx: android.content.Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component
    if (launch != null) try { ctx.startActivity(android.content.Intent.makeRestartActivityTask(launch)) } catch (_: Exception) {}
    Runtime.getRuntime().exit(0)
}
