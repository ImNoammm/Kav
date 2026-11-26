package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import uk.noammm.kav.BuildConfig
import uk.noammm.kav.KavModel
import uk.noammm.kav.Prefs
import uk.noammm.kav.R
import uk.noammm.kav.data.AutoUpdate
import uk.noammm.kav.data.StartNotice
import uk.noammm.kav.data.Updates
import androidx.compose.runtime.LaunchedEffect

@Composable
fun UpdatePrompt(model: KavModel) {
    val release = model.update ?: return
    val ctx = LocalContext.current
    if (model.updateDismissed || !(Shown.updatePrompts || Prefs.autoUpdate(ctx))) return
    ReleasePrompt("Kav", "${Updates.installedVersion(ctx)} → ${release.version}", release, { model.updateDismissed = true }, {
        AnimatedVisibility(model.updateProgress != null || model.updateError != null, enter = opening, exit = closing) { UpdateProgress(model) }
    }) {
        UpdateButton(model, Modifier.weight(1f)) { model.startUpdate(ctx) }
    }
}

// Install now while it's running, and whether the last one didn't go through (InstallStatus).
object UpdateRun {
    var installing by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

// An update Kav downloaded and checked in the background (AutoUpdate), asked about as Kav opens: the update prompt with
// nothing left to download. Install now hands it to Android's installer, which closes Kav; Later asks again next time.
@Composable
internal fun ReadyPrompt(notice: AutoUpdate.Notice, onLater: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy = UpdateRun.installing
    ReleasePrompt(
        T("Kav ${notice.to} is ready 🎉", "Kav ${notice.to} מוכנה 🎉"), "${notice.from} → ${notice.to}", notice.release(),
        { if (!busy) { UpdateRun.failed = false; onLater() } },
        {
            // What the install is doing opens under the notes, kept while it closes, and changes in place.
            val failed = T("It didn't install.", "ההתקנה לא הושלמה.")
            val now = if (UpdateRun.failed) failed else if (busy) T("Installing: Kav closes when it's done.", "מתקינים: Kav תיסגר כשזה יסתיים.") else null
            var said by remember { mutableStateOf(now) }
            if (now != null) said = now
            AnimatedVisibility(now != null, enter = opening, exit = closing) {
                LiveText(said.orEmpty(), style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp), color = if (said == failed) K.critical else K.dim)
            }
        },
    ) {
        KavButton(T("Install now", "להתקין עכשיו"), {
            if (busy) return@KavButton
            if (!Updates.canInstall(ctx)) { Updates.askInstallPermission(ctx); return@KavButton }
            UpdateRun.installing = true
            UpdateRun.failed = false
            val app = ctx.applicationContext
            scope.launch {
                val handed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { AutoUpdate.installNow(app) }.getOrDefault(false) }
                if (!handed) { UpdateRun.installing = false; UpdateRun.failed = true }
            }
        }, Modifier.weight(1f), busy = busy)
    }
}

// A new version's popup: Kav's icon, the versions, the release's notes from GitHub, and Later (when there's one to
// put off) beside its action.
@Composable
private fun ReleasePrompt(
    title: String, versions: String, release: Updates.Release, onLater: () -> Unit, status: @Composable () -> Unit = {},
    later: Boolean = true, action: @Composable RowScope.() -> Unit,
) {
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                AppIcon(56.dp)
                Column {
                    Text(title, style = K.type.title)
                    Text(T.ltr(versions), style = K.type.subhead.tabular)
                }
            }
            ReleaseNotes(release, maxHeight = 260.dp)
            status()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                if (later) KavButton(T("Later", "אחר כך"), onLater, Modifier.weight(1f).heightIn(min = 52.dp), style = ButtonStyle.Secondary)
                action()
            }
        }
    }
}

@Composable
private fun AppIcon(size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.ic_launcher_background), contentDescription = null,
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
        Image(
            painterResource(R.drawable.ic_launcher_foreground), contentDescription = "Kav",
            modifier = Modifier.fillMaxSize().scale(1.5f),
        )
    }
}

@Composable
private fun ReleaseNotes(release: Updates.Release, maxHeight: androidx.compose.ui.unit.Dp) {
    val notes = release.notes.trim().ifBlank { release.name.ifBlank { T("No release notes.", "אין הערות גרסה.") } }
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
        Separator(0.dp)
        Spacer(Modifier.height(K.gap3))
        // The notes are GitHub's, in the language they were written in: each line reads in its own direction, so English
        // notes in a Hebrew Kav start at the left with their bullets first.
        val link = K.accentInk
        Text(
            remember(notes, link) { releaseNotesText(notes, SpanStyle(color = link)) }, Modifier.fillMaxWidth(),
            style = K.type.subhead.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
        )
    }
}

// GitHub's release notes are Markdown: shown as text, with bold as bold, italics as italics, a heading as a bold line, a
// list's marks as bullets, code as its plain words and a link as its words. A link opens only when it is https.
internal fun releaseNotesText(markdown: String, link: SpanStyle): AnnotatedString = buildAnnotatedString {
    markdown.lines().forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        val heading = HEADING.find(raw)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(raw.substring(heading.range.last + 1).trimEnd('#', ' '), link) }
            RULE.matches(raw) -> {}
            else -> {
                val item = ITEM.find(raw)
                val quote = QUOTE.find(raw)
                when {
                    item != null -> { append("• "); inline(raw.substring(item.range.last + 1), link) }
                    quote != null -> inline(raw.substring(quote.range.last + 1), link)
                    else -> inline(raw, link)
                }
            }
        }
    }
}

private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+")
private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val ITEM = Regex("^\\s*[-*+]\\s+")
private val QUOTE = Regex("^\\s{0,3}>\\s?")
private val BARE_URL = Regex("https://[^\\s<>()\\[\\]]*[^\\s<>()\\[\\].,;:!?'\"]")

// One line's words: escapes, `code`, **bold**, *italics*, [words](address), <address> and a bare https address.
private fun AnnotatedString.Builder.inline(text: String, link: SpanStyle, links: Boolean = true) {
    var i = 0
    val plain = StringBuilder()
    fun flush() { if (plain.isNotEmpty()) { append(plain.toString()); plain.clear() } }
    fun linked(words: String, url: String) {
        flush()
        if (url.startsWith("https://")) withLink(LinkAnnotation.Url(url, TextLinkStyles(link))) { inline(words, link, links = false) }
        else inline(words, link, links)
    }
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!<>|~" -> { plain.append(text[i + 1]); i += 2 }
            c == '`' -> {
                val ticks = text.substring(i).takeWhile { it == '`' }
                val end = text.indexOf(ticks, i + ticks.length)
                if (end < 0) { plain.append(ticks); i += ticks.length }
                else { plain.append(text.substring(i + ticks.length, end).trim()); i = end + ticks.length }
            }
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val mark = text.substring(i, i + 2)
                val end = text.indexOf(mark, i + 2)
                if (end <= i + 2 || (mark == "__" && i > 0 && text[i - 1].isLetterOrDigit())) { plain.append(mark); i += 2 }
                else { flush(); withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inline(text.substring(i + 2, end), link, links) }; i = end + 2 }
            }
            (c == '*' || (c == '_' && (i == 0 || !text[i - 1].isLetterOrDigit()))) && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                val end = text.indexOf(c, i + 1)
                if (end < 0 || text[end - 1].isWhitespace() || (c == '_' && end + 1 < text.length && text[end + 1].isLetterOrDigit())) {
                    plain.append(c); i++
                } else { flush(); withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(text.substring(i + 1, end), link, links) }; i = end + 1 }
            }
            c == '!' && text.startsWith("![", i) -> {
                val m = LINK.matchAt(text, i + 1)
                if (m == null) { plain.append(c); i++ } else { plain.append(m.groupValues[1]); i = m.range.last + 1 }
            }
            c == '[' && links -> {
                val m = LINK.matchAt(text, i)
                if (m == null) { plain.append(c); i++ } else { linked(m.groupValues[1], m.groupValues[2]); i = m.range.last + 1 }
            }
            c == '<' && links -> {
                val m = AUTOLINK.matchAt(text, i)
                if (m == null) { plain.append(c); i++ } else { linked(m.groupValues[1], m.groupValues[1]); i = m.range.last + 1 }
            }
            c == 'h' && links && text.startsWith("https://", i) && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                val m = BARE_URL.matchAt(text, i)
                if (m == null) { plain.append(c); i++ } else { flush(); withLink(LinkAnnotation.Url(m.value, TextLinkStyles(link))) { append(m.value) }; i = m.range.last + 1 }
            }
            else -> { plain.append(c); i++ }
        }
    }
    flush()
}

private val LINK = Regex("\\[([^\\]]*)]\\(\\s*<?([^)\\s>]*)>?(?:\\s+\"[^\"]*\")?\\s*\\)")
private val AUTOLINK = Regex("<(https?://[^>\\s]+)>")

@Composable
private fun UpdateProgress(model: KavModel) {
    // The bar and the error are kept while they close, and one fades into the other in place.
    var progress by remember { mutableStateOf(model.updateProgress) }
    model.updateProgress?.let { progress = it }
    var error by remember { mutableStateOf(model.updateError) }
    model.updateError?.let { error = it }
    AnimatedContent(
        model.updateError != null && model.updateProgress == null,
        transitionSpec = {
            (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
        },
        label = "updateProgress",
    ) { failed ->
    if (failed) Text(T("Could not update: $error", "העדכון נכשל: $error"), fontSize = 12.sp, color = K.critical, lineHeight = 17.sp)
    else progress?.let { p ->
        Column(verticalArrangement = Arrangement.spacedBy(K.gap1)) {
            // The bar fills smoothly between ticks, and its words and percentage roll.
            val fill by androidx.compose.animation.core.animateFloatAsState(p.coerceIn(0.02f, 1f), tween(300), label = "update")
            Box(Modifier.fillMaxWidth().height(6.dp).clip(K.capsule).background(K.surface4)) {
                Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(K.accent))
            }
            LiveText(
                if (p >= 1f) T("Opening the installer…", "פותחים את ההתקנה…")
                else T("Downloading… ${(p * 100).toInt()}%", "מורידים… ${(p * 100).toInt()}%"),
                style = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 12.sp), color = K.dim,
            )
        }
    }
    }
}

@Composable
private fun UpdateButton(model: KavModel, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val busy = model.updateProgress != null
    KavButton(T("Update", "עדכון"), onClick, modifier, busy = busy)
}

@Composable
fun UpdateSection(model: KavModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val release = model.update
    var checking by remember { mutableStateOf(false) }
    // The notes of the version in hand, from the newest release the background check kept, when that's this one: opening
    // this page asks GitHub nothing.
    var installedNotes by remember { mutableStateOf<Updates.Release?>(null) }
    LaunchedEffect(release) {
        if (release != null) return@LaunchedEffect
        installedNotes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Updates.stored(ctx) }
            ?.takeIf { it.version.removePrefix("v") == Updates.installedVersion(ctx).removePrefix("v") }
    }
    Column(Modifier.padding(horizontal = K.gap3).fillMaxWidth().panel(K.rCard).padding(K.gap4)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            AppIcon(40.dp)
            // A new status fades in over the old, and the card eases to any new height, as the button beside it changes width.
            Column(Modifier.weight(1f).animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
                Text(T.ltr("Kav ${Updates.installedVersion(ctx)}"), style = K.type.headline)
                val available = release?.let { T("${it.version} is available", "גרסה ${it.version} זמינה") }
                // Short enough for one line beside the button in both languages.
                ChangingText(
                    available ?: when {
                        model.updateFailed -> T("Couldn't check", "הבדיקה נכשלה")
                        model.updateChecked -> T("Up to date", "גרסה עדכנית")
                        else -> T("Not checked yet", "עוד לא נבדק")
                    },
                ) { Text(it, style = K.type.subhead, color = if (it == available) K.accentInk else K.textSecondary) }
            }
            // One button that turns from Check now into Update when a version is found: its words fade and its
            // colours ease, rather than one button taking the other's place.
            val updating = model.updateProgress != null
            KavButton(
                when {
                    release != null -> T("Update", "עדכון")
                    // Short, so the status beside it stays on one line on a narrow screen.
                    else -> T("Check now", "בדקו עכשיו")
                },
                {
                    if (release != null) model.startUpdate(ctx)
                    else if (!checking) scope.launch {
                        checking = true
                        model.checkForUpdate(ctx)
                        checking = false
                    }
                },
                style = if (release != null) ButtonStyle.Primary else ButtonStyle.Secondary,
                busy = if (release != null) updating else checking,
            )
        }
        // The notes, once fetched, and the download open under the row as InfoHeader's text does.
        val notes = release ?: installedNotes
        var lastNotes by remember { mutableStateOf(notes) }
        if (notes != null) lastNotes = notes
        AnimatedVisibility(notes != null, enter = opening, exit = closing) {
            AnimatedContent(
                lastNotes,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)))
                        .using(SizeTransform { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                },
                label = "releaseNotes",
            ) { shown -> shown?.let { Column(Modifier.padding(top = K.gap3)) { ReleaseNotes(it, maxHeight = 240.dp) } } }
        }
        AnimatedVisibility(model.updateProgress != null || model.updateError != null, enter = opening, exit = closing) {
            Column(Modifier.padding(top = K.gap3)) { UpdateProgress(model) }
        }
    }
}

// Said once, the first time Kav opens after an update, one it downloaded itself (AutoUpdate) or one installed by hand
// (WhatsNew): the version it came from, when known, and the one it's on, and what's new in it, open from the start as
// in ReadyPrompt.
@Composable
internal fun UpdatedNotice(notice: AutoUpdate.Notice, onClose: () -> Unit) =
    ReleasePrompt(
        T("Kav has been updated", "Kav עודכנה"), if (notice.from.isBlank()) notice.to else "${notice.from} → ${notice.to}",
        notice.release(), onClose, later = false,
    ) {
        KavButton(T("Close", "סגירה"), onClose, Modifier.weight(1f))
    }

// The message this version brings (StartNotice), once, in ReleasePrompt's look: its text, then why Kav needs help, and
// two ways to close it, Got it and Buy me a coffee, which opens the page as well. On a short screen the text gives way,
// so the ask and the buttons stay in view.
@Composable
internal fun NoticePrompt(notice: StartNotice.Message, onClose: (coffee: Boolean) -> Unit) {
    Dialog(onDismissRequest = { onClose(false) }) {
        Column(
            Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap4)) {
                AppIcon(56.dp)
                Column {
                    Text(notice.title, style = K.type.title)
                    Text(T.ltr(BuildConfig.VERSION_NAME), style = K.type.subhead.tabular)
                }
            }
            Column(Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                Separator(0.dp)
                Spacer(Modifier.height(K.gap3))
                Text(notice.text, style = K.type.subhead)
            }
            Column {
                Separator(0.dp)
                Spacer(Modifier.height(K.gap3))
                Text(notice.helpTitle, style = K.type.headline)
                Text(notice.help, style = K.type.subhead, modifier = Modifier.padding(top = K.gap1))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(T("Got it", "הבנתי"), { onClose(false) }, Modifier.heightIn(min = 52.dp), style = ButtonStyle.Secondary)
                KavButton(T("Buy me a coffee", "קנו לי קפה"), { onClose(true) }, Modifier.weight(1f))
            }
        }
    }
}

private val opening = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60))
private val closing = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120))
