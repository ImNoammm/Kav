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
    // long click or a toggle; field: text can be typed in it; text and image: it shows text, or an image.
    class Node(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val tap: Boolean = false, val field: Boolean = false, val text: Boolean = false, val image: Boolean = false,
        val children: () -> List<Node> = { emptyList() },
    )

    // The pieces, in the order the screen has them: a field or a button whole (what it holds moves with it), else each
    // text or image with nothing under it. One smaller than 4 px across or taking more than half of the screen is no
    // piece (a button that big is looked into). Boxes are cut to the screen (width × height) and given from its top
    // left corner (dx, dy: where that corner is in the window).
    fun pick(roots: List<Node>, width: Int, height: Int, dx: Int = 0, dy: Int = 0): JSONArray {
        val out = JSONArray()
        val half = width.toFloat() * height / 2f
        fun add(n: Node, kind: String): Boolean {
            val l = maxOf(n.left - dx, 0f); val t = maxOf(n.top - dy, 0f)
            val r = minOf(n.right - dx, width.toFloat()); val b = minOf(n.bottom - dy, height.toFloat())
            val w = r - l; val h = b - t
            if (w < 4f || h < 4f || w * h > half) return false
            out.put(JSONObject().put("x", Math.round(l)).put("y", Math.round(t)).put("w", Math.round(w)).put("h", Math.round(h)).put("kind", kind))
            return true
        }
        fun walk(n: Node, depth: Int) {
            if (out.length() >= MAX || depth > 64) return
            val whole = when { n.field -> "field"; n.tap -> "button"; else -> null }
            if (whole != null && add(n, whole)) return
            val kids = n.children()
            if (kids.isEmpty()) {
                if (whole == null) when { n.text -> add(n, "text"); n.image -> add(n, "image") }
                return
            }
            for (k in kids) walk(k, depth + 1)
        }
        for (r in roots) walk(r, 0)
        return out
    }
}
