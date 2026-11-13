package uk.noammm.kav.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ui.Lang
import uk.noammm.kav.ui.Look

// Where a map type's tiles, names and credit come from (MapFile.Type): Kav's own map on the phone, satellite photos
// under its names, or one of OpenFreeMap's styles, from its pack on the phone or, while that isn't there, from
// OpenFreeMap's servers as the map is looked at.
sealed class MapTileProvider(val pack: MapPack) {
    // Draws nothing until its pack is on the phone.
    abstract val needsPack: Boolean
    // Drawn from its pack, or from what goes in it while it's not on the phone; false for a map with no pack at all.
    open val usesPack: Boolean = true
    abstract val credit: String
    // Esri's terms and OpenMapTiles' licence want their credit on the map while it shows. OpenStreetMap's guidelines
    // let Kav's own fade a moment after the map shows, as About Kav keeps the full credits.
    abstract val creditStays: Boolean
    // Its ground is light whatever Kav's look, so its credit is in dark ink.
    open val lightGround: Boolean = false
    // Its tiles load from the internet as the map is looked at.
    abstract fun online(): Boolean

    // The style for MapLibre, or none until its pack is on the phone. preview: Kav's own map read from GitHub for one spot
    // before it's downloaded, as setup and Settings show it.
    abstract fun style(ctx: Context, look: Look, labels: Lang, preview: Boolean): JSONObject?

    protected fun asset(ctx: Context, name: String) = ctx.assets.open(name).reader().use { it.readText() }

    // Kav's own map (Protomaps' build of OpenStreetMap), from the file on the phone or, for a preview, from GitHub.
    protected fun protomaps(ctx: Context, name: String, labels: Lang, preview: Boolean): JSONObject? {
        val files = ctx.applicationContext.filesDir
        val tiles = when {
            MapPacks.STANDARD.onPhone(files) -> "pmtiles://file://" + MapPacks.STANDARD.file(files, MapPacks.STANDARD.parts[0]).absolutePath
            preview -> "pmtiles://" + MapPacks.STANDARD.parts[0].url
            else -> return null
        }
        val style = JSONObject(asset(ctx, name).replace("__MAP__", tiles))
        if (!MapFile.buildings3d) withoutBuildings(style)
        MapLabels.rewrite(style, MapLabels.Schema.PROTOMAPS, labels)
        return MapShields.apply(style, MapLabels.Schema.PROTOMAPS)
    }

    object Standard : MapTileProvider(MapPacks.STANDARD) {
        override val needsPack = true
        override val credit = "Protomaps · © OpenStreetMap"
        override val creditStays = false
        override fun online() = false
        // OLED draws the map like a battery saver: black ground, grey roads, no buildings or land colours.
        override fun style(ctx: Context, look: Look, labels: Lang, preview: Boolean) = protomaps(ctx, when (look) {
            Look.LIGHT -> "map/style-light.json"
            Look.OLED -> "map/style-oled.json"
            else -> "map/style.json"
        }, labels, preview)
    }

    // Satellite keeps the dark map's names, light with a dark edge, which read on any photo.
    object Satellite : MapTileProvider(MapPacks.STANDARD) {
        override val needsPack = true
        override val credit = "Esri, Vantor, Earthstar Geographics · Protomaps · © OpenStreetMap"
        override val creditStays = true
        override fun online() = true
        override fun style(ctx: Context, look: Look, labels: Lang, preview: Boolean) =
            protomaps(ctx, "map/style.json", labels, preview)?.let { MapFile.satellite(it) }
    }

    class OpenFreeMap(private val name: String, override val lightGround: Boolean) : MapTileProvider(MapPacks.OPENFREEMAP) {
        override val needsPack = false
        override val credit = "OpenFreeMap · © OpenMapTiles · © OpenStreetMap"
        override val creditStays = true
        override fun online() = pack.state !is MapPack.State.Ready

        override fun style(ctx: Context, look: Look, labels: Lang, preview: Boolean): JSONObject {
            val style = JSONObject(asset(ctx, "map/openfreemap/$name.json"))
            val files = ctx.applicationContext.filesDir
            if (pack.onPhone(files)) {
                val tiles = pack.file(files, pack.parts[0]).absolutePath
                val assets = pack.file(files, pack.parts[1]).absolutePath
                val sources = style.getJSONObject("sources")
                sources.getJSONObject(sources.keys().next()).put("url", "pmtiles://file://$tiles")
                style.put("sprite", "file://$assets/sprites/ofm")
                style.put("glyphs", "file://$assets/fonts/{fontstack}/{range}.pbf")
            }
            MapLabels.rewrite(style, MapLabels.Schema.OPENMAPTILES, labels)
            return MapShields.apply(style, MapLabels.Schema.OPENMAPTILES)
        }
    }
}

// GovMap's maps, the Survey of Israel's: raster tiles with their own Hebrew names, always online. Its tile server only
// answers GovMap's own site, so Kav's requests to it say they come from there (GovMapTiles).
class GovMap(private val layers: List<Layer>, override val lightGround: Boolean) : MapTileProvider(MapPacks.STANDARD) {
    class Layer(val path: String, val minZoom: Int, val maxZoom: Int)

    override val needsPack = false
    override val usesPack = false
    override val credit = "© Survey of Israel (GovMap)"
    override val creditStays = true
    override fun online() = true

    override fun style(ctx: Context, look: Look, labels: Lang, preview: Boolean): JSONObject {
        val sources = JSONObject()
        val drawn = JSONArray().put(JSONObject().put("id", "background").put("type", "background")
            .put("paint", JSONObject().put("background-color", if (lightGround) "#f2efe9" else "#000000")))
        layers.forEachIndexed { i, l ->
            sources.put("govmap$i", JSONObject().put("type", "raster").put("tileSize", 256)
                .put("tiles", JSONArray().put("${GovMapTiles.HOST_URL}/${l.path}/{z}/{x}/{y}.${if (l.path.startsWith("tms/")) "jpg" else "png"}"))
                .put("minzoom", l.minZoom).put("maxzoom", l.maxZoom).put("bounds", JSONArray(listOf(34.2, 29.4, 35.95, 33.4))))
            drawn.put(JSONObject().put("id", "govmap$i").put("type", "raster").put("source", "govmap$i"))
        }
        // Kav's own markers and labels still need the map's fonts and icons.
        return JSONObject().put("version", 8).put("glyphs", "asset://map/fonts/{fontstack}/{range}.pbf")
            .put("sprite", "asset://map/sprites/black").put("sources", sources).put("layers", drawn)
    }

    companion object {
        val STREETS = GovMap(listOf(Layer("backgroundMaps/streets_and_buildings_6_2026", 7, 19)), lightGround = true)
        val PHOTO = GovMap(listOf(Layer("tms/orto2025me_update", 8, 20), Layer("backgroundMaps/labels_and_line_6_2026", 8, 19)), lightGround = false)
    }
}

// The map's own names in the language chosen for them, apart from Kav's (Settings, Look and language). Only labels that
// show a name change: house numbers, road numbers and the like keep what they show.
object MapLabels {
    enum class Schema { PROTOMAPS, OPENMAPTILES }

    fun rewrite(style: JSONObject, schema: Schema, labels: Lang): JSONObject {
        val name = names(schema, labels) ?: return style
        val layers = style.getJSONArray("layers")
        for (i in 0 until layers.length()) {
            val layout = layers.getJSONObject(i).optJSONObject("layout") ?: continue
            if (namesIn(layout.opt("text-field"))) layout.put("text-field", JSONArray(name.toString()))
        }
        return style
    }

    // Hebrew on Kav's own map is its names as the map has them (Hebrew in Israel, each place's own beyond), as it was
    // before the choice came.
    internal fun names(schema: Schema, labels: Lang): JSONArray? {
        val keys = when (schema) {
            Schema.PROTOMAPS -> if (labels == Lang.HE) return null else listOf("name:en", "name")
            Schema.OPENMAPTILES -> if (labels == Lang.HE) listOf("name:he", "name") else listOf("name:en", "name:latin", "name")
        }
        return JSONArray().put("coalesce").apply { for (k in keys) put(JSONArray().put("get").put(k)) }
    }

    internal fun namesIn(v: Any?): Boolean = when (v) {
        is String -> "{name" in v
        is JSONArray -> (v.length() >= 2 && v.opt(0) == "get" && (v.opt(1) as? String)?.let(::isName) == true) ||
            (0 until v.length()).any { namesIn(v.opt(it)) }
        is JSONObject -> v.keys().asSequence().any { namesIn(v.opt(it)) }
        else -> false
    }

    private fun isName(key: String) =
        key == "name" || key.startsWith("name:") || key.startsWith("name_") || key == "name2" || key == "name3" || key.startsWith("pgf:name")
}

// Kav's own map with 3D buildings off draws no buildings at all, not the flat ones either: the one setting is the switch
// for a map with less to lay out and draw where towns are dense.
internal fun withoutBuildings(style: JSONObject): JSONObject = style.put("layers", style.getJSONArray("layers").let { all ->
    JSONArray().apply { for (i in 0 until all.length()) all.getJSONObject(i).takeIf { it.optString("id") !in setOf("buildings", "buildings_3d") }?.let(::put) }
})

// Road numbers drawn as Israel's route markers (traffic sign 611): the number in the colour of the road's class on a
// white plate, in a frame of that colour. Freeways blue, main roads red, regional roads green and local roads black, as
// the Ministry of Transport's guidelines for signs on interurban roads (2016, figure 2-2) give them. OpenStreetMap tags
// Israel's roads by the same colours (motorway, trunk, primary, secondary), so the colour goes by the stretch of road:
// road 1 is blue where it's a freeway and red where it isn't. The plates are drawn by the app (MapShieldImages).
object MapShields {
    const val PREFIX = "kav-shield-"
    // The class of road each colour of marker goes with, and the ink of its number.
    val KINDS = listOf("motorway" to "freeway", "trunk" to "main", "primary" to "regional", "secondary" to "local")
    val INK = mapOf("freeway" to "#0A3EA6", "main" to "#D7191F", "regional" to "#00803A", "local" to "#1A1A1A")
    const val MAX_DIGITS = 4

    private fun shields(schema: MapLabels.Schema, layer: JSONObject) = when (schema) {
        MapLabels.Schema.PROTOMAPS -> layer.optString("id") == "roads_shields"
        MapLabels.Schema.OPENMAPTILES -> layer.optString("id") in setOf("highway-shield-non-us", "highway-shield-us-interstate", "road_shield_us")
    }

    // The style's own road number layers give way to Israel's markers, drawn last so they sit over every other label:
    // street and place names make room for them. A style that draws no road numbers (Dark, Fiord) stays without them.
    fun apply(style: JSONObject, schema: MapLabels.Schema): JSONObject {
        val layers = style.getJSONArray("layers")
        val kept = JSONArray()
        var had = false
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (shields(schema, layer)) had = true else kept.put(layer)
        }
        if (!had) return style
        style.getJSONObject("sources").put(SOURCE, JSONObject().put("type", "geojson").put("data", POINTS))
        return style.put("layers", kept.put(layer(schema)))
    }

    // The markers stand at fixed points along each road (assets/map/shields.geojson, made by tools/map_shields.py), as
    // other maps place them: they never move as the map zooms, and each zoom only adds the points of the next level
    // between those shown, so the same number never comes up twice close together. z: the zoom a point shows from.
    const val SOURCE = "kav_shields"
    const val POINTS = "asset://map/shields.geojson"

    internal fun layer(schema: MapLabels.Schema): JSONObject {
        val ref = JSONArray().put("get").put("ref")
        val kind = JSONArray().put("get").put("kind")
        val digits = JSONArray().put("length").put(ref)
        val names = KINDS.map { it.second }
        // A marker fades in through the level it first shows at, and back out towards it: on the way out the map drops
        // a level's own labels at once, with no fade of its own.
        val fade = JSONArray().put("interpolate").put(JSONArray().put("exponential").put(0.01)).put(JSONArray().put("zoom")).apply {
            for (z in 8..15) put(z).put(JSONArray().put("case").put(JSONArray().put(">=").put(JSONArray().put("get").put("z")).put(z)).put(0).put(1))
            put(16).put(1)
        }
        return JSONObject()
            .put("id", "kav_road_shields")
            .put("type", "symbol")
            .put("source", SOURCE)
            .put("minzoom", 8)
            .put("filter", JSONArray().put(">=").put(JSONArray().put("zoom")).put(JSONArray().put("get").put("z")))
            .put("layout", JSONObject()
                .put("icon-image", JSONArray().put("concat").put(PREFIX).put(kind).put("-").put(JSONArray().put("to-string").put(digits)))
                .put("text-field", ref)
                .put("text-font", JSONArray().put(if (schema == MapLabels.Schema.PROTOMAPS) "NotoMedium" else "Noto Sans Bold"))
                .put("text-size", 9)
                // Coarser levels win a collision, so a marker that's there stays as more come in around it: the points
                // come coarsest first (tools/map_shields.py) and the map places them in that order. A sort key here
                // had it draw every marker on its own, thousands of draws a frame over a city.
                .put("icon-padding", 4)
                .put("icon-rotation-alignment", "viewport")
                .put("text-rotation-alignment", "viewport"))
            .put("paint", JSONObject().put("text-color", JSONArray().put("match").put(kind).apply {
                names.forEach { put(it); put(INK.getValue(it)) }
                put(INK.getValue("local"))
            }).put("icon-opacity", fade).put("text-opacity", fade))
    }
}
