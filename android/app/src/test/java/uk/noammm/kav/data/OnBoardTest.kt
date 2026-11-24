package uk.noammm.kav.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.noammm.kav.data.Bundle.Companion.hm
import uk.noammm.kav.ui.Step
import uk.noammm.kav.ui.buildSteps
import java.time.LocalDate

// #100: a rider already on the bus picks the stop they get off at on the line's page, with no trip planned. Line 5 from
// A to E, a stop every three minutes, out at 8:00, 8:20 and 8:40; the way back, and line 7 over the same stops, beside it.
class OnBoardTest {
    private val day = LocalDate.of(2026, 11, 24)
    private fun at(h: Int, m: Int) = Schedules.base(day) + hm(h, m)

    private lateinit var stops: List<Int>
    private val net = Bundle().run {
        stops = listOf("A", "B", "C", "D", "E").map { stop(it) }
        for (start in listOf(hm(8, 0), hm(8, 20), hm(8, 40))) {
            trip(stops, stops.indices.map { start + it * 180 }, line = "5", long = "A - E")
            trip(stops.reversed(), stops.indices.map { start + it * 180 }, line = "5", long = "E - A")
            trip(stops, stops.indices.map { start + 60 + it * 180 }, line = "7", long = "A - E")
        }
        net()
    }
    private val line5 = (0 until net.nRoutes).filter { net.rShort[it] == "5" && net.rLong[it] == "A - E" }.toSet()
    private fun place(s: Int) = net.lat[stops[s]] to net.lon[stops[s]]

    @Test fun theRunAtTheRidersStopNowToTheStopTheyGetOffAt() {
        val built = OnBoard.trip(net, line5, stops, to = 4, here = place(1), now = at(8, 23), today = day)!!
        val ride = built.trip.rides.single()
        // The 8:20, boarded at B at 8:23, off at E at 8:32: not the 8:00 gone by, the way back or line 7.
        assertEquals(at(8, 23) to at(8, 32), ride.dep to ride.arr)
        assertEquals(listOf("B", "C", "D", "E"), ride.stops.map { built.resolved.stop(it)!!.name })
        assertEquals("5", built.resolved.line(ride.lineId)!!.number)
        assertEquals("B" to "E", built.from to built.to)
        // Followed as Kav's own trips are, its bus's live times asked for by its stops' codes.
        assertTrue(KavPlan.refreshes(built.trip))
        assertEquals(net.code[stops[1]], built.resolved.stop(ride.fromStop)!!.ministryCode())
        // Its timetable time where it's boarded, which the live bus is matched by.
        assertEquals(at(8, 23), built.trip.legs.first().nextDeps.single { it.tripId == ride.tripId }.staticUtc)
        // On board from the start: a wait and the ride, then there.
        val steps = buildSteps(built.trip, built.from, built.to)
        assertTrue(steps[2] is Step.Ride && steps.last() is Step.Arrive)
    }

    @Test fun withNoPositionTheNextRunToGetThereFromTheStopItLastLeft() {
        val built = OnBoard.trip(net, line5, stops, to = 4, here = null, now = at(8, 30), today = day)!!
        val ride = built.trip.rides.single()
        assertEquals(at(8, 29) to at(8, 32), ride.dep to ride.arr)
        assertEquals("D", built.from)
    }

    @Test fun withoutPositionKeepsTheDisplayedDirection() {
        val both = (0 until net.nRoutes).filter { net.rShort[it] == "5" }.toSet()
        val built = OnBoard.trip(net, both, stops, to = 1, here = null, now = at(8, 24), today = day)!!
        assertEquals("A", built.from)
        assertEquals(at(8, 43), built.trip.arr)
    }

    @Test fun aRiderFarFromTheWayIsNotPlacedOnIt() {
        val far = place(1).let { (la, lo) -> la + 0.05 to lo }
        val built = OnBoard.trip(net, line5, stops, to = 4, here = far, now = at(8, 30), today = day)!!
        assertEquals("D", built.from)
    }

    @Test fun nothingWhenNoRunGetsThereOrTheStopIsTheFirst() {
        assertNull(OnBoard.trip(net, line5, stops, to = 4, here = place(1), now = at(23, 0), today = day))
        assertNull(OnBoard.trip(net, line5, stops, to = 0, here = place(1), now = at(8, 23), today = day))
    }
}
