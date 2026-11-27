package uk.noammm.kav.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material.icons.rounded.Wallet
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import uk.noammm.kav.Prefs
import uk.noammm.kav.TicketStore
import uk.noammm.kav.data.Transit

// The app paying is handed over to. Rav-Pass is HopOn's own app; egg is Egged's app on the same HopOn platform (a
// white label: egg.hopon.co delegates every link on it to com.egged.egg, answers the same app_actions paths, and its
// store version matches Rav-Pass's). Each names its package, its links' host, its signing certificate and the versions
// whose ticket screens Kav's reader was checked against (TicketScreens).
internal enum class PayApp(
    val pkg: String,
    val host: String,
    val en: String,
    val he: String,
    // SHA-256 of the app's signing certificate (its host's assetlinks.json): only that app is read.
    val signer: String,
    // The versions (its versionName) whose ticket screens Kav's reader was checked against: another one says Kav may
    // not read its tickets. egg's are Rav-Pass's own, the same HopOn build under Egged's branding (unverified on a
    // phone so far: the reader says so until one is confirmed).
    val versions: Set<String>,
    // Where the get-it button sends a phone with no Play or Aurora store.
    val getPage: String,
) {
    RAV_PASS("co.hopon.client", "app.ravpass.co.il", "Rav-Pass", "רב-פס",
        "a8d44a2e28a995d71aa308ee08e400c8f083fe30632aba077fd786789c65396d", setOf("10.2.16", "10.2.30"),
        "https://www.apkmirror.com/apk/hopon-mobility-ltd/rav-pass-by-hopon-%D7%94%D7%95%D7%A4%D7%90%D7%95%D7%9F-%D7%A8%D7%91-%D7%A4%D7%A1/"),
    EGG("com.egged.egg", "egg.hopon.co", "egg", "egg",
        "7b3e2b1ef624d1dcde74fa7b27f0b68b752a5cab00d36c0b25b533247ee0b155", setOf("10.2.30"),
        "https://play.google.com/store/apps/details?id=com.egged.egg"), ;

    fun label(): String = T(en, he)

    // The Hebrew name with a one-letter prefix joined to it: straight onto a Hebrew name (ברב-פס), with a hyphen before a
    // Latin one, as Kav's Hebrew writes ב-Kav (ב-egg).
    fun heWith(prefix: String): String = if (he.first() in 'א'..'ת') prefix + he else "$prefix-$he"
    val heIn: String get() = heWith("ב")
    val heFrom: String get() = heWith("מ")
    val heOf: String get() = "של $he"

    companion object {
        // The rider's choice on Settings' Paying page. Until they pick one, the app that's on the phone: Rav-Pass when it's
        // there, egg when only egg is (preferred). Read from the phone when Kav's process starts (KavApp) and each time
        // Kav comes back (PayMode.refresh), and written by the choice itself.
        var current by mutableStateOf(RAV_PASS)
            private set

        fun of(saved: String?): PayApp = entries.firstOrNull { it.name == saved } ?: RAV_PASS

        // With no choice made: Rav-Pass when it's installed or neither is, egg when it's the only one of the two.
        fun preferred(has: (PayApp) -> Boolean): PayApp = if (!has(RAV_PASS) && has(EGG)) EGG else RAV_PASS

        fun refresh(ctx: Context) {
            current = Prefs.payApp(ctx).takeIf { it.isNotEmpty() }?.let(::of)
                ?: preferred { ctx.packageManager.getLaunchIntentForPackage(it.pkg) != null }
        }

        fun choose(ctx: Context, app: PayApp) {
            Prefs.setPayApp(ctx, app.name)
            current = app
        }
    }
}

// Paying with the chosen app, by handing the rider over to it: HopOn allowed Kav a link that opens their app, not
// paying inside Kav (18 Nov 2026). Kav asks no server for this, gives the app nothing about the ride, and never takes
// a ride as paid: what happens in it Kav can't see.
internal object RavPass {
    val PACKAGE: String get() = PayApp.current.pkg
    private val PAY: String get() = "https://${PayApp.current.host}/app_actions/pay"

    // Where Rav-Pass's pay screen opens: its bus QR scanner, its Israel Railways section, its "Light Rail / Carmelit /
    // Cable car" section, or the screen as it is, for any other ride, so no wrong section is opened for the rider.
    enum class Section(val path: String?) { BUS("bus"), TRAIN("train"), LIGHT_RAIL("lightrail"), ANY(null) }

    fun section(mode: Mode, rail: Boolean): Section = when {
        rail -> Section.TRAIN
        mode == Mode.BUS -> Section.BUS
        mode == Mode.TRAM || mode == Mode.SUBWAY || mode == Mode.FUNICULAR || mode == Mode.GONDOLA -> Section.LIGHT_RAIL
        else -> Section.ANY
    }

    // The Carmelit (Haifa's funicular), paid at its station.
    fun carmelit(ride: Transit.Leg, r: Transit.Resolved): Boolean = modeOf(r.routeType(r.line(ride.lineId)?.agencyId ?: -1)) == Mode.FUNICULAR

    fun section(ride: Transit.Leg, r: Transit.Resolved): Section {
        val agency = r.line(ride.lineId)?.agencyId ?: -1
        val type = r.routeType(agency)
        return section(modeOf(type), isRail(type, agency))
    }

    // An intent as plain values, which a test can read off the phone. There is nothing in it for extras.
    data class Open(val action: String, val data: String?, val categories: Set<String>, val pkg: String?)

    // Rav-Pass's own pay link, pinned to Rav-Pass so it can never land in a browser.
    fun link(s: Section) = Open(Intent.ACTION_VIEW, s.path?.let { "$PAY/$it" } ?: PAY, setOf(Intent.CATEGORY_BROWSABLE), PACKAGE)
    // Rav-Pass as its launcher icon opens it, which is all Bus Nearby does.
    val launch = Open(Intent.ACTION_MAIN, null, setOf(Intent.CATEGORY_LAUNCHER), PACKAGE)

    fun steps(s: Section) = listOf(link(s), launch)

    // The first step that opens, or null when none does.
    fun open(s: Section, start: (Open) -> Boolean): Open? = steps(s).firstOrNull(start)

    private fun intent(ctx: Context, o: Open): Intent? =
        if (o == launch) ctx.packageManager.getLaunchIntentForPackage(PACKAGE)
        else Intent(o.action, o.data?.let(Uri::parse)).apply { o.categories.forEach { addCategory(it) }; o.pkg?.let { setPackage(it) } }

    // Only when Android finds them in Rav-Pass (the manifest's <queries> lets Kav see it).
    private fun start(ctx: Context, o: Open): Boolean {
        val i = intent(ctx, o) ?: return false
        if (o.pkg == PACKAGE && i.resolveActivity(ctx.packageManager) == null) return false
        return try { ctx.startActivity(i); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
    }

    // A Pay tap: every one comes here. An extension that takes over paying goes first (the action "pay"), told the ride,
    // or {} for none ({"section"} for another section than the bus's); then Kav's own.
    fun open(ctx: Context, s: Section, ride: PayFor? = null) {
        if (uk.noammm.kav.ext.Actions.take(ctx, "pay") { args(s, ride) }) return
        own(ctx, s, ride)
    }

    fun args(s: Section, ride: PayFor?): org.json.JSONObject = ride?.ride?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        ?: org.json.JSONObject().apply { if (s != Section.BUS) put("section", key(s)) }

    fun key(s: Section) = s.path ?: "any"

    // The ride an extension is told: its section, line, mode, stops with their places, departure and trip.
    fun ride(l: Transit.Leg, r: Transit.Resolved): org.json.JSONObject {
        val j = uk.noammm.kav.ext.Pipeline.legJson(l, r)
        return org.json.JSONObject().put("section", key(section(l, r))).put("line", j.opt("lineNumber")).put("mode", j.opt("mode"))
            .put("from", j.opt("from")).put("to", j.opt("to")).put("dep", l.dep).put("trip", j.opt("trip"))
    }

    // Kav's own paying, also for an extension's kavAction: Rav-Pass. A bus's, with a train entrance still open, asks first
    // about the exit ticket (EntranceAsk).
    fun own(ctx: Context, s: Section, ride: PayFor? = null) {
        if (EntranceAsk.asks(ctx, s, ride)) return
        PayHandOff.pending = ride
        go(ctx, s)
    }

    fun go(ctx: Context, s: Section) {
        PayHandOff.take()
        val opened = open(s) { start(ctx, it) }
        // Rav-Pass gone from the phone since paying was set up: the popup that gets it again.
        if (opened == null) PaySetup.show()
        // Its pay link opens it inside Kav's task, where Kav can tell it's in front; its launcher icon's way doesn't.
        else if (opened == link(s)) { PayOverlay.arm(ctx); Prefs.setPayTappedAt(ctx, System.currentTimeMillis()) }
    }

    // Rav-Pass's own places from Settings, by the app_actions links its router knows (read from Rav-Pass 10.2.30): its
    // account picker over the pay screen, its payment cards (on its Profile tab), its Profile tab, where its rides and
    // billing are one tap away (that screen has no link of its own), its extra passengers, discount profile, coupons and
    // support (each on its Profile tab too), and its Rav-Kav top-up. Each falls back to Rav-Pass's front screen. The picker, like the pay link, is a hand-off from Kav.
    private fun action(name: String) = Open(Intent.ACTION_VIEW, "https://${PayApp.current.host}/app_actions/$name", setOf(Intent.CATEGORY_BROWSABLE), PACKAGE)
    val chooseAccount = action("choose_account")
    val paymentCards = action("payment_methods")
    val profile = action("personal_profile")
    val passengers = action("extra_passengers")
    val discountProfile = action("discount_profile")
    val coupons = action("coupons")
    val support = action("customer_service")
    val ravKavTopUp = action("ravkav_topup")

    // Rav-Pass's own places by the name its link ends with, as the "pay.place.open" action names them.
    fun placeName(o: Open) = o.data?.substringAfterLast('/')
    fun placeNamed(name: String) =
        listOf(paymentCards, profile, discountProfile, chooseAccount, passengers, coupons, ravKavTopUp, support).firstOrNull { placeName(it) == name }

    // Opened by its link, it comes up inside Kav's task as the pay link does, with the button back to Kav over it (showing
    // no ride).
    fun place(ctx: Context, o: Open) {
        val opened = listOf(o, launch).firstOrNull { start(ctx, it) }
        when (opened) {
            null -> PaySetup.show()
            launch -> {}
            else -> {
                PayHandOff.pending = null; PayHandOff.take(); PayOverlay.arm(ctx)
                // Over Rav-Pass's Profile tab: one Back from it comes back to Kav (TicketReader.backFromPlace).
                uk.noammm.kav.TicketReader.placeOpenedAt =
                    if (opened in listOf(discountProfile, passengers, coupons, support, paymentCards)) System.currentTimeMillis() else 0L
                if (opened == chooseAccount) Prefs.setPayTappedAt(ctx, System.currentTimeMillis())
            }
        }
    }

    fun installed(ctx: Context): Boolean = ctx.packageManager.getLaunchIntentForPackage(PACKAGE) != null ||
        intent(ctx, link(Section.BUS))?.resolveActivity(ctx.packageManager) != null

    // Where to get Rav-Pass: the Play Store, else Aurora Store (both answer its market link; the manifest's <queries> lets
    // Kav see which do), else its APKMirror page in the browser, where Morphe sends riders for apps too. No other app that
    // answers market links: F-Droid does, and can't have Rav-Pass.
    enum class Store(val pkg: String?) { PLAY("com.android.vending"), AURORA("com.aurora.store"), APKMIRROR(null) }

    private val MARKET: String get() = "market://details?id=${PayApp.current.pkg}"

    fun store(answering: Collection<String>): Store = Store.entries.first { it.pkg == null || it.pkg in answering }

    fun store(ctx: Context): Store = store(runCatching {
        @Suppress("DEPRECATION") ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(MARKET)), 0)
            .map { it.activityInfo.packageName }
    }.getOrDefault(emptyList()))

    // Rav-Pass's page in that store, opened in it; its APKMirror page when that doesn't open.
    fun get(ctx: Context, s: Store) {
        fun go(i: Intent) = try { ctx.startActivity(i); true } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }
        val mirror = Intent(Intent.ACTION_VIEW, Uri.parse(PayApp.current.getPage)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (s.pkg != null && go(Intent(Intent.ACTION_VIEW, Uri.parse(MARKET)).setPackage(s.pkg)) || go(mirror)) return
        android.widget.Toast.makeText(ctx, T("Couldn't open an app store or a browser.", "לא ניתן היה לפתוח חנות אפליקציות או דפדפן."),
            android.widget.Toast.LENGTH_LONG).show()
    }
}

// Whether Rav-Pass is on the phone, read again each time Kav comes back (from installing it, say), and as soon as
// Android says Rav-Pass was installed, removed or turned off while Kav is open.
@Composable
internal fun rememberRavPass(): Boolean {
    val ctx = LocalContext.current
    // Keyed on the chosen app too: switching it in Settings or in the setup popup re-checks the phone at once.
    var installed by remember(PayApp.current) { mutableStateOf(RavPass.installed(ctx)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { installed = RavPass.installed(ctx) } }
    DisposableEffect(ctx) {
        val changed = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { installed = RavPass.installed(ctx) }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
            for (app in PayApp.entries) addDataSchemeSpecificPart(app.pkg, android.os.PatternMatcher.PATTERN_LITERAL)
        }
        androidx.core.content.ContextCompat.registerReceiver(ctx, changed, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(changed) }
    }
    return installed
}

// Home's one paying row, in every state: set up, it opens Rav-Pass on its bus scanner; turned on with something missing
// since (Rav-Pass removed, a permission taken back), Action required and the setup popup; never turned on (a rider
// updating to Kav 5), an offer to set it up, which its × hides for good (Settings' Paying stays the way back).
@Composable
internal fun PayCard(modifier: Modifier, showPay: Boolean) {
    val ctx = LocalContext.current
    val action = PayMode.lost.isNotEmpty()
    val offer = !Shown.payments && !action
    Column(modifier.fillMaxWidth().panel(K.rCard).animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f))) {
        if (showPay) {
            ListRow(
                T("Pay for a ride", "תשלום על נסיעה"),
                subtitle = when {
                    action -> T("Action required", "נדרשת פעולה")
                    offer -> T("Set up paying with ${PayApp.current.label()}", "הגדרת תשלום עם ${PayApp.current.he}")
                    else -> null
                },
                subtitleColor = if (action) K.problem else K.accentInk,
                leading = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(24.dp)) },
                trailing = if (offer) { {
                    Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .clickable(role = Role.Button, onClickLabel = T("Hide", "הסתרה")) {
                            if (!uk.noammm.kav.ext.Actions.take(ctx, "pay.offer.hide") { org.json.JSONObject() }) PayMode.dismissOffer(ctx)
                        },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, contentDescription = T("Hide", "הסתרה"), tint = K.textTertiary, modifier = Modifier.size(20.dp))
                    }
                } } else null,
                chevron = !offer,
                onClick = {
                    when {
                        action -> PaySetup.show()
                        offer -> payMode(ctx, true, "home")
                        else -> RavPass.open(ctx, RavPass.Section.BUS)
                    }
                },
            )
        }
        // The tickets read from Rav-Pass, in the same card, as Kav's own tickets used to be, and the rides' summary.
        HomeTicketRows()
        if (Shown.payments || !showPay || TicketStore.history.any { it.key.startsWith("ext:") }) RideSummary()
        if (showPay) RavPassUpdatedLine(home = true)
    }
}

// Under a ride's line, where paying for it was: one small action that opens Rav-Pass on that ride's section.
// A ride's own paying, under its line, as it was before 4.3.2: its ticket when Kav has one, else the row for paying it the
// way its mode is paid (the code on board a bus; at the station for train, light rail and the Carmelit), now opening
// Rav-Pass at that section. first: at the top of its card, with its gap under it instead of over it.
@Composable
internal fun RidePay(section: RavPass.Section, carmelit: Boolean, board: String?, line: String?, depMs: Long, off: String? = null, ride: String? = null,
    of: Any? = null, about: (() -> org.json.JSONObject)? = null, first: Boolean = false) {
    if (!Shown.payments) return
    val ctx = LocalContext.current
    if (!first) Spacer(Modifier.height(K.gap3))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).background(K.fill)) {
        if (RideTicketRow(section, line, depMs)) return@Column
        // The pay row itself, which extensions may add to or replace (ride.pay), told the trip, the leg and its index.
        if (about != null) KavPart("ride.pay", of, about) { PayRow(ctx, section, carmelit, board, line, off, ride) }
        else PayRow(ctx, section, carmelit, board, line, off, ride)
    }
    if (first) Spacer(Modifier.height(K.gap3))
}

@Composable
private fun PayRow(ctx: Context, section: RavPass.Section, carmelit: Boolean, board: String?, line: String?, off: String?, ride: String?) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = T("Open the ${PayApp.current.label()} app", "מעבר לאפליקציית ${PayApp.current.he}")) { RavPass.open(ctx, section, PayFor(line, off, ride)) }
            // The title's font keeps room above its capitals, so half a dp less on top centres the text you see.
            .padding(start = K.gap3, end = K.gap3, top = 11.5.dp, bottom = 12.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Canvas(Modifier.size(20.dp)) { drawQr() }
        Column(Modifier.weight(1f)) {
            Text(when {
                section == RavPass.Section.TRAIN -> T("Pay at the train station", "תשלום בתחנת הרכבת")
                carmelit -> T("Pay at the Carmelit station", "תשלום בתחנת הכרמלית")
                section == RavPass.Section.LIGHT_RAIL -> T("Pay at the light rail station", "תשלום בתחנת הרכבת הקלה")
                else -> T("Pay for this ride", "תשלום על הנסיעה")
            }, style = K.type.bodyStrong)
            Text(if (section == RavPass.Section.BUS || section == RavPass.Section.ANY) T("Scan the QR code on the bus", "סורקים את קוד ה-QR באוטובוס")
                else board ?: T("At the station", "בתחנה"), style = K.type.subhead)
        }
    }
}

// A ride of a trip, by its own section, boarding stop, line and departure, and the stop it gets off at (for the button back
// to Kav). about: what its pay row's part (ride.pay) is told, the trip, the leg and its index; none, no part.
@Composable
internal fun RidePay(ride: Transit.Leg, r: Transit.Resolved, about: (() -> org.json.JSONObject)? = null, first: Boolean = false) =
    RidePay(RavPass.section(ride, r), RavPass.carmelit(ride, r), r.stop(ride.fromStop)?.shown(), r.line(ride.lineId)?.number, ride.dep * 1000,
        r.stopName(ride.toStop), remember(ride) { RavPass.ride(ride, r).toString() }, ride, about, first)

// "Pay with Rav-Pass": one switch for paying with Rav-Pass and everything it needs, all or nothing (Noam, 19 Nov 2026):
// Rav-Pass itself on the phone, showing over other apps, for the button back to Kav while paying (PayOverlay), and the ticket reader
// (TicketReader), an accessibility service the rider turns on in Android's own settings: Kav never changes those itself.
// Each only where the phone can have it at all. On only once all are granted; off, Kav shows nothing about paying.
internal object PayMode {
    enum class Need { APP, OVERLAY, READER }

    // What's still missing, by Android version, whether showing over other apps is allowed, whether the reader is on, and
    // whether Rav-Pass is installed.
    fun missing(sdk: Int, overlay: Boolean, reader: Boolean, app: Boolean = true): List<Need> = listOfNotNull(
        Need.APP.takeIf { !app },
        Need.OVERLAY.takeIf { PayOverlay.supported(sdk) && !overlay },
        Need.READER.takeIf { TicketView.supported(sdk) && !reader },
    )

    fun missing(ctx: Context) = missing(android.os.Build.VERSION.SDK_INT, android.provider.Settings.canDrawOverlays(ctx), TicketView.enabled(ctx),
        RavPass.installed(ctx))

    // On: turned on, with nothing missing, or an extension that takes over paying (the action "pay"), which needs none
    // of it. What everything about paying checks, in Kav and outside it (PayEntry).
    fun on(ctx: Context) = uk.noammm.kav.ext.Actions.held("pay") || Prefs.ravPass(ctx) && missing(ctx).isEmpty()

    // Turned on, and something gone since (Rav-Pass removed, the reader switched off in Android, a force stop, showing
    // over other apps taken back): said, never hidden silently.
    var lost by mutableStateOf<List<Need>>(emptyList())
        private set
    // Home's paying row offering it, to a rider updating to Kav 5 without it.
    var offer by mutableStateOf(false)
        private set
    // Read again at start and each time Kav comes back. The first time on a phone set up before Kav 5: on when every
    // permission is already on (as Noam's is), else offered once on Home.
    fun refresh(ctx: Context) {
        PayApp.refresh(ctx)
        val missing = missing(ctx)
        if (!Prefs.ravPassDecided(ctx)) {
            val on = Prefs.onboarded(ctx) && missing.isEmpty()
            Prefs.setRavPass(ctx, on)
            if (on) { Prefs.setPayOverlay(ctx, true); Prefs.setTickets(ctx, true) }
            Prefs.setRavPassOffer(ctx, Prefs.onboarded(ctx) && !on)
        }
        // Still offered, and every permission there now (Android binds the reader again a moment after Kav is updated, so
        // the first look can miss it): on, as it would have been.
        if (Prefs.ravPassOffer(ctx) && !Prefs.ravPass(ctx) && missing.isEmpty()) {
            Prefs.setRavPass(ctx, true); Prefs.setPayOverlay(ctx, true); Prefs.setTickets(ctx, true); Prefs.setRavPassOffer(ctx, false)
        }
        val chosen = Prefs.ravPass(ctx)
        val extension = uk.noammm.kav.ext.Actions.held("pay")
        Shown.payments = extension || chosen && missing.isEmpty()
        lost = if (chosen && !extension) missing else emptyList()
        offer = Prefs.ravPassOffer(ctx) && !chosen && !extension
    }

    // The switch turned on, or Home's offer: on at once when nothing is missing, else the setup popup (PaySetup).
    fun turnOn(ctx: Context) { if (missing(ctx).isEmpty()) done(ctx) else PaySetup.show() }

    // Everything in place: on.
    fun done(ctx: Context) {
        Prefs.setRavPass(ctx, true); Prefs.setPayOverlay(ctx, true); Prefs.setTickets(ctx, true); Prefs.setRavPassOffer(ctx, false)
        refresh(ctx); uk.noammm.kav.PayEntry.sync(ctx)
    }

    // Off: no pay buttons, no button back to Kav, no ticket notification, and the reader stops; the tickets it kept are
    // deleted.
    fun turnOff(ctx: Context) {
        Prefs.setRavPass(ctx, false); Prefs.setPayOverlay(ctx, false); Prefs.setRavPassOffer(ctx, false)
        PayOverlay.disarm()
        turnTicketsOff(ctx)
        refresh(ctx); uk.noammm.kav.PayEntry.sync(ctx)
    }

    fun dismissOffer(ctx: Context) { Prefs.setRavPassOffer(ctx, false); refresh(ctx) }
}

// The switch, in Settings and in setup. Turned on with something missing since: Action required, and the setup popup,
// which has the way to turn it off.
@Composable
internal fun PayModeSwitch() = ListGroup(Modifier.padding(top = K.gap6)) { row { PayModeRow() } }

// The switch's row: what turning it on asks for is behind its (i).
@Composable
private fun PayModeRow() {
    val ctx = LocalContext.current
    val action = PayMode.lost.isNotEmpty()
    val on = Shown.payments || action
    val app = PayApp.current
    ToggleRow(T("Pay with ${app.label()}", "תשלום עם ${app.he}"), on, { Canvas(Modifier.size(18.dp)) { drawQr() } },
        subtitle = if (action) T("Action required", "נדרשת פעולה") else null,
        subtitleMaxLines = 2, subtitleColor = if (action) K.problem else null,
        info = T("Pay buttons on Home and on each ride open ${app.label()}. Kav asks for two permissions: a button back to Kav over it, and reading its ticket screens.",
            "כפתורי התשלום במסך הבית ובכל נסיעה פותחים את ${app.he}. Kav מבקשת שתי הרשאות: כפתור חזרה ל-Kav מעליה, וקריאת מסכי הכרטיס שלה.")) { want ->
        when {
            action -> PaySetup.show(offerOff = true)
            else -> payMode(ctx, want, "settings")
        }
    }
}

// Paying turned on or off by the rider, by name ("pay.mode"): on at once or through setup, off only once they agree.
internal fun payMode(ctx: Context, on: Boolean, from: String) {
    if (!uk.noammm.kav.ext.Actions.take(ctx, "pay.mode") { org.json.JSONObject().put("on", on).put("from", from) }) payModeOwn(ctx, on)
}

internal fun payModeOwn(ctx: Context, on: Boolean) { if (on) PayMode.turnOn(ctx) else PayOffAsk.open = true }

// Turning paying off deletes what Kav kept: asked once, from the switch and from the setup popup alike, over everything
// (MainActivity hosts it).
internal object PayOffAsk {
    var open by mutableStateOf(false)
}

@Composable
internal fun PayOffAskHost() {
    if (!PayOffAsk.open) return
    val ctx = LocalContext.current
    BottomSheet({ PayOffAsk.open = false }) { close ->
        Text(T("Turn off paying?", "לכבות את התשלום?"), style = K.type.title, color = K.text)
        Spacer(Modifier.height(K.gap2))
        Text(T("Your tickets and ride history on this phone are deleted.", "הכרטיסים והיסטוריית הנסיעות בטלפון הזה נמחקים."),
            style = K.type.body, color = K.textSecondary)
        Spacer(Modifier.height(K.gap4))
        KavButton(T("Turn off", "כיבוי"), onClick = { close { PayOffAsk.open = false; PayMode.turnOff(ctx) } }, style = ButtonStyle.Destructive, wide = true)
        Spacer(Modifier.height(K.gap2))
        KavButton(T("Cancel", "ביטול"), onClick = { close { PayOffAsk.open = false } }, style = ButtonStyle.Secondary, wide = true)
    }
}

// Setup's paying step: the same switch, with the same popup.
@Composable
internal fun PayingSetup() = PayModeSwitch()

// Settings' Paying page: the one switch, then while it's on Rav-Pass's own places as rows (the everyday ones first), and
// what Kav keeps: the fare profile, the live tickets and the ride history.
@Composable
internal fun PayingSettings() {
    val ctx = LocalContext.current
    // An extension that takes over paying needs none of Rav-Pass's setup: its own settings are on its page.
    val extension = uk.noammm.kav.ext.Actions.firstName("pay")
    if (extension != null) ListGroup { row { ListRow(T("With \u2068$extension\u2069", "עם \u2068$extension\u2069"), chevron = false) } }
    // Which app paying goes through, first: the switch below and the setup popup both follow this choice, so it shows
    // before paying is set up at all (otherwise a rider who wants egg would have to set Rav-Pass up just to switch).
    else ListGroup { row { PayAppRow() }; row { PayModeRow() } }
    if (!Shown.payments) return
    SectionHeader(PayApp.current.label())
    PlaceGroup(listOf(
        Place(T("Payment cards", "אמצעי תשלום"), Icons.Rounded.CreditCard, RavPass.paymentCards),
        Place(T("Profile", "אזור אישי"), Icons.Rounded.Person, RavPass.profile),
        Place(T("Discount profile", "פרופיל הנחה"), Icons.Rounded.Percent, RavPass.discountProfile),
    ))
    SectionHeader(T("More from ${PayApp.current.label()}", "עוד ${PayApp.current.heIn}"))
    PlaceGroup(listOf(
        // Under More: Rav-Pass's picker closes itself at once for a rider with only a private account (10.2.30's
        // AccountSelectionFragment picks it and goes back), so it's for riders with a business account too.
        Place(T("Choose account", "בחירת חשבון"), Icons.Rounded.SwitchAccount, RavPass.chooseAccount),
        Place(T("Passengers", "נוסעים נוספים"), Icons.Rounded.Groups, RavPass.passengers),
        Place(T("Coupons", "קופונים"), Icons.Rounded.LocalOffer, RavPass.coupons),
        Place(T("Top up a Rav-Kav", "טעינת רב-קו"), Icons.Rounded.Wallet, RavPass.ravKavTopUp),
        Place(T("Support", "שירות לקוחות"), Icons.Rounded.SupportAgent, RavPass.support),
    ))
    SectionHeader(T("In Kav", "ב-Kav"))
    var clearing by remember { mutableStateOf(false) }
    // Kav's own for "pay.history.clear": the question, while this page is open.
    SideEffect { uk.noammm.kav.ext.Actions.own("pay.history.clear") { _, _ -> if (TicketStore.history.isNotEmpty()) clearing = true } }
    val live = liveTickets()
    ListGroup {
        if (TicketView.supported()) row {
            ListRow(T("Ride history", "היסטוריית נסיעות"),
                subtitle = TicketStore.history.size.takeIf { it > 0 }?.let { T.count(it, "1 ride", "נסיעה אחת", "rides", "נסיעות") },
                leading = { Icon(Icons.Rounded.History, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(22.dp)) },
                onClick = { PayHistory.request("settings") })
        }
        if (TicketView.supported() && live.isNotEmpty()) row { LiveTicketsRow() }
        if (TicketStore.history.isNotEmpty()) row {
            ListRow(T("Clear history", "מחיקת ההיסטוריה"), titleColor = K.critical,
                leading = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = K.critical, modifier = Modifier.size(22.dp)) },
                onClick = {
                    if (!uk.noammm.kav.ext.Actions.take(ctx, "pay.history.clear") { org.json.JSONObject().put("rides", TicketStore.history.size) }) clearing = true
                }, chevron = false)
        }
    }
    RavPassUpdatedLine(home = false)
    if (clearing) PageSheet({ clearing = false }) { close ->
        Text(T("Clear the ride history?", "למחוק את היסטוריית הנסיעות?"), style = K.type.title, color = K.text)
        Spacer(Modifier.height(K.gap4))
        KavButton(T("Clear", "מחיקה"), onClick = { close { clearing = false; TicketStore.clearHistory(ctx) } },
            style = ButtonStyle.Destructive, wide = true)
        Spacer(Modifier.height(K.gap2))
        KavButton(T("Cancel", "ביטול"), onClick = { close { clearing = false } }, style = ButtonStyle.Secondary, wide = true)
    }
}

// A sheet from inside a scrolling Settings page: over the whole screen, as FareProfileChoice's.
@Composable
private fun PageSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit) =
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) { BottomSheet(onDismiss, content = content) }

// Which app paying goes through: Rav-Pass, or Egged's egg, the same HopOn platform in Egged's branding. Switching
// keeps the permissions (they are Kav's own) and re-checks the phone: an app that isn't installed is the setup popup's
// first step. The tickets and ride history already read stay.
@Composable
internal fun PayAppChoice() = ListGroup { row { PayAppRow() } }

@Composable
private fun PayAppRow() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    ListRow(T("Payment app", "אפליקציית התשלום"), subtitle = PayApp.current.label(),
        leading = { Icon(Icons.Rounded.Wallet, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(22.dp)) },
        onClick = { open = true })
    if (open) PageSheet({ open = false }) { close ->
        Text(T("Payment app", "אפליקציית התשלום"), style = K.type.title, color = K.text)
        Spacer(Modifier.height(K.gap2))
        Text(T("Pay buttons open the app you choose. Both work the same in Kav.",
            "כפתורי התשלום פותחים את האפליקציה שתבחרו. שתיהן עובדות באותה צורה ב-Kav."),
            style = K.type.body, color = K.textSecondary)
        Spacer(Modifier.height(K.gap4))
        for (app in PayApp.entries) {
            ListRow(app.label(),
                trailing = { androidx.compose.material3.RadioButton(app == PayApp.current, onClick = null) },
                onClick = {
                    PayApp.choose(ctx, app)
                    PayMode.refresh(ctx)
                    uk.noammm.kav.PayEntry.sync(ctx)
                    close { open = false }
                }, chevron = false)
        }
    }
}

// A Rav-Pass place opened by a tap, by name ("pay.place.open").
private fun openPlace(ctx: Context, o: RavPass.Open) {
    if (!uk.noammm.kav.ext.Actions.take(ctx, "pay.place.open") { org.json.JSONObject().put("place", RavPass.placeName(o)) }) RavPass.place(ctx, o)
}

// Rav-Pass's own places, each a row that opens it there, with the mark for opening outside Kav.
private data class Place(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val open: RavPass.Open)

@Composable
private fun PlaceGroup(places: List<Place>) {
    val ctx = LocalContext.current
    ListGroup {
        for ((label, icon, o) in places) row {
            ListRow(label,
                leading = { Icon(icon, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(22.dp)) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = K.textTertiary, modifier = Modifier.size(18.dp)) },
                onClick = { openPlace(ctx, o) }, chevron = false)
        }
    }
}

// The old pay rows' code mark: three corner squares and two dots, in the accent.
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawQr() {
    val w = size.width
    for ((x, y) in listOf(.10f to .10f, .58f to .10f, .10f to .58f)) {
        drawRoundRect(K.accent, androidx.compose.ui.geometry.Offset(w * x, w * y), androidx.compose.ui.geometry.Size(w * .32f, w * .32f),
            androidx.compose.ui.geometry.CornerRadius(w * .06f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * .08f))
    }
    drawRect(K.accent, androidx.compose.ui.geometry.Offset(w * .62f, w * .62f), androidx.compose.ui.geometry.Size(w * .12f, w * .12f))
    drawRect(K.accent, androidx.compose.ui.geometry.Offset(w * .80f, w * .80f), androidx.compose.ui.geometry.Size(w * .10f, w * .10f))
}
