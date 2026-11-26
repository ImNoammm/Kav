package uk.noammm.kav

import uk.noammm.kav.ui.Fix
import uk.noammm.kav.ui.Step
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.boardsAt
import uk.noammm.kav.ui.buildSteps
import uk.noammm.kav.ui.clockFormat
import uk.noammm.kav.ui.isFresh
import uk.noammm.kav.ui.leavePlan
import uk.noammm.kav.ui.metres
import java.util.Date

// #117: the trip's alert to set off for the first stop, from the walk's length and the bus's live time. It comes when the
// walk left takes as long as the bus has; if the bus then runs late, one says there's more time, and one more to leave
// when that comes (keys leave-<leg>-0, -1, -2 ...: even ones to leave, odd ones to wait).
internal fun leaveAlert(journey: ActiveJourney, current: Int, fix: Fix?, now: Long, fired: Set<String>): TripAlert? {
    val p = leavePlan(journey.trip) ?: return null
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
    val slack = board - now - left
    val n = fired.count { it.startsWith("leave-${p.legIndex}-") }
    if (n >= 6) return null
    val name = stop?.shown() ?: journey.fromLabel
    val time = clockFormat().format(Date(board * 1000))
    return if (n % 2 == 0) {
        if (slack > 60) null
        else TripAlert(
            "leave-${p.legIndex}-$n", T("Time to leave", "הגיע הזמן לצאת"),
            T("Line $line leaves $name at $time", "קו $line יוצא מ$name ב-$time"),
            T("Time to leave for line $line", "הגיע הזמן לצאת לקו $line"),
        )
    } else {
        if (slack <= 240) null
        else TripAlert(
            "leave-${p.legIndex}-$n", T("Line $line is running late", "קו $line מתעכב"),
            T("It leaves $name at $time. You have more time", "הוא יוצא מ$name ב-$time. יש לכם עוד זמן"),
        )
    }
}
