package io.github.ravenor907.muninn.net

import android.content.Context
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address

/**
 * Moving to another device ("Move here", cap x1, shared/docs/protocol.md section 18). The new device shows a code
 * ([showCode]); the old one takes it inside its Move screen ([take]), both show the same 4 emoji, the old one says go
 * ([go]) and is locked from then on: it sends everything (identity, contacts, groups, history) straight over the home
 * Wi-Fi in sealed parts, and once the new device says it has it all and took it, the old one erases its keys and shows
 * "Moved to [name]", with one tap to become a linked device of the new one ([becomeLinked], for 10 minutes, and the new
 * device asks "Link [name]?"). An interrupted move goes on where it stopped; the old device stays locked until it's done,
 * or until the person stops it with their own lock ([abort]), or an hour passes with no sign of the new device.
 *
 * The secrets (K and the one-off keys) are kept in the vault ("move.json"); only the state to show before the vault
 * opens is in "muninn-move" settings. The parts on the new device stay sealed with K in the cache until they're taken in.
 */
object Move {
    /** On the new device. [state]: "shown" (code on screen), "hello" (emoji to check), "receiving", "done". */
    data class Incoming(val state: String, val emoji: String = "", val from: String = "", val have: Int = 0, val of: Int = 0, val confirmed: Boolean = false,
                        /** "failed": what went wrong. */
                        val note: String = "")
    /** On the old device. [state]: "checking" (emoji to check), "moving" (locked, sending), "moved". */
    data class Outgoing(val state: String, val emoji: String = "", val to: String = "", val sent: Int = 0, val of: Int = 0, val note: String = "",
                        /** "Moved to": linking is still possible (10 minutes after the move). */
                        val canLink: Boolean = false)

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming.asStateFlow()
    private val _outgoing = MutableStateFlow<Outgoing?>(null)
    val outgoing: StateFlow<Outgoing?> = _outgoing.asStateFlow()
    /** On the new device: the old one asks to become a linked device ("Link [name]?"). */
    private val _linkAsk = MutableStateFlow<String?>(null)
    val linkAsk: StateFlow<String?> = _linkAsk.asStateFlow()
    @Volatile private var linkCode: String? = null
    /** True from "go" until the move is done: this device can't send, read or receive as you. */
    @Volatile var locked = false
        private set

    private const val PREFS = "muninn-move"
    private const val FILE = "move.json"
    private const val OUT_FILE = "move-out.bin"
    private const val COMMON = "@move.json"
    /** Linking after a move: only this long after it. */
    const val LINK_MS = 10 * 60_000L
    /** The old device unlocks by itself after this long with no sign of the new one. */
    const val GIVE_UP_MS = 60 * 60_000L
    @Volatile private var app: Muninn? = null
    private val lock = Any()
    @Volatile private var lastHeard = 0L
    @Volatile private var stop = false

    // ---- what's shown before the vault opens (no secrets)
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun shown(c: Context, side: String) = prefs(c).getString(side, null)?.let { try { SafeJson.obj(it) } catch (e: Exception) { null } }
    private fun show(c: Context, side: String, o: JSONObject?) { prefs(c).edit().apply { if (o == null) remove(side) else putString(side, o.toString()) }.commit() }

    // ---- the secrets, in the vault
    private fun vaultJson(a: Muninn): JSONObject = try { a.vault.read(File(a.filesDir, FILE))?.let { SafeJson.obj(it) } } catch (e: Exception) { null } ?: JSONObject()
    private fun json(a: Muninn, side: String): JSONObject? = vaultJson(a).optJSONObject(side)
    private fun put(a: Muninn, side: String, o: JSONObject?) {
        val all = vaultJson(a)
        if (o == null) all.remove(side) else all.put(side, o)
        try { if (all.length() == 0) File(a.filesDir, FILE).delete() else a.vault.write(File(a.filesDir, FILE), all.toString()) } catch (_: Exception) {}
        val mirror = o?.let { JSONObject().put("state", it.optString("state")).put("to", it.optString("to")).put("from", it.optString("from"))
            .put("emoji", it.optString("emoji")).put("at", it.optLong("at")).put("note", it.optString("note")) }
        show(a, side, mirror)
    }

    /** States of a move to here after which nothing more is taken. */
    private val FINAL = setOf("taking", "taken", "done", "failed")

    /** Muninn started (before the vault opens): an unfinished move on this device goes on, locked. */
    fun init(a: Muninn) {
        app = a
        shown(a, "out")?.let { o ->
            when (o.optString("state")) {
                "moving" -> { locked = true; _outgoing.value = Outgoing("moving", o.optString("emoji"), o.optString("to")); Thread { send(a) }.start() }
                "moved" -> _outgoing.value = Outgoing("moved", to = o.optString("to"), canLink = System.currentTimeMillis() - o.optLong("at") < LINK_MS)
                "checking" -> show(a, "out", null)
            }
        }
        shown(a, "in")?.let { o -> _incoming.value = Incoming(o.optString("state"), o.optString("emoji"), o.optString("from"), note = o.optString("note")) }
    }

    /** The vault is open: a "Moved to" whose 10 minutes are over drops K. */
    fun ready(a: Muninn) {
        // A take-in that never finished (Muninn stopped in the middle, out of memory or killed): failed, said once, and
        // never started again by itself, so it can't stop Muninn over and over. The old device hears "failed".
        json(a, "in")?.let { s ->
            if (s.optString("state") == "taking") {
                File(a.cacheDir, "move-in").deleteRecursively()
                fail(a, s, "Muninn stopped while taking everything in. Nothing here changed. Try again, or leave pictures out with a backup instead.")
            }
        }
        val o = json(a, "out") ?: return
        if (o.optString("state") == "moved" && System.currentTimeMillis() - o.optLong("at") > LINK_MS) {
            put(a, "out", JSONObject().put("state", "moved").put("to", o.optString("to")).put("at", o.optLong("at")))
            _outgoing.value = _outgoing.value?.copy(canLink = false)
        }
    }

    /** The addresses of this device on the home Wi-Fi, with their prefix lengths. */
    private fun mine(): List<Pair<String, Int>> = LocalSend.lanInterfaces().flatMap { nif ->
        nif.interfaceAddresses.filter { it.address is Inet4Address }.map { it.address.hostAddress!! to it.networkPrefixLength.toInt() }
    }

    // ------------------------------------------------------------------ the new device

    /** The code to show (a new one each time, or the same while a move to here is under way). */
    fun showCode(a: Muninn): String = synchronized(lock) {
        val cur = json(a, "in")
        if (cur != null && cur.optString("state") in setOf("hello", "receiving")) return cur.getString("code")
        val kp = ChatCrypto.newKeyPair()
        val secret = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val addrs = mine().map { it.first }.filter { MoveWire.lanIp(it) }.map { "$it:${if (a.ready.value) a.net.serverPort else LocalSend.SERVER_FIRST}" }
        val code = MoveWire.code(MoveWire.Code(addrs, ChatCrypto.b64(kp.public.encoded), secret, a.identity.deviceName.take(40), "muninn"))
        put(a, "in", JSONObject().put("state", "shown").put("code", code).put("priv", ChatCrypto.b64(kp.private.encoded))
            .put("pub", ChatCrypto.b64(kp.public.encoded)).put("s", ChatCrypto.b64(secret)))
        File(a.cacheDir, "move-in").deleteRecursively()
        _incoming.value = Incoming("shown")
        a.listen()   // the Wi-Fi side must be up to be reached
        if (addrs.isEmpty()) "" else code
    }

    /** The person on the new device saw the same 4 emoji on both screens. */
    fun confirmHere(a: Muninn) {
        val o = json(a, "in") ?: return
        put(a, "in", o.put("ok", true))
        _incoming.value = _incoming.value?.copy(confirmed = true)
        Thread { try { finishIfReady(a) } catch (_: Exception) {} }.start()
    }

    /** Stop a move to here (the code stops working). */
    fun cancelHere(a: Muninn) { put(a, "in", null); File(a.cacheDir, "move-in").deleteRecursively(); _incoming.value = null }

    /** "Link [name]?" answered on the new device. */
    fun answerLink(a: Muninn, yes: Boolean) {
        val code = linkCode
        linkCode = null; _linkAsk.value = null
        if (yes && code != null) Link.link(code, confirmed = true)?.let { Link.host?.notice(it) }
        put(a, "in", null); _incoming.value = null
    }

    /** POST [MoveWire.PATH] on this device's Wi-Fi port. */
    fun handle(body: String): Pair<Int, String> = synchronized(lock) {
        val a = app ?: return 503 to "{}"
        if (!a.ready.value) return 503 to "{}"
        val p = try { SafeJson.obj(body) } catch (e: Exception) { return 400 to "{}" }
        val s = json(a, "in") ?: return 404 to "{}"
        when (p.optString("t")) {
            "hello" -> {
                if (s.optString("state") in FINAL) return 409 to "{}"
                val e = p.optString("e")
                // Once parts started coming, only the same old device.
                if (s.has("old") && s.optString("old") != e && s.optString("state") != "shown") return 409 to "{}"
                val k = try { MoveWire.key(ChatCrypto.privateFrom(s.getString("priv")), e, ChatCrypto.b64d(s.getString("s")), s.getString("pub"), e) }
                    catch (x: Exception) { return 400 to "{}" }
                if (!MoveWire.same(p.optString("m"), MoveWire.helloMac(k, e))) return 403 to "{}"
                if (s.optString("old") != e) {
                    s.put("old", e).put("k", ChatCrypto.b64(k)).put("from", p.optString("n").take(40)).put("app", p.optString("app")).put("emoji", MoveWire.emoji(k))
                        .put("state", "hello").put("ok", false)
                    put(a, "in", s)
                    _incoming.value = Incoming("hello", MoveWire.emoji(k), s.optString("from"))
                }
                return 200 to JSONObject().put("m", MoveWire.readyMac(k, e)).toString()
            }
            "part" -> {
                if (s.optString("state") in FINAL) return 409 to "{}"
                val k = s.optString("k").ifEmpty { return 403 to "{}" }.let { ChatCrypto.b64d(it) }
                val i = p.optInt("i", -1); val n = p.optInt("n"); val h = p.optString("h")
                if (n < 1 || n > 100_000 || i !in 0 until n || h.length != 64) return 400 to "{}"
                MoveWire.open(k, p.optString("d"), MoveWire.partAad(i, n, h)) ?: return 403 to "{}"
                if (s.optString("h") != h) { File(a.cacheDir, "move-in").deleteRecursively(); s.put("h", h).put("n", n) }
                val dir = File(a.cacheDir, "move-in").also { it.mkdirs() }
                // Kept as it came (still sealed with K) until it's all here.
                File(dir, i.toString()).writeText(p.optString("d"))
                if (s.optString("state") == "hello") s.put("state", "receiving")
                put(a, "in", s)
                val have = dir.list()?.size ?: 0
                _incoming.value = _incoming.value?.copy(state = "receiving", have = have, of = n)
                return 200 to JSONObject().put("have", have).toString()
            }
            "status" -> {
                val k = s.optString("k").ifEmpty { return 403 to "{}" }.let { ChatCrypto.b64d(it) }
                val h = s.optString("h"); val n = s.optInt("n")
                val st = when {
                    s.optString("state") == "done" -> "done"
                    // Being taken in, or taken in and restarting: never "need" again, so nothing is sent or taken in twice.
                    s.optString("state") == "taking" || s.optString("state") == "taken" -> "taking"
                    s.optString("state") == "failed" -> "failed"
                    h.isEmpty() || missing(a, n).isNotEmpty() -> "need"
                    !s.optBoolean("ok") -> "confirm"
                    else -> { Thread { try { finishIfReady(a) } catch (_: Exception) {} }.start(); "taking" }
                }
                return 200 to JSONObject().put("state", st).put("missing", JSONArray(if (h.isEmpty()) emptyList() else missing(a, n).take(64)))
                    .put("m", MoveWire.statusMac(k, st, h)).toString()
            }
            "link" -> {
                // Only right after the move (10 minutes), and only once the person says yes to "Link [name]?".
                if (s.optString("state") != "done" || System.currentTimeMillis() - s.optLong("at") > LINK_MS) { put(a, "in", null); _incoming.value = null; return 403 to "{}" }
                val k = s.optString("k").ifEmpty { return 403 to "{}" }.let { ChatCrypto.b64d(it) }
                val code = MoveWire.open(k, p.optString("d"), MoveWire.LINK_AAD)?.let { String(it) } ?: return 403 to "{}"
                linkCode = code
                _linkAsk.value = s.optString("from").ifBlank { "your old device" }
                return 200 to "{}"
            }
        }
        return 400 to "{}"
    }

    private fun missing(c: Context, n: Int): List<Int> {
        val dir = File(c.cacheDir, "move-in")
        return (0 until n).filter { !File(dir, it.toString()).exists() }
    }

    /** Everything is here and the person said the emoji match: take it in, then start again as you. */
    private fun finishIfReady(a: Muninn) { synchronized(lock) { takeIn(a) } }

    private fun takeIn(a: Muninn) {
        val s = json(a, "in") ?: return
        if (!s.optBoolean("ok") || s.optString("state") != "receiving") return
        val n = s.optInt("n"); val h = s.optString("h")
        if (n < 1 || missing(a, n).isNotEmpty()) return
        if (!a.ready.value) return
        val k = ChatCrypto.b64d(s.getString("k"))
        val dir = File(a.cacheDir, "move-in")
        // From here on status says "taking": the old device waits, and a second take-in can't start.
        put(a, "in", s.put("state", "taking"))
        // Done, and when: kept in the restored vault, so the old device hears "done" and may ask to link for 10 minutes.
        val done = JSONObject(s.toString()).put("state", "done").put("at", System.currentTimeMillis())
        try {
            // What was here is kept in a "kept-" folder first, as Restore does, so a mistaken move loses nothing. No copy, no move.
            try { a.vault.keepCopy() } catch (e: Exception) {
                fail(a, s, "There isn't room to keep a copy of what's here first, so nothing was taken in. Free some space and try again.")
                return
            }
            // Streamed part by part into the restore, one file at a time: any size the old device could send fits.
            val parts = MoveWire.Parts(n) { i -> MoveWire.open(k, File(dir, i.toString()).readText(), MoveWire.partAad(i, n, h)) }
            Backup.restore(a, a.vault) { stage ->
                var common: ByteArray? = null
                BackupFormat.entries(parts, { true }) { name, d ->
                    if (name == COMMON) common = d
                    else if (name != FILE && name != OUT_FILE && !name.startsWith(VaultCore.MOVE_OUT + "/")) stage(name, d!!)
                }
                if (parts.sha() != h) throw java.io.IOException("the payload didn't check out")
                common?.let { c -> MoveCommon.toMuninn(a, SafeJson.obj(String(c))).forEach { (name, d) -> if (name != FILE && name != OUT_FILE) stage(name, d) } }
                stage(FILE, JSONObject().put("in", done).toString().toByteArray())
            }
        } catch (e: MoveWire.BadPart) {
            // That part goes and is asked for again.
            File(dir, e.index.toString()).delete()
            put(a, "in", s.put("state", "receiving"))
            return
        } catch (e: Throwable) {
            dir.deleteRecursively()
            fail(a, s, if (e is OutOfMemoryError) "Too big to take in on this device. Nothing here changed." else "Couldn't take it in. Nothing here changed.")
            return
        }
        put(a, "in", s.put("state", "taken"))
        show(a, "in", JSONObject().put("state", "done").put("from", s.optString("from")).put("at", done.optLong("at")))
        dir.deleteRecursively()
        _incoming.value = Incoming("done", from = s.optString("from"))
        Thread { Thread.sleep(4000); Backup.restart(a) }.start()
    }

    /** The take-in failed: said here, and to the old device ("failed"), which is then its own again. */
    private fun fail(a: Muninn, s: JSONObject, note: String) {
        put(a, "in", s.put("state", "failed").put("note", note))
        _incoming.value = Incoming("failed", from = s.optString("from"), note = note)
    }

    // ------------------------------------------------------------------ the old device

    /**
     * Takes the new device's code, pasted or scanned inside the Move screen only (never from a link): says hello over
     * the Wi-Fi, only to addresses on this device's own network. Null when the emoji are on screen, else what went wrong.
     */
    fun take(a: Muninn, text: String): String? {
        val c = MoveWire.parse(text) ?: return "That isn't a move code from a device on your Wi-Fi. On the new device: Move here."
        val nets = mine()
        val addrs = c.a.filter { addr -> nets.any { (ip, prefix) -> MoveWire.sameNetwork(addr.substringBefore(':'), ip, prefix) } }
        if (addrs.isEmpty()) return "${c.n} isn't on the same Wi-Fi as this device. Moving only works on your own network."
        val kp = ChatCrypto.newKeyPair()
        val mine = ChatCrypto.b64(kp.public.encoded)
        val k = MoveWire.key(kp.private, c.e, c.s, c.e, mine)
        val hello = JSONObject().put("t", "hello").put("e", mine).put("n", a.identity.deviceName.take(40)).put("app", "muninn").put("m", MoveWire.helloMac(k, mine))
        for (addr in addrs) {
            val r = post(addr, hello.toString()) ?: continue
            if (r.first != 200) continue
            if (!MoveWire.same(try { SafeJson.obj(r.second).optString("m") } catch (e: Exception) { "" }, MoveWire.readyMac(k, mine))) continue
            put(a, "out", JSONObject().put("state", "checking").put("addr", addr).put("all", JSONArray(addrs)).put("k", ChatCrypto.b64(k))
                .put("to", c.n).put("app", c.app).put("emoji", MoveWire.emoji(k)))
            _outgoing.value = Outgoing("checking", MoveWire.emoji(k), c.n)
            return null
        }
        return "Couldn't reach ${c.n}. Both need to be on the same Wi-Fi, with Muninn or Huginn open on the Move here screen."
    }

    /**
     * The emoji match on both screens: lock this device, pack everything up and send it. From here on this device
     * can't send, read or receive as you; [send] runs until the new device has it all.
     */
    fun go(a: Muninn): String? {
        val o = json(a, "out") ?: return "Start again from the new device's code."
        if (o.optString("state") != "checking") return null
        locked = true
        // Packed straight into sealed parts in the vault, one file at a time: a big account never has to fit in memory.
        // Kept until the move is done, so an interrupted one goes on after a restart.
        val dir = File(a.filesDir, VaultCore.MOVE_OUT)
        val packer = MoveWire.Packer { i, part -> a.vault.writeBytes(File(dir, i.toString()), part) }
        try {
            dir.deleteRecursively(); dir.mkdirs()
            if (o.optString("app") == "muninn") Backup.entries(a, a.vault, skip = { it == FILE || it == OUT_FILE }) { name, d -> packer.entry(name, d) }
            else packer.entry(COMMON, MoveCommon.fromMuninn(a).toString().toByteArray())
            packer.finish()
        } catch (e: Throwable) {
            dir.deleteRecursively(); locked = false
            return if (e is OutOfMemoryError) "Too big to gather on this device." else "Couldn't gather your chats (${e.message})."
        }
        val n = MoveWire.parts(packer.size)
        put(a, "out", o.put("state", "moving").put("h", packer.sha).put("n", n).put("dir", true))
        _outgoing.value = Outgoing("moving", o.optString("emoji"), o.optString("to"), 0, n)
        try { a.nostr.keepAlive = false } catch (_: Exception) {}
        Thread { send(a) }.start()
        return null
    }

    /** Stop before "go": nothing was sent. */
    fun cancelOut(a: Muninn) { if (!locked) { put(a, "out", null); _outgoing.value = null } }

    /**
     * Stop a move under way (after the person's own lock or pattern, which the screen asks for first, or by itself after
     * [GIVE_UP_MS] with no sign of the new device): this device is yours again, as it was.
     */
    fun abort(a: Muninn, note: String = "Moving stopped. This device is yours again.") {
        stop = true
        locked = false
        try { File(a.filesDir, OUT_FILE).delete(); File(a.filesDir, VaultCore.MOVE_OUT).deleteRecursively() } catch (_: Exception) {}
        if (a.ready.value) put(a, "out", null) else show(a, "out", null)
        _outgoing.value = null
        try { a.nostr.keepAlive = true; a.nostr.connect() } catch (_: Exception) {}
        Link.host?.notice(note)
    }

    private fun send(a: Muninn) {
        stop = false
        lastHeard = System.currentTimeMillis()
        // After a restart: the secrets come from the vault once it's open.
        while (!a.ready.value && !stop) Thread.sleep(1000)
        val o = json(a, "out") ?: return
        val k = ChatCrypto.b64d(o.getString("k"))
        val h = o.getString("h"); val n = o.getInt("n")
        // The parts, sealed in the vault one by one (a move begun before they were kept that way: the whole payload).
        val dir = File(a.filesDir, VaultCore.MOVE_OUT)
        val blob = if (o.optBoolean("dir")) null else (a.vault.readBytes(File(a.filesDir, OUT_FILE)) ?: return)
        fun part(i: Int): ByteArray? = if (blob != null) blob.copyOfRange(i * MoveWire.PART, minOf(blob.size, (i + 1) * MoveWire.PART))
            else a.vault.readBytes(File(dir, i.toString()))
        val addrs = listOf(o.getString("addr")) + o.optJSONArray("all").let { x -> if (x == null) emptyList() else (0 until x.length()).map { x.getString(it) } }
        var tries = 0
        while (!stop) {
            var st: JSONObject? = null
            for (addr in addrs.distinct()) {
                val r = post(addr, JSONObject().put("t", "status").toString()) ?: continue
                val j = try { SafeJson.obj(r.second) } catch (e: Exception) { continue }
                // Signed with K over the state and the payload's hash (empty before the first part reached it).
                val state = j.optString("state")
                val good = MoveWire.same(j.optString("m"), MoveWire.statusMac(k, state, h)) || (state == "need" && MoveWire.same(j.optString("m"), MoveWire.statusMac(k, state, "")))
                if (r.first == 200 && good) { st = j.put("@addr", addr); break }
            }
            if (st == null) {
                if (System.currentTimeMillis() - lastHeard > GIVE_UP_MS) {
                    abort(a, "${o.optString("to")} wasn't heard from for an hour, so moving stopped. This device is yours again.")
                    return
                }
                _outgoing.value = _outgoing.value?.copy(note = "Waiting for ${o.optString("to")} on the Wi-Fi…")
                if (++tries > 3) Thread.sleep(5000) else Thread.sleep(1500)
                continue
            }
            lastHeard = System.currentTimeMillis()
            val addr = st.getString("@addr")
            when (st.optString("state")) {
                "done" -> { finishMoved(a, o); return }
                // The new device couldn't take it in (and says so with K): this one is yours again.
                "failed" -> { abort(a, "${o.optString("to")} couldn't take everything in, so moving stopped. This device is yours again."); return }
                "need" -> {
                    val m = st.optJSONArray("missing")
                    val want = if (m == null || m.length() == 0) (0 until n).toList() else (0 until m.length()).map { m.getInt(it) }
                    for (i in want) {
                        if (stop) return
                        val part = part(i) ?: run { abort(a, "This device couldn't read what it packed, so moving stopped. This device is yours again."); return }
                        val body = JSONObject().put("t", "part").put("i", i).put("n", n).put("h", h).put("d", MoveWire.seal(k, part, MoveWire.partAad(i, n, h)))
                        val r = post(addr, body.toString())
                        if (r?.first == 200) _outgoing.value = _outgoing.value?.copy(sent = try { SafeJson.obj(r.second).optInt("have") } catch (e: Exception) { 0 }, of = n, note = "")
                    }
                }
                "confirm" -> { _outgoing.value = _outgoing.value?.copy(sent = n, of = n, note = "Tap \"They match\" on ${o.optString("to")}."); Thread.sleep(1500) }
                else -> Thread.sleep(1500)
            }
        }
    }

    /**
     * The new device has everything: this one's keys and chats are erased, and it says where you went. A fresh, empty
     * vault keeps only K and where the new device is, for "Use as a linked device" in the next 10 minutes.
     */
    private fun finishMoved(a: Muninn, o: JSONObject) {
        val to = o.optString("to")
        a.vault.eraseAll()
        val at = System.currentTimeMillis()
        if (a.vault.openDefault()) put(a, "out", JSONObject().put("state", "moved").put("to", to).put("addr", o.optString("addr"))
            .put("all", o.optJSONArray("all") ?: JSONArray()).put("k", o.optString("k")).put("at", at))
        else show(a, "out", JSONObject().put("state", "moved").put("to", to).put("at", at))
        locked = false
        _outgoing.value = Outgoing("moved", to = to, canLink = true)
        Backup.restart(a)
    }

    /** One tap on "Moved to [name]": this device becomes a linked device of the new one, which asks first. Null when asked. */
    fun becomeLinked(a: Muninn): String? {
        if (!a.ready.value && !(a.vault.openDefault().also { if (it) a.start() })) return "Unlock Muninn first."
        val o = json(a, "out")
        if (o == null || System.currentTimeMillis() - o.optLong("at") > LINK_MS) { startFresh(a); return "That was more than 10 minutes ago. Link it from Settings › Devices and backup › Linked devices instead." }
        val k = ChatCrypto.b64d(o.getString("k"))
        val body = JSONObject().put("t", "link").put("d", MoveWire.seal(k, Link.myLinkCode().toByteArray(), MoveWire.LINK_AAD)).toString()
        val addrs = (listOf(o.optString("addr")) + o.optJSONArray("all").let { x -> if (x == null) emptyList() else (0 until x.length()).map { x.getString(it) } }).filter { it.isNotEmpty() }
        for (addr in addrs.distinct()) if (post(addr, body)?.first == 200) {
            startFresh(a)
            return null
        }
        return "Couldn't reach ${o.optString("to")}. Open Muninn or Huginn there, on the same Wi-Fi, and try again."
    }

    /** Leave "Moved to [name]" and use this device as a new one: K goes. */
    fun startFresh(a: Muninn) { if (a.ready.value) put(a, "out", null) else show(a, "out", null); _outgoing.value = null }

    /** A JSON POST to [addr] ("ip:port") on the move path, on the same network only. */
    private fun post(addr: String, json: String): Pair<Int, String>? = LocalSend.postTo(addr, MoveWire.PATH, json)
}
