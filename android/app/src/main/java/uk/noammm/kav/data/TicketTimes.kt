package uk.noammm.kav.data

// Which of the tickets Kav read matters right now, for the places outside Kav's screens that show one: the trip's
// notification, the get-off alert, the quick settings tile, the launcher shortcut and the ticket's own notification.
internal object TicketTimes {
    // The ticket to show: the latest read of those still live.
    fun latest(kept: List<Ticket>, now: Long): Ticket? = kept.filter { it.overAt > now }.maxByOrNull { it.readAt }

    // A train entrance still open: no exit ticket read after it (Tickets.merge drops the entrance when one is), and not
    // past its own end.
    fun openEntrance(kept: List<Ticket>, now: Long): Ticket? =
        kept.filter { it.kind == TicketScreens.Kind.TRAIN_ENTRANCE && it.overAt > now }.maxByOrNull { it.readAt }

    // The ticket whose free transfers a trip rides on: bought by a purchase Kav saw from half an hour before the trip
    // leaves, its transfers not yet over; of several, the one whose transfers last longest.
    fun transferTicket(kept: List<Ticket>, tripDep: Long, now: Long): Ticket? = kept.filter {
        val until = it.validUntil
        val paid = it.paidAt
        until != null && until > now && paid != null && paid >= tripDep - 30 * Ticket.MINUTE
    }.maxByOrNull { it.validUntil!! }

    // The alert comes in the last ten minutes of the transfers, as old Kav's did.
    const val TRANSFER_WARN = 10 * Ticket.MINUTE

    fun transferEndsSoon(until: Long, now: Long) = until - now in 1..TRANSFER_WARN

    // Whole minutes left, rounded up, so "ends in 1 min" is never said with nothing left.
    fun minutesLeft(until: Long, now: Long) = ((until - now + Ticket.MINUTE - 1) / Ticket.MINUTE).coerceAtLeast(1)

    // A ticket time as Kav shows it: in Israel's time, as Rav-Pass writes its own and the ride history counts, whatever
    // zone the phone is set to.
    fun clock(ms: Long): String = israel("HH:mm").format(java.util.Date(ms))

    // Its day ("19.11") when it isn't today's by Israel's calendar, else null.
    fun day(ms: Long, now: Long): String? {
        val f = israel("d.M")
        return f.format(java.util.Date(ms)).takeIf { it != f.format(java.util.Date(now)) }
    }

    private fun israel(pattern: String) = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = Ticket.ISRAEL }
}
