package io.github.ravenor907.muninn.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Amount and Size sliders under Settings › Look › Seasonal, worked out the same way in Huginn. Both are
 * logarithmic, so the low end is fine-grained: a slider position from 0 to 100 gives 1 to 400 on screen
 * (`round(400^(v/100))`) and 15% to 600% of the usual size (`round(15 * 40^(v/100))`). Until Amount is moved it
 * follows the screen: [DEFAULT_BASE] on a phone-sized screen, more on a bigger one.
 */
object FallScale {
    const val MIN_COUNT = 1
    const val MAX_COUNT = 400
    const val MIN_SIZE = 15
    const val MAX_SIZE = 600
    const val DEFAULT_SIZE = 60
    /** How many fall on a phone-sized screen until Amount is moved. */
    const val DEFAULT_BASE = 12f
    /** How many fell on a phone-sized screen at 100% in older versions (11 leaves and 3 seeds). */
    const val OLD_BASE = 14f

    /** Amount at slider position [v] (0 to 100). */
    fun countAt(v: Float): Int = 400.0.pow(v.coerceIn(0f, 100f) / 100.0).roundToInt().coerceIn(MIN_COUNT, MAX_COUNT)
    /** Where [n] sits on the Amount slider. */
    fun countPos(n: Int): Float = (100.0 * ln(n.coerceIn(MIN_COUNT, MAX_COUNT).toDouble()) / ln(400.0)).toFloat()
    /** Size (percent of the usual) at slider position [v] (0 to 100). */
    fun sizeAt(v: Float): Int = (15.0 * 40.0.pow(v.coerceIn(0f, 100f) / 100.0)).roundToInt().coerceIn(MIN_SIZE, MAX_SIZE)
    /** Where [pct] sits on the Size slider. */
    fun sizePos(pct: Int): Float = (100.0 * ln(pct.coerceIn(MIN_SIZE, MAX_SIZE) / 15.0) / ln(40.0)).toFloat()

    /** Screen area against a phone's (412 by 915 dp), held between half and four times. */
    fun area(widthDp: Int, heightDp: Int): Float = (widthDp * heightDp / (412f * 915f)).coerceIn(0.5f, 4f)
    /** How many fall when Amount follows the screen: [base] on a phone, scaled by [area]. */
    fun autoCount(base: Float, area: Float): Int = (base * area).roundToInt().coerceIn(MIN_COUNT, MAX_COUNT)
    /** The phone-sized amount for an older saved "how many" percent and Light: 100% on Full gets today's default. */
    fun legacyBase(oldPct: Int, light: Boolean): Float =
        if (oldPct == 100 && !light) DEFAULT_BASE else OLD_BASE * oldPct.coerceIn(25, 200) / 100f * (if (light) 0.5f else 1f)
    /** The size for an older saved percent: 100% (the old default) gets today's default, anything else stays. */
    fun legacySize(oldPct: Int): Int = if (oldPct == 100) DEFAULT_SIZE else oldPct.coerceIn(MIN_SIZE, MAX_SIZE)
    /** How many of [n] are maple seeds (about 3 in 14); the rest are leaves. In Snow all are flakes. */
    fun seeds(n: Int): Int = (n * 3f / 14f).roundToInt().coerceIn(0, n)
}

/**
 * Settings › Look › Seasonal: Amount (how many fall; 0 follows the screen) and Size (percent of the usual). Kept here
 * so the sliders and what falls follow the same value at once; saved in the app's settings under "fallCount",
 * "fallBase" and "fallSize". Older "leafCount" and "leafSize" percents and the Light animation carry over.
 */
object LeafPrefs {
    /** Amount picked on the slider, or 0: [FallScale.autoCount] of [base] for the screen. */
    var count by mutableIntStateOf(0)
    var base by mutableFloatStateOf(FallScale.DEFAULT_BASE)
    var size by mutableIntStateOf(FallScale.DEFAULT_SIZE)
    private var loaded = false
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
        count = p.getInt("fallCount", 0).let { if (it <= 0) 0 else it.coerceIn(FallScale.MIN_COUNT, FallScale.MAX_COUNT) }
        base = if (p.contains("fallBase")) p.getFloat("fallBase", FallScale.DEFAULT_BASE)
            else FallScale.legacyBase(p.getInt("leafCount", 100), p.getString("seasonAnim", null) == Seasons.LIGHT)
        size = if (p.contains("fallSize")) p.getInt("fallSize", FallScale.DEFAULT_SIZE).coerceIn(FallScale.MIN_SIZE, FallScale.MAX_SIZE)
            else FallScale.legacySize(p.getInt("leafSize", 100))
        // Keep what came over from the older settings, so it stays once the Animation choice is saved as On.
        if (!p.contains("fallBase") && base != FallScale.DEFAULT_BASE) p.edit().putFloat("fallBase", base).apply()
    }
    /** How many fall on a screen [widthDp] by [heightDp]. */
    fun countFor(widthDp: Int, heightDp: Int): Int =
        if (count > 0) count else FallScale.autoCount(base, FallScale.area(widthDp, heightDp))
    fun setSize(context: Context, v: Int) {
        size = v.coerceIn(FallScale.MIN_SIZE, FallScale.MAX_SIZE)
        context.getSharedPreferences("muninn", Context.MODE_PRIVATE).edit().putInt("fallSize", size).apply()
    }
    fun setCount(context: Context, v: Int) {
        count = v.coerceIn(FallScale.MIN_COUNT, FallScale.MAX_COUNT)
        context.getSharedPreferences("muninn", Context.MODE_PRIVATE).edit().putInt("fallCount", count).apply()
    }
}

/** What the Seasonal look lets fall: leaves and maple seeds (Autumn) or snowflakes (Snow). */
enum class Fall { LEAVES, SNOW }

/**
 * What falls behind everything in the Seasonal look: leaves in Autumn, snowflakes in Snow. Nothing is drawn in the
 * other looks. One engine moves both, so snow falls exactly as the leaves do and only the shapes differ: maple, oak
 * and birch leaves tumble, sway and glide on a slow wind with the odd gust, and maple seeds (samaras) spin as they
 * drop; in Snow every flake is a six-armed crystal that rocks, tumbles and flips the way a leaf does.
 *
 * Settings › Look › Animation: On or Off (nothing falls and the clock stops); Amount from 1 to 400 on screen and Size
 * from 15% to 600% ([LeafPrefs]).
 *
 * Cheap on the battery: the shapes are built once and drawn from cached paths in one Canvas, the frame clock runs at
 * about 30 frames a second and only while Muninn is on screen (it rests when the activity stops, so also when the
 * screen is off), each frame is one pass over the particles with nothing allocated, and with Reduce motion (Muninn's
 * or Android's "remove animations") everything lies still in a scatter.
 */
@Composable
fun SeasonFall() {
    if (!Palette.seasonal) return
    val kind = if (Palette.snow) Fall.SNOW else Fall.LEAVES
    if (!Seasons.falls(Access.seasonAnim)) return
    val context = LocalContext.current
    LeafPrefs.load(context)
    val config = LocalConfiguration.current
    val sizePct = LeafPrefs.size
    val n = LeafPrefs.countFor(config.screenWidthDp, config.screenHeightDp)
    val scene = remember(n, kind) {
        val seeds = FallScale.seeds(n)
        Scene(n - seeds, seeds, kind)
    }
    val still = Access.reduceMotion || remember(context) { systemStill(context) }
    var now by remember { mutableLongStateOf(0L) }
    val owner = LocalLifecycleOwner.current
    if (!still) LaunchedEffect(owner, scene) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val start = withFrameNanos { it }
            var last = 0L
            while (true) {
                val t = withFrameNanos { it } - start
                // About 30 frames a second: every other screen frame on a 60 Hz screen.
                if (t - last >= 31_000_000L) { last = t; now = t }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = if (still) 37f else now / 1_000_000_000f
        scene.draw(this, t, sizePct / 100f * density)
    }
}

/** Android's "Remove animations" (animator duration scale 0): everything holds still. */
private fun systemStill(context: Context): Boolean = try {
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
} catch (_: Exception) { false }

private const val TAU = (2 * PI).toFloat()

/** One leaf or seed (or the snowflake in its place) and the numbers that give it its own way of falling. Positions are worked out from the time, so nothing drifts between frames. */
private class Leaf(
    val kind: Int, val tint: Int, val x: Float, val depth: Int, val speed: Float, val sway: Float, val swayT: Float,
    val w: Float, val spin: Float, val flipT: Float, val phase: Float, val dir: Float, val glide: Float, val tumbleT: Float,
    val zig: Float, val rps: Float, val orbit: Float, val orbitT: Float,
)

/**
 * The leaves and seeds on screen, built once per count. [fall] only picks the shapes: Snow draws a flake where a leaf
 * or seed would be, at the same place, turn and tumble.
 */
private class Scene(leaves: Int, seeds: Int, private val fall: Fall) {
    private val all: Array<Leaf>
    init {
        val rnd = java.util.Random(907L + leaves * 31 + seeds)
        fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        all = Array(leaves + seeds) { i ->
            val depth = rnd.nextInt(3)
            val scale = 0.75f + depth * 0.25f
            if (i < leaves) Leaf(
                kind = rnd.nextInt(3), tint = rnd.nextInt(TINTS.size), x = rnd.nextFloat(), depth = depth,
                speed = r(0.038f, 0.078f) * scale, sway = r(6f, 28f), swayT = r(2.8f, 4.8f), w = r(14f, 24f) * scale,
                spin = r(-28f, 28f), flipT = r(1.8f, 3.4f), phase = r(0f, 100f), dir = if (rnd.nextBoolean()) 1f else -1f,
                glide = r(0.4f, 1f), tumbleT = r(2.8f, 5.4f), zig = r(0.6f, 1.3f), rps = 0f, orbit = 0f, orbitT = 1f,
            ) else Leaf(
                kind = 3, tint = rnd.nextInt(SEED_TINTS.size), x = rnd.nextFloat(), depth = depth,
                speed = r(0.055f, 0.08f) * scale, sway = 12f, swayT = r(3.2f, 4.9f), w = r(16f, 24f) * scale,
                spin = 0f, flipT = 1f, phase = r(0f, 100f), dir = if (rnd.nextBoolean()) 1f else -1f,
                glide = 0f, tumbleT = 1f, zig = 0f, rps = r(1.6f, 2.6f) * (if (rnd.nextBoolean()) 1f else -1f), orbit = r(9f, 12f), orbitT = r(3.2f, 3.8f),
            )
        }
    }

    fun draw(d: DrawScope, t: Float, unit: Float) {
        val w = d.size.width
        val h = d.size.height
        // The wind: a slow breath left and right, with a gust now and then that carries everything sideways.
        val breeze = sin(t / 17f * TAU) * 60f + sin(t / 41f * TAU) * 30f
        val g = sin(t / 13f * TAU).coerceAtLeast(0f)
        val gust = g * g * g * 140f
        val wind = (breeze + gust) * unit
        // One pass, nothing allocated: every number below is a plain float.
        for (i in all.indices) {
            val l = all[i]
            val tt = t + l.phase
            val size = l.w * unit
            // Off screen by its own size at each edge (no shape reaches further from its turning point), so a big
            // one leaves the screen whole before it comes back in on the other side.
            val margin = size
            // Top to bottom and round again: from 12% above the screen to 13% below it, or further for a big one.
            val top = minOf(-0.12f * h, -margin)
            val drop = maxOf(1.13f * h, h + margin) - top
            val y = top + (tt * l.speed * h) % drop
            var x = l.x * w + l.dir * l.glide * 22f * unit * tt + wind
            x += sin(tt / l.swayT * TAU) * l.sway * unit + sin(tt / (l.swayT * 2.7f) * TAU) * l.sway * 0.4f * unit
            val samara = l.kind == 3 && fall != Fall.SNOW
            if (samara) x += cos(tt / l.orbitT * TAU) * l.orbit * unit
            val span = w + 2f * margin
            x = ((x + margin) % span + span) % span - margin
            val alpha = when (l.depth) { 0 -> 0.6f; 1 -> 0.85f; else -> 1f }
            val s = size / 48f
            // In Snow every flake, seed slots included, rocks, tumbles and flips like a leaf (below).
            if (samara) {
                // A samara: spins steadily and swings round a small circle as it drops.
                val oy = sin(tt / l.orbitT * TAU) * l.orbit * 0.5f * unit
                d.withTransform({ translate(x, y + oy); rotate(l.rps * 360f * tt, Offset.Zero); scale(s, s, Offset.Zero) }) {
                    drawPath(SAMARA, SEED_BRUSHES[l.tint], alpha = alpha * 0.95f)
                    drawPath(SAMARA, SEED_EDGE, alpha = alpha * 0.6f, style = THIN)
                    drawPath(SAMARA_VEINS, SEED_EDGE, alpha = alpha * 0.55f, style = HAIR)
                    drawOval(SEED_EDGE, Offset(-3.2f, -3f), androidx.compose.ui.geometry.Size(8.4f, 6f), alpha = alpha)
                }
                continue
            }
            // A leaf: rocks on the sway, tumbles over now and then (zig), and turns edge-on and back (flip) as it falls.
            // A seed slot carrying a snowflake has no leaf numbers of its own: steady ones in their place.
            val seed = l.kind == 3
            val spin = if (seed) 16f * l.dir else l.spin
            val zig = if (seed) 0.8f else l.zig
            val tumbleT = if (seed) l.orbitT + 0.6f else l.tumbleT
            val flipT = if (seed) 2.2f + (l.phase % 1f) else l.flipT
            val rot = spin * sin(tt / l.swayT * TAU) + sin(tt * 0.6f) * 10f + zig * 28f * sin(tt / tumbleT * TAU * 2f)
            val flip = 0.55f + 0.45f * cos(tt / flipT * TAU)
            if (fall == Fall.SNOW) {
                // A snowflake in the leaf's place: six-armed crystals in three sizes, so each one shows its turn and flip.
                d.withTransform({ translate(x, y); rotate(rot, Offset.Zero); scale(s * flip, s, Offset.Zero) }) {
                    when (l.kind) {
                        0 -> drawPath(CRYSTAL, Color.White, alpha = alpha * FLAKE_ALPHA, style = FLAKE_STROKE)
                        1 -> scale(0.78f, 0.78f, Offset.Zero) { drawPath(CRYSTAL, Color.White, alpha = alpha * FLAKE_ALPHA, style = FLAKE_STROKE) }
                        else -> drawPath(SMALL_CRYSTAL, Color.White, alpha = alpha * FLAKE_ALPHA, style = FLAKE_STROKE)
                    }
                }
                continue
            }
            val path = when (l.kind) { 0 -> MAPLE; 1 -> OAK; else -> BIRCH }
            val veins = when (l.kind) { 0 -> MAPLE_VEINS; 1 -> OAK_VEINS; else -> BIRCH_VEINS }
            val edge = EDGES[l.tint]
            d.withTransform({ translate(x, y); rotate(rot, Offset.Zero); scale(s * flip, s, Offset.Zero) }) {
                drawPath(path, BRUSHES[l.tint], alpha = alpha)
                drawPath(path, edge, alpha = alpha * 0.9f, style = THIN)
                drawPath(veins, edge, alpha = alpha * 0.8f, style = HAIR)
                drawPath(STEM, edge, alpha = alpha, style = STEM_STROKE)
            }
        }
    }

    companion object {
        private val THIN = Stroke(0.35f)
        private val HAIR = Stroke(0.7f)
        private val STEM_STROKE = Stroke(1.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        private val SEED_EDGE = Color(0xFF6A4A22)

        /** Snowflakes: white, a little see-through. */
        private const val FLAKE_ALPHA = 0.85f
        private val FLAKE_STROKE = Stroke(1.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        /** Soft round flakes: white in the middle, fading out at the edge. */
        /** A six-armed crystal round the origin: [r] long arms with a pair of side branches on each (two pairs if [twigs]). */
        private fun crystal(r: Float, twigs: Boolean): Path = Path().apply {
            val third = (PI / 3).toFloat()
            for (i in 0 until 6) {
                val a = i * third
                val c = cos(a)
                val n = sin(a)
                moveTo(0f, 0f); lineTo(c * r, n * r)
                for (at in if (twigs) floatArrayOf(0.5f, 0.75f) else floatArrayOf(0.55f)) {
                    val len = r * (if (at > 0.6f) 0.2f else 0.3f)
                    for (side in intArrayOf(-1, 1)) {
                        val b = a + side * third
                        moveTo(c * r * at, n * r * at); lineTo(c * r * at + cos(b) * len, n * r * at + sin(b) * len)
                    }
                }
            }
        }
        private val CRYSTAL = crystal(14f, true)
        private val SMALL_CRYSTAL = crystal(9f, false)

        /** Leaf colours (light centre, body, dark edge): gold, orange, rust, red, tan, straw. */
        private val TINTS = listOf(
            Triple(0xFFF7D994, 0xFFE8B45A, 0xFF9A6A22), Triple(0xFFF6C384, 0xFFE59A4C, 0xFF8D4F1C),
            Triple(0xFFE88A5E, 0xFFC45A2E, 0xFF6E2C12), Triple(0xFFDD7A56, 0xFFB9452A, 0xFF5E2010),
            Triple(0xFFCF9A55, 0xFFA86A2A, 0xFF5A3512), Triple(0xFFF2D57A, 0xFFD9A93F, 0xFF8C6A1D),
        )
        private val BRUSHES = TINTS.map { (a, b, c) ->
            Brush.radialGradient(0f to Color(a), 0.55f to Color(b), 1f to Color(c), center = Offset(14f, -2f), radius = 26f)
        }
        private val EDGES = TINTS.map { Color(it.third) }
        private val SEED_TINTS = listOf(Triple(0xFF6A4A22, 0xFFB89A5C, 0xFFE9D9A8), Triple(0xFF7A5A2A, 0xFFC9A86A, 0xFFE9D9A8), Triple(0xFF8A6A33, 0xFFD4B67A, 0xFFE9D9A8))
        private val SEED_BRUSHES = SEED_TINTS.map { (a, b, c) ->
            Brush.horizontalGradient(0f to Color(a), 0.25f to Color(b), 1f to Color(c), startX = 0f, endX = 38f)
        }

        /** The stem, from just left of the leaf's base. */
        private val STEM = Path().apply { moveTo(-7f, 1.2f); quadraticTo(-3.5f, 0.4f, 0f, 0f) }

        /** A maple leaf: five pointed lobes, the base at the origin, the tip at x = 35. */
        private val MAPLE = Path().apply {
            moveTo(1f, -2f); quadraticTo(3.1f, -5.5f, 4f, -9f); quadraticTo(2.4f, -12f, 2f, -15f); lineTo(7f, -23f); lineTo(10f, -18f)
            quadraticTo(12.6f, -19f, 14f, -20f); quadraticTo(12.9f, -16.5f, 13f, -13f); quadraticTo(15.6f, -14.5f, 17f, -16f); lineTo(23f, -21f); lineTo(26f, -15f)
            quadraticTo(24.4f, -12.5f, 24f, -10f); quadraticTo(21.9f, -8.5f, 21f, -7f); quadraticTo(23.6f, -7f, 25f, -7f); quadraticTo(27.6f, -8f, 29f, -9f)
            quadraticTo(32.1f, -6f, 34f, -3f); lineTo(35f, 0f); lineTo(34f, 3f); quadraticTo(30.9f, 6f, 29f, 9f); quadraticTo(26.4f, 8f, 25f, 7f); quadraticTo(22.4f, 7f, 21f, 7f)
            quadraticTo(23.1f, 8.5f, 24f, 10f); quadraticTo(25.6f, 12.5f, 26f, 15f); lineTo(23f, 21f); lineTo(17f, 16f); quadraticTo(14.4f, 14.5f, 13f, 13f)
            quadraticTo(14.1f, 16.5f, 14f, 20f); quadraticTo(11.4f, 19f, 10f, 18f); lineTo(7f, 23f); lineTo(2f, 15f); quadraticTo(3.6f, 12f, 4f, 9f); quadraticTo(1.9f, 5.5f, 1f, 2f); close()
        }
        private val MAPLE_VEINS = Path().apply {
            moveTo(0f, 0f); quadraticTo(19.5f, 0f, 35f, 0f)
            moveTo(0f, 0f); quadraticTo(13.5f, -10.5f, 23f, -21f); moveTo(0f, 0f); quadraticTo(13.5f, 10.5f, 23f, 21f)
            moveTo(0f, 0f); quadraticTo(5.5f, -11.5f, 7f, -23f); moveTo(0f, 0f); quadraticTo(5.5f, 11.5f, 7f, 23f)
        }

        /** An oak leaf: rounded lobes, drawn from its traced outline. */
        private val OAK = outline(floatArrayOf(
            29.0f, 0.0f, 28.1f, 1.7f, 26.0f, 3.2f, 24.0f, 4.3f, 23.0f, 5.5f, 23.3f, 7.2f, 24.2f, 9.3f, 24.6f, 11.4f, 23.6f, 12.8f, 21.3f, 13.1f, 18.6f, 12.7f,
            16.5f, 12.3f, 15.4f, 12.9f, 14.8f, 14.4f, 14.4f, 16.4f, 13.4f, 17.8f, 11.7f, 17.5f, 9.8f, 15.8f, 8.2f, 13.6f, 6.9f, 11.9f, 6.0f, 11.2f, 5.1f, 11.7f,
            3.9f, 13.0f, 2.5f, 14.5f, 1.1f, 15.0f, 0.3f, 13.8f, 0.2f, 11.4f, 0.5f, 8.9f, 0.9f, 7.1f, 0.9f, 5.1f, 0.5f, 3.0f, 0.1f, 1.4f, 0.0f, 0.0f,
            0.1f, -1.4f, 0.5f, -3.0f, 0.9f, -5.1f, 0.9f, -7.1f, 0.5f, -8.9f, 0.2f, -11.4f, 0.3f, -13.8f, 1.1f, -15.0f, 2.5f, -14.5f, 3.9f, -13.0f, 5.1f, -11.7f,
            6.0f, -11.2f, 6.9f, -11.9f, 8.2f, -13.6f, 9.8f, -15.8f, 11.7f, -17.5f, 13.4f, -17.8f, 14.4f, -16.4f, 14.8f, -14.4f, 15.4f, -12.9f, 16.5f, -12.3f,
            18.6f, -12.7f, 21.3f, -13.1f, 23.6f, -12.8f, 24.6f, -11.4f, 24.2f, -9.3f, 23.3f, -7.2f, 23.0f, -5.5f, 24.0f, -4.3f, 26.0f, -3.2f, 28.1f, -1.7f,
        ))
        private val OAK_VEINS = Path().apply {
            moveTo(0f, 0f); quadraticTo(15f, 0f, 26f, 0f)
            moveTo(0f, 0f); quadraticTo(10f, -5.5f, 16f, -11f); moveTo(0f, 0f); quadraticTo(10f, 5.5f, 16f, 11f)
            moveTo(0f, 0f); quadraticTo(6f, -6f, 8f, -12f); moveTo(0f, 0f); quadraticTo(6f, 6f, 8f, 12f)
        }

        /** A birch leaf: a pointed oval with a toothed edge, drawn from its traced outline. */
        private val BIRCH = outline(floatArrayOf(
            36.0f, 0.0f, 36.0f, 1.7f, 33.6f, 3.1f, 32.8f, 4.5f, 30.4f, 5.3f, 28.1f, 5.8f, 27.3f, 6.8f, 24.9f, 6.7f, 24.0f, 7.3f, 23.2f, 7.9f, 21.4f, 7.5f,
            21.2f, 8.4f, 20.1f, 8.3f, 19.0f, 8.2f, 18.7f, 9.2f, 17.5f, 8.4f, 16.9f, 8.8f, 16.2f, 9.3f, 15.3f, 8.4f, 14.7f, 9.4f, 14.0f, 9.0f, 13.3f, 8.6f,
            12.5f, 9.6f, 12.0f, 8.5f, 11.1f, 8.8f, 10.2f, 9.2f, 9.9f, 8.1f, 8.6f, 8.9f, 8.0f, 8.3f, 7.3f, 7.8f, 5.7f, 8.3f, 5.5f, 7.2f, 4.2f, 7.1f, 2.8f, 6.8f,
            2.7f, 5.8f, 0.7f, 5.5f, 0.1f, 4.5f, -0.3f, 3.4f, -2.0f, 2.5f, -1.4f, 1.2f, -2.0f, 0.0f, -2.3f, -1.3f, -0.8f, -2.4f, -1.1f, -3.6f, 0.1f, -4.5f,
            1.5f, -5.2f, 1.6f, -6.3f, 3.5f, -6.4f, 4.2f, -7.1f, 4.9f, -7.8f, 6.6f, -7.4f, 6.8f, -8.4f, 8.0f, -8.3f, 9.0f, -8.2f, 9.3f, -9.2f, 10.5f, -8.4f,
            11.1f, -8.8f, 11.8f, -9.3f, 12.7f, -8.4f, 13.3f, -9.4f, 14.0f, -9.0f, 14.7f, -8.6f, 15.5f, -9.6f, 16.0f, -8.5f, 16.9f, -8.8f, 17.8f, -9.2f,
            18.1f, -8.1f, 19.4f, -8.9f, 20.1f, -8.3f, 20.7f, -7.8f, 22.3f, -8.3f, 22.6f, -7.3f, 24.0f, -7.3f, 25.6f, -7.1f, 26.2f, -6.2f, 28.9f, -6.2f,
            30.4f, -5.3f, 32.0f, -4.3f, 34.8f, -3.3f, 35.1f, -1.7f,
        ))
        private val BIRCH_VEINS = Path().apply {
            moveTo(0f, 0f); quadraticTo(18f, 0f, 32f, 0f)
            moveTo(0f, 0f); quadraticTo(9f, -4f, 14f, -8f); moveTo(0f, 0f); quadraticTo(9f, 4f, 14f, 8f)
            moveTo(0f, 0f); quadraticTo(13f, -3f, 22f, -6f); moveTo(0f, 0f); quadraticTo(13f, 3f, 22f, 6f)
        }

        /** A maple seed: the wing, with the seed itself at the origin. */
        private val SAMARA = Path().apply {
            moveTo(2f, 0f); cubicTo(8f, -9f, 22f, -11f, 34f, -7f); cubicTo(38f, -5f, 38f, 1f, 34f, 3f); cubicTo(24f, 7f, 10f, 7f, 2f, 2f); close()
        }
        private val SAMARA_VEINS = Path().apply {
            moveTo(4f, -1f); quadraticTo(18f, -6f, 33f, -6f); moveTo(4f, 0f); quadraticTo(18f, -3f, 33f, -3f)
            moveTo(4f, 0.5f); quadraticTo(18f, 0f, 33f, -1f); moveTo(4f, 1f); quadraticTo(18f, 2f, 33f, 1f)
        }

        /** A closed outline through [pts] (x, y pairs), smoothed through the midpoints so it has no corners. */
        private fun outline(pts: FloatArray): Path = Path().apply {
            val n = pts.size / 2
            fun x(i: Int) = pts[(i % n) * 2]
            fun y(i: Int) = pts[(i % n) * 2 + 1]
            moveTo((x(0) + x(1)) / 2f, (y(0) + y(1)) / 2f)
            for (i in 1..n) quadraticTo(x(i), y(i), (x(i) + x(i + 1)) / 2f, (y(i) + y(i + 1)) / 2f)
            close()
        }
    }
}
