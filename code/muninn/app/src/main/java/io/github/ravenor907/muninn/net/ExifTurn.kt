package io.github.ravenor907.muninn.net

/**
 * How to stand a photo upright from its EXIF orientation (1 to 8, as ExifInterface reads it): turn it this many degrees
 * clockwise, then mirror it left to right when [Turn.mirror]. Front cameras and some phones write the mirrored ones
 * (2, 4, 5, 7); before, those came out sideways or flipped.
 */
object ExifTurn {
    data class Turn(val degrees: Float, val mirror: Boolean)

    fun of(orientation: Int): Turn = when (orientation) {
        2 -> Turn(0f, true)      // flipped left to right
        3 -> Turn(180f, false)
        4 -> Turn(180f, true)    // flipped upside down
        5 -> Turn(90f, true)     // transposed
        6 -> Turn(90f, false)
        7 -> Turn(270f, true)    // transversed
        8 -> Turn(270f, false)
        else -> Turn(0f, false)
    }
}
