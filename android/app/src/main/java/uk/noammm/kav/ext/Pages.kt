package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// An extension's own screens, a settings page, tabs, a different Home. What a package may show is declared in its
// signed manifest:
//
//   "contributions": [{"id": "screens", "capability": "page.provide"}, {"id": "home", "capability": "home.body"}]
//   "pages": [{"id": "home", "title": {"en": "Bike log", "he": "..."}},
//             {"id": "log", "title": {"en": "Rides", "he": "..."}, "tab": true}]
//   "settings": [{"key": "pace", "type": "number", "label": {"en": "...", "he": "..."}, "min": 5, "max": 40, "default": 15,
//                 "unit": {"en": "min", "he": "דק׳"}}]
//
// A setting is text, number (with an optional step), toggle or choice, or one Kav picks with its own pickers: place
// (Kav's place search: {"name", "lat", "lon"}), stop (Kav's stops: {"code", "name", "lat", "lon"}), time ("HH:MM"),
// date ("YYYY-MM-DD") or color ("#RRGGBB"). A place or stop not chosen yet is null; a time, date or colour, "".
//
// The Home body is the page "home", when the package also declares home.body. A page marked tab may be pinned by the
// rider as a tab (Screens).
//
// Each page is a view of the extension's own code (Kav.screen, ext/Native.kt).
//
// Settings are data too: Kav draws the settings page from the declaration and keeps the values with the extension's
// data (settings.json), so an update keeps them. Its code reads them (Kav.settings) and is told of a change
// (Kav.onSettings).
object PageSpecs {
    const val PAGE = "page.provide"
    const val HOME = "home.body"
    const val HOME_PAGE = "home"
    // Plumbing, not a rule.
    private const val MAX_PAGES = 64
    private val ID = Regex("[a-z0-9][a-z0-9._-]{0,39}")

    enum class Type { TEXT, NUMBER, TOGGLE, CHOICE, PLACE, STOP, TIME, DATE, COLOR }

    // entry: it opens on its own, with no arguments, so Settings offers "Open" for it (default true). A page that only
    // makes sense opened from another page with arguments says "entry": false.
    class PageDecl(val id: String, val title: Pair<String, String>, val tab: Boolean, val entry: Boolean = true)
    class Setting(
        val key: String, val type: Type, val label: Pair<String, String>, val default: Any,
        val min: Double = 0.0, val max: Double = 0.0, val maxLength: Int = 0, val options: List<Pair<String, Pair<String, String>>> = emptyList(),
        val step: Double = 1.0,
        // A number's unit, said after its value ("3 min"); English and Hebrew, empty when it has none.
        val unit: Pair<String, String> = "" to "",
    )

    // provides: it declares page.provide, so its pages are asked.
    class Decl(val provides: Boolean, val pages: List<PageDecl>, val home: Boolean, val settings: List<Setting>) {
        val ids get() = pages.map { it.id }.toSet()
        fun page(id: String) = pages.firstOrNull { it.id == id }
        val tabs get() = pages.filter { it.tab }
    }

    // Its words in English and Hebrew; another language is ignored.
    private fun words(o: JSONObject?, what: String): Pair<String, String> {
        val en = o?.opt("en") as? String
        if (en.isNullOrEmpty()) throw TrustError("$what: needs English words")
        return en to ((o.opt("he") as? String).orEmpty())
    }

    // What the manifest declares; check() has refused anything that breaks these rules at install.
    fun of(m: Manifest): Decl = read(m)

    fun check(m: Manifest) { read(m) }

    private fun read(m: Manifest): Decl {
        val provides = m.contributions.any { it.capability == PAGE }
        val pa = m.json.opt("pages")
        if (pa != null && pa !is JSONArray) throw TrustError("bad pages")
        val pages = (pa as? JSONArray).objects().map { p ->
            val id = p.opt("id") as? String ?: throw TrustError("page: bad id")
            if (!ID.matches(id)) throw TrustError("page: bad id")
            val tab = (p.opt("tab") ?: false) as? Boolean ?: throw TrustError("page: tab is true or false")
            val entry = (p.opt("entry") ?: true) as? Boolean ?: throw TrustError("page: entry is true or false")
            if (tab && !entry) throw TrustError("a tab opens on its own, so it's an entry page")
            PageDecl(id, words(p.optJSONObject("title"), "page title"), tab, entry)
        }
        if ((pa as? JSONArray)?.length() != null && pages.size != pa.length()) throw TrustError("bad pages")
        if (pages.size > MAX_PAGES || pages.map { it.id }.toSet().size != pages.size) throw TrustError("bad pages")
        if (!provides && pages.isNotEmpty()) throw TrustError("pages without a page contribution")
        if (provides && pages.isEmpty()) throw TrustError("a page contribution without pages")
        val home = m.contributions.any { it.capability == HOME }
        if (home && (!provides || pages.none { it.id == HOME_PAGE })) throw TrustError("a Home body needs the page \"home\"")
        val sa = m.json.opt("settings")
        if (sa != null && sa !is JSONArray) throw TrustError("bad settings")
        val settings = (sa as? JSONArray).objects().map(::setting)
        if ((sa as? JSONArray)?.length() != null && settings.size != sa.length()) throw TrustError("bad settings")
        if (settings.map { it.key }.toSet().size != settings.size) throw TrustError("bad settings")
        return Decl(provides, pages, home, settings)
    }

    private fun setting(o: JSONObject): Setting {
        val key = o.opt("key") as? String ?: throw TrustError("setting: bad key")
        if (!ID.matches(key)) throw TrustError("setting: bad key")
        val type = Type.entries.firstOrNull { it.name.lowercase() == o.opt("type") } ?: throw TrustError("setting $key: unknown type")
        val label = words(o.optJSONObject("label"), "setting $key")
        fun num(k: String): Double = (o.opt(k) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: throw TrustError("setting $key: $k must be a number")
        return when (type) {
            Type.TEXT -> {
                val max = ((o.opt("maxLength") as? Number)?.toInt() ?: 10_000).coerceIn(1, 100_000)
                val d = (o.opt("default") ?: "").let { if (it !is String || it.length > max) throw TrustError("setting $key: bad default") else it }
                Setting(key, type, label, d, maxLength = max)
            }
            Type.NUMBER -> {
                val min = num("min"); val max = num("max"); val d = num("default")
                if (min >= max || d < min || d > max) throw TrustError("setting $key: default must be within min and max")
                val step = if (o.has("step")) num("step") else 1.0
                if (step <= 0 || step > max - min) throw TrustError("setting $key: step must be above 0 and within the range")
                val unit = o.optJSONObject("unit")?.let { words(it, "setting $key unit") } ?: ("" to "")
                if (unit.first.length > 16 || unit.second.length > 16) throw TrustError("setting $key: a unit is a word or two")
                Setting(key, type, label, d, min, max, step = step, unit = unit)
            }
            // Chosen with Kav's own pickers: nothing chosen until the rider picks.
            Type.PLACE, Type.STOP -> {
                if (o.has("default") && !o.isNull("default")) throw TrustError("setting $key: a $type has no default")
                Setting(key, type, label, JSONObject.NULL)
            }
            Type.TIME, Type.DATE, Type.COLOR -> {
                val d = (o.opt("default") ?: "") as? String ?: throw TrustError("setting $key: bad default")
                if (d.isNotEmpty() && ExtSettings.valid(Setting(key, type, label, ""), d) == null) throw TrustError("setting $key: bad default")
                Setting(key, type, label, d)
            }
            Type.TOGGLE -> Setting(key, type, label, (o.opt("default") ?: false) as? Boolean ?: throw TrustError("setting $key: bad default"))
            Type.CHOICE -> {
                val opts = (o.opt("options") as? JSONArray)?.objects()?.map { x ->
                    val v = x.opt("value") as? String ?: throw TrustError("setting $key: bad option")
                    if (!ID.matches(v)) throw TrustError("setting $key: bad option")
                    v to words(x.optJSONObject("label"), "setting $key option")
                } ?: throw TrustError("setting $key: options missing")
                if (opts.isEmpty() || opts.map { it.first }.toSet().size != opts.size) throw TrustError("setting $key: different options")
                val d = o.opt("default") ?: opts.first().first
                if (d !is String || opts.none { it.first == d }) throw TrustError("setting $key: default isn't an option")
                Setting(key, type, label, d, options = opts)
            }
        }
    }
}

// The values the rider set on an extension's settings page, in its data folder: the declared default for any value
// that's missing or no longer valid (a newer release changed the declaration).
object ExtSettings {
    private const val FILE = "settings.json"

    fun read(stateDir: File?, decl: PageSpecs.Decl): JSONObject {
        val saved = stateDir?.let { File(it, FILE) }?.takeIf { it.isFile && it.length() <= 4 shl 20 }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() } ?: JSONObject()
        val out = JSONObject()
        for (s in decl.settings) out.put(s.key, valid(s, saved.opt(s.key)) ?: s.default)
        return out
    }

    private val TIME = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
    private val DATE = Regex("[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])")
    private val COLOR = Regex("#[0-9A-Fa-f]{6}")
    private val CODE = Regex("[0-9]{1,8}")

    // The value as it's kept, or null when it isn't one this setting takes. A place or stop can be cleared (null), a time,
    // date or colour emptied ("").
    fun valid(s: PageSpecs.Setting, v: Any?): Any? = when (s.type) {
        PageSpecs.Type.TEXT -> (v as? String)?.takeIf { it.length <= s.maxLength }
        PageSpecs.Type.NUMBER -> (v as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= s.min && it <= s.max }
        PageSpecs.Type.TOGGLE -> v as? Boolean
        PageSpecs.Type.CHOICE -> (v as? String)?.takeIf { x -> s.options.any { it.first == x } }
        PageSpecs.Type.PLACE, PageSpecs.Type.STOP -> if (v == null || v == JSONObject.NULL) JSONObject.NULL else (v as? JSONObject)?.let { o ->
            val lat = (o.opt("lat") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return@let null
            val lon = (o.opt("lon") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return@let null
            val name = (o.opt("name") as? String)?.take(200) ?: ""
            val out = JSONObject().put("name", name).put("lat", lat).put("lon", lon)
            if (s.type == PageSpecs.Type.STOP) (o.opt("code") as? String)?.takeIf { CODE.matches(it) }?.let { out.put("code", it) } ?: return@let null
            out
        }
        PageSpecs.Type.TIME -> (v as? String)?.takeIf { it.isEmpty() || TIME.matches(it) }
        PageSpecs.Type.DATE -> (v as? String)?.takeIf { it.isEmpty() || DATE.matches(it) }
        PageSpecs.Type.COLOR -> (v as? String)?.takeIf { it.isEmpty() || COLOR.matches(it) }?.uppercase()
    }

    // Saves one value. False when it isn't valid; nothing is written then.
    fun write(stateDir: File, decl: PageSpecs.Decl, key: String, value: Any): Boolean {
        val s = decl.settings.firstOrNull { it.key == key } ?: return false
        val v = valid(s, value) ?: return false
        val all = read(stateDir, decl).put(key, v)
        Files.replace(File(stateDir, FILE), all.toString().toByteArray())
        return true
    }
}

internal object Files {
    fun replace(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) throw java.io.IOException("couldn't save ${f.name}")
    }
}

// An extension's declarations and its rider's settings, as Kav's screens read and change them.
class PageHost(
    private val registry: Registry,
) {
    fun decl(identity: String): PageSpecs.Decl? = registry.get(identity)?.let { runCatching { PageSpecs.of(it.manifest) }.getOrNull() }

    fun name(identity: String): String? = registry.get(identity)?.displayName

    // The rider's settings for it, every declared one with its value; and one changed. Kept with its data (Registry).
    fun settings(identity: String): JSONObject? = decl(identity)?.let { ExtSettings.read(registry.stateDir(identity), it) }

    fun setSetting(identity: String, key: String, value: Any): Boolean {
        val d = decl(identity) ?: return false
        return ExtSettings.write(registry.stateDir(identity) ?: return false, d, key, value)
    }
}

// What Home shows when the rider chose an extension's Home body (PLAN.md 3.3). Kav's own search bar and Settings stay
// above every body; the resume card of a trip under way, the "by" label and the way back to Kav's Home stay above the
// extension's body, which can never cover or reorder them. Kav's own Home (with its own resume card) shows whenever the
// extension's isn't there: not chosen, in safe mode, off or missing, failed, or not ready within the budget.
object HomeBody {
    // PLAN.md section 4: Home shows the extension's body within this, or Kav's own body first.
    const val BUDGET_MS = 100L

    enum class Part { RESUME, LABEL, EXTENSION, WAITING, KAV, STRIP_UNAVAILABLE, STRIP_FAILED, STRIP_KAV_CHOSEN }

    sealed class State {
        object Waiting : State()
        object Late : State()
        object Ready : State()
        class Failed(val why: String) : State()
        class Unavailable(val why: String) : State()
    }

    // chosen: the rider picked an extension's Home. kavNow: they tapped Kav's Home for now.
    fun parts(chosen: Boolean, safeMode: Boolean, state: State, journey: Boolean, kavNow: Boolean): List<Part> = when {
        !chosen || safeMode -> listOf(Part.KAV)
        kavNow -> listOf(Part.STRIP_KAV_CHOSEN, Part.KAV)
        state is State.Unavailable -> listOf(Part.STRIP_UNAVAILABLE, Part.KAV)
        state is State.Failed -> listOf(Part.STRIP_FAILED, Part.KAV)
        state is State.Late -> listOf(Part.KAV)
        state is State.Waiting -> listOfNotNull(Part.RESUME.takeIf { journey }, Part.LABEL, Part.WAITING)
        else -> listOfNotNull(Part.RESUME.takeIf { journey }, Part.LABEL, Part.EXTENSION)
    }

    // A late body still replaces Kav's Home when it comes, unless the rider has started using Kav's.
    fun arrived(state: State, touched: Boolean): State = if (state is State.Late && touched) state else State.Ready
}
