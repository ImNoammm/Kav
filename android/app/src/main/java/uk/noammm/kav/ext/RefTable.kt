package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject

// Extension references as the Int and Long ids Transit and the screens already key by. A handle means something only
// in this run of Kav: anything saved keeps the references themselves (refs(), Remap) and gets new handles when read.
//
// The ranges are free of every other producer of ids (audited for phase 1, phase1-results.md):
//   KavPlan's ministry stops, lines and operators   2,000,000,000 and up
//   RailTrip's stops and lines                       2,100,000,000 and 2,110,000,000 and up
//   timetable (Net) indexes and LiveScreen's stops   small, or negative
//   KavLive's made-up trips and operators            negative
//   RailTrip's trains                                their train numbers, small
// So stop, route and agency handles are 1,000,000,000 to 1,999,999,999, and trip handles 2^48 and up. The ministry's
// own references are never put here: their ids stay KavPlan's, as before.
object RefTable {
    const val FIRST = 1_000_000_000
    const val END = 2_000_000_000
    const val FIRST_TRIP = 1L shl 48

    private class Entry(val ref: Ref, val variant: Int?) {
        val token get() = ref.toString() + (variant?.let { "#$it" } ?: "")
    }

    private val ids = HashMap<String, Long>()
    private val entries = HashMap<Long, Entry>()
    private val labels = HashMap<Long, String>()
    private var nextInt = FIRST.toLong()
    private var nextTrip = FIRST_TRIP

    fun isHandle(id: Int) = id in FIRST until END
    fun isTripHandle(id: Long) = id >= FIRST_TRIP

    // A stop's, route's or agency's handle. variant: for an agency, the kind of line, since the screens take a line's kind
    // from its operator.
    fun intern(ref: Ref, variant: Int? = null): Int {
        require(ref.kind != Kind.TRIP) { "trips have Long handles" }
        require(!ref.namespace.ministry) { "the ministry's ids are KavPlan's" }
        return handle(Entry(ref, variant), trip = false).toInt()
    }

    fun internTrip(ref: Ref): Long {
        require(ref.kind == Kind.TRIP) { "not a trip" }
        require(!ref.namespace.ministry) { "the ministry's ids are KavPlan's" }
        return handle(Entry(ref, null), trip = true)
    }

    @Synchronized private fun handle(e: Entry, trip: Boolean): Long = ids.getOrPut(e.token) {
        val id = if (trip) nextTrip++ else nextInt++.also { check(it < END) { "out of handles" } }
        entries[id] = e
        id
    }

    @Synchronized fun ref(id: Int): Ref? = if (isHandle(id)) entries[id.toLong()]?.ref else null
    @Synchronized fun tripRef(id: Long): Ref? = if (isTripHandle(id)) entries[id]?.ref else null

    // An operator's name, as its planner gave it.
    @Synchronized fun label(id: Int): String? = if (isHandle(id)) labels[id.toLong()] else null
    @Synchronized fun setLabel(id: Int, name: String) { if (isHandle(id)) labels[id.toLong()] = name }

    // For tests: as if Kav had started again.
    @Synchronized internal fun clear() {
        ids.clear(); entries.clear(); labels.clear(); nextInt = FIRST.toLong(); nextTrip = FIRST_TRIP
    }

    // The references behind the handles given, to save beside them: {"handle": [reference, operator name]}.
    @Synchronized fun refs(ints: Iterable<Int>, trips: Iterable<Long>): JSONObject {
        val o = JSONObject()
        for (id in ints.filter(::isHandle).map(Int::toLong) + trips.filter(::isTripHandle)) {
            val e = entries[id] ?: continue
            o.put(id.toString(), JSONArray().put(e.token).apply { labels[id]?.let { put(it) } })
        }
        return o
    }

    // Saved handles turned into this run's: every handle in a saved file must have its reference beside it, or nothing
    // in it can be trusted to mean what it meant (Bad). Ids that aren't handles stay as they are.
    class Remap private constructor(private val ints: Map<Int, Int>, private val trips: Map<Long, Long>) {
        class Bad(why: String) : Exception(why)

        fun id(saved: Int): Int = if (isHandle(saved)) ints[saved] ?: throw Bad("no reference for $saved") else saved
        fun trip(saved: Long): Long = if (isTripHandle(saved)) trips[saved] ?: throw Bad("no reference for $saved") else saved

        companion object {
            val NONE = Remap(emptyMap(), emptyMap())

            fun of(o: JSONObject?): Remap {
                if (o == null) return NONE
                val ints = HashMap<Int, Int>()
                val trips = HashMap<Long, Long>()
                for (k in o.keys()) {
                    val saved = k.toLongOrNull() ?: throw Bad("bad handle $k")
                    val a = o.optJSONArray(k) ?: throw Bad("bad entry $k")
                    val token = a.optString(0)
                    val hash = token.lastIndexOf('#')
                    val ref = Ref.parse(if (hash >= 0) token.substring(0, hash) else token) ?: throw Bad("bad reference $token")
                    val variant = if (hash >= 0) token.substring(hash + 1).toIntOrNull() ?: throw Bad("bad variant $token") else null
                    if (ref.kind == Kind.TRIP) {
                        if (!isTripHandle(saved) || variant != null) throw Bad("bad trip $k")
                        trips[saved] = internTrip(ref)
                    } else {
                        if (saved > Int.MAX_VALUE || !isHandle(saved.toInt())) throw Bad("bad handle $k")
                        val id = intern(ref, variant)
                        a.optString(1).takeIf { it.isNotEmpty() }?.let { setLabel(id, it) }
                        ints[saved.toInt()] = id
                    }
                }
                return Remap(ints, trips)
            }
        }
    }
}
