@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.GoogleMaps
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Transit.Place
import uk.noammm.kav.data.TripLink
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import uk.noammm.kav.data.RailTrip
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.Tab
import uk.noammm.kav.ext.Actions

private data class OpenTrip(
    val trip: Transit.Itinerary,
    val resolved: Transit.Resolved,
    val fromLabel: String,
    val toLabel: String,
    val resume: Boolean = false,
    val backHome: Boolean = false,
    // The stops on the way it was planned through, kept with the trip so it's planned through them again.
    val stopovers: List<Transit.Place> = emptyList(),
    // Opened from a list leaving at a time: that time, whose minute its boarding times start from as the card's; else 0.
    val asked: Long = 0,
)

private enum class Sort(val labelText: () -> String) {
    RECOMMENDED({ T("Recommended", "מומלץ") }),
    FASTEST({ T("Fastest", "המהיר ביותר") }),
    EARLIEST_DEPARTURE({ T("Departs first", "יציאה מוקדמת") }),
    EARLIEST_ARRIVAL({ T("Arrives first", "הגעה מוקדמת") }),
    LEAST_TRANSFERS({ T("Fewest transfers", "פחות החלפות") }),
    LEAST_WALKING({ T("Least walking", "פחות הליכה") }),
    CHEAPEST({ T("Cheapest", "הזול ביותר") }),
    LOWEST_CO2({ T("Lowest CO2", "פליטת CO2 נמוכה") }),
}

private const val TOO_CLOSE_M = 120.0
private const val TOO_CLOSE = "too-close"

internal val HERE = listOf("Current location", "המיקום הנוכחי")

private fun timeWay(type: Int) = when (type) {
    Transit.TIME_ARRIVAL -> "arrive"
    Transit.TIME_LAST -> "last"
    else -> "depart"
}

private fun hereName() = T(HERE[0], HERE[1])

private fun herePlace(at: Pair<Double, Double>) = Place(hereName(), "", at.first, at.second)

private fun isHere(p: Place?) = p != null && p.name in HERE

// How long after its first bus left a latest route is planned again when opened, rather than shown as it was.
private const val LEFT_S = 120L

private fun departed(trip: Transit.Itinerary): Boolean =
    trip.rides.firstOrNull()?.let { it.dep + LEFT_S < System.currentTimeMillis() / 1000 } == true

// A trip with nothing on a timetable, a walk, sets out when it's started. The latest route opens as it was, so one
// planned hours before kept its times, and its arrival long past ended the trip as soon as it began.
internal fun startedNow(trip: Transit.Itinerary, now: Long): Transit.Itinerary {
    val late = now - trip.dep
    if (trip.rides.isNotEmpty() || late <= LEFT_S) return trip
    return Transit.Itinerary(
        trip.guid, trip.group, trip.legs.map { it.copy(dep = it.dep + late, arr = it.arr + late) }, trip.dep + late, trip.arr + late,
        trip.fare, trip.currency, trip.co2g, trip.accessible, trip.tags, trip.section, trip.sectionId, trip.singleFares, trip.provider,
    )
}

private fun endpointName(p: Place?) = if (isHere(p)) hereName() else p?.name

// Whether the rider is where the trip starts as they begin it: by a fresh position when there is one, else by the trip
// being planned from where they are.
private fun startsHere(trip: Transit.Itinerary, model: KavModel, plannedHere: Boolean): Boolean {
    val start = trip.legs.firstNotNullOfOrNull { it.shape.firstOrNull() }
    val here = model.here?.takeIf { model.hereFresh }
    return if (start != null && here != null) metres(here.first, here.second, start.first, start.second) < 150 else plannedHere
}

// A shared trip's end by the name it came with. The sender's own location isn't the rider's: it shows as a pinned
// location. A point shared without a name shows as its coordinates.
internal fun sharedPlace(name: String?, lat: Double, lon: Double) = Place(
    when {
        name in HERE -> T("Pinned location", "מיקום מסומן")
        !name.isNullOrBlank() -> name
        else -> "%.5f, %.5f".format(java.util.Locale.US, lat, lon)
    },
    "", lat, lon,
)

// A search as the action "plan" is told: its ends, when it leaves or arrives (0 for now) and which way.
private fun planArgs(from: Place?, to: Place?, fromLL: Pair<Double, Double>?, toLL: Pair<Double, Double>?, at: Long, type: Int): org.json.JSONObject {
    fun end(p: Place?, ll: Pair<Double, Double>?) = org.json.JSONObject().put("name", p?.name ?: hereName()).put("lat", ll?.first).put("lon", ll?.second)
    return org.json.JSONObject().put("from", end(from, fromLL)).put("to", end(to, toLL)).put("at", at / 1000)
        .put("way", timeWay(type))
}

@Composable
fun DirectionsOnline(model: KavModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val here = model.here?.takeIf { model.hereFresh }
    var fromPlace by remember { mutableStateOf<Place?>(null) }
    var toPlace by remember { mutableStateOf<Place?>(null) }
    var picking by remember { mutableStateOf<String?>(null) }
    // Places the rider wants to pass on the way, planned in one request.
    var stopovers by remember { mutableStateOf<List<Transit.Place>>(emptyList()) }
    var showResults by remember { mutableStateOf(false) }
    var hereOrigin by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    var plan by remember { mutableStateOf(Transit.Plan()) }
    var raw by remember { mutableStateOf<List<Transit.Itinerary>>(emptyList()) }
    // A new search's old cards go with a short fade (their items) and slide down a little (this, 0 to 1).
    val leaving = remember { androidx.compose.animation.core.Animatable(0f) }
    var planning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // The rider's route rules (extensions, ext/Pipeline.kt): a line on what they did to the routes, a required one that
    // failed, Kav's routes kept unchecked for the one fallback the rider may ask for, and whether they're still judging.
    var ruleNote by remember { mutableStateOf<String?>(null) }
    // The routes the rider's rules left out of this search (Pipeline.Checked.hiddenRoutes), and whether the rider asked
    // to see them this once (Pipeline.showOnce). What the notice says and how it looks is drawn from
    // Pipeline.hiddenNotice(hiddenRoutes); setting showHidden is its one tap. Both go with the next search.
    var hiddenRoutes by remember { mutableStateOf<List<uk.noammm.kav.ext.Pipeline.Hidden>>(emptyList()) }
    var showHidden by remember { mutableStateOf(false) }
    var ruleFailure by remember { mutableStateOf<uk.noammm.kav.ext.Pipeline.Failure?>(null) }
    var unchecked by remember { mutableStateOf<List<Transit.Itinerary>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    // Bumped by Try again after a plan failed, to plan the same trip again.
    var planAgain by remember { mutableIntStateOf(0) }
    var resolved by remember { mutableStateOf(Transit.Resolved()) }

    var open by remember { mutableStateOf<OpenTrip?>(null) }
    // A trip page opened by a tap: an extension holding "trip.open" goes first (ext/Actions.kt).
    var asking by remember { mutableStateOf<OpenTrip?>(null) }
    fun openTrip(o: OpenTrip) {
        asking = o
        if (!uk.noammm.kav.ext.Actions.take(ctx, "trip.open") { org.json.JSONObject().put("trip", uk.noammm.kav.ext.Slots.trip(o.trip, o.resolved)) }) open = o
    }
    // Kav's own search for Kav.kavAction("plan"): this search planned again, no extension asked.
    var planOwn by remember { mutableStateOf(false) }
    // Kav's own, for Kav.kavAction: the trip last tapped opens; the search on screen is planned.
    DisposableEffect(Unit) {
        uk.noammm.kav.ext.Actions.own("trip.open") { _, _ -> asking?.let { open = it } }
        uk.noammm.kav.ext.Actions.own("plan") { _, _ -> planOwn = true; planAgain++; showResults = true }
        onDispose { }
    }
    var sort by remember { mutableStateOf(Sort.RECOMMENDED) }
    var departAt by remember { mutableLongStateOf(0L) }
    var timeType by remember { mutableIntStateOf(Transit.TIME_DEPARTURE) }
    var whenOpen by remember { mutableStateOf(false) }
    var orderOpen by remember { mutableStateOf(false) }
    var linkTrip by remember { mutableStateOf<List<TripLink.Ride>?>(null) }
    // The recent trip opened, whose planner plans it again when it was an extension.
    var recent by remember { mutableStateOf<RecentTrip?>(null) }
    // The latest route's rides, when it's opened after its first bus left: planned again for now on the planner chosen,
    // it opens on the soonest departure of the same lines, else on the list of routes.
    var againTrip by remember { mutableStateOf<List<TripLink.Ride>?>(null) }
    var openingLink by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }
    var lastLink by remember { mutableStateOf<TripLink.Plan?>(null) }
    val filters = model.filters
    // Whose routes show: the planner chosen in Settings, or the tab picked when every planner's are shown.
    var tab by remember { mutableStateOf<Source?>(null) }
    val tabs = if (KavPlan.everyPlanner) KavPlan.tabs() else emptyList()
    val planner = tab?.takeIf { it in tabs } ?: KavPlan.source
    fun openSearch() {
        hereOrigin = null; stopovers = emptyList(); linkError = null; fromPlace = null
        departAt = 0L; timeType = Transit.TIME_DEPARTURE; picking = "to"
    }
    fun pickPlace(which: String, index: Int = -1) {
        when (which) {
            "from", "to" -> picking = which
            "stop" -> if (index < 0) picking = "stop" else if (index in stopovers.indices) picking = "stop:$index"
        }
    }
    fun swapEnds() {
        val a = fromPlace
        fromPlace = toPlace
        toPlace = a ?: hereOrigin?.let(::herePlace)
    }
    fun removeStop(index: Int) { stopovers = stopovers.filterIndexed { i, _ -> i != index } }
    fun stayAtStop(index: Int, minutes: Int) {
        if (minutes in 0..60) stopovers = stopovers.mapIndexed { i, s -> if (i == index) s.staying(minutes) else s }
    }
    fun chooseTime(ms: Long, type: Int) {
        departAt = ms; timeType = type; whenOpen = false; showResults = true
    }
    fun chooseOrder(order: Sort) { sort = order; orderOpen = false }
    fun resultAction(name: String, args: () -> JSONObject = { JSONObject() }, own: () -> Unit) {
        if (!Actions.take(ctx, name, args)) own()
    }
    fun pickResultPlace(which: String, index: Int = -1) {
        resultAction("results.place.pick", {
            JSONObject().put("end", which).put("index", index.takeIf { it >= 0 })
        }) { pickPlace(which, index) }
    }
    fun selectTime(ms: Long, type: Int) {
        resultAction("results.time.select", {
            JSONObject().put("at", ms / 1000).put("way", timeWay(type))
        }) { chooseTime(ms, type) }
    }
    DisposableEffect(model) {
        Actions.own("search.open") { _, _ ->
            if (model.tab == Tab.Directions && !model.settingsOpen && !showResults && open == null) openSearch()
        }
        fun onResults(name: String, own: (JSONObject) -> Unit) {
            Actions.own(name) { _, a ->
                if (model.tab == Tab.Directions && !model.settingsOpen && showResults && open == null) own(a)
            }
        }
        onResults("results.place.pick") { pickPlace(it.optString("end"), it.optInt("index", -1)) }
        onResults("results.endpoints.swap") { swapEnds() }
        onResults("results.stop.remove") { removeStop(it.optInt("index", -1)) }
        onResults("results.stop.stay") { stayAtStop(it.optInt("index", -1), it.optInt("minutes", -1)) }
        onResults("results.time.select") { a ->
            val seconds = a.optLong("at", -1)
            val type = when (a.optString("way")) {
                "depart" -> Transit.TIME_DEPARTURE
                "arrive" -> Transit.TIME_ARRIVAL
                "last" -> Transit.TIME_LAST
                else -> null
            }
            if (type != null && seconds in 0..4_102_444_800L) chooseTime(seconds * 1000, type)
        }
        onResults("results.order.select") { a ->
            Sort.entries.firstOrNull { it.name.lowercase() == a.optString("order") && (it != Sort.LOWEST_CO2 || Shown.co2) }
                ?.let(::chooseOrder)
        }
        onResults("results.planner.select") { a ->
            if (KavPlan.everyPlanner) KavPlan.tabs().firstOrNull { it.key == a.optString("source") }?.let { tab = it }
        }
        onDispose { }
    }
    val everyScope = rememberCoroutineScope()
    val every = remember { EveryPlanner(everyScope) }
    // Routes a tab already had when it was picked: their live times are from when they were planned.
    var kept by remember { mutableStateOf(false) }

    LaunchedEffect(model.returnHome) {
        if (model.returnHome) {
            open = null; picking = null; showResults = false; linkTrip = null
            model.pendingLink = null; linkError = null
            model.returnHome = false
        }
    }

    // Planning opened from a stop's board (From here, To here) goes back to that board, not Home.
    var backToBoard by remember { mutableStateOf(false) }
    fun leaveResults() {
        showResults = false
        if (backToBoard) { backToBoard = false; model.tab = uk.noammm.kav.Tab.Stations }
    }
    LaunchedEffect(model.pendingFrom, model.pendingTo) {
        if (model.pendingFrom != null || model.pendingTo != null) {
            hereOrigin = null; stopovers = emptyList(); showResults = true
            backToBoard = model.stationStop >= 0
        }
        model.pendingFrom?.let { fromPlace = it; model.pendingFrom = null }
        model.pendingTo?.let { toPlace = it; model.pendingTo = null }
    }

    // The link this run was started for, not the one pending when it gets to run: opened with Kav closed, this screen is
    // first made before the link is handed over, and a run started then would take the link up, be cancelled as the
    // link's own run starts, and on its way out clear the link from under that run, leaving an empty planner.
    val pendingLink = model.pendingLink
    LaunchedEffect(pendingLink) {
        val incoming = pendingLink ?: return@LaunchedEffect
        // A place shared while one is being picked (a favourite's, say) goes into that search instead of starting a trip.
        if (picking != null && incoming.rides.isEmpty() && incoming.fromLat == null) {
            val point = if (incoming.toLat != null && incoming.toLon != null)
                "%.6f, %.6f".format(java.util.Locale.US, incoming.toLat, incoming.toLon) else null
            (point ?: incoming.toName?.takeIf { it.isNotBlank() })?.let {
                model.placeQuery = it
                model.pendingLink = null
                return@LaunchedEffect
            }
        }
        lastLink = incoming; openingLink = true; linkError = null
        hereOrigin = null; stopovers = emptyList()
        open = null; picking = null; linkTrip = null
        fromPlace = null; toPlace = null; showResults = true
        try {
            // A trip shared from another app by its id: that app's servers have its ends.
            val link = incoming.shared?.let { withContext(Dispatchers.IO) { TripLink.resolve(it) } } ?: incoming
            val exact = link.exact
            if (exact != null) {
                val trip = exact.plan.itineraries.first()
                val start = if (link.fromLat != null && link.fromLon != null)
                    sharedPlace(link.fromName, link.fromLat, link.fromLon)
                else trip.legs.firstOrNull { it.shape.isNotEmpty() }?.shape?.firstOrNull()
                    ?.let { sharedPlace(null, it.first, it.second) }
                val end = if (link.toLat != null && link.toLon != null)
                    sharedPlace(link.toName, link.toLat, link.toLon)
                else trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull()
                    ?.let { sharedPlace(null, it.first, it.second) }
                fromPlace = start; toPlace = end; showResults = false
                open = OpenTrip(trip, exact.resolved, start?.name ?: T("Start", "התחלה"),
                    end?.name ?: T("Destination", "יעד"), backHome = true)
            } else {
                // A geo: link may name a place without a point: Google's place search finds it.
                val found = if (link.toLat == null && link.toName != null) withContext(Dispatchers.IO) {
                    uk.noammm.kav.data.Search.provider.suggest(link.toName, model.here).places.firstOrNull()
                } else null
                val toLat = found?.lat ?: link.toLat ?: throw IllegalArgumentException("Missing destination coordinates")
                val toLon = found?.lon ?: link.toLon ?: throw IllegalArgumentException("Missing destination coordinates")
                fromPlace = if (link.fromLat != null && link.fromLon != null) sharedPlace(link.fromName, link.fromLat, link.fromLon) else null
                toPlace = found ?: sharedPlace(link.toName, toLat, toLon)
                // A link that names its rides plans from a little before its time, so the planner still reaches that very
                // departure and the trip opens as it was shared.
                departAt = if (link.rides.isNotEmpty() && link.departMs > 0) link.departMs - 5 * 60_000 else link.departMs
                timeType = Transit.TIME_DEPARTURE
                linkTrip = link.rides.takeIf { it.isNotEmpty() && link.autoRun }
                showResults = link.autoRun
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) {
            linkError = if (e is TripLink.Expired) T("This shared trip is no longer available. Ask for a new link.",
                "הנסיעה המשותפת כבר לא זמינה. בקשו קישור חדש.")
                else T("Couldn't open this shared trip. ${e.message.orEmpty()}", "לא ניתן לפתוח את הנסיעה המשותפת. ${e.message.orEmpty()}")
        } finally {
            openingLink = false
            if (model.pendingLink === incoming) model.pendingLink = null
        }
    }

    // A route pins the fresh position accepted for this search, never the map's cached preview.
    val fromLL = fromPlace?.let { it.lat to it.lon } ?: hereOrigin
    val toLL = toPlace?.let { it.lat to it.lon }
    val fromIsHere = fromPlace == null || isHere(fromPlace)

    var findingHere by remember { mutableStateOf(false) }
    var findHereFailed by remember { mutableStateOf(false) }
    var hereRetry by remember { mutableIntStateOf(0) }
    DisposableEffect(showResults, fromPlace, hereOrigin, picking, hereRetry, openingLink, linkError) {
        if (showResults && fromPlace == null && hereOrigin == null && picking == null && !openingLink && linkError == null) {
            findingHere = true; findHereFailed = false
            val stop = requestLocationOnce(ctx, requireFresh = true, onFail = {
                findingHere = false; findHereFailed = true
                linkTrip = null
            }) {
                hereOrigin = it
                model.locate(it.first, it.second)
                findingHere = false
            }
            onDispose { stop(); findingHere = false }
        } else {
            onDispose { }
        }
    }

    // The inputs the results on screen were planned for: anything else is still being planned, never "no routes". The
    // planner settings too, so routes planned for other settings are planned again, never shown for these.
    val settings = KavPlan.settings
    val planKey = listOf(fromLL, toLL, departAt, timeType, filters, stopovers, planner, settings)
    var plannedFor by remember { mutableStateOf<List<Any?>?>(null) }
    LaunchedEffect(showResults, fromLL, toLL, departAt, timeType, filters, stopovers, openingLink, planAgain, planner, settings) {
        if (!showResults || openingLink) { planning = false; return@LaunchedEffect }
        if (departAt != 0L && departAt < System.currentTimeMillis() && timeType != Transit.TIME_LAST) {
            departAt = 0L; timeType = Transit.TIME_DEPARTURE
            return@LaunchedEffect
        }
        raw = emptyList(); resolved = Transit.Resolved(); error = null
        ruleNote = null; ruleFailure = null; unchecked = emptyList(); checking = false; hiddenRoutes = emptyList(); showHidden = false
        launch { leaving.snapTo(0f); leaving.animateTo(1f, androidx.compose.animation.core.tween(150)) }
        if (fromLL == null || toLL == null) { planning = false; return@LaunchedEffect }
        if (metres(fromLL.first, fromLL.second, toLL.first, toLL.second) < TOO_CLOSE_M) {
            error = TOO_CLOSE; planning = false; linkTrip = null; return@LaunchedEffect
        }
        // An extension holding "plan" goes first: one that takes the search closes the results, and Kav plans nothing.
        val own = planOwn.also { planOwn = false }
        if (!own && uk.noammm.kav.ext.Actions.take(ctx, "plan") { planArgs(fromPlace, toPlace, fromLL, toLL, departAt, timeType) }) {
            planning = false; showResults = false; return@LaunchedEffect
        }
        planning = true
        if (departAt != 0L) delay(250)
        try {
            // The server chosen to plan, Kav's or the rider's own, plans the trip; when it can't, the rider is told and
            // can try again. A shared trip Kav planned only a Kav planner can find again, whichever plans now. With a
            // tab for each planner, the tab's planner plans, and the others are asked at the same time.
            val link = linkTrip
            val types = routeTypesFor(filters)
            val taxi = ResultFilter.TAXI in filters
            // A recent trip an extension planned is planned again by that extension only: if it's gone, it says so.
            val pinned = recent?.takeIf { it.to === toPlace && it.from === fromPlace && it.planner.isNotEmpty() }
                ?.let { uk.noammm.kav.ext.Extensions.address(it.planner) }
            val kav = if (link == null && planner in tabs && pinned == null) {
                every.routes(listOf(fromLL, toLL, departAt, timeType, filters, stopovers, planAgain, settings), planner, tabs,
                    if (departAt == 0L) LEAVE_NOW_KEPT_MS else Long.MAX_VALUE) { by ->
                    val at = KavPlan.plannerOf(by) ?: throw java.io.IOException("no planner answering")
                    KavPlan.plan(fromLL, toLL, departAt, timeType, types, taxi, stopovers, at, settings)
                }.also { kept = it.isCompleted }.await().getOrThrow()
            } else {
                kept = false
                val at = pinned ?: (if (link != null) KavPlan.linkPlanner() else KavPlan.planner())
                    ?: throw java.io.IOException("no planner answering")
                withContext(Dispatchers.IO) { KavPlan.plan(fromLL, toLL, departAt, timeType, types, taxi, stopovers, at, settings = settings) }
            }
            val res = kav.plan
            plan = res
            raw = res.laidOut()
            linkTrip?.let { named ->
                if (raw.none { exactTrip(it, named) }) {
                    res.itineraries.firstOrNull { exactTrip(it, named) }
                        ?.let { raw = listOf(it) + raw }
                }
            }
            resolved = kav.resolved
            val q = uk.noammm.kav.ext.Pipeline.Query(
                way = when (timeType) { Transit.TIME_ARRIVAL -> 'a'; Transit.TIME_LAST -> 'l'; else -> 'd' },
                atS = (if (departAt > 0) departAt else System.currentTimeMillis()) / 1000, nowS = System.currentTimeMillis() / 1000,
                settings = settings.letters, locale = T.lang.code, stays = stopovers.map { it.stayMin * 60L },
            )
            // The rider's route rules judge the routes once Kav's are on screen, so a slow extension never holds them
            // up; their changes come within the pipeline's deadline (PLAN.md section 4).
            if (uk.noammm.kav.ext.Hooks.hasRules() && raw.isNotEmpty()) {
                planning = false; plannedFor = planKey; checking = true
                val kavRoutes = raw
                val checked = withContext(Dispatchers.IO) { uk.noammm.kav.ext.Hooks.check(kavRoutes, resolved, q) }
                checking = false
                if (checked.failure != null) { unchecked = kavRoutes; raw = emptyList(); ruleFailure = checked.failure }
                else { raw = checked.routes; ruleNote = rulesSaid(checked); hiddenRoutes = checked.hiddenRoutes }
            }
            model.activeJourney?.takeIf { active -> raw.any { it === active.trip } }?.let {
                model.activeJourney = it.copy(resolved = Transit.Resolved(
                    it.resolved.lines + resolved.lines, it.resolved.stops + resolved.stops,
                    it.resolved.routeTypes + resolved.routeTypes,
                    resolved.live + it.resolved.live, resolved.shapes + it.resolved.shapes,
                    it.resolved.pollSecs, it.resolved.patterns + resolved.patterns,
                    it.resolved.delays + resolved.delays,
                ))
            }
            // Every search that found routes becomes a recent trip with them,
            // which leave out the taxi card.
            val dest = toPlace
            val found = raw.filterNot(res::isTaxiCard)
            if (dest != null && !isHere(dest) && found.isNotEmpty()) {
                val start = fromPlace?.takeUnless(::isHere)
                    ?: Place(T("Saved start", "נקודת ההתחלה השמורה"), "", fromLL.first, fromLL.second)
                val trip = RecentTrip(start, dest, System.currentTimeMillis(), stopovers, found.first().provider)
                withContext(Dispatchers.IO) { runCatching { Prefs.rememberTrip(ctx, trip) } }
            }
            // Open only after this search and its stop/line details have arrived. A separate effect
            // can otherwise see the previous search before this one has started.
            val named = linkTrip
            val again = againTrip
            val match = named?.let { raw.firstOrNull { exactTrip(it, named) } ?: raw.firstOrNull { sameLines(it, named) } }
                ?: again?.let { raw.filter { sameLines(it, again) }.minByOrNull { it.dep } }
            val fromName = fromPlace?.name ?: hereName()
            val toName = toPlace?.name ?: T("Destination", "יעד")
            if (match != null) {
                open = OpenTrip(match, resolved, fromName, toName, stopovers = stopovers)
            }
            planning = false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Transit.PlannerRefusal) {
            android.util.Log.i("KavPlan", "planner refused: ${e.code} ${e.title}")
            error = when (e.code) {
                Transit.PLAN_TOO_CLOSE -> TOO_CLOSE
                Transit.PLAN_NO_ROUTES -> T(
                    "Nothing is running for this trip at that time. Try another departure time.",
                    "אין קווים לנסיעה הזו בשעה הזו. נסו שעת יציאה אחרת.",
                )
                Transit.PLAN_TOO_FAR -> T(
                    "These two places are too far apart to plan a trip between.",
                    "שני המקומות האלה רחוקים מכדי לתכנן נסיעה ביניהם.",
                )
                Transit.PLAN_NO_COVERAGE -> T(
                    "Kav's planner has no timetable for this area.",
                    "לתכנון של Kav אין לוח זמנים לאזור הזה.",
                )
                else -> e.detail.ifBlank { e.title }.ifBlank {
                    T("Kav's planner would not plan this trip.", "התכנון של Kav לא תכנן את הנסיעה הזו.")
                }
            }
            planning = false
        } catch (e: uk.noammm.kav.ext.ProviderProblem) {
            android.util.Log.i("KavPlan", "extension planner: ${e.message}")
            error = if (e.off) T(
                "Extensions are off while Kav is in safe mode, so the extension you chose to plan with wasn't asked. Turn them back on in Settings › Extensions.",
                "התוספים כבויים כל עוד Kav במצב בטוח, ולכן התוסף שבחרתם לתכנון לא נשאל. אפשר להפעיל אותם שוב בהגדרות › תוספים.",
            ) else if (e.missing) T(
                "The extension that planned this trip isn't installed, so it wasn't planned again.",
                "התוסף שתכנן את הנסיעה הזו לא מותקן, ולכן היא לא תוכננה שוב.",
            ) else plannerProblemWords(ctx, e)
            planning = false
        } catch (e: Exception) {
            android.util.Log.e("KavPlan", "online plan failed", e)
            error = when {
                !uk.noammm.kav.hasNetwork(ctx) -> T(
                    "No connection. The selected planner needs one.",
                    "אין חיבור. המתכנן שנבחר זקוק לחיבור.",
                )
                e is KavPlan.NoTimetable -> T(
                    "Kav's planner has no timetable for that day yet. Try a nearer date.",
                    "לתכנון של Kav עוד אין לוח זמנים ליום הזה. נסו תאריך קרוב יותר.",
                )
                KavLive.serverOf(planner) != KavLive.KAV_SERVER -> T("Your server didn't answer.", "השרת שלכם לא ענה.")
                else -> T("Kav's server didn't answer.", "השרת של Kav לא ענה.")
            }
            planning = false
        }
        plannedFor = planKey
        linkTrip = null
        againTrip = null
    }

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(showResults, raw, resolved.pollSecs, open?.trip, model.activeJourney?.trip, lifecycle, KavLive.source) {
        val active = model.activeJourney?.trip
        if (!showResults || raw.isEmpty() || (active != null && open?.trip === active)) return@LaunchedEffect
        val otherTrips = raw.filterNot { it === active }
        if (otherTrips.isEmpty()) return@LaunchedEffect
        // A search comes with its live times. A tab's kept routes get theirs at once, then every poll.
        var wait = !kept
        var back = false
        // Only while Home is in front: behind another tab, or with Kav away, they wait, and come at once on coming back.
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            if (back) wait = false
            back = true
            while (true) {
                if (wait) kotlinx.coroutines.delay(resolved.pollSecs.coerceIn(15, 120) * 1000L)
                wait = true
                // From the server live locations come from; any other routes stay as they were kept.
                if (!otherTrips.all { KavPlan.refreshes(it) }) break
                val refreshed = withContext(Dispatchers.IO) { KavPlan.live(otherTrips, resolved) }
                resolved = Transit.Resolved(
                    refreshed.lines + resolved.lines, refreshed.stops + resolved.stops,
                    refreshed.routeTypes + resolved.routeTypes, refreshed.live,
                    refreshed.shapes + resolved.shapes, refreshed.pollSecs, refreshed.patterns + resolved.patterns,
                    resolved.delays + refreshed.delays,
                )
            }
        }
    }

    // A trip opened on its own (the latest route, a link, one resumed) has its live times kept fresh as search results'
    // are, from the moment it opens: without, it showed what was known when it was saved.
    var openLive by remember { mutableStateOf<Pair<Transit.Itinerary, Transit.Resolved>?>(null) }
    LaunchedEffect(open?.trip, model.activeJourney?.trip, lifecycle, KavLive.source) {
        val chosen = open ?: return@LaunchedEffect
        if (model.activeJourney?.trip === chosen.trip || raw.any { it === chosen.trip } || !KavPlan.refreshes(chosen.trip)) return@LaunchedEffect
        var known = openLive?.takeIf { it.first === chosen.trip }?.second ?: chosen.resolved
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                known = withContext(Dispatchers.IO) { KavPlan.live(listOf(chosen.trip), known) }
                openLive = chosen.trip to known
                kotlinx.coroutines.delay(known.pollSecs.coerceIn(15, 120) * 1000L)
            }
        }
    }

    // On Shabbat: whether nothing to ride was found at all (Shabbat.kt).
    val nothingOnShabbat = remember(raw, departAt, timeType) {
        timeType == Transit.TIME_DEPARTURE &&
            nothingOnShabbat(if (departAt > 0) departAt / 1000 else System.currentTimeMillis() / 1000, raw)
    }
    val shown = remember(raw, sort, filters, resolved, showHidden, hiddenRoutes) {
        val kept = filterResults(if (showHidden) uk.noammm.kav.ext.Pipeline.showOnce(raw, hiddenRoutes) else raw, filters, resolved)
        when (sort) {
            Sort.RECOMMENDED -> kept
            Sort.FASTEST -> kept.sortedBy { it.durationMin }
            Sort.EARLIEST_DEPARTURE -> kept.sortedBy { it.dep }
            Sort.EARLIEST_ARRIVAL -> kept.sortedBy { it.arr }
            Sort.LEAST_TRANSFERS -> kept.sortedWith(compareBy({ it.transfers }, { it.durationMin }))
            Sort.LEAST_WALKING -> kept.sortedWith(
                compareBy({ i -> i.legs.filter { it.kind == Transit.LegKind.WALK }.sumOf { it.minutes } }, { it.durationMin }),
            )
            Sort.CHEAPEST -> kept.sortedWith(compareBy({ if (it.fare < 0) Int.MAX_VALUE else it.fare }, { it.durationMin }))
            Sort.LOWEST_CO2 -> kept.sortedWith(compareBy({ uk.noammm.kav.data.Emissions.grams(it, resolved).let { g -> if (g < 0) Int.MAX_VALUE else g } }, { it.durationMin }))
        }
    }
    // The first route's time, and the last one shown: once there was one, Earlier and Later stay while new routes load,
    // with the time those are for between them (loadingTime()).
    val firstTime = shown.firstOrNull()?.let { clockFormat().format(java.util.Date(it.dep * 1000)) }
    var shiftTime by remember { mutableStateOf("") }
    SideEffect { firstTime?.let { shiftTime = it } }

    androidx.activity.compose.BackHandler(enabled = showResults && open == null && picking == null && linkTrip == null) {
        leaveResults()
    }

    val under by underSearch(picking != null)
    VeilMaps { under > 0f && under < 1f }
    androidx.compose.animation.AnimatedContent(
        targetState = Triple(open, showResults, linkTrip != null || openingLink),
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = under },
        transitionSpec = {
            if (targetState.first != null || (targetState.second && !initialState.second)) forward()
            else backward()
        },
        label = "directions",
    ) { (chosen, displayingResults, opening) ->
        VeilMaps()
        if (chosen != null) {
            // A trip under way planned again on the way (Replan) shows its new route in the old one's place.
            val trip = model.activeJourney?.takeIf { chosen.trip in it.before }?.trip ?: chosen.trip
            val active = model.activeJourney?.takeIf { it.trip === trip }
            val detailResolved = active?.resolved
                ?: if (!chosen.resume && raw.any { it === chosen.trip }) resolved
                else openLive?.takeIf { it.first === chosen.trip }?.second ?: chosen.resolved
            LaunchedEffect(trip) {
                withContext(Dispatchers.IO) {
                    uk.noammm.kav.data.RecentRoutes.saveLatest(ctx,
                        ActiveJourney(trip, detailResolved, active?.fromLabel ?: chosen.fromLabel, chosen.toLabel, stopovers = active?.stopovers ?: chosen.stopovers))
                }
            }
            TripDetailScreen(
                model,
                trip, detailResolved,
                fromLabel = active?.fromLabel ?: chosen.fromLabel,
                toLabel = chosen.toLabel,
                // Once started, the trip's times are the journey's, not the search's.
                asked = if (active == null) chosen.asked else 0L,
                onBack = { open = null; if (chosen.resume || chosen.backHome) showResults = false },
                startInNavigation = chosen.resume || trip !== chosen.trip,
                onStart = {
                    if (active == null) {
                        model.journeyStep = 0
                        val leaving = startedNow(chosen.trip, System.currentTimeMillis() / 1000)
                        model.activeJourney = ActiveJourney(
                            leaving, detailResolved, chosen.fromLabel, chosen.toLabel,
                            fromHere = startsHere(chosen.trip, model, fromIsHere), stopovers = chosen.stopovers,
                            // Shown in the place of the trip as it was opened, as a trip planned again is.
                            before = if (leaving === chosen.trip) emptyList() else listOf(chosen.trip),
                        )
                    }
                },
                onNavigating = { model.navigating = it },
                onHome = { open = null; showResults = false },
                onEnd = {
                    if (model.activeJourney?.trip === trip) model.activeJourney = null
                    open = null
                    showResults = false
                },
            )
            return@AnimatedContent
        }

    if (!displayingResults) {
        HomeScreen(
            model = model,
            recentTrips = Prefs.trips(ctx),
            onSearch = { resultAction("search.open") { openSearch() } },
            onFavourite = { p ->
                hereOrigin = null; stopovers = emptyList(); linkError = null; fromPlace = null; toPlace = p
                departAt = 0L; timeType = Transit.TIME_DEPARTURE
                showResults = true
            },
            onSetFavourite = { f -> model.settingFavourite = f },
            onTrip = { t ->
                recent = t
                hereOrigin = null; stopovers = t.stopovers; linkError = null; fromPlace = t.from; toPlace = t.to
                departAt = 0L; timeType = Transit.TIME_DEPARTURE
                linkTrip = null; lastLink = null
                planAgain++
                showResults = true
            },
            onResume = {
                model.activeJourney?.let { journey ->
                    openTrip(OpenTrip(journey.trip, journey.resolved, journey.fromLabel, journey.toLabel, resume = true, stopovers = journey.stopovers))
                }
            },
            onLatest = { journey ->
                val start = journey.trip.legs.firstOrNull { it.shape.isNotEmpty() }?.shape?.firstOrNull()
                val end = journey.trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull()
                if (!departed(journey.trip) || start == null || end == null) {
                    openTrip(OpenTrip(journey.trip, journey.resolved, journey.fromLabel, journey.toLabel, backHome = true, stopovers = journey.stopovers))
                } else {
                    // Its first bus has left: the route as it can be ridden now, through the same stops on the way. A route
                    // from where the rider was is planned from where they are.
                    hereOrigin = null; stopovers = journey.stopovers; linkTrip = null
                    val from = if (journey.fromLabel in HERE) null else Place(journey.fromLabel, "", start.first, start.second)
                    val to = Place(journey.toLabel, "", end.first, end.second)
                    fromPlace = from; toPlace = to
                    // Planned again by the planner that planned it, when that was an extension (as a recent trip is).
                    recent = RecentTrip(from, to, 0L, journey.stopovers, journey.trip.provider)
                    departAt = 0L; timeType = Transit.TIME_DEPARTURE
                    againTrip = journey.trip.rides.map { TripLink.Ride(it.lineId, it.tripId, it.dep) }
                    showResults = true
                }
            },
        )
        return@AnimatedContent
    }

    if (opening) {
        LoadingScreen(T("Finding your route", "מוצאים לכם מסלול")) {
            linkTrip = null; model.pendingLink = null; openingLink = false; showResults = false
        }
        return@AnimatedContent
    }

    if (linkError != null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(T("Shared trip", "נסיעה משותפת"), back = { linkError = null; showResults = false })
            Note(linkError.orEmpty(), Modifier.padding(K.gap4))
            KavButton(T("Try again", "נסו שוב"), { model.pendingLink = lastLink }, Modifier.padding(horizontal = K.gap2), style = ButtonStyle.Plain)
        }
        return@AnimatedContent
    }

    val resultsState = androidx.compose.foundation.lazy.rememberLazyListState()
    FreshAlerts()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = resultsState,
            contentPadding = PaddingValues(bottom = K.gap6 + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            item(key = "trip") {
                KavPart("results.endpoints", Triple(fromPlace, toPlace, stopovers), {
                    JSONObject().put("from", endpointName(fromPlace) ?: hereName()).put("to", endpointName(toPlace))
                        .put("stops", JSONArray(stopovers.map { JSONObject().put("name", it.name).put("minutes", it.stayMin) }))
                }) {
                    PlanHeader(
                        // With no start picked the trip starts where you are, so it says so even before the fix arrives.
                        from = endpointName(fromPlace) ?: hereName(),
                        to = endpointName(toPlace),
                        fromIsHere = fromIsHere,
                        toIsHere = isHere(toPlace),
                        onFrom = { pickResultPlace("from") },
                        onTo = { pickResultPlace("to") },
                        stops = stopovers.map { it.name },
                        stays = stopovers.map { it.stayMin },
                        onStay = { i, minutes ->
                            resultAction("results.stop.stay", { JSONObject().put("index", i).put("minutes", minutes) }) { stayAtStop(i, minutes) }
                        },
                        onAddStop = { pickResultPlace("stop") },
                        onStop = { i -> pickResultPlace("stop", i) },
                        onRemoveStop = { i -> resultAction("results.stop.remove", { JSONObject().put("index", i) }) { removeStop(i) } },
                        // Places counted from the start, 0. The start moved away takes where you are as a place like the
                        // others, and a place moved to the start becomes it.
                        onMove = { a, b ->
                            val dest = toPlace
                            val start = fromPlace ?: (hereOrigin ?: here)?.let(::herePlace)
                            if (dest != null && (a > 0 && b > 0 || start != null)) {
                                val way = (listOfNotNull(start.takeIf { a == 0 || b == 0 }) + stopovers + dest).toMutableList()
                                val shift = if (a == 0 || b == 0) 0 else 1
                                way.add(b - shift, way.removeAt(a - shift))
                                if (shift == 0) { hereOrigin = null; fromPlace = way.removeAt(0) }
                                toPlace = way.last(); stopovers = way.dropLast(1)
                            }
                        },
                        onSwap = { resultAction("results.endpoints.swap") { swapEnds() } },
                        onBack = { leaveResults() },
                    )
                }
            }
            item(key = "when") {
                KavPart("results.options", Triple(departAt, timeType, sort), {
                    JSONObject().put("at", departAt / 1000).put("way", timeWay(timeType)).put("order", sort.name.lowercase())
                }) {
                    Column {
                        DepartRow(
                            whenLabel(departAt, timeType),
                            onWhen = { whenOpen = true },
                            order = sort.labelText(),
                            onOrder = { orderOpen = true },
                        )
                        PreciseLocationNudge()
                    }
                }
            }
            // Earlier and Later, with the first route's time between them. It stays while new routes load, so the
            // time rolls to theirs instead of the row going.
            fun androidx.compose.foundation.lazy.LazyListScope.shiftRow(time: String) = item(key = "shift") {
                KavPart("results.time.shift", time, { JSONObject().put("firstDeparture", time) }) {
                    Row(
                        Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(240),
                            fadeOutSpec = androidx.compose.animation.core.tween(150),
                            placementSpec = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f))
                            .fillMaxWidth().padding(horizontal = K.gap4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        fun nudge(delta: Long) {
                            val now = System.currentTimeMillis()
                            val base = if (departAt == 0L) now else departAt
                            val next = (base + delta).let { if (it < now + 60_000L) 0L else it }
                            selectTime(next, if (next == 0L) Transit.TIME_DEPARTURE else timeType)
                        }
                        KavChip(T("Earlier", "מוקדם יותר"), selected = false, onClick = { nudge(-15 * 60_000L) },
                            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft)
                        Spacer(Modifier.weight(1f))
                        // The time rolls up for Later and down for Earlier.
                        RollingText(
                            time,
                            style = K.type.caption.tabular,
                            order = compareBy { t -> t.filter(Char::isDigit).toIntOrNull() ?: 0 },
                        )
                        Spacer(Modifier.weight(1f))
                        KavChip(T("Later", "מאוחר יותר"), selected = false, onClick = { nudge(15 * 60_000L) },
                            trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight)
                    }
                }
            }
            // A tab for each planner under Earlier and Later, when every planner's routes show; over a planner's error
            // or empty answer too, so another can be picked.
            fun androidx.compose.foundation.lazy.LazyListScope.tabsRow() {
                if (tabs.size > 1) item(key = "planners") {
                    KavPart("results.planners", tabs to planner, { JSONObject().put("source", planner.key).put("sources", JSONArray(tabs.map { it.key })) }) {
                        PlannerTabs(tabs, planner, { picked ->
                            resultAction("results.planner.select", { JSONObject().put("source", picked.key) }) { tab = picked }
                        }, listMotion().padding(horizontal = K.gap4, vertical = K.gap2))
                    }
                }
            }
            when {
                error == TOO_CLOSE -> item(key = "too-close") {
                    NoteCard(
                        T(
                            "You're too close to your destination to plan a route.",
                            "אתם קרובים מדי ליעד כדי לתכנן מסלול.",
                        ),
                        listMotion().padding(K.gap4),
                    )
                }
                ruleFailure != null && error == null -> {
                    tabsRow()
                    item(key = "rule-failed") {
                        Column(listMotion().padding(K.gap4)) {
                            Note(ruleFailed(ruleFailure!!))
                            Text(T("Try again", "נסו שוב"), color = K.accentInk,
                                modifier = Modifier.clickable { planAgain++ }.padding(vertical = K.gap3))
                            // The one fallback, asked for by the rider: Kav's routes, said not to be checked.
                            Text(T("Show routes without this check", "הצגת המסלולים בלי הבדיקה הזו"), color = K.accentInk,
                                modifier = Modifier.clickable {
                                    ruleNote = notChecked(ruleFailure!!); raw = unchecked; ruleFailure = null
                                }.padding(vertical = K.gap3))
                        }
                    }
                }
                error != null -> {
                    tabsRow()
                    item(key = "error") {
                        Column(listMotion().padding(K.gap4)) {
                            Note(error.orEmpty())
                            Text(T("Try again", "נסו שוב"), color = K.accentInk,
                                modifier = Modifier.clickable { planAgain++ }.padding(vertical = K.gap3))
                        }
                    }
                }
                toLL == null -> item(key = "no-destination") {
                    NoteCard(
                        if (fromLL == null) T(
                            "Choose where you are starting from, and where you are going.",
                            "בחרו מהיכן אתם יוצאים ולאן אתם רוצים להגיע.",
                        )
                        else T("Where do you want to go?", "לאן תרצו להגיע?"),
                        listMotion().padding(K.gap4),
                    )
                }
                fromLL == null && !findHereFailed -> item(key = "finding") { LoadingBlock(T("Finding routes", "מחפשים מסלולים"), Modifier.animateItem(
                    fadeInSpec = androidx.compose.animation.core.tween(200, delayMillis = 60),
                    fadeOutSpec = androidx.compose.animation.core.tween(120), placementSpec = null,
                ).fillParentMaxHeight(.6f)) }
                fromLL == null -> item(key = "no-start") {
                    Column(listMotion().padding(K.gap4)) {
                        if (findHereFailed) Note(T("Couldn't get an accurate location. Try again or choose a start.",
                            "לא התקבל מיקום מדויק. נסו שוב או בחרו נקודת התחלה."))
                        else NoteCard(T("Choose a start to find routes.", "בחרו נקודת התחלה כדי למצוא מסלולים."))
                        if (findHereFailed && !findingHere) Text(T("Try again", "נסו שוב"), color = K.accentInk,
                            modifier = Modifier.clickable { hereRetry++ }.padding(vertical = K.gap3))
                    }
                }
                planning || plannedFor != planKey -> {
                    if (timeType != Transit.TIME_LAST && shiftTime.isNotEmpty()) shiftRow(loadingTime(departAt))
                    tabsRow()
                    item(key = "finding") { LoadingBlock(T("Finding routes", "מחפשים מסלולים"), Modifier.animateItem(
                        fadeInSpec = androidx.compose.animation.core.tween(200, delayMillis = 60),
                        fadeOutSpec = androidx.compose.animation.core.tween(120), placementSpec = null,
                    ).fillParentMaxHeight(.6f)) }
                }
                shown.isEmpty() -> {
                    tabsRow()
                    val hid = uk.noammm.kav.ext.Pipeline.hiddenNotice(hiddenRoutes).takeIf { raw.isEmpty() && !nothingOnShabbat }
                    // Every route found was left out by the rider's own rules: that, not "none found".
                    if (hid != null) {
                        item(key = "hidden-routes") {
                            HiddenRoutesNotice(hid.count, hid.by, hiddenReasons(hiddenRoutes), showing = showHidden,
                                modifier = listMotion().padding(K.gap4)) { showHidden = true }
                        }
                        ruleNote?.let { note -> item(key = "rules") { NoteCard(note, listMotion().padding(horizontal = K.gap4, vertical = K.gap2)) } }
                    } else item(key = "empty") {
                        NoteCard(
                            if (raw.isEmpty() && nothingOnShabbat) SHABBAT_NOTHING
                            else if (raw.isEmpty()) T("No routes found for this trip.", "לא נמצאו מסלולים לנסיעה הזו.")
                            else T("Every route found is switched off in your filters.", "כל המסלולים שנמצאו הוסתרו על ידי המסננים שלכם."),
                            listMotion().padding(K.gap4),
                        )
                    }
                }
                else -> {
                    if (timeType != Transit.TIME_LAST) shiftRow(firstTime ?: shiftTime)
                    tabsRow()
                    ruleNote?.let { note ->
                        item(key = "rules") { NoteCard(note, listMotion().padding(horizontal = K.gap4, vertical = K.gap2)) }
                    }
                    // Late at night or on Shabbat there may only be walking and cycling. Routes the planner found after
                    // Shabbat or a holiday ends say so on their own cards.
                    if (raw.none { t -> t.legs.any { it.kind == Transit.LegKind.RIDE } }) item(key = "no-transit") {
                        NoteCard(
                            if (nothingOnShabbat) SHABBAT_NOTHING
                            else T("No public transport found for this time.", "לא נמצאה תחבורה ציבורית לשעה הזו."),
                            listMotion().padding(horizontal = K.gap4, vertical = K.gap2),
                        )
                    }
                    // Israel Railways' own trains for the first route that takes the train.
                    railPair(shown, resolved)?.takeIf { sort == Sort.RECOMMENDED }?.let { rail ->
                        item(key = "rail") {
                            val scope = rememberCoroutineScope()
                            // A train journey opens as a trip of its own, like any route, in the railway's name and colours.
                            val railway = shown.firstNotNullOfOrNull { t ->
                                t.rides.firstOrNull(resolved::isTrain)?.let { resolved.line(it.lineId)?.agencyId }
                            } ?: KavPlan.agencyId(2, 2)
                            Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = androidx.compose.animation.core.tween(150), placementSpec = null)
                                .popIn(0, raw to sort)) {
                                RailCard(model.net, rail, Modifier.padding(horizontal = K.gap4)) { journey ->
                                    scope.launch {
                                        // Kav's timetable, loaded now if nothing has needed it yet.
                                        val net = model.net ?: runCatching { uk.noammm.kav.loadNet(ctx) }.getOrNull()?.also { model.net = it }
                                            ?: return@launch
                                        val (trip, r) = withContext(Dispatchers.Default) { RailTrip.of(net, journey, railway) }
                                            ?: return@launch
                                        openTrip(OpenTrip(
                                            trip, r, r.stopName(trip.legs.first().fromStop).orEmpty(),
                                            r.stopName(trip.legs.last().toStop).orEmpty(),
                                        ))
                                    }
                                }
                            }
                        }
                    }
                    // Gett is another app: a favourite goes to it as the place itself.
                    val pickupName = fromPlace?.let { model.favourites.realName(it.name, it.lat to it.lon) }
                    val dropoffName = toPlace?.let { model.favourites.realName(it.name, it.lat to it.lon) }
                    // A plain Taxi row where the plan brought no taxi card, in the taxi section's place: Gett for the
                    // search's own From and To.
                    val taxiLink = if (sort == Sort.RECOMMENDED && ResultFilter.TAXI in filters && shown.none(plan::isTaxiCard)) {
                        fromLL?.let { f -> toLL?.let { t -> gettLink(f, t, pickupName, dropoffName) } }
                    } else null
                    val taxiAt = if (taxiLink != null) taxiSlot(plan, shown) else -1
                    // Each route keeps its row through live updates, and a row that moves glides to its new place.
                    val keys = uniqueKeys(shown.map { "route:${it.guid}" })
                    items(shown.size, key = { keys[it] }) { i ->
                        val mine = raw
                        Column(
                            Modifier.animateItem(
                                fadeInSpec = null, fadeOutSpec = androidx.compose.animation.core.tween(150),
                                placementSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = 1f, stiffness = 500f,
                                    visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
                                ),
                            ).graphicsLayer { if (raw !== mine) translationY = leaving.value * 16.dp.toPx() }
                                .padding(horizontal = K.gap4),
                        ) {
                            // The taxi row and a section's header come and go with the order and the plan, opening in
                            // their place rather than pushing the card down in one jump.
                            var taxiShown by remember { mutableStateOf(taxiLink) }
                            if (taxiLink != null) taxiShown = taxiLink
                            AnimatedVisibility(
                                i == taxiAt && taxiLink != null,
                                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) +
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
                                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) +
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                            ) {
                                Column {
                                    taxiShown?.let { TaxiRow(it) }
                                    Spacer(Modifier.height(K.gap3))
                                }
                            }
                            val heading = plan.heading(shown[i])
                            var headed by remember { mutableStateOf(heading) }
                            val heads = sort == Sort.RECOMMENDED && heading.isNotBlank() && (i == 0 || plan.heading(shown[i - 1]) != heading)
                            if (heads) headed = heading
                            AnimatedVisibility(
                                heads,
                                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) +
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
                                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) +
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
                            ) { SectionHeader(sentenceCase(headed)) }
                            // The taxi card orders its ride with Gett, between the search's own From and To.
                            val taxi = shown[i].legs.firstOrNull { it.kind == Transit.LegKind.TAXI }?.takeIf { plan.isTaxiCard(shown[i]) }
                            Box(Modifier.popIn(i, raw to sort)) {
                                KavPart("results.route", shown[i], { org.json.JSONObject().put("trip", uk.noammm.kav.ext.Slots.trip(shown[i], resolved)).put("index", i) }) {
                                // Leaving at a time, a card lists no bus from before it: the rider didn't ask about those.
                                ItineraryCard(shown[i], resolved, if (timeType == Transit.TIME_DEPARTURE) departAt / 1000 else 0L, footer = resultFooter(model, taxi?.let { leg ->
                                    { OrderGett(gettLink(leg, pickupName, dropoffName), Modifier.fillMaxWidth()) }
                                }, shown[i], resolved)) {
                                    // Not before the rider's required rules have judged it.
                                    if (checking) return@ItineraryCard
                                    openTrip(OpenTrip(
                                        shown[i], resolved, fromPlace?.name ?: T("Current location", "המיקום הנוכחי"),
                                        toPlace?.name ?: T("Destination", "יעד"), stopovers = stopovers,
                                        asked = if (timeType == Transit.TIME_DEPARTURE) departAt / 1000 else 0L,
                                    ))
                                }
                                }
                            }
                        }
                    }
                    if (taxiLink != null && taxiAt == shown.size) item(key = "taxi") {
                        Column(listMotion().padding(horizontal = K.gap4)) { TaxiRow(taxiLink) }
                    }
                    // Routes the rider's extensions left out: said under the routes, with the tap that shows them once.
                    uk.noammm.kav.ext.Pipeline.hiddenNotice(hiddenRoutes)?.let { n ->
                        item(key = "hidden-routes") {
                            HiddenRoutesNotice(n.count, n.by, hiddenReasons(hiddenRoutes), showing = showHidden,
                                modifier = listMotion().padding(horizontal = K.gap4, vertical = K.gap2)) { showHidden = true }
                        }
                    }
                }
            }
        }
        ScrollEdge(resultsState.canScrollBackward)
    }


    }

    val settingFav = model.settingFavourite
    LaunchedEffect(settingFav) { if (settingFav != null) picking = "fav" }

    val rise = with(androidx.compose.ui.platform.LocalDensity.current) { 76.dp.roundToPx() }
    androidx.compose.animation.AnimatedContent(
        targetState = picking,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState != null) searchIn(rise) else searchOut(rise) },
        label = "placePicker",
    ) { which ->
        if (which != null) {
        PlacePicker(
            // "stop" adds a stop on the way, "stop:1" changes the second.
            title = when {
                which == "from" -> T("Start", "התחלה")
                which.startsWith("stop") -> T("Stop on the way", "עצירה בדרך")
                else -> T("Where to?", "לאן?")
            },
            here = here,
            allowMyLocation = which != "fav" && !which.startsWith("stop"),
            initialSetting = if (which == "fav") settingFav else null,
            onMyLocation = { at ->
                if (which == "from") { fromPlace = null; hereOrigin = at }
                else { toPlace = herePlace(at); if (fromPlace == null) hereOrigin = at }
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onPick = { p ->
                if (which.startsWith("stop:")) {
                    val i = which.substringAfter(':').toIntOrNull()
                    stopovers = stopovers.mapIndexed { j, s -> if (j == i) p.staying(s.stayMin) else s }
                    picking = null; model.placeQuery = ""
                    return@PlacePicker
                }
                if (which == "stop") {
                    stopovers = stopovers + p
                    picking = null; model.placeQuery = ""
                    return@PlacePicker
                }
                hereOrigin = null
                if (which == "from") fromPlace = p else toPlace = p
                picking = null
                if (which != "from" || toPlace != null) showResults = true
                model.placeQuery = ""
            },
            onDismiss = { picking = null; model.settingFavourite = null },
            net = model.net,
            favourites = model.favourites,
            onSaveFavourites = { model.saveFavourites(ctx, it) },
            query = model.placeQuery,
            onQuery = { model.placeQuery = it },
            onLocate = { model.locate(it.first, it.second) },
        )
        }
    }

    if (orderOpen) {
        val orders = Sort.entries.filter { it != Sort.LOWEST_CO2 || Shown.co2 }
        ChoiceSheet(
            T("Order routes by", "סדר המסלולים"), orders.map { it.labelText() }, orders.indexOf(sort),
            onPick = { i ->
                val order = orders[i]
                resultAction("results.order.select", { JSONObject().put("order", order.name.lowercase()) }) { chooseOrder(order) }
            },
            onDismiss = { orderOpen = false },
        )
    }

    if (whenOpen) WhenSheet(
        departAt = departAt,
        timeType = timeType,
        onPick = ::selectTime,
        onNow = { selectTime(0L, Transit.TIME_DEPARTURE) },
        onDismiss = { whenOpen = false },
    )
}

internal fun exactTrip(candidate: Transit.Itinerary, named: List<TripLink.Ride>): Boolean {
    val rides = candidate.rides
    if (named.isEmpty() || rides.size != named.size) return false
    return named.indices.all { i ->
        val want = named[i]
        val leg = rides[i]
        if (want.lineId !in leg.lineChoices) return@all false
        val byTrip = want.tripId != 0L &&
            (leg.tripId == want.tripId || leg.options.any { it.tripId == want.tripId })
        byTrip || kotlin.math.abs(leg.dep - want.depSec) <= 60
    }
}

internal fun sameLines(candidate: Transit.Itinerary, named: List<TripLink.Ride>): Boolean {
    val rides = candidate.rides
    if (named.isEmpty() || rides.size != named.size) return false
    return named.indices.all { i -> named[i].lineId in rides[i].lineChoices }
}

// English section names come in Title Case; Kav writes sentence case. Hebrew has no case to change.
private fun sentenceCase(name: String): String =
    if (name.any { it in 'א'..'ת' }) name else name.lowercase(java.util.Locale.US).replaceFirstChar { it.titlecase(java.util.Locale.US) }


private fun whenLabel(departAt: Long, timeType: Int): String {
    if (timeType == Transit.TIME_LAST) return T("Latest departure", "יציאה אחרונה")
    if (departAt <= 0L) return T("Depart now", "יציאה עכשיו")
    val now = java.util.Calendar.getInstance(ISRAEL)
    val then = java.util.Calendar.getInstance(ISRAEL).apply { timeInMillis = departAt }
    val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    // The time as Kav writes it everywhere ("2:15 PM"), the day in Kav's language.
    val time = clockFormat().format(java.util.Date(departAt))
    val stamp = if (sameDay) time
        else java.text.SimpleDateFormat("EEE", T.locale).apply { timeZone = ISRAEL }.format(java.util.Date(departAt)) + " " + time
    return when (timeType) {
        Transit.TIME_ARRIVAL -> T("Arrive by ", "הגעה עד ") + stamp
        else -> T("Depart ", "יציאה ") + stamp
    }
}

// The time between Earlier and Later while new routes load: the one they are asked for, now when leaving now, not the
// last list's.
internal fun loadingTime(departAt: Long, nowMs: Long = System.currentTimeMillis()): String =
    clockFormat().format(java.util.Date(if (departAt > 0L) departAt else nowMs))

// What the rider's route rules did to a search, in a line; null when they changed nothing worth saying.
// Why the hidden routes were left out, as their extensions said it, each reason once.
private fun hiddenReasons(hidden: List<uk.noammm.kav.ext.Pipeline.Hidden>): List<String> = hidden.mapNotNull { it.reason }.distinct()

private fun rulesSaid(c: uk.noammm.kav.ext.Pipeline.Checked): String? {
    val walkOnly = c.snapshot.transfer == null
    val lines = listOfNotNull(
        c.notCatchable.takeIf { it > 0 }?.let { n ->
            if (walkOnly) T.count(n, "1 route isn't catchable with your walking settings", "מסלול אחד לא אפשרי לפי הגדרות ההליכה שלכם",
                "routes aren't catchable with your walking settings", "מסלולים לא אפשריים לפי הגדרות ההליכה שלכם")
            else T.count(n, "1 route isn't catchable with your walking and transfer rules", "מסלול אחד לא אפשרי לפי כללי ההליכה וההחלפות שלכם",
                "routes aren't catchable with your walking and transfer rules", "מסלולים לא אפשריים לפי כללי ההליכה וההחלפות שלכם")
        },
        c.hidden.takeIf { it > 0 && c.hiddenRoutes.isEmpty() }?.let { n ->
            T.count(n, "1 route hidden by your extensions' rules", "מסלול אחד הוסתר לפי הכללים של התוספים שלכם",
                "routes hidden by your extensions' rules", "מסלולים הוסתרו לפי הכללים של התוספים שלכם") +
                // The reasons as the extensions gave them, each set apart as theirs, as their route tags are.
                c.reasons.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = ": ") { (by, why) ->
                    T("\u2068$why\u2069 · by \u2068$by\u2069", "\u2068$why\u2069 · מאת \u2068$by\u2069")
                }.orEmpty()
        },
        c.skipped.filter { it !in c.unsent }.takeIf { it.isNotEmpty() }?.let { s ->
            val it = s.joinToString(", ") { x -> x.label }
            if (s.size == 1) T("Shown without \u2068$it\u2069: it didn't answer in time.", "המסלולים מוצגים בלי \u2068$it\u2069, כי הוא לא ענה בזמן.")
            else T("Shown without \u2068$it\u2069: they didn't answer in time.", "המסלולים מוצגים בלי \u2068$it\u2069, כי הם לא ענו בזמן.")
        },
        // Not theirs to answer: Kav's message about these routes was too large to send.
        c.unsent.takeIf { it.isNotEmpty() }?.let { s ->
            val it = s.joinToString(", ") { x -> x.label }
            if (s.size == 1) T("Shown without \u2068$it\u2069: Kav couldn't ask it about these routes.", "המסלולים מוצגים בלי \u2068$it\u2069, כי Kav לא הצליחה לשאול אותו על המסלולים האלה.")
            else T("Shown without \u2068$it\u2069: Kav couldn't ask them about these routes.", "המסלולים מוצגים בלי \u2068$it\u2069, כי Kav לא הצליחה לשאול אותם על המסלולים האלה.")
        },
    )
    return lines.takeIf { it.isNotEmpty() }?.joinToString(". ")
}

private fun ruleName(f: uk.noammm.kav.ext.Pipeline.Failure): String = when (f.slot) {
    uk.noammm.kav.ext.Pipeline.WALK -> T("Your walking rules", "כללי ההליכה שלכם")
    uk.noammm.kav.ext.Pipeline.TRANSFER -> T("Your transfer rules", "כללי ההחלפות שלכם")
    else -> T("Your route filter", "מסנן המסלולים שלכם")
}

// A required rule that failed (PLAN.md 3.6): the search says so, and never shows routes judged without it by itself.
private fun ruleFailed(f: uk.noammm.kav.ext.Pipeline.Failure): String =
    if (f.why == "missing") T("Can't use ${ruleName(f).replaceFirstChar { it.lowercase() }} (\u2068${f.pinned.label}\u2069): the extension isn't installed or is off, so no routes are shown.",
        "אי אפשר להשתמש ב${ruleName(f)} (\u2068${f.pinned.label}\u2069): התוסף לא מותקן או כבוי, ולכן לא מוצגים מסלולים.")
    else if (f.why == "undeliverable") T("Can't use ${ruleName(f).replaceFirstChar { it.lowercase() }} (\u2068${f.pinned.label}\u2069): Kav couldn't ask it about these routes, so no routes are shown.",
        "אי אפשר להשתמש ב${ruleName(f)} (\u2068${f.pinned.label}\u2069): Kav לא הצליחה לשאול אותו על המסלולים האלה, ולכן לא מוצגים מסלולים.")
    else T("Can't use ${ruleName(f).replaceFirstChar { it.lowercase() }} (\u2068${f.pinned.label}\u2069) right now, so no routes are shown.",
        "אי אפשר להשתמש כרגע ב${ruleName(f)} (\u2068${f.pinned.label}\u2069), ולכן לא מוצגים מסלולים.")

private fun notChecked(f: uk.noammm.kav.ext.Pipeline.Failure): String =
    T("Not checked with ${ruleName(f).replaceFirstChar { it.lowercase() }} (\u2068${f.pinned.label}\u2069).", "המסלולים לא נבדקו לפי ${ruleName(f)} (\u2068${f.pinned.label}\u2069).")
