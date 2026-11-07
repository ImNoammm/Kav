package uk.noammm.kav.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import uk.noammm.kav.data.DownloadableMapTileProvider
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapTileProvider
import uk.noammm.kav.data.OfflineMapTileProvider
import uk.noammm.kav.data.OnlineOrOfflineMapTileProvider
import uk.noammm.kav.data.State as MapPackState

// "Not now" lasts until the app restarts. A map Kav needs can't be put off.
private var dismissed by mutableStateOf(false)

// Whether it would show: StartPopups decides when. A map with nothing to download needs no prompt, and one already on
// the phone needs nothing either.
internal fun mapPromptWanted(ctx: Context): Boolean {
    if (dismissed) return false
    val provider = MapFile.getProvider(ctx) as? DownloadableMapTileProvider ?: return false
    return !provider.isDownloaded(ctx) && !provider.isDownloadOptional()
}

// The download's progress, for the providers that keep one.
internal val MapTileProvider.downloadState: MapPackState?
    get() = when (this) {
        is OfflineMapTileProvider -> state
        is OnlineOrOfflineMapTileProvider -> state
        else -> null
    }

@Composable
fun MapPrompt() {
    val ctx = LocalContext.current
    val provider = remember(MapFile.type) { MapFile.getProvider(ctx) }
    val downloadable = provider as? DownloadableMapTileProvider ?: return
    val state = provider.downloadState
    val done = downloadable.isDownloaded(ctx) || state is MapPackState.Ready
    // Once it's there, let StartPopups move on to the next popup.
    LaunchedEffect(done) { if (done) dismissed = true }
    if (dismissed || done) return

    // A map Kav needs before it can show anything can't be put off: no "Later", and no dismiss.
    val required = !downloadable.isDownloadOptional()
    Dialog(
        onDismissRequest = { if (!required) dismissed = true },
        properties = DialogProperties(
            dismissOnBackPress = !required,
            dismissOnClickOutside = !required,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(
                if (required) T("Download the map", "הורדת המפה")
                else T("Download for offline use", "הורדה לשימוש לא מקוון"),
                style = K.type.title,
            )
            Text(
                if (required) T(
                    "Kav needs this map on your phone before it can show the map. It's about " +
                        "${MapFile.BYTES shr 20} MB for all of Israel, downloaded once. After that the map works with no signal.",
                    "Kav צריכה את המפה הזו בטלפון כדי להציג את המפה. מדובר בכ-${MapFile.BYTES shr 20} מגה-בייט " +
                        "לכל ישראל, בהורדה חד-פעמית. אחר כך המפה עובדת גם בלי קליטה.",
                ) else T(
                    "You can download this map to use it offline, so it keeps working without a signal. It isn't " +
                        "required: without it, Kav loads the map online while you look, and OpenFreeMap sees which area you view.",
                    "אפשר להוריד את המפה הזו לשימוש בלי אינטרנט, כך שהיא תמשיך לעבוד גם בלי קליטה. זה לא חובה: " +
                        "בלעדיה Kav טוענת את המפה מהאינטרנט בזמן הצפייה, ו-OpenFreeMap רואה איזה אזור אתם צופים בו.",
                ),
                fontSize = 14.sp, color = K.muted, lineHeight = 20.sp,
            )
            when (state) {
                is MapPackState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
                        Box(Modifier.fillMaxWidth(state.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(K.accent))
                    }
                    Text(T("Downloading… ${(state.progress * 100).toInt()}%", "מורידים… ${(state.progress * 100).toInt()}%"), fontSize = 12.sp, color = K.dim)
                }
                is MapPackState.Failed ->
                    Text(T("Couldn't download it. ${state.why}", "ההורדה נכשלה. ${state.why}"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp)
                else -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                if (!required) KavButton(
                    T("Not now", "לא עכשיו"), { dismissed = true }, Modifier.weight(1f).heightIn(min = 52.dp),
                    style = ButtonStyle.Secondary,
                )
                val busy = state is MapPackState.Downloading
                KavButton(
                    when {
                        busy -> T("Downloading…", "מורידים…")
                        state is MapPackState.Failed -> T("Try again", "נסו שוב")
                        else -> T("Download", "הורדה")
                    },
                    { downloadable.startDownload(ctx) }, Modifier.weight(1f), enabled = !busy,
                )
            }
        }
    }
}
