package uk.noammm.kav.ext

import android.content.Context
import android.util.Log
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension
import kav.kavapp.api.KavUi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Native extensions (OPEN-PLAN, amendment 2): every package carries Android code (a dex the maker built) that Kav loads
// into its own process and runs, with full access. The manifest says so:
//
//   "native": {"dex": "classes.dex", "entry": "org.example.speed.Speed"}
//
// The entry class implements kav.kavapp.api.KavExtension, Kav's small stable API (kept un-renamed in release
// builds, proguard-rules.pro). Kav calls start(kav) once per run; through it the extension adds cards to Kav's screens,
// screens and a Home of its own, as Android views, things on Kav's maps (NativeMap), and hooks (route rules, a planner,
// stop boards), as JSON text. A crash is counted against the extension that caused it
// (NativeGuard).
object Native {
    private const val TAG = "KavNative"
    private val ENTRY = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")

    class Spec(val dex: String, val entry: String)

    fun of(m: Manifest): Spec? = m.json.optJSONObject("native")?.let { Spec(it.optString("dex"), it.optString("entry")) }

    fun declares(m: Manifest) = m.json.has("native")

    // At install: a dex inside the package, and a class name.
    fun check(m: Manifest, files: Set<String>) {
        if (!declares(m)) return
        val s = of(m) ?: throw TrustError("bad native")
        if (!Archive.safePath(s.dex) || !s.dex.endsWith(".dex") || s.dex !in files) throw TrustError("native: its dex isn't in the package")
        if (!ENTRY.matches(s.entry)) throw TrustError("native: bad entry class")
    }

    // What one loaded extension added, by its identity. onAt: when the rider switched it on (Registry.Installed.enabledAt).
    // nameHe: its name in Hebrew (Manifest.displayNameHe), "" when it has none.
    class Loaded(val identity: String, val name: String, val loader: ClassLoader, val sha256: String = "", val onAt: Long = 0, val nameHe: String = "") {
        val cards = ConcurrentHashMap<String, Kav.Card>()
        val screens = ConcurrentHashMap<String, Kav.Screen>()
        val hooks = ConcurrentHashMap<String, Kav.Hook>()
        // API 4: the actions it takes over (Actions), its views on Kav's screen parts by "<part>|<where>" (Parts), and
        // the methods it hooked (MethodHooks).
        val actions = ConcurrentHashMap<String, Kav.Action>()
        val parts = ConcurrentHashMap<String, Kav.Card>()
        val unhooks: MutableList<Kav.Unhook> = java.util.concurrent.CopyOnWriteArrayList()
        @Volatile var onSettings: Kav.SettingsChanged? = null
        @Volatile var settingsScreen: Kav.Screen? = null
        // API 5: told about the trip the rider follows (TripEvents), while the rider allows it.
        @Volatile var onTrip: Kav.TripListener? = null
        // API 5: its home screen widgets' content, by widget id (Widgets).
        val widgets = ConcurrentHashMap<String, Kav.Widget>()
    }

    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val starting = ConcurrentHashMap<String, Loaded>()

    // Extensions whose code is built against an API this Kav no longer has (an older package of it): not loaded and not
    // counted as a crash; Kav asks their source for a newer release (ExtApp).
    val outdated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // A class of Kav's API its code links to that isn't this Kav's: it was built for an older Kav.
    private fun olderApi(e: Throwable): Boolean {
        // ART names the missing class of Kav's API in the cause or in a suppressed exception of the one it throws.
        fun all(t: Throwable, depth: Int): List<Throwable> = if (depth > 4) emptyList() else
            listOf(t) + (t.cause?.let { all(it, depth + 1) } ?: emptyList()) + t.suppressed.flatMap { all(it, depth + 1) }
        val words = all(e, 0).joinToString(" ") { it.message.orEmpty() }
        return Regex("""[\w./]*api[./]Kav\w*""").findAll(words).any { m -> !m.value.replace('/', '.').contains(KavExtension::class.java.`package`!!.name) }
    }

    // Kav's look for one extension, as views (ui/ExtNative.kt sets it).
    @Volatile var ui: ((identity: String) -> KavUi)? = null

    fun card(identity: String, on: String): Kav.Card? = loaded[identity]?.cards?.get(on)
    fun screen(identity: String, page: String): Kav.Screen? = loaded[identity]?.screens?.get(page)
    fun settingsScreen(identity: String): Kav.Screen? = loaded[identity]?.settingsScreen
    fun isLoaded(identity: String) = loaded.containsKey(identity)
    fun provides(identity: String, hook: String) = loaded[identity]?.hooks?.containsKey(hook) == true
    // The loaded extensions that provide a hook, with when each was switched on.
    fun providing(hook: String): List<Pair<String, Long>> = loaded.values.filter { it.hooks.containsKey(hook) }.map { it.identity to it.onAt }
    fun nameOf(identity: String): String? = loaded[identity]?.let { if (uk.noammm.kav.ui.T.rtl) it.nameHe.ifEmpty { it.name } else it.name }

    // The loaded extensions holding an action, and their views on a part, as Actions and Parts order them.
    fun actionsOf(name: String): List<Actions.Holder> = loaded.values.mapNotNull { l -> l.actions[name]?.let { Actions.Holder(l.identity, l.onAt, it) } }
    fun partsOf(name: String): List<Parts.Piece> = if (loaded.isEmpty()) emptyList() else loaded.values.flatMap { l ->
        Parts.WHERE.mapNotNull { w -> l.parts["$name|$w"]?.let { Parts.Piece(l.identity, l.onAt, w, it) } }
    }

    // Switched off, revoked, blocked or uninstalled: Kav stops calling its code for the rest of this run (its cards,
    // screens, hooks and map things go). What its code started on its own runs until Kav closes; it loads again, if on,
    // at the next start.
    fun retire(identity: String) = synchronized(loaded) {
        val pending = starting.remove(identity)
        val gone = loaded.remove(identity)
        if (gone != null || pending != null) {
            NativeMap.drop(identity)
            listOfNotNull(gone, pending).forEach(::release)
            // Its widgets on the home screen say it's off.
            Widgets.refresh(identity, ask = false)
        }
    }

    // What it added that outlives a call: its hooks taken out, and the screens that ask for its actions told.
    private fun release(l: Loaded) {
        l.unhooks.forEach { runCatching { it.unhook() } }
        l.unhooks.clear()
        if (l.actions.isNotEmpty()) Actions.notifyChanged()
    }

    // Its hook (Hooks), on a thread of Kav's and under the crash guard: null when it
    // provides none, else its answer, or a failure ("crashed", or "late" past deadlineMs, the call left to finish).
    fun hook(identity: String, hook: String, input: String, deadlineMs: Long): Result<String>? {
        val h = loaded[identity]?.hooks?.get(hook) ?: return null
        val f = hooks.submit<String?> { NativeGuard.call(identity, "hook $hook") { h.call(input) ?: "null" } }
        return try {
            f.get(deadlineMs.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("crashed"))
        } catch (e: java.util.concurrent.TimeoutException) {
            Result.failure(IllegalStateException("late"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("interrupted"))
        }
    }

    // A widget's content (Kav.Widget.content), or a tap told to it (tapped), the same way as a hook: null when it has no
    // such widget, else its answer ("" for a tap), or a failure.
    fun widget(identity: String, widget: String, args: String, deadlineMs: Long): Result<String>? {
        val w = loaded[identity]?.widgets?.get(widget) ?: return null
        return ask(identity, "widget $widget", deadlineMs) { w.content(args) ?: "null" }
    }

    fun widgetTap(identity: String, widget: String, tap: String, deadlineMs: Long): Result<String>? {
        val w = loaded[identity]?.widgets?.get(widget) ?: return null
        return ask(identity, "widget $widget tap", deadlineMs) { w.tapped(tap); "" }
    }

    private fun ask(identity: String, what: String, deadlineMs: Long, call: () -> String): Result<String> {
        val f = hooks.submit<String?> { NativeGuard.call(identity, what, call) }
        return try {
            f.get(deadlineMs.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("crashed"))
        } catch (e: java.util.concurrent.TimeoutException) {
            Result.failure(IllegalStateException("late"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("interrupted"))
        }
    }

    // Whether any extension listens to trips (Kav.onTrip): only then are trip events worked out.
    fun listensToTrips(): Boolean = loaded.values.any { it.onTrip != null }

    // A trip event (TripEvents) to each extension listening that the rider allows to follow trips, or to one alone, on a
    // thread of Kav's.
    fun tripEvent(event: String, only: String? = null) {
        for (l in loaded.values) {
            if (only != null && l.identity != only) continue
            val listener = l.onTrip ?: continue
            if (!TripAccess.allows(l.identity, TripAccess.FOLLOW)) continue
            hooks.execute { NativeGuard.call(l.identity, "trip") { listener.trip(event) } }
        }
    }

    // The rider changed one of its settings: its listener is told, with all of them, on a thread of Kav's.
    fun settingsChanged(identity: String, settings: String) {
        val l = loaded[identity]?.onSettings ?: return
        hooks.execute { NativeGuard.call(identity, "settings") { l.changed(settings) } }
    }

    private val hooks = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "kav-native-hook").apply { isDaemon = true } }

    // Tests only: an extension as if loaded, and what its code is given.
    internal fun put(l: Loaded) { loaded[l.identity] = l }
    internal fun api(ctx: Context, hub: Hub, i: Registry.Installed, l: Loaded): Kav = Api(ctx, hub, i, l)
    internal fun forget() { loaded.clear() }

    // Which loaded extension a class is, for the crash guard: the one whose loader defined it.
    internal fun ownerOf(className: String): String? = (loaded.values.asSequence() + starting.values.asSequence()).firstOrNull { l ->
        runCatching { Class.forName(className, false, l.loader).classLoader === l.loader }.getOrDefault(false)
    }?.identity

    // Every active native extension of this run, loaded and started: at Kav's start, and again whenever one is
    // added, updated or switched on while Kav runs (ExtApp.loadNative), so it works at once. One whose
    // installed release changed since it loaded is let go first and loads the new one. Never in safe mode; one that
    // crashed Kav twice was switched off before this (NativeGuard.check).
    @Synchronized fun start(ctx: Context, hub: Hub) {
        if (SafeMode.active) return
        for (i in hub.registry.installed) {
            loaded[i.identity]?.takeIf { it.sha256 != i.sha256 }?.let { retire(i.identity) }
            if (!hub.active(i) || loaded.containsKey(i.identity) || starting.containsKey(i.identity)) continue
            // Built for a newer API than this Kav has: its calls would fail, so it isn't started.
            if (i.manifest.api > Kav.API) { Log.i(TAG, "${i.identity}: needs API ${i.manifest.api}, this Kav has ${Kav.API}"); continue }
            val s = of(i.manifest) ?: continue
            // Marked on disk while it starts: a native crash or a kill in it is counted at the next start (NativeGuard).
            NativeGuard.starting(i.identity)
            // Each in a thread of its own, waited for START_MS: one whose start() never returns holds up no other one,
            // nor what Kav does after them (ExtApp.startNative). It is left running.
            Thread({ NativeGuard.call(i.identity, "start") { load(ctx, hub, i, s) } }, "kav-native-start").apply { isDaemon = true; start() }.join(START_MS)
            NativeGuard.starting(null)
        }
    }

    const val START_MS = 5_000L

    private fun load(ctx: Context, hub: Hub, i: Registry.Installed, s: Spec) {
        val registry = hub.registry
        val dex = verifiedDex(registry.packageDir(i.identity) ?: return, i.sha256, s.dex)
        val loader = dalvik.system.InMemoryDexClassLoader(java.nio.ByteBuffer.wrap(dex), Native::class.java.classLoader)
        val m = i.manifest
        val l = Loaded(i.identity, m.defaultName, loader, i.sha256, i.enabledAt.takeIf { it > 0 } ?: i.installedAt, m.displayNameHe)
        synchronized(registry) {
            val current = registry.get(i.identity) ?: return
            if (current.sha256 != i.sha256 || !hub.active(current)) return
            synchronized(loaded) {
                if (loaded.containsKey(i.identity) || starting.putIfAbsent(i.identity, l) != null) return
                NativeMap.live(i.identity, l)
            }
        }
        try {
            val entry = try { loader.loadClass(s.entry) } catch (e: Throwable) {
                if ((e !is LinkageError && e !is ClassNotFoundException) || !olderApi(e)) throw e
                outdated += i.identity
                Log.i(TAG, "${i.identity}: built for an older Kav ($e)")
                return
            }
            outdated -= i.identity
            // The functions it says it hooks, looked up in this Kav before its code runs (MethodHooks).
            if (i.manifest.contributions.any { it.capability == MethodHooks.CAPABILITY }) MethodHooks.check(i.identity, i.manifest) { log(hub, i.identity, it) }
            val ext = entry.getDeclaredConstructor().newInstance() as KavExtension
            ext.start(Api(ctx.applicationContext, hub, i, l))
            synchronized(loaded) {
                // A switch or update may have retired it while start() was running.
                if (!starting.remove(i.identity, l)) return
                loaded[i.identity] = l
            }
            if (l.actions.isNotEmpty()) Actions.notifyChanged()
            // Listening to trips from its start: told the trip under way, if there is one.
            if (l.onTrip != null) runCatching { TripEvents.catchUp(i.identity) }
            // Its widgets on the home screen asked for their content now it's here.
            if (l.widgets.isNotEmpty()) Widgets.refresh(i.identity)
            Log.i(TAG, "${i.identity}: started, ${l.cards.size} cards, ${l.screens.size} screens")
        } finally {
            synchronized(loaded) {
                if (starting.remove(i.identity, l)) { NativeMap.drop(i.identity); release(l) }
            }
        }
    }

    private fun log(hub: Hub, identity: String, text: String) {
        ExtLog.init(hub.dir)
        runCatching { ExtLog.add(identity, JSONObject().put("t", System.currentTimeMillis() / 1000).put("log", text.take(4000))) }
    }

    // Hash the installed files in Bundle.Ext's order, retaining the same dex bytes that go to ART.
    internal fun verifiedDex(dir: File, digest: String, dexName: String): ByteArray {
        val files = dir.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(dir).invariantSeparatorsPath }
        var dex: ByteArray? = null
        val listed = files.keys.sorted().joinToString("\n") { name ->
            val bytes = files.getValue(name).readBytes()
            if (name == dexName) dex = bytes
            "$name ${sha256(bytes).hex()}"
        }
        if (sha256(listed.toByteArray()).hex() != digest) throw TrustError("its files changed since they were checked")
        return dex ?: throw TrustError("native: its dex isn't in the package")
    }

    // What an extension is given: plain types, Android's own classes.
    private class Api(private val app: Context, private val hub: Hub, private val i: Registry.Installed, private val l: Loaded) : Kav {
        override fun apiVersion() = Kav.API
        override fun context() = app
        override fun id() = i.identity
        override fun locale() = uk.noammm.kav.ui.T.lang.code
        override fun prefs() = Extensions.prefs().toString()

        // API 5: the rider's trips, each behind its own contribution and the rider's switch (TripAccess).
        override fun trips(): String? {
            declares("trips", TripAccess.HISTORY)
            if (!TripAccess.allows(i.identity, TripAccess.HISTORY)) return null
            return TripAccess.history(uk.noammm.kav.Prefs.trips(app)).toString()
        }

        override fun onTrip(listener: Kav.TripListener?) {
            declares("onTrip", TripAccess.FOLLOW)
            l.onTrip = listener
            // Set after its start (from start() itself, Native tells it once started): told the trip under way at once.
            if (listener != null && loaded[i.identity] === l) runCatching { TripEvents.catchUp(i.identity) }
        }

        // API 5: a home screen widget it declares (Widgets).
        override fun widget(id: String, widget: Kav.Widget?) {
            declares("widget", Widgets.CAPABILITY)
            require(Widgets.of(i.manifest).any { it.id == id }) { "$id isn't a widget its manifest declares" }
            if (widget == null) l.widgets.remove(id) else l.widgets[id] = widget
            // Set after its start: its widgets show it at once.
            if (loaded[i.identity] === l) Widgets.refresh(i.identity, id)
        }

        override fun refreshWidget(id: String) {
            declares("refreshWidget", Widgets.CAPABILITY)
            if (loaded[i.identity] === l) Widgets.refresh(i.identity, id)
        }

        // API 5: what Kav has of the timetable and live times (KavData), and the extension's rides in its ride history.
        override fun stops(query: String?): String? {
            val net = uk.noammm.kav.Loaded.net ?: return null
            return KavData.stops(net, JSONObject(query?.ifBlank { null } ?: "{}")).toString()
        }

        override fun vehicles(query: String?): String? {
            offMain("vehicles")
            val q = JSONObject(query?.ifBlank { null } ?: "{}")
            return try { KavData.vehicles(q).toString() } catch (e: java.io.IOException) { null }
        }

        override fun departures(query: String?): String? {
            offMain("departures")
            val net = uk.noammm.kav.Loaded.net ?: return null
            return KavData.departures(app, net, JSONObject(query?.ifBlank { null } ?: "{}"))?.toString()
        }

        override fun addRides(rides: String) {
            needs("addRides", null)
            val a = org.json.JSONArray(rides)
            val all = (0 until a.length()).map { a.getJSONObject(it) }
            val removed = all.mapNotNull { KavData.removed(i.identity, it) }.toSet()
            val list = all.filter { !it.optBoolean("removed") }.map { KavData.ride(i.identity, it) }
            android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { uk.noammm.kav.TicketStore.putRides(app, list, removed) } }
        }

        // API 5: the screen's picture and its pieces' boxes (ScreenPieces), under the crash guard, while it's loaded.
        override fun capture(captured: Kav.Captured) {
            declares("capture", ScreenPieces.CAPABILITY)
            uk.noammm.kav.ui.ScreenOverlay.capture(null) { picture, _, pieces ->
                if (loaded[i.identity] !== l) { picture?.recycle(); return@capture }
                NativeGuard.call(i.identity, "capture") { captured.captured(picture, pieces) }
            }
        }

        // And the screen without its pieces (ScreenOverlay), the same way.
        override fun captureBehind(captured: Kav.CapturedBehind) {
            declares("captureBehind", ScreenPieces.CAPABILITY)
            uk.noammm.kav.ui.ScreenOverlay.capture(null, behind = true) { picture, behind, pieces ->
                if (loaded[i.identity] !== l) { picture?.recycle(); behind?.recycle(); return@capture }
                NativeGuard.call(i.identity, "captureBehind") { captured.captured(picture, behind, pieces) }
            }
        }

        private fun offMain(what: String) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) throw IllegalStateException("$what asks the network: call it off the main thread")
        }

        private fun declares(what: String, capability: String) {
            if (i.manifest.contributions.none { it.capability == capability })
                throw IllegalStateException("$what needs a contribution with capability \"$capability\" in the manifest")
        }
        override fun card(on: String, card: Kav.Card) { l.cards[on] = card }
        override fun screen(page: String, screen: Kav.Screen) { l.screens[page] = screen }
        override fun home(screen: Kav.Screen) { l.screens[PageSpecs.HOME_PAGE] = screen }
        override fun map(): kav.kavapp.api.KavMap = NativeMap.of(i.identity, l)
        override fun provide(name: String, hook: Kav.Hook) {
            l.hooks[name] = hook
            // The trip notification's buttons (TripButtons): provided again, they changed.
            if (name == TripButtons.CAPABILITY) TripButtons.changed?.invoke()
        }
        override fun settings(): String =
            runCatching { ExtSettings.read(hub.registry.stateDir(i.identity), PageSpecs.of(i.manifest)).toString() }.getOrDefault("{}")
        override fun onSettings(changed: Kav.SettingsChanged?) { l.onSettings = changed }
        override fun settingsScreen(screen: Kav.Screen?) { l.settingsScreen = screen }
        // The answer comes on the main thread, under the crash guard like any call into its code.
        override fun pickPlace(picked: Kav.Picked) {
            uk.noammm.kav.ui.ExtPicks.ask { place -> NativeGuard.call(i.identity, "pickPlace") { picked.picked(place?.toString()) } }
        }
        override fun log(text: String) {
            ExtLog.init(hub.dir)
            runCatching { ExtLog.add(i.identity, JSONObject().put("t", System.currentTimeMillis() / 1000).put("log", text.take(4000))) }
        }
        override fun ui(): KavUi = ui?.invoke(i.identity) ?: throw IllegalStateException("Kav's look isn't ready")

        // API 4: only for a manifest that says "api": 4, and each with the contribution it names, so what Kav lists about
        // the extension (ExtUses) is all it does.
        private fun needs(what: String, capability: String?) {
            if (i.manifest.api < 4) throw IllegalStateException("$what needs \"api\": 4 in the manifest")
            if (capability != null && i.manifest.contributions.none { it.capability == capability })
                throw IllegalStateException("$what needs a contribution with capability \"$capability\" in the manifest")
        }

        override fun action(name: String, action: Kav.Action?) {
            needs("action", Actions.CAPABILITY + name)
            require(name in Actions.NAMES) { "$name isn't one of Kav's actions" }
            if (action == null) l.actions.remove(name) else l.actions[name] = action
            if (loaded[i.identity] === l) Actions.notifyChanged()
        }

        override fun kavAction(name: String, activity: Context, args: String?) {
            needs("kavAction", null)
            Actions.kav(name, activity, args.orEmpty())
        }

        override fun part(name: String, where: String, card: Kav.Card?) {
            needs("part", Parts.CAPABILITY + name)
            require(name in Parts.NAMES) { "$name isn't one of Kav's screen parts" }
            require(where in Parts.WHERE) { "where is \"before\", \"after\" or \"replace\"" }
            require(name != ScreenPieces.PART || where == "after") { "${ScreenPieces.PART} takes only \"after\": it is over Kav's screen, never instead of it" }
            if (card == null) l.parts.remove("$name|$where") else l.parts["$name|$where"] = card
            Parts.changed(name)
        }

        override fun addTicket(ticket: String) {
            needs("addTicket", null)
            uk.noammm.kav.ExtTickets.add(app, i.identity, JSONObject(ticket))
        }

        override fun hook(method: java.lang.reflect.Member, before: Kav.MethodHook?, after: Kav.MethodHook?): Kav.Unhook {
            needs("hook", MethodHooks.CAPABILITY)
            return MethodHooks.hook(i.identity, l, method, before, after)
        }

        override fun hook(className: String, method: String, params: Array<String>?, before: Kav.MethodHook?, after: Kav.MethodHook?): Kav.Unhook {
            needs("hook", MethodHooks.CAPABILITY)
            return MethodHooks.hook(i.identity, l, MethodHooks.Declared(className, method, params?.toList().orEmpty()), before, after) { log(it) }
        }

        override fun hooked(className: String, method: String, vararg params: String): Boolean {
            needs("hooked", MethodHooks.CAPABILITY)
            return MethodHooks.hooked(i.identity, MethodHooks.Declared(className, method, params.toList()))
        }

        override fun deoptimize(method: java.lang.reflect.Member): Boolean {
            needs("deoptimize", MethodHooks.CAPABILITY)
            return MethodHooks.deoptimize(method)
        }

        override fun kavClassLoader(): ClassLoader {
            needs("kavClassLoader", null)
            return Native::class.java.classLoader!!
        }
    }

    // A card's or screen's view, made under the crash guard: null when its code failed.
    fun view(identity: String, what: String, make: () -> View?): View? = NativeGuard.call(identity, what, make)
}

// Native code runs inside Kav, so its crash is Kav's crash. The guard counts each one against the extension that
// caused it: the one whose code Kav was calling (marked here), or, for a crash with no mark (a tap on its own view, a
// thread of its own), the one whose classes are in the crash's stack. A crash in a call Kav makes is caught and Kav
// carries on; any other one closes Kav, and is written down first. At the next start, an extension with 2 crashes in
// 5 minutes is switched off before anything of it loads, and Kav says which one and why. Safe mode (start without
// extensions) stays the way back in, so a crash can never leave Kav unable to start.
object NativeGuard {
    private const val TAG = "KavNativeGuard"
    const val CRASHES = 2
    // An extension Kav switched off because its code crashed Kav (Registry.Installed.problem).
    const val CRASHED = "crashed"
    const val WINDOW_MS = 5 * 60_000L

    private val mark = ThreadLocal<String?>()
    @Volatile private var file: File? = null
    // Tests set their own; Kav asks Native.
    @Volatile internal var owner: (String) -> String? = Native::ownerOf
    @Volatile internal var clock: () -> Long = System::currentTimeMillis
    private val mainThread: () -> Boolean = { runCatching { android.os.Looper.myLooper() == android.os.Looper.getMainLooper() }.getOrDefault(false) }
    @Volatile internal var onMain: () -> Boolean = mainThread

    // A call on the main thread still running at Android's own "not responding" length counts as a crash while it runs:
    // Android may end Kav there with no exception to catch, and the same view made at every start would freeze Kav every
    // time. One that then finishes isn't a crash, and its count goes (a busy phone is slow, not broken).
    const val HANG_MS = 5_000L
    @Volatile internal var hangMs = HANG_MS
    private val dog by lazy {
        java.util.concurrent.ScheduledThreadPoolExecutor(1) { r -> Thread(r, "kav-native-watch").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    }
    private fun startingFile() = file?.let { File(it.parentFile, "native-starting") }

    // The extension whose start() is running, or null once it's done.
    fun starting(identity: String?) {
        runCatching { startingFile()?.let { f -> if (identity == null) f.delete() else f.writeText(identity) } }
    }

    // The extensions switched off at this start: (identity, name).
    @Volatile var switchedOff: List<Pair<String, String>> = emptyList()
        private set

    fun open(dir: File) { file = File(dir, "native-crashes.json") }

    // Kav's handler for a crash it didn't catch: counted against its extension, then on to Kav's own handler.
    fun install() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        if (before is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(before))
    }

    class Handler(private val next: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { whose(e, mark.get())?.let { record(it) } }
            // Counted here already: not again at the next start.
            starting(null)
            next?.uncaughtException(t, e)
        }
    }

    // The extension a crash belongs to: the marked one, or the first of its stack's classes that one of them defined.
    internal fun whose(e: Throwable, marked: String?): String? {
        if (marked != null) return marked
        var x: Throwable? = e
        val seen = HashSet<Throwable>()
        while (x != null && seen.add(x)) {
            for (f in x.stackTrace) owner(f.className)?.let { return it }
            x = x.cause
        }
        return null
    }

    // Calls into an extension's code with its mark on. A failure is caught, counted as a crash and logged; null then.
    fun <T> call(identity: String, what: String, block: () -> T): T? {
        val before = mark.get()
        mark.set(identity)
        // RUNNING until the call ends (ENDED) or the watch counts it as hung (HUNG, at hungAt), whichever comes first.
        val state = java.util.concurrent.atomic.AtomicInteger(RUNNING)
        var hungAt = 0L
        val watch = if (onMain()) runCatching {
            dog.schedule(Runnable {
                runCatching { synchronized(this) { if (state.compareAndSet(RUNNING, HUNG)) hungAt = record(identity) } }
            }, hangMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull() else null
        return try {
            block()
        } catch (e: Throwable) {
            runCatching { Log.w(TAG, "$identity $what crashed: $e") }
            record(identity)
            null
        } finally {
            watch?.cancel(false)
            // Hung but finished: not a crash after all.
            if (watch != null) runCatching { synchronized(this) { if (!state.compareAndSet(RUNNING, ENDED)) unrecord(identity, hungAt) } }
            mark.set(before)
        }
    }

    private const val RUNNING = 0
    private const val ENDED = 1
    private const val HUNG = 2

    // Counts a crash now; answers its time.
    @Synchronized internal fun record(identity: String): Long {
        val now = clock()
        val f = file ?: return now
        val all = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        val mine = (all.optJSONArray(identity)?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty() + now).filter { now - it < WINDOW_MS }
        all.put(identity, JSONArray(mine))
        f.parentFile?.mkdirs()
        f.writeText(all.toString())
        return now
    }

    // A crash counted at `at` taken back (a hang that finished).
    @Synchronized private fun unrecord(identity: String, at: Long) {
        val f = file ?: return
        val all = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        val mine = all.optJSONArray(identity)?.let { a -> (0 until a.length()).map { a.optLong(it) } } ?: return
        val i = mine.indexOf(at).takeIf { it >= 0 } ?: return
        val left = mine.filterIndexed { j, _ -> j != i }
        if (left.isEmpty()) all.remove(identity) else all.put(identity, JSONArray(left))
        f.writeText(all.toString())
    }

    // At start, before any native extension loads: those with CRASHES crashes within WINDOW_MS. Their count starts
    // again.
    @Synchronized fun due(): List<String> {
        val f = file ?: return emptyList()
        // A start that never finished (a native crash, or a kill while it ran): one crash of that extension's.
        runCatching { startingFile()?.takeIf { it.isFile }?.let { s -> s.readText().trim().takeIf { it.isNotEmpty() }?.let(::record); s.delete() } }
        val all = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return emptyList()
        val now = clock()
        val out = all.keys().asSequence().toList().filter { k ->
            val a = all.optJSONArray(k) ?: return@filter false
            (0 until a.length()).count { now - a.optLong(it) < WINDOW_MS } >= CRASHES
        }
        if (out.isNotEmpty()) { out.forEach { all.remove(it) }; f.writeText(all.toString()) }
        return out
    }

    // Switches off the extensions due, with the reason Kav shows (CRASHED). The rider turns one on again on
    // its page.
    fun check(registry: Registry) {
        val off = due().mapNotNull { id ->
            val i = registry.get(id) ?: return@mapNotNull null
            registry.change(id) { it.copy(problem = CRASHED) }
            runCatching { Log.w(TAG, "$id switched off: it crashed Kav $CRASHES times") }
            id to i.displayName
        }
        if (off.isNotEmpty()) switchedOff = off
    }

    // Tests only.
    internal fun forget() { file = null; switchedOff = emptyList(); owner = Native::ownerOf; clock = System::currentTimeMillis; hangMs = HANG_MS; onMain = mainThread }
}
