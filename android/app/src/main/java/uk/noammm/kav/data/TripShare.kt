package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// The browser and Kav read the same saved route. Provider request data and live positions are left out.
internal object TripShare {
    private const val MAX_BYTES = 2_000_000

    // card: the picture chats show with the link (ShareSheet's card), a JPEG. A server that doesn't take one yet, or won't
    // take this one, still makes the link, without it.
    // The server a new link is made on, as picked in Settings (null until it is): Kav's or the rider's own (server/selfhost).
    var picked by mutableStateOf<Source?>(null)
    val server: String get() = Sources.shareOn(picked, KavLive.source, KavPlan.source, KavLive.ownServer)

    fun create(trip: JSONObject, days: Int, card: ByteArray? = null): String {
        require(days in listOf(3, 7, 30))
        val at = server
        val doc = JSONObject().put("trip", trip).put("days", days)
        val result = card?.let { picture ->
            val encoded = Base64.getEncoder().encodeToString(picture)
            runCatching { request(at, "/api/share", JSONObject(doc.toString()).put("card", encoded), encoded.length) }.getOrNull()
        } ?: request(at, "/api/share", doc)
        val url = result.getString("url")
        require(made(at, url))
        return url
    }

    // A link where the server that made it keeps its links, and nowhere else.
    internal fun made(at: String, url: String): Boolean = url.matches(Regex(Regex.escape(linkSite(at)) + "/[A-Za-z0-9]{7}"))

    // Where a server's links are: Kav's on share.kavapp.link (asked through kav.noammm.uk, it still gives its own), a
    // copy's on itself.
    internal fun linkSite(at: String): String = if (at == KavLive.KAV_SERVER) "https://${TripLink.HOST}" else at

    // host: the server the link is on, Kav's own or a copy of it.
    fun read(id: String, host: String = TripLink.HOST): JSONObject {
        require(id.matches(Regex("[A-Za-z0-9]{7}")))
        return request("https://$host", "/api/share/$id")
    }

    private fun request(at: String, path: String, doc: JSONObject? = null, card: Int = 0): JSONObject {
        val c = (RiderProxy.open(URI(at + path).toURL()) as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            instanceFollowRedirects = false
        }
        try {
            if (doc != null) {
                val bytes = doc.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 262_144 + card) { "This trip is too large to share" }
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                KavLive.sign(c, path, KavLive.keyFor(at))
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            if (doc == null && c.responseCode in listOf(404, 410)) throw TripLink.Expired()
            if (c.responseCode != if (doc == null) 200 else 201) throw java.io.IOException("Shared trip HTTP ${c.responseCode}")
            val bytes = c.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() <= 262_144) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 262_145 - out.size()))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            require(bytes.size <= 262_144)
            return JSONObject(String(bytes, Charsets.UTF_8))
        } finally { c.disconnect() }
    }

    fun encode(trip: Transit.Itinerary, r: Transit.Resolved, colors: List<String> = emptyList(), stopNamesEnglish: Boolean? = null): String {
        fun legs(l: Transit.Leg): List<Transit.Leg> = listOf(l) + l.alternatives.flatMap(::legs)
        val parts = trip.legs.flatMap(::legs)
        val stopIds = parts.flatMap { it.stops + listOf(it.fromStop, it.toStop) }.toSet()
        val lineIds = parts.flatMap { it.alternativeLineIds + it.lineId }.toSet()
        val lines = r.lines.filterKeys { it in lineIds }
        val agencies = lines.values.map { it.agencyId }.toSet()
        val details = Transit.Resolved(lines, r.stops.filterKeys { it in stopIds }, r.routeTypes.filterKeys { it in agencies })
        val o = JourneyFile.routesJson(listOf(trip), details, emptyList()).put("v", 1).put("colors", JSONArray(colors))
        if (stopNamesEnglish != null) {
            val net = uk.noammm.kav.Loaded.net
            details.stops.forEach { (id, stop) ->
                var name = net?.shown(stop.name, stopNamesEnglish) ?: stop.name
                if (name == stop.name && net != null) {
                    val at = stop.code.toIntOrNull()?.let { KavLive.stopOfCode(net, it) }?.takeIf { it >= 0 }
                    if (at != null) name = if (stopNamesEnglish) net.nameEn.getOrNull(at)?.ifEmpty { null } ?: net.name[at] else net.name[at]
                }
                o.getJSONObject("stops").getJSONObject(id.toString()).put("displayName", name)
            }
        }
        o.getJSONArray("trips").getJSONObject(0).remove("original")
        val bytes = o.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "This trip is too large to share" }
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray()).also {
            require(it.length <= 250_000) { "This trip is too large to share" }
        }
    }

    fun decode(data: String): KavPlan.Planned? = runCatching {
        require(data.length <= 250_000)
        val bytes = GZIPInputStream(Base64.getUrlDecoder().decode(data).inputStream()).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(out.size() + n <= MAX_BYTES)
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        require(o.getInt("v") == 1 && o.getJSONArray("trips").length() == 1)
        val saved = checkNotNull(JourneyFile.routes(o))
        require(saved.routes.single().legs.isNotEmpty())
        KavPlan.Planned(Transit.Plan(saved.routes), saved.resolved)
    }.getOrNull()
}
