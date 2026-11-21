package uk.noammm.kav.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Transit
import uk.noammm.kav.ext.ExtApp
import uk.noammm.kav.ext.ExtUses
import uk.noammm.kav.ext.Hub
import uk.noammm.kav.ext.Registry
import uk.noammm.kav.ext.SafeMode
import uk.noammm.kav.ext.Slots

// An extension's cards on Kav's own screens (ext/Slots.kt), the views its code makes (ExtNative.kt). Each sits where
// Kav puts it, in the order the rider switched them on.

private fun picked(k: Slots.Kind): List<String> = ExtShown.slots.filter { it.endsWith("|${k.key}") }.map { it.substringBeforeLast('|') }

// One extension's card in one slot, once its code has started; nothing in safe mode.
@Composable
private fun SlotPiece(model: KavModel, identity: String, k: Slots.Kind, about: JSONObject, top: Dp = 0.dp) {
    if (!SafeMode.active && uk.noammm.kav.ext.Native.isLoaded(identity)) NativeCard(model, identity, k.key, about, top)
}

// Home: a card from each extension the rider switched it on for (three at most), under Kav's own suggestions.
@Composable
internal fun HomeSlots(model: KavModel, modifier: Modifier = Modifier) {
    if (ExtShown.slots.isEmpty()) return
    val ids = picked(Slots.Kind.HOME)
    if (ids.isEmpty()) return
    Column(modifier) {
        for (id in ids) key(id) { SlotPiece(model, id, Slots.Kind.HOME, JSONObject(), top = K.gap4) }
    }
}

// The trip page: after Kav's own timeline, a section from each extension (three at most). Told about this trip.
@Composable
internal fun TripSlots(model: KavModel, trip: Transit.Itinerary, r: Transit.Resolved) {
    // With no slot switched on (and always with no extensions), nothing of ext is touched.
    if (ExtShown.slots.isEmpty()) return
    val ids = picked(Slots.Kind.TRIP)
    if (ids.isEmpty()) return
    val about = remember(trip) { JSONObject().put("trip", Slots.trip(trip, r)) }
    Column(Modifier.padding(horizontal = K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
        for (id in ids) key(id) { SlotPiece(model, id, Slots.Kind.TRIP, about) }
    }
}

// A stop board: after Kav's own rows, a row from each extension (two at most). Told which stop this is.
@Composable
internal fun StopSlots(model: KavModel, ref: String, name: String) {
    if (ExtShown.slots.isEmpty()) return
    val ids = picked(Slots.Kind.STOP)
    if (ids.isEmpty()) return
    val about = remember(ref) { JSONObject().put("stop", ref).put("name", name) }
    Column(Modifier.padding(start = K.gap3, end = K.gap3, bottom = K.gap2)) {
        for (id in ids) key(id) { SlotPiece(model, id, Slots.Kind.STOP, about, top = K.gap3) }
    }
}

// The name for the "by" label on a route an extension's planner gave (Itinerary.provider, its namespace); null for
// Kav's own.
internal fun plannerName(provider: String): String? {
    if (provider.isEmpty()) return null
    val ns = uk.noammm.kav.ext.Namespace.of(provider) ?: return provider
    return runCatching { uk.noammm.kav.ext.Hooks.installed(ns)?.displayName }.getOrNull() ?: ns.dataset
}

// What a search says when the extension it plans with gave no usable answer: what happened and with whom, never "no
// routes", and Kav's server isn't asked instead.
internal fun plannerProblemWords(ctx: android.content.Context, e: uk.noammm.kav.ext.ProviderProblem): String {
    val name = runCatching { ExtApp.hub(ctx).registry.installed.firstOrNull { it.namespace == e.namespace }?.displayName }.getOrNull()
        ?: e.namespace.dataset
    val n = "\u2068$name\u2069"
    return when (val o = e.outcome) {
        is uk.noammm.kav.ext.Outcome.Unsupported -> T("$n couldn't plan this trip.", "$n לא הצליח לתכנן את הנסיעה הזו.")
        is uk.noammm.kav.ext.Outcome.Unavailable -> T("$n didn't answer, or is busy. Your trip wasn't planned with anyone else. Try again in a moment.",
            "$n לא ענה, או שהוא עמוס. הנסיעה לא תוכננה עם אף מתכנן אחר. נסו שוב בעוד רגע.")
        is uk.noammm.kav.ext.Outcome.Failed -> T("$n couldn't plan this trip (", "$n לא הצליח לתכנן את הנסיעה הזו (") + T.ltr(o.code) +
            T("). Your trip wasn't planned with anyone else.", "). הנסיעה לא תוכננה עם אף מתכנן אחר.")
        is uk.noammm.kav.ext.Outcome.Malformed -> if (o.reason == "infeasible") T("$n answered only routes that can't be ridden as given, so none are shown.",
            "$n החזיר רק מסלולים שאי אפשר לנסוע בהם כמו שהם, ולכן לא מוצג אף אחד מהם.")
        else if (o.reason == "too large") T("$n answered more than Kav takes from a planner, so no routes are shown.",
            "$n החזיר יותר ממה ש-Kav מקבלת ממתכנן, ולכן לא מוצג אף מסלול.")
        else T("$n answered something Kav can't read as routes.", "$n החזיר תשובה ש-Kav לא יכולה לקרוא כמסלולים.")
        else -> T("$n couldn't plan this trip.", "$n לא הצליח לתכנן את הנסיעה הזו.")
    }
}

// A result card's footer: the planner's "by" label when an extension planned it, Kav's own (the taxi card's Gett
// button), then each results card's line for this route (ExtNative.kt), under its name.
internal fun resultFooter(model: KavModel, own: (@Composable () -> Unit)?, route: Transit.Itinerary, r: Transit.Resolved, by: String? = null): (@Composable () -> Unit)? {
    val ids = if (ExtShown.slots.isEmpty()) emptyList() else picked(Slots.Kind.RESULTS)
    if (own == null && by == null && ids.isEmpty()) return null
    return {
        by?.let { ByLabel(it) }
        own?.invoke()
        if (ids.isNotEmpty()) {
            val about = remember(route) { JSONObject().put("trip", Slots.trip(route, r)) }
            for (id in ids) key(id) { ResultLine(model, id, about) }
        }
    }
}

// -- Switches ---------------------------------------------------------------------------------------------------------

// The slot a line of what an extension can do stands for, when it is one.
internal fun slotOf(what: ExtUses.What): Slots.Kind? = when (what) {
    ExtUses.What.SLOT_HOME -> Slots.Kind.HOME
    ExtUses.What.SLOT_RESULTS -> Slots.Kind.RESULTS
    ExtUses.What.SLOT_TRIP -> Slots.Kind.TRIP
    ExtUses.What.SLOT_STOP -> Slots.Kind.STOP
    else -> null
}

// On its page in Settings, at the end of a part's row in "It can": that part's switch, for the rider who wants one of
// them off. Installing it was the yes to what the part is told, so switching one on asks nothing more.
@Composable
internal fun SlotSwitch(i: Registry.Installed, k: Slots.Kind, label: String) {
    ExtUi.tick
    val dir = ExtApp.dir(LocalContext.current)
    val on = Slots.Picks.isOn(i.identity, k)
    Box(
        Modifier.clip(K.capsule).toggleable(on, role = Role.Switch) { v ->
            Slots.Picks.set(dir, i.identity, k, v)
            ExtShown.publish(); ExtUi.changed()
        }.semantics { contentDescription = label },
    ) { KavSwitch(on) }
}
