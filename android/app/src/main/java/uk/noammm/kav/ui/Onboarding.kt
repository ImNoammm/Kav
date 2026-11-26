package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.Timetable

@Composable
fun OnboardingScreen(model: KavModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    // Only what Kav can't guess: a welcome that asks for the location (the language is a chip on it), the look, and
    // paying, which asks for its own permissions. Everything else starts at Kav's own settings and is in Settings.
    val steps = listOf(SetupPart.WELCOME, SetupPart.LOOK, SetupPart.PAYING)
    var page by rememberSaveable { mutableIntStateOf(0) }
    androidx.activity.compose.BackHandler(enabled = page > 0) { page -= 1 }
    // The timetable starts downloading as setup opens, in the background, so the download step doesn't come at all: on
    // Wi-Fi or another unmetered network, and on mobile data only as its own setting allows (Also on mobile data).
    val weekNow = Timetable.state
    LaunchedEffect(Unit) { Timetable.init(ctx) }
    LaunchedEffect(weekNow) {
        if (weekNow is Timetable.State.Missing && (!Timetable.metered(ctx) || Prefs.timetableOnMobile(ctx))) Timetable.startDownload(ctx)
    }
    // So does Kav's map, on an unmetered network. It doesn't hold setup up: it goes on after setup, with its progress on
    // the map, and on mobile data the map asks for it there.
    LaunchedEffect(Unit) {
        val map = MapFile.provider().takeIf { it.needsPack }?.pack
        if (map?.state is MapPack.State.Missing && !Timetable.metered(ctx)) map.download(ctx.applicationContext.filesDir)
    }
    // Leaving the look's step keeps the look picked there.
    fun leave() { if (steps.getOrNull(page) == SetupPart.LOOK) Prefs.lookPicked(ctx) }
    // The timetable there already: setup ends with the last choice, without the download step.
    val doneOrNext: @Composable () -> Unit = {
        AnimatedContent(weekNow is Timetable.State.Ready, transitionSpec = { switching() }, label = "setupNext") { done ->
            if (done) OnboardingButton(T("Done", "סיום")) { leave(); onDone() }
            else OnboardingButton(T("Next", "הבא")) { leave(); page = steps.size }
        }
    }
    val next: @Composable () -> Unit = { OnboardingButton(T("Next", "הבא")) { leave(); page += 1 } }
    // Continue asks for the location, once, and goes on whatever the answer is.
    val askLocation = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) uk.noammm.kav.requestLocationOnce(ctx) { model.locate(it.first, it.second) }
        page += 1
    }
    val welcomed: @Composable () -> Unit = {
        OnboardingButton(T("Continue", "המשך")) {
            if (uk.noammm.kav.hasPreciseLocation(ctx) || uk.noammm.kav.locationAskedThisRun) page += 1
            else { uk.noammm.kav.locationAskedThisRun = true; askLocation.launch(uk.noammm.kav.LOCATION_PERMISSIONS) }
        }
    }
    AnimatedContent(
        page, transitionSpec = { if (targetState > initialState) forward() else backward() }, label = "onboarding",
        modifier = Modifier.fillMaxSize().background(K.bg),
    ) { p ->
        VeilMaps()
        val button = if (p == steps.size - 1) doneOrNext else next
        when (steps.getOrNull(p)) {
            SetupPart.WELCOME -> WelcomeStep(welcomed)
            // The preview is the step: the look and the color under it show on it, so nothing needs saying. The glass is in
            // Settings' Look.
            SetupPart.LOOK -> SetupStep(T("Look", "מראה"), button = button) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    AccentPreview()
                    Spacer(Modifier.height(K.gap5))
                    LookRow(0.dp)
                    Spacer(Modifier.height(K.gap5))
                    AccentPicker { Prefs.setAccent(ctx, it.toArgb()) }
                }
            }
            // Paying with Rav-Pass and its permissions, through the same popup as Settings (Root hosts it).
            SetupPart.PAYING -> SetupStep(T("Paying", "תשלום"), button = button) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { PayingSetup() }
            }
            null -> {
                val week = Timetable.state
                var started by rememberSaveable { mutableStateOf(false) }
                val start = { started = true; Timetable.startDownload(ctx) }
                // One button fades into the next (none while it downloads).
                val shown = when {
                    week is Timetable.State.Ready -> 1
                    week is Timetable.State.Failed -> 2
                    started || week is Timetable.State.Downloading -> 0
                    else -> 3
                }
                SetupStep(
                    T("Download the timetable", "הורדת לוח הזמנים"),
                    T("The week's departures, kept up to date in the background. Kav's map downloads apart, from the map.",
                        "היציאות של השבוע, והוא מתעדכן ברקע. המפה של Kav יורדת בנפרד, מהמפה עצמה."),
                    button = {
                        AnimatedContent(shown, transitionSpec = { switching() }, label = "setupButton") { b ->
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
                        // The status changes in place: the bar fades into the note or the failure, and the space eases.
                        var why by remember { mutableStateOf("") }
                        (week as? Timetable.State.Failed)?.why?.let { why = it }
                        AnimatedContent(shown, transitionSpec = { switching() }, label = "setupStatus") { k ->
                            when (k) {
                                1 -> NoteCard(T("Got it. The timetable stays on your phone from now on.", "מוכן. מעכשיו לוח הזמנים נשאר בטלפון שלכם."))
                                2 -> Text(T("Couldn't download the timetable. $why", "ההורדה של לוח הזמנים לא הצליחה. $why"), style = K.type.subhead, color = K.critical)
                                0 -> DownloadBar((week as? Timetable.State.Downloading)?.progress ?: 0f, T("Downloading the timetable…", "מורידים את לוח הזמנים…"))
                                else -> NoteCard(T("About ${WEEK_MB} MB, once.", "בערך ${megabytes(WEEK_MB.toLong())}, פעם אחת."))
                            }
                        }
                    }
                }
            }
        }
    }
}

// The parts of setup, in their order.
private enum class SetupPart { WELCOME, LOOK, PAYING }

// Kav's icon, what it does and what it does with the location, and Continue in the same place as every step's button.
// A fresh install starts in Hebrew; the chip at the top shows the language and opens the choice in a sheet. The clock
// is the phone's own (Settings can change it).
@Composable
private fun WelcomeStep(button: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var languages by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap3), horizontalArrangement = Arrangement.End) {
                KavChip(
                    (T.wanted ?: T.lang).label, selected = false, onClick = { languages = true },
                    icon = Icons.Rounded.Language,
                    trailing = Icons.Rounded.ExpandMore,
                )
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = K.gap5),
                    verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(96.dp)
                    Spacer(Modifier.height(K.gap6))
                    Text(
                        T("Live buses and trains near you", "אוטובוסים ורכבות בזמן אמת, קרוב אליכם"),
                        style = K.type.largeTitle, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(K.gap3))
                    // True as the code is: the location finds what's near (Live, the nearest station, search sees only
                    // the town) and plans the rider's own trips on Kav's server, and nothing else.
                    Text(
                        T("No account needed. Your location only finds what's near you and plans your trips.",
                            "בלי חשבון. המיקום שלכם משמש רק כדי למצוא מה שקרוב אליכם ולתכנן את הנסיעות שלכם."),
                        style = K.type.subhead, color = K.textSecondary, textAlign = TextAlign.Center,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(K.gap4)) { button() }
        }
        if (languages) BottomSheet({ languages = false }) { close ->
            SheetHeader(T("Language", "שפה"), { close { languages = false } })
            Spacer(Modifier.height(K.gap3))
            LanguageAndTime(ctx, setup = true)
        }
    }
}

// One step of setup: its title (with an (i) when there's more to say) and its choices scroll, and its button stays at
// the bottom, in the same place on every step. The choices sit as on Settings' pages.
@Composable
private fun SetupStep(
    title: String, info: String? = null, button: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = K.gap1)) {
            Spacer(Modifier.height(K.gap6))
            val at = Modifier.padding(horizontal = K.gap3)
            if (info != null) InfoHeader(title, info, at, headline = true)
            else Text(title, style = K.type.largeTitle, modifier = at.padding(bottom = K.gap2))
            Spacer(Modifier.height(K.gap3))
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

// About the size of the week's download.
private const val WEEK_MB = 6

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
