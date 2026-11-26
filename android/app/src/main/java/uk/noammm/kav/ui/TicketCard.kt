package uk.noammm.kav.ui

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import uk.noammm.kav.Prefs
import uk.noammm.kav.TicketReader
import uk.noammm.kav.TicketStore
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Ticket
import uk.noammm.kav.data.TicketScreens
import uk.noammm.kav.data.TicketTimes
import uk.noammm.kav.data.Tickets

// The ticket the rider bought in Rav-Pass, as Kav read it there (TicketReader): in Kav's own look, saying where it came
// from and when, with its code drawn by Kav from what the code says, and a running clock as its sign of life.
internal object TicketView {
    fun supported(sdk: Int = Build.VERSION.SDK_INT) = sdk >= Build.VERSION_CODES.R

    // A ticket for a ride on the trip page: its kind, its line where both are known, paid around the ride's time.
    fun matches(t: Ticket, section: RavPass.Section, line: String?, dep: Long): Boolean {
        val kind = when (t.kind) {
            TicketScreens.Kind.BUS -> RavPass.Section.BUS
            TicketScreens.Kind.TRAIN_ENTRANCE, TicketScreens.Kind.TRAIN_EXIT -> RavPass.Section.TRAIN
            TicketScreens.Kind.LIGHT_RAIL -> RavPass.Section.LIGHT_RAIL
        }
        if (kind != section) return false
        if (t.line != null && !line.isNullOrBlank() && t.line != line) return false
        return (t.paidAt ?: t.shownAt ?: t.readAt) in (dep - 60 * Ticket.MINUTE)..(dep + 60 * Ticket.MINUTE)
    }

    // The code as a grid of modules, drawn by Kav: the same content, in the kind of code Rav-Pass showed, with its
    // character set where it named one. None for a code Kav couldn't redraw exactly (its kind ends in "!": TicketReader).
    fun matrix(content: String, format: String): BitMatrix? = runCatching {
        if (format.endsWith("!")) return null
        val (kind, charset) = format.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
        val f = if (kind == BarcodeFormat.AZTEC.name) BarcodeFormat.AZTEC else BarcodeFormat.QR_CODE
        val hints = HashMap<EncodeHintType, Any>().apply {
            put(EncodeHintType.MARGIN, 0)
            if (f == BarcodeFormat.QR_CODE) put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            charset?.let { put(EncodeHintType.CHARACTER_SET, it) }
        }
        MultiFormatWriter().encode(content, f, 0, 0, hints)
    }.getOrNull()

    fun drawable(format: String) = !format.endsWith("!")

    // The reader turned on in Android's accessibility settings, read from that setting, not from what Android has bound
    // this moment: right after Kav is updated (or force-stopped) the service is on but not yet bound again, and paying must
    // not look switched off then (Noam, 19 Nov 2026: paying showed as off after an update).
    fun enabled(ctx: Context): Boolean = Tickets.readerOn(true,
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        ctx.packageName, TicketReader::class.java.name)
}

@Composable
private fun rememberClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000 - now % 1000) } }
    return now
}

// The live tickets, read again from storage when Kav opens and dropped once over. Nothing ticks for a rider with no
// ticket kept: the minute's check runs only while there is one, and the card's own clock only while a card is shown.
@Composable
internal fun liveTickets(): List<Ticket> {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { TicketStore.load(ctx) } }
    if (TicketStore.kept.isEmpty()) return emptyList()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); now = System.currentTimeMillis(); TicketStore.prune(ctx) } }
    return TicketStore.kept.filter { it.overAt > now }
}

// A read ticket in Kav's own look: a row on Home's Pay card and on the ride, and the ticket itself full screen. What is
// true of a read ticket goes into it: "Paid at" only for a purchase Kav saw, else the time Rav-Pass shows, and "Open in
// Rav-Pass".
private val good get() = if (K.light) Color(0xFF2E7D4F) else Color(0xFF8FD8A0)

private fun kindName(k: TicketScreens.Kind) = when (k) {
    TicketScreens.Kind.BUS -> T("Bus ticket", "כרטיס לאוטובוס")
    TicketScreens.Kind.TRAIN_ENTRANCE -> T("Train entrance ticket", "כרטיס כניסה לרכבת")
    TicketScreens.Kind.TRAIN_EXIT -> T("Train exit ticket", "כרטיס יציאה מהרכבת")
    TicketScreens.Kind.LIGHT_RAIL -> T("Light rail ticket", "כרטיס לרכבת הקלה")
}

// "Line 21 · Tel Aviv Center": the line and where it goes, as the old tickets were named (the quick tile and the
// notification).
internal fun ticketName(t: Ticket): String =
    listOfNotNull(t.line?.let { T("Line $it", "קו $it") }, t.to).joinToString(" · ").ifBlank { kindName(t.kind) }

private fun modeOf(k: TicketScreens.Kind) = when (k) {
    TicketScreens.Kind.BUS -> Mode.BUS
    TicketScreens.Kind.LIGHT_RAIL -> Mode.TRAM
    else -> Mode.TRAIN
}

// When it was paid, as Kav saw it, else the time Rav-Pass shows on it; the day only when it isn't today.
private fun paidLine(t: Ticket): String? {
    val at = t.paidAt ?: return t.shown?.let { T("Validated at $it", "תיקוף ב-$it") }
    val day = TicketTimes.day(at, System.currentTimeMillis())
    val time = TicketTimes.clock(at)
    return if (day == null) T("Paid at $time", "שולם ב-$time") else T("Paid $day at $time", "שולם ב-$day בשעה $time")
}

// The fare's reach, for a bus or light rail ticket: what Rav-Pass's Approve screen named, or, unseen, the reach Kav's
// estimate of its time counts on.
private fun bandLine(t: Ticket): String? = when {
    t.kind != TicketScreens.Kind.BUS && t.kind != TicketScreens.Kind.LIGHT_RAIL -> null
    t.band == null -> T("If up to ${Ticket.NEAR_KM} km", "אם עד ${Ticket.NEAR_KM} ק״מ")
    else -> T("Up to ${t.band} km", "עד ${t.band} ק״מ")
}

// How long the ticket is good for, as old Kav's tickets said it ("Valid until 12:39"), and whether it still is: a bus or
// light rail fare up to 15 km until its free transfers end (Kav's estimate, Ticket.validUntil); a longer fare, this ride;
// a train ticket, until the exit. short: the row's few words ("Until 12:39").
internal fun validity(t: Ticket, now: Long, short: Boolean = false): Pair<String, Boolean> {
    val until = t.validUntil
    val at = until?.let { TicketTimes.clock(it) }
    return when {
        t.kind == TicketScreens.Kind.TRAIN_ENTRANCE -> T("Until you exit", "עד היציאה") to true
        t.kind == TicketScreens.Kind.TRAIN_EXIT -> T("For the exit gate", "לשער היציאה") to true
        until == null && short -> T("This ride", "לנסיעה הזאת") to true
        (t.band ?: 0) > Ticket.NEAR_KM -> T("This ride, no transfers", "לנסיעה הזאת, ללא מעברים") to true
        until == null -> T("Valid for this ride", "בתוקף לנסיעה הזאת") to true
        now < until -> (if (short) T("Until $at", "עד $at") else T("Valid until $at", "בתוקף עד $at")) to true
        else -> (if (short) T("Expired", "פג תוקף") else T("No longer valid", "כבר לא בתוקף")) to false
    }
}

// A ticket shown in Rav-Pass, on its section, for an inspector.
internal fun openInRavPass(ctx: android.content.Context, t: Ticket) {
    TicketStore.showAsked = System.currentTimeMillis(); RavPass.go(ctx, sectionOf(t))
}

private fun sectionOf(t: Ticket) = when (t.kind) {
    TicketScreens.Kind.BUS -> RavPass.Section.BUS
    TicketScreens.Kind.LIGHT_RAIL -> RavPass.Section.LIGHT_RAIL
    else -> RavPass.Section.TRAIN
}

// The ticket's ride in Kav's timetable, found from the words Rav-Pass shows: its route and stops, -1 where not found.
internal class TicketRide(val route: Int, val from: Int, val to: Int)

internal object TicketRides {
    private var of: Net? = null
    private val found = HashMap<String, TicketRide>()
    private fun key(t: Ticket) = listOf(t.key, t.line, t.from, t.to).joinToString("\u0000")
    private val spaces = Regex("\\s+")
    private val slash = Regex(" ?/ ?")

    @Synchronized fun cached(net: Net, t: Ticket): TicketRide? = if (of === net) found[key(t)] else null

    fun find(net: Net, t: Ticket): TicketRide {
        cached(net, t)?.let { return it }
        val ride = ride(net, t.line, t.from, t.to)
        synchronized(this) { if (of !== net) { of = net; found.clear() }; found[key(t)] = ride }
        return ride
    }

    // A stop named as Rav-Pass names it: the timetable's name (Hebrew or English), with or without ", its town".
    fun named(net: Net, text: String): List<Int> {
        fun norm(s: String) = s.trim().replace(spaces, " ").replace(slash, "/").lowercase()
        val want = norm(text)
        val out = ArrayList<Int>()
        for (s in 0 until net.nStops) {
            val town = net.cityOf[s]
            for ((name, city) in listOf(net.name[s] to net.city.getOrElse(town) { "" },
                net.nameEn.getOrElse(s) { "" } to net.cityEn.getOrElse(town) { "" })) {
                if (name.isEmpty()) continue
                val n = norm(name)
                if (want == n || city.isNotEmpty() && want == "$n, ${norm(city)}") { out += s; break }
            }
        }
        return out
    }

    // The trip that leaves the stop it was boarded at and reaches the stop it goes to, of the ticket's line when it has
    // one: the stops and the route are that trip's. Short of one, a trip of the line from the boarding stop; a stop only
    // where its name is one place (a bus station's platforms share its code).
    fun ride(net: Net, line: String?, from: String?, to: String?): TicketRide {
        val froms = from?.let { named(net, it) }.orEmpty()
        val tos = to?.let { named(net, it) }.orEmpty()
        val toSet = tos.toHashSet()
        var near: TicketRide? = null
        for (f in froms) for (i in net.dStart[f] until net.dStart[f + 1]) {
            val k = net.dConn[i]
            val trip = net.tripOf(k)
            val r = net.tripRoute[trip]
            if (line != null && net.rShort[r] != line) continue
            for (j in k + 1 until net.tripStart[trip + 1]) if (net.stStop[j] in toSet) return TicketRide(r, f, net.stStop[j])
            if (near == null && line != null) near = TicketRide(r, f, -1)
        }
        fun one(c: List<Int>) = c.firstOrNull()?.takeIf { s -> c.all { net.code[it] == net.code[s] } } ?: -1
        return near?.let { TicketRide(it.route, it.from, one(tos)) } ?: TicketRide(-1, one(froms), one(tos))
    }
}

@Composable
private fun rememberRide(t: Ticket): Pair<Net?, TicketRide?> {
    val net = uk.noammm.kav.Loaded.net
    val ride by produceState(net?.let { TicketRides.cached(it, t) }, net, t) {
        if (net != null && value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TicketRides.find(net, t) }
    }
    return net to ride
}

// The line's plate: Kav's own, in its operator's colour, once the line is found in the timetable; a plain one before.
@Composable
private fun TicketBadge(t: Ticket, net: Net?, ride: TicketRide?, modifier: Modifier = Modifier) {
    if (net != null && ride != null && ride.route >= 0) LineBadge(net, ride.route, modifier, label = t.line)
    else LinePlateBadge(t.line, modeOf(t.kind), null, modifier)
}

// A stop's name as Kav names it when found, else as Rav-Pass wrote it.
private fun stopName(net: Net?, stop: Int, text: String?): String? =
    if (net != null && stop >= 0) net.stopName(stop) else text

// A ticket's row: the line's plate and where the ride goes, the fare's reach under it, and on the end until when it's
// good. A ticket whose code isn't read yet opens the same way: its page sends the rider to Rav-Pass's drawer.
@Composable
internal fun TicketRow(t: Ticket, inset: androidx.compose.ui.unit.Dp = K.gap4, onClick: () -> Unit) {
    val (until, fresh) = validity(t, rememberClock(), short = true)
    val (net, ride) = rememberRide(t)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = inset, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        TicketBadge(t, net, ride)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text((stopName(net, ride?.to ?: -1, t.to) ?: kindName(t.kind)) + if (t.passengers.size > 1) "  ×${t.passengers.size}" else "",
                style = K.type.body, color = K.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            bandLine(t)?.let { Text(it, style = K.type.subhead, color = K.textSecondary, maxLines = 1) }
        }
        LiveText(until, style = K.type.bodyStrong.tabular, color = if (fresh) good else K.dim, maxLines = 1)
    }
}

// The ticket itself, full screen and bright: each passenger's code (one a page), until when it's good, and the ride, its
// line and stops opening their pages; then Rav-Pass, where the original is.
@Composable
internal fun TicketScreen(t: Ticket, model: uk.noammm.kav.KavModel?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    DisposableEffect(activity) {
        val w = activity?.window
        val before = w?.attributes?.screenBrightness ?: -1f
        if (w != null) w.attributes = w.attributes.apply { screenBrightness = 1f }
        onDispose { if (w != null) w.attributes = w.attributes.apply { screenBrightness = before } }
    }
    androidx.activity.compose.BackHandler(onBack = onClose)
    val now = rememberClock()
    val codes = t.passengers
    val pager = androidx.compose.foundation.pager.rememberPagerState { codes.size.coerceAtLeast(1) }
    val (net, ride) = rememberRide(t)
    Column(Modifier.fillMaxSize().background(K.bg).statusBarsPadding()) {
        val about = { org.json.JSONObject().put("key", t.key).put("kind", t.kind.name)
            .put("line", t.line).put("from", t.from).put("to", t.to).put("validUntil", t.validUntil) }
        KavPart("ticket.header", t, about) { ScreenHeader(kindName(t.kind), back = onClose) }
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = K.gap4)
                .padding(top = K.gap2, bottom = K.gap4 + LocalBottomBarInset.current).animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val gate = t.kind != TicketScreens.Kind.BUS
            // An extension's ticket without codes has nothing to show here.
            if (codes.isEmpty() && uk.noammm.kav.ExtTickets.mine(t)) Unit
            else if (codes.isEmpty()) Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).panel(K.rTile).padding(K.gap4), contentAlignment = Alignment.Center) {
                Text(T("Open the ticket in ${PayApp.current.label()}, under \"Active travel ticket for inspection\". Kav then shows it here too.",
                    "פתחו את הכרטיס ${PayApp.current.heIn}, תחת \"כרטיס נסיעה פעילה לביקורת\". אחר כך Kav תציג אותו גם כאן."),
                    style = K.type.body, color = K.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            } else KavPart("ticket.codes", t, { about().put("code", pager.currentPage).put("codes", codes.size) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxWidth()) { i ->
                        val p = codes[i]
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (!TicketView.drawable(p.format)) Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).panel(K.rTile).padding(K.gap4), contentAlignment = Alignment.Center) {
                                Text(T("Kav can't draw this code exactly. Show it in ${PayApp.current.label()}.", "Kav לא יכולה להציג את הקוד הזה בדיוק. הציגו אותו ${PayApp.current.heIn}."),
                                    style = K.type.body, color = K.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            }
                            // A station's gate reads a code the size Rav-Pass shows a train's at, 180 dp with a white border.
                            else if (gate) Box(Modifier.size(180.dp).clip(RoundedCornerShape(K.rTile)).background(Color.White).padding(10.dp)) {
                                Code(p.code, p.format, Modifier.fillMaxSize())
                            }
                            // A bus's, big enough for any reader and small enough to leave the ticket's facts on the screen.
                            else Code(p.code, p.format, Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(RoundedCornerShape(K.rCard)))
                        }
                    }
                    // Under the code, what Rav-Pass prints under it: whose it is in a group, and the ticket's number.
                    listOfNotNull(
                        if (codes.size > 1) "${pager.currentPage + 1} / ${codes.size}" else null,
                        codes.getOrNull(pager.currentPage)?.name,
                        t.number,
                    ).takeIf { it.isNotEmpty() }?.let {
                        Text(it.joinToString("  ·  "), style = K.type.subhead.tabular, color = K.textTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().panel(K.rCard)) {
                KavPart("ticket.validity", t, about) { TicketValidity(t, now) }
                TicketRideRows(t, net, ride, model, onClose, about)
            }
            if (!uk.noammm.kav.ExtTickets.mine(t)) KavButton(T("Open in ${PayApp.current.label()}", "פתיחה ${PayApp.current.heIn}"), onClick = {
                if (!uk.noammm.kav.ext.Actions.take(ctx, "ticket.ravpass.open") { about() }) openInRavPass(ctx, t)
            }, style = ButtonStyle.Secondary, icon = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.OpenInNew, modifier = Modifier.fillMaxWidth())
        }
    }
}

// Until when it's good, big, with Kav's estimate behind a small (i) as Settings' are (InfoHeader); the fare's reach and
// when it was paid under it.
@Composable
private fun TicketValidity(t: Ticket, now: Long) {
    val (valid, fresh) = validity(t, now)
    var why by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap2, top = K.gap3, bottom = K.gap3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveText(valid, Modifier.weight(1f, fill = false), style = K.type.title.tabular, color = if (fresh) good else K.dim)
            // An extension's ticket ends when it says: no estimate of Kav's to explain.
            if (t.validUntil != null && !uk.noammm.kav.ExtTickets.mine(t)) Box(
                Modifier.padding(start = K.gap1).size(40.dp).clip(CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = T("Kav's estimate", "הערכה של Kav")) { why = !why },
                contentAlignment = Alignment.Center,
            ) {
                val tint by androidx.compose.animation.animateColorAsState(if (why) K.accentInk else K.textTertiary,
                    androidx.compose.animation.core.tween(200), label = "infoTint")
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Info,
                    contentDescription = T("Kav's estimate", "הערכה של Kav"), tint = tint, modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            why,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            NoteCard(T("Kav's estimate: free transfers for 90 minutes from the first validation. ${PayApp.current.label()} keeps its own time.",
                "הערכה של Kav: מעברים בחינם במשך 90 דקות מהתיקוף הראשון. ${PayApp.current.heWith("ל")} יש שעה משלה."),
                Modifier.padding(end = K.gap2, top = K.gap1, bottom = K.gap2))
        }
        listOfNotNull(bandLine(t), paidLine(t)).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString("  ·  "), style = K.type.subhead, color = K.textSecondary)
        }
    }
}

// The ride: the line's plate, then the stop it was boarded at and the stop it goes to, down a short rail. The plate opens
// the line's page and each stop its board, where Kav found them in its timetable; the rest is plain text.
@Composable
private fun TicketRideRows(t: Ticket, net: Net?, ride: TicketRide?, model: uk.noammm.kav.KavModel?, onClose: () -> Unit,
    about: () -> org.json.JSONObject) {
    val from = stopName(net, ride?.from ?: -1, t.from)
    val to = stopName(net, ride?.to ?: -1, t.to)
    if (from == null && to == null && t.line == null) return
    Separator()
    KavPart("ticket.ride", t, about) {
        val m = model?.takeIf { net != null && ride != null }
        Row(Modifier.fillMaxWidth().padding(K.gap2), verticalAlignment = Alignment.Top) {
            val line = m?.takeIf { ride!!.route >= 0 }?.let { mm ->
                {
                    openTimetableLine(mm, ride!!.route, ride.from.takeIf { it >= 0 } ?: ride.to)
                    onClose()
                }
            }
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(K.rBadge + 4.dp))
                    .then(if (line != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = T("Open the line", "פתיחת הקו"), onClick = line) else Modifier)
                    .padding(horizontal = K.gap2),
                contentAlignment = Alignment.Center,
            ) { TicketBadge(t, net, ride) }
            Column(Modifier.weight(1f)) {
                val stops = listOfNotNull(from?.let { it to (ride?.from ?: -1) }, to?.let { it to (ride?.to ?: -1) })
                stops.forEachIndexed { i, (name, stop) ->
                    RideStop(name, first = i == 0, last = i == stops.lastIndex,
                        said = if (stops.size == 2 && i == 0 || stops.size == 1 && from != null) T("Boarded at $name", "עלייה ב$name") else T("To $name", "אל $name"),
                        onClick = m?.takeIf { stop >= 0 }?.let { mm -> { mm.openStation(stop); onClose() } })
                }
            }
        }
    }
}

// One stop of the ride: a dot on the rail, its name, and an arrow when it opens its board.
@Composable
private fun RideStop(name: String, first: Boolean, last: Boolean, said: String, onClick: (() -> Unit)?) {
    val rail = K.textTertiary
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp).clip(RoundedCornerShape(K.rTile))
            .then(if (onClick != null) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick) else Modifier)
            .semantics { contentDescription = said }
            .padding(end = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = size.width / 2; val y = size.height / 2; val w = 2.dp.toPx()
            if (!first) drawLine(rail, Offset(x, 0f), Offset(x, y), w)
            if (!last) drawLine(rail, Offset(x, y), Offset(x, size.height), w)
            drawCircle(rail, 5.dp.toPx(), Offset(x, y))
            drawCircle(K.surface, 2.5.dp.toPx(), Offset(x, y))
        }
        Spacer(Modifier.width(K.gap2))
        Text(name, style = K.type.body, color = K.text, modifier = Modifier.weight(1f).padding(vertical = K.gap2).clearAndSetSemantics {})
        if (onClick != null) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
    }
}

// The code, sharp, as the old tickets drew theirs: whole-pixel squares, black on white with the quiet border readers need.
@Composable
private fun Code(content: String, format: String, modifier: Modifier = Modifier) {
    val m = remember(content, format) { TicketView.matrix(content, format) } ?: return
    val quiet = 4
    Box(modifier.aspectRatio(1f).background(Color.White).semantics { contentDescription = T("Ticket code", "קוד הכרטיס") }) {
        Canvas(Modifier.fillMaxSize()) {
            val n = maxOf(m.width, m.height) + 2 * quiet
            val cell = kotlin.math.floor(size.minDimension / n).coerceAtLeast(1f)
            // On whole pixels, each row's run of dark modules as one rectangle: no faint seams between modules for a
            // reader to trip on.
            val left = kotlin.math.floor((size.width - cell * m.width) / 2); val top = kotlin.math.floor((size.height - cell * m.height) / 2)
            for (y in 0 until m.height) {
                var x = 0
                while (x < m.width) {
                    if (!m.get(x, y)) { x++; continue }
                    val start = x
                    while (x < m.width && m.get(x, y)) x++
                    drawRect(Color.Black, Offset(left + start * cell, top + y * cell), Size((x - start) * cell, cell))
                }
            }
        }
    }
}

// A ride's ticket on the trip page, as the old one sat in the ride's paying card: its row. True when there is one.
@Composable
internal fun RideTicketRow(section: RavPass.Section, line: String?, depMs: Long): Boolean {
    if (!TicketView.supported()) return false
    val t = liveTickets().filter { TicketView.matches(it, section, line, depMs) }.maxByOrNull { it.readAt } ?: return false
    TicketRow(t, inset = K.gap3) { openTicket(null, t.key) }
    return true
}

// Home's tickets, as the old ones were: rows inside the Pay card, under its own row.
@Composable
internal fun HomeTicketRows() {
    if (!TicketView.supported()) return
    val live = liveTickets()
    for (t in live.sortedByDescending { it.readAt }) {
        Separator()
        TicketRow(t) { openTicket(null, t.key) }
    }
}

// Back from Rav-Pass after a bus ticket (TicketReader.back): Kav opens on it.
@Composable
internal fun TicketOpenHost(model: uk.noammm.kav.KavModel? = null) {
    val shown = TicketStore.opened?.let { key -> liveTickets().firstOrNull { it.key == key } }
    TicketStore.opened?.let { key -> if (shown == null) LaunchedEffect(key) { kotlinx.coroutines.delay(2_000); if (TicketStore.opened == key) TicketStore.opened = null } }
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown
    androidx.compose.animation.AnimatedVisibility(shown != null, enter = modalIn(), exit = modalOut()) {
        last?.let { TicketScreen(it, model) { TicketStore.opened = null } }
    }
}

// ---- Turning it off, and Settings ----

// Turned off from Kav: what it kept goes, and the service stops itself.
internal fun turnTicketsOff(ctx: Context) {
    Prefs.setTickets(ctx, false)
    TicketStore.erase(ctx)
    TicketReader.running?.disableSelf()
}

// On the Paying page: the tickets live on this phone now.
@Composable
internal fun LiveTicketsRow() {
    val live = liveTickets()
    ListRow(
        T("Live tickets", "כרטיסים בתוקף"),
        subtitle = if (live.isEmpty()) null else live.joinToString(" · ") { t ->
            (t.line?.let { T("Line $it", "קו $it") } ?: T("Ticket", "כרטיס")) + (t.shown?.let { T(" at $it", " ב-$it") } ?: "") +
                if (t.passengers.size > 1) T(", ${t.passengers.size} passengers", ", ${t.passengers.size} נוסעים") else ""
        },
        leading = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.ConfirmationNumber, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(22.dp)) },
        subtitleMaxLines = 3,
    )
}
