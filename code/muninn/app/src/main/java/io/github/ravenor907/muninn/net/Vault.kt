package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything Muninn keeps (chats, contacts, keys) is encrypted on the phone with one random data
 * key (AES-256-GCM). That data key is itself locked:
 *  - without a lock (Kids' TV, or before one is set), there's no secret to lock it with: it sits in
 *    Muninn's own private files (no_backup), which other apps can't read.
 *  - with a password set, by the password alone (scrypt, N = 2^16, r = 8, p = 1, made in Muninn itself):
 *    without the password nothing is readable, not even by Muninn. Nothing outside the app guards it.
 */
class Vault(private val context: Context) {
    /** The lock file, the data key and the sealed files (see [VaultCore]: the rules that keep saved data safe). */
    val core = VaultCore(context.filesDir, File(context.noBackupFilesDir, "vault-open.key")) { android.util.Log.w("Muninn", "vault: $it") }
    val locked: StateFlow<Boolean> get() = core.locked
    /** Set when the vault can't open for a reason other than a password (see [VaultCore.Trouble]). */
    val trouble: StateFlow<VaultCore.Trouble?> get() = core.trouble

    val hasPassword: Boolean get() = core.hasPassword
    /** The lock is a drawn pattern rather than a typed password. */
    val usesPattern: Boolean get() = core.usesPattern

    /** Opens without a password when none is set. Returns false if a password is needed (or [trouble]). */
    fun openDefault(): Boolean = core.openDefault()
    fun unlock(password: String): Boolean = core.unlock(password)
    /** [password] is the lock's (a password, or a pattern's secret), without changing anything. True when there's no lock. */
    fun check(password: String): Boolean = core.check(password)
    /** The lock just opened is shorter than new locks may be. Only known after an unlock. */
    val weak: Boolean get() = core.weak
    fun lock() = core.lock()
    /** Sets, changes or (with null) removes the password. Must be unlocked. [spare]: a pattern's unlock password. */
    fun setPassword(password: String?, pattern: Boolean = false, spare: String? = null) = core.setPassword(password, pattern, spare)

    // ------------------------------------------------------------------ 1.42: unlock password and wrong tries

    /** A pattern lock with its unlock password set. Either one opens Muninn. */
    val hasSpare: Boolean get() = core.hasSpare
    /** A pattern lock without its unlock password (set before 1.42): it's asked for after the next unlock. */
    val needsSpare: Boolean get() = core.hasPassword && core.usesPattern && !core.hasSpare
    /** The unlock password, asked once more from memory. Changes nothing. */
    fun checkSpare(password: String): Boolean = core.checkSpare(password)
    /** Sets or changes the pattern's unlock password (the pattern stays). Must be unlocked. */
    fun setSpare(password: String): Boolean = core.setSpare(password)

    /**
     * Wrong tries at the lock and their waits ([LockWait]), in the vault's lock-state file (no-backup folder, so never in a
     * backup or a move; Erase everything removes it), mirrored in Muninn's settings (also never in a backup, Backup.phoneOnly).
     */
    val tries: LockStore by lazy {
        val prefs = context.getSharedPreferences("muninn", Context.MODE_PRIVATE)
        val resolver = context.contentResolver
        LockStore(File(context.noBackupFilesDir, "lock-state"), object : LockStore.Clock {
            override fun now() = android.os.SystemClock.elapsedRealtime()
            override fun boot() = try { android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.BOOT_COUNT, 0) } catch (_: Exception) { 0 }
        }, object : LockStore.Mirror {
            override fun read(): LockStore.Data? = if (!prefs.contains("lockFails") && !prefs.contains("lockErase")) null
                else LockStore.Data(prefs.getInt("lockFails", 0), prefs.getLong("lockAt", 0L), prefs.getLong("lockWait", 0L), prefs.getInt("lockBoot", -1),
                    prefs.getBoolean("lockErase", false))
            override fun write(d: LockStore.Data) {
                // commit(), not apply(): on disk before the check runs.
                prefs.edit().putInt("lockFails", d.fails).putLong("lockAt", d.until).putLong("lockWait", d.wait).putInt("lockBoot", d.boot)
                    .putBoolean("lockErase", d.erase).commit()
            }
        })
    }

    // ------------------------------------------------------------------ files

    /** Saved in one safe step, with the last good copy kept. While locked it waits and is written on the next open. */
    fun write(file: File, text: String) = core.write(file, text)
    fun writeBytes(file: File, data: ByteArray) { file.parentFile?.mkdirs(); core.writeBytes(file, data) }
    fun readBytes(file: File): ByteArray? = core.readBytes(file)
    /** Null if missing, locked or damaged. A file that isn't sealed is never taken (another app could have put it there). */
    fun read(file: File): String? = core.read(file)
    /** What was read from [file] couldn't be used: it isn't saved over (so it can't come back empty). */
    fun loadFailed(file: File, why: Throwable? = null) = core.loadFailed(file, why)

    // ------------------------------------------------------------------ backups

    /** True if [file] is one of the vault's encrypted files. */
    fun isSealed(file: File): Boolean = core.isSealed(file)
    /** Every encrypted file Muninn keeps (chats, contacts, keys, photos, voice, sessions), by name relative to its folder. */
    fun sealedFiles(): List<Pair<String, File>> = core.sealedFiles()
    /** Gets a restore ready, swapped in on the next open (see [VaultCore.stageRestore]). Must be unlocked. */
    fun stageRestore(files: List<Pair<String, ByteArray>>) = core.stageRestore(files)
    /** The lock can't be read and the person chose a backup: what's here is set aside (kept) and a new vault starts. */
    fun startOverKeeping(needsLock: Boolean = false): File = core.startOverKeeping(needsLock)
    /** Restored after "Forgot it?": a new lock must be set before anything else shows. */
    val lockNeeded: Boolean get() = core.lockNeeded
    /** Before a restore: a copy of everything here, in a "kept-" folder (see [VaultCore.keepCopy]). */
    fun keepCopy(): File = core.keepCopy()

    /**
     * Erases everything Muninn keeps: chats, contacts, keys, settings, files waiting in the cache (shared in,
     * camera shots, parts of a move), every settings file (moving ones too) and the background work list.
     */
    /** Erase everything and Move: keys kept in memory outside the vault (ring key, day keys, the locked store) go first. */
    @Volatile var onErase: (() -> Unit)? = null

    fun eraseAll() {
        try { onErase?.invoke() } catch (_: Exception) {}
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".xml")) return@forEach
            try { context.getSharedPreferences(f.name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Exception) {}
            try { context.deleteSharedPreferences(f.name.removeSuffix(".xml")) } catch (_: Exception) {}
            f.delete()
        }
        // WorkManager's list of background jobs (the inbox check): it holds no messages, but it shows Muninn was used.
        for (name in listOf("androidx.work.workdb", "androidx.work.workdb-wal", "androidx.work.workdb-shm")) context.getDatabasePath(name).delete()
        context.getDatabasePath("androidx.work.workdb").parentFile?.listFiles()?.forEach { if (it.name.startsWith("androidx.work")) it.delete() }
        context.noBackupFilesDir.listFiles()?.forEach { it.deleteRecursively() }
        core.forget()
    }

    /** The math, separate so it can be tested off the phone. */
    object Crypto {
        val MAGIC = "MUNV1".toByteArray()
        private val random = SecureRandom()

        fun seal(key: ByteArray, plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        fun open(key: SecretKey, data: ByteArray): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) { null }
        fun open(key: ByteArray, data: ByteArray): ByteArray? = open(SecretKeySpec(key, "AES"), data)
        fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded
        /** PBKDF2-HMAC-SHA256 on raw bytes (scrypt's first and last step; one round there). */
        fun pbkdf2(password: ByteArray, salt: ByteArray, rounds: Int, len: Int): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            // HMAC pads the key with zeros, so one zero byte is the same key as none (which SecretKeySpec refuses).
            mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
            val out = ByteArray(len)
            var block = 1
            var at = 0
            while (at < len) {
                mac.update(salt)
                mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
                var u = mac.doFinal()
                val t = u.copyOf()
                for (i in 1 until rounds) { u = mac.doFinal(u); for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte() }
                System.arraycopy(t, 0, out, at, minOf(32, len - at))
                at += 32; block++
            }
            return out
        }

        /** scrypt (RFC 7914), here in Muninn so nothing else is needed. n must be a power of two. Call off the main thread. */
        fun scrypt(password: ByteArray, salt: ByteArray, n: Int, r: Int, p: Int, len: Int = 32): ByteArray {
            require(n > 1 && (n and (n - 1)) == 0 && r > 0 && p > 0)
            val b = pbkdf2(password, salt, 1, p * 128 * r)
            val w = 32 * r
            val x = IntArray(w); val y = IntArray(w); val v = IntArray(w * n); val s = IntArray(16); val t = IntArray(16)
            for (k in 0 until p) {
                val base = k * 128 * r
                for (i in 0 until w) { val o = base + i * 4; x[i] = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or ((b[o + 2].toInt() and 255) shl 16) or ((b[o + 3].toInt() and 255) shl 24) }
                for (i in 0 until n) { System.arraycopy(x, 0, v, i * w, w); blockMix(x, y, r, s, t) }
                for (i in 0 until n) {
                    val j = x[(2 * r - 1) * 16] and (n - 1)
                    val off = j * w
                    for (m in 0 until w) x[m] = x[m] xor v[off + m]
                    blockMix(x, y, r, s, t)
                }
                for (i in 0 until w) { val o = base + i * 4; b[o] = x[i].toByte(); b[o + 1] = (x[i] ushr 8).toByte(); b[o + 2] = (x[i] ushr 16).toByte(); b[o + 3] = (x[i] ushr 24).toByte() }
            }
            v.fill(0)
            return pbkdf2(password, b, 1, len).also { b.fill(0) }
        }

        private fun blockMix(b: IntArray, y: IntArray, r: Int, s: IntArray, t: IntArray) {
            System.arraycopy(b, (2 * r - 1) * 16, t, 0, 16)
            for (i in 0 until 2 * r) {
                for (j in 0 until 16) t[j] = t[j] xor b[i * 16 + j]
                salsa8(t, s)
                System.arraycopy(t, 0, y, (if (i % 2 == 0) i / 2 else r + i / 2) * 16, 16)
            }
            System.arraycopy(y, 0, b, 0, 32 * r)
        }

        private fun rl(a: Int, n: Int) = (a shl n) or (a ushr (32 - n))

        /** Salsa20/8 on the 16 words of [b], in place. */
        private fun salsa8(b: IntArray, x: IntArray) {
            System.arraycopy(b, 0, x, 0, 16)
            for (i in 0 until 4) {
                x[4] = x[4] xor rl(x[0] + x[12], 7); x[8] = x[8] xor rl(x[4] + x[0], 9); x[12] = x[12] xor rl(x[8] + x[4], 13); x[0] = x[0] xor rl(x[12] + x[8], 18)
                x[9] = x[9] xor rl(x[5] + x[1], 7); x[13] = x[13] xor rl(x[9] + x[5], 9); x[1] = x[1] xor rl(x[13] + x[9], 13); x[5] = x[5] xor rl(x[1] + x[13], 18)
                x[14] = x[14] xor rl(x[10] + x[6], 7); x[2] = x[2] xor rl(x[14] + x[10], 9); x[6] = x[6] xor rl(x[2] + x[14], 13); x[10] = x[10] xor rl(x[6] + x[2], 18)
                x[3] = x[3] xor rl(x[15] + x[11], 7); x[7] = x[7] xor rl(x[3] + x[15], 9); x[11] = x[11] xor rl(x[7] + x[3], 13); x[15] = x[15] xor rl(x[11] + x[7], 18)
                x[1] = x[1] xor rl(x[0] + x[3], 7); x[2] = x[2] xor rl(x[1] + x[0], 9); x[3] = x[3] xor rl(x[2] + x[1], 13); x[0] = x[0] xor rl(x[3] + x[2], 18)
                x[6] = x[6] xor rl(x[5] + x[4], 7); x[7] = x[7] xor rl(x[6] + x[5], 9); x[4] = x[4] xor rl(x[7] + x[6], 13); x[5] = x[5] xor rl(x[4] + x[7], 18)
                x[11] = x[11] xor rl(x[10] + x[9], 7); x[8] = x[8] xor rl(x[11] + x[10], 9); x[9] = x[9] xor rl(x[8] + x[11], 13); x[10] = x[10] xor rl(x[9] + x[8], 18)
                x[12] = x[12] xor rl(x[15] + x[14], 7); x[13] = x[13] xor rl(x[12] + x[15], 9); x[14] = x[14] xor rl(x[13] + x[12], 13); x[15] = x[15] xor rl(x[14] + x[13], 18)
            }
            for (i in 0 until 16) b[i] += x[i]
        }

        fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
        fun b64d(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    }

    /** How long a new lock must be. A shorter lock keeps working, with one suggestion. */
    object LockRules {
        const val MIN = 6
        /** "pattern:0,3,6,..." counts dots; a password counts characters. */
        fun strong(secret: String): Boolean =
            if (secret.startsWith("pattern:")) secret.removePrefix("pattern:").split(',').count { it.isNotEmpty() } >= MIN
            else secret.length >= MIN
    }

    companion object {
        /** scrypt for new locks: 64 MiB and about a second on a low-end phone (2^17 would be 128 MiB and about twice as long). */
        const val SCRYPT_N = 1 shl 16
        const val SCRYPT_R = 8
        const val SCRYPT_P = 1
        /** Where a restore waits for the next open. */
        const val STAGE = "restore-pending"
    }
}
