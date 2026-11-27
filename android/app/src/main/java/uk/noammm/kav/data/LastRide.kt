package uk.noammm.kav.data

// The last-bus warning's time logic on plain values, with nothing Android in it, so the same code the app runs is
// the code plain JVM tests run. LastBus reads the timetable and preferences into these types, asks, and only
// posts notifications and sets alarms from what comes back. All times are epoch seconds.
object LastRide {

    // One scheduled departure of one trip at the alert's stop: its line's number, the name of where the trip ends
    // (how trains are told apart), the days it runs as a bitmask of Sunday=0..Saturday=6, and its departure in
    // seconds since the service day's base. Trips after midnight keep their starting day's bit and count past
    // 24*3600, so a 1:20am bus still belongs to the day it started on.
    class Row(val line: String, val endName: String, val daysMask: Int, val depSecs: Long)

    // The day's last departure of the chosen lines at the stop: epoch seconds, or null when none runs that day.
    // Trains have no line numbers, so for them the choice is which ends the trip reaches.
    fun lastDeparture(rows: List<Row>, lines: Set<String>, towardNames: Set<String>, day: Int, base: Long): Long? {
        val bit = 1 shl day
        var last = -1L
        for (r in rows) {
            val wanted = if (towardNames.isNotEmpty()) r.endName in towardNames else r.line in lines
            if (!wanted || r.daysMask and bit == 0) continue
            val at = base + r.depSecs
            if (at > last) last = at
        }
        return if (last < 0) null else last
    }

    // One alert as the planner sees it: when its next last bus leaves, how far before it to warn, and until when it
    // asked to stay quiet (a swipe-away, or the stretch after it already fired).
    class State(val id: Long, val lastAt: Long, val warnMin: Int, val quietUntil: Long)

    // What to do this run: which alerts fire now, and when to wake next.
    class Plan(val fire: List<Long>, val wakeAt: Long?)

    // Fires an alert whose warning time has come within the grace window (a late phone still gets its warning), but
    // never one already quieted for this bus: quietUntil covers its warning time, so a rerun, a boot or an app
    // update on the same day does not warn twice for the same last bus, while next day's warning time is past
    // quietUntil and fires again. Alerts still ahead wake the alarm at their warning time; a quieted one does not.
    fun plan(states: List<State>, now: Long, graceSecs: Long): Plan {
        val fire = mutableListOf<Long>()
        var wakeAt = Long.MAX_VALUE
        for (s in states) {
            val at = s.lastAt - s.warnMin * 60L
            val quieted = s.quietUntil >= at
            when {
                at <= now && now <= s.lastAt + graceSecs -> if (!quieted) fire.add(s.id)
                at > now -> if (!quieted && at < wakeAt) wakeAt = at
            }
        }
        return Plan(fire, if (wakeAt == Long.MAX_VALUE) null else wakeAt)
    }

    // An inexact alarm may be held back by up to three quarters of the time until it, so a wake far off is aimed at
    // in steps (the same rule as nextWake for line alerts): each step comes in time however late it runs.
    fun approach(at: Long, now: Long): Long = if (at - now <= 120) at else now + (at - now) * 4 / 7

    // Which of an alert's candidate last departures (yesterday's first, then today's and the days after, in order)
    // the planner should look at: the first whose grace has not run out and that is not already quieted. After
    // midnight yesterday's last bus can still be ahead, and one already warned for gives way to the next day's.
    fun pick(lastAts: List<Long>, warnMin: Int, quietUntil: Long, now: Long, graceSecs: Long): Long? =
        lastAts.firstOrNull { now <= it + graceSecs && quietUntil < it - warnMin * 60L }

    // One live arrival the server expects: its line's number, where the trip ends (how trains are told apart),
    // and when.
    class Live(val line: String, val endName: String, val expected: Long)

    // The live arrival that is the last scheduled bus: a chosen line (or, for trains, a chosen end), and close
    // enough to the timetable's time that it is that bus and not an earlier one running late or an extra the
    // timetable never had. Null means the server has no live time for the last bus and the warning says the
    // timetable's time, marked as such.
    fun liveLast(live: List<Live>, lines: Set<String>, towardNames: Set<String>, lastAt: Long, toleranceSecs: Long): Long? {
        var best: Long? = null
        for (l in live) {
            val wanted = if (towardNames.isNotEmpty()) l.endName in towardNames else l.line in lines
            if (!wanted) continue
            val gap = kotlin.math.abs(l.expected - lastAt)
            if (gap > toleranceSecs) continue
            if (best == null || gap < kotlin.math.abs(best - lastAt)) best = l.expected
        }
        return best
    }
}
