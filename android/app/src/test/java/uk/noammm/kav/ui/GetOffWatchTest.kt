package uk.noammm.kav.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetOffWatchTest {
    // Five stops along a road, about 660 m apart.
    private val stops = (0..4).map { GetOffStop("stop $it", 32.0, 34.80 + it * 0.007) }

    private fun at(w: GetOffWatch, stop: Int, speed: Float = 8f) = w.onFix(32.0, 34.80 + stop * 0.007, 10f, speed)

    @Test fun theStopBeforeThenTheStop() {
        val w = GetOffWatch(stops, 3)
        assertEquals(GetOffEvent.NONE, at(w, 0))
        assertEquals(GetOffEvent.NONE, at(w, 1))
        assertEquals(GetOffEvent.NEXT, at(w, 2))
        assertEquals(GetOffEvent.NONE, at(w, 2))
        assertEquals(GetOffEvent.ARRIVED, at(w, 3))
        assertEquals(GetOffEvent.NONE, at(w, 3))
        assertEquals(GetOffEvent.NONE, at(w, 4))
    }

    @Test fun aWalkerIsNeverTold() {
        val w = GetOffWatch(stops, 3)
        for (i in 0..4) assertEquals(GetOffEvent.NONE, at(w, i, speed = 1.4f))
    }

    @Test fun nothingWhenItStartsAtTheChosenStop() {
        val w = GetOffWatch(stops, 3)
        assertEquals(GetOffEvent.NONE, at(w, 3))
        assertEquals(GetOffEvent.NONE, at(w, 3))
    }

    @Test fun aMissedStopBeforeStillGetsTheArrival() {
        val w = GetOffWatch(stops, 3)
        assertEquals(GetOffEvent.NONE, at(w, 0))
        assertEquals(GetOffEvent.ARRIVED, at(w, 3))
    }

    @Test fun theSecondStopHasNoWarningBeforeIt() {
        val w = GetOffWatch(stops, 1)
        assertEquals(GetOffEvent.NONE, at(w, 0))
        assertEquals(GetOffEvent.ARRIVED, at(w, 1))
    }

    @Test fun aRoughFixHasMoreRoom() {
        val w = GetOffWatch(stops, 3)
        at(w, 0)
        // 230 m short of the stop: too far for a good fix, near enough for a rough one.
        val lon = 34.80 + 3 * 0.007 - 0.0025
        assertEquals(GetOffEvent.NONE, w.onFix(32.0, lon, 10f, 8f))
        assertEquals(GetOffEvent.ARRIVED, w.onFix(32.0, lon, 100f, 8f))
    }

    @Test fun metres() {
        assertEquals(660.0, getOffMeters(32.0, 34.80, 32.0, 34.807), 30.0)
        assertTrue(getOffMeters(32.0, 34.80, 32.0, 34.80) < 0.01)
    }
}
