// Deterministic checks for boarding detection's decision logic (app/src/main/java/uk/noammm/kav/data/Boarding.kt).
// Plain JVM, no Android, no test framework. To run, from the repository root, with a Kotlin/JVM compiler
// (kotlinc, or kotlin-compiler-embeddable.jar from Maven Central):
//
//   kotlinc -d /tmp/boarding-check \
//     android/app/src/main/java/uk/noammm/kav/data/Boarding.kt android/checks/BoardingCheck.kt
//   java -cp /tmp/boarding-check BoardingCheckKt
//
// Prints "PASS: N checks" or throws at the first failure.
import uk.noammm.kav.data.Boarding.Fix
import uk.noammm.kav.data.Boarding.Sample
import uk.noammm.kav.data.Boarding.Sighting

// Plain JVM tests for boarding detection's decision logic. Run: compile with Boarding.kt, run main.
var checks = 0
fun check(name: String, cond: Boolean) {
    checks++
    if (!cond) throw AssertionError("FAILED: $name")
}

fun main() {
    val now = 1_700_000_000L

    // meters: a degree of latitude is about 111km; same point is 0.
    check("same point", Boarding.meters(32.0, 34.8, 32.0, 34.8) == 0.0)
    check("degree of latitude", Boarding.meters(32.0, 34.8, 33.0, 34.8) in 110_000.0..112_000.0)

    // near: only buses within the radius.
    val fix = Fix(32.08, 34.80, now, 8f)
    val buses = listOf(
        Sighting("a", "10", 32.0802, 34.8002),  // ~30m
        Sighting("b", "20", 32.09, 34.80),      // ~1.1km
    )
    check("near keeps close bus", Boarding.near(fix, buses, 60.0) == listOf("a"))
    check("near empty when all far", Boarding.near(fix, buses, 10.0).isEmpty())

    // moving: by the fix's own speed, or implied from the previous fix.
    check("moving by speed", Boarding.moving(null, Fix(32.0, 34.8, now, 6f), 4.2))
    check("not moving", !Boarding.moving(null, Fix(32.0, 34.8, now, 1f), 4.2))
    check("unknown speed alone is still", !Boarding.moving(null, Fix(32.0, 34.8, now, -1f), 4.2))
    val prev = Fix(32.0, 34.8, now - 60, -1f)
    check("implied speed counts", Boarding.moving(prev, Fix(32.005, 34.8, now, -1f), 4.2))   // ~550m in 60s ~ 9 m/s
    check("implied slow is still", !Boarding.moving(prev, Fix(32.0001, 34.8, now, -1f), 4.2))

    // candidate: the same single bus near on each of the last needed moving samples, fresh.
    fun s(at: Long, moving: Boolean, vararg keys: String) = Sample(at, moving, keys.toList())
    check("candidate after 3 moving samples", Boarding.candidate(listOf(s(now - 40, true, "a"), s(now - 20, true, "a"), s(now, true, "a")), 3, 60, now) == "a")
    check("too few samples", Boarding.candidate(listOf(s(now - 20, true, "a"), s(now, true, "a")), 3, 60, now) == null)
    check("a still sample breaks it", Boarding.candidate(listOf(s(now - 40, true, "a"), s(now - 20, false, "a"), s(now, true, "a")), 3, 60, now) == null)
    check("stale newest is no candidate", Boarding.candidate(listOf(s(now - 140, true, "a"), s(now - 120, true, "a"), s(now - 100, true, "a")), 3, 60, now) == null)
    check("switching buses is no candidate", Boarding.candidate(listOf(s(now - 40, true, "a"), s(now - 20, true, "b"), s(now, true, "a")), 3, 60, now) == null)
    check("two fitting buses are ambiguous", Boarding.candidate(listOf(s(now - 40, true, "a", "b"), s(now - 20, true, "a", "b"), s(now, true, "a", "b")), 3, 60, now) == null)
    check("earlier history does not matter", Boarding.candidate(listOf(s(now - 80, false, "x"), s(now - 40, true, "a"), s(now - 20, true, "a"), s(now, true, "a")), 3, 60, now) == "a")

    println("PASS: $checks checks")
}
