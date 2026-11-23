package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

// A ticket the rider bought in Rav-Pass, as Kav read it off Rav-Pass's own ticket screen (TicketReader), with the rider's
// yes and on this phone only. The one place Kav shows a ride as paid: "paid at" only for a purchase it saw happen
// (Rav-Pass's Done screen right after a Pay tap in Kav, Tickets.purchase), a code only as Rav-Pass's drawer showed it.

// THE matching rules: Rav-Pass's ticket screens by their view ids, nothing else. When Rav-Pass updates, check this file
// against its layouts. Verified against Rav-Pass 10.2.16 and 10.2.30 (layouts ipsdk_fragment_done_ticket,
// ipsdk_fragment_train_entrance, ipsdk_fragment_train_exit, ipsdk_fragment_other_trains_entrance and the boarding pass
// list items; kav-payment-research/RAVPASS-TICKET.md). A screen that doesn't match exactly is not a ticket screen.
internal object TicketScreens {
    // The app whose ticket screens are read: the rider's chosen payment app (PayApp). Rav-Pass and egg are the same
    // HopOn platform, so the screens are the same and only the package (the view ids' prefix), the signer and the
    // checked versions differ.
    val PACKAGE: String get() = uk.noammm.kav.ui.PayApp.current.pkg
    const val VERIFIED = "Rav-Pass 10.2.16, 10.2.30; egg assumed the same HopOn build"
    // The same versions as the app names them (its versionName): another one says Kav may not read its tickets.
    val VERSIONS: Set<String> get() = uk.noammm.kav.ui.PayApp.current.versions
    // The chosen app's signing certificate (SHA-256, from its links host's assetlinks.json): only that app is read.
    val SIGNER: String get() = uk.noammm.kav.ui.PayApp.current.signer

    enum class Kind { BUS, TRAIN_ENTRANCE, TRAIN_EXIT, LIGHT_RAIL }

    // What a reader of the screen hands over: a view's id (as "co.hopon.client:id/name"), its text and its bounds, and
    // for a list how many items it says it has. The text is asked for only once a screen is recognised as a ticket screen.
    interface Node {
        val id: String?
        val text: String?
        val bounds: IntArray // left, top, right, bottom on the screen
        val children: List<Node>
        val itemCount: Int? get() = null
    }

    data class Card(val code: IntArray?, val number: String?, val passenger: String?, val title: String?, val time: String?)
    // The cards on screen with a code, and how many tickets the screen holds as far as Kav can tell: the cards it sees,
    // or the list's own count where that is larger (a card scrolled out of sight).
    data class Screen(val kind: Kind, val cards: List<Card>, val expected: Int = cards.size)

    private val PREFIX: String get() = "$PACKAGE:id/"
    private val CARDS = setOf("boarding_pass_bus_card", "boarding_pass_train_card")
    private val CODES = setOf("boarding_pass_qr", "boarding_pass_train_qr", "boarding_pass_train")

    // Where Kav's button over Rav-Pass may never rest (PayOverlay.clearY): the bus scanner's camera frame, every ticket
    // code, and the top bar's buttons (ipsdk_fragment_scan_barcode_scanner_card_view, ipsdk_toolbar_pay, the scan screen's
    // account button), by id.
    // Rav-Pass's Profile tab (ipsdk_fragment_profile): the only screen with both its payment and coupons rows.
    fun profileTab(root: Node): Boolean {
        val ids = HashSet<String>()
        walk(root) { n -> name(n)?.let { ids += it } }
        return "payment_method_wrapper" in ids && "coupons_wrapper" in ids
    }

    val NO_GO = CODES + setOf("cv_barcode_view_wrapper", "h_barcode_scanner", "toolbar_cancel", "toolbar_search_button",
        "toolbar_menu_button", "toolbar_route_card", "account_selection_button")
    val LISTS = setOf("boarding_pass_recycler", "boarding_pass_recycler_entrance", "boarding_pass_recycler_exit")

    private fun name(n: Node) = n.id?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    private fun walk(n: Node, f: (Node) -> Unit) { f(n); n.children.forEach { walk(it, f) } }

    // A text as read, without the invisible marks that steer its direction (Rav-Pass wraps the line number in its card
    // title in right-to-left marks, "\u200f32\u200f", and not on its Done screen) or any other formatting character:
    // the same line has to read the same on both.
    fun clean(s: String?): String? = s?.filter { Character.getType(it) != Character.FORMAT.toInt() }?.trim()

    // Which ticket screen this is, by ids alone; null for every other screen (sign-in, card entry, profile, anything else).
    fun kind(root: Node): Kind? {
        val ids = HashSet<String>()
        walk(root) { n -> name(n)?.let { ids += it } }
        return when {
            // The inspection drawer open, over the Done screen or the scan screen (Rav-Pass 10.2.30 on Noam's phone): its list
            // with a bus card. Closed, the list is GONE and Android leaves it out of what a service sees.
            "boarding_pass_recycler" in ids && "boarding_pass_bus_card" in ids -> Kind.BUS
            "tv_train_arrival_station_name" in ids && "boarding_pass_recycler_exit" in ids -> Kind.TRAIN_EXIT
            "train_entrance_card_title" in ids && "boarding_pass_recycler_entrance" in ids ->
                if ("recycler_title" in ids || "success_animation_train_entrance_card" in ids) Kind.LIGHT_RAIL else Kind.TRAIN_ENTRANCE
            else -> null
        }
    }

    // Rav-Pass's Done screen, right after a ride is paid (DoneTicketFragment, or the train and light rail entrance and exit
    // screens): which ride, as it says. Ids first; its texts only once it's recognised.
    data class Done(val kind: Kind, val line: String?, val from: String?, val to: String?)

    private fun doneKind(ids: Set<String>): Kind? = when {
        "done_title" in ids && "done_scroll" in ids -> Kind.BUS
        "tv_train_arrival_station_name" in ids && "train_entrance_card_title" in ids -> Kind.TRAIN_EXIT
        "train_entrance_card_title" in ids && "train_entrance" in ids ->
            if ("recycler_title" in ids || "success_animation_train_entrance_card" in ids) Kind.LIGHT_RAIL else Kind.TRAIN_ENTRANCE
        else -> null
    }

    // What a look at Rav-Pass found, for telling a Done screen that APPEARS (a purchase or validation just made) from one
    // already showing: DONE; OTHER, a screen with content of its own; or NONE, only Rav-Pass's frame (its toolbar, tabs,
    // the empty drawer holder), as while a screen is being rebuilt (a rotation), which says nothing either way.
    // INSPECT: the inspection drawer's list open on a screen that isn't a Done screen. Its "Show ticket" opens the Done
    // screen again (ScanBarcodeFragment to DoneTicketFragment, fromApprove=false): that's no purchase.
    enum class Seen { DONE, OTHER, NONE, INSPECT }
    private val FRAME = setOf("action_bar_root", "action_mode_bar_stub", "activity_main_include", "toolbar_include", "main_toolbar_include",
        "toolbar_pay", "toolbar_cancel", "toolbar_search_button", "toolbar_menu_button", "toolbar_route_card", "profile_toolbar_include",
        "profile_toolbar", "nav_host_fragment", "tabs_container", "tabs_include", "popup_container")
    private fun frame(id: String) = id in FRAME || id.startsWith("navigation_bar_item_") || id.startsWith("rp_bottom_bar_item_") ||
        id.startsWith("small_") || id.startsWith("profile_toolbar_")

    fun seen(root: Node): Seen {
        val ids = HashSet<String>()
        walk(root) { n -> name(n)?.let { ids += it } }
        return when {
            doneKind(ids) != null -> Seen.DONE
            ids.any { it in LISTS || it == "card_validity_show_ticket" } -> Seen.INSPECT
            ids.any { !frame(it) } -> Seen.OTHER
            else -> Seen.NONE
        }
    }

    // A purchase Kav saw: the Done screen right after another Rav-Pass screen with content, however the rider got into
    // Rav-Pass. Not one already up when the reader first looked (before is null), nor the same one seen again.
    fun witnessed(before: Seen?, now: Seen) = now == Seen.DONE && before == Seen.OTHER

    fun done(root: Node): Done? {
        val ids = HashSet<String>()
        walk(root) { n -> name(n)?.let { ids += it } }
        val kind = doneKind(ids) ?: return null
        var band: String? = null; var from: String? = null; var to: String? = null
        walk(root) { n ->
            when (name(n)) {
                "done_card_validity_text" -> band = clean(n.text)
                "departure_station" -> from = clean(n.text)?.takeIf { it.isNotEmpty() }
                "arrive_station" -> to = clean(n.text)?.takeIf { it.isNotEmpty() }
                "tv_train_arrival_station_name" -> to = clean(n.text)?.takeIf { it.isNotEmpty() }
            }
        }
        return Done(kind, band?.let { b -> BANDS.firstNotNullOfOrNull { clean(it.find(b)?.groupValues?.get(1)) } }, from, to)
    }

    // The inspection drawer's bar, closed, that the reader taps open itself right after a purchase it saw (Noam, 19 Nov:
    // "YES"): show_tickets_wrapper on Rav-Pass's Done or scan screen, with the drawer's list not on screen. Nothing else is
    // ever tapped. Null on every other screen, or with the drawer already open.
    fun handle(root: Node): Node? {
        var bar: Node? = null; var open = false; var known = false
        walk(root) { n ->
            when (name(n)) {
                "show_tickets_wrapper" -> if (bar == null) bar = n
                "boarding_pass_recycler" -> open = true
                "done_scroll", "scan_scroll" -> known = true
            }
        }
        return bar.takeIf { known && !open }
    }

    // The fare's reach in km on Rav-Pass's Approve screen ("Up to 15 km ride price", estimated_price_title in
    // ipsdk_fragment_approve_ride_card, ApproveBusRideFragment), read only on that screen, which comes right before a
    // purchase. Null on every other screen or with a text Kav doesn't know.
    fun band(root: Node): Int? {
        val ids = HashSet<String>()
        walk(root) { n -> name(n)?.let { ids += it } }
        if ("approve_container" !in ids || "estimated_price_title" !in ids) return null
        var band: Int? = null
        walk(root) { n -> if (band == null && name(n) == "estimated_price_title") clean(n.text)?.let { t -> band = RADII.firstNotNullOfOrNull { it.find(t)?.groupValues?.get(1)?.toIntOrNull() } } }
        return band
    }

    // The price Rav-Pass quotes on the same Approve screen, beside the fare's reach (estimated_price, set from its server's
    // next-ride answer for all the passengers, after discounts, as IPUtils.priceFormat writes it: "₪8.00"), in agorot. Rav-Pass's
    // quote before the purchase, not what the card is charged at the month's end (caps, transfers). Null on any other screen.
    fun price(root: Node): Int? {
        val ids = HashSet<String>()
        walk(root) { n -> name(n)?.let { ids += it } }
        if ("approve_container" !in ids || "estimated_price_title" !in ids) return null
        var price: Int? = null
        walk(root) { n -> if (price == null && name(n) == "estimated_price") price = agorot(clean(n.text)) }
        return price
    }

    fun agorot(text: String?): Int? {
        val number = Regex("(\\d+(?:\\.\\d{1,2})?)").find(text?.replace(",", "") ?: return null)?.value ?: return null
        return number.toBigDecimal().movePointRight(2).toInt()
    }

    // ravpass_ride_price_for_radius_android in the languages Rav-Pass ships.
    private val RADII = listOf(
        Regex("Up to (\\d+) km ride price"),
        Regex("מחיר לנסיעה עד (\\d+) ק[\"״]מ"),
        Regex("السعر لكل رحلة يصل إلى (\\d+) كم"),
        Regex("Стоимость поездки на расстояние менее (\\d+) км"),
        Regex("ราคาการเดินทางสูงสุด (\\d+) กม"),
    )

    // Rav-Pass's own word that it has no active ticket: its bar no_tickets_status, shown with no_active_ticket's text, on
    // the scan screen (ScanBarcodeFragment: no valid bus, light rail or train ride) or the train screen
    // (TravelTrainFragment: no valid train ride). The kinds it ends, or null. Only that view's text is read. The bar is up
    // by default while Rav-Pass waits for its server, so the reader trusts it only once it has stayed (TicketReader).
    fun noTicket(root: Node): Set<Kind>? {
        val ids = HashSet<String>()
        var bar: Node? = null
        walk(root) { n -> name(n)?.let { ids += it; if (it == "no_tickets_status" && bar == null) bar = n } }
        if (clean(bar?.text) !in NO_TICKET) return null
        return when {
            "bus_scan_layout" in ids || "other_scan_layout" in ids -> setOf(Kind.BUS, Kind.LIGHT_RAIL)
            "train_scan_layout" in ids -> setOf(Kind.TRAIN_ENTRANCE, Kind.TRAIN_EXIT)
            else -> null
        }
    }

    // no_active_ticket in the languages Rav-Pass ships (Hebrew: "your ticket will appear here for inspection").
    private val NO_TICKET = setOf("No active ticket", "כרטיס הנסיעה שלך יופיע כאן לביקורת", "لاتوجد حاليًا تذكرة نشطة",
        "Нет действительного билета", "ไม่มีตั๋วที่ใช้งานอยู่")

    // "Active ride in route 21" in the languages Rav-Pass ships.
    private val BANDS = listOf(
        Regex("Active ride in route (.+)"),
        Regex("נסיעה פעילה בקו (.+)"),
        Regex("رحلة نشطة على خط (.+)"),
        Regex("Активная поездка на маршруте (.+)"),
    )

    // The ticket screen's cards, each with the code's bounds (for the picture, cropped to it) and the card's own texts.
    fun read(root: Node): Screen? {
        var kind = kind(root) ?: return null
        val cards = ArrayList<Card>()
        var listed = 0
        walk(root) { n ->
            if (name(n) in LISTS) n.itemCount?.let { listed = maxOf(listed, it) }
            if (name(n) !in CARDS) return@walk
            var code: IntArray? = null; var number: String? = null; var passenger: String? = null
            var title: String? = null; var time: String? = null
            walk(n) { c ->
                when (name(c)) {
                    in CODES -> c.bounds.takeIf { it[2] > it[0] && it[3] > it[1] }?.let { code = it }
                    "barcode_text" -> number = c.text?.trim()?.takeIf { it.isNotEmpty() }
                    "ticket_passenger_name" -> passenger = c.text?.trim()?.takeIf { it.isNotEmpty() }
                    "card_validity_title" -> title = c.text?.trim()
                    "entrance_time" -> time = c.text?.trim()?.takeIf { TIME.matches(it) }
                }
            }
            cards += Card(code, number, passenger, title, time)
        }
        // Tel Aviv's light rail shows its tickets on the bus screen's cards, with an entrance time and no ticket number.
        if (kind == Kind.BUS && cards.isNotEmpty() && cards.all { it.number == null && it.time != null }) kind = Kind.LIGHT_RAIL
        val withCode = cards.filter { it.code != null }
        return Screen(kind, withCode, maxOf(cards.size, listed)).takeIf { withCode.isNotEmpty() }
    }

    private val TIME = Regex("\\d{1,2}:\\d{2}")

    // "Ticket for route 21 at 10:42" in the languages Rav-Pass ships: the line and the time.
    private val TITLES = listOf(
        Regex("Ticket for route (.+?) at (\\d{1,2}:\\d{2})"),
        Regex("כרטיס נסיעה בקו (.+?) בשעה (\\d{1,2}:\\d{2})"),
        Regex("تذكرة سفر على خط (.+?) الساعة (\\d{1,2}:\\d{2})"),
        Regex("Билет для проезда на (.+?) маршруте в (\\d{1,2}:\\d{2})"),
    )

    fun title(text: String?): Pair<String?, String?> {
        val t = clean(text) ?: return null to null
        for (r in TITLES) r.find(t)?.let { return it.groupValues[1].trim() to it.groupValues[2] }
        return null to TIME.find(t)?.value
    }
}

// One passenger's code as Kav read it. format is the code's kind ("QR_CODE", "AZTEC"), with "/UTF-8" when it carries that
// character set, and a closing "!" when Kav couldn't redraw it exactly the same (then it isn't drawn: TicketView.matrix).
internal data class Passenger(val name: String?, val code: String, val format: String)

internal data class Ticket(
    val key: String,
    val kind: TicketScreens.Kind,
    val line: String?,
    // The time as Rav-Pass shows it (its server's validation time), when it shows one; a train's may have none.
    val shown: String?,
    val number: String?,
    val passengers: List<Passenger>,
    // When Kav saw it being bought (Tickets.purchase); null for any other reading, which shows no "paid at" (its validity
    // then counts from the time Rav-Pass shows).
    val paidAt: Long?,
    val readAt: Long,
    // The shown time on the day it was read, for how long it's kept: never shown as when it was paid.
    val shownAt: Long? = null,
    // How many tickets the screen held, so a group isn't shown as whole with codes missing.
    val expected: Int = passengers.size,
    // The ride's stations, as the Done screen names them.
    val from: String? = null,
    val to: String? = null,
    // It already brought the rider back to Kav once. Kept with the ticket and gone with it; only a purchase just seen
    // brings the rider back (Tickets.returns), so a ticket opened again never does.
    val returned: Boolean = false,
    // The fare's reach in km as Rav-Pass's Approve screen named it during the purchase Kav saw; null when Kav didn't see it.
    val band: Int? = null,
    // Where its free-transfer window began, when that was an earlier ticket's (a transfer inside its window: Tickets.current).
    val windowFrom: Long? = null,
    // Rav-Pass's quoted price in agorot on its Approve screen during the purchase Kav saw (TicketScreens.price); null when Kav
    // didn't see it.
    val price: Int? = null,
    // When it ends, for a ticket an extension sold (ExtTickets), which says so; null for one read from Rav-Pass.
    val endsAt: Long? = null,
) {
    // How long Kav keeps it. Rav-Pass shows no expiry, so by kind, from its shown time (else when it was read): a bus
    // ticket for 6 hours (longer than the longest intercity line); light rail for 3; a train entrance for 4 hours, or until
    // its exit ticket is read; an exit ticket for an hour after it was read, time enough to leave the station.
    val overAt: Long get() = endsAt ?: when (kind) {
        TicketScreens.Kind.BUS -> (shownAt ?: readAt) + 6 * HOUR
        TicketScreens.Kind.LIGHT_RAIL -> (shownAt ?: readAt) + 3 * HOUR
        TicketScreens.Kind.TRAIN_ENTRANCE -> (shownAt ?: readAt) + 4 * HOUR
        TicketScreens.Kind.TRAIN_EXIT -> readAt + HOUR
    }

    // When it was paid or validated, for which ticket of a family is the newer: when Kav saw it bought, else the end of the
    // minute Rav-Pass shows (a code read from the drawer is Rav-Pass's current ticket), else when it was read.
    val start: Long get() = paidAt ?: shownAt?.plus(MINUTE - 1) ?: readAt

    // Kav's own estimate from its fares rule (server/live.py, FARE_NEAR and TRANSFER_WINDOW), not Rav-Pass's time: a fare
    // up to 15 km includes rides by bus, light rail or cable car boarded within 90 minutes of its validation; a longer fare
    // includes none (null). From when Kav saw it bought, else Rav-Pass's shown time; null with neither, or for a train.
    val validUntil: Long? get() {
        endsAt?.let { return it }
        if (kind != TicketScreens.Kind.BUS && kind != TicketScreens.Kind.LIGHT_RAIL || (band ?: NEAR_KM) > NEAR_KM) return null
        return (windowFrom ?: paidAt ?: shownAt)?.plus(TRANSFER_WINDOW)
    }

    val complete: Boolean get() = passengers.size >= expected

    fun json() = JSONObject().put("key", key).put("kind", kind.name).put("line", line).put("shown", shown).put("number", number)
        .put("paidAt", paidAt).put("readAt", readAt).put("shownAt", shownAt).put("expected", expected).put("returned", returned).put("from", from).put("to", to)
        .put("band", band).put("windowFrom", windowFrom).put("price", price).put("endsAt", endsAt)
        .put("passengers", JSONArray(passengers.map { JSONObject().put("name", it.name).put("code", it.code).put("format", it.format) }))

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val NEAR_KM = 15
        const val TRANSFER_WINDOW = 90 * MINUTE
        // Rav-Pass is an Israeli app and shows Israel's time.
        val ISRAEL: TimeZone get() = TimeZone.getTimeZone("Asia/Jerusalem")

        private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
        private fun JSONObject.long(k: String): Long? = if (has(k) && !isNull(k)) getLong(k) else null

        fun of(o: JSONObject): Ticket? = runCatching {
            val p = o.getJSONArray("passengers")
            val passengers = (0 until p.length()).map { p.getJSONObject(it).let { j -> Passenger(j.str("name"), j.getString("code"), j.getString("format")) } }
            Ticket(
                o.getString("key"), TicketScreens.Kind.valueOf(o.getString("kind")), TicketScreens.clean(o.str("line")), o.str("shown"), o.str("number"),
                passengers, o.long("paidAt"), o.getLong("readAt"), o.long("shownAt"), o.optInt("expected", passengers.size),
                o.str("from"), o.str("to"), o.optBoolean("returned", false), o.long("band")?.toInt(), o.long("windowFrom"),
                o.long("price")?.toInt(), o.long("endsAt"),
            )
        }.getOrNull()

        // A shown HH:MM on the day it was read: that day, unless it would be more than twelve hours ahead of the reading
        // (read after midnight: the day before). A phone clock a little slow or fast doesn't move it to another day.
        fun shownAt(shown: String, readAt: Long, zone: TimeZone = ISRAEL): Long {
            val (h, m) = shown.split(':').map { it.toInt() }
            val c = Calendar.getInstance(zone).apply {
                timeInMillis = readAt; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis > readAt + 12 * HOUR) c.add(Calendar.DAY_OF_MONTH, -1)
            return c.timeInMillis
        }

        // A purchase Kav saw: the Done screen, at the moment Kav saw it, before any code is read, with the fare's reach
        // and the price Rav-Pass quoted, where its Approve screen named them.
        fun purchase(d: TicketScreens.Done, seenAt: Long, band: Int? = null, price: Int? = null): Ticket = Ticket(
            key = "ride:$seenAt", kind = d.kind, line = d.line, shown = null, number = null, passengers = emptyList(),
            paidAt = seenAt, readAt = seenAt, shownAt = seenAt, expected = 0, from = d.from, to = d.to, band = band, price = price,
        )

        // A screen read, with each card's code decoded (null where it couldn't be), as the ticket it shows. Never "paid":
        // only a purchase Kav saw is (Ticket.purchase), and Tickets.merge gives it the code.
        fun from(screen: TicketScreens.Screen, codes: List<Pair<String, String>?>, readAt: Long, zone: TimeZone = ISRAEL): Ticket? {
            val passengers = screen.cards.zip(codes).mapNotNull { (card, code) -> code?.let { Passenger(card.passenger, it.first, it.second) } }
                .distinctBy { it.code }
            if (passengers.isEmpty()) return null
            val (line, titled) = TicketScreens.title(screen.cards.firstNotNullOfOrNull { it.title })
            val shown = titled ?: screen.cards.firstNotNullOfOrNull { it.time }
            val number = screen.cards.firstNotNullOfOrNull { it.number }
            return Ticket(
                key = number ?: passengers.first().code, kind = screen.kind, line = line, shown = shown, number = number,
                passengers = passengers, paidAt = null, readAt = readAt, shownAt = shown?.let { shownAt(it, readAt, zone) },
                expected = maxOf(screen.expected, passengers.size),
            )
        }
    }
}

// The tickets Kav has read, in its private no-backup storage. Never in a backup, a log, a share or any request.
internal object Tickets {
    const val PAY_RECENT_MS = 5 * Ticket.MINUTE

    // The rider came to Rav-Pass from Kav (a Pay button or a ticket's "Open in Rav-Pass") in the last few minutes: only then
    // does a purchase bring them back to Kav.
    fun handedOff(seenAt: Long, payTappedAt: Long): Boolean = payTappedAt > 0 && seenAt - payTappedAt in 0..PAY_RECENT_MS

    // Why the reader asks Android for window info (flagRetrieveInteractiveWindows): to know that Rav-Pass's own window is
    // the focused application window, not under the notification shade, a call or another app, before it takes a picture
    // or brings the rider back. These two facts of Rav-Pass's window are all it asks: never another window's content or
    // title. Unknown is no.
    fun frontApp(focused: Boolean?, type: Int?): Boolean = focused == true && type == APPLICATION_WINDOW
    const val APPLICATION_WINDOW = 1 // AccessibilityWindowInfo.TYPE_APPLICATION

    // A code read from the drawer belongs to the purchase Kav saw in the last half hour, of the same kind, still without a
    // code, on the same line where both are known. Bus and light rail count as one kind here: Tel Aviv's light rail shows
    // its tickets on the bus screen's cards (unverified on its Done screen), and the code's own kind is kept.
    const val ATTACH_MS = 30 * Ticket.MINUTE
    fun family(k: TicketScreens.Kind) = if (k == TicketScreens.Kind.LIGHT_RAIL) TicketScreens.Kind.BUS else k
    // An extension's ticket (endsAt) is never one Rav-Pass's codes belong to.
    private fun belongs(p: Ticket, read: Ticket) = p.endsAt == null && p.paidAt != null && p.passengers.isEmpty() && family(p.kind) == family(read.kind) &&
        read.readAt - p.paidAt in 0..ATTACH_MS && (p.line == null || read.line == null || p.line == read.line)

    // A group's next card, read on its own once the rider swipes to it (Rav-Pass's list goes across, a card a page): the
    // group still missing codes, of the same kind and line and size, read in the last few minutes.
    const val GROUP_MS = 10 * Ticket.MINUTE
    private fun rest(g: Ticket, read: Ticket) = g.passengers.isNotEmpty() && !g.complete && g.expected == read.expected &&
        family(g.kind) == family(read.kind) && g.line == read.line && read.readAt - g.readAt in 0..GROUP_MS

    // A code kept on its own beside the purchase Kav saw that it belongs to, as Kav 5's first build kept them when the two
    // screens wrote the line differently: the code goes to the purchase, as merge would have given it, and the ticket of
    // its own goes. Each code-less purchase takes the earliest such code.
    fun fold(kept: List<Ticket>): List<Ticket> {
        var out = kept
        for (p in kept.filter { it.paidAt != null && it.passengers.isEmpty() }.sortedBy { it.paidAt }) {
            val read = out.filter { it.paidAt == null && it.passengers.isNotEmpty() && belongs(p, it) }.minByOrNull { it.readAt } ?: continue
            val withCode = p.copy(kind = read.kind, passengers = read.passengers, number = read.number, shown = read.shown,
                expected = read.expected, line = p.line ?: read.line)
            out = out.filter { it !== read }.map { if (it === p) withCode else it }
        }
        return out
    }

    // One current ticket per family, as Rav-Pass keeps one active travel of each (co.hopon.model.a's "travel_bus", replaced
    // by the next): a newer purchase or reading ends the earlier ones. A ticket bought inside the free-transfer window of
    // the one it ends (a transfer, or a fare Kav can't tell from one) keeps that window: it began with the first fare.
    fun current(kept: List<Ticket>): List<Ticket> {
        val newest = HashMap<TicketScreens.Kind, Ticket>()
        for (t in kept.filter { it.endsAt == null }.sortedBy { it.start }) {
            val f = family(t.kind)
            val before = newest[f]
            val from = before?.let { b -> b.windowFrom ?: b.paidAt ?: b.shownAt }
            val inside = before?.validUntil?.let { end -> (t.paidAt ?: t.shownAt)?.let { it < end } } == true
            newest[f] = if (inside && t.windowFrom == null && (t.band ?: Ticket.NEAR_KM) <= Ticket.NEAR_KM) t.copy(windowFrom = from) else t
        }
        return kept.mapNotNull { t -> if (t.endsAt != null) t else newest[family(t.kind)]?.takeIf { it.key == t.key } }
    }

    // Rav-Pass said it has no active ticket of these kinds (TicketScreens.noTicket), its bar up since `since`: the tickets
    // of their family Kav kept before then end.
    fun end(kept: List<Ticket>, kinds: Set<TicketScreens.Kind>, since: Long): List<Ticket> =
        kept.filterNot { t -> t.endsAt == null && kinds.any { family(it) == family(t.kind) } && t.readAt < since }

    // A read ticket added to those kept. The same ticket is one with the same key, or one sharing any passenger's code (a
    // light rail ticket has no number), or the group it's the rest of; seen again it gains any passenger not seen before
    // and keeps its first reading. A ticket already over when it's read isn't kept and isn't new. True when it's new.
    fun merge(kept: List<Ticket>, read: Ticket): Pair<List<Ticket>, Boolean> {
        if (read.overAt <= read.readAt) return kept to false
        val (readTickets, extensionTickets) = kept.partition { it.endsAt == null }
        val (all, new) = add(readTickets, read)
        val now = current(all) + extensionTickets
        // A reading older than the ticket kept of its family is no ticket of Kav's.
        return now to (new && now.any { it.key == read.key })
    }

    private fun add(kept: List<Ticket>, read: Ticket): Pair<List<Ticket>, Boolean> {
        // A purchase Kav saw is a ticket of its own (a transfer ends the ticket before it: current); only codes join or attach.
        if (read.passengers.isEmpty()) return (kept + read) to true
        val old = kept.firstOrNull { it.key == read.key || it.passengers.any { p -> read.passengers.any { q -> q.code == p.code } } }
            ?: kept.filter { rest(it, read) }.maxByOrNull { it.readAt }
        // A train's exit ends its entrance.
        val rest = if (read.kind == TicketScreens.Kind.TRAIN_EXIT) kept.filter { it.kind != TicketScreens.Kind.TRAIN_ENTRANCE } else kept
        if (old == null) {
            // Its code, for the purchase Kav saw: the same ticket, not a new one.
            val ride = rest.filter { belongs(it, read) }.maxByOrNull { it.paidAt!! }
                ?: return (rest + read) to true
            val withCode = ride.copy(kind = read.kind, passengers = read.passengers, number = read.number, shown = read.shown,
                expected = read.expected, line = ride.line ?: read.line)
            return rest.map { if (it === ride) withCode else it } to false
        }
        val more = read.passengers.filter { p -> old.passengers.none { it.code == p.code } }
        val merged = old.copy(passengers = old.passengers + more, expected = maxOf(old.expected, read.expected, old.passengers.size + more.size))
        return fold(rest.map { if (it === old) merged else it }) to false
    }

    fun live(kept: List<Ticket>, now: Long) = kept.filter { it.overAt > now }

    // Whether a purchase just seen brings the rider back to Kav by itself (always, with "Pay with Rav-Pass" on): it's a bus
    // ticket (a gate ticket is scanned while it's shown); new, seen being bought after a Pay tap in Kav; and it never
    // brought them back before. Kav also looks once more that Rav-Pass is still in front.
    fun returns(t: Ticket, new: Boolean): Boolean =
        new && !t.returned && t.kind == TicketScreens.Kind.BUS && t.paidAt != null

    // Whether Kav's reader is on: the rider's yes in Kav, and Kav's service among those Android has enabled, as Android's
    // own setting lists them (ENABLED_ACCESSIBILITY_SERVICES, "pkg/.Cls" or "pkg/pkg.Cls" joined by ':'). Read from the
    // setting, not from what is bound: a force-stopped service is still on, and comes back by itself. When it isn't on,
    // nothing it read is kept or shown.
    fun readerOn(consented: Boolean, enabledSetting: String?, pkg: String, cls: String): Boolean {
        if (!consented) return false
        val short = cls.removePrefix(pkg).takeIf { it.startsWith(".") }
        return enabledSetting.orEmpty().split(':').map { it.trim() }.any { entry ->
            val (p, c) = entry.split('/', limit = 2).let { if (it.size == 2) it[0] to it[1] else return@any false }
            p.equals(pkg, ignoreCase = true) && (c.equals(cls, ignoreCase = true) || (short != null && c.equals(short, ignoreCase = true)))
        }
    }

    fun text(kept: List<Ticket>) = JSONObject().put("tickets", JSONArray(kept.map { it.json() })).toString()

    fun list(text: String?): List<Ticket> = runCatching {
        val t = text?.trim() ?: return emptyList()
        val a = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("tickets")
        (0 until a.length()).mapNotNull { Ticket.of(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())
}
