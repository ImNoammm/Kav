package uk.noammm.kav.ext

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kav.kavapp.api.KavMap
import java.util.concurrent.ConcurrentHashMap

// What native extensions put on Kav's maps (api/KavMap.java): their markers, lines and areas, drawn by TileMap in layers
// of their own over Kav's, a tap on one told to its extension, and the stop look Kav's Live stops are drawn with (the
// last one set wins). Every call into the extension goes through the crash guard.
object NativeMap {
    enum class Kind { MARKER, LINE, AREA }

    class Shape(
        val identity: String, val id: String, val on: String?, val kind: Kind, val points: DoubleArray,
        val color: Int, val outline: Int = 0, val widthDp: Float = 0f, val icon: Bitmap? = null,
    ) {
        val key get() = key(identity, id)
        fun shows(map: String) = on == null || on == map
    }

    private fun key(identity: String, id: String) = "$identity\u0000$id"

    private val shapes = ConcurrentHashMap<String, Shape>()
    private val taps = ConcurrentHashMap<String, KavMap.Tapped>()
    @Volatile private var look: Pair<String, KavMap.StopLook>? = null
    // Each stop's look as its extension answered, by its reference (null: Kav's own).
    private val looks = ConcurrentHashMap<String, Any>()
    private val NONE = Any()

    // Raised at every change, for the maps to draw again.
    private val changes = MutableStateFlow(0)
    val version: StateFlow<Int> = changes

    private fun changed() { changes.value++ }

    // The loaded run of each extension Kav calls now (Native.Loaded): only its own calls are taken. One switched off,
    // updated or retired keeps none, so a thread of an old run draws nothing, and a run started again (switched back on,
    // or its new release) is taken at once.
    private val alive = ConcurrentHashMap<String, Any>()

    fun live(identity: String, run: Any) { alive[identity] = run }

    // An extension Kav no longer calls (Native.retire): its shapes, tap and stop look gone, and nothing more taken from it.
    fun drop(identity: String) {
        alive.remove(identity)
        shapes.values.removeIf { it.identity == identity }
        taps.remove(identity)
        if (look?.first == identity) look = null
        looks.clear()
        changed()
    }

    fun shapes(map: String): List<Shape> = shapes.values.filter { it.shows(map) }

    val hasStopLook get() = look != null

    // A stop's mark as the extension with a stop look wants it, or null for Kav's own.
    fun stopLook(stop: String, name: String, lat: Double, lon: Double): KavMap.Look? {
        val (identity, l) = look ?: return null
        val got = looks.getOrPut(stop) { NativeGuard.call(identity, "stop look") { l.look(stop, name, lat, lon) } ?: NONE }
        return got as? KavMap.Look
    }

    // A tap on one of its shapes, on the main thread with the screen's context.
    fun tapped(key: String, ctx: Context) {
        val s = shapes[key] ?: return
        val t = taps[s.identity] ?: return
        NativeGuard.call(s.identity, "map tap") { t.tapped(s.id, ctx) }
    }

    fun of(identity: String, run: Any): KavMap = object : KavMap {
        private fun live() = alive[identity] === run
        private fun put(s: Shape) { if (!live()) return; shapes[s.key] = s; changed() }

        override fun marker(id: String, on: String?, lat: Double, lon: Double, color: Int, icon: Bitmap?) =
            put(Shape(identity, id, on, Kind.MARKER, doubleArrayOf(lat, lon), color, icon = icon))

        override fun line(id: String, on: String?, points: DoubleArray, color: Int, widthDp: Float) =
            put(Shape(identity, id, on, Kind.LINE, points.copyOf(), color, widthDp = widthDp))

        override fun area(id: String, on: String?, points: DoubleArray, fill: Int, outline: Int) =
            put(Shape(identity, id, on, Kind.AREA, points.copyOf(), fill, outline = outline))

        override fun remove(id: String) { if (live() && shapes.remove(key(identity, id)) != null) changed() }

        override fun clear() { if (live() && shapes.values.removeIf { it.identity == identity }) changed() }

        override fun onTap(tapped: KavMap.Tapped?) { if (!live()) return; if (tapped == null) taps.remove(identity) else taps[identity] = tapped }

        override fun stopLook(l: KavMap.StopLook?) {
            if (!live()) return
            look = if (l != null) identity to l else look?.takeIf { it.first != identity }
            looks.clear()
            changed()
        }
    }
}
