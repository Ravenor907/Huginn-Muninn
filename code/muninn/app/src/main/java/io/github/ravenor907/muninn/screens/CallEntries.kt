package io.github.ravenor907.muninn.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import java.text.DateFormat
import java.util.Date

/** One call in a chat's timeline: missed, answered, declined... Tap it to call back. */
@Composable
internal fun CallLine(l: CallManager.Log, onCall: () -> Unit) {
    val missed = l.result == "missed"
    val tint = if (missed) Palette.danger else Palette.textMuted
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.Center) {
        Row(Modifier.heightIn(min = 36.dp).clip(CircleShape).background(Palette.panel)
            .clickable(role = Role.Button, onClickLabel = "Call ${l.name}", onClick = onCall).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MIcon("call", tint, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(describeCall(l), style = Type.small.copy(color = tint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Missed call · 3:41 PM", "Outgoing call · 2:05 · 9:12 AM". */
internal fun describeCall(l: CallManager.Log): String {
    val what = when (l.result) {
        "missed" -> "Missed call"
        "declined" -> if (l.outgoing) "Call declined" else "You declined a call"
        "noanswer" -> "No answer"
        "busy" -> "Busy"
        "failed" -> "Call couldn't connect"
        else -> if (l.outgoing) "Outgoing call" else "Incoming call"
    }
    val len = if (l.seconds > 0) " · " + (if (l.seconds >= 3600) "%d:%02d:%02d".format(l.seconds / 3600, l.seconds / 60 % 60, l.seconds % 60)
        else "%d:%02d".format(l.seconds / 60, l.seconds % 60)) else ""
    return what + len + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(l.at))
}

/** The calls with [key] after [from] (ms, exclusive) up to [to] (inclusive), oldest first. */
internal fun callsBetween(calls: List<CallManager.Log>, from: Long, to: Long): List<CallManager.Log> =
    calls.filter { it.at > from && it.at <= to }
