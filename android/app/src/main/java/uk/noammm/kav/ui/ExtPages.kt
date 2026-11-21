package uk.noammm.kav.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.Tab
import uk.noammm.kav.ext.Blocks
import uk.noammm.kav.ext.ExtApp
import uk.noammm.kav.ext.Hub
import uk.noammm.kav.ext.HomeBody
import uk.noammm.kav.ext.PageHost
import uk.noammm.kav.ext.PageSpecs
import uk.noammm.kav.ext.Registry
import uk.noammm.kav.ext.SafeMode
import uk.noammm.kav.ext.Screens

// An extension's screens (ext/Pages.kt): its pages over Kav's screens or in the tab the rider pinned, its Home body,
// and its settings page, which Kav draws. Its block trees (ext/Blocks.kt) are Kav's own components in Kav's own layout.
// What Kav keeps for itself on each: the header with Back, the "by <extension>" label, loading and unavailable states
// and their words, the way back to Kav's Home, and the rider's own trips, which Kav draws from its own data. Those are drawn in Kav's own colours even under a theme, so no extension's look can hide or fake them.

private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

// A page to show: one of an extension's pages with its arguments, or its settings page.
data class ExtEntry(val identity: String, val page: String, val args: Map<String, String> = emptyMap(), val settings: Boolean = false)

// The extension pages opened over Kav's screens (from Settings, from a Home body), five at most: a sixth takes the top
// one's place.
object ExtPageNav {
    const val DEEP = 5
    var overlay by mutableStateOf<List<ExtEntry>>(emptyList())
        private set

    fun open(e: ExtEntry) { overlay = if (overlay.size >= DEEP) overlay.dropLast(1) + e else overlay + e }
    fun back() { overlay = overlay.dropLast(1) }
    fun close() { overlay = emptyList() }
}

private fun by(name: String) = T("by ", "מאת ") + "\u2068$name\u2069"

private fun bySpoken(name: String) = T("By $name, a third-party extension, not made by Kav", "מאת $name, תוסף של צד שלישי שלא נוצר על ידי Kav")

// Kav's own colours for what it keeps for itself, whatever theme is on.
@Composable
private fun kavOwn(): Palette = palette(K.look)

// "by <extension>": small grey words on what an extension puts on one of Kav's own screens (a line, a row, a route),
// so a rider can tell whose it is. Its own pages don't carry it: their header names them.
@Composable
internal fun ByLabel(name: String, modifier: Modifier = Modifier) {
    Text(by(name), style = K.type.caption, color = K.textTertiary, maxLines = 1, modifier = modifier.clearAndSetSemantics { contentDescription = bySpoken(name) })
}

@Composable
private fun KavOwnButton(label: String, onClick: () -> Unit) {
    val p = kavOwn()
    Text(
        label, style = K.type.subhead, color = p.text, maxLines = 1,
        modifier = Modifier.heightIn(min = 36.dp).clip(K.capsule).background(p.fillStrong).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4, vertical = K.gap2),
    )
}

// A line Kav says about an extension's screen, in Kav's own colours: why it isn't there, the way back.
@Composable
private fun KavOwnStrip(text: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    val p = kavOwn()
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(p.surface).padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Text(text, style = K.type.subhead, color = p.textSecondary, modifier = Modifier.weight(1f))
        action?.let { (label, go) -> KavOwnButton(label, go) }
    }
}

internal fun iconOf(name: String?): ImageVector? = when (name) {
    "star" -> Icons.Rounded.Star
    "heart" -> Icons.Rounded.Favorite
    "bus" -> Icons.Rounded.DirectionsBus
    "train" -> Icons.Rounded.Train
    "walk" -> Icons.AutoMirrored.Rounded.DirectionsWalk
    "bike" -> Icons.Rounded.PedalBike
    "clock" -> Icons.Rounded.Schedule
    "map" -> Icons.Rounded.Map
    "pin" -> Icons.Rounded.Place
    "info" -> Icons.Rounded.Info
    "warning" -> Icons.Rounded.Warning
    "check" -> Icons.Rounded.Check
    "plus" -> Icons.Rounded.Add
    "edit" -> Icons.Rounded.Edit
    "delete" -> Icons.Rounded.Delete
    "search" -> Icons.Rounded.Search
    "list" -> Icons.AutoMirrored.Rounded.List
    "home" -> Icons.Rounded.Home
    "calendar" -> Icons.Rounded.CalendarMonth
    "settings" -> Icons.Rounded.Settings
    "back" -> Icons.AutoMirrored.Rounded.ArrowBack
    else -> null
}

private val offWords get() =
    T("This extension is off or isn't installed, so this page can't open.", "התוסף הזה כבוי או לא מותקן, ולכן אי אפשר לפתוח את הדף הזה.")

// Extensions whose code this Kav can't load, built for an older one (ext/Native.kt): those whose source is being asked
// for a newer release now (updating), and all of them (outdated). Set by ExtApp, from any thread.
object ExtUpdates {
    var updating by mutableStateOf<Set<String>>(emptySet())
        private set
    var outdated by mutableStateOf<Set<String>>(emptySet())
        private set

    fun set(updating: Set<String>, outdated: Set<String>) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { this.updating = updating; this.outdated = outdated }
    }
}

// Where the rider's own trips (a trips block) go when tapped: Kav's own planning and resume.
class ExtTripLinks(val onTrip: (RecentTrip) -> Unit, val onResume: () -> Unit)

private fun linksFor(model: KavModel) = ExtTripLinks(
    onTrip = { t ->
        ExtPageNav.close()
        model.pendingFrom = t.from
        model.pendingTo = t.to
        model.tab = Tab.Directions
    },
    // The trip's resume card is on Home.
    onResume = { ExtPageNav.close(); model.tab = Tab.Directions },
)

// An extension's words on a Hebrew screen, each in its own direction (a first-strong isolate, as T.ltr does for names),
// so "2 rides" or "°C" keeps its order and still sits where Kav's alignment puts it. Form values are left as they are.
private fun iso(s: String) = T.iso(s)

internal fun isolated(b: Blocks.Block): Blocks.Block {
    if (!T.rtl) return b
    fun row(r: Blocks.Row) = r.copy(title = iso(r.title), subtitle = r.subtitle?.let(::iso), value = r.value?.let(::iso))
    return when (b) {
        is Blocks.Text -> b.copy(text = iso(b.text))
        is Blocks.Row -> row(b)
        is Blocks.ListOf -> b.copy(items = b.items.map(::row))
        is Blocks.Card -> b.copy(title = iso(b.title), subtitle = b.subtitle?.let(::iso), badge = b.badge?.let(::iso))
        is Blocks.Notice -> b.copy(text = iso(b.text))
        is Blocks.Metric -> b.copy(label = iso(b.label), value = iso(b.value), unit = b.unit?.let(::iso))
        is Blocks.Button -> b.copy(label = iso(b.label))
        is Blocks.Chips -> b.copy(items = b.items.map { it.copy(label = iso(it.label)) })
        is Blocks.Image -> b.copy(alt = iso(b.alt))
        else -> b
    }
}

// A switch whose label is the extension's (up to 60 characters): it wraps instead of being cut to one line.
@Composable
private fun WrappingToggle(label: String, on: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        // One node with the switch's role, its state and its words, so TalkBack says "on" or "off" with the label.
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = K.type.body)
            subtitle?.let { Text(it, style = K.type.subhead) }
        }
        KavSwitch(on)
    }
}

@Composable
private fun BlockRow(b: Blocks.Row, act: (Blocks.Action) -> Unit) = ListRow(
    b.title, subtitle = b.subtitle, value = b.value, subtitleMaxLines = 2,
    leading = iconOf(b.icon)?.let { v -> { Icon(v, null, tint = K.textSecondary, modifier = Modifier.size(22.dp)) } },
    onClick = b.action?.let { a -> { act(a) } },
)

// A native extension's block tree (KavUi.blocks, ExtNative.kt), with what its actions do: open its settings, plan to a
// place, or a link.
@Composable
internal fun SlotBlocks(blocks: List<Blocks.Block>, model: KavModel, identity: String, name: String) {
    var link by remember { mutableStateOf<String?>(null) }
    val act: (Blocks.Action) -> Unit = { a ->
        when (a) {
            Blocks.Action.Settings -> ExtPageNav.open(ExtEntry(identity, "", settings = true))
            is Blocks.Action.Plan -> planTo(model, a)
            is Blocks.Action.Link -> link = a.url
        }
    }
    val links = linksFor(model)
    Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        for (b in blocks) BlockView(isolated(b), model, links, act)
    }
    link?.let { url -> OpenLink(url) { link = null } }
}

@Composable
private fun BlockView(b: Blocks.Block, model: KavModel, links: ExtTripLinks, act: (Blocks.Action) -> Unit) {
    val ctx = LocalContext.current
    when (b) {
        is Blocks.Text -> Text(
            b.text, modifier = Modifier.fillMaxWidth().padding(horizontal = K.gap4),
            style = when (b.style) { Blocks.Style.TITLE -> K.type.headline; Blocks.Style.BODY -> K.type.body; Blocks.Style.CAPTION -> K.type.caption },
        )
        is Blocks.Card -> KavCard(onClick = b.action?.let { a -> { act(a) } }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                iconOf(b.icon)?.let { Icon(it, null, tint = K.textSecondary, modifier = Modifier.size(24.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(b.title, style = K.type.headline)
                    b.subtitle?.let { Text(it, style = K.type.subhead) }
                }
                b.badge?.let { Text(it, style = K.type.caption, color = K.text, modifier = Modifier.clip(K.capsule).background(K.fill).padding(horizontal = K.gap2, vertical = 2.dp)) }
            }
        }
        // A warning in Kav's own problem colour; an extension has no way to show one as critical.
        is Blocks.Notice -> NoteCard(b.text, color = if (b.level == Blocks.Level.WARNING) K.problem else K.textSecondary)
        is Blocks.Metric -> KavCard {
            Text(b.label, style = K.type.caption)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(K.gap1)) {
                Text(b.value, style = K.type.largeTitle.tabular)
                b.unit?.let { Text(it, style = K.type.subhead, modifier = Modifier.padding(bottom = 4.dp)) }
            }
        }
        is Blocks.Button -> KavButton(
            b.label, { act(b.action) }, Modifier.padding(horizontal = K.gap2),
            style = if (b.look == Blocks.Look.PRIMARY) ButtonStyle.Primary else ButtonStyle.Secondary, icon = iconOf(b.icon), wide = true,
        )
        is Blocks.Chips -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            for (c in b.items) KavChip(c.label, false, { act(c.action) })
        }
        // The rider's own trips, from Kav's own data: the extension asked for the list and never sees it.
        is Blocks.Trips -> when (b.source) {
            Blocks.TripSource.RECENT -> {
                val trips = remember { Prefs.trips(ctx) }.take(b.limit)
                if (trips.isEmpty()) NoteCard(T("No recent trips yet.", "עוד אין נסיעות אחרונות."))
                else ListGroup { for (t in trips) row { RecentTripRow(t) { links.onTrip(t) } } }
            }
            Blocks.TripSource.JOURNEY -> {
                val j = model.activeJourney
                if (j == null) NoteCard(T("No trip under way.", "אין נסיעה בדרך."))
                else ListGroup { row { ListRow(T("Your trip to ", "הנסיעה שלכם אל ") + j.toLabel, subtitle = T("Under way", "בדרך"), onClick = links.onResume) } }
            }
        }
        is Blocks.Row -> BlockRow(b, act)
        is Blocks.ListOf -> ListGroup { for (r in b.items) row { BlockRow(r, act) } }
        // Kav's own data and pictures (ExtHostBlocks.kt).
        is Blocks.Image -> ImageBlock(b)
        is Blocks.Stop -> StopBlock(model, b.code)
        is Blocks.Departures -> DeparturesBlock(model, b.code, b.limit)
        is Blocks.Line -> LineBlock(model, b.number)
    }
}

@Composable
private fun rememberHost(): suspend () -> PageHost {
    val app = LocalContext.current.applicationContext
    return remember { { withContext(Dispatchers.IO) { ExtShown.host(app) } } }
}

// One of an extension's pages, full screen: its own view (ext/Native.kt) under Kav's header, once its code has started.
@Composable
private fun ExtPageScreen(model: KavModel, e: ExtEntry, onBack: (() -> Unit)?) {
    val app = LocalContext.current.applicationContext
    val title = remember(e) { runCatching { ExtShown.host(app).decl(e.identity)?.page(e.page)?.title }.getOrNull() }
        ?.let { T(it.first, it.second.ifEmpty { it.first }) }.orEmpty()
    if (uk.noammm.kav.ext.Native.screen(e.identity, e.page) != null) {
        NativeScreen(model, e.identity, title, e.page, e.args, onBack)
        return
    }
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(title, back = onBack, large = onBack == null)
        // Its code is still starting (ExtNative.ready rises when it has), or it's off or gone.
        if (ExtNative.ready == 0) Box(Modifier.fillMaxWidth().padding(K.gap6), contentAlignment = Alignment.Center) { Spinner(28.dp) }
        // Its source being asked for a release built for this Kav; none newer there; or truly off or gone.
        else if (e.identity in ExtUpdates.updating) Row(Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            Spinner(18.dp)
            Text(T("Updating…", "מתעדכן…"), style = K.type.body)
        }
        else if (e.identity in ExtUpdates.outdated) KavOwnStrip(T("This extension needs an update from its maker.", "התוסף הזה צריך עדכון מהיוצר שלו."),
            Modifier.padding(horizontal = K.gap4, vertical = K.gap2))
        else KavOwnStrip(offWords, Modifier.padding(horizontal = K.gap4, vertical = K.gap2))
    }
}

// An extension's settings: ONE grouped card, as every part of Settings has, a row each with its label on the left and its
// control on the right. first: rows the extension's page puts before its own settings (what it adds, its screens). Kav
// draws each setting from what the extension declares with its own controls, and keeps the values with its data; its own
// settings view (Kav.settingsScreen) comes under the card. header: the section's title, shown when there is anything.
@Composable
internal fun ExtSettingsForm(identity: String, header: String? = null, first: ListGroupScope.() -> Unit = {}) {
    val host = rememberHost()
    val scope = rememberCoroutineScope()
    var decl by remember(identity) { mutableStateOf<PageSpecs.Decl?>(null) }
    var values by remember(identity) { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(identity) {
        val h = host()
        decl = io { h.decl(identity) }.getOrNull()
        values = io { h.settings(identity) }.getOrNull()
    }
    val before = ListGroupScope().apply(first).rows
    val settings = decl?.settings.orEmpty()
    val v = values ?: org.json.JSONObject()
    // A native extension's own settings view, under the card (Kav.settingsScreen).
    val own = if (ExtNative.ready >= 0) uk.noammm.kav.ext.Native.settingsScreen(identity) else null
    if (before.isEmpty() && settings.isEmpty() && own == null) return
    fun save(key: String, value: Any) = scope.launch {
        val h = host()
        if (io { h.setSetting(identity, key, value) }.getOrDefault(false)) {
            values = io { h.settings(identity) }.getOrNull()
            // Native code that asked is told (Kav.onSettings).
            values?.let { uk.noammm.kav.ext.Native.settingsChanged(identity, it.toString()) }
            // Its Home body and open pages are asked again with the new value.
            ExtUi.changed()
        }
    }
    fun label(p: Pair<String, String>) = T(p.first, p.second.ifEmpty { p.first })
    // A value said at a row's end, kept to a few words.
    fun short(t: String) = if (t.length > 24) t.take(23).trimEnd() + "…" else t
    // The one of Kav's pickers open now.
    var picking by remember(identity) { mutableStateOf<PageSpecs.Setting?>(null) }
    if (header != null) SectionHeader(header)
    val card = before.isNotEmpty() || settings.isNotEmpty()
    if (card) ListGroup {
        for (r in before) row(r)
        for (s in settings) row {
            when (s.type) {
                PageSpecs.Type.TOGGLE -> SwitchRow(label(s.label), v.optBoolean(s.key, s.default as? Boolean ?: false)) { save(s.key, it) }
                PageSpecs.Type.NUMBER -> ListRow(label(s.label), chevron = false, trailing = {
                    ExtStepper(v.optDouble(s.key, s.default as Double), s.min, s.max, s.step, label(s.unit).takeIf { s.unit.first.isNotEmpty() }.orEmpty()) { save(s.key, it) }
                })
                PageSpecs.Type.CHOICE -> ListRow(label(s.label), value = s.options.firstOrNull { it.first == v.optString(s.key) }?.let { label(it.second) },
                    onClick = { picking = s })
                PageSpecs.Type.TEXT -> ListRow(label(s.label), value = v.optString(s.key).takeIf { it.isNotEmpty() }?.let { short(T.iso(it)) } ?: T("Not set", "לא הוגדר"),
                    onClick = { picking = s })
                PageSpecs.Type.COLOR -> ListRow(label(s.label), onClick = { picking = s }, trailing = {
                    colorOf(v.optString(s.key))?.let { c -> Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape).background(c)) }
                })
                // A place, a stop, a time or a date: what's chosen at the row's end, Kav's picker a tap away.
                else -> {
                    val raw = v.opt(s.key)
                    val chosen: String? = when (s.type) {
                        PageSpecs.Type.PLACE, PageSpecs.Type.STOP -> (raw as? org.json.JSONObject)?.let { o -> short(T.iso(o.optString("name").ifEmpty { T("Chosen", "נבחר") })) }
                        PageSpecs.Type.TIME -> (raw as? String)?.takeIf { it.isNotEmpty() }?.let { t -> T.ltr(hhmm(t.substringBefore(':').toInt() * 3600 + t.substringAfter(':').toInt() * 60)) }
                        else -> (raw as? String)?.takeIf { it.isNotEmpty() }?.let(::dateWords)
                    }
                    ListRow(label(s.label), value = chosen ?: T("Not chosen", "לא נבחר"), onClick = {
                        if (s.type == PageSpecs.Type.PLACE || s.type == PageSpecs.Type.STOP)
                            ExtPicks.ask(stop = s.type == PageSpecs.Type.STOP, title = label(s.label)) { picked -> if (picked != null) save(s.key, picked) }
                        else picking = s
                    })
                }
            }
        }
    }
    // Its own view, as its code makes it, each time the page shows.
    if (own != null) androidx.compose.ui.viewinterop.AndroidView(
        factory = { c -> uk.noammm.kav.ext.Native.view(identity, "settings") { own.create(c, "{}") } ?: android.widget.FrameLayout(c) },
        // Under the Settings card at the gap between cards of one section; straight under the title when there's no card.
        modifier = Modifier.fillMaxWidth().padding(top = if (card) K.gap3 else 0.dp),
    )
    picking?.let { s ->
        val done = { picking = null }
        when (s.type) {
            PageSpecs.Type.CHOICE -> ExtChoiceSheet(label(s.label), s.options.map { label(it.second) }, s.options.indexOfFirst { it.first == v.optString(s.key) },
                { save(s.key, s.options[it].first) }, done)
            PageSpecs.Type.TEXT -> ExtTextSheet(label(s.label), v.optString(s.key), s.maxLength, { save(s.key, it) }, done)
            PageSpecs.Type.TIME -> ExtTimeSheet(label(s.label), v.optString(s.key), { save(s.key, it) }, done)
            PageSpecs.Type.DATE -> ExtDateSheet(label(s.label), v.optString(s.key), { save(s.key, it) }, done)
            PageSpecs.Type.COLOR -> ExtColorSheet(label(s.label), v.optString(s.key), { save(s.key, it) }, done)
            else -> done()
        }
    }
}

@Composable
private fun ExtSettingsScreen(identity: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(T("Settings", "הגדרות"), back = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = K.gap4, vertical = K.gap3)) { ExtSettingsForm(identity) }
    }
}

@Composable
private fun ExtEntryView(model: KavModel, e: ExtEntry, onBack: (() -> Unit)?) {
    key(e) {
        if (e.settings) ExtSettingsScreen(e.identity) { onBack?.invoke() } else ExtPageScreen(model, e, onBack)
    }
}

// The pages opened over Kav's screens. Opening one closes Settings, so it's never drawn under a protected surface.
@Composable
internal fun ExtPageOverlay(model: KavModel) {
    // An extension turned off, on or uninstalled in Settings: its tab and theme step out or come back at once.
    val app = LocalContext.current.applicationContext
    val tick = ExtUi.tick
    LaunchedEffect(tick) { if (tick > 0 && ExtShown.anyChosen) withContext(Dispatchers.IO) { runCatching { ExtShown.refresh(app) } } }
    val stack = ExtPageNav.overlay
    LaunchedEffect(stack.isNotEmpty()) { if (stack.isNotEmpty()) model.settingsOpen = false }
    var last by remember { mutableStateOf<ExtEntry?>(null) }
    stack.lastOrNull()?.let { last = it }
    androidx.compose.animation.AnimatedVisibility(stack.isNotEmpty(), enter = modalIn(), exit = modalOut()) {
        BackHandler(stack.isNotEmpty()) { ExtPageNav.back() }
        last?.let { e -> ExtEntryView(model, e) { ExtPageNav.back() } }
    }
}

// The tab the rider pinned: its page.
@Composable
internal fun ExtTabScreen(model: KavModel) {
    val t = ExtShown.tab
    if (t == null) {
        LaunchedEffect(Unit) { if (model.tab == Tab.Ext) model.tab = Tab.Directions }
        return
    }
    ExtEntryView(model, ExtEntry(t.identity, t.page), null)
}

// -- The Home body ---------------------------------------------------------------------------------------------------

// The extension's Home body while the rider has chosen one (ExtShown.home): what Home shows (HomeBody.parts). Null, and nothing at all asked, when no Home body is chosen.
class ExtHome internal constructor(val identity: String, val name: String) {
    var state by mutableStateOf<HomeBody.State>(HomeBody.State.Waiting)
    var kavNow by mutableStateOf(false)
    var reload by mutableIntStateOf(0)
    // The rider started using Kav's Home while the extension's was late: it stays Kav's until Home is opened again.
    var touched = false

    fun parts(journey: Boolean) = HomeBody.parts(chosen = true, safeMode = SafeMode.active, state = state, journey = journey, kavNow = kavNow)

    // Whether the extension's body is what Home shows now (else Kav's own, with its own resume card).
    fun covers(journey: Boolean) = HomeBody.Part.KAV !in parts(journey)
}

@Composable
internal fun rememberExtHome(model: KavModel): ExtHome? {
    val chosen = ExtShown.home ?: return null
    val app = LocalContext.current.applicationContext
    val h = remember(chosen.identity) { ExtHome(chosen.identity, chosen.name) }
    val lang = T.lang
    // Asked again when it changed in Settings (turned off or on, updated).
    val tick = ExtUi.tick
    val ready = ExtNative.ready
    LaunchedEffect(h, h.reload, lang, tick, ready) {
        // Its Home is its code's own view (ext/Native.kt): ready once its code has started. One that has no code
        // running (off, gone) isn't available.
        if (!nativeHome(h, app)) h.state = HomeBody.State.Unavailable("off")
    }
    return h
}

// Watches for the rider using Kav's Home, so a late extension body doesn't replace it under their finger.
internal fun Modifier.watchTouch(h: ExtHome?): Modifier = if (h == null) this else pointerInput(h) {
    awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); h.touched = true }
}

// Home's rows when the extension's body shows: the trip under way, the label with the way back, then its blocks.
internal fun LazyListScope.extHomeItems(model: KavModel, h: ExtHome, resume: (@Composable () -> Unit)?) {
    for (part in h.parts(resume != null)) when (part) {
        HomeBody.Part.RESUME -> resume?.let { r -> item(key = "ext-resume") { Box(Modifier.padding(top = K.gap4)) { r() } } }
        HomeBody.Part.LABEL -> item(key = "ext-label") {
            Row(Modifier.fillMaxWidth().padding(top = K.gap4), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                ByLabel(h.name)
                KavOwnButton(T("Kav's Home", "מסך הבית של Kav")) { h.kavNow = true }
            }
        }
        HomeBody.Part.WAITING -> item(key = "ext-waiting") { Spacer(Modifier.height(K.gap8)) }
        HomeBody.Part.EXTENSION -> if (h.state is HomeBody.State.Ready) item(key = "ext-native") { NativeHome(model, h.identity) }
        else -> {}
    }
}

// Home's first row when Kav's own Home shows although the rider chose an extension's: why, and the way back.
internal fun LazyListScope.extHomeStrip(h: ExtHome) {
    val parts = h.parts(false)
    when {
        HomeBody.Part.STRIP_KAV_CHOSEN in parts -> item(key = "ext-strip") {
            KavOwnStrip(T("You're on Kav's Home.", "זה מסך הבית של Kav."), Modifier.padding(top = K.gap4),
                T("\u2068${h.name}\u2069's Home", "מסך הבית של \u2068${h.name}\u2069") to { h.kavNow = false; h.touched = false; h.reload++ })
        }
        HomeBody.Part.STRIP_FAILED in parts -> item(key = "ext-strip") {
            KavOwnStrip(T("\u2068${h.name}\u2069's Home didn't load, so this is Kav's Home.", "מסך הבית של \u2068${h.name}\u2069 לא נטען, ולכן מוצג מסך הבית של Kav."), Modifier.padding(top = K.gap4),
                T("Try again", "נסו שוב") to { h.state = HomeBody.State.Waiting; h.reload++ })
        }
        HomeBody.Part.STRIP_UNAVAILABLE in parts -> item(key = "ext-strip") {
            val app = LocalContext.current.applicationContext
            val scope = rememberCoroutineScope()
            KavOwnStrip(
                T("\u2068${h.name}\u2069's Home isn't available: the extension is off or isn't installed, so this is Kav's Home.", "מסך הבית של \u2068${h.name}\u2069 לא זמין: התוסף כבוי או לא מותקן, ולכן מוצג מסך הבית של Kav."),
                Modifier.padding(top = K.gap4),
                T("Use Kav's Home", "שימוש במסך הבית של Kav") to { scope.launch { io { Screens.chooseHome(ExtApp.dir(app), null) }; ExtShown.publish() } },
            )
        }
    }
}

// -- On the extension's page in Settings -----------------------------------------------------------------------------

// Its screens and look, each the rider's own choice, as rows of its page's Settings card: open a page, show one as a tab,
// use it as Home, use its look. Settings closes when a page opens, as pages never show under it.
@Composable
internal fun extScreenRows(hub: Hub, i: Registry.Installed): ListGroupScope.() -> Unit {
    ExtUi.tick
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val decl = remember(i.sha256) { runCatching { PageSpecs.of(i.manifest) }.getOrNull() } ?: return {}
    val theme = remember(i.sha256) { Screens.themeOf(i) }
    val on = hub.active(i) && !SafeMode.active
    fun choose(block: () -> Unit) = scope.launch { io { block() }; ExtShown.publish(); ExtUi.changed() }
    val dir = ExtApp.dir(app)
    fun words(p: Pair<String, String>) = T(p.first, p.second.ifEmpty { p.first })
    val tab = Screens.tab
    val home = Screens.home
    val look = Screens.theme
    return {
        for (p in decl.tabs) row {
            SwitchRow(T("Show ", "הצגת ") + "\u2068${words(p.title)}\u2069" + T(" as a tab", " כלשונית"), tab?.let { it.identity == i.identity && it.page == p.id } == true) { v ->
                choose { Screens.chooseTab(dir, if (v) Screens.tabOf(i, p.id) else null) }
            }
        }
        if (decl.home) row {
            SwitchRow(T("Use as my Home screen", "שימוש כמסך הבית שלי"), home?.identity == i.identity) { v -> choose { Screens.chooseHome(dir, if (v) Screens.homeOf(i) else null) } }
        }
        if (theme != null) row {
            SwitchRow(T("Use this look", "שימוש במראה הזה"), look?.identity == i.identity) { v -> choose { Screens.chooseTheme(dir, if (v) theme else null) } }
        }
        // A page that isn't its tab or Home opens from here.
        for (p in decl.pages.filter { it.id != PageSpecs.HOME_PAGE && it.entry && !it.tab }) row {
            ListRow(T("Open ", "פתיחת ") + "\u2068${words(p.title)}\u2069", onClick = if (on) ({ ExtPageNav.open(ExtEntry(i.identity, p.id)) }) else null)
        }
    }
}

