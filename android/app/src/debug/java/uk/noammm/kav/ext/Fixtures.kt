package uk.noammm.kav.ext

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Debug builds only: two local publishers with the very same ids, for the phase 1 collision test on an emulator. Off
// unless files/ext-fixtures.txt exists; its lines: "planner=<publisher/dataset>" picks the planner, "missing=<publisher>"
// leaves one out. Their stop ids are also real ministry stop codes, and their operator the ministry's id for Dan, so any
// lookup that takes them for the ministry's shows up as a wrong name, board or live time. Nothing here uses the network.
object Fixtures {
    private const val TAG = "KavExtFixture"

    // Phase 2's emulator run: a test block list and its key, from files/ext-test.txt ("blocklist-url=", "blocklist-key=").
    // KavApp calls this before ExtApp.start, which makes the hub, and the hub keeps the key it was made with.
    @JvmStatic fun keys(ctx: Context) {
        File(ctx.filesDir, "ext-test.txt").takeIf { it.exists() }?.readLines()?.forEach { l ->
            if (l.startsWith("blocklist-url=")) ExtApp.blockListUrl = l.removePrefix("blocklist-url=").trim()
            if (l.startsWith("blocklist-key=")) ExtApp.blockListKey = l.removePrefix("blocklist-key=").trim()
        }
    }

    @JvmStatic fun install(ctx: Context) {
        val f = File(ctx.filesDir, "ext-fixtures.txt")
        if (!f.exists()) return
        val lines = f.readLines().map { it.trim() }
        val missing = lines.filter { it.startsWith("missing=") }.map { it.removePrefix("missing=") }.toSet()
        for (p in listOf(ALPHA, BETA)) {
            if (p.ns.publisher in missing) continue
            Extensions.register(Contribution(p.ns, "planner", Capabilities.PLAN, "1", p))
            Extensions.register(Contribution(p.ns, "board", Capabilities.BOARD, "1", p.board))
        }
        Extensions.register(Contribution(Gamma.ns, "planner", Capabilities.PLAN, "1", Gamma))
        Extensions.register(Contribution(Gamma.ns, "board", Capabilities.BOARD, "1", Gamma.board))
        lines.firstOrNull { it.startsWith("planner=") }?.removePrefix("planner=")?.let(Namespace::of)
            ?.let { Selections.choosePlanner(ExtApp.dir(ctx), it) }
        Log.i(TAG, "installed, planner ${Extensions.selectedPlanner?.key}, missing $missing")
    }

    // One publisher's metro: a walk to stop 17038, line 7 to stop 25565, a walk on. Line 7 leaves every 15 minutes, shift
    // seconds past; each run's trip id is its number since 1970 in 15 minutes, the same ids for both publishers.
    class Publisher(
        val ns: Namespace, val operator: String, val stops: List<String>, val shift: Long, val late: Long, val nudge: Double,
    ) : PlanSource {
        override val versions = listOf(ContractVersion("plan", 2, 0))
        override val stopovers = false

        private fun dep(n: Long) = n * 900 + shift
        // The first run leaving after atS.
        private fun first(atS: Long) = Math.floorDiv(atS - shift, 900L) + 1

        override fun plan(q: PlanQuery, v: ContractVersion, e: Execution): Outcome<String> {
            Log.i(TAG, "plan ${e.namespace.key} ${e.contribution} gen ${e.generation} $v")
            val a = q.from.first + nudge to q.from.second + nudge
            val b = q.to.first - nudge to q.to.second - nudge
            val n = first(q.atS + 240)
            val dep = dep(n)
            val arr = dep + 900
            val walk1 = JSONObject().put("k", "w").put("dep", dep - 240).put("arr", dep).put("m", 300)
                .put("shape", encode(listOf(q.from, a)))
            val ride = JSONObject().put("k", "r").put("line", "7").put("trip", "$n").put("dep", dep).put("arr", arr)
                .put("sdep", dep).put("stops", JSONArray().put("17038").put("25565")).put("shape", encode(listOf(a, b)))
                .put("next", JSONArray().apply { for (k in n + 1..n + 2) put(JSONArray().put(dep(k)).put("$k")) })
            val walk2 = JSONObject().put("k", "w").put("dep", arr).put("arr", arr + 240).put("m", 300)
                .put("shape", encode(listOf(b, q.to)))
            val body = JSONObject()
                .put("stops", JSONObject()
                    .put("17038", JSONArray().put("17038").put(stops[0]).put(a.first).put(a.second))
                    .put("25565", JSONArray().put("25565").put(stops[1]).put(b.first).put(b.second)))
                .put("agencies", JSONObject().put("5", operator))
                .put("lines", JSONObject().put("7", JSONArray().put("7").put(stops[1]).put("").put(stops[0]).put("").put(5).put(3).put("")))
                .put("it", JSONArray().put(JSONObject().put("dep", dep - 240).put("arr", arr + 240)
                    .put("legs", JSONArray().put(walk1).put(ride).put(walk2))))
            return Outcome.Ok(body.toString())
        }

        val board = object : BoardSource {
            override val versions = listOf(ContractVersion("board", 1, 0))

            override fun departures(stop: String, fromS: Long, limit: Int, v: ContractVersion, e: Execution): Outcome<List<BoardDeparture>> {
                Log.i(TAG, "board ${e.namespace.key} stop $stop gen ${e.generation} $v")
                if (stop != "17038") return Outcome.Empty
                val n = first(fromS - 1)
                return Outcome.Ok((n until n + minOf(limit, 8)).map { k ->
                    BoardDeparture("7", "$k", dep(k), estimatedS = dep(k) + late, platform = ns.publisher.substringAfter('.').take(1).uppercase())
                })
            }
        }
    }

    val ALPHA = Publisher(Namespace("test.alpha", "metro"), "Alpha Ferries", listOf("Alpha Pier", "Alpha Market"), 300, 60, 0.002)
    val BETA = Publisher(Namespace("test.beta", "metro"), "Beta Trams", listOf("Beta Quay", "Beta Square"), 420, 120, 0.004)

    // Phase 3's emulator run: routes with a change, for the walking and transfer rules. Line 7 to Gamma Market, a 50 m
    // walk (planned 60 s) to Gamma Hill, then line 9 leaving 240, 660 or 900 s after line 7 arrives. A rule that says
    // the walk takes 600 s with 30 s to spare leaves the 240 s one uncatchable. Line 7 leaves every 15 minutes, 300 s past.
    object Gamma : PlanSource {
        val ns = Namespace("test.gamma", "metro")
        override val versions = listOf(ContractVersion("plan", 2, 0))
        override val stopovers = false
        private val gaps = listOf(240L, 660L, 900L)

        override fun plan(q: PlanQuery, v: ContractVersion, e: Execution): Outcome<String> {
            Log.i(TAG, "plan ${e.namespace.key} ${e.contribution} gen ${e.generation} $v")
            val a = q.from.first + 0.003 to q.from.second + 0.003
            val b = q.to.first - 0.004 to q.to.second - 0.004
            val c = b.first + 0.0003 to b.second + 0.0003
            val d = q.to.first - 0.001 to q.to.second - 0.001
            val n = Math.floorDiv(q.atS + 240 - 300, 900L) + 1
            val dep = n * 900 + 300
            val its = JSONArray()
            gaps.forEachIndexed { k, gap ->
                val dep9 = dep + 600 + gap
                its.put(JSONObject().put("dep", dep - 240).put("arr", dep9 + 840).put("legs", JSONArray()
                    .put(JSONObject().put("k", "w").put("dep", dep - 240).put("arr", dep).put("m", 300).put("shape", encode(listOf(q.from, a))))
                    .put(JSONObject().put("k", "r").put("line", "7").put("trip", "$n").put("dep", dep).put("arr", dep + 600).put("sdep", dep)
                        .put("stops", JSONArray().put("17038").put("25565")).put("shape", encode(listOf(a, b))))
                    .put(JSONObject().put("k", "w").put("dep", dep + 600).put("arr", dep + 660).put("m", 50).put("shape", encode(listOf(b, c))))
                    .put(JSONObject().put("k", "r").put("line", "9").put("trip", "${n * 10 + k}").put("dep", dep9).put("arr", dep9 + 600).put("sdep", dep9)
                        .put("stops", JSONArray().put("21460").put("21461")).put("shape", encode(listOf(c, d))))
                    .put(JSONObject().put("k", "w").put("dep", dep9 + 600).put("arr", dep9 + 840).put("m", 300).put("shape", encode(listOf(d, q.to))))))
            }
            val body = JSONObject()
                .put("stops", JSONObject()
                    .put("17038", JSONArray().put("17038").put("Gamma Pier").put(a.first).put(a.second))
                    .put("25565", JSONArray().put("25565").put("Gamma Market").put(b.first).put(b.second))
                    .put("21460", JSONArray().put("21460").put("Gamma Hill").put(c.first).put(c.second))
                    .put("21461", JSONArray().put("21461").put("Gamma Park").put(d.first).put(d.second)))
                .put("agencies", JSONObject().put("5", "Gamma Lines"))
                .put("lines", JSONObject()
                    .put("7", JSONArray().put("7").put("Gamma Market").put("").put("Gamma Pier").put("").put(5).put(3).put(""))
                    .put("9", JSONArray().put("9").put("Gamma Park").put("").put("Gamma Hill").put("").put(5).put(3).put("")))
                .put("it", its)
            return Outcome.Ok(body.toString())
        }

        val board = object : BoardSource {
            override val versions = listOf(ContractVersion("board", 1, 0))
            override fun departures(stop: String, fromS: Long, limit: Int, v: ContractVersion, e: Execution): Outcome<List<BoardDeparture>> = Outcome.Empty
        }
    }

    // Google's polyline encoding, at five decimals.
    private fun encode(points: List<Pair<Double, Double>>): String = buildString {
        var lastLat = 0L; var lastLon = 0L
        for ((lat, lon) in points) {
            val la = Math.round(lat * 1e5); val lo = Math.round(lon * 1e5)
            for (d in listOf(la - lastLat, lo - lastLon)) {
                var v = if (d < 0) (d shl 1).inv() else d shl 1
                while (v >= 0x20) { append(((0x20 or (v and 0x1f).toInt()) + 63).toChar()); v = v shr 5 }
                append((v + 63).toInt().toChar())
            }
            lastLat = la; lastLon = lo
        }
    }
}
