package uk.noammm.kav.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan
import androidx.compose.animation.core.Animatable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation

// fades: comes and goes as the stops do (TileMap's dots), so a road changed for another fades with its own stops
// instead of going at once and leaving them behind.
data class MapLine(
    val points: List<Pair<Double, Double>>,
    val colour: Color,
    val width: Float,
    val casing: Float = 0f,
    val dashed: Boolean = false,
    val fades: Boolean = false,
)

// id: the same dot wherever it is, for one that moves, like the rider's own location: it's drawn in its new place at
// once rather than fading out of the old one and into the new. fade: FOCUS_IN or FOCUS_OUT for one that comes or goes
// with what the map looks at (TileMap's focus), by its layer's opacity.
data class MapDot(
    val lat: Double,
    val lon: Double,
    val fill: Color,
    val radius: Float,
    val stroke: Color = Color.Transparent,
    val strokeWidth: Float = 0f,
    val id: String? = null,
    val fade: Int = 0,
)

// What comes in as a map's focus does (TileMap's focus: a picked vehicle's road and stops), and what goes as it does
// (the other vehicles and the stops around).
const val FOCUS_IN = 1
const val FOCUS_OUT = 2

// id: the vehicle a mark is of, so it glides to the vehicle's next place (TileMap).
data class MapMarker(
    val lat: Double,
    val lon: Double,
    val icon: String,
    val rotation: Float = 0f,
    val alpha: Float = 1f,
    val bottom: Boolean = false,
    val turns: Boolean = false,
    val id: String? = null,
    val fade: Int = 0,
)

// Drawn by the map itself, so it moves with the map. Markers stay upright unless they turn. tracks: a vehicle's route
// road by its id, which it glides along (TileMap).
data class MapGeometry(
    val lines: List<MapLine> = emptyList(),
    val dots: List<MapDot> = emptyList(),
    val markers: List<MapMarker> = emptyList(),
    val halos: List<MapDot> = emptyList(),
    val images: Map<String, ImageBitmap> = emptyMap(),
    val tracks: Map<String, List<Pair<Double, Double>>> = emptyMap(),
    // Crowded Live maps keep every vehicle in one source, so gliding cannot change their stacking order.
    val together: Boolean = false,
)

// Every halo on a map at one size, the largest there (the vehicles'): the pulse then sets them all one plain radius. With
// halos of two sizes (the rider's and the vehicles') it has to read the radius from each feature, which has the map lay
// every halo out again at each step of the pulse, thirty times a second: they jump, and frames drop.
internal fun MapGeometry.oneHaloSize(): MapGeometry {
    val r = halos.maxOfOrNull { it.radius } ?: return this
    return if (halos.all { it.radius == r }) this else copy(halos = halos.map { it.copy(radius = r) })
}

private const val SRC_LINES = "kav-lines"
private const val SRC_LINES_IN = "kav-lines-in"
private const val SRC_LINES_OUT = "kav-lines-out"
// Each line source's layers: dashed, casing, line. The fading ones have them twice: under the main ones, for a line that
// stays under one of theirs, and over them (MapLine.fades).
private val LINES = listOf("kav-line-dash", "kav-line-casing", "kav-line")
private val LINES_IN = listOf("kav-line-in-dash", "kav-line-in-casing", "kav-line-in",
    "kav-line-in-over-dash", "kav-line-in-over-casing", "kav-line-in-over")
private val LINES_OUT = listOf("kav-line-out-dash", "kav-line-out-casing", "kav-line-out",
    "kav-line-out-over-dash", "kav-line-out-over-casing", "kav-line-out-over")
private val IN_CASINGS = listOf(LINES_IN[1], LINES_IN[4])
private const val SRC_DOTS = "kav-dots"
private const val SRC_MARKS = "kav-marks"
private const val SRC_LIVE_HALOS = "kav-live-halos"
private const val SRC_LIVE_DOTS = "kav-live-dots"
private const val SRC_LIVE_MARKS = "kav-live-marks"
private const val HALO_LAYER = "kav-live-halo"
private const val HALO_OUT = "kav-live-halo-out"
private const val DOT_IN = "kav-dot-in"
private const val DOT_OUT = "kav-dot-out"
private const val MARK_OUT = "kav-live-mark-out"
private const val TURN_OUT = "kav-live-turn-out"
// The vehicles gliding, in a source of their own, small enough to be sent again at every frame the map draws while the
// rest stay as they were sent.
private const val SRC_GLIDE_HALOS = "kav-glide-halos"
private const val SRC_GLIDE_MARKS = "kav-glide-marks"
private const val SRC_RIDER = "kav-rider"
private const val SRC_3D_WARM = "kav-3d-warm"
private const val RIDER_LAYER = "kav-rider-mark"
private const val GLIDE_HALO = "kav-glide-halo"
private const val GLIDE_HALO_OUT = "kav-glide-halo-out"
// The rider's halo on Live, from the vehicles' source but a layer of its own: what's shown, a focus and the vehicles'
// fades never touch it, only the pulse they share.
private const val RIDER_HALO = "kav-rider-halo"
private const val GLIDE_MARK = "kav-glide-mark"
private const val GLIDE_TURN = "kav-glide-turn"
private const val GLIDE_MARK_OUT = "kav-glide-mark-out"
private const val GLIDE_TURN_OUT = "kav-glide-turn-out"
private val HALO_LAYERS = listOf(HALO_OUT, GLIDE_HALO_OUT, HALO_LAYER, GLIDE_HALO, RIDER_HALO)

// The rider's arrow on a map, by the colours it's drawn in (MapInk).
internal fun mapArrowIcon() = "kav-arrow-${MapInk.icons.text.toArgb()}-${MapInk.icons.bg.toArgb()}"

private fun rgba(c: Color): String =
    "rgba(${(c.red * 255).toInt()},${(c.green * 255).toInt()},${(c.blue * 255).toInt()},${c.alpha})"

private fun Style.ensureKavLayers() {
    if (getSource(SRC_LINES) != null) return
    addSource(GeoJsonSource(SRC_LINES_OUT))
    addSource(GeoJsonSource(SRC_LINES))
    addSource(GeoJsonSource(SRC_LINES_IN))
    addSource(GeoJsonSource(SRC_DOTS))
    addSource(GeoJsonSource(SRC_MARKS))
    addSource(GeoJsonSource(SRC_LIVE_HALOS))
    addSource(GeoJsonSource(SRC_LIVE_DOTS))
    addSource(GeoJsonSource(SRC_LIVE_MARKS))
    addSource(GeoJsonSource(SRC_GLIDE_HALOS))
    addSource(GeoJsonSource(SRC_GLIDE_MARKS))
    addSource(GeoJsonSource(SRC_RIDER))
    val colour = Expression.toColor(Expression.get("colour"))
    val width = Expression.toNumber(Expression.get("width"))
    val sort = Expression.toNumber(Expression.get("sort"))
    val round = PropertyFactory.lineCap(Property.LINE_CAP_ROUND)
    val join = PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    // A map's lines fade as a whole by their layers' opacity (focus), drawn at once at each step. Lines that fade
    // (MapLine.fades) fade where they're drawn before and after: under the lines that stay (a grey road under the
    // route) or over them (a route over its grey road), the going ones under the coming.
    val snap = org.maplibre.android.style.layers.TransitionOptions(0, 0)
    for ((ids, source, over) in listOf(
        Triple(LINES_OUT.take(3), SRC_LINES_OUT, false), Triple(LINES_IN.take(3), SRC_LINES_IN, false), Triple(LINES, SRC_LINES, null),
        Triple(LINES_OUT.drop(3), SRC_LINES_OUT, true), Triple(LINES_IN.drop(3), SRC_LINES_IN, true),
    )) {
        val (dashId, casingId, lineId) = ids
        fun only(filter: Expression) =
            if (over == null) filter else Expression.all(filter, Expression.eq(Expression.get("over"), Expression.literal(over)))
        addLayer(LineLayer(dashId, source).withProperties(
            PropertyFactory.lineColor(colour), PropertyFactory.lineWidth(width),
            PropertyFactory.lineDasharray(arrayOf(1.5f, 2.5f)),
            PropertyFactory.lineSortKey(sort), round, join,
        ).withFilter(only(Expression.eq(Expression.get("dashed"), Expression.literal(true)))).apply { lineOpacityTransition = snap })
        addLayer(LineLayer(casingId, source).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("casingColour"))),
            PropertyFactory.lineWidth(Expression.toNumber(Expression.get("casing"))),
            PropertyFactory.lineSortKey(sort), round, join,
        ).withFilter(only(Expression.all(
            Expression.eq(Expression.get("dashed"), Expression.literal(false)),
            Expression.gt(Expression.get("casing"), Expression.literal(0)),
        ))).apply { lineOpacityTransition = snap })
        addLayer(LineLayer(lineId, source).withProperties(
            PropertyFactory.lineColor(colour), PropertyFactory.lineWidth(width),
            PropertyFactory.lineSortKey(sort), round, join,
        ).withFilter(only(Expression.eq(Expression.get("dashed"), Expression.literal(false)))).apply { lineOpacityTransition = snap })
    }
    // Each fade (MapDot.fade) in its own layer, so a focus coming in only changes their opacities: nothing is sent or
    // laid out again, and every frame of it is drawn at once.
    fun fading(fade: Int) = Expression.eq(Expression.toNumber(Expression.get("fade")), Expression.literal(fade))
    fun dotLayer(id: String, fade: Int) = CircleLayer(id, SRC_DOTS).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
        PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("strokeColour"))),
        PropertyFactory.circleStrokeWidth(Expression.toNumber(Expression.get("strokeWidth"))),
        PropertyFactory.circleSortKey(sort),
    ).withFilter(fading(fade)).apply { circleOpacityTransition = snap; circleStrokeOpacityTransition = snap }
    addLayer(dotLayer(DOT_OUT, FOCUS_OUT))
    addLayer(dotLayer(DOT_IN, FOCUS_IN))
    addLayer(dotLayer("kav-dot", 0))
    // A route's own marks overlap at stations, so they keep the order they were listed in, later ones in front.
    addLayer(markLayer("kav-mark", SRC_MARKS, turns = false).withProperties(
        PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("order"))),
    ))
    // Each step of the pulse is drawn at once, as a radius read from the features always is: a plain number would
    // otherwise ease to it over the map's default 300 ms.
    val rider = Expression.has("rider")
    fun haloLayer(id: String, source: String, out: Boolean) = CircleLayer(id, source).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
    ).withFilter(Expression.all(Expression.not(rider), if (out) fading(FOCUS_OUT) else Expression.not(fading(FOCUS_OUT))))
        .apply { circleRadiusTransition = snap; circleOpacityTransition = snap }
    addLayer(haloLayer(HALO_OUT, SRC_LIVE_HALOS, out = true))
    addLayer(haloLayer(GLIDE_HALO_OUT, SRC_GLIDE_HALOS, out = true))
    addLayer(haloLayer(HALO_LAYER, SRC_LIVE_HALOS, out = false))
    addLayer(haloLayer(GLIDE_HALO, SRC_GLIDE_HALOS, out = false))
    addLayer(CircleLayer(RIDER_HALO, SRC_LIVE_HALOS).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
    ).withFilter(rider).apply { circleRadiusTransition = snap })
    addLayer(CircleLayer("kav-live-dot", SRC_LIVE_DOTS).withProperties(
        PropertyFactory.circleColor(colour),
        PropertyFactory.circleRadius(Expression.toNumber(Expression.get("r"))),
        PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("strokeColour"))),
        PropertyFactory.circleStrokeWidth(Expression.toNumber(Expression.get("strokeWidth"))),
        PropertyFactory.circleSortKey(sort),
    ))
    // The vehicles that go with a focus, each at full alpha: their layers' opacity is a plain number.
    for ((id, source, turns) in listOf(Triple(MARK_OUT, SRC_LIVE_MARKS, false), Triple(TURN_OUT, SRC_LIVE_MARKS, true),
        Triple(GLIDE_MARK_OUT, SRC_GLIDE_MARKS, false), Triple(GLIDE_TURN_OUT, SRC_GLIDE_MARKS, true))) addLayer(markLayer(id, source, turns)
        .withProperties(PropertyFactory.iconOpacity(1f))
        .withFilter(Expression.all(Expression.eq(Expression.get("turns"), Expression.literal(turns)), fading(FOCUS_OUT)))
        .apply { iconOpacityTransition = snap })
    for ((id, source, turns) in listOf(Triple("kav-live-mark", SRC_LIVE_MARKS, false), Triple("kav-live-turn", SRC_LIVE_MARKS, true),
        Triple(GLIDE_MARK, SRC_GLIDE_MARKS, false), Triple(GLIDE_TURN, SRC_GLIDE_MARKS, true))) addLayer(markLayer(id, source, turns)
        .withFilter(Expression.all(Expression.eq(Expression.get("turns"), Expression.literal(turns)), Expression.not(fading(FOCUS_OUT)))))
    addLayer(markLayer(RIDER_LAYER, SRC_RIDER, turns = true).withFilter(Expression.literal(true)))
}

// A layer's opacity times a; a mark keeps its own alpha under it.
// When a map last had something to move besides its halos' pulse: the camera, a source's features (a vehicle's glide,
// the rider, new lines or marks), a fade, a finger. Touched on the main thread only.
private var stirredAt = 0L

private fun stirred() { stirredAt = android.os.SystemClock.uptimeMillis() }

// The halos' pulse alone has been all that moved for this long: long enough for the names a move brought in to have
// faded in, which the map does over 300 ms.
private const val REST_AFTER_MS = 700L

// The most a map draws a second: the display's rate under a finger or a camera move, sixty otherwise, and thirty at
// rest, while nothing on it moves but the halos' pulse. The pulse has thirty steps a second and the map draws on at
// its limit while they come, so at sixty it drew every step twice, and the whole screen over it with it: a trip's
// page, navigation standing still and the Live tab each drew sixty frames a second for as long as they were open.
private class MapPace(private val view: MapView) {
    private var applied = -1
    var busy = 60
        set(rate) { field = rate; apply() }
    var rest = false
        set(on) { if (field != on) { field = on; apply() } }

    private fun apply() {
        val rate = if (rest && busy <= 60) 30 else busy
        if (rate != applied) { view.setMaximumFps(rate); applied = rate }
    }
}

private fun Style.fadeLayers(a: Float, vararg ids: String) {
    stirred()
    for (id in ids) when (val layer = getLayer(id)) {
        is CircleLayer -> layer.setProperties(PropertyFactory.circleOpacity(a), PropertyFactory.circleStrokeOpacity(a))
        is SymbolLayer -> layer.setProperties(
            PropertyFactory.iconOpacity(Expression.product(Expression.toNumber(Expression.get("alpha")), Expression.literal(a))),
        )
        else -> {}
    }
}

private fun markLayer(id: String, source: String, turns: Boolean) = SymbolLayer(id, source).withProperties(
    PropertyFactory.iconImage(Expression.get("icon")),
    PropertyFactory.iconRotate(Expression.toNumber(Expression.get("rot"))),
    PropertyFactory.iconOpacity(Expression.toNumber(Expression.get("alpha"))),
    PropertyFactory.iconAnchor(Expression.get("anchor")),
    PropertyFactory.iconRotationAlignment(
        if (turns) Property.ICON_ROTATION_ALIGNMENT_MAP else Property.ICON_ROTATION_ALIGNMENT_VIEWPORT,
    ),
    PropertyFactory.iconAllowOverlap(true),
    PropertyFactory.iconIgnorePlacement(true),
).withFilter(Expression.eq(Expression.get("turns"), Expression.literal(turns)))

private val installedKavImages = java.util.WeakHashMap<Style, MutableSet<String>>()

private inline fun Style.addKavImage(name: String, image: () -> android.graphics.Bitmap) {
    val installed = installedKavImages.getOrPut(this) { HashSet() }
    if (name !in installed) {
        addImage(name, image())
        installed.add(name)
    }
}

private fun Style.addKavImages(images: Map<String, ImageBitmap>) {
    for ((name, image) in images) addKavImage(name) { image.asAndroidBitmap() }
}

private fun markFeatures(marks: List<MapMarker>): List<Feature> = marks.mapIndexed { i, m ->
    Feature.fromGeometry(Point.fromLngLat(m.lon, m.lat)).apply {
        addNumberProperty("order", i)
        addStringProperty("icon", m.icon)
        addNumberProperty("rot", m.rotation)
        addNumberProperty("alpha", m.alpha)
        addStringProperty("anchor", if (m.bottom) "bottom" else "center")
        addBooleanProperty("turns", m.turns)
        addNumberProperty("fade", m.fade)
    }
}

// over: a fading line drawn over the main lines rather than under them.
private fun lineFeatures(lines: List<MapLine>, over: (MapLine) -> Boolean = { false }): FeatureCollection = FeatureCollection.fromFeatures(
    lines.filter { it.points.size >= 2 }.mapIndexed { i, l ->
        Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.second, it.first) })).apply {
            addStringProperty("colour", rgba(l.colour))
            addStringProperty("casingColour", rgba(MapInk.bg.copy(alpha = l.colour.alpha)))
            addNumberProperty("width", l.width)
            addNumberProperty("casing", l.casing)
            addBooleanProperty("dashed", l.dashed)
            addNumberProperty("sort", i)
            addBooleanProperty("over", over(l))
        }
    },
)

// null: as it was sent.
private fun Style.setKavGeometry(lines: FeatureCollection?, marks: FeatureCollection?) {
    lines?.let { getSourceAs<GeoJsonSource>(SRC_LINES)?.setGeoJson(it) }
    marks?.let { getSourceAs<GeoJsonSource>(SRC_MARKS)?.setGeoJson(it) }
}

private fun Style.setKavDots(dots: FeatureCollection) {
    getSourceAs<GeoJsonSource>(SRC_DOTS)?.setGeoJson(dots)
}

private val MapDot.place: Any get() = id ?: Triple(lat, lon, radius)

private const val GLIDE_MS = 800f
// Past this a vehicle is put in its new place rather than glided there.
private const val GLIDE_MAX_M = 1500.0

// A vehicle's way from where it's drawn to its new place: along its route's road when both places are on it (roadBetween),
// so a U-turn is drawn as one, else straight. Null for one that stays, or went too far to glide, put there at once.
internal class Glide private constructor(private val way: List<Pair<Double, Double>>) {
    private val at = DoubleArray(way.size).also { a ->
        for (i in 1 until way.size) a[i] = a[i - 1] + metres(way[i - 1].first, way[i - 1].second, way[i].first, way[i].second)
    }

    // Where it is a fraction t of the way, by distance along it.
    fun place(t: Float): Pair<Double, Double> {
        val goal = at.last() * t
        var i = 1
        while (i < way.lastIndex && at[i] < goal) i++
        val seg = at[i] - at[i - 1]
        val f = if (seg <= 0.0) 1.0 else ((goal - at[i - 1]) / seg).coerceIn(0.0, 1.0)
        val (a, b) = way[i - 1] to way[i]
        return (a.first + (b.first - a.first) * f) to (a.second + (b.second - a.second) * f)
    }

    companion object {
        fun of(from: Pair<Double, Double>, to: Pair<Double, Double>, track: List<Pair<Double, Double>>?): Glide? {
            val gap = metres(from.first, from.second, to.first, to.second)
            if (gap < 0.5 || gap > GLIDE_MAX_M) return null
            return Glide(track?.let { roadBetween(from, to, it) } ?: listOf(from, to))
        }
    }
}

// The live layer as features, each one's look made once an update: as vehicles glide only their places change. A mark's
// id is its vehicle's, with the halo and heading after a colon.
internal class LiveFeatures(g: MapGeometry) {
    private val halos = dotFeatures(g.halos)
    private val marks = markFeatures(g.markers)
    private val haloIds = g.halos.map { it.id?.substringBefore(':') }
    private val markIds = g.markers.map { it.id?.substringBefore(':') }
    val dots: FeatureCollection = FeatureCollection.fromFeatures(dotFeatures(g.dots))
    val count = halos.size + marks.size

    // The halos and marks of all but the vehicles gliding, where they are.
    fun still(moving: Set<String>): Pair<FeatureCollection, FeatureCollection> {
        fun kept(features: List<Feature>, ids: List<String?>) =
            FeatureCollection.fromFeatures(features.filterIndexed { i, _ -> ids[i] !in moving })
        return kept(halos, haloIds) to kept(marks, markIds)
    }

    // The halos and marks of the vehicles gliding, t of the way, and of others staying where they are: their halos
    // (stayHalos) and marks (stayMarks) leave this source at different times.
    fun moving(t: Float, glides: Map<String, Glide>, stayHalos: Set<String> = emptySet(), stayMarks: Set<String> = stayHalos): Pair<FeatureCollection, FeatureCollection> {
        fun placed(features: List<Feature>, ids: List<String?>, stay: Set<String>) = FeatureCollection.fromFeatures(features.indices.mapNotNull { i ->
            val id = ids[i] ?: return@mapNotNull null
            val glide = glides[id]
            when {
                glide != null -> glide.place(t).let { (lat, lon) -> Feature.fromGeometry(Point.fromLngLat(lon, lat), features[i].properties()) }
                id in stay -> features[i]
                else -> null
            }
        })
        return placed(halos, haloIds, stayHalos) to placed(marks, markIds, stayMarks)
    }

    fun together(t: Float, glides: Map<String, Glide>): Pair<FeatureCollection, FeatureCollection> =
        moving(t, glides, haloIds.filterNotNull().toSet(), markIds.filterNotNull().toSet())
}

// How the live layer keeps up, for measuring it on a phone: on with `adb shell setprop log.tag.KavMap DEBUG`, read with
// `adb logcat -s KavMap`. Each update logs its vehicles, how many glided, what making its features cost, the main
// thread's time per send and the map's frames meanwhile.
private class LiveTimes(private val map: MapView) {
    val on = android.util.Log.isLoggable("KavMap", android.util.Log.DEBUG)
    private val frames = ArrayList<Long>()
    private val listener = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> frames += System.nanoTime() }
    private var began = 0L; private var sends = 0; private var mainNs = 0L; private var worstNs = 0L; var madeNs = 0L

    init { if (on) map.addOnDidFinishRenderingFrameListener(listener) }
    fun close() { if (on) map.removeOnDidFinishRenderingFrameListener(listener) }
    fun begin() { began = System.nanoTime(); sends = 0; mainNs = 0; worstNs = 0; madeNs = 0; frames.clear() }

    fun send(block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        val d = System.nanoTime() - t0
        sends++; mainNs += d; worstNs = maxOf(worstNs, d)
    }

    fun end(features: Int, moving: Int) {
        if (!on) return
        val f = frames.filter { it >= began }
        val gap = f.zipWithNext { a, b -> b - a }.maxOrNull() ?: 0L
        android.util.Log.d("KavMap", String.format(java.util.Locale.US,
            "update: %d features, %d glided; made in %.1f ms off the main thread; %d sends, main %.2f ms avg, %.2f max; " +
                "map %d frames in %d ms, longest gap %d ms",
            features, moving, madeNs / 1e6, sends, if (sends > 0) mainNs / 1e6 / sends else 0.0, worstNs / 1e6,
            f.size, (System.nanoTime() - began) / 1_000_000, gap / 1_000_000))
    }
}

// The dot at a of its own opacity, in tenths, so a fade is about ten updates of the map whatever the screen's rate.
private fun MapDot.faded(a: Float): MapDot {
    val q = (a * 10).roundToInt() / 10f
    return if (q >= 1f) this else copy(fill = fill.copy(alpha = fill.alpha * q), stroke = stroke.copy(alpha = stroke.alpha * q))
}

private fun dotFeatures(dots: List<MapDot>): List<Feature> = dots.mapIndexed { i, d ->
    Feature.fromGeometry(Point.fromLngLat(d.lon, d.lat)).apply {
        addStringProperty("colour", rgba(d.fill))
        addStringProperty("strokeColour", rgba(d.stroke))
        addNumberProperty("r", d.radius)
        addNumberProperty("strokeWidth", d.strokeWidth)
        addNumberProperty("sort", i)
        addNumberProperty("fade", d.fade)
        if (d.id == "me:halo") addBooleanProperty("rider", true)
    }
}

// dots: null where they stay as they were sent.
private fun Style.setKavLiveHalos(halos: FeatureCollection, dots: FeatureCollection?) {
    getSourceAs<GeoJsonSource>(SRC_LIVE_HALOS)?.setGeoJson(halos)
    dots?.let { getSourceAs<GeoJsonSource>(SRC_LIVE_DOTS)?.setGeoJson(it) }
}

private fun Style.setKavLiveMarks(marks: FeatureCollection) {
    getSourceAs<GeoJsonSource>(SRC_LIVE_MARKS)?.setGeoJson(marks)
}

private fun Style.setKavGlide(halos: FeatureCollection, marks: FeatureCollection) {
    getSourceAs<GeoJsonSource>(SRC_GLIDE_HALOS)?.setGeoJson(halos)
    getSourceAs<GeoJsonSource>(SRC_GLIDE_MARKS)?.setGeoJson(marks)
}

private fun arrowBitmap(dp: Float, edge: Color, ink: Color): android.graphics.Bitmap {
    val s = 11f * dp
    val half = (s * 1.35f + 3f * dp).toInt() + 1
    val bmp = android.graphics.Bitmap.createBitmap(2 * half, 2 * half, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = half.toFloat()
    val cy = half.toFloat()
    val path = android.graphics.Path().apply {
        moveTo(cx, cy - s * 1.35f)
        lineTo(cx + s * .8f, cy + s * .75f)
        lineTo(cx, cy + s * .25f)
        lineTo(cx - s * .8f, cy + s * .75f)
        close()
    }
    val outline = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f * dp
        strokeJoin = android.graphics.Paint.Join.ROUND
        color = edge.toArgb()
    }
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink.toArgb() }
    canvas.drawPath(path, outline)
    canvas.drawPath(path, fill)
    return bmp
}

// The same arrow for the stop rail (Navigate.kt), which turns it to point down the ride.
internal fun getArrowBitmap(dp: Float): android.graphics.Bitmap = arrowBitmap(dp, K.bg, K.text)

internal object Geo {
    const val SIZE = 256
    const val MIN_Z = 8
    const val MAX_Z = 16
    const val OVER = 6f
    fun x(lon: Double): Double = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180.0
        return (1.0 - asinh(tan(r)) / PI) / 2.0
    }
    fun lon(x: Double): Double = x * 360.0 - 180.0
    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))
}

private data class Camera(val worldX: Double, val worldY: Double, val zoom: Float, val rotation: Float = 0f) {
    val pxPerWorld get() = Geo.SIZE * 2.0.pow(zoom.toDouble())
}

data class Follow(val lat: Double, val lon: Double, val bearing: Float, val zoom: Float = 18.5f)

// A change of the screen around the map under way (a card opening), by how much the map's height ends up growing:
// meanwhile the map frames what it shows for where the change ends, in one move, rather than following each step.
data class MapSettle(val id: Int, val grow: androidx.compose.ui.unit.Dp)

private const val ANCHOR_X = 0.5f
private const val ANCHOR_Y = 0.72f
private const val TILT_DEG = 40f
private const val MAX_TILT_DEG = 60f
// Degrees of tilt for each pixel the two fingers slide.
private const val TILT_PER_PX = 0.25f

private fun unrotate(at: Offset, anchor: Offset, rotation: Float): Offset {
    if (rotation == 0f) return at
    val rad = Math.toRadians(rotation.toDouble())
    val c = kotlin.math.cos(rad); val sn = kotlin.math.sin(rad)
    val dx = (at.x - anchor.x).toDouble(); val dy = (at.y - anchor.y).toDouble()
    return Offset((dx * c - dy * sn + anchor.x).toFloat(), (dx * sn + dy * c + anchor.y).toFloat())
}

class MapProjection(
    private val centerWorldX: Double,
    private val centerWorldY: Double,
    private val pxPerWorld: Double,
    private val width: Float,
    private val height: Float,
) {
    fun point(lat: Double, lon: Double): Offset {
        val wx = Geo.x(lon)
        val wy = Geo.y(lat)
        return Offset(
            ((wx - centerWorldX) * pxPerWorld + width / 2).toFloat(),
            ((wy - centerWorldY) * pxPerWorld + height / 2).toFloat(),
        )
    }

    fun latLon(at: Offset): Pair<Double, Double> {
        val wx = (at.x - width / 2) / pxPerWorld + centerWorldX
        val wy = (at.y - height / 2) / pxPerWorld + centerWorldY
        return Geo.lat(wy) to Geo.lon(wx)
    }
}

private data class WorldPoint(val x: Double, val y: Double)

private data class Viewport(val left: Float, val top: Float, val right: Float, val bottom: Float, val w: Float, val h: Float) {
    val width get() = (right - left).coerceAtLeast(1f)
    val height get() = (bottom - top).coerceAtLeast(1f)
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    fun inset(fraction: Float): Viewport {
        val pad = fraction.coerceIn(0f, 0.45f)
        return copy(left = left + width * pad, right = right - width * pad,
            top = top + height * pad, bottom = bottom - height * pad)
    }
    fun contains(point: WorldPoint, camera: Camera): Boolean {
        val x = (point.x - camera.worldX) * camera.pxPerWorld + w / 2
        val y = (point.y - camera.worldY) * camera.pxPerWorld + h / 2
        return x >= left && x <= right && y >= top && y <= bottom
    }
    val anchor get() = Offset(left + width * ANCHOR_X, top + height * ANCHOR_Y)
}

private fun fitCamera(points: List<WorldPoint>, viewport: Viewport, padFraction: Float, maxZoom: Float = Geo.MAX_Z.toFloat()): Camera {
    val area = viewport.inset(padFraction)
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val scaleX = area.width / ((maxX - minX).coerceAtLeast(1e-12) * Geo.SIZE)
    val scaleY = area.height / ((maxY - minY).coerceAtLeast(1e-12) * Geo.SIZE)
    val zoom = (ln(minOf(scaleX, scaleY)) / ln(2.0)).toFloat().coerceIn(Geo.MIN_Z.toFloat(), maxZoom)
    val scale = Geo.SIZE * 2.0.pow(zoom.toDouble())
    return Camera(
        (minX + maxX) / 2 - (area.centerX - area.w / 2) / scale,
        (minY + maxY) / 2 - (area.centerY - area.h / 2) / scale,
        zoom,
    )
}

// How quickly the camera catches up with the rider it follows, as a spring's angular frequency (rad/s): settled in
// about 0.7 s, as the move it replaces took.
private const val FOLLOW_W = 7.0

private class MapCamera {
    var value by mutableStateOf<Camera?>(null)
        private set
    var manual by mutableStateOf(false)
        private set
    // The tilt the rider set with two fingers, kept until they tilt it back; tilting, while their fingers are on it.
    var userTilt by mutableFloatStateOf(0f)
        private set
    var tilting by mutableStateOf(false)
    private var animation: Job? = null
    private var destination: Camera? = null
    // Following the rider: one loop each frame eases the camera toward the newest goal, so a heading that changes many
    // times a second turns the map smoothly instead of starting a move, and its ease, at every reading.
    private var followGoal: Camera? = null
    private var followLoop: Job? = null
    private val followSpeed = DoubleArray(4)
    // How long a move takes.
    var duration = 520
    // Whether a move goes at once: one asked for while the map is still hidden is only seen where it ends.
    var jump: () -> Boolean = { false }
    // The map's height its camera is placed for, while the screen around it changes (settle); 0 for its own height.
    // The map growing or shrinking under it then moves nothing on it: only the camera's own move does.
    var ref by mutableFloatStateOf(0f)
        private set

    // Placed for another height, where the map's top edge keeps what it shows.
    fun rebase(from: Float, to: Float, ref: Float, scope: CoroutineScope) {
        this.ref = ref
        val c = value ?: return
        val dy = (to - from) / 2 / c.pxPerWorld
        if (dy == 0.0) return
        animation?.cancel()
        followGoal = followGoal?.let { it.copy(worldY = it.worldY + dy) }
        val going = destination?.let { it.copy(worldY = it.worldY + dy) }
        value = c.copy(worldY = c.worldY + dy)
        destination = null
        if (going != null) moveTo(going, scope)
    }
    private var followed = false
    private var previousPoints = emptyList<WorldPoint>()
    private var previousViewport: Viewport? = null
    private var previousFocus: Any? = null
    private var previousPadding = 0f
    private var previousMaxZoom = Geo.MAX_Z.toFloat()
    // The zoom the map last framed itself at, which Recentre comes back to from further out.
    private var framedZoom: Float? = null

    fun update(
        points: List<WorldPoint>, viewport: Viewport, focus: Any?, padding: Float, maxZoom: Float, keepZoom: Boolean, scope: CoroutineScope,
    ) {
        if (points.isEmpty() || viewport.w <= 0 || viewport.h <= 0) return
        // Moved by hand, the map only reframes for something new to show, not for a resize.
        val reframe = value == null || followed || previousFocus != focus ||
            (!manual && (previousViewport != viewport || previousPadding != padding || previousMaxZoom != maxZoom))
        followed = false
        val current = destination ?: value
        val changed = points.filterIndexed { index, point -> previousPoints.getOrNull(index) != point }
        previousPoints = points
        previousFocus = focus
        previousViewport = viewport
        previousPadding = padding
        previousMaxZoom = maxZoom
        if (reframe) {
            reset(points, viewport, padding, if (keepZoom) current?.zoom ?: maxZoom else maxZoom, scope)
        } else if (!manual && current != null && changed.any { !viewport.contains(it, current) }) {
            val area = viewport.inset(padding)
            val scale = current.pxPerWorld
            val bounds = listOf(
                WorldPoint(current.worldX + (area.left - area.w / 2) / scale, current.worldY + (area.top - area.h / 2) / scale),
                WorldPoint(current.worldX + (area.right - area.w / 2) / scale, current.worldY + (area.bottom - area.h / 2) / scale),
            )
            moveTo(fitCamera(bounds + changed, viewport, padding, current.zoom), scope)
        }
    }

    fun reset(points: List<WorldPoint>, viewport: Viewport, padding: Float, maxZoom: Float, scope: CoroutineScope) {
        if (points.isEmpty()) return
        manual = false
        val frame = fitCamera(points, viewport, padding, maxZoom)
        framedZoom = frame.zoom
        moveTo(frame, scope)
    }

    // Zoomed out past the map's own frame (to the whole country, say), it comes back to that frame's zoom; zoomed in
    // closer, it keeps the zoom.
    fun centre(point: WorldPoint, scope: CoroutineScope) {
        val current = destination ?: value ?: return
        manual = false
        val zoom = maxOf(current.zoom, framedZoom ?: current.zoom)
        moveTo(current.copy(worldX = point.x, worldY = point.y, zoom = zoom, rotation = 0f), scope)
    }

    fun follow(target: Follow, viewport: Viewport, scope: CoroutineScope) {
        val scale = Geo.SIZE * 2.0.pow(target.zoom.toDouble())
        val tx = Geo.x(target.lon)
        val ty = Geo.y(target.lat)
        val anchor = viewport.anchor
        val goal = Camera(
            tx - (anchor.x - viewport.w / 2) / scale,
            ty - (anchor.y - viewport.h / 2) / scale,
            target.zoom,
            ((target.bearing % 360f) + 360f) % 360f,
        )
        manual = false
        followed = true
        followGoal = goal
        if (value == null || jump()) {
            stopFollowing()
            value = goal
            return
        }
        if (followLoop?.isActive == true) return
        animation?.cancel()
        destination = null
        followLoop = scope.launch {
            var last = 0L
            while (true) {
                val now = androidx.compose.runtime.withFrameNanos { it }
                val dt = if (last == 0L) 1.0 / 60 else ((now - last) / 1e9).coerceIn(0.0, 0.05)
                last = now
                val g = followGoal ?: break
                val c = value ?: break
                var turn = (g.rotation - c.rotation).toDouble()
                if (turn > 180) turn -= 360
                if (turn < -180) turn += 360
                val gap = doubleArrayOf(g.worldX - c.worldX, g.worldY - c.worldY, (g.zoom - c.zoom).toDouble(), turn)
                // Critically damped: it catches up as fast as it can without ever swinging past the goal.
                for (i in 0..3) followSpeed[i] += (FOLLOW_W * FOLLOW_W * gap[i] - 2 * FOLLOW_W * followSpeed[i]) * dt
                val px = c.pxPerWorld
                if (abs(gap[0] * px) < 0.5 && abs(gap[1] * px) < 0.5 && abs(gap[2]) < 0.002 && abs(turn) < 0.1 &&
                    abs(followSpeed[0] * px) < 5 && abs(followSpeed[1] * px) < 5 && abs(followSpeed[3]) < 1
                ) {
                    value = g
                    followSpeed.fill(0.0)
                    break
                }
                value = Camera(
                    c.worldX + followSpeed[0] * dt,
                    c.worldY + followSpeed[1] * dt,
                    (c.zoom + followSpeed[2] * dt).toFloat(),
                    (((c.rotation + followSpeed[3] * dt) % 360.0 + 360.0) % 360.0).toFloat(),
                )
            }
        }
    }

    // Two fingers sliding up lean the map back, down stand it up again. Following, it starts from the follow's own tilt.
    fun tiltBy(degrees: Float, from: Float) {
        if (!manual) userTilt = from
        stopFollowing()
        animation?.cancel()
        destination = null
        manual = true
        userTilt = (userTilt + degrees).coerceIn(0f, MAX_TILT_DEG)
    }

    private fun stopFollowing() {
        followLoop?.cancel()
        followLoop = null
        followGoal = null
        followSpeed.fill(0.0)
    }

    fun resume() { manual = false }

    private fun moveTo(target: Camera, scope: CoroutineScope, duration: Int = this.duration) {
        stopFollowing()
        (destination ?: value)?.let { d ->
            var spin = target.rotation - d.rotation
            if (spin > 180f) spin -= 360f
            if (spin < -180f) spin += 360f
            if (abs(target.worldX - d.worldX) * d.pxPerWorld < 2.0 &&
                abs(target.worldY - d.worldY) * d.pxPerWorld < 2.0 &&
                abs(target.zoom - d.zoom) < 0.05f && abs(spin) < 2.5f
            ) return
        }
        animation?.cancel()
        val start = value
        destination = target
        if (start == null || start == target || jump()) {
            value = target
            destination = null
            return
        }
        var spin = target.rotation - start.rotation
        if (spin > 180f) spin -= 360f
        if (spin < -180f) spin += 360f
        // A move that zooms turns about the one place both views show at the same spot on screen, so everything on the
        // map goes straight to where it ends, never out and back as with the centre and the zoom eased each on its own.
        val s0 = start.pxPerWorld; val s1 = target.pxPerWorld
        val pivot = if (abs(spin) < 0.01f && abs(s1 - s0) > s0 * 1e-3)
            WorldPoint((target.worldX * s1 - start.worldX * s0) / (s1 - s0), (target.worldY * s1 - start.worldY * s0) / (s1 - s0))
        else null
        animation = scope.launch {
            animate(0f, 1f, animationSpec = tween(duration, easing = FastOutSlowInEasing)) { fraction, _ ->
                val zoom = start.zoom + (target.zoom - start.zoom) * fraction
                value = if (pivot != null) {
                    val k = s0 / (Geo.SIZE * 2.0.pow(zoom.toDouble()))
                    Camera(pivot.x + (start.worldX - pivot.x) * k, pivot.y + (start.worldY - pivot.y) * k, zoom, start.rotation)
                } else Camera(
                    start.worldX + (target.worldX - start.worldX) * fraction,
                    start.worldY + (target.worldY - start.worldY) * fraction,
                    zoom,
                    ((start.rotation + spin * fraction) + 360f) % 360f,
                )
            }
            value = target
            destination = null
        }
    }

    fun gesture(centroid: Offset, pan: Offset, zoomChange: Float, twist: Float, viewport: Viewport) {
        val current = value ?: return
        stopFollowing()
        animation?.cancel()
        destination = null
        manual = true
        val zoom = (current.zoom + ln(zoomChange.coerceAtLeast(0.01f)) / ln(2f))
            .coerceIn(Geo.MIN_Z.toFloat(), Geo.MAX_Z + Geo.OVER)
        val rotation = (((current.rotation - twist) % 360f) + 360f) % 360f
        val before = current.pxPerWorld
        val after = Geo.SIZE * 2.0.pow(zoom.toDouble())
        val anchor = viewport.anchor
        fun flat(s: Offset, degrees: Float): Pair<Double, Double> {
            val rad = Math.toRadians(degrees.toDouble())
            val dx = s.x - anchor.x
            val dy = s.y - anchor.y
            val ux = anchor.x + dx * cos(rad) - dy * sin(rad)
            val uy = anchor.y + dx * sin(rad) + dy * cos(rad)
            return (ux - viewport.w / 2) to (uy - viewport.h / 2)
        }
        val (cx, cy) = flat(centroid, current.rotation)
        val (px, py) = flat(centroid + pan, rotation)
        value = Camera(
            current.worldX + cx / before - px / after,
            current.worldY + cy / before - py / after,
            zoom,
            rotation,
        )
    }
}

internal fun focalPadding(anchor: Offset, w: Float, h: Float): DoubleArray = doubleArrayOf(
    (2 * anchor.x - w).coerceAtLeast(0f).toDouble(),
    (2 * anchor.y - h).coerceAtLeast(0f).toDouble(),
    (w - 2 * anchor.x).coerceAtLeast(0f).toDouble(),
    (h - 2 * anchor.y).coerceAtLeast(0f).toDouble(),
)

// Kav's own map shows its buildings flat until it leans back: the 3D ones fade in over them with the tilt, fully there
// from 30°, so straight down they cost nothing (a layer at no opacity isn't drawn) and a tilted map, navigation's own
// included, shows them in 3D. Only the opacity changes: a change to the heights or to whether the layer shows has the
// whole map's tiles laid out again, a stall at each step. They fade in from zoom 15 to 15.5 too, like the flat ones
// (style.json): the map file has every building only from 15, so below it a zoom would pop thousands in at once.
// Returns the step set, or `last` when there's no such layer.
private fun Style.leanBuildings(tilt: Float, last: Float): Float {
    // Asked at every move of the camera: the layer is only looked up (a new object each time) when the step changes.
    val step = (((tilt - 3f) / 27f).coerceIn(0f, 1f) * 50).roundToInt() / 50f
    if (step == last) return last
    val layer = getLayer("buildings_3d") as? FillExtrusionLayer ?: return last
    if (last < 0f) layer.fillExtrusionOpacityTransition = org.maplibre.android.style.layers.TransitionOptions(0, 0)
    layer.setProperties(PropertyFactory.fillExtrusionOpacity(
        Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(15, 0), Expression.stop(15.5, step))))
    return step
}

// The graphics driver builds the 3D buildings' shaders the first time one is drawn, and the map stands still for the
// tenth of a second that takes: at the first zoom into a town after Kav opens. So a style that has them draws one as
// soon as it's set, in no colour, both ways the map draws them (see-through while they fade in, solid after), and lets
// it go once a whole frame has had it. Its height and base come from the feature and its colour is one for all, as the
// real ones' are (MapFadesTest), or the driver would build other shaders than theirs.
private suspend fun warmBuildings(view: MapView, map: MapLibreMap, style: Style) {
    if (!style.isFullyLoaded || style.getLayer("buildings_3d") == null) return
    val seen = map.projection.visibleRegion.latLngBounds
    val (lat, lon) = seen.center.latitude to seen.center.longitude
    val (h, w) = seen.latitudeSpan / 40 to seen.longitudeSpan / 40
    if (h <= 0.0 || w <= 0.0) return
    val square = Polygon.fromLngLats(listOf(listOf(
        Point.fromLngLat(lon - w, lat - h), Point.fromLngLat(lon + w, lat - h), Point.fromLngLat(lon + w, lat + h),
        Point.fromLngLat(lon - w, lat + h), Point.fromLngLat(lon - w, lat - h))))
    val ways = listOf("kav-3d-warm-through" to 0.5f, "kav-3d-warm-solid" to 1f)
    var whole = 0
    val drawn = kotlinx.coroutines.CompletableDeferred<Unit>()
    // The second whole frame: the first may be one that was on its way before the square was there.
    val frame = MapView.OnDidFinishRenderingFrameListener { fully, _, _ -> if (fully && ++whole == 2) drawn.complete(Unit) }
    view.addOnDidFinishRenderingFrameListener(frame)
    try {
        style.addSource(GeoJsonSource(SRC_3D_WARM, Feature.fromGeometry(square)))
        for ((id, opacity) in ways) style.addLayer(FillExtrusionLayer(id, SRC_3D_WARM).withProperties(
            PropertyFactory.fillExtrusionColor(android.graphics.Color.TRANSPARENT),
            PropertyFactory.fillExtrusionHeight(Expression.coalesce(Expression.get("height"), Expression.literal(0))),
            PropertyFactory.fillExtrusionBase(Expression.coalesce(Expression.get("min_height"), Expression.literal(0))),
            PropertyFactory.fillExtrusionOpacity(opacity)))
        kotlinx.coroutines.withTimeoutOrNull(3000) { drawn.await() }
    } finally {
        view.removeOnDidFinishRenderingFrameListener(frame)
        if (style.isFullyLoaded) {
            for ((id, _) in ways) style.removeLayer(id)
            style.removeSource(SRC_3D_WARM)
        }
    }
}

// ref: the height the camera is placed for (MapCamera.ref), else the map's own.
private fun MapLibreMap.driveTo(cam: Camera, anchor: Offset, w: Float, h: Float, pitch: Float, screenDensity: Float, ref: Float = 0f) {
    val wx = cam.worldX + (anchor.x - w / 2) / cam.pxPerWorld
    val wy = cam.worldY + (anchor.y - (if (ref > 0f) ref else h) / 2) / cam.pxPerWorld
    val pad = focalPadding(anchor, w, h)
    moveCamera(CameraUpdateFactory.newCameraPosition(
        CameraPosition.Builder()
            .target(LatLng(Geo.lat(wy), Geo.lon(wx)))
            .zoom(cam.zoom.toDouble() - 1.0 - ln(screenDensity.toDouble()) / ln(2.0))
            .bearing(cam.rotation.toDouble())
            .tilt(pitch.toDouble())
            .padding(pad[0], pad[1], pad[2], pad[3])
            .build(),
    ))
}

// A map kept after its screen closed, with the style it has, for the next screen's map.
private class KeptMap(val view: MapView, val map: MapLibreMap, val style: Style, val json: String) {
    var observer: androidx.lifecycle.DefaultLifecycleObserver? = null
}

// One map at a time is kept when its screen closes and handed to the next map that opens: its style, fonts and the
// tiles it drew are loaded already, so that map shows what it should almost at once instead of an empty box filling
// in. It goes with the activity that made it.
private object MapPool {
    private var kept: KeptMap? = null
    // A map is kept drawn out of sight already (MapWarmUp), for the next screen with a map.
    var warmed by mutableStateOf(false)
    // The kept map was let go while Kav was out of sight (drop): none is drawn in its place until Kav is back.
    var resting by mutableStateOf(false)

    fun drop() {
        kept?.let { unwatch(it); it.view.onDestroy() }
        kept = null
        warmed = false
        resting = true
    }

    // refill: another is drawn out of sight a little later, for the screen after this one (the Live tab keeps the one
    // it takes for as long as the app is open).
    fun take(ctx: android.content.Context, refill: Boolean = true): KeptMap? {
        val k = kept ?: return null
        kept = null
        unwatch(k)
        if (k.view.context === ctx) { if (refill) warmed = false; return k }
        k.view.onDestroy()
        return null
    }

    fun give(k: KeptMap) {
        kept?.let { unwatch(it); it.view.onDestroy() }
        kept = k
        val observer = object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                unwatch(k)
                if (kept === k) { kept = null; k.view.onDestroy() }
            }
        }
        k.observer = observer
        (k.view.context as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.addObserver(observer)
    }

    private fun unwatch(k: KeptMap) {
        k.observer?.let { (k.view.context as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.removeObserver(it) }
        k.observer = null
    }
}

// The phone short of memory with Kav in the background (MainActivity.onTrimMemory): the map kept drawn for the next
// screen is let go, about 26 MB, and drawn again once Kav is back. Telling the maps in use to let go of what they can
// (MapView.onLowMemory) freed next to nothing when measured, so they're left alone.
internal object SpareMap {
    fun drop() = MapPool.drop()

    fun back() { MapPool.resting = false }
}

private val KAV_LAYERS = listOf(
    *LINES_OUT.toTypedArray(), *LINES_IN.toTypedArray(), *LINES.toTypedArray(), DOT_OUT, DOT_IN, "kav-dot", "kav-mark", *HALO_LAYERS.toTypedArray(), "kav-live-dot",
    MARK_OUT, TURN_OUT, GLIDE_MARK_OUT, GLIDE_TURN_OUT, "kav-live-mark", "kav-live-turn", GLIDE_MARK, GLIDE_TURN, RIDER_LAYER,
)

// Kav's own lines, marks and vehicles off a map, before it's kept for another screen.
private fun Style.removeKavLayers() {
    for (id in KAV_LAYERS) removeLayer(id)
    for (id in listOf(SRC_LINES_OUT, SRC_LINES, SRC_LINES_IN, SRC_DOTS, SRC_MARKS, SRC_LIVE_HALOS, SRC_LIVE_DOTS, SRC_LIVE_MARKS, SRC_GLIDE_HALOS, SRC_GLIDE_MARKS, SRC_RIDER)) removeSource(id)
}

@Composable
fun TileMap(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    padFraction: Float = 0.10f,
    fitMaxZoom: Float = Geo.MAX_Z.toFloat(),
    focusKey: Any? = Unit,
    recenterOn: Pair<Double, Double>? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    settle: MapSettle? = null,
    // Room below its box the map may grow into (the Live list a picked vehicle's card collapses): the map is drawn that
    // much taller, cut to its box, so growing into it never resizes it. A TextureView resized shows its last picture
    // stretched to the new size until one of that size comes, a jolt at each frame of a map that grows.
    reserve: androidx.compose.ui.unit.Dp = 0.dp,
    // How long the map takes to move to a new frame.
    moveMs: Int = 520,
    follow: Follow? = null,
    geometry: MapGeometry? = null,
    // How far what the map looks at has come in, read at each frame (the Live tab's picked vehicle): its lines and
    // what comes with it (FOCUS_IN) show that much, what goes (FOCUS_OUT) the rest. Opacities only, so the fades go
    // with the camera's move frame by frame; colours with the fade in them would send and lay everything out again.
    focus: (() -> Float)? = null,
    // How much shows, besides, of the stops and of the vehicles that go with a focus (FOCUS_OUT), read at each frame:
    // the Live tab's All, Vehicles and Stops, each fading out what it leaves out, by opacities as focus does.
    outStops: (() -> Float)? = null,
    outVehicles: (() -> Float)? = null,
    // Told each list of the geometry's dots once the map has drawn it (its source taken in, MapLibre parsing it off the
    // main thread, then a frame finished whole), as sent: for a fade that has to wait for its own dots, as a focus coming
    // in on dots just added (the stop picker's ring).
    onDotsSent: ((List<MapDot>) -> Unit)? = null,
    live: MapGeometry? = null,
    rider: MapMarker? = null,
    riderHeading: Float? = null,
    // Told each live geometry once the map has taken it all in, which of its vehicles go with a focus included: a focus
    // fading before then fades them as they were.
    onLiveTaken: ((MapGeometry) -> Unit)? = null,
    onTap: ((Offset, MapProjection) -> Unit)? = null,
    onLook: ((centre: Pair<Double, Double>?, reachKm: Double) -> Unit)? = null,
    moved: Boolean = false,
    keepZoom: Boolean = false,
    status: (@Composable () -> Unit)? = null,
    controls: (@Composable () -> Unit)? = null,
    attribution: Boolean = true,
    // The map read from GitHub while it isn't on the phone yet: a preview, as setup shows before the download.
    remote: Boolean = false,
    // Once it has drawn all it shows.
    onDrawn: (() -> Unit)? = null,
    // Drawn out of sight only to load its tiles (MapWarmUp).
    warming: Boolean = false,
    // The map goes with the rider (recenterOn) as they move, centred on them, until a hand moves it.
    followRider: Boolean = false,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val camera = remember { MapCamera() }
    var inViewport by remember { mutableStateOf(true) }
    camera.duration = moveMs
    val worldPoints = remember(points) {
        points.filter { it.first.isFinite() && it.second.isFinite() }.map {
            WorldPoint(Geo.x(it.second), Geo.y(it.first))
        }
    }
    val following = follow != null && !camera.manual
    // One tilt, never two to switch between: under the rider's fingers it follows them exactly (snap), and it eases
    // only when following starts or stops. Switching from the fingers' tilt to an eased copy lagging behind it made the
    // map jump between two angles.
    val tilt by animateFloatAsState(if (following) TILT_DEG else camera.userTilt,
        if (camera.tilting) snap() else tween(if (following) 600 else 280, easing = FastOutSlowInEasing), label = "tilt")
    // The gesture and tap handlers below are made once: they read the tilt as it is now through this.
    val tiltNow by rememberUpdatedState(tilt)

    // The map of a screen just closed, its style loaded, takes this one's place, so it draws in a moment (MapPool).
    val pooled = remember { MapPool.take(ctx, refill = !warming) }
    val mapView = remember {
        pooled?.view?.apply { alpha = 0f; (parent as? android.view.ViewGroup)?.removeView(this) } ?: MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx.also { uk.noammm.kav.data.GovMapTiles.mapLibre(it) })
            .textureMode(true)
            .compassEnabled(false).logoEnabled(false).attributionEnabled(false)
            .foregroundLoadColor(K.surface1.toArgb()))
            // At most one redraw a frame: drawing again at each change to the map (the halos' pulse, a glide's step,
            // the camera) would run about 240 times a second on the Live tab, keeping the GPU busy enough to drop the
            // whole screen's frames.
            .apply { setMaximumFps(60) }
            // Hidden until its first whole picture (drawn, below).
            .apply { alpha = 0f }
    }
    var map by remember { mutableStateOf(pooled?.map) }
    // Touch and heading motion hold the screen's rate; camera submissions renew it while they keep moving.
    val touching = remember { mutableStateOf(false) }
    val turning = remember { mutableStateOf(false) }
    val cameraMoves = remember { mutableIntStateOf(0) }
    var style by remember { mutableStateOf<Style?>(null) }
    // The 3D buildings' step as last set (leanBuildings); a style just loaded starts them flat again.
    var leaned by remember { mutableFloatStateOf(-1f) }
    // A style this screen has just set, for warmBuildings: one that came with a kept map has had its turn.
    var fresh by remember { mutableStateOf<Style?>(null) }
    LaunchedEffect(style) { leaned = -1f; style?.let { leaned = it.leanBuildings(tiltNow, leaned) } }
    // The style the map has, whether this screen has moved it to its own place yet (a kept map says it's drawn whole for
    // where it last was before it's moved), and whether a frame still loading came since.
    val held = remember { arrayOf(pooled?.json, false, false) }
    // The map fades in once it has drawn all it shows, instead of its lines on empty ground and then its streets a tile
    // at a time. Once: a new look's style comes in under the theme's own fade. One whose tiles never all come (a slow
    // preview) shows anyway after a moment.
    var drawn by remember { mutableStateOf(false) }
    // A screen that frames its map again a frame after it opens (a line's page, once it knows its open stop) shows it
    // framed so at once, not moving there with its tiles coming in as it goes.
    var shownAt by remember { mutableLongStateOf(0L) }
    camera.jump = { !drawn || android.os.SystemClock.uptimeMillis() - shownAt < 100 }
    // What the screen first gives the map to draw, each taken in before the map shows, so it shows with all of them
    // instead of its road first and its marks a frame or two later.
    val awaiting = remember {
        HashSet<String>().apply {
            if (geometry?.lines?.any { it.points.size >= 2 } == true) add(SRC_LINES)
            if (geometry?.markers?.isNotEmpty() == true) add(SRC_MARKS)
            if (geometry?.dots?.isNotEmpty() == true) add(SRC_DOTS)
            if (live?.markers?.isNotEmpty() == true) add(SRC_LIVE_MARKS)
        }
    }
    // A map whose vehicles pulse never finishes rendering, so the first frame drawn with all its tiles in counts. A
    // warming map waits for one still loading first: frames drawn as it moved can be of where it was, whole there.
    if (!drawn) DisposableEffect(mapView) {
        val taken = MapView.OnSourceChangedListener { id -> awaiting.remove(id) }
        mapView.addOnSourceChangedListener(taken)
        val whole = MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (held[1] == true && awaiting.isEmpty()) {
                if (!fully) held[2] = true
                else if (!warming || held[2] == true) drawn = true
            }
        }
        mapView.addOnDidFinishRenderingFrameListener(whole)
        onDispose { mapView.removeOnDidFinishRenderingFrameListener(whole); mapView.removeOnSourceChangedListener(taken) }
    }
    LaunchedEffect(style) { if (style != null && !drawn) { kotlinx.coroutines.delay(1500); drawn = true } }
    LaunchedEffect(drawn) {
        if (!drawn) return@LaunchedEffect
        shownAt = android.os.SystemClock.uptimeMillis()
        onDrawn?.invoke()
        animate(mapView.alpha, 1f, animationSpec = tween(250)) { v, _ -> mapView.alpha = v }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(mapView, lifecycle) {
        if (pooled == null) mapView.onCreate(null)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (map == null) mapView.getMapAsync { m ->
            m.uiSettings.setAllGesturesEnabled(false)
            val densityShift = ln(ctx.resources.displayMetrics.density.toDouble()) / ln(2.0)
            m.setMinZoomPreference(Geo.MIN_Z - 1.0 - densityShift)
            m.setMaxZoomPreference(Geo.MAX_Z + Geo.OVER - 1.0 - densityShift)
            // Tilted, the far part of the map takes its coarser tiles from 25° on (MapLibre's own start is 60°, Kav's
            // steepest): to the horizon in full detail is several times the tiles, and a zoom there dropped frames.
            m.tileLodPitchThreshold = Math.toRadians(25.0)
            m.tileLodMinRadius = 2.0
            map = m
        }
        onDispose {
            mapView.setMaximumFps(60)
            lifecycle.removeObserver(observer)
            val m = map
            val st = style
            val json = held[0] as String?
            map = null
            style = null
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            // Kept, without this screen's lines and marks, for the next map; else let go.
            if (m != null && st != null && st.isFullyLoaded && json != null) {
                st.removeKavLayers()
                MapPool.give(KeptMap(mapView, m, st, json))
            } else mapView.onDestroy()
        }
    }
    val type = MapFile.type
    val provider = MapFile.provider(type)
    val onPhone = provider.pack.state is MapPack.State.Ready
    val mapReady = onPhone || !provider.needsPack || remote
    val labels = MapFile.labels
    // Another map type, or its names in another language, is a new picture: the map fades out, and in again once the
    // new one is drawn whole.
    var drawnAs by remember { mutableStateOf(type to labels) }
    LaunchedEffect(map, mapReady, onPhone, K.look, type, labels, MapFile.buildings3d) {
        if (type to labels != drawnAs) {
            drawnAs = type to labels
            if (drawn) animate(mapView.alpha, 0f, animationSpec = tween(150)) { v, _ -> mapView.alpha = v }
            drawn = false
        }
        if (!mapReady) return@LaunchedEffect
        // Local tiles need no coarse network preview, which would also decode and upload another zoom level.
        map?.setPrefetchZoomDelta(if (provider.online() || remote && !onPhone) 4 else 0)
        val json = MapFile.styleJson(ctx, type, K.look, labels, preview = remote) ?: return@LaunchedEffect
        val kept = pooled?.style
        if (style == null && json == held[0] && kept != null && kept.isFullyLoaded) style = kept
        else map?.setStyle(Style.Builder().fromJson(json).withShields(ctx)) { held[0] = json; style = it; fresh = it }
    }
    LaunchedEffect(fresh) { warmBuildings(mapView, map ?: return@LaunchedEffect, fresh ?: return@LaunchedEffect) }
    fun Style.ensureKavIcons() {
        val px = with(density) { 1.dp.toPx() }
        val arrow = mapArrowIcon()
        addKavImage(arrow) { arrowBitmap(px, MapInk.icons.bg, MapInk.icons.text) }
    }
    val riderNow by rememberUpdatedState(rider)
    val placedRider = remember(style) { arrayOfNulls<MapMarker>(1) }
    fun Style.placeRider(at: Pair<Double, Double>? = null) {
        val mark = riderNow?.let { m -> at?.let { m.copy(lat = it.first, lon = it.second) } ?: m }
        if (mark == placedRider[0]) return
        getSourceAs<GeoJsonSource>(SRC_RIDER)?.setGeoJson(FeatureCollection.fromFeatures(markFeatures(listOfNotNull(mark))))
        placedRider[0] = mark
    }
    LaunchedEffect(style, rider) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        s.ensureKavLayers()
        s.ensureKavIcons()
        s.placeRider(placedRider[0]?.let { it.lat to it.lon })
    }
    val headingNow by rememberUpdatedState(riderHeading)
    LaunchedEffect(style, rider != null, inViewport) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        if (rider == null || !inViewport) return@LaunchedEffect
        s.ensureKavLayers()
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                val turn = HeadingSpring(headingNow ?: 0f)
                var last = 0L
                while (true) {
                    val at = withFrameNanos { it }
                    val moving = turn.step(headingNow ?: 0f, if (last == 0L) 0.0 else (at - last) / 1e9)
                    turning.value = moving
                    if (moving) stirred()
                    last = at
                    s.getLayer(RIDER_LAYER)?.setProperties(PropertyFactory.iconRotate(turn.value))
                    if (!moving) {
                        // Only a turn that shows (over 1.5 degrees, under a pixel at the arrow's tip): the compass jitters by
                        // more than a hundredth of a degree many times a second, and each step has the map drawn again,
                        // so a still phone kept the map drawing sixty times a second.
                        snapshotFlow { headingNow }.first { it != null && kotlin.math.abs((it - turn.value + 540f) % 360f - 180f) > 1.5f }
                        last = 0L
                    }
                }
            } finally {
                turning.value = false
            }
        }
    }
    val focusNow by rememberUpdatedState(focus)
    val outStopsNow by rememberUpdatedState(outStops)
    val outVehiclesNow by rememberUpdatedState(outVehicles)
    // The vehicles fading in when they first come (below), which the ones going with a focus take too.
    val liveFade = remember { Animatable(0f) }
    LaunchedEffect(style) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        s.ensureKavLayers()
        var sent: List<Float>? = null
        snapshotFlow {
            listOf(focusNow?.invoke() ?: 1f, liveFade.value, outStopsNow?.invoke() ?: 1f, outVehiclesNow?.invoke() ?: 1f)
        }.collect { now ->
            if (!s.isFullyLoaded || now == sent) return@collect
            stirred()
            val (f, liveA, stopsA, vehiclesA) = now
            if (f != sent?.get(0)) {
                for (id in LINES) s.getLayer(id)?.setProperties(PropertyFactory.lineOpacity(f))
                s.getLayer(DOT_IN)?.setProperties(PropertyFactory.circleOpacity(f), PropertyFactory.circleStrokeOpacity(f))
            }
            val out = (1f - f) * stopsA
            s.getLayer(DOT_OUT)?.setProperties(PropertyFactory.circleOpacity(out), PropertyFactory.circleStrokeOpacity(out))
            for (id in listOf(HALO_OUT, GLIDE_HALO_OUT)) s.getLayer(id)?.setProperties(PropertyFactory.circleOpacity((1f - f) * vehiclesA * liveA))
            // Never quite 0: a symbol layer at 0 isn't drawn, and coming back its marks would be placed anew and fade in
            // on the map's own 300 ms, after their halos.
            for (id in listOf(MARK_OUT, TURN_OUT, GLIDE_MARK_OUT, GLIDE_TURN_OUT))
                s.getLayer(id)?.setProperties(PropertyFactory.iconOpacity(((1f - f) * liveA * vehiclesA).coerceAtLeast(0.002f)))
            sent = now
        }
    }
    // Lines and marks made off the main thread, and sent only when they change: on the Live tab a pan changes the stops,
    // seldom the lines.
    val sentGeometry = remember(style) { arrayOfNulls<Any>(2) }
    LaunchedEffect(style, geometry) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        if (geometry != null) {
            s.ensureKavLayers()
            s.ensureKavIcons()
            s.addKavImages(geometry.images)
            // Lines that fade are sent as they fade, below.
            val lines = geometry.lines.takeIf { l -> l.none { it.fades } && l != sentGeometry[0] }
            val marks = geometry.markers.takeIf { it != sentGeometry[1] }
            if (lines == null && marks == null) return@LaunchedEffect
            val (lineSet, markSet) = withContext(Dispatchers.Default) {
                lines?.let(::lineFeatures) to marks?.let { FeatureCollection.fromFeatures(markFeatures(it)) }
            }
            if (s.isFullyLoaded) {
                s.setKavGeometry(lineSet, markSet)
                sentGeometry[0] = geometry.lines; sentGeometry[1] = geometry.markers
            }
        }
    }
    // Lines that fade (MapLine.fades) come and go at the stops' pace (below), the ones that stay kept still: a road
    // changed for another fades with its stops. Each change is sent once, the lines going, staying and coming in their
    // own sources, and only those layers' opacities change as it fades: nothing is sent or laid out again. A change that
    // comes mid-fade (the rider's driven part moves) waits for it to end instead of cutting it short.
    val fadingLines by rememberUpdatedState(geometry?.lines?.takeIf { l -> l.any { it.fades } })
    LaunchedEffect(style) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        var shown: List<MapLine>? = null
        fun fade(ids: List<String>, a: Float) {
            stirred()
            for (id in ids) s.getLayer(id)?.setProperties(PropertyFactory.lineOpacity(a))
        }
        // The map draws a source's new lines some frames after they're sent, and its "source changed" comes once a tile,
        // so it can't tell when they're in. A line moved between layers before the other one draws it blinks out, and a
        // layer shown too early shows its last lines. Each step waits 150 ms and four whole frames (400 ms at most); a
        // line drawn in two layers meanwhile looks the same.
        var wholeFrames = 0
        val whole = MapView.OnDidFinishRenderingFrameListener { fully, _, _ -> if (fully) wholeFrames++ }
        mapView.addOnDidFinishRenderingFrameListener(whole)
        suspend fun landed() {
            val from = wholeFrames
            val start = android.os.SystemClock.uptimeMillis()
            while (android.os.SystemClock.uptimeMillis() - start < 400 &&
                (wholeFrames < from + 4 || android.os.SystemClock.uptimeMillis() - start < 150)) withFrameMillis { }
        }
        try { snapshotFlow { fadingLines }.filterNotNull().collect { now ->
            if (now == shown || !s.isFullyLoaded) return@collect
            val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            // A map not shown yet has its lines there at once, to show with it.
            val instant = shown == null || !drawn || scale <= 0f
            val before = if (instant) emptyList() else shown.orEmpty()
            val was = before.toHashSet()
            val listed = now.toHashSet()
            val staying = now.filter { instant || it in was || !it.fades }
            val coming = now.filter { !instant && it !in was && it.fades }
            val going = before.filter { it !in listed && it.fades }
            // A line coming or going fades over the lines that stay when most of them are drawn under it, else under
            // them: with one layer each side it can't go between them, and a trip's middle ride still has to fade in
            // over the grey roads.
            val shows = { l: MapLine -> l.points.size >= 2 }
            fun over(list: List<MapLine>, l: MapLine, stays: (MapLine) -> Boolean): Boolean {
                val at = list.indexOf(l)
                var under = 0; var above = 0
                list.forEachIndexed { j, o -> if (j != at && shows(o) && stays(o)) { if (j < at) under++ else above++ } }
                return under > above
            }
            val (stay, come, go, all) = withContext(Dispatchers.Default) {
                listOf(lineFeatures(staying), lineFeatures(coming) { over(now, it) { o -> o in staying } },
                    lineFeatures(going) { over(before, it) { o -> o in listed } }, lineFeatures(now))
            }
            if (!s.isFullyLoaded) return@collect
            s.ensureKavLayers()
            shown = now
            if (coming.isEmpty() && going.isEmpty()) { s.setKavGeometry(stay, null); return@collect }
            // The fading layers are empty and hidden between fades. The going lines are drawn by theirs only once it has
            // them, over their copy in the main source, and the main one is let go of them after that.
            fade(LINES_IN, 0f); fade(LINES_OUT, 0f)
            s.getSourceAs<GeoJsonSource>(SRC_LINES_OUT)?.setGeoJson(go)
            s.getSourceAs<GeoJsonSource>(SRC_LINES_IN)?.setGeoJson(come)
            landed()
            if (!s.isFullyLoaded) return@collect
            fade(LINES_OUT, focusNow?.invoke() ?: 1f)
            landed()
            s.setKavGeometry(stay, null)
            landed()
            // The coming lines fade in over the going ones, which stay until they're in and then fade out: where the two
            // roads are the same, as two lines from one stop, it stays bright all along instead of dimming as both fade
            // at once. A coming line's dark casing comes in slower than its colour, or it would darken the road under it.
            val inUntil = if (coming.isEmpty()) 0f else 1f
            val end = inUntil + if (going.isEmpty()) 0f else 1f
            var t = 0f
            var last = 0L
            while (t < end) {
                val at = withFrameMillis { it }
                if (last != 0L) t = (t + (at - last).coerceAtMost(32L) / (240f * scale)).coerceAtMost(end)
                last = at
                val focus = focusNow?.invoke() ?: 1f
                val a = t.coerceAtMost(1f)
                fade(LINES_IN.filter { it !in IN_CASINGS }, a * focus); fade(IN_CASINGS, a * a * a * focus)
                fade(LINES_OUT, (1f - (t - inUntil).coerceIn(0f, 1f)) * focus)
            }
            // All of them back in the main source, and the fading ones emptied once it has them: a road left in one
            // flashed back at the next change, before the new one was in.
            if (!s.isFullyLoaded) return@collect
            s.setKavGeometry(all, null)
            landed()
            fade(LINES_IN, 0f); fade(LINES_OUT, 0f)
            // Emptied, and in, before the next change: a line switch comes as two changes (the ride, then its grey road).
            s.getSourceAs<GeoJsonSource>(SRC_LINES_IN)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            s.getSourceAs<GeoJsonSource>(SRC_LINES_OUT)?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            landed()
        } } finally { mapView.removeOnDidFinishRenderingFrameListener(whole) }
    }
    // Stops fade in as they come (a new area loaded, or Stops picked) and out as they go, each from where it was when
    // the next change came, while the ones that stay keep still. A dot is the same one while it keeps its place and
    // size, so new colours show at once. Its fade is drawn into it, in its place in the list, the ones going under the
    // rest, so every update of the map is whole and keeps the order.
    val liveNow by rememberUpdatedState(remember(live) { live?.oneHaloSize() })
    val dots by rememberUpdatedState(geometry?.dots)
    val dotsSentNow by rememberUpdatedState(onDotsSent)
    LaunchedEffect(style) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val shown = LinkedHashMap<Any, Float>()
        val last = HashMap<Any, MapDot>()
        // Nearby Live stops also fade when a pan changes the list, independently of the focus layer's fade.
        fun MapDot.fadesHere() = fade == 0 || (liveNow?.together == true && fade == FOCUS_OUT)
        snapshotFlow { dots }.filterNotNull().collectLatest { now ->
            val listed = now.mapTo(HashSet()) { it.place }
            now.associateByTo(last) { it.place }
            val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            var before = 0L
            var sent: List<MapDot>? = null
            do {
                val at = withFrameMillis { it }
                // A slow frame, like the one a switch's own work makes, moves a fade on by two frames at most, so the
                // fade still shows after it instead of being over.
                // A map not shown yet has its stops there at once, to show with it.
                val step = if (scale <= 0f || !drawn) 1f else if (before == 0L) 0f else (at - before).coerceAtMost(32L) / (240f * scale)
                before = at
                // One that comes or goes with a focus fades by its layer instead (TileMap's focus).
                for (p in listed) shown[p] = if (!last.getValue(p).fadesHere()) 1f else ((shown[p] ?: 0f) + step).coerceAtMost(1f)
                val going = shown.keys.filter { it !in listed }
                for (p in going) {
                    val a = shown.getValue(p) - step
                    if (a > 0f && last.getValue(p).fadesHere()) shown[p] = a else { shown.remove(p); last.remove(p) }
                }
                val drawn = going.mapNotNull { p -> shown[p]?.let { last.getValue(p).faded(it) } } +
                    now.map { it.faded(shown.getValue(it.place)) }
                // Made off the main thread: on the Live tab a pan brings a hundred stops in and others out at once.
                if (drawn != sent && s.isFullyLoaded) {
                    val features = withContext(Dispatchers.Default) { FeatureCollection.fromFeatures(dotFeatures(drawn)) }
                    if (s.isFullyLoaded) {
                        s.ensureKavLayers()
                        val told = dotsSentNow
                        if (told == null) s.setKavDots(features)
                        else {
                            // Taken in (the source changed), then drawn: the first frame the map finishes whole after it,
                            // with the new dots laid out.
                            val taken = kotlinx.coroutines.CompletableDeferred<Unit>()
                            val whole = kotlinx.coroutines.CompletableDeferred<Unit>()
                            val listener = MapView.OnSourceChangedListener { id -> if (id == SRC_DOTS) taken.complete(Unit) }
                            val frame = MapView.OnDidFinishRenderingFrameListener { fully, _, _ -> if (fully && taken.isCompleted) whole.complete(Unit) }
                            mapView.addOnSourceChangedListener(listener)
                            mapView.addOnDidFinishRenderingFrameListener(frame)
                            try {
                                s.setKavDots(features)
                                kotlinx.coroutines.withTimeoutOrNull(500) { whole.await() }
                            } finally {
                                mapView.removeOnSourceChangedListener(listener)
                                mapView.removeOnDidFinishRenderingFrameListener(frame)
                            }
                            told(drawn)
                        }
                    }
                    sent = drawn
                }
            } while (shown.any { (p, a) -> p !in listed || a < 1f })
        }
    }
    // A moving vehicle glides along its route's road where it has one (Glide); a new one, or one too far to glide, is
    // put there at once. Features are made off the main thread once an update. The gliding ones are in a source of their
    // own (SRC_GLIDE_*), sent again at each step, and the rest are sent once: with all of them in one source a step takes
    // the map 20 to 30 ms to take in, and a bus would move at 30 to 45 steps a second instead of at each frame the map
    // draws (60 a second). A step goes once the map has taken in the last one, twice as long after as that took, and
    // never fewer than twenty a second, without steps piling up where it's slow. A vehicle going into or out of the
    // gliding source is sent to the one taking it first, never in neither. Its halo leaves the other as soon as that's
    // taken in: drawn twice, a halo shows brighter, translucent as it is. Its mark stays in both two frames more, as the
    // map places marks a frame or two after it draws halos, and a mark drawn twice in one spot looks the same.
    // While the screen around the map changes (a card coming or going, settle), glides wait, as under a finger: their
    // steps would cost the frames the change needs. They go on from where they stopped.
    val settling by rememberUpdatedState(settle != null)
    val liveTaken by rememberUpdatedState(onLiveTaken)
    val mapStarted = lifecycle.currentStateFlow.collectAsState().value.isAtLeast(Lifecycle.State.STARTED) && inViewport
    val liveActive by rememberUpdatedState(mapStarted)
    val pace = remember(mapView) { MapPace(mapView) }
    // Any source's features changing is something moving (stirredAt): a glide's step, the rider, lines or marks.
    DisposableEffect(mapView) {
        val changed = MapView.OnSourceChangedListener { stirred() }
        mapView.addOnSourceChangedListener(changed)
        onDispose { mapView.removeOnSourceChangedListener(changed) }
    }
    LaunchedEffect(mapView, lifecycle, warming) {
        var boosted = false
        var previousMove = cameraMoves.intValue
        pace.busy = 60
        fun setRate(rate: Int) { pace.busy = rate }
        snapshotFlow { Triple(inViewport && !warming, touching.value || turning.value, cameraMoves.intValue) }
            .combine(lifecycle.currentStateFlow) { motion, state ->
                Triple(motion.first && state.isAtLeast(Lifecycle.State.STARTED), motion.second, motion.third)
            }.collectLatest { (active, held, move) ->
                val moved = move != previousMove
                previousMove = move
                if (!active) { setRate(60); boosted = false; return@collectLatest }
                if ((held || moved) && !boosted) {
                    @Suppress("DEPRECATION")
                    val rate = (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
                    setRate(rate.roundToInt().coerceIn(60, 120))
                    boosted = true
                }
                if (!held) {
                    kotlinx.coroutines.delay(250)
                    setRate(60)
                    boosted = false
                }
            }
    }
    LaunchedEffect(style) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        val drawn = HashMap<String, Pair<Double, Double>>()
        val images = HashMap<String, ImageBitmap>()
        var namedLast = emptySet<String>()
        var togetherLayers = false
        // The rest as last sent, not sent again while it's the same.
        var stillHalosSent: List<MapDot>? = null
        var stillMarksSent: List<MapMarker>? = null
        var liveSentDots: List<MapDot>? = null
        val times = LiveTimes(mapView)
        var taken = 0
        var tookMs = 50L
        var sentMs = 0L
        var stillTaken = 0
        var stillHalosTaken = 0
        // The vehicles in the gliding source.
        var gliding = emptySet<String>()
        val take = MapView.OnSourceChangedListener { id ->
            if (id == SRC_GLIDE_MARKS) { taken++; tookMs = android.os.SystemClock.uptimeMillis() - sentMs }
            if (id == SRC_LIVE_MARKS) stillTaken++
            if (id == SRC_LIVE_HALOS) stillHalosTaken++
        }
        mapView.addOnSourceChangedListener(take)
        // The map's frames drawn: a source taken in has its marks placed a frame or two after its halos, so a vehicle's
        // mark stays in the source it leaves for two more frames, or it would blink out while its halo stays.
        var rendered = 0
        val frame = MapView.OnDidFinishRenderingFrameListener { _, _, _ -> rendered++ }
        mapView.addOnDidFinishRenderingFrameListener(frame)
        suspend fun twoFrames() {
            val from = rendered
            val until = android.os.SystemClock.uptimeMillis() + 100
            while (rendered < from + 2 && android.os.SystemClock.uptimeMillis() < until) withFrameMillis { }
        }
        var wasActive = false
        try {
            snapshotFlow { liveNow.takeIf { liveActive } }.collectLatest { g ->
                if (g == null) { wasActive = false; return@collectLatest }
                // Source replacement also lays symbols out. Let the gesture finish before applying its newest snapshot.
                while (touching.value || settling) {
                    snapshotFlow { touching.value || settling }.first { !it }
                    kotlinx.coroutines.delay(150)
                }
                val glide = wasActive
                wasActive = true
                s.ensureKavLayers()
                s.ensureKavIcons()
                if (g.together) {
                    for ((name, image) in g.images) if (images[name] !== image) {
                        s.addImage(name, image.asAndroidBitmap())
                        images[name] = image
                    }
                    // Named by neither this update nor the last, an image is off the map: let it go, as a vehicle's image
                    // is named for its look (LiveScreen) and a new one comes with each change of it.
                    for (name in images.keys.filter { it !in g.images && it !in namedLast }) { s.removeImage(name); images.remove(name) }
                    namedLast = g.images.keys.toSet()
                } else s.addKavImages(g.images)
                times.begin()
                val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
                val (features, glides) = withContext(Dispatchers.Default) {
                    val made = System.nanoTime()
                    val places = HashMap<String, Pair<Double, Double>>()
                    for (m in g.markers) m.id?.let { places[it.substringBefore(':')] = m.lat to m.lon }
                    for (d in g.halos) d.id?.let { places[it.substringBefore(':')] = d.lat to d.lon }
                    val glides = HashMap<String, Glide>()
                    // A crowd (CROWD) moves to where it is now at once: hundreds of vehicles sent again at each step
                    // of a glide had every pan or zoom meanwhile drop frames, where each moves a few pixels.
                    if (glide && scale > 0f && g.halos.size <= CROWD) for ((id, to) in places) {
                        drawn[id]?.let { was -> Glide.of(was, to, g.tracks[id]) }?.let { glides[id] = it }
                    }
                    drawn.keys.retainAll(places.keys)
                    for ((id, to) in places) if (id !in glides) drawn[id] = to
                    val features = LiveFeatures(g)
                    times.madeNs = System.nanoTime() - made
                    features to glides
                }
                val now = glides.keys.toSet()
                if (g.together) {
                    // Headings stay below every bus body; source changes must not put one bus's arrow over another.
                    if (!togetherLayers) {
                        for ((turn, mark) in listOf(TURN_OUT to MARK_OUT, "kav-live-turn" to "kav-live-mark")) {
                            s.getLayer(turn)?.let { if (s.removeLayer(it)) s.addLayerBelow(it, mark) }
                            // In the order they're sent (their list's), not by a sort key: with one the map draws each
                            // mark on its own, a draw for every vehicle and every heading, which a crowd made thousands.
                            for (id in listOf(turn, mark)) s.getLayer(id)?.setProperties(
                                PropertyFactory.symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
                            )
                        }
                        for (id in listOf(HALO_OUT, HALO_LAYER)) s.getLayer(id)?.setProperties(
                            PropertyFactory.circleSortKey(Expression.toNumber(Expression.get("sort"))),
                        )
                        togetherLayers = true
                    }
                    var start = withFrameMillis { it }
                    var last = start
                    var sent = 0L
                    var asked = stillTaken
                    var haloAsked = stillHalosTaken
                    var told = false
                    while (true) {
                        val at = withFrameMillis { it }
                        if (!told && sent != 0L && stillTaken >= asked && stillHalosTaken >= haloAsked) {
                            told = true
                            liveTaken?.invoke(g)
                        }
                        if (touching.value || settling) start += at - last
                        last = at
                        val t = if (glides.isEmpty()) 1f else ((at - start) / (GLIDE_MS * scale)).coerceIn(0f, 1f)
                        if (sent != 0L && t < 1f && (at - sent < 33 || stillTaken < asked || stillHalosTaken < haloAsked || touching.value || settling)) continue
                        val eased = FastOutSlowInEasing.transform(t)
                        val (halos, marks) = withContext(Dispatchers.Default) {
                            for ((id, glide) in glides) drawn[id] = glide.place(eased)
                            features.together(eased, glides)
                        }
                        asked = stillTaken + 1
                        haloAsked = stillHalosTaken + 1
                        times.send { s.setKavLiveHalos(halos, features.dots); s.setKavLiveMarks(marks); s.placeRider(drawn["me"]) }
                        sent = at
                        if (t >= 1f) break
                    }
                    if (!told) kotlinx.coroutines.withTimeoutOrNull(500) {
                        while (stillTaken < asked || stillHalosTaken < haloAsked) withFrameMillis { }
                        liveTaken?.invoke(g)
                    }
                    times.end(features.count, glides.size)
                    return@collectLatest
                }
                // Ones gliding before and not now stay in the gliding source, where they are, until the rest has them:
                // their halos (stayHalos) until it's taken them in, their marks (stayMarks) two frames longer.
                var stayHalos = gliding - now
                var stayMarks = stayHalos
                val joining = now.any { it !in gliding }
                if (joining) {
                    // New ones go into the gliding source where they're drawn first, then out of the rest.
                    val (halos, marks) = withContext(Dispatchers.Default) { features.moving(0f, glides, stayHalos) }
                    if (s.isFullyLoaded) {
                        val asked = taken + 1
                        sentMs = android.os.SystemClock.uptimeMillis()
                        s.setKavGlide(halos, marks)
                        val until = android.os.SystemClock.uptimeMillis() + 200
                        while (taken < asked && android.os.SystemClock.uptimeMillis() < until) withFrameMillis { }
                    }
                }
                gliding = now + stayHalos
                val stillHalos = g.halos.filter { it.id?.substringBefore(':') !in now }
                val stillMarks = g.markers.filter { it.id?.substringBefore(':') !in now }
                var stillAsked = stillTaken
                var stillHalosAsked = stillHalosTaken
                val haloSend = stillHalos != stillHalosSent || g.dots != liveSentDots
                val markSend = stillMarks != stillMarksSent
                if (haloSend || markSend) {
                    val (haloSet, markSet) = withContext(Dispatchers.Default) { features.still(now) }
                    if (haloSend) {
                        stillHalosAsked = stillHalosTaken + 1
                        if (s.isFullyLoaded) times.send { s.setKavLiveHalos(haloSet, features.dots) }
                        stillHalosSent = stillHalos; liveSentDots = g.dots
                    }
                    if (markSend) {
                        // The new ones' marks leave the rest two frames after their halos, once the gliding source's are placed.
                        if (joining) twoFrames()
                        stillAsked = stillTaken + 1
                        if (s.isFullyLoaded) times.send { s.setKavLiveMarks(markSet) }
                        stillMarksSent = stillMarks
                    }
                }
                var start = withFrameMillis { it }
                var last = start
                var sent = 0L
                var awaited = 0
                var stillFrom = -1
                var stillMs = 0L
                // Told once both sources have this update, after its first step.
                var told = false
                fun tell() {
                    if (told || sent == 0L || taken < awaited || stillTaken < stillAsked || stillHalosTaken < stillHalosAsked) return
                    told = true
                    liveTaken?.invoke(g)
                }
                while (true) {
                    val at = withFrameMillis { it }
                    tell()
                    // Waiting, the glide's own clock waits too.
                    if (touching.value || settling) start += at - last
                    last = at
                    val t = if (glides.isEmpty()) 1f else ((at - start) / (GLIDE_MS * scale)).coerceIn(0f, 1f)
                    // The ones leaving go from the gliding source: their halos once the rest has taken them in, their
                    // marks two frames after that. Each goes in a step of its own, at once.
                    var left = false
                    if (stayHalos.isNotEmpty() && stillHalosTaken >= stillHalosAsked) { stayHalos = emptySet(); left = true }
                    if (stayMarks.isNotEmpty() && stillTaken >= stillAsked) {
                        if (stillFrom < 0) { stillFrom = rendered; stillMs = at }
                        if (rendered >= stillFrom + 2 || at - stillMs > 100) { stayMarks = emptySet(); left = true }
                    }
                    val leaving = stayHalos.isNotEmpty() || stayMarks.isNotEmpty()
                    val gap = if (taken >= awaited) (2 * tookMs).coerceIn(15L, 50L) else 50L
                    // The first step goes at once, where they're drawn, even while waiting: it brings this update's
                    // looks and fades to the ones gliding, which would otherwise keep the last ones until the wait ends.
                    if (!left && t < 1f && sent != 0L && (at - sent < gap || touching.value || settling)) continue
                    // The last step waits until the ones leaving are drawn by the rest, so it can leave them out.
                    if (!left && t >= 1f && leaving) continue
                    sent = at
                    val eased = FastOutSlowInEasing.transform(t)
                    val (halos, marks) = withContext(Dispatchers.Default) {
                        for ((id, glide) in glides) drawn[id] = glide.place(eased)
                        features.moving(eased, glides, stayHalos, stayMarks)
                    }
                    if (s.isFullyLoaded) {
                        awaited = taken + 1
                        sentMs = android.os.SystemClock.uptimeMillis()
                        times.send { s.setKavGlide(halos, marks) }
                    }
                    if (t >= 1f && !leaving) break
                }
                gliding = now
                times.end(features.count, glides.size)
                if (liveTaken != null) kotlinx.coroutines.withTimeoutOrNull(500) { while (!told) { withFrameMillis { }; tell() } }
            }
        } finally {
            mapView.removeOnSourceChangedListener(take)
            mapView.removeOnDidFinishRenderingFrameListener(frame)
            times.close()
        }
    }
    // Marks and vehicles fade in when they first come, not pop up; switching what's shown doesn't fade them again.
    val marksShown = geometry != null && geometry.markers.isNotEmpty()
    val liveShown = live != null && (live.dots.isNotEmpty() || live.markers.isNotEmpty())
    val marksFade = remember { Animatable(0f) }
    LaunchedEffect(marksShown) { if (marksShown) { marksFade.snapTo(0f); marksFade.animateTo(1f, tween(360)) } }
    LaunchedEffect(liveShown) { if (liveShown) { liveFade.snapTo(0f); liveFade.animateTo(1f, tween(360)) } }
    LaunchedEffect(style) {
        val s = style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        snapshotFlow { marksFade.value to liveFade.value }.collect { (marksA, liveA) ->
            if (!s.isFullyLoaded) return@collect
            s.fadeLayers(marksA, "kav-mark")
            s.fadeLayers(liveA, "kav-live-dot", "kav-live-mark", "kav-live-turn", GLIDE_MARK, GLIDE_TURN)
            // The rider's halo is a layer of its own (RIDER_HALO), so this is one plain number.
            for (id in listOf(HALO_LAYER, GLIDE_HALO)) s.getLayer(id)?.setProperties(PropertyFactory.circleOpacity(liveA))
        }
    }
    if (live?.halos?.isNotEmpty() == true) {
        // The pulse goes on only while the map's screen is started, not on a tab kept behind another, where it would keep
        // the app drawing frames and setting the halos' size. It goes on from where it stopped, so the halos don't jump.
        val started = mapStarted
        val pulse = remember { PulseClock() }
        LaunchedEffect(style, started) {
            val s = style?.takeIf { it.isFullyLoaded && started } ?: return@LaunchedEffect
            var sent = 0L
            var seen: MapGeometry? = null
            var one: Float? = null
            var before = -1L
            var held = false
            try { while (true) {
                if (held) {
                    pace.rest = false
                    snapshotFlow { (liveNow?.halos?.size ?: 0) > CROWD }.first { !it }
                    held = false
                    before = -1L
                }
                // No vehicle, no pulse: each step has the map drawn again, and with none on it (a line's page while its
                // buses aren't reporting) the map was drawn thirty times a second for nothing, taking the GPU from the
                // cards over it.
                if (liveNow?.halos.isNullOrEmpty()) {
                    pace.rest = false
                    snapshotFlow { liveNow?.halos.isNullOrEmpty() }.first { !it }
                    before = -1L
                }
                withFrameMillis { t ->
                    // At rest once the pulse is all that's moved for a while (MapPace): nothing under a finger, turning
                    // or changing around the map.
                    pace.rest = !touching.value && !turning.value && !settling &&
                        android.os.SystemClock.uptimeMillis() - stirredAt > REST_AFTER_MS
                    if (before >= 0) pulse.ms += t - before
                    before = t
                    val p = pulse.value()
                    // Halos all one size (the Live tab's) get that size as a plain number: a radius read from each feature
                    // makes the map lay out every halo again at each step, one plain number only draws them again.
                    val g = liveNow
                    if (g !== seen) { seen = g; one = g?.halos?.map { it.radius }?.distinct()?.singleOrNull() }
                    // A crowd of vehicles (a city centre's hundreds) holds still once its halos are near their
                    // smallest: the map then draws them once, not sixty times a second, which on a slower phone took
                    // the frames a pan or a zoom needed. Held where they are, they don't jump, and the pulse goes on
                    // from there once the crowd thins out.
                    if ((g?.halos?.size ?: 0) > CROWD && p < 0.88f) { held = true; return@withFrameMillis }
                    // Thirty steps a second are enough for the soft pulse (under a pixel each): each one has the map and
                    // the whole screen over it drawn again, so at sixty a still screen kept over a core busy.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!s.isFullyLoaded || now - sent < 33) return@withFrameMillis
                    sent = now
                    val radius = one?.let { PropertyFactory.circleRadius((it.toDouble() * p.toDouble()).toFloat()) }
                        ?: PropertyFactory.circleRadius(Expression.product(Expression.toNumber(Expression.get("r")), Expression.literal(p)))
                    for (id in HALO_LAYERS) s.getLayer(id)?.setProperties(radius)
                }
            } } finally { pace.rest = false }
        }
    }

    // Seen while a new map fades in: a light map's ground under a light map, a dark one's under a dark map, so a
    // change between two of them doesn't blink through the app's own.
    BoxWithConstraints(modifier.onGloballyPositioned {
        val bounds = it.boundsInWindow()
        inViewport = warming || bounds.width > 0f && bounds.height > 0f
    }.clipToBounds().background(if (MapInk.light != K.light) MapInk.bg else K.surface1)) {
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        val padL = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx() }.coerceIn(0f, (w - 1).coerceAtLeast(0f))
        val padT = with(density) { contentPadding.calculateTopPadding().toPx() }.coerceIn(0f, (h - 1).coerceAtLeast(0f))
        val padR = with(density) { contentPadding.calculateRightPadding(layoutDirection).toPx() }
        val padB = with(density) { contentPadding.calculateBottomPadding().toPx() }
        val viewport = Viewport(padL, padT,
            (w - padR).coerceAtLeast(padL + 1),
            (h - padB).coerceAtLeast(padT + 1), w, h)
        val liveViewport by rememberUpdatedState(viewport)
        // While the screen around it changes (settle), what the map frames is for its size when the change ends.
        val base = remember(settle?.id) { h }
        val framing = settle?.let { st ->
            val end = (base + with(density) { st.grow.toPx() }).coerceAtLeast(padT + 2)
            Viewport(padL, padT, (w - padR).coerceAtLeast(padL + 1), (end - padB).coerceAtLeast(padT + 1), w, end)
        } ?: viewport
        // Meanwhile the map itself keeps the larger of its heights then and after, cut to its box, instead of being
        // made again at each frame's size; with its room to grow (reserve) it's that size before and after too.
        val viewH = if (settle != null) maxOf(base, framing.h) else h + with(density) { reserve.toPx() }

        val withRider = followRider && !camera.manual && recenterOn != null
        LaunchedEffect(worldPoints, framing, focusKey, padFraction, fitMaxZoom, follow == null, keepZoom, withRider) {
            val ref = if (settle != null) framing.h else 0f
            if (ref != camera.ref) camera.rebase(if (camera.ref > 0f) camera.ref else h, if (ref > 0f) ref else h, ref, scope)
            if (follow == null && !withRider) camera.update(worldPoints, framing, focusKey, padFraction, fitMaxZoom, keepZoom, scope)
        }
        LaunchedEffect(withRider, recenterOn) {
            val me = recenterOn?.takeIf { withRider && it.first.isFinite() && it.second.isFinite() } ?: return@LaunchedEffect
            camera.centre(WorldPoint(Geo.x(me.second), Geo.y(me.first)), scope)
        }
        LaunchedEffect(focusKey) { camera.resume() }
        LaunchedEffect(follow, viewport, camera.manual) {
            if (follow != null && !camera.manual) camera.follow(follow, viewport, scope)
        }
        val anchor = viewport.anchor
        val look by rememberUpdatedState(onLook)
        val mapSize by rememberUpdatedState(w to h)
        // Only a hand on the map changes where it looks: the map growing or shrinking isn't a new place.
        if (onLook != null) LaunchedEffect(Unit) {
            snapshotFlow { camera.value?.takeIf { camera.manual } }.collectLatest { c ->
                if (c == null) return@collectLatest
                kotlinx.coroutines.delay(600)
                val (sw, sh) = mapSize
                val proj = MapProjection(c.worldX, c.worldY, c.pxPerWorld, sw, sh)
                val mid = proj.latLon(Offset(sw / 2, sh / 2))
                val corner = proj.latLon(Offset(0f, 0f))
                look?.invoke(mid, metres(mid.first, mid.second, corner.first, corner.second) / 1000.0)
            }
        }

        // The map's size and anchor as they are now, read here rather than restarting this at each change: as a card
        // opens they change every frame, and restarted at each one it never got to place the map until the card had
        // settled, when the map jumped to where the camera's move had got (Live, a vehicle tapped).
        val placing by rememberUpdatedState(Triple(Offset(w, viewH), h, anchor))
        LaunchedEffect(map) {
            val m = map ?: return@LaunchedEffect
            var driven: Any? = null
            snapshotFlow { camera.value?.let { Triple(it, tilt to camera.ref, placing) } }.filterNotNull()
                .collect {
                    withFrameNanos { }
                    val latest = camera.value?.let { Triple(it, tilt to camera.ref, placing) } ?: return@collect
                    if (latest == driven) return@collect
                    driven = latest
                    val (cam, turned, at) = latest
                    val (pitch, ref) = turned
                    val (size, height, anchorNow) = at
                    m.driveTo(cam, anchorNow, size.x, size.y, pitch, density.density, if (ref > 0f) ref else height)
                    cameraMoves.intValue++
                    stirred()
                    style?.let { leaned = it.leanBuildings(pitch, leaned) }
                    // A frame still loading has to come after this move for a warming map to count as drawn.
                    if (!drawn) held[2] = false
                    held[1] = true
                }
        }

        // Placed again as the map is resized, in the same layout pass, so no frame of it has the new size with the old
        // placing, which shifts it by half the change for a frame each time the Live list grows or shrinks.
        DisposableEffect(mapView, map) {
            val m = map
            val resized = android.view.View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
                val c = camera.value
                if (m != null && c != null && (r - l != or - ol || b - t != ob - ot)) {
                    m.driveTo(c, liveViewport.anchor, (r - l).toFloat(), (b - t).toFloat(), tilt, density.density,
                        if (camera.ref > 0f) camera.ref else liveViewport.h)
                    cameraMoves.intValue++
                    stirred()
                }
            }
            mapView.addOnLayoutChangeListener(resized)
            onDispose { mapView.removeOnLayoutChangeListener(resized) }
        }
        AndroidView(factory = { mapView }, modifier = Modifier
            .layout { measurable, constraints ->
                val tall = viewH.roundToInt().coerceAtLeast(constraints.maxHeight)
                val p = measurable.measure(constraints.copy(minHeight = tall, maxHeight = tall))
                layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
            }
            .ownDamage())

        val tap by rememberUpdatedState(onTap)
        Box(
            Modifier.fillMaxSize()
                .then(if (onTap == null) Modifier else Modifier.pointerInput(anchor) {
                    detectTapGestures { at ->
                        val current = camera.value ?: return@detectTapGestures
                        val flat = MapProjection(current.worldX, current.worldY, current.pxPerWorld,
                            size.width.toFloat(), if (camera.ref > 0f) camera.ref else size.height.toFloat())
                        // Tilted, the flat sums miss: the place under the finger comes from MapLibre, then goes where the
                        // flat map would show it.
                        val leaned = if (tiltNow > 0.5f) map?.projection?.fromScreenLocation(android.graphics.PointF(at.x, at.y)) else null
                        tap?.invoke(leaned?.let { unrotate(flat.point(it.latitude, it.longitude), anchor, 0f) }
                            ?: unrotate(at, anchor, current.rotation), flat)
                    }
                })
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        touching.value = true
                        do { val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial) } while (e.changes.any { it.pressed })
                        touching.value = false
                    }
                }
                .pointerInput(camera) {
                    detectTransformGestures(panZoomLock = true) { centroid, panChange, zoomChange, twist ->
                        camera.gesture(centroid, panChange, zoomChange, twist, liveViewport)
                    }
                }
                .pointerInput(camera) {
                    val start = 18.dp.toPx()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var slid = 0f
                        do {
                            val e = awaitPointerEvent()
                            val fingers = e.changes.filter { it.pressed && it.previousPressed }
                            if (fingers.size == 2) {
                                val (a, b) = fingers
                                val da = a.position - a.previousPosition
                                val db = b.position - b.previousPosition
                                val spread = (a.position - b.position).getDistance() - (a.previousPosition - b.previousPosition).getDistance()
                                val upright = da.y * db.y > 0 && abs(da.y) > 2 * abs(da.x) && abs(db.y) > 2 * abs(db.x) && abs(spread) < 6f
                                if (!camera.tilting) {
                                    slid = if (upright) slid + (da.y + db.y) / 2 else 0f
                                    if (abs(slid) > start) camera.tilting = true
                                }
                                if (camera.tilting) {
                                    camera.tiltBy(-(da.y + db.y) / 2 * TILT_PER_PX, tiltNow)
                                    e.changes.forEach { it.consume() }
                                }
                            }
                        } while (e.changes.any { it.pressed })
                        camera.tilting = false
                    }
                },
        )

        if (!mapReady) MapDownloadCard(
            provider.pack, type == MapFile.Type.SATELLITE,
            Modifier.align(Alignment.TopStart)
                .padding(start = with(density) { padL.toDp() }, top = with(density) { padT.toDp() })
                .size(with(density) { (w - padL - padR).coerceAtLeast(1f).toDp() },
                    with(density) { (h - padT - padB).coerceAtLeast(1f).toDp() }),
        )

        // One row, so a long status wraps instead of running under the buttons.
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .padding(start = contentPadding.calculateStartPadding(layoutDirection) + K.gap2,
                    top = contentPadding.calculateTopPadding() + K.gap2,
                    end = contentPadding.calculateEndPadding(layoutDirection) + K.gap3),
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.weight(1f)) { status?.invoke() }
            controls?.invoke()
            // Moved away: one round button brings the map back, following again or centred as it was.
            androidx.compose.animation.AnimatedVisibility(
                camera.manual || moved,
                enter = androidx.compose.animation.fadeIn(tween(160)) + androidx.compose.animation.scaleIn(initialScale = .8f),
                exit = androidx.compose.animation.fadeOut(tween(120)) + androidx.compose.animation.scaleOut(targetScale = .8f),
            ) {
                // Frosted, not liquid: the map is part of the backdrop a liquid pane draws, so a liquid button on it
                // would draw itself without end and crash the render thread.
                CompositionLocalProvider(LocalLiquidBackdrop provides null) {
                    val me = recenterOn?.takeIf { it.first.isFinite() && it.second.isFinite() }
                    // Its glyph says where it goes: the way followed, the rider, or back to what the map frames.
                    KavIconButton(
                        when {
                            follow != null -> Icons.Rounded.Navigation
                            me != null -> Icons.Rounded.MyLocation
                            else -> Icons.Rounded.CenterFocusStrong
                        },
                        if (follow != null) T("Follow", "עקבו") else T("Recentre", "מירכוז"),
                        {
                            look?.invoke(null, 0.0)
                            when {
                                follow != null -> camera.resume()
                                me != null -> camera.centre(WorldPoint(Geo.x(me.second), Geo.y(me.first)), scope)
                                else -> camera.reset(worldPoints, viewport, padFraction, fitMaxZoom, scope)
                            }
                        },
                        floating = true, tint = K.text, size = 44.dp,
                    )
                }
            }
        }

        // The credit rides up and down with the cards over the map instead of jumping, moved at layout only.
        val creditEnd = animateDpAsState(contentPadding.calculateEndPadding(layoutDirection) + 6.dp, spring(dampingRatio = 1f, stiffness = 500f), label = "creditEnd")
        val creditBottom = animateDpAsState(contentPadding.calculateBottomPadding() + 3.dp, spring(dampingRatio = 1f, stiffness = 500f), label = "creditBottom")
        if (attribution) MapAttribution(Modifier.align(Alignment.BottomEnd)
            .offset { IntOffset(-creditEnd.value.roundToPx(), -creditBottom.value.roundToPx()) })
    }
}

// The halos' pulse, rememberLivePulse's curve (0.85 to 1.35 and back, 1.4 s each way), by the time it has run.
// More halos than this on a map and they stop pulsing (TileMap).
private const val CROWD = 150

private class PulseClock {
    var ms = 0L
    fun value(): Float {
        val k = ms % 2_800L
        return 0.85f + 0.5f * FastOutSlowInEasing.transform(if (k < 1_400L) k / 1_400f else (2_800L - k) / 1_400f)
    }
}

// A map drawn out of sight a little after the app opens, and again a little after a screen takes it, where the rider is
// and then as the trip of Home's latest route frames it, then kept (MapPool): the next screen with a map takes it with
// its style, fonts and the tiles of both loaded, and shows the map whole at once instead of an empty box filling in. It
// takes no room and isn't seen or read out.
@Composable
fun MapWarmUp(here: Pair<Double, Double>?) {
    // Only a map drawn from the phone: one that loads online would ask its server for where the rider is, unasked.
    val provider = MapFile.provider()
    if (MapPool.warmed || MapPool.resting || provider.online() || provider.pack.state !is MapPack.State.Ready) return
    // 0: not yet, 1: where the rider is, 2: the latest route, in its trip page's map (RouteMap). Between the two the
    // map goes back to the pool for a frame, so the second takes the same one.
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(3_000); step = 1 }
    LaunchedEffect(step) { if (step == -2) step = 2 }
    val route = remember { LatestRoute.route?.trip?.legs?.filter { it.shape.size >= 2 }?.flatMap { it.shape }?.takeIf { it.isNotEmpty() } }
    if (step <= 0) return
    val points = if (step == 2 && route != null) route else listOf(here ?: (32.0759 to 34.7745))
    val side = if (step == 2) K.gap3 else 0.dp
    val tall = if (step == 2) 190.dp else 210.dp
    Box(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(
                    constraints.maxWidth - 2 * side.roundToPx(), tall.roundToPx()))
                layout(0, 0) { p.place(0, 0) }
            }
            .graphicsLayer { alpha = 0f }
            .clearAndSetSemantics {},
    ) {
        TileMap(points, Modifier.fillMaxSize(), attribution = false, warming = true, onDrawn = {
            if (step == 1 && route != null) step = -2 else MapPool.warmed = true
        })
    }
}

// The map's credit line, drawn by a screen whose own cards cover the map's corner (Navigate) where they end.
// OpenStreetMap's guidelines let Kav's own map's credit fade 5 s after the map shows, as About Kav keeps the full
// credits. Esri's and OpenMapTiles' stay on the map, Esri's light on a dark edge so it reads on any photo.
@Composable
internal fun MapAttribution(modifier: Modifier = Modifier) {
    val type = MapFile.type
    val provider = MapFile.provider(type)
    val ready = provider.pack.state is MapPack.State.Ready
    var faded by remember(type, ready) { mutableStateOf(false) }
    if (!provider.creditStays && ready) LaunchedEffect(type, ready) {
        kotlinx.coroutines.delay(5_000)
        faded = true
    }
    val alpha by animateFloatAsState(if (faded) 0f else 0.8f, tween(400), label = "mapCredit")
    // Another map's credit fades in over the last one's.
    androidx.compose.animation.Crossfade(type, modifier.alpha(alpha), tween(300), label = "creditType") { t ->
        val p = MapFile.provider(t)
        val photos = t == MapFile.Type.SATELLITE
        Text(
            p.credit, fontSize = 8.sp,
            color = when {
                photos -> Color.White
                p.lightGround -> Color(0xFF3A3A3A)
                // OpenFreeMap's dark maps, Fiord's blue grey too, whatever Kav's look: the map's own light ink.
                t != MapFile.Type.STANDARD -> MapInk.text
                else -> K.dim
            },
            style = if (photos) androidx.compose.ui.text.TextStyle(
                shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .8f), blurRadius = 3f),
            ) else androidx.compose.ui.text.TextStyle.Default,
        )
    }
}

@Composable
private fun MapDownloadCard(pack: MapPack, satellite: Boolean, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(K.gap4).panel(K.rCard).padding(K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap2),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = pack.state) {
                is MapPack.State.Downloading -> {
                    // The bar fills smoothly between ticks, and the percentage rolls.
                    val fill by animateFloatAsState(s.progress.coerceIn(0.02f, 1f), tween(300), label = "mapDownload")
                    Box(Modifier.width(160.dp).height(6.dp).clip(K.capsule).background(K.surface4)) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(K.accent))
                    }
                    LiveText(T("Downloading… ${(s.progress * 100).toInt()}%", "מורידים… ${(s.progress * 100).toInt()}%"), style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp), color = K.dim)
                }
                else -> {
                    Text(T("Download the map", "הורדת המפה"), style = K.type.bodyStrong)
                    Text(
                        if (satellite) T(
                            "Satellite's names come from Kav's map on your phone: about ${pack.bytes shr 20} MB for all of Israel, once.",
                            "השמות במפת הלוויין מגיעים מהמפה של Kav בטלפון: כ-${pack.bytes shr 20} מגה-בייט לכל ישראל, פעם אחת.",
                        ) else T(
                            "About ${pack.bytes shr 20} MB for all of Israel, once. Then it works with no signal.",
                            "כ-${pack.bytes shr 20} מגה-בייט לכל ישראל, פעם אחת. אחר כך היא עובדת גם בלי קליטה.",
                        ),
                        style = K.type.subhead,
                    )
                    if (s is MapPack.State.Failed) Text(T("Couldn't download it. ${s.why}", "ההורדה נכשלה. ${s.why}"), style = K.type.subhead, color = K.critical)
                    KavButton(if (s is MapPack.State.Failed) T("Try again", "נסו שוב") else T("Download", "הורדה"), { pack.download(ctx.applicationContext.filesDir) })
                }
            }
        }
    }
}
