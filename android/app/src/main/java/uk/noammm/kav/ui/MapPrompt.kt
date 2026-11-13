package uk.noammm.kav.ui

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
import uk.noammm.kav.data.MapFile
import uk.noammm.kav.data.MapPack

// "Later" lasts until the app restarts, and leaves the map as it is: the map waits for its download instead of loading
// online.
private var dismissed by mutableStateOf(false)

// Whether it would show: StartPopups decides when. Only a map that can't draw without its download asks.
internal val mapPromptWanted: Boolean
    get() = !dismissed && MapFile.provider().let { it.needsPack && it.pack.state !is MapPack.State.Ready }

@Composable
fun MapPrompt() {
    val pack = MapFile.provider().pack
    val state = pack.state
    if (dismissed || state is MapPack.State.Ready) return
    val ctx = LocalContext.current
    Dialog(onDismissRequest = { dismissed = true }) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(T("Download the map", "הורדת המפה"), style = K.type.title)
            Text(
                T(
                    "Kav keeps its map on your phone instead of loading tiles from a server as " +
                        "you go, so nothing tracks where you look. It's about ${pack.bytes shr 20} MB for " +
                        "all of Israel, downloaded once. After that the map works with no signal.",
                    "Kav שומרת את המפה על הטלפון שלכם במקום לטעון אריחים משרת תוך כדי תנועה, " +
                        "כך שאף אחד לא עוקב אחרי מה שאתם מסתכלים עליו. מדובר בכ-${pack.bytes shr 20} מגה-בייט " +
                        "לכל ישראל, בהורדה חד-פעמית. אחר כך המפה עובדת גם בלי קליטה.",
                ),
                fontSize = 14.sp, color = K.muted, lineHeight = 20.sp,
            )
            when (state) {
                is MapPack.State.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
                    // The bar fills smoothly between ticks, and the percentage rolls.
                    val fill by androidx.compose.animation.core.animateFloatAsState(state.progress.coerceIn(0.02f, 1f), androidx.compose.animation.core.tween(300), label = "map")
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(K.accent))
                    }
                    LiveText(T("Downloading… ${(state.progress * 100).toInt()}%", "מורידים… ${(state.progress * 100).toInt()}%"), style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp), color = K.dim)
                }
                is MapPack.State.Failed ->
                    Text(T("Couldn't download it. ${state.why}", "ההורדה נכשלה. ${state.why}"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp)
                else -> {}
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(
                    T("Later", "אחר כך"), { dismissed = true }, Modifier.weight(1f).heightIn(min = 52.dp),
                    style = ButtonStyle.Secondary,
                )
                val busy = state is MapPack.State.Downloading
                KavButton(
                    when {
                        busy -> T("Downloading…", "מורידים…")
                        state is MapPack.State.Failed -> T("Try again", "נסו שוב")
                        else -> T("Download", "הורדה")
                    },
                    { pack.download(ctx.applicationContext.filesDir) }, Modifier.weight(1f), enabled = !busy,
                )
            }
        }
    }
}
