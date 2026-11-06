package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMapOptions
import java.io.File
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

interface MapTileProvider {
    fun styleJson(ctx: Context): JSONObject
    fun attribution(): String

    fun isAvailable(ctx: Context): Boolean
}

class OfflineMapTileProvider(val styleJsonPath: Path,
                             val tilesPath: Path,
                             val remoteUrl: String,
    val attribution: String

    ) : MapTileProvider{


    override fun styleJson(ctx: Context): JSONObject {
        return JSONObject(File(styleJsonPath.absolutePathString()).readText().replace("__MAP__", tilesPath.absolutePathString()))
    }

    override fun attribution(): String {
        return attribution
    }

    override fun isAvailable(ctx: Context): Boolean {
        return tilesPath.exists()
    }

}
class OnlineMapTileProvider(val styleJsonPath: Path, val attribution: String): MapTileProvider {
    override fun styleJson(ctx: Context): JSONObject {
        return JSONObject(File(styleJsonPath.absolutePathString()).readText())
    }

    override fun attribution(): String {
        return attribution
    }

    override fun isAvailable(ctx: Context): Boolean {
        return true
    }
}

