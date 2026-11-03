package io.github.ravenor907.muninn.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.ravenor907.muninn.MainActivity
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R

/**
 * Reply straight from a message notification (Android's inline reply). The text is sent like one typed in
 * the chat; the notification goes once it's on its way. Only offered (and only sent) while
 * Muninn is open, not panic-locked, not a Kids' TV, and the app lock isn't due.
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? Muninn ?: return
        val key = intent.getStringExtra(KEY) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TEXT)?.toString()?.trim().orEmpty()
        val nm = NotificationManagerCompat.from(context)
        // Checked again here: the lock may have come due (or Kids' TV mode come on) since the notification showed.
        if (text.isEmpty()) { nm.cancel(key.hashCode()); return }
        // Not sent: say so in its place, so the reply doesn't look sent (nothing about the chat shows on it).
        // Muninn may have just restarted for this: everything is running before it's checked (starting goes off the main thread now).
        try { app.ensureStarted() } catch (_: Exception) {}
        // Refused: the typed reply waits in the chat's box (Drafts), so nothing typed is lost.
        if (!app.canReply()) { try { Drafts.keepReply(app, key, text) } catch (_: Exception) {}; notSent(context, key); return }
        val pending = goAsync()
        Thread {
            try { app.chat.replyFromNotification(key, text) } catch (_: Exception) {}
            nm.cancel(key.hashCode())
            pending.finish()
        }.start()
    }

    private fun notSent(context: Context, key: String) {
        val open = PendingIntent.getActivity(context, key.hashCode(), Intent(context, MainActivity::class.java).putExtra("chat", key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, Muninn.QUIET).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Muninn").setContentText("Unlock Muninn to send your reply. It waits in the chat.")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
            .setTimeoutAfter(NOT_SENT_MS).build()
        try { NotificationManagerCompat.from(context).notify(key.hashCode(), n) } catch (_: SecurityException) {}
    }

    companion object {
        /** How long "Unlock Muninn to send your reply. It waits in the chat." stays. */
        private const val NOT_SENT_MS = 60_000L
        const val KEY = "key"
        const val TEXT = "reply"
        const val ACTION = "io.github.ravenor907.muninn.REPLY"
    }
}
