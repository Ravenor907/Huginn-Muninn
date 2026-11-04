package io.github.ravenor907.muninn.screens

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.FileText
import io.github.ravenor907.muninn.net.More
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cap m2 on screen (shared/docs/protocol.md, section 12): polls, link previews, video messages and albums, and the
 * "+" menu in the message box that makes them.
 */

/**
 * How to send a GIF: from the keyboard's GIF key, or a GIF saved in Photos ([onPhotos] opens the photo picker).
 * From + › GIF and the emoji window's GIF button.
 */
internal fun gifHelp(onPhotos: () -> Unit) {
    // Ultra privacy: GIF search is off (a keyboard's GIF search reaches the internet outside Muninn); saved GIFs still go.
    if (!io.github.ravenor907.muninn.net.UltraPrivacy.gifSearchAllowed) {
        Ui.sure(io.github.ravenor907.muninn.ui.Ask("Send a GIF", "GIF search: ${io.github.ravenor907.muninn.net.UltraPrivacy.OFF_NOTE}. " +
            "Your keyboard's GIF search would show your address. A GIF saved in your photos still sends.", "Pick from photos", onConfirm = { onPhotos() }))
        return
    }
    Ui.sure(io.github.ravenor907.muninn.ui.Ask("Send a GIF", "Tap your keyboard's GIF key (on Gboard: the smiley key, then GIF), " +
        "or pick a GIF saved in your photos.", "Pick from photos", onConfirm = { onPhotos() }))
}

/** The "+" in the message box: photos, a GIF, the camera, a video message, a poll. */
@Composable
internal fun AttachDialog(onPhotos: () -> Unit, onCamera: () -> Unit, onVideo: () -> Unit, onPoll: () -> Unit, onFile: (() -> Unit)? = null,
                          /** Cap v3: a video picked from the phone (VideoSendDialog). */
                          onPickVideo: (() -> Unit)? = null, done: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Send", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Line("Photos", "Up to 10 at once", icon = "image") { done(); onPhotos() }
                Line("GIF", "From your keyboard or photos", icon = "image") { done(); gifHelp(onPhotos) }
                Line("Camera", "Take a photo now", icon = "image") { done(); onCamera() }
                // Ultra privacy: sending videos is off (receiving still works), said here rather than hidden.
                val videoOff = !io.github.ravenor907.muninn.net.UltraPrivacy.videoSendAllowed
                Line("Video message", if (videoOff) io.github.ravenor907.muninn.net.UltraPrivacy.OFF_NOTE else "Up to a minute", icon = "video",
                    enabled = !videoOff) { done(); onVideo() }
                // Cap v3: off (shown, with why) while Ultra privacy is on.
                if (onPickVideo != null) Line("Video from your phone", pickedVideoDetail(), icon = "video",
                    enabled = !io.github.ravenor907.muninn.net.UltraPrivacy.on) { done(); onPickVideo() }
                Line("Poll", "Ask a question", icon = "check") { done(); onPoll() }
                if (onFile != null) Line("File", "Any file", icon = "file") { done(); onFile() }
            }
        },
        confirmButton = { MButton("Cancel") { done() } },
    )
}

/** A new poll: a question, 2 to 10 answers, and whether several may be picked. [send] gets null for Cancel. */
@Composable
internal fun PollDialog(send: (More.Poll?) -> Unit) {
    var q by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multi by remember { mutableStateOf(false) }
    val filled = options.map { it.trim() }.filter { it.isNotEmpty() }
    val ok = q.isNotBlank() && filled.size >= More.OPTIONS_MIN && filled.map { it.lowercase() }.distinct().size == filled.size
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { send(null) },
        containerColor = Palette.panel,
        title = { Text("New poll", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(q, { q = it.replace("\n", " ").take(More.QUESTION_MAX) }, "Question", Modifier.padding(bottom = 8.dp))
                options.forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        Field(o, { options[i] = it.replace("\n", " ").take(More.OPTION_MAX) }, "Answer ${i + 1}", Modifier.weight(1f))
                        if (options.size > More.OPTIONS_MIN) io.github.ravenor907.muninn.ui.IconAction("close", "Remove answer ${i + 1}", Palette.textMuted, size = 18.dp) {
                            options.removeAt(i)
                        }
                    }
                }
                if (options.size < More.OPTIONS_MAX) MButton("Add an answer", Modifier.fillMaxWidth(), icon = "plus") { options.add("") }
                ToggleRow("Several answers", if (multi) "People can pick more than one" else "People pick one", multi) { multi = it }
                if (filled.map { it.lowercase() }.distinct().size != filled.size) Text("Two answers are the same.", style = Type.small.copy(color = Palette.danger))
            }
        },
        confirmButton = { MButton("Send", kind = Kind.Accent, enabled = ok) { send(More.Poll(q.trim(), filled, multi)) } },
        dismissButton = { MButton("Cancel") { send(null) } },
    )
}

/** A poll in a bubble: tap an answer to vote (again to take it back), with counts and bars. [names]: who voted, by key. */
@Composable
internal fun PollCard(poll: More.Poll, votes: Map<String, More.Vote>, me: String, names: (String) -> String, onVote: ((List<Int>) -> Unit)?) {
    val counts = More.counts(votes, poll.options.size)
    val total = votes.count { it.value.v.isNotEmpty() }
    val mine = votes[me]?.v.orEmpty()
    var who by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.widthIn(min = 220.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(poll.q, style = Type.big)
        poll.options.forEachIndexed { i, o ->
            val picked = i in mine
            val share = if (total == 0) 0f else counts[i].toFloat() / total
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(io.github.ravenor907.muninn.ui.Corner)
                .background(if (picked) Palette.goldTint else Palette.groundDeep)
                .clickable(enabled = onVote != null, role = if (poll.multi) Role.Checkbox else Role.RadioButton) {
                    val next = when {
                        poll.multi -> if (picked) mine - i else (mine + i).sorted()
                        picked -> emptyList()
                        else -> listOf(i)
                    }
                    onVote?.invoke(next)
                }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp).clip(if (poll.multi) RoundedCornerShape(4.dp) else CircleShape)
                        .background(if (picked) Palette.gold else Color.Transparent).border(1.5.dp, if (picked) Palette.gold else Palette.textMuted,
                            if (poll.multi) RoundedCornerShape(4.dp) else CircleShape), contentAlignment = Alignment.Center) {
                        if (picked) MIcon("check", Palette.onGold, 12.dp, weight = 2.4f)
                    }
                    Text(o, style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f).padding(start = 10.dp))
                    Text("${counts[i]}", style = Type.small, modifier = Modifier.clickable(enabled = counts[i] > 0) { who = i }.padding(start = 8.dp))
                }
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.line)) {
                    Box(Modifier.fillMaxWidth(share).height(4.dp).background(Palette.gold))
                }
            }
        }
        Text("$total ${if (total == 1) "vote" else "votes"}" + (if (poll.multi) " · pick any" else "") + (if (onVote != null) " · tap to vote" else ""),
            style = Type.small, modifier = Modifier.padding(top = 6.dp))
    }
    who?.let { i ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { who = null },
            containerColor = Palette.panel,
            title = { Text(poll.options[i], style = Type.big) },
            text = { Text(votes.filter { i in it.value.v }.keys.joinToString("\n") { if (it == me) "You" else names(it) }, style = Type.normal, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { MButton("Done") { who = null } },
        )
    }
}

/**
 * "Open example.com?" before the browser opens a link. Round 19: with Ultra privacy on it says the browser isn't behind
 * Tor, and offers Copy link instead.
 */
internal fun askOpenLink(context: android.content.Context, url: String) {
    val ultra = io.github.ravenor907.muninn.net.UltraPrivacy.on
    val open = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { Ui.say("No app here opens web links.", true) }
    }
    Ui.sure(if (!ultra) Ask("Open ${More.host(url)}?", url, "Open", onConfirm = open)
        else Ask("Open ${More.host(url)}?", url + "\n\nIt opens in your browser, outside Tor. The site sees your internet address.", "Open",
            extra = "Copy link", onExtra = { io.github.ravenor907.muninn.ui.Clip.copy(context, "Link", url); Ui.say("Link copied.") }, onConfirm = open))
}

/** What a link goes to, made on the sender's phone. Nothing is fetched here; tapping asks before the browser opens it. */
@Composable
internal fun PreviewCard(lp: More.Preview) {
    val context = LocalContext.current
    val thumb = remember(lp.url) { lp.thumb?.let { decode(it, 240) } }
    val host = More.host(lp.url)
    Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp).widthIn(max = 280.dp).clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.groundDeep)
        .clickable(role = Role.Button) {
            askOpenLink(context, lp.url)
        }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        thumb?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(io.github.ravenor907.muninn.ui.Corner)) }
        Column(Modifier.padding(start = if (thumb != null) 10.dp else 2.dp)) {
            Text(lp.title, style = Type.normal.copy(color = Palette.text, fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (lp.description.isNotEmpty()) Text(lp.description, style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(host, style = Type.small.copy(color = Palette.accent), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Plays from memory: the video stays encrypted on disk and is never written out in the clear. */
internal class BytesSource(private val b: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= b.size) return -1
        val n = minOf(size.toLong(), b.size - position).toInt()
        System.arraycopy(b, position.toInt(), buffer, offset, n)
        return n
    }
    override fun getSize() = b.size.toLong()
    override fun close() {}
}

/** The first frame of a video message, for its bubble. */
internal fun videoFrame(bytes: ByteArray?): ImageBitmap? {
    if (bytes == null) return null
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(BytesSource(bytes))
        r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
            val long = maxOf(b.width, b.height)
            (if (long > 480) Bitmap.createScaledBitmap(b, b.width * 480 / long, b.height * 480 / long, true) else b).asImageBitmap()
        }
    } catch (e: Exception) { null } finally { try { r.release() } catch (_: Exception) {} }
}

/** A video message in a bubble: its first frame with a play mark and its length. Tap plays it. */
@Composable
internal fun VideoThumb(m: Chat.Msg, bytes: () -> ByteArray?) {
    val frame by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) { videoFrame(bytes()) } }
    // Round 17: shaped like the clip (a sideways one isn't cropped to its middle).
    val box = io.github.ravenor907.muninn.net.Videos.bubble(frame?.width ?: 0, frame?.height ?: 0)
    Box(Modifier.size(box.first.dp, box.second.dp).clip(io.github.ravenor907.muninn.ui.Corner).background(Color.Black), contentAlignment = Alignment.Center) {
        frame?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) { MIcon("play", Color.White, 24.dp, filled = true) }
        Text(io.github.ravenor907.muninn.net.Voice.length(m.video), style = Type.small.copy(color = Color.White),
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).semantics { contentDescription = "Video message, ${io.github.ravenor907.muninn.net.Voice.length(m.video)}" })
    }
}

/** Full screen playback of a video message ([bytes] the decrypted MP4), from memory. [bottom]: a row under Play (the viewer's actions). */
@Composable
internal fun VideoPlayer(bytes: ByteArray?, close: () -> Unit, bottom: (@Composable () -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    BackHandler { close() }
    var playing by remember { mutableStateOf(true) }
    val player = remember { MediaPlayer() }
    // The video's own shape (width / height, already turned as recorded): a sideways clip is never squeezed into a tall box.
    var ratio by remember(bytes) { mutableStateOf(9f / 16f) }
    // Round 19: a video this device can't decode (HEVC 10-bit, AV1 on an older tablet or TV) says so instead of staying black.
    var failed by remember(bytes) { mutableStateOf(false) }
    // Round 17: where it was when its surface went (screen off, a minimised window), so it goes on from there.
    val at = remember { intArrayOf(0) }
    DisposableEffect(Unit) { onDispose { try { player.release() } catch (_: Exception) {} } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MButton("Back", icon = "back") { close() }
            Spacer(Modifier.weight(1f))
            actions()
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (bytes == null || failed) Text(if (failed) "This video can't be played on this device." else "This video can't be played.",
                style = Type.normal.copy(color = Color.White), modifier = Modifier.padding(24.dp))
            else AndroidView(factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) {
                            try {
                                player.reset()
                                player.setDataSource(BytesSource(bytes))
                                player.setDisplay(h)
                                player.setOnVideoSizeChangedListener { _, w, hh -> if (w > 0 && hh > 0) ratio = w.toFloat() / hh }
                                player.setOnPreparedListener { mp ->
                                    if (at[0] > 0 && at[0] < mp.duration - 200) mp.seekTo(at[0])
                                    // Paused or finished before: it stays so, and the button still says Play.
                                    if (playing) mp.start()
                                }
                                player.setOnCompletionListener { playing = false }
                                player.setOnErrorListener { _, _, _ -> failed = true; playing = false; true }
                                player.prepareAsync()
                            } catch (e: Exception) { Ui.say("This video can't be played.", true) }
                        }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) {
                            try { at[0] = player.currentPosition } catch (_: Exception) {}
                            try { player.stop() } catch (_: Exception) {}
                        }
                    })
                }
            }, modifier = Modifier.aspectRatio(ratio))
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
            MButton(if (playing) "Pause" else "Play", icon = if (playing) "pause" else "play") {
                try { if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true } } catch (_: Exception) {}
            }
        }
        bottom?.invoke()
    }
}

/** An album: up to 10 photos from one person, side by side. Tap one to see it. */
@Composable
internal fun AlbumGrid(photos: List<Chat.Msg>, bytes: (Chat.Msg) -> ByteArray?, onTap: (Chat.Msg) -> Unit) {
    val rows = photos.chunked(if (photos.size <= 4) 2 else 3)
    val albumCtx = LocalContext.current
    Column(Modifier.widthIn(max = 260.dp).padding(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { m ->
                    val bmp by produceState<ImageBitmap?>(null, m.id) { value = withContext(Dispatchers.IO) {
                        bytes(m)?.let { b -> io.github.ravenor907.muninn.ui.PhotoCache.get(albumCtx, "${m.id}:360:${b.size}") { decode(b, 360) } } } }
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.groundDeep)
                        .clickable(role = Role.Image) { onTap(m) }, contentAlignment = Alignment.Center) {
                        bmp?.let { Image(it, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                repeat((if (photos.size <= 4) 2 else 3) - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("${photos.size} photos", style = Type.small, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
    }
}

/**
 * Which messages show: an album's photos from one person collapse into the first of them (which gets the rest in
 * [albums]); everything else shows on its own.
 */
internal fun albumsOf(msgs: List<Chat.Msg>): Pair<Set<String>, Map<String, List<Chat.Msg>>> {
    val hidden = HashSet<String>()
    val albums = HashMap<String, List<Chat.Msg>>()
    msgs.filter { it.album.isNotEmpty() && it.photo.isNotEmpty() && it.unsent.isEmpty() }.groupBy { it.peerKey + "|" + it.mine + "|" + it.album }
        .values.filter { it.size > 1 }.forEach { l ->
            val sorted = l.sortedBy { it.albumAt }
            val first = l.minBy { it.time }
            albums[first.id] = sorted
            l.forEach { if (it.id != first.id) hidden += it.id }
        }
    return hidden to albums
}

/**
 * Media and files, from a chat's menu: two tabs. Media has videos, photos, links and voice shared in the chat, newest
 * first; Files ([files], when given) has the chat's files. [startFiles]: opens on Files. Every photo, video and voice
 * message is a square tile: a tap opens it ([onOpen], the viewer), a long press offers [actions] (Show in chat, Save,
 * Forward, Delete).
 */
@Composable
internal fun SharedMedia(title: String, msgs: List<Chat.Msg>, bytes: (Chat.Msg) -> ByteArray?, onOpen: (Chat.Msg) -> Unit,
                         files: (@Composable (Modifier) -> Unit)? = null, startFiles: Boolean = false, actions: MediaActions? = null,
                         close: () -> Unit) {
    BackHandler { close() }
    var part by remember { mutableStateOf(if (startFiles && files != null) "files" else "media") }
    var tab by remember { mutableStateOf("media") }
    // A file that came as a message is in Files, not here.
    val media = remember(msgs) { msgs.filter { it.photo.isNotEmpty() && it.unsent.isEmpty() && it.voice == 0 && it.file.isEmpty() }.reversed() }
    val links = remember(msgs) { msgs.filter { it.unsent.isEmpty() && (it.preview != null || More.firstLink(it.text) != null) }.reversed() }
    val voices = remember(msgs) { msgs.filter { it.voice > 0 && it.unsent.isEmpty() }.reversed() }
    // Loads in pages: the newest first, and the next page only once the list is scrolled near its end.
    var shown by remember(tab) { mutableStateOf(SHARED_PAGE) }
    var options by remember { mutableStateOf<Chat.Msg?>(null) }
    var forwarding by remember { mutableStateOf<Chat.Msg?>(null) }
    val context = LocalContext.current
    val app = context.applicationContext as io.github.ravenor907.muninn.Muninn
    options?.let { m ->
        if (actions != null) MediaOptions(m, { bytes(m) }, actions, onOpen = { onOpen(m) }, onForward = { forwarding = m }) { options = null }
    }
    ForwardMedia(app, forwarding) { forwarding = null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MButton("Back", icon = "back") { close() }
            Text(title, style = Type.big, modifier = Modifier.padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (files != null) Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Media", Modifier.weight(1f), kind = if (part == "media") Kind.Accent else Kind.Normal, icon = "image") { part = "media" }
            MButton("Files", Modifier.weight(1f), kind = if (part == "files") Kind.Accent else Kind.Normal, icon = "file") { part = "files" }
        }
        if (part == "files" && files != null) files(Modifier.weight(1f).fillMaxWidth())
        else {
            // Each choice gets its share of the width. (In a sideways-scrolling row a button had no width to fit its
            // words into, so its text broke a letter a line and the buttons stood as tall empty bars.)
            io.github.ravenor907.muninn.ui.ChoiceRow(3, Modifier.padding(horizontal = 12.dp), minWidth = 100.dp, gap = 8.dp) { i, mod ->
                when (i) {
                    0 -> MButton("Photos and videos (${media.size})", mod, kind = if (tab == "media") Kind.Accent else Kind.Normal) { tab = "media" }
                    1 -> MButton("Links (${links.size})", mod, kind = if (tab == "links") Kind.Accent else Kind.Normal) { tab = "links" }
                    else -> MButton("Voice (${voices.size})", mod, kind = if (tab == "voice") Kind.Accent else Kind.Normal) { tab = "voice" }
                }
            }
            val total = when (tab) { "media" -> media.size; "links" -> links.size; else -> voices.size }
            if (tab != "links") {
                val tiles = if (tab == "media") media else voices
                val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                val nearEnd by remember { androidx.compose.runtime.derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= shown - 9 } }
                LaunchedEffect(nearEnd, total) { if (nearEnd && shown < total) shown += SHARED_PAGE }
                if (tiles.isEmpty()) Text(if (tab == "media") "No photos or videos yet." else "No voice messages yet.", style = Type.normal, modifier = Modifier.padding(12.dp))
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Adaptive(110.dp), Modifier.weight(1f).padding(10.dp), state = grid) {
                    items(minOf(shown, tiles.size), key = { tiles[it].id }) { i ->
                        val m = tiles[i]
                        MediaTile(m, bytes, onOpen = { onOpen(m) }, onLong = { if (actions != null) { options = m } else onOpen(m) })
                    }
                }
            } else {
                val list = androidx.compose.foundation.lazy.rememberLazyListState()
                val nearEnd by remember { androidx.compose.runtime.derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= shown - 5 } }
                LaunchedEffect(nearEnd, total) { if (nearEnd && shown < total) shown += SHARED_PAGE }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).padding(12.dp), state = list) {
                    if (links.isEmpty()) item { Text("No links yet.", style = Type.normal) }
                    items(minOf(shown, links.size), key = { links[it].id }) { i ->
                        val m = links[i]
                        val lp = m.preview
                        if (lp != null) PreviewCard(lp)
                        else More.firstLink(m.text)?.let { u -> Line(More.host(u), u, icon = "link") {
                            askOpenLink(context, u)
                        } }
                    }
                }
            }
        }
    }
}

/** How many the Shared page shows at a time. */
private const val SHARED_PAGE = 60

/**
 * Tap phones together to add each other. Both phones open this screen and touch backs: each hands over a code that
 * works once and, in turns, reads the other's. Whoever reads a code adds that person and says hi, which adds them back.
 */
@Composable
internal fun TapScreen(app: io.github.ravenor907.muninn.Muninn, back: () -> Unit) {
    BackHandler { back() }
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val nfc = remember { android.nfc.NfcAdapter.getDefaultAdapter(context) }
    var status by remember { mutableStateOf(if (nfc == null) "No NFC. Use My code." else if (!nfc.isEnabled) "Turn on NFC, then come back." else "Hold the phones back to back.") }
    var added by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val link = app.chat.inviteLink(1)
        io.github.ravenor907.muninn.net.Tap.offer(link)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var reading = false
        val got = android.nfc.NfcAdapter.ReaderCallback { tag ->
            val iso = android.nfc.tech.IsoDep.get(tag) ?: return@ReaderCallback
            val code = io.github.ravenor907.muninn.net.Tap.read(iso) ?: return@ReaderCallback
            val c = io.github.ravenor907.muninn.net.Contacts.parse(code) ?: return@ReaderCallback
            main.post {
                if (c.chatKey == app.identity.publicKey || added == c.chatKey) return@post
                app.chat.addContact(c)
                if (io.github.ravenor907.muninn.net.Contacts.once(code)) app.chat.sendPrivate(c.chatKey, "Hi, it's ${app.identity.username}. We tapped phones.")
                added = c.chatKey
                status = "${c.name} added."
            }
        }
        // A phone reading can't be read at the same moment: take turns, a little off beat so two phones meet.
        val flip = object : Runnable {
            override fun run() {
                if (activity != null && nfc != null && nfc.isEnabled) try {
                    if (reading) nfc.disableReaderMode(activity)
                    else nfc.enableReaderMode(activity, got, android.nfc.NfcAdapter.FLAG_READER_NFC_A or android.nfc.NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
                        android.nfc.NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
                    reading = !reading
                } catch (_: Exception) {}
                main.postDelayed(this, 900L + (Math.random() * 700).toLong())
            }
        }
        main.post(flip)
        onDispose {
            main.removeCallbacks(flip)
            io.github.ravenor907.muninn.net.Tap.offer(null)
            try { if (activity != null) nfc?.disableReaderMode(activity) } catch (_: Exception) {}
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { MButton("Back", icon = "back") { back() } }
        Text("Tap phones to add each other", style = Type.title, modifier = Modifier.padding(top = 16.dp))
        Text("Both open this, then touch backs.",
            style = Type.normal, modifier = Modifier.padding(top = 8.dp))
        Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) { MIcon(if (added != null) "check" else "phone", Palette.goldBright, 48.dp) }
        }
        Text(status, style = Type.big, modifier = Modifier.fillMaxWidth())
        added?.let { k -> MButton("Say hi", Modifier.fillMaxWidth().padding(top = 16.dp), Kind.Accent, icon = "chat") { io.github.ravenor907.muninn.screens.ChatNav.open = k } }
    }
}


/**
 * A file that went as a message (the internet route): type icon, name, size and how it's going. Mine: "Sending 42%"
 * with a bar, then "Sent" and "Sent · Saved in their Downloads". Theirs: saved to Downloads when it came, with Open and
 * Folder (Save if it couldn't be saved then). Nothing received ever opens by itself.
 */
@Composable
internal fun FileCard(m: Chat.Msg, bytes: () -> ByteArray?, folder: String = "") {
    val context = LocalContext.current
    val app = context.applicationContext as io.github.ravenor907.muninn.Muninn
    val uploads by app.chat.uploads.collectAsState()
    val size by produceState(-1L, m.photo) { value = withContext(Dispatchers.IO) { bytes()?.size?.toLong() ?: -1L } }
    var savedUri by remember(m.id) { mutableStateOf(m.saved) }
    val progress = uploads[m.id]
    val sending = m.mine && !m.failed && (m.state == "sending" || m.state.isEmpty() && progress != null)
    // Where Save puts it: the chat's folder in Settings › Save files to (Download/Muninn/<chat> by default).
    val where = { if (folder.isEmpty()) io.github.ravenor907.muninn.net.Received.shownRoot() else io.github.ravenor907.muninn.net.Received.shown(folder) }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(min = 200.dp, max = 280.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.coolTint), contentAlignment = Alignment.Center) {
                MIcon(FileText.icon(m.file), if (m.failed) Palette.danger else Palette.accent, 22.dp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(m.file, style = Type.normal.copy(color = Palette.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                val line = if (m.mine) FileText.relayLine(m.state, m.failed, progress) else if (savedUri.isNotEmpty()) "Saved to ${where()}" else "Not saved yet"
                Text((if (size >= 0) FileText.size(size) + " · " else "") + line,
                    style = Type.small.copy(color = if (m.failed) Palette.danger else Palette.textMuted), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (sending) io.github.ravenor907.muninn.ui.Bar(progress ?: 0f, Modifier.padding(top = 8.dp), Palette.gold)
        if (!m.mine) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val kids = io.github.ravenor907.muninn.tv.KidMode.saved(context)
            if (savedUri.isEmpty()) MButton("Save", kind = Kind.Accent) {
                val b = bytes()
                val got = b?.let { io.github.ravenor907.muninn.net.Received.save(context, m.file, it, folder) }
                if (got != null) savedUri = got.second
                Ui.say(if (got != null) "${got.first} is in ${where()}." else "That file couldn't be saved.", got == null)
            } else {
                // An app installer opens only when the person asks, and on a Kids' TV not at all (a parent installs).
                if (!(kids && FileText.isApp(m.file))) MButton("Open", kind = Kind.Accent) {
                    if (!io.github.ravenor907.muninn.net.Received.open(context, savedUri, m.file)) Ui.say("Nothing on this device opens this kind of file.", true)
                }
                MButton("Folder", icon = "folder") {
                    if (!io.github.ravenor907.muninn.net.Received.showFolder(context)) Ui.say("It's in ${where()}. Open it from your Files app.")
                }
            }
        }
    }
}

/**
 * Files on their way in this chat or group ([peer]: their key, or "g:<group>"), and files of theirs coming over the
 * internet route: the same cards as Transfers (TransferCard). A file of mine going as a message shows on the message.
 */
@Composable
internal fun DirectFiles(peer: String, who: String = "them", resend: (String, java.io.File, String) -> Unit = { _, _, _ -> }) {
    val all by io.github.ravenor907.muninn.net.FileLink.all.collectAsState()
    val mine = all.values.filter { it.where == peer && !it.relay }.sortedBy { it.time }
    if (mine.isEmpty()) return
    val app = LocalContext.current.applicationContext as? io.github.ravenor907.muninn.Muninn
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        mine.forEach { f ->
            TransferCard(f, showChat = false) { p, file, n, g ->
                if (g.isEmpty() || app == null) resend(p, file, n) else resendFile(app, scope)(p, file, n, g)
            }
        }
    }
}
