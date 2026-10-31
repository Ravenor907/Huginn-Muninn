package io.github.ravenor907.muninn

import androidx.compose.ui.text.style.TextOverflow
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.net.LocalSend
import io.github.ravenor907.muninn.net.Received
import io.github.ravenor907.muninn.screens.ChatColumn
import io.github.ravenor907.muninn.screens.ChannelColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.ravenor907.muninn.screens.ChatMain
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import android.view.WindowManager
import io.github.ravenor907.muninn.screens.ChatNav
import io.github.ravenor907.muninn.screens.ChatScreen
import io.github.ravenor907.muninn.screens.ContactsScreen
import io.github.ravenor907.muninn.screens.LockScreen
import io.github.ravenor907.muninn.screens.FirstLock
import io.github.ravenor907.muninn.screens.lockChosen
import androidx.compose.runtime.remember
import io.github.ravenor907.muninn.screens.SettingsScreen
import io.github.ravenor907.muninn.screens.SettingsNav
import io.github.ravenor907.muninn.screens.Share
import io.github.ravenor907.muninn.screens.TransferScreen
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.AskDialog
import io.github.ravenor907.muninn.ui.Brand
import io.github.ravenor907.muninn.ui.IconAction
import io.github.ravenor907.muninn.ui.Kind
import io.github.ravenor907.muninn.ui.MButton
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Palette
import io.github.ravenor907.muninn.ui.Type
import io.github.ravenor907.muninn.ui.Access
import io.github.ravenor907.muninn.ui.Ui
import io.github.ravenor907.muninn.ui.bar
import androidx.compose.material3.PlainTooltip
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as Muninn
    /** The bottom tab (phones) or rail button (tablets): chat, calls, files, contacts, or settings (the gear). */
    private var tab by mutableStateOf("chat")
    /** Where the gear goes back to. */
    private var beforeSettings = "chat"
    /** Password set and Muninn was away long enough: ask for it before showing anything. */
    private var uiLocked by mutableStateOf(false)
    /** Test builds only: the emulator screenshots open screens without setting a lock. */
    private val ciSkip get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent?.getBooleanExtra("ci", false) == true
    private var netStarted = false

    private val closePermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) { app.close.start { app.chat.hello() }; app.ble.start(); return@registerForActivityResult }
        // Android stops showing the prompt after it's been refused (twice on Android 11 and up). Then the
        // only way to allow it is Muninn's app settings, so the message on Chat says to tap for that.
        val refused = granted.filterValues { !it }.keys
        closeBlocked = refused.isNotEmpty() && refused.none { shouldShowRequestPermissionRationale(it) }
        if (closeBlocked) app.close.blocked() else app.close.denied()
    }
    private var closeBlocked = false
    private val autoAsked = mutableSetOf<String>()

    private fun closePerms(): List<String> = when {
        Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= 31 -> listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    } + app.close.missing.value

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    /** Starts close-by chat, asking for whatever permission it still needs. Also what tapping the Chat message does. */
    fun askClose() {
        val need = closePerms().distinct().filterNot { granted(it) }
        when {
            need.isEmpty() -> { app.close.start { app.chat.hello() }; app.ble.start() }
            closeBlocked -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            else -> closePermission.launch(need.toTypedArray())
        }
    }

    private val directPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) app.direct.start()
        else Ui.say("Direct connect needs the ${if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location"} permission to make a hotspot.", true)
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    /** Android 8 and 9: saving into Downloads needs the storage permission, asked when a transfer is accepted. */
    private var afterStorage: (() -> Unit)? = null
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Refused: files still arrive, in Muninn's own Downloads folder (Received says where).
        afterStorage?.invoke(); afterStorage = null
    }

    private fun acceptFiles(accept: () -> Unit) {
        if (Received.needsPermission(this)) { afterStorage = accept; storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        else accept()
    }

    /** A lock shorter than the current minimum (vault.weak): suggest a stronger one, once. */
    private fun suggestStronger() {
        val prefs = getSharedPreferences("muninn", MODE_PRIVATE)
        if (!app.vault.weak || prefs.getBoolean("strongerAsked", false)) return
        prefs.edit().putBoolean("strongerAsked", true).apply()
        Ui.sure(Ask("Make your lock stronger?", "Your ${if (app.vault.usesPattern) "pattern" else "password"} is shorter than new ones may be. " +
            "One with at least 6 ${if (app.vault.usesPattern) "dots" else "characters"} is much harder to guess.", "Change it", cancel = "Not now") {
            show("settings"); SettingsNav.folder = "lockset"
        })
    }

    /** Opens a tab by name (also what CI and links ask for); anything unknown is the chat list. */
    private fun show(name: String) {
        val t = when (name) { "send", "files" -> "files"; "contacts", "settings" -> name; else -> "chat" }
        if (t == "settings" && tab != "settings") { beforeSettings = tab; SettingsNav.folder = "" }
        tab = t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        io.github.ravenor907.muninn.ui.Panic.watch(this, this)
        secure()
        // Decided before anything is drawn: a window opened after Muninn was away (the message service
        // keeps the app running with no window) shows the lock first, never the chats behind it.
        lockIfDue()
        recents()
        if (app.ready.value) startNet()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // ChatNav lives as long as the process, and the message service keeps the process alive after
        // Muninn's window is closed. So a fresh open would land back in whatever chat was last on screen:
        // start on the chat list unless a notification (extra "chat") or a scanned code asks otherwise.
        if (savedInstanceState == null) { ChatNav.open = ""; ChatNav.hold = ""; io.github.ravenor907.muninn.tv.TvNav.home() }
        intent?.getStringExtra("screen")?.let { show(it) }   // CI screenshots open each tab
        if (ciSkip && intent?.getStringExtra("screen") == "lock") uiLocked = true   // CI: the lock screen
        if (ciSkip) intent?.getStringExtra("folder")?.let { SettingsNav.folder = it }   // CI: a Settings page
        if (ciSkip && intent?.getBooleanExtra("hold", false) == true) ciHold = true   // CI: a person's three-dot menu
        if (ciSkip && intent?.getBooleanExtra("group", false) == true && app.ready.value) ciGroup()   // CI: a group and its settings
        if (ciSkip && intent?.getBooleanExtra("tvvoice", false) == true)   // CI: the Kids' TV voice library loads and runs
            Thread { android.util.Log.i("Muninn", "TvVoice self-check: " + io.github.ravenor907.muninn.tv.TvVoice.selfCheck()) }.start()
        if (savedInstanceState == null) { openChat(intent); openAdd(intent) }
        handleShare(intent)
        setContent { MuninnTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        // The launcher icon while Muninn is still open: the chat list, like a fresh open.
        // On a TV the launcher says LEANBACK_LAUNCHER; a Kids' TV goes back to "Who do you want to talk to?".
        if (intent.action == Intent.ACTION_MAIN && (intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) &&
            intent.getStringExtra("chat") == null) { toList(); io.github.ravenor907.muninn.tv.TvNav.home() }
        openChat(intent)
        openAdd(intent)
        intent.getStringExtra("screen")?.let { show(it) }
    }

    /** Set when an intent picked the chat to show; cleared once Muninn is back on screen (onResume). */
    private var intentChose = false

    private fun toList() { tab = "chat"; ChatNav.open = ""; ChatNav.space = ""; ChatNav.hold = "" }

    /** Test builds only: hold the first chat on the list once it's there. */
    private var ciHold = false

    /**
     * A scanned contact QR opened Muninn: go to Add someone with the code filled in. Either the add
     * page's own link (muninn://add/CODE) or the page's address itself (https://...add/#MUNINN1:CODE).
     */
    private fun openAdd(i: Intent?) {
        val d = i?.data ?: return
        val code = when {
            // A group invite link (cap j1) comes as muninn://add/MUNINNG1:CODE.
            d.scheme == "muninn" && d.host == "add" ->
                d.path?.trim('/')?.takeIf { it.isNotEmpty() }?.let {
                    if (it.startsWith(io.github.ravenor907.muninn.net.Proto06.CODE_PREFIX)) it
                    else io.github.ravenor907.muninn.net.Contacts.PREFIX + it }
            d.scheme == "https" -> io.github.ravenor907.muninn.net.Contacts.unlink(d.toString()).replace("MUNINNG1%3A", io.github.ravenor907.muninn.net.Proto06.CODE_PREFIX)
                .takeIf { it.startsWith(io.github.ravenor907.muninn.net.Contacts.PAGE.substringBefore('#')) &&
                    (it.contains(io.github.ravenor907.muninn.net.Contacts.PREFIX) || it.contains(io.github.ravenor907.muninn.net.Proto06.CODE_PREFIX)) }
            else -> null
        } ?: return
        // A move code (cap x1) is only ever taken inside the Move screen, never from a link.
        if (code.contains(io.github.ravenor907.muninn.net.MoveWire.PREFIX)) return
        ChatNav.incoming = code
        tab = "chat"; ChatNav.open = "add"; intentChose = true
    }

    /** A message notification was tapped: open that conversation. */
    private fun openChat(i: Intent?) {
        val key = i?.getStringExtra("chat") ?: return
        // A Kids' TV: the notification's or banner's person (the TV's own screen ignores anyone not on its list).
        if (key != "inbox") io.github.ravenor907.muninn.tv.TvNav.show(key)
        tab = "chat"
        ChatNav.open = if (key == "inbox") "" else key
        intentChose = true
    }

    /** Test builds only: a small group for the CI screenshots (made once, then reused). */
    private fun ciGroup() {
        val gs = app.chat.groups
        val g = gs.all.value.values.firstOrNull { it.state.name == "The Family" }?.g ?: gs.create("The Family")?.also { g ->
            gs.addChannel(g, "photos", false); gs.addChannel(g, "Living room", true); gs.sendText(g, "main", "Dinner at six")
        } ?: return
        tab = "chat"
        ChatNav.open = io.github.ravenor907.muninn.net.Folders.groupKey(g) + if (intent?.getBooleanExtra("gsettings", false) == true) "/settings" else ""
        intentChose = true
    }

    /** Wi-Fi, close-by (Bluetooth) and internet: started once the vault is open. */
    private fun startNet() {
        if (netStarted) return
        netStarted = true
        app.listen()
        io.github.ravenor907.muninn.net.MessageService.start(this)
        // Android 12 and older only allow looking for nearby phones with Location: never asked for at setup,
        // only once the person taps "find phones close by" (Nearby on Chats).
        if (Build.VERSION.SDK_INT >= 33 || closePerms().distinct().all { granted(it) }) askClose() else app.close.offer()
    }

    /** "Block screenshots": also hides Muninn's screen in recent apps. */
    fun secure() {
        if (app.identity.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Away long enough (even with no window in between): the lock, before the first frame. */
    private fun lockIfDue() {
        val pw = app.vault.hasPassword
        if (io.github.ravenor907.muninn.ui.AppLock.due(pw, io.github.ravenor907.muninn.ui.AppLock.awaySince,
                android.os.SystemClock.elapsedRealtime(), app.identity.lockAfter)) {
            uiLocked = true
            // Away long enough to lock: come back to the chat list, not the chat that was open. A notification
            // tap can arrive just before or just after this (onNewIntent), so only its choice is kept.
            if (!intentChose) toList()
        }
        io.github.ravenor907.muninn.ui.AppLock.awaySince = 0L
    }

    /**
     * With a lock set, recent apps never gets a picture of Muninn: Android shows that picture when
     * Muninn comes back, before Muninn has drawn the lock. Android 13+ can turn the picture off;
     * Android 12 and below get FLAG_SECURE while Muninn is in the background (secure() puts it back after).
     */
    private fun recents() {
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!app.vault.hasPassword)
    }

    override fun onStart() {
        super.onStart()
        app.visible = true
        lockIfDue()
        app.listen()   // relays (with reconnect) and the Wi-Fi side
        if (app.ready.value) app.chat.foreground(true)   // active status (only if it's on)
    }

    override fun onPause() {
        recents()
        if (Build.VERSION.SDK_INT < 33 && app.vault.hasPassword) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onStop() {
        app.visible = false
        if (app.ready.value && !isChangingConfigurations) app.chat.foreground(false)
        // A new window for a changed setting (dark mode, language) isn't leaving Muninn.
        if (!isChangingConfigurations) {
            io.github.ravenor907.muninn.ui.AppLock.awaySince = android.os.SystemClock.elapsedRealtime().coerceAtLeast(1L)
            // "Right away": the lock goes up now, so nothing else is ever drawn on the way back.
            if (app.vault.hasPassword && app.identity.lockAfter == 0L && !ciSkip) uiLocked = true
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        secure()
        recents()
        intentChose = false
        // Kids' TV: a call that came in over a show just ended; the show goes on, not Muninn.
        if (io.github.ravenor907.muninn.tv.TvCallFocus.sendBack()) moveTaskToBack(true)
        // "Check for updates automatically" off: nothing asks GitHub here (Check now still does). A Kids' TV follows its parent (k7).
        val autoUpdate = if (io.github.ravenor907.muninn.tv.KidMode.on(app)) io.github.ravenor907.muninn.tv.KidTv.state.value.cfg?.upd ?: true
            else app.identity.autoUpdate
        if (autoUpdate) app.updater.checkOnOpen()
        if (app.ready.value) app.net.announce()
        // Back from Muninn's app settings with the permission now allowed: start close-by chat.
        if (netStarted && !app.close.running && app.close.error.value != null && closePerms().all { granted(it) }) {
            closeBlocked = false
            app.close.start { app.chat.hello() }
            app.ble.start()
        }
    }

    private fun startDirect() {
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) app.direct.start() else directPermission.launch(perms)
    }


    /** "Share" › Muninn from another app: copy what was shared so it can be sent, then open Files. */
    private fun handleShare(i: Intent?) {
        if (i == null || (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_SEND_MULTIPLE)) return
        val uris = if (i.action == Intent.ACTION_SEND) listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM))
            else (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        if (uris.isEmpty()) return
        val busy = app.ready.value && app.net.transfer.value.active
        Thread {
            val files = Share.copyIn(this, uris, busy)
            runOnUiThread { Share.pending.addAll(files); show("files") }
        }.start()
    }

    /** Every touch or key on the window: the status stays Online (Away comes after 10 minutes without one). */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) app.touched()
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) app.touched()
        return super.dispatchKeyEvent(event)
    }


    private fun title(t: String) = when (t) { "files" -> "Files"; "contacts" -> "Contacts"; "settings" -> "Settings"; else -> "Muninn" }

    private fun toggleSettings() {
        if (tab == "settings") tab = beforeSettings else show("settings")
    }

    @Composable
    private fun Root() {
        // Kids' TV: its own screens, with no lock and no sign in.
        if (io.github.ravenor907.muninn.tv.KidMode.on(app)) { io.github.ravenor907.muninn.tv.TvRoot(app); return }
        val ready by app.ready.collectAsState()
        val panic by io.github.ravenor907.muninn.ui.Panic.locked.collectAsState()
        LaunchedEffect(panic) { if (panic) toList() }
        // Moving to another device (cap x1): locked while it moves, then "Moved to [name]".
        if (io.github.ravenor907.muninn.screens.MoveCover(app)) return
        if (!ready || uiLocked || panic) { LockScreen(app) { ok -> if (ok) { uiLocked = false; app.start(); startNet(); suggestStronger() } }; return }
        // A lock is suggested on first open, before anything else (Skip goes without one).
        var locked by remember { mutableStateOf(lockChosen(app) || ciSkip) }
        if (!locked) { FirstLock(app) { locked = true }; return }
        val request by app.net.request.collectAsState()
        val offer by app.closeFiles.pendingOffer.collectAsState()
        val apps by io.github.ravenor907.muninn.update.ApkInstall.offers.collectAsState()
        val ask by Ui.ask.collectAsState()
        val toast by Ui.toast.collectAsState()
        LaunchedEffect(Unit) {
            launch { app.net.messages.collect { (ok, msg) -> Ui.say(msg, !ok) } }
            launch { app.chat.notices.collect { Ui.say(it) } }
            // Nearby Connections named a permission the usual set didn't cover (location on some phones): ask once.
            launch { app.close.missing.collect { m -> if (m.isNotEmpty() && autoAsked.addAll(m)) askClose() } }
            launch { app.net.pinNeeded.collect { Ui.say("That device asks for a PIN. Turn off its PIN setting in LocalSend to send from Muninn.", true) } }
        }
        LaunchedEffect(toast) { if (toast != null) { delay(4500); Ui.toast.value = null } }
        // Your profile asked for a chat page (My code): over to Chats with it open.
        val goChat = SettingsNav.goChat
        LaunchedEffect(goChat) { if (goChat.isNotEmpty()) { SettingsNav.goChat = ""; show("chat"); ChatNav.open = goChat } }
        // A chat page asked for a Settings page (the chat list's "to finish" sheet): over to Settings with it open.
        val goSettings = SettingsNav.goSettings
        LaunchedEffect(goSettings) { if (goSettings.isNotEmpty()) { SettingsNav.goSettings = ""; show("settings"); SettingsNav.folder = goSettings } }
        // On the Files tab, Muninn looks for devices on the Wi-Fi at full speed (slower elsewhere, to save battery).
        LaunchedEffect(tab) { app.power.filesOpen.value = tab == "files" }
        // Back from another tab (or Settings) goes to where you came from, then the chat list.
        BackHandler(enabled = tab != "chat") { if (tab == "settings") tab = beforeSettings else tab = "chat" }

        // The status and navigation bars take the look's ground, with dark icons on parchment.
        val light = Palette.light
        val groundArgb = Palette.ground.toArgb()
        SideEffect {
            @Suppress("DEPRECATION") run { window.statusBarColor = groundArgb; window.navigationBarColor = groundArgb }
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Palette.ground)) {
            // Seasonal: leaves or snow fall behind everything (nothing is drawn in the other looks).
            io.github.ravenor907.muninn.ui.SeasonFall()
            // A tablet (smallest screen width 600 dp or more, whichever way it's held) is Discord style: a rail, the
            // chat list and the open chat. A phone, upright or sideways, shows one thing at a time. A window too
            // narrow for the three (split screen) is one thing at a time too.
            val tablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= ChatNav.TABLET_SW_DP
            val wide = tablet && maxWidth >= ChatNav.WIDE_MIN
            SideEffect { ChatNav.wide = wide }
            if (wide) TabletLayout(toast, maxWidth) else PhoneLayout(toast)
        }

        request?.let { r ->
            AlertDialog(
                onDismissRequest = { app.net.decline() },
                containerColor = Palette.panel,
                title = { Text("${r.from} wants to send ${if (r.count == 1) "a file" else "${r.count} files"}", style = Type.big) },
                text = { Text("${r.names} (${LocalSend.size(r.size)}). They'll be saved in ${Received.place(this@MainActivity)}.", style = Type.normal, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { MButton("Accept", kind = Kind.Accent) { acceptFiles { app.net.accept() } } },
                dismissButton = { MButton("Decline") { app.net.decline() } },
            )
        }
        offer?.let { r ->
            AlertDialog(
                onDismissRequest = { app.closeFiles.decline() },
                containerColor = Palette.panel,
                title = { Text("${r.from} wants to send ${if (r.count == 1) "a file" else "${r.count} files"}", style = Type.big) },
                text = { Text("${r.names} (${LocalSend.size(r.size)}), from close by. They'll be saved in ${Received.place(this@MainActivity)}.", style = Type.normal, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { MButton("Accept", kind = Kind.Accent) { acceptFiles { app.closeFiles.accept() } } },
                dismissButton = { MButton("Decline") { app.closeFiles.decline() } },
            )
        }
        // An app that came to this TV (ApkInstall): asked about here first, then Android asks too.
        apps.firstOrNull()?.let { o ->
            AlertDialog(
                onDismissRequest = { io.github.ravenor907.muninn.update.ApkInstall.decline(o) },
                containerColor = Palette.panel,
                title = { Text("Install ${o.name}?", style = Type.big) },
                text = { Text("${o.from} sent it. Only install apps from someone you trust. Android asks once more before it installs.", style = Type.normal, modifier = Modifier.verticalScroll(rememberScrollState())) },
                confirmButton = { MButton("Install", kind = Kind.Accent) { io.github.ravenor907.muninn.update.ApkInstall.accept(this@MainActivity, o) } },
                dismissButton = { MButton("Don't install") { io.github.ravenor907.muninn.update.ApkInstall.decline(o) } },
            )
        }
        // Every item's three-dot menu (also on holding a chat or person), from anywhere: one sheet.
        LaunchedEffect(ready) { if (ready) io.github.ravenor907.muninn.screens.People.load(app) }
        if (ciHold) LaunchedEffect(Unit) {
            ciHold = false
            delay(1500)
            app.chat.contacts.all.value.firstOrNull { !it.groupOnly }?.let { ChatNav.hold = it.chatKey }
                ?: app.chat.groups.all.value.keys.firstOrNull()?.let { ChatNav.hold = io.github.ravenor907.muninn.net.Folders.groupKey(it) }
        }
        if (ChatNav.hold.isNotEmpty()) io.github.ravenor907.muninn.screens.ItemMenu(app, ChatNav.hold, onChats = { tab = "chat" }, onLock = { uiLocked = true }) {
            ChatNav.hold = ""; ChatNav.menuGroup = ""
        }
        io.github.ravenor907.muninn.screens.GroupCallPicker(app)
        // Linked devices: the 4 emoji are checked on both screens before anything is mirrored.
        io.github.ravenor907.muninn.screens.LinkCheck()
        ask?.let { AskDialog(it) }
    }

    /** What a tab shows (the chat tab on phones; tablets lay chat out themselves). */
    @Composable
    private fun TabContent(t: String) {
        when (t) {
            "files" -> TransferScreen(app) { startDirect() }
            "contacts" -> ContactsScreen(app) { tab = "chat" }
            "settings" -> SettingsScreen(app) { secure() }
            else -> ChatScreen(app) { askClose() }
        }
    }

    @Composable
    private fun UpdateBanner() {
        val up by app.updater.state.collectAsState()
        if (up.ready && tab != "settings") Row(Modifier.fillMaxWidth().background(Palette.accentFill).clickable(role = Role.Button) { show("settings") }.padding(10.dp)) {
            Text("Muninn ${up.latest} is ready. Tap to install.", style = Type.small.copy(color = Palette.accent))
        }
    }

    @Composable
    private fun Toast(toast: Pair<String, Boolean>?, modifier: Modifier) {
        toast?.let { (text, bad) ->
            Box(modifier.padding(12.dp).fillMaxWidth().background(if (bad) Palette.dangerFill else Palette.raised).clickable { Ui.toast.value = null }.padding(14.dp)) {
                // A problem is marked with an icon too, never by color alone.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bad) { MIcon("info", Palette.danger, 20.dp); Spacer(Modifier.padding(start = 8.dp)) }
                    Text(text, style = Type.normal.copy(color = if (bad) Palette.danger else Palette.text))
                }
            }
        }
    }

    // ------------------------------------------------------------------ phones

    @Composable
    private fun PhoneLayout(toast: Pair<String, Boolean>?) {
        // An open conversation (or My code, Add someone, the Nearby room) gets the whole screen: no header, no tabs.
        val full = tab == "chat" && ChatNav.open.isNotEmpty()
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Only the raven, the name and the valknut. On Chats the name is centred; in Settings the raven counts taps (Labs).
            val header: @Composable () -> Unit = { Brand(onLogo = if (tab == "settings") ({ io.github.ravenor907.muninn.screens.Labs.tap(app) }) else null,
                title = title(tab).uppercase(), centered = tab == "chat") {
                SettingsButton(if (tab == "settings") "Close settings" else "Settings", tab == "settings") { toggleSettings() }
            } }
            if (Access.reduceMotion) { if (!full) header() } else androidx.compose.animation.AnimatedVisibility(!full) { header() }
            if (!full) UpdateBanner()
            Box(Modifier.weight(1f)) {
                TabContent(tab)
                Toast(toast, Modifier.align(Alignment.BottomCenter))
            }
            if (!full) BottomTabs()
        }
    }

    /**
     * The main Settings button: the valknut, black and grey. A very faint glow shows behind it while Settings is open. TalkBack
     * reads "Settings", and holding it shows the word too, for anyone who doesn't know the knot.
     */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsButton(label: String, open: Boolean, onClick: () -> Unit) {
        androidx.compose.material3.TooltipBox(
            positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Settings") } },
            state = androidx.compose.material3.rememberTooltipState(),
        ) {
            // No circle behind the knot: while Settings is open, a very faint glow comes from its centre instead.
            Box(Modifier.size(48.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                // Centred on the knot's weight, a touch below the middle of the button.
                if (open) Box(Modifier.size(48.dp).offset(y = 1.dp).background(androidx.compose.ui.graphics.Brush.radialGradient(
                    0f to Palette.goldBright.copy(alpha = 0.35f), 0.5f to Palette.goldBright.copy(alpha = 0.12f),
                    1f to androidx.compose.ui.graphics.Color.Transparent)))
                // The knot's weight sits low in its picture (wide base, pointed tops): lift it 2dp so it looks centred.
                Box(Modifier.offset(y = (-2).dp)) { Valknut(28.dp) }
            }
        }
    }

    @Composable
    private fun Valknut(size: androidx.compose.ui.unit.Dp) =
        Image(painterResource(R.drawable.settings_valknut), contentDescription = null, modifier = Modifier.size(size))

    @Composable
    private fun BottomTabs() {
        Column(Modifier.fillMaxWidth().bar(Palette.groundDeep, top = true)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
                listOf("chat" to ("chat" to "Chats"), "files" to ("folder" to "Files"), "contacts" to ("people" to "Contacts")).forEach { (id, v) ->
                    val on = tab == id
                    val tint = if (on) Palette.goldBright else Palette.textMuted
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Tab) { if (id == "chat" && tab == "chat") toList() else show(id) }
                        .semantics { selected = on }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        MIcon(v.first, tint, 22.dp)
                        // One line under the icon: "Contacts" shortens rather than wrapping at a large text size.
                        Text(v.second, style = Type.small.copy(color = tint, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ tablets

    /** One button on the tablet rail. A gold bar at its left marks the one that's open. */
    @Composable
    private fun RailButton(label: String, on: Boolean, round: Boolean, fill: androidx.compose.ui.graphics.Color, onClick: () -> Unit, content: @Composable () -> Unit) {
        Box(Modifier.width(80.dp), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.align(Alignment.CenterStart).size(width = 4.dp, height = 32.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(Palette.goldBright))
            val shape = if (round) CircleShape else io.github.ravenor907.muninn.ui.Corner
            Box(Modifier.size(52.dp).clip(shape).background(fill).clickable(role = Role.Tab, onClick = onClick)
                .semantics { contentDescription = label; selected = on }, contentAlignment = Alignment.Center) { content() }
        }
    }

    /** The rail's top part: direct messages, then each group, then New group. */
    @Composable
    private fun RailSpaces() {
        val groups by app.chat.groups.all.collectAsState()
        val unread by app.chat.groups.unreadGroups.collectAsState()
        val dms = tab == "chat" && ChatNav.space.isEmpty()
        RailButton("Direct messages", dms, round = false, fill = Palette.field, onClick = { ChatNav.space = ""; if (dms) toList() else { show("chat"); ChatNav.open = "" } }) {
            Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(32.dp))
        }
        Box(Modifier.size(width = 32.dp, height = 2.dp).background(Palette.hairline))
        groups.values.forEach { grp ->
            val on = tab == "chat" && ChatNav.space == grp.g
            val name = grp.state.name
            // The open one is a rounded square in gold, the rest circles; a gold dot for unread.
            Box(contentAlignment = Alignment.TopEnd) {
                RailButton(if (grp.g in unread) "$name, unread" else name, on, round = !on, fill = if (on) Palette.gold else Palette.coolTint,
                    onClick = { show("chat"); ChatNav.space = grp.g; ChatNav.open = io.github.ravenor907.muninn.net.Folders.groupKey(grp.g) }) {
                    // Sized to the button, not the font size, so the letter stays inside it.
                    val letter = with(androidx.compose.ui.platform.LocalDensity.current) { 19.dp.toSp() }
                    Text(name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#",
                        style = Type.big.copy(fontSize = letter, lineHeight = letter, fontWeight = FontWeight.SemiBold, color = if (on) Palette.onGold else Palette.accent), maxLines = 1)
                }
                if (grp.g in unread && !on) Box(Modifier.padding(top = 2.dp, end = 12.dp).size(12.dp).clip(CircleShape).background(Palette.gold))
            }
        }
        RailButton("New group", false, round = true, fill = androidx.compose.ui.graphics.Color.Transparent,
            onClick = { show("chat"); ChatNav.space = ""; ChatNav.open = "newgroup" }) {
            MIcon("plus", Palette.textMuted, 22.dp)
        }
    }

    @Composable
    private fun TabletLayout(toast: Pair<String, Boolean>?, width: androidx.compose.ui.unit.Dp) {
        // A phone on its side is narrower than a tablet: the list takes a little less, so the chat keeps room.
        val listW = minOf(270.dp, width * 0.34f)
        Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.width(80.dp).fillMaxHeight().background(Palette.groundDeep).padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Groups can be many: the top part scrolls, the tabs below stay put.
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { RailSpaces() }
                listOf("files" to ("folder" to "Files"), "contacts" to ("people" to "Contacts")).forEach { (id, v) ->
                    val on = tab == id
                    RailButton(v.second, on, round = true, fill = if (on) Palette.field else androidx.compose.ui.graphics.Color.Transparent,
                        onClick = { show(id) }) {
                        MIcon(v.first, if (on) Palette.goldBright else Palette.textMuted, 22.dp)
                    }
                }
                Box(Modifier.width(80.dp), contentAlignment = Alignment.Center) {
                    SettingsButton(if (tab == "settings") "Close settings" else "Settings", tab == "settings") { toggleSettings() }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                UpdateBanner()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tab == "chat") Row(Modifier.fillMaxSize()) {
                        // A group opened from anywhere (a notification, the list) picks it on the rail too.
                        val opened = ChatNav.groupOf(ChatNav.open)
                        LaunchedEffect(opened) { if (opened != null) ChatNav.space = opened }
                        val groups by app.chat.groups.all.collectAsState()
                        val space = groups[ChatNav.space]
                        Column(Modifier.width(listW).fillMaxHeight().background(Palette.panelLow)) {
                            if (space != null) {
                                // A group: its channels (TEXT, then VOICE rooms), the conversation in the main column.
                                ColumnHeader(space.state.name) {
                                    io.github.ravenor907.muninn.ui.DotsButton("Menu for ${space.state.name}") {
                                        ChatNav.menuGroup = ""; ChatNav.hold = io.github.ravenor907.muninn.net.Folders.groupKey(space.g)
                                    }
                                }
                                Box(Modifier.weight(1f).fillMaxWidth()) { ChannelColumn(app, space.g) }
                            } else {
                                ColumnHeader("Chats")
                                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) { ChatColumn(app) { askClose() } }
                            }
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(Palette.hairline))
                        Box(Modifier.weight(1f).fillMaxHeight()) { ChatMain(app) }
                    } else Column(Modifier.fillMaxSize()) {
                        ColumnHeader(title(tab), onRaven = if (tab == "settings") ({ io.github.ravenor907.muninn.screens.Labs.tap(app) }) else null)
                        Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(tab) }
                    }
                    Toast(toast, Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp))
                }
            }
        }
    }

    /** A tablet column's 60dp title bar with a hairline under it. */
    @Composable
    private fun ColumnHeader(text: String, onRaven: (() -> Unit)? = null, trailing: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}) {
        Column(Modifier.fillMaxWidth().bar()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Settings: the raven, which counts taps (Labs). No ripple and nothing announced, as on phones.
                if (onRaven != null) {
                    val tap by androidx.compose.runtime.rememberUpdatedState<() -> Unit>(onRaven)
                    Image(painterResource(R.drawable.raven_silver), contentDescription = null, modifier = Modifier.size(26.dp)
                        .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) })
                    Spacer(Modifier.width(10.dp))
                }
                Text(text.uppercase(), style = Type.title.copy(fontSize = 17.sp, letterSpacing = 2.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                trailing()
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        }
    }
}
