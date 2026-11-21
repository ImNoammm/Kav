package uk.noammm.kav

import uk.noammm.kav.ui.FareFromRavPass
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import uk.noammm.kav.data.Ticket
import uk.noammm.kav.data.TicketScreens
import uk.noammm.kav.data.Tickets
import uk.noammm.kav.data.Ride
import uk.noammm.kav.data.RideHistory
import uk.noammm.kav.ui.PayOverlay
import uk.noammm.kav.ui.TicketView
import java.io.File
import java.util.concurrent.Executors

// Kav's ticket reader: an accessibility service Android lets watch Rav-Pass alone (res/xml/ticket_reader.xml), turned on
// by the rider after Kav's own sheet says what it does (PaySetup). Right after a purchase it saw, it taps Rav-Pass's
// inspection drawer open once to read the code (an accessibility click on that bar alone, Noam's yes of 19 Nov); it never
// taps anything else, types, scrolls or gestures in Rav-Pass. On a screen that isn't one of Rav-Pass's ticket screens (TicketScreens, by view ids) it reads no
// text, keeps nothing and takes no picture. On a ticket screen it reads the cards' texts and takes a picture (of Rav-Pass's
// window alone from Android 14), crops it to each code, decodes the code on the phone and lets the picture go at once:
// Kav keeps what the code says, never an image of Rav-Pass's screen.
class TicketReader : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var busySince = 0L

    override fun onServiceConnected() {
        running = this
        before = null
        runCatching { TicketStore.load(this); schedulePrune() }
    }

    override fun onDestroy() { if (running === this) running = null; main.removeCallbacksAndMessages(null); work.shutdown(); super.onDestroy() }

    override fun onInterrupt() {}

    // Rav-Pass's screen changing: looked at 200 ms after its last change, and at the latest half a second after the first
    // while it keeps changing (its success animation), so neither a settling screen nor a moving one keeps it waiting.
    private var pendingSince = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != TicketScreens.PACKAGE || !Prefs.tickets(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.currentTimeMillis()
        if (pendingSince == 0L) pendingSince = now
        main.removeCallbacks(look)
        main.postDelayed(look, minOf(200L, (pendingSince + 500 - now).coerceAtLeast(0)))
    }

    private fun lookAgain(ms: Long) { main.removeCallbacks(look); main.postDelayed(look, ms) }

    private val look = Runnable {
        pendingSince = 0L
        // A picture that never came back doesn't leave the reader stuck.
        val busy = busySince != 0L && System.currentTimeMillis() - busySince < 5_000
        if (!busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { lookNow() }.onFailure { busySince = 0L }
    }

    // Timing only (step names and milliseconds since the Pay tap, never anything read), in a build made with
    // -PkavTicketTiming=true; release builds have it off.
    private fun timing(step: String) {
        if (BuildConfig.TICKET_TIMING) android.util.Log.d("KavTicketTiming", "$step +${System.currentTimeMillis() - TicketStore.payTapped(this)} ms")
    }

    // Only HopOn's own Rav-Pass is read (a debug build reads Kav's test stand-in too).
    @RequiresApi(Build.VERSION_CODES.R)
    private fun genuine(): Boolean = BuildConfig.DEBUG || runCatching {
        packageManager.hasSigningCertificate(TicketScreens.PACKAGE, TicketScreens.SIGNER.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            PackageManager.CERT_INPUT_SHA256)
    }.getOrDefault(false)

    // The code areas last seen, since when, and how many pictures they've had: a code is read once it has held still and
    // whole for a moment (not a drawer still sliding open), and tried again a few times if it doesn't decode.
    private var codeAreas: List<List<Int>>? = null
    private var codeAreasSince = 0L
    private var tries = 0
    private var cutLooks = 0
    private var readAreas: List<List<Int>>? = null

    @RequiresApi(Build.VERSION_CODES.R)
    private fun lookNow() {
        val root = rootInActiveWindow
        if (root == null || root.packageName != TicketScreens.PACKAGE || !genuine() || !TicketStore.on(this)) { noTicketSince = 0L; return }
        val viewed = Viewed(root)
        timing("look")
        // 1. A purchase: Rav-Pass's Done screen seen to appear after another of its screens. No code needed for it.
        // Only looks with Rav-Pass's window focused count: while another app is starting over it, its window can still be
        // the active one in a state between screens (seen on the emulator: a Done screen then "appeared" twice).
        val front = inFront(root)
        // 0. Sent by "Get it from Rav-Pass" (FareFromRavPass): its Profile tab's profile name, and nothing else of it.
        if (front && FareFromRavPass.asked(this)) readFare(viewed)
        val seen = if (front) TicketScreens.seen(viewed) else TicketScreens.Seen.NONE
        if (front) backFromPlace(viewed)
        if (seen == TicketScreens.Seen.INSPECT) inspectedAt = System.currentTimeMillis()
        // Not right after the drawer's list was open: its "Show ticket" reopens the Done screen of a ticket already bought
        // (Rav-Pass has two ways into its Done screen: that button, and the approve screen of a real purchase). The Done
        // screen straight after the open list is never a purchase (before is INSPECT); the wait covers a look in between
        // that caught Rav-Pass mid-change. It is short on purpose: a real purchase can't be scanned with the list open
        // over the camera, and closing it, scanning, approving and Rav-Pass's own answer take longer than this.
        val witnessed = TicketScreens.witnessed(before, seen) && System.currentTimeMillis() - inspectedAt > INSPECT_MS
        if (seen != TicketScreens.Seen.NONE) before = seen
        // The fare's reach on the Approve screen, held in memory for the purchase that follows it.
        if (front) TicketScreens.band(viewed)?.let { band = it; bandAt = System.currentTimeMillis() }
        if (front) TicketScreens.price(viewed)?.let { price = it; priceAt = System.currentTimeMillis() }
        if (witnessed) TicketScreens.done(viewed)?.let { seenDone(it) }
        // Rav-Pass's "No active ticket" bar, up and in front for NO_TICKET_MS: the tickets of that family end.
        val gone = if (front) TicketScreens.noTicket(viewed) else null
        val now0 = System.currentTimeMillis()
        if (gone == null) noTicketSince = 0L
        else if (noTicketSince == 0L) { noTicketSince = now0; lookAgain(NO_TICKET_MS) }
        else if (now0 - noTicketSince < NO_TICKET_MS) lookAgain(NO_TICKET_MS - (now0 - noTicketSince))
        else { TicketStore.end(this, gone, noTicketSince); schedulePrune() }
        // Sent here by a ticket's "Open in Rav-Pass": its inspection drawer opened for the rider, once (Noam, 19 Nov).
        val asked = TicketStore.showAsked
        if (front && asked > 0 && System.currentTimeMillis() - asked < SHOW_MS && TicketScreens.handle(viewed) != null) tapDrawer("show:$asked")
        // 2. A code: the inspection drawer's list open with a code on screen, on the Done screen or the scan screen.
        // While a purchase waits for its code after the reader's tap, it keeps looking on its own (Rav-Pass's events
        // alone left gaps of over a second on the emulator).
        if (waiting != null) lookAgain(STILL_MS)
        val screen = TicketScreens.read(viewed) ?: run { codeAreas = null; readAreas = null; return }
        val areas = screen.cards.map { it.code!!.toList() }
        if (areas == readAreas) return
        val now = System.currentTimeMillis()
        if (areas != codeAreas) {
            codeAreas = areas; codeAreasSince = now; tries = 0; cutLooks = 0; timing("code-on-screen")
            // The wait for a purchase's code counts from when the code shows, however slowly Rav-Pass's drawer opened.
            if (waiting != null) { main.removeCallbacks(deadline); main.postDelayed(deadline, CODE_WAIT_MS) }
            lookAgain(STILL_MS); return
        }
        // Whole: a code is square; one cut by the screen's edge or a half-open drawer isn't.
        // A few looks more, then only Rav-Pass's own next change: a code that stays cut (scrolled half out of sight)
        // isn't looked at three times a second for as long as it's there.
        if (screen.cards.any { c -> val b = c.code!!; kotlin.math.abs((b[2] - b[0]) - (b[3] - b[1])) > 4 }) { if (++cutLooks < 10) lookAgain(STILL_MS); return }
        cutLooks = 0
        if (now - codeAreasSince < STILL_MS) { lookAgain(STILL_MS - (now - codeAreasSince)); return }
        // Ten pictures that didn't decode: no more of this code until it changes.
        if (tries >= 10) return
        // Rav-Pass's own window in front, not under the shade or anything else: else no picture.
        if (!inFront(root)) { timing("not-in-front"); lookAgain(500); return }
        busySince = now
        timing("picture-asked")
        picture(root, screen) { codes ->
            busySince = 0L
            timing("picture-decoded ${codes.count { it != null }}/${codes.size}")
            runCatching {
                if (!TicketStore.on(this)) return@runCatching
                val read = Ticket.from(screen, codes, System.currentTimeMillis())
                if (read == null) { if (++tries < 10) lookAgain(500); return@runCatching }
                readAreas = areas
                val (stored, new) = TicketStore.add(this, read) ?: return@runCatching
                timing(if (new) "code-kept-new" else "code-kept-with-its-ticket")
                schedulePrune()
                // A group with passengers' codes still to read: Rav-Pass's list of cards moved on to the next one by itself
                // (the accessibility scroll action, no gesture), until every code is read or no card more can be reached.
                if (!stored.complete && pages < stored.expected - 1 && scrollCards(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                    pages++; timing("scrolled-to-card ${pages + 1}")
                    codeAreas = null; readAreas = null
                    if (stored.key == waiting) { main.removeCallbacks(deadline); main.postDelayed(deadline, CODE_WAIT_MS) }
                    lookAgain(STILL_MS)
                    return@runCatching
                }
                scrollBack()
                // The purchase waiting for its code before the rider is brought back: now, code and all.
                if (stored.key == waiting) {
                    waiting = null; main.removeCallbacks(deadline)
                    PayOverlay.reading = PayOverlay.Reading.GOT
                    returnIfStillThere(stored.key)
                }
            }
        }
    }

    // Only within FareFromRavPass.WAIT_MS of "Get it from Rav-Pass", only on Rav-Pass's Profile tab (its profile type and
    // that row's title both there): the text of its profile type alone. Rav-Pass fills it in once its server answers, so
    // an empty one is looked at again for a few seconds before it counts as no profile. Then the rider is brought back.
    private var fareEmptySince = 0L

    private fun readFare(viewed: Viewed) {
        if (find(viewed, FareFromRavPass.PROFILE_TYPE_TITLE) == null) return
        val type = find(viewed, FareFromRavPass.PROFILE_TYPE) ?: return
        val text = type.text?.trim().orEmpty()
        val now = System.currentTimeMillis()
        if (text.isEmpty()) {
            if (fareEmptySince == 0L) fareEmptySince = now
            if (now - fareEmptySince < FARE_EMPTY_MS) { lookAgain(500); return }
        }
        fareEmptySince = 0L
        FareFromRavPass.done(this, uk.noammm.kav.data.RavPassProfiles.read(text))
        back(this, null)
    }

    // A view by its id, by ids alone: no text is asked for on the way.
    private fun find(n: TicketScreens.Node, id: String): TicketScreens.Node? =
        if (n.id == id) n else n.children.firstNotNullOfOrNull { find(it, id) }

    // The last kind of Rav-Pass screen looked at; null until the first look (a Done screen already up isn't witnessed).
    private var before: TicketScreens.Seen? = null
    private var inspectedAt = 0L
    private var band: Int? = null
    private var bandAt = 0L
    private var price: Int? = null
    private var priceAt = 0L
    private var noTicketSince = 0L

    // Rav-Pass's Done screen seen to appear: the purchase Kav saw; for a bus, after a hand-off from Kav, back to Kav two
    // seconds on.
    // Rav-Pass's Done screen seen to appear: the purchase Kav saw. Then the reader taps its inspection drawer open (once,
    // that bar alone) so the code is read; for a bus, after a hand-off from Kav, the rider comes back once the code is
    // kept, or after CODE_WAIT_MS without it (the ticket then says its code is in Rav-Pass).
    private var waiting: String? = null
    private var deadline = Runnable {}

    private fun seenDone(d: TicketScreens.Done) {
        val now = System.currentTimeMillis()
        timing("done-seen")
        val reach = band.takeIf { now - bandAt < BAND_MS }
        val quoted = price.takeIf { now - priceAt < BAND_MS }
        band = null; price = null; pages = 0
        // The drawer tapped open first, before the purchase is kept (saving it brings Kav's shortcuts and notification up
        // to date, which took the main thread 0.4 s on the emulator): its code is what the rider waits for.
        val bought = Ticket.purchase(d, now, reach, quoted)
        if (TicketStore.on(this) && bought.overAt > now) runCatching { tapDrawer(bought.key) }
        val (stored, new) = TicketStore.add(this, bought) ?: return
        schedulePrune()
        if (!new) return
        if (Tickets.handedOff(now, TicketStore.payTapped(this)) && Tickets.returns(stored, new)) {
            waiting = stored.key
            // The button over Rav-Pass says Kav has it, until the code is kept (PayOverlay.reading).
            PayOverlay.reading = PayOverlay.Reading.READING
            main.removeCallbacks(deadline)
            deadline = Runnable {
                if (waiting != stored.key) return@Runnable
                // A picture already on its way is waited for (a little): the code is that close.
                if (busySince != 0L && System.currentTimeMillis() - busySince < PICTURE_WAIT_MS) { main.postDelayed(deadline, 200); return@Runnable }
                waiting = null; timing("code-wait-over"); PayOverlay.reading = PayOverlay.Reading.NO; scrollBack()
                runCatching { returnIfStillThere(stored.key) }
            }
            // Counted again from the tap itself once it's made (tapDrawer).
            main.postDelayed(deadline, CODE_WAIT_MS)
        }
    }

    // The places of Rav-Pass's no-go views on the screen in front (TicketScreens.NO_GO): their bounds only, no text.
    fun noGo(): List<IntArray> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName != TicketScreens.PACKAGE) return emptyList()
        return TicketScreens.NO_GO.flatMap { id ->
            root.findAccessibilityNodeInfosByViewId("${TicketScreens.PACKAGE}:id/$id").filter { it.isVisibleToUser }
                .map { n -> Rect().also { n.getBoundsInScreen(it) }.let { intArrayOf(it.left, it.top, it.right, it.bottom) } }
        }
    }

    // One Back from a Rav-Pass place Kav opened goes back to Kav. Discount profile, Passengers, Coupons, Support and
    // Payment cards open over Rav-Pass's Profile tab (10.2.30's C1571d0 navigates there first), so Back would first show that
    // tab and need a second press: once the screen Kav opened has been seen, the Profile tab coming up after it (Back, or
    // Rav-Pass's own close) brings Kav to the front instead. Home never shows the tab, so it's never mistaken for Back.
    private var sawPlace = false

    private fun backFromPlace(viewed: Viewed) {
        val at = placeOpenedAt
        if (at == 0L || System.currentTimeMillis() - at > PLACE_MS) { sawPlace = false; return }
        val tab = TicketScreens.profileTab(viewed)
        if (!tab) { sawPlace = true; return }
        if (!sawPlace) return
        placeOpenedAt = 0L; sawPlace = false
        timing("back-from-place")
        back(this, null)
    }

    // How many cards of a group the reader has moved Rav-Pass's list on by, to put it back as it was (scrollBack).
    private var pages = 0

    private fun scrollCards(action: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName != TicketScreens.PACKAGE || !inFront(root)) return false
        val list = TicketScreens.LISTS.asSequence()
            .flatMap { root.findAccessibilityNodeInfosByViewId("${TicketScreens.PACKAGE}:id/$it").asSequence() }
            .firstOrNull { it.isScrollable } ?: return false
        return list.performAction(action)
    }

    // Back to the group's first card, once its codes are read or no more can be.
    private fun scrollBack() {
        while (pages > 0 && scrollCards(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) pages--
        pages = 0
    }

    // The one tap the reader makes: the drawer's bar, an accessibility click (no gesture), once per purchase, only with
    // Rav-Pass's window focused and its drawer still closed. If the bar itself takes no click, the drawer's own handle.
    private var tappedFor: String? = null

    private fun tapDrawer(key: String) {
        if (tappedFor == key) return
        val root = rootInActiveWindow ?: return
        if (root.packageName != TicketScreens.PACKAGE || !inFront(root) || !TicketStore.on(this)) return
        val bar = (TicketScreens.handle(Viewed(root)) as? Viewed)?.n ?: run { timing("tap-no-drawer"); return }
        tappedFor = key
        val target = if (bar.isClickable) bar else bar.findAccessibilityNodeInfosByViewId("${TicketScreens.PACKAGE}:id/card_validity_open_close")
            .firstOrNull { it.isClickable } ?: (0 until bar.childCount).mapNotNull { bar.getChild(it) }.firstOrNull { it.isClickable }
        val tapped = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        timing(if (tapped) "tapped-drawer" else "tap-refused")
        if (tapped) {
            if (waiting == key) { main.removeCallbacks(deadline); main.postDelayed(deadline, CODE_WAIT_MS) }
            lookAgain(400)
        }
    }

    // Only if Rav-Pass is still what is in front, on its Done or ticket screen: never over another screen or app.
    // Looked at twice, CONFIRM_MS apart: an app the rider opened a moment before can still be starting, with Rav-Pass
    // in front until it has drawn; the second look sees it then. (An app slower than that still isn't caught.)
    private fun returnIfStillThere(key: String, confirmed: Boolean = false) {
        val root = rootInActiveWindow ?: return
        if (root.packageName != TicketScreens.PACKAGE || !inFront(root)) { timing("no-return-not-in-front"); return }
        val viewed = Viewed(root)
        if (TicketScreens.done(viewed) == null && TicketScreens.read(viewed) == null) return
        if (!TicketStore.on(this) || TicketStore.returnedBefore(key)) return
        if (!confirmed) { main.postDelayed({ runCatching { returnIfStillThere(key, true) } }, CONFIRM_MS); return }
        TicketStore.markReturned(this, key)
        timing("back-to-kav")
        back(this, key)
    }

    // Whether Rav-Pass's own window has the focus: Android's notification shade, a call's screen or a dialog over it takes
    // the focus while Rav-Pass's window can still be the "active" one. Only Rav-Pass's window is asked; unknown is no.
    private fun inFront(root: AccessibilityNodeInfo?): Boolean =
        runCatching { root?.window.let { Tickets.frontApp(it?.isFocused, it?.type) } }.getOrDefault(false)

    // What is kept is deleted when each ticket ends, without Kav being opened: the service runs while it's on.
    private val pruner = Runnable { runCatching { TicketStore.prune(this); schedulePrune() } }

    private fun schedulePrune() {
        main.removeCallbacks(pruner)
        val next = TicketStore.kept.minOfOrNull { it.overAt } ?: return
        main.postDelayed(pruner, (next - System.currentTimeMillis()).coerceAtLeast(0) + 1_000)
    }

    // The picture, each card's code cut out of it and decoded, and the picture let go. From Android 14, of Rav-Pass's window
    // alone; before, of the display, and kept only if Rav-Pass's same ticket screen is still in front when it arrives.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun picture(root: AccessibilityNodeInfo, screen: TicketScreens.Screen, done: (List<Pair<String, String>?>) -> Unit) {
        val none = screen.cards.map { null }
        val callback = object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                timing("picture-got")
                val offset = if (Build.VERSION.SDK_INT >= 34) Rect().also { root.window?.getBoundsInScreen(it) } else Rect()
                val codes = runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle(); shot.hardwareBuffer.close()
                    val read = screen.cards.map { card ->
                        soft?.let { decode(it, card.code!!.let { b -> intArrayOf(b[0] - offset.left, b[1] - offset.top, b[2] - offset.left, b[3] - offset.top) }) }
                    }
                    soft?.recycle()
                    read
                }.getOrElse { runCatching { shot.hardwareBuffer.close() }; none }
                timing("picture-read")
                main.post {
                    // Below Android 14 the picture is of the whole display: only if the same ticket screen is still in front.
                    // Still Rav-Pass's focused window when the picture came (nothing came over it meanwhile); below Android 14,
                    // whose picture is of the whole display, also the same ticket screen.
                    val same = runCatching {
                        val now = rootInActiveWindow
                        now?.packageName == TicketScreens.PACKAGE && inFront(now) && (Build.VERSION.SDK_INT >= 34 ||
                            TicketScreens.read(Viewed(now))?.cards?.map { it.code?.toList() } == screen.cards.map { it.code?.toList() })
                    }.getOrDefault(false)
                    done(if (same) codes else none)
                }
            }

            override fun onFailure(errorCode: Int) { main.post { done(none) } }
        }
        if (Build.VERSION.SDK_INT >= 34) takeScreenshotOfWindow(root.windowId, work, callback)
        else takeScreenshot(Display.DEFAULT_DISPLAY, work, callback)
    }

    // The window as TicketScreens reads it: ids and bounds for anyone, text only when asked, and only views on screen.
    private class Viewed(val n: AccessibilityNodeInfo) : TicketScreens.Node {
        override val id: String? get() = n.viewIdResourceName
        override val text: String? get() = n.text?.toString()
        override val bounds: IntArray get() = Rect().also { n.getBoundsInScreen(it) }.let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        // A list going down counts its items in rows, one going across (a pager of passengers) in columns.
        override val itemCount: Int? get() = n.collectionInfo?.let { maxOf(it.rowCount, it.columnCount) }?.takeIf { it > 0 }
        override val children: List<TicketScreens.Node> by lazy {
            (0 until n.childCount).mapNotNull { n.getChild(it)?.takeIf { c -> c.isVisibleToUser }?.let(::Viewed) }
        }
    }

    companion object {
        // The second look before Kav comes back (returnIfStillThere).
        const val CONFIRM_MS = 400L
        // The tap a moment after the Done screen is seen; the code given this long to be read before the rider comes back.
        // How long a code's place must hold still before its picture (not a drawer still sliding open).
        const val STILL_MS = 150L
        const val CODE_WAIT_MS = 3_000L
        const val PICTURE_WAIT_MS = 2_000L
        const val INSPECT_MS = 1_500L
        // How long the Approve screen's fare reach waits for its Done screen; how long "No active ticket" must stay up.
        const val BAND_MS = 5 * 60_000L
        const val NO_TICKET_MS = 5_000L
        // How long after a ticket's "Open in Rav-Pass" the reader opens the drawer, as Rav-Pass comes up.
        const val SHOW_MS = 15_000L
        // How long Rav-Pass's profile type may stay empty before it counts as no profile.
        const val FARE_EMPTY_MS = 4_000L
        const val EXTRA_TICKET = "uk.noammm.kav.TICKET"

        @Volatile var running: TicketReader? = null
        // When Kav last opened one of Rav-Pass's places that sit over its Profile tab (RavPass.place), for backFromPlace.
        @Volatile var placeOpenedAt = 0L
        const val PLACE_MS = 10 * 60_000L

        // A code cut out of the picture (with a margin, as a reader needs its quiet zone), decoded: what it says and its kind.
        internal fun decode(screen: Bitmap, b: IntArray): Pair<String, String>? = runCatching {
            val m = 12
            val l = (b[0] - m).coerceAtLeast(0); val t = (b[1] - m).coerceAtLeast(0)
            val r = (b[2] + m).coerceAtMost(screen.width); val btm = (b[3] + m).coerceAtMost(screen.height)
            val cut = Bitmap.createBitmap(screen, l, t, r - l, btm - t)
            val px = IntArray(cut.width * cut.height).also { cut.getPixels(it, 0, cut.width, 0, 0, cut.width, cut.height) }
            val w = cut.width; val h = cut.height
            cut.recycle()
            decodePixels(px, w, h)
        }.getOrNull()

        // A quick pass first, as a code drawn sharp on a screen needs; the slower thorough one only when that fails.
        private fun read(px: IntArray, w: Int, h: Int): Result? = read(px, w, h, false) ?: read(px, w, h, true)

        private fun read(px: IntArray, w: Int, h: Int, harder: Boolean): Result? = runCatching {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, h, px))), buildMap {
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC))
                if (harder) put(DecodeHintType.TRY_HARDER, true)
            })
        }.getOrNull()

        @Suppress("UNCHECKED_CAST")
        private fun bytes(r: Result): List<Byte>? =
            (r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>)?.flatMap { it.toList() }

        // What a reader gets from the code: its text, its bytes, and whether it declares a character set.
        private fun same(a: Result, b: Result?) = b != null && a.barcodeFormat == b.barcodeFormat && a.text == b.text &&
            bytes(a) == bytes(b) && a.resultMetadata?.get(ResultMetadataType.SYMBOLOGY_IDENTIFIER) == b.resultMetadata?.get(ResultMetadataType.SYMBOLOGY_IDENTIFIER)

        internal fun pixels(m: BitMatrix, cell: Int = 6): Triple<IntArray, Int, Int> {
            val quiet = 4 * cell
            val w = m.width * cell + 2 * quiet; val h = m.height * cell + 2 * quiet
            val px = IntArray(w * h) { -1 }
            for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y))
                for (dy in 0 until cell) for (dx in 0 until cell) px[(quiet + y * cell + dy) * w + quiet + x * cell + dx] = 0xFF000000.toInt()
            return Triple(px, w, h)
        }

        // The code read, as the content and kind Kav redraws it from: the first way of writing it that a reader reads back
        // exactly as it read the original (the same text, the same bytes, the same character-set mark). When none does,
        // the kind ends in "!", and Kav doesn't draw it but sends the rider to Rav-Pass's own.
        internal fun decodePixels(px: IntArray, w: Int, h: Int): Pair<String, String>? = runCatching {
            val r = read(px, w, h) ?: return null
            val f = r.barcodeFormat.name
            val raw = bytes(r)?.toByteArray()?.let { String(it, Charsets.ISO_8859_1) }
            val ways = listOfNotNull(r.text to f, r.text to "$f/UTF-8", raw?.let { it to f }).distinct()
            ways.firstOrNull { (content, kind) ->
                val m = TicketView.matrix(content, kind) ?: return@firstOrNull false
                val (p, pw, ph) = pixels(m)
                same(r, read(p, pw, ph))
            } ?: (r.text to "$f!")
        }.getOrNull()

        // Kav's own screen to the front of its task, opened on the ticket (or as it was left, for none); nothing is pressed
        // in Rav-Pass.
        fun back(ctx: Context, key: String?) {
            runCatching {
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .apply { key?.let { putExtra(EXTRA_TICKET, it) } })
            }
        }
    }
}

// The tickets read, kept in Kav's no-backup storage only, and dropped once over.
internal object TicketStore {
    var kept by mutableStateOf<List<Ticket>>(emptyList())
        private set
    // The ticket Kav was brought back to show (TicketReader.back), until its sheet is closed.
    var opened by mutableStateOf<String?>(null)
    // When the rider last tapped a ticket's "Open in Rav-Pass": the reader opens Rav-Pass's inspection drawer for them.
    @Volatile var showAsked = 0L
    private var loaded = false
    // When a Pay tap last opened Rav-Pass, kept in Kav's prefs so it outlives Kav's process.
    fun payTapped(ctx: Context): Long = Prefs.payTappedAt(ctx)

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, "rav-pass-tickets.json")

    // The rides of the tickets kept, for the ride history (RideHistory): kept after their tickets end, until paying is turned
    // off or the rider clears them.
    var history by mutableStateOf<List<Ride>>(emptyList())
        private set
    private fun historyFile(ctx: Context) = File(ctx.noBackupFilesDir, "ride-history.json")

    fun on(ctx: Context): Boolean = Tickets.readerOn(
        Prefs.tickets(ctx),
        android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        ctx.packageName, TicketReader::class.java.name,
    )

    // A ticket read is kept until it ends or the rider turns off "Pay with Rav-Pass" (turnTicketsOff); its ride stays in the
    // history after it ends. The reader switched off
    // by Android (a force stop) or in its accessibility settings only stops new readings (coordinator, 19 Nov 00:20).
    fun load(ctx: Context) {
        if (loaded) return prune(ctx)
        loaded = true
        val text = runCatching { file(ctx).readText() }.getOrNull()
        val read = Tickets.list(text)
        kept = Tickets.current(Tickets.fold(Tickets.live(read, System.currentTimeMillis())))
        history = RideHistory.list(runCatching { historyFile(ctx).readText() }.getOrNull())
        // Tickets kept before there was a history: their rides begin it.
        if (!historyFile(ctx).exists() && kept.isNotEmpty()) { history = kept.fold(history, RideHistory::record); runCatching { saveHistory(ctx) } }
        // A file that doesn't read is left as it is, never replaced by nothing at once.
        if (text == null || read.isNotEmpty() || text.isBlank()) save(ctx)
    }

    fun prune(ctx: Context) {
        val live = Tickets.live(kept, System.currentTimeMillis())
        if (live.size != kept.size) { kept = live; save(ctx) }
    }

    // A ticket read: kept (with "paid at" only for a purchase Kav saw), and whether it's new. Null when nothing was kept.
    fun add(ctx: Context, read: Ticket): Pair<Ticket, Boolean>? {
        load(ctx)
        if (!on(ctx)) return null
        val t = read
        val (all, new) = Tickets.merge(Tickets.live(kept, System.currentTimeMillis()), t)
        kept = all
        runCatching { save(ctx) }
        val stored = all.firstOrNull { it.key == t.key || it.passengers.any { p -> t.passengers.any { q -> q.code == p.code } } } ?: return null
        history = RideHistory.record(history, stored)
        runCatching { saveHistory(ctx) }
        return stored to new
    }

    // A ticket an extension sold (ExtTickets): kept as a read one is, with or without the reader, and its ride in the
    // history. The same key again replaces it. One already over isn't kept.
    fun put(ctx: Context, t: Ticket) {
        load(ctx)
        val now = System.currentTimeMillis()
        if (t.overAt <= now) return
        kept = Tickets.current(Tickets.live(kept.filter { it.key != t.key }, now) + t)
        runCatching { save(ctx) }
        history = RideHistory.record(history, t)
        runCatching { saveHistory(ctx) }
    }

    // Rav-Pass says it has no active ticket of these kinds (TicketReader).
    fun end(ctx: Context, kinds: Set<TicketScreens.Kind>, since: Long) {
        val left = Tickets.end(kept, kinds, since)
        if (left.size != kept.size) { kept = left; runCatching { save(ctx) } }
    }

    fun returnedBefore(key: String) = kept.any { it.key == key && it.returned }

    fun markReturned(ctx: Context, key: String) {
        kept = kept.map { if (it.key == key) it.copy(returned = true) else it }
        runCatching { save(ctx) }
    }

    fun erase(ctx: Context) {
        kept = emptyList()
        opened = null
        file(ctx).delete()
        history = emptyList()
        historyFile(ctx).delete()
        PayEntry.sync(ctx)
    }

    // Cleared by the rider: an empty history, kept as a file so the tickets still live don't begin it again (load).
    fun clearHistory(ctx: Context) {
        history = emptyList()
        runCatching { saveHistory(ctx) }
    }

    private fun saveHistory(ctx: Context) = uk.noammm.kav.ext.Files.replace(historyFile(ctx), RideHistory.text(history).toByteArray())

    private fun save(ctx: Context) {
        // Written beside and moved into place: Kav closed mid-write (another thread's crash, Android ending it) leaves
        // the tickets as they were, not a cut file.
        if (kept.isEmpty()) file(ctx).delete() else uk.noammm.kav.ext.Files.replace(file(ctx), Tickets.text(kept).toByteArray())
        runCatching { scheduleEnd(ctx.applicationContext) }
        // After what the reader is doing now (its drawer tap, its picture): the shortcuts and notification can wait a moment.
        android.os.Handler(android.os.Looper.getMainLooper()).post { PayEntry.sync(ctx) }
    }

    // Each ticket deleted when it ends even with the reader off and Kav closed: Android's alarm at the next end, to
    // TicketEnd. (After a force stop Android drops every alarm of the app until it's opened; then it goes at Kav's next
    // start, and is never shown past its end either way.)
    fun scheduleEnd(ctx: Context) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pi = android.app.PendingIntent.getBroadcast(ctx, 0, Intent(ctx, TicketEnd::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val next = kept.minOfOrNull { it.overAt }
        if (next == null) am.cancel(pi) else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, next + 1_000, pi)
    }
}

// A ticket's end (TicketStore.scheduleEnd), a restart or an update: the ended ones deleted, the next end set again.
class TicketEnd : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        runCatching { TicketStore.load(ctx); TicketStore.prune(ctx); TicketStore.scheduleEnd(ctx.applicationContext) }
    }
}
