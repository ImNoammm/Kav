package uk.noammm.kav.ext

import kav.kavapp.api.Kav

// Screen parts: each distinct part of Kav's screens by name, where an extension adds a view before or after it, or
// replaces it (Kav.part, a contribution with capability "part.<name>"; ui/ExtParts.kt draws them). Views before and
// after go in the order their extensions were switched on, first on first, as the cards of API 3 go in install order.
// A replace is the last switched on's; when its view is null, the next one's, then Kav's own. A part no extension adds
// to is drawn as Kav draws it, with nothing around it.
//
// The four cards of API 3 (Kav.card, Slots) sit where they always did, each beside one of these: "home" after
// home.suggestions, "trip" after trip.timeline, "stop" at the top of stop.board, "results" inside results.route's card.
object Parts {
    val SETTINGS_PAGES = listOf("trips", "paying", "live", "look", "map", "privacy", "extensions", "kav")

    // Every part, with what it is told (docs/native.md lists each with its JSON and where Kav draws it).
    val NAMES = listOf(
        "home.places", "home.journey", "home.suggestions", "home.latest", "home.pay", "home.recent",
        "trip.header", "trip.map", "trip.summary", "trip.timeline", "trip.ride", "trip.walk",
        "results.route",
        "stop.header", "stop.board",
        "line.header",
        "settings.root",
        "tabbar",
        "settings.page.header",
        "stops.header", "stops.search", "lines.header", "lines.search", "lines.filters",
        "live.header", "live.filters",
        "results.endpoints", "results.options", "results.time.shift", "results.planners",
        "ticket.header", "ticket.validity", "ticket.ride",
        "pay.history.header", "pay.history.summary", "pay.history.empty", "pay.history.month", "pay.history.ride",
        "nav.header", "nav.problem", "nav.card", "share.targets", "ticket.codes", "pay.setup",
        "nav.wait.details", "nav.ride.details",
    ) + SETTINGS_PAGES.map { "settings.page.$it" }

    const val CAPABILITY = "part."
    val CAPABILITIES get() = NAMES.map { CAPABILITY + it }
    val WHERE = listOf("before", "after", "replace")

    data class Piece(val identity: String, val onAt: Long, val where: String, val card: Kav.Card)

    // The views before (or after) a part, first switched on first.
    fun around(pieces: List<Piece>, where: String): List<Piece> = pieces.filter { it.where == where }.sortedBy { it.onAt }

    // The replacements of a part, the one to try first first.
    fun replacing(pieces: List<Piece>): List<Piece> = pieces.filter { it.where == "replace" }.sortedByDescending { it.onAt }
}
