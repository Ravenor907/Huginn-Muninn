package io.github.ravenor907.muninn.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.ravenor907.muninn.R

/**
 * Settings › Look and text size, and its accessibility choices. Read by the theme, so every screen follows at once:
 * size scales everything, text scales only text, high contrast brightens text and outlines, color-blind friendly
 * swaps the warning color for one that doesn't depend on red/green, and reduce motion turns animations off.
 */
object Access {
    var size by androidx.compose.runtime.mutableFloatStateOf(1f)
    var text by androidx.compose.runtime.mutableFloatStateOf(1f)
    var contrast by androidx.compose.runtime.mutableStateOf(false)
    var colorBlind by androidx.compose.runtime.mutableStateOf(false)
    var reduceMotion by androidx.compose.runtime.mutableStateOf(false)
    /** The look: [Looks.DARK] (the usual), [Looks.BLACK], [Looks.LIGHT] (parchment) or [Looks.SEASONAL] (by the time of year). */
    var look by androidx.compose.runtime.mutableStateOf(Looks.DARK)
    /** Seasonal's season: [Seasons.AUTO] (by the date) or one season's key. */
    var season by androidx.compose.runtime.mutableStateOf(Seasons.AUTO)
    /** Seasonal's background: "" (the season's own) or one of [Seasons.GROUNDS]. */
    var seasonGround by androidx.compose.runtime.mutableStateOf("")
    /** Seasonal's falling leaves or snow: [Seasons.OFF] or on ([Seasons.FULL]). */
    var seasonAnim by androidx.compose.runtime.mutableStateOf(Seasons.FULL)
    /** The accent: one of [Accents.NAMES] or any "#RRGGBB" (Settings › Look). */
    var accent by androidx.compose.runtime.mutableStateOf(Accents.DEFAULT)
    /** The open chat's own color ("#RRGGBB", Person details › Chat color), "" for none: the accent follows it there. */
    var chatAccent by androidx.compose.runtime.mutableStateOf("")
}

/**
 * The accent colors, the same as Huginn's: seven named ones, or any "#RRGGBB". Autumn's gold and copper are
 * warmer, Snow's gold paler. On Light the accent is darkened (by 1.7, as Huginn does), so it reads on parchment;
 * high contrast makes it white. The fills behind accented things are worked out from it with Huginn's formulas.
 * Colors here are ARGB numbers, so they can be checked without a screen.
 */
object Accents {
    const val DEFAULT = "gold"
    val NAMES = listOf("gold", "iron", "copper", "jade", "fjord", "heather", "ember")
    val TITLES = listOf("Gold", "Iron", "Copper", "Jade", "Fjord", "Heather", "Ember")
    /** A chat's own color: the accents as "#RRGGBB" (Huginn's chat swatches). */
    val CHAT = listOf("#C2A25E", "#A9B4BE", "#B07A5A", "#6FAE8C", "#6E9CC8", "#A58BC6", "#CF6F54")

    /** What a chat's saved color is as "#RRGGBB" (a bubble color saved by name before maps to its nearest accent), or "". */
    fun chatHex(v: String?): String = when (v) {
        null, "" -> ""
        "gold" -> "#C2A25E"; "green" -> "#6FAE8C"; "blue" -> "#6E9CC8"; "red" -> "#CF6F54"; "purple" -> "#A58BC6"; "teal" -> "#6FAE8C"
        else -> if (valid(v)) v.trim().uppercase() else ""
    }

    /** A "#RRGGBB" color. */
    fun valid(v: String?): Boolean = v != null && Regex("^#[0-9A-Fa-f]{6}$").matches(v.trim())

    /** A saved accent: a name, a valid "#RRGGBB", else gold. */
    fun choice(v: String?): String = when {
        v != null && v in NAMES -> v
        valid(v) -> v!!.trim().uppercase()
        else -> DEFAULT
    }

    /** The accent [key] as ARGB, before the look changes it. */
    fun base(key: String?, autumn: Boolean = false, snow: Boolean = false): Long = when (choice(key)) {
        "gold" -> if (snow) 0xFFE3C27A else if (autumn) 0xFFE9B56A else 0xFFC2A25E
        "iron" -> 0xFFA9B4BE
        "copper" -> if (autumn) 0xFFD9853B else 0xFFB07A5A
        "jade" -> 0xFF6FAE8C
        "fjord" -> 0xFF6E9CC8
        "heather" -> 0xFFA58BC6
        "ember" -> 0xFFCF6F54
        else -> 0xFF000000L or choice(key).substring(1).toLong(16)
    }

    private fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFF).toFloat() / 255f
    private fun argb(r: Float, g: Float, b: Float): Long {
        fun q(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toLong()
        return 0xFF000000L or (q(r) shl 16) or (q(g) shl 8) or q(b)
    }
    private fun map(c: Long, f: (Float, Int) -> Float) = argb(f(ch(c, 16), 0), f(ch(c, 8), 1), f(ch(c, 0), 2))

    /** On Light: darker by 1.7 (Huginn's Qt.darker), so it reads on parchment. */
    fun darker(c: Long): Long = map(c) { v, _ -> v / 1.7f }
    /** The fill behind an accented choice or button (Huginn's accentFill). */
    fun fill(c: Long, light: Boolean): Long =
        if (light) map(c) { v, i -> v * 0.18f + floatArrayOf(0.8f, 0.78f, 0.72f)[i] } else map(c) { v, i -> v * 0.25f + floatArrayOf(0.06f, 0.06f, 0.06f)[i] }
    /** The fill behind a focused or hovered thing (Huginn's focusFill). */
    fun focus(c: Long, light: Boolean): Long =
        if (light) map(c) { v, i -> v * 0.1f + floatArrayOf(0.86f, 0.84f, 0.79f)[i] } else map(c) { v, i -> v * 0.12f + floatArrayOf(0.11f, 0.11f, 0.1f)[i] }
    /** A softer tint of it: my avatar circle, my bubbles in a chat with its own color, highlighted things. */
    fun tint(c: Long, light: Boolean): Long =
        if (light) fill(c, true) else map(c) { v, _ -> v * 0.22f + 0.05f }
    /** A brighter shade, for text and icons on its tint (darker on Light). */
    fun bright(c: Long, light: Boolean): Long = if (light) map(c) { v, _ -> v * 0.85f } else map(c) { v, _ -> v + (1f - v) * 0.25f }
    /** Relative brightness, 0 to 1. */
    fun luminance(c: Long): Float {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        return 0.2126f * lin(ch(c, 16)) + 0.7152f * lin(ch(c, 8)) + 0.0722f * lin(ch(c, 0))
    }
}

/**
 * The four looks. Their keys are what Settings saves; an unknown saved key means Dark, and a saved season's own
 * key (like "autumn") means Seasonal.
 */
object Looks {
    const val DARK = "dark"
    const val BLACK = "black"
    const val LIGHT = "light"
    /** Seasonal: the look of the season it is now ([Seasons]). */
    const val SEASONAL = "seasonal"
    val ALL = listOf(DARK to "Dark", BLACK to "True black", LIGHT to "Light", SEASONAL to "Seasonal")
    fun of(key: String?): String = if (Seasons.NAMES.any { it.first == key }) SEASONAL else ALL.firstOrNull { it.first == key }?.first ?: DARK
    fun name(key: String): String = ALL.firstOrNull { it.first == key }?.second ?: "Dark"
}

/**
 * The seasons the Seasonal look shows, each over a range of dates (month and day, both included; a range may run
 * past the new year). The first range that covers today wins: Snow from December 1 to the end of February, Autumn
 * the rest of the year. A new season gets its own range and name here and its own shades in [Palette]; it then
 * joins the Season choices and the cycle on the Seasonal button by itself.
 */
object Seasons {
    const val AUTUMN = "autumn"
    const val SNOW = "snow"
    /** Season: by the date. */
    const val AUTO = "auto"
    /** Every season, in the order the Season choices show them. */
    val NAMES = listOf(AUTUMN to "Autumn", SNOW to "Snow")
    /** The Season choices: Auto, then every season. */
    val CHOICES = listOf(AUTO to "Auto") + NAMES

    /** A saved Season choice, or Auto when it isn't one. */
    fun choice(key: String?): String = CHOICES.firstOrNull { it.first == key }?.first ?: AUTO
    fun name(key: String): String = CHOICES.firstOrNull { it.first == key }?.second ?: "Auto"
    /** The choice after [key]: tapping Seasonal again steps Auto, Autumn, Snow and back to Auto. */
    fun next(key: String): String = CHOICES[(CHOICES.indexOfFirst { it.first == key } + 1) % CHOICES.size].first

    /** [season] from [fromMonth]/[fromDay] to [toMonth]/[toDay] (months 1 to 12). */
    data class Span(val season: String, val fromMonth: Int, val fromDay: Int, val toMonth: Int, val toDay: Int) {
        fun covers(month: Int, day: Int): Boolean {
            val at = month * 100 + day
            val from = fromMonth * 100 + fromDay
            val to = toMonth * 100 + toDay
            return if (from <= to) at in from..to else at >= from || at <= to
        }
    }

    // February 29 is in Snow's range, so a leap year's last day of February is Snow too.
    val SPANS = listOf(Span(SNOW, 12, 1, 2, 29), Span(AUTUMN, 1, 1, 12, 31))

    /** The season on [month]/[day]: the first span that covers it, else the first season. */
    fun at(month: Int, day: Int, spans: List<Span> = SPANS): String =
        spans.firstOrNull { it.covers(month, day) }?.season ?: spans.first().season

    /** The season shown for the choice [pick] on [month]/[day]: the date's season for Auto, else the one picked. */
    fun shown(pick: String, month: Int, day: Int): String = choice(pick).let { if (it == AUTO) at(month, day) else it }

    @Volatile private var day = -1L
    @Volatile private var season = AUTUMN

    /** The season today (worked out once a day). */
    val now: String get() {
        val today = System.currentTimeMillis() / 86_400_000L
        if (today != day) {
            val c = java.util.Calendar.getInstance()
            season = at(c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
            day = today
        }
        return season
    }

    /** The season on screen: the one picked, or today's for Auto. */
    val current: String get() = choice(Access.season).let { if (it == AUTO) now else it }

    /** Background: the season's own (""), or one of these dark grounds; panels and lines are shaded from it. */
    val GROUNDS = listOf("#0C1724", "#13201A", "#0E1C1E", "#1E1522", "#22140F", "#221B10", "#17191E", "#111111")
    val GROUND_NAMES = listOf("Night", "Pine", "Teal", "Plum", "Bark", "Umber", "Slate", "Charcoal")
    fun ground(key: String?): String = if (key != null && key in GROUNDS) key else ""

    /** Animation: whether anything falls behind the Seasonal look. How much and how big is [LeafPrefs]. */
    const val OFF = "off"
    /** An older saved choice for about half as many; it reads as on, and [LeafPrefs] keeps the half. */
    const val LIGHT = "light"
    const val FULL = "full"
    val ANIMS = listOf(OFF to "Off", FULL to "On")
    /** A saved Animation choice: Off, or on ([FULL]) for anything else. */
    fun anim(key: String?): String = if (key == OFF) OFF else FULL
    /** Something falls: the animation isn't Off. */
    fun falls(key: String?): Boolean = anim(key) != OFF
}

/**
 * Huginn's stone and iron grays and its aged gold, so the two apps share one house style; Muninn's one accent is
 * silver. Light is Huginn's parchment. Autumn is warm bark and ember over falling leaves; Snow is white on a dark
 * night blue under falling snow. A Seasonal background picked in Settings replaces the season's ground, and the
 * panels and lines are shaded from it. High contrast wins over every look.
 */
object Palette {
    private val hc get() = Access.contrast
    /** The parchment look is on (and high contrast is off): dark text on light paper. */
    val light get() = Access.look == Looks.LIGHT && !hc
    private val black get() = Access.look == Looks.BLACK && !hc
    /** Seasonal, in any season (and high contrast is off): rounded shapes, frosted panels, something falling behind. */
    val seasonal get() = Access.look == Looks.SEASONAL && !hc
    /** Seasonal in Snow. */
    val snow get() = seasonal && Seasons.current == Seasons.SNOW
    /** Seasonal in Autumn (any season without its own shades looks like Autumn). */
    val autumn get() = seasonal && !snow
    /** The Seasonal background picked in Settings, or null for the season's own. */
    private val customGround: Color? get() = if (seasonal) parse(Seasons.ground(Access.seasonGround).ifEmpty { null }) else null

    /**
     * Picks the shade for the look in use: dark, true black, parchment, autumn, high contrast, snow. A shade with a
     * [lift] (a ground, panel or line) follows a picked Seasonal background instead: lighter by that share of the
     * way to white, or darker for a negative one.
     */
    private fun pick(dark: Long, deep: Long, parchment: Long, fall: Long, contrast: Long, winter: Long, lift: Float = Float.NaN): Color {
        if (hc) return Color(contrast)
        if (light) return Color(parchment)
        if (black) return Color(deep)
        if (!seasonal) return Color(dark)
        val g = customGround
        if (g != null && !lift.isNaN()) return shade(g, lift)
        return Color(if (snow) winter else fall)
    }
    /** [g] lifted toward white by [f] (0 to 1), or darkened toward black by a negative [f]. */
    fun shade(g: Color, f: Float): Color =
        if (f >= 0f) Color(g.red + (1f - g.red) * f, g.green + (1f - g.green) * f, g.blue + (1f - g.blue) * f)
        else Color(g.red * (1f + f), g.green * (1f + f), g.blue * (1f + f))

    // True black: only the background goes black (panels and buttons keep their shade, so they still stand out).
    val ground get() = pick(0xFF101112, 0xFF000000, 0xFFF3EFE6, 0xFF221710, 0xFF000000, 0xFF0E1726, 0f)
    val groundDeep get() = pick(0xFF0C0D0E, 0xFF000000, 0xFFE7E1D4, 0xFF1A110B, 0xFF000000, 0xFF0A111D, -0.25f)
    val panel get() = pick(0xFF17191B, 0xFF0B0C0D, 0xFFFBF9F4, 0xFF2B1D13, 0xFF0A0A0A, 0xFF152238, 0.05f)
    val panelLow get() = pick(0xFF141617, 0xFF060607, 0xFFECE7DC, 0xFF241911, 0xFF050505, 0xFF111C2F, 0.025f)
    val raised get() = pick(0xFF1D1E20, 0xFF141516, 0xFFE2DCCF, 0xFF4A3421, 0xFF1A1A1A, 0xFF24375A, 0.14f)
    val line get() = pick(0xFF2C2F33, 0xFF232528, 0xFFCEC6B6, 0xFF3F2C1C, 0xFF9A9A9A, 0xFF2A3D5C, 0.1f)
    val lineMuted get() = pick(0xFF45484D, 0xFF3E4146, 0xFFA69D8B, 0xFF5E4430, 0xFFBDBDBD, 0xFF3F5679, 0.22f)
    val text get() = pick(0xFFE4E1DA, 0xFFE4E1DA, 0xFF1F1C17, 0xFFECDCC4, 0xFFFFFFFF, 0xFFE6EEF8)
    val textSoft get() = pick(0xFFC4C0B7, 0xFFC4C0B7, 0xFF3B362E, 0xFFD9C6AD, 0xFFFFFFFF, 0xFFC9D6E6)
    // On a picked Seasonal background, muted and faint text are a little brighter, so they still read at 4.5:1.
    val textMuted get() = if (customGround != null && autumn) Color(0xFFB9A080)
        else pick(0xFF9C978D, 0xFF9C978D, 0xFF615A4E, 0xFFA88C6C, 0xFFE0E0E0, 0xFF93A7C0)
    // Faint text (hints, times) still reads at 4.5:1 on parchment, on Autumn's bark and on Snow's night blue.
    val textFaint get() = if (customGround != null) Color(if (snow) 0xFF8EA5C0 else 0xFFAD9474)
        else pick(0xFF6E6A62, 0xFF6E6A62, 0xFF746C5F, 0xFF9A7F5E, 0xFFC8C8C8, 0xFF8299B5)
    /** Text that is brighter than the usual text: names in a list. */
    val textBright get() = pick(0xFFF2F0EA, 0xFFF2F0EA, 0xFF14120E, 0xFFF3E2C7, 0xFFFFFFFF, 0xFFF4F8FD)
    /** The accent picked on the Look page ([Accents]), or white in high contrast. */
    val accent: Color get() = accentOf(Access.chatAccent)
    val accentFill: Color get() = fillOf(accent)
    /** Text and icons drawn on a solid accent. */
    val onAccent: Color get() = onAccentOf(accent)
    val focusFill: Color get() = if (hc) Color(0xFF2A2A2A) else Color(Accents.focus(argb(accent), light))

    /** The accent [key] (a name or "#RRGGBB") as this look shows it; "" or nothing valid is the app's own accent. */
    fun accentOf(key: String?): Color {
        if (hc) return Color(0xFFFFFFFF)
        val k = if (key.isNullOrEmpty()) Access.accent else key
        val c = Accents.base(k, autumn, snow)
        return Color(if (light) Accents.darker(c) else c)
    }
    /** The fill behind something in [c] (a selected choice, a button). */
    fun fillOf(c: Color): Color = if (hc) Color(0xFF333333) else Color(Accents.fill(argb(c), light))
    /** A soft tint of [c] (my bubbles in a chat with its own color, my avatar, highlights). */
    fun tintOf(c: Color): Color = if (hc) Color(0xFF333333) else Color(Accents.tint(argb(c), light))
    /** A brighter shade of [c], for text and icons on its tint. */
    fun brightOf(c: Color): Color = if (hc) Color(0xFFFFFFFF) else Color(Accents.bright(argb(c), light))
    /** Text and icons on a solid [c]: dark on a light accent, light on a dark one (a dark picked color, or Light's). */
    fun onAccentOf(c: Color): Color = when {
        hc -> Color(0xFF000000)
        Accents.luminance(argb(c)) > 0.2f -> if (snow) Color(0xFF0A1424) else if (autumn) Color(0xFF2A1608) else Color(0xFF101112)
        else -> Color(0xFFF3EFE6)
    }
    private fun argb(c: Color): Long = c.toArgb().toLong() and 0xFFFFFFFFL
    /** Section headings. */
    val section get() = pick(0xFFC8CDD3, 0xFFC8CDD3, 0xFF4F5862, 0xFFB08A5E, 0xFFFFFFFF, 0xFF8FB0D4)
    // Color-blind friendly: amber instead of rose, so warnings never depend on telling red from green.
    val danger get() = if (Access.colorBlind) (if (light) Color(0xFF8A5A00) else Color(0xFFF2B33D))
        else pick(0xFFD9A79E, 0xFFD9A79E, 0xFFA23B2C, 0xFFE0A89E, 0xFFFFB4A8, 0xFFF0A8A0)
    val dangerLine get() = if (Access.colorBlind) (if (light) Color(0xFFC79A45) else Color(0xFF7A5A1E))
        else if (light) Color(0xFFD9A79E) else Color(0xFF5A3A34)
    val dangerFill get() = if (Access.colorBlind) (if (light) Color(0xFFF6EBD4) else Color(0xFF221B0E))
        else if (light) Color(0xFFF5E2DD) else Color(0xFF1E1716)

    // Chat ("Quiet" look): the accent marks the few things you act on (new chat, play, record, unread). These
    // were gold before the accent could be picked; they follow it now.
    val gold: Color get() = accent
    val goldBright: Color get() = brightOf(accent)
    /** Text and icons drawn on [gold]. */
    val onGold: Color get() = onAccent
    /** My own avatar circle (my initial in goldBright on it), and the soft accent tint behind highlighted things. */
    val goldTint: Color get() = tintOf(accent)
    /** The Nearby room's avatar circle. */
    val coolTint get() = pick(0xFF23272C, 0xFF23272C, 0xFFD6DDEB, 0xFF3A2D24, 0xFF333333, 0xFF22385A, 0.12f)
    /** Search box, message box and their bubbles. */
    val field get() = pick(0xFF1B1D20, 0xFF141516, 0xFFECE7DC, 0xFF1C130C, 0xFF141414, 0xFF0B1320, -0.2f)
    val bubbleMine get() = pick(0xFF2B2E33, 0xFF2B2E33, 0xFFE2DCCF, 0xFF4A3421, 0xFF2A2A2A, 0xFF24375A, 0.14f)
    /** The empty part of a voice message's progress bar. */
    val track get() = pick(0xFF44484E, 0xFF44484E, 0xFFA69D8B, 0xFF5E4430, 0xFF9A9A9A, 0xFF3F5679, 0.22f)
    /** The thin line under a conversation's top bar. */
    val hairline get() = pick(0xFF1F2125, 0xFF16181A, 0xFFD9D2C4, 0xFF3A2718, 0xFF9A9A9A, 0xFF1E2E48, 0.08f)
    /**
     * The status dots (online, away, do not disturb) and the encrypted shield. Color-blind friendly uses blue, yellow
     * and vermilion, which stay apart without telling red from green; high contrast makes each brighter on black. The
     * status is always said in words too, and do not disturb has a bar across its dot.
     */
    val online get() = if (Access.colorBlind) Color(if (hc) 0xFF9FD3FF else if (light) 0xFF2F5F9E else 0xFF6FB3F0)
        else pick(0xFF7FB08A, 0xFF7FB08A, 0xFF2F7D46, 0xFF3FB27F, 0xFF9BE0A8, 0xFF4FC08D)
    /** The away status dot (amber; yellow when color-blind friendly). */
    val away get() = if (Access.colorBlind) Color(if (hc) 0xFFFFF176 else if (light) 0xFF7A6200 else 0xFFF0E442)
        else pick(0xFFD9A440, 0xFFD9A440, 0xFF9A6B00, 0xFFE0B44C, 0xFFFFD84D, 0xFFE0B44C)
    /** The do-not-disturb status dot (red; vermilion when color-blind friendly). */
    val dnd get() = if (Access.colorBlind) Color(if (hc) 0xFFFF9E66 else if (light) 0xFFB34700 else 0xFFE8743B)
        else pick(0xFFD9534F, 0xFFD9534F, 0xFFA23B2C, 0xFFD9534F, 0xFFFF7A7A, 0xFFE06A66)
    /** The offline status dot. */
    val offline get() = pick(0xFF6E6A62, 0xFF6E6A62, 0xFF877F71, 0xFF7E6548, 0xFFC8C8C8, 0xFF7E90A8)

    /**
     * A person's avatar disc and the initial on it, picked from their name so it never changes (the same five tones
     * as Huginn). High contrast draws every initial white on near-black.
     */
    fun avatarTone(name: String): Pair<Color, Color> {
        if (hc) return Color(0xFF1F1F1F) to Color(0xFFFFFFFF)
        val tones = if (light) LIGHT_TONES else if (snow) SNOW_TONES else if (seasonal) AUTUMN_TONES else DARK_TONES
        var h = 0
        for (ch in name) h = (h * 31 + ch.code) % 9973
        val t = tones[h % tones.size]
        return Color(t.first) to Color(t.second)
    }
    private val DARK_TONES = listOf(0xFF2C3A2CL to 0xFFB6D6B6L, 0xFF2D3340L to 0xFFB9C6E0L, 0xFF3A2724L to 0xFFE6A596L, 0xFF33293AL to 0xFFD2BCE0L, 0xFF24363AL to 0xFFAED4D8L)
    private val LIGHT_TONES = listOf(0xFFD5E4D3L to 0xFF2E5A33L, 0xFFD6DDEBL to 0xFF2F4A78L, 0xFFEED5CFL to 0xFF86392AL, 0xFFE2D6EAL to 0xFF5D3D78L, 0xFFD0E5E7L to 0xFF235A60L)
    private val AUTUMN_TONES = listOf(0xFF3D5A80L to 0xFFF3E2C7L, 0xFF7A4E3AL to 0xFFF3E2C7L, 0xFF6F5A2AL to 0xFFF3E2C7L, 0xFF4E6B5AL to 0xFFF3E2C7L, 0xFF5A4A72L to 0xFFF3E2C7L)
    private val SNOW_TONES = listOf(0xFF3D5A80L to 0xFFF4F8FDL, 0xFF6A4E6EL to 0xFFF4F8FDL, 0xFF3E6560L to 0xFFF4F8FDL, 0xFF5A5470L to 0xFFF4F8FDL, 0xFF6E5A44L to 0xFFF4F8FDL)
    /** The six colors a person can pick for their own initial disc (sent with their profile): the one list for it. */
    val MY_COLORS = listOf("#C9A964", "#3D5A80", "#7A4E3A", "#4E7A4E", "#6B4E8A", "#8A8A8A")
    /** What TalkBack calls one of [MY_COLORS]. */
    fun myColorName(hex: String) = when (hex.uppercase()) {
        "#C9A964" -> "Gold"; "#3D5A80" -> "Blue"; "#7A4E3A" -> "Copper"; "#4E7A4E" -> "Green"; "#6B4E8A" -> "Heather"; else -> "Grey"
    }
    /** A color someone picked ("#RRGGBB"), or null when it isn't one. */
    fun parse(hex: String?): Color? {
        val h = hex?.trim() ?: return null
        if (!Regex("^#[0-9A-Fa-f]{6}$").matches(h)) return null
        return Color(0xFF000000L or h.substring(1).toLong(16))
    }
    /** Text that reads on [bg]: near-black on a light disc, cream on a dark one. */
    fun onDisc(bg: Color): Color = if (bg.luminance() > 0.45f) Color(0xFF1A1208) else Color(0xFFF3E2C7)
}

/**
 * The shapes that change with the look. Every look but Seasonal is sharp (the house style); Seasonal (Autumn and
 * Snow alike) rounds buttons, panels, fields and the bars, and the composer becomes a pill.
 */
object LookShapes {
    private val sharp = RoundedCornerShape(4.dp)
    private val round = RoundedCornerShape(14.dp)
    private val pill = RoundedCornerShape(22.dp)
    private val barAll = RoundedCornerShape(22.dp)
    private val barTop = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    /** Panels, buttons and fields. */
    val corner: Shape get() = if (Palette.seasonal) round else sharp
    /** The message box: a pill in Seasonal, sharp elsewhere. */
    val composer: Shape get() = if (Palette.seasonal) pill else sharp
    /** A bar with all corners rounded (the header). */
    val bar: Shape get() = if (Palette.seasonal) barAll else RectangleShape
    /** A bar rounded along its top edge (the bottom tabs, the main list). */
    val barTopShape: Shape get() = if (Palette.seasonal) barTop else RectangleShape
}

/**
 * A header, rail or tab bar. Most looks fill it with [fill] (unspecified: the ground shows through, nothing is
 * drawn). Seasonal rounds it ([top]: only the top corners) and makes the fill a little see-through for what falls.
 */
fun androidx.compose.ui.Modifier.bar(fill: Color = Color.Unspecified, top: Boolean = false): androidx.compose.ui.Modifier =
    if (!Palette.seasonal) { if (fill.isSpecified) this.background(fill) else this }
    else {
        val shape = if (top) LookShapes.barTopShape else LookShapes.bar
        val tint = (if (fill.isSpecified) fill else Palette.panelLow).copy(alpha = if (top) 0.92f else 0.78f)
        (if (top) this else this.padding(horizontal = 10.dp, vertical = 4.dp)).clip(shape).background(tint)
    }

/**
 * The main list: a frosted panel in Seasonal (leaves or snow show softly through it, dim enough that muted text on
 * top of a bright leaf or flake still reads); nothing elsewhere.
 */
fun androidx.compose.ui.Modifier.frosted(): androidx.compose.ui.Modifier =
    if (!Palette.seasonal) this
    else this.padding(start = 10.dp, end = 10.dp, top = 8.dp).clip(LookShapes.barTopShape).background(Palette.panel.copy(alpha = 0.8f))

object Type {
    val display = FontFamily(Font(R.font.cinzel, FontWeight.Bold))
    val body = FontFamily(
        Font(R.font.barlow_regular, FontWeight.Normal),
        Font(R.font.barlow_medium, FontWeight.Medium),
        Font(R.font.barlow_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_bold, FontWeight.Bold),
    )
    val runic = FontFamily(Font(R.font.noto_sans_runic))

    val title get() = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = 3.sp, color = Palette.text)
    val section get() = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = Palette.textMuted)
    val big get() = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Palette.text)
    val normal get() = TextStyle(fontFamily = body, fontSize = 16.sp, color = Palette.textSoft)
    val small get() = TextStyle(fontFamily = body, fontSize = 14.sp, color = Palette.textMuted)
}

@Composable
fun MuninnTheme(content: @Composable () -> Unit) {
    // Size scales everything (dp and sp); text scales only sp, on top of Android's own font size.
    val base = androidx.compose.ui.platform.LocalDensity.current
    val density = androidx.compose.ui.unit.Density(base.density * Access.size, base.fontScale * Access.text)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density) {
        if (PrivateKeyboard.on) Private { Themed(content) } else Themed(content)
    }
}

/** Settings › Lock and privacy › Private keyboard. Set from the saved choice when Muninn starts, and by the switch. */
object PrivateKeyboard {
    var on by androidx.compose.runtime.mutableStateOf(false)
}

/**
 * Every text field under it asks the keyboard for private mode: nothing typed is learned or synced by the keyboard.
 * Most keyboards turn voice typing off in that mode too, so it's only on when Private keyboard is.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun Private(content: @Composable () -> Unit) = androidx.compose.ui.platform.InterceptPlatformTextInput(Incognito, content)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private object Incognito : androidx.compose.ui.platform.PlatformTextInputInterceptor {
    override suspend fun interceptStartInputMethod(
        request: androidx.compose.ui.platform.PlatformTextInputMethodRequest,
        nextHandler: androidx.compose.ui.platform.PlatformTextInputSession,
    ): Nothing {
        val private = object : androidx.compose.ui.platform.PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection =
                request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
        }
        nextHandler.startInputMethod(private)
    }
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (Palette.light) lightColorScheme(
            primary = Palette.accent, onPrimary = Palette.ground, background = Palette.ground, surface = Palette.panel,
            onSurface = Palette.text, onBackground = Palette.text, surfaceVariant = Palette.raised, outline = Palette.line,
            error = Palette.danger, onSurfaceVariant = Palette.textMuted, surfaceContainer = Palette.panel,
        ) else darkColorScheme(
            primary = Palette.accent, onPrimary = Palette.ground, background = Palette.ground, surface = Palette.panel,
            onSurface = Palette.text, onBackground = Palette.text, surfaceVariant = Palette.raised, outline = Palette.line,
            error = Palette.danger, onSurfaceVariant = Palette.textMuted, surfaceContainer = Palette.panel,
        ),
        content = content,
    )
}
