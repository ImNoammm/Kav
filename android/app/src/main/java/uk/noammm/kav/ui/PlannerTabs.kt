package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Source
import uk.noammm.kav.ext.Capabilities
import uk.noammm.kav.ext.Extensions
import uk.noammm.kav.ext.Hooks
import uk.noammm.kav.ext.Selections

// Every planner's routes for one search (KavPlan.everyPlanner): asked all at once, so a tab opens on its routes as soon
// as they're in. Another search, or Try again, asks them all again; so does the same search leaving now once its routes
// are older than it keeps them (LEAVE_NOW_KEPT_MS).
internal class EveryPlanner(private val scope: CoroutineScope, private val clock: () -> Long = System::currentTimeMillis) {
    private var search: List<Any?>? = null
    private var askedAt = 0L
    private val asked = HashMap<String, Deferred<Result<KavPlan.Planned>>>()

    // The routes of planner for search, with every tab's planner asked too, each by its tab's key (KavPlan.plannerTabs).
    // Called on the main thread. keepMs: how long the same search's routes are kept.
    fun routes(
        search: List<Any?>, planner: String, tabs: List<String>, keepMs: Long = Long.MAX_VALUE, ask: (String) -> KavPlan.Planned,
    ): Deferred<Result<KavPlan.Planned>> {
        val now = clock()
        if (search != this.search || now - askedAt > keepMs) {
            asked.values.forEach { it.cancel() }
            asked.clear()
            this.search = search
            askedAt = now
        }
        fun start(s: String) = asked.getOrPut(s) { scope.async(Dispatchers.IO) { runCatching { ask(s) } } }
        val wanted = start(planner)
        tabs.forEach(::start)
        return wanted
    }
}

// How long a search leaving now keeps its routes: they're planned from the minute asked, and a bus can go by in less.
internal const val LEAVE_NOW_KEPT_MS = 120_000L

// A tab for each planner, by its key (KavPlan.plannerTabs), right under Earlier and Later.
@Composable
internal fun PlannerTabs(tabs: List<String>, planner: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    KavSegmented(tabs.map(KavPlan::tabName), tabs.indexOf(planner).coerceAtLeast(0), { i -> onPick(tabs[i]) }, modifier)
}

// Settings: every planner's routes, a tab each, in the order set here: Kav's, and each extension's planner switched on.
// Results open on the tab chosen here, else the planner chosen above. Each search then goes to every planner with a tab,
// which the text says; a planner taken off the tabs isn't asked. Shown only when there are two planners to make tabs of
// (your own server set up too, or an extension's planner), sliding in when there are.
@Composable
internal fun EveryPlannerChoice() {
    val theirs = extensionPlanners()
    AnimatedVisibility(
        KavPlan.offeredTabs().size > 1 || theirs.isNotEmpty(),
        enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
    ) { Column { EveryPlannerRows(theirs) } }
}

// The extensions' planners by their tabs' keys: each active extension's that has one, and any switched on that isn't
// there now (it then says why on its tab), so it can be switched off. Read off the main thread.
@Composable
private fun extensionPlanners(): List<String> {
    val offered by produceState(emptyList<String>(), ExtUi.tick) {
        value = withContext(Dispatchers.IO) {
            runCatching { Hooks.offered(Capabilities.PLAN).map { Extensions.address(it.first.namespace) } }.getOrDefault(emptyList())
        }
    }
    return (offered + Selections.planners.map(Extensions::address)).distinct()
}

@Composable
private fun EveryPlannerRows(theirs: List<String>) {
    val ctx = LocalContext.current
    InfoHeader(
        T("Routes from every planner", "מסלולים מכל המתכננים"),
        T(
            "Results get a tab for each planner under Earlier and Later: Kav's, and each extension's switched on here. Each search then goes to every online planner with a tab: each gets the trip's two points and time, and your IP address. On your phone plans locally. An extension's planner is its own code, and gets the trip's two points and time. Hide a planner's tab and it isn't asked.",
            "בתוצאות יש לשונית לכל מתכנן מתחת למוקדם יותר ומאוחר יותר: של Kav, ושל כל תוסף שמופעל כאן. כל חיפוש נשלח אז לכל המתכננים המקוונים שיש להם לשונית: כל אחד מקבל את שתי הנקודות והשעה של המסלול, ואת כתובת ה-IP שלכם. התכנון בטלפון מתבצע מקומית. המתכנן של תוסף הוא הקוד שלו, והוא מקבל את שתי הנקודות והשעה של המסלול. מסתירים לשונית של מתכנן, והוא לא נשאל.",
        ),
    )
    val dir = uk.noammm.kav.ext.ExtApp.dir(ctx)
    // The tabs in a new order: the planners listed take their new places among the slots they had, so one not listed
    // now (your own server, until one is set up) keeps its place.
    fun reorder(listed: List<String>) {
        val base = KavPlan.tabOrder + listed.filter { it !in KavPlan.tabOrder }
        val next = listed.iterator()
        val order = base.map { if (it in listed && next.hasNext()) next.next() else it }
        KavPlan.tabOrder = order
        Prefs.setPlannerTabs(ctx, order)
    }
    // Laid out as the share page's apps (SwitchOrderRows): a switch shows or hides a planner's tab (an extension's
    // switches its planner on or off), and its handle drags it to another place. The chosen planner's tab can be hidden
    // only while an extension's tab is left, so until then its switch stays on, dimmed.
    val shown = KavPlan.plannerTabs()
    Column(Modifier.fillMaxWidth().panel(K.rCard).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        if (KavPlan.offeredTabs().size > 1) ToggleRow(
            T("A tab for each planner", "לשונית לכל מתכנן"), KavPlan.everyPlanner,
            { Icon(Icons.Rounded.Tab, null, tint = toggleInk(KavPlan.everyPlanner), modifier = Modifier.size(22.dp)) },
        ) { on -> KavPlan.everyPlanner = on; Prefs.setEveryPlanner(ctx, on) }
        if (KavPlan.everyPlanner || theirs.isNotEmpty()) {
            val kav = (if (KavPlan.everyPlanner) KavPlan.offeredTabs() else listOf(KavPlan.source)).map { it.key }
            SwitchOrderRows(
                KavPlan.ordered(kav + theirs, KavPlan.tabOrder), { it }, KavPlan::tabName,
                on = { it in shown },
                onToggle = { key ->
                    val s = Source.of(key)
                    val ns = Extensions.namespaceAt(key)
                    if (s != null) {
                        val hidden = if (key in shown) KavPlan.hiddenTabs + s else KavPlan.hiddenTabs - s
                        KavPlan.hiddenTabs = hidden
                        Prefs.setHiddenTabs(ctx, hidden)
                    } else if (ns != null) {
                        Selections.setPlanner(dir, ns, key !in shown)
                        ExtUi.changed()
                    }
                },
                onOrder = ::reorder,
                enabled = { it != KavPlan.source.key || shown.any(Extensions::isAddress) },
            )
        }
    }
    // Which tab results open on, once there are two; an extension can choose it too (results.planner.select).
    if (shown.size > 1) {
        InfoHeader(
            T("Results open on", "התוצאות נפתחות על"),
            T(
                "The tab results show first. Kav's opens on the planner chosen above. An extension can choose this too.",
                "הלשונית שהתוצאות מציגות קודם. של Kav נפתחת על המתכנן שנבחר למעלה. גם תוסף יכול לבחור את זה.",
            ),
        )
        KavSegmented(shown.map(KavPlan::tabName), shown.indexOf(KavPlan.opensOn(shown)).coerceAtLeast(0), { i -> chooseOpensOn(ctx, shown[i]) })
    }
}

// The tab results open on, by its key, from Settings or an extension (results.planner.select with "default"): one of
// Kav's planners is the one chosen above as well; an extension's planner gets its tab if it hadn't one. A key that's
// no planner's is ignored.
internal fun chooseOpensOn(ctx: android.content.Context, key: String) {
    val s = Source.of(key)?.takeIf { it in Source.PLANNING }
    val ns = Extensions.namespaceAt(key)?.takeIf { it in Selections.planners || Extensions.registered(it, Capabilities.PLAN) }
    if (s == null && ns == null) return
    if (s != null && s != KavPlan.source) { KavPlan.source = s; Prefs.setPlanSource(ctx, s) }
    // Kav's own is no file of the extensions' to write, unless one was chosen before.
    if (ns != null || Selections.opens != null) Selections.chooseOpens(uk.noammm.kav.ext.ExtApp.dir(ctx), ns)
    ExtUi.changed()
}
