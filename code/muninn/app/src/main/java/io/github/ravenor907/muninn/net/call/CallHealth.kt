package io.github.ravenor907.muninn.net.call

import java.util.concurrent.atomic.AtomicLong

/**
 * A call's sound, counted, for a Kids' TV: when a call had none going out or none playing, the TV tells its parents
 * what happened (which microphone opened, how much sound went and came), so a silent call can be found from afar.
 * Numbers and Android's source numbers only: never anything said.
 */
class CallHealth {
    val sent = AtomicLong()      // 20 ms sound packets sent
    val came = AtomicLong()      // sound packets that arrived and opened
    val played = AtomicLong()    // frames written to the speaker
    @Volatile var mic = -1       // the microphone source that opened, -1 none
    @Volatile var writeErr = 0
    val tried = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** The note for the parents, or null when the sound went and played (or the call was too short to tell). */
    fun problem(secs: Long, rate: Int, playState: Int): String? {
        if (secs < 8) return null
        val sentOk = sent.get() > secs * 10      // at least half the 50 a second
        val playedOk = played.get() > secs * 10
        if (sentOk && playedOk) return null
        val mics = synchronized(tried) { tried.joinToString(", ") }.ifEmpty { "none tried" }
        return "TV call sound check (sent by Muninn): ${secs}s call. " +
            "Microphone: ${if (mic < 0) "none opened" else "source $mic"} ($mics). " +
            "Sound sent: ${sent.get()}. Sound arrived: ${came.get()}. Played: ${played.get()} at $rate Hz" +
            (if (writeErr != 0) ", speaker error $writeErr" else "") + ", speaker state $playState."
    }
}
