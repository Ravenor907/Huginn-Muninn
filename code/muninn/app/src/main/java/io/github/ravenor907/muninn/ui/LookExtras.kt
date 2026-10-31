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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Settings › Look, under the Seasonal look: the season (Auto follows the date), the background (the season's own or
 * one of a few dark grounds), the animation (Off, Light or Full), and how big the leaves or snowflakes are and how
 * many fall (both 25% to 200%; 100% is the usual). [onChange] runs after any of the first three changes, to save them.
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

        Label("Animation")
        val anims = Seasons.ANIMS
        val anim = Seasons.anim(Access.seasonAnim)
        ChoiceRow(anims.size, Modifier.padding(top = 6.dp)) { i, m ->
            val (k, n) = anims[i]
            MButton(n, m, if (anim == k) Kind.Accent else Kind.Normal) { Access.seasonAnim = k; onChange() }
        }
        Text("Reduce motion holds it still.", style = Type.small, modifier = Modifier.padding(top = 4.dp))

        if (anim != Seasons.OFF) {
            LeafSlider(if (Palette.snow) "Flake size" else "Leaf size", LeafPrefs.size) { LeafPrefs.setSize(context, it) }
            LeafSlider("How many", LeafPrefs.count) { LeafPrefs.setCount(context, it) }
            Text("Fewer, smaller ones save battery.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 12.dp))

@Composable
private fun LeafSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        Text("$value%", style = Type.small.copy(color = Palette.textSoft), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
    Slider(
        value = value.toFloat(), onValueChange = { onChange((it / 5f).roundToInt() * 5) }, valueRange = 25f..200f, steps = 34,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label, $value percent" },
        // The empty part of the track uses the track shade, which stands out from the ground in every look; the 5% tick
        // marks take the track's own colours so the dots don't crowd it.
        colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.track,
            activeTickColor = Palette.accent, inactiveTickColor = Palette.track),
    )
}
