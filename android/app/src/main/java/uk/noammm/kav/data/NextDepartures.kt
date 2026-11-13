package uk.noammm.kav.data

import java.time.Instant
import java.time.ZoneId

internal class LineDepartures(val route: Int, val departures: List<Transit.Departure>, val number: String = "")

// Catchable alternatives for an old ride, still boarding and alighting at the same public stops.
// Called in the background: a busy stop can have thousands of timetable rows.
internal fun Net.nextDepartures(
    ride: Transit.Leg, r: Transit.Resolved, now: Long, board: KavLive.Board? = null,
): List<LineDepartures> {
    val from = r.stop(ride.fromStop)?.code?.toIntOrNull() ?: return emptyList()
    val to = r.stop(ride.toStop)?.code?.toIntOrNull() ?: return emptyList()
    val type = r.routeType(r.line(ride.lineId)?.agencyId ?: -1)
    if (from <= 0 || to <= 0) return emptyList()
    val rows = ArrayList<Int>()
    val boarding = code.indices.filter { code[it] == from }
    for (s in boarding) {
        for (i in dStart[s] until dStart[s + 1]) {
            val st = dConn[i]
            val trip = tripOf(st)
            val route = tripRoute[trip]
            if (rType[route] != type) continue
            if ((st + 1 until tripStart[trip + 1]).any { code[stStop[it]] == to }) rows += st
        }
    }
    fun grouped(deps: List<Pair<Int, Transit.Departure>>, labels: Map<Int, String> = emptyMap()): List<LineDepartures> = deps
        .groupBy { (route, _) -> Triple(labels[route] ?: rShort[route], rAgency[route], rType[route]) }
        .values.map { line -> LineDepartures(line.first().first,
            line.map { it.second }.distinctBy { it.timeUtc }.sortedBy { it.timeUtc }.take(3), labels[line.first().first].orEmpty()) }
        .sortedBy { it.departures.first().timeUtc }
    val excluded = HashSet<Pair<Int, Long>>()
    if (board != null) {
        val byTrip = rows.groupBy { tripOf(it) }
        val matched = boarding.flatMap { s -> KavLive.boardTimes(this, s, board, now) }
            .filter { time ->
                val reaches = byTrip[time.trip]?.any { st ->
                    val dest = time.to
                    val end = if (dest == null) tripStart[time.trip + 1] - 1 else
                        (st + 1 until tripStart[time.trip + 1]).firstOrNull {
                            val station = name[stStop[it]]
                            station.startsWith(dest) || dest.startsWith(station)
                        } ?: return@any false
                    (st + 1..end).any { code[stStop[it]] == to }
                } == true
                if (!reaches && time.to != null && time.trip in byTrip)
                    excluded += time.trip to (now - KavLive.secondOfDay(now) + time.static)
                time.due.expected >= now - 60 && reaches
            }
        val live = matched
            .map { tripRoute[it.trip] to KavLive.dueArrival(it.due, ride.fromStop, ride.lineId, now = now).departure() }
        if (live.isNotEmpty()) return grouped(live, matched.associate { tripRoute[it.trip] to it.due.number })
    }
    val zone = ZoneId.of("Asia/Jerusalem")
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val out = ArrayList<Pair<Int, Transit.Departure>>()
    for (offset in -1L..7L) {
        val day = today.plusDays(offset)
        val base = Schedules.base(day)
        for (st in rows) {
            val trip = tripOf(st)
            val at = base + stDep[st]
            if (at >= now && (trip to at) !in excluded && runsOn(trip, day.dayOfWeek.value % 7))
                out += tripRoute[trip] to Transit.Departure(0, at, platform = platform[stStop[st]])
        }
    }
    return grouped(out)
}
