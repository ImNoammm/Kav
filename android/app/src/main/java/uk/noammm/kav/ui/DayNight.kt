package uk.noammm.kav.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import uk.noammm.kav.Prefs

// Settings' looks: System, the default, which goes with the phone's dark theme (Prefs.lookOf), or OLED, Light or Dark.
@Composable
internal fun LookRow(inset: Dp) {
    val ctx = LocalContext.current
    val looks = listOf(null, Look.OLED, Look.LIGHT, Look.DARK)
    KavSegmented(
        listOf(T("System", "מערכת"), "OLED", T("Light", "בהיר"), T("Dark", "כהה")),
        if (K.followsSystem) 0 else looks.indexOf(K.look).coerceAtLeast(1),
        { i ->
            Prefs.setLook(ctx, looks[i]); K.followsSystem = looks[i] == null
            val look = Prefs.look(ctx)
            K.restyle { K.applyTheme(look) }
        },
        Modifier.padding(horizontal = inset),
    )
}
