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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Settings › Look › Seasonal, over any look: Off, Auto by date or a season; while it's on, the background (on Dark: the
 * season's own or one of a few dark grounds), falling leaves or snow on or off, and how many fall (Amount, 1 to 400 on
 * screen) and how big they are (Size, 15% to 600% of the usual). [onChange] runs after any of the first three changes,
 * to save them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeasonControls(onChange: () -> Unit) {
    val context = LocalContext.current
    LeafPrefs.load(context)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Label("Seasonal")
        val seasons = Seasons.CHOICES
        val pick = Seasons.choice(Access.season)
        // Two a line, so "Auto by date" never breaks; one a line at a large text size.
        ChoiceRow(seasons.size, Modifier.padding(top = 6.dp), minWidth = 120.dp, max = 2) { i, m ->
            val (k, n) = seasons[i]
            MButton(n, m, if (pick == k) Kind.Accent else Kind.Normal) { Access.season = k; onChange() }
        }
        Text("Leaves or snow over any look.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
        if (pick != Seasons.OFF) {
            // The dark grounds only suit Dark; the other looks keep their own.
            if (Palette.tinted) {
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
                // Smooth (todo 1.42): what falls follows the slider at once; saved when the finger lifts.
                FallSlider("Amount", "$n $unit", FallScale.countPos(n), "1", "${FallScale.MAX_COUNT}",
                    { LeafPrefs.count = FallScale.countAt(it) }) { LeafPrefs.setCount(context, LeafPrefs.count) }
                val size = LeafPrefs.size
                FallSlider("Size", "$size%", FallScale.sizePos(size), "Tiny", "Huge",
                    { LeafPrefs.size = FallScale.sizeAt(it) }) { LeafPrefs.setSize(context, LeafPrefs.size) }
                Text("Fewer, smaller ones save battery.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Label(text: String) =
    Text(text, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 12.dp))

/**
 * A smooth slider from 0 to 100 (where [pos] sits), with [label] and its [value] above and the two ends named below.
 * [onChange] gets every move (apply it, don't save) and [onDone] runs when the finger lifts or the key is let go. The
 * thumb follows the finger, never the whole number it gives (Amount 1 to 2 is a long way at the low end); keys move it
 * one point a press, faster when held.
 */
@Composable
private fun FallSlider(label: String, value: String, pos: Float, low: String, high: String, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
        Text(value, style = Type.small.copy(color = Palette.textSoft), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
    // Where the thumb is while it moves; null when still (then it sits on the saved value).
    var moving by remember { mutableStateOf<Float?>(null) }
    SmoothSlider(moving ?: pos.coerceIn(0f, 100f), { moving = it; onChange(it) }, { onDone(); moving = null },
        0f..100f, 1f, "$label, $value", Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth()) {
        Text(low, style = Type.small, modifier = Modifier.weight(1f))
        Text(high, style = Type.small)
    }
}

/**
 * Settings › Look › Color vision: Typical, the three kinds of color-weak or color-blind sight, and No color, with a
 * small preview of what changes: the status dots (with their shapes), a failed thing, the accent and the call buttons.
 * [onChange] runs after a change, to save it.
 */
@Composable
fun ColorVisionControls(onChange: () -> Unit) {
    val kinds = ColorVision.ALL
    // Two a line at most, so the longer names never break mid-word.
    ChoiceRow(kinds.size, Modifier.padding(vertical = 8.dp), minWidth = 200.dp, max = 2) { i, m ->
        val (k, n) = kinds[i]
        MButton(n, m, if (Access.colorVision == k) Kind.Accent else Kind.Normal) { Access.colorVision = k; onChange() }
    }
    ColorVisionPreview()
}

/** The preview under Color vision. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorVisionPreview() {
    FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((status, words) in listOf("online" to "Online", "away" to "Away", "dnd" to "Busy")) {
            Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(status, 18.dp)
                Text(words, style = Type.small.copy(color = Palette.textSoft), modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(Modifier.height(32.dp).clip(LookShapes.corner).background(Palette.dangerFill).border(1.dp, Palette.dangerLine, LookShapes.corner)
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon("close", Palette.danger, 14.dp)
            Text("Failed", style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(start = 4.dp))
        }
        Box(Modifier.height(32.dp).clip(LookShapes.corner).background(Palette.accentFill).border(1.dp, Palette.accent, LookShapes.corner)
            .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text("Accent", style = Type.small.copy(color = Palette.accent))
        }
        for ((c, words) in listOf(Palette.answer to "Answer", Palette.hangUp to "Hang up")) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(c).semantics { contentDescription = words })
        }
    }
}
