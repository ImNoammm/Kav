package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

// The language stops', stations', towns' and lines' ends' names are shown in, a setting of its own that changes nothing
// else (Settings, Language and time): English where the timetable has a name's English one (the ministry's), else the
// Hebrew. Kav keeps working in the ministry's Hebrew names, which its server matches on, and changes them only where a
// name is shown.
object StopNames {
    var english by mutableStateOf(false)
}

// A name as shown, for a name from the timetable, Kav's server or a stop Kav keeps.
fun shownName(name: String): String = uk.noammm.kav.Loaded.net?.shown(name) ?: name

private class IntVec(cap: Int = 1 shl 16) {
    var a = IntArray(cap)
    var n = 0
    fun push(v: Int) {
        if (n == a.size) a = a.copyOf(a.size * 2)
        a[n++] = v
    }
    fun trimmed(): IntArray = a.copyOf(n)
}

class Net private constructor() {

    lateinit var name: Array<String>; private set
    lateinit var lat: DoubleArray; private set
    lateinit var lon: DoubleArray; private set
    lateinit var code: IntArray; private set
    lateinit var cityOf: IntArray; private set
    lateinit var city: Array<String>; private set
    // A stop's platform (רציף) where the ministry numbers one, else blank: a bus station's platforms are stops of their
    // own that share its code. Bundles before KAV6 have none.
    lateinit var platform: Array<String>; private set
    // Each stop's and town's English name, blank where the ministry has none; empty arrays in bundles without an "en" section.
    var nameEn: Array<String> = emptyArray(); private set
    var cityEn: Array<String> = emptyArray(); private set
    // The names lines end at that are no stop's (the ministry's long names give some another way), in English.
    private var moreEn: Map<String, String> = emptyMap()

    lateinit var rShort: Array<String>; private set
    lateinit var rLong: Array<String>; private set
    lateinit var rType: IntArray; private set
    lateinit var rAgency: IntArray; private set

    lateinit var agency: Array<String>; private set

    lateinit var tripRoute: IntArray; private set
    lateinit var tripStart: IntArray; private set
    // Bit 0 is Sunday. Bundles older than KAV5 hold one day, so their trips run every day.
    lateinit var tripDays: IntArray; private set
    lateinit var stStop: StopOfTime; private set
    lateinit var stDep: IntArray; private set

    // Each stop's departures: dConn[dStart[s] until dStart[s + 1]] are its stop times that aren't their trip's last, as
    // indexes into stStop and stDep, by departure and at the same second by that index.
    lateinit var dStart: IntArray; private set
    lateinit var dConn: IntArray; private set

    lateinit var hay: Array<String>; private set
    var hayEn: Array<String> = emptyArray(); private set

    val nStops get() = lat.size
    val nRoutes get() = rShort.size

    fun cityOf(s: Int): String = city.getOrElse(cityOf[s]) { "" }

    // A stop's and its town's names as shown (StopNames).
    fun stopName(s: Int): String = name[s].let { if (StopNames.english) nameEn.getOrNull(s)?.ifEmpty { null } ?: it else it }
    fun cityName(s: Int): String =
        cityOf(s).let { if (StopNames.english) cityEn.getOrNull(cityOf[s])?.ifEmpty { null } ?: it else it }

    // Any name the timetable has, a stop's, a town's or a line's end, as shown: the ministry's Hebrew (Kav's server, a
    // stop Kav kept) in English, or an English one, when Kav is in English, back in Hebrew. Others stay as they are.
    fun shown(name: String, inEnglish: Boolean = StopNames.english): String = when {
        name.isEmpty() -> name
        inEnglish -> english[name.trim()] ?: name
        else -> hebrew[name.trim()] ?: name
    }

    private val english: Map<String, String> by lazy {
        val m = HashMap<String, String>(nameEn.size + cityEn.size + moreEn.size)
        for (i in nameEn.indices) if (nameEn[i].isNotEmpty()) m.putIfAbsent(name[i], nameEn[i])
        for (i in cityEn.indices) if (cityEn[i].isNotEmpty()) m.putIfAbsent(city[i], cityEn[i])
        for ((he, en) in moreEn) m.putIfAbsent(he, en)
        m
    }

    private val hebrew: Map<String, String> by lazy { HashMap<String, String>().apply { for ((he, en) in english) putIfAbsent(en, he) } }
    fun agencyOf(r: Int): String = agency.getOrElse(rAgency.getOrElse(r) { -1 }) { "" }
    fun tripLast(t: Int): Int = stStop[tripStart[t + 1] - 1]
    fun runsOn(t: Int, day: Int): Boolean = tripDays[t] and (1 shl day) != 0

    fun tripOf(stopTime: Int): Int {
        var lo = 0; var hi = tripRoute.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tripStart[mid] <= stopTime) lo = mid else hi = mid - 1
        }
        return lo
    }

    // The platform a line leaves a bus station from, at about that second of the day: the only one the timetable has it
    // leave from there, else the one of its departure nearest that time, within five minutes. Blank when it can't tell.
    fun platformOf(code: Int, number: String, second: Int): String {
        val stops = platformStops[code] ?: return ""
        // Screens ask on every redraw; a big station has thousands of departures to go through.
        val key = "$code/$number/$second"
        synchronized(platformsFound) { platformsFound[key] }?.let { return it }
        val seen = HashSet<String>()
        var nearest = ""
        var gap = Int.MAX_VALUE
        for (s in stops) for (i in dStart[s] until dStart[s + 1]) {
            val st = dConn[i]
            if (rShort[tripRoute[tripOf(st)]] != number) continue
            seen.add(platform[s])
            val off = Math.abs(Math.floorMod(stDep[st] - second + 43_200, 86_400) - 43_200)
            if (off < gap) { gap = off; nearest = platform[s] }
        }
        val found = when {
            seen.size == 1 -> seen.first()
            gap <= 300 -> nearest
            else -> ""
        }
        synchronized(platformsFound) { platformsFound[key] = found }
        return found
    }

    private val platformsFound = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 256
    }

    private val platformStops: Map<Int, IntArray> by lazy {
        val byCode = HashMap<Int, MutableList<Int>>()
        for (s in 0 until nStops) if (platform[s].isNotEmpty() && code[s] > 0) byCode.getOrPut(code[s]) { ArrayList() }.add(s)
        byCode.mapValues { it.value.toIntArray() }
    }

    // Where each town or city is, as the average of its stops.
    val cityCentre: Array<Pair<Double, Double>?> by lazy {
        val sumLat = DoubleArray(city.size); val sumLon = DoubleArray(city.size); val count = IntArray(city.size)
        for (s in 0 until nStops) {
            val c = cityOf[s]
            if (c !in city.indices || city[c].isBlank()) continue
            sumLat[c] += lat[s]; sumLon[c] += lon[s]; count[c]++
        }
        Array(city.size) { c -> if (count[c] == 0) null else sumLat[c] / count[c] to sumLon[c] / count[c] }
    }

    val stopWords: Array<String> by lazy {
        Array(nStops) {
            val named = name[it] + nameEn.getOrNull(it)?.takeIf { e -> e.isNotEmpty() }?.let { e -> " $e" }.orEmpty()
            spacedWords(if (code[it] > 0) named + " " + code[it] else named)
        }
    }
    val stopType: IntArray by lazy {
        val out = IntArray(nStops) { -1 }
        for (t in tripRoute.indices) {
            val type = rType[tripRoute[t]]
            for (k in tripStart[t] until tripStart[t + 1]) {
                val s = stStop[k]
                if (out[s] < 0 || type < out[s]) out[s] = type
            }
        }
        out
    }

    private lateinit var b: ByteArray
    private var p = 0
    // The bundle still being unpacked into b beside the reading of it (Unpacking), and how far b is known to be filled:
    // reading waits there for more. All of it, with nothing unpacking.
    private var unpacking: Unpacking? = null
    private var filled = Int.MAX_VALUE

    private fun await(upTo: Int) {
        val u = unpacking ?: return
        filled = u.await(upTo)
    }

    private fun vi(): Int {
        if (p + 5 > filled) await(p + 5)
        var sh = 0; var r = 0; var x: Int
        do {
            x = b[p++].toInt() and 0xFF
            r = r or ((x and 0x7f) shl sh)
            sh += 7
        } while (x and 0x80 != 0)
        return (r ushr 1) xor -(r and 1)
    }

    private fun vs(): String {
        val n = vi()
        if (p + n > filled) await(p + n)
        val s = String(b, p, n, StandardCharsets.UTF_8)
        p += n
        return s
    }

    private fun parse(buf: ByteArray, from: Unpacking? = null) {
        b = buf; p = 0
        unpacking = from; filled = if (from == null) Int.MAX_VALUE else 0
        await(4)
        val magic = String(b, 0, 4, StandardCharsets.US_ASCII)
        if (magic != "KAV3" && magic != "KAV4" && magic != "KAV5" && magic != "KAV6") throw IllegalArgumentException("bad bundle: $magic")
        val v4 = magic != "KAV3"
        val v5 = magic == "KAV5" || magic == "KAV6"
        p = 4

        val nS = vi(); val nR = vi(); val nT = vi(); val nC = vi()
        val nST = if (v5) vi() else -1
        agency = if (v4) Array(vi()) { vs() } else emptyArray()

        city = Array(nC) { vs() }

        name = Array(nS) { "" }
        lat = DoubleArray(nS); lon = DoubleArray(nS)
        code = IntArray(nS); cityOf = IntArray(nS)
        var la = 0L; var lo = 0L
        for (i in 0 until nS) {
            la += vi(); lo += vi()
            lat[i] = la / 1e5; lon[i] = lo / 1e5
            code[i] = vi(); cityOf[i] = vi(); name[i] = vs()
        }

        rShort = Array(nR) { "" }; rLong = Array(nR) { "" }; rType = IntArray(nR)
        rAgency = IntArray(nR) { -1 }
        for (i in 0 until nR) {
            rShort[i] = vs(); rLong[i] = vs(); rType[i] = vi()
            if (v4) rAgency[i] = vi()
        }

        tripRoute = IntArray(nT); tripStart = IntArray(nT + 1)
        tripDays = IntArray(nT) { 0x7f }
        if (v5) {
            val stops = StopOfTime(nST, nS); val deps = IntArray(nST)
            var k = 0
            for (t in 0 until nT) {
                tripRoute[t] = vi(); tripDays[t] = vi()
                val n = vi()
                var pt = vi(); var ps = 0
                tripStart[t] = k
                for (j in 0 until n) {
                    val d = pt + vi() + vi(); val s = ps + vi()
                    deps[k] = d; stops[k] = s; k++
                    pt = d; ps = s
                }
            }
            tripStart[nT] = k
            stStop = stops; stDep = deps
        } else {
            val ss = IntVec(1 shl 21); val sd = IntVec(1 shl 21)
            for (t in 0 until nT) {
                tripRoute[t] = vi()
                val n = vi()
                var pt = vi(); var ps = 0
                tripStart[t] = ss.n
                for (k in 0 until n) {
                    val d = pt + vi() + vi(); val s = ps + vi()
                    sd.push(d); ss.push(s)
                    pt = d; ps = s
                }
            }
            tripStart[nT] = ss.n
            stStop = StopOfTime(ss.n, nS).also { for (i in 0 until ss.n) it[i] = ss.a[i] }; stDep = sd.trimmed()
        }

        platform = Array(nS) { "" }
        if (magic == "KAV6") {
            var s = 0
            repeat(vi()) { s += vi(); platform[s] = vs() }
            // Named sections after it, each its name and length, so one this version doesn't know is skipped.
            while (p < b.size) {
                val section = vs()
                val end = vi().let { p + it }
                if (section == "en") {
                    cityEn = Array(vi()) { vs() }
                    nameEn = Array(vi()) { vs() }
                    moreEn = HashMap<String, String>().apply { repeat(vi()) { put(vs(), vs()) } }
                    if (nameEn.size != nS || cityEn.size != nC) { nameEn = emptyArray(); cityEn = emptyArray(); moreEn = emptyMap() }
                }
                p = end
            }
        }

        hay = Array(nS) { (name[it] + " " + cityOf(it)).lowercase() }
        // Found by its English name too, whichever the rider reads.
        hayEn = if (nameEn.isEmpty()) emptyArray()
            else Array(nS) { (nameEn[it] + " " + cityEn.getOrElse(cityOf[it]) { "" }).trim().lowercase() }
        b = ByteArray(0)
        unpacking = null; filled = Int.MAX_VALUE
    }

    // The stop times go to their stops as they come in the file, which reads it once from end to end, and each stop's
    // few are then put in order. Sorting them all by time first, then dealing them out, jumped about the week's 6.7
    // million stop times three times over and took most of the time the timetable needs to open.
    private fun buildConnections() {
        val nT = tripRoute.size
        val nS = lat.size
        val stop = stStop; val dep = stDep; val start = tripStart
        dStart = IntArray(nS + 1)
        for (t in 0 until nT) {
            var k = start[t]; val z = start[t + 1] - 1
            while (k < z) { dStart[stop[k] + 1]++; k++ }
        }
        var most = 0
        for (s in 0 until nS) {
            if (dStart[s + 1] > most) most = dStart[s + 1]
            dStart[s + 1] += dStart[s]
        }
        val m = dStart[nS]
        val fill = dStart.copyOf(nS)
        val conn = IntArray(m); val at = IntArray(m)
        for (t in 0 until nT) {
            var k = start[t]; val z = start[t + 1] - 1
            while (k < z) {
                val i = fill[stop[k]]++
                conn[i] = k; at[i] = dep[k]
                k++
            }
        }
        // Each stop's are its own to put in order, so the stops are shared out between a few threads, about as many
        // stop times to each.
        val threads = minOf(4, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)
        val cuts = IntArray(threads + 1)
        var s = 0
        for (w in 1 until threads) {
            while (s < nS && dStart[s] < m.toLong() * w / threads) s++
            cuts[w] = s
        }
        cuts[threads] = nS
        val starts = dStart
        val sorting = (0 until threads).map { w ->
            kotlin.concurrent.thread(name = "kav-week-$w", isDaemon = true) { sortDepartures(starts, conn, at, cuts[w], cuts[w + 1], most) }
        }
        sorting.forEach { it.join() }
        dConn = conn
    }

    // A stop time's departure above its index: in order as numbers, they're in order by departure, then by index.
    private fun sortDepartures(starts: IntArray, conn: IntArray, at: IntArray, from: Int, to: Int, most: Int) {
        val keys = LongArray(most)
        for (s in from until to) {
            val a = starts[s]; val n = starts[s + 1] - a
            if (n < 2) continue
            var sorted = true
            for (i in 1 until n) if (at[a + i] < at[a + i - 1]) { sorted = false; break }
            if (sorted) continue
            for (i in 0 until n) keys[i] = (at[a + i].toLong() shl 32) or conn[a + i].toLong()
            java.util.Arrays.sort(keys, 0, n)
            for (i in 0 until n) conn[a + i] = keys[i].toInt()
        }
    }

    companion object {
        fun read(input: InputStream): Net = read(input.buffered().use { it.readBytes() })

        // A gzip file ends with its size unpacked, so it's unpacked straight into an array of that size, on a thread of
        // its own while this one reads what's unpacked so far, rather than one after the other. The bundle is let go
        // before the stops' departures are worked out, which need as much room again.
        fun read(file: ByteArray): Net = parsed(file).also { it.buildConnections() }

        // Returned without the unpacked bundle, which nothing holds once this ends.
        private fun parsed(file: ByteArray): Net {
            val net = Net()
            val size = unpackedSize(file)
            if (size > 0) {
                val u = Unpacking(file, size)
                net.parse(u.out, u)
                u.finish()
            } else net.parse(file)
            return net
        }

        // What a gzip file says it unpacks to, 0 for a bundle that isn't gzipped.
        private fun unpackedSize(file: ByteArray): Int {
            if (file.size < 18 || file[0] != 0x1f.toByte() || file[1] != 0x8b.toByte()) return 0
            var size = 0
            for (i in 1..4) size = (size shl 8) or (file[file.size - i].toInt() and 0xff)
            if (size !in 1..(1 shl 29)) throw java.io.IOException("the bundle's file says it unpacks to $size bytes")
            return size
        }
    }

    // A gzip file unpacking into out on a thread of its own. await(upTo) holds the reader until that much is in, or all
    // there is, and says how far it's filled: everything once it's all in.
    private class Unpacking(file: ByteArray, size: Int) {
        val out = ByteArray(size)
        private val lock = Object()
        private var filled = 0
        private var done = false
        private var failed: Throwable? = null
        private val thread = kotlin.concurrent.thread(name = "kav-week-unpack", isDaemon = true) {
            try {
                GZIPInputStream(file.inputStream(), 1 shl 16).use { z ->
                    var at = 0
                    while (at < size) {
                        val got = z.read(out, at, minOf(size - at, 1 shl 17))
                        if (got < 0) break
                        at += got
                        synchronized(lock) { filled = at; lock.notifyAll() }
                    }
                    // gzip checks the size itself once it's read to its end.
                    if (at != size || z.read() >= 0) throw java.io.IOException("the bundle isn't the size its file says")
                }
            } catch (e: Throwable) {
                synchronized(lock) { failed = e }
            } finally {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }

        fun await(upTo: Int): Int = synchronized(lock) {
            while (filled < upTo && !done) lock.wait()
            failed?.let { throw java.io.IOException("the bundle didn't unpack", it) }
            if (done) Int.MAX_VALUE else filled
        }

        // The whole file unpacked as it said it would, or the error.
        fun finish() {
            thread.join()
            synchronized(lock) { failed }?.let { throw java.io.IOException("the bundle didn't unpack", it) }
        }
    }
}

// The stop of each stop time. There are far fewer than 65,536 stops, so each takes two bytes rather than four, 13 MB
// less for the week; a bundle with more stops than that keeps four.
class StopOfTime internal constructor(size: Int, stops: Int) {
    private val two: CharArray? = if (stops <= 65536) CharArray(size) else null
    private val four: IntArray? = if (stops <= 65536) null else IntArray(size)
    val size: Int get() = two?.size ?: four!!.size
    operator fun get(i: Int): Int = if (two != null) two[i].code else four!![i]
    internal operator fun set(i: Int, stop: Int) {
        if (two != null) two[i] = stop.toChar() else four!![i] = stop
    }
}
