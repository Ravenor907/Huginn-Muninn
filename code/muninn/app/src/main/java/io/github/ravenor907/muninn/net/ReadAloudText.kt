package io.github.ravenor907.muninn.net

import java.io.File
import java.io.InputStream

/**
 * Read aloud's rules (code/shared/docs/voices.md), kept free of Android so the unit tests run them; Huginn's ReadAloud
 * (src/readaloud.cpp) follows the same ones and its self-test checks the same cases: the plain text a message is read
 * as, the pieces it's spoken in, the voices' hashes (code/shared/voices.sha256), the release files, and unpacking a
 * voice's tar safely.
 */
object ReadAloudText {
    /** The voices: Female (Alba, the default) and Male (northern English). */
    const val FEMALE = "f"
    const val MALE = "m"
    val VOICES = listOf(FEMALE, MALE)
    /** The longest piece spoken at once. */
    const val MAX_PIECE = 400
    /** The most of a message read. */
    const val MAX_TEXT = 6000
    const val MAX_DOWNLOAD = 160L * 1024 * 1024
    const val MAX_FILE = 120L * 1024 * 1024
    /** The voices-1 release (a pre-release: the update checks never look at it). */
    const val DOWNLOAD = "https://github.com/Ravenor907/Huginn-Muninn/releases/download/voices-1/"

    /** "f" or "m"; anything else is Female. */
    fun voiceOf(v: String?): String = if (v == MALE) MALE else FEMALE

    /** The release file for [voice]. */
    fun fileFor(voice: String): String = if (voice == MALE) "voice-male-1.tar" else "voice-female-1.tar"

    /** The voice's folder, with its release, so a later voices-2 never mixes with these files. */
    fun folderFor(voice: String): String = if (voice == MALE) "male-1" else "female-1"

    private val LINK = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("[*~`|]+")
    private val QUOTE = Regex("""(^|\n)\s*>+\s?""")
    private val AT = Regex("""(?U)(^|\s)@(\w)""")
    private val SPACES = Regex("[ \\t\\r\\u000B\\u000C\\u00A0]+")
    private val END = Regex("""(?<=[.!?…])\s+""")
    private val HASH_LINE = Regex("""^([0-9a-f]{64}) {2}([A-Za-z0-9._-]{1,128})$""")
    private val TOP = setOf("model.onnx", "tokens.txt", "MODEL_CARD", "natural-voices.txt", "espeak-ng-data")

    /** Emoji, pictographs, flags, skin tones, joiners, variation selectors, keycaps and tags: never read out. */
    fun dropped(c: Int): Boolean = c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0x2B00..0x2BFF || c in 0xFE00..0xFE0F ||
        c == 0x200D || c == 0x20E3 || c in 0xE0000..0xE007F

    /** What is read for a message: links as "link", no emoji, marks or @, spaces tidied; lines stay. */
    fun plain(text: String): String {
        var t = if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) else text
        t = LINK.replace(t, " link ")
        t = MARKS.replace(t, "")
        t = t.replace('_', ' ')
        t = QUOTE.replace(t, "$1")
        t = AT.replace(t, "$1$2")
        val out = StringBuilder(t.length)
        t.codePoints().forEach { c -> if (!dropped(c)) out.appendCodePoint(c) }
        return out.toString().split('\n').map { SPACES.replace(it, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /** [text] in pieces to speak one at a time: sentences and lines, none longer than [MAX_PIECE]. */
    fun pieces(text: String): List<String> {
        val out = ArrayList<String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            for (part in END.split(line)) {
                var s = part.trim()
                if (s.isEmpty()) continue
                while (s.length > MAX_PIECE) {
                    // Cut long runs at the last comma, else the last space, before the limit.
                    var cut = s.lastIndexOf(", ", MAX_PIECE - 1)
                    cut = if (cut > 0) cut + 1 else s.lastIndexOf(' ', MAX_PIECE - 1)
                    if (cut <= 0) cut = MAX_PIECE
                    out.add(s.substring(0, cut).trim())
                    s = s.substring(cut).trim()
                }
                if (s.isNotEmpty()) out.add(s)
            }
        }
        return out
    }

    /** code/shared/voices.sha256: file name to SHA-256 (lowercase hex). Comments and odd lines are skipped. */
    fun hashes(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.split('\n')) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            HASH_LINE.matchEntire(l)?.let { out[it.groupValues[2]] = it.groupValues[1] }
        }
        return out
    }

    /** Whether a name in a voice's tar is one to unpack: relative, plain, under the allowed names. */
    fun safeName(name: String): Boolean {
        if (name.isEmpty() || name.length > 200 || name.startsWith("/") || '\\' in name || ':' in name || '\u0000' in name) return false
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." }) return false
        return parts[0] in TOP && (parts.size == 1 || parts[0] == "espeak-ng-data")
    }

    /**
     * Unpacks a voice's tar (ustar) from [input] into [dir]: regular files and folders only, no "..", no absolute paths,
     * at most [MAX_FILE] a file. Null when it's done; otherwise why not (and [dir] may hold part of it).
     */
    fun unpack(input: InputStream, dir: File): String? {
        if (!dir.isDirectory && !dir.mkdirs()) return "can't make its folder"
        val h = ByteArray(512)
        val buf = ByteArray(1 shl 16)
        var files = 0
        fun readFully(b: ByteArray, n: Int): Boolean {
            var got = 0
            while (got < n) { val r = input.read(b, got, n - got); if (r < 0) return false; got += r }
            return true
        }
        fun field(at: Int, len: Int): String {
            var end = at
            while (end < at + len && h[end] != 0.toByte()) end++
            return String(h, at, end - at, Charsets.UTF_8)
        }
        while (true) {
            if (!readFully(h, 512)) return "it ends early"
            if (h.all { it == 0.toByte() }) break
            // The header's checksum: the sum of its bytes with the checksum field read as spaces.
            val sum = field(148, 8).trim().toLongOrNull(8) ?: return "a damaged header"
            var have = 0L
            for (i in 0 until 512) have += if (i in 148 until 156) 0x20 else (h[i].toInt() and 0xFF)
            if (sum != have) return "a damaged header"
            val size = field(124, 12).trim().toLongOrNull(8) ?: return "a damaged header"
            if (size < 0 || size > MAX_FILE) return "a file too big"
            val type = h[156].toInt().toChar()
            var name = field(0, 100)
            val prefix = field(345, 155)
            if (String(h, 257, 5, Charsets.US_ASCII) == "ustar" && prefix.isNotEmpty()) name = "$prefix/$name"
            name = name.trimEnd('/')
            if (!safeName(name)) return "an unexpected name"
            val target = File(dir, name)
            when (type) {
                '5' -> if (!target.isDirectory && !target.mkdirs()) return "can't make a folder"
                '0', '\u0000' -> {
                    target.parentFile?.mkdirs()
                    try {
                        target.outputStream().use { out ->
                            var left = size
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                                if (n < 0) return "it ends early"
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                    } catch (e: java.io.IOException) { return "can't write a file" }
                    files++
                    val pad = ((512 - size % 512) % 512).toInt()
                    if (pad > 0 && !readFully(ByteArray(pad), pad)) return "it ends early"
                }
                // Links, devices and anything else: never in a voice.
                else -> return "something that isn't a file"
            }
        }
        if (files == 0 || !File(dir, "model.onnx").isFile || !File(dir, "tokens.txt").isFile) return "parts of the voice are missing"
        return null
    }

    /** The voice in [dir] is all there. */
    fun complete(dir: File): Boolean =
        File(dir, "model.onnx").isFile && File(dir, "tokens.txt").isFile && File(dir, "espeak-ng-data/phontab").isFile
}
