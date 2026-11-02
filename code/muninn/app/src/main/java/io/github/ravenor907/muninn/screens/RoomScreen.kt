package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.net.call.AudioRoute
import io.github.ravenor907.muninn.net.call.RoomLogic
import io.github.ravenor907.muninn.net.call.RoomManager
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.delay

/**
 * A voice room or group call: everyone in it (a ring while they talk, dimmed while connecting, a mark
 * while muted), how long, and Mute, Speaker and Leave. Ringing, it offers Join and Decline instead.
 * Never shows an address. [hideNames]: panic-locked or previews off on the lock screen.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RoomScreen(onBack: () -> Unit, onJoinRing: () -> Unit, hideNames: Boolean = false, pip: Boolean = false, onAllowMic: () -> Unit = {}) {
    // Cap g2: someone pressed and held (or tapped): Mute for me, Volume, and for admins Timeout and Ban.
    val roomCtx = androidx.compose.ui.platform.LocalContext.current
    val app = roomCtx.applicationContext as? io.github.ravenor907.muninn.Muninn
    var modFor by remember { mutableStateOf<RoomManager.Person?>(null) }
    // Who I muted for me (the chat's Mute for me too): their face says so, so a silent face isn't taken for a broken mic.
    val soundAll by remember(app) {
        app?.chat?.sound?.all ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap<String, io.github.ravenor907.muninn.net.PeopleSound.Person>())
    }.collectAsState()
    val room by RoomManager.room.collectAsState()
    val ring by RoomManager.ring.collectAsState()
    val screen by RoomManager.screen.collectAsState()
    // A shared screen or a camera picture showing: the phone's screen stays on.
    val pics by io.github.ravenor907.muninn.net.call.RoomVideo.state.collectAsState()
    // Not while the lock screen is up: a room opened from the lock screen never keeps a pocketed phone lit.
    val keyguard = roomCtx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
    KeepScreenOn(!keyguard && room != null && (screen != null || pics.cameraOn || pics.pics.values.any { it.on }))
    // Tap the shared screen: full screen and landscape. Picture-in-picture: just the picture.
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(screen == null) { if (screen == null) full = false }
    val r0 = room
    if (r0 != null && (pip || (full && screen != null))) { SharedScreenFull(r0, screen, hideNames, pip) { full = false }; return }
    Column(Modifier.fillMaxSize().background(Palette.ground).systemBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", "Back", Palette.text, size = 24.dp, onClick = onBack)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                MIcon("shield", Palette.online, 14.dp, weight = 2f)
                Spacer(Modifier.width(6.dp))
                Text("End-to-end encrypted, to each person", style = Type.small.copy(fontSize = 13.sp, color = Palette.online))
            }
            Spacer(Modifier.width(44.dp))
        }
        val r = room
        val g = ring
        if (r == null && g != null) {
            // A group call is ringing.
            Spacer(Modifier.weight(1f))
            Text("VOICE ROOM", style = Type.section)
            Spacer(Modifier.height(20.dp))
            Face(if (hideNames) "" else g.from, if (hideNames) "?" else g.name, speaking = false, dim = false, size = 120)
            Spacer(Modifier.height(20.dp))
            Text(if (hideNames) "Someone is calling" else if (g.group.isNotEmpty()) "${g.name} is calling ${g.group}" else "${g.name} is calling",
                style = Type.big.copy(fontSize = 24.sp), textAlign = TextAlign.Center)
            Text("Up to ${RoomLogic.MAX_PEOPLE} people talk at once", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MButton("Decline", kind = Kind.Danger) { RoomManager.declineRing() }
                MButton("Join", kind = Kind.Accent, onClick = onJoinRing)
            }
            return@Column
        }
        if (r == null) {
            Spacer(Modifier.weight(1f))
            Text("Not in a voice room", style = Type.big)
            Spacer(Modifier.weight(1f))
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(28.dp))
            Text(if (hideNames) "Voice room" else r.title, style = Type.title.copy(fontSize = 26.sp, letterSpacing = 1.sp), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 340.dp))
            if (!hideNames && r.groupName.isNotEmpty()) Text(r.groupName, style = Type.normal, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(6.dp))
            Text(status(r), style = Type.normal.copy(color = Palette.textMuted))
            if (r.note.isNotEmpty()) Text(r.note, style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
            // A phone call has the sound: the room is paused, mic and sound, until it ends.
            if (r.held.isNotEmpty()) Text(if (r.held == io.github.ravenor907.muninn.net.call.SoundHold.OTHER_APP) "${r.held}. Others see you muted." else "${r.held}. The room is paused and others see you muted.", style = Type.small.copy(color = Palette.danger),
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            if (r.deafened) Text("Deafened: you hear nobody and your mic is off", style = Type.small.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
            if (r.listenOnly) {
                Text("Listening only. Muninn can't use the microphone.", style = Type.small.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
                MButton("Allow the microphone to talk", Modifier.padding(top = 6.dp), icon = "mic") { onAllowMic() }
            }
            if (r.stillHere) {
                Text("Still here? You're the only one in this room.", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MButton("I'm here", kind = Kind.Accent) { RoomManager.stay() }
                    MButton("Leave", kind = Kind.Danger) { RoomManager.leave() }
                }
            }
            // Someone shares their screen (shared/docs/protocol.md 5.8.7): it shows here, encrypted to this phone like the sound.
            screen?.let { sc ->
                Spacer(Modifier.height(16.dp))
                Text(if (hideNames) "A screen is shared" else "${sc.name} is sharing their screen", style = Type.small.copy(color = Palette.text))
                Spacer(Modifier.height(8.dp))
                val turned = sc.rotation % 180 != 0
                val ratio = if (sc.width > 0 && sc.height > 0) (if (turned) sc.height.toFloat() / sc.width else sc.width.toFloat() / sc.height) else 16f / 9f
                Box(Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.5f, 3f))) {
                    VideoPicture({ RoomManager.screenSurface(it) }, sc.width, sc.height, sc.rotation,
                        Modifier.fillMaxSize().clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.field),
                        if (hideNames) "Shared screen" else "${sc.name}'s screen")
                    // Over the picture (its own view doesn't take touches): tap for full screen.
                    Box(Modifier.matchParentSize().combinedClickable(onClickLabel = "Full screen") { full = true })
                }
                Text("Tap it for full screen", style = Type.small.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
            }
            // Group video (up to 4 people, peer to peer only): everyone with a camera on, and my own picture.
            val vid by io.github.ravenor907.muninn.net.call.RoomVideo.state.collectAsState()
            val showing = r.people.filter { !it.me && vid.pics[it.key]?.on == true }
            if (vid.cameraOn || showing.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                    if (vid.cameraOn) RoomSelfView(Modifier.weight(1f).aspectRatio(3f / 4f))
                    showing.forEach { p ->
                        val pic = vid.pics[p.key] ?: io.github.ravenor907.muninn.net.call.RoomVideo.Pic()
                        Box(Modifier.weight(1f).aspectRatio(3f / 4f).clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.field)) {
                            VideoPicture({ io.github.ravenor907.muninn.net.call.RoomVideo.surface(p.key, it) }, pic.width, pic.height, pic.rotation,
                                Modifier.fillMaxSize(), if (hideNames) "Someone's camera" else "${p.name}'s camera")
                            Text(if (hideNames) "?" else p.name, style = Type.small.copy(color = Palette.text), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp))
                        }
                    }
                }
                Text("Video goes straight between phones, never through a server. Anyone who can't connect directly stays on voice.",
                    style = Type.small.copy(fontSize = 12.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            }
            if (vid.note.isNotEmpty()) Text(vid.note, style = Type.small.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(28.dp))
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                r.people.forEach { p ->
                    val name = if (hideNames && !p.me) "?" else p.name
                    val mutedByMe = !p.me && soundAll[p.key]?.muted == true
                    Column(Modifier.width(96.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                        .combinedClickable(enabled = !p.me && !hideNames && app != null, onClickLabel = "Menu", onLongClickLabel = "Menu",
                            onLongClick = { modFor = p }) { modFor = p }
                        .semantics {
                        contentDescription = name + (if (p.speaking) ", speaking" else "") + (if (p.muted) ", muted" else "") +
                            (if (mutedByMe) ", muted by you, tap to unmute" else "") +
                            (if (p.state == "connecting") ", connecting" else if (p.state == "reconnecting") ", reconnecting" else "")
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box {
                            Face(if (hideNames && !p.me) "" else if (p.me) "me" else p.key, name, p.speaking, p.state != "live", size = 72)
                            if (p.muted || mutedByMe) Box(Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(Palette.dangerFill)
                                .border(1.dp, Palette.dangerLine, CircleShape), contentAlignment = Alignment.Center) { MIcon("mute", Palette.text, 14.dp) }
                        }
                        Text(name, style = Type.small.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        if (mutedByMe) Text("Muted by you", style = Type.small.copy(fontSize = 12.sp, color = Palette.danger), maxLines = 1)
                        if (p.state != "live") Text(if (p.state == "reconnecting") "Reconnecting" else "Connecting", style = Type.small.copy(fontSize = 12.sp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (!hideNames && r.people.size > 1) Text("Tap someone for their volume or to mute their voice for you", style = Type.small.copy(fontSize = 13.sp),
                textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
            Text("Up to ${RoomLogic.MAX_PEOPLE} people. Sound goes over the internet through Muninn's server, so nobody here sees anyone's internet address.",
                style = Type.small.copy(fontSize = 13.sp), textAlign = TextAlign.Center)
            Text("Voice rooms use more battery than chat. With the screen off, Muninn keeps only what the sound needs.",
                style = Type.small.copy(fontSize = 13.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
        val who = modFor
        if (who != null && app != null) PersonModDialog(app, r.g, who.key, who.name,
            { t, _ -> android.widget.Toast.makeText(roomCtx, t, android.widget.Toast.LENGTH_LONG).show() }) { modFor = null }
        // Wraps onto a second line when the four buttons don't fit (a small phone, large text or a long Bluetooth name).
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!r.listenOnly) MButton(if (r.muted) "Unmute mic" else "Mute mic", kind = if (r.muted) Kind.Accent else Kind.Normal, icon = "mic") { RoomManager.setMuted(!r.muted) }
            // Deafen: nobody heard here, mic off; others see you muted.
            MButton(if (r.deafened) "Undeafen" else "Deafen", kind = if (r.deafened) Kind.Accent else Kind.Normal, icon = if (r.deafened) "mute" else "sound") { RoomManager.setDeafened(!r.deafened) }
            // Where the sound goes: the phone, its speaker, headphones or a Bluetooth device.
            val out by AudioRoute.current.collectAsState()
            var picking by remember { mutableStateOf(false) }
            MButton(out?.name ?: "Audio", kind = if (out != null && out?.kind != AudioRoute.Kind.EARPIECE) Kind.Accent else Kind.Normal, icon = "audio") { picking = true }
            if (picking) AudioPicker(onDismiss = { picking = false }) { RoomManager.setOutput(it); picking = false }
            // Group video: up to 4 people.
            val vid by io.github.ravenor907.muninn.net.call.RoomVideo.state.collectAsState()
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val camAsk = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
                if (ok) io.github.ravenor907.muninn.net.call.RoomVideo.setCamera(true)
            }
            if (io.github.ravenor907.muninn.net.call.RoomVideo.allowed(r.people.size) || vid.cameraOn)
                MButton(if (vid.cameraOn) "Camera off" else "Camera", kind = if (vid.cameraOn) Kind.Accent else Kind.Normal, icon = "video") {
                    when {
                        vid.cameraOn -> io.github.ravenor907.muninn.net.call.RoomVideo.setCamera(false)
                        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                            io.github.ravenor907.muninn.net.call.RoomVideo.setCamera(true)
                        else -> camAsk.launch(android.Manifest.permission.CAMERA)
                    }
                }
            MButton("Leave", kind = Kind.Danger) { RoomManager.leave() }
        }
    }
}

@Composable
private fun status(r: RoomManager.Ui): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(r.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    if (r.connected == 0) return if (r.people.size > 1) "Connecting" else if (r.groupCall) "Ringing" else "Waiting for others"
    val s = ((now - r.since) / 1000).coerceAtLeast(0)
    val t = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    return "$t · ${r.connected + 1} people"
}

/** A person in the room: their picture or initial, ringed while they talk, faded while they connect. */
@Composable
private fun Face(key: String, name: String, speaking: Boolean, dim: Boolean, size: Int) {
    val ring = if (speaking) Modifier.border(3.dp, Palette.goldBright, CircleShape) else Modifier
    Box(Modifier.size((size + 8).dp).clip(CircleShape).then(ring).alpha(if (dim) 0.5f else 1f), contentAlignment = Alignment.Center) {
        io.github.ravenor907.muninn.ui.Avatar(key, name, size.dp)
    }
}

/** My camera in the group video grid, mirrored like a mirror; the camera runs only while this shows. */
@Composable
private fun RoomSelfView(modifier: Modifier) {
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = remember { androidx.camera.view.PreviewView(ctx).apply { implementationMode = androidx.camera.view.PreviewView.ImplementationMode.COMPATIBLE
        scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER } }
    androidx.compose.runtime.DisposableEffect(owner) {
        io.github.ravenor907.muninn.net.call.RoomVideo.bindCamera(owner, view.surfaceProvider)
        onDispose { io.github.ravenor907.muninn.net.call.RoomVideo.unbindCamera(owner) }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { view }, modifier = modifier.clip(io.github.ravenor907.muninn.ui.Corner).semantics { contentDescription = "Your camera" })
}
