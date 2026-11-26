package uk.noammm.kav

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.stopOfKey
import uk.noammm.kav.ui.Shown
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.clockFormat
import java.util.Date

// Home-screen widget (#118): the next buses at the rider's first saved stop, without opening Kav. A bus the server
// tracks shows its live minutes; one it only knows from the timetable shows the clock time, like the line alerts do.
// The list comes from DeparturesWidgetService below; tapping a row opens Kav on the stop. The system refreshes the
// widget at most every half hour (widgetInfo updatePeriodMillis), and Kav asks for a refresh when its saved stops
// change.
class DeparturesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(ctx, mgr, id)
    }

    companion object {
        // Every live widget, after the saved stops change.
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(android.content.ComponentName(ctx, DeparturesWidget::class.java))
            for (id in ids) update(ctx, mgr, id)
        }

        fun update(ctx: Context, mgr: AppWidgetManager, id: Int) {
            T.lang = Prefs.lang(ctx)
            Shown.twelveHour = Prefs.twelveHour(ctx)
            // The saved stop's name when the timetable is already in memory; a generic title when it isn't.
            val stopName = Prefs.savedStops(ctx).firstOrNull()?.let { key ->
                Loaded.net?.let { n -> n.stopOfKey(key).takeIf { it >= 0 }?.let { n.stopName(it) } }
            }
            val views = RemoteViews(ctx.packageName, R.layout.widget_departures)
            views.setTextViewText(R.id.widget_title, stopName ?: T("Next buses", "האוטובוסים הבאים"))
            views.setRemoteAdapter(R.id.widget_list, Intent(ctx, DeparturesWidgetService::class.java))
            views.setEmptyView(R.id.widget_list, R.id.widget_empty)
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_title, open)
            views.setPendingIntentTemplate(R.id.widget_list, open)
            mgr.updateAppWidget(id, views)
            mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
        }
    }
}

class DeparturesWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)
}

private class Factory(val ctx: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<String> = emptyList()
    private var stopKey: String? = null

    // Runs on a binder thread, off the main one: the server's answer may take a moment.
    override fun onDataSetChanged() {
        T.lang = Prefs.lang(ctx)
        Shown.twelveHour = Prefs.twelveHour(ctx)
        val first = Prefs.savedStops(ctx).firstOrNull() ?: run { rows = emptyList(); stopKey = null; return }
        stopKey = first
        val code = first.substringBefore('/').toIntOrNull() ?: return
        val now = System.currentTimeMillis() / 1000
        val clock = clockFormat()
        rows = runCatching {
            Prefs.loadSources(ctx)
            KavLive.stops(listOf(code)).due
                .filter { it.stop == code && it.expected > 0 }
                .sortedBy { it.expected }
                .take(8)
                .map { d ->
                    // A bus the server tracks shows live minutes; one it only has from the timetable, the clock time.
                    val when_ = if (d.underWay) {
                        val mins = (d.expected - now) / 60
                        if (mins < 1) T("now", "עכשיו") else T("$mins min", "$mins דק׳")
                    } else clock.format(Date(d.expected * 1000))
                    "${d.number} · $when_"
                }
        }.getOrDefault(emptyList())
    }

    override fun getViewAt(position: Int): RemoteViews =
        RemoteViews(ctx.packageName, R.layout.widget_departure_row).apply {
            setTextViewText(R.id.widget_row_text, rows.getOrElse(position) { "" })
            // A tapped row fills the template's intent with the stop, so it opens on its board.
            stopKey?.let { setOnClickFillInIntent(R.id.widget_row_text, Intent().putExtra(LineAlerts.EXTRA_STOP, it)) }
        }

    override fun getCount() = rows.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount() = 1
    override fun getItemId(position: Int) = position.toLong()
    override fun hasStableIds() = true
    override fun onCreate() {}
    override fun onDestroy() {}
}
