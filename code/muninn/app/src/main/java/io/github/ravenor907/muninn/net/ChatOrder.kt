package io.github.ravenor907.muninn.net

/**
 * The chat list's order and its "New from" bar (plan 2.14). Pinned chats first, then unread ones newest first,
 * then the read ones as they were (by time, or where they were moved by hand). Huginn's list follows the same rules.
 */
object ChatOrder {
    /**
     * [shown]: a section as arranged (pinned first, then by time or by hand). The unread ones that aren't pinned
     * move up to just under the pinned ones, newest first ([last]: a chat's newest message time).
     */
    fun unreadUp(shown: List<String>, pinned: Collection<String>, unread: Set<String>, last: (String) -> Long): List<String> {
        val pins = shown.filter { it in pinned }
        val rest = shown.filter { it !in pinned }
        val up = rest.filter { it in unread }.sortedByDescending { last(it) }
        return pins + up + rest.filter { it !in unread }
    }

    /**
     * The bar's words for the unread chats' names, newest first: "New from Mom, Taylor +3". Two names at most, then
     * how many more. [names] false: "New messages", with no names. Nothing unread: "".
     */
    fun newFrom(newestFirst: List<String>, names: Boolean): String {
        if (newestFirst.isEmpty()) return ""
        if (!names) return "New messages"
        val more = newestFirst.size - 2
        return "New from " + newestFirst.take(2).joinToString(", ") + if (more > 0) " +$more" else ""
    }

    /**
     * Names on the bar: always inside the unlocked app. Only over the lock screen do they follow what
     * notifications show ([notifyNames] false: "Nothing" was chosen).
     */
    fun namesShown(overLockScreen: Boolean, notifyNames: Boolean): Boolean = !overLockScreen || notifyNames

    /** The bar shows while something unread came after its X was last pressed ([closedAt], 0 if never). */
    fun barShown(newestUnread: Long, closedAt: Long): Boolean = newestUnread > 0L && newestUnread > closedAt
}
