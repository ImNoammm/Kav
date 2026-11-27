package uk.noammm.kav.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressGuardTest {
    // #126: a change from one line to another along one street.
    @Test fun aRiderIsNotOnABusThatIsStillWellOff() {
        assertFalse(boardedInTime(departsAt = 1_000 + 6 * 60, now = 1_000))
    }

    @Test fun aRiderIsOnABusThatIsDueOrGone() {
        assertTrue(boardedInTime(departsAt = 1_000 + 60, now = 1_000))
        assertTrue(boardedInTime(departsAt = 1_000, now = 1_000))
        assertTrue(boardedInTime(departsAt = 900, now = 1_000))
        assertTrue(boardedInTime(departsAt = 1_000 + BOARD_EARLY_S, now = 1_000))
        assertFalse(boardedInTime(departsAt = 1_000 + BOARD_EARLY_S + 1, now = 1_000))
    }

    @Test fun aBusGoingByItsEndEarlierHasNotArrived() {
        // Near the road's last point, but only 900 m along a 3,000 m road: it comes back by here later.
        assertFalse(endReached(away = 30.0, along = 900.0, length = 3_000.0, near = 60.0, slack = 0.0))
    }

    @Test fun aBusAtItsEndHasArrived() {
        assertTrue(endReached(away = 30.0, along = 2_980.0, length = 3_000.0, near = 60.0, slack = 0.0))
        assertTrue(endReached(away = 30.0, along = 2_860.0, length = 3_000.0, near = 60.0, slack = 0.0))
    }

    @Test fun farFromTheEndIsNotThere() {
        assertFalse(endReached(away = 200.0, along = 2_990.0, length = 3_000.0, near = 60.0, slack = 0.0))
    }

    @Test fun aRoughFixHasMoreRoom() {
        assertFalse(endReached(away = 85.0, along = 2_990.0, length = 3_000.0, near = 60.0, slack = 0.0))
        assertTrue(endReached(away = 85.0, along = 2_990.0, length = 3_000.0, near = 60.0, slack = 30.0))
        assertTrue(endReached(away = 70.0, along = 2_830.0, length = 3_000.0, near = 60.0, slack = 30.0))
    }

    @Test fun noRoadMeansNearIsEnough() {
        assertTrue(endReached(away = 20.0, along = 0.0, length = 0.0, near = 60.0, slack = 0.0))
    }
}
