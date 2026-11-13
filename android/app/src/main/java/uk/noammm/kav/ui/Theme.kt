package uk.noammm.kav.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.noammm.kav.data.MapFile

enum class Look { DARK, OLED, LIGHT }

object K {
    var look by mutableStateOf(Look.DARK)
    val light get() = look == Look.LIGHT
    var liquid by mutableStateOf(false)

    // Colour roles, one meaning each. Cards stand off the page by their own shade and a hairline, never a white ring.
    var bg by mutableStateOf(Color(0xFF101012))
    // Cards and grouped lists.
    var surface by mutableStateOf(Color(0xFF1C1C1F))
    // Sheets, floating panels and menus.
    var surfaceRaised by mutableStateOf(Color(0xFF26262A))
    // A control's fill: chips, secondary buttons, fields; strong while pressed or emphasised.
    var fill by mutableStateOf(Color(0x14FFFFFF))
    var fillStrong by mutableStateOf(Color(0x24FFFFFF))
    // Hairlines between rows and around cards: see-through on the dark looks, solid on the light one (black at 9 % on
    // the page), where outlines are compared by their solid shade.
    var separator by mutableStateOf(Color(0x1FFFFFFF))
    var text by mutableStateOf(Color(0xFFF5F5F7))
    var textSecondary by mutableStateOf(Color(0xFFC7C7CE))
    // Placeholders, captions and anything disabled: never essential text.
    var textTertiary by mutableStateOf(Color(0xFF9C9CA5))
    var surface2 by mutableStateOf(Color(0xFF2B2B30))
    var surface3 by mutableStateOf(Color(0xFF343439))
    var surface4 by mutableStateOf(Color(0xFF44444A))
    var borderStrong by mutableStateOf(Color(0xFF74747D))
    var sunken by mutableStateOf(Color(0xFF19191C))

    // Other names for the roles above, as some screens call them.
    val surface1 get() = surface
    val border get() = separator
    val dim get() = textTertiary
    val muted get() = textSecondary
    val plate get() = fill
    val plateStrong get() = fillStrong
    val badgePlate get() = surface3
    val co2Pill get() = surface2

    val rCard = 22.dp
    // A sheet's top corners, and a full-screen dialog's.
    val rSheet = 28.dp
    // Photo and glyph tiles in rows, and small blocks inside a card.
    val rTile = 12.dp
    // Line plates.
    val rBadge = 8.dp
    // Every control: buttons, chips, segmented controls, fields, icon buttons.
    val capsule = RoundedCornerShape(percent = 50)

    // Interaction and selection only: filled primary buttons, chosen chips, switches, links.
    var accent by mutableStateOf(DefaultAccent)
    // Accents are always pale, so text on them stays dark in every look.
    val onAccent get() = if (light) text else bg
    // The accent as text and links. On white a pale accent is deepened until it reads at 4.5:1.
    val accentInk: Color get() = if (light) readable(accent, surface) else accent
    val route get() = accent
    val live get() = accent
    var routeIdle by mutableStateOf(Color(0xFF7E7E87))
    var problem by mutableStateOf(Color(0xFFE7C17A))
    var critical by mutableStateOf(Color(0xFFEE929A))
    // Green for a time or position measured live, and nothing else.
    var realtime by mutableStateOf(Color(0xFF04C876))
    val scheduled get() = textSecondary

    val gap1 = 4.dp; val gap2 = 8.dp; val gap3 = 12.dp
    val gap4 = 16.dp; val gap5 = 20.dp; val gap6 = 24.dp; val gap8 = 32.dp

    val type = KType
    // Bus line badges in Kav's look or the strip, as chosen in Settings.
    var lineBadgeAppearance by mutableStateOf(LineBadgeAppearance.KAV)

    // A change of look for KavTheme to fade to (ThemeFade): Settings' look, glass and colour presets are made through it.
    var restyling by mutableStateOf<Restyle?>(null)
    fun restyle(change: () -> Unit) { restyling = Restyle(change) }

    // Every colour has to be set for every look, or a switch leaves strays from the last one (Palette has them all).
    fun applyTheme(chosen: Look) {
        look = chosen
        val p = palette(chosen)
        bg = p.bg; surface = p.surface; surfaceRaised = p.surfaceRaised
        fill = p.fill; fillStrong = p.fillStrong; separator = p.separator
        text = p.text; textSecondary = p.textSecondary; textTertiary = p.textTertiary
        surface2 = p.surface2; surface3 = p.surface3; surface4 = p.surface4
        borderStrong = p.borderStrong; sunken = p.sunken; routeIdle = p.routeIdle
        problem = p.problem; critical = p.critical; realtime = p.realtime
    }
}

// A look's colours: K's, as applyTheme sets them, and a shared trip's card's, which is drawn in its link's look whatever
// the app's is (LocalLook).
class Palette(
    val bg: Color, val surface: Color, val surfaceRaised: Color,
    val fill: Color, val fillStrong: Color, val separator: Color,
    val text: Color, val textSecondary: Color, val textTertiary: Color,
    val surface2: Color, val surface3: Color, val surface4: Color,
    val borderStrong: Color, val sunken: Color, val routeIdle: Color,
    val problem: Color, val critical: Color, val realtime: Color,
)

fun palette(look: Look): Palette = when (look) {
    Look.DARK -> Palette(
        bg = Color(0xFF101012), surface = Color(0xFF1C1C1F), surfaceRaised = Color(0xFF26262A),
        fill = Color(0x14FFFFFF), fillStrong = Color(0x24FFFFFF), separator = Color(0x1FFFFFFF),
        text = Color(0xFFF5F5F7), textSecondary = Color(0xFFC7C7CE), textTertiary = Color(0xFF9C9CA5),
        surface2 = Color(0xFF2B2B30), surface3 = Color(0xFF343439), surface4 = Color(0xFF44444A),
        borderStrong = Color(0xFF74747D), sunken = Color(0xFF19191C), routeIdle = Color(0xFF7E7E87),
        problem = Color(0xFFE7C17A), critical = Color(0xFFEE929A), realtime = Color(0xFF04C876),
    )
    Look.OLED -> Palette(
        bg = Color(0xFF000000), surface = Color(0xFF0E0E10), surfaceRaised = Color(0xFF17171A),
        fill = Color(0x1AFFFFFF), fillStrong = Color(0x29FFFFFF), separator = Color(0x29FFFFFF),
        text = Color(0xFFF5F5F7), textSecondary = Color(0xFFC7C7CE), textTertiary = Color(0xFF9C9CA5),
        surface2 = Color(0xFF1C1C1F), surface3 = Color(0xFF26262A), surface4 = Color(0xFF38383E),
        borderStrong = Color(0xFF6C6C75), sunken = Color(0xFF000000), routeIdle = Color(0xFF7E7E87),
        problem = Color(0xFFE7C17A), critical = Color(0xFFEE929A), realtime = Color(0xFF04C876),
    )
    Look.LIGHT -> Palette(
        bg = Color(0xFFF2F2EF), surface = Color(0xFFFFFFFF), surfaceRaised = Color(0xFFFFFFFF),
        fill = Color(0x0D000000), fillStrong = Color(0x17000000), separator = Color(0xFFDCDCD9),
        text = Color(0xFF16161A), textSecondary = Color(0xFF494951), textTertiary = Color(0xFF6F6F78),
        surface2 = Color(0xFFE6E6E2), surface3 = Color(0xFFD9D9D5), surface4 = Color(0xFFC3C3BF),
        borderStrong = Color(0xFF97979F), sunken = Color(0xFFEAEAE6), routeIdle = Color(0xFFA6A6AE),
        problem = Color(0xFF9A6A00), critical = Color(0xFFB3424E), realtime = Color(0xFF00804C),
    )
}

// The look a part of the screen is drawn in where it isn't the app's: a shared trip's card, in its link's.
val LocalLook = staticCompositionLocalOf<Palette?> { null }

// K's colours for what a shared trip's card shows as the rest of the app does (a route's strip): the card's own look
// inside it, K's everywhere else.
object Ink {
    val bg: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.bg ?: K.bg
    val fill: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.fill ?: K.fill
    val text: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.text ?: K.text
    val textSecondary: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.textSecondary ?: K.textSecondary
    val textTertiary: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.textTertiary ?: K.textTertiary
    val surface4: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.surface4 ?: K.surface4
    val borderStrong: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.borderStrong ?: K.borderStrong
    val problem: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.problem ?: K.problem
    val critical: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.critical ?: K.critical
    val muted: Color @Composable @ReadOnlyComposable get() = textSecondary
    val badgePlate: Color @Composable @ReadOnlyComposable get() = LocalLook.current?.surface3 ?: K.badgePlate
}

// What Kav draws on a map (routes, stops, markers, vehicles and the rider) in colours made for the map under it, while the
// rest of the screen keeps the app's look. Kav's own map goes with the app's look, so it keeps the app's colours;
// OpenFreeMap's light maps (Liberty, Bright) take the light look's, and its dark ones and the satellite photos a dark
// look's: the app's own, or the dark look's when the app is light. Where a map's own colours would still swallow some of
// them, those are deepened or lightened until each reads at 3:1 there (inksFor). MapInkFollower keeps it on the map chosen.
object MapInk {
    var bg by mutableStateOf(Color(0xFF101012))
    var text by mutableStateOf(Color(0xFFF5F5F7))
    var textSecondary by mutableStateOf(Color(0xFFC7C7CE))
    var routeIdle by mutableStateOf(Color(0xFF7E7E87))
    var problem by mutableStateOf(Color(0xFFE7C17A))
    var critical by mutableStateOf(Color(0xFFEE929A))
    var realtime by mutableStateOf(Color(0xFF04C876))
    val muted get() = textSecondary
    // Whether the map under it is a light one.
    val light get() = bg.luminance() > .5f
    // The app's accent, deepened on a light map until it reads there, as on the light look's pages.
    val accent: Color get() = if (light) readable(K.accent, bg) else K.accent
    val route get() = accent
    // The colours its images are drawn in (markers, vehicles and the rider), which change halfway through a change of map
    // rather than at each step of it, where each step would be another image.
    var icons by mutableStateOf(Inks(bg, text, textSecondary, routeIdle, problem, critical, realtime))
    val iconsLight get() = icons.bg.luminance() > .5f
    val iconAccent: Color get() = if (iconsLight) readable(K.accent, icons.bg) else K.accent

    fun lookFor(type: MapFile.Type, app: Look): Look = when (type) {
        MapFile.Type.STANDARD -> app
        MapFile.Type.LIBERTY, MapFile.Type.BRIGHT, MapFile.Type.GOVMAP -> Look.LIGHT
        else -> if (app == Look.LIGHT) Look.DARK else app
    }

    internal fun inksFor(type: MapFile.Type, app: Look): Inks {
        val base = inks(palette(lookFor(type, app)))
        return when (type) {
            // Their water and woods are mid tones: the driven road and the vehicles' colours deepened to read there too.
            MapFile.Type.LIBERTY, MapFile.Type.BRIGHT, MapFile.Type.GOVMAP -> base.copy(
                routeIdle = Color(0xFF65656A), problem = Color(0xFF895E00), realtime = Color(0xFF007646),
                critical = Color(0xFFAF414C),
            )
            // Its blue grey ground swallows the dark looks' grey: a lighter one for the driven road.
            MapFile.Type.FIORD -> base.copy(routeIdle = Color(0xFFA3A3AA))
            else -> base
        }
    }

    data class Inks(
        val bg: Color, val text: Color, val textSecondary: Color, val routeIdle: Color, val problem: Color,
        val critical: Color, val realtime: Color,
    )
    internal fun inks(p: Palette) = Inks(p.bg, p.text, p.textSecondary, p.routeIdle, p.problem, p.critical, p.realtime)
    internal fun now() = Inks(bg, text, textSecondary, routeIdle, problem, critical, realtime)

    // Partway from a's colours to b's, at t.
    internal fun set(a: Inks, b: Inks, t: Float) {
        bg = lerp(a.bg, b.bg, t); text = lerp(a.text, b.text, t); textSecondary = lerp(a.textSecondary, b.textSecondary, t)
        routeIdle = lerp(a.routeIdle, b.routeIdle, t); problem = lerp(a.problem, b.problem, t)
        critical = lerp(a.critical, b.critical, t); realtime = lerp(a.realtime, b.realtime, t)
    }
}

// A new map's colours come over 300 ms, as the map itself fades to the new one; a new look's come at once, under the
// screen's own fade (ThemeFade).
@Composable
private fun MapInkFollower() {
    val app = K.look
    val type = MapFile.type
    var shown by remember { mutableStateOf<Look?>(null) }
    LaunchedEffect(app, type) {
        val to = MapInk.inksFor(type, app)
        val sameLook = shown == app
        shown = app
        if (!sameLook) { MapInk.set(to, to, 1f); MapInk.icons = to; return@LaunchedEffect }
        val from = MapInk.now()
        Animatable(0f).animateTo(1f, tween(300)) {
            MapInk.set(from, to, value)
            if (value >= .5f && MapInk.icons !== to) MapInk.icons = to
        }
    }
}

// A colour made dark enough to read at 4.5:1 on a background, for text in a colour chosen to look pale.
fun readable(c: Color, on: Color): Color {
    var out = c
    var t = 0f
    while (contrast(out, on) < 4.5f && t < .9f) {
        t += .05f
        out = lerp(c, Color.Black, t)
    }
    return out
}

// The type roles, seven in all. Weight carries hierarchy before size; nothing in the interface is under 12 sp.
object KType {
    // Top-level titles, and a trip's duration.
    val largeTitle get() = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, color = K.text)
    // Pushed screens' and sheets' titles.
    val title get() = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp, color = K.text)
    // A card's title, the key line of a card.
    val headline get() = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = K.text)
    // Row titles, paragraphs, field text.
    val body get() = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = K.text)
    // Button labels and values that stand out.
    val bodyStrong get() = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = K.text)
    // A row's second line, chips, notes, footers.
    val subhead get() = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = K.textSecondary)
    // Section headers, tab labels, captions under icons.
    val caption get() = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = .2.sp, color = K.textSecondary)
}

// Figures of one width, for times and counts that change in place without the line jumping.
val TextStyle.tabular get() = copy(fontFeatureSettings = "tnum")

object Shown {
    var co2 by mutableStateOf(false)
    var accessibility by mutableStateOf(true)
    var fareProfile by mutableStateOf(uk.noammm.kav.data.FareProfile.REGULAR)
    var twelveHour by mutableStateOf(false)
    // Paying for rides: the home screen's card and a ride's. Off, Kav shows nothing of it.
    var payments by mutableStateOf(true)
    // A popup and a dot on Settings when a new version is out. Off for riders who update another way, as with Obtainium.
    var updatePrompts by mutableStateOf(true)
}

// Times in tabular figures of the system font.
val Mono get() = TextStyle(fontSize = 12.sp, color = K.muted).tabular

private fun scheme() = (if (K.light) lightColorScheme() else darkColorScheme()).copy(
    primary = K.text, onPrimary = K.bg,
    primaryContainer = K.surface3, onPrimaryContainer = K.text,
    inversePrimary = K.surface4,
    secondary = K.muted, onSecondary = K.bg,
    secondaryContainer = K.surface2, onSecondaryContainer = K.text,
    tertiary = K.muted, onTertiary = K.bg,
    tertiaryContainer = K.surface2, onTertiaryContainer = K.text,
    background = K.bg, onBackground = K.text,
    surface = K.bg, onSurface = K.text,
    surfaceVariant = K.surface3, onSurfaceVariant = K.muted,
    surfaceTint = K.surface3,
    inverseSurface = K.muted, inverseOnSurface = K.bg,
    surfaceContainerLowest = K.bg, surfaceContainerLow = K.sunken,
    surfaceContainer = K.surface, surfaceContainerHigh = K.surfaceRaised,
    surfaceContainerHighest = K.surface3,
    outline = K.separator, outlineVariant = K.separator,
    error = K.critical, onError = K.bg,
    errorContainer = K.surface3, onErrorContainer = K.critical,
)

class Restyle(val change: () -> Unit) { var done = false }

// A new look fades in over the last: the screen as it stands is drawn once into a picture, the change is made under it,
// and the picture fades out. Nothing more is drawn on any other frame.
@Composable
private fun ThemeFade(content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    var recording by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }
    val restyle = K.restyling
    LaunchedEffect(restyle) {
        if (restyle == null || restyle.done) return@LaunchedEffect
        restyle.done = true
        var made = false
        try {
            // The next frame draws the screen into the layer as it goes.
            recording = true
            withFrameNanos {}
            withFrameNanos {}
            held = runCatching { layer.toImageBitmap() }.getOrNull()
            recording = false
            restyle.change()
            made = true
            if (held != null) {
                fade.snapTo(1f)
                fade.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
            }
        } finally {
            recording = false
            // Called off by another change: this one is still made, under the next one's picture.
            if (!made) restyle.change()
            held = null
        }
    }
    Box(
        Modifier.fillMaxSize().drawWithContent {
            if (recording) {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            } else drawContent()
            held?.let { drawImage(it, alpha = fade.value) }
        },
    ) { content() }
}

@Composable
fun KavTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme(),
        typography = Typography(
            titleLarge = K.type.largeTitle,
            titleMedium = K.type.title,
            titleSmall = K.type.headline,
            labelLarge = K.type.bodyStrong,
            labelMedium = K.type.caption,
            // For text that sets no style of its own.
            bodyLarge = TextStyle(fontSize = 14.sp, color = K.text),
            bodyMedium = TextStyle(fontSize = 13.sp, color = K.text),
            bodySmall = TextStyle(fontSize = 12.sp, color = K.dim),
        ),
        content = { MapInkFollower(); ThemeFade(content) },
    )
}
