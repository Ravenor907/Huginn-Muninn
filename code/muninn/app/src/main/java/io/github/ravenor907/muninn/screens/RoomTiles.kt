package io.github.ravenor907.muninn.screens

/**
 * How big group video tiles (3:4) are: 2 across upright, 4 across sideways, and never taller than [TALL] of the
 * window, so a landscape tablet shows every face at once. The width is what the row of tiles is really given (the page
 * inside its padding and the side insets), so a full row always fits on one line. Pure, so it can be tested.
 */
object RoomTiles {
    const val TALL = 0.6f
    const val GAP = 8f
    /** The room page's own side padding (20 dp each side), for a caller that only knows the window's width. */
    const val SIDES = 40f
    const val MIN = 80f

    fun columns(widthDp: Float, heightDp: Float): Int = if (widthDp > heightDp) 4 else 2

    /**
     * A tile's width in dp, for a row [rowDp] wide holding [cols] tiles, in a window [heightDp] high. A dp is taken
     * off for rounding to pixels, so [cols] tiles and their gaps never need more than [rowDp].
     */
    fun tileWidth(rowDp: Float, heightDp: Float, cols: Int): Float {
        val n = cols.coerceAtLeast(1)
        val byWidth = kotlin.math.floor((rowDp - GAP * (n - 1)) / n - 1f)
        val byHeight = heightDp * TALL * 3f / 4f
        return minOf(byWidth, byHeight).coerceAtLeast(minOf(MIN, byWidth)).coerceAtLeast(1f)
    }

    /** A tile's width in dp from the whole window's size (the page's padding taken off). */
    fun tileWidth(widthDp: Float, heightDp: Float): Float = tileWidth(widthDp - SIDES, heightDp, columns(widthDp, heightDp))

    /** The widest a shared screen of [ratio] (width / height) may be, so it's at most [TALL] of the window high. */
    fun screenWidth(widthDp: Float, heightDp: Float, ratio: Float): Float = minOf(widthDp, heightDp * TALL * ratio)
}
