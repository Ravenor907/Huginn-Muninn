package io.github.ravenor907.muninn.tv

/**
 * Daily Hávamál in the standard app (Settings › Look), not a Kids' Device: this device's own switch, off until it's
 * turned on; the day its card was hidden with X (it comes back the next day); the wording; and the day it came on, for
 * Earlier verses. Each device has its own: nothing here is synced to a linked device or sent anywhere. The verse of the
 * day is [Havamal.index], the same rule as on a Kids' Device. Kept through [get] and [put] (null removes a value), so
 * the unit tests run it on a map; the app keeps it in its settings (screens/DailyHavamal.kt).
 */
class HavOwn(private val get: (String) -> String?, private val put: (String, String?) -> Unit) {
    companion object {
        const val ON = "dailyHavOn"
        const val HIDDEN = "dailyHavHidden"
        const val WORDING = "dailyHavWording"
        const val START = "dailyHavStart"
        /** Off until it's turned on in Settings › Look. */
        const val DEFAULT_ON = false
    }

    /** The switch in Settings › Look. */
    val on: Boolean get() = get(ON)?.toBooleanStrictOrNull() ?: DEFAULT_ON

    /** Turned on: Earlier verses counts from [today]. Off: that and today's X are forgotten. */
    fun setOn(v: Boolean, today: Long) {
        put(ON, v.toString())
        if (v) { if (get(START)?.toLongOrNull() == null) put(START, today.toString()) }
        else { put(START, null); put(HIDDEN, null) }
    }

    /** X on the card: hidden for the rest of [today]. */
    fun hideToday(today: Long) = put(HIDDEN, today.toString())

    fun hiddenOn(today: Long): Boolean = get(HIDDEN)?.toLongOrNull() == today

    /** The card shows: switched on and not hidden today. */
    fun cardShown(today: Long): Boolean = on && !hiddenOn(today)

    /** [Havamal.BELLOWS] or [Havamal.MINE] (the translation imported on this device). Anything else reads as Bellows. */
    var wording: String
        get() = get(WORDING)?.takeIf { it in Havamal.WORDINGS } ?: Havamal.BELLOWS
        set(v) = put(WORDING, if (v == Havamal.MINE) Havamal.MINE else Havamal.BELLOWS)

    /** The day it came on (never after [today]: a clock set back starts it again from today). */
    fun start(today: Long): Long {
        val s = get(START)?.toLongOrNull()
        if (s != null && s <= today) return s
        put(START, today.toString())
        return today
    }
}
