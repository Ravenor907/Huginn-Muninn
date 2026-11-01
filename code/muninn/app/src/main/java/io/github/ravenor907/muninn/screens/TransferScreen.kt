package io.github.ravenor907.muninn.screens

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.AppFiles
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Received
import io.github.ravenor907.muninn.ui.Bar
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.hcBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Files waiting to be sent: picked in the Files tab, or shared into Muninn from another app ("Share" › Muninn). */
object Share {
    val pending = androidx.compose.runtime.mutableStateListOf<File>()

    /**
     * Copies what the system picker or another app handed over into Muninn's cache, so it can be sent
     * (Muninn sees no other files on the phone). [busy]: a transfer is running, so older copies stay.
     */
    fun copyIn(context: Context, uris: List<Uri>, busy: Boolean): List<File> {
        val root = File(context.cacheDir, "outgoing")
        if (!busy && pending.isEmpty()) root.deleteRecursively()
        val dir = File(root, System.currentTimeMillis().toString()).also { it.mkdirs() }
        return uris.filter { handedOver(context, it) }.mapNotNull { u ->
            try {
                var name = "shared"
                context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
                val f = Received.free(dir, name.replace('/', '_').replace('\\', '_').ifBlank { "shared" })
                context.contentResolver.openInputStream(u)?.use { ins -> f.outputStream().use { ins.copyTo(it) } } ?: return@mapNotNull null
                f
            } catch (e: Exception) { null }
        }
    }

    /**
     * Only content another app hands over. A file:// path would be opened with Muninn's own rights, so any
     * app could get Muninn to copy its private files out; links into Muninn's own provider are refused too.
     */
    fun handedOver(context: Context, u: Uri): Boolean =
        u.scheme.equals("content", ignoreCase = true) && u.authority?.startsWith(context.packageName) != true
}

private val labelStyle get() = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.5.sp, color = Palette.textMuted)
private val noteStyle get() = TextStyle(fontFamily = Type.body, fontSize = 13.sp, color = Palette.textMuted)

@Composable
private fun Label(text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = labelStyle, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** Someone files can go to: a phone close by (Muninn over Bluetooth) or a LocalSend device on the Wi-Fi. */
private class Target(val key: String, val name: String, val icon: String, val detail: String, val send: (List<File>) -> Unit)

/**
 * The Files tab: one big Send files button (the system's own file picker), everyone close by to send
 * to, and recent transfers with their progress. Received files go to Downloads.
 */
@Composable
fun TransferScreen(app: Muninn, askDirectPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devices by app.net.devices.collectAsState()
    val transfer by app.net.transfer.collectAsState()
    val items by app.net.items.collectAsState()
    val scanning by app.net.scanning.collectAsState()
    val error by app.net.error.collectAsState()
    val direct by app.direct.state.collectAsState()
    var showDirect by remember { mutableStateOf(false) }
    var copying by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    /** Tapped a device before picking anything: send to it as soon as files are picked. */
    var after by remember { mutableStateOf<Target?>(null) }

    fun deliver(t: Target, files: List<File>) {
        if (files.isEmpty()) return
        if (app.net.transfer.value.active) { Ui.say("Wait for the current transfer.", true); return }
        t.send(files)
        Share.pending.clear()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val then = after
        after = null
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        copying = true
        scope.launch {
            val files = withContext(Dispatchers.IO) { Share.copyIn(context, uris, app.net.transfer.value.active) }
            copying = false
            if (files.size < uris.size) Ui.say("${uris.size - files.size} files couldn't be read.", true)
            Share.pending.addAll(files)
            if (then != null) deliver(then, Share.pending.toList())
        }
    }
    fun pick() { try { picker.launch(arrayOf("*/*")) } catch (e: Exception) { Ui.say("This phone has no file picker.", true) } }

    val targets = devices.map { d ->
        val icon = when (d.type) { "mobile" -> "phone"; "server", "headless" -> "server"; else -> "network" }
        Target(d.id, d.alias, icon, d.app?.replaceFirstChar { it.uppercase() } ?: "LocalSend") { files -> app.net.send(d.id, files) }
    }

    // An app on this phone goes out as its APK (and its parts), to a TV with Muninn or any LocalSend device.
    if (pickingApp) AppPicker(onCancel = { pickingApp = false }) { a ->
        pickingApp = false
        copying = true
        scope.launch {
            val files = withContext(Dispatchers.IO) { AppFiles.copyOut(context, a.pkg) }
            copying = false
            if (files.isEmpty()) Ui.say("${a.label} couldn't be read.", true) else Share.pending.addAll(files)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            // The one big action, in gold like the chat list's new chat button.
            val shape = io.github.ravenor907.muninn.ui.Corner
            Row(Modifier.padding(top = 6.dp).fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(Palette.gold)
                .clickable(role = Role.Button, enabled = !copying) { after = null; pick() }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                MIcon("upload", Palette.onGold, 22.dp, weight = 2f)
                Spacer(Modifier.width(10.dp))
                Text(when { copying -> "Getting files ready…"; Share.pending.isEmpty() -> "Send files"; else -> "Add more files" },
                    style = TextStyle(fontFamily = Type.body, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Palette.onGold))
            }
            MButton("Send an app", Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !copying) { pickingApp = true }
            if (Share.pending.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (Share.pending.size == 1) Share.pending[0].name else "${Share.pending.size} files"} · ${LocalSend.size(Share.pending.sumOf { it.length() })}. Tap who gets it.",
                    style = Type.normal.copy(color = Palette.text), modifier = Modifier.weight(1f))
                MButton("Clear") { Share.pending.clear() }
            }
            if (transfer.active) Panel(Modifier.padding(top = 12.dp)) {
                Text(transfer.status, style = Type.big)
                Spacer(Modifier.height(8.dp))
                Bar(transfer.fraction, color = Palette.gold)
                Text("${LocalSend.size(transfer.done)} of ${LocalSend.size(transfer.total)}", style = Type.small, modifier = Modifier.padding(top = 6.dp))
                if (transfer.sending) MButton("Stop sending", Modifier.padding(top = 8.dp)) { app.net.cancelSend() }
            }
            Label("Nearby") {
                Text(if (scanning) "Looking…" else "Look again", style = noteStyle.copy(color = if (scanning) Palette.textFaint else Palette.gold),
                    modifier = Modifier.clip(io.github.ravenor907.muninn.ui.Corner).clickable(role = Role.Button, enabled = !scanning) { app.net.refresh() }
                        .padding(horizontal = 8.dp, vertical = 10.dp))
            }
            if (error != null) Text(error!!, style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(bottom = 8.dp))
            if (targets.isEmpty() && error == null) Text(
                "Nobody nearby yet. You're “${app.identity.deviceName}”.",
                style = Type.normal,
            )
        }
        // Three to a row.
        items(targets.chunked(3), key = { row -> row.joinToString("|") { it.key } }) { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val shape = io.github.ravenor907.muninn.ui.Corner
                    Column(Modifier.weight(1f).clip(shape).background(Palette.field).hcBorder(shape)
                        .clickable(role = Role.Button, onClickLabel = "Send to ${t.name}") {
                            if (Share.pending.isEmpty()) { after = t; pick() } else deliver(t, Share.pending.toList())
                        }.padding(vertical = 14.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        MIcon(t.icon, Palette.accent, 34.dp, weight = 1.5f)
                        Text(t.name, style = Type.small.copy(fontSize = 13.sp, color = Palette.text), textAlign = TextAlign.Center,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        Text(t.detail, style = noteStyle.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item {
            Text("Nearby only. Saved to ${Received.place(context)}.",
                style = noteStyle)
            if (!showDirect && !direct.on) MButton("No shared Wi-Fi?", Modifier.fillMaxWidth().padding(top = 12.dp), icon = "wifi") { showDirect = true }
            if (showDirect || direct.on) Label("Direct connect")
            if (showDirect || direct.on) Panel {
                if (!direct.on) {
                    Text("A private hotspot. No data used.", style = Type.normal)
                    if (direct.error != null) Text(direct.error!!, style = Type.normal.copy(color = Palette.danger), modifier = Modifier.padding(top = 6.dp))
                    MButton(if (direct.starting) "Starting…" else "Start Direct connect", Modifier.fillMaxWidth().padding(top = 10.dp), enabled = !direct.starting, icon = "wifi") { askDirectPermission() }
                    Text("Computers: Huginn › Files › Direct connect.", style = Type.small, modifier = Modifier.padding(top = 8.dp))
                } else {
                    Text("Scan with the other phone's camera", style = Type.big)
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Qr(direct.qr, 220.dp) }
                    Text("Wi-Fi: ${direct.ssid}", style = Type.normal)
                    Text("Password: ${direct.password}", style = Type.normal)
                    MButton("Stop Direct connect", Modifier.fillMaxWidth().padding(top = 10.dp)) { app.direct.stop() }
                }
            }
            Label("Recent")
            if (items.isEmpty()) Text("No files yet.", style = Type.normal)
        }
        items(items) { it ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(io.github.ravenor907.muninn.ui.Corner).background(Palette.coolTint), contentAlignment = Alignment.Center) {
                    MIcon(if (it.sent) "upload" else "download", if (it.failed) Palette.danger else Palette.accent, 20.dp)
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(it.name, style = Type.normal.copy(color = Palette.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val moving = !it.failed && it.fraction > 0f && it.fraction < 1f
                    if (moving) Bar(it.fraction, Modifier.padding(top = 8.dp), Palette.gold)
                    Text(it.status, style = noteStyle.copy(color = if (it.failed) Palette.danger else Palette.textMuted),
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (moving) 4.dp else 2.dp))
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun Qr(text: String, size: Dp) {
    val bmp = remember(text) {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
        Bitmap.createBitmap(m.width, m.height, Bitmap.Config.RGB_565).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) 0xFF101112.toInt() else 0xFFE4E1DA.toInt())
        }
    }
    // A light quiet zone around the code in every look (scanners need it), and never wider than the space it's in.
    androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
        val side = minOf(size, maxWidth - 20.dp).coerceAtLeast(120.dp)
        Box(Modifier.size(side + 20.dp).background(androidx.compose.ui.graphics.Color(0xFFE4E1DA)), contentAlignment = Alignment.Center) {
            Image(bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.size(side))
        }
    }
}
