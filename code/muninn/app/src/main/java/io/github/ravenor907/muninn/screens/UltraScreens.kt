package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.TorRunner
import io.github.ravenor907.muninn.net.UltraPrivacy
import io.github.ravenor907.muninn.ui.Framed
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * Settings › Privacy and lock › Ultra privacy: the switch, Tor's state under it, and what's off while it's on.
 * Turning it on goes through two screens first ([UltraTurnOn]): what it does, then every downside, then Confirm.
 * Turning it off is one tap (it only gives back what it took).
 */
@Composable
internal fun UltraPrivacySection(app: Muninn) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tor by UltraPrivacy.state.collectAsState()
    val percent by UltraPrivacy.progress.collectAsState()
    var asking by remember { mutableStateOf(false) }
    val on = tor != UltraPrivacy.Tor.OFF
    Framed("Ultra privacy") {
        ToggleRow("Ultra privacy", UltraPrivacy.statusLine(tor, percent), on) { want ->
            if (want) asking = true
            else { TorRunner.set(ctx, false); Ui.say("Ultra privacy is off.") }
        }
        if (on) {
            Text("${UltraPrivacy.OFF_NOTE}: direct calls and files, sending videos, link previews and GIF search. Calls go through the call relay.",
                style = Type.small, modifier = Modifier.padding(top = 2.dp))
            Text(UltraPrivacy.NEARBY_NOTE, style = Type.small, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        }
        UltraBanner(Modifier.padding(bottom = 8.dp))
    }
    if (asking) UltraTurnOn(onCancel = { asking = false }) {
        asking = false
        TorRunner.set(ctx, true)
        Ui.say("Ultra privacy is on. Connecting to Tor…")
    }
}

/** Before turning it on: what it does (Next), then every downside (Turn on, Cancel). */
@Composable
internal fun UltraTurnOn(onCancel: () -> Unit, onConfirm: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        containerColor = Palette.panel,
        title = { Text(if (page == 0) "What Ultra privacy does" else "Before you turn it on", style = Type.big, modifier = Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (page == 1) Text("Ultra privacy has these downsides:", style = Type.normal, modifier = Modifier.padding(bottom = 4.dp))
                (if (page == 0) UltraPrivacy.WHAT_IT_DOES else UltraPrivacy.DOWNSIDES).forEach { line ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("•", style = Type.normal.copy(color = Palette.accent), modifier = Modifier.padding(end = 8.dp))
                        Text(line, style = Type.normal)
                    }
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (page == 0) MButton("Next", Modifier.fillMaxWidth(), Kind.Accent) { page = 1 }
                else MButton("Turn on Ultra privacy", Modifier.fillMaxWidth(), Kind.Accent) { onConfirm() }
                MButton(if (page == 0) "Cancel" else "Back", Modifier.fillMaxWidth()) { if (page == 0) onCancel() else page = 0 }
            }
        },
    )
}

/**
 * Tor is slow, blocked or couldn't start while Ultra privacy is on: said at the top of the chat list and in Settings,
 * with Wait (the clock starts over) or Turn off (back to the normal way).
 */
@Composable
internal fun UltraBanner(modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tor by UltraPrivacy.state.collectAsState()
    if (!UltraPrivacy.bannerShows(tor)) return
    Panel(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon("info", Palette.danger, 20.dp)
            Text(UltraPrivacy.bannerText(tor), style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f).padding(start = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tor != UltraPrivacy.Tor.MISSING) MButton("Wait", Modifier.weight(1f)) { TorRunner.waitLonger() }
            MButton("Turn off Ultra privacy", Modifier.weight(1f)) { TorRunner.set(ctx, false); Ui.say("Ultra privacy is off.") }
        }
    }
}

/** First setup's "Your privacy, built in": what protects every chat, and that Ultra privacy is there (off). */
@Composable
internal fun PrivacyBuiltIn(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(UltraPrivacy.SETUP_TITLE, style = Type.big, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        UltraPrivacy.SETUP_LINES.forEach { line ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                MIcon("check", Palette.accent, 18.dp, Modifier.padding(end = 10.dp, top = 2.dp))
                Text(line, style = Type.normal.copy(color = Palette.text))
            }
        }
        Text(UltraPrivacy.SETUP_ULTRA, style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 14.dp))
    }
}
