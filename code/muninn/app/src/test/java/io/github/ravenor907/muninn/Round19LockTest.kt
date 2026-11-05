package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.SafeFile
import io.github.ravenor907.muninn.net.Vault
import io.github.ravenor907.muninn.net.VaultCore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Round 19: the data key changes with the lock, kept copies follow the new lock, and the lock file's own checks. */
class Round19LockTest {
    private lateinit var dir: File
    private lateinit var root: File
    private lateinit var noBackup: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("r19lock").toFile()
        root = File(dir, "files").also { it.mkdirs() }
        noBackup = File(dir, "no_backup").also { it.mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun core(r: File = root) = VaultCore(r, File(noBackup, "vault-open.key")) {}

    /** The data key in [keyFile]'s wrap for [secret] (a typed password lock), or null. */
    private fun keyIn(keyFile: File, secret: String): ByteArray? {
        val o = JSONObject(keyFile.readText())
        val kek = Vault.Crypto.scrypt(secret.toByteArray(), Vault.Crypto.b64d(o.getString("salt")), o.getInt("N"), o.getInt("r"), o.getInt("p"))
        return Vault.Crypto.open(kek, Vault.Crypto.b64d(o.getString("wrapped")))
    }

    private fun opens(k: ByteArray, f: File): Boolean {
        val b = f.readBytes()
        return Vault.Crypto.open(k, b.copyOfRange(Vault.Crypto.MAGIC.size, b.size)) != null
    }

    private fun waitForRekey(v: VaultCore) {
        val until = System.currentTimeMillis() + 30_000
        while (v.rekeyPending && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(v.rekeyNow())
        assertFalse(v.rekeyPending)
    }

    @Test fun changingTheLockChangesTheDataKeyWithoutLosingAnything() {
        val v = core()
        assertTrue(v.openDefault())
        v.write(File(root, VaultCore.SECRETS), "{\"chatPriv\":\"mine\"}")
        v.write(File(root, "chats/one.json"), "hello")
        v.write(File(root, "chats/one.json"), "hello again")   // a last good copy (.bak) too
        v.writeBytes(File(root, "photos/p.jpg"), ByteArray(5000) { it.toByte() })
        v.setPassword("first password")
        waitForRekey(v)
        val oldLock = File(dir, "old-vault.key").also { v.keyFile.copyTo(it) }
        val k1 = keyIn(oldLock, "first password")!!

        // Changed: every file opens with the new lock, and none with the old lock file and the old password.
        v.setPassword("second password")
        assertEquals("hello again", v.read(File(root, "chats/one.json")))   // readable while the change runs
        waitForRekey(v)
        v.lock()
        val again = core()
        assertFalse(again.unlock("first password"))
        assertTrue(again.unlock("second password"))
        assertEquals("{\"chatPriv\":\"mine\"}", again.read(File(root, VaultCore.SECRETS)))
        assertEquals("hello again", again.read(File(root, "chats/one.json")))
        assertEquals(5000, again.readBytes(File(root, "photos/p.jpg"))!!.size)
        for (f in root.walkTopDown().filter { it.isFile && again.isSealed(it) }) assertFalse("${f.name} opens with the old key", opens(k1, f))
        assertFalse(JSONObject(again.keyFile.readText()).has("prev"))
    }

    @Test fun aChangeOfKeyCutShortGoesOnAtTheNextUnlock() {
        val v = core()
        assertTrue(v.openDefault())
        v.write(File(root, VaultCore.SECRETS), "{}")
        for (i in 0 until 40) v.write(File(root, "chats/c$i.json"), "chat $i")
        v.setPassword("a password")
        // Muninn stopped at once, wherever the re-sealing got to: a new start opens everything, and finishes the change.
        val next = core()
        assertTrue(next.unlock("a password"))
        for (i in 0 until 40) assertEquals("chat $i", next.read(File(root, "chats/c$i.json")))
        waitForRekey(next)
        val third = core()
        assertTrue(third.unlock("a password"))
        for (i in 0 until 40) assertEquals("chat $i", third.read(File(root, "chats/c$i.json")))
    }

    @Test fun keptCopiesFollowTheNewLockAndLoseTheirOpenKey() {
        val v = core()
        assertTrue(v.openDefault())
        v.write(File(root, VaultCore.SECRETS), "{\"chatPriv\":\"kept\"}")
        // A restore before any lock: the kept copy holds the plain open key.
        val kept = v.keepCopy()
        assertTrue(kept.listFiles()!!.any { it.name.startsWith("open-") })
        v.setPassword("new lock words")
        waitForRekey(v)
        assertFalse("no plain key left in a kept copy", kept.listFiles()!!.any { it.name.startsWith("open-") })
        // Checked by hand, without opening it (opening a kept copy here would change it): the new lock opens the live key,
        // and the kept copy's own key is sealed with that in its lock file.
        val live = keyIn(File(kept, "vault.key"), "new lock words")!!
        val theirs = Vault.Crypto.open(live, Vault.Crypto.b64d(JSONObject(File(kept, "vault.key").readText()).getString("prev")))!!
        assertTrue(opens(theirs, File(kept, VaultCore.SECRETS)))
        // A later change: the kept copy follows it, and the lock before no longer opens it.
        v.setPassword("newer lock words")
        waitForRekey(v)
        assertFalse(core(kept).unlock("new lock words"))
        val k2 = core(kept)
        assertTrue(k2.unlock("newer lock words"))
        assertEquals("{\"chatPriv\":\"kept\"}", k2.read(File(kept, VaultCore.SECRETS)))
    }

    @Test fun aWeakenedLockFileIsNotUsed() {
        assertTrue(VaultCore.kdfOk(Vault.SCRYPT_N, Vault.SCRYPT_R, Vault.SCRYPT_P))
        assertFalse(VaultCore.kdfOk(1024, 8, 1))
        assertFalse(VaultCore.kdfOk(Vault.SCRYPT_N, 1, 1))
        assertFalse(VaultCore.kdfOk(1 shl 24, 8, 1))
        assertFalse(VaultCore.kdfOk(Vault.SCRYPT_N + 1, 8, 1))
        val v = core()
        assertTrue(v.openDefault())
        v.write(File(root, VaultCore.SECRETS), "{}")
        v.setPassword("strong enough")
        waitForRekey(v)
        v.lock()
        // The same wrap claiming a cheaper scrypt: refused, not tried.
        val o = JSONObject(v.keyFile.readText()).put("N", 1024)
        SafeFile.writeMirror(v.keyFile, o.toString().toByteArray())
        assertFalse(core().unlock("strong enough"))
    }

    @Test fun anUnlockPasswordThatReadsAsAPatternIsRefused() {
        val v = core()
        assertTrue(v.openDefault())
        v.write(File(root, VaultCore.SECRETS), "{}")
        v.setPassword("pattern:0,1,2,5,8,7", pattern = true, spare = "good spare words")
        assertFalse(v.setSpare("pattern:1,2,3,4"))
        assertTrue(v.checkSpare("good spare words"))
        var refused = false
        try { v.setPassword("pattern:0,1,2,5,8,6", pattern = true, spare = "pattern:3,4,5") } catch (_: IllegalArgumentException) { refused = true }
        assertTrue(refused)
        assertTrue(v.check("pattern:0,1,2,5,8,7"))
    }

    @Test fun theCheckAnswersBeforeTheVaultOpens() {
        val v = core()
        assertTrue(v.openDefault())
        v.write(File(root, VaultCore.SECRETS), "{}")
        v.setPassword("the password")
        waitForRekey(v)
        v.lock()
        val fresh = core()
        var said: Boolean? = null
        assertFalse(fresh.unlock("wrong one") { said = it })
        assertEquals(false, said)
        said = null
        assertTrue(fresh.unlock("the password") { r -> said = r; assertTrue("answered while still locked", fresh.locked.value) })
        assertEquals(true, said)
        assertNull(fresh.trouble.value)
    }
}
