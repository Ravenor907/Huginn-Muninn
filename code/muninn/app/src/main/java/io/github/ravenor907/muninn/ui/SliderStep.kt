package io.github.ravenor907.muninn.ui

/**
 * Every slider moves smoothly (todo 1.42, "All sliders smooth"): no notches, the value follows the finger. Keys still
 * step: the keyboard, the d-pad and a controller move it by one [unit] a press, and by five while a key is held past
 * half a second. Kept free of Android so the unit tests run it; Huginn's sliders step the same way.
 */
object SliderStep {
    /** How long a key is held before it moves faster. */
    const val FAST_AFTER_MS = 500L
    /** Units a repeat moves once it's fast. */
    const val FAST = 5f

    /** How many units one key event moves when the key has been down [heldMs]. */
    fun units(heldMs: Long): Float = if (heldMs >= FAST_AFTER_MS) FAST else 1f

    /** [value] moved [dir] (-1 or 1) by a key held [heldMs], kept in [min] to [max]. */
    fun next(value: Float, dir: Int, heldMs: Long, unit: Float, min: Float, max: Float): Float =
        (value + dir * units(heldMs) * unit).coerceIn(min, max)
}
