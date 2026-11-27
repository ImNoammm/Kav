package uk.noammm.kav.ui

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// A theme's outlines (K.outline, one of Looks.OUTLINES): how cards and controls are edged while a theme sets them. Kav's
// own is a hairline round cards and nothing round controls. Cards are outlined in the separator's color and controls in
// borderStrong's, so a theme sets both with its colors:
//   line    one solid line round each
//   double  two thin lines round cards, as a terminal's boxes are drawn, and one round controls
//   sketch  a line drawn by hand, twice over and never quite closed, the same each time a part is drawn
//   bevel   one line, with a raised edge lit from above inside it, and a gloss on controls
// Everything is drawn inside the part's own shape, so it is clipped as the part is.

// A card's, a sheet's or a floating panel's edge, in its shape.
fun Modifier.cardOutline(shape: Shape): Modifier {
    val style = K.outline ?: return border(0.5.dp, K.separator, shape)
    return outlined(shape, style, K.separator, card = true)
}

// A control's edge (a button, a chip, a segmented control's track, an icon button, a switch), in its shape. Nothing for
// Kav's own.
fun Modifier.controlOutline(shape: Shape = K.capsule): Modifier {
    val style = K.outline ?: return this
    return outlined(shape, style, K.borderStrong, card = false)
}

private fun Modifier.outlined(shape: Shape, style: String, color: Color, card: Boolean): Modifier = drawWithCache {
    val rr = when (val o = shape.createOutline(size, layoutDirection, this)) {
        is Outline.Rounded -> o.roundRect
        is Outline.Rectangle -> RoundRect(o.rect)
        is Outline.Generic -> RoundRect(o.path.getBounds())
    }
    val draw = outlineOf(style, rr, color, card)
    onDrawWithContent {
        draw.behind(this)
        drawContent()
        draw.over(this)
    }
}

// The edge of one part, worked out once for its size: what goes under its content (a control's gloss) and over it.
class OutlineDrawing(val behind: DrawScope.() -> Unit, val over: DrawScope.() -> Unit)

// A style's edge round rr. card: a card's, else a control's.
fun Density.outlineOf(style: String, rr: RoundRect, color: Color, card: Boolean): OutlineDrawing {
    val d = density
    fun stroke(inset: Float, width: Float): DrawScope.() -> Unit {
        val path = Path().apply { addRoundRect(rr.inset(inset)) }
        return { drawPath(path, color, style = Stroke(width)) }
    }
    return when (style) {
        "line" -> OutlineDrawing({}, stroke(.75f * d, 1.5f * d))
        "double" -> {
            val outer = stroke(.5f * d, 1f * d)
            if (!card) OutlineDrawing({}, outer) else {
                val inner = stroke(3.5f * d, 1f * d)
                OutlineDrawing({}, { outer(); inner() })
            }
        }
        "sketch" -> {
            val seed = rr.width.roundToInt() * 31 + rr.height.roundToInt()
            val first = sketch(rr.inset(1.6f * d), seed, 1.1f * d, d)
            val second = sketch(rr.inset(1.9f * d), seed * 7 + 3, .9f * d, d)
            OutlineDrawing({}, {
                drawPath(first, color, style = Stroke(1.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(second, color.copy(alpha = color.alpha * .55f), style = Stroke(1f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
            })
        }
        "bevel" -> {
            val edge = Path().apply { addRoundRect(rr.inset(.5f * d)) }
            val lit = Path().apply { addRoundRect(rr.inset(1.75f * d)) }
            val light = Brush.verticalGradient(
                0f to Color.White.copy(alpha = .85f), .45f to Color.Transparent, .6f to Color.Transparent, 1f to Color.Black.copy(alpha = .22f),
                startY = rr.top, endY = rr.bottom,
            )
            val gloss = Brush.verticalGradient(
                0f to Color.White.copy(alpha = .45f), .5f to Color.White.copy(alpha = 0f), 1f to Color.Black.copy(alpha = .07f),
                startY = rr.top, endY = rr.bottom,
            )
            val body = Path().apply { addRoundRect(rr) }
            OutlineDrawing(
                { if (!card) drawPath(body, gloss) },
                { drawPath(lit, light, style = Stroke(1.5f * d)); drawPath(edge, color, style = Stroke(1f * d)) },
            )
        }
        else -> OutlineDrawing({}, {})
    }
}

// A round rect's outline drawn by hand: points every few dp along it, each pushed off the line by a slow wobble, from a
// place along it the seed picks, round once and a little past where it began, drifting off as a pen does.
internal fun sketch(rr: RoundRect, seed: Int, amplitude: Float, density: Float): Path {
    val measure = PathMeasure().apply { setPath(Path().apply { addRoundRect(rr) }, true) }
    val length = measure.length
    val out = Path()
    if (length <= 0f) return out
    val r = java.util.Random(seed.toLong())
    val p1 = r.nextFloat() * 2 * PI.toFloat()
    val p2 = r.nextFloat() * 2 * PI.toFloat()
    val f1 = 1 + r.nextInt(2)
    val f2 = 3 + r.nextInt(3)
    val start = r.nextFloat() * length
    val drift = (if (r.nextBoolean()) 1 else -1) * amplitude * 1.2f
    val total = length + 10f * density
    val step = 5f * density
    var s = 0f
    while (true) {
        val at = (start + s) % length
        val pos = measure.getPosition(at)
        val tan = measure.getTangent(at)
        val t = s / length
        val off = amplitude * (.6f * sin(2 * PI.toFloat() * f1 * t + p1) + .4f * sin(2 * PI.toFloat() * f2 * t + p2)) + drift * t
        val x = pos.x - tan.y * off
        val y = pos.y + tan.x * off
        if (s == 0f) out.moveTo(x, y) else out.lineTo(x, y)
        if (s >= total) break
        s = minOf(s + step, total)
    }
    return out
}

// A round rect made smaller by i on every side, its corners with it.
private fun RoundRect.inset(i: Float): RoundRect {
    fun c(r: CornerRadius) = CornerRadius((r.x - i).coerceAtLeast(0f), (r.y - i).coerceAtLeast(0f))
    return RoundRect(
        left + i, top + i, right - i, bottom - i,
        c(topLeftCornerRadius), c(topRightCornerRadius), c(bottomRightCornerRadius), c(bottomLeftCornerRadius),
    )
}
