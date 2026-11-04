package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.update.FullScreenKeep

/**
 * Right after an update that turned "Full-screen calls" off (Android 14 and newer): one card with one tap to Android's
 * switch, at most once per version (update/FullScreenKeep). Never on a Kids' Device. Also notes the switch as it is
 * now, as the next update's "before".
 */
@Composable
fun FullScreenAfterUpdate(app: Muninn, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val kid = io.github.ravenor907.muninn.tv.KidMode.on(app)
    var show by remember { mutableStateOf(false) }
    val ticks = remember { intArrayOf(0) }
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500) {
        // The before and after record: at once, then every 15 seconds while the chat list shows.
        if (ticks[0]++ % 10 == 0) FullScreenKeep.look(ctx, kid)
        val want = !kid && FullScreenKeep.card(ctx)
        // Turned back on some other way: the card has done its job for this version.
        if (show && !want) FullScreenKeep.cardDone(ctx)
        show = want
    }
    if (!show) return
    Row(modifier.fillMaxWidth().clip(Corner).background(Palette.panel).border(1.dp, Palette.line, Corner)
        .padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Full-screen calls are off", style = Type.normal.copy(color = Palette.text))
            Text("Android turned them off with the update. Calls won't show on the lock screen.", style = Type.small)
        }
        MButton("Turn on", Modifier.padding(start = 8.dp), Kind.Accent) {
            FullScreenKeep.cardDone(ctx); show = false
            openFullScreenSetting(ctx)
        }
        IconAction("close", "Not now", Palette.textMuted, size = 18.dp) { FullScreenKeep.cardDone(ctx); show = false }
    }
}
