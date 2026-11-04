package io.github.ravenor907.muninn.net

import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Half-typed messages, one per chat (a person's key, or "g:" + group + "/" + channel). They live for the
 * whole process, so leaving a chat, switching chats, folding or unfolding, or a new window keeps them, and
 * they are saved in the vault when Muninn's window goes away, so a restart keeps them too.
 * The message being answered ([Draft.re]) or edited ([Draft.edit]) is kept by its id.
 */
object Drafts {
    data class Draft(val text: String = "", val re: String = "", val edit: String = "") {
        val empty get() = text.isEmpty() && re.isEmpty() && edit.isEmpty()
    }

    const val FILE = "drafts.json"
    /** The most a draft keeps: a long paste is split into messages when sent (see [split]). */
    const val KEEP_MAX = 40_000
    /** Longer than this many messages: too long to send as text. */
    const val MAX_PARTS = 10

    private val map = ConcurrentHashMap<String, Draft>()
    /** Picked photos waiting for Send, in memory only (a picker's permission doesn't outlive the process). */
    val picked = ConcurrentHashMap<String, List<String>>()
    /** A search open in a chat, and what it looks for, in memory only: the lock coming up or a fold keeps it. */
    val search = ConcurrentHashMap<String, String>()

    /** The camera open in a chat ("photo" or "video"), and a photo or video taken and waiting for Send, in memory only. */
    class Taken(val mode: String, val photo: ByteArray? = null, val clip: ByteArray? = null, val ms: Int = 0) {
        fun with(photo: ByteArray?, clip: ByteArray?, ms: Int) = Taken(mode, photo, clip, ms)
    }
    val camera = ConcurrentHashMap<String, Taken>()
    /** Round 17: goes up when a photo or video finished after its camera screen went (a fold, a new window), so the new one shows it. */
    val cameraRev = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile private var loaded = false
    @Volatile private var dirty = false
    private val lock = Any()

    /** A group channel's draft key. */
    fun groupKey(g: String, ch: String) = "g:$g/$ch"

    /**
     * Round 17: [key]'s message went (a long one asked "Send as N messages?" first): its draft is emptied and an open
     * chat reads it again, even one made anew while the question was up (a fold, a keyboard), so the text can't go twice.
     */
    fun sent(key: String) {
        put(key, Draft())
        _revision.value++
    }

    /** Round 17: a linked phone's chat shown on this companion (its key follows), and the Nearby room. */
    const val MIRROR = "mirror:"
    const val NEARBY = "nearby"
    fun mirrorKey(peer: String) = MIRROR + peer

    /** The phone was unlinked: the drafts of its chats go. */
    fun dropMirrors() {
        mirrorsGone = true
        if (map.keys.removeIf { it.startsWith(MIRROR) }) dirty = true
    }
    @Volatile private var mirrorsGone = false

    fun get(app: io.github.ravenor907.muninn.Muninn, key: String): Draft { load(app); return map[key] ?: Draft() }

    fun put(key: String, d: Draft) {
        val clean = d.copy(text = d.text.take(KEEP_MAX))
        val old = map[key]
        if (clean.empty) { if (old != null) { map.remove(key); dirty = true } }
        else if (old != clean) { map[key] = clean; dirty = true }
    }

    /** The draft key a notification's chat key goes to: a chat's own, or a group's main channel ("g:" + id). */
    fun replyKey(key: String): String = if (key.startsWith("g:") && '/' !in key) groupKey(key.removePrefix("g:"), "main") else key

    /** [old] with a refused reply [text] added on a new line (or [text] alone when the box was empty). */
    fun withReply(old: Draft, text: String): Draft =
        old.copy(text = (if (old.text.isBlank()) text else old.text + "\n" + text).take(KEEP_MAX))

    /**
     * A notification reply that couldn't be sent (Muninn locked): it waits in its chat's box as a draft, never sent by
     * itself. 1.42: with the drafts readable (the vault open) it goes straight into the draft. With the vault closed it's
     * kept outside the vault only sealed ([ReplyHold]), and added to the draft once the vault opens; when it can't be
     * sealed it's dropped ([ReplyHold.Kept.DROPPED]) and the caller says so. Nothing typed is kept in plain text.
     */
    fun keepReply(app: io.github.ravenor907.muninn.Muninn, key: String, text: String): ReplyHold.Kept {
        if (text.isBlank()) return ReplyHold.Kept.DROPPED
        load(app)
        if (loaded) {
            synchronized(lock) { addReplies(listOf(replyKey(key) to text)) }
            save(app)
            return ReplyHold.Kept.DRAFT
        }
        return if (ReplyHold.hold(app, replyKey(key), text)) ReplyHold.Kept.SEALED else ReplyHold.Kept.DROPPED
    }

    /** [replies] (draft key and text) added to their drafts, each on a new line. Holds [lock]. */
    private fun addReplies(replies: List<Pair<String, String>>) {
        if (replies.isEmpty()) return
        for ((k, t) in replies) map[k] = withReply(map[k] ?: Draft(), t)
        dirty = true
        _revision.value++
    }

    private val _revision = kotlinx.coroutines.flow.MutableStateFlow(0)
    /**
     * Round 17: goes up when text was added to a draft from outside its chat (a share, a refused notification reply), so
     * a chat already open reads its draft again instead of writing its older text over it.
     */
    val revision: kotlinx.coroutines.flow.StateFlow<Int> = _revision

    /**
     * Text shared in from another app (Share › Muninn): added to the draft of the chat picked (a person's key, or "g:" +
     * a group id for its main channel), for the person to read and send. False while the drafts can't be read (locked).
     */
    fun addShared(app: io.github.ravenor907.muninn.Muninn, chat: String, text: String): Boolean {
        if (text.isBlank()) return false
        load(app)
        if (!loaded) return false
        val k = replyKey(chat)
        map[k] = withReply(map[k] ?: Draft(), text)
        dirty = true
        _revision.value++
        save(app)
        return true
    }

    /**
     * What a share of text holds ([subject] and [text] as another app gave them): the text, with the subject (a page's
     * title) on the line before it when the text doesn't already have it. "" for nothing usable.
     */
    fun sharedText(subject: CharSequence?, text: CharSequence?): String {
        val t = text?.toString()?.trim().orEmpty()
        val s = subject?.toString()?.trim().orEmpty().replace('\n', ' ')
        val all = when {
            t.isEmpty() -> s
            s.isEmpty() || t.contains(s) -> t
            else -> s + "\n" + t
        }
        return all.take(KEEP_MAX)
    }

    /**
     * A chat or group is gone (deleted, auto-deleted, left, or the contact removed): its drafts go too, with what waits
     * in memory. [key] is a chat key, or "g:" + a group id for every channel of that group.
     */
    fun forget(app: io.github.ravenor907.muninn.Muninn, key: String) {
        load(app)
        val group = key.startsWith("g:") && '/' !in key
        fun gone(k: String) = k == key || (group && k.startsWith("$key/"))
        if (map.keys.removeIf { gone(it) }) dirty = true
        picked.keys.removeIf { gone(it) }; search.keys.removeIf { gone(it) }; camera.keys.removeIf { gone(it) }
        save(app)
    }

    /** The draft keys of [all] that belong to no chat or group still here ([chats]: chat keys and "g:" + group ids). */
    fun orphans(all: Set<String>, chats: Set<String>): Set<String> =
        all.filterTo(HashSet()) { k -> !k.startsWith(MIRROR) && k != NEARBY && (if (k.startsWith("g:")) k.substringBefore('/') else k) !in chats }

    /** Drops the drafts of chats and groups no longer here ([chats] as in [orphans]); only once they were read. */
    fun prune(app: io.github.ravenor907.muninn.Muninn, chats: Set<String>) {
        if (!loaded) return
        val gone = orphans(map.keys.toSet(), chats)
        if (gone.isEmpty()) return
        gone.forEach { map.remove(it) }
        dirty = true
        save(app)
    }

    /** Reads the saved drafts once the vault is open (never before: a locked read must not be saved over). */
    fun load(app: io.github.ravenor907.muninn.Muninn) {
        if (loaded) return
        synchronized(lock) {
            if (loaded || app.vault.locked.value) return
            val saved = try { app.vault.read(File(app.filesDir, FILE)) } catch (_: Exception) { null }
            decode(saved).forEach { (k, v) -> if (!(mirrorsGone && k.startsWith(MIRROR))) map.putIfAbsent(k, v) }
            if (mirrorsGone) dirty = true
            loaded = true
            // 1.42: replies refused and sealed while the vault was closed join their drafts, written to the vault
            // before they're deleted from outside it.
            ReplyHold.opened(app) { replies ->
                addReplies(replies)
                try { app.vault.write(File(app.filesDir, FILE), encode(map.toMap())); dirty = false; true } catch (_: Exception) { false }
            }
        }
    }

    /** Writes them to the vault, off the main thread, when something changed. */
    fun save(app: io.github.ravenor907.muninn.Muninn) {
        if (!loaded || !dirty) return
        dirty = false
        val text = encode(map.toMap())
        Thread {
            synchronized(lock) {
                try { app.vault.write(File(app.filesDir, FILE), text) } catch (_: Exception) { dirty = true }
            }
        }.start()
    }

    /** Writes them now, on this thread (a panic lock, just before the key is wiped and the process ends). */
    fun saveNow(app: io.github.ravenor907.muninn.Muninn) {
        if (!loaded || !dirty) return
        dirty = false
        val text = encode(map.toMap())
        synchronized(lock) {
            try { app.vault.write(File(app.filesDir, FILE), text) } catch (_: Exception) { dirty = true }
        }
    }

    fun encode(all: Map<String, Draft>): String {
        val o = JSONObject()
        for ((k, d) in all) if (!d.empty) o.put(k, JSONObject().put("t", d.text).apply {
            if (d.re.isNotEmpty()) put("re", d.re)
            if (d.edit.isNotEmpty()) put("ed", d.edit)
        })
        return o.toString()
    }

    fun decode(s: String?): Map<String, Draft> {
        if (s.isNullOrBlank()) return emptyMap()
        val o = try { JSONObject(s) } catch (_: Exception) { return emptyMap() }
        val out = HashMap<String, Draft>()
        for (k in o.keys()) {
            val d = o.optJSONObject(k) ?: continue
            val draft = Draft(d.optString("t").take(KEEP_MAX), d.optString("re").take(200), d.optString("ed").take(200))
            if (!draft.empty) out[k] = draft
        }
        return out
    }

    /**
     * A message too long for one ([max] characters, counted as code points like the rest of chat) split into
     * parts: at the last line break, else the last space, in the second half of each part; else at [max]
     * (never inside a surrogate pair). Each part is trimmed; empty parts are dropped.
     */
    fun split(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.isNotEmpty()) {
            if (rest.codePointCount(0, rest.length) <= max) { out.add(rest); break }
            val hard = rest.offsetByCodePoints(0, max)
            val half = rest.offsetByCodePoints(0, max / 2)
            val window = rest.substring(0, hard)
            var cut = window.lastIndexOf('\n').takeIf { it >= half } ?: -1
            if (cut < 0) cut = window.lastIndexOf(' ').takeIf { it >= half } ?: -1
            // No break or space: never inside an emoji (a family, a flag or a skin tone) or a letter with its marks.
            if (cut < 0) cut = graphemeCut(rest, hard)
            val part = rest.substring(0, cut).trim()
            if (part.isNotEmpty()) out.add(part)
            rest = rest.substring(cut).trim()
        }
        return out
    }

    /** The last place at or before [hard] in [s] that doesn't split a character as shown; [hard] if there is none. */
    fun graphemeCut(s: String, hard: Int): Int {
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(s)
        val b = if (it.isBoundary(hard)) hard else it.preceding(hard)
        return if (b == java.text.BreakIterator.DONE || b <= 0) hard else b
    }
}
