package uk.noammm.kav.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt

// A theme's skin (ext/Looks.kt, Skin) as Kav draws it: each picture read once (ExtLook), and drawn behind the part it
// is for in place of that part's fill. A part the skin leaves out is drawn as it always is.

// One piece: its picture, the edges kept as they are (left, top, right, bottom, in the picture's pixels), dp for each
// pixel, and the color of text and icons on it, or null for Kav's own.
class SkinPiece(val image: ImageBitmap, val insets: IntArray, val scale: Float, val ink: Color?)

class Skin(val pieces: Map<String, SkinPiece>, val background: ImageBitmap?) {
    operator fun get(k: String): SkinPiece? = pieces[k]
}

// The piece stretched over the box from left to right and top to bottom by nine slices: the corners as they are, the
// edges along their length, the middle both ways. Where the box is smaller than two edges, both shrink together. A
// picture drawn larger than its pixels keeps them sharp, so no slice blurs into the next; right to left, it is mirrored.
fun DrawScope.nineSlice(p: SkinPiece, left: Float = 0f, top: Float = 0f, right: Float = size.width, bottom: Float = size.height) {
    val img = p.image
    val s = p.scale * density
    fun edges(a: Float, z: Float, room: Float): Pair<Float, Float> {
        val sum = a + z
        return if (sum <= room || sum == 0f) a to z else a * room / sum to z * room / sum
    }
    val (l, t, r, b) = p.insets
    val (dl, dr) = edges(l * s, r * s, right - left)
    val (dt, db) = edges(t * s, b * s, bottom - top)
    val xs = intArrayOf(0, l, img.width - r, img.width)
    val ys = intArrayOf(0, t, img.height - b, img.height)
    // Rounded once, so the slices meet without a gap.
    val dx = floatArrayOf(left, left + dl, right - dr, right).map { it.roundToInt() }
    val dy = floatArrayOf(top, top + dt, bottom - db, bottom).map { it.roundToInt() }
    val filter = if (s >= 1f) FilterQuality.None else FilterQuality.Low
    scale(if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, 1f, Offset((left + right) / 2, 0f)) {
        for (i in 0..2) for (j in 0..2) {
            val sw = xs[i + 1] - xs[i]; val sh = ys[j + 1] - ys[j]
            val w = dx[i + 1] - dx[i]; val h = dy[j + 1] - dy[j]
            if (sw > 0 && sh > 0 && w > 0 && h > 0)
                drawImage(img, IntOffset(xs[i], ys[j]), IntSize(sw, sh), IntOffset(dx[i], dy[j]), IntSize(w, h), filterQuality = filter)
        }
    }
}

fun Modifier.skin(p: SkinPiece): Modifier = backdrop { nineSlice(p) }

// The page's background picture over the whole box, cropped to fill it from its middle.
fun DrawScope.cover(img: ImageBitmap) {
    val k = maxOf(size.width / img.width, size.height / img.height)
    val w = (size.width / k).roundToInt().coerceIn(1, img.width)
    val h = (size.height / k).roundToInt().coerceIn(1, img.height)
    drawImage(img, IntOffset((img.width - w) / 2, (img.height - h) / 2), IntSize(w, h),
        IntOffset.Zero, IntSize(size.width.roundToInt(), size.height.roundToInt()))
}

// The top of a screen on the skin's topBar, drawn on up behind the status bar; as it is without one.
@Composable
fun Modifier.topBar(): Modifier {
    val p = K.skin?.get("topBar") ?: return this
    val up = WindowInsets.safeDrawing.getTop(LocalDensity.current).toFloat()
    return backdrop { nineSlice(p, top = -up) }
}

// Clipped to the box, except that over a skin's topBar it draws on up behind the status bar too (topBar).
@Composable
fun Modifier.clipUnderTopBar(): Modifier {
    if (K.skin?.get("topBar") == null) return clipToBounds()
    val up = WindowInsets.safeDrawing.getTop(LocalDensity.current).toFloat()
    return drawWithContent { clipRect(top = -up) { this@drawWithContent.drawContent() } }
}

// A title's color on the top of a screen: the topBar's ink, or the style's own.
val topBarInk: Color get() = K.skin?.get("topBar")?.ink ?: Color.Unspecified
