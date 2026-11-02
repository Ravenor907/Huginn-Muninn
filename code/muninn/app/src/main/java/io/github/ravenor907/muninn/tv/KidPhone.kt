@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.ravenor907.muninn.tv

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
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
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * KidUi.TEXT_BOOST), LocalRoundIcons provides true) {
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
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            val tablet = LocalConfiguration.current.smallestScreenWidthDp >= io.github.ravenor907.muninn.screens.ChatNav.TABLET_SW_DP
            val wide = tablet && maxWidth >= io.github.ravenor907.muninn.screens.ChatNav.WIDE_MIN
            if (wide) Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(minOf(400.dp, maxWidth * 0.4f)).fillMaxHeight().background(Palette.panelLow)) {
                    KidHome(app, cfg, quiet, person?.k, onParent)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.hairline))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (person != null) key(person.k) { KidTalk(app, cfg, person, wide = true) { TvNav.home() } }
                    else PickNote()
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

/** A tablet with no conversation open. */
@Composable
private fun PickNote() {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        MIcon("chat", Palette.textFaint, 48.dp)
        Text("Pick a chat", style = Type.normal.copy(color = Palette.textMuted, fontSize = 18.sp), modifier = Modifier.padding(top = 12.dp))
    }
}

/** Home: the greeting card, the quiet time banner, the open tab, then the bottom tabs. */
@Composable
private fun KidHome(app: Muninn, cfg: TvConfig, quiet: QuietTime?, openKey: String?, onParent: () -> Unit) {
    // Back here does nothing: a kid can't back out into anything else.
    BackHandler(enabled = true) {}
    Column(Modifier.fillMaxSize()) {
        GreetingCard(app, cfg, onParent)
        if (quiet != null) QuietLine(quiet, tv = false, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth())
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

/**
 * The top card, where Muninn has the raven, its name and Settings: the raven, "Hi, Ava" with a little sparkle, My colors
 * (unless a parent locked it) and "For a parent".
 */
@Composable
private fun GreetingCard(app: Muninn, cfg: TvConfig, onParent: () -> Unit) {
    val shape = RoundedCornerShape(CARD)
    val name = cfg.kname.ifEmpty { app.identity.username }
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
    val chip = KidPhoneNav.filter
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
                KidUi.FILTERS.forEach { (id, label) ->
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
 * there's no box: the quick messages are the composer, open, with big chips.
 */
@Composable
private fun KidTalk(app: Muninn, cfg: TvConfig, p: TvPerson, wide: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val convs by app.chat.conversations.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    // A person's messages, or a group's (someone else's text says who wrote it).
    val msgs = KidChats.msgs(p.k, convs, gmsgs)
    val group = KidChats.isGroup(p.k)
    val playing by Voice.playing.collectAsState()
    var viewing by remember { mutableStateOf<String?>(null) }
    /** A video playing full screen, or a poll open (their message ids). */
    var video by remember { mutableStateOf<String?>(null) }
    var poll by remember { mutableStateOf<String?>(null) }
    val openedAt = remember { System.currentTimeMillis() }
    var reading by remember { mutableStateOf<String?>(null) }
    var text by remember(p.k) { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf("") }
    val mic = remember { TvAudio.hasMic(context) }
    // k11: typing, photos and voice messages only when a parent chose that chatting level.
    val compose = KidUi.compose(cfg.chat, mic)
    var tray by remember(p.k) { mutableStateOf(compose.trayOpen) }

    fun micOn() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun finishRecording() {
        if (!recording || sending) return
        sending = true
        // Not tied to this screen: a clip on its way is still sent if the screen goes.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val r = TvRecorder.finish()
            sending = false
            recording = false
            if (r != null) { KidChats.sendVoice(app, p.k, r.first, r.second); Ui.say("Sent") } else Ui.say(TvWords.TOO_SHORT, true)
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
        if (t.isNotEmpty()) { KidChats.sendText(app, p.k, t); text = "" }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { Photos.prepare(context, uri, false) }
            if (jpeg == null) Ui.say("That photo can't be sent", true)
            else { withContext(Dispatchers.IO) { KidChats.sendPhoto(app, p.k, jpeg) }; Ui.say("Sent") }
        }
    }

    DisposableEffect(p.k) {
        app.openChat = p.k
        TvRecorder.onLimit = { finishRecording() }
        onDispose {
            if (app.openChat == p.k) app.openChat = ""
            TvRecorder.onLimit = null
            if (TvRecorder.recording.value) TvRecorder.cancel()
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
    LaunchedEffect(sent) { if (sent.isNotEmpty()) { delay(2000); sent = "" } }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // A tablet keeps the list beside the conversation, so there's nothing to go back to.
            if (!wide) IconAction("back", "Back to chats", Palette.text, Modifier.size(TOUCH), size = 26.dp) { onBack() }
            else Spacer(Modifier.width(12.dp))
            io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, AVATAR)
            Text(p.n, style = Type.big.copy(fontSize = 20.sp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp).semantics { heading() })
            if (cfg.calls && !group) Row(Modifier.heightIn(min = TOUCH).clip(RoundedCornerShape(TOUCH / 2)).background(Palette.gold)
                .clickable(role = Role.Button) { kidCall(app, context, p.k) }.semantics(mergeDescendants = true) { contentDescription = "Call ${p.n}" }
                .padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon("call", Palette.onGold, 22.dp)
                Text("Call", style = Type.big.copy(color = Palette.onGold, fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        // The newest at the bottom.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A tap on their message reads it out loud, for a child who can't read yet.
            items(msgs.takeLast(60).asReversed()) { m ->
                TalkBubble(app, m, playing, onText = { reading = m.id }, onVideo = { video = m.id }, onPoll = { poll = m.id },
                    speak = { KidSpeak.say(context, m.text) }, corner = BUBBLE) { viewing = m.id }
            }
        }
        // Quick messages: one tap sends, and the chip says Sent for a moment. The composer at "Quick messages only".
        if (tray || !compose.text) QuickTray(cfg.quick, compose.bigChips, sent) { q ->
            if (sent != q.id) { KidChats.sendText(app, p.k, q.text); sent = q.id }
        }
        if (!compose.text && cfg.quick.isEmpty()) Text("Ask a parent to add quick messages", style = Type.normal.copy(fontSize = 17.sp),
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (compose.text || compose.photo || compose.voice) Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom) {
            if (cfg.quick.isNotEmpty()) RoundIcon("smile", if (tray) "Hide quick messages" else "Quick messages", on = tray) { tray = !tray }
            if (compose.photo) RoundIcon("image", "Send a photo") {
                try { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (_: Exception) { Ui.say(TvWords.ASK_PARENT, true) }
            }
            if (compose.text) {
                val shape = RoundedCornerShape(TOUCH / 2)
                val body = TextStyle(fontFamily = Type.body, fontSize = 17.sp, color = Palette.text)
                Box(Modifier.weight(1f).padding(horizontal = 6.dp).heightIn(min = TOUCH).clip(shape).background(Palette.field).hcBorder(shape)
                    .padding(horizontal = 18.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) Text("Type a message", style = body.copy(color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(text, { text = it.take(2000) }, textStyle = body, cursorBrush = SolidColor(Palette.accent), maxLines = 6,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Type a message" })
                }
            } else Spacer(Modifier.weight(1f))
            when {
                compose.text && text.isNotBlank() -> RoundIcon("send", "Send", filled = true) { sendTyped() }
                compose.voice -> RoundIcon("mic", "Voice message", filled = true) { record() }
                compose.text -> RoundIcon("send", "Send", filled = true, enabled = false) {}
            }
        }
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

/** The quick messages as round chips that wrap; [big] at "Quick messages only". Scrolls when there are many. */
@Composable
private fun QuickTray(quick: List<Quick>, big: Boolean, sent: String, onSend: (Quick) -> Unit) {
    if (quick.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().heightIn(max = if (big) 360.dp else 220.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        quick.forEach { q ->
            val done = sent == q.id
            val shape = RoundedCornerShape(BUBBLE)
            Row(Modifier.heightIn(min = if (big) 64.dp else 48.dp).then(if (big) Modifier.widthIn(min = 140.dp) else Modifier).clip(shape)
                .background(if (done) Palette.accentFill else Palette.field).hcBorder(shape)
                .clickable(role = Role.Button) { onSend(q) }.semantics(mergeDescendants = true) { contentDescription = if (done) "Sent" else q.text }
                .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (done) { MIcon("check", Palette.online, 22.dp); Spacer(Modifier.width(6.dp)) }
                Text(if (done) "Sent" else q.text, style = Type.big.copy(fontSize = if (big) 19.sp else 16.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A 56dp round button: [filled] gold (Send, the mic), [on] lit (the quick messages open), else plain. */
@Composable
private fun RoundIcon(icon: String, label: String, filled: Boolean = false, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val bg = when { !enabled -> Palette.panelLow; filled -> Palette.gold; on -> Palette.accentFill; else -> Color.Transparent }
    val tint = when { !enabled -> Palette.textFaint; filled -> Palette.onGold; on -> Palette.accent; else -> Palette.textMuted }
    Box(Modifier.size(TOUCH).clip(CircleShape).background(bg).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label; selected = on }, contentAlignment = Alignment.Center) {
        MIcon(icon, tint, 26.dp)
    }
}

/**
 * My colors: the same Theme, Seasonal and accent choices as Settings › Look, for the kid. Hidden while a parent turned
 * Let the kid choose off (it then closes by itself).
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
        // Only the colors that suit the Color vision in use, as Settings › Look shows them.
        val names = ColorVision.accents(Access.colorVision)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            names.forEach { n ->
                val on = Palette.accentKey == n
                val c = Palette.accentOf(n)
                val title = Accents.TITLES.getOrElse(Accents.NAMES.indexOf(n)) { n }
                Box(Modifier.size(TOUCH).clip(CircleShape).background(c).border(if (on) 4.dp else 1.dp, if (on) Palette.text else Palette.line, CircleShape)
                    .clickable(role = Role.RadioButton) { putKidTheme(app, now.copy(accent = n)) }
                    .semantics { contentDescription = title; selected = on }, contentAlignment = Alignment.Center) {
                    if (on) MIcon("check", Palette.onAccentOf(c), 26.dp, weight = 2.2f)
                }
            }
        }
        MButton("Done", Modifier.fillMaxWidth().padding(top = 14.dp), Kind.Accent) { onClose() }
    }
}
