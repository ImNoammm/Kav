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

// Kav downloading new versions by itself, to install when the rider says so (AutoUpdate): Settings' switch and a setup
// step. On unless turned off; it sends nothing about the rider, as the background check
// (Checks) only downloads.
@Composable
internal fun AutomaticUpdates(setup: Boolean = false) {
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
        // Setting Kav up, the step's own title carries the (i); in Settings the rows are a card of their own under the
        // update's, and the first row carries it.
        if (setup) {
            InfoHeader(T("Updates", "עדכונים"), info, headline = true)
            Spacer(Modifier.height(K.gap3))
        }
        ListGroup(Modifier.padding(horizontal = if (setup) K.gap3 else 0.dp).padding(top = if (setup) 0.dp else K.gap3).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
            if (!setup) row { UpdatePromptsRow() }
            row {
                ToggleRow(
                    T("Automatic updates", "עדכונים אוטומטיים"), auto,
                    { Icon(Icons.Rounded.SystemUpdate, null, tint = toggleInk(auto), modifier = Modifier.size(22.dp)) },
                    info = if (setup) null else info,
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
                    subtitle = T("About 32 MB a version", "בערך 32MB לגרסה"),
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
