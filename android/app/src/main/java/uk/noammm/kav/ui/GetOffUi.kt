package uk.noammm.kav.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.noammm.kav.GetOffService
import uk.noammm.kav.LOCATION_PERMISSIONS
import uk.noammm.kav.hasLocationPermission
import uk.noammm.kav.requestLocationOnce

// Which stop the get-off alert is set for ("<line>:<stop index>"), null when none is.
object GetOffState {
    var armed by mutableStateOf<String?>(null)
}

// The button on an open stop of a line's way. Not on the way's first stop (nobody gets off where the ride starts).
@Composable
fun GetOffButton(stops: List<GetOffStop>, index: Int, key: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val target = stops.getOrNull(index) ?: return
    if (index < 1 || target.lat == 0.0) return
    val mine = GetOffState.armed == key
    fun toast(text: String) = android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
    fun arm() {
        requestLocationOnce(ctx, onFail = {
            toast(T("Couldn't find your location", "לא הצלחנו לאתר את המיקום שלכם"))
        }) { here ->
            if (getOffMeters(here.first, here.second, target.lat, target.lon) < GET_OFF_NEAR_M) {
                toast(T("You're already near this stop", "אתם כבר קרובים לתחנה הזו"))
            } else {
                GetOffService.arm(ctx, GetOffWatch(stops, index), key)
                toast(T("You'll be told when to get off at ${target.name}", "נודיע לכם מתי לרדת ב${target.name}"))
            }
        }
    }
    // The one-time ask, after the first set-up: to run on time with the screen off (no battery saving for Kav) and to sound
    // through Do Not Disturb. Each is the phone's own screen; saying no, or not now, still sets the alert.
    val prefs = remember { ctx.getSharedPreferences("get_off", android.content.Context.MODE_PRIVATE) }
    var explain by remember { mutableStateOf(false) }
    val dndScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> arm() }
    fun askDnd() {
        if (GetOffService.dndAllowed(ctx)) { arm(); return }
        val ok = runCatching { dndScreen.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }.isSuccess
        if (!ok) arm()
    }
    val batteryScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ -> askDnd() }
    fun askBattery() {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (pm == null || pm.isIgnoringBatteryOptimizations(ctx.packageName)) { askDnd(); return }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
        val ok = runCatching { batteryScreen.launch(intent) }.isSuccess
        if (!ok) askDnd()
    }
    fun ready() {
        if (prefs.getBoolean("asked", false)) arm() else explain = true
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false; prefs.edit().putBoolean("asked", true).apply(); arm() },
            title = { Text(T("So the alert comes on time", "שההתראה תגיע בזמן")) },
            text = {
                Text(T(
                    "To keep the alert from being delayed or silenced, Kav can ask for two things: to not be limited by battery saving, and Do Not Disturb access so the alert still sounds in that mode. You can skip both; the alert works without them, just less reliably.",
                    "כדי שההתראה לא תתעכב ולא תושתק, Kav יכול לבקש שני דברים: שלא תהיה הגבלה של חיסכון בסוללה, וגישה ל'נא לא להפריע' כדי שההתראה תישמע גם במצב הזה. אפשר לדלג על שניהם, ההתראה תעבוד גם בלעדיהם, רק פחות בטוח.",
                ))
            },
            confirmButton = {
                TextButton({ explain = false; prefs.edit().putBoolean("asked", true).apply(); askBattery() }) { Text(T("Continue", "המשך")) }
            },
            dismissButton = {
                TextButton({ explain = false; prefs.edit().putBoolean("asked", true).apply(); arm() }) { Text(T("Not now", "לא עכשיו")) }
            },
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (hasLocationPermission(ctx)) ready()
        else toast(T("Location is needed for this", "צריך גישה למיקום בשביל זה"))
    }
    Box(Modifier.padding(start = K.gap3 + 18.dp + K.gap3, top = K.gap2, bottom = K.gap2)) {
        KavButton(
            if (mine) T("Cancel the get-off alert", "ביטול ההתראה לירידה") else T("Tell me when to get off here", "הודע לי לרדת כאן"),
            {
                if (mine) GetOffService.cancel(ctx)
                else {
                    val need = buildList {
                        if (!hasLocationPermission(ctx)) addAll(LOCATION_PERMISSIONS)
                        if (Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (need.isEmpty()) ready() else ask.launch(need.toTypedArray())
                }
            },
            style = ButtonStyle.Secondary,
        )
    }
}
