package uk.noammm.kav.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import uk.noammm.kav.Prefs

// Settings' looks: OLED, Light, Dark, or System, which goes with the phone's dark theme (Prefs.lookOf), one choice.
@Composable
internal fun LookRow(inset: Dp) {
    val ctx = LocalContext.current
    val looks = listOf(Look.OLED, Look.LIGHT, Look.DARK, null)
    KavSegmented(
        listOf("OLED", T("Light", "בהיר"), T("Dark", "כהה"), T("System", "מערכת")),
        if (K.followsSystem) looks.lastIndex else looks.indexOf(K.look).coerceAtLeast(0),
        { i ->
            Prefs.setLook(ctx, looks[i]); K.followsSystem = looks[i] == null
            val look = Prefs.look(ctx)
            K.restyle { K.applyTheme(look) }
        },
        Modifier.padding(horizontal = inset),
    )
}
