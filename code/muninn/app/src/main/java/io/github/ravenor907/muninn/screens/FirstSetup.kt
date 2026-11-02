package io.github.ravenor907.muninn.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.Backup
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.screens.SetupFlow.Step
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Avatar
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.PatternPad
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * First setup (and Labs › Replay first setup, as practice): one run at a time. MainActivity shows its pages over
 * everything, and the tour over the real main screen; [ended] tells it where to go after.
 */
object Setup {
    var run by mutableStateOf<SetupFlow.Run?>(null)
        private set
    var writes: SetupFlow.Writes = SetupFlow.Writes(true, NoStore)
        private set
    /** Bumped on every page change, so the pages redraw. */
    var tick by mutableIntStateOf(0)
        private set
    /** A run just ended: practice (back to Labs) or not, and whether Start a chat was tapped. */
    class Ended(val practice: Boolean, val startChat: Boolean)
    var ended by mutableStateOf<Ended?>(null)

    private const val STARTED = "setupStarted"
    private const val DONE = "setupDone"
    private fun prefs(c: Context) = c.getSharedPreferences("muninn", Context.MODE_PRIVATE)

    /** Before the window is drawn: a fresh install, by what's saved alone (so Android's notification question waits for its page). */
    fun freshByPrefs(app: Muninn, ci: Boolean): Boolean {
        val p = prefs(app)
        return SetupFlow.shouldStart(io.github.ravenor907.muninn.tv.KidMode.on(app), ci, p.getBoolean(STARTED, false), p.getBoolean(DONE, false),
            lockChosen(app), app.identity.nameAsked, p.getBoolean(KEEP_NOTICE, false), false)
    }

    /** Once Muninn is open: starts first setup on a fresh install, or picks it up again after a restart. */
    fun maybeBegin(app: Muninn, ci: Boolean) {
        if (run != null) return
        val p = prefs(app)
        val contacts = try { app.chat.contacts.all.value.isNotEmpty() } catch (_: Exception) { false }
        if (!SetupFlow.shouldStart(io.github.ravenor907.muninn.tv.KidMode.on(app), ci, p.getBoolean(STARTED, false), p.getBoolean(DONE, false),
                lockChosen(app), app.identity.nameAsked, p.getBoolean(KEEP_NOTICE, false), contacts)) return
        begin(app, false)
    }

    /** Labs › Replay first setup: every page, nothing saved. */
    fun practice(app: Muninn) = begin(app, true)

    private fun begin(app: Muninn, practice: Boolean) {
        val w = SetupFlow.Writes(practice, RealStore(app))
        val facts = SetupFlow.Facts(practice, lockChosen(app), app.identity.nameAsked, allowItems(app).any { !it.allowed(app) },
            if (practice) LinkUi.tablet(app) else LinkUi.offerFirst(app))
        w.markStarted()
        // The 7-day note is on the last page now, so the chat list doesn't say it again.
        w.noticeSeen()
        writes = w
        run = SetupFlow.Run(practice, SetupFlow.steps(facts))
        tick++
    }

    /** First setup's Restore from a backup worked: setup is done, so the restored Muninn starts on its chats. */
    fun restored(app: Muninn) { prefs(app).edit().putBoolean(STARTED, true).putBoolean(DONE, true).putBoolean("nameAsked", true).commit() }

    fun next() { val r = run ?: return; if (!r.next()) finish(false) else tick++ }
    fun back() { run?.back(); tick++ }

    /** Done (or Leave in practice). */
    fun finish(startChat: Boolean) {
        val r = run ?: return
        writes.markDone()
        run = null
        ended = Ended(r.practice, startChat)
        tick++
    }

    /** True while the tour is on the main screen (spots are measured only then). */
    val touring: Boolean get() { tick; return run?.step == Step.TOUR }

    private object NoStore : SetupFlow.Store {
        override fun markStarted() {}
        override fun markDone() {}
        override fun noticeSeen() {}
        override fun saveName(name: String) {}
        override fun nameAsked() {}
        override fun skipLock() {}
        override fun myCode() = SetupFlow.SAMPLE
        override fun inviteLink() = SetupFlow.SAMPLE
    }

    private class RealStore(val app: Muninn) : SetupFlow.Store {
        override fun markStarted() { prefs(app).edit().putBoolean(STARTED, true).apply() }
        override fun markDone() { prefs(app).edit().putBoolean(DONE, true).apply(); app.identity.nameAsked = true }
        override fun noticeSeen() { prefs(app).edit().putBoolean(KEEP_NOTICE, true).remove("limitsNote").apply() }
        override fun saveName(name: String) = io.github.ravenor907.muninn.screens.saveName(app, name)
        override fun nameAsked() { app.identity.nameAsked = true }
        override fun skipLock() = io.github.ravenor907.muninn.screens.skipLock(app)
        override fun myCode(): String = app.chat.myCode()
        override fun inviteLink(): String = app.chat.inviteLink(24)
    }
}

/** One thing on the Allow page: Android's permissions for it and the one reason Muninn needs it. */
private class AllowItem(val title: String, val reason: String, val perms: List<String>) {
    fun allowed(c: Context) = perms.all { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
}

/** What the Allow page asks for, leaving out what this device has no hardware for or Android doesn't ask about. */
private fun allowItems(c: Context): List<AllowItem> {
    val pm = c.packageManager
    return buildList {
        if (Build.VERSION.SDK_INT >= 33) add(AllowItem("Notifications", "So you see new messages and calls.", listOf(Manifest.permission.POST_NOTIFICATIONS)))
        if (pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) add(AllowItem("Microphone", "For calls and voice messages.", listOf(Manifest.permission.RECORD_AUDIO)))
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) add(AllowItem("Camera", "For video calls and scanning codes.", listOf(Manifest.permission.CAMERA)))
        if (Build.VERSION.SDK_INT >= 31) add(AllowItem("Nearby devices", "To chat close by without the internet.", io.github.ravenor907.muninn.net.Ble.PERMISSIONS.toList()))
    }
}

/** The tour's spots on the main screen, by name ([SetupFlow.Stop.spot]), in root coordinates. */
object Tour {
    val spots = mutableStateMapOf<String, Rect>()
}

/** Marks what the tour points at. Measured only while the tour is on. */
fun Modifier.tourSpot(id: String): Modifier = composed {
    if (!Setup.touring) return@composed this
    DisposableEffect(id) { onDispose { Tour.spots.remove(id) } }
    onGloballyPositioned { val r = it.boundsInRoot(); if (Tour.spots[id] != r) Tour.spots[id] = r }
}

/** "Practice" across the top while Labs › Replay first setup runs, with Leave (back to Labs). */
@Composable
private fun PracticeRibbon() {
    Row(Modifier.fillMaxWidth().background(Palette.gold).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("PRACTICE · nothing is saved", style = Type.small.copy(color = Palette.onGold, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
            modifier = Modifier.weight(1f))
        Text("Leave", style = Type.normal.copy(color = Palette.onGold, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.clip(Corner).clickable(role = androidx.compose.ui.semantics.Role.Button) { Setup.finish(false) }
                .padding(horizontal = 12.dp, vertical = 12.dp))
    }
}

/**
 * First setup's pages, over the whole screen, on the look's ground with the season falling behind. The tour isn't
 * here: it runs on the main screen ([TourOverlay]).
 */
@Composable
fun FirstSetup(app: Muninn) {
    Setup.tick
    val run = Setup.run ?: return
    val ask by Ui.ask.collectAsState()
    // Back: the page before; on the first page of practice, back to Labs. On a real first page Android's own Back.
    BackHandler(enabled = !run.first || run.practice) { if (run.first) Setup.finish(false) else Setup.back() }
    Box(Modifier.fillMaxSize().background(Palette.ground)) {
        io.github.ravenor907.muninn.ui.SeasonFall()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (run.practice) PracticeRibbon()
            Brand {}
            Box(Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.runtime.key(run.index) {
                    when (run.step) {
                        Step.WELCOME -> WelcomePage(app)
                        Step.LOCK -> LockPage(app)
                        Step.NAME -> NamePage(app)
                        Step.ALLOW -> AllowPage(app)
                        Step.PEOPLE -> PeoplePage(app)
                        Step.LINK -> LinkPage(app)
                        Step.DONE -> DonePage()
                        Step.TOUR -> {}
                    }
                }
            }
        }
    }
    ask?.let { AskDialog(it) }
}

/** A page: its content (centered, at most 520dp wide, scrolling if [scroll]), the progress dots, then Back, Skip and Next. */
@Composable
private fun Page(skip: String? = null, onSkip: () -> Unit = { Setup.next() }, next: String? = "Next", nextOn: Boolean = true,
                 onNext: () -> Unit = { Setup.next() }, scroll: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val run = Setup.run ?: return
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier.fillMaxSize())
                .padding(horizontal = 20.dp, vertical = 8.dp), content = content)
        }
        // Progress: one dot a page (the tour has none of its own), the current one in the accent and wider.
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).semantics { stateDescription = "Step ${run.dot + 1} of ${run.dots}" },
            horizontalArrangement = Arrangement.Center) {
            repeat(run.dots) { i ->
                val on = i == run.dot
                Box(Modifier.padding(horizontal = 3.dp).size(width = if (on) 18.dp else 8.dp, height = 8.dp).clip(Corner)
                    .background(if (on) Palette.accent else Palette.line))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (!run.first) MButton("Back", Modifier.weight(1f)) { Setup.back() } else Spacer(Modifier.weight(1f))
            if (skip != null) MButton(skip, Modifier.weight(1f)) { onSkip() }
            if (next != null) MButton(next, Modifier.weight(1f), Kind.Accent, enabled = nextOn) { onNext() }
        }
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = Type.big, modifier = Modifier.padding(top = 8.dp).semantics { heading() })

@Composable
private fun Line(text: String, color: Color = Palette.textMuted) =
    Text(text, style = Type.normal.copy(color = color), modifier = Modifier.padding(top = 6.dp))

/** A sample code's QR with PRACTICE across it, so it's never taken for a real one. */
@Composable
private fun PracticeQr(size: androidx.compose.ui.unit.Dp) {
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { alpha = 0.35f }) { Qr(SetupFlow.SAMPLE, size) }
        Text("PRACTICE", style = Type.big.copy(color = Palette.onGold, fontWeight = FontWeight.Bold, letterSpacing = 3.sp),
            modifier = Modifier.background(Palette.gold).padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

/** Welcome, with Restore from a backup (a backup made in Huginn or Muninn; in practice nothing is opened). */
@Composable
private fun WelcomePage(app: Muninn) {
    var restoring by remember { mutableStateOf(false) }
    if (restoring) {
        Page(next = null) {
            RestoreFlow(app, practice = !Setup.writes.live, onCancel = { restoring = false }) { o ->
                Backup.restoreKeeping(app, o)
                Setup.restored(app)
                "Restored. Set a lock any time in Settings › Lock and privacy. Muninn is restarting."
            }
        }
        return
    }
    Page {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(96.dp))
            Text("Welcome to Muninn", style = Type.title, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).semantics { heading() })
            Text("Private messages, calls and files with family and friends. No phone number, no account.", style = Type.normal,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            Text("Have a backup from Huginn or Muninn?", style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
            MButton("Restore from a backup", Modifier.padding(top = 8.dp)) { restoring = true }
        }
    }
}

/** The lock, on by default: Skip asks first. In practice the pattern is drawn but never set. */
@Composable
private fun LockPage(app: Muninn) {
    val w = Setup.writes
    if (w.live && lockChosen(app)) {
        // Back here after choosing: nothing to change.
        Page { Title("Protect Muninn"); Line(if (app.vault.hasPassword) "Your lock is set." else "No lock. Set one in Settings › Lock and privacy.") }
        return
    }
    var drawn by remember { mutableStateOf("") }
    Page(skip = "Skip", onSkip = {
        if (!w.live) Setup.next()
        else Ui.sure(Ask("Skip the lock?", "Without a lock, anyone who can use this phone can open Muninn and read your chats. " +
            "You can set one any time in Settings › Lock and privacy.", "Skip") { w.skipLock(); Setup.next() })
    }, next = if (w.live) null else "Next", nextOn = drawn.isNotEmpty(), scroll = false) {
        Title("Protect Muninn")
        Text("Opens with your pattern or password. It can't be recovered, so pick one you'll remember.", style = Type.small)
        if (w.live) LockSetup(app, onCancel = null, fill = true) { Setup.next() }
        else {
            Text(drawn.ifEmpty { "Draw a pattern: connect at least 6 dots." }, style = Type.normal, modifier = Modifier.padding(top = 12.dp))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                PatternPad { dots -> drawn = if (dots.size < 6) "Connect at least 6 dots." else "Practice: no lock was set." }
            }
        }
    }
}

/** "What should people call you?", with the name Muninn picked filled in, and an optional picture. */
@Composable
private fun NamePage(app: Muninn) {
    val w = Setup.writes
    var name by remember { mutableStateOf(app.identity.username) }
    var picking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    Page(skip = "Skip", onSkip = { w.nameAsked(); Setup.next() }, nextOn = name.isNotBlank(), onNext = {
        w.saveName(name.trim()); w.nameAsked(); Setup.next()
    }) {
        Title("What should people call you?")
        Text("Change it any time in Settings.", style = Type.small)
        Field(name, { name = it.take(24) }, "A name", Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar("me", name.ifBlank { app.identity.username }, 64.dp)
            MButton("Add a picture", Modifier.padding(start = 14.dp), icon = "image") {
                if (w.live) picking = true else note = "Practice: your picture stays as it is."
            }
        }
        if (note.isNotEmpty()) Line(note)
    }
    if (picking) io.github.ravenor907.muninn.ui.PicturePicker { picking = false }
}

/** Notifications and the permissions Muninn uses, each with one reason. Practice shows them but asks Android nothing. */
@Composable
private fun AllowPage(app: Muninn) {
    val ctx = LocalContext.current
    val w = Setup.writes
    val items = remember { allowItems(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        tick++
        // Nearby devices allowed: close-by chat starts now.
        if (res.keys.any { it in io.github.ravenor907.muninn.net.Ble.PERMISSIONS } && res.values.all { it } && app.ready.value) app.ble.start()
    }
    // Android's own screens change these behind our back: look again every little while.
    var look by remember { mutableIntStateOf(0) }
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) { look++ }
    Page(skip = "Skip") {
        Title("Allow a few things")
        Text("Muninn only asks for what it uses.", style = Type.small)
        tick; look
        items.forEach { item ->
            val ok = w.live && item.allowed(ctx)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = Type.normal.copy(color = Palette.text))
                    Text(item.reason, style = Type.small)
                }
                if (ok) Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MIcon("check", Palette.accent, 18.dp, weight = 2.2f)
                    Text("Allowed", style = Type.small.copy(color = Palette.accent), modifier = Modifier.padding(start = 4.dp))
                }
                else MButton("Allow", Modifier.padding(start = 8.dp), Kind.Accent) {
                    if (w.live) ask.launch(item.perms.toTypedArray()) else note = "Practice: Android would ask here. Nothing changes."
                }
            }
        }
        if (note.isNotEmpty()) Line(note)
    }
}

/** My code, big, with Share (a one-time link) and Scan theirs. Practice shows a sample code and scans nothing. */
@Composable
private fun PeoplePage(app: Muninn) {
    val ctx = LocalContext.current
    val w = Setup.writes
    val code = remember { w.myCode() }
    var scanning by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    // A group's invite scanned or pasted here, or opened from a link during setup: the same Join card as + › Add someone.
    var invite by remember { mutableStateOf(if (w.live) GroupLinks.parse(app, ChatNav.incoming) else null) }
    var pasted by remember { mutableStateOf("") }
    Page(skip = "Later", next = "Done") {
        Title("Add people")
        Text("Both people need Muninn. Scan each other's code, or share a link.", style = Type.small)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth - 20.dp, 280.dp).coerceAtLeast(120.dp)
            when {
                scanning -> QrScanner(Modifier.fillMaxWidth().height(minOf(maxWidth, 320.dp))) { scanned ->
                    invite = GroupLinks.parse(app, Contacts.unlink(scanned)).takeIf { !io.github.ravenor907.muninn.net.Link.isCode(scanned) }
                    note = if (invite != null) "" else added(app, scanned)
                    scanning = false
                }
                w.live -> Qr(Contacts.link(code), side)
                else -> PracticeQr(side)
            }
        }
        Text(if (w.live) "Scan to add me. Works for 1 hour." else "A sample code. It adds no one.", style = Type.small,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Share", Modifier.weight(1f), Kind.Accent, icon = "send") {
                if (!w.live) { note = "Practice: this opens Android's share sheet with your link."; return@MButton }
                val link = w.inviteLink()
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Message me on Muninn. This link adds us once, within a day:\n$link"), "Send a one-time link"))
            }
            MButton(if (scanning) "Show my code" else "Scan theirs", Modifier.weight(1f), icon = if (scanning) "qr" else "video") {
                if (!w.live) note = "Practice: scanning is off. For real, their code adds them here."
                else { scanning = !scanning; note = "" }
            }
        }
        invite?.let { p -> Panel(Modifier.padding(top = 12.dp)) {
            Text("You're invited to ${p.name.ifBlank { "a group" }}", style = Type.normal.copy(color = Palette.text))
            JoinCard(app, p) { invite = null; ChatNav.incoming = "" }
        } }
        // No camera needed: a code or invite link from another app on this phone.
        Field(pasted, { pasted = it }, "Paste a code or invite link", Modifier.padding(top = 12.dp), single = false)
        if (pasted.isNotBlank()) MButton("Add", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) {
            if (!w.live) { note = "Practice: their code would add them here."; pasted = ""; return@MButton }
            val p = GroupLinks.parse(app, Contacts.unlink(pasted.trim()))
            if (p != null && !io.github.ravenor907.muninn.net.Link.isCode(pasted)) { invite = p; note = "" } else note = added(app, pasted.trim())
            pasted = ""
        }
        if (note.isNotEmpty()) Line(note, Palette.text)
    }
}

/** A code scanned on the Add people page: added (said back), or why not. */
private fun added(app: Muninn, scanned: String): String {
    val text = Contacts.unlink(scanned)
    if (io.github.ravenor907.muninn.net.Link.isCode(scanned)) return "That's a link code. Use Settings › Linked devices."
    val found = Contacts.parse(text) ?: return "That isn't a Muninn code."
    if (Contacts.expires(text) in 1 until System.currentTimeMillis()) return "That code has ended. Ask for a new one."
    if (found.chatKey == app.identity.publicKey) return "That's your own code."
    app.chat.addContact(found)
    // A one-time code: say hi at once, which adds me on their phone too.
    if (Contacts.once(text)) app.chat.sendPrivate(found.chatKey, "Hi, it's ${app.identity.username}. I added you.")
    io.github.ravenor907.muninn.tv.TvParent.adoptIfTv(app, text)
    return "${found.name} added."
}

/** A tablet with nothing linked: "Link your phone". Practice shows a sample code instead of a real link code. */
@Composable
private fun LinkPage(app: Muninn) {
    if (Setup.writes.live) { LinkYourPhone(app) { Setup.next() }; return }
    Page(skip = "Skip") {
        Title("Link your phone")
        Box(Modifier.fillMaxWidth().padding(top = 14.dp), contentAlignment = Alignment.Center) { PracticeQr(240.dp) }
        Line("In Muninn: Settings › Linked devices › Scan to link")
    }
}

@Composable
private fun DonePage() {
    val practice = Setup.run?.practice == true
    Page(next = "Start a chat", onNext = { Setup.finish(!practice) }) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Palette.accentFill).border(2.dp, Palette.accent, CircleShape),
                contentAlignment = Alignment.Center) { MIcon("check", Palette.accent, 36.dp, weight = 2.2f) }
            Text("You're set.", style = Type.title, modifier = Modifier.padding(top = 14.dp).semantics { heading() })
            Text("Messages, photos and calls delete after 7 days, so old chats can't leak. Family groups can keep them longer.", style = Type.normal, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
            if (practice) Text("Practice ends here and goes back to Labs.", style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * The tour, over the real main screen: everything dimmed but the spot it's about, one sentence, Got it and Skip tour.
 * Nothing under it can be tapped. A spot not on screen (no chats yet) gets its sentence without the highlight.
 */
@Composable
fun TourOverlay() {
    Setup.tick
    val run = Setup.run ?: return
    if (run.step != Step.TOUR) return
    var at by remember { mutableIntStateOf(0) }
    val stop = SetupFlow.TOUR[at.coerceIn(0, SetupFlow.TOUR.lastIndex)]
    val spot = Tour.spots[stop.spot]
    val density = LocalDensity.current
    val pad = with(density) { 6.dp.toPx() }
    val accent = Palette.accent
    BackHandler { if (at > 0) at-- else Setup.back() }
    BoxWithConstraints(Modifier.fillMaxSize()
        // Every touch stops here (after the card's own buttons have theirs).
        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Main).changes.forEach { it.consume() } } }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawBehind {
            drawRect(Color.Black.copy(alpha = 0.62f))
            spot?.let { r ->
                val tl = Offset(r.left - pad, r.top - pad); val sz = Size(r.width + pad * 2, r.height + pad * 2)
                drawRect(Color.Transparent, tl, sz, blendMode = BlendMode.Clear)
                drawRect(accent, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(with(density) { 2.dp.toPx() }))
            }
        }) {
        val fullHeight = maxHeight
        val below = spot != null && with(density) { spot.center.y.toDp() } < fullHeight / 2
        Column(Modifier.fillMaxSize()) {
            if (below) Spacer(Modifier.height(with(density) { (spot!!.bottom + pad).toDp() } + 12.dp)) else Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                Panel(Modifier.widthIn(max = 480.dp)) {
                    Text("${at + 1} of ${SetupFlow.TOUR.size}", style = Type.small)
                    Text(if (spot != null) stop.text else stop.missing, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MButton("Skip tour", Modifier.weight(1f)) { Setup.next() }
                        MButton("Got it", Modifier.weight(1f), Kind.Accent) { if (at < SetupFlow.TOUR.lastIndex) at++ else Setup.next() }
                    }
                }
            }
            if (below) Spacer(Modifier.weight(1f))
            else Spacer(Modifier.height(if (spot == null) 0.dp else (fullHeight - with(density) { (spot.top - pad).toDp() } + 12.dp).coerceAtLeast(0.dp)))
        }
        // The ribbon goes on the side away from the spot, so it never covers it.
        if (run.practice) Box(Modifier.align(if (below) Alignment.BottomCenter else Alignment.TopCenter)
            .then(if (below) Modifier.navigationBarsPadding() else Modifier.statusBarsPadding())) { PracticeRibbon() }
    }
}
