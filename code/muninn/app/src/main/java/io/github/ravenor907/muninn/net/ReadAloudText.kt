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
    const val MAX_TEXT = 4000
    /** The longest "word" read as one: longer runs are cut. */
    const val MAX_WORD = 40
    const val MAX_DOWNLOAD = 160L * 1024 * 1024
    const val MAX_FILE = 120L * 1024 * 1024
    /** The voices-1 release (a pre-release: the update checks never look at it). */
    const val DOWNLOAD = "https://github.com/Ravenor907/Huginn-Muninn/releases/download/voices-1/"

    /** The device's own voice, picked in Settings (never a natural voice, nothing downloads). Never sent to a Kids' Device. */
    const val DEVICE = "d"
    /** Settings' three choices, in order. */
    val CHOICES = listOf(FEMALE to "Female", MALE to "Male", DEVICE to "Device")

    /** "f" or "m"; anything else is Female. */
    fun voiceOf(v: String?): String = if (v == MALE) MALE else FEMALE

    /** Settings' pick: "f", "m" or "d"; anything else is Female. */
    fun choiceOf(v: String?): String = if (v == DEVICE) DEVICE else voiceOf(v)

    // ---- Read aloud speed (todo 1.42): a percent of the usual pace, 50% to 150%, 80% until it's moved.
    const val SPEED_MIN = 50f
    const val SPEED_MAX = 150f
    const val SPEED_DEFAULT = 80f

    /** [pct] held to 50 to 150; anything not a number (or none saved) is 80. Older saved ints read the same. */
    fun speedOf(pct: Number?): Float {
        val f = pct?.toFloat() ?: return SPEED_DEFAULT
        return if (f.isNaN() || f.isInfinite()) SPEED_DEFAULT else f.coerceIn(SPEED_MIN, SPEED_MAX)
    }

    /** What the slider shows: "83%" (rounded only for display). */
    fun speedText(pct: Float): String = "${Math.round(speedOf(pct))}%"

    /** The speech engine's speed for [pct] (1.0 the voice's usual pace; it divides the length scale by it). */
    fun engineSpeed(pct: Float): Float = speedOf(pct) / 100f

    /** Android TextToSpeech.setSpeechRate for [pct] (1.0 the usual rate). */
    fun deviceRate(pct: Float): Float = speedOf(pct) / 100f

    /** Qt QTextToSpeech's rate (-1 to 1, 0 the usual) for [pct]: 50% is -1, 100% is 0, 150% is 1 (Huginn does the same). */
    fun qtRate(pct: Float): Float = (speedOf(pct) - 100f) / 50f

    /** The line "Hear a sample" reads. */
    const val SAMPLE = "This is how your messages sound when they're read aloud."

    /** The release file for [voice]. */
    fun fileFor(voice: String): String = if (voice == MALE) "voice-male-1.tar" else "voice-female-1.tar"

    /** The voice's folder, with its release, so a later voices-2 never mixes with these files. */
    fun folderFor(voice: String): String = if (voice == MALE) "male-1" else "female-1"

    private val LINK = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val MARKS = Regex("[*~`|]+")
    private val QUOTE = Regex("""(^|\n)\s*>+\s?""")
    private val AT = Regex("""(^|\s)@([\p{L}\p{N}_])""")
    private val SPACES = Regex("[ \\t\\r\\u000B\\u000C\\u00A0]+")
    private val END = Regex("""(?<=[.!?…])\s+""")
    private val ONE_SPACE = Regex(" +")
    /** The punctuation the speech engine is given (with letters, digits and spaces). */
    private const val ENGINE_PUNCT = ".,!?;:'\"()-/%+"
    /** Markup that never reaches a speech engine. */
    private const val MARKUP = "<>[]{}\\"
    private val HASH_LINE = Regex("""^([0-9a-f]{64}) {2}([A-Za-z0-9._-]{1,128})$""")
    private val TOP = setOf("model.onnx", "tokens.txt", "MODEL_CARD", "natural-voices.txt", "espeak-ng-data")

    /** Emoji, pictographs, flags, skin tones, joiners, variation selectors, keycaps and tags: never read out. */
    fun dropped(c: Int): Boolean = c in 0x1F000..0x1FAFF || c in 0x2600..0x27BF || c in 0x2B00..0x2BFF || c in 0xFE00..0xFE0F ||
        c == 0x200D || c == 0x20E3 || c in 0xE0000..0xE007F

    /** Control and format characters, unpaired halves, private and unassigned ones, other spaces and markup: a space. */
    private fun blank(c: Int): Boolean {
        val ty = Character.getType(c)
        return ty == Character.CONTROL.toInt() || ty == Character.FORMAT.toInt() || ty == Character.SURROGATE.toInt() ||
            ty == Character.PRIVATE_USE.toInt() || ty == Character.UNASSIGNED.toInt() || ty == Character.LINE_SEPARATOR.toInt() ||
            ty == Character.PARAGRAPH_SEPARATOR.toInt() || ty == Character.SPACE_SEPARATOR.toInt() || (c < 0x80 && MARKUP.indexOf(c.toChar()) >= 0)
    }

    /**
     * What is read for a message: links as "link", no emoji, marks or @, no control characters or markup, words of at
     * most [MAX_WORD], spaces tidied, at most [MAX_TEXT] long; lines stay.
     */
    fun plain(text: String): String {
        var t = if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) else text
        t = LINK.replace(t, " link ")
        t = MARKS.replace(t, "")
        t = t.replace('_', ' ')
        t = QUOTE.replace(t, "$1")
        t = AT.replace(t, "$1$2")
        val out = StringBuilder(t.length)
        var run = 0
        t.codePoints().forEach { c ->
            when {
                dropped(c) -> {}
                c == '\n'.code -> { out.append('\n'); run = 0 }
                blank(c) -> { out.append(' '); run = 0 }
                else -> {
                    // Very long "words" are cut into pieces of MAX_WORD.
                    if (run == MAX_WORD) { out.append(' '); run = 0 }
                    out.appendCodePoint(c)
                    run++
                }
            }
        }
        return out.toString().split('\n').map { SPACES.replace(it, " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * What the natural voice's engine (and its eSpeak NG, native code inside Muninn) is given for one piece: Latin
     * letters (at most two accents each), digits 0 to 9, spaces and . , ! ? ; : ' " ( ) - / % + only; anything else is a
     * space. No brackets, angle brackets or backslashes, so eSpeak's phoneme input ([[ ]]) and SSML can't be reached.
     * The same as Huginn's ReadAloud::forEngine.
     */
    fun forEngine(piece: String): String {
        val out = StringBuilder(piece.length)
        var afterLetter = false
        var accents = 0
        piece.codePoints().forEach { c ->
            val ty = Character.getType(c)
            if (ty == Character.NON_SPACING_MARK.toInt() || ty == Character.COMBINING_SPACING_MARK.toInt() || ty == Character.ENCLOSING_MARK.toInt()) {
                // An accent on a letter (a typed "a" plus its ring): at most two on one letter.
                if (afterLetter && accents < 2) { out.appendCodePoint(c); accents++ }
                return@forEach
            }
            accents = 0
            afterLetter = false
            when {
                Character.isLetter(c) && Character.UnicodeScript.of(c) == Character.UnicodeScript.LATIN -> { out.appendCodePoint(c); afterLetter = true }
                c in '0'.code..'9'.code -> out.appendCodePoint(c)
                c == '&'.code -> out.append(" and ")
                c == 0x2018 || c == 0x2019 -> out.append('\'')
                c == 0x201C || c == 0x201D -> out.append('"')
                c == 0x2026 -> out.append("...")
                c < 0x80 && ENGINE_PUNCT.indexOf(c.toChar()) >= 0 -> out.appendCodePoint(c)
                else -> out.append(' ')
            }
        }
        return ONE_SPACE.replace(out, " ").trim()
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
