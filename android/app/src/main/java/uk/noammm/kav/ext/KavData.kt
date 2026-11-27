package uk.noammm.kav.ext

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Ride
import uk.noammm.kav.data.TicketScreens

// What an extension may ask Kav for that Kav already has (Kav.stops, Kav.vehicles, Kav.departures), and the rides it puts
// in Kav's ride history (Kav.addRides). Each is public transit data, or the extension's own: nothing about the rider's
// place, trips or tickets is given. JSON text in and out, as the rest of the API.
object KavData {
    const val STOP = "il.mot/gtfs/stop/"
    private const val STOPS_MOST = 200
    private const val BUS = 3
    private const val DEPARTURES_MOST = 100

    // -- Stops: Kav's timetable on the phone ---------------------------------------------------------------------------

    // {"near": [lat, lon], "name", "mode", "code", "limit"}: the stops that match all that is given, nearest first when
    // near is given, else by name. mode is the GTFS route type of the stop's lines (a stop served by several kinds is
    // the lowest, as Kav's own marks: a train station 2). Each {"stop", "code", "name", "nameHe", "city", "lat", "lon",
    // "mode", "platform"}, with "metres" when near is given; name as Kav shows it, nameHe the timetable's own. At most
    // limit, 200 at most; a mode other than bus has no cap, so a list of every station of one is whole.
    fun stops(net: Net, query: JSONObject): JSONArray {
        val near = query.optJSONArray("near")?.let { a -> a.optDouble(0) to a.optDouble(1) }?.takeIf { !it.first.isNaN() && !it.second.isNaN() }
        val words = query.optString("name").trim().takeIf { query.has("name") && it.isNotEmpty() }
        val mode = if (query.has("mode")) query.optInt("mode", -1) else null
        val code = if (query.has("code")) query.optInt("code", -1) else null
        val limit = query.optInt("limit", 20).coerceIn(1, if (mode != null && mode != BUS) maxOf(1, net.nStops) else STOPS_MOST)
        val types = if (mode != null) net.stopType else null
        val metres = DoubleArray(net.nStops)
        val found = ArrayList<Int>()
        for (s in 0 until net.nStops) {
            if (code != null && net.code[s] != code) continue
            if (types != null && types[s] != mode) continue
            if (words != null && !net.name[s].contains(words, true) && net.nameEn.getOrNull(s)?.contains(words, true) != true) continue
            if (near != null) metres[s] = distance(near.first, near.second, net.lat[s], net.lon[s])
            found.add(s)
        }
        val ordered = if (near != null) found.sortedBy { metres[it] } else found.sortedBy { net.stopName(it) }
        return JSONArray(ordered.take(limit).map { s ->
            JSONObject().put("stop", STOP + net.code[s]).put("code", net.code[s]).put("name", net.stopName(s)).put("nameHe", net.name[s]).put("city", net.cityName(s))
                .put("lat", net.lat[s]).put("lon", net.lon[s]).put("mode", net.stopType[s]).put("platform", net.platform.getOrElse(s) { "" })
                .apply { if (near != null) put("metres", Math.round(metres[s])) }
        })
    }

    // The stop's index in the timetable from its reference ("il.mot/gtfs/stop/<code>"), -1 when it has none. A bus
    // station's platforms share one code: the first is the station's own.
    fun stopIndex(net: Net, ref: String): Int {
        val code = ref.takeIf { it.startsWith(STOP) }?.removePrefix(STOP)?.toIntOrNull() ?: return -1
        return net.code.indexOfFirst { it == code }
    }

    // Metres along the ground, close enough for "nearest".
    internal fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    // -- Live vehicles: what Kav's Live tab and a line's page show ------------------------------------------------------

    // {"line": "<number>"}: every vehicle on the routes with that number; or {"near": [lat, lon], "km"}: those around a
    // place (Kav's server is told only the box around it, in whole hundredths of a degree, as for the Live tab). Both
    // line and near: the line's vehicles, nearest the place first. Each {"key", "plate", "route", "line", "to",
    // "agency", "mode", "lat", "lon", "bearing", "speed", "at"}, with "metres" when near is given.
    fun vehicles(query: JSONObject): JSONArray {
        val number = query.optString("line").trim()
        val near = query.optJSONArray("near")?.let { a -> a.optDouble(0) to a.optDouble(1) }?.takeIf { !it.first.isNaN() && !it.second.isNaN() }
        val area = when {
            number.isNotEmpty() -> KavLive.line(number)
            near != null -> KavLive.area(KavLive.box(near.first, near.second, query.optDouble("km", 1.0).coerceIn(0.1, 20.0)))
            else -> throw IllegalArgumentException("vehicles needs \"line\" or \"near\"")
        }
        return vehiclesJson(area, near)
    }

    internal fun vehiclesJson(area: KavLive.Area, near: Pair<Double, Double>? = null): JSONArray {
        val metres = near?.let { (lat, lon) -> area.buses.associateWith { distance(lat, lon, it.spot.lat, it.spot.lon) } }
        val buses = if (metres != null) area.buses.sortedBy { metres[it] } else area.buses
        return JSONArray(buses.map { b -> vehicleJson(area, b).apply { metres?.get(b)?.let { put("metres", Math.round(it)) } } })
    }

    private fun vehicleJson(area: KavLive.Area, b: KavLive.Bus): JSONObject {
        val l = area.lines[b.route]
        return JSONObject().put("key", b.key).put("plate", KavLive.plateOf(b.key) ?: JSONObject.NULL).put("route", b.route)
            .put("line", l?.number ?: JSONObject.NULL).put("to", l?.to ?: JSONObject.NULL).put("agency", l?.agency ?: JSONObject.NULL)
            .put("mode", l?.type ?: JSONObject.NULL).put("lat", b.spot.lat).put("lon", b.spot.lon)
            .put("bearing", b.spot.bearing).put("speed", b.spot.speed).put("at", b.spot.at)
    }

    // -- A stop's departures: its board -------------------------------------------------------------------------------

    // {"stop", "limit"}: its next departures as its board in Kav shows them now (ui/Stations.kt boardJson). Null when
    // the timetable hasn't loaded or Kav doesn't know the stop.
    fun departures(ctx: Context, net: Net, query: JSONObject): JSONArray? {
        val stop = stopIndex(net, query.optString("stop"))
        if (stop < 0) return null
        val limit = query.optInt("limit", 20).coerceIn(1, DEPARTURES_MOST)
        return kotlinx.coroutines.runBlocking { uk.noammm.kav.ui.boardJson(ctx, net, stop, limit) }
    }

    // -- Rides into Kav's ride history --------------------------------------------------------------------------------

    // An extension's rides already over, as Kav's ride history keeps them: each {"key", "kind", "line", "from", "to",
    // "at", "riders", "band", "price"}, kind "BUS", "TRAIN" or "LIGHT_RAIL", at in milliseconds, band the fare's reach
    // in km and price in agorot (one to take out is {"key", "removed": true}, KavData.removed). The key is
    // the extension's own, kept as "ext:<its identity>:<key>" as its tickets are, so no extension reaches another's
    // rides or those Kav read. A ride that isn't right throws.
    internal fun ride(identity: String, o: JSONObject): Ride {
        fun text(k: String) = o.optString(k).trim().takeIf { o.has(k) && !o.isNull(k) && it.isNotEmpty() }?.take(200)
        val id = text("key") ?: throw IllegalArgumentException("a ride needs a key")
        val kind = when (o.optString("kind")) {
            "BUS" -> TicketScreens.Kind.BUS
            "LIGHT_RAIL" -> TicketScreens.Kind.LIGHT_RAIL
            // A train ride over is the one its exit ticket was for.
            "TRAIN" -> TicketScreens.Kind.TRAIN_EXIT
            else -> throw IllegalArgumentException("kind is BUS, TRAIN or LIGHT_RAIL")
        }
        val at = o.optLong("at", 0).takeIf { it > 0 } ?: throw IllegalArgumentException("a ride needs at, in milliseconds")
        return Ride(uk.noammm.kav.ExtTickets.key(identity, id), kind, text("line"), text("from"), text("to"), at,
            o.optInt("riders", 1).coerceIn(1, 20), band = if (o.has("band") && !o.isNull("band")) o.optInt("band").takeIf { it > 0 } else null,
            price = if (o.has("price") && !o.isNull("price")) o.optInt("price").takeIf { it >= 0 } else null)
    }

    // {"key", "removed": true}: that ride of the extension's taken out of the history (a ride its pay service cancelled),
    // as "ext:<its identity>:<key>"; null for a ride to add. A removal with no key throws.
    internal fun removed(identity: String, o: JSONObject): String? {
        if (!o.optBoolean("removed")) return null
        val id = o.optString("key").trim().takeIf { o.has("key") && !o.isNull("key") && it.isNotEmpty() }?.take(200)
            ?: throw IllegalArgumentException("a ride needs a key")
        return uk.noammm.kav.ExtTickets.key(identity, id)
    }

    // The history with the keys removed taken out, then the extension's rides added, each one already there (by key)
    // brought up to date, keeping the fare's reach Kav had for it when the extension gives none.
    internal fun withRides(history: List<Ride>, rides: List<Ride>, removed: Set<String> = emptySet()): List<Ride> {
        val gone = removed.mapTo(HashSet(), ::family)
        val out = ArrayList<Ride>(history.size + rides.size)
        val families = ArrayList<String>(history.size + rides.size)
        for (h in history) family(h.key).let { f -> if (f !in gone) { out.add(h); families.add(f) } }
        for (r in rides) {
            val f = family(r.key)
            val at = families.indexOf(f)
            if (at < 0) { out.add(r); families.add(f); continue }
            val band = r.band ?: families.indices.lastOrNull { families[it] == f && out[it].band != null }?.let { out[it].band }
            out[at] = r.copy(band = band)
            for (i in families.indices.reversed()) if (i != at && families[i] == f) { out.removeAt(i); families.removeAt(i) }
        }
        return out
    }

    // A bundle file never updates (Hub.previewFile): a newer file of an extension installs it under an identity of its
    // own, "file.<digest>/<name>". Its rides are the same rides, so a ride of an extension from a bundle file is the same
    // ride as that key of an extension of that name from any bundle file ("ext:file.*/<name>:<key>"). Every other key is
    // only itself.
    internal fun family(key: String): String {
        if (!key.startsWith(FILE_RIDE)) return key
        val identity = key.removePrefix(uk.noammm.kav.ExtTickets.PREFIX).substringBefore(':', "")
        val name = identity.substringAfter('/', "").takeIf { it.isNotEmpty() } ?: return key
        return "$FILE_RIDE*/$name:" + key.removePrefix("${uk.noammm.kav.ExtTickets.PREFIX}$identity:")
    }

    private const val FILE_RIDE = "ext:file."
}
