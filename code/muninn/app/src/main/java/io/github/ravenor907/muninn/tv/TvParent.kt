package io.github.ravenor907.muninn.tv

import io.github.ravenor907.muninn.Muninn
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.ui.Ask
import io.github.ravenor907.muninn.ui.Ui
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A Kids' TV this phone set up (the contact's local "managed" flag, with what the parent chose). */
data class ManagedTv(
    val key: String,
    /** The last config version sent, and the version the TV last said it has. */
    val v: Long = 0, val applied: Long = 0,
    val quick: List<Quick> = emptyList(),
    /** Contacts (chat keys) the TV may reach, besides this phone. */
    val reach: List<String> = emptyList(),
    val dictation: Boolean = false,
    val parents: List<String> = emptyList(),
    /** How this phone's person shows on the TV ("Mom"). */
    val myName: String = "",
    /** Plaintexts in room "tvconfig" waiting for the TV's "applied", and since when. */
    val pending: List<String> = emptyList(), val pendingV: Long = 0, val since: Long = 0,
    /** Disconnect sent: gone from the screens, removed once the TV answers (or after a day). */
    val disconnecting: Boolean = false,
    /** k2 (7.5): calls with the people on the list, and the TV's microphone level 0..100. */
    val calls: Boolean = true, val mic: Int = TvRules.DEFAULT_MIC,
    /** The last config sent had the k2 fields; and the Muninn version the TV last said it runs. */
    val extrasSent: Boolean = false, val app: String = "",
    /** k3 (7.7): when [mic] last changed (here, or on the TV: then [micOnTv]); the TV's storage in bytes and when it said so. */
    val micAt: Long = 0, val micOnTv: Boolean = false, val used: Long = -1, val usedAt: Long = 0,
    /** People picked before they accepted this phone back: invited as soon as they're contacts. (key to since) */
    val waiting: Map<String, Long> = emptyMap(),
    /**
     * Names and mailboxes of people on the TV's list, as the TV's config has them: another parent may have
     * put someone on it this phone doesn't have as a contact, and they stay on the list all the same.
     */
    val others: List<TvPerson> = emptyList(),
    /** Other parents this phone already handed the TV to (a card with the config), so they get it once. */
    val sharedWith: List<String> = emptyList(),
    /** k5 (7.10): the TV's noise removal. */
    val nr: Int = TvRules.NR_BOTH,
    /** k7 (7.12): whether the TV looks for updates by itself (this TV's Auto-update). */
    val upd: Boolean = true,
    /** k8 (7.13): days messages stay on the TV (1 or 7). */
    val keep: Int = TvRules.MAX_KEEP,
    /** k8 (7.13): the TV's permissions still off, from its last status (null: not known). */
    val off: List<String>? = null,
    /** The TV's last update state from its status (one of [TvRules.UPDATE_STATES], or "" when not known). */
    val update: String = "",
) {
    val upToDate: Boolean get() = applied >= v && v > 0
}

/**
 * The parent side of the kids' TV: adds a TV from its code, sends signed configs when the parent changes
 * something (a second or so after the last change, so a run of edits is one config), resends until the TV
 * says "applied" (5 s, 10 s, 20 s, 60 s, then every 5 minutes, for a day), and disconnects it.
 */
object TvParent {
    private const val FILE = "tv-parent.json"
    private const val DAY = 86_400_000L
    private val RETRY = longArrayOf(5_000, 10_000, 20_000, 60_000, 300_000)
    private val _tvs = MutableStateFlow<Map<String, ManagedTv>>(emptyMap())
    val tvs: StateFlow<Map<String, ManagedTv>> = _tvs.asStateFlow()
    @Volatile private var app: Muninn? = null
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-parent").apply { isDaemon = true } }
    private val flushes = ConcurrentHashMap<String, ScheduledFuture<*>>()
    /** Per TV: (sends so far, next send ms). Not saved: after a restart the first resend comes soon. */
    private val tries = ConcurrentHashMap<String, Pair<Int, Long>>()
    /** Cards (7.6) waiting to learn whether the person's app takes one: (TV key, since). Not saved. */
    private val cards = ConcurrentHashMap<String, Pair<String, Long>>()
    /** Per TV: parents this phone just took off, told with the next config it signs. Not saved: the TV tells them too. */
    private val removed = ConcurrentHashMap<String, Set<String>>()
    @Volatile private var ticking = false

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    fun load(app: Muninn) {
        this.app = app
        synchronized(lock) {
            _tvs.value = try { app.vault.read(file(app))?.let { fromJson(JSONObject(it), app.identity.autoUpdate) } } catch (e: Exception) { null } ?: emptyMap()
        }
        if (_tvs.value.isNotEmpty()) ticking()
    }

    /** The 2-second check runs only on a phone that manages a TV (or is inviting someone to one), never on others. */
    @Synchronized private fun ticking() {
        if (ticking) return
        ticking = true
        timer.scheduleWithFixedDelay(Runnable { try { tick() } catch (_: Exception) {} }, 2, 2, TimeUnit.SECONDS)
    }

    /** A TV this phone manages (and hasn't disconnected): its chat offers the Kids' TV screen. */
    fun isManaged(key: String): Boolean = _tvs.value[key]?.disconnecting == false

    /** The chat key in a contact code that says "tv":1, or null for anyone else's code. */
    fun tvKeyIn(text: String): String? {
        val i = text.indexOf(Contacts.PREFIX)
        if (i < 0) return null
        val body = text.substring(i + Contacts.PREFIX.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        return try {
            val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
            if (o.optInt("tv") == 1) o.getString("k") else null
        } catch (e: Exception) { null }
    }

    /**
     * Call right after a contact was added from [codeText]. If it's a Kids' TV's code, asks the parent
     * and sends the TV its first config. True if it was a TV's code.
     */
    fun adoptIfTv(app: Muninn, codeText: String): Boolean {
        val key = tvKeyIn(codeText) ?: return false
        if (isManaged(key) || app.contacts.get(key) == null) return true
        Ui.sure(Ask(
            "Set up this Kids' TV?",
            "Anyone in the room can see it. You choose who it can reach.",
            "Set it up",
        ) { adopt(app, key) })
        return true
    }

    private fun adopt(app: Muninn, key: String) {
        val me = app.identity.publicKey
        put(ManagedTv(key, quick = TvRules.DEFAULT_QUICK.map { Quick(TvRules.quickId(), it) }, parents = listOf(me),
            myName = TvRules.clean(app.identity.username, TvRules.MAX_NAME)))
        flush(key)
        Ui.say("Setting up the TV. Press Allow on the TV for the microphone.")
    }

    /** Changes what the TV shows; the new config goes out a moment after the last change. */
    fun change(key: String, f: (ManagedTv) -> ManagedTv) {
        synchronized(lock) {
            val t = _tvs.value[key] ?: return
            if (t.disconnecting) return
            put(f(t))
        }
        flushes.remove(key)?.cancel(false)
        flushes[key] = timer.schedule(Runnable { try { flush(key) } catch (_: Exception) {} }, 1200, TimeUnit.MILLISECONDS)
    }

    /** People the TV may reach: this phone first, then the chosen contacts (still in Contacts). */
    fun people(app: Muninn, t: ManagedTv): List<TvPerson> {
        val me = TvPerson(app.identity.publicKey, TvRules.clean(t.myName, TvRules.MAX_NAME).ifEmpty { "Parent" }, app.nostr.publicKey)
        val others = t.reach.filter { it != t.key && it != me.k }.mapNotNull { k ->
            app.contacts.get(k)?.let { TvPerson(k, TvRules.clean(it.shown, TvRules.MAX_NAME).ifEmpty { "Someone" }, it.mailbox ?: "") }
                ?: t.others.firstOrNull { it.k == k }
        }
        return (listOf(me) + others).take(TvRules.MAX_CONTACTS)
    }

    private fun flush(key: String) {
        val app = app ?: return
        val plain: String
        val newParents: List<String>
        val signed: JSONObject
        synchronized(lock) {
            val t = _tvs.value[key] ?: return
            if (t.disconnecting) return
            val me = app.identity.publicKey
            val v = maxOf(t.v, t.applied) + 1
            val quick = t.quick.filter { it.text.isNotBlank() }.take(TvRules.MAX_QUICK)
            val extras = if (k2(app, key)) TvExtras(t.calls, t.mic, if (k5(app, key)) t.nr else null,
                if (k7(app, key)) t.upd else null, if (k8(app, key)) t.keep else null) else null
            val cfg = TvRules.config(app.identity.keys.private, key, v, me, t.parents + me, quick, people(app, t), t.dictation, System.currentTimeMillis(), extras)
            try { TvRules.check(cfg) } catch (e: IllegalArgumentException) { Ui.say("That can't go to the TV (${e.message}).", true); return }
            plain = TvRules.configMessage(cfg)
            newParents = TvRules.parse(cfg).parents.filter { it != me && it !in t.sharedWith }
            put(t.copy(v = v, pending = listOf(plain), pendingV = v, since = System.currentTimeMillis(), extrasSent = extras != null,
                sharedWith = (t.sharedWith + newParents).distinct()))
            tries[key] = 1 to System.currentTimeMillis() + RETRY[0]
            signed = cfg
        }
        send(app, key, listOf(plain))
        // A parent just added: their app gets the TV (a card) and this config, so it manages the TV too.
        val tv = app.contacts.get(key)
        if (tv != null) for (p in newParents) try {
            Tv.bridge(app)?.sendTvRoom(p, TvRules.cardMessage(Contacts.code(tv.name, key, tv.mailbox ?: ""), signed))
        } catch (_: Exception) {}
        // Parents just taken off: their phones get this config (which no longer lists them) and stop looking after the TV.
        val gone = removed.remove(key).orEmpty().filter { it !in TvRules.parse(signed).parents }
        if (gone.isNotEmpty()) { val msg = TvRules.removedMessage(signed); for (p in gone) try { Tv.bridge(app)?.sendTvRoom(p, msg) } catch (_: Exception) {} }
    }

    /** Another parent for the TV: someone this phone has as a contact, with a Muninn that manages TVs (k1). */
    fun addParent(app: Muninn, tvKey: String, person: String): String? {
        val c = app.contacts.contact(person) ?: return "Add them as a contact first."
        if ("k1" !in c.caps) { app.chat.probeCaps(person); return "${app.chat.nameOf(person)}'s app can't look after a Kids' TV yet. They need Muninn on a phone, up to date." }
        change(tvKey) { t -> t.copy(parents = (t.parents + person).distinct(), reach = (t.reach + person).distinct().take(TvRules.MAX_CONTACTS - 1)) }
        return null
    }

    /**
     * Takes a parent off the TV. From then on they can't change it; they stay on the list of people it can reach.
     * This phone and the TV (once it has the new config) tell their phone, which stops looking after the TV.
     */
    fun removeParent(app: Muninn, tvKey: String, person: String) {
        if (person == app.identity.publicKey) return
        removed.merge(tvKey, setOf(person)) { x, y -> x + y }
        change(tvKey) { t -> t.copy(parents = t.parents - person, sharedWith = t.sharedWith - person) }
    }

    /**
     * Disconnect: the TV erases everything and goes back to pairing. Signed for the TV's version and the last
     * one sent. [free]: the TV may then be used as a normal Muninn; otherwise it can only be paired again.
     */
    fun disconnect(key: String, free: Boolean = false) {
        val app = app ?: return
        val msgs: List<String>
        synchronized(lock) {
            val t = _tvs.value[key] ?: return
            flushes.remove(key)?.cancel(false)
            val me = app.identity.publicKey
            val now = System.currentTimeMillis()
            msgs = listOf(t.applied, t.v).filter { it >= 1 }.distinct()
                .map { TvRules.disconnectMessage(TvRules.disconnect(app.identity.keys.private, key, it, me, now), free) }
            if (msgs.isEmpty()) { forget(app, key); return }
            put(t.copy(disconnecting = true, pending = msgs, since = now))
            tries[key] = 1 to now + RETRY[0]
        }
        send(app, key, msgs)
    }

    /** The TV's app said "k2": it takes calls and mic in its config, and Update TV. */
    fun k2(app: Muninn, key: String): Boolean = "k2" in (app.contacts.get(key)?.caps ?: emptySet())

    /**
     * Room "tvconfig" on a phone that isn't a TV: the TV's {"t":"applied","v":n[,"app"]}, its status, "removed" (this
     * phone is no longer one of its parents), or a card (7.6) from a contact who invited this person to talk with their kids' TV.
     */
    fun handle(app: Muninn, fromKey: String, plain: String): Boolean {
        val o = try { JSONObject(plain) } catch (e: Exception) { return false }
        if (o.optString("t") == "card") return takeCard(app, fromKey, o.optString("code"), o.optJSONObject("cfg"))
        if (o.optString("t") == "status") return takeStatus(app, fromKey, o)
        if (o.optString("t") == "removed") return takeRemoved(app, fromKey, o)
        if (o.optString("t") != "applied") return false
        val v = o.optLong("v", -1)
        if (v < 0) return false
        val me = app.identity.publicKey
        // The TV's own config (when the TV sends it): what it really runs, checked like the TV checks it.
        val runs = TvRules.shared(fromKey, me, o.optJSONObject("cfg"))
        var resend = false
        var lostTo: String? = null
        synchronized(lock) {
            val t = _tvs.value[fromKey]
            if (t == null) {
                // A TV whose parents now include this phone: it shows up here, to look after with them.
                if (runs != null && app.contacts.get(fromKey) != null) join(app, fromKey, runs, null)
                return true
            }
            if (t.disconnecting) { forget(app, fromKey); return true }
            val version = Canon.runCatching { text(o.opt("app"), 16) }.getOrNull() ?: t.app
            when {
                // The TV runs something newer than this phone sent (or another parent's at the same version): their
                // change, or this phone's own from before a backup was restored. This phone takes it, so it shows
                // what's really on the TV.
                runs != null && runs.v > t.applied && (runs.v > t.pendingV || (runs.v == t.pendingV && runs.by != me)) -> {
                    if (t.pending.isNotEmpty() && runs.by != me) lostTo = app.chat.nameOf(runs.by)
                    put(adopted(app, t, runs).copy(app = version))
                    tries.remove(fromKey)
                }
                // The TV lost its settings (it says version 0): send it everything again.
                v == 0L && runs == null && t.applied > 0 -> { put(t.copy(app = version)); resend = true }
                // A TV app that sends no config and is ahead of this phone: this phone's change goes out on top of it.
                runs == null && v > t.pendingV && t.pending.isNotEmpty() -> {
                    put(t.copy(applied = v, v = maxOf(t.v, v), app = version)); resend = true
                }
                else -> {
                    val done = v >= t.pendingV
                    put(t.copy(applied = maxOf(t.applied, v), v = maxOf(t.v, v), pending = if (done) emptyList() else t.pending, app = version))
                    if (done) tries.remove(fromKey)
                    // The TV just showed it speaks k2: send the call and mic settings it hasn't had yet.
                    resend = done && !t.extrasSent && k2(app, fromKey) && (!t.calls || t.mic != TvRules.DEFAULT_MIC || (k5(app, fromKey) && t.nr != TvRules.NR_BOTH)
                        || (k7(app, fromKey) && !t.upd) || (k8(app, fromKey) && t.keep != TvRules.MAX_KEEP))
                }
            }
        }
        lostTo?.let { Ui.say("$it changed the TV just before you, so your last change didn't go through. Make it again.", true) }
        if (resend) change(fromKey) { it }
        return true
    }

    /** [t] as the TV's config [cfg] has it (another parent's, or newer than this phone's). */
    private fun adopted(app: Muninn, t: ManagedTv, cfg: TvConfig): ManagedTv {
        val me = app.identity.publicKey
        val people = cfg.contacts.filter { it.k != me && it.k != t.key }
        return t.copy(
            v = cfg.v, applied = cfg.v, pendingV = cfg.v, pending = emptyList(),
            quick = cfg.quick, reach = people.map { it.k }, others = people, dictation = cfg.dictation,
            parents = cfg.parents, calls = cfg.calls, mic = cfg.mic, extrasSent = cfg.raw.has("mic"), nr = cfg.nr,
            upd = if (cfg.raw.has("upd")) cfg.upd else t.upd, keep = cfg.keep ?: t.keep,
            myName = cfg.contacts.firstOrNull { it.k == me }?.n ?: t.myName,
            sharedWith = (t.sharedWith + cfg.parents.filter { it != me }).distinct(),
        )
    }

    /** This phone became one of the TV's parents ([by] added it, or the TV said so): it manages the TV from now on. */
    private fun join(app: Muninn, tvKey: String, cfg: TvConfig, by: String?) {
        val me = app.identity.publicKey
        put(adopted(app, ManagedTv(tvKey, myName = TvRules.clean(app.identity.username, TvRules.MAX_NAME)), cfg))
        val tvName = app.contacts.get(tvKey)?.shown ?: "the Kids' TV"
        val who = app.chat.nameOf(by ?: cfg.by)
        if (cfg.by != me) {
            Ui.say("$who made you a parent of $tvName. You can change what it shows from its chat.")
            try { app.notify(who, "Made you a parent of $tvName", tvKey) } catch (_: Exception) {}
        }
    }

    /**
     * k3 (7.7): the TV's microphone level and storage. A level set on the TV after the last change here
     * becomes this phone's too, and goes back out in a config, so every parent's screen shows it and
     * later configs carry it (the last change wins). The TV's clock is never compared with this one's:
     * a level the TV marks "own" is stamped with the time it arrived here.
     */
    private fun takeStatus(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val s = TvRules.status(o) ?: return false
        var adopt = false
        var news: String? = null
        synchronized(lock) {
            val t = _tvs.value[fromKey] ?: return true
            if (t.disconnecting) return true
            val now = System.currentTimeMillis()
            adopt = TvRules.adoptMic(s, t.mic, t.micAt, synced = t.applied >= t.v)
            // The TV's update moved on: said once here (installed, failed, or waiting for Install updates).
            if (s.update != null && s.update != t.update && t.update.isNotEmpty() && s.update in listOf("needs", "done", "failed")) news = s.update
            put(t.copy(used = s.used, usedAt = now, off = s.off ?: t.off,
                mic = if (adopt) s.mic else t.mic, micAt = if (adopt) (if (s.own) now else s.ts) else t.micAt,
                micOnTv = if (adopt) true else t.micOnTv, update = s.update ?: t.update))
        }
        news?.let { Ui.say("${app.contacts.get(fromKey)?.shown ?: "Kids' TV"}: ${TvRules.updateText(it)}", it != "done") }
        if (adopt) change(fromKey) { it }
        return true
    }

    /**
     * The TV (or a parent of it) says this phone is no longer one of its parents, with the signed config that
     * doesn't list it: this phone stops looking after the TV. The TV stays a contact, so the kids can still be
     * reached if it's on the list.
     */
    private fun takeRemoved(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val tvKey = o.optJSONObject("cfg")?.opt("tv") as? String ?: return false
        val me = app.identity.publicKey
        synchronized(lock) {
            val t = _tvs.value[tvKey] ?: return true
            val fromTv = fromKey == tvKey
            if (!fromTv && fromKey !in t.parents) return false
            // From the TV itself: its config, whoever of its parents signed it. From a parent: signed by one this phone knew,
            // and not older than what this phone has.
            TvRules.removedFrom(tvKey, me, if (fromTv) null else t.parents, if (fromTv) 0 else t.applied, o) ?: return false
            _tvs.value = _tvs.value - tvKey
            save(app)
        }
        flushes.remove(tvKey)?.cancel(false)
        tries.remove(tvKey)
        cards.keys.filter { cards[it]?.first == tvKey }.forEach { cards.remove(it) }
        removed.remove(tvKey)
        val tvName = app.contacts.get(tvKey)?.shown ?: "the Kids' TV"
        Ui.say("You're no longer a parent of $tvName.")
        try { app.notify(tvName, "You're no longer a parent of this TV", tvKey) } catch (_: Exception) {}
        return true
    }

    /** The parent moved the microphone slider here. */
    fun setMic(key: String, level: Int) = change(key) { it.copy(mic = level.coerceIn(0, TvRules.MAX_MIC), micAt = System.currentTimeMillis(), micOnTv = false) }

    /** The parent picked the TV's noise removal (k5, 7.10). */
    fun setNoise(key: String, nr: Int) = change(key) { it.copy(nr = nr.coerceIn(TvRules.NR_NONE, TvRules.NR_BOTH)) }

    /** The TV's app said "k5": it takes noise removal in its config. */
    fun k5(app: Muninn, key: String): Boolean = "k5" in (app.contacts.get(key)?.caps ?: emptySet())

    /** The TV's app said "k7": it takes Auto-update in its config (7.12). */
    fun k7(app: Muninn, key: String): Boolean = "k7" in (app.contacts.get(key)?.caps ?: emptySet())

    /** The parent switched the TV's Auto-update (7.12). */
    fun setUpd(key: String, on: Boolean) = change(key) { it.copy(upd = on) }

    /** The TV's app said "k8": it takes "keep" in its config, "allow", and lists its permissions in its status (7.13). */
    fun k8(app: Muninn, key: String): Boolean = "k8" in (app.contacts.get(key)?.caps ?: emptySet())

    /** The parent picked how many days messages stay on the TV (7.13). */
    fun setKeep(key: String, days: Int) = change(key) { it.copy(keep = days.coerceIn(TvRules.MIN_KEEP, TvRules.MAX_KEEP)) }

    /**
     * Asks the TV to turn on [perm] (7.13): the TV shows Android's prompt or opens its settings page, and
     * its next status says whether it's on. False if the TV's app lacks k8.
     */
    fun askAllow(app: Muninn, key: String, perm: String): Boolean {
        if (!k8(app, key) || perm !in TvRules.PERMS) { app.chat.probeCaps(key); return false }
        send(app, key, listOf(TvRules.allowMessage(perm)))
        return true
    }

    /** Takes [person] off the people the TV can reach (or stops waiting for them). They stay a parent if they are one. */
    fun removePerson(key: String, person: String) {
        dropWaiting(key, listOf(person))
        if (_tvs.value[key]?.reach?.contains(person) == true)
            change(key) { t -> t.copy(reach = t.reach - person, others = t.others.filter { it.k != person }) }
    }

    /** The TV's app said "k3": it reports its level and storage and takes Clear history. */
    fun k3(app: Muninn, key: String): Boolean = "k3" in (app.contacts.get(key)?.caps ?: emptySet())

    /** Asks the TV for its storage and level (the Kids' TV screen opens). False if its app lacks k3. */
    fun askUsage(app: Muninn, key: String): Boolean {
        if (!k3(app, key)) { app.chat.probeCaps(key); return false }
        send(app, key, listOf(TvRules.usageMessage()))
        return true
    }

    /** "Clear history": the TV erases its messages, photos, voice messages and caches. False if its app lacks k3. */
    fun clearTv(app: Muninn, key: String): Boolean {
        if (!k3(app, key)) return false
        send(app, key, listOf(TvRules.clearMessage()))
        return true
    }

    /**
     * Someone the parent picked who hasn't accepted this phone yet (Keep in touch, waiting for them): kept
     * on the TV's list of [ManagedTv.waiting] and invited by [tick] the moment they're a contact.
     */
    fun inviteLater(tvKey: String, people: Collection<String>) {
        if (people.isEmpty()) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val t = _tvs.value[tvKey] ?: return
            put(t.copy(waiting = t.waiting + people.associateWith { t.waiting[it] ?: now }))
        }
    }

    /** Stop waiting for someone (unticked before they accepted). */
    fun dropWaiting(tvKey: String, people: Collection<String>) = synchronized(lock) {
        val t = _tvs.value[tvKey] ?: return@synchronized
        if (people.any { it in t.waiting }) put(t.copy(waiting = t.waiting - people.toSet()))
    }

    /** People this phone asked to keep in touch who haven't said yes back yet (key, name): not contacts, so not invitable yet. */
    fun notYetConnected(app: Muninn): List<Pair<String, String>> =
        app.chat.met.value.filter { (k, m) -> m.iSaid && !m.theySaid && !m.dismissed && app.contacts.contact(k) == null }
            .map { (k, m) -> k to m.name }

    /** A card: add the TV as a contact, only from someone who is already a contact, and never as a key change. */
    private fun takeCard(app: Muninn, fromKey: String, code: String, cfg: JSONObject?): Boolean {
        if (app.contacts.get(fromKey) == null) return false
        val tv = Contacts.parse(code) ?: return false
        if (tv.chatKey == app.identity.publicKey) return true
        // A parent made this phone a parent too: the config they signed lists it (and them) as parents.
        val shared = TvRules.shared(tv.chatKey, app.identity.publicKey, cfg)?.takeIf { it.by == fromKey }
        if (app.contacts.get(tv.chatKey) != null) {
            if (shared != null && !isManaged(tv.chatKey)) synchronized(lock) { join(app, tv.chatKey, shared, fromKey) }
            return true
        }
        if (app.chat.keyChangeFor(tv) != null) return true
        app.chat.addContact(tv.copy(invite = null))
        if (shared != null) { synchronized(lock) { join(app, tv.chatKey, shared, fromKey) }; return true }
        val who = app.chat.nameOf(fromKey)
        Ui.say("$who added you to ${tv.name}. You can talk with the kids there now.")
        try { app.notify(who, "Added you to ${tv.name}: you can talk with the kids there now", tv.chatKey) } catch (_: Exception) {}
        return true
    }

    /**
     * Invite people to talk with the kids on the TV, all from this phone: they go on the TV's list (the
     * TV gets their key and mailbox in the next config) and their app gets the TV's code in a card, so it
     * adds the TV by itself. An app without t1 (unknown after a minute) gets the TV's link as a message.
     */
    fun invite(app: Muninn, tvKey: String, people: Collection<String>) {
        val add = people.filter { it != tvKey && it != app.identity.publicKey && app.contacts.get(it) != null }
        if (add.isEmpty()) return
        change(tvKey) { t -> t.copy(reach = (t.reach + add).distinct().take(TvRules.MAX_CONTACTS - 1)) }
        val now = System.currentTimeMillis()
        add.forEach { k -> app.chat.probeCaps(k); cards[k] = tvKey to now; sendCard(app, k, false) }
        if (add.isNotEmpty()) ticking()
    }

    /** True when it went (as a card, or as a message once [late]). */
    private fun sendCard(app: Muninn, person: String, late: Boolean): Boolean {
        val (tvKey, _) = cards[person] ?: return true
        val tv = app.contacts.get(tvKey) ?: run { cards.remove(person); return true }
        val code = Contacts.code(tv.name, tvKey, tv.mailbox ?: "")
        val caps = app.contacts.get(person)?.caps ?: emptySet()
        when {
            "t1" in caps -> Tv.bridge(app)?.sendTvRoom(person, TvRules.cardMessage(code))
            late -> app.chat.sendPrivate(person, "I added you to our Kids' TV so you can talk with the kids. Add it in Muninn: ${Contacts.link(code)}")
            else -> return false
        }
        cards.remove(person)
        return true
    }

    private fun notifyJoined(app: Muninn, k: String) {
        Ui.say("${app.chat.nameOf(k)} accepted, so they can talk with the kids on the TV now.")
    }

    /** The TV's app said "k4": it takes apps a parent sends (TvApps). */
    fun k4(app: Muninn, key: String): Boolean = "k4" in (app.contacts.get(key)?.caps ?: emptySet())

    /** Tells the TV which files of [name] are on their way (7.9). False if the TV's app lacks k4. */
    fun announceApp(app: Muninn, key: String, name: String, parts: List<TvApps.Part>): Boolean {
        if (!k4(app, key)) return false
        send(app, key, listOf(TvApps.message(name, parts)))
        return true
    }

    /** A parent's "Update TV": the TV looks for a new Muninn now and installs it. False if the TV's app lacks k2. */
    fun updateTv(app: Muninn, key: String): Boolean {
        if (!k2(app, key)) return false
        send(app, key, listOf(TvRules.updateMessage()))
        return true
    }

    /** Shares the TV's contact code, so someone the parent added can add the TV on their side. */
    fun shareText(app: Muninn, key: String): String? {
        val c = app.contacts.get(key) ?: return null
        val code = Contacts.code(c.name, key, c.mailbox ?: "")
        return "Add our Kids' TV in Muninn so it can reach you: ${Contacts.link(code)}"
    }

    private fun tick() {
        val app = app ?: return
        val now = System.currentTimeMillis()
        for ((person, w) in cards.entries.toList()) sendCard(app, person, now - w.second > 60_000)
        // Someone picked before they accepted: now a contact, so the invite goes through (or, after 30 days, it's let go).
        for (t in _tvs.value.values) for ((k, since) in t.waiting) {
            if (app.contacts.contact(k) != null) { dropWaiting(t.key, listOf(k)); invite(app, t.key, listOf(k)); notifyJoined(app, k) }
            else if (now - since > 30 * DAY) dropWaiting(t.key, listOf(k))
        }
        for (t in _tvs.value.values) {
            if (t.pending.isEmpty()) continue
            if (now - t.since > DAY) {
                // A day without an answer: a disconnected TV is let go; a config waits for the next change.
                synchronized(lock) { if (t.disconnecting) forget(app, t.key) else put(t.copy(pending = emptyList())) }
                tries.remove(t.key)
                continue
            }
            val (n, next) = tries[t.key] ?: (0 to 0L)
            if (now < next) continue
            tries[t.key] = (n + 1) to now + RETRY[minOf(n, RETRY.size - 1)]
            send(app, t.key, t.pending)
        }
    }

    private fun send(app: Muninn, key: String, msgs: List<String>) {
        val b = Tv.bridge(app) ?: return
        msgs.forEach { try { b.sendTvRoom(key, it) } catch (_: Exception) {} }
    }

    /** The TV is gone: drop it, its chat and the contact. */
    private fun forget(app: Muninn, key: String) {
        synchronized(lock) {
            _tvs.value = _tvs.value - key
            save(app)
        }
        tries.remove(key)
        try { app.chat.deleteConversation(key); app.chat.removeContact(key) } catch (_: Exception) {}
    }

    private fun put(t: ManagedTv) = synchronized(lock) {
        _tvs.value = _tvs.value + (t.key to t)
        app?.let { save(it) }
        ticking()
    }

    private fun save(app: Muninn) {
        try { app.vault.write(file(app), toJson(_tvs.value).toString()) } catch (_: Exception) {}
    }

    private fun toJson(m: Map<String, ManagedTv>): JSONObject = JSONObject().apply {
        for (t in m.values) put(t.key, JSONObject().put("v", t.v).put("applied", t.applied)
            .put("quick", JSONArray().apply { t.quick.forEach { put(JSONObject().put("id", it.id).put("text", it.text)) } })
            .put("reach", JSONArray(t.reach)).put("dictation", t.dictation).put("parents", JSONArray(t.parents))
            .put("myName", t.myName).put("pending", JSONArray(t.pending)).put("pendingV", t.pendingV).put("since", t.since)
            .put("disconnecting", t.disconnecting).put("calls", t.calls).put("mic", t.mic).put("extrasSent", t.extrasSent).put("app", t.app)
            .put("micAt", t.micAt).put("micOnTv", t.micOnTv).put("used", t.used).put("usedAt", t.usedAt)
            .put("waiting", JSONObject().apply { t.waiting.forEach { (k, v) -> put(k, v) } })
            .put("others", JSONArray().apply { t.others.forEach { put(JSONObject().put("k", it.k).put("n", it.n).put("p", it.p)) } })
            .put("sharedWith", JSONArray(t.sharedWith)).put("nr", t.nr).put("upd", t.upd).put("keep", t.keep).put("update", t.update)
            .apply { t.off?.let { put("off", JSONArray(it)) } })
    }

    /** [autoUpdate]: this phone's own update switch, for a TV saved without its own Auto-update. */
    private fun fromJson(o: JSONObject, autoUpdate: Boolean): Map<String, ManagedTv> = o.keys().asSequence().mapNotNull { k ->
        try {
            val x = o.getJSONObject(k)
            val q = x.optJSONArray("quick") ?: JSONArray()
            fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            k to ManagedTv(k, x.optLong("v"), x.optLong("applied"),
                (0 until q.length()).map { q.getJSONObject(it).let { e -> Quick(e.getString("id"), e.getString("text")) } },
                strs(x.optJSONArray("reach")), x.optBoolean("dictation"), strs(x.optJSONArray("parents")), x.optString("myName"),
                strs(x.optJSONArray("pending")), x.optLong("pendingV"), x.optLong("since"), x.optBoolean("disconnecting"),
                x.optBoolean("calls", true), x.optInt("mic", TvRules.DEFAULT_MIC), x.optBoolean("extrasSent"), x.optString("app"),
                x.optLong("micAt"), x.optBoolean("micOnTv"), x.optLong("used", -1), x.optLong("usedAt"),
                x.optJSONObject("waiting")?.let { w -> w.keys().asSequence().associateWith { w.optLong(it) } } ?: emptyMap(),
                x.optJSONArray("others")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { e -> TvPerson(e.getString("k"), e.getString("n"), e.getString("p")) } } } ?: emptyList(),
                strs(x.optJSONArray("sharedWith")), x.optInt("nr", TvRules.NR_BOTH).coerceIn(TvRules.NR_NONE, TvRules.NR_BOTH),
                x.optBoolean("upd", autoUpdate), x.optInt("keep", TvRules.MAX_KEEP).coerceIn(TvRules.MIN_KEEP, TvRules.MAX_KEEP),
                x.optJSONArray("off")?.let { strs(it) },
                x.optString("update").takeIf { it in TvRules.UPDATE_STATES } ?: "")
        } catch (e: Exception) { null }
    }.toMap()
}
