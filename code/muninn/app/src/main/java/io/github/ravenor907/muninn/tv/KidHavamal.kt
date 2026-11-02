package io.github.ravenor907.muninn.tv

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
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

/** The verses in assets/havamal.json (Bellows, 1923), read once. Empty if the file can't be read. */
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
    fun forgetStart(context: Context) { prefs(context).edit().remove(START).apply() }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("muninn-tv", Context.MODE_PRIVATE)
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
        if (verses.isEmpty()) return "Can't read the verses"
        val bytes = try {
            app.contentResolver.openInputStream(uri)?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > Havamal.MAX_FILE) return "That file is too big"
                }
                buf.toByteArray()
            }
        } catch (e: Exception) { null } ?: return "Can't open that file"
        val map = Havamal.parseImport(String(bytes, Charsets.UTF_8), Havamal.numbers(verses))
        if (map.isEmpty()) return "No verses matched. The file needs stanza numbers, like \"1.\" or \"St. 1\"."
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

/** Today, by the device's local date (days since 1970-01-01), moving on at midnight. */
@Composable
private fun havToday(): Long {
    val day by produceState(LocalDate.now().toEpochDay()) { while (true) { delay(60_000); value = LocalDate.now().toEpochDay() } }
    return day
}

/** Today's verse and the ones before it, or null when there are none. */
@Composable
private fun havDay(cfg: TvConfig): Triple<HavVerse, List<HavVerse>, Map<Int, String>?>? {
    val context = LocalContext.current
    val verses = remember { HavAssets.verses(context) }
    if (verses.isEmpty()) return null
    val today = havToday()
    val start = remember(today) { HavDevice.start(context, today) }
    val held by HavDevice.mine.collectAsState()
    val v = verses[Havamal.index(today, verses.size)]
    return Triple(v, Havamal.at(verses, Havamal.earlier(today, start, verses.size)), HavDevice.stanzasFor(cfg, held))
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
    if (open) HavDialog(cfg, day.first, day.second, day.third, tv = false) { open = false }
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
    if (open) HavDialog(cfg, day.first, day.second, day.third, tv = true) { open = false }
}

/** The verse big, what it means, Read aloud (when the device can speak) and Earlier verses. */
@Composable
private fun HavDialog(cfg: TvConfig, today: HavVerse, earlier: List<HavVerse>, mine: Map<Int, String>?, tv: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(today) }
    var listing by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val tts = remember { arrayOfNulls<TextToSpeech>(1) }
    DisposableEffect(Unit) {
        tts[0] = try { TextToSpeech(context.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS } } catch (e: Exception) { null }
        onDispose { try { tts[0]?.stop(); tts[0]?.shutdown() } catch (_: Exception) {}; tts[0] = null }
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
                    try {
                        tts[0]?.speak(text.replace("\n", " ") + ". What it means. " + shown.means, TextToSpeech.QUEUE_FLUSH, null, "hav")
                    } catch (_: Exception) {}
                }
                if (shown != today) BigButton("Today's verse", requester = if (!ready) first else null) { shown = today }
                BigButton("Earlier verses", requester = if (!ready && shown == today) first else null) {
                    try { tts[0]?.stop() } catch (_: Exception) {}
                    listing = true
                }
                BigButton("Close") { onClose() }
            }
        }
    }
    // The remote starts on the first button.
    LaunchedEffect(listing, shown, ready) { if (tv) { delay(120); try { first.requestFocus() } catch (_: Exception) {} } }
}
