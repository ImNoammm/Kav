package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Transit

// The private pipeline (PLAN.md sections 3.5, 3.6, 4, 5): extensions' walking models, transfer policies, filters, a
// ranker and annotators judge a search's routes after its planner answered, and Kav checks every route again itself.
//
// Kav decides what can be caught (3.5). A walking model's times are taken as they are: never cut back to the planner's
// walk; only a negative one is refused. A route whose connection the
// new times or a transfer rule make impossible leaves the list; it is only counted, as "not catchable with your walking
// settings", never shown lower down. No later arrival is made up from a next departure. What the rider asked (leaving
// at, arriving by) is checked again after the times change, and the tags worked out again. There is no 60 s grace
// between legs.
//
// A contribution the rider marked required that fails, or doesn't answer by its deadline, fails the search visibly
// (3.6); an optional one is skipped with a note. Each hook is one call with every route of the search (section 4).
object Pipeline {
    const val WALK = "walk.time"
    const val TRANSFER = "transfer.policy"
    const val FILTER = "results.filter"
    const val RANK = "results.rank"
    const val ANNOTATE = "results.annotate"
    val HOOKS = listOf(WALK, TRANSFER, FILTER, RANK, ANNOTATE)

    // From the planner's answer to the last hook, for every hook together (O1 7.1); each gets what is left, at most its own.
    // A late rule is skipped for this search
    // (a required one fails it visibly) and never punished. With less than FLOOR_MS left a hook isn't asked.
    const val BUDGET_MS = 400L
    const val FLOOR_MS = 20L
    private val HOOK_MS = mapOf(WALK to 200L, TRANSFER to 150L, FILTER to 150L, RANK to 150L, ANNOTATE to 150L)

    private const val MOST_SPARE_S = 24 * 3600
    // The most Kav sends route rules about one search, and a planner's answer is held to (PlanChecks): plumbing.
    const val MAX_MESSAGE = 512 * 1024

    // The query as the routes were asked for. way: d leaving at, a arriving by, l the last way tonight. stays: the
    // seconds asked to stay at each stop on the way, in order.
    class Query(val way: Char, val atS: Long, val nowS: Long, val settings: String, val locale: String, val stays: List<Long> = emptyList())

    // What one hook call came back with: its answer, or why there is none.
    sealed class Ran {
        class Ok(val bytes: ByteArray) : Ran()
        // late, crashed, memory, interrupted, script, missing, undeliverable: written to the extension's log, never held
        // against it (OPEN-PLAN section 3).
        class Failed(val why: String) : Ran()
    }

    // Runs one hook of one pinned contribution in its extension's code (Hooks), within deadlineMs. Tests give their own.
    fun interface Runner {
        fun run(p: Pinned, hook: String, input: ByteArray, deadlineMs: Long): Ran
    }

    // A route a rule left out: the route as it was then, the rule (its extension's name is by.label), and the reason it
    // gave, cleaned (null when it gave none).
    class Hidden(val route: Transit.Itinerary, val by: Pinned, val reason: String?)

    // What the results can tell the rider about routes their rules left out: how many in all, and how many each
    // extension left out, in the order they did (its name, its count). Null when none was. The words and look are the
    // results screen's.
    class HiddenNotice(val count: Int, val by: List<Pair<String, Int>>)

    fun hiddenNotice(hidden: List<Hidden>): HiddenNotice? =
        if (hidden.isEmpty()) null else HiddenNotice(hidden.size, hidden.groupingBy { it.by.label }.eachCount().toList())

    // The routes to show when the rider asks to see what their rules left out, this once: the ones kept, then the ones
    // left out, in the order they were left out. Nothing is judged again and nothing is remembered.
    fun showOnce(routes: List<Transit.Itinerary>, hidden: List<Hidden>): List<Transit.Itinerary> =
        routes + hidden.map { it.route }.filter { h -> routes.none { it === h } }

    // A required contribution that failed: the search shows this instead of routes, and asks once for a fallback.
    class Failure(val slot: String, val pinned: Pinned, val why: String)

    class Checked(
        // What the rider can take, in order; every route carries the snapshot it was judged under.
        val routes: List<Transit.Itinerary>,
        // Left out because they can't be caught with the rider's walking or transfer settings.
        val notCatchable: Int,
        // Left out by a filter or a transfer rule that forbids the change.
        val hidden: Int,
        // Optional contributions that didn't answer in time or failed, so their part was skipped.
        val skipped: List<Pinned>,
        val failure: Failure?,
        // Every hook call that failed, for its extension's log.
        val failures: List<Pair<Pinned, Ran.Failed>>,
        val snapshot: Snapshot,
        // Why routes were hidden, as the filters and transfer rules said it: (the extension's name, its reason), as given.
        val reasons: List<Pair<String, String>> = emptyList(),
        // Of the skipped, those Kav couldn't ask: its message about these routes was more than a call carries. Not
        // their doing, and said so.
        val unsent: List<Pinned> = emptyList(),
        // Each route a rule left out (a filter, or a transfer rule that forbids a change), with the rule and its reason,
        // in the order they were left out: so the results can say how many and by which extension, and show them once.
        val hiddenRoutes: List<Hidden> = emptyList(),
    )

    internal class Walk(val route: Int, val leg: Int, val role: Int, val metres: Int, val inside: Int, val planned: Int, val fromKind: Int, val toKind: Int)
    internal class Change(val route: Int, val fromLeg: Int, val toLeg: Int, val fromMode: Int, val toMode: Int, val walkM: Int, val walkS: Int, val gapS: Int, val same: Boolean, val fromStop: String?, val toStop: String?)

    fun run(
        list: List<Transit.Itinerary>, r: Transit.Resolved, q: Query, snap: Snapshot, runner: Runner,
        budgetMs: Long = BUDGET_MS, clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): Checked {
        val start = clock()
        val failures = ArrayList<Pair<Pinned, Ran.Failed>>()
        val skipped = ArrayList<Pinned>()
        val unsent = ArrayList<Pinned>()
        val left = ArrayList<Hidden>()
        val reasons = LinkedHashSet<Pair<String, String>>()
        // A rule's reason, shown as given (the screen cuts what doesn't fit).
        fun said(why: String?): String? = why?.takeIf { it.isNotBlank() }
        fun reason(p: Pinned, why: String?) {
            val r = said(why) ?: return
            reasons += p.label to r
        }
        val tagged = snap.json
        // Taxi cards have nothing to walk or change; they keep their places.
        val subject = list.indices.filter { i -> list[i].legs.isNotEmpty() && !isTaxi(list[i]) }
        var routes = subject.map { list[it] }

        // Asks one contribution; null when it failed, which fails the search when it's required.
        fun ask(p: Pinned, hook: String, input: ByteArray): Bridge.Reader? {
            val own = HOOK_MS.getValue(hook)
            val left = budgetMs - (clock() - start)
            val got = if (left < FLOOR_MS) Ran.Failed("late") else runner.run(p, hook, input, minOf(own, left))
            val f = when (got) {
                is Ran.Ok -> try { return Bridge.Reader(got.bytes) } catch (e: Bridge.Bad) { Ran.Failed("malformed") }
                is Ran.Failed -> got
            }
            failures += p to f
            if (f.why == "undeliverable") unsent += p
            return null
        }
        fun done(failure: Failure?, out: List<Transit.Itinerary>, notCatchable: Int, hidden: Int) =
            Checked(out, notCatchable, hidden, skipped, failure, failures.toList(), snap, reasons.toList(), unsent.toList(),
                if (failure == null) left.toList() else emptyList())

        // Walking: one call with every walk of the search.
        var walkSecs: Map<Pair<Int, Int>, Int> = emptyMap()
        val walks = walksOf(routes, r)
        snap.walk?.let { p ->
            if (walks.isEmpty()) return@let
            val answer = ask(p, WALK, encode(q, routes, r, walks, emptyList()))
            val secs = answer?.let { runCatching { readWalks(it, walks) }.getOrNull() }
            if (secs == null) {
                if (answer != null) failures += p to Ran.Failed("malformed")
                if (p.required) return done(Failure(WALK, p, failures.last().second.why), emptyList(), 0, 0)
                skipped += p
            } else walkSecs = walks.indices.associate { (walks[it].route to walks[it].leg) to secs[it] }
        }

        // Each route's connections at the new walking times, then the transfer policy's word on each.
        val changes = changesOf(routes, r, walkSecs)
        var rules: Map<Pair<Int, Int>, IntArray> = emptyMap()
        var forbidden: Map<Pair<Int, Int>, String> = emptyMap()
        snap.transfer?.let { p ->
            if (changes.isEmpty()) return@let
            val answer = ask(p, TRANSFER, encode(q, routes, r, walks, changes))
            val read = answer?.let { runCatching { readChanges(it, changes) }.getOrNull() }
            if (read == null) {
                if (answer != null) failures += p to Ran.Failed("malformed")
                if (p.required) return done(Failure(TRANSFER, p, failures.last().second.why), emptyList(), 0, 0)
                skipped += p
            } else { rules = read.first; forbidden = read.second }
        }

        var notCatchable = 0
        var hidden = 0
        if (walkSecs.isNotEmpty() || rules.isNotEmpty() || forbidden.isNotEmpty()) {
            val kept = ArrayList<Transit.Itinerary>()
            routes.forEachIndexed { i, t ->
                forbidden.entries.firstOrNull { it.key.first == i }?.let { f ->
                    hidden++
                    snap.transfer?.let { p -> reason(p, f.value); left += Hidden(t, p, said(f.value)) }
                    return@forEachIndexed
                }
                val retimed = retime(t, i, q, walkSecs, rules)
                if (retimed == null) notCatchable++ else kept += retimed
            }
            // Kav's own tags are worked out again only when the rules changed a time or left a route out: a rule that
            // answered and changed nothing leaves the routes exactly as the planner gave them.
            val same = kept.size == routes.size && kept.indices.all { sameTimes(kept[it], routes[it]) }
            routes = if (same) kept else retag(kept)
        }

        // Filters, in the rider's order, each seeing what the one before left.
        for (p in snap.filters) {
            if (routes.isEmpty()) break
            val answer = ask(p, FILTER, encode(q, routes, r, emptyList(), emptyList()))
            val read = answer?.let { runCatching { readFilter(it, routes.size) }.getOrNull() }
            val drop = read?.first
            if (drop == null) {
                if (answer != null) failures += p to Ran.Failed("malformed")
                if (p.required) return done(Failure(FILTER, p, failures.last().second.why), emptyList(), notCatchable, hidden)
                skipped += p
                continue
            }
            hidden += drop.count { it }
            read.second.forEach { why -> if (why != null) reason(p, why) }
            routes.forEachIndexed { k, t -> if (drop[k]) left += Hidden(t, p, said(read.second[k])) }
            routes = routes.filterIndexed { k, _ -> !drop[k] }
        }

        // The ranker owns Recommended: a permutation of every route of the list itself (the main section).
        snap.rank?.let { p ->
            val main = routes.indices.filter { routes[it].sectionId == MAIN }
            if (main.size < 2) return@let
            val ranked = main.map { routes[it] }
            val answer = ask(p, RANK, encode(q, ranked, r, emptyList(), emptyList()))
            val order = answer?.let { runCatching { readRank(it, ranked.size) }.getOrNull() }
            if (order == null) {
                if (answer != null) failures += p to Ran.Failed("malformed")
                skipped += p
                return@let
            }
            val out = routes.toMutableList()
            main.forEachIndexed { k, at -> out[at] = ranked[order[k]] }
            routes = out
        }

        // Annotators: a tag is the text the hook answers (one its manifest declares shows with its declared words), after
        // Kav's own.
        for (p in snap.annotate) {
            if (routes.isEmpty()) break
            val answer = ask(p, ANNOTATE, encode(q, routes, r, emptyList(), emptyList()))
            val tags = answer?.let { runCatching { readTags(it, routes.size) }.getOrNull() }
            if (tags == null) {
                if (answer != null) failures += p to Ran.Failed("malformed")
                skipped += p
                continue
            }
            routes = routes.mapIndexed { k, t ->
                val mine = tags[k].filter { it.isNotBlank() }.distinct().map { Tags.of(p, it) }
                if (mine.isEmpty()) t else t.with(tags = t.tags + mine)
            }
        }

        // The taxi cards back where they were, after the routes judged before them.
        val taxis = list.filterIndexed { i, _ -> i !in subject }
        val firstTaxi = list.indexOfFirst { it !in subject.map(list::get) }
        val before = if (firstTaxi < 0) routes.size else minOf(routes.size, subject.count { it < firstTaxi })
        val ordered = routes.take(before) + taxis + routes.drop(before)
        return done(null, ordered.map { it.with(policy = tagged) }, notCatchable, hidden)
    }

    internal const val MAIN = 1
    // The most tags an annotator's answer may carry per route: plumbing for the reader, not a rule.
    const val MOST_TAGS = 64

    // Every leg leaves and arrives when it did (the tags a rule added aside).
    private fun sameTimes(a: Transit.Itinerary, b: Transit.Itinerary) = a.dep == b.dep && a.arr == b.arr &&
        a.legs.size == b.legs.size && a.legs.indices.all { a.legs[it].dep == b.legs[it].dep && a.legs[it].arr == b.legs[it].arr }

    internal fun isTaxi(t: Transit.Itinerary) =
        t.legs.any { it.kind == Transit.LegKind.TAXI } && t.legs.none { it.kind == Transit.LegKind.RIDE }

    private fun anchor(l: Transit.Leg) = l.kind != Transit.LegKind.WALK && l.kind != Transit.LegKind.WAIT

    // Every walk in the routes, with its place in the trip: before the first ride, between two, after the last, or all of it.
    // The largest message a search's rules would be sent about these routes, built as the transfer call builds it (every
    // walk and every change with the routes), and its size measured (Extensions: a planner's answer over a call's limit
    // is refused whole).
    internal fun largestMessage(q: Query, routes: List<Transit.Itinerary>, r: Transit.Resolved): Int =
        encode(q, routes, r, walksOf(routes, r), changesOf(routes, r, emptyMap())).size

    private fun walksOf(routes: List<Transit.Itinerary>, r: Transit.Resolved): List<Walk> {
        val out = ArrayList<Walk>()
        routes.forEachIndexed { i, t ->
            val first = t.legs.indexOfFirst(::anchor)
            val last = t.legs.indexOfLast(::anchor)
            t.legs.forEachIndexed { j, l ->
                if (l.kind != Transit.LegKind.WALK) return@forEachIndexed
                val role = when { first < 0 -> 3; j < first -> 0; j > last -> 2; else -> 1 }
                val prev = t.legs.subList(0, j).lastOrNull(::anchor)
                val next = t.legs.subList(j + 1, t.legs.size).firstOrNull(::anchor)
                out += Walk(i, j, role, l.meters, l.insideMeters, (l.arr - l.dep).toInt().coerceAtLeast(0), kindOf(prev, r, true), kindOf(next, r, false))
            }
        }
        return out
    }

    // place 0, a stop 1, a station 2 (a train's).
    private fun kindOf(ride: Transit.Leg?, r: Transit.Resolved, end: Boolean): Int = when {
        ride == null || ride.kind != Transit.LegKind.RIDE -> 0
        r.isTrain(ride) -> 2
        else -> 1
    }

    // The connections between consecutive rides, with the walk between at its (new) time.
    private fun changesOf(routes: List<Transit.Itinerary>, r: Transit.Resolved, secs: Map<Pair<Int, Int>, Int>): List<Change> {
        val out = ArrayList<Change>()
        routes.forEachIndexed { i, t ->
            val rides = t.legs.indices.filter { t.legs[it].kind == Transit.LegKind.RIDE }
            for ((a, b) in rides.zipWithNext()) {
                // Not across a stop the rider stays at: they leave it when they choose.
                if (t.legs.subList(a + 1, b + 1).any { it.visit?.stay == true }) continue
                if (t.legs.subList(a + 1, b).any { anchor(it) }) continue
                val walking = (a + 1 until b).filter { t.legs[it].kind == Transit.LegKind.WALK }
                val walkS = walking.sumOf { secs[i to it] ?: (t.legs[it].arr - t.legs[it].dep).toInt() }
                val from = t.legs[a]; val to = t.legs[b]
                out += Change(i, a, b, mode(from, r), mode(to, r), walking.sumOf { t.legs[it].meters }, walkS,
                    (to.dep - from.arr - walkS).toInt(), from.toStop == to.fromStop, stopRef(from.toStop, r), stopRef(to.fromStop, r))
            }
        }
        return out
    }

    internal fun mode(l: Transit.Leg, r: Transit.Resolved) = r.line(l.lineId)?.agencyId?.let(r::routeType) ?: -1

    // Its route on the same time line again, at the new walking times, or null when it can't be caught (PLAN.md 3.5).
    private fun retime(t: Transit.Itinerary, i: Int, q: Query, secs: Map<Pair<Int, Int>, Int>, rules: Map<Pair<Int, Int>, IntArray>): Transit.Itinerary? {
        val legs = t.legs.toMutableList()
        val anchors = legs.indices.filter { anchor(legs[it]) }
        fun walkS(j: Int) = secs[i to j]?.toLong() ?: (legs[j].arr - legs[j].dep).coerceAtLeast(0)
        val soft = HashSet<Int>()
        var stay = 0
        if (anchors.isEmpty()) {
            // Walking all the way: leaves when it did, gets there when the walk says.
            var at = t.dep
            for (j in legs.indices) if (legs[j].kind == Transit.LegKind.WALK) { val s = walkS(j); legs[j] = legs[j].copy(dep = at, arr = at + s); at += s }
            if (q.way == 'a' && at > maxOf(q.atS, t.arr)) return null
            return t.with(legs = legs, dep = legs.first().dep, arr = at)
        }
        // Before the first anchor: leave when it did if the walks still fit, else earlier; never before what was asked.
        val first = anchors.first()
        val access = (0 until first).filter { legs[it].kind == Transit.LegKind.WALK }
        val accessS = access.sumOf(::walkS)
        val boards = legs[first].dep
        val leave = if (t.dep + accessS <= boards) t.dep else boards - accessS
        val floor = minOf(if (q.way == 'd') q.atS else q.nowS, t.dep)
        if (leave < floor) return null
        place(legs, 0, first, leave, boards, ::walkS)
        // Between anchors: the walks, then at least the spare the transfer rules ask, before the next one leaves.
        for ((a, b) in anchors.zipWithNext()) {
            val arrives = legs[a].arr
            val leaves = legs[b].dep
            val walking = (a + 1 until b).filter { legs[it].kind == Transit.LegKind.WALK }.sumOf(::walkS)
            val visit = legs.subList(a + 1, b + 1).firstOrNull { it.visit != null }?.visit
            val need = when {
                visit?.stay == true -> q.stays.getOrNull(stay++) ?: 0L
                else -> {
                    if (visit != null) stay++
                    val rule = rules[i to b]
                    when {
                        rule == null -> 0L
                        rule[1] and 1 != 0 -> rule[0].toLong()
                        else -> { if (arrives + walking + rule[0] > leaves) soft += b; 0L }
                    }
                }
            }
            val at = arrives + walking
            if (at + need > leaves) return null
            place(legs, a + 1, b, arrives, leaves, ::walkS)
            // Other lines from the same stop that leave before the rider gets there are not ways to take this ride.
            val reach = at + need
            prune(legs, b, reach)
        }
        // After the last anchor: the walks from its arrival; arriving by a time is checked again.
        val last = anchors.last()
        var at = legs[last].arr
        for (j in last + 1 until legs.size) {
            if (legs[j].kind == Transit.LegKind.WALK) { val s = walkS(j); legs[j] = legs[j].copy(dep = at, arr = at + s); at += s }
            else legs[j] = legs[j].copy(dep = at, arr = at)
        }
        if (q.way == 'a' && at > maxOf(q.atS, t.arr)) return null
        val tags = if (soft.isNotEmpty() && "risky_transfer" !in t.tags) t.tags + "risky_transfer" else t.tags
        return t.with(legs = legs, dep = legs.first().dep, arr = at, tags = tags)
    }

    // The legs from..until (exclusive), from `at`: each walk at its time, the waits up to `until`'s departure.
    private fun place(legs: MutableList<Transit.Leg>, from: Int, until: Int, at0: Long, leaves: Long, walkS: (Int) -> Long) {
        var at = at0
        for (j in from until until) {
            val l = legs[j]
            legs[j] = when (l.kind) {
                Transit.LegKind.WALK -> { val s = walkS(j); l.copy(dep = at, arr = at + s).also { at += s } }
                Transit.LegKind.WAIT -> l.copy(dep = minOf(at, leaves), arr = leaves,
                    alternatives = l.alternatives.map { o -> o.copy(dep = minOf(at, o.arr)) })
                else -> l
            }
        }
    }

    // A ride's other ways (Leg.alternatives) that leave before `reach` go, from the ride and from its wait.
    private fun prune(legs: MutableList<Transit.Leg>, ride: Int, reach: Long) {
        val l = legs[ride]
        if (l.alternatives.isEmpty()) return
        val keep = l.alternatives.indices.filter { k -> k == 0 || l.alternatives[k].dep >= reach }
        if (keep.size == l.alternatives.size) return
        legs[ride] = l.copy(alternatives = keep.map { l.alternatives[it] })
        val w = ride - 1
        if (w >= 0 && legs[w].kind == Transit.LegKind.WAIT && legs[w].alternatives.size == l.alternatives.size)
            legs[w] = legs[w].copy(alternatives = keep.map { legs[w].alternatives[it] })
    }

    // Kav's own tags worked out again over what's left, when any time changed: the planner's other tags stay.
    private fun retag(list: List<Transit.Itinerary>): List<Transit.Itinerary> {
        if (list.isEmpty()) return list
        val own = uk.noammm.kav.data.RouteTags.ORDER.toSet()
        val bare = list.map { it.with(tags = emptyList()) }
        val fresh = uk.noammm.kav.data.RouteTags.of(bare)
        return list.indices.map { k ->
            val theirs = list[k].tags.filter { it !in own }
            val risky = if ("risky_transfer" in list[k].tags && "risky_transfer" !in fresh[k].tags) listOf("risky_transfer") else emptyList()
            list[k].with(tags = (theirs + risky + fresh[k].tags).distinct())
        }
    }

    internal fun stopRef(id: Int, r: Transit.Resolved): String? =
        RefTable.ref(id)?.toString() ?: r.stop(id)?.ministryCode()?.let { Namespace.MINISTRY.ref(Kind.STOP, it.toString()).toString() }

    internal fun lineRef(id: Int): String? =
        RefTable.ref(id)?.toString() ?: KavPlan.routeOf(id)?.let { Namespace.MINISTRY.ref(Kind.ROUTE, it.toString()).toString() }

    internal fun tripRef(id: Long): String? =
        RefTable.tripRef(id)?.toString() ?: KavPlan.tripKeyOf(id)?.let { Namespace.MINISTRY.ref(Kind.TRIP, it).toString() }

    private val KINDS = listOf(Transit.LegKind.WALK, Transit.LegKind.WAIT, Transit.LegKind.RIDE, Transit.LegKind.TAXI, Transit.LegKind.BIKE, Transit.LegKind.OTHER)

    private fun e6(v: Double?) = if (v == null || !v.isFinite()) 0 else Math.round(v * 1e6).toInt()

    // A route as JSON, in the shape route rules get it (NativeRules.search): what a trip slot is told.
    fun routeJson(t: Transit.Itinerary, r: Transit.Resolved): JSONObject =
        JSONObject().put("provider", t.provider).put("dep", t.dep).put("arr", t.arr).put("fare", t.fare).put("accessible", t.accessible)
            .put("tags", JSONArray(t.tags)).put("legs", JSONArray(t.legs.map { legJson(it, r) }))

    // One leg of a route, as routeJson gives each.
    fun legJson(l: Transit.Leg, r: Transit.Resolved): JSONObject {
        fun point(stop: Int, end: Pair<Double, Double>?): JSONObject {
            val s = r.stop(stop)
            return JSONObject().put("stop", if (stop >= 0) stopRef(stop, r) else JSONObject.NULL).put("name", s?.shown() ?: JSONObject.NULL)
                .put("lat", s?.lat ?: end?.first ?: JSONObject.NULL).put("lon", s?.lon ?: end?.second ?: JSONObject.NULL)
        }
        val ride = l.kind == Transit.LegKind.RIDE
        val toStop = if (l.kind == Transit.LegKind.WAIT) l.fromStop else l.toStop
        return JSONObject().put("kind", l.kind.name.lowercase()).put("dep", l.dep).put("arr", l.arr).put("metres", l.meters)
            .put("insideMetres", l.insideMeters).put("from", point(l.fromStop, l.shape.firstOrNull())).put("to", point(toStop, l.shape.lastOrNull()))
            .put("line", if (ride) lineRef(l.lineId) ?: JSONObject.NULL else JSONObject.NULL)
            .put("lineNumber", if (ride) l.shortName.ifBlank { r.line(l.lineId)?.number.orEmpty() } else JSONObject.NULL)
            .put("mode", if (ride) mode(l, r) else -1).put("trip", if (ride) tripRef(l.tripId) ?: JSONObject.NULL else JSONObject.NULL)
            .put("stops", l.stops.size)
    }

    // A search as route rules are sent it (Bridge), which NativeRules turns into their JSON.
    internal fun encode(q: Query, routes: List<Transit.Itinerary>, r: Transit.Resolved, walks: List<Walk>, changes: List<Change>): ByteArray {
        val w = Bridge.Writer()
        w.int(q.way.code).long(q.atS).long(q.nowS).str(q.settings).str(q.locale)
        w.int(routes.size)
        for (t in routes) {
            w.str(t.provider).long(t.dep).long(t.arr).int(t.fare).int(if (t.accessible) 1 else 0)
            w.int(t.tags.size); t.tags.forEach { w.str(it) }
            w.int(t.legs.size)
            for (l in t.legs) {
                w.int(KINDS.indexOf(l.kind)).long(l.dep).long(l.arr).int(l.meters).int(l.insideMeters)
                // A wait is at one stop, the one its ride leaves from: both its ends are that stop.
                val toStop = if (l.kind == Transit.LegKind.WAIT) l.fromStop else l.toStop
                for ((stop, end) in listOf(l.fromStop to l.shape.firstOrNull(), toStop to l.shape.lastOrNull())) {
                    val s = r.stop(stop)
                    w.str(if (stop >= 0) stopRef(stop, r) else null).str(s?.shown())
                    w.int(e6(s?.lat ?: end?.first)).int(e6(s?.lon ?: end?.second))
                }
                val ride = l.kind == Transit.LegKind.RIDE
                w.str(if (ride) lineRef(l.lineId) else null).str(if (ride) l.shortName.ifBlank { r.line(l.lineId)?.number.orEmpty() } else null)
                w.int(if (ride) mode(l, r) else -1).str(if (ride) tripRef(l.tripId) else null).int(l.stops.size)
            }
        }
        w.int(walks.size)
        for (k in walks) w.int(k.route).int(k.leg).int(k.role).int(k.metres).int(k.inside).int(k.planned).int(k.fromKind).int(k.toKind)
        w.int(changes.size)
        for (c in changes) w.int(c.route).int(c.fromLeg).int(c.toLeg).int(c.fromMode).int(c.toMode).int(c.walkM).int(c.walkS)
            .int(c.gapS).int(if (c.same) 1 else 0).str(c.fromStop).str(c.toStop)
        return w.bytes()
    }

    // A walking model's times: whole seconds, none negative (an end after a start). Whether a walk is believable is the
    // extension's business.
    private fun readWalks(a: Bridge.Reader, walks: List<Walk>): IntArray {
        val n = a.count(walks.size)
        if (n != walks.size) throw Bridge.Bad("an answer for each walk")
        return IntArray(n) { k ->
            val s = a.int()
            val w = walks[k]
            if (s < 0) throw Bridge.Bad("walk ${k}: $s s for ${w.metres} m")
            s
        }
    }

    // A transfer policy's word on each change: the spare it needs and whether it's a hard rule, or that it's forbidden.
    private fun readChanges(a: Bridge.Reader, changes: List<Change>): Pair<Map<Pair<Int, Int>, IntArray>, Map<Pair<Int, Int>, String>> {
        val n = a.count(changes.size)
        if (n != changes.size) throw Bridge.Bad("an answer for each change")
        val rules = HashMap<Pair<Int, Int>, IntArray>()
        val forbid = HashMap<Pair<Int, Int>, String>()
        for (c in changes) {
            val spare = a.int(); val flags = a.int(); val why = a.str()
            if (spare < 0 || spare > MOST_SPARE_S) throw Bridge.Bad("spare $spare")
            if (flags and 2 != 0) forbid[c.route to c.toLeg] = why.orEmpty()
            rules[c.route to c.toLeg] = intArrayOf(spare, flags)
        }
        return rules to forbid
    }

    // Each route kept or dropped, and for each dropped one the reason it gave (null otherwise).
    private fun readFilter(a: Bridge.Reader, n: Int): Pair<BooleanArray, List<String?>> {
        if (a.count(n) != n) throw Bridge.Bad("an answer for each route")
        val why = arrayOfNulls<String>(n)
        val drop = BooleanArray(n) { k ->
            val d = a.int(); val r = a.str()
            if (d != 0 && d != 1) throw Bridge.Bad("keep or drop")
            if (d == 1) why[k] = r
            d == 1
        }
        return drop to why.toList()
    }

    private fun readRank(a: Bridge.Reader, n: Int): IntArray {
        if (a.count(n) != n) throw Bridge.Bad("every route once")
        val order = IntArray(n) { a.int() }
        if (order.sorted() != (0 until n).toList()) throw Bridge.Bad("not a permutation")
        return order
    }

    private fun readTags(a: Bridge.Reader, n: Int): List<List<String>> {
        if (a.count(n) != n) throw Bridge.Bad("an answer for each route")
        return List(n) { List(a.count(MOST_TAGS)) { a.str().orEmpty() } }
    }
}

// The contributions a search is judged by, pinned to the package releases they came from (A2 3.1, PLAN.md section 2
// "Journey pinning"): kept on every route as JSON, so a journey started from one, and planned again on the way, is
// judged by the same ones, not by whatever the rider has picked since.
class Pinned(
    val namespace: Namespace, val contribution: String, val sha256: String, val release: Long, val required: Boolean,
    val label: String = "", val tags: Map<String, Pair<String, String>> = emptyMap(),
) {
    fun json(): JSONObject = JSONObject().put("ns", namespace.key).put("c", contribution).put("sha", sha256).put("rel", release)
        .put("req", required).put("label", label)
        .put("tags", JSONObject().apply { tags.forEach { (k, v) -> put(k, JSONArray().put(v.first).put(v.second)) } })

    companion object {
        fun of(o: JSONObject): Pinned? {
            val ns = Namespace.of(o.optString("ns")) ?: return null
            val t = o.optJSONObject("tags")
            val tags = t?.keys()?.asSequence()?.associateWith { k -> t.getJSONArray(k).let { it.optString(0) to it.optString(1) } }.orEmpty()
            return Pinned(ns, o.optString("c"), o.optString("sha"), o.optLong("rel"), o.optBoolean("req"), o.optString("label"), tags)
        }
    }
}

class Snapshot(
    val walk: Pinned? = null, val transfer: Pinned? = null, val filters: List<Pinned> = emptyList(),
    val rank: Pinned? = null, val annotate: List<Pinned> = emptyList(),
) {
    val all get() = listOfNotNull(walk, transfer) + filters + listOfNotNull(rank) + annotate
    val empty get() = all.isEmpty()

    val json: String by lazy {
        if (empty) "" else JSONObject().put("v", 1).put("walk", walk?.json()).put("transfer", transfer?.json())
            .put("filters", JSONArray(filters.map { it.json() })).put("rank", rank?.json())
            .put("annotate", JSONArray(annotate.map { it.json() })).toString()
    }

    companion object {
        val NONE = Snapshot()

        // The same, or null when the text isn't one whole snapshot (damaged, or edited by hand): a saved journey whose
        // rules can't all be read is replanned under none of them, never under some (Replan).
        fun parseWhole(s: String): Snapshot? {
            if (s.isEmpty()) return NONE
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return null
            val singles = listOf("walk", "transfer", "rank").map { o.opt(it) }.filter { it != null && it != JSONObject.NULL }
            val lists = listOf("filters", "annotate").flatMap { k -> o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.opt(it) } }.orEmpty() }
            val all = singles + lists
            if (all.any { it !is JSONObject || runCatching { Pinned.of(it) }.getOrNull() == null }) return null
            return parse(s)
        }

        fun parse(s: String): Snapshot {
            if (s.isEmpty()) return NONE
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return NONE
            fun list(k: String) = o.optJSONArray(k)?.objects()?.mapNotNull(Pinned::of).orEmpty()
            return Snapshot(o.optJSONObject("walk")?.let(Pinned::of), o.optJSONObject("transfer")?.let(Pinned::of), list("filters"),
                o.optJSONObject("rank")?.let(Pinned::of), list("annotate"))
        }
    }
}

// An annotator's tag on a route, kept as "x:<namespace>:<contribution>:<tag>" with its words, so it is never taken for
// one of Kav's own tags.
object Tags {
    private const val PREFIX = "x:"
    private val words = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, String>>()

    fun of(p: Pinned, tag: String): String {
        val k = "$PREFIX${p.namespace.key}:${p.contribution}:$tag"
        // Its declared words, or the tag itself as its words in both languages.
        val w = p.tags[tag] ?: (tag to tag)
        words[k] = Triple(w.first, w.second, p.label)
        return k
    }

    fun isTheirs(tag: String) = tag.startsWith(PREFIX)

    // English, Hebrew and whose; a tag not learnt in this run is its own words.
    fun word(tag: String): Triple<String, String, String>? = words[tag]
        ?: tag.takeIf(::isTheirs)?.substringAfterLast(':')?.takeIf { it.isNotEmpty() }?.let { Triple(it, it, "") }

    // A snapshot's tags learnt again (a journey reopened after a restart).
    fun learn(s: Snapshot) { for (p in s.annotate) for (t in p.tags.keys) of(p, t) }
}

// The same route with some of it changed; everything else as it was.
internal fun Transit.Itinerary.with(
    legs: List<Transit.Leg> = this.legs, dep: Long = this.dep, arr: Long = this.arr, tags: List<String> = this.tags,
    policy: String = this.policy,
) = Transit.Itinerary(guid, group, legs, dep, arr, fare, currency, co2g, accessible, tags, section, sectionId, singleFares, provider, policy)
