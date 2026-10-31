package io.github.ravenor907.muninn.net.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.screens.CallActivity
import io.github.ravenor907.muninn.ui.Panic

/**
 * Keeps a call alive with the screen off or Muninn in the background: a phone-call and microphone
 * foreground service, with Android's call-style notification (hang up from it).
 */
class CallService : Service() {
    private var lastPhase: CallSession.Phase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var foreground = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ui = CallManager.call.value
        if (intent?.action == UPDATE && foreground && lastPhase == ui?.phase) return START_NOT_STICKY
        lastPhase = ui?.phase
        // Always go foreground first: a service started with startForegroundService must, even to stop at once.
        val n = if (ui != null) Ringer.ongoing(this, ui) else Ringer.placeholder(this)
        val types = when {
            Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            else -> 0
        }
        try { ServiceCompat.startForeground(this, Ringer.ONGOING_ID, n, types); foreground = true }
        catch (e: Exception) {
            // Android said no to the phone-call type: keep the microphone at least.
            try { if (Build.VERSION.SDK_INT >= 30) { ServiceCompat.startForeground(this, Ringer.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); foreground = true } }
            catch (e2: Exception) {}
        }
        if (ui == null || ui.phase == CallSession.Phase.ENDED || !foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(Ringer.ONGOING_ID)
        super.onDestroy()
    }

    companion object {
        private const val UPDATE = "io.github.ravenor907.muninn.call.UPDATE"

        /** Call from a visible screen (starting or answering a call): Android only allows the microphone then. */
        fun start(c: Context) { try { ContextCompat.startForegroundService(c, Intent(c, CallService::class.java)) } catch (_: Exception) {} }
        fun update(c: Context) {
            if (CallManager.call.value?.phase == CallSession.Phase.INCOMING) return
            try { c.startService(Intent(c, CallService::class.java).setAction(UPDATE)) } catch (_: Exception) {}
        }
        fun stop(c: Context) { try { c.stopService(Intent(c, CallService::class.java)) } catch (_: Exception) {} }
    }
}

/** Decline from the ringing notification, hang up from the ongoing one. */
class CallActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DECLINE -> CallManager.decline()
            HANGUP -> CallManager.hangup()
        }
    }

    companion object {
        const val DECLINE = "io.github.ravenor907.muninn.call.DECLINE"
        const val HANGUP = "io.github.ravenor907.muninn.call.HANGUP"
    }
}

/**
 * The incoming ring: a full-screen call notification (CallStyle on Android 12+), the raven call looping as
 * the ringtone, and vibration, following the phone's ring mode. Also the missed-call notification.
 */
object Ringer {
    const val CALLS = "raven_calls"
    const val MISSED = "raven_missed"
    const val RING_ID = 0x4341
    const val ONGOING_ID = 0x4342
    private val PATTERN = longArrayOf(0, 700, 500, 700, 1200)
    private var player: MediaPlayer? = null
    private var vibrating = false
    private val gap = android.os.Handler(android.os.Looper.getMainLooper())

    fun channels(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        // No channel sound: the ring loops below, and stops the moment the call is answered or ends.
        nm.createNotificationChannel(NotificationChannel(CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Incoming calls and the call going on."
            setSound(null, null); enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
        nm.createNotificationChannel(NotificationChannel(MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Calls you didn't pick up."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        })
    }

    /** The name, unless previews are off or Muninn is panic-locked. */
    private fun shown(c: Context, name: String): String? {
        val preview = try { (c.applicationContext as Muninn).identity.showPreview } catch (e: Exception) { false }
        return if (preview && !Panic.locked.value) name else null
    }

    private fun screen(c: Context, action: String) = PendingIntent.getActivity(c, action.hashCode(),
        Intent(c, CallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(c: Context, action: String) = PendingIntent.getBroadcast(c, action.hashCode(),
        Intent(c, CallActions::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** [name] empty: a call that rang while Muninn is locked (cap b1), shown as "Incoming call" with no name. */
    fun ring(c: Context, callId: String, name: String, key: String) {
        channels(c)
        val who = if (name.isEmpty()) null else shown(c, name)
        val person = Person.Builder().setName(who ?: "Muninn call").setImportant(true).build()
        val n = NotificationCompat.Builder(c, CALLS).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(who ?: "Muninn").setContentText("Incoming call")
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).setAutoCancel(false)
            .setContentIntent(screen(c, CallActivity.SHOW))
            .setFullScreenIntent(screen(c, CallActivity.SHOW), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, action(c, CallActions.DECLINE), screen(c, CallActivity.ANSWER)))
            .setTimeoutAfter(CallLogic.RING_MS + 2_000)
            .build()
        try { NotificationManagerCompat.from(c).notify(RING_ID, n) } catch (_: SecurityException) {}
        // Allowed to show over other apps (a Kids' TV, or a phone that said yes): open the call screen straight
        // away, so it doesn't depend on the full-screen calls permission some devices (Google TV) can't grant.
        if (android.provider.Settings.canDrawOverlays(c)) try {
            c.startActivity(Intent(c, CallActivity::class.java).setAction(CallActivity.SHOW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (_: Exception) {}
        sound(c, true)
    }

    fun ongoing(c: Context, ui: CallManager.Ui): android.app.Notification {
        channels(c)
        val who = shown(c, ui.name)
        val person = Person.Builder().setName(who ?: "Muninn call").setImportant(true).build()
        val b = NotificationCompat.Builder(c, CALLS).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(who ?: "Muninn call")
            .setContentText(when (ui.phase) {
                CallSession.Phase.CALLING -> "Calling"
                CallSession.Phase.RINGING -> "Ringing"
                CallSession.Phase.CONNECTING -> "Connecting"
                CallSession.Phase.RECONNECTING -> "Reconnecting"
                else -> "On a call"
            })
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(screen(c, CallActivity.SHOW))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, action(c, CallActions.HANGUP)))
        if (ui.connectedAt > 0) b.setUsesChronometer(true).setWhen(ui.connectedAt)
        return b.build()
    }

    /** Only while a foreground start has nothing to show yet. */
    fun placeholder(c: Context): android.app.Notification {
        channels(c)
        return NotificationCompat.Builder(c, CALLS).setSmallIcon(R.drawable.ic_notify).setContentTitle("Muninn call")
            .setCategory(NotificationCompat.CATEGORY_CALL).setSilent(true).build()
    }

    fun stop(c: Context) {
        NotificationManagerCompat.from(c).cancel(RING_ID)
        sound(c, false)
    }

    /** [key]: the chat it opens (the caller, or "g:" and a group for a group call). */
    fun missed(c: Context, name: String, key: String) {
        // Extreme battery saver: no notifications; the chat still shows it.
        if (try { (c.applicationContext as Muninn).power.manual() } catch (_: Exception) { false }) return
        channels(c)
        val who = shown(c, name)
        val open = PendingIntent.getActivity(c, key.hashCode() + 1, Intent(c, io.github.ravenor907.muninn.MainActivity::class.java)
            .putExtra("chat", key).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, MISSED).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Missed call").setContentText(who ?: "Someone called you")
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(open).build()
        try { NotificationManagerCompat.from(c).notify(("missed" + key).hashCode(), n) } catch (_: SecurityException) {}
    }

    /** The raven call on loop plus vibration, as the ring mode allows (silent: neither; vibrate: buzz only). */
    @Synchronized
    internal fun sound(c: Context, on: Boolean) {
        if (io.github.ravenor907.muninn.tv.TvRing.instead(c, on)) return   // a Kids' TV rings at its own volume
        val vib = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)
        if (!on) {
            player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            player = null
            if (vibrating) { try { vib?.cancel() } catch (_: Exception) {}; vibrating = false }
            return
        }
        // Do not disturb (Your profile): the call shows, but neither rings nor buzzes.
        val dnd = try { (c.applicationContext as Muninn).doNotDisturb() } catch (_: Exception) { false }
        val mode = if (dnd) AudioManager.RINGER_MODE_SILENT else c.getSystemService(AudioManager::class.java).ringerMode
        if (mode != AudioManager.RINGER_MODE_SILENT) try {
            vib?.vibrate(VibrationEffect.createWaveform(PATTERN, 0)); vibrating = true
        } catch (_: Exception) {}
        if (mode == AudioManager.RINGER_MODE_NORMAL && player == null) try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(c, Uri.parse("android.resource://${c.packageName}/${R.raw.raven_message}"))
                // The raven calls, pauses a breath, calls again: like a ring, not one endless sound.
                setOnCompletionListener { mp -> gap.postDelayed({ if (player === mp) try { mp.start() } catch (_: Exception) {} }, 1_200) }
                prepare(); start()
            }
        } catch (_: Exception) { player?.release(); player = null }
    }
}
