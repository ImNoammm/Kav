package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ext.Extensions
import uk.noammm.kav.ext.Kind
import uk.noammm.kav.ext.Names
import uk.noammm.kav.ext.Namespace
import uk.noammm.kav.ext.PlanChecks
import uk.noammm.kav.ext.RefTable
import uk.noammm.kav.ext.Selections
import uk.noammm.kav.ui.T
import java.util.Locale

// Normalizes provider plans into Transit. Ministry stop, line and operator ids start at BASE;
// journeys use KavLive's negative ids.
// Live times come from the independently selected live provider.
object KavPlan {
    private const val BASE = 2_000_000_000
    private const val PREFS = "kav-plan"

    class Planned(val plan: Transit.Plan, val resolved: Transit.Resolved, val errors: List<String> = emptyList())

    // Operators by the ids given here, kept so a trip reopened later still names them.
    private val operators = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var store: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        store = s
        runCatching { JSONObject(s.getString("operators", "{}") ?: "{}") }.getOrNull()?.let { o ->
            for (k in o.keys()) k.toIntOrNull()?.let { operators[it] = o.optString(k) }
        }
    }

    fun agencyName(agencyId: Int): String? = operators[agencyId]

    // Who plans trips, independently of live locations: Kav's server until the settings are read.
    var source by mutableStateOf(Source.KAV)

    // Every planner's routes, a tab each on the results (Settings), in the rider's order; the rider's own server only
    // once it's set up.
    var everyPlanner by mutableStateOf(false)
    // The tabs' order by their keys (plannerTabs): Kav's planners and the extensions' together.
    var tabOrder by mutableStateOf(Source.PLANNING.map { it.key })
    // Planners the rider took off the tabs, never asked then; the one chosen above keeps its tab, as results open on it,
    // unless an extension's tab is left to open on.
    var hiddenTabs by mutableStateOf(emptySet<Source>())
    fun offeredTabs(): List<Source> =
        ordered(Source.PLANNING.filter { it != Source.OWN || KavLive.ownServer.isNotEmpty() }.map { it.key }, tabOrder).mapNotNull(Source::of)
    fun tabs(): List<Source> = offeredTabs().filter { it == source || it !in hiddenTabs }

    // Every tab on the results, by its key, as the results and extensions name it (results.planners,
    // results.planner.select): Kav's own planners by their source's ("kav", "own"), an extension's by its address
    // ("ext:<publisher>/<dataset>"), in the rider's order: Kav's (each planner above with a tab for each, else the
    // chosen one), then each extension planner the rider switched on (Selections.planners), which always has one. Kav's
    // own may be hidden while an extension's is left.
    fun plannerTabs(): List<String> {
        val theirs = Selections.planners.map(Extensions::address)
        val kav = (if (everyPlanner) tabs() else listOf(source)).filter { theirs.isEmpty() || it !in hiddenTabs }.map { it.key }
        return ordered(kav + theirs, tabOrder)
    }

    // Keys in the rider's order; any they haven't placed after, as they came.
    internal fun ordered(keys: List<String>, order: List<String>): List<String> =
        keys.sortedBy { k -> order.indexOf(k).let { if (it < 0) order.size + keys.indexOf(k) else it } }

    // The tab results open on: the extension's the rider chose, else Kav's chosen planner's, else the first.
    fun opensOn(tabs: List<String> = plannerTabs()): String? =
        Selections.opens?.let(Extensions::address)?.takeIf { it in tabs } ?: source.key.takeIf { it in tabs } ?: tabs.firstOrNull()

    // A tab's name: Kav's planner's, or the extension's (Extensions.nameOf).
    fun tabName(key: String): String = Source.of(key)?.let { uk.noammm.kav.ui.sourceName(it) }
        ?: Extensions.namespaceAt(key)?.let(Extensions::nameOf) ?: key

    // The server that plans for a tab: Kav's chosen planner's as kavPlanner() has it, any other's as it's set.
    fun plannerOf(s: Source): String? = if (s == source) kavPlanner() else KavLive.serverOf(s)

    // The same by a tab's key: an extension's tab is planned at its address (Extensions.plannerAt).
    fun plannerOf(key: String): String? = Source.of(key)?.let(::plannerOf) ?: key.takeIf(Extensions::isAddress)

    // The server chosen to plan: Kav's, or the rider's own once it's set up (Kav's until then).
    val server: String get() = KavLive.serverOf(source)

    // Whether that server is answering, and whether it plans trips at all: Kav's always does, a self-hosted one only when
    // its status lists a planner (server/selfhost with PLANNER=off leaves it out). While either isn't so, no trip is
    // planned and the rider is told.
    var up by mutableStateOf(true)
    var offered by mutableStateOf(true)

    // The planner results open on (opensOn), through any stops the rider added and for the last way there tonight too:
    // Kav's server, or an extension's planner; null while the server isn't answering, or doesn't plan.
    fun planner(): String? = opensOn()?.let(::plannerOf)
    private fun kavPlanner(): String? = server.takeIf { up && offered }
    fun wanted(): Boolean = planner() != null

    // A shared trip Kav planned only a Kav planner finds again, whichever plans now: the one chosen, else the server live
    // locations come from when that's Kav's or the rider's own. Never a provider for what it wasn't chosen for, nor an
    // extension.
    fun linkPlanner(): String? = kavPlanner() ?: KavLive.server.takeIf { KavLive.enabled && !KavLive.source.provider }

    // The planner a trip is planned again on: the one that planned it. An extension's trip goes to no one else, even when
    // that extension is gone (it then says so); Kav's trip stays with Kav's server.
    fun replanAt(trip: Transit.Itinerary): String? =
        if (trip.provider.isEmpty()) kavPlanner() else Namespace.of(trip.provider)?.let(Extensions::address)

    // Planned by Kav's server, by what the plan says of itself: not by how its ids look.
    fun owns(trip: Transit.Itinerary): Boolean = trip.provider.isEmpty() && trip.guid.startsWith("kav-")

    // Whether its live times are refreshed: Kav's server's trips from where live locations come from, an extension's
    // from its own publisher (live()).
    fun refreshes(trip: Transit.Itinerary): Boolean = owns(trip) || trip.provider.isNotEmpty()

    // A shared link that names Kav's own rides, which only Kav's planner can find again.
    fun owns(rides: List<TripLink.Ride>): Boolean = rides.isNotEmpty() && rides.all { it.lineId >= BASE }

    // The selected trip itself, with its stop and line details and the sender's page choices.
    fun sharePayload(
        trip: Transit.Itinerary, fromName: String, toName: String, resolved: Transit.Resolved,
        rides: Boolean = true, colors: List<String> = emptyList(),
        style: ShareStyle = ShareStyle(),
    ): JSONObject? {
        val start = trip.legs.firstNotNullOfOrNull { leg -> leg.shape.firstOrNull() }
            ?: trip.legs.firstNotNullOfOrNull { resolved.stop(it.fromStop)?.point } ?: return null
        val end = trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull()
            ?: trip.legs.asReversed().firstNotNullOfOrNull { resolved.stop(it.toStop)?.point } ?: return null
        val inEnglish = when (style.stopLanguage) { "en" -> true; "he" -> false; else -> StopNames.english }
        val net = uk.noammm.kav.Loaded.net
        fun point(p: Pair<Double, Double>) = String.format(Locale.US, "%.6f,%.6f", p.first, p.second)
        return style.page().put("from", point(start)).put("to", point(end))
            .put("fromName", net?.shown(fromName, inEnglish) ?: fromName).put("toName", net?.shown(toName, inEnglish) ?: toName)
            .put("at", trip.dep.toString()).put("data", TripShare.encode(trip, resolved, colors, inEnglish))
            .put("trip", if (rides) trip.rides.joinToString("~") { "${it.lineId}.${it.tripId}.${it.dep}" } else "")
    }

    // Whether an id is one given here.
    fun isKav(id: Int): Boolean = id >= BASE

    // The ministry's trip ids behind the trip ids of the plans read in this run (their hashes can't be read back), so
    // extensions get a reference for Kav's trips as for anyone else's (ext/Pipeline.kt). Bounded: forgotten all at once.
    private val tripKeys = java.util.concurrent.ConcurrentHashMap<Long, String>()
    private fun rememberTrip(id: Long, key: String) { if (tripKeys.size > 50_000) tripKeys.clear(); tripKeys[id] = key }
    fun tripKeyOf(id: Long): String? = tripKeys[id]

    fun stopId(gtfs: String): Int = BASE + gtfs.toInt()
    fun lineId(gtfs: String): Int = BASE + gtfs.toInt()
    // The ministry's route id of a line given here, which its live reports name; null for any other line.
    fun routeOf(lineId: Int): Int? = (lineId - BASE).takeIf { isKav(lineId) }
    // One per operator and kind of line, since the screens take a line's kind from its operator.
    fun agencyId(gtfs: Int, type: Int): Int = BASE + gtfs * 1000 + type % 1000
    fun gtfsAgency(agencyId: Int): Int? = if (isKav(agencyId)) (agencyId - BASE) / 1000 else null
    fun typeOf(agencyId: Int): Int? = if (isKav(agencyId)) (agencyId - BASE) % 1000 else null

    // Leaving now (n) also brings the taxi card, which is only offered then, but not on the way to other stops;
    // the last departure (l) is the last way there tonight. Stops on the way go last, in order, each with the minutes
    // to stay there when it isn't only passed.
    internal fun path(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, routeTypes: List<Int>,
        taxi: Boolean = false, stopovers: List<Pair<Double, Double>> = emptyList(), stays: List<Int> = emptyList(),
    ): String {
        val at = (if (whenMs > 0) whenMs else System.currentTimeMillis()) / 1000
        val way = when {
            timeType == Transit.TIME_ARRIVAL -> "a"
            timeType == Transit.TIME_LAST -> "l"
            whenMs <= 0 && taxi && stopovers.isEmpty() -> "n"
            else -> "d"
        }
        val types = routeTypes.distinct().sorted().filter { it in 0..7 }
        val only = if (types.isEmpty() || types.size == 8) "" else "/" + types.joinToString(",")
        val via = if (stopovers.isEmpty()) "" else "/via/" + stopovers.take(3).withIndex().joinToString(";") { (i, at) ->
            String.format(Locale.US, "%.6f,%.6f", at.first, at.second) + (stays.getOrNull(i)?.takeIf { it > 0 }?.let { ",$it" } ?: "")
        }
        return String.format(Locale.US, "plan/%.6f,%.6f,%.6f,%.6f/%d%s%s", from.first, from.second, to.first, to.second, at, way, only) + via
    }

    // The rider's planner settings (Settings, Trips), read from Prefs at start.
    var settings by mutableStateOf(PlanSettings.USUAL)

    // at: the server that plans (planner, linkPlanner).
    fun plan(
        from: Pair<Double, Double>, to: Pair<Double, Double>, whenMs: Long, timeType: Int, routeTypes: List<Int>, taxi: Boolean,
        stopovers: List<Transit.Place> = emptyList(), at: String, settings: PlanSettings = this.settings,
    ): Planned {
        val planned = plannerFor(at).plan(PlanRequest(from, to, whenMs, timeType, routeTypes, taxi, stopovers, settings))
        // The server's timetable runs two weeks ahead. Walking and cycling don't need it.
        if (planned.plan.itineraries.none { it.rides.isNotEmpty() } && "OUTSIDE_SERVICE_PERIOD" in planned.errors) {
            throw NoTimetable()
        }
        saveOperators()
        return Planned(planned.plan, live(planned.plan.itineraries, planned.resolved))
    }

    class NoTimetable : java.io.IOException("Kav's planner has no timetable for that day")

    // The server plans a few trips at once and turns the next one away as busy: that one, and one that timed out, is
    // asked again after each of these waits, with up to a third more at random so riders turned away together don't
    // come back together, before the rider is told. Only while one more try as long as the last still fits in
    // retryBudgetMs: a server that doesn't answer at all costs a connect timeout on each of its addresses at every try.
    internal var retryWaitsMs = listOf(1_200L, 2_400L)
    internal var retryBudgetMs = 20_000L
    internal var clock: () -> Long = System::currentTimeMillis

    internal fun asked(path: String, at: String): String {
        val start = clock()
        var tries = 0
        while (true) {
            val began = clock()
            try {
                return KavLive.planned(path, at)
            } catch (e: java.io.IOException) {
                val again = e is java.net.SocketTimeoutException || e.message == "Kav live busy"
                val wait = retryWaitsMs.getOrNull(tries++)
                val now = clock()
                if (!again || wait == null || now - start + wait + (now - began) > retryBudgetMs) throw e
                Thread.sleep(wait + kotlin.random.Random.nextLong(wait / 3 + 1))
            }
        }
    }

    // Live times for Kav's routes, from where live locations come from like the rest of them: a server, Kav's or the
    // rider's own, answers about the ministry's own stops and routes, which Kav's ids are.
    // An extension's rides get theirs from their own publisher (Extensions.arrivals), never from Kav's live provider.
    fun live(list: List<Transit.Itinerary>, r: Transit.Resolved, ending: Boolean = false): Transit.Resolved {
        val kav = kavLive(list, r, ending)
        val theirs = Extensions.arrivals(list)
        if (theirs.live.isEmpty()) return kav
        return Transit.Resolved(kav.lines, kav.stops, kav.routeTypes, kav.live + theirs.live, kav.shapes, 15,
            kav.patterns + theirs.patterns, kav.delays + Transit.delaysOf(list, theirs.live))
    }

    private fun kavLive(list: List<Transit.Itinerary>, r: Transit.Resolved, ending: Boolean): Transit.Resolved {
        if (list.isEmpty()) return r
        val origin = KavLive.origin
        val board: (List<Int>) -> KavLive.Board = when {
            KavLive.stopsEnabled -> { codes -> KavLive.stops(codes) }
            else -> return r.aged()
        }
        val rides = try {
            KavLive.rideArrivals(list, r.stops, r.lines, ending, uk.noammm.kav.Loaded.net, board)
        } catch (e: Exception) {
            return r.aged()
        }
        if (origin != KavLive.origin) return r
        return Transit.Resolved(r.lines, r.stops, r.routeTypes, rides.live, r.shapes, 15, r.patterns + rides.patterns,
            r.delays + Transit.delaysOf(list, rides.live))
    }

    private fun saveOperators() {
        val o = JSONObject()
        for ((k, v) in operators) o.put(k.toString(), v)
        store?.edit()?.putString("operators", o.toString())?.apply()
    }

    // stopNames: the names of the stops on the way, in order, for the legs that come after one. ns: whose ids the plan's
    // are, Kav's server's (the ministry's) or an extension's; an extension's become RefTable handles, so two publishers
    // with the same ids never meet each other or the ministry.
    // An extension's plan (not the ministry's) is held to the one message ceiling (PlanChecks.Bounds.CHARS): past it the
    // whole answer is refused.
    internal fun parse(body: String, stopNames: List<String> = emptyList(), ns: Namespace = Namespace.MINISTRY): Planned {
        val ext = !ns.ministry
        val b = PlanChecks.Bounds
        fun bound(what: String, n: Int, max: Int) { if (ext && n > max) throw IllegalArgumentException("$what: more than $max") }
        // An extension planner's words are shown as given: the screen cuts what doesn't fit (OPEN-PLAN section 3).
        fun word(v: String) = v
        bound("characters", body.length, b.CHARS)
        val response = JSONObject(body)
        val o = response
        val ids = Ids(ns)
        val stops = HashMap<Int, Transit.StopInfo>()
        o.optJSONObject("stops")?.let { s ->
            for (k in s.keys()) {
                val a = s.getJSONArray(k)
                val id = ids.stop(k)
                stops[id] = Transit.StopInfo(id, word(a.optString(1)), word(a.optString(0)), a.optDouble(2), a.optDouble(3))
            }
        }
        val names = o.optJSONObject("agencies") ?: JSONObject()
        val lines = HashMap<Int, Transit.LineInfo>()
        // The ministry's own name for each line, which tells a line's two ways apart in the timetable.
        val routeNames = HashMap<Int, String>()
        val types = HashMap<Int, Int>()
        o.optJSONObject("lines")?.let { l ->
            for (k in l.keys()) {
                // [number, to, to city, from, from city, operator, type, the ministry's own name], as KavLive's lines.
                val a = l.getJSONArray(k)
                val type = a.optInt(6, 3)
                // The ministry's operators are numbers; an extension's ids are whatever it gives.
                val operator = if (ns.ministry) a.optInt(5).toString() else a.optString(5)
                val agency = ids.agency(operator, type)
                types[agency] = type
                word(names.optString(operator)).takeIf { it.isNotBlank() }?.let { ids.name(agency, it) }
                val from = word(a.optString(3)); val to = word(a.optString(1))
                val fromCity = word(a.optString(4)); val toCity = word(a.optString(2))
                val own = word(a.optString(7))
                // A train is named for where it runs.
                val number = word(a.optString(0)).ifBlank { if (from.isNotBlank() && to.isNotBlank()) "$from - $to" else own }
                val caption = if (fromCity.isBlank() || fromCity == toCity) toCity else "$fromCity ↔︎ $toCity"
                val id = ids.line(k)
                lines[id] = Transit.LineInfo(id, number, agency, from, to, caption, own)
                routeNames[id] = own
            }
        }
        val routes = ArrayList<Transit.Itinerary>()
        val all = o.optJSONArray("it") ?: JSONArray()
        for (i in 0 until all.length()) {
            val it = all.getJSONObject(i)
            val legs = ArrayList<Transit.Leg>()
            val parts = it.getJSONArray("legs")
            var lastArr = it.optLong("dep")
            for (j in 0 until parts.length()) {
                val l = parts.getJSONObject(j)
                val dep = l.optLong("dep"); val arr = l.optLong("arr")
                // The first leg on from a stop on the way says which stop, and the stay asked for there.
                val via = l.optInt("via"); val staying = l.optLong("stay") > 0
                val first = legs.size
                val shape = KavLive.decodePolyline(l.optString("shape"), l.optInt("precision", 5))
                fun point(k: String) = l.optJSONArray(k)?.let { p -> p.optDouble(0) to p.optDouble(1) }
                when (l.optString("k")) {
                    "w" -> legs += Transit.Leg(Transit.LegKind.WALK, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape,
                        turns = l.optJSONArray("turns").let { t -> turnsOf(t) { word(it) } },
                        insideMeters = l.optInt("im"))
                    "b" -> legs += Transit.Leg(Transit.LegKind.BIKE, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape)
                    // Waiting for the taxi, then the drive.
                    "x" -> legs += Transit.Leg(Transit.LegKind.OTHER, dep = dep, arr = arr)
                    "t" -> legs += Transit.Leg(
                        Transit.LegKind.TAXI, dep = dep, arr = arr, meters = l.optInt("m"), shape = shape,
                        taxiPickup = point("from"), taxiDropoff = point("to"),
                    )
                    else -> {
                        // The ride and the wait for it, and the same for every other line from that stop to that stop.
                        // Boarded straight from a place the rider stays at, the time before it is the stay, not a wait.
                        val waitFrom = if (staying) dep else minOf(lastArr, dep)
                        val (wait, ride) = ride(l, shape, waitFrom, lines, routeNames, stops, types, ids)
                        val alts = l.optJSONArray("alts") ?: JSONArray()
                        if (alts.length() == 0) {
                            legs += wait; legs += ride
                        } else {
                            val options = listOf(wait to ride) + (0 until alts.length()).map { k ->
                                val alt = alts.getJSONObject(k)
                                ride(alt, KavLive.decodePolyline(alt.optString("shape")), waitFrom, lines, routeNames, stops, types, ids)
                            }
                            legs += wait.copy(alternatives = options.map { it.first })
                            legs += ride.copy(alternatives = options.map { it.second })
                        }
                    }
                }
                if (via > 0) (first until legs.size).firstOrNull { legs[it].kind != Transit.LegKind.WAIT }?.let { k ->
                    legs[k] = legs[k].copy(visit = Transit.Visit(stopNames.getOrNull(via - 1).orEmpty(), lastArr, dep, staying))
                }
                lastArr = arr
            }
            if (legs.isEmpty()) continue
            val tags = (it.optJSONArray("tags") ?: JSONArray()).let { t -> (0 until t.length()).map { k -> t.optString(k) } }
            val guid = (if (ns.ministry) "kav-" else "x-") +
                Integer.toHexString(legs.joinToString("|") { "${it.kind}${it.lineId}${it.tripId}${it.dep}" }.hashCode())
            routes += Transit.Itinerary(
                guid = guid,
                group = 2, legs = legs, dep = it.optLong("dep"), arr = it.optLong("arr"), tags = tags,
                sectionId = it.optInt("sec", SECTION_MAIN),
                accessible = it.optBoolean("access"),
                // The regular fare in agorot, the ministry's as the server adds it up, on a route it could price, paying in
                // an app; and the single fares, where a day pass makes that less.
                fare = it.optInt("fare", -1), currency = if (it.has("fare")) "₪" else "",
                singleFares = it.optInt("singles", -1),
                provider = if (ns.ministry) "" else ns.key,
            )
        }
        val errors = (o.optJSONArray("errors") ?: JSONArray()).let { e -> (0 until e.length()).map { e.optString(it) } }
        return Planned(Transit.Plan(routes, sections()), Transit.Resolved(lines, stops, types), errors)
    }

    private const val SECTION_MAIN = 1

    // A plan's ids as Transit's: the ministry's from BASE as ever, an extension's through RefTable.
    // An extension's id longer than PlanChecks.Bounds.ID is kept as a short stand-in made from it (the same id always
    // makes the same one), so what Kav sends its route rules about a plan stays small whatever ids the planner uses.
    private class Ids(val ns: Namespace) {
        private fun k(v: String) = if (ns.ministry || v.length <= PlanChecks.Bounds.ID) v
            else "~" + java.security.MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).copyOf(12).joinToString("") { "%02x".format(it) }
        fun stop(k: String): Int = if (ns.ministry) stopId(k) else RefTable.intern(ns.ref(Kind.STOP, k(k)))
        fun line(k: String): Int = if (ns.ministry) lineId(k) else RefTable.intern(ns.ref(Kind.ROUTE, k(k)))
        fun agency(k: String, type: Int): Int =
            if (ns.ministry) agencyId(k.toIntOrNull() ?: 0, type) else RefTable.intern(ns.ref(Kind.AGENCY, k(k)), type)
        fun trip(k: String): Long = if (ns.ministry) KavLive.tripIdOf("gtfs:$k").also { rememberTrip(it, k) } else RefTable.internTrip(ns.ref(Kind.TRIP, k(k)))
        fun name(agency: Int, name: String) { if (ns.ministry) operators[agency] = name else RefTable.setLabel(agency, name) }
    }

    // The sections Kav's server fills, by their ids, in order: the timetable card, the routes, taxis, transit with a
    // bike, walking or cycling all the way, and East Jerusalem's operators' routes. Their names come when they're shown
    // (sectionName), so they follow the language Kav is shown in.
    private fun sections() = listOf(
        Transit.Section(3, "", 1, Transit.SECTION_NO_GROUPING, 0),
        Transit.Section(SECTION_MAIN, "", 127, 16, 1),
        Transit.Section(Transit.SECTION_TAXI, "", Int.MAX_VALUE, 1, 2),
        Transit.Section(522, "", 1, 3, 3),
        Transit.Section(521, "", 3, 2, 4),
        Transit.Section(1461, "", Int.MAX_VALUE, 1, 5),
    )

    // A section's heading by its id, in the language Kav is shown in now; null for a section Kav doesn't name.
    fun sectionName(id: Int): String? = when (id) {
        Transit.SECTION_TAXI -> T("Taxi & Ride Hailing", "מוניות")
        522 -> T("Combined Transit & Personal Bike Routes", "מסלולי תחב\"צ בשילוב אופניים")
        521 -> T("Walking & Biking Routes", "מסלולי הליכה ואופניים")
        1461 -> T("Suggested routes including East Jerusalem agencies", "מסלולים הכוללים את קווי מזרח ירושלים")
        else -> null
    }

    // One way to take a stretch on one line: the wait at its first stop, with that line's next departures, and the
    // ride itself, along its road; through its stops when the answer has no road for it,
    // since the map draws a ride and finds the rider aboard by it.
    private fun ride(
        l: JSONObject, shape: List<Pair<Double, Double>>, waitFrom: Long,
        lines: Map<Int, Transit.LineInfo>, names: Map<Int, String>, stops: Map<Int, Transit.StopInfo>,
        types: Map<Int, Int>, id: Ids,
    ): Pair<Transit.Leg, Transit.Leg> {
        val dep = l.optLong("dep"); val arr = l.optLong("arr")
        val line = id.line(l.getString("line"))
        val trip = id.trip(l.getString("trip"))
        val calls = l.optJSONArray("stops") ?: JSONArray()
        val ids = (0 until calls.length()).map { id.stop(calls.getString(it)) }
        val number = lines[line]?.number.orEmpty()
        // The timetable's own time, which Kav's live times find the bus by, worked out from when its trip set out since
        // the planner's times are predicted; and the planner's, live where its updates moved it.
        val first = l.optLong("first", 0L)
        val timetabled = (if (first > 0 && id.ns.ministry) timetabled(stops[ids.firstOrNull() ?: -1]?.code, number, first, names[line].orEmpty()) else null)
            ?: l.optLong("sdep", dep)
        val live = l.optBoolean("live")
        val mine = Transit.Departure(
            trip, timetabled, rtUtc = if (live) dep else 0L,
            statisticalUtc = if (!live && dep != timetabled) dep else 0L, givenUtc = if (live) clock() / 1000 else 0L,
        )
        // The line's departures there after this one, and for a route's first ride those from the time asked about
        // before it: the card lists them from then, catchable or not.
        // A third field 1: the server has that one live (a train card's other trains, which the board can't match).
        val departures = { key: String ->
            val list = l.optJSONArray(key) ?: JSONArray()
            (0 until list.length()).mapNotNull { n ->
                list.optJSONArray(n)?.let { d ->
                    val at = d.optLong(0)
                    val given = d.optInt(2) == 1
                    Transit.Departure(id.trip(d.optString(1)), at, rtUtc = if (given) at else 0L,
                        givenUtc = if (given) clock() / 1000 else 0L)
                }
            }
        }
        val later = departures("next")
        val earlier = departures("before")
        val from = ids.firstOrNull() ?: -1
        val to = ids.lastOrNull() ?: -1
        // An extension's vehicle for this ride (plan 2.1), for Live location until its board gives a newer one.
        if (!id.ns.ministry) uk.noammm.kav.ext.BoardAnswers.vehicle(l.optJSONObject("vehicle"))?.let {
            Extensions.planned(Transit.ArrivalKey(from, trip), it)
        }
        // An extension's alert for this ride (plan 2.1): its words, and whether it's severe, in Kav's own alert row.
        val alert = l.optJSONObject("alert")
        val alertText = alert?.optString("text").orEmpty().trim().take(2000)
        val alertCategory = if (alertText.isEmpty()) 0 else if (alert?.optBoolean("severe") == true) 4 else 3
        // A train's headsign is its run number; its line's name describes the whole route.
        val label = l.optString("head").takeIf {
            types[lines[line]?.agencyId] == 2 && it.isNotBlank() && it.all(Char::isDigit)
        } ?: number
        return Transit.Leg(
            Transit.LegKind.WAIT, lineId = line, dep = minOf(waitFrom, dep), arr = dep,
            fromStop = from, toStop = to, nextDeps = earlier + listOf(mine) + later,
            alertCategory = alertCategory, alertText = alertText,
        ) to Transit.Leg(
            Transit.LegKind.RIDE, lineId = line, tripId = trip, dep = dep, arr = arr, stops = ids,
            fromStop = from, toStop = to, nextDeps = listOf(mine), shortName = label,
            alertCategory = alertCategory, alertText = alertText,
            shape = shape.ifEmpty { ids.mapNotNull { stops[it]?.point } },
        )
    }

    // When the timetable has the trip of that line that set out at first stop at the stop with that code, from Kav's own
    // timetable; null when it hasn't the trip.
    private fun timetabled(code: String?, number: String, first: Long, name: String): Long? {
        val net = uk.noammm.kav.Loaded.net ?: return null
        val stop = KavLive.stopOfCode(net, code?.toIntOrNull() ?: return null).takeIf { it >= 0 } ?: return null
        val at = KavLive.scheduledAt(net, stop, number, first, name) ?: return null
        return first + Math.floorMod(at - KavLive.secondOfDay(first), 86_400)
    }

    // A walk's turns as Kav's server sends them: [way, street, metres, lat, lon] each.
    // street: what a street's name goes through (an extension planner's is cleaned like its other names).
    internal fun turnsOf(a: JSONArray?, street: (String) -> String = { it }): List<Transit.Turn> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        a!!.optJSONArray(i)?.let { t -> Transit.Turn(t.optString(0), street(t.optString(1)), t.optInt(2), t.optDouble(3), t.optDouble(4)) }
    }

    // What each route is best at, in a word: the planner's tags, kept on the route as they came so the words follow the
    // language Kav is shown in.
    private val TAG_WORDS = mapOf(
        "early_arrival" to ("Earliest arrival" to "הגעה הכי מוקדמת"),
        "latest_departure" to ("Latest departure" to "יציאה הכי מאוחרת"),
        "direct" to ("No transfers" to "ללא החלפות"),
        "least_walking" to ("Least walking" to "הכי פחות הליכה"),
        "low_transfer" to ("Fewest transfers" to "הכי מעט החלפות"),
        "last_departure" to ("Last trip" to "נסיעה אחרונה"),
        "long_walk" to ("Long walk" to "הליכה ארוכה"),
        "long_wait_time" to ("Long wait" to "המתנה ארוכה"),
        "risky_transfer" to ("Tight connection" to "החלפה צפופה"),
        // A tight route the server's chance tables give under even odds of making every bus (server/live.py LIKELY_MISSED).
        LIKELY_MISSED to ("Likely to miss" to "סביר שתפספסו"),
        "high_frequency" to ("Frequent service" to "תדירות גבוהה"),
        "flexible_route" to ("Flexible route" to "מסלול גמיש"),
        "after_shabbat" to ("After Shabbat" to "אחרי שבת"),
        "after_holiday" to ("After the holiday" to "אחרי החג"),
    )

    const val LIKELY_MISSED = "likely_missed"

    // A late train's tag (RailTrip): its minutes.
    private const val LATE = "late:"
    private val LATE_WORDS = Regex("^(\\d+) min late$|^באיחור של (\\d+) דק׳$")

    fun lateTag(minutes: Int) = "$LATE$minutes"

    fun isLate(tag: String) = tag.startsWith(LATE)

    // A tag's words as Kav is shown now; null for a tag it has no words for.
    fun tagWord(tag: String): String? = TAG_WORDS[tag]?.let { (en, he) -> T(en, he) }
        ?: tag.takeIf { it.startsWith(LATE) }?.removePrefix(LATE)?.toIntOrNull()?.let { T("$it min late", "באיחור של $it דק׳") }
        ?: tag.takeIf { it.startsWith("x:") }?.let(uk.noammm.kav.ext.Tags::word)?.let { (en, he) -> T.iso(T(en, he)) }

    // A saved tag read back. An older Kav saved its words, in either language: those turn back into the tag.
    internal fun tagOf(saved: String): String? = when {
        saved in TAG_WORDS || saved.startsWith(LATE) || saved.startsWith("x:") -> saved
        else -> TAG_WORDS.entries.firstOrNull { saved == it.value.first || saved == it.value.second }?.key
            ?: LATE_WORDS.find(saved)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { "$LATE$it" }
    }
}
