package io.github.ravenor907.muninn.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.call.AudioRoute
import io.github.ravenor907.muninn.net.call.RoomManager
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type

/** The phone's screen stays on while [on] (a shared screen or a camera picture is showing). */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * A shared screen filling the window: landscape, pinch to zoom (double tap goes back to the whole picture), and a
 * small bar with Mute, Deafen and Back (Leave apart, in the top corner, away from the zoom), hidden after a few
 * seconds and shown again by a tap. [pip]: Android's picture-in-picture window, just the picture (or who's talking
 * when nothing is shared).
 */
@Composable
internal fun SharedScreenFull(r: RoomManager.Ui, sc: RoomManager.Screen?, hideNames: Boolean, pip: Boolean, onExit: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val view = LocalView.current
    if (!pip) {
        BackHandler(onBack = onExit)
        DisposableEffect(Unit) {
            val w = activity?.window
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val bars = w?.let { WindowCompat.getInsetsController(it, view) }
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                bars?.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var bar by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    LaunchedEffect(bar, touched) { if (bar) { kotlinx.coroutines.delay(4_000); bar = false } }
    /** The picture never leaves the window: a zoomed picture moves at most to its own edges. */
    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { box = it }) {
        if (sc != null) {
            VideoPicture({ RoomManager.screenSurface(it) }, sc.width, sc.height, sc.rotation,
                Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                if (hideNames) "Shared screen" else "${sc.name}'s screen")
        } else {
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (hideNames) "Voice room" else r.title, style = Type.normal.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.people.size} people", style = Type.small.copy(color = Color.White))
            }
        }
        if (!pip) {
            // Over the picture (the picture's own view doesn't take touches): zoom, move and double tap.
            Box(Modifier.matchParentSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 4f)
                        offset = if (scale == 1f) Offset.Zero else clamp(offset + pan, scale)
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { bar = !bar; touched++ }, onDoubleTap = { scale = 1f; offset = Offset.Zero }) })
            if (bar) {
                Row(Modifier.align(Alignment.BottomCenter).systemBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!r.listenOnly) MButton(if (r.muted) "Unmute mic" else "Mute mic", kind = if (r.muted) Kind.Accent else Kind.Normal, icon = "mic") { touched++; RoomManager.setMuted(!r.muted) }
                    MButton(if (r.deafened) "Undeafen" else "Deafen", kind = if (r.deafened) Kind.Accent else Kind.Normal) { touched++; RoomManager.setDeafened(!r.deafened) }
                    MButton("Back", icon = "back") { onExit() }
                }
                MButton("Leave", Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(12.dp), kind = Kind.Danger) { RoomManager.leave() }
            }
        }
    }
}

/**
 * Before joining a voice room: who's in it, the microphone on or off, and where the sound goes. "Don't ask again"
 * joins as chosen here from then on (Settings › Calls turns the question back on).
 */
@Composable
internal fun RoomJoinSheet(app: Muninn, g: String, ch: String, onCancel: () -> Unit, onJoin: (muted: Boolean, output: String) -> Unit) {
    val id = app.identity
    val state = try { app.chat.groups.group(g)?.state } catch (e: Exception) { null }
    val title = if (io.github.ravenor907.muninn.net.call.RoomLogic.isGroupCall(ch)) "Group call" else state?.channel(ch)?.n ?: "Voice room"
    // Who's in it and where the sound can go, kept up to date while the sheet shows (earbuds turned on, someone joins).
    val seen by RoomManager.presence.collectAsState()
    val who = remember(g, ch, seen) { try { RoomManager.whoIn(g, ch) } catch (e: Exception) { emptyList() } }
    val ctx = LocalContext.current
    var devices by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { devices++ }
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { devices++ }
        }
        try { am?.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper())) } catch (_: Exception) {}
        onDispose { try { am?.unregisterAudioDeviceCallback(cb) } catch (_: Exception) {} }
    }
    val outputs = remember(devices) {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am == null) emptyList() else try { AudioRoute.preview(am) } catch (e: Exception) { emptyList() }
    }
    var micOff by remember { mutableStateOf(id.roomMuted) }
    var output by remember { mutableStateOf(AudioRoute.AUTOMATIC) }
    var dontAsk by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().background(Palette.ground).systemBarsPadding().padding(20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("JOIN VOICE", style = Type.section)
            Spacer(Modifier.height(8.dp))
            Text(title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state != null) Text(state.name, style = Type.normal)
            Spacer(Modifier.height(6.dp))
            Text(if (who.isEmpty()) "Nobody is here yet" else who.joinToString(", ") { it.name }, style = Type.small)
            Spacer(Modifier.height(16.dp))
            ToggleRow("Microphone on", if (micOff) "You join muted" else "Others hear you when you join", !micOff) { micOff = !it }
            Spacer(Modifier.height(8.dp))
            Text("Sound", style = Type.section)
            Choice("Automatic", "Headphones or Bluetooth first, then the speaker", output == AudioRoute.AUTOMATIC) { output = AudioRoute.AUTOMATIC }
            outputs.forEach { o -> Choice(o.name, "", output == o.id) { output = o.id } }
            Spacer(Modifier.height(8.dp))
            ToggleRow("Don't ask again", "Join straight away next time, mic as chosen here", dontAsk) { dontAsk = it }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
            MButton("Cancel") { onCancel() }
            MButton(if (micOff) "Join muted" else "Join", kind = Kind.Accent, icon = "mic") {
                id.roomMuted = micOff
                if (dontAsk) id.roomAsk = false
                onJoin(micOff, output)
            }
        }
    }
}

@Composable
private fun Choice(title: String, detail: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(io.github.ravenor907.muninn.ui.Corner).clickable(onClick = onClick)
        .background(if (on) Palette.field else Color.Transparent).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.normal.copy(color = Palette.text))
            if (detail.isNotEmpty()) Text(detail, style = Type.small, textAlign = TextAlign.Start)
        }
        if (on) io.github.ravenor907.muninn.ui.MIcon("check", Palette.accent, 18.dp)
    }
}

/**
 * In a voice room: "In voice" with the room's name, on every Muninn screen (under the app bar on phones, in the chat
 * column's header on tablets). Tap to go back to the room; the mic button mutes from here.
 */
@Composable
fun InVoiceChip(modifier: Modifier = Modifier, hideIn: String = "") {
    val r by RoomManager.room.collectAsState()
    val room = r ?: return
    if (hideIn.isNotEmpty() && hideIn == io.github.ravenor907.muninn.net.Folders.groupKey(room.g)) return
    val ctx = LocalContext.current
    val app = ctx.applicationContext as? Muninn
    val name = if (app?.identity?.showPreview == true && !io.github.ravenor907.muninn.ui.Panic.locked.value) room.title else "voice room"
    Row(modifier.clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.field).clickable(onClickLabel = "Open the voice room") { RoomActivity.show(ctx) }
        .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        io.github.ravenor907.muninn.ui.MIcon("sound", Palette.online, 16.dp)
        Text("In voice · $name", style = Type.small.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false))
        if (!room.listenOnly) io.github.ravenor907.muninn.ui.IconAction(if (room.muted) "mute" else "mic", if (room.muted) "Unmute mic" else "Mute mic",
            if (room.muted) Palette.danger else Palette.text, size = 18.dp) { RoomManager.setMuted(!room.muted) }
    }
}
