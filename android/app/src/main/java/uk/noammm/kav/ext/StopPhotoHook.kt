package uk.noammm.kav.ext

import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import uk.noammm.kav.data.Transit

// Optional stop images. A null or failed answer lets the next provider, then Street View, answer. Each failure is
// logged for the extension's developer.
object StopPhotoHook {
    const val CAPABILITY = "stop.photo"
    private const val TAG = "KavStopPhoto"

    internal fun parse(text: String): String? = runCatching {
        val value = JSONTokener(text).nextValue() as? String ?: return null
        val uri = java.net.URI(value)
        value.takeIf { uri.scheme == "https" && !uri.host.isNullOrBlank() }
    }.getOrNull()

    // What one extension is asked: the point and the rider's display settings, and, when Kav knows the stop, its code and,
    // for one of the extension's own stops, the extension's own id for it. Another extension's stop is only a point.
    internal fun input(lat: Double, lon: Double, stop: Transit.StopInfo?, asker: Namespace, prefs: JSONObject): JSONObject {
        val o = JSONObject().put("lat", lat).put("lon", lon).put("prefs", prefs)
        val ref = stop?.let { RefTable.ref(it.id) }
        val mine = ref != null && Namespace(ref.publisher, ref.dataset) == asker
        if (stop != null && (ref == null || mine)) o.put("stop", JSONObject().put("code", stop.code).apply { if (mine) put("id", ref!!.localId) })
        return o
    }

    // Every extension's image address for the point, in turn, as long as Kav takes the one before it.
    fun urls(lat: Double, lon: Double, stop: Transit.StopInfo? = null): Sequence<Pair<String, String>> = sequence {
        val prefs = Extensions.prefs()
        for ((i, _) in Hooks.offered(CAPABILITY)) {
            val input = input(lat, lon, stop, i.namespace, prefs).toString()
            val answer = Native.hook(i.identity, CAPABILITY, input, 3000) ?: continue
            val text = answer.getOrNull()
            val url = text?.takeIf { it != "null" }?.let(::parse)
            when {
                text == null -> Log.w(TAG, "${i.identity}: ${answer.exceptionOrNull()?.message}")
                url != null -> yield(i.identity to url)
                text != "null" -> Log.w(TAG, "${i.identity}: not an https address")
            }
        }
    }

    internal fun failed(identity: String, url: String, why: Throwable?) =
        Log.w(TAG, "$identity: its image didn't load ($url): ${why?.message ?: "not an image Android can read"}")
}
