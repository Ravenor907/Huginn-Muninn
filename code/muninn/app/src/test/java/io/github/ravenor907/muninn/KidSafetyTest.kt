package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.tv.KidPin
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.PinTries
import io.github.ravenor907.muninn.tv.QuietTime
import io.github.ravenor907.muninn.tv.TimeAnchor
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Kids' Device safety (shared/docs/protocol.md 7.18): an older co-parent's config only adds, quiet times follow a trusted
 * time in the parent's zone, the PIN hash goes only to parents, PIN hashing and the wait after wrong PINs, other Kids'
 * Devices and groups.
 */
class KidSafetyTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kB = keys.getString("B")
    private val kC = keys.getString("C")
    private val kTV = keys.getString("TV")
    private val privA = ChatCrypto.privateFrom(chat.getString("privA"))
    private val dad = TvPerson(kA, "Dad", "a".repeat(64))
    private val grandma = TvPerson(kB, "Grandma", "b".repeat(64))
    private val aunt = TvPerson(kC, "Aunt Jo", "c".repeat(64))
    private val salt = "00112233445566778899aabbccddeeff"

    private fun cfg(v: Long, people: List<TvPerson>, extras: TvExtras?, parents: List<String> = listOf(kA)) =
        TvRules.config(privA, kTV, v, kA, parents, emptyList(), people, false, 1761500000000 + v, extras)

    private fun malformed(c: JSONObject, why: String) {
        try { TvRules.check(c); fail("took a config with a bad $why") } catch (e: IllegalArgumentException) { assertEquals(why, e.message) }
    }

    // ---- an older co-parent's app (no k11): its config only adds

    @Test fun anOlderCoParentsConfigNeverTakesAnyoneOff() {
        val cur = TvRules.parse(cfg(20, listOf(dad, grandma), TvExtras(chat = KidRules.QUICK_ONLY), parents = listOf(kA, kC)))
        // Lauren's 1.34 phone never saw Grandma or the second parent: its next change lists neither, and renames Dad.
        val older = TvRules.parse(cfg(21, listOf(dad.copy(n = "Daddy"), aunt), TvExtras(calls = true, mic = 50)))
        assertFalse(KidRules.hasK11(older.raw))
        val kept = KidRules.keepK11(older, cur)
        assertEquals(listOf(kA, kB, kC).sorted(), kept.contacts.map { it.k })
        assertEquals("Daddy", kept.contacts.first { it.k == kA }.n)          // a name it gives is taken
        assertEquals("Grandma", kept.contacts.first { it.k == kB }.n)        // nobody is deleted with their chats
        assertTrue(kC in kept.parents)                                       // nor a parent
        assertEquals(KidRules.QUICK_ONLY, kept.chat)
        assertTrue(kept.raw === older.raw)
        // A restart keeps the people it kept.
        val restarted = KidRules.keptIn(TvRules.parse(older.raw), JSONObject(KidRules.keptJson(kept).toString()))
        assertEquals(kept.contacts, restarted.contacts)
        assertEquals(kept.parents, restarted.parents)
        // A parent's app with k11 can take someone off.
        val k11 = TvRules.parse(cfg(22, listOf(dad), TvExtras(chat = KidRules.QUICK_ONLY, tz = "Europe/London")))
        val set = KidRules.keepK11(k11, kept)
        assertEquals(listOf(kA), set.contacts.map { it.k })
        assertEquals(listOf(kA), set.parents)
        assertEquals("Europe/London", set.tz)
    }

    // ---- quiet times by a trusted time, in the parent's zone

    private val la = "America/Los_Angeles"
    private val bed = QuietTime(KidRules.BED, KidRules.ALL_DAYS, 20 * 60, 7 * 60)
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: String) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test fun quietTimesIgnoreTheDevicesClockAndZone() {
        val nine = at(2026, 11, 2, 21, 0, la)                  // 9 PM in Los Angeles: bedtime
        val anchor = KidRules.anchor(null, nine, 1_000)!!
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        // The kid moves the clock 12 hours on and the zone to Tokyo: still bedtime.
        assertEquals(bed, KidRules.quietOnDevice(listOf(bed), la, anchor, 61_000, nine + 12 * 3_600_000L, tokyo))
        // Ten hours later by the device's uptime it's 7 AM in Los Angeles: over, whatever the clock says.
        assertNull(KidRules.quietOnDevice(listOf(bed), la, anchor, 1_000 + 10 * 3_600_000L, nine, tokyo))
        // No trusted time yet (just restarted): the device's clock stands in, still in the parent's zone.
        assertEquals(bed, KidRules.quietOnDevice(listOf(bed), la, null, 5, nine, tokyo))
        assertNull(KidRules.quietOnDevice(listOf(bed), la, null, 5, at(2026, 11, 3, 9, 0, la), tokyo))
        // A config without a zone (an older parent's app): the device's own zone.
        assertEquals(bed, KidRules.quietAt(listOf(bed), at(2026, 11, 2, 21, 0, "Asia/Tokyo"), "", tokyo))
        assertEquals(bed, KidRules.quietAt(listOf(bed), at(2026, 11, 2, 21, 0, "Asia/Tokyo"), "Mars/Olympus", tokyo))
        assertNull(KidRules.quietAt(listOf(bed), at(2026, 11, 2, 12, 0, "Asia/Tokyo"), "Mars/Olympus", tokyo))
    }

    @Test fun aLaterTrustedTimeWinsAndAnOldOneChangesNothing() {
        val t = at(2026, 11, 2, 12, 0, "UTC")
        val a = KidRules.anchor(null, t, 1_000)!!
        assertEquals(t + 5_000, KidRules.trustedNow(a, 6_000))
        // A config signed an hour ago and resent late: no change.
        assertTrue(KidRules.anchor(a, t - 3_600_000, 2_000) === a)
        // A relay's clock a minute ahead of the estimate: taken.
        assertEquals(TimeAnchor(t + 61_000, 2_000), KidRules.anchor(a, t + 61_000, 2_000))
        // Nonsense (before 2024) is never an anchor.
        assertNull(KidRules.anchor(null, 1_000, 1_000))
        assertNull(KidRules.trustedNow(null, 1_000))
    }

    @Test fun theTimeZoneIsAWellFormedConfigField() {
        val c = cfg(30, listOf(dad), TvExtras(chat = 1, tz = la))
        assertEquals(la, TvRules.parse(c).tz)
        assertTrue(KidRules.hasK11(c))
        // Only with the other k11 fields: an older device never sees it.
        assertFalse(cfg(31, listOf(dad), TvExtras(tz = la)).has("tz"))
        for (bad in listOf("", "../etc/passwd", "America//LA", "America/", "1abc", "a b", "x".repeat(65))) malformed(JSONObject(c.toString()).put("tz", bad), "tz")
        malformed(JSONObject(c.toString()).put("tz", 5), "tz")
        assertEquals(la, KidRules.tzIn(la))
        assertNull(KidRules.zoneOf("Mars/Olympus"))
        assertNotNull(KidRules.zoneOf("Europe/London"))
    }

    @Test fun clockFollowsThe12Or24HourSetting() {
        assertEquals("20:00", KidRules.clock(20 * 60))
        assertEquals("8:00 PM", KidRules.clock(20 * 60, h24 = false))
        assertEquals("12:05 AM", KidRules.clock(5, h24 = false))
        assertEquals("12:30 PM", KidRules.clock(12 * 60 + 30, h24 = false))
    }

    // ---- the PIN hash goes only to parents

    @Test fun thePinHashIsNeverInAFrameForSomeoneWhoIsntAParent() {
        val p = KidPin(salt, "d7eb973c765dbd5bc56c68688684a18d9d1dfb52c4fbb44d128851477cf7246b", KidRules.PIN_ROUNDS)
        val c = cfg(40, listOf(dad, grandma), TvExtras(chat = 1, pin = p))
        assertTrue(c.has("pin"))
        val msg = TvRules.removedMessage(c)
        assertFalse(msg.contains(p.h))
        assertFalse(msg.contains(p.salt))
        assertFalse(JSONObject(msg).getJSONObject("cfg").has("pin"))
        assertTrue(c.has("pin"))   // the config itself is untouched
        // The phone it goes to still learns it was taken off, on its sender's word (the device, or a parent it knew).
        assertNotNull(TvRules.removedFrom(kTV, kB, null, 0, JSONObject(msg)))
        assertNotNull(TvRules.removedFrom(kTV, kB, listOf(kA, kB), 0, JSONObject(msg)))
        // A config without k11 fields still needs its signature.
        val plain = cfg(41, listOf(dad), null)
        val forged = JSONObject(TvRules.removedMessage(JSONObject(plain.toString()).put("dictation", true)))
        assertNull(TvRules.removedFrom(kTV, kB, null, 0, forged))
    }

    // ---- PBKDF2 and the PIN on the device

    @Test fun newPinsUse100kRoundsAndOldOnesStillOpen() {
        // Python: hashlib.pbkdf2_hmac("sha256", b"1234", salt, 100000, 32).
        assertEquals("d7eb973c765dbd5bc56c68688684a18d9d1dfb52c4fbb44d128851477cf7246b", KidRules.pinHash("1234", salt, 100_000))
        assertTrue(KidRules.pinOk("1234", KidPin(salt, "d7eb973c765dbd5bc56c68688684a18d9d1dfb52c4fbb44d128851477cf7246b", 100_000)))
        // A PIN set before "r": 20000 rounds, still opens until a parent sets a new one.
        val old = KidPin(salt, "1eeaf002a05b931ed2f5dd9fd99fc48b215f8e2dc3f300d356eb83bfe6a4adb4")
        assertEquals(KidRules.OLD_ROUNDS, old.r)
        assertTrue(KidRules.pinOk("1234", old))
        assertFalse(KidRules.pinOk("1235", old))
        assertFalse(JSONObject(KidRules.pinJson(old).toString()).has("r"))
        val p = KidRules.newPin("2580")
        assertTrue(KidRules.PIN_ROUNDS >= 100_000)
        assertEquals(KidRules.PIN_ROUNDS, p.r)
        assertTrue(KidRules.pinOk("2580", p))
        assertFalse(KidRules.pinOk("2581", p))
        assertEquals(p, KidRules.pinIn(JSONObject(KidRules.pinJson(p).toString())))
        val c = cfg(50, listOf(dad), TvExtras(chat = 1, pin = p))
        assertEquals(p, TvRules.parse(c).pin)
        fun withPin(o: JSONObject) = JSONObject(c.toString()).put("pin", o)
        malformed(withPin(JSONObject().put("salt", p.salt).put("h", p.h).put("r", 99_999)), "pin")
        malformed(withPin(JSONObject().put("salt", p.salt).put("h", p.h).put("r", "100000")), "pin")
        malformed(withPin(JSONObject().put("salt", p.salt).put("h", p.h).put("r", 2_000_000)), "pin")
        assertEquals(4, KidRules.PIN_MIN)
    }

    @Test fun wrongPinsWaitByUptimeAndAreForgotten() {
        val p = KidPin(salt, "1eeaf002a05b931ed2f5dd9fd99fc48b215f8e2dc3f300d356eb83bfe6a4adb4")   // "1234"
        val now = 100_000L
        var t = PinTries()
        repeat(4) {
            val (r, n) = KidRules.tryPin("9999", p, t, now)
            assertTrue(r.checked && !r.ok && r.wait == 0L)
            assertEquals("Wrong PIN", KidRules.pinText(r))
            t = n
        }
        val (r5, t5) = KidRules.tryPin("9999", p, t, now)
        assertEquals(30_000L, r5.wait)
        assertEquals("Wrong PIN. Wait 30 seconds, then try again.", KidRules.pinText(r5))
        // The right PIN during the wait isn't checked: it says to wait, never "Wrong PIN".
        val (rw, tw) = KidRules.tryPin("1234", p, t5, now + 10_000)
        assertFalse(rw.ok || rw.checked)
        assertEquals(t5, tw)
        assertEquals("Wait 20 seconds, then try again.", KidRules.pinText(rw))
        // After the wait the right PIN opens, and the wrong tries are forgotten.
        val (ok, tOk) = KidRules.tryPin("1234", p, t5, now + 30_000)
        assertTrue(ok.ok)
        assertEquals(PinTries(), tOk)
        // Fewer than 4 digits is no try at all.
        val (short, ts) = KidRules.tryPin("1", p, t5, now + 30_000)
        assertFalse(short.checked)
        assertEquals(t5, ts)
        assertEquals("Type at least 4 digits", KidRules.pinText(short))
        // An hour after the last wrong try, they're forgotten.
        val many = PinTries(12, now)
        assertEquals(15 * 60_000L, KidRules.waitLeft(many, now))
        assertEquals("Wait 15 minutes, then try again.", KidRules.waitText(KidRules.waitLeft(many, now)))
        assertEquals(0, KidRules.failsNow(many, now + KidRules.FAIL_RESET_MS))
        assertEquals(0L, KidRules.waitLeft(many, now + KidRules.FAIL_RESET_MS))
        val (afterHour, _) = KidRules.tryPin("9999", p, many, now + KidRules.FAIL_RESET_MS)
        assertEquals(0L, afterHour.wait)
        // An uptime before the last try (the device restarted): the whole wait, never none.
        assertEquals(30_000L, KidRules.waitLeft(PinTries(5, 50_000), 1_000))
        // Asking a parent's phone: once a minute.
        assertTrue(KidRules.askAgainOk(0, 5_000))
        assertFalse(KidRules.askAgainOk(5_000, 30_000))
        assertTrue(KidRules.askAgainOk(5_000, 65_000))
    }

    // ---- other Kids' Devices and groups

    @Test fun parentsCanAddAnotherKidsDevice() {
        val me = "me"
        // Another Kids' Device is a choice like anyone else; the device itself, this phone and group-only peers aren't.
        assertTrue(KidRules.canAdd("otherKidsTablet", kTV, me, groupOnly = false, reach = emptyList()))
        assertFalse(KidRules.canAdd(kTV, kTV, me, false, emptyList()))
        assertFalse(KidRules.canAdd(me, kTV, me, false, emptyList()))
        assertFalse(KidRules.canAdd("peer", kTV, me, true, emptyList()))
        assertFalse(KidRules.canAdd("already", kTV, me, false, listOf("already")))
        // Group pickers: a Kids' Device only for its own parents.
        assertTrue(KidRules.groupPickOk(isKidDevice = false, mine = false))
        assertTrue(KidRules.groupPickOk(isKidDevice = true, mine = true))
        assertFalse(KidRules.groupPickOk(isKidDevice = true, mine = false))
    }

    @Test fun groupsComeOnlyFromParentsAndQuietTimesHoldThem() {
        val parents = listOf(kA)
        assertTrue(KidRules.groupInviteOk(kA, parents))
        assertFalse(KidRules.groupInviteOk(kB, parents))
        for (room in KidRules.GROUP_ROOMS) {
            assertTrue(room, KidRules.groupRoomOk(kA, room, parents, inMyGroup = false, quiet = null))
            assertTrue(room, KidRules.groupRoomOk(kB, room, parents, inMyGroup = true, quiet = null))
            assertFalse(room, KidRules.groupRoomOk(kC, room, parents, inMyGroup = false, quiet = null))
            // During a quiet time only parents get through; receipts always.
            assertEquals(room, room == "greceipt", KidRules.groupRoomOk(kB, room, parents, inMyGroup = true, quiet = bed))
            assertTrue(room, KidRules.groupRoomOk(kA, room, parents, inMyGroup = false, quiet = bed))
        }
    }

    // ---- the parent's page: when the device was last heard from

    @Test fun lastSeenAndTheQuietDeviceNotice() {
        val now = 10 * 86_400_000L
        val time = { ms: Long -> "T$ms" }
        val day = { ms: Long -> "D$ms" }
        assertEquals("Not heard from yet", KidRules.lastSeen(0, now, time, day))
        assertEquals("Last seen just now", KidRules.lastSeen(now - 30_000, now, time, day))
        assertEquals("Last seen T${now - 3_600_000}", KidRules.lastSeen(now - 3_600_000, now, time, day))
        assertEquals("Last seen D${now - 3 * 86_400_000L}", KidRules.lastSeen(now - 3 * 86_400_000L, now, time, day))
        val seen = now - KidRules.SILENT_MS
        assertTrue(KidRules.silentNotice(seen, 0, now))
        assertFalse(KidRules.silentNotice(seen, now - 1, now))          // told once
        assertFalse(KidRules.silentNotice(now - 3_600_000, 0, now))    // heard from an hour ago
        assertFalse(KidRules.silentNotice(0, 0, now))                  // never heard from: nothing to compare
    }

    @Test fun defaultQuickMessagesStartWithAPicture() {
        assertEquals(KidRules.QUICK_ONLY_NAME, KidRules.levelName(KidRules.QUICK_ONLY))
        // Each default quick message starts with an emoji, so a child who can't read yet can tell them apart.
        for (q in TvRules.DEFAULT_QUICK) assertFalse(q, q[0].isLetterOrDigit())
    }
}
