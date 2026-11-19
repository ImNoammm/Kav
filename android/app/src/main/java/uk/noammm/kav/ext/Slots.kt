package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.data.Transit
import java.io.File

// Slots: places on Kav's own screens where an extension puts a card of its own, the view its code makes (Kav.card,
// ext/Native.kt). A package declares a slot as a contribution, {"id": "card", "capability": "slot.home"}.
//
// A slot shows from install (OPEN-PLAN section 3): the rider can turn one off on the extension's page (Picks), and they
// show in install order. A card is told what its screen shows, in the shape route rules get (Pipeline.routeJson): the
// trip with its coordinates and stop references, one route of a search's results (a results card, under each route),
// or the stop.
object Slots {
    enum class Kind(val capability: String, val key: String) {
        HOME("slot.home", "home"),
        RESULTS("slot.results", "results"),
        TRIP("slot.trip", "trip"),
        STOP("slot.stop", "stop");

        companion object {
            fun of(capability: String) = entries.firstOrNull { it.capability == capability }
        }
    }

    val CAPABILITIES get() = Kind.entries.map { it.capability }

    fun declared(m: Manifest, k: Kind): Manifest.Declared? = m.contributions.firstOrNull { it.capability == k.capability }

    // -- The rider's switches ------------------------------------------------------------------------------------------

    // The slots showing, by extension, in install order ("<identity>|<slot key>"), and the ones the rider turned off.
    // A slot an installed release declares shows unless the rider turned it off (declare). Its own file.
    object Picks {
        private const val FORMAT = 2
        @Volatile private var file: File? = null
        @Volatile var on: List<String> = emptyList()
            private set
        @Volatile private var off: Set<String> = emptySet()

        fun open(dir: File) {
            file = File(dir, "slots.json")
            val o = runCatching { JSONObject(file!!.readText()) }.getOrNull()
            // Format 1 kept only what the rider switched on: those stay on, the rest come on as they're declared.
            on = o?.optJSONArray("on")?.strings().orEmpty()
            off = if (o?.optInt("format") == FORMAT) o.optJSONArray("off")?.strings()?.toSet().orEmpty() else emptySet()
        }

        fun isOn(identity: String, k: Kind) = "$identity|${k.key}" in on

        // The extensions whose k slot shows, first installed first.
        fun of(k: Kind): List<String> = on.filter { it.endsWith("|${k.key}") }.map { it.substringBeforeLast('|') }

        @Synchronized fun set(dir: File, identity: String, k: Kind, value: Boolean) {
            val key = "$identity|${k.key}"
            on = if (value) (if (key in on) on else on + key) else on - key
            off = if (value) off - key else off + key
            save(dir)
        }

        // The slots these releases declare, on unless the rider turned them off. Nothing written when nothing changed.
        // True when it changed.
        @Synchronized fun declare(dir: File, installed: List<Pair<String, Manifest>>): Boolean {
            val add = installed.flatMap { (id, m) -> Kind.entries.filter { declared(m, it) != null }.map { "$id|${it.key}" } }
                .filter { it !in on && it !in off }
            if (add.isEmpty()) return false
            on = on + add
            save(dir)
            return true
        }

        // Every switch of one extension forgotten (it was uninstalled). Nothing written when it had none.
        @Synchronized fun drop(dir: File, identity: String) {
            fun mine(x: String) = x.substringBeforeLast('|') == identity
            if (on.none(::mine) && off.none(::mine)) return
            on = on.filterNot(::mine)
            off = off.filterNot(::mine).toSet()
            save(dir)
        }

        private fun save(dir: File) {
            val f = file ?: File(dir, "slots.json").also { file = it }
            Files.replace(f, JSONObject().put("format", FORMAT).put("on", JSONArray(on)).put("off", JSONArray(off.sorted())).toString().toByteArray())
        }

        internal fun forget() { on = emptyList(); off = emptySet(); file = null }
    }

    // -- What a slot is told -------------------------------------------------------------------------------------------

    // A trip, as a trip slot is told it: the route as route rules get it.
    fun trip(it: Transit.Itinerary, r: Transit.Resolved): JSONObject = Pipeline.routeJson(it, r)
}
