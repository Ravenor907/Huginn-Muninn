package io.github.ravenor907.muninn.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R

/**
 * Keeps Muninn listening for messages while it's closed, so they arrive (and notify) right away
 * instead of when Android next lets the app run. No Google push servers: the phone stays
 * connected to the relays itself. Android requires a small ongoing notification for this; its
 * channel is silent and minimised, and can be hidden in the phone's settings.
 */
class MessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as Muninn
        ensureChannel(this)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("Muninn")
            .setContentText("Waiting for new messages · Tap to hide this").setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE)
            // A tap (or Hide) opens Android's page for this one channel, where it's turned off (as Settings › Notifications ›
            // Hide the waiting notification). Messages arrive either way.
            .setContentIntent(hideIntent(this@MessageService) ?: open)
            .apply { hideIntent(this@MessageService)?.let { addAction(0, "Hide", it) } }
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (e: Exception) {
            // Android must see startForeground after startForegroundService, or it closes the app.
            try { startForeground(ID, n) } catch (_: Exception) {}
            try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf(); return START_NOT_STICKY
        }
        if (!app.identity.stayConnected) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        running = true
        app.listen()
        return START_STICKY   // if Android stops it for memory, it comes back
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    companion object {
        const val CHANNEL = "listening_quiet"
        private const val ID = 9
        @Volatile var running = false

        fun start(context: Context) {
            val app = context.applicationContext as Muninn
            if (!app.identity.stayConnected) return
            // Quiet hours using a scheduled check: it takes over until "Right away" is back (Power starts it then).
            if (app.power.effective() != PowerLogic.Check.LIVE && !app.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)) return
            try { ContextCompat.startForegroundService(context, Intent(context, MessageService::class.java)) } catch (_: Exception) {}
        }

        /** The quiet "Waiting for new messages" channel, made before anything posts to it (this service, the inbox check). */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            // The "listening" channel shows on the lock screen and channels can't be changed, so it is replaced.
            nm.deleteNotificationChannel("listening")
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Waiting for new messages", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps Muninn connected so messages arrive right away. Turn this one off to hide it; Muninn keeps listening and message notifications still come."
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                setSound(null, null)
                enableVibration(false)
            })
        }

        /** Android 8 and newer: opens this channel's own settings page. Null before (no channels). */
        private fun hideIntent(context: Context): PendingIntent? {
            if (Build.VERSION.SDK_INT < 26) return null
            val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun stop(context: Context) { context.stopService(Intent(context, MessageService::class.java)) }

        /** Without this, Android's battery saver cuts the connection while the phone sleeps. */
        fun unrestricted(context: Context) =
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

        @android.annotation.SuppressLint("BatteryLife")
        fun askUnrestricted(context: Context) {
            try {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
    }
}

/** After the phone restarts or Muninn updates, or a Kids' Device's safe mode tries a normal start, start listening again. */
class StartAtBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == io.github.ravenor907.muninn.tv.SafeStart.TRY_AGAIN) {
            MessageService.start(context)
            // 1.45: a Kids' TV's quiet update service and its 30 minute check.
            try { io.github.ravenor907.muninn.tv.TvUpdateService.start(context) } catch (_: Exception) {}
        }
    }
}
