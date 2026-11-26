// Deterministic checks for the last-bus warning's time logic (app/src/main/java/uk/noammm/kav/data/LastRide.kt).
// Plain JVM, no Android, no test framework. To run, from the repository root, with a Kotlin/JVM compiler
// (kotlinc, or kotlin-compiler-embeddable.jar from Maven Central):
//
//   kotlinc -d /tmp/last-ride-check \
//     android/app/src/main/java/uk/noammm/kav/data/LastRide.kt android/checks/LastRideCheck.kt
//   java -cp /tmp/last-ride-check LastRideCheckKt
//
// Prints "PASS: N checks" or throws at the first failure.
import uk.noammm.kav.data.LastRide.Live
import uk.noammm.kav.data.LastRide.Row
import uk.noammm.kav.data.LastRide.State

// Plain JVM tests for the last-bus warning's time logic. Run: compile with LastRide.kt, run main.
var checks = 0
fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) throw AssertionError("FAILED: $name")
}

fun main() {
    val base = 1_700_000_000L // any service-day base, epoch seconds
    val mon = 1
    val tue = 2

    // lastDeparture: picks the latest of the chosen lines, that day only.
    val rows = listOf(
        Row("10", "צפון", mask(mon, tue), 22 * 3600L),
        Row("20", "דרום", mask(mon, tue), 23 * 3600L + 1800),
        Row("30", "מזרח", mask(tue), 21 * 3600L),
    )
    check("latest of chosen lines", LastRide.lastDeparture(rows, setOf("10", "20"), emptySet(), mon, base) == base + 23 * 3600L + 1800)
    check("single line", LastRide.lastDeparture(rows, setOf("10"), emptySet(), mon, base) == base + 22 * 3600L)
    check("line not running that day", LastRide.lastDeparture(rows, setOf("30"), emptySet(), mon, base) == null)
    check("line running that day", LastRide.lastDeparture(rows, setOf("30"), emptySet(), tue, base) == base + 21 * 3600L)
    check("no chosen lines", LastRide.lastDeparture(rows, setOf("99"), emptySet(), mon, base) == null)

    // Trains: the choice is where the trip ends, and line numbers are ignored.
    val trains = listOf(
        Row("", "נהריה", mask(mon), 22 * 3600L),
        Row("", "באר שבע", mask(mon), 23 * 3600L),
    )
    check("toward picks by end", LastRide.lastDeparture(trains, setOf("10"), setOf("באר שבע"), mon, base) == base + 23 * 3600L)
    check("toward empty end", LastRide.lastDeparture(trains, emptySet(), setOf("חיפה"), mon, base) == null)

    // After midnight: a 1:20am trip belongs to the day it started on, never to the next calendar day.
    val late = listOf(Row("10", "צפון", mask(mon), 25 * 3600L + 1200))
    check("after midnight on its service day", LastRide.lastDeparture(late, setOf("10"), emptySet(), mon, base) == base + 25 * 3600L + 1200)
    check("after midnight not on the next day", LastRide.lastDeparture(late, setOf("10"), emptySet(), tue, base) == null)

    // plan: fires at the warning time, through the grace window, not before and not after.
    val lastAt = base + 23 * 3600L + 1800
    val warnAt = lastAt - 20 * 60
    val grace = 600L
    fun st(quiet: Long = 0, warnMin: Int = 20, at: Long = lastAt) = State(7, at, warnMin, quiet)
    check("fires at warning time", LastRide.plan(listOf(st()), warnAt, grace).fire == listOf(7L))
    check("fires within grace for a late phone", LastRide.plan(listOf(st()), warnAt + 300, grace).fire == listOf(7L))
    check("fires until grace after departure", LastRide.plan(listOf(st()), lastAt + grace, grace).fire == listOf(7L))
    check("not after grace", LastRide.plan(listOf(st()), lastAt + grace + 1, grace).fire.isEmpty())
    check("wake before warning time", LastRide.plan(listOf(st()), warnAt - 60, grace).wakeAt == warnAt)
    check("no fire before warning time", LastRide.plan(listOf(st()), warnAt - 60, grace).fire.isEmpty())

    // A swipe-away (or having already fired) quiets the same bus: no second warning, no further wake for it.
    check("quieted bus does not refire", LastRide.plan(listOf(st(quiet = warnAt)), warnAt + 120, grace).fire.isEmpty())
    check("quieted at departure does not refire in grace", LastRide.plan(listOf(st(quiet = lastAt)), lastAt + grace, grace).fire.isEmpty())
    check("quieted pending alert sets no wake", LastRide.plan(listOf(st(quiet = warnAt)), warnAt - 60, grace).wakeAt == null)

    // Next day's bus: its warning time is past yesterday's quiet, so it fires again.
    val nextAt = lastAt + 24 * 3600
    check("fires again next day", LastRide.plan(listOf(st(quiet = lastAt + grace, at = nextAt)), nextAt - 20 * 60, grace).fire == listOf(7L))

    // The warning lead is the alert's own choice.
    check("chosen lead 45m", LastRide.plan(listOf(st(warnMin = 45)), lastAt - 45 * 60, grace).fire == listOf(7L))
    check("late phone still fires with chosen lead", LastRide.plan(listOf(st(warnMin = 45)), lastAt - 20 * 60, grace).fire == listOf(7L))
    check("before a 45m lead only a wake", LastRide.plan(listOf(st(warnMin = 45)), lastAt - 46 * 60, grace).let { it.fire.isEmpty() && it.wakeAt == lastAt - 45 * 60 })

    // Several alerts: the wake is the nearest warning time.
    val early = State(1, lastAt - 3600, 20, 0)
    check("wake is nearest of many", LastRide.plan(listOf(st(), early), lastAt - 6000, grace).wakeAt == lastAt - 3600 - 20 * 60)

    // liveLast: the live arrival that is the last bus, and only that one.
    val live = listOf(
        Live("10", "צפון", lastAt - 40 * 60),   // an earlier bus, too far off to be the last one
        Live("20", "דרום", lastAt + 5 * 60),    // the last bus, 5 minutes late
        Live("30", "מזרח", lastAt),             // a line the rider did not choose
    )
    check("live match is the last bus", LastRide.liveLast(live, setOf("20"), emptySet(), lastAt, 30 * 60) == lastAt + 5 * 60)
    check("stale live ignored, timetable kept", LastRide.liveLast(live, setOf("10"), emptySet(), lastAt, 30 * 60) == null)
    check("other lines ignored", LastRide.liveLast(live, setOf("99"), emptySet(), lastAt, 30 * 60) == null)
    check("trains match their end", LastRide.liveLast(live, emptySet(), setOf("דרום"), lastAt, 30 * 60) == lastAt + 5 * 60)
    check("trains ignore other ends", LastRide.liveLast(live, emptySet(), setOf("נהריה"), lastAt, 30 * 60) == null)
    val two = listOf(Live("20", "דרום", lastAt + 25 * 60), Live("20", "דרום", lastAt + 2 * 60))
    check("closest live time wins", LastRide.liveLast(two, setOf("20"), emptySet(), lastAt, 30 * 60) == lastAt + 2 * 60)

    println("PASS: $checks checks")
}

fun mask(vararg days: Int): Int = days.fold(0) { m, d -> m or (1 shl d) }
