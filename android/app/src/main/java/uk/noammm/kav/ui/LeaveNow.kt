package uk.noammm.kav.ui

import uk.noammm.kav.data.Transit

// A margin before the bus: the walk is meant to reach the stop this long before the bus leaves it.
internal const val LEAVE_MARGIN_S = 60

// When to leave: a route that begins with a walk to its first ride. The walk and the ride it goes to.
internal class LeavePlan(val ride: Transit.Leg, val wait: Transit.Leg?, val legIndex: Int, val walkS: Int)

// Null when the route doesn't begin with a walk of a minute or more to a ride. The walk is its legs' seconds, those
// inside a station (pathway) too.
internal fun leavePlan(trip: Transit.Itinerary): LeavePlan? {
    val i = trip.legs.indexOfFirst { it.kind == Transit.LegKind.RIDE }
    if (i <= 0) return null
    val before = trip.legs.subList(0, i)
    if (before.any { it.kind != Transit.LegKind.WALK && it.kind != Transit.LegKind.WAIT }) return null
    val walkS = before.filter { it.kind == Transit.LegKind.WALK }.sumOf { (it.arr - it.dep).toInt() }
    if (walkS < 60) return null
    return LeavePlan(trip.legs[i], before.last().takeIf { it.kind == Transit.LegKind.WAIT }, i, walkS)
}

// When the bus leaves the first stop: its live time where the planner has one, else the plan's.
internal fun boardsAt(p: LeavePlan, r: Transit.Resolved, chosen: Map<Int, Int>): Long {
    val (ride, wait) = boardingChoice(p.ride, p.wait, chosen[p.legIndex] ?: 0)
    return r.departures(ride, wait).firstOrNull { it.tripId == ride.tripId }?.timeUtc ?: ride.dep
}

// The moment to set off: the walk gets to the stop a margin before the bus leaves.
internal fun leaveAtFor(boards: Long, walkS: Int): Long = boards - walkS - LEAVE_MARGIN_S

// The last moment to set off and still make the bus. After it the bus can't be made.
internal fun lastLeaveFor(boards: Long, walkS: Int): Long = boards - walkS

// "Leave now" from half a minute before the time to leave, until the walk can't make the bus any more.
internal fun leaveNowFor(boards: Long, walkS: Int, now: Long): Boolean =
    leaveAtFor(boards, walkS) - now <= 30 && now < lastLeaveFor(boards, walkS)

internal fun leaveAt(p: LeavePlan, r: Transit.Resolved, chosen: Map<Int, Int>): Long = leaveAtFor(boardsAt(p, r, chosen), p.walkS)

internal fun lastLeave(p: LeavePlan, r: Transit.Resolved, chosen: Map<Int, Int>): Long = lastLeaveFor(boardsAt(p, r, chosen), p.walkS)

internal fun leaveNowDue(p: LeavePlan, r: Transit.Resolved, chosen: Map<Int, Int>, now: Long): Boolean =
    leaveNowFor(boardsAt(p, r, chosen), p.walkS, now)

// A trip's leave time, as every place says it: when to set off, and whether that has come. Null for a route that
// doesn't begin with a walk, which goes by its own time.
internal class LeaveTime(val at: Long, val now: Boolean)

internal fun leaveTime(trip: Transit.Itinerary, r: Transit.Resolved, chosen: Map<Int, Int>, now: Long): LeaveTime? {
    val p = leavePlan(trip) ?: return null
    return LeaveTime(leaveAt(p, r, chosen), leaveNowDue(p, r, chosen, now))
}
