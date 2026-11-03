package io.github.ravenor907.muninn.tv

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Chat
import io.github.ravenor907.muninn.net.Groups
import io.github.ravenor907.muninn.ui.MIcon
import io.github.ravenor907.muninn.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A Kids' Device's conversations: one person (their chat key) or a group a parent put it in ("g:<group id>"). The kids'
 * screens send and read through these, so a group works like a person there: quick messages, and at the chatting level
 * that allows it, typing, photos and voice messages. No calls in a group.
 */
internal object KidChats {
    private const val G = "g:"

    fun isGroup(k: String): Boolean = k.startsWith(G)
    private fun gid(k: String): String = k.removePrefix(G)

    /** The groups a parent put this device in, as tiles. None during a quiet time: only parents get through then. */
    fun groups(app: Muninn, all: Map<String, Groups.Group>, quiet: QuietTime?): List<TvPerson> {
        if (quiet != null) return emptyList()
        val me = app.identity.publicKey
        return all.values.filter { !it.readOnly && it.state.has(me) }.sortedBy { it.state.name.lowercase() }
            .map { TvPerson(G + it.g, it.state.name.ifBlank { "Group" }, "") }
    }

    /**
     * A group's messages as the kids' screens show them: someone else's text says who wrote it. k14: messages from members
     * no parent approved ([KidTv.heldNow]) are left out. Mine say how they're doing; one still sending after a minute waits.
     */
    fun groupMsgs(g: String, list: List<Groups.GMsg>, now: Long = System.currentTimeMillis()): List<Chat.Msg> =
        list.filter { it.unsent.isEmpty() && (it.mine || !KidTv.heldNow(g, it.from)) }.map { m ->
            Chat.Msg(m.id, m.from, m.name, if (m.mine || m.text.isEmpty()) m.text else "${m.name}: ${m.text}", m.mine, m.time,
                failed = m.mine && m.state == "sending" && now - m.time > 60_000L, state = m.state,
                photo = m.photo, voice = m.voice, video = m.video, poll = m.poll, votes = m.votes, gif = m.gif)
        }

    /** How many messages in group conversation [k] are held for a parent (k14); 0 for a person. */
    fun heldCount(k: String, gmsgs: Map<String, List<Groups.GMsg>>): Int =
        if (!isGroup(k)) 0 else gmsgs[gid(k)].orEmpty().count { !it.mine && it.unsent.isEmpty() && KidTv.heldNow(gid(k), it.from) }

    /** The messages of conversation [k], from the private ones [convs] or the groups' [gmsgs]. */
    fun msgs(k: String, convs: Map<String, List<Chat.Msg>>, gmsgs: Map<String, List<Groups.GMsg>>): List<Chat.Msg> =
        if (isGroup(k)) groupMsgs(gid(k), gmsgs[gid(k)].orEmpty()) else convs[k].orEmpty().filter { it.unsent.isEmpty() }

    /** New messages in [k]: a group's are counted from the newest message of this device's. */
    fun unread(k: String, msgs: List<Chat.Msg>, unreadPeople: Set<String>, unreadGroups: Set<String>): Int =
        unreadCount(msgs, if (isGroup(k)) gid(k) in unreadGroups else k in unreadPeople)

    /** The chatting level now, from the device's config (checked when something is sent, not only when a button shows). */
    private fun level(): Int = KidTv.state.value.cfg?.chat ?: KidRules.QUICK_ONLY

    /**
     * A quick message, or text a kid typed ([typed]: only at a level that allows typing). Round 17: during a quiet time
     * (or before the device knows the time, while quiet times are set) it's held for anyone who isn't a parent, as voice
     * messages and photos are, and goes once it's over. Returns what to tell the kid.
     */
    fun sendText(app: Muninn, k: String, text: String, typed: Boolean = false): String {
        if (typed && !KidRules.canSend(level(), "text")) return TvWords.ASK_PARENT
        val parents = KidTv.state.value.parents
        val quiet = KidTv.quietNow()
        if (quiet != null && KidK14.holds(k, parents, quiet)) {
            KidHoldStore.hold(app, k, if (typed) "text" else "quick", text.toByteArray(Charsets.UTF_8), 0)
            return KidK14.willSend(quiet, android.text.format.DateFormat.is24HourFormat(app))
        }
        if (KidTv.timeUnsure() && k !in parents) {
            KidHoldStore.hold(app, k, if (typed) "text" else "quick", text.toByteArray(Charsets.UTF_8), 0)
            return KidK14.WILL_SEND_SOON
        }
        sendTextNow(app, k, text)
        return "Sending"
    }

    /** Text that goes now (a held one, once the quiet time is over). */
    internal fun sendTextNow(app: Muninn, k: String, text: String) {
        if (isGroup(k)) app.chat.sendGroupText(gid(k), "", text) else app.chat.sendPrivate(k, text)
    }

    fun sendVoice(app: Muninn, k: String, aac: ByteArray, ms: Int) {
        if (!KidRules.canSend(level(), "voice")) return
        if (isGroup(k)) app.chat.sendGroupVoice(gid(k), "", aac, ms) else app.chat.sendVoice(k, aac, ms)
    }

    fun sendPhoto(app: Muninn, k: String, jpeg: ByteArray) {
        if (!KidRules.canSend(level(), "photo")) return
        if (isGroup(k)) app.chat.sendGroupPhoto(gid(k), "", jpeg) else app.chat.sendPhoto(k, jpeg)
    }

    /**
     * A finished voice message ([kind] "voice", [ms] long) or photo for [k]: sent now, or, during a quiet time that [k]
     * doesn't get through, held and sent when it ends. Returns what to tell the kid: "Sending", "Will send at 7:00 AM", or
     * "Ask a parent" when the chatting level doesn't allow it any more.
     */
    fun sendOrHold(app: Muninn, k: String, kind: String, bytes: ByteArray, ms: Int): String {
        if (!KidRules.canSend(level(), kind)) return TvWords.ASK_PARENT
        val parents = KidTv.state.value.parents
        val quiet = KidTv.quietNow()
        if (quiet != null && KidK14.holds(k, parents, quiet)) {
            KidHoldStore.hold(app, k, kind, bytes, ms)
            return KidK14.willSend(quiet, android.text.format.DateFormat.is24HourFormat(app))
        }
        if (kind == "voice") sendVoice(app, k, bytes, ms) else sendPhoto(app, k, bytes)
        return "Sending"
    }

    fun markRead(app: Muninn, k: String) {
        if (isGroup(k)) app.chat.groupRead(gid(k)) else app.chat.markRead(k)
    }

    /** A vote on a poll, for option [i]. */
    fun vote(app: Muninn, k: String, msgId: String, i: Int) {
        if (isGroup(k)) app.chat.groupVote(gid(k), msgId, listOf(i)) else app.chat.vote(k, msgId, listOf(i))
    }

    /** What a conversation can show full screen as a photo: not a voice message, a video or a file. */
    fun isPhoto(m: Chat.Msg): Boolean = m.photo.isNotEmpty() && m.voice == 0 && m.video == 0 && m.file.isEmpty()
}

/** Reads a message out loud on a Kids' Device (Android's text to speech), for a child who can't read yet. */
internal object KidSpeak {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var waiting: String? = null

    /** The device has no text to speech (it said so when asked): a tap says that instead of doing nothing. */
    @Volatile private var none = false
    const val NONE = "This device can't read out loud"

    fun say(context: Context, text: String) {
        if (text.isBlank()) return
        if (none) { io.github.ravenor907.muninn.ui.Ui.say(NONE, true); return }
        val t = tts
        if (t == null) {
            waiting = text
            tts = TextToSpeech(context.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) waiting?.let { w -> waiting = null; try { tts?.speak(w, TextToSpeech.QUEUE_FLUSH, null, "kid") } catch (_: Exception) {} }
                else { none = true; waiting = null; io.github.ravenor907.muninn.ui.Ui.say(NONE, true) }
            }
            return
        }
        if (ready) try { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kid") } catch (_: Exception) {}
        else waiting = text
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }

    /** Round 17: left kids' mode: the speech engine is let go. */
    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null; ready = false; waiting = null
    }
}

/**
 * k14: voice messages and photos a quiet time holds ([KidK14.release]), kept in the vault until the quiet time ends, then
 * sent; the kid can cancel each one. A draft is never held here: it stays in the box ([KidPhoneNav.drafts]).
 */
internal object KidHoldStore {
    private const val FILE = "kid-held.json"
    private const val DIR = "kid-held"
    val list = kotlinx.coroutines.flow.MutableStateFlow<List<Held>>(emptyList())
    @Volatile private var loaded = false
    private val random = java.security.SecureRandom()

    private fun file(app: Muninn) = java.io.File(app.filesDir, FILE)
    private fun blob(app: Muninn, id: String) = java.io.File(java.io.File(app.filesDir, DIR), id)

    @Synchronized fun load(app: Muninn) {
        if (loaded) return
        loaded = true
        list.value = try { app.vault.read(file(app))?.let { KidK14.heldIn(org.json.JSONArray(it)) } } catch (_: Exception) { null } ?: emptyList()
    }

    private fun save(app: Muninn) { try { app.vault.write(file(app), KidK14.heldJson(list.value).toString()) } catch (_: Exception) {} }

    /** Holds [bytes] for [k] until the quiet time ends. */
    @Synchronized fun hold(app: Muninn, k: String, kind: String, bytes: ByteArray, ms: Int) {
        load(app)
        val id = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        try { app.vault.writeBytes(blob(app, id), bytes) } catch (_: Exception) { return }
        val before = list.value
        val after = KidK14.addHeld(before, Held(id, k, kind, ms, System.currentTimeMillis()))
        before.filter { b -> after.none { it.id == b.id } }.forEach { blob(app, it.id).delete() }
        list.value = after
        save(app)
    }

    /** The kid said not to send [id]. */
    @Synchronized fun cancel(app: Muninn, id: String) {
        load(app)
        blob(app, id).delete()
        list.value = KidK14.cancelHeld(list.value, id)
        save(app)
    }

    /** Sends what no quiet time holds any more; drops what's for someone no longer on the list. */
    @Synchronized fun release(app: Muninn) {
        load(app)
        if (list.value.isEmpty()) return
        val s = KidTv.state.value
        val cfg = s.cfg ?: return
        // Round 17: while quiet times are set and the device doesn't know the time yet, nothing goes to anyone but a parent.
        val quiet = KidTv.quietNow() ?: (if (KidTv.timeUnsure()) KidK14.UNSURE else null)
        val (send, keep) = KidK14.release(list.value, s.parents, quiet) { k -> KidChats.isGroup(k) || KidTv.approved(k) }
        for (h in send) {
            val bytes = try { app.vault.readBytes(blob(app, h.id)) } catch (_: Exception) { null }
            if (bytes != null && KidRules.canSend(cfg.chat, h.kind)) when (h.kind) {
                "voice" -> KidChats.sendVoice(app, h.k, bytes, h.ms)
                "photo" -> KidChats.sendPhoto(app, h.k, bytes)
                else -> KidChats.sendTextNow(app, h.k, String(bytes, Charsets.UTF_8))
            }
        }
        list.value.filter { h -> keep.none { it.id == h.id } }.forEach { blob(app, it.id).delete() }
        list.value = keep
        save(app)
    }

    /** A disconnect or leaving kids' mode: everything held goes. */
    @Synchronized fun clear(app: Muninn) {
        loaded = true
        list.value = emptyList()
        try { java.io.File(app.filesDir, DIR).deleteRecursively(); file(app).delete() } catch (_: Exception) {}
    }
}

/** A video message, full screen, with Play and Pause. Back closes it. */
@Composable
internal fun KidVideo(app: Muninn, m: Chat.Msg, onClose: () -> Unit) {
    // Opened off the main thread: a video can be large.
    val bytes by produceState<ByteArray?>(null, m.photo) { value = withContext(Dispatchers.IO) { try { app.chat.photo(m.photo) } catch (_: Throwable) { null } } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val b = bytes
        if (b == null) Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            MIcon("video", Palette.textMuted, 72.dp)
        } else Box(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            // Clear of the status bar and a camera cutout, so Back can be reached.
            io.github.ravenor907.muninn.screens.VideoPlayer(b, onClose)
        }
    }
    // A video that can't be read at all doesn't leave a black screen up.
    LaunchedEffect(m.photo) { kotlinx.coroutines.delay(8_000); if (bytes == null) onClose() }
}

/** A poll: its question and choices. A child votes with a tap when typing is allowed ([canVote]); otherwise it only shows. */
@Composable
internal fun KidPoll(m: Chat.Msg, canVote: Boolean, tv: Boolean, onVote: (Int) -> Unit, onClose: () -> Unit) {
    val p = m.poll ?: run { LaunchedEffect(m.id) { onClose() }; return }
    KidDialog(tv, onClose) {
        MIcon("chat", Palette.goldBright, if (tv) 64.dp else 48.dp)
        Text(p.q, style = tvTitle(if (tv) 30.sp else 22.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        p.options.forEachIndexed { i, o ->
            val n = m.votes.values.count { i in it.v }
            Box(Modifier.padding(top = 10.dp)) {
                BigButton(o, sub = if (n == 1) "1 vote" else "$n votes", enabled = canVote) { onVote(i); onClose() }
            }
        }
        if (!canVote) Text("Ask a parent to vote", style = tvText(if (tv) 22.sp else 17.sp, Palette.textSoft), modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.padding(top = 12.dp)) { BigButton("Close") { onClose() } }
    }
}
