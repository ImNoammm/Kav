package uk.noammm.kav.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.Loaded
import uk.noammm.kav.Tab
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.Transit
import uk.noammm.kav.ext.Blocks
import java.time.Instant
import java.time.ZoneId

// The blocks an extension's page fills with Kav's own data, drawn by Kav (ext/Blocks.kt): a ministry stop, its next
// scheduled departures, a line, and a picture from the package. All from what's on the phone: the week's timetable and
// the signed package. Showing them asks no server, so which stop a private page chooses goes nowhere; live times come
// only once the rider opens the stop's own board.

// Its next scheduled departures from a stop, from the timetable on the phone: (line number, unix seconds).
internal fun scheduledDepartures(net: Net, code: Int, limit: Int, now: Long): List<Pair<String, Long>> {
    val stops = net.code.indices.filter { net.code[it] == code }
    val zone = ZoneId.of("Asia/Jerusalem")
    val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
    val out = ArrayList<Pair<String, Long>>()
    for (offset in 0L..1L) {
        val day = today.plusDays(offset)
        val base = Schedules.base(day)
        for (s in stops) for (i in net.dStart[s] until net.dStart[s + 1]) {
            val st = net.dConn[i]
            val trip = net.tripOf(st)
            val at = base + net.stDep[st]
            // A train has no number of its own: it's shown as a train.
            val r = net.tripRoute[trip]
            if (at >= now && net.runsOn(trip, day.dayOfWeek.value % 7)) out += net.rShort[r].ifEmpty { if (net.rType[r] == 2) T("Train", "רכבת") else net.rLong[r].take(12) } to at
        }
        if (out.size >= limit) break
    }
    return out.sortedBy { it.second }.take(limit)
}

private fun stopIndex(net: Net, code: Int) = net.code.indices.firstOrNull { net.code[it] == code }

// Inside a card Kav already draws (an extension's Home card, NativeCard): blocks lie flat in it, with no card of their own.
internal val LocalInCard = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun StopBlock(model: KavModel, code: Int) {
    val net = Loaded.net
    val s = net?.let { stopIndex(it, code) }
    val row: @Composable () -> Unit = {
            ListRow(
                s?.let { net.stopName(it) } ?: T("Stop ", "תחנה ") + T.ltr(code.toString()),
                subtitle = s?.let { net.cityName(it) }?.ifEmpty { null },
                leading = { Icon(Icons.Rounded.Place, null, tint = K.textSecondary, modifier = Modifier.size(22.dp)) },
                onClick = s?.let { i -> { ExtPageNav.close(); model.openStation(i) } },
            )
    }
    if (LocalInCard.current) row() else ListGroup { row { row() } }
}

@Composable
internal fun DeparturesBlock(model: KavModel, code: Int, limit: Int) {
    val net = Loaded.net
    var rows by remember(code, limit, net) { mutableStateOf<List<Pair<String, Long>>?>(null) }
    LaunchedEffect(code, limit, net) {
        if (net != null) rows = withContext(Dispatchers.Default) { runCatching { scheduledDepartures(net, code, limit, System.currentTimeMillis() / 1000) }.getOrDefault(emptyList()) }
    }
    val s = net?.let { stopIndex(it, code) }
    val open = s?.let { i -> { ExtPageNav.close(); model.openStation(i) } }
    val body: @Composable ColumnScope.() -> Unit = {
        Text(s?.let { net.stopName(it) } ?: T.ltr(code.toString()), style = K.type.headline)
        // Scheduled, never live: Kav's green is for times measured live, and none of these is.
        Text(T("Scheduled · tap for live times", "לפי לוח הזמנים · הקישו לזמנים בזמן אמת"), style = K.type.caption)
        val list = rows
        when {
            net == null || list == null -> Spinner(20.dp)
            list.isEmpty() -> Text(T("No more departures today.", "אין עוד יציאות היום."), style = K.type.subhead)
            else -> for ((line, at) in list) Row(Modifier.fillMaxWidth().padding(top = K.gap2), verticalAlignment = Alignment.CenterVertically) {
                LinePlate(line)
                Spacer(Modifier.weight(1f))
                Text(clockFormat().format(java.util.Date(at * 1000)), style = K.type.body.tabular, color = K.scheduled)
            }
        }
    }
    if (!LocalInCard.current) KavCard(onClick = open, content = body)
    else Column(Modifier.fillMaxWidth().then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier)
        .padding(start = K.gap4, end = K.gap4, top = K.gap1, bottom = K.gap4), content = body)
}

@Composable
private fun LinePlate(number: String) {
    Text(T.ltr(number), style = K.type.bodyStrong, modifier = Modifier.clip(RoundedCornerShape(K.rBadge)).background(K.badgePlate)
        .padding(horizontal = K.gap2, vertical = 2.dp))
}

@Composable
internal fun LineBlock(model: KavModel, number: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.surface)
            .clickable(role = Role.Button) {
                ExtPageNav.close()
                model.rememberReturn(Tab.Lines)
                model.scheduleLine = null; model.lineRoute = -1; model.lineFocusStop = -1
                model.lineQuery = number; model.tab = Tab.Lines
            }.padding(K.gap4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LinePlate(number)
        Text(T("Find this line in Kav", "חיפוש הקו הזה ב-Kav"), style = K.type.body)
    }
}

// A picture, at most 8 MB, decoded no larger than the screen needs.
@Composable
internal fun ImageBlock(b: Blocks.Image) {
    var bmp by remember(b.path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(b.path) {
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                // An https address (fetched carrying nothing of Kav's) or a data: address.
                val bytes = if (b.path.startsWith("https://")) uk.noammm.kav.ext.ExtApp.Http.get(b.path, 8 shl 20)
                    else android.util.Base64.decode(b.path.substringAfter(','), android.util.Base64.DEFAULT)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (o.outWidth <= 0 || o.outWidth > 4096 || o.outHeight > 4096) return@runCatching null
                var sample = 1
                while (o.outWidth / (sample * 2) >= 1080) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val m = Modifier.fillMaxWidth().aspectRatio(b.ratio).clip(RoundedCornerShape(K.rCard)).background(K.fill)
    bmp?.let { Image(it, b.alt, m, contentScale = ContentScale.Crop) } ?: Box(m)
}

// Kav's planner with the destination the page gave, planned now: the rider tapped for it. The place's name is the
// page's, checked like any of its text.
internal fun planTo(model: KavModel, p: Blocks.Action.Plan) {
    ExtPageNav.close()
    model.pendingFrom = null
    model.pendingTo = Transit.Place(p.name, "", p.lat, p.lon)
    model.tab = Tab.Directions
}

// A link an extension's page opens: in the rider's browser, as a link on any page does.
@Composable
internal fun OpenLink(url: String, done: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(url) { openLink(ctx, url); done() }
}
