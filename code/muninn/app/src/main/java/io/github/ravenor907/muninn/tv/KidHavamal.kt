package io.github.ravenor907.muninn.tv

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// Daily Hávamál (cap k12, shared/docs/protocol.md 7.18): one verse a day on a Kids' Device's home, opened big with what it
// means and Read aloud. The device keeps a parent's imported translation (if one came); the parent's phone keeps its own.

/** The verses in assets/havamal.json (Bellows, 1923; built from code/shared/havamal.json), read once. Empty if the file can't be read. */
object HavAssets {
    @Volatile private var cached: List<HavVerse>? = null

    fun verses(context: Context): List<HavVerse> {
        cached?.let { return it }
        val v = try { context.assets.open(Havamal.ASSET).bufferedReader(Charsets.UTF_8).use { Havamal.parse(it.readText()) } } catch (e: Exception) { emptyList() }
        if (v.isNotEmpty()) cached = v
        return v
    }
}

/** A translation as kept: its hash ([Havamal.hash] of [json]), the text, and its stanzas. */
data class HavText(val h: String, val json: String, val stanzas: Map<Int, String>)

/** The Kids' Device side: the parent's translation it holds, and the day Daily Hávamál came on. */
object HavDevice {
    private const val FILE = "tv-havamal.json"
    private const val START = "havStart"
    private val _mine = MutableStateFlow<HavText?>(null)
    val mine: StateFlow<HavText?> = _mine.asStateFlow()
    /** Parts of a translation on their way. Used under KidTv's lock. */
    val assembler = Havamal.Assembler()

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    fun load(app: Muninn) {
        _mine.value = try { app.vault.read(file(app))?.let { read(it) } } catch (e: Exception) { null }
    }

    private fun read(s: String): HavText? {
        val json = JSONObject(s).getString("text")
        val map = Havamal.textIn(json) ?: return null
        return HavText(Havamal.hash(json), json, map)
    }

    /** A whole translation came from a parent ([json], hash checked): kept in the vault, replacing any other. */
    fun store(app: Muninn, json: String) {
        val map = Havamal.textIn(json) ?: return
        try { app.vault.write(file(app), JSONObject().put("text", json).toString()) } catch (_: Exception) {}
        _mine.value = HavText(Havamal.hash(json), json, map)
    }

    /** The hash of the translation held, for "applied". */
    fun held(): String? = _mine.value?.h

    /** Disconnect or leaving kids' mode: the translation goes too. */
    fun clear(app: Muninn) {
        file(app).delete()
        _mine.value = null
        forgetStart(app)
    }

    /** The parent's stanzas, only when the config names exactly the translation held. */
    fun stanzasFor(cfg: TvConfig, held: HavText?): Map<Int, String>? =
        held?.takeIf { cfg.havw == Havamal.MINE && cfg.havh == it.h }?.stanzas

    /** The day (days since 1970-01-01) Daily Hávamál first showed here; set to [today] the first time. */
    fun start(context: Context, today: Long): Long {
        val p = prefs(context)
        if (!p.contains(START)) p.edit().putLong(START, today).apply()
        return p.getLong(START, today)
    }

    /** Switched off: Earlier verses starts over the next time it comes on. */
    fun forgetStart(context: Context) { prefs(context).edit().remove(START).remove(SKIP).apply() }

    private const val SKIP = "havSkip"
    /** Bumped by "Next verse", so the row, the tile and the view show the new one. */
    var rev by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /** How many times "Next verse" was pressed [today] (0 on any other day: tomorrow starts from its own verse). */
    fun skips(context: Context, today: Long): Int = Havamal.skipsOn(prefs(context).getString(SKIP, null), today)

    /** "Next verse": the next one in order, today only. */
    fun next(context: Context, today: Long) {
        prefs(context).edit().putString(SKIP, Havamal.skipsKept(today, skips(context, today) + 1)).apply()
        rev++
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("muninn-tv", Context.MODE_PRIVATE)
}

/**
 * Reading a picked plain-text translation: at most [Havamal.MAX_FILE], only the stanzas on the list kept (matched by
 * number). The same for a parent's import (sent only to their own Kids' Devices) and the standard app's own (kept on
 * this device only, see screens/DailyHavamal.kt).
 */
object HavImport {
    sealed class Read {
        /** The stanzas matched, by number. */
        class Ok(val stanzas: Map<Int, String>) : Read()
        /** Nothing to keep: the line to show. */
        class Bad(val line: String) : Read()
    }

    /** Slow (a file): call it off the main thread. */
    fun read(app: Muninn, uri: Uri, verses: List<HavVerse>): Read {
        if (verses.isEmpty()) return Read.Bad("Can't read the verses")
        val bytes = try {
            app.contentResolver.openInputStream(uri)?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > Havamal.MAX_FILE) return Read.Bad("That file is too big")
                }
                buf.toByteArray()
            }
        } catch (e: Exception) { null } ?: return Read.Bad("Can't open that file")
        val map = Havamal.parseImport(String(bytes, Charsets.UTF_8), Havamal.numbers(verses))
        if (map.isEmpty()) return Read.Bad("No verses matched. The file needs stanza numbers, like \"1.\" or \"St. 1\".")
        return Read.Ok(map)
    }
}

/** The parent's side: their own imported translation, kept in their vault, sent only to their own Kids' Devices. */
object HavParent {
    private const val FILE = "havamal-mine.json"
    data class Mine(val text: HavText, val matched: Int, val of: Int)
    private val _mine = MutableStateFlow<Mine?>(null)
    val mine: StateFlow<Mine?> = _mine.asStateFlow()

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    fun load(app: Muninn) {
        _mine.value = try {
            app.vault.read(file(app))?.let { s ->
                val o = JSONObject(s)
                val json = o.getString("text")
                val map = Havamal.textIn(json) ?: return@let null
                Mine(HavText(Havamal.hash(json), json, map), map.size, o.optInt("of", map.size))
            }
        } catch (e: Exception) { null }
    }

    /**
     * Reads a picked plain-text file, keeps only the stanzas whose numbers are on the list, and saves it. Returns the
     * line to show. Slow (file and vault): call it off the main thread.
     */
    fun importFile(app: Muninn, uri: Uri): String {
        val verses = HavAssets.verses(app)
        val map = when (val r = HavImport.read(app, uri, verses)) {
            is HavImport.Read.Bad -> return r.line
            is HavImport.Read.Ok -> r.stanzas
        }
        val json = Havamal.textJson(map)
        try { app.vault.write(file(app), JSONObject().put("text", json).put("of", verses.size).toString()) } catch (e: Exception) { return "Can't save it" }
        _mine.value = Mine(HavText(Havamal.hash(json), json, map), map.size, verses.size)
        TvParent.havTextChanged()
        return Havamal.matchedLine(map.size, verses.size)
    }

    /** Remove: erased from the vault; devices using it go back to Bellows. */
    fun remove(app: Muninn) {
        file(app).delete()
        _mine.value = null
        TvParent.havTextChanged()
    }
}

/**
 * Today, by the device's local date (days since 1970-01-01), moving on at midnight. Round 17: by the trusted time quiet
 * times use (moving the device's clock forward shows no verses early), in the device's own time zone.
 */
@Composable
private fun havToday(): Long {
    fun today(): Long = java.time.Instant.ofEpochMilli(KidTv.nowMs()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
    val day by produceState(today()) { while (true) { delay(60_000); value = today() } }
    return day
}

/** Today's verse (moved on by "Next verse"), the ones before it, the parent's wording, and Next verse; null when there are none. */
private class HavDay(val first: HavVerse, val second: List<HavVerse>, val third: Map<Int, String>?, val next: () -> Unit)

@Composable
private fun havDay(cfg: TvConfig): HavDay? {
    val context = LocalContext.current
    // 1.43: a Kids' Device shows only the verses marked for children, in order.
    val verses = remember { Havamal.forKids(HavAssets.verses(context)) }
    if (verses.isEmpty()) return null
    val today = havToday()
    val start = remember(today) { HavDevice.start(context, today) }
    val held by HavDevice.mine.collectAsState()
    val skips = remember(today, HavDevice.rev) { HavDevice.skips(context, today) }
    val v = verses[Havamal.index(today + skips, verses.size)]
    return HavDay(v, Havamal.at(verses, Havamal.earlier(today, start, verses.size)), HavDevice.stanzasFor(cfg, held)) { HavDevice.next(context, today) }
}

/**
 * On a tablet or phone: today's verse as the first row of the chat list, like a chat (a gold book, the verse's first line
 * and its stanza). A tap opens it big, with what it means and Read aloud.
 */
@Composable
internal fun HavRow(cfg: TvConfig, modifier: Modifier = Modifier) {
    val day = havDay(cfg) ?: return
    var open by remember { mutableStateOf(false) }
    val text = Havamal.wordingOf(day.first, cfg.havw, day.third)
    io.github.ravenor907.muninn.screens.PersonRow("Today's verse", text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
        "Stanza ${day.first.n}", 0, false, {
            Box(Modifier.size(50.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
                MIcon("text", Palette.goldBright, 24.dp)
            }
        }, modifier = modifier) { open = true }
    if (open) HavDialog(cfg, day.first, day.second, day.third, tv = false, onNext = day.next) { open = false }
}

/** On a TV: today's verse as a tile at the end of the people, reached with the D-pad. */
@Composable
internal fun HavTile(cfg: TvConfig) {
    val day = havDay(cfg) ?: return
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && !Access.reduceMotion) 1.06f else 1f, label = "hav")
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.onFocusChanged { focused = it.isFocused }.graphicsLayer { scaleX = scale; scaleY = scale }
        .size(260.dp, 300.dp).clip(shape).background(if (focused) Palette.raised else Palette.panel)
        .then(if (focused) Modifier.border(4.dp, Palette.goldBright, shape) else Modifier)
        .clickable(role = Role.Button) { open = true }
        .semantics { contentDescription = "Today's verse" }, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MIcon("text", Palette.goldBright, 72.dp)
            Text("Today's verse", style = tvText(28.sp, weight = FontWeight.SemiBold), textAlign = TextAlign.Center)
            Text("Stanza ${day.first.n}", style = tvText(22.sp, Palette.textMuted))
        }
    }
    if (open) HavDialog(cfg, day.first, day.second, day.third, tv = true, onNext = day.next) { open = false }
}

/** The verse big, what it means, Read aloud (when the device can speak), Next verse and Earlier verses. */
@Composable
private fun HavDialog(cfg: TvConfig, today: HavVerse, earlier: List<HavVerse>, mine: Map<Int, String>?, tv: Boolean, onNext: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    // "Next verse" changes [today]: the view follows it.
    var shown by remember(today) { mutableStateOf(today) }
    var listing by remember { mutableStateOf(false) }
    // Read aloud (net/ReadAloud): the natural voice a parent picked (k15), or the device's own offline voice until it's in.
    val ready = io.github.ravenor907.muninn.net.ReadAloud.available
    DisposableEffect(Unit) { onDispose { io.github.ravenor907.muninn.net.ReadAloud.stop() } }
    // Round 17: reading aloud stops when a call rings or starts (it would talk over the ring and into the call), and when
    // Muninn goes behind another screen.
    val call by io.github.ravenor907.muninn.net.call.CallManager.call.collectAsState()
    LaunchedEffect(call != null) { if (call != null) io.github.ravenor907.muninn.net.ReadAloud.stop() }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) io.github.ravenor907.muninn.net.ReadAloud.stop() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    val first = remember { FocusRequester() }
    val big = if (tv) 30.sp else 21.sp
    val small = if (tv) 24.sp else 17.sp
    KidDialog(tv, onBack = { if (listing) listing = false else onClose() }) {
        if (listing) {
            Text("Earlier verses", style = tvTitle(if (tv) 32.sp else 24.sp))
            if (earlier.isEmpty()) Text("New verses show here after today.", style = tvText(small, Palette.textMuted), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp))
            earlier.forEachIndexed { i, v ->
                Box(Modifier.padding(top = 10.dp)) {
                    BigButton("Stanza ${v.n}", sub = Havamal.wordingOf(v, cfg.havw, mine).lineSequence().firstOrNull()?.take(60),
                        requester = if (i == 0) first else null) { shown = v; listing = false }
                }
            }
            Box(Modifier.padding(top = 14.dp)) { BigButton("Back", requester = if (earlier.isEmpty()) first else null) { listing = false } }
        } else {
            Text(if (shown == today) "Today's verse" else "Stanza ${shown.n}", style = tvTitle(if (tv) 32.sp else 24.sp))
            if (shown == today) Text("Stanza ${shown.n}", style = tvText(small, Palette.textMuted), modifier = Modifier.padding(top = 4.dp))
            val text = Havamal.wordingOf(shown, cfg.havw, mine)
            Text(text, style = tvText(big), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
            Text("What it means", style = tvText(small, Palette.goldBright, FontWeight.SemiBold), modifier = Modifier.padding(top = 20.dp))
            Text(shown.means, style = tvText(small), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ready) BigButton("Read aloud", icon = "sound", requester = first) {
                    // The verse, a pause, "What it means", then the meaning (each line a pause).
                    io.github.ravenor907.muninn.net.ReadAloud.speakVerse(context, text, shown.means)
                }
                if (shown != today) BigButton("Today's verse", requester = if (!ready) first else null) { shown = today }
                else BigButton("Next verse", icon = "forward", requester = if (!ready) first else null) {
                    io.github.ravenor907.muninn.net.ReadAloud.stop()
                    onNext()
                }
                BigButton("Earlier verses") {
                    io.github.ravenor907.muninn.net.ReadAloud.stop()
                    listing = true
                }
                BigButton("Close") { onClose() }
            }
        }
    }
    // The remote starts on the first button.
    LaunchedEffect(listing, shown, ready) { if (tv) { delay(120); try { first.requestFocus() } catch (_: Exception) {} } }
}
