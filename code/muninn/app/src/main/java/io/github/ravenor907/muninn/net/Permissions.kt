package io.github.ravenor907.muninn.net

import org.json.JSONObject

/**
 * Small rules for what a person's app speaks and what they may do here, kept apart so they can be tested off the
 * phone. The choices themselves live on the contact (Contact.noCalls, noFiles, hideActive), in the encrypted contacts.
 */
object Permissions {
    /** What a group member's app speaks at least (shared/docs/protocol.md 3.7). */
    val GROUP_CAPS = setOf("r1", "g1", "c1")

    /** Tokens that bring others with them (1.1): c2 and v1 mean c1, m2 means m1. */
    private val IMPLIES = mapOf("c2" to setOf("c1"), "v1" to setOf("c1"), "m2" to setOf("m1"))

    fun has(caps: Set<String>, cap: String): Boolean = cap in caps || caps.any { cap in IMPLIES[it].orEmpty() }

    /**
     * A call signal that would ring this phone: a 1:1 offer that isn't inside a voice room (5.2), or a ring into a
     * group call (`vring`, 6.5). Everything else (answers, hang ups, room messages) still goes through, so a call
     * already going on ends cleanly.
     */
    fun isRing(json: String): Boolean = try {
        val o = JSONObject(json)
        val t = o.optString("t")
        (t == "offer" && !o.has("g")) || t == "vring"
    } catch (e: Exception) { false }
}
