package uk.noammm.kav.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Transit.Leg
import uk.noammm.kav.data.Transit.LegKind
import uk.noammm.kav.leaveDue

class LeaveNowTest {
    private fun trip(vararg legs: Leg) = Transit.Itinerary("t", 0, legs.toList(), legs.first().dep, legs.last().arr)
    private fun walk(dep: Long, arr: Long, pathway: Boolean = false) = Leg(LegKind.WALK, dep = dep, arr = arr, pathway = pathway)
    private fun ride(dep: Long) = Leg(LegKind.RIDE, tripId = 7, dep = dep, arr = dep + 600)

    @Test fun walkIsTheSumOfTheLegsSeconds() {
        // 150 s and 170 s: floored to minutes they would be 2 + 2 = 240 s.
        val p = leavePlan(trip(walk(1000, 1150), walk(1150, 1320), ride(2000)))
        assertNotNull(p)
        assertEquals(320, p!!.walkS)
        assertEquals(2, p.legIndex)
    }

    @Test fun aWalkInsideAStationCounts() {
        val p = leavePlan(trip(walk(1000, 1100, pathway = true), walk(1100, 1400), ride(2000)))
        assertEquals(400, p!!.walkS)
    }

    @Test fun noPlanWithoutAWalkOfAMinute() {
        assertNull(leavePlan(trip(ride(2000))))
        assertNull(leavePlan(trip(walk(1000, 1040), ride(2000))))
    }

    @Test fun leaveAtIsTheBusLessTheWalkAndAMargin() {
        assertEquals(2000L - 300 - LEAVE_MARGIN_S, leaveAtFor(2000, 300))
        assertEquals(2000L - 300, lastLeaveFor(2000, 300))
    }

    @Test fun leaveNowFromHalfAMinuteBeforeUntilTheBusCantBeMade() {
        val at = leaveAtFor(2000, 300)
        assertFalse(leaveNowFor(2000, 300, at - 31))
        assertTrue(leaveNowFor(2000, 300, at - 30))
        assertTrue(leaveNowFor(2000, 300, lastLeaveFor(2000, 300) - 1))
        // The walk can't make it any more, though the bus hasn't gone.
        assertFalse(leaveNowFor(2000, 300, lastLeaveFor(2000, 300)))
    }

    @Test fun timeToLeaveOnlyWhileTheBusCanBeMade() {
        assertFalse(leaveDue(LEAVE_MARGIN_S + 1L))
        assertTrue(leaveDue(LEAVE_MARGIN_S.toLong()))
        assertTrue(leaveDue(0))
        assertFalse(leaveDue(-1))
    }
}
