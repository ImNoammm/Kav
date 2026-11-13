package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.Loaded
import uk.noammm.kav.Tab
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.MOVED_S
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.RecentRoutes
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.departuresAt
import uk.noammm.kav.data.nearestStops
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

// The latest route as Home last read it, for the session. Kav reads it ahead as it starts (preload), with its end's photo
// from the phone, so the card opens with both from its first frame.
internal object LatestRoute {
    @Volatile var read = false
    @Volatile var route: ActiveJourney? = null

    fun preload(ctx: android.content.Context) {
        val latest = RecentRoutes.latest(ctx)
        latest?.trip?.legs?.lastOrNull()?.shape?.lastOrNull()?.let { (lat, lon) ->
            uk.noammm.kav.data.PlacePhotos.fromDisk(Transit.Place(latest.toLabel, "", lat, lon))
        }
        route = latest
        read = true
    }
}

private val ease = spring<Dp>(dampingRatio = 1f, stiffness = 500f)

@Composable
internal fun HomeSuggestions(model: KavModel, onLatest: (ActiveJourney) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // The card waits for the latest route, so it opens on its first page instead of switching to it a moment later.
    var read by remember { mutableStateOf(LatestRoute.read) }
    var latest by remember { mutableStateOf(LatestRoute.route) }
    LaunchedEffect(Unit) {
        val route = withContext(Dispatchers.IO) { RecentRoutes.latest(ctx) }
        LatestRoute.route = route
        LatestRoute.read = true
        latest = route
        read = true
    }
    // Only a fade: Home's list glides the cards under it down, and growing the card as well moved them twice.
    AnimatedVisibility(
        read,
        enter = fadeIn(tween(220)),
        exit = ExitTransition.None,
    ) {
        SuggestionsCard(model, latest, onLatest, modifier)
    }
}

private enum class Near { ASK, FINDING, NO_TIMETABLE, NONE, STATION }

// What the station's page shows, with the station and timetable it was worked out from.
private data class NearPage(val near: Near, val found: Found?, val net: Net?)

// The nearest station once worked out, with its lines from the timetable on the phone: null inside when there is no stop
// within 2.5 km.
private class Found(val station: Station?, val lines: List<StationLine>? = null)

// The nearest station's lines as last worked out, live where they were, so the page opens on them at once; their times
// are read against the clock as they show.
private object NearLines {
    var stops: List<Int> = emptyList()
    var lines: List<StationLine>? = null
    fun of(stops: List<Int>) = lines.takeIf { stops == this.stops }
}

@Composable
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
private fun SuggestionsCard(model: KavModel, latest: ActiveJourney?, onLatest: (ActiveJourney) -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val net = model.net ?: Loaded.net
    val here = model.here
    // Null until worked out, so "No stops" never shows for the moment it takes.
    val found by produceState<Found?>(null, net, here) {
        if (net != null && here != null) value = withContext(Dispatchers.Default) {
            val station = nearestStation(net, here.first, here.second)
            Found(station, station?.let { NearLines.of(it.stops) ?: stationLines(net, it.stops, live = false) })
        }
    }
    val routePages = if (latest == null) 0 else 1
    val scope = rememberCoroutineScope()
    // Where the phone may be asked, the card asks it itself and shows it looking, rather than offering a button for the
    // moment the answer takes.
    var locating by remember { mutableStateOf(here == null && hasLocationPermission(ctx)) }
    fun locate() {
        locating = true
        requestLocationOnce(ctx, onFail = { locating = false }) { model.locate(it.first, it.second); locating = false }
    }
    LaunchedEffect(Unit) { if (model.here == null && hasLocationPermission(ctx)) locate() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate()
    }
    val result = found
    val near = when {
        here == null -> if (locating) Near.FINDING else Near.ASK
        model.netLoading -> Near.FINDING
        net == null -> Near.NO_TIMETABLE
        result == null -> Near.FINDING
        result.station == null -> Near.NONE
        else -> Near.STATION
    }
    // The station's page never shows looking. What it last showed stays while it's worked out again (a timetable loading,
    // a new position), so a refresh never takes the page or its dot away; and Kav remembers it had the page, and how tall,
    // so the next start has the page and its dot from the first frame, empty out of sight until it's worked out.
    val shownNear = remember { mutableStateOf<NearPage?>(null) }
    if (near != Near.FINDING) shownNear.value = NearPage(near, result, net)
    val shown = shownNear.value
    val kept = remember { Prefs.nearPage(ctx) }
    val count = routePages + if (shown != null || kept > 0) 1 else 0
    val pager = rememberPagerState { count }
    val heights = remember { mutableStateMapOf<Int, Int>() }
    val ceiling = with(LocalDensity.current) { (440.dp * fontScale).roundToPx() }
    LaunchedEffect(shown != null, heights[routePages]) {
        val height = heights[routePages]
        if (shown != null && height != null && height > 0 && height != kept) Prefs.setNearPage(ctx, height)
    }
    // On a first start, with no page remembered, the card waits a moment for the station, so a route's card opens with
    // both its pages and dots rather than gaining one.
    var waited by remember { mutableStateOf(kept > 0) }
    LaunchedEffect(Unit) { if (!waited) { delay(1_200); waited = true } }
    // Nothing to show yet (no route, the station still being found): no card, until there is. A card that would open on
    // the station's page waits for it, as an empty page in sight would be one being loaded.
    androidx.compose.animation.AnimatedVisibility(count > 0 && (shown != null || routePages > 0 && waited), enter = fadeIn(tween(220)), exit = fadeOut(tween(150))) {
    Column(modifier.fillMaxWidth().panel(K.rCard)) {
        HorizontalPager(pager, Modifier.pageSized(pager, heights, ceiling, bottom = false),
            verticalAlignment = Alignment.Top, beyondViewportPageCount = 1, pageSpacing = K.gap4,
        ) { page ->
            val route = latest
            val waiting = page >= routePages && shown == null
            Column(Modifier.fillMaxWidth().onSizeChanged { heights[page] = it.height }
                .then(if (waiting) Modifier.heightIn(min = with(LocalDensity.current) { kept.toDp() }) else Modifier)) {
                if (page < routePages && route != null) {
                    Caption(T("My latest route", "המסלול האחרון שלי"))
                    // A newer route eases in over the one before.
                    AnimatedContent(
                        route, contentKey = { "${it.trip.guid}|${it.trip.dep}|${it.fromLabel}|${it.toLabel}" },
                        transitionSpec = { swap() }, label = "latestRoute",
                    ) { LatestRouteRow(it, onLatest) }
                } else {
                    Caption(T("Nearest station", "התחנה הקרובה"))
                    if (shown != null) AnimatedContent(shown, contentKey = { it.near }, transitionSpec = { swap() }, label = "nearest") { now ->
                        when (now.near) {
                            Near.ASK -> Column(Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, bottom = K.gap4),
                                verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                                Text(T("Your location is used on the phone to find nearby stops.",
                                    "המיקום משמש בטלפון למציאת תחנות קרובות."), style = K.type.subhead, color = K.textSecondary)
                                KavButton(T("Use my location", "השתמשו במיקום שלי"), {
                                    if (hasLocationPermission(ctx)) locate() else permission.launch(LOCATION_PERMISSIONS)
                                }, Modifier.fillMaxWidth(), busy = locating)
                            }
                            Near.FINDING -> {}
                            Near.NO_TIMETABLE -> ListRow(T("Open the timetable to find nearby stops", "פתחו את לוח הזמנים כדי למצוא תחנות קרובות"),
                                onClick = { model.tab = Tab.Stations })
                            Near.NONE -> ListRow(T("No stops within 2.5 km", "אין תחנות בטווח 2.5 ק״מ"),
                                onClick = { model.tab = Tab.Stations })
                            // Only while there is one: a page fading out can outlive the timetable or position it showed.
                            Near.STATION -> {
                                val station = now.found?.station
                                val shownNet = now.net
                                if (shownNet != null && station != null) {
                                    // Another station, as after a move, fades in over the last.
                                    AnimatedContent(station, contentKey = { it.stop }, transitionSpec = { swap() }, label = "station") {
                                        NearestStation(shownNet, it, now.found?.lines, page == pager.settledPage) { stop -> model.openStation(stop) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            count > 1,
            enter = fadeIn(tween(220, delayMillis = 60)) + androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top),
            exit = fadeOut(tween(150)) + androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top),
        ) { PageDots(maxOf(count, 2), pager, routePages) { page -> scope.launch { pager.animateScrollToPage(page) } } }
    }
    }
}

// One state of a page fading into the next while the card eases to its height.
private fun swap() = ContentTransform(
    fadeIn(tween(220, delayMillis = 60)), fadeOut(tween(150)),
    sizeTransform = SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) },
)

@Composable
private fun Caption(text: String) {
    Text(text, style = K.type.caption, color = K.textSecondary,
        modifier = Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap4, bottom = K.gap1))
}

// The page dots, one tap target each (28 dp square). Each grows and takes the accent as its page slides in, with the
// finger, and sits midway between the last row and the card's edge.
@Composable
private fun PageDots(count: Int, pager: androidx.compose.foundation.pager.PagerState, routePages: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        repeat(count) { page ->
            val label = if (page < routePages) T("My latest route", "המסלול האחרון שלי") else T("Nearest station", "התחנה הקרובה")
            Box(Modifier.size(28.dp).semantics { contentDescription = label }
                .selectable(page == pager.currentPage, role = Role.Tab) { onPick(page) }) {
                val accent = K.accent
                val idle = K.borderStrong
                Box(Modifier.fillMaxWidth().height(16.dp).drawBehind {
                    val near = (1f - abs(pager.currentPage + pager.currentPageOffsetFraction - page)).coerceIn(0f, 1f)
                    drawCircle(androidx.compose.ui.graphics.lerp(idle, accent, near), (3.dp + 1.dp * near).toPx())
                })
            }
        }
    }
}

// A row of the card, the same on both pages: a 40 dp tile, a title over one line (the route's steps on a shared
// baseline, or how far the station is), a value and a chevron. The line keeps gap1 under it as the title keeps room over
// its capitals, so both sit evenly between the hairlines, and the row is as tall as a list row with a subtitle.
@Composable
private fun SuggestionRow(
    title: String,
    clickLabel: String,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    value: (@Composable () -> Unit)? = null,
    line: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        leading()
        Column(Modifier.weight(1f).padding(bottom = K.gap1)) {
            Text(title, style = K.type.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 2.dp).fillMaxWidth().heightIn(min = plateHeight()), contentAlignment = Alignment.CenterStart) { line() }
        }
        value?.invoke()
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
    }
}

// The latest route: its tile where a stop's photo goes, where to, and its steps on one line. The row opens the route.
@Composable
private fun LatestRouteRow(route: ActiveJourney, onLatest: (ActiveJourney) -> Unit) {
    SuggestionRow(
        route.toLabel, T("Open the route", "פתיחת המסלול"), { onLatest(route) },
        leading = {
            // Where the route goes: a favourite by its icon, a place by Google Maps' photo of it, on the route's tile
            // until it comes or where there's none. The row opens the route, so the photo doesn't open on its own.
            val end = rememberTripEnd(route.toLabel, route.trip.legs.lastOrNull()?.shape?.lastOrNull())
            val glyph: @Composable () -> Unit = {
                Icon(Icons.Rounded.Route, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(20.dp))
            }
            if (end != null) TripEndTile(end, open = false, glyph = glyph)
            else Box(Modifier.size(40.dp).clip(RoundedCornerShape(K.rTile)).background(K.fill), contentAlignment = Alignment.Center) { glyph() }
        },
    ) { RouteLine(route) }
}

private class RouteStep(val kind: Transit.LegKind, val minutes: Int, val ride: Transit.Leg? = null)

// The route's steps as a result card's strip has them: walks run together, the rides, taxis and bikes between them.
private fun routeSteps(trip: Transit.Itinerary): List<RouteStep> {
    val out = ArrayList<RouteStep>()
    for (l in trip.legs) {
        if (l.pathway) continue
        when (l.kind) {
            Transit.LegKind.WALK -> {
                val last = out.lastOrNull()
                if (last != null && last.kind == Transit.LegKind.WALK) out[out.lastIndex] = RouteStep(Transit.LegKind.WALK, last.minutes + l.minutes)
                else out.add(RouteStep(Transit.LegKind.WALK, l.minutes))
            }
            Transit.LegKind.RIDE, Transit.LegKind.TAXI, Transit.LegKind.BIKE -> out.add(RouteStep(l.kind, l.minutes, l))
            else -> {}
        }
    }
    return out.filter { it.kind != Transit.LegKind.WALK || it.minutes >= 1 }
}

// Every line that can take a ride, its own first, as a result card lists them.
private fun rideNumbers(ride: Transit.Leg, r: Transit.Resolved): List<String> = ride.lineChoices.mapNotNull { id ->
    ride.options.firstOrNull { it.lineId == id }?.shortName?.ifBlank { null } ?: r.line(id)?.number?.ifBlank { null }
}.distinct()

private fun rideType(ride: Transit.Leg, r: Transit.Resolved): Int = r.line(ride.lineId)?.let { r.routeType(it.agencyId) } ?: 3

// "33, 40 or 8".
private fun orList(items: List<String>) =
    if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + T(" or ", " או ") + items.last()

private fun stepSaid(s: RouteStep, r: Transit.Resolved): String = when (s.kind) {
    Transit.LegKind.RIDE -> s.ride?.let { lineDescription(rideType(it, r), orList(rideNumbers(it, r))) }.orEmpty()
    Transit.LegKind.TAXI -> T("Taxi", "מונית")
    Transit.LegKind.BIKE -> if (stripShowsMinutes(s.minutes)) T("Cycle ${s.minutes} min", "רכיבה ${s.minutes} דק׳") else T("Cycle", "רכיבה")
    else -> if (stripShowsMinutes(s.minutes)) T("Walk ${s.minutes} min", "הליכה ${s.minutes} דק׳") else T("Walk", "הליכה")
}

// The route at a glance, in the card's small plates: each ride by its first line, with how many others can take it; each
// walk as the trip card's strip draws it (StripWalk), its minutes only from five on; arrows between. Every step shows: on one line, closer together if that fits it, else on as many
// lines as it takes (StepFlow). It is said in full. Spaced by what shows, not by boxes: 7 dp either side of an arrow, 4 dp from a plate
// to its "+N", 3 dp from a walker to its minutes, all grown with the text as the glyphs are; and the first step's ink
// starts where the title's does. "+N" and the minutes don't change in place, so their figures take their own widths
// (pnum), not a column's: a 1 has no more room after it than a 4.
@Composable
private fun RouteLine(route: ActiveJourney) {
    val r = route.resolved
    val steps = remember(route.trip) { routeSteps(route.trip) }
    val said = remember(steps, T.lang) {
        T("from ${route.fromLabel}: ", "מ־${route.fromLabel}: ") + steps.joinToString(", ") { stepSaid(it, r) }
    }
    val shown = steps
    val middle = textMiddle(K.type.subhead)
    val plate = plateMinimum()
    val tall = plateHeight()
    val scale = LocalDensity.current.fontScale
    // Spaced, and its walks and arrows drawn, as the trip card's strip (RouteStrip) has them.
    StepFlow(4.dp, 4.dp, Modifier.clearAndSetSemantics { contentDescription = said }) {
        shown.forEachIndexed { i, s ->
            Row {
                if (i > 0) {
                    Box(Modifier.alignByBaseline().onTextLine(middle)) { Chevron(size = 11.dp) }
                    Spacer(Modifier.width(4.dp))
                }
                val ride = s.ride
                if (s.kind == Transit.LegKind.RIDE && ride != null) {
                    val numbers = rideNumbers(ride, r)
                    val type = rideType(ride, r)
                    val agency = r.line(ride.lineId)?.agencyId ?: -1
                    LinePlateBadge(numbers.firstOrNull() ?: r.line(ride.lineId)?.number, modeOf(type),
                        linePlate(type, agency) { Operators.of(r, agency) },
                        Modifier.alignByBaseline().widthIn(min = plate).height(tall), dense = true)
                    // Isolated, so the sign stays before the number in Hebrew.
                    if (numbers.size > 1) Text("\u2066+${numbers.size - 1}\u2069", Modifier.alignByBaseline().padding(start = 4.dp * scale),
                        style = K.type.subhead.copy(fontFeatureSettings = "pnum"), color = K.textSecondary, maxLines = 1, softWrap = false)
                } else {
                    val glyph = Modifier.alignByBaseline().onTextLine(middle)
                    when (s.kind) {
                        Transit.LegKind.TAXI -> Box(glyph) { ModeGlyph(Mode.TAXI, K.textSecondary, 18.dp * scale) }
                        Transit.LegKind.BIKE -> Box(glyph) { CycleGlyph(ride, K.textSecondary, 18.dp * scale) }
                        // The trip card's own walk: its walker, and its minutes only where it shows them.
                        else -> Box(glyph) { StripWalk(s.minutes) }
                    }
                    if (s.kind != Transit.LegKind.WALK && stripShowsMinutes(s.minutes)) Text("${s.minutes}",
                        Modifier.alignByBaseline().padding(start = 3.dp * scale),
                        style = K.type.subhead.copy(fontFeatureSettings = "pnum"),
                        color = K.textSecondary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

private val numberStyle get() = K.type.subhead.tabular.copy(fontWeight = FontWeight.Medium)

// A plate's height on the card: the small plate's 24 dp, grown with the text so its number keeps its room.
@Composable
private fun plateHeight(): Dp = 24.dp * LocalDensity.current.fontScale

@Composable
private fun plateMinimum(): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(density) { with(density) { linePlateWidth(measurer.measure("888", numberStyle).size.width.toDp(), dense = true) } }
}

// How far above its baseline the middle of a line of text in this style sits, in px: a glyph centred there stands level
// with the numbers beside it.
@Composable
private fun textMiddle(style: TextStyle): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(style, density) { measurer.measure("8", style).let { (it.firstBaseline - it.size.height / 2f).roundToInt() } }
}

// A glyph's baseline, set where a line of text centred on it would have one, so it lines up with the numbers by it.
private fun Modifier.onTextLine(middle: Int) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height, mapOf(FirstBaseline to p.height / 2 + middle)) { p.place(0, 0) }
}

// The station the card shows: of the stops within 400 m, the nearest with a departure in the next half hour, else the
// nearest stop of all; with its twins, the stops of its name within 60 m (across the street, or a station's other
// platforms). Their lines leave from the same place the other way, and a phone's fix can't tell which side it's on, so
// the card lists the lines of all of them, each saying where it goes.
private class Station(val stop: Int, val metres: Double, val stops: List<Int>)

private fun nearestStation(net: Net, lat: Double, lon: Double): Station? {
    val around = net.nearestStops(lat, lon)
    if (around.isEmpty()) return null
    val time = ZonedDateTime.now(ZoneId.of("Asia/Jerusalem"))
    val second = time.toLocalTime().toSecondOfDay()
    val day = time.dayOfWeek.value % 7
    fun soon(stop: Int) = net.departuresAt(stop, second, day, limit = 1).any { it.second <= second + 1800 }
    val (stop, away) = around.firstOrNull { (s, m) -> m <= 400 && soon(s) } ?: around.first()
    val twins = around.map { it.first }.filter { s ->
        s != stop && net.name[s] == net.name[stop] && (net.code[s] != net.code[stop] || net.code[s] <= 0) &&
            metres(net.lat[stop], net.lon[stop], net.lat[s], net.lon[s]) <= 60
    }
    return Station(stop, away, listOf(stop) + twins)
}

// A line leaving the station: the stop it leaves from, its plate, where it goes and when it next leaves (a second of
// the day, live or not). key: the same line from one look to the next.
// live: the time from Kav's server, as the stop's board has it, which marks and colours it as there.
private class StationLine(
    val key: String, val stop: Int, val number: String, val route: Int, val type: Int, val agency: Int, val to: String,
    val at: Int, val live: Transit.Departure?,
)

private class StationDeparture(
    val stop: Int, val number: String, val route: Int, val type: Int, val agency: Int, val to: String, val at: Int,
    val live: Transit.Departure?,
)

// A live time as the stop's board makes it (Stations.kt stopLive).
private fun departureOf(b: KavLive.BoardTime) =
    Transit.Departure(KavLive.tripIdOf(b.due.key), b.due.expected - (b.rt - b.static), rtUtc = b.due.expected)

// The station's lines, soonest first, as its stops' boards have their departures: the timetable's, each at the live
// time of its bus where the live source has it, and the live source's buses the timetable hasn't.
// live: with Kav's server's times; else the timetable's alone, at once and with no network.
private fun stationLines(net: Net, stops: List<Int>, live: Boolean = true): List<StationLine> {
    val now = System.currentTimeMillis() / 1000
    val time = ZonedDateTime.now(ZoneId.of("Asia/Jerusalem"))
    val second = time.toLocalTime().toSecondOfDay()
    val day = time.dayOfWeek.value % 7
    val codes = stops.map { net.code[it] }.filter { it > 0 }
    val board = if (!live || codes.isEmpty()) null else runCatching { KavLive.stops(codes) }.getOrNull()
    val all = stops.flatMap { stop ->
        val live = board?.let { b -> runCatching { KavLive.boardTimes(net, stop, b, now) }.getOrNull() }.orEmpty()
        val used = HashSet<KavLive.BoardTime>()
        // From half an hour back: a late bus is still to come after its timetabled time.
        val planned = net.departuresAt(stop, second - 1800, day, limit = 120).map { (c, dep) ->
            val trip = net.tripOf(c)
            val route = net.tripRoute[trip]
            val bus = live.firstOrNull { it !in used && it.trip == trip && abs(it.static - dep) <= 2 * MOVED_S }?.also { used.add(it) }
            StationDeparture(stop, net.rShort[route], route, net.rType[route], -1, bus?.to ?: net.name[net.tripLast(trip)], bus?.rt ?: dep,
                bus?.let(::departureOf))
        }
        val own = live.filter { it !in used && it.trip < 0 }.map { b ->
            val line = board?.lines?.get(b.due.route)
            StationDeparture(stop, b.due.number, -1, line?.type ?: 3, line?.agency ?: -1, b.to ?: line?.to.orEmpty(), b.rt, departureOf(b))
        }
        planned + own
    }.filter { it.at >= second }
    return all.groupBy { Triple(it.stop, it.number, it.to) }.map { (k, ds) ->
        val d = ds.minBy { it.at }
        StationLine("${k.first}|${k.second}|${k.third}", d.stop, d.number, d.route, d.type, d.agency, net.shown(d.to), d.at, d.live)
    }.sortedBy { it.at }
}

// Two lines, a time each: a glance at what leaves next, with few numbers, and room for where
// each goes. The station's board, a tap away, has the rest.
private const val SHOWN_LINES = 2

// Where the station's chevron ink ends, inside its 20 dp box: the line rows' times end there too.
private val TRAILING = K.gap4 + 7.dp

// The nearest station, as a stop's own card: its photo, name and how far, then the lines leaving it soonest, a row
// each under its name: plate, where to, when. A new set of lines fades in over the last; the same lines in a new order
// glide to their places; times roll.
@Composable
private fun NearestStation(net: Net, station: Station, planned: List<StationLine>?, active: Boolean, onOpen: (Int) -> Unit) {
    // The timetable's lines at once (or the last ones worked out); the live times roll in over them as they come, asked
    // whichever page shows while Kav is open: every 30 s on this page, every minute off it.
    var lines by remember(net, station.stops, KavLive.origin) { mutableStateOf(NearLines.of(station.stops) ?: planned) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onPage by rememberUpdatedState(active)
    LaunchedEffect(net, station.stops, KavLive.origin, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val got = withContext(Dispatchers.IO) { stationLines(net, station.stops) }
                NearLines.stops = station.stops; NearLines.lines = got
                lines = got
                delay(if (onPage) 30_000 else 60_000)
            }
        }
    }
    // The times are read against the clock once per update, so they change together, rolling.
    val second = remember(lines) { nowSec() }
    val shown = lines?.take(SHOWN_LINES)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minimum = plateMinimum()
    val tall = plateHeight()
    val widest = remember(shown, density) {
        with(density) { shown?.maxOfOrNull { linePlateWidth(measurer.measure(it.number, numberStyle).size.width.toDp(), dense = true) } } ?: 0.dp
    }
    val plate by animateDpAsState(maxOf(minimum, widest), ease, label = "plateColumn")
    // The times share one column, as wide as the widest a time can be ("88 min", "now", a clock time), so the live mark
    // before it stands at the same place in every row.
    val timeColumn = remember(density, T.rtl, Shown.twelveHour) {
        with(density) {
            listOf(T("88 min", "88 דק׳"), T("now", "עכשיו"), hhmm(23 * 3600 + 59 * 60), hhmm(12 * 3600 + 59 * 60))
                .maxOf { measurer.measure(it, K.type.subhead.tabular).size.width }.toDp()
        }
    }
    // On foot at the pace Kav shows walks at, 1.33 m/s.
    val walk = ceil(station.metres / 1.33 / 60).toInt().coerceAtLeast(1)
    val name = net.stopName(station.stop)
    Column(Modifier.fillMaxWidth()) {
        SuggestionRow(
            name, T("Departures from $name", "יציאות מ$name"), { onOpen(station.stop) },
            leading = { StopGlyphOrPhoto(net, station.stop, null, kind = stopMode(net, station.stop)) },
        ) {
            LiveText(distanceLabel(station.metres) + " · " + T("$walk min walk", "$walk דק׳ הליכה"), style = K.type.subhead, maxLines = 1)
        }
        AnimatedContent(shown, contentKey = { list -> list?.map { it.key }?.toSet() }, transitionSpec = { swap() }, label = "stationLines") { list ->
            Column(Modifier.fillMaxWidth()) {
                when {
                    list == null -> {}
                    list.isEmpty() -> NoteRow(T("No upcoming departures", "אין יציאות קרובות"), tall)
                    // The hairlines stay put, and the lines glide past them to their new places.
                    else -> list.forEach { line ->
                        Separator(inset = K.gap4)
                        key(line.key) {
                            Box(Modifier.glide()) { LineRow(net, line, plate, tall, timeColumn, second) { onOpen(line.stop) } }
                        }
                    }
                }
            }
        }
    }
}

// A line leaving the station: its plate where the station's photo starts, where it goes, and when at the row's end, level
// with the station's chevron, green when live as on a stop's board. The row opens the board of the stop it leaves from,
// a twin's for a twin's line.
@Composable
private fun LineRow(net: Net, line: StationLine, plate: Dp, tall: Dp, timeColumn: Dp, second: Int, onClick: () -> Unit) {
    val from = net.stopName(line.stop)
    OnPlate(
        K.gap3,
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = T("Departures from $from", "יציאות מ$from"), onClick = onClick)
            .padding(start = K.gap4, end = TRAILING, top = K.gap3, bottom = K.gap3),
    ) {
        LineBadge(net, line.route, Modifier.widthIn(min = plate).height(tall), label = line.number, type = line.type,
            agency = line.agency, dense = true)
        Text(T("to ${line.to}", "אל ${line.to}"), style = K.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Every time has a mark before its column, at one place in every row: the board's live mark when it's live, the
        // signal struck through, grey, when it isn't; one fades into the other as a line's live state changes. The time
        // starts just after its mark, "now" as "12 min" does, in the board's colour; the column ends level with the
        // station's chevron.
        Row(verticalAlignment = Alignment.CenterVertically) {
            var marked by remember { mutableStateOf(line.live) }
            if (line.live != null) marked = line.live
            androidx.compose.animation.Crossfade(line.live != null, Modifier.size(12.dp), animationSpec = tween(220), label = "liveMark") { on ->
                if (on) marked?.let { DepMarkGlyph(it, 12.dp) } else LiveOffGlyph(K.scheduled, 12.dp)
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.width(timeColumn), contentAlignment = Alignment.CenterStart) {
                LiveText(relative(maxOf(line.at, second), second) ?: hhmm(line.at), style = K.type.subhead.tabular,
                    color = line.live?.let(::depColour) ?: K.scheduled, maxLines = 1)
            }
        }
    }
}

// A line row's plate, words and time on the plate's baseline, the row as tall as the plate: words in a script set
// taller (Hebrew's are) reach into the row's padding rather than grow the row. The words take the room the plate and
// the time leave.
@Composable
private fun OnPlate(gap: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val space = gap.roundToPx()
        val plate = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val time = measurables[2].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val room = (constraints.maxWidth - plate.width - time.width - 2 * space).coerceAtLeast(0)
        val words = measurables[1].measure(constraints.copy(minWidth = room, maxWidth = room, minHeight = 0))
        val base = plate[FirstBaseline]
        fun top(p: Placeable): Int {
            val b = p[FirstBaseline]
            return if (base == AlignmentLine.Unspecified || b == AlignmentLine.Unspecified) (plate.height - p.height) / 2 else base - b
        }
        layout(constraints.maxWidth, plate.height) {
            plate.placeRelative(0, 0)
            words.placeRelative(plate.width + space, top(words))
            time.placeRelative(constraints.maxWidth - time.width, top(time))
        }
    }
}

// A line row's place while there are no lines to put in it: as tall, its words where a line's plate would start.
@Composable
private fun NoteRow(text: String, tall: Dp) {
    Separator(inset = K.gap4)
    Box(
        Modifier.fillMaxWidth().padding(start = K.gap4, end = TRAILING, top = K.gap3, bottom = K.gap3).heightIn(min = tall),
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

// A route's steps on a baseline (an item without one by its middle), every one of them: on one line, closer together
// (tight) if that makes them fit, else on as many lines as they take, each from the text's edge. A step keeps the arrow
// before it, so a line goes on with one.
@Composable
private fun StepFlow(gap: Dp, tight: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val items = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val most = constraints.maxWidth
        fun across(space: Int) = items.sumOf { it.width } + space * (items.size - 1).coerceAtLeast(0)
        val space = gap.roundToPx().let { normal -> if (across(normal) <= most) normal else tight.roundToPx().takeIf { across(it) <= most } ?: normal }
        val lines = ArrayList<MutableList<Placeable>>()
        var used = 0
        for (p in items) {
            val line = lines.lastOrNull()
            if (line == null || used + space + p.width > most) { lines.add(mutableListOf(p)); used = p.width }
            else { line.add(p); used += space + p.width }
        }
        fun base(p: Placeable) = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: (p.height / 2)
        val bands = lines.map { l -> l.maxOf { base(it) } to l.maxOf { it.height - base(it) } }
        val between = 4.dp.roundToPx()
        val tall = bands.sumOf { it.first + it.second } + between * (lines.size - 1).coerceAtLeast(0)
        layout(most, maxOf(tall, constraints.minHeight)) {
            var y = 0
            lines.forEachIndexed { k, l ->
                val (above, below) = bands[k]
                var x = 0
                l.forEach { it.placeRelative(x, y + above - base(it)); x += it.width + space }
                y += above + below + between
            }
        }
    }
}
