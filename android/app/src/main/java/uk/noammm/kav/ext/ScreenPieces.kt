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
    // a background of its own (a card, the tab bar, a sheet: Modifier.panel and the glass surfaces, ui/Glass.kt);
    // covers: it hides what was drawn before it under its box (seen()), not a box that only holds others, as
    // accessibility has it.
    class Node(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val tap: Boolean = false, val field: Boolean = false, val text: Boolean = false, val image: Boolean = false,
        val card: Boolean = false, val covers: Boolean = tap || field || text || image || card,
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

    // For each piece, what goes in its place in the picture of the screen without its pieces (ui/ScreenOverlay.kt): null
    // for what Kav draws behind it (drawn), when that is what the screen (shot) shows just around the piece, else the
    // color the screen shows most there. Around: points ring px out of its box, on the screen and in no piece; what Kav
    // draws is what shows when it is opaque and within 12 of it in each of red, green and blue at three in four of them.
    // A piece with no such point takes what Kav draws.
    fun behind(pieces: JSONArray, width: Int, height: Int, ring: Int, shot: (Int, Int) -> Int, drawn: (Int, Int) -> Int): List<Int?> {
        val boxes = boxes(pieces)
        return boxes.map { b ->
            val l = b[0] - ring; val t = b[1] - ring; val r = b[2] - 1 + ring; val bt = b[3] - 1 + ring
            val step = maxOf(2, 2 * (r - l + bt - t) / 96)
            val points = ArrayList<IntArray>()
            for (x in l..r step step) { points += intArrayOf(x, t); points += intArrayOf(x, bt) }
            for (y in t..bt step step) { points += intArrayOf(l, y); points += intArrayOf(r, y) }
            val around = points.filter { (x, y) -> x in 0 until width && y in 0 until height && boxes.none { x >= it[0] && x < it[2] && y >= it[1] && y < it[3] } }
            if (around.isEmpty()) return@map null
            val seen = around.map { (x, y) -> shot(x, y) }
            val alike = around.indices.count { k -> drawn(around[k][0], around[k][1]).let { d -> d ushr 24 == 255 && near(d, seen[k]) } }
            if (alike * 4 >= around.size * 3) null else seen.groupingBy { it }.eachCount().maxBy { it.value }.key
        }
    }

    // Only what is each piece's own in the picture (shot, width × height, one color a pixel), and the pieces with their
    // boxes tight to it. Not its own: what of its box is what is behind it (behind, the same size), reached from the
    // box's edge through more of it, so a face of the page's color within its outline stays. A pixel is what is behind
    // it when it is within 8 in each of alpha, red, green and blue of behind there or at a pixel next to it: a picture
    // drawn again off the screen is a little off where it is scaled. Cleared in shot: that, and all that is in no
    // piece's box. A piece with less than 4 px left across is no piece. With fore (the same size: the screen drawn
    // without Kav's background and maps), what is clear in it is never a piece's own, also inside its outline (a
    // see-through tile), and what is a piece's own is taken from it, as clear as it is drawn.
    fun faces(pieces: JSONArray, width: Int, height: Int, shot: IntArray, behind: IntArray, fore: IntArray? = null): JSONArray {
        val back = BooleanArray(width * height) { true }
        val boxes = boxes(pieces).map { b -> intArrayOf(maxOf(b[0], 0), maxOf(b[1], 0), minOf(b[2], width), minOf(b[3], height)) }
        for (b in boxes) for (y in b[1] until b[3]) for (x in b[0] until b[2]) back[y * width + x] = fore != null && fore[y * width + x] ushr 24 == 0
        fun behind(x: Int, y: Int): Boolean {
            val p = shot[y * width + x]
            for (ny in maxOf(y - 1, 0)..minOf(y + 1, height - 1)) for (nx in maxOf(x - 1, 0)..minOf(x + 1, width - 1))
                if (close(p, behind[ny * width + nx])) return true
            return false
        }
        for (b in boxes) {
            val (l, t, r, bt) = b
            if (r <= l || bt <= t) continue
            val queue = IntArray((r - l) * (bt - t))
            val seen = BooleanArray(queue.size)
            var head = 0; var tail = 0
            fun reach(x: Int, y: Int) {
                val k = (y - t) * (r - l) + (x - l)
                if (seen[k]) return
                seen[k] = true
                if (back[y * width + x] || behind(x, y)) { back[y * width + x] = true; queue[tail++] = y * width + x }
            }
            for (x in l until r) { reach(x, t); reach(x, bt - 1) }
            for (y in t until bt) { reach(l, y); reach(r - 1, y) }
            while (head < tail) {
                val at = queue[head++]; val x = at % width; val y = at / width
                if (x > l) reach(x - 1, y); if (x < r - 1) reach(x + 1, y)
                if (y > t) reach(x, y - 1); if (y < bt - 1) reach(x, y + 1)
            }
        }
        for (k in back.indices) if (back[k]) shot[k] = 0 else if (fore != null) shot[k] = fore[k]
        val out = JSONArray()
        for (i in boxes.indices) {
            val (l, t, r, bt) = boxes[i]
            var minX = r; var minY = bt; var maxX = l - 1; var maxY = t - 1
            for (y in t until bt) for (x in l until r) if (!back[y * width + x]) {
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            if (maxX - minX + 1 < 4 || maxY - minY + 1 < 4) continue
            out.put(JSONObject(pieces.getJSONObject(i).toString()).put("x", minX).put("y", minY).put("w", maxX - minX + 1).put("h", maxY - minY + 1))
        }
        return out
    }

    // Whether a box (from the screen's top left corner) is inside a piece's: what is drawn there goes with the piece.
    fun within(l: Float, t: Float, r: Float, b: Float, pieces: JSONArray): Boolean =
        boxes(pieces).any { l >= it[0] - 1 && t >= it[1] - 1 && r <= it[2] + 1 && b <= it[3] + 1 }

    private fun boxes(pieces: JSONArray) = (0 until pieces.length()).map {
        val p = pieces.getJSONObject(it)
        intArrayOf(p.getInt("x"), p.getInt("y"), p.getInt("x") + p.getInt("w"), p.getInt("y") + p.getInt("h"))
    }

    private fun close(a: Int, b: Int): Boolean {
        var s = 0
        while (s < 32) { if (Math.abs((a ushr s and 255) - (b ushr s and 255)) > 8) return false; s += 8 }
        return true
    }

    private fun near(a: Int, b: Int) = (0..2).all { Math.abs((a shr it * 8 and 255) - (b shr it * 8 and 255)) <= 12 }

    // Each node some of which is on the screen and not covered, from the one drawn last (on top) to the first, the way
    // accessibility finds what is seen: a node that covers hides what was drawn before it, once what it holds is
    // looked at. A page drawn over another (Settings over Home) hides that one's nodes, though they are still in the
    // tree. Each root on its own, as accessibility has each view's. look(node, what it holds) says whether to look into
    // what it holds.
    private fun seen(roots: List<Node>, width: Int, height: Int, dx: Int, dy: Int, look: (Node, List<Node>) -> Boolean) {
        for (r in roots.asReversed()) {
            val open = Uncovered(dx.toFloat(), dy.toFloat(), dx + width.toFloat(), dy + height.toFloat())
            fun walk(n: Node, depth: Int) {
                if (depth > 64 || !open.meets(n.left, n.top, n.right, n.bottom)) return
                val kids = n.children()
                if (look(n, kids)) for (k in kids.asReversed()) walk(k, depth + 1)
                if (n.covers) open.cover(n.left, n.top, n.right, n.bottom)
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
