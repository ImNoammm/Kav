package uk.noammm.kav.data

import uk.noammm.kav.ui.T
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.Look

object MapFile {
    const val TAG = "map-1"
    const val URL = "https://github.com/${Updates.OWNER}/${Updates.REPO}/releases/download/$TAG/israel.pmtiles"

    const val BYTES = 185_001_087L

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data class Failed(val why: String) : State
        data object Ready : State
    }

    var state by mutableStateOf<State>(State.Missing)
        private set

    // The tiles Kav draws: its own file on the phone, or Liberty's vector tiles, which load online. Both styles and
    // credits come from a MapTileProvider; this only chooses between them and keeps the offline file downloaded.
    enum class Type { OFFLINE, SATELLITE, LIBERTY, BRIGHT, DARK, FIORD}
    var type by mutableStateOf(Type.OFFLINE)

    // Kept as they are made, so a download's progress and state stay put while screens come and go.
    private val providers = HashMap<String, MapTileProvider>()

    fun getProvider(ctx: Context): MapTileProvider {
        val app = ctx.applicationContext
        val kind = type
        val asset = when (kind) {
            Type.OFFLINE, Type.SATELLITE -> when {
                K.light -> "map/style-light.json"
                K.look == Look.OLED -> "map/style-oled.json"
                else -> "map/style.json"
            }
            Type.LIBERTY, Type.BRIGHT, Type.DARK, Type.FIORD -> "map/openfreemap/${kind.name.lowercase()}.json"
        }
        val variant = when(kind) {
            Type.SATELLITE -> "satellite"
            else -> ""
        }
        return synchronized(providers) {
            providers.getOrPut(asset+variant) {
                when (kind) {
                    Type.OFFLINE -> OfflineMapTileProvider(
                        styleJsonPath = asset,
                        mapPackDir = File(app.filesDir.absolutePath, "mappacks/standard").toPath(),
                        remoteUrl = "https://github.com/szaionz/kav-offline-maps/releases/download/v0.0.2/standard.tar.gz",
                        attribution = OFFLINE_ATTRIBUTION,
                    )
                    Type.SATELLITE -> OfflineMapTileProvider(
                        styleJsonPath = asset,
                        mapPackDir = File(app.filesDir.absolutePath, "mappacks/standard").toPath(),
                        remoteUrl = "https://github.com/szaionz/kav-offline-maps/releases/download/v0.0.2/standard.tar.gz",
                        attribution = OFFLINE_ATTRIBUTION, transformStyleJson = {satellite(it)}
                    )
                    Type.LIBERTY, Type.BRIGHT, Type.DARK, Type.FIORD -> OnlineOrOfflineMapTileProvider(
                        onlineMapTileProvider = OnlineMapTileProvider(
                            styleJsonPath = asset,
                            attribution = OPENFREEMAP_ATTRIBUTION,
                        ),
                        remoteUrl = OPENFREEMAP_URL,
                        mapPackDir = File(app.filesDir.absolutePath, "mappacks/openfreemap").toPath(),
                    )
                }
            }
        }
    }

    private const val PHOTOS = "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"

    private fun onPhotos(layer: JSONObject): JSONObject {
        val paint = layer.optJSONObject("paint")?.takeIf { it.has("text-halo-color") } ?: return layer
        paint.put("text-color", "rgba(255,255,255,0.95)").put("text-halo-color", "rgba(0,0,0,0.75)").put("text-halo-width", 1.5)
        return layer
    }

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

    private const val OFFLINE_ATTRIBUTION = "Protomaps · © OpenStreetMap"
    private const val OPENFREEMAP_ATTRIBUTION = "OpenFreeMap · © OpenStreetMap"
    private const val OPENFREEMAP_URL = "https://github.com/szaionz/kav-offline-maps/releases/download/v0.0.2/openfreemap.tar.gz"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)



    fun file(ctx: Context) = File(File(ctx.filesDir, "map"), "israel.pmtiles")

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (state !is State.Downloading) state = if (file(app).length() == BYTES) State.Ready else State.Missing
        scope.launch {
            File(app.filesDir, "tiles-esri").listFiles()?.forEach { it.delete() }
            File(app.filesDir, "tiles-esri").delete()
        }
    }

    fun startDownload(ctx: Context) {
        if (state is State.Downloading || state is State.Ready) return
        val app = ctx.applicationContext
        state = State.Downloading(0f)
        scope.launch {
            try {
                download(app)
                state = State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KavMap", "map download failed", e)
                state = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun download(ctx: Context) {
        val file = file(ctx)
        file.parentFile?.mkdirs()
        if (file.length() == BYTES) return
        val part = File(file.path + ".part")
        var have = part.length()
        if (have == BYTES && part.renameTo(file)) return
        if (have >= BYTES) { part.delete(); have = 0 }

        var c = URL(URL).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Kav")
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        var hops = 0
        while (c.responseCode in 300..399 && hops < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = URL(URL(URL), next).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Kav")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            hops++
        }
        when (c.responseCode) {
            200 -> { have = 0; part.delete() }
            206 -> {}
            else -> throw RuntimeException(T("Download HTTP ${c.responseCode}", "הורדה נכשלה, שגיאת HTTP ${c.responseCode}"))
        }
        c.inputStream.use { input ->
            java.io.FileOutputStream(part, have > 0).use { out ->
                val buf = ByteArray(256 * 1024)
                var done = have
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    state = State.Downloading((done.toFloat() / BYTES).coerceIn(0f, 1f))
                }
            }
        }
        if (part.length() != BYTES) throw RuntimeException(T("Interrupted at ${part.length() / (1 shl 20)} MB, try again", "ההורדה נקטעה אחרי ${part.length() / (1 shl 20)} MB, נסו שוב"))
        if (!part.renameTo(file)) throw RuntimeException(T("Could not keep the download", "לא ניתן היה לשמור את ההורדה"))
    }

    fun remove(ctx: Context) {
        file(ctx).delete()
        File(file(ctx).path + ".part").delete()
        state = State.Missing
    }
}
