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
 * Cap v3 (protocol.md 29): sends a picked video to a group or one person. Each member gets what GroupVideo.plan says
 * (1.42 rules, never cut): the full copy direct (FileLink, its own card per member, no length cap), the 720p copy up to
 * 5 minutes through FileLink (Tor when there's no direct path; never the relays), or, for older apps only, the old relay
 * copy (one group message to those members, its pieces paced). Each copy is made once and shared by everyone getting it.
 * An older app's full copy that can't go straight within 30 s falls back to its relay copy, as before.
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
    fun picked(t: Target, uri: Uri) = change { m ->
        if (m[t]?.running == true) m else { m[t]?.uri?.takeIf { it != uri }?.let { dropCameraClip(it) }; m + (t to Job(uri)) }
    }

    /** Videos still to open for each chat (Saved media and the camera can pick several): one sheet at a time. */
    private val waiting = HashMap<Target, ArrayDeque<Uri>>()

    /**
     * Several videos for [t]: the first one's sheet opens now (or once [t]'s current one is on its way or put aside),
     * then the next, in order.
     */
    fun pickedMany(t: Target, uris: List<Uri>) {
        if (uris.isEmpty()) return
        synchronized(lock) { waiting.getOrPut(t) { ArrayDeque() }.addAll(uris) }
        next(t)
    }

    /** [t]'s next waiting video, once its sheet is free. */
    private fun next(t: Target) {
        synchronized(lock) {
            if (_jobs.value[t] != null) return
            val q = waiting[t] ?: return
            val u = q.removeFirstOrNull()
            if (q.isEmpty()) waiting.remove(t)
            if (u != null) _jobs.value = _jobs.value + (t to Job(u))
        }
    }

    /**
     * A clip the in-app camera recorded for sending (filesDir/camera-send, a file:// address) is this app's own: it's
     * deleted once its send is on its way (the copies are made by then) or its sheet is closed. Picked videos are never
     * touched.
     */
    private fun dropCameraClip(uri: Uri) {
        if (uri.scheme != "file") return
        val f = File(uri.path ?: return)
        if (f.parentFile?.name == "camera-send" && f.parentFile?.parentFile?.name == "files") f.delete()
    }

    /** [t]'s running send: its sheet hidden ([hide]) or shown again. */
    fun hide(t: Target, hide: Boolean) = change { m -> m[t]?.takeIf { it.running }?.let { m + (t to it.copy(hidden = hide)) } ?: m }

    /**
     * [t]'s sheet was closed (Cancel, or Close after a failure). A running send can't be closed, only hidden. [keepClip]:
     * a camera clip is still needed (Send as a file reads it; the camera's day-old sweep clears it later).
     */
    fun closed(t: Target, keepClip: Boolean = false) {
        change { m -> if (m[t]?.running == true) m else { if (!keepClip) m[t]?.let { dropCameraClip(it.uri) }; m - t } }
        next(t)
    }

    /** A send is running for a chat other than [t]. */
    private fun busyElsewhere(t: Target) = _jobs.value.any { (k, j) -> k != t && j.running }

    /**
     * Starts sending [uri] to [t] in Muninn's own scope (see [send]). False when a send for [t] is already running. When
     * it's on its way it says so ([Chat.notices]) and the sheet closes; when it fails, the sheet keeps the reason.
     */
    fun start(app: Muninn, t: Target, me: String, uri: Uri, info: GroupVideo.Info): Boolean {
        synchronized(lock) {
            if (_jobs.value[t]?.running == true) return false
            _jobs.value = _jobs.value + (t to Job(uri, "Getting ready"))
        }
        scope.launch {
            val r = try {
                send(app, app.chat, t, me, uri, info) { what, f ->
                    change { m -> m[t]?.let { j -> m + (t to j.copy(working = what, progress = f)) } ?: m }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { Sent(false, "That video didn't send. Try again.") }
            catch (e: OutOfMemoryError) { Sent(false, "This phone ran out of memory making the copy.") }
            change { m -> if (r.ok) m - t else m + (t to Job(uri, failed = r.said)) }
            if (r.ok) { dropCameraClip(uri); next(t) }
            app.chat.notices.tryEmit(r.said)
        }
        return true
    }

    /** This phone is on a network that isn't charged by the byte (Wi-Fi, usually). */
    fun unmetered(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (_: Exception) { false }

    /**
     * [k] as a video's sender sees them now: Chat.videoMember, with [GroupVideo.Member.newPath] from their caps, and trust
     * only while this phone may go straight over the internet (Ultra privacy: the same Wi-Fi or Tor only).
     */
    fun member(chat: Chat, k: String, group: Boolean): GroupVideo.Member = chat.videoMember(k, group).let {
        val h = FileLink.host
        // Family and trusted, with an app that can go through the call relay: full quality, whatever the length and the
        // network, until the relay's month is used up or Data saver says otherwise.
        val family = h != null && FileLink.mayAlways(h.badge(k)) && MediaRoute.relayMay(h.routeFor(k, ""), !UltraPrivacy.directAllowed, true, h.hasF5(k), h.relayOver()) && h.mayDownload()
        it.copy(trusted = it.trusted && UltraPrivacy.directAllowed, newPath = GroupVideo.NEW_PATH_CAP in chat.capsOf(k) || h?.hasF4(k) == true, family = family)
    }

    /** Everyone it would go to, as seen now. [me]: my chat key (left out). */
    fun members(chat: Chat, t: Target, me: String): List<GroupVideo.Member> =
        if (!t.group) listOf(member(chat, t.peer, false))
        else chat.groups.group(t.g)?.state?.members?.map { it.k }?.filter { it != me }?.map { member(chat, it, true) }.orEmpty()

    /** The plan for [t] now, for a video [ms] long: who gets the full copy, the 720p copy, the relay copy or nothing. */
    fun plan(context: Context, chat: Chat, t: Target, me: String, ms: Int): Map<String, String> =
        GroupVideo.plan(members(chat, t, me), unmetered(context), ms)

    private fun fileName(): String = "Video " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date()) + ".mp4"

    /** Full and 720p copies waiting to go (FileLink deletes each when its transfer ends; Chat sweeps old ones), as Chat's. */
    private fun videosOut(context: Context): File = File(context.noBackupFilesDir, "videos-out").also { it.mkdirs() }

    /**
     * [file] (a full or 720p copy, [ms] long) to [k] through FileLink with its own card, in group [g] or ("") the chat with
     * them. [vid]: the offer's "vid" (0: a plain file offer, for a kids' device, which declines video offers). [back]:
     * an older app's relay copy if the full copy can't go straight. [file] stays mine.
     */
    private fun offer(context: Context, chat: Chat, k: String, file: File, name: String, vid: Int, g: String, back: (() -> Unit)?) {
        val clean = chat.cleanFileName(name)
        val size = file.length()
        // One file for every member (a second name for it where the storage allows, else a copy), as Chat.sendVideoFull.
        val mine = File(videosOut(context), file.name + "-" + k.take(8).filter { it.isLetterOrDigit() } + "-" + System.nanoTime())
        val ok = try { java.nio.file.Files.createLink(mine.toPath(), file.toPath()); true } catch (_: Exception) {
            try { file.copyTo(mine, overwrite = true); true } catch (_: Exception) { false }
        }
        if (!ok) { if (back != null) back() else FileLink.failed(k, clean, size, "Not enough space on this phone to send it.", group = g); return }
        val card = FileLink.preparing(k, clean, size, g)
        if (back != null) FileLink.onNoPath(card, back)
        val why = FileLink.send(k, mine, clean, card, g, vid = vid)
        if (why != null) {
            mine.delete()
            if (back != null) FileLink.videoFellBack(card)
            else FileLink.failed(k, clean, size, if (why == "update") "${chat.nameOf(k)}'s app is older. Ask them to update."
                else "Can't reach ${chat.nameOf(k)} right now. Try again later.", card, g)
        }
    }

    /**
     * Makes the copies (each once) and sends them. [step]: what's happening ("Making the 720p copy") and how far.
     * Returns what to tell the person. Runs from [start]; every copy has its location and camera details taken out.
     */
    private suspend fun send(context: Context, chat: Chat, t: Target, me: String, uri: Uri, info: GroupVideo.Info,
                             step: (String, Float) -> Unit): Sent {
        VideoPick.clearOld(context, busyElsewhere(t))
        val people = members(chat, t, me).associateBy { it.k }
        val plan = GroupVideo.plan(people.values.toList(), unmetered(context), info.ms)
        if (plan.isEmpty()) return Sent(false, "Nobody else is here yet.")
        // Never cut: the sheet said "Video too long" already; this is only a guard.
        if (GroupVideo.tooLong(info.ms, plan)) return Sent(false, GroupVideo.TOO_LONG_TEXT)
        val full = plan.filterValues { it == GroupVideo.FULL }.keys
        val small = plan.filterValues { it == GroupVideo.SMALL }.keys.toMutableSet()
        val relay = plan.filterValues { it == GroupVideo.RELAY }.keys.toMutableSet()
        if (full.isEmpty() && small.isEmpty() && relay.isEmpty())
            return Sent(false, "Nobody here can get this video now. Their app is older: ask them to update.")
        val ms = info.ms
        val dir = VideoPick.dir(context)
        val newApp = { k: String -> people[k]?.newPath == true }
        val shortEnough = ms <= GroupVideo.RELAY_MAX_MS + GroupVideo.SLACK_MS
        val notes = ArrayList<String>()

        // The full copy, made once for everyone direct.
        var bigFile: File? = null
        if (full.isNotEmpty()) {
            val f = File(dir, "full-" + System.nanoTime() + ".mp4")
            step("Making the full-size copy", 0f)
            // Everyone getting it is Family or trusted: the original quality, as it is, when it fits a file.
            val original = full.all { people[it]?.family == true }
            val why = VideoPick.fullCopy(context, uri, info, f, original) { step("Making the full-size copy", it) }
            if (why != null) {
                f.delete()
                // No full copy: new apps get the 720p copy (up to 5 minutes), older ones the relay copy (up to 20 s).
                for (k in full) when {
                    newApp(k) && ms <= GroupVideo.SMALL_MAX_MS + GroupVideo.SLACK_MS -> small.add(k)
                    !newApp(k) && shortEnough -> relay.add(k)
                }
                if (small.isEmpty() && relay.isEmpty()) return Sent(false, why)
            } else bigFile = f
        }
        val fullNow = if (bigFile == null) emptySet() else full

        // The 720p copy, made once for everyone not direct on a new app.
        var smallFile: File? = null
        if (small.isNotEmpty()) {
            val f = File(dir, "small-" + System.nanoTime() + ".mp4")
            step("Making the 720p copy", 0f)
            val why = VideoPick.smallCopy(context, uri, info, f) { step("Making the 720p copy", it) }
            if (why != null) {
                f.delete()
                if (fullNow.isEmpty() && relay.isEmpty()) { bigFile?.delete(); return Sent(false, why) }
                notes.add("The 720p copy couldn't be made, so ${small.size} didn't get it.")
                small.clear()
            } else smallFile = f
        }

        // The relay copy (older apps only): for those getting it, and kept ready for an older app whose full copy can't go
        // straight.
        var clip: ByteArray? = null
        val oldFull = fullNow.filter { !newApp(it) }
        if (relay.isNotEmpty() || (oldFull.isNotEmpty() && shortEnough)) {
            val f = File(dir, "relay-" + System.nanoTime() + ".mp4")
            step("Making a copy for older apps", 0f)
            val why = VideoPick.relayCopy(context, uri, info, f) { step("Making a copy for older apps", it) }
            if (why == null) clip = try { f.readBytes() } catch (_: Exception) { null }
            f.delete()
            if (clip == null && relay.isNotEmpty()) {
                if (fullNow.isEmpty() && small.isEmpty()) { bigFile?.delete(); smallFile?.delete(); return Sent(false, why ?: "That video can't be read.") }
                notes.add("A copy for older apps couldn't be made, so ${relay.size} didn't get it.")
                relay.clear()
            }
        }

        step("Sending", 1f)
        val name = fileName()
        // A kids' device declines video offers (FileLink): it gets the 720p copy as a plain file offer.
        val vidOf = { k: String -> if (people[k]?.kid == true) 0 else GroupVideo.vidFor(ms, newApp(k)) }
        if (t.group) {
            val glock = Any()
            var msgId: String? = null
            if (relay.isNotEmpty() && clip != null) {
                val (id, why) = chat.sendGroupVideoClip(t.g, t.ch, clip, ms, relay.toSet())
                if (why != null) notes.add(why)
                msgId = id
            }
            val clipBytes = clip
            bigFile?.let { big ->
                for (k in fullNow) {
                    val back: (() -> Unit)? = if (clipBytes == null || newApp(k)) null else ({
                        synchronized(glock) {
                            val id = msgId
                            if (id == null) msgId = chat.sendGroupVideoClip(t.g, t.ch, clipBytes, ms, setOf(k)).first
                            else chat.addGroupVideoMember(t.g, id, k)
                            Unit
                        }
                    })
                    offer(context, chat, k, big, name, vidOf(k), t.g, back)
                }
            }
            smallFile?.let { s -> for (k in small) offer(context, chat, k, s, name, vidOf(k), t.g, null) }
            val older = chat.groups.group(t.g)?.state?.members?.count { it.k != me && !chat.hasM2(it.k) } ?: 0
            if (older > 0) notes.add("$older ${if (older == 1) "person needs" else "people need"} a newer app to see videos.")
        } else {
            val k = t.peer
            val clipBytes = clip
            when {
                bigFile != null && k in fullNow -> offer(context, chat, k, bigFile, name, vidOf(k), "",
                    if (clipBytes == null || newApp(k)) null else ({ chat.sendVideoClip(k, clipBytes, ms)?.let { chat.notices.tryEmit(it) } }))
                smallFile != null && k in small -> offer(context, chat, k, smallFile, name, vidOf(k), "", null)
                clipBytes != null && k in relay -> chat.sendVideoClip(k, clipBytes, ms)?.let { notes.add(it) }
            }
        }
        bigFile?.delete()
        smallFile?.delete()
        val parts = listOfNotNull(
            fullNow.size.takeIf { it > 0 && bigFile != null }?.let { "full quality to $it" },
            small.size.takeIf { it > 0 && smallFile != null }?.let { "a 720p copy over Tor to $it" },
            relay.size.takeIf { it > 0 && clip != null }?.let { "a smaller copy to $it with older apps" },
        )
        val head = when {
            parts.isEmpty() -> "That video didn't send."
            !t.group && fullNow.isNotEmpty() -> "Sending full quality."
            !t.group && small.isNotEmpty() -> "Sending a 720p copy over Tor. It can take a while."
            !t.group -> "Sending. Its pieces go a few a second, so it can take a few minutes."
            else -> "Sending: " + parts.joinToString(", ") + "."
        }
        return Sent(parts.isNotEmpty(), (listOf(head) + notes).joinToString(" "))
    }
}
