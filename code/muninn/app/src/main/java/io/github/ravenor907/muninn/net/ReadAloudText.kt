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

    // ---- Read aloud speed (1.43): how fast words are spoken, 70% to 130% of the voice's usual pace, 100% until moved.
    const val SPEED_MIN = 70f
    const val SPEED_MAX = 130f
    const val SPEED_DEFAULT = 100f
    /** 1.42's default: a speed still saved at exactly this becomes [SPEED_DEFAULT] once. */
    const val OLD_SPEED_DEFAULT = 80f

    /** [pct] held to 70 to 130; anything not a number (or none saved) is 100. Older saved ints read the same. */
    fun speedOf(pct: Number?): Float {
        val f = pct?.toFloat() ?: return SPEED_DEFAULT
        return if (f.isNaN() || f.isInfinite()) SPEED_DEFAULT else f.coerceIn(SPEED_MIN, SPEED_MAX)
    }

    /** A speed saved before 1.43, read once: 80% (1.42's default) becomes 100%, anything else is kept, held to 70 to 130. */
    fun migrateSpeed(old: Number?): Float {
        val f = old?.toFloat() ?: return SPEED_DEFAULT
        return if (f == OLD_SPEED_DEFAULT) SPEED_DEFAULT else speedOf(f)
    }

    /** What the slider shows: "83%" (rounded only for display). */
    fun speedText(pct: Float): String = "${Math.round(speedOf(pct))}%"

    /** The speech engine's speed for [pct] (1.0 the voice's usual pace; it divides the length scale by it). */
    fun engineSpeed(pct: Float): Float = speedOf(pct) / 100f

    /** Android TextToSpeech.setSpeechRate for [pct] (1.0 the usual rate). */
    fun deviceRate(pct: Float): Float = speedOf(pct) / 100f

    /** Qt QTextToSpeech's rate (-1 to 1, 0 the usual) for [pct]: 100% is 0, 70% is -0.6, 130% is 0.6 (Huginn does the same). */
    fun qtRate(pct: Float): Float = (speedOf(pct) - 100f) / 50f

    /**
     * A Kids' Device's speed: the parent's config field "speed" (cap k16) still travels as a whole number from 50 to 150
     * (the caps list is full, so the field keeps its meaning on the wire). The device maps it into 70 to 130: 50 is 70,
     * 100 is 100, 150 is 130. None sent: 100.
     */
    fun kidSpeed(wire: Number?): Float {
        val f = wire?.toFloat() ?: return SPEED_DEFAULT
        if (f.isNaN() || f.isInfinite()) return SPEED_DEFAULT
        return speedOf(100f + (f.coerceIn(WIRE_MIN.toFloat(), WIRE_MAX.toFloat()) - 100f) * 0.6f)
    }

    /** What a parent's slider (70 to 130) sends as "speed": the whole number [kidSpeed] maps back to it. */
    fun kidWire(pct: Float): Int = Math.round(100f + (speedOf(pct) - 100f) / 0.6f).coerceIn(WIRE_MIN, WIRE_MAX)

    const val WIRE_MIN = 50
    const val WIRE_MAX = 150

    // ---- Cadence (1.43): the pauses between phrases and sentences, 50% to 200%, 100% until moved. Never stretches words.
    const val CADENCE_MIN = 50f
    const val CADENCE_MAX = 200f
    const val CADENCE_DEFAULT = 100f

    /** [pct] held to 50 to 200; anything not a number (or none saved) is 100. */
    fun cadenceOf(pct: Number?): Float {
        val f = pct?.toFloat() ?: return CADENCE_DEFAULT
        return if (f.isNaN() || f.isInfinite()) CADENCE_DEFAULT else f.coerceIn(CADENCE_MIN, CADENCE_MAX)
    }

    fun cadenceText(pct: Float): String = "${Math.round(cadenceOf(pct))}%"

    /** A pause of [ms] (at 100%) at cadence [pct]. */
    fun pauseMs(ms: Int, pct: Float): Int = Math.round(ms * cadenceOf(pct) / 100f)

    /** The line "Hear a sample" reads. */
    const val SAMPLE = "This is how your messages sound when they're read aloud."
    /** Read after [SAMPLE] by Hear a sample, so Cadence can be heard too. */
    const val SAMPLE_PAUSES = "Commas, full stops and new lines each get a short pause."

    /** The speech rules' one folder beside the voices (ReadAloud.sharedSpeech). */
    const val SPEECH_FOLDER = "speech"

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

    // ---- 1.43: the text before the voice. Phrases, our own pauses, plain words for numbers and shorthand.

    /** Our own pauses after a phrase, in ms at Cadence 100% ([pauseMs] scales them). */
    const val PAUSE_COMMA = 250
    const val PAUSE_STOP = 500
    const val PAUSE_LINE = 400
    const val PAUSE_ELLIPSIS = 500
    /** Between a Hávamál verse and "What it means". */
    const val PAUSE_MEANS = 900
    /** Between two messages read one after the other. */
    const val PAUSE_MESSAGE = 700
    /** A sentence longer than this many words is split at a natural break. */
    const val LONG_WORDS = 18
    /** "What it means", read between a verse and its meaning. */
    const val MEANS = "What it means."

    /** One phrase, given to the voice whole, and the pause after it (ms at Cadence 100%; 0 after the last). */
    data class Phrase(val text: String, val pause: Int)

    /** "..." and "…" while the text is split: a pause, never read. */
    private const val ELL = '\u0001'

    /**
     * What is spoken for a message: [plain], then [normalize], then (natural voices only) [respell], in phrases with our
     * own pauses after each: comma 250 ms, full stop, ? or ! 500, line break 400, "..." 500.
     */
    fun phrases(text: String, natural: Boolean): List<Phrase> {
        var t = normalize(plain(loneEmoji(text) ?: text))
        if (natural) t = respell(t)
        return split(t)
    }

    /** A Hávamál verse read aloud: the verse, 900 ms, "What it means", then the meaning (each of its lines a pause). */
    fun verse(verse: String, means: String, natural: Boolean): List<Phrase> {
        val out = ArrayList(phrases(verse, natural))
        if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(pause = PAUSE_MEANS)
        out.add(Phrase(MEANS, PAUSE_STOP))
        out.addAll(phrases(means, natural))
        return finish(out)
    }

    /** Several messages one after the other, 700 ms between them. */
    fun messages(texts: List<String>, natural: Boolean): List<Phrase> {
        val out = ArrayList<Phrase>()
        for (t in texts) {
            val p = phrases(t, natural)
            if (p.isEmpty()) continue
            if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(pause = maxOf(out.last().pause, PAUSE_MESSAGE))
            out.addAll(p)
        }
        return finish(out)
    }

    private fun finish(out: MutableList<Phrase>): List<Phrase> {
        if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(pause = 0)
        return out
    }

    private val CLOSERS = "\"')"

    /** [t] (already [normalize]d) in phrases: at sentence ends, commas, ; and :, "..." and line ends; long ones split again. */
    fun split(t: String): List<Phrase> {
        val out = ArrayList<Phrase>()
        fun add(raw: String, pause: Int) {
            val s = raw.trim()
            // Nothing to say (only marks): its pause goes to the phrase before.
            if (s.none { Character.isLetterOrDigit(it) }) {
                if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(pause = maxOf(out.last().pause, pause))
                return
            }
            val parts = longSplit(s)
            parts.forEachIndexed { i, p -> out.add(Phrase(p, if (i == parts.size - 1) pause else PAUSE_COMMA)) }
        }
        for (line in t.split('\n')) {
            val l = line.trim()
            if (l.isEmpty()) continue
            val start = out.size
            var from = 0
            var i = 0
            while (i < l.length) {
                val c = l[i]
                var pause = -1
                var end = i
                if (c == ELL) { pause = PAUSE_ELLIPSIS; end = i }
                else if (c == '.' || c == '!' || c == '?' || c == ',' || c == ';' || c == ':') {
                    var j = i
                    while (j + 1 < l.length && l[j + 1] in ".!?") j++
                    while (j + 1 < l.length && l[j + 1] in CLOSERS) j++
                    if (j + 1 >= l.length || l[j + 1] == ' ' || l[j + 1] == ELL) {
                        pause = if (c == ',' || c == ';' || c == ':') PAUSE_COMMA else PAUSE_STOP
                        end = j + 1
                        i = j
                    }
                }
                if (pause >= 0) {
                    add(l.substring(from, end), pause)
                    from = if (c == ELL) i + 1 else end
                }
                i++
            }
            if (from < l.length) add(l.substring(from), PAUSE_LINE)
            // The line's end: at least the line break's pause.
            if (out.size > start) out[out.size - 1] = out.last().copy(pause = maxOf(out.last().pause, PAUSE_LINE))
        }
        return finish(out)
    }

    private val BREAKS = Regex("""(?<=;)\s+|(?<=,)\s+(?=(?:and|but)\s)|\s+(?=because\s)""", RegexOption.IGNORE_CASE)

    /** A phrase over [LONG_WORDS] words cut at ", and", ", but", " because" and ";", then to at most [MAX_PIECE]. */
    private fun longSplit(s: String): List<String> {
        val words = s.split(' ').count { it.isNotBlank() }
        val first = if (words > LONG_WORDS) BREAKS.split(s).map { it.trim() }.filter { it.isNotEmpty() } else listOf(s)
        return first.flatMap { pieces(it) }
    }

    private val SQUOTES = Regex("[‘’‚‛]")
    private val DQUOTES = Regex("[“”„‟]")
    private val ELLIPSIS = Regex("""\.{3,}|…""")
    private val DOUBLE_DOT = Regex("""\.{2}""")
    private val SHORT = mapOf("lol" to "laughing out loud", "brb" to "be right back", "idk" to "I don't know",
        "omw" to "on my way", "tbh" to "to be honest", "imo" to "in my opinion", "ty" to "thank you", "np" to "no problem")
    private val SHORTHAND = Regex("""(?<![\p{L}\p{N}'])(lol|brb|idk|omw|tbh|imo|ty|np)(?![\p{L}\p{N}'])""", RegexOption.IGNORE_CASE)
    /** Titles before a name: never the end of a sentence. */
    private val TITLES = listOf(Regex("""(?<![\p{L}])Dr\.""") to "Doctor", Regex("""(?<![\p{L}])Mrs\.""") to "Missus",
        Regex("""(?<![\p{L}])Mr\.""") to "Mister", Regex("""(?<![\p{L}])Ms\.""") to "Miz",
        Regex("""(?<![\p{L}])St\.(?=\s+\p{Lu})""") to "Saint")
    /** Others: a full stop stays when a sentence ends with one. */
    private val ABBREVIATIONS = listOf(Regex("""(?<![\p{L}])St\.""") to "Street", Regex("""(?<![\p{L}])etc\.""", RegexOption.IGNORE_CASE) to "et cetera",
        Regex("""(?<![\p{L}])e\.g\.""", RegexOption.IGNORE_CASE) to "for example", Regex("""(?<![\p{L}])i\.e\.""", RegexOption.IGNORE_CASE) to "that is",
        Regex("""(?<![\p{L}])vs\.""", RegexOption.IGNORE_CASE) to "versus", Regex("""(?<![\p{L}])Jr\.""") to "Junior",
        Regex("""(?<![\p{L}])Sr\.""") to "Senior")
    private val ENDS_HERE = Regex("""^(?:\s*$|\s+[\p{Lu}"'])""")
    private val TIME = Regex("""(?<![\p{N}:.])([01]?\d|2[0-3]):([0-5]\d)(?![\p{N}:])(?:\s?([AaPp])\.?\s?[Mm]\.?(?![\p{L}]))?""")
    private val ORDINAL = Regex("""(?<![\p{L}\p{N}.,])(\d{1,9})(st|nd|rd|th)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
    private val MONEY = Regex("""([$\u00A3\u20AC])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?![\p{N}])""")
    private val NUMBER = Regex("""(?<![\p{L}\p{N}.,])(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d+))?(?![\p{N}])""")

    /**
     * Small, safe tidying before the voice (both apps the same): curly quotes made straight, "..." and "…" a pause, ".."
     * one full stop (never "dot"), titles and common abbreviations in words, texting shorthand spelled out, times
     * ("3:30" is "three thirty") and numbers in words.
     */
    fun normalize(text: String): String {
        var t = SQUOTES.replace(text, "'")
        t = DQUOTES.replace(t, "\"")
        t = ELLIPSIS.replace(t, " $ELL ")
        t = DOUBLE_DOT.replace(t, ".")
        for ((r, w) in TITLES) t = r.replace(t, w)
        for ((r, w) in ABBREVIATIONS) t = r.replace(t) { m -> if (ENDS_HERE.containsMatchIn(t.substring(m.range.last + 1))) "$w." else w }
        t = SHORTHAND.replace(t) { m -> SHORT[m.value.lowercase()] ?: m.value }
        t = TIME.replace(t) { m -> timeWords(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3]) }
        t = ORDINAL.replace(t) { m -> m.groupValues[1].toLongOrNull()?.let { ordinalWords(it) } ?: m.value }
        t = MONEY.replace(t) { m -> moneyWords(m.groupValues[1], m.groupValues[2].replace(",", ""), m.groupValues[3]) }
        t = NUMBER.replace(t) { m -> numberText(m.groupValues[1], m.groupValues[2]) }
        return t.split('\n').joinToString("\n") { ONE_SPACE.replace(it, " ").trim() }
    }

    private val ONES = arrayOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
        "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    /** [n] (0 to 999,999,999,999) in words: 1923 is "one thousand nine hundred twenty three". */
    fun numberWords(n: Long): String {
        if (n < 0) return "minus " + numberWords(-n)
        if (n < 20) return ONES[n.toInt()]
        if (n < 100) return TENS[(n / 10).toInt()] + if (n % 10 != 0L) " " + ONES[(n % 10).toInt()] else ""
        if (n < 1000) return ONES[(n / 100).toInt()] + " hundred" + if (n % 100 != 0L) " " + numberWords(n % 100) else ""
        for ((size, name) in listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1000L to "thousand")) {
            if (n >= size) return numberWords(n / size) + " " + name + if (n % size != 0L) " " + numberWords(n % size) else ""
        }
        return n.toString()
    }

    /** A year said the usual way: 1923 "nineteen twenty three", 1905 "nineteen oh five", 2007 "two thousand seven". */
    fun yearWords(n: Int): String {
        if (n in 2000..2009 || n % 1000 == 0) return numberWords(n.toLong())
        val hi = n / 100; val lo = n % 100
        return numberWords(hi.toLong()) + when { lo == 0 -> " hundred"; lo < 10 -> " oh " + ONES[lo]; else -> " " + numberWords(lo.toLong()) }
    }

    /** 1 "first", 22 "twenty second", 100 "one hundredth". */
    fun ordinalWords(n: Long): String {
        val w = numberWords(n)
        val cut = w.lastIndexOf(' ')
        val head = if (cut < 0) "" else w.substring(0, cut + 1)
        val last = w.substring(cut + 1)
        val ord = when (last) {
            "one" -> "first"; "two" -> "second"; "three" -> "third"; "five" -> "fifth"; "eight" -> "eighth"; "nine" -> "ninth"
            "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return head + ord
    }

    /** "3:30" is "three thirty", "3:05" "three oh five", "3:00" "three o'clock", "3:00 pm" "three pee em", "15:00" "fifteen hundred". */
    fun timeWords(h: Int, m: Int, ampm: String): String {
        val half = ampm.lowercase()
        val hour = if (half.isNotEmpty() && h == 0) "twelve" else numberWords(h.toLong())
        val mins = when {
            m == 0 && half.isNotEmpty() -> ""
            m == 0 -> if (h in 1..12) " o'clock" else " hundred"
            m < 10 -> " oh " + ONES[m]
            else -> " " + numberWords(m.toLong())
        }
        val tail = when (half) { "a" -> " ay em"; "p" -> " pee em"; else -> "" }
        return hour + mins + tail
    }

    /** "$15" is "fifteen dollars", "\u00A31.50" "one pound fifty" (the engine never sees the sign). */
    private fun moneyWords(sign: String, whole: String, cents: String): String {
        val n = whole.toLongOrNull() ?: return sign + whole
        val unit = when (sign) { "$" -> "dollar"; "\u00A3" -> "pound"; else -> "euro" }
        val c = cents.toIntOrNull() ?: 0
        return numberWords(n) + " " + unit + (if (n == 1L) "" else "s") + if (c > 0) " " + numberWords(c.toLong()) else ""
    }

    /** One number as found: digits (commas between thousands) and maybe a decimal part. */
    private fun numberText(whole: String, decimal: String): String {
        val digits = whole.replace(",", "")
        fun oneByOne(s: String) = s.map { ONES[it - '0'] }.joinToString(" ")
        val head = when {
            digits.length > 1 && digits.startsWith("0") -> oneByOne(digits)
            digits.length > 12 -> oneByOne(digits)
            decimal.isEmpty() && whole.length == 4 && digits.toInt().let { it in 1100..1999 || it in 2000..2099 } -> yearWords(digits.toInt())
            else -> numberWords(digits.toLong())
        }
        return if (decimal.isEmpty()) head else head + " point " + oneByOne(decimal.take(6))
    }

    /** Names the natural voices say wrongly, respelled for them only (never shown, never for the device's own voice). */
    val RESPELL = listOf("Muninn" to "Moo-nin", "Huginn" to "Hoo-gin", "Hávamál" to "Hah-vah-mawl", "Havamal" to "Hah-vah-mawl",
        "ere" to "air", "Othin" to "Oh-thin", "Loddfafnir" to "Lodd-fahv-neer")
    private val RESPELL_RX = RESPELL.map { (w, r) -> Regex("""(?<![\p{L}])${Regex.escape(w)}(?![\p{L}])""", RegexOption.IGNORE_CASE) to r }

    /** [RESPELL] applied, whole words, any case ("Muninn's" too). */
    fun respell(text: String): String {
        var t = text
        for ((rx, r) in RESPELL_RX) t = rx.replace(t) { m -> if (m.value.first().isLowerCase()) r.lowercase() else r.replaceFirstChar { it.uppercaseChar() } }
        return t
    }

    private val EMOJI_NAMES = mapOf(0x1F600 to "grinning face", 0x1F603 to "grinning face", 0x1F604 to "grinning face",
        0x1F601 to "beaming face", 0x1F642 to "smiling face", 0x1F60A to "smiling face", 0x263A to "smiling face",
        0x1F602 to "face with tears of joy", 0x1F923 to "rolling on the floor laughing", 0x1F606 to "laughing face",
        0x1F609 to "winking face", 0x1F60D to "smiling face with heart eyes", 0x1F970 to "smiling face with hearts",
        0x1F618 to "face blowing a kiss", 0x1F60E to "smiling face with sunglasses", 0x1F914 to "thinking face",
        0x1F622 to "crying face", 0x1F62D to "loudly crying face", 0x1F62E to "surprised face", 0x1F621 to "angry face",
        0x1F620 to "angry face", 0x1F634 to "sleeping face", 0x1F641 to "sad face", 0x2639 to "sad face",
        0x2764 to "red heart", 0x1F44D to "thumbs up", 0x1F44E to "thumbs down", 0x1F44B to "waving hand",
        0x1F44F to "clapping hands", 0x1F64F to "folded hands", 0x1F389 to "party popper", 0x1F525 to "fire",
        0x1F4AF to "hundred points", 0x2705 to "check mark", 0x2B50 to "star", 0x1F436 to "dog", 0x1F431 to "cat")

    /** A message that is one emoji and nothing else: its name ("smiling face"). Null otherwise, or for one not on the list. */
    fun loneEmoji(text: String): String? {
        val cps = text.trim().codePoints().toArray().filter { it != 0xFE0F && it !in 0x1F3FB..0x1F3FF }
        if (cps.size != 1) return null
        return EMOJI_NAMES[cps[0]]
    }

    /** Natural voice samples with the near-silence at both ends taken off (30 ms kept), so our pauses are the pauses. */
    fun trimSilence(samples: FloatArray, rate: Int, floor: Float = 0.004f): FloatArray {
        var a = 0
        while (a < samples.size && Math.abs(samples[a]) < floor) a++
        if (a == samples.size) return FloatArray(0)
        var b = samples.size - 1
        while (b > a && Math.abs(samples[b]) < floor) b--
        val keep = rate * 30 / 1000
        val from = maxOf(0, a - keep)
        val to = minOf(samples.size, b + 1 + keep)
        return if (from == 0 && to == samples.size) samples else samples.copyOfRange(from, to)
    }

    /** [samples] with [ms] of silence after. */
    fun withPause(samples: FloatArray, rate: Int, ms: Int): FloatArray =
        if (ms <= 0 || rate <= 0) samples else samples.copyOf(samples.size + (rate.toLong() * ms / 1000).toInt())

    // ---- 1.43: per-voice tuning for the natural voices (Labs › Voice tuning, Pre-release only).

    /** One preset: the engine's noise scale and noise width. */
    data class Tune(val name: String, val noise: Float, val width: Float)

    /** A is the default (what 1.42 used). */
    val TUNES = listOf(Tune("A", 0.667f, 0.8f), Tune("B", 0.33f, 0.4f), Tune("C", 0.85f, 1.0f), Tune("D", 0.5f, 1.2f), Tune("E", 1.0f, 0.5f))

    fun tuneOf(name: String?): Tune = TUNES.firstOrNull { it.name == name } ?: TUNES[0]

    /** "A: 0.667 / 0.8". */
    fun tuneText(t: Tune): String = "${t.name}: ${t.noise} / ${t.width}"

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
