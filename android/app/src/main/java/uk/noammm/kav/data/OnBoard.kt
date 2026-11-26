package uk.noammm.kav.data

import uk.noammm.kav.ui.Operators
import uk.noammm.kav.ui.alongPath
import uk.noammm.kav.ui.distanceToPath
import uk.noammm.kav.ui.metres
import uk.noammm.kav.ui.splitPath
import java.time.LocalDate

// A rider already on a bus, with no trip planned (Get off here, on a line's page, or Follow on Home's card): a trip of one
// ride, made from the timetable on the phone, that Kav follows as any other. Its ride is, from the stop of the way nearest
// the rider to the stop they get off at: the run of the live bus the rider is on, when Kav knows it (first); else the
// next run to come to that stop, one at most BEHIND_S past its time there still counting as there now, never one that has
// gone on ahead; with none coming within SOON_S (after the last of the day or the evening), the last gone if it can still
// be on its way, else none. With no position near the way, the line's next trip to get there that is under way, from the
// last stop it has left. Its guid is Kav's own (KavPlan.owns), so its bus's live times come as a planned trip's do,
// matched by when the timetable has it at the stop it was boarded at.
object OnBoard {
    // Ids of their own, apart from Kav's planner's, an extension's handles (RefTable) and KavLive's negative ones.
    private const val STOPS = 800_000_000
    private const val LINES = 900_000_000

    // How near a stop of the way the rider has to be for it to be the one they boarded at.
    private const val NEAR_M = 1_000.0
    // How late a bus can be on its timetable at the stop they get off at and still be the one they're on.
    private const val LATE_S = 15 * 60L
    // How long past its time at the rider's stop a run can be and still be there now, the bus a little late.
    private const val BEHIND_S = 2 * 60L
    // How soon the next run has to come for it to be the one, rather than the last gone running late.
    private const val SOON_S = 30 * 60L
    // How far behind the rider along the road a live bus's last report can be and still be the bus they're on: buses
    // report every 15 to 40 seconds, and the report reaches Kav later still. And how far ahead, for a report's error.
    private const val BUS_BEHIND_M = 400.0
    private const val BUS_AHEAD_M = 50.0
    // How far off the road a stop can be for the ride to be drawn on that road rather than stop to stop.
    private const val ROAD_M = 100.0

    class Built(val trip: Transit.Itinerary, val resolved: Transit.Resolved, val from: String, val to: String)

    // lines: the timetable's routes that are the line (a train's, every train of it); way: its stops as the page lists
    // them, in the timetable (-1 where it hasn't one); to: the stop got off at, in way; road: the way's road, or empty;
    // first: when the live bus the rider is on left its first stop (epoch seconds), when Kav knows it.
    // Null when no trip of the line gets there now.
    fun trip(
        net: Net, lines: Set<Int>, way: List<Int>, to: Int, here: Pair<Double, Double>?, now: Long,
        road: List<Pair<Double, Double>> = emptyList(), today: LocalDate = Schedules.today(), first: Long? = null,
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
            val start get() = base + net.stDep[net.tripStart[trip]]
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
        val run = first?.let { f -> runs.filter { kotlin.math.abs(it.start - f) <= 60 }.minByOrNull { kotlin.math.abs(it.start - f) } }
            ?: if (near != null) {
                val next = runs.filter { it.dep >= now - BEHIND_S }.minByOrNull { it.dep }
                val gone = runs.filter { it.dep < now - BEHIND_S }.maxByOrNull { it.dep }
                if (next != null && next.dep - now <= SOON_S) next else gone
            } else runs.filter { it.arr >= now }.minByOrNull { it.arr }
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

    // Of the line's live buses (each: when it left its first stop, and where it last reported), the one the rider is on:
    // the nearest at or just behind them along the road. Null without a road or such a bus.
    fun liveBus(road: List<Pair<Double, Double>>, here: Pair<Double, Double>, buses: List<Pair<Long, Pair<Double, Double>>>): Long? {
        if (road.size < 2) return null
        if (distanceToPath(here.first, here.second, road) > ROAD_M) return null
        val rider = alongPath(here.first, here.second, road)
        return buses.filter { distanceToPath(it.second.first, it.second.second, road) <= ROAD_M }
            .map { it.first to rider - alongPath(it.second.first, it.second.second, road) }
            .filter { it.second in -BUS_AHEAD_M..BUS_BEHIND_M }
            .minByOrNull { kotlin.math.abs(it.second) }?.first
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
