package uk.noammm.kav.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import uk.noammm.kav.Loaded
import uk.noammm.kav.Prefs
import uk.noammm.kav.R
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.MapPacks
import uk.noammm.kav.data.ShareStyle
import uk.noammm.kav.data.Transit
import java.io.ByteArrayOutputStream
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.tan

// A trip's sharing. Its card, the card's map and the picture chats show with Kav's link are made on the phone while the
// trip's page is open, a moment after it opens, so the Share sheet opens on them done. Its links are made only once Share
// is tapped: Kav's keeps the trip on Kav's server, and EGG's and Bus Nearby's ask HopOn for the trip and register it
// there, as their own apps do only for a trip shared. What's made is kept for the session (ShareLinks, RouteShots).
@Stable
internal class SharePrep(
    val trip: Transit.Itinerary, val fromLabel: String, val toLabel: String, val style: ShareStyle, val card: CardLook,
    map: Boolean,
) {
    val kavKey = ShareLinks.key(trip, fromLabel, toLabel, style)
    var kav by mutableStateOf(ShareLinks.made(kavKey)?.let { Result.success(it) })
    var kavAttempt by mutableIntStateOf(0)
    var started by mutableStateOf(false)
    // Share was tapped: the sheet is open, so its links are made, and its card without waiting for the page's moment.
    var wanted by mutableStateOf(false)
    // The card's map: where the link's page shows one and the map is on the phone, drawn once the picture's map is
    // measured; where it can't be drawn the card goes on without it.
    var mapShown by mutableStateOf(map)
    var mapSize by mutableStateOf<IntSize?>(null)
    var shot by mutableStateOf<RouteShot?>(null)
    // The picture is the card zoomed out, its words smaller in the same 1200 x 630, as far as it takes for the map to keep
    // a fair share of it where a long strip wraps onto another row.
    var zoom by mutableFloatStateOf(1f)
    val picture = CompletableDeferred<ByteArray?>()

    // Kav's link once more, after it couldn't be made.
    fun again() { kav = null; kavAttempt++ }
}

// fresh: anything that changes when the Link customization may have, so it's read again (the sheet opening).
@Composable
internal fun rememberSharePrep(
    trip: Transit.Itinerary, r: Transit.Resolved, fromLabel: String, toLabel: String, active: Boolean, fresh: Any,
): SharePrep {
    val ctx = LocalContext.current
    val style = remember(fresh) { Prefs.linkStyle(ctx) }
    val prep = remember(trip, fromLabel, toLabel, style) {
        SharePrep(trip, fromLabel, toLabel, style, CardLook.of(ctx, trip, fromLabel, toLabel, style),
            style.map && MapPacks.STANDARD.state is MapPack.State.Ready && trip.legs.any { it.shape.size >= 2 })
    }
    val resolved by rememberUpdatedState(r)
    LaunchedEffect(prep, active, prep.wanted) {
        if (prep.started) return@LaunchedEffect
        if (!prep.wanted) {
            if (!active) return@LaunchedEffect
            delay(PREP_DELAY_MS)
        }
        prep.started = true
    }
    LaunchedEffect(prep, prep.started, prep.wanted, prep.kavAttempt) {
        if (!prep.started || !prep.wanted || prep.kav?.isSuccess == true) return@LaunchedEffect
        prep.kav = try {
            // Kav's link goes with its picture, unless that takes too long.
            Result.success(shareText(ctx, trip, resolved, fromLabel, toLabel, card = { withTimeoutOrNull(CARD_WAIT_MS) { prep.picture.await() } })
                .also { ShareLinks.keep(prep.kavKey, it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    LaunchedEffect(prep, prep.mapSize) {
        val size = prep.mapSize ?: return@LaunchedEffect
        // Once the picture's layout has settled, so a size it passes through isn't drawn for nothing.
        withFrameNanos {}
        val taken = RouteShots.take(ctx, trip, resolved, prep.card, size, CARD_SCALE / prep.zoom)
        if (taken == null) prep.mapShown = false else prep.shot = taken
    }
    return prep
}

// The picture of a prepared share (SharePrep), drawn off screen where the trip's page is, once its preparing has begun.
@Composable
internal fun SharePicture(prep: SharePrep, r: Transit.Resolved) {
    if (!prep.started) return
    CardPicture(prep.zoom, ready = !prep.mapShown || prep.shot != null, onTaken = { prep.picture.complete(it) }) {
        ShareCard(prep.trip, r, prep.card, prep.shot, prep.mapShown, Modifier.fillMaxSize(), onMapSize = { size ->
            if (size.height < CARD_MAP_SHARE * CARD_HEIGHT * CARD_SCALE && prep.zoom < 1.6f) prep.zoom += .1f else prep.mapSize = size
        })
    }
}

// A trip shared the way a music app shares a song: its card first, as the link's page and a chat's preview of the link
// show it, then where to send it. The card was made while the trip's page was open (SharePrep); the link is made as the
// sheet opens, and its line fills in once it's there: a tap that comes before it is kept, and sent the moment it is. A
// link that can't be made says so, with a way to try again. More is Android's own chooser.
@Composable
internal fun ShareSheet(prep: SharePrep, r: Transit.Resolved, onDismiss: () -> Unit) {
    LaunchedEffect(prep) { prep.wanted = true }
    val made = prep.kav
    val failed = made?.isFailure == true
    BottomSheet(onDismiss) { close ->
        Column(
            Modifier.fillMaxWidth().padding(horizontal = K.gap4).padding(bottom = K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            ShareCard(prep.trip, r, prep.card, prep.shot, prep.mapShown, Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)),
                mapAspect = prep.mapSize?.let { it.width / it.height.toFloat() } ?: 4f)
            androidx.compose.animation.AnimatedVisibility(
                failed,
                enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(spring(dampingRatio = 1f, stiffness = 500f)),
                exit = fadeOut(tween(150)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
                    Text(T("Couldn't make the link.", "לא הצלחנו ליצור את הקישור."), style = K.type.subhead,
                        color = K.critical, modifier = Modifier.weight(1f))
                    KavButton(T("Try again", "נסו שוב"), { prep.again() }, style = ButtonStyle.Secondary)
                }
            }
            // Sent, the sheet closes and is gone, so Share opens it again and Back goes on as before.
            ShareTargets(made?.getOrNull(), failed) { send -> close { send(); onDismiss() } }
        }
    }
}

// A trip's messages with their links as made, by the trip, its ends' names and the page's choices, for as long as Kav is
// open.
private object ShareLinks {
    private val made = HashMap<String, String>()
    fun key(trip: Transit.Itinerary, from: String, to: String, style: ShareStyle) =
        "${trip.guid}|${trip.dep}|$from|$to|${style.json()}"
    fun made(key: String): String? = synchronized(made) { made[key] }
    fun keep(key: String, text: String) = synchronized(made) { made[key] = text }
}

// A shared trip's card as chats show it with Kav's link: 400 x 210 dp at three pixels to the dp, the 1200 x 630 the link's
// page names for its picture, and no larger a JPEG than Kav's server takes.
private const val CARD_WIDTH = 400
private const val CARD_HEIGHT = 210
private const val CARD_SCALE = 3f
private const val CARD_BYTES = 300 * 1024
private const val CARD_WAIT_MS = 6_000L
// How long the trip's page shows before its card is made ready: a glance at a trip and back draws nothing.
private const val PREP_DELAY_MS = 1_500L
// The least of the picture's height its map keeps.
private const val CARD_MAP_SHARE = .42f
// The link's page's accent, the same in every look: its pin at the trip's end.
private val PAGE_ACCENT = Color(0xFFBF5AF2)

// The card as the link's page has the trip: its look and language (Automatic as when the link is made, shareText), and
// the names its ends go by there, in the page's stop-name language.
internal class CardLook(val look: Look, val palette: Palette, val rtl: Boolean, val title: String, val times: String) {
    companion object {
        fun of(ctx: Context, trip: Transit.Itinerary, fromLabel: String, toLabel: String, style: ShareStyle): CardLook {
            val page = style.resolved(Prefs.lang(ctx).code, Prefs.stopNamesEnglish(ctx), Prefs.look(ctx).name.lowercase(Locale.US))
            val look = when (page.theme) { "light" -> Look.LIGHT; "dark" -> Look.DARK; else -> Look.OLED }
            val he = page.language == "he"
            val english = page.stopLanguage == "en"
            val net = Loaded.net
            val (from, to) = sharedNames(trip, fromLabel, toLabel, Prefs.favourites(ctx))
                .let { (a, b) -> (net?.shown(a, english) ?: a) to (net?.shown(b, english) ?: b) }
            val hm = clockFormat()
            val leave = hm.format(Date(trip.dep * 1000))
            val arrive = hm.format(Date(trip.arr * 1000))
            return CardLook(
                look, palette(look), he,
                if (he) "\u2068$from\u2069 ← \u2068$to\u2069" else "\u2068$from\u2069 → \u2068$to\u2069",
                if (he) "יציאה $leave · הגעה $arrive" else "Leave $leave · arrive $arrive",
            )
        }
    }
}

// The trip as its link's page and a chat's preview of the link show it, in the link's look and language: the map of its
// way (where the page shows one), from and to, its steps, and when. onMapSize: the picture's own (CardPicture), at its
// fixed size, which says how large its map is for the map to be drawn to; it changes at once, as a picture can't fade.
@Composable
private fun ShareCard(
    trip: Transit.Itinerary, r: Transit.Resolved, card: CardLook, shot: RouteShot?, map: Boolean, modifier: Modifier,
    mapAspect: Float = 4f, onMapSize: ((IntSize) -> Unit)? = null,
) {
    val p = card.palette
    val picture = onMapSize != null
    CompositionLocalProvider(
        LocalLook provides p,
        LocalLayoutDirection provides if (card.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        Column(modifier.background(p.surface)) {
            @Composable
            fun MapBox(boxModifier: Modifier) {
                Box(boxModifier.background(p.bg)) {
                    if (picture) shot?.let { RouteImage(it, p) }
                    else Crossfade(shot, animationSpec = tween(250), label = "cardMap") { s -> if (s != null) RouteImage(s, p) }
                    // On a veil of the page's colour, so the way under it doesn't run through the words.
                    Text(MapFile.credit(MapFile.Type.STANDARD),
                        Modifier.align(Alignment.BottomEnd).padding(3.dp).background(p.bg.copy(alpha = .7f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        fontSize = 8.sp, lineHeight = 10.sp, color = p.textSecondary)
                }
            }
            if (picture) {
                if (map) MapBox(Modifier.fillMaxWidth().weight(1f).onSizeChanged { onMapSize?.invoke(it) })
            } else AnimatedVisibility(map, enter = fadeIn(tween(200)) + expandVertically(), exit = fadeOut(tween(150)) + shrinkVertically()) {
                MapBox(Modifier.fillMaxWidth().aspectRatio(mapAspect))
            }
            Column(
                Modifier.fillMaxWidth().then(if (picture && !map) Modifier.weight(1f) else Modifier)
                    .padding(horizontal = K.gap4, vertical = K.gap3),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Text(card.title, style = K.type.headline, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                RouteStrip(trip, r)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.times, style = K.type.subhead.tabular, color = p.textSecondary, modifier = Modifier.weight(1f))
                    KavMark(p.text)
                }
            }
        }
    }
}

// Kav's icon and name, small, as the link's page signs itself.
@Composable
private fun KavMark(ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize().scale(1.5f))
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize().scale(1.5f))
        }
        Text("Kav", style = K.type.subhead, color = ink)
    }
}

// The card's map, drawn off screen in the link's look (MapSnapshotter), with where the trip's ways and marks fall in its
// pixels for the card to draw them on as the trip's own map does (RouteMap): walks dotted, rides on their casing, rings
// where a ride is boarded and left, a ring at the start and the pin at the end.
internal class RouteShot(
    val image: ImageBitmap,
    // Its pixels to the map's dp.
    val scale: Float,
    val ways: List<Way>,
    val dots: List<Pair<MapDot, Offset>>,
    val end: Offset,
) {
    class Way(val points: List<Offset>, val colour: Color, val walk: Boolean)
}

private object RouteShots {
    // The few last drawn, so opening the sheet again for the same trip doesn't draw its map again.
    private val kept = object : LinkedHashMap<String, RouteShot>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RouteShot>?) = size > 4
    }

    // size: the picture's map in its pixels, scale of them to its dp. Null where it can't be drawn.
    suspend fun take(ctx: Context, trip: Transit.Itinerary, r: Transit.Resolved, card: CardLook, size: IntSize, scale: Float): RouteShot? {
        val key = "${trip.guid}|${trip.dep}|${card.look}|${card.rtl}|${size.width}x${size.height}|$scale"
        synchronized(kept) { kept[key] }?.let { return it }
        val legs = trip.legs.filter { it.shape.size >= 2 }
        if (legs.isEmpty() || size.width <= 0 || size.height <= 0) return null
        val rides = legs.filter { it.kind != Transit.LegKind.WALK }
        val tints = routeTints(rides, r, card.look == Look.LIGHT)
        val p = card.palette
        val start = legs.first().shape.first()
        // The rings where rides are boarded and left are the trip map's own, on the card's page colour.
        val dots = boardingMarkers(rides, tints, r).map { if (it.fill == MapInk.bg) it.copy(fill = p.bg) else it } + listOf(
            MapDot(start.first, start.second, p.bg, 6f),
            MapDot(start.first, start.second, Color.Transparent, 5f, p.text, 2f),
        )
        val w = (size.width / scale).roundToInt()
        val h = (size.height / scale).roundToInt()
        val camera = fit(legs.flatMap { it.shape }, w, h) ?: return null
        uk.noammm.kav.data.GovMapTiles.mapLibre(ctx)
        // Kav's own map from the phone, whatever map the screens show: a picture made with nothing asked of a server.
        val map = MapFile.styleJson(ctx, MapFile.Type.STANDARD, card.look, MapFile.labels, preview = false) ?: return null
        // With its places' names where it can be drawn with them, else without (labelled).
        for (named in listOf(true, false)) {
            val options = MapSnapshotter.Options(w, h)
                .withPixelRatio(scale)
                .withStyleBuilder(Style.Builder().fromJson(labelled(map, card.rtl, named)))
                .withCameraPosition(camera)
                .withLogo(false)
                .withAttribution(false)
            val shot = withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<RouteShot?> { done ->
                    val snapshotter = MapSnapshotter(ctx, options)
                    done.invokeOnCancellation { snapshotter.cancel() }
                    snapshotter.start({ s ->
                        fun at(lat: Double, lon: Double) = s.pixelForLatLng(LatLng(lat, lon)).let { Offset(it.x, it.y) }
                        var ride = 0
                        val ways = legs.map { leg ->
                            val walk = leg.kind == Transit.LegKind.WALK
                            RouteShot.Way(leg.shape.map { at(it.first, it.second) }, if (walk) p.textSecondary else tints[ride++], walk)
                        }
                        val end = legs.last().shape.last()
                        done.resume(RouteShot(s.bitmap.asImageBitmap(), scale, ways, dots.map { it to at(it.lat, it.lon) }, at(end.first, end.second)))
                    }, { done.resume(null) })
                }
            } ?: continue
            synchronized(kept) { kept[key] = shot }
            return shot
        }
        return null
    }

    // The map's style with its places named in the page's language, else in the other one, in letters Kav's map fonts
    // have; or with no names at all. A still picture fails whole on a single letter the fonts don't have (a place named
    // in another script too), where the map on screen just leaves that letter out. Road numbers stay off it: their
    // plates are images the app adds, which a still picture made from a style can't take (MapSnapshotter adds them
    // before it's ready and crashes).
    private fun labelled(style: String, rtl: Boolean, named: Boolean): String {
        val json = org.json.JSONObject(style)
        val layers = json.getJSONArray("layers")
        val kept = org.json.JSONArray()
        val name = org.json.JSONArray().put("coalesce")
        for (key in if (rtl) listOf("name:he", "name:en") else listOf("name:en", "name:he")) name.put(org.json.JSONArray().put("get").put(key))
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.optString("type") == "symbol") {
                if (!named || layer.optString("id") == "kav_road_shields") continue
                val layout = layer.optJSONObject("layout")
                if (layout != null && "\"name" in layout.opt("text-field")?.toString().orEmpty()) layout.put("text-field", name)
            }
            kept.put(layer)
        }
        return json.put("layers", kept).toString()
    }

    // The camera that shows every point inside a w x h map, with room for the end's pin above its point, no closer than a
    // street's view, as the trip's own map frames it.
    private fun fit(points: List<Pair<Double, Double>>, w: Int, h: Int): CameraPosition? {
        val good = points.filter { it.first.isFinite() && it.second.isFinite() && kotlin.math.abs(it.first) < 85 }
        if (good.isEmpty()) return null
        fun x(lon: Double) = (lon + 180) / 360
        fun y(lat: Double) = Math.toRadians(lat).let { (1 - ln(tan(it) + 1 / kotlin.math.cos(it)) / PI) / 2 }
        val x0 = good.minOf { x(it.second) }; val x1 = good.maxOf { x(it.second) }
        val y0 = good.minOf { y(it.first) }; val y1 = good.maxOf { y(it.first) }
        // Room for the pin (28 dp tall) above the end, and a little on the other sides, a smaller share of a smaller map.
        val (left, top, right, bottom) = listOf(minOf(20.0, w * .08), minOf(34.0, h * .3), minOf(20.0, w * .08), minOf(12.0, h * .1))
        val room = (w - left - right) to (h - top - bottom)
        // The world is 512 points across at zoom 0.
        val zoom = minOf(
            log2(room.first / (maxOf(x1 - x0, 1e-9) * 512)), log2(room.second / (maxOf(y1 - y0, 1e-9) * 512)), 15.5,
        )
        val world = 512 * Math.pow(2.0, zoom)
        // The points' middle sits in the middle of the room left by the margins, which is off the map's own middle.
        val cx = (x0 + x1) / 2 - (left - right) / 2 / world
        val cy = (y0 + y1) / 2 - (top - bottom) / 2 / world
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * cy))))
        return CameraPosition.Builder().target(LatLng(lat, cx * 360 - 180)).zoom(zoom).build()
    }
}

// The card's map with the trip on it, filling its box as a photo does: the picture's box is the map's own size, the
// sheet's about the same.
@Composable
private fun RouteImage(shot: RouteShot, p: Palette) {
    Canvas(Modifier.fillMaxSize()) {
        val iw = shot.image.width.toFloat()
        val ih = shot.image.height.toFloat()
        val s = maxOf(size.width / iw, size.height / ih)
        val dx = (size.width - iw * s) / 2
        val dy = (size.height - ih * s) / 2
        drawImage(
            shot.image, dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
            dstSize = IntSize((iw * s).roundToInt(), (ih * s).roundToInt()), filterQuality = FilterQuality.High,
        )
        // The map's dp, in this drawing's pixels.
        val u = s * shot.scale
        fun on(o: Offset) = Offset(dx + o.x * s, dy + o.y * s)
        fun path(points: List<Offset>) = Path().apply {
            points.forEachIndexed { i, o -> on(o).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
        }
        val round = StrokeCap.Round
        for (way in shot.ways.filter { it.walk }) drawPath(
            path(way.points), way.colour,
            style = Stroke(2 * u, cap = round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * u, 5 * u))),
        )
        val rides = shot.ways.filter { !it.walk }
        for (way in rides) drawPath(path(way.points), p.bg, style = Stroke(7 * u, cap = round, join = StrokeJoin.Round))
        for (way in rides) drawPath(path(way.points), way.colour, style = Stroke(4 * u, cap = round, join = StrokeJoin.Round))
        for ((dot, at) in shot.dots) {
            val c = on(at)
            if (dot.fill.alpha > 0f) drawCircle(dot.fill, dot.radius * u, c)
            // A dot's ring is drawn outside its radius, as the map draws one.
            if (dot.strokeWidth > 0f) drawCircle(dot.stroke, (dot.radius + dot.strokeWidth / 2) * u, c, style = Stroke(dot.strokeWidth * u))
        }
        // The pin is drawn at four fifths of a dp to its unit, its point on the trip's end, as the trip map's (pinIcon).
        val k = .8f * u
        val end = on(shot.end)
        withTransform({ translate(end.x - 14f * k, end.y - 35f * k); scale(k, k, pivot = Offset.Zero) }) {
            val pin = pinPath()
            drawPath(pin, p.bg, style = Stroke(3.5f))
            drawPath(pin, PAGE_ACCENT)
            drawCircle(Color.White, 5f, Offset(14f, 14f))
        }
    }
}

// The card as a chat shows it with the link: drawn once, out of sight, at the picture's size and Kav's own text size (or
// zoomed out, smaller), into a JPEG. It's placed far off screen with nothing to read out, so it's never seen, touched or
// heard. ready: its map is drawn, or there's none.
@Composable
private fun CardPicture(zoom: Float, ready: Boolean, onTaken: (ByteArray?) -> Unit, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    val w = (CARD_WIDTH * CARD_SCALE).roundToInt()
    val h = (CARD_HEIGHT * CARD_SCALE).roundToInt()
    Box(
        Modifier.clearAndSetSemantics {}
            .layout { measurable, _ ->
                val placed = measurable.measure(Constraints.fixed(w, h))
                layout(0, 0) { placed.place(-4 * w, 0) }
            }
            .drawWithContent { layer.record { this@drawWithContent.drawContent() } },
    ) {
        CompositionLocalProvider(LocalDensity provides Density(CARD_SCALE / zoom, 1f)) { content() }
    }
    val taken by rememberUpdatedState(onTaken)
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        // The next frames draw the finished card into the layer.
        withFrameNanos {}
        withFrameNanos {}
        val image = runCatching { layer.toImageBitmap() }.getOrNull()
        taken(image?.let { withContext(Dispatchers.Default) { runCatching { jpeg(it.asAndroidBitmap()) }.getOrNull() } })
    }
}

// The picture as a JPEG no larger than Kav's server takes, a little softer where it has to be.
private fun jpeg(picture: Bitmap): ByteArray? {
    val plain = if (picture.config == Bitmap.Config.HARDWARE) picture.copy(Bitmap.Config.ARGB_8888, false) else picture
    for (quality in listOf(88, 76, 62)) {
        val out = ByteArrayOutputStream()
        plain.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (out.size() <= CARD_BYTES) return out.toByteArray()
    }
    return null
}

// A way to send the trip: an app's icon (or a glyph) and its name.
private class Target(val name: String, val icon: ImageBitmap?, val glyph: androidx.compose.ui.graphics.vector.ImageVector?, val send: (Context, String) -> Unit)

// The apps worth a tap of their own, in this order, where they're installed and take text.
private val APPS = listOf(
    "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
    "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.facebook.orca",
)

// Copy link first, then the installed apps, then More, always ready to tap. A tap that comes before the link is made is
// kept, its target held a little pressed, and goes the moment the link is; a link that can't be made lets it go, and a
// tap after that does nothing.
@Composable
private fun ShareTargets(text: String?, failed: Boolean, close: (send: () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember {
        val pm = ctx.packageManager
        APPS.mapNotNull { pkg ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg)
            val info = pm.resolveActivity(send, 0) ?: return@mapNotNull null
            val icon = runCatching { info.loadIcon(pm).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            Target(info.loadLabel(pm).toString(), icon, null) { c, t ->
                c.startActivity(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(pkg)
                    .putExtra(Intent.EXTRA_TEXT, t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    // The target tapped before the link was made, by name, and what it does with the link.
    var waiting by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    LaunchedEffect(text, waiting) { text?.let { t -> waiting?.let { (_, send) -> waiting = null; send(t) } } }
    LaunchedEffect(failed) { if (failed) waiting = null }
    fun tap(name: String, send: (String) -> Unit) { if (text != null) send(text) else if (!failed) waiting = name to send }
    val copy = T("Copy link", "העתקת קישור")
    val more = T("More", "עוד")
    Column(verticalArrangement = Arrangement.spacedBy(K.gap2)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            TargetButton(copy, null, if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, waiting?.first == copy,
                tint = if (copied) K.realtime else K.text) {
                tap(copy) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kav", it))
                    copied = true
                }
            }
            apps.forEach { a -> TargetButton(a.name, a.icon, null, waiting?.first == a.name) { tap(a.name) { t -> close { a.send(ctx, t) } } } }
            TargetButton(more, null, Icons.Rounded.MoreHoriz, waiting?.first == more) {
                tap(more) { t ->
                    close {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t)
                        ctx.startActivity(Intent.createChooser(send, T("Share trip", "שיתוף נסיעה")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
        }
    }
}

// held: tapped before the link was made, and waiting for it.
@Composable
private fun TargetButton(
    name: String, icon: ImageBitmap?, glyph: androidx.compose.ui.graphics.vector.ImageVector?, held: Boolean,
    tint: androidx.compose.ui.graphics.Color = K.text, onClick: () -> Unit,
) {
    val ink by animateColorAsState(tint, tween(200), label = "targetInk")
    val press by animateFloatAsState(if (held) .9f else 1f, spring(dampingRatio = .7f, stiffness = 500f), label = "targetHeld")
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(K.rTile))
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = K.gap1),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(52.dp).graphicsLayer { scaleX = press; scaleY = press }, contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon, null, Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)))
            else Box(Modifier.size(52.dp).clip(RoundedCornerShape(26.dp)).background(K.fill), contentAlignment = Alignment.Center) {
                androidx.compose.animation.Crossfade(glyph, animationSpec = tween(200), label = "targetGlyph") { g ->
                    if (g != null) Icon(g, null, tint = ink, modifier = Modifier.size(22.dp))
                }
            }
        }
        Text(name, style = K.type.caption.copy(fontSize = 11.sp), color = K.textSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
