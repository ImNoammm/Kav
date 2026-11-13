package uk.noammm.kav.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.maplibre.android.maps.Style
import uk.noammm.kav.data.MapShields
import kotlin.math.ceil

// Israel's route marker behind a road number (MapShields), drawn at the screen's own density so it stays sharp: a frame
// in the road's colour with a thin white rule inside it, and a white plate with its corners cut for the number.
internal object MapShieldImages {
    private val COLOURS = mapOf(
        "freeway" to 0xFF0A3EA6.toInt(), "main" to 0xFFD7191F.toInt(), "regional" to 0xFF00803A.toInt(), "local" to 0xFF1A1A1A.toInt(),
    )
    // Widths in dp for a number of one to four digits.
    private val WIDTHS = floatArrayOf(17f, 21f, 26f, 31f)
    private const val HEIGHT = 15f

    @Volatile private var made: Pair<Float, Map<String, Bitmap>>? = null

    fun images(density: Float): Map<String, Bitmap> {
        made?.takeIf { it.first == density }?.let { return it.second }
        val all = HashMap<String, Bitmap>()
        for ((name, colour) in COLOURS) for (digits in 1..MapShields.MAX_DIGITS) {
            all["${MapShields.PREFIX}$name-$digits"] = draw(colour, WIDTHS[digits - 1], density)
        }
        made = density to all
        return all
    }

    private fun draw(colour: Int, width: Float, d: Float): Bitmap {
        val w = ceil(width * d).toInt()
        val h = ceil(HEIGHT * d).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 2.5f * d, 2.5f * d, p)
        val rule = 1.25f * d
        p.style = Paint.Style.STROKE
        p.strokeWidth = .75f * d
        p.color = android.graphics.Color.WHITE
        c.drawRoundRect(RectF(rule, rule, w - rule, h - rule), 1.6f * d, 1.6f * d, p)
        p.style = Paint.Style.FILL
        val inset = 2.4f * d
        val left = inset
        val right = w - inset
        val top = inset
        val bottom = h - inset
        val cut = (bottom - top) * .3f
        c.drawPath(Path().apply {
            moveTo(left + cut, top); lineTo(right - cut, top); lineTo(right, top + cut); lineTo(right, bottom - cut)
            lineTo(right - cut, bottom); lineTo(left + cut, bottom); lineTo(left, bottom - cut); lineTo(left, top + cut); close()
        }, p)
        return b
    }
}

// A style with the route markers its road numbers are drawn on.
internal fun Style.Builder.withShields(ctx: Context): Style.Builder = apply {
    for ((id, image) in MapShieldImages.images(ctx.resources.displayMetrics.density)) withImage(id, image)
}
