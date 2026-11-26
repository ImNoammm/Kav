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
    androidx.activity.compose.BackHandler(enabled = page > 0) { page = if (page == 8 && !BuildConfig.UPDATER) 6 else page - 1 }
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

    // Settings' groups in Settings' order: Language and time first (every later step reads in it), then how Kav looks
    // (Look, Color, Map), then riding (Trips with paying, Providers), then Privacy, then updates and the download.
    val next = { page += 1 }
    // Everything downloaded already: setup ends with the last choice, without the download step.
    val doneOrNext: @Composable () -> Unit = {
        AnimatedContent(allThere, transitionSpec = { switching() }, label = "setupNext") { done ->
            if (done) OnboardingButton(T("Done", "סיום"), onClick = onDone)
            else OnboardingButton(T("Next", "הבא")) { page = 8 }
        }
    }
    AnimatedContent(
        page, transitionSpec = { if (targetState > initialState) forward() else backward() }, label = "onboarding",
        modifier = Modifier.fillMaxSize().background(K.bg),
    ) { p ->
        VeilMaps()
        when (p) {
            // A fresh install starts in Hebrew: the title is in both languages, Hebrew first, whichever Kav is in.
            0 -> SetupStep("שפה ושעה", under = "Language and time", button = { OnboardingButton(T("Next", "הבא"), onClick = next) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LanguageAndTime(ctx) }
            }
            // The preview shows each choice, so the step needs no explanation.
            1 -> SetupStep(T("Look", "מראה"), button = { OnboardingButton(T("Next", "הבא")) { Prefs.lookPicked(ctx); next() } }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    AccentPreview()
                    LookChoices { SectionHeader(it) }
                }
            }
            2 -> SetupStep(T("Color", "צבע"), button = { OnboardingButton(T("Next", "הבא"), onClick = next) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    AccentPreview()
                    Spacer(Modifier.height(K.gap6))
                    AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                    LineBadgeChoice()
                }
            }
            3 -> SetupStep(T("Map", "מפה"), button = { OnboardingButton(T("Next", "הבא"), onClick = next) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { MapChoices(setup = true) }
            }
            4 -> SetupStep(T("Trips", "נסיעות"), button = { OnboardingButton(T("Next", "הבא"), onClick = next) }) {
                PlanChoices(model)
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    OnTheWayChoices(spoken, startNow, { spoken = it }, { startNow = it }, setup = true)
                    // Paying with Rav-Pass, which can be turned on here with its permissions, or later in Settings.
                    PayingSetup()
                }
            }
            // The rows of Settings' page, in its order.
            5 -> SetupStep(T("Providers", "ספקים"), button = { OnboardingButton(T("Next", "הבא"), onClick = next) }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { Providers() }
            }
            // Settings' page without private search, which needs Settings' place picker (SeenPlacePicker).
            6 -> SetupStep(
                T("Privacy", "פרטיות"),
                T(
                    "What Kav shares, and with whom. Settings has all of this again later.",
                    "מה Kav משתפת, ועם מי. אפשר לשנות את כל זה שוב בהגדרות.",
                ),
                // The F-Droid build has no updates step (BuildConfig.UPDATER): setup goes on from here as from that step.
                button = { if (BuildConfig.UPDATER) OnboardingButton(T("Next", "הבא"), onClick = next) else doneOrNext() },
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    PlaceSearchChoice()
                    StopPhotoChoice()
                    ProxyChoice()
                }
            }
            // The step's title, with its (i), is AutomaticUpdates' own.
            7 -> SetupStep(null, button = doneOrNext) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    AutomaticUpdates(setup = true)
                    UpdatePrompts()
                }
            }
            else -> {
                // The map the chosen map type needs on the phone, or none (an OpenFreeMap map loads online until it's
                // downloaded in Settings).
                val provider = MapFile.provider()
                val pack = provider.pack.takeIf { provider.needsPack }
                val state = pack?.state ?: MapPack.State.Ready
                val week = Timetable.state
                val files = ctx.applicationContext.filesDir
                var started by rememberSaveable { mutableStateOf(false) }
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
                SetupStep(
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
                    button = {
                        AnimatedContent(button, transitionSpec = { switching() }, label = "setupButton") { b ->
                            when (b) {
                                1 -> OnboardingButton(T("Done", "סיום"), onClick = onDone)
                                2 -> OnboardingButton(T("Try again", "נסו שוב")) { start() }
                                3 -> OnboardingButton(T("Download", "הורדה")) { start() }
                                else -> {}
                            }
                        }
                    },
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                        // Weight the combined progress by size, downloading the small timetable first.
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
                    }
                }
            }
        }
    }
}

// One step of setup: its title (with an (i) when there's more to say) and its choices scroll, and its button stays at
// the bottom, in the same place on every step. The choices sit as on Settings' pages; null title: the content's first
// part is the title (Updates); under: a second line of title, quieter.
@Composable
private fun SetupStep(
    title: String?, info: String? = null, under: String? = null, button: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = K.gap1)) {
            Spacer(Modifier.height(K.gap6))
            if (title != null) {
                val at = Modifier.padding(horizontal = K.gap3)
                if (info != null) InfoHeader(title, info, at, headline = true)
                else {
                    Text(title, style = K.type.largeTitle, modifier = if (under == null) at.padding(bottom = K.gap2) else at)
                    if (under != null) Text(under, style = K.type.largeTitle, color = K.textTertiary, modifier = at.padding(bottom = K.gap2))
                }
                Spacer(Modifier.height(K.gap3))
            }
            content()
            Spacer(Modifier.height(K.gap6))
        }
        Box(Modifier.fillMaxWidth().padding(K.gap4)) { button() }
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
