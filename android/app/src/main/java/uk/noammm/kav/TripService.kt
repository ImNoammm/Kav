package uk.noammm.kav

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import uk.noammm.kav.data.JourneyFile
import uk.noammm.kav.ui.Fix
import uk.noammm.kav.ui.T
import uk.noammm.kav.ui.buildSteps
import uk.noammm.kav.ui.journeyProgress
import uk.noammm.kav.ui.arrivedAtEnd
import uk.noammm.kav.ui.ARRIVED_END_S
import androidx.core.location.LocationRequestCompat
import uk.noammm.kav.ui.Step
import uk.noammm.kav.ui.boardingChoice
import uk.noammm.kav.ui.replanAlert
import uk.noammm.kav.ui.firedAfterSwitch
import uk.noammm.kav.data.Transit
import uk.noammm.kav.ui.RavPass

class TripService : Service() {

    private var foreground = false
    private var tracking: (() -> Unit)? = null
    private var fix: Fix? = null
    // Too rough for the notice, but the trip's steps allow for how rough a fix is.
    private var rough: Fix? = null
    private var shown: TripNotice? = null
    private var shownPay: TripPay? = null
    private var postedAt = 0L

    private var cached: Pair<ActiveJourney, Int>? = null
    private var cachedAt = -1L

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            alertIfDue()
            advance()
            retune()
            post()
            handler.postDelayed(this, TICK_MS)
        }
    }
    private val settle = Runnable { post() }

    private val fired = HashSet<String>()
    private var firedFor = ""
    // The trip's legs as the alerts last saw them, and the last alert given, to tell a trip gone on with a new route.
    private var alertedLegs: List<Transit.Leg>? = null
    private var lastAlert = ""
    private val watch = object : Runnable {
        override fun run() {
            alertIfDue()
            handler.postDelayed(this, ALERT_MS)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        T.lang = Prefs.lang(this)
        uk.noammm.kav.ui.Shown.twelveHour = Prefs.twelveHour(this)
        ensureChannel(this)
        if (Prefs.spokenAlerts(this) || Prefs.spokenTurns(this)) Speaker.warm(this)
        running = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_END) { end(); return START_NOT_STICKY }
        if (intent?.action == ACT_REPOST) shown = null
        if (!foreground && !goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (journey() == null) { finish(); return START_NOT_STICKY }
        post(force = true)
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MS)
        handler.removeCallbacks(watch)
        handler.post(watch)
        return START_STICKY
    }

    override fun onDestroy() {
        Speaker.shutdown()
        handler.removeCallbacksAndMessages(null)
        tracking?.invoke()
        tracking = null
        if (running === this) running = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        PayEntry.sync(this)
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val first = notice()?.let { build(this, it) } ?: NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(getString(R.string.app_name))
            .build()
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val located = hasLocationPermission(this) &&
            start(first, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (!located && !start(first, special)) return false
        foreground = true
        PayEntry.sync(this)
        if (located) tracking = trackLocation(this, ::onFix, ::onRough, every = need.every, quality = need.quality)
        return true
    }

    // Location granted after the trip started.
    private fun track() {
        if (tracking != null || !foreground || !hasLocationPermission(this)) return
        val n = notice()?.let { build(this, it) } ?: return
        val special = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (start(n, special or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)) {
            tracking = trackLocation(this, ::onFix, ::onRough, every = need.every, quality = need.quality)
        }
    }

    private fun start(n: Notification, types: Int) = try {
        ServiceCompat.startForeground(this, ID, n, types)
        true
    } catch (e: RuntimeException) {
        false
    }

    private fun onFix(location: Location) {
        val f = Fix(location.latitude, location.longitude, location.fixTime(), location.speed, location.fixAccuracy())
        fix = f
        // The watch timer stops while the screen sleeps, and the fix can carry the trip past the last stop's
        // window before it runs again: every fix checks first, so "get off" isn't skipped.
        alertIfDue()
        val shell = TripBridge.fix
        if (shell != null) shell(f) else advance()
        post()
    }

    private fun onRough(location: Location) {
        val f = Fix(location.latitude, location.longitude, location.fixTime(), location.speed, location.fixAccuracy())
        rough = f
        alertIfDue()
        val shell = TripBridge.fix
        if (shell != null) shell(f) else advance()
        post()
    }

    // How closely the trip needs the rider's position: every second while walking and in the minutes before boarding
    // and getting off; once a minute while the ride is still a few minutes off; every two minutes on board far from
    // the stop, where the bus's own position tells the trip where it is.
    private enum class Need(val every: Long, val quality: Int) {
        CLOSE(1_000L, LocationRequestCompat.QUALITY_HIGH_ACCURACY),
        WAITING(60_000L, LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY),
        RIDING(120_000L, LocationRequestCompat.QUALITY_LOW_POWER),
    }
    private var need = Need.CLOSE

    private fun needNow(): Need {
        val (journey, step) = journey() ?: return Need.CLOSE
        val now = System.currentTimeMillis() / 1000
        return when (val s = buildSteps(journey.trip, journey.fromLabel, journey.toLabel).getOrNull(step)) {
            is Step.Wait -> {
                val (ride, wait) = boardingChoice(s.ride, s.wait, journey.chosen[s.legIndex] ?: 0)
                val due = journey.resolved.departures(ride, wait, now).map { it.timeUtc }.filter { it >= now - 60 }.minOrNull() ?: ride.dep
                if (due - now > 180) Need.WAITING else Need.CLOSE
            }
            is Step.Ride -> {
                val ride = boardingChoice(s.ride, s.wait, journey.chosen[s.legIndex] ?: 0).first
                if (ride.arr - now > 480) Need.RIDING else Need.CLOSE
            }
            else -> Need.CLOSE
        }
    }

    // Asks for the position as often as the step needs, whenever that changes.
    private fun retune() {
        val wanted = needNow()
        if (wanted == need) return
        need = wanted
        val stop = tracking ?: return
        stop()
        tracking = trackLocation(this, ::onFix, ::onRough, every = need.every, quality = need.quality)
    }

    private fun journey(): Pair<ActiveJourney, Int>? {
        TripBridge.journey?.let { return it() }
        val at = JourneyFile.mtime(this)
        if (at != cachedAt) { cached = JourneyFile.load(this); cachedAt = at }
        cached?.let { (journey, _) ->
            if (System.currentTimeMillis() / 1000 > journey.trip.arr + JourneyFile.KEEP_S) {
                JourneyFile.clear(this)
                cached = null
                cachedAt = -1L
            }
        }
        return cached
    }

    // Kept on disk so a restarted service doesn't repeat them.
    private fun alertIfDue() {
        val (journey, step) = journey() ?: return
        val trip = "${journey.trip.guid}:${journey.trip.dep}"
        val sent = getSharedPreferences("trip-alerts", MODE_PRIVATE)
        if (trip != firedFor) {
            firedFor = trip
            fired.clear()
            sent.getString(trip, null)?.let { fired.addAll(it.split(',')) }
        } else alertedLegs?.takeIf { it !== journey.trip.legs && it != journey.trip.legs }?.let { was ->
            // Gone on with a new route under its own name (Replan): the alert of the failure behind it leaves the shade.
            val kept = firedAfterSwitch(fired, was, journey.trip.legs)
            fired.clear(); fired.addAll(kept)
            sent.edit().clear().putString(trip, fired.joinToString(",")).apply()
            if (lastAlert.startsWith("replan-")) NotificationManagerCompat.from(this).cancel(ALERT_ID)
        }
        alertedLegs = journey.trip.legs
        val latest = listOfNotNull(fix, TripBridge.fixNow?.invoke()).maxByOrNull { it.at }
        val now = System.currentTimeMillis() / 1000
        // The leave alert leaves the shade once the walk is on its way past the first stop, or the bus has gone.
        if (lastAlert.startsWith("leave-") && !leaveStillDue(journey, step, now)) {
            NotificationManagerCompat.from(this).cancel(ALERT_ID)
            lastAlert = ""
        }
        val due = listOfNotNull(
            replanAlert(journey, step, latest, now),
            leaveAlert(journey, step, latest, now, fired),
            tripAlert(journey, step, latest, now),
            transferAlert(journey, step, TicketStore.kept, now * 1000),
        ).firstOrNull { it.key !in fired } ?: return
        fired.add(due.key)
        lastAlert = due.key
        sent.edit().clear().putString(trip, fired.joinToString(",")).apply()
        // Getting off a train with its entrance still open in Kav: the exit ticket, one tap away.
        val exit = due.key.startsWith("ride-") && PayEntry.chosen(this) && exitTicketDue(journey, step, TicketStore.kept, now * 1000)
        alert(this, due.title, if (exit) due.text + T(" · Make your exit ticket", " · צרו את כרטיס היציאה") else due.text,
            if (exit) T("Exit ticket", "כרטיס יציאה") to PayEntry.pay(this, RavPass.Section.TRAIN) else null)
        val spoken = if (due.turn) Prefs.spokenTurns(this) else Prefs.spokenAlerts(this)
        if (spoken && Speaker.mayTalk(this)) Speaker.sayAfterSound(this, due.spoken)
    }

    private var arrivedAt = 0L

    private fun advance() {
        if (TripBridge.journey != null) return
        val (journey, step) = journey() ?: return
        val steps = buildSteps(journey.trip, journey.fromLabel, journey.toLabel)
        val now = System.currentTimeMillis() / 1000
        val latest = listOfNotNull(fix, rough).maxByOrNull { it.at }
        var moved = journeyProgress(
            steps, step, journey.resolved, journey.chosen, now,
            latest, canLocate(this), Prefs.beginTripsImmediately(this),
            journey.fromHere,
        )
        // There, as in the app: the trip shows it's arrived, and ends a little later.
        if (arrivedAtEnd(steps, moved, journey.resolved, journey.chosen, now, latest, journey.trip.arr)) {
            if (arrivedAt == 0L) arrivedAt = now
            else if (now - arrivedAt >= ARRIVED_END_S) { arrivedAt = 0L; end(); return }
            moved = steps.lastIndex
        } else arrivedAt = 0L
        if (moved == step) return
        JourneyFile.save(this, journey, moved)
        cached = journey to moved
        cachedAt = JourneyFile.mtime(this)
    }

    // The Pay or Show ticket the notification offers now, with paying turned on (set up or not: PayEntry.chosen).
    private fun payNow(): TripPay? {
        if (!PayEntry.chosen(this)) return null
        val (journey, step) = journey() ?: return null
        TicketStore.load(this)
        return tripPay(journey, step, TicketStore.kept, System.currentTimeMillis())
    }

    private fun notice(): TripNotice? {
        val (journey, step) = journey() ?: return null
        val latest = listOfNotNull(fix, TripBridge.fixNow?.invoke()).maxByOrNull { it.at }
        return tripNotice(journey, step, latest, System.currentTimeMillis() / 1000, Prefs.accent(this))
    }

    private fun post(force: Boolean = false) {
        if (!foreground) return
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - postedAt)
        if (!force && wait > 0) {
            handler.removeCallbacks(settle)
            handler.postDelayed(settle, wait)
            return
        }
        val n = notice() ?: run { finish(); return }
        val pay = runCatching { payNow() }.getOrNull()
        if (n == shown && pay == shownPay) return
        shown = n
        shownPay = pay
        postedAt = System.currentTimeMillis()
        try { NotificationManagerCompat.from(this).notify(ID, build(this, n, pay)) } catch (_: SecurityException) {}
    }

    private fun end() {
        TripBridge.end?.invoke()
        JourneyFile.clear(this)
        finish()
    }

    private fun finish() {
        NotificationManagerCompat.from(this).cancel(ALERT_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ID = 7
        private const val ALERT_ID = 8
        private const val CHANNEL = "navigation"
        private const val ALERTS = "trip-alerts"
        private val BUZZ = longArrayOf(0, 300, 150, 300)
        private const val ACT_END = "uk.noammm.kav.trip.END"
        private const val ACT_REPOST = "uk.noammm.kav.trip.REPOST"
        private const val PROMOTED = "android.requestPromotedOngoing"
        private const val TICK_MS = 15_000L
        private const val MIN_GAP_MS = 2_000L
        private const val ALERT_MS = 5_000L

        private var running: TripService? = null
        val showing get() = running != null

        fun show(ctx: Context) {
            running?.let { it.track(); it.post(); return }
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            runCatching { ctx.startForegroundService(Intent(ctx, TripService::class.java)) }
        }

        fun repost() { running?.post() }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripService::class.java))
            NotificationManagerCompat.from(ctx).cancel(ALERT_ID)
        }

        fun alert(ctx: Context, title: String, text: String, action: Pair<String, Intent>? = null) {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            ensureAlerts(ctx)
            val n = NotificationCompat.Builder(ctx, ALERTS)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(title)
                .setContentText(text)
                .setColor(Prefs.accent(ctx))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .apply { action?.let { (label, i) -> addAction(0, label, PayEntry.pending(ctx, i, 4)) } }
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(ALERT_ID, n) }
        }

        // Get off, change, a transfer running out: a channel of their own that buzzes, apart from the trip's notice,
        // which changes every few seconds and can have the phone quiet the alerts posted beside it.
        private fun ensureAlerts(ctx: Context) {
            NotificationManagerCompat.from(ctx).createNotificationChannel(
                NotificationChannelCompat.Builder(ALERTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(T("Trip alerts", "התראות נסיעה"))
                    .setDescription(T(
                        "When to get off or change, and when a free transfer is about to end",
                        "מתי לרדת או להחליף, ומתי מעבר חינם עומד להסתיים",
                    ))
                    .setVibrationEnabled(true)
                    .setVibrationPattern(BUZZ)
                    .build(),
            )
        }

        private fun ensureChannel(ctx: Context) {
            val nm = NotificationManagerCompat.from(ctx)
            nm.deleteNotificationChannel("trip")
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(T("Live navigation", "ניווט חי"))
                    .setDescription(T(
                        "Step-by-step guidance while a trip is on",
                        "הנחיה צעד־אחר־צעד בזמן נסיעה",
                    ))
                    .build(),
            )
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun act(ctx: Context, action: String): PendingIntent = PendingIntent.getService(
            ctx, action.hashCode(), Intent(ctx, TripService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun build(ctx: Context, n: TripNotice, pay: TripPay? = null): Notification {
            val accent = Prefs.accent(ctx)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) live(ctx, n, accent, pay) else compat(ctx, n, accent, pay)
        }

        // "Pay" or "Show ticket", through Kav's own screen (PayEntry).
        private fun payAction(ctx: Context, pay: TripPay): Pair<String, PendingIntent> =
            if (pay.ticket != null) T("Show ticket", "הצגת הכרטיס") to PayEntry.pending(ctx, PayEntry.ticket(ctx, pay.ticket), 5)
            else T("Pay", "תשלום") to PayEntry.pending(ctx, PayEntry.pay(ctx, pay.section, pay.ride), 5)

        @RequiresApi(Build.VERSION_CODES.BAKLAVA)
        private fun live(ctx: Context, n: TripNotice, accent: Int, pay: TripPay?): Notification {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressSegments(n.parts.map { (length, colour) -> Notification.ProgressStyle.Segment(length).setColor(colour) })
                .setProgress(n.progress)
                .setProgressTrackerIcon(Icon.createWithBitmap(trackerIcon(n.glyph, n.tint)))
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setSubText(n.arrive)
                .setLargeIcon(Icon.createWithBitmap(plateIcon(n.glyph, n.tint)))
                .setStyle(style)
                .setShortCriticalText(n.chip)
                .apply { pay?.let { payAction(ctx, it) }?.let { (label, pi) -> addAction(Notification.Action.Builder(null as Icon?, label, pi).build()) } }
                .addAction(Notification.Action.Builder(null as Icon?, T("End trip", "סיום נסיעה"), act(ctx, ACT_END)).build())
                .setContentIntent(openApp(ctx))
                .setDeleteIntent(act(ctx, ACT_REPOST))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(accent)
                .setCategory(Notification.CATEGORY_NAVIGATION)
                // The next step at a glance on the lock screen, without unlocking.
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addExtras(Bundle().apply { putBoolean(PROMOTED, true) })
                .build()
        }

        private fun compat(ctx: Context, n: TripNotice, accent: Int, pay: TripPay?): Notification =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip_notice)
                .setContentTitle(n.title)
                .setContentText(n.text)
                .setSubText(n.arrive)
                .setLargeIcon(plateIcon(n.glyph, n.tint))
                .setProgress(n.max, n.progress, false)
                .apply { pay?.let { payAction(ctx, it) }?.let { (label, pi) -> addAction(0, label, pi) } }
                .addAction(0, T("End trip", "סיום נסיעה"), act(ctx, ACT_END))
                .setContentIntent(openApp(ctx))
                .setDeleteIntent(act(ctx, ACT_REPOST))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setColor(accent)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
    }
}

object TripBridge {
    @Volatile var end: (() -> Unit)? = null
    @Volatile var journey: (() -> Pair<ActiveJourney, Int>?)? = null
    @Volatile var fix: ((Fix) -> Unit)? = null
    @Volatile var fixNow: (() -> Fix?)? = null
}
