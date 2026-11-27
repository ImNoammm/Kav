package uk.noammm.kav.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Net
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce

// #116, #100: "I'm already on a bus". The rider picks the bus (one near them, or by its line's number), then the stop to get
// off at, and sees how long is left, said to be live (from where the bus is now) or the timetable's alone. Kav also
// notices, while it is open, that the phone is moving along with one bus, and asks whether to follow it. Nothing is kept
// and nothing is followed until the rider says yes; the switch is in Settings (on by default).
internal object OnBus {
    private fun store(ctx: Context) = ctx.getSharedPreferences("kav_onbus", Context.MODE_PRIVATE)
    fun suggest(ctx: Context): Boolean = store(ctx).getBoolean("suggest", true)
    fun setSuggest(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("suggest", on).apply()

    // Buses the rider said no to, until when (epoch seconds), so the same bus isn't offered again at once.
    private val declined = HashMap<String, Long>()
    fun decline(key: String, now: Long) { synchronized(declined) { declined[key] = now + 1800 } }
    fun declinedNow(key: String, now: Long): Boolean = synchronized(declined) { (declined[key] ?: 0L) > now }
}

internal class BusPick(val bus: KavLive.Bus, val line: KavLive.Line, val away: Double)

// Whether the phone travels with one bus: moving like a vehicle, and for three of the bus's own reports in a row (each
// compared with where the phone was when the bus reported), within 100 m of it. Two buses that fit equally are both
// offered, so the rider picks.
internal class RideDetector {
    private val track = ArrayList<Fix>()
    private val near = HashMap<String, LinkedHashMap<Long, Double>>()

    fun moving(fix: Fix): Boolean {
        val before = track.lastOrNull()
        track.add(fix)
        if (track.size > 12) track.removeAt(0)
        if (before == null) return false
        val secs = (fix.at - before.at).coerceAtLeast(1L)
        val m = metres(before.lat, before.lon, fix.lat, fix.lon)
        val ok = m >= 50.0 && m / secs >= 3.0
        if (!ok) near.clear()
        return ok
    }

    private fun phoneAt(t: Long): Pair<Double, Double>? {
        for (i in 0 until track.size - 1) {
            val a = track[i]
            val b = track[i + 1]
            if (t >= a.at && t <= b.at) {
                val f = if (b.at == a.at) 0.0 else (t - a.at).toDouble() / (b.at - a.at)
                return Pair(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
            }
        }
        return null
    }

    fun offer(area: KavLive.Area): List<BusPick> {
        val keys = HashSet<String>()
        for (b in area.buses) {
            val p = phoneAt(b.spot.at) ?: continue
            val d = metres(p.first, p.second, b.spot.lat, b.spot.lon)
            if (d <= 100.0) {
                val seen = near.getOrPut(b.key) { LinkedHashMap() }
                seen[b.spot.at] = d
                while (seen.size > 5) seen.remove(seen.keys.first())
                keys.add(b.key)
            }
        }
        near.keys.retainAll(keys)
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
}

private suspend fun onBusFix(ctx: Context): Pair<Double, Double>? =
    kotlinx.coroutines.suspendCancellableCoroutine<Pair<Double, Double>?> { c ->
        val stop = requestLocationOnce(ctx, onFail = { if (c.isActive) c.resumeWith(Result.success(null)) }, requireFresh = true) { p ->
            if (c.isActive) c.resumeWith(Result.success(p))
        }
        c.invokeOnCancellation { stop() }
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

// The timetable's own rows for this bus's trip, the last stop's too (a stop's list of departures leaves a trip's last stop
// out, so a terminus never had a time that way).
private class OnBusTimes(val stops: List<Int>, val times: List<Int>)

private fun onBusTimes(net: Net, p: BusPick, d: OnBusStops, first: Long): OnBusTimes? {
    for (g in d.stops) {
        val st = KavLive.stopTimeOf(net, g, p.line.number, first, p.line.name)
        if (st < 0) continue
        val t = net.tripOf(st)
        val rows = (net.tripStart[t] until net.tripStart[t + 1]).toList()
        return OnBusTimes(rows.map { net.stStop[it] }, rows.map { net.stDep[it] })
    }
    return null
}

// What the timetable has this trip doing where the bus is now: between the two stops it is between.
private fun onBusScheduledHere(net: Net, tm: OnBusTimes, shape: List<Pair<Double, Double>>, spot: KavLive.Spot): Int? {
    val s = tm.stops
    if (s.size < 2) return null
    val along = shape.size >= 2
    val pos = if (along) alongPath(spot.lat, spot.lon, shape) else 0.0
    val marks = s.map { if (along) alongPath(net.lat[it], net.lon[it], shape) else 0.0 }
    var i = -1
    if (along) {
        for (k in s.indices) if (marks[k] <= pos) i = k
    } else {
        var best = Double.MAX_VALUE
        for (k in s.indices) {
            val m = metres(spot.lat, spot.lon, net.lat[s[k]], net.lon[s[k]])
            if (m < best) { best = m; i = k }
        }
    }
    if (i < 0) i = 0
    val t0 = tm.times[i]
    if (!along || i >= s.lastIndex) return t0
    val t1 = tm.times[i + 1]
    val span = marks[i + 1] - marks[i]
    val f = if (span <= 1.0) 0.0 else ((pos - marks[i]) / span).coerceIn(0.0, 1.0)
    return t0 + ((t1 - t0) * f).toInt()
}

// seconds: null when nothing can say. board: the ministry's own live time for this bus at the stop (what Kav's lines show).
// live: else from the bus's own position (the timetable's time moved by how far the bus runs from it); else the
// timetable's alone. rough: no timetable time for the stop, only how far the road still goes.
internal class OnBusEta(val seconds: Long?, val live: Boolean, val rough: Boolean, val board: Boolean = false)

// Slow (it asks the server): off the main thread.
internal fun onBusEta(net: Net, p: BusPick, d: OnBusStops, dest: Int, spot: KavLive.Spot?, now: Long): OnBusEta {
    val first = KavLive.firstOf(p.bus.key)
    val nowSec = KavLive.secondOfDay(now)
    val fresh = spot != null && now - spot.at <= KavLive.STALE_S
    val code = net.code.getOrElse(dest) { 0 }
    if (code > 0) {
        val board = try {
            KavLive.stops(listOf(code))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val due = board?.due?.firstOrNull {
            it.stop == code && it.expected > 0 && (it.key == p.bus.key || (first != null && it.route == p.bus.route && it.first == first))
        }
        if (due != null) return OnBusEta((due.expected - now).coerceAtLeast(0L), live = true, rough = false, board = true)
    }
    val tm = if (first == null) null else onBusTimes(net, p, d, first)
    val k = tm?.stops?.lastIndexOf(dest) ?: -1
    val sched: Int? = if (tm != null && k >= 0) tm.times[k] else null
    if (tm != null && sched != null) {
        if (spot == null || !fresh) return OnBusEta((sched - nowSec).toLong().coerceAtLeast(0L), live = false, rough = false)
        val here = onBusScheduledHere(net, tm, d.shape, spot)
        if (here != null) {
            val delay = (KavLive.secondOfDay(spot.at) - here).coerceIn(-900, 5400)
            return OnBusEta((sched + delay - nowSec).toLong().coerceAtLeast(0L), live = true, rough = false)
        }
    }
    if (spot != null && d.shape.size >= 2) {
        val left = alongPath(net.lat[dest], net.lon[dest], d.shape) - alongPath(spot.lat, spot.lon, d.shape)
        return OnBusEta((left.coerceAtLeast(0.0) / 5.5).toLong(), live = fresh, rough = true)
    }
    return OnBusEta(null, live = false, rough = true)
}

private fun clockOf(epoch: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = ISRAEL }.format(java.util.Date(epoch * 1000))

private fun awayShown(m: Double): String = if (m < 0) "" else if (m < 1000) "${(m / 10).toInt() * 10} m" else "${"%.1f".format(m / 1000)} km"

@Composable
internal fun OnBusCard(model: KavModel, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf<BusPick?>(null) }
    var picks by remember { mutableStateOf<List<BusPick>>(emptyList()) }
    val suggest = OnBus.suggest(ctx)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(suggest, lifecycle) {
        if (!suggest || !hasLocationPermission(ctx)) { picks = emptyList(); return@LaunchedEffect }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val det = RideDetector()
            // The position Kav already has (the Live tab, a trip's map or a trip under way keep it); this asks for none of its own.
            var seenAt = 0L
            while (true) {
                val p = model.here
                val at = model.hereAt
                if (p != null && at != seenAt && model.hereFresh) {
                    seenAt = at
                    val now = System.currentTimeMillis() / 1000
                    val fix = Fix(p.first, p.second, at)
                    if (det.moving(fix)) {
                        val area = try {
                            withContext(Dispatchers.IO) { KavLive.area(KavLive.box(p.first, p.second, 0.5)) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        picks = if (area == null) emptyList() else det.offer(area).filter { !OnBus.declinedNow(it.bus.key, now) }
                    } else {
                        picks = emptyList()
                    }
                } else if (!model.hereFresh) {
                    picks = emptyList()
                }
                delay(15_000)
            }
        }
    }
    // #116, step C: a trip under way names the lines it rides; a bus the phone travels with that isn't one of them is said.
    val expected = model.activeJourney?.trip?.rides?.map { it.shortName.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    if (picks.isNotEmpty()) Column(modifier.fillMaxWidth().panel(K.rCard).padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
        run {
            Text(T("It looks like you're on a bus", "נראה שאתם באוטובוס"), style = K.type.headline)
            val wrong = expected.isNotEmpty() && picks.size == 1 && picks[0].line.number.trim() !in expected
            if (wrong) Text(
                T(
                    "Your trip uses line ${expected.joinToString(", ")}, but this looks like line ${picks[0].line.number}.",
                    "הנסיעה שלכם משתמשת בקו ${expected.joinToString(", ")}, אבל זה נראה כמו קו ${picks[0].line.number}.",
                ),
                style = K.type.subhead, color = K.textSecondary,
            )
            for (pk in picks) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.fill)
                        .clickable(role = Role.Button) { start = pk; open = true }.padding(K.gap3),
                ) {
                    Text(T("Follow line ${pk.line.number} to ${pk.line.to}", "לעקוב אחרי קו ${pk.line.number} ל${pk.line.to}"), style = K.type.body)
                }
            }
            KavButton(
                T("Not on a bus", "אני לא באוטובוס"),
                { val now = System.currentTimeMillis() / 1000; picks.forEach { OnBus.decline(it.bus.key, now) }; picks = emptyList() },
                Modifier.fillMaxWidth(), style = ButtonStyle.Plain,
            )
        }
    }
    if (open) OnBusDialog(model, start) { open = false; start = null }
}

// #100: the "I'm already on a bus" button, on the Lines tab under the search.
@Composable
internal fun OnBusButton(model: KavModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    KavButton(
        T("I'm already on a bus", "אני כבר באוטובוס"), { open = true },
        modifier.fillMaxWidth(), style = ButtonStyle.Secondary,
    )
    if (open) OnBusDialog(model, null) { open = false }
}

@Composable
internal fun OnBusDialog(model: KavModel, start: BusPick?, dismiss: () -> Unit) {
    val net = model.net ?: uk.noammm.kav.Loaded.net
    var pick by remember { mutableStateOf(start) }
    var dest by remember { mutableStateOf<Int?>(null) }
    var stops by remember { mutableStateOf<OnBusStops?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 620.dp).clip(RoundedCornerShape(K.rCard)).background(K.surface1)
                .verticalScroll(rememberScrollState()).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            val p = pick
            val d = dest
            val s = stops
            if (net == null) {
                Text(T("The timetable is still loading. Try again in a moment.", "לוח הזמנים עדיין נטען. נסו שוב עוד רגע."), style = K.type.subhead)
            } else if (p == null) {
                OnBusPickBus(model) { pick = it }
            } else if (d == null || s == null) {
                OnBusPickStop(net, p, back = { pick = null }) { stop, data -> stops = data; dest = stop }
            } else {
                OnBusRiding(net, p, s, d) { dest = null }
            }
            KavButton(T("Close", "סגירה"), dismiss, Modifier.fillMaxWidth(), style = ButtonStyle.Plain)
        }
    }
}

@Composable
private fun OnBusRow(text: String, sub: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.fill)
            .clickable(role = Role.Button, onClick = onClick).padding(K.gap3),
    ) {
        Text(text, style = K.type.body)
        if (sub.isNotEmpty()) Text(sub, style = K.type.caption, color = K.textSecondary)
    }
}

@Composable
private fun OnBusPickBus(model: KavModel, onPick: (BusPick) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<BusPick>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        found = null
        failed = false
        val number = query.trim()
        if (number.isNotEmpty()) delay(500)
        val here: Pair<Double, Double>? = if (number.isNotEmpty()) model.here ?: onBusFix(ctx) else model.here?.takeIf { model.hereFresh } ?: onBusFix(ctx) ?: model.here
        val area: KavLive.Area? = try {
            withContext(Dispatchers.IO) {
                if (number.isNotEmpty()) KavLive.line(number)
                else if (here != null) KavLive.area(KavLive.box(here.first, here.second, 0.8))
                else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            null
        }
        val picks = ArrayList<BusPick>()
        if (area != null) {
            for (b in area.buses) {
                val line = area.lines[b.route] ?: continue
                val away = if (here == null) -1.0 else metres(here.first, here.second, b.spot.lat, b.spot.lon)
                picks.add(BusPick(b, line, away))
            }
        }
        picks.sortBy { if (it.away < 0) Double.MAX_VALUE else it.away }
        found = picks.take(12)
    }
    Text(T("I'm on a bus", "אני באוטובוס"), style = K.type.title)
    Text(
        T(
            "Pick your bus from the ones near you, or type its line number.",
            "בחרו את האוטובוס שלכם מבין אלה שסביבכם, או הקלידו את מספר הקו.",
        ),
        style = K.type.subhead,
    )
    androidx.compose.material3.OutlinedTextField(
        value = query, onValueChange = { query = it.take(5) }, singleLine = true,
        label = { Text(T("Line number (optional)", "מספר קו (לא חובה)")) }, modifier = Modifier.fillMaxWidth(),
    )
    val list = found
    if (list == null) {
        Text(T("Looking for buses…", "מחפשים אוטובוסים…"), style = K.type.subhead)
    } else if (list.isEmpty()) {
        Text(
            if (failed) T("Couldn't reach the live buses. Try again.", "לא הצלחנו להגיע לאוטובוסים החיים. נסו שוב.")
            else if (query.isBlank()) T("No live buses found near you. Type the line number, or allow location.", "לא נמצאו אוטובוסים חיים לידכם. הקלידו את מספר הקו, או אשרו מיקום.")
            else T("No live buses on this line right now.", "אין עכשיו אוטובוסים חיים בקו הזה."),
            style = K.type.subhead,
        )
    } else {
        for (pk in list) {
            OnBusRow(
                T("${pk.line.number} to ${pk.line.to}", "${pk.line.number} ל${pk.line.to}"),
                listOf(pk.line.from.takeIf { it.isNotBlank() }?.let { T("from $it", "מ$it") }.orEmpty(), awayShown(pk.away))
                    .filter { it.isNotEmpty() }.joinToString(" · "),
            ) { onPick(pk) }
        }
    }
}

@Composable
private fun OnBusPickStop(net: Net, p: BusPick, back: () -> Unit, onStop: (Int, OnBusStops) -> Unit) {
    val data by produceState<OnBusStops?>(null, p.bus.key) {
        value = withContext(Dispatchers.IO) { onBusStops(net, p) }
    }
    Text(T("Line ${p.line.number} to ${p.line.to}", "קו ${p.line.number} ל${p.line.to}"), style = K.type.title)
    Text(T("Where do you get off?", "איפה יורדים?"), style = K.type.subhead)
    val d = data
    if (d == null) {
        Text(T("Looking up its stops…", "מחפשים את התחנות שלו…"), style = K.type.subhead)
    } else if (d.ahead.isEmpty()) {
        Text(T("Couldn't find the stops this bus still has ahead.", "לא מצאנו תחנות שנותרו לאוטובוס הזה."), style = K.type.subhead)
    } else {
        for (g in d.ahead) {
            OnBusRow(net.stopName(g), net.cityName(g)) { onStop(g, d) }
        }
    }
    KavButton(T("Pick another bus", "בחירת אוטובוס אחר"), back, Modifier.fillMaxWidth(), style = ButtonStyle.Secondary)
}

@Composable
private fun OnBusRiding(net: Net, p: BusPick, d: OnBusStops, dest: Int, change: () -> Unit) {
    var spot by remember { mutableStateOf<KavLive.Spot?>(p.bus.spot) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(p.bus.key) {
        while (true) {
            tick++
            val s = spot
            if (s != null) {
                val area = try {
                    withContext(Dispatchers.IO) { KavLive.area(KavLive.box(s.lat, s.lon, 0.8)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val b = area?.buses?.firstOrNull { it.key == p.bus.key }
                if (b != null) spot = b.spot
            }
            delay(15_000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    var calc by remember { mutableStateOf<Pair<Long, OnBusEta>?>(null) }
    LaunchedEffect(spot, tick) {
        val t = System.currentTimeMillis() / 1000
        val e = withContext(Dispatchers.IO) { onBusEta(net, p, d, dest, spot, t) }
        calc = Pair(t, e)
    }
    val eta = calc?.second
    val left = calc?.let { c -> c.second.seconds?.let { (it - (now - c.first)).coerceAtLeast(0L) } }
    val here = spot
    val atStop = here != null && metres(here.lat, here.lon, net.lat[dest], net.lon[dest]) < 100.0
    Text(T("Line ${p.line.number} to ${p.line.to}", "קו ${p.line.number} ל${p.line.to}"), style = K.type.title)
    Text(T("Get off at ${net.stopName(dest)}", "יורדים ב${net.stopName(dest)}"), style = K.type.headline)
    if (atStop) {
        Text(T("The bus is at your stop.", "האוטובוס בתחנה שלכם."), style = K.type.headline)
    } else if (eta == null) {
        Text(T("Working out the time…", "מחשבים את הזמן…"), style = K.type.subhead)
    } else if (left == null) {
        Text(T("No time can be given for this stop.", "אי אפשר לתת זמן לתחנה הזו."), style = K.type.subhead)
    } else {
        Text(
            if (left < 60) T("Less than a minute", "פחות מדקה") else T("${left / 60} min", "${left / 60} דק׳"),
            style = K.type.title,
        )
        Text(T("About ${clockOf(now + left)}", "בסביבות ${clockOf(now + left)}"), style = K.type.subhead)
    }
    val age = here?.let { now - it.at }
    Text(
        if (eta == null) "" else when {
            eta.board -> T("Live: the ministry's own time for this bus at this stop, the same as Kav's lines show.", "חי: הזמן של משרד התחבורה לאוטובוס הזה בתחנה הזו, כמו שמופיע בקווים ב-Kav.")
            eta.live && eta.rough -> T("Rough estimate from where the bus is now (no timetable time for this stop).", "הערכה גסה לפי המיקום של האוטובוס עכשיו (אין שעה בלוח הזמנים לתחנה הזו).")
            eta.live -> T("Live: the timetable's time moved by how late or early the bus runs now. Its position is ${age ?: 0}s old.", "חי: השעה בלוח הזמנים מותאמת לפי האיחור או ההקדמה של האוטובוס עכשיו. המיקום שלו בן ${age ?: 0} שנ׳.")
            eta.rough -> T("Rough estimate only. The bus hasn't reported lately.", "הערכה גסה בלבד. האוטובוס לא דיווח לאחרונה.")
            else -> T("Timetable only: the bus isn't reporting its position now.", "לפי לוח הזמנים בלבד: האוטובוס לא מדווח מיקום כרגע.")
        },
        style = K.type.caption, color = K.textSecondary,
    )
    KavButton(T("Change stop", "החלפת תחנה"), change, Modifier.fillMaxWidth(), style = ButtonStyle.Secondary)
}
