package io.github.ravenor907.muninn.net

import java.io.ByteArrayOutputStream

/**
 * Cutting messages into Bluetooth LE frames and putting them back together. Kept free of Android
 * types so the unit tests run on the computer.
 *
 * A frame is: one flags byte (bit 0 = first frame of a message, bit 1 = last), the message number
 * (4 bytes, big-endian, counting up per sender per connection), then a slice of the message.
 * A message that fits in one frame has flags 3.
 */
object BleFrames {
    const val FIRST = 1
    const val LAST = 2
    const val HEADER = 5
    /** Anything bigger is dropped by both sides. */
    const val MAX_MESSAGE = 64 * 1024

    /** The frames for one message, or an empty list if it's too big to send. */
    fun split(message: ByteArray, number: Int, frameSize: Int): List<ByteArray> {
        require(frameSize > HEADER) { "frame size $frameSize leaves no room for data" }
        if (message.size > MAX_MESSAGE) return emptyList()
        val room = frameSize - HEADER
        // An empty message still goes out as one frame, so the other side sees it.
        val count = maxOf(1, (message.size + room - 1) / room)
        return List(count) { i ->
            val from = i * room
            val to = minOf(from + room, message.size)
            val f = ByteArray(HEADER + (to - from))
            var flags = 0
            if (i == 0) flags = flags or FIRST
            if (i == count - 1) flags = flags or LAST
            f[0] = flags.toByte()
            f[1] = (number ushr 24).toByte()
            f[2] = (number ushr 16).toByte()
            f[3] = (number ushr 8).toByte()
            f[4] = number.toByte()
            System.arraycopy(message, from, f, HEADER, to - from)
            f
        }
    }

    /** The message number in a frame, as an unsigned value. */
    fun number(frame: ByteArray): Long =
        ((frame[1].toLong() and 0xff) shl 24) or ((frame[2].toLong() and 0xff) shl 16) or
            ((frame[3].toLong() and 0xff) shl 8) or (frame[4].toLong() and 0xff)

    /**
     * Puts one connection's frames back together. Not thread safe: feed it from one thread.
     * Drops a message that grows past [MAX_MESSAGE], and anything out of order: a middle or last
     * frame with no first frame before it, or a new message whose number isn't higher than the last.
     */
    class Assembler(private val maxOpen: Int = 4) {
        private val open = LinkedHashMap<Long, ByteArrayOutputStream>()
        private var highest = -1L

        /** Returns the whole message when [frame] was its last frame, otherwise null. */
        fun add(frame: ByteArray): ByteArray? {
            if (frame.size < HEADER) return null
            val flags = frame[0].toInt() and 0xff
            val n = number(frame)
            if (flags and FIRST != 0) {
                if (n <= highest) return null
                highest = n
                open[n] = ByteArrayOutputStream()
                // A sender that keeps starting messages and never finishes them can't fill memory.
                while (open.size > maxOpen) open.remove(open.keys.first())
            }
            val buf = open[n] ?: return null
            val size = frame.size - HEADER
            if (buf.size() + size > MAX_MESSAGE) { open.remove(n); return null }
            buf.write(frame, HEADER, size)
            if (flags and LAST == 0) return null
            open.remove(n)
            return buf.toByteArray()
        }

        fun clear() { open.clear(); highest = -1L }
    }
}
