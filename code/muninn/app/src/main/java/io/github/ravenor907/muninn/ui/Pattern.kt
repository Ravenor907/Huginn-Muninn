package io.github.ravenor907.muninn.ui

import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Turns a drawn pattern into the secret the vault stretches, like a password. */
fun patternSecret(dots: List<Int>) = "pattern:" + dots.joinToString(",")

/** The pattern's rules, kept apart from drawing so they can be tested. Dots are 0..8, row by row. */
object PatternPath {
    /**
     * [dots] with [i] added. A dot already in it is left out; skipping over the middle dot of a row,
     * column or diagonal picks that one up too, as on Android.
     */
    fun add(dots: List<Int>, i: Int): List<Int> {
        if (i !in 0..8 || i in dots) return dots
        val out = dots.toMutableList()
        dots.lastOrNull()?.let { last ->
            val (r1, c1, r2, c2) = listOf(last / 3, last % 3, i / 3, i % 3)
            if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) { val mid = (r1 + r2) / 2 * 3 + (c1 + c2) / 2; if (mid !in out) out.add(mid) }
        }
        out.add(i)
        return out
    }

    /** The dot next to [cursor] one step along (dx, dy), or null at the edge. */
    fun move(cursor: Int, dx: Int, dy: Int): Int? {
        val r = cursor / 3 + dy
        val c = cursor % 3 + dx
        return if (r in 0..2 && c in 0..2) r * 3 + c else null
    }
}

/**
 * Android-style 3×3 pattern. Drag through the dots; passing straight over a dot in between picks
 * it up too, as on Android. [error] colours it red after a wrong try. Calls [onDone] on release.
 * With a D-pad, TV remote, controller or arrow keys: a ring marks a dot, OK (Enter, A) starts there,
 * moving on picks up each dot the ring reaches, OK again is done, and Back (B) starts over.
 * Raise [shake] after a wrong pattern and the pad gives a short shake. Every dot picked up gives a short tick.
 * [trail] off (Settings › Privacy and lock › Hide the pattern as I draw): no line and no lit dots while drawing,
 * so nobody watching sees it; the ticks still say a dot was taken.
 */
@Composable
fun PatternPad(modifier: Modifier = Modifier, error: Boolean = false, enabled: Boolean = true, shake: Int = 0, trail: Boolean = true, onDone: (List<Int>) -> Unit) {
    val dots = remember { mutableStateListOf<Int>() }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var cursor by remember { mutableIntStateOf(4) }
    var drawing by remember { mutableStateOf(false) }   // a pattern from the keys is under way
    var focused by remember { mutableStateOf(false) }
    var eatBack by remember { mutableStateOf(false) }   // Back started over: its release mustn't leave the screen too
    var keyed by remember { mutableStateOf(false) }     // keys have been used here: after a try, the focus comes back
    val done by rememberUpdatedState(onDone)
    val color = if (error) Palette.danger else Palette.accent
    val nudge = remember { Animatable(0f) }
    LaunchedEffect(shake) {
        if (shake > 0) for (x in listOf(-12f, 12f, -8f, 4f, 0f)) nudge.animateTo(x, tween(60))
    }
    fun clear() { drawing = false; finger = null; dots.clear() }
    fun finish() { val d = dots.toList(); clear(); if (d.isNotEmpty()) done(d) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun addDot(i: Int) {
        val d = PatternPath.add(dots.toList(), i)
        if (d.size != dots.size) {
            dots.clear(); dots.addAll(d)
            try { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) } catch (_: Exception) {}
        }
    }

    // A TV, or anything without a touchscreen: the pad takes the focus at once, ready for the remote.
    val context = LocalContext.current
    val keysFirst = remember {
        io.github.ravenor907.muninn.tv.KidMode.isTv(context) || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(keysFirst, enabled) { if ((keysFirst || keyed) && enabled) try { focus.requestFocus() } catch (_: Exception) {} }

    Column(modifier.graphicsLayer { translationX = nudge.value.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().widthIn(max = 320.dp).aspectRatio(1f)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused && drawing) clear() }
            .onKeyEvent { e ->
                if (!enabled) return@onKeyEvent false
                val back = e.key == Key.Back || e.key == Key.Escape || e.key == Key.ButtonB
                if (e.type == KeyEventType.KeyUp) {
                    if (back && eatBack) { eatBack = false; return@onKeyEvent true }
                    return@onKeyEvent false
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                keyed = true
                val step = when (e.key) {
                    Key.DirectionUp -> 0 to -1
                    Key.DirectionDown -> 0 to 1
                    Key.DirectionLeft -> -1 to 0
                    Key.DirectionRight -> 1 to 0
                    else -> null
                }
                when {
                    step != null -> {
                        val to = PatternPath.move(cursor, step.first, step.second)
                        // At the edge the focus may leave the pad, unless a pattern is under way.
                        if (to == null) drawing
                        else { cursor = to; if (drawing) addDot(to); true }
                    }
                    e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.ButtonA -> {
                        if (drawing) finish() else { dots.clear(); drawing = true; addDot(cursor) }
                        true
                    }
                    back && drawing -> { clear(); eatBack = true; true }
                    else -> false
                }
            }
            .focusable(enabled)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                fun center(i: Int) = Offset(size.width * ((i % 3) * 2 + 1) / 6f, size.height * ((i / 3) * 2 + 1) / 6f)
                fun hit(p: Offset): Int? = (0..8).firstOrNull { (center(it) - p).getDistance() < size.width / 9f }
                detectDragGestures(
                    onDragStart = { p -> drawing = false; dots.clear(); finger = p; hit(p)?.let { addDot(it) } },
                    onDrag = { change, _ -> finger = change.position; hit(change.position)?.let { addDot(it) } },
                    onDragEnd = { finger = null; val d = dots.toList(); if (d.isNotEmpty()) done(d); dots.clear() },
                    onDragCancel = { finger = null; dots.clear() },
                )
            }) {
            fun center(i: Int) = Offset(size.width * ((i % 3) * 2 + 1) / 6f, size.height * ((i / 3) * 2 + 1) / 6f)
            val stroke = size.width / 40f
            if (trail) {
                for (k in 1 until dots.size) drawLine(color, center(dots[k - 1]), center(dots[k]), stroke, StrokeCap.Round)
                if (dots.isNotEmpty()) finger?.let { drawLine(color.copy(alpha = 0.5f), center(dots.last()), it, stroke, StrokeCap.Round) }
            }
            for (i in 0..8) {
                val on = trail && i in dots
                drawCircle(if (on) color else Palette.textFaint, radius = size.width / (if (on) 22f else 34f), center = center(i))
            }
            // The remote's highlight: a ring around the dot it's on.
            if (focused && enabled) drawCircle(Palette.accent, radius = size.width / 14f, center = center(cursor), style = Stroke(width = maxOf(2f, size.width / 120f)))
        }
        if (focused && enabled) Text("Arrows move the ring. OK starts on a dot; move on through the dots, then OK again when done. Back starts over.",
            style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).widthIn(max = 320.dp))
    }
}
