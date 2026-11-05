package io.github.ravenor907.muninn.tv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.ReadAloudText
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.SmoothSlider
import io.github.ravenor907.muninn.ui.Type

/**
 * The Kids' Device page's Read aloud speed, under the voice choice (KidVoicePart in ParentTvScreen.kt): a smooth
 * slider from 70% to 130% with its value shown, [speed] the parent's last pick as the config carries it (50 to 150;
 * null: none yet, the device reads at 100%). [onSave] gets the config's whole number ([KidVoice.speedOut]) when the finger lifts or the key is let go; it's sent only when the device and every
 * co-parent's app say k16. [enabled] false while it can't be sent.
 */
@Composable
fun KidSpeedPart(speed: Int?, enabled: Boolean, onSave: (Int) -> Unit) {
    var pct by remember(speed) { mutableFloatStateOf(ReadAloudText.kidSpeed(speed)) }
    val shown = ReadAloudText.speedText(pct)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Speed", style = Type.normal.copy(color = if (enabled) Palette.text else Palette.textFaint), modifier = Modifier.weight(1f))
        Text(shown, style = Type.small.copy(color = Palette.textSoft), maxLines = 1)
    }
    SmoothSlider(pct, { pct = it }, {
        val out = KidVoice.speedOut(pct)
        if (out != speed) onSave(out)
    }, ReadAloudText.SPEED_MIN..ReadAloudText.SPEED_MAX, 1f, "Read aloud speed, $shown", Modifier.fillMaxWidth(), enabled)
    Row(Modifier.fillMaxWidth()) {
        Text("Slower", style = Type.small, modifier = Modifier.weight(1f))
        Text("Faster", style = Type.small)
    }
}
