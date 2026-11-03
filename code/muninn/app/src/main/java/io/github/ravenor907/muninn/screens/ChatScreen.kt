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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Drafts
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.ChatOrder
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
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.snapshotFlow
import io.github.ravenor907.muninn.net.Gifs
import io.github.ravenor907.muninn.ui.GifImage
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
/** Groups whose about box is folded to one line (for this run). */
object AboutFold { val folded = androidx.compose.runtime.mutableStateListOf<String>() }

object ChatNav {
    var open by mutableStateOf("")
    /** A code handed in from a scanned QR, waiting on Add someone. */
    var incoming by mutableStateOf("")
    /** Whose three-dot menu is open: a person's key, "g:" and a group, "list" for the chat list, "" for none. */
    var hold by mutableStateOf("")
    /** For the chat list's menu: waiting requests, archived chats and chats in the trash. */
    var asks by mutableStateOf(0)
    var archived by mutableStateOf(0)
    var trashed by mutableStateOf(0)
    /** Set when [hold] was opened from a group's People page: that group's id, for its admin actions. */
    var menuGroup by mutableStateOf("")
    /** Set when [hold] was opened by holding a chat on the main list: only that menu has Move up and Move down. */
    var holdFromList = false
    /** What an opened conversation does first: "search", "media" or "files" (from its menu), "" nothing. */
    var action by mutableStateOf("")
    /** Where Back goes from a person's details or a Kids' TV section. */
    var back by mutableStateOf("")
    /** A group whose "Pick people" call list is open, "" for none. */
    var callSome by mutableStateOf("")
    /** Test builds only: open the conversation's Call popup once it shows. */
    var callPick by mutableStateOf(false)
    /** Who New group starts with picked (Start a group, from a person's menu). */
    var groupWith by mutableStateOf(setOf<String>())
    /** How Add someone starts (from the + menu): "pick" a contact to message, "tv" to pair a Kids' TV, "" the camera. */
    var addMode by mutableStateOf("")
    /** The chat list's "New from" bar: when its X was pressed, or a notification opened a chat (0: never). For this run only. */
    var newFromClosed by mutableStateOf(0L)
    /** The chat list's filter chip: "all", "unread", "groups" or "tv". For this run only. */
    var filter by mutableStateOf("all")
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
/**
 * Once, after the first contact is added: a backup keeps them on a new phone (without one, everyone has to add you
 * again). Back up opens Settings › Backup; either button puts it away for good. Never on a Kids' TV.
 */
@Composable
private fun BackupNudge(first: String?, modifier: Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("muninn", Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(prefs.getBoolean("backupNudged", false)) }
    val kids = remember { (context.applicationContext as? Muninn)?.let { io.github.ravenor907.muninn.tv.KidMode.on(it) } ?: false }
    if (seen || first == null || kids) return
    fun done() { seen = true; prefs.edit().putBoolean("backupNudged", true).apply() }
    Panel(modifier) {
        Text("Back up so a new phone keeps $first and your chats.", style = Type.normal.copy(color = Palette.text))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Back up", Modifier.weight(1f), Kind.Accent) { done(); SettingsNav.goSettings = "backup" }
            MButton("Not now", Modifier.weight(1f)) { done() }
        }
    }
}

/** Under a picked photo: a small "High quality" switch (remembered for next time), then Cancel and a big Send. */
@Composable
private fun PhotoSendRow(enabled: Boolean, onCancel: () -> Unit, onSend: (Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("muninn", Context.MODE_PRIVATE) }
    var hd by remember { mutableStateOf(prefs.getBoolean("photoHd", false)) }
    Row(Modifier.padding(top = 6.dp).clip(io.github.ravenor907.muninn.ui.Corner)
        .clickable(role = Role.Checkbox) { hd = !hd; prefs.edit().putBoolean("photoHd", hd).apply() }
        .semantics { stateDescription = if (hd) "On" else "Off" }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(io.github.ravenor907.muninn.ui.Corner).border(1.5.dp, if (hd) Palette.accent else Palette.line, io.github.ravenor907.muninn.ui.Corner)
            .background(if (hd) Palette.accentFill else Color.Transparent), contentAlignment = Alignment.Center) {
            if (hd) MIcon("check", Palette.accent, 14.dp, weight = 2.4f)
        }
        Text("High quality (bigger file)", style = Type.small.copy(color = Palette.text), modifier = Modifier.padding(start = 8.dp))
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MButton("Cancel", Modifier.weight(1f), enabled = enabled) { onCancel() }
        MButton("Send", Modifier.weight(2f), Kind.Accent, enabled = enabled) { onSend(hd) }
    }
}

/** "Messages delete after 7 days · Keep longer": a quiet line under a chat's top bar; the tap explains the choices. */
@Composable
private fun KeepLine(days: Int, link: String? = null, onTap: () -> Unit) {
    // [link]: the words after the days. By default "Keep longer" (or "Change" at the most); "Why?" where a tap can't
    // change it, only explain.
    val words = link ?: if (days >= io.github.ravenor907.muninn.net.Limits.LONGEST) "Change" else "Keep longer"
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = words) { onTap() }
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon("clock", Palette.textMuted, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text("Messages delete after $days days", style = tinyStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Text(" · $words",
            style = tinyStyle.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/** How messages travel to someone right now, in a few words (Person details). */
internal fun routeShort(r: String) = when (r) {
    "close" -> "Bluetooth"
    "wifi" -> "Same Wi-Fi"
    "internet" -> "Internet"
    else -> "Not reachable"
}

// The "Quiet" chat look. Every size is dp/sp, so Settings › Look size and text scaling apply.
private val nameStyle get() = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.text)
private val previewStyle get() = TextStyle(fontFamily = Type.body, fontSize = 15.sp, color = Palette.textMuted)
private val metaStyle get() = TextStyle(fontFamily = Type.body, fontSize = 13.sp, color = Palette.textMuted)
/** The chat list's + button and its margin (the list leaves room for both under its last row). */
private val PLUS_SIZE = 56.dp
private val PLUS_MARGIN = 16.dp
private val tinyStyle get() =TextStyle(fontFamily = Type.body, fontSize = 12.sp, color = Palette.textMuted)
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
    m.gif -> "GIF"
    m.photo.isNotEmpty() -> "Photo"
    else -> m.text.replace('\n', ' ')
}

/** A call as the chat list's preview. */
private fun callPreview(l: io.github.ravenor907.muninn.net.call.CallManager.Log) = when {
    !l.outgoing && l.result == "missed" -> "Missed call"
    l.result == "answered" -> "Call · ${l.seconds / 60}:${"%02d".format(l.seconds % 60)}"
    l.outgoing -> "Call, no answer"
    else -> "Call"
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
                open.startsWith("files/") -> open.removePrefix("files/")   // a chat's Files: back to the chat
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
        // Cap f2: a chat's or group's Files.
        else if (open.startsWith("files/")) androidx.compose.runtime.key(open) { ChatFilesScreen(app, open.removePrefix("files/")) }
        else if (open.startsWith("tvset/")) androidx.compose.runtime.key(open) {
            val rest = open.removePrefix("tvset/")
            TvSectionPage(app, rest.substringAfter('/'), rest.substringBefore('/')) { ChatNav.open = ChatNav.back }
        }
        else when (open) {
            "" -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MIcon("chat", Palette.textFaint, 40.dp)
                Text(if (ChatNav.space.isNotEmpty()) "Pick a channel on the left." else "Pick a chat on the left.", style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 12.dp))
            }
            "code" -> MyCode(app)
            "add" -> AddSomeone(app)
            "newgroup" -> NewGroup(app)
            "nearby" -> NearbyRoom(app)
            "trash" -> TrashScreen(app) { ChatNav.open = "" }
            "requests" -> RequestsScreen(app) { ChatNav.open = "" }
            "archived" -> ArchivedScreen(app)
            "transfers" -> TransfersScreen(app)
            "tap" -> TapScreen(app) { ChatNav.open = "" }
            "mirror" -> MirrorList()
            "labsreport" -> LabsReportScreen(app)
            "appstorage" -> AppStorageScreen()
            Chat.LABS_PAGE -> LabsReportsScreen(app)
            else -> if (open.startsWith("mirror/")) androidx.compose.runtime.key(open) { MirrorChatScreen(open.removePrefix("mirror/")) { ChatNav.open = "" } }
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
internal fun PersonRow(name: String, preview: String, time: String, unread: Int, dot: Boolean, avatar: @Composable () -> Unit,
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
            // Unread (plan 2.14): the name bold and the preview in full text color; read: normal weight and muted.
            val fresh = unread > 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The name takes all the room up to the time (the mute bell stays right after it).
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = nameStyle.copy(fontWeight = if (fresh) FontWeight.Bold else FontWeight.Normal), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (muted) MIcon("mute", Palette.textMuted, 14.dp, modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = "Muted" })
                }
                if (time.isNotEmpty()) Text(time, style = metaStyle.copy(color = if (fresh) Palette.accent else Palette.textMuted),
                    maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(preview, style = previewStyle.copy(color = if (fresh) Palette.text else Palette.textMuted), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (fresh) Box(Modifier.padding(start = 8.dp).heightIn(min = 20.dp).widthIn(min = 20.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                    .background(Palette.accent).padding(horizontal = 6.dp).semantics { contentDescription = "$unread unread" },
                    contentAlignment = Alignment.Center) {
                    Text(if (unread > 99) "99+" else "$unread", style = tinyStyle.copy(fontWeight = FontWeight.SemiBold, color = Palette.onAccent))
                } else if (dot) Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(Palette.gold)
                    .semantics { contentDescription = "Waiting on you" })
            }
        }
    }
}

@Composable
private fun ChatList(app: Muninn, onAllowNearby: () -> Unit) {
    // A tablet linked to a phone: the phone's chats are one row at the top ("From your phone"); this device's own chats,
    // groups and folders stay on show below it.
    val contacts by app.chat.contacts.all.collectAsState()
    val convos by app.chat.conversations.collectAsState()
    val requests by app.chat.requests.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val wifi by app.net.devices.collectAsState()
    val met by app.chat.met.collectAsState()
    val blocked by app.chat.blocked.collectAsState()
    val room by app.chat.nearby.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    val gunread by app.chat.groups.unreadGroups.collectAsState()
    val ginvites by app.chat.groups.pending.collectAsState()
    val waitingJoins by app.chat.groups.waitingJoins.collectAsState()
    // Linked devices (section 15): which computers or tablets show this phone's chats.
    val mirroredTo by io.github.ravenor907.muninn.net.Link.companions.collectAsState()
    val folders by app.chat.folders.all.collectAsState()
    val topOrder by app.chat.folders.top.collectAsState()
    val trashed by app.chat.trash.all.collectAsState()
    val intros by app.chat.intros.collectAsState()
    val linkedPhone by io.github.ravenor907.muninn.net.Link.phone.collectAsState()
    val flagState by app.chat.flags.all.collectAsState()
    // A TV used normally has a remote, not a touchscreen: no dragging hint.
    val onTv = io.github.ravenor907.muninn.tv.KidMode.isTv(LocalContext.current)
    var query by remember { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<Folders.Folder?>(null) }
    val activeMap by app.chat.active.collectAsState()
    // Everyone reachable right now: close by first, then the same Wi-Fi. (key, name, route)
    val around = buildList {
        wifi.filter { it.chatKey != null }.forEach { add(Triple(it.chatKey!!, it.chatName ?: it.alias, "wifi")) }
    }.distinctBy { it.first }
    // People around who aren't contacts and haven't been chatted with yet.
    val strangers = around.filter { !app.chat.isContact(it.first) && convos[it.first].isNullOrEmpty() && it.first !in blocked }
    // Calls count too: a missed call moves its chat up and shows as its preview.
    val calls by io.github.ravenor907.muninn.net.call.CallManager.history.collectAsState()
    val lastCall = remember(calls) { calls.groupBy { it.peerKey }.mapValues { (_, l) -> l.maxByOrNull { it.at }!! } }
    fun lastTime(r: ChatRow): Long = if (r.group != null) gmsgs[r.group]?.lastOrNull()?.time ?: groups[r.group]?.state?.ts ?: 0L
        else maxOf(convos[r.key]?.lastOrNull()?.time ?: 0L, lastCall[r.key]?.at ?: 0L)
    // Contacts (never group peers) and groups, newest first. Anyone else sits in Nearby, at the very bottom.
    val everyRow = (contacts.filter { !it.groupOnly }.map { ChatRow(it.chatKey, it.shown, it.name) } +
        groups.values.map { ChatRow(Folders.groupKey(it.g), it.state.name, group = it.g) })
        .filter { it.key !in blocked && it.key !in trashed }
        // Delete chat: off the list until a message comes or goes again.
        .filter { it.key !in flagState.cleared || !convos[it.key].isNullOrEmpty() }
        .sortedByDescending { lastTime(it) }
    // Search: a chat's nickname, the name they chose, or its last message.
    val q = query.trim()
    // Archived chats sit in their own section at the bottom (search still finds them).
    val chatRows = if (q.isEmpty()) everyRow.filter { it.key !in flagState.archived } else everyRow
    val archivedRows = everyRow.filter { it.key in flagState.archived }
    fun lastText(r: ChatRow): String? = if (r.group != null) gmsgs[r.group]?.lastOrNull()?.let { groupPreview(app, it) } else convos[r.key]?.lastOrNull()?.let { preview(it) }
    // Something new in it (the same test as the row's count), or marked unread.
    fun isUnread(r: ChatRow): Boolean = (if (r.group != null) r.group in gunread else r.key in unread) || r.key in flagState.unread
    val unreadKeys = chatRows.filter { isUnread(it) }.map { it.key }.toSet()
    // Filter chips under search: All, Unread, Groups, Kids' TV (only with one paired). Not kept between runs.
    val tvs by io.github.ravenor907.muninn.tv.TvParent.tvs.collectAsState()
    val tvKeys = tvs.filter { !it.value.disconnecting }.keys
    val chip = ChatNav.filter.takeIf { it != "tv" || tvKeys.isNotEmpty() } ?: "all"
    // No chips while every chat is archived: their filter goes back to All, so nothing stays hidden with no way back.
    LaunchedEffect(chatRows.isEmpty()) { if (chatRows.isEmpty()) ChatNav.filter = "all" }
    fun chipKeeps(r: ChatRow): Boolean = when (chip) {
        "unread" -> r.key in unreadKeys
        "groups" -> r.group != null
        "tv" -> r.key in tvKeys
        else -> true
    }
    // Pinned chats (from Person details) come first in their section, in the order they were pinned.
    val pins by People.all.collectAsState()
    fun pinFirst(l: List<String>): List<String> = pins.pinned.filter { it in l } + l.filter { it !in pins.pinned }
    val rowTime = chatRows.associate { it.key to lastTime(it) }
    // Unread ones under the pinned ones, newest first (plan 2.14, owner decision D10). Search keeps newest first.
    fun unreadUp(l: List<String>): List<String> = ChatOrder.unreadUp(pinFirst(l), pins.pinned, unreadKeys) { rowTime[it] ?: 0L }
    val shownRows = if (q.isEmpty()) chatRows.filter { chipKeeps(it) }.let { l -> val at = l.associateBy { it.key }; unreadUp(l.map { it.key }).mapNotNull { at[it] } }
        else chatRows.filter { c ->
            (c.name.contains(q, ignoreCase = true) || c.alt.contains(q, ignoreCase = true) || lastText(c)?.contains(q, ignoreCase = true) == true) && chipKeeps(c)
        }
    // The "New from" bar: the unread chats, newest first (chatRows is newest first already).
    val unreadNewest = chatRows.filter { it.key in unreadKeys }
    // Nearby off: no Nearby room and nobody nearby listed.
    val visible = app.identity.visibleNearby
    // Nearby, never mixed in with the chats: anyone who isn't a contact, chatted with or just around, newest first.
    // Not a contact: marked, so a name they chose ("Mom") never looks like one of mine. Hide puts one away for good.
    val nearbyRows = if (!visible) emptyList() else (convos.filter { (k, v) -> v.isNotEmpty() && !app.chat.isContact(k) }.keys + strangers.map { it.first })
        .distinct().filter { it !in blocked && it !in trashed && it !in flagState.hidden }
        .map { k -> (met[k]?.name ?: around.firstOrNull { it.first == k }?.second ?: app.chat.nameOf(k)).let { n -> ChatRow(k, Chat.stranger(n), n) } }
        .sortedByDescending { lastTime(it) }
    val shownNearby = if (q.isEmpty()) nearbyRows else nearbyRows.filter { c ->
        c.alt.contains(q, ignoreCase = true) || lastText(c)?.contains(q, ignoreCase = true) == true
    }
    val roomPreview = if (app.identity.nearbyRoom) "${around.size} around you" else "Off"
    // The Nearby room shows once someone with Muninn is actually around (or it has messages), not as a chat nobody made.
    val showRoom = visible && (around.isNotEmpty() || room.isNotEmpty()) &&
        (q.isEmpty() || "Nearby room".contains(q, ignoreCase = true) || roomPreview.contains(q, ignoreCase = true))
    val nearbyKeys = nearbyRows.map { it.key }.toSet()
    // Folders are sections that fold away (like Discord categories), with the chats in no folder above them.
    // A chat in several folders shows under each. Newest first, except what was moved by hand.
    val byKey = chatRows.associateBy { it.key }
    val newest = chatRows.map { it.key }
    val foldered = folders.flatMap { it.keys }.toSet()
    // Each section: pinned, then unread newest first, then the rest (newest first, except what was moved by hand).
    fun sectionKeys(sec: String): List<String> = unreadUp(if (sec == Folders.NONE) Folders.arrange(newest.filter { it !in foldered }, topOrder)
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
    // A filter chip other than All shows one plain list, like search.
    val sectioned = q.isEmpty() && chip == "all"
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
    // The "New from" bar: names inside the unlocked app; over the lock screen only if notifications may name people.
    val overLock = LocalContext.current.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    val newestUnread = unreadNewest.firstOrNull()?.let { rowTime[it.key] } ?: 0L
    val barText = ChatOrder.newFrom(unreadNewest.map { it.name }, ChatOrder.namesShown(overLock, app.identity.showNames))
    val showBar = sectioned && barText.isNotEmpty() && ChatOrder.barShown(newestUnread, ChatNav.newFromClosed)
    val scope = rememberCoroutineScope()
    // The list's place of the first unread chat (unread ones sit at the top of their section), or a folded folder holding one.
    // Counts the items the same way the list below adds them.
    fun firstUnreadItem(): Int {
        var i = waitingJoins.size + 1 + (if (linkedPhone?.ok == true) 1 else 0) + (if (folders.isNotEmpty()) 1 else 0)
        val none = sectionKeys(Folders.NONE).filter { byKey.containsKey(it) }
        none.indexOfFirst { it in unreadKeys }.let { if (it >= 0) return i + it }
        i += none.size
        for (f in folders) {
            val keys = sectionKeys(f.id)
            if (f.collapsed) { if (keys.any { it in unreadKeys }) return i; i++; continue }
            i++
            val rows = keys.filter { byKey.containsKey(it) }
            rows.indexOfFirst { it in unreadKeys }.let { if (it >= 0) return i + it }
            i += rows.size + (if (keys.isEmpty()) 1 else 0)
        }
        return 0
    }
    // Nearby shows with All only.
    val nearbyOn = chip == "all"

    // One chat on the list. [sec]: the section it's under ("" no folder), null in search results (nothing to move there).
    val chatRow: @Composable (ChatRow, String?, String) -> Unit = { c, sec, item ->
        val lifted = dragged?.item == item
        // First setup's tour points at the first chat.
        val rowMod = Modifier.then(if (c.key == shownRows.firstOrNull()?.key) Modifier.tourSpot("row") else Modifier).then(if (lifted) Modifier.zIndex(1f).graphicsLayer { translationY = dragBy }.background(Palette.panel) else Modifier)
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
                onLong = { ChatNav.menuGroup = ""; ChatNav.holdFromList = true; ChatNav.hold = c.key }, modifier = rowMod, onDrag = onDrag, onDrop = onDrop) { ChatNav.openGroup(c.group) }
        } else {
            val msgs = convos[c.key].orEmpty()
            val last = msgs.lastOrNull()
            val call = lastCall[c.key]?.takeIf { it.at > (last?.time ?: 0L) }
            PersonRow(c.name, call?.let { callPreview(it) } ?: last?.let { preview(it) }
                    ?: if (app.chat.isContact(c.key)) "Say hi to ${c.name}" else routeText(app.chat.route(c.key)),
                call?.let { when_(it.at) } ?: last?.let { when_(it.time) } ?: "",
                if (c.key in unread) unreadCount(msgs) else if (c.key in flagState.unread) 1 else 0, app.chat.shouldAskKeep(c.key), { Avatar(c.key, c.name, 48.dp, active = c.key in activeMap) },
                muted = (flagState.muted[c.key] ?: 0L) > System.currentTimeMillis(),
                onLong = { ChatNav.menuGroup = ""; ChatNav.holdFromList = true; ChatNav.hold = c.key }, modifier = rowMod, onDrag = onDrag, onDrop = onDrop) {
                    // Someone around with no chat yet: met now, so the chat knows who they are.
                    if (c.key in nearbyKeys && msgs.isEmpty()) app.chat.meet(c.key, c.alt)
                    ChatNav.open = c.key
                }
        }
    }

    Box(Modifier.fillMaxSize().frosted()) {
        // Room under the last row for the + button: its size and both margins, plus a small gap.
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = PLUS_SIZE + PLUS_MARGIN * 2 + 8.dp)) {
            // A join by a code or link: the admin's app lets me in when it's next online.
            items(waitingJoins, key = { "w|" + it.g }) { w ->
                Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    // Cap g4: the admin's app couldn't let me in, and says why.
                    if (w.refused.isNotEmpty()) {
                        Text("Not let in to ${w.name.ifBlank { "the group" }}", style = Type.big)
                        Text(Groups.refusalText(w.refused, w.name.ifBlank { "The group" }) + " To try again, ask ${w.inviter.ifBlank { "them" }} for a new invite.", style = Type.small)
                        MButton("Remove", Modifier.padding(top = 8.dp)) { app.chat.groups.stopWaiting(w.g) }
                    } else {
                        Text("Joining ${w.name.ifBlank { "a group" }}", style = Type.big)
                        Text("Waiting for ${w.inviter.ifBlank { "the person who invited you" }} to let you in. Their app does it when it's next open.", style = Type.small)
                        MButton("Stop waiting", Modifier.padding(top = 8.dp)) { app.chat.groups.stopWaiting(w.g) }
                    }
                }
            }
            item {
                // Search, and the chat list's three-dot menu beside it.
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchBox(query, Modifier.weight(1f)) { query = it }
                    Spacer(Modifier.width(4.dp))
                    Box(Modifier.tourSpot("menu")) {
                        io.github.ravenor907.muninn.ui.DotsButton(if (ChatNav.asks > 0) "Chat list menu, ${ChatNav.asks} requests" else "Chat list menu") { ChatNav.menuGroup = ""; ChatNav.hold = "list" }
                        if (ChatNav.asks > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(10.dp).clip(CircleShape).background(Palette.accent))
                    }
                }
                // Filter chips: All, Unread, Groups, and Kids' TV once one is paired.
                if (chatRows.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("all" to "All", "unread" to "Unread", "groups" to "Groups", "tv" to "Kids' Devices").filter { it.first != "tv" || tvKeys.isNotEmpty() }
                        .forEach { (id, label) -> Chip(label, chip == id) { ChatNav.filter = id } }
                }
                // Something waiting (a group invite, someone wanting to chat, an introduction): one row at the top, so it's never missed.
                val firstAsk = ginvites.firstOrNull()?.let { "${app.chat.contacts.get(it.from)?.shown ?: it.fromName} invited you to ${it.name}" }
                    ?: requests.firstOrNull()?.let { "${it.name} wants to chat" }
                val waitingAsks = requests.size + ginvites.size + intros.count { !it.iSaid && !it.declined } + app.chat.familyOffers().size
                if (waitingAsks > 0) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                    .background(Palette.goldTint).clickable(role = Role.Button) { ChatNav.open = "requests" }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MIcon("people", Palette.goldBright, 20.dp)
                    Text((firstAsk ?: "Someone wants to be in touch") + if (waitingAsks > 1) " (+${waitingAsks - 1} more)" else "",
                        style = Type.normal.copy(color = Palette.text), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text("See", style = Type.normal.copy(color = Palette.goldBright, fontWeight = FontWeight.SemiBold))
                }
                // Permissions still missing, the message limits note and mirrored chats: one row, one sheet.
                FinishRow(app, onAllowNearby, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                BackupNudge(contacts.firstOrNull { !it.groupOnly }?.shown, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                mirroredTo.filter { it.ok }.takeIf { it.isNotEmpty() }?.let { on ->
                    // Tap: Settings › Linked devices.
                    Text("Mirrored to ${on.joinToString(", ") { it.name }} ›", style = Type.small,
                        modifier = Modifier.clickable { SettingsNav.goSettings = "link" }.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                }
                if (app.power.extreme) Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    ExtremeBanner("Battery") { SettingsNav.goSettings = "battery" }
                }
                // "New from Mom, Taylor +3" (plan 2.14): one unread chat opens it, several scroll to them at the top.
                // X hides it until the next new message.
                if (showBar) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                    .background(Palette.accentFill).clickable(role = Role.Button) {
                        val one = unreadNewest.singleOrNull()
                        if (one == null) scope.launch { listState.animateScrollToItem(firstUnreadItem()) }
                        else if (one.group != null) ChatNav.openGroup(one.group)
                        else ChatNav.open = one.key
                    }.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MIcon("chat", Palette.accent, 18.dp)
                    Text(barText, style = Type.normal.copy(color = Palette.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { ChatNav.newFromClosed = newestUnread }
                        .semantics { contentDescription = "Hide until the next message" }, contentAlignment = Alignment.Center) {
                        MIcon("close", Palette.textMuted, 18.dp)
                    }
                }
            }
            // A linked tablet: the phone's chats, one row at the top.
            linkedPhone?.takeIf { it.ok }?.let { ph -> item(key = "mirror") {
                Column {
                    ListLabel("From your phone")
                    PersonRow("${ph.name}'s chats", "Open your phone's chats", "", 0, false, {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
                            MIcon("phone", Palette.goldBright, 22.dp) } }) { ChatNav.open = "mirror" }
                }
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
                Text(if (archivedRows.isNotEmpty()) "All your chats are archived. They're in Archived, in the menu beside Search."
                    else "No chats yet. Tap + to add someone.", style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (shownRows.isEmpty() && q.isNotEmpty()) item {
                Text("No chats match “$q”.", style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            } else if (shownRows.isEmpty() && !sectioned) item {
                Text(when (chip) { "unread" -> "No unread chats."; "groups" -> "No groups yet."; else -> "No Kids' Device chats." },
                    style = Type.normal, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            // Nearby, in a small section of its own at the very bottom, below every chat and folder.
            if (nearbyOn && (showRoom || shownNearby.isNotEmpty())) item(key = "nearby") {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp).height(1.dp).background(Palette.hairline))
                ListLabel("Nearby")
            }
            if (nearbyOn && showRoom) item(key = "room") {
                PersonRow("Nearby room", roomPreview, room.lastOrNull()?.let { when_(it.time) } ?: "", 0, false, { RoomAvatar(48.dp) }) { ChatNav.open = "nearby" }
            }
            // Hold one (or its menu) for Hide, Block, Mute and Delete chat.
            if (nearbyOn) items(shownNearby, key = { "n|" + it.key }) { c -> chatRow(c, null, "n|" + c.key) }
            // Requests, archived chats and the trash live in the chat list's menu (the dots beside Search).
            val pendingIntros = intros.count { !it.iSaid && !it.declined } + app.chat.familyOffers().size
            ChatNav.asks = requests.size + ginvites.size + pendingIntros
            ChatNav.archived = archivedRows.size
            ChatNav.trashed = trashed.size
        }
        // The + button, bottom left above the tabs: New chat, New group, Add contact, Pair a Kids' TV.
        var plusOpen by remember { mutableStateOf(false) }
        Box(Modifier.align(Alignment.BottomStart).padding(PLUS_MARGIN)) {
            Box(Modifier.size(PLUS_SIZE).tourSpot("plus").clip(CircleShape).background(Palette.gold)
                .clickable(role = Role.Button) { plusOpen = true }.semantics { contentDescription = "New chat, new group, add contact or pair a Kids' Device" },
                contentAlignment = Alignment.Center) { MIcon("plus", Palette.onGold, 26.dp) }
            DropdownMenu(expanded = plusOpen, onDismissRequest = { plusOpen = false }) {
                val anyone = contacts.any { !it.groupOnly }
                listOf(Triple("chat", "New chat", if (anyone) "pick" else ""), Triple("people", "New group", "group"),
                    Triple("qr", "Add contact", ""), Triple("tv", "Pair a Kids' Device", "tv")).forEach { (icon, label, how) ->
                    DropdownMenuItem(text = { Text(label, style = Type.normal.copy(color = Palette.text), maxLines = 1) },
                        leadingIcon = { MIcon(icon, Palette.accent, 20.dp) },
                        onClick = {
                            plusOpen = false
                            if (how == "group") ChatNav.open = "newgroup"
                            else { ChatNav.addMode = how; ChatNav.open = "add" }
                        })
                }
            }
        }
    }
    FolderDialogs(app, editingFolder) { editingFolder = null }
}

/**
 * Move up and Move down for [key] on the chat list, in the section it shows in (its first folder, or none): each null
 * where it can't go. The same order the list shows: pinned first, then unread newest first, then what was moved by hand, then newest.
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
    // The same rows as the list: contacts and groups (anyone else is in Nearby, which has no order to change).
    val keys = (ch.contacts.all.value.filter { !it.groupOnly }.map { it.chatKey } + groups.keys.map { Folders.groupKey(it) }).distinct()
        .filter { it !in blocked && it !in trashed && it !in flags.archived && (it !in flags.cleared || !convos[it].isNullOrEmpty()) }.sortedByDescending { last(it) }
    val folders = ch.folders.all.value
    val f = folders.firstOrNull { key in it.keys }
    val sec = f?.id ?: Folders.NONE
    val foldered = folders.flatMap { it.keys }.toSet()
    val raw = if (f == null) Folders.arrange(keys.filter { it !in foldered }, ch.folders.top.value) else Folders.arrange(keys.filter { it in f.keys }, f.keys)
    // Unread ones under the pinned ones, newest first, as the list shows them.
    val unread = raw.filter { k -> k in flags.unread || (if (k.startsWith("g:")) k.removePrefix("g:") in ch.groups.unreadGroups.value else k in ch.unread.value) }.toSet()
    val l = ChatOrder.unreadUp(flags.pinned.filter { it in raw } + raw.filter { it !in flags.pinned }, flags.pinned, unread) { last(it) }
    val i = l.indexOf(key)
    if (i < 0) return Pair(null, null)
    val up: (() -> Unit)? = if (i > 0) ({ ch.folders.order(sec, Folders.shift(l, key, -1)) }) else null
    val down: (() -> Unit)? = if (i < l.size - 1) ({ ch.folders.order(sec, Folders.shift(l, key, 1)) }) else null
    return Pair(up, down)
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
 * A conversation's slim top bar: back, avatar, name and one short status line, then [actions] (at most the Call
 * button, so the name keeps its room) and the three dots. Tapping the name or the dots opens the chat's menu
 * ([onMenu]); null: no menu.
 */
@Composable
private fun ThreadBar(name: String, status: String, statusIcon: String, statusTint: Color, avatar: @Composable () -> Unit,
                      compact: Boolean = false, actions: @Composable () -> Unit = {}, onMenu: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = if (compact) 0.dp else 6.dp, bottom = if (compact) 0.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Tablets keep the chat list beside the conversation, so there's nothing to go back to.
            if (!ChatNav.wide) IconAction("back", "Back to chats", Palette.text, size = 24.dp) { ChatNav.open = "" }
            else Spacer(Modifier.width(12.dp))
            Row(Modifier.weight(1f).heightIn(min = if (compact) 44.dp else 52.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                .then(if (onMenu != null) Modifier.clickable(role = Role.Button, onClickLabel = "Menu") { onMenu() } else Modifier).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                avatar()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(if (hidden) "Nearby is off." else "Nearby chat is off.", style = Type.normal)
                MButton(if (hidden) "Turn on Nearby" else "Turn on Nearby chat", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) {
                    app.identity.visibleNearby = true; app.identity.nearbyChat = true; on = true; app.net.nearbyChanged(); app.ble.hiddenChanged()
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
        Composer(text, { text = it }, "Message everyone nearby") { sendLong(text, { text = "" }) { p, _ -> app.chat.sendNearby(p) } }
    }
}

@Composable
private fun Conversation(app: Muninn, key: String) {
    // Calls start faster when the relay login is already here.
    LaunchedEffect(key) { io.github.ravenor907.muninn.net.call.CallManager.prepare(key) }
    // Only this chat's messages: a message in another chat doesn't redraw the open one.
    val msgs by remember(key) { app.chat.messagesOf(key) }.collectAsState(app.chat.conversations.value[key].orEmpty())
    val c = app.chat.contacts.get(key)
    val met by app.chat.met.collectAsState()
    @Suppress("UNUSED_VARIABLE") val everyone by app.chat.contacts.all.collectAsState()   // redraw when they become a contact
    val m = met[key]
    val name = c?.shown ?: m?.name ?: app.chat.nameOf(key)
    // What their app speaks isn't known yet: ask once (protocol 0.6, 1.2).
    LaunchedEffect(key) { app.chat.probeCaps(key); app.chat.pruneSoon() }
    // A half-typed message, and what it answers or edits, are kept per chat (Drafts): leaving, switching, folding or a restart keeps them.
    val draft0 = remember(key) { Drafts.get(app, key) }
    var text by remember { mutableStateOf(draft0.text) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.lastOrNull()?.id) { app.chat.markRead(key); if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    // Redraw as they come into or go out of range (route, and when to ask "Keep in touch?").
    @Suppress("UNUSED_VARIABLE") val closeNow by app.ble.peers.collectAsState()
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
    // Photos picked and waiting for Send stay with the chat too (in memory only).
    val picked0 = remember(key) { Drafts.picked[key].orEmpty().map { Uri.parse(it) } }
    var picked by remember { mutableStateOf(picked0.singleOrNull()) }
    var preparing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Chat.Msg?>(null) }
    // Cap m2: several photos picked at once, the "+" menu, the camera ("photo" or "video"), a new poll, the Shared page.
    var pickedMany by remember { mutableStateOf(if (picked0.size > 1) picked0 else emptyList()) }
    // Cap a1: GIFs waiting to be sent, one panel at a time (picked, shared in or over the limits).
    var gifs by remember { mutableStateOf<List<PendingGif>>(emptyList()) }
    var attaching by remember { mutableStateOf(false) }
    // The camera open, and what it took waiting for Send, stay with the chat (in memory only, Drafts.camera).
    var camera by remember { mutableStateOf(Drafts.camera[key]?.mode.orEmpty()) }
    var polling by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    var sharedFiles by remember { mutableStateOf(false) }
    // Cap m1: the message being answered or edited, the one held for its sheet, search and the photos page.
    var replyTo by remember { mutableStateOf(draft0.re.takeIf { it.isNotEmpty() }?.let { re -> msgs.firstOrNull { it.id == re } }) }
    var editing by remember { mutableStateOf(draft0.edit.takeIf { it.isNotEmpty() }?.let { ed -> msgs.firstOrNull { it.id == ed } }) }
    androidx.compose.runtime.SideEffect {
        Drafts.put(key, Drafts.Draft(text, replyTo?.id.orEmpty(), editing?.id.orEmpty()))
        val waiting = picked?.let { listOf(it.toString()) } ?: pickedMany.map { it.toString() }
        if (waiting.isEmpty()) Drafts.picked.remove(key) else Drafts.picked[key] = waiting
    }
    DisposableEffect(key) { onDispose { Drafts.save(app) } }
    var sheetFor by remember { mutableStateOf<Chat.Msg?>(null) }
    // A search open here stays too (in memory only), like the draft: the lock coming up or a fold keeps it.
    var searching by remember { mutableStateOf(Drafts.search.containsKey(key)) }
    var query by remember { mutableStateOf(Drafts.search[key].orEmpty()) }
    var matchAt by remember { mutableStateOf(0) }
    androidx.compose.runtime.SideEffect { keepOpen(key, searching, query, camera) }
    val typingMap by app.chat.typing.collectAsState()
    val activeMap by app.chat.active.collectAsState()
    val typingNow = key in typingMap
    val activeNow = key in activeMap
    val me = app.identity.publicKey
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(io.github.ravenor907.muninn.net.More.ALBUM_MAX)) { uris ->
        // GIFs go on their own, animated; the rest as a photo or an album.
        if (uris.isNotEmpty()) scope.launch {
            val anim = withContext(Dispatchers.IO) { uris.filter { Photos.isGif(context, it) } }
            val rest = uris.filterNot { it in anim }
            gifs = gifs + anim.map { PendingGif(it) }
            if (rest.size == 1) picked = rest[0] else if (rest.size > 1) pickedMany = rest
        }
    }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> micAnswer(context, ok) }
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
    /** Cap a1: a GIF, animated. Over the limits, its panel offers a still or a file instead. */
    fun sendGif(p: PendingGif) {
        preparing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Photos.readGif(context, p.uri) }
            when {
                r == null -> { Ui.say("That GIF couldn't be opened.", true); gifs = gifs - p; p.done() }
                r.bytes != null -> { withContext(Dispatchers.IO) { app.chat.sendGif(key, r.bytes) }?.let { Ui.say(it, true) }; gifs = gifs - p; p.done() }
                else -> gifs = gifs.map { if (it === p) PendingGif(p.uri, Gifs.why(r), p.shared) else it }
            }
            preparing = false
        }
    }
    fun sendPollHere(poll: io.github.ravenor907.muninn.net.More.Poll) {
        app.chat.sendPoll(key, poll.q, poll.options, poll.multi)?.let { Ui.say(it, true) }
    }
    // A file over the internet: straight to their phone (or over the internet route when small). Its card shows in
    // the chat at once, while the file is still being copied in, and says how it goes from there.
    fun sendAsFile(uri: Uri, fallbackName: String = "file", then: () -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            var chosenName: String? = null
            var size = 0L
            try {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { chosenName = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
                }
            } catch (_: Exception) {}
            val clean = app.chat.cleanFileName(chosenName ?: fallbackName)
            val card = io.github.ravenor907.muninn.net.FileLink.preparing(key, clean, size)
            val tmp = java.io.File(java.io.File(context.cacheDir, "files").also { it.mkdirs() }, "out-" + System.nanoTime())
            // The file is copied in first: with too little room on the phone, say that, not "couldn't be opened".
            val room = try { tmp.parentFile?.usableSpace ?: Long.MAX_VALUE } catch (_: Exception) { Long.MAX_VALUE }
            val full = size > 0 && room < size + 32L * 1024 * 1024
            val ok = !full && try { context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } != null } catch (e: Exception) { false }
            val why = if (full || (!ok && (try { tmp.parentFile?.usableSpace ?: Long.MAX_VALUE } catch (_: Exception) { Long.MAX_VALUE }) < 4L * 1024 * 1024))
                "Not enough space on this phone. Free some up, then try again." else "That file couldn't be opened."
            if (!ok) { tmp.delete(); io.github.ravenor907.muninn.net.FileLink.failed(key, clean, size, why, card) }
            else app.chat.sendFile(key, tmp, clean, card)?.let { Ui.say(it, true) }
            withContext(Dispatchers.Main) { then() }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) sendAsFile(uri) }
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
        when (action) { "search" -> { searching = true; matchAt = 0 }; "media" -> { sharedFiles = false; shared = true }; "files" -> { sharedFiles = true; shared = true } }
        if (action.isNotEmpty()) ChatNav.action = ""
    }
    // Show in chat (from Media and files or the viewer): the message is scrolled to and lit up for a moment.
    var flash by remember { mutableStateOf("") }
    val mediaActs = MediaActions(io.github.ravenor907.muninn.net.Sharing.folderFor("Muninn", name),
        show = { m ->
            // An album's photo shows in its first photo's place.
            val head = albumsOf(msgs).second.entries.firstOrNull { e -> e.value.any { it.id == m.id } }?.key ?: m.id
            viewing = null; shared = false; flash = head
        },
        delete = { m -> app.chat.deleteMessage(key, m.id) })
    viewing?.let { v ->
        BackHandler { viewing = null }
        MediaViewer(app, v, mediaActs) { viewing = null }
        return
    }
    if (camera.isNotEmpty()) {
        CameraScreen(camera == "video", keep = key, onPhoto = { jpeg -> scope.launch(Dispatchers.IO) { app.chat.sendPhoto(key, jpeg) } },
            onVideo = { mp4, ms -> scope.launch(Dispatchers.IO) { app.chat.sendVideo(key, mp4, ms)?.let { Ui.say(it, true) } } }) { camera = "" }
        return
    }
    if (shared) {
        SharedMedia("Media and files · $name", msgs, { app.chat.photo(it.photo) }, { viewing = it },
            files = { mod -> ChatFilesList(app, key, mod, inChat = { f ->
                // A file that came or went as a message can be shown there; one sent straight has no message.
                msgs.lastOrNull { it.file == f.name && it.mine == f.mine }?.let { m -> { mediaActs.show(m) } }
            }) }, startFiles = sharedFiles, actions = mediaActs) { shared = false }
        return
    }
    LaunchedEffect(flash) {
        if (flash.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(150)   // the list is back first
        val at = msgs.indexOfFirst { it.id == flash }
        if (at < 0) { Ui.say("That message isn't here any more."); flash = ""; return@LaunchedEffect }
        if (Access.reduceMotion) list.scrollToItem(at) else list.animateScrollToItem(at)
        kotlinx.coroutines.delay(2500)
        flash = ""
    }
    if (searching) BackHandler { searching = false; query = "" }
    if (replyTo != null || editing != null) BackHandler { replyTo = null; if (editing != null) { editing = null; text = "" } }
    val matches = if (!searching || query.isBlank()) emptyList()
        else msgs.indices.filter { msgs[it].unsent.isEmpty() && msgs[it].text.contains(query.trim(), ignoreCase = true) }.reversed()
    LaunchedEffect(matchAt, matches.size, query) { matches.getOrNull(matchAt)?.let { list.animateScrollToItem(it) } }
    // Like any messenger: typing, Active now, or nothing. A contact's chat says how long messages stay in the line
    // under the bar (KeepLine), so the bar doesn't say it again; someone just met has no such line, so it says it here.
    // Muted shows first, as Huginn's header does. How messages travel is in Person details.
    val keepDays = app.chat.chatDays(key)
    val chatFlags by app.chat.flags.all.collectAsState()
    val mutedNow = (chatFlags.muted[key] ?: 0L) > System.currentTimeMillis()
    val said = when {
        typingNow -> "typing…"
        activeNow -> if (c != null) "Active now" else "Active now · deletes after $keepDays days"
        c == null -> "Messages delete after $keepDays days"
        else -> ""
    }
    val status = if (mutedNow && !typingNow) (if (said.isEmpty()) "Muted" else "Muted · $said") else said
    androidx.compose.runtime.CompositionLocalProvider(LocalMineBubble provides mineTint(app, key)) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    // Sideways with the keyboard up there's little height left: the bar shrinks to one line and the extra lines
    // under it wait, so the list and the box to type in always fit. Only while the keyboard is up (split screen or a
    // short phone without it keeps every line), and the key-change warning never waits: it shrinks to one line.
    val imeUp = WindowInsets.isImeVisible
    val tight = imeUp && maxHeight < 320.dp
    Column(Modifier.fillMaxSize()) {
        ThreadBar(name, if (tight) "" else status, if (typingNow) "chat" else "shield", Palette.online, { Avatar(key, name, 40.dp, active = activeNow) }, compact = tight, actions = {
            // One Call button; Mute is in the menu. Someone met close by can be called too while on the same Wi-Fi.
            if (c != null || io.github.ravenor907.muninn.net.call.CallManager.canCall(key)) {
                val callable = io.github.ravenor907.muninn.net.call.CallManager.canCall(key)
                // Video calls: both apps must speak c2 (shared/docs/protocol.md 5.8.5). A Kids' TV has no video, so only Voice call.
                val video = callable && io.github.ravenor907.muninn.net.call.CallManager.canVideo(key)
                val choices = if (!callable) emptyList() else listOfNotNull(
                    CallChoice("call", "Voice call") { io.github.ravenor907.muninn.screens.CallActivity.call(context, key) },
                    if (video) CallChoice("video", "Video call") { io.github.ravenor907.muninn.screens.CallActivity.call(context, key, video = true) } else null)
                CallButton(if (callable) "Call $name" else "Calling needs an update", choices, "$name needs an update for calls.")
            }
        }, onMenu = { ChatNav.menuGroup = ""; ChatNav.hold = key })
        if (searching) ChatSearchBar(query, { query = it; matchAt = 0 }, matches.size, matchAt,
            onPrev = { if (matches.isNotEmpty()) matchAt = (matchAt + 1) % matches.size },
            onNext = { if (matches.isNotEmpty()) matchAt = (matchAt - 1 + matches.size) % matches.size }) { searching = false; query = "" }
        // How long messages stay, with one tap to Person details to keep them longer (a contact's chat; someone just met keeps 7 days).
        if (c != null && !searching && !tight) KeepLine(keepDays) { openDetails(key) }
        // Their security key changed: say so until the code has been checked.
        if (c?.keyChanged == true) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = if (tight) 2.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(io.github.ravenor907.muninn.net.ChatWire.keyChangedText(name), style = Type.small.copy(color = Palette.danger), modifier = Modifier.weight(1f),
                maxLines = if (tight) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
            MButton("Compare emoji") { checkEmoji(app, key, name) }
        }
        if (!tight && c == null && m != null && app.chat.shouldAskKeep(key)) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text(if (m.theySaid) "$name wants to keep in touch" else "Keep in touch with $name?", style = Type.big)
            Text("Then chat from anywhere.", style = Type.small)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Yes", Modifier.weight(1f), Kind.Accent) { app.chat.keepInTouch(key) }
                MButton("Not now", Modifier.weight(1f)) { app.chat.notNow(key) }
            }
        } else if (!tight && c == null && m?.iSaid == true) Text("Waiting for $name to say yes.", style = Type.small,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp))
        // A call with them going on: back to it.
        if (!tight) callNow?.takeIf { it.peerKey == key && it.phase != io.github.ravenor907.muninn.net.call.CallSession.Phase.ENDED }?.let {
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
                        quote = quoteOf(m.re), onQuote = { jumpTo(m.re) }, me = me, highlight = current == i || m.id == flash,
                        onReply = if (m.failed) null else ({ editing = null; replyTo = m }), onReactions = { sheetFor = m },
                        onTap = { if (m.mine && m.failed && m.unsent.isEmpty()) app.chat.retry(key, m.id) else if (m.file.isEmpty()) viewing = m }, album = albums.second[m.id], albumBytes = { app.chat.photo(it.photo) }, onAlbumTap = { viewing = it },
                        onVote = { v -> app.chat.vote(key, m.id, v)?.let { Ui.say(it, true) } }, voterName = { if (it == key) name else app.chat.nameOf(it) },
                        folder = io.github.ravenor907.muninn.net.Sharing.folderFor("Muninn", name)) { sheetFor = m }
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
        if (msgs.isEmpty() && calls.isEmpty() && !tight) Text("No messages yet.", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
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
                // Send is the big button; High quality is a small switch that remembers the last choice.
                PhotoSendRow(!preparing, onCancel = { picked = null }) { hd -> sendPhoto(uri, hd) }
            }
        }
        GifShare.pending?.let { u -> LaunchedEffect(u) { GifShare.pending = null; gifs = gifs + PendingGif(u, shared = true) } }
        gifs.firstOrNull()?.let { p ->
            GifPanel(p, name, preparing, onSend = { sendGif(p) }, onStill = {
                gifs = gifs - p
                sendPhoto(p.uri, false)   // a shared copy stays until the next share clears it
            }, onFile = { gifs = gifs - p; sendAsFile(p.uri, "animation.gif") { p.done() } }) { gifs = gifs - p; p.done() }
        }
        if (attaching) AttachDialog(onPhotos = { io.github.ravenor907.muninn.ui.Picking.launch(pick, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = { camera = "photo" }, onVideo = { camera = "video" }, onPoll = { polling = true },
            onFile = { try { io.github.ravenor907.muninn.ui.Picking.launch(pickFile, arrayOf("*/*")) } catch (_: Exception) { Ui.say("This phone has no file picker.", true) } }) { attaching = false }
        DirectFiles(key, name) { p, file, n -> scope.launch(Dispatchers.IO) { app.chat.sendFile(p, file, n)?.let { Ui.say(it, true) } } }
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
        }, onImage = { uri -> scope.launch {
            // From the keyboard (a GIF or sticker picked in Gboard): a GIF goes at once, a picture asks like a picked photo.
            if (withContext(Dispatchers.IO) { Photos.isGif(context, uri) }) sendGif(PendingGif(uri).also { gifs = gifs + it }) else picked = uri
        } }, voice = if (editing != null) null else VoiceHooks(
            allowed = { androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            ask = { micAsk.launch(android.Manifest.permission.RECORD_AUDIO) },
            send = { aac, ms -> scope.launch(Dispatchers.IO) { app.chat.sendVoice(key, aac, ms) } },
        ), banner = banner, tight = tight) {
            val e = editing
            if (e != null) {
                if (!editFits(text)) return@Composer
                app.chat.editMessage(key, e.id, text)?.let { Ui.say(it, true) }; editing = null; text = ""
            } else {
                val re = replyTo?.id ?: ""
                sendLong(text, { replyTo = null; text = "" }) { p, first -> app.chat.sendPrivate(key, p, if (first) re else "") }
            }
        }
    }
    }
    }
    sheetFor?.let { s ->
        val actions = buildList {
            if (s.unsent.isEmpty() && !s.failed) add(SheetAction("Reply", "reply") { editing = null; replyTo = s })
            if (s.unsent.isEmpty() && s.text.isNotEmpty()) add(SheetAction("Copy", "copy") {
                io.github.ravenor907.muninn.ui.Clip.copy(context, "Message", s.text)
                Ui.say("Copied.")
            })
            if (s.mine && s.unsent.isEmpty() && !s.failed && s.photo.isEmpty() && s.text.isNotEmpty())
                add(SheetAction("Edit", "edit") {
                    if (!app.chat.hasM1(key)) Ui.say("$name needs an update for edits.", true)
                    else { replyTo = null; editing = s; text = s.text }
                })
            add(SheetAction((if (s.mine) "Sent " else "Received ") + clock(s.time), "clock", folder = "Info", info = true))
            if (s.mine && s.unsent.isEmpty() && !s.failed) io.github.ravenor907.muninn.net.Ticks.private(s.state, name).takeIf { it.isNotEmpty() }
                ?.let { add(SheetAction(it, "check", folder = "Info", info = true)) }
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
                Ui.sure(Ask("Delete this message?", deletedHereOnly(), "Delete", danger = true, onConfirm = { app.chat.deleteMessage(key, s.id) }))
            })
        }
        MessageSheet(s.reactions[me]?.e?.takeIf { it.isNotEmpty() }, s.unsent.isEmpty() && !s.failed, actions,
            onReact = { e -> app.chat.react(key, s.id, e)?.let { Ui.say(it) } }) { sheetFor = null }
    }
}

private class VoiceHooks(val allowed: () -> Boolean, val ask: () -> Unit, val send: (ByteArray, Int) -> Unit)

/** The microphone asked for: ready, or why not. Refused for good (Android won't ask again): a way to Muninn's app settings. */
private fun micAnswer(context: android.content.Context, ok: Boolean) {
    if (ok) { Ui.say("Ready. Hold the mic to record."); return }
    val act = context as? android.app.Activity
    if (act != null && !act.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO))
        Ui.sure(Ask("Allow the microphone?", "Android won't ask again. To send voice messages, allow the microphone in Muninn's settings.",
            "Open settings", cancel = "Not now", onConfirm = {
                try {
                    context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)))
                } catch (_: Exception) { Ui.say("Open Settings › Apps › Muninn › Permissions to allow the microphone.", true) }
            }))
    else Ui.say("Needs the microphone.", true)
}

/**
 * Sends [text], never cut: longer than one message holds ([Chat.MAX]), it asks first and goes as up to [Drafts.MAX_PARTS]
 * messages, split at line breaks or spaces; longer still, it says so and the text stays. [send] gets each part and
 * whether it's the first (only the first answers a message). [done] clears the box once it's sent.
 */
internal fun sendLong(text: String, done: () -> Unit, send: (String, Boolean) -> Unit) {
    val parts = Drafts.split(text, Chat.MAX)
    when {
        parts.isEmpty() -> done()
        parts.size == 1 -> { send(parts[0], true); done() }
        parts.size > Drafts.MAX_PARTS -> Ui.say("That's too long to send as messages. Send it as a file instead.", true)
        else -> Ui.sure(Ask("Send as ${parts.size} messages?",
            "One message holds up to ${Chat.MAX} characters, so this goes in ${parts.size} parts, split at line breaks or spaces. Nothing is cut.",
            "Send ${parts.size}", onConfirm = {
                // A moment apart, so they keep their order everywhere.
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                parts.forEachIndexed { i, p -> if (i == 0) send(p, true) else main.postDelayed({ send(p, false) }, i * 50L) }
                done()
            }))
    }
}

/** An edit must fit in one message: longer, it says so and nothing is cut. */
private fun editFits(text: String): Boolean {
    val n = Proto06.chars(text.trim())
    if (n <= Chat.MAX) return true
    Ui.say("An edit can be at most ${Chat.MAX} characters. This one is $n.", true)
    return false
}

/**
 * The message box. [onImage]: a picture or GIF the keyboard put in (Gboard's GIFs and stickers, a pasted picture),
 * handed over as its content link; without it the keyboard is told no pictures are taken here.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Composer(text: String, onText: (String) -> Unit, hint: String, onPhoto: (() -> Unit)? = null, voice: VoiceHooks? = null,
                     banner: (@Composable () -> Unit)? = null, onImage: ((Uri) -> Unit)? = null, tight: Boolean = false, onSend: () -> Unit) {
    val onImageSet = onImage != null
    val context = LocalContext.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val onSendNow by rememberUpdatedState(onSend)
    // The box keeps its own text (so the keyboard can put pictures in); [text] stays the one that counts. A change
    // from outside (sent and cleared, an emoji, a message to edit) is put in the box; typing goes back out.
    val field = rememberTextFieldState(text)
    val textNow by rememberUpdatedState(text)
    val onTextNow by rememberUpdatedState(onText)
    val onImageNow by rememberUpdatedState(onImage)
    // [sent] counts Enter sends, so the box is matched to [text] after each one even when [text] looks unchanged.
    var sent by remember { mutableStateOf(0) }
    LaunchedEffect(text, sent) {
        val want = text
        if (field.text.toString() != want) field.edit { replace(0, length, want) }
    }
    // Never cut here: a long message is split or refused when sent (sendLong), with a line saying so before that.
    LaunchedEffect(field) { snapshotFlow { field.text.toString() }.collect { if (it != textNow) onTextNow(it.take(Drafts.KEEP_MAX)) } }
    // Enter on a hardware keyboard sends what the box holds right now: its last letters reach [text] only a frame or
    // two later. An open composition (a Korean or Japanese IME) is committed first: setting the text from here ends it
    // and keeps its letters, so nothing comes back into the emptied box.
    val sendFromBox: () -> Unit = {
        val now = field.text.toString().take(Drafts.KEEP_MAX)
        if (now.isNotBlank()) {
            field.edit { replace(0, length, now) }
            if (now != textNow) onTextNow(now)
            onSendNow()
            sent++
        }
    }
    val receiver = remember {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                val take = onImageNow ?: return transferableContent
                return transferableContent.consume { item ->
                    val uri = item.uri
                    val type = uri?.let { u -> try { context.contentResolver.getType(u) } catch (_: Exception) { null } }.orEmpty()
                    if (uri != null && type.startsWith("image/")) { take(uri); true } else false
                }
            }
        }
    }
    var emoji by remember { mutableStateOf(false) }
    // GIFs come in from the keyboard (Gboard's GIF key) or the photo picker: the emoji window's GIF button says how.
    if (emoji) EmojiPicker("Emoji", { e -> onText((text + e).take(Drafts.KEEP_MAX)) }, onGif = if (onImageSet && onPhoto != null) ({
        emoji = false
        gifHelp(onPhoto)
    }) else null) { emoji = false }
    banner?.invoke()
    val longBy = Proto06.chars(text.trim())
    if (longBy > Chat.MAX) Text("$longBy characters: more than one message holds (${Chat.MAX}).",
        style = Type.small.copy(color = Palette.textMuted), modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp))
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
    Row(Modifier.fillMaxWidth().padding(start = if (onPhoto != null) 6.dp else 12.dp, end = 10.dp, top = if (tight) 4.dp else 8.dp, bottom = if (tight) 4.dp else 12.dp),
        verticalAlignment = Alignment.Bottom) {
        if (onPhoto != null) { IconAction("plus", "Attach", Palette.textMuted, size = 24.dp) { onPhoto() }; Spacer(Modifier.width(4.dp)) }
        val shape = io.github.ravenor907.muninn.ui.LookShapes.composer
        Row(Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(Palette.field).hcBorder(shape).padding(start = 16.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(hint, style = bodyStyle.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(state = field, textStyle = bodyStyle, cursorBrush = SolidColor(Palette.accent),
                    lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = if (tight) 2 else 6),
                    modifier = Modifier.fillMaxWidth().then(if (onImage != null) Modifier.contentReceiver(receiver) else Modifier)
                        // A hardware keyboard: Enter sends, Shift+Enter is a new line, Tab and Shift+Tab move on (never typed in).
                        .onPreviewKeyEvent { ev -> composerKey(ev, focus) { sendFromBox() } }
                        .semantics { contentDescription = hint })
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
 * A code that ran out (codes last a short time, so a lost one can't be used to reach you): who to ask, and one tap
 * that opens Android's share sheet with a ready message to send them by any app.
 */
@Composable
internal fun CodeEnded(name: String) {
    val context = LocalContext.current
    val who = name.ifBlank { "them" }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("That code from $who has run out. Codes only last a short time, so a lost one can't be used to reach you.", style = Type.normal)
        MButton("Ask $who for a new code", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, icon = "send") {
            try {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Hi${if (name.isBlank()) "" else " $name"}, your Muninn code ran out before I could use it. Could you send me a new one?"),
                    "Ask for a new code"))
            } catch (_: Exception) {}
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
        // A tablet's or computer's link code is never taken from a link or the clipboard, only scanned or typed here.
        mutableStateOf(ChatNav.incoming.ifEmpty { if (Contacts.unlink(clip).contains(Contacts.PREFIX) || clip.contains(Proto06.CODE_PREFIX)) clip else "" }
            .let { if (io.github.ravenor907.muninn.net.Link.isCode(it)) "" else it }.also { ChatNav.incoming = "" })
    }
    // Tap phones together needs NFC (and isn't offered on a TV).
    val hasNfc = remember { !io.github.ravenor907.muninn.tv.KidMode.isTv(context) && android.nfc.NfcAdapter.getDefaultAdapter(context) != null }
    // From the + menu: "pick" (New chat) starts on the contacts, "tv" (Pair a Kids' TV) on the camera for the TV's code.
    val start = remember { ChatNav.addMode.also { ChatNav.addMode = "" } }
    // "": the camera; "paste": typing or pasting a code or invite link.
    var mode by remember { mutableStateOf(if (start == "pick") "pick" else "") }
    val found = Contacts.parse(text)
    val invite = GroupLinks.parse(app, text)
    BackHandler(enabled = text.isNotEmpty() || mode.isNotEmpty()) { if (text.isNotEmpty()) text = "" else mode = "" }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar(if (mode == "join") "Join a group" else if (mode == "pick") "New chat" else if (start == "tv") "Pair a Kids' Device" else "Add someone")
        // Scrolls, so everything stays reachable with larger size and text settings or the keyboard up.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            // Like a messenger's compose button: someone already added comes first, then joining, then the camera.
            val people = app.chat.contacts.all.collectAsState().value.filter { !it.groupOnly }
            if (text.isBlank() && mode.isEmpty() && people.isNotEmpty())
                MButton("Message a contact", Modifier.fillMaxWidth().padding(top = 12.dp), Kind.Accent, icon = "chat") { mode = "pick" }
            if (mode == "pick") {
                people.sortedBy { it.shown.lowercase() }.forEach { p ->
                    io.github.ravenor907.muninn.ui.NavRow(p.shown) { ChatNav.open = p.chatKey }
                }
                Spacer(Modifier.height(24.dp))
                return@Column
            }
            // Joining from a copied invite ("choose + then Join a group"): the button is at the top, above the camera.
            if (text.isBlank() && mode.isEmpty()) MButton("Join a group", Modifier.fillMaxWidth().padding(top = 12.dp), if (people.isEmpty()) Kind.Accent else Kind.Normal, icon = "people") { mode = "join" }
            when {
                text.isBlank() && mode.isEmpty() -> {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        QrScanner(Modifier.fillMaxWidth().height(minOf(maxWidth, 340.dp))) { scanned ->
                            // A tablet's or computer's link code (Link your phone): linked here, the emoji come up next.
                            // Linking mirrors every chat to that device: the current lock first.
                            if (io.github.ravenor907.muninn.net.Link.isCode(scanned)) LockGate.ask(app, "Link this device") {
                                LinkUi.take(scanned)?.let { Ui.say(it, true) } ?: run { ChatNav.open = "" }
                            }
                            else text = Contacts.unlink(scanned)
                        }
                    }
                    Text(if (start == "tv") "Point at the code on the Kids' Device." else "Point at their code.", style = Type.normal, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                }
                text.isBlank() -> Field(text, { text = it }, if (mode == "join") "Paste the invite link or code" else "Paste a code or invite link",
                    Modifier.padding(vertical = 12.dp), single = false)
                invite != null -> Panel(Modifier.padding(top = 12.dp)) { JoinCard(app, invite) { text = ""; if (!invite.expired) ChatNav.open = "" } }
                io.github.ravenor907.muninn.net.Link.isCode(text) -> MButton("Link this device", Modifier.fillMaxWidth().padding(top = 12.dp), Kind.Accent, icon = "link") {
                    val code = text
                    LockGate.ask(app, "Link this device") { LinkUi.take(code)?.let { Ui.say(it, true) } ?: run { text = ""; ChatNav.open = "" } }
                }
                found == null -> {
                    Field(text, { text = it }, "Paste a code or link", Modifier.padding(vertical = 10.dp), single = false)
                    Text("That isn't a Muninn code.", style = Type.small.copy(color = Palette.danger))
                }
                Contacts.expires(text) in 1 until System.currentTimeMillis() -> CodeEnded(found.name)
                found.chatKey == app.identity.publicKey -> Text("That's your own code.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 12.dp))
                else -> Panel(Modifier.padding(top = 12.dp)) {
                    Text("Found: ${found.name}", style = Type.big)
                    // Someone already here with a different key: adding this is a key change, so say it first.
                    val replaces = app.chat.keyChangeFor(found)
                    replaces?.let { old ->
                        Text("New security key for ${old.shown}. Compare emoji after.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Not now", Modifier.weight(1f)) { text = "" }
                        MButton("Add ${found.name}", Modifier.weight(1f), Kind.Accent) {
                            app.chat.addContact(found)
                            // A one-time invite link: say hi at once, which adds me on their phone too (the link is used up then).
                            // So is every code Muninn and Huginn show now ("1", 13.6): no request to accept on their side.
                            if (Contacts.once(text)) app.chat.sendPrivate(found.chatKey, "Hi, it's ${app.identity.username}. I added you.")
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
                if (mode == "paste" && text.isBlank()) io.github.ravenor907.muninn.ui.NavRow("Join a group", icon = "people") { mode = "join" }
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
/**
 * [key]'s chat color (Person details › Chat color): while its chat is open, the accent follows it, and my bubbles
 * take a tint of it. Unspecified (the usual bubbles) when the chat has none.
 */
@Composable
internal fun mineTint(app: Muninn, key: String): Color {
    val st by app.chat.flags.all.collectAsState()
    val hex = io.github.ravenor907.muninn.ui.Accents.chatHex(st.colors[key])
    androidx.compose.runtime.DisposableEffect(hex) {
        io.github.ravenor907.muninn.ui.Access.chatAccent = hex
        onDispose { if (io.github.ravenor907.muninn.ui.Access.chatAccent == hex) io.github.ravenor907.muninn.ui.Access.chatAccent = "" }
    }
    return if (hex.isEmpty()) Color.Unspecified else Palette.tintOf(Palette.accentOf(hex))
}

/** My bubbles' color in the chat on screen (Unspecified: the usual). Set per chat with Chat color. */
internal val LocalMineBubble = androidx.compose.runtime.compositionLocalOf { Color.Unspecified }

@Composable
private fun Bubble(m: Chat.Msg, showName: Boolean, theirName: String? = null, photo: (() -> ByteArray?)? = null, statusText: String? = null,
                   quote: Pair<String, String>? = null, onQuote: () -> Unit = {}, me: String = "", groupRead: Int = -1, highlight: Boolean = false,
                   onReply: (() -> Unit)? = null, onReactions: () -> Unit = {}, onTap: () -> Unit = {},
                   /** Cap m2: an album shown in this bubble, opening one of its photos; a vote on a poll; who voted, by key. */
                   album: List<Chat.Msg>? = null, albumBytes: (Chat.Msg) -> ByteArray? = { null }, onAlbumTap: (Chat.Msg) -> Unit = {},
                   onVote: ((List<Int>) -> Unit)? = null, voterName: (String) -> String = { "Someone" },
                   /** Where this chat's files are saved (Sharing.folderFor). */
                   folder: String = "", onLong: () -> Unit) {
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
        m.failed -> "Not sent, tap to retry"
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
            // At most 560 dp wide, so lines stay readable on a wide tablet.
            Box(Modifier.widthIn(max = 560.dp).fillMaxWidth(0.8f), contentAlignment = align) {
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
                            FileCard(m, photo, folder)
                            Box(Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) { metaLine() }
                        }
                        isPhoto && album != null -> Column(Modifier.padding(4.dp)) {
                            Box(Modifier.padding(horizontal = 8.dp)) { Column { name(); quoted() } }
                            AlbumGrid(album, albumBytes, onAlbumTap)
                            Box(Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) { metaLine() }
                        }
                        isPhoto && m.gif -> Column(Modifier.padding(4.dp)) {
                            Box(Modifier.padding(horizontal = 8.dp)) { Column { name(); quoted() } }
                            GifImage(m.id, photo!!, "GIF", Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(260f / 170f)
                                .clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.groundDeep))
                            Box(Modifier.align(Alignment.End).padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp)) { metaLine() }
                        }
                        isPhoto -> Column(Modifier.padding(4.dp)) {
                            Box(Modifier.padding(horizontal = 8.dp)) { Column { name(); quoted() } }
                            // Kept decoded (PhotoCache), keyed by its size too so a photo still arriving is decoded again once whole.
                            val photoCtx = LocalContext.current
                            val bmp by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) {
                                photo!!()?.let { b -> io.github.ravenor907.muninn.ui.PhotoCache.get(photoCtx, "${m.id}:720:${b.size}") { decode(b, 720) } } } }
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
                                if (m.unsent.isNotEmpty()) Text(if (m.unsent == io.github.ravenor907.muninn.net.Groups.REMOVED) "Removed by an admin"
                                    else if (m.mine) "Message deleted by you" else "Message deleted by ${theirName ?: m.unsent}",
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
            Box(Modifier.widthIn(max = 560.dp).fillMaxWidth(0.8f)) { ReactionChips(m.reactions, me, m.mine, onReactions) }
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

/** A photo full screen: Back and, under it, [bottom] (the viewer's actions); without [bottom], Save to the phone's Pictures/Muninn at the top. */
@Composable
internal fun PhotoViewer(app: Muninn, m: Chat.Msg, close: () -> Unit, bottom: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    val bytes = remember(m.id) { app.chat.photo(m.photo) }
    val bmp = remember(m.id) { if (m.gif) null else decode(bytes, 2048) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Back", icon = "back") { close() }
            Spacer(Modifier.weight(1f))
            if (bottom == null) MButton("Save to phone", kind = Kind.Accent, icon = "download", enabled = bytes != null) {
                val ok = bytes != null && savePhoto(context, bytes, m.gif)
                Ui.say(if (ok) "Saved in Pictures › Muninn." else "Couldn't save it.", !ok)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (m.gif) GifImage(m.id, { bytes }, "GIF", Modifier.fillMaxSize(), fit = true, maxSide = 2048)
            else bmp?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                ?: Text(if (bytes == null) "This photo isn't on this phone." else "This photo can't be shown.", style = Type.normal)
        }
        bottom?.invoke()
    }
}

internal fun savePhoto(context: Context, bytes: ByteArray, gif: Boolean = false): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "Muninn-${System.currentTimeMillis()}" + if (gif) ".gif" else ".jpg")
        put(MediaStore.Images.Media.MIME_TYPE, if (gif) "image/gif" else "image/jpeg")
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

/** One choice under a chat header's Call button. */
private class CallChoice(val icon: String, val text: String, val go: () -> Unit)

/**
 * A chat header's one Call button. A tap shows [choices] in a small popup under it (Voice call and Video call,
 * or a group's Call everyone and Drop-in call). No choices: [unavailable] is said instead, and the button is dimmed.
 */
@Composable
private fun CallButton(label: String, choices: List<CallChoice>, unavailable: String = "") {
    var open by remember { mutableStateOf(false) }
    // CI: the screenshot of the open popup.
    LaunchedEffect(ChatNav.callPick, choices.size) { if (ChatNav.callPick && choices.isNotEmpty()) { ChatNav.callPick = false; open = true } }
    Box {
        val tint = if (choices.isEmpty()) Palette.textSoft else Palette.text
        val tap: () -> Unit = { if (choices.isEmpty()) { if (unavailable.isNotEmpty()) Ui.say(unavailable) } else open = true }
        // A plain phone either way; Video call is one of the choices it opens.
        IconAction("call", if (choices.any { it.icon == "video" }) "$label: voice or video" else label, tint) { tap() }
        DropdownMenu(expanded = open && choices.isNotEmpty(), onDismissRequest = { open = false }) {
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(c.text, style = Type.normal.copy(color = Palette.text), maxLines = 1) },
                    onClick = { open = false; c.go() }, leadingIcon = { MIcon(c.icon, Palette.accent, 20.dp) })
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
        title = { Text("Mute notifications for $name", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Calls show as missed, without ringing.", style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
                Line("For 15 minutes", "", icon = "clock") { mute(15 * flags.MINUTE, "$name is muted for 15 minutes.") }
                Line("For 1 hour", "", icon = "clock") { mute(flags.HOUR, "$name is muted for an hour.") }
                Line("For 8 hours", "", icon = "clock") { mute(8 * flags.HOUR, "$name is muted for 8 hours.") }
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
        m.gif -> "GIF"
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
    video = m.video, album = m.album, albumAt = m.albumAt, albumOf = m.albumOf, poll = m.poll, votes = m.votes, preview = m.preview, gif = m.gif)

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

/**
 * A round chip (folders, channels, the chat list's filters). [onLong]: hold to rename or delete. [shape] and [minHeight]:
 * the look's corners and 40dp, unless a screen (the Kids' Device's) asks for rounder and bigger ones.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Chip(text: String, on: Boolean, icon: String? = null, onLong: (() -> Unit)? = null,
                  shape: androidx.compose.ui.graphics.Shape = io.github.ravenor907.muninn.ui.Corner, minHeight: Dp = 40.dp, onClick: () -> Unit) {
    // A 40dp chip with a full 48dp touch area.
    Row(Modifier.minimumInteractiveComponentSize().heightIn(min = minHeight).clip(shape)
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
    // A Kids' Device shows only to one of its parents (it takes groups only from them).
    val people = contacts.filter { !it.groupOnly && !app.chat.trash.has(it.chatKey) && io.github.ravenor907.muninn.tv.TvParent.groupPickOk(it.chatKey) }
        .sortedBy { it.shown.lowercase() }
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

/**
 * Phones: a group's channels as chips under its top bar; a voice room's chip joins it (and says how many of 8 are in).
 * [onEdit] (admins): hold a chip to rename, delete or (a text channel) make it admin-only; with null, add a voice room.
 */
@Composable
private fun ChannelChips(s: Proto06.State, current: String, onText: (String) -> Unit, onEdit: ((Proto06.Channel?) -> Unit)?, onVoice: (String) -> Unit) {
    RoomManager.presence.collectAsState().value   // re-reads who is in which room when it changes
    val room by RoomManager.room.collectAsState()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        s.channels.forEach { c ->
            val edit = onEdit?.let { e -> { e(c) } }
            // Cap g3: an "Only admins post" channel has a lock.
            if (c.t == "text") Chip("# ${c.n}", c.id == current, icon = if (c.ao) "lock" else null, onLong = edit) { onText(c.id) }
            else {
                val n = RoomManager.whoIn(s.g, c.id).size
                Chip(if (n > 0) "${c.n} · $n/${io.github.ravenor907.muninn.net.call.RoomLogic.MAX_PEOPLE}" else c.n, room?.let { it.g == s.g && it.ch == c.id } == true,
                    icon = "audio", onLong = edit) { onVoice(c.id) }
            }
        }
        // No voice room yet: say what they're for (an admin adds one from here).
        if (s.channels.none { it.t == "voice" }) Chip("Voice rooms: talk here", false, icon = "audio") {
            if (onEdit != null) onEdit(null) else Ui.say("An admin adds voice rooms.")
        }
        RoomManager.groupCalls(s.g).forEach { (id, who) ->
            Chip("Group call · ${who.size}", room?.let { it.g == s.g && it.ch == id } == true, icon = "call") { onVoice(id) }
        }
    }
}

/**
 * "Keep longer" (or "Why?") in a group: how long its messages stay, and why. A Folk group always keeps 7 days; a Family
 * group's admins pick 7 to 30 (in Group settings). The rules stay as they are; this says them plainly and, to an admin
 * of a Folk group, offers the choice: make it Family, or keep 7 days.
 */
@Composable
private fun GroupKeepDialog(s: Proto06.State, admin: Boolean, onSettings: () -> Unit, onFamily: () -> Unit, onDismiss: () -> Unit) {
    val days = io.github.ravenor907.muninn.net.Limits.groupDays(s)
    val family = io.github.ravenor907.muninn.net.Limits.groupType(s) == io.github.ravenor907.muninn.net.Limits.FAMILY
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.panel,
        shape = io.github.ravenor907.muninn.ui.Corner,
        title = { Text(if (!family && admin) "Keep messages longer?" else "How long messages stay", style = Type.big) },
        text = {
            Text(when {
                family -> "Messages in ${s.name} delete after $days days for everyone, so old chats can't leak. In a Family group the admins " +
                    "choose 7, 14 or 30 days in Group settings." + (if (admin) "" else " Ask an admin if you'd like longer.")
                admin -> "Messages in ${s.name} delete after $days days for everyone, so old chats can't leak. ${s.name} is a Folk group, " +
                    "and Folk groups keep 7 days.\n\nTo keep up to 30 days, make it a Family group. Then invite links stop, and only " +
                    "admins add people, face to face or by a code. Or keep it Folk, at 7 days."
                else -> "Messages in ${s.name} delete after $days days for everyone, so old chats can't leak. ${s.name} is a Folk group, " +
                    "and Folk groups keep 7 days.\n\nAn admin can make it a Family group to keep up to 30 days. Then invite links stop, " +
                    "and only admins add people."
            }, style = Type.normal)
        },
        confirmButton = {
            when {
                family && admin -> MButton("Group settings", kind = Kind.Accent) { onSettings() }
                admin -> MButton("Make it Family", kind = Kind.Accent) { onFamily() }
                else -> MButton("OK") { onDismiss() }
            }
        },
        dismissButton = when {
            family && admin -> ({ MButton("Close") { onDismiss() } })
            admin -> ({ MButton("Keep 7 days") { onDismiss() } })
            else -> null
        },
    )
}

/** Just made Family: 7, 14 or 30 days, right away. Closing it keeps [now]. */
@Composable
private fun GroupDaysDialog(name: String, now: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.panel,
        shape = io.github.ravenor907.muninn.ui.Corner,
        title = { Text("$name is a Family group now", style = Type.big) },
        text = {
            Column {
                Text("How long should messages stay? Everyone in it gets the same.", style = Type.normal)
                io.github.ravenor907.muninn.net.Limits.CHOICES.forEach { d ->
                    MButton(if (d == now) "$d days (now)" else "$d days", Modifier.fillMaxWidth().padding(top = 8.dp),
                        if (d == io.github.ravenor907.muninn.net.Limits.LONGEST) Kind.Accent else Kind.Normal) { onPick(d) }
                }
            }
        },
        confirmButton = { MButton("Close") { onDismiss() } },
    )
}

/** A small group's "In the drop-in call: Sam, Jo · Join" line, in place of the channel chips. */
@Composable
private fun InVoiceLine(names: List<String>, onJoin: () -> Unit) {
    val shown = if (names.size <= 3) names.joinToString(", ") else names.take(2).joinToString(", ") + " and ${names.size - 2} more"
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Join the drop-in call") { onJoin() }
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon("audio", Palette.online, 14.dp)
        Spacer(Modifier.width(6.dp))
        Text("In the drop-in call: $shown", style = tinyStyle.copy(color = Palette.online), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Text(" · Join", style = tinyStyle.copy(color = Palette.accent, fontWeight = FontWeight.SemiBold), maxLines = 1)
    }
}

/** [ChannelDialog] for a new voice room (an admin's tap on "Voice rooms: talk here"). */
private val NEW_VOICE = Proto06.Channel("", "", "voice")

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
    // Each channel keeps its own half-typed message and what it answers or edits (Drafts).
    val draftKey = Drafts.groupKey(g, ch)
    val draft0 = remember(draftKey) { Drafts.get(app, draftKey) }
    var text by remember(ch) { mutableStateOf(draft0.text) }
    val list = rememberLazyListState()
    LaunchedEffect(g) { app.chat.pruneSoon() }
    LaunchedEffect(msgs.lastOrNull()?.id) { app.chat.groupRead(g); if (msgs.isNotEmpty()) if (Access.reduceMotion) list.scrollToItem(msgs.size - 1) else list.animateScrollToItem(msgs.size - 1) }
    val context = LocalContext.current
    // While it's on screen, new messages here just buzz; its old notification goes.
    DisposableEffect(key) {
        app.openChat = key
        androidx.core.app.NotificationManagerCompat.from(context).cancel(key.hashCode())
        onDispose { if (app.openChat == key) app.openChat = "" }
    }
    val scope = rememberCoroutineScope()
    // Photos picked and waiting for Send stay with the channel, like a private chat's (in memory only).
    val picked0 = remember(draftKey) { Drafts.picked[draftKey].orEmpty().map { Uri.parse(it) } }
    var picked by remember(ch) { mutableStateOf(picked0.singleOrNull()) }
    var preparing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Chat.Msg?>(null) }
    // Cap m2: several photos picked at once, the "+" menu, the camera ("photo" or "video"), a new poll, the Shared page.
    var pickedMany by remember(ch) { mutableStateOf(if (picked0.size > 1) picked0 else emptyList()) }
    // Cap a1: GIFs waiting to be sent, one panel at a time (picked, shared in or over the limits).
    var gifs by remember { mutableStateOf<List<PendingGif>>(emptyList()) }
    var attaching by remember { mutableStateOf(false) }
    var camera by remember(ch) { mutableStateOf(Drafts.camera[draftKey]?.mode.orEmpty()) }
    var polling by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }
    var sharedFiles by remember { mutableStateOf(false) }
    // Cap m1: answering, editing, and the message held for its sheet.
    var replyTo by remember(ch) { mutableStateOf(draft0.re.takeIf { it.isNotEmpty() }?.let { re -> msgs.firstOrNull { it.id == re } }) }
    var editing by remember(ch) { mutableStateOf(draft0.edit.takeIf { it.isNotEmpty() }?.let { ed -> msgs.firstOrNull { it.id == ed } }) }
    androidx.compose.runtime.SideEffect {
        Drafts.put(draftKey, Drafts.Draft(text, replyTo?.id.orEmpty(), editing?.id.orEmpty()))
        val waiting = picked?.let { listOf(it.toString()) } ?: pickedMany.map { it.toString() }
        if (waiting.isEmpty()) Drafts.picked.remove(draftKey) else Drafts.picked[draftKey] = waiting
    }
    DisposableEffect(draftKey) { onDispose { Drafts.save(app) } }
    var sheetFor by remember { mutableStateOf<Groups.GMsg?>(null) }
    // An admin's hold on a channel chip: its dialog (NEW_VOICE: a new voice room).
    var editChannel by remember { mutableStateOf<Proto06.Channel?>(null) }
    // Cap g2: messages from someone muted for me are folded ("Muted, tap to show") until tapped.
    val sound by app.chat.sound.all.collectAsState()
    var unfolded by remember(g) { mutableStateOf(setOf<String>()) }
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
    var searching by remember(ch) { mutableStateOf(Drafts.search.containsKey(draftKey)) }
    var query by remember(ch) { mutableStateOf(Drafts.search[draftKey].orEmpty()) }
    var matchAt by remember(ch) { mutableStateOf(0) }
    androidx.compose.runtime.SideEffect { keepOpen(draftKey, searching, query, camera) }
    if (searching) BackHandler { searching = false; query = "" }
    val matches = if (!searching || query.isBlank()) emptyList()
        else msgs.indices.filter { msgs[it].unsent.isEmpty() && msgs[it].text.contains(query.trim(), ignoreCase = true) }.reversed()
    LaunchedEffect(matchAt, matches.size, query) { matches.getOrNull(matchAt)?.let { list.animateScrollToItem(it) } }
    val action = ChatNav.action
    LaunchedEffect(action) {
        when (action) {
            "search" -> { searching = true; matchAt = 0 }
            "media" -> { sharedFiles = false; shared = true }
            "files" -> { sharedFiles = true; shared = true }
        }
        if (action.isNotEmpty()) ChatNav.action = ""
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(io.github.ravenor907.muninn.net.More.ALBUM_MAX)) { uris ->
        // GIFs go on their own, animated; the rest as a photo or an album.
        if (uris.isNotEmpty()) scope.launch {
            val anim = withContext(Dispatchers.IO) { uris.filter { Photos.isGif(context, it) } }
            val rest = uris.filterNot { it in anim }
            gifs = gifs + anim.map { PendingGif(it) }
            if (rest.size == 1) picked = rest[0] else if (rest.size > 1) pickedMany = rest
        }
    }
    val micAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> micAnswer(context, ok) }
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
    /** Cap a1: a GIF, animated, to the group. Over the limits, its panel offers a still instead. */
    fun sendGif(p: PendingGif) {
        preparing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { Photos.readGif(context, p.uri) }
            when {
                r == null -> { Ui.say("That GIF couldn't be opened.", true); gifs = gifs - p; p.done() }
                r.bytes != null -> { withContext(Dispatchers.IO) { app.chat.sendGroupGif(g, ch, r.bytes) }?.let { Ui.say(it, true) }; gifs = gifs - p; p.done() }
                else -> gifs = gifs.map { if (it === p) PendingGif(p.uri, Gifs.why(r), p.shared) else it }
            }
            preparing = false
        }
    }
    fun sendPollHere(poll: io.github.ravenor907.muninn.net.More.Poll) {
        app.chat.sendGroupPoll(g, ch, poll.q, poll.options, poll.multi)?.let { Ui.say(it, true) }
    }
    // Show in chat (from Media and files or the viewer): its channel, then the message lit up for a moment.
    var flash by remember { mutableStateOf("") }
    val mediaActs = MediaActions(io.github.ravenor907.muninn.net.Sharing.folderFor("Muninn", s.name),
        show = { m ->
            val gm = chats[g].orEmpty().firstOrNull { it.id == m.id }
            // A removed channel's messages are in main.
            if (gm != null) { if (texts.any { it.id == gm.ch }) { ChatNav.channel[g] = gm.ch } else { ChatNav.channel.remove(g) } }
            val here = chats[g].orEmpty().filter { gm == null || it.ch == gm.ch }.map { asMsg(app, it) }
            val head = albumsOf(here).second.entries.firstOrNull { e -> e.value.any { it.id == m.id } }?.key ?: m.id
            viewing = null; shared = false; flash = head
        },
        delete = { m -> app.chat.groups.deleteMessage(g, m.id) })
    viewing?.let { v ->
        BackHandler { viewing = null }
        MediaViewer(app, v, mediaActs) { viewing = null }
        return
    }
    if (camera.isNotEmpty()) {
        CameraScreen(camera == "video", keep = draftKey, onPhoto = { jpeg -> scope.launch(Dispatchers.IO) { app.chat.sendGroupPhoto(g, ch, jpeg) } },
            onVideo = { mp4, ms -> scope.launch(Dispatchers.IO) { app.chat.sendGroupVideo(g, ch, mp4, ms)?.let { Ui.say(it) } } }) { camera = "" }
        return
    }
    if (shared) {
        SharedMedia("Media and files · ${s.name}", chats[g].orEmpty().map { asMsg(app, it) }, { app.chat.photo(it.photo) }, { viewing = it },
            files = { mod -> ChatFilesList(app, "g:$g", mod) }, startFiles = sharedFiles, actions = mediaActs) { shared = false }
        return
    }
    LaunchedEffect(flash) {
        if (flash.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(150)   // the list (and its channel) is back first
        val at = msgs.indexOfFirst { it.id == flash }
        if (at < 0) { Ui.say("That message isn't here any more."); flash = ""; return@LaunchedEffect }
        if (Access.reduceMotion) list.scrollToItem(at) else list.animateScrollToItem(at)
        kotlinx.coroutines.delay(2500)
        flash = ""
    }
    val otherMembers = s.members.filter { it.k != app.identity.publicKey }
    val others = otherMembers.map { memberName(app, it) }
    val inRoom by RoomManager.room.collectAsState()
    // Muted shows first in the bar, as in a 1:1 chat.
    val groupFlags by app.chat.flags.all.collectAsState()
    val groupMuted = (groupFlags.muted[key] ?: 0L) > System.currentTimeMillis()
    androidx.compose.runtime.CompositionLocalProvider(LocalMineBubble provides mineTint(app, key)) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
    // Sideways with the keyboard up: one-line bar, and the extra lines wait, so the list and the box to type in fit.
    // Only while the keyboard is up: split screen or a short phone without it keeps every line.
    val imeUp = WindowInsets.isImeVisible
    val tight = imeUp && maxHeight < 320.dp
    Column(Modifier.fillMaxSize()) {
        ThreadBar(if (texts.size > 1 || ChatNav.wide) "${s.name} · #$chName" else s.name,
            if (tight) "" else (if (groupMuted) "Muted · " else "") + "${s.members.size} ${if (s.members.size == 1) "person" else "people"} · encrypted", "shield", Palette.online,
            { GroupAvatar(s.name, otherMembers, 40.dp) }, compact = tight, actions = {
                // One Call button, like a person's; Mute is in the menu.
                if (!grp.readOnly) {
                    RoomManager.presence.collectAsState().value   // re-reads the group's calls when they change
                    val rooms = s.channels.filter { it.t == "voice" }.map { it.id to it.n }
                    val calls = RoomManager.groupCalls(g).map { (id, _) -> id }
                    val choices = buildList {
                        // Rings every member, so it asks first. To just talk, there are voice rooms.
                        if (s.members.size > 1) add(CallChoice("call", "Call everyone") {
                            val n = s.members.size - 1
                            Ui.sure(Ask("Ring all $n ${if (n == 1) "person" else "people"}?", "Their phones and computers ring now. To talk without ringing anyone, use Drop-in call.", "Ring everyone",
                                extra = if (s.members.size > 2) "Pick people" else null, onExtra = { ChatNav.callSome = g },
                                onConfirm = { RoomActivity.call(context, g, emptyList()) }))
                        })
                        rooms.forEach { (id, n) ->
                            // How many are in it already, so a hangout shows it's going.
                            val inIt = RoomManager.whoIn(g, id).size
                            val base = if (rooms.size == 1) "Drop-in call" else "Drop-in call · $n"
                            val label = if (inIt > 0) "$base · $inIt in it" else if (rooms.size == 1) "$base (no ringing)" else base
                            add(CallChoice("audio", label) { RoomActivity.join(context, g, id) })
                        }
                        // Someone rang everyone: that's a group call, not a drop-in call.
                        calls.forEach { id ->
                            val inIt = RoomManager.whoIn(g, id).size
                            add(CallChoice("audio", if (inIt > 0) "Join group call · $inIt in it" else "Join group call") { RoomActivity.join(context, g, id) })
                        }
                    }
                    if (choices.isNotEmpty()) CallButton("Call ${s.name}", choices)
                }
            }, onMenu = { ChatNav.menuGroup = ""; ChatNav.hold = key })
        if (searching) ChatSearchBar(query, { query = it; matchAt = 0 }, matches.size, matchAt,
            onPrev = { if (matches.isNotEmpty()) matchAt = (matchAt + 1) % matches.size },
            onNext = { if (matches.isNotEmpty()) matchAt = (matchAt - 1 + matches.size) % matches.size }) { searching = false; query = "" }
        // Tablets list the channels in their own column.
        RoomManager.presence.collectAsState().value
        val calls = RoomManager.groupCalls(g)
        val admin = !grp.readOnly && s.isAdmin(me)
        // Only #general and one voice room (every new group): no chips, so it reads like one chat. They show once a
        // second channel is added (Group settings › Channels); the voice room stays in the Call button as Drop-in call.
        val manyChannels = s.channels.count { it.t == "text" } > 1 || s.channels.count { it.t == "voice" } > 1 || calls.isNotEmpty()
        if (!ChatNav.wide && manyChannels && !tight) ChannelChips(s, ch, { ChatNav.channel[g] = it }, if (admin) ({ c: Proto06.Channel? -> editChannel = c ?: NEW_VOICE }) else null) { RoomActivity.join(context, g, it) }
        // No chips (a small group): who is in the drop-in call still shows, on one line with Join.
        if (!ChatNav.wide && !manyChannels && inRoom?.g != g && !tight) s.channels.firstOrNull { it.t == "voice" }?.let { v ->
            val who = RoomManager.whoIn(g, v.id).filter { !it.me }
            if (who.isNotEmpty()) InVoiceLine(who.map { it.name }) { RoomActivity.join(context, g, v.id) }
        }
        // How long messages stay, with one tap to see how to keep them longer.
        var keepInfo by remember { mutableStateOf(false) }
        // Only an admin can change it (a Folk group by making it Family), so for everyone else the link only explains.
        if (!grp.readOnly && !searching && !tight) KeepLine(io.github.ravenor907.muninn.net.Limits.groupDays(s), if (admin) null else "Why?") { keepInfo = true }
        // Made Family from here: how many days, asked right away (it stays at what it was until one is picked).
        var keepPick by remember { mutableStateOf(false) }
        if (keepInfo) GroupKeepDialog(s, admin, onSettings = { keepInfo = false; ChatNav.open = "$key/settings" }, onFamily = {
            keepInfo = false
            app.chat.groups.setLimit(g, io.github.ravenor907.muninn.net.Limits.FAMILY, io.github.ravenor907.muninn.net.Limits.groupDays(s))?.let { Ui.say(it, true) }
                ?: run { keepPick = true }
        }) { keepInfo = false }
        if (keepPick) GroupDaysDialog(s.name, io.github.ravenor907.muninn.net.Limits.groupDays(s), onPick = { d ->
            keepPick = false
            app.chat.groups.setLimit(g, io.github.ravenor907.muninn.net.Limits.FAMILY, d)?.let { Ui.say(it, true) }
                ?: Ui.say("Messages in ${s.name} now delete after $d days.")
        }) { keepPick = false }
        // Cap g3: the group's about text (description and rules), at the top of its first channel. It never expires.
        // Paragraphs show one a line; a tap folds it to one line (kept for this run), for small screens.
        if (s.about.isNotEmpty() && ch == Proto06.MAIN && !tight) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp), onClick = {
            if (g in AboutFold.folded) AboutFold.folded.remove(g) else AboutFold.folded.add(g)
        }) {
            val shown = Groups.aboutShown(s.about)
            Text(shown, style = Type.normal.copy(color = Palette.textSoft), maxLines = if (g in AboutFold.folded) 1 else Int.MAX_VALUE,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = "About this group: $shown. Tap to ${if (g in AboutFold.folded) "show it all" else "fold it"}." })
        }
        if (!tight) inRoom?.let { r -> Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp), onClick = { RoomActivity.show(context) }) {
            Text("In ${r.title}${if (r.groupName.isNotEmpty()) " · ${r.groupName}" else ""}", style = Type.big)
            Text(if (r.muted) "You're muted · tap to open" else "Tap to open", style = Type.small)
        } }
        if (grp.readOnly) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
            Text(if (grp.gone == "removed") (if (app.chat.groups.isBanned(g, me)) "You were banned from ${s.name}" else "You were removed from ${s.name}")
                else "You left ${s.name}", style = Type.big)
            Text("Nothing new arrives.", style = Type.small)
        } else MyModStatus(app, g)
        // Cap g3: reports from members that this admin hasn't seen yet: a tap opens the Reports list (Group settings).
        val newReports = app.chat.groups.reportsByGroup.collectAsState().value[g].orEmpty().count { !it.read }
        if (!grp.readOnly && newReports > 0) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), onClick = { ChatNav.open = "$key/settings" }) {
            Text(if (newReports == 1) "1 new report" else "$newReports new reports", style = Type.big)
            Text("Tap to see them in Group settings › Reports.", style = Type.small)
        }
        if (!grp.readOnly && s.members.size == 1) Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
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
                    if (!gm.mine && gm.id !in unfolded && sound[gm.from]?.let { it.muted && !it.show } == true) MutedChip { unfolded = unfolded + gm.id }
                    else if (m.id !in gAlbums.first) Bubble(m, showName = true, photo = if (gm.photo.isNotEmpty()) ({ app.chat.photo(gm.photo) }) else null,
                        statusText = if (gm.mine) (if (gm.read.isNotEmpty() || gm.unsent.isNotEmpty()) "" else
                            io.github.ravenor907.muninn.net.Ticks.group(gm.to, gm.delivered) { k -> s.member(k)?.let { memberName(app, it) } ?: "Someone" }) else null,
                        quote = quoteOf(gm.re), onQuote = { jumpTo(gm.re) }, me = me, groupRead = if (gm.mine) gm.read.size else -1,
                        highlight = matches.getOrNull(matchAt) == i || gm.id == flash || (!gm.mine && gm.unsent.isEmpty() && io.github.ravenor907.muninn.net.Rich.mentions(gm.text, app.identity.username)),
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
                // Send is the big button; High quality is a small switch that remembers the last choice.
                PhotoSendRow(!preparing, onCancel = { picked = null }) { hd -> sendPhoto(uri, hd) }
            }
        }
        if (!grp.readOnly) GifShare.pending?.let { u -> LaunchedEffect(u) { GifShare.pending = null; gifs = gifs + PendingGif(u, shared = true) } }
        gifs.firstOrNull()?.let { p ->
            GifPanel(p, s.name, preparing, onSend = { sendGif(p) }, onStill = {
                gifs = gifs - p
                sendPhoto(p.uri, false)   // a shared copy stays until the next share clears it
            }, onFile = null) { gifs = gifs - p; p.done() }
        }
        // Cap f2: a file to everyone in the group, by the best route to each; its cards show here and in Transfers.
        val pickGroupFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) sendFileFrom(app, context, scope, key, uri) }
        if (attaching) AttachDialog(onPhotos = { io.github.ravenor907.muninn.ui.Picking.launch(pick, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onCamera = { camera = "photo" }, onVideo = { camera = "video" }, onPoll = { polling = true },
            onFile = { try { io.github.ravenor907.muninn.ui.Picking.launch(pickGroupFile, arrayOf("*/*")) } catch (_: Exception) { Ui.say("This phone has no file picker.", true) } }) { attaching = false }
        DirectFiles(key, s.name)
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
        val adminsOnlyHere = s.channel(ch)?.ao == true && !s.isAdmin(me)
        // @everyone notifies only from admins: say so before a member counts on it.
        if (!grp.readOnly && !s.isAdmin(me) && io.github.ravenor907.muninn.net.Rich.mentions(text, io.github.ravenor907.muninn.net.Rich.EVERYONE))
            Text("Only admins' @everyone notifies the whole group.", style = Type.small, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
        // Cap g3: in an "Only admins post" channel a member gets this line, not a box to type, attach or record in.
        if (!grp.readOnly && adminsOnlyHere) Panel(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
            Text("Only admins post here", style = Type.normal.copy(color = Palette.textMuted), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (!grp.readOnly && !adminsOnlyHere) Composer(text, { text = it }, if (editing != null) "Edit your message" else "Message #$chName", banner = banner, onPhoto = {
            attaching = true
        }, onImage = { uri -> scope.launch {
            if (withContext(Dispatchers.IO) { Photos.isGif(context, uri) }) sendGif(PendingGif(uri).also { gifs = gifs + it }) else picked = uri
        } }, voice = if (editing != null) null else VoiceHooks(
            allowed = { androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            ask = { micAsk.launch(android.Manifest.permission.RECORD_AUDIO) },
            send = { aac, ms -> scope.launch(Dispatchers.IO) { app.chat.sendGroupVoice(g, ch, aac, ms) } },
        ), tight = tight) {
            val e = editing
            if (e != null) {
                if (!editFits(text)) return@Composer
                app.chat.groups.editText(g, e.id, text)?.let { Ui.say(it, true) }; editing = null; text = ""
            } else {
                val re = replyTo?.id ?: ""
                sendLong(text, { replyTo = null; text = "" }) { p, first ->
                    if (!app.chat.sendGroupText(g, ch, p, if (first) re else "") && first && app.chat.groups.myStatus(g).isEmpty() && !adminsOnlyHere)
                        Ui.say("That couldn't be sent.", true)
                }
            }
        }
    }
    }
    }
    editChannel?.let { c -> ChannelDialog(app, g, c.takeIf { it !== NEW_VOICE }, startVoice = true) { editChannel = null } }
    sheetFor?.let { gm ->
        val actions = buildList {
            if (!grp.readOnly && gm.unsent.isEmpty()) add(SheetAction("Reply", "reply") { editing = null; replyTo = gm })
            if (gm.unsent.isEmpty() && gm.text.isNotEmpty()) add(SheetAction("Copy", "copy") {
                io.github.ravenor907.muninn.ui.Clip.copy(context, "Message", gm.text)
                Ui.say("Copied.")
            })
            if (!grp.readOnly && gm.mine && gm.unsent.isEmpty() && gm.photo.isEmpty() && gm.text.isNotEmpty())
                add(SheetAction("Edit", "edit") { replyTo = null; editing = gm; text = gm.text })
            add(SheetAction((if (gm.mine) "Sent " else "Received ") + clock(gm.time), "clock", folder = "Info", info = true))
            // Who reacted with what: everyone in the group sees reactions.
            val shown = io.github.ravenor907.muninn.net.Rich.shown(gm.reactions)
            if (shown.isNotEmpty()) add(SheetAction(shown.entries.joinToString(", ") { (k, e) ->
                "$e " + (if (k == me) "You" else s.member(k)?.let { memberName(app, it) } ?: "Someone") }, "smile", folder = "Info", info = true))
            if (gm.mine) add(SheetAction(Groups.status(gm).ifEmpty { "Sent" }, "check", folder = "Info", info = true))
            // Per member: whose phones have it and whose it's still waiting for.
            if (gm.mine && gm.unsent.isEmpty()) io.github.ravenor907.muninn.net.Ticks.groupLines(gm.to, gm.delivered) { k ->
                s.member(k)?.let { memberName(app, it) } ?: "Someone" }.forEach { add(SheetAction(it, "check", folder = "Info", info = true)) }
            if (gm.mine && gm.unsent.isEmpty() && !grp.readOnly) add(SheetAction("Unsend", "undo", danger = true) {
                Ui.sure(Ask("Unsend this message?",
                    "Removes it from everyone's phone.",
                    "Unsend", danger = true, extra = "Delete for me", onExtra = { app.chat.groups.deleteMessage(g, gm.id) },
                    onConfirm = { app.chat.groups.unsend(g, gm.id) }))
            })
            // Cap g2: the person who wrote it, in a folder of their own: Mute for me, Volume, and for admins Timeout and Ban.
            if (!gm.mine && gm.from.isNotEmpty()) {
                val who = whoOf(gm)
                val p = sound[gm.from]
                add(SheetAction(if (p?.muted == true) "Unmute for me" else "Mute for me", if (p?.muted == true) "sound" else "mute", folder = who) {
                    app.chat.sound.setMuted(gm.from, p?.muted != true)
                    Ui.say(if (p?.muted == true) "$who isn't muted for you." else "$who is muted for you.")
                })
                add(SheetAction("Volume (just for you)", "audio", folder = who) { ModNav.page = "volume"; ChatNav.menuGroup = g; ChatNav.hold = gm.from })
                if (app.chat.groups.canModerate(g, gm.from)) {
                    if (app.chat.groups.timedOutUntil(g, gm.from) > 0) add(SheetAction("Cancel timeout", "clock", folder = who) {
                        app.chat.groups.cancelTimeout(g, gm.from)?.let { Ui.say(it, true) } ?: Ui.say("$who can post again.")
                    }) else add(SheetAction("Timeout $who…", "clock", folder = who) { ModNav.page = "timeout"; ChatNav.menuGroup = g; ChatNav.hold = gm.from })
                    add(SheetAction("Ban $who…", "block", folder = who) { banAsk(app, g, gm.from, who) })
                }
            }
            // Cap g3: Report to admins, through the group (an admin's older app gets a private message, when a contact).
            // A main row, where people look for it (not among the facts in Info).
            if (!gm.mine && gm.unsent.isEmpty() && gm.from.isNotEmpty() && !grp.readOnly && s.admins.any { it != me && it != gm.from })
                add(SheetAction("Report to admins", "shield") {
                    Ui.sure(Ask("Report this message?", "Only the group's admins see it, and that it came from you.", "Report", onConfirm = {
                        val n = app.chat.groups.report(g, gm.id) { k, line -> app.chat.sendPrivate(k, line) }
                        if (n == 0) Ui.say("No admin can get it yet. They may need an update.", true)
                        else Ui.say(if (n == 1) "Sent to 1 admin." else "Sent to $n admins.")
                    }))
                })
            // Cap g2: an admin's Delete for everyone on someone else's message (a signed "remove", 26.2).
            if (!gm.mine && gm.unsent.isEmpty() && gm.from.isNotEmpty() && app.chat.groups.canModerate(g, gm.from))
                add(SheetAction("Delete for everyone", "delete", danger = true) {
                    Ui.sure(Ask("Delete for everyone?", "It shows as Removed by an admin on every member's phone.", "Delete", danger = true,
                        hold = true, spoken = "Hold for 5 seconds to delete this message for everyone",
                        onConfirm = { app.chat.groups.removeForEveryone(g, gm.id, gm.from)?.let { Ui.say(it, true) } ?: Ui.say(removedText(app.chat.groups.withoutRemove(g))) }))
                })
            add(SheetAction("Delete for me", "delete", danger = true) {
                Ui.sure(Ask("Delete this message?", deletedHereOnly(), "Delete", danger = true,
                    onConfirm = { app.chat.groups.deleteMessage(g, gm.id) }))
            })
        }
        MessageSheet(gm.reactions[me]?.e?.takeIf { it.isNotEmpty() }, !grp.readOnly && gm.unsent.isEmpty(), actions,
            onReact = { e -> if (!app.chat.groups.react(g, gm.id, e)) Ui.say("That reaction couldn't be added.", true) },
            readBy = if (gm.mine && gm.unsent.isEmpty()) ({
                ReadByList(Groups.readBy(gm), { k -> s.member(k)?.let { memberName(app, it) } ?: "Someone" }, ::clock)
            }) else null) { sheetFor = null }
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
            Text("VOICE", style = labelStyle.copy(fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp).semantics { heading() })
            // No voice room yet: say what they're for.
            if (voice.isEmpty() && calls.isEmpty()) Text("Voice rooms: talk here", style = Type.small, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            voice.forEach { c ->
                val who = RoomManager.whoIn(g, c.id)
                ChannelRow(if (who.isNotEmpty()) "${c.n} · ${who.size}/${io.github.ravenor907.muninn.net.call.RoomLogic.MAX_PEOPLE}" else c.n, voice = true,
                    on = room?.let { it.g == g && it.ch == c.id } == true) { RoomActivity.join(context, g, c.id) }
                RoomFaces(who)
            }
            calls.forEach { (id, who) ->
                ChannelRow("Group call", voice = true, on = room?.let { it.g == g && it.ch == id } == true) { RoomActivity.join(context, g, id) }
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
    var about by remember(g) { mutableStateOf(Groups.aboutShown(s.about)) }
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
            // Cap g3: the about text (description and rules), shown at the top of the first channel. It never expires.
            if (admin || s.about.isNotEmpty()) io.github.ravenor907.muninn.ui.Framed("About and rules") {
                if (admin) {
                    Field(about, { about = it.take(Proto06.MAX_ABOUT) }, "Description or rules", Modifier.padding(top = 8.dp), single = false)
                    val behind = remember(g, s.v) { if (s.about.isEmpty()) app.chat.groups.needG3Update(g) else emptyList() }
                    Text("Everyone sees it. Up to ${Proto06.MAX_ABOUT} characters and ${Groups.MAX_ABOUT_PARAGRAPHS} lines, kept until changed." +
                        if (behind.isNotEmpty()) " " + Groups.needUpdate(behind) else "", style = Type.small, modifier = Modifier.padding(top = 4.dp))
                    MButton("Save", Modifier.fillMaxWidth().padding(vertical = 8.dp), enabled = Groups.aboutWire(about) != s.about) {
                        app.chat.groups.setAbout(g, about)?.let { Ui.say(it, true) } ?: Ui.say(if (about.isBlank()) "Removed." else "Saved. Everyone sees it.")
                    }
                } else Text(Groups.aboutShown(s.about), style = Type.normal, modifier = Modifier.padding(vertical = 10.dp))
            }
            io.github.ravenor907.muninn.ui.Framed("Type") { GroupLimitPanel(app, g, s, admin) }
            // Family: only admins add people, so there's no switch.
            if (admin && io.github.ravenor907.muninn.net.Limits.linksAllowed(s)) io.github.ravenor907.muninn.ui.Framed("Who can invite") { MembersInviteToggle(app, g) }
            // Cap g2: who is banned and who is timed out, for admins.
            if (admin) GroupModPanels(app, g)
            // Folders are only on this phone.
            if (folders.isNotEmpty()) io.github.ravenor907.muninn.ui.Framed("Folders") {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    folders.forEach { f -> val on = key in f.keys; DetailChip(f.name, on) { app.chat.folders.put(f.id, key, !on) } }
                }
            }
            if (admin || s.channels.size > 1) io.github.ravenor907.muninn.ui.Framed("Channels") {
                s.channels.forEach { c ->
                    Line(if (c.t == "voice") c.n else "# ${c.n}", if (c.t == "voice") "Voice room" else if (c.ao) "Only admins post" else "", icon = if (c.t == "voice") "audio" else "chat", enabled = admin) { channel = c }
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
    // A Family group: each member's safety code is checked once, in person.
    val family = io.github.ravenor907.muninn.net.Limits.groupType(s) == io.github.ravenor907.muninn.net.Limits.FAMILY
    val unchecked = if (family && !grp.readOnly) s.members.count { it.k != me && !app.chat.isVerified(it.k) } else 0
    Column(Modifier.fillMaxSize()) {
        PageBar("People · ${s.members.size}", "Back to ${s.name}") { ChatNav.open = Folders.groupKey(g) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                if (unchecked > 0) Text("Check each one's safety code in person: $unchecked to check. Tap someone, then Compare emoji.",
                    style = Type.small, modifier = Modifier.padding(vertical = 8.dp))
                io.github.ravenor907.muninn.ui.Framed {
                    s.members.sortedWith(compareBy({ it.k != s.owner }, { !s.isAdmin(it.k) }, { memberName(app, it).lowercase() })).forEach { m ->
                        val role = listOf(when { m.k == s.owner -> "Owner"; s.isAdmin(m.k) -> "Admin"; else -> "" },
                            if (family && m.k != me) (if (app.chat.isVerified(m.k)) "Checked" else "Not checked") else "").filter { it.isNotEmpty() }.joinToString(" · ")
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
    // A Kids' Device shows only to one of its parents (it takes groups only from them).
    val people = contacts.filter { !it.groupOnly && !s.has(it.chatKey) && !app.chat.trash.has(it.chatKey) && io.github.ravenor907.muninn.tv.TvParent.groupPickOk(it.chatKey) }
        .sortedBy { it.shown.lowercase() }
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
                        .putExtra(Intent.EXTRA_TEXT, GroupLinks.inviteText(s.name, code)), "Share the group code"))
                }
                MButton("Copy", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "copy") {
                    io.github.ravenor907.muninn.ui.Clip.copy(context, "Muninn group code", GroupLinks.inviteText(s.name, code), sensitive = false)
                    Ui.say("Group code copied, with how to join.")
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
private fun ChannelDialog(app: Muninn, g: String, edit: Proto06.Channel?, startVoice: Boolean = false, done: () -> Unit) {
    var name by remember { mutableStateOf(edit?.n ?: "") }
    var voice by remember { mutableStateOf(edit?.t == "voice" || (edit == null && startVoice)) }
    var adminsOnly by remember { mutableStateOf(edit?.ao == true) }
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
                // Cap g3: for #announcements. Posts still go after the group's message days: lasting rules go in About and rules.
                if (edit != null && edit.t == "text" && edit.id != Proto06.MAIN)
                    io.github.ravenor907.muninn.ui.ToggleRow("Only admins post", "For announcements. Posts here still go after the group's message days; lasting rules go in About and rules", adminsOnly) { adminsOnly = it }
            }
        },
        confirmButton = {
            MButton(if (edit == null) "Add" else "Save", kind = Kind.Accent, enabled = name.isNotBlank()) {
                var err = if (edit == null) app.chat.groups.addChannel(g, name, voice)
                    else if (name.trim() != edit.n) app.chat.groups.renameChannel(g, edit.id, name) else null
                if (err == null && edit != null && adminsOnly != edit.ao) err = app.chat.groups.setAdminsOnly(g, edit.id, adminsOnly)
                if (err != null) Ui.say(err, true)
                done()
            }
        },
        dismissButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Cancel") { done() }
                if (edit != null && edit.id != Proto06.MAIN) MButton("Delete", kind = Kind.Danger) {
                    done()
                    Ui.sure(Ask("Remove ${edit.n}?", "Its messages move to the main channel.", "Remove", danger = true,
                        onConfirm = { app.chat.groups.removeChannel(g, edit.id)?.let { Ui.say(it, true) } }))
                }
            }
        },
    )
}

/**
 * On a linked tablet: the phone's chats, newest first. [inline]: it's the chat list itself (no back arrow); else a page
 * of its own.
 */
@Composable
private fun MirrorList(inline: Boolean = false) {
    if (!inline) BackHandler { ChatNav.open = "" }
    val chats by io.github.ravenor907.muninn.net.Link.mirror.collectAsState()
    val phone by io.github.ravenor907.muninn.net.Link.phone.collectAsState()
    val list = chats.values.sortedByDescending { c -> c.msgs.lastOrNull()?.time ?: 0L }
    LaunchedEffect(Unit) { io.github.ravenor907.muninn.net.Link.fetchMissing() }
    Column(Modifier.fillMaxSize()) {
        if (!inline) PageBar("Your phone's chats") { ChatNav.open = "" }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon("phone", Palette.accent, 20.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(phone?.name ?: "Your phone", style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("History comes over on home Wi-Fi", style = Type.small)
            }
            // Never on opening: only this, at most once in 10 minutes.
            IconAction("refresh", "Refresh", Palette.textMuted, size = 20.dp) {
                Ui.say(io.github.ravenor907.muninn.net.Link.askSync())
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        LazyColumn(Modifier.weight(1f)) {
            if (list.isEmpty()) item { Text("Your chats come over on home Wi-Fi.", style = Type.normal, modifier = Modifier.padding(16.dp)) }
            items(list, key = { it.peer }) { c ->
                val last = c.msgs.lastOrNull()
                PersonRow(c.name, last?.let { (if (it.mine) "You: " else "") + it.text.replace('\n', ' ') } ?: "", last?.let { when_(it.time) } ?: "", 0, false, {
                    io.github.ravenor907.muninn.ui.Avatar(c.peer, c.name, 48.dp) }) { ChatNav.open = "mirror/" + c.peer }
            }
        }
    }
}

/** On a linked tablet: one of the phone's chats. Photos show once they came over on the home Wi-Fi. */
@Composable
private fun MirrorChatScreen(peer: String, back: () -> Unit) {
    BackHandler { back() }
    val chats by io.github.ravenor907.muninn.net.Link.mirror.collectAsState()
    val c = chats[peer]
    val pendingAll by io.github.ravenor907.muninn.net.Link.pending.collectAsState()
    val waiting = pendingAll.filter { it.peer == peer }
    var text by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    val msgs = c?.msgs.orEmpty()
    LaunchedEffect(msgs.size + waiting.size) { if (msgs.isNotEmpty() || waiting.isNotEmpty()) state.scrollToItem(msgs.size + waiting.size - 1) }
    LaunchedEffect(peer) { io.github.ravenor907.muninn.net.Link.fetchMissing() }
    Column(Modifier.fillMaxSize()) {
        PageBar(c?.name ?: "Chat", "Back to your phone's chats") { back() }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = state) {
            items(msgs, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
                    Box(Modifier.widthIn(max = 520.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                        .background(if (m.mine) Palette.goldTint else Palette.field).padding(horizontal = 12.dp, vertical = 8.dp)) {
                        if (m.f != null) MirrorMedia(m, m.mine)
                        else Text(m.text, style = Type.normal.copy(color = Palette.text))
                    }
                }
            }
            // Sent from here, not answered by the phone yet: Sending, or why it didn't send (tap to try again).
            items(waiting, key = { "p:" + it.id }) { w ->
                Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = Alignment.CenterEnd) {
                    Column(Modifier.widthIn(max = 520.dp).clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.goldTint)
                        .clickable(enabled = w.err.isNotEmpty()) { io.github.ravenor907.muninn.net.Link.retrySend(w.id) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(w.text, style = Type.normal.copy(color = Palette.text))
                        Text(if (w.err.isEmpty()) "Sending" else "${w.err} · Retry", style = Type.small.copy(color = if (w.err.isEmpty()) Palette.textMuted else Palette.danger))
                    }
                }
            }
        }
        Composer(text, { text = it }, "Message through your phone") {
            val t = text.trim()
            // Never cut: a message through the phone is one message, so a longer one is refused and kept here.
            if (Proto06.chars(t) > io.github.ravenor907.muninn.net.LinkWire.TEXT_MAX)
                Ui.say("A message through your phone can be at most ${io.github.ravenor907.muninn.net.LinkWire.TEXT_MAX} characters. This one is ${Proto06.chars(t)}.", true)
            else if (t.isNotEmpty()) {
                val r = io.github.ravenor907.muninn.net.Link.sendFromCompanion(peer, t)
                if (r == null) text = "" else Ui.say(r, true)
            }
        }
    }
}

/** Cap a1: a GIF waiting in a chat. [why]: set once it turned out to be over the limits. [shared]: our own copy of one shared in. */
internal class PendingGif(val uri: Uri, val why: String = "", val shared: Boolean = false) {
    /** A shared-in copy goes once it's sent or put aside. */
    fun done() { if (shared) uri.path?.let { java.io.File(it).delete() } }
}

/** A GIF shared into Muninn from another app ("Share" › Muninn), waiting for a chat to be opened. */
object GifShare {
    var pending by mutableStateOf<Uri?>(null)

    /** Copies the shared GIF in (the last one shared replaces any before it); null if it couldn't be read. */
    fun copyIn(context: Context, uri: Uri): Uri? = try {
        val dir = java.io.File(context.cacheDir, "gifshare")
        dir.deleteRecursively(); dir.mkdirs()
        val f = java.io.File(dir, "shared.gif")
        var n = 0L
        context.contentResolver.openInputStream(uri)?.use { ins ->
            f.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val r = ins.read(buf); if (r < 0) break; n += r; if (n > Gifs.MAX_BYTES + 1L) break; out.write(buf, 0, r) }
            }
        } ?: throw java.io.IOException()
        Uri.fromFile(f)
    } catch (e: Exception) { null }
}

/** "Send this GIF?", or, when it's over the limits, the way it can still go: as a still picture or as a file. */
@Composable
private fun GifPanel(p: PendingGif, to: String, busy: Boolean, onSend: () -> Unit, onStill: () -> Unit, onFile: (() -> Unit)?, onCancel: () -> Unit) {
    Panel(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UriThumb(p.uri, Modifier.size(72.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                when {
                    busy -> Text("Getting it ready…", style = Type.big)
                    p.why.isEmpty() -> {
                        Text(if (p.shared) "Send the GIF you shared to $to?" else "Send this GIF to $to?", style = Type.big)
                        Text("It moves. Comments and other details in it are removed.", style = Type.small)
                    }
                    else -> {
                        Text(p.why, style = Type.big)
                        Text("GIFs move up to 8 MB and ${Gifs.MAX_FRAMES} frames. " + (if (onFile != null) "Send it as a still picture, or as a file that keeps moving." else "Send it as a still picture?"),
                            style = Type.small)
                    }
                }
            }
        }
        val buttons = if (p.why.isEmpty()) 2 else if (onFile != null) 3 else 2
        io.github.ravenor907.muninn.ui.ChoiceRow(buttons, Modifier.padding(top = 8.dp), minWidth = 88.dp, gap = 8.dp) { n, m ->
            when {
                n == 0 -> MButton("Cancel", m, enabled = !busy) { onCancel() }
                p.why.isEmpty() -> MButton("Send", m, Kind.Accent, enabled = !busy) { onSend() }
                n == 1 -> MButton("As a still", m, if (onFile == null) Kind.Accent else Kind.Normal, enabled = !busy) { onStill() }
                else -> MButton("As a file", m, Kind.Accent, enabled = !busy) { onFile?.invoke() }
            }
        }
    }
}

/** "Delete for me" says where it goes: this phone only, and a linked computer keeps its own copy. */
internal fun deletedHereOnly(): String =
    if (io.github.ravenor907.muninn.net.Link.companions.value.isEmpty()) "It's deleted on this phone only."
    else "It's deleted on this phone only. Your linked computer keeps its copy."

/**
 * A chat's open search and camera, kept in memory under [k] (Drafts) so the lock coming up, a fold or a recreation
 * doesn't lose them. The camera's taken photo or video is kept by CameraScreen itself under the same key.
 */
private fun keepOpen(k: String, searching: Boolean, query: String, camera: String) {
    if (searching) Drafts.search[k] = query else Drafts.search.remove(k)
    if (camera.isEmpty()) Drafts.camera.remove(k)
    else if (Drafts.camera[k]?.mode != camera) Drafts.camera[k] = Drafts.Taken(camera)
}
