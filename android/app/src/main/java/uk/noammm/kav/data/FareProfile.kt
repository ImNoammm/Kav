package uk.noammm.kav.data

// Single-ride estimates, using Rav-Pass's published profile rules. Monthly caps are priced by the payment service.
enum class FareProfile(val discount: Int) {
    REGULAR(0), YOUTH(50), STUDENT(33), SENIOR_WOMAN(50), SENIOR(100),
    DISABILITY(50), NATIONAL_INSURANCE(50), DISCHARGED(100), BLIND(100), GEOGRAPHIC(0), YOUNG(0);

    fun price(agorot: Int, currency: String): Int {
        if (agorot < 0 || currency !in listOf("", "₪", "ILS", "NIS")) return agorot
        return ((agorot.toLong() * (100 - discount) + 50) / 100).toInt()
    }

    fun price(trip: Transit.Itinerary): Int =
        if (trip.legs.any { it.kind == Transit.LegKind.TAXI || it.kind == Transit.LegKind.BIKE }) trip.fare
        else price(trip.fare, trip.currency)
}
