package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.TransferView
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Banners put away (Close, Back on a TV, or ended a while ago): for as long as Muninn runs. */
object TransferSeen {
    val hidden = MutableStateFlow<Set<String>>(emptySet())
    fun hide(key: String) = hidden.update { (it + key).let { s -> if (s.size > 200) s.drop(s.size - 200).toSet() else s } }
}

/**
 * The banner for a file on its way, as [TransferView.pick] chooses it: the nearby transfer, a straight one, or how the
 * last one ended (that goes by itself after [TransferView.ENDED_MS]). [skipPeer]: a chat that shows its own cards.
 */
@Composable
fun transferBanner(app: Muninn, skipPeer: String = ""): TransferView.Banner? {
    val t by app.net.transfer.collectAsState()
    val fin by app.net.finished.collectAsState()
    val links by FileLink.all.collectAsState()
    val hidden by TransferSeen.hidden.collectAsState()
    val nearby = TransferView.nearby(t.id, t.active, t.sending, t.preparing, t.started > 0, t.done, t.total, t.files.map { it.name }, t.peer)
    val ended = fin?.let { (ok, msg) -> TransferView.ended(app.net.finishedId, ok, msg) }
    val b = TransferView.pick(nearby, ended, links.values, { app.chat.nameOf(it) }, skipPeer, hidden)
    val key = b?.key
    val running = b?.running == true
    LaunchedEffect(key, running) { if (key != null && !running) { delay(TransferView.ENDED_MS); TransferSeen.hide(key) } }
    return b
}

/**
 * A file on its way, over every screen of a phone or tablet: what it is, "Sending 42%" with a bar, then how it ended.
 * Tapping it opens Files ([onOpen]) for a nearby one; Close puts it away.
 */
@Composable
fun TransferStrip(app: Muninn, skipPeer: String = "", onOpen: () -> Unit) {
    val b = transferBanner(app, skipPeer) ?: return
    val icon = when {
        !b.ok -> "info"
        !b.running -> "check"
        b.line.startsWith("Receiving") -> "download"
        else -> "upload"
    }
    Column(Modifier.fillMaxWidth().background(if (b.ok) Palette.raised else Palette.dangerFill)
        .clickable(role = Role.Button, onClickLabel = "Open Files", enabled = b.key.startsWith("nearby:") || b.key.startsWith("ended:")) { onOpen() }
        .padding(start = 12.dp, top = 4.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MIcon(icon, if (b.ok) Palette.goldBright else Palette.danger, 20.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(b.title, style = Type.normal.copy(color = if (b.ok) Palette.text else Palette.danger), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (b.line.isNotEmpty()) Text(b.line, style = Type.small.copy(color = Palette.textMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            IconAction("close", "Hide", Palette.textMuted, size = 18.dp) { TransferSeen.hide(b.key) }
        }
        b.fraction?.let { Bar(it, Modifier.padding(top = 4.dp, end = 12.dp), Palette.gold) }
    }
}
