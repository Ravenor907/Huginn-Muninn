package io.github.ravenor907.muninn.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The vault without the phone around it (see [Vault]): the lock file, the data key and every sealed file under
 * [root]. Kept apart so the rules that guard saved data can be tested off the phone:
 *  - a new data key is made only on a true first run (no lock file, no copy of it, nothing sealed). A lock file
 *    that is there but can't be read, or missing while sealed files are, stops at [trouble] and changes nothing.
 *  - a file that is there but can't be read (both copies) is never saved over, so it can't come back empty.
 *  - what's saved while locked waits in memory and is written once the vault opens again.
 */
class VaultCore(
    private val root: File,
    /** The data key of a vault without a lock ("mode": "open"). */
    private val openKeyFile: File,
    private val log: (String) -> Unit = { System.err.println("Vault: $it") },
) {
    val keyFile = File(root, "vault.key")
    private val keyLock = Any()
    @Volatile private var dataKey: ByteArray? = null
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    /** Why the vault can't open, when it's not just a password that's needed. */
    enum class Trouble {
        /** The lock file is damaged, can't be read, or is missing while saved data is there. */
        KEY,
        /** The lock opened but the keys file (secrets.json) can't be read with it. */
        DATA,
    }
    private val _trouble = MutableStateFlow<Trouble?>(null)
    val trouble: StateFlow<Trouble?> = _trouble.asStateFlow()

    /** Files that are there but couldn't be read (or a caller couldn't make sense of): never saved over. */
    private val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Files read or written fine with this key: safe to keep as the last good copy. */
    private val good: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Saves made while locked, newest per file, written once the vault opens. */
    private class Pending(val file: File, val data: ByteArray, val bak: Boolean)
    private val pending = LinkedHashMap<String, Pending>()

    /** Refused: [name] didn't load, so saving would lose what's in it. */
    class Refused(name: String) : IOException("$name didn't load, so it isn't saved over")

    // ------------------------------------------------------------------ the lock file

    private fun readKey(): SafeFile.Read<JSONObject> = SafeFile.read(keyFile, bakWhenMissing = true) { b ->
        JSONObject(String(b)).takeIf { it.optString("mode") == "open" || it.optString("mode") == "password" }
    }

    private fun lockJson(): JSONObject? = (readKey() as? SafeFile.Read.Ok)?.value

    val hasPassword: Boolean get() = lockJson()?.optString("mode") == "password"
    val usesPattern: Boolean get() = lockJson()?.optString("kind") == "pattern"

    /** Anything sealed (or the open key) is here: a missing lock file is then trouble, not a first run. */
    fun hasData(): Boolean {
        if (SafeFile.exists(openKeyFile)) return true
        if (!root.exists()) return false
        return root.walkTopDown().onEnter { it == root || !(it.parentFile == root && (it.name.startsWith(KEPT) || it.name == "tls")) }
            .any { it.isFile && it.name != keyFile.name && !it.name.startsWith(keyFile.name + ".") && isSealed(it) }
    }

    /** Opens without a password when none is set. False if a password is needed or there's [trouble]. */
    @Synchronized
    fun openDefault(): Boolean {
        if (dataKey != null) return true
        val k: ByteArray
        when (val r = readKey()) {
            is SafeFile.Read.Missing -> {
                if (hasData()) return trouble(Trouble.KEY, "vault.key is missing but saved data is here; no new key is made")
                // A true first run: nothing to lose.
                k = ByteArray(32).also { SecureRandom().nextBytes(it) }
                store(k, null)
            }
            is SafeFile.Read.Bad -> return trouble(Trouble.KEY, "vault.key can't be read; no new key is made")
            is SafeFile.Read.Ok -> {
                if (r.fromBak) fixKeyFile()
                if (r.value.optString("mode") != "open") { _trouble.value = null; return false }
                val kr = SafeFile.read(openKeyFile, bakWhenMissing = true) { b -> b.takeIf { it.size == 32 } }
                if (kr !is SafeFile.Read.Ok) return trouble(Trouble.KEY, "the open key can't be read; no new key is made")
                if (kr.fromBak) try { SafeFile.repair(openKeyFile) } catch (e: Exception) { log("couldn't repair the open key: ${e.message}") }
                k = kr.value
            }
        }
        return opened(k)
    }

    fun unlock(password: String): Boolean {
        val o = (readKey() as? SafeFile.Read.Ok)?.value ?: return openDefault()
        if (o.optString("mode") != "password") return openDefault()
        if (o.optString("kdf") != "scrypt") return false
        val salt = Vault.Crypto.b64d(o.getString("salt"))
        val kek = Vault.Crypto.scrypt(password.toByteArray(), salt, o.optInt("N", Vault.SCRYPT_N), o.optInt("r", Vault.SCRYPT_R), o.optInt("p", Vault.SCRYPT_P))
        val k = Vault.Crypto.open(kek, Vault.Crypto.b64d(o.getString("wrapped"))) ?: return false
        synchronized(this) {
            if (dataKey == null && !opened(k)) return false
            weak = !Vault.LockRules.strong(password)
        }
        return true
    }

    /** [password] is the lock's, without changing anything. True when there's no lock. */
    fun check(password: String): Boolean {
        val o = lockJson() ?: return true
        if (o.optString("mode") != "password") return true
        if (o.optString("kdf") != "scrypt") return false
        val salt = Vault.Crypto.b64d(o.getString("salt"))
        val kek = Vault.Crypto.scrypt(password.toByteArray(), salt, o.optInt("N", Vault.SCRYPT_N), o.optInt("r", Vault.SCRYPT_R), o.optInt("p", Vault.SCRYPT_P))
        return Vault.Crypto.open(kek, Vault.Crypto.b64d(o.getString("wrapped")))?.also { it.fill(0) } != null
    }

    /** The lock just opened is shorter than new locks may be. Only known after an unlock. */
    @Volatile var weak = false
        private set

    /** The data key is in hand: checked against the keys file, then the vault opens and waiting saves go out. */
    private fun opened(k: ByteArray): Boolean {
        val secrets = File(root, SECRETS)
        if (SafeFile.read(secrets) { b -> openSealed(k, b) } is SafeFile.Read.Bad)
            return trouble(Trouble.DATA, "secrets.json can't be read with this key; nothing is changed")
        synchronized(keyLock) { dataKey = k }
        _trouble.value = null
        applyRestore()
        flush()
        _locked.value = false
        return true
    }

    private fun trouble(t: Trouble, why: String): Boolean {
        log(why)
        _trouble.value = t
        return false
    }

    private fun fixKeyFile() {
        try { SafeFile.repair(keyFile) } catch (e: Exception) { log("couldn't repair vault.key: ${e.message}") }
    }

    fun lock() {
        if (!hasPassword) return   // without a password there's nothing to lock with
        synchronized(keyLock) { dataKey?.fill(0); dataKey = null }
        _locked.value = true
    }

    /** Sets, changes or (with null) removes the password. Must be unlocked. */
    fun setPassword(password: String?, pattern: Boolean = false) {
        val k = key() ?: return
        try { store(k, password, pattern) } finally { k.fill(0) }
        if (!password.isNullOrEmpty()) weak = !Vault.LockRules.strong(password)
    }

    private fun store(k: ByteArray, password: String?, pattern: Boolean = false) {
        val o = JSONObject()
        if (password.isNullOrEmpty()) {
            // No secret to lock it with: the key in Muninn's private files, written before vault.key points to it.
            SafeFile.writeMirror(openKeyFile, k)
            o.put("mode", "open")
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            // The password's lock only, stretched with scrypt (slow and memory-hungry, so guessing is too). Takes about a second.
            o.put("mode", "password").put("kind", if (pattern) "pattern" else "text").put("salt", Vault.Crypto.b64(salt))
                .put("kdf", "scrypt").put("N", Vault.SCRYPT_N).put("r", Vault.SCRYPT_R).put("p", Vault.SCRYPT_P)
                .put("wrapped", Vault.Crypto.b64(Vault.Crypto.seal(Vault.Crypto.scrypt(password.toByteArray(), salt, Vault.SCRYPT_N, Vault.SCRYPT_R, Vault.SCRYPT_P), k)))
        }
        // Two copies of the same lock, never an older one: an old password must not open it.
        SafeFile.writeMirror(keyFile, o.toString().toByteArray())
        // Locked by a password now: the open key must not stay behind.
        if (!password.isNullOrEmpty()) SafeFile.delete(openKeyFile)
    }

    /**
     * Restore from backup when the lock can't be read: everything here is set aside (kept, not deleted, in a
     * "kept-" folder) and a new open vault starts, ready for [stageRestore]. Only on the person's say-so.
     */
    @Synchronized
    fun startOverKeeping(): File {
        val kept = File(root, KEPT + System.currentTimeMillis())
        kept.mkdirs()
        root.listFiles()?.forEach { f ->
            if (f == kept || f.name.startsWith(KEPT) || f.name == "tls") return@forEach
            if (!f.renameTo(File(kept, f.name))) throw IOException("couldn't set ${f.name} aside")
        }
        for (f in listOf(openKeyFile, SafeFile.bakOf(openKeyFile))) if (f.exists() && !f.renameTo(File(kept, "open-" + f.name))) throw IOException("couldn't set the open key aside")
        unreadable.clear(); good.clear(); synchronized(pending) { pending.clear() }
        val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
        store(k, null)
        synchronized(keyLock) { dataKey = k }
        _trouble.value = null
        _locked.value = false
        log("set everything aside in ${kept.name} and started a new vault")
        return kept
    }

    /**
     * Before a restore replaces everything: a copy of every file here (the lock file and open key too, so the copy
     * opens as it did) goes into a new "kept-" folder. Nothing here changes. Throws if the copy can't be made.
     */
    @Synchronized
    fun keepCopy(): File {
        val kept = File(root, KEPT + System.currentTimeMillis())
        kept.mkdirs()
        try {
            root.listFiles()?.forEach { f ->
                if (f == kept || f.name.startsWith(KEPT) || f.name == "tls" || f.name == Vault.STAGE) return@forEach
                f.copyRecursively(File(kept, f.name), overwrite = true)
            }
            for (f in listOf(openKeyFile, SafeFile.bakOf(openKeyFile))) if (f.exists()) f.copyTo(File(kept, "open-" + f.name), overwrite = true)
        } catch (e: Exception) {
            kept.deleteRecursively()
            throw IOException("couldn't keep a copy: ${e.message}")
        }
        log("kept a copy of everything in ${kept.name} before a restore")
        return kept
    }

    /** Forgets the key and everything about the files (Erase everything). */
    fun forget() {
        synchronized(keyLock) { dataKey?.fill(0); dataKey = null }
        unreadable.clear(); good.clear(); synchronized(pending) { pending.clear() }
        _trouble.value = null
    }

    // ------------------------------------------------------------------ files

    /** A copy of the data key (null while locked), so locking meanwhile can't change it under a write. */
    private fun key(): ByteArray? = synchronized(keyLock) { dataKey?.copyOf() }

    private fun openSealed(k: ByteArray, b: ByteArray): ByteArray? {
        if (b.size < Vault.Crypto.MAGIC.size || !b.copyOfRange(0, Vault.Crypto.MAGIC.size).contentEquals(Vault.Crypto.MAGIC)) return null
        return Vault.Crypto.open(k, b.copyOfRange(Vault.Crypto.MAGIC.size, b.size))
    }

    fun write(file: File, text: String) = put(file, text.toByteArray(), bak = true)

    /** Photos, voice and other big one-off files: written the same safe way, without a last good copy. */
    fun writeBytes(file: File, data: ByteArray) = put(file, data, bak = false)

    private fun put(file: File, plain: ByteArray, bak: Boolean) {
        val path = file.absolutePath
        if (path in unreadable) {
            log("not saving ${file.name}: it didn't load")
            throw Refused(file.name)
        }
        val k = key()
        if (k == null) {
            // Locked: kept until the vault opens again, never dropped.
            synchronized(pending) { pending.remove(path); pending[path] = Pending(file, plain.copyOf(), bak) }
            return
        }
        try { seal(k, file, plain, bak) } finally { k.fill(0) }
    }

    private fun seal(k: ByteArray, file: File, plain: ByteArray, bak: Boolean) {
        val path = file.absolutePath
        // Only a file known to be good becomes the last good copy.
        val keep = bak && file.exists() && (path in good || (try { openSealed(k, file.readBytes()) } catch (_: Exception) { null }) != null)
        SafeFile.write(file, Vault.Crypto.MAGIC + Vault.Crypto.seal(k, plain), keep)
        good.add(path)
    }

    /** Writes what was saved while locked. */
    private fun flush() {
        val k = key() ?: return
        try {
            val all = synchronized(pending) { pending.values.toList().also { pending.clear() } }
            for (p in all) {
                val path = p.file.absolutePath
                if (path in unreadable) { log("not saving ${p.file.name}: it didn't load"); continue }
                try { seal(k, p.file, p.data, p.bak) } catch (e: Exception) {
                    log("couldn't save ${p.file.name}: ${e.message}")
                    // Kept for the next try, unless a newer save came meanwhile.
                    synchronized(pending) { if (path !in pending) pending[path] = p }
                }
            }
        } finally { k.fill(0) }
    }

    /** How many saves wait for the vault to open. */
    fun waiting(): Int = synchronized(pending) { pending.size }

    fun readBytes(file: File): ByteArray? = get(file)

    /** Null if missing, locked or damaged. A file that isn't sealed is never taken (another app could have put it there). */
    fun read(file: File): String? = get(file)?.let { String(it) }

    private fun get(file: File): ByteArray? {
        val path = file.absolutePath
        synchronized(pending) { pending[path]?.let { return it.data.copyOf() } }
        val k = key()
        if (k == null) {
            // Locked: whatever reads this now sees nothing, so it mustn't save over it until it has read it for real.
            if (file.exists()) unreadable.add(path)
            return null
        }
        try {
            return when (val r = SafeFile.read(file) { b -> openSealed(k, b) }) {
                is SafeFile.Read.Missing -> null
                is SafeFile.Read.Ok -> {
                    unreadable.remove(path)
                    if (r.fromBak) { good.remove(path); log("${file.name} couldn't be read; using its last good copy") } else good.add(path)
                    r.value
                }
                is SafeFile.Read.Bad -> {
                    unreadable.add(path)
                    log("${file.name} couldn't be read; it won't be saved over")
                    null
                }
            }
        } finally { k.fill(0) }
    }

    /** What was read from [file] couldn't be used (a store couldn't make sense of it): it isn't saved over. */
    fun loadFailed(file: File, why: Throwable? = null) {
        unreadable.add(file.absolutePath)
        log("${file.name} didn't load (${why?.javaClass?.simpleName ?: "unreadable"}); it won't be saved over")
    }

    /** [file] can be saved (it loaded, or was never there). */
    fun loadedOk(file: File): Boolean = file.absolutePath !in unreadable

    // ------------------------------------------------------------------ backups

    /** True if [file] is one of the vault's encrypted files. */
    fun isSealed(file: File): Boolean = try {
        file.isFile && file.length() >= Vault.Crypto.MAGIC.size &&
            file.inputStream().use { s -> ByteArray(Vault.Crypto.MAGIC.size).also { s.read(it) } }.contentEquals(Vault.Crypto.MAGIC)
    } catch (e: Exception) { false }

    /** Every encrypted file Muninn keeps, by name relative to [root]. Last good copies and half-written files aren't. */
    fun sealedFiles(): List<Pair<String, File>> =
        root.walkTopDown().onEnter { it == root || it.name != Vault.STAGE && !(it.parentFile == root && (it.name == "tls" || it.name.startsWith(KEPT))) }
            .filter { it.isFile && it.name != keyFile.name && !it.name.endsWith(SafeFile.TMP) && !it.name.endsWith(SafeFile.BAK) && isSealed(it) }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()

    /**
     * Gets a restore ready: each file is encrypted with this phone's key into a waiting folder. Nothing
     * running is touched; the swap happens on the next open (see [applyRestore]), before chats load,
     * so nothing still in memory can write over it. Must be unlocked.
     */
    fun stageRestore(files: List<Pair<String, ByteArray>>) {
        val k = key() ?: throw IllegalStateException("Muninn is locked")
        try {
            val dir = File(root, Vault.STAGE)
            dir.deleteRecursively(); dir.mkdirs()
            val names = ArrayList<String>()
            for ((name, data) in files) {
                if (!BackupFormat.safeName(name) || name == keyFile.name || name == DONE || name.substringBefore('/') in setOf(Vault.STAGE, "tls") ||
                    name.substringBefore('/').startsWith(KEPT)) continue
                val f = File(dir, name)
                SafeFile.write(f, Vault.Crypto.MAGIC + Vault.Crypto.seal(k, data), keepBak = false)
                names += name
            }
            // Written last: without it the folder is an unfinished restore and is thrown away.
            SafeFile.write(File(dir, DONE), names.joinToString("\n").toByteArray(), keepBak = false)
        } finally { k.fill(0) }
    }

    /** Swaps in a staged restore. Safe to repeat if it's interrupted. */
    private fun applyRestore() {
        val dir = File(root, Vault.STAGE)
        if (!dir.exists()) return
        val done = File(dir, DONE)
        if (!done.exists()) { dir.deleteRecursively(); return }
        val names = done.readLines().filter { it.isNotEmpty() }.toSet()
        try {
            sealedFiles().forEach { (name, f) -> if (name !in names) f.delete() }
            // Last good copies of what was here before don't belong to the restored files.
            root.walkTopDown().onEnter { it == root || it.name != Vault.STAGE && !(it.parentFile == root && it.name.startsWith(KEPT)) }
                .filter { it.isFile && it.name.endsWith(SafeFile.BAK) && it.name != keyFile.name + SafeFile.BAK }.forEach { it.delete() }
            for (name in names) {
                val src = File(dir, name)
                if (!src.exists()) continue
                val dst = File(root, name)
                dst.parentFile?.mkdirs()
                if (!src.renameTo(dst)) { dst.delete(); src.renameTo(dst) }
            }
            dir.deleteRecursively()
            unreadable.clear(); good.clear()
        } catch (e: Exception) { log("restore: ${e.message}") }
    }

    companion object {
        const val SECRETS = "secrets.json"
        /** Folders of files set aside by [startOverKeeping]. */
        const val KEPT = "kept-"
        private const val DONE = ".done"
    }
}
