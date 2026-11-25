package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject

// A search for trips, the same whoever plans it.
class PlanRequest(
    val from: Pair<Double, Double>, val to: Pair<Double, Double>, val whenMs: Long, val timeType: Int,
    val routeTypes: List<Int>, val taxi: Boolean, val stopovers: List<Transit.Place> = emptyList(),
    val settings: PlanSettings = PlanSettings.USUAL,
)

// Who plans trips. KavPlan.plan asks the one picked (plannerFor) and does the rest itself: the timetable check, the
// operators' names and live times.
interface TripPlanner {
    fun plan(r: PlanRequest): KavPlan.Planned
}

// Kav's server or the rider's own, at `at`: stops on the way go in its path, at most three, with how long to stay at each.
// A server from before stops could be stayed at doesn't know a path with minutes in it (404): the parts are then planned
// one by one and joined here, as for a planner that takes no stops. One from before planner settings doesn't know a path
// with them (404) either: it's asked again with the usual.
class ServerPlanner(private val at: String) : TripPlanner {
    override fun plan(r: PlanRequest): KavPlan.Planned = try {
        KavPlan.parse(
            KavPlan.asked(KavPlan.path(r.from, r.to, r.whenMs, r.timeType, r.routeTypes, r.taxi, r.stopovers.map { it.lat to it.lon },
                r.stopovers.map { it.stayMin }) + r.settings.path(), at),
            r.stopovers.map { it.name },
        )
    } catch (e: java.io.IOException) {
        if (e.message != "Kav live HTTP 404") throw e
        if (r.settings != PlanSettings.USUAL) {
            plan(PlanRequest(r.from, r.to, r.whenMs, r.timeType, r.routeTypes, r.taxi, r.stopovers))
        } else {
            if (r.stopovers.none { it.stayMin > 0 }) throw e
            LegByLeg(this).plan(r)
        }
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
            val got = inner.plan(PlanRequest(a, b, whenMs, type, r.routeTypes, taxi = false, settings = r.settings))
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
            provider = parts.first().provider, policy = parts.first().policy,
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

// The planner at a server address (KavPlan.planner, plannerOf), or an extension's at its address (Extensions.address).
fun plannerFor(at: String): TripPlanner =
    if (at == KavPlan.TRANSITOUS) TransitousPlanner() else uk.noammm.kav.ext.Extensions.plannerAt(at) ?: ServerPlanner(at)

// The labels Kav's server gives its routes (server/live.py tag()), worked out on the phone for routes that come with
// none: routes joined part by part. Warnings first, then what a route is best at; two at most.
// Whether a ride is a line's last of the day isn't known here, so "Last trip" never shows.
internal object RouteTags {
    private const val TIGHT = 120L // seconds to spare at a change, at most, for a tight connection
    private const val LONG_WALK = 1200 // metres walked in all
    private const val LONG_WAIT = 1300L // seconds waiting at one change
    private const val FREQUENT = 300L // seconds between the first line's departures, on average, at most
    internal val ORDER = listOf("risky_transfer", "long_walk", "long_wait_time", "last_departure", "early_arrival", "direct",
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
        // A fact about a route, as Kav's server has it (live.py tag()): every route without a change, beside one with one.
        if (f.any { it.rides > 1 }) every.filter { f[it].rides == 1 }.forEach { found[it] += "direct" }
        one("least_walking", every, compareBy({ f[it].walkM }, { f[it].t.arr }))
        val fewest = f.minOf { it.rides }
        if (fewest > 1) one("low_transfer", every.filter { f[it].rides == fewest }, compareBy({ f[it].t.arr }, { f[it].walk }))
        return routes.mapIndexed { i, t ->
            val tags = found[i].distinct().sortedBy(ORDER::indexOf).take(2)
            Transit.Itinerary(t.guid, t.group, t.legs, t.dep, t.arr, t.fare, t.currency, t.co2g, t.accessible, tags,
                t.section, t.sectionId, provider = t.provider, policy = t.policy)
        }
    }
}

// #113: trips planned by Transitous (api.transitous.org, MOTIS 2), with the ministry's timetable it has for Israel and no
// live times. Its answer is turned into the plan Kav's server gives, in the ministry's own ids (a stop's code, a line's
// route id, a trip's id), so Kav's plan reader and live times take it as they take the server's. Stops on the way are
// planned part by part, as for any planner that takes none.
class TransitousPlanner : TripPlanner {
    override fun plan(r: PlanRequest): KavPlan.Planned {
        if (r.stopovers.isNotEmpty()) return LegByLeg(this).plan(r)
        val at = java.time.Instant.ofEpochMilli(if (r.whenMs > 0) r.whenMs else System.currentTimeMillis())
            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        val modes = r.routeTypes.distinct().sorted().mapNotNull { MODES[it] }.distinct()
        val speed = when (r.settings.pace) {
            PlanSettings.Pace.SLOW -> "&pedestrianSpeed=1.0"
            PlanSettings.Pace.FAST -> "&pedestrianSpeed=1.6"
            else -> ""
        }
        val url = String.format(java.util.Locale.US, "%s/api/v5/plan?fromPlace=%.6f,%.6f&toPlace=%.6f,%.6f&time=%s&arriveBy=%s&numItineraries=6%s%s",
            BASE, r.from.first, r.from.second, r.to.first, r.to.second, at, r.timeType == Transit.TIME_ARRIVAL, speed,
            if (modes.isEmpty()) "" else "&transitModes=" + modes.joinToString(","))
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", "Kav-fork (Transitous planner)"); c.setRequestProperty("Accept", "application/json")
        val body = try {
            if (c.responseCode !in 200..299) throw java.io.IOException("Transitous HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        return KavPlan.parse(convert(body))
    }

    companion object {
        private const val BASE = "https://api.transitous.org"
        private const val PREFIX = "il-Israel-MOT_"
        // GTFS route types to MOTIS modes.
        private val MODES = mapOf(0 to "TRAM", 1 to "SUBWAY", 2 to "RAIL", 3 to "BUS", 4 to "FERRY", 5 to "CABLE_CAR", 6 to "AERIAL_LIFT", 7 to "FUNICULAR")

        private fun epoch(s: String): Long = try { java.time.Instant.parse(s).epochSecond } catch (e: Exception) { 0L }

        // MOTIS's answer as a plan in the shape of server/API.md. An itinerary with a ride Kav can't name in the ministry's
        // ids (not numbers) is left out whole.
        internal fun convert(body: String): String {
            val o = JSONObject(body)
            val stops = JSONObject(); val lines = JSONObject(); val agencies = JSONObject(); val routes = JSONArray()
            fun code(p: JSONObject?): String? {
                if (p == null) return null
                val code = p.optString("stopCode").ifBlank { return null }
                if (code.toIntOrNull() == null) return null
                if (!stops.has(code)) stops.put(code, JSONArray().put(code).put(p.optString("name")).put(p.optDouble("lat")).put(p.optDouble("lon")))
                return code
            }
            val found = ArrayList<JSONObject>()
            o.optJSONArray("itineraries")?.let { a -> for (i in 0 until a.length()) found += a.getJSONObject(i) }
            o.optJSONArray("direct")?.let { a -> for (i in 0 until minOf(a.length(), 1)) found += a.getJSONObject(i) }
            for (it in found) {
                val legs = JSONArray()
                var ok = true
                val parts = it.optJSONArray("legs") ?: JSONArray()
                for (j in 0 until parts.length()) {
                    val l = parts.getJSONObject(j)
                    val dep = epoch(l.optString("startTime")); val arr = epoch(l.optString("endTime"))
                    val shape = l.optJSONObject("legGeometry")?.optString("points").orEmpty()
                    val precision = l.optJSONObject("legGeometry")?.optInt("precision", 6) ?: 6
                    val number = l.optString("routeShortName")
                    if (number.isBlank() && !l.has("routeId")) {
                        legs.put(JSONObject().put("k", "w").put("dep", dep).put("arr", arr).put("m", l.optDouble("distance", 0.0).toInt())
                            .put("shape", shape).put("precision", precision))
                        continue
                    }
                    val route = l.optString("routeId").substringAfter(PREFIX)
                    val trip = l.optString("tripId").substringAfter(PREFIX)
                    val agency = l.optString("agencyId")
                    val from = code(l.optJSONObject("from")); val to = code(l.optJSONObject("to"))
                    if (route.toIntOrNull() == null || trip.isBlank() || from == null || to == null || agency.toIntOrNull() == null) { ok = false; break }
                    val calls = JSONArray().put(from)
                    l.optJSONArray("intermediateStops")?.let { m -> for (k in 0 until m.length()) code(m.getJSONObject(k))?.let { c -> calls.put(c) } }
                    calls.put(to)
                    val type = l.optInt("routeType", 3)
                    agencies.put(agency, l.optString("agencyName"))
                    val head = l.optString("headsign")
                    val fromName = l.optJSONObject("from")?.optString("name").orEmpty()
                    if (!lines.has(route)) lines.put(route, JSONArray().put(number).put(head).put("").put(fromName).put("").put(agency.toInt()).put(type).put(l.optString("routeLongName")))
                    legs.put(JSONObject().put("k", "r").put("line", route).put("trip", trip).put("dep", dep).put("arr", arr).put("sdep", epoch(l.optString("scheduledStartTime")).takeIf { s -> s > 0 } ?: dep)
                        .put("head", head).put("stops", calls).put("shape", shape).put("precision", precision))
                }
                if (!ok || legs.length() == 0) continue
                routes.put(JSONObject().put("dep", epoch(it.optString("startTime"))).put("arr", epoch(it.optString("endTime"))).put("legs", legs))
            }
            return JSONObject().put("it", routes).put("stops", stops).put("lines", lines).put("agencies", agencies).put("errors", JSONArray()).toString()
        }
    }
}
