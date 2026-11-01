package io.github.ravenor907.muninn.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import io.github.ravenor907.muninn.ui.Corner
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.MessageService
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Panel
import io.github.ravenor907.muninn.ui.Type
import kotlinx.coroutines.delay

/** One thing Android hasn't let Muninn do yet. [id] also names it in the "not now" list. */
private class Missing(val id: String, val title: String, val detail: String, val permission: String?)

private const val HIDDEN = "setupHidden"

/** What's still missing on this phone, leaving out what it has no hardware for and what was put off with "not now". */
private fun missing(ctx: Context, app: Muninn): List<Missing> {
    val pm = ctx.packageManager
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val hidden = ctx.getSharedPreferences("muninn", Context.MODE_PRIVATE).getStringSet(HIDDEN, null).orEmpty()
    return buildList {
        if (pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) && !granted(Manifest.permission.RECORD_AUDIO))
            add(Missing("mic", "Microphone", "For calls and voice messages.", Manifest.permission.RECORD_AUDIO))
        if (pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) && !granted(Manifest.permission.CAMERA))
            add(Missing("camera", "Camera", "For video calls.", Manifest.permission.CAMERA))
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled())
            add(Missing("notify", "Notifications", "So you see new messages.",
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null))
        // Android 14 and newer: a call only fills the screen (also over the lock screen) with this allowed.
        if (fullScreenOff(ctx))
            add(Missing(FULL_SCREEN, "Full-screen calls", "Calls show over the lock screen.", null))
        // Messages only arrive right away if Android lets Muninn run in the background (unrestricted battery use).
        if (app.identity.stayConnected && !MessageService.unrestricted(ctx))
            add(Missing("battery", "Battery setup", "So messages arrive on time.", null))
    }.filter { it.id !in hidden }
}

/** Android 14 and newer only: whether incoming calls are kept from filling the screen. */
fun fullScreenOff(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 34 &&
    try { ctx.getSystemService(android.app.NotificationManager::class.java)?.canUseFullScreenIntent() == false } catch (_: Exception) { false }

/** Android's "Full-screen notifications" switch for Muninn (Android 14 and newer), else Muninn's notification settings. */
fun openFullScreenSetting(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 34) try {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return
    } catch (_: Exception) {}
    openAppSettings(ctx, true)
}

private const val FULL_SCREEN = "fullscreen"

/** Muninn's page in Android's settings: where a permission Android won't ask for again is turned on. */
private fun openAppSettings(ctx: Context, notifications: Boolean) {
    val i = if (notifications) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
    }
}

/** One thing on the chat list's "to finish" sheet: what it is, a short line, and its one button. */
private class Todo(val id: String, val title: String, val detail: String, val button: String, val run: () -> Unit)

private const val FINISH_HIDDEN = "finishHidden"

/**
 * "1 thing to finish" on the chat list: the permissions Muninn still needs, and close-by chat, as one row that opens a small sheet. The row can be put away;
 * it comes back when something new is added. Never on a Kids' TV (a parent sets that up from the TV's own checklist).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FinishRow(app: Muninn, onAllowNearby: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    if (io.github.ravenor907.muninn.tv.KidMode.on(app)) return
    val prefs = remember { ctx.getSharedPreferences("muninn", Context.MODE_PRIVATE) }
    var tick by remember { mutableIntStateOf(0) }
    var list by remember { mutableStateOf(missing(ctx, app)) }
    // Android's own screens change these behind our back: look again every little while and after each answer.
    io.github.ravenor907.muninn.ui.EveryWhileShown(1500, tick) { list = missing(ctx, app) }
    var askedAt by remember { mutableLongStateOf(0L) }
    var asking by remember { mutableStateOf<Missing?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val m = asking; asking = null
        // An answer straight away means Android didn't show its question (refused for good): its settings page instead.
        if (!ok && m != null && System.currentTimeMillis() - askedAt < 500) openAppSettings(ctx, m.id == "notify")
        tick++
    }
    val closeError by app.ble.error.collectAsState()
    // Said once, the first time the chat list opens (new phones and upgraded ones alike).
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean(KEEP_NOTICE, false)) { prefs.edit().putBoolean(KEEP_NOTICE, true).remove("limitsNote").apply(); io.github.ravenor907.muninn.ui.Ui.sure(keepNotice()) }
    }
    var hidden by remember { mutableStateOf(prefs.getStringSet(FINISH_HIDDEN, null).orEmpty()) }
    var open by remember { mutableStateOf(false) }
    val todos = buildList {
        if (closeError == io.github.ravenor907.muninn.net.Ble.PERMISSION_TEXT) add(Todo("close", "Chat close by", "Works without the internet.", "Allow") { onAllowNearby() })
        list.forEach { m ->
            add(Todo(m.id, m.title, m.detail, "Allow") {
                when {
                    m.id == "battery" -> MessageService.askUnrestricted(ctx)
                    m.id == FULL_SCREEN -> openFullScreenSetting(ctx)
                    m.permission != null -> { asking = m; askedAt = System.currentTimeMillis(); ask.launch(m.permission) }
                    else -> openAppSettings(ctx, m.id == "notify")
                }
            })
        }
    }
    val shown = todos.filter { it.id !in hidden }
    if (shown.isEmpty()) return
    Row(modifier.fillMaxWidth().clip(Corner).background(Palette.panel).border(1.dp, Palette.line, Corner)
        .clickable(role = Role.Button, onClickLabel = "Show") { open = true }.padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.gold), contentAlignment = Alignment.Center) {
            Text("${shown.size}", style = Type.small.copy(color = Palette.onGold, fontWeight = FontWeight.SemiBold))
        }
        Text(if (shown.size == 1) "1 thing to finish" else "${shown.size} things to finish", style = Type.normal.copy(color = Palette.text),
            modifier = Modifier.weight(1f).padding(start = 12.dp))
        IconAction("close", "Hide", Palette.textMuted, size = 18.dp) {
            hidden = hidden + shown.map { it.id }
            prefs.edit().putStringSet(FINISH_HIDDEN, hidden).apply()
        }
    }
    if (open) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { open = false }, sheetState = sheet, containerColor = Palette.panel, shape = Corner, dragHandle = null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding()) {
                Text("To finish", style = Type.big, modifier = Modifier.padding(bottom = 4.dp))
                shown.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = Type.normal.copy(color = Palette.text))
                            if (t.detail.isNotEmpty()) Text(t.detail, style = Type.small)
                        }
                        MButton(t.button, Modifier.padding(start = 8.dp), Kind.Accent) { t.run() }
                    }
                }
            }
        }
    }
}
