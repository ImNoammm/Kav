package uk.noammm.kav.ui

import uk.noammm.kav.data.Source
import uk.noammm.kav.data.Transit

// What a planner can bring: every planner Kav has today plans every kind of route.
fun filtersOf(planner: Source): List<ResultFilter> = ResultFilter.entries

enum class ResultFilter {
    BUS, TRAIN, LIGHT_RAIL, CARMELIT_RAKAVLIT, SHUTTLE, TAXI, BIKE, WALK;

    val label: String
        get() = when (this) {
            BUS -> T("Bus", "אוטובוס")
            TRAIN -> T("Train", "רכבת")
            LIGHT_RAIL -> T("Light rail", "רכבת קלה")
            CARMELIT_RAKAVLIT -> T("Carmelit & Rakavlit", "כרמלית ורכבלית")
            SHUTTLE -> T("Shuttle", "שאטל")
            TAXI -> T("Taxi", "מונית")
            BIKE -> T("Bike", "אופניים")
            WALK -> T("Walking only", "הליכה בלבד")
        }
}

fun routeTypesFor(on: Set<ResultFilter>): List<Int> {
    val all = listOf(0, 1, 2, 3, 4, 5, 6, 7)
    val off = HashSet<Int>()
    if (ResultFilter.BUS !in on) off.add(3)
    if (ResultFilter.TRAIN !in on) off.add(2)
    if (ResultFilter.LIGHT_RAIL !in on) off.addAll(listOf(0, 1))
    if (ResultFilter.CARMELIT_RAKAVLIT !in on) off.addAll(listOf(5, 6, 7))
    return all.filterNot { it in off }
}

fun filterResults(list: List<Transit.Itinerary>, on: Set<ResultFilter>, r: Transit.Resolved): List<Transit.Itinerary> {
    if (on.size == ResultFilter.entries.size) return list
    return list.filter { it ->
        val legs = it.legs
        val hasRide = legs.any { l -> l.kind == Transit.LegKind.RIDE }
        val hasTaxi = legs.any { l -> l.kind == Transit.LegKind.TAXI }
        val hasBike = legs.any { l -> l.kind == Transit.LegKind.BIKE }
        if (ResultFilter.TAXI !in on && hasTaxi) return@filter false
        if (ResultFilter.BIKE !in on && hasBike) return@filter false
        if (ResultFilter.WALK !in on && !hasRide && !hasTaxi && !hasBike) return@filter false
        legs.filter { l -> l.kind == Transit.LegKind.RIDE }.all { ride ->
            val info = r.line(ride.lineId) ?: return@all true
            val rt = r.routeType(info.agencyId)
            if (rt == 711) return@all ResultFilter.SHUTTLE in on
            when (modeOf(rt)) {
                Mode.BUS -> ResultFilter.BUS in on
                Mode.TRAIN -> ResultFilter.TRAIN in on
                Mode.TAXI -> ResultFilter.BUS in on
                Mode.TRAM, Mode.SUBWAY -> ResultFilter.LIGHT_RAIL in on
                Mode.FUNICULAR, Mode.CABLE, Mode.GONDOLA -> ResultFilter.CARMELIT_RAKAVLIT in on
                else -> true
            }
        }
    }
}
