package uk.noammm.kav.ext

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

// Installed extensions' hooks in this run of Kav, asked of their native code (ext/Native.kt): their contributions
// registered so the rider can choose them (a planner, stop boards, route rules). A failure of their code goes to the
// extension's log, never against it. Made only when the extensions folder exists (ExtApp.start); in safe mode nothing
// registers.
object Hooks {
    private const val TAG = "KavExtHooks"
    // Hang guards (OPEN-PLAN section 3).
    private const val PLAN_MS = 30_000L
    private const val BOARD_MS = 30_000L

    @Volatile private var ctx: Context? = null
    @Volatile private var hub: Hub? = null
    @Volatile private var applied = -1L
    @Volatile private var byNs: Map<String, Registry.Installed> = emptyMap()

    val armed get() = ctx != null

    // Kav started with the extensions folder there (ExtApp.start): the hub is made when first needed, off the main thread.
    fun arm(c: Context) { ctx = c.applicationContext }

    // The hub was made (ExtApp.hub).
    fun attach(c: Context, h: Hub) {
        ctx = c.applicationContext
        hub = h
    }

    private fun ensure(): Boolean {
        if (hub != null) return true
        ExtApp.hub(ctx ?: return false)
        return hub != null
    }

    // Registers every active extension's contributions, again whenever the registry changed since.
    @Synchronized fun refresh() {
        if (SafeMode.active || !ensure()) return
        val h = hub ?: return
        val rev = h.registry.revision
        if (rev == applied) return
        val active = h.registry.installed.filter { h.active(it) && Native.declares(it.manifest) }
        for (old in byNs.values) if (active.none { it.identity == old.identity && it.sha256 == old.sha256 }) {
            for (c in old.manifest.contributions) Extensions.revoke(old.namespace, c.capability)
        }
        for (i in active) for (c in i.manifest.contributions) runCatching {
            val source: Any = when (c.capability) {
                Capabilities.BOARD -> NativeBoard(i)
                Capabilities.PLAN -> NativePlanner(i)
                in Pipeline.HOOKS -> i
                else -> return@runCatching
            }
            Extensions.register(Contribution(i.namespace, c.id, c.capability, i.sha256, source))
        }.onFailure { Log.w(TAG, "${i.identity} ${c.id}: ${it.message}") }
        byNs = active.associateBy { it.namespace.key }
        // Their slots show from install, unless the rider turned one off (Slots.Picks).
        val shown = h.registry.installed.filter { h.active(it) }.map { it.identity to it.manifest }
        if (runCatching { Slots.Picks.declare(h.dir, shown) }.getOrDefault(false)) uk.noammm.kav.ui.ExtShown.publish()
        applied = rev
    }

    fun installed(ns: Namespace): Registry.Installed? { refresh(); return byNs[ns.key] }

    // What the rider can pick for a rule: every active extension's contributions of that capability.
    fun offered(capability: String): List<Pair<Registry.Installed, Manifest.Declared>> {
        refresh()
        return byNs.values.flatMap { i -> i.manifest.contributions.filter { it.capability == capability }.map { i to it } }
    }

    // The rider's route rules now, pinned to the releases installed now. A rule whose extension isn't there stays, with
    // no release, so a required one fails the search rather than being dropped.
    fun snapshot(): Snapshot {
        val r = Selections.rules
        if (r.empty || SafeMode.active || !ensure()) return Snapshot.NONE
        refresh()
        val installed = hub?.registry?.installed.orEmpty()
        val active = byNs
        fun pin(rule: Selections.Rule?, required: Boolean = rule?.required == true): Pinned? = rule?.takeUnless { switchedOff(it, installed, active) }?.let {
            val i = active[it.namespace.key]
            val ok = i != null && i.manifest.contributions.any { c -> c.id == it.contribution }
            Pinned(it.namespace, it.contribution, if (ok) i!!.sha256 else "", i?.manifest?.release ?: 0, required,
                i?.displayName ?: installed.firstOrNull { x -> x.namespace == it.namespace }?.displayName ?: it.namespace.dataset,
                i?.manifest?.tags.orEmpty())
        }
        return Snapshot(pin(r.walk), pin(r.transfer), r.filters.mapNotNull { pin(it) }, pin(r.rank, false), r.annotate.mapNotNull { pin(it, false) })
    }

    // Ignore disabled extensions, including required rules. Missing extensions still report a failure.
    internal fun switchedOff(rule: Selections.Rule, installed: List<Registry.Installed>, active: Map<String, Registry.Installed>): Boolean =
        rule.namespace.key !in active && installed.any { it.namespace == rule.namespace && !it.enabled }

    // Whether the rider chose any route rules: when not, a search costs nothing more.
    fun hasRules() = !Selections.rules.empty && !SafeMode.active

    // A search's routes judged by the rider's rules now, or by a journey's pinned ones (replanning on the way). Each
    // failure goes to that extension's log.
    fun check(list: List<uk.noammm.kav.data.Transit.Itinerary>, r: uk.noammm.kav.data.Transit.Resolved, q: Pipeline.Query, snap: Snapshot = snapshot()): Pipeline.Checked {
        val c = Pipeline.run(list, r, q, snap, runner)
        for ((p, f) in c.failures) byNs[p.namespace.key]?.let { failed(it, p.contribution, f.why) }
        return c
    }

    // The pipeline's hook calls, each asked of the code of the release it's pinned to. Only the release installed now
    // has its code loaded: a journey pinned to the one before an update finds its rule missing.
    @Volatile internal var runner: Pipeline.Runner = Pipeline.Runner { p, hook, input, deadlineMs ->
        val i = byNs[p.namespace.key]?.takeIf { p.sha256.isNotEmpty() && it.sha256 == p.sha256 }
            ?: return@Runner Pipeline.Ran.Failed("missing")
        // Its code asked with the search as JSON, its answer read back as the pipeline reads every rule's.
        val search = runCatching { NativeRules.search(input) }.getOrElse { return@Runner Pipeline.Ran.Failed("undeliverable") }
        val got = Native.hook(i.identity, hook, search.toString(), deadlineMs) ?: return@Runner Pipeline.Ran.Failed("missing")
        got.fold(
            { text -> runCatching { Pipeline.Ran.Ok(NativeRules.answer(hook, search, text)) }.getOrElse { Pipeline.Ran.Failed("script") } },
            { e -> Pipeline.Ran.Failed(e.message ?: "crashed") },
        )
    }

    // A failure of an extension's code, written to its log (on its page). Nothing is switched off and nothing rolled
    // back by Kav (OPEN-PLAN section 3).
    internal fun failed(i: Registry.Installed, part: String, why: String) {
        Log.w(TAG, "${i.identity} $part failed: $why")
        val h = hub ?: return
        ExtLog.init(h.dir)
        runCatching { ExtLog.add(i.identity, JSONObject().put("t", System.currentTimeMillis() / 1000).put("part", part).put("failed", why)) }
    }

    // An installed extension's timetables and stop boards (Capabilities.BOARD), asked of its code: board.stops once for
    // its list of stops and lines, board.departures for one stop. Kav reads each answer (BoardAnswers).
    class NativeBoard(private val i: Registry.Installed) : BoardSource {
        override val versions = listOf(ContractVersion("board", 1, 0))

        override fun departures(stop: String, fromS: Long, limit: Int, v: ContractVersion, e: Execution): Outcome<List<BoardDeparture>> {
            val input = JSONObject().put("stop", stop).put("from", fromS).put("limit", limit).toString()
            val got = Native.hook(i.identity, "board.departures", input, BOARD_MS) ?: return Outcome.Unavailable()
            return got.fold(
                { BoardAnswers.departures(it, limit) },
                { x -> Outcome.Failed(x.message?.substringBefore(':') ?: "error") },
            )
        }

        override fun catalogue(v: ContractVersion, e: Execution): Outcome<Catalogue> {
            val got = Native.hook(i.identity, "board.stops", "{}", BOARD_MS * 2) ?: return Outcome.Unavailable()
            return got.fold(
                { BoardAnswers.catalogue(it) },
                { x -> Outcome.Failed(x.message?.substringBefore(':') ?: "error") },
            )
        }
    }

    // An installed extension's planner, asked of its code: Plan v2 text back (Extensions.ExtensionPlanner reads it
    // through Kav's own parser).
    class NativePlanner(private val i: Registry.Installed) : PlanSource {
        override val versions = listOf(ContractVersion("plan", 2, 0))
        override val stopovers = false

        override fun plan(q: PlanQuery, v: ContractVersion, e: Execution): Outcome<String> {
            val input = JSONObject().put("from", JSONArray().put(q.from.first).put(q.from.second))
                .put("to", JSONArray().put(q.to.first).put(q.to.second)).put("at", q.atS).put("way", q.way.toString())
                .put("modes", JSONArray(q.routeTypes)).put("settings", q.settings).toString()
            val got = Native.hook(i.identity, Capabilities.PLAN, input, PLAN_MS) ?: return Outcome.Unavailable()
            return got.fold(
                { if (it == "null") Outcome.Empty else Outcome.Ok(it) },
                { x -> Outcome.Failed(x.message?.substringBefore(':') ?: "error") },
            )
        }
    }
}
