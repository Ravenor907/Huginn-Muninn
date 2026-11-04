package io.github.ravenor907.muninn.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.net.call.CallPrivacy
import io.github.ravenor907.muninn.net.call.CallSession
import io.github.ravenor907.muninn.net.call.Opus
import io.github.ravenor907.muninn.ui.MuninnTheme
import io.github.ravenor907.muninn.ui.Panic
import kotlinx.coroutines.launch

/**
 * The call, full screen and over the lock screen: the incoming ring's full-screen intent opens it, and so
 * does the Call button in a chat ([call]). It asks for the microphone when needed, then shows [CallScreen].
 */
class CallActivity : ComponentActivity() {
    /** The phone's own lock screen is up: looked at again on every return, focus change and every second while shown. */
    private var keyguard by mutableStateOf(false)
    /** 1.42: Muninn's own lock counts for this call ([CallPrivacy.lockedNow]): looked at with [keyguard]. */
    private var muninnLocked by mutableStateOf(false)
    /** Kids' TV: the microphone is off, so the call screen says "Ask a parent" instead of Android's prompt. */
    private var tvAskParent by mutableStateOf(false)
    private var pending: (() -> Unit)? = null
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pending; pending = null
        if (ok) p?.invoke() else {
            Toast.makeText(this, "Calls need the microphone. Allow it in Android's settings for Muninn.", Toast.LENGTH_LONG).show()
            if (CallManager.call.value?.phase == CallSession.Phase.INCOMING) CallManager.decline()
            finish()
        }
    }

    private var pendingCamera: Pair<() -> Unit, () -> Unit>? = null
    private var cameraRefused = NO_CAMERA
    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val p = pendingCamera; pendingCamera = null
        if (ok) p?.first?.invoke() else {
            Toast.makeText(this, cameraRefused, Toast.LENGTH_LONG).show()
            p?.second?.invoke()
        }
    }

    /** [f] once the camera may be used; [otherwise] when it may not (the call goes on without video, and [refused] says so). */
    private fun withCamera(f: () -> Unit, otherwise: () -> Unit = {}, refused: String = NO_CAMERA) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) f()
        else { pendingCamera = f to otherwise; cameraRefused = refused; askCamera.launch(Manifest.permission.CAMERA) }
    }

    /**
     * Answers the ring. A video call is answered with the camera, which is asked for first if Android hasn't allowed it
     * yet (the person answering was never asked before); refused, it's answered as a voice call.
     */
    private fun answer(video: Boolean) = withMic {
        if (video && CallManager.videoHere()) withCamera({ CallManager.answer(video = true) }, { CallManager.answer() }, ANSWERED_VOICE)
        else CallManager.answer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        look()
        setContent {
            // The name hides the moment Muninn is panic-locked or the phone locks, and shows again once both are over.
            val panic by Panic.locked.collectAsState()
            // 1.42: and while Muninn's own lock is up (or, while it rings, due): no name or picture until Muninn is unlocked.
            val appLocked by io.github.ravenor907.muninn.ui.AppLock.locked.collectAsState()
            io.github.ravenor907.muninn.ui.EveryWhileShown(1000) { look() }
            val previews = (application as? Muninn)?.identity?.showNames ?: false
            val hide = CallPrivacy.hide(panic, appLocked || muninnLocked, previews, keyguard)
            // The ongoing call's notification follows (it names nobody while this hides).
            androidx.compose.runtime.LaunchedEffect(hide) {
                val ph = CallManager.call.value?.phase
                if (ph != null && ph != CallSession.Phase.ENDED) io.github.ravenor907.muninn.net.call.CallService.update(this@CallActivity)
            }
            MuninnTheme {
                // Kids' TV: the remote-friendly call screen.
                if (io.github.ravenor907.muninn.tv.TvMic.active) io.github.ravenor907.muninn.tv.TvCallScreen(app = application as Muninn, onAnswer = { answer(false) }, onClose = { close() }, askParent = tvAskParent)
                else CallScreen(onBack = { finish() }, onMessage = { key ->
                    startActivity(Intent(this, MainActivity::class.java).putExtra("chat", key).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }, onAnswer = { answer(false) }, hideName = hide,
                    onAnswerVideo = { answer(true) },
                    onCamera = { on -> if (on) withCamera({ CallManager.setCamera(true) }) else CallManager.setCamera(false) })
            }
        }
        // Gone once the call is over (after the short "Call ended" moment).
        lifecycleScope.launch { var seen = false; CallManager.call.collect { if (it != null) seen = true else if (seen) close() } }
        // Made again (dark mode during a call, or after Android stopped it): Android hands back the first Answer or Call
        // intent, which must not answer or call a second time.
        if (savedInstanceState != null) intent?.action = SHOW
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onResume() { super.onResume(); look() }

    // Round 17: on a Kids' TV a call is never live with nothing on screen (TvCallGuard): Home or another app ends it soon
    // after, the TV turned off ends it, and the remote's search, mic and Assistant keys never leave the call screen.
    override fun onStart() { super.onStart(); io.github.ravenor907.muninn.tv.TvCallGuard.shownNow() }

    override fun onStop() { super.onStop(); io.github.ravenor907.muninn.tv.TvCallGuard.hidden(this) }

    override fun onUserInteraction() { super.onUserInteraction(); io.github.ravenor907.muninn.tv.TvCallGuard.touched() }

    private fun kidTv() = io.github.ravenor907.muninn.tv.TvMic.active && io.github.ravenor907.muninn.tv.KidMode.isTv(this)

    override fun onSearchRequested(): Boolean = if (kidTv()) true else super.onSearchRequested()

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean =
        if (kidTv() && io.github.ravenor907.muninn.tv.KidRules.isMicKey(keyCode)) true else super.onKeyDown(keyCode, event)

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); look() }

    /** The phone's lock screen and Muninn's own lock (for the call as it is now: ringing or not), looked at again. */
    private fun look() {
        keyguard = isLocked()
        muninnLocked = CallPrivacy.lockedNow(application as? Muninn, CallManager.call.value?.phase == CallSession.Phase.INCOMING)
    }

    /** Kids' TV: back to the show that was on when the call came (not to Muninn); else just closes. */
    private fun close() { if (io.github.ravenor907.muninn.tv.TvMic.active) io.github.ravenor907.muninn.tv.TvCallFocus.leave(this) else finish() }

    private fun isLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun handle(i: Intent?) {
        when (i?.action) {
            ANSWER -> {
                i.action = SHOW   // a rotation or a return must not answer again
                // The notification's Answer: a video call is answered with video, as its own screen's Video button would.
                answer(CallManager.call.value?.videoCall == true)
            }
            CALL -> {
                val key = i.getStringExtra(KEY) ?: return finish()
                val video = i.getBooleanExtra(VIDEO, false)
                i.action = SHOW   // a rotation or a return must not start a second call
                val go = { withVideo: Boolean ->
                    if (Opus.loadError != null) { Toast.makeText(this, "Calling isn't working on this phone", Toast.LENGTH_LONG).show(); finish() }
                    else if (!CallManager.start(key, withVideo) && CallManager.call.value?.peerKey != key) { Toast.makeText(this, "You're already on a call", Toast.LENGTH_SHORT).show() }
                }
                withMic { if (video) withCamera({ go(true) }, { go(false) }) else go(false) }
            }
            else -> if (CallManager.call.value == null) finish()
        }
    }

    private fun withMic(f: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) f()
        else if (io.github.ravenor907.muninn.tv.TvMic.active) {
            // Kids' TV: the microphone is asked for when a parent pairs the TV (or from their phone), never by a child's
            // press. A ring stays up with "Ask a parent" (Not now still declines); a call out doesn't start.
            tvAskParent = true
            if (CallManager.call.value == null) finish()
        }
        else { pending = f; askMic.launch(Manifest.permission.RECORD_AUDIO) }
    }

    companion object {
        const val SHOW = "io.github.ravenor907.muninn.call.SHOW"
        const val ANSWER = "io.github.ravenor907.muninn.call.ANSWER"
        const val CALL = "io.github.ravenor907.muninn.call.CALL"
        private const val KEY = "peerKey"
        private const val VIDEO = "video"
        private const val NO_CAMERA = "Video needs the camera. Allow it in Android's settings for Muninn."
        private const val ANSWERED_VOICE = "Answered as a voice call: video needs the camera. Allow it in Android's settings for Muninn."

        /** Calls [peerKey]: opens the call screen, asks for the microphone (and for [video] the camera) if needed, and starts the call. */
        fun call(c: Context, peerKey: String, video: Boolean = false) = c.startActivity(Intent(c, CallActivity::class.java).setAction(CALL).putExtra(KEY, peerKey)
            .putExtra(VIDEO, video).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))

        /** Back to the call going on (a "Return to call" bar). */
        fun show(c: Context) = c.startActivity(Intent(c, CallActivity::class.java).setAction(SHOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }
}
