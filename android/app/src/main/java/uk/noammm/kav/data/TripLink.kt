package uk.noammm.kav.data

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONObject

// Kav's short links, geo: links and pasted coordinates.
object TripLink {
    // Where Kav's server's links are: share.kavapp.link, and kav.noammm.uk for those made before it.
    const val HOST = "share.kavapp.link"
    const val OLD_HOST = "kav.noammm.uk"

    class Ride(val lineId: Int, val tripId: Long, val depSec: Long)

    // A trip shared by its id, which its server turns back into the trip (resolve). host: a Kav link's server, Kav's own
    // or a copy of it (server/selfhost).
    class Shared(val source: Source, val id: String, val host: String = HOST)

    class Plan(
        val fromName: String?, val fromLat: Double?, val fromLon: Double?,
        val toName: String?, val toLat: Double?, val toLon: Double?,
        val departMs: Long,
        val autoRun: Boolean,
        val rides: List<Ride> = emptyList(),
        val shared: Shared? = null,
        val exact: KavPlan.Planned? = null,
    )

    // A shared trip its app no longer has.
    class Expired : java.io.IOException("This shared trip is no longer available")

    // A link naming a server a shared trip is never fetched from (allowedHost).
    class NotKavs : java.io.IOException("")

    // The only servers a shared trip is fetched from: Kav's own, and the rider's own server exactly as set in Settings,
    // its port included. A host from anywhere else (a link, a QR code, a message) is never asked. Whole names compared
    // after lower case and a final dot: no ports on Kav's, no users, no parts of names, no look-alikes or addresses.
    fun allowedHost(host: String?): String? {
        val h = host?.lowercase(Locale.US)?.removeSuffix(".")?.takeIf { it.matches(Regex("[a-z0-9.-]{1,253}(:[0-9]{1,5})?")) }
            ?: return null
        val kav = listOf(HOST, OLD_HOST, URI(KavLive.KAV_SERVER).host, URI(KavLive.OLD_KAV_SERVER).host)
        val own = runCatching { URI(KavLive.ownServer).takeIf { it.scheme == "https" }?.rawAuthority?.lowercase(Locale.US) }
            .getOrNull()?.removeSuffix(".")
        return h.takeIf { it in kav || (!own.isNullOrEmpty() && it == own) }
    }

    fun parse(url: String?): Plan? {
        if (url.isNullOrBlank() || url.length > 262_144) return null
        val trimmed = url.trim()
        return if (trimmed.startsWith("geo:", ignoreCase = true)) geo(trimmed.substring(4)) else kav(trimmed)
    }

    // The first link in a text shared to Kav that opens a trip.
    fun inText(text: String?): Plan? = text?.let { Regex("https?://[^\\s<>\"']+").findAll(it) }
        ?.firstNotNullOfOrNull { parse(it.value.trimEnd('.', ',', ')', '!', '?')) }

    // The trip a shared link names, from the app that shared it, asked as that app opens its own link.
    fun resolve(s: Shared): Plan = when {
        s.source == Source.KAV -> saved(TripShare.read(s.id, s.host)) ?: throw Expired()
        else -> throw IllegalArgumentException("${s.source.key} shares no trips")
    }

    // A short code resolves off the main thread.
    // Kav's own links, a link on the rider's own server (the address set in Settings), and kav://trip?host=...&id=..., which a
    // copy's link page opens the app with (server/selfhost), naming the server the trip is on.
    private fun kav(url: String): Plan? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme == "kav" && uri.host == "trip") {
            val q = uri.rawQuery.orEmpty().split('&').mapNotNull { p ->
                p.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to URLDecoder.decode(v, "UTF-8") }
            }.toMap()
            val host = allowedHost(q["host"]) ?: return null
            val id = q["id"]?.takeIf { it.matches(Regex("[A-Za-z0-9]{7}")) } ?: return null
            return Plan(null, null, null, null, null, null, 0L, true, shared = Shared(Source.KAV, id, host))
        }
        if (uri.scheme?.lowercase(Locale.US) != "https" || uri.userInfo != null || uri.port != -1) return null
        val host = allowedHost(uri.host) ?: return null
        val id = uri.rawPath.orEmpty().removePrefix("/")
        if (!id.matches(Regex("[A-Za-z0-9]{7}")) || uri.rawFragment != null || uri.rawQuery != null) return null
        return Plan(null, null, null, null, null, null, 0L, true, shared = Shared(Source.KAV, id, host))
    }

    internal fun saved(o: JSONObject): Plan? {
        val p = o.keys().asSequence().associateWith { o.optString(it) }
        val to = p["to"]?.let(::point)
        val toName = p["toName"]?.takeIf { it.isNotBlank() }
        if (to == null) return null
        val from = p["from"]?.let(::point)
        val at = p["at"]?.toLongOrNull()?.takeIf { it in 1..4_102_444_800L } ?: 0L
        val exact = TripShare.decode(p["data"].orEmpty()) ?: return null
        return Plan(
            p["fromName"]?.takeIf { it.isNotBlank() }, from?.first, from?.second, toName, to?.first, to?.second,
            departMs = at * 1000, autoRun = true, rides = ridesOf(p["trip"]), exact = exact,
        )
    }

    private fun ridesOf(v: String?): List<Ride> {
        if (v.isNullOrBlank()) return emptyList()
        return v.split('~').map { part ->
            val bits = part.split('.')
            if (bits.size != 3) return emptyList()
            Ride(
                bits[0].toIntOrNull() ?: return emptyList(), bits[1].toLongOrNull() ?: return emptyList(),
                bits[2].toLongOrNull() ?: return emptyList(),
            )
        }
    }

    private fun point(text: String): Pair<Double, Double>? {
        val parts = text.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return (lat to lon).takeIf { lat != 0.0 || lon != 0.0 }
    }

    // A point typed or pasted as text, as maps copy it: "32.0853, 34.7818", or with a space for the comma.
    fun coordinates(text: String): Pair<Double, Double>? =
        Regex("^\\s*(-?\\d{1,2}\\.\\d+)\\s*[,\\s]\\s*(-?\\d{1,3}\\.\\d+)\\s*$").find(text)
            ?.let { point("${it.groupValues[1]},${it.groupValues[2]}") }

    // geo: links (RFC 5870, plus Android's ?q=): a point, a point with "(label)", or a place name to search for.
    // Kav plans the way there from where you are, as apps like GeoShare expect of a navigation app.
    private fun geo(body: String): Plan? {
        val q = parameters(body.substringAfter('?', "").substringBefore('#'))["q"]?.trim().orEmpty()
        val labelled = Regex("^(-?[\\d.]+)\\s*,\\s*(-?[\\d.]+)\\s*(?:\\((.*)\\))?$").find(q)
        val at = labelled?.let { point("${it.groupValues[1]},${it.groupValues[2]}") }
            ?: point(body.substringBefore('?').substringBefore(';'))
        val name = if (labelled != null) labelled.groupValues[3].takeIf { it.isNotBlank() } else q.takeIf { it.isNotBlank() }
        if (at == null && name == null) return null
        return Plan(null, null, null, name, at?.first, at?.second, 0L, autoRun = true)
    }

    private fun parameters(query: String): Map<String, String> = buildMap {
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key.isNotEmpty() && key !in this) put(key, runCatching {
                URLDecoder.decode(pair.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
            }.getOrDefault(""))
        }
    }
}
