package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import uk.noammm.kav.data.RideAlerts
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.Net

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

val LocalBottomBarInset = compositionLocalOf { 0.dp }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun bottomCover(): androidx.compose.ui.unit.Dp =
    WindowInsets.ime.union(WindowInsets.navigationBars).asPaddingValues().calculateBottomPadding()

// Opens a ride's line in the Lines tab at the stop it is boarded from, for all its departures there.
val LocalLineOpener = staticCompositionLocalOf<((Transit.Leg, Transit.Resolved) -> Unit)?> { null }

// Opens a service alert's notices, on a screen that hosts ServiceAlertSheet.
val LocalServiceAlertOpener = staticCompositionLocalOf<(RideAlerts.Alert) -> Unit> { {} }

// A ride's service alert: the one its planner gave (a train's, from Israel Railways), else the line's from the chosen
// alerts source (RideAlerts), where that's switched on.
internal fun lineAlert(r: Transit.Resolved, ride: Transit.Leg?, wait: Transit.Leg?): RideAlerts.Alert? {
    val own = wait?.options?.firstOrNull { it.lineId == ride?.lineId && it.alertCategory > 0 } ?: wait
    val listed = ride?.let { RideAlerts.of(r.line(it.lineId), it, r) }?.takeIf { it.category >= 3 }
    val planned = own?.takeIf { it.alertCategory >= 3 && it.alertText.isNotBlank() }
        ?.let { RideAlerts.Alert(it.alertCategory, it.alertText, it.alertIds, RideAlerts.source) }
    return RideAlerts.combined(planned, listed)
}

// Keeps line alerts fresh while a screen with rides is open. rides: the ones a trip page shows, which Google is asked
// about; a list of trips passes none.
@Composable
internal fun FreshAlerts(rides: List<Pair<Transit.Leg, Transit.Resolved>> = emptyList()) {
    if (!RideAlerts.enabled) return
    val shown by androidx.compose.runtime.rememberUpdatedState(rides)
    LaunchedEffect(T.rtl, RideAlerts.source, RideAlerts.language) {
        // Once the screen has come in, so the first ask doesn't share its opening's frames.
        kotlinx.coroutines.delay(900)
        while (true) {
            RideAlerts.refresh(shown)
            kotlinx.coroutines.delay(60_000)
        }
    }
}

// Keeps how busy the shown rides usually are fresh, from Google, while a trip page is open (GoogleCrowd).
@Composable
internal fun FreshCrowd(rides: List<Pair<Transit.Leg, Transit.Resolved>>) {
    if (!uk.noammm.kav.data.GoogleCrowd.enabled) return
    val shown by androidx.compose.runtime.rememberUpdatedState(rides)
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1_500)
        while (true) {
            uk.noammm.kav.data.GoogleCrowd.refresh(shown)
            kotlinx.coroutines.delay(60_000)
        }
    }
}

// How busy the ride's next departure usually is, as Google predicts it for the line and the hour: a meter of five and
// its words, Google's five steps in order (its scale runs from least to most; what it calls each is Kav's reading).
// While Google is asked, the meter pulses; when asking failed, or Google has no level for it, it says so.
@Composable
internal fun CrowdMeter(ride: Transit.Leg, r: Transit.Resolved, dep: Transit.Departure?) {
    val crowd = uk.noammm.kav.data.GoogleCrowd.displayed(
        dep?.let { uk.noammm.kav.data.GoogleCrowd.of(ride, r, it) }, r.isTrain(ride),
    )
    var shown by remember { mutableStateOf(crowd) }
    if (crowd != null) shown = crowd
    androidx.compose.animation.AnimatedVisibility(
        crowd != null,
        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top) +
            androidx.compose.animation.fadeIn(tween(200, delayMillis = 60)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top) +
            androidx.compose.animation.fadeOut(tween(120)),
    ) { shown?.let { CrowdLevel(it) } }
}

// The meter and its words for one state: the bars fill to a level, or wave while Google is asked, and stay empty
// when it failed or has none. Each bar eases to its new fill and the words cross-fade, so a level coming in after the
// wave grows out of it.
@Composable
internal fun CrowdLevel(crowd: uk.noammm.kav.data.GoogleCrowd.Crowd) {
    val level = (crowd as? uk.noammm.kav.data.GoogleCrowd.Crowd.Level)?.level ?: 0
    val words = when (crowd) {
        is uk.noammm.kav.data.GoogleCrowd.Crowd.Level -> when (level) {
            1 -> T("Usually not busy", "בדרך כלל לא עמוס")
            2 -> T("Usually a little busy", "בדרך כלל עמוס מעט")
            3 -> T("Usually busy", "בדרך כלל עמוס")
            4 -> T("Usually very busy", "בדרך כלל עמוס מאוד")
            else -> T("Usually full", "בדרך כלל מלא")
        }
        uk.noammm.kav.data.GoogleCrowd.Crowd.Asking -> T("Checking how busy…", "בודקים כמה עמוס…")
        uk.noammm.kav.data.GoogleCrowd.Crowd.Failed -> T("Couldn't check how busy", "לא הצלחנו לבדוק כמה עמוס")
        uk.noammm.kav.data.GoogleCrowd.Crowd.Unknown -> T("Google has no estimate for this one", "ל-Google אין הערכה לנסיעה הזו")
    }
    val said = if (level > 0) T("$words, $level of 5, as Google predicts", "$words, $level מתוך 5, לפי Google") else words
    val asking = crowd == uk.noammm.kav.data.GoogleCrowd.Crowd.Asking
    // One wave across the bars a second while asking; still otherwise, so a shown level draws nothing more.
    val wave = if (asking) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "crowdWave")
        t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1100, easing = androidx.compose.animation.core.LinearEasing)), label = "crowdWaveAt")
    } else null
    val crest = K.textTertiary
    Row(
        Modifier.padding(top = K.gap1).semantics(mergeDescendants = true) { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        androidx.compose.material3.Icon(
            Icons.Rounded.Groups, contentDescription = null,
            tint = K.dim, modifier = Modifier.size(18.dp).padding(end = 3.dp),
        )
        for (i in 1..5) {
            val fill by androidx.compose.animation.animateColorAsState(if (i <= level) K.textSecondary else K.fill, tween(260), label = "crowdBar")
            Box(
                Modifier.size(width = 5.dp, height = 12.dp).clip(RoundedCornerShape(2.dp))
                    .drawBehind {
                        drawRect(fill)
                        // A brighter crest passing along the empty bars, left to right in either language's order.
                        wave?.let { w ->
                            val d = kotlin.math.abs(w.value * 6f - i)
                            if (d < 1.5f) drawRect(crest.copy(alpha = (1f - d / 1.5f) * .9f))
                        }
                    },
            )
        }
        androidx.compose.animation.Crossfade(words, animationSpec = tween(220), label = "crowdWords") { w ->
            Text(w, style = K.type.subhead, color = if (crowd == uk.noammm.kav.data.GoogleCrowd.Crowd.Failed) K.textTertiary else K.dim,
                modifier = Modifier.padding(start = K.gap1))
        }
    }
}

// Timetables and departures are in Israel's time, whatever zone the phone is set to.
val ISRAEL: TimeZone = TimeZone.getTimeZone("Asia/Jerusalem")

// Times read "14:05", or "2:05 PM" for anyone who asked for AM/PM.
val CLOCK get() = if (Shown.twelveHour) "h:mm a" else "HH:mm"

// One per thread (a SimpleDateFormat isn't safe across them), made again only when the clock's style changes: screens
// ask for it for each time they show.
private val clocks = ThreadLocal<java.text.SimpleDateFormat>()

fun clockFormat(): java.text.SimpleDateFormat = clocks.get()?.takeIf { it.toPattern() == CLOCK }
    ?: java.text.SimpleDateFormat(CLOCK, Locale.US).apply { timeZone = ISRAEL }.also(clocks::set)

fun hhmm(s: Int): String {
    val h = (s / 3600) % 24; val m = (s / 60) % 60
    return if (!Shown.twelveHour) "%02d:%02d".format(Locale.US, h, m)
    else "%d:%02d %s".format(Locale.US, (h + 11) % 12 + 1, m, if (h < 12) "AM" else "PM")
}

// A length of time, written one way everywhere: "55 min", "1 hr 8 min", "2 hr".
fun dur(s: Int): String = durMinutes((s / 60.0).roundToInt())

fun durMinutes(m: Int): String = durParts(m).joinToString(" ") { (n, unit) -> "$n $unit" }

// Its numbers and their units, for setting the numbers larger.
fun durParts(m: Int): List<Pair<Int, String>> = when {
    m < 60 -> listOf(m to T("min", "דק׳"))
    m % 60 == 0 -> listOf(m / 60 to T("hr", "שע׳"))
    else -> listOf(m / 60 to T("hr", "שע׳"), m % 60 to T("min", "דק׳"))
}

fun nowSec(): Int {
    val c = Calendar.getInstance(ISRAEL)
    return c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
}

fun relative(t: Int, from: Int = nowSec()): String? {
    val d = t - from
    if (d < 0 || d > 3600) return null
    val m = (d / 60.0).roundToInt()
    return if (m <= 0) T("now", "עכשיו") else T("$m min", "$m דק׳")
}

private const val EARTH = 6371000.0

fun metres(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
    val p1 = la1 * PI / 180; val p2 = la2 * PI / 180
    val dp = (la2 - la1) * PI / 180; val dl = (lo2 - lo1) * PI / 180
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH * asin(min(1.0, sqrt(a)))
}

fun distanceLabel(m: Double): String =
    if (m < 1000) T("${m.roundToInt()} m", "${m.roundToInt()} מ'")
    else T("%.1f km", "%.1f ק\"מ").format(Locale.US, m / 1000)

enum class Mode { TRAM, SUBWAY, TRAIN, BUS, FERRY, CABLE, GONDOLA, FUNICULAR, TAXI, OTHER }

fun modeOf(type: Int): Mode = when (type) {
    0 -> Mode.TRAM
    1 -> Mode.SUBWAY
    2 -> Mode.TRAIN
    3, 11, 711 -> Mode.BUS
    4 -> Mode.FERRY
    5 -> Mode.CABLE
    6 -> Mode.GONDOLA
    7 -> Mode.FUNICULAR
    8, 715 -> Mode.TAXI
    12 -> Mode.TRAM
    else -> Mode.OTHER
}

fun isRail(routeType: Int, agencyId: Int) = routeType == 2 || agencyId == 854820

// Israel Railways is paid at the station's gates, before the platform, so a train's payment comes with the way to its
// station as well as with the ride. Light rail, buses and the rest are paid as they are ridden.
internal fun paidAtEntry(ride: Transit.Leg, r: Transit.Resolved): Boolean {
    val agency = r.line(ride.lineId)?.agencyId ?: -1
    return isRail(r.routeType(agency), agency)
}

data class ModePlate(val fill: Color, val edge: Color, val ink: Color)

private val TramPlate = ModePlate(Color(0xFFD8232A), Color(0xFFA81A20), Color(0xFFFCF4F4))
private val RailPlate = ModePlate(Color(0xFF1F6FD0), Color(0xFF1854A3), Color(0xFFF3F7FC))
private val TaxiPlate = ModePlate(Color(0xFFF5C518), Color(0xFFC79D0E), Color(0xFF16160F))
private val CarmelitPlate = ModePlate(Color(0xFF0A822E), Color(0xFF086423), Color(0xFFF2FBF4))
private val RakavlitPlate = ModePlate(Color(0xFF8950D4), Color(0xFF693EA3), Color(0xFFF7F4FD))

fun plateFor(routeType: Int, agencyId: Int = -1): ModePlate? = when {
    isRail(routeType, agencyId) -> RailPlate
    modeOf(routeType) == Mode.TRAM -> TramPlate
    modeOf(routeType) == Mode.TAXI -> TaxiPlate
    modeOf(routeType) == Mode.FUNICULAR -> CarmelitPlate
    modeOf(routeType) == Mode.CABLE || modeOf(routeType) == Mode.GONDOLA -> RakavlitPlate
    else -> null
}

// A ride by its kind in words, then who runs it: "Light rail · כפיר" beside "Bus · אקסטרה ירושלים", as the same number
// can be both (tram 1 and bus 1 in Jerusalem).
fun rideBy(routeType: Int, operator: String?): String =
    typeName(routeType) + (operator?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")

// What a line's badge says aloud: its kind and number, "Light rail 1", "Bus 1".
fun lineDescription(routeType: Int, number: String?): String =
    typeName(routeType) + (number?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

fun typeName(routeType: Int): String = when (routeType) {
    711 -> T("Shuttle", "שאטל")
    7 -> T("Carmelit", "כרמלית")
    5, 6 -> T("Rakavlit", "רכבלית")
    else -> modeName(modeOf(routeType))
}

fun modeName(m: Mode): String = when (m) {
    Mode.BUS -> T("Bus", "אוטובוס"); Mode.TRAIN -> T("Train", "רכבת"); Mode.TRAM -> T("Light rail", "רכבת קלה")
    Mode.SUBWAY -> T("Metro", "מטרו"); Mode.FERRY -> T("Ferry", "מעבורת"); Mode.CABLE -> T("Cable car", "רכבל")
    Mode.GONDOLA -> T("Cable car", "רכבל"); Mode.FUNICULAR -> T("Funicular", "פוניקולר")
    Mode.TAXI -> T("Share taxi", "מונית שירות"); Mode.OTHER -> T("Other", "אחר")
}

@Composable
fun WalkGlyph(tint: Color = K.dim, size: androidx.compose.ui.unit.Dp = 13.dp) {
    Canvas(Modifier.size(size)) { drawWalker(tint) }
}

fun DrawScope.drawWalker(tint: Color) {
    val w = size.width; val h = size.height
    val sw = w * 0.11f
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
    drawCircle(tint, radius = w * .12f, center = Offset(w * .52f, h * .14f))
    line(.52f, .28f, .48f, .56f)
    line(.48f, .56f, .34f, .88f)
    line(.48f, .56f, .66f, .84f)
    line(.52f, .36f, .72f, .46f)
}

// label: a train's number, which the timetable doesn't keep, where a live source has given it. type: the mode of a
// line the timetable hasn't, as the live source has it, and agency its operator, by the ministry's id, or operator, by
// its name (a timetable provider's line).
@Composable
fun LineBadge(
    net: Net, route: Int, modifier: Modifier = Modifier, label: String? = null, type: Int = net.rType.getOrElse(route) { 3 },
    agency: Int = -1, dense: Boolean = false, operator: String? = null,
) {
    val plate = linePlate(type, -1) { Operators.of(net, route) ?: Operators.ofGtfs(agency) ?: Operators.ofName(operator) }
    LinePlateBadge(label?.ifBlank { null } ?: net.rShort.getOrElse(route) { "" }, modeOf(type), plate, modifier, dense)
}

@Composable
fun RailMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(
            K.text,
            topLeft = Offset(0f, h * .06f),
            size = androidx.compose.ui.geometry.Size(w, h * .88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f),
        )
        val band = w * .135f
        for (i in 0..2) {
            val y = h * (.70f - i * .19f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * .10f, y)
                cubicTo(w * .34f, y, w * .40f, y - h * .17f, w * .62f, y - h * .17f)
                lineTo(w * .90f, y - h * .17f)
            }
            drawPath(
                path, K.surface1,
                style = Stroke(band, cap = StrokeCap.Butt, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun CarmelitMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawRoundRect(
            Color(0xFFEF8E1F),
            topLeft = Offset(0f, h * .06f),
            size = androidx.compose.ui.geometry.Size(w, h * .88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f),
        )
        val sw = w * .11f
        for (i in 0 until 2) {
            val dx = w * .17f * i
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(dx + w * .14f, h * .33f)
                lineTo(dx + w * .48f, h * .33f)
                lineTo(dx + w * .14f, h * .67f)
                lineTo(dx + w * .48f, h * .67f)
            }
            drawPath(
                path, Color.White,
                style = Stroke(sw, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round),
            )
        }
    }
}

@Composable
fun RakavlitMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawCircle(Color.White, radius = w * .48f, center = Offset(w / 2, h / 2))
        val blades = listOf(Color(0xFF2C6BB3), Color(0xFF8AB6E0), Color(0xFF9BA1A8))
        for (i in 0 until 6) {
            drawArc(
                blades[i % 3],
                startAngle = 60f * i + 8f,
                sweepAngle = 40f,
                useCenter = true,
                topLeft = Offset(w * .06f, h * .06f),
                size = androidx.compose.ui.geometry.Size(w * .88f, h * .88f),
            )
        }
        drawCircle(Color.White, radius = w * .16f, center = Offset(w / 2, h / 2))
    }
}

@Composable
fun ShuttleMark(size: androidx.compose.ui.unit.Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val plate = Color(0xFF565B63)
        drawRoundRect(
            plate,
            topLeft = Offset(0f, h * .06f),
            size = androidx.compose.ui.geometry.Size(w, h * .88f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .12f),
        )
        drawRoundRect(
            Color.White,
            topLeft = Offset(w * .14f, h * .28f),
            size = androidx.compose.ui.geometry.Size(w * .72f, h * .36f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .09f),
        )
        drawRect(
            plate,
            topLeft = Offset(w * .22f, h * .36f),
            size = androidx.compose.ui.geometry.Size(w * .56f, h * .12f),
        )
        drawCircle(Color.White, radius = w * .075f, center = Offset(w * .32f, h * .72f))
        drawCircle(Color.White, radius = w * .075f, center = Offset(w * .68f, h * .72f))
    }
}

@Composable
fun AgencyMark(routeType: Int, agencyId: Int, tint: Color = K.muted, size: androidx.compose.ui.unit.Dp = 15.dp) {
    when {
        routeType == 711 -> ShuttleMark(size)
        isRail(routeType, agencyId) -> RailMark(size)
        modeOf(routeType) == Mode.FUNICULAR -> CarmelitMark(size)
        modeOf(routeType) == Mode.CABLE || modeOf(routeType) == Mode.GONDOLA -> RakavlitMark(size)
        else -> ModeGlyph(modeOf(routeType), tint, size)
    }
}

@Composable
fun WithTimetable(model: uk.noammm.kav.KavModel, content: @Composable (Net) -> Unit) {
    val net = model.net
    when {
        net != null -> content(net)
        model.netError != null -> Column(Modifier.padding(K.gap4)) {
            Note(T("Could not open the offline timetable: ${model.netError}", "לא ניתן היה לפתוח את לוח הזמנים הלא מקוון: ${model.netError}"))
            Spacer(Modifier.height(K.gap3))
            KavButton(T("Try again", "נסו שוב"), style = ButtonStyle.Secondary, onClick = {
                model.netError = null
                model.netLoadAttempt++
            })
        }
        else -> LoadingBlock(T("Opening the timetable", "פותחים את לוח הזמנים"))
    }
}

@Composable
fun PreciseLocationNudge() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (uk.noammm.kav.hasPreciseLocation(ctx)) return
    if (!uk.noammm.kav.hasLocationPermission(ctx)) return
    Column(Modifier.padding(horizontal = K.gap4, vertical = K.gap2).fillMaxWidth().panel(K.rCard).padding(K.gap4)) {
        Column {
            Text(T("Your location is not accurate", "המיקום שלכם אינו מדויק"), style = K.type.headline)
            Text(
                T(
                    "Turn on ‘Use precise location’. Without it Android rounds your position " +
                        "to about a kilometre, which can plan your trip from the wrong town.",
                    "הפעילו את ‘השתמשו במיקום מדויק’. בלעדיו אנדרואיד מעגל את המיקום שלכם " +
                        "לכדי קילומטר בערך, מה שעלול לתכנן את הנסיעה מהעיר הלא נכונה.",
                ),
                style = K.type.subhead, modifier = Modifier.padding(top = 2.dp),
            )
        }
        KavButton(
            T("Change settings", "שינוי הגדרות"), style = ButtonStyle.Secondary, modifier = Modifier.padding(top = K.gap3),
            onClick = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package", ctx.packageName, null),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }
}

@Composable
fun AlertPip(category: Int, size: androidx.compose.ui.unit.Dp = 13.dp) {
    if (category < 3) return
    val tint = if (category >= 4) Ink.critical else Ink.problem
    val bg = Ink.bg
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(bg, w * .5f, Offset(w * .5f, w * .5f))
        drawCircle(tint, w * .42f, Offset(w * .5f, w * .5f), style = Stroke(w * .13f))
        drawCircle(tint, w * .055f, Offset(w * .5f, w * .31f))
        drawLine(
            tint, Offset(w * .5f, w * .44f), Offset(w * .5f, w * .70f),
            w * .11f, StrokeCap.Round,
        )
    }
}

@Composable
fun AlertRow(shown: RideAlerts.Alert?) {
    // An alert read after the card shows opens in place, and goes the same way.
    val raised = shown?.takeIf { it.category >= 3 && it.label.isNotBlank() }
    var kept by remember { mutableStateOf(raised) }
    if (raised != null) kept = raised
    androidx.compose.animation.AnimatedVisibility(
        raised != null,
        enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = androidx.compose.ui.Alignment.Top) +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200, delayMillis = 60)),
        exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = androidx.compose.ui.Alignment.Top) +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
    ) { kept?.let { AlertTile(it) } }
}

@Composable
private fun AlertTile(alert: RideAlerts.Alert) {
    val open = LocalServiceAlertOpener.current
    val opens = alert.ids.isNotEmpty()
    // A quiet tile; the alert's colour comes with its warning glyph, and a chevron where it opens the notice.
    Row(
        Modifier.fillMaxWidth().padding(top = K.gap2)
            .clip(RoundedCornerShape(K.rTile))
            .background(K.fill)
            .then(if (opens) Modifier.clickable(role = Role.Button) { open(alert) } else Modifier)
            .padding(horizontal = K.gap3, vertical = K.gap2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        AlertPip(alert.category, 18.dp)
        Text(alert.label, style = K.type.subhead, color = K.text, modifier = Modifier.weight(1f))
        if (opens) androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = K.textTertiary, modifier = Modifier.size(20.dp),
        )
    }
}

// Any screen's alert row opens its notices here, over the whole app.
@Composable
fun ServiceAlerts(content: @Composable () -> Unit) {
    var notice by remember { mutableStateOf<RideAlerts.Alert?>(null) }
    CompositionLocalProvider(LocalServiceAlertOpener provides { notice = it }) {
        Box(Modifier.fillMaxSize()) {
            content()
            notice?.let { ServiceAlertSheet(it) { notice = null } }
        }
    }
}

// An alert's notices in full, as the operator published them.
@Composable
fun ServiceAlertSheet(alert: RideAlerts.Alert, onDismiss: () -> Unit) {
    // Kept ones open at once; others are fetched, and translated where they're read in English.
    var notices by remember(alert, RideAlerts.language) { mutableStateOf(RideAlerts.cachedNotices(alert.ids, alert.source)?.let(RideAlerts::inLanguageKept)) }
    var failed by remember(alert) { mutableStateOf(false) }
    var tries by remember(alert) { mutableIntStateOf(0) }
    LaunchedEffect(alert, RideAlerts.language, tries) {
        if (notices != null) return@LaunchedEffect
        failed = false
        // A second try a moment later, before saying so.
        val found = runCatching { RideAlerts.noticesNow(alert.ids, alert.source) }.getOrNull()
            ?: run { kotlinx.coroutines.delay(2_000); runCatching { RideAlerts.noticesNow(alert.ids, alert.source) }.getOrNull() }
        if (found == null) failed = true else notices = RideAlerts.inLanguage(found)
    }
    BottomSheet(onDismiss) { close ->
        // A stop or a line in the notice opens on its tab, the sheet closing first.
        val openStop: (Int) -> Unit = { code -> close { onDismiss(); uk.noammm.kav.AlertLink.alert = alert; uk.noammm.kav.AlertLink.stop = code } }
        val openLine: (String) -> Unit = { number -> close { onDismiss(); uk.noammm.kav.AlertLink.alert = alert; uk.noammm.kav.AlertLink.line = number } }
        SheetHeader(alert.label.ifBlank { T("Service alert", "הודעת שירות") }, { close(onDismiss) })
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            val list = notices
            when {
                list == null && !failed ->
                    LoadingPulse(T("Fetching the notice", "מביאים את ההודעה"), Modifier.fillMaxWidth().padding(top = K.gap4, bottom = K.gap2))
                failed -> Column(Modifier.padding(top = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    NoteCard(T("The notice couldn't be fetched.", "לא הצלחנו להביא את ההודעה."))
                    KavButton(T("Try again", "נסו שוב"), { tries++ }, style = ButtonStyle.Secondary)
                }
                list.isNullOrEmpty() -> NoteCard(
                    T("The operator published no further detail for this alert.", "המפעיל לא פרסם פרטים נוספים על הודעה זו."),
                    Modifier.padding(top = K.gap3),
                )
                else -> list.forEachIndexed { i, a ->
                    if (i > 0) {
                        Spacer(Modifier.height(K.gap3))
                        Separator(0.dp)
                    }
                    Spacer(Modifier.height(K.gap3))
                    // A notice in Hebrew reads right to left whatever Kav's language: the operators publish most only so.
                    // Its dates are Kav's own words, in Kav's direction.
                    // By what most of it is written in: an English notice keeps a town's Hebrew name here and there.
                    val hebrew = HEBREW.findAll(a.title + a.body).count() > LATIN.findAll(a.title + a.body).count()
                    val notice = if (hebrew) LayoutDirection.Rtl else LocalLayoutDirection.current
                    CompositionLocalProvider(LocalLayoutDirection provides notice) {
                        a.title.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = K.type.headline, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(K.gap2))
                        }
                    }
                    alertWindow(a)?.let {
                        Text(it, style = K.type.caption)
                        Spacer(Modifier.height(K.gap2))
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides notice) {
                        Box(Modifier.fillMaxWidth()) { alertText(a)?.let { AlertBody(it, a.detour, openLine, openStop) } }
                    }
                    // The detour's stops the notice's own text doesn't list (a feed's, which names them by id).
                    a.detour?.let { d ->
                        val listed = alertText(a)?.let { t -> alertBlocks(t).flatMap { it.stops } }.orEmpty().toSet()
                        DetourStops(RideAlerts.Detour(d.skipped - listed, d.added - listed), openStop)
                    }
                }
            }
        }
    }
}

// A detour's stops by name, as Kav's timetable names them in Kav's language, each with its code.
@Composable
private fun DetourStops(d: RideAlerts.Detour, open: (Int) -> Unit) {
    val net = uk.noammm.kav.Loaded.net
    val skipped = T("Stops not served", "תחנות מבוטלות")
    for ((title, codes) in listOf(skipped to d.skipped, T("Stops added", "תחנות חלופיות") to d.added)) {
        if (codes.isEmpty()) continue
        Spacer(Modifier.height(K.gap4))
        Text(title, style = K.type.subhead.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = K.text)
        Spacer(Modifier.height(K.gap2))
        val served = title != skipped
        StopRows(codes, open) { served }
    }
}

// Stops by their codes, each with its photo or mark and its name in Kav's language from the timetable, opening on the
// Stops tab. added: a red minus where the detour skips it, a green plus where it adds it, none when neither's known.
@Composable
private fun StopRows(codes: List<Int>, open: (Int) -> Unit, added: (Int) -> Boolean?) {
    val net = uk.noammm.kav.Loaded.net
    ListGroup {
        for (code in codes) row {
            val stop = net?.let { uk.noammm.kav.data.KavLive.stopOfCode(it, code) }?.takeIf { it >= 0 }
            ListRow(
                stop?.let { net.stopName(it) } ?: T("Stop $code", "תחנה $code"),
                subtitle = T("Stop $code", "תחנה $code"),
                leading = { DetourStopMark(net, stop, added(code)) },
                // A stop the timetable doesn't know has nothing to open.
                onClick = stop?.let { { open(code) } },
            )
        }
    }
}

// A detour's stop: its photo or mark as on the trip, with a red minus on its corner where it isn't served and a green
// plus where it's added.
@Composable
private fun DetourStopMark(net: uk.noammm.kav.data.Net?, stop: Int?, added: Boolean?) {
    Box {
        if (net != null && stop != null) StopGlyphOrPhoto(net, stop, null, thumb = 40.dp)
        else StopGlyphOrPhoto(-1, null, thumb = 40.dp)
        if (added != null) Box(
            Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(18.dp).clip(CircleShape)
                .background(K.surface).padding(2.dp).clip(CircleShape).background(if (added) K.realtime else K.critical),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                if (added) Icons.Rounded.Add else Icons.Rounded.Remove, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(12.dp),
            )
        }
    }
}

private fun alertWindow(a: RideAlerts.Notice): String? {
    val day = java.text.SimpleDateFormat("d MMM, $CLOCK", T.locale).apply { timeZone = ISRAEL }
    fun at(t: Long) = day.format(java.util.Date(t * 1000))
    return when {
        a.activeFrom > 0 && a.activeTo > 0 -> T("${at(a.activeFrom)} to ${at(a.activeTo)}", "${at(a.activeFrom)} עד ${at(a.activeTo)}")
        a.activeFrom > 0 -> T("From ${at(a.activeFrom)}", "מ-${at(a.activeFrom)}")
        a.activeTo > 0 -> T("Until ${at(a.activeTo)}", "עד ${at(a.activeTo)}")
        else -> null
    }
}

// An alert's text with its lists of line numbers (a number on each line, or several on one) as a row of line plates.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertBody(text: String, detour: RideAlerts.Detour?, openLine: (String) -> Unit, openStop: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        for (block in alertBlocks(text)) {
            if (block.stops.isNotEmpty()) StopRows(block.stops, openStop) { code ->
                when (code) { in detour?.added.orEmpty() -> true; in detour?.skipped.orEmpty() -> false; else -> null }
            }
            else if (block.lines.isEmpty()) LinkedText(block.text, openLine)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(K.gap1), verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                // Each line opens in the Lines tab, found by its number.
                for (n in block.lines) Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button, onClickLabel = T("Open line $n", "פתיחת קו $n")) { openLine(n) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinePlateBadge(n, Mode.BUS, null, dense = true)
                    block.towards[n]?.let { Text(it, style = K.type.subhead, color = K.dim, modifier = Modifier.padding(start = K.gap1, end = K.gap1)) }
                }
            }
        }
    }
}

// An alert's sentence with each line it names ("Line 20", "לקו 4", "קווים 9 ו-19") a link to the line.
@Composable
private fun LinkedText(text: String, openLine: (String) -> Unit) {
    val link = androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = K.accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val shown = remember(text, ltr) {
        // In an English notice a line with a Hebrew letter ("17ב'") is one piece read left to right, or its letter turns
        // the numbers around it.
        fun piece(s: String) = if (ltr) s.replace(HEBREW_LINE) { "\u2066${it.value}\u2069" } else s
        androidx.compose.ui.text.buildAnnotatedString {
            var at = 0
            for ((range, number) in lineMentions(text)) {
                append(piece(text.substring(at, range.first)))
                withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("line:$number", link) { openLine(number) }) {
                    append(piece(text.substring(range.first, range.last + 1)))
                }
                at = range.last + 1
            }
            append(piece(text.substring(at)))
        }
    }
    Text(shown, style = K.type.body, color = K.textSecondary)
}

// A line number with its Hebrew letter, a geresh after it or not: "17ב'", "6ב".
private val HEBREW_LINE = Regex("""(?<![\d\u2066])\d{1,3}[א-ת]['׳]?(?![א-ת])""")
private val LINE_WORD = Regex("""(?i)\blines?\b|(?<![א-ת])[לבהומש]{0,2}(?:קו|קוו?ים|קווי|קוי)(?![א-ת])""")
// A line number, not a time or a date ("8:00", "8.11"), its letter with a geresh or not ("17ב'").
private val LINE_IN_TEXT = Regex("""\d{1,3}(?:[א-תA-Za-z]['׳]?)?(?![א-תA-Za-z\d]|[:.]\d)""")
// The en dash operators type in notices, as a code so the source holds none.
private val DASH = Char(0x2013)
// At most one word between the word and its list: "קווים מושפעים-400/401", "בקוים:", "הקווים, 266".
private val LINE_LEAD = Regex("""^[\s:,]*(?:[א-תA-Za-z'"]{2,12}[\s:,\-${DASH}]*)?""")
// Between numbers of one list: commas, slashes, spaces, "ו" or "and"; "-2" after a number is its way.
private val LINE_BETWEEN = Regex("""^(?:-\d(?!\d))?(?:\s*(?:,|/|&|and(?=\s)|ו-?|ו־|־|\s)\s*)+""")
// After a number of the list, the word for lines and where it goes, up to the next part: "68 lines towards Kiriat Ono, 205".
private val LINE_TAIL = Regex("""^(?:\s+lines?\b)?(?:\s+(?:towards|לכיוון)\s+[^\d,;:\n]+)?""")
// A number the word for lines comes after, as English has it: "the 68 lines".
private val LINES_AFTER = Regex("""(?<![\d:.])\d{1,3}[א-תA-Za-z]?(?=\s+lines?\b)""")

// Where a text names lines, each number's place in it: the numbers after "line(s)" or "קו"/"קווים", one or a list.
internal fun lineMentions(text: String): List<Pair<IntRange, String>> {
    val out = ArrayList<Pair<IntRange, String>>()
    val starts = LINE_WORD.findAll(text).map { word ->
        val after = word.range.last + 1
        after + (LINE_LEAD.find(text.substring(after))?.value?.length ?: 0)
    } + LINES_AFTER.findAll(text).map { it.range.first }
    for (start in starts) {
        var at = start
        while (at < text.length) {
            val m = LINE_IN_TEXT.find(text, at)?.takeIf { it.range.first == at } ?: break
            if (out.none { it.first.first == m.range.first }) out += m.range to m.value
            at = m.range.last + 1
            at += LINE_TAIL.find(text.substring(at))?.value?.length ?: 0
            val gap = LINE_BETWEEN.find(text.substring(at))?.value ?: break
            if (gap.isEmpty()) break
            at += gap.length
        }
    }
    return out.sortedBy { it.first.first }
}

// A run of an alert's text, a list of line numbers in it, or a list of stops by their codes ("20779 - יוחנן הסנדלר").
// towards: where a line in the list goes, where the operator wrote it ("349 כוכב יאיר").
internal class AlertBlock(
    val text: String, val lines: List<String> = emptyList(), val towards: Map<String, String> = emptyMap(),
    val stops: List<Int> = emptyList(),
)

private val TABLE_HEAD = Regex("""מק["'״]{1,2}ט|(?i)stop code""")

// A line naming a stop by its code first: "20779 - יוחנן הסנדלר/בלפור", "37192 הגולן/החרמון (תחנת מוצא)".
private val STOP_LINE = Regex("""^\s*(\d{4,6})(?!\d)\s*[-${DASH}:]?\s*\S.*$""")

private val HEBREW = Regex("[\u0590-\u05FF]")
private val LATIN = Regex("[A-Za-z]")
private val LINE_NUMBER = Regex("[0-9]{1,3}[א-תA-Za-z]?")
// A line number, then where it goes in words (no digits): "349 כוכב יאיר", "370 ת\"א".
private val LINE_TOWARDS = Regex("([0-9]{1,3}[א-תA-Za-z]?)(?![0-9A-Za-zא-ת])((?:\\s+[^\\s0-9][^0-9]*)?)")

// Lines holding only line numbers (several, or one after a line ending with a colon) become one list; the rest stays text.
// In a list (after a colon, or next to a line of it), one line number with where it goes is a part of it too: "205
// towards Yehud", "68 לקריית אונו".
internal fun alertBlocks(text: String): List<AlertBlock> {
    val out = ArrayList<AlertBlock>()
    val prose = ArrayList<String>()
    val numbers = ArrayList<String>()
    val towards = LinkedHashMap<String, String>()
    // A line of line numbers alone, or of parts each a line number and maybe where it goes: "347 / 349 כוכב יאיר / 370".
    fun numbersOf(line: String): List<String>? {
        val tokens = line.split(Regex("[\\s,،;/·•${DASH}-]+")).filter { it.isNotBlank() }
        if (tokens.isNotEmpty() && tokens.all(LINE_NUMBER::matches)) return tokens
        val parts = line.split(Regex("\\s*[/,،;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val found = parts.map { LINE_TOWARDS.matchEntire(it) ?: return null }
        return found.map { m ->
            m.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { towards.putIfAbsent(m.groupValues[1], it) }
            m.groupValues[1]
        }
    }
    fun flushProse() { prose.joinToString("\n").trim().takeIf { it.isNotEmpty() }?.let { out += AlertBlock(it) }; prose.clear() }
    fun flushNumbers() {
        // A line listed once for each way is shown once.
        if (numbers.size >= 2 || numbers.isNotEmpty() && out.lastOrNull()?.text?.trimEnd()?.endsWith(':') == true) out += AlertBlock("", numbers.distinct(), towards.toMap())
        else if (numbers.isNotEmpty()) out += AlertBlock(numbers.joinToString(" "))
        numbers.clear(); towards.clear()
    }
    val stops = ArrayList<Int>()
    fun flushStops() { if (stops.isNotEmpty()) out += AlertBlock("", stops = stops.distinct()); stops.clear() }
    for (line in text.lines()) {
        val stop = STOP_LINE.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
        if (stop != null) {
            // A table's heading over the stops ("מק"ט תחנה  שם תחנה", "Stop code  Stop name"): the rows show both.
            if (stops.isEmpty() && prose.lastOrNull()?.let(TABLE_HEAD::containsMatchIn) == true) prose.removeAt(prose.size - 1)
            flushNumbers(); flushProse(); stops += stop; continue
        }
        if (stops.isNotEmpty()) { if (line.isBlank()) continue; flushStops() }
        val inList = numbers.isNotEmpty() || prose.lastOrNull()?.trimEnd()?.endsWith(':') == true ||
            prose.isEmpty() && out.lastOrNull()?.lines?.isNotEmpty() == true
        val found = numbersOf(line) ?: LINE_TOWARDS.matchEntire(line.trim())?.takeIf { inList && it.groupValues[2].isNotBlank() }?.let { m ->
            towards.putIfAbsent(m.groupValues[1], m.groupValues[2].trim())
            listOf(m.groupValues[1])
        }
        if (found != null) { flushProse(); numbers += found }
        else if (line.isBlank() && numbers.isNotEmpty()) continue
        else { flushNumbers(); prose += line }
    }
    flushStops(); flushNumbers(); flushProse()
    return out
}

private fun alertText(a: RideAlerts.Notice): String? {
    val raw = a.body.takeIf { it.isNotBlank() } ?: return null
    if (!a.html && !raw.contains('<')) return raw.trim().replace(Regex("\n{3,}"), "\n\n")
    return androidx.core.text.HtmlCompat.fromHtml(raw, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT)
        .toString().replace(Regex("\n{3,}"), "\n\n").trim().ifBlank { null }
}

// How many bottom sheets are open. While one is, the tab bar slides away (MainActivity), as it does for paying and
// reports, so a sheet and its scrim are the top of the screen and nothing floats over them.
internal object Sheets {
    var open by mutableIntStateOf(0)
}

@Composable
fun BottomSheet(
    onDismiss: () -> Unit,
    scrolls: Boolean = false,
    content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit,
) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    // Counted from opening until it starts to close, so the bar comes back as the sheet goes down.
    val opened = state.targetState
    DisposableEffect(opened) {
        if (opened) Sheets.open++
        onDispose { if (opened) Sheets.open-- }
    }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val close: (() -> Unit) -> Unit = { then -> if (after == null) { after = then; state.targetState = false } }
    // Only while it's open: one closing leaves Back to the screen under it.
    androidx.activity.compose.BackHandler(enabled = state.targetState) { close(onDismiss) }
    if (state.isIdle && !state.currentState) LaunchedEffect(Unit) { after?.invoke() }
    // Pulled down by its grabber, the sheet follows the finger; let go far enough or fast enough and it closes,
    // otherwise it springs back.
    var pulled by remember { mutableFloatStateOf(0f) }
    val far = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    androidx.compose.animation.AnimatedVisibility(
        state,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = androidx.compose.animation.ExitTransition.None,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier.fillMaxSize()
                    .animateEnterExit(
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
                    )
                    .background(Color.Black.copy(alpha = .4f))
                    .clickable { close(onDismiss) },
            )
            Column(
                Modifier.fillMaxWidth()
                    .animateEnterExit(
                        enter = androidx.compose.animation.slideInVertically(
                            androidx.compose.animation.core.spring(
                                dampingRatio = 0.9f,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
                            ),
                        ) { it },
                        exit = androidx.compose.animation.slideOutVertically(
                            androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing),
                        ) { it },
                    )
                    .offset { androidx.compose.ui.unit.IntOffset(0, pulled.roundToInt()) }
                    // Above the system's bar (and the keyboard); the tab bar is away while a sheet is open.
                    .padding(bottom = bottomCover())
                    .padding(K.gap3)
                    .panel(K.rSheet, solid = true)
                    .clickable(enabled = false) {},
            ) {
                Box(
                    Modifier.fillMaxWidth().height(24.dp).draggable(
                        androidx.compose.foundation.gestures.rememberDraggableState { d -> pulled = (pulled + d).coerceAtLeast(0f) },
                        androidx.compose.foundation.gestures.Orientation.Vertical,
                        onDragStopped = { speed ->
                            if (pulled > far || speed > 1500f) close(onDismiss)
                            else androidx.compose.animation.core.animate(pulled, 0f, speed, spring(dampingRatio = 1f, stiffness = 500f)) { v, _ -> pulled = v }
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(36.dp, 4.dp).clip(K.capsule).background(K.textTertiary.copy(alpha = .5f)))
                }
                Column(
                    Modifier.then(if (scrolls) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(start = K.gap4, end = K.gap4, bottom = K.gap4)
                        .animateContentSize(),
                ) { content(close) }
            }
        }
    }
}

// A stop of Kav's timetable (net, stop), for screens that know the stop by the timetable, or by Kav's own ids. Its
// photo faces it from the road its buses take (lookAt).
@Composable
internal fun StopGlyphOrPhoto(
    net: Net, stop: Int, mode: Mode?, thumb: Dp = 40.dp, mark: Dp = 17.dp, look: StopLook? = null,
    kind: Mode = mode ?: Mode.BUS, opened: Boolean = false,
) {
    val seen = look ?: remember(net, stop) { StopLook(lookAt(net, stop), net.stopName(stop)) }
    StopGlyphOrPhoto(-1, mode, thumb, mark, look = look, kind = kind, opened = opened, photo = seen)
}

// A station by its mode's mark; a stop by its photo from Street View (photo, where there's a place to look at), else
// a tile that opens Street View (look), else by its mode (kind).
@Composable
internal fun StopGlyphOrPhoto(
    stopId: Int, mode: Mode?, thumb: Dp = 40.dp, mark: Dp = 17.dp, resolving: Boolean = false, look: StopLook? = null,
    kind: Mode = mode ?: Mode.BUS, opened: Boolean = false, photo: StopLook? = look,
) {
    val asked = photo?.takeIf { mode == null && StopPhotos.enabled }
    if (asked != null) {
        val key = remember(asked) { StopPhotos.key(asked.look) }
        var shot by remember(key) { mutableStateOf(StopPhotos.thumbNow(asked.look)) }
        var none by remember(key) { mutableStateOf(false) }
        // On a weak connection the photo is asked again, less and less often, for as long as the stop is on screen.
        LaunchedEffect(key) {
            var wait = 2_000L
            while (shot == null && !none) {
                try {
                    val got = StopPhotos.thumb(asked.look)
                    if (got == null) none = true else shot = got
                } catch (e: java.io.IOException) {
                    kotlinx.coroutines.delay(wait)
                    wait = (wait * 2).coerceAtMost(60_000L)
                }
            }
        }
        if (!none) {
            androidx.compose.animation.Crossfade(shot, animationSpec = tween(220), label = "stopPhoto") { p ->
                if (p == null) StopTile(thumb, kind) else StopPhotoTile(asked, p, thumb)
            }
            return
        }
    }
    when {
        mode != null -> StationMark(mode, mark)
        look != null -> NoPhoto(thumb, look)
        else -> StopMark(thumb, kind)
    }
}

// A place by the photo Google Maps shows of it, where photos are on and Google has one; until then, and else, its
// glyph on a quiet tile of the photo's size and shape, as a stop has. The photo fades in over the tile as it comes, and
// tapped (open), shows large.
@Composable
internal fun PlaceGlyphOrPhoto(
    place: uk.noammm.kav.data.Transit.Place, thumb: Dp = 40.dp, open: Boolean = true, glyph: @Composable () -> Unit,
) {
    val asked = place.takeIf { StopPhotos.enabled && uk.noammm.kav.data.PlacePhotos.worthAsking(it) }
    val key = remember(place.name, place.lat, place.lon, place.googleId) { uk.noammm.kav.data.PlacePhotos.key(place) }
    var shot by remember(key) { mutableStateOf(asked?.let { uk.noammm.kav.data.PlacePhotos.thumbNow(it) }) }
    var none by remember(key) { mutableStateOf(false) }
    // On a weak connection Google is asked again, less and less often, for as long as the place is on screen; a row only
    // passing by while a search is typed asks nothing.
    if (asked != null) LaunchedEffect(key) {
        var wait = 2_000L
        while (shot == null && !none) {
            try {
                val got = uk.noammm.kav.data.PlacePhotos.thumb(asked, wait = 300)
                if (got == null) none = true else shot = got
            } catch (e: java.io.IOException) {
                kotlinx.coroutines.delay(wait)
                wait = (wait * 2).coerceAtMost(60_000L)
            }
        }
    }
    androidx.compose.animation.Crossfade(shot.takeIf { asked != null }, animationSpec = tween(220), label = "placePhoto") { b ->
        if (b == null) Box(
            Modifier.size(thumb).clip(RoundedCornerShape(K.rTile)).background(K.fill),
            contentAlignment = Alignment.Center,
        ) { glyph() }
        else PlacePhotoTile(place, b, thumb, open)
    }
}

// Where a trip starts or ends, as its tile shows it: one of the rider's favourites (by its label, or its place within a
// few metres) by the favourite's own icon, as Home's grid has it, never looked up; another named place by Google Maps'
// photo of it; else none (where you are, a pinned spot).
internal sealed interface TripEnd {
    class Favourite(val icon: String) : TripEnd
    class Place(val place: uk.noammm.kav.data.Transit.Place) : TripEnd
}

@Composable
internal fun rememberTripEnd(label: String, at: Pair<Double, Double>?): TripEnd? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return remember(label, at) {
        // The favourite named so first, else the one saved nearest the end within a few metres: the first to match by
        // either let another favourite saved nearby, listed before it, take an end the rider named outright.
        val favourites = uk.noammm.kav.Prefs.favourites(ctx)
        val favourite = favourites.firstOrNull { it.label == label } ?: at?.let { (lat, lon) ->
            favourites.mapNotNull { f -> f.place?.let { f to metres(it.lat, it.lon, lat, lon) } }
                .filter { it.second <= FAVOURITE_NEAR_M }.minByOrNull { it.second }?.first
        }
        when {
            favourite != null -> TripEnd.Favourite(favourite.icon)
            at == null -> null
            else -> uk.noammm.kav.data.Transit.Place(label, "", at.first, at.second)
                .takeIf { uk.noammm.kav.data.PlacePhotos.worthAsking(it) }?.let { TripEnd.Place(it) }
        }
    }
}

// A saved place's own icon on its tile, as the favourites grid draws it, at this size.
@Composable
internal fun FavouriteTile(icon: String, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(K.rTile)).background(K.fill), contentAlignment = Alignment.Center) {
        Icon(favouriteIcon(icon), contentDescription = null, tint = K.text, modifier = Modifier.size(size / 2))
    }
}

// How far a trip's end can be from a favourite's place and still be that favourite.
private const val FAVOURITE_NEAR_M = 30.0

// glyph: a place's while its photo comes or where it has none.
@Composable
internal fun TripEndTile(
    end: TripEnd, thumb: Dp = 40.dp, open: Boolean = true,
    glyph: @Composable () -> Unit = {
        Icon(Icons.Rounded.Place, contentDescription = null, tint = K.muted, modifier = Modifier.size(18.dp))
    },
) {
    when (end) {
        is TripEnd.Favourite -> FavouriteTile(end.icon, thumb)
        is TripEnd.Place -> PlaceGlyphOrPhoto(end.place, thumb, open, glyph)
    }
}

// The place's photo; tapped, the photo large, as a stop's is. A place's tiles are few, so its large photo is fetched as
// soon as the tile shows.
@Composable
private fun PlacePhotoTile(place: uk.noammm.kav.data.Transit.Place, shot: android.graphics.Bitmap, thumb: Dp, open: Boolean) {
    val said = T("A photo of ${place.name}", "תמונה של ${place.name}")
    PhotoTile(shot, said, said, thumb, open, ahead = true, key = place) { uk.noammm.kav.data.PlacePhotos.full(place) }
}

// A photo on its tile; tapped, the photo large. The large one opens only once it's in, so a photo never sharpens in front
// of you: until then a small ring turns on the tile. Where it can't be had, it opens with the photo there is and a way to
// try again, which closes it and opens it afresh once the large one is in. ahead: fetch the large one as soon as the tile
// shows (a screen's few tiles), else on the tap (a list's many). onLarge: a tap on the large photo, besides closing it.
@Composable
private fun PhotoTile(
    shot: android.graphics.Bitmap, said: String, largeSaid: String, thumb: Dp, open: Boolean, ahead: Boolean, key: Any,
    largeAction: String? = null, onLarge: () -> Unit = {}, fetch: suspend () -> android.graphics.Bitmap?,
) {
    var full by remember(key) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    var waiting by remember(key) { mutableStateOf(false) }
    var large by remember(key) { mutableStateOf(false) }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, ahead || waiting, attempt) {
        if (!(ahead || waiting) || full != null) return@LaunchedEffect
        val got = runCatching { fetch() }.getOrNull()
        if (got != null) full = got else failed = true
    }
    // A tap made while the large photo comes opens it the moment it's in.
    LaunchedEffect(waiting, full, failed) { if (waiting && (full != null || failed)) { waiting = false; large = true } }
    Box(
        Modifier.size(thumb).clip(RoundedCornerShape(K.rTile))
            .then(if (open) Modifier.clickable(role = Role.Button, onClickLabel = largeSaid) {
                if (full != null) large = true
                else { if (failed) { failed = false; attempt++ }; waiting = true }
            } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(shot.asImageBitmap(), said, Modifier.matchParentSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        androidx.compose.animation.AnimatedVisibility(
            waiting, Modifier.matchParentSize(),
            enter = androidx.compose.animation.fadeIn(tween(150)), exit = androidx.compose.animation.fadeOut(tween(150)),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
                Spinner(thumb * .4f, track = Color.White.copy(alpha = .3f), arc = Color.White)
            }
        }
    }
    if (!large) return
    androidx.compose.ui.window.Dialog(onDismissRequest = { large = false }) {
        // The button goes under the photo: on it, a busy street showed through and drowned it out.
        Column {
            androidx.compose.foundation.Image(
                (full ?: shot).asImageBitmap(), largeSaid,
                Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(K.rSheet))
                    .clickable(role = Role.Button) { large = false; onLarge() },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            if (full == null) KavButton(
                T("Try again", "נסו שוב"), { large = false; failed = false; attempt++; waiting = true },
                Modifier.padding(top = K.gap3), style = ButtonStyle.Secondary, wide = true,
            )
            else if (largeAction != null) KavButton(
                largeAction, { large = false; onLarge() },
                Modifier.padding(top = K.gap3), style = ButtonStyle.Secondary, wide = true,
            )
        }
    }
}

// Where a stop's photo goes while it comes: the stop's mode on a quiet tile of the photo's size and shape.
@Composable
private fun StopTile(size: Dp, mode: Mode) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(K.rTile)).background(K.fill),
        contentAlignment = Alignment.Center,
    ) { ModeGlyph(mode, K.muted, size * .44f) }
}

// The stop's photo; tapped, the photo large, with Street View a tap further. A list holds many stops, so the large one
// is fetched on the tap.
@Composable
private fun StopPhotoTile(stop: StopLook, shot: StopPhotos.Photo, thumb: Dp) {
    PhotoTile(
        shot.bitmap, T("A photo of the stop", "תמונה של התחנה"), T("Open stop photo", "פתיחת תמונת התחנה"), thumb,
        open = true, ahead = false, key = StopPhotos.key(stop.look),
        largeAction = T("Street View", "Street View"), onLarge = { StreetViewer.open = stop },
    ) { StopPhotos.full(stop.look)?.bitmap }
}

// Where no photo is asked for: the stop's sign, as a trip's steps draw it, on its own in the photo's place.
@Composable
private fun StopMark(size: Dp, mode: Mode) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) { StationMark(mode, size * .7f) }
}

// A stop with no photo: Street View's glyph on the tile, which opens it there.
@Composable
private fun NoPhoto(thumb: Dp, look: StopLook) {
    Box(
        Modifier.size(thumb).clip(RoundedCornerShape(K.rTile)).background(K.fill)
            .semantics { contentDescription = T("No photo of this stop. See it in Street View", "אין תמונה של התחנה. צפייה ב-Street View") }
            .clickable(role = Role.Button) { StreetViewer.open = look },
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(thumb * .5f)) { streetGlyph(K.muted) } }
}

// A small turning arc, for something loading in its place.
@Composable
internal fun Spinner(size: Dp, track: Color = K.surface4, arc: Color = K.accent) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "turn",
    )
    Canvas(Modifier.size(size)) {
        val w = 2.5.dp.toPx()
        val box = Size(this.size.width - w, this.size.height - w)
        drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), box, style = Stroke(w))
        drawArc(arc, turn, 100f, false, Offset(w / 2, w / 2), box, style = Stroke(w, cap = StrokeCap.Round))
    }
}
