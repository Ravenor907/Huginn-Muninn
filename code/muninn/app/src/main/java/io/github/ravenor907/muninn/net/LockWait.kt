package io.github.ravenor907.muninn.net

import org.json.JSONObject
import java.io.File

/**
 * Wrong tries at the lock (1.42). The pattern and the unlock password share one count, and a password-only lock
 * follows the same steps:
 *  - 5 wrong: wait 5 minutes; 5 more: 1 hour; 5 more: 12 hours; 5 more: 24 hours, and 24 hours again after every 5 more.
 *  - With "Erase after too many tries" on (off unless the person turns it on): after the 24-hour wait, 5 more wrong
 *    tries erase everything, the same as Erase everything.
 *  - A right try starts the count again.
 * Waits run on the phone's time since start-up, which can't be changed in Settings, so moving the clock doesn't skip
 * them. A restart sets that time back to zero, so the boot count is kept too: after a restart the wait starts again in full.
 */
object LockWait {
    const val STEP = 5
    /** With erase on: this many wrong tries in a row erase everything (5 after the 24-hour wait). */
    const val ERASE_AT = 25
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    /** The waits in order; the last one repeats. */
    val WAITS = longArrayOf(5 * MINUTE, HOUR, 12 * HOUR, 24 * HOUR)

    /** The wait that [fails] wrong tries bring, in ms. 0 when none is due (or when this try erases instead). */
    fun after(fails: Int, erase: Boolean = false): Long {
        if (fails <= 0 || fails % STEP != 0 || erases(fails, erase)) return 0L
        return WAITS[minOf(fails / STEP, WAITS.size) - 1]
    }

    /** [fails] wrong tries erase everything (only with erase on). */
    fun erases(fails: Int, erase: Boolean): Boolean = erase && fails >= ERASE_AT

    /** In the last stage (erase on, after the 24-hour wait): tries left before everything is erased, 1 to 5. 0 otherwise. */
    fun lastLeft(fails: Int, erase: Boolean): Int = if (erase && fails >= ERASE_AT - STEP && fails < ERASE_AT) ERASE_AT - fails else 0

    /**
     * The count a wrong try makes. [inApp]: asked inside the unlocked app (Change lock, Turn off the lock, linking...):
     * it counts toward the waits but never reaches the erase; at that point the count stays where it is and the app
     * locks instead (second: true).
     */
    fun next(fails: Int, erase: Boolean, inApp: Boolean): Pair<Int, Boolean> =
        if (inApp && erases(fails + 1, erase)) minOf(fails, ERASE_AT - 1) to true else fails + 1 to false

    /** A wait: ends at [until] (time since start-up), is [wait] long, began in start-up number [boot]. */
    data class State(val until: Long, val wait: Long, val boot: Int)

    /** How long is left, in ms. Another start-up since: the whole wait again. */
    fun left(s: State, now: Long, boot: Int): Long = when {
        s.wait <= 0L -> 0L
        boot != s.boot -> s.wait
        else -> (s.until - now).coerceIn(0L, s.wait)
    }

    // ------------------------------------------------------------------ words (never a total like "25")

    /** "5 minutes", "1 hour", "12 hours", "24 hours". */
    fun span(ms: Long): String = when {
        ms >= HOUR && ms % HOUR == 0L -> (ms / HOUR).let { if (it == 1L) "1 hour" else "$it hours" }
        else -> ((ms + MINUTE - 1) / MINUTE).let { if (it == 1L) "1 minute" else "$it minutes" }
    }

    /** Time left, rounded up: "23 hours 59 minutes", "4 minutes", "40 seconds". */
    fun leftWords(ms: Long): String {
        if (ms < MINUTE) return ((ms + 999) / 1000).coerceAtLeast(1).let { if (it == 1L) "1 second" else "$it seconds" }
        val mins = (ms + MINUTE - 1) / MINUTE
        val h = mins / 60
        val m = mins % 60
        val hours = if (h == 1L) "1 hour" else "$h hours"
        val minutes = if (m == 1L) "1 minute" else "$m minutes"
        return when { h == 0L -> minutes; m == 0L -> hours; else -> "$hours $minutes" }
    }

    /** During the wait [fails] wrong tries brought: "Wait 1 hour. Next: 12 hours after 5 more wrong tries." */
    fun waitText(fails: Int, erase: Boolean): String {
        val now = "Wait ${span(after(fails, erase))}."
        if (erases(fails + STEP, erase)) return "$now Next: the last 5 tries. If all 5 are wrong, everything is erased for good."
        val next = after(fails + STEP, erase)
        val again = if (next == after(fails, erase)) " again" else ""
        return "$now Next: ${span(next)}$again after 5 more wrong tries."
    }

    /** "Try again in 59 minutes." */
    fun leftText(ms: Long): String = "Try again in ${leftWords(ms)}."

    /**
     * Not waiting, after [fails] wrong tries: what the next ones bring. "" before the first wrong try. In the last
     * stage it counts down: "Last 3 tries. If all 3 are wrong, everything is erased for good."
     */
    fun triesText(fails: Int, erase: Boolean): String {
        val last = lastLeft(fails, erase)
        if (last == 1) return "Last try. If it's wrong, everything is erased for good."
        if (last > 1) return "Last $last tries. If all $last are wrong, everything is erased for good."
        if (fails <= 0) return ""
        val left = STEP - fails % STEP
        val wait = span(after(fails + left, erase))
        return if (left == 1) "1 more wrong try, then a wait of $wait." else "$left more wrong tries, then a wait of $wait."
    }

    /** The steps in plain words, for first setup and Settings. */
    const val EXPLAINER = "If someone keeps guessing, Muninn makes them wait: 5 wrong tries, 5 minutes. 5 more, 1 hour. " +
        "5 more, 12 hours. Then 24 hours after every 5 more. The right lock starts the count again."
}

/**
 * The count and the wait, kept in the vault's lock-state file (Muninn's private no-backup folder, never in a backup or a
 * move) and mirrored in Muninn's own settings: the higher count of the two is used, so removing one doesn't reset it.
 * Every change is written to disk (and flushed) before it returns. Plain JVM, so the rules can be tested off the phone.
 */
class LockStore(private val file: File, private val clock: Clock, private val mirror: Mirror? = null) {
    /** Time since start-up (ms) and the start-up count. */
    interface Clock { fun now(): Long; fun boot(): Int }
    /** A second place the count is kept. write() must be on disk when it returns. */
    interface Mirror { fun read(): Data?; fun write(d: Data) }

    data class Data(val fails: Int = 0, val until: Long = 0L, val wait: Long = 0L, val boot: Int = -1, val erase: Boolean = false)

    /** A try counted before its check: [fails] now, [erase] as set, [lockApp] when an in-app try stopped short of the erase. */
    class Begun(val fails: Int, val erase: Boolean, val lockApp: Boolean)

    @Synchronized
    fun load(): Data {
        val f = (SafeFile.read(file, bakWhenMissing = true) { parse(it) } as? SafeFile.Read.Ok)?.value
        val m = try { mirror?.read() } catch (_: Exception) { null }
        return when {
            f == null -> m ?: Data()
            m == null -> f
            else -> (if (m.fails > f.fails) m else f).let { it.copy(erase = f.erase || m.erase) }
        }
    }

    private fun save(d: Data) {
        val o = JSONObject().put("fails", d.fails).put("until", d.until).put("wait", d.wait).put("boot", d.boot).put("erase", d.erase)
        SafeFile.writeMirror(file, o.toString().toByteArray())
        mirror?.write(d)
    }

    private fun parse(b: ByteArray): Data? = try {
        val o = JSONObject(String(b))
        Data(o.getInt("fails"), o.optLong("until"), o.optLong("wait"), o.optInt("boot", -1), o.optBoolean("erase"))
    } catch (_: Exception) { null }

    /** Wrong tries in a row so far. */
    fun fails(): Int = load().fails

    /** "Erase after too many tries" (off unless turned on). */
    var erase: Boolean
        get() = load().erase
        set(v) = synchronized(this) { save(load().copy(erase = v)) }

    /** Ms left of the current wait (0: none). Restarted since: the whole wait again, from now. */
    @Synchronized
    fun left(): Long {
        val d = load()
        val boot = clock.boot()
        val now = clock.now()
        val l = LockWait.left(LockWait.State(d.until, d.wait, d.boot), now, boot)
        if (l > 0 && d.boot != boot) save(d.copy(until = now + l, boot = boot))
        // Over: forgotten, so a restart later can't bring it back.
        if (l <= 0 && d.wait > 0) save(d.copy(until = 0L, wait = 0L))
        return l
    }

    /**
     * Counts a try and writes it to disk before the check runs, so stopping Muninn in the middle can't skip it (the wait
     * it brings is written too). Null while a wait runs (no try then). [right] takes it back.
     */
    @Synchronized
    fun begin(inApp: Boolean = false): Begun? {
        if (left() > 0) return null
        val d = load()
        val (n, lockApp) = LockWait.next(d.fails, d.erase, inApp)
        val wait = if (n != d.fails) LockWait.after(n, d.erase) else 0L
        save(if (wait > 0) d.copy(fails = n, until = clock.now() + wait, wait = wait, boot = clock.boot()) else d.copy(fails = n))
        return Begun(n, d.erase, lockApp)
    }

    /** Everything should be erased now (erase on, enough wrong tries: also after Muninn was stopped mid-check). */
    fun eraseDue(): Boolean = load().let { LockWait.erases(it.fails, it.erase) }

    /** The right lock: the count starts again (erase stays as set). */
    @Synchronized
    fun right() { save(Data(erase = load().erase)) }
}
