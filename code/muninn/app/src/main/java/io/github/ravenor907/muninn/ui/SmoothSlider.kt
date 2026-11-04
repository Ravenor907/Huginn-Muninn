package io.github.ravenor907.muninn.ui

import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * A smooth slider (todo 1.42): no steps or snapping, [onChange] gets every move at once (apply it live, don't save),
 * and [onDone] runs when the finger lifts or a key is let go (save then). Left and right (keyboard, d-pad, controller)
 * move it by [unit] a press, five units a repeat once held half a second ([SliderStep]); up and down still move focus.
 */
@Composable
fun SmoothSlider(
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    range: ClosedFloatingPointRange<Float>,
    unit: Float,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val min = range.start
    val max = range.endInclusive
    Slider(
        value = value.coerceIn(min, max),
        onValueChange = { onChange(it.coerceIn(min, max)) },
        onValueChangeFinished = onDone,
        valueRange = range,
        enabled = enabled,
        modifier = modifier
            .onPreviewKeyEvent { e ->
                if (!enabled) return@onPreviewKeyEvent false
                val dir = when (e.key) {
                    Key.DirectionLeft, Key.Minus, Key.NumPadSubtract -> -1
                    Key.DirectionRight, Key.Plus, Key.Equals, Key.NumPadAdd -> 1
                    else -> return@onPreviewKeyEvent false
                }
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        val n = e.nativeKeyEvent
                        onChange(SliderStep.next(value, dir, n.eventTime - n.downTime, unit, min, max))
                        true
                    }
                    KeyEventType.KeyUp -> { onDone(); true }
                    else -> true
                }
            }
            .semantics { contentDescription = description },
        colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.track),
    )
}
