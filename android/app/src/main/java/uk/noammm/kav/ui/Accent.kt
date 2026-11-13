package uk.noammm.kav.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import uk.noammm.kav.Tab
import uk.noammm.kav.TabGlyph
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.text.font.FontWeight

val DefaultAccent = Color(0xFF9ABEFF)

// The slider's strength for a colour picked from white, about the presets'.
private const val SLIDER_SAT = 0.42f

class AccentPreset(private val nameEn: String, private val nameHe: String, val hue: Float, val sat: Float) {
    val label: String get() = T(nameEn, nameHe)
    val color: Color get() = Color.hsv(hue, sat, 1f)
}

val AccentPresets = listOf(
    AccentPreset("Blue", "כחול", 219f, .40f),
    AccentPreset("Green", "ירוק", 140f, .38f),
    AccentPreset("Teal", "טורקיז", 178f, .42f),
    AccentPreset("Violet", "סגול", 262f, .34f),
    AccentPreset("Amber", "ענבר", 44f, .48f),
    AccentPreset("Pink", "ורוד", 338f, .36f),
)

private fun hsvOf(c: Color): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }

// Kav's colour: a slider along every hue, at the colour's own strength (the presets' when it's white), and the presets.
@Composable
fun AccentPicker(modifier: Modifier = Modifier, onChange: (Color) -> Unit) {
    val current = K.accent
    val hsv = remember(current) { hsvOf(current) }
    val strength = if (hsv[1] < .05f) SLIDER_SAT else hsv[1]
    val track = remember(strength) { (0..12).map { Color.hsv((it * 30 % 360).toFloat(), strength, 1f) } }
    // The dot's place on the slider: it follows a finger at once, and glides there when a preset is tapped.
    val target = hsv[0] / 360f
    val dot = remember { Animatable(target) }
    var glide by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (glide) { glide = false; dot.animateTo(target, tween(360, easing = FastOutSlowInEasing)) } else dot.snapTo(target)
    }

    // A tap recolours the app with the theme's fade and the dot glides there, as a preset does; a drag follows at once.
    fun pick(x: Float, width: Int, tap: Boolean = false) {
        val chosen = Color.hsv((x / width.coerceAtLeast(1)).coerceIn(0f, 1f) * 359.9f, strength, 1f)
        if (tap) { glide = true; K.restyle { K.accent = chosen } } else K.accent = chosen
        onChange(chosen)
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // A tap or a drag along it picks; a drag up or down scrolls the page past it.
        Canvas(
            Modifier.fillMaxWidth().height(36.dp)
                .semantics { contentDescription = T("Colour", "צבע") }
                .pointerInput(strength) { detectTapGestures { pick(it.x, size.width, tap = true) } }
                .pointerInput(strength) {
                    detectHorizontalDragGestures(onDragStart = { pick(it.x, size.width) }) { change, _ ->
                        change.consume(); pick(change.position.x, size.width)
                    }
                },
        ) {
            val bar = 14.dp.toPx()
            drawRoundRect(
                Brush.horizontalGradient(track), topLeft = Offset(0f, (size.height - bar) / 2),
                size = androidx.compose.ui.geometry.Size(size.width, bar),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2),
            )
            // On its way to a preset, the dot takes each colour it passes.
            val place = dot.value.coerceIn(0f, 1f)
            val at = Offset(place * size.width, size.height / 2)
            drawCircle(K.bg.copy(alpha = .55f), 15.dp.toPx(), at)
            drawCircle(if (dot.isRunning) Color.hsv(place * 359.9f, hsv[1], 1f) else current, 12.dp.toPx(), at)
            drawCircle(Color.White, 12.dp.toPx(), at, style = Stroke(2.5.dp.toPx()))
        }
        Spacer(Modifier.height(K.gap4))
        Row(horizontalArrangement = Arrangement.spacedBy(K.gap3), verticalAlignment = Alignment.CenterVertically) {
            AccentPresets.forEach { p ->
                val selected = hsvOf(p.color).let { it[1] < .02f && hsv[1] < .02f || (kotlin.math.abs(it[0] - hsv[0]) < 2f && kotlin.math.abs(it[1] - hsv[1]) < .02f) }
                Box(
                    Modifier.size(30.dp).clip(K.capsule).background(p.color)
                        .border(2.dp, if (selected) K.text else K.bg.copy(alpha = .6f), K.capsule)
                        .semantics { contentDescription = p.label }
                        .clickable(role = Role.Button) {
                            if (kotlin.math.abs(p.hue / 360f - target) > .001f) glide = true
                            K.restyle { K.accent = p.color }; onChange(p.color)
                        },
                )
            }
        }
    }
}

@Composable
fun AccentPreview(modifier: Modifier = Modifier) {
    // The page under the floating parts is what their glass is made of: map under the search and the trip card, so
    // liquid glass shows the streets through both and solid hides them, as on Home. It's drawn once; the glass draws
    // again only with it. The map fills the card: ending under the floating parts, it left the rest a darker band.
    val liquid = rememberLiquidBackdrop()
    val density = LocalDensity.current
    var floating by remember { mutableStateOf(150.dp) }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.bg).border(0.5.dp, K.separator, RoundedCornerShape(K.rCard))) {
        Box(Modifier.fillMaxWidth().glassBackdrop(liquid)) {
            PreviewMap(Modifier.matchParentSize())
            Column(
                Modifier.fillMaxWidth().padding(start = K.gap3, top = floating, end = K.gap3, bottom = K.gap3),
                verticalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                    Text(
                        T("Bus", "אוטובוס"), fontSize = 12.sp, color = K.onAccent, fontWeight = FontWeight.Medium,
                        modifier = Modifier.clip(K.capsule).background(K.accent).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text(
                        T("Train", "רכבת"), fontSize = 12.sp, color = K.textSecondary,
                        modifier = Modifier.clip(K.capsule).background(K.fill).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(30.dp, 18.dp).clip(K.capsule).background(K.accent)) {
                        Box(Modifier.align(Alignment.CenterEnd).padding(2.dp).size(14.dp).clip(K.capsule).background(Color.White))
                    }
                }
                Box(
                    Modifier.fillMaxWidth().height(34.dp).clip(K.capsule).background(K.accent),
                    contentAlignment = Alignment.Center,
                ) { Text(T("Start", "התחלה"), fontSize = 12.sp, color = K.onAccent, fontWeight = FontWeight.Medium) }
            }
        }
        // The floating search and settings over the map, and the trip card under them, as on Home.
        CompositionLocalProvider(LocalLiquidBackdrop provides liquid) {
            Column(
                Modifier.fillMaxWidth().padding(K.gap3).onSizeChanged { floating = with(density) { it.height.toDp() } + K.gap3 * 2 },
                verticalArrangement = Arrangement.spacedBy(K.gap2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                    Row(
                        Modifier.weight(1f).height(32.dp).glassSurface(16.dp).padding(horizontal = K.gap3),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = K.textSecondary, modifier = Modifier.size(14.dp))
                        Text(T("Where to?", "לאן?"), fontSize = 12.sp, color = K.textSecondary)
                    }
                    Box(Modifier.size(32.dp).glassSurface(16.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Settings, contentDescription = null, tint = K.text, modifier = Modifier.size(16.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().glassSurface(K.rTile).padding(K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(T("Current trip", "הנסיעה הנוכחית"), fontSize = 12.sp, color = K.textSecondary, modifier = Modifier.weight(1f))
                        Text(T("Resume", "המשך"), fontSize = 12.sp, color = K.accentInk, fontWeight = FontWeight.Medium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                        LinePlateBadge("69", Mode.BUS, operatorPlate(Operators.ofGtfs(5)))
                        Text(T("to Tel Aviv", "לתל אביב"), fontSize = 12.sp, color = K.text, modifier = Modifier.weight(1f))
                        LiveGlyph(K.realtime, 10.dp)
                        Text(T("3 min", "3 דק׳"), fontSize = 12.sp, color = K.realtime, fontWeight = FontWeight.Medium)
                    }
                }
                // The tab bar, the glass seen most: Home on the accent, as TabBar shows the chosen tab.
                Row(
                    Modifier.fillMaxWidth().height(44.dp).glassSurface(22.dp).padding(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Tab.entries.forEachIndexed { i, t ->
                        val ink = if (i == 0) K.onAccent else K.textSecondary
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clip(K.capsule).then(if (i == 0) Modifier.background(K.accent) else Modifier),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            TabGlyph(t, ink, 14.dp)
                            Text(t.label, fontSize = 9.sp, color = ink, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// A few streets and two lines on the page's colour, the kind of map the search floats over on Home.
@Composable
private fun PreviewMap(modifier: Modifier) {
    val street = K.fillStrong
    val line = K.accent
    val other = K.realtime
    Canvas(modifier.background(K.surface1)) {
        val w = size.width; val h = size.height
        fun road(x0: Float, y0: Float, x1: Float, y1: Float, width: Float) =
            drawLine(street, Offset(w * x0, h * y0), Offset(w * x1, h * y1), width, androidx.compose.ui.graphics.StrokeCap.Round)
        val thin = 3.dp.toPx(); val wide = 6.dp.toPx()
        road(-.05f, .3f, 1.05f, .45f, wide); road(.1f, -.1f, .35f, 1.1f, thin); road(.6f, -.1f, .7f, 1.1f, wide)
        road(-.05f, .85f, 1.05f, .7f, thin); road(.85f, -.1f, .95f, 1.1f, thin)
        val route = androidx.compose.ui.graphics.Path().apply {
            moveTo(-.02f * w, .75f * h); cubicTo(.25f * w, .9f * h, .35f * w, .2f * h, .62f * w, .38f * h); lineTo(1.02f * w, .2f * h)
        }
        drawPath(route, line, style = Stroke(4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawLine(other, Offset(w * .2f, h * 1.05f), Offset(w * .78f, h * -.05f), 3.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        listOf(.62f to .38f, .3f to .55f, .82f to .27f).forEach { (x, y) ->
            drawCircle(K.bg, 5.dp.toPx(), Offset(w * x, h * y)); drawCircle(line, 3.dp.toPx(), Offset(w * x, h * y))
        }
    }
}
