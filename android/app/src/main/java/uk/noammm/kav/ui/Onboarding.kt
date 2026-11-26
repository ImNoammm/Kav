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
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.Timetable

@Composable
fun OnboardingScreen(model: KavModel, onDone: () -> Unit) {
    val ctx = LocalContext.current
    // Only what Kav can't guess: its language, its look and paying, which asks for permissions. Everything else starts
    // at Kav's own settings and is in Settings. A build without paying leaves PAYING out of the list.
    val steps = listOf(SetupPart.LANGUAGE, SetupPart.LOOK, SetupPart.PAYING)
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
    AnimatedContent(
        page, transitionSpec = { if (targetState > initialState) forward() else backward() }, label = "onboarding",
        modifier = Modifier.fillMaxSize().background(K.bg),
    ) { p ->
        VeilMaps()
        val button = if (p == steps.size - 1) doneOrNext else next
        when (steps.getOrNull(p)) {
            // A fresh install starts in Hebrew: the title is in both languages, Hebrew first, whichever Kav is in. The
            // clock is the phone's own (Settings can change it).
            SetupPart.LANGUAGE -> SetupStep("שפה", under = "Language", button = button) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) { LanguageAndTime(ctx, clock = false) }
            }
            // The preview shows each choice, so the step needs no explanation.
            SetupPart.LOOK -> SetupStep(T("Look", "מראה"), button = button) {
                Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                    AccentPreview()
                    LookChoices { SectionHeader(it) }
                    SectionHeader(T("Color", "צבע"))
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
private enum class SetupPart { LANGUAGE, LOOK, PAYING }

// One step of setup: its title (with an (i) when there's more to say) and its choices scroll, and its button stays at
// the bottom, in the same place on every step. The choices sit as on Settings' pages; under: a second line of title,
// quieter.
@Composable
private fun SetupStep(
    title: String, info: String? = null, under: String? = null, button: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = K.gap1)) {
            Spacer(Modifier.height(K.gap6))
            val at = Modifier.padding(horizontal = K.gap3)
            if (info != null) InfoHeader(title, info, at, headline = true)
            else {
                Text(title, style = K.type.largeTitle, modifier = if (under == null) at.padding(bottom = K.gap2) else at)
                if (under != null) Text(under, style = K.type.largeTitle, color = K.textTertiary, modifier = at.padding(bottom = K.gap2))
            }
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
