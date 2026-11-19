package uk.noammm.kav.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import uk.noammm.kav.Prefs
import uk.noammm.kav.TicketStore
import uk.noammm.kav.data.RavPassProfiles

// The fare profile from Rav-Pass (Noam, 19 Nov 2026): "Get it from Rav-Pass" opens Rav-Pass's Profile tab, and only within
// WAIT_MS of that tap, and only on that screen, the ticket reader reads the profile name it shows (its view profile_type)
// and nothing else (TicketReader.readFare). The name is matched against Rav-Pass's own list (RavPassProfiles); the match
// sets Kav's fare profile and the rider is brought back. No match leaves the choice by hand, which is always there.
internal object FareFromRavPass {
    const val WAIT_MS = 2 * 60_000L
    const val PROFILE_TYPE = "co.hopon.client:id/profile_type"
    const val PROFILE_TYPE_TITLE = "co.hopon.client:id/profile_type_title"

    // What was found last, read again whenever it changes.
    var found by mutableStateOf<String?>(null)
        private set

    fun load(ctx: Context) { found = Prefs.fareFromRavPass(ctx) }

    fun ask(ctx: Context) {
        Prefs.setFareAskedAt(ctx, System.currentTimeMillis())
        RavPass.place(ctx, RavPass.profile)
    }

    fun asked(ctx: Context): Boolean = (System.currentTimeMillis() - Prefs.fareAskedAt(ctx)) in 0..WAIT_MS

    // The reader's answer, once: the fare profile set when the name matched one Kav has.
    fun done(ctx: Context, read: RavPassProfiles.Read) {
        Prefs.setFareAskedAt(ctx, 0L)
        val kept = when (read) {
            is RavPassProfiles.Read.Fare -> {
                Shown.fareProfile = read.profile; Prefs.setFareProfile(ctx, read.profile)
                "fare|${read.profile.name}|${read.shown}"
            }
            is RavPassProfiles.Read.NoFare -> "nofare||${read.shown}"
            is RavPassProfiles.Read.Unknown -> "unknown||${read.shown}"
            is RavPassProfiles.Read.Several -> "several||${read.shown}"
            RavPassProfiles.Read.Nothing -> "nothing||"
        }
        Prefs.setFareFromRavPass(ctx, kept); found = kept
    }

    // Picked by hand: what Rav-Pass said no longer stands for it.
    fun clear(ctx: Context) { Prefs.setFareFromRavPass(ctx, null); found = null }

    private fun parts() = found?.split('|', limit = 3)?.takeIf { it.size == 3 }

    // "Student · from Rav-Pass", while the profile is still the one Rav-Pass gave.
    fun subtitle(name: String): String {
        val p = parts() ?: return name
        return if (p[0] == "fare" && p[1] == Shown.fareProfile.name) name + T(" · from Rav-Pass", " · מרב-פס") else name
    }

    // Why the last try left the choice by hand.
    fun why(): String? {
        val p = parts() ?: return null
        val shown = p[2]
        return when (p[0]) {
            "nofare" -> T("Rav-Pass shows “$shown”, which Kav has no fare profile for. Choose one by hand.",
                "ברב-פס מופיע “$shown”, ול-Kav אין פרופיל הנחה כזה. בחרו פרופיל ידנית.")
            "unknown" -> T("Rav-Pass shows “$shown”, which isn't one of Rav-Pass's own profile names Kav knows. Choose one by hand.",
                "ברב-פס מופיע “$shown”, שאינו אחד משמות הפרופילים של רב-פס ש-Kav מכירה. בחרו פרופיל ידנית.")
            "several" -> T("Rav-Pass shows more than one profile (“$shown”). Choose one by hand.",
                "ברב-פס מופיע יותר מפרופיל אחד (“$shown”). בחרו פרופיל ידנית.")
            "nothing" -> T("Rav-Pass shows no discount profile. Choose one by hand.", "ברב-פס לא מופיע פרופיל הנחה. בחרו פרופיל ידנית.")
            else -> null
        }
    }
}

// In the fare profile's sheet: the way to get it from Rav-Pass, or, with the ticket reader off, the one line saying it's
// needed.
@Composable
internal fun FareFromRavPassRow() {
    val ctx = LocalContext.current
    val reader = TicketStore.on(ctx)
    ListRow(
        T("Get it from Rav-Pass", "קבלה מרב-פס"),
        subtitle = if (!reader) T("Needs Kav's ticket reader: turn on Pay with Rav-Pass in Settings", "צריך את קורא הכרטיסים של Kav: הפעילו את התשלום עם רב-פס בהגדרות")
            else FareFromRavPass.why() ?: T("Opens Rav-Pass's Profile tab, and Kav reads the profile it shows there", "פותח את האזור האישי ברב-פס, ו-Kav קוראת את הפרופיל שמופיע שם"),
        subtitleMaxLines = Int.MAX_VALUE,
        onClick = if (reader) ({ FareFromRavPass.ask(ctx) }) else null,
    )
}
