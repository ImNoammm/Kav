package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ext.Looks
import uk.noammm.kav.ui.Lang
import uk.noammm.kav.ui.Look

object MapFile {
    // The map Kav draws (Settings, Map): its own, kept on the phone; satellite photos under its names; or one of
    // OpenFreeMap's four, kept on the phone or loaded from OpenFreeMap as the map is looked at.
    enum class Type { STANDARD, SATELLITE, LIBERTY, BRIGHT, DARK, FIORD, GOVMAP, GOVMAP_PHOTO }

    var type by mutableStateOf(Type.STANDARD)

    // The language of the map's own names, apart from Kav's.
    var labels by mutableStateOf(Lang.HE)

    // Buildings standing up in 3D on the standard map, close in (Settings, Map).
    var buildings3d by mutableStateOf(false)

    private val openFreeMap = mapOf(
        Type.LIBERTY to MapTileProvider.OpenFreeMap("liberty", lightGround = true),
        Type.BRIGHT to MapTileProvider.OpenFreeMap("bright", lightGround = true),
        Type.DARK to MapTileProvider.OpenFreeMap("dark", lightGround = false),
        Type.FIORD to MapTileProvider.OpenFreeMap("fiord", lightGround = false),
    )

    fun provider(t: Type = type): MapTileProvider = when (t) {
        Type.STANDARD -> MapTileProvider.Standard
        Type.SATELLITE -> MapTileProvider.Satellite
        Type.GOVMAP -> GovMap.STREETS
        Type.GOVMAP_PHOTO -> GovMap.PHOTO
        else -> openFreeMap.getValue(t)
    }

    // The map types drawn from a pack.
    fun typesOf(pack: MapPack) = Type.entries.filter { provider(it).let { p -> p.usesPack && p.pack === pack } }

    fun credit(t: Type = type) = provider(t).credit

    fun init(ctx: Context) = MapPacks.init(ctx.applicationContext.filesDir)

    // Each style is made off the main thread once and kept: reading, parsing and changing a whole style is work a screen
    // opening shouldn't wait for, and a kept map (MapPool) knows its style by the same text.
    private val made = object : LinkedHashMap<String, String>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 6
    }

    // tone: a theme's colors for Kav's own map (K.mapTone); every other map type is drawn as it is.
    suspend fun styleJson(ctx: Context, t: Type, look: Look, labels: Lang, preview: Boolean, tone: Looks.MapTone? = null): String? {
        val p = provider(t)
        val toned = tone?.takeIf { t == Type.STANDARD }
        val key = "$t|$look|$labels|$preview|${p.pack.state is MapPack.State.Ready}|$buildings3d|${ctx.resources.displayMetrics.density}|$toned"
        synchronized(made) { made[key] }?.let { return it }
        val json = withContext(Dispatchers.Default) {
            p.style(ctx, look, labels, preview)?.let { if (toned != null) toned(it, toned) else it }?.toString()
        } ?: return null
        synchronized(made) { made[key] = json }
        return json
    }

    // Esri's World Imagery needs no key and has photos to zoom 19 over Israel.
    private const val PHOTOS = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    // The photos under the map's names: the land, water, buildings and roads drawn on top would hide what the photo
    // shows, so only the background and the names stay.
    internal fun satellite(style: JSONObject): JSONObject {
        style.getJSONObject("sources").put("satellite", JSONObject()
            .put("type", "raster").put("tiles", JSONArray().put(PHOTOS)).put("tileSize", 256).put("maxzoom", 19))
        val layers = style.getJSONArray("layers")
        val kept = JSONArray()
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            when (layer.optString("type")) {
                "background" -> {
                    kept.put(layer)
                    kept.put(JSONObject().put("id", "satellite").put("type", "raster").put("source", "satellite"))
                }
                "symbol" -> if (layer.optString("id") != "roads_oneway") kept.put(onPhotos(layer))
            }
        }
        return style.put("layers", kept)
    }

    // Kav's own map in a theme's two colors: each of its colors, all greys, is placed by its lightness between the land's
    // (the ground) and the town names' (the ink), and drawn as far between the theme's ground and ink, a little past
    // either end where Kav's map goes past them (buildings darker than the land). The water takes its own color when the
    // theme gives one, and its names' edge with it. Israel's road number markers (MapShields) keep their own colors.
    internal fun toned(style: JSONObject, tone: Looks.MapTone): JSONObject {
        val layers = style.getJSONArray("layers")
        fun layer(id: String) = (0 until layers.length()).map { layers.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
        val land = layer("earth")?.optJSONObject("paint")?.opt("fill-color") as? String ?: return style
        val names = layer("places_locality")?.optJSONObject("paint")?.opt("text-color") as? String ?: return style
        val from = lightness(land) ?: return style
        val to = lightness(names)?.takeIf { it != from } ?: return style
        fun mix(a: Int, b: Int, t: Float) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        fun shade(c: String): String {
            val t = ((lightness(c) ?: return c) - from) / (to - from)
            val g = tone.ground; val k = tone.ink
            return "#%02x%02x%02x".format(
                mix(g shr 16 and 0xff, k shr 16 and 0xff, t), mix(g shr 8 and 0xff, k shr 8 and 0xff, t), mix(g and 0xff, k and 0xff, t))
        }
        fun walk(v: Any?): Any? = when (v) {
            is String -> if (HEX.matches(v)) shade(v) else v
            is JSONArray -> JSONArray().apply { for (i in 0 until v.length()) put(walk(v.get(i))) }
            else -> v
        }
        val water = tone.water?.let { "#%06x".format(it and 0xffffff) }
        for (i in 0 until layers.length()) {
            val layer = layers.getJSONObject(i)
            if (layer.optString("source") == MapShields.SOURCE) continue
            val paint = layer.optJSONObject("paint") ?: continue
            for (k in paint.keys().asSequence().toList()) if (k.endsWith("color")) paint.put(k, walk(paint.get(k)))
            if (water != null && layer.optString("source-layer") == "water") when (layer.optString("type")) {
                "fill" -> paint.put("fill-color", water)
                "line" -> paint.put("line-color", water)
                "symbol" -> if (paint.has("text-halo-color")) paint.put("text-halo-color", water)
            }
        }
        return style
    }

    private val HEX = Regex("#[0-9a-fA-F]{6}")

    // How light a "#rrggbb" color is, 0 to 255.
    private fun lightness(c: String): Float? {
        if (!HEX.matches(c)) return null
        val n = c.substring(1).toInt(16)
        return .2126f * (n shr 16 and 0xff) + .7152f * (n shr 8 and 0xff) + .0722f * (n and 0xff)
    }

    // The black map's names are dark grey, which a photo swallows: white with a dark edge instead. Road numbers keep
    // their own plate.
    private fun onPhotos(layer: JSONObject): JSONObject {
        val paint = layer.optJSONObject("paint")?.takeIf { it.has("text-halo-color") } ?: return layer
        paint.put("text-color", "rgba(255,255,255,0.95)").put("text-halo-color", "rgba(0,0,0,0.75)").put("text-halo-width", 1.5)
        return layer
    }
}
