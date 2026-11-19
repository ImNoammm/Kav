package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

// A route rule of a native extension's (Native.hook): the search the pipeline sends (Bridge) as the JSON its code gets,
// and its answer, an array, written back in the binary form the pipeline reads and checks.
object NativeRules {
    private val KINDS = listOf("walk", "wait", "ride", "taxi", "bike", "other")
    private val ROLES = listOf("access", "transfer", "egress", "only")
    private val PLACES = listOf("place", "stop", "station")

    private class In(bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val strings: List<String>
        private var at = 3

        init {
            if (bytes.size < 12 || b.getInt(0) != Bridge.MAGIC) throw Bridge.Bad("not a bridge message")
            var p = b.getInt(4)
            strings = List(b.getInt(8)) {
                val len = b.getInt(p * 4)
                val s = String(CharArray(len) { k -> b.getChar((p + 1) * 4 + k * 2) })
                p += 1 + (len + 1) / 2
                s
            }
        }

        fun int() = b.getInt(at++ * 4)
        fun str(): String? = int().let { if (it < 0) null else strings[it] }
    }

    fun search(bytes: ByteArray): JSONObject {
        val r = In(bytes)
        val query = JSONObject().put("way", r.int().toChar().toString()).put("at", r.int()).put("now", r.int())
            .put("settings", r.str()).put("locale", r.str())
        fun point() = JSONObject().put("stop", r.str()).put("name", r.str()).put("lat", r.int() / 1e6).put("lon", r.int() / 1e6)
        val routes = JSONArray()
        repeat(r.int()) { i ->
            val route = JSONObject().put("index", i).put("provider", r.str()).put("dep", r.int()).put("arr", r.int())
                .put("fare", r.int()).put("accessible", r.int() == 1)
            route.put("tags", JSONArray().apply { repeat(r.int()) { put(r.str()) } })
            val legs = JSONArray()
            repeat(r.int()) {
                val leg = JSONObject().put("kind", KINDS.getOrElse(r.int()) { "other" }).put("dep", r.int()).put("arr", r.int())
                    .put("metres", r.int()).put("insideMetres", r.int())
                leg.put("from", point()).put("to", point())
                leg.put("line", r.str()).put("lineNumber", r.str()).put("mode", r.int()).put("trip", r.str()).put("stops", r.int())
                legs.put(leg)
            }
            routes.put(route.put("legs", legs))
        }
        val walks = JSONArray()
        repeat(r.int()) {
            walks.put(JSONObject().put("route", r.int()).put("leg", r.int()).put("role", ROLES.getOrNull(r.int())).put("metres", r.int())
                .put("insideMetres", r.int()).put("plannedSec", r.int()).put("fromKind", PLACES.getOrNull(r.int())).put("toKind", PLACES.getOrNull(r.int())))
        }
        val transfers = JSONArray()
        repeat(r.int()) {
            transfers.put(JSONObject().put("route", r.int()).put("fromLeg", r.int()).put("toLeg", r.int()).put("fromMode", r.int())
                .put("toMode", r.int()).put("walkMetres", r.int()).put("walkSec", r.int()).put("gapSec", r.int())
                .put("sameStop", r.int() == 1).put("fromStop", r.str()).put("toStop", r.str()))
        }
        return JSONObject().put("query", query).put("routes", routes).put("walks", walks).put("transfers", transfers)
    }

    // Its answer to hook for this search, written for the pipeline. A wrong answer throws.
    fun answer(hook: String, search: JSONObject, text: String): ByteArray {
        val w = Bridge.Writer()
        fun str(s: String?) = w.str(s?.take(4096))
        fun list(n: String): JSONArray {
            val a = JSONArray(text)
            val want = search.getJSONArray(n).length()
            if (a.length() != want) throw IllegalArgumentException("$hook must answer with an array of $want")
            w.int(a.length())
            return a
        }
        fun whole(v: Any?): Int = (v as? Number)?.toDouble()?.roundToInt() ?: throw IllegalArgumentException("not a number: $v")
        fun truthy(v: Any?) = v == true || (v is String && v.isNotEmpty()) || (v is Number && v.toDouble() != 0.0)
        when (hook) {
            Pipeline.WALK -> list("walks").let { a -> for (k in 0 until a.length()) a.get(k).let { x -> w.int(whole(if (x is JSONObject) x.opt("sec") else x)) } }
            Pipeline.TRANSFER -> list("transfers").let { a ->
                for (k in 0 until a.length()) {
                    val x = a.get(k).let { if (it is Number) JSONObject().put("minSpareSec", it) else it as? JSONObject ?: JSONObject() }
                    w.int(x.opt("minSpareSec")?.let { if (it is Number) whole(it) else 0 } ?: 0)
                    val forbid = x.opt("forbid")
                    w.int((if (x.opt("hard") == false) 0 else 1) or (if (truthy(forbid)) 2 else 0))
                    str(forbid as? String)
                }
            }
            Pipeline.FILTER -> list("routes").let { a ->
                for (k in 0 until a.length()) {
                    val x = a.get(k)
                    w.int(if (x == false || x is String) 1 else 0)
                    str(x as? String)
                }
            }
            Pipeline.RANK -> list("routes").let { a -> for (k in 0 until a.length()) a.get(k).let { x -> w.int(whole(if (x is JSONObject) x.opt("index") else x)) } }
            Pipeline.ANNOTATE -> list("routes").let { a ->
                for (k in 0 until a.length()) {
                    val x = a.get(k)
                    val tags = (x as? JSONArray ?: (x as? JSONObject)?.optJSONArray("tags") ?: JSONArray())
                    val n = minOf(tags.length(), Pipeline.MOST_TAGS)
                    w.int(n)
                    for (t in 0 until n) str(tags.get(t).toString())
                }
            }
            else -> throw IllegalArgumentException("unknown hook $hook")
        }
        return w.bytes()
    }
}
