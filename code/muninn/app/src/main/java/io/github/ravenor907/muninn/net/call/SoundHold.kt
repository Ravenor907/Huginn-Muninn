package io.github.ravenor907.muninn.net.call

/**
 * What a voice room does when something else wants the sound, free of Android (its numbers are copied here) so unit
 * tests check it. A phone call, or another app's call (Discord, WhatsApp: it takes the focus for a moment, sets the
 * call mode or records for a call), pauses the room. Music or a clip plays alongside. When the mode goes back to
 * normal the room takes its call mode, route and focus back, whatever paused it.
 */
object SoundHold {
    const val ON_CALL = "On a phone call"
    const val OTHER_CALL = "In a call in another app"
    const val OTHER_APP = "Paused while another app plays"

    // AudioManager's numbers.
    const val MODE_NORMAL = 0
    const val MODE_RINGTONE = 1
    const val MODE_IN_CALL = 2
    const val MODE_IN_COMMUNICATION = 3
    const val MODE_CALL_SCREENING = 4
    const val FOCUS_GAIN = 1
    const val FOCUS_LOSS = -1
    const val FOCUS_LOSS_TRANSIENT = -2

    /** Android 12 says when the mode changes; older ones are asked this often while a room is up. */
    const val MODE_POLL_MS = 1_000L
    /** A mode change this soon after the room set the call mode itself is the room's own. */
    const val OWN_MODE_MS = 2_000L

    /**
     * [held]: the room's new reason to pause ("" resumes), or null to leave it as it is. [lost]: the focus went for
     * good (Android sends no more focus changes, so the mode is watched instead). [retake]: set the call mode, route
     * and focus again.
     */
    data class Step(val held: String?, val lost: Boolean, val retake: Boolean = false)

    /** A phone call (its ring or the call itself) has the sound. */
    fun phoneCall(mode: Int, sdk: Int): Boolean =
        mode == MODE_IN_CALL || mode == MODE_RINGTONE || (sdk >= 30 && mode == MODE_CALL_SCREENING)

    /** The focus changed. [otherVoip]: another app records for a call now. */
    fun focus(change: Int, mode: Int, otherVoip: Boolean, lost: Boolean, sdk: Int): Step = when (change) {
        // What a transient-exclusive request (calling apps, the assistant) does to the room.
        FOCUS_LOSS_TRANSIENT -> Step(if (phoneCall(mode, sdk)) ON_CALL else if (otherVoip) OTHER_CALL else OTHER_APP, lost)
        FOCUS_LOSS -> when {
            phoneCall(mode, sdk) -> Step(ON_CALL, true)
            otherVoip -> Step(OTHER_CALL, true)
            else -> Step(null, true)   // music or a clip: the room plays on and watches the mode
        }
        FOCUS_GAIN -> Step("", false)
        else -> Step(null, lost)
    }

    /** The mode is [mode] now. [ours]: the room set it a moment ago. */
    fun mode(mode: Int, ours: Boolean, held: String, lost: Boolean, sdk: Int): Step = when {
        phoneCall(mode, sdk) -> Step(ON_CALL, lost)
        mode == MODE_IN_COMMUNICATION && !ours -> Step(OTHER_CALL, lost)
        // The call (phone or app) hung up and reset the mode: the room's own mode, route and focus come back.
        mode == MODE_NORMAL -> Step(if (held.isNotEmpty()) "" else null, false, retake = true)
        else -> Step(null, lost)
    }

    /** Recordings changed: [otherVoip] says whether another app records for a call now. */
    fun recording(otherVoip: Boolean, held: String, lost: Boolean): Step = when {
        otherVoip && ((held.isEmpty() && lost) || held == OTHER_APP) -> Step(OTHER_CALL, lost)
        !otherVoip && held == OTHER_CALL -> Step("", false, retake = true)
        else -> Step(null, lost)
    }
}
