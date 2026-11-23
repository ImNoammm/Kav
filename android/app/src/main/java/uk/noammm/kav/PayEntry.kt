package uk.noammm.kav

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import uk.noammm.kav.data.Ticket
import uk.noammm.kav.data.TicketTimes
import uk.noammm.kav.ui.Lang
import uk.noammm.kav.ui.PayFor
import uk.noammm.kav.ui.RavPass
import uk.noammm.kav.ui.ticketName

// Paying from outside Kav's screens: the trip's notification, the get-off alert, a launcher shortcut, a quick settings
// tile and the live ticket's own notification. Each one opens Kav's own screen first, which then opens Rav-Pass, so
// Rav-Pass comes up inside Kav's task as from a Pay row (the button back to Kav and the reader's hand-off work the same),
// and Android 12's ban on starting an activity from a notification's service or receiver doesn't apply.
internal object PayEntry {
    const val ACTION_PAY = "uk.noammm.kav.action.PAY"
    const val ACTION_TICKET = "uk.noammm.kav.action.TICKET"
    private const val EXTRA_SECTION = "uk.noammm.kav.PAY"
    private const val EXTRA_LINE = "uk.noammm.kav.PAY_LINE"
    private const val EXTRA_OFF = "uk.noammm.kav.PAY_OFF"
    private const val EXTRA_RIDE = "uk.noammm.kav.PAY_RIDE"

    // THE check for every way in here: paying is on. Off, none of them shows.
    fun on(ctx: Context): Boolean = uk.noammm.kav.ui.PayMode.on(ctx)

    // Turned on, set up or not: the Pay shortcut, tile and notification Pay stay, and while something is missing (Rav-Pass
    // removed, a permission taken back) they open Kav on its setup popup.
    fun chosen(ctx: Context): Boolean = Prefs.ravPass(ctx)

    // In the rider's language in Kav, for what Kav posts while none of its screens is open.
    fun t(ctx: Context, en: String, he: String) = if (Prefs.lang(ctx) == Lang.HE) he else en

    // The chosen payment app's name in the rider's language.
    fun appName(ctx: Context): String {
        val app = uk.noammm.kav.ui.PayApp.current
        return if (Prefs.lang(ctx) == Lang.HE) app.he else app.en
    }

    // It with a Hebrew "in" / "from" folded in ("ברב-פס", "מ-egg"); in English the bare name serves.
    fun appIn(ctx: Context): String {
        val app = uk.noammm.kav.ui.PayApp.current
        return if (Prefs.lang(ctx) == Lang.HE) app.heIn else app.en
    }

    fun appFrom(ctx: Context): String {
        val app = uk.noammm.kav.ui.PayApp.current
        return if (Prefs.lang(ctx) == Lang.HE) app.heFrom else app.en
    }

    fun pay(ctx: Context, s: RavPass.Section, ride: PayFor? = null): Intent = Intent(ctx, MainActivity::class.java).setAction(ACTION_PAY)
        .putExtra(EXTRA_SECTION, s.name).putExtra(EXTRA_LINE, ride?.line).putExtra(EXTRA_OFF, ride?.off).putExtra(EXTRA_RIDE, ride?.ride)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // A ticket, or the latest live one when none is named (a shortcut kept from before).
    fun ticket(ctx: Context, key: String?): Intent = Intent(ctx, MainActivity::class.java).setAction(ACTION_TICKET)
        .apply { key?.let { putExtra(TicketReader.EXTRA_TICKET, it) } }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun pending(ctx: Context, i: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // A Pay from outside, waiting for Kav's screen to be in front: Rav-Pass opened before that would see Kav's own
    // resume take the button back to Kav away (MainActivity.onResume).
    private var waiting: Pair<RavPass.Section, PayFor?>? = null

    // Kav's screen given one of these: true when it was one.
    fun take(ctx: Context, intent: Intent?): Boolean = when (intent?.action) {
        ACTION_PAY -> {
            val s = RavPass.Section.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SECTION) } ?: RavPass.Section.BUS
            val line = intent.getStringExtra(EXTRA_LINE); val off = intent.getStringExtra(EXTRA_OFF); val ride = intent.getStringExtra(EXTRA_RIDE)
            waiting = s to (if (line == null && off == null && ride == null) null else PayFor(line, off, ride))
            true
        }
        ACTION_TICKET -> {
            TicketStore.load(ctx)
            val key = intent.getStringExtra(TicketReader.EXTRA_TICKET)
            TicketStore.opened = key?.takeIf { k -> TicketStore.kept.any { it.key == k } }
                ?: TicketTimes.latest(TicketStore.kept, System.currentTimeMillis())?.key
            true
        }
        else -> false
    }

    fun resumed(a: Activity) {
        val (s, ride) = waiting ?: return
        waiting = null
        if (on(a)) RavPass.open(a, s, ride) else if (chosen(a)) uk.noammm.kav.ui.PaySetup.show()
    }

    // Everything outside Kav brought up to date: after a ticket is kept or ends, when Kav goes to the background (where
    // its paying switch may have changed), and when a trip starts or ends.
    private var synced: String? = null
    private var syncing = false

    fun sync(ctx: Context) {
        if (syncing) return
        syncing = true
        runCatching { syncNow(ctx.applicationContext) }
        syncing = false
    }

    private fun syncNow(app: Context) {
        TicketStore.load(app)
        val chosen = chosen(app)
        val live = if (on(app)) TicketTimes.latest(TicketStore.kept, System.currentTimeMillis()) else null
        val state = "$chosen:${live?.key}:${Prefs.lang(app)}"
        if (state != synced) {
            synced = state
            shortcuts(app, chosen, live)
            app.packageManager.setComponentEnabledSetting(ComponentName(app, PayTile::class.java),
                if (chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
        if (chosen) TileService.requestListeningState(app, ComponentName(app, PayTile::class.java))
        TicketNotice.update(app)
        TripService.repost()
    }

    private const val SHORTCUT_PAY = "pay"
    private const val SHORTCUT_TICKET = "ticket"

    // "Pay for a ride" on a long press of Kav's icon (and for launchers that bind a gesture to a shortcut, GitHub #79),
    // with "Show ticket" beside it while one is live. With paying off, none: a pinned one is greyed out with why.
    private fun shortcuts(ctx: Context, on: Boolean, live: Ticket?) {
        val ids = listOf(SHORTCUT_PAY, SHORTCUT_TICKET)
        if (!on) {
            // Only its own: Kav's other shortcuts (SafeStart) stay.
            ShortcutManagerCompat.removeDynamicShortcuts(ctx, ids)
            ShortcutManagerCompat.disableShortcuts(ctx, ids, t(ctx, "Paying is off in Kav's settings", "התשלום כבוי בהגדרות של Kav"))
            return
        }
        ShortcutManagerCompat.enableShortcuts(ctx, ids.map { info(ctx, it, live) })
        if (live == null) ShortcutManagerCompat.removeDynamicShortcuts(ctx, listOf(SHORTCUT_TICKET))
        ShortcutManagerCompat.addDynamicShortcuts(ctx, listOfNotNull(info(ctx, SHORTCUT_PAY, null), live?.let { info(ctx, SHORTCUT_TICKET, it) }))
    }

    private fun info(ctx: Context, id: String, live: Ticket?): ShortcutInfoCompat = if (id == SHORTCUT_PAY)
        ShortcutInfoCompat.Builder(ctx, id).setShortLabel(t(ctx, "Pay", "תשלום")).setLongLabel(t(ctx, "Pay for a ride", "תשלום על נסיעה"))
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.shortcut_pay)).setIntent(pay(ctx, RavPass.Section.BUS)).setRank(0).build()
    else ShortcutInfoCompat.Builder(ctx, id).setShortLabel(t(ctx, "Ticket", "כרטיס")).setLongLabel(t(ctx, "Show ticket", "הצגת הכרטיס"))
        .setIcon(IconCompat.createWithResource(ctx, R.drawable.shortcut_ticket)).setIntent(ticket(ctx, null)).setRank(1).build()
}

// Quick settings: "Pay for a ride", or "Show ticket" while a ticket is live, with its name. Its component is on only while
// paying is turned on (PayEntry.sync), so with paying off the tile isn't there to add.
class PayTile : TileService() {
    override fun onStartListening() { runCatching { show() } }

    private fun live(): Ticket? {
        TicketStore.load(this)
        return if (PayEntry.on(this)) TicketTimes.latest(TicketStore.kept, System.currentTimeMillis()) else null
    }

    private fun show() {
        val tile = qsTile ?: return
        val live = live()
        tile.state = when { !PayEntry.chosen(this) -> Tile.STATE_UNAVAILABLE; live != null -> Tile.STATE_ACTIVE; else -> Tile.STATE_INACTIVE }
        tile.label = if (live != null) PayEntry.t(this, "Show ticket", "הצגת הכרטיס") else PayEntry.t(this, "Pay for a ride", "תשלום על נסיעה")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = live?.let { ticketName(it) } ?: PayEntry.appName(this)
        tile.icon = Icon.createWithResource(this, if (live != null) R.drawable.ic_ticket else R.drawable.ic_pay)
        tile.updateTile()
    }

    override fun onClick() {
        if (!PayEntry.chosen(this)) return
        val i = live()?.let { PayEntry.ticket(this, it.key) } ?: PayEntry.pay(this, RavPass.Section.BUS)
        val go = {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PayEntry.pending(this, i, 2))
            else @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
        if (isLocked) unlockAndRun(go) else go()
    }
}

// A live ticket at hand for the inspector: a quiet notification whose tap opens its code. Not while the trip's own
// notification shows (it has "Show ticket" itself), not after the rider swiped it away, and gone when the ticket ends.
internal object TicketNotice {
    private const val ID = 9
    private const val CHANNEL = "ticket"

    private fun posted(ctx: Context) = ctx.getSharedPreferences("ticket-notice", Context.MODE_PRIVATE)

    fun update(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        val live = TicketTimes.latest(TicketStore.kept, System.currentTimeMillis())
        if (live == null || !PayEntry.on(ctx) || TripService.showing || !nm.areNotificationsEnabled()) {
            nm.cancel(ID)
            posted(ctx).edit().clear().apply()
            return
        }
        val up = runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications.any { it.id == ID } }.getOrDefault(false)
        // Swiped away: not again for the same ticket.
        if (!up && posted(ctx).getString("key", null) == live.key) return
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(PayEntry.t(ctx, "Live ticket", "כרטיס בתוקף"))
                .setDescription(if (Prefs.lang(ctx) == Lang.HE) "הכרטיס שנקרא ${PayEntry.appFrom(ctx)}, במרחק הקשה אחת מהקוד שלו"
                    else "The ticket read from ${PayEntry.appName(ctx)}, one tap from its code")
                .build(),
        )
        val text = (live.paidAt?.let { PayEntry.t(ctx, "Paid at ${TicketTimes.clock(it)}", "שולם ב-${TicketTimes.clock(it)}") }
            ?: PayEntry.t(ctx, "Read at ${TicketTimes.clock(live.readAt)}", "נקרא ב-${TicketTimes.clock(live.readAt)}")) + " · " +
            if (live.passengers.isEmpty()) PayEntry.t(ctx, "Its code is in ${PayEntry.appName(ctx)}", "הקוד שלו ${PayEntry.appIn(ctx)}")
            else PayEntry.t(ctx, "Tap for its code", "הקישו לקוד שלו")
        val title = ticketName(live)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_ticket)
            .setContentTitle(title)
            .setContentText(text)
            .setColor(Prefs.accent(ctx))
            .setContentIntent(PayEntry.pending(ctx, PayEntry.ticket(ctx, live.key), 3))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_ticket)
                .setContentTitle(PayEntry.t(ctx, "Ticket from ${PayEntry.appName(ctx)}", "כרטיס ${PayEntry.appFrom(ctx)}")).setColor(Prefs.accent(ctx)).build())
            .build()
        runCatching { nm.notify(ID, n) }
        posted(ctx).edit().putString("key", live.key).apply()
    }
}
