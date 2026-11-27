package uk.noammm.kav.ui

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.OnBoard
import uk.noammm.kav.fixTime
import uk.noammm.kav.hasPreciseLocation

// Following the bus the rider is already on. While Kav is in front and the phone moves along with one bus, Home's card
// offers to follow it; Follow asks where they get off and starts that ride as Get off here on a line's page does
// (OnBoard), with navigation and the trip notification. Kav asks the phone for no position of its own for this: it reads
// the positions the phone works out anyway (for Kav's own maps and trips, or for another app), and asks Kav's server for
// the buses around the rider only while those show it moving, or while a bus is offered. Nothing runs once Kav is off the
// screen, and nothing is kept. The switch is in the navigation screen's Trip settings, off until the rider turns it on.
internal object OnBus {
    private fun store(ctx: Context) = ctx.getSharedPreferences("kav_onbus", Context.MODE_PRIVATE)
    fun suggest(ctx: Context): Boolean = store(ctx).getBoolean("suggest", false)
    fun setSuggest(ctx: Context, on: Boolean) {
        store(ctx).edit().putBoolean("suggest", on).apply()
        this.on = on
        if (!on) picks = emptyList()
    }

    // The switch as the watch follows it, read when Kav's screen first shows.
    var on by mutableStateOf<Boolean?>(null)
        private set
    fun load(ctx: Context) { if (on == null) on = suggest(ctx) }

    // The buses the phone travels with now, for Home's card.
    var picks by mutableStateOf<List<BusPick>>(emptyList())

    // The bus Follow was tapped for, and its stops, while the sheet that asks where the rider gets off is open.
    var following by mutableStateOf<Pair<BusPick, OnBusStops>?>(null)

    // Kav's server is asked about the buses around the rider at most this often.
    const val ASK_S = 15L

    // Buses the rider said no to, until when (epoch seconds), so the same bus isn't offered again at once.
    private val declined = HashMap<String, Long>()
    fun decline(key: String, now: Long) { synchronized(declined) { declined[key] = now + 1800 } }
    fun declinedNow(key: String, now: Long): Boolean = synchronized(declined) { (declined[key] ?: 0L) > now }
}

internal class BusPick(val bus: KavLive.Bus, val line: KavLive.Line, val away: Double)

// Whether the phone travels with one bus: moving like a vehicle, and for three of the bus's own reports (each compared
// with where the phone was when the bus reported), within 100 m of it. Two buses that fit equally are both offered, so
// the rider picks. A bus stays offered while it stands at a stop with the rider; it goes once one of its reports is far
// from where the phone was then, or it is no longer around.
internal class RideDetector {
    private val track = ArrayList<Fix>()
    private val near = HashMap<String, LinkedHashMap<Long, Double>>()

    // A position the phone had. The phone may give one every second (a map following the rider) or one a minute: one
    // every GAP_S at most is kept, for TRACK_S. True when the phone moves like a vehicle now.
    fun add(fix: Fix): Boolean {
        val last = track.lastOrNull()
        if (last == null || fix.at >= last.at + GAP_S) {
            track.add(fix)
            while (track.size > 1 && track[0].at < fix.at - TRACK_S) track.removeAt(0)
        }
        return moving()
    }

    // At least 50 m and 3 m/s since the newest position WINDOW_S or more before the last.
    fun moving(): Boolean {
        val now = track.lastOrNull() ?: return false
        val before = track.lastOrNull { it.at <= now.at - WINDOW_S } ?: return false
        val secs = (now.at - before.at).coerceAtLeast(1L)
        val m = metres(before.lat, before.lon, now.lat, now.lon)
        return m >= 50.0 && m / secs >= 3.0
    }

    // Where the phone was at t, between the two positions around it; null before the first, after the last, or across a
    // gap too long to say.
    private fun phoneAt(t: Long): Pair<Double, Double>? {
        for (i in 0 until track.size - 1) {
            val a = track[i]
            val b = track[i + 1]
            if (t >= a.at && t <= b.at) {
                if (b.at - a.at > GAP_MAX_S) return null
                val f = if (b.at == a.at) 0.0 else (t - a.at).toDouble() / (b.at - a.at)
                return Pair(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
            }
        }
        return track.lastOrNull()?.takeIf { it.at == t }?.let { it.lat to it.lon }
    }

    fun offer(area: KavLive.Area): List<BusPick> {
        val there = HashSet<String>()
        val far = HashSet<String>()
        for (b in area.buses) {
            there.add(b.key)
            // A report from a moment the phone's place isn't known says nothing either way.
            val p = phoneAt(b.spot.at) ?: continue
            val d = metres(p.first, p.second, b.spot.lat, b.spot.lon)
            if (d <= NEAR_M) {
                val seen = near.getOrPut(b.key) { LinkedHashMap() }
                seen[b.spot.at] = d
                while (seen.size > 5) seen.remove(seen.keys.first())
            } else far.add(b.key)
        }
        near.keys.removeAll { it !in there || it in far }
        val ready = ArrayList<BusPick>()
        for (b in area.buses) {
            val seen = near[b.key] ?: continue
            if (seen.size < 3) continue
            val line = area.lines[b.route] ?: continue
            ready.add(BusPick(b, line, seen.values.average()))
        }
        ready.sortBy { it.away }
        val best = ready.firstOrNull() ?: return emptyList()
        val rival = ready.getOrNull(1)
        return if (rival != null && rival.away < best.away * 1.6 + 25.0) listOf(best, rival) else listOf(best)
    }

    private companion object {
        const val GAP_S = 5L
        const val TRACK_S = 300L
        const val WINDOW_S = 15L
        const val GAP_MAX_S = 90L
        const val NEAR_M = 100.0
    }
}

// The positions the phone works out anyway, for Kav's own maps and trips or for another app: Android's passive provider,
// which turns nothing on. Precise ones only.
private fun passiveFixes(ctx: Context, onFix: (Fix) -> Unit): () -> Unit {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return {}
    val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            if (location.hasAccuracy() && location.accuracy <= PRECISE_M) {
                onFix(Fix(location.latitude, location.longitude, location.fixTime(), location.speed, location.accuracy))
            }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    try {
        LocationManagerCompat.requestLocationUpdates(
            lm, LocationManager.PASSIVE_PROVIDER, LocationRequestCompat.Builder(1000L).build(), listener, Looper.getMainLooper(),
        )
    } catch (e: SecurityException) {
        return {}
    } catch (e: IllegalArgumentException) {
        return {}
    }
    return { try { LocationManagerCompat.removeUpdates(lm, listener) } catch (_: SecurityException) {} }
}

private const val PRECISE_M = 75f

// The watch, from Kav's root: only while the switch is on, precise location is allowed and Kav's screen is in front.
@Composable
internal fun RideWatch() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { OnBus.load(ctx) }
    val on = OnBus.on == true
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(on, lifecycle) {
        if (!on) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!hasPreciseLocation(ctx)) return@repeatOnLifecycle
            val fixes = Channel<Fix>(Channel.CONFLATED)
            val stop = passiveFixes(ctx) { fixes.trySend(it) }
            val det = RideDetector()
            var asked = 0L
            try {
                for (fix in fixes) {
                    val moving = det.add(fix)
                    val now = System.currentTimeMillis() / 1000
                    if (!moving && OnBus.picks.isEmpty() || now - asked < OnBus.ASK_S) continue
                    asked = now
                    val area = try {
                        withContext(Dispatchers.IO) { KavLive.area(KavLive.box(fix.lat, fix.lon, 0.5)) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    OnBus.picks = det.offer(area).filter { !OnBus.declinedNow(it.bus.key, now) }
                }
            } finally {
                stop()
                OnBus.picks = emptyList()
            }
        }
    }
}

// The bus's stops, its road, and which of the stops are still ahead of it.
internal class OnBusStops(val stops: List<Int>, val shape: List<Pair<Double, Double>>, val ahead: List<Int>)

internal fun onBusStops(net: Net, p: BusPick): OnBusStops {
    val first = KavLive.firstOf(p.bus.key)
    val stops: List<Int> = if (first == null) emptyList() else runCatching {
        KavLive.tripStops(net, p.line.number, p.line.name, first, p.bus.spot.lat, p.bus.spot.lon, p.line.type)
    }.getOrDefault(emptyList())
    val shape: List<Pair<Double, Double>> = runCatching { KavLive.shape(p.bus.route) }.getOrDefault(emptyList())
    val s = p.bus.spot
    val ahead: List<Int> = if (shape.size >= 2) {
        val here = alongPath(s.lat, s.lon, shape)
        stops.filter { alongPath(net.lat[it], net.lon[it], shape) >= here - 30.0 }
    } else {
        var nearest = 0
        var best = Double.MAX_VALUE
        for (i in stops.indices) {
            val d = metres(s.lat, s.lon, net.lat[stops[i]], net.lon[stops[i]])
            if (d < best) { best = d; nearest = i }
        }
        stops.drop(nearest + 1)
    }
    return OnBusStops(stops, shape, ahead)
}

// The ride of one bus line from where the rider is to the stop they get off at (OnBoard): its trips of the line's number
// and kind, the bus's stops as the way, the rider's fresh position else the bus's.
private fun onBusRide(model: KavModel, net: Net, p: BusPick, d: OnBusStops, stop: Int): OnBoard.Built? {
    val to = d.stops.lastIndexOf(stop).takeIf { it > 0 } ?: return null
    val lines = (0 until net.nRoutes).filterTo(HashSet()) { net.rShort[it] == p.line.number && net.rType[it] == p.line.type }
    val here = model.here?.takeIf { model.hereFresh } ?: (p.bus.spot.lat to p.bus.spot.lon)
    return OnBoard.trip(net, lines, d.stops, to, here, System.currentTimeMillis() / 1000, d.shape, first = KavLive.firstOf(p.bus.key))
}

// Home's card: the bus the phone travels with, to follow, or not. A trip under way on that bus's line is followed already.
@Composable
internal fun OnBusCard(model: KavModel, modifier: Modifier = Modifier) {
    val net = model.net ?: uk.noammm.kav.Loaded.net
    val trip = model.activeJourney?.trip?.rides?.map { it.shortName.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val picks = OnBus.picks.takeIf { net != null && it.none { p -> p.line.number.trim() in trip } }.orEmpty()
    // The stops of each, worked out off the main thread as the buses report, so Follow opens on them at once.
    val stops by produceState(emptyMap<String, OnBusStops>(), picks, net) {
        val n = net ?: return@produceState
        value = withContext(Dispatchers.IO) { picks.associate { it.bus.key to onBusStops(n, it) } }
    }
    // Shown once its stops are known; what it last showed stays while it leaves.
    val ready = picks.filter { stops[it.bus.key]?.ahead?.isNotEmpty() == true }
    var shown by remember { mutableStateOf(ready) }
    if (ready.isNotEmpty()) shown = ready
    AnimatedVisibility(
        ready.isNotEmpty(), modifier,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) {
        Column(
            Modifier.padding(top = K.gap4).fillMaxWidth().panel(K.rCard)
                .animateContentSize(spring(dampingRatio = 1f, stiffness = 500f)),
        ) {
            Text(
                T("It looks like you're on a bus", "נראה שאתם באוטובוס"), style = K.type.headline,
                modifier = Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap4),
            )
            // A trip under way on other lines: which ones it takes.
            if (trip.isNotEmpty() && shown.size == 1) Text(
                T("Your trip takes line ${trip.joinToString(", ")}", "הנסיעה שלכם בקו ${trip.joinToString(", ")}"),
                style = K.type.subhead, color = K.textSecondary,
                modifier = Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap1),
            )
            for (pk in shown) {
                val d = stops[pk.bus.key]
                ListRow(
                    T("Follow line ${T.ltr(pk.line.number)}", "מעקב אחרי קו ${T.ltr(pk.line.number)}"),
                    subtitle = T("To \u2068${towards(net, pk, d)}\u2069", "אל \u2068${towards(net, pk, d)}\u2069"),
                    leading = { net?.let { LineBadge(it, -1, label = pk.line.number, type = pk.line.type, agency = pk.line.agency) } },
                    onClick = { if (d != null) OnBus.following = pk to d },
                )
            }
            KavButton(
                T("Not on a bus", "לא באוטובוס"),
                { val now = System.currentTimeMillis() / 1000; shown.forEach { OnBus.decline(it.bus.key, now) }; OnBus.picks = emptyList() },
                Modifier.padding(bottom = K.gap1), style = ButtonStyle.Plain,
            )
        }
    }
}

// Where the bus goes: its trip's last stop as the timetable names it, in the rider's language for stop names; else as the
// server has it.
private fun towards(net: Net?, pk: BusPick, d: OnBusStops?): String =
    d?.stops?.lastOrNull()?.let { net?.stopName(it) } ?: pk.line.to

// The sheet Follow opens, drawn by Home outside its list.
@Composable
internal fun OnBusSheet(model: KavModel) {
    val (pk, d) = OnBus.following ?: return
    val net = model.net ?: uk.noammm.kav.Loaded.net ?: return
    FollowSheet(model, net, pk, d) { OnBus.following = null }
}

// Where the rider gets off: the stops the bus still has ahead; a tap starts the ride there.
@Composable
private fun FollowSheet(model: KavModel, net: Net, pk: BusPick, d: OnBusStops, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    BottomSheet(onDismiss, scrolls = true) { close ->
        SheetHeader(
            T("Where do you get off?", "איפה יורדים?"), { close(onDismiss) },
            subtitle = T("Line ${T.ltr(pk.line.number)} to \u2068${towards(net, pk, d)}\u2069", "קו ${T.ltr(pk.line.number)} אל \u2068${towards(net, pk, d)}\u2069"),
        )
        ListGroup(Modifier.padding(top = K.gap3)) {
            for (g in d.ahead) row {
                ListRow(net.stopName(g), subtitle = net.cityName(g).ifBlank { null }, onClick = {
                    scope.launch {
                        val built = withContext(Dispatchers.Default) { onBusRide(model, net, pk, d, g) }
                        if (built == null) {
                            android.widget.Toast.makeText(ctx, T("No trip of this line gets there now.", "אין עכשיו נסיעה של הקו הזה שמגיעה לשם."),
                                android.widget.Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        close {
                            onDismiss()
                            // On board from the start: the trip opens on its ride, under way.
                            model.journeyStep = buildSteps(built.trip, built.from, built.to).indexOfFirst { it is Step.Ride }.coerceAtLeast(0)
                            model.activeJourney = uk.noammm.kav.ActiveJourney(built.trip, built.resolved, built.from, built.to)
                            model.openJourney = true
                            model.tab = uk.noammm.kav.Tab.Directions
                        }
                    }
                })
            }
        }
    }
}
