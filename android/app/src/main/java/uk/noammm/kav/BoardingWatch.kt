package uk.noammm.kav

import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking
import uk.noammm.kav.data.Boarding
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.stopKey
import uk.noammm.kav.ui.T

// Boarding detection (#116): while Kav's map already follows the phone, each moving fix is compared with the buses
// Kav's server sees around it. The same bus near on three moving fixes in a row brings one suggestion, "looks like
// you're on line X", whose tap opens its nearest stop's board; starting a track from there stays the rider's tap.
// The watch never runs in the background and asks no new permission: it only sees the fixes Kav already takes
// while on screen. The decision logic itself is data/Boarding on plain values, tested without a phone.
object BoardingWatch {
    private const val CHANNEL = "boarding"
    private const val TAG = "boarding"
    // A bus this close to the fix is "near": under a bus length's GPS drift.
    private const val RADIUS_M = 60.0
    // How many moving fixes in a row must see the same bus before suggesting.
    private const val NEEDED = 3
    // A newest fix older than this decides nothing.
    private const val MAX_AGE_SECS = 90L
    // Faster than walking or a town bicycle: about 15 km/h.
    private const val MIN_MPS = 4.2
    // At most one area call to the server per this many milliseconds.
    private const val FETCH_EVERY_MS = 20_000L
    // How far around the fix to ask the server about.
    private const val BOX_KM = 0.4
    // A suggested bus stays quiet this long, so one ride suggests once.
    private const val QUIET_SECS = 30 * 60L
    private const val MAX_SAMPLES = 8

    private val samples = ArrayDeque<Boarding.Sample>()
    private val lines = HashMap<String, String>()
    private var prev: Boarding.Fix? = null
    private var lastFetchMs = 0L

    // A fix from Kav's own location tracking, on the main thread; the work moves off it.
    fun onFix(ctx: Context, location: Location) {
        val app = ctx.applicationContext
        val fix = Boarding.Fix(
            location.latitude, location.longitude, location.fixTime(),
            if (location.hasSpeed()) location.speed else -1f,
        )
        Thread { sample(app, fix) }.start()
    }

    @Synchronized private fun sample(ctx: Context, fix: Boarding.Fix) {
        val moving = Boarding.moving(prev, fix, MIN_MPS)
        prev = fix
        // A standing phone still counts as a sample: it breaks a run, so a bus that stopped at a light with the
        // rider beside it, rather than on it, does not add up.
        if (!moving) {
            add(Boarding.Sample(fix.at, false, emptyList()))
            return
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastFetchMs < FETCH_EVERY_MS) return
        lastFetchMs = nowMs
        val buses = runCatching {
            Prefs.loadSources(ctx)
            val area = KavLive.area(KavLive.box(fix.lat, fix.lon, BOX_KM))
            area.buses.mapNotNull { b ->
                val s = b.spot ?: return@mapNotNull null
                val number = area.lines[b.route]?.number ?: ""
                if (number.isNotEmpty()) lines[b.key] = number
                Boarding.Sighting(b.key, number, s.lat, s.lon)
            }
        }.getOrNull() ?: return
        add(Boarding.Sample(fix.at, true, Boarding.near(fix, buses, RADIUS_M)))
        val key = Boarding.candidate(samples.toList(), NEEDED, MAX_AGE_SECS, fix.at) ?: return
        val quietId = ("boarding:$key").hashCode().toLong()
        if (Prefs.alertQuietUntil(ctx, quietId) > fix.at) return
        Prefs.quietAlert(ctx, quietId, fix.at + QUIET_SECS)
        suggest(ctx, lines[key].orEmpty(), fix)
    }

    private fun add(sample: Boarding.Sample) {
        samples.addLast(sample)
        while (samples.size > MAX_SAMPLES) samples.removeFirst()
    }

    private fun suggest(ctx: Context, line: String, fix: Boarding.Fix) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(T("Boarding detection", "זיהוי עלייה לאוטובוס"))
                .setDescription(T(
                    "A suggestion when you seem to have boarded a bus, to start tracking it",
                    "הצעה כשנראה שעליתם לאוטובוס, להתחיל מעקב אחריו",
                ))
                .build(),
        )
        // The tap opens the board of the stop nearest the fix, where this bus is due; without the timetable
        // loaded, Kav simply opens.
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching {
            val net = Loaded.net ?: runBlocking { runCatching { loadNet(ctx) }.getOrNull() }
            if (net != null) {
                var best = -1
                var bestM = Double.MAX_VALUE
                for (s in 0 until net.nStops) {
                    val m = Boarding.meters(fix.lat, fix.lon, net.lat[s], net.lon[s])
                    if (m < bestM) { bestM = m; best = s }
                }
                if (best >= 0) open.putExtra(LineAlerts.EXTRA_STOP, net.stopKey(best))
            }
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_trip_notice)
            .setContentTitle(T("On line $line?", "בקו $line?"))
            .setContentText(T(
                "Looks like you boarded. Tap to follow it.",
                "נראה שעליתם. הקשה כדי לעקוב אחריו.",
            ))
            .setColor(Prefs.accent(ctx))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(android.app.PendingIntent.getActivity(
                ctx, TAG.hashCode(), open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            ))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        try { nm.notify(TAG, 1, n) } catch (_: SecurityException) {}
    }
}
