package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ravenor907.muninn.net.call.AudioRoute
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.net.call.CallVideo
import io.github.ravenor907.muninn.net.call.Vp8
import io.github.ravenor907.muninn.net.call.CallSession.Phase
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.delay

/** The colors only this screen uses (the end-call red and answer green, remapped by Color vision in [Palette]). */
private object CallColors {
    val end get() = Palette.hangUp
    val answer get() = Palette.answer
    val ring1 get() = if (Access.contrast) Color(0xFF333333) else Color(0xFF1B1813)
    val ring2 get() = if (Access.contrast) Color(0xFF1A1A1A) else Color(0xFF141210)
    val encrypted get() = Palette.online
}

/** Call screen line icons (24-unit paths, drawn like MIcon). */
private object CallIcons {
    const val down = "M6 9l6 6 6-6"
    const val shield = "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z"
    const val micOff = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3 M4 4l16 16"
    const val mic = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3"
    const val speaker = "M4 9h4l5-4v14l-5-4H4z M16 9a4 4 0 0 1 0 6 M18.5 6.5a8 8 0 0 1 0 11"
    const val message = "M4 5h16v11H9l-5 4z"
    const val hangup = "M3 15c5-5 13-5 18 0l-2.5 2.5-3.5-1.5v-2.5a10 10 0 0 0-6 0V16l-3.5 1.5z"
    const val answer = "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"
    const val camera = "M3 7h12v10H3z M15 10l6-3v10l-6-3"
    const val cameraOff = "M3 7h12v10H3z M15 10l6-3v10l-6-3 M3 3l18 18"
    const val bluetooth = "M7 7l10 10-5 5V2l5 5L7 17"
    const val headphones = "M4 15v-3a8 8 0 0 1 16 0v3 M4 15h3v5H4z M17 15h3v5h-3z"
    const val flip = "M4 12a8 8 0 0 1 14-5l2-2v6h-6l2.5-2.5A5.5 5.5 0 0 0 6.5 12 M20 12a8 8 0 0 1-14 5l-2 2v-6h6l-2.5 2.5A5.5 5.5 0 0 0 17.5 12"
}

@Composable
internal fun Glyph(path: String, color: Color, size: Dp, weight: Float = 1.8f) {
    val p = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) { drawPath(p, color, style = Stroke(width = weight, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/**
 * A 1:1 call: who, how long, which paths carry it, mute, speaker, message, end.
 * Incoming, it offers Decline and Answer instead. Never shows an address of any kind.
 * [hideName]: panic-locked or previews off on the lock screen: "Muninn call" instead of the name.
 */
/**
 * [onAnswerVideo]: answer a video call with the camera on (asks for the camera first). [onCamera]: turn the camera on
 * or off during the call (on asks for the camera first).
 */
@Composable
fun CallScreen(onBack: () -> Unit, onMessage: (String) -> Unit, onAnswer: () -> Unit, hideName: Boolean = false,
               onAnswerVideo: () -> Unit = onAnswer, onCamera: (Boolean) -> Unit = { CallManager.setCamera(it) }) {
    val ui by CallManager.call.collectAsState()
    val video by CallVideo.state.collectAsState()
    val c = ui
    val showRemote = c != null && c.video && video.peerCamera && c.phase != Phase.ENDED
    val showSelf = c != null && c.video && video.cameraOn && c.phase != Phase.ENDED
    // Once either camera is on: the picture on top, then a strip with who, how and how long, then the buttons.
    // Nothing is drawn over the picture but the small self view. A voice call keeps the big avatar.
    if (c != null && c.phase != Phase.INCOMING && (showRemote || showSelf)) {
        VideoCall(c, video, showRemote, showSelf, if (hideName) "Muninn call" else c.name, onBack, onMessage, onAnswer, onAnswerVideo, onCamera)
        return
    }
    Box(Modifier.fillMaxSize().background(Palette.ground)) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar(onBack)
        if (c == null) {
            Spacer(Modifier.weight(1f))
            Text("No call right now", style = Type.big)
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            val name = if (hideName) "Muninn call" else c.name
            Spacer(Modifier.height(56.dp))
            Avatar(if (hideName) "" else c.peerKey, name, c.peerSpeaking && c.phase == Phase.LIVE)
            Spacer(Modifier.height(36.dp))
            Text(name, style = Type.title.copy(fontSize = 30.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold), maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
            Spacer(Modifier.height(6.dp))
            Text(status(c), style = Type.normal.copy(fontSize = 17.sp, color = Palette.textMuted), textAlign = TextAlign.Center)
            if (c.peerMuted && c.phase == Phase.LIVE) Text("$name is muted", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            if (c.phase != Phase.INCOMING && c.phase != Phase.ENDED) {
                Spacer(Modifier.height(24.dp))
                Paths(c)
                Spacer(Modifier.height(10.dp))
                Text("Muninn keeps every way open, so walking away doesn't drop the call", style = Type.small.copy(fontSize = 13.sp), textAlign = TextAlign.Center)
            }
            if (c.note.isNotEmpty() && c.phase != Phase.ENDED) Text(c.note, style = Type.small.copy(color = Palette.danger), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp))
        }
        Buttons(c, video, onMessage, onAnswer, onAnswerVideo, onCamera, 30.dp)
    }
    }
}

/** Back to the chat, and the "End-to-end encrypted" line. */
@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onBack).semantics { contentDescription = "Back to chat" },
            contentAlignment = Alignment.Center) { Glyph(CallIcons.down, Palette.text, 24.dp) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Glyph(CallIcons.shield, CallColors.encrypted, 14.dp, 2f)
            Spacer(Modifier.width(6.dp))
            Text("End-to-end encrypted", style = Type.small.copy(fontSize = 13.sp, color = CallColors.encrypted))
        }
        Spacer(Modifier.width(48.dp))
    }
}

/** Which ways carry the call right now, in one plain line that wraps on a narrow phone or with large text. */
@Composable
private fun Paths(c: CallManager.Ui) {
    val ways = buildList {
        if (c.wifi) add("Wi-Fi")
        if (c.bluetooth) add("Bluetooth")
        if (c.direct || (c.relay && !c.wifi)) add("the internet")
    }
    Text(if (ways.isEmpty()) "Connecting…" else "Connected over " + ways.joinToString(" and "), style = Type.normal.copy(color = Palette.text),
        textAlign = TextAlign.Center)
}

/** Decline and Answer while it rings; Mute, Audio, Camera, Flip or Message and End once it's on ([gap] above End). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Buttons(c: CallManager.Ui, video: CallVideo.State, onMessage: (String) -> Unit, onAnswer: () -> Unit, onAnswerVideo: () -> Unit,
                    onCamera: (Boolean) -> Unit, gap: Dp) {
    when (c.phase) {
        Phase.INCOMING -> {
            // Rang softly in a voice room: answering leaves it first, and the button says so.
            val room by io.github.ravenor907.muninn.net.call.RoomManager.room.collectAsState()
            val leave = room?.let { "Answer and leave " + it.title.ifEmpty { "the room" } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Round(CallIcons.hangup, "Decline", CallColors.end, Color.White, 76.dp) { CallManager.decline() }
                // A video call can be taken with the camera, or as a voice call.
                if (c.videoCall && Vp8.available) Round(CallIcons.camera, "Video", CallColors.answer, Color.White, 76.dp, onClick = onAnswerVideo)
                Round(CallIcons.answer, leave ?: if (c.videoCall) "Voice only" else "Answer", CallColors.answer, Color.White, 76.dp, onClick = onAnswer)
            }
        }
        Phase.ENDED -> {
            // Left a voice room to take this call: one tap back to it.
            val back by CallManager.backTo.collectAsState()
            val b = back?.takeIf { it.callId == c.callId }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            if (b == null) Spacer(Modifier.height(106.dp))
            else Row(Modifier.fillMaxWidth().heightIn(min = 106.dp), horizontalArrangement = Arrangement.Center) {
                Round(CallIcons.speaker, "Back to " + b.title.ifEmpty { "the room" }, Palette.coolTint, Palette.text, 76.dp) {
                    CallManager.backDone()
                    io.github.ravenor907.muninn.net.call.Ringer.backUsed(ctx)
                    io.github.ravenor907.muninn.screens.RoomActivity.join(ctx, b.g, b.ch)
                }
            }
        }
        else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Wraps onto two lines when four don't fit (a small phone at a large size).
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Round(if (c.muted) CallIcons.micOff else CallIcons.mic, if (c.muted) "Unmute mic" else "Mute mic", if (c.muted) Palette.accent else Palette.coolTint,
                    if (c.muted) Palette.ground else Palette.text, 68.dp, on = c.muted) { CallManager.setMuted(!c.muted) }
                AudioButton { CallManager.setOutput(it) }
                if (c.video) Round(if (video.cameraOn) CallIcons.camera else CallIcons.cameraOff, if (video.cameraOn) "Camera off" else "Camera",
                    if (video.cameraOn) Palette.accent else Palette.coolTint, if (video.cameraOn) Palette.ground else Palette.text, 68.dp, on = video.cameraOn) {
                    onCamera(!video.cameraOn)
                }
                if (c.video && video.cameraOn) Round(CallIcons.flip, "Flip", Palette.coolTint, Palette.text, 68.dp) { CallManager.switchCamera() }
                else Round(CallIcons.message, "Message", Palette.coolTint, Palette.text, 68.dp) { onMessage(c.peerKey) }
            }
            Spacer(Modifier.height(gap))
            Round(CallIcons.hangup, "End call", CallColors.end, Color.White, 76.dp, label = false) { CallManager.hangup() }
        }
    }
}

/**
 * A call with video: their picture fills the top (your own small in its corner), a solid strip under it with the
 * name, the paths and the time, then the buttons. Side by side when the phone is turned sideways, so the picture
 * keeps most of the screen.
 */
@Composable
private fun VideoCall(c: CallManager.Ui, video: CallVideo.State, showRemote: Boolean, showSelf: Boolean, name: String, onBack: () -> Unit,
                      onMessage: (String) -> Unit, onAnswer: () -> Unit, onAnswerVideo: () -> Unit, onCamera: (Boolean) -> Unit) {
    val picture: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Palette.groundDeep)) {
            if (showRemote) RemoteVideo(video, Modifier.fillMaxSize())
            // Their camera is off: just who it is, small, so the self view still has room.
            else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(if (name == c.name) c.peerKey else "", name, c.peerSpeaking && c.phase == Phase.LIVE)
            }
            if (showSelf) SelfView(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(width = 96.dp, height = 128.dp))
        }
    }
    val strip: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(Palette.panel).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = Type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(status(c), style = Type.normal.copy(fontSize = 15.sp, color = Palette.textMuted), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
            }
            if (c.peerMuted && c.phase == Phase.LIVE) Text("$name is muted", style = Type.small, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState())) { Paths(c) }
            if (c.note.isNotEmpty()) Text(c.note, style = Type.small.copy(color = Palette.danger), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.ground).systemBarsPadding()) {
        if (maxWidth > maxHeight) Row(Modifier.fillMaxSize()) {
            picture(Modifier.weight(1f).fillMaxHeight())
            Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { TopBar(onBack) }
                strip()
                Spacer(Modifier.height(16.dp))
                Buttons(c, video, onMessage, onAnswer, onAnswerVideo, onCamera, 16.dp)
            }
        } else Column(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { TopBar(onBack) }
            picture(Modifier.weight(1f).fillMaxWidth())
            strip()
            Spacer(Modifier.height(16.dp))
            Buttons(c, video, onMessage, onAnswer, onAnswerVideo, onCamera, 16.dp)
        }
    }
}

/** The other person's camera, fitted whole into the screen and turned upright. */
@Composable
private fun RemoteVideo(v: CallVideo.State, modifier: Modifier) =
    VideoPicture({ CallVideo.remoteSurface(it) }, v.width, v.height, v.rotation, modifier, "Their camera")

/** A decoded picture ([onSurface] gets the TextureView's surface), fitted whole and turned [rotation] degrees. Also the room's shared screen. */
@Composable
fun VideoPicture(onSurface: (Surface?) -> Unit, width: Int, height: Int, rotation: Int, modifier: Modifier, description: String) {
    AndroidView(factory = { ctx ->
        TextureView(ctx).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                private var surface: Surface? = null
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st); onSurface(surface) }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { onSurface(null); surface?.release(); surface = null; return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
    }, update = { tv ->
        tv.setTransform(Vp8.fit(tv.width.toFloat(), tv.height.toFloat(), width, height, rotation))
    }, modifier = modifier.semantics { contentDescription = description })
}

/** Your own camera, small in the corner (picture in picture), mirrored like a mirror. */
@Composable
private fun SelfView(modifier: Modifier) {
    val owner = LocalLifecycleOwner.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(owner) {
        CallVideo.bindCamera(owner, view.surfaceProvider)
        onDispose { CallVideo.unbindCamera(owner) }
    }
    AndroidView(factory = { view }, modifier = modifier.clip(io.github.ravenor907.muninn.ui.Corner).border(1.dp, Palette.lineMuted, io.github.ravenor907.muninn.ui.Corner)
        .semantics { contentDescription = "Your camera" })
}

@Composable
private fun status(c: CallManager.Ui): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.phase, c.connectedAt) { while (c.phase == Phase.LIVE) { now = System.currentTimeMillis(); delay(500) } }
    return when (c.phase) {
        Phase.CALLING -> "Calling"
        Phase.RINGING -> "Ringing"
        Phase.INCOMING -> if (c.videoCall) "Incoming video call" else "Incoming call"
        Phase.CONNECTING -> "Connecting"
        Phase.RECONNECTING -> "Reconnecting"
        Phase.LIVE -> "Connected · " + clock(((now - c.connectedAt) / 1000).coerceAtLeast(0))
        Phase.ENDED -> when (c.endWhy) {
            "declined" -> if (c.outgoing) "Declined" else "Call declined"
            "busy" -> "Busy on another call"
            "noanswer" -> "No answer"
            "nopath" -> "Can't connect"
            "dropped" -> "Call dropped"
            "missed" -> "Missed call"
            "error" -> "Call failed"
            else -> "Call ended"
        }
    }
}

private fun clock(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

/** The person's picture or initial ([key] "" while the name is hidden: a plain disc, no picture). Two soft rings; the inner one brightens while they speak. */
@Composable
private fun Avatar(key: String, name: String, speaking: Boolean) {
    Box(Modifier.size(176.dp).clip(CircleShape).background(CallColors.ring2), contentAlignment = Alignment.Center) {
        Box(Modifier.size(156.dp).clip(CircleShape).background(if (speaking) Palette.goldTint else CallColors.ring1), contentAlignment = Alignment.Center) {
            Box(Modifier.size(136.dp).clip(CircleShape).semantics { contentDescription = if (speaking) "$name is speaking" else name },
                contentAlignment = Alignment.Center) {
                io.github.ravenor907.muninn.ui.Avatar(key, name, 136.dp)
            }
        }
    }
}

@Composable
private fun Chip(label: String, how: String, live: Boolean) {
    val shape = io.github.ravenor907.muninn.ui.Corner
    var m = Modifier.heightIn(min = 30.dp).clip(shape)
    m = if (live) m.background(Palette.field) else m.border(1.dp, Palette.accentFill, shape)
    Row(m.padding(horizontal = 12.dp, vertical = 6.dp).semantics { contentDescription = "$label, $how"; stateDescription = if (live) "connected" else "not connected" },
        verticalAlignment = Alignment.CenterVertically) {
        val dot = Modifier.size(8.dp).clip(CircleShape)
        Box(if (live) dot.background(Palette.online) else dot.border(1.5.dp, Palette.lineMuted, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.small.copy(fontSize = 13.sp, color = if (live) Palette.text else Palette.textMuted))
    }
}

@Composable
private fun Round(icon: String, text: String, bg: Color, fg: Color, size: Dp, on: Boolean? = null, label: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.clip(io.github.ravenor907.muninn.ui.Corner).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = text; if (on != null) stateDescription = if (on) "on" else "off" }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Glyph(icon, fg, if (size > 70.dp) 32.dp else 26.dp, 2f) }
        // A long Bluetooth name wraps or shortens under its button instead of pushing the others away.
        if (label) Text(text, style = Type.small.copy(fontSize = 13.sp), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp).widthIn(max = size + 24.dp))
    }
}

/** The icon for where the sound goes. */
private fun glyphOf(kind: AudioRoute.Kind?): String = when (kind) {
    AudioRoute.Kind.SPEAKER -> CallIcons.speaker
    AudioRoute.Kind.BLUETOOTH -> CallIcons.bluetooth
    AudioRoute.Kind.WIRED -> CallIcons.headphones
    else -> CallIcons.answer
}

/**
 * "Audio" on a call: says where the sound goes now (Phone, Speaker, Headphones or the Bluetooth device's name) and
 * opens the list of everything connected, so the person can switch at any time. Lit while it isn't the earpiece.
 */
@Composable
private fun AudioButton(onPick: (String) -> Unit) {
    val now by AudioRoute.current.collectAsState()
    var open by remember { mutableStateOf(false) }
    val lit = now != null && now?.kind != AudioRoute.Kind.EARPIECE
    Round(glyphOf(now?.kind), now?.name ?: "Audio", if (lit) Palette.accent else Palette.coolTint, if (lit) Palette.ground else Palette.text, 68.dp) { open = true }
    if (open) AudioPicker(onDismiss = { open = false }) { onPick(it); open = false }
}

/** Every place the sound can go right now, the current one ticked. Shared by calls and voice rooms. */
@Composable
internal fun AudioPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val all by AudioRoute.outputs.collectAsState()
    val now by AudioRoute.current.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.panel,
        title = { Text("Audio", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (all.isEmpty()) Text("Nothing to choose from right now.", style = Type.small)
                all.forEach { o ->
                    val on = o.id == now?.id
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                        .clickable(role = Role.RadioButton) { onPick(o.id) }
                        .semantics { stateDescription = if (on) "selected" else "not selected" }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Glyph(glyphOf(o.kind), if (on) Palette.accent else Palette.text, 24.dp)
                        Text(o.name, style = Type.normal.copy(color = if (on) Palette.accent else Palette.text), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        if (on) Text("In use", style = Type.small.copy(color = Palette.accent))
                    }
                }
                Text("Bluetooth devices show here once they're connected to the phone.", style = Type.small.copy(fontSize = 13.sp),
                    modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = {},
        dismissButton = { io.github.ravenor907.muninn.ui.MButton("Close", onClick = onDismiss) },
    )
}
