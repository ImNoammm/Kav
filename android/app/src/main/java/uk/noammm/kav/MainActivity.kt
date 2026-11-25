package uk.noammm.kav

import androidx.core.view.doOnPreDraw
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.animation.animateColorAsState
import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Rational
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.noammm.kav.data.Backup
import uk.noammm.kav.data.JourneyFile
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.LineAlert
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.GoogleMaps
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.TripLink
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.RecentRoutes
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.Sources
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.nearestStops
import uk.noammm.kav.data.stopOfKey
import uk.noammm.kav.data.Updates
import uk.noammm.kav.ui.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

enum class Tab {
    // Ext: the one extension page the rider pinned as a tab (ExtShown.tab), in the bar only while there is one.
    Directions, Stations, Lines, Live, Ext;

    val label: String get() = when (this) {
        Directions -> T("Home", "בית")
        Stations -> T("Stops", "תחנות")
        Lines -> T("Lines", "קווים")
        Live -> T("Live", "בזמן אמת")
        Ext -> uk.noammm.kav.ui.ExtShown.tab?.label.orEmpty()
    }
}

object Loaded {
    // State, so what's drawn from the timetable (a bus station's platforms) comes in once it has loaded.
    private var net_ by mutableStateOf<Net?>(null)

    val net: Net? get() = net_

    fun store(net: Net) { net_ = net }

    fun clear() { net_ = null }
}

object Pip {
    var active by mutableStateOf(false)
    var wanted by mutableStateOf(false)
    // The rider's setting: navigation goes on in a small window when Kav is left, or doesn't.
    var allowed by mutableStateOf(true)
}

object PendingLink {
    var plan by mutableStateOf<TripLink.Plan?>(null)
}

// A line alert's stop, opened on the Stations tab when the alert is tapped.
object PendingStop {
    var key by mutableStateOf<String?>(null)
}

// A stop (by its code) or a line number tapped in a service alert: opened on its tab, and back from there returns to
// where the alert was opened, with the alert open again.
object AlertLink {
    var stop by mutableStateOf<Int?>(null)
    var line by mutableStateOf<String?>(null)
    var alert: uk.noammm.kav.data.RideAlerts.Alert? = null
}

object PendingBackup {
    var uri by mutableStateOf<android.net.Uri?>(null)

    fun offer(ctx: android.content.Context, uri: android.net.Uri?): Boolean {
        if (uri == null || !Backup.looksLikeBackup(ctx, uri)) return false
        this.uri = uri
        return true
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && uk.noammm.kav.ext.SafeStart.take(this, intent)) return
        Openings.count++
        // A new activity after one whose spare map was let go in the background (onTrimMemory) never gets onRestart.
        uk.noammm.kav.ui.SpareMap.back()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The launch screen's arrow flies up and away as Home fades in under it; Kav is already drawn by now.
            // The arrow is its own surface, so the screen's fade doesn't reach it: it fades by itself.
            if (savedInstanceState == null) {
                Openings.launched = false
                // In case the launch screen never hands over, 1.5 s from Kav's first frame, not from here: a slow
                // phone can take longer than that just to draw it.
                window.decorView.doOnPreDraw { it.postDelayed({ Openings.launched = true }, 1500) }
            }
            splashScreen.setOnExitAnimationListener { splash ->
                // The arrow travels first and fades last, so the fly-up is seen before it's gone.
                val out = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
                splash.iconView?.let { icon ->
                    android.animation.AnimatorSet().apply {
                        playTogether(
                            android.animation.ObjectAnimator.ofFloat(icon, android.view.View.TRANSLATION_Y, -64 * resources.displayMetrics.density).setDuration(380),
                            android.animation.ObjectAnimator.ofFloat(icon, android.view.View.SCALE_X, 0.85f).setDuration(380),
                            android.animation.ObjectAnimator.ofFloat(icon, android.view.View.SCALE_Y, 0.85f).setDuration(380),
                            android.animation.ObjectAnimator.ofFloat(icon, android.view.View.ALPHA, 0f).setDuration(230).apply { startDelay = 150 },
                        )
                        interpolator = out
                    }.start()
                }
                splash.animate().alpha(0f).setStartDelay(150).setDuration(250).setInterpolator(out)
                    .withEndAction { splash.remove(); Openings.launched = true }.start()
            }
        }
        enableEdgeToEdge()
        Prefs.upgrade(this)
        K.accent = Color(Prefs.accent(this))
        K.applyTheme(Prefs.look(this))
        K.followsSystem = Prefs.followsSystem(this)
        K.liquid = Prefs.liquidGlass(this)
        K.lineBadgeAppearance = Prefs.lineBadges(this)
        Shown.co2 = Prefs.showCo2(this)
        Shown.accessibility = Prefs.showAccessibility(this)
        Shown.fareProfile = Prefs.fareProfile(this)
        uk.noammm.kav.ui.FareFromRavPass.load(this)
        uk.noammm.kav.data.RideAlerts.enabled = Prefs.rideAlerts(this)
        uk.noammm.kav.data.RideAlerts.picked = Prefs.alertSources(this)
        uk.noammm.kav.data.RideAlerts.language = Prefs.alertLanguage(this)
        uk.noammm.kav.data.GoogleSession.init(this)
        uk.noammm.kav.data.GoogleCrowd.init(this)
        Prefs.crowdSource(this).let { c -> uk.noammm.kav.data.GoogleCrowd.enabled = c == Prefs.CROWD_GOOGLE; uk.noammm.kav.data.KavCrowd.enabled = c == Prefs.CROWD_KAV }
        uk.noammm.kav.ui.StopPhotos.enabled = Prefs.stopPhotos(this)
        Shown.updatePrompts = Prefs.updatePrompts(this)
        // An extension taking over paying, or letting it go: the pay buttons follow (PayMode).
        val app = applicationContext
        uk.noammm.kav.ext.Actions.changed = { android.os.Handler(android.os.Looper.getMainLooper()).post { uk.noammm.kav.ui.PayMode.refresh(app) } }
        uk.noammm.kav.ui.PayMode.refresh(this)
        Pip.allowed = Prefs.pipNavigation(this)
        Shown.twelveHour = Prefs.twelveHour(this)
        GoogleMaps.shareLocation = !Prefs.privateSearch(this)
        uk.noammm.kav.data.Search.provider = uk.noammm.kav.data.Search.of(Prefs.placeSearch(this))
        Prefs.loadSources(this)
        StopNames.english = Prefs.stopNamesEnglish(this)
        MapFile.type = Prefs.mapType(this)
        MapFile.labels = Prefs.mapLabels(this)
        MapFile.buildings3d = Prefs.buildings3d(this)
        MapFile.init(this)
        uk.noammm.kav.data.Timetable.init(this)
        uk.noammm.kav.data.Schedules.init(this)
        KavPlan.init(this)
        uk.noammm.kav.ui.StopPhotos.init(this)
        uk.noammm.kav.data.PlacePhotos.init(this)
        // Home's card opens with the latest route and its end's photo already read.
        Thread { uk.noammm.kav.ui.LatestRoute.preload(applicationContext) }.start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            addOnPictureInPictureModeChangedListener { Pip.active = it.isInPictureInPictureMode }
        }
        T.lang = Prefs.lang(this)
        // Restored, or reopened from Recents: the link or backup was already handled.
        val fresh = savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh && !uk.noammm.kav.ui.PendingExtension.offer(intent) && !PendingBackup.offer(this, intent?.data)) linkOf(intent)?.let { PendingLink.plan = it }
        if (fresh) intent?.getStringExtra(LineAlerts.EXTRA_STOP)?.let { PendingStop.key = it }
        if (fresh) intent?.getStringExtra(TicketReader.EXTRA_TICKET)?.let { TicketStore.opened = it }
        if (fresh) PayEntry.take(this, intent)
        if (fresh) ExtWidgets.take(this, intent)
        // A force stop drops the line alerts' alarm.
        LineAlerts.reschedule(this)
        // Updates in the background, which must know when Kav is in front so as not to run then.
        Checks.watch(this)
        setContent {
            val light = K.light
            val bg = K.bg
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                val bars = androidx.core.view.WindowCompat.getInsetsController(window, view)
                bars.isAppearanceLightStatusBars = light
                bars.isAppearanceLightNavigationBars = light
                // Before Android 10 the navigation bar keeps a light scrim behind dark-theme icons.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) window.navigationBarColor = bg.toArgb()
            }
            KavTheme {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (T.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) { LanguageSwitch { ServiceAlerts { Root() } } }
            }
        }
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(Pip.wanted).setSeamlessResizeEnabled(false)
        runCatching { setPictureInPictureParams(builder.build()) }
    }

    // Kav's task in split screen or not, for the button over Rav-Pass in it (PayOverlay.split).
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        uk.noammm.kav.ui.PayOverlay.split = isInMultiWindowMode
    }

    // Kav's own screen in front again: the button over Rav-Pass goes at once.
    override fun onResume() {
        super.onResume()
        uk.noammm.kav.ext.Actions.resumed(this)
        uk.noammm.kav.ui.PayOverlay.disarm()
        uk.noammm.kav.ui.PayOverlay.split = isInMultiWindowMode
        // A permission "Pay with Rav-Pass" needs may have been taken away, or given, while Kav was away.
        uk.noammm.kav.ui.PayMode.refresh(this)
        PayEntry.resumed(this)
    }

    override fun onPause() {
        super.onPause()
        uk.noammm.kav.ext.Actions.paused(this)
    }

    // Kav out of sight, with paying maybe switched in its settings: the shortcuts, tile and ticket notification follow.
    override fun onStop() {
        super.onStop()
        PayEntry.sync(this)
        // Extensions' home screen widgets drawn again, in the look the rider may have changed.
        ExtWidgets.left(this)
    }

    // Kav opened: extensions' home screen widgets asked for their content again.
    override fun onStart() {
        super.onStart()
        ExtWidgets.opened(this)
    }

    // Back to the front from another app: an opening too (Home's "Where to?" types itself once per opening).
    override fun onRestart() {
        super.onRestart()
        Openings.count++
        uk.noammm.kav.ui.SpareMap.back()
    }

    // The phone short of memory with Kav in the background: the map kept drawn for the next screen goes, until Kav is
    // back. A Kav that takes less room there is one Android is slower to close.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) uk.noammm.kav.ui.SpareMap.drop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Pip.wanted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(2, 1)).build()) }
        }
    }

    // Kav takes changes of the phone's dark theme itself (uiMode, in the manifest), so nothing starts again: the System look
    // goes with it here, as it changes.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val look = Prefs.look(this)
        if (K.followsSystem && look != K.look) K.restyle { K.applyTheme(look) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (uk.noammm.kav.ext.SafeStart.take(this, intent)) return
        if (PayEntry.take(this, intent)) return
        if (ExtWidgets.take(this, intent)) return
        intent.getStringExtra(TicketReader.EXTRA_TICKET)?.let { TicketStore.opened = it; return }
        intent.getStringExtra(LineAlerts.EXTRA_STOP)?.let { PendingStop.key = it; return }
        if (uk.noammm.kav.ui.PendingExtension.offer(intent)) return
        if (PendingBackup.offer(this, intent.data)) return
        linkOf(intent)?.let { PendingLink.plan = it }
    }

    // A link opened in Kav, or the first link that opens a trip in a message shared to Kav. A message without one says so,
    // as does a link Kav doesn't open (one naming a server that isn't Kav's or the rider's own): nothing is asked of it.
    private fun linkOf(intent: Intent?): TripLink.Plan? {
        if (intent?.action != Intent.ACTION_SEND) return TripLink.parse(intent?.dataString) ?: run {
            if (intent?.dataString?.let { Regex("^(kav|https?)://", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true)
                android.widget.Toast.makeText(this, T("Couldn't open this shared trip.", "לא ניתן לפתוח את הנסיעה המשותפת."),
                    android.widget.Toast.LENGTH_LONG).show()
            null
        }
        return TripLink.inText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()) ?: run {
            android.widget.Toast.makeText(this, T("There's no trip link in what was shared.", "אין קישור לנסיעה במה ששותף."),
                android.widget.Toast.LENGTH_LONG).show()
            null
        }
    }
}

data class ActiveJourney(
    val trip: Transit.Itinerary,
    val resolved: Transit.Resolved,
    val fromLabel: String,
    val toLabel: String,
    val chosen: Map<Int, Int> = emptyMap(),
    // The rider began the trip where it starts: leaving that spot early starts it. A trip from elsewhere waits for its time.
    val fromHere: Boolean = true,
    // The stops on the way it was planned through (a search with "Add a stop"), so it's planned through them again.
    val stopovers: List<Transit.Place> = emptyList(),
    // The trips this one replaced, planned again on the way (Replan): a screen showing one of them shows this one.
    val before: List<Transit.Itinerary> = emptyList(),
)

// A search that found routes: where from and to, planned afresh when opened again.
data class RecentTrip(
    val from: Transit.Place?,
    val to: Transit.Place,
    val at: Long,
    val stopovers: List<Transit.Place> = emptyList(),
    // The extension that planned it (its namespace), which alone plans it again; empty for Kav's planners.
    val planner: String = "",
)

// What search is told about where the user is while private search is on.
enum class Seen { NONE, CITY, PLACE }

class KavModel(net: Net? = null, ctx: Context? = null) : ViewModel() {
    var net by mutableStateOf(net)
    var netLoading by mutableStateOf(false)
    var netError by mutableStateOf<String?>(null)
    var netLoadAttempt by mutableIntStateOf(0)

    var tab by mutableStateOf(Tab.Directions)
    var settingsOpen by mutableStateOf(false)
    // The bus being reported to the ministry, while its report is open.
    internal var report by mutableStateOf<uk.noammm.kav.ui.ReportFor?>(null)
    // A report opened by a tap: an extension holding "report" goes first, and Kav's own opens the last one asked.
    private var reportAsked: uk.noammm.kav.ui.ReportFor? = null
    internal fun openReport(r: uk.noammm.kav.ui.ReportFor) {
        reportAsked = r
        if (!uk.noammm.kav.ext.Actions.take(null, "report") { uk.noammm.kav.ui.reportArgs(r) }) report = r
    }
    internal fun reportOwn() { reportAsked?.let { report = it } }
    var activeJourney by mutableStateOf<ActiveJourney?>(null)
    // A trip started away from Directions (Get off here, on a line's page), for Directions to open under way.
    var openJourney by mutableStateOf(false)
    var returnHome by mutableStateOf(false)

    var navigating by mutableStateOf(false)
    var liveShow by mutableStateOf(LiveShow.ALL)

    var journeyStep by mutableIntStateOf(0)
    // The step card navigation shows, which the rider can swipe away from the trip's step to look at another; -1 while
    // navigation isn't open. Told to extensions following the trip (TripEvents).
    var journeyShown by mutableIntStateOf(-1)

    var pendingFrom by mutableStateOf<Transit.Place?>(null)
    var pendingTo by mutableStateOf<Transit.Place?>(null)
    var pendingLink by mutableStateOf<TripLink.Plan?>(null)
    var settingFavourite by mutableStateOf<uk.noammm.kav.ui.Favourite?>(null)

    // The rider's saved places, stops and lines, each kept here: an extension holding "place.save", "stop.save" or
    // "line.save" goes first, told the whole list as it would be kept.
    var favourites by mutableStateOf(ctx?.let { Prefs.favourites(it) } ?: emptyList())
    fun saveFavourites(ctx: Context, list: List<uk.noammm.kav.ui.Favourite>) {
        if (uk.noammm.kav.ext.Actions.take(null, "place.save") { org.json.JSONObject().put("places", org.json.JSONArray(list.map { f ->
                org.json.JSONObject().put("id", f.id).put("name", f.name).put("lat", f.place?.lat).put("lon", f.place?.lon)
            })) }) { placesAsked = list; return }
        saveFavouritesOwn(ctx, list)
    }
    // The list an extension was last asked to keep, for Kav's own (Kav.kavAction "place.save").
    var placesAsked: List<uk.noammm.kav.ui.Favourite>? = null
    fun saveFavouritesOwn(ctx: Context, list: List<uk.noammm.kav.ui.Favourite>) {
        favourites = list
        Prefs.saveFavourites(ctx, list)
    }

    var savedStops by mutableStateOf(ctx?.let { Prefs.savedStops(it) } ?: emptyList())
    fun saveStops(ctx: Context, keys: List<String>) {
        if (uk.noammm.kav.ext.Actions.take(null, "stop.save") { org.json.JSONObject().put("stops", org.json.JSONArray(keys)) }) return
        saveStopsOwn(ctx, keys)
    }
    fun saveStopsOwn(ctx: Context, keys: List<String>) { savedStops = keys; Prefs.setSavedStops(ctx, keys) }
    var savedLines by mutableStateOf(ctx?.let { Prefs.savedLines(it) } ?: emptyList())
    fun saveLines(ctx: Context, keys: List<String>) {
        if (uk.noammm.kav.ext.Actions.take(null, "line.save") { org.json.JSONObject().put("lines", org.json.JSONArray(keys)) }) return
        saveLinesOwn(ctx, keys)
    }
    fun saveLinesOwn(ctx: Context, keys: List<String>) { savedLines = keys; Prefs.setSavedLines(ctx, keys) }
    var lineAlerts by mutableStateOf(ctx?.let { Prefs.lineAlerts(it) } ?: emptyList())
    fun saveAlerts(ctx: Context, alerts: List<LineAlert>) {
        lineAlerts = alerts
        Prefs.setLineAlerts(ctx, alerts)
        LineAlerts.reschedule(ctx)
    }

    var stationStop by mutableIntStateOf(-1)
    var lineRoute by mutableIntStateOf(-1)
    // The stop to open on the line shown, when it was opened from a trip.
    var lineFocusStop by mutableIntStateOf(-1)
    // A line from the timetable's provider (Schedules) in place of the week's, the provider it came from, and the code
    // of the stop to open on it.
    var scheduleLine by mutableStateOf<uk.noammm.kav.data.Schedules.Line?>(null)
    var scheduleSource by mutableStateOf(Source.KAV)
    var scheduleStop by mutableIntStateOf(-1)
    private data class ReturnPage(
        val destination: Tab, val tab: Tab, val stationStop: Int, val lineRoute: Int,
        val lineFocusStop: Int, val scheduleLine: uk.noammm.kav.data.Schedules.Line?,
        val scheduleSource: Source, val scheduleStop: Int,
    )
    private val returns = ArrayDeque<ReturnPage>()
    fun rememberReturn(destination: Tab) {
        if (tab != destination) returns.addLast(ReturnPage(
            destination, tab, stationStop, lineRoute, lineFocusStop, scheduleLine, scheduleSource, scheduleStop,
        ))
    }
    private fun returnTo(destination: Tab): Boolean {
        val page = returns.lastOrNull()?.takeIf { it.destination == destination } ?: return false
        returns.removeLast()
        stationStop = page.stationStop; lineRoute = page.lineRoute; lineFocusStop = page.lineFocusStop
        scheduleLine = page.scheduleLine; scheduleSource = page.scheduleSource; scheduleStop = page.scheduleStop
        tab = page.tab
        return true
    }
    fun backFromLine() { if (!returnTo(Tab.Lines)) { lineRoute = -1; scheduleLine = null } }
    // Whether back from the tab goes to where it was opened from (a line found from an alert, say).
    fun returnsTo(destination: Tab) = returns.lastOrNull()?.destination == destination
    fun backFromStation() { if (!returnTo(Tab.Stations)) stationStop = -1 }
    // A stop's board opened by a tap: an extension holding "stop.open" goes first (ext/Actions.kt).
    fun openStation(stop: Int) { if (!uk.noammm.kav.ui.stopTaken(this, stop)) openStationOwn(stop) }
    fun openStationOwn(stop: Int) {
        rememberReturn(Tab.Stations)
        stationStop = stop; tab = Tab.Stations
    }
    // A tab tapped, Kav's own (also for an extension's Kav.kavAction "tab.open").
    fun openTab(t: Tab) {
        val on = tab == t
        settingsOpen = false
        if (on) resetOpenPages()
        // Home goes back to its start when tapped while on it; from another tab it's where it was left.
        if (t == Tab.Directions && on) returnHome = true
        tab = t
    }
    fun resetOpenPages() {
        stationStop = -1; lineRoute = -1; scheduleLine = null; returns.clear()
    }
    fun openSchedule(line: uk.noammm.kav.data.Schedules.Line, code: Int) {
        if (uk.noammm.kav.ui.lineTaken(this, line.number, net?.code?.indexOfFirst { it == code } ?: -1)) return
        rememberReturn(Tab.Lines)
        scheduleLine = line; scheduleSource = uk.noammm.kav.data.Schedules.source; scheduleStop = code
        lineRoute = -1; tab = Tab.Lines
    }

    var stopQuery by mutableStateOf("")
    var lineQuery by mutableStateOf("")
    var placeQuery by mutableStateOf("")

    var fix by mutableStateOf<Fix?>(null)
    var here by mutableStateOf<Pair<Double, Double>?>(null)
    private var hereAt by mutableLongStateOf(0L)
    var restored by mutableStateOf(false)
    var heading by mutableStateOf<Float?>(null)
    // A trip's map is on screen before the trip starts: it shows where you are and which way you face.
    var tripMapShown by mutableStateOf(false)
    // A line's page is open, with its map: the same, for where you are on the line.
    var lineMapShown by mutableStateOf(false)

    var filters by mutableStateOf(ctx?.let { Prefs.filters(it) } ?: ResultFilter.entries.toSet())
        private set

    fun setFilter(ctx: Context, f: ResultFilter, on: Boolean) {
        filters = if (on) filters + f else filters - f
        Prefs.setFilter(ctx, f, on)
    }

    var prefsVersion by mutableIntStateOf(0)
        private set

    fun reloadPrefs(ctx: Context) {
        prefsVersion++
        favourites = Prefs.favourites(ctx)
        savedStops = Prefs.savedStops(ctx)
        savedLines = Prefs.savedLines(ctx)
        lineAlerts = Prefs.lineAlerts(ctx)
        LineAlerts.reschedule(ctx)
        filters = Prefs.filters(ctx)
        K.accent = Color(Prefs.accent(ctx))
        K.applyTheme(Prefs.look(ctx))
        K.followsSystem = Prefs.followsSystem(ctx)
        K.liquid = Prefs.liquidGlass(ctx)
        K.lineBadgeAppearance = Prefs.lineBadges(ctx)
        Shown.co2 = Prefs.showCo2(ctx)
        Shown.accessibility = Prefs.showAccessibility(ctx)
        Shown.fareProfile = Prefs.fareProfile(ctx)
        uk.noammm.kav.ui.FareFromRavPass.load(ctx)
        uk.noammm.kav.data.RideAlerts.enabled = Prefs.rideAlerts(ctx)
        uk.noammm.kav.data.RideAlerts.picked = Prefs.alertSources(ctx)
        uk.noammm.kav.data.RideAlerts.language = Prefs.alertLanguage(ctx)
        Prefs.crowdSource(ctx).let { c -> uk.noammm.kav.data.GoogleCrowd.enabled = c == Prefs.CROWD_GOOGLE; uk.noammm.kav.data.KavCrowd.enabled = c == Prefs.CROWD_KAV }
        uk.noammm.kav.ui.StopPhotos.enabled = Prefs.stopPhotos(ctx)
        Shown.updatePrompts = Prefs.updatePrompts(ctx)
        uk.noammm.kav.ui.PayMode.refresh(ctx)
        Checks.ensure(ctx)
        Pip.allowed = Prefs.pipNavigation(ctx)
        Shown.twelveHour = Prefs.twelveHour(ctx)
        GoogleMaps.shareLocation = !Prefs.privateSearch(ctx)
        uk.noammm.kav.data.Search.provider = uk.noammm.kav.data.Search.of(Prefs.placeSearch(ctx))
        Prefs.loadSources(ctx)
        StopNames.english = Prefs.stopNamesEnglish(ctx)
        MapFile.type = Prefs.mapType(ctx)
        MapFile.labels = Prefs.mapLabels(ctx)
        MapFile.buildings3d = Prefs.buildings3d(ctx)
        seen = Prefs.seen(ctx)
        seenPlace = Prefs.seenPlace(ctx)
        T.switchTo(Prefs.lang(ctx))
    }

    // One-off positions for maps and planning; only tracked fixes move a trip's steps on.
    fun locate(lat: Double, lon: Double) {
        here = lat to lon
        hereAt = System.currentTimeMillis() / 1000
        roughFix = Fix(lat, lon, hereAt)
    }

    fun track(lat: Double, lon: Double, speed: Float, at: Long, accuracy: Float) {
        here = lat to lon
        hereAt = at
        fix = Fix(lat, lon, at, speed, accuracy)
        stepFix = fix
    }

    // A one-off or rough position can guide the camera. Only a rough one that was tracked moves the trip's steps on (stepFix).
    var roughFix by mutableStateOf<Fix?>(null)
        private set

    fun glimpse(lat: Double, lon: Double, speed: Float, at: Long, accuracy: Float) {
        here = lat to lon
        hereAt = at
        roughFix = Fix(lat, lon, at, speed, accuracy)
        stepFix = roughFix
    }

    // What moves the trip's steps on: the latest tracked fix, a rough one too, as the steps allow for how rough it is.
    var stepFix: Fix? = null
        private set

    // Recent enough to plan a trip from.
    val hereFresh: Boolean get() = System.currentTimeMillis() / 1000 - hereAt <= 120

    var seen by mutableStateOf(ctx?.let { Prefs.seen(it) } ?: Seen.CITY)
        private set
    var seenPlace by mutableStateOf(ctx?.let { Prefs.seenPlace(it) })
        private set
    @Volatile private var city: Transit.Place? = null

    fun setSeen(ctx: Context, how: Seen, place: Transit.Place? = seenPlace) {
        seen = how
        seenPlace = place
        Prefs.setSeen(ctx, how, place)
    }

    fun standIn(ctx: Context): Pair<Double, Double>? = when (seen) {
        Seen.NONE -> null
        Seen.CITY -> cityAround(ctx)?.let { it.lat to it.lon }
        Seen.PLACE -> seenPlace?.let { it.lat to it.lon }
    }

    // The centre of the town or city the user is in, found on the phone from the nearest stops'
    // locality, or of the closest one when none of those has a locality. Needs the timetable loaded.
    fun cityAround(ctx: Context): Transit.Place? {
        val net = Loaded.net
        val at = here
        if (net != null && at != null) {
            val own = net.nearestStops(at.first, at.second, k = 8)
                .firstOrNull { net.cityOf(it.first).isNotBlank() }?.first?.let { net.cityOf[it] }
            val c = own ?: net.cityCentre.indices.filter { net.cityCentre[it] != null }
                .minByOrNull { net.cityCentre[it]!!.let { p -> metres(at.first, at.second, p.first, p.second) } }
            val centre = c?.let { net.cityCentre.getOrNull(it) }
            if (c != null && centre != null) {
                val found = Transit.Place(net.city[c], "", centre.first, centre.second)
                if (found.name != city?.name) Prefs.setSeenCity(ctx, found)
                city = found
                return found
            }
        }
        return lastCity(ctx)
    }

    fun lastCity(ctx: Context): Transit.Place? = city ?: Prefs.seenCity(ctx).also { city = it }

    var update by mutableStateOf<Updates.Release?>(null)
    var updateDismissed by mutableStateOf(false)
    var updateProgress by mutableStateOf<Float?>(null)
    var updateError by mutableStateOf<String?>(null)
    var updateChecked by mutableStateOf(false)
    var updateFailed by mutableStateOf(false)

    // Check now (Settings): asks GitHub now, as the rider asked, and keeps the answer as the background check does.
    suspend fun checkForUpdate(ctx: Context) {
        try {
            val installed = Updates.installedVersion(ctx)
            val latest = withContext(Dispatchers.IO) { Updates.latest().also { Updates.store(ctx, it) } }
            update = latest.takeIf { Updates.isNewer(it.version, installed) }
            updateFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("KavUpdate", "update check failed", e)
            updateFailed = true
        } finally {
            updateChecked = true
        }
    }

    // Each time Kav opens: GitHub's newest release, so a new version is offered at once. Only checked here; downloading
    // it is the background check's (Checks). Offline, or with both update switches off, the last answer kept.
    suspend fun loadUpdate(ctx: Context) {
        if (Prefs.autoUpdate(ctx) || Prefs.updatePrompts(ctx)) {
            checkForUpdate(ctx)
            if (!updateFailed) {
                if (update != null) updateDismissed = false
                return
            }
        }
        val kept = withContext(Dispatchers.IO) { Updates.stored(ctx) } ?: return
        update = kept.takeIf { Updates.isNewer(it.version, Updates.installedVersion(ctx)) }
        updateChecked = true
    }

    // Outlives the dialog, so closing it doesn't drop the download.
    fun startUpdate(ctx: Context) {
        if (updateProgress != null) return
        val app = ctx.applicationContext
        viewModelScope.launch { installUpdate(app) }
    }

    private suspend fun installUpdate(ctx: Context) {
        val release = update ?: return
        if (!Updates.canInstall(ctx)) { Updates.askInstallPermission(ctx); return }
        updateError = null
        updateProgress = 0f
        try {
            val file = withContext(Dispatchers.IO) {
                Updates.download(ctx, release) { done, total ->
                    updateProgress = if (total > 0) done.toFloat() / total else 0f
                }
            }
            updateProgress = 1f
            Updates.install(ctx, file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("KavUpdate", "update failed", e)
            updateError = e.message ?: e.javaClass.simpleName
        } finally {
            updateProgress = null
        }
    }
}

private val netLoadMutex = Mutex()

suspend fun loadNet(ctx: Context): Net = withContext(Dispatchers.Default) {
    netLoadMutex.withLock {
        Loaded.net ?: run {
            // The seven days from today as last downloaded. Kav doesn't come with a week: there's none before the first
            // download, at setup or at a start without one (TimetableStep).
            val n = uk.noammm.kav.data.Timetable.load(ctx) ?: throw java.io.IOException("No timetable on the phone yet")
            Loaded.store(n)
            n
        }
    }
}

@Composable
private fun Root() {
    val ctx = LocalContext.current
    var onboarded by remember { mutableStateOf(Prefs.onboarded(ctx)) }
    val app = ctx.applicationContext
    val model: KavModel = viewModel { KavModel(Loaded.net, app) }
    // Kav's own handling of its actions, for an extension's Kav.kavAction (ui/ExtActions.kt).
    remember(model) { uk.noammm.kav.ui.kavActions(model) }
    if (!onboarded) {
        // Its paying step sets paying up with the same popup as Settings.
        Box(Modifier.fillMaxSize()) {
            OnboardingScreen(model) { Prefs.setOnboarded(ctx); onboarded = true }
            uk.noammm.kav.ui.PaySetupHost()
        }
        return
    }
    // A rider without a week yet (one who set Kav up when it came with one): it's downloaded before anything else, once.
    // With one on the phone Kav opens at once, as it always did, and reads the week meanwhile; what needs it waits there.
    LaunchedEffect(uk.noammm.kav.data.Timetable.state) {
        if (uk.noammm.kav.data.Timetable.state == uk.noammm.kav.data.Timetable.State.Ready) runCatching { loadNet(app) }
    }
    if (uk.noammm.kav.data.Timetable.state != uk.noammm.kav.data.Timetable.State.Ready) {
        TimetableStep()
        return
    }
    // A new version is asked of GitHub at each opening. Vela's recipe (fixes for Google that need no update) comes from
    // what the background check (Checks) kept, as does a newer week of the timetable, taken in at the next start.
    LaunchedEffect(model, Openings.count) { if (BuildConfig.UPDATER) model.loadUpdate(app) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { Checks.recipesAtOpen(app) }
        }
    }
    // The server chosen for live times not answering: said once when the app opens, and its live times again as soon as
    // it answers. Without internet it isn't the server, and nothing changes. The ministry can also stop answering the
    // server about stations while its snapshot is fine: said once, until a station's answer comes again.
    // The same for the server chosen to plan, checked with it when it's the same one: while it isn't answering, or
    // doesn't plan, no trip is planned.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(KavLive.source, KavPlan.source, KavLive.ownServer) {
        var opening = true
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (hasNetwork(app)) {
                    val asked = HashMap<String, KavLive.Check>()
                    suspend fun check(at: String) = asked.getOrPut(at) {
                        withContext(Dispatchers.IO) { KavLive.check(at, KavLive.keyFor(at)) }
                    }
                    val live = KavLive.server
                    val answers = check(live) as? KavLive.Check.Answers
                    val answering = answers?.live == true
                    KavLive.feedDown = answers != null && !answers.live
                    if (!answering && opening) android.widget.Toast.makeText(
                        app,
                        when {
                            KavLive.feedDown -> T("The Ministry of Transport's live feed isn't working right now",
                                "המידע בזמן אמת של משרד התחבורה לא פועל כרגע")
                            KavLive.own -> T("Your server isn't answering", "השרת שלכם לא עונה")
                            else -> T("Kav's server is offline", "השרת של Kav לא זמין")
                        },
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    KavLive.up = answering
                    if (answering && !KavLive.stopsUp) withContext(Dispatchers.IO) { KavLive.stopsAnswering() }
                    val plans = KavPlan.server
                    val c = check(plans) as? KavLive.Check.Answers
                    if (c == null && opening && plans != live) android.widget.Toast.makeText(
                        app,
                        if (plans != KavLive.KAV_SERVER) T("Your server isn't answering · no trip planning", "השרת שלכם לא עונה · אין תכנון מסלולים")
                        else T("Kav's server is offline · no trip planning", "השרת של Kav לא זמין · אין תכנון מסלולים"),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    KavPlan.up = c != null
                    KavPlan.offered = plans == KavLive.KAV_SERVER || c?.planner != null
                    opening = false
                }
                kotlinx.coroutines.withTimeoutOrNull(if (KavLive.up && KavPlan.up) 30_000L else 15_000L) { KavLive.recheck.receive() }
            }
        }
    }
    var toldStops by remember { mutableStateOf(false) }
    LaunchedEffect(KavLive.stopsUp, KavLive.enabled) {
        if (KavLive.stopsUp || !KavLive.enabled || toldStops) return@LaunchedEffect
        toldStops = true
        android.widget.Toast.makeText(
            app, T("The ministry isn't answering", "משרד התחבורה לא עונה"),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }
    Box(Modifier.fillMaxSize()) {
        Shell(model)
        // Popups as Kav opens go through StartPopups only, one at a time: none of their own here.
        if (Pip.active) PipOverlay(model)
        else StartPopups(model)
        uk.noammm.kav.ui.PaySetupHost()
        uk.noammm.kav.ui.PayOffAskHost()
        uk.noammm.kav.ui.EntranceAskHost()
        uk.noammm.kav.ui.PayHistoryHost()
        uk.noammm.kav.ui.TicketOpenHost(model)
        // Kav's place and stop search for an extension's settings or code (ExtPickers.kt).
        uk.noammm.kav.ui.ExtPickHost(model)
    }
}

@Composable
private fun Shell(model: KavModel) {
    val ctx = LocalContext.current
    DisposableEffect(model) {
        val app = ctx.applicationContext
        GoogleMaps.standIn = { model.standIn(app) }
        onDispose { GoogleMaps.standIn = { null } }
    }
    // Keeps the town search sees current. The timetable is only loaded for it when the last town
    // found is unknown or a few kilometres away.
    val roughly = model.here?.let { (it.first * 100).toInt() to (it.second * 100).toInt() }
    LaunchedEffect(model.seen, roughly) {
        val at = model.here ?: return@LaunchedEffect
        if (model.seen != Seen.CITY) return@LaunchedEffect
        val app = ctx.applicationContext
        val last = model.lastCity(app)
        if (Loaded.net == null && last != null && metres(at.first, at.second, last.lat, last.lon) < 3_000) return@LaunchedEffect
        runCatching { loadNet(app) }
        withContext(Dispatchers.Default) { model.cityAround(app) }
    }
    val askLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) requestLocationOnce(ctx) { model.locate(it.first, it.second) }
    }
    LaunchedEffect(Unit) {
        if (!hasPreciseLocation(ctx)) askLocation.launch(LOCATION_PERMISSIONS)
    }
    val shellLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(shellLifecycle) {
        shellLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (hasLocationPermission(ctx)) requestLocationOnce(ctx) { model.locate(it.first, it.second) }
            // The trip service can only start tracking while the app is in front.
            if (model.activeJourney != null) TripService.show(ctx)
        }
    }

    LaunchedEffect(model) {
        snapshotFlow { model.tab to model.netLoadAttempt }.collect { (tab, _) ->
            if (tab != Tab.Stations && tab != Tab.Lines) return@collect
            if (model.net != null || model.netLoading || model.netError != null) return@collect
            model.netLoading = true
            try {
                model.net = loadNet(ctx).also { net -> launch { uk.noammm.kav.ui.warmLines(ctx, net, model.here) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.netError = e.message ?: e.javaClass.simpleName
            } finally {
                model.netLoading = false
            }
        }
    }
    // Once the timetable is read, what the first search and the Stops and Lines lists need is worked out meanwhile, so
    // each opens whole and the first letters typed find at once. Side by side, each on a core of its own.
    LaunchedEffect(model) {
        val net = snapshotFlow { Loaded.net }.first { it != null }!!
        launch(Dispatchers.Default) { net.stopType.size }
        launch(Dispatchers.Default) { net.stopWords.size }
        launch { uk.noammm.kav.ui.warmStops(net, snapshotFlow { model.here }.first { it != null }) }
        launch { uk.noammm.kav.ui.warmLines(ctx, net, model.here) }
    }

    LaunchedEffect(model) {
        withContext(Dispatchers.IO) { JourneyFile.load(ctx) }?.let { (journey, step) ->
            if (model.activeJourney == null) {
                val last = buildSteps(journey.trip, journey.fromLabel, journey.toLabel).size - 1
                model.journeyStep = step.coerceIn(0, last.coerceAtLeast(0))
                model.activeJourney = journey
            }
        }
        model.restored = true
        snapshotFlow { model.activeJourney to model.journeyStep }.collect { (journey, step) ->
            withContext(Dispatchers.IO) {
                if (journey == null) JourneyFile.clear(ctx) else JourneyFile.save(ctx, journey, step)
            }
        }
    }

    // Extensions listening to trips (Kav.onTrip) are told as the trip followed starts, moves on to another step, is
    // planned again on the way, and ends (TripEvents). Followed here whether any listens or not, so one that starts
    // listening during a trip is told what comes next right.
    LaunchedEffect(model) {
        var was: uk.noammm.kav.ext.TripEvents.Seen? = null
        var wasJourney: ActiveJourney? = null
        var steps: Pair<Transit.Itinerary, List<uk.noammm.kav.ui.Step>>? = null
        snapshotFlow { Triple(model.activeJourney, model.journeyStep, model.journeyShown) }.collect { (journey, step, shown) ->
            val now = journey?.let { j ->
                val s = steps?.takeIf { it.first === j.trip }?.second
                    ?: buildSteps(j.trip, j.fromLabel, j.toLabel).also { steps = j.trip to it }
                uk.noammm.kav.ext.TripEvents.Seen(j.trip, step, s.lastIndex, shown)
            }
            val events = uk.noammm.kav.ext.TripEvents.changes(was, now)
            val before = was; val ended = wasJourney
            was = now; wasJourney = journey
            if (events.isEmpty() || !uk.noammm.kav.ext.Native.listensToTrips()) return@collect
            val stepsNow = steps?.second.orEmpty()
            withContext(Dispatchers.Default) {
                for (e in events) runCatching {
                    val json = if (e == "end" && ended != null && before != null)
                        uk.noammm.kav.ext.TripEvents.json(e, ended, before.step, buildSteps(ended.trip, ended.fromLabel, ended.toLabel),
                            uk.noammm.kav.ext.TripEvents.reason(before), before.shown)
                    else if (journey != null) uk.noammm.kav.ext.TripEvents.json(e, journey, step, stepsNow, shown = shown)
                    else null
                    json?.let { uk.noammm.kav.ext.Native.tripEvent(it.toString()) }
                }
            }
        }
    }

    // A tap on an extension's home screen widget (ExtWidgets): Kav at a stop or a line, the extension's own page or Kav's
    // planner, as the widget's action says; its page in Settings › Extensions, with its settings and switches, for its
    // settings and for one that's off. A stop and a line open the way a service alert's do.
    LaunchedEffect(model) {
        snapshotFlow { ExtWidgets.pending }.collect { o ->
            if (o == null) return@collect
            ExtWidgets.pending = null
            if (o.page || (o.tap as? uk.noammm.kav.ext.Widgets.Tap.Page)?.action == uk.noammm.kav.ext.Blocks.Action.Settings) {
                model.settingsOpen = true
                SettingsNav.wanted = "extensions"
                if (o.identity.isNotEmpty()) ExtUi.wanted = "e:${o.identity}"
                return@collect
            }
            when (val t = o.tap) {
                is uk.noammm.kav.ext.Widgets.Tap.Stop -> AlertLink.stop = t.code
                is uk.noammm.kav.ext.Widgets.Tap.Line -> AlertLink.line = t.number
                is uk.noammm.kav.ext.Widgets.Tap.Screen -> ExtPageNav.open(ExtEntry(o.identity, t.page, t.args))
                is uk.noammm.kav.ext.Widgets.Tap.Page -> when (val a = t.action) {
                    uk.noammm.kav.ext.Blocks.Action.Settings -> {}
                    is uk.noammm.kav.ext.Blocks.Action.Plan -> planTo(model, a)
                    is uk.noammm.kav.ext.Blocks.Action.Link -> openLink(ctx, a.url)
                }
                is uk.noammm.kav.ext.Widgets.Tap.Call, null -> {}
            }
        }
    }

    LaunchedEffect(model) {
        snapshotFlow { PendingStop.key }.collect { key ->
            if (key == null) return@collect
            PendingStop.key = null
            // Tapped before any screen has loaded the timetable.
            val net = model.net ?: runCatching { withContext(Dispatchers.Default) { loadNet(ctx) } }.getOrNull() ?: return@collect
            val stop = net.stopOfKey(key).takeIf { it >= 0 } ?: return@collect
            model.settingsOpen = false; model.stationStop = stop; model.tab = Tab.Stations
        }
    }

    // The tab an alert's stop or line was opened from, till the rider is back on it (or goes elsewhere).
    var alertFrom by remember { mutableStateOf<Tab?>(null) }
    val reopenAlert = uk.noammm.kav.ui.LocalServiceAlertOpener.current
    LaunchedEffect(model) {
        snapshotFlow { AlertLink.stop to AlertLink.line }.collect { (code, number) ->
            if (code == null && number == null) return@collect
            AlertLink.stop = null; AlertLink.line = null
            val from = model.tab
            model.settingsOpen = false
            if (code != null) {
                val net = model.net ?: runCatching { withContext(Dispatchers.Default) { loadNet(ctx) } }.getOrNull() ?: return@collect
                val stop = uk.noammm.kav.data.KavLive.stopOfCode(net, code).takeIf { it >= 0 } ?: return@collect
                model.openStation(stop)
            } else if (number != null) {
                model.rememberReturn(Tab.Lines)
                model.scheduleLine = null; model.lineRoute = -1; model.lineFocusStop = -1
                model.lineQuery = number; model.tab = Tab.Lines
            }
            alertFrom = from
        }
    }
    LaunchedEffect(model) {
        snapshotFlow { model.tab }.collect { tab ->
            val from = alertFrom ?: return@collect
            when (tab) {
                from -> { alertFrom = null; AlertLink.alert?.let(reopenAlert); AlertLink.alert = null }
                Tab.Lines, Tab.Stations -> {}
                else -> { alertFrom = null; AlertLink.alert = null }
            }
        }
    }

    LaunchedEffect(model) {
        snapshotFlow { PendingLink.plan }.collect { plan ->
            if (plan == null) return@collect
            PendingLink.plan = null
            val somewhere = plan.toLat != null && plan.toLon != null
            if (!somewhere && plan.toName == null && plan.shared == null) return@collect
            model.pendingLink = plan
            model.settingsOpen = false
            model.tab = Tab.Directions
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, model.activeJourney?.trip) {
        // Asked at once as a trip starts or resumes: until then only the plan's own times are known, live at the
        // boarding stop alone, so the ride's other stops would show their estimates for a whole poll.
        var first = true
        while (true) {
            val journey = model.activeJourney ?: break
            // A trip Kav planned has its names already, and its live times come from where live locations do; an
            // extension's from its own publisher (KavPlan.live). Any other trip keeps what it was saved with.
            if (!KavPlan.refreshes(journey.trip)) break
            if (!first) delay(journey.resolved.pollSecs.coerceIn(15, 120) * 1000L)
            first = false
            val current = model.activeJourney?.takeIf { it.trip == journey.trip } ?: break
            val refreshed = withContext(Dispatchers.IO) { KavPlan.live(listOf(current.trip), current.resolved, ending = true) }
            val spots = withContext(Dispatchers.IO) { KavLive.tripSpots(current.trip, refreshed) }
            model.activeJourney?.takeIf { it.trip == journey.trip }?.let {
                model.activeJourney = it.copy(resolved = refreshed.withLive(KavLive.fresher(refreshed.live, spots)))
            }
        }
    }


    val journeyActive = model.activeJourney != null
    LaunchedEffect(journeyActive, lifecycle) {
        if (!journeyActive) return@LaunchedEffect
        // Only while Kav is in front: the small window lives on the trip service's fixes (TripBridge.fix), which it
        // asks for only as often as the step needs.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val stop = trackLocation(ctx, onFix = { model.track(it.latitude, it.longitude, it.speed, it.fixTime(), it.fixAccuracy()) },
                onRough = { model.glimpse(it.latitude, it.longitude, it.speed, it.fixTime(), it.fixAccuracy()) })
            try { awaitCancellation() } finally { stop() }
        }
    }
    LaunchedEffect(model.activeJourney?.trip) {
        val journey = model.activeJourney ?: return@LaunchedEffect
        val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        var arrivedAt = 0L
        while (true) {
            val current = model.activeJourney?.takeIf { it.trip === journey.trip } ?: break
            val now = System.currentTimeMillis() / 1000
            val next = journeyProgress(
                steps, model.journeyStep, current.resolved, current.chosen,
                now, model.stepFix, canLocate(ctx), Prefs.beginTripsImmediately(ctx),
                current.fromHere,
            )
            // There: the trip shows it's arrived, and ends a little later, as its End trip would.
            if (arrivedAtEnd(steps, next, current.resolved, current.chosen, now, model.stepFix, current.trip.arr)) {
                model.journeyStep = steps.lastIndex
                if (arrivedAt == 0L) arrivedAt = now
                else if (now - arrivedAt >= ARRIVED_END_S) { model.activeJourney = null; break }
            } else {
                arrivedAt = 0L
                if (next != model.journeyStep) model.journeyStep = next
            }
            delay(2000)
        }
    }
    val askNotice = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(journeyActive, model.restored) {
        if (!model.restored) return@LaunchedEffect
        if (!journeyActive) { TripService.stop(ctx); return@LaunchedEffect }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotice.launch(Manifest.permission.POST_NOTIFICATIONS)
        while (true) {
            if (model.activeJourney != null) TripService.show(ctx)
            withTimeoutOrNull(30_000) {
                snapshotFlow { model.activeJourney to model.journeyStep }.drop(1).first()
            }
        }
    }
    DisposableEffect(model.restored) {
        if (model.restored) {
            TripBridge.end = { model.activeJourney = null }
            TripBridge.journey = { model.activeJourney?.let { it to model.journeyStep } }
            TripBridge.shown = { model.journeyShown }
            TripBridge.fix = {
                if (it.accuracy > ROUGH_FIX_M) model.glimpse(it.lat, it.lon, it.speed, it.at, it.accuracy)
                else model.track(it.lat, it.lon, it.speed, it.at, it.accuracy)
            }
            TripBridge.fixNow = { model.fix }
        }
        onDispose {
            TripBridge.end = null
            TripBridge.journey = null
            TripBridge.shown = null
            TripBridge.fix = null
            TripBridge.fixNow = null
        }
    }
    LaunchedEffect(model.navigating, Pip.allowed) {
        Pip.wanted = model.navigating && Pip.allowed
        (ctx as? MainActivity)?.updatePipParams()
    }
    // Starting the compass or the location is a few calls into the system that hold the main thread, frames missed,
    // so a map scrolled out of view or a tab left keeps them a while: scrolling back to it starts nothing.
    var tripMapKept by remember { mutableStateOf(false) }
    val mapShown = model.tripMapShown || model.lineMapShown
    LaunchedEffect(mapShown) {
        if (mapShown) tripMapKept = true else { delay(MAP_SENSORS_KEPT_MS); tripMapKept = false }
    }
    val headingWanted = model.navigating || tripMapKept || model.tab == Tab.Live
    var headingKept by remember { mutableStateOf(false) }
    LaunchedEffect(headingWanted) {
        if (headingWanted) headingKept = true else { delay(MAP_SENSORS_KEPT_MS); headingKept = false }
    }
    LaunchedEffect(headingKept, lifecycle) {
        // The heading is kept while no map follows it, so one shown again draws the rider's arrow at once, turned the
        // last way, instead of the dot until the compass's first reading.
        if (!headingKept) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val stop = trackHeading(ctx) { model.heading = it }
            try { awaitCancellation() } finally { stop() }
        }
    }
    // The trip's map before it starts follows you as the Live tab's does, while it's on screen; a trip under way does
    // already.
    LaunchedEffect(tripMapKept && !journeyActive, lifecycle) {
        if (!tripMapKept || journeyActive) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val first = requestLocationOnce(ctx) { model.locate(it.first, it.second) }
            val follow = trackLocation(
                ctx, onFix = { model.locate(it.latitude, it.longitude) }, onRough = { model.locate(it.latitude, it.longitude) },
            )
            try { awaitCancellation() } finally { first(); follow() }
        }
    }

    // Kav's protected surfaces keep Kav's own look under a theme (ExtLook): Settings, the report form, navigation.
    // An extension page opened from Settings sits over it in the extension's own look.
    ExtLook.Protected((model.settingsOpen && ExtPageNav.overlay.isEmpty()) || model.report != null || model.navigating)
    var exitAsk by remember { mutableStateOf(false) }
    BackHandler(enabled = !model.navigating) {
        when {
            // As Settings' own back button: it closes onto the tab it was opened over.
            model.settingsOpen -> model.settingsOpen = false
            model.tab != Tab.Directions -> {
                model.resetOpenPages()
                model.tab = Tab.Directions
            }
            else -> exitAsk = true
        }
    }
    if (exitAsk) ExitPrompt(onStay = { exitAsk = false }) {
        exitAsk = false
        (ctx as? ComponentActivity)?.finish()
    }

    val liquid = rememberLiquidBackdrop()
    CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().background(K.bg))
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Spacer(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.safeDrawing).background(K.bg))
        },
        bottomBar = {
            // Settings covers the bar in the overlay above this scaffold; its opening doesn't resize the tabs.
            val covered = model.report != null || uk.noammm.kav.ui.Sheets.open > 0
            val shown = !model.navigating && !covered
            Box(Modifier.graphicsLayer { alpha = if (model.settingsOpen) 0f else 1f }) {
                if (!shown) Spacer(Modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.safeDrawing))
                androidx.compose.animation.AnimatedVisibility(
                    shown,
                    enter = androidx.compose.animation.slideInVertically(spring(dampingRatio = 1f, stiffness = 400f)) { it } +
                        androidx.compose.animation.fadeIn(tween(200)),
                    exit = androidx.compose.animation.slideOutVertically(tween(200)) { it } +
                        androidx.compose.animation.fadeOut(tween(150)),
                ) { uk.noammm.kav.ui.KavPart("tabbar", model.tab, { org.json.JSONObject().put("tab", model.tab.name.lowercase()) }) { TabBar(model) } }
            }
        },
    ) { pad ->
        val direction = LocalLayoutDirection.current
        CompositionLocalProvider(LocalBottomBarInset provides pad.calculateBottomPadding()) {
        Box(Modifier.padding(
            start = pad.calculateStartPadding(direction), top = pad.calculateTopPadding(),
            end = pad.calculateEndPadding(direction),
        ).fillMaxSize().clipToBounds()) {
        CompositionLocalProvider(LocalLiquidBackdrop provides null) {
            // Only the tabs feed the bar's glass.
            Box(Modifier.fillMaxSize()
                .then(if (K.liquid && liquid != null && StreetViewer.open == null) Modifier.liquidSource(liquid) else Modifier)) {
                TabStack(model.tab) { tab ->
                    when (tab) {
                        Tab.Directions -> DirectionsOnline(model)
                        Tab.Stations -> StationsScreen(model)
                        Tab.Lines -> LinesScreen(model)
                        Tab.Live -> LiveScreen(model)
                        Tab.Ext -> ExtTabScreen(model)
                    }
                }
            }
        }
        }
        }
    }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLiquidBackdrop provides null, LocalBottomBarInset provides safe.calculateBottomPadding()) {
        Box(Modifier.padding(
            start = safe.calculateStartPadding(direction), top = safe.calculateTopPadding(),
            end = safe.calculateEndPadding(direction),
        ).fillMaxSize().clipToBounds()) {
            // Prepare Settings after the first screen is drawn, before its first slide.
            var settingsReady by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { delay(500); settingsReady = true }
            val settingsSlide = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(model.settingsOpen) {
                settingsSlide.animateTo(if (model.settingsOpen) 0f else 1f, tween(300))
            }
            if (settingsReady || model.settingsOpen) {
                TabLifecycle(model.settingsOpen) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        translationY = size.height * settingsSlide.value
                    }) {
                        SettingsScreen(model, active = model.settingsOpen || settingsSlide.isRunning) { model.settingsOpen = false }
                    }
                }
            }
            var lastReport by remember { mutableStateOf(model.report) }
            model.report?.let { lastReport = it }
            androidx.compose.animation.AnimatedVisibility(
                model.report != null,
                enter = uk.noammm.kav.ui.modalIn(), exit = uk.noammm.kav.ui.modalOut(),
            ) {
                lastReport?.let { key(it) { ReportScreen(it) { model.report = null } } }
            }
            // An extension's pages, opened over Kav's screens (ExtPages.kt).
            ExtPageOverlay(model)
            // A stop in Street View or on the map, over whatever screen opened it.
            StreetViewHost()
        }
    }
    // A map drawn out of sight once, so the first screen with a map shows it whole at once.
    uk.noammm.kav.ui.MapWarmUp(model.here)
    }
    }
}

// Back on a tab, it's where it was left: each tab once opened keeps its page, scroll and map while another is in front.
// Behind another it isn't laid out or drawn, and its own lifecycle waits at CREATED, so what it polls, its map and its
// back handlers pause until it's back.
@Composable
private fun TabStack(current: Tab, content: @Composable (Tab) -> Unit) {
    // The tabs opened so far, in the order they were first opened.
    val seen = remember { ArrayList<Tab>() }
    val opened = remember(current) { seen.apply { if (current !in this) add(current) }.toList() }
    val fades = remember { HashMap<Tab, androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>() }
    // A field typed into on the tab left doesn't keep the keyboard up on the next.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(current) { focus.clearFocus() }
    uk.noammm.kav.ui.VeilMaps { fades.values.any { it.isRunning } }
    androidx.compose.ui.layout.Layout(
        content = {
            opened.forEach { tab ->
                key(tab) {
                    val shown = tab == current
                    // The first tab shows at once; any opened after it fades in.
                    val fade = remember { fades.getOrPut(tab) { androidx.compose.animation.core.Animatable(if (fades.isEmpty()) 1f else 0f) } }
                    LaunchedEffect(shown) { fade.animateTo(if (shown) 1f else 0f, tween(300)) }
                    // A tab shown again comes to life (its map, the rider's location, its data) once it has faded in:
                    // started as it faded, the Live map resuming under the fade and the rest of that work took the
                    // fade's frames. Opened the first time, it starts at once, so its map loads at once.
                    val inFront by remember { derivedStateOf { fade.value >= 1f } }
                    val wasInFront = remember { booleanArrayOf(false) }
                    SideEffect { if (inFront) wasInFront[0] = true }
                    TabLifecycle(shown && (inFront || !wasInFront[0])) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) { content(tab) }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        // The tab in front last, over the one fading out; the rest stay as they were laid out.
        val showing = opened.indices.filter { opened[it] != current && (fades[opened[it]]?.value ?: 0f) > 0f } +
            opened.indexOf(current)
        val placeables = showing.map { measurables[it].measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

// A tab's own lifecycle: the screen's while the tab is in front, held at CREATED behind another one.
private class TabLife : androidx.lifecycle.LifecycleOwner {
    val registry = androidx.lifecycle.LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
}

@Composable
private fun TabLifecycle(shown: Boolean, content: @Composable () -> Unit) {
    val screen = LocalLifecycleOwner.current.lifecycle
    val life = remember { TabLife() }
    DisposableEffect(screen, shown) {
        val follow = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            life.registry.currentState = if (shown) screen.currentState else minOf(screen.currentState, Lifecycle.State.CREATED)
        }
        screen.addObserver(follow)
        onDispose { screen.removeObserver(follow) }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides life) { content() }
}

@Composable
internal fun ImportPrompt(model: KavModel) {
    val ctx = LocalContext.current
    val uri = PendingBackup.uri ?: return
    var failed by remember(uri) { mutableStateOf<String?>(null) }
    var busy by remember(uri) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) PendingBackup.uri = null }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Import this backup?", "לייבא את הגיבוי הזה?"), style = K.type.title)
            // A failed import fades in over what the import does, in red, and the dialog eases to its new height.
            uk.noammm.kav.ui.ChangingText(
                failed ?: T(
                    "It replaces your saved places, trip history and settings.",
                    "הוא מחליף את המקומות השמורים, היסטוריית הנסיעות וההגדרות שלכם.",
                ),
            ) { said -> Text(said, fontSize = 14.sp, color = if (said == failed) K.critical else K.dim, lineHeight = 20.sp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(
                    T("Cancel", "ביטול"), { if (!busy) PendingBackup.uri = null }, Modifier.weight(1f).heightIn(min = 52.dp),
                    style = ButtonStyle.Secondary,
                )
                KavButton(T("Import", "ייבוא"), {
                    busy = true
                    scope.launch {
                        Backup.read(ctx, uri)
                            .onSuccess { r ->
                                model.reloadPrefs(ctx)
                                if (PendingBackup.uri == uri) PendingBackup.uri = null
                                android.widget.Toast.makeText(
                                    ctx,
                                    T("Imported: ", "יובא: ") + listOf(
                                        T.count(r.favourites, "1 place", "מקום אחד", "places", "מקומות"),
                                        T.count(r.trips, "1 trip", "נסיעה אחת", "trips", "נסיעות"),
                                        T.count(r.recents, "1 search", "חיפוש אחד", "searches", "חיפושים"),
                                    ).joinToString(", "),
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                            .onFailure { e -> failed = importError(e) }
                        busy = false
                    }
                }, Modifier.weight(1f), busy = busy)
            }
        }
    }
}

@Composable
internal fun LookPrompt(onDone: () -> Unit) {
    // Back and a tap outside keep the look as it is, as Done does.
    Dialog(onDismissRequest = onDone) {
        // A dialog is its own window, out of reach of the theme's fade: picking a look eases its colours instead.
        val surface = androidx.compose.animation.animateColorAsState(K.surface1, androidx.compose.animation.core.tween(300), label = "lookSurface").value
        val ink = androidx.compose.animation.animateColorAsState(K.text, androidx.compose.animation.core.tween(300), label = "lookInk").value
        val quiet = androidx.compose.animation.animateColorAsState(K.textSecondary, androidx.compose.animation.core.tween(300), label = "lookQuiet").value
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(surface).padding(K.gap5),
        ) {
            Text(T("Pick a look", "בחרו מראה"), style = K.type.title, color = ink)
            LookChoices { Text(it, style = K.type.caption, color = quiet, modifier = Modifier.padding(top = K.gap4, bottom = K.gap2)) }
            KavButton(T("Done", "סיום"), onDone, Modifier.padding(top = K.gap5), wide = true)
        }
    }
}

// Shown once per install: after setup, or after the look prompt on an update.
@Composable
internal fun SupportPrompt(onDone: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDone) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
        ) {
            Text(T("Enjoying Kav?", "נהנים מקו?"), style = K.type.title)
            Text(
                T(
                    "Kav is made to protect every user's privacy and to make riding the bus a little more " +
                        "bearable (as much as possible).",
                    "קו מפותחת במטרה לשמור על הפרטיות של כל משתמש ולהפוך את השימוש באוטובוסים לחוויה " +
                        "נסבלת יותר (כמה שאפשר).",
                ),
                fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
            )
            Text(
                buildAnnotatedString {
                    append(T(
                        "If you'd like to support Kav's development, you're welcome to tap the button below. " +
                            "If that's not an option for you, you can show your support with ",
                        "אם תרצו לתרום להמשך הפיתוח של האפליקציה, אתם מוזמנים ללחוץ על הכפתור למטה. " +
                            "אם אין לכם אפשרות, אתם מוזמנים להביע תמיכה דרך ",
                    ))
                    withLink(LinkAnnotation.Url(REPO_URL, TextLinkStyles(SpanStyle(color = K.accentInk)))) {
                        append(T("a star on GitHub", "כוכב בגיטהאב"))
                    }
                    append(".")
                },
                fontSize = 14.sp, color = K.dim, lineHeight = 20.sp, modifier = Modifier.padding(top = K.gap2),
            )
            Text(
                T(
                    "*Donating is just a thank-you and 100% optional. You won't lose any features if you can't :)",
                    "*תרומה היא אות הערכה בלבד, והיא אופציונלית לגמרי. לא תאבדו אף פיצ'ר אם אין ביכולתכם לתרום :)",
                ),
                style = K.type.caption, modifier = Modifier.padding(top = K.gap3),
            )
            KavButton(
                T("Buy me a coffee", "קנו לי קפה"), { openLink(ctx, COFFEE_URL); onDone() }, Modifier.padding(top = K.gap5), wide = true,
            )
            KavButton(
                T("Have you seen the economy??", "ראית את מצב האקונומיה??"), onDone, Modifier.padding(top = K.gap2),
                style = ButtonStyle.Secondary, wide = true,
            )
        }
    }
}

@Composable
private fun ExitPrompt(onStay: () -> Unit, onExit: () -> Unit) {
    Dialog(onDismissRequest = onStay) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Exit Kav?", "לצאת מ־Kav?"), style = K.type.title)
            Text(T("A trip in progress is kept until you end it.", "נסיעה שמתבצעת נשמרת עד שתסיימו אותה."), fontSize = 14.sp, color = K.dim, lineHeight = 20.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(
                    T("Stay", "השארות"), onStay, Modifier.weight(1f).heightIn(min = 52.dp), style = ButtonStyle.Secondary,
                )
                KavButton(T("Exit", "יציאה"), onExit, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabBar(model: KavModel) {
    // A glass capsule floating above the gesture area, which stays outside it. The chosen tab sits on the accent, as a
    // segmented control's choice does, and that one highlight slides from tab to tab; no ripple, so nothing flashes.
    val pinned = ExtShown.tab
    val tabs = Tab.entries.filter { it != Tab.Ext || pinned != null }
    // Street View is a full-screen view of its own: the bar steps down while it's open, and no glass refracts the
    // moving picture.
    androidx.compose.animation.AnimatedVisibility(
        StreetViewer.open == null,
        enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
    ) {
    BoxWithConstraints(
        Modifier.navigationBarsPadding().padding(horizontal = K.gap4, vertical = K.gap2)
            .fillMaxWidth().height(64.dp).glassSurface(32.dp).padding(4.dp),
    ) {
        val each = maxWidth / tabs.size
        val at by androidx.compose.animation.core.animateDpAsState(
            each * tabs.indexOf(model.tab).coerceAtLeast(0),
            androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), label = "tabThumb",
        )
        Box(Modifier.offset(x = at).width(each).fillMaxHeight().clip(K.capsule).background(K.accent))
        Row(Modifier.fillMaxSize().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { t ->
                val on = model.tab == t
                val ink by animateColorAsState(
                    if (on) K.onAccent else K.textSecondary, androidx.compose.animation.core.tween(150), label = "tabInk",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(K.capsule).selectable(
                        on, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null, role = Role.Tab,
                    ) {
                        // An extension holding "tab.open" goes first (ext/Actions.kt).
                        if (uk.noammm.kav.ext.Actions.take(null, "tab.open") { org.json.JSONObject().put("tab", t.name.lowercase()) }) return@selectable
                        model.openTab(t)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    TabGlyph(t, ink, 22.dp)
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, style = K.type.caption, color = ink, maxLines = 1)
                }
            }
        }
    }
}
}

@Composable
internal fun TabGlyph(tab: Tab, tint: Color, side: Dp = 18.dp) {
    Canvas(Modifier.size(side)) {
        val w = size.width; val h = size.height; val sw = w * 0.09f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1 * w, y1 * h), Offset(x2 * w, y2 * h), sw, StrokeCap.Round)
        when (tab) {
            Tab.Directions -> {
                line(.10f, .45f, .50f, .12f); line(.50f, .12f, .90f, .45f)
                line(.23f, .40f, .23f, .86f); line(.23f, .86f, .77f, .86f)
                line(.77f, .86f, .77f, .40f); line(.43f, .86f, .43f, .62f)
                line(.43f, .62f, .59f, .62f); line(.59f, .62f, .59f, .86f)
            }
            Tab.Stations -> {
                drawCircle(tint, w * .20f, Offset(w * .50f, h * .34f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                line(.50f, .54f, .50f, .86f); line(.32f, .86f, .68f, .86f)
            }
            Tab.Lines -> listOf(.22f, .50f, .78f).forEachIndexed { i, y ->
                drawCircle(tint, w * .085f, Offset(w * .18f, h * y))
                line(.34f, y, if (i == 1) .86f else .70f, y)
            }
            // Four squares: a screen of someone else's.
            Tab.Ext -> listOf(.30f, .70f).forEach { x -> listOf(.30f, .70f).forEach { y ->
                drawRoundRect(tint, Offset(w * (x - .17f), h * (y - .17f)), androidx.compose.ui.geometry.Size(w * .34f, h * .34f),
                    androidx.compose.ui.geometry.CornerRadius(w * .08f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            } }
            Tab.Live -> {
                drawCircle(tint, w * .14f, Offset(w * .5f, h * .5f))
                drawCircle(tint, w * .30f, Offset(w * .5f, h * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                drawCircle(tint, w * .46f, Offset(w * .5f, h * .5f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw * .7f))
            }
        }
    }
}

fun hasLocationPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

fun canLocate(ctx: Context): Boolean {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
    return hasLocationPermission(ctx) && androidx.core.location.LocationManagerCompat.isLocationEnabled(lm)
}

fun hasPreciseLocation(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

val LOCATION_PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private const val GOOD_FIX_M = 50f
private const val ROUGH_FIX_M = 75f
private const val VIEW_FIX_M = 200f
// How long the compass and the location stay on after the map that wanted them scrolled away or its tab was left.
private const val MAP_SENSORS_KEPT_MS = 15_000L

internal fun Location.fixTime(): Long =
    System.currentTimeMillis() / 1000 - (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000_000

internal fun Location.fixAccuracy(): Float = if (hasAccuracy()) accuracy else POOR_FIX_M

internal fun locationProviders(lm: LocationManager): List<String> {
    val available = runCatching { lm.allProviders }.getOrDefault(emptyList())
    return buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { it in available }
}

private fun lastKnown(ctx: Context): Location? {
    if (!hasLocationPermission(ctx)) return null
    return try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        (locationProviders(lm) + LocationManager.PASSIVE_PROVIDER)
            .asSequence()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    } catch (e: SecurityException) { null }
}

// Map previews may start with a cached fix. Route endpoints require an accurate fix measured after this request.
// Cancelling removes the listener and timeout without reporting failure.
fun requestLocationOnce(
    ctx: Context,
    onFail: () -> Unit = {},
    requireFresh: Boolean = false,
    onResult: (Pair<Double, Double>) -> Unit,
): () -> Unit {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    if (lm == null || !hasLocationPermission(ctx)) { onFail(); return {} }
    val started = SystemClock.elapsedRealtimeNanos()
    var reported = false
    var live = false
    var bestAccuracy = Float.MAX_VALUE
    fun offer(l: Location, fresh: Boolean) {
        val accuracy = if (l.hasAccuracy()) l.accuracy else 1_000f
        if (fresh && !live) {
            live = true
        } else if (accuracy >= bestAccuracy) {
            return
        }
        bestAccuracy = accuracy
        reported = true
        onResult(l.latitude to l.longitude)
    }
    if (!requireFresh) {
        lastKnown(ctx)?.takeIf { System.currentTimeMillis() / 1000 - it.fixTime() in 0..120 }?.let { offer(it, false) }
    } else {
        // A precise fix from the last two minutes is as good as a new one, and plans without waiting for the GPS.
        lastKnown(ctx)?.takeIf {
            System.currentTimeMillis() / 1000 - it.fixTime() in 0..120 && it.hasAccuracy() && it.accuracy in 0f..GOOD_FIX_M
        }?.let { onResult(it.latitude to it.longitude); return {} }
    }

    var done = false
    val handler = Handler(Looper.getMainLooper())
    var timeout: Runnable? = null
    var listener: LocationListenerCompat? = null
    fun stop(failed: Boolean = false) {
        if (done) return
        done = true
        listener?.let { try { LocationManagerCompat.removeUpdates(lm, it) } catch (_: SecurityException) {} }
        timeout?.let { handler.removeCallbacks(it) }
        if (failed && !reported) onFail()
    }
    listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            if (done || location.elapsedRealtimeNanos !in started..SystemClock.elapsedRealtimeNanos()) return
            val accurate = location.hasAccuracy() && location.accuracy in 0f..GOOD_FIX_M
            if (requireFresh && !accurate) return
            if (accurate) stop()
            offer(location, true)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    var any = false
    val request = LocationRequestCompat.Builder(1000L)
        .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
    for (provider in locationProviders(lm)) {
        if (!runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) continue
        try {
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, listener, Looper.getMainLooper())
            any = true
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
    }
    if (!any) stop(failed = true)
    else {
        timeout = Runnable { stop(failed = true) }
        handler.postDelayed(timeout, 30_000)
    }
    return { stop() }
}

// A rough fix goes to onRough instead, to show where you are and to move a trip's steps, which allow for how rough it is,
// but not for anything else the rider's position is used for. Network fixes while a precise provider is talking are
// dropped, and one rougher than VIEW_FIX_M is no use at all.
// every and quality: how often and how precisely, by default every second as closely as the phone can.
fun trackLocation(
    ctx: Context, onFix: (Location) -> Unit, onRough: ((Location) -> Unit)? = null, every: Long = 1000L,
    quality: Int = LocationRequestCompat.QUALITY_HIGH_ACCURACY,
): () -> Unit {
    if (!hasLocationPermission(ctx)) return {}
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return {}
    var active = true
    var lastPrecise = 0L
    var lastAt = 0L
    val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            val at = location.elapsedRealtimeNanos
            val age = SystemClock.elapsedRealtimeNanos() - at
            if (!active || at < lastAt || age !in 0..15_000_000_000L) return
            if (!location.hasAccuracy() || location.accuracy !in 0f..VIEW_FIX_M) return
            val rough = location.accuracy > ROUGH_FIX_M
            val now = SystemClock.elapsedRealtime()
            if (location.provider == LocationManager.NETWORK_PROVIDER && now - lastPrecise < 10_000) return
            if (!rough && location.provider != LocationManager.NETWORK_PROVIDER) lastPrecise = now
            lastAt = at
            if (rough) onRough?.invoke(location) else onFix(location)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    var any = false
    for (provider in locationProviders(lm)) {
        try {
            val interval = if (provider == LocationManager.NETWORK_PROVIDER) maxOf(4000L, every) else every
            val request = LocationRequestCompat.Builder(interval).setQuality(quality).build()
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, listener, Looper.getMainLooper())
            any = true
        } catch (e: SecurityException) {
        } catch (e: IllegalArgumentException) {
        }
    }
    if (!any) return {}
    return { active = false; try { LocationManagerCompat.removeUpdates(lm, listener) } catch (_: SecurityException) {} }
}

fun trackHeading(ctx: Context, onHeading: (Float) -> Unit): () -> Unit {
    val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return {}
    val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return {}
    val rotation = FloatArray(9); val remapped = FloatArray(9); val orientation = FloatArray(3)
    var smoothed: Float? = null
    var published: Float? = null
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ctx.display?.rotation
                else @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay?.rotation
            val (ax, ay) = when (display) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, ax, ay, remapped)
            SensorManager.getOrientation(remapped, orientation)
            val raw = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
            val previous = smoothed
            val next = if (previous == null) raw else {
                var delta = raw - previous
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                ((previous + delta * 0.25f) + 360f) % 360f
            }
            smoothed = next
            // Only a turn of a degree or more is passed on: a phone lying still drifts by less at each reading, and each
            // one would have every screen with a map composed and drawn again.
            val told = published
            if (told != null) {
                var moved = kotlin.math.abs(next - told)
                if (moved > 180f) moved = 360f - moved
                if (moved < 1f) return
            }
            published = next
            onHeading(next)
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    return { sm.unregisterListener(listener) }
}

fun hasNetwork(ctx: Context): Boolean = try {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    val n = cm.activeNetwork
    val caps = n?.let { cm.getNetworkCapabilities(it) }
    caps != null &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
} catch (e: Exception) { false }

object Prefs {
    private const val FILE = "kav"

    private fun store(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private const val RECENTS = "recents"
    private const val MAX_RECENTS = 8

    fun recents(ctx: Context): List<Transit.Place> = try {
        val raw = store(ctx).getString(RECENTS, "[]")
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            place(o)
        }
    } catch (e: Exception) { emptyList() }

    fun remember(ctx: Context, p: Transit.Place) {
        val kept = (listOf(p) + recents(ctx))
            .distinctBy { "%.5f,%.5f".format(java.util.Locale.US, it.lat, it.lon) }
            .take(MAX_RECENTS)
        val arr = org.json.JSONArray()
        for (r in kept) arr.put(json(r))
        store(ctx).edit().putString(RECENTS, arr.toString()).apply()
    }

    private const val TRIPS = "trips"
    private const val MAX_TRIPS = 100

    private fun place(o: org.json.JSONObject) = Transit.Place(
        o.optString("n"), o.optString("d"), o.optDouble("lat"), o.optDouble("lon"),
        o.optInt("t", 5), o.optInt("m", -1), stayMin = o.optInt("s"),
    )

    private fun json(p: Transit.Place): org.json.JSONObject = org.json.JSONObject()
        .put("n", p.name).put("d", p.detail).put("lat", p.lat).put("lon", p.lon)
        .put("t", p.type).put("m", p.mode).apply { if (p.stayMin > 0) put("s", p.stayMin) }

    fun trips(ctx: Context): List<RecentTrip> = try {
        val raw = store(ctx).getString(TRIPS, "[]")
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val to = o.optJSONObject("to") ?: return@mapNotNull null
            val stopovers = o.optJSONArray("stopovers")
            RecentTrip(
                o.optJSONObject("from")?.let { place(it) }, place(to), o.optLong("at"),
                stopovers = (0 until (stopovers?.length() ?: 0)).mapNotNull { j -> stopovers?.optJSONObject(j)?.let { place(it) } },
                planner = o.optString("planner"),
            )
        }
    } catch (e: Exception) { emptyList() }

    private fun tripKey(t: RecentTrip) =
        "%.4f,%.4f>%.4f,%.4f".format(java.util.Locale.US, t.from?.lat ?: 0.0, t.from?.lon ?: 0.0, t.to.lat, t.to.lon) +
            t.stopovers.joinToString("") { "+%.4f,%.4f".format(java.util.Locale.US, it.lat, it.lon) } +
            t.planner.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()

    private fun saveTrips(ctx: Context, trips: List<RecentTrip>) {
        val arr = org.json.JSONArray()
        for (t in trips) {
            arr.put(
                org.json.JSONObject()
                    .put("from", t.from?.let { json(it) }).put("to", json(t.to)).put("at", t.at)
                    .put("stopovers", org.json.JSONArray().apply { t.stopovers.forEach { put(json(it)) } })
                    .apply { if (t.planner.isNotEmpty()) put("planner", t.planner) },
            )
        }
        store(ctx).edit().putString(TRIPS, arr.toString()).apply()
    }

    // Every search that found routes, newest first. Opening one plans again from its places.
    fun rememberTrip(ctx: Context, trip: RecentTrip) {
        val kept = (listOf(trip) + trips(ctx)).distinctBy(::tripKey).take(MAX_TRIPS)
        saveTrips(ctx, kept)
    }

    fun clearRecents(ctx: Context) = store(ctx).edit().remove(RECENTS).apply()

    private const val RECENT_LINES = "recentLines"
    private const val MAX_RECENT_LINES = 8

    fun recentLines(ctx: Context): List<String> = try {
        val arr = org.json.JSONArray(store(ctx).getString(RECENT_LINES, "[]"))
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    fun rememberLine(ctx: Context, key: String) {
        val kept = (listOf(key) + recentLines(ctx)).distinct().take(MAX_RECENT_LINES)
        store(ctx).edit().putString(RECENT_LINES, org.json.JSONArray(kept).toString()).apply()
    }

    // Stops (Net.stopKey) and lines (lineKey) the rider saved, and their line alerts.
    private fun strings(ctx: Context, key: String): List<String> = try {
        val arr = org.json.JSONArray(store(ctx).getString(key, "[]"))
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    fun savedStops(ctx: Context): List<String> = strings(ctx, "savedStops")
    fun setSavedStops(ctx: Context, keys: List<String>) =
        store(ctx).edit().putString("savedStops", org.json.JSONArray(keys).toString()).apply()

    fun savedLines(ctx: Context): List<String> = strings(ctx, "savedLines")
    fun setSavedLines(ctx: Context, keys: List<String>) =
        store(ctx).edit().putString("savedLines", org.json.JSONArray(keys).toString()).apply()

    fun lineAlerts(ctx: Context): List<LineAlert> = LineAlert.list(store(ctx).getString("lineAlerts", null))
    fun setLineAlerts(ctx: Context, alerts: List<LineAlert>) =
        store(ctx).edit().putString("lineAlerts", LineAlert.json(alerts)).apply()

    // An alert swiped away stays quiet until its window ends.
    fun alertQuietUntil(ctx: Context, id: Long): Long =
        runCatching { org.json.JSONObject(store(ctx).getString("alertsQuiet", "{}")!!).optLong(id.toString()) }.getOrDefault(0L)
    fun quietAlert(ctx: Context, id: Long, until: Long) {
        val o = runCatching { org.json.JSONObject(store(ctx).getString("alertsQuiet", "{}")!!) }.getOrDefault(org.json.JSONObject())
        val now = System.currentTimeMillis() / 1000
        o.keys().asSequence().toList().forEach { if (o.optLong(it) < now) o.remove(it) }
        store(ctx).edit().putString("alertsQuiet", o.put(id.toString(), until).toString()).apply()
    }

    fun accent(ctx: Context): Int = store(ctx).getInt("accent", android.graphics.Color.rgb(0x9A, 0xBE, 0xFF))
    fun setAccent(ctx: Context, argb: Int) = store(ctx).edit().putInt("accent", argb).apply()

    fun lang(ctx: Context): Lang =
        Lang.entries.firstOrNull { it.code == store(ctx).getString("lang", null) } ?: Lang.EN
    fun setLang(ctx: Context, lang: Lang) = store(ctx).edit().putString("lang", lang.code).apply()

    // Stops', stations' and towns' names in English rather than the ministry's Hebrew (StopNames), Hebrew until chosen.
    fun stopNamesEnglish(ctx: Context): Boolean = store(ctx).getString("stopNames", null) == "en"
    fun setStopNamesEnglish(ctx: Context, on: Boolean) = store(ctx).edit().putString("stopNames", if (on) "en" else "he").apply()

    // The language of the map's own names (MapFile.labels), Hebrew until chosen: the map as it was before the choice.
    fun buildings3d(ctx: Context): Boolean = store(ctx).getBoolean("buildings3d", false)
    fun setBuildings3d(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("buildings3d", on).apply()
    fun mapLabels(ctx: Context): Lang = if (store(ctx).getString("mapLabels", null) == "en") Lang.EN else Lang.HE
    fun setMapLabels(ctx: Context, lang: Lang) = store(ctx).edit().putString("mapLabels", lang.code).apply()

    fun showCo2(ctx: Context): Boolean = store(ctx).getBoolean("showCo2", false)
    fun setShowCo2(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("showCo2", on).apply()

    fun showAccessibility(ctx: Context): Boolean = store(ctx).getBoolean("showAccessibility", true)
    fun setShowAccessibility(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("showAccessibility", on).apply()

    fun fareProfile(ctx: Context): uk.noammm.kav.data.FareProfile =
        uk.noammm.kav.data.FareProfile.entries.firstOrNull { it.name == store(ctx).getString("fareProfile", null) }
            ?: uk.noammm.kav.data.FareProfile.REGULAR
    fun setFareProfile(ctx: Context, profile: uk.noammm.kav.data.FareProfile) = store(ctx).edit().putString("fareProfile", profile.name).apply()
    // "Get it from Rav-Pass" (FareFromRavPass): when it was tapped, and what the reader found ("kind|profile|name shown").
    fun fareAskedAt(ctx: Context): Long = store(ctx).getLong("fareAskedAt", 0L)
    fun setFareAskedAt(ctx: Context, at: Long) = store(ctx).edit().putLong("fareAskedAt", at).apply()
    fun fareFromRavPass(ctx: Context): String? = store(ctx).getString("fareFromRavPass", null)
    fun setFareFromRavPass(ctx: Context, found: String?) = store(ctx).edit().putString("fareFromRavPass", found).apply()

    // The rider's planner settings, by their letters (PlanSettings).
    fun planSettings(ctx: Context): uk.noammm.kav.data.PlanSettings = uk.noammm.kav.data.PlanSettings.of(store(ctx).getString("planSettings", null))
    fun setPlanSettings(ctx: Context, settings: uk.noammm.kav.data.PlanSettings) =
        store(ctx).edit().putString("planSettings", settings.letters).apply()
    // The times kept in the When sheet (SavedTime), oldest first.
    fun savedTimes(ctx: Context): List<uk.noammm.kav.data.SavedTime> =
        uk.noammm.kav.data.SavedTime.list(store(ctx).getString(uk.noammm.kav.data.SavedTime.KEY, null))
    fun setSavedTimes(ctx: Context, times: List<uk.noammm.kav.data.SavedTime>) =
        store(ctx).edit().putString(uk.noammm.kav.data.SavedTime.KEY, uk.noammm.kav.data.SavedTime.text(times)).apply()

    // "Pay with Rav-Pass" (PayMode): turned on with every permission it needs; absent on a phone that hasn't decided yet.
    // And whether Home still offers it once, to a rider updating to Kav 5 without it.
    fun ravPassDecided(ctx: Context): Boolean = store(ctx).contains("ravPass")
    fun ravPass(ctx: Context): Boolean = store(ctx).getBoolean("ravPass", false)
    fun setRavPass(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("ravPass", on).apply()
    fun ravPassOffer(ctx: Context): Boolean = store(ctx).getBoolean("ravPassOffer", false)
    fun setRavPassOffer(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("ravPassOffer", on).apply()
    // Which app paying goes through (ui.PayApp): its enum name, empty until chosen (then Rav-Pass).
    fun payApp(ctx: Context): String = store(ctx).getString("payApp", "") ?: ""
    fun setPayApp(ctx: Context, app: String) = store(ctx).edit().putString("payApp", app).apply()

    // The button over Rav-Pass back to Kav (PayOverlay): on with "Pay with Rav-Pass", and where the button was left.
    fun payOverlay(ctx: Context): Boolean = store(ctx).getBoolean("payOverlay", false)
    fun setPayOverlay(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("payOverlay", on).apply()
    fun payOverlaySpot(ctx: Context): String? = store(ctx).getString("payOverlaySpot", null)
    fun setPayOverlaySpot(ctx: Context, spot: String) = store(ctx).edit().putString("payOverlaySpot", spot).apply()

    // The ticket reader (TicketReader): the rider's yes, given in Kav's own sheet, and whether a ticket read after a Pay tap
    // brings them back to Kav.
    fun tickets(ctx: Context): Boolean = store(ctx).getBoolean("tickets", false)
    fun setTickets(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("tickets", on).apply()
    // When a Pay tap last opened Rav-Pass: Rav-Pass's Done screen soon after it is a purchase Kav saw.
    fun payTappedAt(ctx: Context): Long = store(ctx).getLong("payTappedAt", 0L)
    fun setPayTappedAt(ctx: Context, at: Long) = store(ctx).edit().putLong("payTappedAt", at).apply()
    // A live ticket's quiet notification (TicketNotice): on until the rider turns it off.

    // Line alerts for every planner's trips (RideAlerts): off until the rider asks for them.
    fun rideAlerts(ctx: Context): Boolean = store(ctx).getBoolean("rideAlerts", false)
    fun setRideAlerts(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("rideAlerts", on).apply()
    // That switch among a store's or a backup's names: its own, or the one an older Kav kept it under.
    internal fun alertsSwitch(names: Iterable<String>): String? = names.toList().run { firstOrNull { it == "rideAlerts" }
        ?: firstOrNull { it.endsWith("Alerts") && it != "lineAlerts" && it != "spokenAlerts" } }
    // How busy a ride usually is (CrowdChoice): from Kav's server (KavCrowd), from Google (GoogleCrowd), or not shown. A new
    // install starts on Kav's; one from before 5.0 keeps what Google's switch was, on unless turned off (upgrade()).
    const val CROWD_KAV = "kav"
    const val CROWD_GOOGLE = "google"
    const val CROWD_OFF = "off"
    val CROWDS = listOf(CROWD_KAV, CROWD_GOOGLE, CROWD_OFF)
    internal fun crowdOf(stored: String?): String = stored?.takeIf { it in CROWDS } ?: CROWD_KAV
    internal fun crowdFromGoogle(on: Boolean): String = if (on) CROWD_GOOGLE else CROWD_OFF
    fun crowdSource(ctx: Context): String = crowdOf(store(ctx).getString("crowdSource", null))
    fun setCrowdSource(ctx: Context, c: String) = store(ctx).edit().putString("crowdSource", c).apply()
    // Where they come from (Source.ALERTS): Kav's server until another is picked.
    // The alerts' source picked in each of Kav's languages (RideAlerts.source); a language with none takes its default.
    fun alertSources(ctx: Context): Map<String, Source> = Lang.entries.mapNotNull { l ->
        Source.of(store(ctx).getString("alertSource.${l.code}", null))?.takeIf { it in Source.ALERTS }?.let { l.code to it }
    }.toMap()
    fun setAlertSource(ctx: Context, s: Source) = store(ctx).edit().putString("alertSource.${T.lang.code}", s.key).apply()
    // The line alerts' notices: "auto" in Kav's language, "en" or "he".
    fun alertLanguage(ctx: Context): String = store(ctx).getString("alertLanguage", null)?.takeIf { it in ALERT_LANGUAGES } ?: "auto"
    fun setAlertLanguage(ctx: Context, l: String) = store(ctx).edit().putString("alertLanguage", l).apply()
    val ALERT_LANGUAGES = listOf("auto", "en", "he")

    // Stops' photos from Street View (StopPhotos): on until the rider turns them off.
    fun stopPhotos(ctx: Context): Boolean = store(ctx).getBoolean("stopPhotos", true)
    fun setStopPhotos(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("stopPhotos", on).apply()

    // Navigation in a small window when Kav is left.
    fun pipNavigation(ctx: Context): Boolean = store(ctx).getBoolean("pipNavigation", true)
    fun setPipNavigation(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("pipNavigation", on).apply()
    // Both off in the F-Droid build (BuildConfig.UPDATER), which F-Droid updates.
    fun updatePrompts(ctx: Context): Boolean = BuildConfig.UPDATER && store(ctx).getBoolean("updatePrompts", true)
    fun setUpdatePrompts(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("updatePrompts", on).apply()
    // Kav downloading new versions by itself, to install when the rider says so (Checks, AutoUpdate): on unless turned
    // off, for riders from before it too.
    fun autoUpdate(ctx: Context): Boolean = BuildConfig.UPDATER && store(ctx).getBoolean("autoUpdate", true)
    fun setAutoUpdate(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("autoUpdate", on).apply()

    fun spokenAlerts(ctx: Context): Boolean = store(ctx).getBoolean("spokenAlerts", false)
    fun setSpokenAlerts(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("spokenAlerts", on).apply()
    // A walk's turns said out loud as each comes (TripService), apart from the other alerts.
    fun spokenTurns(ctx: Context): Boolean = store(ctx).getBoolean("spokenTurns", false)
    fun setSpokenTurns(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("spokenTurns", on).apply()

    fun twelveHour(ctx: Context): Boolean = store(ctx).getBoolean("twelveHour", false)
    fun setTwelveHour(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("twelveHour", on).apply()

    // Settings' System look, kept as "system": it follows the phone's dark theme.
    internal const val SYSTEM_LOOK = "system"
    private val looks = Look.entries.map { it.name.lowercase() } + SYSTEM_LOOK

    // The look a stored choice gives: System's is OLED while the phone is dark and Light while it isn't; none stored, OLED.
    internal fun lookOf(stored: String?, night: Boolean): Look =
        if (stored == SYSTEM_LOOK) (if (night) Look.OLED else Look.LIGHT)
        else Look.entries.firstOrNull { it.name.lowercase() == stored } ?: Look.OLED
    internal fun night(uiMode: Int): Boolean =
        (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    fun look(ctx: Context): Look = lookOf(store(ctx).getString("look", null), night(ctx.resources.configuration.uiMode))
    fun followsSystem(ctx: Context): Boolean = store(ctx).getString("look", null) == SYSTEM_LOOK
    // null: System.
    fun setLook(ctx: Context, look: Look?) = store(ctx).edit().putString("look", look?.name?.lowercase() ?: SYSTEM_LOOK).apply()

    fun liquidGlass(ctx: Context): Boolean = liquidGlassReady && store(ctx).getBoolean("liquidGlass", true)
    fun setLiquidGlass(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("liquidGlass", on).apply()

    fun placeSearch(ctx: Context): String? = store(ctx).getString("placeSearch", null)
    fun setPlaceSearch(ctx: Context, key: String) = store(ctx).edit().putString("placeSearch", key).apply()
    fun privateSearch(ctx: Context): Boolean = store(ctx).getBoolean("privateSearch", true)
    fun setPrivateSearch(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("privateSearch", on).apply()

    // Before live locations and trip planning were two choices: live times from a server, and Kav's planner on it.
    // Still written, for a backup an older Kav reads.
    private fun liveFromKav(ctx: Context): Boolean = store(ctx).getBoolean("liveFromKav", false)

    // A started trip goes straight to its first walk instead of waiting for its time or for the rider to move off.
    fun beginTripsImmediately(ctx: Context): Boolean = store(ctx).getBoolean("beginTripsImmediately", false)
    fun setBeginTripsImmediately(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("beginTripsImmediately", on).apply()

    private fun planWithKav(ctx: Context): Boolean = store(ctx).getBoolean("planWithKav", false)

    // Where live locations come from, and who plans trips: Kav's server or the rider's own, each on its own.
    fun liveSource(ctx: Context): Source = sources(ctx).first
    fun planSource(ctx: Context): Source = sources(ctx).second
    fun setLiveSource(ctx: Context, s: Source) {
        sources(ctx)
        store(ctx).edit().putString("liveSource", s.key).putBoolean("liveFromKav", true).apply()
    }
    fun setPlanSource(ctx: Context, s: Source) {
        sources(ctx)
        store(ctx).edit().putString("planSource", s.key).putBoolean("planWithKav", true).apply()
    }

    // Both choices; the first time, as the two old switches had them, and both kept then: the old switches are still
    // written for an older Kav, and one choice's mustn't move the other.
    private fun sources(ctx: Context): Pair<Source, Source> {
        val s = store(ctx)
        val live = Source.storedLive(s.getString("liveSource", null))
        val plan = Source.stored(s.getString("planSource", null))
        if (live != null && plan != null) return live to plan
        val old = Sources.fromOld(liveFromKav(ctx), planWithKav(ctx), ownServer(ctx).isNotEmpty())
        val both = (live ?: old.first) to (plan ?: old.second)
        s.edit().putString("liveSource", both.first.key).putString("planSource", both.second.key).apply()
        return both
    }

    // Where the timetable comes from, a choice of its own; the first time, where the week came from until then.
    fun timetableSource(ctx: Context): Source = Source.stored(store(ctx).getString("timetableSource", null))
        ?: Sources.timetableOf(liveSource(ctx), planSource(ctx), ownServer(ctx).isNotEmpty())
            .also { store(ctx).edit().putString("timetableSource", it.key).apply() }
    fun setTimetableSource(ctx: Context, s: Source) = store(ctx).edit().putString("timetableSource", s.key).apply()

    // The three choices and the rider's own server, from the settings.
    fun loadSources(ctx: Context) {
        KavLive.ownServer = ownServer(ctx)
        KavLive.ownKey = kavServerKey(ctx)
        KavLive.ownDevice = kavServerDevice(ctx)
        KavLive.ownUser = kavServerUser(ctx)
        KavLive.source = liveSource(ctx)
        KavPlan.source = planSource(ctx)
        KavPlan.everyPlanner = everyPlanner(ctx)
        KavPlan.tabOrder = plannerTabs(ctx)
        KavPlan.hiddenTabs = hiddenTabs(ctx)
        KavPlan.settings = planSettings(ctx)
        uk.noammm.kav.data.TripShare.picked = shareServer(ctx)
        uk.noammm.kav.data.Schedules.source = timetableSource(ctx)
    }

    internal fun linkStyle(ctx: Context): uk.noammm.kav.data.ShareStyle = runCatching {
        uk.noammm.kav.data.ShareStyle.read(org.json.JSONObject(store(ctx).getString("linkStyle", "{}")!!))
    }.getOrDefault(uk.noammm.kav.data.ShareStyle())
    internal fun setLinkStyle(ctx: Context, style: uk.noammm.kav.data.ShareStyle) =
        store(ctx).edit().putString("linkStyle", style.json().toString()).apply()

    // How tall Home's card's nearest-station page last was, in pixels; 0 before it ever showed. Kav opens the card with
    // the page and its dot from the first frame when it had one.
    fun nearPage(ctx: Context): Int = store(ctx).getInt("nearPage", 0)
    fun setNearPage(ctx: Context, height: Int) = store(ctx).edit().putInt("nearPage", height).apply()

    fun everyPlanner(ctx: Context): Boolean = store(ctx).getBoolean("everyPlanner", false)
    fun setEveryPlanner(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("everyPlanner", on).apply()
    // The tabs' order by their keys (KavPlan.plannerTabs): the planners the rider moved first, then any they haven't
    // placed, Kav's in Settings' order.
    fun plannerTabs(ctx: Context): List<String> {
        val kept = store(ctx).getString("plannerTabs", null).orEmpty().split(',')
            .filter { Source.of(it) in Source.PLANNING || uk.noammm.kav.ext.Extensions.isAddress(it) }.distinct()
        return kept + Source.PLANNING.map { it.key }.filter { it !in kept }
    }
    fun setPlannerTabs(ctx: Context, order: List<String>) =
        store(ctx).edit().putString("plannerTabs", order.joinToString(",")).apply()
    // The planners taken off the tabs.
    fun hiddenTabs(ctx: Context): Set<Source> =
        store(ctx).getString("plannerTabsHidden", null).orEmpty().split(',').mapNotNull(Source::of).filter { it in Source.PLANNING }.toSet()
    fun setHiddenTabs(ctx: Context, hidden: Set<Source>) =
        store(ctx).edit().putString("plannerTabsHidden", hidden.joinToString(",") { it.key }).apply()

    // The server share links are made on, Kav's or the rider's own; null until picked (Sources.shareOn).
    fun shareServer(ctx: Context): Source? = Source.of(store(ctx).getString("shareServer", null))?.takeIf { it == Source.KAV || it == Source.OWN }
    fun setShareServer(ctx: Context, s: Source) = store(ctx).edit().putString("shareServer", s.key).apply()

    // Keep the downloaded week fresh in the background, unless the rider has switched it off.
    fun timetableUpdates(ctx: Context): Boolean = store(ctx).getBoolean("timetableUpdates", true)
    fun setTimetableUpdates(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("timetableUpdates", on).apply()
    // The daily timetable and new versions on mobile data too, not only on Wi-Fi.
    fun timetableOnMobile(ctx: Context): Boolean = store(ctx).getBoolean("timetableOnMobile", false)
    fun setTimetableOnMobile(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("timetableOnMobile", on).apply()
    fun updatesOnMobile(ctx: Context): Boolean = store(ctx).getBoolean("updatesOnMobile", false)
    fun setUpdatesOnMobile(ctx: Context, on: Boolean) = store(ctx).edit().putBoolean("updatesOnMobile", on).apply()

    // The rider's own copy of Kav's server, empty while none is set up; the key it takes requests signed with (its
    // KAV_APP_KEY), and the public half of the key its timetable is signed with, as it gave it. Kept under the names
    // they had when Kav's server could be one of them.
    fun ownServer(ctx: Context): String =
        store(ctx).getString("kavServer", null)?.let(KavLive::serverAddress)?.takeIf { it != KavLive.KAV_SERVER }.orEmpty()
    fun kavServerKey(ctx: Context): String = store(ctx).getString("kavServerKey", null).orEmpty()
    // device and user: this phone's id there and who signed it in, on a server with accounts (KavLive.ownDevice).
    fun setOwnServer(ctx: Context, address: String, key: String, bundleKey: String, device: String = "", user: String = "") =
        store(ctx).edit().putString("kavServer", address).putString("kavServerKey", key).putString("kavServerBundleKey", bundleKey)
            .putString("kavServerDevice", device).putString("kavServerUser", user).apply()
    fun kavServerDevice(ctx: Context): String = store(ctx).getString("kavServerDevice", null).orEmpty()
    fun kavServerUser(ctx: Context): String = store(ctx).getString("kavServerUser", null).orEmpty()
    fun kavServerBundleKey(ctx: Context): String = store(ctx).getString("kavServerBundleKey", null).orEmpty()
    fun setKavServerBundleKey(ctx: Context, key: String) = store(ctx).edit().putString("kavServerBundleKey", key).apply()

    // PR 71's test builds called the standard map "offline".
    private val mapTypes = MapFile.Type.entries.map { it.name.lowercase() } + "offline"
    fun mapType(ctx: Context): MapFile.Type = store(ctx).getString("mapType", null).let { saved ->
        MapFile.Type.entries.firstOrNull { it.name.lowercase() == saved } ?: MapFile.Type.STANDARD
    }
    fun setMapType(ctx: Context, type: MapFile.Type) = store(ctx).edit().putString("mapType", type.name.lowercase()).apply()

    fun seen(ctx: Context): Seen = Seen.entries.firstOrNull { it.name == store(ctx).getString("seen", null) } ?: Seen.CITY
    fun seenPlace(ctx: Context): Transit.Place? = savedPlace(ctx, "seenPlace")
    fun setSeen(ctx: Context, how: Seen, place: Transit.Place?) {
        val e = store(ctx).edit().putString("seen", how.name)
        if (place != null) e.putString("seenPlace", json(place).toString()) else e.remove("seenPlace")
        e.apply()
    }
    fun seenCity(ctx: Context): Transit.Place? = savedPlace(ctx, "seenCity")
    fun setSeenCity(ctx: Context, city: Transit.Place) = store(ctx).edit().putString("seenCity", json(city).toString()).apply()
    private fun savedPlace(ctx: Context, key: String): Transit.Place? =
        store(ctx).getString(key, null)?.let { runCatching { place(org.json.JSONObject(it)) }.getOrNull() }
            ?.takeIf(::inIsrael)
    private fun inIsrael(p: Transit.Place) = p.lat in 29.0..34.0 && p.lon in 34.0..36.5

    fun onboarded(ctx: Context): Boolean = store(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context) = store(ctx).edit().putBoolean("onboarded", true).putBoolean("pickSupport", true).apply()

    fun pickLook(ctx: Context): Boolean = store(ctx).getBoolean("pickLook", false)
    fun lookPicked(ctx: Context) {
        val e = store(ctx).edit().remove("pickLook").putString("look", if (K.followsSystem) SYSTEM_LOOK else K.look.name.lowercase())
        if (liquidGlassReady) e.putBoolean("liquidGlass", K.liquid)
        e.apply()
    }

    fun pickSupport(ctx: Context): Boolean = store(ctx).getBoolean("pickSupport", false)
    fun supportShown(ctx: Context) = store(ctx).edit().remove("pickSupport").apply()

    // Runs once, the first time 2.0, 3.0, 4.0, 4.3 and then 5.0 open on an install that had an older version.
    fun upgrade(ctx: Context) {
        val s = store(ctx)
        val version = s.getInt("prefsVersion", 0)
        if (version >= 7) return
        val e = s.edit()
        if (version < 2) {
            s.getStringSet("filtersOff", null)?.let { off ->
                val cable = if ("CARMELIT" in off && "RAKAVLIT" in off) setOf("CARMELIT_RAKAVLIT") else emptySet()
                e.putStringSet("filtersOff", off - setOf("CARMELIT", "RAKAVLIT", "SHARE_TAXI") + cable)
            }
            if (s.getBoolean("onboarded", false)) {
                e.putBoolean("privateSearch", true).putBoolean("pickLook", true).putBoolean("pickSupport", true)
            }
        }
        // Stop names have a language of their own, Hebrew unless chosen. A rider who had Kav in English keeps English
        // names; after this the two settings never move each other.
        if (version < 3 && s.getBoolean("onboarded", false) && !s.contains("stopNames")) e.putString("stopNames", if (lang(ctx) == Lang.EN) "en" else "he")
        // 4.0: alerts take their language's source in place of the one setting all languages shared.
        if (version < 4) e.remove("alertSource")
        // 4.3: a choice of a provider this Kav no longer has is Kav's own from now on, the alerts' switch has its own
        // name, and a shared trip's link is always Kav's.
        if (version < 5) {
            for (key in listOf("planSource", "timetableSource") + Lang.entries.map { "alertSource.${it.code}" })
                s.getString(key, null)?.takeIf { Source.of(it) == null }?.let { e.putString(key, Source.KAV.key) }
            alertsSwitch(s.all.keys)?.takeIf { it != "rideAlerts" }?.let { old ->
                (s.all[old] as? Boolean)?.let { e.putBoolean("rideAlerts", it) }
                e.remove(old)
            }
            e.remove("shareLinks")
        }
        // 5.0: choices of the providers this Kav no longer has are Kav's own (Sources.migrated). The sign-in kept for paying
        // for rides and the device id made for sharing trips to those providers go; nothing is sent anywhere. Also on a
        // phone that ran a test build of 5.0 before this step was in it (prefsVersion 6).
        if (version < 7) {
            Sources.migrated(s.all).forEach { (key, value) -> e.putString(key, value) }
            e.remove("hopOnDevice")
            ctx.getSharedPreferences("rav-pass", Context.MODE_PRIVATE).edit().remove("session").apply()
        }
        // 5.0: how busy comes from Kav's server too; a rider from before keeps Google's switch as it was.
        if (version < 7 && s.getBoolean("onboarded", false) && !s.contains("crowdSource")) {
            e.putString("crowdSource", crowdFromGoogle(s.getBoolean("googleCrowd", true)))
        }
        e.putInt("prefsVersion", 7).apply()
    }

    fun filters(ctx: Context): Set<ResultFilter> {
        val off = store(ctx).getStringSet("filtersOff", emptySet()).orEmpty()
        return ResultFilter.entries.filter { it.name !in off }.toSet()
    }
    fun setFilter(ctx: Context, f: ResultFilter, on: Boolean) {
        val off = store(ctx).getStringSet("filtersOff", emptySet()).orEmpty().toMutableSet()
        if (on) off.remove(f.name) else off.add(f.name)
        store(ctx).edit().putStringSet("filtersOff", off).apply()
    }

    private val oldFilters = setOf(
        ResultFilter.BUS, ResultFilter.TRAIN, ResultFilter.LIGHT_RAIL, ResultFilter.TAXI, ResultFilter.BIKE, ResultFilter.WALK,
    )

    fun backupJson(ctx: Context): org.json.JSONObject {
        val s = store(ctx)
        fun arr(key: String) =
            runCatching { org.json.JSONArray(s.getString(key, "[]")) }.getOrElse { org.json.JSONArray() }
        val on = filters(ctx)
        // 1.5 reads filtersOff, where Carmelit and Rakavlit are apart and share taxis have their own switch.
        val off = ResultFilter.entries.filter { it !in on }.map { it.name } +
            (if (ResultFilter.CARMELIT_RAKAVLIT in on) emptyList() else listOf("CARMELIT", "RAKAVLIT")) +
            (if (ResultFilter.BUS in on) emptyList() else listOf("SHARE_TAXI"))
        return org.json.JSONObject()
            .put("favourites", arr("favourites"))
            .put("trips", arr("trips"))
            .put("recents", arr("recents"))
            .put("recentLines", arr(RECENT_LINES))
            .put("savedStops", arr("savedStops"))
            .put("savedLines", arr("savedLines"))
            .put("lineAlerts", arr("lineAlerts"))
            .put("lang", lang(ctx).code)
            .put("stopNames", if (stopNamesEnglish(ctx)) "en" else "he")
            .put("accent", accent(ctx))
            .put("look", if (followsSystem(ctx)) SYSTEM_LOOK else look(ctx).name.lowercase())
            .put("liquidGlass", s.getBoolean("liquidGlass", true))
            .put("privateSearch", privateSearch(ctx))
            .put("placeSearch", placeSearch(ctx))
            .put("liveSource", liveSource(ctx).key)
            .put("planSource", planSource(ctx).key)
            .put("timetableSource", timetableSource(ctx).key)
            .put("everyPlanner", everyPlanner(ctx))
            .put("linkStyle", linkStyle(ctx).json())
            .put("plannerTabs", plannerTabs(ctx).joinToString(","))
            .put("plannerTabsHidden", hiddenTabs(ctx).joinToString(",") { it.key })
            .apply { shareServer(ctx)?.let { put("shareServer", it.key) } }
            .put("liveFromKav", true)
            .put("planWithKav", true)
            .put("beginTripsImmediately", beginTripsImmediately(ctx))
            .put("timetableUpdates", timetableUpdates(ctx))
            .put("timetableOnMobile", timetableOnMobile(ctx))
            // Kav's server by the address every Kav knows as Kav's, an older one too (KavLive.serverAddress).
            .put("kavServer", ownServer(ctx).ifEmpty { KavLive.OLD_KAV_SERVER })
            // A phone's own sign-in is its alone: a backup restored on another phone signs that one in afresh.
            .put("kavServerKey", if (kavServerDevice(ctx).isEmpty()) kavServerKey(ctx) else "")
            .put("mapType", mapType(ctx).name.lowercase())
            .put("buildings3d", buildings3d(ctx))
            .put("seen", seen(ctx).name)
            .apply { seenPlace(ctx)?.let { put("seenPlace", json(it)) } }
            .put("filters", org.json.JSONObject().apply { ResultFilter.entries.forEach { put(it.name, it in on) } })
            .put("filtersOff", org.json.JSONArray(off))
            .put("showCo2", showCo2(ctx))
            .put("showAccessibility", showAccessibility(ctx))
            .put("fareProfile", fareProfile(ctx).name)
            .put("planSettings", planSettings(ctx).letters)
            .put(uk.noammm.kav.data.SavedTime.KEY, uk.noammm.kav.data.SavedTime.text(savedTimes(ctx)))
            .put("rideAlerts", rideAlerts(ctx))
            .apply { alertSources(ctx).forEach { (lang, s) -> put("alertSource.$lang", s.key) } }
            .put("alertLanguage", alertLanguage(ctx))
            .put("crowdSource", crowdSource(ctx))
            // For an older Kav restoring this backup.
            .put("googleCrowd", crowdSource(ctx) == CROWD_GOOGLE)
            .put("stopPhotos", stopPhotos(ctx))
            .put("pipNavigation", pipNavigation(ctx))
            .put("ravPass", ravPass(ctx))
            .put("payApp", payApp(ctx))
            .put("updatePrompts", updatePrompts(ctx))
            .put("autoUpdate", autoUpdate(ctx))
            .put("updatesOnMobile", updatesOnMobile(ctx))
            .put("spokenAlerts", spokenAlerts(ctx))
            .put("spokenTurns", spokenTurns(ctx))
            .put("twelveHour", twelveHour(ctx))
    }

    // Only what the file holds is restored, so settings an older backup lacks stay as they are.
    fun restoreBackup(ctx: Context, o: org.json.JSONObject) {
        val on = filters(ctx)
        val e = store(ctx).edit()
        for (key in listOf("favourites", "trips", "recents", RECENT_LINES, "savedStops", "savedLines", "lineAlerts")) {
            o.optJSONArray(key)?.let { e.putString(key, it.toString()) }
        }
        if (o.has("lang")) e.putString("lang", o.optString("lang"))
        o.optString("stopNames").takeIf { it == "en" || it == "he" }?.let { e.putString("stopNames", it) }
        if (o.has("accent")) e.putInt("accent", o.optInt("accent"))
        o.optString("look").takeIf { it in looks }?.let { e.putString("look", it) }
        if (o.has("liquidGlass")) e.putBoolean("liquidGlass", o.optBoolean("liquidGlass"))
        if (o.has("privateSearch")) e.putBoolean("privateSearch", o.optBoolean("privateSearch"))
        uk.noammm.kav.data.Search.ALL.firstOrNull { it.key == o.optString("placeSearch") }?.let { e.putString("placeSearch", it.key) }
        if (o.has("beginTripsImmediately")) e.putBoolean("beginTripsImmediately", o.optBoolean("beginTripsImmediately"))
        // A backup from before the timetable was downloaded (no timetableSource) wrote this off by default, when off
        // meant the week Kav came with; now off means a week that's never refreshed, so it's left at its default.
        if (o.has("timetableUpdates") && o.has("timetableSource")) e.putBoolean("timetableUpdates", o.optBoolean("timetableUpdates"))
        val server = KavLive.serverAddress(o.optString("kavServer"))
        server?.let {
            // The server's timetable key is asked of it again (Timetable.refresh).
            if (it != (ownServer(ctx).ifEmpty { KavLive.KAV_SERVER })) e.remove("kavServerBundleKey")
            e.putString("kavServer", it)
        }
        if (o.has("kavServerKey")) e.putString("kavServerKey", o.optString("kavServerKey"))
        // Each choice as the file has it, or as its old switches had it.
        val (live, plan) = Sources.fromBackup(o, (server ?: ownServer(ctx).ifEmpty { KavLive.KAV_SERVER }) != KavLive.KAV_SERVER)
        live?.let { e.putString("liveSource", it.key).putBoolean("liveFromKav", true) }
        plan?.let { e.putString("planSource", it.key).putBoolean("planWithKav", true) }
        Source.stored(o.optString("timetableSource"))?.let { e.putString("timetableSource", it.key) }
        for (l in Lang.entries) Source.of(o.optString("alertSource.${l.code}"))?.takeIf { it in Source.ALERTS }
            ?.let { e.putString("alertSource.${l.code}", it.key) }
        o.optString("alertLanguage").takeIf { it in ALERT_LANGUAGES }?.let { e.putString("alertLanguage", it) }
        o.optString("crowdSource").takeIf { it in CROWDS }?.let { e.putString("crowdSource", it) }
            ?: run { if (o.has("googleCrowd")) e.putString("crowdSource", crowdFromGoogle(o.optBoolean("googleCrowd"))) }
        o.optString("mapType").takeIf { it in mapTypes }?.let { e.putString("mapType", it) }
        if (o.has("buildings3d")) e.putBoolean("buildings3d", o.optBoolean("buildings3d"))
        o.optString("seen").takeIf { n -> Seen.entries.any { it.name == n } }?.let { e.putString("seen", it) }
        o.optJSONObject("seenPlace")?.let { place(it) }?.takeIf(::inIsrael)?.let { e.putString("seenPlace", json(it).toString()) }
        if (o.has("showCo2")) e.putBoolean("showCo2", o.optBoolean("showCo2"))
        if (o.has("showAccessibility")) e.putBoolean("showAccessibility", o.optBoolean("showAccessibility"))
        o.optString("fareProfile").takeIf { name -> uk.noammm.kav.data.FareProfile.entries.any { it.name == name } }
            ?.let { e.putString("fareProfile", it) }
        o.optString("payApp").takeIf { it.isNotEmpty() }?.let { e.putString("payApp", it) }
        o.optString("planSettings").takeIf { it.isNotEmpty() }?.let { e.putString("planSettings", uk.noammm.kav.data.PlanSettings.of(it).letters) }
        uk.noammm.kav.data.SavedTime.restored(o)?.let { e.putString(uk.noammm.kav.data.SavedTime.KEY, uk.noammm.kav.data.SavedTime.text(it)) }
        o.optString("plannerTabs").takeIf { it.isNotEmpty() }?.let { e.putString("plannerTabs", it) }
        if (o.has("plannerTabsHidden")) e.putString("plannerTabsHidden", o.optString("plannerTabsHidden"))
        Source.of(o.optString("shareServer"))?.takeIf { it == Source.KAV || it == Source.OWN }?.let { e.putString("shareServer", it.key) }
        o.optJSONObject("linkStyle")?.let { e.putString("linkStyle", uk.noammm.kav.data.ShareStyle.read(it).json().toString()) }
        for (key in listOf("pipNavigation", "ravPass", "updatePrompts", "autoUpdate", "updatesOnMobile", "timetableOnMobile", "everyPlanner", "stopPhotos")) {
            if (o.has(key)) e.putBoolean(key, o.optBoolean(key))
        }
        alertsSwitch(o.keys().asSequence().asIterable())?.let { e.putBoolean("rideAlerts", o.optBoolean(it)) }
        if (o.has("spokenAlerts")) e.putBoolean("spokenAlerts", o.optBoolean("spokenAlerts"))
        if (o.has("spokenTurns")) e.putBoolean("spokenTurns", o.optBoolean("spokenTurns"))
        if (o.has("twelveHour")) e.putBoolean("twelveHour", o.optBoolean("twelveHour"))
        val chosen = o.optJSONObject("filters")
        val oldOff = o.optJSONArray("filtersOff")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
        val off = ResultFilter.entries.filter { f ->
            when {
                chosen != null && chosen.has(f.name) -> !chosen.optBoolean(f.name)
                chosen == null && oldOff != null && f in oldFilters -> f.name in oldOff
                else -> f !in on
            }
        }
        e.putStringSet("filtersOff", off.map { it.name }.toSet())
        e.apply()
    }

    fun favourites(ctx: Context): List<Favourite> = try {
        val arr = org.json.JSONArray(store(ctx).getString("favourites", "[]"))
        val saved = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Favourite(
                o.optString("id"), o.optString("name"), o.optString("icon"),
                o.optJSONObject("place")?.let { place(it) },
            )
        }
        if (saved.any { it.id == Favourite.HOME }) saved else listOf(Favourite.home()) + saved
    } catch (e: Exception) { listOf(Favourite.home()) }

    fun saveFavourites(ctx: Context, list: List<Favourite>) {
        val arr = org.json.JSONArray()
        for (f in list) {
            arr.put(
                org.json.JSONObject().put("id", f.id).put("name", f.name).put("icon", f.icon)
                    .put("place", f.place?.let { json(it) }),
            )
        }
        store(ctx).edit().putString("favourites", arr.toString()).apply()
    }
    fun lineBadges(ctx: Context): LineBadgeAppearance =
        LineBadgeAppearance.of(store(ctx).getString("lineBadgeAppearance", null))
    fun setLineBadges(ctx: Context, a: LineBadgeAppearance) = store(ctx).edit().putString("lineBadgeAppearance", a.name).apply()
}

