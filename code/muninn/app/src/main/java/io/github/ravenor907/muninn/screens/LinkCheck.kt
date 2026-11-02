package io.github.ravenor907.muninn.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.Link
import io.github.ravenor907.muninn.net.LinkWire
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.PatternPad
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.patternSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Linking by QR (shared/docs/protocol.md section 15). A tablet shows "Link your phone" with a big QR; the phone scans it
 * (Settings › Linked devices › Scan to link, or any scanner in Muninn); both screens show the same 4 emoji and the
 * person says they match; then "Linked". Never on a Kids' TV (it has its own screens and never gets here).
 */
object LinkUi {
    /** The "Link your phone" screen is up (it shows the emoji itself, so the dialog below doesn't). */
    var companionOpen by mutableStateOf(false)
    /** Just linked: the "Linked" step, for [name]; [companion]: this device shows the phone's chats. */
    class Linked(val name: String, val companion: Boolean)
    var linked by mutableStateOf<Linked?>(null)
    private const val OFFERED = "linkOffered"

    /** A tablet (sw600dp or more), not a TV. */
    fun tablet(ctx: android.content.Context): Boolean =
        ctx.resources.configuration.smallestScreenWidthDp >= ChatNav.TABLET_SW_DP && !io.github.ravenor907.muninn.tv.KidMode.isTv(ctx)

    /** First open on a tablet with nothing linked: "Link your phone" comes up once, after the lock and the name. */
    fun offerFirst(app: Muninn): Boolean {
        val prefs = app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE)
        if (!tablet(app) || prefs.getBoolean(OFFERED, false)) return false
        prefs.edit().putBoolean(OFFERED, true).apply()
        return Link.phone.value == null && Link.companions.value.isEmpty()
    }

    /** A code from a scanner: link it (the emoji come up next). Null when done, else what to say. */
    fun take(code: String): String? = Link.link(code)
}

/** The raven, as on every Muninn screen header, above the step. */
@Composable
private fun RavenMark() {
    Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(56.dp))
}

/** One centered column, at most 520dp wide, for each step. */
@Composable
private fun Step(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

/** "Do these match?": the 4 emoji, the same on both screens, then They match or No. */
@Composable
private fun MatchStep(name: String, emoji: String, companion: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) {} }
    Step {
        RavenMark()
        Text("Do these match?", style = Type.title, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
        Text(emoji, style = Type.big.copy(fontSize = 44.sp, letterSpacing = 6.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 22.dp))
        Text(if (companion) "Your phone shows 4 emoji too." else "$name shows 4 emoji too.", style = Type.normal, textAlign = TextAlign.Center)
        MButton("They match", Modifier.fillMaxWidth().padding(top = 22.dp).focusRequester(first), Kind.Accent, icon = "check") { onYes() }
        MButton("No", Modifier.fillMaxWidth().padding(top = 8.dp)) { onNo() }
    }
}

/** "Linked", with a check. */
@Composable
private fun DoneStep(companion: Boolean, name: String, onDone: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) {} }
    Step {
        RavenMark()
        Box(Modifier.padding(top = 18.dp).size(72.dp).clip(CircleShape).background(Palette.accentFill).border(2.dp, Palette.accent, CircleShape),
            contentAlignment = Alignment.Center) { MIcon("check", Palette.accent, 36.dp, weight = 2.2f) }
        Text("Linked", style = Type.title, modifier = Modifier.padding(top = 14.dp).semantics { heading() })
        Text(if (companion) "Your chats, contacts, groups and folders are coming over. Photos follow on home Wi-Fi." else "Your chats, contacts, groups and folders are going to $name. Photos follow on home Wi-Fi.",
            style = Type.normal, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        MButton("Done", Modifier.fillMaxWidth().padding(top = 22.dp).focusRequester(first), Kind.Accent) { onDone() }
    }
}

/**
 * A tablet as the companion: "Link your phone", a big QR of a fresh link code (a new one comes before it runs out),
 * then the emoji, then "Linked". Back (or B on a controller) is Skip.
 */
@Composable
fun LinkYourPhone(app: Muninn, onClose: () -> Unit) {
    val phone by Link.phone.collectAsState()
    val ctx = LocalContext.current
    val done = LinkUi.linked
    fun close() { LinkUi.linked = null; onClose() }
    BackHandler {
        phone?.takeIf { !it.ok }?.let { Link.unlink(it.key) }
        close()
    }
    Box(Modifier.fillMaxSize().background(Palette.ground)) {
        io.github.ravenor907.muninn.ui.SeasonFall()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val p = phone
            when {
                done != null || p?.ok == true -> DoneStep(true, p?.name ?: "") { close() }
                p != null -> MatchStep(p.name, Link.emojiWith(p.key), true,
                    onYes = { Link.confirm(p.key); LinkUi.linked = LinkUi.Linked(p.name, true) },
                    onNo = { Link.unlink(p.key) })
                else -> {
                    // The code on screen is made anew before its 10 minutes run out, without a word.
                    var code by remember { mutableStateOf(Link.myLinkCode()) }
                    var madeAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(5_000)
                            val now = System.currentTimeMillis()
                            if (LinkWire.refreshDue(madeAt, now)) { code = Link.myLinkCode(now); madeAt = now }
                        }
                    }
                    val skip = remember { FocusRequester() }
                    LaunchedEffect(Unit) { try { skip.requestFocus() } catch (_: Exception) {} }
                    Step {
                        RavenMark()
                        Text("Link your phone", style = Type.title, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp).semantics { heading() })
                        if (code.isNotEmpty()) Box(Modifier.padding(top = 18.dp)) { Qr(code, 340.dp) }
                        Text("In Muninn: Settings › Linked devices › Scan to link", style = Type.normal, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp))
                        MButton("Skip", Modifier.fillMaxWidth().padding(top = 22.dp).focusRequester(skip)) { close() }
                        // For a phone without a camera: the same code as text, to paste there.
                        Text("Copy the code instead", style = Type.small.copy(color = Palette.textMuted), textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 10.dp).heightIn(min = 48.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                                .clickable(role = Role.Button) { io.github.ravenor907.muninn.ui.Clip.copy(ctx, "Muninn link code", code); Ui.say("Code copied.") }
                                .padding(horizontal = 12.dp, vertical = 14.dp))
                    }
                }
            }
        }
    }
}

/**
 * Everywhere else (the phone, or a companion linked from somewhere other than its screen): the same steps over the
 * whole screen, before anything is mirrored. Until They match nothing goes either way.
 */
@Composable
fun LinkCheck() {
    val companions by Link.companions.collectAsState()
    val phone by Link.phone.collectAsState()
    if (LinkUi.companionOpen) return
    val d = companions.firstOrNull { !it.ok } ?: phone?.takeIf { !it.ok }
    val done = LinkUi.linked
    val replace by Link.replace.collectAsState()
    // A computer linked again after "Erase and link my phone again" (protocol.md 15): after the emoji and "Linked".
    if (d == null && done == null) {
        replace?.let { r ->
            AlertDialog(onDismissRequest = { Link.answerReplace(false) }, containerColor = Palette.panel,
                title = { Text(if (r.sameName) "Replace its old entry?" else "Replace ${r.oldName}?", style = Type.big) },
                text = {
                    // Same name: the machine tag survived the erase, so it's almost surely the same computer. A different
                    // name: an entry not heard from in 14 days, so Keep both stays the safe choice (Replace isn't the accent).
                    Text(if (r.sameName) "This is ${r.newName} again after an erase. Replace its old entry? If they're two computers with the same name, keep both."
                        else "Just linked: ${r.newName}, after an erase. Not heard from in 14 days: ${r.oldName}. "
                            + "If they're the same computer, replace the old entry. If not, keep both.",
                        style = Type.normal, modifier = Modifier.verticalScroll(rememberScrollState()))
                },
                confirmButton = { MButton("Replace", kind = if (r.sameName) Kind.Accent else Kind.Normal) { Link.answerReplace(true) } },
                dismissButton = { MButton("Keep both") { Link.answerReplace(false) } })
        }
        return
    }
    val companion = d != null && phone?.key == d.key
    Dialog(onDismissRequest = { if (d != null) Link.unlink(d.key); LinkUi.linked = null },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Palette.ground)) {
            io.github.ravenor907.muninn.ui.SeasonFall()
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                if (d != null) MatchStep(d.name, Link.emojiWith(d.key), companion,
                    onYes = { Link.confirm(d.key); LinkUi.linked = LinkUi.Linked(d.name, companion) },
                    onNo = { Link.unlink(d.key) })
                else if (done != null) DoneStep(done.companion, done.name) { LinkUi.linked = null }
            }
        }
    }
}

/**
 * A linked companion's own chats are sealed away; this opens them, after the lock when one is set (password or
 * pattern). [onDone]: true when they're open.
 */
@Composable
fun OwnChatsUnlock(app: Muninn, onDone: (Boolean) -> Unit) {
    if (!app.vault.hasPassword) { LaunchedEffect(Unit) { Link.showOwn(true); onDone(true) }; return }
    val pattern = remember { app.vault.usesPattern }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var shakes by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun tryIt(secret: String) {
        if (busy) return
        busy = true; wrong = false
        scope.launch {
            val ok = withContext(Dispatchers.Default) { try { app.vault.check(secret) } catch (_: Exception) { false } }
            busy = false
            if (ok) { pw = ""; Link.showOwn(true); onDone(true) } else { wrong = true; shakes++ }
        }
    }
    AlertDialog(onDismissRequest = { onDone(false) }, containerColor = Palette.panel,
        title = { Text("This device's own chats", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (pattern) "Draw your pattern." else "Type your password.", style = Type.normal)
                if (pattern) Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                    PatternPad(error = wrong, enabled = !busy, shake = shakes, trail = app.identity.patternTrail) { dots -> tryIt(patternSecret(dots)) }
                } else Field(pw, { pw = it; wrong = false }, "Password", Modifier.padding(top = 10.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (wrong) Text("That's not it. Try again.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { if (!pattern) MButton(if (busy) "Opening…" else "Open", kind = Kind.Accent, enabled = pw.isNotEmpty() && !busy) { tryIt(pw) } },
        dismissButton = { MButton("Cancel") { onDone(false) } })
}

/** Settings › Linked devices: this phone's companions and Scan to link; on a tablet, Link my phone and its phone. */
@Composable
internal fun LinkedDevicesPanel(app: Muninn) {
    val ctx = LocalContext.current
    val companions by Link.companions.collectAsState()
    val phone by Link.phone.collectAsState()
    val tablet = remember { LinkUi.tablet(ctx) }
    var scanning by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    // A new scanner after a code that didn't work (the scanner stops at the first code it reads).
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun take(code: String, scanned: Boolean = false) {
        val err = LinkUi.take(code)
        if (err == null) { scanning = false; pasting = false; pasted = ""; return }
        Ui.say(err, true)
        // Scanning goes on after a moment, so the message can be read before the same code is seen again.
        if (scanned) scope.launch { delay(1500); attempt++ }
    }
    Text("Show your chats on a tablet or computer.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
    phone?.let { p ->
        io.github.ravenor907.muninn.ui.Framed("Your phone") {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon("phone", Palette.accent, 20.dp)
                Text(p.name, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f).padding(start = 12.dp))
                MButton("Unlink", kind = Kind.Danger) {
                    Ui.sure(io.github.ravenor907.muninn.ui.Ask("Unlink ${p.name}?", "Its chats go from this device.", "Unlink", danger = true) { Link.unlink(p.key) })
                }
            }

        }
    }
    if (companions.isNotEmpty()) io.github.ravenor907.muninn.ui.Framed("Linked to this phone") {
        companions.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon(if (d.ok) "check" else "clock", if (d.ok) Palette.accent else Palette.textMuted, 20.dp)
                Text(d.name, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f).padding(start = 12.dp))
                MButton("Unlink", kind = Kind.Danger) {
                    Ui.sure(io.github.ravenor907.muninn.ui.Ask("Unlink ${d.name}?", "Its copy of your chats is removed.", "Unlink", danger = true) { Link.unlink(d.key) })
                }
            }
        }
    }
    io.github.ravenor907.muninn.ui.Framed(if (tablet) "Link" else "Link a tablet or computer") {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A tablet is the companion: it shows the QR. A phone scans it.
            if (tablet && phone == null) MButton("Link my phone", Modifier.fillMaxWidth(), Kind.Accent, icon = "qr") { LinkUi.companionOpen = true }
            MButton(if (scanning) "Stop scanning" else "Scan to link", Modifier.fillMaxWidth(), if (tablet) Kind.Normal else Kind.Accent, icon = "qr") { scanning = !scanning; pasting = false }
            if (scanning) key(attempt) { QrScanner(Modifier.fillMaxWidth().height(280.dp)) { c -> take(c, scanned = true) } }
            if (!scanning) Text(if (pasting) "Paste the code from the other screen." else "Paste a code instead", style = Type.small.copy(color = Palette.textMuted),
                modifier = Modifier.heightIn(min = 40.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                    .clickable(role = Role.Button, enabled = !pasting) { pasting = true }.padding(horizontal = 4.dp, vertical = 10.dp))
            if (pasting && !scanning) {
                Field(pasted, { pasted = it }, "Link code", single = false)
                MButton("Link", Modifier.fillMaxWidth(), enabled = pasted.isNotBlank()) { take(pasted) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/** A message's photo or file on a companion: the picture once it's here, else its word and "On home Wi-Fi". */
@Composable
internal fun MirrorMedia(m: Link.M, mine: Boolean) {
    val f = m.f ?: return
    val stored by Link.stored.collectAsState()
    val here = f.h in stored
    if (here && f.k == "photo") {
        val bmp = remember(f.h) {
            try { Link.mediaBytes(f.h)?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }?.asImageBitmap() } catch (_: Exception) { null }
        }
        if (bmp != null) {
            Image(bmp, contentDescription = "Photo", modifier = Modifier.widthIn(max = 320.dp).heightIn(max = 360.dp).clip(io.github.ravenor907.muninn.ui.Corner))
            return
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
        MIcon(when (f.k) { "photo" -> "image"; "voice" -> "audio"; "video" -> "video"; else -> "file" }, if (mine) Palette.goldBright else Palette.accent, 20.dp)
        Column(Modifier.padding(start = 8.dp)) {
            Text(m.text, style = Type.normal.copy(color = Palette.text))
            Text(if (here) "Saved here" else "On home Wi-Fi", style = Type.small)
        }
    }
}
