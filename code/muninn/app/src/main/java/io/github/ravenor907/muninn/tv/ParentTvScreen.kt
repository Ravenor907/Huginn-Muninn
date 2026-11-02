package io.github.ravenor907.muninn.tv

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.AppFiles
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.HoldToConfirm
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one Kids' Device page on a parent's phone, one per device (TV, tablet or phone), reached from Settings and from the
 * device's chat menu ("Kids' Device settings"). Its header has the device's Muninn version, how its update is going and Update now; then one row per part,
 * each opening its own screen. The rarely used parts are under More. The same ids open a part straight from elsewhere:
 * `ParentTvScreen(app, tvKey, section = id)` (an id it doesn't know, such as "page", opens the page itself).
 */
object ParentTvMenu {
    /** (id, label) on the page, in order. */
    val items: List<Pair<String, String>> = listOf(
        "people" to "Who it can talk to",
        "parents" to "Parents",
        "chatting" to "Chatting",
        "sound" to "Calls and mic",
        "quick" to "Quick messages",
        "quiet" to "Quiet times",
        "lock" to "Parent PIN",
        "havamal" to "Daily Hávamál",
        "apps" to "Apps",
        "updates" to "Updates",
        "more" to "More",
    )

    /** (id, label) under More, in order. Disconnect is last. */
    val more: List<Pair<String, String>> = listOf(
        "names" to "Names",
        "keep" to "Messages stay for",
        "permissions" to "Device permissions",
        "storage" to "Storage",
        "disconnect" to "Disconnect",
    )

    /** The label for [id], or null if it isn't one of [items] or [more]. */
    fun label(id: String?): String? = (items + more).firstOrNull { it.first == id }?.second
}

/** The TV's line: "Muninn 1.33 · Up to date" (cap u1), or what an older TV's status said. */
fun tvUpdateLine(app: Muninn, t: ManagedTv): String =
    TvUpd.line(t.app, t.upd1, TvUpd.behind(t.app, app.updater.current, app.updater.state.value.latest), TvRules.updateText(t.update))

/**
 * Update now on the phone: u1 asks the device and shows each step; an older device (k2) can still be asked to look for an
 * update itself; one older still needs the steps on the device ([updateSteps]).
 */
fun askTvUpdate(app: Muninn, t: ManagedTv) {
    when {
        TvParent.updateNow(app, t.key) -> Ui.say("Asked it to update. It starts once the device is on and online.")
        TvParent.updateTv(app, t.key) -> Ui.say("Asked it to look for an update. It installs once the device is on and online.")
        else -> Ui.say(TvUpd.NEEDS_FIRST, true)
    }
}

/** Update now can reach the device (u1, or the older k2 "update"). */
fun canAskUpdate(app: Muninn, t: ManagedTv): Boolean = TvParent.u1(app, t.key) || TvParent.k2(app, t.key)

/** What a parent does on a device that is behind, exactly, for its kind. After that one update, Update now works from here. */
fun updateSteps(app: Muninn, t: ManagedTv): String {
    val tv = t.dev == KidRules.TV || t.dev.isEmpty()
    val press = if (tv) "press OK on the remote" else "tap Update"
    val first = if (TvParent.u1(app, t.key)) "" else if (TvParent.k2(app, t.key)) "Update now asks it to look for an update. " else ""
    return first + "On the ${if (tv) "TV" else "device"}: open Muninn and leave it on screen. When it shows \"Ask a grown-up\", $press, then Install when Android asks. " +
        "If Install updates is off, turn it on there first (Android asks once). After that one update, Update now works from here."
}

/** "TV", "Tablet", "Phone" (or "Device" while not known): what the page says this Kids' Device is. */
fun kidDeviceKind(app: Muninn, t: ManagedTv): String = KidRules.deviceName(t.dev, TvParent.k11(app, t.key)).replaceFirstChar { it.uppercase() }

/** What each device permission is called on the phone, in [TvRules.PERMS] order. */
private val PERM_NAMES = mapOf("mic" to "Microphone", "notify" to "Notifications", "overlay" to "Show over other apps", "install" to "Install updates")

private const val UPDATE_FIRST = TvUpd.NEEDS_FIRST

/** What kids' mode does and doesn't do on a tablet or phone, and how to lock it in fully. */
private const val FULL_LOCK = "Kids' mode keeps the kids in Muninn's own screens. On a tablet or phone they can still press Home, open " +
    "other apps, or clear Muninn's data in Android's Settings. For a full lock, also turn on App pinning on the device (Settings, " +
    "Security, App pinning, with \"Ask for PIN before unpinning\" on), then pin Muninn from the recent apps screen; or use Google " +
    "Family Link. If Muninn on it is cleared or removed, this page shows when it was last seen, and your phone tells you once " +
    "it hasn't heard from it for a day and a half."

/** An ask from the device to open its parent settings: Unlock or Not now, until it runs out (10 minutes, as on the device). */
@Composable
private fun UnlockCard(app: Muninn, t: ManagedTv, tvName: String) {
    val asks by TvParent.unlockAsks.collectAsState()
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(15_000); value = System.currentTimeMillis() } }
    if (asks[t.key] == null) return
    val a = TvParent.unlockAsk(t.key, now)
    if (a == null) { LaunchedEffect(t.key) { TvParent.answerUnlock(app, t.key, false) }; return }
    val at = android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(a.at))
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(Corner).border(1.dp, Palette.gold, Corner).padding(14.dp)) {
        Text("Unlock $tvName?", style = Type.big.copy(fontSize = 17.sp))
        Text("Asked at $at. " + TvParent.unlockText(tvName), style = Type.small, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Unlock", kind = Kind.Accent) { TvParent.answerUnlock(app, t.key, true) }
            MButton("Not now") { TvParent.answerUnlock(app, t.key, false) }
        }
    }
}
private const val INSTALLS_OFF = "The device can't install apps. Turn on Install updates in Updates."

/**
 * A Kids' Device's page on a parent's phone. [section]: null (or an id it doesn't know) shows the page; an id of
 * [ParentTvMenu.items] or [ParentTvMenu.more] opens that part. Back from a part returns to where it was opened from
 * (the page, or More); Back from the page, or from a [section] opened straight from elsewhere, calls [onBack].
 * Every change goes to the TV as a new signed config.
 */
@Composable
fun ParentTvScreen(app: Muninn, tvKey: String, section: String? = null, onBack: () -> Unit) {
    val tvs by TvParent.tvs.collectAsState()
    val t = tvs[tvKey]
    val direct = section?.takeIf { ParentTvMenu.label(it) != null }
    // The parts opened, in order (More, then one of its parts). A part opened straight from elsewhere goes back there.
    var stack by remember(section) { mutableStateOf(listOfNotNull(direct)) }
    val open = stack.lastOrNull()
    fun back() { if (stack.size > (if (direct != null) 1 else 0)) stack = stack.dropLast(1) else onBack() }
    BackHandler { back() }
    if (t == null || t.disconnecting) { LaunchedEffect(Unit) { onBack() }; return }
    val contacts by app.contacts.all.collectAsState()
    val k3 = TvParent.k3(app, tvKey)
    // The TV's storage, level and permissions (k3, k8): asked when this opens and when a part that shows them opens.
    LaunchedEffect(tvKey, k3, open) { TvParent.askUsage(app, tvKey) }
    val tvName = contacts.firstOrNull { it.chatKey == tvKey }?.shown ?: "Kids' Device"

    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", "Back") { back() }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(ParentTvMenu.label(open) ?: tvName, style = Type.big, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The kind of device (TV, tablet, phone), when it was last heard from (green within a day), and whether
                    // a change is still on its way to it.
                    val ctx = LocalContext.current
                    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
                    val recent = t.seenAt > 0 && now - t.seenAt < 86_400_000L
                    val clock = { ms: Long -> android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms)) }
                    val seen = KidRules.lastSeen(t.seenAt, now, clock) { ms -> android.text.format.DateFormat.getMediumDateFormat(ctx).format(java.util.Date(ms)) + " " + clock(ms) }
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (recent) Palette.online else Palette.textFaint))
                    Spacer(Modifier.width(6.dp))
                    Text(kidDeviceKind(app, t) + " · " + seen + (if (t.upToDate) "" else " · Changes waiting to go"),
                        style = Type.small.copy(fontSize = 13.sp, color = if (recent) Palette.online else Palette.textMuted), maxLines = 2)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (open) {
                null -> { UnlockCard(app, t, tvName); UpdateHeader(app, t); Menu(app, t) { stack = stack + it }; if (t.dev == KidRules.TABLET || t.dev == KidRules.PHONE) Note(FULL_LOCK) }
                "people" -> People(app, t)
                "parents" -> Parents(app, t)
                "chatting" -> Chatting(app, t)
                "quiet" -> Quiet(app, t)
                "lock" -> Lock(app, t)
                "havamal" -> DailyHavamal(app, t)
                "quick" -> QuickMessages(t)
                "sound" -> Sound(app, t)
                "names" -> Names(app, t, tvName)
                "apps" -> Apps(app, t)
                "updates" -> Updates(app, t)
                "more" -> More(app, t, tvName) { stack = stack + it }
                "permissions" -> Permissions(app, t)
                "storage" -> Storage(app, t)
                "keep" -> Keep(app, t)
                "disconnect" -> Disconnect(t.key, onBack)
            }
        }
    }
}

// ------------------------------------------------------------------ the menu

@Composable
private fun Menu(app: Muninn, t: ManagedTv, onOpen: (String) -> Unit) {
    val asks by TvAppAsks.items.collectAsState()
    val k11 = TvParent.k11(app, t.key)
    for ((id, label) in ParentTvMenu.items) {
        val value = when (id) {
            "people" -> "${TvParent.people(app, t).size + t.waiting.size}"
            "parents" -> "${t.parents.size}"
            "quick" -> "${t.quick.size}"
            "sound" -> if (t.calls) "Calls on" else "Calls off"
            // k11: an older device can't take these yet.
            "chatting" -> if (!k11) UPDATE_FIRST else KidRules.levelName(t.chat)
            "quiet" -> if (!k11) UPDATE_FIRST else if (t.quiet.isEmpty()) "Off" else t.quiet.joinToString(", ") { KidRules.quietName(it.n) }
            "lock" -> if (!k11) UPDATE_FIRST else if (t.pin == null) "Not set" else "Set"
            // k12: an older device can't show it yet.
            "havamal" -> if (!TvParent.k12(app, t.key)) UPDATE_FIRST else if (t.hav) "On" else "Off"
            "apps" -> asks.let { TvAppAsks.of(app, t.key).count { !it.answered } }.let { n -> if (n > 0) "$n waiting for you" else "" }
            "updates" -> if (t.upd) "Auto-update on" else "Auto-update off"
            else -> ""
        }
        MenuRow(label, value) { onOpen(id) }
    }
}

/** The page's header: the TV's Muninn and how its update is going, and Update now (cap u1). */
@Composable
private fun UpdateHeader(app: Muninn, t: ManagedTv) {
    val can = TvParent.u1(app, t.key)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tvUpdateLine(app, t), style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        MButton("Update now", Modifier.padding(start = 12.dp), Kind.Accent, enabled = canAskUpdate(app, t)) { askTvUpdate(app, t) }
    }
    if (!can) Note(updateSteps(app, t))
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(Palette.hairline))
}

/** Updates: how the TV's update is going, Update now, the Install updates permission, and Auto-update. */
@Composable
private fun Updates(app: Muninn, t: ManagedTv) {
    val can = TvParent.u1(app, t.key)
    val k7 = TvParent.k7(app, t.key)
    val k8 = TvParent.k8(app, t.key)
    // "Last check: 5 min ago" moves on by itself.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { kotlinx.coroutines.delay(30_000); value = System.currentTimeMillis() } }
    Text(tvUpdateLine(app, t), style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
    t.upd1?.let { st ->
        if (st.s == TvUpd.DOWNLOADING) io.github.ravenor907.muninn.ui.Bar((st.p ?: 0) / 100f, Modifier.padding(top = 6.dp), Palette.gold)
        Text(TvUpd.lastCheck(st.at, now), style = Type.small, modifier = Modifier.padding(top = 4.dp))
    }
    MButton("Update now", Modifier.fillMaxWidth().padding(vertical = 8.dp), Kind.Accent, enabled = canAskUpdate(app, t)) { askTvUpdate(app, t) }
    if (!can) Note(updateSteps(app, t))
    // The TV installs updates by itself only while this is on.
    val installOff = t.off?.contains("install")
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(PERM_NAMES["install"] ?: "Install updates", style = Type.normal.copy(color = Palette.text))
            if (installOff != null) Text(if (installOff) "Off" else "On", style = Type.small.copy(fontSize = 13.sp, color = if (installOff) Palette.textMuted else Palette.online))
        }
        if (installOff != false) MButton("Ask device", enabled = k8) {
            if (TvParent.askAllow(app, t.key, "install")) Ui.say("Asked the device") else Ui.say(UPDATE_FIRST, true)
        }
    }
    TvToggle("Auto-update", "", t.upd, enabled = k7) { on -> TvParent.setUpd(t.key, on) }
    if (!k7) Note(UPDATE_FIRST)
}

/** More: the TV's names, then the rarely used parts, Disconnect TV last. */
@Composable
private fun More(app: Muninn, t: ManagedTv, tvName: String, onOpen: (String) -> Unit) {
    Names(app, t, tvName)
    for ((id, label) in ParentTvMenu.more) {
        if (id == "names") continue
        val value = when (id) {
            "keep" -> keepName(t.keep)
            "permissions" -> t.off?.filter { it != "install" }?.let { off -> if (off.isEmpty()) "All on" else "${off.size} off" } ?: ""
            "storage" -> if (t.used >= 0) sizeText(t.used) else ""
            else -> ""
        }
        MenuRow(label, value, danger = id == "disconnect") { onOpen(id) }
    }
}

@Composable
private fun MenuRow(title: String, value: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.normal.copy(color = when { !enabled -> Palette.textFaint; danger -> Palette.danger; else -> Palette.text }),
            modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = Type.normal.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
        if (!danger) MIcon("forward", Palette.textFaint, 18.dp, Modifier.padding(start = 8.dp))
    }
}

/** A row that only says something (no chevron, nothing to press). */
@Composable
private fun InfoRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = Type.normal.copy(color = Palette.textMuted),
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
    }
}

/** A switch row that is off (faded, does nothing) while the TV's app can't take it yet. */
@Composable
private fun TvToggle(title: String, detail: String, on: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!on) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.big.copy(fontSize = 16.sp, color = if (enabled) Palette.text else Palette.textFaint))
            if (detail.isNotEmpty()) Text(detail, style = Type.small)
        }
        Switch(on, if (enabled) onChange else null, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = Palette.ground,
            checkedTrackColor = Palette.accent, uncheckedThumbColor = Palette.textMuted, uncheckedTrackColor = Palette.raised,
            uncheckedBorderColor = Palette.lineMuted))
    }
}

// ------------------------------------------------------------------ the parts

/**
 * Who the device can talk to (messages and calls, both ways): a minus on each to take them off, Add, its code, and a
 * family group. Only a parent adds people: the device itself has no way to add anyone.
 */
@Composable
private fun People(app: Muninn, t: ManagedTv) {
    val context = LocalContext.current
    val contacts by app.contacts.all.collectAsState()
    val met by app.chat.met.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    val people = TvParent.people(app, t)
    var naming by remember { mutableStateOf("") }
    Note("Only these people can message or call it. The kids can't add anyone: you add and remove people here. " +
        "Tap a name to change how it shows on the device; every parent sees the same names.")
    ItemRow("You: ${people.first().n}", null, onClick = { naming = people.first().k })
    fun remove(name: String, k: String, waiting: Boolean) = Ui.sure(Ask("Remove $name?",
        if (waiting) "They aren't added when they accept." else "It can't reach them any more.", "Remove", danger = true) {
        TvParent.removePerson(t.key, k); Ui.say("Removed")
    })
    // A parent stays on the list (the kids could not answer them otherwise): no minus for them.
    people.drop(1).forEach { p ->
        val parent = p.k in t.parents
        ItemRow(p.n, if (parent) "Parent" else if (TvParent.isKidDevice(p.k)) "Kids' Device" else null, onClick = { naming = p.k },
            onRemove = if (parent) null else ({ remove(p.n, p.k, false) }))
    }
    if (naming.isNotEmpty()) TextDialog("Name on the device", people.firstOrNull { it.k == naming }?.n ?: "", TvRules.MAX_NAME,
        onCancel = { naming = "" }) { name -> TvParent.setName(t.key, naming, name); naming = ""; Ui.say("Saved") }
    t.waiting.keys.sortedBy { app.chat.nameOf(it).lowercase() }.forEach { k -> val n = app.chat.nameOf(k); ItemRow(n, WAITING) { remove(n, k, true) } }
    AddRow("Add") { adding = true }
    MenuRow("Send its code", "") {
        TvParent.shareText(app, t.key)?.let { text ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send its code"))
        }
    }
    Note("Another family's Kids' Device can go on the list too. It can talk with this one once its own parents add this device on their side (send them its code).")
    MenuRow("Group chat", "") { grouping = true }

    if (adding) {
        // People this phone asked who haven't accepted yet: invited by themselves once they accept.
        val pending = remember(met, contacts) { TvParent.notYetConnected(app).filter { it.first != t.key && it.first !in t.waiting }.sortedBy { it.second.lowercase() } }
        // Other Kids' Devices too: this family's (each goes on the other's list) or another family's.
        val choices = contacts.filter { KidRules.canAdd(it.chatKey, t.key, app.identity.publicKey, it.groupOnly, t.reach) }
            .sortedBy { it.shown.lowercase() }
        InviteDialog(choices.map { it.chatKey to it.shown }, pending, title = "Add", onCancel = { adding = false },
            onNew = {
                // Someone not in Muninn's contacts yet: they're added from the chat list's + first, then picked here.
                adding = false
                io.github.ravenor907.muninn.screens.ChatNav.open = "add"
                io.github.ravenor907.muninn.screens.SettingsNav.goTab = "chat"
            }) { k ->
            adding = false
            when {
                pending.any { it.first == k } -> { TvParent.inviteLater(t.key, listOf(k)); Ui.say("Added when they accept") }
                TvParent.isManaged(k) -> { TvParent.addBothWays(app, t.key, k); Ui.say("Added. The two devices can talk with each other now.") }
                TvParent.isKidDevice(k) -> { TvParent.invite(app, t.key, listOf(k)); Ui.say("Added. Once its parents add this device on their side, they can talk.") }
                else -> { TvParent.invite(app, t.key, listOf(k)); Ui.say("Added") }
            }
        }
    }
    // The device itself first, ticked: it joins a group only when one of its parents adds it.
    if (grouping) FamilyGroupDialog(app, listOf(t.key to (contacts.firstOrNull { it.chatKey == t.key }?.shown ?: "Kids' Device")) + people.drop(1).map { it.k to it.n },
        setOf(t.key), onClose = { grouping = false })
}

/** The parents of the device: a minus on each other parent, and Add a parent. */
@Composable
private fun Parents(app: Muninn, t: ManagedTv) {
    val contacts by app.contacts.all.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val me = app.identity.publicKey
    ItemRow("You", null)
    val others = t.parents.filter { it != me }
    // What each co-parent's app takes, so the page can say who needs an update.
    LaunchedEffect(others) { others.forEach { app.chat.probeCaps(it) } }
    others.sortedBy { app.chat.nameOf(it).lowercase() }.forEach { k ->
        val name = app.chat.nameOf(k)
        val caps = contacts.firstOrNull { it.chatKey == k }?.caps.orEmpty()
        // An older app (no k11) can't read this device's settings: its changes only add people, never take anyone off.
        val old = caps.isNotEmpty() && KidRules.CAP !in caps
        ItemRow(name, if (old) "$name's phone needs an update to manage this device" else null) {
            Ui.sure(Ask("Remove $name as a parent?", "They can't change its settings any more. They stay on its list, so they can still talk with the kids. Their phone is told.",
                "Remove", danger = true, hold = true) {
                TvParent.removeParent(app, t.key, k); Ui.say("Removed")
            })
        }
    }
    AddRow("Add a parent") { adding = true }
    if (adding) {
        val choices = contacts.filter { !it.groupOnly && it.chatKey != t.key && it.chatKey != me && it.chatKey !in t.parents && !TvParent.isManaged(it.chatKey) }
            .sortedBy { it.shown.lowercase() }
        InviteDialog(choices.map { it.chatKey to it.shown }, emptyList(), title = "Add a parent", onCancel = { adding = false }) { k ->
            adding = false
            val why = TvParent.addParent(app, t.key, k)
            Ui.say(why ?: "Added", why != null)
        }
    }
}

/** The device's quick messages: tap to edit, a minus to delete, drag the handle to reorder, Add. */
@Composable
private fun QuickMessages(t: ManagedTv) {
    val tvKey = t.key
    var editing by remember { mutableStateOf<Quick?>(null) }
    var order by remember(t.quick) { mutableStateOf(t.quick) }
    var dragId by remember { mutableStateOf("") }
    var dragY by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { 60.dp.toPx() }
    fun commit(list: List<Quick>) { order = list; TvParent.change(tvKey) { it.copy(quick = list) } }
    fun move(id: String, by: Int) {
        val i = order.indexOfFirst { it.id == id }
        val j = i + by
        if (i < 0 || j !in order.indices) return
        commit(order.toMutableList().apply { add(j, removeAt(i)) })
    }
    Text("A TV shows the first ${TvRules.QUICK_SHOWN}; a tablet or phone shows them all.", style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.padding(vertical = 8.dp))
    // A child who can't read yet tells the tiles apart by a picture.
    Note("Start each with an emoji, like \uD83C\uDF4E or \u2764\uFE0F, so a child who can't read yet can tell them apart.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { q ->
            key(q.id) {
                val dragging = dragId == q.id
                Row(
                    Modifier.fillMaxWidth().height(52.dp).zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragY else 0f }
                        .clip(Corner).background(if (dragging) Palette.raised else Palette.field)
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Move up") { move(q.id, -1); true },
                                CustomAccessibilityAction("Move down") { move(q.id, 1); true },
                            )
                        }
                        .padding(start = 4.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The handle: drag it up or down.
                    Box(Modifier.size(40.dp, 52.dp).pointerInput(q.id) {
                        detectVerticalDragGestures(
                            onDragStart = { dragId = q.id; dragY = 0f },
                            onDragEnd = { dragId = ""; dragY = 0f; commit(order) },
                            onDragCancel = { dragId = ""; dragY = 0f; commit(order) },
                        ) { change, dy ->
                            change.consume()
                            dragY += dy
                            val i = order.indexOfFirst { it.id == q.id }
                            if (dragY > rowPx / 2 && i in 0 until order.lastIndex) {
                                order = order.toMutableList().apply { add(i + 1, removeAt(i)) }; dragY -= rowPx
                            } else if (dragY < -rowPx / 2 && i > 0) {
                                order = order.toMutableList().apply { add(i - 1, removeAt(i)) }; dragY += rowPx
                            }
                        }
                    }, contentAlignment = Alignment.Center) { Grip() }
                    Text(q.text, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable(role = Role.Button) { editing = q }.padding(vertical = 14.dp))
                    Minus("Delete ${q.text}") { commit(order.filterNot { it.id == q.id }) }
                }
            }
        }
    }
    if (order.size < TvRules.MAX_QUICK) AddRow("Add") { editing = Quick("", "") }
    editing?.let { q ->
        TextDialog(if (q.id.isEmpty()) "Add a message" else "Edit message", q.text, TvRules.QUICK_TILE, onCancel = { editing = null }) { text ->
            commit(if (q.id.isEmpty()) order + Quick(TvRules.quickId(), text) else order.map { if (it.id == q.id) it.copy(text = text) else it })
            editing = null
        }
    }
}

/** Calls, the device's microphone level and noise removal, and the remote's mic (a TV's). */
@Composable
private fun Sound(app: Muninn, t: ManagedTv) {
    val tvKey = t.key
    var micLevel by remember(t.mic) { mutableFloatStateOf(t.mic.toFloat()) }
    // A TV without k2 can't take calls or a level, nor without k5 noise removal: those are off until it's updated.
    val k2 = TvParent.k2(app, tvKey)
    val k5 = TvParent.k5(app, tvKey)
    if (!k2) Note(UPDATE_FIRST)
    TvToggle("Calls", "", t.calls, enabled = k2) { on -> TvParent.change(tvKey) { it.copy(calls = on) } }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Mic level", style = Type.big.copy(fontSize = 16.sp, color = if (k2) Palette.text else Palette.textFaint), modifier = Modifier.weight(1f))
        Text("${micLevel.toInt()}", style = Type.normal.copy(color = Palette.textMuted))
    }
    Slider(micLevel, { micLevel = it }, enabled = k2, valueRange = 0f..TvRules.MAX_MIC.toFloat(), steps = 9,
        onValueChangeFinished = { TvParent.setMic(tvKey, micLevel.toInt()) },
        colors = SliderDefaults.colors(thumbColor = Palette.gold, activeTrackColor = Palette.gold, inactiveTrackColor = Palette.lineMuted),
        modifier = Modifier.semantics { contentDescription = "Mic level ${micLevel.toInt()} of 100" })
    // Noise removal and the remote's mic are a TV's (an older device that says nothing is a TV).
    if (t.dev != KidRules.TV && t.dev.isNotEmpty()) return
    Text("Noise removal", style = Type.big.copy(fontSize = 16.sp, color = if (k5) Palette.text else Palette.textFaint), modifier = Modifier.padding(top = 12.dp))
    val levels = listOf(TvRules.NR_NONE to "None", TvRules.NR_STANDARD to "Standard", TvRules.NR_STRONG to "Strong", TvRules.NR_BOTH to "Both")
    ChoiceRow(levels.size, Modifier.padding(top = 6.dp, bottom = 8.dp), minWidth = 88.dp) { i, m ->
        val (v, w) = levels[i]
        MButton(w, m, if (t.nr == v) Kind.Accent else Kind.Normal, enabled = k5) { TvParent.setNoise(tvKey, v) }
    }
    if (!k5) Note(UPDATE_FIRST)
    ToggleRow("Remote mic", "Say it on a TV", t.dictation) { on -> TvParent.change(tvKey) { it.copy(dictation = on) } }
}

/** The device's name on this phone, and this parent's name on the device. */
@Composable
private fun Names(app: Muninn, t: ManagedTv, tvName: String) {
    var naming by remember { mutableStateOf("") }
    MenuRow("Device name", tvName) { naming = "tv" }
    MenuRow("Your name on it", t.myName.ifBlank { "Parent" }) { naming = "me" }
    when (naming) {
        "tv" -> TextDialog("Device name", tvName, TvRules.MAX_NAME, onCancel = { naming = "" }) { name -> app.chat.rename(t.key, name); naming = "" }
        "me" -> TextDialog("Your name on it", t.myName, TvRules.MAX_NAME, onCancel = { naming = "" }) { name ->
            TvParent.setName(t.key, app.identity.publicKey, name); naming = ""
        }
    }
}

/** Send an app over the Wi-Fi, and the apps waiting for a parent's Install. */
@Composable
private fun Apps(app: Muninn, t: ManagedTv) {
    val tvKey = t.key
    val context = LocalContext.current
    var sendingApp by remember { mutableStateOf(false) }
    // "Getting ready" shows at once (below, in the strip and on the notification) while the app is copied and checked.
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val prep = app.net.preparing("", listOf(if (uris.size == 1) "App" else "${uris.size} files"))
            if (prep == null) Ui.say("Wait for the transfer to finish.", true)
            else Thread {
                val files = io.github.ravenor907.muninn.screens.Share.copyIn(context, uris, true)
                sendAppToTv(app, tvKey, files.filter { AppFiles.isApk(it.name) }, prep)
            }.start()
        }
    }
    // Apps are off while the TV's app can't take them (k4).
    val k4 = TvParent.k4(app, tvKey)
    val installsOff = t.off?.contains("install") == true
    if (!k4) Note(UPDATE_FIRST)
    if (installsOff) Note(INSTALLS_OFF)
    MenuRow("Send an app", "", enabled = k4) { sendingApp = true }
    // Apps waiting in the TV's Downloads (k9, 7.15): nothing installs there until a parent says Install here.
    val asks by TvAppAsks.items.collectAsState()
    asks.let { TvAppAsks.of(app, tvKey) }.forEach { item ->
        val a = item.ask
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Install ${a.name} on the device?", style = Type.normal)
            Text(listOf(io.github.ravenor907.muninn.net.FileText.size(a.size), a.pkg, a.ver).filter { it.isNotEmpty() }.joinToString(" · "),
                style = Type.small, modifier = Modifier.padding(top = 2.dp))
            if (item.answered) Text(if (item.result.isEmpty()) "Sent to the device…" else TvInstalls.resultText(item.result, item.why),
                style = Type.small.copy(color = if (item.result == "failed" || item.result == "perm") Palette.danger else Palette.textMuted),
                modifier = Modifier.padding(top = 4.dp))
            if (!item.answered || item.result == "failed" || item.result == "perm") androidx.compose.foundation.layout.Row(Modifier.padding(top = 6.dp)) {
                MButton(if (item.answered) "Try again" else "Install", kind = io.github.ravenor907.muninn.ui.Kind.Accent) { TvAppAsks.answer(app, item, true) }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                MButton("Not now") { TvAppAsks.answer(app, item, false) }
            }
        }
    }
    // An app on its way to the TV: how far, how fast, and Cancel; then how it ended. It lands in the TV's Downloads.
    val moving by app.net.transfer.collectAsState()
    val finished by app.net.finished.collectAsState()
    if (moving.active && moving.sending) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(moving.status, style = Type.normal)
        io.github.ravenor907.muninn.ui.Bar(moving.fraction, Modifier.padding(top = 6.dp), Palette.gold)
        val pace = moving.pace()
        Text("${io.github.ravenor907.muninn.net.FileText.percent(moving.done, moving.total)}%" + (if (pace.isNotEmpty()) " · $pace" else ""),
            style = Type.small, modifier = Modifier.padding(top = 4.dp))
        MButton("Cancel", Modifier.padding(top = 6.dp), kind = io.github.ravenor907.muninn.ui.Kind.Danger) { app.net.cancelSend() }
    } else finished?.let { (ok, text) ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text, style = Type.normal.copy(color = if (ok) Palette.text else Palette.danger))
            MButton("Done", Modifier.padding(top = 6.dp)) { app.net.clearFinished() }
        }
    }
    if (sendingApp) io.github.ravenor907.muninn.screens.AppPicker(
        onCancel = { sendingApp = false },
        onFile = { sendingApp = false; try { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) } catch (e: Exception) { Ui.say("This phone has no file picker.", true) } },
    ) { a ->
        sendingApp = false
        val prep = app.net.preparing("", listOf(a.label))
        if (prep == null) Ui.say("Wait for the transfer to finish.", true)
        else Thread { sendAppToTv(app, tvKey, AppFiles.copyOut(context, a.pkg), prep) }.start()
    }
}

/** Asks the device to turn on each permission it still needs; it shows Android's prompt. */
@Composable
private fun Permissions(app: Muninn, t: ManagedTv) {
    val k8 = TvParent.k8(app, t.key)
    Note(if (k8) "Press Allow on the device." else UPDATE_FIRST)
    // Install updates is with Updates.
    for (id in TvRules.PERMS.filter { it != "install" }) {
        val state = when {
            t.off == null -> ""
            id in t.off -> "Off"
            else -> "On"
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(PERM_NAMES[id] ?: id, style = Type.normal.copy(color = Palette.text))
                if (state.isNotEmpty()) Text(state, style = Type.small.copy(fontSize = 13.sp, color = if (state == "On") Palette.online else Palette.textMuted))
            }
            if (state != "On") MButton("Ask device", enabled = k8) {
                if (TvParent.askAllow(app, t.key, id)) Ui.say("Asked the device") else Ui.say(UPDATE_FIRST, true)
            }
        }
    }
}

/** What Muninn takes on the device, and Clear history. */
@Composable
private fun Storage(app: Muninn, t: ManagedTv) {
    InfoRow("Used", when {
        !TvParent.k3(app, t.key) -> UPDATE_FIRST
        t.used < 0 -> "Asking…"
        else -> sizeText(t.used)
    })
    MenuRow("Clear history", "", danger = true) {
        Ui.sure(Ask("Clear its history?", "Erases every message on the device.", "Clear", danger = true) {
            if (TvParent.clearTv(app, t.key)) Ui.say("It's clearing its history")
            else Ui.say(UPDATE_FIRST, true)
        })
    }
    // Labs: App storage on the device (a Kids' Device has no Settings of its own), cap k10.
    if (io.github.ravenor907.muninn.screens.Labs.on(app)) TvAppStorage(app, t)
}

/** Labs › App storage for the TV: its list (newest 40), with Delete, Move to Downloads, Delete all received files and Clear caches. */
@Composable
private fun TvAppStorage(app: Muninn, t: ManagedTv) {
    val lists by TvFiles.lists.collectAsState()
    val k10 = TvParent.k10(app, t.key)
    // Asked when this opens; the TV answers with its list (and again after each change).
    LaunchedEffect(t.key, k10) { TvParent.askFiles(app, t.key) }
    var waiting by remember { mutableStateOf(false) }
    val l = lists[t.key]
    LaunchedEffect(l) { waiting = false }
    // No answer (the TV is off, or nothing changed): the buttons come back after a while.
    LaunchedEffect(waiting) { if (waiting) { kotlinx.coroutines.delay(15_000); waiting = false } }
    Text("APP STORAGE", style = Type.section.copy(color = Palette.accent), modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
    if (!k10) { Note(UPDATE_FIRST); return }
    fun ask(sent: Boolean) { if (sent) waiting = true else Ui.say(UPDATE_FIRST, true) }
    io.github.ravenor907.muninn.screens.StorageList(l?.items, waiting, l?.move == true, count = l?.count ?: 0, total = l?.total ?: 0L,
        onDelete = { i -> ask(TvParent.deleteTvFiles(app, t.key, listOf(i.id))) },
        onMove = { i -> ask(TvParent.moveTvFiles(app, t.key, listOf(i.id))) },
        onDeleteAll = { k -> ask(TvParent.deleteTvFiles(app, t.key, emptyList(), all = k)) })
}

/** How long messages stay on the device. */
@Composable
private fun Keep(app: Muninn, t: ManagedTv) {
    // A TV without k8 can't take it: the rows are off until it's updated.
    val can = TvParent.k8(app, t.key)
    if (!can) Note(UPDATE_FIRST)
    for (days in listOf(TvRules.MIN_KEEP, TvRules.MAX_KEEP)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = can, role = Role.RadioButton) { TvParent.setKeep(t.key, days) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(keepName(days), style = Type.normal.copy(color = if (can) Palette.text else Palette.textFaint), modifier = Modifier.weight(1f))
            if (t.keep == days) MIcon("check", if (can) Palette.goldBright else Palette.textFaint, 22.dp)
        }
    }
}

/** Disconnect: the device erases everything and goes back to pairing (or becomes a normal Muninn). Held to confirm. */
@Composable
private fun Disconnect(tvKey: String, onBack: () -> Unit) {
    Note("Erases the device and unpairs it. If every parent's phone is lost, clear Muninn's data on the device instead.")
    HoldToConfirm("Hold to disconnect", "Hold for 5 seconds to erase and unpair this device", { TvParent.disconnect(tvKey); onBack() },
        Modifier.fillMaxWidth().padding(top = 12.dp))
    Note("Or erase it and let it be used as a normal Muninn, with a lock and Settings.")
    HoldToConfirm("Hold to make it a normal device", "Hold for 5 seconds to erase this device and make it a normal Muninn",
        { TvParent.disconnect(tvKey, free = true); onBack() }, Modifier.fillMaxWidth().padding(top = 8.dp))
}

/** The chatting level: what the kids can send on the device (k11). Calls follow Calls and mic. */
@Composable
private fun Chatting(app: Muninn, t: ManagedTv) {
    val k11 = TvParent.k11(app, t.key)
    if (!k11) Note(UPDATE_FIRST)
    for (level in listOf(KidRules.QUICK_ONLY, KidRules.EVERYTHING)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = k11, role = Role.RadioButton) {
            if (t.chat != level) { TvParent.setChat(t.key, level); Ui.say("Saved. It changes on the device once it's online.") }
        },
            verticalAlignment = Alignment.CenterVertically) {
            Text(KidRules.levelName(level), style = Type.normal.copy(color = if (k11) Palette.text else Palette.textFaint), modifier = Modifier.weight(1f))
            if (t.chat == level) MIcon("check", if (k11) Palette.goldBright else Palette.textFaint, 22.dp)
        }
    }
    Note("Calls follow Calls and mic. On a TV, typing is Say it, with the remote's mic.")
}

/** School hours and bedtime (k11): only parents' messages and calls get through then. */
@Composable
private fun Quiet(app: Muninn, t: ManagedTv) {
    val k11 = TvParent.k11(app, t.key)
    if (!k11) Note(UPDATE_FIRST)
    Note("During these times only parents' messages and calls get through. Everyone else's wait and arrive when it ends. " +
        "They follow this time zone and the time from your phone and the internet, so changing the clock or zone on the device changes nothing.")
    var zoning by remember { mutableStateOf(false) }
    MenuRow("Time zone", t.tz.ifEmpty { java.util.TimeZone.getDefault().id }, enabled = k11) { zoning = true }
    for (n in KidRules.QUIET_NAMES) QuietPart(t, n, k11)
    if (zoning) ZoneDialog(t.tz.ifEmpty { java.util.TimeZone.getDefault().id }, onCancel = { zoning = false }) { z -> zoning = false; TvParent.setTz(t.key, z) }
}

@Composable
private fun QuietPart(t: ManagedTv, n: String, enabled: Boolean) {
    val context = LocalContext.current
    val q = t.quiet.firstOrNull { it.n == n }
    // This phone's own 12 or 24 hour clock.
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    TvToggle(KidRules.quietName(n), q?.let { "${KidRules.daysText(it.d)}, ${KidRules.clock(it.s, h24)} to ${KidRules.clock(it.e, h24)}" } ?: "Off", q != null, enabled) { on ->
        TvParent.setQuiet(t.key, n, if (on) KidRules.quietDefault(n) else null)
    }
    if (q == null) return
    // The days it starts on: tap one to turn it on or off (at least one stays on).
    ChoiceRow(7, Modifier.padding(vertical = 4.dp), minWidth = 40.dp, gap = 4.dp) { i, m ->
        val on = q.d and (1 shl i) != 0
        MButton(KidRules.DAY_NAMES[i], m, if (on) Kind.Accent else Kind.Normal, enabled = enabled) {
            val d = q.d xor (1 shl i)
            if (d != 0) TvParent.setQuiet(t.key, n, q.copy(d = d)) else Ui.say("One day at least stays on. To turn it off, use the switch above.")
        }
    }
    fun pick(start: Boolean) {
        val now = if (start) q.s else q.e
        try {
            android.app.TimePickerDialog(context, { _, h, m ->
                val v = h * 60 + m
                val next = if (start) q.copy(s = v) else q.copy(e = v)
                if (next.s == next.e) Ui.say("The start and end can't be the same.", true) else TvParent.setQuiet(t.key, n, next)
            }, now / 60, now % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
        } catch (_: Exception) {}
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MButton("From ${KidRules.clock(q.s, h24)}", Modifier.weight(1f), enabled = enabled) { pick(true) }
        MButton("To ${KidRules.clock(q.e, h24)}", Modifier.weight(1f), enabled = enabled) { pick(false) }
    }
}

/**
 * The parent PIN (k11): leaving kids' mode, and Muninn's way into Android's settings on the device, need it or a yes
 * from a parent's phone. Only its hash goes to the device. App pinning is suggested, never turned on from here.
 */
@Composable
private fun Lock(app: Muninn, t: ManagedTv) {
    val k11 = TvParent.k11(app, t.key)
    var setting by remember { mutableStateOf(false) }
    if (!k11) Note(UPDATE_FIRST)
    Note("On the device, \"For a parent\" opens only with this PIN or a yes from a parent's phone. Inside Muninn, leaving kids' mode and " +
        "Muninn's way into Android's settings need it. It doesn't stop the Home button, other apps or Android's own Settings on a tablet or phone.")
    MenuRow(if (t.pin == null) "Set a PIN" else "Change PIN", if (t.pin == null) "Not set" else "Set", enabled = k11) { setting = true }
    if (t.pin != null) MenuRow("Remove PIN", "", danger = true, enabled = k11) {
        Ui.sure(Ask("Remove the PIN?", "Then only a parent's phone can open the parent settings on the device.", "Remove", danger = true) {
            TvParent.setPin(t.key, null); Ui.say("PIN removed")
        })
    }
    Note(FULL_LOCK)
    if (setting) PinDialog(onCancel = { setting = false }) { pin ->
        setting = false
        // The hash is slow on purpose: made off the main thread.
        Thread { try { TvParent.setPin(t.key, KidRules.newPin(pin)); Ui.say("PIN set") } catch (_: Exception) {} }.start()
    }
}

/**
 * Daily Hávamál (k12): one verse a day on the kids' home, with what it means. The wording is Bellows' translation (1923),
 * or the parent's own: a plain-text file with stanza numbers, kept in this phone's vault and sent only to this parent's
 * own Kids' Devices.
 */
@Composable
private fun DailyHavamal(app: Muninn, t: ManagedTv) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val k12 = TvParent.k12(app, t.key)
    val mine by HavParent.mine.collectAsState()
    var importing by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            scope.launch {
                val line = withContext(Dispatchers.IO) { HavParent.importFile(app, uri) }
                importing = false
                Ui.say(line, !line.startsWith("Imported"))
            }
        }
    }
    if (!k12) Note(UPDATE_FIRST)
    TvToggle("Daily Hávamál", "One verse a day on the kids' home, with what it means for a child. It can read it aloud.", t.hav, enabled = k12) { on ->
        TvParent.setHav(t.key, on)
    }
    Text("Wording", style = Type.big.copy(fontSize = 16.sp, color = if (k12) Palette.text else Palette.textFaint), modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    val (wording, hash) = TvParent.havWording(t)
    val others = wording == Havamal.MINE && hash != mine?.text?.h
    val choices = buildList {
        add(Havamal.BELLOWS to Havamal.BELLOWS_NAME)
        if (mine != null) add(Havamal.MINE to Havamal.MINE_NAME)
    }
    for ((w, name) in choices) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = k12, role = Role.RadioButton) { TvParent.setHavWording(t.key, w) },
            verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = Type.normal.copy(color = if (k12) Palette.text else Palette.textFaint), modifier = Modifier.weight(1f))
            if (wording == w && !others) MIcon("check", if (k12) Palette.goldBright else Palette.textFaint, 22.dp)
        }
    }
    if (others) Note("Another parent's translation is in use on this device.")
    Note("The kids see \"What it means\" under the verse either way.")
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(Palette.hairline))
    Text(Havamal.MINE_NAME, style = Type.big.copy(fontSize = 16.sp, color = Palette.text))
    Note("A plain-text file (.txt) with stanza numbers, like \"1.\" or \"St. 1\". Only the stanzas on the list are kept. It stays on this phone and goes only to your own Kids' Devices.")
    val m = mine
    if (m == null) {
        MButton(if (importing) "Importing…" else "Import a translation", Modifier.fillMaxWidth().padding(vertical = 6.dp), enabled = !importing) {
            try { pick.launch(arrayOf("text/plain")) } catch (_: Exception) { Ui.say("Can't open files here", true) }
        }
    } else {
        InfoRow(Havamal.matchedLine(m.matched, m.of), "")
        MButton(if (importing) "Importing…" else "Import another", Modifier.fillMaxWidth().padding(vertical = 6.dp), enabled = !importing) {
            try { pick.launch(arrayOf("text/plain")) } catch (_: Exception) { Ui.say("Can't open files here", true) }
        }
        HoldToConfirm("Hold to remove", "Hold for 5 seconds to remove your translation", { HavParent.remove(app); Ui.say("Removed") },
            Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

/** A new PIN, typed twice. */
@Composable
private fun PinDialog(onCancel: () -> Unit, onSave: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val ok = KidRules.pinValid(a) && a == b
    val digits = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text("Parent PIN", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(a, { v -> a = v.filter { it in '0'..'9' }.take(KidRules.PIN_MAX) }, "${KidRules.PIN_MIN} to ${KidRules.PIN_MAX} digits", keyboard = digits, secret = true)
                Text("${KidRules.PIN_GOOD} digits or more is much harder to guess.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
                Field(b, { v -> b = v.filter { it in '0'..'9' }.take(KidRules.PIN_MAX) }, "The same again", Modifier.padding(top = 8.dp), keyboard = digits, secret = true)
                if (b.length >= a.length && b.isNotEmpty() && a != b) Text("They don't match.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { MButton("Save", kind = Kind.Accent, enabled = ok) { if (ok) onSave(a) } },
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}

private fun keepName(days: Int) = if (days <= TvRules.MIN_KEEP) "1 day" else "$days days"

// ------------------------------------------------------------------ pieces

/**
 * A parent sends an app ([files]: one app's APK and its parts, or .apk files picked by hand) to the TV: first
 * their SHA-256s in room tvconfig (7.9), then the files themselves over the Wi-Fi. Runs off the main thread.
 * [prep]: the "Getting ready" transfer that showed the moment it was picked; it becomes the send, or says why not.
 */
private fun sendAppToTv(app: Muninn, tvKey: String, files: List<java.io.File>, prep: String) {
    if (files.isEmpty()) { app.net.notSent(prep, "Pick an app or an .apk file."); return }
    val dev = app.net.deviceFor(tvKey)
    if (dev == null) { app.net.notSent(prep, "The device isn't on this Wi-Fi."); return }
    if (app.net.transfer.value.id != prep) return   // cancelled while it was copied in
    val name = AppFiles.group(files) { it.name }.joinToString(", ") { it.first }
    val parts = try { files.map { TvApps.Part(AppFiles.sha256(it), it.length()) } } catch (e: Exception) { app.net.notSent(prep, "$name couldn't be read."); return }
    if (TvParent.tvs.value[tvKey]?.off?.contains("install") == true) { app.net.notSent(prep, INSTALLS_OFF); return }
    if (app.net.transfer.value.id != prep) return
    if (!TvParent.announceApp(app, tvKey, name, parts)) { app.net.notSent(prep, UPDATE_FIRST); return }
    app.net.send(dev.id, files, prepared = prep)
}

/** Pick one contact; [waiting]: people who haven't accepted this phone yet. */
@Composable
private fun InviteDialog(choices: List<Pair<String, String>>, waiting: List<Pair<String, String>>, title: String,
                         onCancel: () -> Unit, onNew: (() -> Unit)? = null, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choices.isEmpty() && waiting.isEmpty()) Text("Nobody to add yet. Add them as a contact first.", style = Type.small)
                choices.forEach { (k, name) -> PersonRow(name, null) { onPick(k) } }
                waiting.forEach { (k, name) -> PersonRow(name, WAITING) { onPick(k) } }
                if (onNew != null) {
                    Text("Not here? They show you their code in Muninn (+, My code) and you scan it, then add them here.", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                    MButton("Add a contact", Modifier.fillMaxWidth().padding(top = 6.dp), icon = "plus") { onNew() }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}

private const val WAITING = "Waiting for them"

@Composable
private fun PersonRow(name: String, note: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        MIcon("user", if (note == null) Palette.accent else Palette.textFaint, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = Type.small.copy(fontSize = 13.sp))
        }
    }
}

/** A person (or parent) on a list; [onRemove] adds the minus that takes them off. */
@Composable
private fun ItemRow(name: String, note: String?, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically) {
        MIcon("user", Palette.textMuted, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note != null) Text(note, style = Type.small.copy(fontSize = 13.sp))
        }
        if (onRemove != null) Minus("Remove $name", onRemove)
    }
}

/** A red minus in a 48 dp target: takes the row's item off. */
@Composable
private fun Minus(label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.dangerFill), contentAlignment = Alignment.Center) {
            Box(Modifier.size(12.dp, 2.dp).background(Palette.danger))
        }
    }
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 50.dp).clip(Corner).border(1.dp, Palette.lineMuted, Corner)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        MIcon("plus", Palette.goldBright, 18.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Type.normal.copy(color = Palette.goldBright))
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.padding(vertical = 6.dp))
}

/** "12 MB", "1.3 GB". */
private fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "${(b + (1L shl 19)) shr 20} MB"
    else -> "${maxOf(1L, (b + 512) shr 10)} KB"
}

/** Put people who can reach the TV in a family group: tick them and a group you run, or make one called Family. */
@Composable
private fun FamilyGroupDialog(app: Muninn, people: List<Pair<String, String>>, start: Set<String>, onClose: () -> Unit) {
    val groups by app.chat.groups.all.collectAsState()
    val me = app.identity.publicKey
    val mine = groups.values.filter { !it.readOnly && it.state.isAdmin(me) }.sortedBy { it.state.name.lowercase() }
    var picked by remember { mutableStateOf(start) }
    var group by remember { mutableStateOf(mine.firstOrNull { it.state.name.contains("family", ignoreCase = true) }?.g ?: mine.firstOrNull()?.g ?: "") }
    val box = CheckboxDefaults.colors(checkedColor = Palette.gold, checkmarkColor = Palette.onGold, uncheckedColor = Palette.lineMuted)
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Palette.panel,
        title = { Text("Group chat", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Makes a group chat with the people you tick, or adds them to one you run. Leave the device ticked so the kids are in it: " +
                    "it joins a group only when one of its parents adds it.", style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
                Text("Who", style = Type.small)
                if (people.isEmpty()) Text("Add someone first.", style = Type.small)
                people.forEach { (k, name) ->
                    val on = k in picked
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { picked = if (on) picked - k else picked + k },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, null, colors = box)
                        Text(name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("Group", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                mine.forEach { grp ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { group = grp.g },
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group == grp.g, null, colors = box)
                        Text(grp.state.name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.RadioButton) { group = "" }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(group.isEmpty(), null, colors = box)
                    Text("New group: Family", style = Type.normal.copy(color = Palette.text))
                }
            }
        },
        confirmButton = {
            MButton("Add", kind = Kind.Accent, enabled = picked.isNotEmpty()) {
                val g = group.ifEmpty { app.chat.groups.create("Family") ?: "" }
                if (g.isEmpty()) { Ui.say("The group couldn't be made.", true); return@MButton }
                val why = picked.mapNotNull { k -> app.chat.inviteToGroup(g, k) }
                if (why.isEmpty()) Ui.say("Invited") else Ui.say(why.first(), true)
                onClose()
            }
        },
        dismissButton = { MButton("Cancel", onClick = onClose) },
    )
}

/** Six dots: the drag handle. */
@Composable
private fun Grip() {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(2) { Box(Modifier.size(3.dp).clip(CircleShape).background(Palette.textFaint)) }
            }
        }
    }
}

/** Pick the time zone a device's quiet times follow: type part of a city's name. */
@Composable
private fun ZoneDialog(start: String, onCancel: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val all = remember { java.util.TimeZone.getAvailableIDs().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    val shown = remember(q) { val w = q.trim().replace(' ', '_'); (if (w.isEmpty()) all else all.filter { it.contains(w, ignoreCase = true) }).take(60) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text("Time zone", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Now: ${start.replace('_', ' ')}", style = Type.small)
                Field(q, { q = it }, "Type a city, like London", Modifier.padding(top = 8.dp))
                shown.forEach { z -> MenuRow(z.replace('_', ' '), "") { onPick(z) } }
            }
        },
        confirmButton = {},
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}

@Composable
private fun TextDialog(title: String, start: String, max: Int, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    // A longer text from before (a quick message from an older app) only gets shorter here, and saves once it fits.
    val clean = TvRules.clean(text, Int.MAX_VALUE)
    val count = clean.codePointCount(0, clean.length)
    val fits = count in 1..max
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(text, { v -> text = TvRules.quickEdit(text, v, max) }, "Up to $max letters")
                Text("$count of $max", style = Type.small.copy(color = if (count > max) Palette.danger else Palette.textMuted), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { MButton("Save", kind = Kind.Accent, enabled = fits) { if (fits) onSave(clean) } },
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}
