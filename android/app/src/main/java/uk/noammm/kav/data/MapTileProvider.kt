package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMapOptions
import uk.noammm.kav.ui.T
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.noammm.kav.data.MapFile.state


sealed interface State {
    data object Missing : State
    data class Downloading(val progress: Float) : State
    data class Failed(val why: String) : State
    data object Ready : State
}
fun download(ctx: Context, endpointUrl: String, target: File, onUpdate: (State)->(Unit)) {
    target.parentFile?.mkdirs()
    val parentDir = target.parentFile ?: ctx.filesDir

    // Create a temporary directory in the same folder as the target for instant rename/move support
    val tempPart = File(parentDir, "${target.name}.part_${System.currentTimeMillis()}")
    tempPart.mkdirs()

    try {
        var c = URL(endpointUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Kav")

        var hops = 0
        while (c.responseCode in 300..399 && hops < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = URL(URL(endpointUrl), next).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Kav")
            hops++
        }

        if (c.responseCode != 200 && c.responseCode != 206) {
            throw RuntimeException(
                T(
                    "Download HTTP ${c.responseCode}",
                    "הורדה נכשלה, שגיאת HTTP ${c.responseCode}"
                )
            )
        }

        // Get total expected size from Content-Length header
        val totalBytes = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L

        // Start tar xz subprocess reading from stdin, extracting into our temp folder
        val process = ProcessBuilder("tar", "-xz", "-C", tempPart.absolutePath)
            .redirectErrorStream(true)
            .start()

        // Pipe download stream directly into the tar process
        c.inputStream.use { input ->
            process.outputStream.use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (totalBytes > 0) {
                        onUpdate( State.Downloading((done.toFloat() / totalBytes).coerceIn(0f, 1f)))
                    }
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException(T("Tar extraction failed with exit code $exitCode", "חלץ נכשל עם קוד יציאה $exitCode"))
        }

        // Clean up target if it already exists before moving the new version in place
        if (target.exists()) {
            target.deleteRecursively()
        }

        // Seamless atomic-like move into the final target destination
        if (!tempPart.renameTo(target)) {
            // Fallback copy if rename fails across different mount points (rare since it's in the same parent dir)
            tempPart.copyRecursively(target, overwrite = true)
            tempPart.deleteRecursively()
        }
    } finally {
        // Ensure temporary artifacts are cleaned up if anything failed midway
        if (tempPart.exists()) {
            tempPart.deleteRecursively()
        }
    }
}
interface MapTileProvider {
    fun styleJson(ctx: Context): JSONObject
    fun attribution(): String

    fun isAvailable(ctx: Context): Boolean
}

interface DownloadableMapTileProvider : MapTileProvider {
    fun isDownloaded(ctx: Context): Boolean
    fun startDownload(ctx: Context)
    fun isDownloadOptional(): Boolean
}

class OfflineMapTileProvider(
    val styleJsonPath: String,
    val mapPackDir: Path,
    val remoteUrl: String,
    val attribution: String,
    val spriteFileName: String="black", val transformStyleJson: (JSONObject)->(JSONObject)= {it}
) : DownloadableMapTileProvider {


    override fun styleJson(ctx: Context): JSONObject {
        val sj = JSONObject(ctx.assets.open(styleJsonPath).reader().use { it.readText() })
        val tilesPath = File(mapPackDir.absolutePathString(), "israel.pmtiles").toPath()
        val url = if (!isDownloaded(ctx)) "pmtiles://"+ MapFile.URL else "pmtiles://file://$tilesPath"

        val spritesPath = File(mapPackDir.absolutePathString(), "sprites").toPath()
        val fontsPath = File(mapPackDir.absolutePathString(), "fonts").toPath()
        val sources = sj.getJSONObject("sources")
        val key = sources.keys().next()
        sources.getJSONObject(key).put("url",
            url
        )
        val spriteUrl = if (isDownloaded(ctx)) "file://${spritesPath.absolutePathString()}/$spriteFileName" else "https://raw.githubusercontent.com/ImNoammm/Kav/ede72bdb2f0618fdb845503925685dd365cf0982/android/app/src/main/assets/map/sprites/black"
        val fontsUrl = if (isDownloaded(ctx)) "file://"+fontsPath.absolutePathString()+"/{fontstack}/{range}.pbf" else "https://github.com/ImNoammm/Kav/raw/ede72bdb2f0618fdb845503925685dd365cf0982/android/app/src/main/assets/map/fonts/{fontstack}/{range}.pbf"
        sj.put("sprite", spriteUrl)
        sj.put("glyphs", fontsUrl)
        return transformStyleJson(sj)
    }

    override fun attribution(): String {
        return attribution
    }

    override fun isAvailable(ctx: Context): Boolean {
        return mapPackDir.exists()
    }

    var state by mutableStateOf<State>(if (mapPackDir.exists())State.Ready  else State.Missing)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun isDownloaded(ctx : Context): Boolean{
        return mapPackDir.exists()
    }

    override fun startDownload(ctx: Context){
        if (state is State.Downloading || state is State.Ready) return
        val app = ctx.applicationContext
        state = State.Downloading(0f)
        scope.launch {
            try {
                download(app, remoteUrl,
                    target = File(mapPackDir.absolutePathString()), onUpdate = {s -> state=s})
                state = State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KavMap", "map download failed", e)
                state = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
    override fun isDownloadOptional() =  false
}

class OnlineMapTileProvider(val styleJsonPath: String, val attribution: String) :
    MapTileProvider {
    override fun styleJson(ctx: Context): JSONObject {
        return JSONObject(
            ctx.assets.open(styleJsonPath).reader().use { it.readText() }
        )
    }

    override fun attribution(): String {
        return attribution
    }

    override fun isAvailable(ctx: Context): Boolean {
        return true
    }
}

class OnlineOrOfflineMapTileProvider(
    val onlineMapTileProvider: OnlineMapTileProvider,
    val remoteUrl: String,
    val mapPackDir: Path, val spriteFileName: String="ofm"
) : DownloadableMapTileProvider{
    var state by mutableStateOf<State>(if (mapPackDir.exists())State.Ready  else State.Missing)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun isDownloaded(ctx : Context): Boolean{
        return mapPackDir.exists()
    }

    override fun styleJson(ctx: Context): JSONObject {
        val sj = onlineMapTileProvider.styleJson(ctx)
        if (!isDownloaded(ctx)) return sj
        val tilesPath = File(mapPackDir.absolutePathString(), "israel.pmtiles").toPath()
        val spritesPath = File(mapPackDir.absolutePathString(), "sprites").toPath()
        val fontsPath = File(mapPackDir.absolutePathString(), "fonts").toPath()
        val sources = sj.getJSONObject("sources")
        val key = sources.keys().next()
        sources.getJSONObject(key).put("url",
            "pmtiles://file://"+tilesPath.absolutePathString()
        )
        sj.put("sprite", "file://${spritesPath.absolutePathString()}/$spriteFileName")
        sj.put("glyphs", "file://"+fontsPath.absolutePathString()+"/{fontstack}/{range}.pbf")
        return sj
    }

    override fun attribution(): String {
        return onlineMapTileProvider.attribution()
    }

    override fun isAvailable(ctx: Context): Boolean {
        return true
    }
    override fun startDownload(ctx: Context){
        if (state is State.Downloading || state is State.Ready) return
        val app = ctx.applicationContext
        state = State.Downloading(0f)
        scope.launch {
            try {
                download(app, remoteUrl,
                    target = File(mapPackDir.absolutePathString()), onUpdate = {s -> state=s})
                state = State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KavMap", "map download failed", e)
                state = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
    override fun isDownloadOptional() =  true


}