package io.github.ravenor907.muninn.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyPair
import java.security.MessageDigest

/**
 * 1.42: a notification reply refused while Muninn's vault is closed (after a restart or a panic lock, before the lock
 * is opened). It becomes a draft in its chat, never sent by itself. Until the vault opens it's kept outside the vault
 * only sealed: to a key pair of its own whose private half is in the vault ([KEY_FILE]) and whose public half alone
 * is outside it ([PUB_FILE]). So the reply can be sealed while locked, and only an unlocked Muninn can read it.
 * Without that public half (it's made the first time the vault opens), or with the store full, the reply is
 * dropped and the person is told so ("Reply not saved: unlock to reply.").
 */
object ReplyHold {
    /** In the vault: the key pair, {"k": public, "s": private}. */
    const val KEY_FILE = "reply-key.json"
    /** Outside the vault: the public half only. */
    const val PUB_FILE = "reply-key.pub"
    /** Outside the vault: the sealed replies, a JSON list. */
    const val HELD_FILE = "replies-held.json"
    /** At most this many replies wait sealed, and at most [HELD_BYTES] in all. */
    const val HELD_MAX = 20
    const val HELD_BYTES = 400_000L

    const val NOT_SAVED = "Reply not saved: unlock to reply."
    const val WAITS = "Unlock Muninn to send your reply. It waits in the chat."

    private val DOMAIN = "muninn held reply v1".toByteArray()

    /** Where a refused reply goes. */
    enum class Kept { DRAFT, SEALED, DROPPED }

    /**
     * The drafts can be read ([draftsOpen]): straight into the chat's draft, sealed in the vault with the rest. Else
     * sealed outside the vault when there's a public half ([havePub]) and room ([room]); else dropped.
     */
    fun fate(draftsOpen: Boolean, havePub: Boolean, room: Boolean): Kept = when {
        draftsOpen -> Kept.DRAFT
        havePub && room -> Kept.SEALED
        else -> Kept.DROPPED
    }

    /** What the note after a refused reply says. */
    fun note(k: Kept): String = if (k == Kept.DROPPED) NOT_SAVED else WAITS

    /** Room for one more of [size] characters beside [count] held ones of [bytes] characters in all. */
    fun room(count: Int, bytes: Long, size: Long): Boolean = count < HELD_MAX && bytes + size <= HELD_BYTES

    private fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    /** [text] for chat [key] sealed to [pub] (a P-256 public key, base64 SubjectPublicKeyInfo): {"e","n","d"} as text. */
    fun seal(pub: String, key: String, text: String, eph: KeyPair = ChatCrypto.newKeyPair()): String {
        val k = sha256(DOMAIN, ChatCrypto.sharedKey(eph.private, eph.public, ChatCrypto.publicFrom(pub)))
        val plain = JSONObject().put("t", "reply").put("k", key).put("x", text).toString().toByteArray(Charsets.UTF_8)
        val s = ChatCrypto.sealBytes(k, plain, DOMAIN)
        return JSONObject().put("e", ChatCrypto.b64(eph.public.encoded)).put("n", s.nonce).put("d", s.data).toString()
    }

    /** A sealed reply opened with [keys]: the chat key and the text, or null when it isn't one or isn't for these keys. */
    fun open(box: String, keys: KeyPair): Pair<String, String>? = try {
        val o = JSONObject(box)
        val k = sha256(DOMAIN, ChatCrypto.sharedKey(keys.private, keys.public, ChatCrypto.publicFrom(o.getString("e"))))
        val plain = ChatCrypto.openBytes(k, ChatCrypto.Sealed(o.getString("n"), o.getString("d")), DOMAIN)
        plain?.let { JSONObject(String(it, Charsets.UTF_8)) }?.takeIf { it.optString("t") == "reply" }?.let { p ->
            val key = p.optString("k"); val x = p.optString("x")
            if (key.isEmpty() || x.isBlank()) null else key to x
        }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ on the phone

    private val lock = Any()

    private fun pubFile(app: io.github.ravenor907.muninn.Muninn) = File(app.filesDir, PUB_FILE)
    private fun heldFile(app: io.github.ravenor907.muninn.Muninn) = File(app.filesDir, HELD_FILE)
    private fun keyFile(app: io.github.ravenor907.muninn.Muninn) = File(app.filesDir, KEY_FILE)

    /** The public half kept outside the vault, or null. */
    private fun pub(app: io.github.ravenor907.muninn.Muninn): String? =
        (SafeFile.read(pubFile(app)) { b -> String(b, Charsets.UTF_8).trim().takeIf { RingWire.keyOk(it) } } as? SafeFile.Read.Ok)?.value

    /** Null: none there. Throws when the list is there but can't be read (it's then never written over). */
    private fun readHeld(app: io.github.ravenor907.muninn.Muninn): MutableList<String> =
        when (val r = SafeFile.read(heldFile(app)) { b -> JSONArray(String(b, Charsets.UTF_8)).let { a -> MutableList(a.length()) { a.getString(it) } } }) {
            is SafeFile.Read.Ok -> r.value
            is SafeFile.Read.Missing -> mutableListOf()
            is SafeFile.Read.Bad -> throw java.io.IOException("$HELD_FILE can't be read")
        }

    /**
     * The vault is closed: [text] for chat [key] sealed and kept for after unlocking. False (nothing kept) without the
     * public half, with the store full, or when it can't be written.
     */
    fun hold(app: io.github.ravenor907.muninn.Muninn, key: String, text: String): Boolean = try {
        synchronized(lock) {
            val p = pub(app)
            val list = if (p == null) mutableListOf() else readHeld(app)
            val box = p?.let { seal(it, key, text) }
            if (fate(false, box != null, box != null && room(list.size, list.sumOf { it.length.toLong() }, box.length.toLong())) != Kept.SEALED) false
            else {
                list.add(box!!)
                SafeFile.write(heldFile(app), JSONArray(list).toString().toByteArray(Charsets.UTF_8))
                true
            }
        }
    } catch (_: Exception) { false }

    /**
     * The vault is open: makes the key pair the first time (private half in the vault, public half outside), puts the
     * public half back if it's missing (a restore), then hands every reply sealed while locked to [take] (chat key and
     * text, oldest first). Once [take] says they're saved (true) they're deleted; ones that can't be opened go too.
     */
    fun opened(app: io.github.ravenor907.muninn.Muninn, take: (List<Pair<String, String>>) -> Boolean) {
        try {
            synchronized(lock) {
                val keys = keys(app) ?: return
                val pub = ChatCrypto.b64(keys.public.encoded)
                if (pub(app) != pub) SafeFile.write(pubFile(app), pub.toByteArray(Charsets.UTF_8))
                val list = readHeld(app)
                if (list.isEmpty()) return
                val out = list.mapNotNull { open(it, keys) }
                if (out.isEmpty() || take(out)) SafeFile.delete(heldFile(app))
            }
        } catch (_: Exception) {}
    }

    /** The key pair from the vault, made and saved the first time. Null when it's there but can't be read (never replaced). */
    private fun keys(app: io.github.ravenor907.muninn.Muninn): KeyPair? {
        val f = keyFile(app)
        val saved = app.vault.read(f)
        if (saved != null) return try {
            val o = JSONObject(saved)
            KeyPair(ChatCrypto.publicFrom(o.getString("k")), ChatCrypto.privateFrom(o.getString("s")))
        } catch (_: Exception) { app.vault.loadFailed(f); null }
        if (f.exists()) return null
        val kp = ChatCrypto.newKeyPair()
        app.vault.write(f, JSONObject().put("k", ChatCrypto.b64(kp.public.encoded)).put("s", ChatCrypto.b64(kp.private.encoded)).toString())
        return kp
    }
}
