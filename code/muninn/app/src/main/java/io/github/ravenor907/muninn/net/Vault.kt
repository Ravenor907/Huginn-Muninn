package io.github.ravenor907.muninn.net

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
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
 *    Muninn's own private files (no_backup), which other apps can't read. A vault wrapped by Android's
 *    hardware key store is unwrapped and stored this way on its next open.
 *  - with a password set, by the password alone (scrypt, N = 2^16, r = 8, p = 1, made in Muninn itself):
 *    without the password nothing is readable, not even by Muninn. Nothing outside the app guards it.
 *    PBKDF2 locks, and locks also wrapped with the hardware key, open too and are stored with scrypt alone
 *    on that unlock.
 */
class Vault(private val context: Context) {
    private val keyFile = File(context.filesDir, "vault.key")
    /** The data key of a vault without a lock ("mode": "open"). Never part of a backup, and erased with everything else. */
    private val openKeyFile = File(context.noBackupFilesDir, "vault-open.key")
    @Volatile private var dataKey: ByteArray? = null
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    val hasPassword: Boolean get() = read()?.optString("mode") == "password"
    /** The lock is a drawn pattern rather than a typed password. */
    val usesPattern: Boolean get() = read()?.optString("kind") == "pattern"

    /** Opens without a password when none is set. Returns false if a password is needed. */
    fun openDefault(): Boolean {
        if (dataKey != null) return true
        val o = read()
        if (o == null) {
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            store(k, null)
            dataKey = k
        } else if (o.optString("mode") == "open") {
            val k = try { openKeyFile.readBytes() } catch (_: Exception) { null }
            if (k == null || k.size != 32) return false
            dataKey = k
        } else if (o.optString("mode") == "keystore") {
            // Wrapped by the hardware key store: unwrapped once, stored as an open vault, and the store's key
            // deleted. If it can't be unwrapped, nothing is changed (the caller says so).
            val ks = oldSystemKey() ?: return false
            val k = Crypto.open(ks, Crypto.b64d(o.getString("wrapped"))) ?: return false
            try { store(k, null); dropSystemKey() } catch (_: Exception) {}
            dataKey = k
        } else {
            return false
        }
        applyRestore()
        _locked.value = false
        return true
    }

    fun unlock(password: String): Boolean {
        val o = read() ?: return openDefault()
        if (o.optString("mode") != "password") return openDefault()
        val salt = Crypto.b64d(o.getString("salt"))
        val scrypt = o.optString("kdf") == "scrypt"
        val kek = if (scrypt) Crypto.scrypt(password.toByteArray(), salt, o.optInt("N", SCRYPT_N), o.optInt("r", SCRYPT_R), o.optInt("p", SCRYPT_P))
            else Crypto.derive(password, salt, o.optInt("rounds", ROUNDS))
        val wasClosed = dataKey == null
        // "hw": 1: the lock is also wrapped with the hardware key.
        val hw = o.optInt("hw", 0) == 1
        val wrapped = Crypto.b64d(o.getString("wrapped"))
        val inner = if (hw) oldSystemKey()?.let { Crypto.open(it, wrapped) } ?: return false else wrapped
        val k = Crypto.open(kek, inner) ?: return false
        dataKey = k
        // A PBKDF2 lock, or one with the hardware key around it: store it again with scrypt alone, since the key is known.
        if (!scrypt || hw) try { store(k, password, o.optString("kind") == "pattern"); if (hw) dropSystemKey() } catch (_: Exception) {}
        weak = !LockRules.strong(password)
        if (wasClosed) applyRestore()   // only on a fresh open, before chats load
        _locked.value = false
        return true
    }

    /** The lock just opened is shorter than new locks may be. Only known after an unlock. */
    @Volatile var weak = false
        private set

    fun lock() {
        if (!hasPassword) return   // without a password there's nothing to lock with
        dataKey?.fill(0)
        dataKey = null
        _locked.value = true
    }

    /** Sets, changes or (with null) removes the password. Must be unlocked. */
    fun setPassword(password: String?, pattern: Boolean = false) {
        val k = dataKey ?: return
        store(k, password, pattern)
        if (!password.isNullOrEmpty()) weak = !LockRules.strong(password)
    }

    private fun store(k: ByteArray, password: String?, pattern: Boolean = false) {
        val o = JSONObject()
        if (password.isNullOrEmpty()) {
            // No secret to lock it with: the key in Muninn's private files, written before vault.key points to it.
            openKeyFile.parentFile?.mkdirs()
            val t = File(openKeyFile.path + ".tmp")
            t.writeBytes(k)
            if (!t.renameTo(openKeyFile)) throw java.io.IOException("couldn't keep the key")
            o.put("mode", "open")
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            // The password's lock only, stretched with scrypt (slow and memory-hungry, so guessing is too). Takes about a second.
            o.put("mode", "password").put("kind", if (pattern) "pattern" else "text").put("salt", Crypto.b64(salt))
                .put("kdf", "scrypt").put("N", SCRYPT_N).put("r", SCRYPT_R).put("p", SCRYPT_P)
                .put("wrapped", Crypto.b64(Crypto.seal(Crypto.scrypt(password.toByteArray(), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P), k)))
        }
        val tmp = File(keyFile.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(keyFile)) throw java.io.IOException("couldn't save the lock")
        // Locked by a password now: the open key must not stay behind.
        if (!password.isNullOrEmpty()) openKeyFile.delete()
    }

    private fun read(): JSONObject? = try { JSONObject(keyFile.readText()) } catch (e: Exception) { null }

    // ------------------------------------------------------------------ files

    fun write(file: File, text: String) {
        val k = dataKey ?: throw IllegalStateException("Muninn is locked")
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(Crypto.MAGIC + Crypto.seal(k, text.toByteArray()))
        tmp.renameTo(file)
    }

    fun writeBytes(file: File, data: ByteArray) {
        val k = dataKey ?: throw IllegalStateException("Muninn is locked")
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(Crypto.MAGIC + Crypto.seal(k, data))
        tmp.renameTo(file)
    }

    fun readBytes(file: File): ByteArray? {
        if (!file.exists()) return null
        val k = dataKey ?: return null
        val b = file.readBytes()
        if (b.size < Crypto.MAGIC.size || !b.copyOfRange(0, Crypto.MAGIC.size).contentEquals(Crypto.MAGIC)) return null
        return Crypto.open(k, b.copyOfRange(Crypto.MAGIC.size, b.size))
    }

    /** Null if missing, locked or damaged. A file that isn't sealed is never taken (another app could have put it there). */
    fun read(file: File): String? {
        if (!file.exists()) return null
        val k = dataKey ?: return null
        val b = file.readBytes()
        if (b.size < Crypto.MAGIC.size || !b.copyOfRange(0, Crypto.MAGIC.size).contentEquals(Crypto.MAGIC)) return null
        return Crypto.open(k, b.copyOfRange(Crypto.MAGIC.size, b.size))?.let { String(it) }
    }

    // ------------------------------------------------------------------ backups

    /** True if [file] is one of the vault's encrypted files. */
    fun isSealed(file: File): Boolean = try {
        file.isFile && file.length() >= Crypto.MAGIC.size &&
            file.inputStream().use { s -> ByteArray(Crypto.MAGIC.size).also { s.read(it) } }.contentEquals(Crypto.MAGIC)
    } catch (e: Exception) { false }

    /** Every encrypted file Muninn keeps (chats, contacts, keys, photos, voice, sessions), by name relative to its folder. */
    fun sealedFiles(): List<Pair<String, File>> {
        val root = context.filesDir
        return root.walkTopDown().onEnter { it == root || it.name != STAGE && !(it.parentFile == root && it.name == "tls") }
            .filter { it.isFile && it.name != keyFile.name && !it.name.endsWith(".tmp") && isSealed(it) }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
    }

    /**
     * Gets a restore ready: each file is encrypted with this phone's key into a waiting folder. Nothing
     * running is touched; the swap happens on the next open (see [applyRestore]), before chats load,
     * so nothing still in memory can write over it. Must be unlocked.
     */
    fun stageRestore(files: List<Pair<String, ByteArray>>) {
        val k = dataKey ?: throw IllegalStateException("Muninn is locked")
        val dir = File(context.filesDir, STAGE)
        dir.deleteRecursively(); dir.mkdirs()
        val names = ArrayList<String>()
        for ((name, data) in files) {
            if (!BackupFormat.safeName(name) || name == keyFile.name || name == DONE || name.substringBefore('/') in setOf(STAGE, "tls")) continue
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            f.writeBytes(Crypto.MAGIC + Crypto.seal(k, data))
            names += name
        }
        // Written last: without it the folder is an unfinished restore and is thrown away.
        File(dir, DONE).writeText(names.joinToString("\n"))
    }

    /** Swaps in a staged restore. Safe to repeat if it's interrupted. */
    private fun applyRestore() {
        val dir = File(context.filesDir, STAGE)
        if (!dir.exists()) return
        val done = File(dir, DONE)
        if (!done.exists()) { dir.deleteRecursively(); return }
        val names = done.readLines().filter { it.isNotEmpty() }.toSet()
        try {
            sealedFiles().forEach { (name, f) -> if (name !in names) f.delete() }
            for (name in names) {
                val src = File(dir, name)
                if (!src.exists()) continue
                val dst = File(context.filesDir, name)
                dst.parentFile?.mkdirs()
                if (!src.renameTo(dst)) { dst.delete(); src.renameTo(dst) }
            }
            dir.deleteRecursively()
        } catch (_: Exception) {}
    }

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
        dropSystemKey()   // any hardware key store key
        dataKey?.fill(0)
        dataKey = null
    }

    /** The hardware key store's key, only to open a vault it wrapped. Never made anew. */
    private fun oldSystemKey(): SecretKey? = try {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(ALIAS, null) as? SecretKey
    } catch (_: Exception) { null }

    private fun dropSystemKey() {
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
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
        private const val ALIAS = "muninn-vault"
        private const val ROUNDS = 310_000   // PBKDF2 locks
        /** scrypt for new locks: 64 MiB and about a second on a low-end phone (2^17 would be 128 MiB and about twice as long). */
        const val SCRYPT_N = 1 shl 16
        const val SCRYPT_R = 8
        const val SCRYPT_P = 1
        /** Where a restore waits for the next open. */
        const val STAGE = "restore-pending"
        private const val DONE = ".done"
    }
}
