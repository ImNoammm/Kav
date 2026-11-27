package uk.noammm.kav.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack
import uk.noammm.kav.data.MapPacks
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// Home's map: a still picture of the streets around the rider, tilted a little, with no buildings standing up, drawn off
// screen from Kav's own map on the phone (MapSnapshotter): nothing is asked of a server for it. The last one is kept in
// the cache folder, so a start shows it from the first frame; there is none until the map is on the phone and the
// rider's place has been known once.
internal class HomeMapShot(val key: String, val image: ImageBitmap, val rider: Offset?, val station: Offset?)

internal object HomeMap {
    var shot by mutableStateOf<HomeMapShot?>(null)

    // The nearest station Home's card shows (HomeSuggestions), marked on the picture.
    var station by mutableStateOf<Pair<Double, Double>?>(null)

    private fun png(ctx: Context) = File(ctx.cacheDir, "home-map.png")
    private fun about(ctx: Context) = File(ctx.cacheDir, "home-map.txt")

    // Whether Home leaves the map its room: decided from the first frame, so the list never moves when it comes.
    fun shown(ctx: Context, here: Pair<Double, Double>?) = MapPacks.STANDARD.state is MapPack.State.Ready &&
        uk.noammm.kav.hasLocationPermission(ctx) && (here != null || shot != null || png(ctx).exists())

    suspend fun load(ctx: Context) {
        if (shot != null) return
        shot = withContext(Dispatchers.IO) {
            runCatching {
                val lines = about(ctx).readLines()
                val image = BitmapFactory.decodeFile(png(ctx).path)?.asImageBitmap() ?: return@runCatching null
                fun at(s: String?) = s?.split(',')?.takeIf { it.size == 2 }?.let { Offset(it[0].toFloat(), it[1].toFloat()) }
                HomeMapShot(lines[0], image, at(lines.getOrNull(1)), at(lines.getOrNull(2)))
            }.getOrNull()
        } ?: shot
    }

    private suspend fun save(ctx: Context, s: HomeMapShot) = withContext(Dispatchers.IO) {
        runCatching {
            png(ctx).outputStream().use { s.image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            fun line(o: Offset?) = o?.let { "${it.x},${it.y}" }.orEmpty()
            about(ctx).writeText("${s.key}\n${line(s.rider)}\n${line(s.station)}\n")
        }
    }

    // w x h: the picture in dp, scale its pixels to a dp.
    // tone: a theme's colors for the map (K.mapTone).
    suspend fun take(ctx: Context, key: String, here: Pair<Double, Double>, station: Pair<Double, Double>?, look: Look,
        tone: uk.noammm.kav.ext.Looks.MapTone?, rtl: Boolean, w: Int, h: Int, scale: Float): HomeMapShot? {
        uk.noammm.kav.data.GovMapTiles.mapLibre(ctx)
        val map = MapFile.styleJson(ctx, MapFile.Type.STANDARD, look, MapFile.labels, preview = false, tone = tone) ?: return null
        val camera = CameraPosition.Builder().target(LatLng(here.first, here.second)).zoom(15.5).tilt(40.0).bearing(0.0).build()
        val (image, at) = snapshot(ctx, { named -> flat(RouteShots.labelled(map, rtl, named)) }, camera, w, h, scale, listOfNotNull(here, station))
            ?: return null
        val shot = HomeMapShot(key, image, at.getOrNull(0), station?.let { at.getOrNull(1) })
        save(ctx, shot)
        return shot
    }

    // The style without buildings standing up (Settings' 3D buildings is for the map itself), and without the names of
    // towns and neighborhoods, which stood over the rider's dot.
    private fun flat(style: String): String {
        val json = org.json.JSONObject(style)
        val layers = json.getJSONArray("layers")
        val kept = org.json.JSONArray()
        for (i in 0 until layers.length()) layers.getJSONObject(i).let {
            if (it.optString("type") != "fill-extrusion" && it.optString("source-layer") != "places") kept.put(it)
        }
        return json.put("layers", kept).toString()
    }
}

// The picture, height tall and as wide as the screen, fading into the page at its foot, with the rider and the nearest
// station marked where they are on it. Drawn again (and kept) when the rider moves, the station changes or the look does.
@Composable
internal fun HomeMapPicture(here: Pair<Double, Double>?, height: Dp, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val width = LocalConfiguration.current.screenWidthDp
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val look = K.look
    val tone = K.mapTone
    val station = HomeMap.station
    LaunchedEffect(Unit) { HomeMap.load(ctx) }
    if (here != null) {
        fun r(v: Double) = (v * 10_000).roundToInt()
        val key = "2|${r(here.first)},${r(here.second)}|${station?.let { "${r(it.first)},${r(it.second)}" }}|$look|$tone|${MapFile.labels}|$rtl|" +
            "${width}x${height.value.roundToInt()}|$density"
        LaunchedEffect(key) {
            if (HomeMap.shot?.key == key) return@LaunchedEffect
            // The station is worked out a moment after the place: one picture for both.
            delay(300)
            HomeMap.take(ctx, key, here, station, look, tone, rtl, width, height.value.roundToInt(), density)?.let { HomeMap.shot = it }
        }
    }
    Crossfade(HomeMap.shot, modifier, animationSpec = tween(400), label = "homeMap") { shot ->
        Box(Modifier.fillMaxSize()) {
            if (shot == null) return@Box
            Image(shot.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            shot.station?.let { at ->
                Box(
                    Modifier.offset { IntOffset((at.x.dp - 14.dp).roundToPx(), (at.y.dp - 14.dp).roundToPx()) }.size(28.dp)
                        .clip(CircleShape).background(K.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.DirectionsBus, contentDescription = null, tint = K.onAccent, modifier = Modifier.size(18.dp)) }
            }
            // The rider as Kav's maps draw them: a halo, and a dot in the text's colour ringed in the page's.
            shot.rider?.let { at ->
                Box(Modifier.offset { IntOffset((at.x.dp - 15.dp).roundToPx(), (at.y.dp - 15.dp).roundToPx()) }.size(30.dp)
                    .clip(CircleShape).background(MapInk.text.copy(alpha = .16f)))
                Box(Modifier.offset { IntOffset((at.x.dp - 7.dp).roundToPx(), (at.y.dp - 7.dp).roundToPx()) }.size(14.dp)
                    .clip(CircleShape).background(MapInk.text).border(2.dp, MapInk.bg, CircleShape))
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to K.bg.copy(alpha = 0f), 1f to K.bg)))
        }
    }
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
