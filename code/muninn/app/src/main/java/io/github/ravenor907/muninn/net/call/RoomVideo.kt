package io.github.ravenor907.muninn.net.call

import android.content.Context
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Group video in a voice room or group call, peer to peer only: up to [MAX_PEOPLE] people. The camera is encoded once
 * and each frame goes to every person in the room over that pair's own call (sealed with that pair's keys), but only
 * on Wi-Fi or the Direct path, never through the relay (CallMedia.videoNoRelay). Someone who can't be reached directly
 * stays on voice and sees "Video needs a direct connection". Each person's picture is decoded on its own thread.
 */
object RoomVideo {
    const val MAX_PEOPLE = 4
    /** Sent to up to 3 people at once from one phone: kept small. */
    private val PROFILE = VideoWire.Profile(640, 480, 15, 450)

    data class Pic(val width: Int = 0, val height: Int = 0, val rotation: Int = 0, val on: Boolean = false)
    data class State(val cameraOn: Boolean = false, val front: Boolean = true, val pics: Map<String, Pic> = emptyMap(), val note: String = "")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private fun update(f: (State) -> State) { _state.value = f(_state.value) }

    private val remotes = ConcurrentHashMap<String, RemoteVideo>()
    /** Who to send to now: peer key to that pair's media (RoomManager keeps it current). */
    @Volatile private var medias: Map<String, CallMedia> = emptyMap()
    @Volatile private var app: Context? = null
    @Volatile private var owner: LifecycleOwner? = null
    @Volatile private var preview: Preview.SurfaceProvider? = null
    private var provider: ProcessCameraProvider? = null
    private val cameraExec = Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-room-enc").apply { isDaemon = true } }
    @Volatile private var encoder: Vp8Encoder? = null
    @Volatile private var keyWanted = false
    @Volatile private var lastFrameAt = 0L

    /** The people in the room and their calls' media, whenever it changes. [people]: how many, me included. */
    fun peers(c: Context, now: Map<String, CallMedia>, people: Int) {
        app = c.applicationContext
        medias = now
        now.values.forEach { it.videoNoRelay = true }
        remotes.keys.filter { it !in now }.forEach { k -> remotes.remove(k)?.stop(); update { s -> s.copy(pics = s.pics - k) } }
        if (people > MAX_PEOPLE && _state.value.cameraOn) {
            setCamera(false)
            update { it.copy(note = "Video is for up to $MAX_PEOPLE people. More joined, so it's voice only.") }
        }
        if (_state.value.cameraOn) now.values.forEach { it.setVideoSending(VideoWire.CAMERA, true) }
    }

    fun allowed(people: Int) = people <= MAX_PEOPLE && Vp8.available

    /** A peer's camera frame (from that pair's media). */
    fun frame(peer: String, data: ByteArray, key: Boolean, rotation: Int) {
        remotes.getOrPut(peer) {
            RemoteVideo("muninn-room-dec", { medias[peer]?.requestKeyframe(VideoWire.CAMERA) }) { w, h, r ->
                update { s -> s.copy(pics = s.pics + (peer to Pic(w, h, r, true))) }
            }.also { it.start() }
        }.frame(data, key, rotation)
    }

    fun peerCamera(peer: String, on: Boolean) {
        update { s -> s.copy(pics = if (on) s.pics + (peer to (s.pics[peer] ?: Pic()).copy(on = true)) else s.pics - peer) }
        if (!on) remotes.remove(peer)?.stop()
    }

    fun keyWanted(peer: String) { if (peer in medias) keyWanted = true }

    fun surface(peer: String, sf: Surface?) {
        remotes.getOrPut(peer) {
            RemoteVideo("muninn-room-dec", { medias[peer]?.requestKeyframe(VideoWire.CAMERA) }) { w, h, r ->
                update { s -> s.copy(pics = s.pics + (peer to Pic(w, h, r, true))) }
            }.also { it.start() }
        }.surface(sf)
    }

    /** Left the room: everything stops. */
    fun reset() {
        setCamera(false)
        remotes.values.forEach { it.stop() }
        remotes.clear()
        medias = emptyMap()
        _state.value = State()
    }

    // ------------------------------------------------------------------ my camera

    fun setCamera(on: Boolean) {
        update { it.copy(cameraOn = on && Vp8.available, note = "") }
        medias.values.forEach { it.setVideoSending(VideoWire.CAMERA, on) }
        if (on) rebind() else stopCamera()
    }

    fun switchCamera() { update { it.copy(front = !it.front) }; if (_state.value.cameraOn) rebind() }

    fun bindCamera(o: LifecycleOwner, p: Preview.SurfaceProvider?) { owner = o; preview = p; if (_state.value.cameraOn) rebind() }

    fun unbindCamera(o: LifecycleOwner) { if (owner !== o) return; owner = null; preview = null; stopCamera() }

    private fun rebind() {
        val c = app ?: return
        val o = owner ?: return
        val f = ProcessCameraProvider.getInstance(c)
        f.addListener({
            val prov = try { f.get() } catch (e: Exception) { return@addListener }
            provider = prov
            if (owner !== o || !_state.value.cameraOn) return@addListener
            val sel = if (_state.value.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val res = ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(PROFILE.width, PROFILE.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val analysis = ImageAnalysis.Builder().setResolutionSelector(res).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
            analysis.setAnalyzer(cameraExec) { img -> try { onCameraFrame(img) } catch (_: Exception) {} finally { img.close() } }
            val pv = preview?.let { sp -> Preview.Builder().build().also { it.setSurfaceProvider(sp) } }
            try {
                prov.unbindAll()
                if (pv != null) prov.bindToLifecycle(o, sel, pv, analysis) else prov.bindToLifecycle(o, sel, analysis)
            } catch (e: Exception) { update { it.copy(cameraOn = false, note = "The camera isn't available.") } }
        }, ContextCompat.getMainExecutor(c))
    }

    private fun stopCamera() {
        val c = app
        provider?.let { p -> c?.let { ContextCompat.getMainExecutor(it).execute { try { p.unbindAll() } catch (_: Exception) {} } } }
        cameraExec.execute { encoder?.close(); encoder = null }
    }

    private fun onCameraFrame(img: ImageProxy) {
        if (!_state.value.cameraOn) return
        // Only people reachable on Wi-Fi or Direct get video; the rest only hear me.
        val to = medias.values.filter { it.c2 && it.freeFresh() }
        if (to.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastFrameAt < 1000L / PROFILE.fps - 3) return
        lastFrameAt = now
        val w = img.width and 1.inv(); val h = img.height and 1.inv()
        var e = encoder
        if (e == null || e.width != w || e.height != h) {
            e?.close()
            e = try { Vp8Encoder(w, h, PROFILE.fps, PROFILE.capKbps) } catch (x: Exception) { null } ?: return
            encoder = e
            keyWanted = true
        }
        if (keyWanted) { keyWanted = false; e.requestKey() }
        val rot = img.imageInfo.rotationDegrees
        e.encode(img) { bytes, key -> to.forEach { m -> m.sendVideo(VideoWire.CAMERA, key, rot, bytes) } }
    }
}
