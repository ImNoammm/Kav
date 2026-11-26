package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import uk.noammm.kav.Checks
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.Updates

// Kav downloading new versions by itself, to install when the rider says so (AutoUpdate): Settings' switch. On unless
// turned off; it sends nothing about the rider, as the background check (Checks) only downloads.
@Composable
internal fun AutomaticUpdates() {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(Prefs.autoUpdate(ctx)) }
    var mobile by remember { mutableStateOf(Prefs.updatesOnMobile(ctx)) }
    // Read again whenever Kav is back from Android's settings.
    var mayInstall by remember { mutableStateOf(Updates.canInstall(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { mayInstall = Updates.canInstall(ctx) } }
    val info = T(
        "Kav asks GitHub when it opens and downloads a new version in the background. Nothing installs until you say so. GitHub sees your IP address.",
        "Kav שואלת את GitHub כשהיא נפתחת ומורידה גרסה חדשה ברקע. שום דבר לא מותקן בלי אישור שלכם. GitHub רואה את כתובת ה-IP שלכם.",
    )
    Column(Modifier.fillMaxWidth()) {
        // The rows are a card of their own under the update's, and the switch carries the (i).
        ListGroup(Modifier.padding(top = K.gap3).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
            row { UpdatePromptsRow() }
            row {
                ToggleRow(
                    T("Automatic updates", "עדכונים אוטומטיים"), auto,
                    { Icon(Icons.Rounded.SystemUpdate, null, tint = toggleInk(auto), modifier = Modifier.size(22.dp)) },
                    info = info,
                ) { on ->
                    auto = on
                    Prefs.setAutoUpdate(ctx, on)
                    Checks.ensure(ctx)
                }
            }
            if (auto) row {
                ToggleRow(
                    T("Also on mobile data", "גם בנתונים סלולריים"), mobile,
                    { Icon(Icons.Rounded.SignalCellularAlt, null, tint = toggleInk(mobile), modifier = Modifier.size(22.dp)) },
                    subtitle = T("About 32 MB a version", "בערך ${megabytes(32)} לגרסה"),
                ) { on ->
                    mobile = on
                    Prefs.setUpdatesOnMobile(ctx, on)
                }
            }
            if (auto && !mayInstall) row {
                ListRow(
                    T("Let Kav install updates", "הרשאה להתקין עדכונים"),
                    subtitle = T("Android asks this once", "Android שואלת את זה פעם אחת"),
                    onClick = { Updates.askInstallPermission(ctx) },
                )
            }
        }
    }
}
