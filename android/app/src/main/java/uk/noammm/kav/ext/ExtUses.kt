package uk.noammm.kav.ext

// What an extension is and what it uses, worked out from its signed manifest alone: its contributions (and their
// capabilities) and its settings. The screen words each line itself (ExtensionsScreen), so no line is
// publisher text and none can be faked; a line is only shown when the manifest establishes it.
//
// A publisher may attach a short explanation to a line (Manifest.explanations, by the line's key). It's shown under
// Kav's own words, labelled as theirs. It can't add, hide or reorder a line: one for a line this extension doesn't have
// is dropped.
//
// Each later kind or use is added here with a key, and gets explanations with no other change.
object ExtUses {
    enum class What { KIND, SEES_SEARCHES, CHANGES_RESULTS, KEEPS_DATA, PERMISSIONS, SCREENS, TAB, HOME, LOOK, SLOT_HOME, SLOT_RESULTS, SLOT_TRIP, SLOT_STOP, WIDGETS, NATIVE, ADDS, ACTIONS, PARTS, HOOKS }

    // key: what an explanation names it by. detail: the kind's capability, what it changes (as capabilities) or the
    // permissions. note: the publisher's explanation (English, Hebrew), when it gave one.
    class Line(val key: String, val what: What, val detail: List<String>, val note: Pair<String, String>?)

    // The kinds Kav runs today, in the order they're listed.
    val KINDS = listOf(Capabilities.PLAN, Capabilities.BOARD, StopPhotoHook.CAPABILITY, TripButtons.CAPABILITY, TripAccess.HISTORY, TripAccess.FOLLOW, Pipeline.WALK, Pipeline.TRANSFER, Pipeline.FILTER, Pipeline.RANK, Pipeline.ANNOTATE)
    // The kinds that are given the rider's trip searches (a stop photo is asked with the stop's place only; the trips
    // ones have lines of their own, and only what the rider allows).
    private val SEARCHING = KINDS - Capabilities.BOARD - StopPhotoHook.CAPABILITY - TripButtons.CAPABILITY - TripAccess.CAPABILITIES.toSet()
    // The route rules, by what they change in a search's results.
    val CHANGES = listOf(Pipeline.WALK, Pipeline.TRANSFER, Pipeline.FILTER, Pipeline.RANK, Pipeline.ANNOTATE)
    // Every key an explanation can name, for the developer kit: a kind's capability, or one of these.
    val KEYS = KINDS + listOf("searches", "results", "data", "native", "permissions", "actions", "parts", MethodHooks.CAPABILITY) + SCREEN_KEYS + Manifest.ADDS
    // Its own screens, a page the rider can pin as a tab, a Home body, a theme and home screen widgets (ext/Pages.kt,
    // ext/Looks.kt, ext/Widgets.kt).
    private val SCREEN_KEYS get() = listOf(PageSpecs.PAGE, "tab", PageSpecs.HOME, Looks.CAPABILITY, Widgets.CAPABILITY) + Slots.CAPABILITIES

    fun of(m: Manifest): List<Line> {
        val caps = m.contributions.map { it.capability }.toSet()
        return buildList {
            fun line(key: String, what: What, detail: List<String> = emptyList()) = add(Line(key, what, detail, m.explanations[key]))
            for (c in KINDS) if (c in caps) line(c, What.KIND, listOf(c))
            val screens = runCatching { PageSpecs.of(m) }.getOrNull()
            if (PageSpecs.PAGE in caps) line(PageSpecs.PAGE, What.SCREENS)
            if (screens?.tabs?.isNotEmpty() == true) line("tab", What.TAB)
            if (PageSpecs.HOME in caps) line(PageSpecs.HOME, What.HOME)
            if (Looks.declares(m)) line(Looks.CAPABILITY, What.LOOK)
            // What it says it adds that Kav can't see in its code (Manifest.adds): its maps, notifications, location, settings.
            for (a in m.adds) line(a, What.ADDS, listOf(a))
            // Its cards on Kav's own screens (ext/Slots.kt), on from when it's switched on.
            for ((k, what) in listOf(Slots.Kind.HOME to What.SLOT_HOME, Slots.Kind.RESULTS to What.SLOT_RESULTS, Slots.Kind.TRIP to What.SLOT_TRIP, Slots.Kind.STOP to What.SLOT_STOP))
                if (k.capability in caps) line(k.capability, what)
            // Its widgets on the home screen (ext/Widgets.kt), which show what its code gives them there.
            if (Widgets.CAPABILITY in caps) line(Widgets.CAPABILITY, What.WIDGETS)
            // API 4: the actions it takes over, the parts of Kav's screens it changes, and hooks into Kav's own code.
            val actions = Actions.NAMES.filter { Actions.CAPABILITY + it in caps }
            if (actions.isNotEmpty()) line("actions", What.ACTIONS, actions)
            val parts = Parts.NAMES.filter { Parts.CAPABILITY + it in caps }
            if (parts.isNotEmpty()) line("parts", What.PARTS, parts)
            if (MethodHooks.CAPABILITY in caps) line(MethodHooks.CAPABILITY, What.HOOKS)
            if (caps.any { it in SEARCHING }) line("searches", What.SEES_SEARCHES)
            val changes = CHANGES.filter { it in caps }
            if (changes.isNotEmpty()) line("results", What.CHANGES_RESULTS, changes)
            // One with settings keeps the rider's values (Registry's state folder).
            if (screens?.settings?.isNotEmpty() == true) line("data", What.KEEPS_DATA)
            // Its own Android code, inside Kav, with full access (Native).
            if (Native.declares(m)) line("native", What.NATIVE)
            val perms = (m.permissions + m.contributions.flatMap { it.permissions }).distinct()
            if (perms.isNotEmpty()) line("permissions", What.PERMISSIONS, perms)
        }
    }
}
