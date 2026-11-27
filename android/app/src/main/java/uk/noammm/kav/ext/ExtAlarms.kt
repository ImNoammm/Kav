package uk.noammm.kav.ext

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

// An extension's alarms (Kav.alarm): Android wakes Kav at the time it set, with Kav closed too, and Kav tells the
// extension (Kav.onAlarm) once its native extensions have started. Each by the extension's own id; the same id again
// moves it, a time of 0 or less takes it out. Not to the second: Android may hold it a few minutes while the phone is
// idle, as with any app's alarm that needs no special permission. At most MOST at a time for each extension.
object ExtAlarms {
    const val MOST = 20
    private const val ACTION = "uk.noammm.kav.EXT_ALARM"
    private const val PREFS = "ext-alarms"
    private const val START_WAIT_MS = 8_000L

    fun set(ctx: Context, identity: String, id: String, atMs: Long) {
        require(id.isNotBlank() && id.length <= 100) { "an alarm's id is 1 to 100 characters" }
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Only those still set: a restart of the phone drops them all.
        val ids = prefs.getStringSet(identity, emptySet()).orEmpty().filterTo(HashSet()) {
            PendingIntent.getBroadcast(ctx, 0, intent(ctx, identity, it), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 0, intent(ctx, identity, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (atMs <= 0) {
            am.cancel(pi); pi.cancel()
            prefs.edit().putStringSet(identity, ids - id).apply()
            return
        }
        check(id in ids || ids.size < MOST) { "at most $MOST alarms at a time" }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        prefs.edit().putStringSet(identity, ids + id).apply()
    }

    // One intent for each extension's id: Android tells alarms apart by their data.
    private fun intent(ctx: Context, identity: String, id: String) = Intent(ctx, Receiver::class.java).setAction(ACTION)
        .setData(Uri.Builder().scheme("kav-alarm").authority("ext").appendPath(identity).appendPath(id).build())
        .putExtra("identity", identity).putExtra("id", id)

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) return
            val identity = intent.getStringExtra("identity") ?: return
            val id = intent.getStringExtra("id") ?: return
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(identity, prefs.getStringSet(identity, emptySet()).orEmpty() - id).apply()
            val done = goAsync()
            Thread({
                try {
                    ExtApp.awaitStarted(START_WAIT_MS)
                    Native.alarm(identity, id)
                } finally { done.finish() }
            }, "kav-ext-alarm").start()
        }
    }
}
