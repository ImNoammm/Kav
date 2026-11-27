package uk.noammm.kav.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import uk.noammm.kav.KavModel
import uk.noammm.kav.data.Net
import java.util.Calendar

// Kav's own pickers for an extension's settings (ext/Pages.kt: place, stop, time, date, number, color), and Kav's place
// search and Android's file picker for a native extension that asks (Kav.pickPlace, Kav.pickFile).

// A place or stop someone is waiting for: the search draws over everything (ExtPickHost) and gives back
// {"name", "lat", "lon"} (and "code" for a stop), or null when the rider closed it.
internal object ExtPicks {
    class Ask(val stop: Boolean, val title: String, val answer: (JSONObject?) -> Unit)

    var asking by mutableStateOf<Ask?>(null)
        private set

    fun ask(stop: Boolean = false, title: String? = null, answer: (JSONObject?) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            // One at a time: one already open is closed first, as if the rider had.
            asking?.answer?.invoke(null)
            asking = Ask(stop, title ?: if (stop) T("Choose a stop", "בחירת תחנה") else T("Choose a place", "בחירת מקום"), answer)
        }
    }

    fun done(value: JSONObject?) {
        val a = asking ?: return
        asking = null
        a.answer(value)
    }

    // A file someone is waiting for (Kav.pickFile): Android's photo picker when it asks for images only, else Android's
    // file picker, opened by ExtPickHost; null when the rider closed it. launched: opened already, so a screen made
    // again (a rotation) doesn't open it twice.
    class FileAsk(val types: List<String>, val answer: (android.net.Uri?) -> Unit) { var launched = false }

    var file by mutableStateOf<FileAsk?>(null)
        private set

    fun file(types: List<String>, answer: (android.net.Uri?) -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            file?.answer?.invoke(null)
            file = FileAsk(types, answer)
        }
    }

    fun fileDone(uri: android.net.Uri?) {
        val f = file ?: return
        file = null
        f.answer(uri)
    }
}

// At the root of Kav's screen, over everything: Kav's place search, rising in as it does everywhere else.
@Composable
internal fun ExtPickHost(model: KavModel) {
    val ctx = LocalContext.current
    val photo = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { ExtPicks.fileDone(it) }
    val document = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { ExtPicks.fileDone(it) }
    val asked = ExtPicks.file
    androidx.compose.runtime.LaunchedEffect(asked) {
        if (asked == null || asked.launched) return@LaunchedEffect
        asked.launched = true
        runCatching {
            if (asked.types.all { it.startsWith("image/") }) photo.launch(androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            else document.launch(asked.types.toTypedArray())
        }.onFailure { ExtPicks.fileDone(null) }
    }
    var kept by remember { mutableStateOf<ExtPicks.Ask?>(null) }
    ExtPicks.asking?.let { kept = it }
    androidx.compose.animation.AnimatedVisibility(ExtPicks.asking != null, enter = modalIn(), exit = modalOut()) {
        val a = kept ?: return@AnimatedVisibility
        var q by remember(a) { mutableStateOf("") }
        androidx.activity.compose.BackHandler { ExtPicks.done(null) }
        // Over the whole window, outside Kav's scaffold: kept between the status bar and the navigation bar (and above
        // the keyboard) as Kav's own screens are, on Kav's ground.
        Box(Modifier.fillMaxSize().background(K.bg).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) {
        PlacePicker(
            title = a.title,
            here = null,
            allowMyLocation = false,
            onMyLocation = {},
            onPick = { p ->
                if (!a.stop) ExtPicks.done(JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon))
                else {
                    // The timetable as loaded (Loaded.net), which Kav's own screens read.
                    val stop = (uk.noammm.kav.Loaded.net ?: model.net)?.let { stopFor(it, p.name, p.lat, p.lon) }
                    if (stop != null) ExtPicks.done(stop)
                    else android.widget.Toast.makeText(ctx, T("No stop there. Choose a stop.", "אין שם תחנה. בחרו תחנה."), android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { ExtPicks.done(null) },
            favourites = model.favourites,
            onSaveFavourites = { model.saveFavourites(ctx, it) },
            query = q,
            onQuery = { q = it },
            net = uk.noammm.kav.Loaded.net ?: model.net,
        )
        }
    }
}

// The stop picked: the nearest stop within 300 m of the point, one with the picked name first (a stop found by search
// may stand for its station, a little off the stop's own point).
private fun stopFor(net: Net, name: String, lat: Double, lon: Double): JSONObject? {
    var best = -1
    var bestD = Double.MAX_VALUE
    val cos = Math.cos(Math.toRadians(lat))
    for (i in 0 until net.nStops) {
        if (net.code[i] <= 0) continue
        val dy = (net.lat[i] - lat) * 111_320.0
        val dx = (net.lon[i] - lon) * 111_320.0 * cos
        // A stop of the picked name counts as 200 m nearer than it is.
        val d = Math.sqrt(dx * dx + dy * dy) - if (net.stopName(i) == name) 200.0 else 0.0
        if (d < bestD) { bestD = d; best = i }
    }
    if (best < 0 || bestD > 300.0) return null
    return JSONObject().put("code", net.code[best].toString()).put("name", net.stopName(best)).put("lat", net.lat[best]).put("lon", net.lon[best])
}

// A number between min and max, a step at a time, at a row's end: − and + either side of the value and its unit.
@Composable
internal fun ExtStepper(value: Double, min: Double, max: Double, step: Double, unit: String, onChange: (Double) -> Unit) {
    fun snap(d: Double) = (Math.round((d - min) / step) * step + min).coerceIn(min, max)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
        StepButton(Icons.Rounded.Remove, T("Less", "פחות"), value > min) { onChange(snap(value - step)) }
        androidx.compose.animation.AnimatedContent(value, Modifier.widthIn(min = 56.dp),
            transitionSpec = { sideways(targetState > initialState) }, label = "stepper") { v ->
            Text(numberWords(v, unit), style = K.type.body.tabular, textAlign = TextAlign.Center)
        }
        StepButton(Icons.Rounded.Add, T("More", "יותר"), value < max) { onChange(snap(value + step)) }
    }
}

// "3 min": a value as Kav writes it, with its unit when it has one.
internal fun numberWords(v: Double, unit: String): String {
    val n = if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
    return T.ltr(n) + if (unit.isNotEmpty()) " $unit" else ""
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(K.fill)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) K.text else K.textTertiary, modifier = Modifier.size(18.dp)) }
}

// One of a few choices, in a sheet: each a row, the chosen one ticked.
@Composable
internal fun ExtChoiceSheet(title: String, choices: List<String>, chosen: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
            SheetHeader(title, { close(onDismiss) })
            ListGroup {
                choices.forEachIndexed { n, c ->
                    row {
                        ListRow(c, onClick = { close { onPick(n); onDismiss() } }, chevron = false,
                            trailing = if (n == chosen) ({ Icon(Icons.Rounded.Check, null, tint = K.accentInk, modifier = Modifier.size(20.dp)) }) else null)
                    }
                }
            }
        }
    }
}

// Words, in a sheet with one field.
@Composable
internal fun ExtTextSheet(title: String, value: String, max: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf(value) }
    Sheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap4)) {
            SheetHeader(title, { close(onDismiss) })
            KavField(typed, { typed = it.take(max) }, "", Modifier.fillMaxWidth())
            KavButton(T("Done", "סיום"), { close { onPick(typed); onDismiss() } }, wide = true)
        }
    }
}

// A time: Kav's own time entry, the one trips are set with, in a sheet.
@Composable
internal fun ExtTimeSheet(title: String, value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val start = value.split(':').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }
        ?: Calendar.getInstance(ISRAEL).let { listOf(it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }
    var hour by remember { mutableIntStateOf(start[0]) }
    var minute by remember { mutableIntStateOf(start[1]) }
    Sheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap4), horizontalAlignment = Alignment.CenterHorizontally) {
            SheetHeader(title, { close(onDismiss) })
            TimeEntry(hour, minute) { h, m -> hour = h; minute = m }
            KavButton(T("Done", "סיום"), { close { onPick("%02d:%02d".format(java.util.Locale.US, hour, minute)); onDismiss() } }, wide = true)
        }
    }
}

// A date: Kav's day stepper, the one trips use, from a year back to a year ahead.
@Composable
internal fun ExtDateSheet(title: String, value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val today = remember { Calendar.getInstance(ISRAEL) }
    val from = remember(value) {
        runCatching {
            val (y, m, d) = value.split('-').map { it.toInt() }
            val c = Calendar.getInstance(ISRAEL).apply { set(y, m - 1, d) }
            ((c.timeInMillis - today.timeInMillis) / 86_400_000.0).let { Math.round(it).toInt() }
        }.getOrDefault(0)
    }
    var day by remember { mutableIntStateOf(from.coerceIn(-365, 365)) }
    Sheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap4)) {
            SheetHeader(title, { close(onDismiss) })
            Row(Modifier.fillMaxWidth().clip(K.capsule).background(K.fill), verticalAlignment = Alignment.CenterVertically) {
                Step(true, day > -365, T("Previous day", "היום הקודם")) { day-- }
                androidx.compose.animation.AnimatedContent(day, Modifier.weight(1f), transitionSpec = { sideways(targetState > initialState) }, label = "date") { d ->
                    Text(dayLabel(today, d), style = K.type.body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Step(false, day < 365, T("Next day", "היום הבא")) { day++ }
            }
            if (day != 0) KavButton(T("Today", "היום"), { day = 0 }, style = ButtonStyle.Plain, wide = true)
            KavButton(T("Done", "סיום"), {
                val c = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, day) }
                close { onPick("%04d-%02d-%02d".format(java.util.Locale.US, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))); onDismiss() }
            }, wide = true)
        }
    }
}

// How a date setting reads on its row: Today, Tomorrow or "Tue 19 Nov".
internal fun dateWords(value: String): String = runCatching {
    val (y, m, d) = value.split('-').map { it.toInt() }
    val today = Calendar.getInstance(ISRAEL)
    val c = Calendar.getInstance(ISRAEL).apply { set(y, m - 1, d) }
    val days = Math.round((c.timeInMillis - today.timeInMillis) / 86_400_000.0).toInt()
    if (days in -1..1) dayLabel(today, days) else java.text.SimpleDateFormat("EEE d MMM yyyy", T.locale).apply { timeZone = ISRAEL }.format(c.time)
}.getOrDefault(value)

// The presets: Kav's own accents and a spread of clear colours, light to deep.
private val PRESETS = listOf(
    0xFF4C8DFF, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835, 0xFFFFB300,
    0xFFF57C00, 0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB,
    0xFF039BE5, 0xFF00ACC1, 0xFF6D4C41, 0xFF757575, 0xFF37474F, 0xFF000000,
).map { Color(it) }

internal fun colorOf(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

private fun hexOf(c: Color) = "#%06X".format(c.toArgb() and 0xFFFFFF)

// A colour: presets to tap, and a hue and a shade to slide for any other; the colour itself, big, at the top.
@Composable
internal fun ExtColorSheet(title: String, value: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val start = colorOf(value) ?: PRESETS.first()
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(start.toArgb(), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    // One shade from pale (0) through the full colour (0.5) to deep (1).
    var shade by remember { mutableFloatStateOf(if (hsv[2] < 1f) 0.5f + (1f - hsv[2]) / 2f else hsv[1] / 2f) }
    var picked by remember { mutableStateOf(start) }
    fun fromSliders() = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue,
        if (shade <= .5f) shade * 2f else 1f, if (shade <= .5f) 1f else 1f - (shade - .5f) * 2f * .85f)))
    val shown by androidx.compose.animation.animateColorAsState(picked, androidx.compose.animation.core.tween(200), label = "picked")
    Sheet(onDismiss) { close ->
        Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap4)) {
            SheetHeader(title, { close(onDismiss) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(shown).border(1.dp, K.separator, CircleShape))
                Text(T.ltr(hexOf(picked)), style = K.type.title.tabular)
            }
            // Six a row, each a circle; the chosen one ringed and ticked.
            Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                for (row in PRESETS.chunked(6)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (c in row) {
                        val on = hexOf(c) == hexOf(picked)
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(c)
                                .border(if (on) 3.dp else 1.dp, if (on) K.text else K.separator, CircleShape)
                                .semantics { contentDescription = hexOf(c) }
                                .clickable(role = Role.Button) {
                                    picked = c
                                    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
                                    hue = hsv[0]; shade = if (hsv[2] < 1f) .5f + (1f - hsv[2]) / 2f else hsv[1] / 2f
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.animation.AnimatedVisibility(on, enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut()) {
                                Icon(Icons.Rounded.Check, null, tint = if (c.luminance() > .5f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            GradientSlider(T("Hue", "גוון"), hue / 360f, Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) })) {
                hue = it * 360f; picked = fromSliders()
            }
            GradientSlider(T("Shade", "בהירות"), shade, Brush.horizontalGradient(listOf(
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0f, 1f))),
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))),
                Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, .15f))),
            ))) { shade = it; picked = fromSliders() }
            KavButton(T("Done", "סיום"), { close { onPick(hexOf(picked)); onDismiss() } }, wide = true)
        }
    }
}

private fun Color.luminance(): Float = androidx.core.graphics.ColorUtils.calculateLuminance(toArgb()).toFloat()

// A track of colour to slide along, its thumb a ring at the value (0 to 1); tapped or dragged anywhere on it.
@Composable
private fun GradientSlider(label: String, value: Float, brush: Brush, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
        Text(label, style = K.type.caption)
        BoxWithConstraints(Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = label }) {
            val width = constraints.maxWidth.toFloat()
            fun at(x: Float) = (x / width).coerceIn(0f, 1f)
            Canvas(
                Modifier.fillMaxWidth().height(32.dp)
                    .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)) } }
                    .pointerInput(Unit) { detectDragGestures { change, _ -> onChange(at(change.position.x)) } },
            ) {
                val h = size.height
                drawRoundRect(brush, cornerRadius = CornerRadius(h / 2, h / 2))
                val x = (value * size.width).coerceIn(h / 2, size.width - h / 2)
                drawCircle(Color.White, h / 2 - 2.dp.toPx(), Offset(x, h / 2), style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = .25f), h / 2 - .5.dp.toPx(), Offset(x, h / 2), style = Stroke(1.dp.toPx()))
            }
        }
    }
}
