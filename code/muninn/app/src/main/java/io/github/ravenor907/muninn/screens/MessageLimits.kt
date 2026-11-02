package io.github.ravenor907.muninn.screens

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
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

/**
 * Group settings › Type: Folk or Family, and for Family how many days messages stay (7 to 30). Only admins change it;
 * the same for everyone in the group (16.2).
 */
@Composable
fun GroupLimitPanel(app: Muninn, g: String, s: Proto06.State, admin: Boolean) {
    val days = Limits.groupDays(s)
    val type = Limits.groupType(s)
    Text("${Limits.typeName(type)} group · $days days", style = Type.normal, modifier = Modifier.padding(top = 8.dp))
    Text(Limits.typeLine(type), style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
    if (admin) {
        io.github.ravenor907.muninn.ui.ChoiceRow(Limits.TYPES.size, Modifier.padding(bottom = 8.dp), minWidth = 88.dp) { i, m ->
            val t = Limits.TYPES[i]
            MButton(Limits.typeName(t), m, if (type == t) Kind.Accent else Kind.Normal) {
                if (type != t) Ui.sure(io.github.ravenor907.muninn.ui.Ask(
                    if (t == Limits.FAMILY) "Make it Family?" else "Make it Folk?",
                    if (t == Limits.FAMILY) "Invite links stop. Only admins add people." else "Messages disappear after 7 days. Members can invite.",
                    if (t == Limits.FAMILY) "Make it Family" else "Make it Folk") {
                    app.chat.groups.setLimit(g, t, days)?.let { Ui.say(it, true) } ?: Ui.say("This is a ${Limits.typeName(t)} group now.")
                })
            }
        }
        if (type == Limits.FAMILY) io.github.ravenor907.muninn.ui.ChoiceRow(Limits.CHOICES.size, Modifier.padding(bottom = 8.dp)) { i, m ->
            val d = Limits.CHOICES[i]
            MButton("$d days", m, if (days == d) Kind.Accent else Kind.Normal) {
                if (days != d) app.chat.groups.setLimit(g, Limits.FAMILY, d)?.let { Ui.say(it, true) }
            }
        }
    }
    TypeRulesLink()
    val me = app.identity.publicKey
    val older = s.members.filter { it.k != me && app.chat.contacts.get(it.k)?.caps?.let { c -> c.isNotEmpty() && Limits.CAP !in c } == true }
    if (older.isNotEmpty()) Text("Needs an update: " + older.joinToString(", ") { memberName(app, it) },
        style = Type.small.copy(color = Palette.textSoft), modifier = Modifier.padding(bottom = 8.dp))
}

/** "What's the difference?": the rules of Folk and Family. */
@Composable
fun TypeRulesLink() {
    Text("What's the difference?", style = Type.small.copy(color = Palette.accent,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
        modifier = Modifier.padding(vertical = 8.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) {
            Ui.sure(io.github.ravenor907.muninn.ui.Ask("Folk and Family",
                Limits.TYPES.joinToString("\n\n") { t -> Limits.typeName(t) + "\n" + Limits.typeRules(t).joinToString("\n") { "· $it" } },
                "Got it", cancel = "") {})
        })
}

/** New group: two cards, Folk and Family, each with its one line; "What's the difference?" opens the rules. */
@Composable
fun GroupKindPicker(kind: String, onKind: (String) -> Unit) {
    val picked = if (kind == Limits.FAMILY) Limits.FAMILY else Limits.FOLK
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Limits.TYPES.forEach { t ->
            val on = picked == t
            Column(Modifier.weight(1f).fillMaxHeight().clip(io.github.ravenor907.muninn.ui.Corner)
                .border(if (on) 2.dp else 1.dp, if (on) Palette.accent else Palette.line, io.github.ravenor907.muninn.ui.Corner)
                .selectable(on, role = androidx.compose.ui.semantics.Role.RadioButton) { onKind(t) }
                .padding(12.dp)) {
                Text(Limits.typeName(t), style = Type.normal.copy(color = if (on) Palette.accent else Palette.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                Text(Limits.typeLine(t), style = Type.small, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    // Family picked: say up front how people join (in person, by QR or a code), and offer Folk for anyone who wants a link.
    if (picked == Limits.FAMILY) Panel(Modifier.padding(top = 8.dp)) {
        Text("How people join a Family group", style = Type.normal.copy(color = Palette.text))
        Text("You add each person yourself: they scan your QR code when you're together, or you send them a code. " +
            "There are no invite links, so nobody can pass one on. Relatives far away? Send them a code.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        MButton("Want a link? Use Folk instead", Modifier.fillMaxWidth().padding(top = 8.dp)) { onKind(Limits.FOLK) }
    }
    TypeRulesLink()
}
