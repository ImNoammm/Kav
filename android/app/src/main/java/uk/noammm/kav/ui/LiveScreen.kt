package uk.noammm.kav.ui

import uk.noammm.kav.data.liveProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.Tab
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.shownName
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.loadNet
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.trackLocation
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VisibilityThreshold
import uk.noammm.kav.data.Net
import androidx.compose.animation.animateContentSize

private const val LOOK_REACH_KM = 3.5
// The vehicles' list under the map, which folds away to the bar's room while a card is open.
private val LIVE_LIST = 260.dp
// A vehicle's or a stop's card opening or closing is one motion: the card, the map's new frame, the other vehicles' and
// stops' fade and the list folding away all take this long, with one easing.
private const val LIVE_MOTION_MS = 450
private val liveMotion = tween<Float>(LIVE_MOTION_MS, easing = FastOutSlowInEasing)

internal fun liveSnapshotFresh(saved: Long, now: Long) = saved > 0 && now - saved in 0L..15L

// Whether the vehicles shown still stand while the map is moved: fresh, or still the server's latest, its next snapshot
// not yet 15 s overdue. Only a return after that starts again from nothing.
internal fun liveAreaCurrent(fetched: Long, next: Long, now: Long) =
    liveSnapshotFresh(fetched, now) || fetched > 0 && now in fetched..next + 15

// Whether vehicles that are no longer current stay on the map while the server is asked again: seen under two minutes
// ago, they glide from there to where they are now, as on any round. Cleared first, they went out and came in again.
// Older than that they'd slide across the map from where they were, so the map starts again from nothing.
internal fun liveAreaStays(fetched: Long, now: Long) = fetched > 0 && now - fetched in 0L..120L

// What the Live tab showed last, so opening it again shows the buses at once while they refresh.
private object LiveMemory {
    // Every bus around, as last asked.
    var area: KavLive.Area? = null
    var areaAt = 0L
    var areaCentre = 0.0 to 0.0
    var source = ""

    fun recentArea(here: Pair<Double, Double>) = area?.takeIf {
        source == KavLive.origin && liveSnapshotFresh(areaAt, System.currentTimeMillis() / 1000) && metres(here.first, here.second, areaCentre.first, areaCentre.second) < 500
    }
}

private fun busCount(n: Int) =
    if (n == 0) T("No tracked vehicles right now", "אין כרגע כלי רכב במעקב")
    else T.count(n, "1 live vehicle", "כלי רכב אחד בזמן אמת", "live vehicles", "כלי רכב בזמן אמת")

private sealed interface LiveFocus {
    data class Vehicle(val tripId: Long, val from: Int? = null) : LiveFocus
    data class Stop(val id: Int) : LiveFocus
}

// Whether what was tapped opens now. A stop, or nothing, at once. A vehicle once its road and stops are in (or at once
// from an open card) and the map has its vehicles split around it, or after waiting for them.
internal fun opensNow(vehicle: Boolean, cardOpen: Boolean, ready: Boolean, inMap: Boolean, waited: Boolean) =
    !vehicle || ((cardOpen || ready) && inMap) || waited

internal fun keptLiveVehicle(keep: Long?, chosen: Long?, last: Long?, coming: Boolean): Long? =
    keep ?: chosen ?: last.takeIf { coming }

// A vehicle's name on its plate: its line's number. A train's line has none: it goes by its run number, which the
// ministry names its vehicle by (route_first_641), as the trip pages show it. Else "..." while its line is looked up, or
// its line's id.
internal fun plateNumber(number: String?, vehicleId: String, routeType: Int, pending: Boolean, lineId: Int): String =
    number?.ifBlank { null }
        ?: vehicleId.substringAfterLast('_', "").takeIf { routeType == 2 && it.isNotEmpty() && it.all(Char::isDigit) }
        ?: if (pending) "…" else "#$lineId"

// How near the stop a vehicle was opened from is to its stop in Kav's timetable, for that stop's code.
private const val CROWD_STOP_M = 100.0

private class Tracked(
    val arrival: Transit.Arrival,
    val stop: Transit.Stop?,
    val line: Transit.LineInfo?,
    val looked: Boolean,
    val routeType: Int,
    // The line as Kav's server has it, from the ministry's own data.
    val kav: KavLive.Line? = null,
) {
    val tripId get() = arrival.tripId
    val number get() = plateNumber(line?.number, arrival.vehicleId, routeType, pending, arrival.lineId)
    val pending get() = line == null && !looked
    val eta get() = arrival.rtUtc.takeIf { it > 0 } ?: arrival.staticUtc

    // A bus's operator colour, by the ministry's id from Kav's server.
    fun operator(): Color? = busOperator(routeType, line?.agencyId ?: -1) { kav?.agency?.let(Operators::ofGtfs) }
}

@Composable
fun LiveScreen(model: KavModel) {
    androidx.compose.runtime.key(KavLive.source, KavLive.server) { LiveScreenForSource(model) }
}

@Composable
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
private fun LiveScreenForSource(model: KavModel) {
    val ctx = LocalContext.current
    val here = model.here ?: (32.0759 to 34.7745)
    val located = model.here != null
    var locGranted by remember { mutableStateOf(hasLocationPermission(ctx)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            locGranted = true
            requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        if (!locGranted && model.here == null) ask.launch(LOCATION_PERMISSIONS)
    }
    // Where you are at once, from what the phone already knows, then every fix while the map is on screen: one fix
    // every two minutes left you where you had been as you walked or rode. A trip under way follows you already.
    val tripTracks = model.activeJourney != null
    LaunchedEffect(locGranted, lifecycle, tripTracks) {
        if (!locGranted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val first = requestLocationOnce(ctx) { model.locate(it.first, it.second) }
            val follow = if (tripTracks) ({}) else trackLocation(
                ctx, onFix = { model.locate(it.latitude, it.longitude) }, onRough = { model.locate(it.latitude, it.longitude) },
            )
            try { kotlinx.coroutines.awaitCancellation() } finally { first(); follow() }
        }
    }
    var look by remember { mutableStateOf<Pair<Pair<Double, Double>, Double>?>(null) }
    var zoomedOut by remember { mutableStateOf(false) }
    var moved by remember { mutableStateOf(false) }
    // Recentred on the rider: the map goes with them as they move, until it's moved by hand.
    var withMe by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(LiveMemory.recentArea(here)) }
    var areaAt by remember { mutableLongStateOf(if (area != null) LiveMemory.areaAt else 0L) }
    var areaNext by remember { mutableLongStateOf(0L) }
    var stopBuses by remember { mutableStateOf<Map<String, KavLive.Bus>>(emptyMap()) }
    var stopLines by remember { mutableStateOf<Map<Int, KavLive.Line>>(emptyMap()) }
    var board by remember { mutableStateOf<Pair<Int, KavLive.Board>?>(null) }
    var status by remember {
        mutableStateOf(when {
            area != null -> busCount(area!!.buses.size)
            else -> T("Connecting…", "מתחברים…")
        })
    }
    var loading by remember { mutableStateOf(area == null) }
    // The last round couldn't reach the server: no vehicles then means none known, not none there.
    var unreached by remember { mutableStateOf(false) }
    var near by remember { mutableStateOf(emptyList<Transit.Stop>()) }
    var focus by remember { mutableStateOf<LiveFocus?>(null) }
    var picked by remember { mutableStateOf<Tracked?>(null) }
    var farStop by remember { mutableStateOf<Transit.Stop?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { delay(1000); now = System.currentTimeMillis() / 1000 }
        }
    }

    // Where the stops around were last looked up. A trip's tracking moves `here` every second; they're looked up again
    // once you're 300 m away.
    var stopsAt by remember { mutableStateOf(here) }
    LaunchedEffect(here) { if (metres(here.first, here.second, stopsAt.first, stopsAt.second) > 300) stopsAt = here }

    // The stops around come from the timetable, by their place in it: nothing to look up anywhere.
    LaunchedEffect(look?.first ?: stopsAt, look?.second) {
        val net = model.net ?: runCatching { loadNet(ctx) }.getOrNull()?.also { model.net = it }
            ?: return@LaunchedEffect
        val (at, reach) = look ?: (stopsAt to 1.5)
        val inView = withContext(Dispatchers.Default) {
            net.nearestStops(at.first, at.second, k = 120, radius = reach.coerceIn(0.6, LOOK_REACH_KM) * 1000)
                .map { it.first }
        }
        near = inView.map { g -> Transit.Stop(-(g + 1), net.lat[g], net.lon[g], net.name[g]) }
    }

    // Every bus in the box around where the map looks, asked again when the server has a newer snapshot.
    val dataLifecycle = (ctx as? androidx.lifecycle.LifecycleOwner)?.lifecycle ?: lifecycle
    val active = model.tab == Tab.Live
    LaunchedEffect(dataLifecycle, active, look?.first ?: stopsAt, look?.second) {
        if (!active) return@LaunchedEffect
        val (at, reach) = look ?: (stopsAt to 1.5)
        val box = KavLive.box(at.first, at.second, reach.coerceIn(0.6, LOOK_REACH_KM))
        dataLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val back = System.currentTimeMillis() / 1000
            if (!liveAreaCurrent(areaAt, areaNext, back) && !liveAreaStays(areaAt, back)) {
                area = null; stopBuses = emptyMap(); stopLines = emptyMap(); picked = null
                loading = true
                status = T("Connecting…", "מתחברים…")
            }
            while (true) {
                val wait = try {
                    val answer = withContext(Dispatchers.IO) { KavLive.area(box) }
                    area = answer; loading = false; unreached = false
                    areaAt = System.currentTimeMillis() / 1000; areaNext = answer.next
                    if (look == null) {
                        LiveMemory.area = answer; LiveMemory.areaCentre = stopsAt
                        LiveMemory.areaAt = System.currentTimeMillis() / 1000
                        LiveMemory.source = KavLive.origin
                    }
                    answer.next - System.currentTimeMillis() / 1000
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    loading = false; unreached = true
                    status = T("Could not refresh · retrying shortly", "לא ניתן היה לרענן · ננסה שוב בקרוב")
                    15L
                }
                delay(wait.coerceIn(5, 30) * 1000L)
            }
        }
    }
    // The stops in view are asked about every round. The ministry answers for them live, a minute
    // or so fresher than its snapshot of every bus, which fills in the rest.
    LaunchedEffect(dataLifecycle, active, near) {
        if (!active) return@LaunchedEffect
        val net = model.net ?: return@LaunchedEffect
        val codes = near.mapNotNull { s -> net.code.getOrNull(-s.id - 1)?.takeIf { it > 0 } }.distinct()
        if (codes.isEmpty()) return@LaunchedEffect
        dataLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val boards = withContext(Dispatchers.IO) {
                    codes.chunked(KavLive.STOPS_MAX).map { part -> async { runCatching { KavLive.stops(part) }.getOrNull() } }
                        .awaitAll().filterNotNull()
                }
                if (boards.isNotEmpty()) {
                    stopBuses = boards.flatMap { it.due }.mapNotNull { d -> d.spot?.let { KavLive.Bus(d.key, d.route, it) } }
                        .groupBy { it.key }.mapValues { (_, same) -> same.maxBy { it.spot.at } }
                    stopLines = boards.fold(emptyMap()) { all, b -> all + b.lines }
                }
                delay(15_000)
            }
        }
    }
    // The arrivals at the stop that's open, from the ministry.
    val boardStop = (focus as? LiveFocus.Stop)?.id?.takeIf { it < 0 }
    if (boardStop != null) LaunchedEffect(boardStop, lifecycle) {
        val net = model.net ?: return@LaunchedEffect
        val code = net.code.getOrElse(-boardStop - 1) { 0 }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { withContext(Dispatchers.IO) { KavLive.stops(listOf(code)) } }
                    .onSuccess { board = boardStop to it }
                delay(15_000)
            }
        }
    }

    // The bus being followed is asked about on its own, straight from the ministry, fresher than the rest.
    val followedRoute = (focus as? LiveFocus.Vehicle)?.let { f ->
        area?.buses?.firstOrNull { KavLive.tripIdOf(it.key) == f.tripId }?.route
            ?: board?.second?.due?.firstOrNull { KavLive.tripIdOf(it.key) == f.tripId }?.route
    }
    val followed = rememberKavSpots(listOfNotNull(followedRoute))

    val stopsById = remember(near, farStop) { (near + listOfNotNull(farStop)).associateBy { it.id } }
    val vehicles = remember(area, stopBuses, stopLines, followed) {
        // Each bus where it was seen last, from the snapshot or the stops in view. Nearest first: there's no stop of
        // yours to time them at.
        val known = area?.lines.orEmpty() + stopLines
        (area?.buses.orEmpty() + stopBuses.values).groupBy { it.key }.values.map { same -> same.maxBy { it.spot.at } }
            .map { bus ->
                val line = known[bus.route]
                Tracked(KavLive.fresher(KavLive.busArrival(bus), followed), null, KavLive.lineInfo(line, ""), true, line?.type ?: 3, line)
            }.sortedBy { metres(stopsAt.first, stopsAt.second, it.arrival.lat, it.arrival.lon) }
    }
    // The roads of the nearest vehicles asked ahead from Kav's server, two at a time, so one tapped opens at once with
    // its road; each is kept for the session.
    val nearRoutes = remember(vehicles) { vehicles.mapNotNull { KavLive.routeOf(it.arrival.vehicleId)?.takeIf { r -> r > 0 } }.distinct().take(24) }
    LaunchedEffect(nearRoutes) {
        if (KavLive.source.liveProvider.nearbyOnly || nearRoutes.isEmpty()) return@LaunchedEffect
        delay(1_000)
        val todo = nearRoutes.filter { KavLive.shapeNow(it).isEmpty() }
        val next = java.util.concurrent.atomic.AtomicInteger()
        kotlinx.coroutines.coroutineScope {
            repeat(2) {
                launch(Dispatchers.IO) {
                    while (true) { val r = todo.getOrNull(next.getAndIncrement()) ?: break; runCatching { KavLive.shape(r) } }
                }
            }
        }
    }
    LaunchedEffect(vehicles.size, area != null || stopBuses.isNotEmpty()) {
        if (area != null || stopBuses.isNotEmpty()) status = busCount(vehicles.size)
    }

    androidx.activity.compose.BackHandler(focus != null) {
        focus = when (val f = focus) {
            is LiveFocus.Vehicle -> f.from?.let { LiveFocus.Stop(it) }
            else -> null
        }
    }
    val focusStop = (focus as? LiveFocus.Stop)?.let { f -> stopsById[f.id] ?: farStop?.takeIf { it.id == f.id } }
    LaunchedEffect(focus) { if (focus == null) farStop = null }
    val focusVehicle = (focus as? LiveFocus.Vehicle)?.let { f ->
        vehicles.firstOrNull { it.tripId == f.tripId } ?: picked?.takeIf { it.tripId == f.tripId }
    }
    // A bus's stops are those of its trip in Kav's own timetable, looked up again as soon as what it takes is in: the
    // timetable, and the bus's line from the snapshot, the stops in view or the stop that's open.
    val tripKey = focusVehicle?.arrival?.vehicleId.orEmpty()
    val tripLine = KavLive.routeOf(tripKey)?.let { route ->
        area?.lines?.get(route) ?: stopLines[route] ?: board?.second?.lines?.get(route)
    }
    val tripNet = model.net ?: uk.noammm.kav.Loaded.net
    val lineStops by produceState(emptyList<Transit.Stop>(), tripKey, tripLine != null, tripNet != null) {
        value = emptyList()
        val a = focusVehicle?.arrival ?: return@produceState
        val first = KavLive.firstOf(tripKey) ?: return@produceState
        val net = tripNet ?: return@produceState
        val line = tripLine ?: return@produceState
        value = withContext(Dispatchers.Default) {
            KavLive.tripStops(net, line.number, line.name, first, a.lat, a.lon, line.type)
                .map { g -> Transit.Stop(-(g + 1), net.lat[g], net.lon[g], net.name[g]) }
        }
    }
    // The bus's stops for a report: from Kav's timetable, with the codes on their signs.
    val reportStops = remember(lineStops, tripNet) {
        lineStops.map { s ->
            ReportStop(s.id, s.name, if (s.id < 0) tripNet?.code?.getOrNull(-s.id - 1)?.takeIf { it > 0 }?.toString() else null)
        }
    }
    // The road comes from the ministry's timetable, by the bus's route.
    val route = rememberKavShape(focusVehicle?.arrival?.vehicleId?.let(KavLive::routeOf) ?: -1)
    // How far along its stops the focused bus is, for the rail on its card.
    val focusBus = focusVehicle?.arrival?.takeIf { it.hasLocation }
    // Where its stops are along its road, worked out once and off the main thread: on a long road, for each stop again
    // at each of the bus's moves, it would take the frames its card opens with.
    val placing by produceState<StopsOnRoute?>(null, route, lineStops) {
        value = if (route.size < 2 || lineStops.isEmpty()) null else withContext(Dispatchers.Default) { StopsOnRoute(route, lineStops) }
    }
    val onRoad = placing?.of(route, lineStops)
    val progress = remember(lineStops, route, onRoad, focusBus?.lat, focusBus?.lon) { busProgress(lineStops, route, focusBus, onRoad) }
    // How busy the bus usually is (GoogleCrowd), as the trip cards have it: at the stop it was opened from, else at the
    // stop ahead of it, by the code on that stop's sign in Kav's timetable.
    val fromStop = (focus as? LiveFocus.Vehicle)?.from?.let { stopsById[it] }
    val ahead = if (progress >= 0f) progress.toInt() else -1
    val crowdAt = remember(lineStops, tripNet, fromStop, ahead) {
        val net = tripNet ?: return@remember null
        val mine = fromStop?.let { f ->
            lineStops.indices.minByOrNull { metres(f.lat, f.lon, lineStops[it].lat, lineStops[it].lon) }
                ?.takeIf { metres(f.lat, f.lon, lineStops[it].lat, lineStops[it].lon) <= CROWD_STOP_M }
        }
        val i = mine ?: ahead.takeIf { it in lineStops.indices } ?: return@remember null
        val s = lineStops[i]
        // Toward the line's end, as Lines asks: between two stops next to each other Google gives a walk, which names no
        // stop.
        val toward = lineStops.lastOrNull()?.takeIf { i < lineStops.lastIndex } ?: lineStops.getOrNull(i - 1) ?: return@remember null
        val code = net.code.getOrNull(-s.id - 1)?.takeIf { it > 0 } ?: return@remember null
        uk.noammm.kav.data.GoogleCrowd.Stop("$code", s.lat to s.lon, toward.lat to toward.lon) to (mine != null)
    }
    if (uk.noammm.kav.data.GoogleCrowd.enabled && crowdAt != null) LaunchedEffect(crowdAt.first.code) {
        while (true) {
            uk.noammm.kav.data.GoogleCrowd.refreshStops(listOf(crowdAt.first))
            delay(60_000)
        }
    }
    val crowd = crowdAt?.let { (stop, opened) ->
        val v = focusVehicle ?: return@let null
        // At the stop it was opened from, when it's due there; at the stop ahead, about now.
        val at = if (opened && v.arrival.stopId == fromStop?.id) v.eta else System.currentTimeMillis() / 1000
        uk.noammm.kav.data.GoogleCrowd.displayed(uk.noammm.kav.data.GoogleCrowd.levelOf(stop.code, v.number, at), v.routeType == 2)
    }
    // A vehicle tapped on the map opens once its road and stops are in and placed (0.7 s at most), so its card, the
    // map's frame and the fades start together from what they end on; another one while a card is open, or a stop, at once.
    var waited by remember(focus) { mutableStateOf(focus !is LiveFocus.Vehicle) }
    LaunchedEffect(focus) { if (!waited) { delay(700); waited = true } }
    var open by remember { mutableStateOf(focus) }
    val ready = route.isNotEmpty() && lineStops.isNotEmpty() && (onRoad != null || route.size < 2)
    // The vehicle the map's vehicles are split around (the one kept, the rest going with the fade), as the map has it.
    // It waits for the tapped one, or that one would fade out with the rest and come back at once, and the one picked
    // last would stay until it went at once, as if it jumped to the new one.
    var mapKept by remember { mutableStateOf<Long?>(null) }
    val inMap = (focus as? LiveFocus.Vehicle)?.tripId == mapKept
    if (open != focus && opensNow(focus is LiveFocus.Vehicle, open != null, ready, inMap, waited)) {
        open = focus
    }
    val openStop = (open as? LiveFocus.Stop)?.let { f -> stopsById[f.id] ?: farStop?.takeIf { it.id == f.id } }
    val openVehicle = if (open is LiveFocus.Vehicle) focusVehicle else null
    // The map's frame for where the motion ends, with the list folded away or back, so the camera makes one move.
    var settle by remember { mutableStateOf<MapSettle?>(null) }
    var wasOpen by remember { mutableStateOf(open != null) }
    if ((open != null) != wasOpen) {
        wasOpen = open != null
        settle = MapSettle((settle?.id ?: 0) + 1, if (open != null) LIVE_LIST - K.gap2 else K.gap2 - LIVE_LIST)
    }
    LaunchedEffect(settle) { if (settle != null) { delay(LIVE_MOTION_MS + 100L); settle = null } }
    // The card is its solid shade while it comes or goes, and its glass fades back once it has settled: blurring the
    // moving map under it at each frame of the move was most of the GPU's work then. Opening, it starts solid, as it
    // fades in anyway; closing, its glass fades out first.
    val cardGlass = animateFloatAsState(
        if (settle != null) 0f else 1f,
        when { settle == null -> tween(300); open != null -> snap(); else -> tween(150) },
        label = "cardGlass",
    )
    val liveLiquid = rememberLiquidBackdrop()
    val density = LocalDensity.current
    var cardHeight by remember { mutableStateOf(0.dp) }
    Column(Modifier.fillMaxSize()) {
        // Where the live data comes from, under an info button instead of on every visit.
        var about by rememberSaveable { mutableStateOf(false) }
        ScreenHeader(T("Live", "בזמן אמת")) {
            KavIconButton(Icons.Rounded.Info, T("Where live data comes from", "מאיפה המידע בזמן אמת"), { about = !about },
                floating = true, tint = androidx.compose.animation.animateColorAsState(if (about) K.accentInk else K.textSecondary, tween(200), label = "aboutTint").value)
        }
        androidx.compose.animation.AnimatedVisibility(
            about,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(tween(220, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
        Row(
            Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap3).fillMaxWidth()
                .clip(RoundedCornerShape(K.rTile)).background(K.fill).padding(K.gap3),
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
        LiveGlyph(K.realtime, 16.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        // Where they come from: the live locations' choice in Settings.
        Text(
            when {
                KavLive.source.liveProvider.nearbyOnly -> T(
                    "Buses and times come from ${sourceName(KavLive.source)}'s service (HopOn), about every 20s. " +
                        "The map shows vehicles serving the stops around you, not every vehicle in the area.",
                    "האוטובוסים והזמנים מגיעים מהשירות של ${sourceName(KavLive.source)} (הופאון), בערך כל 20 שניות. " +
                        "המפה מציגה כלי רכב המשרתים את התחנות סביבכם, ולא את כל כלי הרכב באזור.",
                )
                KavLive.own -> T(
                    "Buses and times come straight from the Ministry of Transport through your server, about every 15s. " +
                        "A vehicle shows here once one of the stops around you has a bus reporting its position.",
                    "האוטובוסים והזמנים מגיעים ישירות ממשרד התחבורה דרך השרת שלכם, בערך כל 15 שניות. " +
                        "כלי רכב מופיע כאן ברגע שאחת התחנות סביבכם מקבלת דיווח מיקום מאוטובוס.",
                )
                else -> T(
                    "Buses and times come straight from the Ministry of Transport through Kav's server, about every 15s. " +
                        "A vehicle shows here once one of the stops around you has a bus reporting its position.",
                    "האוטובוסים והזמנים מגיעים ישירות ממשרד התחבורה דרך השרת של Kav, בערך כל 15 שניות. " +
                        "כלי רכב מופיע כאן ברגע שאחת התחנות סביבכם מקבלת דיווח מיקום מאוטובוס.",
                )
            },
            style = K.type.subhead,
        )
        FreshnessKey()
        }
        }
        }
        // What the map shows: words, one control, in reach above the map.
        val shows = listOf(LiveShow.ALL, LiveShow.TRAFFIC, LiveShow.STOPS)
        KavSegmented(
            listOf(T("All", "הכול"), T("Vehicles", "כלי רכב"), T("Stops", "תחנות")),
            selected = shows.indexOf(model.liveShow),
            onSelect = { model.liveShow = shows[it] },
            modifier = Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap3),
        )
        // Once you're found the row folds away and the map grows into its place.
        androidx.compose.animation.AnimatedVisibility(
            !located,
            enter = androidx.compose.animation.fadeIn(tween(220, delayMillis = 60)) +
                androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top),
            exit = androidx.compose.animation.fadeOut(tween(150)) +
                androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top),
        ) {
        Row(
            Modifier.padding(horizontal = K.gap4).padding(bottom = K.gap2),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KavButton(T("Use my location", "השתמשו במיקום שלי"), {
                if (hasLocationPermission(ctx)) { locGranted = true; requestLocationOnce(ctx) { model.locate(it.first, it.second) } }
                else ask.launch(LOCATION_PERMISSIONS)
            }, style = ButtonStyle.Secondary, icon = Icons.Rounded.MyLocation)
            Text(T("Showing central Tel Aviv", "מוצג מרכז תל אביב"), style = K.type.caption)
        }
        }
        Box(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth().weight(1f).panel(K.rCard),
        ) {
            CompositionLocalProvider(LocalLiquidBackdrop provides liveLiquid) {
                LiveMap(
                    here, model.heading, near, vehicles, open, openStop, openVehicle, lineStops, route, onRoad, model.liveShow,
                    keep = (focus as? LiveFocus.Vehicle)?.tripId,
                    onKept = { mapKept = it },
                    following = !moved,
                    withMe = withMe,
                    onLook = { centre, reach ->
                        moved = centre != null
                        zoomedOut = centre != null && reach > LOOK_REACH_KM
                        // Recentred (no centre), the map goes with the rider; moved by hand, it stays.
                        withMe = centre == null
                        val was = look
                        when {
                            centre == null -> look = null
                            zoomedOut -> Unit
                            // A nudge isn't somewhere new: the stops are looked up again past 150 m or a real zoom.
                            was == null || metres(was.first.first, was.first.second, centre.first, centre.second) > 150 ||
                                abs(reach - was.second) > was.second * .25 -> look = centre to reach
                        }
                    },
                    // Read by the map alone, so the card's height easing frame by frame redraws the map's frame, not this
                    // whole screen with it.
                    bottomPadding = { if (open != null) cardHeight + K.gap2 else 0.dp },
                    settle = settle,
                    modifier = Modifier.fillMaxSize().glassBackdrop(liveLiquid),
                    onVehicle = { focus = LiveFocus.Vehicle(it.tripId) },
                    onStop = { s -> if (s.id !in stopsById) farStop = s; focus = LiveFocus.Stop(s.id) },
                    status = {
                        Column(Modifier.panel(K.rTile).padding(6.dp)) {
                            // One message fades into the next as the chip eases to its size; a count in the same one rolls.
                            val said = when {
                                zoomedOut && model.liveShow == LiveShow.TRAFFIC ->
                                    1 to T("Zoom in to see the vehicles here", "התקרבו כדי לראות את כלי הרכב כאן")
                                zoomedOut -> 2 to T("Zoom in to load stops and vehicles here", "התקרבו כדי לטעון כאן תחנות וכלי רכב")
                                else -> 3 to status
                            }
                            androidx.compose.animation.AnimatedContent(
                                said, contentKey = { it.first },
                                transitionSpec = {
                                    (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                                        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                            androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)
                                        })
                                },
                                label = "liveStatus",
                            ) { (_, text) -> LiveText(text, style = K.type.caption.tabular) }
                            if (KavLive.source.liveProvider.nearbyOnly) Text(T("Vehicles serving nearby stops", "כלי רכב המשרתים תחנות סמוכות"), style = K.type.caption, color = K.textSecondary)
                        }
                    },
                    controls = {},
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = open != null,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = androidx.compose.animation.slideInVertically(tween(LIVE_MOTION_MS, easing = FastOutSlowInEasing)) { it } +
                        fadeIn(tween(200)),
                    exit = androidx.compose.animation.slideOutVertically(tween(LIVE_MOTION_MS, easing = FastOutSlowInEasing)) { it } +
                        fadeOut(tween(200, delayMillis = LIVE_MOTION_MS - 200)),
                ) {
                    var shown by remember { mutableStateOf(open) }
                    if (open != null) shown = open
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(start = K.gap2, end = K.gap2, bottom = K.gap2)
                            .onSizeChanged { cardHeight = with(density) { it.height.toDp() } }
                            .glassSurface(K.rCard, glass = { cardGlass.value }),
                    ) {
                        // Another stop, or a line picked on the card: the card fades to it and springs to its size;
                        // what loads into a card after that grows into it the same way.
                        AnimatedContent(
                            shown,
                            transitionSpec = {
                                (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                                    .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                            },
                            contentKey = { f ->
                                when (f) {
                                    is LiveFocus.Stop -> "stop ${f.id}"
                                    is LiveFocus.Vehicle -> "vehicle ${f.tripId}"
                                    else -> "none"
                                }
                            },
                            label = "liveCard",
                        ) { f ->
                            Box(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
                                when (f) {
                                    is LiveFocus.Stop -> {
                                        // The ministry's arrivals at the stop, in the shape the card takes.
                                        val b = board?.takeIf { it.first == f.id }?.second
                                        val due = remember(b) {
                                            b?.due.orEmpty().associate { d -> Transit.ArrivalKey(f.id, KavLive.tripIdOf(d.key)) to KavLive.dueArrival(d, f.id) }
                                        }
                                        val dueLines = remember(b) {
                                            b?.due.orEmpty().associate { d -> -d.route to KavLive.lineInfo(b?.lines?.get(d.route), d.number) }
                                        }
                                        val kinds = remember(b) {
                                            (b?.lines?.values.orEmpty().map { it.type } + 3).associateBy { KavLive.agencyOf(it) }
                                        }
                                        LiveStopCard(
                                            stopsById[f.id], f.id, KavLive.fresher(due, followed), dueLines, kinds, now,
                                            onVehicle = { v -> picked = v; focus = LiveFocus.Vehicle(v.tripId, from = f.id) },
                                            onClose = { focus = null },
                                        )
                                    }
                                    is LiveFocus.Vehicle -> LiveVehicleCard(
                                        focusVehicle?.takeIf { it.tripId == f.tripId }, now, lineStops, progress, reportStops, crowd,
                                        onReport = { model.report = it },
                                    ) { focus = f.from?.let { LiveFocus.Stop(it) } }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
        val below = when {
            open != null -> 0
            loading -> 1
            vehicles.isEmpty() -> 2
            else -> 3
        }
        // Each side of the fade keeps its own: the empty note fades out as the list fades in, and back.
        var listed by remember { mutableStateOf(vehicles) }
        if (vehicles.isNotEmpty()) listed = vehicles
        // While a card is open the list stays made, folded away under it and faded out, not touched or read out: made
        // again as the card closes, its rows would take the close's first frames (50 to 70 ms on a phone).
        var kept by remember { mutableIntStateOf(if (below == 0) 3 else below) }
        if (below != 0) kept = below
        val inset = LocalBottomBarInset.current
        val folded = below == 0
        // Its height is read as it's laid out: read here, it made this whole screen again at each frame of the fold.
        val tall = androidx.compose.animation.core.animateDpAsState(
            if (folded) inset + K.gap2 else LIVE_LIST + inset, tween(LIVE_MOTION_MS, easing = FastOutSlowInEasing), label = "live-below-height",
        )
        val shown by animateFloatAsState(if (folded) 0f else 1f, if (folded) tween(140) else tween(200, delayMillis = 90), label = "live-below-fade")
        Box(
            Modifier.fillMaxWidth()
                .layout { measurable, constraints ->
                    val h = constraints.constrainHeight(tall.value.roundToPx())
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .clipToBounds()
                .then(if (folded) Modifier.clearAndSetSemantics {}.pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                } else Modifier)
                .graphicsLayer { alpha = shown },
        ) {
        AnimatedContent(
            targetState = kept,
            transitionSpec = { fadeIn(tween(200, delayMillis = 90)) togetherWith fadeOut(tween(140)) },
            label = "live-below",
        ) { state ->
            when (state) {
                // One height whatever is below, so the map above never resizes with the list.
                1 -> Box(
                    Modifier.fillMaxWidth().height(LIVE_LIST + LocalBottomBarInset.current)
                        .padding(bottom = LocalBottomBarInset.current),
                    contentAlignment = Alignment.Center,
                ) { LoadingPulse(T("Finding vehicles", "מאתרים כלי רכב")) }
                else -> LiveList(if (state == 2) emptyList() else listed, now, stopsAt, unreached, Modifier.fillMaxWidth()
                    .height(LIVE_LIST + LocalBottomBarInset.current).padding(K.gap2)) {
                    focus = LiveFocus.Vehicle(it.tripId)
                }
            }
        }
        }
    }
}

// Where each stop of a line is along its road, and the road's place by it, worked out once for the road and stops.
private class StopsOnRoute(val route: List<Pair<Double, Double>>, val stops: List<Transit.Stop>) {
    val along = DoubleArray(stops.size) { alongRoute(stops[it].lat to stops[it].lon, route) }
    val points = List(stops.size) { pointOnRoute(route, along[it]) }
    fun of(route: List<Pair<Double, Double>>, stops: List<Transit.Stop>) = takeIf { it.route === route && it.stops === stops }
}

private class LineLook(
    val tripId: Long,
    val tint: Color,
    val ahead: List<Pair<Double, Double>>,
    val driven: List<Pair<Double, Double>>,
    val stops: List<Pair<Pair<Double, Double>, Boolean>>,
    val from: Pair<Double, Double>?,
)

private fun lineLook(
    tripId: Long, route: List<Pair<Double, Double>>, stops: List<Transit.Stop>, tint: Color,
    bus: Transit.Arrival?, from: Transit.Stop?, onRoad: StopsOnRoute?,
): LineLook {
    val shaped = route.size >= 2
    // The road measured once (alongRoute), the bus placed on it once, and the stops as placed already (onRoad).
    val busAlong = if (bus != null && shaped) alongRoute(bus.lat to bus.lon, route) else -1.0
    val (driven, ahead) = if (bus != null && shaped) splitRoute(route, busAlong)
        else emptyList<Pair<Double, Double>>() to route
    val placed = stops.mapIndexed { i, st ->
        if (!shaped) return@mapIndexed (st.lat to st.lon) to false
        val along = onRoad?.along?.get(i) ?: alongRoute(st.lat to st.lon, route)
        (onRoad?.points?.get(i) ?: pointOnRoute(route, along)) to (busAlong >= 0 && along < busAlong)
    }
    return LineLook(tripId, tint, ahead, driven, placed, from?.let { onRoute(it.lat to it.lon, route) })
}

@Composable
private fun LiveMap(
    center: Pair<Double, Double>,
    heading: Float?,
    stops: List<Transit.Stop>,
    vehicles: List<Tracked>,
    focus: LiveFocus?,
    focusStop: Transit.Stop?,
    focusVehicle: Tracked?,
    lineStops: List<Transit.Stop>,
    route: List<Pair<Double, Double>>,
    onRoad: StopsOnRoute?,
    show: LiveShow,
    // The vehicle tapped, kept in from the tap on while the rest wait to fade, and told once the map has it so.
    keep: Long?,
    onKept: (Long?) -> Unit,
    following: Boolean,
    withMe: Boolean,
    onLook: (Pair<Double, Double>?, Double) -> Unit,
    bottomPadding: () -> Dp,
    settle: MapSettle?,
    modifier: Modifier,
    onVehicle: (Tracked) -> Unit,
    onStop: (Transit.Stop) -> Unit,
    status: @Composable () -> Unit,
    controls: @Composable () -> Unit,
) {
    val reach = with(LocalDensity.current) { 22.dp.toPx() }
    val stopReach = with(LocalDensity.current) { 20.dp.toPx() }
    val from = (focus as? LiveFocus.Vehicle)?.from?.let { id ->
        stops.firstOrNull { it.id == id } ?: lineStops.firstOrNull { it.id == id }
    }
    val spot = (center.first * 300).roundToInt() to (center.second * 300).roundToInt()
    // Once moved by hand the map keeps its key, so nothing reframes it until Reset.
    val pinned = remember { arrayOf(spot) }
    if (following) pinned[0] = spot
    val where = pinned[0]
    val points = remember(focus, route.isNotEmpty(), lineStops.isNotEmpty(), stops.isEmpty(), following, where, show) {
        when {
            focusVehicle != null -> listOfNotNull(focusVehicle.arrival.takeIf { it.hasLocation }?.let { it.lat to it.lon }) +
                listOfNotNull(from?.let { it.lat to it.lon }) + route.ifEmpty { lineStops.map { it.lat to it.lon } }
            focusStop != null && show == LiveShow.STOPS -> listOf(focusStop.lat to focusStop.lon)
            focusStop != null -> listOf(focusStop.lat to focusStop.lon) + vehicles
                .filter { it.arrival.stopId == focusStop.id && it.arrival.hasLocation }
                .map { it.arrival.lat to it.arrival.lon }
            following -> stops.map { it.lat to it.lon } + center
            else -> stops.map { it.lat to it.lon }
        }
    }
    val chosen = focusVehicle?.tripId
    // Stops only still draws a vehicle picked from the list.
    val shown = if (show == LiveShow.STOPS) vehicles.filter { it.tripId == chosen } else vehicles
    // What All, Vehicles and Stops leave out fades (TileMap's outStops and outVehicles); only what's shown is tapped.
    // The stops stay sent while hidden (a circle layer at no opacity isn't drawn), so they fade in from the first frame
    // instead of a few frames late; the vehicles are drawn until they're gone and back from where they were.
    val stopsShown = animateFloatAsState(if (show != LiveShow.TRAFFIC) 1f else 0f, tween(240), label = "liveStops")
    val othersShown = animateFloatAsState(if (show != LiveShow.STOPS) 1f else 0f, tween(240), label = "liveVehicles")
    val othersDrawn by remember { derivedStateOf { othersShown.value > 0f } }
    val drawnVehicles = if (othersDrawn) vehicles else shown
    val tint = focusVehicle?.let { v ->
        plateFor(v.routeType, v.line?.agencyId ?: -1)?.fill ?: v.operator()?.takeIf(::hued)?.let { onScreen(it, MapInk.light) } ?: MapInk.route
    }
    val bus = focusVehicle?.arrival?.takeIf { it.hasLocation }
    val current = remember(chosen, route, lineStops, onRoad, tint, bus, from) {
        if (chosen == null || tint == null) null else lineLook(chosen, route, lineStops, tint, bus, from, onRoad?.of(route, lineStops))
    }
    var last by remember { mutableStateOf<LineLook?>(null) }
    SideEffect { if (current != null) last = current }
    val look = current ?: last
    val lineShown = animateFloatAsState(if (chosen != null) 1f else 0f, liveMotion, label = "lineView")
    // The fade itself is the map's (TileMap focus, read at each frame with the camera's move); what's drawn only
    // changes where it starts and ends: the picked vehicle's road and stops while it's coming or there. What goes
    // stays sent, its layers not drawn while hidden, so it's there at once when it comes back.
    val coming by remember { derivedStateOf { lineShown.value > 0f } }
    val geometry = remember(stops, center, MapInk.bg, MapInk.muted, MapInk.routeIdle, MapInk.accent, look, coming, focusStop) {
        val lines = ArrayList<MapLine>()
        val dots = ArrayList<MapDot>()
        stops.forEach { st ->
            dots += MapDot(st.lat, st.lon, MapInk.bg, 6f, fade = FOCUS_OUT)
            dots += MapDot(st.lat, st.lon, Color.Transparent, 4.2f, MapInk.muted, 1.6f, fade = FOCUS_OUT)
        }
        if (look != null && coming) {
            look.ahead.takeIf { it.size >= 2 }?.let { lines += MapLine(it, look.tint, 5f, casing = 9f) }
            look.driven.takeIf { it.size >= 2 }?.let { lines += MapLine(it, MapInk.routeIdle, 5f, casing = 9f) }
            look.stops.forEach { (at, passed) ->
                dots += MapDot(at.first, at.second, MapInk.bg, 6f, fade = FOCUS_IN)
                dots += MapDot(at.first, at.second, Color.Transparent, 3.5f, if (passed) MapInk.routeIdle else look.tint, 2f, fade = FOCUS_IN)
            }
            look.from?.let { f -> dots += boardingDots(f, look.tint).map { it.copy(fade = FOCUS_IN) } }
        }
        focusStop?.let {
            dots += MapDot(it.lat, it.lon, MapInk.bg, 10f)
            dots += MapDot(it.lat, it.lon, Color.Transparent, 6.5f, MapInk.accent, 2.6f)
        }
        MapGeometry(lines = lines, dots = dots)
    }
    // Rebuild when positions, selection or displayed colours change; ticking ages alone need no source upload.
    val density = LocalDensity.current
    val lookTrip = look?.tripId
    val second = System.currentTimeMillis() / 1000
    val kept = keptLiveVehicle(keep, chosen, lookTrip, coming)
    val freshness = remember(drawnVehicles, second) {
        drawnVehicles.map { if (it.arrival.vehicleStatus == 2) Freshness.ESTIMATED else freshnessOf(it.arrival.sampleUtc, second) }
    }
    // Keep the first distance for each bus: live updates and glides must never swap which overlapping bus is on top.
    val distances = remember { HashMap<Long, Double>() }
    // Keyed on the vehicles drawn, not on the switch: sent again as the fade began, the map placed their marks anew and
    // most of them blinked out instead of fading.
    val moving = remember(drawnVehicles, kept, density, freshness, MapInk.icons) {
        val ordered = drawnVehicles.sortedWith(compareByDescending<Tracked> { v ->
            distances.getOrPut(v.tripId) { metres(center.first, center.second, v.arrival.lat, v.arrival.lon) }
        }.thenBy { it.tripId })
        val g = vehicleGeometry(ordered.map { it.arrival to modeOf(it.routeType) }, density, fade = { a -> if (a.tripId == kept) 0 else FOCUS_OUT })
        val images = HashMap<String, androidx.compose.ui.graphics.ImageBitmap>()
        // Placement matches by image key and position. Neighbouring buses must not borrow each other's identity. The look
        // is in the name too: the map kept drawing an image added again under the same name, so a bus whose report went
        // fresh kept its old colour under a glow of the new one.
        val markers = g.markers.map { m ->
            val name = "kav-live-${m.id}~${m.icon}"
            images[name] = g.images.getValue(m.icon)
            m.copy(icon = name)
        }
        g.copy(markers = markers, images = images)
    }
    // The rider as navigation draws them, an arrow the way the phone faces, gliding from fix to fix as the vehicles do.
    val me = remember(center, heading != null, density, K.look, MapInk.icons) {
        riderArrowGeometry(center, heading?.let { 0f }, density)
    }
    val live = remember(moving, me) {
        // The rider's halo the vehicles' size here, so all the halos pulse by one plain radius (TileMap): a halo of
        // another size made the map lay every halo out again at each step of the pulse, thirty times a second. Always
        // that size, vehicles shown or not: taken from the first vehicle's, it shrank when Stops left none.
        val meHalos = me.halos.map { it.copy(radius = VEHICLE_HALO_R) }
        moving.copy(halos = moving.halos + meHalos, images = moving.images + me.images, together = true)
    }
    TileMap(
        points, modifier, focusKey = focus ?: where,
        recenterOn = center,
        contentPadding = PaddingValues(bottom = bottomPadding()),
        settle = settle,
        // The list the card collapses, room the map grows into without being resized.
        reserve = if (focus == null) LIVE_LIST - K.gap2 else 0.dp,
        moveMs = LIVE_MOTION_MS,
        geometry = geometry,
        focus = { lineShown.value },
        outStops = { stopsShown.value },
        outVehicles = { othersShown.value },
        onLook = onLook,
        moved = !following,
        live = live,
        rider = me.markers.firstOrNull(),
        riderHeading = heading,
        onLiveTaken = { g -> if (g === live) onKept(kept) },
        followRider = withMe && following && focus == null,
        onTap = { at, proj ->
            val tappable = if (chosen == null) shown else shown.filter { it.tripId == chosen }
            val vehicle = tappable.map { it to (proj.point(it.arrival.lat, it.arrival.lon) - at).getDistance() }
                .filter { it.second <= reach }.minByOrNull { it.second }?.first
            if (vehicle != null) onVehicle(vehicle)
            else (if (chosen != null) lineStops else if (show == LiveShow.TRAFFIC) emptyList() else stops)
                .map { it to (proj.point(it.lat, it.lon) - at).getDistance() }
                .filter { it.second <= stopReach }.minByOrNull { it.second }?.first
                ?.let(onStop)
        },
        keepZoom = show == LiveShow.STOPS && focusStop != null,
        status = status,
        controls = controls,
    )
}

enum class LiveShow { STOPS, TRAFFIC, ALL }

@Composable
private fun LinePlate(v: Tracked) {
    val agency = v.line?.agencyId ?: -1
    val plate = plateFor(v.routeType, agency) ?: operatorPlate(v.operator())
    // The strip look for buses, when chosen in Settings.
    BadgeLookSwitch(K.lineBadgeAppearance == LineBadgeAppearance.STRIP && plateFor(v.routeType, agency) == null) { strip ->
        if (strip) {
            LinePlateBadge(v.number, Mode.BUS, plate, Modifier.widthIn(max = 120.dp), glyph = { ink, size -> AgencyMark(v.routeType, agency, ink, size) },
                look = LineBadgeAppearance.STRIP)
        } else Row(
            Modifier.clip(RoundedCornerShape(K.rBadge)).background(plate?.fill ?: K.plate)
                .border(1.dp, plate?.edge ?: K.borderStrong, RoundedCornerShape(K.rBadge))
                .padding(start = 6.dp, end = 8.dp, top = 3.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgencyMark(v.routeType, agency, plate?.ink ?: K.muted, 15.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                v.number, fontSize = 16.sp, color = plate?.ink ?: K.text, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 96.dp),
            )
        }
    }
}

private fun stopName(name: String?) = name?.ifBlank { null }?.let(::shownName) ?: "…"

@Composable
private fun LiveList(
    vehicles: List<Tracked>, now: Long, from: Pair<Double, Double>, unreached: Boolean, modifier: Modifier, onSelect: (Tracked) -> Unit,
) {
    if (vehicles.isEmpty()) {
        // Nothing near: a quiet line in the middle of the space, the why behind the (i) at the top.
        Column(
            modifier.fillMaxWidth().padding(K.gap6),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            LiveGlyph(K.textTertiary, 28.dp)
            ChangingText(
                if (unreached) T("Live locations can't be reached right now", "אין כרגע גישה למיקומים בזמן אמת")
                else T("No vehicles nearby right now", "אין כרגע כלי רכב בסביבה"),
            ) { Text(it, style = K.type.body, color = K.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        return
    }
    // The freshest positions first: green, then yellow, then red, each kept in its own order.
    val sorted = vehicles.sortedBy { freshnessOf(it.arrival.sampleUtc, now).ordinal }
    // The list keeps its place as vehicles change order. Kept by vehicle, it would follow the first one down the list
    // as its position aged, and scroll on by itself.
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val order = sorted.map { it.tripId }
    val shownOrder = remember { arrayOfNulls<List<Long>>(1) }
    SideEffect {
        if (shownOrder[0] != order && !state.isScrollInProgress) {
            state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        }
        shownOrder[0] = order
    }
    LazyColumn(modifier, state = state, contentPadding = PaddingValues(
        top = K.gap1, bottom = K.gap4 + LocalBottomBarInset.current,
    )) {
        itemsIndexed(sorted, key = { _, it -> it.tripId }) { i, v ->
            val a = v.arrival
            val ageS = (now - a.sampleUtc).coerceAtLeast(0)
            val tint by androidx.compose.animation.animateColorAsState(
                if (a.vehicleStatus == 2) K.problem else freshColour(freshnessOf(a.sampleUtc, now)), tween(300), label = "fresh",
            )
            // A vehicle that comes into the list fades in, one that leaves fades out, and the rest glide to their places.
            // Its counters roll every second, and that redraw stays in the row rather than the whole tab and its glass.
            Row(
                Modifier.animateItem(
                    fadeInSpec = tween(220, delayMillis = 60),
                    placementSpec = spring(
                        dampingRatio = 1f, stiffness = 500f,
                        visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
                    ),
                    fadeOutSpec = tween(150),
                ).ownDamage().fillMaxWidth().groupItem(i == 0, i == sorted.lastIndex)
                    .clickable(role = Role.Button) { onSelect(v) }
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(K.gap3),
            ) {
                LinePlate(v)
                Column(Modifier.weight(1f)) {
                    Text(
                        v.line?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "אל ${shownName(it)}") }
                            ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                        style = K.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // Where it is rolls to the new distance or time at each refresh.
                    LiveText(
                        if (a.stopId < 0 && v.stop == null) {
                            val far = distanceLabel(metres(from.first, from.second, a.lat, a.lon))
                            T("$far from you", "$far ממך")
                        } else "${stopName(v.stop?.name)} · ${whenLabel(v.eta, now)}",
                        style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap1)) {
                    LiveGlyph(tint, 12.dp)
                    TickText(T("${ageS}s ago", "לפני ${ageS} שנ׳"), K.type.caption.tabular, K.textSecondary, said = ageSaid(ageS))
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun CardClose(onClose: () -> Unit) {
    KavIconButton(Icons.Rounded.Close, T("Close", "סגירה"), onClose, size = 36.dp)
}

@Composable
private fun LiveVehicleCard(
    v: Tracked?, now: Long, stops: List<Transit.Stop>, progress: Float, reportStops: List<ReportStop>, crowd: uk.noammm.kav.data.GoogleCrowd.Crowd?,
    onReport: (ReportFor) -> Unit, onClose: () -> Unit,
) {
    var last by remember { mutableStateOf(v) }
    if (v != null) last = v
    val shown = v ?: last
    Column(Modifier.fillMaxWidth().padding(K.gap4)) {
        if (shown == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note(T("This vehicle is no longer reporting.", "כלי הרכב הזה כבר לא משדר מיקום."), Modifier.weight(1f))
                CardClose(onClose)
            }
            return@Column
        }
        val a = shown.arrival
        Row(
            Modifier.fillMaxWidth().padding(bottom = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            LinePlate(shown)
            Column(Modifier.weight(1f)) {
                shown.line?.destination?.ifBlank { null }?.let(::shownName)?.let {
                    Text(T("to $it", "אל $it"), fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                shown.line?.origin?.ifBlank { null }?.let(::shownName)?.let {
                    Text(T("from $it", "מ-$it"), fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CardClose(onClose)
        }
        val (headline, shade) = when {
            a.vehicleStatus == 3 -> T("Not departed yet", "טרם יצא") to K.dim
            !a.hasLocation -> T("No live location right now", "אין מיקום בזמן אמת כרגע") to K.dim
            a.vehicleStatus == 2 -> T("Out of route", "מחוץ למסלול") to K.problem
            else -> locationHeadline(a.sampleUtc, now)
        }
        // A new state eases to its colour.
        val tint by androidx.compose.animation.animateColorAsState(shade, tween(300), label = "headline")
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.hasLocation) { LiveGlyph(tint, 13.dp); Spacer(Modifier.width(6.dp)) }
            // Its seconds roll as they tick; the words stay still (LiveText).
            LiveText(headline, style = K.type.bodyStrong, color = shade, modifier = Modifier.weight(1f))
        }
        if (a.sampleUtc > 0) LiveText(
            updatedLabel(a),
            style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 14.sp), color = K.dim, modifier = Modifier.padding(top = K.gap1),
        )
        // It comes from Google a moment after the card: it opens in place, as on the trip cards (CrowdMeter).
        var shownCrowd by remember { mutableStateOf(crowd) }
        if (crowd != null) shownCrowd = crowd
        androidx.compose.animation.AnimatedVisibility(
            crowd != null,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) { shownCrowd?.let { Box(Modifier.padding(top = K.gap2)) { CrowdLevel(it) } } }
        Spacer(Modifier.height(K.gap3))
        KavLive.plateOf(a.vehicleId)?.let { LiveFact(T("Number plate", "מספר רישוי"), it) }
        if (a.platform.isNotBlank()) LiveFact(T("Platform", "רציף"), a.platform)
        // A train, on the form's own questions for trains (ReportFor.train).
        shown.line?.takeIf { modeOf(shown.routeType) == Mode.TRAIN }?.let { line ->
            Text(T("Report this train", "דיווח על הרכבת הזו"), fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clickable(role = Role.Button) {
                    onReport(ReportFor(
                        line = shown.number, agency = "",
                        origin = line.origin, destination = line.destination, stops = reportStops, at = now, train = true,
                    ))
                }.padding(vertical = K.gap2))
        }
        // A light rail, on the same questions, as Jerusalem's or Gush Dan's by where it is.
        shown.line?.takeIf { modeOf(shown.routeType).let { m -> m == Mode.TRAM || m == Mode.SUBWAY } }?.let { line ->
            Text(T("Report this light rail", "דיווח על הרכבת הקלה"), fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clickable(role = Role.Button) {
                    onReport(ReportFor(
                        line = shown.number, agency = "",
                        origin = line.origin, destination = line.destination, stops = reportStops, at = now, train = true,
                        trainType = lightRailType(if (a.hasLocation) a.lat to a.lon else null),
                    ))
                }.padding(vertical = K.gap2))
        }
        shown.line?.takeIf { modeOf(shown.routeType) == Mode.BUS }?.let { line ->
            Text(T("Report this bus", "דיווח על האוטובוס הזה"), fontSize = 14.sp, color = K.accentInk,
                modifier = Modifier.clickable(role = Role.Button) {
                    val kav = shown.kav
                    onReport(ReportFor(
                        line = shown.number, agency = "",
                        origin = line.origin, destination = line.destination,
                        stops = reportStops,
                        plate = KavLive.plateOf(a.vehicleId), at = now,
                        near = if (a.hasLocation) a.lat to a.lon else null,
                        towns = kav?.let { it.fromCity to it.toCity },
                        operatorId = kav?.agency, direction = kav?.name?.let(::ministryDirection),
                    ))
                }.padding(vertical = K.gap2))
        }
        // Only once it's known where the bus is, so the rail opens on it.
        androidx.compose.animation.AnimatedVisibility(
            stops.size > 1 && progress >= 0f,
            enter = androidx.compose.animation.expandVertically() + fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + fadeOut(),
        ) { BusRail(stops, progress) }
    }
}

private val RAIL_HEIGHT = 120.dp

// The bus's stops on a rail, as the card for the bus you're on draws them, from the last one it passed. At each refresh
// the bus glides along the rail to where it is now, and as it passes a stop the rail scrolls on to it rather than
// dropping the stop behind.
@Composable
private fun BusRail(stops: List<Transit.Stop>, progress: Float) {
    val ids = remember(stops) { stops.map { it.id } }
    val names = remember(stops) { stops.associate { it.id to Transit.StopInfo(it.id, it.name, "") } }
    val eased by animateFloatAsState(progress, tween(900, easing = FastOutSlowInEasing), label = "busRail")
    val scroll = rememberScrollState()
    // Opened on the bus at once; from then on the rail glides with it.
    var placed by remember { mutableStateOf(false) }
    Column {
        Spacer(Modifier.height(K.gap2))
        Box(Modifier.height(1.dp).fillMaxWidth().background(K.border))
        Spacer(Modifier.height(K.gap2 - 5.dp))
        Box(Modifier.fillMaxWidth().heightIn(max = RAIL_HEIGHT).verticalScroll(scroll)) {
            StopRail(ids, names, eased, 0, follow = { top ->
                if (placed) scroll.animateScrollTo(top, tween(600, easing = FastOutSlowInEasing)) else { scroll.scrollTo(top); placed = true }
            }, byFew = true)
        }
    }
}

// A count that ticks once a second (a position's age): only the digits that changed roll, briefly, each its own layer
// moved and faded in the draw phase, so a roll draws no text again and the frames between ticks do nothing. The number
// keeps its digits left to right in Hebrew too.
// said: what a screen reader is told, one phrase that changes far less often than the digits roll.
@Composable
private fun TickText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, said: String) {
    val number = remember(text) { Regex("\\d+").find(text) }
    if (number == null) { Text(text, style = style, color = color, maxLines = 1); return }
    // A digit place that comes in later (9 to 10) rolls in too; on the first look nothing rolls.
    var seen by remember { mutableStateOf(false) }
    SideEffect { seen = true }
    Row(Modifier.clearAndSetSemantics { contentDescription = said }) {
        val before = text.substring(0, number.range.first)
        val after = text.substring(number.range.last + 1)
        if (before.isNotEmpty()) Text(before, style = style, color = color, maxLines = 1)
        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
            Row {
                val digits = number.value
                // Each digit is known by its place from the right, so the ones stay the ones when the number grows.
                digits.forEachIndexed { i, d -> key(digits.length - i) { RollingDigit(d, style, color, seen) } }
            }
        }
        if (after.isNotEmpty()) Text(after, style = style, color = color, maxLines = 1)
    }
}

// How long ago a vehicle reported, as a screen reader says it: a ticking count of seconds, said each second, would have
// every accessibility service told of a change every second for each vehicle listed.
private fun ageSaid(s: Long): String = when {
    s < 10 -> T("Updated just now", "עודכן הרגע")
    s < 60 -> T("Updated about ${s / 10 * 10} seconds ago", "עודכן לפני כ-${s / 10 * 10} שניות")
    else -> T("Updated about ${s / 60} minutes ago", "עודכן לפני כ-${s / 60} דקות")
}

@Composable
private fun RollingDigit(d: Char, style: androidx.compose.ui.text.TextStyle, color: Color, late: Boolean) {
    var last by remember { mutableStateOf(if (late) ' ' else d) }
    val roll = remember(d) { Animatable(if (last == d) 1f else 0f) }
    LaunchedEffect(d) {
        roll.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        last = d
    }
    val old = last
    // One glyph each, so its fade is the glyph's own alpha, without a layer drawn offscreen for it.
    val fade = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
    Box(contentAlignment = Alignment.Center) {
        if (old != d) Text(old.toString(), style = style, color = color, maxLines = 1, modifier = Modifier.graphicsLayer {
            val t = roll.value
            alpha = 1f - t; translationY = -t * size.height * .45f
            compositingStrategy = fade
        })
        Text(d.toString(), style = style, color = color, maxLines = 1, modifier = Modifier.graphicsLayer {
            val t = roll.value
            alpha = t; translationY = (1f - t) * size.height * .45f
            compositingStrategy = fade
        })
    }
}

// How far along its stops the bus is, as the rail counts it: the stops it passed and the part of the way to the next,
// measured along its road. -1 without one.
private fun busProgress(stops: List<Transit.Stop>, route: List<Pair<Double, Double>>, bus: Transit.Arrival?, onRoad: StopsOnRoute?): Float {
    if (bus == null || route.size < 2 || stops.isEmpty()) return -1f
    val along = alongRoute(bus.lat to bus.lon, route)
    var passed = 0
    var prev = 0.0
    var next = Double.NaN
    for ((i, s) in stops.withIndex()) {
        val a = onRoad?.along?.get(i) ?: alongRoute(s.lat to s.lon, route)
        if (a <= along) { passed++; prev = a } else { next = a; break }
    }
    val frac = if (!next.isNaN() && next > prev) ((along - prev) / (next - prev)).toFloat().coerceIn(0f, 1f) else 0f
    return (passed + frac).coerceAtMost(stops.size.toFloat())
}

@Composable
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
private fun LiveStopCard(
    stop: Transit.Stop?,
    stopId: Int,
    arrivals: Map<Transit.ArrivalKey, Transit.Arrival>,
    lines: Map<Int, Transit.LineInfo?>,
    modes: Map<Int, Int>,
    now: Long,
    onVehicle: (Tracked) -> Unit,
    onClose: () -> Unit,
) {
    val due = remember(arrivals, stopId) {
        arrivals.values.filter { it.stopId == stopId }
            .sortedBy { a -> a.rtUtc.takeIf { it > 0 } ?: a.staticUtc }
    }
    val rows = remember(due, lines, modes, stop) {
        due.map { a ->
            val line = lines[a.lineId]
            Tracked(a, stop, line, a.lineId in lines, modes[line?.agencyId ?: -1] ?: 3)
        }
    }
    val moving = remember(due) { due.count { it.hasLocation } }
    val ctx = LocalContext.current
    // The board lists nothing at a stop where every line ends, which is not the same as nothing due.
    val endsHere by produceState(false, stop?.id, rows.isEmpty()) {
        value = false
        val st = stop ?: return@produceState
        if (rows.isNotEmpty()) return@produceState
        val net = runCatching { loadNet(ctx) }.getOrNull() ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val same = net.nearestStops(st.lat, st.lon, k = 6, radius = 60.0).map { it.first }
                .filter { net.name[it] == st.name }.toHashSet()
            same.isNotEmpty() && same.none { net.dStart[it + 1] > net.dStart[it] } &&
                net.tripRoute.indices.any { net.tripLast(it) in same }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = K.gap3)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(bottom = K.gap2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            val name = stop?.name
            // The timetable's own stop here.
            val inNet by produceState<Pair<Net, Int>?>(null, stop?.id, stopId) {
                val st = stop ?: return@produceState
                val net = runCatching { loadNet(ctx) }.getOrNull() ?: return@produceState
                value = withContext(Dispatchers.Default) {
                    net.nearestStops(st.lat, st.lon, k = 6, radius = 60.0).map { it.first }.firstOrNull { net.name[it] == st.name }
                }?.let { net to it }
            }
            // Street View faces the stop from its road where the timetable has it, else from wherever Google stands.
            val look = inNet?.let { (net, at) -> StopLook(lookAt(net, at), stopName(name)) }
                ?: stop?.let { StopLook(StreetLook(it.lat to it.lon), stopName(name)) }
            inNet?.let { (net, at) -> StopGlyphOrPhoto(net, at, null, thumb = 44.dp, look = look, opened = true) }
                ?: StopGlyphOrPhoto(stopId, null, thumb = 44.dp, look = look, opened = true)
            Column(Modifier.weight(1f)) {
                Text(
                    stopName(name), style = K.type.headline,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                // The counts roll as buses come and go.
                LiveText(
                    if (rows.isEmpty() && endsHere) T("No departures · every line ends here", "אין יציאות · כל הקווים מסתיימים כאן")
                    else if (rows.isEmpty()) T("Nothing due right now", "אין יציאות כרגע")
                    else T.count(rows.size, "1 due", "יציאה אחת", "due", "יציאות") + " · " +
                        if (moving == 1) T("1 reporting a position", "אחת מדווחת מיקום") else T("$moving reporting a position", "$moving מדווחות מיקום"),
                    style = K.type.subhead.tabular,
                )
            }
            CardClose(onClose)
        }
        if (rows.isNotEmpty()) LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 300.dp),
            contentPadding = PaddingValues(horizontal = K.gap1),
        ) {
            items(rows, key = { it.tripId }) { v ->
                val live = v.arrival.hasLocation
                // A bus coming due fades in, one gone fades out, and the rest glide to their new places.
                Row(
                    Modifier.animateItem(
                        fadeInSpec = androidx.compose.animation.core.tween(220, delayMillis = 60),
                        fadeOutSpec = androidx.compose.animation.core.tween(150),
                        placementSpec = androidx.compose.animation.core.spring(
                            dampingRatio = 1f, stiffness = 500f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold,
                        ),
                    ).fillMaxWidth().clip(RoundedCornerShape(K.rTile))
                        .clickable(
                            role = Role.Button,
                            onClickLabel = if (live) T("Follow this vehicle", "מעקב אחר כלי הרכב")
                            else T("See where line ${v.number} goes", "המסלול של קו ${v.number}"),
                        ) { onVehicle(v) }
                        .padding(horizontal = K.gap3, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    LinePlate(v)
                    Column(Modifier.weight(1f)) {
                        Text(
                            v.line?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "אל ${shownName(it)}") }
                                ?: if (v.pending) T("Looking up the line…", "מאתרים את הקו…") else T("Line ${v.number}", "קו ${v.number}"),
                            fontSize = 14.sp, color = K.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (v.arrival.platform.isNotBlank()) {
                            Spacer(Modifier.height(K.gap1))
                            PlatformTag(v.arrival.platform)
                        }
                    }
                    if (live) LiveGlyph(
                        if (v.arrival.vehicleStatus == 2) K.problem else freshColour(freshnessOf(v.arrival.sampleUtc, now)), 11.dp,
                    )
                    LiveText(
                        whenLabel(v.eta, now), style = K.type.subhead.tabular,
                        color = if (live) K.text else K.scheduled,
                    )
                    Icon(
                        Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Text(label, fontSize = 14.sp, color = K.dim, modifier = Modifier.width(130.dp))
        Text(value, fontSize = 14.sp, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

// What a vehicle's colour on the map says (freshColour): how old its position is.
@Composable
private fun FreshnessKey() {
    Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        for ((f, label) in listOf(
            Freshness.LIVE to T("Position from the last 1.5 min", "מיקום מהדקה וחצי האחרונה"),
            Freshness.ESTIMATED to T("Position 1.5 to 3.5 min old", "מיקום בן דקה וחצי עד שלוש וחצי דקות"),
            Freshness.OFF to T("Older: it may be off", "ישן יותר: ייתכן שאינו מדויק"),
        )) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(freshColour(f)))
            Text(label, style = K.type.subhead)
        }
    }
}
