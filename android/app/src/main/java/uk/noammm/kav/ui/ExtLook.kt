package uk.noammm.kav.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import uk.noammm.kav.ext.ExtApp
import uk.noammm.kav.ext.Looks
import uk.noammm.kav.ext.PageHost
import uk.noammm.kav.ext.SafeMode
import uk.noammm.kav.ext.Screens

// What Kav's own screens read of the rider's extension choices (ext/Screens.kt): the theme in force, the pinned tab and
// the Home body. Only ExtApp.start fills them, and only when the extensions folder exists, so with nothing installed
// every screen reads null and does exactly what it did before (PLAN.md section 4: zero added work). In safe mode none
// is filled: Kav's look, Kav's Home, four tabs.
object ExtShown {
    class Tab(val identity: String, val page: String, private val title: Pair<String, String>) {
        val label get() = T(title.first, title.second.ifEmpty { title.first })
    }
    class Home(val identity: String, val name: String)

    var tab by mutableStateOf<Tab?>(null)
    var home by mutableStateOf<Home?>(null)
    // The slots the rider switched on ("<identity>|<slot>", ext/Slots.kt), in their order. Empty with no extensions.
    var slots by mutableStateOf<List<String>>(emptyList())
    // The rider has chosen any of the three, so a change in Settings (an extension off, on or gone) is checked again.
    @Volatile var anyChosen = false
        private set

    @Volatile private var host: PageHost? = null
    private val main = Handler(Looper.getMainLooper())

    // As Kav starts: the choices as they were kept, shown at once; then, off the main thread, each checked against
    // what's installed and on.
    fun start(ctx: Context) {
        if (SafeMode.active) return
        publish()
        if (Screens.tab == null && Screens.theme == null) return
        val app = ctx.applicationContext
        Thread({ runCatching { refresh(app) } }, "kav-ext-screens").start()
    }

    // The page host, made with the hub (off the main thread, the first time an extension's page is asked).
    fun host(ctx: Context): PageHost = host ?: ExtApp.hub(ctx).let { h -> PageHost(h.registry) }.also { host = it }

    // After a choice changed: shown at once.
    fun publish() {
        val t = Screens.tab?.let { Tab(it.identity, it.page, it.title) }
        val h = Screens.home?.let { Home(it.identity, it.name) }
        val look = Screens.theme
        val s = uk.noammm.kav.ext.Slots.Picks.on
        anyChosen = Screens.tab != null || Screens.home != null || look != null || s.isNotEmpty()
        onMain { tab = t; home = h; slots = s; ExtLook.set(look) }
    }

    // Each choice checked against the registry: a tab or theme whose extension is off or gone steps out, and comes back
    // when it's on again; a theme updated since it was chosen takes the new release's colours. The Home body says for
    // itself why it isn't there (ExtHome).
    fun refresh(ctx: Context) {
        if (SafeMode.active) return
        val hub = ExtApp.hub(ctx)
        fun on(identity: String) = hub.registry.get(identity)?.takeIf { hub.active(it) }
        val t = Screens.tab?.takeIf { p -> on(p.identity)?.let { Screens.tabOf(it, p.page) } != null }
            ?.let { Tab(it.identity, it.page, it.title) }
        val look = Screens.theme?.let { p ->
            val i = on(p.identity) ?: return@let null
            if (i.sha256 == p.sha256) p else Screens.themeOf(i)
        }
        onMain { tab = t; ExtLook.set(look) }
    }

    private fun onMain(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block) }
}

// The theme in force, resolved once to one value (Looks.Theme), and Kav's protected surfaces: while one is on the screen
// (Settings, the start popups and notices, the report form, navigation) every colour is Kav's own (PLAN.md section 2).
// K reads its palette here (Theme.kt, applyTheme).
object ExtLook {
    @Volatile private var theme: Looks.Theme? = null
    @Volatile private var palettes: Map<String, Palette> = emptyMap()
    private var holds by mutableIntStateOf(0)
    // A protected surface is on the screen: extension pages under it pause.
    val protectedNow get() = holds > 0

    // The theme's colours for a look (Looks.Theme.colours decides), or Kav's own.
    fun paletteFor(look: Look): Palette {
        val key = look.name.lowercase()
        return theme?.colours(key, protectedShown = holds > 0)?.let { palettes[key] } ?: palette(look)
    }

    internal fun set(t: Screens.Theme?) {
        if (t == null && theme == null) return
        val r = t?.resolved()
        palettes = r?.looks?.mapValues { (k, c) -> paletteOf(c, palette(Look.entries.first { it.name.lowercase() == k })) }.orEmpty()
        theme = r
        K.applyTheme(K.look)
    }

    // A role the theme leaves out stays Kav's own (base).
    private fun paletteOf(c: Looks.Colours, base: Palette): Palette {
        val k = base
        fun x(r: String) = c[r]?.let(::Color)
        return Palette(
            bg = x("bg") ?: k.bg, surface = x("surface") ?: k.surface, surfaceRaised = x("surfaceRaised") ?: k.surfaceRaised,
            fill = x("fill") ?: k.fill, fillStrong = x("fillStrong") ?: k.fillStrong, separator = x("separator") ?: k.separator,
            text = x("text") ?: k.text, textSecondary = x("textSecondary") ?: k.textSecondary, textTertiary = x("textTertiary") ?: k.textTertiary,
            surface2 = x("surface2") ?: k.surface2, surface3 = x("surface3") ?: k.surface3, surface4 = x("surface4") ?: k.surface4,
            borderStrong = x("borderStrong") ?: k.borderStrong, sunken = x("sunken") ?: k.sunken, routeIdle = x("routeIdle") ?: k.routeIdle,
            problem = x("problem") ?: k.problem, critical = x("critical") ?: k.critical, realtime = x("realtime") ?: k.realtime,
        )
    }

    private fun hold(d: Int) {
        val before = holds > 0
        holds = (holds + d).coerceAtLeast(0)
        if (theme != null && before != holds > 0) K.applyTheme(K.look)
    }

    // On a protected surface: Kav's own look while it's shown. Costs nothing when no theme is chosen.
    @Composable
    fun Protected(shown: Boolean) {
        if (!shown) return
        DisposableEffect(Unit) {
            hold(1)
            onDispose { hold(-1) }
        }
    }
}
