package uk.noammm.kav.ui

import uk.noammm.kav.data.Transit

// A walk's turns in words, and the one coming next.

// A turn as said: "Turn right onto Kaplan"; a way with no name of its own (a pavement, a crossing, a path) isn't named, so
// "Turn left" alone. At a roundabout on foot, which way it takes the rider, from the walk's own road: the planner only
// says "round the roundabout" (issue 74: "At the roundabout, take the path" where Yair just turned left along the
// pavement). The first one says where to set out along.
internal fun turnText(t: Transit.Turn, walk: Transit.Leg? = null): String {
    // A street in the other language's script kept whole, so it doesn't turn the sentence round it.
    val named = t.street.takeIf { it.isNotBlank() }?.let { "\u2068$it\u2069" }
    fun onto(en: String, he: String) = if (named == null) T(en, he) else T("$en onto $named", "$he ל$named")
    return when (t.way) {
        "DEPART" -> if (named == null) T("Start walking", "התחילו ללכת") else T("Head along $named", "התחילו ללכת ב$named")
        "LEFT" -> onto("Turn left", "פנו שמאלה")
        "RIGHT" -> onto("Turn right", "פנו ימינה")
        "SLIGHTLY_LEFT" -> onto("Bear left", "המשיכו קלות שמאלה")
        "SLIGHTLY_RIGHT" -> onto("Bear right", "המשיכו קלות ימינה")
        "HARD_LEFT" -> onto("Turn sharp left", "פנו חדות שמאלה")
        "HARD_RIGHT" -> onto("Turn sharp right", "פנו חדות ימינה")
        "UTURN_LEFT", "UTURN_RIGHT" -> if (named == null) T("Turn back", "הסתובבו לאחור") else T("Turn back along $named", "חזרו לאחור ב$named")
        "CIRCLE_CLOCKWISE", "CIRCLE_COUNTERCLOCKWISE" -> when {
            named != null -> T("At the roundabout, take $named", "בכיכר, המשיכו ל$named")
            else -> when (walk?.let { roundaboutTurn(t, it) }) {
                -1 -> T("At the roundabout, turn left", "בכיכר, פנו שמאלה")
                1 -> T("At the roundabout, turn right", "בכיכר, פנו ימינה")
                0 -> T("At the roundabout, go straight on", "בכיכר, המשיכו ישר")
                else -> T("Go round the roundabout", "המשיכו מסביב לכיכר")
            }
        }
        "ELEVATOR" -> T("Take the lift", "עלו במעלית")
        "ENTER_STATION" -> T("Enter the station", "היכנסו לתחנה")
        "EXIT_STATION" -> T("Leave the station", "צאו מהתחנה")
        "FOLLOW_SIGNS" -> T("Follow the signs", "עקבו אחרי השילוט")
        else -> if (named == null) T("Continue straight", "המשיכו ישר") else T("Continue along $named", "המשיכו ב$named")
    }
}

// Which way a roundabout takes the rider on foot: the way the walk's road leaves it (the turn's metres on, round it)
// against the way it came in, -1 left, 1 right, 0 straight on; null without a road to tell by.
internal fun roundaboutTurn(t: Transit.Turn, walk: Transit.Leg): Int? {
    val road = walk.shape
    if (road.size < 2 || !t.lat.isFinite() || !t.lon.isFinite()) return null
    val at = alongPath(t.lat, t.lon, road)
    val start = pointAlong(road, at) ?: return null
    val before = pointAlong(road, maxOf(0.0, at - ROUNDABOUT_LOOK_M)) ?: return null
    val after = pointAlong(road, at + maxOf(t.metres.toDouble(), ROUNDABOUT_LOOK_M)) ?: return null
    if (metres(before.first, before.second, start.first, start.second) < 3.0 ||
        metres(start.first, start.second, after.first, after.second) < 3.0) return null
    val came = bearing(before.first, before.second, start.first, start.second)
    val goes = bearing(start.first, start.second, after.first, after.second)
    val turn = ((goes - came + 540f) % 360f) - 180f
    return when {
        turn < -ROUNDABOUT_STRAIGHT_DEG -> -1
        turn > ROUNDABOUT_STRAIGHT_DEG -> 1
        else -> 0
    }
}

// How far back the way in is read, and how far a way out can bend and still be straight on.
private const val ROUNDABOUT_LOOK_M = 20.0
private const val ROUNDABOUT_STRAIGHT_DEG = 30f

// The walk's next turn from where the rider is on it (fix), with the metres to it; from its start without a fix. Null
// past its last turn, or for a walk without turns. A turn counts as ahead until 5 m past it.
internal fun nextTurn(walk: Transit.Leg, fix: Fix?): Pair<Transit.Turn, Double>? {
    val turns = walk.turns.drop(1).filter { it.lat.isFinite() && it.lon.isFinite() && (it.lat != 0.0 || it.lon != 0.0) }
    if (turns.isEmpty() || walk.shape.size < 2) return null
    val at = fix?.let { alongPath(it.lat, it.lon, walk.shape) } ?: 0.0
    return turns.map { it to alongPath(it.lat, it.lon, walk.shape) - at }.firstOrNull { it.second > -5.0 }
}
