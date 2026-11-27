package uk.noammm.kav.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.KavModel
import uk.noammm.kav.Tab
import uk.noammm.kav.TicketStore
import uk.noammm.kav.data.Net
import uk.noammm.kav.data.Transit
import uk.noammm.kav.ext.Actions
import uk.noammm.kav.ext.Kind
import uk.noammm.kav.ext.Namespace

// The actions Kav handles from its model, through ext/Actions.kt: what each is told, and Kav's own handling of each for
// Kav.kavAction. The ones handled on a screen of their own (a trip, a search, paying) are set where they're handled.

internal fun stopArgs(net: Net?, stop: Int): JSONObject {
    val ref = uk.noammm.kav.ext.RefTable.ref(stop)?.toString()
        ?: net?.code?.getOrNull(stop)?.takeIf { it > 0 }?.let { Namespace.MINISTRY.ref(Kind.STOP, it.toString()).toString() }
    val name = uk.noammm.kav.ext.RefTable.label(stop) ?: net?.takeIf { stop in 0 until it.code.size }?.stopName(stop)
    return JSONObject().put("stop", ref ?: JSONObject.NULL).put("name", name ?: JSONObject.NULL)
}

// A stop's board opened by a tap: true when an extension holding "stop.open" took it.
internal fun stopTaken(model: KavModel, stop: Int): Boolean = Actions.take(null, "stop.open") { stopArgs(model.net, stop) }

internal fun planFromStop(model: KavModel, stop: Int, place: Transit.Place, end: String) {
    if (!Actions.take(null, "plan.stop") {
        stopArgs(model.net, stop).put("end", end).put("place", JSONObject()
            .put("name", place.name).put("detail", place.detail).put("lat", place.lat).put("lon", place.lon)
            .put("type", place.type).put("mode", place.mode))
    }) planFromStopOwn(model, place, end)
}

private fun planFromStopOwn(model: KavModel, place: Transit.Place, end: String) {
    when (end) {
        "from" -> model.pendingFrom = place
        "to" -> model.pendingTo = place
        else -> return
    }
    model.tab = Tab.Directions
}

internal fun liveShowKey(show: LiveShow) = if (show == LiveShow.TRAFFIC) "vehicles" else show.name.lowercase()

// A line's page opened by a tap: its number, and the stop it opens at when there is one.
internal fun lineTaken(model: KavModel, number: String?, stop: Int): Boolean = Actions.take(null, "line.open") {
    JSONObject().put("number", number ?: JSONObject.NULL).put("stop", if (stop >= 0) stopArgs(model.net, stop).opt("stop") else JSONObject.NULL)
}

// A ticket opened by a tap.
internal fun openTicket(ctx: Context?, key: String) {
    if (!Actions.take(ctx, "ticket.open") { JSONObject().put("key", key) }) TicketStore.opened = key
}

// A bus reported, as the report screen is given it.
internal fun reportArgs(r: ReportFor): JSONObject = JSONObject().put("line", r.line).put("agency", r.agency).put("origin", r.origin)
    .put("destination", r.destination).put("at", r.at).put("plate", r.plate ?: JSONObject.NULL)
    .put("stops", JSONArray(r.stops.map { it.name }))

// Kav's own handling of these, for Kav.kavAction, set once Kav's model is there.
internal fun kavActions(model: KavModel) {
    Actions.own("stop.open") { _, a ->
        val net = model.net
        val code = a.optString("stop").substringAfterLast('/').toIntOrNull()
        val stop = if (net != null && code != null) net.code.indexOfFirst { it == code } else -1
        if (stop >= 0) model.openStationOwn(stop)
    }
    Actions.own("line.open") { _, a ->
        val net = model.net ?: return@own
        val number = a.optString("number")
        val code = a.optString("stop").substringAfterLast('/').toIntOrNull()
        val stop = code?.let { c -> net.code.indexOfFirst { it == c } } ?: -1
        // The week's route with that number, at that stop when one is named.
        val route = (0 until net.nRoutes).firstOrNull { net.rShort[it] == number } ?: return@own
        openTimetableLineOwn(model, route, stop)
    }
    Actions.own("ticket.open") { _, a -> TicketStore.opened = a.optString("key").ifEmpty { null } }
    Actions.own("report") { _, _ -> model.reportOwn() }
    Actions.own("settings.page.open") { _, a ->
        // live was the Providers page, now part of Privacy.
        val page = a.optString("page").let { if (it == "live") "privacy" else it }
        if (page in uk.noammm.kav.ext.Parts.SETTINGS_PAGES) {
            model.settingsOpen = true
            SettingsNav.wanted = page
        }
    }
    Actions.own("plan.stop") { _, a ->
        val place = a.optJSONObject("place") ?: return@own
        val lat = place.optDouble("lat")
        val lon = place.optDouble("lon")
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@own
        planFromStopOwn(model, Transit.Place(place.optString("name"), place.optString("detail"), lat, lon,
            place.optInt("type", 5), place.optInt("mode", -1)), a.optString("end"))
    }
    Actions.own("live.show") { _, a ->
        LiveShow.entries.firstOrNull { liveShowKey(it) == a.optString("show") }?.let { model.liveShow = it }
    }
    Actions.own("pay.history.open") { _, _ -> PayHistory.open = true }
    // Until the results' screen has opened (it takes this over then): only the tab results open on, with "default".
    Actions.own("results.planner.select") { ctx, a -> if (a.optBoolean("default")) chooseOpensOn(ctx, a.optString("source")) }
    Actions.own("pay.mode") { ctx, a -> if (a.has("on")) payModeOwn(ctx, a.optBoolean("on")) }
    Actions.own("pay.offer.hide") { ctx, _ -> PayMode.dismissOffer(ctx) }
    Actions.own("pay.place.open") { ctx, a -> RavPass.placeNamed(a.optString("place"))?.let { RavPass.place(ctx, it) } }
    Actions.own("ticket.ravpass.open") { ctx, a ->
        TicketStore.kept.firstOrNull { it.key == a.optString("key") && !uk.noammm.kav.ExtTickets.mine(it) }?.let { openInRavPass(ctx, it) }
    }
    Actions.own("settings.open") { _, _ -> model.settingsOpen = true }
    Actions.own("street.open") { _, _ -> StreetViewer.asked?.let { StreetViewer.open = it } }
    Actions.own("taxi") { ctx, a -> a.optString("link").takeIf { it.startsWith("gett://") }?.let { openGettOwn(ctx, it) } }
    Actions.own("place.save") { ctx, _ -> model.placesAsked?.let { model.saveFavouritesOwn(ctx, it) } }
    fun keys(a: JSONObject, k: String) = a.optJSONArray(k)?.let { j -> (0 until j.length()).map { j.optString(it) }.filter { it.isNotEmpty() } }
    Actions.own("stop.save") { ctx, a -> keys(a, "stops")?.let { model.saveStopsOwn(ctx, it) } }
    Actions.own("line.save") { ctx, a -> keys(a, "lines")?.let { model.saveLinesOwn(ctx, it) } }
    Actions.own("line.alerts.save") { ctx, a ->
        a.optJSONArray("alerts")?.let { j -> model.saveAlertsOwn(ctx, (0 until j.length()).mapNotNull { j.optJSONObject(it)?.let(uk.noammm.kav.data.LineAlert::of) }) }
    }
    Actions.own("link.open") { _, _ -> uk.noammm.kav.LinkAsked.plan?.let(model::openLinkOwn) }
    Actions.own("tab.open") { _, a -> uk.noammm.kav.Tab.entries.firstOrNull { it.name.equals(a.optString("tab"), true) }?.let { model.openTab(it) } }
    Actions.own("pay") { ctx, a ->
        val s = RavPass.Section.entries.firstOrNull { RavPass.key(it) == a.optString("section", "bus") } ?: RavPass.Section.BUS
        val ride = a.takeIf { it.has("line") || it.has("to") }
        RavPass.own(ctx, s, ride?.let { PayFor(it.optString("line").ifEmpty { null }, it.optJSONObject("to")?.optString("name")?.ifEmpty { null }, it.toString()) })
    }
}
