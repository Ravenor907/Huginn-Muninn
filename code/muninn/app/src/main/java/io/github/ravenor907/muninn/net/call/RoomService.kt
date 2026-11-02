package io.github.ravenor907.muninn.net.call

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.R
import io.github.ravenor907.muninn.screens.RoomActivity
import io.github.ravenor907.muninn.ui.Panic

/**
 * Keeps a voice room or group call alive with the screen off or Muninn in the background: a microphone
 * foreground service with Android's call-style notification (Mute, Deafen and Leave from it, the lock screen too).
 */
class RoomService : Service() {
    private var foreground = false
    private var lastTitle = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ui = RoomManager.room.value
        val title = ui?.let { "${it.title} ${it.groupName} ${it.connected} ${it.muted} ${it.deafened} ${it.held} ${it.listenOnly}" } ?: ""
        if (intent?.action == UPDATE && foreground && title == lastTitle) return START_NOT_STICKY
        lastTitle = title
        val n = RoomRinger.ongoing(this, ui)
        // Listening only (no microphone permission): Android refuses the microphone type, so just the call type.
        val mic = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val types = when {
            Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            else -> 0
        }
        try { ServiceCompat.startForeground(this, RoomRinger.ONGOING_ID, n, types); foreground = true }
        catch (e: Exception) {
            try { if (Build.VERSION.SDK_INT >= 30) { ServiceCompat.startForeground(this, RoomRinger.ONGOING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); foreground = true } }
            catch (e2: Exception) {}
        }
        if (ui == null || !foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        NotificationManagerCompat.from(this).cancel(RoomRinger.ONGOING_ID)
        super.onDestroy()
    }

    companion object {
        private const val UPDATE = "io.github.ravenor907.muninn.room.UPDATE"
        fun start(c: Context) { try { ContextCompat.startForegroundService(c, Intent(c, RoomService::class.java)) } catch (_: Exception) {} }
        fun update(c: Context) { try { c.startService(Intent(c, RoomService::class.java).setAction(UPDATE)) } catch (_: Exception) {} }
        fun stop(c: Context) { try { c.stopService(Intent(c, RoomService::class.java)) } catch (_: Exception) {} }
    }
}

/** Decline a group call from its ringing notification; mute, deafen or leave a room from the ongoing one. */
class RoomActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val r = RoomManager.room.value
        when (intent.action) {
            DECLINE -> RoomManager.declineRing()
            LEAVE -> RoomManager.leave()
            MUTE -> if (r != null) RoomManager.setMuted(!r.muted)
            DEAFEN -> if (r != null) RoomManager.setDeafened(!r.deafened)
            STAY -> { RoomManager.stay(); RoomRinger.stillHereGone(context) }
        }
    }

    companion object {
        const val DECLINE = "io.github.ravenor907.muninn.room.DECLINE"
        const val LEAVE = "io.github.ravenor907.muninn.room.LEAVE"
        const val MUTE = "io.github.ravenor907.muninn.room.MUTE"
        const val DEAFEN = "io.github.ravenor907.muninn.room.DEAFEN"
        const val STAY = "io.github.ravenor907.muninn.room.STAY"
    }
}

/** The group call's ring (full screen, the raven call as the ringtone, like a 1:1 call) and the ongoing notification. */
object RoomRinger {
    const val RING_ID = 0x4351
    const val ONGOING_ID = 0x4352
    const val STILL_HERE_ID = 0x4353
    const val LEFT_ID = 0x4354

    private fun shown(c: Context, text: String): String? {
        val preview = try { (c.applicationContext as Muninn).identity.showPreview } catch (e: Exception) { false }
        return if (preview && !Panic.locked.value) text else null
    }

    private fun screen(c: Context, action: String) = PendingIntent.getActivity(c, action.hashCode(),
        Intent(c, RoomActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(c: Context, action: String) = PendingIntent.getBroadcast(c, action.hashCode(),
        Intent(c, RoomActions::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun ring(c: Context, name: String, group: String) {
        Ringer.channels(c)
        val who = shown(c, if (group.isNotEmpty()) "$name · $group" else name)
        val person = Person.Builder().setName(who ?: "Muninn group call").setImportant(true).build()
        val n = NotificationCompat.Builder(c, Ringer.CALLS).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(who ?: "Muninn").setContentText("Group call")
            .setCategory(NotificationCompat.CATEGORY_CALL).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setOngoing(true).setAutoCancel(false)
            .setContentIntent(screen(c, RoomActivity.SHOW))
            .setFullScreenIntent(screen(c, RoomActivity.SHOW), true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, action(c, RoomActions.DECLINE), screen(c, RoomActivity.ANSWER)))
            .setTimeoutAfter(RoomLogic.RING_MS + 2_000)
            .build()
        try { NotificationManagerCompat.from(c).notify(RING_ID, n) } catch (_: SecurityException) {}
        Ringer.sound(c, true)
    }

    fun stop(c: Context) {
        NotificationManagerCompat.from(c).cancel(RING_ID)
        Ringer.sound(c, false)
    }

    /** What the ongoing notification says: who's there, and Muted, Deafened or On a phone call. */
    private fun text(ui: RoomManager.Ui?): String {
        if (ui == null) return ""
        val who = if (ui.connected == 0) "Waiting for others" else "${ui.connected + 1} people"
        val me = when {
            ui.held.isNotEmpty() -> ui.held
            ui.deafened -> "Deafened"
            ui.listenOnly -> "Listening only"
            ui.muted -> "Muted"
            else -> ""
        }
        return if (me.isEmpty()) who else "$who · $me"
    }

    /** Mute and Deafen (Leave is the call style's own red button). Listening only has no microphone to unmute. */
    private fun NotificationCompat.Builder.roomActions(c: Context, ui: RoomManager.Ui?): NotificationCompat.Builder {
        if (ui == null) return this
        if (!ui.listenOnly) addAction(NotificationCompat.Action.Builder(R.drawable.ic_notify, if (ui.muted) "Unmute" else "Mute", action(c, RoomActions.MUTE)).build())
        addAction(NotificationCompat.Action.Builder(R.drawable.ic_notify, if (ui.deafened) "Undeafen" else "Deafen", action(c, RoomActions.DEAFEN)).build())
        return this
    }

    fun ongoing(c: Context, ui: RoomManager.Ui?): android.app.Notification {
        Ringer.channels(c)
        val title = ui?.let { shown(c, if (it.groupName.isNotEmpty()) "${it.title} · ${it.groupName}" else it.title) } ?: "Muninn voice room"
        val person = Person.Builder().setName(title).setImportant(true).build()
        // The lock screen hides a private notification's actions: this public copy names nothing but keeps Mute,
        // Deafen and Leave there.
        val public = NotificationCompat.Builder(c, Ringer.CALLS).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Muninn voice room").setContentText(text(ui))
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(screen(c, RoomActivity.SHOW))
            .roomActions(c, ui)
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_notify, "Leave", action(c, RoomActions.LEAVE)).build())
            .build()
        val b = NotificationCompat.Builder(c, Ringer.CALLS).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text(ui))
            .setCategory(NotificationCompat.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(screen(c, RoomActivity.SHOW))
            .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, action(c, RoomActions.LEAVE)))
            .roomActions(c, ui)
        if (ui != null) b.setUsesChronometer(true).setWhen(ui.since)
        return b.build()
    }

    /** Alone in the room for a while: "Still here?", with I'm here and Leave. */
    fun stillHere(c: Context, ui: RoomManager.Ui) {
        val title = shown(c, ui.title) ?: "Voice room"
        Ringer.channels(c)
        // On the calls channel: a muted messages channel or Do not disturb for messages doesn't hide it.
        val n = NotificationCompat.Builder(c, Ringer.CALLS).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Still here?")
            .setContentText("You're the only one in $title. Muninn leaves in ${RoomLogic.ASK_MS / 60_000} minutes.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setAutoCancel(true)
            .setContentIntent(screen(c, RoomActivity.SHOW))
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_notify, "I'm here", action(c, RoomActions.STAY)).build())
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_notify, "Leave", action(c, RoomActions.LEAVE)).build())
            .setTimeoutAfter(RoomLogic.ASK_MS)
            .build()
        try { NotificationManagerCompat.from(c).notify(STILL_HERE_ID, n) } catch (_: SecurityException) {}
    }

    /** Muninn left [room] after "Still here?" went unanswered: a note that says so, until it's tapped or cleared. */
    fun leftAlone(c: Context, room: String) {
        val title = shown(c, room)?.takeIf { it.isNotEmpty() }
        Ringer.channels(c)
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            PendingIntent.getActivity(c, LEFT_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val n = NotificationCompat.Builder(c, Ringer.CALLS).setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (title != null) "You left $title" else "You left the voice room")
            .setContentText("Nobody else came, so Muninn left after asking \"Still here?\". Join again any time.")
            .setCategory(NotificationCompat.CATEGORY_STATUS).setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setAutoCancel(true)
            .apply { if (open != null) setContentIntent(open) }
            .build()
        try { NotificationManagerCompat.from(c).notify(LEFT_ID, n) } catch (_: SecurityException) {}
    }

    fun stillHereGone(c: Context) { try { NotificationManagerCompat.from(c).cancel(STILL_HERE_ID) } catch (_: Exception) {} }
}
