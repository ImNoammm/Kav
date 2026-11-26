package uk.noammm.kav.ui

import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.TripAlert
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Transit
import kotlin.math.max

// A trip under way whose plan no longer works, and a new route from where the rider is. Every rule here decides only
// on what Kav knows for sure: a missing or old fix, or a bus with no live data, decides nothing.
internal enum class Broken { CANCELLED, GONE, LATE_CHANGE, OFF_ROUTE }

// Where a new route starts: a place and the time to leave it (0, now), and how many of the trip's legs stay before it,
// the ride the rider is on and all before it when it starts where that ride ends. done: the legs behind the rider, whose
// stops on the way are passed.
internal class ReplanFrom(val at: Pair<Double, Double>, val time: Long, val keep: Int, val done: Int)

// missed: the ride that can't be taken; riding, the one the rider is on when it's a change. arrive and leaves: when the
// rider gets to the change and when its bus leaves. next: the next departure there they can still take, 0 if none known.
internal class Failure(
    val kind: Broken,
    val key: String,
    val from: ReplanFrom,
    val missed: Transit.Leg? = null,
    val arrive: Long = 0,
    val leaves: Long = 0,
    val next: Long = 0,
)

internal fun retainedFailure(held: Pair<Int, Failure>?, step: Int, failing: Failure?): Pair<Int, Failure>? = when {
    failing?.kind == Broken.OFF_ROUTE -> null
    failing != null -> step to failing
    else -> held?.takeIf { it.first == step && it.second.kind != Broken.OFF_ROUTE }
}

// A bus counts as past the rider's stop this far along its road beyond it, by a report at most BUS_FRESH_S old, on the
// road (ON_ROAD_M), with the rider at least BEHIND_M behind it.
private const val PAST_STOP_M = 200.0
private const val BUS_FRESH_S = 180L
private const val ON_ROAD_M = 100.0
private const val BEHIND_M = 150.0

// A change fails when the rider gets to its stop more than this after every bus there that the plan offers has left: a
// bus a minute or two late is no failure.
internal const val CHANGE_LATE_S = 120L

// Far off the route: this far from everything still ahead in the trip.
internal const val OFF_ROUTE_M = 500.0

// Off the route on a walk for sure, for a new route without a tap: OFF_WALK_FIXES sharp fixes off it in a row (each at
// most OFF_WALK_ACCURACY_M off, as the phone says), the first and last OFF_WALK_S apart, none more than OFF_WALK_GAP_S
// after the one before. A rough fix decides nothing; a sharp one on the route starts it over.
private const val OFF_WALK_FIXES = 3
private const val OFF_WALK_S = 15L
private const val OFF_WALK_GAP_S = 60L
private const val OFF_WALK_ACCURACY_M = 50f

// The fixes off the walk in a row: since, the first one's time, 0 with none; last, the last fix looked at.
internal data class OffWalk(val since: Long = 0, val fixes: Int = 0, val last: Long = 0) {
    val sure get() = fixes >= OFF_WALK_FIXES && last - since >= OFF_WALK_S
}

// The watch after a fix. off: the trip's plan fails as off the route by this fix, on a walk.
internal fun offWalk(w: OffWalk, fix: Fix?, off: Boolean, now: Long): OffWalk = when {
    fix == null || !fix.isFresh(now) || fix.at <= w.last || fix.accuracy > OFF_WALK_ACCURACY_M -> w
    !off -> OffWalk(last = fix.at)
    w.fixes == 0 || fix.at - w.last > OFF_WALK_GAP_S -> OffWalk(fix.at, 1, fix.at)
    else -> w.copy(fixes = w.fixes + 1, last = fix.at)
}

// A small detour on a walk: the rider took another street. Off the walk's own path by DETOUR_M more than the fix says it
// may be off (so the sidewalk across the street is still on it), for as many sharp fixes as offWalk() counts, the walk
// follows them: walked again from where they are to where it went, the rest of the trip as it was while its next ride
// can still be made from there. A new walk at most every DETOUR_EVERY_S.
internal const val DETOUR_M = 50.0
internal const val DETOUR_EVERY_S = 30L

internal fun offWalkPath(step: Step, fix: Fix): Boolean =
    step is Step.Walk && distanceToPath(fix.lat, fix.lon, step.leg.shape).let { it != Double.MAX_VALUE && it > DETOUR_M + fix.accuracy }

// The part of a walk walked again: its first run of legs in the street, without the steps inside a station before or
// after it, which stay as they were.
private fun streetLegs(step: Step.Walk, trip: Transit.Itinerary): IntRange? {
    val legs = step.legs.filter { it in trip.legs.indices }
    val first = legs.firstOrNull { !trip.legs[it].pathway } ?: return null
    val end = legs.firstOrNull { it > first && trip.legs[it].pathway } ?: (legs.last() + 1)
    return first until end
}

// Where the walk walked again goes: the end of that part.
internal fun detourTarget(step: Step.Walk, trip: Transit.Itinerary): Pair<Double, Double>? =
    streetLegs(step, trip)?.let { run -> run.reversed().firstNotNullOfOrNull { trip.legs[it].shape.lastOrNull() } }

// The planner's walk from here to there, by the trip's own planner; null when it gives none. Network: call it off the
// main thread.
internal fun walkFromHere(trip: Transit.Itinerary, here: Pair<Double, Double>, to: Pair<Double, Double>, routeTypes: List<Int>): List<Transit.Leg>? {
    val planner = KavPlan.replanAt(trip) ?: return null
    val planned = uk.noammm.kav.data.plannerFor(planner).plan(
        uk.noammm.kav.data.PlanRequest(here, to, 0L, Transit.TIME_DEPARTURE, routeTypes, false, settings = KavPlan.settings))
    return planned.plan.itineraries.firstOrNull { it.legs.isNotEmpty() && it.legs.all { l -> l.kind == Transit.LegKind.WALK } }?.legs
}

// The trip with its walk's street part walked from here, leaving now: the planner's walk as one leg in its place, and
// empty legs at its end for the rest of the legs it had, so every leg after it keeps its place (the lines chosen, the
// alerts given). The legs after it in the walk, and the wait after the walk, move with its new end; the trip's own end
// too when the walk ends it.
internal fun followedWalk(trip: Transit.Itinerary, step: Step.Walk, walk: List<Transit.Leg>, now: Long): Transit.Itinerary? {
    val run = streetLegs(step, trip) ?: return null
    if (walk.isEmpty() || walk.any { it.kind != Transit.LegKind.WALK }) return null
    val to = detourTarget(step, trip) ?: return null
    val arrive = now + walk.sumOf { (it.arr - it.dep).coerceAtLeast(0) }
    val first = trip.legs[run.first]
    val last = trip.legs[run.last]
    val walked = Transit.Leg(
        Transit.LegKind.WALK, dep = now, arr = arrive, toStop = last.toStop, meters = walk.sumOf { it.meters },
        shape = walk.flatMap { it.shape }, turns = walk.flatMap { it.turns }, visit = first.visit,
        insideMeters = walk.sumOf { it.insideMeters },
    )
    val shift = arrive - last.arr
    val end = step.legs.last
    val legs = trip.legs.mapIndexed { i, l ->
        when {
            i == run.first -> walked
            i in run -> Transit.Leg(Transit.LegKind.WALK, dep = arrive, arr = arrive, toStop = last.toStop)
            i in run.last + 1..end -> l.copy(dep = l.dep + shift, arr = l.arr + shift)
            else -> l
        }
    }.toMutableList()
    val there = legs[end].arr
    for (i in end + 1 until legs.size) {
        if (legs[i].kind != Transit.LegKind.WAIT) break
        legs[i] = legs[i].copy(dep = minOf(there, legs[i].arr))
    }
    return Transit.Itinerary(
        trip.guid, trip.group, legs, trip.dep, if (end == trip.legs.lastIndex) there else trip.arr, trip.fare, trip.currency,
        trip.co2g, trip.accessible, trip.tags, trip.section, trip.sectionId, trip.singleFares, trip.provider, trip.policy,
    )
}

// A detour followed: the trip on with the walk from here, or, when the walk's next ride can't be made from here, the
// rider asked whether to plan a new route.
internal sealed interface Detour {
    class Follow(val trip: Transit.Itinerary) : Detour
    class Ask(val failure: Failure) : Detour
}

// The walk the rider is on (step `at`), walked from here by the planner's walk: null when it can't take its place. The
// ride after it is made when one of its lines leaves no more than CHANGE_LATE_S before the rider gets to it, as for a
// change.
internal fun detour(
    trip: Transit.Itinerary, steps: List<Step>, at: Int, walk: List<Transit.Leg>, here: Pair<Double, Double>,
    r: Transit.Resolved, chosen: Map<Int, Int>, now: Long,
): Detour? {
    val step = steps.getOrNull(at) as? Step.Walk ?: return null
    val next = followedWalk(trip, step, walk, now) ?: return null
    val after = buildSteps(next, "", "")
    if (after.size != steps.size) return null
    val arrive = (after[at] as? Step.Walk)?.leg?.arr ?: return null
    val ride = step.toRide ?: return Detour.Follow(next)
    val options = Transit.boardingOptions(ride, step.toWait)
    if (options.any { (o, w) -> departureOf(o, w, r, now).let { it.status != 3 && it.timeUtc + CHANGE_LATE_S >= arrive } }) {
        return Detour.Follow(next)
    }
    val (missed, wait) = boardingChoice(ride, step.toWait, chosen[step.toLeg] ?: 0)
    return Detour.Ask(Failure(Broken.OFF_ROUTE, "aside-$at", ReplanFrom(here, 0, legsKept(step, trip), legsDone(step, trip)),
        missed, arrive, departureOf(missed, wait, r, now).timeUtc, nextDeparture(options, r, now, arrive - CHANGE_LATE_S)))
}

private fun choice(step: Step.Wait, chosen: Map<Int, Int>) = boardingChoice(step.ride, step.wait, chosen[step.legIndex] ?: 0)

private fun departureOf(ride: Transit.Leg, wait: Transit.Leg?, r: Transit.Resolved, now: Long) =
    r.departures(ride, wait, now).firstOrNull { it.tripId == ride.tripId } ?: Transit.Departure(ride.tripId, ride.dep)

// The soonest departure from `from` on of any of the lines, not the bus missed nor a cancelled one; 0 if none known.
private fun nextDeparture(options: List<Pair<Transit.Leg, Transit.Leg?>>, r: Transit.Resolved, now: Long, from: Long, except: Long = 0L): Long =
    options.flatMap { (o, w) -> r.departures(o, w, now) }
        .filter { it.tripId != except && it.status != 3 && it.timeUtc >= from }
        .minOfOrNull { it.timeUtc } ?: 0L

// The plan's own walk from one ride to the next.
private fun walkBetween(trip: Transit.Itinerary, from: Int, to: Int): Long {
    val after = (from + 1).coerceIn(0, trip.legs.size)
    return trip.legs.subList(after, to.coerceIn(after, trip.legs.size)).filter { it.kind == Transit.LegKind.WALK }.sumOf { it.arr - it.dep }
}

// The legs behind a rider on this step.
private fun legsDone(step: Step, trip: Transit.Itinerary): Int = when (step) {
    is Step.Walk -> step.toLeg.takeIf { it >= 0 } ?: trip.legs.size
    is Step.Wait -> step.legIndex
    is Step.Ride -> step.legIndex + 1
    is Step.Taxi -> trip.legs.indexOfFirst { it === step.leg } + 1
    is Step.Cycle -> trip.legs.indexOfFirst { it === step.leg } + 1
    is Step.Start -> 0
    is Step.Arrive -> trip.legs.size
}

// The legs a new route from where the rider is keeps before it, at their places: those of the steps behind them and the
// ride they're on, without the walk and wait that brought them to where they stand.
private fun legsKept(step: Step, trip: Transit.Itinerary): Int {
    val legs = trip.legs
    var n = when (step) {
        is Step.Walk -> step.toLeg.takeIf { it >= 0 } ?: legs.size
        is Step.Wait -> step.legIndex
        is Step.Ride -> step.legIndex + 1
        is Step.Taxi -> legs.indexOfFirst { it === step.leg } + 1
        is Step.Cycle -> legs.indexOfFirst { it === step.leg } + 1
        is Step.Start -> 0
        is Step.Arrive -> legs.size
    }.coerceIn(0, legs.size)
    while (n > 0 && legs[n - 1].kind.let { it == Transit.LegKind.WALK || it == Transit.LegKind.WAIT }) n--
    return n
}

// The plan's bus has gone past the rider's stop without them: it was reported, lately and on its road, well past the
// stop, which no longer lists it, and the rider's own fix, taken no earlier than that report, has them well behind it.
// A bus doesn't go back, so at that fix it was ahead of them: they aren't on it.
internal fun busGone(ride: Transit.Leg, r: Transit.Resolved, rider: Fix, now: Long): Boolean {
    val shape = ride.shape
    if (shape.size < 2 || r.live[Transit.ArrivalKey(ride.fromStop, ride.tripId)] != null) return false
    val bus = busOn(ride, r, ride.tripId)?.takeIf { now - it.sampleUtc <= BUS_FRESH_S && rider.at >= it.sampleUtc } ?: return false
    if (distanceToPath(bus.lat, bus.lon, shape) > ON_ROAD_M) return false
    val ahead = alongPath(bus.lat, bus.lon, shape)
    return ahead >= PAST_STOP_M && alongPath(rider.lat, rider.lon, shape) + BEHIND_M + 3 * rider.slack < ahead
}

// Whether the trip's plan has stopped working, and where a new route would start: null while it works, or while Kav
// can't tell. Nothing is decided before the trip sets out.
internal fun planFailure(
    steps: List<Step>, current: Int, trip: Transit.Itinerary, r: Transit.Resolved, chosen: Map<Int, Int>, fix: Fix?, now: Long,
): Failure? {
    if (steps.isEmpty()) return null
    val at = current.coerceIn(0, steps.lastIndex)
    val step = steps[at]
    if (step is Step.Start || step is Step.Arrive) return null
    val live = fix?.takeIf { it.isFresh(now) }
    val done = legsDone(step, trip)
    val keep = legsKept(step, trip)
    // The ride walked to or waited for, before any ride; or the ride the rider is on.
    val waiting = steps.drop(at).takeWhile { it !is Step.Ride }.firstOrNull { it is Step.Wait } as? Step.Wait
    val riding = step as? Step.Ride
    val fromHere = (live?.let { it.lat to it.lon } ?: waiting?.let { r.stop(choice(it, chosen).first.fromStop)?.point })
        ?.let { ReplanFrom(it, 0, keep, done) }
    // On a ride, a new route starts where it ends, as the bus gets there, behind it.
    fun fromChange(ride: Transit.Leg, arrive: Long) = (r.stop(ride.toStop)?.point ?: ride.shape.lastOrNull())
        ?.let { ReplanFrom(it, max(now, arrive), riding!!.legIndex + 1, riding.legIndex + 1) }
    fun ridden() = riding?.let { boardingChoice(it.ride, it.wait, chosen[it.legIndex] ?: 0).first }
    fun arrival(ride: Transit.Leg) = if (riding != null)
        rideTimes(ride, r, riderTrip(ride, r, live, now), r.stops, live, now).lastOrNull()?.utc ?: r.alightAt(ride)
        else rideTimes(ride, r, ride.tripId, r.stops, null, now).lastOrNull()?.utc ?: r.alightAt(ride)

    // The next ride to board is cancelled.
    (waiting ?: riding?.change)?.let { board ->
        val (ride, wait) = choice(board, chosen)
        if (departureOf(ride, wait, r, now).status == 3) {
            val from = (if (riding != null) fromChange(ridden()!!, arrival(ridden()!!)) else fromHere) ?: return null
            return Failure(Broken.CANCELLED, "cancelled-${board.legIndex}-${ride.tripId}", from, ride,
                next = nextDeparture(Transit.boardingOptions(board.ride, board.wait), r, now, now, ride.tripId))
        }
    }

    // Its bus has gone by without the rider.
    if (waiting != null && live != null) {
        val (ride, _) = choice(waiting, chosen)
        if (busGone(ride, r, live, now)) return Failure(Broken.GONE, "gone-${waiting.legIndex}-${ride.tripId}",
            ReplanFrom(live.lat to live.lon, 0, keep, done), ride,
            next = nextDeparture(Transit.boardingOptions(waiting.ride, waiting.wait), r, now, now, ride.tripId))
    }

    // The change after the ride ridden, or after the one walked to or waited for, can't be made: its bus gets there more
    // than CHANGE_LATE_S after every bus the plan offers there has left.
    val rideStep = riding ?: waiting?.let { steps.getOrNull(steps.indexOf(it) + 1) as? Step.Ride }
    val change = rideStep?.change
    if (rideStep != null && change != null) {
        val ride = boardingChoice(rideStep.ride, rideStep.wait, chosen[rideStep.legIndex] ?: 0).first
        val arrive = arrival(ride)
        val ready = arrive + walkBetween(trip, rideStep.legIndex, change.legIndex)
        val options = Transit.boardingOptions(change.ride, change.wait)
        if (options.none { (o, w) -> departureOf(o, w, r, now).let { it.status != 3 && it.timeUtc + CHANGE_LATE_S >= ready } }) {
            val from = (if (riding != null) fromChange(ride, arrive) else fromHere) ?: return null
            val (next, nextWait) = choice(change, chosen)
            return Failure(Broken.LATE_CHANGE, "late-${change.legIndex}-${next.tripId}", from, next, arrive,
                departureOf(next, nextWait, r, now).timeUtc, nextDeparture(options, r, now, ready))
        }
    }

    // Far from everything still ahead in the trip, and from the step before it.
    if (live != null && at > 0) {
        val ahead = steps.drop(at - 1).flatMap { s ->
            when (s) {
                is Step.Walk -> listOf(s.leg.shape)
                is Step.Wait -> listOfNotNull(stepTarget(s, r, chosen)?.let(::listOf))
                is Step.Ride -> listOf(boardingChoice(s.ride, s.wait, chosen[s.legIndex] ?: 0).first.shape)
                is Step.Taxi -> listOf(s.leg.shape)
                is Step.Cycle -> listOf(s.leg.shape)
                is Step.Arrive -> listOf(s.focus.takeLast(1))
                is Step.Start -> emptyList()
            }
        }.filter { it.isNotEmpty() }
        if (ahead.isNotEmpty() && ahead.all { distanceToPath(live.lat, live.lon, it) > OFF_ROUTE_M + 3 * live.slack }) {
            return Failure(Broken.OFF_ROUTE, "off-$at", ReplanFrom(live.lat to live.lon, 0, keep, done))
        }
    }
    return null
}

// A ride's name, "Bus 7", kept whole: a line break never falls inside it.
private fun rideName(ride: Transit.Leg, r: Transit.Resolved): String {
    val number = ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }
    val mode = modeName(legMode(ride, r))
    return (if (number.isBlank()) mode else "$mode $number").replace(' ', '\u00A0')
}

internal fun failureTitle(f: Failure, r: Transit.Resolved): String {
    val line = f.missed?.let { rideName(it, r) }.orEmpty()
    return when (f.kind) {
        Broken.CANCELLED -> T("$line is canceled", "$line מבוטל")
        Broken.GONE -> T("You missed $line", "פספסתם את $line")
        Broken.LATE_CHANGE -> T("This connection won't work", "לא תספיקו להחליף")
        Broken.OFF_ROUTE -> T("You're off the route", "יצאתם מהמסלול")
    }
}

// What went wrong in a line: when the rider gets to the change and when its bus leaves, and the next departure there.
internal fun failureText(f: Failure, r: Transit.Resolved, now: Long): String? {
    val hm = clockFormat()
    fun time(utc: Long) = hm.format(java.util.Date(utc * 1000))
    val next = f.next.takeIf { it > 0 }?.let { minsLeft(it - now) }
        ?.let { if (it == 0L) T("The next one is now", "היציאה הבאה ממש עכשיו")
            else T("The next one is in $it min", "היציאה הבאה בעוד $it דק׳") }
    val change = f.missed?.takeIf { f.kind == Broken.LATE_CHANGE || f.kind == Broken.OFF_ROUTE }?.let { ride ->
        val stop = r.stopName(ride.fromStop)?.takeIf { it.isNotBlank() }
        val line = rideName(ride, r)
        if (stop != null) T("You get to $stop at ${time(f.arrive)}, after $line leaves at ${time(f.leaves)}",
            "הגעה ל$stop ב-${time(f.arrive)}, אחרי היציאה של $line ב-${time(f.leaves)}")
        else T("You get there at ${time(f.arrive)}, after $line leaves at ${time(f.leaves)}",
            "הגעה ב-${time(f.arrive)}, אחרי היציאה של $line ב-${time(f.leaves)}")
    }
    return listOfNotNull(change, next).joinToString("") { "$it. " }.trimEnd().ifEmpty { null }
}

// The trip alert for a plan that stopped working, once per failure; the app's card has the new route.
internal fun replanAlert(journey: ActiveJourney, current: Int, fix: Fix?, now: Long): TripAlert? {
    val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    val f = planFailure(steps, current, journey.trip, journey.resolved, journey.chosen, fix, now) ?: return null
    val title = failureTitle(f, journey.resolved)
    return TripAlert("replan-${f.key}", title, T("Tap for a new route from here", "הקישו למסלול חדש מכאן"), title)
}

// On a ride's card with a change: how long after the bus the plan changes to the next one of its lines leaves there.
internal fun missedChangeNext(step: Step.Ride, r: Transit.Resolved, chosen: Map<Int, Int>, now: Long): Long? {
    val change = step.change ?: return null
    val (ride, wait) = choice(change, chosen)
    val leaves = departureOf(ride, wait, r, now).timeUtc
    val next = nextDeparture(Transit.boardingOptions(change.ride, change.wait), r, now, leaves + 60, ride.tripId)
    return next.takeIf { it > 0 }?.let { minsLeft(it - leaves) }
}

// The trip on a new route from where the failure says to where it ends, through the stops on the way not passed yet:
// the planner's first route, after the legs it keeps; and the step the trip is on then. Null when no planner answers or
// none is found. Network: call it off the main thread.
internal fun replanned(journey: ActiveJourney, from: ReplanFrom, routeTypes: List<Int>, current: Int): Pair<ActiveJourney, Int>? {
    val trip = journey.trip
    val end = trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull() ?: return null
    // Its own planner, never another one: an extension's trip isn't sent to Kav's server, nor Kav's to an extension.
    val planner = KavPlan.replanAt(trip) ?: return null
    val stopovers = journey.stopovers.drop(trip.legs.take(from.done).count { it.visit != null })
    val planned = KavPlan.plan(from.at, end, from.time * 1000, Transit.TIME_DEPARTURE, routeTypes, false, stopovers, planner)
    // The route rules the journey was chosen under (its pinned snapshot), not whatever the rider picked since. If a
    // required one fails, no new route: the trip keeps the one it has.
    val routes = if (trip.policy.isEmpty()) planned.plan.laidOut() else {
        val now = System.currentTimeMillis() / 1000
        val q = uk.noammm.kav.ext.Pipeline.Query('d', if (from.time > 0) from.time else now, now, KavPlan.settings.letters, T.lang.code,
            stopovers.map { it.stayMin * 60L })
        // Rules that can't all be read back: no new route, rather than one judged under only some of them.
        val pinned = uk.noammm.kav.ext.Snapshot.parseWhole(trip.policy) ?: return null
        val c = uk.noammm.kav.ext.Hooks.check(planned.plan.laidOut(), planned.resolved, q, pinned)
        if (c.failure != null) return null
        c.routes
    }
    val route = routes.firstOrNull { !planned.plan.isTaxiCard(it) && it.legs.isNotEmpty() } ?: return null
    val next = switched(journey, from, route, planned.resolved, stopovers)
    return next to stepAfterSwitch(next, from, current)
}

// The trip going on with a new route: the trip's legs it keeps, at their places, then the route, under the trip's own name
// and time.
internal fun switched(
    journey: ActiveJourney, from: ReplanFrom, route: Transit.Itinerary, b: Transit.Resolved, stopovers: List<Transit.Place>,
): ActiveJourney {
    val trip = journey.trip
    val a = journey.resolved
    val legs = trip.legs.take(from.keep) + route.legs
    val joined = Transit.Itinerary(trip.guid, trip.group, legs, trip.dep, route.arr, tags = route.tags, sectionId = trip.sectionId,
        provider = trip.provider, policy = trip.policy)
    return journey.copy(
        trip = joined,
        resolved = Transit.Resolved(a.lines + b.lines, a.stops + b.stops, a.routeTypes + b.routeTypes, a.live + b.live,
            a.shapes + b.shapes, a.pollSecs, a.patterns + b.patterns, a.delays + b.delays),
        chosen = journey.chosen.filterKeys { it < from.keep },
        stopovers = stopovers,
        before = journey.before + trip,
    )
}

// The step a trip is on once it has gone on with a new route: still the one it was on when the route starts where a ride
// it keeps ends (on that ride, or about to board it); else the route's first, after the steps of the legs it keeps. The
// rider is under way by then, so never the start, which keeps the trip's own time as it was started.
internal fun stepAfterSwitch(next: ActiveJourney, from: ReplanFrom, current: Int): Int = when {
    from.time > 0 -> current
    else -> buildSteps(Transit.Itinerary(next.trip.guid, next.trip.group, next.trip.legs.take(from.keep), next.trip.dep, next.trip.arr),
        next.fromLabel, next.toLabel).size - 1
}.coerceIn(0, buildSteps(next.trip, next.fromLabel, next.toLabel).lastIndex)

// The trip's alerts already given, once it has gone on with a new route under its own name: those of the legs it changed
// may come again, and those of the failures behind it are done with. A leg the same at its place keeps its alerts, as
// a ride after a walk walked again does.
internal fun firedAfterSwitch(fired: Set<String>, was: List<Transit.Leg>, now: List<Transit.Leg>): Set<String> =
    fired.filterNot { k ->
        k.startsWith("replan-") || LEG_ALERT.matchEntire(k)?.groupValues?.get(1)?.toIntOrNull()?.let { was.getOrNull(it) != now.getOrNull(it) } == true
    }.toSet()

// A ride's alerts by its leg (tripAlert): arriving, delayed, get off.
private val LEG_ALERT = Regex("(?:wait|traffic|ride)-(\\d+)")
