package io.github.ravenor907.muninn.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import io.github.ravenor907.muninn.ui.MIcon

/** Icons only the kids' screens use, drawn like the app's own line icons (24-unit paths). */
internal object TvIcons {
    /** "Say it": a head speaking, with two sound lines. */
    const val SAY = "say"
    val paths = mapOf(
        SAY to "M9 4a4 4 0 1 0 0.01 0 M3 21a6 6 0 0 1 12 0 M15.5 5.5a3.5 3.5 0 0 1 0 5 M18 3a7 7 0 0 1 0 10",
    )
}

/** One of [TvIcons], else the app's icon of that name. */
@Composable
internal fun TvIcon(name: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val own = TvIcons.paths[name]
    if (own == null) { MIcon(name, color, size, modifier); return }
    val path = remember(name) { PathParser().parsePathString(own).toPath() }
    val boldness = 1.6f * maxOf(1f, size.value / 28f)
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            drawPath(path, color, style = Stroke(width = boldness, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
    }
}
