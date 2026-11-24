package uk.noammm.kav.ext

import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener

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

    // Every extension's image address for the point, in turn, as long as Kav takes the one before it.
    fun urls(lat: Double, lon: Double): Sequence<Pair<String, String>> = sequence {
        val input = JSONObject().put("lat", lat).put("lon", lon).put("prefs", Extensions.prefs()).toString()
        for ((i, _) in Hooks.offered(CAPABILITY)) {
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
