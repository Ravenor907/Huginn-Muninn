package io.github.ravenor907.muninn.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.net.VideoSend
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Picking
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saved media (owner, 2026-11-04): the Send sheet's one picker for photos, videos and GIFs together, up to 10.
 *
 * Android's own photo picker does the browsing (PickMultipleVisualMedia, images and videos). It needs no access to
 * the whole gallery, shows only what the person picks to Muninn, and works the same on phones, tablets and TVs. Then
 * this screen shows what was picked as the mockup has it: the All, Photos, Videos and GIFs filters, the pick-order
 * numbers, each video's length and a GIF badge, "Add more", and the bar with "3 selected", "2 photos, 1 video" and Send.
 * A tap takes an item out or puts it back.
 *
 * On Send: photos go as they always have (one photo, or an album, shrunk and scrubbed), GIFs animated (one over the
 * limits goes to the chat's GIF panel, [onGif], for a still or a file), and each video through the video path
 * (VideoSend.picked, one after another for the same chat).
 */
object SavedMedia {
    /** One picked item: what it is (Photos.Pick.PHOTO, VIDEO or GIF) and, for a video, its length. */
    data class Item(val uri: Uri, val kind: String, val ms: Long = 0)

    /** What a chat has picked: [items] as they came, [order] the chosen ones (indices into [items]) in pick order. */
    data class Picked(val items: List<Item>, val order: List<Int>)

    private val _open = MutableStateFlow<Map<VideoSend.Target, Picked>>(emptyMap())
    /** Each chat's picks waiting on this screen; kept while the window is rebuilt or the lock comes up (memory only). */
    val open: StateFlow<Map<VideoSend.Target, Picked>> = _open.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private fun change(f: (Map<VideoSend.Target, Picked>) -> Map<VideoSend.Target, Picked>) = synchronized(lock) { _open.value = f(_open.value) }

    /** New picks for [t], added after any already there; the first ones that fit in 10 are chosen. */
    fun add(t: VideoSend.Target, more: List<Item>) = change { m ->
        val old = m[t]
        val items = (old?.items.orEmpty() + more).distinctBy { it.uri }
        var order = old?.order.orEmpty()
        items.indices.drop(old?.items?.size ?: 0).forEach { order = Photos.Pick.toggle(order, it) }
        if (items.isEmpty()) m else m + (t to Picked(items, order))
    }

    /** Finds out what each picked item is (off the main thread), then shows them for [t]. */
    fun load(context: Context, t: VideoSend.Target, uris: List<Uri>) {
        if (uris.isNotEmpty()) scope.launch {
            add(t, uris.map { u ->
                val kind = Photos.kindOf(context, u)
                Item(u, kind, if (kind == Photos.Pick.VIDEO) Photos.videoMs(context, u) else 0L)
            })
        }
    }

    fun set(t: VideoSend.Target, p: Picked) = change { m -> m + (t to p) }
    fun close(t: VideoSend.Target) = change { m -> m - t }

    // ------------------------------------------------------------------ videos, one at a time per chat

    /**
     * Each video goes through the video path: VideoSend.pickedMany opens one send sheet at a time for the chat (its
     * rememberVideoPicker shows it), and the next once the last is on its way or put aside.
     */
    fun queueVideos(t: VideoSend.Target, uris: List<Uri>) = VideoSend.pickedMany(t, uris)

    /** Sends what was chosen (see the class note). Runs in its own scope, so leaving the chat doesn't stop it. */
    fun send(app: Muninn, t: VideoSend.Target, chosen: List<Item>, onGif: (Uri) -> Unit) {
        val context: Context = app
        scope.launch {
            val photos = chosen.filter { it.kind == Photos.Pick.PHOTO }
            if (photos.isNotEmpty()) {
                // One photo keeps the person's High quality choice, as when it was picked on its own; an album goes normal.
                val hd = photos.size == 1 && context.getSharedPreferences("muninn", Context.MODE_PRIVATE).getBoolean("photoHd", false)
                val jpegs = photos.mapNotNull { Photos.prepare(context, it.uri, hd) }
                when {
                    jpegs.isEmpty() -> Ui.say(if (photos.size == 1) "That photo couldn't be opened." else "Those photos couldn't be opened.", true)
                    jpegs.size == 1 -> if (t.group) app.chat.sendGroupPhoto(t.g, t.ch, jpegs[0]) else app.chat.sendPhoto(t.peer, jpegs[0])
                    else -> if (t.group) app.chat.sendGroupPhotos(t.g, t.ch, jpegs) else app.chat.sendPhotos(t.peer, jpegs)
                }
            }
            chosen.filter { it.kind == Photos.Pick.GIF }.forEach { g ->
                val r = Photos.readGif(context, g.uri)
                when {
                    r == null -> Ui.say("That GIF couldn't be opened.", true)
                    r.bytes != null -> (if (t.group) app.chat.sendGroupGif(t.g, t.ch, r.bytes) else app.chat.sendGif(t.peer, r.bytes))?.let { Ui.say(it, true) }
                    else -> withContext(Dispatchers.Main) { onGif(g.uri) }
                }
            }
            val videos = chosen.filter { it.kind == Photos.Pick.VIDEO }.map { it.uri }
            if (videos.isNotEmpty()) withContext(Dispatchers.Main) { queueVideos(t, videos) }
        }
    }
}

/**
 * Saved media for one chat ([target]: a person or a group's channel). Returns what the Send sheet's row (and the
 * camera's Saved media button) runs: Android's photo picker, for photos, videos and GIFs. What's picked then shows here,
 * over the chat, until Send or Close. [onGif]: a GIF over the limits, for the chat's GIF panel (a still or a file).
 */
@Composable
internal fun rememberSavedMedia(app: Muninn, target: VideoSend.Target, onGif: (Uri) -> Unit = {}): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Photos.Pick.MAX)) { uris ->
        SavedMedia.load(context.applicationContext, target, uris)
    }
    val open by SavedMedia.open.collectAsState()
    val picked = open[target]
    val launch: () -> Unit = {
        try { Picking.launch(launcher, PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
        catch (_: Exception) { Ui.say("This phone has no photo picker.", true) }
    }
    if (picked != null) Dialog(onDismissRequest = { SavedMedia.close(target) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        SavedMediaScreen(picked, onChange = { SavedMedia.set(target, it) }, onAddMore = launch, onClose = { SavedMedia.close(target) }) { chosen ->
            SavedMedia.close(target)
            SavedMedia.send(app, target, chosen, onGif)
        }
    }
    return launch
}

/** The picked items as the mockup's Saved media screen. */
@Composable
private fun SavedMediaScreen(p: SavedMedia.Picked, onChange: (SavedMedia.Picked) -> Unit, onAddMore: () -> Unit, onClose: () -> Unit,
                             onSend: (List<SavedMedia.Item>) -> Unit) {
    BackHandler { onClose() }
    var filter by remember { mutableStateOf("all") }
    val kinds = p.items.map { it.kind }
    val shown = Photos.Pick.shown(kinds, filter)
    val chosen = p.order.map { p.items[it] }
    Column(Modifier.fillMaxSize().background(Palette.ground)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction("close", "Close", Palette.gold) { onClose() }
            Text("Saved media", style = Type.big.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f).padding(start = 12.dp))
            MButton("Add more", icon = "plus") { onAddMore() }
        }
        ChoiceRow(Photos.Pick.FILTERS.size, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp), minWidth = 72.dp, gap = 8.dp) { i, mod ->
            val (key, label) = Photos.Pick.FILTERS[i]
            MButton(label, mod, kind = if (filter == key) Kind.Accent else Kind.Normal) { filter = key }
        }
        LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f).fillMaxWidth().padding(horizontal = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(shown.size, key = { p.items[shown[it]].uri.toString() }) { n ->
                val i = shown[n]
                val at = p.order.indexOf(i)
                PickTile(p.items[i], if (at >= 0) at + 1 else 0) {
                    val next = Photos.Pick.toggle(p.order, i)
                    if (next == p.order) Ui.say("Up to ${Photos.Pick.MAX} at once.")
                    onChange(p.copy(order = next))
                }
            }
        }
        if (shown.isEmpty()) Text(when (filter) { Photos.Pick.PHOTO -> "No photos picked."; Photos.Pick.VIDEO -> "No videos picked."; else -> "No GIFs picked." },
            style = Type.normal, modifier = Modifier.padding(16.dp))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(Palette.raised)
            .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp)) {
            Text(Photos.Pick.NOTE, style = Type.small.copy(color = Palette.gold))
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Photos.Pick.selected(chosen.size), style = Type.big)
                    val s = Photos.Pick.summary(chosen.count { it.kind == Photos.Pick.PHOTO }, chosen.count { it.kind == Photos.Pick.VIDEO },
                        chosen.count { it.kind == Photos.Pick.GIF })
                    if (s.isNotEmpty()) Text(s, style = Type.small)
                }
                MButton("Send", kind = Kind.Accent, enabled = chosen.isNotEmpty(), icon = "send") { onSend(chosen) }
            }
        }
    }
}

/** One tile: the picture, its pick number (or an empty ring), a video's length or a GIF badge. A tap picks or unpicks. */
@Composable
private fun PickTile(item: SavedMedia.Item, number: Int, onTap: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { Photos.thumb(context, item.uri, item.kind)?.asImageBitmap() }
    }
    val what = when (item.kind) { Photos.Pick.VIDEO -> "Video, ${Photos.Pick.length(item.ms)}"; Photos.Pick.GIF -> "GIF"; else -> "Photo" }
    Box(Modifier.aspectRatio(1f).background(Palette.groundDeep)
        .then(if (number > 0) Modifier.border(3.dp, Palette.gold) else Modifier)
        .clickable(role = Role.Checkbox) { onTap() }
        .semantics { contentDescription = if (number > 0) "$what, picked $number" else "$what, not picked" }) {
        bmp?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (number > 0) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape).background(Palette.gold),
            contentAlignment = Alignment.Center) {
            Text("$number", style = Type.small.copy(color = Palette.onGold, fontWeight = FontWeight.Bold))
        } else Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape))
        val badge = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xDD0D1411))
            .padding(horizontal = 7.dp, vertical = 2.dp)
        when (item.kind) {
            Photos.Pick.VIDEO -> Row(badge, verticalAlignment = Alignment.CenterVertically) {
                MIcon("play", Color.White, 10.dp, filled = true)
                Spacer(Modifier.width(4.dp))
                Text(Photos.Pick.length(item.ms), style = Type.small.copy(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            }
            Photos.Pick.GIF -> Text("GIF", style = Type.small.copy(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = badge)
            else -> {}
        }
    }
}
