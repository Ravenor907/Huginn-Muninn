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
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * The Kids' TV's menu on a parent's phone, one row per part, each opening its own screen. The same ids open a
 * part straight from elsewhere (the TV contact's menu): `ParentTvScreen(app, tvKey, section = id)`.
 */
object ParentTvMenu {
    /** (id, label) in menu order. Disconnect is last. */
    val items: List<Pair<String, String>> = listOf(
        "people" to "People",
        "parents" to "Parents",
        "quick" to "Quick messages",
        "sound" to "Sound",
        "names" to "Names",
        "apps" to "Apps and updates",
        "permissions" to "TV permissions",
        "storage" to "Storage",
        "keep" to "Delete messages after",
        "disconnect" to "Disconnect TV",
    )

    /** The label for [id], or null if it isn't one of [items]. */
    fun label(id: String?): String? = items.firstOrNull { it.first == id }?.second
}

/** What each TV permission is called on the phone, in [TvRules.PERMS] order. */
private val PERM_NAMES = mapOf("mic" to "Microphone", "notify" to "Notifications", "overlay" to "Show over other apps", "install" to "Install updates")

private const val UPDATE_FIRST = "Update the TV first"

/**
 * "Kids' TV" on a parent's phone. [section]: null shows the menu; one of [ParentTvMenu.items]' ids opens that
 * screen. Back from a screen opened from the menu returns to the menu; Back from the menu, or from a [section]
 * opened straight from elsewhere (the TV contact's menu), calls [onBack]. Every change goes to the TV as a new signed config.
 */
@Composable
fun ParentTvScreen(app: Muninn, tvKey: String, section: String? = null, onBack: () -> Unit) {
    val tvs by TvParent.tvs.collectAsState()
    val t = tvs[tvKey]
    var open by remember(section) { mutableStateOf(section?.takeIf { ParentTvMenu.label(it) != null }) }
    // A section opened straight from elsewhere goes back there, not to this menu.
    fun back() { if (open != null && section == null) open = null else onBack() }
    BackHandler { back() }
    if (t == null || t.disconnecting) { LaunchedEffect(Unit) { onBack() }; return }
    val contacts by app.contacts.all.collectAsState()
    val k3 = TvParent.k3(app, tvKey)
    // The TV's storage, level and permissions (k3, k8): asked when this opens and when a part that shows them opens.
    LaunchedEffect(tvKey, k3, open) { TvParent.askUsage(app, tvKey) }
    val tvName = contacts.firstOrNull { it.chatKey == tvKey }?.shown ?: "Kids' TV"

    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", "Back") { back() }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(ParentTvMenu.label(open) ?: tvName, style = Type.big, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (t.upToDate) Palette.online else Palette.textFaint))
                    Spacer(Modifier.width(6.dp))
                    Text(if (t.upToDate) "Connected" else "TV offline; will update",
                        style = Type.small.copy(fontSize = 13.sp, color = if (t.upToDate) Palette.online else Palette.textMuted))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (open) {
                null -> Menu(app, t) { open = it }
                "people" -> People(app, t)
                "parents" -> Parents(app, t)
                "quick" -> QuickMessages(t)
                "sound" -> Sound(app, t)
                "names" -> Names(app, t, tvName)
                "apps" -> Apps(app, t)
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
    for ((id, label) in ParentTvMenu.items) {
        val value = when (id) {
            "people" -> "${TvParent.people(app, t).size + t.waiting.size}"
            "parents" -> "${t.parents.size}"
            "quick" -> "${t.quick.size}"
            "sound" -> if (t.calls) "Calls on" else "Calls off"
            "names" -> t.myName.ifBlank { "Parent" }
            "apps" -> t.app
            "permissions" -> t.off?.let { off -> if (off.isEmpty()) "All on" else "${off.size} off" } ?: ""
            "storage" -> if (t.used >= 0) sizeText(t.used) else ""
            "keep" -> keepName(t.keep)
            else -> ""
        }
        MenuRow(label, value, danger = id == "disconnect") { onOpen(id) }
    }
}

@Composable
private fun MenuRow(title: String, value: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.normal.copy(color = if (danger) Palette.danger else Palette.text), modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = Type.normal.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
        if (!danger) MIcon("forward", Palette.textFaint, 18.dp, Modifier.padding(start = 8.dp))
    }
}

// ------------------------------------------------------------------ the parts

/** Who can reach the TV: a minus on each to take them off, Add, the TV's code, and a family group. */
@Composable
private fun People(app: Muninn, t: ManagedTv) {
    val context = LocalContext.current
    val contacts by app.contacts.all.collectAsState()
    val met by app.chat.met.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    val people = TvParent.people(app, t)
    ItemRow("You", null)
    people.drop(1).forEach { p -> ItemRow(p.n, null) { TvParent.removePerson(t.key, p.k) } }
    t.waiting.keys.sortedBy { app.chat.nameOf(it).lowercase() }.forEach { k -> ItemRow(app.chat.nameOf(k), WAITING) { TvParent.removePerson(t.key, k) } }
    AddRow("Add") { adding = true }
    MenuRow("Send TV code", "") {
        TvParent.shareText(app, t.key)?.let { text ->
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send TV code"))
        }
    }
    MenuRow("Family group", "") { grouping = true }

    if (adding) {
        // People this phone asked who haven't accepted yet: invited by themselves once they accept.
        val pending = remember(met, contacts) { TvParent.notYetConnected(app).filter { it.first != t.key && it.first !in t.waiting }.sortedBy { it.second.lowercase() } }
        val choices = contacts.filter { !it.groupOnly && it.chatKey != t.key && it.chatKey != app.identity.publicKey && !TvParent.isManaged(it.chatKey) && it.chatKey !in t.reach }
            .sortedBy { it.shown.lowercase() }
        InviteDialog(choices.map { it.chatKey to it.shown }, pending, title = "Add", onCancel = { adding = false }) { k ->
            adding = false
            if (pending.any { it.first == k }) { TvParent.inviteLater(t.key, listOf(k)); Ui.say("Added when they accept") }
            else { TvParent.invite(app, t.key, listOf(k)); Ui.say("Added") }
        }
    }
    if (grouping) FamilyGroupDialog(app, people.drop(1).map { it.k to it.n }, emptySet(), onClose = { grouping = false })
}

/** The parents of the TV: a minus on each other parent, and Add a parent. */
@Composable
private fun Parents(app: Muninn, t: ManagedTv) {
    val contacts by app.contacts.all.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val me = app.identity.publicKey
    ItemRow("You", null)
    t.parents.filter { it != me }.sortedBy { app.chat.nameOf(it).lowercase() }.forEach { k ->
        ItemRow(app.chat.nameOf(k), null) { TvParent.removeParent(app, t.key, k); Ui.say("Removed") }
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

/** The TV's quick messages: tap to edit, a minus to delete, drag the handle to reorder, Add. */
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
    Text("The first ${TvRules.QUICK_SHOWN} show on the TV.", style = Type.small.copy(fontSize = 13.sp), modifier = Modifier.padding(vertical = 8.dp))
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
        TextDialog(if (q.id.isEmpty()) "Add a message" else "Edit message", q.text, TvRules.MAX_QUICK_TEXT, onCancel = { editing = null }) { text ->
            commit(if (q.id.isEmpty()) order + Quick(TvRules.quickId(), text) else order.map { if (it.id == q.id) it.copy(text = text) else it })
            editing = null
        }
    }
}

/** Calls, the TV's microphone level and noise removal, and the remote's mic. */
@Composable
private fun Sound(app: Muninn, t: ManagedTv) {
    val tvKey = t.key
    var micLevel by remember(t.mic) { mutableFloatStateOf(t.mic.toFloat()) }
    if (!TvParent.k2(app, tvKey)) Note(UPDATE_FIRST)
    ToggleRow("Calls", "", t.calls) { on -> TvParent.change(tvKey) { it.copy(calls = on) } }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Mic level", style = Type.big.copy(fontSize = 16.sp), modifier = Modifier.weight(1f))
        Text("${micLevel.toInt()}", style = Type.normal.copy(color = Palette.textMuted))
    }
    Slider(micLevel, { micLevel = it }, valueRange = 0f..TvRules.MAX_MIC.toFloat(), steps = 9,
        onValueChangeFinished = { TvParent.setMic(tvKey, micLevel.toInt()) },
        colors = SliderDefaults.colors(thumbColor = Palette.gold, activeTrackColor = Palette.gold, inactiveTrackColor = Palette.lineMuted),
        modifier = Modifier.semantics { contentDescription = "Mic level ${micLevel.toInt()} of 100" })
    Text("Noise removal", style = Type.big.copy(fontSize = 16.sp), modifier = Modifier.padding(top = 12.dp))
    val levels = listOf(TvRules.NR_NONE to "None", TvRules.NR_STANDARD to "Standard", TvRules.NR_STRONG to "Strong", TvRules.NR_BOTH to "Both")
    ChoiceRow(levels.size, Modifier.padding(top = 6.dp, bottom = 8.dp), minWidth = 88.dp) { i, m ->
        val (v, w) = levels[i]
        MButton(w, m, if (t.nr == v) Kind.Accent else Kind.Normal) { TvParent.setNoise(tvKey, v) }
    }
    if (!TvParent.k5(app, tvKey)) Note(UPDATE_FIRST)
    ToggleRow("Remote mic", "Talk to type on the TV", t.dictation) { on -> TvParent.change(tvKey) { it.copy(dictation = on) } }
}

/** The TV's name on this phone, and this parent's name on the TV. */
@Composable
private fun Names(app: Muninn, t: ManagedTv, tvName: String) {
    var naming by remember { mutableStateOf("") }
    MenuRow("TV name", tvName) { naming = "tv" }
    MenuRow("Your name on the TV", t.myName.ifBlank { "Parent" }) { naming = "me" }
    when (naming) {
        "tv" -> TextDialog("TV name", tvName, TvRules.MAX_NAME, onCancel = { naming = "" }) { name -> app.chat.rename(t.key, name); naming = "" }
        "me" -> TextDialog("Your name on the TV", t.myName, TvRules.MAX_NAME, onCancel = { naming = "" }) { name ->
            TvParent.change(t.key) { it.copy(myName = name) }; naming = ""
        }
    }
}

/** Send an app over the Wi-Fi, update now, and the TV's Auto-update. */
@Composable
private fun Apps(app: Muninn, t: ManagedTv) {
    val tvKey = t.key
    val context = LocalContext.current
    var sendingApp by remember { mutableStateOf(false) }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) Thread {
            val files = io.github.ravenor907.muninn.screens.Share.copyIn(context, uris, app.net.transfer.value.active)
            sendAppToTv(app, tvKey, files.filter { AppFiles.isApk(it.name) })
        }.start()
    }
    MenuRow("Send an app", "") { sendingApp = true }
    MenuRow("Update now", t.app) {
        if (TvParent.updateTv(app, tvKey)) Ui.say("The TV is updating")
        else Ui.say(UPDATE_FIRST, true)
    }
    ToggleRow("Auto-update", "", t.upd) { on -> TvParent.setUpd(tvKey, on) }
    if (!TvParent.k7(app, tvKey)) Note(UPDATE_FIRST)
    if (sendingApp) io.github.ravenor907.muninn.screens.AppPicker(
        onCancel = { sendingApp = false },
        onFile = { sendingApp = false; try { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) } catch (e: Exception) { Ui.say("This phone has no file picker.", true) } },
    ) { a ->
        sendingApp = false
        Thread { sendAppToTv(app, tvKey, AppFiles.copyOut(context, a.pkg)) }.start()
    }
}

/** Asks the TV to turn on each permission it still needs; the TV shows Android's prompt. */
@Composable
private fun Permissions(app: Muninn, t: ManagedTv) {
    val k8 = TvParent.k8(app, t.key)
    Note(if (k8) "Press Allow on the TV." else UPDATE_FIRST)
    for (id in TvRules.PERMS) {
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
            if (state != "On") MButton("Ask TV", enabled = k8) {
                if (TvParent.askAllow(app, t.key, id)) Ui.say("Asked the TV") else Ui.say(UPDATE_FIRST, true)
            }
        }
    }
}

/** What Muninn takes on the TV, and Clear history. */
@Composable
private fun Storage(app: Muninn, t: ManagedTv) {
    MenuRow("Used", when {
        !TvParent.k3(app, t.key) -> UPDATE_FIRST
        t.used < 0 -> "Asking…"
        else -> sizeText(t.used)
    }) {}
    MenuRow("Clear history", "", danger = true) {
        Ui.sure(Ask("Clear the TV's history?", "Erases every message on the TV.", "Clear", danger = true) {
            if (TvParent.clearTv(app, t.key)) Ui.say("The TV is clearing its history")
            else Ui.say(UPDATE_FIRST, true)
        })
    }
}

/** How long messages stay on the TV. */
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

/** Disconnect: the TV erases everything and goes back to pairing. Held to confirm. */
@Composable
private fun Disconnect(tvKey: String, onBack: () -> Unit) {
    Note("Erases the TV and unpairs it.")
    MButton("Disconnect TV", Modifier.fillMaxWidth().padding(top = 12.dp), Kind.Danger) {
        Ui.sure(Ask("Disconnect this TV?", "Erases the TV and unpairs it.\nAll parent phones lost? Clear Muninn's data on the TV.",
            "Disconnect", danger = true, hold = true,
            extra = "Make it a normal TV", onExtra = { TvParent.disconnect(tvKey, free = true); onBack() }) { TvParent.disconnect(tvKey); onBack() })
    }
}

private fun keepName(days: Int) = if (days <= TvRules.MIN_KEEP) "1 day" else "$days days"

// ------------------------------------------------------------------ pieces

/**
 * A parent sends an app ([files]: one app's APK and its parts, or .apk files picked by hand) to the TV: first
 * their SHA-256s in room tvconfig (7.9), then the files themselves over the Wi-Fi. Runs off the main thread.
 */
private fun sendAppToTv(app: Muninn, tvKey: String, files: List<java.io.File>) {
    if (files.isEmpty()) { Ui.say("Pick an app or an .apk file.", true); return }
    val dev = app.net.deviceFor(tvKey)
    if (dev == null) { Ui.say("The TV isn't on this Wi-Fi.", true); return }
    if (app.net.transfer.value.active) { Ui.say("Wait for the transfer to finish.", true); return }
    val name = AppFiles.group(files) { it.name }.joinToString(", ") { it.first }
    val parts = try { files.map { TvApps.Part(AppFiles.sha256(it), it.length()) } } catch (e: Exception) { Ui.say("$name couldn't be read.", true); return }
    if (!TvParent.announceApp(app, tvKey, name, parts)) { Ui.say(UPDATE_FIRST, true); return }
    app.net.send(dev.id, files)
    Ui.say("Sending $name. Press Install on the TV.")
}

/** Pick one contact; [waiting]: people who haven't accepted this phone yet. */
@Composable
private fun InviteDialog(choices: List<Pair<String, String>>, waiting: List<Pair<String, String>>, title: String,
                         onCancel: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (choices.isEmpty() && waiting.isEmpty()) Text("Add them as a contact first.", style = Type.small)
                choices.forEach { (k, name) -> PersonRow(name, null) { onPick(k) } }
                waiting.forEach { (k, name) -> PersonRow(name, WAITING) { onPick(k) } }
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
private fun ItemRow(name: String, note: String?, onRemove: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
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
        title = { Text("Family group", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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

@Composable
private fun TextDialog(title: String, start: String, max: Int, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(start) }
    val clean = TvRules.clean(text, max)
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(text, { v -> text = if (v.codePointCount(0, v.length) <= max) v else text }, "Up to $max letters")
                Text("${clean.codePointCount(0, clean.length)} of $max", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { MButton("Save", kind = Kind.Accent, enabled = clean.isNotEmpty()) { if (clean.isNotEmpty()) onSave(clean) } },
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}
