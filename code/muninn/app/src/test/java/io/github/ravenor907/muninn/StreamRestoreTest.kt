package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.BackupFormat
import io.github.ravenor907.muninn.net.GcmStream
import io.github.ravenor907.muninn.net.MoveWire
import io.github.ravenor907.muninn.net.SafeFile
import io.github.ravenor907.muninn.net.VaultCore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 1.54: backups and moves streamed (any size the phone could make restores), and restores that change nothing on failure. */
class StreamRestoreTest {
    private lateinit var dir: File
    private lateinit var root: File
    private lateinit var noBackup: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("stream").toFile()
        root = File(dir, "files").also { it.mkdirs() }
        noBackup = File(dir, "no_backup").also { it.mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    private fun core() = VaultCore(root, File(noBackup, "vault-open.key")) { }

    private fun jdkSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); if (aad.isNotEmpty()) updateAAD(aad); doFinal(plain)
        }

    @Test fun gcmStreamIsTheSameAsOneShotGcm() {
        val rnd = Random(7)
        for (size in listOf(0, 1, 15, 16, 17, 100, 65_535, 65_536, 65_553, 300_001)) for (aadSize in listOf(0, 5, 16, 40)) {
            val key = ByteArray(32).also { rnd.nextBytes(it) }; val nonce = ByteArray(12).also { rnd.nextBytes(it) }
            val aad = ByteArray(aadSize).also { rnd.nextBytes(it) }; val plain = ByteArray(size).also { rnd.nextBytes(it) }
            val out = ByteArrayOutputStream()
            GcmStream.Writer(out, key, nonce, aad).use { w ->
                // In uneven pieces.
                var o = 0
                while (o < plain.size) { val t = minOf(plain.size - o, 1 + rnd.nextInt(9000)); w.write(plain, o, t); o += t }
            }
            val expected = jdkSeal(key, nonce, aad, plain)
            assertArrayEquals("size $size aad $aadSize", expected, out.toByteArray())
            // And back, read in small pieces.
            val r = GcmStream.Reader(ByteArrayInputStream(expected), key, nonce, aad)
            val back = ByteArrayOutputStream(); val buf = ByteArray(777)
            while (true) { val n = r.read(buf); if (n < 0) break; back.write(buf, 0, n) }
            assertTrue(r.checked)
            assertArrayEquals(plain, back.toByteArray())
        }
    }

    @Test fun gcmStreamRefusesAChangedByte() {
        val key = ByteArray(32) { it.toByte() }; val nonce = ByteArray(12) { 1 }
        val sealed = jdkSeal(key, nonce, ByteArray(0), ByteArray(5000) { 3 })
        for (i in listOf(0, 2500, sealed.size - 1)) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            try { GcmStream.Reader(ByteArrayInputStream(bad), key, nonce).drain(); fail("byte $i") } catch (e: GcmStream.BadTag) { }
        }
        try { GcmStream.Reader(ByteArrayInputStream(ByteArray(10)), key, nonce).drain(); fail() } catch (e: GcmStream.BadTag) { }
    }

    private fun backup(list: List<Pair<String, ByteArray>>, pw: String = "correct horse battery"): ByteArray {
        val out = ByteArrayOutputStream()
        BackupFormat.write(out, pw, list.map { (n, d) -> BackupFormat.Entry(n) { d } })
        return out.toByteArray()
    }

    @Test fun streamedOpenHoldsOnlyTheAtEntriesAndRestoresEverything() {
        val big = ByteArray(3_000_000) { (it * 31).toByte() }
        val list = listOf("@about.json" to "{\"v\":1,\"app\":\"muninn\",\"files\":true}".toByteArray(), "photos/a" to big, "chats.json" to "{}".toByteArray(),
            "@prefs.json" to "{}".toByteArray())
        val b = backup(list)
        var opens = 0
        val o = BackupFormat.open({ opens++; ByteArrayInputStream(b) }, "correct horse battery")
        assertEquals(listOf("@about.json", "@prefs.json"), o.files.map { it.first })
        assertEquals(2, o.items)
        assertEquals(2, BackupFormat.summary(o).items)
        val got = ArrayList<Pair<String, ByteArray>>()
        o.each { n, d -> got += n to d }
        assertEquals(2, opens)
        assertEquals(list.map { it.first }, got.map { it.first })
        assertArrayEquals(big, got[1].second)
        // The in-memory open gives the same.
        assertEquals(list.map { it.first }, BackupFormat.open(b, "correct horse battery").files.map { it.first })
        try { BackupFormat.open({ ByteArrayInputStream(b) }, "wrong horse battery"); fail() } catch (e: BackupFormat.WrongPassword) { }
        // Changed between checking and restoring: the restore throws (and stages nothing, see below).
        val changed = b.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 4).toByte() }
        var first = true
        val o2 = BackupFormat.open({ if (first) { first = false; ByteArrayInputStream(b) } else ByteArrayInputStream(changed) }, "correct horse battery")
        try { o2.each { _, _ -> }; fail() } catch (e: Exception) { }
    }

    @Test fun packerMatchesPackAndPartsReadItBack() {
        val entries = listOf("a" to ByteArray(10) { 1 }, "photos/b" to ByteArray(MoveWire.PART * 2 + 5) { (it % 251).toByte() }, "@prefs.json" to "{}".toByteArray())
        val parts = ArrayList<ByteArray>()
        val p = MoveWire.Packer { i, part -> assertEquals(parts.size, i); parts += part }
        entries.forEach { (n, d) -> p.entry(n, d) }
        p.finish()
        val blob = MoveWire.pack(entries)
        assertEquals(blob.size.toLong(), p.size)
        assertEquals(MoveWire.sha256Hex(blob), p.sha)
        assertEquals(MoveWire.parts(blob.size.toLong()), parts.size)
        assertArrayEquals(blob, parts.fold(ByteArray(0)) { a, b -> a + b })
        val back = ArrayList<String>()
        val r = MoveWire.Parts(parts.size) { parts[it] }
        BackupFormat.entries(r, { true }) { n, _ -> back += n }
        assertEquals(entries.map { it.first }, back)
        assertEquals(p.sha, r.sha())
        try { MoveWire.Parts(parts.size) { if (it == 1) null else parts[it] }.sha(); fail() } catch (e: MoveWire.BadPart) { assertEquals(1, e.index) }
    }

    @Test fun aFailedStageLeavesNothingStaged() {
        val v = core()
        assertTrue(v.openDefault())
        try { v.stageRestore { stage -> stage("chats.json", "{}".toByteArray()); throw IllegalStateException("bad") }; fail() } catch (e: IllegalStateException) { }
        assertFalse(File(root, "restore-pending").exists())
    }

    @Test fun restoreOverPutsEverythingBackWhenItFails() {
        val v = core()
        assertTrue(v.openDefault())
        v.setPassword("the old lock")
        while (v.rekeyPending) Thread.sleep(5)
        val secrets = File(root, VaultCore.SECRETS)
        v.write(secrets, "{\"chatPriv\":\"mine\"}")
        val sealed = secrets.readBytes(); val lock = v.keyFile.readBytes()
        try { v.restoreOver(needsLock = true) { throw java.io.IOException("the file went") }; fail() } catch (e: java.io.IOException) { }
        assertArrayEquals(sealed, secrets.readBytes())
        assertArrayEquals(lock, v.keyFile.readBytes())
        assertFalse(root.listFiles()!!.any { it.name.startsWith(VaultCore.KEPT) })
        assertTrue(v.hadLock())
    }

    @Test fun restoreOverStagesWithoutOpeningAndAsksForANewLock() {
        val v = core()
        assertTrue(v.openDefault())
        v.setPassword("the old lock")
        while (v.rekeyPending) Thread.sleep(5)
        v.write(File(root, VaultCore.SECRETS), "{\"chatPriv\":\"mine\"}")
        v.lock()
        assertTrue(v.locked.value)
        val kept = v.restoreOver(needsLock = true) { stage -> stage(VaultCore.SECRETS, "{\"chatPriv\":\"restored\"}".toByteArray()) }
        // Nothing opened on the new vault meanwhile: still shown as locked until the restart.
        assertTrue(v.locked.value)
        assertTrue(kept.exists())
        val after = core()
        assertTrue(after.openDefault())
        assertTrue(after.lockNeeded)
        assertEquals("{\"chatPriv\":\"restored\"}", after.read(File(root, VaultCore.SECRETS)))
    }

    @Test fun keptCopiesAreCapped() {
        val v = core()
        assertTrue(v.openDefault())
        v.write(File(root, VaultCore.SECRETS), "{}")
        repeat(4) { v.keepCopy(); Thread.sleep(3) }
        assertEquals(VaultCore.KEPT_MAX, root.listFiles()!!.count { it.name.startsWith(VaultCore.KEPT) })
    }

    @Test fun hadLockWithoutALockFile() {
        val v = core()
        assertTrue(v.openDefault())
        assertFalse(v.hadLock())
        // No lock file and no open key: there may have been a lock.
        SafeFile.delete(v.keyFile); SafeFile.delete(File(noBackup, "vault-open.key"))
        assertTrue(core().hadLock())
    }
}
