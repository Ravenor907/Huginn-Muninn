package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.ModLogic
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Framed
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.MenuRow
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.delay

/**
 * Mute for me, Volume, and for admins Timeout and Ban (cap g2, shared/docs/protocol.md section 26), the same in every
 * place a person shows: a group's People (their menu), a voice room (press and hold someone) and a group message
 * (press and hold it). The same as Huginn's.
 */
object ModNav {
    /** The page a person's menu opens on ("volume" or "timeout"), set just before it opens. */
    var page by mutableStateOf("")
}

/** "9 min left", "3 h left", "2 days left". */
internal fun leftText(until: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((until - now).coerceAtLeast(0) + 59_999) / 60_000
    if (min < 60) return "${min.coerceAtLeast(1)} min left"
    val hours = (min + 59) / 60
    return if (hours < 48) "$hours h left" else "${(hours + 23) / 24} days left"
}

/**
 * The rows in a person's menu (Menus.kt): [danger] false gives Mute for me, Fold their messages, Volume and Timeout;
 * true gives Ban, with the red rows. [go] opens "volume" or "timeout" in the same sheet.
 */
@Composable
internal fun ColumnScope.ModRows(app: Muninn, g: String, key: String, name: String, danger: Boolean, go: (String) -> Unit, done: () -> Unit) {
    val sound by app.chat.sound.all.collectAsState()
    @Suppress("UNUSED_VARIABLE") val serial by app.chat.groups.modSerial.collectAsState()
    val p = sound[key] ?: io.github.ravenor907.muninn.net.PeopleSound.Person()
    val admin = g.isNotEmpty() && app.chat.groups.canModerate(g, key)
    if (danger) {
        if (admin) MenuRow("close", "Ban", danger = true) {
            done()
            io.github.ravenor907.muninn.ui.Ui.sure(io.github.ravenor907.muninn.ui.Ask("Ban $name?",
                "They leave the group and can't rejoin by a link, code or invite until you unban them.", "Ban", danger = true,
                onConfirm = { tell(app.chat.groups.ban(g, key), "$name is banned.") }))
        }
        return
    }
    MenuRow(if (p.muted) "sound" else "mute", if (p.muted) "Unmute for me" else "Mute for me") {
        done(); app.chat.sound.setMuted(key, !p.muted)
        io.github.ravenor907.muninn.ui.Ui.say(if (p.muted) "$name isn't muted for you." else "$name is muted for you.")
    }
    if (p.muted) MenuRow("hide", "Fold their messages", value = if (!p.show) "On" else "Off") { app.chat.sound.setHides(key, p.show) }
    MenuRow("audio", "Volume", value = "${p.volume}%") { go("volume") }
    if (admin) {
        if (app.chat.groups.timedOutUntil(g, key) > 0) MenuRow("clock", "Cancel timeout") { done(); tell(app.chat.groups.cancelTimeout(g, key), "$name can post again.") }
        else MenuRow("clock", "Timeout") { go("timeout") }
    }
}

private fun tell(err: String?, done: String) {
    if (err != null) io.github.ravenor907.muninn.ui.Ui.say(err, true) else io.github.ravenor907.muninn.ui.Ui.say(done)
}

/** A slider from 0 to 200 %, saved as it moves; their sound changes at once. Only on this phone. */
@Composable
internal fun VolumeDialog(app: Muninn, key: String, name: String, done: () -> Unit) {
    var v by remember(key) { mutableIntStateOf(app.chat.sound.volume(key)) }
    AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Volume for $name", style = Type.big) },
        text = {
            Column {
                Text("Only on this phone. Their voice in calls and voice rooms.", style = Type.small)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                    Text("$v%", style = Type.small.copy(color = Palette.textSoft))
                }
                Slider(value = v.toFloat(), onValueChange = { f ->
                    val n = ModLogic.clampVolume(Math.round(f / 5f) * 5)
                    if (n != v) { v = n; app.chat.sound.setVolume(key, n) }
                }, valueRange = ModLogic.VOLUME_MIN.toFloat()..ModLogic.VOLUME_MAX.toFloat(), steps = 39,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Volume for $name, $v percent" },
                    colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.track))
                MButton("Back to 100%", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = v != ModLogic.VOLUME_DEFAULT) {
                    v = ModLogic.VOLUME_DEFAULT; app.chat.sound.setVolume(key, v)
                }
            }
        },
        confirmButton = { MButton("Done") { done() } },
    )
}

/** Timeout [key] in [g]: 10 minutes, 1 hour, 1 day or 1 week. They can read but not post, send files or join voice. */
@Composable
internal fun TimeoutDialog(app: Muninn, g: String, key: String, name: String, say: (String, Boolean) -> Unit = { t, b -> io.github.ravenor907.muninn.ui.Ui.say(t, b) },
                           done: () -> Unit) {
    AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Timeout $name", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("They can read but not post, send files or join voice.", style = Type.small, modifier = Modifier.padding(bottom = 8.dp))
                ModLogic.TIMEOUTS.forEachIndexed { i, ms ->
                    Line(ModLogic.TIMEOUT_NAMES[i], "", icon = "clock") {
                        val err = app.chat.groups.timeout(g, key, ms)
                        if (err != null) say(err, true) else say("$name is timed out.", false)
                        done()
                    }
                }
            }
        },
        confirmButton = { MButton("Cancel") { done() } },
    )
}

/**
 * Someone in a voice room, pressed and held: Mute for me, Volume, and for admins Timeout and Ban, in one dialog that
 * works outside the main screen too (the room has its own window). [say] shows what happened.
 */
@Composable
internal fun PersonModDialog(app: Muninn, g: String, key: String, name: String, say: (String, Boolean) -> Unit, done: () -> Unit) {
    val sound by app.chat.sound.all.collectAsState()
    @Suppress("UNUSED_VARIABLE") val serial by app.chat.groups.modSerial.collectAsState()
    var page by remember(key) { mutableStateOf("") }
    if (page == "volume") { VolumeDialog(app, key, name) { page = "" }; return }
    if (page == "timeout") { TimeoutDialog(app, g, key, name, say) { done() }; return }
    val p = sound[key] ?: io.github.ravenor907.muninn.net.PeopleSound.Person()
    val admin = g.isNotEmpty() && app.chat.groups.canModerate(g, key)
    AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text(name, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (page == "ban") {
                    Text("They leave the group and can't rejoin by a link, code or invite until you unban them.", style = Type.normal)
                    MButton("Ban $name", Modifier.fillMaxWidth().padding(top = 12.dp), Kind.Danger) {
                        val err = app.chat.groups.ban(g, key)
                        if (err != null) say(err, true) else say("$name is banned.", false)
                        done()
                    }
                    return@Column
                }
                MenuRow(if (p.muted) "sound" else "mute", if (p.muted) "Unmute for me" else "Mute for me") {
                    app.chat.sound.setMuted(key, !p.muted)
                    say(if (p.muted) "$name isn't muted for you." else "$name is muted for you.", false)
                }
                if (p.muted) MenuRow("hide", "Fold their messages", value = if (!p.show) "On" else "Off") { app.chat.sound.setHides(key, p.show) }
                MenuRow("audio", "Volume", value = "${p.volume}%") { page = "volume" }
                if (admin) {
                    if (app.chat.groups.timedOutUntil(g, key) > 0) MenuRow("clock", "Cancel timeout") {
                        val err = app.chat.groups.cancelTimeout(g, key)
                        if (err != null) say(err, true) else say("$name can post again.", false)
                        done()
                    } else MenuRow("clock", "Timeout") { page = "timeout" }
                    MenuRow("close", "Ban", danger = true) { page = "ban" }
                }
            }
        },
        confirmButton = { MButton(if (page == "ban") "Cancel" else "Done") { if (page == "ban") page = "" else done() } },
    )
}

/** A folded message from someone muted for me: "Muted, tap to show". */
@Composable
internal fun MutedChip(onShow: () -> Unit) {
    Row(Modifier.clip(Corner).clickable(role = Role.Button, onClickLabel = "Show") { onShow() }.heightIn(min = 40.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon("mute", Palette.textMuted, 16.dp)
        Text("Muted, tap to show", style = Type.small, modifier = Modifier.padding(start = 8.dp))
    }
}

/** "You're timed out until 3:40 PM" or "You were banned from Family" over the conversation, while it lasts. */
@Composable
internal fun MyModStatus(app: Muninn, g: String) {
    @Suppress("UNUSED_VARIABLE") val serial by app.chat.groups.modSerial.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(g) { while (true) { delay(30_000); tick++ } }
    val text = remember(g, serial, tick) { app.chat.groups.myStatus(g) }
    if (text.isNotEmpty()) io.github.ravenor907.muninn.ui.Panel(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
        Text(text, style = Type.big)
        Text("You can read, but not post, send files or join voice.", style = Type.small)
    }
}

/** Group settings for admins: who is banned (Unban) and who is timed out (time left, Cancel). */
@Composable
internal fun GroupModPanels(app: Muninn, g: String) {
    @Suppress("UNUSED_VARIABLE") val serial by app.chat.groups.modSerial.collectAsState()
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(g) { while (true) { delay(30_000); tick++ } }
    val banned = remember(g, serial) { app.chat.groups.bannedList(g) }
    val outs = remember(g, serial, tick) { app.chat.groups.timeoutList(g) }
    Framed("Banned") {
        if (banned.isEmpty()) Text("Nobody", style = Type.small, modifier = Modifier.padding(vertical = 8.dp))
        banned.forEach { b ->
            Line(b.name, "Unban", icon = "close") {
                io.github.ravenor907.muninn.ui.Ui.sure(io.github.ravenor907.muninn.ui.Ask("Unban ${b.name}?",
                    "They can join again by a code, a link or an invite.", "Unban",
                    onConfirm = { tell(app.chat.groups.unban(g, b.key), "${b.name} is unbanned.") }))
            }
        }
    }
    Framed("Timeouts") {
        if (outs.isEmpty()) Text("Nobody", style = Type.small, modifier = Modifier.padding(vertical = 8.dp))
        outs.forEach { t ->
            Line(t.name, leftText(t.until), icon = "clock", trailing = "Cancel") {
                io.github.ravenor907.muninn.ui.Ui.sure(io.github.ravenor907.muninn.ui.Ask("Cancel the timeout for ${t.name}?",
                    "They can post again now.", "Cancel timeout",
                    onConfirm = { tell(app.chat.groups.cancelTimeout(g, t.key), "${t.name} can post again.") }))
            }
        }
    }
}
