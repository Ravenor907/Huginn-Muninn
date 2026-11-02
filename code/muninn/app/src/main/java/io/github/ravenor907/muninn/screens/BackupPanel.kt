package io.github.ravenor907.muninn.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Backup
import io.github.ravenor907.muninn.net.BackupFormat
import io.github.ravenor907.muninn.net.FileText
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PW = BackupFormat.MIN_PASSWORD

/** The system's Save picker, opening in Download (the person can pick any other place). */
private class SaveInDownload : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent = super.createIntent(context, input).putExtra(
        DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"))
}

/** What every backup holds, said before saving and before restoring. */
private const val INSIDE = "Your identity, contacts, chats, groups, folders and settings."

/** Settings › Backup: Back up (one encrypted file you save where you like) and Restore from a backup. */
@Composable
fun BackupPanel(app: Muninn) {
    var mode by remember { mutableStateOf("") }          // "", "backup" or "restore"
    Panel {
        when (mode) {
            "backup" -> BackupFlow(app) { mode = "" }
            "restore" -> RestoreFlow(app, onCancel = { mode = "" }) { o ->
                val kept = Backup.restoreKeeping(app, o)
                "Restored. What was here is kept in $kept. Muninn is restarting."
            }
            else -> {
                Text("One file with everything, locked with a password you choose.", style = Type.normal)
                Text("Save it anywhere. It restores on a new phone or computer instead of this one. To use a computer as well, link it.",
                    style = Type.small, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("Back up", Modifier.weight(1f), Kind.Accent) { mode = "backup" }
                    MButton("Restore from a backup", Modifier.weight(1f)) { mode = "restore" }
                }
            }
        }
    }
}

/** How strong a backup password looks, in a few words. */
fun strengthText(pw: String): Pair<String, Boolean> = when (BackupFormat.strength(pw)) {
    0 -> "Too short: $MIN_PW or more characters." to true
    1 -> "Weak: add more words or different characters." to true
    2 -> "Fair. Longer is stronger." to false
    3 -> "Good." to false
    else -> "Strong." to false
}

/** Back up: what's inside (files and pictures on or off), the password twice, the Save picker, then where it went. */
@Composable
fun BackupFlow(app: Muninn, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("what") }        // what, password, saving, saved
    var withFiles by remember { mutableStateOf(true) }
    var sizes by remember { mutableStateOf<Backup.Sizes?>(null) }
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<Backup.Saved?>(null) }

    LaunchedEffect(Unit) { sizes = withContext(Dispatchers.IO) { try { Backup.sizes(app.vault) } catch (e: Exception) { null } } }

    val saveTo = rememberLauncherForActivityResult(SaveInDownload()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pw = pw1
        step = "saving"; progress = 0f; error = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Nothing past a chat's message limit goes into the backup (shared/docs/protocol.md section 16).
                try { app.chat.prune(); Result.success(Backup.create(app, uri, pw, withFiles) { progress = it }) }
                catch (e: Throwable) {
                    try { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) } catch (_: Exception) {}
                    Result.failure<Backup.Saved>(e)
                }
            }
            pw1 = ""; pw2 = ""
            result.onSuccess { saved = it; step = "saved" }
                .onFailure { error = "The backup couldn't be saved. ${if (it is OutOfMemoryError) "Too big for this phone: leave files and pictures out." else it.message ?: ""}".trim(); step = "password" }
        }
    }

    when (step) {
        "what" -> {
            Text("What's inside", style = Type.big)
            Text(INSIDE, style = Type.normal, modifier = Modifier.padding(top = 6.dp))
            val s = sizes
            ToggleRow("Files and pictures", if (s == null) "Counting…" else "Photos, voice and video messages: ${FileText.size(s.media)}", withFiles) { withFiles = it }
            Text("Encrypted with a password you choose. Only that password opens it.", style = Type.small)
            MButton("Next", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { step = "password" }
            MButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp)) { onClose() }
        }
        "password", "saving" -> {
            val busy = step == "saving"
            Text("Backup password", style = Type.big)
            Field(pw1, { pw1 = it; error = "" }, "Password ($MIN_PW or more characters)", Modifier.padding(top = 10.dp), secret = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (pw1.isNotEmpty()) strengthText(pw1).let { (t, bad) ->
                Text(t, style = Type.small.copy(color = if (bad) Palette.danger else Palette.text), modifier = Modifier.padding(top = 4.dp))
            }
            Field(pw2, { pw2 = it; error = "" }, "Type it again", Modifier.padding(top = 8.dp), secret = true,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (pw2.isNotEmpty() && pw1 != pw2) Text("They don't match yet.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
            Text("No one can reset it. Write it down. Lose it, lose the backup.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            Text("Next you pick where to save. Download is a good place.", style = Type.small, modifier = Modifier.padding(top = 2.dp))
            MButton(if (busy) "Saving…" else "Choose where to save", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent,
                enabled = !busy && BackupFormat.strength(pw1) > 0 && pw1 == pw2) { saveTo.launch(Backup.fileName()) }
            MButton("Back", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { step = "what" }
            if (busy) Bar(progress, Modifier.padding(top = 8.dp))
            if (error.isNotEmpty()) Text(error, style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
        }
        else -> {
            val s = saved
            Text("Backup saved", style = Type.big)
            if (s != null) {
                Text("Saved to ${s.where}", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                Text("Encrypted with your password", style = Type.normal, modifier = Modifier.padding(top = 2.dp))
                Text("Size: ${FileText.size(s.bytes)}", style = Type.normal, modifier = Modifier.padding(top = 2.dp))
                Text(if (s.files) "Files and pictures are in it." else "Files and pictures are not in it.", style = Type.small, modifier = Modifier.padding(top = 2.dp))
            }
            MButton("Done", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { onClose() }
        }
    }
}

/**
 * Restore: pick any file (the phone's file picker), its password (wrong ones say so and can be tried again), then what's
 * in it and Restore (replaces what's here), behind a confirm. [restore] does the work off the main thread and says what
 * happened; Muninn then restarts. In [practice] nothing is opened or changed.
 */
@Composable
fun RestoreFlow(app: Muninn, practice: Boolean = false, onCancel: () -> Unit, restore: (BackupFormat.Opened) -> String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var where by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<BackupFormat.Opened?>(null) }
    var done by remember { mutableStateOf("") }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        file = uri; opened = null; pw = ""; wrong = false; error = ""
        scope.launch { where = withContext(Dispatchers.IO) { try { Backup.where(ctx, uri) } catch (e: Exception) { "" } } }
    }

    fun openIt() {
        val uri = file ?: return
        val p = pw
        busy = true; wrong = false; error = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { try { Result.success(Backup.open(ctx, uri, p)) } catch (e: Throwable) { Result.failure<BackupFormat.Opened>(e) } }
            busy = false
            r.onSuccess { opened = it; pw = "" }.onFailure {
                when (it) {
                    is BackupFormat.WrongPassword -> wrong = true
                    is BackupFormat.NotABackup -> error = it.message ?: "This isn't a Huginn or Muninn backup."
                    is OutOfMemoryError -> error = "Too big to open on this phone."
                    else -> error = "Couldn't read that file."
                }
            }
        }
    }

    fun replace(o: BackupFormat.Opened, copyOk: Boolean = false) {
        // A backup from another device (a computer, or another phone) makes this phone the same person as that device:
        // said first, with its own confirm (the same rule as Huginn's).
        val s = BackupFormat.summary(o)
        if (!copyOk && (s.app == "huginn" || (s.device.isNotEmpty() && s.device != app.identity.deviceName))) {
            val where = s.device.ifEmpty { if (s.app == "huginn") "that computer" else "that phone" }
            Ui.sure(Ask("This makes a copy of you",
                "This phone becomes the same person as $where. If both are used, messages stop working on one or both, " +
                    "and friends see errors." + (if (s.app == "huginn") " To use both, link instead: Settings › Linked devices › Scan to link." else "") +
                    " Restore only if $where won't chat again.",
                "Restore anyway", danger = true) { replace(o, copyOk = true) })
            return
        }
        Ui.sure(Ask("Replace what's here?", "Everything on this phone is replaced by the backup. A copy of what's here now is kept first.",
            "Restore", danger = true, hold = true) {
            busy = true; error = ""
            scope.launch {
                val r = withContext(Dispatchers.IO) { try { Result.success(restore(o)) } catch (e: Exception) { Result.failure<String>(e) } }
                r.onSuccess { done = it; delay(1500); Backup.restart(ctx) }
                    .onFailure { busy = false; error = "Restore failed. Nothing changed. ${it.message ?: ""}".trim() }
            }
        })
    }

    Column {
        val o = opened
        when {
            done.isNotEmpty() -> Text(done, style = Type.normal)
            file == null -> {
                Text("Restore from a backup", style = Type.big)
                Text("Choose the backup file. Any name works, from Huginn or Muninn.", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                Text("Look in Download, or wherever you saved it.", style = Type.small, modifier = Modifier.padding(top = 2.dp))
                MButton("Choose file", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) {
                    if (practice) Ui.say("Practice: this opens the file picker.") else pick.launch(arrayOf("*/*"))
                }
            }
            o == null -> {
                Text("Backup password", style = Type.big)
                Text("File: ${where.ifEmpty { "the one you chose" }}", style = Type.small, modifier = Modifier.padding(top = 4.dp))
                Field(pw, { pw = it; wrong = false; error = "" }, "Password", Modifier.padding(top = 10.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (wrong) Text("Wrong password. Try again.", style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                MButton(if (busy) "Checking…" else "Open", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent,
                    enabled = !busy && pw.isNotEmpty()) { openIt() }
                MButton("Choose another file", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { pick.launch(arrayOf("*/*")) }
            }
            else -> {
                val s = BackupFormat.summary(o)
                val from = if (s.app == "huginn") "Huginn" else "Muninn"
                Text("What's in it", style = Type.big)
                Text("Made ${Backup.madeOn(s.created)}${if (s.device.isNotEmpty()) " on ${s.device}" else ""} in $from", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                if (o.header.v < 3) Text("An older backup. Encrypted with its password.", style = Type.small, modifier = Modifier.padding(top = 2.dp))
                if (s.contacts != null) {
                    Text("Your identity", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                    Text(listOf(count(s.contacts, "contact"), count(s.chats ?: 0, "chat"), count(s.groups ?: 0, "group"), count(s.folders ?: 0, "folder"))
                        .joinToString(", ") + ", settings", style = Type.normal)
                } else Text("$INSIDE ${count(s.items, "item")} in all.", style = Type.normal, modifier = Modifier.padding(top = 6.dp))
                Text(when {
                    s.app == "huginn" -> "Made in Huginn: messages come across, pictures and files stay behind."
                    s.files == false -> "Files and pictures: not in it."
                    else -> "Files and pictures: in it."
                }, style = Type.small, modifier = Modifier.padding(top = 4.dp))
                Text("A copy of what's here now is kept on this phone first.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
                MButton(if (busy) "Restoring…" else "Restore (replaces what's here)", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Danger,
                    enabled = !busy) { replace(o) }
            }
        }
        if (done.isEmpty()) MButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { onCancel() }
        if (error.isNotEmpty()) Text(error, style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
    }
}

private fun count(n: Int, word: String) = "$n ${if (n == 1) word else word + "s"}"
