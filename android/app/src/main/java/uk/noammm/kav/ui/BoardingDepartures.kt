package uk.noammm.kav.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.Transit
import uk.noammm.kav.data.nextDepartures

// The last ones found for a boarding, kept for this run of the app: a card shown again (Home, the trip) starts from them
// while they're refreshed, instead of saying it's refreshing.
private val lastFound = HashMap<String, List<Transit.Departure>>()
private fun keyOf(ride: Transit.Leg) = "${ride.fromStop}:${ride.toStop}:${ride.lineId}:${ride.tripId}"

// A boarding's own line's next departures: the plan's while any is still to come; once they've all gone (or are
// cancelled), the line's next ones from the stop, live buses before timetable rows, so its card shows when the line comes
// next. Only its own line: another line is never listed in a line's card.
@Composable
internal fun rememberLineDepartures(ride: Transit.Leg, wait: Transit.Leg?, r: Transit.Resolved, now: Long): List<Transit.Departure> {
    val planned = r.departures(ride, wait, now)
    val upcoming = planned.any { it.timeUtc >= now - 60 && it.state != Transit.TimeState.CANCELED }
    val ctx = LocalContext.current.applicationContext
    var found by remember(ride) { mutableStateOf(synchronized(lastFound) { lastFound[keyOf(ride)] }) }
    LaunchedEffect(ride, upcoming, now / 20, KavLive.origin) {
        if (upcoming) return@LaunchedEffect
        val net = try { uk.noammm.kav.loadNet(ctx) } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return@LaunchedEffect
        }
        val code = r.stop(ride.fromStop)?.code?.toIntOrNull()
        val board = if (KavLive.stopsEnabled && code != null) withContext(Dispatchers.IO) {
            try { KavLive.stops(listOf(code)) } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { null }
        } else null
        val number = ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }
        val next = withContext(Dispatchers.Default) {
            net.nextDepartures(ride, r, now, board)
                .filter { it.number.ifBlank { net.rShort[it.route] } == number }
                .flatMap { it.departures }.distinctBy { it.timeUtc }.sortedBy { it.timeUtc }.take(3)
        }
        synchronized(lastFound) { lastFound[keyOf(ride)] = next }
        found = next
    }
    return if (upcoming) planned else found?.takeIf { it.isNotEmpty() } ?: planned
}
