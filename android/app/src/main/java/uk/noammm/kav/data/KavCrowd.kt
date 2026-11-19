package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// How busy a line usually is at an hour of the week, from Kav's server (live.py busy/): the Ministry of Transport's count
// of Rav-Kav taps on each line, direction and hour of a month, per trip on the road then, told against the line's own
// busiest hours. Taps are boardings, not riders on board, so it never says how full a bus is. Asked for the routes a
// screen shows, from the server live times come from (Kav's unless the rider's own), else Kav's; nothing goes to Google.
object KavCrowd {
    var enabled by mutableStateOf(false)

    // A route's levels: by kind of day ("weekday" Sunday to Thursday, "friday", "saturday"), an hour a character ("3" one
    // of its busiest hours, "2" about average, "1" one of its quieter, "q" a line that's never busy, "0" too little to
    // say), and the month they're from ("2026-10").
    class Levels(val month: String, val byKind: Map<String, String>)

    // Each route asked about: its levels, or null where the server has none for it.
    private var levels by mutableStateOf<Map<Int, Levels?>>(emptyMap())
    // When each route was last asked about, answered or not.
    private val asked = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private const val KEEP_MS = 6 * 3600_000L // the levels change once a month
    private const val RETRY_MS = 10 * 60_000L // after no answer
    private const val MOST = 16 // routes in one question, as the server takes

    // Asks about the routes not asked about lately, at most MOST at a time.
    suspend fun refresh(routes: Collection<Int>) = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext
        val now = System.currentTimeMillis()
        val due = routes.distinct().filter { r -> asked[r]?.let { now - it >= if (levels.containsKey(r)) KEEP_MS else RETRY_MS } ?: true }
        for (part in due.sorted().chunked(MOST)) {
            part.forEach { asked[it] = now }
            val body = KavLive.busy(part) ?: continue
            val got = runCatching { parse(body) }.getOrNull() ?: continue
            synchronized(this@KavCrowd) { levels = levels + part.associateWith { got[it] } }
        }
    }

    // How busy the route usually is at that time (seconds), as the meter shows it; null when there's nothing to say.
    fun at(route: Int?, at: Long): GoogleCrowd.Crowd.Usual? {
        if (!enabled || route == null || at <= 0) return null
        val l = levels[route] ?: return null
        return levelAt(l, at)?.let { GoogleCrowd.Crowd.Usual(it, l.month) }
    }

    internal fun parse(body: String): Map<Int, Levels> {
        val o = JSONObject(body)
        val month = o.optString("from").take(7)
        val routes = o.optJSONObject("routes") ?: JSONObject()
        val out = HashMap<Int, Levels>()
        for (r in routes.keys()) {
            val kinds = routes.optJSONObject(r) ?: continue
            out[r.toIntOrNull() ?: continue] = Levels(month, kinds.keys().asSequence().associateWith { kinds.optString(it) })
        }
        return out
    }

    // The level at that time in Israel: its kind of day and its hour.
    internal fun levelAt(l: Levels, at: Long): Char? {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Jerusalem")).apply { timeInMillis = at * 1000 }
        val kind = when (c.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.FRIDAY -> "friday"
            java.util.Calendar.SATURDAY -> "saturday"
            else -> "weekday"
        }
        return l.byKind[kind]?.getOrNull(c.get(java.util.Calendar.HOUR_OF_DAY))?.takeIf { it in "123q" }
    }
}
