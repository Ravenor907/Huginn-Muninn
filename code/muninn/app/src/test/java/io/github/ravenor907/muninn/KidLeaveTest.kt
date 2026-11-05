package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.call.CallSession.Phase
import io.github.ravenor907.muninn.tv.KidCallBack
import io.github.ravenor907.muninn.tv.KidK14
import io.github.ravenor907.muninn.tv.KidReset
import io.github.ravenor907.muninn.tv.KidRules
import io.github.ravenor907.muninn.tv.OwnerSettings
import io.github.ravenor907.muninn.tv.Quick
import io.github.ravenor907.muninn.tv.SafeStart
import io.github.ravenor907.muninn.tv.TvExtras
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import io.github.ravenor907.muninn.tv.TvUpd
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 14, Kids' Device: leaving kids' mode puts everything back, the call screen's Back, a TV's pairing code and remote,
 * honest "Sent", updates that fail for want of room, the crash-loop guard, quick message pictures through an older
 * co-parent's change, Send its code, and who may ask to add another family's Kids' Device.
 */
class KidLeaveTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kTV = keys.getString("TV")
    private val privA = ChatCrypto.privateFrom(chat.getString("privA"))
    private val people = listOf(TvPerson(kA, "Dad", "a".repeat(64)))
    private val apple = "🍎"

    private fun cfg(v: Long, extras: TvExtras? = null, quick: List<Quick> = emptyList()) =
        TvRules.config(privA, kTV, v, kA, listOf(kA), quick, people, false, 1761500000000 + v, extras)

    // ---- what kids' mode changed, put back

    @Test fun whatTheDeviceHadBeforeComesBackWhole() {
        val o = OwnerSettings(1.15f, 0.9f, true, "deutan", true, "black", "winter", "#112233", 86_400_000L, "Raven 4821", false, "HOUR", false)
        assertEquals(o, KidReset.ownerIn(KidReset.ownerJson(o)))
        // Nothing kept (a device paired before this was kept) or damaged: the defaults stand in.
        assertNull(KidReset.ownerIn(null))
        assertNull(KidReset.ownerIn(""))
        assertNull(KidReset.ownerIn("not json"))
        // A size out of reach is the usual size, never a huge one.
        val big = KidReset.ownerIn(JSONObject(KidReset.ownerJson(o)).put("text", 9.0).put("size", 0.1).toString())!!
        assertEquals(1f, big.text, 0f)
        assertEquals(1f, big.size, 0f)
        // Defaults: no deleting after N days, staying connected, visible nearby.
        val d = OwnerSettings()
        assertEquals(0L, d.deleteAfter)
        assertTrue(d.stay)
        assertTrue(d.nearby)
    }

    @Test fun aKidsDeviceNameNeverStaysAfterLeaving() {
        assertEquals("Raven 4821", KidReset.nameAfterLeave("Raven 4821", 1234))
        assertEquals("Ryan's tablet", KidReset.nameAfterLeave("  Ryan's tablet ", 1234))
        for (n in listOf("Kids' TV", "Kids' Tablet", "Kids' Phone", "", "   ")) assertEquals(n, "Raven 1234", KidReset.nameAfterLeave(n, 1234))
    }

    @Test fun firstSetupRunsAgainAfterLeaving() {
        // Every mark first setup's start check reads goes, the lock skip and the name ask included.
        assertEquals(setOf("setupStarted", "setupDone", "keepNotice", "lockSkipped", "nameAsked"), KidReset.SETUP_KEYS.toSet())
        assertEquals(setOf("taken", "accessTaken"), KidReset.LOOK_KEYS.toSet())
    }

    // ---- a call on a Kids' Device

    @Test fun backNeverHidesACallThatsOn() {
        assertEquals(KidCallBack.DECLINE, KidCallBack.of(Phase.INCOMING))
        assertEquals(KidCallBack.CLOSE, KidCallBack.of(null))
        assertEquals(KidCallBack.CLOSE, KidCallBack.of(Phase.ENDED))
        for (p in listOf(Phase.CALLING, Phase.RINGING, Phase.CONNECTING, Phase.LIVE, Phase.RECONNECTING)) {
            assertEquals(p.name, KidCallBack.HINT, KidCallBack.of(p))
            assertTrue(p.name, KidCallBack.live(p))
        }
        // Opening Muninn goes back to a ring too, never to one that's over.
        assertTrue(KidCallBack.live(Phase.INCOMING))
        assertFalse(KidCallBack.live(Phase.ENDED))
        assertFalse(KidCallBack.live(null))
    }

    // ---- the TV's pairing code and remote

    @Test fun thePairingCodeFollowsTheClockNotUptime() {
        val made = 1_761_500_000_000L
        assertFalse(KidRules.codeStale(made, made + 49 * 60_000L))
        assertTrue(KidRules.codeStale(made, made + 50 * 60_000L))
        // Overnight in standby: stale the moment it wakes.
        assertTrue(KidRules.codeStale(made, made + 9 * 3_600_000L))
        // The clock went back: made again.
        assertTrue(KidRules.codeStale(made, made - 1))
    }

    @Test fun theRemotesMicButtonStaysInMuninn() {
        assertTrue(KidRules.isMicKey(84))    // KEYCODE_SEARCH
        assertTrue(KidRules.isMicKey(219))   // KEYCODE_ASSIST
        assertTrue(KidRules.isMicKey(231))   // KEYCODE_VOICE_ASSIST
        for (k in listOf(4, 19, 20, 21, 22, 23, 66, 82, 85)) assertFalse("$k", KidRules.isMicKey(k))
    }

    @Test fun aQuickMessageNeverSaysSentBeforeItWent() {
        assertEquals("Sending…", KidK14.tileWord("Sending"))
        assertEquals("Sending…", KidK14.tileWord(""))
        assertEquals("Sent", KidK14.tileWord("Sent"))
        assertEquals("Not sent yet", KidK14.tileWord("Waiting"))
        // The state the tile reads: Sent only once a relay or a direct route took it.
        assertEquals("Sending", KidK14.sendState(true, "pending", false, false))
        assertEquals("Waiting", KidK14.sendState(true, "pending", true, false))
        assertEquals("Sent", KidK14.sendState(true, "sent", false, false))
    }

    // ---- updates

    @Test fun aFullDeviceSaysSoAndNeverStartsAnInstallItCantFinish() {
        assertTrue(TvUpd.roomFor(40_000_000L, 100_000_000L))
        assertFalse(TvUpd.roomFor(40_000_000L, 99_999_999L))
        assertTrue(TvUpd.roomFor(0L, 0L))
        assertTrue(TvUpd.isNoSpace("write failed: ENOSPC (No space left on device)"))
        assertTrue(TvUpd.isNoSpace("No space left on device"))
        assertFalse(TvUpd.isNoSpace("HTTP 404"))
        assertFalse(TvUpd.isNoSpace(null))
        assertEquals(TvUpd.FAILED to TvUpd.NO_SPACE, TvUpd.fromUpdater("Not enough space on this device for the update.", false, 0.4f))
        assertTrue(TvUpd.NO_SPACE.length <= TvUpd.MAX_WHY)
        // A frame with it is well-formed.
        val st = TvUpd.stateIn(JSONObject(TvUpd.stateMessage(TvUpd.St("1.36", TvUpd.FAILED, why = TvUpd.NO_SPACE, at = 5))))!!
        assertEquals(TvUpd.NO_SPACE, st.why)
    }

    @Test fun installingOrATapFromBeforeARestartIsNotKeptForever() {
        assertEquals(TvUpd.CURRENT, TvUpd.onStart(TvUpd.INSTALLING))
        assertEquals(TvUpd.CURRENT, TvUpd.onStart(TvUpd.TAP))
        assertEquals(TvUpd.CURRENT, TvUpd.onStart(TvUpd.DOWNLOADING))
        assertEquals(TvUpd.CURRENT, TvUpd.onStart(TvUpd.CHECKING))
        assertEquals(TvUpd.CURRENT, TvUpd.onStart(null))
        assertEquals(TvUpd.CURRENT, TvUpd.onStart("nonsense"))
        assertEquals(TvUpd.FAILED, TvUpd.onStart(TvUpd.FAILED))
    }

    @Test fun aReleaseThatKeepsCrashingStartsOnlyTheUpdater() {
        assertFalse(SafeStart.safe(0))
        assertFalse(SafeStart.safe(SafeStart.LIMIT - 1))
        assertTrue(SafeStart.safe(SafeStart.LIMIT))
        // The count holds only for the version that crashed: a newer one starts normally.
        assertEquals(3, SafeStart.carried(3, "1.35", "1.35"))
        assertEquals(0, SafeStart.carried(3, "1.35", "1.36"))
        assertEquals(0, SafeStart.carried(3, "", "1.35"))
        assertEquals(0, SafeStart.carried(-2, "1.35", "1.35"))
        // Only a crash within the first minute counts.
        assertTrue(SafeStart.counts(0L))
        assertTrue(SafeStart.counts(59_999L))
        assertFalse(SafeStart.counts(60_000L))
        assertFalse(SafeStart.counts(-1L))
    }

    @Test fun theSafeModeNeverTrapsADevice() {
        val h = 60 * 60_000L
        // Same install, same boot: the count carries.
        assertEquals(3, SafeStart.startFrom(3, "1.35@1", "1.35@1", 4, 4, 0L, 10L))
        // Installed again (same version, new install time): starts over.
        assertEquals(0, SafeStart.startFrom(3, "1.35@1", "1.35@2", 4, 4, 0L, 10L))
        // The device restarted: starts over. An unknown boot count keeps the count.
        assertEquals(0, SafeStart.startFrom(3, "1.35@1", "1.35@1", 4, 5, 5L, 10L))
        assertEquals(3, SafeStart.startFrom(3, "1.35@1", "1.35@1", -1, 5, 0L, 10L))
        // In the safe mode for TRIAL_MS: one normal try, with one crash away from the safe mode again.
        assertEquals(SafeStart.LIMIT - 1, SafeStart.startFrom(3, "v", "v", 1, 1, 1_000L, 1_000L + SafeStart.TRIAL_MS))
        assertEquals(3, SafeStart.startFrom(3, "v", "v", 1, 1, 1_000L, 1_000L + SafeStart.TRIAL_MS - 1))
        assertFalse(SafeStart.safe(SafeStart.LIMIT - 1))
        assertTrue(SafeStart.safe(SafeStart.LIMIT - 1 + 1))
        // A clock set back counts as due; no time kept is never due.
        assertTrue(SafeStart.trialDue(10 * h, 5 * h))
        assertFalse(SafeStart.trialDue(0L, 100 * h))
        // A few minutes, not seconds, of running normally clears the count.
        assertTrue(SafeStart.HEALTHY_MS >= 2 * 60_000L && SafeStart.HEALTHY_MS <= 10 * 60_000L)
        assertTrue(SafeStart.TRIAL_MS <= 6 * h)
    }

    @Test fun aCallOverAnotherAppSaysWhoIsCalling() {
        assertEquals("Mom is calling. Open Muninn to answer.", KidCallBack.overText("Mom"))
        assertEquals("Someone is calling. Open Muninn to answer.", KidCallBack.overText(" "))
        assertTrue(KidCallBack.overDue(stillRinging = true, visible = false, overlay = false))
        assertFalse(KidCallBack.overDue(stillRinging = false, visible = false, overlay = false))
        assertFalse(KidCallBack.overDue(stillRinging = true, visible = true, overlay = false))
        assertFalse(KidCallBack.overDue(stillRinging = true, visible = false, overlay = true))
        // A kids' phone goes back to the call on opening until the kid dismisses its bar for that call; a TV always does.
        assertTrue(KidCallBack.jumpBack(live = true, tv = false, callId = "c1", hidden = ""))
        assertFalse(KidCallBack.jumpBack(live = true, tv = false, callId = "c1", hidden = "c1"))
        assertTrue(KidCallBack.jumpBack(live = true, tv = false, callId = "c2", hidden = "c1"))
        assertTrue(KidCallBack.jumpBack(live = true, tv = true, callId = "c1", hidden = "c1"))
        assertFalse(KidCallBack.jumpBack(live = false, tv = true, callId = "c1", hidden = ""))
        // The parent's page says plainly what Android allows while Show over other apps is off.
        val note = io.github.ravenor907.muninn.tv.overNote("kids' TV")
        assertTrue(note.contains("Calls can't come up over other apps on this kids' TV") && note.contains("open Muninn to answer"))
    }

    // ---- quick message pictures through an older co-parent's change

    @Test fun anOlderCoParentsChangeKeepsThePictures() {
        val id = TvRules.quickId()
        val q = Quick(id, "I'm hungry", apple)
        val cur = TvRules.parse(cfg(1, TvExtras(chat = 2, gok = emptyList(), pics = true), quick = listOf(q)))
        assertEquals(apple, cur.quick.single().e)
        // 1.34 (no k14 field, so no pictures) changes anything: each quick message keeps its picture.
        val older = TvRules.parse(cfg(2, quick = listOf(Quick(id, "I'm hungry"), Quick(TvRules.quickId(), "Hi"))))
        val kept = KidRules.keepK11(older, cur)
        assertEquals(apple, kept.quick.first { it.id == id }.e)
        assertEquals("", kept.quick.first { it.id != id }.e)
        // A restart keeps them too.
        val restarted = KidRules.keptIn(TvRules.parse(older.raw), JSONObject(KidRules.keptJson(kept).toString()))
        assertEquals(apple, restarted.quick.first { it.id == id }.e)
        // A parent app with k14 that took the picture off: off.
        val newer = TvRules.parse(cfg(3, TvExtras(chat = 2, gok = emptyList(), pics = true), quick = listOf(Quick(id, "I'm hungry"))))
        assertEquals("", KidRules.keepK11(newer, kept).quick.single().e)
        // The parent's phone the same way.
        assertEquals(apple, KidK14.keepPics(listOf(Quick(id, "I'm hungry")), listOf(q), k14 = false).single().e)
        assertEquals("", KidK14.keepPics(listOf(Quick(id, "I'm hungry")), listOf(q), k14 = true).single().e)
    }

    // ---- other families' Kids' Devices

    @Test fun sendItsCodeSaysItsAKidsDeviceAndWhoseParent() {
        val body = JSONObject().put("n", "Ava's tablet").put("k", kTV).put("p", "b".repeat(64)).toString()
        val code = "MUNINN1:" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray())
        val shared = KidK14.kidCode(code, kA)
        assertEquals(kTV to kA, KidK14.kidCodeIn("Add our Kids' Device in Muninn so it can reach you: $shared"))
        // An ordinary code, and a pairing code, are neither.
        assertNull(KidK14.kidCodeIn(code))
        val pairing = "MUNINN1:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(JSONObject(body).put("tv", 1).put("kid", 1).put("by", kA).toString().toByteArray())
        assertNull(KidK14.kidCodeIn(pairing))
        // It still reads as the device's own code (name, key, mailbox).
        val o = JSONObject(String(java.util.Base64.getUrlDecoder().decode(shared.removePrefix("MUNINN1:"))))
        assertEquals(kTV, o.getString("k"))
        assertEquals("Ava's tablet", o.getString("n"))
    }

    @Test fun onlyItsOwnParentAsksToAddAKidsDevice() {
        val me = "me"; val bob = "bob"; val leo = "leo"; val tv = "ava"
        // Never yet seen here: asked about, in honest words.
        assertTrue(KidK14.kidAddOk(leo, bob, me, tv, mine = false, contact = false, knownKid = false, handedBy = null))
        // Handed over by that same parent: asked about.
        assertTrue(KidK14.kidAddOk(leo, bob, me, tv, mine = false, contact = true, knownKid = true, handedBy = bob))
        // Someone already here who isn't a Kids' Device can't be called one.
        assertFalse(KidK14.kidAddOk("carol", bob, me, tv, mine = false, contact = true, knownKid = false, handedBy = null))
        // Another parent handed it over and a co-parent asks: still asked about, naming who shared it first.
        assertTrue(KidK14.kidAddOk(leo, bob, me, tv, mine = false, contact = true, knownKid = true, handedBy = "leosmom"))
        assertEquals("leosmom", KidK14.sharedFirstBy("leosmom", bob))
        assertNull(KidK14.sharedFirstBy(bob, bob))
        assertNull(KidK14.sharedFirstBy(null, bob))
        val co = KidK14.kidAddText("Dad", "Leo", "Ava", new = false, firstBy = "Mom")
        assertTrue(co.contains("Mom shared this device with you first; Dad says they're also its parent."))
        assertFalse(KidK14.kidAddText("Dad", "Leo", "Ava", new = false).contains("shared this device"))
        // Never this phone, the sender, the device itself or one of this phone's own.
        assertFalse(KidK14.kidAddOk(me, bob, me, tv, mine = false, contact = false, knownKid = false, handedBy = null))
        assertFalse(KidK14.kidAddOk(bob, bob, me, tv, mine = false, contact = true, knownKid = false, handedBy = null))
        assertFalse(KidK14.kidAddOk(tv, bob, me, tv, mine = true, contact = true, knownKid = true, handedBy = null))
        assertFalse(KidK14.kidAddOk("mine2", bob, me, tv, mine = true, contact = true, knownKid = true, handedBy = null))
        val t = KidK14.kidAddText("Bob", "Leo", "Ava", new = true)
        assertTrue(t.contains("Bob says"))
        assertTrue(t.contains("Only add it if you trust Bob"))
        assertTrue(t.contains("new to you"))
        assertFalse(KidK14.kidAddText("Bob", "Leo", "Ava", new = false).contains("new to you"))
    }

    // ---- the largest sizes

    @Test fun atTheLargestSizesTheNameAndTheBoxGetTheirOwnRows() {
        // A 360 dp phone at the usual sizes: as before.
        assertFalse(KidK14.nameOwnLine(360f, back = true, call = true))
        assertFalse(KidK14.boxOwnRow(360f, 2, 1.1f))
        // The pane about 208 dp wide at Android's Largest: the name under the bar, the box above the buttons.
        assertTrue(KidK14.nameOwnLine(208f, back = true, call = true))
        assertTrue(KidK14.boxOwnRow(208f, 2, 3.3f))
        assertTrue(KidK14.boxOwnRow(208f, 1, 1f))
        // A tablet's wide pane keeps one row even at a big text size.
        assertFalse(KidK14.boxOwnRow(700f, 2, 2f))
        assertEquals("3 waiting to send", KidK14.heldSummary(3))
        assertEquals(1.5f, KidRules.PAD_FONT_MAX, 0f)
    }
}
