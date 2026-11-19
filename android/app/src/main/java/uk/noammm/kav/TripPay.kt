package uk.noammm.kav

import uk.noammm.kav.data.Ticket
import uk.noammm.kav.data.TicketTimes
import uk.noammm.kav.data.Transit
import uk.noammm.kav.ui.Mode
import uk.noammm.kav.ui.PayFor
import uk.noammm.kav.ui.RavPass
import uk.noammm.kav.ui.Step
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.TicketView
import uk.noammm.kav.ui.boardingChoice
import uk.noammm.kav.ui.buildSteps
import uk.noammm.kav.ui.isRail
import uk.noammm.kav.ui.modeOf

// Paying on the trip's notification: "Pay" for the ride being waited for (a train or light rail also on the walk to its
// station, paid at the gates), and for a bus once aboard, where its code is scanned; "Show ticket" once Kav has read that
// ride's ticket. The section, line and stop to get off at are Kav's own, for Rav-Pass's section and the button back.
internal data class TripPay(val section: RavPass.Section, val ride: PayFor, val ticket: String?)

internal fun tripPay(journey: ActiveJourney, current: Int, kept: List<Ticket>, nowMs: Long): TripPay? {
    val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    if (steps.isEmpty()) return null
    val r = journey.resolved
    fun pick(ride: Transit.Leg, wait: Transit.Leg?, leg: Int) = boardingChoice(ride, wait, journey.chosen[leg] ?: 0).first
    fun gated(ride: Transit.Leg) = RavPass.section(ride, r).let { it == RavPass.Section.TRAIN || it == RavPass.Section.LIGHT_RAIL }
    val (ride, aboard) = when (val s = steps[current.coerceIn(0, steps.lastIndex)]) {
        is Step.Wait -> pick(s.ride, s.wait, s.legIndex) to false
        is Step.Walk -> s.toRide?.let { pick(it, s.toWait, s.toLeg) }?.takeIf(::gated)?.let { it to false } ?: return null
        is Step.Start -> s.board?.let { pick(it, s.boardWait, s.boardLeg) }?.takeIf(::gated)?.let { it to false } ?: return null
        is Step.Ride -> pick(s.ride, s.wait, s.legIndex) to true
        else -> return null
    }
    val section = RavPass.section(ride, r)
    val line = r.line(ride.lineId)?.number
    val payFor = PayFor(line, r.stopName(ride.toStop))
    val ticket = kept.filter { it.overAt > nowMs && TicketView.matches(it, section, line, ride.dep * 1000) }.maxByOrNull { it.readAt }
    if (ticket != null) return TripPay(section, payFor, ticket.key)
    // Aboard a train or light rail, its ticket was bought at the gates: nothing to pay there.
    if (aboard && section != RavPass.Section.BUS && section != RavPass.Section.ANY) return null
    return TripPay(section, payFor, null)
}

// The get-off alert of a train ride, with a train entrance still open in Kav: "Exit ticket", which opens Rav-Pass's
// train section, where an open entrance shows its "I'm off the train".
internal fun exitTicketDue(journey: ActiveJourney, current: Int, kept: List<Ticket>, nowMs: Long): Boolean {
    val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    val s = steps.getOrNull(current.coerceIn(0, steps.lastIndex.coerceAtLeast(0))) as? Step.Ride ?: return false
    val ride = boardingChoice(s.ride, s.wait, journey.chosen[s.legIndex] ?: 0).first
    return RavPass.section(ride, journey.resolved) == RavPass.Section.TRAIN && TicketTimes.openEntrance(kept, nowMs) != null
}

// Ten minutes before the free transfers of the ticket a trip rides on run out (Ticket.validUntil, Kav's estimate for a
// purchase it saw), while a bus or light rail ride is still to be boarded: that one may cost again. Old Kav's
// transferAlert (before 4.3.2), with the read ticket in place of HopOn's.
internal fun transferAlert(journey: ActiveJourney, current: Int, kept: List<Ticket>, nowMs: Long): TripAlert? {
    val ends = TicketTimes.transferTicket(kept, journey.trip.dep * 1000, nowMs)?.validUntil ?: return null
    if (!TicketTimes.transferEndsSoon(ends, nowMs)) return null
    val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
    if (steps.isEmpty()) return null
    val at = current.coerceIn(0, steps.lastIndex)
    val r = journey.resolved
    fun covered(ride: Transit.Leg): Boolean {
        val agency = r.line(ride.lineId)?.agencyId ?: -1
        val type = r.routeType(agency)
        return !isRail(type, agency) && modeOf(type) in setOf(Mode.BUS, Mode.TRAM, Mode.SUBWAY)
    }
    fun chosen(ride: Transit.Leg, wait: Transit.Leg?, leg: Int) = boardingChoice(ride, wait, journey.chosen[leg] ?: 0).first
    (steps[at] as? Step.Ride)?.let { if (!covered(chosen(it.ride, it.wait, it.legIndex))) return null }
    val next = steps.drop(at).firstOrNull { it is Step.Wait } as? Step.Wait ?: return null
    if (!covered(chosen(next.ride, next.wait, next.legIndex))) return null
    val left = TicketTimes.minutesLeft(ends, nowMs)
    return TripAlert(
        "transfer-$ends", T("Free transfer ends in $left min", "המעבר בחינם מסתיים בעוד $left דק׳"),
        T("A ride you board after that may be paid again (Kav's estimate)", "ייתכן שנסיעה שתעלו אליה אחרי זה תשולם מחדש (הערכה של Kav)"),
        T("Your free transfer ends in ", "המעבר בחינם מסתיים בעוד ") + T.count(left, "1 minute", "דקה אחת", "minutes", "דקות"),
    )
}
