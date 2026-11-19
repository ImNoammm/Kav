package uk.noammm.kav.ext

import org.json.JSONObject
import java.io.File

// What the rider chose an extension for on Kav's own screens, in its own versioned file beside the selections
// (PLAN.md section 2, Selections store): the Home body, the one pinned tab, and the theme. Each is the rider's explicit
// choice; an extension can't make it. A choice whose extension isn't there (off, uninstalled, blocked, safe mode) stays
// a choice and shows as unavailable: Home shows Kav's own with a line saying why, the tab steps out of the bar, and Kav
// draws in its own look. None of them ever turns into another extension's.
//
// A choice keeps what Kav needs to show it at once as it starts, before any package is read: the extension's name, the
// tab's title, and the theme's colours as they resolved when it was chosen (Looks), with the package digest they came
// from. The background check after start (ui/ExtLook.kt) confirms each against the registry, or takes it back.
object Screens {
    private const val FORMAT = 1

    class Home(val identity: String, val name: String)
    class Tab(val identity: String, val page: String, val title: Pair<String, String>, val name: String)
    class Theme(val identity: String, val sha256: String, val name: String, val looks: Map<String, Looks.Colours>) {
        // The one immutable value Kav draws with.
        fun resolved() = Looks.Theme(identity, name, looks)
    }

    @Volatile var home: Home? = null
        private set
    @Volatile var tab: Tab? = null
        private set
    @Volatile var theme: Theme? = null
        private set
    @Volatile private var file: File? = null

    fun open(dir: File) {
        val f = File(dir, "screens.json")
        file = f
        if (!f.isFile) return
        runCatching {
            val o = JSONObject(f.readText())
            if (o.optInt("format") != FORMAT) return
            home = o.optJSONObject("home")?.let { Home(it.getString("identity"), it.getString("name")) }
            tab = o.optJSONObject("tab")?.let {
                val t = it.getJSONObject("title")
                Tab(it.getString("identity"), it.getString("page"), t.getString("en") to t.optString("he"), it.getString("name"))
            }
            theme = o.optJSONObject("theme")?.let { Theme(it.getString("identity"), it.getString("sha256"), it.getString("name"), Looks.read(it.getJSONObject("theme"))) }
        }
    }

    // Tests only: forgotten in memory, the file left alone.
    internal fun forget() { home = null; tab = null; theme = null; file = null }

    @Synchronized fun chooseHome(dir: File, h: Home?) { home = h; save(dir) }
    @Synchronized fun chooseTab(dir: File, t: Tab?) { tab = t; save(dir) }
    @Synchronized fun chooseTheme(dir: File, t: Theme?) { theme = t; save(dir) }

    // One extension's Home, tab and theme, no longer chosen (it was uninstalled). Nothing written when none was its.
    @Synchronized fun drop(dir: File, identity: String) {
        if (home?.identity != identity && tab?.identity != identity && theme?.identity != identity) return
        if (home?.identity == identity) home = null
        if (tab?.identity == identity) tab = null
        if (theme?.identity == identity) theme = null
        save(dir)
    }

    private fun save(dir: File) {
        val f = file ?: File(dir, "screens.json").also { file = it }
        val o = JSONObject().put("format", FORMAT)
        home?.let { o.put("home", JSONObject().put("identity", it.identity).put("name", it.name)) }
        tab?.let { o.put("tab", JSONObject().put("identity", it.identity).put("page", it.page).put("name", it.name)
            .put("title", JSONObject().put("en", it.title.first).put("he", it.title.second))) }
        theme?.let {
            o.put("theme", JSONObject().put("identity", it.identity).put("sha256", it.sha256).put("name", it.name)
                .put("theme", JSONObject().put("looks", it.resolved().json())))
        }
        Files.replace(f, o.toString().toByteArray())
    }

    // A choice made now from an installed extension, or null when it doesn't offer that.
    fun homeOf(i: Registry.Installed): Home? = runCatching { PageSpecs.of(i.manifest) }.getOrNull()?.takeIf { it.home }?.let { Home(i.identity, i.displayName) }

    fun tabOf(i: Registry.Installed, page: String): Tab? = runCatching { PageSpecs.of(i.manifest) }.getOrNull()?.tabs?.firstOrNull { it.id == page }
        ?.let { Tab(i.identity, it.id, it.title, i.displayName) }

    fun themeOf(i: Registry.Installed): Theme? = runCatching { Looks.of(i.manifest, i.identity) }.getOrNull()
        ?.let { Theme(i.identity, i.sha256, i.displayName, it.looks) }
}
