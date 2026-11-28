package uk.noammm.kav.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Each map draws on its own surface (a SurfaceView) instead of through Kav's window (a TextureView). On Noam's phone at
// 120 Hz a pinch showed 119.6 map frames a second with none over 25 ms apart this way, against 104.5 and 6 gaps through
// the window. Its surface sits under the window, seen through a hole the map punches in it, so none of Compose's
// alpha, glass or pictures of the screen see the map itself: what follows keeps the look the same on top of that.
internal const val MAP_ON_SURFACE = true

// Whether a page is fading or sliding with a transition (a tab, a page opening or closing), read as it's drawn. A map on
// its own surface can't fade with its page: its hole goes through a fading page at full strength. So while any of these
// says so, every map shows a still of itself drawn by Compose instead, which fades and slides with its page like the rest.
internal object MapVeil {
    val sources = mutableStateListOf<() -> Boolean>()
    val on: Boolean get() = sources.any { it() }
}

// One transition the maps wait for, for as long as it's in the composition.
@Composable
internal fun VeilMaps(running: () -> Boolean) {
    if (!MAP_ON_SURFACE) return
    val now by rememberUpdatedState(running)
    DisposableEffect(Unit) {
        val source = { now() }
        MapVeil.sources += source
        onDispose { MapVeil.sources -= source }
    }
}

// In the content of an AnimatedContent or an AnimatedVisibility: while it comes or goes.
@Composable
internal fun AnimatedVisibilityScope.VeilMaps() {
    val t = transition
    VeilMaps { t.currentState != EnterExitState.Visible || t.targetState != EnterExitState.Visible }
}

// A map's last still frame and where it is, for what draws Kav's own layer where the map's hole is (the glass over a
// map, the theme's fade): image while the map rests, its plain ground colour while it moves.
internal class MapStill {
    var image by mutableStateOf<ImageBitmap?>(null)
    var at by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
    var tint by mutableStateOf(Color.Transparent)
    // The two pictures it takes turns copying into, so the one shown isn't the one being written.
    val spare = arrayOfNulls<Bitmap>(2)
    var turn = 0
}

internal object MapStills {
    // The maps on their own surfaces seen now (started), in root coordinates.
    val shown = mutableStateListOf<MapStill>()

    // Home's map's last still as Home closed, with its ground and the place it was centered on. A page opened over Home
    // ends its map, and the Home that comes back makes a new one: it stands on this until it has drawn again
    // (TileMap's carryStill), instead of on plain ground and then "Loading the map" for most of a second.
    private var home: Triple<ImageBitmap, Color, Pair<Double, Double>>? = null

    fun keepHome(image: ImageBitmap?, tint: Color, at: Pair<Double, Double>) { home = image?.let { Triple(it, tint, at) } }

    // Kept only while it's still a picture of what Home would show: the same map colors, the rider not 50 m away.
    fun homeAt(at: Pair<Double, Double>): ImageBitmap? = home?.takeIf { (_, tint, was) ->
        tint == MapInk.bg && metres(was.first, was.second, at.first, at.second) < 50
    }?.first

    fun dropHome() { home = null }

    // Whether a box at root position at, size size, lies over any map on its own surface in front now.
    fun over(at: Offset, size: androidx.compose.ui.geometry.Size): Boolean = shown.any { s ->
        s.size.width > 0 && s.size.height > 0 && at.x < s.at.x + s.size.width && at.x + size.width > s.at.x &&
            at.y < s.at.y + s.size.height && at.y + size.height > s.at.y
    }

    // Fills what's left transparent under what's drawn already, in the coordinates of a layer placed at origin in the
    // root: a map's hole in a copy of Kav's layer is filled with that map's still, and nothing else is touched.
    fun DrawScope.drawUnder(origin: Offset) {
        for (s in shown) {
            if (s.size.width <= 0 || s.size.height <= 0) continue
            val at = s.at - origin
            val image = s.image
            if (image != null) drawImage(image, dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()), dstSize = s.size,
                blendMode = BlendMode.DstOver)
            else drawRect(s.tint, at, androidx.compose.ui.geometry.Size(s.size.width.toFloat(), s.size.height.toFloat()),
                blendMode = BlendMode.DstOver)
        }
    }
}

// About a megapixel at most: a whole screen's map at a little under full size, a map card at its own.
private const val STILL_PIXELS = 1_000_000f

internal fun View.findSurface(): SurfaceView? {
    if (this is SurfaceView) return this
    if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).findSurface()?.let { return it }
    return null
}

// The top w × h of the map's surface, copied off the GPU into one of the still's two pictures; null if it couldn't be.
internal suspend fun MapStill.take(surface: SurfaceView, w: Int, h: Int): ImageBitmap? {
    if (w <= 0 || h <= 0 || !surface.holder.surface.isValid) return null
    val k = minOf(1f, sqrt(STILL_PIXELS / (w.toFloat() * h)))
    val bw = (w * k).roundToInt().coerceAtLeast(1)
    val bh = (h * k).roundToInt().coerceAtLeast(1)
    turn = 1 - turn
    val into = spare[turn]?.takeIf { it.width == bw && it.height == bh }
        ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { spare[turn] = it }
    val ok = kotlinx.coroutines.suspendCancellableCoroutine { c ->
        try {
            PixelCopy.request(surface, android.graphics.Rect(0, 0, w, h), into, { r -> c.resume(r == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()))
        } catch (_: IllegalArgumentException) { c.resume(false) }
    }
    return if (ok) into.asImageBitmap() else null
}
