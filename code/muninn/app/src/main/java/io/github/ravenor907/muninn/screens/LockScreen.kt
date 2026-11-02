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
import io.github.ravenor907.muninn.net.Vault
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.PatternPad
import io.github.ravenor907.muninn.ui.patternSecret
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.LaunchedEffect
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

/** Shown while Muninn is locked with a password. Nothing else is readable until it's typed. */
@Composable
fun LockScreen(app: Muninn, done: (Boolean) -> Unit) {
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
    val prefs = remember { app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE) }
    val pattern = remember { app.vault.usesPattern }
    // After every 5 wrong tries, wait (kept across restarts), so guessing is slow. Timed by the time since
    // start-up and the boot count (LockWait), so changing the clock doesn't skip it.
    fun bootCount() = try { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0) } catch (_: Exception) { 0 }
    fun elapsed() = android.os.SystemClock.elapsedRealtime()
    fun storedLeft(): Long {
        val s = io.github.ravenor907.muninn.net.LockWait.State(prefs.getLong("lockAt", 0L), prefs.getLong("lockWait", 0L), prefs.getInt("lockBoot", -1))
        val boot = bootCount()
        val left = io.github.ravenor907.muninn.net.LockWait.left(s, elapsed(), boot)
        // Restarted since: the wait starts again from now, in this start-up's time.
        if (left > 0 && s.boot != boot) prefs.edit().putLong("lockAt", elapsed() + left).putInt("lockBoot", boot).apply()
        return left
    }
    var waitUntil by remember { mutableStateOf(elapsed() + storedLeft()) }
    var now by remember { mutableStateOf(elapsed()) }
    LaunchedEffect(waitUntil) { while (elapsed() < waitUntil) { now = elapsed(); kotlinx.coroutines.delay(500) }; now = elapsed() }
    val waiting = now < waitUntil
    if (forgot) {
        ForgotLock(app) { forgot = false }
        ask?.let { AskDialog(it) }
        return
    }
    fun tryIt(secret: String = pw) {
        if (waiting || busy) return
        busy = true; wrong = false
        scope.launch {
            val ok = withContext(Dispatchers.Default) { app.vault.unlock(secret) }   // the key stretching takes a moment
            busy = false
            if (ok) {
                pw = ""; prefs.edit().putInt("lockFails", 0).remove("lockAt").remove("lockWait").remove("lockBoot").apply()
                io.github.ravenor907.muninn.ui.Panic.unlocked(); done(true)
            } else {
                wrong = true
                shakes++
                val fails = prefs.getInt("lockFails", 0) + 1
                prefs.edit().putInt("lockFails", fails).apply()
                val wait = io.github.ravenor907.muninn.net.LockWait.after(fails)
                if (wait > 0) {
                    waitUntil = elapsed() + wait
                    prefs.edit().putLong("lockAt", waitUntil).putLong("lockWait", wait).putInt("lockBoot", bootCount()).apply()
                }
            }
        }
    }
    // Clear of the gesture bar too, so the pattern and Erase never sit under it.
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand()
        // A pattern: the dots sit where Settings › Lock and privacy says (Lower unless changed), for one-handed use.
        if (pattern) Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text("Draw your pattern to open", style = Type.big)
            Text(when {
                waiting -> "Too many tries. Try again in ${(waitUntil - now + 999) / 1000} s."
                busy -> "Opening…"
                wrong -> "That's not it. Try again."
                else -> "Your messages, contacts and keys stay encrypted until you do."
            }, style = Type.small.copy(color = if (waiting || wrong) Palette.danger else Palette.textMuted), modifier = Modifier.padding(top = 4.dp))
            ForgotLink { forgot = true }
            val (top, bottom) = padWeights(app.identity.patternPos)
            Spacer(Modifier.weight(top))
            PatternPad(error = wrong, enabled = !waiting && !busy, shake = shakes, trail = app.identity.patternTrail) { dots -> tryIt(patternSecret(dots)) }
            Spacer(Modifier.weight(bottom))
        }
        // A password: scrolls, so with larger size and text settings the field and buttons can always be reached.
        else Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel {
                Text("Muninn is locked", style = Type.big)
                Text(if (pattern) "Draw your pattern. Your messages, contacts and keys stay encrypted until you do."
                    else "Type your password. Your messages, contacts and keys stay encrypted until you do.", style = Type.normal)
                if (pattern) {
                    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        PatternPad(error = wrong, enabled = !waiting && !busy, shake = shakes, trail = app.identity.patternTrail) { dots -> tryIt(patternSecret(dots)) }
                    }
                } else {
                    Field(pw, { pw = it; wrong = false }, "Password", Modifier.padding(top = 10.dp), secret = true,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
                when {
                    waiting -> Text("Too many tries. Try again in ${(waitUntil - now + 999) / 1000} s.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                    busy -> Text("Opening…", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                    wrong -> Text("That's not it. Try again.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                }
                if (!pattern) MButton(if (busy) "Opening…" else "Unlock", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw.isNotEmpty() && !busy && !waiting) { tryIt() }
                ForgotLink { forgot = true }
            }
        }
    }
    ask?.let { AskDialog(it) }
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
                    // What was here can't be opened without the lock: erased, not kept. The wrong tries go too.
                    old.deleteRecursively()
                    app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE).edit()
                        .remove("lockFails").remove("lockAt").remove("lockWait").remove("lockBoot").commit()
                    "Restored. Muninn is restarting. Set a new lock next: your old one isn't in the backup."
                }
            }
        }
    }
}

/** Space above and below the pattern for Top, Middle and Lower. */
internal fun padWeights(pos: String): Pair<Float, Float> = when (pos) { "top" -> 0.15f to 1f; "middle" -> 1f to 1f; else -> 1f to 0.12f }

/**
 * Choose and confirm a lock: a 9-dot pattern (at least 6 dots) or a password (at least 6 characters).
 * Used on first open (suggested, with Skip) and for Settings › Change lock. [onCancel] null = no Cancel button.
 * [cancelText]: what the Cancel button says.
 */
@Composable
fun LockSetup(app: Muninn, onCancel: (() -> Unit)? = null, fill: Boolean = false, cancelText: String = "Cancel", onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var usePattern by remember { mutableStateOf(true) }
    var first by remember { mutableStateOf<List<Int>?>(null) }
    var note by remember { mutableStateOf("") }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var shakes by remember { mutableStateOf(0) }   // a pattern that won't do shakes the pad
    fun save(secret: String, pattern: Boolean) {
        saving = true
        scope.launch {
            withContext(Dispatchers.Default) { app.vault.setPassword(secret, pattern) }
            saving = false
            Ui.say(if (pattern) "Pattern set. Nobody can reset it, so a backup is a good idea." else "Password set. Nobody can reset it, so a backup is a good idea.")
            onDone()
        }
    }
    // [fill]: the rest of the screen is ours, so the pattern sits where Settings › Lock and privacy says.
    // A password page scrolls above the keyboard, so the fields and Save stay reachable at large text sizes; the
    // pattern page keeps its spacing so the dots sit where they're set to.
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(if (fill) Modifier.fillMaxSize().then(if (usePattern) Modifier else Modifier.imePadding().verticalScroll(scroll)) else Modifier,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Pattern", Modifier.weight(1f), if (usePattern) Kind.Accent else Kind.Normal) { usePattern = true; first = null; note = "" }
            MButton("Password", Modifier.weight(1f), if (!usePattern) Kind.Accent else Kind.Normal) { usePattern = false; first = null; note = "" }
        }
        if (usePattern) {
            Text(when {
                saving -> "Saving…"
                note.isNotEmpty() -> note
                first == null -> "Draw a pattern: connect at least 6 dots."
                else -> "Draw it again to confirm."
            }, style = Type.normal.copy(color = if (note.isNotEmpty()) Palette.danger else Palette.text), modifier = Modifier.padding(top = 10.dp))
            val (top, bottom) = padWeights(app.identity.patternPos)
            if (fill) Spacer(Modifier.weight(top))
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                PatternPad(error = note.isNotEmpty(), enabled = !saving, shake = shakes) { dots ->
                    val f = first
                    when {
                        dots.size < Vault.LockRules.MIN -> { note = "Connect at least 6 dots."; shakes++ }
                        f == null -> { first = dots; note = "" }
                        f != dots -> { first = null; note = "That didn't match. Start over."; shakes++ }
                        else -> save(patternSecret(dots), true)
                    }
                }
            }
            if (fill) Spacer(Modifier.weight(bottom))
        } else {
            Field(pw1, { pw1 = it }, "New password", Modifier.padding(top = 10.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
            Field(pw2, { pw2 = it }, "Type it again", Modifier.padding(top = 8.dp), secret = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (pw1.isNotEmpty() && pw1.length < Vault.LockRules.MIN) Text("At least 6 characters.", style = Type.small.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 4.dp))
            if (pw2.isNotEmpty() && pw1 != pw2) Text("They don't match yet.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
            MButton(if (saving) "Saving…" else "Save password", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = pw1.length >= Vault.LockRules.MIN && pw1 == pw2 && !saving) { save(pw1, false) }
        }
        if (onCancel != null) MButton(cancelText, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !saving) { onCancel() }
    }
}

/** First open: a lock is suggested before anything else, with Skip to go without one. */
@Composable
fun FirstLock(app: Muninn, done: () -> Unit) {
    val ask by Ui.ask.collectAsState()
    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand {}
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("Protect Muninn", style = Type.big)
            Text("Muninn opens with your pattern or password, and everything it keeps is encrypted with it. There's no way to recover it, so pick one you'll remember.",
                style = Type.small)
            LockSetup(app, onCancel = {
                Ui.sure(Ask("Skip the lock?", "Without a lock, anyone who can use this phone can open Muninn and read your chats. " +
                    "You can set one any time in Settings › Lock and privacy.", "Skip") { skipLock(app); done() })
            }, fill = true, cancelText = "Skip") { done() }
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
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text("Protect Muninn", style = Type.big)
            Text("Set a new lock. Your old one isn't in the backup.", style = Type.normal)
            Text("Your chats are locked with it, so nobody can reset it. Pick one you'll remember, and make a new backup " +
                "(Settings › Backup) once you're in.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            LockSetup(app, onCancel = null, fill = true) { done() }
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
