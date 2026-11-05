package io.github.ravenor907.muninn.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ravenor907.muninn.net.AppStorage
import io.github.ravenor907.muninn.net.FileText
import io.github.ravenor907.muninn.net.Received
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** App storage on this device: [AppStorage]'s allowlist over Muninn's own folders. Call off the main thread. */
object AppStorageOps {
    fun roots(c: Context): Map<Char, File> = buildMap {
        put('c', c.cacheDir)
        put('f', c.filesDir)
        (try { c.getExternalFilesDir(null) } catch (e: Exception) { null })?.let { put('e', it) }
    }

    fun scan(c: Context): List<AppStorage.Item> = AppStorage.scan(roots(c), System.currentTimeMillis())

    /** Move to Downloads lands in the real Downloads (not Muninn's own folder on Android 8 and 9 without the permission). */
    fun canMove(c: Context): Boolean = !Received.needsPermission(c)

    fun delete(c: Context, id: String): Boolean = AppStorage.delete(roots(c), id)

    fun deleteKind(c: Context, kind: AppStorage.Kind): Int = AppStorage.deleteKind(roots(c), kind, System.currentTimeMillis())

    /** A received file into Downloads, then gone from here: the name it got there, or null. */
    fun move(c: Context, id: String): String? {
        if (AppStorage.spotOf(id)?.kind != AppStorage.Kind.RECEIVED || !canMove(c)) return null
        val roots = roots(c)
        val f = AppStorage.resolve(roots, id) ?: return null
        return try {
            val t = Received.create(c, f.name)
            try { f.inputStream().use { it.copyTo(t.out) }; t.finish() } catch (e: Exception) { t.discard(); return null }
            AppStorage.delete(roots, id)
            t.name
        } catch (e: Exception) { null }
    }
}

/** App storage: what Muninn keeps in its own folders that can go. Opened from Settings › Devices and backup › More. */
@Composable
internal fun AppStorageScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<AppStorage.Item>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val canMove = remember { AppStorageOps.canMove(context) }
    fun act(work: () -> String?) {
        busy = true
        scope.launch {
            val said = withContext(Dispatchers.IO) { try { work() } catch (e: Exception) { null } }
            items = withContext(Dispatchers.IO) { AppStorageOps.scan(context) }
            busy = false
            said?.let { Ui.say(it) }
        }
    }
    LaunchedEffect(Unit) { items = withContext(Dispatchers.IO) { AppStorageOps.scan(context) } }
    Column(Modifier.fillMaxSize()) {
        PageBar("App storage")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            StorageList(items, busy, canMove,
                onDelete = { i -> act { if (AppStorageOps.delete(context, i.id)) "Deleted" else "Couldn't delete" } },
                onMove = { i -> act { AppStorageOps.move(context, i.id)?.let { "Moved to Downloads" } ?: "Couldn't move" } },
                onDeleteAll = { k -> act { val n = AppStorageOps.deleteKind(context, k); if (k == AppStorage.Kind.CACHE) "Caches cleared" else "$n deleted" } })
        }
    }
}

/**
 * The list App storage shows, here and for a Kids' TV on a parent's phone: the total, Delete all received files and
 * Clear caches (each asks first), then each file with Delete and, when received, Move to Downloads. [items] null: still
 * reading. [count] and [total]: all of them when [items] is only the newest few (a Kids' TV sends at most 40).
 */
@Composable
internal fun StorageList(
    items: List<AppStorage.Item>?, busy: Boolean, canMove: Boolean,
    count: Int = items?.size ?: 0, total: Long = items?.let { AppStorage.total(it) } ?: 0L,
    onDelete: (AppStorage.Item) -> Unit, onMove: (AppStorage.Item) -> Unit, onDeleteAll: (AppStorage.Kind) -> Unit,
) {
    if (items == null) { Text("Reading…", style = Type.normal.copy(color = Palette.textMuted)); return }
    val received = items.filter { it.kind == AppStorage.Kind.RECEIVED }
    val caches = items.filter { it.kind == AppStorage.Kind.CACHE }
    Text("Total ${FileText.size(total)}", style = Type.big)
    Text(if (count == 1) "1 file" else "$count files", style = Type.small)
    Spacer(Modifier.height(10.dp))
    MButton("Delete all received files", Modifier.fillMaxWidth(), Kind.Danger, enabled = !busy && received.isNotEmpty()) {
        Ui.sure(Ask("Delete all received files?", "Only files kept inside Muninn. Downloads stays as it is.", "Delete", danger = true, hold = true,
            spoken = "Hold for 5 seconds to delete all received files") {
            onDeleteAll(AppStorage.Kind.RECEIVED)
        })
    }
    Spacer(Modifier.height(8.dp))
    MButton("Clear caches", Modifier.fillMaxWidth(), enabled = !busy && caches.isNotEmpty()) {
        Ui.sure(Ask("Clear caches?", "Muninn makes them again when needed.", "Clear") { onDeleteAll(AppStorage.Kind.CACHE) })
    }
    if (items.isEmpty()) {
        Text("Nothing to clear.", style = Type.normal.copy(color = Palette.textMuted), modifier = Modifier.padding(top = 16.dp))
        return
    }
    val shown = items.take(SHOWN)
    for (i in shown) StorageRow(i, busy, canMove, onDelete, onMove)
    if (count > shown.size) Text("Showing the newest ${shown.size}.", style = Type.small, modifier = Modifier.padding(top = 10.dp))
    Spacer(Modifier.height(24.dp))
}

private const val SHOWN = 200

@Composable
private fun StorageRow(i: AppStorage.Item, busy: Boolean, canMove: Boolean, onDelete: (AppStorage.Item) -> Unit, onMove: (AppStorage.Item) -> Unit) {
    val date = remember(i.at) { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(i.at)) }
    Panel(Modifier.padding(top = 10.dp)) {
        Text(i.name, style = Type.normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(i.kind.word, FileText.size(i.size), date, i.folder).joinToString(" · "), style = Type.small, maxLines = 2)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (i.kind == AppStorage.Kind.RECEIVED && canMove) MButton("Move to Downloads", Modifier.weight(1f), enabled = !busy) { onMove(i) }
            MButton("Delete", Modifier.weight(1f), Kind.Danger, enabled = !busy) {
                Ui.sure(Ask("Delete ${i.name}?", "Can't be undone.", "Delete", danger = true, hold = true, spoken = "Hold for 5 seconds to delete ${i.name}") { onDelete(i) })
            }
        }
    }
}
