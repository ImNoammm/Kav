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
import uk.noammm.kav.data.LineAlert
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Schedules
import uk.noammm.kav.data.shownName
import uk.noammm.kav.ui.Shown
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.clockFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date

// The phone's alarm, a restart, an update or a new clock: the leave-now alerts brought up to date and the next wake
// set, off the main thread.
class LeaveNowReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                if (intent.action == LeaveNow.ACT_QUIET) {
                    LeaveNow.quiet(ctx, intent.getLongExtra(LeaveNow.EXTRA_ID, 0), intent.getLongExtra(LeaveNow.EXTRA_UNTIL, 0))
                }
                LeaveNow.run(ctx.applicationContext)
            } finally {
                done.finish()
            }
        }.start()
    }
}

// Leave-now alerts: between the times and on the days a rider chose, one alert the walk's length before the next
// chosen bus or train leaves their stop: leave now and you catch it. A delayed bus moves the moment with it, from
// the server's live times; with no live time the timetable's, marked as such. Each departure alerts once. The wake
// is an inexact alarm, which a sleeping phone may hold back a few minutes. The time logic itself, which departure
// to aim at, when to fire and when to stay quiet, lives in data/LeaveNow on plain values, tested without a phone.
object LeaveNow {
    const val ACT_WAKE = "uk.noammm.kav.LEAVE_NOW"
    const val ACT_QUIET = "uk.noammm.kav.LEAVE_NOW_QUIET"
    const val EXTRA_ID = "alert"
    const val EXTRA_UNTIL = "until"
    // The stop a tapped alert opens on the Stations tab.
    const val EXTRA_STOP = "uk.noammm.kav.STOP"
    private const val CHANNEL = "leave-now"
    private const val TAG = "leave-now"

    // After the alerts change, and when Kav opens (a force stop drops its alarms).
    fun reschedule(ctx: Context) {
        val app = ctx.applicationContext
        Thread { run(app) }.start()
    }

    // Swiped away while it shows: quiet until that bus's time passes.
    fun quiet(ctx: Context, id: Long, until: Long) {
        if (System.currentTimeMillis() / 1000 < until) Prefs.quietAlert(ctx, id, until)
    }

    // The alert's chosen departures still ahead, live from the server when it answers, from the timetable when it
    // does not or knows nothing of the stop.
    private fun departures(ctx: Context, net: Net?, a: LineAlert, now: Long): List<uk.noammm.kav.data.LeaveNow.Dep> {
        runCatching { Prefs.loadSources(ctx) }
        val live = runCatching {
            val board = KavLive.stops(listOf(a.code))
            board.due.filter { it.stop == a.code && (it.expected > 0 || it.aimed > 0) }.map { d ->
                uk.noammm.kav.data.LeaveNow.Dep(d.number, board.lines[d.route]?.to ?: "", d.aimed, d.expected)
            }
        }.getOrNull()
        if (!live.isNullOrEmpty()) return live
        return if (net == null) emptyList() else timetableDeps(net, a, now)
    }

    private fun timetableDeps(net: Net, a: LineAlert, now: Long): List<uk.noammm.kav.data.LeaveNow.Dep> {
        val out = ArrayList<uk.noammm.kav.data.LeaveNow.Dep>()
        val zone = ZoneId.of("Asia/Jerusalem")
        val today = Instant.ofEpochSecond(now).atZone(zone).toLocalDate()
        // Today and tomorrow cover every window the alert can be in, trips after midnight included.
        for (offset in 0L..1L) {
            val day = today.plusDays(offset)
            val base = Schedules.base(day)
            val dow = day.dayOfWeek.value % 7
            for (s in net.code.indices) {
                if (net.code[s] != a.code) continue
                for (i in net.dStart[s] until net.dStart[s + 1]) {
                    val st = net.dConn[i]
                    val trip = net.tripOf(st)
                    if (!net.runsOn(trip, dow)) continue
                    val at = base + net.stDep[st]
                    if (at <= now) continue
                    val route = net.tripRoute[trip]
                    out.add(uk.noammm.kav.data.LeaveNow.Dep(net.rShort[route], net.name[net.tripLast(trip)], at, 0))
                }
            }
        }
        return out
    }

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
        val alerts = Prefs.lineAlerts(ctx).filter { it.leaveMin > 0 }
        if (alerts.isEmpty()) {
            schedule(ctx, null)
            return
        }
        // The next wake first: a run the system cuts short for taking too long must not end the alerts. Outside the
        // alerts' windows that is the next window's start; inside, the next leave moment.
        val net = Loaded.net ?: runBlocking { runCatching { loadNet(ctx) }.getOrNull() }
        val active = alerts.filter { it.window(now) != null }
        class Target(val a: LineAlert, val dep: uk.noammm.kav.data.LeaveNow.Dep)
        val targets = active.mapNotNull { a ->
            uk.noammm.kav.data.LeaveNow.target(departures(ctx, net, a, now), a.lines.toSet(), a.toward.map { it.name }.toSet(), now)
                ?.let { Target(a, it) }
        }
        val plan = uk.noammm.kav.data.LeaveNow.plan(
            targets.map {
                uk.noammm.kav.data.LeaveNow.State(
                    it.a.id, uk.noammm.kav.data.LeaveNow.leaveAt(it.dep, it.a.leaveMin),
                    uk.noammm.kav.data.LeaveNow.depAt(it.dep), Prefs.alertQuietUntil(ctx, it.a.id),
                )
            }, now,
        )
        val nextWindow = alerts.mapNotNull { it.nextStart(now) }.minOrNull()
        schedule(ctx, listOfNotNull(plan.wakeAt, nextWindow).minOrNull())
        val firing = targets.filter { it.a.id in plan.fire }
        // Deleted, finished or swiped away.
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications }.getOrNull().orEmpty()
            .filter { it.tag == TAG && firing.none { t -> notificationId(t.a) == it.id } }
            .forEach { nm.cancel(TAG, it.id) }
        for (t in firing) {
            // Fired: quiet until this bus's time passes, so a rerun, a boot or an update does not alert for it twice.
            Prefs.quietAlert(ctx, t.a.id, uk.noammm.kav.data.LeaveNow.depAt(t.dep))
            post(ctx, t.a, t.dep, now)
        }
    }

    private fun notificationId(a: LineAlert) = a.id.hashCode()

    private fun post(ctx: Context, a: LineAlert, dep: uk.noammm.kav.data.LeaveNow.Dep, now: Long) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(T("Leave-now alerts", "התראות ״צאו עכשיו״"))
                .setDescription(T(
                    "When to leave for the next bus or train you chose, by the walk to the stop",
                    "מתי לצאת לאוטובוס או לרכבת הבאים שבחרתם, לפי ההליכה לתחנה",
                ))
                .build(),
        )
        val clock = clockFormat()
        val at = uk.noammm.kav.data.LeaveNow.depAt(dep)
        val label = if (a.toward.isNotEmpty()) shownName(dep.endName) else dep.line
        // The walk minutes say where they came from: measured ones carry when and from where, since the rider may
        // be somewhere else by now; chosen ones say they were chosen.
        val walk = if (a.leaveSrc == 1) T(
            "The walk was measured at ${a.leaveMin} minutes from your location when the alert was set.",
            "ההליכה נמדדה כ-${a.leaveMin} דקות מהמיקום שלכם כשההתראה נקבעה.",
        ) else T(
            "You set the walk at ${a.leaveMin} minutes.",
            "ההליכה נקבעה על ידכם ל-${a.leaveMin} דקות.",
        )
        // A live time says so; the timetable's says so.
        val text = if (dep.expected > 0) T(
            "Live: $label is expected at ${clock.format(Date(at * 1000))}. $walk",
            "זמן אמת: $label צפוי ב-${clock.format(Date(at * 1000))}. $walk",
        ) else T(
            "By the timetable $label leaves at ${clock.format(Date(at * 1000))}. $walk No live time for it.",
            "לפי לוח הזמנים $label יוצא ב-${clock.format(Date(at * 1000))}. $walk אין זמן אמת עבורו.",
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
            Intent(ctx, LeaveNowReceiver::class.java).setAction(ACT_QUIET).putExtra(EXTRA_ID, a.id).putExtra(EXTRA_UNTIL, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(T("Leave now: $lines at ${a.name}", "צאו עכשיו: $lines ב${a.name}"))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(Prefs.accent(ctx))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // Gone once the bus's time has passed even if the phone holds the next wake back.
            .setTimeoutAfter((at - now).coerceAtLeast(1) * 1000)
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
            ctx, 0, Intent(ctx, LeaveNowReceiver::class.java).setAction(ACT_WAKE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (at == null) am.cancel(pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pi)
    }
}
