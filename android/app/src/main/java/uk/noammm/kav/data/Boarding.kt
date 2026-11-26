package uk.noammm.kav.data

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Boarding detection's decision logic on plain values, with nothing Android in it, so the same code the app runs is
// the code plain JVM tests run. BoardingWatch feeds it phone fixes and the buses Kav's server sees near them, and
// only posts the "looks like you're on line X" suggestion from what comes back. All times are epoch seconds.
object Boarding {

    // A phone fix: where, when, and its speed in m/s (-1 when the phone doesn't say).
    class Fix(val lat: Double, val lon: Double, val at: Long, val speed: Float)

    // A bus the server saw: its stable key across samples, its line's number, and where.
    class Sighting(val key: String, val line: String, val lat: Double, val lon: Double)

    // One moment of the watch: when, whether the phone was moving at a vehicle's pace, and the keys of the buses
    // near it then.
    class Sample(val at: Long, val moving: Boolean, val buses: List<String>)

    // Great-circle distance in meters.
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    // The keys of the buses within the radius of the fix.
    fun near(fix: Fix, buses: List<Sighting>, radiusM: Double): List<String> =
        buses.filter { meters(fix.lat, fix.lon, it.lat, it.lon) <= radiusM }.map { it.key }

    // Whether the phone is moving at a vehicle's pace: its own speed reading, or, when it doesn't give one, the
    // speed implied by the distance from the previous fix over the time between them.
    fun moving(prev: Fix?, fix: Fix, minMps: Double): Boolean {
        if (fix.speed >= minMps) return true
        if (prev == null) return false
        val dt = fix.at - prev.at
        if (dt <= 0) return false
        return meters(prev.lat, prev.lon, fix.lat, fix.lon) / dt >= minMps
    }

    // The bus the rider plausibly boarded: exactly one bus near on each of the last `needed` samples, every one of
    // them moving, the newest no older than maxAgeSecs. Standing still, a gap, a stale fix, or two buses that both
    // fit (a bus and its twin on the same line) are no candidate: a wrong guess costs more than a missed one.
    fun candidate(samples: List<Sample>, needed: Int, maxAgeSecs: Long, now: Long): String? {
        if (samples.size < needed) return null
        val tail = samples.takeLast(needed)
        if (now - tail.last().at > maxAgeSecs) return null
        if (tail.any { !it.moving }) return null
        val common = tail.map { it.buses.toSet() }.reduce { a, b -> a intersect b }
        return common.singleOrNull()
    }
}
