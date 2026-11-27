package uk.noammm.kav

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import uk.noammm.kav.ui.GET_OFF_LIFE_MS
import uk.noammm.kav.ui.GetOffEvent
import uk.noammm.kav.ui.GetOffState
import uk.noammm.kav.ui.GetOffWatch
import uk.noammm.kav.ui.T

// "Tell me when to get off here": watches the phone's location while the rider is on the way, says (and posts) "get off at
// the next stop" at the stop before the chosen one and "you're here" at the chosen one, and ends itself there, when the
// rider cancels it, or after three hours. Nothing is kept: if the phone ends the service, the alert is gone.
class GetOffService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var tracking: (() -> Unit)? = null
    private var watch: GetOffWatch? = null
    private var stopName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACT_CANCEL) {
            finish()
            return START_NOT_STICKY
        }
        val w = pending
        pending = null
        if (w == null) {
            // Started with nothing to watch (the service restarted): a notice is still owed to startForeground.
            startOngoing()
            finish()
            return START_NOT_STICKY
        }
        watch = w
        stopName = w.stops[w.target].name
        startOngoing()
        Speaker.warm(this)
        tracking?.invoke()
        tracking = trackLocation(this, ::onFix, ::onFix, every = 2000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ finish() }, GET_OFF_LIFE_MS)
        return START_NOT_STICKY
    }

    private fun startOngoing() {
        ensureChannels(this)
        val n = NotificationCompat.Builder(this, WATCH)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(T("Watching for your stop", "עוקב אחרי התחנה שלכם"))
            .setContentText(if (stopName.isEmpty()) "" else T("Getting off at $stopName", "יורדים ב$stopName"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(this))
            .addAction(0, T("Cancel", "ביטול"), PendingIntent.getService(
                this, 1, Intent(this, GetOffService::class.java).setAction(ACT_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .build()
        val types = if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, ONGOING_ID, n, types)
    }

    private fun onFix(loc: Location) {
        val w = watch ?: return
        val speed = if (loc.hasSpeed()) loc.speed else 0f
        val acc = if (loc.hasAccuracy()) loc.accuracy else 100f
        when (w.onFix(loc.latitude, loc.longitude, acc, speed)) {
            GetOffEvent.NEXT -> announce(NEXT_ID, NEXT, T("Get off at the next stop", "רדו בתחנה הבאה"), stopName)
            GetOffEvent.ARRIVED -> {
                announce(ARRIVE_ID, ARRIVE, T("You've arrived", "הגעתם לתחנה"), stopName)
                finish()
            }
            GetOffEvent.NONE -> {}
        }
    }

    // The push (with its sound and buzz) and the same words said aloud, over the music like a navigation app.
    private fun announce(id: Int, channel: String, title: String, text: String) {
        val n = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(this))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(id, n) }
        // Without a text-to-speech voice for the language, the push and its sound are what the rider gets.
        if (Speaker.mayTalk(this)) Speaker.sayAfterSound(this, title)
    }

    private fun finish() {
        handler.removeCallbacksAndMessages(null)
        tracking?.invoke()
        tracking = null
        watch = null
        GetOffState.armed = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tracking?.invoke()
        tracking = null
        GetOffState.armed = null
        super.onDestroy()
    }

    companion object {
        private const val ACT_CANCEL = "uk.noammm.kav.GET_OFF_CANCEL"
        private const val WATCH = "get-off-watch"
        private const val NEXT = "get-off-next"
        private const val ARRIVE = "get-off-arrive"
        private const val ONGOING_ID = 140
        private const val NEXT_ID = 141
        private const val ARRIVE_ID = 142
        private val BUZZ = longArrayOf(0, 400, 200, 400)
        private val BUZZ_STRONG = longArrayOf(0, 700, 250, 700, 250, 900)

        // The watch handed to the service started right after; held here only until it picks it up.
        @Volatile private var pending: GetOffWatch? = null

        fun arm(ctx: Context, w: GetOffWatch, key: String) {
            pending = w
            GetOffState.armed = key
            val ok = runCatching { ctx.startForegroundService(Intent(ctx, GetOffService::class.java)) }.isSuccess
            if (!ok) { pending = null; GetOffState.armed = null }
        }

        fun cancel(ctx: Context) {
            GetOffState.armed = null
            pending = null
            ctx.stopService(Intent(ctx, GetOffService::class.java))
        }

        private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun ensureChannels(ctx: Context) {
            val nm = NotificationManagerCompat.from(ctx)
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(WATCH, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(T("Get-off alert running", "התראת ירידה פעילה"))
                    .build(),
            )
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(NEXT, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(T("Get off at the next stop", "רדו בתחנה הבאה"))
                    .setVibrationEnabled(true)
                    .setVibrationPattern(BUZZ)
                    .build(),
            )
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(ARRIVE, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(T("Get off now", "הגעתם לתחנה"))
                    .setVibrationEnabled(true)
                    .setVibrationPattern(BUZZ_STRONG)
                    .build(),
            )
        }
    }
}
