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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
        ExtLook.root = ExtApp.dir(ctx)
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
    @Volatile private var font: FontFamily? = null
    @Volatile private var skin: Skin? = null
    // The extensions folder, where a theme's font is read from (ExtShown.start).
    @Volatile internal var root: java.io.File? = null
    private var holds by mutableIntStateOf(0)
    // A protected surface is on the screen: extension pages under it pause.
    val protectedNow get() = holds > 0

    // The theme's accent for a look, its line badges and its corners, or null for the rider's own (Kav's own corners),
    // as for the colours.
    fun accentFor(look: Look): Color? = theme?.colours(look.name.lowercase(), protectedShown = holds > 0)?.get("accent")?.let(::Color)
    fun badges(): LineBadgeAppearance? = theme?.takeIf { holds == 0 }?.badges?.let { if (it == "strip") LineBadgeAppearance.STRIP else LineBadgeAppearance.KAV }
    fun corners(): Looks.Corners? = theme?.takeIf { holds == 0 }?.corners
    // Its font and its outlines (Looks.OUTLINES, "kav" being Kav's own), or null for Kav's own, likewise.
    fun font(): FontFamily? = font?.takeIf { theme != null && holds == 0 }
    fun outline(): String? = theme?.takeIf { holds == 0 }?.outline?.takeIf { it != "kav" }
    // The colors of Kav's own map in a look (MapFile.toned), or null for Kav's own map, likewise.
    fun mapTone(look: Look): Looks.MapTone? = theme?.takeIf { holds == 0 }?.map?.get(look.name.lowercase())
    // Its skin's pictures as read, or null for Kav's own parts, likewise.
    fun skin(): Skin? = skin?.takeIf { theme != null && holds == 0 }

    // The theme's colours for a look (Looks.Theme.colours decides), or Kav's own.
    fun paletteFor(look: Look): Palette {
        val key = look.name.lowercase()
        return theme?.colours(key, protectedShown = holds > 0)?.let { palettes[key] } ?: palette(look)
    }

    internal fun set(t: Screens.Theme?) {
        if (t == null && theme == null) return
        val r = t?.resolved()
        palettes = r?.looks?.mapValues { (k, c) -> paletteOf(c, palette(Look.entries.first { it.name.lowercase() == k })) }.orEmpty()
        val d = root
        font = if (t == null || d == null) null else r?.font?.let { fontOf(uk.noammm.kav.ext.Registry.packageDir(d, t.sha256), it) }
        skin = if (t == null || d == null) null else r?.skin?.let { skinOf(uk.noammm.kav.ext.Registry.packageDir(d, t.sha256), it) }
        theme = r
        K.applyTheme(K.look)
    }

    // The font file at path in a package's folder as a family Kav's type can use, or null (Kav's own type) when it isn't
    // there or Android can't read it: it is tried once here, so a broken file never reaches a screen. A variable font
    // is drawn at each weight Kav uses; one of a single weight is made bolder by Android where Kav asks for more.
    private fun fontOf(dir: java.io.File, path: String): FontFamily? = runCatching {
        val file = java.io.File(dir, path)
        if (!file.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator) || !file.isFile) return null
        android.graphics.Typeface.Builder(file).build() ?: return null
        val head = ByteArray(12 + 16 * 64).let { b ->
            file.inputStream().use { input ->
                var n = 0
                while (n < b.size) { val k = input.read(b, n, b.size - n); if (k < 0) break; n += k }
                b.copyOf(n)
            }
        }
        if (Looks.variable(head)) FontFamily(listOf(400, 500, 600, 700).map { Font(file, FontWeight(it)) })
        else FontFamily(Font(file))
    }.getOrNull()

    // The skin's pictures in a package's folder, each read once here, or null when none can be: a picture that isn't
    // there, that Android can't read, larger than PIECE_SIDE (a piece) or whose insets don't fit in it is left out. The
    // background is read at most BACKGROUND_SIDE on its longer side.
    private const val PIECE_SIDE = 1024
    private const val BACKGROUND_SIDE = 2048

    private fun skinOf(dir: java.io.File, s: Looks.Skin): Skin? {
        fun read(path: String, most: Int, shrink: Boolean): android.graphics.Bitmap? = runCatching {
            val file = java.io.File(dir, path)
            if (!file.canonicalPath.startsWith(dir.canonicalPath + java.io.File.separator) || !file.isFile) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            if (shrink) while (maxOf(bounds.outWidth, bounds.outHeight) / sample > most) sample *= 2
            else if (maxOf(bounds.outWidth, bounds.outHeight) > most) return null
            android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
        val pieces = s.pieces.mapNotNull { (k, p) ->
            val b = read(p.image, PIECE_SIDE, shrink = false) ?: return@mapNotNull null
            val (l, t, r, bottom) = p.insets
            if (l + r > b.width || t + bottom > b.height) return@mapNotNull null
            k to SkinPiece(b.asImageBitmap(), p.insets.toIntArray(), p.scale.toFloat(), p.ink?.let(::Color))
        }.toMap()
        val background = s.background?.let { read(it, BACKGROUND_SIDE, shrink = true) }?.asImageBitmap()
        return if (pieces.isEmpty() && background == null) null else Skin(pieces, background)
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
