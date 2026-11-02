package io.github.ravenor907.muninn.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.ChatFlags
import io.github.ravenor907.muninn.net.Folders
import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Framed
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.MenuRow
import io.github.ravenor907.muninn.ui.NavRow
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/**
 * A private note about a person, kept encrypted in the vault ("people.json"), on this phone only. Pinned chats live in
 * ChatFlags and what each person may do in the encrypted contacts (Contact.noCalls, noFiles, hideActive), where Chat
 * enforces them; [all] mirrors both for the screens. Pins and choices still found in this file move there on the first load.
 */
object People {
    /** What a person may do with me. All on unless switched off. */
    data class Can(val calls: Boolean = true, val files: Boolean = true, val active: Boolean = true)

    data class State(val pinned: List<String> = emptyList(), val notes: Map<String, String> = emptyMap(), val can: Map<String, Can> = emptyMap())

    val all = MutableStateFlow(State())
    private var app: Muninn? = null
    private fun file(a: Muninn) = File(a.filesDir, "people.json")

    /** Once the vault is open. */
    fun load(a: Muninn) {
        if (app === a) return
        app = a
        val o = try { a.vault.read(file(a))?.let { JSONObject(it) } } catch (e: Exception) { null }
        val notes = o?.optJSONObject("notes")
        all.value = State(notes = notes?.keys()?.asSequence()?.associateWith { notes.optString(it) }.orEmpty())
        // Pins and choices still in this file go to their own place, once.
        val pins = o?.optJSONArray("pinned")
        val can = o?.optJSONObject("can")
        if (pins != null || can != null) {
            if (pins != null) for (i in pins.length() - 1 downTo 0) a.chat.flags.pin(pins.getString(i), true)
            can?.keys()?.forEach { k -> can.getJSONObject(k).let { apply(a, k, Can(it.optBoolean("c", true), it.optBoolean("f", true), it.optBoolean("a", true))) } }
            set(all.value)
        }
        refresh()
    }

    private fun set(s: State) {
        all.value = s
        val a = app ?: return
        val o = JSONObject().put("notes", JSONObject().apply { s.notes.forEach { (k, v) -> put(k, v) } })
        try { a.vault.write(file(a), o.toString()) } catch (_: Exception) {}
    }

    /** Pins and choices as they are now, for the screens. */
    private fun refresh() {
        val a = app ?: return
        all.value = all.value.copy(pinned = a.chat.flags.all.value.pinned,
            can = a.contacts.all.value.filter { it.noCalls || it.noFiles || it.hideActive }.associate { it.chatKey to Can(!it.noCalls, !it.noFiles, !it.hideActive) })
    }

    private fun apply(a: Muninn, key: String, c: Can) {
        a.chat.setCallsAllowed(key, c.calls)
        a.chat.setFilesAllowed(key, c.files)
        a.chat.setShowsActive(key, c.active)
    }

    fun pinned(key: String) = app?.chat?.flags?.pinned(key) == true
    fun pin(key: String, on: Boolean) { app?.chat?.flags?.pin(key, on); refresh() }
    fun note(key: String): String = all.value.notes[key].orEmpty()
    fun setNote(key: String, text: String) = all.value.let { s ->
        val t = text.trim().take(500)
        set(s.copy(notes = if (t.isEmpty()) s.notes - key else s.notes + (key to t)))
    }

    /** What a person may do: from the contact, where Chat enforces it (calls and video, files, active status). */
    fun can(key: String): Can = app?.chat?.let { Can(it.callsAllowed(key), it.filesAllowed(key), it.showsActive(key)) } ?: Can()
    fun setCan(key: String, c: Can) { val a = app ?: return; apply(a, key, c); refresh() }
}

/** The three starting folders, used as a person's role. "None" is in none of them. */
private val ROLES = Folders.DEFAULTS

/** What a role changes, in a few words. */
internal fun roleWords(role: String?): String = when (role) {
    "Family" -> "Family: you can keep messages up to 30 days"
    "Work" -> "Work: you can keep messages up to 30 days"
    "Folk" -> "Folk: messages stay 7 days"
    else -> "None: messages stay 7 days"
}

/**
 * How long a 1:1 chat keeps messages, in plain words, and how to keep them longer: the same in Person details and
 * the chat's "Keep longer". The rule stays: only someone in your Family or Work folder can be kept longer (7, 14 or
 * 30 days), and both phones keep the shorter of the two choices. [folders] is read so it redraws when a role changes.
 */
@Composable
internal fun KeepChoices(app: Muninn, key: String, name: String, folders: List<Folders.Folder>) {
    val inRole = ROLES.firstOrNull { r -> folders.any { it.name.equals(r, true) && key in it.keys } }
    val roles = ROLES + "None"
    Text("Who is $name to you?", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 8.dp))
    ChoiceRow(roles.size, Modifier.padding(top = 6.dp), minWidth = 72.dp, gap = 8.dp) { i, m ->
        val r = roles[i]
        DetailChip(r, if (r == "None") inRole == null else inRole == r, m) { setRole(app, key, if (r == "None") null else r) }
    }
    Text(roleWords(inRole), style = Type.small, modifier = Modifier.padding(top = 6.dp))
    if (app.chat.contacts.contact(key) == null) return
    val days = app.chat.chatDays(key)
    if (app.chat.isClose(key)) {
        val mine = app.chat.myDays(key)
        Text("Keep messages for", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
        ChoiceRow(Limits.CHOICES.size, Modifier.padding(top = 6.dp), gap = 8.dp) { i, m ->
            val d = Limits.CHOICES[i]
            DetailChip("$d days", mine == d, m) { app.chat.setKeep(key, d) }
        }
        Text(if (days < mine) "$name keeps $days days, so this chat does too. Ask $name to pick $mine days for you as well."
            else "Both phones keep $days days.", style = Type.small, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    } else Text("Messages with $name delete after $days days. To keep them longer, pick Family or Work above, then choose 14 or 30 days. " +
        "Only people in those two get longer, so other chats can't pile up and leak. $name picks too, and the shorter time wins.",
        style = Type.small, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
}

/** "Keep longer", from the top of a 1:1 chat: [KeepChoices] in a small window. */
@Composable
internal fun KeepLongerDialog(app: Muninn, key: String, name: String, onDismiss: () -> Unit) {
    val folders by app.chat.folders.all.collectAsState()
    app.chat.contacts.all.collectAsState().value   // redraws when their days or mine change
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.panel,
        shape = Corner,
        title = { Text("How long messages stay", style = Type.big) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { KeepChoices(app, key, name, folders) } },
        confirmButton = { io.github.ravenor907.muninn.ui.MButton("Done") { onDismiss() } },
    )
}

/** Puts [key] in the [role] folder (making it if it was deleted) and takes it out of the other two. Null: none. */
private fun setRole(app: Muninn, key: String, role: String?) {
    val fs = app.chat.folders.all.value
    ROLES.forEach { r -> fs.filter { it.name.equals(r, true) }.forEach { f -> if (r != role && key in f.keys) app.chat.folders.put(f.id, key, false) } }
    if (role == null) return
    val id = fs.firstOrNull { it.name.equals(role, true) }?.id ?: app.chat.folders.add(role) ?: return
    app.chat.folders.put(id, key, true)
}

/** The 4 emoji check, the same from every place that offers it. */
internal fun checkEmoji(app: Muninn, key: String, name: String) {
    Ui.sure(Ask("Check $name", "${app.chat.emojiWith(key)}\n${app.chat.codeWith(key)}\n\nSame 4 emoji on their screen? You're safe.",
        "They match", onConfirm = { app.chat.verify(key, name) }))
}

/** The Kids' TV menu as (section id, label), Disconnect last. */
private fun tvRows(): List<Pair<String, String>> = io.github.ravenor907.muninn.tv.ParentTvMenu.items

private fun tvIcon(id: String) = when (id) {
    "people" -> "people"; "parents" -> "user"; "quick" -> "chat"; "sound" -> "audio"; "names" -> "edit"; "apps" -> "tv"
    "permissions" -> "shield"; "storage" -> "folder"; "keep" -> "clock"; else -> "close"
}

/** Opens a Kids' TV section from anywhere: the chat column shows it, and Back returns to where it was opened. */
internal fun openTvSection(key: String, section: String) {
    ChatNav.back = ChatNav.open.takeUnless { it.startsWith("tvset/") || it.startsWith("details/") } ?: ""
    ChatNav.open = "tvset/$section/$key"
}

/** Opens a person's details page, coming back to where it was opened. */
internal fun openDetails(key: String) {
    ChatNav.back = ChatNav.open.takeUnless { it.startsWith("tvset/") || it.startsWith("details/") } ?: ""
    ChatNav.open = "details/$key"
}

/**
 * Every item's three-dot menu, in one sheet that looks the same everywhere: a person (a contact, someone met close
 * by, a Kids' TV), a group, a group member (with the group's admin actions), and "list" for the chat list itself.
 * [key]: a chat key, "g:" and a group id, or "list". [onChats]: shows the Chats tab (menus opened from Contacts),
 * [onLock]: locks Muninn now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemMenu(app: Muninn, key: String, onChats: () -> Unit, onLock: () -> Unit, done: () -> Unit) {
    // Cap g2: a group message's sheet may open someone's menu straight on Volume or Timeout.
    var page by remember(key) { mutableStateOf(ModNav.page.also { ModNav.page = "" }) }
    if (page == "volume" || page == "timeout") {
        val who = app.chat.nameOf(key)
        if (page == "volume") VolumeDialog(app, key, who) { done() } else TimeoutDialog(app, ChatNav.menuGroup, key, who) { done() }
        return
    }
    if (page == "mute") {
        val g = if (key.startsWith("g:")) app.chat.groups.group(key.removePrefix("g:")) else null
        MuteDialog(app, key, g?.state?.name ?: app.chat.nameOf(key)) { done() }
        return
    }
    if (page == "folder") {
        NameDialog("New folder", "", "Add", "Folder name") { n -> if (n != null) app.chat.folders.add(n); done() }
        return
    }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = done, sheetState = sheet, containerColor = Palette.panel, shape = Corner, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).navigationBarsPadding()) {
            when {
                key == "list" -> ListMenu(app, onLock, { page = "folder" }, done)
                page == "notify" -> NotifyMenu(app, key) { page = "" }
                key.startsWith("g:") -> GroupMenu(app, key.removePrefix("g:"), page == "more", { page = it }, done)
                else -> PersonMenu(app, key, onChats, page == "more", { page = it }, done)
            }
        }
    }
}

@Composable
private fun MenuTitle(text: String, avatar: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        avatar()
        Text(text, style = Type.big, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).height(1.dp).background(Palette.hairline))
}

/** The chat list's own menu: refresh, a new folder, whether Muninn is online, and locking now. */
@Composable
private fun ColumnScope.ListMenu(app: Muninn, onLock: () -> Unit, onFolder: () -> Unit, done: () -> Unit) {
    val up by app.nostr.connected.collectAsState()
    MenuTitle("Chats")
    MenuRow("refresh", "Refresh") { done(); app.refreshChats(); app.refreshNearby(); Ui.say("Checking for new messages…") }
    MenuRow("folder", "New folder") { onFolder() }
    MenuRow("people", "Requests", value = if (ChatNav.asks > 0) "${ChatNav.asks}" else "") { done(); ChatNav.open = "requests" }
    MenuRow("download", "Archived", value = if (ChatNav.archived > 0) "${ChatNav.archived}" else "") { done(); ChatNav.open = "archived" }
    MenuRow("delete", "Trash", value = if (ChatNav.trashed > 0) "${ChatNav.trashed}" else "") { done(); ChatNav.open = "trash" }
    // Cap f2: every file coming or going, with how many are running.
    val files by io.github.ravenor907.muninn.net.FileLink.all.collectAsState()
    val nearby by app.net.transfer.collectAsState()
    val running = transfersRunning(files, nearby.active)
    MenuRow("download", "Transfers", value = if (running > 0) "$running" else "") { done(); ChatNav.open = "transfers" }
    // Bug reports that came in: only on the Labs contact's own phone, and only while Labs mode is on.
    if (app.chat.isLabs() && Labs.on(app)) {
        val reports by app.chat.labs.reports.collectAsState()
        MenuRow("info", "Labs reports", value = if (reports.isNotEmpty()) "${reports.size}" else "") { done(); ChatNav.open = io.github.ravenor907.muninn.net.Chat.LABS_PAGE }
    }
    // Said in words too, never by the dot's color alone.
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(horizontal = 6.dp).size(8.dp).clip(CircleShape).background(if (up > 0) Palette.online else Palette.textFaint))
        Spacer(Modifier.width(18.dp))
        Text(if (up > 0) "Online" else "Offline", style = Type.normal.copy(color = Palette.textSoft))
    }
    if (app.vault.hasPassword) MenuRow("lock", "Lock now") { done(); onLock() }
    // Labs mode: report a bug (a screenshot of the list as it is, then the report screen).
    val context = androidx.compose.ui.platform.LocalContext.current
    if (Labs.on(app)) MenuRow("info", "Report a bug") { done(); Labs.report(context) }
}

/**
 * A person's menu, short enough for one screen: a row of round buttons (Search, Media, Mute, Details), then
 * Notifications, Mark unread and Start a group, then More. More ([more]) has the rest: Move up and down, Hide (someone
 * nearby who isn't a contact), then the red ones, Block, Delete chat and Delete contact. A Kids' TV has the same shape,
 * with its first sections in the list and the rest under More. From a group's People page, the group's actions too.
 */
@Composable
private fun ColumnScope.PersonMenu(app: Muninn, key: String, onChats: () -> Unit, more: Boolean, go: (String) -> Unit, done: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()
    val flags by app.chat.flags.all.collectAsState()
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val contact = app.chat.contacts.contact(key)
    val name = app.chat.nameOf(key)
    val inGroup = ChatNav.menuGroup.takeIf { it.isNotEmpty() }?.let { app.chat.groups.group(it) }
    val tv = tvs[key]?.disconnecting == false
    val hasChat = tv || contact != null || app.chat.conversations.value[key].orEmpty().isNotEmpty()
    if (more) MenuTitle("More for $name") else MenuTitle(name) { io.github.ravenor907.muninn.ui.Avatar(key, name, 40.dp, showDot = true) }
    fun open(action: String) { done(); ChatNav.space = ""; ChatNav.action = action; ChatNav.open = key; onChats() }
    val muted = (flags.muted[key] ?: 0L) > System.currentTimeMillis()
    // Delete chat: the messages go from this phone; the person stays (a Kids' TV's settings too).
    val deleteChat: @Composable () -> Unit = {
        MenuRow("delete", "Delete chat", danger = true) {
            done()
            Ui.sure(Ask("Delete chat with $name?", if (tv) "Its messages go from this phone. TV settings stay." else "Its messages go from this phone.",
                "Delete chat", danger = true, onConfirm = { app.chat.deleteChat(key); if (ChatNav.open == key) ChatNav.open = ""; Ui.say("Chat deleted.") }))
        }
    }
    if (!more) QuickRow {
        if (hasChat) {
            QuickButton("search", "Search") { open("search") }
            QuickButton("image", "Media") { open("media") }
        }
        QuickButton(if (muted) "sound" else "mute", if (muted) "Unmute" else "Mute", on = muted) {
            if (muted) { done(); app.chat.flags.mute(key, 0); Ui.say("$name isn't muted.") } else go("mute")
        }
        QuickButton("user", "Details") { done(); ChatNav.space = ""; openDetails(key); onChats() }
    }
    if (tv) {
        // A Kids' TV this phone looks after: its first sections here, the rest under More, Disconnect TV last.
        val rows = tvRows().filter { it.first != "disconnect" }
        val section: @Composable (Pair<String, String>) -> Unit = { (id, label) -> MenuRow(tvIcon(id), label) { done(); openTvSection(key, id); onChats() } }
        if (!more) {
            rows.take(TV_FIRST).forEach { section(it) }
            MenuRow("clock", "Notifications") { go("notify") }
            MoreRow { go("more") }
            return
        }
        rows.drop(TV_FIRST).forEach { section(it) }
        MenuGap()
        deleteChat()
        tvRows().firstOrNull { it.first == "disconnect" }?.let { (id, label) ->
            MenuRow(tvIcon(id), label, danger = true) { done(); openTvSection(key, id); onChats() }
        }
        // No Block for a TV this phone looks after: Disconnect TV is how it goes.
        MenuRow("back", "Back") { go("") }
        return
    }
    val member = inGroup != null && !inGroup.readOnly && inGroup.state.has(key)
    if (!more) {
        // In a group: Add as contact (someone I'm not in touch with yet).
        if (member && contact == null) MenuRow("plus", "Add as contact") {
            done(); app.chat.askToAdd(inGroup!!.g, key, name, inGroup.state.member(key)?.p.orEmpty()); Ui.say("Asked $name.")
        }
        MenuRow("clock", "Notifications") { go("notify") }
        if (inGroup == null) MarkUnreadRow(app, key, done)
        // The New group picker with them already picked; there, Call them rings everyone picked.
        if (inGroup == null && contact != null && !contact.groupOnly) StartGroupRow(key, onChats, done)
        // In a group: Mute for me and Volume for everyone, Timeout for admins (cap g2).
        if (inGroup != null) ModRows(app, inGroup.g, key, name, danger = false, go = go, done = done)
        MoreRow { go("more") }
        return
    }
    if (inGroup == null) MoveRows(app, key)
    if (inGroup != null && contact != null && !contact.groupOnly) StartGroupRow(key, onChats, done)
    // From a group's People page: what its admins can do.
    if (member) {
        val s = inGroup!!.state
        val me = app.identity.publicKey
        val g = inGroup.g
        if (s.isAdmin(me) && key != s.owner) {
            if (s.isAdmin(key)) { if (s.owner == me) MenuRow("people", "Remove admin") {
                done(); app.chat.groups.setAdmin(g, key, false)?.let { Ui.say(it, true) } ?: Ui.say("$name isn't an admin.")
            } } else MenuRow("people", "Make admin") {
                done(); app.chat.groups.setAdmin(g, key, true)?.let { Ui.say(it, true) } ?: Ui.say("$name is an admin.")
            }
        }
        if (s.owner == me) MenuRow("shield", "Make owner") {
            done()
            Ui.sure(Ask("Make $name the owner?", "You stay an admin.", "Make owner", onConfirm = { app.chat.groups.handOver(g, key)?.let { Ui.say(it, true) } }))
        }
    }
    // Someone nearby who isn't a contact: Hide takes them off the Nearby list for good, with no notifications.
    if (contact == null && inGroup == null) MenuRow("hide", "Hide") {
        done(); app.chat.flags.hide(key, true); if (ChatNav.open == key) ChatNav.open = ""; Ui.say("$name is hidden.")
    }
    // The red ones, last, apart from the rest.
    MenuGap()
    if (inGroup != null && !inGroup.readOnly) {
        val s = inGroup.state
        val me = app.identity.publicKey
        if (s.isAdmin(me) && key != s.owner && s.has(key) && (!s.isAdmin(key) || s.owner == me)) MenuRow("close", "Remove $name from group…", danger = true) {
            done()
            Ui.sure(Ask("Remove $name from ${s.name}?", "Their phone says they were removed.", "Remove", danger = true,
                onConfirm = { app.chat.groups.remove(inGroup.g, key)?.let { Ui.say(it, true) } }))
        }
        ModRows(app, inGroup.g, key, name, danger = true, go = go, done = done)
    }
    MenuRow("close", "Block", danger = true) {
        done()
        Ui.sure(Ask("Block $name?", "They aren't told.", "Block", danger = true, onConfirm = { app.chat.block(key); Ui.say("$name blocked.") }))
    }
    if (inGroup == null) deleteChat()
    if (contact != null) MenuRow("delete", "Delete contact", danger = true) {
        done()
        Ui.sure(Ask("Delete ${contact.shown}?", TRASH_NOTE, "Delete", danger = true,
            onConfirm = { app.chat.trashContact(key); if (ChatNav.open == key) ChatNav.open = ""; Ui.say("${contact.shown} is in Trash.") }))
    }
    MenuRow("back", "Back") { go("") }
}

/** How many Kids' TV sections show on its menu's first page; the rest are under More. */
private const val TV_FIRST = 3

/** Start a group, with [key] already picked. */
@Composable
private fun StartGroupRow(key: String, onChats: () -> Unit, done: () -> Unit) {
    MenuRow("people", "Start a group") {
        done(); ChatNav.space = ""; ChatNav.groupWith = setOf(key); ChatNav.open = "newgroup"; onChats()
    }
}

/** The top of a person's or group's menu: up to four round buttons, side by side. */
@Composable
private fun QuickRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), content = content)
    MenuGap()
}

/** One round button: an icon in a circle, with a short word under it. [on]: filled, like Mute while muted. */
@Composable
private fun RowScope.QuickButton(icon: String, label: String, on: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(Corner).clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (on) Palette.accentFill else Palette.raised)
            .border(1.dp, if (on) Palette.accent else Palette.lineMuted, CircleShape), contentAlignment = Alignment.Center) {
            MIcon(icon, Palette.accent, 22.dp)
        }
        Text(label, style = Type.small.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp))
    }
}

/** A thin line between parts of a menu. */
@Composable
private fun MenuGap() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).height(1.dp).background(Palette.hairline))
}

/** The last row of a menu's first page: More opens the rest in the same sheet. */
@Composable
private fun MoreRow(onClick: () -> Unit) {
    MenuRow("more", "More", onClick = onClick)
}

/** Move up and Move down on the chat list, for anyone who can't drag (keys, switch access, TalkBack). Nothing when it isn't listed. */
@Composable
private fun MoveRows(app: Muninn, key: String) {
    @Suppress("UNUSED_VARIABLE") val fs by app.chat.folders.all.collectAsState()   // redraw after each move
    @Suppress("UNUSED_VARIABLE") val order by app.chat.folders.top.collectAsState()
    @Suppress("UNUSED_VARIABLE") val fl by app.chat.flags.all.collectAsState()
    val (up, down) = listMoves(app, key)
    if (up == null && down == null) return
    MenuRow("upload", "Move up", enabled = up != null) { up?.invoke() }
    MenuRow("download", "Move down", enabled = down != null) { down?.invoke() }
}

/** Mark unread (or Mark read), on a person's menu. */
@Composable
private fun MarkUnreadRow(app: Muninn, key: String, done: () -> Unit) {
    val fl by app.chat.flags.all.collectAsState()
    val newOnes by app.chat.unread.collectAsState()
    if (key in fl.unread || key in newOnes) MenuRow("chat", "Mark read") { app.chat.markRead(key); done(); Ui.say("Marked read.") }
    else MenuRow("chat", "Mark unread") {
        app.chat.flags.markUnread(key, true); if (ChatNav.open == key) ChatNav.open = ""; done(); Ui.say("Marked unread.")
    }
}

/**
 * A chat's notifications: every message, one until it's opened (Reduce notifications), or for a group only @mentions
 * and replies to me; and for a person whether they always come through. Calls ring either way.
 */
@Composable
private fun ColumnScope.NotifyMenu(app: Muninn, key: String, back: () -> Unit) {
    MenuTitle("Notifications")
    val flags by app.chat.flags.all.collectAsState()
    val group = key.startsWith("g:")
    val big = group && (app.chat.groups.group(key.removePrefix("g:"))?.state?.members?.size ?: 0) > ChatFlags.BIG_GROUP
    val mode = ChatFlags.modeFor(flags.notify[key], app.identity.reduceNotifications, group, big)
    fun pick(m: String) { app.chat.flags.setNotify(key, m) }
    MenuRow("chat", "Every message", value = if (mode == ChatFlags.EVERY) "On" else "") { pick(ChatFlags.EVERY) }
    MenuRow("clock", "One until I open it", value = if (mode == ChatFlags.ONE) "On" else "") { pick(ChatFlags.ONE) }
    if (group) MenuRow("people", "Only mentions and replies to me", value = if (mode == ChatFlags.MENTIONS) "On" else "") { pick(ChatFlags.MENTIONS) }
    if (group) {
        // The same nicknames as Settings › Notifications, saved as they're typed.
        var nick by remember { mutableStateOf(app.identity.mentionNames) }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("Also mention me as", style = io.github.ravenor907.muninn.ui.Type.small)
            Field(nick, { nick = it.take(80); app.identity.mentionNames = nick }, "Nicknames, like tay, T", Modifier.padding(top = 4.dp))
        }
    }
    if (!group) {
        var ra by remember(key) { mutableStateOf(app.power.isRightAway(key)) }
        Column(Modifier.padding(horizontal = 20.dp)) {
            ToggleRow("Always right away", "Even when saving battery", ra) { app.power.setRightAway(key, it); ra = it }
        }
    }
    MenuRow("back", "Back") { back() }
}

/**
 * A group's menu, like a person's: round buttons (Search, Media, Mute, Details for Group settings), then
 * Notifications, Invite people and People, then More. More ([more]) has Move up and down, Call everyone (it asks) and,
 * last in red, Leave (or Delete, once left).
 */
@Composable
private fun ColumnScope.GroupMenu(app: Muninn, g: String, more: Boolean, go: (String) -> Unit, done: () -> Unit) {
    val all by app.chat.groups.all.collectAsState()
    val flags by app.chat.flags.all.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Gone meanwhile: the sheet closes, after this frame (never during composition).
    val grp = all[g] ?: run { LaunchedEffect(g) { done() }; return }
    val s = grp.state
    val key = Folders.groupKey(g)
    val me = app.identity.publicKey
    fun page(p: String) { done(); if (ChatNav.wide) ChatNav.space = g; ChatNav.open = "$key/$p" }
    if (!more) {
        MenuTitle(s.name)
        val muted = (flags.muted[key] ?: 0L) > System.currentTimeMillis()
        QuickRow {
            QuickButton("search", "Search") { done(); ChatNav.action = "search"; ChatNav.openGroup(g) }
            QuickButton("image", "Media") { done(); ChatNav.action = "media"; ChatNav.openGroup(g) }
            QuickButton(if (muted) "sound" else "mute", if (muted) "Unmute" else "Mute", on = muted) {
                if (muted) { done(); app.chat.flags.mute(key, 0); Ui.say("${s.name} isn't muted.") } else go("mute")
            }
            QuickButton("gear", "Details") { page("settings") }
        }
        MenuRow("clock", "Notifications") { go("notify") }
        if (!grp.readOnly && (s.isAdmin(me) || GroupLinks.canInvite(app, g))) MenuRow("plus", "Invite people") { page("add") }
        MenuRow("people", "People", value = "${s.members.size}") { page("people") }
        MoreRow { go("more") }
        return
    }
    MenuTitle("More for ${s.name}")
    MoveRows(app, key)
    // Rings every member, so it sits under More and asks first. To just talk, there are voice rooms.
    if (!grp.readOnly && s.members.size > 1) MenuRow("call", "Call everyone") {
        done()
        val n = s.members.size - 1
        Ui.sure(Ask("Ring all $n ${if (n == 1) "person" else "people"}?", "Their phones and computers ring now. To just talk, join a voice room.", "Ring everyone",
            extra = if (s.members.size > 2) "Pick people" else null, onExtra = { ChatNav.callSome = g },
            onConfirm = { RoomActivity.call(context, g, emptyList()) }))
    }
    MenuGap()
    if (!grp.readOnly) MenuRow("close", "Leave", danger = true) {
        done()
        if (s.owner == me && s.members.size > 1) Ui.say("You own ${s.name}. Make someone else owner first.", true)
        // One step: leave and delete it (no read-only copy left behind), or keep its history as the second choice.
        // Its voice room is left at once too.
        else Ui.sure(Ask("Leave ${s.name}?", "You stop getting its messages, and it's deleted from this phone.", "Leave", danger = true,
            extra = "Leave, keep history", onExtra = {
                if (io.github.ravenor907.muninn.net.call.RoomManager.room.value?.g == g) io.github.ravenor907.muninn.net.call.RoomManager.leave()
                app.chat.groups.leave(g)?.let { Ui.say(it, true) } ?: Ui.say("You left ${s.name}.")
            },
            onConfirm = {
                if (io.github.ravenor907.muninn.net.call.RoomManager.room.value?.g == g) io.github.ravenor907.muninn.net.call.RoomManager.leave()
                // Its history goes now; the leave keeps going until everyone has it.
                val err = app.chat.leaveAndDeleteGroup(g)
                if (err != null) Ui.say(err, true)
                else { ChatNav.open = ""; ChatNav.space = ""; Ui.say("You left ${s.name}.") }
            }))
    } else MenuRow("delete", "Delete", danger = true) {
        done()
        Ui.sure(Ask("Delete ${s.name}?", "This can't be undone.", "Delete", danger = true,
            onConfirm = { if (app.chat.deleteGroup(g)) { ChatNav.open = ""; ChatNav.space = "" } }))
    }
    MenuRow("back", "Back") { go("") }
}

/**
 * Person details: one page for everything about a person on this phone. Nickname and note, role and folders, how
 * long the chat keeps messages, its color and place, what they can do, the emoji check, direct calls, and introducing them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonDetails(app: Muninn, key: String, back: () -> Unit) {
    val folders by app.chat.folders.all.collectAsState()
    val flags by app.chat.flags.all.collectAsState()
    val people by People.all.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()
    val contact = app.chat.contacts.contact(key)
    val name = app.chat.nameOf(key)
    var nick by remember(key) { mutableStateOf(contact?.nickname ?: "") }
    var note by remember(key) { mutableStateOf(People.note(key)) }
    var introducing by remember { mutableStateOf(false) }
    val nickNow by rememberUpdatedState(nick)
    val noteNow by rememberUpdatedState(note)
    // Saved on the way out, whichever way that is.
    DisposableEffect(key) {
        onDispose {
            val c = app.chat.contacts.contact(key)
            if (c != null && nickNow.trim() != (c.nickname ?: "")) app.chat.rename(key, nickNow)
            if (noteNow.trim() != People.note(key)) People.setNote(key, noteNow)
        }
    }
    BackHandler { back() }
    if (introducing) IntroduceDialog(app, key, name) { introducing = false }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar(name, "Back") { back() }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Framed("Name and note") {
                if (contact != null) {
                    Field(nick, { nick = it.take(40) }, "Nickname (${contact.name})", Modifier.padding(top = 8.dp))
                }
                Field(note, { note = it.take(500) }, "Note, only you see it", Modifier.padding(vertical = 8.dp), single = false)
            }

            // Who they are to you (Family, Work, Folk) decides how long messages stay, so it's said that way.
            Framed("How long messages stay") {
                KeepChoices(app, key, name, folders)
                val others = folders.filter { f -> ROLES.none { it.equals(f.name, true) } }
                if (others.isNotEmpty()) {
                    Text("Your other folders", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 4.dp))
                    FlowRow(Modifier.padding(top = 6.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        others.forEach { f -> val on = key in f.keys; DetailChip(f.name, on) { app.chat.folders.put(f.id, key, !on) } }
                    }
                }
            }

            Framed("This chat") {
                ToggleRow("Pin to top", "", key in people.pinned) { People.pin(key, it) }
                ToggleRow("Archive", "", key in flags.archived) { app.chat.flags.archive(key, it) }
                Text("Chat color", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
                ColorChoices(app, key)
            }

            if (contact != null) Framed("They can") {
                val can = people.can[key] ?: People.can(key)
                ToggleRow("Call me", "", can.calls) { People.setCan(key, can.copy(calls = it)) }
                ToggleRow("Send me files", "Photos always arrive", can.files) { People.setCan(key, can.copy(files = it)) }
                ToggleRow("See when I'm active", "", can.active) { People.setCan(key, can.copy(active = it)) }
            }

            Framed("Safety") {
                NavRow("Compare emoji", if (app.chat.isVerified(key)) "Checked" else "Not checked yet", icon = "shield") { checkEmoji(app, key, name) }
                // How messages reach them right now (moved here from the chat's top bar).
                Text("Reaching $name by: ${routeShort(app.chat.route(key))}", style = Type.small, modifier = Modifier.padding(vertical = 6.dp))
                if (contact != null) {
                    var fast by remember(key) { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.trusted(key)) }
                    ToggleRow("Direct calls and files", "Faster, and big files go straight. They see your address", fast) {
                        io.github.ravenor907.muninn.net.call.CallManager.setTrusted(key, it); fast = it
                    }
                    NavRow("Introduce", "To another contact", icon = "people") { introducing = true }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** [key]'s chat color: the accent (none picked) or one of the accent colors, as round swatches. Only I see it. */
@Composable
private fun ColorChoices(app: Muninn, key: String) {
    val st by app.chat.flags.all.collectAsState()
    val now = io.github.ravenor907.muninn.ui.Accents.chatHex(st.colors[key])
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val names = listOf("Usual accent") + io.github.ravenor907.muninn.ui.Accents.TITLES
        (listOf("") + io.github.ravenor907.muninn.ui.Accents.CHAT).forEachIndexed { i, hex ->
            val on = hex.equals(now, ignoreCase = true)
            val c = if (hex.isEmpty()) Palette.accentOf(io.github.ravenor907.muninn.ui.Access.accent) else Palette.accentOf(hex)
            Box(Modifier.size(44.dp).clip(CircleShape).background(c)
                .border(if (on) 3.dp else 1.dp, if (on) Palette.text else Palette.line, CircleShape)
                .clickable(role = Role.RadioButton) { app.chat.flags.color(key, hex) }
                .semantics { selected = on; contentDescription = names[i] },
                contentAlignment = Alignment.Center) {
                if (on) MIcon("check", Palette.onAccentOf(c), 20.dp, weight = 2.2f)
                else if (hex.isEmpty()) Box(Modifier.size(12.dp).clip(CircleShape).background(Palette.ground))
            }
        }
    }
}

/** A sharp-cornered choice: gold-tinted when on. */
@Composable
internal fun DetailChip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 48.dp).clip(Corner).background(if (on) Palette.goldTint else Color.Transparent)
        .border(1.dp, if (on) Palette.gold else Palette.line, Corner)
        .clickable(role = Role.Button, onClick = onClick).semantics { selected = on }
        .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.normal.copy(fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) Palette.goldBright else Palette.textSoft), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A Kids' TV section, from the TV contact's menu. */
@Composable
internal fun TvSectionPage(app: Muninn, key: String, section: String, back: () -> Unit) {
    io.github.ravenor907.muninn.tv.ParentTvScreen(app, key, section = section, onBack = back)
}
