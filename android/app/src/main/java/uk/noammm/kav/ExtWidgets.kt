package uk.noammm.kav

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import uk.noammm.kav.ext.Blocks
import uk.noammm.kav.ext.ExtApp
import uk.noammm.kav.ext.ExtLog
import uk.noammm.kav.ext.Hub
import uk.noammm.kav.ext.Native
import uk.noammm.kav.ext.SafeMode
import uk.noammm.kav.ext.Widgets
import uk.noammm.kav.ui.Look
import uk.noammm.kav.ui.T
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Kav's home screen widgets that show an extension's (ext/Widgets.kt): a small one and a wide one, both resizable, so the
// launcher's list has two of Kav's and not one per extension. Each placed one shows the extension's widget the rider
// chose for it (ExtWidgetSetup), drawn here in Kav's look: its light or dark colors (both, for Settings' System look,
// on Android 12 and newer), the rider's accent, Kav's corners and its language's direction.
//
// Its content comes from the extension's code, which runs only while Kav does. Android's update (every half hour at
// most) starts Kav if it isn't running; its extensions start as Kav starts (ExtApp), and Kav waits for them, asks the
// widget within Widgets.ASK_MS and keeps what it answered. When it can't answer, the widget keeps what it last showed.
// Besides, Kav asks again when it opens, when the extension asks (Kav.refreshWidget), and at the time its content says it
// changes (next), with an alarm that doesn't wake the phone. Nothing polls, and nothing holds the phone awake.
open class ExtWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = work { ExtWidgets.update(ctx, ids.toList(), ask = true) }

    // Resized: as many rows as fit now.
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) = work { ExtWidgets.update(ctx, listOf(id), ask = true) }

    override fun onDeleted(ctx: Context, ids: IntArray) = work { ExtWidgets.deleted(ctx, ids.toList()) }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ExtWidgets.ACTION_CALL -> work { ExtWidgets.call(ctx, intent) }
            ExtWidgets.ACTION_PINNED -> work { ExtWidgets.pinned(ctx, intent) }
            ExtWidgets.ACTION_NEXT -> work { ExtWidgets.update(ctx, ExtWidgets.placed(ctx), ask = true) }
            else -> super.onReceive(ctx, intent)
        }
    }

    // Off the main thread, with the broadcast held open until it's done (Android gives it ten seconds).
    private fun work(block: () -> Unit) {
        val pending = goAsync()
        Thread({ try { runCatching(block) } finally { pending.finish() } }, "kav-widget").start()
    }
}

class ExtWidgetSmall : ExtWidget()
class ExtWidgetWide : ExtWidget()

object ExtWidgets {
    const val ACTION_CALL = "uk.noammm.kav.action.WIDGET_CALL"
    const val ACTION_PINNED = "uk.noammm.kav.action.WIDGET_PINNED"
    const val ACTION_NEXT = "uk.noammm.kav.action.WIDGET_NEXT"
    // A tap that opens Kav (MainActivity): the widget, and the row tapped (ROW_WIDGET for the widget itself, ROW_PAGE for
    // the extension's page in Settings). The action itself is read from what the widget shows, never from the intent.
    const val EXTRA_OPEN = "uk.noammm.kav.widget.OPEN"
    const val EXTRA_ROW = "uk.noammm.kav.widget.ROW"
    // Settings › Extensions, from the widget picker with nothing to pick.
    const val EXTRA_EXTENSIONS = "uk.noammm.kav.widget.EXTENSIONS"
    private const val EXTRA_IDENTITY = "uk.noammm.kav.widget.IDENTITY"
    private const val EXTRA_WIDGET = "uk.noammm.kav.widget.WIDGET"
    const val ROW_WIDGET = -1
    const val ROW_PAGE = -2
    // How long an update waits for Kav's extensions to start, in a Kav Android started for it, and a call has to answer:
    // with Widgets.ASK_MS for the content after it, a tap is done within the ten seconds Android gives its broadcast.
    private const val START_WAIT_MS = 4_000L
    private const val CALL_MS = 2_000L
    // An extension asking again and again is answered at most this often.
    private const val GAP_MS = 10_000L

    private fun providers(ctx: Context) = listOf(ComponentName(ctx, ExtWidgetSmall::class.java), ComponentName(ctx, ExtWidgetWide::class.java))

    fun placed(ctx: Context): List<Int> {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers(ctx).flatMap { runCatching { mgr.getAppWidgetIds(it).toList() }.getOrDefault(emptyList()) }
    }

    // What an extension or Kav asks for (Widgets.refresh): done on one thread of its own, a little later, so asks that
    // come together are done once.
    private val queue = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kav-widgets").apply { isDaemon = true } }
    private val waiting = ConcurrentHashMap.newKeySet<String>()
    private val asked = ConcurrentHashMap<String, Long>()

    fun attach(ctx: Context) {
        val app = ctx.applicationContext
        Widgets.refresher = { identity, widget, ask -> soon(app, "$identity|${widget.orEmpty()}", ask) { Widgets.Placed.showing(identity, widget) } }
    }

    private fun soon(app: Context, key: String, ask: Boolean, ids: () -> List<Int>) {
        if (!waiting.add("$key|$ask")) return
        val now = System.currentTimeMillis()
        val wait = if (ask) ((asked[key] ?: 0L) + GAP_MS - now).coerceAtLeast(300L) else 0L
        queue.schedule({
            waiting.remove("$key|$ask")
            if (ask) asked[key] = System.currentTimeMillis()
            runCatching { update(app, ids(), ask) }
        }, wait, TimeUnit.MILLISECONDS)
    }

    // Kav opened: every placed widget asked again; Kav left: drawn again from what each showed, in the look the rider may
    // have changed meanwhile.
    fun opened(ctx: Context) = everything(ctx, true)
    fun left(ctx: Context) = everything(ctx, false)

    private fun everything(ctx: Context, ask: Boolean) {
        val app = ctx.applicationContext
        if (!ExtApp.dir(app).isDirectory) return
        queue.execute { runCatching { placed(app).takeIf { it.isNotEmpty() }?.let { update(app, it, ask) } } }
    }

    // -- Drawing ---------------------------------------------------------------------------------------------------------

    fun update(ctx: Context, ids: List<Int>, ask: Boolean) {
        if (ids.isEmpty()) return
        val app = ctx.applicationContext
        T.lang = Prefs.lang(app)
        val dir = ExtApp.dir(app)
        val hub = if (dir.isDirectory) {
            attach(app)
            if (ask) ExtApp.awaitStarted(START_WAIT_MS)
            ExtApp.hub(app)
        } else null
        val mgr = AppWidgetManager.getInstance(app)
        for (id in ids) runCatching {
            val sizes = sizes(mgr, id)
            val show = show(app, hub, id, ask, sizes)
            mgr.updateAppWidget(id, views(app, id, show, sizes))
        }
        if (hub != null) next(app)
    }

    // What one placed widget shows now, asking the extension's code first when ask is true.
    private fun show(app: Context, hub: Hub?, id: Int, ask: Boolean, sizes: List<Size>): Widgets.Show {
        val choice = Widgets.Placed.of(id)
        val installed = choice?.let { hub?.registry?.get(it.identity) }
        val active = installed != null && hub!!.active(installed)
        val dir = ExtApp.dir(app)
        var fresh: Widgets.Content? = null
        if (ask && choice != null && active && !SafeMode.active) {
            val args = Widgets.args(choice.widget, sizes.maxOf { it.rows(true) }, sizes.maxOf { it.w }, sizes.maxOf { it.h })
            Native.widget(choice.identity, choice.widget, args.toString(), Widgets.ASK_MS)?.let { answer ->
                answer.onFailure { failed(dir, choice.identity, choice.widget, it.message ?: "failed") }.getOrNull()?.takeIf { it != "null" }?.let { text ->
                    runCatching { Widgets.read(text) }
                        .onSuccess { fresh = it; Widgets.keep(dir, id, choice, text) }
                        .onFailure { failed(dir, choice.identity, choice.widget, it.message ?: "unreadable") }
                }
            }
        }
        val kept = if (fresh == null && choice != null && hub != null) Widgets.kept(dir, id, choice) else null
        return Widgets.decide(choice, installed, active, SafeMode.active, fresh, kept)
    }

    // Written in its log, on its page in Settings, so its maker sees why its widget didn't change.
    private fun failed(dir: java.io.File, identity: String, widget: String, why: String) {
        ExtLog.init(dir)
        runCatching { ExtLog.add(identity, JSONObject().put("t", System.currentTimeMillis() / 1000).put("part", "widget $widget").put("failed", why)) }
    }

    // A placed widget's size in dp, as the launcher gives it: one for each way the phone is held.
    class Size(val w: Int, val h: Int) {
        // The rows that fit under its title, with a note under them or without.
        fun rows(note: Boolean) = ((h - 24 - 28 - (if (note) 20 else 0)) / ROW_DP).coerceIn(1, 12)
        val narrow get() = w < 180
    }

    private const val ROW_DP = 40

    private fun sizes(mgr: AppWidgetManager, id: Int): List<Size> {
        val o = mgr.getAppWidgetOptions(id)
        if (Build.VERSION.SDK_INT >= 31) {
            @Suppress("DEPRECATION")
            o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.takeIf { it.isNotEmpty() }?.let { l -> return l.map { Size(it.width.toInt(), it.height.toInt()) } }
        }
        val minW = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
        val maxH = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110)
        return listOf(Size(minW, maxH))
    }

    // Android 12 and newer pick the views for each size themselves; before, the one held upright.
    private fun views(app: Context, id: Int, show: Widgets.Show, sizes: List<Size>): RemoteViews =
        if (Build.VERSION.SDK_INT >= 31 && sizes.size > 1) RemoteViews(sizes.associate { SizeF(it.w.toFloat(), it.h.toFloat()) to draw(app, id, show, it) })
        else draw(app, id, show, sizes.first())

    // Kav's colors for a look: the widget's card, its words, the rider's accent and what's drawn on it.
    private class Ink(look: Look, accent: Int) {
        private val p = uk.noammm.kav.ui.palette(look)
        private val light = look == Look.LIGHT
        val card = p.surface.toArgb()
        val text = p.text.toArgb()
        val secondary = p.textSecondary.toArgb()
        val tertiary = p.textTertiary.toArgb()
        val accent = accent
        val accentInk = (if (light) uk.noammm.kav.ui.readable(Color(accent), p.surface) else Color(accent)).toArgb()
        val onAccent = (if (light) p.text else p.bg).toArgb()
    }

    // Sets a color, both of them where the widget follows the phone's dark theme.
    private class Paint(val day: Ink, val night: Ink?) {
        fun set(rv: RemoteViews, view: Int, method: String, role: (Ink) -> Int) {
            if (night != null && Build.VERSION.SDK_INT >= 31) rv.setColorInt(view, method, role(day), role(night))
            else rv.setInt(view, method, role(day))
        }
    }

    private fun paint(app: Context): Paint {
        val accent = Prefs.accent(app)
        if (Prefs.followsSystem(app) && Build.VERSION.SDK_INT >= 31)
            return Paint(Ink(Prefs.lookOf(Prefs.SYSTEM_LOOK, false), accent), Ink(Prefs.lookOf(Prefs.SYSTEM_LOOK, true), accent))
        return Paint(Ink(Prefs.look(app), accent), null)
    }

    private fun draw(app: Context, id: Int, show: Widgets.Show, size: Size): RemoteViews {
        val rv = RemoteViews(app.packageName, R.layout.widget_ext)
        val ink = paint(app)
        rv.setInt(R.id.widget_root, "setLayoutDirection", if (T.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR)
        ink.set(rv, R.id.widget_bg, "setColorFilter") { it.card }
        ink.set(rv, R.id.widget_title, "setTextColor") { it.text }
        ink.set(rv, R.id.widget_message, "setTextColor") { it.secondary }
        ink.set(rv, R.id.widget_note, "setTextColor") { it.tertiary }
        ink.set(rv, R.id.widget_button_bg, "setColorFilter") { it.accent }
        ink.set(rv, R.id.widget_button_text, "setTextColor") { it.onAccent }
        // The launcher may apply these over what the widget showed before, so every part is set, each time.
        rv.removeAllViews(R.id.widget_rows)
        rv.setViewVisibility(R.id.widget_title, View.VISIBLE)
        rv.setViewVisibility(R.id.widget_message, View.GONE)
        rv.setViewVisibility(R.id.widget_button, View.GONE)
        rv.setViewVisibility(R.id.widget_gap, View.VISIBLE)
        rv.setViewVisibility(R.id.widget_note, View.GONE)
        fun message(title: String, text: String, button: String?, tap: PendingIntent?) {
            rv.setTextViewText(R.id.widget_title, title)
            rv.setTextViewText(R.id.widget_message, text)
            rv.setViewVisibility(R.id.widget_message, View.VISIBLE)
            rv.setViewVisibility(R.id.widget_gap, View.GONE)
            rv.setViewVisibility(R.id.widget_note, View.GONE)
            if (button != null) {
                rv.setTextViewText(R.id.widget_button_text, button)
                rv.setViewVisibility(R.id.widget_button, View.VISIBLE)
            }
            tap?.let { rv.setOnClickPendingIntent(R.id.widget_root, it) }
        }
        val kav = "Kav"
        when (show) {
            Widgets.Show.Choose -> message(kav, T("Choose which extension's widget this shows.", "בחרו איזה ווידג׳ט של תוסף יוצג כאן."), T("Choose", "בחירה"), setup(app, id))
            Widgets.Show.Gone -> message(kav, T("The extension this showed was removed.", "התוסף שהוצג כאן הוסר."), T("Choose another", "בחירת ווידג׳ט אחר"), setup(app, id))
            Widgets.Show.SafeMode -> message(kav, T("Kav started without extensions (safe mode).", "‏Kav נפתחה בלי תוספים (מצב בטוח)."), T("Open Kav", "פתיחת Kav"), open(app, id, ROW_WIDGET))
            is Widgets.Show.Off -> message(name(show.name), T("This extension is off.", "התוסף הזה כבוי."), T("Open in Kav", "פתיחה ב-Kav"), open(app, id, ROW_PAGE))
            is Widgets.Show.Waiting -> message(name(show.name), T("Open Kav to load it.", "פתחו את Kav כדי לטעון אותו."), null, open(app, id, ROW_WIDGET))
            is Widgets.Show.Shows -> content(app, rv, id, show.content, size, ink)
        }
        return rv
    }

    private fun name(s: String) = T.iso(s)

    private fun content(app: Context, rv: RemoteViews, id: Int, c: Widgets.Content, size: Size, ink: Paint) {
        rv.setTextViewText(R.id.widget_title, c.title)
        rv.setViewVisibility(R.id.widget_title, if (c.title.isEmpty()) View.GONE else View.VISIBLE)
        rv.setOnClickPendingIntent(R.id.widget_root, tap(app, id, ROW_WIDGET, c.tap) ?: open(app, id, ROW_WIDGET))
        if (c.rows.isEmpty()) {
            rv.setTextViewText(R.id.widget_message, c.empty ?: T("Nothing to show now", "אין מה להציג עכשיו"))
            rv.setViewVisibility(R.id.widget_message, View.VISIBLE)
            rv.setViewVisibility(R.id.widget_gap, View.GONE)
        }
        for ((i, r) in c.rows.take(size.rows(c.note != null)).withIndex()) rv.addView(R.id.widget_rows, row(app, id, i, r, size, ink))
        rv.setTextViewText(R.id.widget_note, c.note.orEmpty())
        rv.setViewVisibility(R.id.widget_note, if (c.note == null) View.GONE else View.VISIBLE)
    }

    private fun row(app: Context, id: Int, index: Int, r: Widgets.Row, size: Size, ink: Paint): RemoteViews {
        val v = RemoteViews(app.packageName, R.layout.widget_ext_row)
        v.setTextViewText(R.id.row_title, r.title)
        ink.set(v, R.id.row_title, "setTextColor") { it.text }
        // Narrow, a row is its title and value alone.
        val subtitle = r.subtitle?.takeUnless { size.narrow }
        v.setTextViewText(R.id.row_subtitle, subtitle.orEmpty())
        v.setViewVisibility(R.id.row_subtitle, if (subtitle == null) View.GONE else View.VISIBLE)
        ink.set(v, R.id.row_subtitle, "setTextColor") { it.secondary }
        v.setTextViewText(R.id.row_value, r.value.orEmpty())
        v.setViewVisibility(R.id.row_value, if (r.value == null) View.GONE else View.VISIBLE)
        ink.set(v, R.id.row_value, "setTextColor") { it.accentInk }
        if (r.line != null) {
            v.setTextViewText(R.id.row_line_text, T.ltr(r.line))
            v.setViewVisibility(R.id.row_line, View.VISIBLE)
            ink.set(v, R.id.row_line_bg, "setColorFilter") { it.accent }
            ink.set(v, R.id.row_line_text, "setTextColor") { it.onAccent }
        } else ICONS[r.icon]?.let { icon ->
            v.setImageViewResource(R.id.row_icon, icon)
            v.setViewVisibility(R.id.row_icon, View.VISIBLE)
            ink.set(v, R.id.row_icon, "setColorFilter") { it.secondary }
        }
        tap(app, id, index, r.tap)?.let { v.setOnClickPendingIntent(R.id.row, it) }
        return v
    }

    // A page's icons (Blocks.ICONS), as drawables.
    private val ICONS = mapOf(
        "star" to R.drawable.widget_icon_star, "heart" to R.drawable.widget_icon_heart, "bus" to R.drawable.widget_icon_bus,
        "train" to R.drawable.widget_icon_train, "walk" to R.drawable.widget_icon_walk, "bike" to R.drawable.widget_icon_bike,
        "clock" to R.drawable.widget_icon_clock, "map" to R.drawable.widget_icon_map, "pin" to R.drawable.widget_icon_pin,
        "info" to R.drawable.widget_icon_info, "warning" to R.drawable.widget_icon_warning, "check" to R.drawable.widget_icon_check,
        "plus" to R.drawable.widget_icon_plus, "edit" to R.drawable.widget_icon_edit, "delete" to R.drawable.widget_icon_delete,
        "search" to R.drawable.widget_icon_search, "list" to R.drawable.widget_icon_list, "home" to R.drawable.widget_icon_home,
        "calendar" to R.drawable.widget_icon_calendar, "settings" to R.drawable.widget_icon_settings,
    )

    // -- Taps ------------------------------------------------------------------------------------------------------------

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // Each part of each widget its own: the widget's id and the row.
    private fun code(id: Int, row: Int) = id * 64 + row + 2

    // What a tap does: a link opens straight from the home screen, a call goes to the extension's code, anything else
    // opens Kav, which reads the action from what the widget shows. Null: the part has no action.
    private fun tap(app: Context, id: Int, row: Int, t: Widgets.Tap?): PendingIntent? = when (t) {
        null -> null
        is Widgets.Tap.Page -> (t.action as? Blocks.Action.Link)?.let { link ->
            PendingIntent.getActivity(app, code(id, row), Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)
        } ?: open(app, id, row)
        is Widgets.Tap.Call -> PendingIntent.getBroadcast(app, code(id, row), Intent(app, providerOf(app, id)).setAction(ACTION_CALL)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra(EXTRA_ROW, row), FLAGS)
        else -> open(app, id, row)
    }

    private fun providerOf(app: Context, id: Int): Class<*> =
        if (AppWidgetManager.getInstance(app).getAppWidgetInfo(id)?.provider?.className == ExtWidgetWide::class.java.name) ExtWidgetWide::class.java else ExtWidgetSmall::class.java

    private fun open(app: Context, id: Int, row: Int): PendingIntent = PendingIntent.getActivity(app, code(id, row),
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_OPEN, id).putExtra(EXTRA_ROW, row), FLAGS)

    private fun setup(app: Context, id: Int): PendingIntent = PendingIntent.getActivity(app, code(id, ROW_PAGE - 1),
        Intent(app, ExtWidgetSetup::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), FLAGS)

    // A widget tap that opened Kav, for its screens to act on (MainActivity): the extension, and the tap's action, or its
    // page in Settings (Settings › Extensions itself when identity is empty).
    class Opened(val identity: String, val tap: Widgets.Tap?, val page: Boolean)

    var pending by mutableStateOf<Opened?>(null)

    // True when the intent is a widget's tap, set in pending.
    fun take(ctx: Context, intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_EXTENSIONS, false) == true) { pending = Opened("", null, true); return true }
        val id = intent?.getIntExtra(EXTRA_OPEN, AppWidgetManager.INVALID_APPWIDGET_ID) ?: return false
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val row = intent.getIntExtra(EXTRA_ROW, ROW_WIDGET)
        val dir = ExtApp.dir(ctx)
        if (!dir.isDirectory) return true
        val choice = Widgets.Placed.of(id) ?: return true
        pending = if (row == ROW_PAGE) Opened(choice.identity, null, true)
            else Opened(choice.identity, Widgets.kept(dir, id, choice)?.let { Widgets.tapOf(it, row) }, false)
        return true
    }

    // A part whose action is a call: told to the extension's code, then the widget asked again.
    fun call(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val row = intent.getIntExtra(EXTRA_ROW, ROW_WIDGET)
        val dir = ExtApp.dir(app)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || !dir.isDirectory) return
        val choice = Widgets.Placed.of(id) ?: return
        val call = Widgets.kept(dir, id, choice)?.let { Widgets.tapOf(it, row) } as? Widgets.Tap.Call ?: return
        ExtApp.awaitStarted(START_WAIT_MS)
        val hub = ExtApp.hub(app)
        val installed = hub.registry.get(choice.identity) ?: return
        if (!hub.active(installed) || SafeMode.active) return update(app, listOf(id), ask = false)
        Native.widgetTap(choice.identity, choice.widget, Widgets.tapArgs(choice.widget, call.name, row).toString(), CALL_MS)
            ?.onFailure { failed(dir, choice.identity, choice.widget, it.message ?: "failed") }
        update(app, Widgets.Placed.showing(choice.identity, choice.widget), ask = true)
    }

    // -- Placing and removing --------------------------------------------------------------------------------------------

    fun choose(ctx: Context, id: Int, identity: String, widget: String) {
        val app = ctx.applicationContext
        Widgets.Placed.set(ExtApp.dir(app), id, identity, widget)
        update(app, listOf(id), ask = true)
    }

    fun deleted(ctx: Context, ids: List<Int>) {
        val dir = ExtApp.dir(ctx.applicationContext)
        if (dir.isDirectory) Widgets.Placed.drop(dir, ids)
    }

    // Add to home screen, from the extension's page: the launcher asks the rider where, and Kav is told the widget it
    // placed (pinned). False when the launcher can't.
    fun pin(ctx: Context, identity: String, widget: Widgets.Decl): Boolean {
        val mgr = AppWidgetManager.getInstance(ctx)
        if (Build.VERSION.SDK_INT < 26 || !mgr.isRequestPinAppWidgetSupported) return false
        val provider = if (widget.size == Widgets.Size.WIDE) ExtWidgetWide::class.java else ExtWidgetSmall::class.java
        val done = Intent(ctx, provider).setAction(ACTION_PINNED).putExtra(EXTRA_IDENTITY, identity).putExtra(EXTRA_WIDGET, widget.id)
        // Mutable: Android adds the placed widget's id to it.
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(ctx, (identity + widget.id).hashCode(), done, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
        return mgr.requestPinAppWidget(ComponentName(ctx, provider), null, callback)
    }

    fun pinned(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val identity = intent.getStringExtra(EXTRA_IDENTITY) ?: return
        val widget = intent.getStringExtra(EXTRA_WIDGET) ?: return
        // Only a widget just placed, with no choice yet: the receiver is open to other apps, which mustn't change one.
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || id !in placed(ctx) || Widgets.Placed.of(id) != null) return
        choose(ctx, id, identity, widget)
    }

    // -- The next time a widget's content changes by itself ------------------------------------------------------------

    // One alarm for all of them, at the soonest a widget's content said it changes (next); RTC, so it waits for the phone
    // to be awake rather than waking it.
    private fun next(app: Context) {
        val dir = ExtApp.dir(app)
        val due = placed(app).mapNotNull { id -> Widgets.Placed.of(id)?.let { Widgets.due(dir, id, it) } }.minOrNull()
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(app, 0, Intent(app, ExtWidgetSmall::class.java).setAction(ACTION_NEXT), FLAGS)
        if (due == null) alarms.cancel(pi)
        // A time already past (Kav was closed then) is asked about in a minute, not at once again and again.
        else alarms.set(AlarmManager.RTC, due.coerceAtLeast(System.currentTimeMillis() + 60_000L), pi)
    }
}
