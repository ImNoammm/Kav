package uk.noammm.kav.ext

import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.LegByLeg
import uk.noammm.kav.data.PlanRequest
import uk.noammm.kav.data.TripPlanner
import uk.noammm.kav.data.Transit
import java.util.concurrent.ConcurrentHashMap

// A planner: answers with Plan v2, which is server/API.md's Plan with every id the provider's own (its namespace's).
// Never told a place's name: only where it is.
interface PlanSource {
    val versions: List<ContractVersion>
    // Whether it plans through stops on the way itself; if not, Kav plans each part and joins them (LegByLeg).
    val stopovers: Boolean
    fun plan(q: PlanQuery, v: ContractVersion, e: Execution): Outcome<String>
}

class PlanQuery(
    val from: Pair<Double, Double>, val to: Pair<Double, Double>, val atS: Long,
    // d leaving at, a arriving by, l the last way there tonight.
    val way: Char, val routeTypes: List<Int>, val stopovers: List<Stopover>, val settings: String,
)

class Stopover(val lat: Double, val lon: Double, val stayMin: Int)

// A timetable and stop-board provider: what leaves one of its own stops from a time on, and the stops and lines it
// covers, so the rider can find them in the Stops tab (Kav searches that list itself, on the phone).
interface BoardSource {
    val versions: List<ContractVersion>
    fun departures(stop: String, fromS: Long, limit: Int, v: ContractVersion, e: Execution): Outcome<List<BoardDeparture>>
    fun catalogue(v: ContractVersion, e: Execution): Outcome<Catalogue> = Outcome.Unsupported("no stop list")
}

// route and trip are the provider's own ids. estimatedS: its estimate, 0 for none. headsign: where it goes, as the
// provider names it ("" when its line's name says it). vehicle: where it is now, when the provider knows (board 1.1).
class BoardDeparture(
    val route: String, val trip: String, val scheduledS: Long, val estimatedS: Long = 0, val platform: String = "", val headsign: String = "",
    val vehicle: Vehicle? = null,
)

// Where a provider's bus or train is: degrees, when it was there (seconds since 1970, 0 for "as it answered"), and its
// heading in degrees from north, -1 for none. Live location shows it (board 1.1, plan 2.1).
class Vehicle(val lat: Double, val lon: Double, val atS: Long = 0, val bearing: Int = -1)

// A provider's stops and lines: its own ids, names as it gives them (cleaned), and the kind of line (GTFS route type:
// 0 tram, 1 metro, 2 train, 3 bus, 4 ferry, 5 cable car, 6 gondola, 7 funicular).
class StopEntry(val id: String, val name: String, val lat: Double, val lon: Double, val code: String = "", val mode: Int = 3)
class LineEntry(val id: String, val number: String, val name: String, val mode: Int = 3)
class Catalogue(val stops: List<StopEntry>, val lines: Map<String, LineEntry>) {
    val byId: Map<String, StopEntry> by lazy { stops.associateBy { it.id } }
}

class Contribution(val namespace: Namespace, val id: String, val capability: String, val revision: String, val source: Any) {
    init {
        require(Capabilities.known(capability)) { "$capability isn't a capability this Kav runs" }
        require(!namespace.ministry) { "the ministry's data is Kav's own" }
    }
}

// A provider that couldn't give a usable answer, or isn't there: the rider is told, and nothing is asked of anyone else.
// off: extensions are off for this run (SafeMode), so it wasn't asked.
class ProviderProblem(val namespace: Namespace, val outcome: Outcome<*>, val missing: Boolean = false, val off: Boolean = false) :
    java.io.IOException("${namespace.key}: ${if (off) "safe mode" else if (missing) "not installed" else outcome.toString()}")

// The contributions registered in this run of Kav and the grants they hold: installed packages' (Hooks) and, in debug
// builds, the test fixtures'. In safe mode nothing registers. Nothing here starts
// with Kav when nothing is registered.
object Extensions {
    // plan 2.1: a ride's alert and vehicle, and the rider's display settings in the query; board 1.1: a departure's vehicle.
    val HOST_PLAN = listOf(ContractVersion("plan", 2, 1))
    val HOST_BOARD = listOf(ContractVersion("board", 1, 1))
    private const val ADDRESS = "ext:"

    private val contributions = ConcurrentHashMap<String, Contribution>()
    private val grants = ConcurrentHashMap<String, Grant>()
    private val generations = ConcurrentHashMap<String, Long>()

    private fun key(ns: Namespace, capability: String) = "${ns.key} $capability"

    fun register(c: Contribution) {
        if (SafeMode.active) return
        val k = key(c.namespace, c.capability)
        contributions[k] = c
        grants[k] = Grant(c.capability, generations.merge(k, 1L, Long::plus)!!)
    }

    // Takes the grant away: an answer still on its way is thrown away when it comes.
    fun revoke(ns: Namespace, capability: String) {
        val k = key(ns, capability)
        generations.merge(k, 1L, Long::plus)
        grants.remove(k)
    }

    internal fun clear() { contributions.clear(); grants.clear(); catalogues.clear(); planned.clear(); Selections.forget() }

    fun registered(ns: Namespace, capability: String) = grants.containsKey(key(ns, capability))

    // The address KavPlan.plan takes for an extension's planner.
    fun address(ns: Namespace) = ADDRESS + ns.key
    fun address(key: String) = ADDRESS + key
    fun isAddress(at: String) = at.startsWith(ADDRESS)
    fun namespaceAt(at: String): Namespace? = if (isAddress(at)) Namespace.of(at.removePrefix(ADDRESS)) else null

    // The planner at an extension address, whether or not it's there now: one that isn't says so when asked.
    fun plannerAt(at: String): TripPlanner? {
        if (!isAddress(at)) return null
        val ns = namespaceAt(at) ?: return MissingPlanner(Namespace("invalid", "invalid"))
        if (SafeMode.active) return OffPlanner(ns)
        // An installed extension's planner registers once its package is read (Hooks).
        if (!registered(ns, Capabilities.PLAN)) Hooks.refresh()
        return if (registered(ns, Capabilities.PLAN)) ExtensionPlanner(ns) else MissingPlanner(ns)
    }

    // Asks a contribution under the grant it holds now, and keeps the answer only if the grant is still that one after.
    private fun <T> ask(ns: Namespace, capability: String, host: List<ContractVersion>, call: (Contribution, ContractVersion, Execution) -> Outcome<T>): Answer<T>? {
        val k = key(ns, capability)
        val c = contributions[k] ?: return null
        val g = grants[k] ?: return null
        val provenance = Provenance(ns, c.id, c.revision)
        val versions = when (val s = c.source) { is PlanSource -> s.versions; is BoardSource -> s.versions; else -> emptyList() }
        val v = ContractVersion.negotiate(host, versions)
            ?: return Answer(Outcome.Unsupported("no common version of ${host.first().name}"), provenance)
        val out = try {
            call(c, v, Execution(ns, c.id, g.generation))
        } catch (e: Exception) {
            Outcome.Failed(e.javaClass.simpleName)
        }
        if (grants[k]?.generation != g.generation) return Answer(Outcome.Unavailable(), provenance)
        return Answer(out, provenance)
    }

    private class MissingPlanner(val ns: Namespace) : TripPlanner {
        override fun plan(r: PlanRequest): KavPlan.Planned = throw ProviderProblem(ns, Outcome.Unavailable(), missing = true)
    }

    private class OffPlanner(val ns: Namespace) : TripPlanner {
        override fun plan(r: PlanRequest): KavPlan.Planned = throw ProviderProblem(ns, Outcome.Unavailable(), off = true)
    }

    // An extension's planner as Kav's other planners are asked (TripPlanner): its plan through KavPlan's own parser,
    // every id in its namespace, and anything but a plan said as what it is.
    private class ExtensionPlanner(val ns: Namespace) : TripPlanner {
        override fun plan(r: PlanRequest): KavPlan.Planned {
            val c = contributions[key(ns, Capabilities.PLAN)]?.source as? PlanSource
                ?: throw ProviderProblem(ns, Outcome.Unavailable(), missing = true)
            // LegByLeg asks each part without stops on the way, so it doesn't come back here.
            if (r.stopovers.isNotEmpty() && !c.stopovers) return LegByLeg(this).plan(r)
            val at = (if (r.whenMs > 0) r.whenMs else System.currentTimeMillis()) / 1000
            val way = when (r.timeType) { Transit.TIME_ARRIVAL -> 'a'; Transit.TIME_LAST -> 'l'; else -> 'd' }
            val q = PlanQuery(r.from, r.to, at, way, r.routeTypes, r.stopovers.map { Stopover(it.lat, it.lon, it.stayMin) }, r.settings.letters)
            val answer = ask(ns, Capabilities.PLAN, HOST_PLAN) { s, v, e -> (s.source as PlanSource).plan(q, v, e) }
                ?: throw ProviderProblem(ns, Outcome.Unavailable(), missing = true)
            return when (val o = answer.outcome) {
                is Outcome.Ok -> try {
                    KavPlan.parse(o.value, r.stopovers.map { it.name }, ns)
                } catch (e: Exception) {
                    // Past one of the bounds (KavPlan.parse): too large, said as that.
                    val big = e is IllegalArgumentException && e.message.orEmpty().contains("more than")
                    throw ProviderProblem(ns, Outcome.Malformed(if (big) "too large" else e.javaClass.simpleName))
                }.let { p ->
                    // A route that can't be ridden as given never shows (ext/PlanChecks.kt); all of them dropped isn't "no routes".
                    val c = PlanChecks.check(p)
                    if (c.dropped > 0 && c.planned.plan.itineraries.isEmpty()) throw ProviderProblem(ns, Outcome.Malformed("infeasible"))
                    // The backstop to the bounds: what Kav would send the rider's route rules about these routes must
                    // fit one call, or the planner's answer is refused whole as too large (no rule is asked, or blamed).
                    val routes = c.planned.plan.itineraries
                    if (routes.isNotEmpty() && Pipeline.largestMessage(Pipeline.Query(way, q.atS, q.atS, q.settings, "en"), routes, c.planned.resolved) > Pipeline.MAX_MESSAGE)
                        throw ProviderProblem(ns, Outcome.Malformed("too large"))
                    c.planned
                }
                Outcome.Empty -> KavPlan.Planned(Transit.Plan(), Transit.Resolved())
                else -> throw ProviderProblem(ns, o)
            }
        }
    }

    // The stop lists of the timetable and stop-board providers the rider has on, each asked once per release in this
    // run and kept. A provider that can't give one is left out (and asked again next time).
    private val catalogues = ConcurrentHashMap<String, Pair<String, Catalogue>>()

    fun boards(): List<Namespace> {
        if (Hooks.armed) Hooks.refresh()
        return contributions.values.filter { it.capability == Capabilities.BOARD && grants.containsKey(key(it.namespace, it.capability)) }.map { it.namespace }
    }

    // An extension installed since Kav started registers when its package is first needed (Hooks), as a planner does.
    private fun ensureBoard(ns: Namespace) { if (!registered(ns, Capabilities.BOARD) && Hooks.armed) Hooks.refresh() }

    fun catalogue(ns: Namespace): Catalogue? {
        ensureBoard(ns)
        val c = contributions[key(ns, Capabilities.BOARD)] ?: return null
        catalogues[ns.key]?.takeIf { it.first == c.revision }?.let { return it.second }
        val got = ask(ns, Capabilities.BOARD, HOST_BOARD) { s, v, e -> (s.source as BoardSource).catalogue(v, e) }?.outcome
        return (got as? Outcome.Ok)?.value?.also { catalogues[ns.key] = c.revision to it }
    }

    // A saved stop of an extension's is kept with Kav's other saved stops as "ext:<its reference>", so it keeps its
    // publisher through a restart and a backup (the reference, not this run's handle, is what's saved). Kav's own
    // lookups never read it as one of theirs (Net.stopOfKey wants a number first).
    private const val SAVED = "ext:"
    fun savedKey(handle: Int): String? = RefTable.ref(handle)?.takeIf { it.kind == Kind.STOP }?.let { SAVED + it }
    fun savedHandle(key: String): Int? = if (!key.startsWith(SAVED)) null
        else Ref.parse(key.removePrefix(SAVED))?.takeIf { it.kind == Kind.STOP && !it.namespace.ministry }?.let { RefTable.intern(it) }

    // The name the rider knows a provider by: its extension's.
    // Its name as installed, switched on or not (a tab of a planner switched off still says whose it is).
    fun nameOf(ns: Namespace): String = (if (Hooks.armed) (Hooks.installed(ns) ?: Hooks.anyInstalled(ns))?.displayName else null) ?: ns.dataset

    // An extension's stop behind a handle, as its provider lists it; null for any other id, or one it doesn't list.
    fun stopOf(handle: Int): Pair<Namespace, StopEntry>? {
        val r = RefTable.ref(handle)?.takeIf { it.kind == Kind.STOP } ?: return null
        return catalogue(r.namespace)?.byId?.get(r.localId)?.let { r.namespace to it }
    }

    // The same, only from the stop lists already asked for in this run: never asks a provider, so the screen can call it.
    fun knownStop(handle: Int): Pair<Namespace, StopEntry>? {
        val r = RefTable.ref(handle)?.takeIf { it.kind == Kind.STOP } ?: return null
        return catalogues[r.namespace.key]?.second?.byId?.get(r.localId)?.let { r.namespace to it }
    }

    // The stops of every provider whose name or code has the words in it, handles and all, after Kav's own (the Stops tab).
    fun searchStops(q: String, max: Int = 30): List<Int> {
        val words = q.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return boards().flatMap { ns ->
            catalogue(ns)?.stops.orEmpty().filter { s -> words.all { w -> s.name.lowercase().contains(w) || s.code.lowercase() == w } }
                .map { RefTable.intern(ns.ref(Kind.STOP, it.id)) }
        }.take(max)
    }

    // The providers' stops within metres of a place, nearest first, with their distance.
    fun nearStops(lat: Double, lon: Double, metres: Double = 2_500.0, max: Int = 10): List<Pair<Int, Double>> =
        boards().flatMap { ns ->
            catalogue(ns)?.stops.orEmpty().map { it to distance(lat, lon, it.lat, it.lon) }.filter { it.second <= metres }
                .map { (s, d) -> RefTable.intern(ns.ref(Kind.STOP, s.id)) to d }
        }.sortedBy { it.second }.take(max)

    private fun distance(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6_371_000.0
        val x = Math.toRadians(d - b) * Math.cos(Math.toRadians((a + c) / 2))
        val y = Math.toRadians(c - a)
        return r * Math.sqrt(x * x + y * y)
    }

    // An extension's stop's departures: only the stop's own publisher is asked, never Kav's server or another
    // publisher with the same ids, wherever Kav shows departures (a board, results, a card). Null when the id isn't an
    // extension's stop.
    fun departures(stopId: Int, fromS: Long, limit: Int = 40): Answer<List<BoardDeparture>>? {
        val stop = RefTable.ref(stopId) ?: return null
        ensureBoard(stop.namespace)
        return ask(stop.namespace, Capabilities.BOARD, HOST_BOARD) { s, v, e -> (s.source as BoardSource).departures(stop.localId, fromS, limit, v, e) }
            ?: Answer(Outcome.Unavailable(), Provenance(stop.namespace, "", ""))
    }

    // A ride's own line's departures from its boarding stop, as the cards list them. Null for a ride that isn't on an
    // extension's stop and line, which Kav's own boards answer.
    fun lineDepartures(ride: Transit.Leg, nowS: Long): Outcome<List<Transit.Departure>>? {
        val line = RefTable.ref(ride.lineId) ?: return null
        val stop = RefTable.ref(ride.fromStop) ?: return null
        if (line.namespace != stop.namespace) return Outcome.Malformed("line and stop from different publishers")
        val answer = departures(ride.fromStop, nowS - 60) ?: return null
        return when (val o = answer.outcome) {
            is Outcome.Ok -> Outcome.Ok(o.value.filter { it.route == line.localId }.map { departure(stop.namespace, it, nowS) })
            Outcome.Empty -> Outcome.Empty
            is Outcome.Unsupported -> o
            is Outcome.Unavailable -> o
            is Outcome.Failed -> o
            is Outcome.Malformed -> o
        }
    }

    private fun departure(ns: Namespace, d: BoardDeparture, nowS: Long) = Transit.Departure(
        RefTable.internTrip(ns.ref(Kind.TRIP, d.trip)), d.scheduledS, rtUtc = d.estimatedS,
        givenUtc = if (d.estimatedS > 0) nowS else 0L, platform = d.platform,
    )

    // Live times for the rides on extensions' stops, from their own publishers' boards: the ride's own trip and its line's
    // others from that stop, under a pattern of the line's (its handle, which no ministry pattern is: those are negative).
    // Rides on Kav's stops are left to Kav's live provider (KavPlan.live).
    // A departure's vehicle, when the provider gave one, is the bus Live location shows (tracked, with its place). A
    // ride's own vehicle as its plan gave it (planned) stands in while its board has no place for that trip.
    fun arrivals(list: List<Transit.Itinerary>, nowS: Long = System.currentTimeMillis() / 1000): KavLive.Rides {
        val rides = list.flatMap { it.rides }.flatMap { it.options }
            .filter { RefTable.isHandle(it.fromStop) && RefTable.isHandle(it.lineId) }
        if (rides.isEmpty()) return KavLive.Rides(emptyMap(), emptyMap())
        val boards = HashMap<Int, List<BoardDeparture>>()
        val live = HashMap<Transit.ArrivalKey, Transit.Arrival>()
        val patterns = HashMap<Int, List<Int>>()
        fun arrival(ride: Transit.Leg, trip: Long, scheduledS: Long, estimatedS: Long, platform: String, v: Vehicle?) = Transit.Arrival(
            ride.fromStop, ride.lineId, trip, scheduledS, estimatedS, 0, 0, 0, 0, false, false, v != null,
            lat = v?.lat ?: 0.0, lon = v?.lon ?: 0.0,
            sampleUtc = v?.let { it.atS.takeIf { s -> s > 0 } ?: nowS } ?: if (estimatedS > 0) nowS else 0L,
            patternId = ride.lineId, platform = platform, bearing = v?.bearing ?: -1,
        )
        for (ride in rides) {
            val stop = RefTable.ref(ride.fromStop) ?: continue
            val line = RefTable.ref(ride.lineId)?.takeIf { it.namespace == stop.namespace } ?: continue
            val board = boards.getOrPut(ride.fromStop) {
                (departures(ride.fromStop, nowS - 3600, 80)?.outcome as? Outcome.Ok)?.value.orEmpty()
            }
            val mine = board.filter { it.route == line.localId }
            val own = planned[Transit.ArrivalKey(ride.fromStop, ride.tripId)]
            if (mine.isEmpty() && own == null) continue
            patterns[ride.lineId] = ((patterns[ride.lineId] ?: listOf(ride.fromStop)) + ride.toStop).distinct()
            for (d in mine) {
                val trip = RefTable.internTrip(stop.namespace.ref(Kind.TRIP, d.trip))
                live[Transit.ArrivalKey(ride.fromStop, trip)] = arrival(ride, trip, d.scheduledS, d.estimatedS, d.platform, d.vehicle)
            }
            val key = Transit.ArrivalKey(ride.fromStop, ride.tripId)
            if (own != null && live[key]?.tracked != true) {
                val d = ride.nextDeps.firstOrNull { it.tripId == ride.tripId }
                val before = live[key]
                live[key] = arrival(ride, ride.tripId, before?.staticUtc ?: d?.staticUtc ?: ride.dep,
                    before?.rtUtc ?: d?.rtUtc ?: 0L, before?.platform.orEmpty(), own)
            }
        }
        return KavLive.Rides(live, patterns)
    }

    // The vehicles extensions' plans gave their rides, by the ride's boarding stop and trip (KavPlan.parse). Bounded:
    // forgotten all at once.
    private val planned = ConcurrentHashMap<Transit.ArrivalKey, Vehicle>()

    internal fun planned(key: Transit.ArrivalKey, v: Vehicle) { if (planned.size > 5_000) planned.clear(); planned[key] = v }

    // The rider's display settings, for an extension to show things as Kav does (Kav.prefs, and plan.provide's input):
    // stop names in English or Hebrew, the 12- or 24-hour clock, and Kav's language.
    fun prefs(): org.json.JSONObject = org.json.JSONObject()
        .put("stopNames", if (uk.noammm.kav.data.StopNames.english) "en" else "he")
        .put("clock", if (uk.noammm.kav.ui.Shown.twelveHour) 12 else 24)
        .put("locale", uk.noammm.kav.ui.T.lang.code)
}
