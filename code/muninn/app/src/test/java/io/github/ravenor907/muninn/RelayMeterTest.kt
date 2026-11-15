package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.MediaRoute
import io.github.ravenor907.muninn.net.RelayMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The shared relay total is only advice (as Huginn): 66 GB a day of the month at most, and never the stop. */
class RelayMeterTest {
    private val gb = 1_000_000_000L

    @Test
    fun aFakedTotalIsCappedAndNeverStops() {
        assertEquals(66 * gb, RelayMeter.believedShared(950 * gb, 1))
        assertEquals(300 * gb, RelayMeter.believedShared(300 * gb, 10))
        assertEquals(0L, RelayMeter.believedShared(-5, 3))
        assertEquals(31 * 66 * gb, RelayMeter.believedShared(Long.MAX_VALUE, 40))
        // Even a believed 950 GB doesn't stop relayed files: only this install's own count does.
        assertFalse(MediaRoute.stopped(10 * gb, 0L))
    }
}
