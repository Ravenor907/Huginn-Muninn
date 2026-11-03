package io.github.ravenor907.muninn.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.net.Drafts
import io.github.ravenor907.muninn.net.More
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.net.Videos
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.delay
import java.io.File

/**
 * The camera inside Muninn: a photo, or a video message of up to a minute ([video]). The front and back cameras
 * switch with one button. Nothing is sent until the person sees what they took and taps Send; Discard throws it away.
 * Pictures and videos are made in the app's own cache and deleted once sent or thrown away.
 * [keep]: a chat's key in Drafts.camera, so a photo or video waiting for Send (and one being recorded, stopped and kept)
 * outlives the lock coming up, a fold or a recreation, in memory only. "" keeps nothing.
 */
@Composable
fun CameraScreen(video: Boolean, keep: String = "", onPhoto: (ByteArray) -> Unit, onVideo: (ByteArray, Int) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted(Manifest.permission.CAMERA) && (!video || granted(Manifest.permission.RECORD_AUDIO))) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        allowed = r.values.all { it } || (granted(Manifest.permission.CAMERA) && (!video || granted(Manifest.permission.RECORD_AUDIO)))
        if (!allowed) { Ui.say(if (video) "Video messages need the camera and the microphone." else "Photos need the camera.", true); close() }
    }
    LaunchedEffect(Unit) { if (!allowed) ask.launch(if (video) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) else arrayOf(Manifest.permission.CAMERA)) }
    var front by remember { mutableStateOf(video) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableLongStateOf(0L) }
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
        if (keep.isEmpty() || recording != null || photo != null || clip != null) return@LaunchedEffect
        val k = Drafts.camera[keep] ?: return@LaunchedEffect
        k.clip?.let { c -> written[1] = c; clip = c to k.ms } ?: k.photo?.let { p -> written[0] = p; photo = p }
    }
    // The file being recorded: kept when the screen goes mid-recording, so the stopped video can still be kept.
    val recFile = remember { arrayOfNulls<File>(1) }
    var busy by remember { mutableStateOf(false) }
    val dir = remember { File(context.cacheDir, "camera").also { it.mkdirs() } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val videoCapture = remember {
        VideoCapture.withOutput(Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .setTargetVideoEncodingBitRate(Videos.VIDEO_BITRATE).build())
    }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
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
    // (Re)binds the camera for the chosen side: the preview plus a photo or a video use, never both (some phones can't).
    LaunchedEffect(front, allowed, photo, clip) {
        if (!allowed || photo != null || clip != null) return@LaunchedEffect
        val provider = try { ProcessCameraProvider.getInstance(context).get() } catch (e: Exception) { Ui.say("The camera isn't available.", true); close(); return@LaunchedEffect }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            if (video) provider.bindToLifecycle(owner, selector, preview, videoCapture) else provider.bindToLifecycle(owner, selector, preview, imageCapture)
        } catch (e: Exception) {
            if (front) { front = false } else { Ui.say("The camera isn't available.", true); close() }
        }
    }
    LaunchedEffect(recording) { while (recording != null) { elapsed = System.currentTimeMillis() - startedAt; delay(200) } }

    fun takePhoto() {
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

    @SuppressLint("MissingPermission")   // asked for above, before the camera opens
    fun startVideo() {
        if (!granted(Manifest.permission.RECORD_AUDIO)) { ask.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return }
        turnNow()
        val f = File(dir, "video-${System.nanoTime()}.mp4")
        recFile[0] = f
        // Small enough to go through the relays like a photo: about 2 MB, a minute at most.
        val opts = FileOutputOptions.Builder(f).setFileSizeLimit(Photos.INTERNET_PIECES.toLong() * Photos.PIECE - 64 * 1024)
            .setDurationLimitMillis(More.VIDEO_MAX_MS.toLong()).build()
        startedAt = System.currentTimeMillis(); elapsed = 0
        recording = videoCapture.output.prepareRecording(context, opts).withAudioEnabled().start(ContextCompat.getMainExecutor(context)) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                val ms = (ev.recordingStats.recordedDurationNanos / 1_000_000).toInt().coerceIn(1, More.VIDEO_MAX_MS)
                // Round 17: stopped by the camera going (screen off, Home, a minimised window) is kept too; its file is whole.
                val ok = !ev.hasError() || ev.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                    ev.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED || ev.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                val bytes = if (ok && f.exists()) f.readBytes() else null
                f.delete()
                if (bytes != null && More.isVideo(bytes) && ms >= 500) { clip = bytes to ms; keepTaken(null, bytes to ms); Drafts.cameraRev.value++ }
                else Ui.say("That was too short to send. Tap to start, tap again to stop.", true)
            }
        }
    }

    BackHandler { if (photo != null) photo = null else if (clip != null) clip = null else close() }
    // Taken: look at it first, then Send or Discard.
    photo?.let { p ->
        val bmp = remember(p) { decode(p, 1600) }
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                bmp?.let { Image(it, contentDescription = "The photo you took", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MButton("Discard", Modifier.weight(1f), Kind.Danger, icon = "delete") { photo = null }
                MButton("Send", Modifier.weight(1f), Kind.Accent, icon = "send") { onPhoto(p); close() }
            }
        }
        return
    }
    clip?.let { (bytes, ms) ->
        Box(Modifier.fillMaxSize()) {
            VideoPlayer(bytes, { clip = null }) {}
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 72.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MButton("Discard", Modifier.weight(1f), Kind.Danger, icon = "delete") { clip = null }
                MButton("Send", Modifier.weight(1f), Kind.Accent, icon = "send") { onVideo(bytes, ms); close() }
            }
        }
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) AndroidView({ previewView }, Modifier.fillMaxSize())
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MButton("Close", icon = "close") { recording?.stop(); close() }
            Spacer(Modifier.weight(1f))
            if (recording != null) Text("● " + io.github.ravenor907.muninn.net.Voice.length(elapsed.toInt()) + " / 1:00",
                style = Type.normal.copy(color = Color(0xFFFF8A80)))
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 32.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(56.dp))
            // The shutter: a photo, or start and stop a video message.
            val label = when { !video -> "Take photo"; recording != null -> "Stop recording"; else -> "Start recording" }
            Box(Modifier.size(76.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(6.dp).clip(CircleShape)
                .background(if (video) Color(0xFFE5534B) else Color.White)
                .clickable(enabled = !busy, role = Role.Button) {
                    when { !video -> takePhoto(); recording != null -> recording?.stop(); else -> startVideo() }
                }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                if (recording != null) Box(Modifier.size(26.dp).background(Color.White))
            }
            // Front or back camera.
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0x66000000))
                .clickable(enabled = recording == null, role = Role.Button) { front = !front }
                .semantics { contentDescription = if (front) "Use the back camera" else "Use the front camera" }, contentAlignment = Alignment.Center) {
                MIcon("refresh", Color.White, 26.dp)
            }
        }
        if (!video) Text("Tap to take a photo. You see it before it's sent.", style = Type.small.copy(color = Color.White),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        else if (recording == null) Text("Tap to record up to a minute, tap again to stop. You see it before it's sent.", style = Type.small.copy(color = Color.White),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}
