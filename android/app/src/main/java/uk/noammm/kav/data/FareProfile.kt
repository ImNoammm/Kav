package uk.noammm.kav.data

// Single-ride estimates, by the published fare profiles' discounts. Monthly caps aren't priced here.
// offDayPasses: the discount is off day passes too, a senior citizen's (the price control order, section 7); the others'
// are off single fares and monthly passes only.
enum class FareProfile(val discount: Int, val offDayPasses: Boolean = false) {
    REGULAR(0), YOUTH(50), STUDENT(33), SENIOR_WOMAN(50, true), SENIOR(100),
    DISABILITY(50), NATIONAL_INSURANCE(50), DISCHARGED(100), BLIND(100), GEOGRAPHIC(0), YOUNG(0);

    fun price(agorot: Int, currency: String): Int {
        if (agorot < 0 || currency !in listOf("", "₪", "ILS", "NIS")) return agorot
        return ((agorot.toLong() * (100 - discount) + 50) / 100).toInt()
    }

    // A trip a day pass makes cheaper paying in an app (Kav's server gives its single fares too): the least of the single
    // fares with the discount and the day pass, that at full price unless the discount is off day passes too.
    fun price(trip: Transit.Itinerary): Int =
        if (trip.legs.any { it.kind == Transit.LegKind.TAXI || it.kind == Transit.LegKind.BIKE }) trip.fare
        else if (trip.singleFares < 0 || offDayPasses) price(trip.fare, trip.currency)
        else minOf(price(trip.singleFares, trip.currency), trip.fare)
}
