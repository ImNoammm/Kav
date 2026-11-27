package uk.noammm.kav.data

// The timetable shortens "תחנת"/"תחנה" to "ת." ("ת.רכבת חדרה מערב"), so a typed one also stands for the short form.
private val STATION_WORDS = setOf("תחנת", "תחנה")
private fun forms(word: String): List<String> = if (word in STATION_WORDS) listOf(word, "ת") else listOf(word)

fun Net.searchStops(q: String, limit: Int = 60): IntArray {
    val raw = q.trim().lowercase()
    if (raw.isEmpty()) return IntArray(0)
    val all = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
    val words = all.filter { !it.all { c -> c.isDigit() } }
    val need = words.ifEmpty { all }

    val idx = ArrayList<Int>(limit * 4)
    val score = ArrayList<Double>(limit * 4)
    val types = stopType
    // A typed code finds its own stop first, before the stops whose codes only start with it (4156 before 41560-41569).
    val number = need.singleOrNull()?.takeIf { it.all { c -> c.isDigit() } }?.toIntOrNull()
    for (i in name.indices) {
        if (types[i] < 0) continue
        if (code[i] == number) { idx.add(i); score.add(-1.0); continue }
        // In Hebrew, else by its English name.
        val sc = matched(hay[i], need) ?: hayEn.getOrNull(i)?.let { matched(it, need) }
        if (sc != null) { idx.add(i); score.add(sc); continue }
        if (need.size == 1 && code[i].toString().startsWith(need[0])) { idx.add(i); score.add(0.5) }
    }
    val ranked = idx.indices.sortedBy { score[it] }.take(limit).map { idx[it] }
    // A bus station's platforms share its code: together, where the first of them ranks, in platform order.
    val group = { s: Int -> if (platform[s].isEmpty() || code[s] <= 0) "#$s" else "${code[s]}" }
    val first = HashMap<String, Int>()
    ranked.forEachIndexed { i, s -> first.putIfAbsent(group(s), i) }
    return ranked.sortedWith(compareBy<Int>(
        { first.getValue(group(it)) }, { platform[it].toIntOrNull() ?: Int.MAX_VALUE }, { platform[it] },
    )).toIntArray()
}

// How well every word is found in a stop's name and town, the earlier the better; null when one isn't.
private fun matched(h: String, need: List<String>): Double? {
    var sc = 0.0
    for (t in need) {
        val at = forms(t).map { h.indexOf(if (it == "ת") "ת." else it) }.filter { it >= 0 }.minOrNull() ?: return null
        sc += if (at == 0) 0.0 else minOf(at, 40) / 100.0
    }
    return sc
}

internal fun searchWords(s: String): List<String> {
    val sb = StringBuilder(s.length)
    for (c in java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)) {
        if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.COMBINING_DIACRITICAL_MARKS) continue
        sb.append(if (c.isLetter()) c.lowercaseChar() else if (c.isDigit()) c else ' ')
    }
    return sb.split(' ').filter { it.isNotEmpty() }
}

internal fun spacedWords(s: String) = searchWords(s).joinToString(" ", " ", " ")

// Every typed word has to start a word of the name, code or mode.
fun Net.stopsMatching(q: String, at: Pair<Double, Double>?, modeName: (Int) -> String): List<Int> {
    val need = searchWords(q).map { w -> forms(w).map { " $it" } }
    if (need.isEmpty()) return emptyList()
    val words = stopWords
    val types = stopType
    val modes = HashMap<Int, String>()
    val codes = HashSet<Int>()
    val hits = ArrayList<Int>()
    for (i in words.indices) {
        if (types[i] < 0) continue
        val mode = modes.getOrPut(types[i]) { spacedWords(modeName(types[i])) }
        if (need.all { f -> f.any { words[i].contains(it) || mode.contains(it) } } && (code[i] <= 0 || codes.add(code[i]))) hits.add(i)
    }
    if (at == null) return hits.sortedBy { name[it] }.take(4)
    val (la, lo) = at
    fun sq(i: Int) = (la - lat[i]) * (la - lat[i]) + (lo - lon[i]) * (lo - lon[i])
    return hits.sortedWith(compareBy({ sq(it) }, { name[it] })).take(4)
        .sortedWith(compareBy({ Math.round(uk.noammm.kav.ui.metres(la, lo, lat[it], lon[it])) }, { name[it] }))
}

// Tel Aviv's weekend lines' operator as the timetable names it (tools/export_web_bundle.py), and what riders type for
// them, which finds their lines too.
const val NAIM_NAME = "נעים בסופ״ש"
const val NAIM_WORDS = "na'im naim basofash נעים בסופ״ש בסופ\"ש בסופש"

fun Net.searchRoutes(q: String, types: IntArray = intArrayOf()): IntArray {
    val need = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = ArrayList<Int>(256)
    val seen = HashSet<String>(1024)
    for (r in rShort.indices) {
        if (types.isNotEmpty() && rType[r] !in types) continue
        if (need.isNotEmpty()) {
            // A number is a line number: the long names carry codes like "-10" that would match half the country.
            val h = (rShort[r] + " " + rLong[r] + if (agencyOf(r) == NAIM_NAME) " $NAIM_WORDS" else "").lowercase()
            val number = rShort[r].lowercase()
            if (!need.all { if (it.all(Char::isDigit)) number.startsWith(it) else h.contains(it) }) continue
        }
        if (!seen.add(rShort[r] + "\u0000" + rLong[r])) continue
        out.add(r)
    }
    return out.toIntArray()
}

fun Net.representativeTrip(route: Int): Int {
    var best = -1; var bestLen = -1
    for (t in tripRoute.indices) {
        if (tripRoute[t] != route) continue
        val len = tripStart[t + 1] - tripStart[t]
        if (len > bestLen) { bestLen = len; best = t }
    }
    return best
}

// Stops no trip serves are left out of every list. The national timetable still names some; stopType is -1 for them.
fun Net.nearestStops(la: Double, lo: Double, k: Int = 24, radius: Double = 2500.0): List<Pair<Int, Double>> {
    val out = ArrayList<Pair<Int, Double>>(k * 4)
    val types = stopType
    for (i in lat.indices) {
        if (types[i] < 0) continue
        if (kotlin.math.abs(lat[i] - la) > 0.03 || kotlin.math.abs(lon[i] - lo) > 0.04) continue
        val d = uk.noammm.kav.ui.metres(la, lo, lat[i], lon[i])
        if (d < radius) out.add(i to d)
    }
    return out.sortedBy { it.second }.take(k)
}

// The stops a town is known by, for a search nothing is typed in yet: up to three of its train stations, then its
// busiest stops, one a name, the busiest of each name standing for it. The town is the one of the stop nearest the
// point; with no point, or no stop within 5 km of it, the whole country's. The timetable gives train stations no town,
// so such a stop is in the town of the nearest stop that has one. The town's index (-1 for the country) and the stops.
fun Net.placesAround(at: Pair<Double, Double>?, most: Int = 6): Pair<Int, List<Int>> {
    fun named(s: Int) = city.getOrElse(cityOf[s]) { "" }.isNotBlank()
    fun townNear(la: Double, lo: Double, radius: Double) =
        nearestStops(la, lo, 8, radius).firstOrNull { named(it.first) }?.let { cityOf[it.first] } ?: -1
    val town = at?.let { (la, lo) -> townNear(la, lo, 5000.0) } ?: -1
    val types = stopType
    val total = HashMap<String, Int>()
    val busiest = HashMap<String, Int>()
    for (s in 0 until nStops) {
        if (types[s] < 0 || (town >= 0 && (if (named(s)) cityOf[s] else townNear(lat[s], lon[s], 1500.0)) != town)) continue
        val key = name[s]
        val runs = dStart[s + 1] - dStart[s]
        total[key] = (total[key] ?: 0) + runs
        val best = busiest[key]
        if (best == null || runs > dStart[best + 1] - dStart[best]) busiest[key] = s
    }
    val byRuns = busiest.entries.sortedByDescending { total.getValue(it.key) }.map { it.value }
    val trains = byRuns.filter { types[it] == 2 }.take(3)
    return town to (trains + byRuns.filter { it !in trains }).take(most)
}
