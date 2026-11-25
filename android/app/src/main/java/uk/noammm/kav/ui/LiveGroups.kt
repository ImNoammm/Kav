package uk.noammm.kav.ui

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

// Vehicles whose marks would lie on one another on the map at a zoom are drawn as one mark that says how many it holds,
// and split back into their own marks as the map zooms in.

// A vehicle's place, by the same id its mark has on the map.
internal data class GroupPoint(val id: String, val lat: Double, val lon: Double)

// Merged marks with more than one vehicle; a vehicle alone is in none. lat and lon: where its mark is, on its first core
// (groupVehicles), so no two marks lie on one another however the vehicles sit about it. key: the
// same while the group lasts, through vehicles joining or leaving it, so its mark glides on instead of being swapped.
// cores: for each of ids, the vehicle its small group is about (groupVehicles), given back so it's worked out the same.
internal data class VehicleGroup(
    val ids: List<String>, val lat: Double, val lon: Double, val key: String = ids.first(),
    val cores: List<String> = ids.map { ids.first() },
)

// points at zoom (Kav's camera zoom, where the world is Geo.SIZE * 2^zoom screen pixels wide), grouped as map apps group
// their marks, in two steps:
// - small groups: a vehicle (its core, the first by id not yet in one) and those whose marks lie on its own (centres
//   closer than mergePx); not a chain of marks each touching the next, which made a line of buses along a road one group
//   that only parted once every one of them stood apart, deep in;
// - small groups whose cores are closer than groupPx (their merged marks would lie on one another) are one, about the
//   first of them; and a lone vehicle whose mark would lie under one (closer than coverPx to a core) goes in with it.
// So groups part a step at a time as the map zooms in. previous: id to the key of the group it was in, and cores the core
// it was with: a small group of before keeps those still within splitPx of its core, and groups that were one stay one
// until their cores are groupPx * splitPx / mergePx apart, so a group at its edge doesn't go back and forth (splitPx no
// more than a quarter zoom level, onZoom's step, past mergePx). keep: never merged (the vehicle followed or opened). The
// groups don't depend on the order points come in, and worked out again from what they gave, they come out the same.
internal fun groupVehicles(
    points: List<GroupPoint>, zoom: Double, mergePx: Double, splitPx: Double,
    keep: Set<String> = emptySet(), previous: Map<String, String> = emptyMap(),
    coverPx: Double = 0.0, groupPx: Double = 0.0, cores: Map<String, String> = emptyMap(),
): List<VehicleGroup> {
    val scale = Geo.SIZE * 2.0.pow(zoom)
    val free = points.filter { it.id !in keep }.sortedBy { it.id }
    if (free.size < 2) return emptyList()
    val x = DoubleArray(free.size) { Geo.x(free[it].lon) * scale }
    val y = DoubleArray(free.size) { Geo.y(free[it].lat) * scale }
    fun gap(i: Int, j: Int) = hypot(x[j] - x[i], y[j] - y[i])
    val cell = maxOf(mergePx, splitPx, coverPx).coerceAtLeast(1.0)
    val grid = HashMap<Long, MutableList<Int>>()
    fun key(cx: Long, cy: Long) = cx * 1_000_003L + cy
    for (i in free.indices) grid.getOrPut(key(floor(x[i] / cell).toLong(), floor(y[i] / cell).toLong())) { ArrayList() }.add(i)
    // The vehicles within reach (no more than cell) of i, by index.
    fun near(i: Int, reach: Double): List<Int> {
        val gx = floor(x[i] / cell).toLong(); val gy = floor(y[i] / cell).toLong()
        val out = ArrayList<Int>()
        for (ax in gx - 1..gx + 1) for (ay in gy - 1..gy + 1) for (j in grid[key(ax, ay)].orEmpty()) if (j != i && gap(i, j) < reach) out += j
        return out
    }
    val index = HashMap<String, Int>(free.size * 2)
    for (i in free.indices) index[free[i].id] = i
    // The core of the small group each vehicle is in, or -1.
    val core = IntArray(free.size) { -1 }
    // Held: each small group of before, about its core (the first of its group still here when none was given).
    val before = HashMap<Int, MutableList<Int>>()
    val keyed = HashMap<String, MutableList<Int>>()
    for (i in free.indices) {
        val k = previous[free[i].id] ?: continue
        val c = cores[free[i].id]?.let { index[it] }?.takeIf { previous[free[it].id] == k }
        if (c != null) before.getOrPut(c) { ArrayList() }.add(i) else keyed.getOrPut(k) { ArrayList() }.add(i)
    }
    for (members in keyed.values) before.getOrPut(members.min()) { ArrayList() }.addAll(members)
    for (c in before.keys.sorted()) {
        if (core[c] != -1) continue
        val held = (before.getValue(c) + c).distinct().filter { core[it] == -1 && (it == c || gap(c, it) < splitPx) }
        if (held.size >= 2) for (m in held) core[m] = c
    }
    for (i in free.indices) {
        if (core[i] != -1) continue
        // On the mark of a small group's core: in it, the nearest.
        near(i, mergePx).filter { core[it] == it }.minByOrNull { gap(i, it) }?.let { core[i] = it; continue }
        val joining = near(i, mergePx).filter { it > i && core[it] == -1 }
        if (joining.isNotEmpty()) { core[i] = i; for (j in joining) core[j] = i }
    }
    // Small groups whose merged marks would lie on one another: one, about the first.
    val heads = free.indices.filter { core[it] == it }
    val top = HashMap<Int, Int>()
    for (c in heads) {
        if (c in top) continue
        top[c] = c
        if (groupPx <= 0.0) continue
        for (d in heads) if (d !in top) {
            val was = previous[free[c].id]?.takeIf { it == previous[free[d].id] }
            if (gap(c, d) < if (was != null) groupPx * splitPx / mergePx else groupPx) top[d] = c
        }
    }
    // A lone vehicle under a group's mark: in with the nearest core.
    if (coverPx > 0.0) for (i in free.indices) if (core[i] == -1) {
        near(i, coverPx).filter { core[it] == it }.minByOrNull { gap(i, it) }?.let { core[i] = it }
    }
    val found = free.indices.filter { core[it] != -1 }.groupBy { top.getValue(core[it]) }.values
        .filter { it.size >= 2 }.map { it.sorted() }.sortedBy { it.first() }
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
        val head = free[top.getValue(core[m.first()])]
        VehicleGroup(ids, head.lat, head.lon, key, m.map { free[core[it]].id })
    }
}

// The merged mark a tap at `at` is on, of the groups the map draws now (not ones worked out since, which it shows only
// once it's taken them): the nearest whose middle is within reach on screen. point: a place's spot on screen.
internal fun groupTapped(groups: List<VehicleGroup>, at: androidx.compose.ui.geometry.Offset, reach: Float,
    point: (Double, Double) -> androidx.compose.ui.geometry.Offset): VehicleGroup? =
    groups.map { it to (point(it.lat, it.lon) - at).getDistance() }.filter { it.second <= reach }.minByOrNull { it.second }?.first

// The id to group's-key map groupVehicles takes back as previous, and the id to core map it takes back as cores.
internal fun List<VehicleGroup>.membership(): Map<String, String> = buildMap { for (g in this@membership) for (id in g.ids) put(id, g.key) }
internal fun List<VehicleGroup>.cores(): Map<String, String> = buildMap { for (g in this@cores) g.ids.forEachIndexed { k, id -> put(id, g.cores[k]) } }

// The zoom a tapped group opens at: the first, a quarter level at a time in from zoom (onZoom's own steps, so the zoom
// told once the camera rests is that one), where its vehicles aren't all one group any more; or null when no zoom up to
// maxZoom opens it (buses at one spot): a tap then lists them instead.
internal fun openZoom(
    points: List<GroupPoint>, zoom: Double, mergePx: Double, splitPx: Double, maxZoom: Double,
    coverPx: Double = 0.0, groupPx: Double = 0.0, cores: Map<String, String> = emptyMap(),
): Double? {
    if (points.size < 2) return null
    val held = points.associate { it.id to "g" }
    var z = kotlin.math.ceil(zoom * 4) / 4 + 0.25
    while (z <= maxZoom) {
        if (groupVehicles(points, z, mergePx, splitPx, previous = held, coverPx = coverPx, groupPx = groupPx, cores = cores).none { it.ids.size == points.size }) return z
        z += 0.25
    }
    return null
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
