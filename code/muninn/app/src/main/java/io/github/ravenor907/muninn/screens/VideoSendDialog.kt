package io.github.ravenor907.muninn.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.GroupVideo
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.UltraPrivacy
import io.github.ravenor907.muninn.net.VideoPace
import io.github.ravenor907.muninn.net.VideoPick
import io.github.ravenor907.muninn.net.VideoSend
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Picking
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cap v3: a group video message of mine whose relay pieces went paced (VideoPace): one line a member, "Anna: smaller
 * copy, 40% sent". Empty for one that wasn't paced (an older one, or since a restart).
 */
internal fun pacedVideoLines(gm: Groups.GMsg, nameOf: (String) -> String): List<String> {
    val sent = VideoPace.sent.value
    return gm.to.mapNotNull { k ->
        sent[VideoPace.key(k, Proto06.envelopeId(gm.id, k))]?.let { (n, of) ->
            GroupVideo.progressLine(nameOf(k), GroupVideo.RELAY, n, of, false)
        }
    }
}

/** What the "Video from your phone" line says under its name (or why it's off). */
internal fun pickedVideoDetail(): String =
    if (UltraPrivacy.on) "Off while Ultra privacy is on" else "Full size close by, up to 20 seconds over the internet"

/**
 * Cap v3 (protocol.md 29): the "Video from your phone" picker for a chat. Returns what the Send sheet's line runs; the
 * picked video then opens [VideoSendDialog] here. Ultra privacy on: nothing opens, and it says why.
 */
@Composable
internal fun rememberVideoPicker(app: Muninn, target: VideoSend.Target): () -> Unit {
    var picked by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) picked = uri }
    picked?.let { u -> VideoSendDialog(app, target, u) { picked = null } }
    return {
        if (UltraPrivacy.on) Ui.say("Sending videos is off while Ultra privacy is on.", true)
        else try { Picking.launch(launcher, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
        catch (_: Exception) { Ui.say("This phone has no video picker.", true) }
    }
}

/**
 * A picked video, before it goes: what it is, who gets the full copy and who the smaller one, and for a video longer
 * than 20 seconds a choice: trim it (a slider picks which 20 seconds) or send it in full later, straight, to those who
 * can take it. Then the copies are made here with their progress, and it closes once they're on their way.
 */
@Composable
internal fun VideoSendDialog(app: Muninn, target: VideoSend.Target, uri: Uri, done: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = app.identity.publicKey
    var info by remember { mutableStateOf<GroupVideo.Info?>(null) }
    var failed by remember { mutableStateOf("") }
    var start by remember { mutableIntStateOf(0) }
    var inFull by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var working by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(uri) {
        val i = withContext(Dispatchers.IO) { VideoPick.probe(context, uri) }
        if (i == null) failed = "That video can't be read." else info = i
    }
    LaunchedEffect(info, inFull) {
        if (info != null) plan = withContext(Dispatchers.IO) { VideoSend.plan(context, app.chat, target, me, inFull) }
    }
    val nameOf: (String) -> String = { k -> app.chat.nameOf(k) }
    val i = info
    val length = i?.let { GroupVideo.length(it.ms) } ?: GroupVideo.SHORT
    val clip = if (i != null && length != GroupVideo.SHORT && !inFull) GroupVideo.trim(i.ms, start) else null
    val sendNow = {
        val v = info
        if (v != null && working.isEmpty()) {
            working = "Getting ready"
            scope.launch(Dispatchers.IO) {
                val said = VideoSend.send(context, app.chat, target, me, uri, v, clip, inFull) { what, f -> working = what; progress = f }
                withContext(Dispatchers.Main) { Ui.say(said); done() }
            }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (working.isEmpty()) done() },
        containerColor = Palette.panel,
        title = { Text("Send a video", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    UltraPrivacy.on -> Text("Off while Ultra privacy is on. Videos you get still play.", style = Type.normal)
                    failed.isNotEmpty() -> Text(failed, style = Type.normal.copy(color = Palette.danger))
                    i == null -> Text("Checking the video", style = Type.normal)
                    working.isNotEmpty() -> {
                        Text(working, style = Type.normal)
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Palette.accent, trackColor = Palette.track,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).semantics { contentDescription = "$working, ${(progress * 100).toInt()}%" })
                        Text("Keep Muninn open until it says it's sending.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                    }
                    else -> {
                        Text("${GroupVideo.clock(i.ms)} long", style = Type.normal)
                        if (length != GroupVideo.SHORT) {
                            Text(if (length == GroupVideo.LONG) "Over the internet, videos can be up to 20 seconds. Trim it, or send it in full later to those it can reach straight."
                                else "Over the internet, videos can be up to 20 seconds. Trim it to send it here; longer videos can go as a File.",
                                style = Type.small, modifier = Modifier.padding(top = 6.dp))
                            if (!inFull) {
                                val last = GroupVideo.lastStart(i.ms)
                                val (s, e) = GroupVideo.trim(i.ms, start)
                                val said = "From ${GroupVideo.clock(s)} to ${GroupVideo.clock(e)}"
                                Text(said, style = Type.normal.copy(color = Palette.textSoft), modifier = Modifier.padding(top = 10.dp))
                                Slider(value = start.toFloat(), onValueChange = { start = it.toInt().coerceIn(0, last) }, valueRange = 0f..last.toFloat().coerceAtLeast(1f),
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Which 20 seconds, $said" },
                                    colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent, inactiveTrackColor = Palette.track))
                            }
                            if (length == GroupVideo.LONG) Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                MButton(if (inFull) "Trim to 20 seconds instead" else "Send in full later, when direct", Modifier.fillMaxWidth()) { inFull = !inFull }
                            }
                        }
                        Text(GroupVideo.summary(plan, nameOf), style = Type.small, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        },
        confirmButton = {
            val ready = !UltraPrivacy.on && i != null && failed.isEmpty() && working.isEmpty() &&
                plan.values.any { it != GroupVideo.NONE }
            if (working.isEmpty()) MButton("Send", kind = Kind.Accent, enabled = ready) { sendNow() }
        },
        dismissButton = { if (working.isEmpty()) MButton("Cancel") { done() } },
    )
}
