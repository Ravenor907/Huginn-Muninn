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
    val FIELDS = setOf(FIELD)

    /** Throws IllegalArgumentException("voice") if config [c] has a "voice" that isn't "f" or "m". */
    fun check(c: JSONObject) {
        if (c.has(FIELD)) require((c.get(FIELD) as? String) in ReadAloudText.VOICES) { FIELD }
    }

    /** The voice config [c] carries, or null when it has none (an older parent app, or none picked yet). */
    fun of(c: JSONObject): String? = (c.opt(FIELD) as? String)?.takeIf { it in ReadAloudText.VOICES }

    /** What a parent's phone sends: the pick ("" none yet) only to a device with [k15]. */
    fun toSend(pick: String, k15: Boolean): String? = pick.takeIf { k15 && it in ReadAloudText.VOICES }

    /** The device page's two choices. */
    val CHOICES = listOf(ReadAloudText.FEMALE to "Female", ReadAloudText.MALE to "Male")
}
