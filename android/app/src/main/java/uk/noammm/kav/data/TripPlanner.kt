package uk.noammm.kav.data

// A search for trips, the same whoever plans it.
class PlanRequest(
    val from: Pair<Double, Double>, val to: Pair<Double, Double>, val whenMs: Long, val timeType: Int,
    val routeTypes: List<Int>, val taxi: Boolean, val stopovers: List<Transit.Place> = emptyList(),
)

// Who plans trips. KavPlan.plan asks the one picked (plannerFor) and does the rest itself: the timetable check, the
// operators' names and live times.
interface TripPlanner {
    fun plan(r: PlanRequest): KavPlan.Planned
}

// Kav's server or the rider's own, at `at`: stops on the way go in its path, at most three, with how long to stay at each.
// A server from before stops could be stayed at doesn't know a path with minutes in it (404): the parts are then planned
// one by one and joined here, as for a planner that takes no stops.
class ServerPlanner(private val at: String) : TripPlanner {
    override fun plan(r: PlanRequest): KavPlan.Planned = try {
        KavPlan.parse(
            KavPlan.asked(KavPlan.path(r.from, r.to, r.whenMs, r.timeType, r.routeTypes, r.taxi, r.stopovers.map { it.lat to it.lon },
                r.stopovers.map { it.stayMin }), at),
            r.stopovers.map { it.name },
        )
    } catch (e: java.io.IOException) {
        if (e.message != "Kav live HTTP 404" || r.stopovers.none { it.stayMin > 0 }) throw e
        LegByLeg(this).plan(r)
    }
}

// Bus Nearby or EGG, whose planner leaves out stops on the way it's given (LegByLeg adds them) and gives its routes no
// labels (RouteTags adds them).
class HopOnPlanner private constructor(private val app: Source) : TripPlanner {
    override fun plan(r: PlanRequest) = KavPlan.parse(
        BusNearby.plan(r.from, r.to, r.whenMs, r.timeType, r.routeTypes, app),
    ).let { p -> KavPlan.Planned(Transit.Plan(RouteTags.of(p.plan.itineraries), p.plan.sections), p.resolved, p.errors) }

    companion object {
        val BUS_NEARBY = HopOnPlanner(Source.BUS_NEARBY)
        val EGG = HopOnPlanner(Source.EGG)
    }
}

// Stops on the way for a planner that takes none: each part planned on its own, one request a part, and joined end to
// end. Leaving at a time, the first part leaves then and each next part leaves once the one before arrives; arriving by
// a time (or the last way tonight), it goes back from the end. A stop the rider stays at is left that much after getting
// there, at the soonest. Each route of the part asked first goes on with the
// route of the next part that fits it and gets there first (back from the end, that leaves last), so riders still get
// a few to choose from. Not the one that leaves first: a walk leaves at once, however long it takes.
class LegByLeg(private val inner: TripPlanner) : TripPlanner {
    override fun plan(r: PlanRequest): KavPlan.Planned {
        if (r.stopovers.isEmpty()) return inner.plan(r)
        val stops = r.stopovers.take(MAX_STOPS)
        val points = listOf(r.from) + stops.map { it.lat to it.lon } + listOf(r.to)
        val parts = points.zipWithNext()
        val backwards = r.timeType == Transit.TIME_ARRIVAL || r.timeType == Transit.TIME_LAST
        val answers = ArrayList<KavPlan.Planned>()
        var chains: List<List<Transit.Itinerary>> = emptyList()
        for (k in parts.indices) {
            val (a, b) = if (backwards) parts[parts.lastIndex - k] else parts[k]
            // The seconds to stay at the stop this part meets the ones before it at: where it ends, going back from the
            // end, else where it starts.
            val stay = if (k == 0) 0L else stops[if (backwards) parts.lastIndex - k else k - 1].stayMin * 60L
            val whenMs = when {
                k == 0 -> r.whenMs
                backwards -> (chains.maxOf { it.first().dep } - stay) * 1000
                else -> (chains.minOf { it.last().arr } + stay) * 1000
            }
            val type = if (k == 0) r.timeType else if (backwards) Transit.TIME_ARRIVAL else Transit.TIME_DEPARTURE
            val got = inner.plan(PlanRequest(a, b, whenMs, type, r.routeTypes, taxi = false))
            answers += got
            // Not a part cycled: it leaves at once, so it would take every part, and the rider's filter every route.
            val routes = got.plan.itineraries.filter {
                it.legs.isNotEmpty() && !got.plan.isTaxiCard(it) && got.plan.schedule() !== it && it.legs.none { l -> l.kind == Transit.LegKind.BIKE }
            }
            chains = if (k == 0) routes.take(OPTIONS).map { listOf(it) } else chains.mapNotNull { chain ->
                if (backwards) routes.filter { it.arr <= chain.first().dep - stay }.maxByOrNull { it.dep }?.let { listOf(it) + chain }
                else routes.filter { it.dep >= chain.last().arr + stay }.minByOrNull { it.arr }?.let { chain + it }
            }
            if (chains.isEmpty()) break
        }
        val joined = RouteTags.of(chains.map { join(it, stops) }.distinctBy { t -> t.legs.map { "${it.kind}:${it.lineId}:${it.dep}" } })
        return KavPlan.Planned(Transit.Plan(joined), merge(answers.map { it.resolved }), answers.flatMap { it.errors }.distinct())
    }

    // The parts end to end, the first leg on from each stop on the way saying so (a ride rather than the wait for it),
    // until that part sets out.
    private fun join(parts: List<Transit.Itinerary>, stops: List<Transit.Place>): Transit.Itinerary {
        val fares = parts.map { it.fare }
        val legs = parts.flatMapIndexed { k, part ->
            val stop = stops.getOrNull(k - 1)
            val first = part.legs.indexOfFirst { it.kind != Transit.LegKind.WAIT }
            if (stop == null || first < 0) part.legs else part.legs.mapIndexed { i, l ->
                if (i == first) l.copy(visit = Transit.Visit(stop.name, parts[k - 1].arr, part.dep, stop.stayMin > 0)) else l
            }
        }
        return Transit.Itinerary(
            guid = parts.joinToString("+") { it.guid }, group = 0, legs = legs,
            dep = parts.first().dep, arr = parts.last().arr,
            fare = if (fares.all { it >= 0 }) fares.sum() else -1, currency = parts.first().currency,
            co2g = parts.map { it.co2g }.let { c -> if (c.all { it >= 0 }) c.sum() else -1 },
        )
    }

    private fun merge(all: List<Transit.Resolved>) = Transit.Resolved(
        lines = all.fold(emptyMap()) { m, x -> m + x.lines },
        stops = all.fold(emptyMap()) { m, x -> m + x.stops },
        routeTypes = all.fold(emptyMap()) { m, x -> m + x.routeTypes },
        live = all.fold(emptyMap()) { m, x -> m + x.live },
        shapes = all.fold(emptyMap()) { m, x -> m + x.shapes },
        pollSecs = all.minOf { it.pollSecs },
        patterns = all.fold(emptyMap()) { m, x -> m + x.patterns },
        delays = all.fold(emptyMap()) { m, x -> m + x.delays },
    )

    companion object {
        // As many stops as Kav's server plans through.
        const val MAX_STOPS = 3
        const val OPTIONS = 5
    }
}

// The planner at a server address (KavPlan.planner, plannerOf): `by` tells HopOn's two apps apart, which share one.
fun plannerFor(at: String, by: Source): TripPlanner = when (at) {
    BusNearby.SERVER -> LegByLeg(if (by == Source.EGG) HopOnPlanner.EGG else HopOnPlanner.BUS_NEARBY)
    else -> ServerPlanner(at)
}

// The labels Kav's server gives its routes (server/live.py tag()), worked out on the phone for routes that come with
// none: Bus Nearby's and EGG's, and routes joined part by part. Warnings first, then what a route is best at; two at most.
// Whether a ride is a line's last of the day isn't known here, so "Last trip" never shows.
internal object RouteTags {
    private const val TIGHT = 120L // seconds to spare at a change, at most, for a tight connection
    private const val LONG_WALK = 1200 // metres walked in all
    private const val LONG_WAIT = 1300L // seconds waiting at one change
    private const val FREQUENT = 300L // seconds between the first line's departures, on average, at most
    private val ORDER = listOf("risky_transfer", "long_walk", "long_wait_time", "last_departure", "early_arrival", "direct",
        "least_walking", "low_transfer", "high_frequency", "flexible_route")

    private class Facts(val t: Transit.Itinerary) {
        val rides = t.rides.size
        val walk = t.legs.filter { it.kind == Transit.LegKind.WALK }.sumOf { (it.arr - it.dep).coerceAtLeast(0) }
        val walkM = t.legs.filter { it.kind == Transit.LegKind.WALK }.sumOf { it.meters }
        // The time to spare at each change: from one ride's end to the next's start, less the walk between. Not across a
        // stop the rider stays at: they leave it when they choose, so it's no change to miss nor a wait.
        private val spare = t.legs.indices.filter { t.legs[it].kind == Transit.LegKind.RIDE }.zipWithNext { i, j ->
            if (t.legs.subList(i + 1, j + 1).any { it.visit?.stay == true }) null
            else t.legs[j].dep - t.legs[i].arr - t.legs.subList(i + 1, j).filter { it.kind == Transit.LegKind.WALK }.sumOf { it.arr - it.dep }
        }.filterNotNull()
        val slack = spare.minOrNull()
        val wait = spare.maxOrNull() ?: 0L
        val headway = t.rides.firstOrNull()?.let { r ->
            val at = (listOf(r.dep) + r.nextDeps.map { it.timeUtc }).filter { it > 0 }.distinct().sorted()
            if (at.size >= 2) (at.last() - at.first()) / (at.size - 1) else null
        }
        val choices = t.rides.isNotEmpty() && t.rides.all { it.lineChoices.size > 1 }
    }

    fun of(routes: List<Transit.Itinerary>): List<Transit.Itinerary> {
        if (routes.isEmpty() || routes.any { it.tags.isNotEmpty() }) return routes
        val f = routes.map(::Facts)
        val found = f.map { r ->
            buildList {
                if (r.slack != null && r.slack <= TIGHT) add("risky_transfer")
                if (r.walkM >= LONG_WALK) add("long_walk")
                if (r.wait >= LONG_WAIT) add("long_wait_time")
                if (r.headway != null && r.headway <= FREQUENT) add("high_frequency")
                if (r.choices) add("flexible_route")
            }.toMutableList()
        }
        fun one(name: String, of: List<Int>, by: Comparator<Int>) { of.minWithOrNull(by)?.let { found[it] += name } }
        val every = routes.indices.toList()
        one("early_arrival", every, compareBy<Int>({ f[it].t.arr }, { f[it].rides }, { f[it].walk }, { -f[it].t.dep }))
        one("direct", every.filter { f[it].rides == 1 }, compareBy({ f[it].t.arr }, { f[it].walk }))
        one("least_walking", every, compareBy({ f[it].walkM }, { f[it].t.arr }))
        val fewest = f.minOf { it.rides }
        if (fewest > 1) one("low_transfer", every.filter { f[it].rides == fewest }, compareBy({ f[it].t.arr }, { f[it].walk }))
        return routes.mapIndexed { i, t ->
            val tags = found[i].distinct().sortedBy(ORDER::indexOf).take(2)
            Transit.Itinerary(t.guid, t.group, t.legs, t.dep, t.arr, t.fare, t.currency, t.co2g, t.accessible, tags,
                t.section, t.sectionId, t.original)
        }
    }
}
