package io.github.ravenor907.muninn.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import io.github.ravenor907.muninn.net.Lru
import io.github.ravenor907.muninn.net.MemoryBudget

/**
 * Decoded chat photos kept in memory, so scrolling back doesn't decode them again: about an eighth of the memory
 * Android gives Muninn. Emptied when Muninn locks or erases (Muninn.lockedNow), so no picture stays decoded then.
 */
object PhotoCache {
    @Volatile private var cache: Lru<String, ImageBitmap>? = null

    private fun of(context: Context): Lru<String, ImageBitmap> = cache ?: synchronized(this) {
        cache ?: run {
            val mb = try { context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 64 } catch (_: Exception) { 64 }
            Lru<String, ImageBitmap>(MemoryBudget.bytes(mb, 8)) { it.width.toLong() * it.height * 4 }.also { cache = it }
        }
    }

    /** The picture for [key] (a message id and size), decoded by [make] only when it isn't kept already. */
    fun get(context: Context, key: String, make: () -> ImageBitmap?): ImageBitmap? = of(context).getOrPut(key, make)

    fun clear() { cache?.clear() }
}
