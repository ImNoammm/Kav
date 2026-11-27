package uk.noammm.kav

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.os.Build
import uk.noammm.kav.ui.Look

// The launch screen in the rider's look: Light's background with a dark arrow, Dark's or OLED's with the white one.
// While Kav follows the phone, Android's own launch screen gets it from launch.xml (values-night for dark). A look the
// rider picked can only be told to Android from 13 on (setSplashScreenTheme, kept for the next launches); before that
// the launch screen follows the phone, and the hand-over to Kav (SplashFlight) has to match it.
internal object LaunchLook {
    fun of(ctx: Context): Look =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || Prefs.followsSystem(ctx)) Prefs.look(ctx)
        else Prefs.lookOf(null, Prefs.night(ctx.resources.configuration.uiMode))

    // Kav's own backgrounds (Theme.kt's palettes), not a theme's: Android's launch screen can't have those.
    fun bg(look: Look): Int = when (look) {
        Look.LIGHT -> 0xFFF2F2EF.toInt()
        Look.DARK -> 0xFF101012.toInt()
        Look.OLED -> android.graphics.Color.BLACK
    }

    // The arrow: Light's text color on Light, white on the dark looks.
    fun ink(look: Look): Int = if (look == Look.LIGHT) 0xFF16161A.toInt() else android.graphics.Color.WHITE

    fun remember(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        activity.splashScreen.setSplashScreenTheme(
            if (Prefs.followsSystem(activity)) Resources.ID_NULL
            else when (Prefs.look(activity)) {
                Look.LIGHT -> R.style.Theme_Kav_Launch_Light
                Look.DARK -> R.style.Theme_Kav_Launch_Dark
                Look.OLED -> R.style.Theme_Kav_Launch_Oled
            }
        )
    }
}
