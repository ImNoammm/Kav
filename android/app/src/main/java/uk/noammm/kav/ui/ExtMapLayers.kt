package uk.noammm.kav.ui

import androidx.compose.ui.graphics.toArgb
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import uk.noammm.kav.ext.NativeMap

// Native extensions' shapes on a map (ext/NativeMap.kt), in one source and layers of their own over Kav's: areas, their
// outlines, lines, markers drawn as their icons or as dots.
private const val SRC_EXT = "kav-ext"
private val EXT_LAYERS = listOf("kav-ext-area", "kav-ext-outline", "kav-ext-line", "kav-ext-dot", "kav-ext-mark")

private fun argb(c: Int) = "rgba(${(c shr 16) and 255},${(c shr 8) and 255},${c and 255},${((c ushr 24) / 255f)})"

private fun iconName(b: android.graphics.Bitmap) = "kav-ext-${System.identityHashCode(b)}"

internal fun Style.ensureExtLayers() {
    if (getSource(SRC_EXT) != null) return
    addSource(GeoJsonSource(SRC_EXT))
    val kind = Expression.get("kind")
    fun of(k: String) = Expression.eq(kind, Expression.literal(k))
    val colour = Expression.toColor(Expression.get("colour"))
    addLayer(FillLayer("kav-ext-area", SRC_EXT).withProperties(PropertyFactory.fillColor(colour)).withFilter(of("area")))
    addLayer(LineLayer("kav-ext-outline", SRC_EXT).withProperties(
        PropertyFactory.lineColor(Expression.toColor(Expression.get("outline"))), PropertyFactory.lineWidth(1.5f),
    ).withFilter(Expression.all(of("area"), Expression.has("outline"))))
    addLayer(LineLayer("kav-ext-line", SRC_EXT).withProperties(
        PropertyFactory.lineColor(colour), PropertyFactory.lineWidth(Expression.toNumber(Expression.get("width"))),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
    ).withFilter(of("line")))
    addLayer(CircleLayer("kav-ext-dot", SRC_EXT).withProperties(
        PropertyFactory.circleColor(colour), PropertyFactory.circleRadius(6f),
        PropertyFactory.circleStrokeColor(argb(MapInk.bg.toArgb())), PropertyFactory.circleStrokeWidth(2f),
    ).withFilter(Expression.all(of("marker"), Expression.not(Expression.has("icon")))))
    addLayer(SymbolLayer("kav-ext-mark", SRC_EXT).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
    ).withFilter(Expression.all(of("marker"), Expression.has("icon"))))
}

internal fun Style.removeExtLayers() {
    for (id in EXT_LAYERS) removeLayer(id)
    removeSource(SRC_EXT)
}

// The shapes for this map, sent whole (an extension's shapes are few).
internal fun Style.setExtShapes(shapes: List<NativeMap.Shape>) {
    for (s in shapes) s.icon?.let { b -> val n = iconName(b); if (getImage(n) == null) addImage(n, b) }
    fun pts(p: DoubleArray) = (0 until p.size / 2).map { Point.fromLngLat(p[2 * it + 1], p[2 * it]) }
    val features = shapes.mapNotNull { s ->
        val g = when (s.kind) {
            NativeMap.Kind.MARKER -> pts(s.points).firstOrNull()
            NativeMap.Kind.LINE -> pts(s.points).takeIf { it.size >= 2 }?.let { LineString.fromLngLats(it) }
            NativeMap.Kind.AREA -> pts(s.points).takeIf { it.size >= 3 }?.let { Polygon.fromLngLats(listOf(it + it.first())) }
        } ?: return@mapNotNull null
        Feature.fromGeometry(g).apply {
            addStringProperty("key", s.key)
            addStringProperty("kind", s.kind.name.lowercase())
            addStringProperty("colour", argb(s.color))
            if (s.kind == NativeMap.Kind.LINE) addNumberProperty("width", s.widthDp)
            if (s.outline != 0) addStringProperty("outline", argb(s.outline))
            s.icon?.let { addStringProperty("icon", iconName(it)) }
        }
    }
    getSourceAs<GeoJsonSource>(SRC_EXT)?.setGeoJson(FeatureCollection.fromFeatures(features))
}

// The extension shape under a tap, the top one: its key (NativeMap.tapped), or null.
internal fun MapLibreMap.extShapeAt(x: Float, y: Float): String? {
    val r = 12 * android.content.res.Resources.getSystem().displayMetrics.density
    val near = android.graphics.RectF(x - r, y - r, x + r, y + r)
    // The top layer first: a marker over a line over an area.
    return EXT_LAYERS.reversed().firstNotNullOfOrNull { layer -> queryRenderedFeatures(near, layer).firstNotNullOfOrNull { it.getStringProperty("key") } }
}
