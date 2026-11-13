package uk.noammm.kav.data

import java.time.LocalDate

// A provider's own timetable, asked line by line and stop by stop. Kav's server and the rider's own have none of these:
// their week is the one on the phone (Timetable), so Source.timetable is null for them. Schedules keeps the answers.
interface TimetableProvider {
    // A train line's times at a station are all its trains to the way's end, so its ways go by their last stop.
    val waysByEnd: Boolean

    fun lines(): List<Schedules.Line>
    fun ways(line: Schedules.Line, on: LocalDate): List<Schedules.Way>
    fun times(line: Schedules.Line, way: Schedules.Way, at: Int, day: LocalDate): List<Long>
    fun departures(code: Int, day: LocalDate, lines: List<Schedules.Line>): List<Schedules.Departure>
}

class HopOnTimetable private constructor(private val app: Source) : TimetableProvider {
    override val waysByEnd get() = true
    override fun lines() = BusNearby.lines(app)
    override fun ways(line: Schedules.Line, on: LocalDate) = BusNearby.ways(line, app)
    override fun times(line: Schedules.Line, way: Schedules.Way, at: Int, day: LocalDate) = BusNearby.times(line, way, at, day, app)
    override fun departures(code: Int, day: LocalDate, lines: List<Schedules.Line>) = BusNearby.departures(code, day, lines, app)

    companion object {
        val BUS_NEARBY = HopOnTimetable(Source.BUS_NEARBY)
        val EGG = HopOnTimetable(Source.EGG)
    }
}

val Source.timetable: TimetableProvider?
    get() = when (this) {
        Source.BUS_NEARBY -> HopOnTimetable.BUS_NEARBY
        Source.EGG -> HopOnTimetable.EGG
        Source.KAV, Source.OWN, Source.GOOGLE -> null
    }
