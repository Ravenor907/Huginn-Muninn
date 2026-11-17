package io.github.ravenor907.muninn.tv

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.net.MessageService
import java.util.concurrent.TimeUnit

/**
 * 1.45: a Kids' TV keeps itself up to date while it sleeps. This quiet foreground service runs while kids' mode is on
 * a TV (started with kids' mode and at boot, back by itself if Android stops it), and a 30 minute background check
 * with a network ([TvUpdateWorker]) wakes it even with the screen off. The check and the install are [TvUpdate]'s.
 * On Android 11 and older a downloaded update waits for the screen to turn on (Android asks there), and this
 * service hears that.
 */
class TvUpdateService : Service() {
    private var screen: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as Muninn
        MessageService.ensureChannel(this)
        val open = PendingIntent.getActivity(this, 12, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, MessageService.CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("Muninn")
            .setContentText("Keeping Muninn up to date").setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open).build()
        try {
            // Android 14 and newer: the same type as the message service (a dataSync service started at boot is refused
            // on Android 15 and is limited to 6 hours a day).
            if (Build.VERSION.SDK_INT >= 34) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            else if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (e: Exception) {
            try { startForeground(ID, n) } catch (_: Exception) {}
            try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf(); return START_NOT_STICKY
        }
        if (!wanted(app)) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (screen == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_SCREEN_ON) TvUpdate.screenOn(c) }
            }
            try { ContextCompat.registerReceiver(this, r, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED); screen = r } catch (_: Exception) {}
        }
        schedule(this)
        return START_STICKY
    }

    override fun onDestroy() {
        screen?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screen = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 14
        private const val WORK = "tv-update"

        /** Kids' mode on a TV only. */
        fun wanted(c: Context): Boolean = try { val a = c.applicationContext as Muninn; KidMode.on(a) && KidMode.isTv(a) } catch (_: Exception) { false }

        fun start(c: Context) {
            if (!wanted(c)) return
            try { ContextCompat.startForegroundService(c, Intent(c, TvUpdateService::class.java)) } catch (_: Exception) {}
            schedule(c)
        }

        fun stop(c: Context) {
            try { c.stopService(Intent(c, TvUpdateService::class.java)) } catch (_: Exception) {}
            try { WorkManager.getInstance(c).cancelUniqueWork(WORK) } catch (_: Exception) {}
        }

        /** About every 30 minutes, only with a network, screen on or off. Kept across reboots by WorkManager. */
        fun schedule(c: Context) {
            try {
                val req = PeriodicWorkRequestBuilder<TvUpdateWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
                WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
            } catch (_: Exception) {}
        }
    }
}

/** The 30 minute check: brings the service back if it was stopped, then looks for an update and waits for it. */
class TvUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as Muninn
        if (!TvUpdateService.wanted(app)) return Result.success()
        TvUpdateService.start(app)
        // 1.55: all of it ends within [MAX_MS] of the start, whatever each wait takes: Android stops a background job at
        // 10 minutes (and counts that against the app).
        val end = System.currentTimeMillis() + MAX_MS
        // 1.55: this work may be what started Muninn (the TV's firmware can refuse the service): kids' mode starts on its
        // own thread once the stores are open, and a check asked before that would be dropped.
        val ready = System.currentTimeMillis() + 60_000L
        while (!TvUpdate.isStarted && System.currentTimeMillis() < ready && !isStopped) Thread.sleep(1_000)
        try { TvUpdate.periodic(app) } catch (_: Exception) {}
        // The work keeps the device awake: wait for the download and then for the install session to be written and
        // committed, so all of it finishes with the screen off. 1.55: the install starts on the main thread right after
        // the download, so it counts as done only when idle twice in a row.
        Thread.sleep(3_000)
        var idle = 0
        while (System.currentTimeMillis() < end && idle < 2 && !isStopped) {
            idle = if (app.updater.state.value.busy || TvUpdate.installing) 0 else idle + 1
            if (idle < 2) Thread.sleep(2_000)
        }
        return Result.success()
    }

    companion object {
        /** The longest one check runs, from its start: well under Android's 10 minutes for a background job. */
        const val MAX_MS = 8 * 60_000L
    }
}
