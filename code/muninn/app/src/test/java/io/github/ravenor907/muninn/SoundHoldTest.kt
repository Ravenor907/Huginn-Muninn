package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.SoundHold
import io.github.ravenor907.muninn.net.call.SoundHold.FOCUS_GAIN
import io.github.ravenor907.muninn.net.call.SoundHold.FOCUS_LOSS
import io.github.ravenor907.muninn.net.call.SoundHold.FOCUS_LOSS_TRANSIENT
import io.github.ravenor907.muninn.net.call.SoundHold.MODE_IN_CALL
import io.github.ravenor907.muninn.net.call.SoundHold.MODE_IN_COMMUNICATION
import io.github.ravenor907.muninn.net.call.SoundHold.MODE_NORMAL
import io.github.ravenor907.muninn.net.call.SoundHold.MODE_RINGTONE
import io.github.ravenor907.muninn.net.call.SoundHold.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A voice room and other calls: phone calls, other apps' calls, music and clips. */
class SoundHoldTest {
    @Test
    fun anotherAppsCallPausesTheRoomAndItComesBack() {
        // Discord takes the focus for its call (transient-exclusive): paused, then named a call once it records.
        val s1 = SoundHold.focus(FOCUS_LOSS_TRANSIENT, MODE_IN_COMMUNICATION, otherVoip = false, lost = false, sdk = 33)
        assertEquals(SoundHold.OTHER_APP, s1.held)
        assertEquals(Step(SoundHold.OTHER_CALL, false), SoundHold.recording(true, SoundHold.OTHER_APP, false))
        // It hangs up and resets the mode: the room takes its mode, route and focus back.
        assertEquals(Step("", false, retake = true), SoundHold.mode(MODE_NORMAL, ours = false, held = SoundHold.OTHER_CALL, lost = false, sdk = 33))
        // Or its recording ends first.
        assertEquals(Step("", false, retake = true), SoundHold.recording(false, SoundHold.OTHER_CALL, false))
    }

    @Test
    fun aCallAppTakingTheFocusForGoodIsACall() {
        assertEquals(Step(SoundHold.OTHER_CALL, true), SoundHold.focus(FOCUS_LOSS, MODE_IN_COMMUNICATION, otherVoip = true, lost = false, sdk = 30))
        // It took the focus before recording: the recording says so.
        val lost = SoundHold.focus(FOCUS_LOSS, MODE_IN_COMMUNICATION, otherVoip = false, lost = false, sdk = 30)
        assertEquals(Step(null, true), lost)
        assertEquals(Step(SoundHold.OTHER_CALL, true), SoundHold.recording(true, "", lost = true))
        // Its call mode, not the room's own.
        assertEquals(SoundHold.OTHER_CALL, SoundHold.mode(MODE_IN_COMMUNICATION, ours = false, held = "", lost = true, sdk = 33).held)
        assertEquals(null, SoundHold.mode(MODE_IN_COMMUNICATION, ours = true, held = "", lost = false, sdk = 33).held)
    }

    @Test
    fun aClipDoesNotStopPhoneCallDetection() {
        // A YouTube clip on Android 11: the focus goes for good and the room plays on.
        val clip = SoundHold.focus(FOCUS_LOSS, MODE_IN_COMMUNICATION, otherVoip = false, lost = false, sdk = 30)
        assertEquals(null, clip.held)
        assertTrue(clip.lost)
        // A phone call later is still caught (the mode is watched), and the room comes back after it.
        assertEquals(SoundHold.ON_CALL, SoundHold.mode(MODE_RINGTONE, ours = false, held = "", lost = true, sdk = 30).held)
        assertEquals(SoundHold.ON_CALL, SoundHold.mode(MODE_IN_CALL, ours = false, held = SoundHold.ON_CALL, lost = true, sdk = 30).held)
        val back = SoundHold.mode(MODE_NORMAL, ours = false, held = SoundHold.ON_CALL, lost = true, sdk = 30)
        assertEquals(Step("", false, retake = true), back)
        // Music alone never pauses the room, and the focus coming back resumes it.
        assertEquals(null, SoundHold.recording(false, "", lost = true).held)
        assertEquals(Step("", false), SoundHold.focus(FOCUS_GAIN, MODE_IN_COMMUNICATION, false, true, 30))
    }

    @Test
    fun phoneCallsAreCalls() {
        assertTrue(SoundHold.phoneCall(MODE_IN_CALL, 26))
        assertTrue(SoundHold.phoneCall(SoundHold.MODE_CALL_SCREENING, 30))
        assertFalse(SoundHold.phoneCall(SoundHold.MODE_CALL_SCREENING, 29))
        assertFalse(SoundHold.phoneCall(MODE_IN_COMMUNICATION, 33))
        assertEquals(SoundHold.ON_CALL, SoundHold.focus(FOCUS_LOSS_TRANSIENT, MODE_RINGTONE, true, false, 33).held)
    }
}
