package uk.noammm.kav.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.noammm.kav.TicketStore
import uk.noammm.kav.data.Ride
import uk.noammm.kav.data.RideHistory
import uk.noammm.kav.data.TicketScreens
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The ride history, as old Kav's payment history was (PayHistory.kt before 4.3.2), now from the tickets Kav's reader read
// (TicketStore.history) rather than HopOn's billing: this month and the last at the top, then every ride month by month.
internal object PayHistory {
    var open by mutableStateOf(false)
}

// year * 12 + month, from 0, in Israel time.
private fun monthKey(ms: Long): Int = Calendar.getInstance(ISRAEL).run { timeInMillis = ms; get(Calendar.YEAR) * 12 + get(Calendar.MONTH) }

private fun monthName(key: Int): String = SimpleDateFormat("MMMM yyyy", if (T.rtl) Locale("iw") else Locale.US)
    .apply { timeZone = ISRAEL }
    .format(Calendar.getInstance(ISRAEL).apply { clear(); set(key / 12, key % 12, 1, 12, 0) }.time)

// In Israel's time, as the month is counted.
private fun shortMonth(key: Int): String =
    SimpleDateFormat("MMM", if (T.rtl) Locale("iw") else Locale.US).apply { timeZone = ISRAEL }.format(
        Calendar.getInstance(ISRAEL).apply { clear(); set(key / 12, key % 12, 1) }.time,
    )

private fun rides(n: Int) = T.count(n, "1 ride", "נסיעה אחת", "rides", "נסיעות")

// Shekels as Rav-Pass writes them, from agorot.
private fun shekels(agorot: Int) = "₪" + java.math.BigDecimal(agorot).movePointLeft(2).setScale(2).toPlainString()

// The rides' quoted prices added up, only when Kav saw every one of them bought (RideHistory.total).
private fun quoted(list: List<Ride>): String? = RideHistory.total(list)?.let(::shekels)

@Composable
internal fun PayHistoryHost() {
    androidx.compose.animation.AnimatedVisibility(PayHistory.open, enter = modalIn(), exit = modalOut()) {
        PayHistoryScreen { PayHistory.open = false }
    }
}

@Composable
private fun PayHistoryScreen(onClose: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val months = TicketStore.history.groupBy { monthKey(it.at) }.toSortedMap(compareByDescending { it })
        .map { (k, v) -> k to v.sortedByDescending { it.at } }
    val current = monthKey(now)
    val thisMonth = months.firstOrNull { it.first == current }?.second.orEmpty()
    val lastMonth = months.firstOrNull { it.first == current - 1 }?.second.orEmpty()
    androidx.activity.compose.BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(K.bg).statusBarsPadding()) {
        ScreenHeader(T("Ride history", "היסטוריית נסיעות"), back = onClose)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = K.gap4, end = K.gap4, bottom = K.gap6 + LocalBottomBarInset.current),
        ) {
            item(key = "stats") {
                Column(Modifier.fillMaxWidth().panel(K.rCard).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                    // A month with every ride's price known shows its quoted total over its rides; else its rides alone.
                    val thisTotal = quoted(thisMonth)
                    val lastTotal = quoted(lastMonth)
                    Row(horizontalArrangement = Arrangement.spacedBy(K.gap6)) {
                        Stat(T("This month", "החודש"), rides(thisMonth.size), thisTotal)
                        Stat(T("Last month", "חודש שעבר"), rides(lastMonth.size), lastTotal)
                    }
                    if (thisTotal != null || lastTotal != null) Note(T("Prices are what Rav-Pass quoted when you paid. Monthly caps can lower the charge.",
                        "המחירים הם מה שרב-פס הציגה כשמשלמים. תקרות חודשיות יכולות להוריד את החיוב."))
                }
            }
            if (months.isEmpty()) item(key = "none") {
                NoteCard(T("Rides you pay for with Rav-Pass show here.", "נסיעות שתשלמו עליהן ברב-פס יופיעו כאן."), Modifier.padding(top = K.gap4))
            }
            months.forEach { (key, list) ->
                item(key = "m$key") {
                    Row(Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, top = K.gap6, bottom = K.gap2),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(monthName(key), style = K.type.header, modifier = Modifier.weight(1f))
                        Text(listOfNotNull(rides(list.size), quoted(list)?.let(T::ltr)).joinToString(" · "), style = K.type.subhead)
                    }
                }
                itemsIndexed(list, key = { _, r -> "r${r.key}" }) { i, r -> RideRow(r, i == 0, i == list.lastIndex) }
            }
        }
    }
}

@Composable
private fun Stat(label: String, count: String, price: String?) {
    Column {
        Text(label, style = K.type.subhead)
        Text(price?.let(T::ltr) ?: count, style = K.type.largeTitle.tabular, color = K.text)
        if (price != null) Text(count, style = K.type.subhead)
    }
}

// One ride; its ticket opens when Kav still shows it.
@Composable
private fun RideRow(r: Ride, first: Boolean, last: Boolean) {
    val live = liveTickets().firstOrNull { it.key == r.key }
    val time = SimpleDateFormat("d.M  $CLOCK", Locale.US).apply { timeZone = ISRAEL }
    Row(
        Modifier.fillMaxWidth().groupItem(first, last)
            .clickable(enabled = live != null, role = Role.Button) { TicketStore.opened = r.key }
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        ModeGlyph(when (r.kind) {
            TicketScreens.Kind.BUS -> Mode.BUS
            TicketScreens.Kind.LIGHT_RAIL -> Mode.TRAM
            else -> Mode.TRAIN
        }, K.textSecondary, 20.dp)
        Column(Modifier.weight(1f)) {
            val name = listOfNotNull(r.line?.let { T("Line $it", "קו $it") }, r.to).joinToString(" · ").ifBlank {
                when (r.kind) {
                    TicketScreens.Kind.BUS -> T("Bus", "אוטובוס")
                    TicketScreens.Kind.LIGHT_RAIL -> T("Light rail", "רכבת קלה")
                    TicketScreens.Kind.TRAIN_ENTRANCE -> T("Train entrance", "כניסה לרכבת")
                    TicketScreens.Kind.TRAIN_EXIT -> T("Train exit", "יציאה מהרכבת")
                }
            }
            Text(name + if (r.riders > 1) "  ×${r.riders}" else "", style = K.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(T.ltr(time.format(Date(r.at))) + (r.from?.let { "  $it" } ?: ""), style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        r.price?.let { Text(T.ltr(shekels(it)), style = K.type.body.tabular, color = K.text) }
    }
}

// Home's look at it, inside the pay card under the tickets: this month's rides, and the six months as bars with this one
// in the accent. A tap opens the history.
@Composable
internal fun RideSummary() {
    val history = TicketStore.history
    if (history.isEmpty()) return
    val current = monthKey(System.currentTimeMillis())
    val keys = (current - 5..current).toList()
    val by = history.groupBy { monthKey(it.at) }
    val counts = keys.map { by[it]?.size ?: 0 }
    Separator()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = T("Open the ride history", "פתיחת היסטוריית הנסיעות")) { PayHistory.open = true }
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap4),
    ) {
        Column(Modifier.weight(1f)) {
            Text(T("This month", "החודש"), style = K.type.subhead)
            val month = by[current].orEmpty()
            RollingText(listOfNotNull(quoted(month)?.let(T::ltr), rides(counts.last())).joinToString(" · "), style = K.type.headline)
        }
        RideBars(keys, counts, Modifier.semantics {
            contentDescription = T("Rides per month: ", "נסיעות לפי חודש: ") + keys.zip(counts).joinToString(", ") { (m, n) -> shortMonth(m) + " " + n }
        })
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp))
    }
}

// Six thin bars on one baseline, rounded at the top, the month's initial under each; they spring to new heights.
@Composable
private fun RideBars(keys: List<Int>, counts: List<Int>, modifier: Modifier = Modifier) {
    val top = (counts.maxOrNull()?.takeIf { it > 0 } ?: 1).toFloat()
    val heights = counts.map { animateFloatAsState(it / top, spring(dampingRatio = .8f, stiffness = 300f), label = "rideBar").value }
    val past = K.textTertiary
    val now = K.accentInk
    val floor = K.fillStrong
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(width = 96.dp, height = 32.dp)) {
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (keys.size - 1)) / keys.size
            val r = minOf(w / 2, 4.dp.toPx())
            keys.indices.forEach { i ->
                val x = mirrorX((i * (w + gap) + w / 2) / size.width) * size.width - w / 2
                val h = heights[i] * size.height
                if (h < 1f) drawRoundRect(floor, Offset(x, size.height - 2.dp.toPx()), Size(w, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                else drawPath(androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(x, size.height - h, x + w, size.height,
                        topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
                }, if (i == keys.lastIndex) now else past)
            }
        }
        Spacer(Modifier.height(K.gap1))
        Row(Modifier.width(96.dp)) {
            keys.forEachIndexed { i, k ->
                Text(shortMonth(k).take(1), style = K.type.caption, color = if (i == keys.lastIndex) K.text else K.textTertiary,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}
