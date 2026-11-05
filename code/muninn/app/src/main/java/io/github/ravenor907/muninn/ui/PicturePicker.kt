package io.github.ravenor907.muninn.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Photos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PRIVACY = "Sent only to your contacts and the groups you're in, encrypted like a message."

/**
 * Your profile picture: choose one from your photos, take one with the camera, or use your initial on a colour.
 * A chosen picture goes through Move and scale (drag to move, pinch to zoom) and is saved small (256 by 256), round
 * everywhere it's shown. Nothing needs a new permission: the photo picker and the camera Muninn already has.
 * [onDone] closes it, saved or not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PicturePicker(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Muninn
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<Bitmap?>(null) }
    var camera by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) { Photos.prepare(context, uri, true) }
            val bmp = jpeg?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            busy = false
            if (bmp == null) Ui.say("Muninn couldn't open that picture.", true) else picked = bmp
        }
    }
    when {
        camera -> Dialog(onDismissRequest = { camera = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                io.github.ravenor907.muninn.screens.CameraScreen(video = false,
                    onPhoto = { jpeg -> picked = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size); camera = false },
                    onVideo = { _, _ -> }, close = { camera = false })
            }
        }
        picked != null -> Dialog(onDismissRequest = { picked = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            MoveAndScale(picked!!, onCancel = { picked = null }) { out ->
                app.profile.setMyPicture(out)
                picked = null
                Ui.say("Picture saved.")
                onDone()
            }
        }
        else -> Dialog(onDismissRequest = onDone) {
            val pictures by app.profile.pictures.collectAsState()
            val myColour by app.profile.myColour.collectAsState()
            val hasPicture = pictures.containsKey("me")
            // Scrolls when it's taller than the screen (a phone on its side, or large text).
            Panel(Modifier.widthIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Your picture", style = Type.big)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar("me", app.identity.username, 72.dp)
                    Text(PRIVACY, style = Type.small, modifier = Modifier.weight(1f).padding(start = 14.dp))
                }
                Spacer(Modifier.height(12.dp))
                Line(if (busy) "Opening…" else "Choose a picture", "From your photos", icon = "image") {
                    if (!busy) try { io.github.ravenor907.muninn.ui.Picking.launch(choose, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (_: Exception) { Ui.say("This phone has no photo picker.", true) }
                }
                Line("Take a photo", "With Muninn's camera", icon = "video") { camera = true }
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
                    Text("Use my initial and a color", style = Type.normal.copy(color = Palette.text))
                    // Wraps onto a second line when six don't fit; each is a full 48dp touch target.
                    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Palette.MY_COLORS.forEach { hex ->
                            val on = !hasPicture && myColour.equals(hex, ignoreCase = true)
                            val c = Palette.parse(hex) ?: Palette.goldTint
                            Box(Modifier.minimumInteractiveComponentSize().size(40.dp).clip(CircleShape).background(c)
                                .border(if (on) 3.dp else 1.dp, if (on) Palette.goldBright else Palette.line, CircleShape)
                                .clickable(role = Role.RadioButton) { app.profile.setMyColour(hex); if (hasPicture) app.profile.clearMyPicture(); onDone() }
                                .semantics { selected = on; contentDescription = "${Palette.myColorName(hex)} with your initial" },
                                contentAlignment = Alignment.Center) {
                                // Sized to the disc, so the initial stays inside it at any font size.
                                val letter = with(androidx.compose.ui.platform.LocalDensity.current) { 18.dp.toSp() }
                                Text(initialOf(app.identity.username), style = Type.big.copy(color = Palette.onDisc(c), fontSize = letter, lineHeight = letter), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
                if (hasPicture) Line("Remove picture", "Back to your initial", icon = "delete") {
                    app.profile.clearMyPicture(); Ui.say("Picture removed."); onDone()
                }
                Spacer(Modifier.height(8.dp))
                MButton("Close", Modifier.fillMaxWidth()) { onDone() }
            }
        }
    }
}

/**
 * Move and scale: the picture under a round window. Drag to move, pinch to zoom (1x to 5x); the picture always
 * covers the window. Save crops what's inside the circle to a 256 by 256 bitmap.
 */
@Composable
private fun MoveAndScale(src: Bitmap, onCancel: () -> Unit, onSave: (Bitmap) -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    val image = remember(src) { src.asImageBitmap() }
    var win by remember { mutableFloatStateOf(1f) }
    // The window is square and never taller than about half the screen, so the words and buttons below it stay in view
    // on a phone held sideways; the rest scrolls if it still can't fit.
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth - 60.dp, 448.dp, maxHeight * 0.55f).coerceAtLeast(160.dp)
        Panel(Modifier.padding(16.dp).widthIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            Text("Move and scale", style = Type.big)
            Spacer(Modifier.height(10.dp))
            BoxWithConstraints(Modifier.size(side).align(Alignment.CenterHorizontally).clip(Corner).background(Color.Black)) {
                val s = constraints.maxWidth.toFloat()
                win = s
                // The shorter side fills the window at 1x; zoom scales up from there.
                val base = s / minOf(src.width, src.height)
                fun scale() = base * zoom
                fun clamp() {
                    val w = src.width * scale(); val h = src.height * scale()
                    val maxX = (w - s) / 2f; val maxY = (h - s) / 2f
                    dx = dx.coerceIn(-maxX, maxX); dy = dy.coerceIn(-maxY, maxY)
                }
                Canvas(Modifier.fillMaxSize().pointerInput(src) {
                    detectTransformGestures { _, pan, z, _ ->
                        zoom = (zoom * z).coerceIn(1f, 5f)
                        dx += pan.x; dy += pan.y
                        clamp()
                    }
                }.semantics { contentDescription = "Your picture. Drag to move, pinch to zoom." }) {
                    val k = scale()
                    val left = s / 2f + dx - src.width * k / 2f
                    val top = s / 2f + dy - src.height * k / 2f
                    withTransform({ translate(left, top); scale(k, k, Offset.Zero) }) { drawImage(image) }
                    // Dim everything outside the circle, and ring it in gold.
                    val hole = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, s, s))
                        addOval(androidx.compose.ui.geometry.Rect(0f, 0f, s, s))
                    }
                    drawPath(hole, Color.Black, alpha = 0.55f)
                    drawCircle(Palette.gold, radius = s / 2f - 1f, center = Offset(s / 2f, s / 2f), style = Stroke(2f * density))
                }
            }
            Text("Drag to move, pinch to zoom. $PRIVACY", style = Type.small, modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MButton("Save", Modifier.weight(1f), Kind.Accent) {
                    onSave(crop(src, zoom, dx / win, dy / win))
                }
                MButton("Cancel", Modifier.weight(1f)) { onCancel() }
            }
        }
    }
}

/** What's under the round window, as a 256 by 256 bitmap. [fx] and [fy]: how far the picture was moved, as fractions of the window. */
private fun crop(src: Bitmap, zoom: Float, fx: Float, fy: Float): Bitmap {
    val out = 256f
    // Work in a window of 1 by 1: the shorter side fills it at 1x.
    val base = 1f / minOf(src.width, src.height)
    val k = base * zoom
    val left = 0.5f + fx - src.width * k / 2f
    val top = 0.5f + fy - src.height * k / 2f
    val bmp = Bitmap.createBitmap(out.toInt(), out.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.scale(out, out)
    canvas.translate(left, top)
    canvas.scale(k, k)
    canvas.drawBitmap(src, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
    return bmp
}
