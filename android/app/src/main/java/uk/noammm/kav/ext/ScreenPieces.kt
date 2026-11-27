package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject

// The screen as an extension may see it (Kav.capture, a contribution with capability "screen.capture"): one picture of
// what Kav's window shows, and where its pieces are on it, each by its box and what kind of piece it is. Never a
// piece's text, description or value, and never what is typed: each field's inside is painted over in the picture
// (ui/ScreenOverlay.kt). With the part "screen.overlay", a view over the whole screen (the same file), it can draw the
// screen's pieces moving, its own picture over Kav's, and Kav's own screen stays as it was under it.
object ScreenPieces {
    const val CAPABILITY = "screen.capture"
    const val PART = "screen.overlay"
    const val MAX = 300

    // One node of Kav's screen as its semantics have it, with its box in the window's pixels. tap: it takes a click, a
    // long click or a toggle; field: text can be typed in it; text and image: it shows text, or an image; card: it draws
    // a background of its own (a card, the tab bar, a sheet: Modifier.panel and the glass surfaces, ui/Glass.kt).
    class Node(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val tap: Boolean = false, val field: Boolean = false, val text: Boolean = false, val image: Boolean = false,
        val card: Boolean = false,
        val children: () -> List<Node> = { emptyList() },
    )

    // The pieces, in the order the screen has them: a field, a button or a card whole (what it holds moves with it, so no
    // background is left behind empty), else each text or image with nothing under it. One smaller than 4 px across is
    // no piece, nor a field or button taking more than half of the screen, or a card more than nine tenths of it (those
    // are looked into), nor one all covered by what is drawn over it (seen()). Boxes are cut to the screen (width ×
    // height) and given from its top left corner (dx, dy: where that corner is in the window).
    fun pick(roots: List<Node>, width: Int, height: Int, dx: Int = 0, dy: Int = 0): JSONArray {
        val out = ArrayList<JSONObject>()
        val area = width.toFloat() * height
        seen(roots, width, height, dx, dy) { n, kids ->
            if (out.size >= MAX) return@seen false
            val whole = when { n.field -> "field"; n.tap -> "button"; n.card -> "card"; else -> null }
            val most = if (whole == "card") area * 0.9f else area / 2f
            if (whole != null) box(n, width, height, dx, dy, most)?.let { out += it.put("kind", whole); return@seen false }
            if (whole == null && kids.isEmpty()) when {
                n.text -> box(n, width, height, dx, dy, area / 2f)?.let { out += it.put("kind", "text") }
                n.image -> box(n, width, height, dx, dy, area / 2f)?.let { out += it.put("kind", "image") }
            }
            true
        }
        return JSONArray(out.asReversed())
    }

    // Every field on the screen, inside a piece or not, for its inside to be painted over in the picture. Also one
    // covered by what is drawn over it: that may be see-through.
    fun fields(roots: List<Node>, width: Int, height: Int, dx: Int = 0, dy: Int = 0): JSONArray {
        val out = JSONArray()
        fun walk(n: Node, depth: Int) {
            if (depth > 64) return
            if (n.field) box(n, width, height, dx, dy, Float.MAX_VALUE)?.let { out.put(it.put("kind", "field")) }
            for (k in n.children()) walk(k, depth + 1)
        }
        for (r in roots) walk(r, 0)
        return out
    }

    // Each node some of which is on the screen and not covered, from the one drawn last (on top) to the first, the way
    // accessibility finds what is seen: a node covers what was drawn before it, once what it holds is looked at. A
    // page drawn over another (Settings over Home) hides that one's nodes, though they are still in the tree. Each root
    // on its own, as accessibility has each view's. look(node, what it holds) says whether to look into what it holds.
    private fun seen(roots: List<Node>, width: Int, height: Int, dx: Int, dy: Int, look: (Node, List<Node>) -> Boolean) {
        for (r in roots.asReversed()) {
            val open = Uncovered(dx.toFloat(), dy.toFloat(), dx + width.toFloat(), dy + height.toFloat())
            fun walk(n: Node, depth: Int) {
                if (depth > 64 || !open.meets(n.left, n.top, n.right, n.bottom)) return
                val kids = n.children()
                if (look(n, kids)) for (k in kids.asReversed()) walk(k, depth + 1)
                open.cover(n.left, n.top, n.right, n.bottom)
            }
            walk(r, 0)
        }
    }

    // What of the screen nothing has covered yet, as boxes that don't overlap.
    private class Uncovered(l: Float, t: Float, r: Float, b: Float) {
        private var open = listOf(floatArrayOf(l, t, r, b))

        fun meets(l: Float, t: Float, r: Float, b: Float) = r > l && b > t && open.any { it[0] < r && l < it[2] && it[1] < b && t < it[3] }

        fun cover(l: Float, t: Float, r: Float, b: Float) {
            // Past this many boxes the rest is left open: more given, and more painted over, never less.
            if (r <= l || b <= t || open.size > 4000) return
            open = open.flatMap { o ->
                if (o[0] >= r || l >= o[2] || o[1] >= b || t >= o[3]) return@flatMap listOf(o)
                val mt = maxOf(o[1], t); val mb = minOf(o[3], b)
                listOfNotNull(
                    if (o[1] < t) floatArrayOf(o[0], o[1], o[2], t) else null,
                    if (b < o[3]) floatArrayOf(o[0], b, o[2], o[3]) else null,
                    if (o[0] < l) floatArrayOf(o[0], mt, l, mb) else null,
                    if (r < o[2]) floatArrayOf(r, mt, o[2], mb) else null,
                )
            }
        }
    }

    private fun box(n: Node, width: Int, height: Int, dx: Int, dy: Int, most: Float): JSONObject? {
        val l = maxOf(n.left - dx, 0f); val t = maxOf(n.top - dy, 0f)
        val r = minOf(n.right - dx, width.toFloat()); val b = minOf(n.bottom - dy, height.toFloat())
        val w = r - l; val h = b - t
        if (w < 4f || h < 4f || w * h > most) return null
        return JSONObject().put("x", Math.round(l)).put("y", Math.round(t)).put("w", Math.round(w)).put("h", Math.round(h))
    }
}
