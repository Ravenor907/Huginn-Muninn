package io.github.ravenor907.muninn.net.call

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.abs

/**
 * Share a video inside a 1:1 call. The video plays in the camera's place, so the other side sees it large on its call
 * screen (any version that shows video calls, a Kids' TV included), with its own picture in the corner. Its sound goes
 * into the call under the voices: lower while anyone talks, and through this side's call sound too, so the echo
 * canceller knows it. Nothing new goes on the wire. The person sharing can pause, go on and stop.
 */
object CallShare {
    data class State(val on: Boolean = false, val paused: Boolean = false, val name: String = "", val atMs: Long = 0, val ms: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    val active: Boolean get() = _state.value.on

    /** The video's sound, 20 ms frames at the call's rate: what goes out (taken by the microphone loop) and what plays here. */
    val sendQ = ArrayBlockingQueue<ShortArray>(12)
    val localQ = ArrayBlockingQueue<ShortArray>(12)
    @Volatile var keyWanted = false

    @Volatile private var run = 0
    @Volatile private var paused = false
    @Volatile private var pausedAt = 0L
    @Volatile private var startedAt = 0L
    private val lock = Object()

    /** Under a voice, about -12 dB; with nobody talking, about -6 dB. */
    fun mix(into: ShortArray, video: ShortArray, talk: Boolean) {
        val g = if (talk) 0.25f else 0.5f
        for (i in into.indices) {
            val v = into[i] + (if (i < video.size) video[i] * g else 0f)
            into[i] = v.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }

    /** This side is talking in [pcm] (a 20 ms frame): loud enough to be a voice, not room noise. */
    fun talking(pcm: ShortArray): Boolean {
        var sum = 0L
        for (x in pcm) sum += abs(x.toInt())
        return sum / maxOf(1, pcm.size) > 900
    }

    /** Starts sharing [uri] into the call now. Null when it started, else what to say. */
    fun start(context: Context, uri: Uri, name: String): String? {
        val s = CallVideo.shareable() ?: return "Video can't be shared in this call."
        val m = s.mediaNow ?: return "Video can't be shared in this call."
        stop()
        val my: Int
        synchronized(lock) {
            my = ++run
            paused = false
            sendQ.clear(); localQ.clear()
            startedAt = System.currentTimeMillis()
            _state.value = State(on = true, name = name)
        }
        CallVideo.sharing(true)
        keyWanted = true
        Thread({ videoLoop(context, uri, my, m) }, "muninn-share-video").apply { isDaemon = true; start() }
        Thread({ audioLoop(context, uri, my) }, "muninn-share-audio").apply { isDaemon = true; start() }
        return null
    }

    fun pause(on: Boolean) {
        if (!active || paused == on) return
        synchronized(lock) {
            if (on) { pausedAt = System.currentTimeMillis(); sendQ.clear(); localQ.clear() }
            else { startedAt += System.currentTimeMillis() - pausedAt; lock.notifyAll() }
            paused = on
            _state.value = _state.value.copy(paused = on)
        }
    }

    fun stop() {
        if (!active) return
        synchronized(lock) { run++; paused = false; lock.notifyAll(); sendQ.clear(); localQ.clear(); _state.value = State() }
        CallVideo.sharing(false)
    }

    /** The call ended: nothing more to share into. */
    fun callEnded() { if (active) synchronized(lock) { run++; sendQ.clear(); localQ.clear(); _state.value = State() } }

    /** Changes the state only while share [my] is still the one on (a stopped one never turns it back on). */
    private fun change(my: Int, f: (State) -> State) { synchronized(lock) { if (alive(my)) _state.value = f(_state.value) } }

    private fun alive(my: Int) = run == my

    /** Waits while paused; false once stopped. */
    private fun holdWhilePaused(my: Int): Boolean {
        synchronized(lock) { while (paused && alive(my)) try { lock.wait(200) } catch (_: InterruptedException) { return false } }
        return alive(my)
    }

    /** Waits until [ptsUs] of the video is due; false once stopped. */
    private fun waitFor(ptsUs: Long, my: Int): Boolean {
        while (alive(my)) {
            if (!holdWhilePaused(my)) return false
            val due = startedAt + ptsUs / 1000
            val wait = due - System.currentTimeMillis()
            if (wait <= 0) return true
            try { Thread.sleep(minOf(wait, 50)) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    // ------------------------------------------------------------------ the picture

    private fun videoLoop(context: Context, uri: Uri, my: Int, m: CallMedia) {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        var enc: Vp8Encoder? = null
        try {
            ex.setDataSource(context, uri, null)
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: throw IllegalArgumentException("no video")
            ex.selectTrack(t)
            val f = ex.getTrackFormat(t)
            val total = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            change(my) { it.copy(ms = total) }
            val rotation = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
            val d = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            d.configure(f, null, null, 0)
            d.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var lastSent = 0L
            var lastRate = 0L
            while (alive(my)) {
                if (!inputDone) {
                    val i = d.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = d.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { d.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = d.dequeueOutputBuffer(info, 10_000)
                if (o < 0) continue
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { d.releaseOutputBuffer(o, false); break }
                if (!waitFor(info.presentationTimeUs, my)) { d.releaseOutputBuffer(o, false); break }
                change(my) { it.copy(atMs = info.presentationTimeUs / 1000) }
                val prof = m.profile(VideoWire.CAMERA)
                val now = System.currentTimeMillis()
                // The call's frame rate at most; a frame that's late is dropped rather than queued.
                if (now - lastSent < 1000L / prof.fps - 3) { d.releaseOutputBuffer(o, false); continue }
                val img = try { d.getOutputImage(o) } catch (_: Exception) { null }
                if (img != null) {
                    val frame = I420.from(img, prof.width, prof.height)
                    img.close()
                    var e = enc
                    if (e == null || e.width != frame.w || e.height != frame.h) {
                        e?.close()
                        e = Vp8Encoder(frame.w, frame.h, prof.fps, m.rate[0].kbps)
                        enc = e
                        keyWanted = true
                    }
                    if (now - lastRate > 1000) { lastRate = now; e.setKbps(m.rate[0].kbps) }
                    if (keyWanted) { keyWanted = false; e.requestKey() }
                    e.encodeI420(frame.y, frame.u, frame.v) { bytes, key -> m.sendVideo(VideoWire.CAMERA, key, rotation, bytes) }
                    lastSent = now
                }
                d.releaseOutputBuffer(o, false)
            }
        } catch (_: Exception) {
            // Opened on this thread (never the screen's): one that can't be read says so.
            if (alive(my) && _state.value.atMs == 0L) io.github.ravenor907.muninn.ui.Ui.say("That video can't be played here.", true)
        } finally {
            try { dec?.stop() } catch (_: Exception) {}; try { dec?.release() } catch (_: Exception) {}
            enc?.close(); ex.release()
            // Played to the end: the camera's place goes back.
            if (alive(my)) android.os.Handler(android.os.Looper.getMainLooper()).post { if (alive(my)) stop() }
        }
    }

    // ------------------------------------------------------------------ the sound

    private fun audioLoop(context: Context, uri: Uri, my: Int) {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val t = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            ex.selectTrack(t)
            val f = ex.getTrackFormat(t)
            val d = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME)!!)
            dec = d
            d.configure(f, null, null, 0)
            d.start()
            var rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            val frame = Opus.FRAME
            val pending = ShortArray(frame)
            var have = 0
            var pos = 0.0           // where the resampler stands in the decoded sound
            var made = 0L           // 20 ms frames made so far (their time in the video)
            var inputDone = false
            while (alive(my)) {
                if (!holdWhilePaused(my)) break
                if (!inputDone) {
                    val i = d.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = d.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { d.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { d.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = d.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    d.outputFormat.let { nf -> rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                    continue
                }
                if (o < 0) continue
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { d.releaseOutputBuffer(o, false); break }
                val out = d.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                out.position(info.offset / 2)
                val samples = ShortArray(info.size / 2)
                out.get(samples, 0, minOf(samples.size, out.remaining()))
                d.releaseOutputBuffer(o, false)
                // Mono at the call's rate (simple linear steps: speech-grade, as the call itself is).
                val mono = samples.size / maxOf(1, ch)
                val step = rate.toDouble() / Opus.RATE
                while (pos < mono - 1) {
                    val k = pos.toInt(); val fr = pos - k
                    var a = 0; var b = 0
                    for (c in 0 until ch) { a += samples[k * ch + c]; b += samples[(k + 1) * ch + c] }
                    pending[have++] = ((a + (b - a) * fr) / ch).toInt().coerceIn(-32768, 32767).toShort()
                    pos += step
                    if (have == frame) {
                        made++
                        // Behind the picture (a hiccup somewhere): this frame is dropped, so sound and picture stay together.
                        if ((made * 20L) < System.currentTimeMillis() - startedAt - 200) { have = 0; continue }
                        val copy = pending.copyOf()
                        // Paced by the call: the microphone loop takes one frame every 20 ms.
                        while (alive(my) && !sendQ.offer(copy, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) { if (!holdWhilePaused(my)) break }
                        localQ.offer(copy.copyOf())
                        have = 0
                    }
                }
                pos -= (mono - 1).coerceAtLeast(0)
            }
        } catch (_: Exception) {
        } finally {
            try { dec?.stop() } catch (_: Exception) {}; try { dec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    /** A decoded frame held to [maxW] x [maxH] by halving (cheap), as even-sized I420. */
    private class I420(val w: Int, val h: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray) {
        companion object {
            fun from(img: android.media.Image, maxW: Int, maxH: Int): I420 {
                val long = maxOf(img.width, img.height); val short = minOf(img.width, img.height)
                val limLong = maxOf(maxW, maxH); val limShort = minOf(maxW, maxH)
                var f = 1
                while (long / f > limLong || short / f > limShort) f *= 2
                val w = (img.width / f) and 1.inv(); val h = (img.height / f) and 1.inv()
                val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
                plane(img.planes[0], f, w, h, y)
                plane(img.planes[1], f, w / 2, h / 2, u)
                plane(img.planes[2], f, w / 2, h / 2, v)
                return I420(w, h, y, u, v)
            }

            private fun plane(p: android.media.Image.Plane, f: Int, w: Int, h: Int, out: ByteArray) {
                val b = p.buffer; val rs = p.rowStride; val ps = p.pixelStride
                val lim = b.limit()
                for (yy in 0 until h) {
                    val row = yy * f * rs
                    for (xx in 0 until w) {
                        val i = row + xx * f * ps
                        out[yy * w + xx] = if (i < lim) b.get(i) else 0
                    }
                }
            }
        }
    }
}
