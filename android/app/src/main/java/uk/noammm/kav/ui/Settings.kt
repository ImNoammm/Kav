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
import uk.noammm.kav.KavModel
import uk.noammm.kav.PendingBackup
import uk.noammm.kav.Pip
import uk.noammm.kav.Prefs
import uk.noammm.kav.Seen
import uk.noammm.kav.data.Backup
import uk.noammm.kav.data.GoogleRecipe
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.MapPacks
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.SignedFile
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.Sources
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.TripShare
import uk.noammm.kav.data.Updates
import androidx.compose.material.icons.rounded.Apartment
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Schedule
import uk.noammm.kav.data.RideAlerts
import uk.noammm.kav.data.RiderProxy
import uk.noammm.kav.data.GoogleCrowd
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Place
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
    androidx.activity.compose.BackHandler(page != null) { page = null }
    val parts = ArrayList<SettingsPart>()
    fun part(id: String, title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
        parts += SettingsPart(id, title, summary, content)
    }
    val waiting = model.update != null && Shown.updatePrompts

    part(
        "live",
        T("Live times and planners", "זמנים בזמן אמת ומתכננים"),
        serverUses(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            LiveSource()
            PlanningSource()
            EveryPlannerChoice()
            AlertsChoice()
            CrowdChoice()
            TimetableSource()
            TimetableUpdates()
            ShareServerChoice()
        }
    }

    // Everything about a trip, from what a plan may show to paying for it.
    val offered = filtersOf(KavPlan.source)
    val ways = offered.count { it in model.filters }
    var spoken by remember { mutableStateOf(Prefs.spokenAlerts(ctx)) }
    var startNow by remember { mutableStateOf(Prefs.beginTripsImmediately(ctx)) }
    var keepQuickPay by remember { mutableStateOf(Prefs.keepQuickPay(ctx)) }
    part(
        "trips",
        T("Trips", "נסיעות"),
        listOfNotNull(
            if (ways == offered.size) T("Every way of getting there", "כל הדרכים להגיע")
            else T("$ways of ${offered.size} ways of getting there", "$ways מתוך ${offered.size} דרכים להגיע"),
            if (spoken) T("alerts said out loud", "התראות בקול") else T("alerts on the screen", "התראות על המסך"),
            when {
                !Shown.payments -> T("paying hidden", "תשלום מוסתר")
                Payer.signedIn -> T("Rav-Pass account", "חשבון רב-פס")
                else -> null
            },
        ).joinToString(" · "),
    ) {
        PlanChoices(model)
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            OnTheWayChoices(spoken, startNow, { spoken = it }, { startNow = it })
            InfoHeader(T("Paying", "תשלום"), T("On Home and on each ride. Off, Kav shows nothing about paying.", "במסך הבית ובכל נסיעה. כשכבוי, Kav לא מציגה שום דבר על תשלום."))
            ListGroup {
                row {
                    ToggleRow(T("Paying in Kav", "תשלום ב-Kav"), Shown.payments, { Canvas(Modifier.size(18.dp)) { drawQr() } }) { on ->
                        Shown.payments = on; Prefs.setShowPayments(ctx, on)
                    }
                }
                // A rider can end the trip in Kav while still on the bus, which closes quick pay with it.
                if (Shown.payments) row {
                    ToggleRow(
                        T("Keep quick pay after a trip ends", "להשאיר תשלום מהיר אחרי סיום הנסיעה"), keepQuickPay,
                        { Icon(Icons.Rounded.Schedule, contentDescription = null, tint = toggleInk(keepQuickPay), modifier = Modifier.size(20.dp)) },
                        subtitle = T("Until you pay or close it", "עד שתשלמו או תסגרו אותו"),
                    ) { on -> keepQuickPay = on; Prefs.setKeepQuickPay(ctx, on) }
                }
            }
        }
        // Folds away on sign out, and opens again on sign in.
        AnimatedVisibility(
            Payer.signedIn,
            enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top),
            exit = fadeOut(tween(150)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top),
        ) {
            Column {
                Spacer(Modifier.height(K.gap2))
                PaymentsSection(model)
            }
        }
    }

    part(
        "privacy",
        T("Privacy and sharing", "פרטיות ושיתוף"),
        listOf(
            searchName().let { who ->
                when {
                    !Prefs.privateSearch(ctx) -> T("$who sees where you are", "$who רואה איפה אתם")
                    model.seen == Seen.CITY -> T("Private search: $who sees your town", "חיפוש פרטי: $who רואה את היישוב שלכם")
                    model.seen == Seen.PLACE -> T("Private search: $who sees a place you picked", "חיפוש פרטי: $who רואה מקום שבחרתם")
                    else -> T("Private search: $who sees no location", "חיפוש פרטי: $who לא רואה מיקום")
                }
            },
            T("Kav links", "קישורי Kav"),
            if (RiderProxy.setting.on) T("through your proxy", "דרך הפרוקסי שלכם") else null,
        ).filterNotNull().joinToString(" · "),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            PrivacyRows(model) { choosing = true }
            StopPhotoChoice()
            ProxyChoice()
            LinkCustomization()
        }
    }

    part(
        "look",
        T("Look and language", "מראה ושפה"),
        listOfNotNull(
            when (K.look) { Look.OLED -> "OLED"; Look.LIGHT -> T("Light", "בהיר"); Look.DARK -> T("Dark", "כהה") },
            if (!liquidGlassReady) null else if (K.liquid) T("liquid glass", "זכוכית נוזלית") else T("solid", "אחיד"),
            T.lang.label,
            if (Shown.twelveHour) T("12-hour clock", "שעון 12 שעות") else T("24-hour clock", "שעון 24 שעות"),
        ).joinToString(" · "),
    ) {
        // In setup's order: the preview, the look and its glass, the colour, the line badges, then the languages.
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            AccentPreview()
            LookChoices { Sub(it) }
            Sub(T("Colour", "צבע"))
            Spacer(Modifier.height(K.gap4))
            AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
            LineBadgeChoice()
            Sub(T("Kav's language", "השפה של Kav"))
            LanguageRow(ctx)
            InfoHeader(
                T("Stop names", "שמות תחנות"),
                T("Only the names of stops, stations and towns. Kav's own language stays as it is.",
                    "רק שמות התחנות, התחנות והיישובים. השפה של Kav נשארת כפי שהיא."),
            )
            StopNamesRow(ctx, explained = false)
            InfoHeader(
                T("Names on the map", "שמות במפה"),
                T("Places and streets on the map. Kav's own language stays as it is.",
                    "מקומות ורחובות על המפה. השפה של Kav נשארת כפי שהיא."),
            )
            MapLabelsRow(ctx)
            Sub(T("Time format", "תבנית שעה"))
            ClockChoice(ctx)
        }
    }

    part("map", T("Map", "מפה"), mapSummary()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { MapChoices() }
    }

    part(
        "kav",
        "Kav",
        T.ltr("Kav ${Updates.installedVersion(ctx)}") +
            (model.update?.let { T(" · ${it.version} is available", " · גרסה ${it.version} זמינה") }
                ?: T(" · no ads, analytics or account", " · בלי פרסומות, אנליטיקה או חשבון")),
    ) {
        Spacer(Modifier.height(K.gap1))
        UpdateSection(model)
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
            UpdatePrompts()
            AutomaticUpdates()
            BackupSection(model)
            FixCheck()
            HelpSection()
        }
        Sub(T("Not in Kav", "מה שאין ב-Kav"))
        Absent(
            T("No account, unless you pay", "אין חשבון, אלא אם משלמים"),
            T(
                "Planning, live times and search need no sign-in, profile or sync. Paying for a ride signs in to " +
                    "your own Rav-Pass account, used for nothing but paying.",
                "תכנון, זמנים בזמן אמת וחיפוש לא דורשים התחברות, פרופיל או סנכרון. תשלום על נסיעה מתחבר " +
                    "לחשבון רב-פס שלכם, שמשמש רק לתשלומים.",
            ),
        )
        Absent(
            T("No adverts", "אין פרסומות"),
            T(
                "Vungle, AdMob and Facebook Audience Network are all absent. Kav calls no ad endpoint, and never " +
                    "requests ad targeting.",
                "Vungle, ‏AdMob ו־Facebook Audience Network: כולם לא נמצאים כאן. Kav לא פונה לאף נקודת קצה פרסומית, " +
                    "ולעולם לא מבקשת מיקוד פרסומי.",
            ),
        )
        Absent(
            T("No analytics or attribution", "אין אנליטיקה או ייחוס"),
            T(
                "Braze (~1,100), AppsFlyer (~890), Adjust, " +
                    "Firebase Crashlytics (443) and Facebook SDK (~700) are all absent.",
                "Braze (כ־1,100), AppsFlyer (כ־890), Adjust, " +
                    "Firebase Crashlytics (443) ו־Facebook SDK (כ־700): כולם לא נמצאים כאן.",
            ),
        )
        Absent(
            T("No support chat", "אין צ'אט תמיכה"),
            T("Zendesk (~1,800 references) is not here either.", "גם Zendesk (כ־1,800 הפניות) לא נמצאת כאן."),
        )
        Absent(
            T("No upsell", "אין מכירה נוספת"),
            T(
                "There is no premium tier to be offered, so nothing in this app " +
                    "has a reason to interrupt you.",
                "אין גרסת פרימיום להציע, ולכן לשום דבר באפליקציה הזו אין סיבה להפריע לכם.",
            ),
        )
        Sub(T("Credits", "קרדיטים"))
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
    }

    Box(Modifier.fillMaxSize()) {
    androidx.compose.animation.AnimatedContent(
        page, Modifier.fillMaxSize().background(K.bg),
        transitionSpec = { if (targetState != null) forward() else backward() }, label = "settingsPage",
    ) { shown ->
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
                        Group(T("Update available", "עדכון זמין"))
                        Column(Modifier.padding(horizontal = K.gap1)) { UpdateSection(model) }
                        Spacer(Modifier.height(K.gap5))
                    }
                }
                Column(Modifier.padding(horizontal = K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap6)) {
                    for (group in SETTINGS_GROUPS) PartGroup(group.mapNotNull { id -> parts.firstOrNull { it.id == id } }) { page = it }
                }
            } else {
                ScreenHeader(open.title, "", back = { page = null })
                Column(Modifier.padding(horizontal = K.gap1), content = open.content)
            }
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

private class SettingsPart(val id: String, val title: String, val summary: String, val content: @Composable ColumnScope.() -> Unit)

// Trips first, then how Kav looks and behaves, then Kav itself.
private val SETTINGS_GROUPS = listOf(
    listOf("trips", "live", "look", "map", "privacy"),
    listOf("kav"),
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
                    Text(p.summary, fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, maxLines = 2,
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

// Kav's GitHub forms, prefilled (Feedback.kt), the code, and the coffee.
@Composable
private fun HelpSection() {
    val ctx = LocalContext.current
    @Composable
    fun link(title: String, subtitle: String, url: () -> String) = ListRow(
        title, subtitle = subtitle, onClick = { openLink(ctx, url()) }, chevron = false,
        trailing = { Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(20.dp)) },
    )
    InfoHeader(T("Help and feedback", "עזרה ומשוב"), T(
            "The forms open with Kav's version and your phone's model filled in, for you to check. Nothing is sent without you.",
            "הטפסים נפתחים כשגרסת Kav ודגם הטלפון כבר ממולאים, כדי שתבדקו. שום דבר לא נשלח בלעדיכם.",
        ))
    ListGroup {
        row { link(T("Report a bug", "דיווח על באג"), T("On GitHub", "ב-GitHub")) { feedbackLink(ctx, bug = true) } }
        row { link(T("Ask for a feature", "בקשה לפיצ'ר"), T("On GitHub", "ב-GitHub")) { feedbackLink(ctx, bug = false) } }
        row { link(T("Kav's code", "הקוד של Kav"), T("And its server's", "ושל השרת שלה")) { REPO_URL } }
        row { link(T("Buy me a coffee", "קנו לי קפה"), T("If you like Kav", "אם Kav עוזרת לכם")) { COFFEE_URL } }
    }
}

@Composable
internal fun LookChoices(inset: Dp = 0.dp, heading: @Composable (String) -> Unit) {
    val ctx = LocalContext.current
    heading(T("Look", "מראה"))
    LookRow(inset)
    if (!liquidGlassReady) return
    InfoHeader(T("Glass", "זכוכית"), T("Liquid glass is the one we recommend.", "זכוכית נוזלית היא האפשרות המומלצת."))
    Row(Modifier.padding(horizontal = inset).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        KavSegmented(
            listOf(T("Liquid glass", "זכוכית נוזלית"), T("Solid", "אחיד")), selected = if (K.liquid) 0 else 1,
            onSelect = { i -> K.restyle { K.liquid = i == 0 }; Prefs.setLiquidGlass(ctx, i == 0) },
        )
    }
}

// Speech turned on where the phone can say nothing: it has no text-to-speech engine, or none with a voice for Kav's
// language.
private fun noVoice(ctx: android.content.Context) = android.widget.Toast.makeText(
    ctx,
    T("This phone has no text-to-speech voice for English. Install one, or pick it in Android's settings under Text-to-speech.",
        "בטלפון הזה אין קול להקראה בעברית. התקינו אחד, או בחרו בו בהגדרות אנדרואיד תחת המרת טקסט לדיבור."),
    android.widget.Toast.LENGTH_LONG,
).show()

// setup: only whether alerts are spoken; the small window and starting at once are Settings' alone.
@Composable
internal fun OnTheWayChoices(
    spoken: Boolean, startNow: Boolean, onSpoken: (Boolean) -> Unit, onStart: (Boolean) -> Unit, setup: Boolean = false,
) {
    val ctx = LocalContext.current
    InfoHeader(T("Alerts", "התראות"), if (setup) T(
        "Your bus arriving and when to get off, and each turn of a walk as you reach it, spoken over whatever is playing in Kav's language.",
        "האוטובוס שלכם מגיע ומתי לרדת, וכל פנייה בהליכה כשאתם מגיעים אליה, בקול מעל מה שמתנגן, בשפה של Kav.",
    ) else T(
        "Your bus arriving and when to get off, and each turn of a walk as you reach it, spoken over whatever is playing in Kav's language; and the current step in a small window when you leave Kav. On silent, they're said only into headphones.",
        "האוטובוס שלכם מגיע ומתי לרדת, וכל פנייה בהליכה כשאתם מגיעים אליה, בקול מעל מה שמתנגן, בשפה של Kav; והצעד הנוכחי בחלון קטן כשיוצאים מ-Kav. במצב שקט הם נאמרים רק באוזניות.",
    ))
    var turns by remember { mutableStateOf(Prefs.spokenTurns(ctx)) }
    ListGroup {
        row {
            ToggleRow(T("Say alerts out loud", "הקראת התראות בקול"), spoken, { SpeakerGlyph(toggleInk(spoken), 18.dp) }) { on ->
                onSpoken(on); Prefs.setSpokenAlerts(ctx, on)
                if (on) uk.noammm.kav.Speaker.say(ctx, T("Alerts will sound like this", "כך יישמעו ההתראות")) { noVoice(ctx) }
            }
        }
        row {
            ToggleRow(T("Say walking directions out loud", "הקראת הוראות הליכה בקול"), turns, { WalkGlyph(toggleInk(turns), 16.dp) }) { on ->
                turns = on; Prefs.setSpokenTurns(ctx, on)
                if (on) uk.noammm.kav.Speaker.say(ctx, T("Turn right onto Herzl", "פנו ימינה להרצל")) { noVoice(ctx) }
            }
        }
        if (!setup) row {
            ToggleRow(T("Small window", "חלון קטן"), Pip.allowed, { PipGlyph(toggleInk(Pip.allowed)) }) { on ->
                Pip.allowed = on; Prefs.setPipNavigation(ctx, on)
            }
        }
    }
    if (setup) return
    InfoHeader(T("Starting a trip", "התחלת נסיעה"), T(
        "A trip you start goes straight to its first walk, as in other navigation apps, instead of waiting for its time or for you to move away.",
        "נסיעה שמתחילים עוברת מיד להליכה הראשונה, כמו באפליקציות ניווט אחרות, במקום לחכות לשעה שלה או לכך שתתרחקו.",
    ))
    ListGroup {
        row {
            ToggleRow(T("Start right away", "להתחיל מיד"), startNow, { RouteGlyph(toggleInk(startNow)) }) { on ->
                onStart(on); Prefs.setBeginTripsImmediately(ctx, on)
            }
        }
    }
}

// How bus lines look: in Look and colour, and the colour step of setting Kav up.
@Composable
internal fun LineBadgeChoice() {
    val ctx = LocalContext.current
    InfoHeader(
        T("Line badges", "סמלי הקווים"),
        T(
            "How bus lines look on boards, plans and trips. Kav: the operator's colour fills the badge. Strip: a grey badge with the operator's colour as a strip under it.",
            "איך קווי האוטובוס נראים בלוחות, במסלולים ובנסיעות. Kav: צבע המפעיל ממלא את הסמל. פס: סמל אפור עם פס בצבע המפעיל מתחתיו.",
        ),
    )
    val badges = listOf(LineBadgeAppearance.KAV, LineBadgeAppearance.STRIP)
    KavSegmented(
        listOf("Kav", T("Strip", "פס")), badges.indexOf(K.lineBadgeAppearance),
        { i -> K.lineBadgeAppearance = badges[i]; Prefs.setLineBadges(ctx, badges[i]) },
    )
    androidx.compose.animation.AnimatedContent(
        K.lineBadgeAppearance, Modifier.fillMaxWidth().padding(top = K.gap3),
        transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(220, delayMillis = 60)) +
                androidx.compose.animation.scaleIn(tween(220, delayMillis = 60), initialScale = .9f)) togetherWith
                androidx.compose.animation.fadeOut(tween(150))
        },
        label = "badgeLook",
    ) { look ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap3, Alignment.CenterHorizontally)) {
            for ((number, operator) in listOf("480" to 3, "69" to 5, "1" to 15)) {
                LinePlateBadge(number, Mode.BUS, operatorPlate(Operators.ofGtfs(operator)), look = look)
            }
        }
    }
}

// Whether Kav tells of new versions on its own: in Updates, under its Check for updates, and the updates step of
// setting Kav up, which has none.
@Composable
internal fun UpdatePrompts(checkAbove: Boolean = true) {
    val ctx = LocalContext.current
    InfoHeader(T("New versions", "גרסאות חדשות"), T(
        "Off, Kav doesn't look for new versions on its own and shows no popup or dot for them, for updating with " +
            "Obtainium or another app." + if (checkAbove) " Check for updates above still works." else "",
        "כשכבוי, Kav לא מחפשת גרסאות חדשות בעצמה ולא מציגה עליהן חלון או נקודה, בשביל עדכון עם Obtainium " +
            "או אפליקציה אחרת." + if (checkAbove) " הכפתור בדקו עדכונים למעלה עדיין עובד." else "",
    ))
    ListGroup {
        row {
            ToggleRow(
                T("Tell me about new versions", "עדכנו אותי על גרסאות חדשות"), Shown.updatePrompts,
                { Icon(Icons.Rounded.NewReleases, null, tint = toggleInk(Shown.updatePrompts), modifier = Modifier.size(22.dp)) },
            ) { on -> Shown.updatePrompts = on; Prefs.setUpdatePrompts(ctx, on) }
        }
    }
}

// Which map Kav draws: its own, kept on the phone; satellite photos under its names, which load from Esri; or one of
// OpenFreeMap's four, loaded from OpenFreeMap until they're downloaded. After setup, the download the chosen map needs
// or may have is right under the choices, then what's on the phone, each pack once with the maps that go with it.
@Composable
internal fun MapChoices(setup: Boolean = false) {
    val ctx = LocalContext.current
    InfoHeader(
        T("Map type", "סוג מפה"),
        T(
            "Standard is Kav's own map, kept on your phone, and works without internet. Satellite photos come from Esri " +
                "while you look, and the OpenFreeMap maps load from OpenFreeMap until you download them, so each sees " +
                "which area you view. GovMap streets and aerial photos load from the Survey of Israel while you look. 3D buildings " +
                "are off at first: if the map stutters or drops frames with them on, turn them off.",
            "המפה הרגילה היא המפה של Kav, שנשמרת בטלפון ועובדת בלי אינטרנט. תמונות הלוויין מגיעות מ-Esri בזמן הצפייה, " +
                "ומפות OpenFreeMap נטענות מ-OpenFreeMap עד שמורידים אותן, ולכן כל אחד מהם רואה איזה אזור אתם צופים בו. מפות הרחובות ותצלומי האוויר של GovMap נטענים מהמרכז למיפוי ישראל בזמן הצפייה. " +
                "הבניינים בתלת-ממד כבויים בהתחלה: אם המפה מגמגמת או מאבדת פריימים כשהם פועלים, כבו אותם.",
        ),
    )
    fun pick(t: MapFile.Type) { MapFile.type = t; Prefs.setMapType(ctx, t) }
    ListGroup {
        row {
            MapTypeChoice(mapName(MapFile.Type.STANDARD), T("Kav's own map, on your phone. No internet needed.",
                "המפה של Kav, בטלפון. בלי צורך באינטרנט."), MapFile.type == MapFile.Type.STANDARD) { pick(MapFile.Type.STANDARD) }
        }
        row {
            MapTypeChoice(mapName(MapFile.Type.SATELLITE), T("Photos from Esri while you look, with the standard map's names.",
                "תצלומים מ-Esri בזמן הצפייה, עם השמות של המפה הרגילה."), MapFile.type == MapFile.Type.SATELLITE) { pick(MapFile.Type.SATELLITE) }
        }
    }
    // Under the standard map once it's picked: its buildings standing up in 3D close in, or flat.
    androidx.compose.animation.AnimatedVisibility(
        MapFile.type == MapFile.Type.STANDARD,
        enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
            androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
        exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
            androidx.compose.animation.fadeOut(tween(120)),
    ) {
        ListGroup(Modifier.padding(top = K.gap2)) {
            row {
                ToggleRow(T("3D buildings", "בניינים בתלת-ממד"), MapFile.buildings3d, { Icon(Icons.Rounded.Apartment, contentDescription = null, tint = toggleInk(MapFile.buildings3d), modifier = Modifier.size(20.dp)) },
                    subtitle = T("Buildings stand up when you tilt the map. Off, the map shows no buildings", "הבניינים מתרוממים כשמטים את המפה. כשזה כבוי, המפה בלי בניינים"),
                    subtitleMaxLines = Int.MAX_VALUE) { on ->
                    MapFile.buildings3d = on; Prefs.setBuildings3d(ctx, on)
                }
            }
        }
    }
    Sub("OpenFreeMap")
    ListGroup {
        for ((t, look) in listOf(
            MapFile.Type.LIBERTY to T("Light, in colour", "בהירה, צבעונית"),
            MapFile.Type.BRIGHT to T("Light", "בהירה"),
            MapFile.Type.DARK to T("Dark grey", "אפורה כהה"),
            MapFile.Type.FIORD to T("Dark blue", "כחולה כהה"),
        )) row { MapTypeChoice(mapName(t), look, MapFile.type == t) { pick(t) } }
    }
    Sub("GovMap")
    ListGroup {
        for ((t, look) in listOf(
            MapFile.Type.GOVMAP to T("The Survey of Israel's streets and buildings, with Hebrew names. Loads while you look.",
                "הרחובות והמבנים של המרכז למיפוי ישראל, עם שמות בעברית. נטענת בזמן הצפייה."),
            MapFile.Type.GOVMAP_PHOTO to T("The Survey of Israel's 2025 aerial photos, with names. Loads while you look.",
                "תצלומי האוויר של המרכז למיפוי ישראל מ-2025, עם שמות. נטענים בזמן הצפייה."),
        )) row { MapTypeChoice(mapName(t), look, MapFile.type == t) { pick(t) } }
    }
    if (!setup) {
        MapDownload()
        MapsOnPhone()
    }
    // The map as it looks with the choice above, around Dizengoff Center; it only shows, so the page scrolls over it.
    // Before Kav's own map is downloaded (setup), it's read from GitHub for this spot alone.
    Box(Modifier.padding(top = K.gap3).fillMaxWidth().height(160.dp).panel(K.rCard)) {
        TileMap(listOf(32.0731 to 34.7712, 32.0781 to 34.7792), Modifier.fillMaxSize(), remote = true)
        Box(Modifier.matchParentSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}

internal fun mapName(t: MapFile.Type) = when (t) {
    MapFile.Type.STANDARD -> T("Standard", "רגילה")
    MapFile.Type.SATELLITE -> T("Satellite", "לוויין")
    MapFile.Type.GOVMAP -> T("GovMap streets", "GovMap רחובות")
    MapFile.Type.GOVMAP_PHOTO -> T("GovMap aerial", "GovMap תצלום אוויר")
    else -> t.name.lowercase().replaceFirstChar { it.uppercase() }
}

// What the map is, where it comes from and the language of its names, for Settings' list.
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
        if (MapFile.labels == Lang.HE) T("names in Hebrew", "שמות בעברית") else T("names in English", "שמות באנגלית"),
    ).joinToString(" · ")
}

// The maps that go with a pack, named for its row and its notes.
private fun mapsOf(pack: MapPack): String = MapFile.typesOf(pack).map { mapName(it) }.let { names ->
    if (names.size < 2) return@let names.joinToString()
    // Hebrew's "and" joins a Latin name with a hyphen.
    val and = T(" and ", if (names.last().first() in 'A'..'z') " ו-" else " ו")
    names.dropLast(1).joinToString(", ") + and + names.last()
}

// The chosen map's download while it isn't on the phone: what it is and its size, its progress, or why it failed.
@Composable
private fun MapDownload() {
    val ctx = LocalContext.current
    val provider = MapFile.provider()
    val pack = provider.pack
    val state = pack.state
    // Kept while the card closes once the map is in, so it doesn't flash back to "isn't on your phone yet".
    var shown by remember { mutableStateOf(state) }
    if (state !is MapPack.State.Ready) shown = state
    AnimatedVisibility(state !is MapPack.State.Ready, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.padding(top = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
            val now = shown
            // The note, the bar and a failure fade into each other while the space eases.
            androidx.compose.animation.AnimatedContent(
                when (now) { is MapPack.State.Downloading -> 1; is MapPack.State.Failed -> 2; else -> 0 },
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(150)))
                        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                },
                label = "mapMissing",
            ) { kind ->
            when (kind) {
                1 -> DownloadBar((shown as? MapPack.State.Downloading)?.progress ?: 1f, T("Downloading the map…", "מורידים את המפה…"))
                2 -> {
                    val why = (shown as? MapPack.State.Failed)?.why.orEmpty()
                    Text(T("Couldn't download the map. $why", "ההורדה של המפה לא הצליחה. $why"), style = K.type.subhead, color = K.critical)
                }
                else -> Text(
                    when {
                        !provider.needsPack -> T(
                            "${mapsOf(pack)} load from OpenFreeMap while you look. Download them to keep all four on your " +
                                "phone instead: about ${pack.bytes shr 20} MB, once.",
                            "${mapsOf(pack)} נטענות מ-OpenFreeMap בזמן הצפייה. אפשר להוריד אותן כדי לשמור את ארבען בטלפון: " +
                                "בערך ${pack.bytes shr 20} MB, פעם אחת.",
                        )
                        MapFile.type == MapFile.Type.SATELLITE -> T(
                            "Satellite's names come from the map on your phone, which isn't here yet: about ${pack.bytes shr 20} MB, once.",
                            "השמות במפת הלוויין מגיעים מהמפה שבטלפון, שעוד לא כאן: בערך ${pack.bytes shr 20} MB, פעם אחת.")
                        else -> T("This map isn't on your phone yet: about ${pack.bytes shr 20} MB, once.",
                            "המפה הזו עוד לא בטלפון: בערך ${pack.bytes shr 20} MB, פעם אחת.")
                    },
                    style = K.type.subhead, color = K.textSecondary,
                )
            }
            }
            // Folds away while it downloads, and comes back as Try again if it fails.
            AnimatedVisibility(
                now !is MapPack.State.Downloading,
                enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top),
                exit = fadeOut(tween(150)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top),
            ) {
                KavButton(
                    if (now is MapPack.State.Failed) T("Try again", "נסו שוב") else T("Download", "הורדה"),
                    { pack.download(ctx.applicationContext.filesDir) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download,
                )
            }
        }
    }
}

// What's on the phone: each pack once, with its size and the maps that go with it, and a way to delete it. A pack the
// maps can't draw without goes back to waiting for its download; OpenFreeMap's maps go back to loading online, and the
// row says so before it goes.
@Composable
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
private fun MapsOnPhone() {
    val ctx = LocalContext.current
    val files = ctx.applicationContext.filesDir
    val sizes = MapPacks.all.map { pack ->
        produceState(0L, pack.state) { value = withContext(Dispatchers.IO) { pack.used(files) } }.value
    }
    val any = sizes.any { it > 0 }
    AnimatedVisibility(any, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.padding(top = K.gap3)) {
            Sub(T("On your phone", "בטלפון"))
            Column(Modifier.fillMaxWidth().panel(K.rCard)) {
                MapPacks.all.forEachIndexed { i, pack ->
                    AnimatedVisibility(sizes[i] > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column {
                            if (MapPacks.all.take(i).indices.any { sizes[it] > 0 }) Separator()
                            MapPackRow(pack, sizes[i])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPackRow(pack: MapPack, used: Long) {
    val ctx = LocalContext.current
    val state = pack.state
    var asking by remember(pack) { mutableStateOf(false) }
    if (state is MapPack.State.Downloading) asking = false
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Satellite only takes the standard map's names and roads from it; its photos are never on the phone.
                val standard = pack === MapPacks.STANDARD
                Text(if (standard) T("Standard map", "המפה הרגילה") else mapsOf(pack), fontSize = 16.sp, color = K.text)
                LiveText(
                    when (state) {
                        is MapPack.State.Downloading -> T("Downloading… ${(state.progress * 100).toInt()}%", "מורידים… ${(state.progress * 100).toInt()}%")
                        is MapPack.State.Ready -> T("${used shr 20} MB", "${used shr 20} MB") + if (standard) T(
                            " · Streets and names available offline",
                            " · רחובות ושמות זמינים ללא אינטרנט",
                        ) else ""
                        else -> T("${used shr 20} of ${pack.bytes shr 20} MB, not finished", "${used shr 20} מתוך ${pack.bytes shr 20} MB, לא הסתיים")
                    },
                    style = K.type.subhead, color = K.dim,
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                state !is MapPack.State.Downloading && !asking,
                enter = fadeIn(tween(220, delayMillis = 60)), exit = fadeOut(tween(150)),
            ) {
                KavButton(T("Delete", "מחיקה"), { asking = true }, style = ButtonStyle.Plain)
            }
        }
        // The question opens under the row, saying what happens to the maps that go with the pack.
        AnimatedVisibility(
            asking,
            enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top),
            exit = fadeOut(tween(150)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                Text(
                    if (MapFile.typesOf(pack).any { MapFile.provider(it).needsPack }) T(
                        "${mapsOf(pack)} then wait for this map to be downloaded again before they show anything.",
                        "${mapsOf(pack)} יחכו אז עד שהמפה הזו תורד שוב לפני שיציגו משהו.",
                    ) else T(
                        "${mapsOf(pack)} then load from OpenFreeMap while you look, so OpenFreeMap sees which area you view.",
                        "${mapsOf(pack)} ייטענו אז מ-OpenFreeMap בזמן הצפייה, ולכן OpenFreeMap יראה איזה אזור אתם צופים בו.",
                    ),
                    style = K.type.subhead, color = K.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                    KavButton(T("Keep", "השארה"), { asking = false }, Modifier.weight(1f), style = ButtonStyle.Secondary)
                    KavButton(T("Delete ${used shr 20} MB", "מחיקת ${used shr 20} MB"), {
                        asking = false
                        pack.remove(ctx.applicationContext.filesDir)
                    }, Modifier.weight(1f), style = ButtonStyle.Destructive)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
        InfoHeader(
            T("What a plan may show", "מה מסלול יכול לכלול"),
            offered.joinToString("\n") { f -> f.label + ": " + f.desc },
        )
        ListGroup {
            offered.forEach { f ->
                row {
                    val on = f in model.filters
                    ToggleRow(f.label, on, { FilterGlyph(f, on) }) { model.setFilter(ctx, f, it) }
                }
            }
        }
        InfoHeader(T("What a card shows", "מה מוצג בכרטיס"), T(
            "The CO2e of every plan and trip, from every planner: Kav works it out from each ride's distance, by bus, train, light rail or taxi, with the UK government's figures per passenger.",
            "ה-CO2e של כל מסלול ונסיעה, מכל מתכנן: Kav מחשבת אותו לפי המרחק של כל נסיעה, באוטובוס, ברכבת, ברכבת קלה או במונית, לפי הנתונים של ממשלת בריטניה לכל נוסע.",
        ))
        ListGroup {
            row { FareProfileChoice() }
            row {
                ToggleRow(T("Wheelchair accessibility", "נגישות לכיסא גלגלים"), Shown.accessibility,
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
    }
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

@Composable
private fun FareProfileChoice() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    ListRow(T("Fare profile", "פרופיל הנחה"), subtitle = fareProfileName(Shown.fareProfile),
        subtitleMaxLines = 2, onClick = { open = true })
    if (open) androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
    BottomSheet({ open = false }, scrolls = true) { close ->
        SheetHeader(T("Fare profile", "פרופיל הנחה"), { close { open = false } })
        NoteCard(T(
            "Changes single-ride estimates only. Monthly caps and passes are not included. Payments use your approved Rav-Pass profile.",
            "משנה רק את הערכת המחיר של נסיעות בודדות. תקרות חודשיות ומנויים אינם נכללים. התשלום משתמש בפרופיל המאושר ברב-פס.",
        ))
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
                Shown.fareProfile = profile; Prefs.setFareProfile(ctx, profile)
                close { open = false }
            }, chevron = false,
        )
    }
    }
}

// Detours and other service alerts on the rides, whichever planner plans, from the source picked (RideAlerts): Kav's
// server or the rider's own, or Google Maps asked from the phone.
@Composable
internal fun AlertsChoice() {
    val ctx = LocalContext.current
    InfoHeader(T("Detours and alerts", "עיקופים והתראות"), T(
        "Detours, delays and moved stops on your lines, whichever planner you use: on results, trips and navigation. Kav in English and Kav in Hebrew each keep their own source: Kav's server, which also translates the notices into English, until you pick another.",
        "עיקופים, עיכובים ותחנות שהוזזו בקווים שלכם, בכל מתכנן: בתוצאות, בנסיעות ובניווט. ל-Kav באנגלית ול-Kav בעברית יש מקור משלהן: השרת של Kav, שגם מתרגם את ההודעות לאנגלית, עד שתבחרו אחר.",
    ))
    ListGroup {
        row {
            ToggleRow(
                T("Service alerts", "הודעות שירות"), RideAlerts.enabled,
                { Icon(Icons.Rounded.Campaign, null, tint = toggleInk(RideAlerts.enabled), modifier = Modifier.size(22.dp)) },
            ) { on -> RideAlerts.enabled = on; Prefs.setRideAlerts(ctx, on) }
        }
    }
    AnimatedVisibility(
        RideAlerts.enabled,
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) {
        Column {
            AlertsSource()
            // Only a Kav server translates notices, so the choice of their language is offered with one.
            AnimatedVisibility(
                RideAlerts.translates(),
                enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
                exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
            ) {
                Column {
                    InfoHeader(T("Notices in", "ההודעות ב"), T(
                        "The operators write their notices in Hebrew. In English, Kav's server translates them, or your own server when alerts come from it: only the notice's text goes, nothing of your trip. Line numbers, stop codes and times stay as written.",
                        "המפעילים כותבים את ההודעות בעברית. באנגלית, השרת של Kav מתרגם אותן, או השרת שלכם כשההתראות מגיעות ממנו: רק נוסח ההודעה נשלח, שום דבר על הנסיעה. מספרי קווים, קודי תחנות ושעות נשארים כפי שנכתבו.",
                    ))
                    KavSegmented(
                        listOf(T("Automatic", "אוטומטי"), "English", "עברית"), Prefs.ALERT_LANGUAGES.indexOf(RideAlerts.language),
                        { i -> RideAlerts.language = Prefs.ALERT_LANGUAGES[i]; Prefs.setAlertLanguage(ctx, Prefs.ALERT_LANGUAGES[i]) },
                    )
                }
            }
        }
    }
}

// How busy each ride usually is, from Google Maps' page for its boarding stop (GoogleCrowd).
@Composable
internal fun CrowdChoice() {
    val ctx = LocalContext.current
    InfoHeader(T("How busy", "עומס"), T(
        "How busy the next bus or train usually is at that hour, as Google Maps predicts it, shown on trips, in navigation and on a line's page. Kav asks Google from your phone about the stop you board at while one of these is open, at most every ten minutes: Google sees the stop and your IP address.",
        "כמה עמוס בדרך כלל האוטובוס או הרכבת הבאים בשעה הזו, לפי התחזית של Google Maps, בנסיעות, בניווט ובדף של קו. Kav שואלת את Google מהטלפון על תחנת העלייה כשאחד מהם פתוח, לכל היותר כל עשר דקות: Google רואה את התחנה ואת כתובת ה-IP שלכם.",
    ))
    ListGroup {
        row {
            ToggleRow(
                T("How busy, from Google", "עומס, מ-Google"), GoogleCrowd.enabled,
                { Icon(Icons.Rounded.Groups, null, tint = toggleInk(GoogleCrowd.enabled), modifier = Modifier.size(22.dp)) },
            ) { on -> GoogleCrowd.enabled = on; Prefs.setGoogleCrowd(ctx, on) }
        }
    }
}

@Composable
private fun AlertsSource() {
    val ctx = LocalContext.current
    SourceChoice(
        T("Alerts from", "הודעות מ"),
        { s ->
            when (s) {
                Source.GOOGLE -> T(
                    "Google Maps, asked from your phone about each ride of a trip you open, at most every five minutes: Google sees the stops you board and leave each ride at, and your IP address. Only an alert that names your line is shown. The operators write them in Hebrew, and Google cuts them at 1000 characters. Not shown on the list of results.",
                    "Google Maps, נשאלת מהטלפון על כל נסיעה בקו במסלול שפתחתם, לכל היותר כל חמש דקות: Google רואה את התחנות שבהן אתם עולים ויורדים בכל קו, ואת כתובת ה-IP שלכם. מוצגת רק התראה שמזכירה את הקו שלכם. המפעילים כותבים אותן בעברית, ו-Google קוטעת אותן אחרי 1000 תווים. לא מוצג ברשימת התוצאות.",
                )
                Source.KAV -> T(
                    "Kav's server sends Israel's whole list with every notice in it, every ten minutes while results or a trip are open. It sees your IP address but not your trip: your lines are found in the list on your phone. Notices come in English too, translated on Kav's server.",
                    "השרת של Kav שולח את כל הרשימה של ישראל עם כל ההודעות שבה, כל עשר דקות כשתוצאות או נסיעה פתוחות. הוא רואה את כתובת ה-IP שלכם אבל לא את הנסיעה: הקווים שלכם נמצאים ברשימה בטלפון.",
                )
                Source.OWN -> T(
                    "Your own copy of Kav's server sends the whole list the same way, from the GTFS-Realtime alerts feed it's set up with, such as the Ministry of Transport's.",
                    "עותק משלכם של השרת של Kav שולח את כל הרשימה באותה דרך, מפיד התראות ה-GTFS-Realtime שהוגדר בו, כמו זה של משרד התחבורה.",
                )
                Source.BUS_NEARBY, Source.EGG -> ""
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
            c == null -> K.textTertiary to T("Checking the connection", "בודקים את החיבור")
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
    InfoHeader(T("Photos", "תמונות"), T(
        "A stop's photo is Google's Street View facing it. A place's is the photo Google Maps shows first when you open " +
            "the place; a place with none, like most addresses, keeps its pin. Each is fetched when the stop or place is " +
            "shown, and kept on your phone: Google then sees that stop, or the place's name and location, and your IP " +
            "address. Off, Google is asked only when you open Street View.",
        "התמונה של תחנה היא Street View של Google שפונה אליה. התמונה של מקום היא זו ש-Google Maps מציגה ראשונה " +
            "כשפותחים את המקום; מקום בלי תמונה, כמו רוב הכתובות, נשאר עם הסיכה. כל אחת נטענת כשהתחנה או המקום " +
            "מוצגים, ונשמרת בטלפון: Google רואה אז את התחנה, או את השם והמיקום של המקום, ואת כתובת ה-IP שלכם. כשכבוי, " +
            "Google נשאלת רק כשפותחים Street View.",
    ))
    ListGroup {
        row {
            ToggleRow(T("Photos of stops and places", "תמונות של תחנות ומקומות"), StopPhotos.enabled, { PhotoGlyph(toggleInk(StopPhotos.enabled)) }) { on ->
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
        "Send everything Kav asks for through a proxy of your own: Kav's server, Google, the planners, the maps and the " +
            "pages Kav shows then see the proxy's address instead of yours. If the proxy can't be reached, nothing loads " +
            "until it's back or turned off. Pages shown in Kav (Street View, Rav-Pass, the report form) can log in to an " +
            "HTTP proxy but not to a SOCKS5 one. A connection Kav already has open can carry on as it was for a few " +
            "minutes; close and reopen Kav for everything to switch at once.",
        "שליחת כל מה ש-Kav מבקשת דרך פרוקסי משלכם: השרת של Kav, Google, המתכננים, המפות והעמודים ש-Kav מציגה רואים " +
            "אז את הכתובת של הפרוקסי במקום שלכם. אם אי אפשר להגיע לפרוקסי, שום דבר לא נטען עד שהוא חוזר או נכבה. עמודים " +
            "שמוצגים ב-Kav (Street View, רב-פס, טופס הדיווח) יכולים להתחבר לפרוקסי HTTP אבל לא לפרוקסי SOCKS5. " +
            "חיבור ש-Kav כבר פתחה יכול להמשיך כמו שהיה עוד כמה דקות; סגרו ופתחו את Kav כדי שהכול יעבור מיד.",
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
                    trying -> T("Trying…", "בודקים…") to K.textSecondary
                    tried == "" -> T("Works: Kav goes through it", "עובד: Kav עוברת דרכו") to K.realtime
                    tried != null -> T("Couldn't connect: $tried", "אין חיבור: $tried") to K.problem
                    draft != saved -> T("Not saved yet", "עוד לא נשמר") to K.textSecondary
                    else -> null
                }
                Text(said?.first.orEmpty(), style = K.type.subhead, color = said?.second ?: K.textSecondary, modifier = Modifier.weight(1f))
                KavButton(T("Save and try", "שמירה ובדיקה"), { save(draft) }, enabled = draft.on && !trying, style = ButtonStyle.Secondary)
            }
            if (!RiderProxy.pagesFollow) Text(
                T("This phone's WebView can't use a proxy, so pages shown in Kav still connect directly.",
                    "ה-WebView בטלפון הזה לא יכול להשתמש בפרוקסי, ולכן עמודים שמוצגים ב-Kav עדיין מתחברים ישירות."),
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
            if (s.hopOn) T(
                "${sourceName(s)} supplies arrivals and vehicle positions. The map shows vehicles serving nearby stops, not every vehicle in the area. Requests go directly to HopOn, which sees your IP address and the requested area or stops.",
                "${sourceName(s)} מספק זמני הגעה ומיקומי כלי רכב. המפה מציגה כלי רכב המשרתים תחנות סמוכות, ולא את כל כלי הרכב באזור. הבקשות נשלחות ישירות להופאון, שרואה את כתובת ה-IP ואת האזור או התחנות המבוקשים.",
            ) else when (s) {
                Source.BUS_NEARBY, Source.EGG, Source.GOOGLE -> ""
                Source.KAV -> T("Buses and trains straight from the Ministry of Transport, with Kav's own arrival times. ",
                    "אוטובוסים ורכבות ישירות ממשרד התחבורה, עם זמני ההגעה של Kav. ")
                Source.OWN -> T("Your own copy of Kav's server, from server/selfhost. As set now, ",
                    "עותק משלכם של השרת של Kav, מ-server/selfhost. כפי שמוגדר עכשיו, ") + kavStill() + " "
            } + if (s.hopOn) "" else T(
                "A server sees only the lines and area you look at, never your IP address, and keeps no logs.",
                "השרת רואה רק את הקווים והאזור שאתם צופים בהם, אף פעם לא את כתובת ה-IP שלכם, ולא שומר יומנים.",
            )
        },
        Source.LIVE, KavLive.source, { s -> KavLive.source = s; Prefs.setLiveSource(ctx, s) },
        // Own picked but not set up yet: its address and key come first, Kav's server answers until then.
        server = KavLive.server.takeUnless { KavLive.source == Source.OWN && KavLive.ownServer.isEmpty() },
        fields = true,
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to T("Checking the connection", "בודקים את החיבור")
            answers?.live == true && KavLive.source.hopOn -> K.realtime to T("Connected · nearby-stop coverage", "מחובר · כיסוי לפי תחנות סמוכות")
            answers?.live == true -> K.realtime to T(
                "Connected · ${"%,d".format(answers.vehicles)} buses live",
                "מחובר · ${"%,d".format(answers.vehicles)} אוטובוסים בזמן אמת",
            )
            answers != null -> K.problem to T("Connected, but no live buses yet", "מחובר, אבל עוד אין אוטובוסים בזמן אמת")
            else -> K.problem to if (KavLive.source.hopOn) T("Provider unavailable. Try again later.", "הספק לא זמין. נסו שוב מאוחר יותר.")
                else T("Not reachable. Tap for details", "לא זמין. הקישו לפרטים")
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
                Source.BUS_NEARBY, Source.EGG -> T(
                    "${sourceName(s)} plans trips through HopOn. The provider receives the trip's points, time and your IP address. Last-trip searches arrive by 02:30, as in its app. Taxi cards are available with Kav's planner.",
                    "${sourceName(s)} מתכנן מסלולים דרך הופאון. הספק מקבל את נקודות המסלול, השעה וכתובת ה-IP. חיפוש נסיעה אחרונה הוא להגעה עד 02:30, כמו ביישומון שלו. כרטיסי מוניות זמינים בתכנון של Kav.",
                )
                Source.KAV -> T(
                    "Kav's planner works out the trips you search for on Kav's server, on the ministry's timetable. It sees each trip's two points and time, never your IP address, and keeps no logs.",
                    "המתכנן של Kav מחשב את המסלולים שאתם מחפשים בשרת של Kav, לפי לוח הזמנים של משרד התחבורה. הוא רואה את שתי הנקודות והשעה של כל מסלול, אף פעם לא את כתובת ה-IP שלכם, ולא שומר יומנים.",
                )
                Source.OWN -> T("Your own copy of Kav's server works out the trips you search for, with its planner on. As set now, ",
                    "עותק משלכם של השרת של Kav מחשב את המסלולים שאתם מחפשים, עם המתכנן שלו. כפי שמוגדר עכשיו, ") + kavStill()
            } + T(
                " The trips it finds get their live times from the choice above.",
                " המסלולים שהוא מוצא מקבלים את הזמנים בזמן אמת מהבחירה שלמעלה.",
            )
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
            KavPlan.source == Source.OWN && KavLive.ownServer.isEmpty() ->
                if (KavLive.source == Source.OWN) T("Set up your server above, under live locations. Until then, Kav's server plans trips.",
                    "הגדירו את השרת שלכם למעלה, תחת מיקומים בזמן אמת. עד אז, השרת של Kav מתכנן מסלולים.")
                else T("Until your server is set up, Kav's server plans trips.", "עד שהשרת שלכם מוגדר, השרת של Kav מתכנן מסלולים.")
            !KavPlan.offered && KavPlan.source == Source.OWN ->
                T("Your server doesn't plan trips yet. Turn its planner on, or pick Kav's server.",
                    "השרת שלכם עוד לא מתכנן מסלולים. הפעילו את המתכנן שלו, או בחרו בשרת של Kav.")
            else -> null
        },
    ) { c ->
        val answers = c as? KavLive.Check.Answers
        when {
            c == null -> K.textTertiary to T("Checking the connection", "בודקים את החיבור")
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
                Source.BUS_NEARBY, Source.EGG -> T(
                    "${sourceName(s)} gives the Lines tab's lines, each line's stops and times, and each stop's departures, asked through HopOn as you open them. HopOn sees the lines and stops you open, and your IP address.",
                    "${sourceName(s)} נותן את רשימת הקווים, את התחנות והזמנים של כל קו ואת היציאות מכל תחנה, דרך הופאון, כשפותחים אותם. הופאון רואה את הקווים והתחנות שאתם פותחים ואת כתובת ה-IP שלכם.",
                )
                Source.KAV -> T(
                    "The week of scheduled departures on your phone, built each night on GitHub from the Ministry of Transport's timetable. Lines and stop boards open without signal and ask no one.",
                    "שבוע היציאות המתוכננות שבטלפון, שנבנה כל לילה ב-GitHub מלוח הזמנים של משרד התחבורה. קווים ולוחות תחנות נפתחים בלי קליטה ולא שואלים אף אחד.",
                )
                Source.OWN -> T(
                    "The week your own copy of Kav's server builds each night, kept on your phone. As set now, ",
                    "השבוע שעותק משלכם של השרת של Kav בונה כל לילה, שמור בטלפון. כפי שמוגדר עכשיו, ",
                ) + kavStill()
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
            c == null -> K.textTertiary to T("Checking the connection", "בודקים את החיבור")
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
    if (parts.isEmpty()) return T(
        "nothing goes to Kav's server, other than opening a link made there.",
        "שום דבר לא נשלח לשרת של Kav, חוץ מפתיחת קישור שנוצר בו.",
    )
    return T("still from Kav's server: ", "עדיין מהשרת של Kav: ") + parts.joinToString(", ") {
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
                "Where a Kav link you share keeps the trip until it expires: Kav's server or your own. Whoever opens the link reads the trip from there. Until you pick, links are made on your server while live times or trips come from it.",
                "המקום שבו קישור Kav ששיתפתם שומר את הנסיעה עד שתוקפו פג: השרת של Kav או השרת שלכם. מי שפותח את הקישור קורא את הנסיעה משם. עד שתבחרו, קישורים נוצרים בשרת שלכם כשהזמנים בזמן אמת או המסלולים מגיעים ממנו.",
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
    Source.BUS_NEARBY -> T("Bus Nearby", "אוטובוס קרוב")
    Source.EGG -> "EGG"
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
        val c = withContext(Dispatchers.IO) { KavLive.check(at, KavLive.keyFor(at), source) }
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
    Column(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
    InfoHeader(title, about(source))
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
        // Kav's own, each choice's first, is the one Kav recommends.
        if (order.firstOrNull() == Source.KAV) Text(
            T("Recommended: Kav", "מומלץ: Kav"), style = K.type.caption,
            modifier = Modifier.padding(start = K.gap3, top = K.gap1),
        )
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
    val (dot, line) = status(inUse)
    val title = if (source.provider) sourceName(source) else if (server != KavLive.KAV_SERVER) T.ltr(server.orEmpty().removePrefix("https://")) else T("Kav's server", "השרת של Kav")
    // The card comes and goes with a choice that asks a server, and what it says (another provider, its check coming
    // back) fades from one to the next.
    AnimatedVisibility(
        !settingUp || showFields,
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
                    leading = { Box(Modifier.size(10.dp).clip(CircleShape).background(shade)) },
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
                if (scanning) BottomSheet({ scanning = false }) { close ->
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
                }
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

// What a shared trip's link is.
@Composable
private fun ShareLinks() {
    // Made where the rider picked (TripShare.server).
    val on = if (TripShare.server != KavLive.KAV_SERVER) T("your server", "שרת שלכם") else T("Kav's server", "שרת של Kav")
    InfoHeader(
        T("Trips you share", "נסיעות שאתם משתפים"),
        T("A Kav link keeps the trip you picked, with its stops and map, on $on until it expires. A Bus Nearby or EGG trip also goes to HopOn for their app links.",
            "קישור Kav שומר את הנסיעה שבחרתם, עם התחנות והמפה, ב$on עד שתוקפו פג. נסיעה של אוטובוס קרוב או EGG נשלחת גם ל-HopOn ליצירת קישורי האפליקציות שלהם."),
    )
}

@Composable
private fun LinkCustomization() {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(Prefs.linkStyle(ctx)) }
    fun save(next: uk.noammm.kav.data.ShareStyle) { style = next; Prefs.setLinkStyle(ctx, next) }
    ShareLinks()
    Column {
    Sub(T("Link expires after", "תוקף הקישור"))
    val expiryDays = listOf(3, 7, 30)
    KavSegmented(expiryDays.map { T("$it days", "$it ימים") }, expiryDays.indexOf(style.expiryDays),
        { save(style.copy(expiryDays = expiryDays[it])) })
    // Matching Kav is Automatic for all three; turned off, each starts from what Kav shows now.
    val matching = style.language == "auto" && style.stopLanguage == "auto" && style.theme == "auto"
    val kavLanguage = if (T.lang == Lang.HE) "he" else "en"
    val kavStops = if (StopNames.english) "en" else "he"
    val kavTheme = K.look.name.lowercase()
    InfoHeader(T("The page", "העמוד"), T("Changes apply to links you share from now on.", "השינויים חלים על קישורים שתשתפו מעכשיו."))
    ListGroup {
        row {
            ToggleRow(T("Match my Kav settings", "כמו ההגדרות שלי ב-Kav"), matching,
                { Icon(Icons.Rounded.Sync, null, tint = toggleInk(matching), modifier = Modifier.size(20.dp)) }) { on ->
                save(if (on) style.copy(language = "auto", stopLanguage = "auto", theme = "auto")
                else style.copy(language = kavLanguage, stopLanguage = kavStops, theme = kavTheme))
            }
        }
    }
    AnimatedVisibility(!matching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column {
            val languages = listOf("en", "he")
            Sub(T("Language", "שפה"))
            KavSegmented(listOf("English", "עברית"), languages.indexOf(style.language.takeUnless { it == "auto" } ?: kavLanguage),
                { save(style.copy(language = languages[it])) })
            Sub(T("Stop names", "שמות תחנות"))
            KavSegmented(listOf("English", "עברית"), languages.indexOf(style.stopLanguage.takeUnless { it == "auto" } ?: kavStops),
                { save(style.copy(stopLanguage = languages[it])) })
            Sub(T("Colours", "צבעים"))
            val themes = listOf("oled", "light", "dark")
            KavSegmented(listOf("OLED", T("Light", "בהיר"), T("Dark", "כהה")), themes.indexOf(style.theme.takeUnless { it == "auto" } ?: kavTheme),
                { save(style.copy(theme = themes[it])) })
        }
    }
    Sub(T("On the page", "בעמוד"))
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        // Laid out as the app rows under it, with the handle's room left empty, so the names and switches line up; the names
        // start where the section's header and Settings' other rows do.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = K.gap4, end = K.gap1), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp)
                .clickable(role = Role.Switch) { save(style.copy(map = !style.map)) }
                .semantics { toggleableState = if (style.map) ToggleableState.On else ToggleableState.Off },
                verticalAlignment = Alignment.CenterVertically) {
                Text(T("Map preview", "תצוגה מקדימה של המפה"), Modifier.weight(1f), style = K.type.body)
                KavSwitch(style.map)
            }
            Spacer(Modifier.size(48.dp))
        }
        SwitchOrderRows(
            style.order, { it },
            { id -> when (id) { "egg" -> "EGG"; "bnb" -> T("Bus Nearby", "אוטובוס קרוב"); else -> "Kav" } },
            on = { it in style.apps },
            onToggle = { id -> save(style.copy(apps = if (id in style.apps) style.apps - id else style.apps + id)) },
            onOrder = { save(style.copy(order = it)) },
        )
    }
    }
}

// Rows that each turn something on or off and can be dragged into another order by their handle (or moved up and down
// by TalkBack's actions): the share page's apps and the planners' tabs. A held row lifts and follows the finger, and the
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
    InfoHeader(T("Timetable on your phone", "לוח הזמנים בטלפון"), T(
        "The week of scheduled departures Kav keeps on your phone, for stop boards and planning without signal, built each night on GitHub from the Ministry of Transport's timetable. Off, Kav keeps the last copy, which can go out of date. On mobile data, it updates only once the copy is three days old, unless you turn on Also on mobile data.",
        "שבוע היציאות המתוכננות ש-Kav שומרת בטלפון, ללוחות התחנות ולתכנון בלי קליטה, שנבנה כל לילה ב-GitHub מלוח הזמנים של משרד התחבורה. כשכבוי, Kav שומרת את העותק האחרון, שעלול להתיישן. בנתונים סלולריים הוא מתעדכן רק כשהעותק בן שלושה ימים, אלא אם תפעילו את גם בנתונים סלולריים.",
    ))
    ListGroup(Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        row {
            val from = if (KavLive.timetableServer != null) T("From your server", "מהשרת שלכם") else T("From GitHub", "מ-GitHub")
            ListRow(
                T("Update it every day", "לעדכן אותו כל יום"),
                Modifier.semantics { toggleableState = if (on) ToggleableState.On else ToggleableState.Off },
                subtitle = if (mobile) from else from + T(", on Wi-Fi", ", ב-Wi-Fi"),
                trailing = { KavSwitch(on) }, onClick = { on = !on; Prefs.setTimetableUpdates(ctx, on) }, chevron = false,
            )
        }
        if (on) row {
            ListRow(
                T("Also on mobile data", "גם בנתונים סלולריים"),
                Modifier.semantics { toggleableState = if (mobile) ToggleableState.On else ToggleableState.Off },
                subtitle = T("Up to 6 MB a day", "עד 6MB ביום"),
                trailing = { KavSwitch(mobile) }, onClick = { mobile = !mobile; Prefs.setTimetableOnMobile(ctx, mobile) }, chevron = false,
            )
        }
    }
}

// The section's summary line: where each part comes from.
@Composable
private fun serverUses(): String {
    val ctx = LocalContext.current
    fun named(at: String?) = when (at) {
        null -> T("unavailable", "לא זמין")
        KavLive.KAV_SERVER -> T("Kav's server", "השרת של Kav")
        else -> T("your server", "השרת שלכם")
    }
    val live = if (KavLive.source.hopOn) sourceName(KavLive.source) else named(KavLive.server)
    val trips = if (KavPlan.source.provider) sourceName(KavPlan.source) else named(KavPlan.planner())
    val timetable = if (Schedules.source.provider) sourceName(Schedules.source)
        else if (KavLive.timetableServer != null) T("your server", "השרת שלכם") else "Kav"
    // Where links are made, once there's a choice of it (ShareServerChoice).
    val links = if (KavLive.ownServer.isEmpty()) "" else " · " + T("links: ", "קישורים: ") + named(TripShare.server)
    return T("Live: ", "זמן אמת: ") + live + " · " + T("trips: ", "מסלולים: ") + trips + " · " +
        T("timetable: ", "לוח זמנים: ") + timetable + links
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
    PlaceSearchChoice()
    InfoHeader(T("Search", "חיפוש"), T(
            "Your exact location stays off search. A trip still sends its two points.",
            "המיקום המדויק שלכם לא נשלח בחיפוש. מסלול עדיין שולח את שתי הנקודות שלו.",
        ))
    ListGroup {
        row {
            ToggleRow(T("Private search", "חיפוש פרטי"), priv, { ShieldGlyph(toggleInk(priv), 18.dp) }) { on ->
                priv = on; uk.noammm.kav.Prefs.setPrivateSearch(ctx, on); uk.noammm.kav.data.GoogleMaps.shareLocation = !on
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
    subtitleMaxLines: Int = 1, onChange: (Boolean) -> Unit,
) = ListRow(
    title, subtitle = subtitle,
    subtitleMaxLines = subtitleMaxLines,
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
        // The search's name fades to the other one's when it's switched, the words around it staying where they are.
        ChangingText(searchName()) { who -> SectionHeader(T("What $who sees", "מה $who רואה")) }
        KavSegmented(
            listOf(T("Your town", "היישוב שלכם"), T("A place", "מקום"), T("Nothing", "כלום")),
            seens.indexOf(model.seen).coerceAtLeast(0),
            { i ->
                val seen = seens[i]
                if (seen == Seen.PLACE && model.seenPlace == null) onChoose() else model.setSeen(ctx, seen)
            },
        )
        val place = model.seenPlace?.name
        androidx.compose.animation.AnimatedContent(
            model.seen to city,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
            label = "seen",
        ) { (seen, town) ->
            NoteCard(
                when (seen) {
                    Seen.NONE -> T("Searches carry no location, so results aren't sorted by distance.",
                        "החיפוש לא כולל מיקום, ולכן התוצאות לא ממוינות לפי מרחק.")
                    Seen.CITY -> T("The centre of your town, worked out on your phone. ", "מרכז היישוב שלכם, שמחושב בטלפון. ") +
                        (town?.let { T("Now: $it.", "כרגע: $it.") } ?: "")
                    Seen.PLACE -> T("A place you pick, so results near it come first.", "מקום שתבחרו, כך שתוצאות קרובות אליו מופיעות ראשונות.")
                },
                Modifier.padding(top = K.gap2),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            model.seen == Seen.PLACE,
            enter = androidx.compose.animation.expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
            exit = androidx.compose.animation.shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) +
                androidx.compose.animation.fadeOut(tween(120)),
        ) {
            ListGroup {
                row {
                    ListRow(
                        place ?: T("Pick a place", "בחירת מקום"),
                        subtitle = searchName().let { who -> T("$who sees this instead of you", "$who רואה אותו במקומכם") },
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
    KavSegmented(
        Lang.entries.map { it.label }, selected = Lang.entries.indexOf(T.lang),
        onSelect = { i -> val l = Lang.entries[i]; T.switchTo(l); Prefs.setLang(ctx, l) },
    )
}

// The language of stops', stations', towns' and lines' ends' names, apart from Kav's own, and nothing else: as the
// Ministry of Transport names them in Hebrew, or in English where it has their English names.
@Composable
internal fun StopNamesRow(ctx: android.content.Context, modifier: Modifier = Modifier, explained: Boolean = true) {
    Column(modifier) {
        // In the order Kav's languages are listed in.
        KavSegmented(
            listOf("English", "עברית"), selected = if (StopNames.english) 0 else 1,
            onSelect = { i -> StopNames.english = i == 0; Prefs.setStopNamesEnglish(ctx, i == 0) },
        )
        if (explained) NoteCard(
            T(
                "Only the names of stops, stations and towns, as the Ministry of Transport gives them, on the boards, " +
                    "lines and trips. Kav's own language stays as it is.",
                "רק שמות התחנות והיישובים, כפי שמשרד התחבורה נותן אותם, בלוחות, בקווים ובנסיעות. השפה של Kav נשארת כפי שהיא.",
            ),
            Modifier.padding(top = K.gap2),
        )
    }
}

// The search picked, by name, for what Settings says it sees.
internal fun searchName() = if (uk.noammm.kav.data.Search.provider == uk.noammm.kav.data.PhotonSearch) "OpenStreetMap" else "Google"

// The language of the map's own names, apart from Kav's: Hebrew as the map has them, or English where it has those.
@Composable
internal fun MapLabelsRow(ctx: android.content.Context) {
    // In the order Kav's languages are listed in.
    KavSegmented(
        listOf("English", "עברית"), selected = if (MapFile.labels == Lang.EN) 0 else 1,
        onSelect = { i -> val l = if (i == 0) Lang.EN else Lang.HE; MapFile.labels = l; Prefs.setMapLabels(ctx, l) },
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
private fun PipGlyph(tint: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * .09f
        drawRoundRect(tint, Offset(w * .08f, h * .18f), androidx.compose.ui.geometry.Size(w * .84f, h * .64f),
            androidx.compose.ui.geometry.CornerRadius(w * .1f), style = Stroke(sw))
        drawRoundRect(tint, Offset(w * .48f, h * .48f), androidx.compose.ui.geometry.Size(w * .32f, h * .24f),
            androidx.compose.ui.geometry.CornerRadius(w * .05f))
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

// Kav's fixes for Google are signed files it checks once a day. This asks now, for when something has just stopped
// working and a fix may already be out.
@Composable
private fun FixCheck() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<SignedFile.Check?>(null) }
    var checking by remember { mutableStateOf(false) }
    InfoHeader(
        T("Fixes for Google", "תיקונים ל-Google"),
        T("Small signed updates Kav fetches when Google changes how it answers, so it keeps working between releases.",
            "עדכונים קטנים וחתומים ש-Kav מורידה כש-Google משנה את אופן התשובה, כדי שתמשיך לעבוד בין גרסאות."),
    )
    Row(
        Modifier.fillMaxWidth().panel(K.rCard).padding(horizontal = K.gap4, vertical = K.gap3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f)) {
            // The status rolls from one state to the next, in its colour.
            LiveText(
                when {
                    checking -> T("Looking…", "בודקים…")
                    result == SignedFile.Check.NEWER -> T("A fix arrived and is in use", "הגיע תיקון והוא כבר בשימוש")
                    result == SignedFile.Check.SAME -> T("Kav has the latest fixes", "ל-Kav יש את התיקונים האחרונים")
                    result == SignedFile.Check.FAILED -> T("Couldn't check. Try again online.", "לא הצלחנו לבדוק. נסו שוב כשיש חיבור.")
                    else -> T("Checked once a day, no update needed", "נבדק פעם ביום, בלי צורך בעדכון")
                },
                style = K.type.body, color = if (result == SignedFile.Check.NEWER) K.realtime else K.text,
            )
        }
        KavButton(T("Check now", "בדקו עכשיו"), busy = checking, style = ButtonStyle.Secondary, onClick = {
            if (checking) return@KavButton
            scope.launch {
                checking = true
                result = withContext(Dispatchers.IO) {
                    runCatching { GoogleRecipe.refresh(ctx, force = true) }.getOrDefault(SignedFile.Check.FAILED)
                }
                checking = false
            }
        })
    }
}

@Composable
private fun Group(title: String) = SectionHeader(title, Modifier.padding(horizontal = K.gap4))

@Composable
private fun Absent(title: String, desc: String) {
    Row(
        Modifier.padding(horizontal = K.gap4, vertical = K.gap2).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Box(
            Modifier.padding(top = 11.dp).width(14.dp).height(1.dp).background(K.borderStrong),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = K.type.body)
            Text(desc, style = K.type.subhead, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

// 24 or 12 hours: two choices that change every time shown, so a segmented control.
@Composable
internal fun ClockChoice(ctx: android.content.Context, modifier: Modifier = Modifier) {
    KavSegmented(
        listOf(T("24-hour (14:05)", "24 שעות (14:05)"), T("12-hour (2:05 PM)", "12 שעות (2:05 PM)")),
        selected = if (Shown.twelveHour) 1 else 0,
        onSelect = { i -> Shown.twelveHour = i == 1; Prefs.setTwelveHour(ctx, i == 1) },
        modifier = modifier,
    )
}
