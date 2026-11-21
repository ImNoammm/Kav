package uk.noammm.kav

import android.content.Context
import org.json.JSONObject
import uk.noammm.kav.data.Passenger
import uk.noammm.kav.data.Ticket
import uk.noammm.kav.data.TicketScreens

// A ticket an extension sold (Kav.addTicket), in Kav's own tickets and ride history as a read one is: on Home's pay
// card, on the ride, in "This month". It ends when the extension says, and stays when the extension is switched off or
// removed. Its key is always "ext:<the extension's identity>:<its own id>", so no extension can change another's
// ticket, nor one Kav read.
internal object ExtTickets {
    const val PREFIX = "ext:"

    fun key(identity: String, id: String) = if (id.startsWith("$PREFIX$identity:")) id else "$PREFIX$identity:$id"

    fun mine(t: Ticket) = t.key.startsWith(PREFIX)

    // The ticket from its JSON ({"key", "kind", "line", "from", "to", "boughtAt", "validUntil", "riders", "price",
    // "number", "codes", "needsExit"}), as Kav keeps it: a passenger for each code, drawn as a QR code.
    fun ticket(identity: String, o: JSONObject, now: Long): Ticket {
        fun text(k: String) = o.optString(k).trim().takeIf { o.has(k) && !o.isNull(k) && it.isNotEmpty() }?.take(200)
        val id = text("key") ?: throw IllegalArgumentException("a ticket needs a key")
        val kind = when (o.optString("kind")) {
            "BUS" -> TicketScreens.Kind.BUS
            "LIGHT_RAIL" -> TicketScreens.Kind.LIGHT_RAIL
            // A train ticket the rider still exits the station with is an entrance; one that needs no exit is the one
            // for the exit gate.
            "TRAIN" -> if (o.optBoolean("needsExit")) TicketScreens.Kind.TRAIN_ENTRANCE else TicketScreens.Kind.TRAIN_EXIT
            else -> throw IllegalArgumentException("kind is BUS, TRAIN or LIGHT_RAIL")
        }
        val bought = o.optLong("boughtAt", now).takeIf { it > 0 } ?: now
        val until = o.optLong("validUntil", 0)
        if (until <= bought) throw IllegalArgumentException("validUntil must be after boughtAt")
        val codes = o.optJSONArray("codes")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { c -> c.isNotEmpty() } } }.orEmpty()
            .distinct().take(20)
        val riders = maxOf(1, o.optInt("riders", 1), codes.size)
        return Ticket(
            key = key(identity, id), kind = kind, line = text("line"), shown = null, number = text("number"),
            passengers = codes.map { Passenger(null, it, "QR_CODE") }, paidAt = bought, readAt = now, shownAt = bought,
            expected = riders, from = text("from"), to = text("to"),
            price = if (o.has("price") && !o.isNull("price")) o.optInt("price").takeIf { it >= 0 } else null, endsAt = until,
        )
    }

    // Kept on the main thread, where Kav's screens read its tickets.
    fun add(ctx: Context, identity: String, o: JSONObject) {
        val t = ticket(identity, o, System.currentTimeMillis())
        val app = ctx.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { TicketStore.put(app, t) } }
    }
}
