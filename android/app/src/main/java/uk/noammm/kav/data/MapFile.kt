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
    enum class Type { OFFLINE, LIBERTY, }

    var type by mutableStateOf(Type.OFFLINE)

    fun getProvider(ctx: Context): MapTileProvider {
        val openFreeMapPath = File(ctx.filesDir.absolutePath, "mappacks/openfreemap").toPath()
        val standardMapPath = File(ctx.filesDir.absolutePath, "mappacks/standard").toPath()
        return when (type) {
            Type.OFFLINE -> OfflineMapTileProvider(
                styleJsonPath = styleFile(ctx, when {
                    K.light -> "map/style-light.json"
                    K.look== Look.OLED -> "map/style-oled.json"
                    else -> "map/style.json"
                }),
                mapPackDir = standardMapPath,
                remoteUrl = URL,
                attribution = OFFLINE_ATTRIBUTION,
            )
            Type.LIBERTY ->
                OnlineOrOfflineMapTileProvider(
                onlineMapTileProvider =  OnlineMapTileProvider(
                styleJsonPath = styleFile(ctx, "map/openfreemap/liberty.json"),
                attribution = OPENFREEMAP_ATTRIBUTION,
                ), remoteUrl = "https://github.com/szaionz/kav-offline-maps/releases/download/v0.0.0/openfreemap.tar.gz", mapPackDir = openFreeMapPath)

        }
    }


    private const val OFFLINE_ATTRIBUTION = "Protomaps · © OpenStreetMap"
    private const val OPENFREEMAP_ATTRIBUTION = "OpenFreeMap · © OpenStreetMap"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The assets are styles, but MapTileProvider reads a style from a Path, so each is copied to the phone once per run.
    private val extracted = HashSet<String>()

    fun file(ctx: Context) = File(File(ctx.filesDir, "map"), "israel.pmtiles")

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        if (state !is State.Downloading) state = if (file(app).length() == BYTES) State.Ready else State.Missing
        scope.launch {
            File(app.filesDir, "tiles-esri").listFiles()?.forEach { it.delete() }
            File(app.filesDir, "tiles-esri").delete()
        }
    }

    // OLED draws the map like a battery saver: black ground, grey roads, no buildings or land colours. remote: read
    // from the file on GitHub, a piece at a time, for a preview before it's downloaded.
    // A copied asset keeps its name: style.json, style-light.json, style-oled.json and liberty.json are all distinct.
    private fun styleFile(ctx: Context, asset: String): Path {
        val file = File(File(ctx.filesDir, "map-styles"), asset.substringAfterLast('/'))
        synchronized(extracted) {
            if (asset !in extracted) {
                file.parentFile?.mkdirs()
                ctx.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
                extracted += asset
            }
        }
        return file.toPath()
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
