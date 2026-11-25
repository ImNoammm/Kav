package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject

// The buttons of the trip's notification (TripService), a named spot an extension may replace (Kav.provide with the
// capability "trip.notification"). Kav asks the one switched on last that provides it, with Kav's own buttons in order
// ("pay" or "ticket", then "end") and the ride paying is for, and shows the buttons it answers: each of Kav's own by its
// id, kept where the answer puts it or left out, and buttons of its own, at most three in all, whose taps Kav tells it.
// With none, or a failed, late or unreadable answer, Kav's own as they are. Kav.provide called again for it has Kav post
// the notification again, so the extension can say its buttons changed.
//   asked:    {"event": "buttons", "buttons": [{"id", "label"}], "ride": <the ride's pay JSON or null>}
//   answered: {"buttons": [{"id"}, {"id", "label"}]}
//   a tap:    {"event": "tap", "id"}
object TripButtons {
    const val CAPABILITY = "trip.notification"
    private const val ASK_MS = 300L
    private const val TAP_MS = 3_000L
    private const val MOST = 3

    // owner: the extension whose button it is; null for Kav's own.
    class Button(val id: String, val label: String, val owner: String?)

    // TripService: post the notification again (set while it runs).
    @Volatile var changed: (() -> Unit)? = null

    // The extension answering for the notification: of those that provide it, the one switched on last.
    private fun holder(): String? = Native.providing(CAPABILITY).maxByOrNull { it.second }?.first
        ?.takeIf { id -> Hooks.offered(CAPABILITY).any { it.first.identity == id } }

    fun of(own: List<Pair<String, String>>, ride: JSONObject?): List<Button> {
        val kav = own.map { Button(it.first, it.second, null) }
        val identity = holder() ?: return kav
        val asked = JSONObject().put("event", "buttons").put("ride", ride ?: JSONObject.NULL)
            .put("buttons", JSONArray(own.map { JSONObject().put("id", it.first).put("label", it.second) }))
        val text = Native.hook(identity, CAPABILITY, asked.toString(), ASK_MS)?.getOrNull()?.takeIf { it != "null" } ?: return kav
        return parse(text, own, identity) ?: kav
    }

    // Kav's own buttons keep Kav's words; one of the extension's needs a label. Null for an answer Kav can't read.
    internal fun parse(text: String, own: List<Pair<String, String>>, identity: String): List<Button>? = runCatching {
        val a = JSONObject(text).getJSONArray("buttons")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kav = own.firstOrNull { it.first == id }
            if (kav != null) Button(id, kav.second, null)
            else o.optString("label").trim().takeIf { it.isNotEmpty() }?.let { Button(id, it.take(30), identity) }
        }.distinctBy { it.id }.take(MOST)
    }.getOrNull()

    // A tap on one of its own buttons, told to it on a thread of Kav's.
    fun tap(identity: String, id: String) {
        Thread { Native.hook(identity, CAPABILITY, JSONObject().put("event", "tap").put("id", id).toString(), TAP_MS) }.start()
    }
}
