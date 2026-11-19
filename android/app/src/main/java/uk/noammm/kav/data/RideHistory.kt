package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject

// The rides Kav read a ticket for, kept after the ticket ends for the ride history and Home's summary (Noam, 19 Nov 2026,
// as old Kav had): the ride only, never a ticket's code, number or passengers' names. On this phone only, in no backup,
// and deleted when paying is turned off or by Clear history.
internal data class Ride(
    val key: String,
    val kind: TicketScreens.Kind,
    val line: String?,
    val from: String?,
    val to: String?,
    // When it was paid (Kav saw it bought) or validated (Rav-Pass's shown time), else when its ticket was read.
    val at: Long,
    val riders: Int,
    // The fare's reach in km, when Kav saw it bought and Rav-Pass's Approve screen named it.
    val band: Int? = null,
    // The price in agorot Rav-Pass quoted on that screen (Ticket.price): its quote, not the month's charge.
    val price: Int? = null,
) {
    fun json(): JSONObject = JSONObject().put("key", key).put("kind", kind.name).put("line", line).put("from", from).put("to", to)
        .put("at", at).put("riders", riders).put("band", band).put("price", price)

    companion object {
        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null

        fun of(o: JSONObject): Ride? = runCatching {
            Ride(o.getString("key"), TicketScreens.Kind.valueOf(o.getString("kind")), o.str("line"), o.str("from"), o.str("to"),
                o.getLong("at"), o.optInt("riders", 1), if (o.has("band") && !o.isNull("band")) o.getInt("band") else null,
                if (o.has("price") && !o.isNull("price")) o.getInt("price") else null)
        }.getOrNull()

        fun of(t: Ticket): Ride = Ride(t.key, t.kind, t.line, t.from, t.to, t.start, maxOf(1, t.expected, t.passengers.size), t.band, t.price)
    }
}

internal object RideHistory {
    // A ticket kept, as its ride: added, or the same ride (by its ticket's key) brought up to date with what was read since,
    // keeping when it was first paid or validated.
    fun record(rides: List<Ride>, t: Ticket): List<Ride> {
        val r = Ride.of(t)
        val old = rides.firstOrNull { it.key == r.key } ?: return rides + r
        val merged = r.copy(at = minOf(old.at, r.at), line = r.line ?: old.line, from = r.from ?: old.from, to = r.to ?: old.to,
            riders = maxOf(old.riders, r.riders), band = r.band ?: old.band, price = r.price ?: old.price)
        return rides.map { if (it.key == r.key) merged else it }
    }

    // The rides' quoted prices added up, in agorot, only when every one has its price: a total of some would read as
    // what they all cost.
    fun total(rides: List<Ride>): Int? = rides.takeIf { it.isNotEmpty() && it.all { r -> r.price != null } }?.sumOf { it.price!! }

    fun text(rides: List<Ride>): String = JSONObject().put("rides", JSONArray(rides.map { it.json() })).toString()

    fun list(text: String?): List<Ride> = runCatching {
        val a = JSONObject(text ?: return emptyList()).getJSONArray("rides")
        (0 until a.length()).mapNotNull { Ride.of(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
