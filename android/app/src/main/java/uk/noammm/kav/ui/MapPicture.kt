package uk.noammm.kav.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.MapPacks
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// Home's map, live, from under the search to under the places' tiles: the step of the trip on now as the trip's own map
// shows it (NavigateMap), or with no trip the vehicles around the rider as the Live tab draws them, from the same asking
// of Kav's server (KavLive.area). North up and leaning, centered on the rider, with only their arrow turning with the
// way the phone faces: a picture of where they are, never following as navigation does. Drawn from Kav's own map on the
// phone alone: nothing is asked of a map server for it.
internal object HomeMap {
    // Where the rider was last, kept in the cache folder: the map shows there from the first frame, before the phone
    // says where they are now. There is none until the rider's place has been known once.
    private var last: Pair<Double, Double>? = null
    private var read = false

    private fun file(ctx: Context) = File(ctx.cacheDir, "home-map.txt")

    fun place(ctx: Context): Pair<Double, Double>? {
        if (!read) {
            read = true
            last = runCatching { file(ctx).readText().trim().split(',').let { it[0].toDouble() to it[1].toDouble() } }.getOrNull()
        }
        return last
    }

    suspend fun keep(ctx: Context, here: Pair<Double, Double>) {
        last = here
        withContext(Dispatchers.IO) { runCatching { file(ctx).writeText("${here.first},${here.second}") } }
    }

    // Whether Home leaves the map its room: decided from the first frame, so the list never moves when it comes.
    fun shown(ctx: Context, here: Pair<Double, Double>?) = MapPacks.STANDARD.state is MapPack.State.Ready &&
        uk.noammm.kav.hasLocationPermission(ctx) && (here != null || place(ctx) != null)
}

// Kav's zoom of Home's map with no trip, as it was before 516dbd4 (MapLibre 15.5 at 420 dpi: Kav's zoom less 1 and the density steps).
private const val HOME_Z = 17.9f

// The arrow on Home's trip map turns by this many degrees at a time with the compass: each turn sends the trip's marks
// to the map again.
private const val HOME_TURN = 15f

// The map, fading into the page at its foot. padding: what of it the search and the tiles cover, so what it shows is in
// the part between them. Kav's pulse shows there if the map takes a while to draw.
@Composable
internal fun HomeLiveMap(model: KavModel, padding: PaddingValues, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    // The compass is on while it shows (MainActivity's heading).
    DisposableEffect(Unit) { model.homeMapShown = true; onDispose { model.homeMapShown = false } }
    LaunchedEffect(model.here) { model.here?.let { HomeMap.keep(ctx, it) } }
    var drawn by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(600); slow = true }
    Box(modifier) {
        val journey = model.activeJourney
        if (journey != null) HomeTripMap(model, journey, model.heading?.let { ((it / HOME_TURN).roundToInt() * HOME_TURN) % 360f }, padding) { drawn = true }
        else (model.here ?: HomeMap.place(ctx))?.let { HomeBusesMap(it, model.heading, padding) { drawn = true } }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to K.bg.copy(alpha = 0f), 1f to K.bg)))
        AnimatedVisibility(slow && !drawn, Modifier.fillMaxSize().padding(padding), enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(contentAlignment = Alignment.Center) { LoadingPulse(T("Loading the map", "טוענים את המפה")) }
        }
    }
}

// The trip's step on now, as the trip's map shows it, north up and centered on the rider where that map follows them.
@Composable
private fun HomeTripMap(model: KavModel, journey: ActiveJourney, heading: Float?, padding: PaddingValues, onDrawn: () -> Unit) {
    val steps = remember(journey.trip, journey.fromLabel, journey.toLabel) { buildSteps(journey.trip, journey.fromLabel, journey.toLabel) }
    val current = model.journeyStep.coerceIn(0, steps.lastIndex)
    val step = steps.getOrNull(current)
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() / 1000 } }
    // As the trip's screen has them (NavigateScreen): the bus waited for, and the fix the map follows.
    val waitingFor = steps.drop(current).takeWhile { it !is Step.Ride }.firstOrNull { it is Step.Wait }
        ?.let { it as Step.Wait }?.let { boardingChoice(it.ride, it.wait, journey.chosen[it.legIndex] ?: 0) }
    val fix = model.fix
    val seen = model.roughFix?.takeIf { rough -> fix == null || rough.at > fix.at + 10 } ?: fix
    NavigateMap(journey.trip, journey.resolved, step, journey.chosen, waitingFor, model.here, seen, heading, now,
        riding = step is Step.Ride, modifier = Modifier.fillMaxSize(), contentPadding = padding, attribution = false,
        still = true, onDrawn = onDrawn)
}

// The vehicles around the rider, merged where they'd lie on one another as on the Live tab, asked about while Home is
// in front as often as the Live tab asks. Kept for the Live tab too, which then opens with them (LiveMemory).
@Composable
private fun HomeBusesMap(here: Pair<Double, Double>, heading: Float?, padding: PaddingValues, onDrawn: () -> Unit) {
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var area by remember { mutableStateOf(LiveMemory.recentArea(here)) }
    // Asked about again once the rider is 300 m from where they were, as the Live tab's stops are.
    var at by remember { mutableStateOf(here) }
    LaunchedEffect(here) { if (metres(here.first, here.second, at.first, at.second) > 300) at = here }
    LaunchedEffect(at, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val wait = try {
                    val answer = withContext(Dispatchers.IO) { KavLive.area(KavLive.box(at.first, at.second, 1.5)) }
                    area = answer
                    LiveMemory.area = answer; LiveMemory.areaCentre = at
                    LiveMemory.areaAt = System.currentTimeMillis() / 1000; LiveMemory.source = KavLive.origin
                    answer.next - System.currentTimeMillis() / 1000
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    15L
                }
                delay(wait.coerceIn(5, 30) * 1000L)
            }
        }
    }
    val mergeAt = with(density) { GROUP_MERGE_DP.dp.toPx().toDouble() }
    val splitAt = with(density) { GROUP_SPLIT_DP.dp.toPx().toDouble() }
    val coverAt = with(density) { (GROUP_MARK_R + VEHICLE_MARK_R).dp.toPx().toDouble() }
    val groupAt = with(density) { (2 * GROUP_MARK_R).dp.toPx().toDouble() }
    // No halos: their pulse would have the map drawn thirty times a second for as long as Home is open.
    val vehicles = remember(area, density, MapInk.icons) {
        val buses = area?.buses.orEmpty().sortedByDescending { metres(here.first, here.second, it.spot.lat, it.spot.lon) }
        val lines = area?.lines.orEmpty()
        val g = vehicleGeometry(buses.map { KavLive.busArrival(it) to modeOf(lines[it.route]?.type ?: 3) }, density)
        // The look in each image's name, as the Live tab names them: an image added again under one name kept its old look.
        val images = HashMap<String, ImageBitmap>()
        val markers = g.markers.map { m -> "kav-live-${m.id}~${m.icon}".let { images[it] = g.images.getValue(m.icon); m.copy(icon = it) } }
        val points = buses.map { KavLive.busArrival(it) }.map { GroupPoint(vehicleMarkId(it), it.lat, it.lon) }
        val groups = groupVehicles(points, HOME_Z.toDouble(), mergeAt, splitAt, coverPx = coverAt, groupPx = groupAt)
        val seen = buses.associate { vehicleMarkId(KavLive.busArrival(it)) to it.spot.at }
        val now = System.currentTimeMillis() / 1000
        g.copy(markers = markers, images = images).merged(groups) { group ->
            val tint = mapFreshColour(freshnessOf(group.ids.maxOf { seen[it] ?: 0L }, now))
            val (name, image) = groupMark(group.ids.size, tint, density)
            images[name] = image
            name to tint
        }.let { m ->
            // Group marks are made to go with a focus (FOCUS_OUT), which a map without one draws at no opacity.
            m.copy(markers = m.markers.map { it.copy(fade = 0) }, halos = emptyList(), images = images)
        }
    }
    // They fade in as they come, not at once.
    val come = animateFloatAsState(if (area != null) 1f else 0f, tween(350), label = "homeVehicles")
    val me = remember(here, heading != null, density, K.look, MapInk.icons) { riderArrowGeometry(here, heading?.let { 0f }, density) }
    val k = come.value
    val live = MapGeometry(
        markers = if (k < 1f) vehicles.markers.map { it.copy(alpha = it.alpha * k) } else vehicles.markers,
        images = vehicles.images + me.images, tracks = vehicles.tracks, together = true,
    )
    TileMap(listOf(here), Modifier.fillMaxSize(), follow = Follow(here.first, here.second, 0f, HOME_Z, centered = true),
        contentPadding = padding, live = live, rider = me.markers.firstOrNull(), riderHeading = heading,
        recenterControl = false, attribution = false, onDrawn = onDrawn, touch = false, name = "home")
}

// One still picture of a map, w x h dp at scale pixels to a dp, drawn off screen (MapSnapshotter), with where the points
// given fall on it in dp. With its places' names where it can be drawn with them, else without (ShareSheet's labelled).
internal suspend fun snapshot(ctx: Context, style: (named: Boolean) -> String, camera: CameraPosition, w: Int, h: Int,
    scale: Float, points: List<Pair<Double, Double>> = emptyList(), wait: Long = 10_000): Pair<ImageBitmap, List<Offset>>? {
    for (named in listOf(true, false)) {
        val options = MapSnapshotter.Options(w, h)
            .withPixelRatio(scale)
            .withStyleBuilder(Style.Builder().fromJson(style(named)))
            .withCameraPosition(camera)
            .withLogo(false)
            .withAttribution(false)
        return withTimeoutOrNull(wait) {
            suspendCancellableCoroutine<Pair<ImageBitmap, List<Offset>>?> { done ->
                val snapshotter = MapSnapshotter(ctx, options)
                done.invokeOnCancellation { snapshotter.cancel() }
                snapshotter.start({ s ->
                    val at = points.map { p -> s.pixelForLatLng(LatLng(p.first, p.second)).let { Offset(it.x / scale, it.y / scale) } }
                    done.resume(s.bitmap.asImageBitmap() to at)
                }, { done.resume(null) })
            }
        } ?: continue
    }
    return null
}

// Settings › Map's preview: the map picked, a still picture of the streets around Dizengoff Center, drawn once for each
// map, look and language and kept in the cache folder, so picking another shows it at once instead of loading a whole
// live map each time (Noam, 27 Nov 2026). Kav's own map is read from the phone, or before it's downloaded from GitHub
// for this spot alone, as the live preview was; the others from their providers, once.
internal object MapPreviews {
    private val shots = mutableStateMapOf<String, ImageBitmap>()

    private fun file(ctx: Context, key: String) =
        File(File(ctx.cacheDir, "map-previews").apply { mkdirs() }, "${key.hashCode().toUInt()}.png")

    suspend fun get(ctx: Context, key: String, type: MapFile.Type, look: Look, rtl: Boolean, w: Int, h: Int, scale: Float): ImageBitmap? {
        shots[key]?.let { return it }
        val kept = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file(ctx, key).path)?.asImageBitmap() }.getOrNull() }
        if (kept != null) return kept.also { shots[key] = it }
        uk.noammm.kav.data.GovMapTiles.mapLibre(ctx)
        val map = MapFile.styleJson(ctx, type, look, MapFile.labels, preview = true) ?: return null
        val camera = CameraPosition.Builder().target(LatLng(32.0756, 34.7752)).zoom(15.3).build()
        val image = snapshot(ctx, { named -> RouteShots.labelled(map, rtl, named) }, camera, w, h, scale, wait = 20_000)?.first ?: return null
        withContext(Dispatchers.IO) {
            runCatching { file(ctx, key).outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        }
        return image.also { shots[key] = it }
    }
}

// The preview, as wide as it's given and height tall, in a card's corners, with the map's credit at its foot. The last
// picture stays while the next is drawn, then fades into it.
@Composable
internal fun MapPreview(height: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val type = MapFile.type
    val look = K.look
    val labels = MapFile.labels
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(height).panel(K.rCard)) {
        val w = maxWidth.value.roundToInt()
        val h = height.value.roundToInt()
        val key = "$type|$look|$labels|$rtl|${w}x$h|$density"
        var shown by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(key) { MapPreviews.get(ctx, key, type, look, rtl, w, h, density)?.let { shown = it } }
        Crossfade(shown, Modifier.fillMaxSize(), animationSpec = tween(250), label = "mapPreview") { image ->
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        val provider = MapFile.provider(type)
        Text(
            provider.credit, style = K.type.caption.copy(fontSize = 10.sp),
            color = (if (provider.lightGround) Color.Black else MapInk.text).copy(alpha = .7f), maxLines = 1,
            modifier = Modifier.align(androidx.compose.ui.AbsoluteAlignment.BottomLeft).padding(horizontal = K.gap3, vertical = K.gap2),
        )
    }
}
