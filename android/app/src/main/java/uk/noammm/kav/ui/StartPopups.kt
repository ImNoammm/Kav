package uk.noammm.kav.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.ImportPrompt
import uk.noammm.kav.KavModel
import uk.noammm.kav.LookPrompt
import uk.noammm.kav.PendingBackup
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.AutoUpdate
import uk.noammm.kav.data.StartNotice
import uk.noammm.kav.data.Updates
import uk.noammm.kav.data.WhatsNew

// Every popup Kav puts up by itself as it opens, in the order they go: a look to pick after a big update, the message
// this version brings (StartNotice), the map to download, a backup the rider opened, a new version (New version
// alerts), one Kav downloaded itself to install (Automatic updates), and "Kav has been updated" with what's new, after
// any update. Setup comes before all of them, as Root shows nothing else
// until it's done.
enum class StartPopup { LOOK, NOTICE, MAP, IMPORT, EXTENSION, BLOCKED, CRASHED, UPDATE, READY, UPDATED }

// Which one shows: one at a time, kept until it closes, and one an opening (Openings.popups), the rest waiting for the
// next. Opening 0 is none: Kav's screen brought back by Android in a new run of Kav. One the rider asked for (a backup
// or a link they opened) still waits for the one showing to close, but not for an opening.
internal class PopupQueue {
    private var showing: StartPopup? = null
    private var used = 0

    fun pick(wanted: Set<StartPopup>, asked: Set<StartPopup>, opening: Int): StartPopup? {
        showing?.let { if (it in wanted) return it }
        showing = StartPopup.entries.firstOrNull { it in wanted && (it in asked || used != opening) }
        if (showing != null && showing !in asked) used = opening
        return showing
    }
}

private val queue = PopupQueue()

// THE way to show a popup when Kav opens. A new one gets a place in StartPopup and a line below, never a Dialog of its
// own in Root or a screen, so that two can never be on top of each other.
@Composable
fun StartPopups(model: KavModel) {
    val ctx = LocalContext.current
    var pickLook by remember { mutableStateOf(Prefs.pickLook(ctx)) }
    var notice by remember { mutableStateOf(StartNotice.notice(ctx)) }
    // From the phone alone, at each opening: an update downloaded, and one Kav just had, by itself or by hand. Nothing is
    // asked of the network here (Checks downloads in the background).
    var updated by remember { mutableStateOf<AutoUpdate.Notice?>(null) }
    var ready by remember { mutableStateOf<AutoUpdate.Notice?>(null) }
    // Later: not again until Kav next opens, when it's wanted again at once.
    var readyLater by remember { mutableIntStateOf(-1) }
    // Something the security block list names, read from the phone as Kav opens (ExtensionsScreen.kt).
    var blocked by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    val opening = Openings.popups
    LaunchedEffect(opening) {
        val app = ctx.applicationContext
        if (updated == null) updated = withContext(Dispatchers.IO) { AutoUpdate.notice(app) ?: WhatsNew.notice(app) }
        if (ready == null && Prefs.autoUpdate(app)) ready = withContext(Dispatchers.IO) { AutoUpdate.ready(app) }
        blocked = withContext(Dispatchers.IO) { runCatching { BlockNotice.names(app) }.getOrDefault(emptyList()) }
    }
    val readyNow = ready?.takeIf { r -> model.update?.let { Updates.isNewer(it.version, r.to) } != true }
    val wanted = buildSet {
        if (pickLook) add(StartPopup.LOOK)
        if (notice != null) add(StartPopup.NOTICE)
        if (mapPromptWanted) add(StartPopup.MAP)
        if (PendingBackup.uri != null) add(StartPopup.IMPORT)
        if (PendingExtension.link != null || PendingExtension.file != null) add(StartPopup.EXTENSION)
        if (blocked.isNotEmpty()) add(StartPopup.BLOCKED)
        // Extensions the crash guard switched off as Kav opened (NativeGuard), said in this opening.
        if (ExtCrashes.items.isNotEmpty()) add(StartPopup.CRASHED)
        // The version downloaded in the background, unless the check as Kav opened found a newer one, which is offered
        // to download and install instead.
        if (model.update != null && !model.updateDismissed && (Shown.updatePrompts || Prefs.autoUpdate(ctx)) && readyNow == null) {
            add(StartPopup.UPDATE)
        }
        if (readyNow != null && readyLater != opening) add(StartPopup.READY)
        if (updated != null) add(StartPopup.UPDATED)
    }
    // A sheet or screen the rider has open over Kav (paying's setup or turning it off, a ticket, a pick for an extension)
    // is never left under a popup: the popup waits for it to close.
    val covered = PaySetup.open || PayOffAsk.open || EntranceAsk.pending != null || PayHistory.open ||
        uk.noammm.kav.TicketStore.opened != null || ExtPicks.asking != null
    val shown = if (Openings.launched && !covered) queue.pick(wanted, setOf(StartPopup.IMPORT, StartPopup.EXTENSION, StartPopup.CRASHED), opening) else null
    // Kav's notices and prompts keep Kav's own look under a theme (ExtLook).
    ExtLook.Protected(shown != null)
    when (shown) {
        StartPopup.LOOK -> LookPrompt { Prefs.lookPicked(ctx); pickLook = false }
        StartPopup.NOTICE -> notice?.let { n ->
            NoticePrompt(n) {
                StartNotice.closed(ctx, n.id)
                notice = null
            }
        }
        StartPopup.MAP -> MapPrompt()
        StartPopup.IMPORT -> ImportPrompt(model)
        StartPopup.EXTENSION -> ExtensionPrompt()
        StartPopup.BLOCKED -> BlockedPrompt(blocked, { BlockNotice.seen(ctx); blocked = emptyList() }) {
            BlockNotice.seen(ctx); blocked = emptyList()
            model.settingsOpen = true
            SettingsNav.wanted = "extensions"
        }
        StartPopup.CRASHED -> CrashedPrompt(ExtCrashes.items, { ExtCrashes.items = emptyList() }) { id ->
            ExtCrashes.items = emptyList()
            model.settingsOpen = true
            SettingsNav.wanted = "extensions"
            ExtUi.wanted = "e:$id"
        }
        StartPopup.UPDATE -> UpdatePrompt(model)
        StartPopup.READY -> readyNow?.let { ReadyPrompt(it) { readyLater = opening } }
        StartPopup.UPDATED -> updated?.let { UpdatedNotice(it) { AutoUpdate.noticeShown(ctx); WhatsNew.shown(ctx); updated = null } }
        null -> {}
    }
}

// The window every popup above is drawn in, laid out in Kav's language from its first frame. Compose draws a dialog's
// first frames in the phone's own direction until its window catches up, and while Kav is busy opening that is long
// enough to see a Hebrew popup laid out left to right.
@Composable
internal fun StartDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) =
    androidx.compose.ui.window.Dialog(onDismissRequest) {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides
                if (T.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
            content = content,
        )
    }
