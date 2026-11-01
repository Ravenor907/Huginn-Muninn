package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.minimumInteractiveComponentSize
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.Avatar
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Section
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/** The status choices on Your profile: what's sent, what it's called, and one short line about it. */
internal enum class Status(val key: String, val title: String, val hint: String) {
    ONLINE("online", "Online", ""),
    AWAY("away", "Away", "after 10 min idle"),
    DND("dnd", "Do not disturb", "no sounds"),
    INVISIBLE("invisible", "Invisible", "look offline");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key } ?: ONLINE
    }
}

/**
 * A new chosen name: saved, said to the Wi-Fi and Bluetooth, and sent with my profile to everyone who already has me
 * (cap p1), so their copy of my name follows.
 */
internal fun saveName(app: Muninn, name: String) {
    val id = app.identity
    if (name.isBlank() || name.trim() == id.username) return
    id.username = name
    if (!app.ready.value) return
    app.net.announce(); app.ble.hiddenChanged()
    try { app.profile.nameChanged() } catch (_: Exception) {}
}

/** The color of a status's square and dot. */
private fun statusColour(s: Status): Color = when (s) {
    Status.ONLINE -> Palette.online
    Status.AWAY -> if (Access.colorBlind) Palette.gold else Palette.away
    Status.DND -> Palette.danger
    Status.INVISIBLE -> Palette.textFaint
}

/**
 * Settings › Your profile: your picture, name and status, as your contacts see them. Nothing here goes to anyone
 * but your contacts, end-to-end encrypted like a message.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(app: Muninn) {
    val id = app.identity
    val profile = app.profile
    val status by profile.myStatus.collectAsState()
    val custom by profile.myCustomStatus.collectAsState()
    val colour by profile.myColour.collectAsState()
    val pictures by profile.pictures.collectAsState()
    var name by remember { mutableStateOf(id.username) }
    var statusText by remember(custom) { mutableStateOf(custom) }
    var picking by remember { mutableStateOf(false) }
    val current = Status.of(status)

    // The big picture, name and status, centred like a card.
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Change picture") { picking = true }
            .semantics { contentDescription = "Your picture. Tap to change it" }) {
            Avatar("me", id.username, 112.dp, showDot = true)
        }
        Text(id.username, style = Type.big.copy(fontSize = 22.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        Text(if (custom.isNotBlank()) "${current.title} · $custom" else current.title, style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
        Text("Only your contacts see this", style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }

    Section("Picture")
    MButton("Change picture", Modifier.fillMaxWidth(), Kind.Accent, icon = "image") { picking = true }
    if (pictures["me"] != null) MButton("Remove picture", Modifier.fillMaxWidth().padding(top = 6.dp), Kind.Danger) {
        profile.clearMyPicture(); Ui.say("Picture removed.")
    }
    if (picking) PicturePickerSheet(app) { picking = false }

    Text("Color of your initial", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 14.dp))
    // Wraps onto a second line when six don't fit (a small phone at a large size); each is a full 48dp touch target.
    FlowRow(Modifier.padding(top = 8.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Palette.MY_COLORS.forEach { hex ->
            val on = colour.equals(hex, true)
            Box(Modifier.minimumInteractiveComponentSize().size(40.dp).clip(CircleShape).background(Palette.parse(hex) ?: Palette.goldTint)
                .border(if (on) 3.dp else 0.dp, if (on) Palette.text else Color.Transparent, CircleShape)
                .selectable(selected = on, role = Role.RadioButton) { profile.setMyColour(hex) }
                .semantics { contentDescription = Palette.myColorName(hex) + if (on) ", chosen" else "" },
                contentAlignment = Alignment.Center) {
                if (on) MIcon("check", Palette.text, 20.dp)
            }
        }
    }
    Text("Shows when there's no picture.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

    Section("Name")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(name, { name = it.take(24) }, "A name", Modifier.weight(1f))
        MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = name.isNotBlank() && name.trim() != id.username) {
            saveName(app, name); Ui.say("You're “${id.username}” now.")
        }
    }
    Text("Any name you like.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

    Section("Status")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Field(statusText, { statusText = it.take(80) }, "Set a custom status…", Modifier.weight(1f))
        MButton("Save", Modifier.padding(start = 8.dp), Kind.Accent, enabled = statusText.trim() != custom) {
            profile.setCustomStatus(statusText.trim()); Ui.say(if (statusText.isBlank()) "Status cleared." else "Status saved.")
        }
    }
    Column(Modifier.padding(top = 8.dp).selectableGroup()) {
        Status.entries.forEach { s ->
            val on = s == current
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Corner).background(if (on) Palette.accentFill else Color.Transparent)
                .selectable(selected = on, role = Role.RadioButton) { profile.setStatus(s.key) }
                .semantics { contentDescription = s.title + (if (s.hint.isNotEmpty()) ", ${s.hint}" else "") + if (on) ", chosen" else "" }
                .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(Corner).background(statusColour(s)))
                Text(s.title, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f).padding(start = 12.dp))
                // The hint shares the row with the name instead of squeezing it out at a large text size.
                if (s.hint.isNotEmpty()) Text(s.hint, style = Type.small, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false).padding(start = 8.dp))
            }
        }
    }
    Text(when (current) {
        Status.ONLINE -> "Green dot. Away after 10 minutes."
        Status.AWAY -> "Contacts see an amber dot."
        Status.DND -> "Red dot. No sounds or ringing."
        Status.INVISIBLE -> "Look offline. You still get everything."
    }, style = Type.small, modifier = Modifier.padding(top = 4.dp))

    Section("More")
    ProfileRow("My code", "For someone to scan") { SettingsNav.goChat = "code" }
    ProfileRow("Linked devices", "Tablet or second phone") { SettingsNav.folder = "link" }
    Spacer(Modifier.height(8.dp))
}

/** The picture picker, over the page while open. */
@Composable
private fun PicturePickerSheet(app: Muninn, onDone: () -> Unit) {
    io.github.ravenor907.muninn.ui.PicturePicker(onDone)
}

/** A row that opens something: its name, a line about it, and an arrow. */
@Composable
private fun ProfileRow(title: String, detail: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.normal.copy(color = Palette.text))
            Text(detail, style = Type.small)
        }
        MIcon("forward", Palette.textMuted, 20.dp)
    }
}
