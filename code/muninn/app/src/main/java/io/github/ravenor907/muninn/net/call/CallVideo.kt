package io.github.ravenor907.muninn.net.call

import android.content.Context
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
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
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * VP8 with MediaCodec (shared/docs/protocol.md 5.8.5): Android has a VP8 encoder and decoder on every phone (the
 * software ones, c2.android.vp8.*, when there is no hardware one), and Huginn uses libvpx: the same bitstream.
 */
object Vp8 {
    const val MIME = MediaFormat.MIMETYPE_VIDEO_VP8

    /** True when this phone can both encode and decode VP8. */
    val available: Boolean by lazy {
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            list.any { it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } } && list.any { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MIME, true) } }
        } catch (e: Throwable) { false }
    }

    /** A hardware codec (Android 10 says so; before that, Android's own software codecs go by their names). */
    fun isHardware(i: MediaCodecInfo): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 29) return i.isHardwareAccelerated && !i.isSoftwareOnly
        val n = i.name.lowercase()
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") || n.contains(".sw."))
    }

    /** The size a VP8 keyframe says (RFC 6386 9.1), or null when [data] isn't a keyframe. */
    fun keyframeSize(data: ByteArray): Size? {
        if (data.size < 10 || (data[0].toInt() and 1) != 0) return null
        if ((data[3].toInt() and 0xFF) != 0x9D || (data[4].toInt() and 0xFF) != 0x01 || (data[5].toInt() and 0xFF) != 0x2A) return null
        val w = ((data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)) and 0x3FFF
        val h = ((data[8].toInt() and 0xFF) or ((data[9].toInt() and 0xFF) shl 8)) and 0x3FFF
        return if (w > 0 && h > 0) Size(w, h) else null
    }

    /** A matrix for a TextureView of [vw] x [vh] showing a [w] x [h] picture turned [rotation] degrees, fitted whole and centered. */
    fun fit(vw: Float, vh: Float, w: Int, h: Int, rotation: Int, fill: Boolean = false): Matrix {
        val m = Matrix()
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return m
        val turned = rotation % 180 != 0
        val rw = if (turned) h.toFloat() else w.toFloat()
        val rh = if (turned) w.toFloat() else h.toFloat()
        val k = if (fill) maxOf(vw / rw, vh / rh) else minOf(vw / rw, vh / rh)
        // The TextureView first stretches the picture over the whole view: undo that, turn it, scale it.
        m.postScale(w / vw, h / vh, vw / 2, vh / 2)
        m.postRotate(rotation.toFloat(), vw / 2, vh / 2)
        m.postScale(k, k, vw / 2, vh / 2)
        return m
    }
}

/** VP8 encoder for YUV frames from the camera. Runs on the caller's thread (one thread at a time). */
class Vp8Encoder(val width: Int, val height: Int, fps: Int, kbps: Int) : AutoCloseable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(Vp8.MIME)
    private val info = MediaCodec.BufferInfo()
    private var kbpsNow = kbps
    private var started = System.nanoTime()

    init {
        fun format(cbr: Boolean) = MediaFormat.createVideoFormat(Vp8.MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            if (cbr) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        try { codec.configure(format(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        catch (e: Exception) { codec.reset(); codec.configure(format(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        codec.start()
    }

    fun setKbps(k: Int) {
        if (k == kbpsNow) return
        kbpsNow = k
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, k * 1000) }) } catch (_: Exception) {}
    }

    fun requestKey() { try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Exception) {} }

    /** One camera frame in; whatever the encoder has ready comes out through [out] (VP8 bytes, keyframe). */
    fun encode(img: ImageProxy, out: (ByteArray, Boolean) -> Unit) {
        val i = codec.dequeueInputBuffer(10_000)
        if (i >= 0) {
            val dst = codec.getInputImage(i)
            if (dst != null) {
                for (p in 0..2) {
                    val s = img.planes[p]; val d = dst.planes[p]
                    val w = if (p == 0) width else width / 2
                    val h = if (p == 0) height else height / 2
                    copyPlane(s.buffer, s.rowStride, s.pixelStride, d.buffer, d.rowStride, d.pixelStride, w, h)
                }
            } else {
                // No Image view of the input: plain I420 into the buffer.
                val b = codec.getInputBuffer(i)!!
                b.clear()
                for (p in 0..2) {
                    val s = img.planes[p]
                    val w = if (p == 0) width else width / 2
                    val h = if (p == 0) height else height / 2
                    val src = s.buffer
                    for (y in 0 until h) for (x in 0 until w) b.put(src.get(y * s.rowStride + x * s.pixelStride))
                }
            }
            codec.queueInputBuffer(i, 0, width * height * 3 / 2, (System.nanoTime() - started) / 1000, 0)
        }
        drain(out)
    }

    private fun copyPlane(src: java.nio.ByteBuffer, srs: Int, sps: Int, dst: java.nio.ByteBuffer, drs: Int, dps: Int, w: Int, h: Int) {
        if (sps == 1 && dps == 1) {
            val row = ByteArray(w)
            for (y in 0 until h) {
                src.position(y * srs); src.get(row, 0, minOf(w, src.remaining()))
                dst.position(y * drs); dst.put(row, 0, minOf(w, dst.remaining()))
            }
            return
        }
        for (y in 0 until h) {
            val so = y * srs; val dO = y * drs
            for (x in 0 until w) {
                val si = so + x * sps; val di = dO + x * dps
                if (si < src.limit() && di < dst.limit()) dst.put(di, src.get(si))
            }
        }
    }

    private fun drain(out: (ByteArray, Boolean) -> Unit) {
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            if (o < 0) return
            val buf = codec.getOutputBuffer(o)
            if (buf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val b = ByteArray(info.size)
                buf.position(info.offset); buf.get(b)
                out(b, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0)
            }
            codec.releaseOutputBuffer(o, false)
        }
    }

    override fun close() { try { codec.stop() } catch (_: Exception) {}; try { codec.release() } catch (_: Exception) {} }
}

/**
 * VP8 decoder onto a Surface (a TextureView's): the decoder renders straight to it, nothing is copied by hand.
 * Started at the first keyframe; a keyframe of another size starts it again. The device's hardware VP8 decoder
 * is tried first (a TV box's CPU is too weak for the software one at 720p), then the software one.
 */
class Vp8Decoder(private val surface: Surface) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var size: Size? = null
    private val info = MediaCodec.BufferInfo()
    private var pts = 0L
    @Volatile private var lastInputAt = 0L

    /** The decoder in use is the device's hardware one. */
    var hardware = false; private set

    /** Fed a frame within the last 100 ms: its picture may still come out (the caller drains sooner). */
    val busy: Boolean get() = codec != null && System.currentTimeMillis() - lastInputAt < 100

    /** False when it couldn't take [data] (no keyframe yet): the caller asks for one. */
    fun decode(data: ByteArray, key: Boolean): Boolean {
        val ks = if (key) Vp8.keyframeSize(data) else null
        if (ks != null && ks != size) {
            close()
            val c = open(ks) ?: return false
            codec = c; size = ks
        }
        val c = codec ?: return false
        return try {
            // A busy decoder gets a little time (its output drained meanwhile); skipping a frame would break the ones
            // after it, so if it stays busy the caller asks for a keyframe instead.
            var i = -1
            for (tries in 0 until 5) {
                i = c.dequeueInputBuffer(20_000)
                if (i >= 0) break
                drain(c)
            }
            if (i < 0) return false
            val b = c.getInputBuffer(i)!!
            b.clear()
            if (data.size > b.capacity()) { c.queueInputBuffer(i, 0, 0, pts, 0); return false }
            b.put(data)
            c.queueInputBuffer(i, 0, data.size, pts, 0)
            pts += 33_000
            lastInputAt = System.currentTimeMillis()
            drain(c)
            true
        } catch (e: Exception) { close(); false }
    }

    /** Shows what the decoder has finished since the last call (the worker calls this while it waits for frames). */
    fun drain() { val c = codec ?: return; try { drain(c) } catch (e: Exception) { close() } }

    /** Only the newest finished picture goes on screen; older ones still waiting are late and are let go unshown. */
    private fun drain(c: MediaCodec) {
        var last = -1
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (o < 0) continue   // format or buffers changed: nothing to show, ask again
            if (last >= 0) c.releaseOutputBuffer(last, false)
            last = o
        }
        if (last >= 0) c.releaseOutputBuffer(last, true)
    }

    /** A started decoder for [s]: hardware ones first, each tried with low-latency hints and then without. */
    private fun open(s: Size): MediaCodec? {
        for ((name, hw) in candidates(s)) {
            for (hints in listOf(true, false)) {
                val c = try { MediaCodec.createByCodecName(name) } catch (e: Exception) { null } ?: break
                try {
                    c.configure(format(s, hints), surface, null, 0); c.start()
                    hardware = hw
                    return c
                } catch (e: Exception) { try { c.release() } catch (_: Exception) {} }
            }
        }
        // Nothing in the list would start: whatever Android picks, as before.
        val c = try { MediaCodec.createDecoderByType(Vp8.MIME) } catch (e: Exception) { return null }
        return try { c.configure(MediaFormat.createVideoFormat(Vp8.MIME, s.width, s.height), surface, null, 0); c.start(); hardware = false; c }
        catch (e: Exception) { try { c.release() } catch (_: Exception) {}; null }
    }

    private fun format(s: Size, hints: Boolean) = MediaFormat.createVideoFormat(Vp8.MIME, s.width, s.height).apply {
        if (!hints) return@apply
        // Room for a big keyframe, real-time priority, and no frames held back where the decoder can do that.
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(s.width * s.height, 256 * 1024))
        setInteger(MediaFormat.KEY_PRIORITY, 0)
        if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }

    /** The VP8 decoders that take [s] (name, hardware), hardware first; secure-only ones left out. */
    private fun candidates(s: Size): List<Pair<String, Boolean>> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(Vp8.MIME, true) } && !it.name.endsWith(".secure") }
            .filter { i ->
                try { i.getCapabilitiesForType(Vp8.MIME).videoCapabilities?.isSizeSupported(s.width, s.height) != false } catch (e: Exception) { true }
            }
            .map { i -> i.name to Vp8.isHardware(i) }
            .sortedBy { if (it.second) 0 else 1 }
    } catch (e: Exception) { emptyList() }

    val videoSize: Size? get() = size

    override fun close() {
        codec?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        codec = null; size = null
    }
}

/**
 * Someone's VP8 picture (their camera, or a screen they share) decoded onto a Surface on its own thread. Frames
 * that come while there is no surface are dropped; a new surface asks for a keyframe ([askKey]).
 */
class RemoteVideo(private val thread: String, private val askKey: () -> Unit, private val onSize: (Int, Int, Int) -> Unit) {
    private class Item(val data: ByteArray, val key: Boolean, val rotation: Int)
    private val queue = LinkedBlockingDeque<Item>()
    private var worker: Thread? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var changed = false
    private var w = 0; private var h = 0; private var r = 0

    private val lock = Object()
    /** The surface the decoder renders to right now (null when it has none). */
    private var inUse: Surface? = null

    /**
     * The screen's surface, or null when it goes away. With null this waits (at most 1 s) until the decoder has let go
     * of the old surface, so the caller can release it safely.
     */
    fun surface(sf: Surface?) {
        synchronized(lock) {
            surface = sf
            changed = true
            if (sf == null) {
                val until = System.currentTimeMillis() + 1_000
                while (inUse != null && worker != null && System.currentTimeMillis() < until) lock.wait(20)
            }
        }
        if (sf != null) askKey()
    }

    fun frame(data: ByteArray, key: Boolean, rotation: Int) {
        if (worker == null) start()
        if (queue.size >= MAX_QUEUE) { queue.clear(); askKey() }
        queue.offerLast(Item(data, key, rotation))
    }

    /**
     * Frames are decoded in order on this thread (never the screen's). When the decoder falls [BEHIND] frames behind,
     * the late ones are dropped rather than shown late: it jumps to the newest keyframe waiting, or, with none,
     * drops them all and waits for the keyframe it asks for (VP8 frames build on the ones before, so a gap needs one).
     * Those keyframe requests are also what tells the sender to send something lighter (CallMedia).
     */
    @Synchronized fun start() {
        if (worker != null) return
        worker = Thread({
            try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) } catch (_: Exception) {}
            var dec: Vp8Decoder? = null
            var decSurface: Surface? = null
            var waitKey = false
            try {
                while (!Thread.currentThread().isInterrupted) {
                    // Short polls, so a surface that goes away is let go of quickly; shorter while a picture is on its way.
                    var next = queue.pollFirst(if (dec?.busy == true) 4L else 20L, TimeUnit.MILLISECONDS)
                    synchronized(lock) {
                        val now = surface
                        if (changed || now !== decSurface) { changed = false; dec?.close(); dec = null; decSurface = now; inUse = null; lock.notifyAll() }
                    }
                    if (next == null) {
                        // Nothing new: show what the decoder finished meanwhile, not only when the next frame comes.
                        val d = dec
                        if (d != null && synchronized(lock) { surface === decSurface }) d.drain()
                        continue
                    }
                    if (queue.size >= BEHIND) {
                        val all = ArrayList<Item>(queue.size + 1).apply { add(next!!) }
                        queue.drainTo(all)
                        val k = all.indexOfLast { it.key }
                        if (k >= 0) {
                            next = all[k]
                            for (i in all.size - 1 downTo k + 1) queue.offerFirst(all[i])
                        } else { waitKey = true; askKey(); continue }
                    }
                    val item = next!!
                    if (waitKey && !item.key) { askKey(); continue }
                    val sf = decSurface ?: continue
                    val ok = synchronized(lock) { if (surface === sf) { inUse = sf; true } else { inUse = null; lock.notifyAll(); false } }
                    if (!ok) continue
                    val d = dec ?: Vp8Decoder(sf).also { dec = it }
                    if (!d.decode(item.data, item.key)) { waitKey = true; askKey(); continue }
                    waitKey = false
                    val sz = d.videoSize ?: continue
                    if (sz.width != w || sz.height != h || item.rotation != r) { w = sz.width; h = sz.height; r = item.rotation; onSize(w, h, r) }
                }
            } catch (_: InterruptedException) {
            } finally { dec?.close(); synchronized(lock) { inUse = null; lock.notifyAll() } }
        }, thread).apply { isDaemon = true; start() }
    }

    private companion object {
        /** About a quarter of a second at 30 frames a second: later than this, frames are dropped, not queued. */
        const val BEHIND = 8
        const val MAX_QUEUE = 60
    }

    @Synchronized fun stop() {
        worker?.interrupt(); worker = null
        queue.clear()
        w = 0; h = 0; r = 0
    }
}

/**
 * The video of the 1:1 call (5.8.5, 5.8.6): the camera (CameraX) encoded with [Vp8Encoder] and sent through the
 * call's media, which seals it with the call's keys; the peer's frames decoded onto the screen's surface. The call
 * screen binds the camera while it is visible (Android doesn't let an app film in the background), so the
 * picture pauses when Muninn goes to the background and comes back with it.
 */
object CallVideo {
    data class State(
        /** Video can go both ways in this call (both c2 and VP8). */
        val possible: Boolean = false,
        val cameraOn: Boolean = false,
        val front: Boolean = true,
        /** The peer's camera is on (its reports say so). */
        val peerCamera: Boolean = false,
        /** The peer's picture: its size and the turn that shows it upright. */
        val width: Int = 0, val height: Int = 0, val rotation: Int = 0,
        /** The size we send (480p over the relay, 720p on a free path). */
        val sendHeight: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var session: CallSession? = null
    @Volatile private var app: Context? = null
    private val remote = RemoteVideo("muninn-video-dec", { session?.mediaNow?.requestKeyframe(VideoWire.CAMERA) }) { w, h, r ->
        update { it.copy(width = w, height = h, rotation = r) }
    }
    private val cameraExec = Executors.newSingleThreadExecutor { r -> Thread(r, "muninn-video-enc").apply { isDaemon = true } }
    @Volatile private var encoder: Vp8Encoder? = null
    @Volatile private var lastFrameAt = 0L
    @Volatile private var keyWanted = false
    @Volatile private var lastRate = 0L
    @Volatile private var owner: LifecycleOwner? = null
    @Volatile private var preview: Preview.SurfaceProvider? = null
    private var provider: ProcessCameraProvider? = null
    private var flipped = false
    @Volatile private var readyFor: CallSession? = null

    private fun update(f: (State) -> State) { _state.value = f(_state.value) }

    /** A new 1:1 call (or none). [wantCamera]: start with the camera on (a video call). */
    fun attach(c: Context, s: CallSession?, wantCamera: Boolean) {
        app = c.applicationContext
        if (session === s) return
        detach()
        session = s
        if (s != null) update { State(possible = false, cameraOn = wantCamera && cameraHere()) }
    }

    /** This device may film: VP8, and not a Kids' TV (a TV shows the caller's camera but never sends its own). */
    private fun cameraHere() = CallLogic.sendsCamera(Vp8.available, io.github.ravenor907.muninn.tv.TvMic.active)

    /** The session's media exists and both sides said what they can (call this when the call connects). */
    fun ready(s: CallSession) {
        if (s !== session || readyFor === s) return
        val m = s.mediaNow ?: return
        readyFor = s
        val possible = s.videoBoth
        update { it.copy(possible = possible, cameraOn = it.cameraOn && possible && cameraHere(), peerCamera = m.peerSending(VideoWire.CAMERA)) }
        if (possible && _state.value.cameraOn) { m.setVideoSending(VideoWire.CAMERA, true); rebind() }
        if (possible) remote.start()
    }

    /** Before the call connects: start with the camera on (answering a video call with video). */
    fun want(s: CallSession, on: Boolean) { if (s === session) update { it.copy(cameraOn = on && cameraHere()) } }

    fun detach() {
        session?.mediaNow?.setVideoSending(VideoWire.CAMERA, false)
        session = null
        readyFor = null
        flipped = false
        stopCamera()
        remote.stop()
        _state.value = State()
    }

    // ------------------------------------------------------------------ from the call's media (any thread)

    fun onFrame(s: CallSession, stream: Int, data: ByteArray, key: Boolean, rotation: Int) {
        if (s !== session || stream != VideoWire.CAMERA) return
        remote.frame(data, key, rotation)
    }

    fun onKeyWanted(s: CallSession, stream: Int) { if (s === session && stream == VideoWire.CAMERA) keyWanted = true }

    fun onPeerVideo(s: CallSession, camera: Boolean) { if (s === session) update { it.copy(peerCamera = camera) } }

    /** A free path came or went: the camera may send another size (5.8.6). */
    fun onProfile(s: CallSession) { if (s === session && _state.value.cameraOn) rebind() }

    // ------------------------------------------------------------------ the peer's picture

    /** The call screen's TextureView surface, or null when it goes away. */
    fun remoteSurface(sf: Surface?) = remote.surface(sf)

    // ------------------------------------------------------------------ the camera

    fun setCamera(on: Boolean) {
        val s = session ?: return
        if (on && (!_state.value.possible || !cameraHere())) return
        update { it.copy(cameraOn = on) }
        s.mediaNow?.setVideoSending(VideoWire.CAMERA, on)
        if (on) s.mediaNow?.videoStarted()
        if (on) rebind() else stopCamera()
    }

    fun switchCamera() {
        update { it.copy(front = !it.front) }
        if (_state.value.cameraOn) rebind()
    }

    /** The call screen is showing: the camera may run, with [p] as the self view (picture in picture). */
    fun bindCamera(o: LifecycleOwner, p: Preview.SurfaceProvider?) {
        owner = o; preview = p
        if (_state.value.cameraOn) rebind()
    }

    fun unbindCamera(o: LifecycleOwner) {
        if (owner !== o) return
        owner = null; preview = null
        stopCamera()
    }

    private fun rebind() {
        val c = app ?: return
        val o = owner ?: return
        val m = session?.mediaNow ?: return
        val prof = m.profile(VideoWire.CAMERA)
        val f = ProcessCameraProvider.getInstance(c)
        f.addListener({
            val prov = try { f.get() } catch (e: Exception) { return@addListener }
            provider = prov
            if (owner !== o || !_state.value.cameraOn) return@addListener
            val sel = if (_state.value.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val res = ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(prof.width, prof.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build()
            val analysis = ImageAnalysis.Builder().setResolutionSelector(res)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888).build()
            analysis.setAnalyzer(cameraExec) { img -> try { onCameraFrame(img, prof) } catch (_: Exception) {} finally { img.close() } }
            val pv = preview?.let { sp -> Preview.Builder().build().also { it.setSurfaceProvider(sp) } }
            try {
                prov.unbindAll()
                if (pv != null) prov.bindToLifecycle(o, sel, pv, analysis) else prov.bindToLifecycle(o, sel, analysis)
                update { it.copy(sendHeight = prof.height) }
            } catch (e: Exception) {
                // No camera on that side (a tablet without a back camera): try the other one once.
                if (!flipped) { flipped = true; update { it.copy(front = !it.front) }; rebind() }
            }
        }, ContextCompat.getMainExecutor(c))
    }

    private fun stopCamera() {
        val c = app
        provider?.let { p -> c?.let { ContextCompat.getMainExecutor(it).execute { try { p.unbindAll() } catch (_: Exception) {} } } }
        cameraExec.execute { encoder?.close(); encoder = null }
    }

    private fun onCameraFrame(img: ImageProxy, prof: VideoWire.Profile) {
        val s = session ?: return
        val m = s.mediaNow ?: return
        if (!_state.value.cameraOn) return
        val now = System.currentTimeMillis()
        // The profile's frame rate at most, kept on a schedule: a 30 fps camera gives 20 or 24 a second, not every other frame (15).
        val every = 1000L / prof.fps
        if (now + 3 < lastFrameAt) return
        lastFrameAt = maxOf(lastFrameAt + every, now + every / 2)   // when the next frame is due
        val w = img.width and 1.inv(); val h = img.height and 1.inv()
        var e = encoder
        if (e == null || e.width != w || e.height != h) {
            e?.close()
            e = try { Vp8Encoder(w, h, prof.fps, m.rate[0].kbps) } catch (x: Exception) { null } ?: return
            encoder = e
            keyWanted = true
        }
        if (now - lastRate > 1000) { lastRate = now; e.setKbps(m.rate[0].kbps) }
        if (keyWanted) { keyWanted = false; e.requestKey() }
        val rot = img.imageInfo.rotationDegrees
        e.encode(img) { bytes, key -> m.sendVideo(VideoWire.CAMERA, key, rot, bytes) }
    }
}
