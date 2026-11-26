package uk.noammm.kav

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.LastRide
import uk.noammm.kav.data.LineAlert
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.shownName
import uk.noammm.kav.ui.Shown
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.clockFormat
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.util.Date

// The phone's alarm, a restart, an update or a new clock: the last-bus warnings brought up to date and the next wake
// set, off the main thread.
class LastBusReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                if (intent.action == LastBus.ACT_QUIET) {
                    LastBus.quiet(ctx, intent.getLongExtra(LastBus.EXTRA_ID, 0), intent.getLongExtra(LastBus.EXTRA_UNTIL, 0))
                }
                LastBus.run(ctx.applicationContext)
            } finally {
                done.finish()
            }
        }.start()
    }
}

// Last bus warnings: on the days a rider chose, one warning their chosen time before the day's last departure of
// their lines at their stop, far enough ahead to still reach it. When Kav's server has a live time for that last
// bus the warning says so, and says when it only knows the timetable's. The wake is an inexact alarm, which a
// sleeping phone may hold back a few minutes. The time logic itself, which day a departure belongs to, when to
// fire and when to stay quiet, lives in data/LastRide on plain values, tested without a phone.
object LastBus {
    const val ACT_WAKE = "uk.noammm.kav.LAST_BUS"
    const val ACT_QUIET = "uk.noammm.kav.LAST_BUS_QUIET"
    const val EXTRA_ID = "alert"
    const val EXTRA_UNTIL = "until"
    // The stop a tapped warning opens on the Stations tab.
    const val EXTRA_STOP = "uk.noammm.kav.STOP"
    private const val CHANNEL = "last-bus"
    private const val TAG = "last-bus"
    // The warning stays until this long after the departure, for a phone the alarm reached late.
    private const val GRACE = 10 * 60L
    // A live time this close to the timetable's last departure is taken to be that bus.
    private const val LIVE_MATCH = 30 * 60L

    // After the alerts change, and when Kav opens (a force stop drops its alarms).
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        Thread { run(app) }.start()
    }

    // Swiped away while it shows: quiet until the bus's time passes.
    fun quiet(ctx: Context, id: Long, until: Long) {
        if (System.currentTimeMillis() / 1000 < until) Prefs.quietAlert(ctx, id, until)
    }

    // Every scheduled departure at the alert's stop, as plain rows for the time logic.
    private fun rows(net: Net, a: LineAlert): List<LastRide.Row> {
        val out = ArrayList<LastRide.Row>()
        for (s in net.code.indices) {
            if (net.code[s] != a.code) continue
            for (i in net.dStart[s] until net.dStart[s + 1]) {
                val st = net.dConn[i]
                val trip = net.tripOf(st)
                val route = net.tripRoute[trip]
                var days = 0
                for (d in 0..6) if (net.runsOn(trip, d)) days = days or (1 shl d)
                out.add(LastRide.Row(net.rShort[route], net.name[net.tripLast(trip)], days, net.stDep[st].toLong()))
            }
        }
        return out
    }

    // The day's last departure of the alert's lines (or train ends) at its stop, epoch seconds; null when none runs.
    private fun lastDeparture(rows: List<LastRide.Row>, a: LineAlert, day: LocalDate): Long? =
        LastRide.lastDeparture(rows, a.lines.toSet(), a.toward.map { it.name }.toSet(), day.dayOfWeek.value % 7, Schedules.base(day))

    @Synchronized fun run(ctx: Context) {
        T.lang = Prefs.lang(ctx)
        Shown.twelveHour = Prefs.twelveHour(ctx)
        val now = System.currentTimeMillis() / 1000
        val nm = NotificationManagerCompat.from(ctx)
        // With notifications off, nothing to wake for: Kav sets the alarm again when it's let post them.
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL)?.importance == NotificationManagerCompat.IMPORTANCE_NONE) {
            schedule(ctx, null)
            return
        }
        val alerts = Prefs.lineAlerts(ctx).filter { it.lastRide }
        if (alerts.isEmpty()) {
            schedule(ctx, null)
            return
        }
        val net = Loaded.net ?: runBlocking { runCatching { loadNet(ctx) }.getOrNull() }
        if (net == null) {
            // The timetable could not be read: try again in an hour rather than dropping the warnings.
            schedule(ctx, now + 3600)
            return
        }
        val zone = ZoneId.of("Asia/Jerusalem")
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        // Each alert's next last departure, today or the first day it runs on after it.
        class Due(val a: LineAlert, val at: Long)
        val dues = alerts.mapNotNull { a ->
            val rows = rows(net, a)
            (0L..7L).firstNotNullOfOrNull { d ->
                val day = today.plusDays(d)
                if (a.days and (1 shl (day.dayOfWeek.value % 7)) == 0) null
                else lastDeparture(rows, a, day)?.let { Due(a, it) }
            }
        }
        // Which to fire and when to wake, from the tested time logic; the wake first, so a run the system cuts
        // short for taking too long has already set the next one.
        val plan = LastRide.plan(
            dues.map { LastRide.State(it.a.id, it.at, it.a.warnMin, Prefs.alertQuietUntil(ctx, it.a.id)) }, now, GRACE,
        )
        schedule(ctx, plan.wakeAt)
        val due = dues.filter { it.a.id in plan.fire }
        // Deleted, finished or swiped away.
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications }.getOrNull().orEmpty()
            .filter { it.tag == TAG && due.none { d -> notificationId(d.a) == it.id } }
            .forEach { nm.cancel(TAG, it.id) }
        for (d in due) {
            // Fired: quiet until its bus's time passes, so a rerun, a boot or an update today does not warn twice.
            Prefs.quietAlert(ctx, d.a.id, d.at + GRACE)
            post(ctx, d.a, d.at, now)
        }
    }

    private fun notificationId(a: LineAlert) = a.id.hashCode()

    private fun post(ctx: Context, a: LineAlert, at: Long, now: Long) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(T("Last bus warnings", "אזהרות אוטובוס אחרון"))
                .setDescription(T(
                    "A warning before the day's last ride of the lines you chose, on the days you chose",
                    "אזהרה לפני הנסיעה האחרונה של היום בקווים שבחרתם, בימים שבחרתם",
                ))
                .build(),
        )
        val clock = clockFormat()
        // Live when the server knows this bus, marked as live; the timetable's time otherwise, marked as such.
        val live = runCatching {
            Prefs.loadSources(ctx)
            val board = KavLive.stops(listOf(a.code))
            val arrivals = board.due.filter { it.stop == a.code && it.expected > 0 }.map { d ->
                LastRide.Live(d.number, board.lines[d.route]?.to ?: "", d.expected)
            }
            LastRide.liveLast(arrivals, a.lines.toSet(), a.toward.map { it.name }.toSet(), at, LIVE_MATCH)
        }.getOrNull()
        val time = clock.format(Date(at * 1000))
        val text = if (live != null) T(
            "Live: the last one is expected at ${clock.format(Date(live * 1000))} (timetable: $time)",
            "זמן אמת: האחרון צפוי ב-${clock.format(Date(live * 1000))} (לוח הזמנים: $time)",
        ) else T(
            "By the timetable the last one leaves at $time. No live time for it.",
            "לפי לוח הזמנים האחרון יוצא ב-$time. אין זמן אמת עבורו.",
        )
        val lines = if (a.toward.isNotEmpty()) T("Trains to ", "רכבות אל ") + a.toward.joinToString(", ") { shownName(it.name) }
            else a.lines.joinToString(", ")
        val open = PendingIntent.getActivity(
            ctx, notificationId(a),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_STOP, a.stop).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val swiped = PendingIntent.getBroadcast(
            ctx, notificationId(a),
            Intent(ctx, LastBusReceiver::class.java).setAction(ACT_QUIET).putExtra(EXTRA_ID, a.id).putExtra(EXTRA_UNTIL, at + GRACE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(T("Last ride: $lines at ${a.name}", "נסיעה אחרונה: $lines ב${a.name}"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(Prefs.accent(ctx))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Gone once the bus's time has passed even if the phone holds the next wake back.
            .setTimeoutAfter((at + GRACE - now).coerceAtLeast(1) * 1000)
            .setContentIntent(open)
            .setDeleteIntent(swiped)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try { nm.notify(TAG, notificationId(a), n) } catch (_: SecurityException) {}
    }

    private fun schedule(ctx: Context, at: Long?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, LastBusReceiver::class.java).setAction(ACT_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pi)
    }
}
