package io.github.ravenor907.muninn.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.ravenor907.muninn.Muninn
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How much room Muninn takes on a Kids' TV, and keeping it small: TVs have little storage, and nobody
 * clears a TV. It keeps recent history only, makes big photos smaller, drops old voice messages and
 * empties its caches by itself (at start and every few hours); a parent can also Clear history from
 * their phone (7.7), which keeps the pairing and the contacts.
 */
object TvStorage {
    /** Per person, the newest messages kept. */
    const val KEEP = 300
    /** Voice messages are kept two weeks. */
    const val VOICE_DAYS = 14
    /** Photos and voice messages together, at most. */
    const val MAX_BLOBS = 150L * 1024 * 1024
    /** Photos bigger than this are made smaller (longest side 1280, JPEG quality 80). */
    const val SHRINK_OVER = 400 * 1024
    private const val MAX_CACHE = 50L * 1024 * 1024
    private const val DAY = 86_400_000L
    /** A downloaded update waiting to be installed (Updater) stays. */
    private val KEEP_CACHES = setOf("updates")
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tv-storage").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var started = false
    @Volatile private var job: java.util.concurrent.ScheduledFuture<*>? = null

    /** Bytes Muninn's own files and caches take on this device. */
    fun used(app: Muninn): Long = size(app.filesDir) + size(app.cacheDir) + (app.externalCacheDir?.let { size(it) } ?: 0L)

    private fun size(f: File): Long = try {
        if (f.isFile) f.length() else f.listFiles()?.sumOf { size(it) } ?: 0L
    } catch (e: Exception) { 0L }

    /** Once, on a Kids' TV: trims now and every six hours. */
    fun start(app: Muninn) {
        if (started) return
        started = true
        job = worker.scheduleWithFixedDelay({ try { trim(app) } catch (_: Exception) {} }, 30, 6 * 3600, TimeUnit.SECONDS)
    }

    /** The device left kids' mode: the trimming stops (an ordinary Muninn keeps all its history). */
    fun stop() {
        job?.cancel(false)
        job = null
        started = false
    }

    /** The automatic cap: recent history, smaller photos, no old voice messages, no stale caches. Only in kids' mode. */
    fun trim(app: Muninn) {
        if (!app.ready.value || !KidMode.on(app)) return
        val now = System.currentTimeMillis()
        app.chat.trimHistory(KEEP, now - VOICE_DAYS * DAY, MAX_BLOBS, SHRINK_OVER) { shrink(it) }
        val caches = listOfNotNull(app.cacheDir, app.externalCacheDir)
        val all = caches.sumOf { size(it) } > MAX_CACHE
        for (c in caches) c.listFiles()?.forEach { f -> if (f.name !in KEEP_CACHES && (all || now - f.lastModified() > DAY)) f.deleteRecursively() }
    }

    /** "Clear history" from a parent: every message, photo and voice message, and the caches. Pairing and contacts stay. */
    fun clear(app: Muninn) {
        app.chat.deleteAll()
        for (c in listOfNotNull(app.cacheDir, app.externalCacheDir)) c.listFiles()?.forEach { if (it.name !in KEEP_CACHES) it.deleteRecursively() }
    }

    /** Runs [f] on the storage thread (sizes and deleting take a moment on a TV). */
    fun later(f: () -> Unit) { worker.execute { try { f() } catch (_: Exception) {} } }

    /** A photo made smaller, or null if it can't be read or wouldn't get smaller. */
    fun shrink(jpeg: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= 1280) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 1280f / maxOf(bmp.width, bmp.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (sized !== bmp) sized.recycle()
        bmp.recycle()
        return out.toByteArray().takeIf { it.size < jpeg.size }
    }
}
