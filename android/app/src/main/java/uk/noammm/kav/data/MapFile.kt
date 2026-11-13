package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

    suspend fun styleJson(ctx: Context, t: Type, look: Look, labels: Lang, preview: Boolean): String? {
        val p = provider(t)
        val key = "$t|$look|$labels|$preview|${p.pack.state is MapPack.State.Ready}|$buildings3d"
        synchronized(made) { made[key] }?.let { return it }
        val json = withContext(Dispatchers.Default) { p.style(ctx, look, labels, preview)?.toString() } ?: return null
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

    // The black map's names are dark grey, which a photo swallows: white with a dark edge instead. Road numbers keep
    // their own plate.
    private fun onPhotos(layer: JSONObject): JSONObject {
        val paint = layer.optJSONObject("paint")?.takeIf { it.has("text-halo-color") } ?: return layer
        paint.put("text-color", "rgba(255,255,255,0.95)").put("text-halo-color", "rgba(0,0,0,0.75)").put("text-halo-width", 1.5)
        return layer
    }
}
