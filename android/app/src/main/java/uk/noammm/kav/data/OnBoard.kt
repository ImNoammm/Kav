package uk.noammm.kav.data

import uk.noammm.kav.ui.Operators
import uk.noammm.kav.ui.distanceToPath
import uk.noammm.kav.ui.metres
import uk.noammm.kav.ui.splitPath
import java.time.LocalDate

// A rider already on a bus, with no trip planned (Get off here, on a line's page): a trip of one ride, made from the
// timetable on the phone, that Kav follows as any other. Its ride is the line's trip running now from the stop of the
// way nearest the rider to the stop they get off at; with no position near the way, the line's next trip to get there
// that is under way, from the last stop it has left. Its guid is Kav's own (KavPlan.owns), so its bus's live times come
// as a planned trip's do, matched by when the timetable has it at the stop it was boarded at.
object OnBoard {
    // Ids of their own, apart from Kav's planner's, an extension's handles (RefTable) and KavLive's negative ones.
    private const val STOPS = 800_000_000
    private const val LINES = 900_000_000

    // How near a stop of the way the rider has to be for it to be the one they boarded at.
    private const val NEAR_M = 1_000.0
    // How late a bus can be on its timetable at the stop they get off at and still be the one they're on.
    private const val LATE_S = 15 * 60L
    // How far off the road a stop can be for the ride to be drawn on that road rather than stop to stop.
    private const val ROAD_M = 100.0

    class Built(val trip: Transit.Itinerary, val resolved: Transit.Resolved, val from: String, val to: String)

    // lines: the timetable's routes that are the line (a train's, every train of it); way: its stops as the page lists
    // them, in the timetable (-1 where it hasn't one); to: the stop got off at, in way; road: the way's road, or empty.
    // Null when no trip of the line gets there now.
    fun trip(
        net: Net, lines: Set<Int>, way: List<Int>, to: Int, here: Pair<Double, Double>?, now: Long,
        road: List<Pair<Double, Double>> = emptyList(), today: LocalDate = Schedules.today(),
    ): Built? {
        val alight = way.getOrNull(to)?.takeIf { it >= 0 } ?: return null
        if (to <= 0) return null
        val preceding = way.take(to).lastOrNull { it >= 0 } ?: return null
        val near = here?.let { (la, lo) ->
            (0 until to).filter { way[it] >= 0 }.minByOrNull { metres(la, lo, net.lat[way[it]], net.lon[way[it]]) }
                ?.takeIf { metres(la, lo, net.lat[way[it]], net.lon[way[it]]) <= NEAR_M }?.let { way[it] }
        }
        // Each run of the line through that stop, today's and yesterday's still out after midnight: where it's boarded
        // (its stop time) and its times there and at the stop got off at.
        class Run(val trip: Int, val board: Int, val off: Int, val base: Long) {
            val dep get() = base + net.stDep[board]
            val arr get() = base + net.stDep[off]
        }
        val runs = ArrayList<Run>()
        // The line's own trips, not the stop's departures (Net.dConn), which leave out where a trip ends.
        val trips = net.tripRoute.indices.filter { net.tripRoute[it] in lines }
        for (back in 0L..1L) {
            val day = today.minusDays(back)
            val base = Schedules.base(day)
            val dow = day.dayOfWeek.value % 7
            for (t in trips) {
                if (!net.runsOn(t, dow)) continue
                val off = (net.tripStart[t] + 1 until net.tripStart[t + 1]).firstOrNull { net.stStop[it] == alight } ?: continue
                // Keep the displayed direction even without a location: it must pass the preceding stop first.
                if ((net.tripStart[t] until off).none { net.stStop[it] == preceding }) continue
                if (base + net.stDep[off] < now - LATE_S) continue
                val board = if (near != null) {
                    (off - 1 downTo net.tripStart[t]).firstOrNull { net.stStop[it] == near } ?: continue
                } else {
                    // Not placed: the last stop the run has left by now, else its first.
                    (off - 1 downTo net.tripStart[t]).firstOrNull { base + net.stDep[it] <= now } ?: net.tripStart[t]
                }
                runs += Run(t, board, off, base)
            }
        }
        val run = if (near != null) runs.minByOrNull { kotlin.math.abs(it.dep - now) }
            else runs.filter { it.arr >= now }.minByOrNull { it.arr }
        return run?.let { built(net, it.trip, it.board, it.off, it.base, road) }
    }

    private fun built(net: Net, t: Int, board: Int, off: Int, base: Long, road: List<Pair<Double, Double>>): Built {
        val route = net.tripRoute[t]
        val type = net.rType[route]
        val agency = Operators.gtfsOf(net.agencyOf(route))?.let { KavPlan.agencyId(it, type) } ?: KavLive.agencyOf(type)
        val line = LINES + route
        val calls = (board..off).map { net.stStop[it] }
        val stops = calls.distinct().associate { s ->
            (STOPS + s) to Transit.StopInfo(STOPS + s, net.name[s], net.code[s].toString(), net.lat[s], net.lon[s])
        }
        val ids = calls.map { STOPS + it }
        val dep = base + net.stDep[board]
        val arr = base + net.stDep[off]
        val trip = KavLive.tripIdOf("onboard:$t:$base")
        val first = net.stStop[net.tripStart[t]]
        val last = net.tripLast(t)
        val info = Transit.LineInfo(-1, net.rShort[route], agency, net.name[first], net.name[last], "", net.rLong[route])
        val timetabled = listOf(Transit.Departure(trip, dep))
        val points = calls.map { net.lat[it] to net.lon[it] }
        val legs = listOf(
            Transit.Leg(Transit.LegKind.WAIT, lineId = line, dep = dep, arr = dep, fromStop = ids.first(), toStop = ids.first(),
                nextDeps = timetabled),
            Transit.Leg(Transit.LegKind.RIDE, lineId = line, tripId = trip, dep = dep, arr = arr, stops = ids,
                fromStop = ids.first(), toStop = ids.last(), nextDeps = timetabled, shape = along(road, points)),
        )
        val itinerary = Transit.Itinerary(
            guid = "kav-onboard-" + Integer.toHexString("$t:$base:$board:$off".hashCode()),
            group = 2, legs = legs, dep = dep, arr = arr,
        )
        val resolved = Transit.Resolved(mapOf(line to info), stops, mapOf(agency to type))
        return Built(itinerary, resolved, net.stopName(calls.first()), net.stopName(calls.last()))
    }

    // The stretch of the road from the first stop to the last, where the road passes them; else stop to stop.
    internal fun along(road: List<Pair<Double, Double>>, points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val a = points.first(); val b = points.last()
        if (road.size < 2 || points.any { distanceToPath(it.first, it.second, road) > ROAD_M }) return points
        val ahead = splitPath(road, a.first, a.second).second
        val piece = splitPath(ahead, b.first, b.second).first
        return if (piece.size >= 2) piece else points
    }
}
