package io.github.ravenor907.muninn.screens

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.FileLink
import io.github.ravenor907.muninn.net.FileText
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Received
import io.github.ravenor907.muninn.net.Sharing
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.Corner
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.Line
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

// File sharing (cap f2, shared/docs/protocol.md section 27): Transfers (the chat list's three dots), one card for a file
// on its way (the same in a chat, a group and Transfers), a chat's Files, a group's file rules, and the share sheet's
// "Send to" picker.

/** A file on its way, in a few words: size, kind, what it's doing, its route and pace. */
internal fun transferLine(f: FileLink.Progress, who: String): String {
    val status = when {
        f.state == "sending" && f.paused -> "Paused"
        f.state == "sending" && f.held -> "Paused on their side"
        else -> FileText.line(f.state, f.mine, f.done, f.size, f.note, who)
    }
    return listOf(FileText.size(f.size), f.kind, status, if (f.state != "failed") f.route else "", f.pace).filter { it.isNotEmpty() }.joinToString(" · ")
}

/**
 * One transfer's card: type icon, name, what it's doing with its route and speed, who it's from or to and where it goes,
 * a bar while it moves, and its buttons: Accept, Always from this person and Decline when it asks; Pause or Resume and
 * Cancel while it moves; Open, Folder and Close once saved; Retry and Close after a failure. [showChat]: say which chat
 * (Transfers). [resend]: Retry hands the file back (who, the file, its name, its group).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransferCard(f: FileLink.Progress, showChat: Boolean, resend: (String, File, String, String) -> Unit) {
    val context = LocalContext.current
    val link = FileLink
    val failed = f.state == "failed"
    val who = link.host?.nameOf(f.peer) ?: "Someone"
    val chat = link.chatOf(f)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Corner).background(Palette.panel).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(Corner).background(Palette.coolTint), contentAlignment = Alignment.Center) {
                MIcon(FileText.icon(f.name), if (failed) Palette.danger else Palette.accent, 22.dp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(f.name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(transferLine(f, who), style = Type.small.copy(color = if (failed) Palette.danger else Palette.textMuted), maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
                val whereLine = if (f.mine) (if (showChat) "To $chat" else "")
                    else "From $who" + (if (f.group.isNotEmpty()) " in $chat" else "") + (if (f.folder.isNotEmpty()) " · Saves to ${f.folder}" else "")
                if (whereLine.isNotEmpty()) Text(whereLine, style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (f.state == "sending") Bar(if (f.size > 0) f.done.toFloat() / f.size else 0f, Modifier.padding(top = 8.dp),
            if (f.paused || f.held) Palette.textMuted else Palette.gold)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                f.state == "waiting" || f.state == "asking" -> {
                    MButton(if (f.state == "asking") "Accept" else "Download", kind = Kind.Accent) { link.accept(f.id) }
                    if (link.host?.isContact(f.peer) == true) MButton("Always from this person") { link.acceptAlways(f.id) }
                    MButton("Decline") { link.decline(f.id) }
                }
                link.canResume(f.id) -> {
                    MButton("Resume", kind = Kind.Accent, icon = "play") { link.resume(f.id) }
                    MButton("Cancel", kind = Kind.Danger) { link.cancel(f.id) }
                }
                link.canPause(f.id) -> {
                    MButton("Pause", icon = "pause") { link.pause(f.id) }
                    MButton("Cancel", kind = Kind.Danger) { link.cancel(f.id) }
                }
                link.canCancel(f.id) -> MButton("Cancel", kind = Kind.Danger) { link.cancel(f.id) }
                f.state == "done" && !f.mine && f.uri.isNotEmpty() -> {
                    // An app installer opens only when the person asks, and on a Kids' TV not at all (a parent approves it).
                    val kids = io.github.ravenor907.muninn.tv.KidMode.saved(context)
                    if (!(kids && FileText.isApp(f.name))) MButton("Open", kind = Kind.Accent) {
                        if (!Received.open(context, f.uri, f.name)) Ui.say("Nothing on this device opens this kind of file.", true)
                    }
                    MButton("Folder", icon = "folder") {
                        if (!Received.showFolder(context)) Ui.say("It's in ${f.folder.ifEmpty { "Downloads" }}. Open it from your Files app.")
                    }
                    MButton("Close") { link.dismiss(f.id) }
                }
                failed && link.canRetry(f.id) -> {
                    MButton("Retry", kind = Kind.Accent, icon = "refresh") {
                        val g = link.retryGroup(f.id)
                        link.takeRetry(f.id)?.let { (p, file, n) -> resend(p, file, n, g) }
                    }
                    MButton("Close") { link.dismiss(f.id) }
                }
                !FileText.running(f.state) -> MButton("Close") { link.dismiss(f.id) }
            }
        }
    }
}

/** Retry for a card: a person's file again, or one member's copy of a group's file. */
internal fun resendFile(app: Muninn, scope: CoroutineScope): (String, File, String, String) -> Unit = { p, file, n, g ->
    scope.launch(Dispatchers.IO) {
        if (g.isEmpty()) app.chat.sendFile(p, file, n)?.let { Ui.say(it, true) }
        else app.chat.sendMemberFile(p, file, n, FileLink.preparing(p, n, file.length(), g), g)
    }
}

/**
 * The one way to send a file from this phone, for the clip in a chat or group and the share sheet: copied in, then to
 * the person, or to everyone in the group by the best route to each. [where]: their key or "g:<group>".
 */
internal fun sendFileFrom(app: Muninn, context: Context, scope: CoroutineScope, where: String, uri: Uri, then: () -> Unit = {}) {
    scope.launch(Dispatchers.IO) {
        var chosen: String? = null
        var size = 0L
        try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { chosen = c.getString(0); if (!c.isNull(1)) size = c.getLong(1) }
            }
        } catch (_: Exception) {}
        val clean = app.chat.cleanFileName(chosen ?: "file")
        val tmp = File(File(context.cacheDir, "files").also { it.mkdirs() }, "out-" + System.nanoTime())
        val ok = try { context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } != null } catch (e: Exception) { false }
        if (!ok) { tmp.delete(); Ui.say("That file couldn't be opened.", true) }
        else if (where.startsWith("g:")) app.chat.sendGroupFile(where.removePrefix("g:").substringBefore('/'), tmp, clean)?.let { Ui.say(it, true) }
        else {
            val card = FileLink.preparing(where, clean, size)
            app.chat.sendFile(where, tmp, clean, card)?.let { Ui.say(it, true) }
        }
        then()
    }
}

/** Running or waiting for an answer here: the count beside Transfers. */
internal fun transfersRunning(all: Map<String, FileLink.Progress>, nearby: Boolean): Int =
    all.values.count { FileText.running(it.state) || it.state == "asking" || it.state == "waiting" } + (if (nearby) 1 else 0)

/**
 * Transfers (the chat list's three dots): every file coming or going, newest first, the nearby (LocalSend) one first
 * while it runs. Each says what it is, who it's from or to and which chat, how it's going and its route, and why when it
 * didn't work.
 */
@Composable
internal fun TransfersScreen(app: Muninn) {
    val scope = rememberCoroutineScope()
    val all by FileLink.all.collectAsState()
    val nearby by app.net.transfer.collectAsState()
    val list = all.values.sortedByDescending { it.time }
    Column(Modifier.fillMaxSize()) {
        PageBar("Transfers")
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (nearby.active) item {
                Panel(Modifier.padding(top = 8.dp)) {
                    Text(TransferViewTitle(nearby), style = Type.big)
                    Text(listOf(LocalSend.size(nearby.total), nearby.status, "Direct", nearby.pace()).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = Type.small, modifier = Modifier.padding(top = 4.dp))
                    Bar(nearby.fraction, Modifier.padding(top = 8.dp), Palette.gold)
                    if (nearby.sending) MButton("Cancel", Modifier.padding(top = 8.dp), kind = Kind.Danger) { app.net.cancelSend() }
                    else MButton("Cancel", Modifier.padding(top = 8.dp), kind = Kind.Danger) { app.net.cancelReceive() }
                }
            }
            if (list.isEmpty() && !nearby.active) item {
                Text("Nothing on its way. Send a file with the clip in any chat or group, or share it to Muninn from another app.",
                    style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 16.dp))
            }
            items(list, key = { it.id }) { f -> TransferCard(f, showChat = true, resend = resendFile(app, scope)) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun TransferViewTitle(t: LocalSend.Transfer): String =
    (if (t.sending) "To " else "From ") + "LocalSend · " + t.peer.ifEmpty { "a device nearby" }

/**
 * A chat's Files (its three dots › Files): everything sent and received there, newest first, with Open, Folder and
 * Delete (a received file goes from Downloads too; a sent one only leaves the list). A group's page starts with who
 * can send files and the size limit, which admins change. [where]: their key or "g:<group>".
 */
@Composable
internal fun ChatFilesScreen(app: Muninn, where: String) {
    val context = LocalContext.current
    val files by app.chat.sharedFiles.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val g = if (where.startsWith("g:")) where.removePrefix("g:") else ""
    val grp = if (g.isEmpty()) null else groups[g]
    val title = grp?.state?.name ?: app.chat.nameOf(where)
    var rules by remember { mutableStateOf(false) }
    val shown = remember(files, where) { app.chat.filesIn(where) }
    Column(Modifier.fillMaxSize()) {
        PageBar("Files · $title", "Back to $title") { ChatNav.open = where }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            item {
                Text("Received files are saved in Download/${Sharing.folderFor("Muninn", title)}.", style = Type.small, modifier = Modifier.padding(top = 10.dp))
                if (grp != null) {
                    val admin = !grp.readOnly && grp.state.isAdmin(app.identity.publicKey)
                    Panel(Modifier.padding(top = 10.dp)) {
                        Text("Who can send files", style = Type.big)
                        Text(Sharing.rulesLine(grp.state.fs, grp.state.fm), style = Type.normal, modifier = Modifier.padding(top = 4.dp))
                        Text("Every member's app follows this.", style = Type.small, modifier = Modifier.padding(top = 2.dp))
                        if (admin) MButton("Change", Modifier.padding(top = 8.dp), icon = "edit") { rules = true }
                    }
                }
                if (shown.isEmpty()) Text("No files here yet. Send one with the clip beside the message box.", style = Type.normal.copy(color = Palette.textMuted),
                    modifier = Modifier.padding(top = 16.dp))
            }
            items(shown, key = { it.time.toString() + it.name + it.uri }) { f -> SharedFileRow(app, context, f) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (rules && grp != null) FileRulesDialog(app, g, grp.state.fs, grp.state.fm) { rules = false }
}

@Composable
private fun SharedFileRow(app: Muninn, context: Context, f: Chat.SharedFile) {
    val there = remember(f.uri) { f.mine || Received.exists(context, f.uri) }
    val day = remember(f.time) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(f.time)) }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Corner).background(Palette.panel).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(Corner).background(Palette.coolTint), contentAlignment = Alignment.Center) {
                MIcon(FileText.icon(f.name), if (there) Palette.accent else Palette.textMuted, 22.dp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(f.name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(FileText.size(f.size) + " · " + (if (f.mine) "Sent " else "Received ") + day + (if (!there) " · Moved or deleted" else ""),
                    style = Type.small.copy(color = if (there) Palette.textMuted else Palette.danger), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val kids = io.github.ravenor907.muninn.tv.KidMode.saved(context)
            if (!f.mine && there && !(kids && FileText.isApp(f.name))) MButton("Open", kind = Kind.Accent) {
                if (!Received.open(context, f.uri, f.name)) Ui.say("Nothing on this device opens this kind of file.", true)
            }
            if (!f.mine && there) MButton("Folder", icon = "folder") {
                if (!Received.showFolder(context)) Ui.say("It's in Downloads. Open it from your Files app.")
            }
            MButton(if (f.mine) "Remove" else "Delete", kind = Kind.Danger) {
                if (f.mine) { app.chat.deleteFile(f); Ui.say("Removed from the list. The file itself stays where it was.") }
                else Ui.sure(io.github.ravenor907.muninn.ui.Ask("Delete ${f.name}?", if (there) "It's deleted from this phone." else "It's already gone; this takes it off the list.",
                    "Delete", danger = true, onConfirm = { if (app.chat.deleteFile(f)) Ui.say("Deleted.") else Ui.say("It couldn't be deleted.", true) }))
            }
        }
    }
}

/** A group's file rules (admins): who can send files and the size limit. Every member's app enforces them. */
@Composable
internal fun FileRulesDialog(app: Muninn, g: String, fs: String, fm: Int, done: () -> Unit) {
    var who by remember { mutableStateOf(Sharing.whoMaySend(fs)) }
    var mb by remember { mutableStateOf(fm) }
    AlertDialog(
        onDismissRequest = done,
        containerColor = Palette.panel,
        title = { Text("Who can send files", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Line("Everyone", selected = who == Sharing.EVERYONE) { who = Sharing.EVERYONE }
                Line("Admins only", selected = who == Sharing.ADMINS) { who = Sharing.ADMINS }
                Line("Nobody", selected = who == Sharing.NOBODY) { who = Sharing.NOBODY }
                if (who != Sharing.NOBODY) {
                    Text("Size limit", style = Type.big, modifier = Modifier.padding(top = 12.dp))
                    listOf(0, 10, 25, 100, 500, 2048).forEach { n ->
                        Line(if (n == 0) "No limit" else if (n >= 1024) "${n / 1024} GB" else "$n MB", selected = mb == n) { mb = n }
                    }
                }
            }
        },
        confirmButton = {
            MButton("Save", kind = Kind.Accent) {
                done()
                app.chat.groups.setFiles(g, who, if (who == Sharing.NOBODY) 0 else mb)?.let { Ui.say(it, true) } ?: Ui.say("Saved. Every member's app follows it.")
            }
        },
        dismissButton = { MButton("Cancel") { done() } },
    )
}

/** Files shared into Muninn from another app, waiting for "Send to" (MainActivity's share sheet). */
object ShareTo {
    val pending = androidx.compose.runtime.mutableStateListOf<Uri>()
}

/**
 * The share sheet's "Send to": people, groups, and LocalSend devices nearby ("Nearby: LocalSend · <device>"). A person
 * or a group gets the files like the clip in their chat (and their chat opens); a device nearby gets them over LocalSend.
 */
@Composable
internal fun ShareToDialog(app: Muninn, openChat: (String) -> Unit) {
    if (ShareTo.pending.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val people by app.chat.contacts.all.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val devices by app.net.devices.collectAsState()
    val uris = ShareTo.pending.toList()
    fun toChat(where: String) {
        ShareTo.pending.clear()
        uris.forEach { sendFileFrom(app, context, scope, where, it) }
        openChat(where)
    }
    AlertDialog(
        onDismissRequest = { ShareTo.pending.clear() },
        containerColor = Palette.panel,
        title = { Text(if (uris.size == 1) "Send the file to" else "Send ${uris.size} files to", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val contacts = people.filter { !it.groupOnly }.sortedBy { app.chat.nameOf(it.chatKey).lowercase() }
                if (contacts.isNotEmpty()) Text("PEOPLE", style = Type.small, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                contacts.forEach { c -> Line(app.chat.nameOf(c.chatKey), icon = "user") { toChat(c.chatKey) } }
                val live = groups.values.filter { !it.readOnly }
                if (live.isNotEmpty()) Text("GROUPS", style = Type.small, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                live.forEach { gr -> Line(gr.state.name, icon = "people") { toChat(io.github.ravenor907.muninn.net.Folders.groupKey(gr.state.g)) } }
                val plain = devices.filter { !it.canChat }
                Text("NEARBY", style = Type.small, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                if (plain.isEmpty()) Text("No LocalSend devices on this Wi-Fi.", style = Type.small)
                plain.forEach { d ->
                    Line("Nearby: LocalSend · ${d.alias}", icon = "network") {
                        ShareTo.pending.clear()
                        scope.launch(Dispatchers.IO) {
                            val files = Share.copyIn(context, uris, app.net.transfer.value.active)
                            if (files.isEmpty()) Ui.say("Those files couldn't be read.", true) else app.net.send(d.id, files)
                        }
                        Ui.say("Sending. Transfers shows how it goes.")
                    }
                }
            }
        },
        confirmButton = { MButton("Cancel") { ShareTo.pending.clear() } },
    )
}
