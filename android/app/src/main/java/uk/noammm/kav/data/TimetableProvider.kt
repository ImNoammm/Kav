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

// The provider a choice of timetable asks: none of today's choices has one (Kav's server, the rider's own, Google).
val Source.timetable: TimetableProvider?
    get() = null
