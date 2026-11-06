package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.VideoWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Video fragments, reassembly with NACK and keyframe requests, reports and rate (shared/docs/protocol.md 5.8), as Huginn's VideoWire::selfTest. */
class VideoWireTest {
    private fun bytes(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed * 7) and 0xFF).toByte() }

    @Test
    fun header() {
        val e = VideoWire.encode(VideoWire.Fragment(VideoWire.SCREEN, true, 270, 0xBEEF, 2, 3, "vp8".toByteArray()))
        assertEquals(VideoWire.HEADER + 3, e.size)
        assertEquals(listOf(1, 7, 0xBE, 0xEF, 2, 3), e.take(6).map { it.toInt() and 0xFF })
        val g = VideoWire.decode(e)!!
        assertTrue(g.stream == VideoWire.SCREEN && g.key && g.rotation == 270 && g.frame == 0xBEEF && g.index == 2 && g.count == 3)
        assertArrayEquals("vp8".toByteArray(), g.data)
        assertNull(VideoWire.decode(e.copyOf().also { it[4] = 3 }))      // index past the count
        assertNull(VideoWire.decode(e.copyOf().also { it[0] = 2 }))      // unknown stream
        assertNull(VideoWire.decode(e.copyOf(VideoWire.HEADER)))          // no data
    }

    @Test
    fun splitAndJoin() {
        val big = bytes(5000, 1)
        val parts = VideoWire.split(VideoWire.CAMERA, 7, true, 90, big)
        assertEquals(5, parts.size)
        assertTrue(parts.all { it.size <= VideoWire.HEADER + VideoWire.MAX_FRAGMENT && it.size >= VideoWire.HEADER + 900 })
        assertTrue(VideoWire.split(VideoWire.CAMERA, 0, false, 0, bytes(VideoWire.MAX_FRAGMENT * 255 + 1, 0)).isEmpty())
        val a = VideoWire.Assembler()
        for (i in listOf(3, 0, 4, 1, 2)) a.add(VideoWire.decode(parts[i])!!, 0)
        val out = a.next()!!
        assertArrayEquals(big, out.data); assertTrue(out.key); assertEquals(90, out.rotation); assertEquals(7, out.frame)
        assertNull(a.next())
    }

    @Test
    fun nackFillsTheHole() {
        val a = VideoWire.Assembler()
        val sent = (0 until 4).map { VideoWire.split(VideoWire.CAMERA, 65534 + it, it == 0, 0, bytes(2500, it)) }
        var t = 0L; var got = 0
        for (i in 0 until 4) {
            for (k in sent[i].indices) { if (i == 1 && k == 1) continue; a.add(VideoWire.decode(sent[i][k])!!, t) }
            t += 40
            while (a.next() != null) got++
        }
        assertEquals(1, got)
        val ask = a.poll(t, 50)
        assertEquals(1, ask.nacks.size); assertEquals(65535, ask.nacks[0].frame); assertEquals(listOf(1), ask.nacks[0].indexes); assertFalse(ask.keyframe)
        val n = VideoWire.decodeNack(VideoWire.encodeNack(ask.nacks[0]))!!
        assertEquals(65535, n.frame); assertEquals(listOf(1), n.indexes)
        assertTrue(a.poll(t + 10, 50).nacks.isEmpty())
        a.add(VideoWire.decode(sent[1][1])!!, t + 60)
        var last: VideoWire.Assembler.Frame? = null
        while (true) { last = a.next() ?: break; got++ }
        assertEquals(4, got); assertEquals(0, a.stats.abandoned)
    }

    @Test
    fun lostForGoodWaitsForAKeyframe() {
        val a = VideoWire.Assembler()
        var t = 0L; var got = 0; var asked = false; var lastFrame = -1
        for (i in 0 until 30) {
            val parts = VideoWire.split(VideoWire.CAMERA, i, i == 0 || i == 20, 0, bytes(1500, i))
            if (i != 5) for (p in parts) a.add(VideoWire.decode(p)!!, t)
            asked = asked || a.poll(t, 40).keyframe
            while (true) { val f = a.next() ?: break; got++; lastFrame = f.frame }
            t += 40
        }
        assertEquals(1, a.stats.abandoned); assertTrue(asked)
        assertEquals(15, got); assertEquals(29, lastFrame)
        assertTrue(VideoWire.Assembler().poll(0, 40).keyframe)
    }

    /** A jump of 500 frames: no placeholders for them all and no NACK storm, a keyframe asked for instead. */
    @Test
    fun wideGapWaitsForAKeyframe() {
        val a = VideoWire.Assembler()
        for (i in listOf(0, 500)) {
            for (p in VideoWire.split(VideoWire.CAMERA, i, i == 0, 0, bytes(800, i))) a.add(VideoWire.decode(p)!!, 0)
            a.next()
        }
        val ask = a.poll(100, 40)
        assertTrue(ask.nacks.isEmpty()); assertTrue(ask.keyframe); assertEquals(1, a.stats.abandoned)
    }

    @Test
    fun reportAndRate() {
        val e = VideoWire.encodeReport(VideoWire.Report(7, camera = true, jitterMs = 80, kbps = 812, rttMs = 45))
        assertEquals(8, e.size)
        assertEquals(VideoWire.Report(7, true, false, 80, 812, 45), VideoWire.decodeReport(e))
        // Cap c3: bit 2, not watching the screen share.
        val paused = VideoWire.encodeReport(VideoWire.Report(screenPaused = true))
        assertEquals(4, paused[1].toInt())
        assertEquals(true, VideoWire.decodeReport(paused)?.screenPaused)
        val rc = VideoWire.RateControl()
        rc.setCap(700); assertEquals(420, rc.kbps)
        rc.onReport(12, 1000); assertEquals(315, rc.kbps)
        var t = 1000L
        repeat(60) { t += 1000; rc.onReport(0, t) }
        assertEquals(700, rc.kbps)
        assertEquals(480, VideoWire.cameraProfile(false).height); assertEquals(720, VideoWire.cameraProfile(true).height)
    }

    /** The bytes Huginn's self-test checks, so both apps write the same header. */
    @Test
    fun sameBytesAsHuginn() {
        val e = VideoWire.encode(VideoWire.Fragment(VideoWire.CAMERA, false, 90, 0x0102, 0, 1, byteArrayOf(9)))
        assertEquals(listOf(0, 2, 1, 2, 0, 1, 9), e.map { it.toInt() and 0xFF })
        assertEquals(listOf(0, 0x12, 0x34, 2, 0, 5), VideoWire.encodeNack(VideoWire.Nack(0, 0x1234, listOf(0, 5))).map { it.toInt() and 0xFF })
    }

    @Test
    fun aStrugglingReceiverGetsTheLightCamera() {
        for (free in listOf(false, true)) {
            val p = VideoWire.light(VideoWire.cameraProfile(free))
            assertEquals(640, p.width); assertEquals(360, p.height)
            assertTrue(p.fps <= 20)
            assertTrue(p.capKbps <= 800 && p.capKbps <= VideoWire.cameraProfile(free).capKbps)
        }
        val s = VideoWire.Strain()
        s.start(0)
        // The first seconds don't count; then 4 requests within 10 s do, once.
        repeat(6) { assertFalse(s.keyAsked(it * 500L)) }
        assertFalse(s.keyAsked(6_000)); assertFalse(s.keyAsked(9_000)); assertFalse(s.keyAsked(12_000))
        assertTrue(s.keyAsked(14_000))
        assertTrue(s.strained)
        assertFalse(s.keyAsked(14_500))
        // Spread out, they don't.
        val t = VideoWire.Strain(); t.start(0)
        for (at in listOf(6_000L, 11_000L, 16_000L, 21_000L, 26_000L)) assertFalse(t.keyAsked(at))
        assertFalse(t.strained)
    }

    @Test
    fun aLowBatteryMakesTheCameraLighter() {
        for (free in listOf(false, true)) {
            val p = VideoWire.lowPower(VideoWire.cameraProfile(free))
            assertTrue(p.height <= 360 && p.width <= 480)
            assertTrue(p.fps <= 15)
            assertTrue(p.capKbps <= 400)
        }
    }

    @Test
    fun theLadderStepsDownAtOnceAndUpWithRoom() {
        // A free path (cap 2000): lines at 900, 500 and 240.
        assertEquals(0, VideoWire.rung(1200, 2000, 0))
        assertEquals(1, VideoWire.rung(800, 2000, 0))
        assertEquals(3, VideoWire.rung(200, 2000, 0))
        // Back up only with a quarter more than the line.
        assertEquals(3, VideoWire.rung(260, 2000, 3))
        assertEquals(2, VideoWire.rung(310, 2000, 3))
        assertEquals(1, VideoWire.rung(1000, 2000, 1))
        assertEquals(0, VideoWire.rung(1130, 2000, 1))
        // The relay (cap 700) starting at its usual 420: no step.
        assertEquals(0, VideoWire.rung(420, 700, 0))
        val p = VideoWire.cameraProfile(true)
        assertEquals(p, VideoWire.onRung(p, 0))
        assertEquals(480, VideoWire.onRung(p, 1).height)
        assertEquals(15, VideoWire.onRung(p, 3).fps)
    }
}
