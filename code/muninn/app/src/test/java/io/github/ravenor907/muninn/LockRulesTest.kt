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
    private class TestClock(var now: Long = 1_000L, var boot: Int = 1) : LockStore.Clock {
        override fun now() = now
        override fun boot() = boot
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
            c.now += s.left()
            last = s.begin()!!.fails
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

    @Test fun triesInsideTheAppNeverErase() {
        // Erase on, 24 wrong: an in-app try stays one short and locks the app instead.
        assertEquals(24 to true, LockWait.next(24, erase = true, inApp = true))
        assertEquals(25 to false, LockWait.next(24, erase = true, inApp = false))
        assertEquals(25 to false, LockWait.next(24, erase = false, inApp = true))
        assertEquals(6 to false, LockWait.next(5, erase = true, inApp = true))
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
        val b = s.begin(inApp = true)!!
        assertTrue(b.lockApp)
        assertEquals(24, b.fails)
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
        // With erase on, the last try counted before a kill erases on the next start.
        val c2 = TestClock()
        val dir2 = File(dir, "two").also { it.mkdirs() }
        val s2 = LockStore(File(dir2, "lock-state"), c2)
        s2.erase = true
        wrong(s2, c2, 24, erase = true)
        c2.now += s2.left()
        s2.begin()
        assertTrue(LockStore(File(dir2, "lock-state"), c2).eraseDue())
    }

    @Test fun clockChangesCantSkipAWaitAndARestartStartsItAgain() {
        val c = TestClock(now = 500_000L, boot = 3)
        val s = store(c)
        wrong(s, c, 10)
        assertEquals(hour, s.left())
        // The wall clock isn't used at all; time since start-up moves on as it does.
        c.now += 10 * min
        assertEquals(50 * min, s.left())
        // A restart: time since start-up back near zero, another boot number. The whole wait again, not nothing.
        c.now = 2_000L; c.boot = 4
        assertEquals(hour, s.left())
        c.now += 59 * min
        assertEquals(min, s.left())
        c.now += min
        assertEquals(0L, s.left())
        // Over and forgotten: a later restart doesn't bring it back.
        c.now = 10L; c.boot = 5
        assertEquals(0L, s.left())
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
