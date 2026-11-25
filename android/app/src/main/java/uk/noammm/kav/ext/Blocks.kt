package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject

// A block tree a native extension hands Kav (KavUi.blocks), as Kav draws it: Kav's own blocks, rendered with its own
// components, in its own layout, language direction and accessibility.
//
// A tree is read as plumbing, not judged (OPEN-PLAN section 3): one ceiling, 512 KB; no counts, no lengths (Kav's
// components cut what doesn't fit), no character rules. A field Kav doesn't know is ignored, a block type it doesn't
// know is skipped and counted, an icon it doesn't know shows none. What makes a tree unreadable is only what Kav
// couldn't draw: a block without its text, a value of the wrong kind, an action with nothing to do.
//
// Actions: settings (its settings page), plan (Kav's planner with a destination filled in, planned only by the rider's
// tap), and link or open (any address or app link).
//
// Host data blocks (stop, departures, line) name Kav's own data, the ministry's, which Kav draws from the phone. An
// image is an https address or a data: address.
object Blocks {
    const val MAX_BYTES = 512 * 1024

    class Bad(message: String) : Exception(message)

    enum class Style { TITLE, BODY, CAPTION }
    enum class Level { INFO, WARNING }
    enum class Look { PRIMARY, SECONDARY }
    enum class TripSource { RECENT, JOURNEY }

    // The icons Kav has a glyph for (ui/ExtPages.kt). Another name shows no icon.
    val ICONS = setOf(
        "star", "heart", "bus", "train", "walk", "bike", "clock", "map", "pin", "info", "warning", "check", "plus",
        "edit", "delete", "search", "list", "home", "calendar", "settings",
    )

    sealed class Action {
        object Settings : Action()
        data class Plan(val lat: Double, val lon: Double, val name: String) : Action()
        data class Link(val url: String) : Action()
    }

    sealed class Block
    data class Text(val text: String, val style: Style) : Block()
    data class Row(val title: String, val subtitle: String?, val value: String?, val icon: String?, val action: Action?) : Block()
    data class Card(val title: String, val subtitle: String?, val icon: String?, val badge: String?, val action: Action?) : Block()
    data class Notice(val level: Level, val text: String) : Block()
    data class Metric(val label: String, val value: String, val unit: String?) : Block()
    data class Button(val label: String, val icon: String?, val action: Action, val look: Look) : Block()
    data class Chip(val label: String, val action: Action)
    data class Chips(val items: List<Chip>) : Block()
    data class ListOf(val items: List<Row>) : Block()
    // The rider's own trips, which Kav fills in and draws itself: the extension names the list, never sees it.
    data class Trips(val source: TripSource, val limit: Int) : Block()
    // A picture (path: an https address or a data: address), with words for TalkBack.
    data class Image(val path: String, val alt: String, val ratio: Float) : Block()
    // A ministry stop, by its code: Kav's own stop row, opening Kav's board.
    data class Stop(val code: Int) : Block()
    // A ministry stop's next scheduled departures, from the timetable on the phone (never live, never asked).
    data class Departures(val code: Int, val limit: Int) : Block()
    // A line number: Kav's own plate, opening Kav's search for that line.
    data class Line(val number: String) : Block()

    data class Section(val title: String?, val blocks: List<Block>)

    // skipped: blocks of a type this Kav doesn't know, left out.
    data class Page(val title: String, val sections: List<Section>, val skipped: Int)

    internal val NUMBER = Regex("[A-Za-z0-9\u0590-\u05FF-]{1,8}")

    fun read(bytes: ByteArray): Page {
        val o = try { StrictJson.obj(bytes, MAX_BYTES) } catch (e: TrustError) { throw Bad(e.message ?: "bad JSON") }
        return Reader().page(o)
    }

    fun read(text: String): Page = read(text.toByteArray())

    // The "action" of o as a page reads it (settings, plan, link or open), or null when it has none Kav knows: for a
    // widget's taps (Widgets), which add their own.
    internal fun action(o: JSONObject): Action? = Reader().action(o, false)

    private class Reader {
        var skipped = 0

        fun str(o: JSONObject, key: String): String = o.opt(key) as? String ?: throw Bad("$key must be text")

        fun optStr(o: JSONObject, key: String): String? = o.opt(key) as? String

        fun <E : Enum<E>> enumOf(o: JSONObject, key: String, values: Array<E>, default: E?): E {
            val v = o.opt(key) ?: return default ?: throw Bad("$key missing")
            return values.firstOrNull { it.name.lowercase() == v } ?: default ?: throw Bad("$key: unknown $v")
        }

        fun icon(o: JSONObject): String? = (o.opt("icon") as? String)?.takeIf { it in ICONS }

        fun action(o: JSONObject, required: Boolean): Action? {
            val a = o.opt("action") ?: if (required) throw Bad("action missing") else return null
            if (a !is JSONObject || a.length() == 0) throw Bad("bad action")
            return when {
                a.has("settings") -> Action.Settings
                a.has("plan") -> {
                    val p = a.opt("plan") as? JSONObject ?: throw Bad("action: bad plan")
                    val lat = (p.opt("lat") as? Number)?.toDouble() ?: throw Bad("plan: lat")
                    val lon = (p.opt("lon") as? Number)?.toDouble() ?: throw Bad("plan: lon")
                    if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) throw Bad("plan: not a place")
                    Action.Plan(lat, lon, optStr(p, "name").orEmpty())
                }
                // Any address or app link: https, geo:, tel:, another app's.
                a.has("link") || a.has("open") -> {
                    val url = (a.opt("link") ?: a.opt("open")) as? String ?: throw Bad("action: bad link")
                    if (url.isBlank() || ':' !in url) throw Bad("action: bad link")
                    Action.Link(url.trim())
                }
                else -> if (required) throw Bad("action: unknown ${a.keys().next()}") else null
            }
        }

        fun array(o: JSONObject, key: String): List<JSONObject> {
            val a = o.opt(key) as? JSONArray ?: throw Bad("$key must be a list")
            return (0 until a.length()).mapNotNull { a.opt(it) as? JSONObject }
        }

        // A ministry stop reference, il.mot/gtfs/stop/<code>: the stops Kav draws from the phone.
        fun ministryStop(o: JSONObject): Int {
            val r = (o.opt("ref") as? String)?.let(Ref::parse) ?: throw Bad("not a stop reference")
            if (r.namespace != Namespace.MINISTRY || r.kind != Kind.STOP) throw Bad("only the ministry's stops")
            return r.localId.toIntOrNull()?.takeIf { it > 0 } ?: throw Bad("not a stop code")
        }

        fun row(o: JSONObject): Row = Row(str(o, "title"), optStr(o, "subtitle"), optStr(o, "value"), icon(o), action(o, false))

        fun int(o: JSONObject, key: String, default: Int, range: IntRange): Int = ((o.opt(key) as? Number)?.toInt() ?: default).coerceIn(range)

        // Null for a type this Kav doesn't know.
        fun block(o: JSONObject): Block? {
            val type = o.opt("type") as? String ?: throw Bad("a block without a type")
            if (type !in TYPES) { skipped++; return null }
            return when (type) {
                "row" -> row(o)
                "text" -> Text(str(o, "text"), enumOf(o, "style", Style.entries.toTypedArray(), Style.BODY))
                "card" -> Card(str(o, "title"), optStr(o, "subtitle"), icon(o), optStr(o, "badge"), action(o, false))
                "notice" -> Notice(enumOf(o, "level", Level.entries.toTypedArray(), Level.INFO), str(o, "text"))
                "metric" -> Metric(str(o, "label"), str(o, "value"), optStr(o, "unit"))
                "button" -> Button(str(o, "label"), icon(o), action(o, true)!!, enumOf(o, "style", Look.entries.toTypedArray(), Look.SECONDARY))
                "chips" -> Chips(array(o, "items").map { c -> Chip(str(c, "label"), action(c, true)!!) })
                "list" -> ListOf(array(o, "items").filter { (it.opt("type") ?: "row") == "row" }.map(::row))
                "trips" -> Trips(enumOf(o, "source", TripSource.entries.toTypedArray(), null), int(o, "limit", 3, 1..50))
                "image" -> {
                    val src = o.opt("src") as? String ?: throw Bad("image: src")
                    val path = if (src.startsWith("https://") || src.startsWith("data:image/")) src else throw Bad("image: https: or data:")
                    val ratio = ((o.opt("ratio") as? Number)?.toFloat() ?: 1.5f).takeIf { it.isFinite() && it > 0f } ?: 1.5f
                    Image(path, optStr(o, "alt").orEmpty(), ratio.coerceIn(0.1f, 10f))
                }
                "stop" -> Stop(ministryStop(o))
                "departures" -> Departures(ministryStop(o), int(o, "limit", 4, 1..50))
                "line" -> {
                    val n = o.opt("number") as? String ?: throw Bad("line: number")
                    if (!NUMBER.matches(n)) throw Bad("line: bad number")
                    Line(n)
                }
                else -> throw Bad("unknown block")
            }
        }

        fun page(o: JSONObject): Page {
            val title = optStr(o, "title").orEmpty()
            val sections = array(o, "sections").map { s -> Section(optStr(s, "title"), array(s, "blocks").mapNotNull(::block)) }
            return Page(title, sections, skipped)
        }
    }

    private val TYPES = setOf("text", "row", "card", "notice", "metric", "button", "chips", "list", "trips", "image", "stop", "departures", "line")
}
