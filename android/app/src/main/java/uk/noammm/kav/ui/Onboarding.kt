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
import uk.noammm.kav.BuildConfig
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
    androidx.activity.compose.BackHandler(enabled = page > 0) { page = if (page == 6 && !BuildConfig.UPDATER) 4 else page - 1 }
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
        VeilMaps()
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
                StopNamesRow(ctx)
                SectionHeader(T("Names on the map", "שמות במפה"))
                MapLabelsRow(ctx)
                SectionHeader(T("Time format", "תבנית שעה"))
                ClockChoice(ctx, Modifier)
                Spacer(Modifier.height(K.gap6))
                OnboardingButton(T("Next", "הבא")) { page = 1 }
            } else if (p == 1) {
                // The preview under it shows each choice, so the step needs no explanation.
                Text(T("Pick a look", "בחרו מראה"), style = K.type.largeTitle)
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
                Text(T("Pick a color", "בחרו צבע"), style = K.type.largeTitle)
                Spacer(Modifier.height(K.gap5))
                AccentPreview()
                Spacer(Modifier.height(K.gap6))
                AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LineBadgeChoice() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { MapChoices(setup = true) }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 3 }
            } else if (p == 3) {
                InfoHeader(
                    T("Providers", "ספקים"),
                    T(
                        "What Kav shares, and with whom. Settings has all of this again later.",
                        "מה Kav משתפת, ועם מי. אפשר לשנות את כל זה שוב בהגדרות.",
                    ),
                    headline = true,
                )
                Spacer(Modifier.height(K.gap3))
                // The same choices as Settings: where live locations come from, who plans trips, whose timetable lines
                // and stops show, and whether the week on the phone updates itself.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LiveSource() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PlanningSource() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { TimetableSource(); TimetableUpdates() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PlaceSearchChoice() }
                // What's asked of Google or another service besides: line alerts, how busy, and photos.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { AlertsChoice() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { CrowdChoice() }
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { StopPhotoChoice() }
                // And whether all of it goes through a proxy of the rider's own.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { ProxyChoice() }
                Spacer(Modifier.height(K.gap8))
                OnboardingButton(T("Next", "הבא")) { page = 4 }
            } else if (p == 4) {
                // Named as Settings' page with the same choices is; each section under it has its own header.
                Text(T("Trips", "נסיעות"), style = K.type.largeTitle)
                Spacer(Modifier.height(K.gap3))
                PlanChoices(model)
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    OnTheWayChoices(spoken, startNow, { spoken = it }, { startNow = it }, setup = true)
                }
                // Paying with Rav-Pass, which can be turned on here with its permissions, or later in Settings.
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PayingSetup() }
                Spacer(Modifier.height(K.gap8))
                if (BuildConfig.UPDATER) OnboardingButton(T("Next", "הבא")) { page = 5 }
                // The F-Droid build has no updates step (BuildConfig.UPDATER): setup goes on from here as from that step.
                else AnimatedContent(allThere, transitionSpec = { switching() }, label = "setupNext") { done ->
                    if (done) OnboardingButton(T("Done", "סיום"), onClick = onDone)
                    else OnboardingButton(T("Next", "הבא")) { page = 6 }
                }
            } else if (p == 5) {
                AutomaticUpdates(setup = true)
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { UpdatePrompts() }
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
                InfoHeader(
                    if (pack != null) T("Download the map and timetable", "הורדת המפה ולוח הזמנים")
                    else T("Download the timetable", "הורדת לוח הזמנים"),
                    if (pack != null) T(
                        "The map is for all of Israel and then works without signal. The week's timetable comes with it.",
                        "המפה היא של כל ישראל, ואחרי ההורדה היא עובדת גם בלי קליטה. איתה מגיע לוח הזמנים של השבוע.",
                    ) else if (provider is uk.noammm.kav.data.GovMap) T(
                        "The GovMap map loads as you look, so the Survey of Israel sees the area you view. What downloads now is the week's timetable.",
                        "מפת GovMap נטענת בזמן הצפייה, ולכן המרכז למיפוי ישראל רואה את האזור שאתם צופים בו. מה שיורד עכשיו הוא לוח הזמנים של השבוע.",
                    ) else T(
                        "The OpenFreeMap map loads as you look, so OpenFreeMap sees the area you view. What downloads now is the week's timetable.",
                        "מפת OpenFreeMap נטענת בזמן הצפייה, ולכן OpenFreeMap רואה את האזור שאתם צופים בו. מה שיורד עכשיו הוא לוח הזמנים של השבוע.",
                    ),
                    headline = true,
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
                        else -> ((pack?.bytes ?: 0L) + WEEK_BYTES.toLong() shr 20).let { mb -> NoteCard(T("About $mb MB, once.", "בערך ${megabytes(mb)}, פעם אחת.")) }
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
        InfoHeader(
            T("Getting the timetable", "מורידים את לוח הזמנים"),
            T(
                "Kav now downloads its timetable: the week's departures, about 6 MB, kept up to date in the background.",
                "Kav מורידה עכשיו את לוח הזמנים: היציאות של השבוע, בערך ${megabytes(6)}, והוא מתעדכן ברקע.",
            ),
            headline = true,
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
