package io.github.ravenor907.muninn.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Limits
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

// Message limits (cap d1, shared/docs/protocol.md section 16): what the screens show and change.

/** Set once the chat list has said that messages delete after 7 days ([keepNotice]). */
const val KEEP_NOTICE = "keepNotice"

/**
 * Said once to everyone, the first time the chat list opens: messages, photos and calls delete after 7 days.
 * "Save photos" says how to keep one.
 */
fun keepNotice(): io.github.ravenor907.muninn.ui.Ask = io.github.ravenor907.muninn.ui.Ask(
    "Messages delete after 7 days", "Messages, photos and calls delete after 7 days.", "Got it", cancel = "",
    extra = "Save photos", onExtra = {
        Ui.sure(io.github.ravenor907.muninn.ui.Ask("Save photos", "Open a photo, then tap Save to phone. It stays in Pictures › Muninn.",
            "Got it", cancel = "") {})
    }) {}

/** Group settings › Keep messages: the group's type and days. Only admins change it; the same for everyone in the group. */
@Composable
fun GroupLimitPanel(app: Muninn, g: String, s: Proto06.State, admin: Boolean) {
    val days = Limits.groupDays(s)
    Text("${Limits.kindName(s.kind)} group · $days days", style = Type.normal, modifier = Modifier.padding(vertical = 8.dp))
    if (admin) {
        val kinds = listOf("") + Limits.KINDS
        io.github.ravenor907.muninn.ui.ChoiceRow(kinds.size, Modifier.padding(bottom = 8.dp), minWidth = 88.dp) { i, m ->
            val k = kinds[i]
            MButton(Limits.kindName(k), m, if (s.kind == k) Kind.Accent else Kind.Normal) {
                if (s.kind != k) app.chat.groups.setLimit(g, k, if (k.isEmpty()) Limits.DEFAULT else days)?.let { Ui.say(it, true) }
            }
        }
        if (s.kind.isNotEmpty()) io.github.ravenor907.muninn.ui.ChoiceRow(Limits.CHOICES.size, Modifier.padding(bottom = 8.dp)) { i, m ->
            val d = Limits.CHOICES[i]
            MButton("$d days", m, if (days == d) Kind.Accent else Kind.Normal) {
                if (days != d) app.chat.groups.setLimit(g, s.kind, d)?.let { Ui.say(it, true) }
            }
        } else Text("Family or Work: up to 30 days.", style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
    }
    val me = app.identity.publicKey
    val older = s.members.filter { it.k != me && app.chat.contacts.get(it.k)?.caps?.let { c -> c.isNotEmpty() && Limits.CAP !in c } == true }
    if (older.isNotEmpty()) Text("Needs an update: " + older.joinToString(", ") { memberName(app, it) },
        style = Type.small.copy(color = Palette.textSoft), modifier = Modifier.padding(bottom = 8.dp))
}

/** New group: Standard, Family or Work (Family and Work can keep messages longer, set in its settings). */
@Composable
fun GroupKindPicker(kind: String, onKind: (String) -> Unit) {
    val kinds = listOf("") + Limits.KINDS
    io.github.ravenor907.muninn.ui.ChoiceRow(kinds.size, Modifier.padding(bottom = 8.dp), minWidth = 88.dp) { i, m ->
        val k = kinds[i]
        MButton(Limits.kindName(k), m, if (kind == k) Kind.Accent else Kind.Normal) { onKind(k) }
    }
}
