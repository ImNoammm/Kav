package uk.noammm.kav

import uk.noammm.kav.ui.Fix
import uk.noammm.kav.ui.LEAVE_MARGIN_S
import uk.noammm.kav.ui.Step
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.boardsAt
import uk.noammm.kav.ui.buildSteps
import uk.noammm.kav.ui.clockFormat
import uk.noammm.kav.ui.isFresh
import uk.noammm.kav.ui.leavePlan
import uk.noammm.kav.ui.metres
import java.util.Date

// Whether it is time to set off: the bus can still be made (the time left to its stop less the walk is not below
// nothing) and the margin is down to what was left for it. Once the bus can't be made it stays quiet: the plan has
// stopped working, which its own alert says.
internal fun leaveDue(slackS: Long): Boolean = slackS in 0..LEAVE_MARGIN_S

// The trip's alert to set off for the first stop, from the walk's length and the bus's live time (key leave-<leg>).
internal fun leaveAlert(journey: ActiveJourney, current: Int, fix: Fix?, now: Long, fired: Set<String>): TripAlert? {
    val p = leavePlan(journey.trip) ?: return null
    val key = "leave-${p.legIndex}"
    if (key in fired) return null
    val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    val step = steps.getOrNull(current) ?: return null
    val walking = step as? Step.Walk
    if (step !is Step.Start && (walking == null || walking.toLeg != p.legIndex)) return null
    val r = journey.resolved
    val ride = p.ride
    val line = ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }
    if (line.isBlank()) return null
    val board = boardsAt(p, r, journey.chosen)
    if (board <= now) return null
    val stop = r.stops[ride.fromStop]
    // Along the way, the distance still to the stop (about a second a metre, the street being longer than the line); else the walk's plan.
    val at = fix?.takeIf { walking != null && it.isFresh(now) }
    val point = stop?.point
    val left = if (at != null && point != null) (metres(at.lat, at.lon, point.first, point.second)).toLong() else p.walkS.toLong()
    if (!leaveDue(board - now - left)) return null
    val name = stop?.shown() ?: journey.fromLabel
    val time = clockFormat().format(Date(board * 1000))
    return TripAlert(
        key, T("Time to leave", "הגיע הזמן לצאת"),
        T("Line $line leaves $name at $time", "קו $line יוצא מ$name ב-$time"),
        T("Time to leave for line $line", "הגיע הזמן לצאת לקו $line"),
    )
}

// Whether the leave alert (key leave-<leg>) still applies: the trip is at its start or on the walk to the first stop, and
// the bus hasn't left. Past that the alert is taken down, as a replan alert is once the route is switched.
internal fun leaveStillDue(journey: ActiveJourney, current: Int, now: Long): Boolean {
    val p = leavePlan(journey.trip) ?: return false
    val step = buildSteps(journey.trip, journey.fromLabel, journey.toLabel).getOrNull(current) ?: return false
    if (step !is Step.Start && !(step is Step.Walk && step.toLeg == p.legIndex)) return false
    return boardsAt(p, journey.resolved, journey.chosen) > now
}
