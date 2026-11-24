package uk.noammm.kav.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DepartureBoard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocalOffer
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.noammm.kav.ext.Capabilities
import uk.noammm.kav.ext.ExtUses
import uk.noammm.kav.ext.Looks
import uk.noammm.kav.ext.PageSpecs
import uk.noammm.kav.ext.Pipeline
import uk.noammm.kav.ext.SourceImages
import java.io.File

// The pieces Settings › Extensions builds its sheets and cards from: a source's picture (or a letter tile), a header
// with the picture and name in the middle, the one line that says an extension isn't Kav's and the notice behind it,
// what an extension can do as short rows in Kav's own words (the publisher's note opens under a row), and a Details row
// that keeps keys and addresses out of the way.

// Pictures decoded once per file version: within SourceImages' bounds, and sampled down to about the size drawn.
private object Avatars {
    private val decoded = HashMap<String, ImageBitmap?>()

    @Synchronized fun cached(stamp: String?): ImageBitmap? = stamp?.let { decoded[it] }

    // An installed extension's own picture: its file is the package's own, already within the package's limits.
    fun decodeAny(f: File, stamp: String, px: Int): ImageBitmap? = runCatching { decode(f.readBytes(), stamp, px) }.getOrNull()

    // An extension's own picture, from its package's bytes (the install sheet, before anything is on the phone).
    fun decode(bytes: ByteArray, stamp: String, px: Int): ImageBitmap? {
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < 1 || bounds.outHeight < 1) return@runCatching null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
        synchronized(this) { decoded[stamp] = bmp }
        return bmp
    }

    fun decode(f: File, stamp: String, px: Int): ImageBitmap? {
        val bmp = runCatching {
            if (f.length() > SourceImages.MAX) return@runCatching null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth !in 1..SourceImages.SIDE || bounds.outHeight !in 1..SourceImages.SIDE) return@runCatching null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
        synchronized(this) { decoded[stamp] = bmp }
        return bmp
    }
}

// The first letter or digit of a name, for its tile.
private fun initial(name: String): String {
    var i = 0
    while (i < name.length) {
        val cp = name.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp)).uppercase(T.locale)
        i += Character.charCount(cp)
    }
    return "?"
}

// A source's picture in a circle: the picture its bundle brings (bytes, while it's offered and not added yet), else the
// file SourceImages keeps (null for none, as for a file import); its name's first letter on a quiet tile until there is
// one, and whenever it can't be read.
@Composable
internal fun SourceAvatar(file: File?, name: String, size: Dp, modifier: Modifier = Modifier, kav: Boolean = false, bytes: ByteArray? = null) {
    // Kav's own sources wear Kav's icon.
    if (kav) {
        Box(modifier.size(size).clip(CircleShape)) {
            Image(androidx.compose.ui.res.painterResource(uk.noammm.kav.R.drawable.ic_launcher_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Image(androidx.compose.ui.res.painterResource(uk.noammm.kav.R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        return
    }
    ExtUi.tick
    val px = with(LocalDensity.current) { size.roundToPx() }
    val stamp = bytes?.let { "bytes:${it.size}:${it.contentHashCode()}:$px" } ?: file?.takeIf { it.isFile }?.let { "${it.path}:${it.lastModified()}:$px" }
    val image by produceState(Avatars.cached(stamp), stamp) {
        if (stamp != null && value == null) value = withContext(Dispatchers.IO) { if (bytes != null) Avatars.decode(bytes, stamp, px) else file?.let { Avatars.decode(it, stamp, px) } }
        if (stamp == null) value = null
    }
    Box(modifier.size(size).clip(CircleShape).background(K.surface3), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(initial(name), style = K.type.headline.copy(fontSize = (size.value * .4f).sp, lineHeight = (size.value * .5f).sp), color = K.textSecondary)
    }
}

// An extension's own picture (Manifest.icon) on a rounded square, as an app's is, from the installed package (file) or
// from the package being offered (bytes); without one, the mark of what it adds on a quiet tile. A source's is round.
@Composable
internal fun ExtTile(file: File?, bytes: ByteArray?, capabilities: List<String>, size: Dp, modifier: Modifier = Modifier) {
    ExtUi.tick
    val px = with(LocalDensity.current) { size.roundToPx() }
    val stamp = file?.takeIf { it.isFile }?.let { "${it.path}:${it.lastModified()}:$px" } ?: bytes?.let { "bytes:${it.size}:${it.contentHashCode()}:$px" }
    val image by produceState(Avatars.cached(stamp), stamp) {
        if (stamp != null && value == null) value = withContext(Dispatchers.IO) {
            if (file != null && file.isFile) Avatars.decodeAny(file, stamp, px) else bytes?.let { Avatars.decode(it, stamp, px) }
        }
        if (stamp == null) value = null
    }
    Box(modifier.size(size).clip(RoundedCornerShape(size * .28f)).background(K.fill), contentAlignment = Alignment.Center) {
        val img = image
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(listingIcon(capabilities), null, tint = K.textSecondary, modifier = Modifier.size(size * .55f))
    }
}

// The picture, the name under it and a small line under that, in the middle: the top of the add popup, the install
// sheet and the pages. Room under the picture; the name and its line kept close, as one block.
@Composable
internal fun CentredHeader(avatar: @Composable () -> Unit, title: String, subtitle: String?, kav: Boolean? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        avatar()
        Text(title, style = K.type.title, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = K.gap4))
        // Who it comes from, in the same short line: its source, then a shield and whose it is (kav: Kav's own; false:
        // someone else's). The shield is the line's only colour.
        if (kav == null) subtitle?.let { Text(it, style = K.type.subhead, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap1)) {
            subtitle?.let { Text(T.iso(it) + "  ·", style = K.type.subhead, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            Icon(if (kav) Icons.Rounded.VerifiedUser else Icons.Rounded.GppMaybe, null, tint = if (kav) K.accent else K.problem, modifier = Modifier.size(16.dp))
            Text(if (kav) T("Made by Kav", "נוצר על ידי Kav") else T("Third-party", "צד שלישי"), style = K.type.subhead, maxLines = 1)
        }
    }
}

// The long sentence behind "Third-party", for Details: whose it is, said once in full.
@Composable
internal fun ThirdPartyFact() = Text(T("Not made or reviewed by Kav.", "לא נוצר ולא נבדק על ידי Kav."), style = K.type.subhead, color = K.textSecondary)

// The last row of a header card, under a hairline: it opens what most riders don't need (keys, addresses, the version).
// The same height and padding as the rows of Kav's grouped cards.
@Composable
internal fun Details(title: String = T("Details", "פרטים"), icon: ImageVector = Icons.Rounded.Info, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "details")
    Column(Modifier.fillMaxWidth()) {
        Separator()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = K.gap4, vertical = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Icon(icon, null, tint = K.dim)
            Text(title, style = K.type.body, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null, tint = K.dim, modifier = Modifier.size(20.dp).rotate(turn))
        }
        AnimatedVisibility(open, enter = unfold, exit = fold) {
            Column(Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, bottom = K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) { content() }
        }
    }
}

// Settings' own motion for what opens and closes in place (Settings.kt): a fade after a beat, and a spring with no bounce.
internal val unfold = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) +
    androidx.compose.animation.expandVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), expandFrom = Alignment.Top)
internal val fold = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) +
    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f), shrinkTowards = Alignment.Top)

// Something that unfolds into a list when it arrives, unless it was there when the list first showed (then the screen's
// own transition brings it).
@Composable
internal fun Arriving(already: Boolean, content: @Composable () -> Unit) {
    val state = remember { androidx.compose.animation.core.MutableTransitionState(already).apply { targetState = true } }
    AnimatedVisibility(state, enter = unfold, exit = fold) { content() }
}

// A row that turns something on or off, with no icon, as the rows beside it in its card: its words, and the switch at its
// end, which the whole row flips.
@Composable
internal fun SwitchRow(title: String, on: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) = ListRow(
    title, subtitle = subtitle, subtitleMaxLines = 2,
    modifier = Modifier.semantics { toggleableState = if (on) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off },
    trailing = { KavSwitch(on) }, onClick = { onChange(!on) }, chevron = false,
)

// A switch inside Details: its label, a line under it, and the switch, which the whole line flips.
@Composable
internal fun DetailSwitch(label: String, subtitle: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile)).clickable(role = Role.Switch) { onChange(!on) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = K.type.body)
            subtitle?.let { Text(it, style = K.type.subhead) }
        }
        KavSwitch(on)
    }
}

// One fact in Details: a small label and its value.
@Composable
internal fun DetailFact(label: String, value: String) {
    Column {
        Text(label, style = K.type.caption)
        Text(value, style = K.type.subhead)
    }
}

// A key's fingerprint in Details: groups of four, four groups to a line, in figures of one width, so two can be
// compared by eye and a group is never broken over two lines.
@Composable
internal fun KeyFact(label: String, fingerprint: String) {
    Column {
        Text(label, style = K.type.caption)
        Text(
            fingerprint.chunked(4).chunked(4).joinToString("\n") { T.ltr(it.joinToString(" ")) },
            style = K.type.subhead.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

// One short line with a mark before it, in the middle: what Kav has to say about a source or an extension without a
// card or a paragraph (it's third-party, it's unsigned, a part of it is paused).
@Composable
internal fun FlagLine(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Rounded.GppMaybe, tint: Color = K.problem) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2, Alignment.CenterHorizontally)) {
        Icon(icon, null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text, style = K.type.subhead, modifier = Modifier.weight(1f, fill = false))
    }
}

// What third-party means, in full: behind About extensions. Kav's own words throughout.
@Composable
internal fun ThirdPartyNotice(onClose: () -> Unit) {
    Popup(onClose, { KavButton(T("Got it", "הבנתי"), onClose, wide = true) }) {
        Text(T("Third-party extensions", "תוספים של צד שלישי"), style = K.type.title)
        Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
            NoticeRow(Icons.Rounded.Groups, T("Other people make and publish extensions and their sources.", "אנשים אחרים יוצרים ומפרסמים את התוספים ואת המקורות שלהם."))
            NoticeRow(Icons.Rounded.VerifiedUser, T("Kav doesn't make, review or endorse them.", "\u200fKav לא יוצרת אותם, לא בוחנת אותם ולא ממליצה עליהם."))
            NoticeRow(Icons.Rounded.Gavel, T("Each publisher alone is responsible for theirs.", "כל מפרסם אחראי לבדו למה שהוא מפרסם."))
            NoticeRow(Icons.Rounded.Policy, T("Kav's privacy policy doesn't cover them.", "מדיניות הפרטיות של Kav לא חלה עליהם."))
        }
        Text(T("Only add what you trust.", "הוסיפו רק מה שאתם סומכים עליו."), style = K.type.bodyStrong)
    }
}

// The first time the rider opens Settings › Extensions, before anything of it: whose extensions are, in three lines, and
// one button on. Shown once (ExtensionsPage keeps that it was seen).
@Composable
internal fun FirstNotice(onContinue: () -> Unit) {
    // It rises a little into place as it fades in, after the page has slid in.
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        shown,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 120)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 380f)) { it / 10 },
    ) { NoticeCard(onContinue) }
}

@Composable
private fun NoticeCard(onContinue: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
        KavCard {
            Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap4)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(K.fill), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Extension, null, tint = K.textSecondary, modifier = Modifier.size(28.dp))
                    }
                }
                Text(T("Before you add extensions", "לפני שמוסיפים תוספים"), style = K.type.headline,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Column(verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                    NoticeRow(Icons.Rounded.Groups, T("Extensions are made by other people, not by Kav.", "תוספים נוצרים על ידי אנשים אחרים, לא על ידי Kav."))
                    NoticeRow(Icons.Rounded.VerifiedUser, T("Kav doesn't review them and isn't responsible for what they do.", "‏Kav לא בוחנת אותם ואינה אחראית למה שהם עושים."))
                    NoticeRow(Icons.Rounded.Android, T("They're someone else's code, and can do anything Kav can on this phone.", "הם קוד של מישהו אחר, ויכולים לעשות בטלפון הזה כל מה ש-Kav יכולה."))
                    NoticeRow(Icons.Rounded.Gavel, T("Installing one is your own call.", "ההחלטה להתקין תוסף היא שלכם בלבד."))
                }
                KavButton(T("Continue", "המשך"), onContinue, wide = true)
            }
        }
    }
}

@Composable
private fun NoticeRow(icon: ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
        Icon(icon, null, tint = K.textSecondary, modifier = Modifier.padding(top = 1.dp).size(20.dp))
        Text(text, style = K.type.body, modifier = Modifier.weight(1f))
    }
}

// A popup of Kav's whose buttons stay in view: what's above them scrolls when it doesn't fit (Details open, large type,
// a short screen).
@Composable
internal fun Popup(onDismiss: () -> Unit, buttons: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap4)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(K.gap4), content = content)
            buttons()
        }
    }
}

// The mark of what an extension mostly is, for its tile in a source's list: from the capabilities its listing names and
// what its manifest says it adds.
internal fun listingIcon(capabilities: List<String>): ImageVector = when {
    Capabilities.PLAN in capabilities -> Icons.Rounded.Route
    Capabilities.BOARD in capabilities -> Icons.Rounded.DepartureBoard
    "slot.home" in capabilities || PageSpecs.HOME in capabilities -> Icons.Rounded.Home
    Looks.CAPABILITY in capabilities -> Icons.Rounded.Palette
    PageSpecs.PAGE in capabilities -> Icons.Rounded.Dashboard
    "slot.results" in capabilities -> Icons.AutoMirrored.Rounded.List
    "slot.trip" in capabilities -> Icons.Rounded.Timeline
    "slot.stop" in capabilities -> Icons.Rounded.Place
    Pipeline.WALK in capabilities -> Icons.AutoMirrored.Rounded.DirectionsWalk
    capabilities.any { it in Pipeline.HOOKS } -> Icons.Rounded.Tune
    "map" in capabilities -> Icons.Rounded.Map
    else -> Icons.Rounded.Extension
}

private fun kindIcon(cap: String): ImageVector = when (cap) {
    Capabilities.PLAN -> Icons.Rounded.Route
    Capabilities.BOARD -> Icons.Rounded.DepartureBoard
    uk.noammm.kav.ext.StopPhotoHook.CAPABILITY -> Icons.Rounded.PhotoCamera
    uk.noammm.kav.ext.TripAccess.HISTORY -> Icons.Rounded.History
    uk.noammm.kav.ext.TripAccess.FOLLOW -> Icons.Rounded.Navigation
    Pipeline.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
    Pipeline.TRANSFER -> Icons.Rounded.SwapHoriz
    Pipeline.FILTER -> Icons.Rounded.FilterList
    Pipeline.RANK -> Icons.Rounded.SwapVert
    else -> Icons.Rounded.LocalOffer
}

private fun kindWords(cap: String) = when (cap) {
    Capabilities.PLAN -> T("Trip planner", "מתכנן מסלולים")
    Capabilities.BOARD -> T("Provides timetables and stop boards", "מספק לוחות זמנים ולוחות תחנה")
    uk.noammm.kav.ext.StopPhotoHook.CAPABILITY -> T("Stop photos", "תמונות של תחנות")
    uk.noammm.kav.ext.TripAccess.HISTORY -> T("Trip history", "היסטוריית נסיעות")
    uk.noammm.kav.ext.TripAccess.FOLLOW -> T("Your trips", "הנסיעות שלכם")
    Pipeline.WALK -> T("Walking model", "מודל הליכה")
    Pipeline.TRANSFER -> T("Transfer rule", "כלל החלפות")
    Pipeline.FILTER -> T("Route filter", "מסנן מסלולים")
    Pipeline.RANK -> T("Ranker", "מדרג מסלולים")
    else -> T("Route tags", "תגיות למסלולים")
}

private fun changeWords(cap: String) = when (cap) {
    Pipeline.WALK -> T("walk times", "זמני הליכה")
    Pipeline.TRANSFER -> T("which changes you can make", "אילו החלפות אפשר לעשות")
    Pipeline.FILTER -> T("which routes show", "אילו מסלולים מוצגים")
    Pipeline.RANK -> T("their order", "הסדר שלהם")
    else -> T("tags on routes", "תגיות על מסלולים")
}

private fun lineIcon(l: ExtUses.Line): ImageVector = when (l.what) {
    ExtUses.What.KIND -> kindIcon(l.detail.first())
    ExtUses.What.SEES_SEARCHES -> Icons.Rounded.Search
    ExtUses.What.CHANGES_RESULTS -> Icons.Rounded.Tune
    ExtUses.What.KEEPS_DATA -> Icons.Rounded.Storage
    ExtUses.What.NATIVE -> Icons.Rounded.Android
    ExtUses.What.PERMISSIONS -> Icons.Rounded.Shield
    ExtUses.What.SCREENS -> Icons.Rounded.Dashboard
    ExtUses.What.TAB -> Icons.Rounded.Tab
    ExtUses.What.HOME -> Icons.Rounded.Home
    ExtUses.What.LOOK -> Icons.Rounded.Palette
    ExtUses.What.SLOT_HOME -> Icons.Rounded.Home
    ExtUses.What.SLOT_RESULTS -> Icons.AutoMirrored.Rounded.List
    ExtUses.What.SLOT_TRIP -> Icons.Rounded.Timeline
    ExtUses.What.SLOT_STOP -> Icons.Rounded.Place
    ExtUses.What.ADDS -> addIcon(l.detail.first())
    ExtUses.What.ACTIONS -> Icons.Rounded.TouchApp
    ExtUses.What.PARTS -> Icons.Rounded.Dashboard
    ExtUses.What.HOOKS -> Icons.Rounded.Build
}

// An action an extension takes over (ext/Actions.kt), as a few words.
private fun actionWords(name: String) = when (name) {
    "pay" -> T("paying", "תשלום")
    "ticket.open" -> T("opening tickets", "פתיחת כרטיסים")
    "stop.open" -> T("opening stops", "פתיחת תחנות")
    "line.open" -> T("opening lines", "פתיחת קווים")
    "trip.open" -> T("opening trips", "פתיחת נסיעות")
    "trip.start" -> T("starting trips", "התחלת נסיעות")
    "trip.end" -> T("ending trips", "סיום נסיעות")
    "share" -> T("sharing trips", "שיתוף נסיעות")
    "plan" -> T("trip searches", "חיפושי מסלולים")
    "report" -> T("reports", "דיווחים")
    "alert.open" -> T("service alerts", "הודעות שירות")
    "taxi" -> T("taxis", "מוניות")
    "street.open" -> "Street View"
    "place.save" -> T("saving places", "שמירת מקומות")
    "stop.save" -> T("saving stops", "שמירת תחנות")
    "line.save" -> T("saving lines", "שמירת קווים")
    "settings.open" -> T("opening Settings", "פתיחת ההגדרות")
    "tab.open" -> T("switching tabs", "מעבר בין לשוניות")
    "settings.page.open" -> T("opening Settings pages", "פתיחת עמודי הגדרות")
    "pay.history.open" -> T("opening ride history", "פתיחת היסטוריית נסיעות")
    "plan.stop" -> T("planning from or to a stop", "תכנון מתחנה או אליה")
    "live.show" -> T("changing what Live shows", "בחירת מה להציג בזמן אמת")
    "search.open" -> T("opening place search", "פתיחת חיפוש מקומות")
    "results.place.pick" -> T("choosing places for trips", "בחירת מקומות לנסיעה")
    "results.endpoints.swap" -> T("swapping start and destination", "החלפת מוצא ויעד")
    "results.stop.remove" -> T("removing stops along a trip", "הסרת עצירות בדרך")
    "results.stop.stay" -> T("setting time at a stop", "הגדרת זמן שהייה בעצירה")
    "results.time.select" -> T("choosing when to travel", "בחירת מועד נסיעה")
    "results.order.select" -> T("sorting routes", "מיון מסלולים")
    "results.planner.select" -> T("choosing a trip planner", "בחירת מתכנן נסיעות")
    "trip.track" -> T("following a ride's bus", "מעקב אחרי האוטובוס של נסיעה")
    "nav.step" -> T("moving between a trip's steps", "מעבר בין שלבי הנסיעה")
    "nav.board.select" -> T("choosing which bus to take", "בחירת האוטובוס לעלות עליו")
    "nav.replan" -> T("finding a new route on the way", "חיפוש מסלול חדש בדרך")
    "nav.replan.keep" -> T("keeping a route on the way", "המשך במסלול בדרך")
    "nav.plan" -> T("showing the plan on the way", "הצגת המסלול בדרך")
    "nav.exit" -> T("leaving a trip for Home", "יציאה מהנסיעה למסך הבית")
    "share.send" -> T("sharing trips", "שיתוף נסיעות")
    "pay.mode" -> T("turning paying on and off", "הפעלה וכיבוי של התשלום")
    "pay.offer.hide" -> T("hiding the offer to pay", "הסתרת ההצעה לשלם")
    "pay.place.open" -> T("opening Rav-Pass's pages", "פתיחת עמודים ברב-פס")
    "pay.history.clear" -> T("clearing ride history", "מחיקת היסטוריית הנסיעות")
    "ticket.ravpass.open" -> T("showing tickets in Rav-Pass", "הצגת כרטיסים ברב-פס")
    else -> T.ltr(name)
}

// A part of Kav's screens (ext/Parts.kt), as a few words.
private fun partWords(name: String) = when (name) {
    "home.places" -> T("Home's places", "המקומות במסך הבית")
    "home.journey" -> T("Home's trip under way", "הנסיעה הפעילה במסך הבית")
    "home.suggestions" -> T("Home's suggestions", "ההצעות במסך הבית")
    "home.latest" -> T("Home's latest route", "המסלול האחרון במסך הבית")
    "home.pay" -> T("Home's pay card", "כרטיס התשלום במסך הבית")
    "home.recent" -> T("Home's recent trips", "הנסיעות האחרונות במסך הבית")
    "trip.header" -> T("a trip's header", "הכותרת של נסיעה")
    "trip.map" -> T("a trip's map", "המפה של נסיעה")
    "trip.summary" -> T("a trip's summary", "הסיכום של נסיעה")
    "trip.timeline" -> T("a trip's steps", "השלבים של נסיעה")
    "trip.ride" -> T("a trip's rides", "קטעי הנסיעה בתחבורה")
    "trip.walk" -> T("a trip's walks", "קטעי ההליכה")
    "results.route" -> T("trip results", "תוצאות החיפוש")
    "stop.header" -> T("a stop's header", "הכותרת של תחנה")
    "stop.board" -> T("stop boards", "לוחות תחנה")
    "line.header" -> T("a line's header", "הכותרת של קו")
    "settings.root" -> T("Settings", "ההגדרות")
    "settings.page.header" -> T("Settings headers", "כותרות בהגדרות")
    "settings.page.trips" -> T("trip settings", "הגדרות נסיעות")
    "settings.page.paying" -> T("payment settings", "הגדרות תשלום")
    "settings.page.live" -> T("live times and planner settings", "הגדרות זמני הגעה ומתכננים")
    "settings.page.look" -> T("look and language settings", "הגדרות מראה ושפה")
    "settings.page.map" -> T("map settings", "הגדרות מפה")
    "settings.page.privacy" -> T("privacy and sharing settings", "הגדרות פרטיות ושיתוף")
    "settings.page.extensions" -> T("extension settings", "הגדרות תוספים")
    "settings.page.kav" -> T("Kav settings", "הגדרות Kav")
    "pay.history.header" -> T("the ride history header", "כותרת היסטוריית הנסיעות")
    "pay.history.summary" -> T("ride history totals", "סיכום היסטוריית הנסיעות")
    "pay.history.empty" -> T("empty ride history", "היסטוריית נסיעות ריקה")
    "pay.history.month" -> T("months in ride history", "חודשים בהיסטוריית הנסיעות")
    "pay.history.ride" -> T("rides in ride history", "נסיעות בהיסטוריית הנסיעות")
    "stops.header" -> T("the Stops tab header", "כותרת לשונית התחנות")
    "stops.search" -> T("stop search", "חיפוש תחנות")
    "lines.header" -> T("the Lines tab header", "כותרת לשונית הקווים")
    "lines.search" -> T("line search", "חיפוש קווים")
    "lines.filters" -> T("line filters", "מסנני קווים")
    "live.header" -> T("the Live tab header", "כותרת לשונית זמן אמת")
    "live.filters" -> T("Live filters", "מסנני זמן אמת")
    "results.endpoints" -> T("the search's start and destination", "המוצא והיעד בחיפוש")
    "results.options" -> T("trip search options", "אפשרויות חיפוש מסלולים")
    "results.time.shift" -> T("earlier and later routes", "מסלולים מוקדמים ומאוחרים")
    "results.planners" -> T("trip planner tabs", "לשוניות מתכנני הנסיעות")
    "ticket.header" -> T("ticket headers", "כותרות כרטיסים")
    "ticket.validity" -> T("ticket validity", "תוקף כרטיסים")
    "ticket.ride" -> T("the ride on a ticket", "פרטי הנסיעה בכרטיס")
    "nav.header" -> T("the top of a trip under way", "החלק העליון של נסיעה בדרך")
    "nav.problem" -> T("problems on the way", "בעיות בדרך")
    "nav.card" -> T("a trip's step cards", "כרטיסי השלבים של נסיעה")
    "nav.wait.details" -> T("each line on a trip's waiting card", "כל קו בכרטיס ההמתנה של נסיעה")
    "nav.ride.details" -> T("the line on a trip's riding card", "הקו בכרטיס הנסיעה")
    "share.targets" -> T("ways to share a trip", "דרכים לשתף נסיעה")
    "ticket.codes" -> T("ticket codes", "קודי כרטיסים")
    "pay.setup" -> T("setting up paying", "הגדרת התשלום")
    "tabbar" -> T("the tab bar", "סרגל הלשוניות")
    else -> T.ltr(name)
}

private fun addIcon(a: String): ImageVector = when (a) {
    "map" -> Icons.Rounded.Map
    "notifications" -> Icons.Rounded.Notifications
    "location" -> Icons.Rounded.MyLocation
    else -> Icons.Rounded.Settings
}

// A word of the manifest's "adds", short, as on its row.
private fun addWord(a: String): String = when (a) {
    "map" -> T("Map", "מפה")
    "notifications" -> T("Notifications", "התראות")
    "location" -> T("Location", "מיקום")
    else -> T("Settings", "הגדרות")
}

// Kav's words for a line: all from the signed manifest, none of them the publisher's.
internal fun lineWords(l: ExtUses.Line): String {
    val list = ", "
    return when (l.what) {
        ExtUses.What.KIND -> kindWords(l.detail.first())
        ExtUses.What.SEES_SEARCHES -> T("Sees your trip searches", "רואה את חיפושי המסלולים שלכם")
        ExtUses.What.CHANGES_RESULTS -> T("Changes your results: ", "משנה את התוצאות שלכם: ") + l.detail.joinToString(list, transform = ::changeWords)
        ExtUses.What.KEEPS_DATA -> T("Keeps data on your phone", "שומר מידע בטלפון שלכם")
        ExtUses.What.NATIVE -> T("Runs Android code: full access", "מריץ קוד Android: גישה מלאה")
        ExtUses.What.PERMISSIONS -> T("Asks for: ", "מבקש הרשאות: ") + l.detail.joinToString(list) { T.ltr(it) }
        ExtUses.What.SCREENS -> T("Adds screens", "מוסיף מסכים")
        ExtUses.What.TAB -> T("Adds a tab you can pin", "מוסיף לשונית שאפשר להצמיד")
        ExtUses.What.HOME -> T("Can replace your Home screen", "יכול להחליף את מסך הבית שלכם")
        ExtUses.What.LOOK -> T("Changes how Kav looks", "משנה את המראה של Kav")
        ExtUses.What.SLOT_HOME -> T("Can add a card to your Home screen", "יכול להוסיף כרטיס למסך הבית שלכם")
        ExtUses.What.SLOT_RESULTS -> T("Can add a line to your trip results", "יכול להוסיף שורה לתוצאות החיפוש שלכם")
        ExtUses.What.SLOT_TRIP -> T("Can add a part to a trip's page", "יכול להוסיף חלק לעמוד של נסיעה")
        ExtUses.What.SLOT_STOP -> T("Can add a row to stop boards", "יכול להוסיף שורה ללוחות תחנה")
        ExtUses.What.ADDS -> when (l.detail.first()) {
            "map" -> T("Draws on Kav's maps", "מצייר על המפות של Kav")
            "notifications" -> T("Sends notifications", "שולח התראות")
            "location" -> T("Uses your location", "משתמש במיקום שלכם")
            else -> T("Has settings", "כולל הגדרות")
        }
        ExtUses.What.ACTIONS -> T("Takes over: ", "לוקח על עצמו: ") + l.detail.joinToString(list, transform = ::actionWords)
        ExtUses.What.PARTS -> T("Changes parts of Kav's screens: ", "משנה חלקים במסכים של Kav: ") + l.detail.joinToString(list, transform = ::partWords)
        ExtUses.What.HOOKS -> T("Changes how Kav itself works", "משנה את האופן שבו Kav עצמה פועלת")
    }
}

// Publisher text set apart inside Kav's words: isolated, in quotes. Straight quotes in Hebrew, as Kav's Hebrew uses them
// (curly ones come out the wrong way round around a Hebrew name); the text can't hold either kind (Names.clean).
internal fun quoted(s: String) = if (T.rtl) T.iso("\"$s\"") else "\u2068\u201c$s\u201d\u2069"

// The publisher's explanation of a line, in Kav's language, else the other one.
private fun noteOf(l: ExtUses.Line): String? = l.note?.let { (en, he) -> (if (T.rtl) he.ifEmpty { en } else en.ifEmpty { he }).ifEmpty { null } }

// What an extension adds, in a few of Kav's words, for its row in a source's list and in Installed: from the
// capabilities its listing or manifest names. As many as fit a line, then how many more, so nothing is cut.
// An installed extension's row line: what it adds (its manifest's parts, its tab, settings it declares, and what it says
// it adds), else its own short description.
internal fun rowWords(m: uk.noammm.kav.ext.Manifest): String {
    val decl = runCatching { PageSpecs.of(m) }.getOrNull()
    val tab = decl?.tabs?.isNotEmpty() == true
    // Its own screens go without saying once one of them is a tab.
    val caps = m.contributions.map { it.capability }.filter { !(tab && it == PageSpecs.PAGE) }
    return listingWords(caps, m.adds.filter { it != "settings" }, tab).ifEmpty { T.iso(m.description.substringBefore(". ").take(60)) }
}

internal fun listingWords(capabilities: List<String>, adds: List<String> = emptyList(), tab: Boolean = false): String {
    // Its maps first (what a rider sees most), then what the manifest's parts are, its tab, then settings.
    val all = adds.filter { it != "settings" }.map(::addWord) + capabilities.distinct().map {
        when (it) {
            PageSpecs.PAGE -> T("Screens", "מסכים")
            PageSpecs.HOME -> T("Home screen", "מסך הבית")
            Looks.CAPABILITY -> T("Look", "מראה")
            "slot.home" -> T("Home card", "כרטיס בית")
            "slot.results" -> T("Results line", "שורה בתוצאות")
            "slot.trip" -> T("Trip page", "עמוד נסיעה")
            "slot.stop" -> T("Stop boards", "לוחות תחנה")
            "action.pay" -> T("Paying", "תשלום")
            uk.noammm.kav.ext.MethodHooks.CAPABILITY -> T("Changes Kav", "משנה את Kav")
            Capabilities.BOARD -> T("Timetables", "לוחות זמנים")
            else -> when {
                it.startsWith("action.") -> actionWords(it.removePrefix("action.")).replaceFirstChar { c -> c.uppercase() }
                it.startsWith("part.") -> partWords(it.removePrefix("part.")).replaceFirstChar { c -> c.uppercase() }
                else -> kindWords(it)
            }
        }
    } + listOfNotNull(
        if (tab) T("Tab", "לשונית") else null,
    )
    // As many as fit one line, with no count of the rest.
    var shown = 0
    var length = 0
    for (w in all) {
        if (shown >= 3 || (shown > 0 && length + w.length > 30)) break
        shown++; length += w.length
    }
    return all.take(shown).joinToString(" · ") { T.iso(it) }
}

// A line's short label, for the list of what an extension can do: Kav's words, a few of them.
internal fun lineLabel(l: ExtUses.Line): String {
    return when (l.what) {
        ExtUses.What.KIND -> when (l.detail.first()) {
            Capabilities.BOARD -> T("Timetables and stop boards", "לוחות זמנים ולוחות תחנה")
            uk.noammm.kav.ext.TripAccess.HISTORY -> T("Reads your recent trips, if you allow it", "קורא את הנסיעות האחרונות שלכם, אם תאשרו")
            uk.noammm.kav.ext.TripAccess.FOLLOW -> T("Follows the trips you take, if you allow it", "עוקב אחרי הנסיעות שלכם, אם תאשרו")
            else -> kindWords(l.detail.first())
        }
        ExtUses.What.KEEPS_DATA -> T("Keeps data on this phone", "שומר מידע בטלפון הזה")
        ExtUses.What.SCREENS -> T("Own screens", "מסכים ייעודיים")
        ExtUses.What.TAB -> T("A tab you can pin", "לשונית שאפשר להצמיד")
        ExtUses.What.SLOT_HOME -> T("Home card", "כרטיס במסך הבית")
        ExtUses.What.SLOT_RESULTS -> T("Line on trip results", "שורה בתוצאות החיפוש")
        ExtUses.What.SLOT_TRIP -> T("Part on a trip's page", "חלק בעמוד של נסיעה")
        ExtUses.What.SLOT_STOP -> T("Row on stop boards", "שורה בלוחות תחנה")
        ExtUses.What.ADDS -> addWord(l.detail.first())
        ExtUses.What.ACTIONS -> T("Takes over actions", "לוקח על עצמו פעולות")
        ExtUses.What.PARTS -> T("Changes screen parts", "משנה חלקי מסכים")
        else -> lineWords(l)
    }
}

// What tells a rider little about this one: kept out of the list, and said in one line under Details (alsoWords).
private val ALWAYS = setOf(ExtUses.What.KEEPS_DATA)

internal fun alsoWords(lines: List<ExtUses.Line>): String? =
    lines.filter { it.what in ALWAYS }.takeIf { it.isNotEmpty() }?.joinToString(" · ") { T.iso(lineLabel(it)) }


// What an extension can do, a short row each: its mark and Kav's label. A row with more to it has a small arrow and
// opens in place: the publisher's own note, labelled as theirs,
// in quotes and isolated, so it reads as quoted and can't reorder Kav's words. Past eight rows the first seven show and
// the rest are a tap away. Android code of its own is the strong row, first. trailing: something at a row's end (its
// switch, on the extension's own page). every: for an update's new lines, where even what every extension does is news.
@Composable
internal fun CanDo(lines: List<ExtUses.Line>, every: Boolean = false, trailing: (@Composable (ExtUses.Line) -> Unit)? = null) {
    val opened = remember { mutableStateMapOf<String, Boolean>() }
    val all = (if (every) lines else lines.filter { it.what !in ALWAYS }).filter { it.what != ExtUses.What.NATIVE }
    var everything by rememberSaveable { mutableStateOf(false) }
    val shown = if (everything || all.size <= 8) all else all.take(7)
    Column {
        for (l in shown) {
            val note = noteOf(l)
            val opens = note != null
            val key = l.what.name + l.key
            val open = opened[key] == true
            val turn by animateFloatAsState(if (open) 180f else 0f, label = "canDo")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile))
                    .then(if (opens) Modifier.clickable(role = Role.Button) { opened[key] = !open } else Modifier),
            ) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(vertical = K.gap2),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
                ) {
                    Icon(lineIcon(l), null, tint = K.textSecondary, modifier = Modifier.size(20.dp))
                    Text(lineLabel(l), style = K.type.body, modifier = Modifier.weight(1f))
                    if (opens) Icon(Icons.Rounded.ExpandMore, null, tint = K.textTertiary, modifier = Modifier.size(20.dp).rotate(turn))
                    trailing?.invoke(l)
                }
                AnimatedVisibility(open, enter = unfold, exit = fold) {
                    Column(Modifier.fillMaxWidth().padding(start = 32.dp, bottom = K.gap2), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        note?.let {
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(T("From the publisher: ", "מהמפרסם: ")) }
                                    append(T.iso(it))
                                },
                                style = K.type.subhead, color = K.textTertiary,
                            )
                        }
                    }
                }
            }
        }
        if (shown.size < all.size) Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(K.rTile)).clickable(role = Role.Button) { everything = true }.padding(vertical = K.gap2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Icon(Icons.Rounded.MoreHoriz, null, tint = K.textSecondary, modifier = Modifier.size(20.dp))
            Text(T("and ${all.size - shown.size} more", "ועוד ${all.size - shown.size}"), style = K.type.body, color = K.accentInk, modifier = Modifier.weight(1f))
        }
    }
}
