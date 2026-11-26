package uk.noammm.kav

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.stopOfKey
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.T

// Shown as the departures widget is placed on the home screen (#118): which saved stop's departures it shows.
// The choice is kept per widget, so several widgets can watch several stops. A widget placed without a choice
// (placed before this screen existed) shows the first saved stop, as it always did.
class DeparturesWidgetConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Backing out of placing the widget adds nothing.
        setResult(RESULT_CANCELED)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        T.lang = Prefs.lang(this)
        K.look = Prefs.look(this)
        setContent { Picker() }
    }

    @Composable
    private fun Picker() {
        val ctx = LocalContext.current
        val keys = remember { Prefs.savedStops(ctx) }
        var net by remember { mutableStateOf(Loaded.net) }
        var tried by remember { mutableStateOf(false) }
        // The timetable gives the stops their names; when Kav has not run yet it loads here first.
        LaunchedEffect(Unit) {
            if (net == null) {
                net = runCatching { loadNet(ctx) }.getOrNull()?.also { Loaded.store(it) }
                tried = true
            }
        }
        Column(Modifier.fillMaxSize().background(K.bg).padding(K.gap5)) {
            Text(T("Which stop's departures?", "יציאות מאיזו תחנה?"), style = K.type.title)
            Spacer(Modifier.height(K.gap4))
            when {
                keys.isEmpty() -> Text(
                    T(
                        "No stops are saved yet. Save a stop in Kav first, then add the widget again.",
                        "עוד לא נשמרו תחנות. שמרו תחנה ב-Kav קודם, ואז הוסיפו את הווידג׳ט שוב.",
                    ),
                    style = K.type.body,
                )
                net == null && !tried -> Text(T("Loading the timetable…", "טוען את לוח הזמנים…"), style = K.type.body)
                else -> for (key in keys) StopRow(key, net)
            }
        }
    }

    @Composable
    private fun StopRow(key: String, net: Net?) {
        // Without the timetable the stop's number stands in for its name.
        val name = net?.let { n -> n.stopOfKey(key).takeIf { it >= 0 }?.let { n.stopName(it) } }
            ?: key.substringBefore('/')
        Text(
            name,
            style = K.type.body,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { choose(key) }
                .padding(vertical = K.gap3),
        )
    }

    private fun choose(key: String) {
        Prefs.setWidgetStop(this, widgetId, key)
        val mgr = AppWidgetManager.getInstance(this)
        DeparturesWidget.update(this, mgr, widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
