package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.tv.Held
import io.github.ravenor907.muninn.tv.HoldAsk
import io.github.ravenor907.muninn.tv.KidAccess
import io.github.ravenor907.muninn.tv.KidAdd
import io.github.ravenor907.muninn.tv.KidK14
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.KidUi
import io.github.ravenor907.muninn.tv.Quick
import io.github.ravenor907.muninn.tv.QuietTime
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cap k14 (shared/docs/protocol.md 7.18) and the rules the kids' screens on a tablet or phone follow: the tray's size, when
 * the composer folds, the greeting, held group members, what a quiet time holds, and the config fields kept when absent.
 */
class KidK14Test {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kTV = keys.getString("TV")
    private val privA = ChatCrypto.privateFrom(chat.getString("privA"))
    private val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)))

    private fun cfg(v: Long, extras: TvExtras? = null, quick: List<Quick> = emptyList()) =
        TvRules.config(privA, kTV, v, kA, listOf(kA), quick, people, false, 1761500000000 + v, extras)

    // ---- the layout

    @Test fun theTrayTakesAShareOfTheHeightNeverAFixedSize() {
        // A phone upright: 40% of the conversation.
        assertEquals(256f, KidK14.trayMax(640f), 0.01f)
        // A phone held sideways (about 330 dp): 40%, so the messages keep the rest (no more 360 dp tray leaving 0 for them).
        val side = KidK14.trayMax(330f)
        assertEquals(132f, side, 0.01f)
        assertTrue(330f - side >= KidK14.MIN_LIST)
        // A 7-inch tablet sideways and a 10-inch one: still a share.
        assertEquals(211.2f, KidK14.trayMax(528f), 0.01f)
        assertEquals(291.2f, KidK14.trayMax(728f), 0.01f)
        // Short: at least 120 dp while the messages keep 120; shorter still, half each.
        assertEquals(120f, KidK14.trayMax(260f), 0.01f)
        assertEquals(100f, KidK14.trayMax(200f), 0.01f)
        for (h in listOf(100f, 150f, 240f, 300f, 330f, 500f, 900f)) assertTrue("$h", KidK14.trayMax(h) <= h)
        // Sideways on a phone: one scrolling row of chips. A tablet, or a phone upright, wraps them.
        assertTrue(KidK14.trayOneRow(640f, 360f, tablet = false))
        assertFalse(KidK14.trayOneRow(360f, 640f, tablet = false))
        assertFalse(KidK14.trayOneRow(1280f, 800f, tablet = true))
        // At Quick messages only the tray is the composer; with typing it closes while the kid types (focus or keyboard).
        assertTrue(KidK14.trayShown(quickOnly = true, opened = false, typing = true))
        assertTrue(KidK14.trayShown(quickOnly = false, opened = true, typing = false))
        assertFalse(KidK14.trayShown(quickOnly = false, opened = true, typing = true))
        assertFalse(KidK14.trayShown(quickOnly = false, opened = false, typing = false))
    }

    @Test fun theComposerFoldsItsButtonsWhenTheBoxGetsNarrow() {
        // 360 dp with the smile and photo: 128 dp to type in, under 160: they fold into one button, leaving 184.
        assertEquals(128f, KidK14.typingWidth(360f, 2), 0.01f)
        assertTrue(KidK14.collapse(360f, 2))
        assertTrue(KidK14.typingWidth(360f, 1) >= KidK14.MIN_TYPING)
        // Display size Largest (about 300 dp): folds too.
        assertTrue(KidK14.collapse(300f, 2))
        // A tablet's pane, or a phone held sideways: room enough.
        assertFalse(KidK14.collapse(640f, 2))
        assertFalse(KidK14.collapse(420f, 2))
        // One button (only the smile, or only the photo) never folds: More would be as wide.
        assertFalse(KidK14.collapse(300f, 1))
        assertFalse(KidK14.collapse(200f, 0))
    }

    @Test fun callIsOnlyItsIconAtALargeTextSize() {
        // The kids' text is 1.1 times Android's: at Android's 100% the 360 dp phone keeps the word "Call".
        assertFalse(KidK14.callIconOnly(360f, 1.1f, back = true))
        // At 200% (2.2 with the kids' boost), and at 150% and over, the icon alone, so the name keeps its room.
        assertTrue(KidK14.callIconOnly(360f, 2.2f, back = true))
        assertTrue(KidK14.callIconOnly(800f, 1.5f, back = false))
        // Display size Largest at 130%: the name would get under 96 dp beside the whole button.
        assertTrue(KidK14.callIconOnly(300f, 1.43f, back = true))
        // A tablet's pane (no Back): the word stays.
        assertFalse(KidK14.callIconOnly(560f, 1.1f, back = false))
    }

    // ---- the greeting

    @Test fun theGreetingUsesTheChildsNameNeverTheDevicesName() {
        // A parent app before k14 sent the device's name as "kname": the greeting doesn't use it.
        val old = TvRules.parse(cfg(1, TvExtras(chat = 2, kname = "Ava's tablet")))
        assertEquals("", old.child)
        assertEquals("Hi!", KidUi.greeting(old.child))
        // The child's name set on the device page.
        val set = TvRules.parse(cfg(2, TvExtras(chat = 2, child = "  Ava ")))
        assertEquals("Ava", set.child)
        assertEquals("Hi, Ava", KidUi.greeting(set.child))
        assertEquals("Hi, Ava", KidK14.greeting(set.child))
        // A blank one isn't sent; without one, just "Hi!".
        assertFalse(cfg(3, TvExtras(chat = 2, child = "   ")).has("child"))
        assertEquals("Hi!", KidUi.greeting(""))
        // The latest config wins: another parent's change replaces it; one without "child" keeps it.
        val next = KidRules.keepK11(TvRules.parse(cfg(4, TvExtras(chat = 2, child = "Max"))), set)
        assertEquals("Max", next.child)
        assertEquals("Max", KidRules.keepK11(TvRules.parse(cfg(5, TvExtras(chat = 2))), next).child)
    }

    // ---- groups

    @Test fun groupMembersNoParentApprovedAreHeld() {
        val me = "me"
        val parents = listOf("mom", "dad")
        val contacts = listOf("mom", "dad", "gran")
        val gok = listOf("g1" to "sam")
        // The device itself, its parents and everyone on its list get through.
        for (k in listOf(me, "mom", "dad", "gran")) assertFalse(k, KidK14.held(k, "g1", me, parents, contacts, gok))
        // Someone a group admin added: held.
        assertTrue(KidK14.held("riley", "g1", me, parents, contacts, gok))
        // Approved for one group only.
        assertFalse(KidK14.held("sam", "g1", me, parents, contacts, gok))
        assertTrue(KidK14.held("sam", "g2", me, parents, contacts, gok))
        // The ask to the parents, and their words.
        val a = HoldAsk("AbC_d-1", "riley-key", "Riley", "Soccer")
        assertEquals(a, KidK14.holdIn(JSONObject(KidK14.holdMessage(a))))
        assertNull(KidK14.holdIn(JSONObject(KidK14.holdMessage(a)).put("x", 1)))
        assertNull(KidK14.holdIn(JSONObject(KidK14.holdMessage(a)).put("g", "a b")))
        assertNull(KidK14.holdIn(JSONObject(KidK14.holdMessage(a)).put("k", "")))
        assertEquals("Approve Riley in Soccer for Ava?", KidK14.approveText("Riley", "Soccer", "Ava"))
    }

    @Test fun anotherFamilysDeviceWaitsUntilItsParentAddsThisOne() {
        val a = KidAdd("leo-tablet", "ava-tablet", "Ava", "MUNINN1:abc")
        assertEquals(a, KidK14.kidAddIn(JSONObject(KidK14.kidAddMessage(a))))
        assertNull(KidK14.kidAddIn(JSONObject(KidK14.kidAddMessage(a)).put("tv", "ava-tablet")))
        assertNull(KidK14.kidAddIn(JSONObject(KidK14.kidAddMessage(a)).put("code", "")))
        assertEquals("leo-tablet" to "ava-tablet", KidK14.kidOkIn(JSONObject(KidK14.kidOkMessage("leo-tablet", "ava-tablet"))))
        assertNull(KidK14.kidOkIn(JSONObject().put("t", "kidok").put("tv", "x")))
        assertEquals("Waiting for Leo's parent to add Ava", KidK14.waitingText("Leo", "Ava"))
        // On the device: its messages there wait, whatever a relay said.
        val c = TvRules.parse(cfg(10, TvExtras(chat = 2, wait = listOf("zz", "aa", "aa"))))
        assertEquals(listOf("aa", "zz"), c.wait)
        assertEquals("Waiting", KidK14.sendState(true, "sent", false, waiting = true))
    }

    // ---- what a sent message says, and TalkBack

    @Test fun sentOnlyOnceARelayOrADirectRouteTookIt() {
        assertEquals("Sending", KidK14.sendState(true, "sending", false, false))
        assertEquals("Waiting", KidK14.sendState(true, "sending", true, false))
        for (s in listOf("sent", "delivered", "read")) assertEquals(s, "Sent", KidK14.sendState(true, s, false, false))
        assertEquals("", KidK14.sendState(false, "read", false, false))
        assertEquals("", KidK14.sendState(true, "", false, false))
        // A burst of taps on different chips sends one a moment.
        assertTrue(KidK14.chipOk(0L, 5_000L))
        assertFalse(KidK14.chipOk(5_000L, 5_900L))
        assertTrue(KidK14.chipOk(5_000L, 6_500L))
    }

    @Test fun everyBubbleSaysWhoWhatAndWhen() {
        assertEquals("Mom: I'm on my way, 4:12 PM", KidK14.bubbleLabel("Mom", false, "I'm on my way", 0, 0, false, false, false, null, "4:12 PM"))
        assertEquals("You: Photo, 4:12 PM", KidK14.bubbleLabel("Ava", true, "", 0, 0, true, false, false, null, "4:12 PM"))
        assertEquals("Dad: Voice message 0:12, 9:00 AM", KidK14.bubbleLabel("Dad", false, "", 12_000, 0, true, false, false, null, "9:00 AM"))
        assertEquals("Gran: Video 1:05, 9:00 AM", KidK14.bubbleLabel("Gran", false, "", 0, 65_000, true, false, false, null, "9:00 AM"))
        // A group's text that starts with the sender's name doesn't say it twice.
        assertEquals("Sam: hi, 9:00 AM", KidK14.bubbleLabel("Sam", false, "Sam: hi", 0, 0, false, false, false, null, "9:00 AM"))
        for (l in listOf(KidK14.HELD_LINE, KidK14.WAIT_LINE, KidK14.waitingText("Leo", "Ava")))
            assertFalse(l, l.contains('\u2014') || l.contains('\u2013'))
    }

    @Test fun aQuickMessageShowsItsPicture() {
        assertEquals("🍎" to "I'm hungry", KidK14.picture(Quick("i", "🍎 I'm hungry")))
        assertEquals("❤️" to "I love you", KidK14.picture(Quick("i", "❤️ I love you")))
        // The parent's pick wins, and the words never repeat the text's own emoji.
        assertEquals("🚗" to "Pick me up", KidK14.picture(Quick("i", "🍎 Pick me up", "🚗")))
        assertEquals("" to "Home time", KidK14.picture(Quick("i", "Home time")))
        assertEquals("Pick me up", KidK14.spoken(Quick("i", "🍎 Pick me up")))
        assertFalse(KidK14.emojiOk("A"))
        assertFalse(KidK14.emojiOk(""))
        assertFalse(KidK14.emojiOk("🍎 🍎"))
        // "e" goes only to a device with k14 (pics), and comes back; a device without it would refuse it.
        val q = listOf(Quick(TvRules.quickId(), "Hungry", "🍎"))
        val with = cfg(20, TvExtras(chat = 2, pics = true), q)
        TvRules.check(with)
        assertEquals("🍎", TvRules.parse(with).quick.first().e)
        val without = cfg(21, TvExtras(chat = 2), q)
        assertFalse(without.getJSONArray("quick").getJSONObject(0).has("e"))
        assertEquals("", TvRules.parse(without).quick.first().e)
        // A malformed "e" is refused.
        val bad = JSONObject(with.toString())
        bad.getJSONArray("quick").getJSONObject(0).put("e", "abc")
        try { TvRules.check(bad); fail("quick fields") } catch (e: IllegalArgumentException) { assertEquals("quick fields", e.message) }
    }

    // ---- quiet times: a draft stays, a clip or photo is held

    @Test fun aQuietTimeHoldsAndThenSends() {
        val bed = QuietTime(KidRules.BED, KidRules.ALL_DAYS, 20 * 60, 7 * 60)
        val parents = listOf("mom")
        assertTrue(KidK14.holds("gran", parents, bed))
        assertTrue(KidK14.holds("g:x", parents, bed))
        assertFalse(KidK14.holds("mom", parents, bed))
        assertFalse(KidK14.holds("gran", parents, null))
        assertEquals("Will send at 7:00 AM", KidK14.willSend(bed, h24 = false))
        assertEquals("Will send at 07:00", KidK14.willSend(bed, h24 = true))
        val id1 = "a".repeat(32)
        val id2 = "b".repeat(32)
        var list = KidK14.addHeld(emptyList(), Held(id1, "gran", "voice", 4_000, 1L))
        list = KidK14.addHeld(list, Held(id2, "leo", "photo", 0, 2L))
        // Still bedtime: both wait. Over: Gran's goes; Leo was taken off the list, so his is dropped.
        val (now, keep) = KidK14.release(list, parents, bed) { it != "leo" }
        assertTrue(now.isEmpty())
        assertEquals(listOf(id1), keep.map { it.id })
        val (send, left) = KidK14.release(list, parents, null) { it != "leo" }
        assertEquals(listOf(id1), send.map { it.id })
        assertTrue(left.isEmpty())
        // Don't send: gone.
        assertEquals(listOf(id2), KidK14.cancelHeld(list, id1).map { it.id })
        // At most ten wait; saved and read back, a damaged entry skipped.
        var many = emptyList<Held>()
        repeat(12) { i -> many = KidK14.addHeld(many, Held("%032x".format(i), "gran", "voice", 1, i.toLong())) }
        assertEquals(KidK14.MAX_HELD, many.size)
        val json = KidK14.heldJson(list).put(JSONObject().put("id", "x").put("k", "gran").put("kind", "voice"))
        assertEquals(list, KidK14.heldIn(JSONArray(json.toString())))
    }

    // ---- the config fields

    @Test fun theK14FieldsRoundTripAndAreGated() {
        assertTrue(KidK14.CAP in Proto06.caps(Proto06.CAPS))
        val access = KidAccess(text = 150, size = 115, hc = true, cv = "deutan", still = true)
        val c = cfg(30, TvExtras(chat = 2, access = access, child = "Ava", gok = listOf("g2" to "b", "g1" to "a"), wait = listOf("x")))
        TvRules.check(c)
        val p = TvRules.parse(c)
        assertEquals(access, p.access)
        assertEquals("Ava", p.child)
        assertEquals(listOf("g1" to "a", "g2" to "b"), p.gok)
        assertEquals(listOf("x"), p.wait)
        // A device without k14 never gets them (null: left out).
        val older = cfg(31, TvExtras(chat = 2))
        for (f in KidK14.FIELDS) assertFalse(f, older.has(f))
        // Only the values Settings › Look has.
        assertFalse(cfg(32, TvExtras(chat = 2, access = KidAccess(text = 120, size = 50, cv = "pink"))).let { it.has("tsz") || it.has("dsz") || it.has("cv") })
        fun bad(o: JSONObject, why: String) = try { KidK14.check(o); fail(why) } catch (e: IllegalArgumentException) { assertEquals(why, e.message) }
        bad(JSONObject().put("tsz", 120), "tsz")
        bad(JSONObject().put("dsz", "100"), "dsz")
        bad(JSONObject().put("hc", 1), "hc")
        bad(JSONObject().put("cv", "Deutan"), "cv")
        bad(JSONObject().put("still", "yes"), "still")
        bad(JSONObject().put("child", " "), "child")
        bad(JSONObject().put("gok", JSONArray().put(JSONObject().put("g", "b").put("k", "x")).put(JSONObject().put("g", "a").put("k", "x"))), "gok")
        bad(JSONObject().put("gok", JSONArray().put(JSONObject().put("g", "a").put("k", "x").put("n", 1))), "gok")
        bad(JSONObject().put("wait", JSONArray().put("b").put("a")), "wait")
        bad(JSONObject().put("wait", JSONArray().put("a b")), "wait")
    }

    @Test fun anAbsentK14FieldKeepsTheDevicesValue() {
        val cur = TvRules.parse(cfg(40, TvExtras(chat = 2, access = KidAccess(text = 130, hc = true, cv = "mono", still = false, size = 85),
            child = "Ava", gok = listOf("g1" to "sam"), wait = listOf("leo"))))
        // An older parent app (no k11 field), or one without k14: all of them stay.
        for (new in listOf(TvRules.parse(cfg(41, TvExtras(calls = false))), TvRules.parse(cfg(42, TvExtras(chat = 2))))) {
            val k = KidRules.keepK11(new, cur)
            assertEquals(cur.access, k.access)
            assertEquals("Ava", k.child)
            assertEquals(listOf("g1" to "sam"), k.gok)
            assertEquals(listOf("leo"), k.wait)
        }
        // Each on its own: only "tsz" and an empty "gok" sent.
        val one = KidRules.keepK11(TvRules.parse(cfg(43, TvExtras(chat = 2, access = KidAccess(text = 100), gok = emptyList()))), cur)
        assertEquals(KidAccess(text = 100, size = 85, hc = true, cv = "mono", still = false), one.access)
        assertTrue(one.gok.isEmpty())
        assertEquals(listOf("leo"), one.wait)
        // Kept beside the signed config, so a restart keeps them too; a damaged copy changes nothing.
        val older = TvRules.parse(cfg(44, TvExtras(chat = 2)))
        assertTrue(KidRules.needsKept(older.raw))
        val kept = KidRules.keepK11(older, cur)
        val saved = JSONObject(KidRules.keptJson(kept).toString())
        val restarted = KidRules.keptIn(TvRules.parse(older.raw), saved)
        assertEquals(cur.access, restarted.access)
        assertEquals("Ava", restarted.child)
        assertEquals(listOf("g1" to "sam"), restarted.gok)
        assertEquals(listOf("leo"), restarted.wait)
        val damaged = KidRules.keptIn(older, JSONObject().put("tsz", 99).put("cv", 3).put("child", "").put("gok", "x").put("wait", JSONArray().put("b").put("a")))
        assertEquals(KidAccess(), damaged.access)
        assertEquals("", damaged.child)
        assertTrue(damaged.gok.isEmpty() && damaged.wait.isEmpty())
        // The accessibility goes on when it's new to the device, or always while the kid may not pick; never when none is set.
        val a = KidAccess(text = 150)
        assertEquals(a, KidK14.accessToApply(a, "", lookPick = true))
        assertNull(KidK14.accessToApply(a, a.key, lookPick = true))
        assertEquals(a, KidK14.accessToApply(a, a.key, lookPick = false))
        assertNull(KidK14.accessToApply(KidAccess(), "", lookPick = false))
    }
}
