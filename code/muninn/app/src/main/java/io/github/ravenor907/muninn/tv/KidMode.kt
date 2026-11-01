package io.github.ravenor907.muninn.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Contact
import io.github.ravenor907.muninn.net.Contacts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Whether this install is a Kids' TV. Decided once, on the first start on a TV (Android TV or Google TV):
 * a fresh install becomes a Kids' TV; a TV that already has a lock or contacts (someone used Muninn on it
 * before) stays a normal Muninn. Kept outside the vault and outside Muninn's own settings, so neither a
 * disconnect nor Erase all turns it off.
 */
object KidMode {
    private const val PREFS = "muninn-tv"
    /** "kids", "normal", or null while not decided (or not a TV). Compose state, so the screen follows. */
    private var mode by mutableStateOf<String?>(null)
    @Volatile private var loaded = false
    @Volatile private var tv: Boolean? = null

    fun isTv(context: Context): Boolean {
        tv?.let { return it }
        val ui = try { context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION } catch (e: Exception) { false }
        val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        return (ui || leanback).also { tv = it }
    }

    /** True if this install runs as a Kids' TV. Cheap: MainActivity asks on every composition. */
    fun on(app: Muninn): Boolean {
        if (app.ready.value) Tv.install(app)
        if (!loaded) { mode = prefs(app).getString("mode", null); loaded = true }
        mode?.let { return it == "kids" }
        if (!isTv(app)) return false
        if (app.vault.hasPassword) return decide(app, "normal")
        // No password but not open: the vault's data key file is gone. TvRoot starts the TV over.
        if (!app.ready.value) return true
        return decide(app, if (app.contacts.all.value.isEmpty()) "kids" else "normal")
    }

    /**
     * Before pairing only: run this TV as an ordinary Muninn (with a lock and Settings) from now on. Once a
     * parent has paired it, only a parent can let it go: their Disconnect with "make it a normal TV" ([free]).
     */
    fun useNormally(app: Muninn) { if (!paired(app)) decide(app, "normal") }

    /** A parent has paired this TV at some point and hasn't let it go: the kids can't leave kids' mode. */
    fun paired(context: Context): Boolean = prefs(context).getBoolean("paired", false)
    fun markPaired(context: Context) { if (!paired(context)) prefs(context).edit().putBoolean("paired", true).apply() }
    /** A parent's Disconnect said the TV may be used normally: the pairing screen offers it again. */
    fun free(context: Context) { prefs(context).edit().putBoolean("paired", false).apply() }

    /** Kids' mode as saved, without deciding anything: for Chat before the TV side is installed. */
    fun saved(context: Context): Boolean = prefs(context).getString("mode", null) == "kids"

    private fun decide(app: Muninn, m: String): Boolean {
        prefs(app).edit().putString("mode", m).apply()
        mode = m
        return m == "kids"
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * What a Kids' TV holds: its parents' keys (empty while pairing) and the config they last sent.
 * [micLocal]: a microphone level kept on the TV (7.7), used instead of the config's until a parent
 * changes it; [micAt]: when (this TV's clock). [nrLocal]: noise removal kept on the TV (7.10), the same way.
 */
data class KidState(val parents: List<String> = emptyList(), val cfg: TvConfig? = null, val micLocal: Int? = null, val micAt: Long = 0,
                    val nrLocal: Int? = null)

/**
 * The TV side of shared/docs/protocol.md section 7: pairing, taking configs, the people it may talk to, and
 * disconnect. Everything is kept in the vault in open mode (its data key in Muninn's own private files, no
 * password), so the TV opens straight to the kids' screen and never asks anyone to sign in.
 */
object KidTv {
    private const val FILE = "tv-kids.json"
    private val _state = MutableStateFlow(KidState())
    val state: StateFlow<KidState> = _state.asStateFlow()
    /** A parent disconnected this TV: erasing, then back to the pairing screen. */
    val wiping = MutableStateFlow(false)
    private val lock = Any()
    // "rich", "mx" and "live" (cap m1): replies, reactions, edits and typing from the same people.
    private val PRIVATE_ROOMS = setOf("private", "photo", "receipt", "unsend", "rich", "mx", "live")

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    fun load(app: Muninn) = synchronized(lock) {
        val o = try { app.vault.read(file(app))?.let { JSONObject(it) } } catch (e: Exception) { null }
        if (o == null) { _state.value = KidState(); return@synchronized }
        val ps = o.optJSONArray("parents")
        val parents = if (ps == null) emptyList() else (0 until ps.length()).map { ps.getString(it) }
        val cfg = o.optJSONObject("cfg")?.let { c -> try { TvRules.check(c); TvRules.parse(c) } catch (e: Exception) { null } }
        val local = if (o.has("micLocal")) o.optInt("micLocal").coerceIn(0, TvRules.MAX_MIC) else null
        val nr = if (o.has("nrLocal")) o.optInt("nrLocal").coerceIn(TvRules.NR_NONE, TvRules.NR_BOTH) else null
        _state.value = KidState(parents, cfg, local, o.optLong("micAt"), nr)
        if (KidMode.on(app)) tune(_state.value)
        setupAsk.value = parents.isNotEmpty() && prefs(app).getBoolean(SETUP_ASK, false)
    }

    /** The microphone level and noise removal (the TV's own, else the parent's) take effect on this TV. */
    private fun tune(s: KidState) {
        TvMic.active = s.cfg != null
        TvMic.level = s.micLocal ?: s.cfg?.mic ?: TvRules.DEFAULT_MIC
        TvMic.noise = s.nrLocal ?: s.cfg?.nr ?: TvRules.NR_BOTH
    }

    /**
     * k8 (7.13): a parent asked the TV to turn on one of its permissions ("mic", "notify", "overlay",
     * "install"). The kids' screen shows Android's prompt or opens its settings page, then clears this.
     */
    val allowAsk = MutableStateFlow<String?>(null)

    /**
     * A parent just paired this TV: the kids' screen asks for the microphone and notifications at once, while
     * the parent is still at the TV, so a child never meets Android's prompt. Kept until asked (a restart asks then).
     */
    val setupAsk = MutableStateFlow(false)
    private const val SETUP_ASK = "setupAsk"

    /** The setup prompts came up: not asked again. */
    fun setupAsked(context: Context) {
        setupAsk.value = false
        prefs(context).edit().putBoolean(SETUP_ASK, false).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("muninn-tv", Context.MODE_PRIVATE)

    private fun save(app: Muninn, s: KidState) {
        val o = JSONObject().put("parents", JSONArray(s.parents))
        s.cfg?.let { o.put("cfg", it.raw) }
        s.micLocal?.let { o.put("micLocal", it).put("micAt", s.micAt) }
        s.nrLocal?.let { o.put("nrLocal", it) }
        app.vault.write(file(app), o.toString())
    }

    /** The TV's permissions changed (one was turned on or off): its parents' phones are told, with the status. */
    fun permsChanged(app: Muninn) { if (_state.value.cfg != null) statusSoon(app) }

    /** The TV's update moved on (waiting, installing, done, failed): its parents' phones are told, with the status. */
    fun updateChanged(app: Muninn) { if (_state.value.cfg != null) statusSoon(app) }

    private val statusTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-status").apply { isDaemon = true } }
    @Volatile private var statusDue: java.util.concurrent.ScheduledFuture<*>? = null

    /** The status (7.7) to every parent whose app takes it, a moment after the last change. */
    private fun statusSoon(app: Muninn, to: List<String>? = null) {
        statusDue?.cancel(false)
        statusDue = statusTimer.schedule({ try { sendStatus(app, to) } catch (_: Exception) {} }, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun sendStatus(app: Muninn, to: List<String>?) {
        val s = _state.value
        val cfg = s.cfg ?: return
        val b = Tv.bridge(app) ?: return
        val ts = if (s.micLocal != null) s.micAt else cfg.ts
        val off = TvUpdate.needs(app).map { it.id }.filter { it in TvRules.PERMS }
        val msg = TvRules.statusMessage(TvMic.level, TvStorage.used(app), ts, own = s.micLocal != null, off = off, update = TvUpdate.state.value.code)
        for (k in to ?: s.parents) if ("k3" in (app.contacts.get(k)?.caps ?: emptySet())) try { b.sendTvRoom(k, msg) } catch (_: Exception) {}
    }

    /** The QR code a parent scans: this TV's contact code with a fresh invite (an hour) and "tv":1. */
    fun pairingCode(app: Muninn): String {
        // A fresh TV is named for what it is, not a random Nearby name.
        if (Regex("""Raven \d{4}""").matches(app.identity.username)) app.identity.username = "Kids' TV"
        val code = app.chat.myCode(once = false)
        val body = code.removePrefix(Contacts.PREFIX)
        val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)).put("tv", 1)
        return Contacts.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray(Charsets.UTF_8))
    }

    /** Kids' mode gate (see [TvHandler.allow]). Calls (k2): only with people on the list, unless a parent turned calls off. */
    fun allow(fromKey: String, room: String): Boolean {
        if (wiping.value) return false
        if (room == "tvconfig") return true
        val s = _state.value
        if (s.parents.isEmpty()) return false
        if (room !in PRIVATE_ROOMS && !(room == "call" && s.cfg?.calls != false)) return false
        return approved(fromKey)
    }

    /** On the parent's list (or a parent): the only people this TV talks to, calls included. */
    fun approved(key: String): Boolean {
        val s = _state.value
        return key in s.parents || s.cfg?.contacts?.any { it.k == key } == true
    }

    /** Room "tvconfig" on the TV: config and disconnect. */
    fun handle(app: Muninn, fromKey: String, mailbox: String?, plain: String, inviteOk: Boolean): Boolean = synchronized(lock) {
        if (wiping.value) return false
        val o = try { JSONObject(plain) } catch (e: Exception) { return false }
        val me = app.identity.publicKey
        val s = _state.value
        when (o.optString("t")) {
            "config" -> {
                val new = o.optJSONObject("cfg") ?: return false
                // A parent taken off the TV whose phone still sends its config: told again that they were removed.
                if (s.parents.isNotEmpty() && fromKey !in s.parents && new.optString("by") !in s.parents) { tellRemoved(app, fromKey); return false }
                val pairing = s.parents.isEmpty()
                val parents = if (pairing) {
                    // Pairing: only from the one who scanned a code this TV showed in the last hour, signing for themselves.
                    if (!inviteOk || new.opt("by") != fromKey) return false
                    listOf(fromKey)
                } else s.parents
                val (ok, _) = TvRules.accept(me, parents, s.cfg?.raw, new)
                if (!ok) {
                    // Older or the same from a parent (it opened with their key): say where the TV is, so their app stops resending.
                    // The TV's own config goes along, so a parent whose phone is behind (another parent's change,
                    // or a restored backup) catches up instead of thinking theirs went through.
                    val cur = s.cfg
                    if (cur != null && fromKey in s.parents) { answer(app, listOf(fromKey), cur.v, cur.raw); return true }
                    return false
                }
                val cfg = try { TvRules.parse(new) } catch (e: Exception) { return false }
                KidMode.markPaired(app)
                // Parents this config takes off: their phones stop looking after the TV (sent while they're still contacts).
                if (!pairing) for (k in TvRules.dropped(s.parents, cfg.parents)) send(app, k, TvRules.removedMessage(cfg.raw))
                apply(app, cfg, fromKey, mailbox)
                // Just paired: the microphone and notifications are asked for now, while the parent is at the TV.
                if (pairing) { prefs(app).edit().putBoolean(SETUP_ASK, true).apply(); setupAsk.value = true }
                // k8 (7.13): how long messages stay on the TV, as the parent set it.
                cfg.keep?.let { d ->
                    val ms = TvRules.keepMs(d)
                    if (app.identity.deleteAfter != ms) { app.identity.deleteAfter = ms; app.chat.pruneSoon() }
                }
                // A parent who picked another microphone level wins over one set on the TV (the last change wins);
                // a config that only carries the level on keeps the TV's own.
                val parentMic = s.cfg == null || cfg.mic != s.cfg.mic
                // Noise removal (7.10) the same way: a parent's new pick wins over the TV's own.
                val parentNr = s.cfg == null || cfg.nr != s.cfg.nr
                val next = (if (parentMic) KidState(cfg.parents, cfg) else KidState(cfg.parents, cfg, s.micLocal, s.micAt))
                    .copy(nrLocal = if (parentNr) null else s.nrLocal)
                save(app, next)
                _state.value = next
                tune(next)
                // Someone taken off the list (or calls turned off) mid-call: the call ends.
                io.github.ravenor907.muninn.net.call.CallManager.call.value?.let { c ->
                    if (!cfg.calls || !approved(c.peerKey)) io.github.ravenor907.muninn.net.call.CallManager.hangup()
                }
                answer(app, cfg.parents, cfg.v, cfg.raw)
                statusSoon(app)
                true
            }
            "update" -> {
                // k2: a parent's "Update TV". Only from a parent; the answer says which Muninn the TV runs now.
                val cur = s.cfg ?: return false
                if (fromKey !in s.parents) return false
                TvUpdate.checkNow(app)
                answer(app, listOf(fromKey), cur.v, cur.raw)
                true
            }
            "usage" -> {
                // k3 (7.7): a parent's phone asks how much room Muninn takes and which level is in use.
                if (s.cfg != null && fromKey !in s.parents) { tellRemoved(app, fromKey); return false }
                if (s.cfg == null || fromKey !in s.parents) return false
                TvStorage.later { sendStatus(app, listOf(fromKey)) }
                true
            }
            "clear" -> {
                // k3 (7.7): a parent's "Clear history": messages, photos, voice messages and caches. Pairing and contacts stay.
                if (s.cfg == null || fromKey !in s.parents) return false
                TvStorage.later { TvStorage.clear(app); sendStatus(app, null) }
                true
            }
            "allow" -> {
                // k8 (7.13): a parent asks the TV to turn on a permission; the kids' screen brings up Android's prompt.
                if (s.cfg == null || fromKey !in s.parents) return false
                val perm = TvRules.allowIn(o) ?: return false
                allowAsk.value = perm
                // Muninn not on screen: bring it up, so the prompt shows (Android may not allow it from the background).
                if (!app.visible) try {
                    app.startActivity(android.content.Intent(app, io.github.ravenor907.muninn.MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                } catch (_: Exception) {}
                true
            }
            "appok", "appno" -> {
                // k9 (7.15): a parent's Install or Not now for an app waiting in the TV's Downloads.
                if (s.cfg == null || fromKey !in s.parents) return false
                val (h, yes) = TvInstalls.parseAnswer(o) ?: return false
                TvAppInstall.answer(app, fromKey, h, yes, s.parents)
                true
            }
            "apps" -> {
                // k4 (7.9): a parent names the files of an app that's coming over the Wi-Fi; only those are taken.
                if (s.cfg == null || fromKey !in s.parents) return false
                val (name, parts) = TvApps.parse(o) ?: return false
                TvApps.allow(fromKey, name, parts)
                true
            }
            "disconnect" -> {
                val d = o.optJSONObject("d") ?: return false
                val cur = s.cfg ?: return false
                if (!TvRules.acceptDisconnect(me, s.parents, cur.v, d).first) return false
                wiping.value = true
                // A parent who said the TV may be used normally from now on: the pairing screen offers it again.
                if (o.optBoolean("free")) KidMode.free(app)
                answer(app, s.parents, cur.v)   // their apps stop resending; sent before the contacts go
                Thread { try { Thread.sleep(3000); wipe(app) } catch (_: Exception) {} finally { wiping.value = false } }.start()
                true
            }
            else -> false
        }
    }

    /** The TV's contacts become exactly the config's people (and its parents). People taken off go with their chats. */
    private fun apply(app: Muninn, cfg: TvConfig, fromKey: String, mailbox: String?) {
        val keep = cfg.contacts.map { it.k }.toSet() + cfg.parents
        app.contacts.all.value.filter { it.chatKey !in keep }.forEach {
            app.chat.deleteConversation(it.chatKey)
            app.chat.removeContact(it.chatKey)
        }
        for (p in cfg.contacts) upsert(app, p.k, p.n, p.p.takeIf { it.length == 64 })
        for (k in cfg.parents) if (cfg.contacts.none { it.k == k })
            upsert(app, k, app.contacts.get(k)?.name ?: "Parent", if (k == fromKey) mailbox else null)
    }

    private fun upsert(app: Muninn, key: String, name: String, mailbox: String?) {
        val c = app.contacts.get(key)
        if (c == null) app.contacts.put(Contact(key, name, mailbox, verified = true))
        else app.contacts.set(c.copy(name = name, mailbox = mailbox ?: c.mailbox, nickname = null))
    }

    /**
     * Someone on the TV's list who isn't a parent (any more) asked for a parent's thing: the TV's config, which
     * doesn't list them, so their phone stops looking after the TV. Never to anyone off the list.
     */
    private fun tellRemoved(app: Muninn, to: String) {
        val cur = _state.value.cfg ?: return
        if (to in cur.parents || cur.contacts.none { it.k == to }) return
        send(app, to, TvRules.removedMessage(cur.raw))
    }

    private fun send(app: Muninn, to: String, msg: String) { try { Tv.bridge(app)?.sendTvRoom(to, msg) } catch (_: Exception) {} }

    /** "applied" to [to], with the TV's signed config [cfg] so every parent's phone sees what the TV runs. */
    private fun answer(app: Muninn, to: List<String>, v: Long, cfg: JSONObject? = null) {
        val b = Tv.bridge(app) ?: return
        val version = try { app.updater.current } catch (e: Exception) { null }
        to.forEach { try { b.sendTvRoom(it, TvRules.appliedMessage(v, version, cfg)) } catch (_: Exception) {} }
    }

    /**
     * Paired, but the saved config can't be read (a damaged file, or an update that checks configs more
     * strictly): the TV stays a Kids' TV and tells its parents it has nothing (version 0), so their phones
     * send it again. Until then it shows "Getting this TV ready", never the pairing screen.
     */
    fun askParents(app: Muninn) {
        val s = _state.value
        if (s.parents.isNotEmpty() && s.cfg == null && !wiping.value) answer(app, s.parents, 0)
    }

    /**
     * Disconnect: erase the chats, caches, contacts, config and parent keys, then back to the pairing screen.
     */
    private fun wipe(app: Muninn) = synchronized(lock) {
        TvStorage.clear(app)   // every message and the caches (a downloaded update stays)
        setupAsked(app)
        app.contacts.all.value.forEach { app.chat.removeContact(it.chatKey) }
        file(app).delete()
        TvApps.clear()
        _state.value = KidState()
        tune(KidState())
    }

    /** The vault can't be opened (its data key file is gone): nothing on it is readable, so start the TV over. */
    fun startOver(app: Muninn) {
        app.vault.eraseAll()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** Connects Chat to the kids' TV: the TV side in kids' mode, the parent side everywhere else. */
object Tv : TvHandler {
    @Volatile private var app: Muninn? = null

    /** Once the vault is open (Muninn.start, or the first screen after it). Safe to call again. */
    fun install(app: Muninn) {
        val b = app.chat as? TvBridge
        if (this.app === app && (b == null || b.tvHandler === this)) return
        var first = false
        synchronized(this) {
            if (this.app !== app) { this.app = app; first = true; KidTv.load(app); TvParent.load(app) }
            b?.tvHandler = this
        }
        if (first && KidMode.on(app)) kids(app)
    }

    /**
     * A Kids' TV keeps listening with Muninn in the background (the foreground service, as on a phone, also
     * started after the TV boots), brings an incoming call up over whatever is on, and updates itself.
     */
    private fun kids(app: Muninn) {
        // A Kids' TV is always reachable at once, whatever "Check for messages" said before it became one.
        if (app.power.check != io.github.ravenor907.muninn.net.PowerLogic.Check.LIVE) app.power.check = io.github.ravenor907.muninn.net.PowerLogic.Check.LIVE
        if (!app.identity.stayConnected) app.identity.stayConnected = true
        io.github.ravenor907.muninn.net.MessageService.start(app)
        TvRing.watch(app)
        TvUpdate.start(app)
        TvStorage.start(app)
        KidTv.askParents(app)
    }

    fun bridge(app: Muninn): TvBridge? = if (app.ready.value) app.chat as? TvBridge else null

    override fun allow(fromKey: String, room: String): Boolean {
        // Never let anything through before the TV side knows its parents: only configs, which it checks itself.
        val a = app ?: return room == "tvconfig"
        return if (KidMode.on(a)) KidTv.allow(fromKey, room) else true
    }

    override fun onTvRoom(fromKey: String, mailbox: String?, plain: String, inviteOk: Boolean): Boolean {
        val a = app ?: return false
        return if (KidMode.on(a)) KidTv.handle(a, fromKey, mailbox, plain, inviteOk) else TvParent.handle(a, fromKey, plain)
    }
}
