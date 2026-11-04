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

/** A Kids' Device (TV, tablet or phone) this phone set up (the contact's local "managed" flag, with what the parent chose). */
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
    /** u1 (7.17): the TV's last "upstate" (null: none yet), and when it came (this phone's clock). */
    val upd1: TvUpd.St? = null, val upd1At: Long = 0,
    /** k11 (7.18): the chatting level, quiet times and parent PIN; whether the last config carried them; the device kind. */
    val chat: Int = KidRules.EVERYTHING, val quiet: List<QuietTime> = emptyList(), val pin: KidPin? = null,
    val k11Sent: Boolean = false, val dev: String = "",
    /**
     * k12 (7.18): Daily Hávamál on, the wording picked ([Havamal.BELLOWS] or [Havamal.MINE]), the translation hash the
     * device's config names (another parent's, when they picked theirs), and the one the device last said it holds.
     */
    val hav: Boolean = false, val havw: String = Havamal.BELLOWS, val havx: String = "", val havHeld: String = "",
    /**
     * k11: each person's name on the device (chat key to name), the same for every parent: set on the device page,
     * taken from the device's config, else the name the person gave themselves (never a nickname on this phone).
     */
    val names: Map<String, String> = emptyMap(),
    /** k11: the time zone its quiet times follow ("": this phone's). */
    val tz: String = "",
    /** When this phone last heard from the device (this phone's clock), and when it last said the device went quiet. */
    val seenAt: Long = 0, val silentTold: Long = 0,
    /** k13 (7.18): the colors picked for the device (the same choices as Settings › Look), and Let the kid choose. */
    val look: String = KidLook.DEFAULT.look, val season: String = KidLook.DEFAULT.season, val accent: String = KidLook.DEFAULT.accent,
    val lookPick: Boolean = true,
    /**
     * k14 (7.18): the child's name for the greeting (the same for every parent; "" for none), the accessibility picked
     * for the device (null fields: the device keeps its own), group members a parent approved and declined (group id to
     * chat key), group members the device asks about, other families' Kids' Devices on its list still waiting for their
     * parents (key to since), and other families' parents asking to put their Kids' Device on this one's list.
     */
    val child: String = "", val access: KidAccess = KidAccess(),
    val gok: List<Pair<String, String>> = emptyList(), val gno: List<Pair<String, String>> = emptyList(),
    val holdAsks: List<HoldAsk> = emptyList(), val waitKid: Map<String, Long> = emptyMap(),
    val kidAsks: List<KidAddAsk> = emptyList(),
    /**
     * Round 17 (7.18): people ([goneC]) and parents ([goneP]) taken off the device by a parent app with k11 (this phone, or
     * another parent's config it took): an older parent app's config can't bring them back.
     */
    val goneC: List<String> = emptyList(), val goneP: List<String> = emptyList(),
    /** k15 (7.18): the read-aloud voice picked for the device, "f" or "m" ("": none picked yet, the device keeps its own). */
    val voice: String = "",
    /** k15 (1.42): the read-aloud speed picked for the device, a whole percent 50 to 150 (null: none picked, it reads at 80%). */
    val speed: Int? = null,
) {
    val upToDate: Boolean get() = applied >= v && v > 0
}

/** Another family's parent ([from]) asks to put their Kids' Device on this one's list ([add]). */
data class KidAddAsk(val add: KidAdd, val from: String)

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
    /**
     * Round 17: per device, what it ran before this phone's change ([bases]), and the version, hash and state of the last
     * config this phone signed ([signed]): when another parent's change at the same version wins, this phone's change is
     * put on top of theirs. Not saved: after a restart the old message ("make it again") stands in.
     */
    private val bases = ConcurrentHashMap<String, ManagedTv>()
    private data class Signed(val v: Long, val hash: String, val state: ManagedTv)
    private val signed = ConcurrentHashMap<String, Signed>()
    /** Round 17: per device, the wrong-PIN count its parents were last told about. Not saved. */
    private val pinTold = ConcurrentHashMap<String, Int>()
    /** k12: per device, when this phone last sent it its translation. Not saved. */
    private val havSent = ConcurrentHashMap<String, Long>()
    @Volatile private var ticking = false

    private fun file(app: Muninn) = File(app.filesDir, FILE)

    fun load(app: Muninn) {
        this.app = app
        synchronized(lock) {
            _tvs.value = try { app.vault.read(file(app))?.let { fromJson(JSONObject(it), app.identity.autoUpdate) } } catch (e: Exception) { null } ?: emptyMap()
        }
        HavParent.load(app)
        loadKids(app)
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
        // Another family's Kids' Device, from its parent's Send its code: known as one (no setting it up), and that parent
        // is the one asked to add this phone's Kids' Device back when it goes on one's list.
        KidK14.kidCodeIn(codeText)?.let { (kid, by) ->
            if (kid != app.identity.publicKey && !isManaged(kid)) { markKid(app, kid); if (by != app.identity.publicKey) noteKidFrom(app, kid, by) }
            return true
        }
        val key = tvKeyIn(codeText) ?: return false
        markKid(app, key)
        if (isManaged(key) || app.contacts.get(key) == null) return true
        Ui.sure(Ask(
            "Set up this Kids' Device?",
            "Anyone near it can see what's on it. You choose who it can reach and what the kids can send.",
            "Set it up",
        ) { adopt(app, key) })
        return true
    }

    private fun adopt(app: Muninn, key: String) {
        val me = app.identity.publicKey
        // A new device starts at Quick messages only, in this phone's time zone; the parent opens more from its page.
        put(ManagedTv(key, quick = TvRules.DEFAULT_QUICK.map { Quick(TvRules.quickId(), it) }, parents = listOf(me),
            myName = TvRules.clean(app.identity.username, TvRules.MAX_NAME), chat = KidRules.QUICK_ONLY,
            tz = java.util.TimeZone.getDefault().id, seenAt = System.currentTimeMillis()))
        flush(key)
        Ui.say("Setting it up. Press Allow on the Kids' Device for the microphone.")
        // Straight to the device's page: who it can talk to, chatting, quiet times and the PIN are all there.
        try { io.github.ravenor907.muninn.screens.openTvSection(key, io.github.ravenor907.muninn.screens.TV_PAGE) } catch (_: Exception) {}
    }

    // ---- Kids' Devices this phone knows of (its own, and others' it was handed a card for or scanned)

    private const val KIDS_FILE = "tv-known.json"
    private val kidKeys = MutableStateFlow<Set<String>>(emptySet())
    @Volatile private var kidsLoaded = false

    private fun loadKids(app: Muninn) {
        if (kidsLoaded) return
        kidsLoaded = true
        kidKeys.value = try {
            app.vault.read(File(app.filesDir, KIDS_FILE))?.let { JSONArray(it) }?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        } catch (_: Exception) { null } ?: emptySet()
        try {
            app.vault.read(File(app.filesDir, KID_FROM_FILE))?.let { JSONObject(it) }?.let { o -> o.keys().forEach { k -> kidFrom[k] = o.getString(k) } }
        } catch (_: Exception) {}
    }

    /** k14: who handed this phone each other family's Kids' Device (a card, or their ask): the parent to ask to add one back. */
    private const val KID_FROM_FILE = "tv-kidfrom.json"
    private val kidFrom = ConcurrentHashMap<String, String>()
    /** Who first shared Kids' Device [kid] here (by card or Send its code), or null. */
    fun sharedBy(kid: String): String? = kidFrom[kid]

    private fun noteKidFrom(app: Muninn, kid: String, parent: String) {
        if (kidFrom[kid] == parent) return
        kidFrom[kid] = parent
        try { app.vault.write(File(app.filesDir, KID_FROM_FILE), JSONObject(kidFrom.toMap()).toString()) } catch (_: Exception) {}
    }

    private fun markKid(app: Muninn, key: String) {
        loadKids(app)
        if (key in kidKeys.value) return
        kidKeys.value = kidKeys.value + key
        try { app.vault.write(File(app.filesDir, KIDS_FILE), JSONArray(kidKeys.value.toList()).toString()) } catch (_: Exception) {}
    }

    /** [key] isn't a Kids' Device any more (it left kids' mode, or this phone let it go): an ordinary contact again. */
    private fun unmarkKid(app: Muninn, key: String) {
        loadKids(app)
        if (key in kidKeys.value) {
            kidKeys.value = kidKeys.value - key
            try { app.vault.write(File(app.filesDir, KIDS_FILE), JSONArray(kidKeys.value.toList()).toString()) } catch (_: Exception) {}
        }
        if (kidFrom.remove(key) != null) try { app.vault.write(File(app.filesDir, KID_FROM_FILE), JSONObject(kidFrom.toMap()).toString()) } catch (_: Exception) {}
    }

    /** A Kids' Device: one this phone looks after, or another family's it knows from a card or its code. */
    fun isKidDevice(key: String): Boolean = key in _tvs.value || key in kidKeys.value

    /** Group pickers on this phone: a Kids' Device shows only to one of its parents (7.18). */
    fun groupPickOk(key: String): Boolean = KidRules.groupPickOk(isKidDevice(key), isManaged(key))

    /** Changes what the TV shows; the new config goes out a moment after the last change. */
    fun change(key: String, f: (ManagedTv) -> ManagedTv) {
        synchronized(lock) {
            val t = _tvs.value[key] ?: return
            if (t.disconnecting) return
            // Round 17: the first change since the device confirmed everything: what it ran is kept as the base.
            if (flushes[key]?.isDone != false && t.pending.isEmpty() && t.applied >= t.v) bases[key] = t
            val n = f(t)
            // Who this change takes off (people or parents) is remembered; anyone put back is not.
            put(n.copy(goneC = KidMerge.gone(t.reach, n.reach, n.goneC), goneP = KidMerge.gone(t.parents, n.parents, n.goneP)))
        }
        flushes.remove(key)?.cancel(false)
        flushes[key] = timer.schedule(Runnable { try { flush(key) } catch (_: Exception) {} }, 1200, TimeUnit.MILLISECONDS)
    }

    /** People the TV may reach: this phone first, then the chosen contacts (still in Contacts). */
    fun people(app: Muninn, t: ManagedTv): List<TvPerson> {
        val myKey = app.identity.publicKey
        val me = TvPerson(myKey, TvRules.clean(t.names[myKey] ?: t.myName, TvRules.MAX_NAME).ifEmpty { "Parent" }, app.nostr.publicKey)
        // Every parent stays on the list, so the kids can always answer them.
        val others = (t.reach + t.parents).distinct().filter { it != t.key && it != me.k }.mapNotNull { k ->
            val known = t.others.firstOrNull { it.k == k }
            val c = app.contacts.get(k)
            if (c == null && known == null) return@mapNotNull null
            TvPerson(k, nameOn(t, k, c?.name, known?.n), c?.mailbox ?: known?.p ?: "")
        }
        return (listOf(me) + others).take(TvRules.MAX_CONTACTS)
    }

    /**
     * [k]'s name on the device: the one a parent set on the device page, else the device's config's ([fromConfig]), else
     * the name they gave themselves ([own]). Never this phone's private nickname for them.
     */
    private fun nameOn(t: ManagedTv, k: String, own: String?, fromConfig: String?): String =
        TvRules.clean(t.names[k] ?: fromConfig ?: own ?: "", TvRules.MAX_NAME).ifEmpty { "Someone" }

    /** The name [k] has on the device now (for the device page). */
    fun nameOnDevice(app: Muninn, t: ManagedTv, k: String): String = people(app, t).firstOrNull { it.k == k }?.n ?: app.chat.nameOf(k)

    /** A parent set [k]'s name on the device: the same on every parent's page once the config goes out. */
    fun setName(key: String, k: String, name: String) {
        val n = TvRules.clean(name, TvRules.MAX_NAME)
        if (n.isEmpty()) return
        change(key) { t -> t.copy(names = t.names + (k to n), myName = if (k == app?.identity?.publicKey) n else t.myName) }
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
            val k11 = k11(app, key)
            val k12 = k12(app, key)
            val k13 = k13(app, key)
            val k14 = k14(app, key)
            val (havw, havh) = havWording(t)
            val extras = if (k2(app, key)) TvExtras(t.calls, t.mic, if (k5(app, key)) t.nr else null,
                if (k7(app, key)) t.upd else null, if (k8(app, key)) t.keep else null,
                if (k11) t.chat else null, if (k11) t.quiet else null, if (k11) t.pin else null,
                if (k12) t.hav else null, if (k12) havw else null, if (k12) havh else null,
                // k11: the time zone quiet times follow (this phone's until a parent picks another; saved below).
                tz = if (k11) KidRules.zoneToSend(t.tz, java.util.TimeZone.getDefault().id) else null,
                // k13: the colors and Let the kid choose; the child's name a parent set (never the device's name).
                look = if (k13) t.look else null, season = if (k13) t.season else null, accent = if (k13) t.accent else null,
                lookPick = if (k13) t.lookPick else null, kname = if (k13) t.child.ifEmpty { null } else null,
                // k14: the child's name, accessibility, approved group members, other families' devices still waiting, and
                // each quick message's emoji.
                access = if (k14) t.access else null, child = if (k14) t.child.ifEmpty { null } else null,
                gok = if (k14) t.gok else null, wait = if (k14) t.waitKid.keys.toList() else null, pics = if (k14) true else null,
                // k15: the read-aloud voice, once a parent picked one, and only while every co-parent's app reads it.
                voice = KidVoice.toSend(t.voice, k15(app, key), coParentCaps(app, t)),
                // k16 (1.42): the speed beside it, only when the device and every co-parent's app say k16.
                speed = KidVoice.speedToSend(t.speed?.toFloat(), k16(app, key), coParentCaps(app, t))) else null
            val cfg = TvRules.config(app.identity.keys.private, key, v, me, t.parents + me, quick, people(app, t), t.dictation, System.currentTimeMillis(), extras)
            try { TvRules.check(cfg) } catch (e: IllegalArgumentException) { Ui.say("That can't go to the Kids' Device (${e.message}).", true); return }
            plain = TvRules.configMessage(cfg)
            newParents = TvRules.parse(cfg).parents.filter { it != me && it !in t.sharedWith }
            // Round 14: a device set up before 1.35 had no zone kept: the one this config carries is kept now, so the next
            // change (from any parent, wherever they are) sends the same zone instead of its own phone's.
            put(t.copy(v = v, pending = listOf(plain), pendingV = v, since = System.currentTimeMillis(), extrasSent = extras != null, k11Sent = extras?.chat != null,
                sharedWith = (t.sharedWith + newParents).distinct(), tz = KidRules.zoneToKeep(t.tz, extras?.tz)))
            _tvs.value[key]?.let { TvParent.signed[key] = Signed(v, Canon.hash(cfg), it) }
            tries[key] = 1 to System.currentTimeMillis() + RETRY[0]
            signed = cfg
        }
        send(app, key, listOf(plain))
        // k12: the device is to show this phone's own translation and doesn't hold it yet: it goes along.
        sendHavText(app, key, force = true)
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
        if ("k1" !in c.caps) { app.chat.probeCaps(person); return "${app.chat.nameOf(person)}'s app can't look after a Kids' Device yet. They need Muninn on a phone, up to date." }
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
        // Anything the device itself sends: it was heard from now ("Last seen" on its page).
        seen(fromKey)
        if (o.optString("t") == "card") return takeCard(app, fromKey, o.optString("code"), o.optJSONObject("cfg"))
        if (o.optString("t") == "status") return takeStatus(app, fromKey, o)
        if (o.optString("t") == "removed") return takeRemoved(app, fromKey, o)
        // k9 (7.15): an app waiting on a TV this phone looks after, or how its install went.
        if (o.optString("t") == "appask" || o.optString("t") == "appres") return _tvs.value.containsKey(fromKey) && TvAppAsks.handle(app, fromKey, o)
        // k10 (7.16): the TV's App storage list, for a parent in Labs mode.
        if (o.optString("t") == "filelist") return _tvs.value.containsKey(fromKey) && TvFiles.take(fromKey, o)
        // u1 (7.17): how the TV's update is going, only from a TV this phone looks after.
        if (o.optString("t") == "upstate") return takeUpstate(app, fromKey, o)
        // k11 (7.18): someone at the device asks to open its parent settings, or it left kids' mode.
        if (o.optString("t") == "unlockask") return takeUnlockAsk(app, fromKey, o)
        if (o.optString("t") == "left") return takeLeft(app, fromKey, o)
        // Round 17: another family's device on one of this phone's devices' lists left kids' mode; or the device was
        // already set up by another parent when this phone scanned its code.
        if (o.optString("t") == "kidleft") return takeKidLeft(app, fromKey, o)
        if (o.optString("t") == "taken") return takeTaken(app, fromKey, o)
        // k14 (7.18): a group member the device holds, and another family's Kids' Device.
        if (o.optString("t") == "ghold") return takeHold(app, fromKey, o)
        if (o.optString("t") == "kidadd") return takeKidAdd(app, fromKey, o)
        if (o.optString("t") == "kidok") return takeKidOk(app, fromKey, o)
        if (o.optString("t") != "applied") return false
        val v = o.optLong("v", -1)
        if (v < 0) return false
        val me = app.identity.publicKey
        // The TV's own config (when the TV sends it): what it really runs, checked like the TV checks it.
        val runs = TvRules.shared(fromKey, me, o.optJSONObject("cfg"))
        var resend = false
        var lostTo: String? = null
        var news: String? = null
        var both: String? = null
        var probe: ManagedTv? = null
        synchronized(lock) {
            val t = _tvs.value[fromKey]
            if (t == null) {
                // A TV whose parents now include this phone: it shows up here, to look after with them.
                if (runs != null && app.contacts.get(fromKey) != null) join(app, fromKey, runs, null)
                return true
            }
            if (t.disconnecting) { forget(app, fromKey); return true }
            val version = Canon.runCatching { text(o.opt("app"), 16) }.getOrNull() ?: t.app
            // k11: what kind of device it is (an older Kids' TV says nothing).
            val dev = KidRules.deviceIn(o).ifEmpty { t.dev }
            // k12: the translation the device holds ("" for none), kept on whatever this phone takes below.
            val held = (o.opt("havh") as? String)?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: ""
            // Round 17: another parent's config at the version this phone just signed (7.2: the lower hash stays on the device).
            val mine = signed[fromKey]
            val sameV = runs != null && runs.by != me && mine != null && runs.v == mine.v && Canon.hash(runs.raw) != mine.hash
            val theirsWins = sameV && KidMerge.lost(mine!!.hash, Canon.hash(runs!!.raw))
            when {
                // Ours has the lower hash: the device takes it once it gets it (or already has). Nothing to adopt; keep
                // waiting for its "applied", so neither phone says anything false.
                sameV && !theirsWins && t.pending.isNotEmpty() -> put(t.copy(app = version, dev = dev))
                // Theirs won, over a change of ours still on its way or one the device already ran: both changes go on,
                // ours made again on top of theirs as the next version.
                sameV && theirsWins && bases[fromKey] != null && (t.pending.isNotEmpty() || t.applied == runs!!.v) -> {
                    val theirs = adopted(app, t, runs!!).copy(app = version, dev = dev)
                    put(KidMerge.merge(bases[fromKey]!!, mine!!.state, theirs))
                    bases[fromKey] = theirs
                    signed.remove(fromKey)
                    tries.remove(fromKey)
                    both = app.chat.nameOf(runs.by)
                    resend = true
                }
                // The TV runs something newer than this phone sent (or another parent's at the same version): their
                // change, or this phone's own from before a backup was restored. This phone takes it, so it shows
                // what's really on the TV.
                runs != null && runs.v > t.applied && (runs.v > t.pendingV || (runs.v == t.pendingV && runs.by != me)) -> {
                    if (t.pending.isNotEmpty() && runs.by != me) lostTo = app.chat.nameOf(runs.by)
                    var next = adopted(app, t, runs).copy(app = version, dev = dev)
                    // Round 17: who a parent app with k11 took off is remembered here too.
                    if (KidRules.hasK11(runs.raw)) next = next.copy(goneC = KidMerge.gone(t.reach, next.reach, t.goneC), goneP = KidMerge.gone(t.parents, next.parents, t.goneP))
                    if (!KidRules.hasK11(runs.raw) && k11(app, fromKey)) {
                        // A co-parent's older app (no k11 field) may not have seen the newest list: the device took
                        // nobody off (7.18), and neither does this phone. Names stay as they were here, and the whole
                        // config goes out again, so the device and every parent's phone agree.
                        val before = people(app, t).associate { it.k to it.n }
                        next = next.copy(reach = (next.reach + t.reach).distinct().take(TvRules.MAX_CONTACTS - 1),
                            others = next.others + t.others.filter { x -> next.others.none { it.k == x.k } },
                            parents = (next.parents + t.parents).distinct(), names = next.names + before)
                        // Round 17: removals win. People and parents a newer parent app took off stay off (the device
                        // leaves them out too), and this phone's person hears that the older app tried.
                        val backC = next.reach.filter { it in t.goneC }
                        val backP = next.parents.filter { it in t.goneP && it != runs.by }
                        if (backC.isNotEmpty() || backP.isNotEmpty()) {
                            next = next.copy(reach = next.reach - backC.toSet(), others = next.others.filter { it.k !in backC },
                                parents = next.parents - backP.toSet())
                            val who = app.chat.nameOf(runs.by)
                            val tvName = app.contacts.get(fromKey)?.shown ?: "the Kids' Device"
                            val names = (backC + backP).distinct().joinToString(", ") { k -> before[k] ?: app.chat.nameOf(k) }
                            news = "$who's older Muninn tried to put $names back on $tvName. They stay off. Ask $who to update Muninn."
                        }
                        resend = true
                    } else if (runs.by != me && t.applied > 0) {
                        // A co-parent's change that the others should hear about.
                        val who = app.chat.nameOf(runs.by)
                        val tvName = app.contacts.get(fromKey)?.shown ?: "the Kids' Device"
                        val off = t.parents.filter { it !in runs.parents && it != me }
                        news = when {
                            runs.pin != t.pin -> if (runs.pin == null) "$who removed the parent PIN on $tvName." else "$who changed the parent PIN on $tvName."
                            off.isNotEmpty() -> "$who took ${off.joinToString(", ") { app.chat.nameOf(it) }} off as a parent of $tvName."
                            else -> null
                        }
                    }
                    put(next)
                    tries.remove(fromKey)
                }
                // The TV lost its settings (it says version 0): send it everything again.
                v == 0L && runs == null && t.applied > 0 -> { put(t.copy(app = version, dev = dev)); resend = true }
                // A TV app that sends no config and is ahead of this phone: this phone's change goes out on top of it.
                runs == null && v > t.pendingV && t.pending.isNotEmpty() -> {
                    put(t.copy(applied = v, v = maxOf(t.v, v), app = version, dev = dev)); resend = true
                }
                else -> {
                    val done = v >= t.pendingV
                    // Round 17: what the device confirmed is the new base for this phone's next change.
                    if (done && runs != null && runs.v > (signed[fromKey]?.v ?: 0L)) { bases.remove(fromKey); signed.remove(fromKey) }
                    // Round 14: no zone kept here yet (set up before 1.35): the one the device runs is kept.
                    put(t.copy(applied = maxOf(t.applied, v), v = maxOf(t.v, v), pending = if (done) emptyList() else t.pending, app = version, dev = dev,
                        tz = KidRules.zoneToKeep(t.tz, runs?.takeIf { KidRules.hasK11(it.raw) }?.tz)))
                    if (done) tries.remove(fromKey)
                    // The TV just showed it speaks k2: send the call and mic settings it hasn't had yet.
                    resend = done && !t.extrasSent && k2(app, fromKey) && (!t.calls || t.mic != TvRules.DEFAULT_MIC || (k5(app, fromKey) && t.nr != TvRules.NR_BOTH)
                        || (k7(app, fromKey) && !t.upd) || (k8(app, fromKey) && t.keep != TvRules.MAX_KEEP))
                    // The device just showed it speaks k11: its chatting level, quiet times and PIN go out.
                    if (done && !t.k11Sent && k11(app, fromKey) && (t.chat != KidRules.EVERYTHING || t.quiet.isNotEmpty() || t.pin != null)) resend = true
                    // The device just showed it speaks k12: Daily Hávamál as the parent set it goes out.
                    if (done && runs != null && !Havamal.hasOn(runs.raw) && k12(app, fromKey) && (t.hav || t.havw == Havamal.MINE)) resend = true
                    // The device just showed it speaks k13: the colors as the parent set them go out.
                    if (done && runs != null && !runs.raw.has("look") && k13(app, fromKey)) resend = true
                    // The device just showed it speaks k14: its accessibility, child's name and the rest go out.
                    if (done && runs != null && !runs.raw.has("gok") && k14(app, fromKey)) resend = true
                    // Round 19: the device runs a voice pick that a co-parent's older app can't read (that parent was
                    // added, or the pick went out before): the config goes out again without it, so they see it all again.
                    if (done && runs != null && ((KidVoice.of(runs.raw) != null && !KidVoice.allRead(coParentCaps(app, t))) || (KidVoice.speedOf(runs.raw) != null && !KidVoice.allReadSpeed(coParentCaps(app, t))))) { resend = true; probe = t }
                    // ...and once every co-parent reads it, the voice (and 1.42: the speed) picked here goes out.
                    if (done && runs != null && KidVoice.of(runs.raw) == null && t.voice.isNotEmpty() && k15(app, fromKey) && KidVoice.allRead(coParentCaps(app, t))) resend = true
                    if (done && runs != null && KidVoice.speedOf(runs.raw) == null && t.speed != null && k16(app, fromKey) && KidVoice.allReadSpeed(coParentCaps(app, t))) resend = true
                }
            }
            _tvs.value[fromKey]?.let { if (it.havHeld != held) put(it.copy(havHeld = held)) }
        }
        // Round 19: a co-parent's caps may be old (they updated): asked again, so the voice goes out once they read it.
        probe?.let { probeVoice(app, it) }
        lostTo?.let { Ui.say("$it changed the Kids' Device just before you, so your last change didn't go through. Make it again.", true) }
        both?.let { Ui.say("$it changed the Kids' Device at the same moment as you. Your change is going out again on top of theirs, so both are on it.") }
        news?.let { n ->
            Ui.say(n)
            try { app.notice(app.contacts.get(fromKey)?.shown ?: "Kids' Device", n, pageOf(fromKey), "change$fromKey") } catch (_: Exception) {}
        }
        // Round 17: after a merge the base stays what the device runs (theirs), so the next version goes out as it is.
        if (resend && both != null) flushSoon(fromKey)
        else if (resend) change(fromKey) { it }
        else sendHavText(app, fromKey, force = false)
        return true
    }

    private fun flushSoon(key: String) {
        flushes.remove(key)?.cancel(false)
        flushes[key] = timer.schedule(Runnable { try { flush(key) } catch (_: Exception) {} }, 1200, TimeUnit.MILLISECONDS)
    }

    /** [t] as the TV's config [cfg] has it (another parent's, or newer than this phone's). */
    private fun adopted(app: Muninn, t: ManagedTv, cfg: TvConfig): ManagedTv {
        val me = app.identity.publicKey
        val people = cfg.contacts.filter { it.k != me && it.k != t.key }
        return t.copy(
            v = cfg.v, applied = cfg.v, pendingV = cfg.v, pending = emptyList(),
            // k14: an older parent app's config has no pictures on its quick messages: each keeps the one it had here.
            quick = KidK14.keepPics(cfg.quick, t.quick, cfg.raw.has("gok")), reach = people.map { it.k }, others = people, dictation = cfg.dictation,
            parents = cfg.parents, calls = cfg.calls, mic = cfg.mic, extrasSent = cfg.raw.has("mic"), nr = cfg.nr,
            upd = if (cfg.raw.has("upd")) cfg.upd else t.upd, keep = cfg.keep ?: t.keep,
            // An older parent app's config has no k11 field: the device kept its level, quiet times and PIN (7.18), so does this phone.
            chat = if (KidRules.hasK11(cfg.raw)) cfg.chat else t.chat, quiet = if (KidRules.hasK11(cfg.raw)) cfg.quiet else t.quiet,
            pin = if (KidRules.hasK11(cfg.raw)) cfg.pin else t.pin, k11Sent = cfg.raw.has("chat"),
            // k12: an older parent app's config leaves Daily Hávamál as it was; a translation named is kept by its hash.
            hav = if (Havamal.hasOn(cfg.raw)) cfg.hav else t.hav,
            havw = if (Havamal.hasWording(cfg.raw)) cfg.havw else t.havw,
            havx = if (Havamal.hasWording(cfg.raw)) cfg.havh ?: "" else t.havx,
            myName = cfg.contacts.firstOrNull { it.k == me }?.n ?: t.myName,
            sharedWith = (t.sharedWith + cfg.parents.filter { it != me }).distinct(),
            // k11: everyone's name on the device as its config has them (the same for every parent), and its time zone.
            names = cfg.contacts.associate { it.k to it.n }, tz = if (KidRules.hasK11(cfg.raw)) cfg.tz else t.tz,
            // k13: another parent's colors; a config without them leaves this phone's as they were.
            look = cfg.look ?: t.look, season = cfg.season ?: t.season, accent = cfg.accent ?: t.accent,
            lookPick = if (cfg.raw.has("lookpick")) cfg.lookPick else t.lookPick,
            // k14: another parent's child's name (the latest change wins), accessibility, approved members and waiting devices;
            // a config without one leaves this phone's as it was.
            child = if (cfg.raw.has("child")) cfg.child else t.child,
            access = KidAccess(
                if (cfg.raw.has("tsz")) cfg.access.text else t.access.text, if (cfg.raw.has("dsz")) cfg.access.size else t.access.size,
                if (cfg.raw.has("hc")) cfg.access.hc else t.access.hc, if (cfg.raw.has("cv")) cfg.access.cv else t.access.cv,
                if (cfg.raw.has("still")) cfg.access.still else t.access.still),
            gok = if (cfg.raw.has("gok")) cfg.gok else t.gok,
            waitKid = if (cfg.raw.has("wait")) cfg.wait.associateWith { t.waitKid[it] ?: System.currentTimeMillis() } else t.waitKid,
            // k15: another parent's voice pick (the latest config wins); a config without one leaves this phone's as it was.
            voice = KidVoice.of(cfg.raw) ?: t.voice,
            speed = KidVoice.speedOf(cfg.raw) ?: t.speed,
        )
    }

    /** Anything from a device this phone looks after: when it was last heard from (saved at most every half minute). */
    private fun seen(fromKey: String) = synchronized(lock) {
        val t = _tvs.value[fromKey] ?: return@synchronized
        val now = System.currentTimeMillis()
        if (!t.disconnecting && now - t.seenAt > 30_000) put(t.copy(seenAt = now))
    }

    /** This phone became one of the TV's parents ([by] added it, or the TV said so): it manages the TV from now on. */
    private fun join(app: Muninn, tvKey: String, cfg: TvConfig, by: String?) {
        val me = app.identity.publicKey
        put(adopted(app, ManagedTv(tvKey, myName = TvRules.clean(app.identity.username, TvRules.MAX_NAME)), cfg))
        val tvName = app.contacts.get(tvKey)?.shown ?: "the Kids' Device"
        val who = app.chat.nameOf(by ?: cfg.by)
        if (cfg.by != me) {
            Ui.say("$who made you a parent of $tvName. You can change what it shows from its page.")
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
        news?.let {
            val name = app.contacts.get(fromKey)?.shown ?: "Kids' Device"
            Ui.say("$name: ${TvRules.updateText(it)}", it != "done")
            // Not only while Muninn is open: a notification, so a parent away from home hears it too.
            if (it != "done") try { app.notice(name, TvRules.updateText(it), pageOf(fromKey), "update$fromKey") } catch (_: Exception) {}
        }
        if (adopt) change(fromKey) { it }
        // Round 17: many wrong parent PINs in a row on the device: said once (again only when it keeps going).
        val told = pinTold[fromKey] ?: 0
        if (s.pinfail < KidRules.PIN_WARN) pinTold.remove(fromKey)
        else if (s.pinfail >= told + KidRules.PIN_WARN || told == 0) {
            pinTold[fromKey] = s.pinfail
            val name = app.contacts.get(fromKey)?.shown ?: "Kids' Device"
            val n = "Someone typed a wrong parent PIN ${s.pinfail} times in a row on $name. Each try now waits longer. Set a new PIN if it may be known."
            Ui.say(n, true)
            try { app.notice(name, n, pageOf(fromKey), "pinfail$fromKey") } catch (_: Exception) {}
        }
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
        val tvName = app.contacts.get(tvKey)?.shown ?: "the Kids' Device"
        Ui.say("You're no longer a parent of $tvName.")
        try { app.notify(tvName, "You're no longer a parent of this Kids' Device", tvKey) } catch (_: Exception) {}
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
        // A parent stays on the list (the kids could not answer them otherwise): take them off as a parent first.
        if (_tvs.value[key]?.parents?.contains(person) == true) return
        dropWaiting(key, listOf(person))
        if (_tvs.value[key]?.reach?.contains(person) == true)
            change(key) { t -> t.copy(reach = t.reach - person, others = t.others.filter { it.k != person }, waitKid = t.waitKid - person) }
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

    /** The TV's app said "k10": App storage from this phone (7.16). */
    fun k10(app: Muninn, key: String): Boolean = TvFiles.CAP in (app.contacts.get(key)?.caps ?: emptySet())

    /** Asks the TV for its App storage list. False if its app lacks k10. */
    fun askFiles(app: Muninn, key: String): Boolean {
        if (!k10(app, key)) { app.chat.probeCaps(key); return false }
        send(app, key, listOf(TvFiles.askMessage()))
        return true
    }

    /** Asks the TV to delete files on its App storage list ([all]: every received file, or the caches). */
    fun deleteTvFiles(app: Muninn, key: String, ids: List<String>, all: io.github.ravenor907.muninn.net.AppStorage.Kind? = null): Boolean {
        if (!k10(app, key)) return false
        send(app, key, listOf(if (all != null) TvFiles.deleteAllMessage(all) else TvFiles.deleteMessage(ids)))
        return true
    }

    /** Asks the TV to move received files to its Downloads. */
    fun moveTvFiles(app: Muninn, key: String, ids: List<String>): Boolean {
        if (!k10(app, key)) return false
        send(app, key, listOf(TvFiles.moveMessage(ids)))
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
        // A card only ever carries a Kids' Device: group pickers here leave it out unless this phone looks after it.
        markKid(app, tv.chatKey)
        // k14: the parent to ask when this phone puts that device on one of its own.
        if (tv.chatKey != app.identity.publicKey) noteKidFrom(app, tv.chatKey, fromKey)
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
        val now = System.currentTimeMillis()
        // k14: another family's Kids' Device waits until its own parents put this one on its list.
        val others = add.filter { isKidDevice(it) && !isManaged(it) }
        change(tvKey) { t -> t.copy(reach = (t.reach + add).distinct().take(TvRules.MAX_CONTACTS - 1),
            waitKid = t.waitKid + others.filter { it !in t.waitKid }.associateWith { now }) }
        askOtherParents(app, tvKey, others)
        // A Kids' Device gets no card: it takes people only from its own parents' configs (this phone's, or theirs).
        add.filter { !isKidDevice(it) }.forEach { k -> app.chat.probeCaps(k); cards[k] = tvKey to now; sendCard(app, k, false) }
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
            late -> app.chat.sendPrivate(person, "I added you to our Kids' Device so you can talk with the kids. Add it in Muninn: ${Contacts.link(code)}")
            else -> return false
        }
        cards.remove(person)
        return true
    }

    private fun notifyJoined(app: Muninn, k: String) {
        Ui.say("${app.chat.nameOf(k)} accepted, so they can talk with the kids on the Kids' Device now.")
    }

    /** The TV's app said "k4": it takes apps a parent sends (TvApps). */
    fun k4(app: Muninn, key: String): Boolean = "k4" in (app.contacts.get(key)?.caps ?: emptySet())

    /** Tells the TV which files of [name] are on their way (7.9). False if the TV's app lacks k4. */
    fun announceApp(app: Muninn, key: String, name: String, parts: List<TvApps.Part>): Boolean {
        if (!k4(app, key)) return false
        send(app, key, listOf(TvApps.message(name, parts)))
        return true
    }

    /** A parent's "Update" (older devices): the device looks for a new Muninn now and installs it. False if the TV's app lacks k2. */
    fun updateTv(app: Muninn, key: String): Boolean {
        if (!k2(app, key)) return false
        send(app, key, listOf(TvRules.updateMessage()))
        return true
    }

    /** The TV's app said "u1": Update now from anywhere, with its steps on this phone (7.17). */
    fun u1(app: Muninn, key: String): Boolean = TvUpd.canAsk(app.contacts.get(key)?.caps ?: emptySet())

    /** u1: Update now. False (and a caps probe) while the TV's app lacks u1. */
    fun updateNow(app: Muninn, key: String): Boolean {
        if (!u1(app, key)) { app.chat.probeCaps(key); return false }
        send(app, key, listOf(TvUpd.nowMessage()))
        return true
    }

    /** u1: the TV's "upstate". A new failure, or a TV that needs a tap, is said once here. */
    private fun takeUpstate(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val st = TvUpd.stateIn(o) ?: return false
        var news: String? = null
        synchronized(lock) {
            val t = _tvs.value[fromKey] ?: return false
            if (t.disconnecting) return true
            val was = t.upd1
            if ((st.s == TvUpd.FAILED || st.s == TvUpd.TAP) && (was?.s != st.s || was?.why != st.why)) news = TvUpd.text(st)
            put(t.copy(upd1 = st, upd1At = System.currentTimeMillis(), app = st.app))
        }
        news?.let {
            val name = app.contacts.get(fromKey)?.shown ?: "Kids' Device"
            Ui.say("$name: $it", true)
            // Not only while Muninn is open: a notification, so a parent away from home hears it too.
            try { app.notice(name, it, pageOf(fromKey), "update$fromKey") } catch (_: Exception) {}
        }
        return true
    }

    // ---- k11 (7.18): the chatting level, quiet times, the parent PIN, unlocking from here, and a device that left

    /** The device's app said "k11". */
    fun k11(app: Muninn, key: String): Boolean = KidRules.CAP in (app.contacts.get(key)?.caps ?: emptySet())

    /** The parent picked the chatting level. */
    fun setChat(key: String, level: Int) = change(key) { it.copy(chat = level.coerceIn(KidRules.QUICK_ONLY, KidRules.EVERYTHING)) }

    /** School hours or bedtime: [q] on (its days and times), or off (null) for the name [n]. */
    fun setQuiet(key: String, n: String, q: QuietTime?) = change(key) { t ->
        t.copy(quiet = (t.quiet.filter { it.n != n } + listOfNotNull(q?.copy(n = n))).sortedBy { KidRules.QUIET_NAMES.indexOf(it.n) })
    }

    /** The parent PIN (made with [KidRules.newPin], off the main thread), or none. */
    fun setPin(key: String, p: KidPin?) = change(key) { it.copy(pin = p) }

    /** The time zone the device's quiet times follow (an IANA id this phone knows). */
    fun setTz(key: String, tz: String) { if (KidRules.zoneOf(tz) != null) change(key) { it.copy(tz = tz) } }

    /** Kids' Devices this phone looks after, other than [key]: put on each other's lists in one step. */
    fun addBothWays(app: Muninn, key: String, other: String) {
        invite(app, key, listOf(other))
        if (isManaged(other)) invite(app, other, listOf(key))
    }

    /** An ask from a device to open its parent settings: its id, and when it came (this phone's clock). */
    data class UnlockAsk(val id: String, val at: Long)

    /** The asks waiting for this phone's person, one per device (a newer one from the same device replaces it). */
    val unlockAsks = MutableStateFlow<Map<String, UnlockAsk>>(emptyMap())

    /** The device page that [key]'s notices open. */
    fun pageOf(key: String) = "tvset/page/$key"

    /**
     * The device's ask to open its parent settings: a card on the device's page, a notification that opens that page, and
     * a dialog only when no other dialog is open (it never replaces one). An ask runs out after 10 minutes, as on the device.
     */
    private fun takeUnlockAsk(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val id = KidRules.idIn(o, "unlockask") ?: return false
        val t = _tvs.value[fromKey] ?: return false
        if (t.disconnecting) return true
        val name = app.contacts.get(fromKey)?.shown ?: "Kids' Device"
        // Round 17: an ask from the same device while one is still open only replaces it, quietly (no new sound or
        // dialog); Unlock takes a hold, so a sleepy tap never opens Leave kids' mode.
        val open = unlockAsk(fromKey) != null
        unlockAsks.value = unlockAsks.value + (fromKey to UnlockAsk(id, System.currentTimeMillis()))
        if (open) return true
        if (Ui.ask.value == null) Ui.sure(Ask("Unlock $name?", unlockText(name), "Unlock", hold = true, cancel = "Not now", onCancel = { answerUnlock(app, fromKey, false) }) {
            answerUnlock(app, fromKey, true)
        })
        try { app.notice("Unlock $name?", "Someone at $name asks to open its parent settings. Tap to answer.", pageOf(fromKey), "unlock$fromKey") } catch (_: Exception) {}
        return true
    }

    fun unlockText(name: String) =
        "Someone at $name asks to open its parent settings for 10 minutes. With them open, they can leave kids' mode or change Android's settings for Muninn."

    /** The ask from [key] still waiting (not run out), or null. */
    fun unlockAsk(key: String, now: Long = System.currentTimeMillis()): UnlockAsk? =
        unlockAsks.value[key]?.takeIf { now - it.at in 0L..KidRules.ASK_MS }

    /** This phone's person said yes ([yes]) or not now to [key]'s ask. One that ran out says so instead of pretending. */
    fun answerUnlock(app: Muninn, key: String, yes: Boolean) {
        val a = unlockAsk(key)
        unlockAsks.value = unlockAsks.value - key
        if (!yes) return
        if (a == null) { Ui.say("That ask ran out. Ask again on the device.", true); return }
        send(app, key, listOf(KidRules.unlockMessage(a.id)))
        Ui.say("Sent. Its parent settings open for 10 minutes once it gets this.")
    }

    /** The device left kids' mode (with the PIN, or a parent's yes): this phone stops looking after it. It stays a contact. */
    private fun takeLeft(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        if (!KidRules.isLeft(o)) return false
        // Sent by the device itself: it's an ordinary contact from now on, here and in the group pickers (7.18).
        unmarkKid(app, fromKey)
        synchronized(lock) {
            // Someone on its list (not a parent): nothing more to drop.
            if (_tvs.value[fromKey] == null) return true
            _tvs.value = _tvs.value - fromKey
            save(app)
        }
        flushes.remove(fromKey)?.cancel(false)
        tries.remove(fromKey)
        removed.remove(fromKey)
        cards.keys.filter { cards[it]?.first == fromKey }.forEach { cards.remove(it) }
        TvFiles.forget(fromKey)
        unlockAsks.value = unlockAsks.value - fromKey
        val name = app.contacts.get(fromKey)?.shown ?: "The Kids' Device"
        Ui.say("$name left kids' mode")
        try { app.notice("$name left kids' mode", "Someone with the parent PIN or a parent's yes left kids' mode. It's a normal Muninn now.", fromKey, "left$fromKey") } catch (_: Exception) {}
        return true
    }

    /**
     * Round 17 (7.18): this phone's Kids' Device [fromKey] says another family's Kids' Device on its list left kids' mode.
     * It's an ordinary Muninn now: it comes off the list (the device already stopped talking to it), and this phone's
     * person is told.
     */
    private fun takeKidLeft(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val k = KidRules.kidLeftIn(o) ?: return false
        val t = _tvs.value[fromKey] ?: return false
        if (t.disconnecting) return true
        if (k == app.identity.publicKey || k in t.parents) return true
        unmarkKid(app, k)
        val had = k in t.reach || t.others.any { it.k == k } || k in t.waitKid
        if (had) change(fromKey) { x -> x.copy(reach = x.reach - k, others = x.others.filter { it.k != k }, waitKid = x.waitKid - k) }
        val tvName = app.contacts.get(fromKey)?.shown ?: "the Kids' Device"
        val who = t.names[k] ?: app.chat.nameOf(k)
        val n = "$who left kids' mode, so it's off $tvName's list. Add it again only if you trust whoever has it now."
        Ui.say(n)
        try { app.notice(tvName, n, pageOf(fromKey), "kidleft$fromKey$k") } catch (_: Exception) {}
        return true
    }

    /**
     * Round 17: this phone scanned a pairing code another parent had already used. The device stays theirs; this phone
     * stops setting it up and says whom to ask.
     */
    private fun takeTaken(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        if (!KidRules.isTaken(o)) return false
        synchronized(lock) {
            val t = _tvs.value[fromKey] ?: return true
            // Only while this phone never got it set up: a device it looks after never says this.
            if (t.applied > 0 || t.disconnecting) return true
            _tvs.value = _tvs.value - fromKey
            save(app)
        }
        flushes.remove(fromKey)?.cancel(false)
        tries.remove(fromKey)
        bases.remove(fromKey); signed.remove(fromKey)
        val tvName = app.contacts.get(fromKey)?.shown ?: "This Kids' Device"
        Ui.say("$tvName is already set up by another parent. Ask them to add you as a parent from its page.", true)
        return true
    }

    // ---- k12 (7.18): Daily Hávamál, and this phone's own translation for it

    /** The device's app said "k12". */
    fun k12(app: Muninn, key: String): Boolean = Havamal.CAP in (app.contacts.get(key)?.caps ?: emptySet())

    // ---- k13 (7.18): the device's colors

    /** The device's app said "k13". */
    fun k13(app: Muninn, key: String): Boolean = KidLook.CAP in (app.contacts.get(key)?.caps ?: emptySet())

    /** The parent picked the device's look, Seasonal choice or accent. */
    fun setLook(key: String, look: String) = change(key) { it.copy(look = io.github.ravenor907.muninn.ui.Looks.of(look)) }
    fun setSeason(key: String, season: String) = change(key) { it.copy(season = io.github.ravenor907.muninn.ui.Seasons.choice(season)) }
    fun setAccent(key: String, accent: String) = change(key) { it.copy(accent = io.github.ravenor907.muninn.ui.Accents.choice(accent)) }

    /** Let the kid choose: whether "My colors" shows on the device. */
    fun setLookPick(key: String, on: Boolean) = change(key) { it.copy(lookPick = on) }

    // ---- k14 (7.18): the child's name, accessibility, group members a parent approves, and other families' Kids' Devices

    /** The device's app said "k14". */
    fun k14(app: Muninn, key: String): Boolean = KidK14.CAP in (app.contacts.get(key)?.caps ?: emptySet())

    /** The child's name, for the greeting on the device ("Hi, Ava"). The same on every parent's page once the config goes out. */
    fun setChild(key: String, name: String) {
        val n = TvRules.clean(name, TvRules.MAX_NAME)
        if (n.isNotEmpty()) change(key) { it.copy(child = n) }
    }

    /** The child's name, or the device's name on this phone while none is set (for this phone's own words, never the device's). */
    fun childName(app: Muninn, t: ManagedTv): String = t.child.ifEmpty { app.contacts.get(t.key)?.shown ?: "the Kids' Device" }

    /** The parent picked part of the device's accessibility. */
    fun setAccess(key: String, f: (KidAccess) -> KidAccess) = change(key) { it.copy(access = f(it.access)) }

    // ---- k15 (7.18): the read-aloud voice

    /** The device's app said "k15". */
    fun k15(app: Muninn, key: String): Boolean = KidVoice.CAP in (app.contacts.get(key)?.caps ?: emptySet())

    /** The device's app said "k16" (it reads the read-aloud speed). */
    fun k16(app: Muninn, key: String): Boolean = KidVoice.SPEED_CAP in (app.contacts.get(key)?.caps ?: emptySet())

    /** 1.42: whether the speed can go to this device now (it and every co-parent's app say k16). */
    fun speedReady(app: Muninn, t: ManagedTv): Boolean = k16(app, t.key) && KidVoice.allReadSpeed(coParentCaps(app, t))

    /** Round 19: each other parent's caps (null: not a contact here), for fields every parent's app must read. */
    private fun coParentCaps(app: Muninn, t: ManagedTv): List<Set<String>?> {
        val me = app.identity.publicKey
        return t.parents.filter { it != me && it != t.key }.distinct().map { app.contacts.get(it)?.caps }
    }

    /**
     * Round 19: why the voice can't be picked yet because of a co-parent (what the device page says), or null when every
     * co-parent's app reads it.
     */
    fun voiceBlockedBy(app: Muninn, t: ManagedTv): String? {
        val me = app.identity.publicKey
        val k = t.parents.filter { it != me && it != t.key }.distinct().firstOrNull { KidVoice.CAP !in (app.contacts.get(it)?.caps ?: emptySet()) }
            ?: return null
        val n = nameOnDevice(app, t, k)
        return if (app.contacts.get(k) == null) "Add $n as a contact first, so this phone can tell their Muninn reads it."
        else "Ask $n to update Muninn first."
    }

    /** Asks each co-parent whose app doesn't say k15 (or k16) for its caps again (the device page, once when it opens). */
    fun probeVoice(app: Muninn, t: ManagedTv) {
        val me = app.identity.publicKey
        t.parents.filter { it != me && it != t.key && app.contacts.get(it) != null
                && (app.contacts.get(it)?.caps ?: emptySet()).let { c -> KidVoice.CAP !in c || KidVoice.SPEED_CAP !in c } }
            .forEach { try { app.chat.probeCaps(it) } catch (_: Exception) {} }
    }

    /** The parent picked the device's read-aloud voice ("f" or "m"). */
    fun setVoice(key: String, voice: String) {
        if (voice in io.github.ravenor907.muninn.net.ReadAloudText.VOICES) change(key) { it.copy(voice = voice) }
    }

    /** The parent picked the device's read-aloud speed (a whole percent, 50 to 150). */
    fun setSpeed(key: String, pct: Int) {
        if (pct in KidVoice.SPEED_MIN..KidVoice.SPEED_MAX) change(key) { it.copy(speed = pct) }
    }

    /**
     * The device holds a group member who isn't on its list: a card on its page, a notification, and a dialog when no
     * other dialog is open. Once approved it's let in; once declined it isn't asked about again.
     */
    private fun takeHold(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val a = KidK14.holdIn(o) ?: return false
        val pair = a.g to a.k
        var title = ""
        synchronized(lock) {
            val t = _tvs.value[fromKey] ?: return false
            if (t.disconnecting || pair in t.gno) return true
            // Approved before (its config may not have reached the device yet): the config goes again.
            if (pair in t.gok) { change(fromKey) { it }; return true }
            if (t.holdAsks.any { it.g == a.g && it.k == a.k }) return true
            put(t.copy(holdAsks = t.holdAsks + a))
            title = KidK14.approveText(a.n, a.gn, childName(app, t))
        }
        val text = "${a.n} is in the group ${a.gn} but isn't on its list. Approve shows their messages in that group only. Decline keeps them hidden."
        if (Ui.ask.value == null) Ui.sure(Ask(title, text, "Approve", cancel = "Decline", onCancel = { answerHold(fromKey, a, false) }) {
            answerHold(fromKey, a, true)
        })
        try { app.notice(title, text, pageOf(fromKey), "ghold$fromKey${a.g}${a.k}".take(120)) } catch (_: Exception) {}
        return true
    }

    /** Approve ([yes]): [a] goes on the device's list for that group only. Decline: kept hidden, not asked again. */
    fun answerHold(key: String, a: HoldAsk, yes: Boolean) {
        val pair = a.g to a.k
        change(key) { t ->
            t.copy(holdAsks = t.holdAsks.filter { it.g != a.g || it.k != a.k },
                gok = if (yes) (t.gok + pair).distinct().take(KidK14.MAX_GOK) else t.gok - pair,
                gno = if (yes) t.gno - pair else (t.gno + pair).distinct())
        }
        Ui.say(if (yes) "Approved. Their messages show there once the device is online." else "Kept hidden")
    }

    /** Asks the parents of other families' Kids' Devices ([others]) to put [tvKey] on their device's list. */
    private fun askOtherParents(app: Muninn, tvKey: String, others: List<String>) {
        if (others.isEmpty()) return
        val t = _tvs.value[tvKey] ?: return
        val me = app.contacts.get(tvKey) ?: return
        val code = Contacts.code(me.name, tvKey, me.mailbox ?: "")
        for (k in others) {
            val parent = kidFrom[k] ?: continue
            send(app, parent, listOf(KidK14.kidAddMessage(KidAdd(k, tvKey, childName(app, t), code))))
        }
    }

    /**
     * Another family's parent asks to put their Kids' Device ([KidAdd.k]) on this phone's Kids' Device ([KidAdd.tv]). One
     * already on its list: the answer says so at once. Otherwise a card on the device's page and a notification.
     */
    private fun takeKidAdd(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val a = KidK14.kidAddIn(o) ?: return false
        if (app.contacts.get(fromKey) == null) return false
        loadKids(app)
        // Never this phone, its own devices or the sender, and never someone already here who isn't known as a Kids' Device.
        // A device another parent handed over (by card or code) is still asked about: the ask names who shared it first.
        if (!KidK14.kidAddOk(a.k, fromKey, app.identity.publicKey, a.tv, isManaged(a.k), app.contacts.get(a.k) != null, isKidDevice(a.k), kidFrom[a.k])) return true
        var title = ""
        var text = ""
        synchronized(lock) {
            val t = _tvs.value[a.tv] ?: return false
            if (t.disconnecting) return true
            if (a.k in t.reach) {
                noteKidFrom(app, a.k, fromKey)
                // Both lists have each other now.
                if (a.k in t.waitKid) change(a.tv) { it.copy(waitKid = it.waitKid - a.k) }
                send(app, fromKey, listOf(KidK14.kidOkMessage(a.tv, a.k)))
                return true
            }
            if (t.kidAsks.any { it.add.k == a.k }) return true
            put(t.copy(kidAsks = t.kidAsks + KidAddAsk(a, fromKey)))
            val child = childName(app, t)
            val who = app.chat.nameOf(fromKey)
            title = "Let ${a.n} talk with $child?"
            // Nothing can prove a device is a child's: said as it is, so the parent decides on who's asking.
            val firstBy = KidK14.sharedFirstBy(kidFrom[a.k], fromKey)?.let { app.chat.nameOf(it) }
            text = KidK14.kidAddText(who, a.n, child, app.contacts.get(a.k) == null, firstBy)
        }
        if (Ui.ask.value == null) Ui.sure(Ask(title, text, "Add", cancel = "Not now", onCancel = { answerKidAdd(app, a.tv, a.k, false) }) {
            answerKidAdd(app, a.tv, a.k, true)
        })
        try { app.notice(title, text, pageOf(a.tv), "kidadd${a.tv}${a.k}".take(120)) } catch (_: Exception) {}
        return true
    }

    /** Add ([yes]): the other family's device goes on [key]'s list, and its parent hears so. Not now: the ask goes. */
    fun answerKidAdd(app: Muninn, key: String, k: String, yes: Boolean) {
        val ask = _tvs.value[key]?.kidAsks?.firstOrNull { it.add.k == k } ?: return
        synchronized(lock) { _tvs.value[key]?.let { t -> put(t.copy(kidAsks = t.kidAsks.filter { it.add.k != k })) } }
        if (!yes) return
        val c = Contacts.parse(ask.add.code)
        if (c == null || c.chatKey != k) { Ui.say("That device's code can't be read.", true); return }
        if (app.contacts.get(k) == null) {
            if (app.chat.keyChangeFor(c) != null) { Ui.say("That device's code can't be added here.", true); return }
            app.chat.addContact(c.copy(invite = null))
        }
        markKid(app, k)
        // Its parent (who asked) is the one asked to add this phone's device back.
        noteKidFrom(app, k, ask.from)
        invite(app, key, listOf(k))
        send(app, ask.from, listOf(KidK14.kidOkMessage(key, k)))
        Ui.say("Added. The two devices can talk once it's online.")
    }

    /** Another family's parent put this phone's Kids' Device (the "k" in [o]) on theirs (its "tv"): it no longer waits. */
    private fun takeKidOk(app: Muninn, fromKey: String, o: JSONObject): Boolean {
        val (tv, k) = KidK14.kidOkIn(o) ?: return false
        if (app.contacts.get(fromKey) == null) return false
        val t = _tvs.value[k] ?: return false
        if (tv in t.waitKid) change(k) { it.copy(waitKid = it.waitKid - tv) }
        return true
    }

    /** The parent switched Daily Hávamál on or off. */
    fun setHav(key: String, on: Boolean) = change(key) { it.copy(hav = on) }

    /** The parent picked the wording: Bellows, or their own translation (only once they imported one). */
    fun setHavWording(key: String, w: String) {
        val mine = HavParent.mine.value
        if (w == Havamal.MINE) { if (mine != null) change(key) { it.copy(havw = Havamal.MINE, havx = mine.text.h) } }
        else change(key) { it.copy(havw = Havamal.BELLOWS, havx = "") }
    }

    /**
     * The wording and translation hash a config carries for [t]: this phone's own translation when the parent picked it;
     * another parent's (still named in the device's config) when this phone has none; else Bellows.
     */
    fun havWording(t: ManagedTv): Pair<String, String?> {
        if (t.havw != Havamal.MINE) return Havamal.BELLOWS to null
        val mine = HavParent.mine.value?.text?.h
        if (mine != null && (t.havx.isEmpty() || t.havx == mine)) return Havamal.MINE to mine
        if (t.havx.isNotEmpty()) return Havamal.MINE to t.havx
        return Havamal.BELLOWS to null
    }

    /**
     * This phone's translation to [key], in parts, only when the device's config names it and the device doesn't hold it
     * yet. [force] false: at most once a minute (an "applied" that still says otherwise). Only to a device this phone looks after.
     */
    private fun sendHavText(app: Muninn, key: String, force: Boolean) {
        val t = _tvs.value[key] ?: return
        if (t.disconnecting || !k12(app, key)) return
        val mine = HavParent.mine.value?.text ?: return
        if (havWording(t).second != mine.h || t.havHeld == mine.h) return
        val now = System.currentTimeMillis()
        if (!force && now - (havSent[key] ?: 0L) < 60_000) return
        havSent[key] = now
        send(app, key, Havamal.partMessages(mine.json))
    }

    /** This phone's translation was imported or removed: devices using it get the new one, or go back to Bellows. */
    fun havTextChanged() {
        val mine = HavParent.mine.value?.text?.h
        val using = synchronized(lock) { _tvs.value.values.filter { it.havw == Havamal.MINE && !it.disconnecting }.map { it.key } }
        for (k in using) {
            if (mine == null) change(k) { it.copy(havw = Havamal.BELLOWS, havx = "") }
            else change(k) { it.copy(havx = mine) }
        }
    }

    /** Shares the TV's contact code, so someone the parent added can add the TV on their side. */
    fun shareText(app: Muninn, key: String): String? {
        val c = app.contacts.get(key) ?: return null
        // k14: marked as a Kids' Device, with this parent, so another family's parent can put it on their own Kids' Device's
        // list and this phone is asked to add theirs back.
        val code = try { KidK14.kidCode(Contacts.code(c.name, key, c.mailbox ?: ""), app.identity.publicKey) } catch (_: Exception) { Contacts.code(c.name, key, c.mailbox ?: "") }
        return "Add our Kids' Device in Muninn so it can reach you: ${Contacts.link(code)}"
    }

    private fun tick() {
        val app = app ?: return
        val now = System.currentTimeMillis()
        // A device not heard from for a day and a half: its parents are told once (it may be off, or Muninn on it cleared).
        for (t in _tvs.value.values) if (!t.disconnecting && KidRules.silentNotice(t.seenAt, t.silentTold, now)) {
            synchronized(lock) { _tvs.value[t.key]?.let { put(it.copy(silentTold = now)) } }
            val name = app.contacts.get(t.key)?.shown ?: "Your Kids' Device"
            val since = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(t.seenAt))
            try {
                app.notice("Not heard from $name", "Nothing from it since $since. It may be off or offline, or Muninn on it may have been cleared, reset or removed.",
                    pageOf(t.key), "silent${t.key}")
            } catch (_: Exception) {}
        }
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
        TvFiles.forget(key)
        // If it's added again later (as a normal device, after "make it a normal device"), it isn't marked as a Kids' Device.
        unmarkKid(app, key)
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
            .put("quick", JSONArray().apply { t.quick.forEach { q -> put(JSONObject().put("id", q.id).put("text", q.text).apply { if (q.e.isNotEmpty()) put("e", q.e) }) } })
            .put("reach", JSONArray(t.reach)).put("dictation", t.dictation).put("parents", JSONArray(t.parents))
            .put("myName", t.myName).put("pending", JSONArray(t.pending)).put("pendingV", t.pendingV).put("since", t.since)
            .put("disconnecting", t.disconnecting).put("calls", t.calls).put("mic", t.mic).put("extrasSent", t.extrasSent).put("app", t.app)
            .put("micAt", t.micAt).put("micOnTv", t.micOnTv).put("used", t.used).put("usedAt", t.usedAt)
            .put("waiting", JSONObject().apply { t.waiting.forEach { (k, v) -> put(k, v) } })
            .put("others", JSONArray().apply { t.others.forEach { put(JSONObject().put("k", it.k).put("n", it.n).put("p", it.p)) } })
            .put("sharedWith", JSONArray(t.sharedWith)).put("nr", t.nr).put("upd", t.upd).put("keep", t.keep).put("update", t.update)
            .apply { t.off?.let { put("off", JSONArray(it)) } }
            .apply { t.upd1?.let { put("upd1", JSONObject(TvUpd.stateMessage(it))).put("upd1At", t.upd1At) } }
            .put("chat", t.chat).put("quiet", KidRules.quietJson(t.quiet)).put("k11Sent", t.k11Sent).put("dev", t.dev)
            .put("hav", t.hav).put("havw", t.havw).put("havx", t.havx).put("havHeld", t.havHeld)
            .put("names", JSONObject().apply { t.names.forEach { (k, n) -> put(k, n) } }).put("tz", t.tz)
            .put("seenAt", t.seenAt).put("silentTold", t.silentTold)
            .put("look", t.look).put("season", t.season).put("accent", t.accent).put("lookPick", t.lookPick)
            .put("child", t.child).put("gok", KidK14.gokJson(t.gok)).put("gno", KidK14.gokJson(t.gno))
            .put("access", JSONObject().apply { KidK14.putInto(this, TvExtras(access = t.access)) })
            .put("holdAsks", JSONArray().apply { t.holdAsks.forEach { put(JSONObject(KidK14.holdMessage(it))) } })
            .put("waitKid", JSONObject().apply { t.waitKid.forEach { (k, v) -> put(k, v) } })
            .put("kidAsks", JSONArray().apply { t.kidAsks.forEach { put(JSONObject().put("a", JSONObject(KidK14.kidAddMessage(it.add))).put("from", it.from)) } })
            .put("goneC", JSONArray(t.goneC)).put("goneP", JSONArray(t.goneP)).put("voice", t.voice)
            .apply { t.speed?.let { put("speed", it) } }
            .apply { t.pin?.let { put("pin", KidRules.pinJson(it)) } })
    }

    /** [autoUpdate]: this phone's own update switch, for a TV saved without its own Auto-update. */
    private fun fromJson(o: JSONObject, autoUpdate: Boolean): Map<String, ManagedTv> = o.keys().asSequence().mapNotNull { k ->
        try {
            val x = o.getJSONObject(k)
            val q = x.optJSONArray("quick") ?: JSONArray()
            fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            k to ManagedTv(k, x.optLong("v"), x.optLong("applied"),
                (0 until q.length()).map { q.getJSONObject(it).let { e -> Quick(e.getString("id"), e.getString("text"), e.optString("e").takeIf { x -> KidK14.emojiOk(x) } ?: "") } },
                strs(x.optJSONArray("reach")), x.optBoolean("dictation"), strs(x.optJSONArray("parents")), x.optString("myName"),
                strs(x.optJSONArray("pending")), x.optLong("pendingV"), x.optLong("since"), x.optBoolean("disconnecting"),
                x.optBoolean("calls", true), x.optInt("mic", TvRules.DEFAULT_MIC), x.optBoolean("extrasSent"), x.optString("app"),
                x.optLong("micAt"), x.optBoolean("micOnTv"), x.optLong("used", -1), x.optLong("usedAt"),
                x.optJSONObject("waiting")?.let { w -> w.keys().asSequence().associateWith { w.optLong(it) } } ?: emptyMap(),
                x.optJSONArray("others")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { e -> TvPerson(e.getString("k"), e.getString("n"), e.getString("p")) } } } ?: emptyList(),
                strs(x.optJSONArray("sharedWith")), x.optInt("nr", TvRules.NR_BOTH).coerceIn(TvRules.NR_NONE, TvRules.NR_BOTH),
                x.optBoolean("upd", autoUpdate), x.optInt("keep", TvRules.MAX_KEEP).coerceIn(TvRules.MIN_KEEP, TvRules.MAX_KEEP),
                x.optJSONArray("off")?.let { strs(it) },
                x.optString("update").takeIf { it in TvRules.UPDATE_STATES } ?: "",
                upd1 = x.optJSONObject("upd1")?.let { TvUpd.stateIn(it) }, upd1At = x.optLong("upd1At"),
                chat = x.optInt("chat", KidRules.EVERYTHING).coerceIn(KidRules.QUICK_ONLY, KidRules.EVERYTHING),
                quiet = x.optJSONArray("quiet")?.let { a -> try { KidRules.quietIn(a) } catch (_: Exception) { null } } ?: emptyList(),
                pin = x.optJSONObject("pin")?.let { p -> try { KidRules.pinIn(p) } catch (_: Exception) { null } },
                k11Sent = x.optBoolean("k11Sent"), dev = x.optString("dev").takeIf { it in KidRules.DEVICES } ?: "",
                hav = x.optBoolean("hav"), havw = if (x.optString("havw") == Havamal.MINE) Havamal.MINE else Havamal.BELLOWS,
                havx = x.optString("havx"), havHeld = x.optString("havHeld"),
                names = x.optJSONObject("names")?.let { n -> n.keys().asSequence().associateWith { n.optString(it) }.filterValues { it.isNotBlank() } } ?: emptyMap(),
                tz = x.optString("tz"), seenAt = x.optLong("seenAt"), silentTold = x.optLong("silentTold"),
                look = io.github.ravenor907.muninn.ui.Looks.of(x.optString("look")),
                season = io.github.ravenor907.muninn.ui.Seasons.choice(x.optString("season")),
                accent = io.github.ravenor907.muninn.ui.Accents.choice(x.optString("accent")),
                lookPick = x.optBoolean("lookPick", true),
                child = x.optString("child").takeIf { KidK14.nameOk(it) } ?: "",
                access = x.optJSONObject("access")?.let { a -> try { KidK14.check(a); KidK14.accessIn(a) } catch (_: Exception) { null } } ?: KidAccess(),
                gok = x.optJSONArray("gok")?.let { a -> try { KidK14.gokIn(a) } catch (_: Exception) { null } } ?: emptyList(),
                gno = x.optJSONArray("gno")?.let { a -> try { KidK14.gokIn(a) } catch (_: Exception) { null } } ?: emptyList(),
                holdAsks = x.optJSONArray("holdAsks")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { KidK14.holdIn(it) } } } ?: emptyList(),
                waitKid = x.optJSONObject("waitKid")?.let { w -> w.keys().asSequence().associateWith { w.optLong(it) } } ?: emptyMap(),
                kidAsks = x.optJSONArray("kidAsks")?.let { a -> (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    val add = e.optJSONObject("a")?.let { KidK14.kidAddIn(it) } ?: return@mapNotNull null
                    e.optString("from").takeIf { it.isNotEmpty() }?.let { KidAddAsk(add, it) }
                } } ?: emptyList(),
                goneC = strs(x.optJSONArray("goneC")), goneP = strs(x.optJSONArray("goneP")),
                voice = x.optString("voice").takeIf { it in io.github.ravenor907.muninn.net.ReadAloudText.VOICES } ?: "",
                speed = x.optInt("speed", 0).takeIf { it in KidVoice.SPEED_MIN..KidVoice.SPEED_MAX })
        } catch (e: Exception) { null }
    }.toMap()
}
