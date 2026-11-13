package uk.noammm.kav.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit

private const val ROUTE_HUE_NUDGE = 34f
private const val ROUTE_HUE_RUNGS = 3

private fun routeBase(ride: Transit.Leg, r: Transit.Resolved, light: Boolean, fallback: Color): Color {
    val info = r.line(ride.lineId) ?: return fallback
    val type = r.routeType(info.agencyId)
    return plateFor(type, info.agencyId)?.fill
        ?: busOperator(type, info.agencyId) { Operators.of(r, info.agencyId) }?.takeIf(::hued)?.let { onScreen(it, light) }
        ?: fallback
}

private fun turned(base: Color, rung: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    hsv[0] = (hsv[0] + ROUTE_HUE_NUDGE * rung).mod(360f)
    hsv[1] = hsv[1].coerceAtLeast(0.55f)
    hsv[2] = hsv[2].coerceAtLeast(0.82f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// light: whether what they're drawn on is light, the app's pages by default (a map's is MapInk.light); fallback: a ride
// whose line has no colour of its own.
internal fun routeTints(
    rides: List<Transit.Leg>, r: Transit.Resolved, light: Boolean = K.light, fallback: Color = K.route,
): List<Color> {
    var run = 0
    var last: Color? = null
    return rides.map { ride ->
        val base = routeBase(ride, r, light, fallback)
        run = if (base == last) (run + 1).mod(ROUTE_HUE_RUNGS) else 0
        last = base
        if (run == 0) base else turned(base, run)
    }
}

internal fun boardingDots(at: Pair<Double, Double>, tint: Color, core: Color = tint): List<MapDot> = listOf(
    MapDot(at.first, at.second, MapInk.bg, 9f),
    MapDot(at.first, at.second, Color.Transparent, 7f, tint, 2.5f),
    MapDot(at.first, at.second, core, 3.5f),
)

internal fun boardingMarkers(
    rides: List<Transit.Leg>,
    tints: List<Color>,
    r: Transit.Resolved,
    stops: Map<Int, Transit.StopInfo> = r.stops,
): List<MapDot> {
    val board = rides.map { l ->
        (stops[l.fromStop]?.point ?: l.stops.firstOrNull()?.let { stops[it] }?.point ?: l.shape.firstOrNull())
            ?.let { onRoute(it, l.shape) }
    }
    val alight = rides.map { l ->
        (stops[l.toStop]?.point ?: l.stops.lastOrNull()?.let { stops[it] }?.point ?: l.shape.lastOrNull())
            ?.let { onRoute(it, l.shape) }
    }
    val changes = rides.indices.map { i ->
        i < rides.lastIndex && rides[i].toStop > 0 && rides[i].toStop == rides[i + 1].fromStop
    }
    return rides.indices.flatMap { i ->
        val tint = tints[i]
        val getOn = if (i > 0 && changes[i - 1]) emptyList() else board[i]?.let { boardingDots(it, tint) }.orEmpty()
        val getOff = alight[i]?.let {
            if (!changes[i]) boardingDots(it, tint)
            else boardingDots(between(it, board[i + 1] ?: it), tint, tints[i + 1])
        }.orEmpty()
        getOn + getOff
    }
}

// The walk inside a station has no path, so each end gets a mark instead of a line.
internal enum class StationPoint { ENTRANCE, EXIT, PLATFORM }

internal class StationMark(
    val lat: Double,
    val lon: Double,
    val point: StationPoint,
    val mode: Mode = Mode.TRAIN,
    val tint: Color = MapInk.route,
)

internal fun stationMarks(
    legs: List<Transit.Leg>,
    rides: List<Transit.Leg>,
    tints: List<Color>,
    r: Transit.Resolved,
): List<StationMark> {
    val out = ArrayList<StationMark>()
    for (i in legs.indices) {
        if (!legs[i].pathway) continue
        val before = legs.subList(0, i).lastOrNull { !it.pathway && it.kind != Transit.LegKind.WAIT }
        val after = legs.subList(i + 1, legs.size).firstOrNull { !it.pathway && it.kind != Transit.LegKind.WAIT }
        for ((leg, ending) in listOf(before to true, after to false)) {
            if (leg == null) continue
            val (lat, lon) = (if (ending) leg.shape.lastOrNull() else leg.shape.firstOrNull()) ?: continue
            out.add(
                if (leg.kind == Transit.LegKind.WALK) {
                    StationMark(lat, lon, if (ending) StationPoint.ENTRANCE else StationPoint.EXIT)
                } else StationMark(
                    lat, lon, StationPoint.PLATFORM, modeOf(r.routeType(r.line(leg.lineId)?.agencyId ?: -1)),
                    tints.getOrElse(rides.indexOfFirst { it === leg }) { MapInk.route },
                ),
            )
        }
    }
    return out.distinctBy { Triple(it.lat, it.lon, it.point) }
}

internal class MapMarks(val markers: List<MapMarker>, val images: Map<String, ImageBitmap>)

// Station doors, platforms and the pin where the trip ends, drawn by the map itself.
internal fun tripMarks(
    marks: List<StationMark>,
    end: Pair<Double, Double>?,
    entrance: Painter,
    exit: Painter,
    density: Density,
    dir: LayoutDirection,
): MapMarks {
    val markers = ArrayList<MapMarker>()
    // Doors come after the platforms, so a door sharing a station with the next ride's platform stays in front of it.
    val doors = ArrayList<MapMarker>()
    val images = HashMap<String, ImageBitmap>()
    for (m in marks) {
        if (m.point == StationPoint.PLATFORM) {
            val (name, image) = ringedMark(m.mode, m.tint, 11f, 9f, density)
            images[name] = image
            markers += MapMarker(m.lat, m.lon, name)
            continue
        }
        val out = m.point == StationPoint.EXIT
        val (name, image) = doorIcon(if (out) exit else entrance, out, density, dir)
        images[name] = image
        doors += MapMarker(m.lat, m.lon, name)
    }
    markers += doors
    end?.let { (lat, lon) ->
        val (name, image) = pinIcon(density, dir)
        images[name] = image
        markers += MapMarker(lat, lon, name, bottom = true)
    }
    return MapMarks(markers, images)
}

private val iconCache = HashMap<String, ImageBitmap>()

private fun icon(
    name: String, w: Float, h: Float, density: Density, dir: LayoutDirection, draw: DrawScope.() -> Unit,
): Pair<String, ImageBitmap> = name to iconCache.getOrPut(name) {
    val image = ImageBitmap(ceil(w).toInt(), ceil(h).toInt())
    CanvasDrawScope().draw(density, dir, androidx.compose.ui.graphics.Canvas(image), Size(w, h), draw)
    image
}

private fun doorIcon(glyph: Painter, out: Boolean, density: Density, dir: LayoutDirection) = with(density) {
    val outer = 23.dp.toPx(); val inner = 19.dp.toPx(); val g = 14.dp.toPx()
    val ink = MapInk.icons
    val name = "kav-door-${if (out) "out" else "in"}-${ink.bg.toArgb()}-${ink.text.toArgb()}-$dir"
    icon(name, outer, outer, density, dir) {
        drawRoundRect(ink.bg, Offset.Zero, Size(outer, outer), CornerRadius(7.dp.toPx()))
        drawRoundRect(ink.text, Offset((outer - inner) / 2, (outer - inner) / 2), Size(inner, inner), CornerRadius(5.dp.toPx()))
        translate((outer - g) / 2, (outer - g) / 2) { with(glyph) { draw(Size(g, g), colorFilter = ColorFilter.tint(ink.bg)) } }
    }
}

// The end of trip pin, in 0.8dp units. The image ends at the tip so it stands on the spot.
// The end of a trip's way: a pin 28 wide and 35 tall, its point at (14, 35). A shared trip's card draws it too.
internal fun pinPath() = Path().apply {
    moveTo(0f, 14f)
    cubicTo(0f, 24.5f, 14f, 35f, 14f, 35f)
    cubicTo(14f, 35f, 28f, 24.5f, 28f, 14f)
    cubicTo(28f, 6.268f, 21.732f, 0f, 14f, 0f)
    cubicTo(6.268f, 0f, 0f, 6.268f, 0f, 14f)
    close()
}

private fun pinIcon(density: Density, dir: LayoutDirection) = with(density) {
    val u = .8f.dp.toPx()
    val bg = MapInk.icons.bg
    val accent = MapInk.iconAccent
    icon("kav-pin-${accent.toArgb()}-${bg.toArgb()}", 31.5f * u, 36.75f * u, density, dir) {
        val pin = pinPath()
        withTransform({
            scale(u, u, pivot = Offset.Zero)
            translate(1.75f, 1.75f)
        }) {
            drawPath(pin, bg, style = Stroke(3.5f))
            drawPath(pin, accent)
            drawCircle(Color.White, 5f, Offset(14f, 14f))
        }
    }
}

// A mode on its ring as one image, so marks that overlap stack whole instead of one's ring cutting through another's
// icon. The radii are in dp.
// A vehicle's glow on the Live and line maps, the rider's there too (LiveScreen).
internal const val VEHICLE_HALO_R = 18f

internal fun ringedMark(mode: Mode, fill: Color, outer: Float, inner: Float, density: Density): Pair<String, ImageBitmap> =
    with(density) {
        val o = outer.dp.toPx()
        val bg = MapInk.icons.bg
        val name = "kav-ringed-${mode.name}-${fill.toArgb()}-${bg.toArgb()}-$outer-$inner"
        icon(name, 2 * o, 2 * o, density, LayoutDirection.Ltr) {
            drawCircle(bg, o, Offset(o, o))
            drawCircle(fill, inner.dp.toPx(), Offset(o, o))
            drawModeMark(mode, Offset(o, o), 12.dp.toPx())
        }
    }

// Which way a bus is heading: an arrowhead just outside its ring, drawn pointing north for the map to turn.
internal fun headingMark(fill: Color, outer: Float, density: Density): Pair<String, ImageBitmap> =
    with(density) {
        val o = outer.dp.toPx(); val reach = (outer + 6f).dp.toPx(); val half = 4.dp.toPx()
        val c = reach + 2.dp.toPx()
        val bg = MapInk.icons.bg
        val name = "kav-heading-${fill.toArgb()}-${bg.toArgb()}-$outer"
        icon(name, 2 * c, 2 * c, density, LayoutDirection.Ltr) {
            val tip = Path().apply {
                moveTo(c, c - reach)
                lineTo(c + half, c - o + 1.dp.toPx())
                lineTo(c - half, c - o + 1.dp.toPx())
                close()
            }
            drawPath(tip, bg, style = Stroke(2.dp.toPx()))
            drawPath(tip, fill)
        }
    }

// The rider on a trip's map: their dot, and from it a cone the way the phone faces, which the map turns with itself so it
// points the same way on the ground. Nothing without a fix, and no cone without a compass. Its images: riderImages.
internal fun riderGeometry(at: Pair<Double, Double>?, heading: Float?): MapGeometry {
    val (lat, lon) = at ?: return MapGeometry()
    return MapGeometry(
        halos = listOf(MapDot(lat, lon, MapInk.text.copy(alpha = 0.16f), 15f, id = "me:halo")),
        markers = listOf(MapMarker(lat, lon, riderIcon(cone = heading != null), heading ?: 0f, turns = true, id = "me")),
    )
}

// The rider as navigation draws them: the arrow the way the phone faces, else their dot without a compass. Its id makes it
// glide from fix to fix as a vehicle does (TileMap), not jump.
internal fun riderArrowGeometry(at: Pair<Double, Double>?, heading: Float?, density: Density, alpha: Float = 1f): MapGeometry {
    val (lat, lon) = at ?: return MapGeometry()
    val halo = MapDot(lat, lon, MapInk.text.copy(alpha = 0.16f * alpha), 15f, id = "me:halo")
    return if (heading != null) MapGeometry(halos = listOf(halo),
        markers = listOf(MapMarker(lat, lon, mapArrowIcon(), heading, alpha, turns = true, id = "me")))
    else MapGeometry(halos = listOf(halo), markers = listOf(MapMarker(lat, lon, riderIcon(cone = false), 0f, alpha, id = "me")),
        images = riderImages(density))
}

private fun riderIcon(cone: Boolean) = "kav-me-${if (cone) "cone" else "dot"}-${MapInk.icons.text.toArgb()}-${MapInk.icons.bg.toArgb()}"

internal fun riderImages(density: Density): Map<String, ImageBitmap> = with(density) {
    val ink = MapInk.icons
    val reach = 40.dp.toPx()
    listOf(false, true).associate { cone ->
        icon(riderIcon(cone), 2 * reach, 2 * reach, density, LayoutDirection.Ltr) {
            val at = Offset(reach, reach)
            if (cone) {
                // 70 degrees wide, pointing north for the map to turn, fading out from the dot.
                val wedge = Path().apply {
                    moveTo(reach, reach)
                    arcTo(Rect(at, reach), -125f, 70f, false)
                    close()
                }
                drawPath(wedge, Brush.radialGradient(listOf(ink.text.copy(alpha = 0.4f), ink.text.copy(alpha = 0f)), at, reach))
            }
            drawCircle(ink.bg, 8.dp.toPx(), at)
            drawCircle(ink.text, 5.dp.toPx(), at)
        }
    }
}

// Vehicles as the Live tab draws them. alpha fades one out; track is the road it glides along (TileMap), by default its
// route's where Kav's server has given it.
internal fun vehicleGeometry(
    vehicles: List<Pair<Transit.Arrival, Mode>>,
    density: Density,
    track: (Transit.Arrival) -> List<Pair<Double, Double>>? = { a -> KavLive.routeOf(a.vehicleId)?.let(KavLive::shapeNow) },
    alpha: (Transit.Arrival) -> Float = { 1f },
    // Whether it comes or goes with what the map looks at (MapDot.fade).
    fade: (Transit.Arrival) -> Int = { 0 },
): MapGeometry {
    val halos = ArrayList<MapDot>()
    val markers = ArrayList<MapMarker>()
    val images = HashMap<String, ImageBitmap>()
    val tracks = HashMap<String, List<Pair<Double, Double>>>()
    val now = System.currentTimeMillis() / 1000
    for ((a, mode) in vehicles) {
        val k = alpha(a)
        if (k <= .01f) continue
        val f = fade(a)
        val tint = if (a.vehicleStatus == 2) MapInk.icons.problem else mapFreshColour(freshnessOf(a.sampleUtc, now))
        // The vehicle, the same as it moves, so the map glides it (TileMap).
        val id = "v" + a.vehicleId.ifEmpty { a.tripId.toString() }.replace(':', '.')
        track(a)?.takeIf { it.size >= 2 }?.let { tracks[id] = it }
        halos += MapDot(a.lat, a.lon, tint.copy(alpha = .2f * k), VEHICLE_HALO_R, id = "$id:halo", fade = f)
        val (name, image) = ringedMark(mode, tint, 11f, 9f, density)
        images[name] = image
        markers += MapMarker(a.lat, a.lon, name, alpha = k, id = id, fade = f)
        if (a.bearing >= 0) {
            val (heading, arrow) = headingMark(tint, 11f, density)
            images[heading] = arrow
            markers += MapMarker(a.lat, a.lon, heading, rotation = a.bearing.toFloat(), alpha = k, turns = true, id = "$id:turn", fade = f)
        }
    }
    return MapGeometry(markers = markers, halos = halos, images = images, tracks = tracks)
}

private fun between(a: Pair<Double, Double>, b: Pair<Double, Double>) =
    ((a.first + b.first) / 2) to ((a.second + b.second) / 2)

// A road's stretches in metres and its length up to each point, for the road last asked about: a line's stops are put on
// its road one after another, and measuring the whole road again for each of them takes most of a line page's first frame.
private class Measured(val path: List<Pair<Double, Double>>, val seg: DoubleArray, val walked: DoubleArray)
@Volatile private var measured: Measured? = null

private fun measuredOf(path: List<Pair<Double, Double>>): Measured {
    measured?.let { if (it.path === path) return it }
    val seg = DoubleArray(path.size); val walked = DoubleArray(path.size)
    for (i in 0 until path.lastIndex) {
        seg[i] = metres(path[i].first, path[i].second, path[i + 1].first, path[i + 1].second)
        walked[i + 1] = walked[i] + seg[i]
    }
    return Measured(path, seg, walked).also { measured = it }
}

// alongPath and then pointAlong (Progress.kt), the same sums in the same order, with the road measured once.
internal fun onRoute(at: Pair<Double, Double>, path: List<Pair<Double, Double>>): Pair<Double, Double> =
    if (path.size < 2) at else pointOnRoute(path, alongRoute(at, path))

// alongPath (Progress.kt), the same sums in the same order, with the road measured once for all the places put on it.
internal fun alongRoute(at: Pair<Double, Double>, path: List<Pair<Double, Double>>): Double {
    if (path.size < 2) return 0.0
    val m = measuredOf(path)
    val kx = kotlin.math.cos(Math.toRadians(at.first)) * 111_320.0
    val ky = 110_574.0
    var best = Double.MAX_VALUE; var along = 0.0
    for (i in 0 until path.lastIndex) {
        val ax = (path[i].second - at.second) * kx; val ay = (path[i].first - at.first) * ky
        val bx = (path[i + 1].second - at.second) * kx; val by = (path[i + 1].first - at.first) * ky
        val dx = bx - ax; val dy = by - ay
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val t = if (len == 0.0) 0.0 else (((-ax) * dx + (-ay) * dy) / (len * len)).coerceIn(0.0, 1.0)
        val px = ax + dx * t; val py = ay + dy * t
        val d = kotlin.math.sqrt(px * px + py * py)
        if (d < best) { best = d; along = m.walked[i] + m.seg[i] * t }
    }
    return along
}

// splitPath (Progress.kt) on a road measured once, at a place already measured along it (alongRoute): the road driven
// and the road ahead.
internal fun splitRoute(path: List<Pair<Double, Double>>, along: Double): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
    if (path.size < 2) return path to emptyList()
    val cut = pointOnRoute(path, along)
    val m = measuredOf(path)
    for (i in 0 until path.lastIndex) {
        if (m.walked[i] + m.seg[i] >= along) return (path.subList(0, i + 1) + cut) to (listOf(cut) + path.subList(i + 1, path.size))
    }
    return path to emptyList()
}

// pointAlong (Progress.kt) on a road measured once.
internal fun pointOnRoute(path: List<Pair<Double, Double>>, along: Double): Pair<Double, Double> {
    if (path.size < 2 || along <= 0.0) return path.first()
    val m = measuredOf(path)
    for (i in 0 until path.lastIndex) {
        if (m.walked[i] + m.seg[i] >= along) {
            val t = if (m.seg[i] == 0.0) 0.0 else (along - m.walked[i]) / m.seg[i]
            return (path[i].first + (path[i + 1].first - path[i].first) * t) to
                (path[i].second + (path[i + 1].second - path[i].second) * t)
        }
    }
    return path.last()
}

// me and heading: where the rider is and which way the phone faces (riderGeometry). onShown says whether the map is on
// screen, scrolled into view, for as long as it's there.
@Composable
fun RouteMap(
    trip: Transit.Itinerary, r: Transit.Resolved = Transit.Resolved(), height: Dp = 190.dp, modifier: Modifier = Modifier,
    me: Pair<Double, Double>? = null, heading: Float? = null, onShown: ((Boolean) -> Unit)? = null,
) {
    val legs = trip.legs.filter { it.shape.size >= 2 }
    if (legs.isEmpty()) return
    var inView by remember { mutableStateOf(false) }
    if (onShown != null) DisposableEffect(inView) { onShown(inView); onDispose { onShown(false) } }
    val points = legs.flatMap { it.shape }
    val vehicles = trip.rides.flatMap { it.options }.mapNotNull { o ->
        r.arrival(o)?.takeIf { it.hasLocation }?.let { it to modeOf(r.routeType(r.line(o.lineId)?.agencyId ?: -1)) }
    }.distinctBy { it.first.tripId }

    val rides = legs.filter { it.kind != Transit.LegKind.WALK }
    // Each ride's line grey around it, with its stops before boarding, as navigation draws it (rememberGreyRoad).
    val greys = rides.mapIndexed { i, l ->
        androidx.compose.runtime.key(i) { if (l.kind == Transit.LegKind.RIDE) rememberGreyRoad(l, r.arrival(l), r) else null }
    }.filterNotNull()
    val greyRoads = greys.map { it.road }.filter { it.size >= 2 }
    val greyStops = greys.flatMap { it.stops }
    val tints = routeTints(rides, r, MapInk.light, MapInk.route)
    val marks = remember(trip, tints, r, MapInk.light) { stationMarks(trip.legs, rides, tints, r) }
    val entrance = rememberVectorPainter(Icons.AutoMirrored.Rounded.Login)
    val exit = rememberVectorPainter(Icons.AutoMirrored.Rounded.Logout)
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val geometry = remember(legs, greyRoads, greyStops, r.stops, tints, marks, MapInk.bg, MapInk.text, MapInk.icons, K.accent, dir) {
        val station = tripMarks(marks, legs.last().shape.lastOrNull(), entrance, exit, density, dir)
        MapGeometry(
            lines = greyRoads.map { MapLine(it, MapInk.routeIdle, 3f, casing = 6f) } +
                legs.filter { it.kind == Transit.LegKind.WALK }.map { MapLine(it.shape, MapInk.muted, 2f, dashed = true) } +
                rides.mapIndexed { i, l -> MapLine(l.shape, tints[i], 4f, casing = 7f) },
            // Where the trip starts, and each stop on the way (where the leg on from it starts), ringed alike.
            dots = greyStopDots(greyStops) + boardingMarkers(rides, tints, r) +
                (listOfNotNull(legs.first().shape.firstOrNull()) + legs.filter { it.visit != null }.mapNotNull { it.shape.firstOrNull() })
                    .flatMap { (lat, lon) ->
                        listOf(MapDot(lat, lon, MapInk.bg, 6f), MapDot(lat, lon, Color.Transparent, 5f, MapInk.text, 2f))
                    },
            markers = station.markers,
            images = station.images,
        )
    }
    val moving = remember(vehicles, density, MapInk.icons) {
        vehicleGeometry(vehicles, density)
    }
    val rider = remember(me, heading != null, MapInk.text, MapInk.icons) { riderGeometry(me, heading?.let { 0f }) }
    val riderBitmaps = remember(density, MapInk.icons) { riderImages(density) }
    TileMap(
        points,
        modifier.fillMaxWidth().height(height).panel(K.rTile)
            .then(if (onShown == null) Modifier else Modifier.onGloballyPositioned { inView = it.boundsInWindow().height > 0f }),
        geometry = geometry,
        live = moving.copy(
            halos = moving.halos + rider.halos,
            images = if (me == null) moving.images else moving.images + riderBitmaps,
            // All in one source as they glide, so a glow is never in two at once, drawn twice and brighter.
            together = true,
        ),
        rider = rider.markers.firstOrNull(),
        riderHeading = heading,
    )
}
