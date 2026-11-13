package uk.noammm.kav.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

// How busy a ride's bus usually is, from Google Maps' page for its boarding stop: each departure there carries Google's
// predicted crowding ("least", "less", "middle", higher up the scale 2..6), by the line and the time of day, not
// a count of who's on board. Shown as a meter with Kav's words for its five steps (CrowdLevel). Asked from the phone,
// only while a trip page, navigation or a line's page is open, a stop at most every ten minutes; Google
// sees the stop and the phone's address. A stop's page is found by Google's own id for it, which a directions reply
// gives with the stop's code and is kept for good.
object GoogleCrowd {
    var enabled by mutableStateOf(false)

    // A departure on a stop's page: its line, when, and its level 1 to 5.
    internal class Dep(val number: String, val at: Long, val level: Int)

    private class Board(val deps: List<Dep>, val at: Long)

    private var boards by mutableStateOf<Map<String, Board>>(emptyMap())
    // Stops whose page couldn't be had the last time it was asked for (Google refused, gave a limited view, is resting,
    // or has no page for the stop), until one comes.
    private var failed by mutableStateOf<Set<String>>(emptySet())
    // Stops a screen has asked about: one never asked about shows nothing rather than a wait that never ends.
    private var asked by mutableStateOf<Set<String>>(emptySet())

    private fun waiting(code: String): Crowd? = when (code) {
        in failed -> Crowd.Failed
        in asked -> Crowd.Asking
        else -> null
    }

    // What a card shows for a departure: its level, Google still being asked, asking failed, or Google has none for it.
    sealed interface Crowd {
        data class Level(val level: Int) : Crowd
        data object Asking : Crowd
        data object Failed : Crowd
        data object Unknown : Crowd
    }

    fun displayed(crowd: Crowd?, train: Boolean): Crowd? =
        crowd?.takeUnless { train && it !is Crowd.Level }
    private val ids = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var store: android.content.SharedPreferences? = null
    private val asking = Mutex()

    private const val FRESH_S = 10 * 60L
    private const val NEAR_S = 3 * 60L
    private const val HOUR_PART_S = 30 * 60L
    private const val LOG = "KavCrowd"

    fun init(ctx: Context) {
        if (store != null) return
        store = ctx.applicationContext.getSharedPreferences("google-stops", Context.MODE_PRIVATE).also { s ->
            s.all.forEach { (code, id) -> (id as? String)?.let { ids[code] = it } }
        }
    }

    // Google's ids for stops, from any directions reply (GoogleAlerts' too).
    fun learn(found: Map<String, String>) {
        val fresh = found.filter { (code, id) -> ids[code] != id }
        if (fresh.isEmpty()) return
        ids.putAll(fresh)
        store?.edit()?.apply { fresh.forEach { (code, id) -> putString(code, id) } }?.apply()
    }

    // A train goes by its line's two stations, as Google names it, not by the train's own number (100).
    private fun number(ride: Transit.Leg, r: Transit.Resolved) =
        (if (r.isTrain(ride)) r.line(ride.lineId)?.number?.trim()?.ifEmpty { null } else null)
            ?: ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }.trim().ifEmpty { null }

    // Google names a train by its two ends, each a station and its town ("נתב''ג-נמל תעופה בן גוריון<->נהריה-נהריה"),
    // Kav by its two stations ("נתב''ג - נהריה"): both come down to the stations, letters and digits only. A bus's
    // number stays as it is.
    internal fun lineKey(name: String): String {
        val ends = when {
            "<->" in name -> name.split("<->").map { it.substringBeforeLast('-') }
            " - " in name -> name.split(" - ")
            else -> return name.trim()
        }
        if (ends.size != 2) return name.trim()
        return ends.joinToString("|") { end -> end.filter { it.isLetterOrDigit() } }
    }

    // The ride's line's departure from its boarding stop. Null when it's off, or the stop or line isn't known.
    fun of(ride: Transit.Leg, r: Transit.Resolved, dep: Transit.Departure): Crowd? {
        val code = r.stop(ride.fromStop)?.code ?: return null
        val number = number(ride, r) ?: return null
        return crowdAt(code, number, listOf(dep.timeUtc, dep.staticUtc).filter { it > 0 }.distinct())
    }

    // A page already had keeps its levels while it's asked for again; none yet is being asked for, unless that failed.
    private fun crowdAt(code: String, number: String, times: List<Long>): Crowd? {
        if (!enabled || code.isEmpty() || number.isEmpty()) return null
        val board = boards[code] ?: return waiting(code)
        return levelAt(board.deps, lineKey(number), times)?.let { Crowd.Level(it) } ?: Crowd.Unknown
    }

    // That line's departure within three minutes of one of the times (when it's expected, when it's timetabled: Google
    // lists some by one, some by the other), else, as the level is the line's at that hour, its nearest within half an
    // hour of the first.
    internal fun levelAt(deps: List<Dep>, number: String, times: List<Long>): Int? {
        val line = deps.filter { it.number == number }
        val first = times.firstOrNull() ?: return null
        return times.firstNotNullOfOrNull { at -> line.filter { kotlin.math.abs(it.at - at) <= NEAR_S }.minByOrNull { kotlin.math.abs(it.at - at) } }?.level
            ?: line.filter { kotlin.math.abs(it.at - first) <= HOUR_PART_S }.minByOrNull { kotlin.math.abs(it.at - first) }?.level
    }

    // Each boarding stop of the rides shown whose page is older than ten minutes (Stop): its id first if it isn't kept
    // (the ride's directions give it), then the page.
    suspend fun refresh(rides: List<Pair<Transit.Leg, Transit.Resolved>>) =
        // Every ride on the trip shown: its card shows the line's next departures whenever the plan was made.
        refreshStops(rides.mapNotNull { (ride, r) ->
            val from = r.stop(ride.fromStop) ?: return@mapNotNull null
            val a = from.point ?: return@mapNotNull null
            val b = r.stop(ride.toStop)?.point ?: return@mapNotNull null
            Stop(from.code, a, b)
        })

    // A stop to ask about: its code, where it is, and a stop further along a line from it (for the directions that give
    // its Google id the first time).
    class Stop(val code: String, val at: Pair<Double, Double>, val toward: Pair<Double, Double>)

    suspend fun refreshStops(stops: List<Stop>) = withContext(Dispatchers.IO) {
        if (!enabled || stops.isEmpty()) return@withContext
        val wanted = stops.map { it.code }.filter { it.isNotEmpty() }.toSet()
        asked = asked + wanted
        // One at a time, each waiting its turn rather than dropped, which left a stop "checking" until its screen asked
        // again a minute later. Stops the one before brought in are fresh then, and not asked again.
        asking.lock()
        try {
            val now = System.currentTimeMillis() / 1000
            // Asked again: each shows as being asked until its turn ends.
            failed = failed - wanted
            for (stop in stops.distinctBy { it.code }) {
                val code = stop.code.ifEmpty { null } ?: continue
                if (boards[code]?.let { now - it.at < FRESH_S } == true) continue
                if (GoogleSession.paused) { failed = failed + code; continue }
                try {
                    val id = ids[code] ?: run {
                        learn(GoogleAlerts.stopIds(GoogleSession.spaced { GoogleAlerts.directions(stop.at, stop.toward) }))
                        ids[code]
                    }
                    val deps = id?.let { GoogleSession.spaced { page(it, code) } }
                    if (deps == null) {
                        if (android.util.Log.isLoggable(LOG, android.util.Log.DEBUG)) android.util.Log.d(LOG,
                            "stop $code: " + if (id == null) "Google's directions from it named no stop" else "its page was another stop's")
                        failed = failed + code; continue
                    }
                    // On with `adb shell setprop log.tag.KavCrowd DEBUG`: what each page had, its lines and how far ahead.
                    if (android.util.Log.isLoggable(LOG, android.util.Log.DEBUG)) android.util.Log.d(LOG,
                        "stop $code: ${deps.size} departures, ${deps.minOfOrNull { it.at }?.minus(now)?.div(60)} to " +
                            "${deps.maxOfOrNull { it.at }?.minus(now)?.div(60)} min, lines ${deps.map { it.number }.distinct()}")
                    boards = boards + (code to Board(deps, System.currentTimeMillis() / 1000))
                } catch (e: java.io.IOException) {
                    if (android.util.Log.isLoggable(LOG, android.util.Log.DEBUG)) android.util.Log.d(LOG, "stop $code: ${e.message}")
                    failed = failed + code
                } catch (e: org.json.JSONException) {
                    // A reply that isn't the list Google gives: failed like one that couldn't be asked for, not a crash.
                    if (android.util.Log.isLoggable(LOG, android.util.Log.DEBUG)) android.util.Log.d(LOG, "stop $code: ${e.message}")
                    failed = failed + code
                }
            }
        } finally {
            asking.unlock()
        }
    }

    // A line's departure from a stop around a time (a vehicle on the Live tab).
    fun levelOf(code: String, number: String, at: Long): Crowd? = crowdAt(code, number, listOf(at))

    // A line's next departure from a stop, as the stop's page has it (a line's page in Lines), with when it leaves.
    internal fun nextOf(code: String, number: String, now: Long): Pair<Crowd, Long?>? {
        if (!enabled || code.isEmpty() || number.isEmpty()) return null
        val board = boards[code] ?: return waiting(code)?.let { it to null }
        val key = lineKey(number)
        val next = board.deps.filter { it.number == key && it.at >= now - 60 }.minByOrNull { it.at } ?: return Crowd.Unknown to null
        return Crowd.Level(next.level) to next.at
    }

    // A stop's page, its departures with a level. Null when the page is another stop's (an id gone stale).
    private fun page(id: String, code: String): List<Dep>? {
        val url = "https://www.google.com/maps/preview/place?authuser=0&hl=en&gl=il&pb=" +
            URLEncoder.encode("!1m14!1s$id$PLACE_TAIL", "UTF-8")
        val body = GoogleSession.get(url) ?: throw java.io.IOException("Google refused the request")
        if (body.contains("limited view") || !body.startsWith(")]}'")) throw java.io.IOException("Google gave a limited view")
        val deps = departures(body, code)
        if (deps == null) ids.remove(code)?.let { store?.edit()?.remove(code)?.apply() }
        return deps
    }

    // root[6][62] is the stop: [12] its code; [1][group][2][line] a line, [5] holding [5, [number]], [1][way][3] its
    // departures, each [0][0][0] when and [0][10][2] [_, level 2 to 6] with [0][10][3] naming it "crowdedness". A
    // departure without one is unknown, never quiet.
    internal fun departures(body: String, code: String): List<Dep>? {
        val stop = JSONArray(body.substringAfter('\n')).optJSONArray(6)?.optJSONArray(62) ?: return null
        if (stop.optString(12).trim() != code) return null
        val out = ArrayList<Dep>()
        val groups = stop.optJSONArray(1) ?: return out
        for (g in 0 until groups.length()) {
            val lines = groups.optJSONArray(g)?.optJSONArray(2) ?: continue
            for (l in 0 until lines.length()) {
                val line = lines.optJSONArray(l) ?: continue
                var number = ""
                line.optJSONArray(5)?.let { e ->
                    for (i in 0 until e.length()) e.optJSONArray(i)?.takeIf { it.optInt(0) == 5 }?.let {
                        number = it.optJSONArray(1)?.optString(0).orEmpty().trim()
                    }
                }
                if (number.isEmpty()) continue
                val ways = line.optJSONArray(1) ?: continue
                for (w in 0 until ways.length()) {
                    val deps = ways.optJSONArray(w)?.optJSONArray(3) ?: continue
                    for (d in 0 until deps.length()) {
                        val dep = deps.optJSONArray(d)?.optJSONArray(0) ?: continue
                        val at = dep.optJSONArray(0)?.optLong(0) ?: continue
                        val info = dep.optJSONArray(10) ?: continue
                        val named = info.optJSONArray(3)?.let { a ->
                            (0 until a.length()).any { a.optJSONArray(it)?.optJSONArray(0)?.optString(0) == "crowdedness" && a.optJSONArray(it)?.length() ?: 0 > 1 }
                        } == true
                        // [4, 3] or [3, 2]: the second runs with the word ("least" 2, "less" 3, "middle" 4), the first doesn't.
                        val level = info.optJSONArray(2)?.optInt(1) ?: continue
                        if (named && at > 0 && level in 2..6) out += Dep(lineKey(number), at, level - 1)
                    }
                }
            }
        }
        return out
    }

    // The rest of the page request the Maps page itself sent (8 Nov 2026), after the stop's id.
    private const val PLACE_TAIL = "!3m12!1m3!1d13518.827742491887!2d34.961676!3d32.104202699999995!2m3!1f0.0!2f0.0!3f0.0!3m2!1i1024!2i768!4f13.1!" +
        "12m4!2m3!1i360!2i120!4i8!13m57!2m2!1i203!2i100!3m2!2i4!5b1!6m6!1m2!1i86!2i86!1m2!1i408!2i240!7m33!1m3!1e1!2b0!" +
        "3e3!1m3!1e2!2b1!3e2!1m3!1e2!2b0!3e3!1m3!1e8!2b0!3e3!1m3!1e10!2b0!3e3!1m3!1e10!2b1!3e2!1m3!1e10!2b0!3e4!1m3!1e9" +
        "!2b1!3e2!2b1!9b0!15m8!1m7!1m2!1m1!1e2!2m2!1i195!2i195!3i20!14m3!1syGnwao7fJLyBhbIPzM21wA0!7e81!15i10112!15m108" +
        "!1m26!13m9!2b1!3b1!4b1!6i1!8b1!9b1!14b1!20b1!25b1!18m15!3b1!4b1!5b1!6b1!13b1!14b1!17b1!21b1!22b1!30b1!32b1!33m" +
        "1!1b1!34b1!36e2!10m1!8e3!11m1!3e1!17b1!20m2!1e3!1e6!24b1!25b1!26b1!27b1!29b1!30m1!2b1!36b1!37b1!39m3!2m2!2i1!3" +
        "i1!43b1!52b1!54m1!1b1!55b1!56m1!1b1!61m2!1m1!1e1!65m5!3m4!1m3!1m2!1i224!2i298!72m22!1m8!2b1!5b1!7b1!12m4!1b1!2" +
        "b1!4m1!1e1!4b1!8m10!1m6!4m1!1e1!4m1!1e3!4m1!1e4!3sother_user_google_review_posts__and__hotel_and_vr_partner_re" +
        "view_posts!6m1!1e1!9b1!89b1!90m2!1m1!1e2!98m3!1b1!2b1!3b1!103b1!113b1!114m3!1b1!2m1!1b1!117b1!122m1!1b1!126b1!" +
        "127b1!128m1!1b1!21m0!22m1!1e81!30m8!3b1!6m2!1b1!2b1!7m2!1e3!2b1!9b1!34m5!7b1!10b1!14b1!15m1!1b0!37i802"
}
