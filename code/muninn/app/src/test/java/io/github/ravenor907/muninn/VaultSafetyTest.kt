package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.SafeFile
import io.github.ravenor907.muninn.net.VaultCore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Phase 1 data safety: the lock file, last good copies, one-step writes and saves made while locked. */
class VaultSafetyTest {
    private lateinit var dir: File
    private lateinit var root: File
    private lateinit var noBackup: File
    private val logged = ArrayList<String>()

    @Before fun setUp() {
        dir = Files.createTempDirectory("vault").toFile()
        root = File(dir, "files").also { it.mkdirs() }
        noBackup = File(dir, "no_backup").also { it.mkdirs() }
    }

    @After fun tearDown() {
        SafeFile.midWrite = null
        dir.deleteRecursively()
    }

    private fun core() = VaultCore(root, File(noBackup, "vault-open.key")) { logged.add(it) }
    private fun garbage(f: File) = f.writeBytes("not a lock at all".toByteArray())

    // ------------------------------------------------------------------ the lock file

    @Test fun firstRunMakesAKey() {
        val v = core()
        assertTrue(v.openDefault())
        assertTrue(v.keyFile.exists())
        assertTrue(SafeFile.bakOf(v.keyFile).exists())
        assertNull(v.trouble.value)
    }

    @Test fun corruptKeyNeverMakesANewKey() {
        val first = core()
        assertTrue(first.openDefault())
        val secrets = File(root, VaultCore.SECRETS)
        first.write(secrets, "{\"chatPriv\":\"mine\"}")
        val openKey = File(noBackup, "vault-open.key").readBytes()
        val sealed = secrets.readBytes()
        // Both copies of the lock file damaged.
        garbage(first.keyFile); garbage(SafeFile.bakOf(first.keyFile))

        val v = core()
        assertFalse(v.openDefault())
        assertEquals(VaultCore.Trouble.KEY, v.trouble.value)
        assertTrue(v.locked.value)
        // Nothing was made new or written over.
        assertArrayEquals("not a lock at all".toByteArray(), v.keyFile.readBytes())
        assertArrayEquals(openKey, File(noBackup, "vault-open.key").readBytes())
        assertArrayEquals(sealed, secrets.readBytes())
        // And nothing can be saved while it's like this.
        v.write(File(root, "chats.json"), "{}")
        assertFalse(File(root, "chats.json").exists())
        // Trying again changes nothing either.
        assertFalse(v.openDefault())
        assertArrayEquals("not a lock at all".toByteArray(), v.keyFile.readBytes())
    }

    @Test fun missingKeyWithDataIsTroubleNotAFirstRun() {
        val first = core()
        assertTrue(first.openDefault())
        first.write(File(root, "contacts.json"), "[]")
        first.keyFile.delete(); SafeFile.bakOf(first.keyFile).delete()

        val v = core()
        assertFalse(v.openDefault())
        assertEquals(VaultCore.Trouble.KEY, v.trouble.value)
        assertFalse(v.keyFile.exists())
    }

    @Test fun unreadableKeyFileIsTrouble() {
        val first = core()
        assertTrue(first.openDefault())
        first.write(File(root, "contacts.json"), "[]")
        // A folder where the file should be: reading it fails like a disk error would.
        first.keyFile.delete(); SafeFile.bakOf(first.keyFile).delete()
        first.keyFile.mkdirs()

        val v = core()
        assertFalse(v.openDefault())
        assertEquals(VaultCore.Trouble.KEY, v.trouble.value)
        assertTrue(v.keyFile.isDirectory)
    }

    @Test fun damagedKeyFileOpensFromItsCopy() {
        val first = core()
        assertTrue(first.openDefault())
        first.write(File(root, "contacts.json"), "[\"Ann\"]")
        garbage(first.keyFile)

        val v = core()
        assertTrue(v.openDefault())
        assertEquals("[\"Ann\"]", v.read(File(root, "contacts.json")))
        // Put right from the copy.
        assertArrayEquals(SafeFile.bakOf(v.keyFile).readBytes(), v.keyFile.readBytes())
    }

    @Test fun wrongOpenKeyIsTroubleAndKeepsTheKeysFile() {
        val first = core()
        assertTrue(first.openDefault())
        val secrets = File(root, VaultCore.SECRETS)
        first.write(secrets, "{\"chatPriv\":\"mine\"}")
        val sealed = secrets.readBytes()
        val openKey = File(noBackup, "vault-open.key")
        openKey.writeBytes(ByteArray(32) { 7 }); SafeFile.bakOf(openKey).writeBytes(ByteArray(32) { 7 })

        val v = core()
        assertFalse(v.openDefault())
        assertEquals(VaultCore.Trouble.DATA, v.trouble.value)
        assertArrayEquals(sealed, secrets.readBytes())
    }

    // ------------------------------------------------------------------ stores

    @Test fun corruptStoreFallsBackToItsLastGoodCopy() {
        val v = core()
        assertTrue(v.openDefault())
        val f = File(root, "contacts.json")
        v.write(f, "[\"Ann\"]")
        v.write(f, "[\"Ann\",\"Bo\"]")
        garbage(f)

        val again = core()
        assertTrue(again.openDefault())
        assertEquals("[\"Ann\"]", again.read(f))
        // Saving now replaces the damaged file and keeps the good copy as it was.
        again.write(f, "[\"Ann\",\"Cy\"]")
        assertEquals("[\"Ann\",\"Cy\"]", again.read(f))
        assertEquals("[\"Ann\"]", core().also { assertTrue(it.openDefault()) }.let { c ->
            SafeFile.bakOf(f).copyTo(File(root, "copy.json")); c.read(File(root, "copy.json"))
        })
    }

    @Test fun corruptStoreIsNeverSavedEmpty() {
        val v = core()
        assertTrue(v.openDefault())
        val f = File(root, "chats.json")
        v.write(f, "{\"a\":[1]}")
        v.write(f, "{\"a\":[1,2]}")
        garbage(f); garbage(SafeFile.bakOf(f))

        val again = core()
        assertTrue(again.openDefault())
        assertNull(again.read(f))
        try { again.write(f, "{}"); fail("saved over a store that didn't load") } catch (_: VaultCore.Refused) {}
        assertArrayEquals("not a lock at all".toByteArray(), f.readBytes())
        assertTrue(logged.any { "chats.json" in it })
    }

    @Test fun storeThatCouldNotBeParsedIsNotSavedOver() {
        val v = core()
        assertTrue(v.openDefault())
        val f = File(root, "folders.json")
        v.write(f, "{\"folders\":[\"Work\"]}")
        val before = f.readBytes()
        v.loadFailed(f, IllegalStateException("bad json"))
        try { v.write(f, "{}"); fail("saved over a store that didn't load") } catch (_: VaultCore.Refused) {}
        assertArrayEquals(before, f.readBytes())
    }

    // ------------------------------------------------------------------ one-step writes

    @Test fun interruptedWriteLeavesTheOldFile() {
        val f = File(root, "state.json")
        SafeFile.write(f, "old".toByteArray())
        SafeFile.midWrite = { throw IOException("power cut") }
        try { SafeFile.write(f, "new and longer".toByteArray()); fail("the write should have stopped") } catch (_: IOException) {}
        SafeFile.midWrite = null
        assertEquals("old", f.readText())
        assertFalse(SafeFile.tmpOf(f).exists())
    }

    @Test fun interruptedVaultWriteLeavesTheOldFile() {
        val v = core()
        assertTrue(v.openDefault())
        val f = File(root, "contacts.json")
        v.write(f, "[\"Ann\"]")
        SafeFile.midWrite = { throw IOException("power cut") }
        try { v.write(f, "[]"); fail("the write should have stopped") } catch (_: IOException) {}
        SafeFile.midWrite = null
        assertEquals("[\"Ann\"]", core().also { assertTrue(it.openDefault()) }.read(f))
    }

    @Test fun lastGoodCopyIsThePreviousVersion() {
        val f = File(root, "x.json")
        SafeFile.write(f, "one".toByteArray())
        SafeFile.write(f, "two".toByteArray())
        assertEquals("two", f.readText())
        assertEquals("one", SafeFile.bakOf(f).readText())
        val r = SafeFile.read(f) { b -> String(b).takeIf { it == "one" } }
        assertEquals(SafeFile.Read.Ok("one", true), r)
    }

    @Test fun deletedFileStaysDeleted() {
        val f = File(root, "x.json")
        SafeFile.write(f, "one".toByteArray())
        SafeFile.write(f, "two".toByteArray())
        f.delete()
        assertEquals(SafeFile.Read.Missing, SafeFile.read(f) { String(it) })
    }

    @Test fun twoWritersNeverMix() {
        val f = File(root, "x.json")
        val a = "a".repeat(200_000).toByteArray()
        val b = "b".repeat(200_000).toByteArray()
        val threads = (0 until 8).map { i -> Thread { repeat(20) { SafeFile.write(f, if (i % 2 == 0) a else b) } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        val got = f.readBytes()
        assertTrue(got.contentEquals(a) || got.contentEquals(b))
    }

    // ------------------------------------------------------------------ locked

    @Test fun savedWhileLockedIsWrittenAfterUnlock() {
        val v = core()
        assertTrue(v.openDefault())
        v.setPassword("correct horse")
        val f = File(root, "contacts.json")
        v.write(f, "[\"Ann\"]")
        v.lock()
        assertTrue(v.locked.value)

        v.write(f, "[\"Ann\",\"Bo\"]")
        assertEquals(1, v.waiting())
        // Read back as saved, even while locked.
        assertEquals("[\"Ann\",\"Bo\"]", v.read(f))

        assertTrue(v.unlock("correct horse"))
        assertEquals(0, v.waiting())
        val fresh = core()
        assertTrue(fresh.unlock("correct horse"))
        assertEquals("[\"Ann\",\"Bo\"]", fresh.read(f))
    }

    @Test fun readWhileLockedIsNeverSavedOverWithNothing() {
        val v = core()
        assertTrue(v.openDefault())
        v.setPassword("correct horse")
        val f = File(root, "trash.json")
        v.write(f, "{\"trash\":{\"k\":1}}")
        v.lock()
        // Locked: the read sees nothing, so a save based on it must not replace the file.
        assertNull(v.read(f))
        try { v.write(f, "{\"trash\":{}}"); fail("saved over a store read while locked") } catch (_: VaultCore.Refused) {}
        assertTrue(v.unlock("correct horse"))
        assertEquals("{\"trash\":{\"k\":1}}", v.read(f))
        // Read for real now: saving works again.
        v.write(f, "{\"trash\":{}}")
        assertEquals("{\"trash\":{}}", v.read(f))
    }

    /** Before a restore: a copy of everything (lock file and open key too) goes into a kept- folder; nothing here changes. */
    @Test fun keepCopyBeforeARestore() {
        val v = core()
        assertTrue(v.openDefault())
        val secrets = File(root, VaultCore.SECRETS)
        v.write(secrets, "{\"chatPriv\":\"mine\"}")
        val photo = File(root, "photos/p1").also { it.parentFile!!.mkdirs() }
        v.writeBytes(photo, byteArrayOf(1, 2, 3))
        val kept = v.keepCopy()
        assertTrue(kept.name.startsWith(VaultCore.KEPT))
        assertArrayEquals(secrets.readBytes(), File(kept, VaultCore.SECRETS).readBytes())
        assertArrayEquals(photo.readBytes(), File(kept, "photos/p1").readBytes())
        assertTrue(File(kept, "vault.key").exists())
        assertTrue(File(kept, "open-vault-open.key").exists())
        assertEquals("{\"chatPriv\":\"mine\"}", v.read(secrets))
        assertFalse(v.sealedFiles().any { it.first.startsWith(VaultCore.KEPT) })
    }

    /**
     * "Forgot it?" › Erase and restore (M7-1): the backup has no lock in it, so the restored vault must not stay open.
     * After the restart it asks for a new lock (lockNeeded), and only a real new lock clears that.
     */
    @Test fun forgotLockRestoreIsNeverLeftUnlocked() {
        val first = core()
        assertTrue(first.openDefault())
        first.setPassword("forgotten one", pattern = true)
        first.write(File(root, VaultCore.SECRETS), "{\"chatPriv\":\"old\"}")

        // A later start: locked, and the lock is forgotten.
        val locked = core()
        assertFalse(locked.openDefault())
        assertTrue(locked.hasPassword)
        val kept = locked.startOverKeeping(needsLock = true)
        locked.stageRestore(listOf(VaultCore.SECRETS to "{\"chatPriv\":\"backup\"}".toByteArray()))
        kept.deleteRecursively()

        // Muninn restarts: the backup is in, and a new lock is needed before anything shows.
        val restored = core()
        assertTrue(restored.openDefault())
        assertEquals("{\"chatPriv\":\"backup\"}", restored.read(File(root, VaultCore.SECRETS)))
        assertFalse(restored.hasPassword)
        assertTrue(restored.lockNeeded)
        // A restart before the new lock is set still asks.
        assertTrue(core().lockNeeded)
        // Taking the lock off doesn't count as setting one.
        restored.setPassword(null)
        assertTrue(restored.lockNeeded)

        restored.setPassword("new pattern", pattern = true)
        assertFalse(restored.lockNeeded)
        assertTrue(restored.hasPassword)
        // Locked from the next start on: nothing opens without the new lock.
        val next = core()
        assertFalse(next.openDefault())
        assertTrue(next.locked.value)
        assertFalse(next.unlock("forgotten one"))
        assertTrue(next.unlock("new pattern"))
        assertEquals("{\"chatPriv\":\"backup\"}", next.read(File(root, VaultCore.SECRETS)))
    }

    /** The other start over (the lock file can't be read) doesn't ask for a lock. */
    @Test fun troubleStartOverDoesNotAskForALock() {
        val v = core()
        assertTrue(v.openDefault())
        v.startOverKeeping()
        assertFalse(v.lockNeeded)
        assertFalse(core().lockNeeded)
    }
}
