package io.github.ravenor907.muninn.ui

import android.content.pm.PackageManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clipToBounds
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
 * How big the pattern pad is drawn, in dp: the smaller of the width and height it's given, less [PAD] for the room
 * around it, never over [MAX] (a tablet's pad stays hand-sized) and never under [MIN] (a small phone's stays drawable;
 * the pages around it scroll instead). A side that isn't limited (a scrolling page) doesn't count.
 */
object PatternSize {
    const val MAX = 320f
    const val MIN = 160f
    const val PAD = 16f

    fun side(maxWidth: Float, maxHeight: Float, reserve: Float = 0f): Float {
        val w = if (maxWidth.isFinite()) maxWidth else Float.POSITIVE_INFINITY
        val h = if (maxHeight.isFinite()) maxHeight - reserve else Float.POSITIVE_INFINITY
        val room = minOf(w, h)
        if (!room.isFinite()) return MAX
        return (room - PAD).coerceIn(MIN, MAX)
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

    val hinting = focused && enabled
    // Sized from both directions (PatternSize): the dots never run off the screen or under the buttons, and the
    // pad never grows past a hand's reach on a tablet. Measured once here, outside the Column below.
    BoxWithConstraints(modifier.graphicsLayer { translationX = nudge.value.dp.toPx() }.clipToBounds(), contentAlignment = Alignment.Center) {
    val side = PatternSize.side(maxWidth.value, maxHeight.value, reserve = if (hinting) HINT_ROOM else 0f).dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(side).clipToBounds()
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
                // Round 17: from the shorter side, so a pad squeezed in a short window never has touching circles.
                fun hit(p: Offset): Int? = (0..8).firstOrNull { (center(it) - p).getDistance() < minOf(size.width, size.height) / 9f }
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
        if (hinting) Text("Arrows move the ring. OK starts on a dot; move on through the dots, then OK again when done. Back starts over.",
            style = Type.small, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).widthIn(max = side))
    }
    }
}

/** Room kept under the pad for the remote's hint line, in dp. */
private const val HINT_ROOM = 64f

/**
 * A page around a [PatternPad] that always fits: [top] (titles, the status line, Forgot it?) and [bottom] (Cancel, Skip)
 * are never pushed off or squeezed to nothing. Upright, the pad gets the height left between them (never less than its
 * smallest size) and sits at [bias] (-1 top, 0 middle, 1 bottom); a page taller than the screen (large text, a small
 * phone) scrolls. Wider than tall (a phone on its side, a tablet in landscape): the words and buttons on the left,
 * scrolling, and the pad on the right, as tall as the screen allows.
 */
@Composable
fun PadPage(modifier: Modifier = Modifier, bias: Float = 0f, top: @Composable ColumnScope.() -> Unit,
            bottom: @Composable ColumnScope.() -> Unit = {}, pad: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        // Read once here: the Row and Column below use these, not the scope's own.
        val roomW = maxWidth
        val roomH = maxHeight
        val padMin = (PatternSize.MIN + PatternSize.PAD).dp
        val sideways = roomH.value.isFinite() && roomW.value.isFinite() && roomW > roomH && roomW >= padMin * 2
        if (sideways) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) { top(); bottom() }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = BiasAlignment(0f, bias)) { pad() }
            }
        } else {
            Layout(content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { top() }
                Box(contentAlignment = BiasAlignment(0f, bias)) { pad() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) { bottom() }
            }, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { parts, c ->
                val most = (PatternSize.MAX + PatternSize.PAD).dp.roundToPx()
                val w = if (c.hasBoundedWidth) c.maxWidth else most
                val fixed = Constraints(minWidth = w, maxWidth = w)
                val t = parts[0].measure(fixed)
                val b = parts[2].measure(fixed)
                val room = if (roomH.value.isFinite()) roomH.roundToPx() else t.height + most + b.height
                val padH = PadFit.padHeight(room, t.height, b.height, padMin.roundToPx())
                val p = parts[1].measure(Constraints(minWidth = w, maxWidth = w, minHeight = padH, maxHeight = padH))
                layout(w, t.height + padH + b.height) {
                    t.place(0, 0)
                    p.place(0, t.height)
                    b.place(0, t.height + padH)
                }
            }
        }
    }
}

/** [PadPage]'s sums, kept apart so they can be tested. */
object PadFit {
    /** The pad's height: what's left of [room] under [top] and over [bottom], never less than [least]. */
    fun padHeight(room: Int, top: Int, bottom: Int, least: Int): Int = maxOf(room - top - bottom, least)

    /** Whether the whole page fits without scrolling. */
    fun fits(room: Int, top: Int, bottom: Int, least: Int): Boolean = top + padHeight(room, top, bottom, least) + bottom <= room
}
