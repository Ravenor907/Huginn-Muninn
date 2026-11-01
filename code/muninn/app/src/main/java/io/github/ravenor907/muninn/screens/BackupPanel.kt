package io.github.ravenor907.muninn.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PW = BackupFormat.MIN_PASSWORD

/** Settings › Backup: one encrypted file with every chat, contact and key, and putting it back. */
@Composable
fun BackupPanel(app: Muninn) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("") }          // "", "backup" or "restore"
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<Uri?>(null) }
    var opened by remember { mutableStateOf<BackupFormat.Opened?>(null) }

    fun reset() { mode = ""; pw1 = ""; pw2 = ""; busy = false; progress = 0f; file = null; opened = null }

    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pw = pw1
        busy = true; progress = 0f; bad = false; status = "Locking the backup with your password…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                // Nothing past a chat's message limit goes into the backup (shared/docs/protocol.md section 16).
                try { app.chat.prune(); Result.success(Backup.create(ctx, app.vault, uri, pw) { progress = it }) }
                catch (e: Throwable) {
                    try { android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri) } catch (_: Exception) {}
                    Result.failure<Int>(e)
                }
            }
            reset()
            result.onSuccess { status = "Backup saved. Keep its password safe."; bad = false }
                .onFailure { status = "The backup couldn't be saved. ${it.message ?: ""}".trim(); bad = true }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { if (file == null) reset(); return@rememberLauncherForActivityResult }
        file = uri; opened = null; pw1 = ""; status = ""; bad = false
    }

    fun openIt() {
        val uri = file ?: return
        val pw = pw1
        busy = true; bad = false; status = "Checking the password…"
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Result.success(Backup.open(ctx, uri, pw)) } catch (e: Throwable) { Result.failure<BackupFormat.Opened>(e) }
            }
            busy = false
            r.onSuccess { opened = it; pw1 = ""; status = "" }.onFailure {
                bad = true
                status = when (it) {
                    is BackupFormat.WrongPassword -> "That's not the password for this backup."
                    is BackupFormat.NotABackup -> it.message ?: "This isn't a Muninn backup."
                    is OutOfMemoryError -> "Too big to open on this phone."
                    else -> "Couldn't read that file."
                }
            }
        }
    }

    fun replace(o: BackupFormat.Opened) {
        Ui.sure(Ask("Replace everything?",
            "Replaces everything here. This can't be undone.",
            "Replace everything", danger = true, hold = true) {
            busy = true; status = "Restoring…"
            scope.launch {
                val ok = withContext(Dispatchers.IO) { try { Backup.restore(ctx, app.vault, o); true } catch (e: Exception) { false } }
                if (!ok) { busy = false; bad = true; status = "Restore failed. Nothing changed."; return@launch }
                status = "Restored. Muninn is restarting…"
                delay(800)
                Backup.restart(ctx)
            }
        })
    }

    Panel {
        Text("Everything, in one encrypted file.", style = Type.normal)
        Text("Use on one phone at a time.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        when (mode) {
            "backup" -> {
                Field(pw1, { pw1 = it }, "Backup password ($MIN_PW or more characters)", Modifier.padding(top = 10.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                Field(pw2, { pw2 = it }, "Type it again", Modifier.padding(top = 8.dp), secret = true,
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                when {
                    pw1.isNotEmpty() && pw1.length < MIN_PW -> Text("At least $MIN_PW characters.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                    pw2.isNotEmpty() && pw1 != pw2 -> Text("They don't match yet.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                    else -> Text("Lose it, lose the backup. Write it down.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
                }
                MButton(if (busy) "Saving…" else "Choose where to save", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent,
                    enabled = !busy && pw1.length >= MIN_PW && pw1 == pw2) { saveTo.launch(Backup.fileName()) }
                MButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { reset(); status = "" }
            }
            "restore" -> {
                val o = opened
                when {
                    file == null -> MButton("Pick the backup file", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent) { pick.launch(arrayOf("*/*")) }
                    o == null -> {
                        Field(pw1, { pw1 = it; bad = false; status = "" }, "Backup password", Modifier.padding(top = 10.dp), secret = true,
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                        MButton(if (busy) "Checking…" else "Open backup", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent,
                            enabled = !busy && pw1.isNotEmpty()) { openIt() }
                        MButton("Pick a different file", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { pick.launch(arrayOf("*/*")) }
                    }
                    else -> {
                        val count = o.files.count { it.first != Backup.PREFS_ENTRY }
                        Text("Backup from ${Backup.madeOn(o)}: $count ${if (count == 1) "item" else "items"}.", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
                        Text("Replaces everything on this phone.", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 4.dp))
                        MButton("Replace everything with this backup", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Danger, enabled = !busy) { replace(o) }
                    }
                }
                MButton("Cancel", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { reset(); status = "" }
            }
            else -> Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Back up chats", Modifier.weight(1f), Kind.Accent) { status = ""; mode = "backup" }
                MButton("Restore from backup", Modifier.weight(1f)) { status = ""; mode = "restore"; pick.launch(arrayOf("*/*")) }
            }
        }
        if (busy && mode == "backup" && progress > 0f) Bar(progress, Modifier.padding(top = 8.dp))
        if (status.isNotEmpty()) Text(status, style = Type.small.copy(color = if (bad) Palette.danger else Palette.text), modifier = Modifier.padding(top = 6.dp))
    }
}
