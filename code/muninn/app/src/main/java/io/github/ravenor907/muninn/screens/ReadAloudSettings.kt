package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.ReadAloud
import io.github.ravenor907.muninn.net.ReadAloudText
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.NavRow
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.SmoothSlider
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * The Read aloud page (consolidation item 8; Settings › Look › Accessibility › Read aloud): the on/off switch, the
 * voice (Female, Alba, the default; Male, northern English; or Device, the phone's own), the speed (50% to 150%, 80%
 * until moved) with Hear a sample, Download the voice now, Allow on mobile data and Delete voices. Until the natural
 * voices are published (no line in voices.sha256) Female and Male can't be picked and one line says "Natural voices
 * aren't ready yet": never a download that can't start.
 */
@Composable
internal fun ReadAloudSettings() {
    val ctx = LocalContext.current
    val status by ReadAloud.status.collectAsState()
    val files by ReadAloud.files.collectAsState()
    var on by remember { mutableStateOf(ReadAloud.isOn(ctx)) }
    var voice by remember { mutableStateOf(ReadAloud.ownVoice(ctx)) }
    var mobile by remember { mutableStateOf(ReadAloud.mobileData(ctx)) }
    LaunchedEffect(Unit) { ReadAloud.refresh(ctx) }
    val has = remember(files, status) { ReadAloud.hasFiles(ctx) }
    val natural = remember(files, status, voice) { ReadAloud.natural(ctx) }
    val ready = remember(files, status, voice) { ReadAloud.ready(ctx) }
    val device = voice == ReadAloudText.DEVICE
    ToggleRow("Read aloud", "Reads messages out loud", on) { on = it; ReadAloud.setOn(ctx, it) }
    if (on) {
        Text("Voice", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 8.dp))
        // Before the voices are published only Device reads, so it shows picked and the other two wait.
        val shown = if (natural) voice else ReadAloudText.DEVICE
        ChoiceRow(ReadAloudText.CHOICES.size, Modifier.padding(top = 6.dp)) { i, m ->
            val (v, name) = ReadAloudText.CHOICES[i]
            MButton(name, m, if (shown == v) Kind.Accent else Kind.Normal, enabled = natural || v == ReadAloudText.DEVICE) {
                if (v == voice) return@MButton
                // A natural voice is already in and this one isn't: switching downloads it (about 65 MB) and deletes
                // the one kept, so ask.
                if (v != ReadAloudText.DEVICE && has && !ReadAloud.hasVoice(ctx, v)) Ui.sure(Ask("Switch to the ${name.lowercase()} voice?",
                    "Muninn downloads it (about 65 MB), then deletes the voice it has now.", "Switch") { voice = v; ReadAloud.setVoice(ctx, v) })
                else { voice = v; ReadAloud.setVoice(ctx, v) }
            }
        }
        if (!natural) Text("Natural voices aren't ready yet", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        else if (status.isNotEmpty() && !device) Text(status, style = Type.small, modifier = Modifier.padding(top = 4.dp))
        ReadAloudSpeed()
        // Fetch the voice now (on home Wi-Fi before a trip, or through Tor with Ultra privacy on).
        if (natural && !device && !ready && !status.startsWith("Downloading")) NavRow("Download the voice now", "About 65 MB") {
            ReadAloud.prepare(ctx, asked = true)
        }
        if (natural && !device) ToggleRow("Allow on mobile data", "Voice download on mobile data", mobile) {
            mobile = it; ReadAloud.setMobileData(ctx, it)
        }
    }
    if (has) NavRow("Delete voices", "Frees about 65 MB") {
        Ui.sure(Ask("Delete voices?", "The phone's own voice reads until the natural voice downloads again.", "Delete", danger = true) {
            ReadAloud.deleteVoices(ctx); Ui.say("Voices deleted.")
        })
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Read aloud's speed, right under the voice: a smooth slider from 50% to 150% (80% until moved) with its value shown
 * ("83%"), applied at once while it moves and saved when the finger lifts or the key is let go; Hear a sample reads a
 * short line at that speed.
 */
@Composable
internal fun ReadAloudSpeed() {
    val ctx = LocalContext.current
    var pct by remember { mutableFloatStateOf(ReadAloud.ownSpeed(ctx)) }
    val shown = ReadAloudText.speedText(pct)
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Speed", style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        Text(shown, style = Type.small.copy(color = Palette.textSoft), maxLines = 1)
    }
    SmoothSlider(pct, { pct = it; ReadAloud.previewSpeed(ctx, it) }, { ReadAloud.setSpeed(ctx, pct) },
        ReadAloudText.SPEED_MIN..ReadAloudText.SPEED_MAX, 1f, "Read aloud speed, $shown", Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth()) {
        Text("Slower", style = Type.small, modifier = Modifier.weight(1f))
        Text("Faster", style = Type.small)
    }
    MButton("Hear a sample", Modifier.padding(top = 6.dp), icon = "sound") { ReadAloud.sample(ctx) }
}

/**
 * The Read aloud row's line under its name (Look › Accessibility), at most 32 letters: "Off", "Female voice, ready",
 * "Male voice, 120%", "Device voice, 80%".
 */
@Composable
internal fun readAloudRowValue(): String {
    val ctx = LocalContext.current
    val status by ReadAloud.status.collectAsState()
    val files by ReadAloud.files.collectAsState()
    return remember(status, files) {
        if (!ReadAloud.isOn(ctx)) "Off"
        else {
            val v = ReadAloud.ownVoice(ctx)
            val name = if (v == ReadAloudText.DEVICE || !ReadAloud.natural(ctx)) "Device" else if (v == ReadAloudText.MALE) "Male" else "Female"
            val speed = ReadAloudText.speedText(ReadAloud.ownSpeed(ctx))
            if (name != "Device" && ReadAloud.ready(ctx) && speed == "80%") "$name voice, ready" else "$name voice, $speed"
        }
    }
}

/** About › More: the natural voices and the speech engine, with their licenses (code/shared/licenses/natural-voices.txt). */
@Composable
internal fun VoiceCredits() {
    Text("Natural voices: Alba (CC BY 4.0) and Northern English Male (CC BY-SA 4.0), Piper voices by Michael Hansen.",
        style = Type.small, modifier = Modifier.padding(top = 10.dp))
    Text("Licenses: speech engine sherpa-onnx (Apache-2.0) with ONNX Runtime (MIT) and eSpeak NG (GPL-3.0). " +
        "Each voice's model card comes with its download.", style = Type.small, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
}
