package io.github.ravenor907.muninn.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.LabsInbox
import io.github.ravenor907.muninn.net.LabsWire
import io.github.ravenor907.muninn.net.Photos
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Field
import io.github.ravenor907.muninn.ui.Framed
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MenuRow
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.ToggleRow
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import java.text.DateFormat
import java.util.Date

/**
 * Labs mode: tap the raven in Settings 10 times (within 5 seconds) to turn it on, 10 more to turn it off. It shows
 * the Labs page in Settings (consolidation 2.15: Report a bug, Labs reports, Update channel, Replay first setup and,
 * on Pre-release, Things to test) and Report a bug in the chat list's menu.
 */
object Labs {
    private val taps = ArrayDeque<Long>()
    /** Bumped on every change, so Settings and the chat list menu redraw. */
    var changed by mutableStateOf(0)
        private set
    /** The screenshot taken for the report screen, if any (the screen as it was just before). */
    @Volatile var shot: Bitmap? = null
    /** What the report screen starts with (Things to test's "Beta test t1 Natural voices: "), taken once. */
    @Volatile var prefill: String = ""

    fun on(app: Muninn): Boolean { changed; return app.identity.labs }

    fun tap(app: Muninn) {
        val t = android.os.SystemClock.elapsedRealtime()
        taps.addLast(t)
        while (taps.isNotEmpty() && t - taps.first() > 5_000) taps.removeFirst()
        if (taps.size < 10) return
        taps.clear()
        val now = !app.identity.labs
        app.identity.labs = now
        // Off: back on the Stable channel too (what's installed stays until the next release).
        if (!now) app.identity.preRelease = false
        changed++
        Ui.say(if (now) "Labs mode on" else "Labs mode off")
    }

    fun setPreRelease(app: Muninn, on: Boolean) {
        app.identity.preRelease = on
        changed++
        // Pre-release builds then come by the normal automatic update.
        if (on && app.identity.autoUpdate) app.updater.check(installAfter = true)
    }

    fun goBack(app: Muninn) {
        Ui.sure(Ask("Go back to stable?", "Installs the latest stable release now.", "Go back") {
            app.updater.backToStable(); changed++
        })
    }

    /** Report a bug: takes a picture of this app's own window as it is now, then opens the report screen. */
    fun report(context: Context, start: String = "") {
        val activity = activityOf(context)
        shot = null
        prefill = start
        val open: () -> Unit = { SettingsNav.goChat = "labsreport" }
        if (activity == null) { open(); return }
        capture(activity) { b -> shot = b; open() }
    }

    private fun activityOf(c: Context): Activity? {
        var x: Context? = c
        while (x != null) { if (x is Activity) return x; x = (x as? ContextWrapper)?.baseContext }
        return null
    }

    /** PixelCopy of Muninn's own window (never another app's), or drawing it, or null. */
    private fun capture(a: Activity, done: (Bitmap?) -> Unit) {
        val view = a.window.decorView
        val w = view.width; val h = view.height
        if (w <= 0 || h <= 0) { done(null); return }
        val b = try { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) } catch (e: Throwable) { done(null); return }
        fun drawn(): Bitmap? = try { view.draw(android.graphics.Canvas(b)); b } catch (e: Exception) { null }
        try {
            PixelCopy.request(a.window, b, { result -> done(if (result == PixelCopy.SUCCESS) b else drawn()) }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { done(drawn()) }
    }

    fun info(app: Muninn) = LabsWire.info("muninn", app.updater.current, "Android ${Build.VERSION.RELEASE}",
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" "))
}

/**
 * Settings › Labs, one page (consolidation 2.15): Report a bug, Labs reports (the Labs contact's own phone), the
 * update channel, Replay first setup, and Voice tuning and Things to test while on Pre-release. The chat list's menu keeps Report a bug
 * (it takes a picture of the screen you're on).
 */
@Composable
internal fun LabsPage(app: Muninn) {
    val context = LocalContext.current
    Labs.changed
    Framed {
        MenuRow("info", "Report a bug") { Labs.report(context) }
        if (app.chat.isLabs()) {
            val reports by app.chat.labs.reports.collectAsState()
            MenuRow("info", "Labs reports", value = if (reports.isNotEmpty()) "${reports.size}" else "") { SettingsNav.goChat = Chat.LABS_PAGE }
        }
        // First setup again, as practice: nothing it does is saved.
        MenuRow("refresh", "Replay first setup (practice)") { Setup.practice(app) }
    }
    UpdateChannel(app)
    // Each voice packet on the relay as well as a direct path, for calls that drop out. Off until someone turns it on.
    Framed {
        val prefs = remember { app.getSharedPreferences("muninn", android.content.Context.MODE_PRIVATE) }
        var two by remember { mutableStateOf(prefs.getBoolean(io.github.ravenor907.muninn.net.call.CallLogic.TWO_PATHS, false)) }
        io.github.ravenor907.muninn.ui.ToggleRow("Voice over two paths", "Sends voice on two routes in calls. Uses the relay more", two) {
            two = it
            prefs.edit().putBoolean(io.github.ravenor907.muninn.net.call.CallLogic.TWO_PATHS, it).apply()
        }
    }
    // The natural voices' tuning, only on the Pre-release channel.
    if (app.identity.preRelease) Framed { MenuRow("sound", "Voice tuning") { SettingsNav.folder = "tuning" } }
    // The beta's checklist: only on the Pre-release channel.
    if (app.identity.preRelease) ThingsToTest()
}

/** Report a bug: the screenshot (on by default), what happened, then Send. */
@Composable
internal fun LabsReportScreen(app: Muninn) {
    val shot = remember { Labs.shot }
    val image = remember(shot) { shot?.asImageBitmap() }
    var include by remember { mutableStateOf(shot != null) }
    var text by remember { mutableStateOf(Labs.prefill.take(LabsWire.MAX_TEXT).also { Labs.prefill = "" }) }
    var sending by remember { mutableStateOf(false) }
    fun close() { Labs.shot = null; ChatNav.open = "" }
    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Report a bug") { close() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (image != null) {
                Image(image, contentDescription = "Screenshot", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).border(1.dp, Palette.line, Corner))
                ToggleRow("Include screenshot", "Only Muninn's own screen", include) { include = it }
            }
            Field(text, { if (it.length <= LabsWire.MAX_TEXT) text = it }, "What happened?", Modifier.heightIn(min = 120.dp), single = false)
            Text("${text.length} / ${LabsWire.MAX_TEXT}", style = Type.small)
            Text("Sent encrypted to ${LabsWire.NAME}, with the app version, Android version and phone model.", style = Type.small)
        }
        MButton(if (sending) "Sending…" else "Send", Modifier.fillMaxWidth().padding(16.dp), Kind.Accent, enabled = !sending && text.isNotBlank()) {
            sending = true
            val bmp = if (include) shot else null
            Thread {
                val jpeg = bmp?.let { Photos.screenshot(it) }
                app.chat.sendLabsReport(text, jpeg, Labs.info(app)) { ok ->
                    Handler(Looper.getMainLooper()).post {
                        sending = false
                        if (ok) { Ui.say("Report sent. Thank you."); close() } else Ui.say("Couldn't send. Try again.", true)
                    }
                }
            }.start()
        }
    }
}

/** The Labs contact's own phone: every report that came in, newest first. Reply makes the sender a contact. */
@Composable
internal fun LabsReportsScreen(app: Muninn) {
    androidx.compose.runtime.LaunchedEffect(Unit) { Thread { app.chat.labs.load() }.start() }
    val reports by app.chat.labs.reports.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageBar("Labs reports")
        if (reports.isEmpty()) Text("No reports yet.", style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(reports, key = { it.id }) { r -> ReportCard(app, r) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ReportCard(app: Muninn, r: LabsInbox.Report) {
    val shot = remember(r.id) { decode(app.chat.labs.screenshot(r), 1280) }
    val who = if (app.chat.isContact(r.from)) app.chat.nameOf(r.from) else Chat.stranger(r.name)
    Panel(Modifier.padding(top = 10.dp)) {
        Text(who, style = Type.big)
        Text(listOf(r.app.replaceFirstChar { it.uppercase() }, r.version, r.os, r.model).filter { it.isNotBlank() }.joinToString(" · "), style = Type.small)
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.at)), style = Type.small)
        Text(r.text, style = Type.normal, modifier = Modifier.padding(top = 8.dp))
        if (shot != null) Image(shot, contentDescription = "Screenshot", contentScale = ContentScale.Fit,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().heightIn(max = 480.dp).border(1.dp, Palette.line, Corner))
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MButton("Reply", Modifier.weight(1f), Kind.Accent) { app.chat.labsReply(r); ChatNav.open = r.from }
            val clip = androidx.compose.ui.platform.LocalClipboardManager.current
            MButton("Copy", Modifier.weight(1f)) {
                val head = listOf(r.app.replaceFirstChar { it.uppercase() }, r.version, r.os, r.model).filter { it.isNotBlank() }.joinToString(" · ")
                clip.setText(androidx.compose.ui.text.AnnotatedString(head + "\n\n" + r.text))
                Ui.say("Report copied.")
            }
            MButton("Delete", Modifier.weight(1f), Kind.Danger) {
                Ui.sure(Ask("Delete this report?", "Its screenshot goes too.", "Delete", danger = true) { app.chat.labs.delete(r.id) })
            }
        }
    }
}
