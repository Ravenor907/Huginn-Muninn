package io.github.ravenor907.muninn.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/** What the "Video from your phone" line (or the Saved media note) says under its name. Ultra privacy: videos still go (Tor or the same Wi-Fi). */
internal fun pickedVideoDetail(): String = GroupVideo.NOTE

/**
 * Cap v3 (protocol.md 29): the "Video from your phone" picker for a chat. Returns what the Send sheet's line runs; the
 * picked video then opens [VideoSendDialog] here. Ultra privacy on: it still opens (1.42: videos go over Tor or the same
 * Wi-Fi). The picked video and its send live in VideoSend.jobs, so the sheet comes back as it was after a layout change,
 * the lock or leaving the chat.
 */
@Composable
internal fun rememberVideoPicker(app: Muninn, target: VideoSend.Target): () -> Unit {
    val jobs by VideoSend.jobs.collectAsState()
    val job = jobs[target]
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) VideoSend.picked(target, uri) }
    if (job != null && !job.hidden) VideoSendDialog(app, target, job)
    return {
        when {
            // A send already on its way here: show it again rather than pick another.
            VideoSend.jobs.value[target]?.running == true -> VideoSend.hide(target, false)
            else -> try { Picking.launch(launcher, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                catch (_: Exception) { Ui.say("This phone has no video picker.", true) }
        }
    }
}

/**
 * A picked video, before it goes (1.42: never cut). What it is and who gets which copy; then, when it's over 5 minutes
 * and someone has no direct way, "Video too long" (Send as a file, or Cancel); when it's bigger than 720p and someone
 * gets the 720p copy, "Shrink to 720p to send?" (Shrink, or Cancel). The copies are made in Muninn's own scope
 * (VideoSend.start) with their progress here; it can be hidden meanwhile, and it closes once they're on their way. A
 * send that failed shows why, with Try again.
 */
@Composable
internal fun VideoSendDialog(app: Muninn, target: VideoSend.Target, job: VideoSend.Job) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = app.identity.publicKey
    val uri = job.uri
    val key = uri.toString()
    var info by remember(key) { mutableStateOf<GroupVideo.Info?>(null) }
    var failed by remember(key) { mutableStateOf("") }
    var plan by remember(key) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var planned by remember(key) { mutableStateOf(false) }
    val working = job.working
    val progress = job.progress
    LaunchedEffect(key) {
        val i = withContext(Dispatchers.IO) { VideoPick.probe(context, uri) }
        if (i == null) failed = "That video can't be read." else info = i
    }
    LaunchedEffect(info) {
        val i = info
        if (i != null) { plan = withContext(Dispatchers.IO) { VideoSend.plan(context, app.chat, target, me, i.ms) }; planned = true }
    }
    val nameOf: (String) -> String = { k -> app.chat.nameOf(k) }
    val i = info
    val close = { VideoSend.closed(target) }
    val idle = !job.running && job.failed.isEmpty() && failed.isEmpty() && i != null && planned
    val tooLong = idle && GroupVideo.tooLong(i!!.ms, plan)
    val shrink = idle && !tooLong && GroupVideo.needsShrink(i!!, plan)
    val sendNow = {
        val v = info
        if (v != null && !job.running) VideoSend.start(app, target, me, uri, v)
    }
    val asFile = {
        VideoSend.closed(target, keepClip = true)
        sendFileFrom(app, context, scope, if (target.group) "g:${target.g}" else target.peer, uri)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (job.running) VideoSend.hide(target, true) else close() },
        containerColor = Palette.panel,
        title = {
            Text(when {
                tooLong -> GroupVideo.TOO_LONG_TITLE
                shrink -> GroupVideo.SHRINK_TITLE
                else -> "Send a video"
            }, style = Type.big)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    working.isNotEmpty() -> {
                        Text(working, style = Type.normal)
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, color = Palette.accent, trackColor = Palette.track,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).semantics { contentDescription = "$working, ${(progress * 100).toInt()}%" })
                        Text("You can hide this and keep using Muninn. It says when the video is on its way.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                    }
                    job.failed.isNotEmpty() -> Text(job.failed, style = Type.normal.copy(color = Palette.danger))
                    failed.isNotEmpty() -> Text(failed, style = Type.normal.copy(color = Palette.danger))
                    i == null || !planned -> Text("Checking the video", style = Type.normal)
                    tooLong -> {
                        Text(GroupVideo.TOO_LONG_TEXT, style = Type.normal)
                        Text("This one is ${GroupVideo.clock(i.ms)}. As a file it goes whole, close by, direct or over Tor.",
                            style = Type.small.copy(color = Palette.textSoft), modifier = Modifier.padding(top = 8.dp))
                    }
                    else -> {
                        if (shrink) Text(GroupVideo.SHRINK_TEXT, style = Type.normal, modifier = Modifier.padding(bottom = 8.dp))
                        Text("${GroupVideo.clock(i.ms)} long", style = Type.normal)
                        Text(GroupVideo.summary(plan, nameOf), style = Type.small, modifier = Modifier.padding(top = 10.dp))
                        if (UltraPrivacy.on) Text("Ultra privacy: videos send over Tor or on the same Wi-Fi.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
                        Text("Sent without its location or camera details.", style = Type.small.copy(color = Palette.textSoft), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        },
        confirmButton = {
            when {
                job.running -> MButton("Hide") { VideoSend.hide(target, true) }
                job.failed.isNotEmpty() -> MButton("Try again", kind = Kind.Accent) { VideoSend.picked(target, uri) }
                tooLong -> MButton("Send as a file", kind = Kind.Accent) { asFile() }
                shrink -> MButton("Shrink", kind = Kind.Accent) { sendNow() }
                else -> {
                    val ready = idle && plan.values.any { it != GroupVideo.NONE }
                    MButton("Send", kind = Kind.Accent, enabled = ready) { sendNow() }
                }
            }
        },
        dismissButton = { if (!job.running) MButton(if (job.failed.isNotEmpty()) "Close" else "Cancel") { close() } },
    )
}
