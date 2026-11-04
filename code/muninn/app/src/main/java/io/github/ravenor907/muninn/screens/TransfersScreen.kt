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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.combinedClickable
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
import io.github.ravenor907.muninn.ui.Field
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
    val tor = f.route.startsWith(io.github.ravenor907.muninn.net.TorFiles.TOR)
    val status = when {
        f.state == "sending" && f.paused -> "Paused"
        f.state == "sending" && f.held -> "Paused on their side"
        // Cap f4: queued ("Sends when Jo's phone is online"), "Connecting privately…" while Tor starts, and the path.
        (f.state == "offered" || f.state == "connecting") && f.note.isNotEmpty() -> f.note
        f.state == "sending" && tor && f.note.isEmpty() -> (if (f.mine) "Sending" else "Receiving") + " over Tor (slower) · " + FileText.percent(f.done, f.size) + "%"
        else -> FileText.line(f.state, f.mine, f.done, f.size, f.note, who, f.folder)
    }
    val route = if (f.state == "failed" || (tor && f.state == "sending")) "" else f.route
    return listOf(FileText.size(f.size), f.kind, status, route, f.pace).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The trust badge's colors (the mockup's): ink on a soft fill. */
private fun badgeFill(badge: String): androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color(when (badge) {
    FileLink.BADGE_TRUSTED -> 0xFF9FC7A6
    FileLink.BADGE_FAMILY -> 0xFFA9C4DE
    FileLink.BADGE_CONTACT -> 0xFFD8C39A
    else -> 0xFFE3A99E
})

/**
 * The receiving card (1.42, both apps): who (with their trust badge), "wants to send you a ZIP file", the file's name,
 * size and how it comes (Nearby, Trusted direct, Tor slower), the risky note for programs and archives, Decline and
 * Accept, and "Always accept from <name>" only for someone trusted or Family. Every file asks first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IncomingFileCard(f: FileLink.Progress, who: String, showChat: Boolean) {
    val link = FileLink
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Corner).background(Palette.panel).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(who, style = Type.normal.copy(color = Palette.textBright, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.badge.isNotEmpty()) Text(f.badge, style = Type.small.copy(color = androidx.compose.ui.graphics.Color(0xFF0D1411),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                modifier = Modifier.clip(Corner).background(badgeFill(f.badge)).padding(horizontal = 9.dp, vertical = 3.dp))
        }
        Text("wants to send you ${f.typeWord}" + (if (showChat && f.group.isNotEmpty()) " in ${link.chatOf(f)}" else ""),
            style = Type.normal.copy(color = Palette.textSoft))
        Row(Modifier.fillMaxWidth().clip(Corner).background(Palette.panelLow).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MIcon(FileText.icon(f.name), Palette.gold, 30.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(f.name, style = Type.normal.copy(color = Palette.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(FileText.size(f.size), f.method).filter { it.isNotEmpty() }.joinToString(" · "), style = Type.small, maxLines = 2)
            }
        }
        if (f.risky) Row(verticalAlignment = Alignment.Top) {
            MIcon("warning", androidx.compose.ui.graphics.Color(0xFFE3B27A), 16.dp)
            Text(FileLink.RISKY_NOTE, style = Type.small.copy(color = androidx.compose.ui.graphics.Color(0xFFE3B27A)), modifier = Modifier.padding(start = 8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MButton("Decline", Modifier.weight(1f)) { link.decline(f.id) }
            MButton("Accept", Modifier.weight(1f), kind = Kind.Accent) { link.accept(f.id) }
        }
        if (f.canAlways) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MButton("Always accept from $who") { link.acceptAlways(f.id) }
        }
    }
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
    // 1.42 (owner): every file or video of theirs that asks first is the receiving card.
    if (f.state == "asking" && !f.mine) { IncomingFileCard(f, who, showChat); return }
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
                    MButton(if (f.state == "asking" && f.vid == 0) "Accept" else "Download", kind = Kind.Accent) { link.accept(f.id) }
                    if (f.canAlways && f.vid == 0) MButton("Always accept from $who") { link.acceptAlways(f.id) }
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
                        if (!Received.showFolder(context)) Ui.say("It's in ${f.folder.ifEmpty { Received.shownRoot() }}. Open it from your Files app.")
                    }
                    MButton("Close") { link.dismiss(f.id) }
                }
                failed && link.canRetry(f.id) -> {
                    // Too big to go without Direct: one tap turns it on here and asks them to do the same.
                    if (link.needsDirect(f.id) && link.host?.isContact(f.peer) == true) {
                        val on = io.github.ravenor907.muninn.net.call.CallManager.trusted(f.peer)
                        MButton(if (on) "Ask $who" else "Turn on Trusted direct", kind = Kind.Accent) { allowDirect(context, f.peer, who) }
                    }
                    MButton("Retry", kind = Kind.Accent, icon = "refresh") {
                        val g = link.retryGroup(f.id)
                        link.takeRetry(f.id)?.let { (p, file, n) -> resend(p, file, n, g) }
                    }
                    MButton("Close") { link.dismiss(f.id) }
                }
                // What came of their file is kept (it goes on from there): Close keeps it, Discard asks, then deletes it.
                failed && link.isKept(f.id) -> {
                    MButton("Close") { link.dismiss(f.id) }
                    MButton("Discard", kind = Kind.Danger) {
                        val pct = FileText.percent(link.keptBytes(f.id), f.size)
                        Ui.sure(io.github.ravenor907.muninn.ui.Ask("Throw away the $pct% that came?",
                            "The $pct% of ${f.name} that came (${FileText.size(link.keptBytes(f.id))}) is deleted. It starts over if $who sends it again. " +
                                "Close keeps it for ${FileLink.KEEP_DAYS} days instead.", "Discard", danger = true) { link.discard(f.id) })
                    }
                }
                !FileText.running(f.state) -> MButton("Close") { link.dismiss(f.id) }
            }
        }
    }
}

/**
 * A file too big to go without Direct: Trusted direct with them on here (it shares this phone's internet address
 * with them), and a message asks them to turn it on too. Retry once they have.
 */
internal fun allowDirect(context: Context, peer: String, who: String) {
    val app = context.applicationContext as? Muninn ?: return
    // Ultra privacy: nothing goes straight over the internet while it's on (the same Wi-Fi still does).
    if (!io.github.ravenor907.muninn.net.UltraPrivacy.directAllowed) {
        Ui.say("Direct files: ${io.github.ravenor907.muninn.net.UltraPrivacy.OFF_NOTE}. On the same Wi-Fi they still go straight.", true)
        return
    }
    val ask = {
        if (!io.github.ravenor907.muninn.net.call.CallManager.trusted(peer)) io.github.ravenor907.muninn.net.call.CallManager.setTrusted(peer, true)
        app.chat.sendPrivate(peer, "Can you turn on Trusted direct for me? It's in Details for my name, under Safety. Then big files go straight between us.")
        Ui.say("Direct is on here, and $who was asked to turn it on too. Retry once they have.")
    }
    if (io.github.ravenor907.muninn.net.call.CallManager.trusted(peer)) { ask(); return }
    Ui.sure(io.github.ravenor907.muninn.ui.Ask("Turn on Trusted direct with $who?",
        "Big files go straight between your devices. Both of you turn this on. It shares your internet address with $who.", "Turn on and ask") { ask() })
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
        // Cap f4: a file over 1 GB that will most likely go over Tor asks first.
        val slow = if (!where.startsWith("g:") && size > 0) FileLink.torWarning(where, size) else null
        if (slow != null) {
            Ui.sure(io.github.ravenor907.muninn.ui.Ask("Send over Tor?", slow, "Send") { Thread { copyAndSend(app, context, where, uri, clean, size) }.start() })
            then()
            return@launch
        }
        copyAndSend(app, context, where, uri, clean, size)
        then()
    }
}

/** sendFileFrom's second half: the file copied in, then sent. */
private fun copyAndSend(app: Muninn, context: Context, where: String, uri: Uri, clean: String, size: Long) {
    run {
        val tmp = File(File(context.cacheDir, "files").also { it.mkdirs() }, "out-" + System.nanoTime())
        val ok = try { context.contentResolver.openInputStream(uri)?.use { ins -> tmp.outputStream().use { ins.copyTo(it) } } != null } catch (e: Exception) { false }
        if (!ok) { tmp.delete(); Ui.say("That file couldn't be opened.", true) }
        else if (where.startsWith("g:")) app.chat.sendGroupFile(where.removePrefix("g:").substringBefore('/'), tmp, clean)?.let { Ui.say(it, true) }
        else {
            val card = FileLink.preparing(where, clean, size)
            app.chat.sendFile(where, tmp, clean, card)?.let { Ui.say(it, true) }
        }
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
                    Text(listOf(LocalSend.size(nearby.total), nearby.status, "Same Wi-Fi", nearby.pace()).filter { it.isNotEmpty() }.joinToString(" · "),
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

/** A chat's Files as a page of its own, with Back to the chat. The menu opens them as a tab of Media and files. */
@Composable
internal fun ChatFilesScreen(app: Muninn, where: String) {
    val groups by app.chat.groups.all.collectAsState()
    val g = if (where.startsWith("g:")) where.removePrefix("g:") else ""
    val title = (if (g.isEmpty()) null else groups[g])?.state?.name ?: app.chat.nameOf(where)
    Column(Modifier.fillMaxSize()) {
        PageBar("Files · $title", "Back to $title") { ChatNav.open = where }
        ChatFilesList(app, where, Modifier.fillMaxSize())
    }
}

/**
 * A chat's Files (the Files tab of Media and files): everything sent and received there, newest first, with Open,
 * Show in chat, Forward, Folder and Delete (a received file goes from Downloads too; a sent one only leaves the list).
 * A tap opens a received file, a long press lists the same actions. A group's list starts with who can send files and
 * the size limit, which admins change. [where]: their key or "g:<group>". [inChat]: Show in chat for a file that
 * went as a message (null: it has no message).
 */
@Composable
internal fun ChatFilesList(app: Muninn, where: String, modifier: Modifier, inChat: (Chat.SharedFile) -> (() -> Unit)? = { null }) {
    val context = LocalContext.current
    val files by app.chat.sharedFiles.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    val g = if (where.startsWith("g:")) where.removePrefix("g:") else ""
    val grp = if (g.isEmpty()) null else groups[g]
    val title = grp?.state?.name ?: app.chat.nameOf(where)
    var rules by remember { mutableStateOf(false) }
    // Typing in the box shows only the files whose name has it.
    var filter by remember(where) { mutableStateOf("") }
    val all = remember(files, where) { app.chat.filesIn(where) }
    val shown = remember(all, filter) { filter.trim().let { q -> if (q.isEmpty()) all else all.filter { it.name.contains(q, ignoreCase = true) } } }
    LazyColumn(modifier.padding(horizontal = 12.dp)) {
        item {
            Text("Received files are saved in ${Received.shown(Sharing.folderFor("Muninn", title))}.", style = Type.small, modifier = Modifier.padding(top = 10.dp))
            if (grp != null) {
                val admin = !grp.readOnly && grp.state.isAdmin(app.identity.publicKey)
                Panel(Modifier.padding(top = 10.dp)) {
                    Text("Who can send files", style = Type.big)
                    Text(Sharing.rulesLine(grp.state.fs, grp.state.fm), style = Type.normal, modifier = Modifier.padding(top = 4.dp))
                    Text("Every member's app follows this.", style = Type.small, modifier = Modifier.padding(top = 2.dp))
                    if (admin) MButton("Change", Modifier.padding(top = 8.dp), icon = "edit") { rules = true }
                }
            }
            if (all.isNotEmpty()) Field(filter, { filter = it }, "Find a file by name", Modifier.padding(top = 10.dp))
            if (shown.isEmpty()) Text(if (all.isEmpty()) "No files here yet. Send one with the clip beside the message box." else "No file names have that.",
                style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 16.dp))
        }
        items(shown, key = { it.time.toString() + it.name + it.uri }) { f -> SharedFileRow(app, context, f, inChat(f)) }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (rules && grp != null) FileRulesDialog(app, g, grp.state.fs, grp.state.fm) { rules = false }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SharedFileRow(app: Muninn, context: Context, f: Chat.SharedFile, show: (() -> Unit)?) {
    val there = remember(f.uri) { f.mine || Received.exists(context, f.uri) }
    val day = remember(f.time) { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(f.time)) }
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf(false) }
    var forwarding by remember { mutableStateOf(false) }
    val kids = io.github.ravenor907.muninn.tv.KidMode.saved(context)
    val canOpen = !f.mine && there && !(kids && FileText.isApp(f.name))
    val canForward = !f.mine && there && f.uri.isNotEmpty()
    val open: () -> Unit = { if (!Received.open(context, f.uri, f.name)) Ui.say("Nothing on this device opens this kind of file.", true) }
    val folder: () -> Unit = { if (!Received.showFolder(context)) Ui.say("It's in ${Received.shownRoot()}. Open it from your Files app.") }
    val delete: () -> Unit = {
        if (f.mine) { app.chat.deleteFile(f); Ui.say("Removed from the list. The file itself stays where it was.") }
        else Ui.sure(io.github.ravenor907.muninn.ui.Ask("Delete ${f.name}?", if (there) "It's deleted from this phone." else "It's already gone; this takes it off the list.",
            "Delete", danger = true, hold = there, spoken = io.github.ravenor907.muninn.ui.holdSpoken("delete ${f.name}"),
            onConfirm = { if (app.chat.deleteFile(f)) Ui.say("Deleted.") else Ui.say("It couldn't be deleted.", true) }))
    }
    if (forwarding) ChatPicker(app, "Forward ${f.name} to", onPick = { to, name ->
        forwarding = false
        sendFileFrom(app, context, scope, to, Uri.parse(f.uri))
        Ui.say("Sending to $name. Transfers shows how it goes.")
    }, onCancel = { forwarding = false })
    if (options) FileOptions(f, canOpen, show, canForward, there && !f.mine, { options = false },
        onOpen = open, onForward = { forwarding = true }, onFolder = folder, onDelete = delete)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Corner).background(Palette.panel)
        .combinedClickable(onClickLabel = if (canOpen) "Open" else null, onLongClickLabel = "Options", onLongClick = { options = true },
            onClick = { if (canOpen) open() else { options = true } })
        .padding(10.dp)) {
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
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canOpen) MButton("Open", kind = Kind.Accent) { open() }
            if (show != null) MButton("Show in chat", icon = "chat") { show() }
            if (canForward) MButton("Forward", icon = "send") { forwarding = true }
            if (!f.mine && there) MButton("Folder", icon = "folder") { folder() }
            MButton(if (f.mine) "Remove" else "Delete", kind = Kind.Danger) { delete() }
        }
    }
}

/** A file's long-press list: the same actions as its buttons. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FileOptions(f: Chat.SharedFile, canOpen: Boolean, show: (() -> Unit)?, canForward: Boolean, canFolder: Boolean, done: () -> Unit,
                        onOpen: () -> Unit, onForward: () -> Unit, onFolder: () -> Unit, onDelete: () -> Unit) {
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = done, sheetState = sheet, containerColor = Palette.panel, shape = Corner, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp).navigationBarsPadding()) {
            Text(f.name, style = Type.big, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            if (canOpen) io.github.ravenor907.muninn.ui.MenuRow("open", "Open") { done(); onOpen() }
            if (show != null) io.github.ravenor907.muninn.ui.MenuRow("chat", "Show in chat") { done(); show() }
            if (canForward) io.github.ravenor907.muninn.ui.MenuRow("send", "Forward") { done(); onForward() }
            if (canFolder) io.github.ravenor907.muninn.ui.MenuRow("folder", "Folder") { done(); onFolder() }
            io.github.ravenor907.muninn.ui.MenuRow("delete", if (f.mine) "Remove" else "Delete", danger = true) { done(); onDelete() }
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
    /** Round 17: text or a link shared in (no files): it goes into the picked chat's message box, for the person to send. */
    var text by androidx.compose.runtime.mutableStateOf("")
}

/**
 * The share sheet's "Send to": people, groups, and LocalSend devices nearby ("Nearby: LocalSend · <device>"). A person
 * or a group gets the files like the clip in their chat (and their chat opens); a device nearby gets them over LocalSend.
 */
@Composable
internal fun ShareToDialog(app: Muninn, openChat: (String) -> Unit) {
    if (ShareTo.pending.isEmpty()) { ShareTextDialog(app, openChat); return }
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

/** Round 17: text or a link shared in: pick a person or a group, and it waits in that chat's message box to be sent. */
@Composable
private fun ShareTextDialog(app: Muninn, openChat: (String) -> Unit) {
    val shared = ShareTo.text
    if (shared.isEmpty()) return
    val people by app.chat.contacts.all.collectAsState()
    val groups by app.chat.groups.all.collectAsState()
    fun toChat(where: String) {
        ShareTo.text = ""
        if (!io.github.ravenor907.muninn.net.Drafts.addShared(app, where, shared)) { Ui.say("Unlock Muninn first, then share it again.", true); return }
        openChat(where)
    }
    AlertDialog(
        onDismissRequest = { ShareTo.text = "" },
        containerColor = Palette.panel,
        title = { Text("Send to", style = Type.big) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(shared.take(200), style = Type.small, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp))
                val contacts = people.filter { !it.groupOnly }.sortedBy { app.chat.nameOf(it.chatKey).lowercase() }
                if (contacts.isNotEmpty()) Text("PEOPLE", style = Type.small, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                contacts.forEach { c -> Line(app.chat.nameOf(c.chatKey), icon = "user") { toChat(c.chatKey) } }
                val live = groups.values.filter { !it.readOnly }
                if (live.isNotEmpty()) Text("GROUPS", style = Type.small, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                live.forEach { gr -> Line(gr.state.name, icon = "people") { toChat(io.github.ravenor907.muninn.net.Folders.groupKey(gr.state.g)) } }
                if (contacts.isEmpty() && live.isEmpty()) Text("No chats yet. Add someone first.", style = Type.small)
            }
        },
        confirmButton = { MButton("Cancel") { ShareTo.text = "" } },
    )
}
