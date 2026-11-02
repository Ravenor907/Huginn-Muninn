package io.github.ravenor907.muninn.net

/**
 * Cap f2 (shared/docs/protocol.md, section 27): file sharing, the parts Muninn and Huginn must agree on (Huginn:
 * sharing.cpp, checked in --chat-selftest). Plain logic only (SharingTest).
 *  - the route a file takes: the same Wi-Fi first ("Direct"), else the internet ("Over the internet")
 *  - a group's file rules in its signed state: who may send ("fs") and the size limit ("fm", whole MB)
 *  - where a received file goes: Download/Muninn/<chat name>, with every name made safe
 *  - why a file was refused, in plain words
 */
object Sharing {
    const val CAP = "f2"
    const val EVERYONE = "everyone"
    const val ADMINS = "admins"
    const val NOBODY = "nobody"
    /** "fm": 1 to 4096 MB (4 GB, the most a file can be). */
    const val MAX_LIMIT_MB = 4096
    private const val MB = 1024L * 1024

    /**
     * The route for a file of [size]: "direct" (the same Wi-Fi), "internet" (straight, only between people who trust each
     * other with their addresses), "relay" (small files through the relays), or "" when none can take it.
     */
    fun route(sameWifi: Boolean, trusted: Boolean, size: Long, relayMax: Long): String = when {
        sameWifi -> "direct"
        trusted -> "internet"
        size in 1..relayMax -> "relay"
        else -> ""
    }

    /** What a transfer row says about its route. */
    fun routeLabel(route: String): String = if (route == "direct") "Direct" else "Over the internet"

    /** Who may send files in a group: "everyone", "admins" or "nobody" ([fs] as the state has it, "" when left out). */
    fun whoMaySend(fs: String): String = if (fs == ADMINS || fs == NOBODY) fs else EVERYONE

    /** Empty when [fs] and [fm] are well-formed (or left out: "" and 0), else what's wrong. */
    fun checkRules(fs: String?, fm: Long?): String = when {
        fs != null && fs != ADMINS && fs != NOBODY -> "fs"
        fm != null && fm !in 1..MAX_LIMIT_MB.toLong() -> "fm"
        else -> ""
    }

    /**
     * Empty when a file of [size] from [from] is welcome in a group (members [members], admins [admins], rules [fs] and
     * [fm]; fm 0: no limit), else why not in plain words. Every member's app checks it.
     */
    fun refusal(members: Collection<String>, admins: Collection<String>, fs: String, fm: Int, from: String, size: Long): String {
        if (from !in members) return "Refused: they aren't in this group."
        val who = whoMaySend(fs)
        if (who == NOBODY) return "Refused by the group's file rules: nobody can send files."
        if (who == ADMINS && from !in admins) return "Refused by the group's file rules: only admins can send files."
        if (fm > 0 && size > fm * MB) return "Refused by the group's file rules: files up to $fm MB."
        return ""
    }

    /** "Everyone can send files", "Only admins can send files · Up to 25 MB", "Nobody can send files". */
    fun rulesLine(fs: String, fm: Int): String {
        val who = whoMaySend(fs)
        val line = when (who) { NOBODY -> "Nobody can send files"; ADMINS -> "Only admins can send files"; else -> "Everyone can send files" }
        return if (fm > 0 && who != NOBODY) "$line · Up to $fm MB" else line
    }

    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$")

    /**
     * One safe file or folder name: no folders, "..", control characters, characters Windows refuses, leading or trailing
     * dots or spaces, or reserved names (CON, NUL, COM1...). [fallback] when nothing is left.
     */
    fun safeName(name: String, fallback: String): String {
        val sb = StringBuilder()
        for (c in name) sb.append(if (c.code < 0x20 || c.code == 0x7f || c in "/\\:*?\"<>|") '_' else c)
        var out = sb.toString().trim()
        // At most 120 characters, never half of a surrogate pair.
        if (out.codePointCount(0, out.length) > 120) out = out.substring(0, out.offsetByCodePoints(0, 120))
        out = out.trimStart('.', ' ').trimEnd('.', ' ')
        if (out.isEmpty()) return fallback
        val stem = out.substringBefore('.').trim().uppercase()
        val numbered = stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) && stem[3] in '1'..'9'
        return if (stem in RESERVED || numbered) "_$out" else out
    }

    /** The folder a chat's files go in, under Downloads: "Muninn/<chat name, made safe>". */
    fun folderFor(app: String, chatName: String): String = safeName(app, "Muninn") + "/" + safeName(chatName, "Chat")

    /** A file's kind in a few words, from its name. */
    fun kindOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "PDF document"
        "doc", "docx", "odt", "rtf", "txt", "md", "pages" -> "Document"
        "xls", "xlsx", "ods", "csv" -> "Spreadsheet"
        "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp" -> "Picture"
        "mp4", "m4v", "mov", "mkv", "webm", "avi" -> "Video"
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac" -> "Sound"
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2" -> "Archive"
        "apk", "apks", "xapk", "apkm" -> "App installer"
        "exe", "msi", "bat", "cmd", "ps1", "sh", "appimage", "flatpak", "flatpakref" -> "Program"
        else -> "File"
    }

    /** A decline's "why" (27.4) in plain words for the sender. */
    fun declineText(why: String): String = when (why) {
        "rules" -> "Refused by the group's file rules."
        "space" -> "Not enough space on their device."
        "no" -> "They declined."
        else -> "They declined, or Direct calls is off on their side."
    }
}
