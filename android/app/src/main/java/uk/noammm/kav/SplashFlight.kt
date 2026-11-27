package uk.noammm.kav

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// The launch screen's exit (Android 12 and newer): the arrow tilts and swoops off along a curve, with a short trail in
// the rider's accent color behind it, as the launch screen's color (LaunchLook) fades and Kav shows through. Drawn here, in a view added to the
// SplashScreenView, because Android's own icon is a separate surface clipped to the icon's mask: it can't leave it.
// It takes over from that icon in the pose splash_icon.xml has reached, `played` ms into it, so nothing jumps. It
// never takes a touch: the screen under it is usable all along.
@SuppressLint("ViewConstructor")
class SplashFlight(context: Context, private val icon: View, played: Long, private val accent: Int, ink: Int) : View(context) {
    private val fastOutSlowIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // Time into the exit, ms (0 to TOTAL), set by MainActivity's animator.
    var t = 0f

    // How much of the launch screen's color is still there, 1 to 0.
    val backdrop get() = 1 - fastOutSlowIn.getInterpolation(((t - 60) / 240).coerceIn(0f, 1f))

    private val arrow = PathParser.createPathFromPathData(ARROW)
    private val lines = LINES.map { PathParser.createPathFromPathData(it) }
    private val grown = LINE_GROWTH.map { (from, length) -> fastOutSlowIn.getInterpolation(((played - from) / length).coerceIn(0f, 1f)) }
    private val lift = 7 * fastOutSlowIn.getInterpolation(((played - 100) / 300f).coerceIn(0f, 1f))
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val glow = ColorUtils.setAlphaComponent(accent, 0xCC)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeWidth = 1.6f; color = if (ink == android.graphics.Color.WHITE) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 2.2f; color = ColorUtils.setAlphaComponent(ink, 0xD9)
    }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = ColorUtils.blendARGB(accent, android.graphics.Color.WHITE, 0.55f)
    private val part = Path()
    private val ribbon = Path()
    private val p = FloatArray(2)
    private val left = FloatArray(2 * (STEPS + 1))
    private val right = FloatArray(2 * (STEPS + 1))
    private val along = FloatArray(2 * (STEPS + 1))

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        // The icon's 72 visible units (18 to 90 of its 108) fill the icon view, centered on it.
        val unit = icon.width / 72f
        if (unit <= 0f) return
        val sx = icon.x + icon.width / 2f
        val sy = icon.y + icon.height / 2f - lift * unit
        val d = min(width, height).toFloat()
        val k = scale(t)
        val arrowWidth = 45.5f * unit * k

        // The speed lines stay where they were and fade at once.
        val lineAlpha = 1 - (t / 90f).coerceIn(0f, 1f)
        if (lineAlpha > 0f) {
            canvas.save()
            canvas.translate(icon.x + icon.width / 2f - 54 * unit, icon.y + icon.height / 2f - 54 * unit)
            canvas.scale(unit, unit)
            line.alpha = (0xD9 * lineAlpha).toInt()
            lines.forEachIndexed { i, path ->
                if (grown[i] >= 1f) canvas.drawPath(path, line)
                else if (grown[i] > 0f) {
                    val m = PathMeasure(path, false)
                    part.reset()
                    m.getSegment(0f, m.length * grown[i], part, true)
                    canvas.drawPath(part, line)
                }
            }
            canvas.restore()
        }

        drawTrail(canvas, sx, sy, d, arrowWidth)

        if (t < GO + TRAVEL) {
            val u = progress(t)
            point(u, sx, sy, d, p)
            val outline = 1 - backdrop
            canvas.save()
            canvas.translate(p[0], p[1])
            canvas.rotate(angle(t, sx, sy, d))
            canvas.scale(unit * k, unit * k)
            canvas.translate(-54f, -54f)
            // An edge in the other color keeps the arrow seen over a Kav of its own color as the launch screen's goes.
            if (outline > 0f) {
                edge.alpha = (0xD9 * outline).toInt()
                canvas.drawPath(arrow, edge)
            }
            fill.setShadowLayer(6f, 0f, 0f, glow)
            canvas.drawPath(arrow, fill)
            canvas.restore()
        }
    }

    // A tapering strip along the path the arrow just flew, faded toward its tail: the trailer's ribbon, short.
    private fun drawTrail(canvas: Canvas, sx: Float, sy: Float, d: Float, arrowWidth: Float) {
        val from = max(GO, t - TAIL)
        val to = min(t, GO + TRAVEL)
        if (to - from < 1f) return
        for (i in 0..STEPS) {
            point(progress(from + (to - from) * i / STEPS), sx, sy, d, p)
            along[2 * i] = p[0]; along[2 * i + 1] = p[1]
        }
        for (i in 0..STEPS) {
            val a = max(0, i - 1); val c = min(STEPS, i + 1)
            var dx = along[2 * c] - along[2 * a]; var dy = along[2 * c + 1] - along[2 * a + 1]
            val len = kotlin.math.hypot(dx, dy).takeIf { it > 0f } ?: 1f
            dx /= len; dy /= len
            val age = (t - (from + (to - from) * i / STEPS)) / TAIL
            val w = max(0.5f, 0.5f * arrowWidth * (1 - age).coerceIn(0f, 1f).pow(0.6f)) / 2
            left[2 * i] = along[2 * i] - dy * w; left[2 * i + 1] = along[2 * i + 1] + dx * w
            right[2 * i] = along[2 * i] + dy * w; right[2 * i + 1] = along[2 * i + 1] - dx * w
        }
        val tx = along[0]; val ty = along[1]; val hx = along[2 * STEPS]; val hy = along[2 * STEPS + 1]
        ribbon.reset()
        ribbon.moveTo(left[0], left[1])
        for (i in 1..STEPS) ribbon.lineTo(left[2 * i], left[2 * i + 1])
        for (i in STEPS downTo 0) ribbon.lineTo(right[2 * i], right[2 * i + 1])
        ribbon.close()
        trail.shader = LinearGradient(tx, ty, hx, hy, intArrayOf(ColorUtils.setAlphaComponent(accent, 0), ColorUtils.setAlphaComponent(accent, 0x5C), ColorUtils.setAlphaComponent(accent, 0xA6)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(ribbon, trail)
        // A thinner, lighter core down its middle.
        core.style = Paint.Style.STROKE
        core.strokeCap = Paint.Cap.ROUND
        core.strokeWidth = 0.14f * arrowWidth
        core.shader = LinearGradient(tx, ty, hx, hy, ColorUtils.setAlphaComponent(light, 0), ColorUtils.setAlphaComponent(light, 0xC0), Shader.TileMode.CLAMP)
        ribbon.reset()
        ribbon.moveTo(tx, ty)
        for (i in 1..STEPS) ribbon.lineTo(along[2 * i], along[2 * i + 1])
        canvas.drawPath(ribbon, core)
    }

    companion object {
        // The exit's timeline, ms: the arrow tilts over TILT, sets off at GO and flies for TRAVEL, accelerating; the
        // trail drains TAIL after it, off screen.
        const val TILT = 110f
        const val GO = 40f
        const val TRAVEL = 290f
        const val TAIL = 100f
        const val TOTAL = GO + TRAVEL + TAIL
        private const val STEPS = 20

        // Kav's arrow and the speed lines as splash_icon.xml draws them and leaves them, with when (ms) and for how
        // long each line grows there.
        private const val ARROW = "M52.47,33.55 A1.73,1.73 0 0,1 55.53,33.55 L76.76,74.34 A0.95,0.95 0 0,1 75.59,75.67 L54,67.68 L32.41,75.67 A0.95,0.95 0 0,1 31.24,74.34 Z"
        private val LINES = listOf(
            "M44,67 C45.8,69.17 45.8,71.33 44,73.5 C42.2,75.67 42.2,77.83 44,80",
            "M54,64 C52.2,66.83 52.2,69.67 54,72.5 C55.8,75.33 55.8,78.17 54,81",
            "M64,67 C65.8,69.17 65.8,71.33 64,73.5 C62.2,75.67 62.2,77.83 64,80",
        )
        private val LINE_GROWTH = listOf(190f to 260f, 150f to 270f, 190f to 260f)

        // How far along the curve the arrow is at time t, 0 to 1: still, then speeding up.
        fun progress(t: Float): Float {
            val s = ((t - GO) / TRAVEL).coerceIn(0f, 1f)
            return s * s
        }

        // The arrow's size, 1 at take-off, smaller as it goes.
        fun scale(t: Float) = 1 - 0.38f * ((t - GO) / TRAVEL).coerceIn(0f, 1f)

        // The flight's curve from the arrow's start (sx, sy) on a screen whose shorter side is d: up and to the
        // right, over the top and out past the top left corner.
        private fun controls(sx: Float, sy: Float, d: Float) = floatArrayOf(
            sx, sy,
            sx + 0.45f * d, sy - 0.3f * d,
            sx + 0.35f * d, sy - 0.95f * d,
            sx - 0.75f * d, sy - 1.35f * d,
        )

        fun point(u: Float, sx: Float, sy: Float, d: Float, out: FloatArray) {
            val c = controls(sx, sy, d)
            val v = 1 - u
            val a = v * v * v; val b = 3 * v * v * u; val e = 3 * v * u * u; val f = u * u * u
            out[0] = a * c[0] + b * c[2] + e * c[4] + f * c[6]
            out[1] = a * c[1] + b * c[3] + e * c[5] + f * c[7]
        }

        // Which way the curve goes at u, in degrees clockwise from straight up (the way the arrow points at rest).
        fun heading(u: Float, sx: Float, sy: Float, d: Float): Float {
            val c = controls(sx, sy, d)
            val v = 1 - u
            val dx = 3 * v * v * (c[2] - c[0]) + 6 * v * u * (c[4] - c[2]) + 3 * u * u * (c[6] - c[4])
            val dy = 3 * v * v * (c[3] - c[1]) + 6 * v * u * (c[5] - c[3]) + 3 * u * u * (c[7] - c[5])
            return Math.toDegrees(atan2(dx, -dy).toDouble()).toFloat()
        }

        // The arrow turns from upright to the curve's way over TILT, then follows it.
        fun angle(t: Float, sx: Float, sy: Float, d: Float): Float {
            val tilt = 1 - (1 - (t / TILT).coerceIn(0f, 1f)).pow(3)
            return heading(progress(t), sx, sy, d) * tilt
        }
    }
}
