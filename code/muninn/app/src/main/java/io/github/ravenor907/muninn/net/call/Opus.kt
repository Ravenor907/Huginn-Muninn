package io.github.ravenor907.muninn.net.call

/**
 * libopus, bundled (src/main/cpp): Android's own Opus encoder only exists from Android 10, and Muninn
 * runs from Android 8. Settings from shared/docs/protocol.md 5.5: VOIP, mono, 16 kHz, 16 kbps VBR, 20 ms,
 * in-band FEC for 10% loss, DTX, complexity 5.
 */
object Opus {
    const val RATE = 16_000
    const val FRAME = 320           // 20 ms at 16 kHz
    const val BITRATE = 16_000
    const val MAX_PACKET = 400

    /** Null if the native library couldn't load (then calls say so instead of crashing). */
    val loadError: Throwable? = try { System.loadLibrary("muninn_opus"); null } catch (t: Throwable) { t }

    @JvmStatic external fun encoderNew(rate: Int, bitrate: Int, loss: Int, complexity: Int): Long
    @JvmStatic external fun encode(h: Long, pcm: ShortArray, frame: Int, out: ByteArray): Int
    @JvmStatic external fun encoderTune(h: Long, bitrate: Int, loss: Int)
    @JvmStatic external fun encoderFree(h: Long)
    @JvmStatic external fun decoderNew(rate: Int): Long
    @JvmStatic external fun decode(h: Long, data: ByteArray?, pcm: ShortArray, frame: Int, fec: Int): Int
    @JvmStatic external fun decoderFree(h: Long)

    class Encoder : AutoCloseable {
        private var h = encoderNew(RATE, BITRATE, 10, 5).also { check(it != 0L) { "Opus encoder" } }
        private val out = ByteArray(MAX_PACKET)

        private var bitrate = BITRATE
        private var loss = 10

        /**
         * One 20 ms frame to one packet; null for an error, and for DTX silence unless [keepSilence] (then the 1 or 2 byte
         * silence packet comes back too, see [isSilence]: a 1:1 call sends one every 400 ms so the other side makes comfort noise).
         */
        @Synchronized fun encode(pcm: ShortArray, keepSilence: Boolean = false): ByteArray? {
            if (h == 0L) return null
            val n = encode(h, pcm, FRAME, out)
            return if (n > 2 || (keepSilence && n > 0)) out.copyOf(n) else null
        }

        /** 5.8.4: the bitrate (16, 24 or 32 kbps) and the expected loss the other side reports. Unchanged when the same. */
        @Synchronized fun tune(bps: Int, lossPct: Int) {
            val b = bps.coerceIn(8_000, 64_000); val l = lossPct.coerceIn(0, 40)
            if (h == 0L || (b == bitrate && l == loss)) return
            bitrate = b; loss = l
            encoderTune(h, b, l)
        }
        val bitrateNow: Int get() = bitrate

        @Synchronized override fun close() { if (h != 0L) { encoderFree(h); h = 0L } }
    }

    fun isSilence(p: ByteArray) = p.size <= 2

    class Decoder : AutoCloseable {
        private var h = decoderNew(RATE).also { check(it != 0L) { "Opus decoder" } }

        /** Decodes [packet] into [pcm] (FRAME samples). [packet] null: concealment. [fec]: rebuild the frame before it. */
        @Synchronized fun decode(packet: ByteArray?, pcm: ShortArray, fec: Boolean = false): Int {
            if (h == 0L) return -1
            return decode(h, packet, pcm, FRAME, if (fec) 1 else 0)
        }

        @Synchronized override fun close() { if (h != 0L) { decoderFree(h); h = 0L } }
    }
}
