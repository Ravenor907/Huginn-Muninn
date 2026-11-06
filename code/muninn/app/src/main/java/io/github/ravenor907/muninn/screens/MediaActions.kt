package io.github.ravenor907.muninn.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Folders
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.Received
import io.github.ravenor907.muninn.net.Voice
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.MenuRow
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What one photo, GIF, video or voice message offers in Media and files and in the viewer: Show in chat ([show]: the
 * chat closes what's over it, scrolls to the message and lights it up for a moment), Save, Forward and Delete (for me).
 * [folder]: the chat's folder in Downloads, where a video or voice message is saved.
 */
internal class MediaActions(val folder: String, val show: (Chat.Msg) -> Unit, val delete: (Chat.Msg) -> Unit)

/** "photo", "GIF", "video" or "voice message", for the words around it. */
internal fun mediaWord(m: Chat.Msg): String = when {
    m.video > 0 -> "video"
    m.voice > 0 -> "voice message"
    m.gif -> "GIF"
    else -> "photo"
}

/** Delete for me: it goes from this phone only. One message, so the usual confirm (like Delete this message). */
internal fun askDeleteMedia(m: Chat.Msg, delete: () -> Unit) {
    val w = mediaWord(m)
    Ui.sure(Ask("Delete this $w?", deletedHereOnly(), "Delete", danger = true,
        onConfirm = { delete(); Ui.say("Deleted.") }))
}

/** Save: a photo or GIF to Pictures › Muninn (as the viewer always has), a video or voice message to the chat's folder in Downloads. */
internal fun saveMedia(context: Context, m: Chat.Msg, bytes: ByteArray?, folder: String) {
    if (m.big) { saveBig(context, m, folder); return }
    if (bytes == null) { Ui.say("It isn't on this phone, so it can't be saved.", true); return }
    if (m.video == 0 && m.voice == 0) {
        val ok = savePhoto(context, bytes, m.gif)
        Ui.say(if (ok) "Saved in Pictures › Muninn." else "Couldn't save it.", !ok)
        return
    }
    val got = Received.save(context, "Muninn-${System.currentTimeMillis()}" + if (m.video > 0) ".mp4" else ".aac", bytes, folder)
    val where = if (folder.isEmpty()) Received.shownRoot() else Received.shown(folder)
    Ui.say(if (got != null) "${got.first} is in $where." else "Couldn't save it.", got == null)
}

/** A longer video (BigVideo) written whole into the chat's folder in Downloads, off the main thread. */
private fun saveBig(context: Context, m: Chat.Msg, folder: String) {
    val app = context.applicationContext as? Muninn ?: return
    Ui.say("Saving the video…")
    Thread {
        val got = Received.saveStream(context, "Muninn-${System.currentTimeMillis()}.mp4", folder) { out -> app.chat.exportBig(m.photo, out) }
        val where = if (folder.isEmpty()) Received.shownRoot() else Received.shown(folder)
        Ui.say(if (got != null) "${got.first} is in $where." else "Couldn't save it.", got == null)
    }.start()
}

/** Sends [bytes] of [m] to [to] (a person's key or "g:" and a group) as a new message, the way the chat itself sends one. */
internal fun forwardMedia(app: Muninn, m: Chat.Msg, bytes: ByteArray, to: String): String? {
    val g = if (to.startsWith("g:")) to.removePrefix("g:") else null
    return when {
        m.big -> "Longer videos can't be forwarded. Save it, then send it as a file."
        m.video > 0 -> if (g != null) app.chat.sendGroupVideo(g, Proto06.MAIN, bytes, m.video) else app.chat.sendVideo(to, bytes, m.video)
        m.voice > 0 -> {
            if (g != null) { if (!app.chat.sendGroupVoice(g, Proto06.MAIN, bytes, m.voice)) "That didn't send." else null }
            else { app.chat.sendVoice(to, bytes, m.voice); null }
        }
        m.gif -> if (g != null) app.chat.sendGroupGif(g, Proto06.MAIN, bytes) else app.chat.sendGif(to, bytes)
        else -> {
            if (g != null) { if (!app.chat.sendGroupPhoto(g, Proto06.MAIN, bytes)) "That didn't send." else null }
            else { app.chat.sendPhoto(to, bytes); null }
        }
    }
}

/** Forward: pick a person or a group. [title]: what's being sent ("Forward the photo to"). */
@Composable
internal fun ChatPicker(app: Muninn, title: String, onPick: (where: String, name: String) -> Unit, onCancel: () -> Unit) {
    val people by app.chat.contacts.all.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.panel,
        title = { Text(title, style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val contacts = people.filter { !it.groupOnly }.sortedBy { app.chat.nameOf(it.chatKey).lowercase() }
                if (contacts.isNotEmpty()) Text("PEOPLE", style = Type.small, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                contacts.forEach { c -> val n = app.chat.nameOf(c.chatKey); Line(n, icon = "user") { onPick(c.chatKey, n) } }
                val live = groups.values.filter { !it.readOnly }
                if (live.isNotEmpty()) Text("GROUPS", style = Type.small, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                live.forEach { gr -> Line(gr.state.name, icon = "people") { onPick(Folders.groupKey(gr.state.g), gr.state.name) } }
                if (contacts.isEmpty() && live.isEmpty()) Text("No one to send it to yet.", style = Type.normal)
            }
        },
        confirmButton = { MButton("Cancel") { onCancel() } },
    )
}

/** Forward [m]: the picker, then it goes as a new message. Nothing shows while [m] is null. */
@Composable
internal fun ForwardMedia(app: Muninn, m: Chat.Msg?, done: () -> Unit) {
    if (m == null) return
    val scope = rememberCoroutineScope()
    ChatPicker(app, "Forward the ${mediaWord(m)} to", onPick = { to, name ->
        done()
        scope.launch {
            val err = withContext(Dispatchers.IO) {
                val b = app.chat.photo(m.photo)
                if (m.big) "Longer videos can't be forwarded. Save it, then send it as a file."
                else if (b == null) "It isn't on this phone, so it can't be forwarded." else forwardMedia(app, m, b, to)
            }
            if (err != null) Ui.say(err, true) else Ui.say("Sent to $name.")
        }
    }, onCancel = done)
}

/**
 * Open, Show in chat, Save, Forward and Delete for one item: a long press in Media and files. [onOpen]: the viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaOptions(m: Chat.Msg, bytes: () -> ByteArray?, a: MediaActions, onOpen: () -> Unit, onForward: () -> Unit, done: () -> Unit) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = done, sheetState = sheet, containerColor = Palette.panel, shape = Corner, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).navigationBarsPadding()) {
            MenuRow("open", "Open") { done(); onOpen() }
            MenuRow("chat", "Show in chat") { done(); a.show(m) }
            MenuRow("download", "Save") { done(); saveMedia(context, m, bytes(), a.folder) }
            MenuRow("send", "Forward") { done(); onForward() }
            MenuRow("delete", "Delete", danger = true) { done(); askDeleteMedia(m) { a.delete(m) } }
        }
    }
}

/** The viewer's own row: Show in chat, Save, Forward and Delete, side by side (two lines at a large text size). */
@Composable
internal fun MediaActionRow(m: Chat.Msg, bytes: ByteArray?, a: MediaActions, onForward: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    ChoiceRow(4, Modifier.padding(12.dp).navigationBarsPadding(), minWidth = 84.dp) { i, mod ->
        when (i) {
            0 -> MButton("Show in chat", mod, icon = "chat") { close(); a.show(m) }
            1 -> MButton("Save", mod, icon = "download", enabled = bytes != null) { saveMedia(context, m, bytes, a.folder) }
            2 -> MButton("Forward", mod, icon = "send", enabled = bytes != null) { onForward() }
            else -> MButton("Delete", mod, Kind.Danger, icon = "delete") { askDeleteMedia(m) { close(); a.delete(m) } }
        }
    }
}

/**
 * Any photo, GIF, video or voice message full screen, from a chat or from Media and files. [a]: its actions (null: Back
 * and Save only, as before).
 */
@Composable
internal fun MediaViewer(app: Muninn, m: Chat.Msg, a: MediaActions?, close: () -> Unit) {
    BackHandler { close() }
    // Opened off the main thread (a video can be megabytes): a dark screen for that moment, then the media.
    val loaded by produceState<Pair<Boolean, ByteArray?>>(false to null, m.id) { value = true to withContext(Dispatchers.IO) { app.chat.photo(m.photo) } }
    if (!loaded.first) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("Opening…", style = Type.normal.copy(color = Color.White)) }
        return
    }
    val bytes = loaded.second
    var forwarding by remember(m.id) { mutableStateOf(false) }
    if (forwarding) ForwardMedia(app, m) { forwarding = false }
    val bar: (@Composable () -> Unit)? = if (a != null) ({ MediaActionRow(m, bytes, a, { forwarding = true }, close) }) else null
    when {
        // A longer video (BigVideo) plays from its sealed pieces, never whole in memory.
        m.big -> VideoPlayer(m.id, remember(m.id) { app.chat.bigSource(m.photo) }, close, bottom = bar)
        m.video > 0 -> VideoPlayer(bytes, close, bottom = bar)
        m.voice > 0 -> Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(12.dp)) { MButton("Back", icon = "back") { close() } }
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                VoiceNote(m, { bytes }, Voice.length(m.voice) + " · " + (if (m.mine) "You" else m.name), Type.normal)
            }
            bar?.invoke()
        }
        else -> PhotoViewer(app, m, close, bar)
    }
}

/**
 * One square tile in Media and files: a photo or GIF's picture, a video's first frame (or a play tile), a voice
 * message's tile, or, when it isn't on this phone, a tile saying so. A tap opens it, a long press its options.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaTile(m: Chat.Msg, bytes: (Chat.Msg) -> ByteArray?, onOpen: () -> Unit, onLong: () -> Unit) {
    val context = LocalContext.current
    // Loaded off the main thread: null while loading, then the picture (or nothing to show).
    val state by produceState<Pair<Boolean, ImageBitmap?>?>(null, m.id) {
        value = withContext(Dispatchers.IO) {
            val b = bytes(m)
            when {
                b == null -> false to null
                m.voice > 0 -> true to null
                m.video > 0 -> true to videoFrame(b)
                else -> true to io.github.ravenor907.muninn.ui.PhotoCache.get(context, "${m.id}:360:${b.size}") { decode(b, 360) }
            }
        }
    }
    val here = state?.first != false
    val bmp = state?.second
    val word = mediaWord(m)
    val said = when {
        !here -> "${word.replaceFirstChar { it.uppercase() }}, not on this phone"
        m.video > 0 -> "Video, ${Voice.length(m.video)}"
        m.voice > 0 -> "Voice message, ${Voice.length(m.voice)}, ${if (m.mine) "you" else m.name}"
        else -> word.replaceFirstChar { it.uppercase() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(2.dp).clip(Corner).background(if (m.video > 0 && here) Color.Black else Palette.groundDeep)
        .combinedClickable(role = Role.Image, onClickLabel = "Open", onLongClickLabel = "Options", onLongClick = onLong, onClick = onOpen)
        .semantics(mergeDescendants = true) { contentDescription = said }, contentAlignment = Alignment.Center) {
        when {
            !here -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MIcon("download", Palette.textMuted, 26.dp)
                Text("Not on this phone", style = Type.small, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            }
            m.voice > 0 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.gold), contentAlignment = Alignment.Center) {
                    MIcon("mic", Palette.onGold, 22.dp)
                }
                Text(Voice.length(m.voice), style = Type.small.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
                Text(if (m.mine) "You" else m.name, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp))
            }
            else -> {
                if (bmp != null) Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else if (state != null && m.video == 0) MIcon("image", Palette.textMuted, 26.dp)
                if (m.video > 0) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        MIcon("play", Color.White, 20.dp, filled = true)
                    }
                    Text(Voice.length(m.video), style = Type.small.copy(color = Color.White), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 4.dp))
                }
                // A GIF holds still here (its first frame); it moves once opened.
                if (m.gif) Text("GIF", style = Type.small.copy(color = Color.White), modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 4.dp))
            }
        }
    }
}
