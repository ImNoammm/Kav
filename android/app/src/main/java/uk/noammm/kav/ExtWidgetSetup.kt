package uk.noammm.kav

import android.appwidget.AppWidgetManager
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
import uk.noammm.kav.ext.ExtApp
import uk.noammm.kav.ext.SafeMode
import uk.noammm.kav.ext.Widgets
import uk.noammm.kav.ui.K
import uk.noammm.kav.ui.KavButton
import uk.noammm.kav.ui.KavTheme
import uk.noammm.kav.ui.ListGroup
import uk.noammm.kav.ui.ListRow
import uk.noammm.kav.ui.ScreenHeader
import uk.noammm.kav.ui.T

// Which extension's widget a Kav widget shows (ExtWidgets.kt): opened by the launcher as one is placed, and again when
// the rider reconfigures it. Lists every widget of the extensions that are on, in Kav's look; backing out places nothing.
class ExtWidgetSetup : ComponentActivity() {
    // One extension's widget: whose, and its declaration.
    private class Option(val identity: String, val name: String, val decl: Widgets.Decl)

    private var options by mutableStateOf<List<Option>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        // Only for a widget of Kav's own: another app can open this screen too.
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider?.packageName != packageName) {
            finish(); return
        }
        T.lang = Prefs.lang(this)
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
                        ScreenHeader(T("Choose a widget", "בחירת ווידג׳ט"), back = { finish() })
                        val list = options
                        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
                            when {
                                list == null -> {}
                                list.isEmpty() -> {
                                    Text(
                                        if (SafeMode.active) T("Kav started without extensions (safe mode).", "‏Kav נפתחה בלי תוספים (מצב בטוח).")
                                        else T("None of your extensions that are on adds a widget.", "אף אחד מהתוספים הפעילים שלכם לא מוסיף ווידג׳ט."),
                                        style = K.type.body, modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap3),
                                    )
                                    KavButton(T("Open extensions", "פתיחת התוספים"), {
                                        startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            .putExtra(ExtWidgets.EXTRA_EXTENSIONS, true))
                                        finish()
                                    }, modifier = Modifier.padding(horizontal = K.gap4))
                                }
                                else -> ListGroup {
                                    for (o in list) row {
                                        ListRow(
                                            T(o.decl.title.first, o.decl.title.second.ifEmpty { o.decl.title.first }),
                                            subtitle = listOfNotNull(T.iso(o.name), o.decl.description.takeIf { it.first.isNotEmpty() }?.let { T(it.first, it.second.ifEmpty { it.first }) })
                                                .joinToString(" · "),
                                            subtitleMaxLines = 2,
                                            onClick = { choose(id, o) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun load(): List<Option> {
        if (SafeMode.active || !ExtApp.dir(this).isDirectory) return emptyList()
        val hub = ExtApp.hub(this)
        return hub.registry.installed.filter { hub.active(it) }.sortedWith(compareBy(uk.noammm.kav.ext.Names.order()) { it.displayName }).flatMap { i -> Widgets.of(i.manifest).map { Option(i.identity, i.displayName, it) } }
    }

    private fun choose(id: Int, o: Option) {
        val app = applicationContext
        Thread { runCatching { ExtWidgets.choose(app, id, o.identity, o.decl.id) } }.start()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        finish()
    }
}
