package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.Sources
import uk.noammm.kav.data.Timetable

@Composable
fun OnboardingScreen(model: KavModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var spoken by remember { mutableStateOf(Prefs.spokenAlerts(ctx)) }
    var startNow by remember { mutableStateOf(Prefs.beginTripsImmediately(ctx)) }
    androidx.activity.compose.BackHandler(enabled = page > 0) { page-- }
    // What the last step downloads starts as setup opens, in the background, so it's there by then and the last step
    // doesn't come at all: on Wi-Fi or another unmetered network; on mobile data the timetable only as its own setting
    // allows (Also on mobile data), and the map only once the last step's Download asks for it. The timetable is the
    // chosen source's: a server of one's own chosen on the way has its own copy fetched instead.
    val timetableAt = Sources.timetableFrom(Schedules.source, KavLive.ownServer)
    val weekNow = Timetable.state
    LaunchedEffect(timetableAt) { Timetable.init(ctx) }
    LaunchedEffect(timetableAt, weekNow) {
        if (weekNow is Timetable.State.Missing && (!Timetable.metered(ctx) || Prefs.timetableOnMobile(ctx))) Timetable.startDownload(ctx)
    }
    LaunchedEffect(Unit) {
        val map = MapFile.provider().takeIf { it.needsPack }?.pack
        if (map?.state is MapPack.State.Missing && !Timetable.metered(ctx)) map.download(ctx.applicationContext.filesDir)
    }
    // An OpenFreeMap map draws online until it's downloaded in Settings, so it holds nothing up here.
    val mapNow = MapFile.provider().let { if (it.needsPack) it.pack.state else MapPack.State.Ready }
    val allThere = mapNow is MapPack.State.Ready && weekNow is Timetable.State.Ready

    Box(Modifier.fillMaxSize()) {
    AnimatedContent(
        page, transitionSpec = { if (targetState > initialState) forward() else backward() }, label = "onboarding",
        modifier = Modifier.fillMaxSize().background(K.bg),
    ) { p ->
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(K.gap4),
        ) {
            Spacer(Modifier.height(K.gap6))
            if (p == 0) {
                Text("בחרו הגדרות אזוריות", style = K.type.largeTitle)
                Text("Choose a locale", style = K.type.largeTitle, color = K.textTertiary)
                Spacer(Modifier.height(K.gap6))
                KavSegmented(
                    Lang.entries.map { it.label }, selected = Lang.entries.indexOf(T.lang),
                    onSelect = { i -> val l = Lang.entries[i]; T.switchTo(l); Prefs.setLang(ctx, l) },
                )
                // A question of its own: the names of stops, apart from Kav's language.
                Text(
                    T("Stop names", "שמות תחנות"),
                    style = K.type.caption,
                    modifier = Modifier.padding(start = K.gap3, top = K.gap3, bottom = K.gap2),
                )
                StopNamesRow(ctx, Modifier)
                Text(
                    T("Names on the map", "שמות במפה"),
                    style = K.type.caption,
                    modifier = Modifier.padding(start = K.gap3, top = K.gap3, bottom = K.gap2),
                )
                MapLabelsRow(ctx)
                Text(
                    T("Time format", "תבנית שעה"),
                    style = K.type.caption,
                    modifier = Modifier.padding(start = K.gap3, top = K.gap3, bottom = K.gap2),
                )
                ClockChoice(ctx, Modifier)
                Spacer(Modifier.height(K.gap6))
                OnboardingButton(T("Next", "הבא")) { page = 1 }
            } else if (p == 1) {
                Text(T("Pick a look", "בחרו מראה"), style = K.type.largeTitle)
                Text(
                    T(
                        "OLED black, light or dark, with liquid glass or solid on top. " +
                            "The preview changes as you tap, and Settings has this again later.",
                        "שחור OLED, בהיר או כהה, עם זכוכית נוזלית או משטחים אטומים. " +
                            "התצוגה המקדימה משתנה כשאתם מקישים, ואפשר לשנות זאת שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap2))
                LookChoices {
                    Text(it, style = K.type.caption,
                        modifier = Modifier.padding(start = K.gap3, top = K.gap4, bottom = K.gap2))
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { Prefs.lookPicked(ctx); page = 2 }
            } else if (p == 2) {
                Text(T("Pick a colour", "בחרו צבע"), style = K.type.largeTitle)
                Text(
                    T(
                        "Kav is grey with one colour on top. Slide along the colours, or take a preset; " +
                            "the preview follows as you go, and Settings has this again later.",
                        "Kav אפורה עם צבע אחד מעליה. החליקו לאורך הצבעים או בחרו גוון מוכן; " +
                            "התצוגה המקדימה מתעדכנת תוך כדי, ואפשר לשנות זאת שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap6))
                AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LineBadgeChoice() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { MapChoices(setup = true) }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 3 }
            } else if (p == 3) {
                Text(T("Providers", "ספקים"), style = K.type.largeTitle)
                Text(
                    T(
                        "What Kav shares, and with whom. Settings has all of this again later.",
                        "מה Kav משתפת, ועם מי. אפשר לשנות את כל זה שוב בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap3))
                // The same choices as Settings: where live locations come from, who plans trips, whose timetable lines
                // and stops show, and whether the week on the phone updates itself.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LiveSource() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PlanningSource() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { TimetableSource(); TimetableUpdates() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PlaceSearchChoice() }
                // What's asked of Google or another service besides: line alerts, how busy, and photos.
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { AlertsChoice() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { CrowdChoice() }
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { StopPhotoChoice() }
                // And whether all of it goes through a proxy of the rider's own.
                Spacer(Modifier.height(K.gap3))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { ProxyChoice() }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 4 }
            } else if (p == 4) {
                Text(T("What should a plan show?", "מה מסלול יכול לכלול?"), style = K.type.largeTitle)
                Text(
                    T(
                        "Everything is on. Switch off what you never take and the planner " +
                            "leaves it out. This is the same list Settings keeps.",
                        "הכול פעיל. כבו את מה שאתם אף פעם לא נוסעים בו והמתכנן ישמיט אותו. " +
                            "זו אותה רשימה שנמצאת בהגדרות.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap3))
                PlanChoices(model)
                Spacer(Modifier.height(K.gap5))
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    OnTheWayChoices(spoken, startNow, { spoken = it }, { startNow = it }, setup = true)
                }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 5 }
            } else if (p == 5) {
                AutomaticUpdatesStep()
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { UpdatePrompts(checkAbove = false) }
                Spacer(Modifier.height(K.gap8))
                // Everything downloaded already: setup ends here, without its last step.
                AnimatedContent(allThere, transitionSpec = { switching() }, label = "setupNext") { done ->
                    if (done) OnboardingButton(T("Done", "סיום"), onClick = onDone)
                    else OnboardingButton(T("Next", "הבא")) { page = 6 }
                }
            } else {
                // The map the chosen map type needs on the phone, or none (an OpenFreeMap map loads online until it's
                // downloaded in Settings).
                val provider = MapFile.provider()
                val pack = provider.pack.takeIf { provider.needsPack }
                val state = pack?.state ?: MapPack.State.Ready
                val week = Timetable.state
                val files = ctx.applicationContext.filesDir
                Text(
                    if (pack != null) T("Download the map and timetable", "הורדת המפה ולוח הזמנים")
                    else T("Download the timetable", "הורדת לוח הזמנים"),
                    style = K.type.largeTitle,
                )
                Text(
                    if (pack != null) T(
                        "Kav keeps its map on your phone instead of loading tiles from a server as " +
                            "you go, so nothing tracks where you look. It's about ${pack.bytes shr 20} MB for all " +
                            "of Israel, once. After that the map works with no signal. Kav needs it " +
                            "before it can show you anything, so it downloads now. With it comes the week of scheduled " +
                            "departures, about 6 MB, built each night from the Ministry of Transport's timetable and " +
                            "kept up to date in the background.",
                        "Kav מחזיקה את המפה בטלפון שלכם במקום לטעון אריחים משרת תוך כדי תנועה, " +
                            "כך שאף אחד לא עוקב אחרי מה שאתם מסתכלים עליו. זה בערך ${pack.bytes shr 20} MB לכל " +
                            "ישראל, פעם אחת. אחרי זה המפה עובדת גם בלי קליטה. Kav צריכה אותה כדי " +
                            "להציג לכם משהו, אז מורידים אותה עכשיו. איתה מגיע שבוע היציאות המתוכננות, בערך 6 MB, " +
                            "שנבנה כל לילה מלוח הזמנים של משרד התחבורה ומתעדכן ברקע.",
                    ) else if (provider is uk.noammm.kav.data.GovMap) T(
                        "The GovMap map you picked loads from the Survey of Israel as you look, so it sees which area you view. " +
                            "What downloads now is the week of scheduled departures, about 6 MB, kept on your phone for use without signal.",
                        "מפת GovMap שבחרתם נטענת מהמרכז למיפוי ישראל בזמן הצפייה, ולכן הוא רואה איזה אזור אתם צופים בו. " +
                            "מה שיורד עכשיו הוא שבוע היציאות המתוכננות, בערך 6 MB, שנשמר בטלפון לשימוש גם בלי קליטה.",
                    ) else T(
                        "The OpenFreeMap map you picked loads from OpenFreeMap as you look, so OpenFreeMap sees which " +
                            "area you view. Settings can keep it on your phone instead. What downloads now is the week " +
                            "of scheduled departures, about 6 MB, built each night from the Ministry of Transport's " +
                            "timetable and kept up to date in the background.",
                        "מפת OpenFreeMap שבחרתם נטענת מ-OpenFreeMap בזמן הצפייה, ולכן OpenFreeMap רואה איזה אזור " +
                            "אתם צופים בו. בהגדרות אפשר לשמור אותה בטלפון במקום זאת. מה שיורד עכשיו הוא שבוע היציאות " +
                            "המתוכננות, בערך 6 MB, שנבנה כל לילה מלוח הזמנים של משרד התחבורה ומתעדכן ברקע.",
                    ),
                    style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
                )
                Spacer(Modifier.height(K.gap5))
                // Weight the combined progress by size, downloading the small timetable first.
                var started by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(started, week, state) {
                    if (started && week is Timetable.State.Ready && state is MapPack.State.Missing) pack?.download(files)
                }
                val weekDone = when (week) { is Timetable.State.Ready -> 1f; is Timetable.State.Downloading -> week.progress; else -> 0f }
                val mapDone = when (state) { is MapPack.State.Ready -> 1f; is MapPack.State.Downloading -> state.progress; else -> 0f }
                val mapBytes = pack?.bytes?.toFloat() ?: 0f
                val total = (weekDone * WEEK_BYTES + mapDone * mapBytes) / (WEEK_BYTES + mapBytes)
                // The status changes in place: the bar fades into the note or the failure, and the space eases.
                val kind = when {
                    state is MapPack.State.Ready && week is Timetable.State.Ready -> 1
                    week is Timetable.State.Failed -> 2
                    state is MapPack.State.Failed -> 3
                    started || week is Timetable.State.Downloading || state is MapPack.State.Downloading -> 4
                    else -> 0
                }
                var why by remember { mutableStateOf("") }
                ((week as? Timetable.State.Failed)?.why ?: (state as? MapPack.State.Failed)?.why)?.let { why = it }
                AnimatedContent(kind, transitionSpec = { switching() }, label = "setupStatus") { k ->
                    when (k) {
                        1 -> NoteCard(
                            if (pack != null) T("Got it. The map and timetable stay on your phone from now on.", "מוכן. מעכשיו המפה ולוח הזמנים נשארים בטלפון שלכם.")
                            else T("Got it. The timetable stays on your phone from now on.", "מוכן. מעכשיו לוח הזמנים נשאר בטלפון שלכם."),
                        )
                        2 -> Text(T("Couldn't download the timetable. $why", "ההורדה של לוח הזמנים לא הצליחה. $why"), style = K.type.subhead, color = K.critical)
                        3 -> Text(T("Couldn't download the map. $why", "ההורדה של המפה לא הצליחה. $why"), style = K.type.subhead, color = K.critical)
                        4 -> DownloadBar(
                            total,
                            if (week !is Timetable.State.Ready) T("Downloading the timetable…", "מורידים את לוח הזמנים…")
                            else T("Downloading the map…", "מורידים את המפה…"),
                        )
                        else -> {}
                    }
                }
                Spacer(Modifier.height(K.gap8))
                val start = {
                    started = true
                    if (week !is Timetable.State.Ready) Timetable.startDownload(ctx)
                    else if (state !is MapPack.State.Ready) pack?.download(files)
                }
                // One button fades into the next (none while it downloads).
                val button = when {
                    state is MapPack.State.Ready && week is Timetable.State.Ready -> 1
                    week is Timetable.State.Failed || state is MapPack.State.Failed -> 2
                    started || state is MapPack.State.Downloading || week is Timetable.State.Downloading -> 0
                    else -> 3
                }
                AnimatedContent(button, transitionSpec = { switching() }, label = "setupButton") { b ->
                    when (b) {
                        1 -> OnboardingButton(T("Done", "סיום"), onClick = onDone)
                        2 -> OnboardingButton(T("Try again", "נסו שוב")) { start() }
                        3 -> OnboardingButton(T("Download", "הורדה")) { start() }
                        else -> {}
                    }
                }
            }
            Spacer(Modifier.height(K.gap6))
        }
    }
    }
}

// One state of setup's last step fading into the next while the space it takes eases.
private fun <S> androidx.compose.animation.AnimatedContentTransitionScope<S>.switching() =
    (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) togetherWith
        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)))
        .using(androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f) })

@Composable
private fun OnboardingButton(label: String, onClick: () -> Unit) = KavButton(label, onClick, wide = true)

// About the size of the week's download, for its share of setup's one bar with the map.
private const val WEEK_BYTES = 6_500_000f

@Composable
internal fun DownloadBar(progress: Float, what: String = T("Downloading…", "מורידים…")) = Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
    // The bar fills smoothly between ticks, and its words and percentage roll.
    val fill = androidx.compose.animation.core.animateFloatAsState(progress.coerceIn(0.02f, 1f), androidx.compose.animation.core.tween(300), label = "download").value
    Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(K.accent))
    }
    LiveText("$what ${(progress * 100).toInt()}%", style = K.type.caption)
}

// A rider who set Kav up when it came with its timetable gets the week before anything else, once: Kav reads it
// everywhere. It starts at once, and Try again stays until it's in.
@Composable
internal fun TimetableStep() {
    val ctx = LocalContext.current
    val week = Timetable.state
    LaunchedEffect(Unit) { if (Timetable.state is Timetable.State.Missing) Timetable.startDownload(ctx) }
    Column(
        Modifier.fillMaxSize().background(K.bg).windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(K.gap4),
    ) {
        Spacer(Modifier.height(K.gap6))
        Text(T("Getting the timetable", "מורידים את לוח הזמנים"), style = K.type.largeTitle)
        Text(
            T(
                "Kav no longer comes with its timetable. It downloads the week of scheduled departures, about 6 MB, built " +
                    "each night from the Ministry of Transport's timetable, and keeps it up to date in the background.",
                "Kav כבר לא מגיעה עם לוח הזמנים. היא מורידה את שבוע היציאות המתוכננות, בערך 6 MB, שנבנה כל לילה " +
                    "מלוח הזמנים של משרד התחבורה, ומעדכנת אותו ברקע.",
            ),
            style = K.type.subhead, modifier = Modifier.padding(top = K.gap2),
        )
        Spacer(Modifier.height(K.gap5))
        // The bar and a failure fade into each other, and Try again fades in under them.
        var why by remember { mutableStateOf("") }
        (week as? Timetable.State.Failed)?.let { why = it.why }
        AnimatedContent(when (week) { is Timetable.State.Downloading -> 1; is Timetable.State.Failed -> 2; else -> 0 }, transitionSpec = { switching() }, label = "weekStatus") { k ->
            when (k) {
                1 -> DownloadBar((week as? Timetable.State.Downloading)?.progress ?: 1f)
                2 -> Text(T("Couldn't download it. $why", "ההורדה לא הצליחה. $why"), style = K.type.subhead, color = K.critical)
                else -> {}
            }
        }
        Spacer(Modifier.height(K.gap8))
        AnimatedContent(week is Timetable.State.Failed || week is Timetable.State.Missing, transitionSpec = { switching() }, label = "weekRetry") { again ->
            if (again) OnboardingButton(T("Try again", "נסו שוב")) { Timetable.startDownload(ctx) }
        }
    }
}
