package io.github.ravenor907.muninn.screens

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.ReadAloud
import io.github.ravenor907.muninn.tv.HavAssets
import io.github.ravenor907.muninn.tv.HavImport
import io.github.ravenor907.muninn.tv.HavOwn
import io.github.ravenor907.muninn.tv.HavVerse
import io.github.ravenor907.muninn.tv.Havamal
import io.github.ravenor907.muninn.ui.ChoiceRow
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.HoldToConfirm
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// Daily Hávamál in the standard app (plan 1.42, section 1): the same verses and "What it means" lines as a Kids' Device
// (code/shared/havamal.json), switched on in Settings › Look (off until then). A card at the top of the chat list; a tap
// opens the verse, what it means, Read aloud and Earlier verses; X hides it for the rest of today. The wording is Bellows
// or "Your translation", a plain-text file imported here and kept in this device's vault only: never sent to anyone, and
// left out of backups and moves (see Backup).

/** This device's Daily Hávamál: its switch and day values ([HavOwn]) and its own imported translation. */
object DailyHav {
    /** The imported translation, in the vault: {"text": [Havamal.textJson], "of": how many verses there were}. */
    const val FILE = "havamal-own.json"
    private const val PREFS = "muninn"

    /** Bumped on each change made here, so the card and Settings › Look show it at once. */
    var rev by mutableIntStateOf(0)
        private set

    /** CI screenshots only (never saved): 1 shows the card, 2 opens it too. */
    var ci by mutableIntStateOf(0)

    /** The imported translation: its stanzas by number, and how many of how many verses matched. */
    class Own(val stanzas: Map<Int, String>, val matched: Int, val of: Int)
    private val _own = MutableStateFlow<Own?>(null)
    val own: StateFlow<Own?> = _own.asStateFlow()
    @Volatile private var loaded = false

    /** The values, kept in Muninn's settings. */
    fun of(context: Context): HavOwn {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HavOwn({ k -> try { p.getString(k, null) } catch (e: Exception) { null } },
            { k, v -> p.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() })
    }

    /** Today by this device's own date (days since 1970-01-01). */
    fun today(): Long = LocalDate.now().toEpochDay()

    fun setOn(context: Context, on: Boolean) { of(context).setOn(on, today()); rev++ }
    fun hideToday(context: Context) { of(context).hideToday(today()); rev++ }
    fun setWording(context: Context, w: String) { of(context).wording = w; rev++ }

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    /** Reads the imported translation once the vault is open. Slow: off the main thread. */
    fun load(app: Muninn) {
        if (loaded) return
        val o = try { app.vault.read(file(app))?.let { JSONObject(it) } } catch (e: Exception) { null }
        val map = o?.optString("text")?.let { Havamal.textIn(it) }
        _own.value = if (o == null || map == null) null else Own(map, map.size, o.optInt("of", map.size))
        loaded = true
    }

    /** Reads a picked .txt, keeps the stanzas that match and saves them here. The line to show. Slow: off the main thread. */
    fun importFile(app: Muninn, uri: android.net.Uri): Pair<String, Boolean> {
        val verses = HavAssets.verses(app)
        val map = when (val r = HavImport.read(app, uri, verses)) {
            is HavImport.Read.Bad -> return r.line to false
            is HavImport.Read.Ok -> r.stanzas
        }
        try {
            app.vault.write(file(app), JSONObject().put("text", Havamal.textJson(map)).put("of", verses.size).toString())
        } catch (e: Exception) { return "Can't save it" to false }
        _own.value = Own(map, map.size, verses.size)
        loaded = true
        rev++
        return Havamal.matchedLine(map.size, verses.size) to true
    }

    /** Remove: erased here, and the wording goes back to Bellows. */
    fun remove(app: Muninn) {
        try { file(app).delete() } catch (_: Exception) {}
        _own.value = null
        of(app).wording = Havamal.BELLOWS
        rev++
    }
}

/** Today (days since 1970-01-01, this device's date), moving on at midnight. */
@Composable
private fun havToday(): Long {
    val day by produceState(DailyHav.today()) { while (true) { delay(60_000); value = DailyHav.today() } }
    return day
}

/** The card at the top of the chat list: today's verse, a tap to open it, X to hide it until tomorrow. */
@Composable
internal fun DailyHavCard(app: Muninn, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val today = havToday()
    val ci = DailyHav.ci
    val own = remember(DailyHav.rev, today) { DailyHav.of(context) }
    val shown = remember(DailyHav.rev, today) { own.cardShown(today) }
    if (ci == 0 && !shown) return
    val verses = remember { HavAssets.verses(context) }
    if (verses.isEmpty()) return
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DailyHav.load(app) } }
    val mine by DailyHav.own.collectAsState()
    val v = verses[Havamal.index(today, verses.size)]
    val wording = remember(DailyHav.rev) { own.wording }
    val text = Havamal.wordingOf(v, wording, mine?.stanzas)
    var open by remember { mutableStateOf(ci == 2) }
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Corner).background(Palette.goldTint)
        .clickable(role = Role.Button, onClickLabel = "Open") { open = true }
        .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MIcon("text", Palette.goldBright, 22.dp)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Today's verse · Stanza ${v.n}", style = Type.normal.copy(color = Palette.text, fontWeight = FontWeight.SemiBold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { DailyHav.hideToday(context); DailyHav.ci = 0 }
            .semantics { contentDescription = "Hide for today" }, contentAlignment = Alignment.Center) {
            MIcon("close", Palette.textMuted, 18.dp)
        }
    }
    if (open) {
        val earlier = remember(today) { Havamal.at(verses, Havamal.earlier(today, own.start(today), verses.size)) }
        DailyHavDialog(v, earlier, wording, mine?.stanzas) { open = false; if (DailyHav.ci == 2) DailyHav.ci = 1 }
    }
}

/** The verse, what it means, Read aloud (when this device can speak) and Earlier verses. */
@Composable
private fun DailyHavDialog(today: HavVerse, earlier: List<HavVerse>, wording: String, mine: Map<Int, String>?, onClose: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(today) }
    var listing by remember { mutableStateOf(false) }
    fun quiet() { try { ReadAloud.stop() } catch (_: Exception) {} }
    fun close() { quiet(); onClose() }
    // Reading stops when a call rings or starts, when Muninn goes behind another screen, and when this closes.
    val call by io.github.ravenor907.muninn.net.call.CallManager.call.collectAsState()
    LaunchedEffect(call != null) { if (call != null) quiet() }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) quiet() }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o); quiet() }
    }
    Dialog(onDismissRequest = { if (listing) listing = false else close() }) {
        Panel(Modifier.widthIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            if (listing) {
                Text("Earlier verses", style = Type.big)
                if (earlier.isEmpty()) Text("New verses show here after today.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                earlier.forEach { v ->
                    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(Corner).clickable(role = Role.Button) { shown = v; listing = false }
                        .padding(vertical = 8.dp)) {
                        Text("Stanza ${v.n}", style = Type.normal.copy(color = Palette.text))
                        Text(Havamal.wordingOf(v, wording, mine).lineSequence().firstOrNull().orEmpty(), style = Type.small,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                MButton("Back", Modifier.fillMaxWidth().padding(top = 10.dp)) { listing = false }
            } else {
                val text = Havamal.wordingOf(shown, wording, mine)
                Text(if (shown == today) "Today's verse" else "Stanza ${shown.n}", style = Type.big)
                if (shown == today) Text("Stanza ${shown.n}", style = Type.small, modifier = Modifier.padding(top = 2.dp))
                Text(text, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 12.dp))
                Text("What it means", style = Type.normal.copy(color = Palette.goldBright, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(top = 16.dp))
                Text(shown.means, style = Type.normal, modifier = Modifier.padding(top = 4.dp))
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ReadAloud.available) MButton("Read aloud", Modifier.fillMaxWidth(), Kind.Accent, icon = "sound") {
                        try { ReadAloud.speak(context, text.replace("\n", " ") + ". What it means. " + shown.means) } catch (_: Exception) {}
                    }
                    if (shown != today) MButton("Today's verse", Modifier.fillMaxWidth()) { quiet(); shown = today }
                    MButton("Earlier verses", Modifier.fillMaxWidth()) { quiet(); listing = true }
                    MButton("Close", Modifier.fillMaxWidth()) { close() }
                }
            }
        }
    }
}

/** Settings › Look › Daily Hávamál: the switch, the wording, and your own translation (kept on this device only). */
@Composable
internal fun DailyHavSettings(app: Muninn) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val own = remember(DailyHav.rev) { DailyHav.of(context) }
    val on = remember(DailyHav.rev) { own.on }
    val wording = remember(DailyHav.rev) { own.wording }
    val mine by DailyHav.own.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { DailyHav.load(app) } }
    var importing by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            scope.launch {
                val (line, ok) = withContext(Dispatchers.IO) { DailyHav.importFile(app, uri) }
                importing = false
                Ui.say(line, !ok)
            }
        }
    }
    ToggleRow("Daily Hávamál", "A verse a day at the top of your chats", on) { DailyHav.setOn(context, it) }
    if (!on) return
    Text("Wording", style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 10.dp))
    val choices = buildList {
        add(Havamal.BELLOWS to Havamal.BELLOWS_NAME)
        if (mine != null) add(Havamal.MINE to Havamal.MINE_NAME)
    }
    ChoiceRow(choices.size, Modifier.padding(vertical = 8.dp), minWidth = 120.dp) { i, m ->
        val (w, name) = choices[i]
        MButton(name, m, if (wording == w || (choices.size == 1 && w == Havamal.BELLOWS)) Kind.Accent else Kind.Normal) { DailyHav.setWording(context, w) }
    }
    Text(Havamal.MINE_NAME, style = Type.normal.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
    Text("A plain-text file (.txt) with stanza numbers, like \"1.\" or \"St. 1\". It stays on this device and is never sent.",
        style = Type.small, modifier = Modifier.padding(top = 4.dp))
    val m = mine
    if (m != null) Text(Havamal.matchedLine(m.matched, m.of), style = Type.small.copy(color = Palette.text), modifier = Modifier.padding(top = 6.dp))
    MButton(if (importing) "Importing…" else if (m == null) "Import a translation" else "Import another",
        Modifier.fillMaxWidth().padding(vertical = 8.dp), enabled = !importing) {
        try { io.github.ravenor907.muninn.ui.Picking.launch(pick, arrayOf("text/plain")) } catch (_: Exception) { Ui.say("Can't open files here", true) }
    }
    if (m != null) HoldToConfirm("Hold to remove", "Hold for 5 seconds to remove your translation", { DailyHav.remove(app); Ui.say("Removed") },
        Modifier.fillMaxWidth().padding(bottom = 8.dp))
}
