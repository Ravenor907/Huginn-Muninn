package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.LockStore
import io.github.ravenor907.muninn.net.LockWait
import io.github.ravenor907.muninn.net.VaultCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 1.42 lock rules: the wrong-try count, its waits, the erase switch, the lock-state file, and the unlock password. */
class LockRulesTest {
    private lateinit var dir: File
    private val min = 60_000L
    private val hour = 60 * min

    /** A clock the test moves: time since start-up and the start-up count. */
    private class TestClock(var now: Long = 1_000L, var boot: Int = 1, var wall: Long = 1_700_000_000_000L) : LockStore.Clock {
        override fun now() = now
        override fun boot() = boot
        override fun wall() = wall
    }

    /** Muninn's settings, as the second place the count is kept. */
    private class TestMirror : LockStore.Mirror {
        var data: LockStore.Data? = null
        override fun read() = data
        override fun write(d: LockStore.Data) { data = d }
    }

    @Before fun setUp() { dir = Files.createTempDirectory("lockrules").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun store(clock: TestClock, mirror: LockStore.Mirror? = null) = LockStore(File(dir, "lock-state"), clock, mirror)

    /** [n] wrong tries in a row, each counted before its check and found wrong; waits are let run out between. */
    private fun wrong(s: LockStore, c: TestClock, n: Int, erase: Boolean = false): Int {
        var last = 0
        repeat(n) {
            val l = s.left()
            c.now += l; c.wall += l
            last = s.begin()!!.fails
            s.wrong()
            if (LockWait.erases(last, erase)) return last
        }
        return last
    }

    // ------------------------------------------------------------------ the steps

    @Test fun waitsAreFiveMinutesOneHourTwelveHoursThenTwentyFourHours() {
        for (i in 1..4) assertEquals(0L, LockWait.after(i))
        assertEquals(5 * min, LockWait.after(5))
        for (i in 6..9) assertEquals(0L, LockWait.after(i))
        assertEquals(hour, LockWait.after(10))
        assertEquals(12 * hour, LockWait.after(15))
        assertEquals(24 * hour, LockWait.after(20))
        // Erase off: 24 hours again after every 5 more, for ever.
        assertEquals(24 * hour, LockWait.after(25))
        assertEquals(24 * hour, LockWait.after(30))
        assertEquals(24 * hour, LockWait.after(500))
        assertFalse(LockWait.erases(25, erase = false))
        assertFalse(LockWait.erases(1000, erase = false))
    }

    @Test fun eraseOnErasesFiveTriesAfterTheTwentyFourHourWait() {
        assertEquals(24 * hour, LockWait.after(20, erase = true))
        assertFalse(LockWait.erases(24, erase = true))
        assertTrue(LockWait.erases(25, erase = true))
        assertEquals(0L, LockWait.after(25, erase = true))   // erased, no wait
        assertEquals(0, LockWait.lastLeft(19, erase = true))
        assertEquals(5, LockWait.lastLeft(20, erase = true))
        assertEquals(1, LockWait.lastLeft(24, erase = true))
        assertEquals(0, LockWait.lastLeft(22, erase = false))
    }

    @Test fun triesInsideTheAppNeverCount() {
        // Round 19 (owner rule): prompts inside the open app never count toward a wait or the erase.
        val c = TestClock()
        val s = store(c)
        repeat(4) { val b = s.begin(inApp = true)!!; assertFalse(b.lockApp); assertTrue(b.inApp) }
        assertEquals(0, s.fails())
        assertEquals(0L, s.left())
        // The fifth wrong in a row locks Muninn (the lock screen takes over); still nothing on disk.
        assertTrue(s.begin(inApp = true)!!.lockApp)
        assertEquals(0, s.fails())
        // A right one starts the in-app count again.
        s.begin(inApp = true); s.right()
        repeat(4) { assertFalse(s.begin(inApp = true)!!.lockApp) }
    }

    @Test fun screensSpellOutEachStepInPlainWords() {
        assertEquals("Wait 5 minutes. Next: 1 hour after 5 more wrong tries.", LockWait.waitText(5, false))
        assertEquals("Wait 1 hour. Next: 12 hours after 5 more wrong tries.", LockWait.waitText(10, false))
        assertEquals("Wait 12 hours. Next: 24 hours after 5 more wrong tries.", LockWait.waitText(15, false))
        assertEquals("Wait 24 hours. Next: 24 hours again after 5 more wrong tries.", LockWait.waitText(20, false))
        assertEquals("Wait 24 hours. Next: 24 hours again after 5 more wrong tries.", LockWait.waitText(45, false))
        assertEquals("Wait 24 hours. Next: the last 5 tries. If all 5 are wrong, everything is erased for good.", LockWait.waitText(20, true))
        assertEquals("Last 5 tries. If all 5 are wrong, everything is erased for good.", LockWait.triesText(20, true))
        assertEquals("Last 3 tries. If all 3 are wrong, everything is erased for good.", LockWait.triesText(22, true))
        assertEquals("Last try. If it's wrong, everything is erased for good.", LockWait.triesText(24, true))
        assertEquals("", LockWait.triesText(0, false))
        assertEquals("4 more wrong tries, then a wait of 5 minutes.", LockWait.triesText(1, false))
        assertEquals("1 more wrong try, then a wait of 1 hour.", LockWait.triesText(9, false))
        assertEquals("5 more wrong tries, then a wait of 24 hours.", LockWait.triesText(20, false))
        // Never a total like "25".
        for (f in 0..60) for (e in listOf(false, true)) {
            assertFalse(LockWait.triesText(f, e).contains("25"))
            if (f > 0 && f % 5 == 0 && !LockWait.erases(f, e)) assertFalse(LockWait.waitText(f, e).contains("25"))
        }
        assertEquals("Try again in 59 minutes.", LockWait.leftText(58 * min + 1))
        assertEquals("Try again in 23 hours 59 minutes.", LockWait.leftText(24 * hour - 90_000))
        assertEquals("Try again in 1 hour.", LockWait.leftText(hour))
        assertEquals("Try again in 40 seconds.", LockWait.leftText(39_500))
    }

    // ------------------------------------------------------------------ the lock-state file

    @Test fun countsAndWaitsFollowTheStepsAndRepeat() {
        val c = TestClock()
        val s = store(c)
        assertEquals(4, wrong(s, c, 4))
        assertEquals(0L, s.left())
        assertEquals(5, wrong(s, c, 1))
        assertEquals(5 * min, s.left())
        assertNull("no try while waiting", s.begin())
        assertEquals(10, wrong(s, c, 5))
        assertEquals(hour, s.left())
        assertEquals(15, wrong(s, c, 5))
        assertEquals(12 * hour, s.left())
        assertEquals(20, wrong(s, c, 5))
        assertEquals(24 * hour, s.left())
        assertEquals(25, wrong(s, c, 5))
        assertEquals(24 * hour, s.left())
        assertEquals(30, wrong(s, c, 5))
        assertEquals(24 * hour, s.left())
        assertFalse(s.eraseDue())
        // The right lock: the count starts again.
        c.now += s.left()
        s.begin()
        s.right()
        assertEquals(0, s.fails())
        assertEquals(0L, s.left())
    }

    @Test fun eraseSwitchOnErasesOnlyAtTheEnd() {
        val c = TestClock()
        val s = store(c)
        assertFalse(s.erase)
        s.erase = true
        assertEquals(20, wrong(s, c, 20, erase = true))
        assertEquals(24 * hour, s.left())
        assertFalse(s.eraseDue())
        assertEquals(24, wrong(s, c, 4, erase = true))
        assertFalse(s.eraseDue())
        c.now += s.left()
        val last = s.begin()!!
        assertEquals(25, last.fails)
        assertTrue(LockWait.erases(last.fails, last.erase))
        // Not while its check runs; once it's known to be wrong.
        assertFalse(s.eraseDue())
        s.wrong()
        assertTrue(s.eraseDue())
        // The switch survives a right try; turning it off keeps the count.
        s.right()
        assertTrue(s.erase)
        s.erase = false
        assertFalse(s.erase)
    }

    @Test fun inAppTriesLockInsteadOfErasing() {
        val c = TestClock()
        val s = store(c)
        s.erase = true
        wrong(s, c, 24, erase = true)
        c.now += s.left()
        // Five wrong in-app tries at the last stage: none counted, no erase, and the fifth locks Muninn.
        var b = s.begin(inApp = true)!!
        repeat(4) { b = s.begin(inApp = true)!! }
        assertTrue(b.lockApp)
        assertEquals(24, s.fails())
        assertFalse(s.eraseDue())
    }

    @Test fun stoppedMidCheckStillCounts() {
        // The try is on disk before the check: a new LockStore (Muninn killed and started again) sees it.
        val c = TestClock()
        val first = store(c)
        wrong(first, c, 4)
        assertNotNull(first.begin())   // the fifth try: counted, then the process dies before the check ends
        val again = store(c)
        assertEquals(5, again.fails())
        assertEquals(5 * min, again.left())
        // With erase on, the last try stopped before its check answered: nothing was learned from it, so it's given back
        // at the next start instead of erasing on a try that may have been right.
        val c2 = TestClock()
        val dir2 = File(dir, "two").also { it.mkdirs() }
        val s2 = LockStore(File(dir2, "lock-state"), c2)
        s2.erase = true
        wrong(s2, c2, 24, erase = true)
        c2.now += s2.left()
        s2.begin()
        val restarted = LockStore(File(dir2, "lock-state"), c2)
        assertFalse(restarted.eraseDue())
        restarted.settle()
        assertEquals(24, restarted.fails())
        assertFalse(restarted.eraseDue())
        // Its answer saved (wrong) before Muninn stopped: the next start erases.
        restarted.begin()
        restarted.wrong()
        val again2 = LockStore(File(dir2, "lock-state"), c2)
        again2.settle()
        assertTrue(again2.eraseDue())
        // A check running in this process is never given back.
        val c3 = TestClock()
        val s3 = LockStore(File(File(dir, "three").also { it.mkdirs() }, "lock-state"), c3)
        s3.erase = true
        wrong(s3, c3, 24, erase = true)
        c3.now += s3.left()
        s3.begin()
        s3.settle()
        assertEquals(25, s3.fails())
    }

    @Test fun clockChangesCantSkipAWaitAndARestartOnlyPausesIt() {
        val c = TestClock(now = 500_000L, boot = 3)
        val s = store(c)
        wrong(s, c, 10)
        assertEquals(hour, s.left())
        // Within one start-up the wall clock isn't used; time since start-up moves on as it does.
        c.now += 10 * min; c.wall += 10 * min
        assertEquals(50 * min, s.left())
        c.wall += 30 * hour   // the clock moved forward: no effect
        assertEquals(50 * min, s.left())
        c.wall -= 30 * hour
        // A restart: time since start-up back near zero, another boot number. It goes on from what was left, not again
        // in full (round 19: a phone that restarts every night still gets out of a 24-hour wait).
        c.now = 2_000L; c.boot = 4; c.wall += 5 * min
        assertEquals(50 * min - 2_000L, s.left())
        c.now += 49 * min; c.wall += 49 * min
        assertEquals(min - 2_000L, s.left())
        c.now += min; c.wall += min
        assertEquals(0L, s.left())
        // Over and forgotten: a later restart doesn't bring it back.
        c.now = 10L; c.boot = 5
        assertEquals(0L, s.left())
    }

    @Test fun aRestartWithTheClockMovedOnNeverShortensAWait() {
        val c = TestClock(now = 500_000L, boot = 3)
        val s = store(c)
        wrong(s, c, 20)
        assertEquals(24 * hour, s.left())
        c.now += 2 * hour; c.wall += 2 * hour
        assertEquals(22 * hour, s.left())   // saved: 22 hours left
        // Restarted with the wall clock set a day ahead: the time since start-up (1 minute) is all that surely passed.
        c.now = min; c.boot = 4; c.wall += 24 * hour
        assertEquals(22 * hour - min, s.left())
        // Restarted with the clock honest and 3 hours gone: the wall clock says less is left, but what was saved says more:
        // the longer reading wins (a restart pauses at worst).
        val c2 = TestClock(now = 500_000L, boot = 3)
        val s2 = LockStore(File(File(dir, "b").also { it.mkdirs() }, "lock-state"), c2)
        wrong(s2, c2, 20)
        c2.now += 2 * hour; c2.wall += 2 * hour
        s2.left()
        c2.now = min; c2.boot = 4; c2.wall += 3 * hour
        assertEquals(22 * hour - min, s2.left())
        // A file from before round 19 (no time left saved): the whole wait again, as before.
        val f = File(dir, "old").also { it.mkdirs() }
        File(f, "lock-state").writeText("{\"fails\":10,\"until\":3600000,\"wait\":3600000,\"boot\":1,\"erase\":false}")
        val c3 = TestClock(now = 5_000L, boot = 2)
        assertEquals(hour, LockStore(File(f, "lock-state"), c3).left())
    }

    @Test fun removingTheLockStateFileDoesntResetTheCount() {
        val c = TestClock()
        val m = TestMirror()
        val s = store(c, m)
        wrong(s, c, 12)
        s.erase = true
        // The file and its copy gone (or an old one put back): the mirror still has the higher count.
        File(dir, "lock-state").delete(); File(dir, "lock-state.bak").delete()
        val again = store(c, m)
        assertEquals(12, again.fails())
        assertTrue(again.erase)
        // The next try writes both again; then the mirror cleared instead: the file still has it.
        assertEquals(13, again.begin()!!.fails)
        m.data = LockStore.Data()
        assertEquals(13, store(c, m).fails())
        assertTrue(store(c, m).erase)
    }

    @Test fun aRightTryCutShortBetweenTheTwoCopiesStaysRight() {
        // Round 19: right() writes the file, then the mirror. Stopped in between, the mirror still has the old count; the
        // newer save wins, so the count stays reset (before, the higher count won and a right last try could erase).
        val c = TestClock()
        val m = object : LockStore.Mirror {
            var data: LockStore.Data? = null
            var frozen = false
            override fun read() = data
            override fun write(d: LockStore.Data) { if (!frozen) data = d }
        }
        val s = store(c, m)
        s.erase = true
        wrong(s, c, 24, erase = true)
        c.now += s.left()
        s.begin()
        m.frozen = true   // the mirror's write never lands
        s.right()
        val again = store(c, m)
        assertEquals(0, again.fails())
        again.settle()
        assertFalse(again.eraseDue())
        assertTrue(again.erase)
    }

    @Test fun turningTheLockOffTurnsEraseOff() {
        val c = TestClock()
        val s = store(c)
        s.erase = true
        wrong(s, c, 3)
        s.reset()
        assertFalse(s.erase)
        assertEquals(0, s.fails())
    }

    // ------------------------------------------------------------------ the unlock password

    @Test fun eitherThePatternOrTheUnlockPasswordOpens() {
        val root = File(dir, "files").also { it.mkdirs() }
        val noBackup = File(dir, "no_backup").also { it.mkdirs() }
        val v = VaultCore(root, File(noBackup, "vault-open.key")) {}
        assertTrue(v.openDefault())
        v.write(File(root, "secrets.json"), "{}")
        v.setPassword("pattern:0,1,2,5,8,7", pattern = true, spare = "spare words here")
        assertTrue(v.hasSpare)
        assertTrue(v.checkSpare("spare words here"))
        assertFalse(v.checkSpare("pattern:0,1,2,5,8,7"))
        v.lock()
        val fresh = VaultCore(root, File(noBackup, "vault-open.key")) {}
        assertFalse(fresh.unlock("pattern:0,1,2,5,8,6"))
        assertFalse(fresh.unlock("wrong words"))
        assertTrue(fresh.unlock("spare words here"))
        val fresh2 = VaultCore(root, File(noBackup, "vault-open.key")) {}
        assertTrue(fresh2.unlock("pattern:0,1,2,5,8,7"))
        // Changing the unlock password keeps the pattern; the old one stops working.
        assertTrue(fresh2.setSpare("new spare words"))
        assertTrue(fresh2.check("pattern:0,1,2,5,8,7"))
        assertTrue(fresh2.check("new spare words"))
        assertFalse(fresh2.check("spare words here"))
        // Never stored as it is.
        val key = String(File(root, "vault.key").readBytes())
        assertFalse(key.contains("new spare words"))
        assertFalse(key.contains("0,1,2,5,8,7"))
        // A password lock has no unlock password.
        fresh2.setPassword("just a password", pattern = false, spare = "ignored")
        assertFalse(fresh2.hasSpare)
    }
}
