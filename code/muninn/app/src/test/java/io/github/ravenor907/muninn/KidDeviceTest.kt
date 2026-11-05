package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.tv.KidPin
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.ParentTvMenu
import io.github.ravenor907.muninn.tv.QuietTime
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvParent
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import io.github.ravenor907.muninn.tv.TvUpd
import io.github.ravenor907.muninn.tv.kidPeople
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Kids' Device (cap k11, shared/docs/protocol.md 7.18): the rename keeps a paired TV working, the chatting level, contacts
 * only from parents, the parent PIN, quiet times (overnight too, parents passing through) and cap gating.
 */
class KidDeviceTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kB = keys.getString("B")
    private val kTV = keys.getString("TV")
    private val privA = ChatCrypto.privateFrom(chat.getString("privA"))
    private val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)), TvPerson(kB, "Grandma", "b".repeat(64)))

    private fun cfg(v: Long, extras: TvExtras? = null) =
        TvRules.config(privA, kTV, v, kA, listOf(kA), emptyList(), people, false, 1761500000000 + v, extras)

    private fun malformed(c: JSONObject, why: String) {
        try { TvRules.check(c); fail("took a config with a bad $why") } catch (e: IllegalArgumentException) { assertEquals(why, e.message) }
    }

    // ---- the rename: wire names and saved names stay, a paired TV keeps working

    @Test fun aTvPairedBeforeTheRenameKeepsWorking() {
        // The signed domains and the pairing code's "tv":1 are as they were, so no re-pairing.
        assertEquals("huginn-muninn tvconfig v1", TvRules.CONFIG)
        assertEquals("huginn-muninn tvdisconnect v1", TvRules.DISCONNECT)
        val o = JSONObject().put("k", kTV).put("n", "Kids' TV").put("p", "").put("tv", 1)
        val code = Contacts.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray(Charsets.UTF_8))
        assertEquals(kTV, TvParent.tvKeyIn(code))
        // A config from before k11 (no chat, quiet or pin) still checks and reads as before: everything allowed, no quiet, no PIN.
        val old = cfg(3, TvExtras(calls = true, mic = 50, keep = 7))
        TvRules.check(old)
        assertTrue(TvRules.accept(kTV, listOf(kA), null, old).first)
        val p = TvRules.parse(old)
        assertEquals(KidRules.EVERYTHING, p.chat)
        assertTrue(p.quiet.isEmpty())
        assertNull(p.pin)
        // The vectors' configs (made by the reference) still read.
        val vs = all.getJSONObject("tv").getJSONObject("configs")
        for (k in vs.keys()) TvRules.check(vs.getJSONObject(k).getJSONObject("config"))
    }

    @Test fun anOlderTvSaysNothingAboutItsKindAndShowsAsATv() {
        val applied = JSONObject(TvRules.appliedMessage(4, "1.34"))
        assertFalse(applied.has("dev"))
        assertEquals("", KidRules.deviceIn(applied))
        assertEquals("TV", KidRules.deviceName("", k11 = false))
        assertEquals("device", KidRules.deviceName("", k11 = true))
        val tablet = JSONObject(TvRules.appliedMessage(4, "1.35", dev = KidRules.TABLET))
        assertEquals("tablet", KidRules.deviceIn(tablet))
        assertEquals("", KidRules.deviceIn(JSONObject().put("dev", "fridge")))
        // A kind the app doesn't know is never put in.
        assertFalse(JSONObject(TvRules.appliedMessage(4, "1.35", dev = "fridge")).has("dev"))
    }

    @Test fun theParentPageSaysKidsDeviceNotTv() {
        val labels = (ParentTvMenu.items + ParentTvMenu.more).map { it.second }
        for (l in labels) assertFalse(l, Regex("\\bTV\\b").containsMatchIn(l))
        assertEquals("Update this device first", TvUpd.NEEDS_FIRST)
        for (t in listOf(TvRules.updateText("needs"), TvRules.updateText("ready"), TvRules.updateText("failed"), TvUpd.TOUCH_PROMPT,
                KidRules.EVERYTHING_NAME, KidRules.QUICK_ONLY_NAME))
            assertFalse(t, t.contains('\u2014') || t.contains('\u2013') || Regex("\\bTV\\b").containsMatchIn(t))
    }

    // ---- the chatting level

    @Test fun quickMessagesOnlyMeansNothingElseIsSent() {
        for (what in listOf("text", "say", "photo", "voice")) {
            assertFalse(what, KidRules.canSend(KidRules.QUICK_ONLY, what))
            assertTrue(what, KidRules.canSend(KidRules.EVERYTHING, what))
        }
        assertTrue(KidRules.canSend(KidRules.QUICK_ONLY, "quick"))
        // On a TV: Quick messages only leaves Call (when calls are on) and the quick messages.
        val voice = KidRules.canSend(KidRules.QUICK_ONLY, "voice")
        val say = KidRules.canSend(KidRules.QUICK_ONLY, "say")
        assertEquals(listOf("call"), TvRules.talkButtons(calls = true, mic = true && voice, dictate = true && say))
        assertEquals("q1", TvRules.firstTalkFocus(false, voice, say, listOf("q1")))
        // The level goes in the config and comes back out.
        val c = cfg(5, TvExtras(chat = KidRules.QUICK_ONLY))
        TvRules.check(c)
        assertEquals(KidRules.QUICK_ONLY, TvRules.parse(c).chat)
        malformed(JSONObject(c.toString()).put("chat", 3), "chat")
        malformed(JSONObject(c.toString()).put("chat", 0), "chat")
        malformed(JSONObject(c.toString()).put("chat", true), "chat")
    }

    // ---- contacts only from parents

    @Test fun theKidsReachOnlyWhoTheParentsListed() {
        val c = TvRules.parse(cfg(6))
        assertEquals(listOf(kA, kB), kidPeople(c, null).map { it.k })
        // A config signed by someone who isn't a parent is refused: nobody else can put people on the list.
        val stranger = TvRules.config(privA, kTV, 7, kA, listOf(kA), emptyList(), people + TvPerson("someone", "Stranger", ""), false, 1)
        assertFalse(TvRules.accept(kTV, listOf(kB), c.raw, stranger).first)
        // An older config can't bring back someone a parent took off.
        val newer = TvRules.config(privA, kTV, 8, kA, listOf(kA), emptyList(), people.take(1), false, 2)
        assertTrue(TvRules.accept(kTV, listOf(kA), c.raw, newer).first)
        assertFalse(TvRules.accept(kTV, listOf(kA), newer, c.raw).first)
    }

    // ---- the parent PIN

    @Test fun pbkdf2MatchesTheKnownVectors() {
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        assertEquals("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc", hex(KidRules.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1)))
        assertEquals("ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43", hex(KidRules.pbkdf2("password".toByteArray(), "salt".toByteArray(), 2)))
        // The PIN hash itself (Python: hashlib.pbkdf2_hmac("sha256", b"1234", salt, 20000, 32)).
        assertEquals("1eeaf002a05b931ed2f5dd9fd99fc48b215f8e2dc3f300d356eb83bfe6a4adb4", KidRules.pinHash("1234", "00112233445566778899aabbccddeeff"))
    }

    @Test fun onlyTheRightPinOpensAndWrongOnesSlowDown() {
        val p = KidRules.newPin("2580")
        assertTrue(KidRules.pinOk("2580", p))
        assertFalse(KidRules.pinOk("2581", p))
        assertFalse(KidRules.pinOk("", p))
        assertFalse(KidRules.pinOk("2580", null))
        assertFalse(KidRules.pinValid("123"))
        assertFalse(KidRules.pinValid("123456789"))
        assertFalse(KidRules.pinValid("12a4"))
        assertTrue(KidRules.pinValid("00000000"))
        // Two PINs alike get different salts, so their hashes differ.
        assertFalse(KidRules.newPin("2580").h == p.h)
        assertEquals(0L, KidRules.waitMs(4))
        assertEquals(30_000L, KidRules.waitMs(5))
        assertEquals(60_000L, KidRules.waitMs(6))
        assertEquals(KidRules.MAX_WAIT_MS, KidRules.waitMs(50))
        // In the config: only the hash, well-formed or refused.
        val c = cfg(9, TvExtras(chat = 2, pin = p))
        TvRules.check(c)
        assertEquals(p, TvRules.parse(c).pin)
        assertFalse(c.toString().contains("\"2580\""))
        malformed(JSONObject(c.toString()).put("pin", JSONObject().put("salt", p.salt.uppercase()).put("h", p.h)), "pin")
        malformed(JSONObject(c.toString()).put("pin", JSONObject().put("salt", p.salt).put("h", p.h).put("pin", "2580")), "pin")
        malformed(JSONObject(c.toString()).put("pin", "2580"), "pin")
    }

    @Test fun aParentsPhoneUnlocksOnlyTheAskItWasSent() {
        val id = KidRules.askId()
        assertEquals(id, KidRules.idIn(JSONObject(KidRules.unlockAskMessage(id)), "unlockask"))
        assertEquals(id, KidRules.idIn(JSONObject(KidRules.unlockMessage(id)), "unlock"))
        assertNull(KidRules.idIn(JSONObject(KidRules.unlockMessage(id)).put("x", 1), "unlock"))
        assertNull(KidRules.idIn(JSONObject().put("t", "unlock").put("id", "nothex"), "unlock"))
        val t0 = 1_000_000L
        assertTrue(KidRules.unlockOk(kA, listOf(kA), id, t0, id, t0 + 60_000))
        assertFalse(KidRules.unlockOk(kB, listOf(kA), id, t0, id, t0 + 60_000))          // not a parent
        assertFalse(KidRules.unlockOk(kA, listOf(kA), id, t0, KidRules.askId(), t0))      // another ask
        assertFalse(KidRules.unlockOk(kA, listOf(kA), null, t0, id, t0))                  // nothing asked
        assertFalse(KidRules.unlockOk(kA, listOf(kA), id, t0, id, t0 + KidRules.ASK_MS + 1))   // too late
        assertTrue(KidRules.isLeft(JSONObject(KidRules.leftMessage())))
        assertFalse(KidRules.isLeft(JSONObject().put("t", "left").put("x", 1)))
    }

    // ---- quiet times

    private val mon = 0; private val tue = 1; private val sun = 6
    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test fun schoolHoursAreOnOnlyOnTheirDaysAndTimes() {
        val school = QuietTime(KidRules.SCHOOL, KidRules.WEEKDAYS, at(8), at(15))
        val list = listOf(school)
        assertEquals(school, KidRules.quietNow(list, mon, at(8)))
        assertEquals(school, KidRules.quietNow(list, mon, at(14, 59)))
        assertNull(KidRules.quietNow(list, mon, at(15)))
        assertNull(KidRules.quietNow(list, mon, at(7, 59)))
        assertNull(KidRules.quietNow(list, sun, at(10)))
    }

    @Test fun bedtimeRunsPastMidnightFromTheDayItStarts() {
        // Only Monday nights: Monday 22:00 to Tuesday 06:00.
        val bed = QuietTime(KidRules.BED, 1 shl mon, at(22), at(6))
        val list = listOf(bed)
        assertEquals(bed, KidRules.quietNow(list, mon, at(22)))
        assertEquals(bed, KidRules.quietNow(list, mon, at(23, 59)))
        assertEquals(bed, KidRules.quietNow(list, tue, at(0)))
        assertEquals(bed, KidRules.quietNow(list, tue, at(5, 59)))
        assertNull(KidRules.quietNow(list, tue, at(6)))
        assertNull(KidRules.quietNow(list, mon, at(21, 59)))
        assertNull(KidRules.quietNow(list, tue, at(22)))     // Tuesday night isn't on
        assertNull(KidRules.quietNow(list, mon, at(3)))      // early Monday belongs to Sunday night, which isn't on
        // Sunday night runs into Monday morning (the week wraps).
        val sunday = listOf(QuietTime(KidRules.BED, 1 shl sun, at(20), at(7)))
        assertTrue(KidRules.quietNow(sunday, mon, at(6, 30)) != null)
    }

    @Test fun parentsPassThroughAQuietTimeAndNobodyElseDoes() {
        val bed = QuietTime(KidRules.BED, KidRules.ALL_DAYS, at(20), at(7))
        assertTrue(KidRules.passes(kA, listOf(kA), bed))
        assertFalse(KidRules.passes(kB, listOf(kA), bed))
        assertTrue(KidRules.passes(kB, listOf(kA), null))
        // The home screen shows only the parents then.
        val c = TvRules.parse(cfg(10, TvExtras(chat = 2, quiet = listOf(bed))))
        assertEquals(listOf(kA), kidPeople(c, bed).map { it.k })
        assertEquals(listOf(bed), c.quiet)
    }

    @Test fun quietTimesAreWellFormedOrRefused() {
        val school = KidRules.quietDefault(KidRules.SCHOOL)
        val bed = KidRules.quietDefault(KidRules.BED)
        assertEquals(listOf(school, bed), KidRules.quietIn(KidRules.quietJson(listOf(bed, school))))
        val c = cfg(11, TvExtras(chat = 2, quiet = listOf(school, bed)))
        TvRules.check(c)
        fun withQuiet(vararg w: JSONObject) = JSONObject(c.toString()).put("quiet", JSONArray().apply { w.forEach { put(it) } })
        fun w(n: String = "bed", d: Any = 127, s: Any = 1200, e: Any = 420) = JSONObject().put("n", n).put("d", d).put("s", s).put("e", e)
        malformed(withQuiet(w(s = 600, e = 600)), "quiet")              // start and end the same
        malformed(withQuiet(w(), w()), "quiet")                          // bedtime twice
        malformed(withQuiet(w(d = 0)), "quiet")
        malformed(withQuiet(w(d = 128)), "quiet")
        malformed(withQuiet(w(e = 1440)), "quiet")
        malformed(withQuiet(w(n = "nap")), "quiet")
        malformed(withQuiet(w(s = "20:00")), "quiet")
        malformed(withQuiet(w().put("x", 1)), "quiet")
        malformed(JSONObject(c.toString()).put("quiet", JSONObject()), "quiet")
        assertEquals("Weekdays", KidRules.daysText(KidRules.WEEKDAYS))
        assertEquals("Mon, Wed", KidRules.daysText(5))
        assertEquals("07:05", KidRules.clock(at(7, 5)))
    }

    // ---- cap gating

    @Test fun onlyADeviceThatSaysK11GetsTheNewFields() {
        assertTrue(KidRules.CAP in Proto06.caps(Proto06.CAPS))
        // A parent's phone leaves them out for an older device (null), so that device never sees a config it would refuse.
        val older = cfg(12, TvExtras(calls = true, mic = 50, keep = 7))
        for (f in listOf("chat", "quiet", "pin")) assertFalse(f, older.has(f))
        // For a k11 device: chat always, quiet and pin only when set.
        val k11 = cfg(13, TvExtras(chat = 1, quiet = emptyList()))
        assertTrue(k11.has("chat"))
        assertFalse(k11.has("quiet"))
        assertFalse(k11.has("pin"))
        // The reference's well-formed check, as an older device has it, would refuse these fields: "fields".
        val oldFields = setOf("tv", "v", "by", "parents", "quick", "contacts", "dictation", "ts", "sig", "calls", "mic", "nr", "upd", "keep")
        assertFalse(oldFields.containsAll(k11.keys().asSequence().toSet()))
        assertTrue(oldFields.containsAll(older.keys().asSequence().toSet()))
        assertEquals(KidPin("00112233445566778899aabbccddeeff", "a".repeat(64)),
            KidRules.pinIn(JSONObject().put("salt", "00112233445566778899aabbccddeeff").put("h", "a".repeat(64))))
    }

    @Test fun anOlderParentAppsConfigKeepsTheDevicesLevelQuietTimesAndPin() {
        val pin = KidPin("00112233445566778899aabbccddeeff", "b".repeat(64))
        val bed = KidRules.quietDefault(KidRules.BED)
        val cur = TvRules.parse(cfg(20, TvExtras(chat = KidRules.QUICK_ONLY, quiet = listOf(bed), pin = pin)))
        // An older parent app's next change (no k11 field): the rest is taken, the k11 values stay.
        val older = TvRules.parse(cfg(21, TvExtras(calls = false, mic = 40, keep = 3)))
        assertFalse(KidRules.hasK11(older.raw))
        val kept = KidRules.keepK11(older, cur)
        assertEquals(21L, kept.v)
        assertFalse(kept.calls)
        assertEquals(KidRules.QUICK_ONLY, kept.chat)
        assertEquals(listOf(bed), kept.quiet)
        assertEquals(pin, kept.pin)
        // The signed config stays exactly as it came.
        assertTrue(kept.raw === older.raw)
        // Kept beside the signed config, so a restart keeps them too.
        val saved = JSONObject(KidRules.keptJson(kept).toString())
        val restarted = KidRules.keptIn(TvRules.parse(older.raw), saved)
        assertEquals(KidRules.QUICK_ONLY, restarted.chat)
        assertEquals(listOf(bed), restarted.quiet)
        assertEquals(pin, restarted.pin)
        // A damaged saved copy changes nothing.
        assertEquals(KidRules.EVERYTHING, KidRules.keptIn(older, JSONObject().put("quiet", "x")).chat)
        // A config that carries a k11 field sets all three: here quiet times and the PIN turned off.
        val k11 = TvRules.parse(cfg(22, TvExtras(chat = KidRules.EVERYTHING)))
        val set = KidRules.keepK11(k11, kept)
        assertEquals(KidRules.EVERYTHING, set.chat)
        assertTrue(set.quiet.isEmpty())
        assertNull(set.pin)
        // ...and the saved copy is not used for it.
        assertNull(KidRules.keptIn(k11, saved).pin)
        // Pairing (nothing before): the config as it came.
        assertEquals(KidRules.EVERYTHING, KidRules.keepK11(older, null).chat)
    }
}
