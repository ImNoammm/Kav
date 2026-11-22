package uk.noammm.kav.ext

import android.content.Context
import org.json.JSONObject
import kav.kavapp.api.Kav
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

// Actions: the rider's main taps by name, each handled in one place in Kav, which asks the extensions holding it first
// (Kav.action, a contribution with capability "action.<name>"). The last switched on goes first; one that answers false,
// or crashes, passes it on to the next, then to Kav's own. An extension runs Kav's own itself with Kav.kavAction. With
// no extension holding an action, Kav does its own at once and builds no arguments.
object Actions {
    // Every action, with what its arguments carry (docs/native.md lists each with its JSON and where Kav handles it).
    val NAMES = listOf(
        "pay", "ticket.open",
        "stop.open", "line.open", "trip.open", "trip.start", "trip.end", "share", "plan",
        "report", "alert.open", "taxi", "street.open",
        "place.save", "stop.save", "line.save",
        "settings.open", "tab.open",
        "settings.page.open", "pay.history.open", "plan.stop", "live.show",
        "search.open", "results.place.pick", "results.endpoints.swap", "results.stop.remove",
        "results.stop.stay", "results.time.select", "results.order.select", "results.planner.select",
    )

    const val CAPABILITY = "action."
    val CAPABILITIES get() = NAMES.map { CAPABILITY + it }

    // The screen in front, for an action Kav starts away from a view of its own (a notification, a shortcut).
    @Volatile private var front: WeakReference<Context>? = null

    fun resumed(activity: Context) { front = WeakReference(activity) }

    fun paused(activity: Context) { if (front?.get() === activity) front = null }

    // Kav's own handling of each action, for Kav.kavAction: set by the screens that do it, with the same arguments an
    // extension is given.
    private val own = ConcurrentHashMap<String, (Context, JSONObject) -> Unit>()

    fun own(name: String, handle: (Context, JSONObject) -> Unit) { own[name] = handle }

    // Told when an extension takes an action or lets one go (its code started, or it was switched off), on any thread.
    @Volatile var changed: (() -> Unit)? = null

    internal fun notifyChanged() { runCatching { changed?.invoke() } }

    // One extension's handler, and when it was switched on.
    class Holder(val identity: String, val onAt: Long, val action: Kav.Action)

    // The holders of an action, the last switched on first; two switched on at the same moment, the later installed first.
    fun order(holders: List<Holder>): List<Holder> = holders.sortedByDescending { it.onAt }

    fun holders(name: String): List<Holder> = order(Native.actionsOf(name))

    fun held(name: String) = Native.actionsOf(name).isNotEmpty()

    // The display name of the extension that goes first on an action, or null with none.
    fun firstName(name: String): String? = holders(name).firstOrNull()?.let { Native.nameOf(it.identity) }

    // Asks each holder in turn, under the crash guard: true when one handled it.
    fun chain(holders: List<Holder>, run: (Holder) -> Boolean?): Boolean = holders.any { run(it) == true }

    // A tap of this action: true when an extension handled it, and Kav does nothing more. args is built only when one
    // holds it. ctx: the screen's, else the screen in front.
    fun take(ctx: Context?, name: String, args: () -> JSONObject): Boolean {
        if (SafeMode.active) return false
        val holders = holders(name)
        if (holders.isEmpty()) return false
        val activity = ctx?.takeIf { it is android.app.Activity } ?: front?.get() ?: ctx ?: return false
        val text = runCatching { args().toString() }.getOrDefault("{}")
        return chain(holders) { h -> NativeGuard.call(h.identity, "action $name") { h.action.run(activity, text) } }
    }

    // Kav's own handling, asked for by an extension: on the main thread, with no extension asked.
    fun kav(name: String, ctx: Context, args: String) {
        val handle = own[name] ?: throw IllegalArgumentException("$name isn't an action Kav has, or its screen hasn't opened yet")
        val a = JSONObject(args.ifBlank { "{}" })
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) handle(ctx, a)
        else android.os.Handler(android.os.Looper.getMainLooper()).post { handle(ctx, a) }
    }
}
