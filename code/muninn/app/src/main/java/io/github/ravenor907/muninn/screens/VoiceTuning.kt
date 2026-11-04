package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.ReadAloud
import io.github.ravenor907.muninn.net.ReadAloudText
import io.github.ravenor907.muninn.tv.HavAssets
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Framed
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui

/**
 * Settings › Labs › Voice tuning (1.43, shown only on the Pre-release channel): each natural voice's noise scale and
 * noise width. It reads Hávamál stanza 3 and its meaning with each of five presets (A, the default, to E); "Use this"
 * keeps one for that voice on this device, and the values in use are shown so they can be reported.
 */
@Composable
internal fun VoiceTuningPage() {
    val ctx = LocalContext.current
    val files by ReadAloud.files.collectAsState()
    val speaking by ReadAloud.speaking.collectAsState()
    val stanza = remember { HavAssets.verses(ctx).firstOrNull { it.n == 3 } }
    var voice by remember { mutableStateOf(ReadAloud.ownVoice(ctx).let { if (it == ReadAloudText.DEVICE) ReadAloudText.FEMALE else it }) }
    val has = remember(files, voice) { ReadAloud.hasVoice(ctx, voice) }
    val inUse = remember(files, voice) { ReadAloud.tune(ctx, voice) }
    DisposableEffect(Unit) { onDispose { ReadAloud.stop() } }
    Framed {
        Text("Reads Hávamál stanza 3 and its meaning with each preset. Use this keeps one for this voice.",
            style = Type.small, modifier = Modifier.padding(top = 6.dp))
        Text("Voice", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
        val choices = ReadAloudText.CHOICES.filter { it.first != ReadAloudText.DEVICE }
        ChoiceRow(choices.size, Modifier.padding(vertical = 6.dp)) { i, m ->
            val (v, name) = choices[i]
            MButton(name, m, if (voice == v) Kind.Accent else Kind.Normal) { ReadAloud.stop(); voice = v }
        }
        val name = choices.firstOrNull { it.first == voice }?.second ?: ""
        Text("In use: $name, ${ReadAloudText.tuneText(inUse)}", style = Type.normal.copy(color = Palette.text, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(top = 6.dp))
        if (!has) Text("Download this voice first on the Read aloud page.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        if (stanza == null) Text("Can't read the verses.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        ReadAloudText.TUNES.forEach { t ->
            Text("${t.name}: noise ${t.noise}, width ${t.width}", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Play", Modifier.weight(1f), icon = "play", enabled = has && stanza != null) {
                    stanza?.let { ReadAloud.tryTune(ctx, voice, t, it.verse, it.means) }
                }
                MButton(if (inUse == t) "In use" else "Use this", Modifier.weight(1f), if (inUse == t) Kind.Accent else Kind.Normal) {
                    ReadAloud.setTune(ctx, voice, t.name)
                    Ui.say("Saved: $name, ${ReadAloudText.tuneText(t)}")
                }
            }
        }
        if (speaking) MButton("Stop", Modifier.fillMaxWidth().padding(top = 12.dp)) { ReadAloud.stop() }
    }
}
