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

    /**
     * Events can be backdated up to 10 minutes (see [Nostr.publish]), and the sender's clock may be off too (round 14):
     * look back the blur plus 30 minutes past the last finish. Whatever comes again is dropped as seen.
     */
    const val SINCE_SLACK_S = 40 * 60L

    /** At most this much (s) is believed of a relay's own clock against this device's. */
    const val RELAY_OFFSET_MAX_S = 86_400L

    /**
     * Round 14: a relay's EOSE cursor is kept in the relay's own time (its answer's Date), so this device's clock
     * being off doesn't move it: [localS] by this device's clock plus [relayOffsetS] (relay less local), when known.
     */
    fun cursor(localS: Long, relayOffsetS: Long?): Long =
        localS + (relayOffsetS?.takeIf { it in -RELAY_OFFSET_MAX_S..RELAY_OFFSET_MAX_S } ?: 0L)
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

    /**
     * A sender counts a message as held once [HELD_RELAYS] relays stored it, but those may not be the
     * relays this phone keeps open (one in the background, chosen by its own health), so what waits on the others has
     * to be read too: when this phone isn't listening on every relay, catching up and the inbox check read all of them.
     */
    fun sweepAll(keptOpen: Int, live: Int, total: Int): Boolean = total > 0 && (keptOpen < total || live < total)

    /** A relay ended the inbox request asking for a sign-in first (NIP-42 "auth-required:"). */
    fun authClosed(reason: String): Boolean = reason.startsWith("auth-required")

    /** A live socket whose inbox request isn't open (closed by the relay, or never answered) is asked again after this long. */
    const val REASK_AFTER_MS = 30_000L
    /** ... and at most this often per relay, so a relay that keeps refusing isn't asked on every check. */
    const val REASK_EVERY_MS = 120_000L

    /**
     * The keepalive check: a live socket that still answers but whose inbox request isn't open ([subscribed] false) is
     * asked for the inbox again, once it has been open [REASK_AFTER_MS] and wasn't asked within [REASK_EVERY_MS]
     * ([askedAgoMs] null: never asked again). Without this a closed request stayed closed while the socket looked alive.
     */
    fun reask(subscribed: Boolean, openAgoMs: Long, askedAgoMs: Long?): Boolean =
        !subscribed && openAgoMs >= REASK_AFTER_MS && (askedAgoMs == null || askedAgoMs !in 0 until REASK_EVERY_MS)

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

    /** A message no route has taken for this long, or a relay asking us to slow down, shows the calm "delayed" line. */
    const val DELAY_AFTER_MS = 20_000L
    const val LIMIT_SHOWS_MS = 120_000L

    /**
     * Whether "Messages are delayed; they'll send automatically" shows: something of mine has waited [oldestWaitingMs]
     * (null: nothing waits) for any route, or waits while a relay asked us to slow down [limitedAgoMs] ago (null: none did).
     */
    fun delayed(oldestWaitingMs: Long?, limitedAgoMs: Long?): Boolean =
        oldestWaitingMs != null && (oldestWaitingMs >= DELAY_AFTER_MS || (limitedAgoMs != null && limitedAgoMs in 0 until LIMIT_SHOWS_MS))

    /** Relay-side checks (pings) an hour, from [pings] counted over [ms] connected. */
    fun perHour(pings: Int, ms: Long): Int = if (ms <= 0) 0 else (pings * 3_600_000L / ms).toInt()
}

/**
 * Every relay gets each message, not just the first two that store it. A receiver in the background listens on one relay
 * chosen by its own health, so the sender keeps sending the same event (same id) to each relay that hasn't stored it yet,
 * on [RETRY_MS], until each has stored it or refused it for good. "Waiting for their phone" still starts at the first
 * relay's OK. A relay that can't be reached ([failed]) rests for [DOWN_MS]: nothing is sent to it until then, so one dead
 * relay never costs a connection per message. Plain Kotlin, so the tests check it.
 */
class RelaySpread(private val max: Int = MAX) {
    private class Owed(val total: Int, val left: MutableSet<String>, val rounds: Int)
    private val missing = LinkedHashMap<String, Owed>()
    private val fails = HashMap<String, Int>()
    private val downUntil = HashMap<String, Long>()

    /** Event [id] went to [relays]: each one owes an OK, for [rounds] tries more. The oldest is let go past [max]. */
    @Synchronized fun add(id: String, relays: Collection<String>, rounds: Int = RETRY_MS.size) {
        if (id.isEmpty() || relays.isEmpty()) return
        missing[id] = Owed(relays.size, relays.toMutableSet(), rounds)
        while (missing.size > max) missing.remove(missing.keys.first())
    }

    /** [url] stored [id]. True when every relay has it now, or it isn't tracked (nothing more to send). */
    @Synchronized fun stored(id: String, url: String): Boolean {
        val m = missing[id] ?: return true
        m.left.remove(url)
        if (m.left.isEmpty()) { missing.remove(id); return true }
        return false
    }

    /**
     * [url] answered "OK false" with [reason]. A duplicate counts as stored; a rate limit (or a reason that may pass) keeps
     * it owed; anything else means this relay won't take it, so it's dropped from the list. True when nothing is left.
     */
    @Synchronized fun refused(id: String, url: String, reason: String): Boolean {
        val m = missing[id] ?: return true
        if (reason.startsWith("duplicate") || refusedForGood(reason)) m.left.remove(url)
        if (m.left.isEmpty()) { missing.remove(id); return true }
        return false
    }

    /** The relays that still owe [id] an OK (empty: done or not tracked). */
    @Synchronized fun missing(id: String): Set<String> = missing[id]?.left?.toSet().orEmpty()

    /** The relays [id] goes to on its next try: the ones owing it that aren't resting. */
    @Synchronized fun due(id: String, now: Long): Set<String> = missing[id]?.left?.filter { !down(it, now) }?.toSet().orEmpty()

    @Synchronized fun tracked(id: String): Boolean = missing.containsKey(id)

    /** The wait before try [round] of [id], or null once its tries are used. */
    @Synchronized fun wait(id: String, round: Int): Long? = missing[id]?.let { if (round < it.rounds) retryMs(round) else null }

    /** At least one relay stored [id]. */
    @Synchronized fun anyStored(id: String): Boolean = missing[id]?.let { it.left.size < it.total } ?: true

    /** Given up on (all tries used): stops being sent. */
    @Synchronized fun forget(id: String) { missing.remove(id) }

    @Synchronized fun size(): Int = missing.size

    /** A sending socket to [url] failed to connect: [DOWN_AFTER] in a row and it rests for [DOWN_MS]. */
    @Synchronized fun failed(url: String, now: Long) {
        val n = (fails[url] ?: 0) + 1
        fails[url] = n
        if (n >= DOWN_AFTER) { downUntil[url] = now + DOWN_MS; fails[url] = 0 }
    }

    /** [url] answered: it's in use again. */
    @Synchronized fun reached(url: String) { fails.remove(url); downUntil.remove(url) }

    /** [url] is resting now. */
    @Synchronized fun down(url: String, now: Long): Boolean = (downUntil[url] ?: 0L) > now

    /** Who a new event goes to: every relay not resting, or all of them when every one is (one may be back). */
    @Synchronized fun targets(relays: List<String>, now: Long): List<String> = relays.filter { !down(it, now) }.ifEmpty { relays }

    companion object {
        const val MAX = 500
        /** When the relays still owing an OK get the same event again: after 10 s, 30 s, 1 min, 5 min and 15 min, then no more. */
        val RETRY_MS = longArrayOf(10_000L, 30_000L, 60_000L, 300_000L, 900_000L)
        /** Tries for what nobody waits on (a receipt, typing): the first three, then the message's own resend asks again. */
        const val LIGHT_ROUNDS = 3
        /** Failed connections in a row before a relay rests. */
        const val DOWN_AFTER = 3
        const val DOWN_MS = 10 * 60_000L

        /** The wait before try [round] (0 first), or null once every try was used. */
        fun retryMs(round: Int): Long? = RETRY_MS.getOrNull(round)

        /**
         * NIP-01 "OK false" prefixes after which this relay will never take this event: "blocked", "invalid", "pow" (needs
         * proof of work), "restricted", "error". "rate-limited", "auth-required" and anything unknown may pass later.
         */
        fun refusedForGood(reason: String): Boolean =
            listOf("blocked", "invalid", "pow", "restricted", "error").any { reason.startsWith("$it:") || reason == it }
    }
}
