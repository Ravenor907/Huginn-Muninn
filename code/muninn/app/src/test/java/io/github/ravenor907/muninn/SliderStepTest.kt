package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.ui.SliderStep
import org.junit.Assert.assertEquals
import org.junit.Test

/** Smooth sliders (todo 1.42): keys move one unit a press, five a repeat once held half a second, kept in range. */
class SliderStepTest {
    @Test fun keySteps() {
        assertEquals(81f, SliderStep.next(80f, 1, 0, 1f, 50f, 150f), 0f)
        assertEquals(79f, SliderStep.next(80f, -1, 499, 1f, 50f, 150f), 0f)
        assertEquals(85f, SliderStep.next(80f, 1, 500, 1f, 50f, 150f), 0f)
        assertEquals(75f, SliderStep.next(80f, -1, 2_000, 1f, 50f, 150f), 0f)
        assertEquals(150f, SliderStep.next(148f, 1, 900, 1f, 50f, 150f), 0f)
        assertEquals(50f, SliderStep.next(50.5f, -1, 0, 1f, 50f, 150f), 0f)
        // A smooth value stays smooth: 83.4 moves to 84.4, never snapped.
        assertEquals(84.4f, SliderStep.next(83.4f, 1, 0, 1f, 50f, 150f), 1e-4f)
    }
}
