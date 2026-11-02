package io.github.ravenor907.muninn.tv

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

    /** A group's messages as the kids' screens show them: someone else's text says who wrote it. */
    fun groupMsgs(list: List<Groups.GMsg>): List<Chat.Msg> = list.filter { it.unsent.isEmpty() }.map { m ->
        Chat.Msg(m.id, m.from, m.name, if (m.mine || m.text.isEmpty()) m.text else "${m.name}: ${m.text}", m.mine, m.time,
            photo = m.photo, voice = m.voice, video = m.video, poll = m.poll, votes = m.votes, gif = m.gif)
    }

    /** The messages of conversation [k], from the private ones [convs] or the groups' [gmsgs]. */
    fun msgs(k: String, convs: Map<String, List<Chat.Msg>>, gmsgs: Map<String, List<Groups.GMsg>>): List<Chat.Msg> =
        if (isGroup(k)) groupMsgs(gmsgs[gid(k)].orEmpty()) else convs[k].orEmpty().filter { it.unsent.isEmpty() }

    /** New messages in [k]: a group's are counted from the newest message of this device's. */
    fun unread(k: String, msgs: List<Chat.Msg>, unreadPeople: Set<String>, unreadGroups: Set<String>): Int =
        unreadCount(msgs, if (isGroup(k)) gid(k) in unreadGroups else k in unreadPeople)

    fun sendText(app: Muninn, k: String, text: String) {
        if (isGroup(k)) app.chat.sendGroupText(gid(k), "", text) else app.chat.sendPrivate(k, text)
    }

    fun sendVoice(app: Muninn, k: String, aac: ByteArray, ms: Int) {
        if (isGroup(k)) app.chat.sendGroupVoice(gid(k), "", aac, ms) else app.chat.sendVoice(k, aac, ms)
    }

    fun sendPhoto(app: Muninn, k: String, jpeg: ByteArray) {
        if (isGroup(k)) app.chat.sendGroupPhoto(gid(k), "", jpeg) else app.chat.sendPhoto(k, jpeg)
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

    fun say(context: Context, text: String) {
        if (text.isBlank()) return
        val t = tts
        if (t == null) {
            waiting = text
            tts = TextToSpeech(context.applicationContext) { st ->
                ready = st == TextToSpeech.SUCCESS
                if (ready) waiting?.let { w -> waiting = null; try { tts?.speak(w, TextToSpeech.QUEUE_FLUSH, null, "kid") } catch (_: Exception) {} }
            }
            return
        }
        if (ready) try { t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kid") } catch (_: Exception) {}
        else waiting = text
    }

    fun stop() { try { tts?.stop() } catch (_: Exception) {} }
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
        } else io.github.ravenor907.muninn.screens.VideoPlayer(b, onClose)
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
