package io.github.ravenor907.muninn.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Every file Muninn keeps is written the same careful way: to a temporary file in the same folder, flushed to
 * the disk, then moved over the old one in one step, so a crash or a full disk leaves the old file whole.
 * The last good version stays beside it as "name.bak" and is read when the file itself can't be.
 * One writer at a time per file. Plain JVM, so it can be tested off the phone.
 */
object SafeFile {
    const val BAK = ".bak"
    const val TMP = ".tmp"

    /** What a read found. */
    sealed class Read<out T> {
        /** Neither the file nor its last good copy is there. */
        object Missing : Read<Nothing>()
        /** Read; [fromBak] when the file itself couldn't be and its last good copy was used. */
        data class Ok<T>(val value: T, val fromBak: Boolean) : Read<T>()
        /** There, but neither copy could be read (damaged, or the disk refused). */
        object Bad : Read<Nothing>()
    }

    private val locks = ConcurrentHashMap<String, Any>()

    /** The lock for [file]: every read and write of it (and its .bak) holds it. */
    fun lockFor(file: File): Any = locks.computeIfAbsent(file.absolutePath) { Any() }

    fun bakOf(file: File) = File(file.path + BAK)
    fun tmpOf(file: File) = File(file.path + TMP)

    /** The file or its last good copy is there (the lock file, which a missing copy can stand in for). */
    fun exists(file: File): Boolean = file.exists() || bakOf(file).exists()

    /** Test hook: called halfway through writing a temporary file (throw to act out a crash). */
    @Volatile internal var midWrite: ((File) -> Unit)? = null

    /**
     * Reads [file], or its last good copy when [parse] can't take it (null or a throw). A missing file is
     * Missing (it was deleted on purpose: a write never leaves it missing), unless [bakWhenMissing].
     */
    fun <T> read(file: File, bakWhenMissing: Boolean = false, parse: (ByteArray) -> T?): Read<T> = synchronized(lockFor(file)) {
        var there = false
        val main = try { file.exists() } catch (_: Exception) { true }
        if (!main && !bakWhenMissing) return Read.Missing
        for (f in listOf(file, bakOf(file))) {
            val present = if (f === file) main else try { f.exists() } catch (_: Exception) { true }
            if (!present) continue
            there = true
            val v = try { parse(f.readBytes()) } catch (_: Exception) { null }
            if (v != null) return Read.Ok(v, f !== file)
        }
        if (there) Read.Bad else Read.Missing
    }

    /**
     * Writes [data] to [file] in one step. With [keepBak] the file being replaced becomes the last good copy
     * (pass false when that file isn't known to be good, so a good .bak isn't replaced by a damaged one).
     */
    fun write(file: File, data: ByteArray, keepBak: Boolean = true): Unit = put(file, data, keepBak, dropStaleBak = true)

    private fun put(file: File, data: ByteArray, keepBak: Boolean, dropStaleBak: Boolean): Unit = synchronized(lockFor(file)) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("no folder for ${file.name}")
        dir.mkdirs()
        val tmp = tmpOf(file)
        writeSynced(tmp, data)
        val bak = bakOf(file)
        if (!file.exists()) { if (dropStaleBak) bak.delete() }   // a copy from before the file was deleted isn't its last good version
        else if (keepBak) {
            // The file stays in place the whole time: the copy is a second name for it (or a copy where links aren't allowed).
            try { keepCopy(file, bak) } catch (e: Exception) { tmp.delete(); throw e }
        }
        if (!tmp.renameTo(file)) { tmp.delete(); throw IOException("couldn't save ${file.name}") }
        syncDir(dir)
    }

    private fun keepCopy(file: File, bak: File) {
        val next = File(bak.path + TMP)
        next.delete()
        try {
            java.nio.file.Files.createLink(next.toPath(), file.toPath())
        } catch (_: Exception) {
            next.delete()
            writeSynced(next, file.readBytes())
        }
        if (!next.renameTo(bak)) { next.delete(); throw IOException("couldn't keep the last copy of ${file.name}") }
    }

    /** Writes [data] to [file] and the same to its .bak: two good copies (the lock file, where an older copy must not linger). */
    fun writeMirror(file: File, data: ByteArray): Unit = synchronized(lockFor(file)) {
        put(file, data, keepBak = false, dropStaleBak = false)
        val bak = bakOf(file)
        val tmp = File(bak.path + TMP)
        writeSynced(tmp, data)
        if (!tmp.renameTo(bak)) { tmp.delete(); throw IOException("couldn't save the copy of ${file.name}") }
        syncDir(bak.absoluteFile.parentFile)
    }

    /** Puts the last good copy back as the file itself (after a read had to use it). */
    fun repair(file: File): Unit = synchronized(lockFor(file)) {
        val b = bakOf(file).readBytes()
        put(file, b, keepBak = false, dropStaleBak = false)
    }

    /** Deletes [file], its last good copy and any half-written temporary file. */
    fun delete(file: File): Unit = synchronized(lockFor(file)) {
        file.delete(); bakOf(file).delete(); tmpOf(file).delete(); Unit
    }

    private fun writeSynced(f: File, data: ByteArray) {
        try {
            FileOutputStream(f).use { out ->
                val half = data.size / 2
                out.write(data, 0, half)
                midWrite?.invoke(f)
                out.write(data, half, data.size - half)
                out.flush()
                out.fd.sync()
            }
        } catch (e: Throwable) {
            f.delete()
            throw e
        }
    }

    /** So the rename itself survives a power cut. Not every system allows it; skipped where it doesn't. */
    private fun syncDir(dir: File?) {
        if (dir == null) return
        try {
            java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {}
    }
}
