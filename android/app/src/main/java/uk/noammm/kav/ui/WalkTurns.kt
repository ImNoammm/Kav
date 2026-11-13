package uk.noammm.kav.ui

import uk.noammm.kav.data.Transit

// A walk's turns in words, and the one coming next.

// A turn as said: "Turn right onto Kaplan", or onto "the path" where the way has no name. The first one says where
// to set out along.
internal fun turnText(t: Transit.Turn): String {
    val he = T.rtl
    // A street in the other language's script kept whole, so it doesn't turn the sentence round it.
    val onto = t.street.takeIf { it.isNotBlank() }?.let { "\u2068$it\u2069" } ?: if (he) "השביל" else "the path"
    return when (t.way) {
        "DEPART" -> T("Head along $onto", "התחילו ללכת ב$onto")
        "LEFT" -> T("Turn left onto $onto", "פנו שמאלה ל$onto")
        "RIGHT" -> T("Turn right onto $onto", "פנו ימינה ל$onto")
        "SLIGHTLY_LEFT" -> T("Bear left onto $onto", "המשיכו קלות שמאלה ל$onto")
        "SLIGHTLY_RIGHT" -> T("Bear right onto $onto", "המשיכו קלות ימינה ל$onto")
        "HARD_LEFT" -> T("Turn sharp left onto $onto", "פנו חדות שמאלה ל$onto")
        "HARD_RIGHT" -> T("Turn sharp right onto $onto", "פנו חדות ימינה ל$onto")
        "UTURN_LEFT", "UTURN_RIGHT" -> T("Turn back along $onto", "הסתובבו ב$onto")
        "CIRCLE_CLOCKWISE", "CIRCLE_COUNTERCLOCKWISE" -> T("At the roundabout, take $onto", "בכיכר, המשיכו ל$onto")
        "ELEVATOR" -> T("Take the lift", "עלו במעלית")
        "ENTER_STATION" -> T("Enter the station", "היכנסו לתחנה")
        "EXIT_STATION" -> T("Leave the station", "צאו מהתחנה")
        "FOLLOW_SIGNS" -> T("Follow the signs", "עקבו אחרי השילוט")
        else -> T("Continue along $onto", "המשיכו ב$onto")
    }
}

// The walk's next turn from where the rider is on it (fix), with the metres to it; from its start without a fix. Null
// past its last turn, or for a walk without turns. A turn counts as ahead until 5 m past it.
internal fun nextTurn(walk: Transit.Leg, fix: Fix?): Pair<Transit.Turn, Double>? {
    val turns = walk.turns.drop(1).filter { it.lat.isFinite() && it.lon.isFinite() && (it.lat != 0.0 || it.lon != 0.0) }
    if (turns.isEmpty() || walk.shape.size < 2) return null
    val at = fix?.let { alongPath(it.lat, it.lon, walk.shape) } ?: 0.0
    return turns.map { it to alongPath(it.lat, it.lon, walk.shape) - at }.firstOrNull { it.second > -5.0 }
}
