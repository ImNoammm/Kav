package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.data.Transit
import uk.noammm.kav.ui.Step
import java.io.File

// The rider's trips, for an extension only once the rider allows it, each on the extension's page and off until then:
// "trips.history", the trips Kav's Recent trips list has (Kav.trips), and "trips.follow", the trip the rider follows as
// it starts, moves from step to step and ends (Kav.onTrip, TripEvents). A package declares each as a contribution, and
// its page in Settings › Extensions lists it, so the rider knows before allowing it.
object TripAccess {
    const val HISTORY = "trips.history"
    const val FOLLOW = "trips.follow"
    val CAPABILITIES = listOf(HISTORY, FOLLOW)

    // -- The rider's switches: "<identity>|<capability>", in their own file ----------------------------------------------

    @Volatile private var file: File? = null
    @Volatile var allowed: Set<String> = emptySet()
        private set

    fun open(dir: File) {
        file = File(dir, "trips.json")
        allowed = runCatching { JSONObject(file!!.readText()).optJSONArray("allowed")?.strings()?.toSet() }.getOrNull().orEmpty()
    }

    fun allows(identity: String, capability: String) = "$identity|$capability" in allowed

    @Synchronized fun set(dir: File, identity: String, capability: String, on: Boolean) {
        val key = "$identity|$capability"
        allowed = if (on) allowed + key else allowed - key
        save(dir)
    }

    // Everything allowed to one extension, forgotten (it was uninstalled). Nothing written when it had nothing.
    @Synchronized fun drop(dir: File, identity: String) {
        val mine = allowed.filter { it.substringBeforeLast('|') == identity }
        if (mine.isEmpty()) return
        allowed = allowed - mine.toSet()
        save(dir)
    }

    private fun save(dir: File) {
        val f = file ?: File(dir, "trips.json").also { file = it }
        Files.replace(f, JSONObject().put("allowed", JSONArray(allowed.sorted())).toString().toByteArray())
    }

    internal fun forget() { allowed = emptySet(); file = null }

    // -- What an extension is told ----------------------------------------------------------------------------------------

    // The Recent trips list, newest first, as Kav.trips gives it.
    fun history(trips: List<RecentTrip>): JSONArray = JSONArray(trips.map { t ->
        JSONObject().put("from", t.from?.let(::place) ?: JSONObject.NULL).put("to", place(t.to))
            .put("stopovers", JSONArray(t.stopovers.map(::place))).put("at", t.at / 1000)
            .put("planner", t.planner.takeIf { it.isNotEmpty() }?.let { "ext:$it" }.orEmpty())
    })

    private fun place(p: Transit.Place): JSONObject =
        JSONObject().put("name", p.name).put("detail", p.detail).put("lat", p.lat).put("lon", p.lon)
}

// The trip the rider follows, told to the extensions the rider allows (TripAccess.FOLLOW) as it changes: MainActivity
// passes each change of the journey and its step (seen), and a listener set during a trip is told it at once (catchUp).
object TripEvents {
    // A followed trip as far as its events go: the trip itself (a new one on the way is a replan), its step, and the
    // index of its last step (Arrive), so an end there is an arrival.
    class Seen(val trip: Transit.Itinerary, val step: Int, val last: Int)

    // The events between what was seen and what is now, in order.
    internal fun changes(was: Seen?, now: Seen?): List<String> = when {
        was == null && now == null -> emptyList()
        was == null -> listOf("start")
        now == null -> listOf("end")
        was.trip !== now.trip -> listOf("replan")
        was.step != now.step -> listOf("step")
        else -> emptyList()
    }

    // An end at the trip's last step is an arrival; any other, the rider ending it.
    internal fun reason(was: Seen) = if (was.step >= was.last) "arrived" else "ended"

    // An event as Kav.onTrip tells it: the trip in the shape slots and parts get it (Slots.trip), its places' names, and
    // the step the rider is on.
    internal fun json(event: String, j: ActiveJourney, step: Int, steps: List<Step>, reason: String? = null): JSONObject {
        val r = j.resolved
        val s = steps.getOrNull(step)
        val legIndex = when (s) {
            is Step.Wait -> s.legIndex
            is Step.Ride -> s.legIndex
            is Step.Taxi -> j.trip.legs.indexOf(s.leg)
            is Step.Cycle -> j.trip.legs.indexOf(s.leg)
            else -> -1
        }
        val leg = when (s) {
            is Step.Wait -> uk.noammm.kav.ui.boardingChoice(s.ride, s.wait, j.chosen[s.legIndex] ?: 0).first
            is Step.Ride -> uk.noammm.kav.ui.boardingChoice(s.ride, s.wait, j.chosen[s.legIndex] ?: 0).first
            is Step.Walk -> s.leg
            is Step.Taxi -> s.leg
            is Step.Cycle -> s.leg
            else -> null
        }
        val stepJson = JSONObject().put("index", step).put("count", steps.size).put("kind", kind(s))
            .put("leg", leg?.let { Pipeline.legJson(it, r) } ?: JSONObject.NULL).put("legIndex", legIndex)
        return JSONObject().put("event", event).put("trip", Slots.trip(j.trip, r)).put("from", j.fromLabel).put("to", j.toLabel)
            .put("step", stepJson).apply { if (reason != null) put("reason", reason) }
    }

    internal fun kind(s: Step?): String = when (s) {
        is Step.Start -> "start"
        is Step.Walk -> "walk"
        is Step.Wait -> "wait"
        is Step.Ride -> "ride"
        is Step.Taxi -> "taxi"
        is Step.Cycle -> "cycle"
        is Step.Arrive -> "arrive"
        null -> "none"
    }

    // A listener set while a trip is under way: told it at once, as its start.
    fun catchUp(identity: String) {
        val (j, step) = uk.noammm.kav.TripBridge.journey?.invoke() ?: return
        val steps = uk.noammm.kav.ui.buildSteps(j.trip, j.fromLabel, j.toLabel)
        Native.tripEvent(json("start", j, step, steps).toString(), identity)
    }
}
