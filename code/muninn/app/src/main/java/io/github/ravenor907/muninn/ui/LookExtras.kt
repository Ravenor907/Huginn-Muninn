package io.github.ravenor907.muninn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Settings › Look, under the Seasonal look: the season (Auto follows the date), the background (the season's own or
 * one of a few dark grounds), falling leaves or snow on or off, and how many fall (Amount, 1 to 400 on screen) and how
 * big they are (Size, 15% to 600% of the usual). [onChange] runs after any of the first three changes, to save them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonControls(onChange: () -> Unit) {
    val context = LocalContext.current
    LeafPrefs.load(context)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Label("Season")
        val seasons = Seasons.CHOICES
        val pick = Seasons.choice(Access.season)
        ChoiceRow(seasons.size, Modifier.padding(top = 6.dp)) { i, m ->
            val (k, n) = seasons[i]
            MButton(n, m, if (pick == k) Kind.Accent else Kind.Normal) { Access.season = k; onChange() }
        }
        Text("Auto follows the date.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

        Label("Background")
        val ground = Seasons.ground(Access.seasonGround)
        // Wraps onto a second line when they don't all fit; each is a full 48dp touch target.
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Seasons.GROUNDS.forEachIndexed { i, hex ->
                val on = ground == hex
                val c = Palette.parse(hex) ?: Palette.ground
                Box(Modifier.minimumInteractiveComponentSize().size(36.dp).clip(CircleShape).background(c)
                    .border(if (on) 3.dp else 1.dp, if (on) Palette.accent else Palette.lineMuted, CircleShape)
                    .clickable(role = Role.RadioButton) { Access.seasonGround = hex; onChange() }
                    .semantics { selected = on; contentDescription = "${Seasons.GROUND_NAMES.getOrElse(i) { "Color" }} background" })
            }
        }
        MButton("Default", Modifier.padding(top = 6.dp), if (ground.isEmpty()) Kind.Accent else Kind.Normal) {
            Access.seasonGround = ""; onChange()
        }

        val on = Seasons.falls(Access.seasonAnim)
        val snow = Palette.snow
        ToggleRow(if (snow) "Falling snow" else "Falling leaves", "Reduce motion holds it still.", on) {
            Access.seasonAnim = if (it) Seasons.FULL else Seasons.OFF; onChange()
        }

        if (on) {
            val config = LocalConfiguration.current
            val n = LeafPrefs.countFor(config.screenWidthDp, config.screenHeightDp)
            val unit = if (snow) (if (n == 1) "flake" else "flakes") else (if (n == 1) "leaf" else "leaves")
            FallSlider("Amount", "$n $unit", FallScale.countPos(n), "1", "${FallScale.MAX_COUNT}") {
                LeafPrefs.setCount(context, FallScale.countAt(it))
            }
            val size = LeafPrefs.size
            FallSlider("Size", "$size%", FallScale.sizePos(size), "Tiny", "Huge") {
                LeafPrefs.setSize(context, FallScale.sizeAt(it))
            }
            Text("Fewer, smaller ones save battery.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 12.dp))

/**
 * A slider from 0 to 100 (where [pos] sits; [onChange] gets the new position), with [label] and its [value] above
 * and the two ends named below.
 */
@Composable
private fun FallSlider(label: String, value: String, pos: Float, low: String, high: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        Text(value, style = Type.small.copy(color = Palette.textSoft), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
    Slider(
        value = pos.coerceIn(0f, 100f), onValueChange = onChange, valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label, $value" },
        // The empty part of the track uses the track shade, which stands out from the ground in every look.
        colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.track),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(low, style = Type.small, modifier = Modifier.weight(1f))
        Text(high, style = Type.small)
    }
}
