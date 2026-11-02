package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Backup
import io.github.ravenor907.muninn.net.VaultCore
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The vault can't open for a reason other than the password (see [VaultCore.Trouble]). Nothing is changed or made
 * new here on its own: Try again reads the files again; Restore from a backup sets everything here aside (kept) first.
 */
@Composable
fun VaultTrouble(app: Muninn, trouble: VaultCore.Trouble, done: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var restoring by remember { mutableStateOf(false) }

    fun tryAgain() {
        busy = true; status = ""
        scope.launch {
            val ok = withContext(Dispatchers.IO) { try { app.vault.openDefault() } catch (_: Exception) { false } }
            busy = false
            // Opened: on with it. A password needed: the lock screen comes back by itself.
            if (ok) done(true) else if (app.vault.trouble.value != null) status = "Still can't open it."
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.ground).statusBarsPadding().navigationBarsPadding()) {
        Brand()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Panel {
                Text("Muninn can't open your data", style = Type.big)
                Text(when (trouble) {
                    VaultCore.Trouble.KEY -> "Its lock file is missing or damaged."
                    VaultCore.Trouble.DATA -> "Your keys file can't be read."
                } + " Nothing was changed or deleted.", style = Type.normal, modifier = Modifier.padding(top = 4.dp))
                MButton(if (busy && !restoring) "Opening…" else "Try again", Modifier.fillMaxWidth().padding(top = 10.dp), Kind.Accent, enabled = !busy) { tryAgain() }
                if (!restoring) MButton("Restore from a backup", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy) { restoring = true }
            }
            // The usual Restore from a backup: what's here is set aside (kept) first, then the backup goes in.
            if (restoring) Panel {
                RestoreFlow(app, onCancel = { restoring = false }) { o ->
                    val files = Backup.prepare(app, o)
                    val kept = app.vault.startOverKeeping()
                    Backup.restore(ctx, app.vault, files)
                    "Restored. What was here is kept in ${kept.name}. Muninn is restarting."
                }
            }
            if (status.isNotEmpty()) Text(status, style = Type.small.copy(color = Palette.danger))
        }
    }
}
