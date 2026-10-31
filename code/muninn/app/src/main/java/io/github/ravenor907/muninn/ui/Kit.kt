package io.github.ravenor907.muninn.ui

import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.isSpecified
import io.github.ravenor907.muninn.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** Panels, buttons and fields: sharp in most looks, rounded in Seasonal (see LookShapes). */
val Corner: androidx.compose.ui.graphics.Shape get() = LookShapes.corner

/**
 * One slim row: the raven, the MUNINN wordmark (or a tab's [title]), then [trailing] (icon-only buttons). The wordmark takes
 * only the space left beside the buttons and shrinks to fit it, so the two never collide.
 * [onLogo]: taps on the raven (Settings counts them, for Labs). No ripple and nothing announced.
 * [centered]: the wordmark sits in the middle of the row (the chat list, with only the Settings button beside it).
 */
@Composable
fun Brand(onLogo: (() -> Unit)? = null, title: String = "MUNINN", centered: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().bar().heightIn(min = 64.dp).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val tap by androidx.compose.runtime.rememberUpdatedState(onLogo)
        val logo = Modifier.size(30.dp).then(if (onLogo != null) Modifier.pointerInput(Unit) { detectTapGestures(onTap = { tap?.invoke() }) } else Modifier)
        Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = logo)
        Spacer(Modifier.width(10.dp))
        FitLine(title, Type.title, Modifier.weight(1f), if (centered) Alignment.Center else Alignment.CenterStart)
        trailing()
    }
}

/** A 48dp icon-only button (the smallest comfortable touch target); [label] is what TalkBack reads. */
@Composable
fun IconAction(icon: String, label: String, tint: Color = Palette.text, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 22.dp, onClick: () -> Unit) {
    Box(modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) { MIcon(icon, tint, size) }
}

/** One line of text that shrinks (down to 60%) to fit its width instead of wrapping or running into its neighbors. */
@Composable
fun FitLine(text: String, style: TextStyle, modifier: Modifier = Modifier, align: Alignment = Alignment.CenterStart) {
    BoxWithConstraints(modifier, contentAlignment = align) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val size = remember(text, style, maxW) {
            if (!constraints.hasBoundedWidth) return@remember style.fontSize
            var s = style.fontSize.value
            val floor = s * 0.6f
            while (s > floor && measurer.measure(text, scaled(style, s), softWrap = false, maxLines = 1).size.width > maxW) s -= 0.5f
            maxOf(s, floor).sp
        }
        Text(text, style = scaled(style, size.value), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** [style] at font size [sp], its letter spacing shrunk to match. */
private fun scaled(style: TextStyle, sp: Float): TextStyle {
    val k = sp / style.fontSize.value
    return style.copy(fontSize = sp.sp, letterSpacing = if (style.letterSpacing.isSpecified) style.letterSpacing * k else style.letterSpacing)
}

/** High contrast outlines fields and bubbles that otherwise only differ by a shade. */
fun Modifier.hcBorder(shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (Access.contrast) this.border(1.dp, Palette.line, shape) else this

@Composable
fun Section(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = Type.section.copy(color = Palette.accent), modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, focused: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var m = modifier.fillMaxWidth().clip(Corner).background(if (focused) Palette.focusFill else Palette.panel)
        .border(if (focused) 2.dp else 1.dp, if (focused) Palette.accent else Palette.line, Corner)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Column(m.padding(14.dp), content = content)
}

/**
 * A framed section: a short label, then its controls in one card with the look's corners. Every page groups its
 * controls this way, with the same spacing between cards.
 */
@Composable
fun Framed(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 14.dp)) {
        if (title != null) Text(title.uppercase(), style = Type.section.copy(color = Palette.accent),
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp).semantics { heading() })
        Column(Modifier.fillMaxWidth().clip(Corner).background(Palette.panel).border(1.dp, Palette.line, Corner)
            .padding(horizontal = 14.dp, vertical = 6.dp), content = content)
    }
}

/** A row that opens a page: its name, an optional short value under it, and an arrow. */
@Composable
fun NavRow(title: String, value: String = "", icon: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { MIcon(icon, Palette.accent, 20.dp); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = Type.normal.copy(color = Palette.text))
            if (value.isNotEmpty()) Text(value, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MIcon("forward", Palette.textMuted, 18.dp)
    }
}

/** One item in a three-dot menu. Destructive items are red and go last. [value]: a short state on the right. */
@Composable
fun MenuRow(icon: String, text: String, danger: Boolean = false, value: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    val tint = when { !enabled -> Palette.textFaint; danger -> Palette.danger; else -> Palette.text }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Corner).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon(icon, if (danger) Palette.danger else if (enabled) Palette.accent else Palette.textFaint, 20.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, style = Type.normal.copy(color = tint), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (value.isNotEmpty()) Text(value, style = Type.small, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The three-dot button that opens an item's menu, the same everywhere. [label] is what TalkBack reads. */
@Composable
fun DotsButton(label: String, tint: Color = Palette.text, onClick: () -> Unit) {
    Column(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.5.dp, Alignment.CenterVertically)) {
        repeat(3) { Box(Modifier.size(3.5.dp).clip(CircleShape).background(tint)) }
    }
}

enum class Kind { Normal, Accent, Danger }

@Composable
fun MButton(text: String, modifier: Modifier = Modifier, kind: Kind = Kind.Normal, enabled: Boolean = true, icon: String? = null, onClick: () -> Unit) {
    val (bg, line, fg) = when (kind) {
        Kind.Accent -> Triple(Palette.accentFill, Palette.accent, Palette.accent)
        Kind.Danger -> Triple(Palette.dangerFill, Palette.dangerLine, Palette.danger)
        Kind.Normal -> Triple(Palette.raised, Palette.lineMuted, Palette.text)
    }
    Row(
        modifier.heightIn(min = 48.dp).clip(Corner).background(bg).border(1.dp, line, Corner)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { MIcon(icon, if (enabled) fg else Palette.textFaint, 20.dp); Spacer(Modifier.width(8.dp)) }
        FitText(text, TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
            color = if (enabled) fg else Palette.textFaint, textAlign = TextAlign.Center), Modifier.weight(1f, fill = false))
    }
}

/**
 * Text that fits the space it's given on any phone and at any font size: it never splits a word
 * across lines. It shrinks (down to 70%) until every word fits on a line and the whole label takes
 * at most two lines; only if it still can't fit at the smallest size does it wrap normally.
 */
@Composable
fun FitText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val measurer = rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val size = remember(text, style, maxW) {
            if (!constraints.hasBoundedWidth) return@remember style.fontSize
            val words = text.split(' ').filter { it.isNotEmpty() }
            var s = style.fontSize.value
            val floor = s * 0.7f
            while (s > floor) {
                val st = style.copy(fontSize = s.sp)
                val widest = words.maxOfOrNull { measurer.measure(it, st, softWrap = false, maxLines = 1).size.width } ?: 0
                val lines = measurer.measure(text, st, constraints = Constraints(maxWidth = maxW)).lineCount
                if (widest <= maxW && lines <= 2) break
                s -= 0.5f
            }
            maxOf(s, floor).sp
        }
        Text(text, style = style.copy(fontSize = size, lineHeight = size * 1.2f))
    }
}

@Composable
fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!on) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Type.big.copy(fontSize = 16.sp))
            if (detail.isNotEmpty()) Text(detail, style = Type.small)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Palette.ground, checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.textMuted, uncheckedTrackColor = Palette.raised, uncheckedBorderColor = Palette.lineMuted))
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, single: Boolean = true, keyboard: KeyboardOptions = KeyboardOptions.Default,
          secret: Boolean = false) {
    Box(modifier.fillMaxWidth().clip(Corner).background(Palette.groundDeep).border(1.dp, Palette.lineMuted, Corner).padding(12.dp)) {
        if (value.isEmpty()) Text(hint, style = Type.normal.copy(color = Palette.textFaint))
        BasicTextField(value, onChange, singleLine = single, keyboardOptions = keyboard, textStyle = Type.normal.copy(color = Palette.text),
            cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}

@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, color: Color = Palette.accent) {
    Box(modifier.fillMaxWidth().height(6.dp).background(Palette.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(color))
    }
}

@Composable
fun Line(title: String, detail: String = "", icon: String? = null, trailing: String = "", selected: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Corner).background(if (selected) Palette.accentFill else Color.Transparent)
            .border(if (selected) 1.dp else 0.dp, if (selected) Palette.accent else Color.Transparent, Corner)
            .then(if (onLong != null) Modifier.pointerInput(onLong, onClick) { detectTapsAndLong(onClick, onLong) } else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { MIcon(icon, if (selected) Palette.accent else Palette.textMuted, 24.dp); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing.isNotEmpty()) Text(trailing, style = Type.small, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapsAndLong(onTap: () -> Unit, onLong: () -> Unit) {
    detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() })
}

// ------------------------------------------------------------------ dialogs and toasts

/** One question at a time, app-wide. hold: the confirm button has to be held for 2 seconds. */
data class Ask(
    val title: String, val message: String, val confirm: String, val danger: Boolean = false, val hold: Boolean = false,
    val cancel: String = "Cancel", val onCancel: () -> Unit = {},
    /** An optional second action, shown beside the main one. */
    val extra: String? = null, val onExtra: () -> Unit = {}, val onConfirm: () -> Unit,
)

object Ui {
    val ask = MutableStateFlow<Ask?>(null)
    val toast = MutableStateFlow<Pair<String, Boolean>?>(null)
    fun sure(a: Ask) { ask.value = a }
    fun say(text: String, bad: Boolean = false) { toast.value = text to bad }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskDialog(a: Ask) {
    AlertDialog(
        onDismissRequest = { Ui.ask.value = null; a.onCancel() },
        containerColor = Palette.panel,
        title = { Text(a.title, style = Type.big) },
        // Long messages at a large text size scroll instead of pushing the buttons off the screen.
        text = { Text(a.message, style = Type.normal, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            if (a.hold) HoldButton(a.confirm) { Ui.ask.value = null; a.onConfirm() }
            else MButton(a.confirm, kind = if (a.danger) Kind.Danger else Kind.Accent) { Ui.ask.value = null; a.onConfirm() }
        },
        dismissButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton(a.cancel) { Ui.ask.value = null; a.onCancel() }
                a.extra?.let { MButton(it) { Ui.ask.value = null; a.onExtra() } }
            }
        },
    )
}

/** Hold for 2 seconds: a stray tap can't erase a drive. Letting go early does nothing. */
@Composable
fun HoldButton(text: String, onDone: () -> Unit) {
    var held by remember { mutableFloatStateOf(0f) }
    var down by remember { mutableStateOf(false) }
    LaunchedEffect(down) {
        if (!down) { held = 0f; return@LaunchedEffect }
        val start = System.currentTimeMillis()
        while (down) {
            held = ((System.currentTimeMillis() - start) / 2000f).coerceAtMost(1f)
            if (held >= 1f) { down = false; onDone(); break }
            delay(30)
        }
    }
    Box(
        Modifier.heightIn(min = 48.dp).clip(Corner).background(Palette.dangerFill).border(1.dp, Palette.dangerLine, Corner)
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); down = true; waitForUpOrCancellation(); down = false } },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The fill grows across the whole button, however tall large text makes it.
        Box(Modifier.matchParentSize()) { Box(Modifier.fillMaxHeight().fillMaxWidth(held).background(Palette.dangerLine)) }
        Text(if (held > 0f) "Keep holding…" else "Hold: $text", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Palette.danger)
    }
}

/**
 * A row of [count] choices that share the width equally, as many per line as fit at the current text size: all on
 * one line usually, two or one per line at a large font size, so no label is squeezed into broken words.
 * [minWidth] is the narrowest a choice may be at 100% text, [max] the most on one line; [item] draws choice i with the
 * modifier it's given.
 */
@Composable
fun ChoiceRow(count: Int, modifier: Modifier = Modifier, minWidth: androidx.compose.ui.unit.Dp = 72.dp, gap: androidx.compose.ui.unit.Dp = 6.dp,
              max: Int = count, item: @Composable (Int, Modifier) -> Unit) {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perLine = (((maxWidth + gap) / (minWidth * scale + gap)).toInt()).coerceIn(1, maxOf(minOf(count, max), 1))
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            (0 until count).chunked(perLine).forEach { line ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    line.forEach { i -> item(i, Modifier.weight(1f)) }
                    // A short last line keeps the same widths as the lines above it.
                    repeat(perLine - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

