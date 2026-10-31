@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.ravenor907.muninn.screens

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.ravenor907.muninn.net.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.ravenor907.muninn.net.Voice
import androidx.compose.ui.input.nestedscroll.nestedScroll
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Folders
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.call.RoomLogic
import io.github.ravenor907.muninn.net.call.RoomManager
import io.github.ravenor907.muninn.ui.Section
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.selected
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.bar
import io.github.ravenor907.muninn.ui.frosted
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.hcBorder
import java.util.Calendar
import java.text.DateFormat
import java.util.Date

/** [name] is what's shown (a nickname if set); [alt] is the name they chose, so search finds either. [group]: a group's id. */
private data class ChatRow(val key: String, val name: String, val alt: String = name, val group: String? = null)

/** A chat being dragged on the chat list: its list item, the section it came from and its key. */
private data class Dragged(val item: String, val section: String, val key: String)

/**
 * Where a conversation is open: "" none, "nearby" the room, "trash", "archived", "requests", "code", "add", "newgroup",
 * a contact's key, "details/<key>" a person's details, "tvset/<section>/<key>" a Kids' TV section,
 * "g:<group id>" a group, or "g:<group id>/settings", "/people" or "/add" its pages.
 */
object ChatNav {
    var open by mutableStateOf("")
    /** A code handed in from a scanned QR, waiting on Add someone. */
    var incoming by mutableStateOf("")
    /** Whose three-dot menu is open: a person's key, "g:" and a group, "list" for the chat list, "" for none. */
    var hold by mutableStateOf("")
    /** Set when [hold] was opened from a group's People page: that group's id, for its admin actions. */
    var menuGroup by mutableStateOf("")
    /** What an opened conversation does first: "search" or "media" (from its menu), "" nothing. */
    var action by mutableStateOf("")
    /** Where Back goes from a person's details or a Kids' TV section. */
    var back by mutableStateOf("")
    /** A group whose "Pick people" call list is open, "" for none. */
    var callSome by mutableStateOf("")
    /** Who New group starts with picked (Start a group, from a person's menu). */
    var groupWith by mutableStateOf(setOf<String>())
    /**
     * On a tablet ([TABLET_SW_DP]) and at least this wide, Muninn uses the tablet layout (rail, chat list,
     * conversation); on a phone, a conversation takes the whole screen.
     */
    val WIDE_MIN = 480.dp
    /** A tablet: Android's smallest screen width of 600 dp (sw600dp) or more. */
    const val TABLET_SW_DP = 600
    /** True while the tablet layout is showing: the list stays beside the conversation, so no back arrow. */
    var wide by mutableStateOf(false)
    /** Tablets: what the rail has picked, "" for direct messages or a group's id. */
    var space by mutableStateOf("")
    /** Each group's open text channel. */
    val channel = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun openGroup(g: String) { open = Folders.groupKey(g); if (wide) space = g }
    /** The group [open] points at (conversation or settings), or null. */
    fun groupOf(open: String): String? = if (open.startsWith("g:")) open.removePrefix("g:").substringBefore('/') else null
}

private fun routeText(r: String) = when (r) {
    "close" -> "Close by (Bluetooth)"
    "wifi" -> "Same Wi-Fi"
    "internet" -> "Over the internet"
    else -> "Not reachable right now"
}
/** The few words for a conversation's top bar. */
private fun routeShort(r: String) = when (r) {
    "close" -> "Bluetooth"
    "wifi" -> "Same Wi-Fi"
    "internet" -> "Internet"
    else -> "Not reachable"
}
private fun routeIcon(r: String) = when (r) { "close" -> "phone"; "wifi" -> "wifi"; "internet" -> "network"; else -> "user" }

// The "Quiet" chat look. Every size is dp/sp, so Settings › Accessibility size and text scaling apply.
private val nameStyle get() = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.text)
private val previewStyle get() = TextStyle(fontFamily = Type.body, fontSize = 15.sp, color = Palette.textMuted)
private val metaStyle get() = TextStyle(fontFamily = Type.body, fontSize = 13.sp, color = Palette.textMuted)
private val tinyStyle get() = TextStyle(fontFamily = Type.body, fontSize = 12.sp, color = Palette.textMuted)
private val bodyStyle get() = TextStyle(fontFamily = Type.body, fontSize = 16.sp, color = Palette.text)
private val labelStyle get() = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.5.sp, color = Palette.textMuted)

private fun clock(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
private fun dayIndex(t: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = t }
    return c.get(Calendar.YEAR) * 400L + c.get(Calendar.DAY_OF_YEAR)
}
/** The chat list's time: the clock today, the date before that. */
private fun when_(t: Long) = if (dayIndex(t) == dayIndex(System.currentTimeMillis())) clock(t) else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(t))
private fun dayLabel(t: Long): String {
    val now = System.currentTimeMillis()
    return when (dayIndex(t)) {
        dayIndex(now) -> "Today"
        dayIndex(now - 86_400_000L) -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t))
    }
}
private fun initial(name: String) = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** What the chat list shows under a name. */
private fun preview(m: Chat.Msg) = when {
    m.unsent.isNotEmpty() -> "Message deleted"
    m.voice > 0 -> "Voice message · ${Voice.length(m.voice)}"
    m.photo.isNotEmpty() -> "Photo"
    else -> m.text.replace('\n', ' ')
}

/** Unread messages: theirs since my last message (at least 1 once the chat is marked unread). */
private fun unreadCount(msgs: List<Chat.Msg>): Int {
    var n = 0
    for (m in msgs.asReversed()) { if (m.mine) break; if (m.unsent.isEmpty()) n++ }
    return maxOf(n, 1)
}

/** Phones: the chat list, or one conversation (or My code / Add someone) over the whole screen. */
@Composable
fun ChatScreen(app: Muninn, onAllowNearby: () -> Unit = {}) {
    val open = ChatNav.open
    Box(Modifier.fillMaxSize()) {
        when {
            open.isEmpty() -> ChatList(app, onAllowNearby)
            else -> ChatMain(app)
        }
    }
}

/** Tablets: the middle column, the list of direct messages. */
@Composable
fun ChatColumn(app: Muninn, onAllowNearby: () -> Unit = {}) = ChatList(app, onAllowNearby)

/** Tablets: the main column, and phones once a chat is open: the open conversation, or a note to pick one. */
@Composable
fun ChatMain(app: Muninn) {
    val open = ChatNav.open
    Box(Modifier.fillMaxSize()) {
        // Back from a group's page goes to the group, from a person's details where they came from, from anything else to the list.
        if (open.isNotEmpty()) BackHandler {
            ChatNav.open = when {
                open.startsWith("g:") && open.contains('/') -> open.substringBefore('/')
                open.startsWith("details/") || open.startsWith("tvset/") -> ChatNav.back
                else -> ""
            }
        }
        val g = ChatNav.groupOf(open)
        if (g != null) androidx.compose.runtime.key(open) {
            when (open.substringAfter('/', "")) {
                "settings" -> GroupSettings(app, g)
                "people" -> GroupPeople(app, g)
                "add" -> GroupAdd(app, g)
                else -> GroupConversation(app, g)
            }
        }
        else if (open.startsWith("details/")) androidx.compose.runtime.key(open) { PersonDetails(app, open.removePrefix("details/")) { ChatNav.open = ChatNav.back } }
        else if (open.startsWith("tvset/")) androidx.compose.runtime.key(open) {
            val rest = open.removePrefix("tvset/")
            TvSectionPage(app, rest.substringAfter('/'), rest.substringBefore('/')) { ChatNav.open = ChatNav.back }
        }
        else when (open) {
            "" -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MIcon("chat", Palette.textFaint, 40.dp)
                Text("Pick a chat on the left.", style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 12.dp))
            }
            "code" -> MyCode(app)
            "add" -> AddSomeone(app)
            "newgroup" -> NewGroup(app)
            "nearby" -> NearbyRoom(app)
            "trash" -> TrashScreen(app) { ChatNav.open = "" }
            "requests" -> RequestsScreen(app) { ChatNav.open = "" }
            "archived" -> ArchivedScreen(app)
            "tap" -> TapScreen(app) { ChatNav.open = "" }
            "mirror" -> MirrorList { ChatNav.open = "" }
            "labsreport" -> LabsReportScreen(app)
            Chat.LABS_PAGE -> LabsReportsScreen(app)
            else -> if (open.startsWith("mirror/")) androidx.compose.runtime.key(open) { MirrorChatScreen(open.removePrefix("mirror/")) { ChatNav.open = "mirror" } }
                else androidx.compose.runtime.key(open) { Conversation(app, open) }
        }
    }
}

/** The slim top bar for a full-screen page: back arrow and a title, then a hairline. */
@Composable
internal fun PageBar(title: String, backLabel: String = "Back to chats", back: () -> Unit = { ChatNav.open = "" }) {
    Column(Modifier.fillMaxWidth().bar()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", backLabel, Palette.text, size = 24.dp) { back() }
            Spacer(Modifier.width(6.dp))
            Text(title.uppercase(), style = Type.title.copy(fontSize = 17.sp, letterSpacing = 2.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
    }
}

/** A person: their picture or initial, with the status dot. [active]: active status (cap m1) shows the green dot when no status is known. */
@Composable
private fun Avatar(key: String, name: String, size: Dp, active: Boolean = false) =
    io.github.ravenor907.muninn.ui.Avatar(key, name, size, showDot = true, active = active)

@Composable
private fun RoomAvatar(size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Palette.coolTint), contentAlignment = Alignment.Center) {
        MIcon("network", Palette.accent, size * 0.46f)
    }
}

@Composable
private fun ListLabel(text: String, top: Dp = 4.dp, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = labelStyle, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

@Composable
private fun SearchBox(value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    val shape = io.github.ravenor907.muninn.ui.LookShapes.composer
    Row(modifier.heightIn(min = 44.dp)
        .clip(shape).background(Palette.field).hcBorder(shape).padding(start = 14.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MIcon("search", Palette.textMuted, 18.dp, weight = 1.8f)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text("Search chats", style = bodyStyle.copy(color = Palette.textMuted), maxLines = 1)
            BasicTextField(value, onChange, singleLine = true, textStyle = bodyStyle, cursorBrush = SolidColor(Palette.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search chats" })
        }
        if (value.isNotEmpty()) IconAction("close", "Clear search", Palette.textMuted, size = 18.dp) { onChange("") }
    }
}

/**
 * Touch on a chat row that can be moved. A tap is a tap; a hold does [onLong] when let go; a hold and then a
 * drag moves the row ([onDrag] with each step, then [onDrop]: true when let go, false when the touch was
 * taken away). Moving before the hold is a scroll and is left to the list. The touch is taken first, so the
 * row's clickable only sees keys and TalkBack; [press] still shows the ripple.
 */
private suspend fun PointerInputScope.holdToDrag(press: MutableInteractionSource, onTap: () -> Unit, onHeld: () -> Unit, onLong: () -> Unit,
                                                 onDrag: (Float) -> Unit, onDrop: (Boolean) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        down.consume()
        val p = PressInteraction.Press(down.position)
        press.tryEmit(p)
        // 0: still deciding, 1: a tap, 2: not ours (a scroll, or the touch went away).
        var outcome = 0
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (outcome == 0) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (c == null) outcome = 2
                else if (!c.pressed) { c.consume(); outcome = 1 }
                else if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) outcome = 2
            }
        }
        if (outcome == 1) { press.tryEmit(PressInteraction.Release(p)); onTap(); return@awaitEachGesture }
        if (outcome == 2) { press.tryEmit(PressInteraction.Cancel(p)); return@awaitEachGesture }
        // Held: from here the row has the touch.
        onHeld()
        var dragging = false
        var ended = false
        var total = 0f
        try {
            while (true) {
                val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                val dy = c.position.y - c.previousPosition.y
                c.consume()
                if (!c.pressed) { ended = true; break }
                total += dy
                if (!dragging && abs(total) > viewConfiguration.touchSlop) { dragging = true; press.tryEmit(PressInteraction.Cancel(p)); onDrag(total) }
                else if (dragging) onDrag(dy)
            }
        } finally {
            if (!dragging) press.tryEmit(PressInteraction.Release(p))
            if (dragging) onDrop(ended) else if (ended) onLong()
        }
    }
}

/**
 * One chat in the list: avatar, name and time, then the last message and how many are unread. [onLong]: hold to pick, or for folders.
 * [onDrag]: hold then drag moves it (each step in px, then [onDrop] with false if the touch was taken away).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PersonRow(name: String, preview: String, time: String, unread: Int, dot: Boolean, avatar: @Composable () -> Unit,
                      muted: Boolean = false, onLong: (() -> Unit)? = null, picked: Boolean = false, longLabel: String = "Menu", modifier: Modifier = Modifier,
                      onDrag: ((Float) -> Unit)? = null, onDrop: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val tapNow by rememberUpdatedState(onClick)
    val longNow by rememberUpdatedState(onLong)
    val dragNow by rememberUpdatedState(onDrag)
    val dropNow by rememberUpdatedState(onDrop)
    // Touch goes through holdToDrag when the row can move; keys, D-pad and TalkBack still use the clickable below.
    val touch = if (onDrag == null) Modifier else Modifier.pointerInput(Unit) {
        holdToDrag(press, { tapNow() }, { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }, { longNow?.invoke() },
            { dragNow?.invoke(it) }, { dropNow?.invoke(it) })
    }
    Row(modifier.fillMaxWidth().background(if (picked) Palette.accentFill else Color.Transparent).then(touch)
        .combinedClickable(interactionSource = press, indication = LocalIndication.current, role = Role.Button,
            onLongClickLabel = if (onLong != null) longLabel else null, onLongClick = onLong, onClick = onClick)
        .semantics { selected = picked }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box {
            avatar()
            // Picked for a new group: a gold check on the avatar.
            if (picked) Box(Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(Palette.ground).padding(2.dp).clip(CircleShape)
                .background(Palette.gold), contentAlignment = Alignment.Center) { MIcon("check", Palette.onGold, 12.dp, weight = 2.4f) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (muted) MIcon("mute", Palette.textMuted, 14.dp, modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = "Muted" })
                Spacer(Modifier.weight(1f))
                if (time.isNotEmpty()) Text(time, style = metaStyle.copy(color = if (unread > 0) Palette.gold else Palette.textMuted),
                    maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(preview, style = previewStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (unread > 0) Box(Modifier.padding(start = 8.dp).heightIn(min = 20.dp).widthIn(min = 20.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                    .background(Palette.gold).padding(horizontal = 6.dp).semantics { contentDescription = "$unread unread" },
                    contentAlignment = Alignment.Center) {
                    Text(if (unread > 99) "99+" else "$unread", style = tinyStyle.copy(fontWeight = FontWeight.SemiBold, color = Palette.onGold))
                } else if (dot) Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(Palette.gold)
                    .semantics { contentDescription = "Waiting on you" })
            }
        }
    }
}

@Composable
private fun ChatList(app: Muninn, onAllowNearby: () -> Unit) {
    val contacts by app.chat.contacts.all.collectAsState()
    val convos by app.chat.conversations.collectAsState()
    val requests by app.chat.requests.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val close by app.close.list.collectAsState()
    val wifi by app.net.devices.collectAsState()
    val met by app.chat.met.collectAsState()
    val blocked by app.chat.blocked.collectAsState()
    val room by app.chat.nearby.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    val gunread by app.chat.groups.unreadGroups.collectAsState()
    val ginvites by app.chat.groups.pending.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    val topOrder by app.chat.folders.top.collectAsState()
    val trashed by app.chat.trash.all.collectAsState()
    val intros by app.chat.intros.collectAsState()
    val linkedPhone by io.github.ravenor907.muninn.net.Link.phone.collectAsState()
    val flagState by app.chat.flags.all.collectAsState()
    // A TV used normally has a remote, not a touchscreen: no dragging hint.
    val onTv = io.github.ravenor907.muninn.tv.KidMode.isTv(LocalContext.current)
    var showMore by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<Folders.Folder?>(null) }
    val activeMap by app.chat.active.collectAsState()
    // Everyone reachable right now: close by first, then the same Wi-Fi. (key, name, route)
    val around = buildList {
        close.filter { it.connected && it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.name, "close")) }
        wifi.filter { it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.chatName ?: it.alias, "wifi")) }
    }.distinctBy { it.first }
    // Close-by people already chatting with (not contacts yet) sit with the chats.
    val strangers = around.filter { !app.chat.isContact(it.first) && convos[it.first].isNullOrEmpty() && it.first !in blocked }
    fun lastTime(r: ChatRow): Long = if (r.group != null) gmsgs[r.group]?.lastOrNull()?.time ?: groups[r.group]?.state?.ts ?: 0L
        else convos[r.key]?.lastOrNull()?.time ?: 0L
    // Contacts (never group peers), anyone else there's a conversation with, and groups, newest first.
    val everyRow = (contacts.filter { !it.groupOnly }.map { ChatRow(it.chatKey, it.shown, it.name) } +
        // Not a contact: marked, so a name they chose ("Mom") never looks like one of mine.
        convos.filter { (k, v) -> v.isNotEmpty() && !app.chat.isContact(k) }.map { (k, _) -> (met[k]?.name ?: app.chat.nameOf(k)).let { n -> ChatRow(k, Chat.stranger(n), n) } } +
        groups.values.map { ChatRow(Folders.groupKey(it.g), it.state.name, group = it.g) })
        .filter { it.key !in blocked && it.key !in trashed }
        .sortedByDescending { lastTime(it) }
    // Search: a chat's nickname, the name they chose, or its last message.
    val q = query.trim()
    // Archived chats sit in their own section at the bottom (search still finds them).
    val chatRows = if (q.isEmpty()) everyRow.filter { it.key !in flagState.archived } else everyRow
    val archivedRows = everyRow.filter { it.key in flagState.archived }
    fun lastText(r: ChatRow): String? = if (r.group != null) gmsgs[r.group]?.lastOrNull()?.let { groupPreview(app, it) } else convos[r.key]?.lastOrNull()?.let { preview(it) }
    val shownRows = if (q.isEmpty()) chatRows else chatRows.filter { c ->
        c.name.contains(q, ignoreCase = true) || c.alt.contains(q, ignoreCase = true) || lastText(c)?.contains(q, ignoreCase = true) == true
    }
    // Hidden from people nearby: no Nearby room and nobody new around.
    val visible = app.identity.visibleNearby
    val shownStrangers = if (!visible) emptyList() else if (q.isEmpty()) strangers else strangers.filter { it.second.contains(q, ignoreCase = true) }
    val roomPreview = if (app.identity.nearbyRoom) "${around.size} around you" else "Off"
    val showRoom = visible && (q.isEmpty() || "Nearby room".contains(q, ignoreCase = true) || roomPreview.contains(q, ignoreCase = true))
    // Folders are sections that fold away (like Discord categories), with the chats in no folder above them.
    // A chat in several folders shows under each. Newest first, except what was moved by hand.
    val byKey = chatRows.associateBy { it.key }
    val newest = chatRows.map { it.key }
    val foldered = folders.flatMap { it.keys }.toSet()
    // Pinned chats (from Person details) come first in their section, in the order they were pinned.
    val pins by People.all.collectAsState()
    fun pinFirst(l: List<String>): List<String> = pins.pinned.filter { it in l } + l.filter { it !in pins.pinned }
    fun sectionKeys(sec: String): List<String> = pinFirst(if (sec == Folders.NONE) Folders.arrange(newest.filter { it !in foldered }, topOrder)
        else folders.firstOrNull { it.id == sec }?.let { f -> Folders.arrange(newest.filter { it in f.keys }, f.keys) }.orEmpty())
    // Hold a chat and drag it: within its section to reorder, or onto another section (its header or a chat in it) to move it there.
    val listState = rememberLazyListState()
    var dragged by remember { mutableStateOf<Dragged?>(null) }
    var dragBy by remember { mutableStateOf(0f) }
    // Every list item a chat can be dropped on: its key to (section, the chat there or null for the section's header).
    val spots = HashMap<String, Pair<String, String?>>()
    fun dropSpot(): String? {
        val d = dragged ?: return null
        val seen = listState.layoutInfo.visibleItemsInfo
        val me = seen.firstOrNull { it.key == d.item } ?: return null
        val y = me.offset + me.size / 2 + dragBy
        return seen.firstOrNull { val k = it.key as? String; k != null && k != d.item && spots.containsKey(k) && y >= it.offset && y < it.offset + it.size }?.key as? String
    }
    fun drop(commit: Boolean) {
        val d = dragged ?: return
        val spot = if (commit) dropSpot()?.let { spots[it] } else null
        dragged = null; dragBy = 0f
        val (to, onto) = spot ?: return
        val now = sectionKeys(to)
        val rest = now - d.key
        // Onto a header: first in that section. Onto a chat: in its place (after it when moving down the same section).
        val at = if (onto == null) 0 else rest.indexOf(onto).let { i ->
            if (i < 0) rest.size else if (to == d.section && now.indexOf(d.key) in 0 until now.indexOf(onto)) i + 1 else i
        }
        val order = rest.toMutableList().apply { add(at.coerceIn(0, size), d.key) }
        if (to != d.section) app.chat.folders.move(d.key, d.section, to)
        app.chat.folders.order(to, order)
    }
    val sectioned = q.isEmpty()
    if (sectioned) {
        spots["h"] = Folders.NONE to null
        sectionKeys(Folders.NONE).forEach { spots["c|$it"] = Folders.NONE to it }
        folders.forEach { f ->
            spots["h" + f.id] = f.id to null
            if (!f.collapsed) {
                val keys = sectionKeys(f.id)
                keys.forEach { spots["c${f.id}|$it"] = f.id to it }
                if (keys.isEmpty()) spots["e" + f.id] = f.id to null
            }
        }
    }
    val hover = dropSpot()

    // One chat on the list. [sec]: the section it's under ("" no folder), null in search results (nothing to move there).
    val chatRow: @Composable (ChatRow, String?, String) -> Unit = { c, sec, item ->
        val lifted = dragged?.item == item
        val rowMod = Modifier.then(if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragBy }.background(Palette.panel) else Modifier)
            .then(if (hover == item) Modifier.background(Palette.focusFill) else Modifier)
        val onDrag: ((Float) -> Unit)? = if (sec == null) null else ({ dy ->
            if (dragged?.item != item) { dragged = Dragged(item, sec, c.key); dragBy = 0f }
            dragBy += dy
        })
        val onDrop: ((Boolean) -> Unit)? = if (sec == null) null else ({ commit -> drop(commit) })
        if (c.group != null) {
            val grp = groups[c.group]
            val msgs = gmsgs[c.group].orEmpty()
            val last = msgs.lastOrNull()
            val otherMembers = grp?.state?.members.orEmpty().filter { it.k != app.identity.publicKey }
            val others = otherMembers.map { memberName(app, it) }
            PersonRow(c.name, last?.let { groupPreview(app, it) } ?: when {
                    grp?.readOnly == true -> "You're not in it any more"
                    others.isEmpty() -> "Just you so far: invite people"
                    else -> "${others.size + 1} people"
                }, last?.let { when_(it.time) } ?: "",
                if (c.group in gunread) groupUnread(msgs) else if (c.key in flagState.unread) 1 else 0, false, { GroupAvatar(c.name, otherMembers, 48.dp) },
                muted = (flagState.muted[c.key] ?: 0L) > System.currentTimeMillis(),
                onLong = { ChatNav.menuGroup = ""; ChatNav.hold = c.key }, modifier = rowMod, onDrag = onDrag, onDrop = onDrop) { ChatNav.openGroup(c.group) }
        } else {
            val msgs = convos[c.key].orEmpty()
            val last = msgs.lastOrNull()
            PersonRow(c.name, last?.let { preview(it) } ?: routeText(app.chat.route(c.key)), last?.let { when_(it.time) } ?: "",
                if (c.key in unread) unreadCount(msgs) else if (c.key in flagState.unread) 1 else 0, app.chat.shouldAskKeep(c.key), { Avatar(c.key, c.name, 48.dp, active = c.key in activeMap) },
                muted = (flagState.muted[c.key] ?: 0L) > System.currentTimeMillis(),
                onLong = { ChatNav.menuGroup = ""; ChatNav.hold = c.key }, modifier = rowMod, onDrag = onDrag, onDrop = onDrop) { ChatNav.open = c.key }
        }
    }

    Box(Modifier.fillMaxSize().frosted()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                // Search, and the chat list's three-dot menu beside it.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchBox(query, Modifier.weight(1f)) { query = it }
                    Spacer(Modifier.width(4.dp))
                    io.github.ravenor907.muninn.ui.DotsButton("Chat list menu") { ChatNav.menuGroup = ""; ChatNav.hold = "list" }
                }
                // Permissions still missing, the message limits note and mirrored chats: one row, one sheet.
                FinishRow(app, onAllowNearby, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            }
            // A companion (linked devices): the phone's chats, on their own page.
            linkedPhone?.let { ph -> item(key = "mirror") {
                PersonRow("${ph.name}'s chats", "From your phone", "", 0, false, {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
                        MIcon("phone", Palette.goldBright, 22.dp) } }) { ChatNav.open = "mirror" }
            } }
            // With folders, the chats in none of them sit under their own label (also where a dragged chat lands).
            if (folders.isNotEmpty() && sectioned) item(key = "h") {
                ListLabel("Chats", modifier = if (hover == "h") Modifier.background(Palette.focusFill) else Modifier)
            }
            if (!sectioned) items(shownRows, key = { "s" + it.key }) { c -> chatRow(c, null, "s" + c.key) }
            else {
                items(sectionKeys(Folders.NONE).mapNotNull { byKey[it] }, key = { "c|" + it.key }) { c -> chatRow(c, Folders.NONE, "c|" + c.key) }
                folders.forEach { f ->
                    val keys = sectionKeys(f.id)
                    item(key = "h" + f.id) {
                        FolderHeader(f, keys.count { it in unread || it.removePrefix("g:") in gunread }, hover == "h" + f.id,
                            onToggle = { app.chat.folders.collapse(f.id, !f.collapsed) }, onEdit = { editingFolder = f })
                    }
                    if (!f.collapsed) {
                        items(keys.mapNotNull { byKey[it] }, key = { "c${f.id}|" + it.key }) { c -> chatRow(c, f.id, "c${f.id}|" + c.key) }
                        if (keys.isEmpty()) item(key = "e" + f.id) {
                            Text(if (onTv) "Empty. Set roles in Person details." else "Empty. Drag a chat here.", style = Type.small,
                                modifier = Modifier.fillMaxWidth().background(if (hover == "e" + f.id) Palette.focusFill else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 8.dp))
                        }
                    }
                }
            }
            if (chatRows.isEmpty()) item {
                Text("No chats yet. Tap + to add someone.", style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (shownRows.isEmpty() && q.isNotEmpty()) item {
                Text("No chats match “$q”.", style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            // Nearby, in a section of its own.
            if (showRoom || shownStrangers.isNotEmpty()) item(key = "nearby") {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp).height(1.dp).background(Palette.hairline))
                ListLabel("Nearby")
            }
            if (showRoom) item(key = "room") {
                PersonRow("Nearby room", roomPreview, room.lastOrNull()?.let { when_(it.time) } ?: "", 0, false, { RoomAvatar(48.dp) }) { ChatNav.open = "nearby" }
            }
            if (shownStrangers.isNotEmpty()) item(key = "around") {
                ListLabel("Around you", top = 10.dp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    shownStrangers.forEach { (k, name, r) ->
                        val shape = io.github.ravenor907.muninn.ui.Corner
                        Row(Modifier.heightIn(min = 40.dp).clip(shape).border(1.dp, if (Access.contrast) Palette.lineMuted else Palette.line, shape)
                            .clickable(role = Role.Button, onClickLabel = "Message $name") { app.chat.meet(k, name); ChatNav.open = k }
                            .semantics { stateDescription = routeText(r) }.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            MIcon(routeIcon(r), Palette.accent, 16.dp, weight = 1.8f)
                            Spacer(Modifier.width(8.dp))
                            Text(name, style = bodyStyle.copy(fontSize = 15.sp), maxLines = 1)
                        }
                    }
                }
            }
            // Requests, archived chats and the trash: one row at the bottom.
            val pendingIntros = intros.count { !it.iSaid && !it.declined } + app.chat.familyOffers().size
            val asks = requests.size + ginvites.size + pendingIntros
            val labsHere = app.chat.isLabs()
            if (q.isEmpty() && (asks > 0 || archivedRows.isNotEmpty() || trashed.isNotEmpty() || labsHere)) item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(1.dp).background(Palette.hairline))
                PersonRow("More", if (labsHere) "Requests, archived, trash, Labs" else "Requests, archived, trash", "", asks, false,
                    { Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.coolTint), contentAlignment = Alignment.Center) { MIcon("download", Palette.accent, 22.dp) } }) {
                    showMore = true
                }
            }
            // Room under the last row for the + button.
            item { Spacer(Modifier.height(96.dp)) }
        }
        // Add someone: a round gold button at the bottom left, above the tabs.
        Box(Modifier.align(Alignment.BottomStart).padding(16.dp).size(56.dp).clip(CircleShape).background(Palette.gold)
            .clickable(role = Role.Button) { ChatNav.open = "add" }.semantics { contentDescription = "Add someone" },
            contentAlignment = Alignment.Center) { MIcon("plus", Palette.onGold, 26.dp) }
    }
    if (showMore) MoreSheet(asks = requests.size + ginvites.size + intros.count { !it.iSaid && !it.declined } + app.chat.familyOffers().size,
        archived = archivedRows.size, trash = trashed.size, labs = if (app.chat.isLabs()) app.chat.labs.reports.value.size else -1) { showMore = false }
    FolderDialogs(app, editingFolder) { editingFolder = null }
}

/**
 * Move up and Move down for [key] on the chat list, in the section it shows in (its first folder, or none): each null
 * where it can't go. The same order the list shows: pinned first, then what was moved by hand, then newest.
 */
internal fun listMoves(app: Muninn, key: String): Pair<(() -> Unit)?, (() -> Unit)?> {
    val ch = app.chat
    val convos = ch.conversations.value
    val gmsgs = ch.groups.messages.value
    val groups = ch.groups.all.value
    val flags = ch.flags.all.value
    val blocked = ch.blocked.value
    val trashed = ch.trash.all.value
    fun last(k: String): Long = if (k.startsWith("g:")) k.removePrefix("g:").let { g -> gmsgs[g]?.lastOrNull()?.time ?: groups[g]?.state?.ts ?: 0L }
        else convos[k]?.lastOrNull()?.time ?: 0L
    val keys = (ch.contacts.all.value.filter { !it.groupOnly }.map { it.chatKey } + convos.filter { (k, v) -> v.isNotEmpty() && !ch.isContact(k) }.keys +
        groups.keys.map { Folders.groupKey(it) }).distinct().filter { it !in blocked && it !in trashed && it !in flags.archived }.sortedByDescending { last(it) }
    val folders = ch.folders.all.value
    val f = folders.firstOrNull { key in it.keys }
    val sec = f?.id ?: Folders.NONE
    val foldered = folders.flatMap { it.keys }.toSet()
    val raw = if (f == null) Folders.arrange(keys.filter { it !in foldered }, ch.folders.top.value) else Folders.arrange(keys.filter { it in f.keys }, f.keys)
    val l = flags.pinned.filter { it in raw } + raw.filter { it !in flags.pinned }
    val i = l.indexOf(key)
    if (i < 0) return Pair(null, null)
    val up: (() -> Unit)? = if (i > 0) ({ ch.folders.order(sec, Folders.shift(l, key, -1)) }) else null
    val down: (() -> Unit)? = if (i < l.size - 1) ({ ch.folders.order(sec, Folders.shift(l, key, 1)) }) else null
    return Pair(up, down)
}

/** The chat list's More row: Requests, Archived and Trash, each its own page. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(asks: Int, archived: Int, trash: Int, labs: Int = -1, done: () -> Unit) {
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = done, sheetState = sheet, containerColor = Palette.panel, shape = Corner, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).navigationBarsPadding()) {
            Text("More", style = Type.big, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            Box(Modifier.fillMaxWidth().padding(bottom = 4.dp).height(1.dp).background(Palette.hairline))
            io.github.ravenor907.muninn.ui.MenuRow("people", "Requests", value = if (asks > 0) "$asks" else "") { done(); ChatNav.open = "requests" }
            io.github.ravenor907.muninn.ui.MenuRow("download", "Archived", value = if (archived > 0) "$archived" else "") { done(); ChatNav.open = "archived" }
            io.github.ravenor907.muninn.ui.MenuRow("delete", "Trash", value = if (trash > 0) "$trash" else "") { done(); ChatNav.open = "trash" }
            // The Labs contact's own phone: the bug reports that came in.
            if (labs >= 0) io.github.ravenor907.muninn.ui.MenuRow("info", "Labs reports", value = if (labs > 0) "$labs" else "") { done(); ChatNav.open = Chat.LABS_PAGE }
        }
    }
}

/** Archived chats: they come back to the list when a message arrives. Hold one for its menu. */
@Composable
private fun ArchivedScreen(app: Muninn) {
    val flags by app.chat.flags.all.collectAsState()
    val convos by app.chat.conversations.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()
    val keys = flags.archived.filter { k -> if (k.startsWith("g:")) groups.containsKey(k.removePrefix("g:")) else true }
    Column(Modifier.fillMaxSize()) {
        PageBar("Archived")
        LazyColumn(Modifier.weight(1f)) {
            if (keys.isEmpty()) item { Text("Nothing archived.", style = Type.normal, modifier = Modifier.padding(16.dp)) }
            items(keys, key = { it }) { k ->
                val g = if (k.startsWith("g:")) groups[k.removePrefix("g:")] else null
                if (g != null) {
                    val last = gmsgs[g.g]?.lastOrNull()
                    PersonRow(g.state.name, last?.let { groupPreview(app, it) } ?: "", last?.let { when_(it.time) } ?: "", 0, false,
                        { GroupAvatar(g.state.name, g.state.members.filter { it.k != app.identity.publicKey }, 48.dp) },
                        onLong = { ChatNav.menuGroup = ""; ChatNav.hold = k }) { ChatNav.openGroup(g.g) }
                } else {
                    val name = app.chat.nameOf(k)
                    val last = convos[k]?.lastOrNull()
                    PersonRow(name, last?.let { preview(it) } ?: "", last?.let { when_(it.time) } ?: "", 0, false, { Avatar(k, name, 48.dp) },
                        onLong = { ChatNav.menuGroup = ""; ChatNav.hold = k }) { ChatNav.open = k }
                }
            }
        }
    }
}

/**
 * A conversation's slim top bar: back, avatar, name and one short status line, then [actions] and the three dots.
 * Tapping the name or the dots opens the chat's menu ([onMenu]); null: no menu.
 */
@Composable
private fun ThreadBar(name: String, status: String, statusIcon: String, statusTint: Color, avatar: @Composable () -> Unit,
                      actions: @Composable () -> Unit = {}, onMenu: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Tablets keep the chat list beside the conversation, so there's nothing to go back to.
            if (!ChatNav.wide) IconAction("back", "Back to chats", Palette.text, size = 24.dp) { ChatNav.open = "" }
            else Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                .then(if (onMenu != null) Modifier.clickable(role = Role.Button, onClickLabel = "Menu") { onMenu() } else Modifier).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MIcon(statusIcon, statusTint, 13.dp, weight = 2f)
                        Spacer(Modifier.width(5.dp))
                        Text(status, style = metaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            actions()
            if (onMenu != null) io.github.ravenor907.muninn.ui.DotsButton("Menu for $name") { onMenu() }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
    }
}

@Composable
private fun DayLabel(t: Long) {
    Text(dayLabel(t), style = tinyStyle, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
private fun NearbyRoom(app: Muninn) {
    val msgs by app.chat.nearby.collectAsState()
    var text by remember { mutableStateOf("") }
    var on by remember { mutableStateOf(app.identity.nearbyRoom) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.lastOrNull()?.id) { if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize().imePadding()) {
        ThreadBar("Nearby room", if (on) "You're “${app.identity.username}”" else "Off", "network", Palette.accent, { RoomAvatar(40.dp) }, onMenu = null)
        if (!on) {
            Column(Modifier.padding(16.dp)) {
                val hidden = !app.identity.visibleNearby
                Text(if (hidden) "You're hidden from people nearby." else "Nearby chat is off.", style = Type.normal)
                MButton(if (hidden) "Be visible to people nearby" else "Turn on Nearby chat", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) {
                    app.identity.visibleNearby = true; app.identity.nearbyChat = true; on = true; app.net.announce(); app.close.readvertise(); app.ble.hiddenChanged()
                }
            }
            return@Column
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                Column {
                    if (i == 0 || dayIndex(msgs[i - 1].time) != dayIndex(m.time)) DayLabel(m.time)
                    Bubble(m, showName = true) {
                        Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true,
                            extra = if (m.mine) null else "Mute ${m.name}", onExtra = { app.chat.block(m.peerKey) },
                            onConfirm = { app.chat.deleteNearbyMessage(m.id) }))
                    }
                }
            }
        }
        if (msgs.isEmpty()) Text("Say hello.", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        Composer(text, { text = it }, "Message everyone nearby") { app.chat.sendNearby(text); text = "" }
    }
}

@Composable
private fun Conversation(app: Muninn, key: String) {
    // Calls start faster when the relay login is already here.
    LaunchedEffect(key) { io.github.ravenor907.muninn.net.call.CallManager.prepare(key) }
    val convos by app.chat.conversations.collectAsState()
    val c = app.chat.contacts.get(key)
    val met by app.chat.met.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // redraw when they become a contact
    val m = met[key]
    val name = c?.shown ?: m?.name ?: app.chat.nameOf(key)
    // What their app speaks isn't known yet: ask once (protocol 0.6, 1.2).
    LaunchedEffect(key) { app.chat.probeCaps(key); app.chat.pruneSoon() }
    val msgs = convos[key].orEmpty()
    var text by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.lastOrNull()?.id) { app.chat.markRead(key); if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    // Redraw as they come into or go out of range (route, and when to ask "Keep in touch?").
    @Suppress("UNUSED_VARIABLE") val closeNow by app.close.list.collectAsState()
    @Suppress("UNUSED_VARIABLE") val wifiNow by app.net.devices.collectAsState()
    val route = app.chat.route(key)
    val verified = app.chat.isVerified(key)
    val context = LocalContext.current
    // Calls with them show in the timeline, between the messages, by time.
    val history by io.github.ravenor907.muninn.net.call.CallManager.history.collectAsState()
    val calls = remember(history, key) { history.filter { it.peerKey == key }.sortedBy { it.at } }
    val callNow by io.github.ravenor907.muninn.net.call.CallManager.call.collectAsState()
    // While this chat is on screen, new messages here just buzz instead of notifying; its old notification goes.
    DisposableEffect(key) {
        app.openChat = key
        androidx.core.app.NotificationManagerCompat.from(context).cancel(key.hashCode())
        onDispose { if (app.openChat == key) app.openChat = ""; app.chat.typingTo(key, false) }
    }
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Chat.Msg?>(null) }
    // Cap m2: several photos picked at once, the "+" menu, the camera ("photo" or "video"), a new poll, the Shared page.
    var pickedMany by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var attaching by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf("") }
    var polling by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    // Cap m1: the message being answered or edited, the one held for its sheet, search and the photos page.
    var replyTo by remember { mutableStateOf<Chat.Msg?>(null) }
    var editing by remember { mutableStateOf<Chat.Msg?>(null) }
    var sheetFor by remember { mutableStateOf<Chat.Msg?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matchAt by remember { mutableStateOf(0) }
    val typingMap by app.chat.typing.collectAsState()
    val activeMap by app.chat.active.collectAsState()
    val typingNow = key in typingMap
    val activeNow = key in activeMap
    val me = app.identity.publicKey
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(io.github.ravenor907.muninn.net.More.ALBUM_MAX)) { uris ->
        if (uris.size == 1) picked = uris[0] else if (uris.size > 1) pickedMany = uris
    }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Ui.say(if (ok) "Ready. Hold the mic to record." else "Needs the microphone.", !ok)
    }
    DisposableEffect(Unit) { onDispose { Voice.stopPlaying(); Voice.cancel() } }
    fun sendPhoto(uri: Uri, hd: Boolean) {
        preparing = true
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) { Photos.prepare(context, uri, hd) }
            if (jpeg == null) Ui.say("That photo couldn't be opened.", true)
            else withContext(Dispatchers.IO) { app.chat.sendPhoto(key, jpeg) }
            preparing = false; picked = null
        }
    }
    /** An album: up to 10 photos, each shrunk and scrubbed like any photo. */
    fun sendMany(uris: List<Uri>) {
        preparing = true
        scope.launch {
            val jpegs = withContext(Dispatchers.Default) { uris.mapNotNull { Photos.prepare(context, it, false) } }
            if (jpegs.isEmpty()) Ui.say("Those photos couldn't be opened.", true)
            else withContext(Dispatchers.IO) { app.chat.sendPhotos(key, jpegs) }
            preparing = false; pickedMany = emptyList()
        }
    }
    fun sendPollHere(poll: io.github.ravenor907.muninn.net.More.Poll) {
        app.chat.sendPoll(key, poll.q, poll.options, poll.multi)?.let { Ui.say(it, true) }
    }
    // A file over the internet: straight to their phone (or over the relays when small).
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = try { context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null } } catch (e: Exception) { null } ?: "file"
            val tmp = java.io.File(java.io.File(context.cacheDir, "files").also { it.mkdirs() }, "out-" + System.nanoTime())
            val ok = try { context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } != null } catch (e: Exception) { false }
            if (!ok) Ui.say("That file couldn't be opened.", true)
            else app.chat.sendFile(key, tmp, name)?.let { Ui.say(it, true) }
        }
    }
    fun jumpTo(id: String) {
        val i = msgs.indexOfFirst { it.id == id }
        if (i < 0) Ui.say("That message isn't here any more.") else scope.launch { list.animateScrollToItem(i) }
    }
    fun quoteOf(re: String): Pair<String, String>? {
        if (re.isEmpty()) return null
        val q = msgs.firstOrNull { it.id == re } ?: return "Message" to "Not here any more"
        return (if (q.mine) "You" else name) to (if (q.unsent.isNotEmpty()) "Message deleted" else preview(q))
    }
    // Search or Media picked in this person's menu.
    val action = ChatNav.action
    LaunchedEffect(action) {
        when (action) { "search" -> { searching = true; matchAt = 0 }; "media" -> shared = true }
        if (action.isNotEmpty()) ChatNav.action = ""
    }
    viewing?.let { v ->
        BackHandler { viewing = null }
        if (v.video > 0) VideoPlayer(app.chat.photo(v.photo), { viewing = null }) else PhotoViewer(app, v) { viewing = null }
        return
    }
    if (camera.isNotEmpty()) {
        CameraScreen(camera == "video", onPhoto = { jpeg -> scope.launch(Dispatchers.IO) { app.chat.sendPhoto(key, jpeg) } },
            onVideo = { mp4, ms -> scope.launch(Dispatchers.IO) { app.chat.sendVideo(key, mp4, ms)?.let { Ui.say(it, true) } } }) { camera = "" }
        return
    }
    if (shared) {
        SharedMedia("Shared · $name", msgs, { app.chat.photo(it.photo) }, { viewing = it }) { shared = false }
        return
    }
    if (searching) BackHandler { searching = false; query = "" }
    if (replyTo != null || editing != null) BackHandler { replyTo = null; if (editing != null) { editing = null; text = "" } }
    val matches = if (!searching || query.isBlank()) emptyList()
        else msgs.indices.filter { msgs[it].unsent.isEmpty() && msgs[it].text.contains(query.trim(), ignoreCase = true) }.reversed()
    LaunchedEffect(matchAt, matches.size, query) { matches.getOrNull(matchAt)?.let { list.animateScrollToItem(it) } }
    val status = when {
        typingNow -> "typing…"
        activeNow -> "Active now · ${routeShort(route)}"
        else -> "Encrypted · ${routeShort(route)}"
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalMineBubble provides mineTint(app, key)) {
    Column(Modifier.fillMaxSize().imePadding()) {
        ThreadBar(name, status, if (typingNow) "chat" else "shield", Palette.online, { Avatar(key, name, 40.dp, active = activeNow) }, actions = {
            // Someone met close by can be called too while on the same Wi-Fi.
            if (c != null || io.github.ravenor907.muninn.net.call.CallManager.canCall(key)) {
                val callable = io.github.ravenor907.muninn.net.call.CallManager.canCall(key)
                IconAction("call", if (callable) "Call $name" else "Calling needs an update", if (callable) Palette.text else Palette.textSoft) {
                    if (callable) io.github.ravenor907.muninn.screens.CallActivity.call(context, key)
                    else Ui.say("$name needs an update for calls.")
                }
                // Video calls: both apps must speak c2 (shared/docs/protocol-0.6.md 5.8.5).
                if (callable && io.github.ravenor907.muninn.net.call.CallManager.canVideo(key))
                    IconAction("video", "Video call $name", Palette.text) { io.github.ravenor907.muninn.screens.CallActivity.call(context, key, video = true) }
            }
        }, onMenu = { ChatNav.menuGroup = ""; ChatNav.hold = key })
        if (searching) ChatSearchBar(query, { query = it; matchAt = 0 }, matches.size, matchAt,
            onPrev = { if (matches.isNotEmpty()) matchAt = (matchAt + 1) % matches.size },
            onNext = { if (matches.isNotEmpty()) matchAt = (matchAt - 1 + matches.size) % matches.size }) { searching = false; query = "" }
        // Their security key changed: say so until the code has been checked.
        if (c?.keyChanged == true) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(io.github.ravenor907.muninn.net.ChatWire.keyChangedText(name), style = Type.small.copy(color = Palette.danger), modifier = Modifier.weight(1f))
            MButton("Check emoji code") { checkEmoji(app, key, name) }
        }
        if (c == null && m != null && app.chat.shouldAskKeep(key)) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text(if (m.theySaid) "$name wants to keep in touch" else "Keep in touch with $name?", style = Type.big)
            Text("Then chat from anywhere.", style = Type.small)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Yes", Modifier.weight(1f), Kind.Accent) { app.chat.keepInTouch(key) }
                MButton("Not now", Modifier.weight(1f)) { app.chat.notNow(key) }
            }
        } else if (c == null && m?.iSaid == true) Text("Waiting for $name to say yes.", style = Type.small,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        // A call with them going on: back to it.
        callNow?.takeIf { it.peerKey == key && it.phase != io.github.ravenor907.muninn.net.call.CallSession.Phase.ENDED }?.let {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp).clip(CircleShape).background(Palette.goldTint)
                .clickable(role = Role.Button) { CallActivity.show(context) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MIcon("call", Palette.goldBright, 20.dp)
                Spacer(Modifier.width(10.dp))
                Text("Return to call", style = Type.normal.copy(color = Palette.goldBright), maxLines = 1)
            }
        }
        val current = matches.getOrNull(matchAt)
        val albums = remember(msgs) { albumsOf(msgs) }
        val callOf: (io.github.ravenor907.muninn.net.call.CallManager.Log) -> Unit = { l -> io.github.ravenor907.muninn.screens.CallActivity.call(context, l.peerKey) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                Column {
                    // The calls since the message before this one.
                    var day = if (i == 0) -1L else dayIndex(msgs[i - 1].time)
                    callsBetween(calls, if (i == 0) Long.MIN_VALUE else msgs[i - 1].time, m.time).forEach { l ->
                        if (dayIndex(l.at) != day) { day = dayIndex(l.at); DayLabel(l.at) }
                        CallLine(l) { callOf(l) }
                    }
                    if (dayIndex(m.time) != day) DayLabel(m.time)
                    // An album shows once, in its first photo's place.
                    if (m.id !in albums.first) Bubble(m, showName = false, theirName = name, photo = if (m.photo.isNotEmpty()) ({ app.chat.photo(m.photo) }) else null,
                        quote = quoteOf(m.re), onQuote = { jumpTo(m.re) }, me = me, highlight = current == i,
                        onReply = if (m.failed) null else ({ editing = null; replyTo = m }), onReactions = { sheetFor = m },
                        onTap = { viewing = m }, album = albums.second[m.id], albumBytes = { app.chat.photo(it.photo) }, onAlbumTap = { viewing = it },
                        onVote = { v -> app.chat.vote(key, m.id, v)?.let { Ui.say(it, true) } }, voterName = { if (it == key) name else app.chat.nameOf(it) }) { sheetFor = m }
                }
            }
            // The calls since the last message.
            val after = callsBetween(calls, msgs.lastOrNull()?.time ?: Long.MIN_VALUE, Long.MAX_VALUE)
            if (after.isNotEmpty()) item(key = "calls") {
                Column {
                    var day = msgs.lastOrNull()?.time?.let { dayIndex(it) } ?: -1L
                    after.forEach { l ->
                        if (dayIndex(l.at) != day) { day = dayIndex(l.at); DayLabel(l.at) }
                        CallLine(l) { callOf(l) }
                    }
                }
            }
            if (typingNow) item(key = "typing") { TypingRow(name) }
        }
        if (msgs.isEmpty() && calls.isEmpty()) Text("No messages yet.", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        picked?.let { uri ->
            Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UriThumb(uri, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (preparing) "Getting it ready…" else "Send this photo?", style = Type.big)
                        Text("Location and camera details are removed.", style = Type.small)
                    }
                }
                // Three a line; fewer at a large text size, so "High quality" isn't squeezed.
                io.github.ravenor907.muninn.ui.ChoiceRow(3, Modifier.padding(top = 8.dp), minWidth = 88.dp, gap = 8.dp) { n, m ->
                    when (n) {
                        0 -> MButton("Cancel", m, enabled = !preparing) { picked = null }
                        1 -> MButton("High quality", m, enabled = !preparing) { sendPhoto(uri, true) }
                        else -> MButton("Send", m, Kind.Accent, enabled = !preparing) { sendPhoto(uri, false) }
                    }
                }
            }
        }
        if (attaching) AttachDialog(onPhotos = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = { camera = "photo" }, onVideo = { camera = "video" }, onPoll = { polling = true }, onFile = { pickFile.launch(arrayOf("*/*")) }) { attaching = false }
        DirectFiles(key)
        if (polling) PollDialog { poll -> polling = false; if (poll != null) sendPollHere(poll) }
        if (pickedMany.isNotEmpty()) Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { pickedMany.take(5).forEach { UriThumb(it, Modifier.size(52.dp)) } }
            Text(if (preparing) "Getting them ready…" else "Send ${pickedMany.size} photos as an album?", style = Type.big, modifier = Modifier.padding(top = 6.dp))
            Text("Location and camera details are removed.", style = Type.small)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Cancel", Modifier.weight(1f), enabled = !preparing) { pickedMany = emptyList() }
                MButton("Send", Modifier.weight(1f), Kind.Accent, enabled = !preparing) { sendMany(pickedMany) }
            }
        }
        val banner: (@Composable () -> Unit)? = editing?.let { e -> { ComposeBanner("Editing your message", e.text, "edit") { editing = null; text = "" } } }
            ?: replyTo?.let { r -> { ComposeBanner("Replying to ${if (r.mine) "yourself" else name}", preview(r), "reply") { replyTo = null } } }
        Composer(text, { text = it; app.chat.typingTo(key, it.isNotBlank()) }, if (editing != null) "Edit your message" else "Message $name", onPhoto = {
            attaching = true
        }, voice = VoiceHooks(
            allowed = { androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            ask = { micAsk.launch(android.Manifest.permission.RECORD_AUDIO) },
            send = { aac, ms -> scope.launch(Dispatchers.IO) { app.chat.sendVoice(key, aac, ms) } },
        ), banner = banner) {
            val e = editing
            if (e != null) { app.chat.editMessage(key, e.id, text)?.let { Ui.say(it, true) }; editing = null }
            else { app.chat.sendPrivate(key, text, replyTo?.id ?: ""); replyTo = null }
            text = ""
        }
    }
    }
    sheetFor?.let { s ->
        val actions = buildList {
            if (s.unsent.isEmpty() && !s.failed) add(SheetAction("Reply", "reply") { editing = null; replyTo = s })
            if (s.mine && s.unsent.isEmpty() && !s.failed && s.photo.isEmpty() && s.text.isNotEmpty())
                add(SheetAction("Edit", "edit") {
                    if (!app.chat.hasM1(key)) Ui.say("$name needs an update for edits.", true)
                    else { replyTo = null; editing = s; text = s.text }
                })
            if (s.unsent.isEmpty() && s.text.isNotEmpty()) add(SheetAction("Copy text", "copy") {
                io.github.ravenor907.muninn.ui.Clip.copy(context, "Message", s.text)
                Ui.say("Copied.")
            })
            when {
                // Mine, and it got out: take it back from both phones, or just remove it here.
                s.mine && s.unsent.isEmpty() && !s.failed -> add(SheetAction("Unsend", "undo", danger = true) {
                    Ui.sure(Ask("Unsend this message?", "Removes it from both phones.",
                        "Unsend", danger = true, extra = "Delete for me", onExtra = { app.chat.deleteMessage(key, s.id) },
                        onConfirm = { app.chat.unsend(key, s.id) }))
                })
                s.mine && s.failed -> add(SheetAction("Try again", "send") { app.chat.retry(key, s.id) })
            }
            add(SheetAction("Delete for me", "delete", danger = true) {
                Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true, onConfirm = { app.chat.deleteMessage(key, s.id) }))
            })
        }
        MessageSheet(s.reactions[me]?.e?.takeIf { it.isNotEmpty() }, s.unsent.isEmpty() && !s.failed, actions,
            onReact = { e -> app.chat.react(key, s.id, e)?.let { Ui.say(it) } }) { sheetFor = null }
    }
}

private class VoiceHooks(val allowed: () -> Boolean, val ask: () -> Unit, val send: (ByteArray, Int) -> Unit)

@Composable
private fun Composer(text: String, onText: (String) -> Unit, hint: String, onPhoto: (() -> Unit)? = null, voice: VoiceHooks? = null,
                     banner: (@Composable () -> Unit)? = null, onSend: () -> Unit) {
    val context = LocalContext.current
    var emoji by remember { mutableStateOf(false) }
    if (emoji) EmojiPicker("Emoji", { e -> onText((text + e).take(Chat.MAX)) }) { emoji = false }
    banner?.invoke()
    val recording by Voice.recording.collectAsState()
    var dragged by remember { mutableStateOf(0f) }
    var shown by remember { mutableStateOf(0L) }
    LaunchedEffect(recording) { while (recording) { shown = Voice.elapsed(); kotlinx.coroutines.delay(200) } }
    if (voice != null && recording) {
        // Recording: hold the mic, let go to send, slide left to cancel.
        Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp).fillMaxWidth().clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.dangerFill)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Palette.danger, CircleShape))
            Text("  ${Voice.length(shown.toInt())}", style = Type.big)
            Spacer(Modifier.weight(1f))
            Text(if (dragged < -120f) "Let go to cancel" else "‹ Slide left to cancel", style = Type.small.copy(color = if (dragged < -120f) Palette.danger else Palette.textSoft))
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = if (onPhoto != null) 6.dp else 12.dp, end = 10.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom) {
        if (onPhoto != null) { IconAction("image", "Send a picture", Palette.textMuted, size = 24.dp) { onPhoto() }; Spacer(Modifier.width(4.dp)) }
        val shape = io.github.ravenor907.muninn.ui.LookShapes.composer
        Row(Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(Palette.field).hcBorder(shape).padding(start = 16.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(hint, style = bodyStyle.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(text, { onText(it.take(Chat.MAX)) }, textStyle = bodyStyle, cursorBrush = SolidColor(Palette.accent), maxLines = 6,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint })
            }
            IconAction("smile", "Emoji", Palette.textMuted, size = 20.dp) { emoji = true }
        }
        Spacer(Modifier.width(8.dp))
        if (voice != null && text.isBlank()) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (recording) Palette.danger else Palette.gold)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!voice.allowed()) { voice.ask(); return@awaitEachGesture }
                        dragged = 0f
                        Voice.onLimit = { Voice.finish()?.let { (aac, ms) -> voice.send(aac, ms); Ui.say("5 minutes is the most: sent.") } }
                        if (!Voice.start(context)) { Ui.say("The microphone isn't available right now.", true); return@awaitEachGesture }
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            dragged = c.position.x - down.position.x
                            if (!c.pressed) break
                        }
                        if (!Voice.recording.value) { dragged = 0f; return@awaitEachGesture }   // the 5-minute limit already sent it
                        if (dragged < -120f) { Voice.cancel(); Ui.say("Recording cancelled.") }
                        else Voice.finish()?.let { (aac, ms) -> voice.send(aac, ms) } ?: Ui.say("Hold the mic while you talk.")
                        dragged = 0f
                    }
                }.semantics { contentDescription = "Hold to record a voice message" },
                contentAlignment = Alignment.Center) { MIcon("mic", if (recording) Palette.ground else Palette.onGold, 20.dp, weight = 2f) }
        } else {
            val ready = text.isNotBlank()
            Box(Modifier.size(48.dp).clip(CircleShape).background(if (ready) Palette.gold else Palette.accentFill)
                .clickable(enabled = ready, role = Role.Button) { onSend() }.semantics { contentDescription = "Send" },
                contentAlignment = Alignment.Center) { MIcon("send", if (ready) Palette.onGold else Palette.textFaint, 20.dp, weight = 2f) }
        }
    }
}

@Composable
private fun MyCode(app: Muninn) {
    val context = LocalContext.current
    var code by remember { mutableStateOf(app.chat.myCode()) }
    Column(Modifier.fillMaxSize()) {
        PageBar("My code", "Back to Add someone") { ChatNav.open = "add" }
        // Scrolls, so with larger size and text settings the buttons can always be reached.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // The code fits the width and at most half the height, so it never pushes the buttons far away.
            val qr = minOf(maxWidth - 32.dp - 20.dp, maxHeight * 0.5f, 280.dp).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), contentAlignment = Alignment.Center) { Qr(Contacts.link(code), qr) }
                Text("Scan to add me. Works for 1 hour.", style = Type.normal, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Share", Modifier.weight(1f), Kind.Accent, icon = "send") {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Message me on Muninn. Tap to add me (works for 1 hour):\n${Contacts.link(code)}"), "Share your Muninn code"))
                    }
                    MButton("Copy", Modifier.weight(1f), icon = "copy") {
                        io.github.ravenor907.muninn.ui.Clip.copy(context, "Muninn code", code)
                        Ui.say("Code copied.")
                    }
                }
                // Less used: a link that adds us once (for someone far away), and a fresh code.
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("One-time link", Modifier.weight(1f), icon = "link") {
                        val link = app.chat.inviteLink(24)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Message me on Muninn. This link adds us once, within a day:\n$link"), "Send a one-time link"))
                    }
                    MButton("New code", Modifier.weight(1f), icon = "refresh") { code = app.chat.myCode(); Ui.say("New code made.") }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * + › Add someone: the camera first. Point it at their code (or a group's invite) and it's read at once.
 * Underneath: my code, pasting a code or link, a new group, and tapping phones (with NFC).
 */
@Composable
private fun AddSomeone(app: Muninn) {
    val context = LocalContext.current
    var text by remember {
        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        mutableStateOf(ChatNav.incoming.ifEmpty { if (Contacts.unlink(clip).contains(Contacts.PREFIX) || clip.contains(Proto06.CODE_PREFIX)) clip else "" }.also { ChatNav.incoming = "" })
    }
    // Tap phones together needs NFC (and isn't offered on a TV).
    val hasNfc = remember { !io.github.ravenor907.muninn.tv.KidMode.isTv(context) && android.nfc.NfcAdapter.getDefaultAdapter(context) != null }
    // "": the camera; "paste": typing or pasting a code or invite link.
    var mode by remember { mutableStateOf("") }
    val found = Contacts.parse(text)
    val invite = GroupLinks.parse(app, text)
    BackHandler(enabled = text.isNotEmpty() || mode.isNotEmpty()) { if (text.isNotEmpty()) text = "" else mode = "" }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Add someone")
        // Scrolls, so everything stays reachable with larger size and text settings or the keyboard up.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            when {
                text.isBlank() && mode.isEmpty() -> {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        QrScanner(Modifier.fillMaxWidth().height(minOf(maxWidth, 340.dp))) { scanned -> text = Contacts.unlink(scanned) }
                    }
                    Text("Point at their code.", style = Type.normal, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                text.isBlank() -> Field(text, { text = it }, "Paste a code or link", Modifier.padding(vertical = 12.dp), single = false)
                invite != null -> Panel(Modifier.padding(top = 12.dp)) { JoinCard(app, invite) { text = ""; if (!invite.expired) ChatNav.open = "" } }
                found == null -> {
                    Field(text, { text = it }, "Paste a code or link", Modifier.padding(vertical = 10.dp), single = false)
                    Text("That isn't a Muninn code.", style = Type.small.copy(color = Palette.danger))
                }
                Contacts.expires(text) in 1 until System.currentTimeMillis() -> Text("That code has ended. Ask for a new one.",
                    style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 12.dp))
                found.chatKey == app.identity.publicKey -> Text("That's your own code.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 12.dp))
                else -> Panel(Modifier.padding(top = 12.dp)) {
                    Text("Found: ${found.name}", style = Type.big)
                    // Someone already here with a different key: adding this is a key change, so say it first.
                    val replaces = app.chat.keyChangeFor(found)
                    replaces?.let { old ->
                        Text("New security key for ${old.shown}. Check emoji after.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Not now", Modifier.weight(1f)) { text = "" }
                        MButton("Add ${found.name}", Modifier.weight(1f), Kind.Accent) {
                            app.chat.addContact(found)
                            // A one-time invite link: say hi at once, which adds me on their phone too (the link is used up then).
                            if (Contacts.once(text)) app.chat.sendPrivate(found.chatKey, "Hi, it's ${app.identity.username}. I added you with your invite link.")
                            // A Kids' TV's code: offer to set it up (no lock on the TV, so ask first).
                            io.github.ravenor907.muninn.tv.TvParent.adoptIfTv(app, text)
                            // A key change has its own warning (from Chat): don't cover it.
                            if (replaces == null) Ui.say("${found.name} added.")
                            ChatNav.open = found.chatKey
                        }
                    }
                }
            }
            io.github.ravenor907.muninn.ui.Framed {
                io.github.ravenor907.muninn.ui.NavRow("My code", icon = "qr") { ChatNav.open = "code" }
                if (mode.isEmpty() && text.isBlank()) io.github.ravenor907.muninn.ui.NavRow("Paste a code or link", icon = "copy") { mode = "paste" }
                io.github.ravenor907.muninn.ui.NavRow("New group", icon = "people") { ChatNav.open = "newgroup" }
                if (hasNfc) io.github.ravenor907.muninn.ui.NavRow("Tap phones together", icon = "phone") { ChatNav.open = "tap" }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A message. [quote]: the message a reply answers (name, preview), tapped to jump there. Reactions sit under
 * it ([me]: my key, to mark mine). [groupRead]: a group message of mine, how many have read it (a check mark
 * and that number in a circle), -1 otherwise. [onReply]: swipe right to reply. [highlight]: a search match
 * or an @mention of me.
 */
/** The bubble color picked for [key]'s chat, or Unspecified for the usual grey. */
@Composable
internal fun mineTint(app: Muninn, key: String): Color {
    val st by app.chat.flags.all.collectAsState()
    return st.colors[key]?.let { io.github.ravenor907.muninn.net.ChatFlags.COLORS[it] }?.let { Color(it.first) } ?: Color.Unspecified
}

/** My bubbles' color in the chat on screen (Unspecified: the usual). Set per chat with Chat color. */
internal val LocalMineBubble = androidx.compose.runtime.compositionLocalOf { Color.Unspecified }

@Composable
private fun Bubble(m: Chat.Msg, showName: Boolean, theirName: String? = null, photo: (() -> ByteArray?)? = null, statusText: String? = null,
                   quote: Pair<String, String>? = null, onQuote: () -> Unit = {}, me: String = "", groupRead: Int = -1, highlight: Boolean = false,
                   onReply: (() -> Unit)? = null, onReactions: () -> Unit = {}, onTap: () -> Unit = {},
                   /** Cap m2: an album shown in this bubble, opening one of its photos; a vote on a poll; who voted, by key. */
                   album: List<Chat.Msg>? = null, albumBytes: (Chat.Msg) -> ByteArray? = { null }, onAlbumTap: (Chat.Msg) -> Unit = {},
                   onVote: ((List<Int>) -> Unit)? = null, voterName: (String) -> String = { "Someone" }, onLong: () -> Unit) {
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    val replyNow by rememberUpdatedState(onReply)
    val align = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart
    // The corner nearest the sender is the sharp one.
    val r = if (Palette.seasonal) 16.dp else 4.dp
    val shape = if (m.mine) RoundedCornerShape(r, r, 1.dp, r) else RoundedCornerShape(r, r, r, 1.dp)
    val status = when {
        statusText != null -> statusText
        !m.mine -> ""
        m.state == "unsending" -> "Removing from their phone"
        m.unsent.isNotEmpty() -> ""
        m.failed -> "Didn't send"
        // Honest ticks: "Delivered" only once their phone has it, else whose phone it's waiting for.
        else -> io.github.ravenor907.muninn.net.Ticks.private(m.state, theirName)
    }
    val edited = if (m.edited > 0 && m.unsent.isEmpty()) "(edited)" else ""
    // A group message of mine that someone read: the check mark and the count stand in for "Read by 2".
    val showRead = groupRead > 0 && m.mine && m.unsent.isEmpty()
    val meta = listOf(edited, clock(m.time), if (showRead) "" else status).filter { it.isNotEmpty() }.joinToString(" · ")
    val metaStyle = tinyStyle.copy(color = if (m.failed) Palette.danger else Palette.textMuted)
    val metaLine: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = if (showRead) Modifier.semantics { contentDescription = "$meta, read by $groupRead" } else Modifier) {
            // Wraps to a second line at a large text size; the read mark keeps its place beside it.
            Text(meta, style = metaStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (showRead) { Spacer(Modifier.width(4.dp)); MIcon("check", Palette.gold, 13.dp, weight = 2.2f); Spacer(Modifier.width(3.dp)); Circled(groupRead) }
        }
    }
    val isVoice = m.unsent.isEmpty() && m.voice > 0 && photo != null
    val isVideo = m.unsent.isEmpty() && photo != null && m.video > 0
    val isPhoto = m.unsent.isEmpty() && photo != null && m.voice == 0 && m.video == 0 && m.file.isEmpty()
    // Swipe right to reply: the bubble follows the finger a little, and past the line it's a reply.
    var dragX by remember { mutableStateOf(0f) }
    val swipe = if (onReply != null && m.unsent.isEmpty()) Modifier.pointerInput(m.id) {
        detectHorizontalDragGestures(
            onDragEnd = { if (dragX > 140f) replyNow?.invoke(); dragX = 0f },
            onDragCancel = { dragX = 0f },
        ) { change, amount -> dragX = (dragX + amount).coerceIn(0f, 220f); if (dragX > 0f) change.consume() }
    } else Modifier
    Column(Modifier.fillMaxWidth().then(swipe)) {
        Box(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(dragX.toInt() / 2, 0) }, contentAlignment = align) {
            if (dragX > 40f) MIcon("reply", if (dragX > 140f) Palette.gold else Palette.textMuted, 20.dp, Modifier.align(Alignment.CenterStart).offset(x = (-28).dp))
            Box(Modifier.fillMaxWidth(0.8f), contentAlignment = align) {
                Column(
                    Modifier.clip(shape).background(if (highlight) Palette.goldTint else if (m.mine) LocalMineBubble.current.takeOrElse { Palette.bubbleMine } else Palette.field).hcBorder(shape)
                        .then(if (highlight) Modifier.border(1.dp, Palette.gold, shape) else Modifier)
                        .pointerInput(m.id) { detectTapGestures(onTap = { if (photo != null && m.voice == 0) tapNow() }, onLongPress = { longNow() }) },
                ) {
                    val name: @Composable () -> Unit = {
                        if (showName && !m.mine) Text(m.name, style = tinyStyle.copy(fontWeight = FontWeight.SemiBold, color = Palette.goldBright), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    val quoted: @Composable () -> Unit = { if (quote != null && m.unsent.isEmpty()) QuoteBlock(quote.first, quote.second, onQuote) }
                    when {
                        isVoice -> Column(Modifier.padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)) {
                            name(); quoted()
                            // Mine: length and receipt ("0:04 · Read"). Theirs: length and when it came.
                            VoiceNote(m, photo!!, Voice.length(m.voice) + " · " + (if (m.mine) status.ifEmpty { clock(m.time) } else clock(m.time)), metaStyle)
                        }
                        isVideo -> Column(Modifier.padding(4.dp)) {
                            Box(Modifier.padding(horizontal = 8.dp)) { Column { name(); quoted() } }
                            VideoThumb(m, photo!!)
                            Box(Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) { metaLine() }
                        }
                        m.file.isNotEmpty() && m.unsent.isEmpty() && photo != null -> Column {
                            Box(Modifier.padding(horizontal = 12.dp)) { Column { name(); quoted() } }
                            FileCard(m, photo)
                            Box(Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) { metaLine() }
                        }
                        isPhoto && album != null -> Column(Modifier.padding(4.dp)) {
                            Box(Modifier.padding(horizontal = 8.dp)) { Column { name(); quoted() } }
                            AlbumGrid(album, albumBytes, onAlbumTap)
                            Box(Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) { metaLine() }
                        }
                        isPhoto -> Column(Modifier.padding(4.dp)) {
                            Box(Modifier.padding(horizontal = 8.dp)) { Column { name(); quoted() } }
                            val bmp by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) { decode(photo!!(), 720) } }
                            val pic = io.github.ravenor907.muninn.ui.Corner
                            Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(260f / 170f).clip(pic).background(Palette.groundDeep),
                                contentAlignment = Alignment.Center) {
                                bmp?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                    ?: Text("Photo", style = Type.small)
                            }
                            Box(Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) { metaLine() }
                        }
                        m.poll != null && m.unsent.isEmpty() -> Column(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                            Box(Modifier.padding(horizontal = 12.dp)) { Column { name(); quoted() } }
                            PollCard(m.poll, m.votes, me, voterName, onVote)
                            Box(Modifier.align(Alignment.End).padding(horizontal = 12.dp)) { metaLine() }
                        }
                        else -> Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            name(); quoted()
                            // The time (and receipt) sits inline after the text, on its last line.
                            Row(verticalAlignment = Alignment.Bottom) {
                                if (m.unsent.isNotEmpty()) Text(if (m.mine) "Message deleted by you" else "Message deleted by ${theirName ?: m.unsent}",
                                    style = bodyStyle.copy(color = Palette.textFaint, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                                    modifier = Modifier.weight(1f, fill = false))
                                else Text(m.text, style = bodyStyle, modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp))
                                metaLine()
                            }
                            // Cap m2: what a link goes to, made on the sender's phone.
                            if (m.unsent.isEmpty()) m.preview?.let { Box(Modifier.padding(top = 6.dp)) { PreviewCard(it) } }
                        }
                    }
                }
            }
        }
        if (m.unsent.isEmpty()) Box(Modifier.fillMaxWidth(), contentAlignment = align) {
            Box(Modifier.fillMaxWidth(0.8f)) { ReactionChips(m.reactions, me, m.mine, onReactions) }
        }
    }
}

/** Decodes a JPEG, no bigger than [max] pixels on the long side. */
internal fun decode(bytes: ByteArray?, max: Int): ImageBitmap? {
    if (bytes == null) return null
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    var sample = 1
    while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
private fun UriThumb(uri: Uri, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { try { context.contentResolver.openInputStream(uri)?.use { decode(it.readBytes(), 200) } } catch (e: Exception) { null } }
    }
    Box(modifier.background(Palette.groundDeep, Corner), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(Corner)) }
    }
}

/** A photo full screen: Back, or Save to the phone's Pictures/Muninn. */
@Composable
private fun PhotoViewer(app: Muninn, m: Chat.Msg, close: () -> Unit) {
    val context = LocalContext.current
    val bytes = remember(m.id) { app.chat.photo(m.photo) }
    val bmp = remember(m.id) { decode(bytes, 2048) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Back", icon = "back") { close() }
            Spacer(Modifier.weight(1f))
            MButton("Save to phone", kind = Kind.Accent, icon = "download", enabled = bytes != null) {
                Ui.say(if (bytes != null && savePhoto(context, bytes)) "Saved in Pictures › Muninn." else "Couldn't save it.", bytes == null)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Text("This photo can't be shown.", style = Type.normal)
        }
    }
}

private fun savePhoto(context: Context, bytes: ByteArray): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Muninn-${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Muninn")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    uri != null && context.contentResolver.openOutputStream(uri)?.use { it.write(bytes); true } == true
} catch (e: Exception) { false }

/** A voice message: a gold play/pause button, a thin progress bar and [label]. Plays from memory only. */
@Composable
internal fun VoiceNote(m: Chat.Msg, sound: () -> ByteArray?, label: String, labelStyle: TextStyle) {
    val playing by Voice.playing.collectAsState()
    val progress by Voice.progress.collectAsState()
    val mine = playing == m.id
    LaunchedEffect(mine) { while (mine) { Voice.tick(); kotlinx.coroutines.delay(100) } }
    val scope = rememberCoroutineScope()
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A 36dp button with a full 48dp touch area around it.
        Box(Modifier.minimumInteractiveComponentSize().size(36.dp).clip(CircleShape).background(Palette.gold).clickable(role = Role.Button) {
            if (mine) Voice.stopPlaying()
            else scope.launch { val b = withContext(Dispatchers.IO) { sound() }; if (b != null) Voice.play(m.id, b) else Ui.say("This voice message can't be played.", true) }
        }.semantics { contentDescription = if (mine) "Pause voice message" else "Play voice message" },
            contentAlignment = Alignment.Center) { MIcon(if (mine) "pausefill" else "play", Palette.onGold, 14.dp, filled = true) }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.track)) {
            Box(Modifier.fillMaxWidth(if (mine) progress.coerceIn(0f, 1f) else 0f).height(4.dp).background(Palette.gold))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Message requests: someone with my code who isn't a contact yet, and group invites. Nothing reaches the chats until accepted. */
@Composable
private fun RequestsScreen(app: Muninn, back: () -> Unit) {
    BackHandler { back() }
    val requests by app.chat.requests.collectAsState()
    val ginvites by app.chat.groups.pending.collectAsState()
    val intros by app.chat.intros.collectAsState()
    @Suppress("UNUSED_VARIABLE") val gs by app.chat.groups.all.collectAsState()
    val asked = intros.filter { !it.iSaid && !it.declined }
    val offers = app.chat.familyOffers()
    Column(Modifier.fillMaxSize()) {
        PageBar("Requests")
        LazyColumn(Modifier.weight(1f).padding(top = 12.dp)) {
            if (requests.isEmpty() && ginvites.isEmpty() && asked.isEmpty() && offers.isEmpty()) item { Text("No requests.", style = Type.normal, modifier = Modifier.padding(16.dp)) }
            // Cap i1: introductions, and people in a group asking to be in touch.
            items(asked, key = { "n" + it.id }) { i ->
                Panel(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    val by = if (i.by.startsWith("g:")) app.chat.groups.group(i.by.removePrefix("g:"))?.state?.name ?: "a group" else app.chat.nameOf(i.by)
                    Text(if (i.by.startsWith("g:")) "${i.n}, from $by, wants to be in touch" else "$by wants to introduce you to ${i.n}", style = Type.big)
                    if (i.theySaid) Text("${i.n} said yes.", style = Type.small)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Yes", kind = Kind.Accent) { app.chat.answerIntro(i.id, true) }
                        MButton("No thanks") { app.chat.answerIntro(i.id, false) }
                    }
                }
            }
            items(offers, key = { "f" + it.id }) { o ->
                Panel(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    val gname = app.chat.groups.group(o.by.removePrefix("g:"))?.state?.name ?: "Family"
                    Text("Add ${o.n} as a contact?", style = Type.big)
                    Text("In $gname with you.", style = Type.small)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Add", kind = Kind.Accent) { app.chat.askToAdd(o.by.removePrefix("g:"), o.k, o.n, o.p); Ui.say("Asked ${o.n}.") }
                        MButton("Not now") { app.chat.dismissOffer(o) }
                    }
                }
            }
            items(requests, key = { "r" + it.peerKey }) { r ->
                Panel(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    Text("${r.name} wants to chat", style = Type.big)
                    // A new key with a contact's inbox: never taken on quietly.
                    if (r.replaces.isNotEmpty()) Text("Claims to replace ${app.chat.nameOf(r.replaces)}. Check first.",
                        style = Type.small.copy(color = Palette.danger))
                    if (r.viaInternet) Text("Only accept if you know them.", style = Type.small)
                    else {
                        Text("Code: ${r.code}", style = Type.title.copy(color = Palette.accent), modifier = Modifier.padding(vertical = 6.dp))
                        Text("Same code on their screen?", style = Type.small)
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Accept", kind = Kind.Accent) { app.chat.acceptRequest(r); ChatNav.open = r.peerKey }
                        MButton("Decline") { app.chat.declineRequest(r) }
                    }
                }
            }
            // Group invites from contacts: nothing changes until they're accepted.
            items(ginvites, key = { "i" + it.g + it.inv }) { inv ->
                Panel(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    Text("${app.chat.contacts.get(inv.from)?.shown ?: inv.fromName} invited you to ${inv.name}", style = Type.big)
                    Text("${inv.state.members.size} ${if (inv.state.members.size == 1) "person" else "people"} in it.", style = Type.small)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Join", kind = Kind.Accent) {
                            val err = app.chat.groups.acceptInvite(inv)
                            if (err != null) Ui.say(err, true) else Ui.say("Asked to join ${inv.name}.")
                        }
                        MButton("Decline") { app.chat.groups.declineInvite(inv) }
                    }
                }
            }
        }
    }
}

/** Mute notifications from a chat: for a while, or until turned back on. Only on this phone. */
@Composable
internal fun MuteDialog(app: Muninn, key: String, name: String, done: () -> Unit) {
    val flags = io.github.ravenor907.muninn.net.ChatFlags
    fun mute(ms: Long, said: String) {
        app.chat.flags.mute(key, if (ms == flags.FOREVER) flags.FOREVER else System.currentTimeMillis() + ms)
        Ui.say(said); done()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Mute $name", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Calls still ring.", style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
                Line("For 1 hour", "", icon = "clock") { mute(flags.HOUR, "$name is muted for an hour.") }
                Line("For 8 hours", "", icon = "clock") { mute(8 * flags.HOUR, "$name is muted for 8 hours.") }
                Line("For a week", "", icon = "clock") { mute(7 * 24 * flags.HOUR, "$name is muted for a week.") }
                Line("Until I turn it back on", "", icon = "mute") { mute(flags.FOREVER, "$name is muted.") }
            }
        },
        confirmButton = { MButton("Cancel") { done() } },
    )
}

/** Introduce [key] to another contact: both are asked, and if both say yes they're in touch. */
@Composable
internal fun IntroduceDialog(app: Muninn, key: String, name: String, done: () -> Unit) {
    val all by app.chat.contacts.all.collectAsState()
    val others = all.filter { !it.groupOnly && it.chatKey != key }.sortedBy { it.shown.lowercase() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Introduce $name to", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Both are asked first.", style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
                if (others.isEmpty()) Text("No other contacts yet.", style = Type.normal)
                others.forEach { c ->
                    Line(c.shown, "", icon = "user") {
                        val err = app.chat.introduce(key, c.chatKey)
                        Ui.say(err ?: "Asked $name and ${c.shown}.", err != null)
                        done()
                    }
                }
            }
        },
        confirmButton = { MButton("Cancel") { done() } },
    )
}

// ------------------------------------------------------------------ groups (protocol 0.6, section 3)


/** How a member is shown: "You", a contact's nickname, or the name they have in the group. */
internal fun memberName(app: Muninn, m: Proto06.Member): String =
    if (m.k == app.identity.publicKey) "You" else app.chat.contacts.contact(m.k)?.shown ?: m.n.ifBlank { "Someone" }

/** What the chat list shows under a group's name: who said it, and what. */
private fun groupPreview(app: Muninn, m: Groups.GMsg): String {
    val who = if (m.mine) "You" else app.chat.contacts.contact(m.from)?.shown ?: m.name
    val what = when {
        m.unsent.isNotEmpty() -> "Message deleted"
        m.voice > 0 -> "Voice message · ${Voice.length(m.voice)}"
        m.photo.isNotEmpty() -> "Photo"
        else -> m.text.replace('\n', ' ')
    }
    return "$who: $what"
}

/** Unread in a group: theirs since my last message (at least 1 once the group is marked unread). */
private fun groupUnread(msgs: List<Groups.GMsg>): Int {
    var n = 0
    for (m in msgs.asReversed()) { if (m.mine) break; if (m.unsent.isEmpty() && !m.seen) n++ }
    return maxOf(n, 1)
}

/** A group message as a bubble: the sender's nickname if they're a contact. */
private fun asMsg(app: Muninn, m: Groups.GMsg) = Chat.Msg(m.id, m.from,
    if (m.mine) app.identity.username else app.chat.contacts.contact(m.from)?.shown ?: m.name, m.text, m.mine, m.time,
    state = m.state, photo = m.photo, unsent = m.unsent, voice = m.voice, re = m.re, reactions = m.reactions, edited = m.edited,
    video = m.video, album = m.album, albumAt = m.albumAt, albumOf = m.albumOf, poll = m.poll, votes = m.votes, preview = m.preview)

/** Two members' faces, one over the other; a group of one or two shows a single circle. */
@Composable
private fun GroupAvatar(name: String, others: List<Proto06.Member>, size: Dp) {
    val app = LocalContext.current.applicationContext as Muninn
    if (others.size < 2) {
        val one = others.firstOrNull()
        if (one != null) io.github.ravenor907.muninn.ui.Avatar(one.k, memberName(app, one), size)
        else Box(Modifier.size(size).clip(CircleShape).background(Palette.coolTint), contentAlignment = Alignment.Center) {
            Text(initial(name), style = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.36f).sp, color = Palette.accent))
        }
        return
    }
    val small = size * 0.68f
    Box(Modifier.size(size).semantics { contentDescription = "Group" }) {
        Box(Modifier.align(Alignment.TopStart).size(small).clip(CircleShape).background(Palette.ground).padding(2.dp)) {
            io.github.ravenor907.muninn.ui.Avatar(others[0].k, memberName(app, others[0]), small - 4.dp)
        }
        Box(Modifier.align(Alignment.BottomEnd).size(small).clip(CircleShape).background(Palette.ground).padding(2.dp)) {
            io.github.ravenor907.muninn.ui.Avatar(others[1].k, memberName(app, others[1]), small - 4.dp)
        }
    }
}

/** A round chip (folders, channels). [onLong]: hold to rename or delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Chip(text: String, on: Boolean, icon: String? = null, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = io.github.ravenor907.muninn.ui.Corner
    // A 40dp chip with a full 48dp touch area.
    Row(Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp).clip(shape)
        .then(if (on) Modifier.background(Palette.gold) else Modifier.border(1.dp, if (Access.contrast) Palette.lineMuted else Palette.line, shape))
        .combinedClickable(role = Role.Tab, onLongClickLabel = if (onLong != null) "Rename or delete" else null, onLongClick = onLong, onClick = onClick)
        .semantics { selected = on }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { MIcon(icon, if (on) Palette.onGold else Palette.textMuted, 16.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = bodyStyle.copy(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) Palette.onGold else Palette.text), maxLines = 1)
    }
}

/**
 * A folder's section header on the chat list: tap to fold it away or open it, hold to rename or delete it.
 * [unread]: chats in it with something new, shown on the header so a folded section still says so.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(f: Folders.Folder, unread: Int, hover: Boolean, onToggle: () -> Unit, onEdit: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (hover) Palette.focusFill else Color.Transparent)
        .combinedClickable(role = Role.Button, onClickLabel = if (f.collapsed) "Show" else "Fold away", onLongClickLabel = "Rename or delete",
            onLongClick = onEdit, onClick = onToggle)
        .semantics { heading(); stateDescription = if (f.collapsed) "Folded away" else "Showing" }
        .heightIn(min = 48.dp).padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MIcon("forward", Palette.textMuted, 14.dp, Modifier.rotate(if (f.collapsed) 0f else 90f), weight = 2f)
        Spacer(Modifier.width(6.dp))
        Text(f.name.uppercase(), style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (unread > 0) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.gold).semantics { contentDescription = "$unread unread" })
    }
}

/** Renaming or deleting a folder (from its header). [done]: the dialog closed. */
@Composable
private fun FolderDialogs(app: Muninn, editing: Folders.Folder?, done: () -> Unit) {
    editing?.let { f ->
        NameDialog("Folder", f.name, "Save", "Folder name", extra = "Delete folder", onExtra = {
            done()
            Ui.sure(Ask("Delete ${f.name}?", "The chats in it stay.", "Delete", danger = true,
                onConfirm = { app.chat.folders.delete(f.id) }))
        }) { n -> done(); if (n != null) app.chat.folders.rename(f.id, n) }
    }
}

/** What putting a contact in the trash does, said the same way everywhere. */
internal const val TRASH_NOTE = "Restore from Trash within 30 days."

/** A name in a dialog (new folder, rename folder). [done] gets null for Cancel. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun NameDialog(title: String, start: String, confirm: String, hint: String, note: String = "", max: Int = Folders.MAX_NAME,
                       extra: String? = null, onExtra: () -> Unit = {}, done: (String?) -> Unit) {
    var text by remember { mutableStateOf(start) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { done(null) },
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (note.isNotEmpty()) Text(note, style = Type.normal)
                Field(text, { text = it.take(max) }, hint, Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { MButton(confirm, kind = Kind.Accent, enabled = text.isNotBlank()) { done(text.trim()) } },
        dismissButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Cancel") { done(null) }
                if (extra != null) MButton(extra, kind = Kind.Danger) { onExtra() }
            }
        },
    )
}

@Composable
private fun NoGroup() {
    Column(Modifier.fillMaxSize()) {
        PageBar("Group")
        Text("This group isn't here.", style = Type.normal, modifier = Modifier.padding(16.dp))
    }
}

/** Add someone › New group: a name, its type, and the contacts to invite. */
@Composable
private fun NewGroup(app: Muninn) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    // Start a group (a person's menu): them already picked.
    var chosen by remember { mutableStateOf(ChatNav.groupWith) }
    LaunchedEffect(Unit) { ChatNav.groupWith = emptySet() }
    val contacts by app.chat.contacts.all.collectAsState()
    val people = contacts.filter { !it.groupOnly && !app.chat.trash.has(it.chatKey) }.sortedBy { it.shown.lowercase() }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("New group")
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            io.github.ravenor907.muninn.ui.Framed("Name") {
                Field(name, { name = it.take(Proto06.MAX_NAME) }, "Group name", Modifier.padding(vertical = 8.dp))
                GroupKindPicker(kind) { kind = it }
            }
            if (people.isNotEmpty()) io.github.ravenor907.muninn.ui.Framed("Tap people to add") {
                people.forEach { c ->
                    val on = c.chatKey in chosen
                    Line(c.shown, "", icon = if (on) "check" else "user", selected = on) { chosen = if (on) chosen - c.chatKey else chosen + c.chatKey }
                }
            }
            MButton("Create", Modifier.fillMaxWidth().padding(top = 16.dp), Kind.Accent, enabled = name.isNotBlank()) {
                val g = app.chat.groups.create(name, kind)
                if (g == null) Ui.say("Give the group a name.", true)
                else {
                    val failed = chosen.mapNotNull { k -> app.chat.inviteToGroup(g, k) }
                    if (ChatNav.wide) ChatNav.space = g
                    ChatNav.open = Folders.groupKey(g)
                    Ui.say(failed.firstOrNull() ?: "${name.trim()} is ready.", failed.isNotEmpty())
                }
            }
            // A group with the people picked, and a group call to them at once.
            MButton("Call them", Modifier.fillMaxWidth().padding(top = 12.dp), Kind.Normal, enabled = chosen.isNotEmpty(), icon = "call") {
                val picked = chosen.toList()
                val (g, msg) = app.chat.startGroup(picked)
                if (g == null) { Ui.say(msg, true); return@MButton }
                if (name.isNotBlank()) app.chat.groups.rename(g, name)
                ChatNav.openGroup(g)
                Ui.say(msg)
                // Rings once the group has reached them, and says who couldn't be rung.
                io.github.ravenor907.muninn.net.call.GroupRing.start(app, g, picked)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Phones: a group's channels as chips under its top bar; a voice room's chip joins it (and says who's in). */
@Composable
private fun ChannelChips(s: Proto06.State, current: String, onText: (String) -> Unit, onVoice: (String) -> Unit) {
    RoomManager.presence.collectAsState().value   // re-reads who is in which room when it changes
    val room by RoomManager.room.collectAsState()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.channels.forEach { c ->
            if (c.t == "text") Chip("# ${c.n}", c.id == current) { onText(c.id) }
            else {
                val n = RoomManager.whoIn(s.g, c.id).size
                Chip(if (n > 0) "${c.n} · $n" else c.n, room?.let { it.g == s.g && it.ch == c.id } == true, icon = "audio") { onVoice(c.id) }
            }
        }
        RoomManager.groupCalls(s.g).forEach { (id, who) ->
            Chip("Voice room · ${who.size}", room?.let { it.g == s.g && it.ch == id } == true, icon = "call") { onVoice(id) }
        }
    }
}

/** Who's in a voice room or group call, under its row: small faces, ringed while they talk. */
@Composable
private fun RoomFaces(people: List<RoomManager.Person>) {
    if (people.isEmpty()) return
    Row(Modifier.padding(start = 38.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        people.take(8).forEach { p ->
            Box(Modifier.size(28.dp).clip(CircleShape).then(if (p.speaking) Modifier.border(2.dp, Palette.goldBright, CircleShape) else Modifier)
                .semantics { contentDescription = p.name + if (p.speaking) ", speaking" else "" }, contentAlignment = Alignment.Center) {
                io.github.ravenor907.muninn.ui.Avatar(if (p.me) "me" else p.key, p.name, 28.dp)
            }
        }
    }
}

/** The group menu's "Pick people" call list, wherever it was opened. */
@Composable
internal fun GroupCallPicker(app: Muninn) {
    val g = ChatNav.callSome
    if (g.isEmpty()) return
    val s = app.chat.groups.group(g)?.state ?: run { ChatNav.callSome = ""; return }
    CallSomeDialog(app, s) { ChatNav.callSome = "" }
}

/** Ring some members of a group into a group call: tick who, then Call. */
@Composable
internal fun CallSomeDialog(app: Muninn, s: Proto06.State, done: () -> Unit) {
    val context = LocalContext.current
    val me = app.identity.publicKey
    val people = s.members.filter { it.k != me }.sortedBy { memberName(app, it).lowercase() }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Pick people", style = Type.big) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(people, key = { it.k }) { m ->
                    val old = RoomManager.needsUpdate(m.k)
                    Line(memberName(app, m), if (old) "Needs an update" else "",
                        icon = "user", selected = m.k in chosen) { if (!old) chosen = if (m.k in chosen) chosen - m.k else chosen + m.k }
                }
            }
        },
        confirmButton = { MButton("Call", kind = Kind.Accent, enabled = chosen.isNotEmpty()) { done(); RoomActivity.call(context, s.g, chosen.toList()) } },
        dismissButton = { MButton("Cancel") { done() } },
    )
}

@Composable
private fun GroupConversation(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    val chats by app.chat.groups.messages.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // names follow nicknames
    val grp = all[g]
    if (grp == null) { NoGroup(); return }
    val s = grp.state
    val key = Folders.groupKey(g)
    val texts = s.channels.filter { it.t == "text" }
    val ch = ChatNav.channel[g]?.takeIf { id -> texts.any { it.id == id } } ?: Proto06.MAIN
    val chName = s.channel(ch)?.n ?: "general"
    // A removed channel's messages show in main.
    val msgs = chats[g].orEmpty().filter { it.ch == ch || (ch == Proto06.MAIN && s.channel(it.ch)?.t != "text") }
    var text by remember(ch) { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(g) { app.chat.pruneSoon() }
    LaunchedEffect(msgs.lastOrNull()?.id) { app.chat.groups.markRead(g); app.chat.flags.read(Folders.groupKey(g)); if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    val context = LocalContext.current
    // While it's on screen, new messages here just buzz; its old notification goes.
    DisposableEffect(key) {
        app.openChat = key
        androidx.core.app.NotificationManagerCompat.from(context).cancel(key.hashCode())
        onDispose { if (app.openChat == key) app.openChat = "" }
    }
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Chat.Msg?>(null) }
    // Cap m2: several photos picked at once, the "+" menu, the camera ("photo" or "video"), a new poll, the Shared page.
    var pickedMany by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var attaching by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf("") }
    var polling by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    // Cap m1: answering, editing, and the message held for its sheet.
    var replyTo by remember(ch) { mutableStateOf<Groups.GMsg?>(null) }
    var editing by remember(ch) { mutableStateOf<Groups.GMsg?>(null) }
    var sheetFor by remember { mutableStateOf<Groups.GMsg?>(null) }
    val me = app.identity.publicKey
    fun whoOf(m: Groups.GMsg) = if (m.mine) "You" else app.chat.contacts.contact(m.from)?.shown ?: m.name
    fun quoteOf(re: String): Pair<String, String>? {
        if (re.isEmpty()) return null
        val q = chats[g].orEmpty().firstOrNull { it.id == re } ?: return "Message" to "Not here any more"
        return whoOf(q) to groupPreview(app, q).substringAfter(": ")
    }
    fun jumpTo(id: String) {
        val i = msgs.indexOfFirst { it.id == id }
        if (i < 0) Ui.say("That message isn't here.") else scope.launch { list.animateScrollToItem(i) }
    }
    if (replyTo != null || editing != null) BackHandler { replyTo = null; if (editing != null) { editing = null; text = "" } }
    // Search in this channel (from the group's menu, like Media).
    var searching by remember(ch) { mutableStateOf(false) }
    var query by remember(ch) { mutableStateOf("") }
    var matchAt by remember(ch) { mutableStateOf(0) }
    if (searching) BackHandler { searching = false; query = "" }
    val matches = if (!searching || query.isBlank()) emptyList()
        else msgs.indices.filter { msgs[it].unsent.isEmpty() && msgs[it].text.contains(query.trim(), ignoreCase = true) }.reversed()
    LaunchedEffect(matchAt, matches.size, query) { matches.getOrNull(matchAt)?.let { list.animateScrollToItem(it) } }
    val action = ChatNav.action
    LaunchedEffect(action) {
        when (action) {
            "search" -> { searching = true; matchAt = 0 }
            "media" -> shared = true
        }
        if (action.isNotEmpty()) ChatNav.action = ""
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(io.github.ravenor907.muninn.net.More.ALBUM_MAX)) { uris ->
        if (uris.size == 1) picked = uris[0] else if (uris.size > 1) pickedMany = uris
    }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Ui.say(if (ok) "Ready. Hold the mic to record." else "Needs the microphone.", !ok)
    }
    DisposableEffect(Unit) { onDispose { Voice.stopPlaying(); Voice.cancel() } }
    fun sendPhoto(uri: Uri, hd: Boolean) {
        preparing = true
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) { Photos.prepare(context, uri, hd) }
            if (jpeg == null) Ui.say("That photo couldn't be opened.", true)
            else withContext(Dispatchers.IO) { app.chat.sendGroupPhoto(g, ch, jpeg) }
            preparing = false; picked = null
        }
    }
    fun sendMany(uris: List<Uri>) {
        preparing = true
        scope.launch {
            val jpegs = withContext(Dispatchers.Default) { uris.mapNotNull { Photos.prepare(context, it, false) } }
            if (jpegs.isEmpty()) Ui.say("Those photos couldn't be opened.", true)
            else withContext(Dispatchers.IO) { app.chat.sendGroupPhotos(g, ch, jpegs) }
            preparing = false; pickedMany = emptyList()
        }
    }
    fun sendPollHere(poll: io.github.ravenor907.muninn.net.More.Poll) {
        app.chat.sendGroupPoll(g, ch, poll.q, poll.options, poll.multi)?.let { Ui.say(it, true) }
    }
    viewing?.let { v ->
        BackHandler { viewing = null }
        if (v.video > 0) VideoPlayer(app.chat.photo(v.photo), { viewing = null }) else PhotoViewer(app, v) { viewing = null }
        return
    }
    if (camera.isNotEmpty()) {
        CameraScreen(camera == "video", onPhoto = { jpeg -> scope.launch(Dispatchers.IO) { app.chat.sendGroupPhoto(g, ch, jpeg) } },
            onVideo = { mp4, ms -> scope.launch(Dispatchers.IO) { app.chat.sendGroupVideo(g, ch, mp4, ms)?.let { Ui.say(it) } } }) { camera = "" }
        return
    }
    if (shared) {
        SharedMedia("Shared · ${s.name}", chats[g].orEmpty().map { asMsg(app, it) }, { app.chat.photo(it.photo) }, { viewing = it }) { shared = false }
        return
    }
    val otherMembers = s.members.filter { it.k != app.identity.publicKey }
    val others = otherMembers.map { memberName(app, it) }
    val inRoom by RoomManager.room.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalMineBubble provides mineTint(app, key)) {
    Column(Modifier.fillMaxSize().imePadding()) {
        ThreadBar(if (texts.size > 1 || ChatNav.wide) "${s.name} · #$chName" else s.name,
            "${s.members.size} ${if (s.members.size == 1) "person" else "people"} · encrypted · ${io.github.ravenor907.muninn.net.Limits.groupDays(s)} days", "shield", Palette.online,
            { GroupAvatar(s.name, otherMembers, 40.dp) }, onMenu = { ChatNav.menuGroup = ""; ChatNav.hold = key })
        if (searching) ChatSearchBar(query, { query = it; matchAt = 0 }, matches.size, matchAt,
            onPrev = { if (matches.isNotEmpty()) matchAt = (matchAt + 1) % matches.size },
            onNext = { if (matches.isNotEmpty()) matchAt = (matchAt - 1 + matches.size) % matches.size }) { searching = false; query = "" }
        // Tablets list the channels in their own column.
        RoomManager.presence.collectAsState().value
        val calls = RoomManager.groupCalls(g)
        if (!ChatNav.wide && (s.channels.size > 1 || calls.isNotEmpty())) ChannelChips(s, ch, { ChatNav.channel[g] = it }) { RoomActivity.join(context, g, it) }
        inRoom?.let { r -> Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp), onClick = { RoomActivity.show(context) }) {
            Text("In ${r.title}${if (r.groupName.isNotEmpty()) " · ${r.groupName}" else ""}", style = Type.big)
            Text(if (r.muted) "You're muted · tap to open" else "Tap to open", style = Type.small)
        } }
        if (grp.readOnly) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text(if (grp.gone == "removed") "You were removed from ${s.name}" else "You left ${s.name}", style = Type.big)
            Text("Nothing new arrives.", style = Type.small)
        } else if (s.members.size == 1) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text("Just you so far", style = Type.big)
            MButton("Add people", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, icon = "plus") { ChatNav.open = "$key/add" }
        }
        val gAlbums = remember(msgs) { albumsOf(msgs.map { asMsg(app, it) }) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
            items(msgs.size, key = { msgs[it].id }) { i ->
                val gm = msgs[i]
                val m = asMsg(app, gm)
                Column {
                    if (i == 0 || dayIndex(msgs[i - 1].time) != dayIndex(gm.time)) DayLabel(gm.time)
                    // Mine: a check mark and how many have read it, circled; before anyone has, "Sending" or "Delivered".
                    if (m.id !in gAlbums.first) Bubble(m, showName = true, photo = if (gm.photo.isNotEmpty()) ({ app.chat.photo(gm.photo) }) else null,
                        statusText = if (gm.mine) (if (gm.read.isNotEmpty() || gm.unsent.isNotEmpty()) "" else
                            io.github.ravenor907.muninn.net.Ticks.group(gm.to, gm.delivered) { k -> s.member(k)?.let { memberName(app, it) } ?: "Someone" }) else null,
                        quote = quoteOf(gm.re), onQuote = { jumpTo(gm.re) }, me = me, groupRead = if (gm.mine) gm.read.size else -1,
                        highlight = matches.getOrNull(matchAt) == i || (!gm.mine && gm.unsent.isEmpty() && io.github.ravenor907.muninn.net.Rich.mentions(gm.text, app.identity.username)),
                        onReply = if (grp.readOnly) null else ({ editing = null; replyTo = gm }), onReactions = { sheetFor = gm },
                        onTap = { viewing = m }, album = gAlbums.second[m.id], albumBytes = { app.chat.photo(it.photo) }, onAlbumTap = { viewing = it },
                        onVote = if (grp.readOnly) null else ({ v -> app.chat.groupVote(g, gm.id, v) }),
                        voterName = { k -> s.member(k)?.let { memberName(app, it) } ?: "Someone" }) { sheetFor = gm }
                }
            }
        }
        if (msgs.isEmpty()) Text("No messages in #$chName yet.", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        picked?.let { uri ->
            Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UriThumb(uri, Modifier.size(72.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (preparing) "Getting it ready…" else "Send this photo to ${s.name}?", style = Type.big)
                        Text("Location and camera details are removed.", style = Type.small)
                    }
                }
                // Three a line; fewer at a large text size, so "High quality" isn't squeezed.
                io.github.ravenor907.muninn.ui.ChoiceRow(3, Modifier.padding(top = 8.dp), minWidth = 88.dp, gap = 8.dp) { n, m ->
                    when (n) {
                        0 -> MButton("Cancel", m, enabled = !preparing) { picked = null }
                        1 -> MButton("High quality", m, enabled = !preparing) { sendPhoto(uri, true) }
                        else -> MButton("Send", m, Kind.Accent, enabled = !preparing) { sendPhoto(uri, false) }
                    }
                }
            }
        }
        if (attaching) AttachDialog(onPhotos = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = { camera = "photo" }, onVideo = { camera = "video" }, onPoll = { polling = true }) { attaching = false }
        if (polling) PollDialog { poll -> polling = false; if (poll != null) sendPollHere(poll) }
        if (pickedMany.isNotEmpty()) Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { pickedMany.take(5).forEach { UriThumb(it, Modifier.size(52.dp)) } }
            Text(if (preparing) "Getting them ready…" else "Send ${pickedMany.size} photos as an album?", style = Type.big, modifier = Modifier.padding(top = 6.dp))
            Text("Location and camera details are removed.", style = Type.small)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Cancel", Modifier.weight(1f), enabled = !preparing) { pickedMany = emptyList() }
                MButton("Send", Modifier.weight(1f), Kind.Accent, enabled = !preparing) { sendMany(pickedMany) }
            }
        }
        val banner: (@Composable () -> Unit)? = editing?.let { e -> { ComposeBanner("Editing your message", e.text, "edit") { editing = null; text = "" } } }
            ?: replyTo?.let { r -> { ComposeBanner("Replying to ${whoOf(r)}", groupPreview(app, r).substringAfter(": "), "reply") { replyTo = null } } }
        if (!grp.readOnly) Composer(text, { text = it }, if (editing != null) "Edit your message" else "Message #$chName", banner = banner, onPhoto = {
            attaching = true
        }, voice = VoiceHooks(
            allowed = { androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            ask = { micAsk.launch(android.Manifest.permission.RECORD_AUDIO) },
            send = { aac, ms -> scope.launch(Dispatchers.IO) { app.chat.sendGroupVoice(g, ch, aac, ms) } },
        )) {
            val e = editing
            if (e != null) { app.chat.groups.editText(g, e.id, text)?.let { Ui.say(it, true) }; editing = null }
            else if (!app.chat.sendGroupText(g, ch, text, replyTo?.id ?: "")) Ui.say("Too long to send.", true)
            else replyTo = null
            text = ""
        }
    }
    }
    sheetFor?.let { gm ->
        val actions = buildList {
            if (!grp.readOnly && gm.unsent.isEmpty()) add(SheetAction("Reply", "reply") { editing = null; replyTo = gm })
            if (!grp.readOnly && gm.mine && gm.unsent.isEmpty() && gm.photo.isEmpty() && gm.text.isNotEmpty())
                add(SheetAction("Edit", "edit") { replyTo = null; editing = gm; text = gm.text })
            if (gm.unsent.isEmpty() && gm.text.isNotEmpty()) add(SheetAction("Copy text", "copy") {
                io.github.ravenor907.muninn.ui.Clip.copy(context, "Message", gm.text)
                Ui.say("Copied.")
            })
            // Who reacted with what: everyone in the group sees reactions.
            val shown = io.github.ravenor907.muninn.net.Rich.shown(gm.reactions)
            if (shown.isNotEmpty()) add(SheetAction(shown.entries.joinToString(", ") { (k, e) ->
                "$e " + (if (k == me) "You" else s.member(k)?.let { memberName(app, it) } ?: "Someone") }, "smile") {})
            if (gm.mine) add(SheetAction(Groups.status(gm).ifEmpty { "Sent" }, "check") {})
            // Per member: whose phones have it and whose it's still waiting for.
            if (gm.mine && gm.unsent.isEmpty()) io.github.ravenor907.muninn.net.Ticks.groupLines(gm.to, gm.delivered) { k ->
                s.member(k)?.let { memberName(app, it) } ?: "Someone" }.forEach { add(SheetAction(it, "check") {}) }
            if (gm.mine && gm.unsent.isEmpty() && !grp.readOnly) add(SheetAction("Unsend", "undo", danger = true) {
                Ui.sure(Ask("Unsend this message?",
                    "Removes it from everyone's phone.",
                    "Unsend", danger = true, extra = "Delete for me", onExtra = { app.chat.groups.deleteMessage(g, gm.id) },
                    onConfirm = { app.chat.groups.unsend(g, gm.id) }))
            })
            add(SheetAction("Delete for me", "delete", danger = true) {
                Ui.sure(Ask("Delete this message?", "It's deleted on this phone only.", "Delete", danger = true,
                    onConfirm = { app.chat.groups.deleteMessage(g, gm.id) }))
            })
        }
        MessageSheet(gm.reactions[me]?.e?.takeIf { it.isNotEmpty() }, !grp.readOnly && gm.unsent.isEmpty(), actions,
            onReact = { e -> if (!app.chat.groups.react(g, gm.id, e)) Ui.say("That reaction couldn't be added.", true) }) { sheetFor = null }
    }
}

/** Tablets: the middle column for a group: its TEXT channels, then its VOICE rooms, and me at the bottom. */
@Composable
fun ChannelColumn(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    val grp = all[g] ?: return
    val s = grp.state
    val current = ChatNav.channel[g] ?: Proto06.MAIN
    val here = ChatNav.groupOf(ChatNav.open) == g && !ChatNav.open.endsWith("/settings")
    val voice = s.channels.filter { it.t == "voice" }
    val context = LocalContext.current
    RoomManager.presence.collectAsState().value   // re-reads who is in which room when it changes
    val room by RoomManager.room.collectAsState()
    val calls = RoomManager.groupCalls(g)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 16.dp)) {
            Text("TEXT", style = labelStyle.copy(fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp, bottom = 6.dp).semantics { heading() })
            s.channels.filter { it.t == "text" }.forEach { c ->
                ChannelRow(c.n, voice = false, on = here && c.id == current) { ChatNav.channel[g] = c.id; ChatNav.open = Folders.groupKey(g) }
            }
            if (voice.isNotEmpty() || calls.isNotEmpty()) {
                Text("VOICE", style = labelStyle.copy(fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp).semantics { heading() })
                voice.forEach { c ->
                    ChannelRow(c.n, voice = true, on = room?.let { it.g == g && it.ch == c.id } == true) { RoomActivity.join(context, g, c.id) }
                    RoomFaces(RoomManager.whoIn(g, c.id))
                }
            }
            calls.forEach { (id, who) ->
                ChannelRow("Voice room", voice = true, on = room?.let { it.g == g && it.ch == id } == true) { RoomActivity.join(context, g, id) }
                RoomFaces(who)
            }
            if (grp.readOnly) Text(if (grp.gone == "removed") "You were removed from this group." else "You left this group.",
                style = Type.small, modifier = Modifier.padding(start = 8.dp, top = 18.dp))
        }
        Row(Modifier.fillMaxWidth().background(Palette.groundDeep).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar("me", app.identity.username, 36.dp)
            Text(app.identity.username, style = nameStyle.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp))
            if (room != null) MButton("Open", kind = Kind.Accent) { RoomActivity.show(context) }
            else if (!grp.readOnly && s.members.size > 1) MButton("Call", icon = "call") { RoomActivity.call(context, g, emptyList()) }
        }
    }
}

@Composable
private fun ChannelRow(name: String, voice: Boolean, on: Boolean, onClick: () -> Unit) {
    val shape = io.github.ravenor907.muninn.ui.Corner
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(if (on) Palette.raised else Color.Transparent)
        .clickable(role = Role.Tab, onClick = onClick).semantics { selected = on }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (voice) MIcon("audio", Palette.textMuted, 18.dp) else Text("#", style = bodyStyle.copy(fontSize = 19.sp, color = Palette.textMuted))
        Spacer(Modifier.width(8.dp))
        Text(name, style = bodyStyle.copy(color = if (on) Palette.text else Palette.textSoft), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Group settings: its name, how long it keeps messages, who can invite, its folders and its channels. */
@Composable
private fun GroupSettings(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    val grp = all[g]
    if (grp == null) { NoGroup(); return }
    val s = grp.state
    val key = Folders.groupKey(g)
    val me = app.identity.publicKey
    val admin = s.isAdmin(me) && !grp.readOnly
    var name by remember(g) { mutableStateOf(s.name) }
    var channel by remember { mutableStateOf<Proto06.Channel?>(null) }
    var addingChannel by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Group settings", "Back to ${s.name}") { ChatNav.open = key }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            io.github.ravenor907.muninn.ui.Framed("Name") {
                if (admin) {
                    Field(name, { name = it.take(Proto06.MAX_NAME) }, "Group name", Modifier.padding(top = 8.dp))
                    MButton("Save name", Modifier.fillMaxWidth().padding(vertical = 8.dp), enabled = name.isNotBlank() && name.trim() != s.name) {
                        app.chat.groups.rename(g, name)?.let { Ui.say(it, true) } ?: Ui.say("Renamed to ${name.trim()}.")
                    }
                } else Text(s.name, style = Type.big, modifier = Modifier.padding(vertical = 10.dp))
            }
            io.github.ravenor907.muninn.ui.Framed("Keep messages") { GroupLimitPanel(app, g, s, admin) }
            if (admin) io.github.ravenor907.muninn.ui.Framed("Who can invite") { MembersInviteToggle(app, g) }
            // Folders are only on this phone.
            if (folders.isNotEmpty()) io.github.ravenor907.muninn.ui.Framed("Folders") {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    folders.forEach { f -> val on = key in f.keys; DetailChip(f.name, on) { app.chat.folders.put(f.id, key, !on) } }
                }
            }
            if (admin || s.channels.size > 1) io.github.ravenor907.muninn.ui.Framed("Channels") {
                s.channels.forEach { c ->
                    Line(if (c.t == "voice") c.n else "# ${c.n}", if (c.t == "voice") "Voice room" else "", icon = if (c.t == "voice") "audio" else "chat") { if (admin) channel = c }
                }
                if (admin && s.channels.size < Proto06.MAX_CHANNELS) MButton("Add a channel", Modifier.fillMaxWidth().padding(vertical = 8.dp), icon = "plus") { addingChannel = true }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (addingChannel) ChannelDialog(app, g, null) { addingChannel = false }
    channel?.let { c -> ChannelDialog(app, g, c) { channel = null } }
}

/** A group's People: everyone in it. Tap someone for their menu, with the group's admin actions. */
@Composable
private fun GroupPeople(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()
    val grp = all[g]
    if (grp == null) { NoGroup(); return }
    val s = grp.state
    val me = app.identity.publicKey
    Column(Modifier.fillMaxSize()) {
        PageBar("People · ${s.members.size}", "Back to ${s.name}") { ChatNav.open = Folders.groupKey(g) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                io.github.ravenor907.muninn.ui.Framed {
                    s.members.sortedWith(compareBy({ it.k != s.owner }, { !s.isAdmin(it.k) }, { memberName(app, it).lowercase() })).forEach { m ->
                        val role = when { m.k == s.owner -> "Owner"; s.isAdmin(m.k) -> "Admin"; else -> "" }
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Corner)
                            .then(if (m.k != me) Modifier.clickable(role = Role.Button, onClickLabel = "Menu") { ChatNav.menuGroup = g; ChatNav.hold = m.k } else Modifier)
                            .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(if (m.k == me) "me" else m.k, memberName(app, m), 40.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(memberName(app, m), style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (role.isNotEmpty()) Text(role, style = Type.small)
                            }
                            if (m.k != me) io.github.ravenor907.muninn.ui.DotsButton("Menu for ${memberName(app, m)}", Palette.textMuted) { ChatNav.menuGroup = g; ChatNav.hold = m.k }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** A group's Add people: from contacts, by link or code, and the links still working. */
@Composable
private fun GroupAdd(app: Muninn, g: String) {
    val all by app.chat.groups.all.collectAsState()
    val contacts by app.chat.contacts.all.collectAsState()
    val grp = all[g]
    if (grp == null) { NoGroup(); return }
    val s = grp.state
    val admin = s.isAdmin(app.identity.publicKey) && !grp.readOnly
    val people = contacts.filter { !it.groupOnly && !s.has(it.chatKey) && !app.chat.trash.has(it.chatKey) }.sortedBy { it.shown.lowercase() }
    // Whose app can join: ask the ones not known yet (their answers update this list).
    LaunchedEffect(Unit) { people.forEach { app.chat.probeCaps(it.chatKey) } }
    var sent by remember { mutableStateOf(setOf<String>()) }
    var linking by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        PageBar("Add people", "Back to ${s.name}") { ChatNav.open = Folders.groupKey(g) }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (admin) io.github.ravenor907.muninn.ui.Framed("From contacts") {
                if (people.isEmpty()) Text("Everyone is in it already.", style = Type.small, modifier = Modifier.padding(vertical = 10.dp))
                people.forEach { c ->
                    val ready = "g1" in c.caps
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.shown, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!ready && c.chatKey !in sent) Text("Needs an update", style = Type.small)
                        }
                        MButton(if (c.chatKey in sent) "Invited" else "Invite", enabled = c.chatKey !in sent, kind = if (ready) Kind.Accent else Kind.Normal) {
                            val err = app.chat.inviteToGroup(g, c.chatKey)
                            if (err != null) Ui.say(err, true) else sent = sent + c.chatKey
                        }
                    }
                }
            }
            io.github.ravenor907.muninn.ui.Framed("Link or code") {
                if (GroupLinks.canInvite(app, g)) io.github.ravenor907.muninn.ui.NavRow("New invite link", icon = "link") { linking = true }
                if (admin) io.github.ravenor907.muninn.ui.NavRow("Group code", "Works for 1 hour", icon = "qr") {
                    code = app.chat.groups.code(g) ?: run { Ui.say("Only admins can make a group code.", true); null }
                }
            }
            androidx.compose.runtime.key(tick) { InviteLinksSection(app, g, admin) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (linking) InviteLinkDialog(app, g, s.name) { linking = false; tick++ }
    code?.let { c -> GroupCodeDialog(app, g, s, c) { code = null } }
}

@Composable
private fun GroupCodeDialog(app: Muninn, g: String, s: Proto06.State, code: String, done: () -> Unit) {
    val context = LocalContext.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("The group code", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Join ${s.name}. Works for 1 hour.", style = Type.normal)
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { Qr(code, 220.dp) }
                MButton("Share", Modifier.fillMaxWidth(), Kind.Accent, icon = "send") {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Join ${s.name} on Muninn. Open Muninn, tap + › Add someone and paste this (it works for an hour):\n$code"), "Share the group code"))
                }
                MButton("Copy", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "copy") {
                    io.github.ravenor907.muninn.ui.Clip.copy(context, "Muninn group code", code)
                    Ui.say("Group code copied.")
                }
                MButton("Stop this group's codes", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Danger) {
                    done()
                    Ui.sure(Ask("Stop the group codes?", "They stop working at once.", "Stop", danger = true,
                        onConfirm = { app.chat.groups.revokeCodes(g); Ui.say("Those group codes don't work any more.") }))
                }
            }
        },
        confirmButton = { MButton("Done") { done() } },
    )
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun ChannelDialog(app: Muninn, g: String, edit: Proto06.Channel?, done: () -> Unit) {
    var name by remember { mutableStateOf(edit?.n ?: "") }
    var voice by remember { mutableStateOf(edit?.t == "voice") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text(if (edit == null) "Add a channel" else if (edit.t == "voice") "Voice room" else "Channel", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(name, { name = it.take(Proto06.MAX_CHANNEL_NAME) }, "Channel name")
                if (edit == null) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Text", Modifier.weight(1f), if (!voice) Kind.Accent else Kind.Normal) { voice = false }
                    MButton("Voice room", Modifier.weight(1f), if (voice) Kind.Accent else Kind.Normal) { voice = true }
                }
            }
        },
        confirmButton = {
            MButton(if (edit == null) "Add" else "Save", kind = Kind.Accent, enabled = name.isNotBlank()) {
                val err = if (edit == null) app.chat.groups.addChannel(g, name, voice) else app.chat.groups.renameChannel(g, edit.id, name)
                if (err != null) Ui.say(err, true)
                done()
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Cancel") { done() }
                if (edit != null && edit.id != Proto06.MAIN) MButton("Remove", kind = Kind.Danger) {
                    done()
                    Ui.sure(Ask("Remove ${edit.n}?", "Its messages move to the main channel.", "Remove", danger = true,
                        onConfirm = { app.chat.groups.removeChannel(g, edit.id)?.let { Ui.say(it, true) } }))
                }
            }
        },
    )
}

/** On a companion (linked devices): the phone's chats. */
@Composable
private fun MirrorList(back: () -> Unit) {
    BackHandler { back() }
    val chats by io.github.ravenor907.muninn.net.Link.mirror.collectAsState()
    val list = chats.values.sortedByDescending { c -> c.msgs.lastOrNull()?.time ?: 0L }
    Column(Modifier.fillMaxSize()) {
        PageBar("Your phone's chats") { back() }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("While your phone is online.", style = Type.small, modifier = Modifier.weight(1f))
            IconAction("refresh", "Ask the phone again", Palette.textMuted, size = 20.dp) {
                io.github.ravenor907.muninn.net.Link.askSync(); Ui.say("Asking your phone…")
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            if (list.isEmpty()) item { Text("Nothing yet. Is your phone online?", style = Type.normal, modifier = Modifier.padding(16.dp)) }
            items(list, key = { it.peer }) { c ->
                PersonRow(c.name, c.msgs.lastOrNull()?.let { (if (it.mine) "You: " else "") + it.text } ?: "", "", 0, false, {
                    io.github.ravenor907.muninn.ui.Avatar(c.peer, c.name, 48.dp) }) { ChatNav.open = "mirror/" + c.peer }
            }
        }
    }
}

/** On a companion: one of the phone's chats. Text only; photos and the rest show as words and stay on the phone. */
@Composable
private fun MirrorChatScreen(peer: String, back: () -> Unit) {
    BackHandler { back() }
    val chats by io.github.ravenor907.muninn.net.Link.mirror.collectAsState()
    val c = chats[peer]
    var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    val msgs = c?.msgs.orEmpty()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) state.scrollToItem(msgs.size - 1) }
    Column(Modifier.fillMaxSize()) {
        PageBar(c?.name ?: "Chat", "Back to your phone's chats") { back() }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = state) {
            items(msgs, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(m.text, style = Type.normal.copy(color = Palette.text),
                        modifier = Modifier.widthIn(max = 520.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                            .background(if (m.mine) Palette.goldTint else Palette.focusFill).padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Composer(text, { text = it.take(Chat.MAX) }, "Message through your phone") {
            val t = text.trim()
            if (t.isNotEmpty()) {
                if (io.github.ravenor907.muninn.net.Link.sendFromCompanion(peer, t)) text = "" else Ui.say("No phone is linked.", true)
            }
        }
    }
}
