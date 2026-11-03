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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Whether this install is a Kids' Device. Decided once, on the first start on a TV (Android TV or Google TV):
 * a fresh install becomes a Kids' Device; a TV that already has a lock or contacts (someone used Muninn on it
 * before) stays a normal Muninn. A tablet or phone becomes one from first setup's welcome page ([makeKids]).
 * Kept outside the vault and outside Muninn's own settings, so neither a disconnect nor Erase all turns it off.
 * The saved names ("muninn-tv", "kids") stay as they were, so a paired TV keeps working.
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

    /** "tv", "tablet" or "phone" ([KidRules.DEVICES]): what the kids' screens look like, and what the parents' phones show. */
    fun device(context: Context): String = when {
        isTv(context) -> KidRules.TV
        context.resources.configuration.smallestScreenWidthDp >= 600 -> KidRules.TABLET
        else -> KidRules.PHONE
    }

    /**
     * First setup on a tablet or phone: this device becomes a Kids' Device and shows the pairing code. Only on a fresh
     * install (no lock, no contacts), like a TV. False when it can't.
     */
    fun makeKids(app: Muninn): Boolean {
        if (app.vault.hasPassword || !app.ready.value || app.contacts.all.value.isNotEmpty()) return false
        decide(app, "kids")
        Tv.startKids(app)
        return true
    }

    /** True if this install runs as a Kids' Device. Cheap: MainActivity asks on every composition. */
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
    fun useNormally(app: Muninn) {
        if (paired(app)) return
        // As Leave does: the kids' jobs stop, and its own name and settings come back.
        Tv.stopKids(app)
        KidReset.restore(app)
        KidReset.afterLeave(app)
        decide(app, "normal")
    }

    /**
     * k11: someone with the parent PIN (or a parent's phone) let it go: erased, it runs as an ordinary Muninn now. The kids'
     * jobs stop, its own name and settings come back, and first setup runs again (it can be made a Kids' Device again there).
     */
    internal fun leave(app: Muninn) {
        free(app)
        Tv.stopKids(app)
        KidReset.afterLeave(app)
        decide(app, "normal")
    }

    /** A parent has paired this TV at some point and hasn't let it go: the kids can't leave kids' mode. */
    fun paired(context: Context): Boolean = prefs(context).getBoolean("paired", false)
    fun markPaired(context: Context) { if (!paired(context)) prefs(context).edit().putBoolean("paired", true).apply() }
    /** A parent's Disconnect said the TV may be used normally: the pairing screen offers it again. */
    fun free(context: Context) { prefs(context).edit().putBoolean("paired", false).apply() }

    /** Kids' mode as saved, without deciding anything: for Chat before the TV side is installed. */
    fun saved(context: Context): Boolean = prefs(context).getString("mode", null) == "kids"

    private fun decide(app: Muninn, m: String): Boolean {
        // Becoming a Kids' Device: what this device had before is kept, so leaving kids' mode puts it all back.
        if (m == "kids" && prefs(app).getString("mode", null) != "kids") KidReset.saveOwner(app)
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
        // k11: the chatting level, quiet times and PIN kept from before an older parent app's config (7.18).
        val cfg = o.optJSONObject("cfg")?.let { c -> try { TvRules.check(c); KidRules.keptIn(TvRules.parse(c), o.optJSONObject("k11")) } catch (e: Exception) { null } }
        val local = if (o.has("micLocal")) o.optInt("micLocal").coerceIn(0, TvRules.MAX_MIC) else null
        val nr = if (o.has("nrLocal")) o.optInt("nrLocal").coerceIn(TvRules.NR_NONE, TvRules.NR_BOTH) else null
        _state.value = KidState(parents, cfg, local, o.optLong("micAt"), nr)
        me = try { app.identity.publicKey } catch (_: Exception) { "" }
        // k12: the parent's translation for Daily Hávamál, if one came.
        HavDevice.load(app)
        // k11: the trusted time kept from before Muninn restarted (while the device hasn't).
        loadAnchor(app)
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
        s.cfg?.let { o.put("cfg", it.raw); if (KidRules.needsKept(it.raw)) o.put("k11", KidRules.keptJson(it)) }
        s.micLocal?.let { o.put("micLocal", it).put("micAt", s.micAt) }
        s.nrLocal?.let { o.put("nrLocal", it) }
        app.vault.write(file(app), o.toString())
    }

    /** The TV's permissions changed (one was turned on or off): its parents' phones are told, with the status. */
    fun permsChanged(app: Muninn) { if (_state.value.cfg != null) statusSoon(app) }

    /** The TV's update moved on (waiting, installing, done, failed): its parents' phones are told, with the status. */
    fun updateChanged(app: Muninn) { if (_state.value.cfg != null) statusSoon(app) }

    /** u1 (7.17): an "upstate" to [to], or to every parent, only those whose app says u1. */
    fun sendUpd(app: Muninn, msg: String, to: List<String>? = null) {
        val s = _state.value
        if (s.cfg == null) return
        for (k in to ?: s.parents) if (k in s.parents && TvUpd.CAP in (app.contacts.get(k)?.caps ?: emptySet())) send(app, k, msg)
    }

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
        if (Regex("""Raven \d{4}""").matches(app.identity.username)) app.identity.username = when (KidMode.device(app)) {
            KidRules.TABLET -> "Kids' Tablet"
            KidRules.PHONE -> "Kids' Phone"
            else -> "Kids' TV"
        }
        val code = app.chat.myCode(once = false)
        val body = code.removePrefix(Contacts.PREFIX)
        val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)).put("tv", 1)
        return Contacts.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Kids' mode gate (see [TvHandler.allow]). Calls (k2): only with people on the list, unless a parent turned calls off.
     * Groups: only those a parent put this device in ([inMyGroup]: the sender is in one of its groups).
     */
    fun allow(fromKey: String, room: String, inMyGroup: (String) -> Boolean = { false }): Boolean {
        if (wiping.value) return false
        if (room == "tvconfig") return true
        val s = _state.value
        if (s.parents.isEmpty()) return false
        // Groups a parent put the device in: its parents and the people in those groups (the invite itself only from a
        // parent, which Groups checks with [groupInviteOk]).
        if (room in KidRules.GROUP_ROOMS) return KidRules.groupRoomOk(fromKey, room, s.parents, inMyGroup(fromKey), quietNow())
        if (room !in PRIVATE_ROOMS && !(room == "call" && s.cfg?.calls != false)) return false
        if (!approved(fromKey)) return false
        // k11 (7.18): during a quiet time only the parents get through (and a call already under way). Everyone else's
        // app keeps what they sent and tries again, so it arrives once the quiet time is over; nothing rings or shows here.
        if (room == "receipt" || io.github.ravenor907.muninn.net.call.CallManager.call.value?.peerKey == fromKey) return true
        return KidRules.passes(fromKey, s.parents, quietNow())
    }

    /** A group invite, or being put straight into a group: only from one of this device's parents. */
    fun groupInviteOk(fromKey: String): Boolean = KidRules.groupInviteOk(fromKey, _state.value.parents)

    // ---- k14 (7.18): group members no parent approved are held

    /** This device's own chat key (set when it loads). */
    @Volatile private var me = ""

    /** Whether [from]'s messages in group [g] are held: not this device, a parent, on its list, or approved for [g]. */
    fun heldNow(g: String, from: String): Boolean {
        val s = _state.value
        val c = s.cfg ?: return false
        return KidK14.held(from, g, me, s.parents, c.contacts.map { it.k }, c.gok)
    }

    private const val HELD_ASKED = "gheldAsked"

    /**
     * A message from [from] ([name]) in group [g] ([gn]) was held: the parents whose app takes it (k14) are asked once
     * whether to approve them for that group. Asked again only if no parent could be asked yet.
     */
    fun onHeld(app: Muninn, g: String, from: String, name: String, gn: String) {
        statusTimer.execute {
            try {
                val s = _state.value
                if (s.cfg == null) return@execute
                val id = "$g $from"
                val p = prefs(app)
                val asked = p.getStringSet(HELD_ASKED, emptySet()).orEmpty()
                if (id in asked) return@execute
                val to = s.parents.filter { KidK14.CAP in (app.contacts.get(it)?.caps ?: emptySet()) }
                if (to.isEmpty()) { s.parents.forEach { app.chat.probeCaps(it) }; return@execute }
                val msg = KidK14.holdMessage(HoldAsk(g, from, name, gn))
                to.forEach { send(app, it, msg) }
                p.edit().putStringSet(HELD_ASKED, (asked + id).toList().takeLast(200).toSet()).apply()
            } catch (_: Exception) {}
        }
    }

    // ---- trusted time (7.18): quiet times follow a parent's config time or a relay's clock, never the device's alone

    @Volatile private var anchor: TimeAnchor? = null
    @Volatile private var clockApp: Context? = null
    private const val ANCHOR_WALL = "anchorWall"
    private const val ANCHOR_AT = "anchorAt"
    private const val ANCHOR_BOOT = "anchorBoot"

    fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()

    /** How many times the device started (Android's count), so a time measured since start is known to still hold. -1: not known. */
    private fun bootCount(context: Context): Int =
        try { android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) } catch (_: Exception) { -1 }

    /** The anchor kept from before Muninn restarted, while the device itself hasn't restarted. */
    private fun loadAnchor(context: Context) {
        clockApp = context.applicationContext
        val p = prefs(context)
        val boot = bootCount(context)
        val at = p.getLong(ANCHOR_AT, -1L)
        if (boot >= 0 && p.getInt(ANCHOR_BOOT, -2) == boot && at in 0..elapsed()) anchor = TimeAnchor(p.getLong(ANCHOR_WALL, 0L), at)
    }

    /**
     * A trusted time [wall] (a parent's signed config, or a relay's clock from its connection's Date): the device's quiet
     * times follow it, plus the time since by the device's own uptime. Changing the device's clock or zone moves nothing.
     */
    fun heardTime(wall: Long) {
        val ctx = clockApp ?: return
        if (!KidMode.saved(ctx)) return
        val el = elapsed()
        val next = KidRules.anchor(anchor, wall, el) ?: return
        if (next == anchor) return
        anchor = next
        try { prefs(ctx).edit().putLong(ANCHOR_WALL, next.wall).putLong(ANCHOR_AT, next.at).putInt(ANCHOR_BOOT, bootCount(ctx)).apply() } catch (_: Exception) {}
    }

    /**
     * The quiet time on now, or null: by the trusted time in the parent's time zone. Only right after the device started,
     * before any trusted time came, its own clock stands in.
     */
    fun quietNow(): QuietTime? {
        val c = _state.value.cfg ?: return null
        if (c.quiet.isEmpty()) return null
        return KidRules.quietOnDevice(c.quiet, c.tz, anchor, elapsed(), System.currentTimeMillis(), java.util.TimeZone.getDefault())
    }

    // ---- k11 (7.18): the parent settings on the device, opened by the parent PIN or a parent's phone

    /** Until when the parent settings are open (elapsedRealtime, so moving the clock changes nothing); 0: closed. */
    val unlockedUntil = MutableStateFlow(0L)
    @Volatile private var asked: String? = null
    @Volatile private var askedAt = 0L
    /** The last ask from the device (elapsedRealtime, and this device's clock for "Asked at"); 0: none. */
    @Volatile private var lastAsk = 0L
    @Volatile var lastAskWall = 0L
        private set

    fun unlocked(now: Long = elapsed()): Boolean = unlockedUntil.value > now
    fun lockAgain() { unlockedUntil.value = 0L }

    /** The wrong tries so far. A restart of the device starts the uptime over: the wait then starts over from now. */
    private fun tries(context: Context): PinTries {
        val p = prefs(context)
        val fails = p.getInt(PIN_FAILS, 0)
        if (fails <= 0) return PinTries()
        val at = p.getLong(PIN_AT, 0L)
        val now = elapsed()
        if (p.getInt(PIN_BOOT, -2) != bootCount(context) || at > now) {
            p.edit().putLong(PIN_AT, now).putInt(PIN_BOOT, bootCount(context)).apply()
            return PinTries(fails, now)
        }
        return PinTries(fails, at)
    }

    /** How long before the next PIN try (ms, 0: now), after wrong ones. */
    fun pinWait(context: Context): Long = KidRules.waitLeft(tries(context), elapsed())

    /**
     * The parent PIN typed on the device ([KidRules.tryPin]): right, the parent settings open for 10 minutes. During a
     * wait it isn't checked. Slow on purpose (the hash): call it off the main thread.
     */
    fun tryPin(context: Context, pin: String): PinTry {
        val now = elapsed()
        val (r, t) = KidRules.tryPin(pin, _state.value.cfg?.pin, tries(context), now)
        prefs(context).edit().putInt(PIN_FAILS, t.fails).putLong(PIN_AT, t.at).putInt(PIN_BOOT, bootCount(context)).apply()
        if (r.ok) unlockedUntil.value = elapsed() + KidRules.UNLOCK_MS
        return r
    }
    private const val PIN_FAILS = "pinFails"
    private const val PIN_AT = "pinAt"
    private const val PIN_BOOT = "pinBoot"

    /** What [askUnlock] did. */
    enum class Asked { SENT, TOO_SOON, CHECKING, NEEDS_UPDATE }

    /**
     * Asks every parent whose app takes it (k11) to open the parent settings from their phone, at most once a minute.
     * CHECKING: what the parents' apps take isn't known yet (asked now); NEEDS_UPDATE: none of them takes it.
     */
    fun askUnlock(app: Muninn): Asked {
        val s = _state.value
        if (s.cfg == null) return Asked.CHECKING
        val now = elapsed()
        if (!KidRules.askAgainOk(lastAsk, now)) return Asked.TOO_SOON
        val caps = s.parents.map { app.contacts.get(it)?.caps ?: emptySet() }
        val to = s.parents.filterIndexed { i, _ -> KidRules.CAP in caps[i] }
        if (to.isEmpty()) {
            s.parents.forEach { app.chat.probeCaps(it) }
            return if (caps.any { it.isEmpty() }) Asked.CHECKING else Asked.NEEDS_UPDATE
        }
        val id = KidRules.askId()
        asked = id
        askedAt = now
        lastAsk = now
        lastAskWall = System.currentTimeMillis()
        val msg = KidRules.unlockAskMessage(id)
        to.forEach { send(app, it, msg) }
        return Asked.SENT
    }

    /**
     * Leave kids' mode, only while the parent settings are open: the parents' phones are told (they stop looking after
     * it), then everything is erased as a disconnect does, and Muninn runs as an ordinary one with a lock and Settings.
     */
    fun leave(app: Muninn) {
        if (!unlocked() || wiping.value) return
        val msg = KidRules.leftMessage()
        // The parents stop looking after it; everyone else on its list stops treating it as a Kids' Device (7.18).
        val s = _state.value
        (s.parents + (s.cfg?.contacts?.map { it.k } ?: emptyList())).distinct().forEach { send(app, it, msg) }
        wiping.value = true
        Thread {
            try { Thread.sleep(3000); wipe(app); KidMode.leave(app) } catch (_: Exception) {}
            finally { unlockedUntil.value = 0L; wiping.value = false }
        }.start()
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
                // k11 (7.18): a config from an older parent app (no k11 field) keeps this device's chatting level,
                // quiet times and PIN; one that carries a k11 field sets them.
                // It also only adds people and parents: taking someone off needs a parent app with k11.
                val cfg = try { KidRules.keepK11(TvRules.parse(new), s.cfg) } catch (e: Exception) { return false }
                KidMode.markPaired(app)
                // A parent's settings reached this device: Muninn runs normally, so the crash count starts over (SafeStart).
                SafeStart.healthy(app)
                // A parent signed it at this time: a trusted time for quiet times (an older one, resent late, moves nothing).
                heardTime(cfg.ts)
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
                SafeStart.healthy(app)
                TvUpdate.checkNow(app)
                answer(app, listOf(fromKey), cur.v, cur.raw)
                true
            }
            "upnow" -> {
                // u1 (7.17): a parent's Update now, from anywhere. Only from a current parent; they hear each step.
                if (s.cfg != null && fromKey !in s.parents) { tellRemoved(app, fromKey); return false }
                if (!TvUpd.fromParent(fromKey, s.parents, s.cfg != null) || !TvUpd.isNow(o)) return false
                SafeStart.healthy(app)
                TvUpdate.remoteNow(app, fromKey)
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
            "files", "filedel", "filemove" -> {
                // k10 (7.16): a parent's App storage (Labs): the TV's list, or deleting or moving files on its allowlist.
                if (s.cfg == null || fromKey !in s.parents) return false
                TvStorage.later {
                    val list = TvFiles.serve(app, o) ?: return@later
                    send(app, fromKey, list)
                    if (o.optString("t") != "files") sendStatus(app, null)
                }
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
            "unlock" -> {
                // k11 (7.18): a parent's phone said yes to the ask from this device: the parent settings open for 10 minutes.
                if (s.cfg == null || fromKey !in s.parents) return false
                val id = KidRules.idIn(o, "unlock") ?: return false
                // The device's uptime, never its clock: moving the clock neither stretches nor revives an ask.
                val now = elapsed()
                if (!KidRules.unlockOk(fromKey, s.parents, asked, askedAt, id, now)) return false
                asked = null
                unlockedUntil.value = now + KidRules.UNLOCK_MS
                true
            }
            "havtext" -> {
                // k12 (7.18): a part of a parent's own translation for Daily Hávamál. Only from a current parent; once
                // every part is in and its hash checks, it's kept, and that parent hears which translation the device holds.
                val cur = s.cfg ?: return false
                if (fromKey !in s.parents) return false
                val part = Havamal.partIn(o) ?: return false
                val json = HavDevice.assembler.add(part) ?: return true
                HavDevice.store(app, json)
                answer(app, listOf(fromKey), cur.v, cur.raw)
                true
            }
            // A card (7.6) for a Kids' Device: someone's parent invited it to talk with another device. A Kids' Device takes
            // people only from its own parents' configs, so it's taken (no resending) and nothing changes.
            "card" -> s.cfg != null && approved(fromKey)
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

    /**
     * The TV's contacts become exactly the config's people (and its parents). People taken off go with their chats. People
     * known only from a group a parent put it in stay (they're no contacts the kids can open).
     */
    private fun apply(app: Muninn, cfg: TvConfig, fromKey: String, mailbox: String?) {
        val keep = cfg.contacts.map { it.k }.toSet() + cfg.parents
        app.contacts.all.value.filter { it.chatKey !in keep && !it.groupOnly }.forEach {
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
        else app.contacts.set(c.copy(name = name, mailbox = mailbox ?: c.mailbox, nickname = null, groupOnly = false))
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
        // k11: what kind of device this is, so the parents' page shows it.
        val dev = try { KidMode.device(app) } catch (_: Exception) { null }
        // k12: which parent's translation it holds, so that parent's phone sends it again if it isn't the one in use.
        val havh = HavDevice.held()
        to.forEach { try { b.sendTvRoom(it, TvRules.appliedMessage(v, version, cfg, dev, havh)) } catch (_: Exception) {} }
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
        HavDevice.clear(app)
        // k14: anything a quiet time held, and the group members already asked about.
        KidHoldStore.clear(app)
        // Wrong PIN tries count only against the PIN they were typed for, never the next family's.
        prefs(app).edit().remove(HELD_ASKED).remove(PIN_FAILS).remove(PIN_AT).remove(PIN_BOOT).apply()
        // Everything else kids' mode changed (sizes, look, how long messages stay, drafts, waiting asks): put back.
        KidReset.restore(app)
        unlockedUntil.value = 0L
        asked = null
        lastAsk = 0L
        _state.value = KidState()
        tune(KidState())
    }

    /**
     * While the device runs: its status to the parents every 6 hours, so their page shows when it was last heard from
     * (and tells them when it goes quiet); and group invites, taken only from its parents, any other declined.
     */
    internal fun watch(app: Muninn) {
        stopWatch()
        statusJob = statusTimer.scheduleWithFixedDelay({ try { if (_state.value.cfg != null) sendStatus(app, null) } catch (_: Exception) {} },
            1L, 360L, java.util.concurrent.TimeUnit.MINUTES)
        invites = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            app.chat.groups.pending.collect { list ->
                // Only while it's a Kids' Device: an ordinary Muninn answers its own invites.
                if (!KidMode.on(app)) return@collect
                for (inv in list) try {
                    if (groupInviteOk(inv.from)) app.chat.groups.acceptInvite(inv) else app.chat.groups.declineInvite(inv)
                } catch (_: Exception) {}
            }
        }
    }
    @Volatile private var statusJob: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var invites: kotlinx.coroutines.Job? = null

    /** Left kids' mode: no more status to parents, and group invites are the person's own to answer again. */
    internal fun stopWatch() {
        statusJob?.cancel(false); statusJob = null
        invites?.cancel(); invites = null
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
        if (first && KidMode.on(app)) startKids(app)
    }
    @Volatile private var kidsStarted = false

    /**
     * A Kids' TV keeps listening with Muninn in the background (the foreground service, as on a phone, also
     * started after the TV boots), brings an incoming call up over whatever is on, and updates itself.
     */
    internal fun startKids(app: Muninn) {
        if (kidsStarted) return
        kidsStarted = true
        // A Kids' TV is always reachable at once, whatever "Check for messages" said before it became one.
        if (app.power.check != io.github.ravenor907.muninn.net.PowerLogic.Check.LIVE) app.power.check = io.github.ravenor907.muninn.net.PowerLogic.Check.LIVE
        if (!app.identity.stayConnected) app.identity.stayConnected = true
        // A kids' phone or tablet doesn't show itself to people nearby (a school's Wi-Fi): its parents' files still come over
        // the relays and the home Wi-Fi. Put back when it leaves kids' mode (KidReset).
        if (!KidMode.isTv(app) && app.identity.visibleNearby) app.identity.visibleNearby = false
        io.github.ravenor907.muninn.net.MessageService.start(app)
        TvRing.watch(app)
        TvUpdate.start(app)
        TvStorage.start(app)
        KidTv.askParents(app)
        KidTv.watch(app)
    }

    /** Left kids' mode: the trimming, the silent updates, the status to parents and the invite answers all stop. */
    internal fun stopKids(app: Muninn) {
        if (!kidsStarted) return
        kidsStarted = false
        try { TvStorage.stop() } catch (_: Exception) {}
        try { TvUpdate.stop(app) } catch (_: Exception) {}
        KidTv.stopWatch()
    }

    fun bridge(app: Muninn): TvBridge? = if (app.ready.value) app.chat as? TvBridge else null

    override fun allow(fromKey: String, room: String): Boolean {
        // Never let anything through before the TV side knows its parents: only configs, which it checks itself.
        val a = app ?: return room == "tvconfig"
        return if (KidMode.on(a)) KidTv.allow(fromKey, room) { k -> try { a.chat.groups.sharesGroup(k) } catch (_: Exception) { false } } else true
    }

    /** k14: on a Kids' Device, a group member no parent approved is held (their messages don't show or notify). */
    override fun groupHeld(g: String, fromKey: String): Boolean {
        val a = app ?: return false
        return KidMode.on(a) && KidTv.heldNow(g, fromKey)
    }

    override fun onGroupHeld(g: String, fromKey: String, name: String, groupName: String) {
        val a = app ?: return
        if (KidMode.on(a)) KidTv.onHeld(a, g, fromKey, name, groupName)
    }

    /** On a Kids' Device a group invite (or being put straight in a group) comes only from one of its parents. */
    override fun groupInviteOk(fromKey: String): Boolean {
        val a = app ?: return true
        return !KidMode.on(a) || KidTv.groupInviteOk(fromKey)
    }

    override fun onTvRoom(fromKey: String, mailbox: String?, plain: String, inviteOk: Boolean): Boolean {
        val a = app ?: return false
        return if (KidMode.on(a)) KidTv.handle(a, fromKey, mailbox, plain, inviteOk) else TvParent.handle(a, fromKey, plain)
    }
}
