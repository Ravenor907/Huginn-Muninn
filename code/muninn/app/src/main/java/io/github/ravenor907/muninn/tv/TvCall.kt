package io.github.ravenor907.muninn.tv

import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.CallManager
import io.github.ravenor907.muninn.net.call.CallVideo
import io.github.ravenor907.muninn.net.call.CallSession.Phase
import io.github.ravenor907.muninn.screens.CallActivity
import io.github.ravenor907.muninn.screens.VideoPicture
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A call on the Kids' TV, made for the remote: one big focused button (Answer when it rings, Hang up
 * during the call), Back declines a ring. [askParent]: Answer was pressed with the microphone off, so it says "Ask a parent". A big muted icon shows while the call is muted. The microphone
 * level and noise removal are set from a parent's phone. Shown by CallActivity in kids' mode.
 * When the caller's camera is on, it fills the screen and the name, status and button sit in a band along the
 * bottom; the TV never sends a camera of its own.
 */
@Composable
fun TvCallScreen(@Suppress("UNUSED_PARAMETER") app: io.github.ravenor907.muninn.Muninn, onAnswer: () -> Unit, onClose: () -> Unit,
                 askParent: Boolean = false) {
    val c by CallManager.call.collectAsState()
    val video by CallVideo.state.collectAsState()
    val context = LocalContext.current
    val mic = remember { TvAudio.hasMic(context) }
    // A Kids' Device on a tablet or phone: the same screen, narrower buttons that fit its width.
    val tv = remember { KidMode.isTv(context) }
    val wide = if (tv) 260.dp else 140.dp
    val call = c
    BackHandler { if (call?.phase == Phase.INCOMING) CallManager.decline() else onClose() }
    Box(Modifier.fillMaxSize().background(Palette.ground), contentAlignment = Alignment.Center) {
        if (call == null) { Text("Call ended", style = tvTitle(34.sp)); return@Box }
        // The caller's camera, full screen behind the controls (the same surface path as the phone's call screen).
        val showVideo = CallLogic.showRemote(call.video, video.peerCamera, call.phase == Phase.ENDED)
        if (showVideo) VideoPicture({ CallVideo.remoteSurface(it) }, video.width, video.height, video.rotation, Modifier.fillMaxSize(), "Their camera")
        // Centred when it fits; on a 540 dp tall TV screen it scrolls, and the focused button comes into view.
        // Over their camera: a band along the bottom, so the picture keeps most of the screen.
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = (if (showVideo) Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Palette.ground.copy(alpha = 0.72f))
                else Modifier.align(Alignment.Center))
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = if (tv) 48.dp else 16.dp, vertical = if (showVideo) 20.dp else 32.dp)) {
            // No video coming: who it is, big.
            if (!showVideo) {
                val initial = call.name.trim().let { if (it.isEmpty()) "?" else String(Character.toChars(it.codePointAt(0))).uppercase() }
                Box(Modifier.size(170.dp).clip(CircleShape).background(if (call.peerSpeaking) Palette.gold else Palette.goldTint), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(150.dp).clip(CircleShape).background(Palette.goldTint), contentAlignment = Alignment.Center) {
                        Text(initial, style = tvText(64.sp, Palette.goldBright, FontWeight.SemiBold))
                    }
                }
            }
            Text(call.name, style = tvTitle(if (showVideo) 34.sp else 44.sp), modifier = Modifier.padding(top = if (showVideo) 0.dp else 22.dp))
            Text(status(call), style = tvText(26.sp, Palette.textSoft), modifier = Modifier.padding(top = 8.dp))
            if (call.phase != Phase.ENDED) when {
                !mic -> TvProblemLine("mic", TvWords.NO_MIC)
                // The microphone is off on this TV: only a parent can turn it on (from their phone).
                askParent -> TvProblemLine(TvWords.icon(TvWords.ASK_PARENT), TvWords.ASK_PARENT)
                call.muted -> Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    MIcon("mute", Palette.danger, 72.dp)
                    Text("Muted", style = tvText(30.sp, Palette.danger, FontWeight.SemiBold), modifier = Modifier.padding(start = 14.dp))
                }
                call.note.isNotEmpty() -> Text(call.note, style = tvText(22.sp, Palette.danger), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            }
            val first = remember { FocusRequester() }
            Row(Modifier.padding(top = if (showVideo) 20.dp else 36.dp), horizontalArrangement = Arrangement.spacedBy(if (tv) 20.dp else 12.dp)) {
                when (call.phase) {
                    Phase.INCOMING -> {
                        Box(Modifier.width(wide)) { BigButton("Answer", icon = "call", requester = first, onClick = onAnswer) }
                        Box(Modifier.width(wide)) { BigButton("Not now", icon = "close") { CallManager.decline() } }
                    }
                    Phase.ENDED -> Box(Modifier.width(wide)) { BigButton("OK", requester = first) { CallManager.dismiss(); onClose() } }
                    else -> Box(Modifier.width(if (tv) 300.dp else 220.dp)) { BigButton("Hang up", icon = "close", requester = first) { CallManager.hangup() } }
                }
            }
            LaunchedEffect(call.phase == Phase.INCOMING, call.phase == Phase.ENDED, showVideo) { delay(80); try { first.requestFocus() } catch (_: Exception) {} }
        }
    }
}

@Composable
private fun status(c: CallManager.Ui): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.phase, c.connectedAt) { while (c.phase == Phase.LIVE) { now = System.currentTimeMillis(); delay(500) } }
    return when (c.phase) {
        Phase.CALLING -> "Calling…"
        Phase.RINGING -> "Ringing…"
        Phase.INCOMING -> "is calling you"
        Phase.CONNECTING -> "Connecting…"
        Phase.RECONNECTING -> "Reconnecting…"
        Phase.LIVE -> ((now - c.connectedAt) / 1000).coerceAtLeast(0).let { s -> "%d:%02d".format(s / 60, s % 60) }
        Phase.ENDED -> when (c.endWhy) {
            "declined" -> if (c.outgoing) "They can't talk right now" else "Call ended"
            "busy" -> "They're on another call"
            "noanswer" -> "No answer"
            "nopath", "error" -> "The call couldn't connect"
            "dropped" -> "The call dropped"
            "missed" -> "Missed call"
            else -> "Call ended"
        }
    }
}

/**
 * Ringing on the TV. A TV has no ring volume anyone sets, so the raven call plays on the media stream
 * (the volume the TV is at), and takes audio focus for a moment, which pauses or ducks a show or game.
 * The call screen is also brought up over whatever is on (full-screen intent from the call notification,
 * and a direct start, which Android allows when Muninn may show over other apps).
 */
object TvRing {
    private var player: MediaPlayer? = null
    /** The raven ring is playing (it counts as music to Android). */
    val ringing: Boolean get() = player != null
    private val gap = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var watching = false
    private var lastCall = ""

    /** Called from the call ringer: true when this is a Kids' TV and the TV rang (or stopped) itself. */
    @Synchronized
    fun instead(c: Context, on: Boolean): Boolean {
        if (!TvMic.active) return false
        if (on) start(c) else stop(c)
        return true
    }

    private fun start(c: Context) {
        if (player != null) return
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        // The show pauses for the ring and stays paused for the whole call (TvCallFocus gives it back at the end).
        TvCallFocus.hold(c)
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(c, Uri.parse("android.resource://${c.packageName}/${R.raw.raven_message}"))
                setOnCompletionListener { mp -> gap.postDelayed({ if (player === mp) try { mp.start() } catch (_: Exception) {} }, 1_000) }
                prepare(); start()
            }
        } catch (_: Exception) { player?.release(); player = null }
    }

    private fun stop(c: Context) {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
    }

    /** Once, on a Kids' TV: every new incoming call brings the call screen up. */
    fun watch(c: Context) {
        if (watching) return
        watching = true
        val app = c.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            CallManager.call.collect { ui ->
                if (TvMic.active) {
                    // The show playing pauses for any call (ringing, calling out, talking) and comes back after.
                    if (ui != null && ui.phase != Phase.ENDED) TvCallFocus.hold(app) else TvCallFocus.release(app)
                    if (ui?.phase == Phase.LIVE) TvCallFocus.live(app)
                }
                if (ui != null && ui.phase == Phase.INCOMING && ui.callId != lastCall && TvMic.active) {
                    lastCall = ui.callId
                    try {
                        app.startActivity(Intent(app, CallActivity::class.java).setAction(CallActivity.SHOW)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

/**
 * A call on the Kids' TV and whatever was on before it. When a call rings or starts, Muninn takes audio
 * focus for the whole call (a show or game pauses, as for a phone call; one that ignores focus but
 * plays through a media session gets Pause), so its sound never goes into the microphone. When the call
 * ends the focus goes back (the show resumes; one that was paused with Pause gets Play), and if the TV
 * was showing something else when the call came, the call screen closes back to it instead of to Muninn.
 */
object TvCallFocus {
    private var request: AudioFocusRequest? = null
    private var pausedByKey = false
    private var wasPlaying = false
    /** Muninn wasn't on screen when the call began: back to what was. */
    @Volatile private var returnToShow = false
    /** Until when MainActivity coming up after a call should step back behind the show. */
    @Volatile private var sendBackUntil = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    @Synchronized
    fun hold(c: Context) {
        if (request != null) return
        val app = c.applicationContext
        val am = app.getSystemService(AudioManager::class.java) ?: return
        val playing = try { am.isMusicActive && !TvRing.ringing } catch (e: Exception) { false }
        wasPlaying = playing
        returnToShow = !((app as? io.github.ravenor907.muninn.Muninn)?.visible ?: true)
        pausedByKey = false
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        request = try {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { }.build().also { am.requestAudioFocus(it) }
        } catch (e: Exception) { null }
        // Something still playing a moment later ignores focus: ask its media session to pause.
        if (playing) main.postDelayed({
            if (request != null && !pausedByKey && try { am.isMusicActive } catch (e: Exception) { false } && !TvRing.ringing) {
                key(am, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); pausedByKey = true
            }
        }, 700)
    }

    @Synchronized
    fun release(c: Context) {
        val r = request ?: return
        request = null
        val am = c.applicationContext.getSystemService(AudioManager::class.java) ?: return
        try { am.abandonAudioFocusRequest(r) } catch (_: Exception) {}
        // What was playing comes back: by itself with the focus, or with Play if it's still paused a moment later.
        if (wasPlaying) main.postDelayed({ if (!(try { am.isMusicActive } catch (e: Exception) { true })) key(am, android.view.KeyEvent.KEYCODE_MEDIA_PLAY) }, 900)
        pausedByKey = false; wasPlaying = false
        if (returnToShow) sendBackUntil = System.currentTimeMillis() + 4000
    }

    /** The call is up (the ring has stopped): a show that ignored focus and still plays gets Pause now. */
    @Synchronized
    fun live(c: Context) {
        if (request == null || !wasPlaying || pausedByKey) return
        val am = c.applicationContext.getSystemService(AudioManager::class.java) ?: return
        if (try { am.isMusicActive } catch (e: Exception) { false }) { key(am, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); pausedByKey = true }
    }

    /** The call screen closes: behind whatever was on screen when the call came, else just closes. */
    fun leave(a: android.app.Activity) {
        if (returnToShow) try { a.moveTaskToBack(true) } catch (_: Exception) {}
        a.finish()
    }

    /** MainActivity asks when it comes up: true right after a call that came in over a show (it steps back behind it). */
    fun sendBack(): Boolean = (System.currentTimeMillis() < sendBackUntil).also { if (it) sendBackUntil = 0 }

    private fun key(am: AudioManager, code: Int) {
        try {
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        } catch (_: Exception) {}
    }
}
