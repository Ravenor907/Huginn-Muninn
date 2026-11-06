package io.github.ravenor907.muninn.net

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Labs: what the app did lately, for a bug report (hold the raven 7 seconds). In memory only, the last [MAX] lines, and
 * only while Labs mode is on. It says what happened (screens, messages and files moving, calls, what the app said),
 * never what anyone wrote, no keys, no addresses.
 */
object ActivityLog {
    const val MAX = 600
    /** At most this much of it goes with a report. */
    const val MAX_CHARS = 24_000
    @Volatile var on = false
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun add(tag: String, text: String) {
        if (!on) return
        val line = synchronized(clock) { clock.format(Date()) } + " " + tag + ": " + text.replace('\n', ' ').take(240)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    /** A short, safe form of an id ("3fa2c1"). */
    fun id(s: String) = s.filter { it.isLetterOrDigit() }.take(6)

    /** The newest lines that fit [MAX_CHARS] after [head] (the device and app facts), oldest first. */
    fun dump(head: List<String>): String {
        val all = synchronized(lines) { lines.toList() }
        val top = head.joinToString("\n")
        var room = MAX_CHARS - top.length - 1
        val keep = ArrayDeque<String>()
        for (l in all.asReversed()) { if (l.length + 1 > room) break; keep.addFirst(l); room -= l.length + 1 }
        return top + "\n" + keep.joinToString("\n")
    }
}
