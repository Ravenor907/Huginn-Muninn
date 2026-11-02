package io.github.ravenor907.muninn.net

/**
 * When to send again, confirm, connect again and catch up. Plain Kotlin (no Android), like [PowerLogic], so the
 * tests can check every case.
 *
 * - A message not yet confirmed goes again after 5 s, 10 s, 20 s, 1 min, then every 5 min, for a day.
 * - Once at least [HELD_RELAYS] relays said they stored it, it waits there for the other phone: it only goes to
 *   the relays again 1 hour and 6 hours later ([HELD_AGAIN_MS]). It still goes at once when the other phone
 *   shows up close by or on the same Wi-Fi.
 * - "Delivered" for the same message goes back at most once every [RECEIPT_GAP_MS], and once per photo, not
 *   once per piece.
 * - A relay that drops waits 2 s, 4 s, 8 s ... up to a minute. Failures in a row across all relays slow down
 *   swapping too, so a phone with no signal doesn't try one relay after another without a pause.
 * - On connecting, a relay is asked only for what came since it last finished sending (less [SINCE_SLACK_S]
 *   for blurred times), never more than [SINCE_MAX_S] back.
 */
object NetLogic {
    /** Wait before each new try: 5 s, 10 s, 20 s, 1 min, then every 5 min. */
    val RETRY = longArrayOf(5_000, 10_000, 20_000, 60_000, 300_000)
    /** Relays that must say they stored a message before it stops being sent every few minutes. */
    const val HELD_RELAYS = 2
    /** Once relays hold it: sent to them again this long after they first did (1 hour, then 6 hours). */
    val HELD_AGAIN_MS = longArrayOf(3_600_000L, 6 * 3_600_000L)
    /** Nothing more is due (until the other phone shows up close by, or the day of trying is over). */
    const val NEVER = Long.MAX_VALUE

    /**
     * When the next try is due, after try number [count] at [now]. [heldAt]: when relays first held it (0: not
     * yet).
     */
    fun next(count: Int, now: Long, heldAt: Long): Long =
        if (heldAt <= 0) now + RETRY[(count - 1).coerceIn(0, RETRY.size - 1)]
        else HELD_AGAIN_MS.map { heldAt + it }.firstOrNull { it > now } ?: NEVER

    /**
     * Whether a try at [now] also goes to the relays. Always until they hold it; after that only once each for
     * the 1 hour and 6 hour times that have come since it last went ([publishedAt]).
     */
    fun publish(heldAt: Long, publishedAt: Long, now: Long): Boolean =
        heldAt <= 0 || HELD_AGAIN_MS.any { heldAt + it in (publishedAt + 1)..now }

    /** "Delivered" for one message goes back at most this often (resends and photo pieces don't each get one). */
    const val RECEIPT_GAP_MS = 3 * 60_000L

    /** A receipt for this message may go now: none went yet, or the last one went [lastAt] long enough ago. */
    fun receiptDue(lastAt: Long?, now: Long): Boolean = lastAt == null || now - lastAt >= RECEIPT_GAP_MS || now < lastAt

    /** The longest wait before connecting to a relay again. */
    const val MAX_BACKOFF_MS = 60_000L

    /** One relay failed [n] times in a row: wait 2 s, 4 s, 8 s ... up to a minute. */
    fun retryMs(n: Int): Long = minOf(MAX_BACKOFF_MS, 1000L shl n.coerceIn(1, 6))

    /**
     * [streak] failures in a row across every relay: how long to wait before another relay takes the place of
     * one that failed. The first swap is at once, then 2 s, 4 s ... up to a minute.
     */
    fun swapMs(streak: Int): Long = if (streak <= 1) 0L else minOf(MAX_BACKOFF_MS, 1000L shl minOf(streak - 1, 6))

    /** Wait before connecting [url]'s relay again: the longer of its own wait and the swap wait. */
    fun reconnectMs(n: Int, streak: Int): Long = maxOf(retryMs(n), swapMs(streak))

    /** Events can be backdated up to 10 minutes (see [Nostr.publish]): look back twice that past the last finish. */
    const val SINCE_SLACK_S = 20 * 60L
    /**
     * Never ask for more than the longest message limit (and a day for blurred times) back: nothing older is kept
     * anywhere, and a phone or TV offline for days still gets everything that waits for it.
     */
    const val SINCE_MAX_S = (Limits.LONGEST + 1) * 86_400L

    /** What to ask a relay for, in seconds: since it last finished sending ([eoseAt], 0: never) less the slack. */
    fun since(eoseAt: Long, nowS: Long): Long = maxOf(0L, nowS - SINCE_MAX_S, eoseAt - SINCE_SLACK_S)

    /**
     * A relay's EOSE cursor may move on: its inbox request finished ([subscribed]), nothing it handed over was left
     * untaken ([refusedOn] holds the relays that did, each on its own), and no older page is still coming ([paging]).
     */
    fun cursorMoves(url: String, subscribed: Boolean, refusedOn: Set<String>, paging: Boolean): Boolean =
        subscribed && url !in refusedOn && !paging

    /**
     * Locked: one more envelope of [size] characters may be kept for after unlocking, with [count] kept already
     * ([total] characters in all), within [maxCount] and [maxTotal]. A single envelope bigger than the whole store never is.
     */
    fun mayHold(count: Int, total: Long, size: Long, maxCount: Int, maxTotal: Long): Boolean =
        count < maxCount && size >= 0 && total + size <= maxTotal

    /** A socket heard from this recently is alive: the keepalive check skips it. */
    const val ALIVE_MS = 60_000L

    fun aliveRecently(heardAgoMs: Long): Boolean = heardAgoMs in 0 until ALIVE_MS

    /**
     * The battery's step for saving (0 fine, 1 below [PowerLogic.LOW_BATTERY], 2 below [PowerLogic.VERY_LOW]), or
     * -1 while charging. Android says the level often; only a change of step (or the plug) is worth acting on.
     */
    fun batteryStep(percent: Int, plugged: Boolean): Int = when {
        plugged -> -1
        percent < PowerLogic.VERY_LOW -> 2
        percent < PowerLogic.LOW_BATTERY -> 1
        else -> 0
    }

    /**
     * A message that arrived in chat [key] opens the conversation window: a 1:1 message does (from anyone, the
     * "Always right away" list included), a group message never does. My own sends always do (Power).
     */
    fun opensTalk(key: String): Boolean = !key.startsWith(GROUP_KEY)

    /** Chat keys of groups start with this (Folders.groupKey). */
    const val GROUP_KEY = "g:"

    /** Relay-side checks (pings) an hour, from [pings] counted over [ms] connected. */
    fun perHour(pings: Int, ms: Long): Int = if (ms <= 0) 0 else (pings * 3_600_000L / ms).toInt()
}
