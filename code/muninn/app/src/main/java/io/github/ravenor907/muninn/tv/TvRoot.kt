package io.github.ravenor907.muninn.tv

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.painterResource
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Voice
import io.github.ravenor907.muninn.screens.Qr
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The kids' TV screens: 1920 x 1080 is 960 x 540 dp.
// Everything works with the D-pad, OK and Back alone: every action is a focusable tile, and focus shows as a
// gold ring (never by color alone: the ring is 4 dp wide and the tile grows a little). Nothing a child reads is
// smaller than 20 sp. There are no parent screens on the TV: a parent sets everything from their phone.

// A warm dark shade for a focused tile and their messages; on parchment (Light) the look's own light shades, so the
// dark text stays readable.
private val TileFocus get() = if (Palette.light) Palette.focusFill else Color(0xFF1D1A14)
private val TheirBubble get() = if (Palette.light) Palette.field else Color(0xFF1D1A14)

internal fun tvText(size: TextUnit, color: Color = Palette.text, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Type.body, fontSize = size, fontWeight = weight, color = color)

internal fun tvTitle(size: TextUnit) = TextStyle(fontFamily = Type.display, fontWeight = FontWeight.Bold, fontSize = size, letterSpacing = 1.sp, color = Palette.text)

/**
 * Which conversation the TV shows. Kept outside the screen so MainActivity can set it: opening Muninn
 * fresh (the launcher, not a notification) lands on the people tiles, and a message notification or banner
 * opens that person.
 */
object TvNav {
    /** The person whose conversation is open ("": the people tiles). */
    var open by mutableStateOf("")
    /** The last one opened, for the focus on the home screen. */
    var last by mutableStateOf("")
    fun home() { open = "" }
    fun show(key: String) { open = key; last = key }
}

/** The whole app on a Kids' TV: pairing until a parent sets it up, then the kids' screens. No lock, no sign in. */
@Composable
fun TvRoot(app: Muninn) {
    val ready by app.ready.collectAsState()
    Box(Modifier.fillMaxSize().background(Palette.ground)) {
        // The Seasonal look's leaves or snow fall behind everything (nothing is drawn in the other looks).
        io.github.ravenor907.muninn.ui.SeasonFall()
        if (!ready) TvStarting(app)
        else {
            val st by KidTv.state.collectAsState()
            val wiping by KidTv.wiping.collectAsState()
            val cfg = st.cfg
            when {
                wiping -> TvNote("Starting over…")
                st.parents.isEmpty() -> TvPairing(app)
                // Paired, but its settings couldn't be read: still a Kids' TV; the parents' phones send them again.
                cfg == null -> TvNote("Getting ready", TvWords.ASK_PARENT)
                else -> TvKids(app, cfg)
            }
        }
        TvToast(Modifier.align(Alignment.BottomCenter))
        val ask by Ui.ask.collectAsState()
        ask?.let { AskDialog(it) }
    }
}

@Composable
private fun TvStarting(app: Muninn) {
    TvNote("Starting…")
    LaunchedEffect(Unit) {
        delay(1500)
        // Without a password the vault opens with the key store's key. If that key is gone, nothing saved
        // can be read: start the TV over so a parent can pair it again.
        if (!app.ready.value) { if (app.vault.openDefault()) app.start() else KidTv.startOver(app) }
    }
}

@Composable
private fun TvNote(title: String, detail: String = "") {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = tvTitle(34.sp), textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) Text(detail, style = tvText(24.sp, Palette.textMuted), modifier = Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun TvToast(modifier: Modifier) {
    val toast by Ui.toast.collectAsState()
    LaunchedEffect(toast) { if (toast != null) { delay(4500); Ui.toast.value = null } }
    toast?.let { (text, bad) ->
        Row(modifier.padding(24.dp).clip(RoundedCornerShape(13.dp)).background(if (bad) Palette.dangerFill else Palette.raised).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MIcon(if (bad || text != "Sent") "info" else "check", if (bad) Palette.danger else Palette.goldBright, 30.dp)
            Spacer(Modifier.width(14.dp))
            Text(text, style = tvText(24.sp, if (bad) Palette.danger else Palette.text))
        }
    }
}

// ------------------------------------------------------------------ pairing

private const val HOLD_MS = 5_000L

/**
 * The pairing screen: the big code a parent scans with their phone's camera. Holding Back for 5 seconds (no hint on
 * screen) offers to run Muninn normally instead, only while no parent has ever paired this TV.
 */
@Composable
private fun TvPairing(app: Muninn) {
    var code by remember { mutableStateOf(try { KidTv.pairingCode(app) } catch (e: Exception) { "" }) }
    // The invite in the code lasts an hour: show a fresh one before it runs out.
    LaunchedEffect(Unit) { while (true) { delay(50 * 60_000L); try { code = KidTv.pairingCode(app) } catch (_: Exception) {} } }
    // When Back went down (0: up; -1: the hold already counted, until it comes up).
    var backDown by remember { mutableLongStateOf(0L) }
    val root = remember { FocusRequester() }
    LaunchedEffect(backDown) {
        if (backDown <= 0L) return@LaunchedEffect
        delay(HOLD_MS)
        if (backDown > 0L) {
            backDown = -1L
            if (!KidMode.paired(app)) Ui.sure(Ask("Use Muninn normally?", "This TV gets a lock and Settings.", "Use normally") { KidMode.useNormally(app) })
        }
    }
    BackHandler {}
    Raven(Modifier)
    Column(Modifier.fillMaxSize().focusRequester(root)
        .onPreviewKeyEvent { e ->
            if (e.key != Key.Back) return@onPreviewKeyEvent false
            when (e.type) {
                KeyEventType.KeyDown -> {
                    // A fresh press starts the count (after a dialog took the last release, too); held repeats don't.
                    if (backDown == 0L || (backDown < 0L && e.nativeKeyEvent.repeatCount == 0)) backDown = System.currentTimeMillis()
                    true
                }
                KeyEventType.KeyUp -> { backDown = 0L; true }
                else -> true
            }
        }
        .focusable().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (code.isNotEmpty()) Qr(Contacts.link(code), 380.dp)
        Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon("phone", Palette.goldBright, 36.dp)
            Spacer(Modifier.width(14.dp))
            Text("Parent: scan with phone camera", style = tvTitle(30.sp))
        }
    }
    LaunchedEffect(Unit) { delay(80); try { root.requestFocus() } catch (_: Exception) {} }
}

// ------------------------------------------------------------------ the kids' screens

@Composable
private fun TvKids(app: Muninn, cfg: TvConfig) {
    val open = TvNav.open
    val person = cfg.contacts.firstOrNull { it.k == open }
    val context = LocalContext.current
    // What's still off on the TV, checked every few seconds (a parent's request may have opened Android's settings):
    // an update waiting for "install" goes in once it's allowed, and a change is reported to the parents' phones.
    LaunchedEffect(Unit) {
        var last: Set<String>? = null
        while (true) {
            val now = TvUpdate.needs(context).map { it.id }.toSet()
            if ("install" !in now) TvUpdate.resume(context)
            if (last != null && now != last) KidTv.permsChanged(app)
            last = now
            delay(3000)
        }
    }
    // Someone a parent just took off the list: back to the people who are still on it.
    if (person == null) TvHome(app, cfg, TvNav.last) { TvNav.show(it) }
    else TvTalk(app, cfg, person) { TvNav.home() }
    TvAllow(app)
    TvUpdatePrompt()
}

/**
 * A parent asked from their phone for one of the TV's permissions (7.13): Android's own "Allow" prompt comes
 * up, or, for a setting Android only has a page for, that page opens, with one short line saying what to press.
 */
@Composable
private fun TvAllow(app: Muninn) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val asked by KidTv.allowAsk.collectAsState()
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { KidTv.permsChanged(app) }
    fun line(text: String) = try { android.widget.Toast.makeText(context.applicationContext, text, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    LaunchedEffect(asked) {
        val id = asked ?: return@LaunchedEffect
        KidTv.allowAsk.value = null
        delay(300)
        val p = TvUpdate.permission(id)
        when {
            p != null && ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED -> KidTv.permsChanged(app)
            p != null && TvUpdate.settingsOnly(context, activity, p) ->
                if (TvUpdate.openAppPage(context)) line(TvUpdate.line(id, settingsPage = true)) else Ui.say(TvWords.ASK_PARENT, true)
            p != null -> { Ui.say(TvUpdate.line(id, settingsPage = false)); TvUpdate.markAsked(context, p); perm.launch(p) }
            // Notifications need no prompt before Android 13.
            id == "notify" -> KidTv.permsChanged(app)
            else -> if (TvUpdate.open(context, id)) line(TvUpdate.line(id, settingsPage = true)) else Ui.say(TvWords.ASK_PARENT, true)
        }
    }
}

/** A new Muninn is downloaded and Android wants its Install box: OK on the remote brings it up. */
@Composable
private fun TvUpdatePrompt() {
    val context = LocalContext.current
    val st by TvUpdate.state.collectAsState()
    var later by remember { mutableStateOf(false) }
    LaunchedEffect(st) { later = false }
    if (later || st.confirm == null) return
    TvDialog(onBack = { later = true }) {
        MIcon("download", Palette.goldBright, 64.dp)
        Text("New Muninn", style = tvTitle(32.sp), modifier = Modifier.padding(top = 12.dp))
        DialogButtons(listOf("Install" to { TvUpdate.confirm(context) }, "Later" to { later = true }))
    }
}

/** The activity behind [this] (Android asks it whether a permission can still be asked for). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Parents first, then everyone else, each by name. */
private fun ordered(cfg: TvConfig): List<TvPerson> =
    cfg.contacts.sortedWith(compareBy<TvPerson>({ it.k !in cfg.parents }, { it.n.lowercase() }))

private fun unreadCount(list: List<Chat.Msg>, unread: Boolean): Int {
    if (!unread) return 0
    val i = list.indexOfLast { it.mine }
    return list.drop(i + 1).count { !it.mine && it.unsent.isEmpty() }.coerceAtLeast(1)
}

/** Home: only the people the TV can reach, as big tiles, each with a badge for new messages. */
@Composable
private fun TvHome(app: Muninn, cfg: TvConfig, focusKey: String, onOpen: (String) -> Unit) {
    val convs by app.chat.conversations.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val people = remember(cfg) { ordered(cfg) }
    // Back here does nothing: a kid can't back out into anything else.
    BackHandler(enabled = true) {}
    val focusAt = people.indexOfFirst { it.k == focusKey }.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    val list = rememberLazyListState()
    if (people.isEmpty()) {
        Raven(Modifier)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            MIcon(TvWords.icon(TvWords.ASK_PARENT), Palette.goldBright, 72.dp)
            Text(TvWords.ASK_PARENT, style = tvTitle(34.sp), modifier = Modifier.padding(top = 16.dp))
        }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Raven(Modifier.align(Alignment.TopStart))
        LazyRow(Modifier.fillMaxWidth(), state = list,
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp)) {
            itemsIndexed(people, key = { _, p -> p.k }) { i, p ->
                Tile(p, unreadCount(convs[p.k].orEmpty(), p.k in unread), if (i == focusAt) first else null) { onOpen(p.k) }
            }
        }
    }
    LaunchedEffect(people) {
        try { list.scrollToItem(focusAt) } catch (_: Exception) {}
        delay(80)
        try { first.requestFocus() } catch (_: Exception) {}
    }
}

/** The raven logo in the corner, as everywhere in Muninn. */
@Composable
private fun Raven(modifier: Modifier) {
    Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = modifier.padding(start = 48.dp, top = 32.dp).size(28.dp))
}

/** A person on the TV: their picture if they've sent one, else their initial on their colour. */
@Composable
private fun Avatar(p: TvPerson, size: Dp) {
    io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, size)
}

@Composable
private fun Tile(p: TvPerson, count: Int, requester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !Access.reduceMotion) 1.06f else 1f, label = "tile")
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(260.dp, 300.dp).clip(shape).background(if (focused) TileFocus else Palette.panel)
            .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (count > 0) "${p.n}, $count new" else p.n },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Avatar(p, 150.dp)
            Text(p.n, style = tvText(32.sp, weight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp))
        }
        if (count > 0) Box(Modifier.align(Alignment.TopEnd).padding(14.dp).sizeIn(minWidth = 44.dp).height(44.dp)
            .clip(RoundedCornerShape(22.dp)).background(Palette.gold).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(if (count > 9) "9+" else "$count", style = tvText(24.sp, Palette.onGold, FontWeight.Bold))
        }
    }
}

// ------------------------------------------------------------------ talking to one person

private sealed interface Overlay {
    data class Listening(val partial: String) : Overlay
    data class Heard(val text: String) : Overlay
    data object Recording : Overlay
    data object Sending : Overlay
    /** A few words from [TvWords], shown with their icon. */
    data class Problem(val words: String) : Overlay
}

/**
 * One person: their big picture with a green (reachable) or grey dot, the last 3 messages, one row of big
 * buttons (Call, Voice message, Talk to type), and below them up to 4 quick messages. The remote starts on Call.
 */
@Composable
private fun TvTalk(app: Muninn, cfg: TvConfig, p: TvPerson, onBack: () -> Unit) {
    val context = LocalContext.current
    val convs by app.chat.conversations.collectAsState()
    val msgs = convs[p.k].orEmpty().filter { it.unsent.isEmpty() }
    val playing by Voice.playing.collectAsState()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    val speech = remember { TvSpeech(context) }
    val openedAt = remember { System.currentTimeMillis() }
    val mic = remember { TvAudio.hasMic(context) }
    val canDictate = remember { TvAudio.canDictate(context) }
    val dictate = cfg.dictation && canDictate
    var sent by remember { mutableStateOf("") }
    /** A photo shown full screen (its message id); Back goes back to the conversation. */
    var viewing by remember { mutableStateOf<String?>(null) }

    // OK on Send, or the 5-minute limit: "Sending…" while the last of the clip is encoded off the main thread.
    // Not tied to this screen, so a clip on its way is still sent if the screen goes.
    fun finishRecording() {
        if (overlay != Overlay.Recording) return   // OK and the limit at once: sent once
        overlay = Overlay.Sending
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val r = TvRecorder.finish()
            if (r != null) { app.chat.sendVoice(p.k, r.first, r.second); overlay = null; Ui.say("Sent") }
            else overlay = Overlay.Problem(TvWords.TOO_SHORT)
        }
    }
    fun cancel() {
        if (overlay == Overlay.Sending) return   // a clip being sent finishes
        speech.close(); if (TvRecorder.recording.value) TvRecorder.cancel(); overlay = null
    }

    DisposableEffect(p.k) {
        app.openChat = p.k
        TvRecorder.onLimit = { finishRecording() }
        onDispose {
            if (app.openChat == p.k) app.openChat = ""
            TvRecorder.onLimit = null
            speech.close()
            if (TvRecorder.recording.value) TvRecorder.cancel()
            Voice.stopPlaying()
        }
    }
    LaunchedEffect(msgs.size) { app.chat.markRead(p.k) }
    // Whether their app takes calls isn't known until it says so: ask once (protocol 0.6, 1.2).
    LaunchedEffect(p.k) { app.chat.probeCaps(p.k) }
    // A voice message that arrives while this screen is open plays out loud by itself.
    val newest = msgs.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest != null && !newest.mine && newest.voice > 0 && newest.time >= openedAt && overlay == null)
            app.chat.photo(newest.photo)?.let { Voice.play(newest.id, it) }
    }
    LaunchedEffect(playing) { while (playing.isNotEmpty()) { Voice.tick(); delay(200) } }
    LaunchedEffect(sent) { if (sent.isNotEmpty()) { delay(2000); sent = "" } }
    BackHandler { onBack() }

    var afterMic by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val f = afterMic; afterMic = null
        if (ok) f?.invoke() else overlay = Overlay.Problem(TvWords.ASK_PARENT)
    }
    fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else { afterMic = f; TvUpdate.markAsked(context, Manifest.permission.RECORD_AUDIO); micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    fun listen() = withMic {
        Voice.stopPlaying()
        overlay = Overlay.Listening("")
        speech.start(
            partial = { if (overlay is Overlay.Listening) overlay = Overlay.Listening(it) },
            done = { overlay = Overlay.Heard(TvRules.clean(it, 2000)) },
            fail = { overlay = Overlay.Problem(it) },
        )
    }
    fun record() = withMic {
        Voice.stopPlaying()
        overlay = if (TvRecorder.start(context)) Overlay.Recording else Overlay.Problem(TvWords.NO_MIC)
    }
    fun call() {
        when {
            !mic -> overlay = Overlay.Problem(TvWords.NO_MIC)
            io.github.ravenor907.muninn.net.call.Opus.loadError != null -> overlay = Overlay.Problem(TvWords.CANT_CALL)
            !io.github.ravenor907.muninn.net.call.CallManager.canCall(p.k) -> {
                app.chat.probeCaps(p.k)
                overlay = Overlay.Problem(TvWords.CANT_CALL)
            }
            else -> withMic { io.github.ravenor907.muninn.screens.CallActivity.call(context, p.k) }
        }
    }

    val buttons = TvRules.talkButtons(cfg.calls, mic, dictate)
    val quick = cfg.quick.take(TvRules.QUICK_SHOWN)
    val firstId: String? = TvRules.firstTalkFocus(cfg.calls, mic, dictate, quick.map { it.id })
    val focusManager = LocalFocusManager.current
    val first = remember { FocusRequester() }
    fun req(id: String) = if (id == firstId) first else null

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Reachable(app, p)
            Text(p.n, style = tvTitle(34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            // The last messages sit at the bottom; when they're taller than the room left, they scroll and the newest shows.
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.BottomStart) {
                Column(Modifier.verticalScroll(rememberScrollState(), reverseScrolling = true), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    msgs.takeLast(3).forEach { TalkBubble(app, it, playing) { viewing = it.id } }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (id in buttons) when (id) {
                    "call" -> ActionTile("Call", "call", req(id), Modifier.weight(1f)) { call() }
                    "rec" -> ActionTile("Voice message", "mic", req(id), Modifier.weight(1f)) { record() }
                    "say" -> ActionTile("Talk to type", "chat", req(id), Modifier.weight(1f)) { listen() }
                }
            }
            if (quick.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                quick.forEach { q ->
                    QuickTile(if (sent == q.id) "Sent" else q.text, sent == q.id, req(q.id), Modifier.weight(1f)) {
                        app.chat.sendPrivate(p.k, q.text); sent = q.id
                    }
                }
                repeat(TvRules.QUICK_SHOWN - quick.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    LaunchedEffect(firstId) {
        delay(80)
        // No button at all: the first thing that takes focus (a voice message or photo), so the remote has a place to start.
        try { if (firstId != null) first.requestFocus() else focusManager.moveFocus(FocusDirection.Next) } catch (_: Exception) {}
    }

    viewing?.let { id ->
        val photos = msgs.filter { it.photo.isNotEmpty() && it.voice == 0 }
        TvPhoto(app, photos, id, onMove = { viewing = it }) { viewing = null }
    }

    overlay?.let { o ->
        TvDialog(onBack = { cancel() }) {
            when (o) {
                is Overlay.Listening -> {
                    MIcon("chat", Palette.goldBright, 80.dp)
                    Text("Listening…", style = tvTitle(32.sp), modifier = Modifier.padding(top = 14.dp))
                    if (o.partial.isNotEmpty()) Text(o.partial, style = tvText(28.sp, Palette.textSoft), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                    DialogButtons(listOf("Done" to { speech.stop() }))
                }
                is Overlay.Heard -> {
                    Text("“${o.text}”", style = tvText(32.sp, weight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                    // The remote starts on Again, so a stray OK never sends words that came out wrong.
                    DialogButtons(listOf(
                        "Send" to { app.chat.sendPrivate(p.k, o.text); overlay = null; Ui.say("Sent") },
                        "Again" to { listen() },
                    ), focus = 1)
                }
                Overlay.Recording -> {
                    val ms by produceState(0L) { while (true) { value = TvRecorder.elapsed(); delay(250) } }
                    // The press that opened this can't send it: Send and Stop take OK after 2 seconds.
                    val armed = TvRules.recordingArmed(ms)
                    MIcon("mic", Palette.danger, 110.dp)
                    Text(Voice.length(ms.toInt()), style = tvTitle(56.sp), modifier = Modifier.padding(top = 10.dp))
                    DialogButtons(listOf(
                        "Send" to { if (TvRules.recordingArmed(TvRecorder.elapsed())) finishRecording() },
                        "Stop" to { if (TvRules.recordingArmed(TvRecorder.elapsed())) cancel() },
                    ), enabled = armed)
                }
                Overlay.Sending -> {
                    MIcon("send", Palette.goldBright, 80.dp)
                    Text("Sending…", style = tvTitle(32.sp), modifier = Modifier.padding(top = 14.dp))
                }
                is Overlay.Problem -> {
                    MIcon(TvWords.icon(o.words), Palette.danger, 80.dp)
                    Text(o.words, style = tvTitle(34.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                    DialogButtons(listOf("OK" to { overlay = null }))
                }
            }
        }
    }
}

/** Their big picture, with a green dot when they can be reached now and a grey one when not. */
@Composable
private fun Reachable(app: Muninn, p: TvPerson) {
    val route by produceState(app.chat.route(p.k), p.k) { while (true) { delay(5000); value = app.chat.route(p.k) } }
    val on = route == "close" || route == "wifi" || route == "internet"
    Box(Modifier.size(150.dp).semantics { contentDescription = if (on) "${p.n}, can be reached" else "${p.n}, away" }) {
        Avatar(p, 150.dp)
        Box(Modifier.align(Alignment.BottomEnd).size(40.dp).clip(CircleShape).background(Palette.ground).padding(5.dp)
            .clip(CircleShape).background(if (on) Palette.online else Palette.textFaint))
    }
}

/** One of the big conversation buttons: a large icon above its name. */
@Composable
private fun ActionTile(text: String, icon: String, requester: FocusRequester?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.heightIn(min = 170.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (focused) TileFocus else Palette.field)
            .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MIcon(icon, Palette.goldBright, 64.dp)
        Text(text, style = tvText(24.sp, weight = FontWeight.SemiBold), textAlign = TextAlign.Center, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
    }
}

/** A quick message: OK sends it at once, and it says Sent for a moment. */
@Composable
private fun QuickTile(text: String, done: Boolean, requester: FocusRequester?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    Row(
        modifier.heightIn(min = 96.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (focused) TileFocus else Palette.field)
            .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (done) { MIcon("check", Palette.online, 28.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = tvText(21.sp, weight = FontWeight.SemiBold), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TalkBubble(app: Muninn, m: Chat.Msg, playing: String, onPhoto: () -> Unit) {
    val shape = if (m.mine) RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp) else RoundedCornerShape(14.dp, 14.dp, 14.dp, 4.dp)
    val bg = if (m.mine) Palette.bubbleMine else TheirBubble
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        when {
            m.voice > 0 -> {
                var focused by remember { mutableStateOf(false) }
                val on = playing == m.id
                Row(Modifier.onFocusChanged { focused = it.isFocused }.clip(shape).background(bg)
                    .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
                    .clickable(role = Role.Button) { if (on) Voice.stopPlaying() else app.chat.photo(m.photo)?.let { Voice.play(m.id, it) } }
                    .padding(start = 7.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.gold), contentAlignment = Alignment.Center) {
                        MIcon(if (on) "pausefill" else "play", Palette.onGold, 18.dp, filled = true)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(Voice.length(m.voice), style = tvText(20.sp, Palette.textSoft))
                }
            }
            m.photo.isNotEmpty() -> {
                val bmp = remember(m.photo) { try { app.chat.photo(m.photo)?.let { thumb(it) } } catch (e: Throwable) { null } }
                var focused by remember { mutableStateOf(false) }
                // OK on a photo shows it full screen.
                if (bmp != null) Image(bmp, contentDescription = "Photo. Press OK to see it big.", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(150.dp, 110.dp).onFocusChanged { focused = it.isFocused }.clip(shape)
                        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
                        .clickable(role = Role.Image, onClick = onPhoto))
                else MIcon("image", Palette.textMuted, 40.dp, Modifier.clip(shape).background(bg).padding(10.dp))
            }
            else -> Text(m.text, style = tvText(20.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(shape).background(bg).padding(horizontal = 13.dp, vertical = 9.dp))
        }
    }
}

/** A small copy for the conversation: a big photo decoded whole would take a TV's memory. */
private fun thumb(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
    var sample = 1
    while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= 400) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

/**
 * A photo from the conversation, full screen. Left and Right go to the one before or after, Back (or OK)
 * goes back to the conversation.
 */
@Composable
private fun TvPhoto(app: Muninn, photos: List<Chat.Msg>, id: String, onMove: (String) -> Unit, onClose: () -> Unit) {
    val i = photos.indexOfFirst { it.id == id }
    val m = photos.getOrNull(i) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val context = LocalContext.current
    val bmp = remember(m.photo) {
        try {
            app.chat.photo(m.photo)?.let { bytes ->
                // No bigger than the screen.
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                val screen = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels).coerceAtLeast(1280)
                var sample = 1
                while (maxOf(b.outWidth, b.outHeight) / (sample * 2) >= screen) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        } catch (e: Throwable) { null }
    }
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { photos.getOrNull(i - 1)?.let { onMove(it.id) }; true }
                    Key.DirectionRight -> { photos.getOrNull(i + 1)?.let { onMove(it.id) }; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onClose(); true }
                    else -> false
                }
            }.focusable(), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else MIcon("image", Palette.textMuted, 96.dp)
            if (photos.size > 1) Text("${i + 1} of ${photos.size}", style = tvText(22.sp, Palette.textSoft),
                modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LaunchedEffect(Unit) { delay(60); try { focus.requestFocus() } catch (_: Exception) {} }
    }
}

@Composable
internal fun BigButton(text: String, icon: String? = null, sub: String? = null, enabled: Boolean = true, requester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(17.dp)
    val fg = if (enabled) Palette.text else Palette.textFaint
    Box(
        Modifier.fillMaxWidth().heightIn(min = 95.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape).background(if (enabled) Palette.field else Palette.panelLow)
            .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
            // Stays focusable while it's off, so moving with the D-pad never jumps somewhere unexpected.
            .clickable(role = Role.Button) { if (enabled) onClick() }
            .semantics { if (!enabled) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { MIcon(icon, if (enabled) Palette.goldBright else Palette.textFaint, 36.dp); Spacer(Modifier.width(12.dp)) }
            Column {
                Text(text, style = tvText(26.sp, fg, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = tvText(20.sp, Palette.textMuted))
            }
        }
    }
}

/** A short problem line with its icon (the call screen's "No microphone"). */
@Composable
internal fun TvProblemLine(icon: String, words: String) {
    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, Palette.danger, 40.dp)
        Text(words, style = tvText(26.sp, Palette.danger, FontWeight.SemiBold), modifier = Modifier.padding(start = 12.dp))
    }
}

/** A dialog window of its own, so focus can't wander to the screen behind it. Back closes it. */
@Composable
internal fun TvDialog(onBack: () -> Unit, content: @Composable () -> Unit) {
    // Scrolls when it's taller than the screen; the focused button comes into view.
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(min = 420.dp, max = 720.dp).clip(RoundedCornerShape(20.dp)).background(Palette.panel)
            .verticalScroll(rememberScrollState()).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** A row of big dialog buttons; the remote starts on button [focus]. [enabled] false: they show faded and do nothing. */
@Composable
internal fun DialogButtons(buttons: List<Pair<String, () -> Unit>>, focus: Int = 0, enabled: Boolean = true) {
    val first = remember { FocusRequester() }
    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        buttons.forEachIndexed { i, (label, act) ->
            Box(Modifier.width(if (buttons.size > 2) 190.dp else 240.dp)) {
                BigButton(label, enabled = enabled, requester = if (i == focus.coerceIn(0, buttons.lastIndex)) first else null, onClick = act)
            }
        }
    }
    LaunchedEffect(buttons.map { it.first }) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
}
