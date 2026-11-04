package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // Round 19: never on a TV, used as a Kids' Device or not (Google TV has no page for this switch).
    val tv = remember { io.github.ravenor907.muninn.tv.KidMode.isTv(ctx) }
    var show by remember { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    // Each time Muninn comes back on screen (from Android's switch too), not on a timer: the before and after record,
    // then whether the card shows. Off the main thread (two calls to Android).
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val want = withContext(Dispatchers.IO) {
                FullScreenKeep.look(ctx, kid)
                !kid && !tv && FullScreenKeep.card(ctx)
            }
            // Turned back on some other way: the card has done its job for this version.
            if (show && !want) withContext(Dispatchers.IO) { FullScreenKeep.cardDone(ctx) }
            show = want
        }
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
