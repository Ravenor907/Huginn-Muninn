package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.AppFiles
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Send an app": the apps on this phone, by name, to send to a TV. [onFile], when given, also offers an
 * .apk file from the phone (the Files tab picks files with its own button).
 */
@Composable
fun AppPicker(onCancel: () -> Unit, onFile: (() -> Unit)? = null, onPick: (AppFiles.Installed) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppFiles.Installed>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { AppFiles.installed(context) } }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text("Send an app", style = Type.big) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("A Kids' Device installs it only after you press Install on your phone. Apps made for phones don't always work on a TV.",
                    style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
                if (onFile != null) MButton("Pick an .apk file", Modifier.fillMaxWidth().padding(bottom = 8.dp)) { onFile() }
                val list = apps
                when {
                    list == null -> Text("Looking at your apps…", style = Type.small)
                    list.isEmpty() -> Text("No apps here that can be sent.", style = Type.small)
                    else -> list.forEach { a ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Send ${a.label}") { onPick(a) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(a.label, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                            Text(LocalSend.size(a.size), style = Type.small)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { MButton("Cancel", onClick = onCancel) },
    )
}
