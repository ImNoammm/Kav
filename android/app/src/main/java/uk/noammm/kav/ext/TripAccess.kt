package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.ActiveJourney
import uk.noammm.kav.RecentTrip
import uk.noammm.kav.data.Transit
import uk.noammm.kav.journeyArrival
import uk.noammm.kav.ui.Fix
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
        // Its home screen widgets may show what it reads.
        Widgets.refresh(identity)
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

    // The Recent trips list, newest first, as Kav.trips gives it. A trip from where the rider was is saved with a
    // placeholder start (DirectionsOnline): its from is null, as Kav.trips says.
    fun history(trips: List<RecentTrip>): JSONArray = JSONArray(trips.map { t ->
        val from = t.from?.takeUnless { it.name in uk.noammm.kav.ui.SAVED_STARTS }
        JSONObject().put("from", from?.let(::place) ?: JSONObject.NULL).put("to", place(t.to))
            .put("stopovers", JSONArray(t.stopovers.map(::place))).put("at", t.at / 1000)
            .put("planner", t.planner.takeIf { it.isNotEmpty() }?.let { "ext:$it" }.orEmpty())
    })

    private fun place(p: Transit.Place): JSONObject =
        JSONObject().put("name", p.name).put("detail", p.detail).put("lat", p.lat).put("lon", p.lon)
}

// The trip the rider follows, told to the extensions the rider allows (TripAccess.FOLLOW) as it changes: MainActivity
// passes each change of the journey and its step (seen), and a listener set during a trip is told it at once (catchUp).
object TripEvents {
    // A followed trip as far as its events go: the trip itself (a new one on the way is a replan), its step, the
    // index of its last step (Arrive), so an end there is an arrival, and the step card navigation shows (-1 while
    // navigation isn't open). The rider can swipe to another card than the trip's step to look at it.
    class Seen(val trip: Transit.Itinerary, val step: Int, val last: Int, val shown: Int = -1)

    // The events between what was seen and what is now, in order.
    internal fun changes(was: Seen?, now: Seen?): List<String> = when {
        was == null && now == null -> emptyList()
        was == null -> listOf("start")
        now == null -> listOf("end")
        was.trip !== now.trip -> listOf("replan")
        was.step != now.step -> listOf("step")
        was.shown != now.shown -> listOf("shown")
        else -> emptyList()
    }

    // An end at the trip's last step is an arrival; any other, the rider ending it.
    internal fun reason(was: Seen) = if (was.step >= was.last) "arrived" else "ended"

    // An event as Kav.onTrip tells it: the trip in the shape slots and parts get it (Slots.trip), its places' names, the
    // step the rider is on and the one navigation shows, every step of the trip (stepList), and when it gets there as
    // Kav estimates it now (eta, seconds since 1970, the trip notification's "Arrive").
    internal fun json(
        event: String, j: ActiveJourney, step: Int, steps: List<Step>, reason: String? = null, shown: Int = -1,
        fix: Fix? = null, now: Long = System.currentTimeMillis() / 1000,
    ): JSONObject {
        val r = j.resolved
        val s = steps.getOrNull(step)
        val leg = legOf(s, j.chosen)
        val stepJson = JSONObject().put("index", step).put("count", steps.size).put("kind", kind(s))
            .put("leg", leg?.let { Pipeline.legJson(it, r) } ?: JSONObject.NULL).put("legIndex", legIndex(s, j.trip))
            .put("shown", shown)
        return JSONObject().put("event", event).put("trip", Slots.trip(j.trip, r)).put("from", j.fromLabel).put("to", j.toLabel)
            .put("step", stepJson).put("steps", stepList(j, steps)).put("eta", journeyArrival(j, steps, step, fix, now))
            .apply { if (reason != null) put("reason", reason) }
    }

    // The arrival last told, with its trip, so "eta" is told only as Kav's estimate moves to another minute.
    private var told: Pair<Transit.Itinerary, Long>? = null

    // Kav working out the trip's arrival again (the trip service, every few seconds, with Kav in the background too):
    // an "eta" event when it moved to another minute. A trip just started or planned again on the way was told its
    // arrival with that event.
    fun arrival(j: ActiveJourney, step: Int, fix: Fix?) {
        if (!Native.listensToTrips()) return
        val steps = uk.noammm.kav.ui.buildSteps(j.trip, j.fromLabel, j.toLabel)
        val now = System.currentTimeMillis() / 1000
        val eta = journeyArrival(j, steps, step, fix, now)
        val was = synchronized(this) { told.also { told = j.trip to eta } }
        if (was == null || was.first !== j.trip || was.second / 60 == eta / 60) return
        val shown = uk.noammm.kav.TripBridge.shown?.invoke() ?: -1
        Native.tripEvent(json("eta", j, step, steps, shown = shown, fix = fix, now = now).toString())
    }

    // Each step of the trip, in navigation's order, so an extension knows what every one is: its kind, the index of its
    // leg in trip.legs (-1 for none), the line a wait or ride is for (the one the rider chose there), and the place it's
    // about: where it starts, the stop walked to, the stop boarded at, the stop got off at, or where the trip ends.
    internal fun stepList(j: ActiveJourney, steps: List<Step>): JSONArray = JSONArray(steps.map { s ->
        val r = j.resolved
        val leg = legOf(s, j.chosen)
        val line = if (s is Step.Wait || s is Step.Ride) leg?.let { it.shortName.ifBlank { r.line(it.lineId)?.number.orEmpty() }.ifBlank { null } } else null
        val place = when (s) {
            is Step.Start -> s.label
            is Step.Walk -> r.stop(s.toStop)?.shown() ?: s.toName
            is Step.Wait -> leg?.let { r.stop(it.fromStop)?.shown() }
            is Step.Ride, is Step.Taxi, is Step.Cycle -> leg?.let { r.stop(it.toStop)?.shown() }
            is Step.Arrive -> s.label
        }
        JSONObject().put("kind", kind(s)).put("legIndex", legIndex(s, j.trip))
            .put("line", line ?: JSONObject.NULL).put("place", place ?: JSONObject.NULL)
    })

    // A step's leg's index in trip.legs, -1 for a step with none of its own (a walk is made of one or more walks).
    internal fun legIndex(s: Step?, trip: Transit.Itinerary): Int = when (s) {
        is Step.Wait -> s.legIndex
        is Step.Ride -> s.legIndex
        is Step.Taxi -> trip.legs.indexOf(s.leg)
        is Step.Cycle -> trip.legs.indexOf(s.leg)
        else -> -1
    }

    // A step's leg, a wait's and a ride's the bus the rider chose there.
    internal fun legOf(s: Step?, chosen: Map<Int, Int>): Transit.Leg? = when (s) {
        is Step.Wait -> uk.noammm.kav.ui.boardingChoice(s.ride, s.wait, chosen[s.legIndex] ?: 0).first
        is Step.Ride -> uk.noammm.kav.ui.boardingChoice(s.ride, s.wait, chosen[s.legIndex] ?: 0).first
        is Step.Walk -> s.leg
        is Step.Taxi -> s.leg
        is Step.Cycle -> s.leg
        else -> null
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
        val shown = uk.noammm.kav.TripBridge.shown?.invoke() ?: -1
        val fix = uk.noammm.kav.TripBridge.fixNow?.invoke()
        Native.tripEvent(json("start", j, step, steps, shown = shown, fix = fix).toString(), identity)
    }
}
