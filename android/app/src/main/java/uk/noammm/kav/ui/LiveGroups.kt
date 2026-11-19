package uk.noammm.kav.ui

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

// Vehicles whose marks would lie on one another on the map at a zoom are drawn as one mark that says how many it holds,
// and split back into their own marks as the map zooms in.

// A vehicle's place, by the same id its mark has on the map.
internal data class GroupPoint(val id: String, val lat: Double, val lon: Double)

// Merged marks with more than one vehicle; a vehicle alone is in none. lat and lon: the middle of its vehicles. key: the
// same while the group lasts, through vehicles joining or leaving it, so its mark glides on instead of being swapped.
internal data class VehicleGroup(val ids: List<String>, val lat: Double, val lon: Double, val key: String = ids.first())

// points at zoom (Kav's camera zoom, where the world is Geo.SIZE * 2^zoom screen pixels wide). Vehicles whose marks
// lie on one another (centres closer than mergePx on screen) are in one group, and so is any vehicle whose mark lies on
// one of theirs: a group is the vehicles linked that way, and splits as soon as no two of them do. previous: id to the
// key of the group it was in; two vehicles of the same group stay linked until splitPx apart, so a group at its edge
// doesn't go back and forth (splitPx no more than a quarter zoom level, onZoom's step, past mergePx, so it splits within
// a step of its marks parting). keep: never merged (the vehicle followed or opened). The groups don't depend on the
// order points come in.
internal fun groupVehicles(
    points: List<GroupPoint>, zoom: Double, mergePx: Double, splitPx: Double,
    keep: Set<String> = emptySet(), previous: Map<String, String> = emptyMap(),
): List<VehicleGroup> {
    val scale = Geo.SIZE * 2.0.pow(zoom)
    val free = points.filter { it.id !in keep }.sortedBy { it.id }
    if (free.size < 2) return emptyList()
    val x = DoubleArray(free.size) { Geo.x(free[it].lon) * scale }
    val y = DoubleArray(free.size) { Geo.y(free[it].lat) * scale }
    val link = IntArray(free.size) { it }
    fun root(i: Int): Int { var r = i; while (link[r] != r) r = link[r]; var k = i; while (link[k] != r) { val n = link[k]; link[k] = r; k = n }; return r }
    val cell = maxOf(mergePx, splitPx).coerceAtLeast(1.0)
    val grid = HashMap<Long, MutableList<Int>>()
    fun key(cx: Long, cy: Long) = cx * 1_000_003L + cy
    for (i in free.indices) grid.getOrPut(key(floor(x[i] / cell).toLong(), floor(y[i] / cell).toLong())) { ArrayList() }.add(i)
    for (i in free.indices) {
        val gx = floor(x[i] / cell).toLong(); val gy = floor(y[i] / cell).toLong()
        val was = previous[free[i].id]
        for (ax in gx - 1..gx + 1) for (ay in gy - 1..gy + 1) for (j in grid[key(ax, ay)].orEmpty()) {
            if (j <= i) continue
            val reach = if (was != null && previous[free[j].id] == was) splitPx else mergePx
            if (hypot(x[j] - x[i], y[j] - y[i]) < reach) link[root(j)] = root(i)
        }
    }
    val found = free.indices.groupBy { root(it) }.values.filter { it.size >= 2 }.map { it.sorted() }.sortedBy { it.first() }
    val groups = found.map { m -> m.map { free[it].id } }
    // Each group keeps the key most of its vehicles had, the largest first; a new one takes its first vehicle's id.
    val keys = arrayOfNulls<String>(groups.size)
    val used = HashSet<String>()
    groups.indices.flatMap { g -> groups[g].mapNotNull { previous[it] }.groupingBy { it }.eachCount().map { (k, n) -> Triple(g, k, n) } }
        .sortedWith(compareBy({ -it.third }, { it.second }, { it.first }))
        .forEach { (g, k, _) -> if (keys[g] == null && used.add(k)) keys[g] = k }
    return groups.mapIndexed { g, ids ->
        val key = keys[g] ?: (ids.firstOrNull { it !in used } ?: "${ids.first()}#$g").also { used += it }
        val m = found[g]
        VehicleGroup(ids, m.sumOf { free[it].lat } / m.size, m.sumOf { free[it].lon } / m.size, key)
    }
}

// The merged mark a tap at `at` is on, of the groups the map draws now (not ones worked out since, which it shows only
// once it's taken them): the nearest whose middle is within reach on screen. point: a place's spot on screen.
internal fun groupTapped(groups: List<VehicleGroup>, at: androidx.compose.ui.geometry.Offset, reach: Float,
    point: (Double, Double) -> androidx.compose.ui.geometry.Offset): VehicleGroup? =
    groups.map { it to (point(it.lat, it.lon) - at).getDistance() }.filter { it.second <= reach }.minByOrNull { it.second }?.first

// The id to group's-key map groupVehicles takes back as previous.
internal fun List<VehicleGroup>.membership(): Map<String, String> = buildMap { for (g in this@membership) for (id in g.ids) put(id, g.key) }

// The zoom at which a group's vehicles all stand apart (every pair at least splitPx on screen), or null when no zoom up
// to maxZoom does it (buses at one spot): a tap then lists them instead.
internal fun splitZoom(points: List<GroupPoint>, splitPx: Double, maxZoom: Double): Double? {
    var closest = Double.MAX_VALUE
    for (i in points.indices) for (j in i + 1 until points.size) {
        val d = hypot(Geo.x(points[i].lon) - Geo.x(points[j].lon), Geo.y(points[i].lat) - Geo.y(points[j].lat))
        if (d < closest) closest = d
    }
    if (closest == Double.MAX_VALUE) return null
    if (closest <= 0.0) return null
    val zoom = kotlin.math.log2(splitPx / (Geo.SIZE * closest))
    return zoom.takeIf { it <= maxZoom }
}

// The zoom at which places spread at most px across on screen (their widest side), or the deepest zoom for one place.
internal fun fitZoom(points: List<GroupPoint>, px: Double): Double {
    if (points.size < 2) return (Geo.MAX_Z + Geo.OVER).toDouble()
    val xs = points.map { Geo.x(it.lon) }; val ys = points.map { Geo.y(it.lat) }
    val spread = maxOf(xs.max() - xs.min(), ys.max() - ys.min())
    if (spread <= 0.0) return (Geo.MAX_Z + Geo.OVER).toDouble()
    return kotlin.math.log2(px / (Geo.SIZE * spread)).coerceAtMost((Geo.MAX_Z + Geo.OVER).toDouble())
}

// The live map's vehicles with groups merged: each member's mark, arrow and halo go to the group's middle at alpha 0
// (the map glides and fades them there, then stops sending them), and the group gets its own halo and mark on top.
// mark: the image name and halo colour for a group.
internal fun MapGeometry.merged(groups: List<VehicleGroup>, mark: (VehicleGroup) -> Pair<String, androidx.compose.ui.graphics.Color>): MapGeometry {
    if (groups.isEmpty()) return this
    val into = HashMap<String, VehicleGroup>()
    for (g in groups) for (id in g.ids) into[id] = g
    fun groupOf(id: String?) = id?.let { into[it.substringBefore(':')] }
    val marks = markers.map { m -> groupOf(m.id)?.let { g -> m.copy(lat = g.lat, lon = g.lon, alpha = 0f) } ?: m }
    val glows = halos.map { d -> groupOf(d.id)?.let { g -> d.copy(lat = g.lat, lon = g.lon, alpha = 0f) } ?: d }
    val groupHalos = ArrayList<MapDot>(); val groupMarks = ArrayList<MapMarker>()
    for (g in groups) {
        val (icon, glow) = mark(g)
        val id = GROUP_ID + g.key
        groupHalos += MapDot(g.lat, g.lon, glow, VEHICLE_HALO_R, id = "$id:halo", fade = FOCUS_OUT)
        // The count in the mark's id: a group that gains or loses one fades its old count out into the new one, both
        // gliding with the group to its new middle, instead of the number changing in one frame.
        groupMarks += MapMarker(g.lat, g.lon, icon, id = "$id:n${g.ids.size}", fade = FOCUS_OUT)
    }
    return copy(markers = marks + groupMarks, halos = glows + groupHalos)
}

// On screen, in dp: vehicles' marks (rings 11 dp round) lie on one another closer than GROUP_MERGE_DP and merge; merged,
// they part past GROUP_SPLIT_DP, a quarter zoom level (one step of onZoom) further.
internal const val VEHICLE_MARK_R = 11f
internal const val GROUP_MERGE_DP = 2 * VEHICLE_MARK_R
internal val GROUP_SPLIT_DP = GROUP_MERGE_DP * 2f.pow(0.25f)
