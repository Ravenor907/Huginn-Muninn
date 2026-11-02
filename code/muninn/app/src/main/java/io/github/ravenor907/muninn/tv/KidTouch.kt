package io.github.ravenor907.muninn.tv

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.net.Voice
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A Kids' Device on a tablet or phone: the same screens as the TV's, for touch. Big friendly tiles for the people it can
// talk to; in a conversation, Call, quick messages, and (when a parent allows it) typing, photos and voice messages.
// Nothing to add people with, no Settings: a parent sets everything from their phone.

/** The kids' screens on a touch screen: the people, or one conversation ([person]). */
@Composable
internal fun KidTouch(app: Muninn, cfg: TvConfig, person: TvPerson?, quiet: QuietTime?, onParent: () -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        if (person == null) TouchHome(app, cfg, quiet, onParent) { TvNav.show(it) }
        else TouchTalk(app, cfg, person) { TvNav.home() }
    }
}

/** Home: the people it can talk to as big tiles (only parents during a quiet time), each with a badge for new messages. */
@Composable
private fun TouchHome(app: Muninn, cfg: TvConfig, quiet: QuietTime?, onParent: () -> Unit, onOpen: (String) -> Unit) {
    val convs by app.chat.conversations.collectAsState()
    val unread by app.chat.unread.collectAsState()
    val people = kidPeople(cfg, quiet)
    // Back here does nothing: a kid can't back out into anything else.
    BackHandler(enabled = true) {}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.weight(1f))
            ParentLock(tv = false, onClick = onParent)
        }
        if (quiet != null) QuietLine(quiet, tv = false, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (people.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                MIcon(TvWords.icon(TvWords.ASK_PARENT), Palette.goldBright, 64.dp)
                Text(TvWords.ASK_PARENT, style = tvTitle(26.sp), modifier = Modifier.padding(top = 14.dp))
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(people, key = { it.k }) { p -> TouchTile(p, unreadCount(convs[p.k].orEmpty(), p.k in unread)) { onOpen(p.k) } }
        }
    }
}

@Composable
private fun TouchTile(p: TvPerson, count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp).clip(shape).background(Palette.panel)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = if (count > 0) "${p.n}, $count new" else p.n },
        contentAlignment = Alignment.Center) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, 96.dp)
            Text(p.n, style = tvText(22.sp, weight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count > 0) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).sizeIn(minWidth = 34.dp).height(34.dp)
            .clip(RoundedCornerShape(17.dp)).background(Palette.gold).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(if (count > 9) "9+" else "$count", style = tvText(18.sp, Palette.onGold, FontWeight.Bold))
        }
    }
}

/**
 * One person: Call at the top (when a parent allows calls), the conversation, the quick messages as big tiles, and,
 * when the chatting level allows it, a photo button, a voice message button and a box to type in.
 */
@Composable
private fun TouchTalk(app: Muninn, cfg: TvConfig, p: TvPerson, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val convs by app.chat.conversations.collectAsState()
    val msgs = convs[p.k].orEmpty().filter { it.unsent.isEmpty() }
    val playing by Voice.playing.collectAsState()
    var viewing by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf<String?>(null) }
    var text by remember(p.k) { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf("") }
    // k11: typing, photos and voice messages only when a parent chose that chatting level.
    val typing = KidRules.canSend(cfg.chat, "text")
    val photos = KidRules.canSend(cfg.chat, "photo")
    val mic = remember { TvAudio.hasMic(context) }
    val voice = mic && KidRules.canSend(cfg.chat, "voice")

    fun micOn() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun finishRecording() {
        if (!recording || sending) return
        sending = true
        // Not tied to this screen: a clip on its way is still sent if the screen goes.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val r = TvRecorder.finish()
            sending = false
            recording = false
            if (r != null) { app.chat.sendVoice(p.k, r.first, r.second); Ui.say("Sent") } else Ui.say(TvWords.TOO_SHORT, true)
        }
    }
    fun cancelRecording() { if (sending) return; if (TvRecorder.recording.value) TvRecorder.cancel(); recording = false }
    fun record() {
        // The microphone is asked for when a parent pairs the device; without it, the child reads "Ask a parent".
        if (!micOn()) { Ui.say(TvWords.ASK_PARENT, true); return }
        Voice.stopPlaying()
        if (TvRecorder.start(context)) recording = true else Ui.say(TvWords.NO_MIC, true)
    }
    fun call() {
        when {
            !mic -> Ui.say(TvWords.NO_MIC, true)
            io.github.ravenor907.muninn.net.call.Opus.loadError != null -> Ui.say(TvWords.CANT_CALL, true)
            !io.github.ravenor907.muninn.net.call.CallManager.canCall(p.k) -> { app.chat.probeCaps(p.k); Ui.say(TvWords.CANT_CALL, true) }
            !micOn() -> Ui.say(TvWords.ASK_PARENT, true)
            else -> io.github.ravenor907.muninn.screens.CallActivity.call(context, p.k)
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val jpeg = withContext(Dispatchers.IO) { Photos.prepare(context, uri, false) }
            if (jpeg == null) Ui.say("That photo can't be sent", true)
            else { withContext(Dispatchers.IO) { app.chat.sendPhoto(p.k, jpeg) }; Ui.say("Sent") }
        }
    }

    DisposableEffect(p.k) {
        app.openChat = p.k
        TvRecorder.onLimit = { finishRecording() }
        onDispose {
            if (app.openChat == p.k) app.openChat = ""
            TvRecorder.onLimit = null
            if (TvRecorder.recording.value) TvRecorder.cancel()
            Voice.stopPlaying()
        }
    }
    LaunchedEffect(msgs.size) { app.chat.markRead(p.k) }
    // Whether their app takes calls isn't known until it says so: ask once (shared/docs/protocol.md 1.2).
    LaunchedEffect(p.k) { app.chat.probeCaps(p.k) }
    LaunchedEffect(playing) { while (playing.isNotEmpty()) { Voice.tick(); delay(200) } }
    LaunchedEffect(sent) { if (sent.isNotEmpty()) { delay(2000); sent = "" } }
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("back", "Back") { onBack() }
            io.github.ravenor907.muninn.ui.Avatar(p.k, p.n, 44.dp)
            Text(p.n, style = tvTitle(22.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
            if (cfg.calls) Row(Modifier.clip(RoundedCornerShape(24.dp)).background(Palette.gold).clickable(role = Role.Button) { call() }
                .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MIcon("call", Palette.onGold, 24.dp)
                Text("Call", style = tvText(20.sp, Palette.onGold, FontWeight.Bold), modifier = Modifier.padding(start = 8.dp))
            }
        }
        // The newest at the bottom.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(msgs.takeLast(60).asReversed()) { m -> TalkBubble(app, m, playing, onText = { reading = m.id }) { viewing = m.id } }
        }
        // Quick messages: one tap sends, and it says Sent for a moment.
        if (cfg.quick.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = if (typing) 168.dp else 300.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cfg.quick.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { q ->
                        TouchQuick(if (sent == q.id) "Sent" else q.text, sent == q.id, Modifier.weight(1f)) {
                            if (sent != q.id) { app.chat.sendPrivate(p.k, q.text); sent = q.id }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (typing || photos || voice) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (photos) RoundButton("image", "Send a photo") {
                try { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (_: Exception) { Ui.say(TvWords.ASK_PARENT, true) }
            }
            if (voice) { Spacer(Modifier.width(8.dp)); RoundButton("mic", "Voice message") { record() } }
            if (typing) {
                Field(text, { text = it }, "Type a message", Modifier.weight(1f).padding(horizontal = 8.dp), single = false)
                RoundButton("send", "Send", enabled = text.isNotBlank()) {
                    val t = TvRules.clean(text, 2000)
                    if (t.isNotEmpty()) { app.chat.sendPrivate(p.k, t); text = "" }
                }
            }
        }
    }

    viewing?.let { id ->
        val list = msgs.filter { it.photo.isNotEmpty() && it.voice == 0 }
        TvPhoto(app, list, id, onMove = { viewing = it }) { viewing = null }
    }
    reading?.let { id ->
        val m = msgs.firstOrNull { it.id == id }
        if (m == null) LaunchedEffect(id) { reading = null } else TvFullText(m.text) { reading = null }
    }
    if (recording) KidDialog(tv = false, onBack = { cancelRecording() }) {
        val ms by produceState(0L) { while (true) { value = TvRecorder.elapsed(); delay(250) } }
        MIcon("mic", Palette.danger, 72.dp)
        Text(if (sending) "Sending…" else Voice.length(ms.toInt()), style = tvTitle(36.sp), modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MButton("Send", Modifier.weight(1f), Kind.Accent, enabled = !sending, icon = "send") { finishRecording() }
            MButton("Delete", Modifier.weight(1f), enabled = !sending, icon = "delete") { cancelRecording() }
        }
    }
}

@Composable
private fun TouchQuick(text: String, done: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(modifier.heightIn(min = 64.dp).clip(shape).background(Palette.field).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (done) { MIcon("check", Palette.online, 22.dp); Spacer(Modifier.width(6.dp)) }
        Text(text, style = tvText(18.sp, weight = FontWeight.SemiBold), textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RoundButton(icon: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(if (enabled) Palette.gold else Palette.panelLow)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        MIcon(icon, if (enabled) Palette.onGold else Palette.textFaint, 26.dp)
    }
}
