package io.github.ravenor907.muninn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The timing behind [HoldToConfirm], apart from Compose so it can be tested on its own: press, then [tick] while
 * held; it confirms once [durationMs] has passed. Letting go before that is early (nothing happens). Times are in ms
 * from any steady clock.
 */
class HoldTimer(val durationMs: Long = HOLD_MS) {
    enum class Release { NONE, EARLY, CONFIRMED }

    private var startedAt = -1L

    /** Confirmed during this hold (it fires once, until let go). */
    var done = false
        private set

    val holding: Boolean get() = startedAt >= 0

    /** Pressed. A second press while already held (a key's repeats) changes nothing. */
    fun press(now: Long) {
        if (startedAt >= 0) return
        startedAt = now
        done = false
    }

    /** 0 to 1: how far the hold has gone. */
    fun progress(now: Long): Float =
        if (startedAt < 0) 0f else if (done) 1f else ((now - startedAt).toFloat() / durationMs).coerceIn(0f, 1f)

    /** True exactly once: the first tick at or past [durationMs]. */
    fun tick(now: Long): Boolean {
        if (startedAt < 0 || done) return false
        if (now - startedAt < durationMs) return false
        done = true
        return true
    }

    /**
     * Let go. EARLY: before the time was up (say to keep holding). CONFIRMED: the time was up but no tick had seen it
     * yet, so confirm now. NONE: not held, or already confirmed. Either way it's ready for a new hold.
     */
    fun release(now: Long): Release {
        if (startedAt < 0) return Release.NONE
        val r = when {
            done -> Release.NONE
            tick(now) -> Release.CONFIRMED
            else -> Release.EARLY
        }
        startedAt = -1L
        done = false
        return r
    }

    companion object {
        /** Every hard-to-undo action is held this long. */
        const val HOLD_MS = 5000L
    }
}

/**
 * A button for a hard-to-undo action: it fills over 5 seconds while pressed (a touch, or Enter, the D-pad's center or
 * a controller's A held), then the phone buzzes and [onConfirm] runs. Letting go early does nothing and says "Keep
 * holding for 5 seconds". TalkBack reads [spoken] (for example "Hold for 5 seconds to block Mom"). [danger]: red,
 * otherwise the accent.
 */
@Composable
fun HoldToConfirm(label: String, spoken: String, onConfirm: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = true) {
    val timer = remember { HoldTimer() }
    var progress by remember { mutableFloatStateOf(0f) }
    var down by remember { mutableStateOf(false) }
    var early by remember { mutableStateOf(false) }
    val confirm by rememberUpdatedState(onConfirm)
    val haptic = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    fun now() = android.os.SystemClock.uptimeMillis()
    fun fire() {
        progress = 1f
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        confirm()
    }
    fun press() {
        if (down) return
        timer.press(now())
        early = false
        down = true
    }
    fun release() {
        if (!down) return
        down = false
        when (timer.release(now())) {
            HoldTimer.Release.EARLY -> early = true
            HoldTimer.Release.CONFIRMED -> fire()
            HoldTimer.Release.NONE -> {}
        }
        progress = 0f
    }
    LaunchedEffect(down) {
        while (down) {
            val t = now()
            progress = timer.progress(t)
            if (timer.tick(t)) { fire(); break }
            delay(16)
        }
    }
    val fill = if (danger) Palette.dangerFill else Palette.accentFill
    val line = if (danger) Palette.dangerLine else Palette.accent
    val fg = if (danger) Palette.danger else Palette.accent
    Column(modifier.width(IntrinsicSize.Max), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Corner).background(if (focused) Palette.focusFill else fill)
                .border(if (focused) 2.dp else 1.dp, if (focused) Palette.accent else line, Corner)
                .onPreviewKeyEvent { e ->
                    val hold = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter || e.key == Key.ButtonA
                    if (!hold) return@onPreviewKeyEvent false
                    when (e.type) {
                        KeyEventType.KeyDown -> { press(); true }
                        KeyEventType.KeyUp -> { release(); true }
                        else -> false
                    }
                }
                .focusable(interactionSource = source)
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); press(); waitForUpOrCancellation(); release() } }
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                    if (down) stateDescription = "${(progress * 100).toInt()} percent"
                },
            contentAlignment = Alignment.Center,
        ) {
            // The fill grows across the whole button, however tall large text makes it.
            Box(Modifier.matchParentSize()) { Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(line.copy(alpha = 0.55f))) }
            Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = fg)
        }
        if (early) Text("Keep holding for 5 seconds", style = Type.small.copy(color = Palette.textSoft),
            modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** "Block" → "Hold to block"; a label that already says Hold stays as it is. */
fun holdLabel(confirm: String): String =
    if (confirm.startsWith("Hold", ignoreCase = true)) confirm else "Hold to " + confirm.replaceFirstChar { it.lowercase() }

/** What TalkBack reads on a hold: "Hold for 5 seconds to block Mom". [what]: the action with its object. */
fun holdSpoken(what: String): String = "Hold for 5 seconds to " + what.replaceFirstChar { it.lowercase() }
