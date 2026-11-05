package io.github.ravenor907.muninn.net

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R

/**
 * Checks the internet inbox while Muninn isn't listening live: closed, or with Android's Battery
 * Saver (or Doze) on, when Muninn drops its live connections: connect, wait for what's waiting, close.
 */
class InboxWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val app = applicationContext as Muninn
        if (app.visible || app.nostr.listening()) return Result.success()   // already listening
        // Quiet hours may have ended or the phone charged: the mode in use (and this schedule) catch up first.
        app.power.refresh()
        // "Only when open", chosen or for quiet hours: nothing until Muninn is opened.
        if (app.power.effective() == PowerLogic.Check.OPEN) return Result.success()
        app.power.countCheck()
        MessageService.start(app)   // allowed again once Muninn may run in the background
        // Locked with a password: only says "New message"; nothing is opened until it's unlocked.
        app.nostr.checkInbox()   // every relay, closing again by itself unless one is kept open
        Thread.sleep(20_000)
        return Result.success()
    }

    /** Before Android 12 an urgent check runs as a short foreground job, which needs a notification. */
    override fun getForegroundInfo(): ForegroundInfo {
        MessageService.ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, MessageService.CHANNEL).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Muninn").setContentText("Checking for messages").setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN).build()
        return ForegroundInfo(10, n)
    }
}
