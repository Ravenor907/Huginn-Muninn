package io.github.ravenor907.muninn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cap v3 (protocol.md 29): sends a picked video to a group or one person. Each member gets what GroupVideo.plan says:
 * the full copy straight (FileLink, its own card per member), or the relay copy (one group message to those members,
 * its pieces paced). A full copy that can't go straight within 30 s falls back to the relay copy for that member.
 *
 * Round 19: a send runs in Muninn's own scope ([start]), not the sheet's, so a layout change (split screen, DeX, a turn),
 * the lock or leaving the chat doesn't stop it part way. [jobs] holds each chat's picked video and how far its send is;
 * the sheet shows that and finds it again when it comes back. A send that fails stays there with its reason until seen.
 */
object VideoSend {
    /** A group [g] (and channel [ch]) or, with [g] empty, the chat with [peer]. */
    data class Target(val g: String, val ch: String, val peer: String) {
        val group get() = g.isNotEmpty()
    }

    /**
     * A chat's picked video: [working] (what's happening, empty before Send) and [progress] while it's on its way, or
     * [failed] (what to tell the person) once a send didn't work.
     */
    data class Job(val uri: Uri, val working: String = "", val progress: Float = 0f, val failed: String = "", val hidden: Boolean = false) {
        val running get() = working.isNotEmpty()
    }

    /** What a send says: [ok] false when nothing went. */
    data class Sent(val ok: Boolean, val said: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _jobs = MutableStateFlow<Map<Target, Job>>(emptyMap())
    /** Each chat's picked video and its send, if any. */
    val jobs: StateFlow<Map<Target, Job>> = _jobs.asStateFlow()

    private fun change(f: (Map<Target, Job>) -> Map<Target, Job>) = synchronized(lock) { _jobs.value = f(_jobs.value) }

    /** A video was picked for [t]: its sheet opens (and comes back after the window is rebuilt). A running send stays. */
    fun picked(t: Target, uri: Uri) = change { m -> if (m[t]?.running == true) m else m + (t to Job(uri)) }

    /** [t]'s running send: its sheet hidden ([hide]) or shown again. */
    fun hide(t: Target, hide: Boolean) = change { m -> m[t]?.takeIf { it.running }?.let { m + (t to it.copy(hidden = hide)) } ?: m }

    /** [t]'s sheet was closed (Cancel, or Close after a failure). A running send can't be closed, only hidden. */
    fun closed(t: Target) = change { m -> if (m[t]?.running == true) m else m - t }

    /** A send is running for a chat other than [t]. */
    private fun busyElsewhere(t: Target) = _jobs.value.any { (k, j) -> k != t && j.running }

    /**
     * Starts sending [uri] to [t] in Muninn's own scope (see [send]). False when a send for [t] is already running. When
     * it's on its way it says so ([Chat.notices]) and the sheet closes; when it fails, the sheet keeps the reason.
     */
    fun start(app: Muninn, t: Target, me: String, uri: Uri, info: GroupVideo.Info, clip: Pair<Int, Int>?, inFull: Boolean): Boolean {
        synchronized(lock) {
            if (_jobs.value[t]?.running == true) return false
            _jobs.value = _jobs.value + (t to Job(uri, "Getting ready"))
        }
        scope.launch {
            val r = try {
                send(app, app.chat, t, me, uri, info, clip, inFull) { what, f ->
                    change { m -> m[t]?.let { j -> m + (t to j.copy(working = what, progress = f)) } ?: m }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Sent(false, "That video didn't send. Try again.") }
            catch (e: OutOfMemoryError) { Sent(false, "This phone ran out of memory making the copy. Try a shorter video.") }
            change { m -> if (r.ok) m - t else m + (t to Job(uri, failed = r.said)) }
            app.chat.notices.tryEmit(r.said)
        }
        return true
    }

    /** This phone is on a network that isn't charged by the byte (Wi-Fi, usually). */
    fun unmetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (_: Exception) { false }

    /** Everyone it would go to, as seen now. [me]: my chat key (left out). */
    fun members(chat: Chat, t: Target, me: String): List<GroupVideo.Member> =
        if (!t.group) listOf(chat.videoMember(t.peer, false))
        else chat.groups.group(t.g)?.state?.members?.map { it.k }?.filter { it != me }?.map { chat.videoMember(it, true) }.orEmpty()

    /** The plan for [t] now: who gets the full copy, who the relay copy, who nothing ([inFull]: a long video in full). */
    fun plan(context: Context, chat: Chat, t: Target, me: String, inFull: Boolean): Map<String, String> =
        GroupVideo.plan(members(chat, t, me), unmetered(context), inFull)

    private fun fileName(): String = "Video " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date()) + ".mp4"

    /**
     * Makes the copies and sends them. [clip]: a trim (start, end in ms) or null. [inFull]: a long video sent in full to
     * those who can take it straight (no relay copy). [step]: what's happening ("Making the smaller copy") and how far.
     * Returns what to tell the person. Runs from [start]; every copy has its location and camera details taken out.
     */
    private suspend fun send(context: Context, chat: Chat, t: Target, me: String, uri: Uri, info: GroupVideo.Info, clip: Pair<Int, Int>?,
                             inFull: Boolean, step: (String, Float) -> Unit): Sent {
        // Ultra privacy: sending videos is off entirely (receiving still works).
        if (UltraPrivacy.on) return Sent(false, "Off while Ultra privacy is on.")
        VideoPick.clearOld(context, busyElsewhere(t))
        val plan = plan(context, chat, t, me, inFull)
        if (plan.isEmpty()) return Sent(false, "Nobody else is here yet.")
        val full = plan.filterValues { it == GroupVideo.FULL }.keys
        val relay = plan.filterValues { it == GroupVideo.RELAY }.keys
        if (full.isEmpty() && relay.isEmpty()) return Sent(false, "Nobody here can get it in full now: they need the same Wi-Fi or Direct calls and files with you.")
        val ms = clip?.let { it.second - it.first } ?: info.ms
        val dir = VideoPick.dir(context)
        // The relay copy: for those getting it, and kept ready for anyone whose full copy can't go straight.
        var small: ByteArray? = null
        if (!inFull) {
            val f = File(dir, "relay-" + System.nanoTime() + ".mp4")
            step("Making the smaller copy", 0f)
            VideoPick.relayCopy(context, uri, info, f, clip) { step("Making the smaller copy", it) }?.let { f.delete(); return Sent(false, it) }
            small = try { f.readBytes() } catch (_: Exception) { null } finally { f.delete() }
            if (small == null) return Sent(false, "That video can't be read.")
        }
        var bigFile: File? = null
        if (full.isNotEmpty()) {
            val f = File(dir, "full-" + System.nanoTime() + ".mp4")
            step("Making the full-size copy", 0f)
            val why = VideoPick.fullCopy(context, uri, info, f, if (inFull) null else clip) { step("Making the full-size copy", it) }
            if (why != null) {
                f.delete()
                // No full copy: everyone gets the relay copy when there is one.
                if (small == null) return Sent(false, why)
            } else bigFile = f
        }
        step("Sending", 1f)
        val name = fileName()
        val notes = ArrayList<String>()
        if (t.group) {
            val lock = Any()
            var msgId: String? = null
            val relayNow = if (bigFile == null) relay + full else relay
            if (relayNow.isNotEmpty() && small != null) {
                val (id, why) = chat.sendGroupVideoClip(t.g, t.ch, small, ms, relayNow.toSet())
                if (why != null) notes.add(why)
                msgId = id
            }
            val clipBytes = small
            bigFile?.let { big ->
                for (k in full) {
                    val back: (() -> Unit)? = if (clipBytes == null) null else ({
                        synchronized(lock) {
                            val id = msgId
                            if (id == null) msgId = chat.sendGroupVideoClip(t.g, t.ch, clipBytes, ms, setOf(k)).first
                            else chat.addGroupVideoMember(t.g, id, k)
                            Unit
                        }
                    })
                    chat.sendVideoFull(k, big, name, ms, t.g, back)
                }
                big.delete()
            }
            val older = chat.groups.group(t.g)?.state?.members?.count { it.k != me && !chat.hasM2(it.k) } ?: 0
            if (older > 0) notes.add("$older ${if (older == 1) "person needs" else "people need"} a newer app to see videos.")
        } else {
            val big = bigFile
            val clipBytes = small
            if (big != null && t.peer in full) {
                chat.sendVideoFull(t.peer, big, name, ms, "", if (clipBytes == null) null else ({ chat.sendVideoClip(t.peer, clipBytes, ms)?.let { chat.notices.tryEmit(it) } }))
                big.delete()
            } else if (clipBytes != null) chat.sendVideoClip(t.peer, clipBytes, ms)?.let { notes.add(it) }
        }
        val sentFull = if (bigFile == null) emptySet() else full
        val sentRelay = if (bigFile == null) relay + full else relay
        return Sent(true, (listOf(when {
            sentFull.isEmpty() -> "Sending. Its pieces go a few a second, so it can take a few minutes."
            sentRelay.isEmpty() -> "Sending full size straight."
            else -> "Sending: full size straight to ${sentFull.size}, a smaller copy to ${sentRelay.size}."
        }) + notes).joinToString(" "))
    }
}
