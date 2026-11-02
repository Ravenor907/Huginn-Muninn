package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Move
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Said wherever a move is about to start. */
const val MOVE_WARNING = "Only if it's yours and here."

/**
 * Two ways to use another device, on the code screen: "Use on both" (linked devices) and "Move here"
 * (everything moves to this device, and the old one is locked out). Also "Move from this device", for the old one.
 */
@Composable
fun MoveSection(app: Muninn) {
    var showing by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun takeIt(text: String) {
        scope.launch {
            val err = withContext(Dispatchers.IO) { Move.take(app, text) }
            if (err != null) Ui.say(err, true) else pasted = ""
        }
    }
    Section("Move here")
    Panel {
        Text("Bring everything here over home Wi-Fi.", style = Type.small)
        MButton("Move here", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent) { showing = true }
    }
    Section("Move from this device")
    Panel {
        Text("Scan the new device's code. Same Wi-Fi.", style = Type.small)
        Text(MOVE_WARNING, style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
        if (scanning) QrScanner(Modifier.fillMaxWidth().padding(top = 8.dp)) { code -> scanning = false; takeIt(code) }
        // Moving sends everything to the other device: the current lock first.
        else MButton("Scan the code", Modifier.fillMaxWidth().padding(top = 8.dp), icon = "qr") { LockGate.ask(app, "Move from this device") { scanning = true } }
        Field(pasted, { pasted = it }, "Or paste the move code", Modifier.padding(top = 8.dp), single = false)
        MButton("Next", Modifier.fillMaxWidth().padding(top = 8.dp), Kind.Accent, enabled = pasted.isNotBlank()) { val code = pasted; LockGate.ask(app, "Move from this device") { takeIt(code) } }
    }
    if (showing) MoveHereDialog(app) { showing = false }
}

/** On the new device: the code, then the 4 emoji, then the parts coming in. */
@Composable
fun MoveHereDialog(app: Muninn, onDone: () -> Unit) {
    val code = remember { Move.showCode(app) }
    val inc by Move.incoming.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Back is Cancel (once everything is here, there's nothing to cancel); a tap beside the dialog does nothing.
    AlertDialog(onDismissRequest = { if (inc?.state != "done") { Move.cancelHere(app); onDone() } }, properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), containerColor = Palette.panel,
        title = { Text(when (inc?.state) { "hello", "receiving" -> "Moving from ${inc?.from}"; "done" -> "Moved here"; else -> "Move here" }, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                val i = inc
                when (i?.state) {
                    "hello", "receiving" -> {
                        Text("Same on both screens?", style = Type.normal)
                        Text(i.emoji, style = Type.big, modifier = Modifier.padding(vertical = 12.dp))
                        if (i.of > 0) Text("${i.have} of ${i.of} parts here", style = Type.small)
                        if (i.confirmed) Text("Almost done. Muninn reopens as you.", style = Type.small, textAlign = TextAlign.Center)
                    }
                    "done" -> Text("Done. Muninn reopens as you.", style = Type.normal)
                    else -> {
                        Text("Scan this on your old device.",
                            style = Type.small)
                        if (code.isEmpty()) Text("Connect to your home Wi-Fi first.", style = Type.normal.copy(color = Palette.danger))
                        else Box(Modifier.padding(vertical = 10.dp)) { Qr(code, 220.dp) }
                        if (code.isNotEmpty()) MButton("Copy", Modifier.fillMaxWidth(), icon = "copy") {
                            io.github.ravenor907.muninn.ui.Clip.copy(ctx, "Move code", code)
                            Ui.say("Code copied.")
                        }
                    }
                }
            }
        },
        confirmButton = {
            val i = inc
            if ((i?.state == "hello" || i?.state == "receiving") && !i.confirmed) MButton("They match", kind = Kind.Accent) { Move.confirmHere(app) }
        },
        dismissButton = { if (inc?.state != "done") MButton("Cancel") { Move.cancelHere(app); onDone() } })
}

/**
 * On the old device, over everything: the emoji check before the move, the lock while it moves, and "Moved to [name]"
 * after. True when it covers the screen (nothing else may show).
 */
@Composable
fun MoveCover(app: Muninn): Boolean {
    val out by Move.outgoing.collectAsState()
    val ask by Move.linkAsk.collectAsState()
    // Never over the lock screen: the question waits until Muninn is unlocked.
    val appLocked by io.github.ravenor907.muninn.ui.AppLock.locked.collectAsState()
    val vaultLocked by app.vault.locked.collectAsState()
    var unlocking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (!appLocked && !vaultLocked) ask?.let { name ->
        // Back is Don't link.
        AlertDialog(onDismissRequest = { Move.answerLink(app, false) }, properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), containerColor = Palette.panel,
            title = { Text("Link $name?", style = Type.big) },
            text = { Text("$name wants to be a linked device.", style = Type.normal, modifier = Modifier.verticalScroll(rememberScrollState())) },
            // A linked device sees every chat: the current lock first.
            confirmButton = { MButton("Link", kind = Kind.Accent) { LockGate.ask(app, "Link $name") { Move.answerLink(app, true) } } },
            dismissButton = { MButton("Don't link") { Move.answerLink(app, false) } })
    }
    val o = out ?: return false
    if (unlocking) {
        // Stopping a move needs the person's own password or pattern.
        LockScreen(app) { ok -> unlocking = false; if (ok) Move.abort(app) }
        return true
    }
    when (o.state) {
        "checking" -> {
            // Back is Cancel.
            AlertDialog(onDismissRequest = { Move.cancelOut(app) }, properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false), containerColor = Palette.panel,
                title = { Text("Moving to ${o.to}", style = Type.big) },
                text = { Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Same on both screens? Tap They match.", style = Type.normal)
                    Text(o.emoji, style = Type.big, modifier = Modifier.padding(vertical = 12.dp))
                    Text(MOVE_WARNING, style = Type.normal.copy(color = Palette.danger))
                    Text("This device is then locked out.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
                } },
                confirmButton = { MButton("They match", kind = Kind.Accent) { scope.launch { withContext(Dispatchers.IO) { Move.go(app) }?.let { Ui.say(it, true) } } } },
                dismissButton = { MButton("Cancel") { Move.cancelOut(app) } })
            return false
        }
        "moving" -> Cover("Moving to ${o.to}", if (o.of > 0) "${o.sent} of ${o.of} parts there. ${o.note}" else o.note.ifEmpty { "Getting ready…" },
            "Keep both on the same Wi-Fi.") {
            MButton("Stop moving", Modifier.fillMaxWidth().padding(top = 16.dp)) { unlocking = true }
        }
        "moved" -> Cover("Moved to ${o.to}", "Everything is on ${o.to} now.", "") {
            if (o.canLink) MButton("Use as a linked device", Modifier.fillMaxWidth().padding(top = 16.dp), Kind.Accent) {
                scope.launch {
                    val err = withContext(Dispatchers.IO) { Move.becomeLinked(app) }
                    Ui.say(err ?: "Say yes on ${o.to}.", err != null)
                }
            }
            MButton("Start fresh", Modifier.fillMaxWidth().padding(top = 8.dp)) { Move.startFresh(app) }
        }
        else -> return false
    }
    return true
}

@Composable
private fun Cover(title: String, line: String, note: String, buttons: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.ground).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(title, style = Type.big, textAlign = TextAlign.Center)
            Text(line, style = Type.normal, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
            if (note.isNotEmpty()) Text(note, style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            buttons()
        }
    }
}
