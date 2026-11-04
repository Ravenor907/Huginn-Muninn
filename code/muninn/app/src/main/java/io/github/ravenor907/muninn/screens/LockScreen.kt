package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.LockWait
import io.github.ravenor907.muninn.net.Vault
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.PadPage
import io.github.ravenor907.muninn.ui.PatternPad
import androidx.compose.foundation.layout.ColumnScope
import io.github.ravenor907.muninn.ui.patternSecret
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Shown while Muninn is locked with a password. Nothing else is readable until it's typed. [inApp]: asked inside the
 * open app (Stop moving): checked without counting toward the waits or the erase; 5 wrong in a row lock Muninn.
 */
@Composable
fun LockScreen(app: Muninn, inApp: Boolean = false, done: (Boolean) -> Unit) {
    // The lock file or the keys can't be read: never a new key over what's here, the way back instead.
    val trouble by app.vault.trouble.collectAsState()
    trouble?.let { VaultTrouble(app, it, done); return }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var shakes by remember { mutableStateOf(0) }   // a wrong pattern shakes the pad
    var forgot by remember { mutableStateOf(false) }   // "Forgot it?": the way back is a backup
    val ask by Ui.ask.collectAsState()
    val scope = rememberCoroutineScope()
    val pattern = remember { app.vault.usesPattern }
    // 1.42: a pattern lock also opens with its unlock password, typed instead of drawn.
    val hasSpare = remember { app.vault.hasSpare }
    // Round 17: with a hardware keyboard (a keyboard cover), typing goes straight into the password; a pattern lock with
    // its unlock password starts on that, which is quicker with keys.
    val hardKeys = androidx.compose.ui.platform.LocalConfiguration.current.let {
        it.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY && it.hardKeyboardHidden != android.content.res.Configuration.HARDKEYBOARDHIDDEN_YES
    }
    var useSpare by remember { mutableStateOf(hasSpare && hardKeys) }
    val drawing = pattern && !useSpare
    // Wrong tries (pattern and unlock password share one count) and their waits (LockWait), in the vault's lock-state
    // file: each try is counted on disk before it's checked, so stopping Muninn in the middle can't skip it. Read off the
    // main thread; nothing can be tried until it's read.
    val tries = remember { LockTries(app) }
    var loaded by remember { mutableStateOf(false) }
    var fails by remember { mutableStateOf(0) }
    var erase by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(tries.elapsed()) }
    var short by remember { mutableStateOf(false) }   // a drawing too short to be any lock: not a try
    LaunchedEffect(Unit) {
        // A try counted before Muninn was stopped mid-check: given back if it was the one that would erase (nothing was
        // learned from it), so a right last try can never erase. Then: erase on, and the last try known to be wrong.
        val read = withContext(Dispatchers.IO) { tries.settle(); LockRead(tries.fails(), tries.erase, tries.waitUntil(), tries.eraseDue()) }
        if (inApp) { loaded = true; return@LaunchedEffect }
        fails = read.fails; erase = read.erase; waitUntil = read.waitUntil
        now = tries.elapsed()
        loaded = true
        // An open vault was opened with the right lock: never an erase then.
        if (read.eraseDue && app.vault.locked.value) LockErase.start(app)
    }
    LaunchedEffect(waitUntil) {
        var ticks = 0
        while (tries.elapsed() < waitUntil) {
            now = tries.elapsed(); kotlinx.coroutines.delay(500)
            // What's left is saved each minute, so a restart loses as little of the wait as it can.
            if (++ticks % 120 == 0) withContext(Dispatchers.IO) { tries.snapshot() }
        }
        now = tries.elapsed()
        // Over: the wait is forgotten, so a restart later can't bring it back.
        if (waitUntil > 0L) withContext(Dispatchers.IO) { tries.waitUntil() }
    }
    val waiting = now < waitUntil
    // Messages still arrive (sealed) during a wait, and calls still ring, as "Incoming call" only (CallService.shown).
    if (forgot) {
        ForgotLock(app) { forgot = false }
        ask?.let { AskDialog(it) }
        return
    }
    fun tryIt(secret: String = pw) {
        if (waiting || busy || !loaded) return
        busy = true; wrong = false; short = false
        if (inApp) {
            val begun = tries.begin(inApp = true)
            scope.launch {
                val right = withContext(Dispatchers.Default) { try { app.vault.check(secret) } catch (_: Exception) { false } }
                busy = false; pw = ""
                if (right) { withContext(Dispatchers.IO) { tries.right() }; done(true); return@launch }
                wrong = true; shakes++; fails = begun?.fails ?: 0
                if (begun?.lockApp == true) { done(false); io.github.ravenor907.muninn.ui.AppLock.raise() }
            }
            return
        }
        scope.launch {
            // The whole try runs to its end even when this screen goes meanwhile (the vault opening swaps it for "Opening",
            // a new window for dark mode or DeX): the count is settled the moment the check answers, before the vault opens,
            // and what follows a right try (the lock coming down, "Make your lock stronger?") still runs.
            withContext(NonCancellable) {
                // Counted and on disk first; null while a wait runs.
                val begun = withContext(Dispatchers.IO) { tries.begin(inApp = false) }
                if (begun == null) { busy = false; waitUntil = withContext(Dispatchers.IO) { tries.waitUntil() }; return@withContext }
                var answer: Boolean? = null
                val ok = withContext(Dispatchers.Default) {   // the key stretching takes a moment
                    app.vault.unlock(secret) { right -> answer = right; if (right) tries.right() else tries.wrong() }
                }
                if (answer == null) withContext(Dispatchers.IO) { tries.wrong() }   // no lock to check against
                busy = false
                pw = ""
                if (ok) {
                    fails = 0
                    io.github.ravenor907.muninn.ui.Panic.unlocked(app); done(true)
                } else if (answer == true) {
                    // The right lock, but what it opens can't be read: the vault's trouble page takes over. Not a wrong try.
                    fails = 0
                } else {
                    wrong = true
                    shakes++
                    fails = begun.fails
                    if (LockWait.erases(begun.fails, begun.erase) && answer == false) { LockErase.start(app); return@withContext }
                    val until = withContext(Dispatchers.IO) { tries.waitedUntil() }
                    if (until > 0) waitUntil = until
                }
            }
        }
    }
    // Fewer dots than any lock ever had (a pocket, a toddler): not counted, not checked.
    fun drawn(dots: List<Int>) {
        if (waiting || busy || !loaded) return
        if (dots.size < LockWait.SHORTEST_PATTERN) { short = true; wrong = false; shakes++ } else tryIt(patternSecret(dots))
    }
    // CI screenshots only: the words after LockSample's tries, as if a wait had just begun.
    val sample = LockSample.fails
    val status = if (sample >= 0) triesStatus(sample, LockSample.erase, LockWait.after(sample, LockSample.erase) > 0,
            LockWait.after(sample, LockSample.erase) - 1_000L, false, true)
        else if (short && !busy && !waiting) SHORT_NOTE
        else if (inApp) (if (busy) "Checking…" else listOf(if (wrong) "That's not it." else "", if (wrong) inAppText(fails) else "").filter { it.isNotEmpty() }.joinToString(" "))
        else triesStatus(fails, erase, waiting, waitUntil - now, busy, wrong)
    val alarm = sample >= 0 || waiting || wrong || short || (!inApp && LockWait.lastLeft(fails, erase) > 0)
    // After 5 wrong patterns the unlock password is offered first: it's the way back, not another way to use up tries.
    val spareLink: @Composable () -> Unit = {
        if (pattern && hasSpare) {
            if (useSpare) LinkText("Draw your pattern instead") { useSpare = false; wrong = false; short = false }
            else if (fails >= io.github.ravenor907.muninn.net.LockWait.STEP) MButton("Use your unlock password", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { useSpare = true; wrong = false; short = false }
            else LinkText("Use your unlock password") { useSpare = true; wrong = false; short = false }
        }
    }
    // Clear of the gesture bar too, so the pattern and Erase never sit under it.
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand()
        // A pattern: the dots sit where Settings › Privacy and lock says (Lower unless changed), for one-handed use.
        // PadPage keeps the words and Forgot it? in reach and the pad on screen, upright or sideways, at any text size.
        if (drawing) PadPage(Modifier.weight(1f).fillMaxWidth().padding(16.dp), padBias(app.identity.patternPos), top = {
            Text("Draw your pattern to open", style = Type.big, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(status.ifEmpty { "Your messages, contacts and keys stay encrypted until you do." },
                style = Type.small.copy(color = if (alarm) Palette.danger else Palette.textMuted),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            spareLink()
            ForgotLink { forgot = true }
        }) {
            PatternPad(error = wrong || short, enabled = loaded && !waiting && !busy, shake = shakes, trail = app.identity.patternTrail) { dots -> drawn(dots) }
        }
        // A password (or the unlock password): scrolls, so with larger size and text settings the field and buttons can always be reached.
        else Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel {
                Text("Muninn is locked", style = Type.big)
                Text(if (pattern) "Type your unlock password. Your messages, contacts and keys stay encrypted until you do."
                    else "Type your password. Your messages, contacts and keys stay encrypted until you do.", style = Type.normal)
                val pwFocus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(hardKeys) { if (hardKeys) try { pwFocus.requestFocus() } catch (_: Exception) {} }
                Field(pw, { pw = it; wrong = false }, if (pattern) "Unlock password" else "Password", Modifier.padding(top = 10.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                    onDone = { if (pw.isNotEmpty() && !busy && !waiting) tryIt() }, focus = pwFocus)
                if (status.isNotEmpty()) Text(status, style = Type.normal.copy(color = if (alarm) Palette.danger else Palette.text),
                    modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                MButton(if (busy) "Opening…" else "Unlock", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = loaded && pw.isNotEmpty() && !busy && !waiting) { tryIt() }
                spareLink()
                ForgotLink { forgot = true }
            }
        }
    }
    // No app-wide question is drawn over the lock: one asked before the lock went up must never be answered here
    // (AppLock closes them), and one that comes in while locked waits for the unlock. Only Forgot it? asks here.
}

/** What the lock screen reads from the lock-state file, off the main thread. */
private class LockRead(val fails: Int, val erase: Boolean, val waitUntil: Long, val eraseDue: Boolean)

/** A drawing with fewer dots than any lock ever had: shaken off, not counted. */
internal const val SHORT_NOTE = "Connect at least 4 dots."

/**
 * What the lock says about wrong tries, in plain words: during a wait, the wait, what comes next and the time left;
 * otherwise what the next wrong tries bring (counting down in the last stage). "" when there's nothing to say.
 */
internal fun triesStatus(fails: Int, erase: Boolean, waiting: Boolean, leftMs: Long, busy: Boolean, wrong: Boolean, checking: String = "Opening…"): String {
    val w = io.github.ravenor907.muninn.net.LockWait
    return when {
        waiting -> w.waitText(fails, erase) + " " + w.leftText(leftMs)
        busy -> checking
        else -> listOf(if (wrong) "That's not it." else "", w.triesText(fails, erase)).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/** CI screenshots only (a debug build started with "ci"): the lock screen's words after [fails] wrong tries. Nothing is counted. */
object LockSample {
    @Volatile var fails = -1
        private set
    @Volatile var erase = false
        private set

    fun show(fails: Int, erase: Boolean) { this.fails = fails; this.erase = erase }
}

/** A small underlined link (Use your unlock password, Draw your pattern instead). */
@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Text(text, style = Type.small.copy(color = Palette.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp).wrapContentHeight()
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onClick() })
}

/**
 * "Erase after too many tries" reached its last wrong try: everything is erased at once, the same as Erase everything,
 * on its own thread so nothing on screen can stop it halfway, then Muninn starts again as new.
 */
internal object LockErase {
    @Volatile private var running = false

    fun start(app: Muninn) {
        if (running) return
        running = true
        val ctx = app.applicationContext
        Thread {
            try { app.vault.eraseAll() } catch (_: Exception) {}
            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component
            if (launch != null) try { ctx.startActivity(android.content.Intent.makeRestartActivityTask(launch)) } catch (_: Exception) {}
            Runtime.getRuntime().exit(0)
        }.start()
    }
}

/** "Forgot it?" under the lock. */
@Composable
private fun ForgotLink(onClick: () -> Unit) {
    Text("Forgot it?", style = Type.small.copy(color = Palette.accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = Modifier.padding(top = 8.dp).heightIn(min = 48.dp).wrapContentHeight()
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Restore from a backup") { onClick() })
}

/**
 * The lock forgotten. Nobody can reset it (the owner's choice), so the way back is a backup: what's here is erased
 * and the usual Restore from a backup runs (its last step asks first, holding the button). No backup: clear
 * Muninn's storage in Android settings and start again.
 */
@Composable
private fun ForgotLock(app: Muninn, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var restoring by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler { if (restoring) restoring = false else onBack() }
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand()
        Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!restoring) Panel {
                Text("Forgot your lock?", style = Type.big)
                Text("Your chats are encrypted with it, so nobody can open or reset it.", style = Type.normal, modifier = Modifier.padding(top = 4.dp))
                Text("If you made a backup, you can erase what's on this phone and put the backup back. Messages that came after the backup are lost. " +
                    "Then you set a new lock.", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                MButton("Restore from a backup", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { restoring = true }
                Text("No backup? Clear Muninn's storage in Android settings (Storage › Clear storage) to start again with a new account. " +
                    "Your contacts will need to add you again.", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                MButton("Open Android settings", Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    try {
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", ctx.packageName, null)))
                    } catch (_: Exception) { Ui.say("Open Android Settings › Apps › Muninn.", true) }
                }
                MButton("Back to the lock", Modifier.fillMaxWidth().padding(top = 8.dp)) { onBack() }
            }
            if (restoring) Panel {
                RestoreFlow(app, erases = true, onCancel = { restoring = false }) { o ->
                    val files = io.github.ravenor907.muninn.net.Backup.prepare(app, o)
                    // The lock isn't in the backup: after the restart a new one is set before any chat shows.
                    val old = app.vault.startOverKeeping(needsLock = true)
                    io.github.ravenor907.muninn.net.Backup.restore(ctx, app.vault, files)
                    // What was here can't be opened without the lock: erased, not kept. The wrong tries go too: they counted
                    // against a lock that's gone (this took the backup and its Backup password, and erased what was here).
                    // So does "Erase after too many tries": the new lock starts with it off, like any new lock.
                    old.deleteRecursively()
                    try { app.vault.tries.reset() } catch (_: Exception) {}
                    "Restored. Muninn is restarting. Set a new lock next: your old one isn't in the backup."
                }
            }
        }
    }
}

/** Space above and below the pattern for Top, Middle and Lower. */
internal fun padWeights(pos: String): Pair<Float, Float> = when (pos) { "top" -> 0.15f to 1f; "middle" -> 1f to 1f; else -> 1f to 0.12f }

/** The same places as an alignment bias for [PadPage]: -1 top, 0 middle, 1 bottom. */
internal fun padBias(pos: String): Float { val (t, b) = padWeights(pos); return (t - b) / (t + b) }

/**
 * Choose and confirm a lock: a 9-dot pattern (at least 6 dots) or a password (at least 6 characters). A pattern also
 * needs its unlock password (1.42), typed twice; either one opens Muninn.
 * Used on first open (suggested, with Skip) and for Settings › Change lock. [onCancel] null = no Cancel button.
 * [cancelText]: what the Cancel button says. [header]: the page's own title and words, above the choice, so they
 * scroll with it and never squeeze the pad or push Cancel and Skip off the screen. [recallNow]: the unlock password
 * is asked once more from memory right after it's saved; false (first setup) asks at the end of setup instead.
 */
@Composable
fun LockSetup(app: Muninn, onCancel: (() -> Unit)? = null, fill: Boolean = false, cancelText: String = "Cancel",
              recallNow: Boolean = true, header: @Composable ColumnScope.() -> Unit = {}, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usePattern by remember { mutableStateOf(true) }
    var first by remember { mutableStateOf<List<Int>?>(null) }
    var note by remember { mutableStateOf("") }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var shakes by remember { mutableStateOf(0) }   // a pattern that won't do shakes the pad
    // The pattern drawn twice, waiting for its unlock password; then the unlock password asked from memory.
    var drawn by remember { mutableStateOf<String?>(null) }
    var recalling by remember { mutableStateOf(false) }
    fun save(secret: String, pattern: Boolean, spare: String? = null) {
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.Default) { try { app.vault.setPassword(secret, pattern, spare); true } catch (_: Exception) { false } }
            saving = false
            pw1 = ""; pw2 = ""
            if (!saved) { Ui.say("That didn't save. Try again.", true); return@launch }
            if (pattern) {
                Ui.say("Pattern and unlock password set. Nobody can reset them, so a backup is a good idea.")
                // Asked from memory next; kept until it's answered, so leaving the page asks again at the next start.
                setRecallPending(app, true)
                if (recallNow) { recalling = true; return@launch }
            } else Ui.say("Password set. Nobody can reset it, so a backup is a good idea.")
            onDone()
        }
    }
    val cancel: @Composable ColumnScope.() -> Unit = {
        if (onCancel != null) MButton(cancelText, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !saving) { onCancel() }
    }
    if (recalling) {
        UnlockPasswordSteps(app, recall = true, fill = fill) { recalling = false; onDone() }
        return
    }
    val pattern = drawn
    if (pattern != null) {
        // The pattern's unlock password: typed twice, then saved with the pattern (two wraps of the same key).
        val scroll = androidx.compose.foundation.rememberScrollState()
        Column(if (fill) Modifier.fillMaxSize().imePadding().verticalScroll(scroll) else Modifier,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            header()
            SpareWords()
            SpareFields(pw1, { pw1 = it }, pw2, { pw2 = it }) { if (spareOk(pw1, pw2) && !saving) save(pattern, true, pw1) }
            MButton(if (saving) "Saving…" else "Save", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent,
                enabled = spareOk(pw1, pw2) && !saving) { save(pattern, true, pw1) }
            MButton("Draw a different pattern", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !saving) { drawn = null; first = null; pw1 = ""; pw2 = "" }
            cancel()
        }
        return
    }
    val choose: @Composable ColumnScope.() -> Unit = {
        header()
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Pattern", Modifier.weight(1f), if (usePattern) Kind.Accent else Kind.Normal) { usePattern = true; first = null; note = "" }
            MButton("Password", Modifier.weight(1f), if (!usePattern) Kind.Accent else Kind.Normal) { usePattern = false; first = null; note = "" }
        }
        if (usePattern) Text(when {
            saving -> "Saving…"
            note.isNotEmpty() -> note
            first == null -> "Draw a pattern: connect at least 6 dots. Next you set an unlock password too."
            else -> "Draw it again to confirm."
        }, style = Type.normal.copy(color = if (note.isNotEmpty()) Palette.danger else Palette.text), modifier = Modifier.padding(top = 10.dp))
    }
    if (usePattern) {
        val pad: @Composable () -> Unit = {
            PatternPad(error = note.isNotEmpty(), enabled = !saving, shake = shakes) { dots ->
                val f = first
                when {
                    dots.size < Vault.LockRules.MIN -> { note = "Connect at least 6 dots."; shakes++ }
                    f == null -> { first = dots; note = "" }
                    f != dots -> { first = null; note = "That didn't match. Start over."; shakes++ }
                    else -> { drawn = patternSecret(dots); note = "" }
                }
            }
        }
        // [fill]: the rest of the screen is ours, so the pattern sits where Settings › Privacy and lock says, and the
        // page scrolls (or goes side by side) whenever it doesn't fit, so Cancel and Skip can always be reached.
        if (fill) PadPage(Modifier.fillMaxSize(), padBias(app.identity.patternPos), top = choose, bottom = cancel, pad = pad)
        else Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            choose()
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { pad() }
            cancel()
        }
        return
    }
    // A password page scrolls above the keyboard, so the fields, Save and Cancel stay reachable at large text sizes.
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(if (fill) Modifier.fillMaxSize().imePadding().verticalScroll(scroll) else Modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        choose()
        Field(pw1, { pw1 = it }, "New password", Modifier.padding(top = 10.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
        Field(pw2, { pw2 = it }, "Type it again", Modifier.padding(top = 8.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
            onDone = { if (pw1.length >= Vault.LockRules.MIN && pw1 == pw2 && !saving) save(pw1, false) })
        if (pw1.isNotEmpty() && pw1.length < Vault.LockRules.MIN) Text("At least 6 characters.", style = Type.small.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 4.dp))
        if (pw2.isNotEmpty() && pw1 != pw2) Text("They don't match yet.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
        MButton(if (saving) "Saving…" else "Save password", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw1.length >= Vault.LockRules.MIN && pw1 == pw2 && !saving) { save(pw1, false) }
        cancel()
    }
}

/** What the unlock password is, above its two fields. */
@Composable
private fun SpareWords() {
    Text("Unlock password", style = Type.big, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
    Text("A pattern also needs an unlock password. Either one opens Muninn, so if you forget the pattern, the unlock password still gets you in. " +
        "It isn't your Backup password: that one is only for backup files.", style = Type.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
}

/** An unlock password that can be saved: long enough, typed the same twice, and not one that reads as a drawn pattern. */
internal fun spareOk(pw1: String, pw2: String): Boolean = pw1.length >= Vault.LockRules.MIN && pw1 == pw2 && !pw1.startsWith(DRAWN)

/** How Muninn writes a drawn pattern: an unlock password starting this way could never open it. */
private const val DRAWN = "pattern:"

/** The unlock password typed twice, with what's still missing. [onDone]: Enter in the second field (a hardware keyboard). */
@Composable
private fun SpareFields(pw1: String, set1: (String) -> Unit, pw2: String, set2: (String) -> Unit, onDone: () -> Unit = {}) {
    Field(pw1, set1, "Unlock password", Modifier.padding(top = 10.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
    Field(pw2, set2, "Type it again", Modifier.padding(top = 8.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password), onDone = onDone)
    if (pw1.isNotEmpty() && pw1.length < Vault.LockRules.MIN) Text("At least 6 characters.", style = Type.small.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 4.dp))
    if (pw1.startsWith(DRAWN)) Text("It can't start with \"$DRAWN\".", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
    if (pw2.isNotEmpty() && pw1 != pw2) Text("They don't match yet.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
}

/**
 * The unlock password on its own (the pattern stays): [recall] false sets it (typed twice), then asks for it once more
 * from memory; true only asks from memory. Asking from memory checks it by opening its wrap, then forgets it. Like every
 * prompt inside the open app it never counts toward the waits or the erase; 5 wrong in a row lock Muninn. Wrong: try
 * again, or set a new one (after the current lock, so nobody holding the open phone can swap it).
 */
@Composable
fun UnlockPasswordSteps(app: Muninn, recall: Boolean, fill: Boolean = true, header: @Composable ColumnScope.() -> Unit = {}, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf(recall) }
    var proving by remember { mutableStateOf(false) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    val tries = remember { LockTries(app) }
    if (proving) {
        ConfirmLock(app, "Set a new unlock password", Modifier.fillMaxSize(), onCancel = { proving = false }) {
            proving = false; asking = false; wrong = false; pw1 = ""; pw2 = ""
        }
        return
    }
    fun check() {
        if (pw1.isEmpty() || busy) return
        busy = true
        val given = pw1
        val begun = tries.begin(inApp = true)
        scope.launch {
            val right = withContext(Dispatchers.Default) { try { app.vault.checkSpare(given) } catch (_: Exception) { false } }
            busy = false
            pw1 = ""
            if (right) {
                withContext(Dispatchers.IO) { tries.right() }
                setRecallPending(app, false); Ui.say("That's it. Keep it somewhere safe."); onDone()
            } else {
                wrong = true
                if (begun?.lockApp == true) io.github.ravenor907.muninn.ui.AppLock.raise()
            }
        }
    }
    fun save() {
        if (!spareOk(pw1, pw2) || busy) return
        busy = true
        val given = pw1
        scope.launch {
            val saved = withContext(Dispatchers.Default) { try { app.vault.setSpare(given) } catch (_: Exception) { false } }
            busy = false
            pw1 = ""; pw2 = ""
            if (saved) { setRecallPending(app, true); asking = true } else Ui.say("That didn't save. Try again.", true)
        }
    }
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(if (fill) Modifier.fillMaxSize().imePadding().verticalScroll(scroll) else Modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        header()
        if (asking) {
            Text("Type your unlock password", style = Type.big, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Text("From memory this time, so you know you'll have it if you forget your pattern.", style = Type.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Field(pw1, { pw1 = it; wrong = false }, "Unlock password", Modifier.padding(top = 10.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
                onDone = { check() })
            if (wrong) Text("That's not it. Try again, or set a new one.", style = Type.normal.copy(color = Palette.danger),
                modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
            MButton(if (busy) "Checking…" else "Check", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw1.isNotEmpty() && !busy) { check() }
            if (wrong) MButton("Set a new unlock password", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { proving = true }
        } else {
            SpareWords()
            SpareFields(pw1, { pw1 = it }, pw2, { pw2 = it }) { save() }
            MButton(if (busy) "Saving…" else "Save", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent,
                enabled = spareOk(pw1, pw2) && !busy) { save() }
        }
    }
}

/** The unlock password still has to be asked once more from memory (set during first setup, asked at its end). */
fun recallPending(app: Muninn): Boolean =
    app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE).getBoolean(UNLOCK_RECALL, false) && app.vault.hasSpare

internal fun setRecallPending(app: Muninn, on: Boolean) {
    app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE).edit().putBoolean(UNLOCK_RECALL, on).apply()
}

private const val UNLOCK_RECALL = "unlockRecall"

/**
 * After an unlock: a pattern set before 1.42 gets its unlock password now ("set"), and one set during first setup is
 * asked once more from memory ("recall") once setup is over. Null when there's nothing to ask.
 */
fun spareStep(app: Muninn): String? = when {
    app.vault.needsSpare -> "set"
    recallPending(app) -> "recall"
    else -> null
}

/** [spareStep]'s page, full screen, before any chat shows. No Skip: the unlock password is the way back in. */
@Composable
fun UnlockPasswordPage(app: Muninn, step: String, done: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand {}
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            UnlockPasswordSteps(app, recall = step == "recall", header = {
                if (step == "set") {
                    Text("One more step for your pattern", style = Type.big, modifier = Modifier.fillMaxWidth())
                    Text("Muninn now asks for an unlock password with every pattern. ${io.github.ravenor907.muninn.net.LockWait.EXPLAINER}",
                        style = Type.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }) { done() }
        }
    }
}

/** First open: a lock is suggested before anything else, with Skip to go without one. */
@Composable
fun FirstLock(app: Muninn, done: () -> Unit) {
    val ask by Ui.ask.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand {}
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            LockSetup(app, onCancel = {
                Ui.sure(Ask("Skip the lock?", "Without a lock, anyone who can use this phone can open Muninn and read your chats. " +
                    "You can set one any time in Settings › Privacy and lock.", "Skip") { skipLock(app); done() })
            }, fill = true, cancelText = "Skip", header = {
                Text("Protect Muninn", style = Type.big, modifier = Modifier.fillMaxWidth())
                Text("Muninn opens with your pattern or password, and everything it keeps is encrypted with it. There's no way to recover it, so pick one you'll remember.",
                    style = Type.small, modifier = Modifier.fillMaxWidth())
                Text(io.github.ravenor907.muninn.net.LockWait.EXPLAINER, style = Type.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }) { done() }
        }
    }
    ask?.let { AskDialog(it) }
}

/**
 * Restored after "Forgot it?" (the vault's lockNeeded): the same lock step as first setup, before any chat shows.
 * No Skip here: the person just chose to restore after forgetting their lock.
 */
@Composable
fun NewLock(app: Muninn, done: () -> Unit) {
    val ask by Ui.ask.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand {}
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            LockSetup(app, onCancel = null, fill = true, header = {
                Text("Protect Muninn", style = Type.big, modifier = Modifier.fillMaxWidth())
                Text("Set a new lock. Your old one isn't in the backup.", style = Type.normal, modifier = Modifier.fillMaxWidth())
                Text("Your chats are locked with it, so nobody can reset it. Pick one you'll remember, and make a new backup " +
                    "(Settings › Devices and backup) once you're in.", style = Type.small, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }) { done() }
        }
    }
    ask?.let { AskDialog(it) }
}

/**
 * First open, after the lock: "What should people call you?", with the random name filled in. Continue keeps what's
 * typed (Settings › Profile changes it later); Skip keeps the random name.
 */
@Composable
fun FirstName(app: Muninn, done: () -> Unit) {
    var name by remember { mutableStateOf(app.identity.username) }
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Brand {}
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("What should people call you?", style = Type.big)
            Text("Change it any time in Settings.", style = Type.small)
            Field(name, { name = it.take(24) }, "A name", Modifier.padding(top = 12.dp))
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Skip", Modifier.weight(1f)) { app.identity.nameAsked = true; done() }
                MButton("Continue", Modifier.weight(1f), Kind.Accent, enabled = name.isNotBlank()) {
                    saveName(app, name); app.identity.nameAsked = true; done()
                }
            }
        }
    }
}

/** The person chose no lock: first open doesn't suggest one again. */
fun skipLock(app: Muninn) = app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE).edit().putBoolean(LOCK_SKIPPED, true).apply()

/** Whether a lock is set, or the person chose to go without one. */
fun lockChosen(app: Muninn): Boolean = app.vault.hasPassword ||
    app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE).getBoolean(LOCK_SKIPPED, false)

private const val LOCK_SKIPPED = "lockSkipped"

/**
 * Wrong tries at Muninn's lock: one count for the pattern and the unlock password at the lock screen, and the 1.42 waits
 * ([io.github.ravenor907.muninn.net.LockWait]), kept in the vault's lock-state file across restarts and timed by the time
 * since start-up (with what's left and the wall clock kept for a restart), so changing the clock doesn't skip them. A try
 * is counted on disk ([begin]) before it's checked. Prompts inside the open app (Confirm it's you, own chats, the unlock
 * password from memory) are never counted on disk and never bring a wait or the erase: 5 wrong in a row lock Muninn.
 */
internal class LockTries(private val app: Muninn) {
    private val store get() = app.vault.tries
    fun elapsed() = android.os.SystemClock.elapsedRealtime()

    /** When the wait after too many tries ends (in [elapsed] time); now when there is none. Writes to disk: off the main thread. */
    fun waitUntil(): Long = elapsed() + store.left()

    /** Wrong tries in a row so far, and whether "Erase after too many tries" is on. */
    fun fails(): Int = store.fails()
    val erase: Boolean get() = store.erase
    fun eraseDue(): Boolean = store.eraseDue()
    /** An erasing try whose check never answered (Muninn stopped mid-check) is given back. Off the main thread. */
    fun settle() = store.settle()
    /** Saves what's left of a running wait. Off the main thread. */
    fun snapshot() = store.snapshot()

    /** Counts a try (on disk at the lock screen, in memory only for [inApp]) before the check. Null while a wait runs. */
    fun begin(inApp: Boolean): io.github.ravenor907.muninn.net.LockStore.Begun? = store.begin(inApp)

    /** The right lock: the count starts again. */
    fun right() = store.right()
    /** A counted try was wrong. */
    fun wrong() = store.wrong()

    /** After a wrong check (already counted by [begin]): when the wait it brought ends, or 0 when there's none. */
    fun waitedUntil(): Long { val l = store.left(); return if (l > 0) elapsed() + l else 0L }
}

/** Inside the open app, after [fails] wrong in a row: what the next ones bring. "" before the first. */
internal fun inAppText(fails: Int): String {
    if (fails <= 0) return ""
    val left = io.github.ravenor907.muninn.net.LockWait.IN_APP_LOCK_AT - fails
    return if (left <= 1) "1 more wrong try locks Muninn." else "$left more wrong tries lock Muninn."
}

/**
 * Asks for the current lock before anything that changes it or gets around it: Change lock, Turn off the lock, turning
 * off panic lock, linking a tablet or computer, moving to another device. Muninn is open, so a wrong try here never
 * counts toward the lock screen's waits or the erase; after 5 wrong in a row Muninn locks (the lock screen takes over).
 * [what] is the page's title. [onOk] runs once the right pattern or password is given; Cancel and Back run [onCancel].
 */
@Composable
fun ConfirmLock(app: Muninn, what: String, modifier: Modifier = Modifier, onCancel: () -> Unit, onOk: () -> Unit) {
    val tries = remember { LockTries(app) }
    val pattern = remember { app.vault.usesPattern }
    val hasSpare = remember { app.vault.hasSpare }
    var useSpare by remember { mutableStateOf(false) }
    val drawing = pattern && !useSpare
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var short by remember { mutableStateOf(false) }
    var shakes by remember { mutableStateOf(0) }
    var fails by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val ok by androidx.compose.runtime.rememberUpdatedState(onOk)
    val cancelNow by androidx.compose.runtime.rememberUpdatedState(onCancel)
    androidx.activity.compose.BackHandler { cancelNow() }
    fun tryIt(secret: String) {
        if (busy) return
        busy = true; wrong = false; short = false
        // In memory only: never a wait, never the erase.
        val begun = tries.begin(inApp = true)
        scope.launch {
            val right = withContext(Dispatchers.Default) { try { app.vault.check(secret) } catch (_: Exception) { false } }
            busy = false
            pw = ""
            if (right) { withContext(Dispatchers.IO) { tries.right() }; ok(); return@launch }
            wrong = true; shakes++; fails = begun?.fails ?: 0
            if (begun?.lockApp == true) { cancelNow(); io.github.ravenor907.muninn.ui.AppLock.raise() }
        }
    }
    fun drawn(dots: List<Int>) {
        if (busy) return
        if (dots.size < io.github.ravenor907.muninn.net.LockWait.SHORTEST_PATTERN) { short = true; wrong = false; shakes++ } else tryIt(patternSecret(dots))
    }
    val status = when {
        busy -> "Checking…"
        short -> SHORT_NOTE
        else -> listOf(if (wrong) "That's not it." else "", if (wrong) inAppText(fails) else "").filter { it.isNotEmpty() }.joinToString(" ")
    }
    val top: @Composable ColumnScope.() -> Unit = {
        Text(what, style = Type.big, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(if (drawing) "Draw your current pattern first." else if (pattern) "Type your unlock password first." else "Type your current password first.", style = Type.normal,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        if (status.isNotEmpty()) Text(status, style = Type.small.copy(color = if (busy) Palette.textMuted else Palette.danger),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
        if (pattern && hasSpare) LinkText(if (useSpare) "Draw your pattern instead" else "Use your unlock password") { useSpare = !useSpare; wrong = false; short = false }
    }
    val cancel: @Composable ColumnScope.() -> Unit = {
        MButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp)) { cancelNow() }
    }
    if (drawing) PadPage(modifier, 0f, top = top, bottom = cancel) {
        PatternPad(error = wrong || short, enabled = !busy, shake = shakes, trail = app.identity.patternTrail) { dots -> drawn(dots) }
    }
    else Column(modifier.imePadding().verticalScroll(androidx.compose.foundation.rememberScrollState()), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        top()
        Field(pw, { pw = it; wrong = false }, if (pattern) "Unlock password" else "Password", Modifier.padding(top = 10.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password),
            onDone = { if (pw.isNotEmpty() && !busy) tryIt(pw) })
        MButton(if (busy) "Checking…" else "Continue", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw.isNotEmpty() && !busy) { tryIt(pw) }
        cancel()
    }
}

/**
 * Something that needs the current lock first, from anywhere in Muninn: [LockGateDialog] shows [ConfirmLock] over the
 * screen and runs it once the lock is given. Closed when the lock goes up (closeForLock).
 */
object LockGate {
    class Gate(val what: String, val then: () -> Unit)

    var open by mutableStateOf<Gate?>(null)
        private set

    /** Runs [then] after the current lock is given; at once when no lock is set. One at a time: a second ask while one is open is dropped (the person asks again). */
    fun ask(app: Muninn, what: String, then: () -> Unit) {
        if (!app.vault.hasPassword) { then(); return }
        if (open == null) open = Gate(what, then)
    }

    fun close() { open = null }
}

/** Draws [LockGate]'s question, full screen, over whatever is open. */
@Composable
fun LockGateDialog(app: Muninn) {
    val g = LockGate.open ?: return
    androidx.compose.ui.window.Dialog(onDismissRequest = { LockGate.close() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            androidx.compose.runtime.key(g) {
                ConfirmLock(app, g.what, Modifier.fillMaxSize(), onCancel = { LockGate.close() }) { LockGate.close(); g.then() }
            }
        }
    }
}
