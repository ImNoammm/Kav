package uk.noammm.kav.data

// The leave-now alert's time logic on plain values, with nothing Android in it, so the same code the app runs is
// the code plain JVM tests run. LeaveNow reads departures and preferences into these types, asks, and only posts
// notifications and sets alarms from what comes back. All times are epoch seconds.
object LeaveNow {

    // One departure of a chosen trip at the alert's stop: its line's number, where the trip ends (how trains are
    // told apart), the timetable's time, and the server's live expected time (0 when the server has none).
    class Dep(val line: String, val endName: String, val aimed: Long, val expected: Long)

    // The time to count from: the live expected time when the server knows it, the timetable's otherwise. A
    // delayed bus moves the leave moment with it; an early one brings it forward.
    fun depAt(dep: Dep): Long = if (dep.expected > 0) dep.expected else dep.aimed

    // When to leave for a departure: its time minus the walk to the stop.
    fun leaveAt(dep: Dep, walkMin: Int): Long = depAt(dep) - walkMin * 60L

    // The departure to aim at: the earliest chosen one that has not left yet. One whose leave moment already
    // passed but which has not left still counts: the alert fires at once, as "leave now" then means now.
    fun target(deps: List<Dep>, lines: Set<String>, towardNames: Set<String>, now: Long): Dep? {
        var best: Dep? = null
        for (d in deps) {
            val wanted = if (towardNames.isNotEmpty()) d.endName in towardNames else d.line in lines
            if (!wanted) continue
            if (depAt(d) <= now) continue
            if (best == null || depAt(d) < depAt(best)) best = d
        }
        return best
    }

    // One alert as the planner sees it: the leave moment and departure of its current target, and until when it
    // asked to stay quiet (set to the departure when it fires or is swiped away, so one departure alerts once).
    class State(val id: Long, val leaveAt: Long, val depAt: Long, val quietUntil: Long)

    // What to do this run: which alerts fire now, and when to wake next.
    class Plan(val fire: List<Long>, val wakeAt: Long?)

    // Fires an alert whose leave moment has come and whose bus has not left, unless it already fired for this
    // departure (quietUntil reaches its leave moment). Alerts still ahead wake the alarm at their leave moment.
    fun plan(states: List<State>, now: Long): Plan {
        val fire = mutableListOf<Long>()
        var wakeAt = Long.MAX_VALUE
        for (s in states) {
            val quieted = s.quietUntil >= s.leaveAt
            when {
                s.leaveAt <= now && now < s.depAt -> if (!quieted) fire.add(s.id)
                s.leaveAt > now -> if (!quieted && s.leaveAt < wakeAt) wakeAt = s.leaveAt
            }
        }
        return Plan(fire, if (wakeAt == Long.MAX_VALUE) null else wakeAt)
    }
}
