// Deterministic checks for the leave-now alert's time logic (app/src/main/java/uk/noammm/kav/data/LeaveNowTimes.kt).
// Plain JVM, no Android, no test framework. To run, from the repository root, with a Kotlin/JVM compiler
// (kotlinc, or kotlin-compiler-embeddable.jar from Maven Central):
//
//   kotlinc -d /tmp/leave-now-check \
//     android/app/src/main/java/uk/noammm/kav/data/LeaveNowTimes.kt android/checks/LeaveNowCheck.kt
//   java -cp /tmp/leave-now-check LeaveNowCheckKt
//
// Prints "PASS: N checks" or throws at the first failure.
import uk.noammm.kav.data.LeaveNowTimes
import uk.noammm.kav.data.LeaveNowTimes.Dep
import uk.noammm.kav.data.LeaveNowTimes.State

// Plain JVM tests for the leave-now alert's time logic. Run: compile with LeaveNowTimes.kt, run main.
var checks = 0
fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) throw AssertionError("FAILED: $name")
}

fun main() {
    val now = 1_700_000_000L
    val walk = 10 // minutes

    // depAt/leaveAt: live time wins when the server has it, timetable otherwise.
    check("timetable time without live", LeaveNowTimes.depAt(Dep("10", "צפון", now + 3600, 0)) == now + 3600)
    check("live time wins", LeaveNowTimes.depAt(Dep("10", "צפון", now + 3600, now + 3700)) == now + 3700)
    check("leave is walk before live", LeaveNowTimes.leaveAt(Dep("10", "צפון", now + 3600, now + 3700), walk) == now + 3700 - 600)

    // target: earliest chosen departure that has not left, by live time when known.
    val deps = listOf(
        Dep("10", "צפון", now - 300, 0),              // left already
        Dep("99", "צפון", now + 600, 0),              // a line not chosen
        Dep("20", "דרום", now + 1200, 0),             // chosen, later
        Dep("10", "צפון", now + 1800, now + 900),     // chosen, live says earlier than the timetable's
    )
    check("earliest catchable by live time", LeaveNowTimes.target(deps, setOf("10", "20"), emptySet(), now)?.let { LeaveNowTimes.depAt(it) } == now + 900)
    check("gone bus is skipped", LeaveNowTimes.target(listOf(Dep("10", "צפון", now - 60, 0)), setOf("10"), emptySet(), now) == null)
    check("unchosen lines skipped", LeaveNowTimes.target(deps, setOf("77"), emptySet(), now) == null)
    check("trains picked by end", LeaveNowTimes.target(deps, emptySet(), setOf("דרום"), now)?.line == "20")
    check("trains other end skipped", LeaveNowTimes.target(deps, emptySet(), setOf("נהריה"), now) == null)

    // plan: fires from the leave moment until the bus leaves, wakes at a future leave moment.
    val leave = now + 900 - 600
    val dep = now + 900
    fun st(quiet: Long = 0, leaveAt: Long = leave, depAt: Long = dep) = State(3, leaveAt, depAt, quiet)
    check("fires at leave moment", LeaveNowTimes.plan(listOf(st()), leave).fire == listOf(3L))
    check("fires while bus still coming", LeaveNowTimes.plan(listOf(st()), leave + 300).fire == listOf(3L))
    check("leave moment passed but bus not left: still fires (leave now means now)", LeaveNowTimes.plan(listOf(st()), dep - 60).fire == listOf(3L))
    check("silent once the bus left", LeaveNowTimes.plan(listOf(st()), dep).fire.isEmpty())
    check("wake before leave moment", LeaveNowTimes.plan(listOf(st()), leave - 60).wakeAt == leave)
    check("no fire before leave moment", LeaveNowTimes.plan(listOf(st()), leave - 60).fire.isEmpty())

    // One departure alerts once: quieted until its departure, it never refires; the next departure does.
    check("quieted departure does not refire", LeaveNowTimes.plan(listOf(st(quiet = dep)), leave + 120).fire.isEmpty())
    check("quieted pending wakes at the bus's departure, for the next one", LeaveNowTimes.plan(listOf(st(quiet = dep)), leave - 60).wakeAt == dep)
    check("quieted and fired wakes at the departure too", LeaveNowTimes.plan(listOf(st(quiet = dep)), leave + 120).wakeAt == dep)
    check("departed bus sets no wake", LeaveNowTimes.plan(listOf(st(quiet = dep)), dep + 5).wakeAt == null)
    val next = State(3, dep + 300, dep + 900, dep)
    check("next departure fires again", LeaveNowTimes.plan(listOf(next), dep + 300).fire == listOf(3L))

    // A delayed bus moves the leave moment: the same alert re-planned with the live time fires later, not now.
    val delayed = State(3, leave + 600, dep + 600, 0)
    check("delay moves fire later", LeaveNowTimes.plan(listOf(delayed), leave).let { it.fire.isEmpty() && it.wakeAt == leave + 600 })

    // Several alerts: the wake is the nearest leave moment.
    val earlier = State(1, leave - 300, dep, 0)
    check("wake is nearest of many", LeaveNowTimes.plan(listOf(st(), earlier), leave - 600).wakeAt == leave - 300)

    // approach: a wake far off is aimed at in steps (4/7 of the way), a near one exactly.
    check("near wake is exact", LeaveNowTimes.approach(now + 100, now) == now + 100)
    check("far wake is a step", LeaveNowTimes.approach(now + 7000, now) == now + 4000)
    check("repeated steps converge", LeaveNowTimes.approach(now + 7000, now + 4000) == now + 4000 + 1714)

    println("PASS: $checks checks")
}
