package uk.noammm.kav.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import uk.noammm.kav.data.KavFleet

// One short line on what kind of bus a live bus is (KavFleet), where its plate is shown: what it runs on, its year, its
// size and its seats, as the ministry's file of the operators' buses has them; null when the file says nothing of it.
internal fun busKindWords(b: KavFleet.Bus): String? {
    val power = when (b.power) {
        "electric" -> T("Electric", "חשמלי")
        "diesel" -> T("Diesel", "דיזל")
        "gas" -> T("Natural gas", "גז טבעי")
        else -> null
    }
    val size = when (b.size) {
        "articulated" -> T("articulated", "מפרקי")
        "midibus" -> T("midibus", "מידיבוס")
        "minibus" -> T("minibus", "מיניבוס")
        else -> null
    }
    val body = listOfNotNull(size, b.seats.takeIf { it > 0 }?.let { T("$it seats", "$it מושבים") }).joinToString(", ").ifEmpty { null }
    return listOfNotNull(power, b.year.takeIf { it > 0 }?.toString(), body).joinToString(" · ").ifEmpty { null }
        ?.replaceFirstChar { it.uppercaseChar() }
}

// The line for the bus with this vehicle id, asked of Kav's server once while it shows.
@Composable
internal fun rememberBusKind(vehicleId: String): String? {
    if (KavFleet.plateOf(vehicleId) == null) return null
    LaunchedEffect(vehicleId) { KavFleet.ask(vehicleId) }
    return KavFleet.of(vehicleId)?.let(::busKindWords)
}
