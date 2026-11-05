package io.github.ravenor907.muninn.net

/**
 * Honest ticks for the sender, in plain words (plain Kotlin, so the tests check the wording). "Delivered"
 * only once the other phone's own receipt says it has the message; until then, whose phone it's waiting for.
 * "Sent" alone would only mean a relay took it.
 */
object Ticks {
    /** "Anna's phone", or "their phone" without a name. */
    fun phone(name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it's phone" } ?: "their phone"

    /** A 1:1 message of mine in [state] ("sending", "sent", "delivered", "read"), to [name]. */
    fun private(state: String, name: String?): String = when (state) {
        "read" -> "Read"
        "delivered" -> "Delivered"
        "sent" -> "Waiting for ${phone(name)}"
        "sending" -> "Sending"
        else -> ""
    }

    /**
     * A group message of mine before anyone has read it: sent [to] these members, [delivered] to these, with
     * [name] for each. Everyone has it: "Delivered". One or two still missing: whose phones. More: how many.
     */
    fun group(to: Set<String>, delivered: Set<String>, name: (String) -> String): String {
        if (to.isEmpty()) return "Sent"
        val waiting = to - delivered
        if (waiting.isEmpty()) return "Delivered"
        val names = waiting.map(name).sorted()
        return when {
            names.size == 1 -> "Waiting for ${phone(names[0])}"
            names.size == 2 && delivered.isEmpty() -> "Waiting for ${names[0]}'s and ${names[1]}'s phones"
            delivered.isEmpty() -> "Waiting for ${names.size} phones"
            else -> "Delivered to ${(delivered intersect to).size} of ${to.size}"
        }
    }

    /** For the message's menu in a group: who has it, and whose phones it's still waiting for (empty lines left out). */
    fun groupLines(to: Set<String>, delivered: Set<String>, name: (String) -> String): List<String> {
        val have = (delivered intersect to).map(name).sorted()
        val waiting = (to - delivered).map(name).sorted()
        return listOfNotNull(
            if (have.isEmpty()) null else "Delivered to " + have.joinToString(", "),
            if (waiting.isEmpty()) null else if (waiting.size == 1) "Waiting for ${phone(waiting[0])}" else "Waiting for the phones of " + waiting.joinToString(", "),
        )
    }
}
