package uk.noammm.kav.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.BuildConfig
import uk.noammm.kav.KavModel
import uk.noammm.kav.PendingBackup
import uk.noammm.kav.Prefs
import uk.noammm.kav.Seen
import uk.noammm.kav.data.Backup
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.PlanSettings
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.Sources
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.TripShare
import uk.noammm.kav.data.Updates
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.QrCodeScanner
import uk.noammm.kav.data.RideAlerts
import uk.noammm.kav.data.RiderProxy
import uk.noammm.kav.data.GoogleCrowd
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.ui.input.pointer.pointerInput

@Composable
fun SettingsScreen(model: KavModel, active: Boolean = true, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var choosing by remember { mutableStateOf(false) }

    // Each part is a row saying what it's set to, and opens on its own page.
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(active) { if (!active) { page = null; choosing = false } }
    LaunchedEffect(SettingsNav.wanted) { SettingsNav.wanted?.let { page = it; SettingsNav.wanted = null } }
    androidx.activity.compose.BackHandler(page != null) { page = null }
    // The header's Back is the phone's: a page that has gone a step in (Extensions, to a source or an extension) steps out first.
    val backs = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val parts = ArrayList<SettingsPart>()
    fun part(id: String, title: String, summary: String, summaryColor: Color? = null, sheet: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
        parts += SettingsPart(id, title, summary, summaryColor, sheet, content)
    }
    val waiting = model.update != null && Shown.updatePrompts

    // What a plan may show, and what a trip does by itself. A trip's voice is on the navigation screen (Navigate.kt), its
    // link's choices in the Share sheet (ShareSheet.kt).
    val offered = filtersOf(KavPlan.source)
    val ways = offered.count { it in model.filters }
    part(
        "trips",
        T("Trips", "נסיעות"),
        if (ways == offered.size) T("Every way of getting there", "כל הדרכים להגיע")
        else T("$ways of ${offered.size} ways of getting there", "$ways מתוך ${offered.size} דרכים להגיע"),
    ) {
        PlanChoices(model)
    }

    // Paying with Rav-Pass, on its own page (Noam, 19 Nov 2026).
    part(
        "paying",
        T("Paying", "תשלום"),
        when {
            PayMode.lost.isNotEmpty() -> T("Action required", "נדרשת פעולה")
            Shown.payments -> uk.noammm.kav.ext.Actions.firstName("pay")?.let { T("With \u2068$it\u2069", "עם \u2068$it\u2069") } ?: T("With ${PayApp.current.label()}", "עם ${PayApp.current.he}")
            else -> T("Not set up", "לא מוגדר")
        },
        if (PayMode.lost.isNotEmpty()) K.problem else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            PayingSettings()
            FareProfileGroup(header = Shown.payments)
        }
    }

    part(
        "look",
        T("Look", "מראה"),
        listOfNotNull(
            if (K.followsSystem) T("System", "מערכת")
            else when (K.look) { Look.OLED -> "OLED"; Look.LIGHT -> T("Light", "בהיר"); Look.DARK -> T("Dark", "כהה") },
            if (!liquidGlassReady) null else if (K.liquid) T("transparent", "שקוף") else T("solid", "אחיד"),
        ).joinToString(" · "),
    ) {
        // As setup's look step: the preview, the look and its glass, the color; then the line badges. A theme, once one is
        // installed, comes first: it can set the rest.
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            ThemeChoice()
            AccentPreview()
            LookChoices { Sub(it) }
            Sub(T("Color", "צבע"))
            AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
            LineBadgeChoice()
        }
    }

    // A few choices, so a sheet as tall as they are rather than a page left mostly empty.
    part("language", T("Language and time", "שפה ושעה"), languageSummary(), sheet = true) {
        LanguageAndTime(ctx)
    }

    part("map", T("Map", "מפה"), mapSummary()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { MapChoices() }
    }

    // What search sees, who Kav asks for live times and trips, and the proxy everything can go through. Its id was once
    // two pages, privacy and live (Providers); extensions opening live come here (ExtActions.kt).
    part(
        "privacy",
        T("Privacy", "פרטיות"),
        listOfNotNull(
            searchName().let { who ->
                when {
                    !Prefs.privateSearch(ctx) -> T("$who sees where you are", "$who רואה איפה אתם")
                    model.seen == Seen.CITY -> T("$who sees your town", "$who רואה את היישוב שלכם")
                    model.seen == Seen.PLACE -> T("$who sees a place you picked", "$who רואה מקום שבחרתם")
                    else -> T("$who sees no location", "$who לא רואה מיקום")
                }
            },
            if (providersAreKavs(ctx)) null else T("your own providers", "ספקים משלכם"),
            if (RiderProxy.setting.on) T("through your proxy", "דרך הפרוקסי שלכם") else null,
        ).joinToString(" · "),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            PrivacyRows(model) { choosing = true }
            // Still the part settings.page.live, which was the Providers page, for extensions made for it.
            val providers = T("Live times and trips", "זמן אמת ומסלולים")
            KavPart("settings.page.live", "live" to providers, { org.json.JSONObject().put("page", "live").put("title", providers) }) {
                Column { Providers(providers) }
            }
            ProxyChoice()
        }
    }

    // Third-party extensions, their sources, and safe mode (ExtensionsScreen.kt).
    part("extensions", T("Extensions", "תוספים"), extensionsSummary(ctx)) { ExtensionsPage() }

    part(
        "kav",
        "Kav",
        T.ltr("Kav ${Updates.installedVersion(ctx)}") +
            (model.update?.let { T(" · ${it.version} is available", " · גרסה ${it.version} זמינה") }
                ?: T(" · no ads, analytics or account", " · בלי פרסומות, אנליטיקה או חשבון")),
    ) {
        Spacer(Modifier.height(K.gap1))
        if (BuildConfig.UPDATER) UpdateSection(model)
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            if (BuildConfig.UPDATER) AutomaticUpdates()
            BackupSection(model)
            HelpSection()
        }
        // With the page's other sections: its header on their line, its list on a card as theirs are.
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            Sub(T("Not in Kav", "מה שאין ב-Kav"))
            Column(Modifier.fillMaxWidth().panel(K.rCard).padding(vertical = K.gap2)) {
                Absent(T("No account", "אין חשבון"))
                Absent(T("No ads", "אין פרסומות"))
                Absent(T("No analytics or attribution", "אין אנליטיקה או ייחוס"))
                Absent(T("No support chat", "אין צ'אט תמיכה"))
                Absent(T("No upsell", "אין מכירה נוספת"))
            }
            Credits()
        }
    }

    // A part shown as a sheet opens over the list, which stays.
    val sheet = parts.firstOrNull { it.id == page && it.sheet }
    Box(Modifier.fillMaxSize()) {
    androidx.compose.animation.AnimatedContent(
        page.takeIf { sheet == null }, Modifier.fillMaxSize().background(K.bg),
        transitionSpec = {
            if (SettingsNav.backward) { SettingsNav.backward = false; backward() } else if (targetState != null) forward() else backward()
        }, label = "settingsPage",
    ) { shown ->
        VeilMaps()
        val open = parts.firstOrNull { it.id == shown }
        Column(Modifier.fillMaxSize().background(K.bg).verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomBarInset.current + K.gap8)) {
            if (open == null) {
                ScreenHeader(T("Settings", "הגדרות"), "", back = onClose)
                // A new version waiting comes before everything else.
                AnimatedVisibility(
                    waiting,
                    enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top),
                    exit = fadeOut(tween(150)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top),
                ) {
                    Column {
                        Column(Modifier.padding(horizontal = K.gap1)) { UpdateSection(model) }
                        Spacer(Modifier.height(K.gap5))
                    }
                }
                KavPart("settings.root", Unit, { org.json.JSONObject() }) {
                    Column(Modifier.padding(horizontal = K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap6)) {
                        for (group in SETTINGS_GROUPS) PartGroup(group.mapNotNull { id -> parts.firstOrNull { it.id == id } }) { id ->
                            if (!uk.noammm.kav.ext.Actions.take(ctx, "settings.page.open") { org.json.JSONObject().put("page", id) }) page = id
                        }
                    }
                }
            } else {
                // Inside Extensions, the step the rider is on (a source, Sources), and on Sources its refresh and plus.
                val headTitle = (if (open.id == "extensions") ExtUi.title else null) ?: open.title
                val headBack = { backs?.onBackPressed() ?: run { page = null } }
                val headAdd = if (open.id == "extensions") ExtUi.headerAdd else null
                val headCheck = if (open.id == "extensions") ExtUi.headerCheck else null
                val about = { org.json.JSONObject().put("page", open.id).put("title", headTitle) }
                KavPart("settings.page.header", open.id to headTitle, about) {
                    if (headAdd != null || headCheck != null) ScreenHeader(headTitle, back = headBack) {
                        headCheck?.let { ExtCheckButton(it) }
                        headAdd?.let { KavIconButton(Icons.Rounded.Add, T("Add a source", "הוספת מקור"), it, floating = true, tint = K.text) }
                    } else ScreenHeader(headTitle, "", back = headBack)
                }
                Column(Modifier.padding(horizontal = K.gap1)) {
                    KavPart("settings.page.${open.id}", open.id to headTitle, about, inset = K.gap3) { Column(content = open.content) }
                }
            }
        }
    }
    sheet?.let { open ->
        BottomSheet({ page = null }, scrolls = true) { close ->
            val about = { org.json.JSONObject().put("page", open.id).put("title", open.title) }
            KavPart("settings.page.header", open.id to open.title, about) { SheetHeader(open.title, { close { page = null } }) }
            KavPart("settings.page.${open.id}", open.id to open.title, about) { Column(content = open.content) }
        }
    }
    SeenPlacePicker(model, choosing) { choosing = false }
    }
}

// Picking the place search sees: it slides up over the page it was asked from and back down onto it, which stays.
@Composable
internal fun SeenPlacePicker(model: KavModel, open: Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    androidx.compose.animation.AnimatedVisibility(open, enter = modalIn(), exit = modalOut()) {
        var q by remember { mutableStateOf("") }
        PlacePicker(
            title = T("A place for Google to see", "מקום ש-Google יראה"),
            here = null,
            allowMyLocation = false,
            onMyLocation = {},
            onPick = { p -> model.setSeen(ctx, Seen.PLACE, p); onClose() },
            onDismiss = onClose,
            favourites = model.favourites,
            onSaveFavourites = { model.saveFavourites(ctx, it) },
            query = q,
            onQuery = { q = it },
            net = model.net,
        )
    }
}

// sheet: the part's few choices open in a sheet over Settings instead of on a page of their own.
private class SettingsPart(val id: String, val title: String, val summary: String, val summaryColor: Color?, val sheet: Boolean, val content: @Composable ColumnScope.() -> Unit)

// Riding first (what is changed most), then how Kav looks, then Kav itself.
private val SETTINGS_GROUPS = listOf(
    listOf("trips", "paying"),
    listOf("look", "language", "map"),
    listOf("privacy", "extensions", "kav"),
)

@Composable
private fun PartGroup(parts: List<SettingsPart>, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Box(Modifier.padding(start = K.gap4).fillMaxWidth().height(0.5.dp).background(K.border))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClick = { onOpen(p.id) })
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(p.title, fontSize = 16.sp, color = K.text)
                    Text(p.summary, fontSize = 14.sp, color = p.summaryColor ?: K.dim, lineHeight = 20.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(K.gap2))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
                    tint = K.dim, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// A heading inside a part.
@Composable
private fun Sub(title: String) = SectionHeader(title)

// Kav's GitHub forms (Feedback.kt), the code, and the coffee.
@Composable
private fun HelpSection() {
    val ctx = LocalContext.current
    var reporting by remember { mutableStateOf(false) }
    @Composable
    fun link(title: String, onClick: () -> Unit) = ListRow(
        title, onClick = onClick, chevron = false,
        trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp)) },
    )
    InfoHeader(T("Help and feedback", "עזרה ומשוב"), T(
            "The forms open blank. For a bug, Kav shows what it can add about itself and your phone, and adds it only if you choose to.",
            "הטפסים נפתחים ריקים. בדיווח על באג, Kav מראה מה היא יכולה להוסיף עליה ועל הטלפון, ומוסיפה אותם רק אם תבחרו בכך.",
        ))
    ListGroup {
        row { link(T("Report a bug", "דיווח על באג")) { reporting = true } }
        row { link(T("Ask for a feature", "בקשה לפיצ'ר")) { openLink(ctx, featureLink()) } }
        row { link(T("Kav's code", "הקוד של Kav")) { openLink(ctx, REPO_URL) } }
        row { link(T("Buy me a coffee", "קנו לי קפה")) { openLink(ctx, COFFEE_URL) } }
    }
    if (reporting) BugReportSheet { reporting = false }
}

// Before GitHub's bug form opens: the details Kav can add, as they would go, and one switch to add them, off each time.
// Off, the form opens blank.
@Composable
private fun BugReportSheet(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val details = remember { bugDetails(ctx) }
    var add by remember { mutableStateOf(false) }
    val (version, android, device, setup) = details.map { it.second }
    Sheet(onClose) { close ->
        Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
            SheetHeader(T("Report a bug", "דיווח על באג"), { close(onClose) })
            ListGroup {
                row { SwitchRow(T("Add these details", "להוסיף את הפרטים האלה"), add) { add = it } }
                row {
                    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap3), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (line in listOf("Kav $version", "Android $android", device, setup).filter { it.isNotBlank() }) {
                            Text(T.ltr(line), style = K.type.subhead)
                        }
                    }
                }
            }
            KavButton(T("Open GitHub", "פתיחת GitHub"), { close { onClose(); openLink(ctx, bugLink(details, add)) } }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun LookChoices(inset: Dp = 0.dp, heading: @Composable (String) -> Unit) {
    val ctx = LocalContext.current
    heading(T("Look", "מראה"))
    LookRow(inset)
    if (!liquidGlassReady) return
    heading(T("Glass", "זכוכית"))
    Row(Modifier.padding(horizontal = inset).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        KavSegmented(
            listOf(T("Transparent", "שקוף"), T("Solid", "אחיד")), selected = if (K.liquid) 0 else 1,
            onSelect = { i -> K.restyle { K.liquid = i == 0 }; Prefs.setLiquidGlass(ctx, i == 0) },
        )
    }
}

// Speech turned on where the phone can say nothing: it has no text-to-speech engine, or none with a voice for Kav's
// language.
private fun noVoice(ctx: android.content.Context) = android.widget.Toast.makeText(
    ctx,
    T("No English voice on this phone. Add one in Android's Text-to-speech settings.",
        "אין בטלפון קול בעברית. הוסיפו אחד בהגדרות המרת טקסט לדיבור של Android."),
    android.widget.Toast.LENGTH_LONG,
).show()

// A trip's voice: the sheet behind the navigation screen's speaker (Navigate.kt). onChange: a switch was moved.
@Composable
internal fun OnTheWayChoices(onChange: () -> Unit) {
    val ctx = LocalContext.current
    var spoken by remember { mutableStateOf(Prefs.spokenAlerts(ctx)) }
    var turns by remember { mutableStateOf(Prefs.spokenTurns(ctx)) }
    ListGroup(Modifier.padding(top = K.gap4)) {
        row {
            ToggleRow(T("Say alerts out loud", "הקראת התראות בקול"), spoken, { SpeakerGlyph(toggleInk(spoken), 18.dp) }, info = T(
                "Spoken over what's playing, in Kav's language. On silent, only into headphones.",
                "מושמעות מעל מה שמתנגן, בשפה של Kav. במצב שקט, רק באוזניות.",
            )) { on ->
                spoken = on; Prefs.setSpokenAlerts(ctx, on); onChange()
                if (on) uk.noammm.kav.Speaker.say(ctx, T("Alerts will sound like this", "כך יישמעו ההתראות")) { noVoice(ctx) }
            }
        }
        row {
            ToggleRow(T("Say walking directions", "הקראת הוראות הליכה"), turns, { WalkGlyph(toggleInk(turns), 16.dp) }) { on ->
                turns = on; Prefs.setSpokenTurns(ctx, on); onChange()
                if (on) uk.noammm.kav.Speaker.say(ctx, T("Turn right onto Herzl", "פנו ימינה להרצל")) { noVoice(ctx) }
            }
            // Only with alerts said out loud, which it is about.
            AnimatedVisibility(
                spoken,
                enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
            ) {
                Column {
                    Separator(K.gap4)
                    var inCalls by remember { mutableStateOf(Prefs.spokenInCalls(ctx)) }
                    ToggleRow(T("Speak during calls", "הקראה בזמן שיחה"), inCalls, { CallGlyph(toggleInk(inCalls)) }, info = T(
                        "Only a bus arriving and getting off, and only you hear them. Not on speakerphone.",
                        "רק הגעת האוטובוס והירידה, ורק אתם שומעים אותן. לא כשהרמקול פועל.",
                    )) { on -> inCalls = on; Prefs.setSpokenInCalls(ctx, on) }
                }
            }
        }
    }
}

// What a trip does by itself and what its cards show while it's under way: in Settings › Trips.
@Composable
private fun DuringTripChoices(startNow: Boolean, onStartNow: (Boolean) -> Unit, suggestBus: Boolean, onSuggestBus: (Boolean) -> Unit,
    walkTurns: Boolean, onWalkTurns: (Boolean) -> Unit) {
    SectionHeader(T("During a trip", "במהלך נסיעה"))
    ListGroup {
        row {
            ToggleRow(T("Start right away", "להתחיל מיד"), startNow, { RouteGlyph(toggleInk(startNow)) }, info = T(
                "A trip goes straight to its first walk instead of waiting for its time.",
                "הנסיעה עוברת מיד להליכה הראשונה, בלי לחכות לשעה שלה.",
            ), onChange = onStartNow)
        }
        row {
            ToggleRow(
                T("Recognize the bus", "זיהוי האוטובוס"), suggestBus, { ModeGlyph(Mode.BUS, toggleInk(suggestBus), 18.dp) }, info = T(
                    "While Kav is open and you move with a bus, Home offers to follow it. Kav's server is asked for the buses around you.",
                    "כש-Kav פתוחה ואתם נוסעים עם אוטובוס, מסך הבית מציע לעקוב אחריו. השרת של Kav נשאל על האוטובוסים סביבכם.",
                ), onChange = onSuggestBus,
            )
        }
        row {
            ToggleRow(T("Walking directions on cards", "הוראות הליכה בכרטיסים"), walkTurns, { WalkGlyph(toggleInk(walkTurns), 16.dp) },
                onChange = onWalkTurns)
        }
    }
}

// How bus lines look: in Look.
@Composable
internal fun LineBadgeChoice() {
    val ctx = LocalContext.current
    SectionHeader(T("Line badges", "סמלי הקווים"))
    val badges = listOf(LineBadgeAppearance.KAV, LineBadgeAppearance.STRIP)
    KavSegmented(
        listOf("Kav", T("Strip", "פס")), badges.indexOf(K.lineBadgeAppearance),
        { i -> K.lineBadgeAppearance = badges[i]; Prefs.setLineBadges(ctx, badges[i]) },
    )
}

// Whether Kav tells of new versions on its own: a row of the updates' card in Settings (AutomaticUpdates).
@Composable
internal fun UpdatePromptsRow() {
    val ctx = LocalContext.current
    ToggleRow(
        T("New version alerts", "גרסאות חדשות"), Shown.updatePrompts,
        { Icon(Icons.Rounded.NewReleases, null, tint = toggleInk(Shown.updatePrompts), modifier = Modifier.size(22.dp)) },
        info = T("A popup, and a dot on the Settings button, when a new version is out.", "חלון קופץ ונקודה על כפתור ההגדרות כשיוצאת גרסה חדשה."),
    ) { on -> Shown.updatePrompts = on; Prefs.setUpdatePrompts(ctx, on) }
}

// Which map Kav draws, under how it looks: by provider first: Kav's own, kept on the phone; one of OpenFreeMap's four,
// loaded from OpenFreeMap until they're downloaded; GovMap's; or Esri's satellite photos under Kav's names. Only the
// provider picked opens its own rows. The download the chosen map needs or may have is right under the choices, in one
// card, then what's on the phone, each pack once with the maps that go with it.
@Composable
internal fun MapChoices() {
    val ctx = LocalContext.current
    // The map as it looks with the choices under it, around Dizengoff Center, as a still picture (MapPreview).
    MapPreview(200.dp, Modifier.padding(top = K.gap2))
    InfoHeader(
        T("Provider", "ספק"),
        T(
            "Kav's map is on your phone and works offline. The others load as you look, so their provider sees the area you view.",
            "המפה של Kav נמצאת בטלפון ועובדת בלי אינטרנט. האחרות נטענות בזמן הצפייה, ולכן הספק שלהן רואה את האזור שאתם צופים בו.",
        ),
    )
    fun pick(t: MapFile.Type) { MapFile.type = t; Prefs.setMapType(ctx, t) }
    val providers = MAP_PROVIDERS
    val at = providers.indexOfFirst { MapFile.type in it.second }
    // A provider's first map when it's picked.
    KavSegmented(providers.map { it.first }, at, { i -> if (i != at) pick(providers[i].second.first()) })
    fun open(t: MapFile.Type) = providers[at].second.contains(t)
    // Kav's own map: its buildings standing up in 3D close in, or flat.
    ProviderRows(open(MapFile.Type.STANDARD)) {
        row {
            ToggleRow(T("3D buildings", "בניינים בתלת-ממד"), MapFile.buildings3d, { Icon(Icons.Rounded.Apartment, contentDescription = null, tint = toggleInk(MapFile.buildings3d), modifier = Modifier.size(20.dp)) },
                subtitle = T("Stand up when you tilt the map", "מתרוממים כשמטים את המפה"),
                subtitleMaxLines = Int.MAX_VALUE) { on ->
                MapFile.buildings3d = on; Prefs.setBuildings3d(ctx, on)
            }
        }
    }
    ProviderRows(open(MapFile.Type.LIBERTY)) {
        for ((t, look) in listOf(
            MapFile.Type.LIBERTY to T("Light, in color", "בהירה, צבעונית"),
            MapFile.Type.BRIGHT to T("Light", "בהירה"),
            MapFile.Type.DARK to T("Dark gray", "אפורה כהה"),
            MapFile.Type.FIORD to T("Dark blue", "כחולה כהה"),
        )) row { MapTypeChoice(mapName(t), look, MapFile.type == t) { pick(t) } }
    }
    ProviderRows(open(MapFile.Type.GOVMAP)) {
        for ((t, look) in listOf(
            MapFile.Type.GOVMAP to T("Survey of Israel streets, names in Hebrew", "רחובות המרכז למיפוי ישראל, שמות בעברית"),
            MapFile.Type.GOVMAP_PHOTO to T("Survey of Israel aerial photos, 2025", "תצלומי אוויר של המרכז למיפוי ישראל, 2025"),
        )) row { MapTypeChoice(mapName(t), look, MapFile.type == t) { pick(t) } }
    }
    MapDownload()
}

// Map providers in the order the map's choice lists them, Kav's own first, each with its maps.
private val MAP_PROVIDERS = listOf(
    "Kav" to listOf(MapFile.Type.STANDARD),
    "OpenFreeMap" to listOf(MapFile.Type.LIBERTY, MapFile.Type.BRIGHT, MapFile.Type.DARK, MapFile.Type.FIORD),
    "GovMap" to listOf(MapFile.Type.GOVMAP, MapFile.Type.GOVMAP_PHOTO),
    "Esri" to listOf(MapFile.Type.SATELLITE),
)

// The rows of the provider picked, in and out as every dependent row is.
@Composable
private fun ProviderRows(open: Boolean, rows: ListGroupScope.() -> Unit) {
    AnimatedVisibility(
        open,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) { ListGroup(Modifier.padding(top = K.gap3), content = rows) }
}

internal fun mapName(t: MapFile.Type) = when (t) {
    MapFile.Type.STANDARD -> T("Standard", "רגילה")
    MapFile.Type.SATELLITE -> T("Satellite", "לוויין")
    MapFile.Type.GOVMAP -> T("GovMap streets", "GovMap רחובות")
    MapFile.Type.GOVMAP_PHOTO -> T("GovMap aerial", "GovMap תצלום אוויר")
    else -> t.name.lowercase().replaceFirstChar { it.uppercase() }
}

// What the map is and where it comes from, for Settings' list.
@Composable
private fun mapSummary(): String {
    val type = MapFile.type
    val p = MapFile.provider(type)
    return listOf(
        mapName(type),
        when {
            type == MapFile.Type.SATELLITE -> T("photos from Esri", "תצלומים מ-Esri")
            p is uk.noammm.kav.data.GovMap -> T("loads from GovMap", "נטענת מ-GovMap")
            p.online() -> T("loads from OpenFreeMap", "נטענת מ-OpenFreeMap")
            p.pack.state is MapPack.State.Ready -> T("on your phone", "בטלפון")
            else -> T("not downloaded yet", "עוד לא הורדה")
        },
    ).joinToString(" · ")
}

// The chosen map's download while it isn't on the phone, in one small card, the only place it shows: Keep on your
// phone, what it's for and its size, with a Download pill at its end; then the bar while it saves, or why it failed with
// Try again.
@Composable
private fun MapDownload() {
    val ctx = LocalContext.current
    val provider = MapFile.provider()
    val pack = provider.pack
    val state = pack.state
    // Kept while the card closes once the map is in, so it doesn't flash back to Download.
    var shown by remember { mutableStateOf(state) }
    if (state !is MapPack.State.Ready) shown = state
    AnimatedVisibility(state !is MapPack.State.Ready, enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)), exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120))) {
        KavCard(Modifier.padding(top = K.gap3)) {
            val now = shown
            // The row and the bar fade into each other while the space eases.
            androidx.compose.animation.AnimatedContent(
                now is MapPack.State.Downloading,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(150)))
                        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                },
                label = "mapDownload",
            ) { saving ->
                if (saving) DownloadBar((shown as? MapPack.State.Downloading)?.progress ?: 1f, T("Saving to your phone…", "שומרים בטלפון…"))
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(T("Keep on your phone", "שמירה בטלפון"), fontSize = 16.sp, color = K.text)
                        val size = megabytes(pack.bytes shr 20)
                        val failed = shown as? MapPack.State.Failed
                        Text(
                            when {
                                failed != null -> T("Couldn't download. ${failed.why}", "ההורדה לא הצליחה. ${failed.why}").trim()
                                !provider.needsPack -> T("Work offline too · $size", "עובדות גם בלי אינטרנט · $size")
                                MapFile.type == MapFile.Type.SATELLITE -> T("For the names on the photos · $size", "לשמות שעל התצלומים · $size")
                                else -> T("Needed for this map · $size", "נדרשת למפה הזו · $size")
                            },
                            style = K.type.subhead, color = if (failed != null) K.critical else K.textSecondary,
                        )
                    }
                    SmallButton(if (now is MapPack.State.Failed) T("Try again", "נסו שוב") else T("Download", "הורדה"), false) {
                        pack.download(ctx.applicationContext.filesDir)
                    }
                }
            }
        }
    }
}

// One map, picked with a radio button; tapping the whole row picks it.
@Composable
private fun MapTypeChoice(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        RadioButton(
            selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = K.accentInk, unselectedColor = K.dim),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, color = K.text)
            Text(detail, style = K.type.subhead, color = K.dim)
        }
    }
}

@Composable
internal fun PlanChoices(model: KavModel) {
    val ctx = LocalContext.current
    val offered = filtersOf(KavPlan.source)
    var startNow by remember { mutableStateOf(Prefs.beginTripsImmediately(ctx)) }
    var suggestBus by remember { mutableStateOf(OnBus.suggest(ctx)) }
    var walkTurns by remember { mutableStateOf(Prefs.walkTurnsOnCards(ctx)) }
    // Kav's: every way of getting there, the usual routes, a card with wheelchair access and without emissions, and a
    // trip that waits for its time, follows no bus by itself and keeps its cards short.
    val kavs = offered.all { it in model.filters } && KavPlan.settings == PlanSettings() && Shown.accessibility && !Shown.co2 &&
        !startNow && !suggestBus && !walkTurns
    // The page opens on its choices themselves, set to Kav's; a page holding only a Default or Custom choice was a page
    // for one setting (Noam, 27 Nov 2026). Reset to default shows once something differs.
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
        InfoHeader(
            T("What a plan may show", "מה מסלול יכול לכלול"),
            T("Shuttle: municipal shuttle lines. Taxi: Gett rides, on their own or before a train.",
                "שאטל: קווי שאטל עירוניים. מונית: נסיעות Gett, בפני עצמן או לפני רכבת."),
        )
        ListGroup {
            offered.forEach { f ->
                row {
                    val on = f in model.filters
                    ToggleRow(f.label, on, { FilterGlyph(f, on) }) { model.setFilter(ctx, f, it) }
                }
            }
        }
        RouteSettings()
        InfoHeader(T("What a card shows", "מה מוצג בכרטיס"), T(
            "Emissions are CO2e per passenger, worked out from each ride's distance.",
            "הפליטות הן CO2e לנוסע, מחושבות לפי המרחק של כל נסיעה.",
        ))
        ListGroup {
            row {
                ToggleRow(T("Wheelchair access", "נגישות לכיסא גלגלים"), Shown.accessibility,
                    { AccessibleGlyph(toggleInk(Shown.accessibility), 18.dp) }) { on ->
                    Shown.accessibility = on; Prefs.setShowAccessibility(ctx, on)
                }
            }
            row {
                ToggleRow(T("Emissions", "פליטות"), Shown.co2, { GlobeGlyph(toggleInk(Shown.co2), K.surface1, 18.dp) }) { on ->
                    Shown.co2 = on; Prefs.setShowCo2(ctx, on)
                }
            }
        }
        DuringTripChoices(
            startNow, { startNow = it; Prefs.setBeginTripsImmediately(ctx, it) },
            suggestBus, { suggestBus = it; OnBus.setSuggest(ctx, it) },
            walkTurns, { walkTurns = it; Prefs.setWalkTurnsOnCards(ctx, it) },
        )
        // Back to Kav's own, once anything differs.
        AnimatedVisibility(
            !kavs,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) {
            KavButton(T("Reset to default", "איפוס לברירת המחדל"), {
                offered.filter { it !in model.filters }.forEach { model.setFilter(ctx, it, true) }
                KavPlan.settings = PlanSettings(); Prefs.setPlanSettings(ctx, PlanSettings())
                Shown.accessibility = true; Prefs.setShowAccessibility(ctx, true)
                Shown.co2 = false; Prefs.setShowCo2(ctx, false)
                startNow = false; Prefs.setBeginTripsImmediately(ctx, false)
                suggestBus = false; OnBus.setSuggest(ctx, false)
                walkTurns = false; Prefs.setWalkTurnsOnCards(ctx, false)
            }, Modifier.padding(top = K.gap4), style = ButtonStyle.Plain)
        }
    }
}

// How Kav's planner, and the rider's own server, pick routes for this rider (KavPlan.settings): one group of four rows,
// each a choice of two or three in a menu, the usual in the middle.
@Composable
private fun RouteSettings() {
    val ctx = LocalContext.current
    val now = KavPlan.settings
    fun save(next: PlanSettings) { KavPlan.settings = next; Prefs.setPlanSettings(ctx, next) }
    InfoHeader(T("Walking and changes", "הליכה והחלפות"), listOf(
        T("Walking pace", "קצב הליכה") to paceInfo(), T("Walking", "הליכה") to walkingInfo(),
        T("Changes", "החלפות") to changesInfo(), T("Time at the stop", "זמן בתחנה") to spareInfo(),
    ).joinToString("\n") { (row, info) -> "$row — $info" })
    ListGroup {
        row {
            PickRow(T("Walking pace", "קצב הליכה"), listOf(T("Slow", "איטי"), T("Usual", "רגיל"), T("Fast", "מהיר")),
                PlanSettings.Pace.entries.indexOf(now.pace)) { save(now.copy(pace = PlanSettings.Pace.entries[it])) }
        }
        row {
            PickRow(T("Walking", "הליכה"), listOf(T("Less", "פחות"), T("Usual", "רגיל"), T("I don't mind", "לא אכפת לי")),
                PlanSettings.Walking.entries.indexOf(now.walking)) { save(now.copy(walking = PlanSettings.Walking.entries[it])) }
        }
        row {
            PickRow(T("Changes", "החלפות"), listOf(T("Usual", "רגיל"), T("Fewer", "פחות")),
                PlanSettings.Changes.entries.indexOf(now.changes)) { save(now.copy(changes = PlanSettings.Changes.entries[it])) }
        }
        row {
            PickRow(T("Time at the stop", "זמן בתחנה"), listOf(T("Tight", "צפוף"), T("Usual", "רגיל"), T("Relaxed", "נינוח")),
                PlanSettings.Spare.entries.indexOf(now.spare)) { save(now.copy(spare = PlanSettings.Spare.entries[it])) }
        }
    }
}

// What each choice does, under its own "i", in a rider's words; the rules themselves are the server's (live.py
// rider_cost(), kept()).
internal fun paceInfo() = T(
    "Slow: 1 meter a second. Usual: 1.33 (4.8 km/h). Fast: 1.6.",
    "איטי: מטר בשנייה. רגיל: 1.33 (4.8 קמ״ש). מהיר: 1.6.",
)

internal fun walkingInfo() = T(
    "Less: routes with less walking, even if a little longer. I don't mind: more walking to save time or a change.",
    "פחות: מסלולים עם פחות הליכה, גם אם הם קצת ארוכים יותר. לא אכפת לי: יותר הליכה כדי לחסוך זמן או החלפה.",
)

internal fun changesInfo() = T(
    "Fewer: routes with fewer changes, even if they arrive a few minutes later.",
    "פחות: מסלולים עם פחות החלפות, גם אם הם מגיעים כמה דקות מאוחר יותר.",
)

internal fun spareInfo() = T(
    "Tight: no spare time, so you leave later and miss more buses. Relaxed: a minute and a half before a bus and three at a change.",
    "צפוף: בלי זמן מיותר, כך שיוצאים מאוחר יותר ומפספסים יותר אוטובוסים. נינוח: דקה וחצי לפני אוטובוס ושלוש דקות בהחלפה.",
)

// A row whose value is picked from a short menu, the menu styled like the stop-time one in Results.
@Composable
private fun PickRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListRow(title, onClick = { open = true }, chevron = false, trailing = {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap1)) {
                Text(options[selected], style = K.type.subhead, color = K.textSecondary, maxLines = 1)
                Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(16.dp))
            }
            androidx.compose.material3.DropdownMenu(
                open, onDismissRequest = { open = false }, modifier = Modifier.background(K.bg),
                containerColor = K.bg, tonalElevation = 0.dp, shadowElevation = 8.dp,
                shape = RoundedCornerShape(K.rTile), border = androidx.compose.foundation.BorderStroke(0.5.dp, K.separator),
            ) {
                options.forEachIndexed { i, label ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(label, style = K.type.body, color = if (i == selected) K.accentInk else K.text) },
                        onClick = { open = false; onSelect(i) },
                    )
                }
            }
        }
    })
}

private fun fareProfileName(profile: uk.noammm.kav.data.FareProfile): String = when (profile) {
    uk.noammm.kav.data.FareProfile.REGULAR -> T("Regular", "רגיל")
    uk.noammm.kav.data.FareProfile.YOUTH -> T("Youth", "נוער")
    uk.noammm.kav.data.FareProfile.STUDENT -> T("Student", "סטודנט")
    uk.noammm.kav.data.FareProfile.SENIOR_WOMAN -> T("Women aged 62 to 66", "נשים בגיל 62 עד 66")
    uk.noammm.kav.data.FareProfile.SENIOR -> T("Age 67 and over", "גיל 67 ומעלה")
    uk.noammm.kav.data.FareProfile.DISABILITY -> T("Disability", "נכים")
    uk.noammm.kav.data.FareProfile.NATIONAL_INSURANCE -> T("National Insurance benefit", "זכאי ביטוח לאומי")
    uk.noammm.kav.data.FareProfile.DISCHARGED -> T("Recently discharged", "משוחררי צבא ושירות לאומי")
    uk.noammm.kav.data.FareProfile.BLIND -> T("Blind or visually impaired", "עיוורים ולקויי ראייה")
    uk.noammm.kav.data.FareProfile.GEOGRAPHIC -> T("Geographic eligibility", "זכאות גאוגרפית")
    uk.noammm.kav.data.FareProfile.YOUNG -> T("Young adults aged 18 to 26", "צעירים בגיל 18 עד 26")
}

// The fare profile Kav's prices are worked out for, at the bottom of Settings' paying page, where with paying on it has a
// header of its own, apart from the app's own discount profile above it.
@Composable
internal fun FareProfileGroup(header: Boolean = false) {
    if (header) SectionHeader(T("Prices in Kav", "מחירים ב-Kav"))
    ListGroup(if (header) Modifier else Modifier.padding(top = K.gap6)) { row { FareProfileChoice() } }
}

@Composable
private fun FareProfileChoice() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    ListRow(T("Fare profile", "פרופיל הנחה"), subtitle = FareFromRavPass.subtitle(fareProfileName(Shown.fareProfile)),
        subtitleMaxLines = 2, onClick = { open = true })
    if (open) androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
    BottomSheet({ open = false }, scrolls = true) { close ->
        SheetHeader(T("Fare profile", "פרופיל הנחה"), { close { open = false } })
        FareFromRavPassRow()
        for (profile in uk.noammm.kav.data.FareProfile.entries) ListRow(
            fareProfileName(profile),
            subtitle = when (profile) {
                uk.noammm.kav.data.FareProfile.REGULAR -> T("Full single-ride fare", "מחיר רגיל לנסיעה בודדת")
                uk.noammm.kav.data.FareProfile.GEOGRAPHIC -> T("50% off monthly caps; regular single-ride fare", "50% הנחה בתקרה החודשית; מחיר רגיל לנסיעה בודדת")
                uk.noammm.kav.data.FareProfile.YOUNG -> T("33% off monthly caps; regular single-ride fare", "33% הנחה בתקרה החודשית; מחיר רגיל לנסיעה בודדת")
                uk.noammm.kav.data.FareProfile.DISCHARGED -> T("Free travel during the first year after discharge", "נסיעה חינם בשנה הראשונה מהשחרור")
                else -> T("${profile.discount}% off single rides", "${profile.discount}% הנחה בנסיעה בודדת")
            },
            subtitleMaxLines = Int.MAX_VALUE,
            trailing = { RadioButton(profile == Shown.fareProfile, onClick = null) },
            onClick = {
                Shown.fareProfile = profile; Prefs.setFareProfile(ctx, profile); FareFromRavPass.clear(ctx)
                close { open = false }
            }, chevron = false,
        )
    }
    }
}

// Detours and other service alerts on the rides, whichever planner plans, from the source picked (RideAlerts): Kav's
// server or the rider's own, or Google Maps asked from the phone. Always on.
@Composable
internal fun AlertsChoice() {
    val ctx = LocalContext.current
    AlertsSource()
    // Only a Kav server translates notices, so the choice of their language is offered with one.
    AnimatedVisibility(
        RideAlerts.translates(),
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) {
        Column {
            InfoHeader(T("Notices in", "שפת ההודעות"), T(
                "Operators write notices in Hebrew. For English, the server translates the notice's text; nothing of your trip is sent.",
                "המפעילים כותבים את ההודעות בעברית. לאנגלית, השרת מתרגם את נוסח ההודעה; שום דבר על הנסיעה לא נשלח.",
            ))
            // Automatic first, then Kav's language.
            val order = listOf("auto") + Presets.languagesFrom(T.lang).map { it.code }
            KavSegmented(
                order.map { if (it == "auto") T("Automatic", "אוטומטי") else Lang.entries.first { l -> l.code == it }.label },
                order.indexOf(RideAlerts.language),
                { i -> RideAlerts.language = order[i]; Prefs.setAlertLanguage(ctx, order[i]) },
            )
        }
    }
}

// How busy each ride usually is: from Kav's server, by the Ministry of Transport's count of Rav-Kav taps (KavCrowd); from
// Google Maps' page for its boarding stop (GoogleCrowd); or not shown. Each choice says what goes where.
@Composable
internal fun CrowdChoice() {
    val ctx = LocalContext.current
    var crowd by remember { mutableStateOf(Prefs.crowdSource(ctx)) }
    // What each choice is and what it sends where, behind the (i), so it fades as the choice changes.
    val server = if (KavLive.server == KavLive.KAV_SERVER) T("Kav's server", "השרת של Kav") else T("Your server", "השרת שלכם")
    val said = when (crowd) {
        Prefs.CROWD_KAV -> T(
            "How busy the line usually is at this hour, from counts of Rav-Kav taps. $server learns which lines you look at.",
            "כמה עמוס הקו בדרך כלל בשעה הזו, לפי ספירת תיקופי רב-קו. $server יודע אילו קווים אתם מסתכלים עליהם.",
        )
        Prefs.CROWD_GOOGLE -> T(
            "Google Maps' prediction. Google sees the stop you board at and your IP address.",
            "התחזית של Google Maps. Google רואה את תחנת העלייה ואת כתובת ה-IP שלכם.",
        )
        else -> T("Nothing is shown, and no one is asked.", "לא מוצג דבר, ואף אחד לא נשאל.")
    }
    InfoHeader(T("How busy", "עומס"), said)
    KavSegmented(
        listOf("Kav", "Google", T("Off", "כבוי")), Prefs.CROWDS.indexOf(crowd),
        { i ->
            crowd = Prefs.CROWDS[i]
            Prefs.setCrowdSource(ctx, crowd)
            GoogleCrowd.enabled = crowd == Prefs.CROWD_GOOGLE
            uk.noammm.kav.data.KavCrowd.enabled = crowd == Prefs.CROWD_KAV
        },
    )
}

@Composable
private fun AlertsSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Alerts from", "מקור ההודעות"),
        { s ->
            when (s) {
                Source.GOOGLE -> T(
                    "Asked from your phone for each ride of a trip you open. Google sees the stops you board and leave at, and your IP address.",
                    "Google נשאלת מהטלפון על כל קו במסלול שפתחתם. היא רואה את התחנות שבהן אתם עולים ויורדים, ואת כתובת ה-IP שלכם.",
                )
                Source.KAV -> T(
                    "Kav's server sends the whole country's list, so it sees your IP address but not your trip.",
                    "השרת של Kav שולח את הרשימה של כל הארץ, ולכן הוא רואה את כתובת ה-IP שלכם אבל לא את הנסיעה.",
                )
                Source.OWN -> T(
                    "Your own server sends the whole list the same way.",
                    "השרת שלכם שולח את כל הרשימה באותה דרך.",
                )
            }
        },
        Source.ALERTS, RideAlerts.source, { s -> RideAlerts.source = s; Prefs.setAlertSource(ctx, s) },
        // Google is asked from the phone, with no server to check.
        server = when (RideAlerts.source) {
            Source.KAV -> KavLive.KAV_SERVER
            Source.OWN -> KavLive.ownServer.ifEmpty { null }
            else -> null
        },
        // Set up above already when live locations, trip planning or the timetable come from it.
        fields = KavLive.source != Source.OWN && KavPlan.source != Source.OWN && Schedules.source != Source.OWN,
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to ""
            answers?.alerts != null -> K.realtime to T("Connected · alerts ready", "מחובר · ההתראות זמינות")
            answers != null -> K.problem to T("Connected, but it doesn't serve alerts", "מחובר, אבל הוא לא מגיש התראות")
            else -> K.problem to T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
        }
    }
}

// Each stop's photo, from Street View facing it.
@Composable
internal fun StopPhotoChoice() {
    val ctx = LocalContext.current
    ListGroup(Modifier.padding(top = K.gap6)) {
        row {
            ToggleRow(T("Photos", "תמונות"), StopPhotos.enabled, { PhotoGlyph(toggleInk(StopPhotos.enabled)) }, info = T(
                "A stop's is Street View facing it; a place's is from Google Maps. Google sees the stop or place, and your IP address.",
                "תמונה של תחנה היא Street View שפונה אליה, ושל מקום מגיעה מ-Google Maps. Google רואה את התחנה או המקום, ואת כתובת ה-IP שלכם.",
            )) { on ->
                StopPhotos.enabled = on; Prefs.setStopPhotos(ctx, on)
            }
        }
    }
}

// The rider's own proxy for all of Kav's requests (RiderProxy): its kind, where it is and its login, saved and tried at once.
@Composable
internal fun ProxyChoice() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = RiderProxy.setting
    InfoHeader(T("Proxy", "פרוקסי"), T(
        "Everything Kav asks for goes through your proxy, so servers see its address, not yours. If it can't be reached, nothing loads.",
        "כל מה ש-Kav מבקשת עובר דרך הפרוקסי שלכם, כך שהשרתים רואים את הכתובת שלו ולא את שלכם. אם אי אפשר להגיע אליו, שום דבר לא נטען.",
    ))
    val kinds = RiderProxy.Kind.entries
    var kind by remember { mutableStateOf(saved.kind) }
    var host by remember { mutableStateOf(saved.host) }
    var port by remember { mutableStateOf(saved.port.takeIf { it > 0 }?.toString().orEmpty()) }
    var user by remember { mutableStateOf(saved.user) }
    var password by remember { mutableStateOf(saved.password) }
    // null: not tried since a change; "": works; else what went wrong.
    var tried by remember { mutableStateOf<String?>(null) }
    var trying by remember { mutableStateOf(false) }
    val draft = RiderProxy.Setting(kind, host.trim(), port.trim().toIntOrNull() ?: 0, user, password)
    fun save(s: RiderProxy.Setting) {
        RiderProxy.set(ctx, s)
        tried = null
        if (!s.on) return
        trying = true
        scope.launch {
            tried = withContext(Dispatchers.IO) { RiderProxy.check() } ?: ""
            trying = false
        }
    }
    KavSegmented(
        listOf(T("Off", "כבוי"), "HTTP", "SOCKS5"), kinds.indexOf(kind),
        { i ->
            kind = kinds[i]
            // Off at once; a kind with its address already filled in, too.
            val next = draft.copy(kind = kinds[i])
            if (next.kind == RiderProxy.Kind.OFF || next.on) save(next)
        },
    )
    AnimatedVisibility(
        kind != RiderProxy.Kind.OFF,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut(tween(120)),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                KavField(host, { host = it; tried = null }, T("Address", "כתובת"), Modifier.weight(1f),
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                KavField(port, { v -> port = v.filter(Char::isDigit).take(5); tried = null }, T("Port", "פורט"), Modifier.width(110.dp),
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            }
            KavField(user, { user = it; tried = null }, T("Username (if it has one)", "שם משתמש (אם יש)"), Modifier.fillMaxWidth())
            KavField(password, { password = it; tried = null }, T("Password", "סיסמה"), Modifier.fillMaxWidth(),
                keyboard = androidx.compose.ui.text.input.KeyboardType.Password, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                val said = when {
                    trying -> null
                    tried == "" -> T("Works: Kav goes through it", "עובד: Kav עוברת דרכו") to K.realtime
                    tried != null -> T("Couldn't connect: $tried", "אין חיבור: $tried") to K.problem
                    draft != saved -> T("Not saved yet", "עוד לא נשמר") to K.textSecondary
                    else -> null
                }
                Text(said?.first.orEmpty(), style = K.type.subhead, color = said?.second ?: K.textSecondary, modifier = Modifier.weight(1f))
                KavButton(T("Save and try", "שמירה ובדיקה"), { save(draft) }, enabled = draft.on, busy = trying, style = ButtonStyle.Secondary)
            }
            if (!RiderProxy.pagesFollow) Text(
                T("This phone's WebView can't use a proxy: pages shown in Kav connect directly.",
                    "ה-WebView בטלפון הזה לא תומך בפרוקסי: עמודים שמוצגים ב-Kav מתחברים ישירות."),
                style = K.type.subhead, color = K.problem,
            )
        }
    }
}

@Composable
internal fun LiveSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Live locations and arrival times", "מיקומים וזמני הגעה בזמן אמת"),
        { s ->
            when (s) {
                Source.GOOGLE -> ""
                Source.KAV -> T(
                    "From the Ministry of Transport, with Kav's arrival times. The server sees the lines and area you look at, not your IP address.",
                    "ממשרד התחבורה, עם זמני ההגעה של Kav. השרת רואה את הקווים והאזור שאתם צופים בהם, לא את כתובת ה-IP שלכם.",
                )
                Source.OWN -> T("Your own copy of Kav's server. ", "עותק משלכם של השרת של Kav. ") + kavStill()
            }
        },
        Source.LIVE, KavLive.source, { s -> KavLive.source = s; Prefs.setLiveSource(ctx, s) },
        // Own picked but not set up yet: its address and key come first, Kav's server answers until then.
        server = KavLive.server.takeUnless { KavLive.source == Source.OWN && KavLive.ownServer.isEmpty() },
        fields = true,
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to ""
            answers?.live == true -> K.realtime to T(
                "Connected · ${"%,d".format(answers.vehicles)} buses live",
                "מחובר · ${"%,d".format(answers.vehicles)} אוטובוסים בזמן אמת",
            )
            answers != null -> K.problem to T("Connected, but no live buses yet", "מחובר, אבל עוד אין אוטובוסים בזמן אמת")
            else -> K.problem to T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
        }
    }
}

// Who plans trips: Kav's server by default. The rider's own server plans once it's set up, and Kav's until then; one
// that lists no planner plans nothing, and says so.
@Composable
internal fun PlanningSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Trip planning", "תכנון מסלולים"),
        { s ->
            when (s) {
                // Google gives line alerts only, never offered here.
                Source.GOOGLE -> ""
                Source.KAV -> T(
                    "Kav's server plans the trips you search for. It sees each trip's two points and time, not your IP address.",
                    "השרת של Kav מתכנן את המסלולים שאתם מחפשים. הוא רואה את שתי הנקודות והשעה של כל מסלול, לא את כתובת ה-IP שלכם.",
                )
                Source.OWN -> T("Your own copy of Kav's server plans your trips. ", "עותק משלכם של השרת של Kav מתכנן את המסלולים שלכם. ") + kavStill()
            }
        },
        Source.PLANNING, KavPlan.source, { s -> KavPlan.source = s; Prefs.setPlanSource(ctx, s) },
        // Own picked but not set up yet: its address and key come first, Kav's server plans until then.
        server = KavPlan.server.takeUnless { KavPlan.source == Source.OWN && KavLive.ownServer.isEmpty() },
        // Set up above already when live locations come from it too.
        fields = KavLive.source != Source.OWN,
        checked = { at, c ->
            KavPlan.up = c is KavLive.Check.Answers
            KavPlan.offered = at == KavLive.KAV_SERVER || (c as? KavLive.Check.Answers)?.planner != null
        },
        note = when {
            KavPlan.source.key !in KavPlan.plannerTabs() ->
                T("Its tab is hidden, so an extension plans your trips.", "הלשונית שלו מוסתרת, ולכן תוסף מתכנן את המסלולים שלכם.")
            KavPlan.source == Source.OWN && KavLive.ownServer.isEmpty() ->
                if (KavLive.source == Source.OWN) T("Set up your server above. Until then, Kav's server plans trips.",
                    "הגדירו את השרת שלכם למעלה. עד אז, השרת של Kav מתכנן מסלולים.")
                else T("Until your server is set up, Kav's server plans trips.", "עד שהשרת שלכם מוגדר, השרת של Kav מתכנן מסלולים.")
            !KavPlan.offered && KavPlan.source == Source.OWN ->
                T("Your server doesn't plan trips yet. Turn its planner on, or pick Kav's server.",
                    "השרת שלכם עוד לא מתכנן מסלולים. הפעילו את המתכנן שלו, או בחרו בשרת של Kav.")
            else -> null
        },
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to ""
            answers != null && (KavPlan.server == KavLive.KAV_SERVER || answers.planner == true) ->
                K.realtime to T("Connected · trip planner ready", "מחובר · מתכנן המסלולים מוכן")
            answers?.planner == false -> K.problem to T("Connected, but its trip planner isn't ready yet", "מחובר, אבל מתכנן המסלולים שלו עוד לא מוכן")
            answers != null -> K.problem to T("Connected, but it doesn't plan trips", "מחובר, אבל הוא לא מתכנן מסלולים")
            else -> K.problem to if (KavPlan.source.provider) T("Provider unavailable. Try again later.", "הספק לא זמין. נסו שוב מאוחר יותר.")
                else T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
        }
    }
}

// Where the Lines tab, a line's stops and times, and stop boards come from: the week on the phone, Kav's (built on GitHub)
// or the rider's own server's, or a provider asked online as each opens, with the week on the phone standing in while it
// can't be reached.
@Composable
internal fun TimetableSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Timetable", "לוח זמנים"),
        { s ->
            when (s) {
                // Google gives line alerts only, never offered here.
                Source.GOOGLE -> ""
                Source.KAV -> T(
                    "The week of departures kept on your phone, from the Ministry of Transport's timetable. Lines and stop boards ask no one.",
                    "שבוע היציאות ששמור בטלפון, מלוח הזמנים של משרד התחבורה. קווים ולוחות תחנות לא שואלים אף אחד.",
                )
                Source.OWN -> T("The week your own server builds, kept on your phone. ", "השבוע שהשרת שלכם בונה, שמור בטלפון. ") + kavStill()
            } + if (s.provider) T(" Without signal, Kav shows the timetable on your phone instead.", " בלי קליטה, Kav מציגה במקום זאת את לוח הזמנים שבטלפון.") else ""
        },
        Source.TIMETABLE, Schedules.source, { s -> Schedules.source = s; Prefs.setTimetableSource(ctx, s) },
        // The week on the phone asks no one until it's updated (below); the own server once it's set up.
        server = when {
            Schedules.source.provider -> KavLive.serverOf(Schedules.source)
            Schedules.source == Source.OWN -> KavLive.ownServer.ifEmpty { null }
            else -> null
        },
        // Set up above already when live locations or trip planning come from it too.
        fields = KavLive.source != Source.OWN && KavPlan.source != Source.OWN,
        note = if (Schedules.source == Source.OWN && KavLive.ownServer.isEmpty() && (KavLive.source == Source.OWN || KavPlan.source == Source.OWN)) {
            T("Set up your server above. Until then, the week comes from GitHub.", "הגדירו את השרת שלכם למעלה. עד אז, השבוע מגיע מ-GitHub.")
        } else null,
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to ""
            answers != null && Schedules.source.provider -> K.realtime to T("Connected · timetable ready", "מחובר · לוח הזמנים זמין")
            answers?.timetable?.isNotEmpty() == true -> K.realtime to T("Connected · its week is built", "מחובר · השבוע שלו בנוי")
            answers != null -> K.problem to T("Connected, but its week isn't built yet", "מחובר, אבל השבוע שלו עוד לא נבנה")
            else -> K.problem to if (Schedules.source.provider) T("Provider unavailable. Try again later.", "הספק לא זמין. נסו שוב מאוחר יותר.")
                else T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
        }
    }
}

// What the choices as they are still take from Kav's server, said beside "My own" (Sources.onKav). Opening a link made
// on Kav's server always reads it from there, the link's own host.
private fun kavStill(): String {
    val parts = Sources.onKav(
        KavLive.source, KavPlan.source, if (KavPlan.everyPlanner) KavPlan.tabs() else emptyList(), TripShare.server, KavLive.ownServer,
    )
    if (parts.isEmpty()) return T("Nothing goes to Kav's server.", "שום דבר לא נשלח לשרת של Kav.")
    return T("Still from Kav's server: ", "עדיין מהשרת של Kav: ") + parts.joinToString(", ") {
        when (it) {
            Sources.Part.LIVE -> T("live locations", "מיקומים בזמן אמת")
            Sources.Part.TRIPS -> T("trip planning", "תכנון מסלולים")
            Sources.Part.TAB -> T("its planner's tab", "הלשונית של המתכנן שלו")
            Sources.Part.LINKS -> T("share links", "קישורי שיתוף")
        }
    } + "."
}

// Where new share links are made, once the rider has a server of their own to make them on: until picked, there while
// live times or trips come from it (Sources.shareOn).
@Composable
internal fun ShareServerChoice() {
    val ctx = LocalContext.current
    AnimatedVisibility(
        KavLive.ownServer.isNotEmpty(),
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) {
        Column {
            InfoHeader(T("Share links made on", "קישורי שיתוף נוצרים ב"), T(
                "Where a Kav link keeps the trip until it expires. Whoever opens the link reads it from there.",
                "איפה קישור Kav שומר את הנסיעה עד שתוקפו פג. מי שפותח את הקישור קורא אותה משם.",
            ))
            val order = listOf(Source.KAV, Source.OWN)
            KavSegmented(
                listOf(T("Kav's server", "השרת של Kav"), T("My own", "שרת משלי")),
                if (TripShare.server == KavLive.KAV_SERVER) 0 else 1,
                { i -> TripShare.picked = order[i]; Prefs.setShareServer(ctx, order[i]) },
            )
        }
    }
}

internal fun sourceName(source: Source): String = when (source) {
    Source.KAV -> "Kav"
    Source.OWN -> T("My own", "שרת משלי")
    Source.GOOGLE -> "Google"
}

// A null server means the own server isn't configured; a null status means its check is pending.
// Address and key fields appear only under the first choice using the own server.
@Composable
private fun SourceChoice(
    title: String, about: (Source) -> String, order: List<Source>, source: Source, onSource: (Source) -> Unit, server: String?,
    fields: Boolean, checked: (String, KavLive.Check) -> Unit = { _, _ -> }, note: String? = null,
    status: (KavLive.Check?) -> Pair<Color, String>,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    // The server set up shows wherever its fields do, also under a choice they moved to after it was set up elsewhere.
    var address by remember(KavLive.ownServer) { mutableStateOf(KavLive.ownServer.removePrefix("https://")) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<KavLive.Check?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    // The server in use, checked again whenever it changes.
    var inUse by remember { mutableStateOf<KavLive.Check?>(null) }
    LaunchedEffect(server, source) {
        inUse = null
        val at = server ?: return@LaunchedEffect
        val c = withContext(Dispatchers.IO) { KavLive.check(at, KavLive.keyFor(at)) }
        inUse = c
        checked(at, c)
    }
    // A check of another address under way, called off when another starts.
    var check by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Who may use the server, once Connect asked it: a person's account, or anyone. Kept until the address changes.
    var access by remember { mutableStateOf<String?>(null) }
    // A change of server or choice that moves where the daily timetable comes from asks the new one at once.
    fun moving(change: () -> Unit) {
        val before = KavLive.timetableServer
        change()
        if (KavLive.timetableServer != before) scope.launch(Dispatchers.IO) { runCatching { uk.noammm.kav.data.Timetable.serverChanged(app) } }
    }
    // device and user: this phone signed in there, on a server with accounts.
    fun use(at: String, with: String, bundleKey: String, device: String = "", user: String = "") = moving {
        KavLive.ownServer = at; KavLive.ownKey = with; KavLive.ownDevice = device; KavLive.ownUser = user
        Prefs.setOwnServer(ctx, at, with, bundleKey, device, user); KavLive.recheck.trySend(Unit)
        result = null
    }
    // Connect: the server asked who may use it. Anyone: in use at once. People with an account: the username and
    // password open under the address. Neither: what's wrong, under the buttons.
    fun connect(at: String) {
        check?.cancel()
        problem = null
        check = scope.launch {
            checking = true; result = null
            val c = withContext(Dispatchers.IO) { KavLive.check(at, "") }
            checking = false
            val answers = c as? KavLive.Check.Answers
            when {
                answers?.access == "accounts" -> { access = "accounts"; result = c }
                answers != null -> { access = answers.access; use(at, "", answers.bundleKey.orEmpty()) }
                else -> result = c
            }
        }
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    // Signing this phone in to a server with accounts, by a password or an invite, and using it once signed in.
    fun signIn(at: String, bundleKey: String, invite: String = "") {
        if (signingIn) return
        problem = null
        scope.launch {
            signingIn = true
            try {
                val got = withContext(Dispatchers.IO) { KavLive.signIn(at, username.trim(), password, invite) }
                password = ""
                use(at, got.token, bundleKey, got.device, got.user)
            } catch (e: KavLive.SignInRefused) {
                problem = e.why
            } catch (e: Exception) {
                problem = T("Couldn't reach the server to sign in.", "לא ניתן היה להגיע לשרת כדי להתחבר.")
            }
            signingIn = false
        }
    }
    // Outside the eased column: the header eases its own height (InfoHeader), and twice would be slower than every other (i).
    InfoHeader(title, about(source))
    Column(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
    // In the choice's own order (Source.LIVE, Source.PLANNING): its default first.
    Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        val rows = if (order.size > 4) order.chunked(3) else listOf(order)
        val pickedRow = rows.indexOfFirst { source in it }
        rows.forEachIndexed { row, choices ->
            // A row without the choice parks its thumb past its end when the choice is in a later row, before its start
            // when in an earlier one, so the thumb leaves and comes in the way the rows read on to each other.
            val picked = if (row < pickedRow) choices.size else choices.indexOf(source)
            KavSegmented(
                choices.map(::sourceName), picked,
                { i -> moving { onSource(choices[i]) }; KavLive.recheck.trySend(Unit) },
            )
        }
    }
    var said by remember { mutableStateOf(note) }
    if (note != null) said = note
    AnimatedVisibility(
        note != null,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) { NoteCard(said.orEmpty(), Modifier.padding(top = K.gap2)) }
    val settingUp = server == null
    val showFields = source == Source.OWN && fields
    // While the server is asked, a small ring turns where its dot is and the last answer's line stays in place.
    val asking = inUse == null
    val (dot, found) = status(inUse)
    var line by remember(server) { mutableStateOf("") }
    if (!asking) line = found
    val title = if (source.provider) sourceName(source) else if (server != KavLive.KAV_SERVER) T.ltr(server.orEmpty().removePrefix("https://")) else T("Kav's server", "השרת של Kav")
    // The card comes and goes with a choice that asks a server other than Kav's own, and what it says (another
    // provider, its check coming back) fades from one to the next.
    AnimatedVisibility(
        (!settingUp && server != KavLive.KAV_SERVER) || showFields,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) {
    Column {
    Spacer(Modifier.height(K.gap3))
    Column(Modifier.fillMaxWidth().panel(K.rCard).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        if (!settingUp) {
            val shade by androidx.compose.animation.animateColorAsState(dot, tween(250), label = "statusDot")
            androidx.compose.animation.AnimatedContent(
                title to line,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(androidx.compose.animation.SizeTransform(clip = false))
                },
                label = "sourceStatus",
            ) { (name, said) ->
                ListRow(
                    name, subtitle = said,
                    leading = {
                        androidx.compose.animation.Crossfade(asking, animationSpec = tween(150), label = "asking") { ring ->
                            if (ring) Spinner(10.dp) else Box(Modifier.size(10.dp).clip(CircleShape).background(shade))
                        }
                    },
                    onClick = if (source.provider) null else ({ open = !open }),
                )
            }
        }
        // What the check found opens under it, eases out while the next check runs, and back with what that finds.
        var found by remember { mutableStateOf(inUse) }
        if (inUse != null) found = inUse
        AnimatedVisibility(
            !settingUp && open && !source.provider && inUse != null,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut(tween(120)),
        ) {
            Column {
                Separator()
                Column(Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, bottom = K.gap4)) { found?.let { CheckLines(it) } }
            }
        }
        AnimatedVisibility(
            showFields,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut(tween(120)),
        ) {
          Column {
            if (!settingUp) Separator()
            Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                KavField(address, { address = it; result = null; problem = null; access = null }, "kav.example.com", Modifier.fillMaxWidth(),
                    keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                val here = KavLive.serverAddress(address)
                val connected = here != null && here == KavLive.ownServer
                val signedInHere = connected && KavLive.ownDevice.isNotEmpty()
                AnimatedVisibility(signedInHere) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                        Text(T("Signed in as ${KavLive.ownUser}", "מחוברים בתור ${KavLive.ownUser}"), style = K.type.body,
                            modifier = Modifier.weight(1f))
                        KavButton(T("Sign out", "התנתקות"), {
                            val at = KavLive.ownServer
                            scope.launch(Dispatchers.IO) { KavLive.signOut(at) }
                            use(at, "", Prefs.kavServerBundleKey(ctx))
                            access = "accounts"
                        }, style = ButtonStyle.Secondary)
                    }
                }
                AnimatedVisibility(access == "accounts" && !signedInHere) {
                    Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                        KavField(username, { username = it; problem = null }, T("Username", "שם משתמש"), Modifier.fillMaxWidth())
                        KavField(password, { password = it; problem = null }, T("Password", "סיסמה"), Modifier.fillMaxWidth(),
                            keyboard = androidx.compose.ui.text.input.KeyboardType.Password, secret = true)
                    }
                }
                var said by remember { mutableStateOf(problem) }
                if (problem != null) said = problem
                AnimatedVisibility(
                    problem != null,
                    enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) + fadeIn(tween(200, delayMillis = 60)),
                    exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) + fadeOut(tween(120)),
                ) { Text(said.orEmpty(), style = K.type.subhead, color = K.problem) }
                // The QR code the server's setup page shows: its address, with a person's invite that signs this phone in at
                // once; connected straight away.
                var scanning by remember { mutableStateOf(false) }
                val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> scanning = ok }
                KavButton(T("Scan the server's QR code", "סריקת קוד ה-QR של השרת"), {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) scanning = true
                    else askCamera.launch(android.Manifest.permission.CAMERA)
                }, style = ButtonStyle.Secondary, icon = Icons.Rounded.QrCodeScanner)
                // Over the whole screen, as the page's other sheets are: the page scrolls, so a sheet laid out in it would sit
                // inside this card.
                if (scanning) androidx.compose.ui.window.Dialog(
                    onDismissRequest = { scanning = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                ) { BottomSheet({ scanning = false }) { close ->
                    Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                        Text(T("Point the camera at the code on your server's setup page", "כוונו את המצלמה לקוד בדף ההגדרה של השרת שלכם"),
                            style = K.type.body)
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(K.rCard))) {
                            QrScanner(Modifier.matchParentSize(), onFail = { scanning = false }) { code ->
                                KavLive.serverCode(code)?.let { found ->
                                    address = found.address.removePrefix("https://"); access = null
                                    close {
                                        scanning = false
                                        if (found.invite.isEmpty()) connect(found.address)
                                        else scope.launch {
                                            val c = withContext(Dispatchers.IO) { KavLive.check(found.address, "") }
                                            result = c
                                            signIn(found.address, (c as? KavLive.Check.Answers)?.bundleKey.orEmpty(), found.invite)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } }
                // Connect, then Sign in where the server asks for an account, then nothing once in: one fading into the next.
                androidx.compose.animation.AnimatedContent(
                    when { access == "accounts" && !signedInHere -> 2; !connected -> 1; else -> 0 },
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                            .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                    },
                    label = "serverButton",
                ) { which ->
                    when (which) {
                        2 -> KavButton(T("Sign in", "התחברות"), {
                            here?.let { signIn(it, (result as? KavLive.Check.Answers)?.bundleKey ?: Prefs.kavServerBundleKey(ctx)) }
                        }, busy = signingIn)
                        1 -> KavButton(T("Connect", "התחברות לשרת"), {
                            if (here == null) problem = T("That isn't an https address.", "זו לא כתובת https.")
                            else if (!checking) connect(here)
                        }, busy = checking)
                        else -> {}
                    }
                }
                // A server set up here is kept until it's forgotten: its tab among the planners' too (KavPlan.tabs()).
                AnimatedVisibility(KavLive.ownServer.isNotEmpty()) {
                    KavButton(T("Forget this server", "שכחת השרת הזה"), {
                        val at = KavLive.ownServer
                        if (KavLive.ownDevice.isNotEmpty()) scope.launch(Dispatchers.IO) { KavLive.signOut(at) }
                        use("", "", "")
                        address = ""; access = null
                    }, style = ButtonStyle.Secondary)
                }
                // A test's findings come in under the buttons, and the next test's fade in over them.
                androidx.compose.animation.AnimatedContent(
                    result,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                            .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                    },
                    label = "testFound",
                ) { found -> found?.let { CheckLines(it) } }
            }
          }
        }
    }
    }
    }
    }
}

// What a test found, a line for each part of the server, as a rider would miss it.
@Composable
private fun CheckLines(c: KavLive.Check) {
    val lines: List<Pair<Boolean, String>> = when (c) {
        is KavLive.Check.Unreached -> listOf(false to when (c.why) {
            KavLive.Check.Why.NO_SUCH_HOST -> T("No server found at that address.", "לא נמצא שרת בכתובת הזו.")
            KavLive.Check.Why.HTTPS -> T("Its HTTPS certificate isn't valid, so Kav won't use it.", "תעודת ה-HTTPS שלו לא תקינה, ולכן Kav לא תשתמש בו.")
            KavLive.Check.Why.TIMEOUT -> T("It didn't answer in time.", "הוא לא ענה בזמן.")
            KavLive.Check.Why.NO_ANSWER -> T("Nothing answers at that address.", "שום דבר לא עונה בכתובת הזו.")
        })
        // Signed in there, the server turning it away means it signed this phone out (an admin did, or the account).
        KavLive.Check.Refused -> listOf(false to if (KavLive.ownDevice.isNotEmpty()) T(
            "The server signed this phone out. Sign in again.",
            "השרת ניתק את הטלפון הזה. התחברו שוב.",
        ) else T(
            "That server is private: it doesn't let other phones connect.",
            "השרת הזה פרטי: הוא לא מאפשר לטלפונים אחרים להתחבר.",
        ))
        KavLive.Check.NotKav -> listOf(false to T("Something answers there, but not Kav's server.", "משהו עונה שם, אבל לא השרת של Kav."))
        is KavLive.Check.Answers -> listOfNotNull(
            if (c.live) true to T("Live buses: ${"%,d".format(c.vehicles)} reporting now", "אוטובוסים בזמן אמת: ${"%,d".format(c.vehicles)} מדווחים עכשיו")
            else false to T(
                "No live buses yet: the Ministry of Transport isn't answering it. Check MOT_KEY.",
                "עוד אין אוטובוסים בזמן אמת: משרד התחבורה לא עונה לו. בדקו את MOT_KEY.",
            ),
            c.planner?.let {
                if (it) true to T("Trip planner: ready", "מתכנן המסלולים: מוכן")
                else false to T(
                    "Trip planner: not ready yet. Its first build takes about half an hour.",
                    "מתכנן המסלולים: עוד לא מוכן. הבנייה הראשונה שלו לוקחת בערך חצי שעה.",
                )
            },
            c.predictions?.let {
                if (it) true to T("Kav's predicted times: on", "הזמנים החזויים של Kav: פעילים")
                else false to T("Kav's predicted times: off, so the ministry's are shown", "הזמנים החזויים של Kav: כבויים, ולכן מוצגים אלה של משרד התחבורה")
            },
            c.timetable?.let { from ->
                val day = runCatching {
                    java.time.LocalDate.parse(from).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", T.locale))
                }.getOrNull()
                if (day != null) true to T("Today's timetable: from $day", "לוח הזמנים של היום: מ-$day")
                else false to T("Today's timetable: not built yet", "לוח הזמנים של היום: עוד לא נבנה")
            },
        )
    }
    Column(Modifier.padding(top = K.gap3), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((ok, text) in lines) Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(if (ok) K.realtime else K.problem))
            Text(text, style = K.type.subhead, color = if (ok) K.text else K.textSecondary)
        }
    }
}

// A shared trip's link: how long it lasts and how its page looks, under the Share sheet's Options (ShareSheet.kt).
// onChange: the style was saved, so the sheet's card and link are made again in it.
@Composable
internal fun LinkOptions(onChange: () -> Unit) {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(Prefs.linkStyle(ctx)) }
    fun save(next: uk.noammm.kav.data.ShareStyle) { style = next; Prefs.setLinkStyle(ctx, next); onChange() }
    // Made where the rider picked (TripShare.server).
    val on = if (TripShare.server != KavLive.KAV_SERVER) T("your server", "שרת שלכם") else T("Kav's server", "שרת של Kav")
    InfoHeader(
        T("Link expires after", "תוקף הקישור"),
        T("A Kav link keeps the trip you shared, with its stops and map, on $on until then.",
            "קישור Kav שומר את הנסיעה ששיתפתם, עם התחנות והמפה, ב$on עד אז."),
    )
    val expiryDays = listOf(3, 7, 30)
    KavSegmented(expiryDays.map { T("$it days", "$it ימים") }, expiryDays.indexOf(style.expiryDays),
        { save(style.copy(expiryDays = expiryDays[it])) })
    // Matching Kav is Automatic for all three; turned off, each starts from what Kav shows now.
    val matching = style.language == "auto" && style.stopLanguage == "auto" && style.theme == "auto"
    val kavLanguage = if (T.lang == Lang.HE) "he" else "en"
    val kavStops = if (StopNames.english) "en" else "he"
    val kavTheme = K.look.name.lowercase()
    ListGroup(Modifier.padding(top = K.gap6)) {
        row {
            ToggleRow(T("Page matches my Kav", "כמו ה-Kav שלי"), matching,
                { Icon(Icons.Rounded.Sync, null, tint = toggleInk(matching), modifier = Modifier.size(20.dp)) },
                info = T("The shared page's language, stop names and colors.",
                    "השפה, שמות התחנות והצבעים של העמוד המשותף.")) { on ->
                save(if (on) style.copy(language = "auto", stopLanguage = "auto", theme = "auto")
                else style.copy(language = kavLanguage, stopLanguage = kavStops, theme = kavTheme))
            }
        }
    }
    AnimatedVisibility(
        !matching,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) {
        Column {
            val languages = listOf("en", "he")
            Sub(T("Language", "שפה"))
            KavSegmented(listOf("English", "עברית"), languages.indexOf(style.language.takeUnless { it == "auto" } ?: kavLanguage),
                { save(style.copy(language = languages[it])) })
            Sub(T("Stop names", "שמות תחנות"))
            KavSegmented(listOf("English", "עברית"), languages.indexOf(style.stopLanguage.takeUnless { it == "auto" } ?: kavStops),
                { save(style.copy(stopLanguage = languages[it])) })
            Sub(T("Colors", "צבעים"))
            val themes = listOf("oled", "light", "dark")
            KavSegmented(listOf("OLED", T("Light", "בהיר"), T("Dark", "כהה")), themes.indexOf(style.theme.takeUnless { it == "auto" } ?: kavTheme),
                { save(style.copy(theme = themes[it])) })
        }
    }
}

// Rows that each turn something on or off and can be dragged into another order by their handle (or moved up and down
// by TalkBack's actions): the planners' tabs. A held row lifts and follows the finger, and the
// order changes as it passes half of a neighbour. A row that can't be turned off right now shows its switch dimmed.
@Composable
internal fun <I> SwitchOrderRows(
    order: List<I>, id: (I) -> String, label: (I) -> String, on: (I) -> Boolean,
    onToggle: (I) -> Unit, onOrder: (List<I>) -> Unit, enabled: (I) -> Boolean = { true },
) {
    var held by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableMapOf<String, Int>() }
    // A drag's handler lives as long as its row, so it reads the order as it is now, not as it was when the drag began.
    val latest by androidx.compose.runtime.rememberUpdatedState(order)
    val reorder by androidx.compose.runtime.rememberUpdatedState(onOrder)
    fun move(item: I, by: Int) {
        val i = order.indexOf(item)
        val other = i + by
        if (i < 0 || other !in order.indices) return
        onOrder(order.toMutableList().apply { add(other, removeAt(i)) })
    }
    order.forEachIndexed { i, item -> val key = id(item); key(key) {
        val name = label(item)
        val lifted = held == key
        val lift by androidx.compose.animation.core.animateFloatAsState(
            if (lifted) 1f else 0f, spring(dampingRatio = 1f, stiffness = 600f), label = "lift",
        )
        Column(Modifier.fillMaxWidth()
            .onSizeChanged { heights[key] = it.height }
            .zIndex(if (lifted) 1f else lift)
            .glide(enabled = !lifted)
            .graphicsLayer {
                translationY = if (lifted) dragY else 0f
                scaleX = 1f + .04f * lift; scaleY = 1f + .04f * lift
                shadowElevation = 8f * lift
            }
            .background(K.surfaceRaised.copy(alpha = lift))) {
        Box(Modifier.padding(start = K.gap4).fillMaxWidth().height(.5.dp).background(K.border))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .padding(start = K.gap4, end = K.gap1), verticalAlignment = Alignment.CenterVertically) {
            val can = enabled(item)
            Row(Modifier.weight(1f).heightIn(min = 56.dp)
                .clickable(enabled = can, role = Role.Switch) { onToggle(item) }
                .semantics { toggleableState = if (on(item)) ToggleableState.On else ToggleableState.Off },
                verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = K.type.body)
                KavSwitch(on(item), Modifier.graphicsLayer { alpha = if (can) 1f else .4f })
            }
            Box(Modifier.size(48.dp).semantics {
                contentDescription = T("Reorder $name", "שינוי הסדר של $name")
                customActions = buildList {
                    if (i > 0) add(CustomAccessibilityAction(T("Move up", "הזזה למעלה")) { move(item, -1); true })
                    if (i < order.lastIndex) add(CustomAccessibilityAction(T("Move down", "הזזה למטה")) { move(item, 1); true })
                }
            }.pointerInput(key) {
                try { detectVerticalDragGestures(
                    onDragStart = { held = key; dragY = 0f },
                    onDragEnd = { held = null; dragY = 0f },
                    onDragCancel = { held = null; dragY = 0f },
                    onVerticalDrag = { change, distance ->
                        change.consume(); dragY += distance
                        val next = latest.toMutableList()
                        var at = next.indexOfFirst { id(it) == key }
                        while (at < next.lastIndex && dragY > (heights[id(next[at + 1])] ?: 1) / 2f) {
                            dragY -= (heights[id(next[at + 1])] ?: 1)
                            next.add(at + 1, next.removeAt(at)); at++
                        }
                        while (at > 0 && dragY < -(heights[id(next[at - 1])] ?: 1) / 2f) {
                            dragY += (heights[id(next[at - 1])] ?: 1)
                            next.add(at - 1, next.removeAt(at)); at--
                        }
                        if (next != latest) reorder(next)
                    },
                ) } finally { if (held == key) { held = null; dragY = 0f } }
            }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Menu, null, tint = K.dim, modifier = Modifier.size(22.dp)) }
        }
        }
    } }
}

// The week of scheduled departures kept on the phone: updated daily from GitHub's nightly build, or the rider's own server
// when a choice uses it (Sources.timetableFrom). The last copy stays when updates are off.
@Composable
internal fun TimetableUpdates() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(Prefs.timetableUpdates(ctx)) }
    var mobile by remember { mutableStateOf(Prefs.timetableOnMobile(ctx)) }
    ListGroup(Modifier.padding(top = K.gap6)) {
        row {
            val from = if (KavLive.timetableServer != null) T("From your server", "מהשרת שלכם") else T("From GitHub", "מ-GitHub")
            ListRow(
                T("Timetable updates", "עדכון לוח הזמנים"),
                Modifier.semantics { toggleableState = if (on) ToggleableState.On else ToggleableState.Off },
                subtitle = if (mobile) from else from + T(", on Wi-Fi", ", ב-Wi-Fi"),
                trailing = { KavSwitch(on) }, onClick = { on = !on; Prefs.setTimetableUpdates(ctx, on) }, chevron = false,
                info = T("The week of departures on your phone, for stop boards and planning without signal.",
                    "שבוע היציאות שבטלפון, ללוחות התחנות ולתכנון בלי קליטה."),
            )
            AnimatedVisibility(
                on,
                enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
            ) {
                Column {
                    Separator(K.gap4)
                    ListRow(
                        T("Also on mobile data", "גם בנתונים סלולריים"),
                        Modifier.semantics { toggleableState = if (mobile) ToggleableState.On else ToggleableState.Off },
                        subtitle = T("Up to 6 MB a day", "עד ${megabytes(6)} ביום"),
                        trailing = { KavSwitch(mobile) }, onClick = { mobile = !mobile; Prefs.setTimetableOnMobile(ctx, mobile) }, chevron = false,
                    )
                }
            }
        }
    }
}

// Every choice of Providers is Kav's own (Default).
private fun providersAreKavs(ctx: android.content.Context) =
    listOf(KavLive.source, KavPlan.source, Schedules.source, RideAlerts.source).all { it == Source.KAV } &&
        TripShare.server == KavLive.KAV_SERVER && RideAlerts.language == "auto" && uk.noammm.kav.data.KavCrowd.enabled && !GoogleCrowd.enabled &&
        Prefs.timetableUpdates(ctx) && !Prefs.timetableOnMobile(ctx)

// Who Kav asks for live times, trips, the timetable, line alerts and how busy, and where a shared trip's link is made:
// on the Privacy page, after search. Default is Kav for all of it; Custom opens each choice.
@Composable
internal fun Providers(title: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    InfoHeader(title, T(
        "Who Kav asks for live times, trips, the timetable, line alerts and how full a bus is. By default, Kav's server.",
        "את מי Kav שואלת על זמן אמת, מסלולים, לוח הזמנים, התראות קווים וכמה האוטובוס מלא. כברירת מחדל, את השרת של Kav.",
    ))
    DefaultOrCustom(providersAreKavs(ctx), { providersToKav(ctx, scope) }) {
        LiveSource()
        PlanningSource()
        TimetableSource()
        TimetableUpdates()
        AlertsChoice()
        CrowdChoice()
        ShareServerChoice()
    }
    // With more than one planner to ask (an extension's, or the rider's own server's).
    EveryPlannerChoice()
    StopPhotoChoice()
}

// Kav for every choice of Providers. A server of the rider's own stays set up, unused.
private fun providersToKav(ctx: android.content.Context, scope: kotlinx.coroutines.CoroutineScope) {
    val before = KavLive.timetableServer
    KavLive.source = Source.KAV; Prefs.setLiveSource(ctx, Source.KAV)
    KavPlan.source = Source.KAV; Prefs.setPlanSource(ctx, Source.KAV)
    Schedules.source = Source.KAV; Prefs.setTimetableSource(ctx, Source.KAV)
    RideAlerts.source = Source.KAV; Prefs.setAlertSource(ctx, Source.KAV)
    RideAlerts.language = "auto"; Prefs.setAlertLanguage(ctx, "auto")
    if (TripShare.server != KavLive.KAV_SERVER) { TripShare.picked = Source.KAV; Prefs.setShareServer(ctx, Source.KAV) }
    Prefs.setCrowdSource(ctx, Prefs.CROWD_KAV); uk.noammm.kav.data.KavCrowd.enabled = true; GoogleCrowd.enabled = false
    Prefs.setTimetableUpdates(ctx, true); Prefs.setTimetableOnMobile(ctx, false)
    KavLive.recheck.trySend(Unit)
    val app = ctx.applicationContext
    if (KavLive.timetableServer != before) scope.launch(Dispatchers.IO) { runCatching { uk.noammm.kav.data.Timetable.serverChanged(app) } }
}

// Language and time's summary: Kav's language, the names' languages, the clock.
@Composable
private fun languageSummary(): String {
    fun inLang(he: Boolean) = if (he) T("in Hebrew", "בעברית") else T("in English", "באנגלית")
    val stops = !StopNames.english
    val map = MapFile.labels == Lang.HE
    return listOf(
        T.lang.label,
        if (stops == map) T("stops and map ", "תחנות ומפה ") + inLang(stops)
        else T("stops ", "תחנות ") + inLang(stops) + " · " + T("map ", "מפה ") + inLang(map),
        if (Shown.twelveHour) T("12-hour clock", "שעון 12 שעות") else T("24-hour clock", "שעון 24 שעות"),
    ).joinToString(" · ")
}

// One language for Kav, stop names and the map's names, or Custom, which opens the three apart; then, in Settings, the
// clock, the phone's own until one is picked. Setup's language sheet is the same without the clock, and without the
// language's header, which the sheet's title already says.
@Composable
internal fun LanguageAndTime(ctx: android.content.Context, setup: Boolean = false) {
    // The language being switched to counts as Kav's at once, so a tap on it never shows the three apart on the way.
    val preset = Presets.language(T.wanted ?: T.lang, StopNames.english, MapFile.labels)
    var picked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val custom = Presets.custom(picked, preset != null)
    val langs = Presets.languagesFrom(T.lang)
    if (!setup) Sub(T("Language", "שפה"))
    KavSegmented(
        langs.map { it.label } + T("Custom", "מותאם אישית"),
        selected = if (custom) langs.size else langs.indexOf(preset),
        onSelect = { i ->
            picked = i == langs.size
            langs.getOrNull(i)?.let { l ->
                T.switchTo(l); Prefs.setLang(ctx, l)
                StopNames.english = l == Lang.EN; Prefs.setStopNamesEnglish(ctx, l == Lang.EN)
                MapFile.labels = l; Prefs.setMapLabels(ctx, l)
            }
        },
    )
    CustomRows(custom) {
        Sub(T("Kav's language", "השפה של Kav"))
        LanguageRow(ctx)
        StopNamesRow(ctx)
        Sub(T("Names on the map", "שמות במפה"))
        MapLabelsRow(ctx)
    }
    if (!setup) {
        Sub(T("Time format", "תבנית שעה"))
        ClockChoice(ctx)
    }
}

// Kav's settings or the rider's own: only Custom opens the rows behind it. kavs: the settings are Kav's own now;
// toKavs puts them back.
@Composable
internal fun DefaultOrCustom(kavs: Boolean, toKavs: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var picked by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val custom = Presets.custom(picked, kavs)
    KavSegmented(
        listOf(T("Default", "ברירת מחדל"), T("Custom", "מותאם אישית")), if (custom) 1 else 0,
        { i -> picked = i == 1; if (i == 0) toKavs() }, modifier,
    )
    CustomRows(custom, content)
}

// The rows Custom opens, in and out with the motion every row that depends on another has.
@Composable
private fun CustomRows(open: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        open,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) { Column(content = content) }
}

@Composable
internal fun PlaceSearchChoice() {
    val ctx = LocalContext.current
    InfoHeader(T("Search places with", "חיפוש מקומות עם"), T(
        "Google finds the most places. OpenStreetMap's are mapped by people, and Photon searches them. Either gets what you type.",
        "ל-Google יש הכי הרבה מקומות. המקומות של OpenStreetMap ממופים בידי אנשים, ו-Photon מחפש בהם. שניהם מקבלים את מה שאתם מקלידים.",
    ))
    KavSegmented(
        listOf("Google", "OpenStreetMap"), selected = uk.noammm.kav.data.Search.ALL.indexOf(uk.noammm.kav.data.Search.provider),
        onSelect = { i ->
            val s = uk.noammm.kav.data.Search.ALL[i]
            uk.noammm.kav.data.Search.provider = s; uk.noammm.kav.Prefs.setPlaceSearch(ctx, s.key)
        },
    )
}

// Who searches places, private search and what the search sees instead, in Settings.
@Composable
internal fun PrivacyRows(model: KavModel, onChoose: () -> Unit) {
    val ctx = LocalContext.current
    var priv by remember(model.prefsVersion) { mutableStateOf(Prefs.privateSearch(ctx)) }
    var house by remember(model.prefsVersion) { mutableStateOf(Prefs.houseCheck(ctx)) }
    PlaceSearchChoice()
    ListGroup(Modifier.padding(top = K.gap6)) {
        row {
            ToggleRow(T("Private search", "חיפוש פרטי"), priv, { ShieldGlyph(toggleInk(priv), 18.dp) }, info = T(
                "Your exact location stays off search. A trip still sends its two points.",
                "המיקום המדויק שלכם לא נשלח בחיפוש. מסלול עדיין שולח את שתי הנקודות שלו.",
            )) { on ->
                priv = on; uk.noammm.kav.Prefs.setPrivateSearch(ctx, on); uk.noammm.kav.data.GoogleMaps.shareLocation = !on
            }
        }
        row {
            ToggleRow(T("Exact house numbers", "מספרי בתים מדויקים"), house, {
                Icon(Icons.Outlined.Home, null, tint = toggleInk(house), modifier = Modifier.size(20.dp))
            }, info = T(
                "When you type a street, number and town, Kav also asks GovMap, the Survey of Israel's map, and uses its point if the search's is far off. GovMap gets what you type, never your location.",
                "‏Kav שואלת גם את GovMap, המפה של המרכז למיפוי ישראל, כשאתם מקלידים רחוב, מספר ועיר, ומשתמשת בנקודה שלה אם זו של החיפוש רחוקה. GovMap מקבלת את מה שאתם מקלידים, אף פעם לא את המיקום שלכם.",
            )) { on ->
                house = on; uk.noammm.kav.data.Addresses.checkHouse = on; uk.noammm.kav.Prefs.setHouseCheck(ctx, on)
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        priv,
        enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
            androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
        exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
            androidx.compose.animation.fadeOut(tween(120)),
    ) { SeenChoice(model, onChoose) }
}

// The ink of a row's glyph that's on or off, easing from one to the other as the switch slides.
@Composable
internal fun toggleInk(on: Boolean): Color =
    androidx.compose.animation.animateColorAsState(if (on) K.text else K.dim, tween(200), label = "toggleInk").value

// A row that turns something on or off: tapping anywhere on it flips the switch at its end.
@Composable
internal fun ToggleRow(
    title: String, on: Boolean, glyph: @Composable () -> Unit, subtitle: String? = null,
    subtitleMaxLines: Int = 1, subtitleColor: Color? = null, info: String? = null, onChange: (Boolean) -> Unit,
) = ListRow(
    title, subtitle = subtitle, info = info,
    subtitleMaxLines = subtitleMaxLines, subtitleColor = subtitleColor,
    modifier = Modifier.semantics { toggleableState = if (on) ToggleableState.On else ToggleableState.Off },
    leading = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { glyph() } },
    trailing = { KavSwitch(on) }, onClick = { onChange(!on) }, chevron = false,
)

// A way of getting there, by its mode's own mark.
@Composable
private fun FilterGlyph(f: ResultFilter, on: Boolean) {
    val tint = toggleInk(on)
    when (f) {
        ResultFilter.BUS -> ModeGlyph(Mode.BUS, tint, 18.dp)
        ResultFilter.TRAIN -> ModeGlyph(Mode.TRAIN, tint, 18.dp)
        ResultFilter.LIGHT_RAIL -> ModeGlyph(Mode.TRAM, tint, 18.dp)
        ResultFilter.CARMELIT_RAKAVLIT -> CarmelitMark(18.dp)
        ResultFilter.SHUTTLE -> ShuttleMark(18.dp)
        ResultFilter.TAXI -> ModeGlyph(Mode.TAXI, tint, 18.dp)
        ResultFilter.BIKE -> BikeGlyph(tint)
        ResultFilter.WALK -> WalkGlyph(tint, 16.dp)
    }
}

// What Google's search is told about where you are: one choice of three, what it means under it, and the place when
// it's one.
@Composable
private fun SeenChoice(model: KavModel, onChoose: () -> Unit) {
    val ctx = LocalContext.current
    var city by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(model.seen, model.here) {
        if (model.seen != Seen.CITY) return@LaunchedEffect
        runCatching { uk.noammm.kav.loadNet(ctx) }
        city = withContext(Dispatchers.Default) { model.cityAround(ctx.applicationContext)?.name }
    }
    val seens = listOf(Seen.CITY, Seen.PLACE, Seen.NONE)
    Column {
        val about = when (model.seen) {
            Seen.NONE -> T("Searches carry no location, so results aren't sorted by distance.",
                "החיפוש לא כולל מיקום, ולכן התוצאות לא ממוינות לפי מרחק.")
            Seen.CITY -> T("The center of your town, worked out on your phone. ", "מרכז היישוב שלכם, שמחושב בטלפון. ") +
                (city?.let { T("Now: $it.", "כרגע: $it.") } ?: "")
            Seen.PLACE -> T("A place you pick, so results near it come first.", "מקום שתבחרו, כך שתוצאות קרובות אליו מופיעות ראשונות.")
        }
        // The search's name fades to the other one's when it's switched.
        ChangingText(searchName()) { who -> InfoHeader(T("What $who sees", "מה $who רואה"), about) }
        KavSegmented(
            listOf(T("Your town", "היישוב שלכם"), T("A place", "מקום"), T("Nothing", "כלום")),
            seens.indexOf(model.seen).coerceAtLeast(0),
            { i ->
                val seen = seens[i]
                if (seen == Seen.PLACE && model.seenPlace == null) onChoose() else model.setSeen(ctx, seen)
            },
        )
        val place = model.seenPlace?.name
        androidx.compose.animation.AnimatedVisibility(
            model.seen == Seen.PLACE,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
            ListGroup(Modifier.padding(top = K.gap2)) {
                row {
                    ListRow(
                        place ?: T("Pick a place", "בחירת מקום"),
                        leading = { Icon(Icons.Rounded.Place, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(24.dp)) },
                        onClick = onChoose,
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(ctx: android.content.Context) {
    val langs = Presets.languagesFrom(T.lang)
    KavSegmented(
        langs.map { it.label }, selected = langs.indexOf(T.wanted ?: T.lang),
        onSelect = { i -> val l = langs[i]; T.switchTo(l); Prefs.setLang(ctx, l) },
    )
}

// The language of stops', stations', towns' and lines' ends' names, apart from Kav's own, and nothing else: as the
// Ministry of Transport names them in Hebrew, or in English where it has their English names.
@Composable
internal fun StopNamesRow(ctx: android.content.Context) {
    SectionHeader(T("Stop names", "שמות תחנות"))
    val langs = Presets.languagesFrom(T.lang)
    KavSegmented(
        langs.map { it.label }, selected = langs.indexOf(if (StopNames.english) Lang.EN else Lang.HE),
        onSelect = { i -> val en = langs[i] == Lang.EN; StopNames.english = en; Prefs.setStopNamesEnglish(ctx, en) },
    )
}

// The search picked, by name, for what Settings says it sees.
internal fun searchName() = if (uk.noammm.kav.data.Search.provider == uk.noammm.kav.data.PhotonSearch) "OpenStreetMap" else "Google"

// The language of the map's own names, apart from Kav's: Hebrew as the map has them, or English where it has those.
@Composable
internal fun MapLabelsRow(ctx: android.content.Context) {
    // In the order Kav's languages are listed in.
    val langs = Presets.languagesFrom(T.lang)
    KavSegmented(
        langs.map { it.label }, selected = langs.indexOf(MapFile.labels),
        onSelect = { i -> val l = langs[i]; MapFile.labels = l; Prefs.setMapLabels(ctx, l) },
    )
}

@Composable
private fun BackupSection(model: KavModel) {
    val ctx = LocalContext.current
    fun toast(s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_SHORT).show()

    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(Backup.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            Backup.write(ctx, uri)
                .onSuccess { toast(T("Backup saved", "הגיבוי נשמר")) }
                .onFailure { toast(T("Couldn't write that file", "לא ניתן היה לכתוב את הקובץ")) }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PendingBackup.uri = uri
    }

    InfoHeader(T("Backup", "גיבוי"), T(
            "Your saved places, trips, searches and settings. Importing replaces what is here.",
            "המקומות השמורים, הנסיעות, החיפושים וההגדרות שלכם. ייבוא מחליף את מה שנמצא כאן.",
        ))
    ListGroup {
        row { ListRow(T("Export to a .kav file", "ייצוא לקובץ ‎.kav"), onClick = { save.launch(Backup.suggestedName()) }) }
        row { ListRow(T("Import from a .kav file", "ייבוא מקובץ ‎.kav"), onClick = { open.launch(arrayOf("*/*")) }) }
    }
}

internal fun importError(e: Throwable): String =
    if (e is Backup.NotABackup) T("That file isn't a Kav backup.", "הקובץ הזה אינו גיבוי של Kav.")
    else T("Couldn't read that file.", "לא ניתן היה לקרוא את הקובץ.")

internal const val COFFEE_URL = "https://www.buymeacoffee.com/Noamm"
internal const val REPO_URL = "https://github.com/${Updates.OWNER}/${Updates.REPO}"

internal fun openLink(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

// Google's panorama of a spot, in the Maps app when it's there, else on the web. Only when asked: Google learns the spot.
internal fun openStreetView(ctx: android.content.Context, look: StreetLook) {
    val (app, web) = streetViewLinks(look)
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(app))
        .setPackage("com.google.android.apps.maps").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }.onFailure { openLink(ctx, web) }
}

// The Maps app's link and the web's, facing the way the look faces when it has one (degrees clockwise from north, as
// both take it: the app as cbp's second field). The web link names the spot as its query too, which Google ignores
// there, so a maps app that takes Google's links instead (Vela) opens the spot rather than nothing.
internal fun streetViewLinks(look: StreetLook): Pair<String, String> {
    val (lat, lon) = look.at
    val ll = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)
    return Pair(
        "google.streetview:cbll=$ll" + (look.heading?.let { "&cbp=0,$it,0,0,0" } ?: ""),
        "https://www.google.com/maps/@?api=1&map_action=pano&viewpoint=$ll" + (look.heading?.let { "&heading=$it" } ?: "") + "&query=$ll",
    )
}

@Composable
private fun SpeakerGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        val cone = Path().apply {
            moveTo(w * .12f, h * .38f)
            lineTo(w * .30f, h * .38f)
            lineTo(w * .52f, h * .18f)
            lineTo(w * .52f, h * .82f)
            lineTo(w * .30f, h * .62f)
            lineTo(w * .12f, h * .62f)
            close()
        }
        drawPath(cone, tint, style = Stroke(sw))
        drawArc(tint, -45f, 90f, false, Offset(w * .46f, h * .32f), androidx.compose.ui.geometry.Size(w * .36f, h * .36f),
            style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(tint, -45f, 90f, false, Offset(w * .40f, h * .16f), androidx.compose.ui.geometry.Size(w * .56f, h * .68f),
            style = Stroke(sw, cap = StrokeCap.Round))
    }
}

// A phone's handset: its earpiece, its mouthpiece and the curve between them.
@Composable
private fun CallGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        drawLine(tint, Offset(w * .20f, h * .30f), Offset(w * .36f, h * .14f), w * .18f, StrokeCap.Round)
        drawLine(tint, Offset(w * .70f, h * .86f), Offset(w * .86f, h * .70f), w * .18f, StrokeCap.Round)
        val grip = Path().apply {
            moveTo(w * .24f, h * .26f)
            cubicTo(w * .14f, h * .52f, w * .48f, h * .86f, w * .74f, h * .76f)
        }
        drawPath(grip, tint, style = Stroke(sw * 1.4f, cap = StrokeCap.Round))
    }
}

@Composable
private fun RouteGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        val road = Path().apply {
            moveTo(w * .22f, h * .80f)
            cubicTo(w * .22f, h * .45f, w * .78f, h * .55f, w * .78f, h * .20f)
        }
        drawPath(road, tint, style = Stroke(sw, cap = StrokeCap.Round))
        drawCircle(tint, w * .11f, Offset(w * .22f, h * .80f))
        drawCircle(tint, w * .11f, Offset(w * .78f, h * .20f))
    }
}

@Composable
private fun PhotoGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        drawRoundRect(tint, Offset(w * .08f, h * .16f), androidx.compose.ui.geometry.Size(w * .84f, h * .68f),
            androidx.compose.ui.geometry.CornerRadius(w * .1f), style = Stroke(sw))
        drawCircle(tint, w * .08f, Offset(w * .34f, h * .38f))
        val hill = Path().apply {
            moveTo(w * .14f, h * .78f); lineTo(w * .42f, h * .52f); lineTo(w * .58f, h * .66f); lineTo(w * .68f, h * .56f)
            lineTo(w * .86f, h * .78f)
        }
        drawPath(hill, tint, style = Stroke(sw, cap = StrokeCap.Round))
    }
}

@Composable
private fun ShieldGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        val shield = Path().apply {
            moveTo(w * .5f, h * .08f)
            lineTo(w * .86f, h * .24f)
            lineTo(w * .86f, h * .52f)
            cubicTo(w * .86f, h * .78f, w * .70f, h * .90f, w * .5f, h * .96f)
            cubicTo(w * .30f, h * .90f, w * .14f, h * .78f, w * .14f, h * .52f)
            lineTo(w * .14f, h * .24f)
            close()
        }
        drawPath(shield, tint, style = Stroke(sw))
        drawCircle(tint, w * .085f, Offset(w * .5f, h * .46f))
        drawLine(tint, Offset(w * .5f, h * .46f), Offset(w * .5f, h * .66f), sw, StrokeCap.Round)
    }
}

@Composable
private fun Absent(title: String, desc: String? = null) {
    Row(
        Modifier.padding(horizontal = K.gap4, vertical = K.gap2).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Box(
            Modifier.padding(top = 11.dp).width(14.dp).height(1.dp).background(K.borderStrong),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = K.type.body)
            desc?.let { Text(it, style = K.type.subhead, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

// Whose data and maps Kav shows, in full as their licenses ask, on a sheet of its own behind one row.
@Composable
private fun Credits() {
    var open by remember { mutableStateOf(false) }
    ListGroup(Modifier.padding(top = K.gap6)) { row { ListRow(T("Credits", "קרדיטים"), onClick = { open = true }) } }
    if (open) androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BottomSheet({ open = false }, scrolls = true) { close ->
            SheetHeader(T("Credits", "קרדיטים"), { close { open = false } })
            Absent(
                T("How busy", "עומס"),
                T("The Ministry of Transport's count of Rav-Kav taps, from data.gov.il.", "ספירת תיקופי הרב-קו של משרד התחבורה, מ-data.gov.il."),
            )
            Absent(
                T("Maps", "מפות"),
                T(
                    "Map data © OpenStreetMap contributors, Protomaps. Satellite imagery: Esri, Vantor, Earthstar Geographics. " +
                        "OpenFreeMap maps: OpenFreeMap, © OpenMapTiles, styles after OSM Bright, OSM Liberty, Dark Matter and " +
                        "Fiord Color (MapTiler, OpenMapTiles, Mapbox, CARTO, Maputnik), Noto fonts (SIL Open Font License). " +
                        "Road numbers drawn as the Ministry of Transport's route markers.",
                    "נתוני המפה © תורמי OpenStreetMap, Protomaps. צילומי הלוויין: Esri, Vantor, Earthstar Geographics. " +
                        "מפות OpenFreeMap: ‏OpenFreeMap, © OpenMapTiles, בסגנונות על פי OSM Bright, ‏OSM Liberty, ‏Dark Matter ו-Fiord " +
                        "Color ‏(MapTiler, OpenMapTiles, Mapbox, CARTO, Maputnik), גופני Noto ‏(SIL Open Font License). " +
                        "מספרי הדרכים מצוירים כתמרורי מספר הדרך של משרד התחבורה.",
                ),
            )
            Absent(
                T("Buses", "אוטובוסים"),
                T("The Ministry of Transport's file of the operators' buses, from data.gov.il.",
                    "קובץ ציי האוטובוסים של המפעילים, של משרד התחבורה, מ-data.gov.il."),
            )
        }
    }
}

// 24 or 12 hours: two choices that change every time shown, so a segmented control.
@Composable
internal fun ClockChoice(ctx: android.content.Context, modifier: Modifier = Modifier) {
    KavSegmented(
        listOf(T("24-hour (14:05)", "24 שעות (14:05)"), T("12-hour (2:05 PM)", "12 שעות (${T.ltr("2:05 PM")})")),
        selected = if (Shown.twelveHour) 1 else 0,
        onSelect = { i -> Shown.twelveHour = i == 1; Prefs.setTwelveHour(ctx, i == 1) },
        modifier = modifier,
    )
}
