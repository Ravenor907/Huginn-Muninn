package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.tv.KidLook
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.KidTheme
import io.github.ravenor907.muninn.tv.KidUi
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import io.github.ravenor907.muninn.ui.Accents
import io.github.ravenor907.muninn.ui.Looks
import io.github.ravenor907.muninn.ui.Seasons
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * A Kids' Device on a tablet or phone (shared/docs/protocol.md 7.18): the composer for each chatting level, the colors a
 * parent picks (cap k13), the list's order and filters, and nothing anywhere to add or scan.
 */
class KidPhoneTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kTV = keys.getString("TV")
    private val privA = ChatCrypto.privateFrom(chat.getString("privA"))
    private val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)))

    private fun cfg(v: Long, extras: TvExtras? = null) =
        TvRules.config(privA, kTV, v, kA, listOf(kA), emptyList(), people, false, 1761500000000 + v, extras)

    private fun srcDir() = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
    private fun source(name: String) = srcDir().walkTopDown().first { it.isFile && it.name == name }.readText()

    // ---- the composer

    @Test fun theChattingLevelPicksTheComposer() {
        // Quick messages only: no box, no photo, no voice; the quick messages are the composer, open, with big chips.
        val q = KidUi.compose(KidRules.QUICK_ONLY, mic = true)
        assertFalse(q.text)
        assertFalse(q.photo)
        assertFalse(q.voice)
        assertTrue(q.trayOpen)
        assertTrue(q.bigChips)
        // Everything: the box, photo and voice; the quick messages wait behind the smile.
        val e = KidUi.compose(KidRules.EVERYTHING, mic = true)
        assertTrue(e.text)
        assertTrue(e.photo)
        assertTrue(e.voice)
        assertFalse(e.trayOpen)
        assertFalse(e.bigChips)
        // No microphone: no voice messages, the rest as the level says.
        assertFalse(KidUi.compose(KidRules.EVERYTHING, mic = false).voice)
        assertTrue(KidUi.compose(KidRules.EVERYTHING, mic = false).text)
        assertFalse(KidUi.compose(KidRules.QUICK_ONLY, mic = false).voice)
        // Each button shows only when the device lets that through.
        for (level in listOf(KidRules.QUICK_ONLY, KidRules.EVERYTHING)) {
            val c = KidUi.compose(level, mic = true)
            assertEquals(KidRules.canSend(level, "text"), c.text)
            assertEquals(KidRules.canSend(level, "photo"), c.photo)
            assertEquals(KidRules.canSend(level, "voice"), c.voice)
            assertEquals(!c.text, c.trayOpen)
        }
    }

    // ---- the colors (k13)

    @Test fun theColorFieldsRoundTripAndAreCheckedAndGatedByK13() {
        assertTrue(KidLook.CAP in Proto06.caps(Proto06.CAPS))
        val c = cfg(50, TvExtras(chat = KidRules.EVERYTHING, look = Looks.LIGHT, season = Seasons.SNOW, accent = "#4f8fde", lookPick = false, kname = "  Ava  "))
        TvRules.check(c)
        assertEquals("#4F8FDE", c.getString("accent"))
        assertEquals("Ava", c.getString("kname"))
        val p = TvRules.parse(c)
        assertEquals(Looks.LIGHT, p.look)
        assertEquals(Seasons.SNOW, p.season)
        assertEquals("#4F8FDE", p.accent)
        assertFalse(p.lookPick)
        assertEquals("Ava", p.kname)
        assertEquals(KidTheme(Looks.LIGHT, Seasons.SNOW, "#4F8FDE"), KidLook.parentTheme(p))
        // A device without k13 never gets them (null: left out), so it never sees a config it would refuse.
        val older = cfg(51, TvExtras(chat = KidRules.EVERYTHING))
        for (f in KidLook.FIELDS) assertFalse(f, older.has(f))
        val p0 = TvRules.parse(older)
        assertNull(p0.look)
        assertTrue(p0.lookPick)
        assertEquals("", p0.kname)
        assertNull(KidLook.parentTheme(p0))
        // A blank name isn't sent.
        assertFalse(cfg(52, TvExtras(chat = 2, kname = "   ")).has("kname"))
        // Malformed: capitals, the wrong type, a lower-case hex, a color Muninn doesn't have, a blank, long or two-line name.
        fun bad(o: JSONObject, why: String) = try { KidLook.check(o); fail(why) } catch (e: IllegalArgumentException) { assertEquals(why, e.message) }
        bad(JSONObject().put("look", "Dark"), "look")
        bad(JSONObject().put("look", 1), "look")
        bad(JSONObject().put("season", ""), "season")
        bad(JSONObject().put("accent", "#4f8fde"), "accent")
        bad(JSONObject().put("accent", "pink"), "accent")
        bad(JSONObject().put("lookpick", "yes"), "lookpick")
        bad(JSONObject().put("kname", "  "), "kname")
        bad(JSONObject().put("kname", "a".repeat(41)), "kname")
        bad(JSONObject().put("kname", "A\nB"), "kname")
        // A look or Seasonal choice this app doesn't know (a newer parent's app) is well-formed and shows as Dark, Seasonal off.
        val newer = TvRules.parse(cfg(53, TvExtras(chat = 2, look = "neon", season = "spring", accent = "jade")))
        assertEquals(KidTheme(Looks.DARK, Seasons.OFF, "jade"), KidLook.parentTheme(newer))
        // Any other new field is still refused.
        try { TvRules.check(JSONObject(c.toString()).put("colour", "red")); fail("fields") } catch (e: IllegalArgumentException) { assertEquals("fields", e.message) }
        // Every Look, Seasonal choice and accent Muninn has can be sent.
        for ((l, _) in Looks.ALL) for ((s, _) in Seasons.CHOICES) for (a in Accents.NAMES)
            TvRules.check(cfg(54, TvExtras(chat = 2, look = l, season = s, accent = a)))
    }

    @Test fun anAbsentColorFieldKeepsTheDevicesValue() {
        val cur = TvRules.parse(cfg(60, TvExtras(chat = 2, look = Looks.BLACK, season = Seasons.AUTO, accent = "ember", lookPick = false, kname = "Ava")))
        // An older parent app (no k11, k12 or k13 field), or one without k13: all five stay.
        for (new in listOf(TvRules.parse(cfg(61, TvExtras(calls = false))), TvRules.parse(cfg(62, TvExtras(chat = 2, hav = true, havw = "b"))))) {
            val k = KidRules.keepK11(new, cur)
            assertEquals(Looks.BLACK, k.look)
            assertEquals(Seasons.AUTO, k.season)
            assertEquals("ember", k.accent)
            assertFalse(k.lookPick)
            assertEquals("Ava", k.kname)
            assertTrue(k.raw === new.raw)
        }
        // Each field on its own: only "lookpick" sent.
        val onlyPick = KidRules.keepK11(TvRules.parse(cfg(63, TvExtras(chat = 2, lookPick = true))), cur)
        assertTrue(onlyPick.lookPick)
        assertEquals(Looks.BLACK, onlyPick.look)
        assertEquals("Ava", onlyPick.kname)
        // All sent: they set it.
        val set = KidRules.keepK11(TvRules.parse(cfg(64, TvExtras(chat = 2, look = Looks.LIGHT, season = Seasons.OFF, accent = "gold",
            lookPick = true, kname = "Max"))), cur)
        assertEquals(KidTheme(Looks.LIGHT, Seasons.OFF, "gold"), KidLook.parentTheme(set))
        assertTrue(set.lookPick)
        assertEquals("Max", set.kname)
        // Kept beside the signed config, so a restart keeps them too.
        val older = TvRules.parse(cfg(65, TvExtras(chat = 2)))
        val kept = KidRules.keepK11(older, cur)
        assertTrue(KidRules.needsKept(older.raw))
        val saved = JSONObject(KidRules.keptJson(kept).toString())
        val restarted = KidRules.keptIn(TvRules.parse(older.raw), saved)
        assertEquals(Looks.BLACK, restarted.look)
        assertEquals(Seasons.AUTO, restarted.season)
        assertEquals("ember", restarted.accent)
        assertFalse(restarted.lookPick)
        assertEquals("Ava", restarted.kname)
        // A config that carries them isn't changed by the saved copy; a damaged copy changes nothing.
        assertEquals(Looks.LIGHT, KidRules.keptIn(TvRules.parse(cfg(66, TvExtras(chat = 2, look = Looks.LIGHT))), saved).look)
        val damaged = KidRules.keptIn(older, JSONObject().put("look", 5).put("accent", "#zzzzzz").put("lookpick", "no").put("kname", ""))
        assertNull(damaged.look)
        assertNull(damaged.accent)
        assertTrue(damaged.lookPick)
        assertEquals("", damaged.kname)
        // Pairing (nothing before): as it came.
        assertNull(KidRules.keepK11(older, null).look)
    }

    @Test fun theParentsColorsGoOnWhenTheyChangeOrWhileTheKidMayNotPick() {
        val pick = TvRules.parse(cfg(70, TvExtras(chat = 2, look = Looks.LIGHT, season = Seasons.OFF, accent = "jade", lookPick = true)))
        val parent = KidLook.parentTheme(pick)
        assertNotNull(parent)
        val kids = KidTheme(Looks.BLACK, Seasons.SNOW, "ember")
        // New to the device: they go on.
        assertEquals(parent, KidLook.toApply(pick, "", kids))
        // The same colors sent again: the kid's own pick stays.
        assertNull(KidLook.toApply(pick, parent!!.key, kids))
        assertTrue(KidLook.kidMayPick(pick))
        // The parent changed them: on again.
        val changed = TvRules.parse(cfg(71, TvExtras(chat = 2, look = Looks.DARK, season = Seasons.OFF, accent = "jade", lookPick = true)))
        assertEquals(Looks.DARK, KidLook.toApply(changed, parent.key, kids)?.look)
        // Let the kid choose off: no My colors, and always the parent's colors.
        val locked = TvRules.parse(cfg(72, TvExtras(chat = 2, look = Looks.LIGHT, season = Seasons.OFF, accent = "jade", lookPick = false)))
        assertFalse(KidLook.kidMayPick(locked))
        assertEquals(parent, KidLook.toApply(locked, parent.key, kids))
        assertNull(KidLook.toApply(locked, parent.key, parent))
        // No colors from a parent yet: nothing changes.
        assertNull(KidLook.toApply(TvRules.parse(cfg(73, TvExtras(chat = 2))), "", kids))
    }

    // ---- the list

    @Test fun theListKeepsMuninnsOrderAndFilters() {
        val last = mapOf("dad" to 10L, "mom" to 30L, "g:x" to 20L, "amy" to 0L, "ben" to 0L)
        // Unread first, newest first; then the rest newest first; nothing yet keeps the list's own order.
        assertEquals(listOf("g:x", "mom", "dad", "amy", "ben"), KidUi.order(listOf("dad", "mom", "amy", "ben", "g:x"), setOf("g:x")) { last[it] ?: 0L })
        assertEquals(listOf("mom", "g:x", "dad", "amy", "ben"), KidUi.order(listOf("dad", "mom", "amy", "ben", "g:x"), emptySet()) { last[it] ?: 0L })
        assertEquals(listOf("all", "family", "groups"), KidUi.FILTERS.map { it.first })
        assertEquals(listOf("All", "Family", "Groups"), KidUi.FILTERS.map { it.second })
        assertTrue(KidUi.keeps("all", "g:x") && KidUi.keeps("all", "dad"))
        assertTrue(KidUi.keeps("family", "dad"))
        assertFalse(KidUi.keeps("family", "g:x"))
        assertTrue(KidUi.keeps("groups", "g:x"))
        assertFalse(KidUi.keeps("groups", "dad"))
    }

    @Test fun theGreetingUsesTheKidsName() {
        assertEquals("Hi, Ava", KidUi.greeting("Ava"))
        assertEquals("Hi, Ava", KidUi.greeting("  Ava "))
        for (n in listOf("", "Kids' Tablet", "Kids' Phone", "Kids' TV", "Raven 1234")) assertEquals(n, "Hi there", KidUi.greeting(n))
    }

    // ---- nothing to add or scan

    @Test fun theKidsNavNeverOffersAddOrScan() {
        assertEquals(listOf("chats", "call", "people"), KidUi.TABS.map { it.first })
        assertEquals(listOf("Chats", "Call", "People"), KidUi.TABS.map { it.second })
        val words = KidUi.TABS + KidUi.FILTERS + KidUi.CARD_ACTIONS
        for ((id, label) in words) for (w in KidUi.NEVER)
            assertFalse("$id offers $w", Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn("$id $label"))
        for ((_, label) in words) assertFalse(label, label.contains('\u2014') || label.contains('\u2013'))
        // The screens themselves: no Add someone, scanner, + button, code, Share nearby or Settings anywhere in them.
        val src = source("KidPhone.kt")
        for (bad in listOf("AddSomeone", "QrScan", "addMode", "\"add\"", "\"plus\"", "\"qr\"", "Share nearby", "TransferScreen",
                "SettingsScreen", "ChatNav.open", "myCode", "pairingCode", "NewGroup"))
            assertFalse(bad, src.contains(bad))
        // A tablet or phone gets Muninn's own layout, KidPhone: no separate tile screen for touch.
        assertTrue(srcDir().walkTopDown().none { it.isFile && it.name == "KidTouch.kt" })
        assertTrue(source("TvRoot.kt").contains("KidPhone(app, cfg, shown, quiet)"))
    }
}
