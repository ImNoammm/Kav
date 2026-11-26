package uk.noammm.kav

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.stopOfKey
import uk.noammm.kav.ext.Widgets
import uk.noammm.kav.ui.Shown
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.clockFormat
import java.util.Date

// A home screen widget with the next buses at a saved stop: the one the rider chose for it as it was placed
// (DeparturesWidgetConfigActivity), so several widgets can show several stops. Drawn as Kav's extension widgets are
// (ExtWidgets): Kav's look, light or dark, a line plate, where each goes and its time. Its departures are the stop's board
// in Kav (boardJson): live times from Kav's server where it has them, green, and the timetable on the phone for the rest,
// and for all of them when the server can't be reached. Times are clock times, which stay true between refreshes as
// minutes wouldn't; one that has gone leaves at its time, drawn again from what was last fetched, asking nothing. Android
// refreshes it every half hour at most, starting Kav for it if Kav isn't running; Kav refreshes it besides when its
// saved stops change. A tap opens the stop's board.
class DeparturesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = work { NextBuses.update(ctx, ids.toList(), fetch = true) }

    // Resized: as many rows as fit now.
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) =
        work { NextBuses.update(ctx, listOf(id), fetch = false) }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) { Prefs.dropWidgetStop(ctx, id); NextBuses.forget(ctx, id) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == NextBuses.ACTION_REDRAW) work { NextBuses.update(ctx, NextBuses.placed(ctx), fetch = false) }
        else super.onReceive(ctx, intent)
    }

    // Off the main thread, with the broadcast held open until it's done (Android gives it ten seconds).
    private fun work(block: () -> Unit) {
        val pending = goAsync()
        Thread({ try { runCatching(block) } finally { pending.finish() } }, "kav-departures").start()
    }

    companion object {
        // Every placed widget, after the saved stops change.
        fun refresh(ctx: Context) = later(ctx, fetch = true)

        // Drawn again from what each showed, in the look the rider may have changed, as Kav is left.
        fun redraw(ctx: Context) = later(ctx, fetch = false)

        // One widget, just placed or given another stop.
        fun update(ctx: Context, id: Int) {
            val app = ctx.applicationContext
            Thread({ runCatching { NextBuses.update(app, listOf(id), fetch = true) } }, "kav-departures").start()
        }

        private fun later(ctx: Context, fetch: Boolean) {
            val app = ctx.applicationContext
            Thread({ runCatching { NextBuses.placed(app).takeIf { it.isNotEmpty() }?.let { NextBuses.update(app, it, fetch) } } }, "kav-departures").start()
        }
    }
}

internal object NextBuses {
    const val ACTION_REDRAW = "uk.noammm.kav.action.DEPARTURES_REDRAW"
    private const val ACTION_STOP = "uk.noammm.kav.action.WIDGET_STOP"
    // How far ahead it shows, and how many it keeps.
    private const val AHEAD_S = 2 * 3600L
    private const val MOST = 24

    // A departure as the widget keeps it: its line (empty for a train), where it goes, its time (the live one where
    // there is one) and whether that time is live.
    data class Dep(val line: String, val to: String, val at: Long, val live: Boolean)

    // What was fetched for a widget: its stop (the saved stop's key) and its name, when, whether Kav's server answered,
    // and the departures.
    data class Got(val key: String, val name: String, val at: Long, val reached: Boolean, val deps: List<Dep>)

    // The board's departures (boardJson), without the canceled.
    fun fromBoard(rows: JSONArray): List<Dep> = (0 until rows.length()).mapNotNull { i ->
        val o = rows.optJSONObject(i) ?: return@mapNotNull null
        if (o.optBoolean("cancelled")) return@mapNotNull null
        val live = if (o.isNull("live")) null else o.optLong("live")
        Dep(o.optString("line"), o.optString("to"), live ?: o.optLong("at"), live != null)
    }

    // Those it shows at now: not gone yet, within the next two hours, soonest first.
    fun due(deps: List<Dep>, now: Long): List<Dep> = deps.filter { it.at >= now - 30 && it.at <= now + AHEAD_S }.sortedBy { it.at }

    // When the first of them goes, to be drawn again without it; null with none.
    fun nextRedraw(deps: List<Dep>, now: Long): Long? = due(deps, now).firstOrNull()?.let { it.at + 60 }

    // What the widget shows: the stop's name, its departures with their clock times, and when they were fetched, or that
    // they are the timetable's because the server couldn't be reached. got null: nothing fetched (saved: a stop to show).
    fun content(got: Got?, saved: Boolean, now: Long, clock: (Long) -> String): Pair<Widgets.Content, List<Boolean>> {
        if (got == null) {
            val empty = if (saved) T("Open Kav to set it up.", "פתחו את Kav כדי להגדיר אותה.")
                else T("Save a stop in Kav to see its buses here.", "שמרו תחנה ב-Kav כדי לראות כאן את האוטובוסים שלה.")
            return Widgets.Content("Kav", emptyList(), null, empty, null, 0) to emptyList()
        }
        val shown = due(got.deps, now)
        val rows = shown.map { d ->
            Widgets.Row(d.to, null, clock(d.at), if (d.line.isBlank()) "train" else null, d.line.ifBlank { null }, null)
        }
        val note = if (got.reached) T("Updated ${clock(got.at)}", "עודכן ב-${clock(got.at)}")
            else T("No connection · timetable", "אין חיבור · לוח זמנים")
        val empty = T("No buses in the next two hours", "אין אוטובוסים בשעתיים הקרובות")
        return Widgets.Content(got.name, rows, note, empty, null, 0) to shown.map { it.live }
    }

    fun placed(ctx: Context): List<Int> = runCatching {
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, DeparturesWidget::class.java)).toList()
    }.getOrDefault(emptyList())

    fun update(ctx: Context, ids: List<Int>, fetch: Boolean) {
        if (ids.isEmpty()) return
        val app = ctx.applicationContext
        T.lang = Prefs.lang(app)
        Shown.twelveHour = Prefs.twelveHour(app)
        StopNames.english = Prefs.stopNamesEnglish(app)
        if (fetch) Prefs.loadSources(app)
        val saved = Prefs.savedStops(app)
        val now = System.currentTimeMillis() / 1000
        val clock = clockFormat()
        for (id in ids) runCatching {
            // The stop chosen for this widget, else the first saved stop.
            val key = Prefs.widgetStop(app, id) ?: saved.firstOrNull()
            // Fetched now when asked to, else what was kept for its stop; fetched anyway when nothing was.
            val got = key?.let { k ->
                val kept = kept(app, id)?.takeIf { it.key == k }
                (if (fetch || kept == null) fetched(app, k, now)?.also { keep(app, id, it) } else null) ?: kept
            }
            val (content, live) = content(got, key != null, now) { clock.format(Date(it * 1000)) }
            ExtWidgets.drawOwn(app, id, content, live, open(app, id, key))
        }
        schedule(app, now)
    }

    // The stop's board now, from the timetable on the phone (loaded for it when Kav isn't running) with the live times
    // over it. Null without a timetable or a stop it knows.
    private fun fetched(app: Context, key: String, now: Long): Got? {
        val net = Loaded.net ?: runCatching { kotlinx.coroutines.runBlocking { loadNet(app) } }.getOrNull() ?: return null
        val stop = net.stopOfKey(key).takeIf { it >= 0 } ?: return null
        var reached = false
        val rows = kotlinx.coroutines.runBlocking { uk.noammm.kav.ui.boardJson(app, net, stop, MOST) { reached = it } }
        return Got(key, net.stopName(stop), now, reached, fromBoard(rows))
    }

    // A tap opens the stop's board, as a line alert's does. Its own action and request code each, so each widget's
    // intent is its own; complete as it is made, so nothing has to be filled in later.
    private fun open(app: Context, id: Int, key: String?): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).setAction(ACTION_STOP).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (key != null) intent.putExtra(LineAlerts.EXTRA_STOP, key)
        return PendingIntent.getActivity(app, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    // -- What each widget last fetched, to draw again without asking ------------------------------------------------------

    private fun store(ctx: Context) = ctx.getSharedPreferences("kav_departures_widget", Context.MODE_PRIVATE)

    internal fun write(g: Got): String = JSONObject().put("key", g.key).put("name", g.name).put("at", g.at).put("reached", g.reached)
        .put("deps", JSONArray(g.deps.map { JSONObject().put("line", it.line).put("to", it.to).put("at", it.at).put("live", it.live) })).toString()

    internal fun read(text: String): Got? = runCatching {
        val o = JSONObject(text)
        val a = o.getJSONArray("deps")
        Got(o.getString("key"), o.getString("name"), o.getLong("at"), o.getBoolean("reached"), (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Dep(it.getString("line"), it.getString("to"), it.getLong("at"), it.getBoolean("live")) }
        })
    }.getOrNull()

    private fun keep(ctx: Context, id: Int, g: Got) = store(ctx).edit().putString("$id", write(g)).apply()
    private fun kept(ctx: Context, id: Int): Got? = store(ctx).getString("$id", null)?.let(::read)
    fun forget(ctx: Context, id: Int) = store(ctx).edit().remove("$id").apply()

    // One alarm for all of them, at the soonest a shown departure goes; RTC, so it waits for the phone to be awake rather
    // than waking it, and draws again from what was kept.
    private fun schedule(app: Context, now: Long) {
        val due = placed(app).mapNotNull { id -> kept(app, id)?.let { nextRedraw(it.deps, now) } }.minOrNull()
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, 0, Intent(app, DeparturesWidget::class.java).setAction(ACTION_REDRAW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (due == null) alarms.cancel(pi) else alarms.set(AlarmManager.RTC, due * 1000, pi)
    }
}
