package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.github.ravenor907.muninn.net.ReadAloud
import io.github.ravenor907.muninn.net.ReadAloudText
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.NavRow
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * Settings › Look › Read aloud (net/ReadAloud): Female (Alba, the default) or Male (northern English), Download now,
 * Allow on mobile data, how the natural voice is coming along, and Delete voices. Until the voices are published (no
 * line in voices.sha256) only one line shows: "Natural voices aren't ready yet", never a download that can't start.
 */
@Composable
internal fun ReadAloudSettings() {
    val ctx = LocalContext.current
    val status by ReadAloud.status.collectAsState()
    val files by ReadAloud.files.collectAsState()
    var voice by remember { mutableStateOf(ReadAloud.ownVoice(ctx)) }
    var mobile by remember { mutableStateOf(ReadAloud.mobileData(ctx)) }
    LaunchedEffect(Unit) { ReadAloud.refresh(ctx) }
    val has = remember(files, status) { ReadAloud.hasFiles(ctx) }
    val natural = remember(files, status, voice) { ReadAloud.natural(ctx) }
    val ready = remember(files, status, voice) { ReadAloud.ready(ctx) }
    if (!natural) {
        // Before the voices are published: one plain line, no voice choice and nothing to download.
        Text(status.ifEmpty { "Natural voices aren't ready yet. The phone's own voice reads" }, style = Type.small, modifier = Modifier.padding(top = 8.dp))
    } else {
        ChoiceRow(2, Modifier.padding(top = 8.dp)) { i, m ->
            val v = ReadAloudText.VOICES[i]
            MButton(if (v == ReadAloudText.MALE) "Male" else "Female", m, if (voice == v) Kind.Accent else Kind.Normal) {
                if (v == voice) return@MButton
                // A voice is already in: switching downloads the other one (about 65 MB) and deletes this one, so ask.
                if (has) Ui.sure(Ask("Switch to the ${if (v == ReadAloudText.MALE) "male" else "female"} voice?",
                    "Muninn downloads it (about 65 MB), then deletes the voice it has now.", "Switch") { voice = v; ReadAloud.setVoice(ctx, v) })
                else { voice = v; ReadAloud.setVoice(ctx, v) }
            }
        }
        if (status.isNotEmpty()) Text(status, style = Type.small, modifier = Modifier.padding(top = 4.dp))
        // Fetch the voice now (on home Wi-Fi before a trip, or through Tor with Ultra privacy on).
        if (!ready && !status.startsWith("Downloading")) NavRow("Download the voice now", "About 65 MB") {
            ReadAloud.prepare(ctx, asked = true)
        }
        ToggleRow("Allow on mobile data", "Downloads the voice (about 65 MB) on mobile data too", mobile) {
            mobile = it; ReadAloud.setMobileData(ctx, it)
        }
    }
    if (has) NavRow("Delete voices", "Frees the space. Downloads again when needed") {
        Ui.sure(Ask("Delete voices?", "The phone's own voice reads until the natural voice downloads again.", "Delete", danger = true) {
            ReadAloud.deleteVoices(ctx); Ui.say("Voices deleted.")
        })
    }
    Spacer(Modifier.height(8.dp))
}

/** About › More: the natural voices and the speech engine, with their licenses (code/shared/licenses/natural-voices.txt). */
@Composable
internal fun VoiceCredits() {
    Text("Natural voices: Alba (CC BY 4.0) and Northern English Male (CC BY-SA 4.0), Piper voices by Michael Hansen.",
        style = Type.small, modifier = Modifier.padding(top = 10.dp))
    Text("Licenses: speech engine sherpa-onnx (Apache-2.0) with ONNX Runtime (MIT) and eSpeak NG (GPL-3.0). " +
        "Each voice's model card comes with its download.", style = Type.small, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
}
