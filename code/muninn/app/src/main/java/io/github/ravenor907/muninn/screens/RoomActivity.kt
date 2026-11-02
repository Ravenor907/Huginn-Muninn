package io.github.ravenor907.muninn.screens

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.call.AudioRoute
import io.github.ravenor907.muninn.net.call.RoomActions
import io.github.ravenor907.muninn.net.call.RoomManager
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Panic
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * A voice room or group call, full screen (over the lock screen only when opened there, as for a group call's ring). Joining,
 * calling a group and answering a ring come through here so the microphone can be asked for first; without it
 * a room is joined listening only. Tapping a voice room shows the join sheet first (unless "Don't ask again").
 * A shared screen keeps the phone's screen on and goes to picture-in-picture when the person leaves the room screen
 * (Home, another app, or Back).
 */
class RoomActivity : ComponentActivity() {
    private var pending: ((Boolean) -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        p?.invoke(ok)
    }
    /** Listening only, "Allow the microphone to talk": asked again, or Android's settings once it won't ask. */
    private val askMicToTalk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) RoomManager.micAllowed() else {
            Toast.makeText(this, "Allow the microphone for Muninn in Android's settings.", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            } catch (_: Exception) {}
        }
    }
    /** The join sheet is up for this (group, room). */
    private var asking by mutableStateOf<Pair<String, String>?>(null)
    private var pip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overLock(locked())
        // Back with a shared screen showing: the picture goes on in a small window, so the chat can be answered.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!enterPip()) finish() }
        })
        val app = application as? Muninn
        // In Recents now: "Hide Muninn's screen" covers this window too.
        if (app?.identity?.secureScreen == true) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(app?.identity?.showPreview == true)
        val hide = { Panic.locked.value || (!(app?.identity?.showPreview ?: false) &&
            getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) }
        setContent {
            MuninnTheme {
                val a = asking
                if (a != null && app != null) RoomJoinSheet(app, a.first, a.second, onCancel = { asking = null; finish() }) { muted, output ->
                    asking = null
                    joinNow(a.first, a.second, muted, output)
                } else RoomScreen(onBack = { if (!enterPip()) finish() }, onJoinRing = { withMic { mic -> say(RoomManager.answerRing(listen = !mic)) } }, hideNames = hide(),
                    pip = pip, onAllowMic = { askMicToTalk.launch(Manifest.permission.RECORD_AUDIO) })
            }
        }
        // Gone once there's no room and no ring any more.
        lifecycleScope.launch {
            var seen = false
            RoomManager.room.combine(RoomManager.ring) { r, g -> r != null || g != null }.collect { if (it) seen = true else if (seen) finish() }
        }
        lifecycleScope.launch { RoomManager.notices.collect { Toast.makeText(this@RoomActivity, it, Toast.LENGTH_LONG).show() } }
        // Picture-in-picture keeps Mute (or Unmute) and Leave up to date, and goes in by itself on Home (Android 12+).
        lifecycleScope.launch {
            RoomManager.room.combine(RoomManager.screen) { r, sc -> Triple(r?.muted, r?.listenOnly, sc != null) }.collect {
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (locked()) overLock(true); handle(intent) }

    // Over the lock screen only when opened there (a ring, or the notification on the lock screen). Once the phone is
    // unlocked, a later lock covers the room: no lit screen, buttons or camera in a pocket.
    override fun onResume() {
        super.onResume()
        if (!locked()) overLock(false)
        // Back from Android's settings with the microphone allowed: listening only ends by itself.
        if (RoomManager.room.value?.listenOnly == true &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) RoomManager.micAllowed()
        RoomManager.screenInFront(true)
    }
    override fun onPause() { RoomManager.screenInFront(false); super.onPause() }

    private fun locked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    @Suppress("DEPRECATION")
    private fun overLock(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(on); setTurnScreenOn(on); return }
        val f = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        if (on) window.addFlags(f) else window.clearFlags(f)
    }

    // Watching: the shared screen comes only while the room screen shows (picture-in-picture counts) with the screen on.
    override fun onStart() { super.onStart(); RoomManager.setWatching(true) }
    override fun onStop() {
        RoomManager.setWatching(false)
        // Out of sight: no longer over the lock screen (a ring still is), so a pocketed phone doesn't light up the room.
        if (RoomManager.ring.value == null) overLock(false)
        super.onStop()
    }

    /** Home or another app while a screen is shared: the picture goes on in a small window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    /** A screen is shared: the room screen shrinks to picture-in-picture. False when there's nothing to show in one. */
    private fun enterPip(): Boolean {
        if (pip) return true
        val sc = RoomManager.screen.value ?: return false
        if (RoomManager.room.value == null || asking != null) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val turned = sc.rotation % 180 != 0
        val w = if (turned) sc.height else sc.width
        val h = if (turned) sc.width else sc.height
        // Android takes ratios between 1:2.39 and 2.39:1.
        val ratio = if (w > 0 && h > 0) Rational(w, h).let { r -> if (r.toFloat() > 2.39f) Rational(239, 100) else if (r.toFloat() < 1 / 2.39f) Rational(100, 239) else r } else Rational(16, 9)
        return try { enterPictureInPictureMode(pipParams(ratio)) } catch (_: Exception) { false }
    }

    /** The small window's ratio, its Mute (or Unmute) and Leave buttons, and auto-enter while a screen is shared. */
    private fun pipParams(ratio: Rational? = null): PictureInPictureParams {
        val r = RoomManager.room.value
        val acts = mutableListOf<RemoteAction>()
        fun act(icon: Int, title: String, action: String, code: Int) = RemoteAction(Icon.createWithResource(this, icon), title, title,
            PendingIntent.getBroadcast(this, code, Intent(this, RoomActions::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (r != null && !r.listenOnly) acts += act(if (r.muted) android.R.drawable.ic_btn_speak_now else android.R.drawable.ic_lock_silent_mode,
            if (r.muted) "Unmute" else "Mute", RoomActions.MUTE, 0x5201)
        if (r != null) acts += act(android.R.drawable.ic_menu_close_clear_cancel, "Leave", RoomActions.LEAVE, 0x5202)
        val b = PictureInPictureParams.Builder().setActions(acts)
        if (ratio != null) b.setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(r != null && RoomManager.screen.value != null && asking == null)
        return b.build()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    private fun say(why: String?) { if (why != null) Toast.makeText(this, why, Toast.LENGTH_LONG).show() }

    private fun joinNow(g: String, ch: String, muted: Boolean, output: String) {
        withMic { mic ->
            if (!mic) say("Listening only. Allow the microphone to talk.")
            RoomManager.join(g, ch, muted, output, listen = !mic)?.let { say(it); if (RoomManager.room.value == null) finish() }
        }
    }

    private fun handle(i: Intent?) {
        when (i?.action) {
            ANSWER -> withMic { mic -> say(RoomManager.answerRing(listen = !mic)) }
            JOIN -> {
                val g = i.getStringExtra(GROUP) ?: return finish()
                val ch = i.getStringExtra(ROOM) ?: return finish()
                i.action = SHOW   // a rotation or a return must not join again
                val id = (application as? Muninn)?.identity
                val already = RoomManager.room.value?.let { it.g == g && it.ch == ch } == true
                when {
                    already -> {}
                    id != null && id.roomAsk -> asking = g to ch
                    else -> joinNow(g, ch, id?.roomMuted ?: false, AudioRoute.AUTOMATIC)
                }
            }
            CALL -> {
                val g = i.getStringExtra(GROUP) ?: return finish()
                val keys = i.getStringArrayExtra(KEYS)?.toList() ?: emptyList()
                i.action = SHOW
                withMic { mic ->
                    if (!mic) { say("Calling a group needs the microphone. Allow it in Android's settings for Muninn."); finish() }
                    else RoomManager.callGroup(g, keys)?.let { say(it); if (RoomManager.room.value == null) finish() }
                }
            }
            else -> if (RoomManager.room.value == null && RoomManager.ring.value == null && asking == null) finish()
        }
    }

    /** [f] gets whether the microphone is allowed (asked first when it isn't yet). */
    private fun withMic(f: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f(true)
        else { pending = f; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    companion object {
        const val SHOW = "io.github.ravenor907.muninn.room.SHOW"
        const val ANSWER = "io.github.ravenor907.muninn.room.ANSWER"
        const val JOIN = "io.github.ravenor907.muninn.room.JOIN"
        const val CALL = "io.github.ravenor907.muninn.room.CALL"
        private const val GROUP = "group"
        private const val ROOM = "room"
        private const val KEYS = "keys"

        private fun open(c: Context, i: Intent) = c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        /** Joins a voice room (or a group call going on) of [g]: the join sheet first, unless turned off. */
        fun join(c: Context, g: String, ch: String) = open(c, Intent(c, RoomActivity::class.java).setAction(JOIN).putExtra(GROUP, g).putExtra(ROOM, ch))
        /** Rings [keys] of [g] (everyone who can join, when empty) into a new group call. */
        fun call(c: Context, g: String, keys: List<String>) = open(c, Intent(c, RoomActivity::class.java).setAction(CALL).putExtra(GROUP, g).putExtra(KEYS, keys.toTypedArray()))
        /** Back to the room going on. */
        fun show(c: Context) = open(c, Intent(c, RoomActivity::class.java).setAction(SHOW))
    }
}
