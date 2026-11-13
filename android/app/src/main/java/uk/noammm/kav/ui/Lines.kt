package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.timetable
import uk.noammm.kav.data.shownName
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.representativeTrip
import uk.noammm.kav.data.lineTimesAt
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.searchRoutes
import uk.noammm.kav.data.trainLine
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState

private val FILTERS = listOf(
    "All" to intArrayOf(),
    "Bus" to intArrayOf(3, 11, 715, 8),
    "Light rail" to intArrayOf(0, 1, 12),
    "Israel Railways" to intArrayOf(2),
    "Carmelit / Rakavlit" to intArrayOf(7, 5, 6),
    "Shuttle" to intArrayOf(711),
)

private fun filterLabel(label: String): String = when (label) {
    "Bus" -> T("Bus", "אוטובוס")
    "Light rail" -> T("Light rail", "רכבת קלה")
    "Israel Railways" -> T("Israel Railways", "רכבת ישראל")
    "Carmelit / Rakavlit" -> T("Carmelit / Rakavlit", "כרמלית / רכבלית")
    "Shuttle" -> T("Shuttle", "שאטל")
    else -> T("All", "הכול")
}

@Composable
fun LinesScreen(model: KavModel) {
    WithTimetable(model) { net -> LinesBody(model, net) }
}

@Composable
private fun LinesBody(model: KavModel, net: Net) {
    // A provider's line stays open only while it's still the timetable picked.
    val line = model.scheduleLine?.takeIf { model.scheduleSource == Schedules.source }
    androidx.activity.compose.BackHandler(model.lineRoute >= 0 || line != null || model.returnsTo(uk.noammm.kav.Tab.Lines)) { model.backFromLine() }
    val list = remember(net) { lineListFor(net) }
    androidx.compose.animation.AnimatedContent(
        targetState = Triple(model.lineRoute, line, Schedules.source),
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { if (targetState.first >= 0 || targetState.second != null) forward() else backward() },
        label = "line",
    ) { (route, open, source) ->
        when {
            open != null -> ScheduleLineDetail(model, net, open) { model.backFromLine() }
            route >= 0 -> TimetableLineDetail(model, net, route) { model.backFromLine() }
            source.timetable != null -> ScheduleLineList(model, net, list)
            else -> LineList(model, net, list)
        }
    }
}

// Kept across tab switches, and filled as soon as the timetable loads, so Recent is there the moment Lines opens.
private var kept: Pair<Net, LineListState>? = null

private fun lineListFor(net: Net): LineListState =
    kept?.takeIf { it.first === net }?.second ?: LineListState().also { kept = net to it }

suspend fun warmLines(ctx: android.content.Context, net: Net, here: Pair<Double, Double>?) {
    // First the list Lines opens on, the provider's when it's the timetable picked.
    warmSchedule(ctx, net, here)
    val list = lineListFor(net)
    if (list.leadReady) return
    withContext(Dispatchers.Default) {
        val ends = lineEndpoints(net)
        val sections = foldLines(net, "", FILTERS[0].second, ends)
        val lead = leadSections(ctx, net, here, ends)
        withContext(Dispatchers.Main) {
            list.endpoints = ends; list.sections = sections; list.lead = lead; list.leadReady = true
        }
    }
}

// The provider's list as well, from its copy on the phone, so Lines opens with it whole. Opening the tab still asks the
// provider when the copy is due.
private suspend fun warmSchedule(ctx: android.content.Context, net: Net, here: Pair<Double, Double>?) {
    val source = Schedules.source
    if (source.timetable == null) return
    val state = scheduleListFor(source)
    if (state.lines != null) return
    val lines = withContext(Dispatchers.IO) { runCatching { Schedules.keptLines(ctx, source) }.getOrNull() } ?: return
    val types = FILTERS[0].second
    val saved = uk.noammm.kav.Prefs.savedLines(ctx)
    val sections = withContext(Dispatchers.Default) { foldSchedule(lines, "", types) }
    val lead = withContext(Dispatchers.Default) { scheduleLead(ctx, net, here, lines) }
    if (state.lines != null || Schedules.source != source) return
    state.lines = lines
    state.sections = sections; state.sectionsFor = state.sectionsKey(lines, "", types)
    state.lead = lead; state.leadFor = state.leadKey(lines, here, "", types, saved); state.leadReady = true
}

// Kept while a line is open, so coming back lands where the list was.
private class LineListState {
    var types by mutableStateOf(FILTERS[0].second)
    val scroll = LazyListState()
    var scrolled by mutableStateOf(false)
    var sections by mutableStateOf(emptyList<Pair<String, List<LineRow>>>())
    var endpoints by mutableStateOf(emptyMap<Int, Pair<Int, Int>>())
    var lead by mutableStateOf(emptyList<Pair<String, List<LineRow>>>())
    var leadReady by mutableStateOf(false)
}

// note: why these are the week's lines, when the timetable's provider couldn't be asked.
@Composable
private fun LineList(model: KavModel, net: Net, list: LineListState, note: String? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by model::lineQuery
    var types by list::types
    val listState = list.scroll
    var sections by list::sections
    var endpoints by list::endpoints
    LaunchedEffect(net) {
        if (endpoints.isEmpty()) endpoints = withContext(Dispatchers.Default) { lineEndpoints(net) }
    }
    LaunchedEffect(net, q, types, endpoints) {
        sections = withContext(Dispatchers.Default) { foldLines(net, q, types, endpoints) }
    }
    var lead by list::lead
    LaunchedEffect(Unit) {
        if (model.here == null && uk.noammm.kav.hasLocationPermission(ctx)) {
            uk.noammm.kav.requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        }
    }
    LaunchedEffect(net, endpoints, model.here, q, types, model.savedLines) {
        val here = model.here
        lead = if (q.isNotBlank()) {
            if (here == null) emptyList()
            else withContext(Dispatchers.Default) {
                val rows = foldRoutes(net, nearestMatches(net, q, types, here), endpoints, namesakeEnds(net, endpoints)).take(NEAREST_SHOWN)
                if (rows.isEmpty()) emptyList() else listOf(T("Nearest to you", "הקרובים אליכם") to rows)
            }
        } else if (types.isNotEmpty()) emptyList()
        else withContext(Dispatchers.Default) { leadSections(ctx, net, here, endpoints) }
        list.leadReady = true
    }
    // The full list waits for Recent and Nearby, so it doesn't flash up first and then get pushed down.
    val listReady = list.leadReady || q.isNotBlank() || types.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Lines", "קווים"))
        var scrolled by list::scrolled
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { if (it) scrolled = true }
        }
        LaunchedEffect(lead) { if (lead.isNotEmpty() && !scrolled) listState.scrollToItem(0) }
        Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(
            start = K.gap4, end = K.gap4, bottom = LocalBottomBarInset.current + K.gap4,
        )) {
            if (note != null) item(key = "note") { NoteCard(note, Modifier.animateItem().padding(bottom = K.gap3)) }
            item(key = "search") { LineSearch(q, { q = it }, types) { types = it } }
            item(key = "count") {
                val total = sections.sumOf { it.second.size }
                // The count rolls as a search or a filter narrows the list.
                LiveText(
                    T.count(total, "1 line", "קו אחד", "lines", "קווים"), style = K.type.caption.tabular,
                    modifier = Modifier.padding(horizontal = K.gap4),
                )
            }
            lead.forEach { (label, rows) ->
                item(key = "lead-$label") { Box(Modifier.animateItem()) { SectionLabel(label) } }
                itemsIndexed(rows, key = { _, it -> "lead-$label-${it.route}" }) { i, row -> Box(Modifier.animateItem()) { LineRowCard(model, net, row, i == 0, i == rows.lastIndex) } }
            }
            if (listReady) sections.forEach { (operator, rows) ->
                if (operator.isNotBlank()) item(key = "op-$operator") {
                    Box(Modifier.animateItem()) { SectionLabel(operatorLabel(operator)) }
                }
                itemsIndexed(rows, key = { _, it -> it.route }) { i, row -> Box(Modifier.animateItem()) { LineRowCard(model, net, row, i == 0, i == rows.lastIndex) } }
            }
        }
        ScrollEdge(listState.canScrollBackward)
        }
    }
}

// The search card and the mode chips over a list of lines.
@Composable
private fun LineSearch(q: String, onQ: (String) -> Unit, types: IntArray, onTypes: (IntArray) -> Unit) {
    Column {
        // The same card as the Stops tab's search.
        Box(Modifier.fillMaxWidth().panel(K.rCard)) {
            KavField(q, onQ, T("Line number or name", "מספר או שם קו"), Modifier.fillMaxWidth(), search = true, flat = true)
        }
        Spacer(Modifier.height(K.gap2))
        Row(
            Modifier
                .bleed(K.gap4)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = K.gap4),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            FILTERS.forEach { (label, t) -> Chip(filterLabel(label), types === t) { onTypes(t) } }
        }
        Spacer(Modifier.height(K.gap3))
    }
}

private fun Modifier.bleed(by: androidx.compose.ui.unit.Dp): Modifier = layout { measurable, constraints ->
    val side = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * side, maxWidth = constraints.maxWidth + 2 * side),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-side, 0) }
}

@Composable
private fun SectionLabel(label: String) = SectionHeader(label)

@Composable
private fun LineRowCard(model: KavModel, net: Net, row: LineRow, first: Boolean, last: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .groupItem(first, last)
            .clickable(role = Role.Button) { model.lineRoute = row.route }
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LineIdentity(net.rType[row.route], net.rShort[row.route], busOperator(net.rType[row.route], -1) { Operators.of(net, row.route) })
        LineSpan(net, row, Modifier.weight(1f))
        RowChevron()
    }
}

// The chevron at the end of a row that opens something.
@Composable
private fun RowChevron() = Icon(
    Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp),
)

private fun lineEndpoints(net: Net): Map<Int, Pair<Int, Int>> {
    val longest = IntArray(net.nRoutes) { -1 }
    for (t in net.tripRoute.indices) {
        val route = net.tripRoute[t]
        val previous = longest[route]
        if (previous < 0 || net.tripStart[t + 1] - net.tripStart[t] >
            net.tripStart[previous + 1] - net.tripStart[previous]) longest[route] = t
    }
    return buildMap {
        longest.forEachIndexed { route, t ->
            if (t >= 0 && net.tripStart[t + 1] > net.tripStart[t])
                put(route, net.stStop[net.tripStart[t]] to net.stStop[net.tripStart[t + 1] - 1])
        }
    }
}

private data class LineRow(val route: Int, val a: Int, val b: Int, val both: Boolean)

private fun terminusName(net: Net, stop: Int): String = net.name[stop].substringBefore('/').trim()

// A line's end as its row shows it, up to any "/": in English by the whole stop's translation first, as the part before
// "/" seldom has one of its own.
internal fun shownEnd(net: Net, stop: Int): String =
    net.nameEn.getOrNull(stop)?.takeIf { StopNames.english && it.isNotEmpty() }?.substringBefore('/')?.trim()
        ?: net.shown(terminusName(net, stop))

// The same for a provider's line, whose ends are the ministry's names. A mark after one (a ✈ on trains to the
// airport) stays after the name as shown.
internal fun providerEnd(name: String): String {
    val core = name.trim().trimEnd { it.isWhitespace() || it.category == CharCategory.OTHER_SYMBOL || it == '\uFE0E' || it == '\uFE0F' }
    val mark = name.trim().substring(core.length).trim()
    val shown = shownName(core).takeIf { it != core }?.substringBefore('/')?.trim() ?: shownName(core.substringBefore('/').trim())
    return if (mark.isEmpty()) shown else "$shown $mark"
}

internal fun lineKey(net: Net, r: Int) =
    "${net.rAgency.getOrElse(r) { -1 }}|${net.rType[r]}|${net.rShort[r]}|${net.rLong[r]}"

private fun namesakeEnds(net: Net, ends: Map<Int, Pair<Int, Int>>): Map<String, Pair<Int, Int>> {
    val byName = HashMap<String, Pair<Int, Int>>(ends.size * 2)
    for ((r, e) in ends) byName.putIfAbsent(lineKey(net, r), e)
    return byName
}

private fun foldRoutes(
    net: Net, routes: Iterable<Int>, ends: Map<Int, Pair<Int, Int>>,
    byName: Map<String, Pair<Int, Int>>,
): Collection<LineRow> {
    val rows = LinkedHashMap<String, LineRow>()
    for (r in routes) {
        val e = ends[r] ?: byName[lineKey(net, r)]
        if (e == null && ends.isNotEmpty()) continue
        val key = if (e == null) "lone-$r" else {
            val a = terminusName(net, e.first); val b = terminusName(net, e.second)
            val (lo, hi) = if (a <= b) a to b else b to a
            "${net.rAgency.getOrElse(r) { -1 }}|${net.rType[r]}|${net.rShort[r]}|$lo $hi"
        }
        val prev = rows[key]
        if (prev == null) rows[key] = LineRow(r, e?.first ?: -1, e?.second ?: -1, false)
        else if (!prev.both) rows[key] = prev.copy(both = true)
    }
    return rows.values
}

private fun foldLines(
    net: Net, q: String, types: IntArray, ends: Map<Int, Pair<Int, Int>>,
): List<Pair<String, List<LineRow>>> {
    val rows = foldRoutes(net, net.searchRoutes(q, types).asIterable(), ends, namesakeEnds(net, ends))
    return rows.groupBy { net.agencyOf(it.route) }.mapValues { (_, rs) ->
        rs.sortedWith(compareBy(
            { net.rShort[it.route].toIntOrNull() ?: Int.MAX_VALUE },
            { net.rShort[it.route] },
            { if (it.a >= 0) terminusName(net, it.a) else "" },
        ))
    }.toList()
}

// A search's lines nearest the rider, by their nearest stop: the ones numbered exactly as searched when there are any
// (27, not 270), so the 27 that stops near you is first, not wherever its operator's list puts it.
private fun nearestMatches(net: Net, q: String, types: IntArray, here: Pair<Double, Double>): List<Int> {
    val found = net.searchRoutes(q, types)
    val number = q.trim().lowercase().split(Regex("\\s+")).firstOrNull { t -> t.any(Char::isDigit) }
    val exact = found.filter { net.rShort[it].lowercase() == number }
    val wanted = (exact.ifEmpty { found.toList() }).toHashSet()
    if (wanted.isEmpty()) return emptyList()
    val near = HashMap<Int, Double>()
    // One trip a route is enough to place it: its ways and variants share their stops near the rider.
    for (t in net.tripRoute.indices) {
        val r = net.tripRoute[t]
        if (r !in wanted || r in near) continue
        var best = Double.MAX_VALUE
        for (i in net.tripStart[t] until net.tripStart[t + 1]) {
            val st = net.stStop[i]
            best = minOf(best, metres(here.first, here.second, net.lat[st], net.lon[st]))
        }
        near[r] = best
    }
    return near.entries.sortedBy { it.value }.map { it.key }
}

private const val NEAREST_SHOWN = 3

private fun leadSections(
    ctx: android.content.Context, net: Net, here: Pair<Double, Double>?,
    ends: Map<Int, Pair<Int, Int>>,
): List<Pair<String, List<LineRow>>> {
    if (ends.isEmpty()) return emptyList()
    val byName = namesakeEnds(net, ends)
    val out = ArrayList<Pair<String, List<LineRow>>>()

    // Lines by their keys, in the keys' order.
    fun keyed(keys: List<String>): List<LineRow> {
        val slot = HashMap<String, Int>()
        keys.forEachIndexed { i, k -> slot.putIfAbsent(k, i) }
        val routes = arrayOfNulls<Int>(keys.size)
        for (r in 0 until net.nRoutes) {
            val i = slot[lineKey(net, r)] ?: continue
            if (routes[i] == null) routes[i] = r
        }
        return foldRoutes(net, routes.filterNotNull(), ends, byName).toList()
    }
    // Saved from a line's page.
    val saved = keyed(uk.noammm.kav.Prefs.savedLines(ctx))
    if (saved.isNotEmpty()) out.add(T("Saved", "שמורים") to saved)
    val recent = keyed(uk.noammm.kav.Prefs.recentLines(ctx))
    if (recent.isNotEmpty()) out.add(T("Recent", "אחרונים") to recent)

    if (here != null) {
        val near = net.nearestStops(here.first, here.second, k = 20, radius = 900.0)
        if (near.isNotEmpty()) {
            val rank = HashMap<Int, Int>(near.size * 2)
            near.forEachIndexed { i, (s, _) -> rank[s] = i }
            val best = HashMap<Int, Int>()
            for (t in net.tripRoute.indices) {
                val route = net.tripRoute[t]
                for (i in net.tripStart[t] until net.tripStart[t + 1]) {
                    val o = rank[net.stStop[i]] ?: continue
                    val held = best[route]
                    if (held == null || o < held) best[route] = o
                }
            }
            val routes = best.entries.sortedBy { it.value }.map { it.key }
            val rows = foldRoutes(net, routes, ends, byName).take(10)
            if (rows.isNotEmpty()) out.add(T("Nearby", "בקרבת מקום") to rows)
        }
    }
    return out
}

private fun operatorLabel(name: String): String = when (name) {
    "רכבת ישראל" -> T("Israel Railways", "רכבת ישראל")
    "כרמלית" -> T("Carmelit", "כרמלית")
    "כבל אקספרס" -> T("Rakavlit (Cable Express)", "רכבלית (כבל אקספרס)")
    "דן נתיבים בעמ" -> T("Dan Nativim", "דן נתיבים")
    else -> name
}

@Composable
private fun LineSpan(net: Net, row: LineRow, modifier: Modifier = Modifier) {
    if (row.a < 0) {
        Text(T("Route stops", "תחנות המסלול"), style = K.type.body, color = K.muted, modifier = modifier)
        return
    }
    val a = shownEnd(net, row.a); val b = shownEnd(net, row.b)
    val text = when {
        a == b -> "\u2068$a\u2069"
        row.both -> "\u2068$a\u2069 ↔ \u2068$b\u2069"
        else -> T("\u2068$a\u2069 → \u2068$b\u2069", "\u2068$a\u2069 ← \u2068$b\u2069")
    }
    Text(
        text, style = K.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

// A line as the stop boards show it: its plate, with the mode's mark and the number, at least as wide as a three-digit
// line's so a list lines up, and a longer number as wide as it needs.
@Composable
private fun LineIdentity(rt: Int, number: String, operator: Color? = null) {
    val density = LocalDensity.current
    val fonts = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
    val plateWidth = remember(density, fonts, direction) { threeDigitPlate(density, fonts, direction) }
    LinePlateBadge(number.ifBlank { null }, modeOf(rt), linePlate(rt, -1) { operator }, Modifier.widthIn(min = plateWidth))
}

// The same for every row, so it's measured once rather than for each row as it scrolls into view.
private var threeDigits: Pair<List<Any>, androidx.compose.ui.unit.Dp>? = null

private fun threeDigitPlate(
    density: androidx.compose.ui.unit.Density, fonts: androidx.compose.ui.text.font.FontFamily.Resolver,
    direction: androidx.compose.ui.unit.LayoutDirection,
): androidx.compose.ui.unit.Dp {
    val style = K.type.bodyStrong.tabular.copy(fontWeight = FontWeight.Medium)
    val key = listOf(density, fonts, direction, style)
    threeDigits?.takeIf { it.first == key }?.let { return it.second }
    val width = with(density) {
        linePlateWidth(androidx.compose.ui.text.TextMeasurer(fonts, density, direction, 1).measure("888", style).size.width.toDp())
    }
    threeDigits = key to width
    return width
}

@Composable
private fun LineAtStop(net: Net, routes: Set<Int>, stop: Int, last: Boolean, onBoard: () -> Unit) {
    val now = nowSec()
    val times = remember(routes, stop) {
        if (last) emptyList()
        else {
            val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
            routes.flatMap { net.lineTimesAt(stop, it, today) }.distinct().sorted()
        }
    }
    val train = routes.any { net.rType[it] == 2 }
    var all by remember(routes, stop) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(start = K.gap3 + 18.dp + K.gap3, end = K.gap3, bottom = K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        val next = times.filter { it >= now }.take(3)
        LiveText(
            when {
                last -> T("This line ends here.", "הקו מסתיים כאן.")
                next.isEmpty() && train -> T("No more trains on this line today.", "אין עוד רכבות בקו הזה היום.")
                next.isEmpty() -> T("No more buses on this line today.", "אין עוד יציאות בקו הזה היום.")
                else -> T("Next: ", "הבאים: ") + next.joinToString(" · ") { relative(it, now) ?: hhmm(it) }
            },
            style = K.type.body.tabular,
        )
        androidx.compose.animation.AnimatedVisibility(
            all,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
            Text(times.joinToString("   ") { hhmm(it) }, style = K.type.subhead.tabular, lineHeight = 22.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            if (times.isNotEmpty()) KavButton(
                if (all) T("Hide schedule", "הסתרת לוח הזמנים") else T("Today's schedule", "לוח הזמנים להיום"),
                { all = !all }, style = ButtonStyle.Secondary,
            )
            KavButton(T("Stop board", "לוח התחנה"), onBoard, style = ButtonStyle.Secondary)
        }
    }
}

// shape: the line's road from Kav's server.
private class LineLive(
    val checked: Boolean,
    val failed: Boolean = false,
    val vehicles: List<Transit.Arrival> = emptyList(),
    val shape: List<Pair<Double, Double>> = emptyList(),
)

// The line's buses, every 20 s while the page is on screen. route: the week's route, asked by its number and name, and
// stops its stops; else (-1) a provider's line the week hasn't, by its number and where its way ends. The same line keeps
// what it last found while its stops change (the provider's way taking the week's place) and starts over only for
// another line or another live source.
// A way of the line switched to shows its own live state at once, as last found for it (LinePage asks for the other ways
// too while it shows), never checking again from nothing.
@Composable
private fun rememberLive(net: Net, route: Int, stops: List<Int>, line: Schedules.Line?, way: Schedules.Way?): LineLive {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val about = liveKey(route, line, way)
    val live = remember { mutableStateOf(LineLives.get(about) ?: LineLive(checked = false)) }
    val shownFor = remember { arrayOfNulls<Any>(1) }
    LaunchedEffect(about, stops, lifecycle) {
        if (shownFor[0] != about) { live.value = LineLives.get(about) ?: LineLive(checked = false); shownFor[0] = about }
        if (if (route >= 0) stops.isEmpty() else line == null || way == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val tracks = HashMap<Int, List<Pair<Double, Double>>>()
            while (true) {
                live.value = try {
                    withContext(Dispatchers.IO) { lineLiveNow(net, route, stops, line, way, tracks) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (live.value.checked && !live.value.failed) live.value else LineLive(checked = true, failed = true)
                }
                LineLives.put(about, live.value)
                delay(20_000)
            }
        }
    }
    return live.value
}

// What a line's live state is kept by: the week's route it's asked by, else the provider's line and way; and the source.
private fun liveKey(route: Int, line: Schedules.Line?, way: Schedules.Way?): List<Any?> =
    listOf(if (route >= 0) "route $route" else "line ${line?.id} ${way?.id}", KavLive.source, KavLive.server)

// Each way's live state as last found, for the session.
private object LineLives {
    private val kept = HashMap<List<Any?>, LineLive>()
    fun get(key: List<Any?>): LineLive? = synchronized(kept) { kept[key] }
    fun put(key: List<Any?>, live: LineLive) = synchronized(kept) { kept[key] = live }
}

// What a provider's way is asked for live by: the week's route of the same name, with its stops, where the week has
// them all; else (-1) the provider's line and way themselves.
private fun liveAsk(net: Net, line: Schedules.Line, way: Schedules.Way): Pair<Int, List<Int>> {
    val route = line.names.getOrNull(line.routes.indexOf(way.id))?.let { name ->
        (0 until net.nRoutes).firstOrNull { net.rLong[it] == name && (line.type == 2 || net.rShort[it] == line.number) }
    } ?: -1
    val stops = way.stops.map { KavLive.stopOfCode(net, it.code) }
    return if (route >= 0 && stops.isNotEmpty() && stops.all { it >= 0 }) route to stops else -1 to emptyList()
}

// The line's buses now, as rememberLive asks every 20 s. tracks: a train line's track by the route of a train of it
// under way, each asked once.
private fun lineLiveNow(
    net: Net, route: Int, stops: List<Int>, line: Schedules.Line?, way: Schedules.Way?,
    tracks: HashMap<Int, List<Pair<Double, Double>>>,
): LineLive {
    // The timetable gives a train no number to look its line up by, and each train a route of its own.
    val number = if (route >= 0) net.rShort[route].trim() else line!!.number.trim()
    val train = route >= 0 && net.rType[route] == 2 && number.isEmpty()
    return when {
        train -> {
            val on = KavLive.trainsOn(net, net.trainLine(route), stops)
            val track = on.map { -it.lineId }.distinct().firstNotNullOfOrNull { r ->
                tracks.getOrPut(r) { runCatching { KavLive.shape(r) }.getOrDefault(emptyList()) }.takeIf { it.size >= 2 }
            }
            LineLive(checked = true, vehicles = on, shape = track.orEmpty())
        }
        route >= 0 -> serverLineLive(number, net.rLong[route].trim(), stops.lastOrNull()?.let { net.name[it] }, stops.map { net.code[it] })
        line!!.type == 2 || number.isBlank() -> LineLive(checked = true)
        else -> {
            val w = way!!
            val name = line.names.getOrNull(line.routes.indexOf(w.id)).orEmpty()
            // Live providers use the ministry's Hebrew names even when the timetable's page is in English.
            val last = w.stops.lastOrNull()
            val end = last?.let { KavLive.stopOfCode(net, it.code) } ?: -1
            serverLineLive(number, name, if (end >= 0) net.name[end] else last?.name, w.stops.map { it.code })
        }
    }
}

// The line's buses from Kav's server: the routes with its number that are this one, by the ministry's name for it, or
// for a timetable a little older than the ministry's, by where they end. With the name, the road the route takes too,
// whether or not a bus is on it now: the line is drawn on its streets, not stop to stop.
private fun serverLineLive(number: String, name: String, last: String?, codes: List<Int>): LineLive {
    val area = KavLive.line(number, codes)
    val exact = area.lines.filterValues { name.isNotBlank() && it.name == name }.keys
    val routes = exact.ifEmpty { area.lines.filterValues { last != null && it.to == last }.keys }
    val shape = exact.minOrNull()?.let { runCatching { KavLive.shape(it) }.getOrNull() }.orEmpty()
    return LineLive(checked = true, vehicles = area.buses.filter { it.route in routes }.map { KavLive.busArrival(it) }, shape = shape)
}

@Composable
private fun LineRouteMap(
    calls: List<Pair<Double, Double>>, shape: List<Pair<Double, Double>>, rt: Int,
    vehicles: List<Transit.Arrival>, modifier: Modifier, selected: Int = -1, onSelect: ((Int) -> Unit)? = null,
    operator: Color? = null, trains: Boolean = rt == 2, frame: Any? = null,
) {
    val tint = plateFor(rt)?.fill ?: operator?.takeIf(::hued)?.let { onScreen(it, MapInk.light) } ?: MapInk.route
    val path = shape.ifEmpty { calls }
    // A new road for the line (the provider's in place of the week's, a road in place of stop to stop) glides over from
    // the one drawn, rather than taking its place at once.
    var drawn by remember { mutableStateOf(path) }
    LaunchedEffect(path) {
        if (drawn == path || drawn.size < 2 || path.size < 2) { drawn = path; return@LaunchedEffect }
        val from = resampled(drawn, 160)
        val to = resampled(path, 160)
        androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { t, _ ->
            drawn = if (t >= 1f) path else from.indices.map { i ->
                (from[i].first + (to[i].first - from[i].first) * t) to (from[i].second + (to[i].second - from[i].second) * t)
            }
        }
        drawn = path
    }
    // A train line's trains are where their times put them (KavLive.withTrainsPlaced), which the ministry's positions
    // for them don't change: those can stay where a train stood, with fresh times, while it runs on.
    val spots = rememberKavSpots(remember(vehicles, trains) { if (trains) emptyList() else KavLive.routesOf(vehicles) })
    val shown = remember(vehicles, spots) { vehicles.map { KavLive.fresher(it, spots) } }
    val placed = remember(path, calls) { calls.map { onRoute(it, path) } }
    // Another way of the line (frame): its stops' circles go first, the road glides to the new one as the map reframes,
    // and only then do the new way's circles come (WAY_SWITCH_MS), so none stand on a road that's moving. Going and
    // coming, they fade as the map fades any stop leaving or joining its list (TileMap), drawn frame by frame.
    val circles = remember { WayCircles(frame, placed to selected) }
    if (circles.frame != frame) { circles.frame = frame; circles.fading = true; circles.switches++ }
    if (!circles.fading) circles.shown = placed to selected
    val switches = circles.switches
    var between by remember { mutableStateOf(false) }
    LaunchedEffect(switches) {
        if (switches == 0) return@LaunchedEffect
        between = true
        kotlinx.coroutines.delay(WAY_SWITCH_MS.toLong())
        circles.fading = false
        between = false
    }
    val (stops, chosen) = circles.shown
    val geometry = remember(drawn, stops, tint, MapInk.bg, MapInk.text, MapInk.accent, chosen, between) {
        val start = stops.first()
        val end = stops.last()
        MapGeometry(
            lines = listOf(MapLine(drawn, tint, 4f, casing = 8f)),
            dots = if (between) emptyList() else stops.drop(1).dropLast(1).flatMap { (lat, lon) ->
                listOf(MapDot(lat, lon, MapInk.bg, 6f), MapDot(lat, lon, Color.Transparent, 3.5f, tint, 2f))
            } + listOf(
                MapDot(start.first, start.second, MapInk.bg, 7f),
                MapDot(start.first, start.second, Color.Transparent, 5f, MapInk.text, 2f),
                MapDot(end.first, end.second, MapInk.bg, 8f),
                MapDot(end.first, end.second, MapInk.text, 5f),
            ) + listOfNotNull(stops.getOrNull(chosen)).flatMap { (lat, lon) ->
                listOf(MapDot(lat, lon, MapInk.bg, 11f), MapDot(lat, lon, Color.Transparent, 7f, MapInk.accent, 3f), MapDot(lat, lon, MapInk.text, 2.5f))
            },
        )
    }
    val mode = modeOf(rt)
    val reach = with(LocalDensity.current) { 22.dp.toPx() }
    // The open stop with the stops either side of it, so it's seen where it is on the line.
    val framed = if (selected in placed.indices) placed.subList((selected - 1).coerceAtLeast(0), (selected + 2).coerceAtMost(placed.size)) else path
    TileMap(
        // frame: a new one (another way of the line) frames the map again.
        framed, modifier, geometry = geometry, focusKey = selected to frame,
        live = vehicleGeometry(shown.map { it to mode }, LocalDensity.current, track = { path }),
        onTap = onSelect?.let { pick ->
            { at, proj ->
                placed.indices.map { it to (proj.point(placed[it].first, placed[it].second) - at).getDistance() }
                    .filter { it.second <= reach }.minByOrNull { it.second }?.let { pick(it.first) }
            }
        },
    )
}

// A path as n points evenly spaced along it, for gliding one road into another.
private fun resampled(path: List<Pair<Double, Double>>, n: Int): List<Pair<Double, Double>> {
    val k = kotlin.math.cos(Math.toRadians(path.first().first))
    val at = DoubleArray(path.size)
    for (i in 1 until path.size) {
        val dy = path[i].first - path[i - 1].first; val dx = (path[i].second - path[i - 1].second) * k
        at[i] = at[i - 1] + kotlin.math.sqrt(dx * dx + dy * dy)
    }
    val total = at.last()
    if (total <= 0.0) return List(n) { path.first() }
    var j = 1
    return List(n) { i ->
        val goal = total * i / (n - 1)
        while (j < path.lastIndex && at[j] < goal) j++
        val seg = at[j] - at[j - 1]
        val f = if (seg <= 0.0) 1.0 else ((goal - at[j - 1]) / seg).coerceIn(0.0, 1.0)
        (path[j - 1].first + (path[j].first - path[j - 1].first) * f) to (path[j - 1].second + (path[j].second - path[j - 1].second) * f)
    }
}

@Composable
// status: the line's live location alone, under its map; else its next departures and how busy it is, above it.
private fun LineLiveNote(
    fresh: LineLive, next: NextStart?, busy: Pair<String, Pair<uk.noammm.kav.data.GoogleCrowd.Crowd, Long?>>? = null,
    status: Boolean = false,
) {
    // Never a "Checking...": until the first answer the note keeps the last it had (the week's line handing over to the
    // provider's), else holds its place unseen.
    var kept by remember { mutableStateOf<LineLive?>(null) }
    if (fresh.checked) kept = fresh
    val live = if (fresh.checked) fresh else kept ?: fresh
    val count = live.vehicles.size
    val text = when {
        !live.checked -> ""
        live.failed -> T("Couldn't check for a live location", "לא ניתן היה לבדוק מיקום בזמן אמת")
        count == 0 -> T("No live location right now", "אין כרגע מיקום בזמן אמת")
        else -> T("Live location · $count on the road", "מיקום בזמן אמת · $count בדרך")
    }
    // What's found fades in, and the next departure slides open under it, instead of popping in.
    var shownNext by remember { mutableStateOf(next) }
    if (next != null) shownNext = next
    val tint by animateColorAsState(
        if (!live.checked) Color.Transparent else if (count > 0) K.realtime else K.textTertiary, tween(200), label = "lineLiveTint",
    )
    Column(Modifier.padding(horizontal = K.gap4).padding(top = K.gap3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (status) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            LiveGlyph(tint, 14.dp)
            AnimatedContent(
                text to (count > 0),
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                },
                label = "lineLive",
            ) { (t, on) -> Text(t, style = K.type.subhead, color = if (on) K.realtime else K.textSecondary) }
        }
        if (!status) AnimatedVisibility(
            live.checked && count == 0 && next != null,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) {
            // The next three as a route card shows them: minutes within the hour, else the time, its day only when it
            // isn't today.
            shownNext?.let { n ->
                val now = rememberNow() / 1000
                FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(T("Next departures from ${n.from}: ", "היציאות הבאות מ-${n.from}: "), style = K.type.subhead)
                    DepartureTimes(n.times.map { Transit.Departure(0, it) }, now)
                }
            }
        }
        // At the rider's stop: when the line's next bus leaves it, and how busy it usually is then (Google's level). Not
        // "From", which the line's next departures already start with.
        var shownBusy by remember { mutableStateOf(busy) }
        if (busy != null) shownBusy = busy
        if (!status) AnimatedVisibility(
            busy != null,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) {
            shownBusy?.let { (stop, crowd) ->
                Column(Modifier.padding(top = 2.dp)) {
                    val at = crowd.second?.let { clockFormat().format(java.util.Date(it * 1000)) }
                    Text(if (at != null) T("How busy at $stop at $at", "עומס ב-$stop ב-$at") else T("How busy at $stop", "עומס ב-$stop"), style = K.type.subhead)
                    CrowdLevel(crowd.first)
                }
            }
        }
    }
}

// A line's next departures from its first stop: the stop, and up to three times, in epoch seconds.
internal class NextStart(val from: String, val times: List<Long>)

// The first of the next three trips, and their times in seconds from today's midnight.
private fun nextStart(net: Net, routes: Set<Int>): Pair<Int, List<Long>>? {
    val today = java.util.Calendar.getInstance(ISRAEL).get(java.util.Calendar.DAY_OF_WEEK) - 1
    val now = nowSec()
    val found = ArrayList<Pair<Long, Int>>()
    for (t in net.tripRoute.indices) {
        if (net.tripRoute[t] !in routes || net.tripStart[t + 1] <= net.tripStart[t]) continue
        val dep = net.stDep[net.tripStart[t]].toLong()
        for (k in -1..7) {
            if (!net.runsOn(t, ((today + k) % 7 + 7) % 7)) continue
            val at = k * 86_400L + dep
            if (at >= now) found += at to t
        }
    }
    val next = found.sortedBy { it.first }.distinctBy { it.first }.take(3)
    return if (next.isEmpty()) null else next.first().second to next.map { it.first }
}

// The Lines tab from the timetable's provider (Schedules): its lines, and a line's ways with their stops and times,
// asked as they open. Saved, recent and nearby lines are the week on the phone's, found among the provider's by the
// ministry's names. Without the provider, the week's lines and line pages show, and say why.

// The provider's list, kept while it's the one picked, so coming back from a line lands where the list was.
private class ScheduleListState(val source: Source) {
    var lines by mutableStateOf<List<Schedules.Line>?>(null)
    var failed by mutableStateOf(false)
    var types by mutableStateOf(FILTERS[0].second)
    val scroll = LazyListState()
    // Asked of the provider this run; until then lines can be the copy on the phone.
    var asked = false
    // What the list shows, and what it was worked out for, so it's whole the moment the tab opens.
    var sections by mutableStateOf(emptyList<Pair<String, List<Schedules.Line>>>())
    var sectionsFor: List<Any?>? = null
    var lead by mutableStateOf(emptyList<Pair<String, List<Schedules.Line>>>())
    var leadFor: List<Any?>? = null
    var leadReady by mutableStateOf(false)

    fun sectionsKey(lines: List<Schedules.Line>, q: String, types: IntArray) = listOf(lines, q, types, T.lang, StopNames.english)
    fun leadKey(lines: List<Schedules.Line>, here: Pair<Double, Double>?, q: String, types: IntArray, saved: List<String>) =
        listOf(lines, here, q, types, saved, T.lang, StopNames.english)
}

private var keptSchedule: ScheduleListState? = null

private fun scheduleListFor(source: Source): ScheduleListState =
    keptSchedule?.takeIf { it.source == source } ?: ScheduleListState(source).also { keptSchedule = it }

private fun unreachable(source: Source, what: String) = T(
    "Couldn't reach ${sourceName(source)}. $what",
    "${sourceName(source)} לא זמין. $what",
)

@Composable
private fun ScheduleLineList(model: KavModel, net: Net, week: LineListState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val source = Schedules.source
    val state = remember(source) { scheduleListFor(source) }
    // Asked again each time the tab opens while it couldn't be. The copy on the phone shows meanwhile.
    LaunchedEffect(state) {
        if (state.asked) return@LaunchedEffect
        state.failed = false
        try {
            state.lines = withContext(Dispatchers.IO) { Schedules.lines(ctx) }
            state.asked = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state.lines == null) state.failed = true
        }
    }
    val lines = state.lines
    when {
        lines != null -> ScheduleLines(model, net, state, lines)
        state.failed -> LineList(model, net, week, unreachable(source,
            T("These are the lines in the timetable on your phone.", "אלה הקווים שבלוח הזמנים בטלפון.")))
        else -> LineList(model, net, week)
    }
}

@Composable
private fun ScheduleLines(model: KavModel, net: Net, state: ScheduleListState, lines: List<Schedules.Line>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var q by model::lineQuery
    var types by state::types
    var sections by state::sections
    var lead by state::lead
    var leadReady by state::leadReady
    LaunchedEffect(lines, q, types) {
        val key = state.sectionsKey(lines, q, types)
        if (state.sectionsFor == key) return@LaunchedEffect
        sections = withContext(Dispatchers.Default) { foldSchedule(lines, q, types) }
        state.sectionsFor = key
    }
    LaunchedEffect(Unit) {
        if (model.here == null && uk.noammm.kav.hasLocationPermission(ctx)) {
            uk.noammm.kav.requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        }
    }
    LaunchedEffect(lines, model.here, q, types, model.savedLines) {
        val key = state.leadKey(lines, model.here, q, types, model.savedLines)
        if (state.leadFor == key) return@LaunchedEffect
        val here = model.here
        lead = if (q.isNotBlank()) {
            if (here == null) emptyList()
            else withContext(Dispatchers.Default) {
                val match = ScheduleMatch(net, lines)
                val wanted = foldSchedule(lines, q, types).flatMap { it.second }.mapTo(HashSet()) { it.id }
                val rows = nearestMatches(net, q, types, here).mapNotNull(match::of)
                    .filter { it.id in wanted }.distinctBy { it.id }.take(NEAREST_SHOWN)
                if (rows.isEmpty()) emptyList() else listOf(T("Nearest to you", "הקרובים אליכם") to rows)
            }
        } else if (types.isNotEmpty()) emptyList()
        else withContext(Dispatchers.Default) { scheduleLead(ctx, net, here, lines) }
        state.leadFor = key
        leadReady = true
    }
    // As the week's list: the full list waits for Recent and Nearby.
    val listReady = leadReady || q.isNotBlank() || types.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(T("Lines", "קווים"))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = state.scroll, contentPadding = PaddingValues(
                start = K.gap4, end = K.gap4, bottom = LocalBottomBarInset.current + K.gap4,
            )) {
                item(key = "search") { LineSearch(q, { q = it }, types) { types = it } }
                item(key = "count") {
                    LiveText(
                        T.count(sections.sumOf { it.second.size }, "1 line", "קו אחד", "lines", "קווים") +
                            T(" · from ${sourceName(Schedules.source)}", " · מ־${sourceName(Schedules.source)}"),
                        style = K.type.caption.tabular, modifier = Modifier.padding(horizontal = K.gap4),
                    )
                }
                lead.forEach { (label, rows) ->
                    item(key = "lead-$label") { Box(Modifier.animateItem()) { SectionLabel(label) } }
                    itemsIndexed(rows, key = { _, it -> "lead-$label-${it.id}" }) { i, l ->
                        Box(Modifier.animateItem()) { ScheduleLineRow(l, i == 0, i == rows.lastIndex) { model.openSchedule(l, -1) } }
                    }
                }
                if (listReady) sections.forEach { (operator, rows) ->
                    if (operator.isNotBlank()) item(key = "op-$operator") { Box(Modifier.animateItem()) { SectionLabel(operatorLabel(operator)) } }
                    itemsIndexed(rows, key = { _, it -> "line-${it.id}" }) { i, l ->
                        Box(Modifier.animateItem()) { ScheduleLineRow(l, i == 0, i == rows.lastIndex) { model.openSchedule(l, -1) } }
                    }
                }
            }
            ScrollEdge(state.scroll.canScrollBackward)
        }
    }
}

// The provider's lines as the list shows them: of the modes picked and with every word searched for (a number starting
// the line's), in their operators' sections, the biggest first, and by number in each.
private fun foldSchedule(lines: List<Schedules.Line>, q: String, types: IntArray): List<Pair<String, List<Schedules.Line>>> {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return lines.asSequence().distinctBy { it.id }
        .filter { l -> types.isEmpty() || l.type in types }
        .filter { l ->
            val h = (l.number + " " + l.names.joinToString(" ") + " " + l.from + " " + l.to + " " + l.operator + " " +
                shownName(l.from) + " " + shownName(l.to)).lowercase()
            need.all { if (it.all(Char::isDigit)) l.number.lowercase().startsWith(it) else h.contains(it) }
        }
        .groupBy { it.operator }.toList()
        .sortedByDescending { it.second.size }
        .map { (operator, ls) -> operator to ls.sortedWith(compareBy({ it.number.toIntOrNull() ?: Int.MAX_VALUE }, { it.number }, { it.from })) }
}

// The week's routes among a provider's lines: by the ministry's name with the number, else the one line of that
// operator with that number.
internal class ScheduleMatch(private val net: Net, lines: List<Schedules.Line>) {
    private val byName = HashMap<String, Schedules.Line>()
    private val byNumber = HashMap<String, MutableList<Schedules.Line>>()

    init {
        for (l in lines) {
            for (n in l.names) byName.putIfAbsent("${l.number}|$n", l)
            byNumber.getOrPut("${l.operator}|${l.type}|${l.number}") { ArrayList() }.add(l)
        }
    }

    fun of(route: Int): Schedules.Line? {
        val number = if (net.rType[route] == 2) "" else net.rShort[route]
        return byName["$number|${net.rLong[route]}"] ?: byNumber["${net.agencyOf(route)}|${net.rType[route]}|$number"]?.singleOrNull()
            ?: if (net.rType[route] == 2) TrainEnds.of(net).ends(route)?.let { byEnds[it] } else null
    }
    // A train line by its two stations: the provider names it so, not by the ministry's route names.
    private val byEnds = lines.filter { it.type == 2 }.associateBy { "${it.from}|${it.to}" }
}

// The week's train routes by their first and last stations' names ("from|to"), each route's ends the other way round
// too, found in one pass over the trips and kept for the timetable they're of.
internal class TrainEnds private constructor(net: Net) {
    private val routeOf = HashMap<String, Int>()
    private val endsOf = HashMap<Int, String>()

    init {
        for (t in net.tripRoute.indices) {
            val r = net.tripRoute[t]
            if (net.rType[r] != 2 || r in endsOf || net.tripStart[t + 1] - net.tripStart[t] < 2) continue
            val key = "${net.name[net.stStop[net.tripStart[t]]]}|${net.name[net.stStop[net.tripStart[t + 1] - 1]]}"
            endsOf[r] = key
            routeOf.putIfAbsent(key, r)
        }
    }

    fun ends(route: Int): String? = endsOf[route]
    fun route(from: String, to: String): Int = routeOf["$from|$to"] ?: -1

    companion object {
        private var kept: Pair<Net, TrainEnds>? = null
        @Synchronized fun of(net: Net): TrainEnds = kept?.takeIf { it.first === net }?.second ?: TrainEnds(net).also { kept = net to it }
    }
}

// A provider's line in the week, by the ministry's names of its routes: for its key (saved and recent lines), live
// locations as the week's line page has them, and its page when the provider can't be asked. -1 when the week hasn't it.
private fun weekRouteOf(net: Net, line: Schedules.Line): Int {
    val names = line.names.toHashSet()
    return (0 until net.nRoutes).firstOrNull { r -> net.rLong[r] in names && (line.type == 2 || net.rShort[r] == line.number) }
        ?: if (line.type == 2) TrainEnds.of(net).route(line.from, line.to) else -1
}

// Of the week's routes of a provider's line, the one riders get on at the stop with this code; null without one.
private fun weekRouteAt(net: Net, line: Schedules.Line, code: Int): Int? {
    if (code <= 0) return null
    val names = line.names.toHashSet()
    return (0 until net.nRoutes).firstOrNull { r ->
        net.rLong[r] in names && (line.type == 2 || net.rShort[r] == line.number) && net.representativeTrip(r).let { t ->
            t >= 0 && (net.tripStart[t] until net.tripStart[t + 1] - 1).any { net.code[net.stStop[it]] == code }
        }
    }
}

// Saved, recent and nearby lines, as the week's list has them, among the provider's.
private fun scheduleLead(
    ctx: android.content.Context, net: Net, here: Pair<Double, Double>?, lines: List<Schedules.Line>,
): List<Pair<String, List<Schedules.Line>>> {
    val match = ScheduleMatch(net, lines)
    val byKey = HashMap<String, Int>()
    for (r in 0 until net.nRoutes) byKey.putIfAbsent(lineKey(net, r), r)
    fun keyed(keys: List<String>) = keys.mapNotNull { k -> byKey[k]?.let(match::of) }.distinctBy { it.id }
    val out = ArrayList<Pair<String, List<Schedules.Line>>>()
    keyed(uk.noammm.kav.Prefs.savedLines(ctx)).takeIf { it.isNotEmpty() }?.let { out.add(T("Saved", "שמורים") to it) }
    keyed(uk.noammm.kav.Prefs.recentLines(ctx)).takeIf { it.isNotEmpty() }?.let { out.add(T("Recent", "אחרונים") to it) }
    if (here != null) {
        val near = net.nearestStops(here.first, here.second, k = 20, radius = 900.0)
        val rank = HashMap<Int, Int>(near.size * 2)
        near.forEachIndexed { i, (s, _) -> rank[s] = i }
        val best = HashMap<Int, Int>()
        if (rank.isNotEmpty()) for (t in net.tripRoute.indices) {
            val route = net.tripRoute[t]
            for (i in net.tripStart[t] until net.tripStart[t + 1]) {
                val o = rank[net.stStop[i]] ?: continue
                val held = best[route]
                if (held == null || o < held) best[route] = o
            }
        }
        val rows = best.entries.sortedBy { it.value }.mapNotNull { match.of(it.key) }.distinctBy { it.id }.take(10)
        if (rows.isNotEmpty()) out.add(T("Nearby", "בקרבת מקום") to rows)
    }
    return out
}

@Composable
private fun ScheduleLineRow(line: Schedules.Line, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .groupItem(first, last)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        LineIdentity(line.type, line.number, busOperator(line.type, -1) { Operators.ofName(line.operator) })
        val a = providerEnd(line.from); val b = providerEnd(line.to)
        Text(
            when {
                b.isEmpty() || a == b -> "\u2068$a\u2069"
                line.both -> "\u2068$a\u2069 ↔ \u2068$b\u2069"
                else -> T("\u2068$a\u2069 → \u2068$b\u2069", "\u2068$a\u2069 ← \u2068$b\u2069")
            },
            style = K.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        RowChevron()
    }
}

// A line opened from a stop's board or a trip, by its route in the week on the phone. With a provider's timetable its
// line there is found meanwhile, and the provider's ways take the week's place on the same page (LinePage).
@Composable
private fun TimetableLineDetail(model: KavModel, net: Net, route: Int, onBack: () -> Unit) {
    val source = Schedules.source
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val code = remember(route, source) { model.lineFocusStop.takeIf { it >= 0 }?.let { net.code[it] } ?: -1 }
    var line by remember(route, source) { mutableStateOf<Schedules.Line?>(null) }
    var failed by remember(route, source) { mutableStateOf(false) }
    LaunchedEffect(route, source) {
        if (source.timetable != null) try {
            line = withContext(Dispatchers.IO) { ScheduleMatch(net, Schedules.lines(ctx, source)).of(route) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { failed = true }
    }
    LinePage(model, net, route, line, code, failed, onBack)
}

// A provider's line opened from its list. Where the week has it, the week's version shows while its ways load.
@Composable
private fun ScheduleLineDetail(model: KavModel, net: Net, line: Schedules.Line, code: Int = model.scheduleStop, onBack: () -> Unit) {
    val route = remember(line) { weekRouteOf(net, line) }
    // The week's way of it through the stop it was opened at stands in, the way the provider's will open on.
    val at = remember(line) { code }
    val shown = remember(line, route) { weekRouteAt(net, line, at) ?: route }
    LinePage(model, net, shown, line, at, false, onBack, keyRoute = route)
}

// A stop of the line as its page lists it: its code and names, the week's stop it is (-1 where the week hasn't it), and
// where it is.
private class PageStop(val code: Int, val name: String, val city: String, val week: Int, val lat: Double, val lon: Double)

// The way's ends over the stops, and which way they are (order, for the slide when another is picked).
private data class PageEnds(val key: String, val order: Int, val to: String?, val from: String?)

// A line's page. The week on the phone shows it at once. With a provider's timetable the provider's ways take its place
// as they come, on this same page: the map keeps its camera and its line, the live note what it found, and the stops
// their rows, the same stops staying where they are and only what differs changing.
@Composable
private fun LinePage(
    model: KavModel, net: Net, route: Int, line: Schedules.Line?, code: Int, lineFailed: Boolean, onBack: () -> Unit,
    keyRoute: Int = route,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val source = Schedules.source
    LaunchedEffect(keyRoute) { if (keyRoute >= 0) uk.noammm.kav.Prefs.rememberLine(ctx, lineKey(net, keyRoute)) }
    // A train's line is every train between its two stations the same way, each of which the timetable keeps as a
    // route of its own.
    val routes = remember(route) {
        if (route < 0) emptySet() else if (net.rType[route] == 2 && net.rShort[route].isBlank()) net.trainLine(route) else setOf(route)
    }
    // Found on the page's first frame, one pass over the week's trips, so its stops are there as it opens.
    val weekStops = remember(route) {
        if (route < 0) null else {
            val t = if (routes.size == 1) net.representativeTrip(route) else {
                val ids = routes.toIntArray()
                var best = -1; var bestLen = -1
                for (i in net.tripRoute.indices) {
                    if (net.tripRoute[i] !in ids) continue
                    val len = net.tripStart[i + 1] - net.tripStart[i]
                    if (len > bestLen) { bestLen = len; best = i }
                }
                best
            }
            if (t < 0) emptyList() else (net.tripStart[t] until net.tripStart[t + 1]).map { net.stStop[it] }
        }
    }
    var ways by remember(line, source) { mutableStateOf<List<Schedules.Way>?>(null) }
    var waysFailed by remember(line, source) { mutableStateOf(false) }
    LaunchedEffect(line, source) {
        val l = line ?: return@LaunchedEffect
        ways = try {
            withContext(Dispatchers.IO) { Schedules.ways(l) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            waysFailed = true; null
        }
    }
    // The stop it was opened at, by its code, and as the week's stop.
    val opensAt = remember(route) { code }
    val weekFocus = remember(route) {
        model.lineFocusStop.takeIf { it >= 0 } ?: opensAt.takeIf { it > 0 }?.let { KavLive.stopOfCode(net, it) } ?: -1
    }
    // The way with the stop it was opened at, one riders get on there before one they only get off; else the first.
    // Picked as the ways first show, so they don't open on the first and slide over to it.
    fun opening(list: List<Schedules.Way>?, at: Int): Int? = list?.takeIf { at > 0 }?.let {
        val boards = list.indexOfFirst { w -> w.stops.indices.any { w.stops[it].code == at && it !in w.alight && it < w.stops.lastIndex } }
        (boards.takeIf { it >= 0 } ?: list.indexOfFirst { w -> w.stops.any { it.code == at } }).takeIf { it >= 0 }
    }
    // Without a stop to open at, the way going where the week's version shown goes, so the page doesn't turn round.
    fun alongWeek(list: List<Schedules.Way>?): Int? {
        val w = weekStops?.takeIf { it.isNotEmpty() } ?: return null
        val last = net.code[w.last()]; val start = net.code[w.first()]
        return list?.indexOfFirst { it.stops.lastOrNull()?.code == last }?.takeIf { it >= 0 }
            ?: list?.indexOfFirst { it.stops.firstOrNull()?.code == start }?.takeIf { it >= 0 }
    }
    var picked by remember(line) { mutableIntStateOf(-1) }
    val pick = if (picked >= 0) picked else opening(ways, opensAt) ?: alongWeek(ways) ?: 0
    var focus by remember(line) { mutableIntStateOf(opensAt) }
    LaunchedEffect(ways) { model.scheduleStop = -1 }
    val all = ways.orEmpty()
    val way = if (line != null) all.getOrNull(pick) else null
    // The way that first takes the week's place is the week's list carried on: its scroll, its open stop, no slide.
    val first = remember(route) { arrayOfNulls<String>(1) }
    if (way != null && first[0] == null) first[0] = way.id
    val listKey = if (way == null || way.id == first[0]) "week" else way.id
    val rows: List<PageStop>? = remember(way, weekStops) {
        way?.stops?.map { s ->
            PageStop(s.code, providerStopName(net, s).ifBlank { s.code.toString() }, providerStopCity(net, s), KavLive.stopOfCode(net, s.code), s.lat, s.lon)
        } ?: weekStops?.map { s -> PageStop(net.code[s], net.stopName(s), net.cityName(s), s, net.lat[s], net.lon[s]) }
    }
    val keys = remember(rows) { uniqueKeys(rows.orEmpty().map { "${it.code}" }) }
    // Its stops' boards asked ahead, nearest the one it was opened at first, for the Stop board a stop of it opens.
    LaunchedEffect(rows) {
        val r = rows ?: return@LaunchedEffect
        delay(1_500)
        val at = r.indexOfFirst { it.code == opensAt }.coerceAtLeast(0)
        BoardsAhead.stops(ctx, r.indices.sortedBy { kotlin.math.abs(it - at) }.map { r[it].code }, 12)
    }
    // Kept by the week's route, so the provider's line being found keeps the open stop and the scroll.
    val opened = remember(route) { mutableStateMapOf<String, String>() }
    val lists = remember(route) { HashMap<String, LazyListState>() }
    fun listFor(key: String) = lists.getOrPut(key) { LazyListState() }
    // The stop a list opens on: the one the page was opened at (where riders board before where they only get off), or
    // the week's stop it was opened for; -1 for none.
    fun openingAt(r: List<PageStop>): Int {
        val at = if (way != null) {
            way.stops.indices.firstOrNull { way.stops[it].code == focus && it !in way.alight } ?: way.stops.indexOfFirst { it.code == focus }
        } else r.indexOfFirst { weekFocus >= 0 && it.week == weekFocus }
        return if (at >= 0 && (way == null || focus > 0)) at else -1
    }
    // Open from the page's first frame, so its map is framed on the stop at once; once the effect below has had its say
    // for the list, what's open is only what's kept there (closing the stop leaves none open).
    val decided = remember(route) { mutableStateMapOf<String, Boolean>() }
    val firstOpen = remember(rows, listKey) { rows?.let { r -> openingAt(r).takeIf { it >= 0 }?.let { keys.getOrNull(it) } } }
    val open = if (decided[listKey] == true) opened[listKey] else opened[listKey] ?: firstOpen
    val scope = rememberCoroutineScope()
    LaunchedEffect(rows, listKey) {
        val r = rows ?: return@LaunchedEffect
        if (way == null && model.lineFocusStop >= 0) model.lineFocusStop = -1
        if (opened[listKey] != null) { decided[listKey] = true; return@LaunchedEffect }
        val at = openingAt(r)
        if (at >= 0) opened[listKey] = keys[at]
        decided[listKey] = true
        if (at >= 0) listFor(listKey).scrollToItem(at)
    }

    // Live locations by the week's route for what's shown, where the provider's way is one the week has; else by the line's
    // number and where the way ends.
    val liveRoute = remember(way, route) {
        if (way == null || line == null) route
        else line.names.getOrNull(line.routes.indexOf(way.id))?.let { name ->
            (0 until net.nRoutes).firstOrNull { net.rLong[it] == name && (line.type == 2 || net.rShort[it] == line.number) }
        } ?: -1
    }
    val liveStops = remember(rows) { rows.orEmpty().map { it.week } }
    val byRoute = liveRoute >= 0 && liveStops.isNotEmpty() && liveStops.all { it >= 0 }
    val live = rememberLive(net, if (byRoute) liveRoute else -1, if (byRoute) liveStops else emptyList(), line, way)
    // The line's other ways' live locations too, as often while the page shows, so a switch to one shows its own at once.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(all, way, lifecycle) {
        val l = line ?: return@LaunchedEffect
        val others = all.filter { it != way }
        if (others.isEmpty()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val tracks = HashMap<Int, List<Pair<Double, Double>>>()
            while (true) {
                for (w in others) {
                    val (r, stops) = liveAsk(net, l, w)
                    try {
                        LineLives.put(liveKey(r, l, w), withContext(Dispatchers.IO) { lineLiveNow(net, r, stops, l, w, tracks) })
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                    }
                }
                delay(20_000)
            }
        }
    }
    val weekNext = remember(route) {
        if (route < 0) null else nextStart(net, routes)?.let { (t, at) ->
            val from = net.stopName(net.stStop[net.tripStart[t]])
            val midnight = Schedules.base(Schedules.today())
            NextStart(from, at.map { midnight + it })
        }
    }
    var wayNext by remember(way) { mutableStateOf<NextStart?>(null) }
    var wayNextFound by remember(way) { mutableStateOf(false) }
    LaunchedEffect(way) {
        val w = way ?: return@LaunchedEffect
        val l = line ?: return@LaunchedEffect
        wayNext = withContext(Dispatchers.IO) { runCatching { scheduleNextStart(net, l, w) }.getOrNull() }
        wayNextFound = true
    }
    // The provider's way that took the week's place keeps the week's next departure where its own day doesn't have one.
    val next = if (way == null) weekNext
        else if (wayNextFound) wayNext ?: weekNext.takeIf { listKey == "week" } else weekNext.takeIf { listKey == "week" }

    // How busy the line's next bus from the rider's stop usually is (GoogleCrowd): the stop it was opened at, else the
    // way's stop nearest the rider, with the end of the line for its Google id: adjacent stops can give only a walk.
    val number = if (route >= 0) net.rShort[route] else line?.number.orEmpty()
    val here = model.here
    val mine = remember(rows, opensAt, here) {
        val r = rows ?: return@remember -1
        r.indexOfFirst { it.code == opensAt }.takeIf { it >= 0 } ?: here?.let { (la, lo) ->
            r.indices.filter { r[it].lat.isFinite() && r[it].lon.isFinite() }
                .minByOrNull { (r[it].lat - la) * (r[it].lat - la) + (r[it].lon - lo) * (r[it].lon - lo) }
        } ?: -1
    }
    val myStop = rows?.getOrNull(mine)
    val toward = rows?.let { r -> r.lastOrNull()?.takeIf { it.code != myStop?.code } ?: r.getOrNull(mine - 1) }
    if (uk.noammm.kav.data.GoogleCrowd.enabled && myStop != null && toward != null && myStop.lat.isFinite() && toward.lat.isFinite()) {
        LaunchedEffect(myStop.code, number) {
            val stop = uk.noammm.kav.data.GoogleCrowd.Stop("${myStop.code}", myStop.lat to myStop.lon, toward.lat to toward.lon)
            while (true) {
                uk.noammm.kav.data.GoogleCrowd.refreshStops(listOf(stop))
                delay(60_000)
            }
        }
    }
    val busy = myStop?.let { s -> uk.noammm.kav.data.GoogleCrowd.nextOf("${s.code}", number, System.currentTimeMillis() / 1000)
        ?.takeIf { uk.noammm.kav.data.GoogleCrowd.displayed(it.first, (if (route >= 0) net.rType[route] else line?.type) == 2) != null }
        ?.let { s.name to it } }

    val rt = if (route >= 0) net.rType[route] else line?.type ?: 3
    val operator = if (route >= 0) Operators.of(net, route) else line?.let { Operators.ofName(it.operator) }
    // A train line's track comes with a train of it under way, which the next look may not have.
    var road by remember(route, KavLive.source, KavLive.server) { mutableStateOf(emptyList<Pair<Double, Double>>()) }
    LaunchedEffect(live.shape) { if (live.shape.isNotEmpty()) road = live.shape }
    val placed = remember(rows) { rows.orEmpty().indices.filter { rows!![it].lat.isFinite() && rows[it].lon.isFinite() } }
    val calls = remember(rows, placed) { placed.map { rows!![it].lat to rows[it].lon } }
    val shape = way?.shape?.ifEmpty { null } ?: live.shape.ifEmpty { road }

    Column(Modifier.fillMaxSize().background(K.bg)) {
        // A train has no number: its two ends, as its row in the list names it.
        fun twoEnds(a: String, b: String) = T("\u2068$a\u2069 → \u2068$b\u2069", "\u2068$a\u2069 ← \u2068$b\u2069")
        val ends = when {
            number.isNotBlank() -> null
            route >= 0 -> weekStops?.takeIf { it.size >= 2 }?.let { twoEnds(shownEnd(net, it.first()), shownEnd(net, it.last())) }
            else -> line?.takeIf { it.from.isNotBlank() && it.to.isNotBlank() }?.let { twoEnds(providerEnd(it.from), providerEnd(it.to)) }
        }
        ScreenHeader(ends ?: (T("Line ", "קו ") + number), back = onBack)
        Row(
            Modifier.padding(horizontal = K.gap4).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            if (route >= 0) LineIdentity(net.rType[route], net.rShort[route], busOperator(net.rType[route], -1) { Operators.of(net, route) })
            else if (line != null) LineIdentity(line.type, line.number, busOperator(line.type, -1) { Operators.ofName(line.operator) })
            fun weekName(stop: Int): String = net.stopName(stop) + net.cityName(stop).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            fun wayName(s: Schedules.Stop): String = providerStopName(net, s) +
                providerStopCity(net, s).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val ends = when {
                way != null -> PageEnds(listKey, pick, wayName(way.stops.last()), wayName(way.stops.first()))
                weekStops?.isNotEmpty() == true -> PageEnds(listKey, pick, weekName(weekStops!!.last()), weekName(weekStops!!.first()))
                else -> PageEnds(listKey, pick, null, null)
            }
            // The way's ends move with its stops below when another way is picked, and ease to the provider's names.
            AnimatedContent(
                ends, Modifier.weight(1f),
                transitionSpec = {
                    if (initialState.key != targetState.key) sideways(targetState.order > initialState.order).using(SizeTransform(clip = false))
                    else (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(150))).using(SizeTransform(clip = false))
                },
                label = "wayEnds",
            ) { e ->
                Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                    when {
                        e.to != null && e.from != null -> {
                            Text(T("To \u2068${e.to}\u2069", "אל \u2068${e.to}\u2069"),
                                style = K.type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(T("From \u2068${e.from}\u2069", "מ־\u2068${e.from}\u2069"),
                                fontSize = 12.sp, lineHeight = 17.sp, color = K.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        route >= 0 -> Text(T("Route stops", "תחנות המסלול"), style = K.type.body, color = K.muted)
                        else -> Text(line?.operator.orEmpty(), style = K.type.body, color = K.muted)
                    }
                }
            }
            // Kept at the top of the Lines tab, by the week's line it is.
            if (keyRoute >= 0) {
                val key = lineKey(net, keyRoute)
                val saved = key in model.savedLines
                KavIconButton(
                    if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    if (saved) T("Saved, tap to remove", "שמור, הקישו להסרה") else T("Save this line", "שמירת הקו"),
                    { model.saveLines(ctx, if (saved) model.savedLines - key else listOf(key) + model.savedLines) },
                    floating = true, tint = if (saved) K.accentInk else K.textSecondary,
                )
            }
        }
        AnimatedVisibility(
            all.size > 1,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
        ) {
            Row(
                Modifier.padding(top = K.gap3).horizontalScroll(rememberScrollState()).padding(horizontal = K.gap4),
                horizontalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                val labels = remember(all) { wayLabels(net, all) }
                all.forEachIndexed { i, _ -> Chip(labels.getOrElse(i) { "" }, i == pick) { picked = i; focus = -1 } }
            }
        }
        // Why this is the week's line: its provider couldn't be asked.
        val unanswered = route >= 0 && (lineFailed || waysFailed)
        AnimatedVisibility(
            unanswered,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
        ) {
            NoteCard(
                unreachable(source, T("This is the line in the timetable on your phone.", "זה הקו שבלוח הזמנים בטלפון.")),
                Modifier.padding(horizontal = K.gap4).padding(top = K.gap3),
            )
        }
        val state = when {
            ways != null && way == null && line != null -> 1
            rows == null && route < 0 && waysFailed -> 2
            rows == null -> 3
            else -> 0
        }
        AnimatedContent(
            state, Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(150)) },
            label = "linePage",
        ) { s ->
            when (s) {
                1 -> NoteCard(T("This line has no trips in the coming days.", "אין לקו הזה נסיעות בימים הקרובים."), Modifier.padding(K.gap4))
                2 -> NoteCard(unreachable(source, T("Try again in a moment.", "נסו שוב בעוד רגע.")), Modifier.padding(K.gap4))
                3 -> LoadingBlock(
                    if (route >= 0) T("Loading stops", "טוען תחנות…")
                    else T("Loading the line from ${sourceName(source)}", "טוענים את הקו מ${sourceName(source)}"),
                )
                else -> Column(Modifier.fillMaxSize()) {
                    val shown = rows.orEmpty()
                    if (shown.isNotEmpty()) {
                        // The line's next departures and how busy it is lead, under its name; whether it's live goes under
                        // the map that shows it.
                        LineLiveNote(live, next, busy)
                        // A bus, train or light rail of the line reported, on the form's questions for its kind
                        // (ReportFor.train): its stops in order, the rider picking where they got on, and a train's number
                        // on the form where the rider knows it.
                        val mode = modeOf(rt)
                        val rail = mode == Mode.TRAM || mode == Mode.SUBWAY
                        if (mode == Mode.TRAIN || rail || mode == Mode.BUS) Text(
                            when {
                                mode == Mode.TRAIN -> T("Report a train on this line", "דיווח על רכבת בקו הזה")
                                rail -> T("Report a light rail on this line", "דיווח על רכבת קלה בקו הזה")
                                else -> T("Report a bus on this line", "דיווח על אוטובוס בקו הזה")
                            },
                            fontSize = 14.sp, color = K.accentInk,
                            modifier = Modifier.padding(horizontal = K.gap4).clickable(role = Role.Button) {
                                val first = shown.first(); val last = shown.last()
                                model.report = ReportFor(
                                    line = if (mode == Mode.TRAIN) "" else number,
                                    agency = if (mode == Mode.BUS) (if (route >= 0) net.agencyOf(route) else line?.operator.orEmpty()) else "",
                                    origin = first.name, destination = last.name,
                                    stops = shown.map { ReportStop(it.week, it.name, it.code.takeIf { c -> c > 0 }?.toString()) },
                                    at = System.currentTimeMillis() / 1000,
                                    near = here,
                                    towns = if (mode == Mode.BUS && first.city.isNotBlank() && last.city.isNotBlank()) first.city to last.city else null,
                                    direction = if (mode == Mode.BUS && route >= 0) ministryDirection(net.rLong[route]) else null,
                                    train = mode != Mode.BUS,
                                    trainType = if (rail) lightRailType(first.lat.takeIf { it.isFinite() }?.let { it to first.lon }) else "1",
                                )
                            }.padding(vertical = K.gap2))
                        val chosen = keys.indexOf(open)
                        if (calls.size >= 2) LineRouteMap(
                            calls, shape, rt, live.vehicles,
                            Modifier.padding(horizontal = K.gap4).padding(top = K.gap3).fillMaxWidth().height(210.dp).panel(K.rCard),
                            placed.indexOf(chosen),
                            { i -> opened[listKey] = keys[placed[i]]; scope.launch { listFor(listKey).glideTo(placed[i]) } },
                            operator, frame = listKey,
                        )
                        LineLiveNote(live, null, status = true)
                    }
                    ChangingText(
                        when {
                            way != null -> T.count(shown.size, "1 stop", "תחנה אחת", "stops", "תחנות") +
                                T(" · from ${sourceName(source)}", " · מ־${sourceName(source)}")
                            shown.isEmpty() -> T("No trips on this line in the loaded timetable", "אין נסיעות בקו הזה בלוח הזמנים הטעון")
                            else -> T.count(shown.size, "1 stop", "תחנה אחת", "stops", "תחנות") + T(" · full route", " · המסלול המלא")
                        },
                    ) { SectionHeader(it, Modifier.padding(horizontal = K.gap4)) }
                    // Another way's stops go as its road starts to change, and its own come once the road and the map have
                    // moved (WAY_SWITCH_MS), with the map's circles; the provider's taking the week's place don't move.
                    AnimatedContent(
                        PageList(listKey, pick, way, shown, keys), Modifier.fillMaxSize(),
                        transitionSpec = { fadeIn(tween(220, delayMillis = WAY_SWITCH_MS)) togetherWith fadeOut(tween(120)) },
                        contentKey = { it.key }, label = "way",
                    ) { l ->
                        PageStops(model, net, line, l.way, routes, l.rows, l.keys, listFor(l.key), opened[l.key]) { k ->
                            if (k == null) opened.remove(l.key) else opened[l.key] = k
                        }
                    }
                }
            }
        }
    }
}

// Equal while what it holds is, so the page recomposing (live times, the map) isn't another list for its transition.
private data class PageList(val key: String, val order: Int, val way: Schedules.Way?, val rows: List<PageStop>, val keys: List<String>)

// How long a switch to another way of the line takes to settle: TileMap's camera move (its moveMs), longer than the road's
// glide (400 ms). The new way's stops show after it.
private const val WAY_SWITCH_MS = 520

// The circles a line's map shows: frame, the way they're of; shown, its stops and the open one; fading, the last way's
// still going.
private class WayCircles(var frame: Any?, var shown: Pair<List<Pair<Double, Double>>, Int>) {
    var fading = false
    var switches = 0
}

// A stop picked, on the map or in the list, glides to the top of the list in one motion, whether it's on screen or not
// (the last few only as far as the list goes). Aimed each frame at where the row is, so it never runs past it and snaps
// back as the card opening and the one closing change the rows on the way. Kav's spring (critically damped, stiffness 500).
private suspend fun LazyListState.glideTo(index: Int) = scroll {
    val k = 500f
    var speed = 0f
    var then = withFrameNanos { it }
    while (true) {
        val now = withFrameNanos { it }
        val dt = ((now - then) / 1e9f).coerceIn(0f, 1 / 30f)
        then = now
        val left = towardTop(index)
        if (kotlin.math.abs(left) < 1f && kotlin.math.abs(speed) < 60f) break
        speed += (k * left - 2 * kotlin.math.sqrt(k) * speed) * dt
        val step = speed * dt
        // At the list's end the last stops can't come to the top.
        if (kotlin.math.abs(scrollBy(step) - step) > 0.5f) break
    }
}

// How far the list has to move to bring a row to its top: exactly once the row is in view, else as far as rows the
// size of those in view would take.
private fun LazyListState.towardTop(index: Int): Float {
    val info = layoutInfo
    val seen = info.visibleItemsInfo
    seen.firstOrNull { it.index == index }?.let { return (it.offset - info.viewportStartOffset).toFloat() }
    val first = seen.firstOrNull() ?: return 0f
    val last = seen.last()
    val each = (last.offset + last.size - first.offset).toFloat() / seen.size
    return (index - first.index) * each + (first.offset - info.viewportStartOffset)
}

// A way's times at each of its stops today (before dawn with last night's still to leave too), from today's midnight,
// as its stops' cards show them: worked out ahead for the way shown (PageStops), so a card opens with its times.
private object StopTimes {
    private val kept = HashMap<String, List<Int>>()
    private fun key(way: Schedules.Way, at: Int) = "${way.id}|$at|${Schedules.today()}"
    fun get(way: Schedules.Way, at: Int): List<Int>? = synchronized(kept) { kept[key(way, at)] }

    // Off the main thread.
    fun of(line: Schedules.Line, way: Schedules.Way, at: Int): List<Int> {
        get(way, at)?.let { return it }
        val now = nowSec()
        val today = Schedules.today()
        val midnight = Schedules.base(today)
        val night = if (now < 4 * 3600) Schedules.times(line, way, at, today.minusDays(1)) else emptyList()
        val times = (night + Schedules.times(line, way, at, today)).map { (it - midnight).toInt() }.filter { it >= 0 }.distinct().sorted()
        synchronized(kept) { kept[key(way, at)] = times }
        return times
    }

    fun ahead(line: Schedules.Line, way: Schedules.Way) {
        for (at in way.stops.indices) if (at < way.stops.lastIndex && at !in way.alight) runCatching { of(line, way, at) }
    }
}

// The stops of what the page shows, each keyed by its stop: a stop in both the week's list and the provider's keeps its
// row, and the provider's extra or missing ones come and go.
@Composable
private fun PageStops(
    model: KavModel, net: Net, line: Schedules.Line?, way: Schedules.Way?, routes: Set<Int>, rows: List<PageStop>,
    keys: List<String>, state: LazyListState, open: String?, onOpen: (String?) -> Unit,
) {
    // Every stop's times there, worked out ahead, so its card opens with them.
    LaunchedEffect(line, way) { if (line != null && way != null) withContext(Dispatchers.IO) { StopTimes.ahead(line, way) } }
    // Rows glide to their places only as the stops themselves change (the provider's list taking the week's place, with
    // stops of its own): beside a card opening or closing they move with it, as one, never lagging over it.
    val glide = remember(keys) { mutableStateOf(true) }
    LaunchedEffect(keys) { delay(600); glide.value = false }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(
        start = K.gap2, end = K.gap2, bottom = LocalBottomBarInset.current,
    )) {
        items(rows.size, key = { keys[it] }) { i ->
            val s = rows[i]
            val chosen = open == keys[i]
            val ends = i == 0 || i == rows.lastIndex
            val shade by androidx.compose.animation.animateColorAsState(
                if (chosen) K.plateStrong else K.plateStrong.copy(alpha = 0f), androidx.compose.animation.core.tween(220), label = "chosen",
            )
            val room by androidx.compose.animation.core.animateDpAsState(if (chosen) K.gap1 else 0.dp, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), label = "chosenRoom")
            Column(
                Modifier.animateItem(
                    fadeInSpec = tween(220, delayMillis = 60),
                    placementSpec = if (glide.value) spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = androidx.compose.ui.unit.IntOffset.VisibilityThreshold) else null,
                    fadeOutSpec = tween(150),
                ).padding(vertical = room).clip(RoundedCornerShape(K.rTile)).background(shade),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(K.rTile))
                        .clickable {
                            onOpen(if (chosen) null else keys[i])
                            if (!chosen) scope.launch { state.glideTo(i) }
                        }
                        .padding(horizontal = K.gap3, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                        if (chosen) Box(
                            Modifier.size(13.dp).clip(K.capsule).background(K.accent).padding(3.5.dp).clip(K.capsule).background(K.bg),
                        ) else Box(Modifier.size(7.dp).clip(K.capsule).background(if (ends) K.text else K.surface4))
                    }
                    Spacer(Modifier.width(K.gap3))
                    Column(Modifier.weight(1f)) {
                        ChangingText(s.name) {
                            Text(
                                it, fontSize = 16.sp, color = if (chosen || ends) K.text else K.muted,
                                fontWeight = if (chosen) FontWeight.Medium else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (s.city.isNotBlank()) ChangingText(s.city) { Text(it, style = K.type.subhead, maxLines = 1) }
                    }
                }
                // Opens downwards from under the stop, so what's in it stays where it lands.
                AnimatedVisibility(
                    chosen,
                    enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
                    exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) + fadeOut(tween(120)),
                ) {
                    // The week's times there fade to the provider's.
                    AnimatedContent(
                        way != null && line != null,
                        transitionSpec = {
                            (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(150)))
                                .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                        },
                        label = "stopTimes",
                    ) { provided ->
                        if (provided && way != null && line != null) {
                            ScheduleAtStop(line, way, i, last = i == rows.lastIndex, alight = i in way.alight, board = s.week.takeIf { it >= 0 }?.let { at ->
                                { model.openStation(at) }
                            })
                        } else if (s.week >= 0) {
                            LineAtStop(net, routes, s.week, last = i == rows.lastIndex) { model.openStation(s.week) }
                        }
                    }
                }
            }
        }
    }
}

// Each way's button: where it ends, most often its last stop's name and then named as that stop is. The first way to a
// place (each end's main one, Schedules.arrange) is just that; a later one to the same place also says where it starts,
// or, starting at the same place too, how many stops it calls at.
internal fun wayLabels(net: Net, ways: List<Schedules.Way>): List<String> {
    val ends = ways.map { w ->
        val last = w.stops.last()
        if (last.name.trim() == w.to.trim()) providerStopName(net, last) else shownName(w.to)
    }
    val starts = ways.map { providerStopName(net, it.stops.first()).substringBefore('/').trim() }
    return ways.indices.map { i ->
        val to = T("To \u2068${ends[i]}\u2069", "אל \u2068${ends[i]}\u2069")
        val same = ways.indices.filter { ends[it] == ends[i] }
        when {
            same.first() == i -> to
            same.count { starts[it] == starts[i] } == 1 -> to + T(" · from \u2068${starts[i]}\u2069", " · מ־\u2068${starts[i]}\u2069")
            else -> to + " · " + T.count(ways[i].stops.size, "1 stop", "תחנה אחת", "stops", "תחנות")
        }
    }
}

// A provider's stop and town as shown. In English, by the week's names for its code first: a provider can name a stop its
// own way, and the week's translations, found by name, then miss it. A stop the week has is in the week's town, as the
// week's line pages show it: none for a train station, which Bus Nearby puts in the town "תחנת רכבת".
internal fun providerStopName(net: Net, s: Schedules.Stop): String =
    weekStopOf(net, s)?.let { net.nameEn.getOrNull(it)?.ifEmpty { null } } ?: shownName(s.name)

internal fun providerStopCity(net: Net, s: Schedules.Stop): String =
    KavLive.stopOfCode(net, s.code).takeIf { it >= 0 }?.let { net.cityName(it) } ?: shownName(s.city)

private fun weekStopOf(net: Net, s: Schedules.Stop): Int? =
    if (StopNames.english) KavLive.stopOfCode(net, s.code).takeIf { it >= 0 } else null

// The way's next departure from its first stop riders get on at (a line can set out from a depot where none do), today
// or tomorrow, as the week's line page says it.
private fun scheduleNextStart(net: Net, line: Schedules.Line, way: Schedules.Way): NextStart? {
    val first = way.stops.indices.firstOrNull { it !in way.alight && it < way.stops.lastIndex } ?: return null
    val today = Schedules.today()
    val now = System.currentTimeMillis() / 1000
    val at = (0..1).flatMap { k -> Schedules.times(line, way, first, today.plusDays(k.toLong())).filter { it >= now } }
        .distinct().sorted().take(3).ifEmpty { return null }
    val from = providerStopName(net, way.stops[first])
    return NextStart(from, at)
}

// A provider's line at one of its way's stops: its next times there and today's all, as the week's line page has them.
// alight: riders only get off it there, so it has no times to give.
@Composable
private fun ScheduleAtStop(line: Schedules.Line, way: Schedules.Way, at: Int, last: Boolean, alight: Boolean, board: (() -> Unit)?) {
    val now = nowSec()
    var times by remember(way.id, at) { mutableStateOf(if (last || alight) emptyList() else StopTimes.get(way, at)) }
    var failed by remember(way.id, at) { mutableStateOf(false) }
    LaunchedEffect(way.id, at) {
        if (last || alight || times != null) return@LaunchedEffect
        times = try {
            withContext(Dispatchers.IO) { StopTimes.of(line, way, at) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true; emptyList()
        }
    }
    val train = line.type == 2
    var all by remember(way.id, at) { mutableStateOf(false) }
    val list = times.orEmpty()
    Column(
        Modifier.fillMaxWidth().padding(start = K.gap3 + 18.dp + K.gap3, end = K.gap3, bottom = K.gap3),
        verticalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        val next = list.filter { it >= now }.take(3)
        LiveText(
            when {
                last -> T("This line ends here.", "הקו מסתיים כאן.")
                alight -> T("Drop-off only. This line doesn't pick up riders here.", "הורדה בלבד. הקו לא אוסף כאן נוסעים.")
                // Held blank, never "Checking...": the times fade in as they come.
                times == null -> ""
                failed -> unreachable(Schedules.source, T("Its times here didn't load.", "הזמנים כאן לא נטענו."))
                next.isEmpty() && train -> T("No more trains on this line today.", "אין עוד רכבות בקו הזה היום.")
                next.isEmpty() -> T("No more buses on this line today.", "אין עוד יציאות בקו הזה היום.")
                else -> T("Next: ", "הבאים: ") + next.joinToString(" · ") { relative(it, now) ?: hhmm(it) }
            },
            style = K.type.body.tabular,
        )
        androidx.compose.animation.AnimatedVisibility(
            all,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top) +
                androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
            Text(list.joinToString("   ") { hhmm(it) }, style = K.type.subhead.tabular, lineHeight = 22.sp)
        }
        Row {
            // Its times come after the card opens: the schedule's button slides in before Stop board, not pushing it.
            androidx.compose.animation.AnimatedVisibility(
                list.isNotEmpty(),
                enter = androidx.compose.animation.expandHorizontally(spring(dampingRatio = 1f, stiffness = 500f)) +
                    androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
                exit = androidx.compose.animation.shrinkHorizontally(spring(dampingRatio = 1f, stiffness = 500f)) +
                    androidx.compose.animation.fadeOut(tween(120)),
            ) {
                KavButton(
                    if (all) T("Hide schedule", "הסתרת לוח הזמנים") else T("Today's schedule", "לוח הזמנים להיום"),
                    { all = !all }, style = ButtonStyle.Secondary, modifier = Modifier.padding(end = K.gap2),
                )
            }
            if (board != null) KavButton(T("Stop board", "לוח התחנה"), board, style = ButtonStyle.Secondary)
        }
    }
}

// A line opened from elsewhere (a stop's board or a trip): the provider's when the timetable is a
// provider's that lists it, else the week's. Opens the Lines tab.
internal fun openTimetableLine(model: KavModel, route: Int, stop: Int) {
    model.rememberReturn(uk.noammm.kav.Tab.Lines)
    model.scheduleLine = null
    model.lineFocusStop = stop; model.lineRoute = route; model.tab = uk.noammm.kav.Tab.Lines
}
