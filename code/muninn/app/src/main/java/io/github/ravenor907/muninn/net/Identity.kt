package io.github.ravenor907.muninn.net

import android.content.Context
import android.os.Build
import java.io.File
import java.security.KeyPair
import java.util.UUID
import kotlin.random.Random

/**
 * Who this phone is to other devices, and the person's choices. Choices are kept in SharedPreferences; keys, the
 * blocked list and the chat key are kept in the encrypted vault (secrets.json).
 */
class Identity(context: Context, private val vault: Vault) {
    private val secrets = java.io.File(context.filesDir, "secrets.json")
    private val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)

    init {
        // Link previews start off. A phone already set up (it has a fingerprint) with no choice stored keeps them on.
        if (prefs.contains("fingerprint") && !prefs.contains("linkPreviews")) prefs.edit().putBoolean("linkPreviews", true).apply()
    }

    /** Random and stable: LocalSend uses it to tell devices apart and to skip itself. */
    val fingerprint: String = prefs.getString("fingerprint", null) ?: UUID.randomUUID().toString().replace("-", "").also {
        prefs.edit().putString("fingerprint", it).apply()
    }

    /** The name LocalSend apps and Huginn show for this phone. */
    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: "Muninn on ${Build.MODEL}"
        set(v) = prefs.edit().putString("deviceName", v.trim().take(40)).apply()

    /** Nearby chat name: random until the person picks one, so nobody is named by default. */
    var username: String
        get() = prefs.getString("username", null) ?: "Raven ${Random.nextInt(1000, 10000)}".also { username = it }
        set(v) = prefs.edit().putString("username", v.trim().take(24)).apply()

    var nearbyChat: Boolean
        get() = prefs.getBoolean("nearbyChat", true)
        set(v) = prefs.edit().putBoolean("nearbyChat", v).apply()

    /**
     * Settings › Lock and privacy › Nearby. Off: no Nearby room or Nearby list, nobody new can message over Bluetooth
     * or Wi-Fi, and nothing about this phone goes out on its own ([nearbyOff]). Contacts still reach it.
     */
    var visibleNearby: Boolean
        get() = prefs.getBoolean("visibleNearby", true)
        set(v) = prefs.edit().putBoolean("visibleNearby", v).apply()

    /** The Nearby room is on: its own switch, and only while visible to people nearby. */
    val nearbyRoom: Boolean get() = nearbyChat && visibleNearby

    /** A Kids' TV keeps its own nearby behavior whatever the Nearby switch says (Muninn sets this when it starts). */
    @Volatile var kidsTv: () -> Boolean = { false }

    /**
     * Settings › Lock and privacy › Nearby is off: nothing is announced on the Wi-Fi or Bluetooth, nothing is scanned
     * for, nobody nearby is listed and only contacts are answered. Contacts still reach this phone on the home Wi-Fi
     * where they were last seen (shared/docs/protocol.md 21.3). Never on a Kids' TV.
     */
    val nearbyOff: Boolean get() = !visibleNearby && !kidsTv()

    /** Settings › Notifications › Reduce notifications: one per chat until it's opened, instead of one per message. */
    var reduceNotifications: Boolean
        get() = prefs.getBoolean("reduceNotifications", false)
        set(v) = prefs.edit().putBoolean("reduceNotifications", v).apply()

    /** Checks GitHub for a new Muninn when it opens. Off: only "Check now" does. */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("autoUpdate", true)
        set(v) = prefs.edit().putBoolean("autoUpdate", v).apply()
    /** Labs mode: the Labs section in Settings (tap the raven there 10 times). */
    var labs: Boolean
        get() = prefs.getBoolean("labs", false)
        set(v) = prefs.edit().putBoolean("labs", v).apply()
    /** The update channel: true for Pre-release (the "beta" pre-release too), false for Stable. */
    var preRelease: Boolean
        get() = prefs.getBoolean("preRelease", false)
        set(v) = prefs.edit().putBoolean("preRelease", v).apply()
    /**
     * When the last Labs report went from here (ms, 0: never). For 30 days after it a reply from the Labs contact
     * comes as a request, like any first message ([LabsWire.replyOpen]).
     */
    var labsReportedAt: Long
        get() = prefs.getLong("labsReportedAt", 0L)
        set(v) = prefs.edit().putLong("labsReportedAt", v).remove("labsReported").apply()
    var keepNearbyHistory: Boolean
        get() = prefs.getBoolean("keepNearby", false)
        set(v) = prefs.edit().putBoolean("keepNearby", v).apply()

    /** What the vault keeps in secrets.json (empty when there's nothing yet). */
    private fun sealed(): org.json.JSONObject = vault.read(secrets)?.let {
        try { org.json.JSONObject(it) } catch (e: Exception) { vault.loadFailed(secrets, e); null }
    } ?: org.json.JSONObject()

    /** Secret keys, kept only in the encrypted vault (one found in the plain settings is moved there). */
    @Synchronized
    private fun secret(name: String, make: () -> String): String {
        val o = sealed()
        o.optString(name).ifEmpty { null }?.let { return it }
        // The keys file is there but didn't load (or Muninn is locked): never a new key in place of the saved one.
        if (!vault.core.loadedOk(secrets)) throw IllegalStateException("secrets.json didn't load")
        val v = prefs.getString(name, null) ?: make()
        o.put(name, v)
        vault.write(secrets, o.toString())
        prefs.edit().remove(name).apply()
        return v
    }

    /** This device's chat key pair, made once. Both halves are in the vault (a public half in the plain settings moves there). */
    val keys: KeyPair by lazy {
        var pub: String? = null
        val priv = secret("chatPriv") {
            ChatCrypto.newKeyPair().let { kp -> pub = ChatCrypto.b64(kp.public.encoded); ChatCrypto.b64(kp.private.encoded) }
        }
        val p = secret("chatPub") { pub ?: prefs.getString("chatPub", null) ?: throw IllegalStateException("no chat key") }
        KeyPair(ChatCrypto.publicFrom(p), ChatCrypto.privateFrom(priv))
    }

    /** The Nostr secret (internet inbox). */
    fun nostrSecret(): String = secret("nostrSecret") { Schnorr.hex(Schnorr.newSecret()) }

    /**
     * The call relay's id for this phone: random, made once. Not the chat key, so nobody who knows that key
     * can use up this phone's relay logins, and the relay can't tell who is calling.
     */
    fun relayId(): ByteArray = secret("relayId") {
        Schnorr.hex(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
    }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Stay connected in the background so messages arrive right away (an ongoing, silent notification). */
    var stayConnected: Boolean
        get() = prefs.getBoolean("stayConnected", true)
        set(v) = prefs.edit().putBoolean("stayConnected", v).apply()

    // ---- accessibility (Settings › Accessibility)
    var uiSize: Float
        get() = prefs.getFloat("uiSize", 1f)
        set(v) = prefs.edit().putFloat("uiSize", v).apply()
    var textSize: Float
        get() = prefs.getFloat("textSize", 1f)
        set(v) = prefs.edit().putFloat("textSize", v).apply()
    var highContrast: Boolean
        get() = prefs.getBoolean("highContrast", false)
        set(v) = prefs.edit().putBoolean("highContrast", v).apply()
    /** Settings › Look › Color vision ([io.github.ravenor907.muninn.ui.ColorVision]); an older Color-blind friendly switch opens as green-weak. */
    var colorVision: String
        get() = io.github.ravenor907.muninn.ui.ColorVision.of(prefs.getString("colorVision", null), prefs.getBoolean("colorBlind", false))
        set(v) = prefs.edit().putString("colorVision", io.github.ravenor907.muninn.ui.ColorVision.of(v)).apply()
    var reduceMotion: Boolean
        get() = prefs.getBoolean("reduceMotion", false)
        set(v) = prefs.edit().putBoolean("reduceMotion", v).apply()

    /** The look: "dark", "black" (true black) or "light". Any other saved value opens as "dark" (see [seasonal]). */
    var look: String
        get() { migrateSeasonal(); return prefs.getString("look", null) ?: "dark" }
        set(v) = prefs.edit().putString("look", v).apply()
    /** The accent (Settings › Look): "gold" and the other names in [io.github.ravenor907.muninn.ui.Accents], or any "#RRGGBB". */
    var accent: String
        get() = io.github.ravenor907.muninn.ui.Accents.choice(prefs.getString("accent", null))
        set(v) = prefs.edit().putString("accent", io.github.ravenor907.muninn.ui.Accents.choice(v)).apply()
    /** First open: the "What should people call you?" step is done (or was skipped). */
    var nameAsked: Boolean
        get() = prefs.getBoolean("nameAsked", false)
        set(v) = prefs.edit().putBoolean("nameAsked", v).apply()
    /** Seasonal, over any look: "off", "auto" (by the date) or a season's key, like "snow". */
    var season: String
        get() { migrateSeasonal(); return prefs.getString("seasonal", null) ?: "off" }
        set(v) = prefs.edit().putString("seasonal", v).apply()
    /**
     * Seasonal used to be a look of its own (saved as "seasonal", or a season's key, with the season under "season"):
     * once, that opens as Dark with Seasonal on, the same season and the rest of its settings.
     */
    private fun migrateSeasonal() {
        if (prefs.contains("seasonal")) return
        val (look, seasonal) = io.github.ravenor907.muninn.ui.Looks.migrate(prefs.getString("look", null), prefs.getString("season", null))
        val edit = prefs.edit().putString("seasonal", seasonal)
        if (seasonal != "off") edit.putString("look", look)
        edit.apply()
    }
    /** Seasonal's background: "" (the season's own) or a "#RRGGBB" from the Look page's swatches. */
    var seasonGround: String
        get() = prefs.getString("seasonGround", null) ?: ""
        set(v) = prefs.edit().putString("seasonGround", v).apply()
    /** Seasonal's animation: "off", "light" (about half as much falls) or "full". */
    var seasonAnim: String
        get() = prefs.getString("seasonAnim", null) ?: "full"
        set(v) = prefs.edit().putString("seasonAnim", v).apply()

    /** Show the line and lit dots while a pattern is drawn. Off: nothing shows, so nobody watching sees it. */
    var patternTrail: Boolean
        get() = prefs.getBoolean("patternTrail", true)
        set(v) = prefs.edit().putBoolean("patternTrail", v).apply()
    /** Ask the keyboard not to learn what's typed in Muninn (most keyboards then turn voice typing off too). */
    var privateKeyboard: Boolean
        get() = prefs.getBoolean("privateKeyboard", false)
        set(v) = prefs.edit().putBoolean("privateKeyboard", v).apply()

    /** Where the lock pattern sits on the screen: "top", "middle" or "lower" (easiest one-handed). */
    var patternPos: String
        get() = prefs.getString("patternPos", null)?.takeIf { it in setOf("top", "middle", "lower") } ?: "lower"
        set(v) = prefs.edit().putString("patternPos", v).apply()

    // ---- privacy
    /** Let people see when their messages were read. Delivered always shows. */
    var readReceipts: Boolean
        get() = prefs.getBoolean("readReceipts", true)
        set(v) = prefs.edit().putBoolean("readReceipts", v).apply()
    /** Delete messages older than this (milliseconds); 0 keeps them. */
    var deleteAfter: Long
        get() = prefs.getLong("deleteAfter", 0L)
        set(v) = prefs.edit().putLong("deleteAfter", v).apply()
    /** Active status (cap m1): a green dot for contacts while their app is open. Off sends nothing and shows nobody's. */
    var activeStatus: Boolean
        get() = prefs.getBoolean("activeStatus", true)
        set(v) = prefs.edit().putBoolean("activeStatus", v).apply()
    /** Link previews (cap m2): this phone looks up a link I send and adds its title and picture. Never for links I get. */
    var linkPreviews: Boolean
        get() = prefs.getBoolean("linkPreviews", false)
        set(v) = prefs.edit().putBoolean("linkPreviews", v).apply()
    /** Notifications say only "New message" unless this is on. */
    var showPreview: Boolean
        get() = prefs.getBoolean("showPreview", false)
        set(v) = prefs.edit().putBoolean("showPreview", v).apply()
    /** Blocks screenshots and hides Muninn's screen in recent apps. */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secureScreen", false)
        set(v) = prefs.edit().putBoolean("secureScreen", v).apply()
    /** With a password: ask again after Muninn has been in the background this long (ms). Never while in use. */
    var lockAfter: Long
        get() = prefs.getLong("lockAfter", io.github.ravenor907.muninn.ui.AppLock.DEFAULT_AFTER)
        set(v) = prefs.edit().putLong("lockAfter", v).apply()
    val publicKey: String get() = ChatCrypto.b64(keys.public.encoded)

    /** The blocked list, in memory once read (it's asked about on every message). */
    @Volatile private var mutedNow: Set<String>? = null

    /**
     * Blocked chat keys, kept in the vault. A list from the plain settings (older versions) moves there once.
     * While the vault is locked, the plain list (if any) is all there is.
     */
    fun muted(): Set<String> {
        mutedNow?.let { return it }
        synchronized(this) {
            mutedNow?.let { return it }
            val plain = prefs.getStringSet("muted", null)?.toSet()
            if (vault.locked.value) return plain.orEmpty()
            val o = sealed()
            val a = o.optJSONArray("muted")
            val s = if (a != null) (0 until a.length()).map { a.getString(it) }.toSet() + plain.orEmpty() else plain.orEmpty()
            if (plain != null) try {
                vault.write(secrets, o.put("muted", org.json.JSONArray(s.toList())).toString())
                prefs.edit().remove("muted").apply()
            } catch (_: Exception) { return s }
            mutedNow = s
            return s
        }
    }

    @Synchronized
    fun setMuted(key: String, on: Boolean) {
        val s = muted().toMutableSet(); if (on) s.add(key) else s.remove(key)
        try { vault.write(secrets, sealed().put("muted", org.json.JSONArray(s.toList())).toString()); mutedNow = s }
        catch (_: Exception) {}
    }

    companion object {
        /** What goes out in place of a name while hidden from people nearby. */
        const val HIDDEN_NAME = "Muninn"
    }
}
