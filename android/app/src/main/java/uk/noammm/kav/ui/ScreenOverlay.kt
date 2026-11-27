package uk.noammm.kav.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.Pip
import uk.noammm.kav.ext.ScreenPieces
import java.lang.ref.WeakReference

// The part "screen.overlay" (ext/ScreenPieces.kt): extensions' views over the whole of Kav's screen, under its dialogs
// and sheets, which are windows of their own. They sit in a plain Android layer above Kav's Compose view, so a touch
// a view doesn't take (false from its first touch) goes on to Kav as if the layer weren't there. Not in picture in
// picture, nor in safe mode (rememberPart).
//
// And the screen's picture with its pieces' boxes (Kav.capture): Kav's window copied as it shows now, with its maps,
// which draw on surfaces of their own, under it. With it, if asked (Kav.captureBehind), the same screen without its
// pieces: Kav's backdrops drawn again off the screen where the pieces are (without()).
// A surface that draws a background of its own (ui/Glass.kt), so a card is one piece with what it holds (ScreenPieces).
// Seen by nothing else: accessibility skips a key it doesn't know.
private val Card = SemanticsPropertyKey<Unit>("KavCard")
internal fun Modifier.cardPiece(): Modifier = semantics { this[Card] = Unit }

// What a surface draws behind what it holds (Kav's background, a skin's piece), drawn again off the screen for the
// picture of what is behind the screen's pieces (Kav.captureBehind). maps: Kav's maps show over it, through holes in
// what is drawn after it. One to a box: a second on the same box takes the first's place in that picture.
internal fun Modifier.backdrop(maps: Boolean = false, paint: DrawScope.() -> Unit): Modifier = this then BackdropElement(maps, paint)

private val Backdrop = SemanticsPropertyKey<BackdropNode>("KavBackdrop")

private data class BackdropElement(val maps: Boolean, val paint: DrawScope.() -> Unit) : ModifierNodeElement<BackdropNode>() {
    override fun create() = BackdropNode(maps, paint)
    override fun update(node: BackdropNode) { node.maps = maps; node.paint = paint; node.invalidateDraw() }
}

private class BackdropNode(var maps: Boolean, var paint: DrawScope.() -> Unit) :
    Modifier.Node(), DrawModifierNode, LayoutAwareModifierNode, SemanticsModifierNode {
    var at: LayoutCoordinates? = null
    var units = Density(1f)
    var direction = LayoutDirection.Ltr

    override fun ContentDrawScope.draw() {
        units = Density(density, fontScale); direction = layoutDirection
        paint()
        drawContent()
    }

    override fun onPlaced(coordinates: LayoutCoordinates) { at = coordinates }

    override fun SemanticsPropertyReceiver.applySemantics() { this[Backdrop] = this@BackdropNode }
}

object ScreenOverlay {
    private const val TAG = "kav.screen.overlay"
    private var shownOn: WeakReference<Activity>? = null

    @Composable
    internal fun Host() {
        val activity = LocalContext.current as? Activity ?: return
        val part = if (Pip.active) null else rememberPart(ScreenPieces.PART, Unit) { JSONObject() }
        val views = part?.after?.map { it.second }.orEmpty()
        SideEffect { show(activity, views) }
        DisposableEffect(activity) { onDispose { show(activity, emptyList()) } }
    }

    private fun show(activity: Activity, views: List<View>) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        var layer = content.findViewWithTag<FrameLayout>(TAG)
        if (views.isEmpty()) {
            layer?.let { it.removeAllViews(); content.removeView(it) }
            if (shownOn?.get() === activity) shownOn = null
            return
        }
        shownOn = WeakReference(activity)
        if (layer == null) {
            layer = FrameLayout(activity).apply { tag = TAG }
            content.addView(layer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        if (layer.childCount != views.size || views.indices.any { layer.getChildAt(it) !== views[it] }) {
            layer.removeAllViews()
            for (v in views) {
                (v.parent as? ViewGroup)?.removeView(v)
                layer.addView(v, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            }
        }
        layer.bringToFront()
    }

    // Kav's screen in front, with what the part's views cover, from its top left corner, and with behind, the screen
    // without its pieces, else null. Told on the main thread: null, null and "[]" when Kav isn't in front or the copy
    // fails. The pictures are the extension's, to recycle when done.
    fun capture(activity: Activity?, behind: Boolean = false, answer: (Bitmap?, Bitmap?, String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            val a = activity ?: shownOn?.get()
            val front = (a as? ComponentActivity)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true
            val content = a?.findViewById<ViewGroup>(android.R.id.content)
            val window = a?.window
            if (!front || content == null || window == null || content.width <= 0 || content.height <= 0) { answer(null, null, "[]"); return@post }
            val at = IntArray(2).also { content.getLocationInWindow(it) }
            val box = Rect(at[0], at[1], at[0] + content.width, at[1] + content.height)
            val layer = content.findViewWithTag<View>(TAG)
            val shot = Bitmap.createBitmap(box.width(), box.height(), Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(window, box, shot, { r ->
                    if (r != PixelCopy.SUCCESS) { shot.recycle(); answer(null, null, "[]"); return@request }
                    under(content, layer, box, shot, behind) { picture, maps ->
                        val roots = runCatching { roots(content, layer) }.getOrDefault(emptyList())
                        val pieces = runCatching { ScreenPieces.pick(roots, box.width(), box.height(), box.left, box.top) }.getOrDefault(JSONArray())
                        // Every field, also one inside a card or a button, else none is given: no picture with what is typed.
                        val fields = runCatching { ScreenPieces.fields(roots, box.width(), box.height(), box.left, box.top) }.getOrNull()
                        if (fields == null) { picture.recycle(); maps?.recycle(); answer(null, null, "[]"); return@under }
                        blank(picture, fields)
                        val back = if (behind) without(content, layer, box, picture, maps, pieces) else null
                        maps?.recycle()
                        answer(picture, back, pieces.toString())
                    }
                }, main)
            } catch (_: IllegalArgumentException) { shot.recycle(); answer(null, null, "[]") }
        }
    }

    // Kav's maps under the window's picture: each draws on its own surface, which the window shows through a hole. With
    // keep, the maps the window shows on their own too (not one a page covers), else null.
    private fun under(content: ViewGroup, layer: View?, box: Rect, shot: Bitmap, keep: Boolean, done: (Bitmap, Bitmap?) -> Unit) {
        val surfaces = mutableListOf<SurfaceView>()
        fun find(v: View) {
            if (v === layer || v.visibility != View.VISIBLE) return
            if (v is SurfaceView) { if (v.width > 0 && v.height > 0 && v.holder.surface.isValid) surfaces += v; return }
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
        }
        find(content)
        if (surfaces.isEmpty()) { done(shot, null); return }
        val out = Bitmap.createBitmap(shot.width, shot.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val seen = if (keep) Bitmap.createBitmap(shot.width, shot.height, Bitmap.Config.ARGB_8888) else null
        var left = surfaces.size
        val main = Handler(Looper.getMainLooper())
        for (s in surfaces) {
            val at = IntArray(2).also { s.getLocationInWindow(it) }
            val copy = Bitmap.createBitmap(s.width, s.height, Bitmap.Config.ARGB_8888)
            fun next(ok: Boolean) {
                val x = at[0] - box.left; val y = at[1] - box.top
                if (ok) canvas.drawBitmap(copy, x.toFloat(), y.toFloat(), null)
                if (ok && seen != null && shows(shot, x, y, s.width, s.height)) Canvas(seen).drawBitmap(copy, x.toFloat(), y.toFloat(), null)
                copy.recycle()
                if (--left == 0) { canvas.drawBitmap(shot, 0f, 0f, null); shot.recycle(); done(out, seen) }
            }
            try { PixelCopy.request(s, copy, { r -> next(r == PixelCopy.SUCCESS) }, main) } catch (_: IllegalArgumentException) { next(false) }
        }
    }

    // Whether the window shows a surface in this box: a hole in its picture somewhere over it, not a page covering it all.
    private fun shows(shot: Bitmap, x: Int, y: Int, w: Int, h: Int): Boolean {
        for (py in maxOf(y, 0) until minOf(y + h, shot.height) step 8) for (px in maxOf(x, 0) until minOf(x + w, shot.width) step 8)
            if (shot.getPixel(px, py) ushr 24 < 255) return true
        return false
    }

    // The screen without its pieces: the picture, and where each piece is, Kav's backdrops drawn there off the screen, or
    // the color seen most around it where those aren't what shows around it (ScreenPieces.behind): a page of one color
    // that is no backdrop.
    private fun without(content: ViewGroup, layer: View?, box: Rect, picture: Bitmap, maps: Bitmap?, pieces: JSONArray): Bitmap? = runCatching {
        val drawn = runCatching { drawn(content, layer, box, picture.width, picture.height, maps, pieces) }.getOrNull()
        val ring = (3 * content.resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val how = ScreenPieces.behind(pieces, picture.width, picture.height, ring, picture::getPixel) { x, y -> drawn?.getPixel(x, y) ?: 0 }
        val out = picture.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = android.graphics.Paint()
        for (k in how.indices) {
            val p = pieces.getJSONObject(k)
            val r = Rect(p.getInt("x"), p.getInt("y"), p.getInt("x") + p.getInt("w"), p.getInt("y") + p.getInt("h"))
            val flat = how[k]
            if (flat == null && drawn != null) canvas.drawBitmap(drawn, r, r, null)
            else { paint.color = flat ?: 0; canvas.drawRect(r, paint) }
        }
        drawn?.recycle()
        out
    }.getOrNull()

    // Kav's backdrops off the screen, each in the order it is drawn, at its place: not one inside a piece's box, which
    // goes with the piece; Kav's maps over the one they show through.
    private fun drawn(content: ViewGroup, layer: View?, box: Rect, width: Int, height: Int, maps: Bitmap?, pieces: JSONArray): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = androidx.compose.ui.graphics.Canvas(out.asImageBitmap())
        val scope = CanvasDrawScope()
        for (b in backdrops(content, layer)) {
            val at = b.at?.takeIf { it.isAttached } ?: continue
            val clip = at.boundsInWindow().translate(-box.left.toFloat(), -box.top.toFloat())
            if (clip.isEmpty || ScreenPieces.within(clip.left, clip.top, clip.right, clip.bottom, pieces)) continue
            val pos = at.positionInWindow()
            canvas.save()
            canvas.clipRect(clip.left, clip.top, clip.right, clip.bottom)
            canvas.translate(pos.x - box.left, pos.y - box.top)
            scope.draw(b.units, b.direction, canvas, Size(at.size.width.toFloat(), at.size.height.toFloat())) { b.paint(this) }
            canvas.restore()
            if (b.maps && maps != null) canvas.nativeCanvas.drawBitmap(maps, 0f, 0f, null)
        }
        return out
    }

    // Each backdrop on the screen, in the order it is drawn, by Kav's semantics as they are, unmerged.
    private fun backdrops(content: ViewGroup, layer: View?): List<BackdropNode> {
        val out = mutableListOf<BackdropNode>()
        fun walk(n: SemanticsNode, depth: Int) {
            if (depth > 64) return
            n.config.getOrNull(Backdrop)?.let { out += it }
            for (k in n.children) if (placed(k, n)) walk(k, depth + 1)
        }
        fun find(v: View) {
            if (v === layer || v.visibility != View.VISIBLE) return
            if (v is androidx.compose.ui.node.RootForTest) walk(v.semanticsOwner.unmergedRootSemanticsNode, 0)
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
        }
        find(content)
        return out
    }

    // What is typed in a field kept out of the picture: the field's inside painted over in its own color (from near its top
    // edge, where no text is), its rounded ends as they were.
    private fun blank(picture: Bitmap, fields: JSONArray) {
        val canvas = Canvas(picture)
        val paint = android.graphics.Paint()
        for (k in 0 until fields.length()) {
            val p = fields.getJSONObject(k)
            val x = p.getInt("x"); val y = p.getInt("y"); val w = p.getInt("w"); val h = p.getInt("h")
            val sy = (y + h * 0.12f).toInt().coerceIn(0, picture.height - 1)
            val seen = listOf(0.3f, 0.5f, 0.7f).map { picture.getPixel((x + w * it).toInt().coerceIn(0, picture.width - 1), sy) }
            paint.color = seen.groupingBy { it }.eachCount().maxBy { it.value }.key
            val dx = minOf(h * 0.3f, w / 4f); val dy = h * 0.15f
            canvas.drawRect(x + dx, y + dy, x + w - dx, y + h - dy, paint)
        }
    }

    // Each of Kav's Compose views on the screen (not the part's own), by its semantics.
    private fun roots(content: ViewGroup, layer: View?): List<ScreenPieces.Node> {
        val roots = mutableListOf<ScreenPieces.Node>()
        fun find(v: View) {
            if (v === layer || v.visibility != View.VISIBLE) return
            if (v is androidx.compose.ui.node.RootForTest) roots += node(v.semanticsOwner.rootSemanticsNode)
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
        }
        find(content)
        return roots
    }

    private fun node(n: SemanticsNode): ScreenPieces.Node {
        val c = n.config
        val b = n.boundsInWindow
        val tap = SemanticsActions.OnClick in c || SemanticsActions.OnLongClick in c || SemanticsProperties.ToggleableState in c
        val field = SemanticsActions.SetText in c || SemanticsProperties.EditableText in c
        val text = SemanticsProperties.Text in c
        val image = c.getOrNull(SemanticsProperties.Role) == Role.Image || SemanticsProperties.ContentDescription in c
        val card = Card in c
        return ScreenPieces.Node(
            b.left, b.top, b.right, b.bottom, tap, field, text, image, card,
            // A page's list covers what is under it, as one piece does; a box only holding others doesn't.
            covers = tap || field || text || image || card || c.isMergingSemanticsOfDescendants || SemanticsActions.ScrollBy in c,
            children = { n.children.filter { placed(it, n) }.map(::node) },
        )
    }

    // Whether a node is on the screen: a tab behind another (MainActivity's TabStack) or a list's item held for reuse
    // keeps its nodes and their last boxes, but isn't placed, so nothing of it is drawn.
    private fun placed(n: SemanticsNode, parent: SemanticsNode): Boolean {
        var i: androidx.compose.ui.layout.LayoutInfo? = n.layoutInfo
        while (i != null && i !== parent.layoutInfo) {
            if (!i.isPlaced || i.isDeactivated) return false
            i = i.parentInfo
        }
        return true
    }
}
