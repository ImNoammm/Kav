package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject

// A board provider's answers read and checked by Kav (Hooks.NativeBoard): nothing an extension sends is shown as it
// came. Ids are short plain text; names are cleaned like any publisher text (Names.clean) and can't pass as Kav; places
// are real coordinates; times are whole seconds; and there are limits on how many. Anything else is Malformed, never an
// empty board.
object BoardAnswers {
    const val MOST_STOPS = 200_000
    const val MOST_LINES = 20_000
    private const val MOST_ID = 128
    // No time past the year 2100: a provider that answers year 3000 or Long.MAX_VALUE has its answer refused.
    const val LAST_TIME = 4_102_444_800L

    class Bad(why: String) : Exception(why)

    private fun id(v: Any?, what: String): String {
        val s = (v as? String) ?: (v as? Number)?.let { if (it.toDouble() == Math.floor(it.toDouble())) it.toLong().toString() else null } ?: throw Bad("$what id")
        if (s.isEmpty() || s.length > MOST_ID || s.any { it.isISOControl() || it.isWhitespace() && it != ' ' }) throw Bad("$what id")
        return s
    }

    private fun name(v: Any?): String = (v as? String).orEmpty()

    private fun mode(v: Any?) = ((v as? Number)?.toInt() ?: 3).takeIf { it in 0..12 } ?: 3

    fun catalogue(text: String): Outcome<Catalogue> = try {
        val o = JSONObject(text)
        val a = o.optJSONArray("stops") ?: throw Bad("no stops")
        if (a.length() > MOST_STOPS) throw Bad("too many stops")
        val stops = (0 until a.length()).map { k ->
            val s = a.optJSONObject(k) ?: throw Bad("stop $k")
            val lat = s.optDouble("lat", Double.NaN); val lon = s.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) throw Bad("stop $k place")
            val n = name(s.opt("name"))
            if (n.isEmpty()) throw Bad("stop $k name")
            StopEntry(id(s.opt("id"), "stop"), n, lat, lon, name(s.opt("code")), mode(s.opt("mode")))
        }
        if (stops.map { it.id }.toSet().size != stops.size) throw Bad("two stops with one id")
        val l = o.optJSONObject("lines") ?: JSONObject()
        if (l.length() > MOST_LINES) throw Bad("too many lines")
        val lines = l.keys().asSequence().associateWith { k ->
            val x = l.optJSONObject(k) ?: throw Bad("line $k")
            LineEntry(id(k, "line"), name(x.opt("number")), name(x.opt("name")), mode(x.opt("mode")))
        }
        if (stops.isEmpty()) Outcome.Empty else Outcome.Ok(Catalogue(stops, lines))
    } catch (e: Exception) {
        Outcome.Malformed(e.message ?: "bad stop list")
    }

    // Optional positions use degrees and Unix seconds, just like the plan contract.
    fun vehicle(o: JSONObject?): Vehicle? {
        if (o == null) return null
        val lat = o.optDouble("lat", Double.NaN)
        val lon = o.optDouble("lon", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Vehicle(lat, lon, o.optLong("at").coerceIn(0, LAST_TIME), o.optInt("bearing", -1).takeIf { it in 0..359 } ?: -1)
    }

    fun departures(text: String, limit: Int): Outcome<List<BoardDeparture>> = try {
        if (text == "null") Outcome.Empty
        else {
            val a = JSONArray(text)
            if (a.length() > limit) throw Bad("more than $limit departures")
            val out = (0 until a.length()).map { k ->
                val d = a.optJSONObject(k) ?: throw Bad("departure $k")
                val at = d.opt("time")
                if (at !is Number || at.toDouble() != Math.floor(at.toDouble()) || at.toLong() !in 1..LAST_TIME) throw Bad("departure $k time")
                val est = d.opt("estimated")
                if (est != null && (est !is Number || est.toLong() !in 0..LAST_TIME)) throw Bad("departure $k estimate")
                BoardDeparture(id(d.opt("line"), "line"), id(d.opt("trip") ?: "${d.opt("line")}@$at", "trip"), at.toLong(),
                    (est as? Number)?.toLong() ?: 0L, name(d.opt("platform")), name(d.opt("headsign")), vehicle(d.optJSONObject("vehicle")))
            }
            if (out.isEmpty()) Outcome.Empty else Outcome.Ok(out.sortedBy { if (it.estimatedS > 0) it.estimatedS else it.scheduledS })
        }
    } catch (e: Exception) {
        Outcome.Malformed(e.message ?: "bad departures")
    }
}
