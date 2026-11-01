package io.github.ravenor907.muninn.ui

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
import androidx.compose.ui.unit.dp

/** Huginn's line icons (the same 24-unit paths), so both apps look like one family. */
object Icons {
    val paths = mapOf(
        "folder" to "M3 6h6l2 2h10v11H3z",
        "file" to "M6 3h8l4 4v14H6z M14 3v4h4",
        "image" to "M3 5h18v14H3z M3 16l5-5 4 4 3-3 6 6",
        "text" to "M6 3h8l4 4v14H6z M14 3v4h4 M9 12h6 M9 15h6 M9 18h4",
        "video" to "M3 6h13v12H3z M16 10l5-3v10l-5-3",
        "audio" to "M9 18V5l11-2v13 M6 18a3 3 0 1 0 0.01 0 M17 16a3 3 0 1 0 0.01 0",
        "home" to "M3 11l9-7 9 7 M5 10v10h14V10 M10 20v-6h4v6",
        "download" to "M12 4v11 M7 10l5 5 5-5 M5 20h14",
        "network" to "M3 5h18v10H3z M8 19h8 M12 15v4",
        "server" to "M4 6h16v5H4z M4 13h16v5H4z M7 8.5h0.01 M7 15.5h0.01",
        "phone" to "M8 3h8v18H8z M11 18h2",
        "gear" to "M12 9a3 3 0 1 0 0.01 0 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M4.9 4.9l2.1 2.1 M17 17l2.1 2.1 M4.9 19.1L7 17 M17 7l2.1-2.1",
        "search" to "M11 4a7 7 0 1 0 0.01 0 M20 20l-3.5-3.5",
        "copy" to "M8 8h11v11H8z M5 16V5h11",
        "delete" to "M5 7h14 M9 7V4h6v3 M7 7l1 13h8l1-13",
        "more" to "M4 11h2v2h-2z M11 11h2v2h-2z M18 11h2v2h-2z",
        "info" to "M12 3l9 9-9 9-9-9z M12 10v6 M12 7v1",
        "check" to "M5 12l5 5 9-10",
        "back" to "M15 5l-7 7 7 7",
        "forward" to "M9 5l7 7-7 7",
        "undo" to "M9 14L4 9l5-5 M4 9h11a5 5 0 0 1 0 10h-3",
        "link" to "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
        "lock" to "M6 11h12v9H6z M9 11V8a3 3 0 0 1 6 0v3",
        "open" to "M5 12h14 M12 5l7 7-7 7",
        "clock" to "M12 3a9 9 0 1 0 0.01 0 M12 7v5l3 3",
        "chat" to "M4 5h16v11H9l-5 4z",
        "send" to "M3 11l18-8-8 18-2-8z",
        "wifi" to "M2 9a15 15 0 0 1 20 0 M5 12.5a10 10 0 0 1 14 0 M8.5 16a5 5 0 0 1 7 0 M12 19.5h0.01",
        "user" to "M12 4a4 4 0 1 0 0.01 0 M4 21a8 8 0 0 1 16 0",
        "mute" to "M4 9h4l5-4v14l-5-4H4z M16 9l5 6 M21 9l-5 6",
        "close" to "M5 5l14 14 M19 5L5 19",
        "plus" to "M12 5v14 M5 12h14",
        "mic" to "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z M5 11a7 7 0 0 0 14 0 M12 18v3",
        "play" to "M7 4l13 8-13 8z",
        "pause" to "M7 4v16 M17 4v16",
        "pausefill" to "M6 4h4v16H6z M14 4h4v16h-4z",
        "shield" to "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z",
        "upload" to "M12 16V4 M7 9l5-5 5 5 M4 20h16",
        "call" to "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2",
        "people" to "M9 4a4 4 0 1 0 0.01 0 M2 20c1-4 4-6 7-6s6 2 7 6 M17 11a3 3 0 1 0 0-6 M22 20c-.6-2.5-2-4-4-4.6",
        "tv" to "M3 5h18v12H3z M8 21h8",
        "refresh" to "M20 12a8 8 0 1 1-2.3-5.7 M20 4v4h-4",
        "reply" to "M10 6l-6 6 6 6 M4 12h10a6 6 0 0 1 6 6v1",
        "smile" to "M12 3a9 9 0 1 0 0.01 0 M8 14a5 5 0 0 0 8 0 M9 9.5h0.01 M15 9.5h0.01",
        "edit" to "M4 20h4L19 9l-4-4L4 16z M14 6l4 4",
        "pin" to "M9 3h6l-1 6 4 4H6l4-4z M12 13v8",
        "qr" to "M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h2v2h-2z M18 18h2v2h-2z M14 18h2 M18 14h2",
    )
}

@Composable
fun MIcon(name: String, color: Color = Palette.textMuted, size: Dp = 24.dp, modifier: Modifier = Modifier, weight: Float = 1.6f, filled: Boolean = false) {
    val path = remember(name) { PathParser().parsePathString(Icons.paths[name] ?: Icons.paths.getValue("file")).toPath() }
    // In the icon's own 24-unit space, so it scales with the icon once (big icons on a TV stay as light as small ones).
    val boldness = weight
    Canvas(modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            if (filled) drawPath(path, color)
            else drawPath(path, color, style = Stroke(width = boldness, cap = StrokeCap.Square, join = StrokeJoin.Miter))
        }
    }
}
