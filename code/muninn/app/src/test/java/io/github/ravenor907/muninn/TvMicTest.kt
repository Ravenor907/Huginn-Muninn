package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.tv.TvMic
import io.github.ravenor907.muninn.tv.TvRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The Kids' TV microphone: gain from the parent's level, a limiter that never clips, and ADTS headers. */
class TvMicTest {
    @Test
    fun levelMapsToGain() {
        assertEquals(0f, TvMic.gainDb(0), 0.001f)
        assertEquals(15f, TvMic.gainDb(50), 0.001f)
        assertEquals(30f, TvMic.gainDb(100), 0.001f)
        assertEquals(30f, TvMic.gainDb(250), 0.001f)   // out of range is held to 100
    }

    @Test
    fun quietSpeechGetsLouderAndLoudNeverWraps() {
        val pcm = shortArrayOf(1000, -1000, 0, 20000, -20000, Short.MAX_VALUE, Short.MIN_VALUE)
        TvMic.Gain(50).process(pcm)
        // 1000 at +15 dB is about 5623: below the knee, so exactly louder.
        assertTrue(abs(pcm[0] - 5623) < 3); assertTrue(abs(pcm[1] + 5623) < 3)
        assertEquals(0, pcm[2].toInt())
        // Loud samples bend toward full scale, keep their sign, and stay in range.
        for (i in 3..6) assertTrue("sample $i", abs(pcm[i].toInt()) in 19661..32767)
        assertTrue(pcm[3] > 0 && pcm[4] < 0 && pcm[5] > 0 && pcm[6] < 0)
        // Level 0 leaves the sound as the TV gave it.
        val same = shortArrayOf(123, -456)
        TvMic.Gain(0).process(same)
        assertEquals(123, same[0].toInt()); assertEquals(-456, same[1].toInt())
        // The limiter is smooth and never goes past full scale.
        var last = 0f
        for (i in 0..400) { val y = TvMic.Gain.shape(i / 100f); assertTrue(y >= last && y <= 1f); last = y }
    }

    @Test
    fun adtsHeaderSaysLcMono44k() {
        val h = TvRecorder.adts(100)
        assertEquals(7, h.size)
        assertEquals(0xFF, h[0].toInt() and 0xFF); assertEquals(0xF1, h[1].toInt() and 0xFF)
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
        assertEquals(107, len)
        assertEquals(1, (h[2].toInt() and 0xC0) shr 6)   // profile LC
        assertEquals(4, (h[2].toInt() and 0x3C) shr 2)   // 44.1 kHz
        // Voice.check takes what the recorder makes.
        assertTrue(io.github.ravenor907.muninn.net.Voice.check(listOf(h + ByteArray(100))) != null)
    }

    @Test
    fun aNewLevelReachesARunningGain() {
        var level = 0
        val g = TvMic.Gain { level }
        val a = shortArrayOf(1000); g.process(a)
        assertEquals(1000, a[0].toInt())
        level = 50   // the parent turned it up during the call
        val b = shortArrayOf(1000); g.process(b)
        assertTrue(abs(b[0] - 5623) < 3)
    }

    @Test
    fun callMicFallsBackToMicOnlyOnTheTv() {
        val vc = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val mic = android.media.MediaRecorder.AudioSource.MIC
        assertEquals(listOf(vc), TvMic.callSources(false).toList())
        assertEquals(listOf(vc, mic), TvMic.callSources(true).toList())
        // With its own voice processing the TV uses the clean voice-search path first.
        val vr = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION
        assertEquals(listOf(vr, mic, vc), TvMic.callSources(true, true).toList())
        assertEquals(listOf(vc), TvMic.callSources(false, true).toList())
    }

    @Test
    fun afterProcessingTheLevelIsCenteredOn50() {
        // 50 leaves the processed sound as it is; 100 is +15 dB; 0 is -15 dB.
        val a = shortArrayOf(1000); TvMic.Gain(50, TvMic.PROCESSED_OFFSET_DB).process(a)
        assertEquals(1000, a[0].toInt())
        val b = shortArrayOf(1000); TvMic.Gain(100, TvMic.PROCESSED_OFFSET_DB).process(b)
        assertTrue(abs(b[0] - 5623) < 3)
        val c = shortArrayOf(5623); TvMic.Gain(0, TvMic.PROCESSED_OFFSET_DB).process(c)
        assertTrue(abs(c[0] - 1000) < 3)
    }
}
