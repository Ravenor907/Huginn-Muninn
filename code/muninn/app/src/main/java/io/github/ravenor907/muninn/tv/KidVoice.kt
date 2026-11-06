package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.net.ReadAloudText
import org.json.JSONObject

/**
 * Cap k15 (shared/docs/protocol.md 7.18): the read-aloud voice a parent picks on a Kids' Device's page, config field
 * "voice", "f" (Female, Alba) or "m" (Male, northern English). Sent only to a device whose app said "k15", once a parent
 * picked one; the device keeps the last one it took (net/ReadAloud) and downloads only that voice. Kept free of Android
 * so the unit tests run it.
 */
object KidVoice {
    const val CAP = "k15"
    const val FIELD = "voice"
    /**
     * 1.42: the read-aloud speed, a whole number from 50 to 150, sent beside "voice" only when the device and every
     * co-parent's app say [SPEED_CAP] (k16). 1.43: the device maps it into its 70% to 130% ([ReadAloudText.kidSpeed]:
     * 50 is 70%, 100 is 100%, 150 is 130%); the field itself is unchanged. A config without one: the device reads at 100%.
     */
    const val SPEED = "speed"
    /** Cap k16: the app reads the config field [SPEED]. */
    const val SPEED_CAP = "k16"
    /** Cap k17: the device's update channel, "pre" for Pre-release. Stable when it's left out. */
    const val UPDATES = "uch"
    const val UPDATES_PRE = "pre"
    const val UPDATES_CAP = "k17"
    val FIELDS = setOf(FIELD, SPEED, UPDATES)

    /** Whether config [c] puts the device on Pre-release. Anything but [UPDATES_PRE] is Stable, the safe side. */
    fun preRelease(c: JSONObject): Boolean = c.opt(UPDATES) == UPDATES_PRE

    /**
     * Throws IllegalArgumentException("voice") if config [c] has a "voice" that isn't "f" or "m", and ("speed") if it
     * has a "speed" that isn't a whole number from 50 to 150.
     */
    fun check(c: JSONObject) {
        if (c.has(FIELD)) require((c.get(FIELD) as? String) in ReadAloudText.VOICES) { FIELD }
        if (c.has(UPDATES)) require(c.get(UPDATES) == UPDATES_PRE) { UPDATES }
        if (c.has(SPEED)) {
            val v = c.get(SPEED)
            require(Canon.isInt(v) && (v as Number).toLong() in SPEED_MIN.toLong()..SPEED_MAX.toLong()) { SPEED }
        }
    }

    const val SPEED_MIN = 50
    const val SPEED_MAX = 150

    /** The speed config [c] carries (50 to 150, on the wire), or null when it has none: the device reads at 100%. */
    fun speedOf(c: JSONObject): Int? {
        val v = c.opt(SPEED) ?: return null
        if (!Canon.isInt(v)) return null
        return (v as Number).toLong().takeIf { it in SPEED_MIN.toLong()..SPEED_MAX.toLong() }?.toInt()
    }

    /** A parent's slider value (70% to 130%) as the whole number a config carries (50 to 150). */
    fun speedOut(pct: Float): Int = ReadAloudText.kidWire(pct)

    /**
     * What a parent's phone sends for the speed: [wire] (the config's number, 50 to 150; null: none set yet) only when
     * the device says k16 ([k16]) and every co-parent's app does too ([others]). Left out, the device reads at 100%.
     */
    fun speedToSend(wire: Int?, k16: Boolean, others: List<Set<String>?>): Int? =
        if (wire == null || !k16 || !allReadSpeed(others)) null else wire.coerceIn(SPEED_MIN, SPEED_MAX)

    /** The voice config [c] carries, or null when it has none (an older parent app, or none picked yet). */
    fun of(c: JSONObject): String? = (c.opt(FIELD) as? String)?.takeIf { it in ReadAloudText.VOICES }

    /** What a parent's phone sends: the pick ("" none yet) only to a device with [k15]. */
    fun toSend(pick: String, k15: Boolean): String? = pick.takeIf { k15 && it in ReadAloudText.VOICES }?.let { ReadAloudText.voiceOf(it) }   // Male is retired

    /**
     * Round 19: and only when every other parent's app reads it too ([others]: each co-parent's caps, null when not a
     * contact here). Every parent checks the device's config with its own field list, and an older app (1.41) turns down
     * one with "voice": it would stop seeing the device's settings and its next change would undo the others'. Left out,
     * the device keeps the last voice it took.
     */
    fun toSend(pick: String, k15: Boolean, others: List<Set<String>?>): String? = toSend(pick, k15 && allRead(others))

    /** Whether every co-parent ([others]' caps) says [CAP]. */
    fun allRead(others: List<Set<String>?>): Boolean = others.all { it != null && CAP in it }

    /** Whether every co-parent ([others]' caps) says [SPEED_CAP] (k16), so their app reads "speed". */
    fun allReadSpeed(others: List<Set<String>?>): Boolean = others.all { it != null && SPEED_CAP in it }

    /** The device page's two choices. */
    val CHOICES = listOf(ReadAloudText.FEMALE to "Female")
}
