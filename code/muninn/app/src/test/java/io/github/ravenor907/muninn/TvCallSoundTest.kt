package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.CallLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kids' TV call sound: the microphone's read errors are recovered quietly, without a restart loop. */
class TvCallSoundTest {
    @Test
    fun goodReadsNeverRestart() {
        val m = CallLogic.MicErrors()
        for (t in 0L until 10_000L step 20) assertFalse(m.read(ok = true, now = t))
    }

    @Test
    fun restartsAfterHalfASecondOfErrorsAtMostEveryTwoSeconds() {
        val m = CallLogic.MicErrors()
        var t = 0L
        val at = ArrayList<Long>()
        // Errors only (the recorder stopped): one read every 20 ms for 5 s.
        repeat(250) { if (m.read(ok = false, now = t)) at += t; t += 20 }
        assertEquals(480L, at.first())                  // the 25th error in a row
        assertTrue(at.zipWithNext().all { (a, b) -> b - a >= 2_000 })
        assertEquals(3, at.size)                        // 0.48 s, 2.48 s, 4.48 s
    }

    @Test
    fun aGoodReadClearsTheCount() {
        val m = CallLogic.MicErrors()
        var t = 0L
        repeat(24) { assertFalse(m.read(false, t)); t += 20 }
        assertFalse(m.read(true, t)); t += 20
        repeat(24) { assertFalse(m.read(false, t)); t += 20 }
        assertTrue(m.read(false, t))
    }
}
