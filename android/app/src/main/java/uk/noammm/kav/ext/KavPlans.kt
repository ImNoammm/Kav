package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.PlanSettings
import uk.noammm.kav.data.Transit

// Kav's own planner for an extension (Kav.plan), and its routes put first in Kav's own results (the hook "results.add").
//
// Kav.plan asks Kav's planner as Kav's search does, and answers each route as route rules get it with a "ref". Kav keeps
// the route by it for KEEP_S, the last MOST of each extension, so the extension can give it back (results.add,
// Kav.switchTrip); a ref of another extension's is unknown to it.
//
// results.add: once Kav's own planner has answered a search (not an extension's planner, a recent trip an extension
// planned or a shared trip) and its routes are on screen, each extension that provides it is asked:
//   asked:    {"query": {"from": [lat, lon], "to", "at", "way", "now", "settings", "modes", "reason"}, "routes": [Kav's],
//              "trip"}: reason "search", or "replan" for Kav's New route from here, which also gives the trip under way;
//              at 0 for a search for now
//   answered: [{"ref", "start", "walks": [seconds, ...]}]
// Each route it answers goes first in the results, at most MOST_ADDED of each: start is when the rider leaves (the route's
// own by default), walks how long each walk before its first ride takes (the route's own by default). Kav lays the walks and
// waits out from start, and takes the route only when they reach the first ride by when it leaves. likely_missed, worked
// out at the route's own walking pace, goes, and Kav's own tags are worked out again. For New route from here the first
// one is the new route.
object KavPlans {
    const val ADD = "results.add"
    private const val KEEP_S = 15 * 60L
    private const val MOST = 60
    private const val MOST_ADDED = 3
    private const val ASK_MS = 20_000L

    class Kept(val route: Transit.Itinerary, val resolved: Transit.Resolved, val at: Long)

    private val kept = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, Kept>>()
    private val next = java.util.concurrent.atomic.AtomicLong()

    internal fun keep(identity: String, route: Transit.Itinerary, r: Transit.Resolved, now: Long): String {
        val ref = "r${next.incrementAndGet()}"
        val mine = kept.getOrPut(identity) { LinkedHashMap() }
        synchronized(mine) {
            mine.entries.removeAll { now - it.value.at > KEEP_S }
            mine[ref] = Kept(route, r, now)
            while (mine.size > MOST) mine.remove(mine.keys.first())
        }
        return ref
    }

    internal fun kept(identity: String, ref: String, now: Long): Kept? =
        kept[identity]?.let { synchronized(it) { it[ref]?.takeIf { k -> now - k.at <= KEEP_S } } }

    internal fun forget() = kept.clear()

    class Query(
        val from: Pair<Double, Double>, val to: Pair<Double, Double>, val atS: Long, val way: Char, val settings: PlanSettings,
        val modes: List<Int>,
    )

    // Its query: {"from": [lat, lon], "to": [lat, lon], "at", "way", "settings", "modes"}. A query that isn't right throws.
    internal fun query(o: JSONObject): Query {
        fun place(name: String): Pair<Double, Double> {
            val a = o.optJSONArray(name) ?: throw IllegalArgumentException("$name is [lat, lon]")
            val p = a.optDouble(0) to a.optDouble(1)
            require(p.first in -90.0..90.0 && p.second in -180.0..180.0) { "$name is [lat, lon]" }
            return p
        }
        val way = o.optString("way", "d").ifEmpty { "d" }
        require(way == "d" || way == "a") { "way is \"d\" or \"a\"" }
        val modes = o.optJSONArray("modes")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: ALL_MODES
        val letters = o.optString("settings").takeIf { it.isNotEmpty() }
        return Query(place("from"), place("to"), o.optLong("at", 0).coerceAtLeast(0), way[0],
            letters?.let(PlanSettings::of) ?: KavPlan.settings, modes)
    }

    private val ALL_MODES = listOf(0, 1, 2, 3, 4, 5, 6, 7)

    // Kav.plan: null when Kav's planner can't be reached; no routes when it found none.
    fun plan(identity: String, q: Query): JSONObject? {
        val at = KavPlan.plannerOf(KavPlan.source) ?: return null
        val planned = try {
            KavPlan.plan(q.from, q.to, q.atS * 1000, if (q.way == 'a') Transit.TIME_ARRIVAL else Transit.TIME_DEPARTURE, q.modes, false,
                emptyList(), at, q.settings)
        } catch (e: Transit.PlannerRefusal) {
            return JSONObject().put("routes", JSONArray())
        } catch (e: KavPlan.NoTimetable) {
            return JSONObject().put("routes", JSONArray())
        } catch (e: java.io.IOException) {
            return null
        }
        return answer(identity, planned.plan.laidOut().filterNot(planned.plan::isTaxiCard), planned.resolved, System.currentTimeMillis() / 1000)
    }

    internal fun answer(identity: String, routes: List<Transit.Itinerary>, r: Transit.Resolved, now: Long): JSONObject =
        JSONObject().put("routes", JSONArray(routes.map { Pipeline.routeJson(it, r).put("ref", keep(identity, it, r, now)) }))

    // A route given back: its walks before its first ride from start, at the times walks gives them. Null when it can't
    // be laid out so: no ride, something other than walking or waiting before it, walks that don't match, a start before
    // now, or walks that don't reach the ride by when it leaves (its live time when Kav has one).
    internal fun laidOut(k: Kept, start: Long?, walks: List<Long>?, nowS: Long): Transit.Itinerary? {
        val legs = k.route.legs.toMutableList()
        val first = legs.indexOfFirst { it.kind == Transit.LegKind.RIDE }
        if (first < 0) return null
        if ((0 until first).any { (legs[it].kind != Transit.LegKind.WALK && legs[it].kind != Transit.LegKind.WAIT) || legs[it].visit != null }) return null
        val access = (0 until first).filter { legs[it].kind == Transit.LegKind.WALK }
        if (walks != null && (walks.size != access.size || walks.any { it < 0 })) return null
        fun walkS(j: Int): Long = walks?.get(access.indexOf(j)) ?: (legs[j].arr - legs[j].dep).coerceAtLeast(0)
        val at0 = start ?: k.route.dep
        val boards = Pipeline.liveDep(legs[first], k.resolved) ?: legs[first].dep
        if (at0 < nowS - 60 || at0 + access.sumOf(::walkS) > boards) return null
        Pipeline.place(legs, 0, first, at0, legs[first].dep, ::walkS)
        return k.route.with(legs = legs, dep = legs.first().dep, tags = k.route.tags - KavPlan.LIKELY_MISSED)
    }

    // An extension's answer read: its routes laid out, at most MOST_ADDED, and the details they need. Null for one Kav
    // can't read.
    internal fun read(identity: String, text: String, nowS: Long): List<Kept>? = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            given(kept(identity, o.optString("ref"), nowS) ?: return@mapNotNull null, o, nowS)
        }.take(MOST_ADDED)
    }.getOrNull()

    // A kept route laid out by an answer's start and walks; null when it can't be laid out so.
    internal fun given(k: Kept, o: JSONObject, nowS: Long): Kept? {
        val start = if (o.has("start") && !o.isNull("start")) o.getLong("start") else null
        val walks = o.optJSONArray("walks")?.let { w -> (0 until w.length()).map { w.getLong(it) } }
        return laidOut(k, start, walks, nowS)?.let { Kept(it, k.resolved, k.at) }
    }

    // Whether any extension switched on would add routes: only then is the question put together.
    fun wanted(): Boolean = holders().isNotEmpty()

    // Of those that provide it, the extensions switched on, the one switched on last first.
    private fun holders(): List<String> {
        val on = Hooks.offered(ADD).map { it.first.identity }.toSet()
        return Native.providing(ADD).filter { it.first in on }.sortedByDescending { it.second }.map { it.first }
    }

    class Added(val routes: List<Transit.Itinerary>, val resolved: Transit.Resolved)

    // Each extension that adds routes asked, all at once, within ASK_MS; their routes, in that order, and what they need of
    // the timetable and live times beside r. Null when none adds one. Network: off the main thread.
    fun ask(
        reason: String, q: Query, nowS: Long, routes: List<Transit.Itinerary>, r: Transit.Resolved, trip: JSONObject? = null,
    ): Added? {
        val who = holders()
        if (who.isEmpty()) return null
        val asked = JSONObject()
            .put("query", JSONObject().put("from", JSONArray().put(q.from.first).put(q.from.second))
                .put("to", JSONArray().put(q.to.first).put(q.to.second)).put("at", q.atS).put("way", q.way.toString()).put("now", nowS)
                .put("settings", q.settings.letters).put("modes", JSONArray(q.modes)).put("reason", reason))
            .put("routes", JSONArray(routes.map { Pipeline.routeJson(it, r) }))
            .apply { if (trip != null) put("trip", trip) }
            .toString()
        val answers = arrayOfNulls<List<Kept>>(who.size)
        who.mapIndexed { n, id ->
            Thread({ answers[n] = Native.hook(id, ADD, asked, ASK_MS)?.getOrNull()?.let { read(id, it, nowS) } }, "kav-results-add").apply { isDaemon = true; start() }
        }.forEach { it.join(ASK_MS + 1_000) }
        val all = answers.filterNotNull().flatten()
        if (all.isEmpty()) return null
        return Added(all.map { it.route }, all.fold(r) { a, k -> merged(a, k.resolved) })
    }

    // Kav.switchTrip: {"ref", "after", "dep", "arr", "start", "walks"}. The trip under way goes on with the route, from where
    // its ride at leg index after ends: the legs up to that ride kept, the route after them, as Kav's own New route from here
    // does. With start or walks, the route is laid out by them first, as for results.add.
    // dep and arr are the trip's own as trip events give them, so a trip changed since is left as it is. False when it
    // wasn't switched: no such route of this extension's, no trip under way or another one, a ride the rider is past,
    // stops on the way still ahead, or Kav's screen isn't running.
    fun switchTrip(identity: String, o: JSONObject, nowS: Long): Boolean {
        val k = kept(identity, o.optString("ref"), nowS)?.let { if (o.has("start") || o.has("walks")) given(it, o, nowS) else it }
            ?: return false
        val (journey, step) = uk.noammm.kav.TripBridge.journey?.invoke() ?: return false
        val (next, at) = switched(journey, step, k, o.optInt("after", -1), o.optLong("dep"), o.optLong("arr")) ?: return false
        return onMain { uk.noammm.kav.TripBridge.switchTo?.invoke(journey, next, at) == true }
    }

    internal fun switched(
        journey: uk.noammm.kav.ActiveJourney, step: Int, k: Kept, after: Int, dep: Long, arr: Long,
    ): Pair<uk.noammm.kav.ActiveJourney, Int>? {
        val trip = journey.trip
        if (trip.dep != dep || trip.arr != arr) return null
        val ride = trip.legs.getOrNull(after)?.takeIf { it.kind == Transit.LegKind.RIDE } ?: return null
        if (k.route.legs.none { it.kind == Transit.LegKind.RIDE }) return null
        val steps = uk.noammm.kav.ui.buildSteps(trip, journey.fromLabel, journey.toLabel)
        // Past that ride already (on a walk or ride after it, or there): the rider isn't where the route starts.
        fun leg(s: uk.noammm.kav.ui.Step): Int = when (s) {
            is uk.noammm.kav.ui.Step.Walk -> if (!s.legs.isEmpty()) s.legs.first else trip.legs.indexOf(s.leg)
            is uk.noammm.kav.ui.Step.Arrive -> trip.legs.size
            else -> TripEvents.legIndex(s, trip)
        }
        if (steps.take(step + 1).any { leg(it) > after }) return null
        val at = ride.shape.lastOrNull() ?: journey.resolved.stop(ride.toStop)?.point ?: return null
        val from = uk.noammm.kav.ui.ReplanFrom(at, k.route.dep.coerceAtLeast(1), after + 1, after + 1)
        // A route of plan's goes through no stop on the way: never one in place of a trip with some still ahead.
        if (journey.stopovers.drop(trip.legs.take(after + 1).count { it.visit != null }).isNotEmpty()) return null
        val next = uk.noammm.kav.ui.switched(journey, from, k.route, k.resolved, emptyList())
        return next to uk.noammm.kav.ui.stepAfterSwitch(next, from, step)
    }

    private fun onMain(run: () -> Boolean): Boolean {
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == main) return run()
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        android.os.Handler(main).post { ok = runCatching(run).getOrDefault(false); done.countDown() }
        return done.await(3, java.util.concurrent.TimeUnit.SECONDS) && ok
    }

    // Two searches' details as one: the second's live times over the first's.
    internal fun merged(a: Transit.Resolved, b: Transit.Resolved) = Transit.Resolved(a.lines + b.lines, a.stops + b.stops,
        a.routeTypes + b.routeTypes, a.live + b.live, a.shapes + b.shapes, a.pollSecs, a.patterns + b.patterns, a.delays + b.delays)
}
