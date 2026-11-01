package io.github.ravenor907.muninn.net

/**
 * What a file on its way says, in a chat and in Files: the same words as Huginn's FileLink::statusText and
 * LocalSend::paceText. Plain logic only (FileTextTest).
 */
object FileText {
    /** The most a file can be on the internet route (Photos.INTERNET_PIECES pieces). */
    val RELAY_MAX: Long get() = Photos.INTERNET_PIECES.toLong() * Photos.PIECE

    fun percent(done: Long, size: Long): Int = if (size <= 0) 0 else (done.coerceIn(0, size) * 100 / size).toInt()

    /** A straight transfer (FileLink.Progress): its state in a few words. [who]: the other person's name. */
    fun line(state: String, mine: Boolean, done: Long, size: Long, note: String, who: String): String = when (state) {
        "connecting" -> if (mine) "Getting ready" else "Receiving"
        "offered" -> "Waiting for $who to accept"
        "asking" -> "Wants to send you this"
        "waiting" -> "On mobile data. Download it?"
        "sending" -> if (!mine && note.isNotEmpty()) note else (if (mine) "Sending " else "Receiving ") + percent(done, size) + "%"
        "done" -> if (mine) "Sent · Saved in their Downloads" else "Saved to Downloads"
        "failed" -> note.ifEmpty { "Didn't go through" }
        else -> note
    }

    /** True while it can still be stopped. */
    fun running(state: String): Boolean = state == "connecting" || state == "offered" || state == "sending"

    /**
     * A file of mine that goes as a message over the internet route: [state] is the message's ("sending", "sent",
     * "delivered", "read"); [fraction] how much the route took so far, or null before anything did.
     */
    fun relayLine(state: String, failed: Boolean, fraction: Float?): String = when {
        state == "delivered" || state == "read" -> "Sent · Saved in their Downloads"
        state == "sent" -> "Sent · Arrives when they're online"
        failed -> "Not sent · Tap to retry"
        fraction != null -> "Sending " + (fraction.coerceIn(0f, 1f) * 100).toInt() + "%"
        else -> "Sending"
    }

    fun tooBig(): String = "Too big for the internet route: up to ${size(RELAY_MAX)}. Send it on the same Wi-Fi, or turn on Direct calls for each other."

    /** "2.1 MB/s · 12 s left"; empty until a second has passed and something moved. */
    fun pace(done: Long, total: Long, ms: Long): String {
        if (ms < 1000 || done <= 0) return ""
        val perSec = done * 1000 / ms
        if (perSec <= 0) return ""
        val speed = size(perSec) + "/s"
        if (total <= done) return speed
        return speed + " · " + left((total - done + perSec - 1) / perSec)
    }

    /** "12 s left", "3 min left", "1 h 5 min left". */
    fun left(seconds: Long): String = when {
        seconds < 60 -> "${seconds.coerceAtLeast(1)} s left"
        seconds < 3600 -> "${(seconds + 59) / 60} min left"
        else -> "${seconds / 3600} h ${(seconds % 3600 + 59) / 60} min left"
    }

    /** The same sizes as LocalSend.size. */
    fun size(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, b / 1073741824.0)
        b >= 1L shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, b / 1048576.0)
        b >= 1L shl 10 -> "%.0f KB".format(java.util.Locale.ROOT, b / 1024.0)
        else -> "$b bytes"
    }

    /** A type icon name for a file name (MIcon): picture, video, sound, or a plain file. */
    fun icon(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "image"
        "mp4", "m4v", "mov", "mkv", "webm", "avi" -> "video"
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac" -> "audio"
        "txt", "md", "pdf", "doc", "docx", "odt", "rtf" -> "text"
        else -> "file"
    }

    /** An app installer: never opened by itself, and on a Kids' TV not at all. */
    fun isApp(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("apk", "apks", "xapk", "apkm")
}
