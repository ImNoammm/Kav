@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.RailTrip
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.HopOnShare
import uk.noammm.kav.data.shownName
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.font.FontWeight

// The message a shared trip goes out as with Kav's own link: the trip's page on Kav's server, which offers it in the
// other apps the Link customization names too. The Share sheet sends it. card: the picture chats show with the link,
// asked for only when one is made.
internal suspend fun shareText(
    ctx: android.content.Context, trip: Transit.Itinerary, r: Transit.Resolved, fromLabel: String, toLabel: String,
    card: suspend () -> ByteArray? = { null },
): String {
    val style = uk.noammm.kav.Prefs.linkStyle(ctx).resolved(uk.noammm.kav.Prefs.lang(ctx).code,
        uk.noammm.kav.Prefs.stopNamesEnglish(ctx), uk.noammm.kav.Prefs.look(ctx).name.lowercase(Locale.US))
    val (fromName, toName) = sharedNames(trip, fromLabel, toLabel, uk.noammm.kav.Prefs.favourites(ctx))
    return if (RailTrip.owns(trip)) {
        val picture = card()
        val link = withContext(Dispatchers.IO) {
            uk.noammm.kav.data.TripShare.create(checkNotNull(KavPlan.sharePayload(trip, fromName, toName, r, rides = false, style = style)), style.expiryDays, picture)
        }
        railShare(trip, r, fromName, toName, link)
    } else {
        val (egg, busNearby) = if (trip.rides.isNotEmpty() && style.apps.any { it == "egg" || it == "bnb" })
            withContext(Dispatchers.IO) { HopOnShare.links(ctx, trip, r) } else null to null
        val colors = routeTints(trip.legs.filter { it.shape.size >= 2 && it.kind != Transit.LegKind.WALK }, r)
            .map { "#%06x".format(Locale.US, it.toArgb() and 0xffffff) }
        val picture = card()
        val url = withContext(Dispatchers.IO) {
            uk.noammm.kav.data.TripShare.create(checkNotNull(KavPlan.sharePayload(trip, fromName, toName, r, rides = KavPlan.owns(trip),
                egg = egg, busNearby = busNearby, colors = colors, style = style)), style.expiryDays, picture)
        }
        T("$fromName → $toName\n$url", "$fromName ← $toName\n$url")
    }
}

// The names a shared trip's ends go by in its message and links, which other people and apps see: the places
// themselves, never a favourite's own name, and the sender's own location as a pinned one.
internal fun sharedNames(trip: Transit.Itinerary, fromLabel: String, toLabel: String, favourites: List<Favourite>): Pair<String, String> {
    fun shared(label: String, at: Pair<Double, Double>?) =
        favourites.realName(label, at).let { if (it in HERE) T("Pinned location", "מיקום מסומן") else it }
    return shared(fromLabel, trip.legs.firstNotNullOfOrNull { it.shape.firstOrNull() }) to
        shared(toLabel, trip.legs.lastOrNull { it.shape.isNotEmpty() }?.shape?.lastOrNull())
}

// An Israel Railways journey is shared in words: each with its times and platforms,
// and a link preserving the selected trip.
internal fun railShare(trip: Transit.Itinerary, r: Transit.Resolved, fromLabel: String, toLabel: String,
    link: String): String {
    fun at(stop: Int, platform: String) = r.stopName(stop).orEmpty() +
        if (platform.isNotBlank()) T(" (platform $platform)", " (רציף $platform)") else ""
    val trains = trip.rides.map { ride ->
        val number = r.line(ride.lineId)?.number.orEmpty()
        val leaves = hm.format(Date(ride.dep * 1000))
        val reaches = hm.format(Date(ride.arr * 1000))
        T("$leaves train $number, ${at(ride.fromStop, r.platform(ride))} → $reaches ${at(ride.toStop, r.alightPlatform(ride))}",
            "$leaves רכבת $number, ${at(ride.fromStop, r.platform(ride))} ← $reaches ${at(ride.toStop, r.alightPlatform(ride))}")
    }
    return (listOf(T("$fromLabel → $toLabel", "$fromLabel ← $toLabel")) + trains + link).joinToString("\n")
}

private val hm get() = clockFormat()

// A stop on the trip page, named as navigation names it: as the setting has stop names shown (StopNames), whoever
// planned the trip.
internal fun stopRowName(r: Transit.Resolved, stopId: Int, fallback: String): String = r.stopName(stopId) ?: fallback


@Composable
fun TripDetailScreen(
    model: KavModel,
    trip: Transit.Itinerary,
    resolved: Transit.Resolved,
    fromLabel: String,
    toLabel: String,
    onBack: () -> Unit,
    startInNavigation: Boolean = false,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    onNavigating: (Boolean) -> Unit = {},
    onHome: () -> Unit = onBack,
) {
    val r = rememberFresher(trip, resolved)
    PlannedPlatforms(trip, r)
    var tracking by remember { mutableStateOf<Pair<Transit.Leg, Int>?>(null) }
    var navigating by remember(trip) { mutableStateOf(startInNavigation) }
    var planFromNavigation by remember(trip) { mutableStateOf(false) }
    LaunchedEffect(navigating) { onNavigating(navigating) }
    DisposableEffect(Unit) { onDispose { onNavigating(false) } }
    fun leavePlan() {
        if (planFromNavigation) { planFromNavigation = false; navigating = true } else onBack()
    }
    androidx.activity.compose.BackHandler {
        when {
            navigating -> onHome()
            tracking != null -> tracking = null
            else -> leavePlan()
        }
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // The boards of the trip's stops asked ahead, so the one tapped opens with its provider's times: where it boards,
    // changes and gets off first, then the stops along its rides.
    LaunchedEffect(trip) {
        kotlinx.coroutines.delay(1_000)
        val rides = trip.legs.filter { it.kind == Transit.LegKind.RIDE }
        val stops = rides.flatMap { listOf(it.fromStop, it.toStop) } + rides.flatMap { it.stops }
        BoardsAhead.stops(ctx, stops.mapNotNull { r.stop(it)?.code?.toIntOrNull() }, 16)
    }
    // The timetable route with this ride's number that leaves its boarding stop, matched by the stop's public code.
    val openLine: (Transit.Leg, Transit.Resolved) -> Unit = { ride, res ->
        scope.launch {
            val code = res.stop(ride.fromStop)?.code?.toIntOrNull() ?: return@launch
            val number = ride.shortName.ifBlank { res.line(ride.lineId)?.number.orEmpty() }
            val net = model.net ?: withContext(Dispatchers.Default) { uk.noammm.kav.loadNet(ctx) }.also { model.net = it }
            val found = withContext(Dispatchers.Default) {
                val stop = net.code.indexOfFirst { it == code }.takeIf { it >= 0 } ?: return@withContext null
                val route = (net.dStart[stop] until net.dStart[stop + 1]).asSequence()
                    .map { net.tripRoute[net.tripOf(net.dConn[it])] }
                    .firstOrNull { net.rShort[it] == number } ?: return@withContext null
                route to stop
            } ?: return@launch
            openTimetableLine(model, found.first, found.second)
        }
    }
    var sharingOpen by remember(trip) { mutableStateOf(false) }
    FreshAlerts(trip.rides.map { it to r })
    FreshCrowd(trip.rides.map { it to r })
    CompositionLocalProvider(LocalLineOpener provides openLine) {
    Box(Modifier.fillMaxSize()) {
    val plan: @Composable () -> Unit = {
        TripDetailBody(trip, r, fromLabel, toLabel, onBack = ::leavePlan,
            me = model.here?.takeIf { model.hereFresh }, heading = model.heading,
            onMapShown = { model.tripMapShown = it },
            onTrack = { leg, stopId -> tracking = leg to stopId },
            onStart = { navigating = true; planFromNavigation = false; onStart() },
            onReport = { model.report = it },
            onShare = { sharingOpen = true },
            pay = if (Shown.payments) { leg, ride -> TripPay(model, leg, ride, r, trip) } else null)
    }
    androidx.compose.animation.AnimatedContent(
        targetState = Triple(tracking != null, navigating || planFromNavigation, tracking),
        transitionSpec = {
            val goingDeeper = (targetState.first || targetState.second) &&
                !(initialState.first || initialState.second)
            if (goingDeeper) forward() else backward()
        },
        label = "trip",
    ) { (isTracking, underWay, target) ->
        when {
            isTracking && target != null ->
                LiveLocationScreen(target.first, target.second, r) { tracking = null }
            // Under way, navigation stays as it is under the trip's plan, its map just where it was, and the plan slides
            // over it and away as any screen does.
            underWay -> Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalBottomBarInset provides WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()) {
                NavigateScreen(
                    model, trip, r, fromLabel, toLabel,
                    onStop = { navigating = false; onEnd() },
                    onExit = onHome,
                    onPlan = { navigating = false; planFromNavigation = true },
                )
                }
                androidx.compose.animation.AnimatedVisibility(
                    planFromNavigation,
                    enter = forward().targetContentEnter,
                    exit = backward().initialContentExit,
                ) { Box(Modifier.fillMaxSize().background(K.bg)) { plan() } }
            }
            else -> plan()
        }
    }
    // The trip's sharing is made ready while its page shows, so the sheet opens on it done; the Link customization is
    // read again as the sheet opens.
    val share = rememberSharePrep(trip, r, fromLabel, toLabel, active = !navigating && tracking == null, fresh = sharingOpen)
    SharePicture(share, r)
    if (sharingOpen) ShareSheet(share, r) { sharingOpen = false }
    }
    }
}

@Composable
private fun TripDetailBody(
    trip: Transit.Itinerary,
    r: Transit.Resolved,
    fromLabel: String,
    toLabel: String,
    onBack: () -> Unit,
    me: Pair<Double, Double>?,
    heading: Float?,
    onMapShown: (Boolean) -> Unit,
    onTrack: (Transit.Leg, Int) -> Unit,
    onStart: () -> Unit,
    onReport: (ReportFor) -> Unit,
    onShare: () -> Unit,
    pay: (@Composable (legIndex: Int, ride: Transit.Leg) -> Unit)? = null,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(K.gap3).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack)
            Spacer(Modifier.width(K.gap3))
            Text(T("Trip", "נסיעה"), style = K.type.title, maxLines = 1, modifier = Modifier.weight(1f))
            ShareButton(onShare)
            val taxiOnly = trip.legs.any { it.kind == Transit.LegKind.TAXI } &&
                trip.legs.none { it.kind == Transit.LegKind.RIDE }
            if (!taxiOnly) {
                Spacer(Modifier.width(K.gap2))
                StartButton(onStart)
            }
        }


        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomBarInset.current)) {
            // Where you are and which way you face before a trip starts, as while it's under way.
            RouteMap(trip, r, modifier = Modifier.padding(horizontal = K.gap3, vertical = K.gap2),
                me = me, heading = heading, onShown = onMapShown)
            Summary(trip, r)
            Spacer(Modifier.height(K.gap3))
            Timeline(trip, r, fromLabel, toLabel, onTrack, onReport, pay)
            Spacer(Modifier.height(K.gap8))
        }
    }
}

@Composable
private fun Summary(trip: Transit.Itinerary, r: Transit.Resolved) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap4)
            .glassSurface(K.rCard).padding(K.gap4),
    ) {
        Text(dur((trip.arr - trip.dep).toInt()), style = K.type.largeTitle.tabular)
        // When it arrives, what it costs and the rest of what's true of it, in one line: facts, not buttons.
        val facts = ArrayList<String>()
        facts.add(T("Arrives ${hm.format(Date(trip.arr * 1000))}", "הגעה ב-${hm.format(Date(trip.arr * 1000))}"))
        val amount = Shown.fareProfile.price(trip)
        if (amount >= 0) facts.add("%s%.2f".format(Locale.US, trip.currency, amount / 100.0))
        if (Shown.accessibility && trip.accessible) facts.add(T("Wheelchair accessible", "נגיש לכיסא גלגלים"))
        if (Shown.co2) uk.noammm.kav.data.Emissions.grams(trip, r).takeIf { it >= 0 }?.let { facts.add(co2(it)) }
        Text(facts.joinToString(" · "), style = K.type.subhead.tabular)
        Spacer(Modifier.height(K.gap3))
        RouteStrip(trip, r)
        RouteTags(trip, Modifier.padding(top = K.gap3))
    }
}

// What a route is best at, as small ovals on its card and its page: a late train's in red.
@Composable
internal fun RouteTags(trip: Transit.Itinerary, modifier: Modifier = Modifier) {
    val tags = trip.tags.mapNotNull { tag -> KavPlan.tagWord(tag)?.let { it to KavPlan.isLate(tag) } }
    // Tags that come with a later answer open in place, and go the same way.
    var tagged by remember { mutableStateOf(tags) }
    if (tags.isNotEmpty()) tagged = tags
    androidx.compose.animation.AnimatedVisibility(
        tags.isNotEmpty(), modifier,
        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap1), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            tagged.forEach { (word, late) ->
                Text(
                    word, style = K.type.caption, color = if (late) K.critical else K.textSecondary, maxLines = 1,
                    modifier = Modifier.clip(K.capsule).background(K.fill).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// train: a late one says by how much, as the railway's own list does.
private fun depNote(deps: List<Transit.Departure>, train: Boolean = false): String? {
    val d = deps.firstOrNull() ?: return null
    val state = when (d.state) {
        Transit.TimeState.CANCELED -> T("Canceled for this station", "מבוטל לתחנה זו")
        Transit.TimeState.FREQUENCY -> null
        Transit.TimeState.OUT_OF_SHAPE -> T("Deviated from route", "סוטה מהמסלול")
        Transit.TimeState.REAL_TIME -> null
        Transit.TimeState.REAL_TIME_HIGH -> T("Arrival time is accurate", "זמן ההגעה מדויק")
        Transit.TimeState.REAL_TIME_MEDIUM -> T("Arrival time is fairly accurate", "זמן ההגעה מדויק למדי")
        Transit.TimeState.REAL_TIME_LOW -> T("Arrival time may not be accurate", "ייתכן שזמן ההגעה אינו מדויק")
        Transit.TimeState.REAL_TIME_DROPPED -> T("Real-Time unavailable", "אין מידע בזמן אמת")
        Transit.TimeState.STATISTICAL -> T("Based on previous arrivals", "מבוסס על הגעות קודמות")
        Transit.TimeState.STATIC -> T("Scheduled time", "לפי לוח זמנים")
    }
    val alert = when {
        d.alert == 4 -> T("Service alert on this line", "התרעת שירות בקו זה")
        d.alert == 3 -> T("Service change on this line", "שינוי שירות בקו זה")
        d.status == 2 -> (if (train) lateBy(d)?.let(::lateLabel) else null) ?: T("Running late", "באיחור")
        d.status == 4 -> T("Running ahead of schedule", "מקדים את הלוח")
        else -> null
    }
    return listOfNotNull(state, alert).joinToString(" · ").ifBlank { null }
}

fun co2(g: Int): String = if (g < 1000) T("$g g CO2e", "$g גרם CO2e") else T("%.2f kg CO2e", "%.2f ק\"ג CO2e").format(Locale.US, g / 1000.0)

@Composable
private fun Timeline(
    trip: Transit.Itinerary,
    r: Transit.Resolved,
    fromLabel: String,
    toLabel: String,
    onTrack: (Transit.Leg, Int) -> Unit,
    onReport: (ReportFor) -> Unit,
    pay: (@Composable (legIndex: Int, ride: Transit.Leg) -> Unit)? = null,
) {
    val shaped = trip.legs.filter { it.shape.size >= 2 && it.kind != Transit.LegKind.WALK }
    val fares = remember(trip) { rideFares(trip) }
    val tints = routeTints(shaped, r)
    fun tintOf(l: Transit.Leg) = tints.getOrNull(shaped.indexOf(l)) ?: K.route
    val walk = Seg(K.dim, walk = true)
    val lastRide = trip.legs.lastOrNull { it.kind == Transit.LegKind.RIDE }
    fun nextFromSameStop(i: Int): Int {
        var j = i + 1
        while (j < trip.legs.size) {
            val l = trip.legs[j]
            when {
                // Not across a stop on the way: the ride on from it is boarded on its own, under the stop's row.
                l.visit != null -> return -1
                l.kind == Transit.LegKind.RIDE -> return if (l.fromStop > 0 && l.fromStop == trip.legs[i].toStop) j else -1
                l.kind == Transit.LegKind.WAIT -> j++
                l.kind == Transit.LegKind.WALK && (l.pathway || (l.minutes < 1 && l.meters <= 30)) -> j++
                else -> return -1
            }
        }
        return -1
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = K.gap3)
            .panel(K.rCard).padding(vertical = K.gap2),
    ) {
        val start = rememberTripEnd(fromLabel, trip.legs.firstOrNull()?.shape?.firstOrNull())
        Rail(Mark.START, null, walk, node = endNode(start)) {
            Endpoint(fromLabel, hm.format(Date(trip.dep * 1000)), T("Leave at", "יציאה בשעה"), start)
        }
        var boarded = -1
        trip.legs.forEachIndexed { i, l ->
            // A stop on the way, before the leg on from it: its name and when the rider is there, as a trip page
            // has its extra stop; how long and until when, where they asked to stay.
            l.visit?.let { at ->
                Rail(Mark.STOP, walk, walk, K.textSecondary) {
                    val name = at.place.ifBlank { T("Stop on the way", "עצירה בדרך") }
                    val until = hm.format(Date(at.until * 1000))
                    if (at.stay) Step("$name · ${durMinutes(at.minutes)}", T("until $until", "עד $until"))
                    else Step(name, hm.format(Date(at.from * 1000)))
                }
            }
            when (l.kind) {
                Transit.LegKind.WALK -> {
                    val prev = trip.legs.getOrNull(i - 1)
                    // Walks one after another are one walk, but for the one on from a stop on the way.
                    if (prev?.kind != Transit.LegKind.WALK || l.visit != null) {
                        var mins = 0; var metres = 0; var j = i
                        while (j < trip.legs.size && trip.legs[j].kind == Transit.LegKind.WALK && (j == i || trip.legs[j].visit == null)) {
                            mins += trip.legs[j].minutes; metres += trip.legs[j].meters; j++
                        }
                        if (mins >= 1 || metres > 30) Rail(Mark.NONE, walk, walk) {
                            Step(walkLabel(metres, mins), null)
                        }
                    }
                }
                Transit.LegKind.TAXI -> Rail(Mark.NONE, Seg(K.muted), Seg(K.muted)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        GettPill(l)
                        Text(durMinutes(l.minutes), style = K.type.subhead)
                    }
                }
                Transit.LegKind.BIKE -> Rail(Mark.NONE, Seg(K.muted), Seg(K.muted)) {
                    Step(cycleLabel(l), null)
                }
                Transit.LegKind.RIDE -> {
                    val wait = trip.legs.getOrNull(i - 1)?.takeIf { w -> w.kind == Transit.LegKind.WAIT }
                    val tint = tintOf(l)
                    val ride = Seg(tint)
                    val mode = legMode(l, r)
                    if (boarded != i) {
                        val board = r.stop(l.fromStop)
                        Rail(Mark.STOP, walk, ride, tint) {
                            StopRowDetail(
                                stopRowName(r, l.fromStop, T("Board here", "עלייה כאן")), board?.code,
                                hm.format(Date(l.dep * 1000)), mode,
                                platform = r.platform(l, wait),
                                stopId = l.fromStop,
                                streetView = (board?.point ?: l.shape.firstOrNull())?.let { lookAt(it, l, r) },
                            )
                            Spacer(Modifier.height(K.gap2))
                            BoardCard(l, wait, r, onTrack, onReport) { RideSummary(l, r, i, fares[i], pay) }
                        }
                    }
                    val alight = r.stop(l.toStop)
                    val n = nextFromSameStop(i)
                    if (n >= 0) {
                        val next = trip.legs[n]
                        val nextWait = trip.legs.getOrNull(n - 1)?.takeIf { w -> w.kind == Transit.LegKind.WAIT }
                        // A train as the railway expects it in, a bus as planned.
                        val arrive = hm.format(Date((if (r.isTrain(l)) r.alightAt(l) else l.arr) * 1000))
                        // From train to train: where the next one leaves from, against where this one comes in.
                        val change = trainChange(l, next, nextWait, r)
                        Rail(Mark.STOP, ride, Seg(tintOf(next)), tintOf(next)) {
                            StopRowDetail(
                                stopRowName(r, l.toStop, T("Change here", "החלפה כאן")), alight?.code,
                                hm.format(Date(next.dep * 1000)), legMode(next, r),
                                platform = r.platform(next, nextWait),
                                stopId = l.toStop,
                                streetView = alight?.point?.let { lookAt(it, l, r) },
                                note = T("Change here · you arrive $arrive", "החלפה כאן · מגיעים ב-$arrive"),
                            )
                            change?.let {
                                Spacer(Modifier.height(K.gap2))
                                ChangeBlock(it)
                            }
                            Spacer(Modifier.height(K.gap2))
                            BoardCard(next, nextWait, r, onTrack, onReport) { RideSummary(next, r, n, fares[n], pay) }
                        }
                        boarded = n
                    } else {
                        // Getting off one train to walk to another: the platform it comes in at.
                        val walkingToTrain = r.isTrain(l) &&
                            trip.legs.drop(i + 1).firstOrNull { it.kind == Transit.LegKind.RIDE }?.let(r::isTrain) == true
                        Rail(Mark.STOP, ride, walk, tint) {
                            StopRowDetail(
                                stopRowName(r, l.toStop, T("Get off here", "ירידה כאן")), alight?.code,
                                hm.format(Date((if (r.isTrain(l)) r.alightAt(l) else l.arr) * 1000)), mode,
                                platform = if (walkingToTrain) r.alightPlatform(l) else "",
                                stopId = if (l === lastRide) l.toStop else -1,
                                streetView = alight?.point?.let { lookAt(it, l, r) },
                            )
                        }
                    }
                }
                else -> {}
            }
        }
        val end = rememberTripEnd(toLabel, trip.legs.lastOrNull()?.shape?.lastOrNull())
        Rail(Mark.END, walk, null, node = endNode(end)) {
            // A trip ending on a train arrives when the train does, as the railway expects it in.
            val arrives = trip.legs.lastOrNull()?.takeIf { it.kind == Transit.LegKind.RIDE && r.isTrain(it) }?.let(r::alightAt) ?: trip.arr
            Endpoint(toLabel, hm.format(Date(arrives * 1000)), T("Arrive", "הגעה"), end)
        }
    }
}

// Under a ride's line: how many stops and minutes it rides and its own fare (rideFares), and paying for it.
@Composable
private fun RideSummary(
    l: Transit.Leg, r: Transit.Resolved, legIndex: Int, fare: Int?,
    pay: (@Composable (legIndex: Int, ride: Transit.Leg) -> Unit)?,
) {
    Separator(0.dp)
    Box(Modifier.padding(vertical = K.gap1)) {
        val price = fare?.let { "%s%.2f".format(Locale.US, l.currency, Shown.fareProfile.price(it, l.currency) / 100.0) }
        if (l.options.size == 1) RideStops(l, r) { Step(rideLabel(l), price) }
        else if (price != null) Step(T("Fare", "מחיר"), price)
    }
    pay?.let { Box(Modifier.padding(bottom = K.gap2)) { it(legIndex, l) } }
}

// Each ride's own fare, by leg index. A plan gives every ride of a stretch one ticket covers the whole ticket's price
// (three rides of 42.00 on a 42.00 trip), so a price goes on the first ride of each such stretch only; and none goes on
// any ride when the prices don't add up to the trip's, as a wrong price is worse than none.
internal fun rideFares(trip: Transit.Itinerary): Map<Int, Int> {
    val rides = trip.legs.withIndex().filter { it.value.kind == Transit.LegKind.RIDE && it.value.fare >= 0 }
    if (rides.isEmpty()) return emptyMap()
    if (rides.sumOf { it.value.fare } == trip.fare) return rides.associate { it.index to it.value.fare }
    val stretches = rides.filterIndexed { n, ride -> n == 0 || rides[n - 1].value.fare != ride.value.fare }
    if (trip.fare >= 0 && stretches.sumOf { it.value.fare } != trip.fare) return emptyMap()
    return stretches.associate { it.index to it.value.fare }
}

private fun walkLabel(metres: Int, mins: Int): String {
    val d = if (metres > 0) distanceLabel(metres.toDouble()) else null
    val m = if (mins >= 1) durMinutes(mins) else null
    val walk = T("Walk", "הליכה")
    return listOfNotNull(walk, d, m).let {
        if (it.size == 3) "$walk ${it[1]} · ${it[2]}" else it.joinToString(" ")
    }
}

// The ride's step opens to every stop it passes on the way.
@Composable
private fun RideStops(l: Transit.Leg, r: Transit.Resolved, label: @Composable () -> Unit) {
    var open by remember(l) { mutableStateOf(false) }
    val between = l.stops.drop(1).dropLast(1)
    Column(Modifier.fillMaxWidth()
        .then(if (between.isEmpty()) Modifier else Modifier.clickable(role = Role.Button) { open = !open })) {
        label()
        // The stops on the way unfold downwards under the ride and fold back into it.
        androidx.compose.animation.AnimatedVisibility(
            open,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            Column(Modifier.padding(start = 23.dp, top = K.gap2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                between.forEach { id ->
                    Text(r.stopName(id) ?: "…", fontSize = 14.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun RideDetails(ride: Transit.Leg, r: Transit.Resolved, lead: (@Composable () -> Unit)? = null) {
    val own = remember(ride) { ride.copy(alternatives = emptyList()) }
    RideStops(own, r) { Step(rideLabel(own), null, lead) }
}

// The ride's stops and time; for a ride several lines can take, from the least to the most of them when they differ.
internal fun rideLabel(l: Transit.Leg): String {
    val counts = l.options.map { it.stops.size - 1 }.filter { it > 0 }
    val fewest = counts.minOrNull() ?: 0
    val most = counts.maxOrNull() ?: 0
    val stops = when {
        fewest != most -> T("$fewest-$most stops", "$fewest-$most תחנות")
        most == 1 -> T("1 stop", "תחנה 1")
        else -> T("$most stops", "$most תחנות")
    }
    val minutes = l.options.filter { it.arr > it.dep }.map { it.minutes }.ifEmpty { listOf(l.minutes) }
    val quickest = minutes.min()
    val slowest = minutes.max()
    val time = when {
        quickest == slowest -> durMinutes(quickest)
        slowest < 60 -> "$quickest-$slowest " + T("min", "דק׳")
        else -> durMinutes(quickest) + T(" to ", " עד ") + durMinutes(slowest)
    }
    return if (most > 0) T("Ride $stops · ", "נסיעה $stops · ") + time
        else T("Ride ", "נסיעה ") + time
}

private class Seg(val tint: Color, val walk: Boolean = false)

private enum class Mark { START, END, STOP, NONE }

@Composable
private fun Rail(
    mark: Mark,
    above: Seg?,
    below: Seg?,
    tint: Color = K.text,
    // How far down the row its mark is: level with the row's name.
    node: Dp = 21.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The rail is drawn behind the row as tall as it is now. A canvas filling the row's intrinsic height would make the
    // row as tall as a step still unfolding, and what's under it would jump.
    Row(Modifier.fillMaxWidth().drawBehind {
            val x = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width - 22.dp.toPx() else 22.dp.toPx()
            val nodeY = node.toPx().coerceAtMost(size.height * .5f)
            val ring = 7.dp.toPx()
            fun stretch(s: Seg?, from: Float, to: Float) {
                if (s == null || to <= from) return
                if (!s.walk) {
                    drawLine(s.tint, Offset(x, from), Offset(x, to), 5.dp.toPx(), StrokeCap.Butt)
                    return
                }
                val step = 6.dp.toPx()
                var y = from + step / 2
                while (y < to) { drawCircle(s.tint, 1.7.dp.toPx(), Offset(x, y)); y += step }
            }
            val gap = if (mark == Mark.NONE) 0f else ring
            stretch(above, 0f, nodeY - gap)
            stretch(below, nodeY + gap, size.height)
            val at = Offset(x, nodeY)
            when (mark) {
                Mark.START -> drawCircle(K.text, ring - 1.5.dp.toPx(), at, style = Stroke(2.5.dp.toPx()))
                Mark.END -> drawCircle(K.text, ring - 1.dp.toPx(), at)
                Mark.STOP -> drawCircle(tint, ring - 1.5.dp.toPx(), at, style = Stroke(3.dp.toPx()))
                Mark.NONE -> {}
            }
        }) {
        Spacer(Modifier.width(44.dp))
        Column(
            Modifier.weight(1f).padding(end = K.gap4, top = K.gap2, bottom = K.gap2),
            content = content,
        )
    }
}

// end: the place's tile, as a stop has its photo: a favourite's icon, a place's photo from Google Maps; none for where
// you are or a pinned spot.
@Composable
private fun Endpoint(label: String, time: String, when_: String, end: TripEnd? = null) {
    // The name, with nothing under it, centred on the tile (two lines of it as one block), and the time's first line
    // level with the name's, as a stop's row has its time beside its name.
    Layout(
        {
            if (end != null) TripEndTile(end)
            Text(label, style = K.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TimeColumn(time, when_)
        },
        Modifier.fillMaxWidth(),
    ) { parts, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val tile = if (end != null) parts[0].measure(loose) else null
        val times = parts[parts.size - 1].measure(loose)
        val start = if (tile != null) tile.width + K.gap3.roundToPx() else 0
        val room = (constraints.maxWidth - start - K.gap2.roundToPx() - times.width).coerceAtLeast(0)
        val name = parts[parts.size - 2].measure(loose.copy(maxWidth = room))
        val top = ((tile?.height ?: 0) - name.height).coerceAtLeast(0) / 2
        layout(constraints.maxWidth, maxOf(tile?.height ?: 0, top + name.height, top + times.height)) {
            tile?.placeRelative(0, 0)
            name.placeRelative(start, top)
            times.placeRelative(constraints.maxWidth - times.width, top)
        }
    }
}

// Where an end's rail mark goes: level with its name, centred on its tile where it has one.
private fun endNode(end: TripEnd?): Dp = if (end != null) K.gap2 + 20.dp else 21.dp

internal fun legMode(ride: Transit.Leg, r: Transit.Resolved): Mode =
    modeOf(r.routeType(r.line(ride.lineId)?.agencyId ?: -1))

@Composable
private fun StopRowDetail(
    name: String,
    code: String?,
    time: String,
    mode: Mode? = null,
    platform: String = "",
    stopId: Int = -1,
    note: String? = null,
    // Where the stop is, for a look at it in Street View: one that's hidden or has no sign is easier to find.
    streetView: StreetLook? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        if (mode == Mode.BUS && streetView != null) {
            StopGlyphOrPhoto(stopId, null, photo = StopLook(streetView, name))
            Spacer(Modifier.width(K.gap3))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = K.type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!code.isNullOrBlank()) Text(T("Stop $code", "תחנה $code"), style = K.type.subhead)
            // A note that comes with live times opens under the name instead of pushing the timeline down at once.
            var noted by remember { mutableStateOf(note) }
            if (note != null) noted = note
            androidx.compose.animation.AnimatedVisibility(
                note != null,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ) { Text(noted.orEmpty(), style = K.type.subhead) }
            if (platform.isNotBlank()) {
                Spacer(Modifier.height(K.gap1))
                PlatformTag(platform)
            }
        }
        Spacer(Modifier.width(K.gap2))
        TimeColumn(time)
    }
}

@Composable
private fun Step(label: String, trailing: String?, glyph: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) { glyph(); Spacer(Modifier.width(K.gap2)) }
        Text(label, style = K.type.subhead, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = K.type.subhead.tabular)
    }
}

@Composable
private fun BoardCard(
    ride: Transit.Leg,
    wait: Transit.Leg?,
    r: Transit.Resolved,
    onTrack: (Transit.Leg, Int) -> Unit,
    onReport: (ReportFor) -> Unit,
    summary: @Composable ColumnScope.() -> Unit = {},
) {
    val now = System.currentTimeMillis() / 1000
    val all = Transit.boardingOptions(ride, wait)
    // Each line's next departures, which its row shows, and by which the lines are ordered (boardingOrder).
    val depsOf = all.map { (option, boarding) ->
        androidx.compose.runtime.key(option.lineId) { rememberLineDepartures(option, boarding, r, now).filter { it.timeUtc >= now - 60 } }
    }
    val options = boardingOrder(depsOf, null, ARRIVALS_SHOWN).map { all[it] to depsOf[it] }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.fill).padding(horizontal = K.gap3, vertical = K.gap1)) {
        if (options.size > 1) Text(T("Take one of these lines", "בחרו אחד מהקווים האלה"), fontSize = 14.sp, color = K.dim)
        options.forEachIndexed { index, (pair, deps) ->
            val (option, boarding) = pair
            if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = K.gap2).height(1.dp).background(K.border))
            BoardOption(option, boarding, r, deps, onTrack, onReport, showPlatform = options.size > 1)
        }
        summary()
    }
}

// A leg's lines in the order they're shown: each by its next departure, live where known, again as the times come, the
// soonest first and one with none left last. most: how many a display shows; the picked one always shows. The line
// taken isn't changed by the order: the plan's, or the rider's pick.
internal fun boardingOrder(depsOf: List<List<Transit.Departure>>, picked: Int?, most: Int = Int.MAX_VALUE): List<Int> {
    val sorted = depsOf.indices.sortedWith(compareBy({ depsOf[it].firstOrNull()?.timeUtc ?: Long.MAX_VALUE }, { it }))
    val shown = sorted.take(most)
    return if (picked == null || picked !in depsOf.indices || picked in shown) shown else shown + picked
}

// The lines a route's card lists with their next arrivals.
internal const val ARRIVALS_SHOWN = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoardOption(
    ride: Transit.Leg,
    wait: Transit.Leg?,
    r: Transit.Resolved,
    lineDeps: List<Transit.Departure>,
    onTrack: (Transit.Leg, Int) -> Unit,
    onReport: (ReportFor) -> Unit,
    showPlatform: Boolean = false,
) {
    val info = r.line(ride.lineId)
    val agency = info?.agencyId ?: -1
    val rt = if (info != null) r.routeType(agency) else 3
    val plate = linePlate(rt, agency) { Operators.of(r, agency) }
    val now = System.currentTimeMillis() / 1000
    Column(Modifier.fillMaxWidth().padding(vertical = K.gap2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val said = lineDescription(rt, ride.shortName.ifBlank { null } ?: info?.number)
            // The strip look for buses, when chosen in Settings.
            BadgeLookSwitch(K.lineBadgeAppearance == LineBadgeAppearance.STRIP && modeOf(rt) == Mode.BUS) { strip ->
                if (strip) Box(Modifier.clearAndSetSemantics { contentDescription = said }) {
                    LinePlateBadge(ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "…", Mode.BUS, plate,
                        look = LineBadgeAppearance.STRIP)
                } else Row(
                    Modifier.clearAndSetSemantics { contentDescription = said }
                        .clip(RoundedCornerShape(K.rBadge)).background(plate?.fill ?: K.surface2)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AgencyMark(rt, agency, plate?.ink ?: K.muted, 15.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        ride.shortName.ifBlank { null } ?: info?.number?.ifBlank { null } ?: "…",
                        style = K.type.bodyStrong, color = plate?.ink ?: K.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
            }
            Spacer(Modifier.width(K.gap3))
            Column(Modifier.weight(1f)) {
                Text(
                    info?.destination?.ifBlank { null }?.let { T("to ${shownName(it)}", "לכיוון ${shownName(it)}") } ?: "",
                    fontSize = 14.sp, color = K.muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(rideBy(rt, r.agencyName(agency)), fontSize = 12.sp, color = K.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // A platform given once the operator names it, and the report flag, slide in at the row's end.
            val platform = if (showPlatform) r.platform(ride, wait) else ""
            var platformShown by remember { mutableStateOf(platform) }
            if (platform.isNotBlank()) platformShown = platform
            androidx.compose.animation.AnimatedVisibility(
                platform.isNotBlank(),
                enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ) {
                Row {
                    Spacer(Modifier.width(K.gap2))
                    PlatformTag(platformShown)
                }
            }
            val report = rideReport(ride, r, null, minOf(ride.dep, now))
            var reportShown by remember { mutableStateOf(report) }
            if (report != null) reportShown = report
            androidx.compose.animation.AnimatedVisibility(
                report != null,
                enter = androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
            ) {
                Row {
                    Spacer(Modifier.width(K.gap2))
                    ReportButton { reportShown?.let(onReport) }
                }
            }
        }
        if (showPlatform) {
            Spacer(Modifier.height(K.gap1))
            RideDetails(ride, r)
        }
        val deps = lineDeps.filter { it.timeUtc >= now - 60 }.take(3)
        // The departures open under the line once they're in; what's known of them fades from one note to the next.
        var depsShown by remember { mutableStateOf(deps) }
        if (deps.isNotEmpty()) depsShown = deps
        androidx.compose.animation.AnimatedVisibility(
            deps.isNotEmpty(),
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            Column {
                Spacer(Modifier.height(K.gap2))
                Text(T("Departures", "יציאות"), fontSize = 14.sp, color = K.dim)
                Spacer(Modifier.height(K.gap1))
                DepartureTimes(depsShown, now)
                CrowdMeter(ride, r, depsShown.firstOrNull())
                androidx.compose.animation.AnimatedContent(
                    depNote(depsShown, r.isTrain(ride)),
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) togetherWith
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)))
                            .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f) })
                    },
                    label = "depNote",
                ) { said -> if (said != null) Text(said, fontSize = 14.sp, color = K.dim, modifier = Modifier.padding(top = K.gap1)) }
            }
        }
        AlertRow(lineAlert(r, ride, wait))
        // Not boarded yet, so the soonest bus of the line still to come, as on the trip's map.
        val bus = shownArrival(ride, ride to wait, r, now)?.takeIf { it.hasLocation }
        // With no position for any, the soonest timed live: Live location looks for it on Kav's server, or says it has none.
        val timed = deps.firstNotNullOfOrNull { d ->
            r.live[Transit.ArrivalKey(ride.fromStop, d.tripId)]?.takeIf { it.rtUtc > 0 && it.status != 3 }
        }
        Spacer(Modifier.height(K.gap2))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap2), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
            LiveLocationButton(bus != null, bus?.let { freshColour(freshnessOf(it.sampleUtc, now)) } ?: K.realtime, compact = true) {
                onTrack((bus ?: timed)?.let { a ->
                    // Another train is another number: each run is a route of its own.
                    val number = a.vehicleId.substringAfterLast('_', "")
                        .takeIf { r.isTrain(ride) && KavLive.routeOf(a.vehicleId) != null && it.isNotEmpty() && it.all(Char::isDigit) }
                    ride.copy(tripId = a.tripId, shortName = number ?: ride.shortName)
                } ?: ride, ride.fromStop)
            }
            LocalLineOpener.current?.takeIf { !uk.noammm.kav.data.RailTrip.owns(ride.lineId) }?.let { open ->
                CardButton(T("All departures", "כל היציאות")) { open(ride, r) }
            }
        }
    }
}

// A small button inside a card: the secondary look at the size of the card's own lines, 40 high (48 to the touch).
@Composable
private fun CardButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.minimumInteractiveComponentSize().heightIn(min = 40.dp).clip(K.capsule).background(K.fill)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = K.gap4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = K.type.subhead, color = K.text, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

// Every time down the timeline in one column: centred, the same width, with what it means (Leave at, Arrive) under it.
@Composable
private fun TimeColumn(time: String, label: String? = null) {
    Column(Modifier.widthIn(min = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LiveText(time, style = K.type.subhead.tabular, color = K.text)
        label?.let { Text(it, style = K.type.caption, color = K.textTertiary) }
    }
}
