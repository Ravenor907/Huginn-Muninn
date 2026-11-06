@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.ui.fitBars
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.ChatOrder
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.net.Voice
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Accents
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.ColorVision
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.LocalRoundIcons
import io.github.ravenor907.muninn.ui.Looks
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Seasons
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.bar
import io.github.ravenor907.muninn.ui.hcBorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

// A Kids' Device on a tablet or phone: Muninn's own layout (a top card, filter chips, the chat list, bottom tabs, and the
// conversation with its composer), a little rounder and bigger for kids. Colors always come from the look, Seasonal choice
// and accent in use, so every theme works. Nothing to add people with, nothing to scan, no Settings: a parent sets
// everything from their phone. A TV keeps its own D-pad screens (TvRoot).

/** The kids' bottom tab, filter chip and My colors (kept outside the screens, so they stay while a chat is open). */
internal object KidPhoneNav {
    var tab by mutableStateOf("chats")
    var filter by mutableStateOf("all")
    var colors by mutableStateOf(false)
    /** What's typed in each chat's box (chat key to text): it stays while the chat closes (a quiet time, Back, the screen turning). */
    val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** When the last quick message went, from any chip (a burst of taps sends one a moment). */
    @Volatile var chipAt = 0L
}

/** The kids' style: rounder cards and rows, rounder bubbles, bigger avatars and touch targets. */
private val CARD = 22.dp
private val BUBBLE = 20.dp
private val AVATAR = 50.dp
private val TOUCH = 56.dp
/** The widest one column gets on a phone held sideways, so rows and chips don't stretch across the screen. */
private val COLUMN_MAX = 720.dp

/** Slightly bigger text (on top of Settings › Look's sizes and Android's font size) and rounded icons, over everything in it. */
@Composable
internal fun KidStyle(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    // At a large text size already (130% or more), no boost on top: the biggest sizes still have to fit.
    val boost = if (Access.text >= 1.3f) 1f else KidUi.TEXT_BOOST
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * boost), LocalRoundIcons provides true) {
        content()
    }
}

private const val LOOK_PREFS = "kidlook"
private const val TAKEN = "taken"

/** k13 (7.18): puts the parent's colors on when they are new here, or always while the kid may not pick their own. */
internal fun applyKidLook(app: Muninn, cfg: TvConfig) {
    val prefs = app.getSharedPreferences(LOOK_PREFS, Context.MODE_PRIVATE)
    val parent = KidLook.parentTheme(cfg)
    if (parent == null) { prefs.edit().remove(TAKEN).apply(); return }
    val showing = KidTheme(Access.look, Seasons.choice(Access.season), Access.accent)
    val t = KidLook.toApply(cfg, prefs.getString(TAKEN, "") ?: "", showing) ?: return
    putKidTheme(app, t)
    prefs.edit().putString(TAKEN, parent.key).apply()
}

private const val ACCESS_TAKEN = "accessTaken"

/**
 * k14 (7.18): puts the accessibility a parent picked on (text size, size of everything, high contrast, color vision, less
 * motion) when it is new here, or always while the kid may not pick their own. Saved the same way Settings › Look saves them.
 */
internal fun applyKidAccess(app: Muninn, cfg: TvConfig) {
    val prefs = app.getSharedPreferences(LOOK_PREFS, Context.MODE_PRIVATE)
    val a = KidK14.accessToApply(cfg.access, prefs.getString(ACCESS_TAKEN, "") ?: "", cfg.lookPick) ?: return
    val id = app.identity
    a.text?.let { v -> val f = v / 100f; Access.text = f; id.textSize = f }
    a.size?.let { v -> val f = v / 100f; Access.size = f; id.uiSize = f }
    a.hc?.let { Access.contrast = it; id.highContrast = it }
    a.cv?.let { Access.colorVision = it; id.colorVision = it }
    a.still?.let { Access.reduceMotion = it; id.reduceMotion = it }
    prefs.edit().putString(ACCESS_TAKEN, a.key).apply()
}

/** Shows and saves [t], the same way Settings › Look does. */
private fun putKidTheme(app: Muninn, t: KidTheme) {
    Access.look = Looks.of(t.look); Access.season = Seasons.choice(t.season); Access.accent = Accents.choice(t.accent)
    app.identity.look = Access.look; app.identity.season = Access.season; app.identity.accent = Access.accent
}

/**
 * The kids' screens on a tablet or phone: the chat list, or one conversation ([person]). On a tablet, two panes as Muninn's
 * tablet layout has: the list on the left, the conversation on the right.
 */
@Composable
internal fun KidPhone(app: Muninn, cfg: TvConfig, person: TvPerson?, quiet: QuietTime?, onParent: () -> Unit) {
    KidStyle {
        BoxWithConstraints(Modifier.fillMaxSize().fitBars().imePadding()) {
            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= io.github.ravenor907.muninn.screens.ChatNav.TABLET_SW_DP
            val full = maxWidth
            val wide = tablet && full >= io.github.ravenor907.muninn.screens.ChatNav.WIDE_MIN
            if (wide) Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(minOf(400.dp, full * 0.4f)).fillMaxHeight().background(Palette.panelLow)) {
                    KidHome(app, cfg, quiet, person?.k, onParent)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.hairline))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (person != null) key(person.k) { KidTalk(app, cfg, person, wide = true) { TvNav.home() } }
                    else PickNote(cfg, quiet)
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = COLUMN_MAX).fillMaxSize()) {
                    if (person == null) KidHome(app, cfg, quiet, null, onParent)
                    else key(person.k) { KidTalk(app, cfg, person, wide = false) { TvNav.home() } }
                }
            }
            if (KidPhoneNav.colors) MyColors(app, cfg) { KidPhoneNav.colors = false }
        }
    }
}

/** A tablet with no conversation open; during a quiet time it says why the chat closed and who the kid can talk to. */
@Composable
private fun PickNote(cfg: TvConfig, quiet: QuietTime?) {
    val words = if (quiet == null) "Pick a chat" else {
        val ns = kidPeople(cfg, quiet).map { it.n }
        val who = if (ns.size <= 1) ns.firstOrNull() ?: "a parent" else ns.dropLast(1).joinToString(", ") + " and " + ns.last()
        "${KidRules.quietName(quiet.n)} now. You can talk to $who."
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MIcon(if (quiet == null) "chat" else "clock", Palette.textFaint, 48.dp)
        Text(words, style = Type.normal.copy(color = Palette.textMuted, fontSize = 18.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Home: the greeting card, the quiet time banner, the open tab, then the bottom tabs. */
@Composable
private fun KidHome(app: Muninn, cfg: TvConfig, quiet: QuietTime?, openKey: String?, onParent: () -> Unit) {
    // Back here does nothing: a kid can't back out into anything else.
    BackHandler(enabled = true) {}
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The height, taken here once: what a quiet time holds never takes more than a quarter of it, so the parents (the only
    // people at bedtime) always stay in sight below.
    val heldMax = maxHeight * 0.25f
    Column(Modifier.fillMaxSize()) {
        GreetingCard(app, cfg, onParent)
        if (quiet != null) QuietLine(quiet, tv = false, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth())
        HeldList(app, cfg, quiet, heldMax)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (KidPhoneNav.tab) {
                "call" -> KidCalls(app, cfg, quiet)
                "people" -> KidPeopleTab(app, cfg, quiet, openKey)
                else -> KidChatList(app, cfg, quiet, openKey)
            }
        }
        KidTabs()
    }
    }
}

/**
 * k14: what a quiet time is holding (a voice message or photo the kid finished), each with when it goes and Don't send.
 */
@Composable
private fun HeldList(app: Muninn, cfg: TvConfig, quiet: QuietTime?, maxH: Dp) {
    val held by KidHoldStore.list.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { KidHoldStore.load(app) } }
    // More than one: one row ("3 waiting to send") that opens the list, so the people stay in sight.
    var open by remember { mutableStateOf(false) }
    if (held.isEmpty()) return
    val context = LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(context)
    val groupsAll by app.chat.groups.all.collectAsState()
    Column(Modifier.fillMaxWidth().heightIn(max = maxH).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp)) {
        if (held.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(CARD)).background(Palette.panel)
            .clickable(role = Role.Button) { open = !open }.semantics { stateDescription = if (open) "Open" else "Closed" }
            .heightIn(min = TOUCH).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon("clock", Palette.accent, 22.dp)
            Text(KidK14.heldSummary(held.size), style = Type.normal.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            MIcon(if (open) "close" else "more", Palette.textMuted, 22.dp)
        }
        if (held.size == 1 || open) held.forEach { h ->
            val who = cfg.contacts.firstOrNull { it.k == h.k }?.n ?: groupsAll[h.k.removePrefix("g:")]?.state?.name ?: "someone"
            val what = when (h.kind) { "voice" -> "Voice message"; "photo" -> "Photo"; else -> "Message" }
            val line = "$what to $who. " + (quiet?.let { KidK14.willSend(it, h24) } ?: if (KidTv.timeUnsure()) KidK14.WILL_SEND_SOON else "Sending soon")
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(CARD)).background(Palette.panel).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MIcon(when (h.kind) { "voice" -> "mic"; "photo" -> "image"; else -> "chat" }, Palette.accent, 22.dp)
                Text(line, style = Type.normal.copy(fontSize = 16.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 10.dp))
                Box(Modifier.size(TOUCH).clip(CircleShape).clickable(role = Role.Button) { KidHoldStore.cancel(app, h.id) }
                    .semantics { contentDescription = "Don't send the ${what.lowercase()} to $who" }, contentAlignment = Alignment.Center) {
                    MIcon("close", Palette.textMuted, 22.dp)
                }
            }
        }
    }
}

/**
 * The top card, where Muninn has the raven, its name and Settings: the raven, "Hi, Ava" with a little sparkle, My colors
 * (unless a parent locked it) and "For a parent". The name is the child's name a parent set (k14), never the device's.
 */
@Composable
private fun GreetingCard(app: Muninn, cfg: TvConfig, onParent: () -> Unit) {
    val shape = RoundedCornerShape(CARD)
    val name = cfg.child
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp).clip(shape).background(Palette.panel)
        .heightIn(min = 72.dp).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(12.dp))
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
            Text(KidUi.greeting(name), style = Type.big.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            MIcon("sparkle", Palette.goldBright, 20.dp)
        }
        if (KidLook.kidMayPick(cfg)) IconAction("palette", "My colors", Palette.accent, Modifier.size(TOUCH), size = 26.dp) { KidPhoneNav.colors = true }
        ParentLock(tv = false, modifier = Modifier.size(TOUCH), onClick = onParent)
    }
}

/** The bottom tabs: Chats, Call and People. */
@Composable
private fun KidTabs() {
    Column(Modifier.fillMaxWidth().bar(Palette.groundDeep, top = true)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KidUi.TABS.forEach { (id, label) ->
                val on = KidPhoneNav.tab == id
                val tint = if (on) Palette.goldBright else Palette.textMuted
                Column(Modifier.weight(1f).heightIn(min = TOUCH).clip(RoundedCornerShape(CARD)).background(if (on) Palette.accentFill else Color.Transparent)
                    .clickable(role = Role.Tab) { KidPhoneNav.tab = id }.semantics { selected = on }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    MIcon(when (id) { "call" -> "call"; "people" -> "people"; else -> "chat" }, tint, 24.dp)
                    Text(label, style = Type.small.copy(color = tint, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** One conversation on the list: who, their messages, how many are new, and when the last one came. */
private data class KidRow(val p: TvPerson, val msgs: List<Chat.Msg>, val unread: Int, val last: Long)

/** The people (parents first, by name; only parents during a quiet time) and groups, each with its messages. */
@Composable
private fun kidRows(app: Muninn, cfg: TvConfig, quiet: QuietTime?): List<KidRow> {
    val convs by app.chat.conversations.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val groupsAll by app.chat.groups.all.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    val unreadGroups by app.chat.groups.unreadGroups.collectAsState()
    return (kidPeople(cfg, quiet) + KidChats.groups(app, groupsAll, quiet)).map { p ->
        val m = KidChats.msgs(p.k, convs, gmsgs)
        KidRow(p, m, KidChats.unread(p.k, m, unread, unreadGroups), m.lastOrNull()?.time ?: 0L)
    }
}

/** What the list shows under a name. */
private fun kidPreview(m: Chat.Msg?, name: String): String {
    if (m == null) return "Say hi to $name"
    val what = when {
        m.voice > 0 -> "Voice message"
        m.video > 0 -> "Video"
        m.poll != null -> "Poll: ${m.poll.q}"
        m.file.isNotEmpty() -> "File"
        m.gif -> "GIF"
        m.photo.isNotEmpty() -> "Photo"
        else -> m.text.replace('\n', ' ')
    }
    return if (m.mine) "You: $what" else what
}

/** The clock today, the date before that. */
private fun kidTime(t: Long): String {
    if (t <= 0L) return ""
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    val today = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    return (if (today) DateFormat.getTimeInstance(DateFormat.SHORT) else DateFormat.getDateInstance(DateFormat.SHORT)).format(Date(t))
}

/** A rounded card for a row on the kids' lists; [open]: the conversation open beside it on a tablet. */
private fun rowCard(open: Boolean) = Modifier.padding(horizontal = 10.dp, vertical = 3.dp).clip(RoundedCornerShape(CARD))
    .background(if (open) Palette.accentFill else Palette.panel)

/**
 * The chat list: filter chips (All, Family, Groups), the "New from" bar, Daily Hávamál when it's on, then the
 * conversations in Muninn's order (unread first, newest first), each with Muninn's unread styling.
 */
@Composable
private fun KidChatList(app: Muninn, cfg: TvConfig, quiet: QuietTime?, openKey: String?) {
    val rows = kidRows(app, cfg, quiet)
    val byKey = rows.associateBy { it.p.k }
    val unreadKeys = rows.filter { it.unread > 0 }.map { it.p.k }.toSet()
    val ordered = KidUi.order(rows.map { it.p.k }, unreadKeys) { byKey[it]?.last ?: 0L }.mapNotNull { byKey[it] }
    // The Groups chip only while the device is in a group.
    val hasGroups = rows.any { KidChats.isGroup(it.p.k) }
    val chips = KidUi.FILTERS.filter { it.first != "groups" || hasGroups }
    val chip = KidPhoneNav.filter.takeIf { f -> chips.any { it.first == f } } ?: "all"
    val shown = ordered.filter { KidUi.keeps(chip, it.p.k) }
    // "New from Mom, Dad" over the list: a tap opens the newest; X hides it until the next new message.
    val unreadNewest = ordered.filter { it.unread > 0 }.sortedByDescending { it.last }
    var closedAt by remember { mutableLongStateOf(0L) }
    val newestAt = unreadNewest.firstOrNull()?.last ?: 0L
    val barText = ChatOrder.newFrom(unreadNewest.map { it.p.n }, true)
    val showBar = chip == "all" && barText.isNotEmpty() && ChatOrder.barShown(newestAt, closedAt)
    if (rows.isEmpty() && !cfg.hav) { BigNote(TvWords.icon(TvWords.ASK_PARENT), TvWords.ASK_PARENT); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)) {
        item(key = "chips") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chips.forEach { (id, label) ->
                    io.github.ravenor907.muninn.screens.Chip(label, chip == id, shape = RoundedCornerShape(CARD), minHeight = TOUCH) { KidPhoneNav.filter = id }
                }
            }
        }
        if (showBar) item(key = "newfrom") {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp).clip(RoundedCornerShape(CARD)).background(Palette.accentFill)
                .clickable(role = Role.Button) { unreadNewest.firstOrNull()?.let { TvNav.show(it.p.k) } }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MIcon("chat", Palette.accent, 20.dp)
                Text(barText, style = Type.normal.copy(color = Palette.text, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp))
                Box(Modifier.size(TOUCH).clip(CircleShape).clickable(role = Role.Button) { closedAt = newestAt }
                    .semantics { contentDescription = "Hide until the next message" }, contentAlignment = Alignment.Center) {
                    MIcon("close", Palette.textMuted, 20.dp)
                }
            }
        }
        // k12 (7.18): Daily Hávamál, when a parent turned it on: today's verse at the top of the list.
        if (cfg.hav && chip == "all") item(key = "hav") { HavRow(cfg, rowCard(false)) }
        items(shown, key = { it.p.k }) { r ->
            val p = r.p
            io.github.ravenor907.muninn.screens.PersonRow(p.n, kidPreview(r.msgs.lastOrNull(), p.n), kidTime(r.last), r.unread, false,
                { io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, AVATAR) }, modifier = rowCard(p.k == openKey)) { TvNav.show(p.k) }
        }
        if (shown.isEmpty() && rows.isNotEmpty()) item(key = "none") {
            Text(if (chip == "groups") "No groups yet." else "Nobody here yet.", style = Type.normal.copy(fontSize = 17.sp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

/** Call: everyone it can call (not groups), each a big row. Off when a parent turned calls off. */
@Composable
private fun KidCalls(app: Muninn, cfg: TvConfig, quiet: QuietTime?) {
    val context = LocalContext.current
    val people = kidPeople(cfg, quiet)
    if (!cfg.calls) { BigNote("call", "Calls are off. Ask a parent."); return }
    if (people.isEmpty()) { BigNote(TvWords.icon(TvWords.ASK_PARENT), TvWords.ASK_PARENT); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(people, key = { it.k }) { p ->
            Row(rowCard(false).fillMaxWidth().clickable(role = Role.Button) { kidCall(app, context, p.k) }
                .semantics(mergeDescendants = true) { contentDescription = "Call ${p.n}" }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, AVATAR)
                Text(p.n, style = Type.big.copy(fontSize = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 14.dp))
                Box(Modifier.size(TOUCH).clip(CircleShape).background(Palette.gold), contentAlignment = Alignment.Center) {
                    MIcon("call", Palette.onGold, 24.dp)
                }
            }
        }
    }
}

/** People: everyone it can talk to and its groups, as big round cards; a tap opens the conversation. */
@Composable
private fun KidPeopleTab(app: Muninn, cfg: TvConfig, quiet: QuietTime?, openKey: String?) {
    val rows = kidRows(app, cfg, quiet)
    if (rows.isEmpty()) { BigNote(TvWords.icon(TvWords.ASK_PARENT), TvWords.ASK_PARENT); return }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows.size, key = { rows[it].p.k }) { i ->
            val r = rows[i]
            val p = r.p
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(CARD)).background(if (p.k == openKey) Palette.accentFill else Palette.panel)
                .clickable(role = Role.Button) { TvNav.show(p.k) }
                .semantics(mergeDescendants = true) { contentDescription = if (r.unread > 0) "${p.n}, ${r.unread} new" else p.n }
                .padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, 72.dp)
                    if (r.unread > 0) Box(Modifier.align(Alignment.TopEnd).heightIn(min = 24.dp).widthIn(min = 24.dp).clip(CircleShape)
                        .background(Palette.accent).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(if (r.unread > 9) "9+" else "${r.unread}", style = Type.small.copy(color = Palette.onAccent, fontWeight = FontWeight.Bold))
                    }
                }
                Text(p.n, style = Type.big.copy(fontSize = 18.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
}

/** A big icon and a few words, in the middle. */
@Composable
private fun BigNote(icon: String, text: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MIcon(icon, Palette.goldBright, 56.dp)
        Text(text, style = Type.big.copy(fontSize = 20.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
}

/** Calls [k], or says in a few words why it can't. */
private fun kidCall(app: Muninn, context: Context, k: String) {
    when {
        !TvAudio.hasMic(context) -> Ui.say(TvWords.NO_MIC, true)
        io.github.ravenor907.muninn.net.call.Opus.loadError != null -> Ui.say(TvWords.CANT_CALL, true)
        !io.github.ravenor907.muninn.net.call.CallManager.canCall(k) -> { app.chat.probeCaps(k); Ui.say(TvWords.CANT_CALL, true) }
        // The microphone is asked for when a parent pairs the device; without it, the child reads "Ask a parent".
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> Ui.say(TvWords.ASK_PARENT, true)
        else -> io.github.ravenor907.muninn.screens.CallActivity.call(context, k)
    }
}

/**
 * One conversation, Muninn's own: the top bar (back, avatar, name, and Call when a parent allows calls), the messages,
 * and the composer for the chatting level. Quick messages sit behind the smile left of the box; at "Quick messages only"
 * there's no box: the quick messages are the composer, open, with big chips. k14: the tray takes a share of the height
 * (one scrolling row on a phone held sideways), closes while the kid types, and the smile and photo fold into More when
 * the box would get too narrow. A draft stays when the chat closes; a quiet time holds a finished clip or photo.
 */
@Composable
private fun KidTalk(app: Muninn, cfg: TvConfig, p: TvPerson, wide: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val convs by app.chat.conversations.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    // A person's messages, or a group's (someone else's text says who wrote it; members no parent approved are held).
    val msgs = KidChats.msgs(p.k, convs, gmsgs)
    val group = KidChats.isGroup(p.k)
    val held = KidChats.heldCount(p.k, gmsgs)
    // k14: another family's Kids' Device whose parents haven't added this one yet.
    val waiting = p.k in cfg.wait
    val playing by Voice.playing.collectAsState()
    var viewing by remember { mutableStateOf<String?>(null) }
    /** A video playing full screen, or a poll open (their message ids). */
    var video by remember { mutableStateOf<String?>(null) }
    var poll by remember { mutableStateOf<String?>(null) }
    val openedAt = remember { System.currentTimeMillis() }
    var reading by remember { mutableStateOf<String?>(null) }
    // The draft lives outside this screen, so a quiet time (or Back, or the screen turning) never loses it.
    var text by remember(p.k) { mutableStateOf(KidPhoneNav.drafts[p.k] ?: "") }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    /** The quick message just sent (its id) and when, so its chip says how it's doing. */
    var sent by remember { mutableStateOf("") }
    var sentAt by remember { mutableLongStateOf(0L) }
    val mic = remember { TvAudio.hasMic(context) }
    // k11: typing, photos and voice messages only when a parent chose that chatting level.
    val compose = KidUi.compose(cfg.chat, mic)
    var tray by remember(p.k) { mutableStateOf(compose.trayOpen) }
    /** The box has focus, or the keyboard is up: the tray stays closed meanwhile. */
    var focused by remember { mutableStateOf(false) }
    val ime = WindowInsets.isImeVisible
    /** The folded smile and photo are open (k14: a narrow composer). */
    var more by remember(p.k) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun setText(v: String) { text = v; if (v.isEmpty()) KidPhoneNav.drafts.remove(p.k) else KidPhoneNav.drafts[p.k] = v }
    fun micOn() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun finishRecording() {
        if (!recording || sending) return
        sending = true
        // Not tied to this screen: a clip on its way is still sent (or held by a quiet time) if the screen goes.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val r = TvRecorder.finish()
            sending = false
            recording = false
            if (r != null) Ui.say(KidChats.sendOrHold(app, p.k, "voice", r.first, r.second)) else Ui.say(TvWords.TOO_SHORT, true)
        }
    }
    fun cancelRecording() { if (sending) return; if (TvRecorder.recording.value) TvRecorder.cancel(); recording = false }
    fun record() {
        if (!micOn()) { Ui.say(TvWords.ASK_PARENT, true); return }
        Voice.stopPlaying()
        if (TvRecorder.start(context)) recording = true else Ui.say(TvWords.NO_MIC, true)
    }
    fun sendTyped() {
        val t = TvRules.clean(text, 2000)
        if (t.isNotEmpty()) { KidChats.sendText(app, p.k, t, typed = true).let { r -> if (r != "Sending") Ui.say(r) }; setText("") }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Not tied to this screen either: a photo picked as a quiet time starts is held, not lost.
        if (uri != null) CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val jpeg = withContext(Dispatchers.IO) { Photos.prepare(context, uri, false) }
            if (jpeg == null) Ui.say("That photo can't be sent", true)
            else Ui.say(withContext(Dispatchers.IO) { KidChats.sendOrHold(app, p.k, "photo", jpeg, 0) })
        }
    }
    fun pickPhoto() {
        try { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (_: Exception) { Ui.say(TvWords.ASK_PARENT, true) }
    }
    /** Consolidation 2.19: the in-app camera, for a photo (no video on a kids' device), when the parent allows photos. */
    var camera by remember(p.k) { mutableStateOf(false) }
    /** The smile: opening the tray puts the keyboard away. */
    fun toggleTray() {
        if (!tray) { focusManager.clearFocus(); keyboard?.hide() }
        tray = !tray
    }

    DisposableEffect(p.k) {
        app.openChat = p.k
        TvRecorder.onLimit = { finishRecording() }
        onDispose {
            if (app.openChat == p.k) app.openChat = ""
            TvRecorder.onLimit = null
            if (TvRecorder.recording.value && !sending) {
                // A quiet time closed the chat mid-recording: the clip is finished and held, never lost. Back cancels it.
                val q = KidTv.quietNow()
                if (q != null && KidK14.holds(p.k, KidTv.state.value.parents, q)) CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                    TvRecorder.finish()?.let { r -> Ui.say(KidChats.sendOrHold(app, p.k, "voice", r.first, r.second)) }
                } else TvRecorder.cancel()
            }
            Voice.stopPlaying()
            KidSpeak.stop()
        }
    }
    LaunchedEffect(msgs.size) { KidChats.markRead(app, p.k) }
    // Whether their app takes calls isn't known until it says so: ask once (shared/docs/protocol.md 1.2).
    LaunchedEffect(p.k) { if (!group) app.chat.probeCaps(p.k) }
    // A voice message that arrives while this screen is open plays out loud by itself, as on a TV.
    val newest = msgs.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest != null && !newest.mine && newest.voice > 0 && newest.time >= openedAt && !recording)
            app.chat.photo(newest.photo)?.let { Voice.play(newest.id, it) }
    }
    LaunchedEffect(playing) { while (playing.isNotEmpty()) { Voice.tick(); delay(200) } }
    LaunchedEffect(sent) { if (sent.isNotEmpty()) { delay(2500); sent = "" } }
    // Typing (the box focused, or the keyboard up) closes the tray; a parent opening typing up closes the big one too.
    LaunchedEffect(ime, focused) { if (compose.text && (ime || focused)) tray = false }
    LaunchedEffect(compose.text) { if (compose.text) tray = false }
    BackHandler { onBack() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The pane's size, taken here once: the rows below use these, never maxWidth or maxHeight in their own scopes.
        val paneW = maxWidth.value
        val paneH = maxHeight.value
        val conf = LocalConfiguration.current
        val oneRow = KidK14.trayOneRow(conf.screenWidthDp.toFloat(), conf.screenHeightDp.toFloat(),
            conf.smallestScreenWidthDp >= io.github.ravenor907.muninn.screens.ChatNav.TABLET_SW_DP)
        val trayMax = KidK14.trayMax(paneH).dp
        val fontScale = LocalDensity.current.fontScale
        val callIcon = KidK14.callIconOnly(paneW, fontScale, back = !wide)
        // The smile, the camera and the photo, beside the box; folded into More when the box would get too narrow to type in.
        val icons = (if (cfg.quick.isNotEmpty()) 1 else 0) + (if (compose.photo) 2 else 0)
        // At the largest sizes the box gets a row of its own above the buttons (then nothing needs folding), and the name
        // a line of its own under the bar.
        val boxRow = compose.text && KidK14.boxOwnRow(paneW, icons, fontScale)
        val fold = compose.text && !boxRow && KidK14.collapse(paneW, icons)
        val nameLine = KidK14.nameOwnLine(paneW, back = !wide, call = cfg.calls && !group)
        val chipState = if (sent.isEmpty()) "" else {
            val q = cfg.quick.firstOrNull { it.id == sent }
            val m = q?.let { x -> msgs.lastOrNull { it.mine && it.text == x.text && it.time >= sentAt - 2_000L } }
            if (m == null) "Sending" else KidK14.sendState(true, m.state, m.failed, waiting).ifEmpty { "Sending" }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                // A tablet keeps the list beside the conversation, so there's nothing to go back to.
                if (!wide) IconAction("back", "Back to chats", Palette.text, Modifier.size(TOUCH), size = 26.dp) { onBack() }
                else Spacer(Modifier.width(12.dp))
                if (!nameLine) {
                    io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, AVATAR)
                    Text(p.n, style = Type.big.copy(fontSize = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() })
                } else Spacer(Modifier.weight(1f))
                // At a large text size (or a narrow screen) Call is only its icon, so the name keeps its room.
                if (cfg.calls && !group) {
                    if (callIcon || nameLine) Box(Modifier.size(TOUCH).clip(CircleShape).background(Palette.gold).clickable(role = Role.Button) { kidCall(app, context, p.k) }
                        .semantics { contentDescription = "Call ${p.n}" }, contentAlignment = Alignment.Center) {
                        MIcon("call", Palette.onGold, 24.dp)
                    } else Row(Modifier.heightIn(min = TOUCH).clip(RoundedCornerShape(TOUCH / 2)).background(Palette.gold)
                        .clickable(role = Role.Button) { kidCall(app, context, p.k) }.semantics(mergeDescendants = true) { contentDescription = "Call ${p.n}" }
                        .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        MIcon("call", Palette.onGold, 22.dp)
                        Text("Call", style = Type.big.copy(color = Palette.onGold, fontWeight = FontWeight.Bold), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            if (nameLine) Text(p.n, style = Type.big.copy(fontSize = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp).semantics { heading() })
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
            // The newest at the bottom. The list is measured after the rows below, so it always keeps what they leave.
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A tap on their message reads it out loud, for a child who can't read yet.
                items(msgs.takeLast(60).asReversed()) { m ->
                    TalkBubble(app, m, playing, onText = { reading = m.id }, onVideo = { video = m.id }, onPoll = { poll = m.id },
                        speak = { KidSpeak.say(context, m.text) }, corner = BUBBLE, who = if (group) "" else p.n,
                        status = KidK14.sendState(m.mine, m.state, m.failed, waiting)) { viewing = m.id }
                }
            }
            if (waiting) TalkNote(KidK14.WAIT_LINE)
            if (held > 0) TalkNote(KidK14.HELD_LINE)
            // 1.42: a file someone on the list sends asks first here too (Decline / Accept), and shows how it's going.
            io.github.ravenor907.muninn.screens.DirectFiles(p.k, p.n)
            // Quick messages: one tap sends, reads it out loud, and the chip says how it's doing. The composer at "Quick messages only".
            if (KidK14.trayShown(!compose.text, tray, ime || focused)) QuickTray(cfg.quick, compose.bigChips, sent, chipState, trayMax, oneRow) { q ->
                val now = System.currentTimeMillis()
                if (KidK14.chipOk(KidPhoneNav.chipAt, now)) {
                    KidPhoneNav.chipAt = now
                    KidChats.sendText(app, p.k, q.text).let { r -> if (r != "Sending") Ui.say(r) }
                    sent = q.id
                    sentAt = now
                    KidSpeak.say(context, KidK14.spoken(q))
                }
            }
            if (!compose.text && cfg.quick.isEmpty()) Text("Ask a parent to add quick messages", style = Type.normal.copy(fontSize = 17.sp),
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
            // The folded buttons, open: big and labelled, above the box (consolidation 2.19: the same four as a phone's
            // Send menu shape). What the parent's rules don't allow stays, greyed, and says "Ask a parent".
            if (fold && more) Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoreChoice("smile", "Quick messages", Modifier.weight(1f), enabled = cfg.quick.isNotEmpty()) { more = false; toggleTray() }
                    MoreChoice("image", "Camera", Modifier.weight(1f), enabled = compose.photo) { more = false; camera = true }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoreChoice("folder", "Saved media", Modifier.weight(1f), enabled = compose.photo) { more = false; pickPhoto() }
                    MoreChoice("mic", "Voice message", Modifier.weight(1f), enabled = compose.voice) { more = false; record() }
                }
            }
            // The typing box: beside the buttons, or (at the largest sizes) on a row of its own above them.
            val field: @Composable (Modifier) -> Unit = { m ->
                val shape = RoundedCornerShape(TOUCH / 2)
                val body = TextStyle(fontFamily = Type.body, fontSize = 17.sp, color = Palette.text)
                Box(m.heightIn(min = TOUCH).clip(shape).background(Palette.field).hcBorder(shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Type a message", style = body.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(text, { setText(it.take(2000)) }, textStyle = body, cursorBrush = SolidColor(Palette.accent),
                        // At a large text size the box grows to 3 lines at most, so the messages keep room.
                        maxLines = if (fontScale >= 1.5f) 3 else 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendTyped() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "Type a message" })
                }
            }
            if (boxRow) field(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp))
            if (compose.text || compose.photo || compose.voice) Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom) {
                if (fold) RoundIcon("more", "More", on = more, toggle = true) { more = !more }
                else {
                    if (cfg.quick.isNotEmpty()) RoundIcon("smile", "Quick messages", on = tray, toggle = true) { toggleTray() }
                    if (compose.photo) RoundIcon("image", "Camera") { camera = true }
                    if (compose.photo) RoundIcon("folder", "Saved media") { pickPhoto() }
                }
                if (compose.text && !boxRow) field(Modifier.weight(1f).padding(horizontal = 6.dp))
                else Spacer(Modifier.weight(1f))
                when {
                    compose.text && text.isNotBlank() -> RoundIcon("send", "Send", filled = true) { sendTyped() }
                    compose.voice -> RoundIcon("mic", "Voice message", filled = true) { record() }
                    compose.text -> RoundIcon("send", "Send", filled = true, enabled = false) {}
                }
            }
        }
    }

    if (camera) androidx.compose.ui.window.Dialog({ camera = false },
        androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        io.github.ravenor907.muninn.screens.CameraScreen(photoOnly = true, onPhoto = { jpeg ->
            // Not tied to this screen: a photo taken as a quiet time starts is held, not lost.
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { Ui.say(withContext(Dispatchers.IO) { KidChats.sendOrHold(app, p.k, "photo", jpeg, 0) }) }
        }, onVideo = { _, _ -> }) { camera = false }
    }
    viewing?.let { id ->
        val list = msgs.filter { KidChats.isPhoto(it) }
        TvPhoto(app, list, id, onMove = { viewing = it }) { viewing = null }
    }
    video?.let { id -> msgs.firstOrNull { it.id == id }?.let { m -> KidVideo(app, m) { video = null } } ?: LaunchedEffect(id) { video = null } }
    poll?.let { id ->
        msgs.firstOrNull { it.id == id }?.let { m -> KidPoll(m, compose.text, tv = false, onVote = { i -> KidChats.vote(app, p.k, m.id, i) }) { poll = null } }
            ?: LaunchedEffect(id) { poll = null }
    }
    reading?.let { id ->
        val m = msgs.firstOrNull { it.id == id }
        if (m == null) LaunchedEffect(id) { reading = null } else TvFullText(m.text) { reading = null }
    }
    if (recording) KidDialog(tv = false, onBack = { cancelRecording() }) {
        val ms by produceState(0L) { while (true) { value = TvRecorder.elapsed(); delay(250) } }
        MIcon("mic", Palette.danger, 72.dp)
        Text(if (sending) "Sending…" else Voice.length(ms.toInt()), style = tvTitle(36.sp), modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MButton("Send", Modifier.weight(1f), Kind.Accent, enabled = !sending, icon = "send") { finishRecording() }
            MButton("Delete", Modifier.weight(1f), enabled = !sending, icon = "delete") { cancelRecording() }
        }
    }
}

/** A line under the messages: why some don't show, or that a parent still has to say yes. */
@Composable
private fun TalkNote(text: String) {
    Text(text, style = Type.normal.copy(color = Palette.textMuted, fontSize = 15.sp), textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
}

/**
 * One of the folded buttons, open: its icon and its name. Not [enabled] (the parent's rules): greyed, still focusable
 * and tappable, and it says "Ask a parent" instead of vanishing.
 */
@Composable
private fun MoreChoice(icon: String, label: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(BUBBLE)
    Row(modifier.heightIn(min = TOUCH).clip(shape).background(if (enabled) Palette.field else Palette.panelLow).hcBorder(shape)
        .clickable(role = Role.Button) { if (enabled) onClick() else Ui.say(TvWords.ASK_PARENT, true) }
        .semantics(mergeDescendants = true) { if (!enabled) stateDescription = TvWords.ASK_PARENT }
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        MIcon(icon, if (enabled) Palette.accent else Palette.textFaint, 24.dp)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = Type.big.copy(fontSize = 16.sp, color = if (enabled) Palette.text else Palette.textFaint), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!enabled) Text(TvWords.ASK_PARENT, style = Type.small, maxLines = 1)
        }
    }
}

/**
 * The quick messages as round chips; [big] at "Quick messages only". At most [maxH] tall, scrolling inside it; one
 * scrolling row ([oneRow]) on a phone held sideways. The chip just sent ([sent]) says how it's doing ([state]).
 */
@Composable
private fun QuickTray(quick: List<Quick>, big: Boolean, sent: String, state: String, maxH: Dp, oneRow: Boolean, onSend: (Quick) -> Unit) {
    if (quick.isEmpty()) return
    if (oneRow) Row(Modifier.fillMaxWidth().heightIn(max = maxH).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        quick.forEach { q -> QuickChip(q, big, if (sent == q.id) state else "", oneRow = true) { onSend(q) } }
    } else FlowRow(Modifier.fillMaxWidth().heightIn(max = maxH).verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quick.forEach { q -> QuickChip(q, big, if (sent == q.id) state else "", oneRow = false) { onSend(q) } }
    }
}

/** One quick message: its picture big (the parent's emoji, or the one it starts with), then its words. */
@Composable
private fun QuickChip(q: Quick, big: Boolean, state: String, oneRow: Boolean, onClick: () -> Unit) {
    val (pic, words) = KidK14.picture(q)
    val shape = RoundedCornerShape(BUBBLE)
    val done = state.isNotEmpty()
    Row(Modifier.heightIn(min = if (big) 64.dp else 48.dp).then(if (big && !oneRow) Modifier.widthIn(min = 140.dp) else Modifier)
        .then(if (oneRow) Modifier.widthIn(max = 260.dp) else Modifier).clip(shape)
        .background(if (done) Palette.accentFill else Palette.field).hcBorder(shape)
        .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = if (done) "$words, $state" else words }
        .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (pic.isNotEmpty()) Text(pic, style = Type.big.copy(fontSize = if (big) 30.sp else 24.sp), maxLines = 1, modifier = Modifier.padding(end = 8.dp))
        Column {
            Text(words, style = Type.big.copy(fontSize = if (big) 19.sp else 16.sp), maxLines = if (oneRow) 1 else 3, overflow = TextOverflow.Ellipsis)
            if (done) Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == "Sent") { MIcon("check", Palette.online, 16.dp); Spacer(Modifier.width(4.dp)) }
                Text(state, style = Type.small.copy(color = Palette.textMuted))
            }
        }
    }
}

/**
 * A 56dp round button: [filled] gold (Send, the mic), [on] lit (the quick messages or More open), else plain. Only a
 * [toggle] says whether it's open; the others are plain buttons to TalkBack.
 */
@Composable
private fun RoundIcon(icon: String, label: String, filled: Boolean = false, on: Boolean = false, toggle: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> Palette.panelLow; filled -> Palette.gold; on -> Palette.accentFill; else -> Color.Transparent }
    val tint = when { !enabled -> Palette.textFaint; filled -> Palette.onGold; on -> Palette.accent; else -> Palette.textMuted }
    Box(Modifier.size(TOUCH).clip(CircleShape).background(bg).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; if (toggle) stateDescription = if (on) "Open" else "Closed" }, contentAlignment = Alignment.Center) {
        MIcon(icon, tint, 26.dp)
    }
}

/**
 * My colors: the same Theme, Seasonal and accent choices as Settings › Look, and its text sizes, for the kid. Hidden while
 * a parent turned Let the kid choose off (it then closes by itself).
 */
@Composable
private fun MyColors(app: Muninn, cfg: TvConfig, onClose: () -> Unit) {
    if (!KidLook.kidMayPick(cfg)) { LaunchedEffect(Unit) { onClose() }; return }
    val now = KidTheme(Access.look, Seasons.choice(Access.season), Access.accent)
    val label = Type.big.copy(fontSize = 17.sp)
    KidDialog(tv = false, onBack = onClose) {
        MIcon("palette", Palette.goldBright, 44.dp)
        Text("My colors", style = Type.big.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp))
        Text("Theme", style = label, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        val looks = Looks.ALL
        ChoiceRow(looks.size, Modifier.padding(vertical = 6.dp), minWidth = 100.dp) { i, m ->
            val (k, n) = looks[i]
            MButton(n, m, if (now.look == k) Kind.Accent else Kind.Normal) { putKidTheme(app, now.copy(look = k)) }
        }
        Text("Seasonal", style = label, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        val seasons = Seasons.CHOICES
        ChoiceRow(seasons.size, Modifier.padding(vertical = 6.dp), minWidth = 120.dp, max = 2) { i, m ->
            val (k, n) = seasons[i]
            MButton(n, m, if (now.season == k) Kind.Accent else Kind.Normal) { putKidTheme(app, now.copy(season = k)) }
        }
        Text("Color", style = label, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        // Only the colors that suit the Color vision in use, as Settings › Look shows them; each with its name, so they
        // can be told apart in high contrast or No color too.
        val names = ColorVision.accents(Access.colorVision)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            names.forEach { n ->
                val on = Palette.accentKey == n
                val c = Palette.accentOf(n)
                val title = Accents.TITLES.getOrElse(Accents.NAMES.indexOf(n)) { n }
                Column(Modifier.clip(RoundedCornerShape(CARD)).clickable(role = Role.RadioButton) { putKidTheme(app, now.copy(accent = n)) }
                    .semantics(mergeDescendants = true) { contentDescription = title; selected = on }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(TOUCH).clip(CircleShape).background(c).border(if (on) 4.dp else 1.dp, if (on) Palette.text else Palette.line, CircleShape),
                        contentAlignment = Alignment.Center) {
                        if (on) MIcon("check", Palette.onAccentOf(c), 26.dp, weight = 2.2f)
                    }
                    Text(title, style = Type.small.copy(color = if (on) Palette.text else Palette.textMuted), maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        // k14: Muninn's text size, as Settings › Look has it (a parent's pick goes on again when they change it).
        Text("Text size", style = label, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        val sizes = KidK14.TEXT_SIZES
        ChoiceRow(sizes.size, Modifier.padding(vertical = 6.dp), minWidth = 80.dp) { i, m ->
            val f = sizes[i] / 100f
            MButton("${sizes[i]}%", m, if (Access.text == f) Kind.Accent else Kind.Normal) { Access.text = f; app.identity.textSize = f }
        }
        MButton("Done", Modifier.fillMaxWidth().padding(top = 14.dp), Kind.Accent) { onClose() }
    }
}
