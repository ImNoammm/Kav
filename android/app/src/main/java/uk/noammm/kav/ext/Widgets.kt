package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Home screen widgets an extension fills. Android only knows the widgets an app declares in its own manifest, and an
// extension's code loads while Kav runs, so Kav owns them: two of its own (ExtWidgets.kt: a small one and a wide one,
// both resizable), and the rider chooses, as one is placed, which extension's widget it shows. A package declares its
// widgets in its signed manifest:
//
//   "contributions": [{"id": "widgets", "capability": "widget.provide"}]
//   "widgets": [{"id": "next", "title": {"en": "Next shuttles", "he": "..."}, "description": {"en": "...", "he": "..."},
//                "size": "wide"}]
//
// size is the one it looks best at, "small" or "wide": Kav's Add to home screen places that one. Its code gives the
// content (Kav.widget) as JSON text, which Kav draws in its own look, never views of the extension's:
//
//   {"title": "Next shuttles", "rows": [{"title": "Harbor", "subtitle": "Platform 2", "value": "14:05", "icon": "bus",
//    "line": "3", "action": {...}}], "note": "From the shuttle's timetable", "empty": "No more shuttles today",
//    "action": {...}, "next": 600}
//
// A row is a page's row (Blocks) with a line plate besides; rows Kav has no room for are left out. The actions are a
// page's (settings, here its page in Settings › Extensions with its switches; plan; link or open) and a widget's own: stop (Kav's board for a ministry stop), line (Kav's search for
// a line number), page (one of its pages, with its arguments) and call (Kav.Widget.tapped). next: in how many seconds
// its content changes by itself, for Kav to ask again then (at the earliest NEXT_MIN_S, while the phone is awake).
object Widgets {
    const val CAPABILITY = "widget.provide"
    // Plumbing, not rules.
    const val MAX_BYTES = 64 * 1024
    private const val MAX_WIDGETS = 16
    private const val MAX_ROWS = 24
    const val NEXT_MIN_S = 5 * 60
    // How long its code has to answer.
    const val ASK_MS = 3_000L
    private val ID = Regex("[a-z0-9][a-z0-9._-]{0,39}")

    enum class Size(val key: String) { SMALL("small"), WIDE("wide") }

    class Decl(val id: String, val title: Pair<String, String>, val description: Pair<String, String>, val size: Size)

    class Bad(message: String) : Exception(message)

    // -- What a manifest declares ----------------------------------------------------------------------------------------

    fun declares(m: Manifest) = m.contributions.any { it.capability == CAPABILITY }

    // Its widgets; check() refused anything that breaks these rules at install.
    fun of(m: Manifest): List<Decl> = runCatching { read(m) }.getOrDefault(emptyList())

    fun check(m: Manifest) { read(m) }

    private fun words(o: JSONObject?, what: String, required: Boolean): Pair<String, String> {
        val en = o?.opt("en") as? String
        if (en.isNullOrEmpty()) { if (required) throw TrustError("$what: needs English words") else return "" to "" }
        return en to ((o.opt("he") as? String).orEmpty())
    }

    private fun read(m: Manifest): List<Decl> {
        val wa = m.json.opt("widgets")
        if (wa != null && wa !is JSONArray) throw TrustError("bad widgets")
        val list = (wa as? JSONArray).objects().map { w ->
            val id = w.opt("id") as? String ?: throw TrustError("widget: bad id")
            if (!ID.matches(id)) throw TrustError("widget: bad id")
            val size = (w.opt("size") ?: Size.SMALL.key).let { s -> Size.entries.firstOrNull { it.key == s } } ?: throw TrustError("widget $id: size is small or wide")
            Decl(id, words(w.optJSONObject("title"), "widget title", true), words(w.optJSONObject("description"), "widget description", false), size)
        }
        if ((wa as? JSONArray)?.length() != null && list.size != wa.length()) throw TrustError("bad widgets")
        if (list.size > MAX_WIDGETS || list.map { it.id }.toSet().size != list.size) throw TrustError("bad widgets")
        if (!declares(m) && list.isNotEmpty()) throw TrustError("widgets without a widget contribution")
        if (declares(m) && list.isEmpty()) throw TrustError("a widget contribution without widgets")
        return list
    }

    // -- Its content -----------------------------------------------------------------------------------------------------

    sealed class Tap {
        // One of a page's actions: its settings, Kav's planner to a place, or a link.
        data class Page(val action: Blocks.Action) : Tap()
        data class Stop(val code: Int) : Tap()
        data class Line(val number: String) : Tap()
        data class Screen(val page: String, val args: Map<String, String>) : Tap()
        data class Call(val name: String) : Tap()
    }

    data class Row(val title: String, val subtitle: String?, val value: String?, val icon: String?, val line: String?, val tap: Tap?)

    data class Content(val title: String, val rows: List<Row>, val note: String?, val empty: String?, val tap: Tap?, val nextS: Int)

    fun read(text: String): Content {
        val o = try { StrictJson.obj(text.toByteArray(), MAX_BYTES) } catch (e: TrustError) { throw Bad(e.message ?: "bad JSON") }
        fun str(x: JSONObject, key: String): String = x.opt(key) as? String ?: throw Bad("$key must be text")
        fun optStr(x: JSONObject, key: String): String? = (x.opt(key) as? String)?.takeIf { it.isNotBlank() }
        val rows = (o.opt("rows") ?: JSONArray()).let { it as? JSONArray ?: throw Bad("rows must be a list") }.objects().take(MAX_ROWS).map { r ->
            val line = optStr(r, "line")?.also { if (!Blocks.NUMBER.matches(it)) throw Bad("line: bad number") }
            Row(str(r, "title"), optStr(r, "subtitle"), optStr(r, "value"), (r.opt("icon") as? String)?.takeIf { it in Blocks.ICONS }, line, tap(r))
        }
        val next = ((o.opt("next") as? Number)?.toInt() ?: 0).let { if (it <= 0) 0 else it.coerceIn(NEXT_MIN_S, 24 * 3600) }
        return Content(optStr(o, "title").orEmpty(), rows, optStr(o, "note"), optStr(o, "empty"), tap(o), next)
    }

    // The "action" of o: a widget's own, else a page's.
    private fun tap(o: JSONObject): Tap? {
        val a = o.opt("action") ?: return null
        if (a !is JSONObject || a.length() == 0) throw Bad("bad action")
        return when {
            a.has("stop") -> {
                val r = (a.opt("stop") as? String)?.let(Ref::parse) ?: throw Bad("stop: not a stop reference")
                if (r.namespace != Namespace.MINISTRY || r.kind != Kind.STOP) throw Bad("stop: only the ministry's stops")
                Tap.Stop(r.localId.toIntOrNull()?.takeIf { it > 0 } ?: throw Bad("stop: not a stop code"))
            }
            a.has("line") -> Tap.Line((a.opt("line") as? String)?.takeIf { Blocks.NUMBER.matches(it) } ?: throw Bad("line: bad number"))
            a.has("page") -> {
                val page = (a.opt("page") as? String)?.takeIf { ID.matches(it) } ?: throw Bad("page: bad id")
                val args = a.optJSONObject("args")?.let { x -> x.keys().asSequence().mapNotNull { k -> (x.opt(k) as? String)?.let { k to it } }.toMap() }.orEmpty()
                Tap.Screen(page, args)
            }
            a.has("call") -> Tap.Call((a.opt("call") as? String)?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: throw Bad("call: a name"))
            else -> try { Blocks.action(o) } catch (e: Blocks.Bad) { throw Bad(e.message ?: "bad action") }?.let { Tap.Page(it) } ?: throw Bad("action: unknown ${a.keys().next()}")
        }
    }

    // The tap of a part of a widget's content: its row's (row >= 0), or the whole widget's.
    fun tapOf(c: Content, row: Int): Tap? = if (row >= 0) c.rows.getOrNull(row)?.tap else c.tap

    // What Kav tells content(): the widget, how many rows fit and its size in dp.
    fun args(widget: String, rows: Int, widthDp: Int, heightDp: Int): JSONObject =
        JSONObject().put("widget", widget).put("rows", rows).put("width", widthDp).put("height", heightDp)

    // What Kav tells tapped(): the widget, the call's name and the row tapped (-1 for the widget itself).
    fun tapArgs(widget: String, call: String, row: Int): JSONObject = JSONObject().put("widget", widget).put("call", call).put("row", row)

    // -- What a placed widget shows --------------------------------------------------------------------------------------

    sealed class Show {
        // Placed, and not chosen yet (or Kav lost the choice).
        object Choose : Show()
        // Its extension, or this widget of it, is no longer there.
        object Gone : Show()
        // Kav started without extensions.
        object SafeMode : Show()
        // Its extension is switched off, blocked or turned off by Kav.
        data class Off(val name: String) : Show()
        // Nothing yet: its code hasn't answered and there's no content kept.
        data class Waiting(val name: String) : Show()
        // kept: the last content it gave, shown because it didn't answer now.
        data class Shows(val content: Content, val kept: Boolean) : Show()
    }

    // installed: the chosen extension, null when it's gone; active: Hub.active; fresh: what its code answered now (null
    // when it wasn't asked); kept: the content kept from before.
    fun decide(choice: Placed.Choice?, installed: Registry.Installed?, active: Boolean, safeMode: Boolean, fresh: Content?, kept: Content?): Show = when {
        choice == null -> Show.Choose
        installed == null || of(installed.manifest).none { it.id == choice.widget } -> Show.Gone
        safeMode -> Show.SafeMode
        !active -> Show.Off(installed.displayName)
        fresh != null -> Show.Shows(fresh, false)
        kept != null -> Show.Shows(kept, true)
        else -> Show.Waiting(installed.displayName)
    }

    // -- The widgets placed, and what each last showed --------------------------------------------------------------------

    // Which extension's widget each placed Kav widget shows, by Android's widget id, in its own file. A widget whose
    // extension was removed keeps its choice, so it says so until the rider chooses another one or removes it.
    object Placed {
        class Choice(val identity: String, val widget: String)

        @Volatile private var file: File? = null
        @Volatile var all: Map<Int, Choice> = emptyMap()
            private set

        fun open(dir: File) {
            file = File(dir, "widgets.json")
            all = runCatching {
                val o = JSONObject(file!!.readText()).getJSONObject("placed")
                o.keys().asSequence().mapNotNull { k ->
                    val c = o.optJSONObject(k) ?: return@mapNotNull null
                    k.toIntOrNull()?.let { it to Choice(c.optString("identity"), c.optString("widget")) }
                }.toMap()
            }.getOrDefault(emptyMap())
        }

        fun of(id: Int): Choice? = all[id]

        // The placed widgets showing one extension's widgets, or one widget of it.
        fun showing(identity: String, widget: String? = null): List<Int> =
            all.filter { (_, c) -> c.identity == identity && (widget == null || c.widget == widget) }.keys.sorted()

        @Synchronized fun set(dir: File, id: Int, identity: String, widget: String) {
            all = all + (id to Choice(identity, widget))
            save(dir)
        }

        // Widgets removed from the home screen. Nothing written when none was known.
        @Synchronized fun drop(dir: File, ids: Collection<Int>) {
            if (ids.none { it in all }) return
            all = all - ids.toSet()
            save(dir)
            ids.forEach { kept(dir, it).delete() }
        }

        private fun save(dir: File) {
            val f = file ?: File(dir, "widgets.json").also { file = it }
            val o = JSONObject()
            for ((id, c) in all.toSortedMap()) o.put(id.toString(), JSONObject().put("identity", c.identity).put("widget", c.widget))
            Files.replace(f, JSONObject().put("placed", o).toString().toByteArray())
        }

        internal fun forget() { all = emptyMap(); file = null }
    }

    // The content a placed widget last showed, as its code gave it, with whose it was: shown again when its code can't
    // answer (Kav was closed, it was late or crashed). Its own file each.
    private fun keptDir(dir: File) = File(dir, "widgets")
    private fun kept(dir: File, id: Int) = File(keptDir(dir), "$id.json")

    fun keep(dir: File, id: Int, choice: Placed.Choice, text: String) {
        val o = JSONObject().put("identity", choice.identity).put("widget", choice.widget).put("at", System.currentTimeMillis()).put("content", text)
        runCatching { Files.replace(kept(dir, id), o.toString().toByteArray()) }
    }

    // The kept content, only when it's the chosen widget's, and still readable.
    fun kept(dir: File, id: Int, choice: Placed.Choice): Content? = runCatching {
        val o = JSONObject(kept(dir, id).readText())
        if (o.optString("identity") != choice.identity || o.optString("widget") != choice.widget) null else read(o.getString("content"))
    }.getOrNull()

    // When the kept content said it changes by itself (next), in milliseconds since 1970; null for never.
    fun due(dir: File, id: Int, choice: Placed.Choice): Long? = runCatching {
        val o = JSONObject(kept(dir, id).readText())
        if (o.optString("identity") != choice.identity || o.optString("widget") != choice.widget) null
        else read(o.getString("content")).nextS.takeIf { it > 0 }?.let { o.getLong("at") + it * 1000L }
    }.getOrNull()

    // An uninstalled extension's content goes with it; its widgets say it's gone.
    fun dropKept(dir: File, identity: String) {
        for (id in Placed.showing(identity)) kept(dir, id).delete()
    }

    // -- Asking for a refresh --------------------------------------------------------------------------------------------

    // Set by Kav's widgets (ExtWidgets.kt): draws again the placed widgets of an extension (one widget of it, or all), and
    // asks its code for their content when ask is true.
    @Volatile var refresher: ((identity: String, widget: String?, ask: Boolean) -> Unit)? = null

    fun refresh(identity: String, widget: String? = null, ask: Boolean = true) {
        if (Placed.showing(identity, widget).isEmpty()) return
        runCatching { refresher?.invoke(identity, widget, ask) }
    }
}
