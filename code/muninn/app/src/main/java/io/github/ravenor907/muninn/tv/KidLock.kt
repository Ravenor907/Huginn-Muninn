package io.github.ravenor907.muninn.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.HoldToConfirm
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A dialog on a Kids' Device: the TV's own ([TvDialog], sized for the remote), or on a tablet or phone one that fits
 * the screen's width. Back closes it.
 */
@Composable
internal fun KidDialog(tv: Boolean, onBack: () -> Unit, compact: Boolean = false, content: @Composable () -> Unit) {
    if (tv) { TvDialog(onBack, content); return }
    // [compact]: the text no bigger than 1.5 times and slimmer edges, so a keypad fits at the largest sizes.
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(if (compact) 8.dp else 16.dp).clip(RoundedCornerShape(20.dp)).background(Palette.panel)
            .verticalScroll(rememberScrollState()).padding(if (compact) 12.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (compact) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, minOf(d.fontScale, KidRules.PAD_FONT_MAX))) { content() }
            } else content()
        }
    }
}

/**
 * "For a parent" on a Kids' Device (cap k11, 7.18): closed until the parent PIN is typed or a parent's phone says yes;
 * then, for 10 minutes, Android's settings for Muninn and Leave kids' mode. Everything else is set from a parent's phone.
 * Works with touch and with the D-pad alike.
 */
@Composable
internal fun KidParentArea(app: Muninn, cfg: TvConfig, tv: Boolean, onClose: () -> Unit) {
    val until by KidTv.unlockedUntil.collectAsState()
    // The device's uptime, as the unlock itself counts: moving the clock changes nothing.
    val now by produceState(KidTv.elapsed()) { while (true) { value = KidTv.elapsed(); delay(1000) } }
    // Closing it, any way, locks it again at once: a parent who looked and handed the device back leaves nothing open.
    val close = { KidTv.lockAgain(); onClose() }
    KidDialog(tv, close, compact = true) {
        if (until > now) KidUnlocked(app, tv, until - now, close) else KidLocked(app, cfg, tv, close)
    }
}

@Composable
private fun KidLocked(app: Muninn, cfg: TvConfig, tv: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    // A wait after wrong PINs: counted down here, and the keypad takes nothing until it's over.
    val wait by produceState(KidTv.pinWait(context), checking) { while (true) { value = KidTv.pinWait(context); delay(1000) } }
    // The wait is over: its words go.
    LaunchedEffect(wait <= 0) { if (wait <= 0 && line.contains("Wait")) line = "" }
    MIcon("lock", Palette.goldBright, if (tv) 64.dp else 48.dp)
    Text("For a parent", style = tvTitle(if (tv) 32.sp else 24.sp), modifier = Modifier.padding(top = 10.dp))
    fun ok() {
        if (checking) return
        // Fewer than 4 digits is no try at all: nothing is checked or counted.
        if (pin.length < KidRules.PIN_MIN) { line = KidRules.pinText(PinTry(false, false, 0)); return }
        checking = true
        val typed = pin
        scope.launch {
            val r = withContext(Dispatchers.Default) { KidTv.tryPin(context, typed) }
            // Round 17: only this dialog, still up, opens the settings: a check that ends after it closed opens nothing
            // (withContext gives up here once the dialog is gone).
            if (r.ok) KidTv.openWithPin()
            checking = false
            pin = ""
            line = KidRules.pinText(r)
        }
    }
    // While a wait counts down, the line says only what happened ("Wrong PIN"), not the wait a second time.
    val shown = when { wait <= 0 -> line; line.startsWith("Wrong PIN") -> "Wrong PIN"; else -> "" }
    if (cfg.pin != null) {
        // The digits typed so far, as dots; what happened ("Wrong PIN") in their place above the keypad, so it's always in sight.
        if (pin.isEmpty() && shown.isNotEmpty()) Text(shown, style = tvText(if (tv) 24.sp else 18.sp, Palette.danger),
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        else Text(if (pin.isEmpty()) "Type the parent PIN" else "● ".repeat(pin.length).trim(), style = tvText(if (tv) 28.sp else 22.sp, Palette.textSoft),
            modifier = Modifier.padding(top = 12.dp).semantics { contentDescription = "${pin.length} digits typed" })
        if (wait > 0) Text(KidRules.waitText(wait), style = tvText(if (tv) 24.sp else 18.sp, Palette.textSoft), textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp))
        Keypad(tv, onDigit = { d -> if (wait <= 0 && pin.length < KidRules.PIN_MAX) pin += d; line = "" }, onDelete = { pin = pin.dropLast(1) },
            onOk = { if (wait > 0) line = "" else ok() })
    } else Text("No parent PIN is set. A parent's phone can open this.", style = tvText(if (tv) 24.sp else 18.sp, Palette.textSoft),
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    var asked by remember { mutableStateOf("") }
    if (asked.isNotEmpty()) Text(asked, style = tvText(if (tv) 22.sp else 17.sp, Palette.textSoft), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
    Box(Modifier.padding(top = 16.dp)) {
        BigButton("Ask a parent's phone", icon = "phone") {
            val at = { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(KidTv.lastAskWall)) }
            asked = when (KidTv.askUnlock(app)) {
                KidTv.Asked.SENT -> "Asked at ${at()}. A parent can say yes on their phone."
                KidTv.Asked.TOO_SOON -> "Asked at ${at()}. ${KidRules.askAgainText(KidTv.askWait(context))}"
                KidTv.Asked.CHECKING -> "Checking the parents' phones. Try again in a moment."
                KidTv.Asked.NEEDS_UPDATE -> "A parent's phone needs an update first"
            }
        }
    }
    Box(Modifier.padding(top = 10.dp)) { BigButton("Close") { onClose() } }
}

@Composable
private fun KidUnlocked(app: Muninn, tv: Boolean, left: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    MIcon("lock", Palette.online, if (tv) 64.dp else 48.dp)
    Text("Parent settings", style = tvTitle(if (tv) 32.sp else 24.sp), modifier = Modifier.padding(top = 10.dp))
    // [left]: how long it stays open, by the device's uptime; shown as a time on this device's clock.
    val at = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + left))
    Text("Open until $at. Everything else is set on a parent's phone, on this device's page.", style = tvText(if (tv) 22.sp else 16.sp, Palette.textSoft),
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    // The keypad's key had the remote's focus and is gone now: it lands here, so no press is lost.
    val first = remember { FocusRequester() }
    Box(Modifier.padding(top = 16.dp)) {
        BigButton("Android settings for Muninn", icon = "gear", requester = first) { if (!TvUpdate.openAppPage(context)) Ui.say(TvWords.ASK_PARENT, true) }
    }
    if (tv) LaunchedEffect(Unit) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
    Text("Leaving kids' mode erases the messages and people on this device. Then it runs as a normal Muninn.", style = tvText(if (tv) 22.sp else 16.sp, Palette.textMuted),
        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
    HoldToConfirm("Hold to leave kids' mode", "Hold for 5 seconds to erase this device and leave kids' mode",
        { KidTv.leave(app); onClose() }, Modifier.fillMaxWidth().padding(top = 10.dp))
    Box(Modifier.padding(top = 12.dp)) { BigButton("Lock again") { KidTv.lockAgain(); onClose() } }
}

/** 1 to 9, Delete, 0, OK: big keys a remote's D-pad or a finger can press. */
@Composable
private fun Keypad(tv: Boolean, onDigit: (Char) -> Unit, onDelete: () -> Unit, onOk: () -> Unit) {
    val first = remember { FocusRequester() }
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Delete", "0", "OK"))
    // Round 17: a remote's (or keyboard's) number keys type digits, its delete key deletes and Enter is OK.
    Column(Modifier.padding(top = 14.dp).widthIn(max = if (tv) 420.dp else 320.dp)
        .onPreviewKeyEvent { e ->
            if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val code = e.nativeKeyEvent.keyCode
            when (val d = KidRules.padKey(code)) {
                null -> false
                "Delete" -> { onDelete(); true }
                "OK" -> { onOk(); true }
                else -> { onDigit(d[0]); true }
            }
        }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    PadKey(k, tv, if (k == "1") first else null, Modifier.weight(1f)) {
                        when (k) { "Delete" -> onDelete(); "OK" -> onOk(); else -> onDigit(k[0]) }
                    }
                }
            }
        }
    }
    if (tv) LaunchedEffect(Unit) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
}

@Composable
private fun PadKey(label: String, tv: Boolean, requester: FocusRequester?, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.heightIn(min = if (tv) 64.dp else 56.dp)
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .clip(shape).background(Palette.field)
        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
        .clickable(role = Role.Button, onClick = onClick)
        .then(if (label == "Delete" && !tv) Modifier.semantics { contentDescription = "Delete" } else Modifier),
        contentAlignment = Alignment.Center) {
        // On a touch screen Delete is its icon: the word doesn't fit a key at a large text size.
        if (label == "Delete" && !tv) MIcon("back", Palette.text, 26.dp)
        else Text(label, style = tvText(if (label.length > 1) (if (tv) 22.sp else 18.sp) else (if (tv) 30.sp else 26.sp), weight = FontWeight.SemiBold))
    }
}

/** The small lock in a corner of the kids' screens that opens [KidParentArea]. */
@Composable
internal fun ParentLock(tv: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(modifier.size(if (tv) 56.dp else 48.dp).onFocusChanged { focused = it.isFocused }.clip(shape)
        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "For a parent" },
        contentAlignment = Alignment.Center) {
        MIcon("lock", Palette.textMuted, if (tv) 30.dp else 24.dp)
    }
}

/** "Quiet time until 07:00. Only parents now." */
@Composable
internal fun QuietLine(q: QuietTime, tv: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.raised).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MIcon("clock", Palette.goldBright, if (tv) 30.dp else 22.dp)
        // The device's own 12 or 24 hour setting.
        val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
        Text("${KidRules.quietName(q.n)} until ${KidRules.clock(q.e, h24)}. Only parents now.", style = tvText(if (tv) 24.sp else 17.sp),
            modifier = Modifier.padding(start = 10.dp))
    }
}
