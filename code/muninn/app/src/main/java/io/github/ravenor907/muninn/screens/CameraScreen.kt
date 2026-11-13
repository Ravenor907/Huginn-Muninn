package io.github.ravenor907.muninn.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.view.KeyEvent
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.net.Drafts
import io.github.ravenor907.muninn.net.More
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.net.UltraPrivacy
import io.github.ravenor907.muninn.net.VideoSend
import io.github.ravenor907.muninn.net.Videos
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** The camera's own colors (mockup, 2026-11-04): it stays dark whatever the theme, like the picture behind it. */
private val CamPanel = Color(0xFF0D1411)
private val CamText = Color(0xFFEFE7D6)
private val CamHint = Color(0xFFC9B48A)
private val CamButton = Color(0xFF1F2925)
private val CamRec = Color(0xFFE0533F)
private val CamTrack = Color(0xFF3A4540)

/**
 * The camera inside Muninn (owner, 2026-11-04): tap the shutter for a photo, hold it for a video of up to a minute
 * (a ring fills round the shutter, "0:12 / 1:00", "Recording. Let go to stop."). On a TV or a handheld, the same with
 * the select or A button: press for a photo, hold for a video. It replaces the old video message recorder.
 * Nothing is sent until the person sees what they took and taps Send; Discard throws it away.
 *
 *  - [target]: the chat the video goes to. Its video goes the video path (VideoSend, full size close by, else up to 5
 *    minutes at 720p), recorded at up to 1080p. Without one (an older caller), a held video goes the old way through
 *    [onVideo] as a small video message.
 *  - [onSaved]: the Saved media button beside the shutter (the camera closes and the picker opens).
 *  - [photoOnly]: no video at all (a profile picture). By default, a camera with no chat to send to.
 *  - [keep]: a chat's key in Drafts.camera, so a photo waiting for Send outlives the lock coming up, a fold or a
 *    recreation (in memory only); a video waiting for Send is kept as a file in Muninn's own storage. "" keeps nothing.
 *  - [video]: no longer used (one camera does both); kept so older callers still build.
 *
 * Camera permission is asked when it opens; the microphone only when a video is first held for.
 * Kids' devices keep their own photo button (k11, the parent's chatting level); this camera isn't on them.
 */
@Composable
fun CameraScreen(video: Boolean = false, keep: String = "", target: VideoSend.Target? = null, onSaved: (() -> Unit)? = null,
                 photoOnly: Boolean = keep.isEmpty() && target == null,
                 onPhoto: (ByteArray) -> Unit, onVideo: (ByteArray, Int) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        allowed = ok || granted(Manifest.permission.CAMERA)
        if (!allowed) { Ui.say("Photos and videos need the camera.", true); close() }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Ui.say(if (ok) "Now hold the shutter to record." else "Videos need the microphone.", !ok)
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(Manifest.permission.CAMERA) }
    var front by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
    // A video for [target] waiting for Send: a file under Muninn's own storage, found again after a fold or recreation.
    val sendDir = remember { File(context.filesDir, "camera-send").also { it.mkdirs() } }
    val pending = remember { File(sendDir, "pending-" + Integer.toHexString(keep.ifEmpty { "x" }.hashCode()) + ".mp4") }
    LaunchedEffect(Unit) {
        // Videos handed to the video path more than a day ago are long sent (or given up on).
        val old = System.currentTimeMillis() - 86_400_000L
        sendDir.listFiles()?.forEach { if (it != pending && it.lastModified() < old) it.delete() }
    }
    var clipFile by remember { mutableStateOf(if (target != null && pending.length() > 0) pending else null) }
    // What was taken, waiting for Send or Discard (and kept under [keep] while this chat's camera is open).
    val kept = remember { if (keep.isEmpty()) null else Drafts.camera[keep] }
    var photo by remember { mutableStateOf(kept?.photo) }
    var clip by remember { mutableStateOf(kept?.let { k -> k.clip?.let { c -> c to k.ms } }) }
    fun keepTaken(p: ByteArray?, c: Pair<ByteArray, Int>?) {
        if (keep.isEmpty()) return
        Drafts.camera[keep]?.let { Drafts.camera[keep] = it.with(p, c?.first, c?.second ?: 0) }
    }
    // Written back only when this screen changed it (taken, discarded): a new screen never writes its empty state over
    // a photo or video that finished after the old screen went (round 17).
    val written = remember { arrayOf<Any?>(kept?.photo, kept?.clip) }
    androidx.compose.runtime.SideEffect {
        if (photo !== written[0] || clip?.first !== written[1]) { keepTaken(photo, clip); written[0] = photo; written[1] = clip?.first }
    }
    // One that finished after this screen opened (the old screen's recording or photo, after a fold or a new window).
    val camRev by Drafts.cameraRev.collectAsState()
    LaunchedEffect(camRev) {
        if (recording != null || photo != null || clip != null || clipFile != null) return@LaunchedEffect
        if (target != null && pending.length() > 0) { clipFile = pending; return@LaunchedEffect }
        if (keep.isEmpty()) return@LaunchedEffect
        val k = Drafts.camera[keep] ?: return@LaunchedEffect
        k.clip?.let { c -> written[1] = c; clip = c to k.ms } ?: k.photo?.let { p -> written[0] = p; photo = p }
    }
    val taken = photo != null || clip != null || clipFile != null
    // The file being recorded: kept when the screen goes mid-recording, so the stopped video can still be kept.
    val recFile = remember { arrayOfNulls<File>(1) }
    var busy by remember { mutableStateOf(false) }
    val dir = remember { File(context.cacheDir, "camera").also { it.mkdirs() } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember {
        // For the video path: up to 1080p, which goes as it is to anyone close by. The old video message: small.
        val q = if (target != null) Quality.FHD else Quality.SD
        VideoCapture.withOutput(Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
            .setTargetVideoEncodingBitRate(if (target != null) FULL_BITRATE else Videos.VIDEO_BITRATE).build())
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val preview = remember { Preview.Builder().build() }
    // The bound camera, and whether photo and video are bound together (some phones can only do one at a time: then
    // holding switches to video for the recording and back afterwards).
    val cam = remember { arrayOfNulls<Camera>(1) }
    var both by remember { mutableStateOf(true) }
    var rebind by remember { mutableIntStateOf(0) }
    // Muninn turns with the phone without a new window, so the camera is told how the screen is turned: else photos and
    // videos taken after turning come out sideways. Set again on every turn and just before each shot.
    fun turnNow() {
        val r = previewView.display?.rotation ?: return
        imageCapture.targetRotation = r
        if (recording == null) videoCapture.targetRotation = r
    }
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    LaunchedEffect(orientation) { turnNow() }
    DisposableEffect(Unit) {
        // A turn of 180 degrees changes no orientation, so the display itself is watched too.
        val dm = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        val l = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
            override fun onDisplayChanged(id: Int) { turnNow() }
        }
        dm?.registerDisplayListener(l, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(l) }
    }
    DisposableEffect(Unit) { onDispose {
        val busyFile = if (recording != null) recFile[0] else null
        recording?.stop()
        try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (_: Exception) {}
        // The video still being finished is left for its own callback, which keeps it and deletes the file.
        dir.listFiles()?.forEach { if (it != busyFile) it.delete() }
    } }
    val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    /** Binds the preview with photo and video together, or (where the phone can't) with only photo or only video. */
    fun bind(videoOnly: Boolean): Boolean {
        val provider = try { ProcessCameraProvider.getInstance(context).get() } catch (e: Exception) { return false }
        preview.setSurfaceProvider(previewView.surfaceProvider)
        provider.unbindAll()
        if (!videoOnly && !photoOnly && both) {
            try { cam[0] = provider.bindToLifecycle(owner, selector, preview, imageCapture, videoCapture); return true }
            catch (e: Exception) { both = false; provider.unbindAll() }
        }
        return try {
            cam[0] = if (videoOnly) provider.bindToLifecycle(owner, selector, preview, videoCapture)
                else provider.bindToLifecycle(owner, selector, preview, imageCapture)
            true
        } catch (e: Exception) { false }
    }
    LaunchedEffect(front, allowed, taken, rebind) {
        if (!allowed || taken) return@LaunchedEffect
        try { ProcessCameraProvider.getInstance(context).get() } catch (e: Exception) { Ui.say("The camera isn't available.", true); close(); return@LaunchedEffect }
        if (!bind(false)) { if (front) front = false else { Ui.say("The camera isn't available.", true); close() } }
    }
    LaunchedEffect(recording) { while (recording != null) { elapsed = System.currentTimeMillis() - startedAt; delay(100) } }
    LaunchedEffect(flash, front) { imageCapture.flashMode = if (flash && !front) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }

    fun takePhoto() {
        if (busy || recording != null || taken) return
        busy = true
        turnNow()
        val f = File(dir, "photo-${System.nanoTime()}.jpg")
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    Thread {
                        // Shrunk and scrubbed like any photo: location and camera details never leave the phone.
                        val jpeg = Photos.prepare(context, Uri.fromFile(f), false)
                        f.delete()
                        ContextCompat.getMainExecutor(context).execute {
                            busy = false
                            if (jpeg == null) Ui.say("That photo didn't work. Try again.", true)
                            // Kept from here, not only by the screen: one that went meanwhile (a fold) still has it.
                            else { photo = jpeg; keepTaken(jpeg, null); Drafts.cameraRev.value++ }
                        }
                    }.start()
                }
                override fun onError(e: ImageCaptureException) { busy = false; Ui.say("That photo didn't work. Try again.", true) }
            })
    }

    @SuppressLint("MissingPermission")   // the microphone is checked just below; the camera before it opened
    fun startVideo() {
        if (photoOnly || busy || recording != null || taken) return
        // Without a chat to send to, the old video message, which goes through the relays: never under Ultra privacy.
        // With one, the video path decides (Tor or the same Wi-Fi under Ultra).
        if (target == null && UltraPrivacy.on) { Ui.say("Sending videos is off here while Ultra privacy is on.", true); return }
        if (!granted(Manifest.permission.RECORD_AUDIO)) { askMic.launch(Manifest.permission.RECORD_AUDIO); return }
        if (!both && !bind(true)) { Ui.say("The camera can't record video here.", true); rebind++; return }
        turnNow()
        val f = File(dir, "video-${System.nanoTime()}.mp4")
        recFile[0] = f
        val opts = FileOutputOptions.Builder(f).setDurationLimitMillis(Photos.Shutter.VIDEO_MAX_MS).apply {
            // The old video message must fit the relays like a photo: about 2 MB.
            if (target == null) setFileSizeLimit(Photos.INTERNET_PIECES.toLong() * Photos.PIECE - 64 * 1024)
        }.build()
        startedAt = System.currentTimeMillis(); elapsed = 0
        if (flash && !front) cam[0]?.cameraControl?.enableTorch(true)
        val t = target
        recording = videoCapture.output.prepareRecording(context, opts).withAudioEnabled().start(ContextCompat.getMainExecutor(context)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                try { cam[0]?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
                val ms = (ev.recordingStats.recordedDurationNanos / 1_000_000).toInt().coerceIn(1, More.VIDEO_MAX_MS)
                // Round 17: stopped by the camera going (screen off, Home, a minimised window) is kept too; its file is whole.
                val ok = !ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                    ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED || ev.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                if (!ok || !f.exists() || ms < 500) { f.delete(); Ui.say("That was too short to send. Hold the shutter to record.", true) }
                else if (t != null) {
                    // Kept as a file for Send (a minute of 1080p is too big to hold in memory).
                    pending.delete()
                    if (f.renameTo(pending) || (f.copyTo(pending, true).length() > 0 && f.delete())) { clipFile = pending; Drafts.cameraRev.value++ }
                    else Ui.say("That video couldn't be kept. Try again.", true)
                } else {
                    val bytes = f.readBytes()
                    f.delete()
                    if (More.isVideo(bytes)) { clip = bytes to ms; keepTaken(null, bytes to ms); Drafts.cameraRev.value++ }
                    else Ui.say("That video didn't work. Try again.", true)
                }
                if (!both) rebind++
            }
        }
    }
    fun stopVideo() { recording?.stop() }

    BackHandler {
        when {
            recording != null -> recording?.stop()
            photo != null -> photo = null
            clip != null -> clip = null
            clipFile != null -> { pending.delete(); clipFile = null }
            else -> close()
        }
    }
    // Taken: look at it first, then Send or Discard.
    photo?.let { p ->
        val bmp = remember(p) { decode(p, 1600) }
        // Send and Discard sit at the top: a full-screen sheet can run past the bottom of some phones' screens.
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MButton("Discard", Modifier.weight(1f), Kind.Danger, icon = "delete") { photo = null }
                MButton("Send", Modifier.weight(1f), Kind.Accent, icon = "send") { onPhoto(p); close() }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it, contentDescription = "The photo you took", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        return
    }
    clipFile?.let { f ->
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MButton("Discard", Modifier.weight(1f), Kind.Danger, icon = "delete") { pending.delete(); clipFile = null }
                MButton("Send", Modifier.weight(1f), Kind.Accent, icon = "send") {
                    val t = target
                    val out = File(sendDir, "video-${System.nanoTime()}.mp4")
                    if (t != null && f.renameTo(out)) { SavedMedia.queueVideos(t, listOf(Uri.fromFile(out))); close() }
                    else Ui.say("That video couldn't be sent. Try again.", true)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(f.path)
                        setOnPreparedListener { mp -> mp.isLooping = true; start() }
                        setOnErrorListener { _, _, _ -> true }
                    }
                }, onRelease = { it.stopPlayback() }, modifier = Modifier.fillMaxSize().semantics { contentDescription = "The video you took" })
            }
        }
        return
    }
    clip?.let { (bytes, ms) ->
        Box(Modifier.fillMaxSize()) {
            VideoPlayer(bytes, { clip = null }) {
                MButton("Discard", kind = Kind.Danger, icon = "delete") { clip = null }
                MButton("Send", kind = Kind.Accent, icon = "send") { onVideo(bytes, ms); close() }
            }
        }
        return
    }

    val rec = recording != null
    // The press itself: a tap takes a photo, a hold records until let go. Read through these so the gesture, set up
    // once, always runs the current ones.
    val onTap by rememberUpdatedState({ takePhoto() })
    val onHold by rememberUpdatedState({ startVideo() })
    val onLetGo by rememberUpdatedState({ stopVideo() })
    val shutterFocus = remember { FocusRequester() }
    LaunchedEffect(allowed) { if (allowed) try { shutterFocus.requestFocus() } catch (_: Exception) {} }
    // The select, Enter or A button: pressed and let go soon is a photo; held is a video until it's let go.
    val keyHold = remember { arrayOfNulls<Job>(1) }
    val keyHeld = remember { booleanArrayOf(false) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (allowed) AndroidView({ previewView }, Modifier.fillMaxSize())
            if (rec) Row(Modifier.align(Alignment.TopCenter).padding(top = 18.dp).clip(RoundedCornerShape(20.dp)).background(CamPanel)
                .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(CamRec))
                Spacer(Modifier.width(8.dp))
                Text(Photos.Shutter.clock(elapsed), style = Type.big.copy(color = CamText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            } else Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(44.dp, CamPanel, "Close", CircleShape) { close() }.also { }
                Spacer(Modifier.weight(1f))
                if (!front && cam[0]?.cameraInfo?.hasFlashUnit() != false)
                    RoundButton(44.dp, if (flash) CamHint else CamPanel, if (flash) "Flash on" else "Flash off", CircleShape, icon = "flash", tint = if (flash) CamPanel else CamText) { flash = !flash }
            }
        }
        Column(Modifier.fillMaxWidth().height(200.dp).background(CamPanel).padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (rec) 10.dp else 14.dp)) {
            Text(when { rec -> Photos.Shutter.RECORDING; photoOnly -> "Tap for a photo"; else -> Photos.Shutter.HINT },
                style = Type.normal.copy(color = CamHint, fontSize = 15.sp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                if (onSaved != null && !rec) RoundButton(48.dp, Color(0xFF3B4A3F), "Saved media", RoundedCornerShape(10.dp), icon = "image") { close(); onSaved() }
                else Spacer(Modifier.size(48.dp))
                // The shutter: a ring that fills while recording, a red square inside.
                val shutter = if (rec) 104.dp else 84.dp
                val label = if (rec) "Recording: let go to stop" else if (photoOnly) "Shutter: tap for a photo" else "Shutter: tap for a photo, hold for video"
                Box(Modifier.size(shutter).clip(CircleShape)
                    .focusRequester(shutterFocus)
                    .onKeyEvent { e ->
                        val k = e.nativeKeyEvent.keyCode
                        if (k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER && k != KeyEvent.KEYCODE_NUMPAD_ENTER &&
                            k != KeyEvent.KEYCODE_BUTTON_A && k != KeyEvent.KEYCODE_SPACE) return@onKeyEvent false
                        when (e.type) {
                            KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount == 0) {
                                keyHeld[0] = false
                                keyHold[0]?.cancel()
                                // A camera only for photos (a profile picture) takes one however long it's held.
                                if (!photoOnly) keyHold[0] = scope.launch { delay(Photos.Shutter.HOLD_MS); keyHeld[0] = true; onHold() }
                            }
                            KeyEventType.KeyUp -> { keyHold[0]?.cancel(); if (keyHeld[0]) onLetGo() else onTap(); keyHeld[0] = false }
                        }
                        true
                    }
                    .focusable()
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            var held = false
                            val hold = if (photoOnly) null else scope.launch { delay(Photos.Shutter.HOLD_MS); held = true; onHold() }
                            tryAwaitRelease()
                            hold?.cancel()
                            if (held) onLetGo() else onTap()
                        })
                    }
                    .semantics { contentDescription = label; role = Role.Button; onClick("Take a photo") { onTap(); true } },
                    contentAlignment = Alignment.Center) {
                    if (rec) {
                        val fill = Photos.Shutter.ring(elapsed)
                        Canvas(Modifier.fillMaxSize()) {
                            val w = 6.dp.toPx()
                            val inset = w / 2 + 2.dp.toPx()
                            val arc = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                            drawArc(CamTrack, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
                            drawArc(CamRec, -90f, 360f * fill, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
                        }
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(CamRec))
                    } else Box(Modifier.fillMaxSize().border(4.dp, CamText, CircleShape).padding(9.dp).clip(CircleShape).background(CamText))
                }
                if (!rec) RoundButton(48.dp, CamButton, if (front) "Use the back camera" else "Use the front camera", CircleShape, icon = "refresh") { front = !front }
                else Spacer(Modifier.size(48.dp))
            }
        }
    }
}

/** A typical phone's own 1080p30 bitrate is far more; 8 Mbit/s keeps a minute near 60 MB and still looks the same. */
private const val FULL_BITRATE = 8_000_000

/** A round (or rounded) button over the camera. [icon] "flash" is drawn here; the rest are Muninn's own icons. */
@Composable
private fun RoundButton(size: Dp, bg: Color, label: String, shape: androidx.compose.ui.graphics.Shape, icon: String = "close",
                        tint: Color = CamText, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(shape).background(bg).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        if (icon == "flash") {
            val bolt = remember { PathParser().parsePathString("M13 3L5 14h6l-1 7 8-11h-6z").toPath() }
            Canvas(Modifier.size(22.dp)) { scale(this.size.width / 24f, pivot = Offset.Zero) { drawPath(bolt, tint, style = Stroke(2.dp.toPx() * 24f / this.size.width)) } }
        } else MIcon(icon, tint, 22.dp)
    }
}
