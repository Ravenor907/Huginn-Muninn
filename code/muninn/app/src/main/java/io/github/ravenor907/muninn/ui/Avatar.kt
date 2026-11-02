package io.github.ravenor907.muninn.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn

/**
 * A person's round picture, or a round disc in their colour with their initial. Pictures are round in every look.
 * [key] is the chat key, or "me". [showDot] adds the status dot on the corner ([StatusDot]): filled online, a hollow
 * ring away, a minus for do not disturb (so it never depends on colour alone), grey offline, none when nothing is known.
 * Color vision draws a picked disc colour as the nearest disc of its own set ([Palette.disc]).
 * [active]: they're active now (cap m1) and nothing else is known, so the dot shows green and says "Active now".
 * TalkBack hears the status in words; the name is read from the text beside the avatar, as before.
 */
@Composable
fun Avatar(key: String, name: String, size: Dp, showDot: Boolean = false, modifier: Modifier = Modifier, active: Boolean = false) {
    val app = LocalContext.current.applicationContext as Muninn
    val ready by app.ready.collectAsState()
    val profile = if (ready) app.profile else null
    val versions by (profile?.pictures ?: NONE).collectAsState()
    val version = versions[key]
    val picture = remember(profile, key, version) { if (profile == null || version == null) null else runCatching { profile.picture(key) }.getOrNull() }
    val myColour by (profile?.myColour ?: EMPTY).collectAsState()
    val (bg, fg) = if (key == "me") (Palette.parse(myColour)?.let { Palette.disc(it) } ?: (Palette.goldTint to Palette.goldBright))
        else (if (profile != null && key.isNotEmpty() && !Access.contrast) runCatching { Palette.parse(profile.colourOf(key)) }.getOrNull() else null)
            ?.let { Palette.disc(it) } ?: Palette.avatarTone(name)
    Box(modifier.size(size)) {
        if (picture != null) Image(picture.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape))
        else Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            // The initial is sized to the disc, not to the font size, so it never spills out of a small avatar.
            val letter = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.4f).toSp() }
            Text(initialOf(name), style = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = letter, lineHeight = letter, color = fg),
                maxLines = 1, softWrap = false)
        }
        if (showDot) {
            // Watched so the dot follows a change of status; the words come from statusOf.
            val mine by (profile?.myStatus ?: EMPTY).collectAsState()
            val all by (profile?.statuses ?: NO_STATUS).collectAsState()
            val status = if (key == "me") mine else remember(all, key) { runCatching { profile?.statusOf(key) ?: "" }.getOrDefault("") }
            StatusDot(if (status.isEmpty() && active) "active" else status, size * 0.3f, Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * The status dot: [status] is "online", "away", "dnd", "offline" or "active" (active now); anything else draws nothing.
 * Each has its own shape, so it never depends on color alone: online (and active) filled, away a hollow ring, do not
 * disturb a minus, offline a plain gray dot.
 */
@Composable
fun StatusDot(status: String, size: Dp, modifier: Modifier = Modifier) {
    val (colour, words) = when (status) {
        "online" -> Palette.online to "Online"
        "active" -> Palette.online to "Active now"
        "away" -> Palette.away to "Away"
        "dnd" -> Palette.dnd to "Do not disturb"
        "offline" -> Palette.offline to "Offline"
        else -> return
    }
    Box(modifier.size(size).clip(CircleShape).background(Palette.ground).padding(size * 0.14f).clip(CircleShape).background(colour)
        .semantics { contentDescription = words }, contentAlignment = Alignment.Center) {
        if (status == "dnd") Box(Modifier.fillMaxWidth(0.6f).height((size.value * 0.14f).coerceAtLeast(1.5f).dp).background(Palette.ground))
        // Away: the middle cut out, leaving a ring.
        if (status == "away") Box(Modifier.fillMaxSize(0.5f).clip(CircleShape).background(Palette.ground))
    }
}

/** The first letter or digit of a name, in capitals; "?" for an empty one. */
fun initialOf(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

private val NONE = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>>(emptyMap())
private val EMPTY = kotlinx.coroutines.flow.MutableStateFlow("")
private val NO_STATUS = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
