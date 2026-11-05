package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.call.CallSession.Phase
import io.github.ravenor907.muninn.tv.KidCallBack
import io.github.ravenor907.muninn.tv.KidK14
import io.github.ravenor907.muninn.tv.KidMerge
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.ManagedTv
import io.github.ravenor907.muninn.tv.PinTries
import io.github.ravenor907.muninn.tv.QuietTime
import io.github.ravenor907.muninn.tv.SafeStart
import io.github.ravenor907.muninn.tv.TimeAnchor
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 17: the Kids' Device fixes (trusted time, the PIN, removals win, devices that leave, two parents at once, TV calls). */
class Round17KidsTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kB = keys.getString("B")
    private val kC = keys.getString("C")
    private val kTV = keys.getString("TV")
    private val privA = ChatCrypto.privateFrom(chat.getString("privA"))
    private val mom = TvPerson(kA, "Mom", "a".repeat(64))
    private val bob = TvPerson(kB, "Bob", "b".repeat(64))
    private val grandpa = TvPerson(kC, "Grandpa", "c".repeat(64))

    private fun cfg(v: Long, people: List<TvPerson>, extras: TvExtras?, parents: List<String> = listOf(kA), ts: Long = 1761500000000 + v) =
        TvRules.config(privA, kTV, v, kA, parents, emptyList(), people, false, ts, extras)

    // ---- trusted time: one fast clock can't move it

    private val t0 = 1_780_000_000_000L
    private val h = 3_600_000L

    @Test fun oneClockFarAheadLosesToThreeThatAgree() {
        var r = emptyMap<String, TimeAnchor>()
        r = KidRules.addReading(r, "relay:bad", TimeAnchor(t0 + 12 * h, 1_000))
        r = KidRules.addReading(r, "relay:a", TimeAnchor(t0, 1_000))
        r = KidRules.addReading(r, "relay:b", TimeAnchor(t0 + 2_000, 1_000))
        r = KidRules.addReading(r, "parent:x", TimeAnchor(t0 - 3_000, 1_000))
        val a = KidRules.clockFrom(null, r, 1_000)!!
        assertTrue(kotlin.math.abs(KidRules.trustedNow(a, 1_000)!! - t0) < 5_000)
    }

    @Test fun oneSourceAloneCantJumpForwardOrBack() {
        val cur = TimeAnchor(t0, 1_000)
        // One relay 12 hours ahead: nothing moves.
        val ahead = KidRules.addReading(emptyMap(), "relay:bad", TimeAnchor(t0 + 12 * h, 2_000))
        assertTrue(KidRules.clockFrom(cur, ahead, 2_000) === cur)
        // One parent's phone an hour behind (or an old config resent late): nothing moves either.
        val behind = KidRules.addReading(emptyMap(), "parent:p", TimeAnchor(t0 - h, 2_000))
        assertTrue(KidRules.clockFrom(cur, behind, 2_000) === cur)
        // A small step forward from one source is taken (at most 10 minutes).
        val small = KidRules.addReading(emptyMap(), "relay:a", TimeAnchor(t0 + 1_000 + 60_000, 2_000))
        assertEquals(TimeAnchor(t0 + 61_000, 2_000), KidRules.clockFrom(cur, small, 2_000))
        // Between readings the uptime counts.
        assertEquals(t0 + 61_000 + 5_000, KidRules.trustedNow(KidRules.clockFrom(cur, small, 2_000), 7_000))
    }

    @Test fun twoSourcesThatAgreeMoveItEitherWay() {
        // The anchor came from one bad relay at start; two good ones later pull it back 12 hours.
        val bad = TimeAnchor(t0 + 12 * h, 1_000)
        var r = KidRules.addReading(emptyMap(), "relay:bad", bad)
        r = KidRules.addReading(r, "relay:a", TimeAnchor(t0 + 10_000, 11_000))
        r = KidRules.addReading(r, "parent:x", TimeAnchor(t0 + 10_500, 11_000))
        val a = KidRules.clockFrom(bad, r, 11_000)!!
        assertTrue(kotlin.math.abs(KidRules.trustedNow(a, 11_000)!! - (t0 + 10_000)) < 1_000)
        // A tie (one against one) goes to the reading nearest the current time.
        val cur = TimeAnchor(t0, 1_000)
        var tie = KidRules.addReading(emptyMap(), "relay:bad", TimeAnchor(t0 + 12 * h, 1_000))
        tie = KidRules.addReading(tie, "relay:a", TimeAnchor(t0 + 30_000, 1_000))
        assertEquals(TimeAnchor(t0 + 30_000, 1_000), KidRules.clockFrom(cur, tie, 1_000))
    }

    @Test fun readingsAreOnePerSourceAndBounded() {
        var r = emptyMap<String, TimeAnchor>()
        r = KidRules.addReading(r, "relay:a", TimeAnchor(t0, 1))
        r = KidRules.addReading(r, "relay:a", TimeAnchor(t0 + 5, 2))
        assertEquals(1, r.size)
        assertEquals(TimeAnchor(t0 + 5, 2), r["relay:a"])
        for (i in 0 until 30) r = KidRules.addReading(r, "relay:$i", TimeAnchor(t0, 10L + i))
        assertEquals(KidRules.MAX_SOURCES, r.size)
        // Nonsense (before 2024) is never a reading.
        assertEquals(r, KidRules.addReading(r, "relay:x", TimeAnchor(1_000, 50)))
        // Old readings (12 hours of uptime) aren't used.
        val old = KidRules.addReading(emptyMap(), "relay:a", TimeAnchor(t0, 0))
        assertNull(KidRules.clockFrom(null, old, KidRules.READING_MS + 1))
    }

    // ---- the parent PIN and asks

    @Test fun aTryCountsBeforeTheCheckAndGuessingNeverSpeedsUp() {
        val now = 1_000_000L
        assertEquals(PinTries(1, now), KidRules.counted(PinTries(), now))
        assertEquals(PinTries(8, now), KidRules.counted(PinTries(7, now - 2 * h), now))
        // Waits double up to 4 hours.
        assertEquals(KidRules.MAX_WAIT_MS, KidRules.waitMs(100))
        assertEquals(4 * h, KidRules.MAX_WAIT_MS)
        // New PINs: 6 to 8 digits; an old 4-digit one still checks.
        assertFalse(KidRules.newPinOk("1234"))
        assertFalse(KidRules.newPinOk("12345"))
        assertTrue(KidRules.newPinOk("123456"))
        assertTrue(KidRules.pinValid("1234"))
        // The remote's number keys and delete key; Enter is left to the focused key.
        assertEquals("0", KidRules.padKey(7)); assertEquals("9", KidRules.padKey(16))
        assertEquals("5", KidRules.padKey(149)); assertEquals("Delete", KidRules.padKey(67))
        assertNull(KidRules.padKey(66)); assertNull(KidRules.padKey(23))
    }

    @Test fun asksNobodyAnswersWaitLongerEachTime() {
        assertEquals(60_000L, KidRules.askGap(0))
        assertEquals(60_000L, KidRules.askGap(1))
        assertEquals(2 * 60_000L, KidRules.askGap(2))
        assertEquals(5 * 60_000L, KidRules.askGap(3))
        assertEquals(15 * 60_000L, KidRules.askGap(4))
        assertEquals(h, KidRules.askGap(5))
        assertEquals(h, KidRules.askGap(50))
        assertTrue(KidRules.askAgainOk(0, 5_000, 9))
        assertFalse(KidRules.askAgainOk(5_000, 5_000 + 30 * 60_000L, 5))
        assertTrue(KidRules.askAgainOk(5_000, 5_000 + h, 5))
        assertEquals("You can ask again in a minute.", KidRules.askAgainText(30_000))
        assertEquals("You can ask again in 15 minutes.", KidRules.askAgainText(15 * 60_000L))
    }

    @Test fun manyWrongPinsReachTheParentsInTheStatus() {
        val s = TvRules.status(JSONObject(TvRules.statusMessage(50, 10, 5, pinfail = 12)))!!
        assertEquals(12, s.pinfail)
        assertEquals(0, TvRules.status(JSONObject(TvRules.statusMessage(50, 10, 5)))!!.pinfail)
        assertFalse(JSONObject(TvRules.statusMessage(50, 10, 5)).has("pinfail"))
        // Junk there is ignored, not a reason to drop the status.
        assertEquals(0, TvRules.status(JSONObject(TvRules.statusMessage(50, 10, 5)).put("pinfail", "x"))!!.pinfail)
    }

    // ---- removals win over an older parent app

    @Test fun anOlderAppCantBringBackSomeoneTakenOff() {
        val v10 = TvRules.parse(cfg(10, listOf(mom, bob, grandpa), TvExtras(chat = 2), parents = listOf(kA, kC)))
        // Mom (k11) takes Bob off the list and Grandpa off as a parent.
        val v11 = TvRules.parse(cfg(11, listOf(mom, grandpa), TvExtras(chat = 2), parents = listOf(kA)))
        val (goneC, goneP) = KidRules.tombstones(v10, v11, emptySet(), emptySet())
        assertEquals(setOf(kB), goneC)
        assertEquals(setOf(kC), goneP)
        // Dad's 1.34 app (no k11) still lists both.
        val old = TvRules.parse(cfg(12, listOf(mom, bob, grandpa), TvExtras(calls = true, mic = 40), parents = listOf(kA, kC)))
        assertFalse(KidRules.hasK11(old.raw))
        val (kept, out) = KidRules.stripGone(KidRules.keepK11(old, v11), goneC, goneP)
        assertEquals(listOf(kA, kC), kept.contacts.map { it.k }.sorted())
        assertEquals(listOf(kA), kept.parents)
        assertEquals(setOf(kB, kC), out.toSet())
        // Its signer is never left out.
        assertEquals(kA, KidRules.stripGone(old, setOf(kA), setOf(kA)).first.by)
        assertTrue(kA in KidRules.stripGone(old, setOf(kA), setOf(kA)).first.parents)
        // A k11 config that lists Bob again (a parent put him back) clears him; one that drops Grandpa remembers him.
        val back = TvRules.parse(cfg(13, listOf(mom, bob, grandpa), TvExtras(chat = 2)))
        assertEquals(emptySet<String>(), KidRules.tombstones(v11, back, goneC, goneP).first)
        val drop = TvRules.parse(cfg(13, listOf(mom, bob), TvExtras(chat = 2)))
        assertEquals(setOf(kC), KidRules.tombstones(v11, drop, goneC, goneP).first)
    }

    @Test fun theParentsPhoneRemembersRemovalsToo() {
        assertEquals(listOf(kB), KidMerge.gone(listOf(kA, kB), listOf(kA), emptyList()))
        assertEquals(emptyList<String>(), KidMerge.gone(listOf(kA), listOf(kA, kB), listOf(kB)))
    }

    // ---- other families' devices that leave kids' mode

    @Test fun aDeviceThatLeftIsNoLongerTalkedTo() {
        val c = TvRules.parse(cfg(10, listOf(mom, bob), TvExtras(chat = 2)))
        val w = KidRules.withoutLeft(c, setOf(kB))
        assertEquals(listOf(kA), w.contacts.map { it.k })
        // Never a parent.
        assertEquals(listOf(kA, kB).sorted(), KidRules.withoutLeft(c, setOf(kA)).contacts.map { it.k }.sorted())
        // A k11 config signed after "left" that lists it again brings it back; an earlier one doesn't.
        val left = mapOf(kB to c.ts + 1_000)
        assertEquals(left, KidRules.leftAfter(left, c))
        val later = TvRules.parse(cfg(11, listOf(mom, bob), TvExtras(chat = 2), ts = c.ts + 5_000))
        assertTrue(KidRules.leftAfter(left, later).isEmpty())
        // An older parent app's config never does.
        val older = TvRules.parse(cfg(12, listOf(mom, bob), TvExtras(calls = true), ts = c.ts + 9_000))
        assertEquals(left, KidRules.leftAfter(left, older))
        // The messages.
        assertEquals(kB, KidRules.kidLeftIn(JSONObject(KidRules.kidLeftMessage(kB))))
        assertNull(KidRules.kidLeftIn(JSONObject(KidRules.kidLeftMessage(kB)).put("x", 1)))
        assertNull(KidRules.kidLeftIn(JSONObject().put("t", "kidleft").put("k", "a b")))
        assertTrue(KidRules.isTaken(JSONObject(KidRules.takenMessage())))
        assertFalse(KidRules.isTaken(JSONObject().put("t", "taken").put("by", kA)))
    }

    // ---- two parents at once

    @Test fun bothParentsChangesEndUpOnTheDevice() {
        val base = ManagedTv("tv", v = 10, applied = 10, reach = listOf(kB, kC), mic = 50, chat = 1, names = mapOf(kB to "Bob"))
        // Mom takes Grandpa off and renames Bob; Dad turned the microphone up and the chatting level on.
        val mine = base.copy(reach = listOf(kB), names = mapOf(kB to "Uncle Bob"))
        val theirs = base.copy(v = 11, applied = 11, mic = 80, chat = 2)
        val m = KidMerge.merge(base, mine, theirs)
        assertEquals(listOf(kB), m.reach)
        assertEquals("Uncle Bob", m.names[kB])
        assertEquals(80, m.mic)
        assertEquals(2, m.chat)
        assertEquals(11L, m.applied)
        // Who stays on the device: the lower hash (7.2).
        assertTrue(KidMerge.lost("bb", "aa"))
        assertFalse(KidMerge.lost("aa", "bb"))
        // Sets: theirs, plus what this phone added, minus what it took off.
        assertEquals(listOf("x", "z", "n"), KidMerge.setMerge(listOf("x", "y"), listOf("x", "n"), listOf("x", "y", "z")))
    }

    // ---- quiet times hold texts too

    @Test fun aHeldTextSurvivesARestart() {
        val a = JSONArray().put(JSONObject().put("id", "0".repeat(32)).put("k", kB).put("kind", "text").put("ms", 0).put("at", 5))
            .put(JSONObject().put("id", "1".repeat(32)).put("k", kB).put("kind", "quick").put("ms", 0).put("at", 6))
            .put(JSONObject().put("id", "2".repeat(32)).put("k", kB).put("kind", "exe").put("ms", 0).put("at", 7))
        assertEquals(listOf("text", "quick"), KidK14.heldIn(a).map { it.kind })
        assertTrue(KidK14.holds(kB, listOf(kA), KidK14.UNSURE))
        assertFalse(KidK14.holds(kA, listOf(kA), KidK14.UNSURE))
        val bed = QuietTime(KidRules.BED, KidRules.ALL_DAYS, 20 * 60, 7 * 60)
        assertTrue(KidRules.canSend(KidRules.QUICK_ONLY, "quick"))
        assertFalse(KidRules.canSend(KidRules.QUICK_ONLY, "text"))
        assertEquals("Will send at 07:00", KidK14.willSend(bed, true))
    }

    // ---- a call on a Kids' TV is never live with nothing on screen

    @Test fun aTvCallEndsWhenItsScreenGoesAway() {
        assertTrue(KidCallBack.endsHidden(Phase.LIVE))
        assertTrue(KidCallBack.endsHidden(Phase.CALLING))
        assertTrue(KidCallBack.endsHidden(Phase.RECONNECTING))
        assertFalse(KidCallBack.endsHidden(Phase.INCOMING))
        assertFalse(KidCallBack.endsHidden(Phase.ENDED))
        assertFalse(KidCallBack.endsHidden(null))
        assertEquals("decline", KidCallBack.screenOff(Phase.INCOMING))
        assertEquals("hangup", KidCallBack.screenOff(Phase.LIVE))
        assertEquals("", KidCallBack.screenOff(Phase.ENDED))
        assertEquals("", KidCallBack.screenOff(null))
        assertTrue(KidCallBack.stillDue(0, KidCallBack.IDLE_MS))
        assertFalse(KidCallBack.stillDue(0, KidCallBack.IDLE_MS - 1))
        assertFalse(KidCallBack.stillOver(0, 10 * KidCallBack.STILL_MS))
        assertTrue(KidCallBack.stillOver(1_000, 1_000 + KidCallBack.STILL_MS))
        assertEquals("The call with Grandma ends in 10 seconds. Open Muninn to keep talking.", KidCallBack.hiddenText("Grandma"))
        assertEquals(10_000L, KidCallBack.HIDDEN_MS)
    }

    @Test fun theCallingLineFitsAndroidsToastLimitsForTheWholeRing() {
        val at = KidCallBack.OVER_AT_MS
        // Each one after the last has gone (a long toast is 3.5 s).
        assertTrue(at.zipWithNext().all { (a, b) -> b - a >= 3_500L })
        // No more than Android shows from the background: 3 in 20 s, 5 in 42 s, 6 in 68 s.
        fun most(window: Long) = at.maxOf { s -> at.count { it in s until s + window } }
        assertTrue(most(20_000) <= 3 && most(42_000) <= 5 && most(68_000) <= 6)
        // The last one is still up near the end of a 45 s ring.
        assertTrue(at.last() + 3_500L >= 43_000L)
    }

    @Test fun pairingAsksForShowOverOtherAppsOnATv() {
        assertEquals(listOf("mic", "overlay"), TvRules.setupAsks(true, false, false, true, tv = true, overlayOn = false))
        assertEquals(listOf("mic"), TvRules.setupAsks(true, false, false, true, tv = false, overlayOn = false))
        assertEquals(emptyList<String>(), TvRules.setupAsks(true, true, false, true, tv = true, overlayOn = true))
        assertTrue(TvRules.OVERLAY_WHY.contains("Parent"))
    }

    // ---- safe mode

    @Test fun safeModeCountsNativeAndLateCrashesOnly() {
        assertEquals(2, SafeStart.LIMIT)
        assertTrue(SafeStart.exitCounts(SafeStart.REASON_CRASH_NATIVE, reachedHealthy = false))
        assertTrue(SafeStart.exitCounts(SafeStart.REASON_ANR, reachedHealthy = false))
        assertTrue(SafeStart.exitCounts(SafeStart.REASON_CRASH, reachedHealthy = false))
        assertFalse(SafeStart.exitCounts(SafeStart.REASON_CRASH, reachedHealthy = true))
        assertFalse(SafeStart.exitCounts(3, reachedHealthy = false))    // low memory
        assertFalse(SafeStart.exitCounts(10, reachedHealthy = false))   // stopped by the person
        assertTrue(SafeStart.countsUntilHealthy(90_000, healthy = false))
        assertFalse(SafeStart.countsUntilHealthy(90_000, healthy = true))
        assertFalse(SafeStart.countsUntilHealthy(SafeStart.HEALTHY_MS, healthy = false))
        // A parent's frame right at the start doesn't clear a crash loop early.
        assertEquals(SafeStart.HEALTHY_MS, SafeStart.healthyIn(0))
        assertEquals(SafeStart.AFTER_PARENT_MS, SafeStart.healthyIn(10 * 60_000L))
    }
}
