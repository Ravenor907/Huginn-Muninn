package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.BleFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Bluetooth LE framing Huginn and Muninn share: split, flags, and putting it back together. */
class BleFramesTest {
    private fun join(frames: List<ByteArray>, a: BleFrames.Assembler = BleFrames.Assembler()): ByteArray? {
        var out: ByteArray? = null
        frames.forEachIndexed { i, f ->
            val r = a.add(f)
            if (i < frames.size - 1) assertNull("finished early at frame $i", r) else out = r
        }
        return out
    }

    @Test
    fun roundTripAtSeveralMtus() {
        val rnd = Random(7)
        for (mtu in listOf(23, 24, 27, 100, 185, 247, 515)) {
            val frameSize = minOf(mtu - 3, 512)
            for (len in listOf(0, 1, frameSize - 5, frameSize - 4, frameSize, 1000, 20_000, BleFrames.MAX_MESSAGE)) {
                val msg = rnd.nextBytes(len)
                val frames = BleFrames.split(msg, 42, frameSize)
                assertTrue(frames.all { it.size <= frameSize })
                assertEquals(maxOf(1, (len + frameSize - 6) / (frameSize - 5)), frames.size)
                assertArrayEquals("mtu $mtu len $len", msg, join(frames))
            }
        }
    }

    @Test
    fun utf8TextSurvives() {
        val text = """{"room":"private","fromName":"Hrafn","data":"${"ᚱᚨᚢᛖᚾ ".repeat(50)}"}"""
        val back = join(BleFrames.split(text.toByteArray(), 0, 20))
        assertEquals(text, String(back!!))
    }

    @Test
    fun singleFrameHasFlagsThreeAndBigEndianNumber() {
        val frames = BleFrames.split("hi".toByteArray(), 0x01020304, 20)
        assertEquals(1, frames.size)
        val f = frames[0]
        assertEquals(3, f[0].toInt())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), f.copyOfRange(1, 5))
        assertEquals(0x01020304L, BleFrames.number(f))
        assertArrayEquals("hi".toByteArray(), f.copyOfRange(5, f.size))
        assertArrayEquals("hi".toByteArray(), BleFrames.Assembler().add(f))
    }

    @Test
    fun multiFrameFlags() {
        val frames = BleFrames.split(ByteArray(40), 1, 20)   // 15 bytes a frame: 3 frames
        assertEquals(listOf(1, 0, 2), frames.map { it[0].toInt() })
    }

    @Test
    fun numbersAreUnsigned() {
        val f = BleFrames.split(ByteArray(3), -1, 20)[0]
        assertEquals(0xFFFFFFFFL, BleFrames.number(f))
    }

    @Test
    fun oversizeIsRejected() {
        assertTrue(BleFrames.split(ByteArray(BleFrames.MAX_MESSAGE + 1), 0, 512).isEmpty())
        // A sender that ignores the limit: frames past 64 KB are dropped, and so is the message.
        val a = BleFrames.Assembler()
        val big = ByteArray(BleFrames.MAX_MESSAGE + 507)
        val frames = BleFrames.split(big.copyOf(BleFrames.MAX_MESSAGE), 5, 512).toMutableList()
        frames[frames.size - 1] = frames.last().also { it[0] = 0 }   // not the last after all
        frames += BleFrames.split(ByteArray(600), 5, 512).map { it.also { f -> f[0] = 0 } }
        frames[frames.size - 1][0] = BleFrames.LAST.toByte()
        assertTrue(frames.all { a.add(it) == null })
        // The next message still gets through.
        assertArrayEquals(byteArrayOf(9), a.add(BleFrames.split(byteArrayOf(9), 6, 20)[0]))
    }

    @Test
    fun outOfOrderIsRejected() {
        val a = BleFrames.Assembler()
        val frames = BleFrames.split(ByteArray(40) { it.toByte() }, 3, 20)
        // Middle and last before the first: nothing to add them to.
        assertNull(a.add(frames[1]))
        assertNull(a.add(frames[2]))
        // An older number after a newer one is dropped too.
        assertArrayEquals(byteArrayOf(1), a.add(BleFrames.split(byteArrayOf(1), 10, 20)[0]))
        assertNull(a.add(BleFrames.split(byteArrayOf(2), 9, 20)[0]))
        assertNull(a.add(BleFrames.split(byteArrayOf(3), 10, 20)[0]))
        assertArrayEquals(byteArrayOf(4), a.add(BleFrames.split(byteArrayOf(4), 11, 20)[0]))
        // Too short to be a frame.
        assertNull(a.add(byteArrayOf(3, 0, 0)))
    }

    @Test
    fun interleavedMessagesReassembleByNumber() {
        val a = BleFrames.Assembler()
        val one = BleFrames.split(ByteArray(40) { 1 }, 1, 20)
        val two = BleFrames.split(ByteArray(30) { 2 }, 2, 20)
        assertNull(a.add(one[0]))
        assertNull(a.add(two[0]))
        assertNull(a.add(one[1]))
        assertArrayEquals(ByteArray(30) { 2 }, a.add(two[1]))
        assertArrayEquals(ByteArray(40) { 1 }, a.add(one[2]))
    }
}
