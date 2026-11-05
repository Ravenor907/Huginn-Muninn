package io.github.ravenor907.muninn.tv

/**
 * Voice processing for the Kids' TV microphone, in libmuninn_tvvoice (src/main/cpp/tvvoice): WebRTC's
 * echo canceller (AEC3), high-pass filter and automatic gain (AGC2), then RNNoise for the room's noise.
 * Loaded only when a Kids' TV first records; a phone never loads it. Works in 10 ms steps.
 */
object TvVoice {
    const val AEC = 1
    const val RNNOISE = 2
    const val AGC = 4
    const val NS = 8

    /** Null once the library is in; the error if this TV can't load it (then the TV records without it). */
    val loadError: Throwable? by lazy { try { System.loadLibrary("muninn_tvvoice"); null } catch (t: Throwable) { t } }
    val available: Boolean get() = loadError == null

    @JvmStatic private external fun create(inRate: Int, outRate: Int, renderRate: Int, playRate: Int, flags: Int): Long
    @JvmStatic private external fun destroy(h: Long)
    @JvmStatic private external fun capture(h: Long, pcm: ShortArray, inOff: Int, out: ShortArray, outOff: Int): Int
    @JvmStatic private external fun render(h: Long, pcm: ShortArray, inOff: Int, out: ShortArray, outOff: Int): Int
    @JvmStatic private external fun setDelay(h: Long, ms: Int)
    @JvmStatic private external fun voiceProbability(h: Long): Float

    /**
     * One recording's or one call's processing. [capture] (the microphone thread) and [render] (the
     * speaker thread) may run at the same time, each from one thread. If this TV turns out too slow for
     * everything (more than half of real time over a second), RNNoise gives way to WebRTC's lighter noise
     * suppressor, and after that only the echo canceller (calls) or nothing is left.
     */
    class Processor private constructor(
        val inRate: Int, val outRate: Int, val renderRate: Int, val playRate: Int, flags: Int, private var h: Long,
    ) : AutoCloseable {
        private val inStep = inRate / 100
        private val outStep = outRate / 100
        private val renderStep = renderRate / 100
        private val playStep = playRate / 100
        @Volatile var flags = flags; private set
        private val lock = Any()
        private var spentNs = 0L
        private var steps = 0
        @Volatile private var delayMs = 0

        /** [n] samples (a whole number of 10 ms steps) in at [inRate]; out at [outRate]. Returns the samples written. */
        fun capture(pcm: ShortArray, n: Int, out: ShortArray): Int {
            var o = 0
            var i = 0
            val t0 = System.nanoTime()
            synchronized(lock) {
                while (i + inStep <= n && o + outStep <= out.size) {
                    if (h == 0L) return 0
                    val got = capture(h, pcm, i, out, o)
                    o += if (got > 0) got else outStep
                    i += inStep
                }
            }
            watch(System.nanoTime() - t0, n / inStep)
            return o
        }

        /** [n] samples at [renderRate] about to be played: the echo canceller hears them, [out] gets them at [playRate]. */
        fun render(pcm: ShortArray, n: Int, out: ShortArray): Int {
            var o = 0
            var i = 0
            synchronized(lock) {
                while (i + renderStep <= n && o + playStep <= out.size) {
                    if (h == 0L) return 0
                    val got = render(h, pcm, i, out, o)
                    o += if (got > 0) got else playStep
                    i += renderStep
                }
            }
            return o
        }

        /** Speaker-to-microphone delay hint for the echo canceller (it also finds the delay itself). */
        fun delay(ms: Int) { delayMs = ms; synchronized(lock) { if (h != 0L) setDelay(h, ms) } }

        /** RNNoise's chance that the last 10 ms were someone talking (0..1), or -1 without RNNoise. */
        fun voice(): Float = synchronized(lock) { if (h != 0L) voiceProbability(h) else -1f }

        private fun watch(ns: Long, n: Int) {
            spentNs += ns; steps += n
            if (steps < 100) return
            val share = spentNs / (steps * 10_000_000.0)
            spentNs = 0; steps = 0
            if (share <= 0.5) return
            // Too slow for this TV: drop the heaviest part and carry on.
            val next = when {
                flags and RNNOISE != 0 -> (flags and RNNOISE.inv()) or NS
                flags and (NS or AGC) != 0 -> flags and AEC
                else -> return
            }
            synchronized(lock) {
                val n2 = create(inRate, outRate, renderRate, playRate, next)
                if (n2 == 0L) return
                destroy(h); h = n2; flags = next
                if (delayMs > 0) setDelay(h, delayMs)
            }
        }

        override fun close() = synchronized(lock) { if (h != 0L) { destroy(h); h = 0L } }

        companion object {
            /** Null when the library isn't there or these rates can't be done. */
            fun open(inRate: Int, outRate: Int, renderRate: Int, playRate: Int, flags: Int): Processor? {
                if (!available) return null
                val h = try { create(inRate, outRate, renderRate, playRate, flags) } catch (t: Throwable) { 0L }
                return if (h == 0L) null else Processor(inRate, outRate, renderRate, playRate, flags, h)
            }
        }
    }

    /**
     * Test builds (CI on an emulator): loads the library and runs a second of a call through everything.
     * "ok ..." or what went wrong.
     */
    fun selfCheck(): String {
        loadError?.let { return "can't load: $it" }
        val p = Processor.open(48_000, 16_000, 16_000, 48_000, AEC or RNNOISE or AGC) ?: return "can't open 48 kHz"
        val mic = ShortArray(960); val out = ShortArray(320); val far = ShortArray(320); val play = ShortArray(960)
        var phase = 0.0
        var heard = 0.0
        for (f in 0 until 50) {
            for (i in mic.indices) { phase += 2 * Math.PI * 220 / 48_000; mic[i] = (3000 * kotlin.math.sin(phase)).toInt().toShort() }
            for (i in far.indices) far[i] = (2000 * kotlin.math.sin(i * 0.3 + f)).toInt().toShort()
            if (p.render(far, far.size, play) != 960) { p.close(); return "render length" }
            if (f >= 10) for (s in play) heard += s.toDouble() * s
            if (p.capture(mic, mic.size, out) != 320) { p.close(); return "capture length" }
        }
        val v = p.voice()
        p.close()
        // The speaker must carry the call: a 2000-peak tone is about 1400 RMS, silence is 0.
        val rms = kotlin.math.sqrt(heard / (40 * 960))
        if (rms < 700) return "speaker silent (rms ${rms.toInt()})"
        // Every noise removal choice (k5): none, Standard, Strong and both, for a clip.
        for (nr in intArrayOf(0, NS, RNNOISE, NS or RNNOISE)) {
            val clip = Processor.open(44_100, 44_100, 16_000, 16_000, nr or AGC) ?: return "can't open 44.1 kHz with noise $nr"
            val c = ShortArray(882)
            val n = clip.capture(c, c.size, ShortArray(882))
            clip.close()
            if (n != 882) return "clip length $n with noise $nr"
        }
        return "ok (voice $v)"
    }
}
