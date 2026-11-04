package io.github.ravenor907.muninn.tv

import android.content.Context
import io.github.ravenor907.muninn.Muninn
import org.json.JSONObject

/**
 * What this device had before it became a Kids' Device: Settings › Look and its sizes, how long messages stay, its name,
 * whether it stays connected and how often it checks, and whether it shows to people nearby. Kept so Leave kids' mode (and a
 * parent's Disconnect) put all of it back. [check] is a [io.github.ravenor907.muninn.net.PowerLogic.Check] name.
 */
data class OwnerSettings(
    val text: Float = 1f, val size: Float = 1f, val hc: Boolean = false, val cv: String = "", val still: Boolean = false,
    val look: String = "", val season: String = "", val accent: String = "", val deleteAfter: Long = 0L,
    val name: String = "", val stay: Boolean = true, val check: String = "", val nearby: Boolean = true,
)

/**
 * Everything kids' mode changes, put back: on a parent's Disconnect (the device stays a Kids' Device and shows the pairing
 * screen) and on Leave kids' mode (it then runs as an ordinary Muninn, and first setup can make it a Kids' Device again).
 * The JSON helpers are free of Android, so the unit tests run them.
 */
object KidReset {
    private const val PREFS = "muninn-tv"
    private const val OWNER = "owner"
    private const val LOOK_PREFS = "kidlook"
    /** What kid mode saved in kidlook: the parent's colors and accessibility it put on last. */
    val LOOK_KEYS = listOf("taken", "accessTaken")
    /** First setup's marks ("muninn" prefs): cleared on Leave, so first setup runs again with "Set up as a Kids' Device". */
    val SETUP_KEYS = listOf("setupStarted", "setupDone", "keepNotice", "lockSkipped", "nameAsked")

    /** [o] as saved. */
    fun ownerJson(o: OwnerSettings): String = JSONObject().put("text", o.text.toDouble()).put("size", o.size.toDouble()).put("hc", o.hc)
        .put("cv", o.cv).put("still", o.still).put("look", o.look).put("season", o.season).put("accent", o.accent)
        .put("deleteAfter", o.deleteAfter).put("name", o.name).put("stay", o.stay).put("check", o.check).put("nearby", o.nearby).toString()

    /** What [s] saved, or null when nothing was saved (a device that became a Kids' Device before this was kept) or it's damaged. */
    fun ownerIn(s: String?): OwnerSettings? {
        if (s.isNullOrEmpty()) return null
        return try {
            val o = JSONObject(s)
            val d = OwnerSettings()
            fun size(k: String) = o.optDouble(k, 1.0).toFloat().takeIf { it in 0.5f..3f } ?: 1f
            OwnerSettings(size("text"), size("size"), o.optBoolean("hc", d.hc), o.optString("cv", d.cv), o.optBoolean("still", d.still),
                o.optString("look", d.look), o.optString("season", d.season), o.optString("accent", d.accent),
                o.optLong("deleteAfter", 0L).coerceAtLeast(0L), o.optString("name", d.name), o.optBoolean("stay", d.stay),
                o.optString("check", d.check), o.optBoolean("nearby", d.nearby))
        } catch (_: Exception) { null }
    }

    /** The name after Leave: the one it had before; a Kids' Device name never stays ("Raven 1234" when none was kept). */
    fun nameAfterLeave(saved: String, random: Int): String {
        val t = saved.trim()
        return if (t.isEmpty() || t in KID_NAMES) "Raven $random" else t
    }

    /** The names a Kids' Device gives itself ([KidTv.pairingCode]). */
    val KID_NAMES = setOf("Kids' TV", "Kids' Tablet", "Kids' Phone", "Kids' Device")

    // ---- on the device

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Just became a Kids' Device: what it had before is kept (once; a device already kept keeps the first). */
    fun saveOwner(app: Muninn) {
        try {
            val p = prefs(app)
            if (p.contains(OWNER)) return
            val id = app.identity
            val check = try { app.power.check.name } catch (_: Exception) { "" }
            val o = OwnerSettings(id.textSize, id.uiSize, id.highContrast, id.colorVision, id.reduceMotion, id.look, id.season, id.accent,
                id.deleteAfter, id.username, id.stayConnected, check, id.visibleNearby)
            p.edit().putString(OWNER, ownerJson(o)).apply()
        } catch (_: Exception) {}
    }

    private fun owner(c: Context): OwnerSettings = ownerIn(prefs(c).getString(OWNER, null)) ?: OwnerSettings()

    /**
     * A disconnect or Leave: the look, sizes and accessibility, how long messages stay, the colors and accessibility kid mode
     * took from a parent, the kids' tabs, drafts and open chat, the apps and permission asks waiting for a parent, and the
     * update state the parents saw all go back to what this device had before (Android's defaults when nothing was kept).
     */
    fun restore(app: Muninn) {
        val o = owner(app)
        val id = app.identity
        try {
            id.textSize = o.text; id.uiSize = o.size; id.highContrast = o.hc; id.reduceMotion = o.still
            id.colorVision = o.cv.ifEmpty { io.github.ravenor907.muninn.ui.ColorVision.TYPICAL }
            id.look = io.github.ravenor907.muninn.ui.Looks.of(o.look.ifEmpty { null })
            id.season = io.github.ravenor907.muninn.ui.Seasons.choice(o.season.ifEmpty { null })
            id.accent = io.github.ravenor907.muninn.ui.Accents.choice(o.accent.ifEmpty { null })
            if (id.deleteAfter != o.deleteAfter) { id.deleteAfter = o.deleteAfter; try { app.chat.pruneSoon() } catch (_: Exception) {} }
        } catch (_: Exception) {}
        try {
            val e = app.getSharedPreferences(LOOK_PREFS, Context.MODE_PRIVATE).edit()
            LOOK_KEYS.forEach { e.remove(it) }
            e.apply()
        } catch (_: Exception) {}
        try { TvUpdate.forget(app) } catch (_: Exception) {}
        try { TvAppInstall.forget(app) } catch (_: Exception) {}
        KidPhoneNav.drafts.clear()
        KidPhoneNav.chipAt = 0L
        // Screen state: on the main thread, where the screens read it.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                io.github.ravenor907.muninn.ui.Access.apply {
                    text = id.textSize; size = id.uiSize; contrast = id.highContrast; colorVision = id.colorVision; reduceMotion = id.reduceMotion
                    look = io.github.ravenor907.muninn.ui.Looks.of(id.look); season = io.github.ravenor907.muninn.ui.Seasons.choice(id.season)
                    accent = id.accent
                }
            } catch (_: Exception) {}
            KidPhoneNav.tab = "chats"; KidPhoneNav.filter = "all"; KidPhoneNav.colors = false
            TvNav.open = ""; TvNav.last = ""
        }
    }

    /**
     * Left kids' mode (after [restore]): its old name, staying connected, how often it checks and Nearby come back, and
     * first setup runs again (with "Set up as a Kids' Device"). What was kept goes, so a later Kids' Device keeps afresh.
     */
    fun afterLeave(app: Muninn) {
        val o = owner(app)
        val id = app.identity
        try {
            id.username = nameAfterLeave(o.name, kotlin.random.Random.nextInt(1000, 10000))
            id.stayConnected = o.stay
            id.visibleNearby = o.nearby
            o.check.takeIf { it.isNotEmpty() }?.let { c ->
                try { app.power.check = io.github.ravenor907.muninn.net.PowerLogic.Check.valueOf(c) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        try {
            val e = app.getSharedPreferences("muninn", Context.MODE_PRIVATE).edit()
            SETUP_KEYS.forEach { e.remove(it) }
            e.apply()
        } catch (_: Exception) {}
        prefs(app).edit().remove(OWNER).apply()
    }
}
