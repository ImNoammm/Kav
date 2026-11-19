package uk.noammm.kav.ext

import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Transit

// What Kav keeps of an extension planner's answer: only what its own screens need in
// order to draw a route (OPEN-PLAN section 3): legs, each ending no earlier than it starts, in order, and a route that
// ends no earlier than it starts. Whether a route is sensible (fast walks, a time outside what was asked) is the
// extension's business. When every route is dropped the answer isn't "no routes": the planner is said to have answered
// routes Kav can't show.
//
// KavPlan.parse holds an extension's answer to the message ceiling (Bounds.CHARS), so what Kav then sends route rules
// and trip slots about it stays within Pipeline.MAX_MESSAGE.
object PlanChecks {
    object Bounds {
        // The answer's length in characters (as it comes, before it's read).
        const val CHARS = Pipeline.MAX_MESSAGE / 2
        // An id (a stop's, line's, trip's or operator's) longer than this is kept as a short stand-in (KavPlan.Ids).
        const val ID = 64
    }

    class Checked(val planned: KavPlan.Planned, val dropped: Int)

    fun feasible(it: Transit.Itinerary): Boolean {
        val legs = it.legs
        if (legs.isEmpty() || it.arr < it.dep) return false
        var last = Long.MIN_VALUE
        for (l in legs) {
            if (l.arr < l.dep || l.dep < last) return false
            last = l.arr
        }
        return true
    }

    fun check(p: KavPlan.Planned): Checked {
        val kept = p.plan.itineraries.filter(::feasible)
        val dropped = p.plan.itineraries.size - kept.size
        if (dropped == 0) return Checked(p, 0)
        return Checked(KavPlan.Planned(Transit.Plan(kept, p.plan.sections), p.resolved, p.errors), dropped)
    }
}
