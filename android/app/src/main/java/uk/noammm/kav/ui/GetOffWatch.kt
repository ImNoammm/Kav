package uk.noammm.kav.ui

// "Tell me when to get off here" (the Lines page): a stop on a line's way, the stops before it, and the phone's fixes in
// order. It says NEXT once, when the phone is at the stop before the chosen one, and ARRIVED once at the chosen one.
class GetOffStop(val name: String, val lat: Double, val lon: Double)

enum class GetOffEvent { NONE, NEXT, ARRIVED }

// Nearer than this to the chosen stop, the alert isn't set: the rider is there already, or about to board there.
const val GET_OFF_NEAR_M = 400.0
// A walk is under 3 m/s; a bus that has been under way is over this at some moment.
const val GET_OFF_MOVING_MS = 4.0f
// The alert ends by itself.
const val GET_OFF_LIFE_MS = 3 * 3600 * 1000L

// Metres between two points, close enough for a city.
fun getOffMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon) * Math.cos(Math.toRadians((aLat + bLat) / 2))
    return 6_371_000.0 * Math.sqrt(dLat * dLat + dLon * dLon)
}

// How near a stop counts, with a rough fix given more room.
fun getOffRadius(accuracy: Float): Double = 150.0 + accuracy.coerceIn(0f, 100f)

class GetOffWatch(val stops: List<GetOffStop>, val target: Int) {
    private var moved = false
    private var away = false
    private var nextDone = false
    private var arrived = false

    // False alarms: nothing until the phone has moved like a vehicle and has been well away from the chosen stop; the stop
    // before only counts when the phone is nearer it than the chosen one, and the chosen one when it is the nearer.
    fun onFix(lat: Double, lon: Double, accuracy: Float, speed: Float): GetOffEvent {
        if (arrived || target !in stops.indices) return GetOffEvent.NONE
        if (speed >= GET_OFF_MOVING_MS) moved = true
        val t = stops[target]
        val toTarget = getOffMeters(lat, lon, t.lat, t.lon)
        if (toTarget > GET_OFF_NEAR_M) away = true
        if (!moved || !away) return GetOffEvent.NONE
        val radius = getOffRadius(accuracy)
        val before = if (target >= 1) stops[target - 1] else null
        val toBefore = if (before != null) getOffMeters(lat, lon, before.lat, before.lon) else Double.MAX_VALUE
        if (toTarget < radius && toTarget <= toBefore) {
            arrived = true
            return GetOffEvent.ARRIVED
        }
        // The stop before the chosen one, when that isn't where the ride starts.
        if (!nextDone && target >= 2 && toBefore < radius && toBefore < toTarget) {
            nextDone = true
            return GetOffEvent.NEXT
        }
        return GetOffEvent.NONE
    }
}
