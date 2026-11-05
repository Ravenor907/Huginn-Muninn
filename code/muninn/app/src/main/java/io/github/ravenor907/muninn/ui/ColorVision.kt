package io.github.ravenor907.muninn.ui

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Settings › Look › Color vision: the kind of sight every color that means something is remapped for. Typical keeps
 * the look's own colors; the others use sets made for color-blind eyes, the same as Huginn's Theme.qml: Okabe-Ito's
 * blue, yellow and vermilion for red-weak and green-weak sight, teal, pink and red (never blue against yellow) for
 * blue-weak sight, and lightness alone for no color. Status dots also have shapes ([StatusDot]) and failed things
 * say so in words, so nothing depends on color alone.
 *
 * Colors here are ARGB numbers, so they can be checked without a screen ([seenAs] and [deltaE] are what the test uses).
 */
object ColorVision {
    const val TYPICAL = "typical"
    /** Red-weak or red-blind (protanomaly, protanopia). */
    const val PROTAN = "protan"
    /** Green-weak or green-blind (deuteranomaly, deuteranopia). */
    const val DEUTAN = "deutan"
    /** Blue-weak or blue-blind (tritanomaly, tritanopia). */
    const val TRITAN = "tritan"
    /** No color (achromatopsia): gray, strong contrast. */
    const val MONO = "mono"
    val ALL = listOf(TYPICAL to "Typical", PROTAN to "Red-weak or red-blind", DEUTAN to "Green-weak or green-blind",
        TRITAN to "Blue-weak or blue-blind", MONO to "No color")

    /** A saved choice; with none, an older Color-blind friendly switch that was on opens as green-weak (what it showed). */
    fun of(saved: String?, oldColorBlind: Boolean = false): String =
        if (saved != null && ALL.any { it.first == saved }) saved else if (oldColorBlind) DEUTAN else TYPICAL

    /** Which of a set: 0 on a dark ground (Dark, True black, Seasonal), 1 on Light, 2 in high contrast. */
    fun tone(light: Boolean, contrast: Boolean): Int = if (contrast) 2 else if (light) 1 else 0

    /** Online, away, do not disturb and offline, for each kind and [tone]; null for typical sight. */
    fun status(kind: String, tone: Int): LongArray? = STATUS[kind]?.get(tone)
    private val STATUS = mapOf(
        PROTAN to listOf(longArrayOf(0xFF56B4E9, 0xFFF0E442, 0xFFE07020, 0xFF6E6A62), longArrayOf(0xFF0063A0, 0xFFA88700, 0xFF8F2D00, 0xFF9A948A),
            longArrayOf(0xFF7CCBFF, 0xFFFFF05A, 0xFFFF8A2E, 0xFF8C8C8C)),
        DEUTAN to listOf(longArrayOf(0xFF56B4E9, 0xFFF0E442, 0xFFD55E00, 0xFF6E6A62), longArrayOf(0xFF0063A0, 0xFFA88700, 0xFF8F2D00, 0xFF9A948A),
            longArrayOf(0xFF7CCBFF, 0xFFFFF05A, 0xFFFF5A1E, 0xFF8C8C8C)),
        TRITAN to listOf(longArrayOf(0xFF2BC4C4, 0xFFF4A6C0, 0xFFE8443A, 0xFF6E6A62), longArrayOf(0xFF007C80, 0xFFB4527A, 0xFFC0281E, 0xFF9A948A),
            longArrayOf(0xFF4FF0F0, 0xFFFFB0D0, 0xFFFF5A4A, 0xFF8C8C8C)),
        MONO to listOf(longArrayOf(0xFFFFFFFF, 0xFFB0B0B0, 0xFF707070, 0xFF3C3C3C), longArrayOf(0xFF000000, 0xFF444444, 0xFF777777, 0xFFB0B0B0),
            longArrayOf(0xFFFFFFFF, 0xFFB0B0B0, 0xFF707070, 0xFF3C3C3C)),
    )

    /** Danger: its text, line and fill, for each kind and [tone]; null for typical sight. */
    fun danger(kind: String, tone: Int): LongArray? = DANGER[kind]?.get(tone)
    private val DANGER = mapOf(
        PROTAN to listOf(longArrayOf(0xFFF08A4B, 0xFF7A4220, 0xFF24160E), longArrayOf(0xFFA84300, 0xFFD9A27E, 0xFFF8E6DA), longArrayOf(0xFFFFA060, 0xFF7A4220, 0xFF24160E)),
        DEUTAN to listOf(longArrayOf(0xFFF08A4B, 0xFF7A4220, 0xFF24160E), longArrayOf(0xFFA84300, 0xFFD9A27E, 0xFFF8E6DA), longArrayOf(0xFFFFA060, 0xFF7A4220, 0xFF24160E)),
        TRITAN to listOf(longArrayOf(0xFFF2877C, 0xFF6E3A34, 0xFF24120F), longArrayOf(0xFFB0281E, 0xFFE0A49E, 0xFFF7DEDB), longArrayOf(0xFFFF9A90, 0xFF6E3A34, 0xFF24120F)),
        MONO to listOf(longArrayOf(0xFF9A9A9A, 0xFF8A8A8A, 0xFF262626), longArrayOf(0xFF5A5A5A, 0xFF6A6A6A, 0xFFE2E2E2), longArrayOf(0xFFB8B8B8, 0xFF8A8A8A, 0xFF262626)),
    )

    /** The call buttons: answer and hang up; null for typical sight. */
    fun calls(kind: String): Pair<Long, Long>? = when (kind) {
        PROTAN, DEUTAN -> 0xFF2F6FB3 to 0xFFC25A00
        TRITAN -> 0xFF00838A to 0xFFC62828
        MONO -> 0xFF7A7A7A to 0xFF2E2E2E
        else -> null
    }

    /** The accents that stay apart for each kind of sight; the others map to the nearest of these ([safeAccent]). */
    fun accents(kind: String): List<String> = when (kind) {
        PROTAN -> listOf("gold", "iron", "jade", "fjord")
        DEUTAN -> listOf("gold", "iron", "fjord")
        TRITAN -> listOf("gold", "iron", "jade", "ember")
        MONO -> listOf("iron")
        else -> Accents.NAMES
    }

    /** The accent shown for a saved one ([key]: a name or "#RRGGBB"): itself if it suits [kind], else the nearest that does. */
    fun safeAccent(key: String, kind: String): String {
        val safe = accents(kind)
        if (kind == TYPICAL || key in safe) return key
        val c = Accents.base(key)
        return safe.minByOrNull { deltaE(c, Accents.base(it)) } ?: Accents.DEFAULT
    }

    /** Avatar discs (background to initial) that stay apart for [kind]: index 0 on a dark ground, 1 on Light; null for typical. */
    fun tones(kind: String, light: Boolean): List<Pair<Long, Long>>? = TONES[if (kind == DEUTAN) PROTAN else kind]?.get(if (light) 1 else 0)
    private val TONES = mapOf(
        PROTAN to listOf(
            listOf(0xFF302617L to 0xFFE8D3A8L, 0xFF7E5925L to 0xFFFFE8C0L, 0xFF792E9EL to 0xFFF0D6FFL, 0xFF8A4272L to 0xFFFFD6EEL),
            listOf(0xFFDBB3B3L to 0xFF6A2A2AL, 0xFFE6DBA8L to 0xFF5E4A0EL, 0xFFD7EAF4L to 0xFF1F4A78L, 0xFFA8C2E6L to 0xFF1F3A66L)),
        TRITAN to listOf(
            listOf(0xFF4F1717L to 0xFFF2B0A8L, 0xFF9E2E2EL to 0xFFFFE0DCL, 0xFF5A522BL to 0xFFF0E6B8L, 0xFF1E6767L to 0xFFCFF5F5L),
            listOf(0xFFDBB3B3L to 0xFF6A2A2AL, 0xFFEEE3DDL to 0xFF4A3A30L, 0xFFA8E6E6L to 0xFF0F4A4AL, 0xFFB3C4DBL to 0xFF24364FL)),
        MONO to listOf(
            listOf(0xFF141414L to 0xFFFFFFFFL, 0xFF383838L to 0xFFFFFFFFL, 0xFF5E5E5EL to 0xFFFFFFFFL, 0xFFD0D0D0L to 0xFF000000L),
            listOf(0xFF202020L to 0xFFFFFFFFL, 0xFF5A5A5AL to 0xFFFFFFFFL, 0xFFB0B0B0L to 0xFF000000L, 0xFFE8E8E8L to 0xFF000000L)),
    )
    /** A color someone picked for their own disc, as the nearest disc of [kind]'s set (itself for typical sight). */
    fun disc(c: Long, kind: String, light: Boolean): Pair<Long, Long>? = tones(kind, light)?.minByOrNull { deltaE(c, it.first) }

    /**
     * A falling leaf's color for [kind] and the look: on Light it's darker, so it shows on parchment; red-weak and
     * green-weak sight turn the reds to oranges (lighter for red-weak, to whom red looks dark); no color makes it gray by its
     * lightness.
     */
    fun leafInk(c: Long, kind: String, light: Boolean): Long {
        var r = ch(c, 16); var g = ch(c, 8); var b = ch(c, 0)
        if (kind == MONO) {
            val y = r * 0.299 + g * 0.587 + b * 0.114
            r = y; g = y; b = y
        } else if (kind == PROTAN || kind == DEUTAN) {
            val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2; val d = mx - mn
            var h = 0.0
            if (d > 0) h = when (mx) { r -> 60 * (((g - b) / d) % 6); g -> 60 * ((b - r) / d + 2); else -> 60 * ((r - g) / d + 4) }
            if (h < 0) h += 360
            val sat = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
            if (h < 30 || h > 330) {
                val nh = 30 + (if (h > 330) 0.0 else h) * 0.4
                val nl = min(0.9, l + if (kind == PROTAN) 0.1 else 0.04)
                val cc = (1 - abs(2 * nl - 1)) * sat
                val x = cc * (1 - abs((nh / 60) % 2 - 1))
                val m = nl - cc / 2
                r = cc + m; g = x + m; b = m
            }
        }
        if (light) { r *= 0.72; g *= 0.72; b *= 0.72 }
        return argb(r, g, b)
    }
    /** Snow: white, or a soft gray-blue on Light (gray with no color). */
    fun flakeInk(kind: String, light: Boolean): Long = if (!light) 0xFFFFFFFF else if (kind == MONO) 0xFF8A8A8A else 0xFF7F93AD

    // ---------------------------------------------------------------- how a color looks to each kind of sight

    /** Machado, Oliveira and Fernandes (2009) at full strength, on linear RGB. */
    private val MACHADO = mapOf(
        PROTAN to doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
        DEUTAN to doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
        TRITAN to doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900),
    )

    /** [c] as [kind] sees it (no color: its lightness alone), as linear RGB. */
    fun seenAs(c: Long, kind: String): DoubleArray {
        val l = doubleArrayOf(lin(ch(c, 16)), lin(ch(c, 8)), lin(ch(c, 0)))
        if (kind == MONO) { val y = 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]; return doubleArrayOf(y, y, y) }
        val m = MACHADO[kind] ?: return l
        return DoubleArray(3) { i -> (m[i * 3] * l[0] + m[i * 3 + 1] * l[1] + m[i * 3 + 2] * l[2]).coerceIn(0.0, 1.0) }
    }

    /** How far apart [a] and [b] look to [kind] (CIE deltaE 1976; about 2 is just noticeable, 20 is plainly different). */
    fun deltaE(a: Long, b: Long, kind: String = TYPICAL): Double {
        val x = lab(seenAs(a, kind)); val y = lab(seenAs(b, kind))
        return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
    }

    private fun lab(l: DoubleArray): DoubleArray {
        val x = (0.4124 * l[0] + 0.3576 * l[1] + 0.1805 * l[2]) / 0.95047
        val y = 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]
        val z = (0.0193 * l[0] + 0.1192 * l[1] + 0.9505 * l[2]) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toDouble() / 255.0
    private fun argb(r: Double, g: Double, b: Double): Long {
        fun q(v: Double) = (v.coerceIn(0.0, 1.0) * 255.0 + 0.5).toLong()
        return 0xFF000000L or (q(r) shl 16) or (q(g) shl 8) or q(b)
    }
}
