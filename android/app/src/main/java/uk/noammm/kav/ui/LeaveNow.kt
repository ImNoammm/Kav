package uk.noammm.kav.ui

import uk.noammm.kav.data.Transit

// #117: when to leave. A route that begins with a walk to its first ride: the walk and the ride it goes to.
internal class LeavePlan(val ride: Transit.Leg, val wait: Transit.Leg?, val legIndex: Int, val walkS: Int)

// Null when the route doesn't begin with a walk of a minute or more to a ride.
internal fun leavePlan(trip: Transit.Itinerary): LeavePlan? {
    val i = trip.legs.indexOfFirst { it.kind == Transit.LegKind.RIDE }
    if (i <= 0) return null
    val before = trip.legs.subList(0, i)
    if (before.any { it.kind != Transit.LegKind.WALK && it.kind != Transit.LegKind.WAIT }) return null
    val walkS = before.filter { it.kind == Transit.LegKind.WALK && !it.pathway }.sumOf { it.minutes } * 60
    if (walkS < 60) return null
    return LeavePlan(trip.legs[i], before.last().takeIf { it.kind == Transit.LegKind.WAIT }, i, walkS)
}

// When the bus leaves the first stop: its live time where the planner has one, else the plan's.
internal fun boardsAt(p: LeavePlan, r: Transit.Resolved, chosen: Map<Int, Int>): Long {
    val (ride, wait) = boardingChoice(p.ride, p.wait, chosen[p.legIndex] ?: 0)
    return r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }?.timeUtc ?: ride.dep
}

// The moment to set off so that the walk gets to the stop as the bus leaves.
internal fun leaveAt(p: LeavePlan, r: Transit.Resolved, chosen: Map<Int, Int>): Long = boardsAt(p, r, chosen) - p.walkS

// "Leave now" from half a minute before the time to leave, until the bus has gone.
internal fun leaveNowDue(p: LeavePlan, r: Transit.Resolved, chosen: Map<Int, Int>, now: Long): Boolean =
    leaveAt(p, r, chosen) - now <= 30 && boardsAt(p, r, chosen) > now
