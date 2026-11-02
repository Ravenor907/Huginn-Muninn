package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.call.AudioRoute.Kind
import io.github.ravenor907.muninn.net.call.AudioRoute.Output
import io.github.ravenor907.muninn.net.call.pickOutput
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where call sound goes by itself: voice calls at the ear, video calls and voice rooms on the speaker, a headset always first. */
class AudioRouteTest {
    private val ear = Output("earpiece", Kind.EARPIECE, "Phone")
    private val speaker = Output("speaker", Kind.SPEAKER, "Speaker")
    private val wired = Output("dev:7", Kind.WIRED, "Headphones")
    private val bt = Output("dev:9", Kind.BLUETOOTH, "Buds")

    private fun pick(all: List<Output>, video: Boolean, chosen: String = "", room: Boolean = false) = pickOutput(all, chosen, room, video)?.id

    @Test
    fun voiceCallsStartAtTheEar() {
        assertEquals("earpiece", pick(listOf(ear, speaker), video = false))
        assertEquals("dev:7", pick(listOf(ear, speaker, wired), video = false))
        assertEquals("dev:9", pick(listOf(ear, speaker, wired, bt), video = false))
    }

    @Test
    fun videoCallsStartOnTheSpeakerUnlessAHeadsetIsConnected() {
        assertEquals("speaker", pick(listOf(ear, speaker), video = true))
        assertEquals("dev:7", pick(listOf(ear, speaker, wired), video = true))
        assertEquals("dev:9", pick(listOf(ear, speaker, wired, bt), video = true))
        // A phone with no speaker listed (unusual): the earpiece rather than nothing.
        assertEquals("earpiece", pick(listOf(ear), video = true))
    }

    @Test
    fun thePersonsChoiceWinsWhileItsThere() {
        assertEquals("earpiece", pick(listOf(ear, speaker, bt), video = true, chosen = "earpiece"))
        assertEquals("speaker", pick(listOf(ear, speaker, bt), video = false, chosen = "speaker"))
        // The chosen headset went away: back to the usual order.
        assertEquals("speaker", pick(listOf(ear, speaker), video = true, chosen = "dev:9"))
    }

    @Test
    fun voiceRoomsFollowTheHeadsetThenTheSpeaker() {
        assertEquals("dev:9", pick(listOf(ear, speaker, wired, bt), video = false, room = true))
        assertEquals("dev:7", pick(listOf(ear, speaker, wired), video = false, room = true))
        assertEquals("speaker", pick(listOf(ear, speaker), video = false, room = true))
        // The chosen earbuds ran flat: the speaker, not the earpiece.
        assertEquals("speaker", pick(listOf(ear, speaker), video = false, chosen = "dev:9", room = true))
        // The earpiece, only when picked.
        assertEquals("earpiece", pick(listOf(ear, speaker, bt), video = false, chosen = "earpiece", room = true))
    }
}
