package io.github.ravenor907.muninn.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.R

/** Keeps a transfer going when the screen turns off or Muninn goes to the background. */
class NearbyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_ON, false) != true) {
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_transfer), NotificationManager.IMPORTANCE_LOW))
        val n = build(this, "Transferring files", -1)
        running = true
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        // Awake for about 10 minutes at a time, renewed while the transfer moves: a stalled one lets the phone sleep.
        if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muninn:transfer")
            .apply { setReferenceCounted(false) }
        renewed = 0L
        progress()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        try { getSystemService(NotificationManager::class.java)?.cancel(ID) } catch (_: Exception) {}
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val EXTRA_ON = "on"
        private const val AWAKE_MS = 10 * 60_000L
        @Volatile private var wake: PowerManager.WakeLock? = null
        @Volatile private var renewed = 0L
        private const val ID = 7
        @Volatile private var running = false
        @Volatile private var notedAt = 0L

        /** The ongoing notification: [text] ("Sending game.apk") and a bar at [percent] (below 0: none). */
        private fun build(c: Context, text: String, percent: Int) = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (percent >= 0) "$text · $percent%" else c.getString(R.string.app_name)).setContentText(if (percent >= 0) c.getString(R.string.app_name) else text)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .apply { if (percent >= 0) setProgress(100, percent.coerceIn(0, 100), false) }
            .setContentIntent(PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
            .build()

        /** How far the transfer is, on its notification too (at most once a second), while the service runs. */
        fun note(context: Context, text: String, percent: Int) {
            if (!running) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - notedAt < 1000L) return
            notedAt = now
            try { context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context.applicationContext, text, percent)) } catch (_: Exception) {}
        }

        /** The transfer moved: keep the phone awake another 10 minutes (renewed at most once a minute). */
        fun progress() {
            val w = wake ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            if (renewed != 0L && now - renewed < 60_000L) return
            renewed = now
            try { w.acquire(AWAKE_MS) } catch (_: Exception) {}
        }

        fun hold(context: Context, on: Boolean) {
            try {
                val i = Intent(context, NearbyService::class.java).putExtra(EXTRA_ON, on)
                if (on) ContextCompat.startForegroundService(context, i) else context.stopService(i)
            } catch (_: Exception) {
                // Android may refuse from the background; the transfer still runs while Muninn is open.
            }
        }
    }
}
