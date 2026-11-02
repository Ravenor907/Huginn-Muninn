package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.tv.Havamal
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Daily Hávamál (cap k12, shared/docs/protocol.md 7.18): the verses, the verse of the day, the config fields and imports. */
class HavamalTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kTV = keys.getString("TV")
    private val privA = ChatCrypto.privateFrom(chat.getString("privA"))
    private val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)))

    private fun cfg(v: Long, extras: TvExtras? = null) =
        TvRules.config(privA, kTV, v, kA, listOf(kA), emptyList(), people, false, 1761500000000 + v, extras)

    private fun asset(): String {
        val f = File("src/main/assets/${Havamal.ASSET}").takeIf { it.isFile } ?: File("app/src/main/assets/${Havamal.ASSET}")
        return f.readText(Charsets.UTF_8)
    }

    // ---- the verses

    @Test fun theVersesLoadAndEachHasItsNumberVerseAndMeaning() {
        val o = JSONObject(asset())
        assertTrue(o.getString("translation").contains("Bellows"))
        assertTrue(o.getString("note").contains("public domain"))
        val verses = Havamal.parse(asset())
        assertTrue(verses.size in 50..70)
        val raw = o.getJSONArray("verses")
        for (i in 0 until raw.length()) {
            val e = raw.getJSONObject(i)
            assertEquals(setOf("n", "verse", "means"), e.keys().asSequence().toSet())
            assertTrue(e.getInt("n") > 0)
            assertTrue(e.getString("verse").isNotBlank())
            assertTrue(e.getString("means").isNotBlank())
        }
        // In stanza order, each once, and no dashes in what the kids read.
        assertEquals(verses.map { it.n }.sorted(), verses.map { it.n })
        assertEquals(verses.size, verses.map { it.n }.toSet().size)
        for (v in verses) for (t in listOf(v.verse, v.means)) assertFalse("${v.n}", t.contains('\u2014') || t.contains('\u2013'))
    }

    // ---- the verse of the day

    @Test fun theDaysVerseIsTheSameAllDayAndCycles() {
        val size = Havamal.parse(asset()).size
        val today = LocalDate.of(2026, 11, 2).toEpochDay()
        // The same date gives the same verse, however often it's asked.
        assertEquals(Havamal.index(today, size), Havamal.index(today, size))
        assertEquals(Havamal.index(LocalDate.of(2026, 11, 2).toEpochDay(), size), Havamal.index(today, size))
        // The next day the next verse, and after the whole list it starts over.
        assertEquals((Havamal.index(today, size) + 1) % size, Havamal.index(today + 1, size))
        assertEquals(Havamal.index(today, size), Havamal.index(today + size, size))
        val seen = (0 until size).map { Havamal.index(today + it, size) }.toSet()
        assertEquals(size, seen.size)
        // Before 1970 still works.
        assertTrue(Havamal.index(-5, size) in 0 until size)
        assertEquals(0, Havamal.index(today, 0))
    }

    @Test fun earlierVersesAreTheOnesAlreadyShownAtMostThirty() {
        val size = 60
        val today = 20_000L
        // Came on today: nothing before.
        assertTrue(Havamal.earlier(today, today, size).isEmpty())
        // Came on three days ago: those three, newest first.
        assertEquals(listOf(today - 1, today - 2, today - 3).map { Havamal.index(it, size) }, Havamal.earlier(today, today - 3, size))
        // On for a long time: at most 30, never today's.
        val long = Havamal.earlier(today, today - 400, size)
        assertEquals(Havamal.EARLIER, long.size)
        assertFalse(Havamal.index(today, size) in long)
        // A short list repeats: each shows once.
        val short = Havamal.earlier(today, today - 30, 5)
        assertEquals(short.toSet().size, short.size)
        assertFalse(Havamal.index(today, 5) in short)
    }

    // ---- the config fields

    @Test fun theConfigFieldsRoundTripAndAreCheckedAndGatedByK12() {
        assertTrue(Havamal.CAP in Proto06.caps(Proto06.CAPS))
        val h = "c".repeat(64)
        val c = cfg(30, TvExtras(chat = KidRules.EVERYTHING, hav = true, havw = Havamal.MINE, havh = h))
        TvRules.check(c)
        val p = TvRules.parse(c)
        assertTrue(p.hav)
        assertEquals(Havamal.MINE, p.havw)
        assertEquals(h, p.havh)
        val b = TvRules.parse(cfg(31, TvExtras(chat = KidRules.EVERYTHING, hav = false, havw = Havamal.BELLOWS)))
        assertFalse(b.hav)
        assertEquals(Havamal.BELLOWS, b.havw)
        assertNull(b.havh)
        assertFalse(b.raw.has("havh"))
        // "Your translation" without a hash goes out as Bellows.
        assertEquals(Havamal.BELLOWS, cfg(32, TvExtras(hav = true, havw = Havamal.MINE)).getString("havw"))
        // A device without k12 never gets them (null: left out), so it never sees a config it would refuse.
        val older = cfg(33, TvExtras(chat = KidRules.EVERYTHING))
        for (f in listOf("hav", "havw", "havh")) assertFalse(f, older.has(f))
        val p0 = TvRules.parse(older)
        assertFalse(p0.hav)
        assertEquals(Havamal.BELLOWS, p0.havw)
        // Malformed: the wrong type, another wording, a hash with Bellows or not hex.
        fun bad(o: JSONObject, why: String) = try { Havamal.check(o); fail(why) } catch (e: IllegalArgumentException) { assertEquals(why, e.message) }
        bad(JSONObject().put("hav", 1), "hav")
        bad(JSONObject().put("havw", "c"), "havw")
        bad(JSONObject().put("havw", "b").put("havh", h), "havh")
        bad(JSONObject().put("havw", "y").put("havh", "C".repeat(64)), "havh")
        Havamal.check(JSONObject().put("hav", true).put("havw", "y").put("havh", h))
    }

    @Test fun anAbsentFieldKeepsTheDevicesValue() {
        val h = "d".repeat(64)
        val cur = TvRules.parse(cfg(40, TvExtras(chat = KidRules.QUICK_ONLY, hav = true, havw = Havamal.MINE, havh = h)))
        // An older parent app's config (no k11 or k12 field): Daily Hávamál and its wording stay.
        val older = TvRules.parse(cfg(41, TvExtras(calls = false)))
        val kept = KidRules.keepK11(older, cur)
        assertTrue(kept.hav)
        assertEquals(Havamal.MINE, kept.havw)
        assertEquals(h, kept.havh)
        assertEquals(KidRules.QUICK_ONLY, kept.chat)
        assertTrue(kept.raw === older.raw)
        // A k11 parent app without k12: k11 values set, k12 kept.
        val k11only = TvRules.parse(cfg(42, TvExtras(chat = KidRules.EVERYTHING)))
        val k = KidRules.keepK11(k11only, cur)
        assertEquals(KidRules.EVERYTHING, k.chat)
        assertTrue(k.hav)
        assertEquals(h, k.havh)
        // Each field on its own: "hav" sent, "havw" not.
        val onlyOff = TvRules.parse(cfg(43, TvExtras(chat = KidRules.EVERYTHING, hav = false)))
        val o = KidRules.keepK11(onlyOff, cur)
        assertFalse(o.hav)
        assertEquals(Havamal.MINE, o.havw)
        // Both sent: they set it.
        val set = KidRules.keepK11(TvRules.parse(cfg(44, TvExtras(chat = KidRules.EVERYTHING, hav = true, havw = Havamal.BELLOWS))), cur)
        assertEquals(Havamal.BELLOWS, set.havw)
        assertNull(set.havh)
        // Kept beside the signed config, so a restart keeps them too.
        assertTrue(KidRules.needsKept(older.raw))
        // Nothing to keep only once every k11, k12 and k13 field came.
        assertTrue(KidRules.needsKept(cfg(45, TvExtras(chat = 2, hav = true, havw = "b"))))
        assertFalse(KidRules.needsKept(cfg(45, TvExtras(chat = 2, hav = true, havw = "b", look = "dark", season = "off", accent = "gold",
            lookPick = true, kname = "Ava"))))
        val saved = JSONObject(KidRules.keptJson(kept).toString())
        val restarted = KidRules.keptIn(TvRules.parse(older.raw), saved)
        assertTrue(restarted.hav)
        assertEquals(Havamal.MINE, restarted.havw)
        assertEquals(h, restarted.havh)
        assertEquals(KidRules.QUICK_ONLY, restarted.chat)
        // A config that carries the fields isn't changed by the saved copy; a damaged copy changes nothing.
        val withOwn = TvRules.parse(cfg(46, TvExtras(chat = 2, hav = false, havw = "b")))
        assertFalse(KidRules.keptIn(withOwn, saved).hav)
        assertFalse(KidRules.keptIn(older, JSONObject().put("hav", "yes").put("havw", 3)).hav)
        // Pairing (nothing before): as it came, off.
        assertFalse(KidRules.keepK11(older, null).hav)
    }

    // ---- an imported translation

    @Test fun anImportKeepsOnlyNumberedStanzasOnTheList() {
        val text = "\uFEFFThe Hávamál\r\nA translation\r\n\r\n1. First line of one\r\nsecond line of one\r\n\r\n" +
            "St. 2\nTwo, on the line below\n\n3) three\nhas 4 goats in it\n" +
            "Stanza 4\nfour\n\n77\nCattle die\n\n12 and a number going down below\n999 too far\n"
        val got = Havamal.parseImport(text, setOf(1, 2, 3, 4, 5, 77))
        assertEquals(setOf(1, 2, 3, 4, 77), got.keys)
        assertEquals("First line of one\nsecond line of one", got[1])
        assertEquals("Two, on the line below", got[2])
        // A number inside a verse that doesn't go up is part of the verse.
        assertEquals("three\nhas 4 goats in it", got[3])
        assertEquals("four", got[4])
        assertTrue(got[77]!!.startsWith("Cattle die"))
        // Stanzas not on the list are dropped; nothing numbered, nothing kept.
        assertFalse(Havamal.parseImport("1. one\n2. two", setOf(2)).containsKey(1))
        assertTrue(Havamal.parseImport("No numbers here\nat all", setOf(1)).isEmpty())
        assertEquals("Imported: 5 of 60 verses matched", Havamal.matchedLine(5, 60))
    }

    @Test fun aTranslationTravelsInPartsAndIsCheckedByItsHash() {
        val stanzas = (1..120).associateWith { "Line one of $it\nline two of $it, " + "word ".repeat(40) }
        val json = Havamal.textJson(stanzas)
        assertEquals(stanzas, Havamal.textIn(json))
        val msgs = Havamal.partMessages(json)
        assertTrue(msgs.size > 1)
        val parts = msgs.map { Havamal.partIn(JSONObject(it))!! }
        assertTrue(parts.all { it.h == Havamal.hash(json) && it.of == msgs.size })
        // Out of order is fine; done only when every part is in.
        val a = Havamal.Assembler()
        for (p in parts.reversed().dropLast(1)) assertNull(a.add(p))
        assertEquals(json, a.add(parts.first()))
        // A wrong hash is not taken.
        val b = Havamal.Assembler()
        val wrong = parts.map { it.copy(h = "e".repeat(64)) }
        assertNull(wrong.map { b.add(it) }.last())
        // Malformed parts and texts are ignored.
        assertNull(Havamal.partIn(JSONObject(msgs[0]).put("x", 1)))
        assertNull(Havamal.partIn(JSONObject(msgs[0]).put("i", msgs.size)))
        assertNull(Havamal.textIn("""{"0":"x"}"""))
        assertNull(Havamal.textIn("""{"01":"x"}"""))
        assertNull(Havamal.textIn("not json"))
        assertNotEquals(Havamal.hash(json), Havamal.hash(json + " "))
        // "applied" says which translation a device holds.
        assertEquals(Havamal.hash(json), JSONObject(TvRules.appliedMessage(3, "1.35", dev = KidRules.TABLET, havh = Havamal.hash(json))).getString("havh"))
        assertFalse(JSONObject(TvRules.appliedMessage(3, "1.35")).has("havh"))
    }

    @Test fun theChosenWordingShowsWithBellowsForTheRest() {
        val v = Havamal.parse(asset()).first()
        val mine = mapOf(v.n to "My own words")
        assertEquals("My own words", Havamal.wordingOf(v, Havamal.MINE, mine))
        assertEquals(v.verse, Havamal.wordingOf(v, Havamal.BELLOWS, mine))
        assertEquals(v.verse, Havamal.wordingOf(v, Havamal.MINE, emptyMap()))
        assertEquals(v.verse, Havamal.wordingOf(v, Havamal.MINE, null))
    }
}
