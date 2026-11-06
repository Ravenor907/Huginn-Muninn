package io.github.ravenor907.muninn.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.ravenor907.muninn.net.call.CallManager
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Round 17: on a Kids' TV a call is never live with the microphone on and nothing on screen. A TV has no status bar or
 * notification shade, so a call behind another app (Home, the Assistant key) or behind a dark screen (the TV turned off)
 * would carry on unseen. So:
 * - the call screen gone mid-call (calling out, or on): a line says it ends in 10 seconds, and it ends unless Muninn is
 *   opened again by then. A ring is left alone (it can't be heard by anyone).
 * - the screen turned off (HDMI-CEC puts the box to sleep): a ring is declined, a call hung up.
 * - no key pressed for half an hour (a TV turned off without HDMI-CEC can't tell Muninn): "Still talking?", and the call
 *   ends a minute later with no answer.
 * Only on a TV in kids' mode; a kids' tablet or phone works like any phone (its screen goes off at the ear).
 */
object TvCallGuard {
    /** The call screen is on screen (between its onStart and onStop). */
    @Volatile var shown = false
        private set
    /** The call screen went behind another app during this call: a show isn't sent Play at the end. */
    @Volatile var left = false
        private set
    /** "Still talking?" is up on the call screen. */
    val still = MutableStateFlow(false)
    private val main = Handler(Looper.getMainLooper())
    private var end: Runnable? = null
    @Volatile private var lastKey = 0L
    @Volatile private var askedAt = 0L
    private var receiver: BroadcastReceiver? = null
    private var ticking = false
    private var callId = ""

    private fun now() = SystemClock.elapsedRealtime()

    private fun on(c: Context): Boolean = TvMic.active && KidMode.isTv(c)

    /** The call screen came up. */
    fun shownNow() {
        shown = true
        left = false   // back on the call: a show paused for it plays again at its end
        touched()
        cancelEnd()
    }

    /** A key or a touch on the call screen: someone is there. */
    fun touched() {
        lastKey = now()
        askedAt = 0L
        if (still.value) still.value = false
    }

    /**
     * The call screen went away (its onStop): not for a rotation or its own close. A call going out or on ends in
     * [KidCallBack.HIDDEN_MS] unless Muninn comes back, and the TV says so at once.
     */
    fun hidden(a: Activity) {
        shown = false
        if (a.isChangingConfigurations || a.isFinishing || !on(a)) return
        val c = CallManager.call.value ?: return
        if (!KidCallBack.endsHidden(c.phase)) return
        left = true
        // The call stays in front: with "Show over other apps" allowed, the call screen comes straight back over whatever
        // took the screen (Home, the Assistant, a cast), quietly. The mic and sound are never touched here. Should it not
        // come back, the call still ends in 10 seconds as before (a TV call is never live with nothing on screen).
        val app = a.applicationContext
        val overlay = try { android.provider.Settings.canDrawOverlays(app) } catch (_: Exception) { false }
        if (overlay) {
            main.postDelayed({
                val now = CallManager.call.value
                if (!shown && now != null && now.callId == c.callId && KidCallBack.endsHidden(now.phase)) try {
                    app.startActivity(Intent(app, io.github.ravenor907.muninn.screens.CallActivity::class.java)
                        .setAction(io.github.ravenor907.muninn.screens.CallActivity.SHOW)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                } catch (_: Exception) {}
                // Android didn't let it come back (some versions need more): the TV says so, as before.
                main.postDelayed({
                    val still = CallManager.call.value
                    if (!shown && still != null && still.callId == c.callId && KidCallBack.endsHidden(still.phase))
                        try { android.widget.Toast.makeText(app, KidCallBack.hiddenText(c.name), android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
                }, 1_000L)
            }, BACK_MS)
        } else try { android.widget.Toast.makeText(app, KidCallBack.hiddenText(c.name), android.widget.Toast.LENGTH_LONG).show() } catch (_: Exception) {}
        endSoon()
    }

    /** How soon the call screen comes back over another app (long enough for that app's own start not to undo it). */
    private const val BACK_MS = 600L

    /** Every change of the call (from [TvRing.watch], on the main thread). */
    fun onCall(c: Context, ui: CallManager.Ui?) {
        val app = c.applicationContext
        if (ui == null || !KidCallBack.live(ui.phase) || !on(app)) {
            cancelEnd()
            stopWatching(app)
            if (still.value) still.value = false
            return
        }
        if (ui.callId != callId) { callId = ui.callId; left = false; touched() }
        watchScreen(app)
        // A call going out or on with no call screen (it never came up, or went away): ended as above, without the line
        // (the screen may be on its way up this moment; it cancels this when it shows).
        if (KidCallBack.endsHidden(ui.phase) && !shown && end == null) endSoon()
        if (shown) cancelEnd()
        tick()
    }

    private fun endSoon() {
        cancelEnd()
        val r = Runnable {
            end = null
            val c = CallManager.call.value
            if (!shown && c != null && KidCallBack.endsHidden(c.phase)) CallManager.hangup()
        }
        end = r
        main.postDelayed(r, KidCallBack.HIDDEN_MS)
    }

    private fun cancelEnd() { end?.let { main.removeCallbacks(it) }; end = null }

    /** The screen turned off (the TV off, with HDMI-CEC): a ring is declined, a call hung up. Registered only during a call. */
    private fun watchScreen(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                when (KidCallBack.screenOff(CallManager.call.value?.phase)) {
                    "decline" -> CallManager.decline()
                    "hangup" -> CallManager.hangup()
                }
            }
        }
        try {
            androidx.core.content.ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (_: Exception) {}
    }

    private fun stopWatching(app: Context) {
        val r = receiver ?: return
        receiver = null
        try { app.unregisterReceiver(r) } catch (_: Exception) {}
    }

    /** Every 15 seconds during a call: "Still talking?" after half an hour with no key, and the end a minute after that. */
    private fun tick() {
        if (ticking) return
        ticking = true
        main.postDelayed(object : Runnable {
            override fun run() {
                val c = CallManager.call.value
                if (c == null || !KidCallBack.endsHidden(c.phase)) { ticking = false; if (still.value) still.value = false; return }
                val t = now()
                if (askedAt == 0L && KidCallBack.stillDue(lastKey, t)) { askedAt = t; still.value = true }
                else if (KidCallBack.stillOver(askedAt, t)) { askedAt = 0L; still.value = false; CallManager.hangup() }
                main.postDelayed(this, 15_000L)
            }
        }, 15_000L)
    }
}
