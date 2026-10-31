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
import io.github.ravenor907.muninn.net.Folders
import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Framed
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
    "Family" -> "Family: messages kept up to 30 days"
    "Work" -> "Work: messages kept up to 30 days"
    "Folk" -> "Folk: messages kept for 7 days"
    else -> "None: messages kept for 7 days"
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
    var page by remember(key) { mutableStateOf("") }
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
                key.startsWith("g:") -> GroupMenu(app, key.removePrefix("g:"), { page = "mute" }, done)
                page == "notify" -> NotifyMenu(app, key) { page = "" }
                else -> PersonMenu(app, key, onChats, { page = it }, done)
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
 * A person's menu: Search, Media, Start a group, Mute, Notifications, Person details, then Block and Delete. A Kids' TV
 * has its own sections, then Search, Media, Mute and Notifications too.
 */
@Composable
private fun ColumnScope.PersonMenu(app: Muninn, key: String, onChats: () -> Unit, go: (String) -> Unit, done: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()
    val flags by app.chat.flags.all.collectAsState()
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val contact = app.chat.contacts.contact(key)
    val name = app.chat.nameOf(key)
    val inGroup = ChatNav.menuGroup.takeIf { it.isNotEmpty() }?.let { app.chat.groups.group(it) }
    MenuTitle(name) { io.github.ravenor907.muninn.ui.Avatar(key, name, 40.dp, showDot = true) }
    fun open(action: String) { done(); ChatNav.space = ""; ChatNav.action = action; ChatNav.open = key; onChats() }
    val block: @Composable () -> Unit = {
        MenuRow("close", "Block", danger = true) {
            done()
            Ui.sure(Ask("Block $name?", "They aren't told.", "Block", danger = true, onConfirm = { app.chat.block(key); Ui.say("$name blocked.") }))
        }
    }
    val muted = (flags.muted[key] ?: 0L) > System.currentTimeMillis()
    val muteRow: @Composable () -> Unit = {
        MenuRow("mute", if (muted) "Unmute" else "Mute") {
            if (muted) { done(); app.chat.flags.mute(key, 0); Ui.say("$name isn't muted.") } else go("mute")
        }
    }
    if (tvs[key]?.disconnecting == false) {
        // A Kids' TV this phone looks after.
        val rows = tvRows()
        rows.filter { it.first != "disconnect" }.forEach { (id, label) ->
            MenuRow(tvIcon(id), label) { done(); openTvSection(key, id); onChats() }
        }
        MenuRow("search", "Search") { open("search") }
        MenuRow("image", "Media") { open("media") }
        muteRow()
        MenuRow("clock", "Notifications") { go("notify") }
        MenuRow("user", "Person details") { done(); openDetails(key); onChats() }
        rows.firstOrNull { it.first == "disconnect" }?.let { (id, label) ->
            MenuRow(tvIcon(id), label, danger = true) { done(); openTvSection(key, id); onChats() }
        }
        block()
        return
    }
    if (contact != null || app.chat.conversations.value[key].orEmpty().isNotEmpty()) {
        MenuRow("search", "Search") { open("search") }
        MenuRow("image", "Media") { open("media") }
    }
    // The New group picker with them already picked; there, Call them rings everyone picked.
    if (contact != null && !contact.groupOnly) MenuRow("people", "Start a group") {
        done(); ChatNav.space = ""; ChatNav.groupWith = setOf(key); ChatNav.open = "newgroup"; onChats()
    }
    muteRow()
    MenuRow("clock", "Notifications") { go("notify") }
    MenuRow("user", "Person details") { done(); ChatNav.space = ""; openDetails(key); onChats() }
    if (inGroup == null) MoveRows(app, key)
    // From a group's People page: what its admins can do.
    if (inGroup != null && !inGroup.readOnly) {
        val s = inGroup.state
        val me = app.identity.publicKey
        val g = inGroup.g
        if (s.isAdmin(me) && key != s.owner && s.has(key)) {
            if (s.isAdmin(key)) MenuRow("people", "Remove admin") {
                done(); app.chat.groups.setAdmin(g, key, false)?.let { Ui.say(it, true) } ?: Ui.say("$name isn't an admin.")
            } else MenuRow("people", "Make admin") {
                done(); app.chat.groups.setAdmin(g, key, true)?.let { Ui.say(it, true) } ?: Ui.say("$name is an admin.")
            }
        }
        if (s.owner == me && s.has(key)) MenuRow("shield", "Make owner") {
            done()
            Ui.sure(Ask("Make $name the owner?", "You stay an admin.", "Make owner", onConfirm = { app.chat.groups.handOver(g, key)?.let { Ui.say(it, true) } }))
        }
        if (s.isAdmin(me) && key != s.owner && s.has(key)) MenuRow("close", "Remove from group", danger = true) {
            done()
            Ui.sure(Ask("Remove $name from ${s.name}?", "Their phone says they were removed.", "Remove", danger = true,
                onConfirm = { app.chat.groups.remove(g, key)?.let { Ui.say(it, true) } }))
        }
    }
    block()
    if (contact != null) MenuRow("delete", "Delete", danger = true) {
        done()
        Ui.sure(Ask("Delete ${contact.shown}?", TRASH_NOTE, "Delete", danger = true,
            onConfirm = { app.chat.trashContact(key); if (ChatNav.open == key) ChatNav.open = ""; Ui.say("${contact.shown} is in Trash.") }))
    }
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

/** A person's notifications: whether they always come through, and marking the chat unread (or read again). */
@Composable
private fun ColumnScope.NotifyMenu(app: Muninn, key: String, back: () -> Unit) {
    MenuTitle("Notifications")
    var ra by remember(key) { mutableStateOf(app.power.isRightAway(key)) }
    val fl by app.chat.flags.all.collectAsState()
    val newOnes by app.chat.unread.collectAsState()
    val unread = key in fl.unread || key in newOnes
    Column(Modifier.padding(horizontal = 20.dp)) {
        ToggleRow("Always right away", "Even when saving battery", ra) { app.power.setRightAway(key, it); ra = it }
    }
    if (unread) MenuRow("chat", "Mark read") { app.chat.markRead(key); back(); Ui.say("Marked read.") }
    else MenuRow("chat", "Mark unread") {
        app.chat.flags.markUnread(key, true); if (ChatNav.open == key) ChatNav.open = ""; back(); Ui.say("Marked unread.")
    }
    MenuRow("back", "Back") { back() }
}

/** A group's menu: Call, People, Add people, Group settings, Mute, then Leave. */
@Composable
private fun ColumnScope.GroupMenu(app: Muninn, g: String, onMute: () -> Unit, done: () -> Unit) {
    val all by app.chat.groups.all.collectAsState()
    val flags by app.chat.flags.all.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Gone meanwhile: the sheet closes, after this frame (never during composition).
    val grp = all[g] ?: run { LaunchedEffect(g) { done() }; return }
    val s = grp.state
    val key = Folders.groupKey(g)
    val me = app.identity.publicKey
    MenuTitle(s.name)
    fun go(page: String) { done(); if (ChatNav.wide) ChatNav.space = g; ChatNav.open = "$key/$page" }
    if (!grp.readOnly && s.members.size > 1) MenuRow("call", "Call") {
        done()
        Ui.sure(Ask("Call ${s.name}?", "Rings everyone who can join.", "Call everyone",
            extra = if (s.members.size > 2) "Pick people" else null, onExtra = { ChatNav.callSome = g },
            onConfirm = { RoomActivity.call(context, g, emptyList()) }))
    }
    MenuRow("people", "People", value = "${s.members.size}") { go("people") }
    if (!grp.readOnly && (s.isAdmin(me) || GroupLinks.canInvite(app, g))) MenuRow("plus", "Add people") { go("add") }
    MenuRow("search", "Search") { done(); ChatNav.action = "search"; ChatNav.openGroup(g) }
    MenuRow("image", "Media") { done(); ChatNav.action = "media"; ChatNav.openGroup(g) }
    MenuRow("gear", "Group settings") { go("settings") }
    MoveRows(app, key)
    val muted = (flags.muted[key] ?: 0L) > System.currentTimeMillis()
    MenuRow("mute", if (muted) "Unmute" else "Mute") {
        if (muted) { done(); app.chat.flags.mute(key, 0); Ui.say("${s.name} isn't muted.") } else onMute()
    }
    if (!grp.readOnly) MenuRow("close", "Leave", danger = true) {
        done()
        if (s.owner == me && s.members.size > 1) Ui.say("You own ${s.name}. Make someone else owner first.", true)
        else Ui.sure(Ask("Leave ${s.name}?", "You stop getting its messages.", "Leave", danger = true,
            onConfirm = { app.chat.groups.leave(g)?.let { Ui.say(it, true) } ?: Ui.say("You left ${s.name}.") }))
    } else MenuRow("delete", "Delete", danger = true) {
        done()
        Ui.sure(Ask("Delete ${s.name}?", "This can't be undone.", "Delete", danger = true,
            onConfirm = { if (app.chat.deleteGroup(g)) { ChatNav.open = ""; ChatNav.space = "" } }))
    }
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

            Framed("Role") {
                val inRole = ROLES.firstOrNull { r -> folders.any { it.name.equals(r, true) && key in it.keys } }
                val roles = ROLES + "None"
                ChoiceRow(roles.size, Modifier.padding(top = 8.dp), minWidth = 72.dp, gap = 8.dp) { i, m ->
                    val r = roles[i]
                    DetailChip(r, if (r == "None") inRole == null else inRole == r, m) { setRole(app, key, if (r == "None") null else r) }
                }
                Text(roleWords(inRole), style = Type.small, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                val others = folders.filter { f -> ROLES.none { it.equals(f.name, true) } }
                if (others.isNotEmpty()) FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    others.forEach { f -> val on = key in f.keys; DetailChip(f.name, on) { app.chat.folders.put(f.id, key, !on) } }
                }
            }

            if (contact != null) Framed("Keep messages") {
                val days = app.chat.chatDays(key)
                if (app.chat.isClose(key)) {
                    val mine = app.chat.myDays(key)
                    ChoiceRow(Limits.CHOICES.size, Modifier.padding(top = 8.dp), gap = 8.dp) { i, m ->
                        val d = Limits.CHOICES[i]
                        DetailChip("$d days", mine == d, m) { app.chat.setKeep(key, d) }
                    }
                    Text(if (days < mine) "$name keeps $days days. Shorter wins." else "Both phones keep $days days.", style = Type.small,
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                } else Text("$days days. Family or Work: up to 30.", style = Type.normal, modifier = Modifier.padding(vertical = 10.dp))
            }

            Framed("This chat") {
                ToggleRow("Pin to top", "", key in people.pinned) { People.pin(key, it) }
                ToggleRow("Archive", "", key in flags.archived) { app.chat.flags.archive(key, it) }
                Text("Color", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
                ColorChoices(app, key)
            }

            if (contact != null) Framed("They can") {
                val can = people.can[key] ?: People.can(key)
                ToggleRow("Call me", "", can.calls) { People.setCan(key, can.copy(calls = it)) }
                ToggleRow("Send me files", "Photos always arrive", can.files) { People.setCan(key, can.copy(files = it)) }
                ToggleRow("See when I'm active", "", can.active) { People.setCan(key, can.copy(active = it)) }
            }

            Framed("Safety") {
                NavRow("Check emoji code", if (app.chat.isVerified(key)) "Checked" else "Not checked yet", icon = "shield") { checkEmoji(app, key, name) }
                if (contact != null) {
                    var fast by remember(key) { mutableStateOf(io.github.ravenor907.muninn.net.call.CallManager.trusted(key)) }
                    ToggleRow("Direct calls", "Faster. They see your address", fast) {
                        io.github.ravenor907.muninn.net.call.CallManager.setTrusted(key, it); fast = it
                    }
                    NavRow("Introduce", "To another contact", icon = "people") { introducing = true }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** The bubble colors for my messages in [key]'s chat, as round swatches. */
@Composable
private fun ColorChoices(app: Muninn, key: String) {
    val st by app.chat.flags.all.collectAsState()
    val now = st.colors[key] ?: ""
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val choices = listOf("" to (0xFF2B2E33L to 0xFFC8CDD3L)) + io.github.ravenor907.muninn.net.ChatFlags.COLORS.toList()
        choices.forEach { (n, c) ->
            val on = n == now
            val fill = if (n.isEmpty()) Palette.bubbleMine else Color(c.first)
            val dot = if (n.isEmpty()) Palette.accent else Color(c.second)
            Box(Modifier.size(44.dp).clip(CircleShape).background(fill)
                .border(if (on) 3.dp else 1.dp, if (on) Palette.goldBright else Palette.line, CircleShape)
                .clickable(role = Role.RadioButton) { app.chat.flags.color(key, n) }
                .semantics { selected = on; contentDescription = if (n.isEmpty()) "Usual grey" else n.replaceFirstChar { it.uppercase() } },
                contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).clip(CircleShape).background(dot)) }
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
