package uk.noammm.kav.ext

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// What the rider picked an extension for, in its own versioned file (PLAN.md section 2, "Selections store"). A pick
// whose extension isn't there (uninstalled, off, blocked, safe mode) stays a pick and shows why it doesn't work; it never
// turns into Kav's own planner or another extension's by itself (Sources.stored does that for Kav's own choices).
object Selections {
    private const val FORMAT = 1
    @Volatile private var file: File? = null

    // The extensions' planners the rider switched on, in the order they were: each plans in a tab of its own on the
    // results, beside Kav's (KavPlan.plannerTabs).
    var planners by mutableStateOf(emptyList<Namespace>())
        private set

    // The extension's tab results open on, when the rider (or an extension, with results.planner.select) chose one.
    // Null: the tab of Kav's own planner (Settings › Live times and planners).
    var opens by mutableStateOf<Namespace?>(null)
        private set

    // Reads the file, if there is one. KavApp calls this only when the extensions folder exists.
    fun open(dir: File) {
        val f = File(dir, "selections.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            if (o.optInt("format") != FORMAT) return
            val slots = o.optJSONObject("slots") ?: return
            val listed = slots.optJSONArray("planners").objects().mapNotNull { Namespace.of(it.optString("namespace")) }
            // Before tabs, one extension's planner planned in place of Kav's: it keeps its tab, and results open on it.
            val old = slots.optJSONObject("planner")?.optString("namespace")?.let(Namespace::of)
            planners = if (slots.has("planners")) listed.distinct() else listOfNotNull(old)
            opens = if (slots.has("planners")) slots.optJSONObject("opens")?.optString("namespace")?.let(Namespace::of) else old
            chosen = Rules(
                slots.optJSONObject("walk")?.let(Rule::of), slots.optJSONObject("transfer")?.let(Rule::of),
                slots.optJSONArray("filters").objects().mapNotNull(Rule::of), slots.optJSONObject("rank")?.let(Rule::of),
                slots.optJSONArray("annotate").objects().mapNotNull(Rule::of),
            )
        }
    }

    // A route rule the rider chose (ext/Pipeline.kt): whose, which of its contributions, and whether a search without it
    // fails (PLAN.md 3.6). A ranker and annotators are never required.
    class Rule(val namespace: Namespace, val contribution: String, val required: Boolean) {
        fun json(): JSONObject = JSONObject().put("namespace", namespace.key).put("contribution", contribution).put("required", required)

        companion object {
            fun of(o: JSONObject): Rule? = Namespace.of(o.optString("namespace"))?.let { Rule(it, o.optString("contribution"), o.optBoolean("required")) }
        }
    }

    // One walking model, one transfer policy and one ranker at most; filters and annotators in the rider's order.
    class Rules(
        val walk: Rule? = null, val transfer: Rule? = null, val filters: List<Rule> = emptyList(), val rank: Rule? = null,
        val annotate: List<Rule> = emptyList(),
    ) {
        val empty get() = walk == null && transfer == null && filters.isEmpty() && rank == null && annotate.isEmpty()
        val all get() = listOfNotNull(walk, transfer) + filters + listOfNotNull(rank) + annotate

        // Only the rules whose contribution still has its slot's capability in the extension as installed now. A rule
        // whose contribution an update turned into something else is dropped: it would otherwise keep
        // running under its old capability with no line and no switch for it (the extension's page draws both from the
        // current manifest). One whose extension or contribution is gone stays, so a required one fails visibly.
        fun fitting(installed: (Namespace) -> Manifest?): Rules {
            fun ok(r: Rule?, capability: String) = r?.takeIf { fits(it, capability, installed) }
            return Rules(ok(walk, Pipeline.WALK), ok(transfer, Pipeline.TRANSFER), filters.filter { fits(it, Pipeline.FILTER, installed) },
                ok(rank, Pipeline.RANK), annotate.filter { fits(it, Pipeline.ANNOTATE, installed) })
        }

        private fun fits(r: Rule, capability: String, installed: (Namespace) -> Manifest?): Boolean {
            val mine = installed(r.namespace)?.contributions?.filter { it.id == r.contribution }.orEmpty()
            return mine.isEmpty() || mine.any { it.capability == capability }
        }
    }

    // The extensions as installed now, by namespace (the hub sets it); without it every rule is taken as chosen.
    @Volatile var installed: ((Namespace) -> Manifest?)? = null

    @Volatile private var chosen = Rules()

    // The rider's rules, less any an update turned into another capability (Rules.fitting).
    val rules: Rules get() = installed?.let { chosen.fitting(it) } ?: chosen

    // Saves the rules without the ones that no longer fit, after a source is added, updated or removed.
    @Synchronized fun prune(dir: File, installed: (Namespace) -> Manifest?) {
        val kept = chosen.fitting(installed)
        if (kept.all.size != chosen.all.size) save(dir, planners, opens, kept)
    }

    // Tests only: the choice forgotten in memory, the file left alone.
    internal fun forget() { planners = emptyList(); opens = null; chosen = Rules(); file = null; installed = null }

    // An extension's planner switched on (its tab added, last) or off (its tab gone, and results no longer open on it).
    @Synchronized fun setPlanner(dir: File, ns: Namespace, on: Boolean) {
        val next = if (on) (planners - ns) + ns else planners - ns
        save(dir, next, opens?.takeIf { it in next }, chosen)
    }

    // The extension's tab results open on; null for Kav's own. One whose planner isn't switched on is switched on.
    @Synchronized fun chooseOpens(dir: File, ns: Namespace?) =
        save(dir, if (ns == null || ns in planners) planners else planners + ns, ns, chosen)

    @Synchronized fun chooseRules(dir: File, r: Rules) = save(dir, planners, opens, r)

    // One extension's planner and rules, no longer chosen (it was uninstalled). Nothing written when none was its.
    @Synchronized fun drop(dir: File, ns: Namespace) {
        val c = chosen
        if (ns !in planners && c.all.none { it.namespace == ns }) return
        fun keep(r: Rule?) = r?.takeIf { it.namespace != ns }
        save(dir, planners - ns, opens?.takeIf { it != ns }, Rules(keep(c.walk), keep(c.transfer), c.filters.filter { it.namespace != ns }, keep(c.rank), c.annotate.filter { it.namespace != ns }))
    }

    private fun save(dir: File, tabs: List<Namespace>, first: Namespace?, r: Rules) {
        val f = file ?: File(dir, "selections.json").also { file = it }
        val slots = JSONObject()
        slots.put("planners", org.json.JSONArray(tabs.map { JSONObject().put("namespace", it.key) }))
        if (first != null) slots.put("opens", JSONObject().put("namespace", first.key))
        r.walk?.let { slots.put("walk", it.json()) }
        r.transfer?.let { slots.put("transfer", it.json()) }
        if (r.filters.isNotEmpty()) slots.put("filters", org.json.JSONArray(r.filters.map { it.json() }))
        r.rank?.let { slots.put("rank", it.json()) }
        if (r.annotate.isNotEmpty()) slots.put("annotate", org.json.JSONArray(r.annotate.map { it.json() }))
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(JSONObject().put("format", FORMAT).put("slots", slots).toString().toByteArray()); it.fd.sync() }
        if (!tmp.renameTo(f)) throw java.io.IOException("couldn't save the choice")
        planners = tabs
        opens = first
        chosen = r
    }
}

// Starting Kav with no extensions (PLAN.md section 2, Recovery): by the rider's switch, or by Kav itself after it closed
// with an error three times in ten minutes while extensions were installed. Managing them (checking for updates, removing a source)
// still works; nothing is registered and no extension's planner is asked.
object SafeMode {
    private const val CRASHES = 3
    private const val WINDOW_S = 600

    // Decided as Kav starts, for the whole run.
    @Volatile var active = false
        private set

    private fun marker(dir: File) = File(dir, "safe-mode")

    // "rider" or "crashes" while it's on, else null.
    fun why(dir: File): String? = marker(dir).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { "rider" }

    // Safe mode for one start only (SafeStart, from the shortcut): used up by the start that reads it.
    const val ONCE = "once"

    fun start(dir: File) {
        active = marker(dir).exists()
        if (active && why(dir) == ONCE) marker(dir).delete()
    }

    // Takes effect from the next start, as extensions are registered then.
    fun set(dir: File, on: Boolean, why: String = "rider") {
        if (on) { dir.mkdirs(); marker(dir).writeText(why) } else { marker(dir).delete(); File(dir, "crashes").delete() }
    }

    // Counts the times Kav closes with an error; the third within WINDOW_S turns safe mode on for the next start.
    fun watch(dir: File) {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashed(dir, System.currentTimeMillis() / 1000) }
            before?.uncaughtException(t, e)
        }
    }

    fun crashed(dir: File, nowS: Long) {
        val f = File(dir, "crashes")
        val recent = (runCatching { f.readLines() }.getOrDefault(emptyList()).mapNotNull { it.toLongOrNull() } + nowS)
            .filter { nowS - it < WINDOW_S }
        f.writeText(recent.joinToString("\n"))
        if (recent.size >= CRASHES && !marker(dir).exists()) marker(dir).writeText("crashes")
    }
}
