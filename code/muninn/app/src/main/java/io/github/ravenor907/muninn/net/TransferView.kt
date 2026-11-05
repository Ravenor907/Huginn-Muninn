package io.github.ravenor907.muninn.net

/**
 * A file on its way, in one short banner: the strip over every screen on a phone or tablet, and the banner on a
 * Kids' TV (in Muninn, and over other apps). The nearby transfer (LocalSend) comes first, then a straight or internet
 * one still moving, then how the last one ended. Plain logic only (TransferViewTest).
 */
object TransferView {
    /**
     * [key]: the same for one transfer while it runs and after it ends, so hiding it hides just that one. [fraction]:
     * the bar (null: no bar). [running]: still going (an ended one goes by itself after a few seconds). [ok]: false
     * shows as a problem.
     */
    data class Banner(val key: String, val title: String, val line: String, val fraction: Float?, val running: Boolean, val ok: Boolean = true)

    /** How long an ended transfer stays up. */
    const val ENDED_MS = 8_000L

    /** "game.apk", or "3 files". */
    fun what(names: List<String>): String = when (names.size) {
        0 -> "Files"
        1 -> names[0]
        else -> "${names.size} files"
    }

    /**
     * The nearby transfer under way ([id]: its own). [preparing]: being copied and checked before it goes. [started]:
     * bytes began to move; until then a sender waits for [peer] to accept. Null when nothing runs.
     */
    fun nearby(id: String, active: Boolean, sending: Boolean, preparing: Boolean, started: Boolean, done: Long, total: Long,
               names: List<String>, peer: String): Banner? {
        if (!active) return null
        val pct = FileText.percent(done, total)
        val line = when {
            preparing -> "Getting ready"
            sending && !started -> if (peer.isEmpty()) "Waiting to accept" else "Waiting for $peer to accept"
            sending -> "Sending $pct%"
            else -> "Receiving $pct%"
        }
        val fraction = if (total > 0) (done.coerceIn(0, total).toFloat() / total) else 0f
        return Banner("nearby:$id", what(names), line, fraction, true)
    }

    /** How the last nearby transfer ([id]) ended: LocalSend's own words ("File from Dad: game.apk · Saved to Downloads"). */
    fun ended(id: String, ok: Boolean, message: String): Banner? =
        if (message.isEmpty()) null else Banner("ended:$id", message, "", null, false, ok)

    /** A straight or internet transfer ([FileLink.Progress]); [who]: the other person's name. Null while it waits for an answer here. */
    fun link(p: FileLink.Progress, who: String): Banner? {
        if (p.state == "asking" || p.state == "waiting") return null
        val running = FileText.running(p.state)
        val fraction = when {
            p.state == "sending" -> if (p.size > 0) p.done.coerceIn(0, p.size).toFloat() / p.size else 0f
            running -> 0f
            else -> null
        }
        return Banner("link:${p.id}:${if (running) "run" else p.state}", p.name,
            FileText.line(p.state, p.mine, p.done, p.size, p.note, who), fraction, running, p.state != "failed")
    }

    /**
     * The one banner to show: the nearby transfer, else a straight one still moving, else how the last nearby one
     * ended, else a straight one that just ended. [skipPeer]: that person's chat is open (it shows its own cards).
     * [hidden]: keys put away (Back, Close, or ended a while ago).
     */
    fun pick(nearby: Banner?, ended: Banner?, links: Collection<FileLink.Progress>, whoOf: (String) -> String,
             skipPeer: String = "", hidden: Set<String> = emptySet()): Banner? {
        val mine = links.filter { skipPeer.isEmpty() || it.peer != skipPeer }.mapNotNull { link(it, whoOf(it.peer)) }
        return (listOfNotNull(nearby) + mine.filter { it.running } + listOfNotNull(ended) + mine.filter { !it.running })
            .firstOrNull { it.key !in hidden }
    }
}
