package uk.noammm.kav

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.stopOfKey
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.KavButton
import uk.noammm.kav.ui.KavTheme
import uk.noammm.kav.ui.ListGroup
import uk.noammm.kav.ui.ListRow
import uk.noammm.kav.ui.ScreenHeader
import uk.noammm.kav.ui.T

// Which saved stop a next buses widget shows (DeparturesWidget): opened by the launcher as one is placed, and again when
// the rider reconfigures it. The choice is kept per widget, so several widgets can show several stops. In Kav's look;
// backing out places nothing.
class DeparturesWidgetConfigActivity : ComponentActivity() {
    // A saved stop: its key, and its name and town from the timetable (its number without one).
    private class Option(val key: String, val name: String, val city: String)

    private var options by mutableStateOf<List<Option>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // Only for a next buses widget of Kav's own: another app can open this screen too.
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID ||
            AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider != ComponentName(this, DeparturesWidget::class.java)) {
            finish(); return
        }
        T.lang = Prefs.lang(this)
        StopNames.english = Prefs.stopNamesEnglish(this)
        K.accent = Color(Prefs.accent(this))
        K.applyTheme(Prefs.look(this))
        enableEdgeToEdge()
        val app = applicationContext
        Thread {
            options = runCatching { load() }.getOrDefault(emptyList())
        }.start()
        setContent {
            // The bars' icons in the look's own colors, as on Kav's screens.
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = K.light
                    isAppearanceLightNavigationBars = K.light
                }
            }
            KavTheme {
                CompositionLocalProvider(LocalLayoutDirection provides if (T.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    Column(Modifier.fillMaxSize().background(K.bg).systemBarsPadding().verticalScroll(rememberScrollState())) {
                        ScreenHeader(T("Choose a stop", "בחירת תחנה"), back = { finish() })
                        val list = options
                        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                            when {
                                list == null -> {}
                                list.isEmpty() -> {
                                    Text(
                                        T("Save a stop in Kav first.", "שמרו קודם תחנה ב-Kav."),
                                        style = K.type.body, modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap3),
                                    )
                                    KavButton(T("Open Kav", "פתיחת Kav"), {
                                        startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        finish()
                                    }, modifier = Modifier.padding(horizontal = K.gap4))
                                }
                                else -> ListGroup {
                                    for (o in list) row {
                                        ListRow(o.name, subtitle = o.city.ifBlank { null }, onClick = { choose(id, o.key) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // The saved stops, named from the timetable, which is read for it when Kav isn't running.
    private fun load(): List<Option> {
        val keys = Prefs.savedStops(this)
        if (keys.isEmpty()) return emptyList()
        val net = Loaded.net ?: runCatching { kotlinx.coroutines.runBlocking { loadNet(this@DeparturesWidgetConfigActivity) } }.getOrNull()
        return keys.map { key ->
            val stop = net?.stopOfKey(key)?.takeIf { it >= 0 }
            if (net == null || stop == null) Option(key, key.substringBefore('/'), "") else Option(key, net.stopName(stop), net.cityName(stop))
        }
    }

    private fun choose(id: Int, key: String) {
        Prefs.setWidgetStop(this, id, key)
        DeparturesWidget.update(this, id)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
