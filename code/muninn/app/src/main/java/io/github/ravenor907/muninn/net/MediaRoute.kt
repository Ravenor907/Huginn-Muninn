package io.github.ravenor907.muninn.net

import java.time.Instant
import java.time.ZoneOffset

/**
 * How photos, videos and files travel (Settings › Files › Send media, and each chat's own choice), and what the call relay
 * may carry. Family and trusted contacts get the fewest limits: whatever they send arrives by itself at full quality,
 * straight to them when that works, otherwise through the call relay, encrypted end to end (cap f5, protocol.md 32).
 * Tor only when it's chosen, Ultra privacy is on, or someone outside Family and trusted contacts has no other way (the card
 * says so). Plain Kotlin, so the tests check it; Huginn's filelink.cpp follows the same rules.
 */
object MediaRoute {
    /** Their app can carry a file over a call relay allocation. Said after the first 48 caps (see Proto06.CAPS). */
    const val CAP = "f5"
    const val AUTO = "auto"
    const val FASTEST = "fastest"
    const val TOR = "tor"
    val CHOICES = listOf(AUTO, FASTEST, TOR)
    fun label(c: String) = when (c) { FASTEST -> "Fastest"; TOR -> "Most private (Tor)"; else -> "Automatic" }
    fun clean(c: String?) = if (c in CHOICES) c!! else AUTO

    /** A chat's own choice if it has one, else the one in Settings. */
    fun chosen(chat: String?, global: String?) = if (chat in CHOICES) chat!! else clean(global)

    /** Straight over the internet (never in Ultra privacy, never when Tor is the choice). */
    fun directMay(choice: String, ultra: Boolean) = !ultra && choice != TOR

    /** The call relay carries it: Family and trusted only, both apps able, never in Ultra privacy or past the month's limit. */
    fun relayMay(choice: String, ultra: Boolean, family: Boolean, peerCan: Boolean, over: Boolean) =
        !ultra && choice != TOR && family && peerCan && !over

    /** Tor may carry it: chosen, Ultra privacy, or nobody else can (not Family or trusted, or their app has no relay). */
    fun torMay(choice: String, ultra: Boolean, family: Boolean, peerCan: Boolean) = ultra || choice == TOR || !family || !peerCan

    /** How long the direct way gets before the relay is tried. */
    fun punchMs(choice: String, relay: Boolean) = if (relay && choice == FASTEST) FASTEST_PUNCH_MS else FileLink.PUNCH_MS
    const val FASTEST_PUNCH_MS = 3_000L

    /** Quiet this long on the direct way, it goes on through the relay. */
    const val SWITCH_MS = 6_000L
    /** How long a side waits for the other's relay address. */
    const val WAIT_MS = 45_000L
    /** A relay leg is renewed this long before its login ends. */
    const val RENEW_BEFORE_MS = 120_000L

    /** An offline person's file waits this long and goes when they're back. */
    const val FAMILY_WAIT_MS = 7 * 24 * 3_600_000L
    /** A stopped send to Family or a trusted contact tries again by itself after these waits, then each last one. */
    val AUTO_RETRY_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L)
    fun retryAfter(tries: Int): Long = AUTO_RETRY_MS[tries.coerceIn(0, AUTO_RETRY_MS.size - 1)]

    // ---- the month's relay use

    /** Past this in a month relayed sends go as the 720p copy, well under the free 1,000 GB. */
    const val LIMIT_BYTES = 800L * 1_000_000_000L
    /** Used is reported to the Worker at most this often, in bytes moved. */
    const val REPORT_EVERY_BYTES = 200L * 1_000_000L

    /** "2026-11", the month the count belongs to (UTC, as Cloudflare's billing month starts). */
    fun month(nowMs: Long): String = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).let { "%d-%02d".format(it.year, it.monthValue) }

    fun over(localBytes: Long, sharedBytes: Long) = maxOf(localBytes, sharedBytes) >= LIMIT_BYTES

    /** What Settings says, "" while it isn't worth saying. */
    fun meterNote(localBytes: Long, sharedBytes: Long): String {
        val used = maxOf(localBytes, sharedBytes)
        return when {
            over(localBytes, sharedBytes) -> "Sending through the relay is near its free limit this month, so relayed videos go as a 720p copy until next month."
            used >= 1_000_000_000L -> "Through the relay this month: ${used / 1_000_000_000L} GB of 800 GB."
            else -> ""
        }
    }
}
