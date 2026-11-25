package uk.noammm.kav.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import uk.noammm.kav.BuildConfig
import uk.noammm.kav.Prefs
import uk.noammm.kav.TicketStore
import uk.noammm.kav.data.Ticket
import uk.noammm.kav.data.TicketScreens
import uk.noammm.kav.data.TicketTimes

// The ride a Pay tap was for, from Kav's own trip: its line and the stop the rider gets off at, shown on the button back
// to Kav over Rav-Pass (PayOverlay), so on Rav-Pass's approve screen they pick that arrival stop and pay that fare.
// Nothing of it goes to Rav-Pass. ride: the ride as JSON text (RavPass.ride), for an extension that takes over paying.
internal data class PayFor(val line: String?, val off: String?, val ride: String? = null) {
    fun label(): String? = listOfNotNull(
        line?.takeIf { it.isNotBlank() }?.let { T("Line $it", "קו $it") },
        off?.takeIf { it.isNotBlank() }?.let { T("get off at $it", "ירידה ב$it") },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
}

internal object PayHandOff {
    // Set by a Pay tap (RavPass.open), taken by the hand-off it leads to (RavPass.go): a later hand-off of its own, as a
    // ticket's "Open in Rav-Pass", shows no ride.
    @Volatile var pending: PayFor? = null
    @Volatile var shown: PayFor? = null

    fun take() { shown = pending; pending = null }
}

// A Pay tap for a bus with a train entrance still open, as old Kav asked: the exit ticket first, or the bus. An entrance
// whose exit was made out of Kav's sight stays open in Kav until its own end, so the bus is always one tap away.
internal object EntranceAsk {
    var pending by mutableStateOf<Pair<RavPass.Section, PayFor?>?>(null)
    private var skip = false

    // True when it asks instead of the tap going on.
    fun asks(ctx: Context, s: RavPass.Section, ride: PayFor?): Boolean {
        if (skip) { skip = false; return false }
        if (s != RavPass.Section.BUS) return false
        TicketStore.load(ctx)
        if (TicketTimes.openEntrance(TicketStore.kept, System.currentTimeMillis()) == null) return false
        pending = s to ride
        return true
    }

    fun bus(ctx: Context, s: RavPass.Section, ride: PayFor?) { skip = true; RavPass.own(ctx, s, ride) }
}

@Composable
internal fun EntranceAskHost() {
    val ctx = LocalContext.current
    val (section, ride) = EntranceAsk.pending ?: return
    BottomSheet(onDismiss = { EntranceAsk.pending = null }) { close ->
        Text(T("Your train entrance is still open", "הכניסה שלכם לרכבת עדיין פתוחה"), style = K.type.title, color = K.text)
        Spacer(Modifier.height(K.gap2))
        Text(T("Make the exit ticket in ${PayApp.current.label()} first?", "ליצור קודם את כרטיס היציאה ${PayApp.current.heIn}?"), style = K.type.body, color = K.textSecondary)
        Spacer(Modifier.height(K.gap4))
        KavButton(T("Exit ticket", "כרטיס יציאה"), onClick = {
            close { EntranceAsk.pending = null; RavPass.own(ctx, RavPass.Section.TRAIN) }
        }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(K.gap2))
        KavButton(T("Pay for the bus", "תשלום על האוטובוס"), onClick = {
            close { EntranceAsk.pending = null; EntranceAsk.bus(ctx, section, ride) }
        }, modifier = Modifier.fillMaxWidth(), style = ButtonStyle.Secondary)
    }
}

// Rav-Pass updated past the versions the reader was checked against (TicketScreens.VERSIONS): read from Android's package
// list, as Kav already checks that Rav-Pass is installed; nothing is asked of anyone. A debug build counts Kav's test
// stand-in (versionName "stub…") as checked.
internal object RavPassVersion {
    fun checked(name: String?, debug: Boolean = BuildConfig.DEBUG): Boolean =
        name == null || name in TicketScreens.VERSIONS || (debug && name.startsWith("stub"))

    fun name(ctx: Context): String? = runCatching { ctx.packageManager.getPackageInfo(RavPass.PACKAGE, 0).versionName }.getOrNull()

    // Home says it only after a Pay tap in the last three hours read no ticket: with tickets being read, all is well.
    fun missed(kept: List<Ticket>, tappedAt: Long, now: Long) =
        tappedAt > 0 && now - tappedAt in 0..3 * Ticket.HOUR && kept.none { it.readAt >= tappedAt }
}

// One quiet line: in the paying settings whenever Rav-Pass's version wasn't checked, under Home's pay card only after a
// Pay tap's hand-off read no ticket.
@Composable
internal fun RavPassUpdatedLine(home: Boolean) {
    if (!TicketView.supported() || !Prefs.tickets(LocalContext.current)) return
    val ctx = LocalContext.current
    var unchecked by remember { mutableStateOf(false) }
    var tapped by remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Again when the rider switches payment app: the other app's version.
    LaunchedEffect(lifecycle, PayApp.current) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            unchecked = !RavPassVersion.checked(RavPassVersion.name(ctx)); tapped = Prefs.payTappedAt(ctx)
        }
    }
    if (!unchecked || home && !RavPassVersion.missed(TicketStore.kept, tapped, System.currentTimeMillis())) return
    val text = T("${PayApp.current.label()} has updated, so tickets may not show in Kav until Kav's next update.",
        "${PayApp.current.he} התעדכנה, ולכן ייתכן שכרטיסים לא יוצגו ב-Kav עד העדכון הבא של Kav.")
    if (home) {
        Separator()
        Text(text, style = K.type.subhead, modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap3))
    } else Note(text, Modifier.padding(horizontal = K.gap4, vertical = K.gap2))
}
