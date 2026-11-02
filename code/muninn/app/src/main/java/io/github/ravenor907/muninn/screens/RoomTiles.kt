package io.github.ravenor907.muninn.screens

/**
 * How big group video tiles (3:4) are, from the window's size in dp: 2 across upright, 4 across sideways, and never
 * taller than [TALL] of the window, so a landscape tablet shows every face at once. Pure, so it can be tested.
 */
object RoomTiles {
    const val TALL = 0.6f
    const val GAP = 8f
    /** Room for the page's own side padding. */
    const val SIDES = 32f
    const val MIN = 80f

    fun columns(widthDp: Float, heightDp: Float): Int = if (widthDp > heightDp) 4 else 2

    /** A tile's width in dp. */
    fun tileWidth(widthDp: Float, heightDp: Float): Float {
        val cols = columns(widthDp, heightDp)
        val byWidth = (widthDp - SIDES - GAP * (cols - 1)) / cols
        val byHeight = heightDp * TALL * 3f / 4f
        return minOf(byWidth, byHeight).coerceAtLeast(MIN)
    }

    /** The widest a shared screen of [ratio] (width / height) may be, so it's at most [TALL] of the window high. */
    fun screenWidth(widthDp: Float, heightDp: Float, ratio: Float): Float = minOf(widthDp, heightDp * TALL * ratio)
}
