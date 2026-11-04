package io.github.ravenor907.muninn.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cap v3 (protocol.md 29): sends a picked video to a group or one person. Each member gets what GroupVideo.plan says:
 * the full copy straight (FileLink, its own card per member), or the relay copy (one group message to those members,
 * its pieces paced). A full copy that can't go straight within 30 s falls back to the relay copy for that member.
 */
object VideoSend {
    /** A group [g] (and channel [ch]) or, with [g] empty, the chat with [peer]. */
    data class Target(val g: String, val ch: String, val peer: String) {
        val group get() = g.isNotEmpty()
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
     * Returns what to tell the person.
     */
    suspend fun send(context: Context, chat: Chat, t: Target, me: String, uri: Uri, info: GroupVideo.Info, clip: Pair<Int, Int>?,
                     inFull: Boolean, step: (String, Float) -> Unit): String {
        // Ultra privacy: sending videos is off entirely (receiving still works).
        if (UltraPrivacy.on) return "Off while Ultra privacy is on."
        VideoPick.clearOld(context)
        val plan = plan(context, chat, t, me, inFull)
        if (plan.isEmpty()) return "Nobody else is here yet."
        val full = plan.filterValues { it == GroupVideo.FULL }.keys
        val relay = plan.filterValues { it == GroupVideo.RELAY }.keys
        if (full.isEmpty() && relay.isEmpty()) return "Nobody here can get it in full now: they need the same Wi-Fi or Direct calls and files with you."
        val ms = clip?.let { it.second - it.first } ?: info.ms
        val dir = VideoPick.dir(context)
        // The relay copy: for those getting it, and kept ready for anyone whose full copy can't go straight.
        var small: ByteArray? = null
        if (!inFull) {
            val f = File(dir, "relay-" + System.nanoTime() + ".mp4")
            step("Making the smaller copy", 0f)
            VideoPick.relayCopy(context, uri, info, f, clip) { step("Making the smaller copy", it) }?.let { f.delete(); return it }
            small = try { f.readBytes() } catch (_: Exception) { null } finally { f.delete() }
            if (small == null) return "That video can't be read."
        }
        var bigFile: File? = null
        if (full.isNotEmpty()) {
            val f = File(dir, "full-" + System.nanoTime() + ".mp4")
            step("Making the full-size copy", 0f)
            val why = VideoPick.fullCopy(context, uri, info, f, if (inFull) null else clip) { step("Making the full-size copy", it) }
            if (why != null) {
                f.delete()
                // No full copy: everyone gets the relay copy when there is one.
                if (small == null) return why
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
        return (listOf(when {
            full.isEmpty() -> "Sending. Its pieces go a few a second, so it can take a few minutes."
            relay.isEmpty() -> "Sending full size straight."
            else -> "Sending: full size straight to ${full.size}, a smaller copy to ${relay.size}."
        }) + notes).joinToString(" ")
    }
}
