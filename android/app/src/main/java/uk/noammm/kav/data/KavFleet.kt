package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

// What kind of bus a live bus is, from Kav's server (live.py bus/), out of the Ministry of Transport's daily file of the
// operators' buses: what it runs on, its year, its size and its seats. Asked once a bus while its card is open, by the
// plate in its vehicle id, from the server live times come from (Kav's or the rider's own; another provider's buses
// aren't asked about). Nothing when the bus isn't in the file. The file says nothing of a bus's accessibility.
object KavFleet {
    // size: "bus", "articulated", "midibus", "minibus" or ""; power: "diesel", "electric", "gas" or ""; 0 where not known.
    class Bus(val size: String, val seats: Int, val year: Int, val power: String)

    private var buses by mutableStateOf<Map<String, Bus?>>(emptyMap())

    // Asks about the bus once, unless its live times come from another provider.
    suspend fun ask(vehicleId: String) = withContext(Dispatchers.IO) {
        val plate = plateOf(vehicleId) ?: return@withContext
        synchronized(this@KavFleet) {
            if (buses.containsKey(plate)) return@withContext
            buses = buses + (plate to null)
        }
        val got = KavLive.bus(plate)?.let { runCatching { parse(it) }.getOrNull() } ?: return@withContext
        synchronized(this@KavFleet) { buses = buses + (plate to got) }
    }

    fun of(vehicleId: String): Bus? = plateOf(vehicleId)?.let { buses[it] }

    // The plate in a vehicle id (route_first departure_plate), digits only, as Kav's server knows buses by it.
    internal fun plateOf(vehicleId: String): String? =
        vehicleId.substringAfterLast('_', "").takeIf { it.length in 5..9 && it.all(Char::isDigit) }

    internal fun parse(body: String): Bus? {
        val b = JSONObject(body).optJSONObject("bus") ?: return null
        return Bus(b.optString("size"), b.optInt("seats"), b.optInt("year"), b.optString("power"))
            .takeIf { it.size.isNotEmpty() || it.seats > 0 || it.year > 0 || it.power.isNotEmpty() }
    }
}
