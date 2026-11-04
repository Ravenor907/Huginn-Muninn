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
 * them. A restart sets that time back to zero, so the time left is kept as well, with the wall-clock end: after a
 * restart the wait goes on from the longer of the two readings, so a restart can pause a wait but never shorten it.
 * Lock prompts inside the open app (own chats, Confirm it's you) never count toward the waits or the erase.
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

    /** No pattern shorter than this was ever allowed, so a shorter drawing (a pocket, a toddler) is never counted or checked. */
    const val SHORTEST_PATTERN = 4

    /** Wrong tries in a row at a prompt inside the open app before Muninn locks (they're never counted on disk). */
    const val IN_APP_LOCK_AT = STEP

    /**
     * A wait: ends at [until] (time since start-up), is [wait] long, began in start-up number [boot]. [left]: ms left when
     * it was last saved (-1: not known, a file from before), [wallEnd]: when it ends by the wall clock (0: not known).
     */
    data class State(val until: Long, val wait: Long, val boot: Int, val left: Long = -1L, val wallEnd: Long = 0L)

    /**
     * How long is left, in ms. Another start-up since: the longer of what was left when last saved less the time since
     * this start-up (the least time that can have passed) and the wall clock's reading, never more than the wait.
     */
    fun left(s: State, now: Long, boot: Int, wall: Long = 0L): Long = when {
        s.wait <= 0L -> 0L
        boot != s.boot -> if (s.left < 0L) s.wait else
            maxOf(s.left - now.coerceAtLeast(0L), if (s.wallEnd > 0L) s.wallEnd - wall else 0L).coerceIn(0L, s.wait)
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
 * move) and mirrored in Muninn's own settings. Every save numbers both copies ([Data.seq]) and the newer one is used, so
 * a save cut short between the two can't bring back an older count (a right try stays right), and removing one copy
 * doesn't reset anything. Every change is written to disk (and flushed) before it returns. Plain JVM, so the rules can
 * be tested off the phone.
 *
 * The try that would erase is marked as being checked ([Data.checking]) until its answer is known. Its answer is saved
 * the moment the check ends, before anything shows it, so Muninn stopped in the middle learns nothing from it: that try
 * is given back at the next start ([settle]) instead of erasing on a try that may have been right.
 */
class LockStore(private val file: File, private val clock: Clock, private val mirror: Mirror? = null) {
    /** Time since start-up (ms), the start-up count, and the wall clock (only a second reading after a restart). */
    interface Clock { fun now(): Long; fun boot(): Int; fun wall(): Long = System.currentTimeMillis() }
    /** A second place the count is kept. write() must be on disk when it returns. */
    interface Mirror { fun read(): Data?; fun write(d: Data) }

    /**
     * [seq]: which save this is (the higher one wins). [left]: ms of the wait left when last saved (-1: not known).
     * [wallEnd]: when the wait ends by the wall clock. [checking]: the erasing try is being checked.
     */
    data class Data(val fails: Int = 0, val until: Long = 0L, val wait: Long = 0L, val boot: Int = -1, val erase: Boolean = false,
                    val seq: Long = 0L, val left: Long = -1L, val wallEnd: Long = 0L, val checking: Boolean = false)

    /** A try counted before its check: [fails] now, [erase] as set, [lockApp] when an in-app prompt should lock Muninn now. */
    class Begun(val fails: Int, val erase: Boolean, val lockApp: Boolean, val inApp: Boolean = false)

    /** Wrong tries in a row at prompts inside the open app: in memory only, never toward a wait or the erase. */
    @Volatile private var inAppFails = 0
    private val inAppLock = Any()
    /** A check started in this process hasn't answered yet (so its mark isn't left over from a stopped Muninn). */
    @Volatile private var inFlight = false

    @Synchronized
    fun load(): Data {
        val f = (SafeFile.read(file, bakWhenMissing = true) { parse(it) } as? SafeFile.Read.Ok)?.value
        val m = try { mirror?.read() } catch (_: Exception) { null }
        return when {
            f == null -> m ?: Data()
            m == null -> f
            // The newer save wins. Copies from before numbering (both 0): the higher count, as before.
            m.seq != f.seq -> if (m.seq > f.seq) m else f
            else -> (if (m.fails > f.fails) m else f).let { it.copy(erase = f.erase || m.erase) }
        }
    }

    private fun save(d: Data) {
        val n = d.copy(seq = d.seq + 1)
        val o = JSONObject().put("fails", n.fails).put("until", n.until).put("wait", n.wait).put("boot", n.boot).put("erase", n.erase)
            .put("seq", n.seq).put("left", n.left).put("wallEnd", n.wallEnd).put("checking", n.checking)
        SafeFile.writeMirror(file, o.toString().toByteArray())
        mirror?.write(n)
    }

    private fun parse(b: ByteArray): Data? = try {
        val o = JSONObject(String(b))
        Data(o.getInt("fails"), o.optLong("until"), o.optLong("wait"), o.optInt("boot", -1), o.optBoolean("erase"),
            o.optLong("seq", 0L), o.optLong("left", -1L), o.optLong("wallEnd", 0L), o.optBoolean("checking", false))
    } catch (_: Exception) { null }

    /** Wrong tries in a row so far. */
    fun fails(): Int = load().fails

    /** "Erase after too many tries" (off unless turned on). */
    var erase: Boolean
        get() = load().erase
        set(v) = synchronized(this) { save(load().copy(erase = v)) }

    /** Ms left of the current wait (0: none). Restarted since: what was left, never shortened by the restart. */
    @Synchronized
    fun left(): Long {
        val d = load()
        val boot = clock.boot()
        val now = clock.now()
        val wall = clock.wall()
        val l = LockWait.left(LockWait.State(d.until, d.wait, d.boot, d.left, d.wallEnd), now, boot, wall)
        when {
            // Over: forgotten, so a restart later can't bring it back.
            l <= 0 -> if (d.wait > 0) save(d.copy(until = 0L, wait = 0L, left = -1L, wallEnd = 0L))
            // A new start-up: timed from now in this one.
            d.boot != boot -> save(d.copy(until = now + l, boot = boot, left = l, wallEnd = wall + l))
            // What's left, kept at most a minute old, so a restart loses as little of the wait as it can.
            d.left < 0L || d.left - l >= SNAPSHOT_MS -> save(d.copy(left = l, wallEnd = wall + l))
        }
        return l
    }

    /** Saves what's left of a running wait now (the phone is shutting down, or Muninn goes to the background). */
    fun snapshot() { try { left() } catch (_: Exception) {} }

    /**
     * Counts a try and writes it to disk before the check runs, so stopping Muninn in the middle can't skip it (the wait
     * it brings is written too). Null while a wait runs (no try then). [right] or [wrong] answers it.
     * [inApp]: a prompt inside the open app (own chats, Confirm it's you): not counted on disk, never toward a wait or the
     * erase; after [LockWait.IN_APP_LOCK_AT] wrong in a row it asks Muninn to lock (lockApp), and the lock screen takes over.
     */
    fun begin(inApp: Boolean = false): Begun? {
        // In memory only, never waiting on the disk (asked on the main thread inside the open app).
        if (inApp) synchronized(inAppLock) {
            val n = inAppFails + 1
            inAppFails = if (n >= LockWait.IN_APP_LOCK_AT) 0 else n
            return Begun(n, false, n >= LockWait.IN_APP_LOCK_AT, inApp = true)
        }
        return beginCounted()
    }

    @Synchronized
    private fun beginCounted(): Begun? {
        settle()
        if (left() > 0) return null
        val d = load()
        val n = d.fails + 1
        val wait = LockWait.after(n, d.erase)
        val erasing = LockWait.erases(n, d.erase)
        val now = clock.now()
        val wall = clock.wall()
        save(if (wait > 0) d.copy(fails = n, until = now + wait, wait = wait, boot = clock.boot(), left = wait, wallEnd = wall + wait, checking = false)
            else d.copy(fails = n, checking = erasing))
        inFlight = true
        return Begun(n, d.erase, false)
    }

    /** The check of a counted try found it wrong: the count stays, and an erasing try is now known to be wrong. */
    @Synchronized
    fun wrong() {
        inFlight = false
        val d = load()
        if (d.checking) save(d.copy(checking = false))
    }

    /**
     * At the lock screen, before [eraseDue]: an erasing try whose check never answered (Muninn was stopped in the middle,
     * so nothing was learned from it) is given back. Not while a check runs in this process.
     */
    @Synchronized
    fun settle() {
        if (inFlight) return
        val d = load()
        if (d.checking) save(d.copy(fails = (d.fails - 1).coerceAtLeast(0), checking = false))
    }

    /** Everything should be erased now (erase on, enough wrong tries, the last one known to be wrong). */
    fun eraseDue(): Boolean = load().let { LockWait.erases(it.fails, it.erase) && !it.checking }

    /** The right lock: the count starts again (erase stays as set). */
    @Synchronized
    fun right() {
        inFlight = false
        inAppFails = 0
        val d = load()
        // Already clear: nothing to write (an in-app prompt, most of the time).
        if (d.fails == 0 && d.wait == 0L && !d.checking) return
        save(Data(erase = d.erase, seq = d.seq))
    }

    /** The lock removed: the count and the erase switch go too, so a lock set later starts with erase off. */
    @Synchronized
    fun reset() {
        inFlight = false
        inAppFails = 0
        save(Data(seq = load().seq))
    }

    private companion object { const val SNAPSHOT_MS = 60_000L }
}
