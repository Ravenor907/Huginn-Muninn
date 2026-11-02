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
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.rememberCoroutineScope
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

// The Kids' Device screens on a TV: 1920 x 1080 is 960 x 540 dp. (A tablet or phone gets Muninn's own layout, KidPhone.)
// Everything works with the D-pad, OK and Back alone: every action is a focusable tile, and focus shows as a
// gold ring (never by color alone: the ring is 4 dp wide and the tile grows a little). Nothing a child reads is
// smaller than 20 sp. A parent sets everything from their phone; "For a parent" (the lock) needs the parent PIN.

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

/** The whole app on a Kids' Device: pairing until a parent sets it up, then the kids' screens. No lock, no sign in. */
@Composable
fun TvRoot(app: Muninn) {
    val ready by app.ready.collectAsState()
    // A file on its way (an app a parent sends, or a file from someone): a banner in the corner that never takes the
    // focus. Back hides it (that Back does nothing else); it goes by itself a few seconds after the file arrived.
    val file = if (ready) io.github.ravenor907.muninn.screens.transferBanner(app) else null
    Box(Modifier.fillMaxSize().background(Palette.ground).onPreviewKeyEvent { e ->
        val b = file
        if (b == null || e.key != Key.Back) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyUp) io.github.ravenor907.muninn.screens.TransferSeen.hide(b.key)
        true
    }) {
        // The Seasonal look's leaves or snow fall behind everything (nothing is drawn in the other looks).
        io.github.ravenor907.muninn.ui.SeasonFall()
        // A release that kept crashing as it started: only the updater runs, looking for a fixed one (SafeStart).
        if (SafeStart.active) TvNote(SAFE_TITLE, SAFE_LINE)
        else if (!ready) TvStarting(app)
        else {
            val st by KidTv.state.collectAsState()
            val wiping by KidTv.wiping.collectAsState()
            // A plain notice for anything else nearby sharing says; how a transfer ended shows in the file banner.
            LaunchedEffect(Unit) { app.net.messages.collect { (ok, msg) -> if (msg != app.net.finished.value?.second) Ui.say(msg, !ok) } }
            val cfg = st.cfg
            when {
                wiping -> TvNote("Starting over…")
                st.parents.isEmpty() -> TvPairing(app)
                // Paired, but its settings couldn't be read: still a Kids' Device; the parents' phones send them again.
                cfg == null -> TvNote("Getting ready", TvWords.ASK_PARENT)
                else -> TvKids(app, cfg)
            }
        }
        TvToast(Modifier.align(Alignment.BottomCenter))
        file?.let { TvFileBanner(it, Modifier.align(Alignment.TopEnd)) }
        val ask by Ui.ask.collectAsState()
        ask?.let { AskDialog(it) }
    }
}

private const val SAFE_TITLE = "Muninn is waiting for a fix"
private const val SAFE_LINE = "It looks for a new version every hour. A parent can also install Muninn again."

@Composable
private fun TvStarting(app: Muninn) {
    val trouble by app.vault.trouble.collectAsState()
    if (trouble == null) TvNote("Starting…") else TvNote("Muninn can't open its data", "Nothing was deleted. Trying again…")
    LaunchedEffect(Unit) {
        delay(1500)
        // Without a password the vault opens with its data key from Muninn's private files. If that key or the keys
        // file can't be read, nothing is erased or made new: it keeps trying (a read can fail for a moment).
        while (!app.ready.value) {
            if (kotlinx.coroutines.withContext(Dispatchers.IO) { app.vault.openDefault() }) { kotlinx.coroutines.withContext(Dispatchers.Default) { app.start() }; break }
            if (app.vault.trouble.value == null) { KidTv.startOver(app); break }
            delay(5000)
        }
    }
}

@Composable
private fun TvNote(title: String, detail: String = "") {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = tvTitle(34.sp), textAlign = TextAlign.Center)
        if (detail.isNotEmpty()) Text(detail, style = tvText(24.sp, Palette.textMuted), modifier = Modifier.padding(top = 14.dp))
    }
}

/** "game.apk", "Receiving 42%" and a bar; then "File from Dad: game.apk · Saved to Downloads". Never focusable. */
@Composable
private fun TvFileBanner(b: io.github.ravenor907.muninn.net.TransferView.Banner, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.padding(horizontal = 48.dp, vertical = 27.dp).widthIn(max = 460.dp).clip(shape).background(if (b.ok) Palette.raised else Palette.dangerFill)
        .border(2.dp, if (b.ok) Palette.goldBright else Palette.danger, shape).padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(when { !b.ok -> "info"; !b.running -> "check"; b.line.startsWith("Sending") -> "upload"; else -> "download" },
                if (b.ok) Palette.goldBright else Palette.danger, 30.dp)
            Spacer(Modifier.width(12.dp))
            Text(b.title, style = tvText(24.sp, if (b.ok) Palette.text else Palette.danger, FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (b.line.isNotEmpty()) Text(b.line, style = tvText(22.sp, Palette.textSoft), modifier = Modifier.padding(top = 6.dp))
        b.fraction?.let { io.github.ravenor907.muninn.ui.Bar(it, Modifier.padding(top = 10.dp), Palette.gold) }
        Text("Back to hide", style = tvText(20.sp, Palette.textMuted), modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TvToast(modifier: Modifier) {
    val toast by Ui.toast.collectAsState()
    LaunchedEffect(toast) { if (toast != null) { delay(4500); Ui.toast.value = null } }
    toast?.let { (text, bad) ->
        Row(modifier.padding(horizontal = 48.dp, vertical = 27.dp).clip(RoundedCornerShape(13.dp)).background(if (bad) Palette.dangerFill else Palette.raised).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val icon = when { text == "Sent" && !bad -> "check"; text in TvWords.ALL -> TvWords.icon(text); else -> "info" }
            MIcon(icon, if (bad) Palette.danger else Palette.goldBright, 30.dp)
            Spacer(Modifier.width(14.dp))
            Text(text, style = tvText(24.sp, if (bad) Palette.danger else Palette.text))
        }
    }
}

// ------------------------------------------------------------------ pairing

private const val HOLD_MS = 5_000L
/** How soon the pairing screen tries again when its code couldn't be made. */
private const val PAIR_RETRY_MS = 3_000L

/**
 * The pairing screen: the big code a parent scans with their phone's camera. On a TV, holding Back for 5 seconds (no
 * hint on screen) offers to run Muninn normally instead, only while no parent has ever paired it; on a tablet or phone
 * a small button below the code does.
 */
@Composable
private fun TvPairing(app: Muninn) {
    var code by remember { mutableStateOf(try { KidTv.pairingCode(app) } catch (e: Exception) { "" }) }
    /** When the code was made, by the clock (a device asleep overnight doesn't count uptime, the invite's hour does). */
    var madeAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val tv = KidMode.isTv(LocalContext.current)
    fun remake() { try { code = KidTv.pairingCode(app); madeAt = System.currentTimeMillis() } catch (_: Exception) {} }
    // The invite in the code lasts an hour: a fresh one well before it runs out, looked at every minute and on every return
    // to the screen (after the TV slept). A code that couldn't be made is tried again within seconds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(if (code.isEmpty()) PAIR_RETRY_MS else 60_000L)
            if (code.isEmpty() || KidRules.codeStale(madeAt, System.currentTimeMillis())) remake()
        }
    }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && (code.isEmpty() || KidRules.codeStale(madeAt, System.currentTimeMillis()))) remake()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // When Back went down (0: up; -1: the hold already counted, until it comes up).
    var backDown by remember { mutableLongStateOf(0L) }
    val root = remember { FocusRequester() }
    LaunchedEffect(backDown) {
        if (backDown <= 0L) return@LaunchedEffect
        delay(HOLD_MS)
        if (backDown > 0L) {
            backDown = -1L
            if (!KidMode.paired(app)) Ui.sure(Ask("Use Muninn normally?", "This device gets a lock and Settings.", "Use normally") { KidMode.useNormally(app) })
        }
    }
    BackHandler {}
    Raven(Modifier)
    // Centred when it fits; on a phone held sideways at a large size it scrolls, so "Use Muninn normally" stays in reach.
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val fullH = maxHeight
    Column(Modifier.fillMaxWidth().focusRequester(root)
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
        .focusable().verticalScroll(rememberScrollState()).heightIn(min = fullH).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (code.isNotEmpty()) Qr(Contacts.link(code), if (tv) 380.dp else 260.dp)
        else Text("Making the code…", style = tvText(26.sp, Palette.textMuted))
        Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon("phone", Palette.goldBright, if (tv) 36.dp else 28.dp)
            Spacer(Modifier.width(14.dp))
            Text("Parent: in Muninn on your phone, tap +, then Pair a Kids' Device", style = tvTitle(if (tv) 30.sp else 20.sp), textAlign = TextAlign.Center)
        }
        // A tablet or phone has no remote to hold Back on: a small button, until a parent pairs it.
        if (!tv && !KidMode.paired(app)) Text("Not a child's device? Use Muninn normally", style = tvText(16.sp, Palette.textMuted),
            modifier = Modifier.padding(top = 28.dp).clickable(role = Role.Button) {
                Ui.sure(Ask("Use Muninn normally?", "This device gets a lock and Settings.", "Use normally") { KidMode.useNormally(app) })
            }.padding(12.dp))
    }
    }
    LaunchedEffect(Unit) { delay(80); try { root.requestFocus() } catch (_: Exception) {} }
}

// ------------------------------------------------------------------ the kids' screens

@Composable
private fun TvKids(app: Muninn, cfg: TvConfig) {
    val open = TvNav.open
    val groupsAll by app.chat.groups.all.collectAsState()
    // A person on the list, or a group a parent put this device in.
    val person = cfg.contacts.firstOrNull { it.k == open } ?: KidChats.groups(app, groupsAll, null).firstOrNull { it.k == open }
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
    val tv = remember { KidMode.isTv(context) }
    // k11 (7.18): a quiet time on now (checked every half minute): only the parents show, and only they get through.
    val quiet by produceState(KidTv.quietNow(), cfg) { while (true) { value = KidTv.quietNow(); delay(30_000) } }
    val shown = person?.takeIf { KidRules.passes(it.k, cfg.parents, quiet) }
    if (person != null && shown == null) LaunchedEffect(person.k) { TvNav.home() }
    var parent by remember { mutableStateOf(false) }
    // k12: Daily Hávamál turned off: Earlier verses starts over the next time it comes on.
    LaunchedEffect(cfg.hav) { if (!cfg.hav) HavDevice.forgetStart(context) }
    // k13 (7.18): the colors a parent picked go on when they change, or always while the kid may not pick their own.
    // k14: the accessibility a parent picked, the same way.
    LaunchedEffect(cfg) { applyKidLook(app, cfg); applyKidAccess(app, cfg) }
    // k14: a voice message or photo a quiet time held goes once it's over (the quiet time is checked every half minute).
    LaunchedEffect(quiet) { kotlinx.coroutines.withContext(Dispatchers.IO) { KidHoldStore.release(app) } }
    // Someone a parent just took off the list: back to the people who are still on it.
    if (!tv) KidPhone(app, cfg, shown, quiet) { parent = true }
    else if (shown == null) TvHome(app, cfg, TvNav.last, quiet, onParent = { parent = true }) { TvNav.show(it) }
    else TvTalk(app, cfg, shown) { TvNav.home() }
    if (parent) KidParentArea(app, cfg, tv) { parent = false }
    TvAllow(app)
    TvUpdatePrompt(tv)
    KidCallBar(tv)
}

/**
 * A call ringing or on while the kids' screens show (the call screen went behind, or came up over nothing): one big
 * button back to it, where Answer or Hang up is. On a TV it stays until the call is answered or over; on a tablet or
 * phone Back puts it away for this call.
 */
@Composable
private fun KidCallBar(tv: Boolean) {
    val call by io.github.ravenor907.muninn.net.call.CallManager.call.collectAsState()
    val c = call
    var hidden by remember { mutableStateOf("") }
    if (c == null || !KidCallBack.live(c.phase) || hidden == c.callId) return
    val context = LocalContext.current
    val ringing = c.phase == io.github.ravenor907.muninn.net.call.CallSession.Phase.INCOMING
    TvDialog(onBack = { if (tv) io.github.ravenor907.muninn.screens.CallActivity.show(context) else hidden = c.callId }) {
        MIcon("call", Palette.goldBright, 72.dp)
        Text(if (ringing) "${c.name} is calling" else "On a call with ${c.name}", style = tvTitle(if (tv) 34.sp else 24.sp),
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        DialogButtons(listOf((if (ringing) "Go to the call" else "Back to the call") to { io.github.ravenor907.muninn.screens.CallActivity.show(context) }))
    }
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
    // A tablet or phone in Doze can hold messages and calls back for minutes: at pairing, with the parent there, Android
    // is asked to let Muninn run (a TV doesn't sleep that way).
    fun askBattery() {
        if (!KidMode.isTv(context) && !io.github.ravenor907.muninn.net.MessageService.unrestricted(context))
            io.github.ravenor907.muninn.net.MessageService.askUnrestricted(context)
    }
    val several = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { KidTv.permsChanged(app); askBattery() }
    fun line(text: String) = try { android.widget.Toast.makeText(context.applicationContext, text, android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    // Just paired: the microphone and notifications are asked for at once, while the parent is still at the TV.
    val setup by KidTv.setupAsk.collectAsState()
    LaunchedEffect(setup) {
        if (!setup) return@LaunchedEffect
        delay(800)
        val notifyPrompt = android.os.Build.VERSION.SDK_INT >= 33
        val asks = TvRules.setupAsks(TvAudio.hasMic(context), granted(Manifest.permission.RECORD_AUDIO),
            notifyPrompt, notifyPrompt && granted(Manifest.permission.POST_NOTIFICATIONS))
        KidTv.setupAsked(context)
        val perms = asks.mapNotNull { TvUpdate.permission(it) }
        if (perms.isEmpty()) { askBattery(); return@LaunchedEffect }
        perms.forEach { TvUpdate.markAsked(context, it) }
        Ui.say(SETUP_LINE)
        try { several.launch(perms.toTypedArray()) } catch (_: Exception) {}
    }
    LaunchedEffect(asked) {
        val id = asked ?: return@LaunchedEffect
        KidTv.allowAsk.value = null
        delay(300)
        val p = TvUpdate.permission(id)
        when {
            p != null && ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED -> KidTv.permsChanged(app)
            // Android's page for Muninn also has Clear storage and Uninstall: it opens only while a parent has the settings open.
            p != null && TvUpdate.settingsOnly(context, activity, p) ->
                if (!KidTv.unlocked()) Ui.say(ALLOW_LOCKED, true)
                else if (TvUpdate.openAppPage(context)) line(TvUpdate.line(id, settingsPage = true)) else Ui.say(TvWords.ASK_PARENT, true)
            p != null -> { Ui.say(TvUpdate.line(id, settingsPage = false)); TvUpdate.markAsked(context, p); perm.launch(p) }
            // Notifications need no prompt before Android 13.
            id == "notify" -> KidTv.permsChanged(app)
            else -> if (TvUpdate.open(context, id)) line(TvUpdate.line(id, settingsPage = true)) else Ui.say(TvWords.ASK_PARENT, true)
        }
    }
}

/** A permission Android only turns on from its page for Muninn, while the parent settings are closed. */
private const val ALLOW_LOCKED = "A parent can turn it on: For a parent, then Android settings for Muninn"

/** The one line the TV shows while Android asks for its permissions right after pairing. */
private const val SETUP_LINE = "Parent: press Allow so the kids can talk"

/**
 * A new Muninn is downloaded and can't go in by itself (Android wants its Install box, or Muninn may not install apps
 * yet): the whole screen says "Ask a grown-up: press OK to update", and OK brings up Android's box (or the setting).
 * Never during a call. Later hides it for half an hour.
 */
@Composable
private fun TvUpdatePrompt(tv: Boolean) {
    val context = LocalContext.current
    val st by TvUpdate.state.collectAsState()
    val call by io.github.ravenor907.muninn.net.call.CallManager.call.collectAsState()
    var later by remember { mutableStateOf(false) }
    LaunchedEffect(st) { later = false }
    LaunchedEffect(later) { if (later) { delay(30 * 60_000L); later = false } }
    if (later || !TvUpdate.prompting(st, call != null)) return
    Dialog(onDismissRequest = { later = true }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Palette.ground).padding(48.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            MIcon("download", Palette.goldBright, 96.dp)
            Text(if (tv) TvUpd.TV_PROMPT else TvUpd.TOUCH_PROMPT, style = tvTitle(if (tv) 40.sp else 28.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
            if (tv) DialogButtons(listOf("Update" to { TvUpdate.confirm(context) }, "Later" to { later = true }))
            else Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                io.github.ravenor907.muninn.ui.MButton("Update", kind = io.github.ravenor907.muninn.ui.Kind.Accent) { TvUpdate.confirm(context) }
                io.github.ravenor907.muninn.ui.MButton("Later") { later = true }
            }
        }
    }
}

/** The activity behind [this] (Android asks it whether a permission can still be asked for). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun unreadCount(list: List<Chat.Msg>, unread: Boolean): Int {
    if (!unread) return 0
    val i = list.indexOfLast { it.mine }
    return list.drop(i + 1).count { !it.mine && it.unsent.isEmpty() }.coerceAtLeast(1)
}

/**
 * Home: only the people the TV can reach (only parents during a quiet time), as big tiles, each with a badge for new
 * messages. Up from the tiles reaches the lock, "For a parent".
 */
@Composable
private fun TvHome(app: Muninn, cfg: TvConfig, focusKey: String, quiet: QuietTime?, onParent: () -> Unit, onOpen: (String) -> Unit) {
    val convs by app.chat.conversations.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val groupsAll by app.chat.groups.all.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    val unreadGroups by app.chat.groups.unreadGroups.collectAsState()
    // The people, then the groups a parent put the device in (none during a quiet time).
    val people = remember(cfg, quiet, groupsAll) { kidPeople(cfg, quiet) + KidChats.groups(app, groupsAll, quiet) }
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
        // "For a parent" stays in reach with nobody on the list.
        Box(Modifier.fillMaxSize()) { ParentLock(tv = true, modifier = Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 48.dp), onClick = onParent) }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Raven(Modifier.align(Alignment.TopStart))
        ParentLock(tv = true, modifier = Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 48.dp), onClick = onParent)
        if (quiet != null) QuietLine(quiet, tv = true, modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        LazyRow(Modifier.fillMaxWidth(), state = list,
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp)) {
            itemsIndexed(people, key = { _, p -> p.k }) { i, p ->
                Tile(p, KidChats.unread(p.k, KidChats.msgs(p.k, convs, gmsgs), unread, unreadGroups), if (i == focusAt) first else null) { onOpen(p.k) }
            }
            // k12 (7.18): Daily Hávamál, when a parent turned it on: today's verse as the last tile.
            if (cfg.hav) item(key = "havamal") { HavTile(cfg) }
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
 * buttons (Call, Voice message, Say it), and below them up to 4 quick messages. The remote starts on Call.
 */
@Composable
private fun TvTalk(app: Muninn, cfg: TvConfig, p: TvPerson, onBack: () -> Unit) {
    val context = LocalContext.current
    val convs by app.chat.conversations.collectAsState()
    val gmsgs by app.chat.groups.messages.collectAsState()
    // A person's messages, or a group's (someone else's text says who wrote it).
    val msgs = KidChats.msgs(p.k, convs, gmsgs)
    val playing by Voice.playing.collectAsState()
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    /** A video playing full screen, or a poll open (their message ids). */
    var video by remember { mutableStateOf<String?>(null) }
    var poll by remember { mutableStateOf<String?>(null) }
    val speech = remember { TvSpeech(context) }
    val openedAt = remember { System.currentTimeMillis() }
    val mic = remember { TvAudio.hasMic(context) }
    val canDictate = remember { TvAudio.canDictate(context) }
    // k11: voice messages and Say it only when a parent chose that chatting level.
    val dictate = cfg.dictation && canDictate && KidRules.canSend(cfg.chat, "say")
    val voice = mic && KidRules.canSend(cfg.chat, "voice")
    var sent by remember { mutableStateOf("") }
    var sentAt by remember { mutableLongStateOf(0L) }
    /** A photo shown full screen (its message id); Back goes back to the conversation. */
    var viewing by remember { mutableStateOf<String?>(null) }
    /** A long message shown whole (its message id); Back or OK goes back to the conversation. */
    var reading by remember { mutableStateOf<String?>(null) }

    // OK on Send, or the 5-minute limit: "Sending…" while the last of the clip is encoded off the main thread.
    // Not tied to this screen, so a clip on its way is still sent if the screen goes.
    fun finishRecording() {
        if (overlay != Overlay.Recording) return   // OK and the limit at once: sent once
        overlay = Overlay.Sending
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val r = TvRecorder.finish()
            if (r != null) { overlay = null; Ui.say(KidChats.sendOrHold(app, p.k, "voice", r.first, r.second)) }
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
    // Muninn on screen: Home, the TV going off or a show in front pause this conversation (nothing plays, nothing is marked
    // read, and a recording or Say it in progress is dropped, never sent).
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var shown by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> shown = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> shown = false
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { cancel(); Voice.stopPlaying() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // A call coming in (or going out) drops a recording in progress: the call has the microphone.
    val callNow by io.github.ravenor907.muninn.net.call.CallManager.call.collectAsState()
    LaunchedEffect(callNow != null) { if (callNow != null) cancel() }
    // Seen only while on screen: back on screen, what came meanwhile is marked read.
    LaunchedEffect(msgs.size, shown) { if (shown) KidChats.markRead(app, p.k) }
    // Whether their app takes calls isn't known until it says so: ask once (shared/docs/protocol.md 1.2).
    LaunchedEffect(p.k) { if (!KidChats.isGroup(p.k)) app.chat.probeCaps(p.k) }
    // A voice message that arrives while this screen is on screen plays out loud by itself (one that came while Muninn was
    // away plays when it's back).
    val newest = msgs.lastOrNull()
    LaunchedEffect(newest?.id, shown) {
        if (shown && newest != null && !newest.mine && newest.voice > 0 && newest.time >= openedAt && overlay == null && callNow == null)
            app.chat.photo(newest.photo)?.let { Voice.play(newest.id, it) }
    }
    LaunchedEffect(playing) { while (playing.isNotEmpty()) { Voice.tick(); delay(200) } }
    // "Sending…" until the relay took it ("Sent"), then a moment more; one that can't go yet keeps saying so a while.
    val sentState = if (sent.isEmpty()) "" else {
        val q = cfg.quick.firstOrNull { it.id == sent }
        val m = q?.let { x -> msgs.lastOrNull { it.mine && it.text == x.text && it.time >= sentAt - 2_000L } }
        if (m == null) "Sending" else KidK14.sendState(true, m.state, m.failed, p.k in cfg.wait).ifEmpty { "Sending" }
    }
    LaunchedEffect(sent, sentState) { if (sent.isNotEmpty()) { delay(if (sentState == "Sent") 2000 else 8000); sent = "" } }
    BackHandler { onBack() }

    // The microphone is asked for when a parent pairs the TV (or from their phone), never by a child's press:
    // without it, the child reads "Ask a parent".
    fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else overlay = Overlay.Problem(TvWords.ASK_PARENT)
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

    // No calls in a group.
    val calls = cfg.calls && !KidChats.isGroup(p.k)
    val buttons = TvRules.talkButtons(calls, voice, dictate)
    val quick = cfg.quick.take(TvRules.QUICK_SHOWN)
    val firstId: String? = TvRules.firstTalkFocus(calls, voice, dictate, quick.map { it.id })
    val focusManager = LocalFocusManager.current
    val first = remember { FocusRequester() }
    fun req(id: String) = if (id == firstId) first else null

    // The remote's search or mic button: a voice message (or Say it, by the parent's level), never the system's search.
    Row(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
        if (!KidRules.isMicKey(e.nativeKeyEvent.keyCode)) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyUp && overlay == null) { if (voice) record() else if (dictate) listen() }
        true
    }.padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.width(260.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Reachable(app, p)
            Text(p.n, style = tvTitle(34.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
            // The last messages sit at the bottom; when they're taller than the room left, they scroll and the newest shows.
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.BottomStart) {
                Column(Modifier.verticalScroll(rememberScrollState(), reverseScrolling = true), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    // The kid's own messages say how they're doing (Sending, Sent, Waiting), as on a tablet or phone.
                    msgs.takeLast(3).forEach { TalkBubble(app, it, playing, onText = { reading = it.id }, onVideo = { video = it.id }, onPoll = { poll = it.id },
                        who = if (KidChats.isGroup(p.k)) "" else p.n, status = KidK14.sendState(it.mine, it.state, it.failed, p.k in cfg.wait)) { viewing = it.id } }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)) {
            if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (id in buttons) when (id) {
                    "call" -> ActionTile("Call", "call", req(id), Modifier.weight(1f)) { call() }
                    "rec" -> ActionTile("Voice message", "mic", req(id), Modifier.weight(1f)) { record() }
                    "say" -> ActionTile("Say it", TvIcons.SAY, req(id), Modifier.weight(1f)) { listen() }
                }
            }
            if (quick.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                quick.forEach { q ->
                    // "Sending…" on the press, "Sent" only once the relay took it, "Waiting" while it can't go yet.
                    val label = if (sent == q.id) KidK14.tileWord(sentState) else q.text
                    QuickTile(label, sent == q.id && sentState == "Sent", req(q.id), Modifier.weight(1f)) {
                        // While it says how it's doing, more presses do nothing: one message per press-and-wait.
                        if (sent != q.id) { KidChats.sendText(app, p.k, q.text); sent = q.id; sentAt = System.currentTimeMillis() }
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
        val photos = msgs.filter { KidChats.isPhoto(it) }
        TvPhoto(app, photos, id, onMove = { viewing = it }) { viewing = null }
    }
    video?.let { id -> msgs.firstOrNull { it.id == id }?.let { m -> KidVideo(app, m) { video = null } } ?: LaunchedEffect(id) { video = null } }
    poll?.let { id ->
        msgs.firstOrNull { it.id == id }?.let { m -> KidPoll(m, KidRules.canSend(cfg.chat, "text"), tv = true, onVote = { i -> KidChats.vote(app, p.k, m.id, i) }) { poll = null } }
            ?: LaunchedEffect(id) { poll = null }
    }
    reading?.let { id ->
        val m = msgs.firstOrNull { it.id == id }
        if (m == null) LaunchedEffect(id) { reading = null }
        else TvFullText(m.text) { reading = null }
    }

    overlay?.let { o ->
        TvDialog(onBack = { cancel() }) {
            when (o) {
                is Overlay.Listening -> {
                    TvIcon(TvIcons.SAY, Palette.goldBright, 80.dp)
                    Text("Listening…", style = tvTitle(32.sp), modifier = Modifier.padding(top = 14.dp))
                    if (o.partial.isNotEmpty()) Text(o.partial, style = tvText(28.sp, Palette.textSoft), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
                    DialogButtons(listOf("Done" to { speech.stop() }))
                }
                is Overlay.Heard -> {
                    Text("“${o.text}”", style = tvText(32.sp, weight = FontWeight.SemiBold), textAlign = TextAlign.Center)
                    // The remote starts on Again, so a stray OK never sends words that came out wrong.
                    DialogButtons(listOf(
                        "Send" to { KidChats.sendText(app, p.k, o.text); overlay = null; Ui.say("Sending") },
                        "Again" to { listen() },
                    ), focus = 1)
                }
                Overlay.Recording -> {
                    val ms by produceState(0L) { while (true) { value = TvRecorder.elapsed(); delay(250) } }
                    // The press that opened this can't send it: Send and Delete take OK after 2 seconds.
                    val armed = TvRules.recordingArmed(ms)
                    MIcon("mic", Palette.danger, 110.dp)
                    Text(Voice.length(ms.toInt()), style = tvTitle(56.sp), modifier = Modifier.padding(top = 10.dp))
                    DialogButtons(listOf(
                        "Send" to { if (TvRules.recordingArmed(TvRecorder.elapsed())) finishRecording() },
                        "Delete" to { if (TvRules.recordingArmed(TvRecorder.elapsed())) cancel() },
                    ), enabled = armed, icons = listOf("send", "delete"))
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
        TvIcon(icon, Palette.goldBright, 64.dp)
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

/**
 * One message in a kids' conversation. [who]: their name as the device shows it (a group's message has its sender's own
 * [Chat.Msg.name]); [status]: how my message is doing ("Sending", "Sent", "Waiting"), shown under it. TalkBack reads every
 * bubble as "Mom: I'm on my way, 4:12 PM" (k14), with the TV's "Press OK" only on a TV ([speak] null).
 */
@Composable
internal fun TalkBubble(app: Muninn, m: Chat.Msg, playing: String, onText: () -> Unit, onVideo: () -> Unit = {}, onPoll: () -> Unit = {},
                        speak: (() -> Unit)? = null, corner: Dp = 14.dp, who: String = "", status: String = "", onPhoto: () -> Unit) {
    // [corner]: 14dp on a TV; rounder on a tablet or phone (the kids' style).
    val shape = if (m.mine) RoundedCornerShape(corner, corner, 4.dp, corner) else RoundedCornerShape(corner, corner, corner, 4.dp)
    val bg = if (m.mine) Palette.bubbleMine else TheirBubble
    val touch = speak != null
    val time = remember(m.time) { if (m.time > 0L) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(m.time)) else "" }
    val label = KidK14.bubbleLabel(who.ifBlank { m.name }, m.mine, m.text, m.voice, if (m.photo.isNotEmpty()) m.video else 0,
        m.photo.isNotEmpty(), m.gif, m.file.isNotEmpty(), m.poll?.q, time)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start) {
        when {
            m.voice > 0 -> {
                var focused by remember { mutableStateOf(false) }
                val on = playing == m.id
                Row(Modifier.onFocusChanged { focused = it.isFocused }.clip(shape).background(bg)
                    .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
                    .clickable(role = Role.Button) { if (on) Voice.stopPlaying() else app.chat.photo(m.photo)?.let { Voice.play(m.id, it) } }
                    .semantics(mergeDescendants = true) { contentDescription = if (on) "$label, playing" else label }
                    .padding(start = 7.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.gold), contentAlignment = Alignment.Center) {
                        MIcon(if (on) "pausefill" else "play", Palette.onGold, 18.dp, filled = true)
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(Voice.length(m.voice), style = tvText(20.sp, Palette.textSoft))
                }
            }
            // A video message: a play tile; OK or a tap plays it full screen.
            m.video > 0 && m.photo.isNotEmpty() -> KidTile(if (m.mine) "Video" else "Video, ${Voice.length(m.video)}", "video", shape, bg, onVideo,
                if (touch) label else "$label. Press OK to play it.")
            // A file: the kids can't open files here.
            m.file.isNotEmpty() -> KidTile("Ask a parent to open this", "file", shape, bg, null, "$label. Ask a parent to open this.")
            // A poll: its question; OK or a tap shows the choices.
            m.poll != null -> KidTile(m.poll.q.ifBlank { "Poll" }, "chat", shape, bg, onPoll, if (touch) label else "$label. Press OK to see the choices.")
            // Cap a1: a GIF moves right in the conversation (still with Less motion or Android's Remove animations); OK shows it big.
            m.photo.isNotEmpty() && m.gif -> {
                var focused by remember { mutableStateOf(false) }
                io.github.ravenor907.muninn.ui.GifImage(m.id, { app.chat.photo(m.photo) }, if (touch) label else "$label. Press OK to see it big.",
                    Modifier.size(150.dp, 110.dp).onFocusChanged { focused = it.isFocused }.clip(shape).background(bg)
                        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
                        .clickable(role = Role.Image, onClick = onPhoto), maxSide = 400)
            }
            m.photo.isNotEmpty() -> {
                val bmp = remember(m.photo) { try { app.chat.photo(m.photo)?.let { thumb(it) } } catch (e: Throwable) { null } }
                var focused by remember { mutableStateOf(false) }
                // OK on a photo shows it full screen.
                if (bmp != null) Image(bmp, contentDescription = if (touch) label else "$label. Press OK to see it big.", contentScale = ContentScale.Crop,
                    modifier = Modifier.size(150.dp, 110.dp).onFocusChanged { focused = it.isFocused }.clip(shape)
                        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
                        .clickable(role = Role.Image, onClick = onPhoto))
                else MIcon("image", Palette.textMuted, 40.dp, Modifier.clip(shape).background(bg).semantics { contentDescription = label }.padding(10.dp))
            }
            else -> {
                // A message longer than the bubble shows: the remote can land on it, and OK shows it whole. On a touch
                // screen ([speak]) a tap on their message also reads it out loud.
                var cut by remember(m.id) { mutableStateOf(false) }
                var focused by remember { mutableStateOf(false) }
                val tap = cut || (speak != null && !m.mine)
                Text(m.text, style = tvText(20.sp), maxLines = 3, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow) cut = true },
                    modifier = Modifier.semantics { contentDescription = if (cut && !touch) "$label. Press OK to read it all." else label }
                        .then(if (tap) Modifier.onFocusChanged { focused = it.isFocused } else Modifier)
                        .clip(shape).background(bg)
                        .then(if (tap && focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
                        .then(if (tap) Modifier.clickable(role = Role.Button) { if (!m.mine) speak?.invoke(); if (cut) onText() } else Modifier)
                        .padding(horizontal = 13.dp, vertical = 9.dp))
            }
        }
        // 20 sp on a TV (nothing a child reads there is smaller); 14 sp on a touch screen.
        if (status.isNotEmpty()) Text(status, style = tvText(if (touch) 14.sp else 20.sp, if (status == "Waiting") Palette.textSoft else Palette.textMuted),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** A bubble that is an icon and a few words: a video, a file or a poll. [onClick] null: nothing to press. */
@Composable
private fun KidTile(text: String, icon: String, shape: androidx.compose.ui.graphics.Shape, bg: Color, onClick: (() -> Unit)?, spoken: String) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.widthIn(max = 260.dp).onFocusChanged { focused = it.isFocused }.clip(shape).background(bg)
        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics(mergeDescendants = true) { contentDescription = spoken }
        .padding(start = 7.dp, end = 13.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (onClick != null) Palette.gold else Palette.panelLow), contentAlignment = Alignment.Center) {
            MIcon(if (icon == "video") "play" else icon, if (onClick != null) Palette.onGold else Palette.textMuted, 18.dp, filled = icon == "video")
        }
        Spacer(Modifier.width(9.dp))
        Text(text, style = tvText(20.sp, Palette.textSoft), maxLines = 2, overflow = TextOverflow.Ellipsis)
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
internal fun TvPhoto(app: Muninn, photos: List<Chat.Msg>, id: String, onMove: (String) -> Unit, onClose: () -> Unit) {
    val i = photos.indexOfFirst { it.id == id }
    val m = photos.getOrNull(i) ?: run { LaunchedEffect(Unit) { onClose() }; return }
    val context = LocalContext.current
    val bmp = remember(m.photo) {
        if (m.gif) return@remember null
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
            }.focusable().clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            if (m.gif) io.github.ravenor907.muninn.ui.GifImage(m.id, { app.chat.photo(m.photo) }, "GIF", Modifier.fillMaxSize(), fit = true,
                maxSide = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels).coerceIn(720, 1920))
            else if (bmp != null) Image(bmp, contentDescription = "Photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else MIcon("image", Palette.textMuted, 96.dp)
            if (photos.size > 1) Text("${i + 1} of ${photos.size}", style = tvText(22.sp, Palette.textSoft),
                modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xAA000000)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
        LaunchedEffect(Unit) { delay(60); try { focus.requestFocus() } catch (_: Exception) {} }
    }
}

/**
 * A long message, whole and big. Up and Down scroll it, OK or Back goes back to the conversation.
 */
@Composable
internal fun TvFullText(text: String, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Palette.ground).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-TEXT_STEP) }; true }
                    Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(TEXT_STEP) }; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onClose(); true }
                    else -> false
                }
            }.focusable().clickable(onClick = onClose), contentAlignment = Alignment.TopCenter) {
            Text(text, style = tvText(30.sp), modifier = Modifier.widthIn(max = 820.dp).verticalScroll(scroll).padding(horizontal = 48.dp, vertical = 40.dp))
        }
        LaunchedEffect(Unit) { delay(60); try { focus.requestFocus() } catch (_: Exception) {} }
    }
}

/** How far Up or Down scrolls a long message, in pixels. */
private const val TEXT_STEP = 300f

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

/**
 * A row of big dialog buttons; the remote starts on button [focus]. [enabled] false: they show faded and do nothing.
 * [icons]: an icon for each button, in order (none when empty).
 */
@Composable
internal fun DialogButtons(buttons: List<Pair<String, () -> Unit>>, focus: Int = 0, enabled: Boolean = true, icons: List<String?> = emptyList()) {
    val first = remember { FocusRequester() }
    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
        buttons.forEachIndexed { i, (label, act) ->
            Box(Modifier.width(if (buttons.size > 2) 190.dp else 240.dp)) {
                BigButton(label, icon = icons.getOrNull(i), enabled = enabled, requester = if (i == focus.coerceIn(0, buttons.lastIndex)) first else null, onClick = act)
            }
        }
    }
    LaunchedEffect(buttons.map { it.first }) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
}
