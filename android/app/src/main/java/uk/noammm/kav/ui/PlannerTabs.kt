package uk.noammm.kav.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import uk.noammm.kav.Prefs
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Source

// Every planner's routes for one search (KavPlan.everyPlanner): asked all at once, so a tab opens on its routes as soon
// as they're in. Another search, or Try again, asks them all again.
internal class EveryPlanner(private val scope: CoroutineScope) {
    private var search: List<Any?>? = null
    private val asked = HashMap<Source, Deferred<Result<KavPlan.Planned>>>()

    // The routes of planner for search, with every tab's planner asked too. Called on the main thread.
    fun routes(search: List<Any?>, planner: Source, tabs: List<Source>, ask: (Source) -> KavPlan.Planned): Deferred<Result<KavPlan.Planned>> {
        if (search != this.search) {
            asked.values.forEach { it.cancel() }
            asked.clear()
            this.search = search
        }
        fun start(s: Source) = asked.getOrPut(s) { scope.async(Dispatchers.IO) { runCatching { ask(s) } } }
        val wanted = start(planner)
        tabs.forEach(::start)
        return wanted
    }
}

// A tab for each planner, right under Earlier and Later.
@Composable
internal fun PlannerTabs(tabs: List<Source>, planner: Source, onPick: (Source) -> Unit, modifier: Modifier = Modifier) {
    KavSegmented(tabs.map(::sourceName), tabs.indexOf(planner).coerceAtLeast(0), { i -> onPick(tabs[i]) }, modifier)
}

// Settings: every planner's routes, a tab each, in the order set here. Results open on the tab of the planner chosen
// above. Each search then goes to every planner with a tab, which the text says; a planner taken off the tabs isn't asked.
@Composable
internal fun EveryPlannerChoice() {
    val ctx = LocalContext.current
    InfoHeader(
        T("Routes from every planner", "מסלולים מכל המתכננים"),
        T(
            "Results get a tab for each planner under Earlier and Later, and open on the planner chosen above. Each search then goes to every online planner with a tab: each gets the trip's two points and time, and your IP address. On your phone plans locally. Hide a planner's tab and it isn't asked.",
            "בתוצאות יש לשונית לכל מתכנן מתחת למוקדם יותר ומאוחר יותר, והן נפתחות על המתכנן שנבחר למעלה. כל חיפוש נשלח אז לכל המתכננים המקוונים שיש להם לשונית: כל אחד מקבל את שתי הנקודות והשעה של המסלול, ואת כתובת ה-IP שלכם. התכנון בטלפון מתבצע מקומית. מסתירים לשונית של מתכנן, והוא לא נשאל.",
        ),
    )
    // The tabs in a new order: the planners offered take their new places among the slots they had, so one not offered
    // now (your own server, until one is set up) keeps its place.
    fun reorder(offered: List<Source>) {
        val next = offered.iterator()
        val order = KavPlan.tabOrder.map { if (it in offered && next.hasNext()) next.next() else it }
        KavPlan.tabOrder = order
        Prefs.setPlannerTabs(ctx, order)
    }
    // Laid out as the share page's apps (SwitchOrderRows): a switch shows or hides a planner's tab, and its handle drags it
    // to another place. The chosen planner's tab can't be hidden, so its switch stays on, dimmed.
    Column(Modifier.fillMaxWidth().panel(K.rCard).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        ToggleRow(
            T("A tab for each planner", "לשונית לכל מתכנן"), KavPlan.everyPlanner,
            { Icon(Icons.Rounded.Tab, null, tint = toggleInk(KavPlan.everyPlanner), modifier = Modifier.size(22.dp)) },
        ) { on -> KavPlan.everyPlanner = on; Prefs.setEveryPlanner(ctx, on) }
        if (KavPlan.everyPlanner) {
            val shown = KavPlan.tabs()
            SwitchOrderRows(
                KavPlan.offeredTabs(), { it.name }, ::sourceName,
                on = { it in shown },
                onToggle = { s ->
                    val hidden = if (s in shown) KavPlan.hiddenTabs + s else KavPlan.hiddenTabs - s
                    KavPlan.hiddenTabs = hidden
                    Prefs.setHiddenTabs(ctx, hidden)
                },
                onOrder = ::reorder,
                enabled = { it != KavPlan.source },
            )
        }
    }
}
