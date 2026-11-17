package io.github.ravenor907.muninn.net.call

import java.util.concurrent.atomic.AtomicLong

/**
 * A call's sound, counted, for a Kids' TV: after a call that connected, the TV tells its parents what happened (which
 * microphone opened, how much real sound it heard, went, came and played, and where the speaker sound went), so a
 * silent call can be found from afar. Numbers and Android's source and device numbers only: never anything said.
 * Pure (no Android), so the unit tests can run it.
 */
class CallHealth {
    val micFrames = AtomicLong()    // 20 ms frames read from the microphone
    val micReal = AtomicLong()      // ...of them with any sound at all (not exact zeros)
    val micZero = AtomicLong()      // ...of them exact digital zeros (another app holds the microphone, or a gated mic)
    val sent = AtomicLong()         // 20 ms sound packets sent (silence goes as one packet every 400 ms)
    val came = AtomicLong()         // sound packets that arrived and opened
    val playedReal = AtomicLong()   // frames written to the speaker made from what arrived
    val playedSilence = AtomicLong() // frames written to the speaker with nothing arrived (silence)
    @Volatile var mic = -1          // the microphone source in use, -1 none
    @Volatile var writeErr = 0      // the last speaker write error (negative), 0 none
    @Volatile var readErr = 0       // the last microphone read error (negative), 0 none
    @Volatile var zerosSeen = false // a run of several seconds of exact zeros came from the microphone
    val micReopens = AtomicLong()   // the microphone closed and opened again mid-call (errors, zeros, stuck)
    val speakerRebuilds = AtomicLong() // the speaker made again mid-call (errors, stuck)
    @Volatile var speakerFallback = 0 // 0: the speaker as planned; 1: plain media; 2: the call's rate; -1: none could be made
    private val tried = LinkedHashMap<String, Int>()

    /** One try of microphone source [src]: "ok", "no open" or "no start". Kept as a short count per outcome. */
    fun tried(src: Int, what: String) = synchronized(tried) { val k = "$src $what"; tried[k] = (tried[k] ?: 0) + 1 }

    fun triedText(): String = synchronized(tried) { tried.entries.joinToString(",") { (k, n) -> if (n > 1) "$k x$n" else k } }.ifEmpty { "none" }

    /** What the speaker side was: [mode] the audio mode name, [usage] "media" or "voice", [route] the device it played on. */
    data class Setup(val mode: String, val usage: String, val route: String, val rate: Int, val playState: Int)

    /** What went wrong, in a few words each, or empty when the numbers look right for [secs] connected seconds. */
    fun problems(secs: Long): List<String> {
        val out = ArrayList<String>()
        val s = maxOf(1L, secs)
        if (mic < 0) out += "no microphone open"
        else {
            if (micFrames.get() < s * 25) out += "microphone gave little"
            if (micZero.get() * 2 > micFrames.get() && micFrames.get() > 0) out += "microphone gave zeros"
            else if (micReal.get() < s * 5) out += "microphone nearly silent"
        }
        if (sent.get() < s * 2) out += "little sent"
        if (came.get() < s * 2) out += "little arrived"
        else if (playedReal.get() * 2 < came.get()) out += "arrived but not played"
        if (playedReal.get() + playedSilence.get() < s * 25) out += "speaker took little"
        if (writeErr != 0) out += "speaker error $writeErr"
        if (speakerFallback < 0) out += "no speaker"
        return out
    }

    /**
     * The note for the parents, or null when it's not wanted: shorter than 8 connected seconds, or the numbers look
     * right and [always] is false. Numbers first, under [MAX_CHARS], so a chat message never cuts the end off.
     */
    fun report(secs: Long, setup: Setup, always: Boolean): String? {
        if (secs < 8) return null
        val p = problems(secs)
        if (p.isEmpty() && !always) return null
        val text = "$PREFIX${secs}s connected. " +
            (if (p.isEmpty()) "Looks fine." else "Problem: ${p.joinToString(", ")}.") + "\n" +
            "Out: mic frames ${micFrames.get()}, sound ${micReal.get()}, zeros ${micZero.get()}${if (zerosSeen) " (zero run seen)" else ""}, " +
            "sent ${sent.get()}.\n" +
            "In: arrived ${came.get()}, played sound ${playedReal.get()}, played silence ${playedSilence.get()}.\n" +
            "Mic: source ${if (mic < 0) "none" else "$mic ${sourceName(mic)}"}, reopened ${micReopens.get()}" +
            (if (readErr != 0) ", read error $readErr" else "") + ", tried ${triedText()}.\n" +
            "Speaker: ${setup.usage}, mode ${setup.mode}, out ${setup.route}, ${setup.rate} Hz, state ${setup.playState}" +
            ", rebuilt ${speakerRebuilds.get()}" + (if (speakerFallback != 0) ", fallback $speakerFallback" else "") +
            (if (writeErr != 0) ", error $writeErr" else "") + "."
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - 1) + "…"
    }

    companion object {
        /** How the note starts: the kids' own screen leaves these out (they're for the parents). */
        const val PREFIX = "TV call sound check (sent by Muninn): "
        /** Well under a private message's 2000 characters. */
        const val MAX_CHARS = 1500

        /** Android's microphone source numbers, named. */
        fun sourceName(src: Int): String = when (src) {
            0 -> "default"; 1 -> "mic"; 5 -> "camcorder"; 6 -> "voice recognition"; 7 -> "voice call"; 9 -> "unprocessed"
            else -> "other"
        }
    }
}
