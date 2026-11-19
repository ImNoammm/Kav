package uk.noammm.kav.data

import org.json.JSONObject
import java.util.Locale

// A time the rider keeps in the When sheet (issue 74): "Arrive by 08:00" a few days a week, planned the evening before.
// The settings and backups hold them as "a0800,d0730", a for arrive by and d for depart at, oldest first.
data class SavedTime(val type: Int, val hour: Int, val minute: Int) {
    val key: String get() = (if (type == Transit.TIME_ARRIVAL) "a" else "d") + "%02d%02d".format(Locale.US, hour, minute)

    companion object {
        const val KEY = "savedTimes"
        const val MAX = 6

        // Anything that isn't a time is left out.
        fun list(stored: String?): List<SavedTime> = stored.orEmpty().split(',').mapNotNull { of(it.trim()) }.distinct().takeLast(MAX)

        fun text(times: List<SavedTime>): String = times.joinToString(",") { it.key }

        // Newest last. One already kept stays where it is; a seventh drops the oldest.
        fun keep(times: List<SavedTime>, time: SavedTime): List<SavedTime> = if (time in times) times else (times + time).takeLast(MAX)

        // A backup's, or null for one from before saved times (or with something else under the name): the phone's stay.
        fun restored(backup: JSONObject): List<SavedTime>? = (backup.opt(KEY) as? String)?.let { list(it) }

        private fun of(key: String): SavedTime? {
            if (key.length != 5 || !key.drop(1).all { it in '0'..'9' }) return null
            val type = when (key[0]) {
                'a' -> Transit.TIME_ARRIVAL
                'd' -> Transit.TIME_DEPARTURE
                else -> return null
            }
            val hour = key.substring(1, 3).toInt()
            val minute = key.substring(3).toInt()
            return if (hour < 24 && minute < 60) SavedTime(type, hour, minute) else null
        }
    }
}
