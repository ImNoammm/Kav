package uk.noammm.kav.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.noammm.kav.ext.Capabilities
import uk.noammm.kav.ext.ExtApp
import uk.noammm.kav.ext.ExtLog
import uk.noammm.kav.ext.ExtUses
import uk.noammm.kav.ext.Extensions
import uk.noammm.kav.ext.Hub
import uk.noammm.kav.ext.GitHub
import uk.noammm.kav.ext.Manifest
import uk.noammm.kav.ext.Namespace
import uk.noammm.kav.ext.PageSpecs
import uk.noammm.kav.ext.Pipeline
import uk.noammm.kav.ext.NativeGuard
import uk.noammm.kav.ext.Registry
import uk.noammm.kav.ext.SafeMode
import uk.noammm.kav.ext.Selections
import uk.noammm.kav.ext.Slots
import uk.noammm.kav.ext.Themes
import uk.noammm.kav.ext.Screens
import uk.noammm.kav.ext.Bundle
import uk.noammm.kav.ext.TrustError

// Settings › Extensions: the sources the rider added, Morphe's way (PLAN.md section 7), what's installed from them or
// from files, and the switches for automatic updates, the security block list and safe mode. Each screen is a picture,
// a name, a short line and the action; keys, addresses and explanations are a tap away. Wherever a source is added or
// an extension installed, its header says whose it is (CentredHeader).

// A change made here or by a check: everything that shows the hub reads this, so it draws again.
internal object ExtUi {
    var tick by mutableIntStateOf(0)
    // The step inside Extensions Settings' header names (a source, Sources), or null for Extensions itself.
    var title by mutableStateOf<String?>(null)
    // The header's plus and refresh on Sources and a source's page (Settings draws them; refresh turns into a spinner
    // while checking), and a step inside Extensions to open (the crash popup's Open).
    var headerAdd by mutableStateOf<(() -> Unit)?>(null)
    var headerCheck by mutableStateOf<(() -> Unit)?>(null)
    var checking by mutableStateOf(false)
    var wanted by mutableStateOf<String?>(null)
    // The source cards open on the main page, by key, while Kav runs (null: the first one).
    var opened by mutableStateOf<String?>(null)
    // The Settings page a step was opened from (Look and language's themes): Back from that step returns there.
    var returnTo by mutableStateOf<String?>(null)
    fun changed() { tick++ }
}

// An add-source link (kav://add-source, kavapp.link/add-source) or a bundle file the rider opened from outside Kav:
// shown by StartPopups as soon as Kav is on the screen, and nothing is added without the rider's yes.
object PendingExtension {
    // A GitHub repository, "owner/repo".
    var link by mutableStateOf<String?>(null)
    var file by mutableStateOf<Uri?>(null)

    fun offer(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.data ?: return false
        if (data.scheme == "kav" || (data.scheme == "https" && data.host == "kavapp.link")) {
            GitHub.repoOf(data.toString())?.let { link = it; return true }
            return false
        }
        if (data.scheme == "content" || data.scheme == "file") {
            val name = data.lastPathSegment.orEmpty().lowercase()
            if (name.endsWith(Bundle.SUFFIX)) { file = data; return true }
        }
        return false
    }
}

// Settings asked to open on a part (the block-list notice opens Extensions).
internal object SettingsNav {
    var wanted by mutableStateOf<String?>(null)
    // The page asked for is a step back (Back from a step opened from it): it slides in as Back does.
    var backward = false
}

private suspend fun <T> io(block: () -> T): Result<T> = withContext(Dispatchers.IO) { runCatching(block) }

private fun toast(ctx: Context, s: String) = android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show()

internal fun extensionsSummary(ctx: Context): String {
    if (!ExtApp.dir(ctx).isDirectory) return T("None added", "לא נוספו")
    ExtUi.tick
    val h = ExtApp.hub(ctx)
    return listOfNotNull(
        T.count(h.sources.size, "1 source", "מקור אחד", "sources", "מקורות"),
        T.count(h.registry.installed.size, "1 installed", "תוסף מותקן אחד", "installed", "מותקנים"),
        if (SafeMode.active) T("safe mode", "מצב בטוח") else null,
    ).joinToString(" · ")
}

// A name its publisher chose, inside Kav's own sentence: isolated from the words around it, so it can't reorder Kav's,
// and with no quote marks, as no name in Kav's words has. The name is already plain text (Names.clean).
private fun named(s: String) = T.iso(s)

// Text of either direction inside a sentence or a line of parts, kept to itself so it can't reorder what's around it.
private fun iso(s: String) = T.iso(s)

// "1.0.0 · 2 KB · Dana's extensions": parts of a line, each isolated, so in Hebrew they keep their order.
private fun parts(vararg p: String?) = p.filterNotNull().joinToString(" · ", transform = ::iso)

private fun date(s: Long) = if (s <= 0) "" else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, T.locale).format(java.util.Date(s * 1000))

// The kept picture of a source, when it has one.
private fun imageOf(hub: Hub?, key: String?): java.io.File? = key?.let { hub?.images?.file(it) }

// Pictures arrive on a thread of their own: the screens draw again when one does.
private fun watchImages(hub: Hub) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    hub.images?.onChange = { main.post { ExtUi.changed() } }
}


private fun reasonWords(reason: String) = when (reason) {
    "malware" -> T("malware", "תוכנה זדונית")
    "credential-theft" -> T("stealing passwords or keys", "גניבת סיסמאות או מפתחות")
    "impersonation" -> T("pretending to be someone else", "התחזות")
    "kav-bug-abuse" -> T("abusing a bug in Kav", "ניצול באג ב-Kav")
    else -> reason
}

// Where an installed extension came from: its source.
private fun fromWords(hub: Hub, i: Registry.Installed) = hub.source(i.source)?.name.orEmpty()

// Why something didn't work, in the rider's language where Kav knows it, else as the check said it.
private fun failure(e: Throwable): String = when {
    e is java.io.IOException -> T("Couldn't connect: ", "אי אפשר היה להתחבר: ") + engineWords(e.message ?: "")
    e is TrustError -> T("Didn't pass Kav's checks: ", "הקובץ לא עבר את הבדיקות של Kav: ") + engineWords(e.message ?: "")
    e is Registry.Refused -> T("Not added: ", "לא נוסף: ") + engineWords(e.message ?: "")
    else -> e.message ?: e.javaClass.simpleName
}

// What Kav's own checks say (ext/ writes them in English), in the rider's language for the ones a rider is likely to
// meet; any other is shown as it is, isolated, after the Hebrew line that says what kind of problem it is.
private val BLOCKED = Regex("^(?:the source is )?on the security block list \\((.+)\\)$")
private val API = Regex("^made for another extension API \\((-?\\d+)\\)$")
private val FOLDER = Regex("^(.+): its folder isn't named as its manifest names it$")
private val UNSIGNED = Regex("^(.+) isn't signed by a key Kav trusts for it$")
private val UNDECLARED = Regex("^it can't bring code it doesn't declare: (.+)$")
internal fun engineWords(message: String): String {
    val m = message.trim()
    BLOCKED.find(m)?.let { return T("on the security block list (", "ברשימת החסימה האבטחתית (") + reasonWords(it.groupValues[1]) + ")" }
    API.find(m)?.let { return T(m, "נבנה לגרסה אחרת של ממשק התוספים (\u2066${it.groupValues[1]}\u2069)") }
    FOLDER.find(m)?.let { return T("${it.groupValues[1]}: the folder isn't named as the manifest names the extension", "${it.groupValues[1]}: שם התיקייה לא תואם לשם שבקובץ ה-manifest") }
    UNSIGNED.find(m)?.let { return T("${it.groupValues[1]} isn't signed by a key Kav trusts for that", "${it.groupValues[1]} לא חתום במפתח ש-Kav סומכת עליו לזה") }
    UNDECLARED.find(m)?.let { return T("code the manifest doesn't declare: ${it.groupValues[1]}", "קוד שלא מופיע בקובץ ה-manifest: ${it.groupValues[1]}") }
    return when (m) {
        "needs a newer version of Kav" -> T(m, "דורש גרסה חדשה יותר של Kav")
        Manifest.OLDER -> T(m, "נבנה לגרסה ישנה יותר של Kav")
        Hub.NOT_GITHUB -> T(m, "לא מאגר GitHub")
        Hub.ALREADY -> T(m, "כבר נוסף")
        GitHub.NO_RELEASE -> T("no such repository, or no release yet", "אין מאגר כזה, או שעוד אין גרסה")
        "its latest release has no files", "its latest release has no .kavb bundle" -> T("the latest release has no .kavb bundle", "בגרסה האחרונה אין קובץ ‎.kavb")
        "its latest release has more than one .kavb bundle" -> T("the latest release has more than one .kavb bundle", "בגרסה האחרונה יש יותר מקובץ ‎.kavb אחד")
        "no extensions in it" -> T("no extensions in the bundle", "אין תוספים בחבילה")
        "its icon isn't in it", "its icon isn't a picture in it" -> T("the icon isn't in the bundle", "הסמל לא נמצא בחבילה")
        "its icon isn't a PNG or JPEG of at most 256 KB and 1024 by 1024 pixels" -> T("the icon isn't a PNG or JPEG of at most 256 KB and 1024 by 1024 pixels", "הסמל אינו PNG או JPEG של עד ‎256 KB ו-‎1024×1024 פיקסלים")
        "a theme contribution without its theme" -> T("a theme contribution without a theme", "תרומת ערכת נושא בלי ערכת נושא")
        "native: its dex isn't in the package" -> T("native: the dex file isn't in the package", "קובץ ה-dex לא נמצא בחבילה")
        "couldn't place its code" -> T("couldn't place the extension's code", "אי אפשר היה לשמור את הקוד של התוסף")
        "a tab opens on its own, so it's an entry page" -> T("a tab page must be an entry page", "דף של לשונית חייב להיות דף כניסה")
        "preview it again" -> T("open the preview again", "יש לפתוח שוב את התצוגה המקדימה")
        "GitHub's answer isn't readable" -> T(m, "אי אפשר לקרוא את התשובה של GitHub")
        "too large" -> T(m, "גדול מדי")
        "took too long" -> T(m, "לקח יותר מדי זמן")
        "not an https address" -> T(m, "לא כתובת https")
        else -> T.ltr(m)
    }
}


// The first time, the notice (FirstNotice) comes before anything of Extensions, and before the hub is made; Continue
// moves on to Extensions the way Settings moves to a page.
@Composable
internal fun ExtensionsPage() {
    val ctx = LocalContext.current
    val store = remember { ctx.getSharedPreferences("extensions", Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(store.getBoolean("noticeSeen", false)) }
    androidx.compose.animation.AnimatedContent(seen, transitionSpec = { forward() }, label = "extNotice") { past ->
        if (past) ExtensionsMain()
        else FirstNotice { store.edit().putBoolean("noticeSeen", true).apply(); seen = true }
    }
}

// Where the rider is inside Extensions, one step a line: "sources", "s:<source key>", "e:<identity>". Back takes the
// last step off, and each step comes in and goes out as Settings' pages do.
@Composable
private fun ExtensionsMain() {
    val ctx = LocalContext.current
    val hub = remember { ExtApp.hub(ctx).also(::watchImages) }
    ExtUi.tick
    var path by rememberSaveable { mutableStateOf("") }
    val steps = path.split('\n').filter { it.isNotEmpty() }
    fun open(step: String) { path = (steps + step).joinToString("\n") }
    fun back() {
        val from = ExtUi.returnTo
        if (steps.size == 1 && from != null) { ExtUi.returnTo = null; SettingsNav.backward = true; SettingsNav.wanted = from }
        else path = steps.dropLast(1).joinToString("\n")
    }
    androidx.activity.compose.BackHandler(steps.isNotEmpty()) { back() }
    // A step asked from outside (the crash popup's Open): taken once.
    ExtUi.wanted?.let { w -> LaunchedEffect(w) { path = w; ExtUi.wanted = null } }
    var adding by remember { mutableStateOf(false) }
    // A bundle file the rider picked (its name and bytes), shown before it's added.
    var file by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            io { fileName(ctx, uri) to readFile(ctx, uri) }.onSuccess { file = it }.onFailure { toast(ctx, failure(it)) }
        }
    }
    // Settings' header names the step the rider is on; a screen showing one thing is titled with its name.
    val top = steps.lastOrNull()
    val title = when {
        top == null -> null
        top == "sources" -> T("Sources", "מקורות")
        top == "themes" -> T("Themes", "ערכות נושא")
        top.startsWith("s:") -> hub.source(top.removePrefix("s:"))?.name
        else -> hub.registry.get(top.removePrefix("e:"))?.displayName
    }
    // On Sources, the header's plus adds one and its refresh checks them all; on a GitHub source's page, refresh checks it.
    val add: (() -> Unit)? = if (top == "sources") ({ adding = true }) else null
    val checked = top?.removePrefix("s:")?.takeIf { top.startsWith("s:") }?.let(hub::source)?.takeIf { it.kind == Hub.Kind.GITHUB }
    val check: (() -> Unit)? = when {
        top == "sources" && hub.sources.isNotEmpty() -> ({ checkAll(ctx, scope) })
        checked != null -> ({ checkOne(ctx, scope, hub, checked) })
        else -> null
    }
    SideEffect { ExtUi.title = title; ExtUi.headerAdd = add; ExtUi.headerCheck = check }
    // "Start in safe mode" on Kav's icon only while something is installed (SafeStart).
    val count = hub.registry.installed.size
    LaunchedEffect(count) { io { uk.noammm.kav.ext.SafeStart.sync(ctx.applicationContext, count > 0) } }
    // Leaving Extensions closes the source cards the rider opened: the next visit starts as the first did.
    DisposableEffect(Unit) { onDispose { ExtUi.title = null; ExtUi.headerAdd = null; ExtUi.headerCheck = null; ExtUi.opened = null; ExtUi.returnTo = null } }
    androidx.compose.animation.AnimatedContent(
        path,
        transitionSpec = { if (depth(targetState) >= depth(initialState)) forward() else backward() },
        label = "extStep",
    ) { at ->
        val step = at.substringAfterLast('\n').ifEmpty { null }
        when {
            step == null -> MainExtensions(hub, ::open) { adding = true }
            step == "sources" -> SourcesScreen(hub, ::open)
            step == "themes" -> Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3).padding(top = K.gap3)) { ThemeList(hub) { open("e:$it") } }
            step.startsWith("s:") -> hub.source(step.removePrefix("s:"))
                ?.let { SourcePage(hub, it, ::open, ::back) } ?: LaunchedEffect(step) { back() }
            else -> hub.registry.get(step.removePrefix("e:"))
                ?.let { ExtensionPage(hub, it) } ?: LaunchedEffect(step) { back() }
        }
    }
    file?.let { f -> key(f) { AddSourcePopup(hub, null, f) { file = null } } }
    if (adding) AddSourceSheet(hub, { pick.launch(arrayOf("*/*")) }) { adding = false }
}

// Check now, for every source: what changed or failed, said in a toast.
private fun checkAll(ctx: Context, scope: kotlinx.coroutines.CoroutineScope) {
    if (ExtUi.checking) return
    ExtUi.checking = true
    scope.launch {
        io { ExtApp.check(ctx) }.onSuccess { r ->
            if (r != null) toast(ctx, listOfNotNull(
                if (r.updated.isNotEmpty()) T("Updated: ", "עודכנו: ") + r.updated.joinToString(", ") else null,
                if (r.failed.isNotEmpty()) T("Failed: ", "נכשלו: ") + r.failed.joinToString(", ") else null,
            ).joinToString("\n").ifEmpty { T("Everything is up to date", "הכול מעודכן") })
        }.onFailure { toast(ctx, failure(it)) }
        ExtUi.checking = false
        ExtUi.changed()
    }
}

// Check now, for one source, from its page.
private fun checkOne(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, hub: Hub, s: Hub.Source) {
    if (ExtUi.checking) return
    ExtUi.checking = true
    scope.launch {
        io { hub.refresh(s.key) }.onSuccess { if (it.updatedAt != s.updatedAt) ExtApp.loadNative(ctx) }.onFailure { toast(ctx, failure(it)) }
        ExtUi.checking = false; ExtUi.changed()
    }
}

// The header's refresh (Check now): Kav's spinner in its place while checking.
@Composable
internal fun ExtCheckButton(onCheck: () -> Unit) {
    androidx.compose.animation.AnimatedContent(ExtUi.checking,
        transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) },
        label = "checking") { busy ->
        if (busy) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Spinner(20.dp, track = K.fill, arc = K.textSecondary) }
        else KavIconButton(Icons.Rounded.Refresh, T("Check now", "בדיקה עכשיו"), onCheck)
    }
}

private fun depth(path: String) = if (path.isEmpty()) 0 else path.count { it == '\n' } + 1

private class SourceRow(val key: String, val name: String, val picture: java.io.File?, val line: String?, val problem: Boolean)

// A source's short status under its name: what's wrong with it, else its release.
private fun sourceStatus(hub: Hub, s: Hub.Source): Pair<String?, Boolean> = when {
    hub.blocked(s) != null -> T("Blocked", "חסום") to true
    s.error.isNotEmpty() -> T("Last check failed", "הבדיקה האחרונה נכשלה") to true
    s.kind == Hub.Kind.FILE -> T("From a file", "מקובץ") to false
    s.bundle.isEmpty() -> T("Not read yet", "עוד לא נקרא") to false
    // When it last changed; its release's name is on its own page, not up front.
    else -> s.updatedAt.takeIf { it > 0 }?.let { T("Updated ", "עודכן ב-") + dayWords(it) } to false
}

// "19 Nov", with the year only when it isn't this one.
private fun dayWords(s: Long): String {
    val then = java.util.Calendar.getInstance().apply { timeInMillis = s * 1000 }
    val thisYear = then.get(java.util.Calendar.YEAR) == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    return java.text.SimpleDateFormat(if (thisYear) "d MMM" else "d MMM yyyy", T.locale).format(then.time)
}

// Its switch: on runs it at once, off stops it; one Kav turned off after crashes comes back on with its count cleared. A
// tab it offers goes in the bar when no other extension's is there, and a planner it brings gets its tab on the results.
private fun switchOn(ctx: Context, hub: Hub, id: String, v: Boolean) {
    hub.setEnabled(id, v)
    if (v) {
        runCatching { uk.noammm.kav.ext.Hooks.refresh() }
        hub.registry.get(id)?.takeIf { i ->
            i.namespace !in Selections.planners && i.manifest.contributions.any { it.capability == Capabilities.PLAN }
        }?.let { i -> runCatching { Selections.setPlanner(ExtApp.dir(ctx), i.namespace, true) } }
        hub.registry.get(id)?.takeIf { hub.active(it) && uk.noammm.kav.ext.Screens.tab == null }?.let { i ->
            runCatching { PageSpecs.of(i.manifest).tabs.firstOrNull() }.getOrNull()
                ?.let { p -> uk.noammm.kav.ext.Screens.tabOf(i, p.id)?.let { uk.noammm.kav.ext.Screens.chooseTab(ExtApp.dir(ctx), it) } }
        }
        ExtApp.loadNative(ctx)
    }
    ExtShown.publish()
    // Its tab and theme step out or come back now, by whether it's on.
    val app = ctx.applicationContext
    Thread({ runCatching { ExtShown.refresh(app) } }, "kav-ext-screens").start()
    ExtUi.changed()
}

// The look Kav draws in: a theme applied (identity), every other theme switched off (Themes), or with null Kav's own,
// every theme off and no extension's look chosen either. One look at a time, as Screens keeps it.
internal fun applyLook(ctx: Context, hub: Hub, identity: String?) {
    for (o in Themes.toSwitchOff(hub.registry.installed, identity)) hub.setEnabled(o, false)
    Screens.chooseTheme(ExtApp.dir(ctx), identity?.let(hub.registry::get)?.let(Screens::themeOf))
    if (identity != null) switchOn(ctx, hub, identity, true)
    else {
        ExtShown.publish()
        val app = ctx.applicationContext
        Thread({ runCatching { ExtShown.refresh(app) } }, "kav-ext-screens").start()
        ExtUi.changed()
    }
}

// The applied theme's name, or Kav's own look.
private fun appliedName(hub: Hub): String = Themes.applied(hub.registry.installed)?.takeIf { hub.active(it) }?.displayName ?: T("Kav's own look", "המראה של Kav")

// A theme in a list: its mark, name and a line, Apply at its end (a tick once applied); the row opens its page.
@Composable
private fun ThemeRow(hub: Hub, i: Registry.Installed, line: String?, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val applied = hub.active(i) && Themes.applied(hub.registry.installed)?.identity == i.identity
    ThemeChoiceRow(i.displayName, line, { ExtTile(hub.registry.iconFile(i.identity), null, listOf(uk.noammm.kav.ext.Looks.CAPABILITY), 40.dp) },
        applied, hub.blocked(i) == null && i.problem.isEmpty(), { applyLook(ctx, hub, i.identity) }, onOpen)
}

@Composable
private fun ThemeChoiceRow(name: String, line: String?, tile: @Composable () -> Unit, applied: Boolean, can: Boolean, onApply: () -> Unit, onOpen: (() -> Unit)?) {
    ListRow(name, subtitle = line, subtitleMaxLines = 2, leading = tile, onClick = onOpen ?: { if (!applied) onApply() }, chevron = false, trailing = {
        androidx.compose.animation.AnimatedContent(applied,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)))
                    .using(androidx.compose.animation.SizeTransform { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f) })
            },
            label = "applied") { on ->
            if (on) Box(Modifier.heightIn(min = 36.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, T("Applied", "בשימוש"), tint = K.accentInk, modifier = Modifier.padding(horizontal = K.gap3).size(20.dp))
            }
            else if (can) SmallButton(T("Apply", "החלה"), false, onApply)
        }
    })
}

// Every installed theme, Kav's own look first: one applied at a time, Apply on each, a theme's page a tap away (onOpen).
@Composable
internal fun ThemeList(hub: Hub, onOpen: (String) -> Unit) {
    ExtUi.tick
    val ctx = LocalContext.current
    val themes = hub.registry.installed.filter { it.manifest.isTheme }
    val kavOwn = Themes.applied(hub.registry.installed)?.takeIf { hub.active(it) } == null && Screens.theme == null
    ListGroup(inset = K.gap4 + 40.dp + K.gap3) {
        row {
            ThemeChoiceRow("Kav", T("Kav's own look", "המראה של Kav"), { SourceAvatar(null, "Kav", 40.dp, kav = true) },
                kavOwn, true, { applyLook(ctx, hub, null) }, null)
        }
        for (i in themes) row { key(i.identity) { ThemeRow(hub, i, troubleOf(hub, i) ?: fromWords(hub, i)) { onOpen(i.identity) } } }
    }
}

// Look and language's themes, once one is installed: the same list, and a theme's page opens in Extensions with Back
// returning here. Nothing at all with none installed.
@Composable
internal fun ThemeChoice() {
    val ctx = LocalContext.current
    ExtUi.tick
    if (!ExtApp.dir(ctx).isDirectory) return
    val hub = remember { ExtApp.hub(ctx) }
    if (hub.registry.installed.none { it.manifest.isTheme }) return
    InfoHeader(T("Theme", "ערכת נושא"), T(
        "A theme changes how Kav looks, and can add settings and more of its own. Settings keeps Kav's own look, so no theme can hide or fake it.",
        "ערכת נושא משנה את המראה של Kav, ויכולה להוסיף הגדרות ועוד משלה. ההגדרות נשארות במראה של Kav, כך שאף ערכת נושא לא יכולה להסתיר אותן או לזייף אותן.",
    ))
    ThemeList(hub) { id -> ExtUi.returnTo = "look"; ExtUi.wanted = "e:$id"; SettingsNav.wanted = "extensions" }
    // The page's own parts follow at a section's distance.
    Spacer(Modifier.height(K.gap6))
}

// Settings › Extensions, Morphe's way: one card for each source, which opens to its extensions, each with its own switch;
// then the sources and the options.
@Composable
private fun MainExtensions(hub: Hub, open: (String) -> Unit, addSource: () -> Unit) {
    ExtUi.tick
    var about by remember { mutableStateOf(false) }
    var restart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
        SafeModeCard(hub) { restart = true }
        PlannerChoice(hub)
        if (hub.sources.isEmpty()) KavCard {
            Column(Modifier.fillMaxWidth().padding(vertical = K.gap4), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape).background(K.fill), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Extension, null, tint = K.textSecondary, modifier = Modifier.size(28.dp))
                }
                Text(T("No sources yet", "עוד אין מקורות"), style = K.type.headline, modifier = Modifier.padding(top = K.gap3))
                KavButton(T("Add a source", "הוספת מקור"), addSource, Modifier.padding(top = K.gap5), wide = true)
            }
        }
        // A bundle file of a source's own name (a test of its next release) stands in for that source while it has
        // nothing of its own yet, so the same name never shows twice.
        val shown = hub.sources.filter { s -> !(s.kind == Hub.Kind.GITHUB && s.bundle.isEmpty() && hub.sources.any { it.kind == Hub.Kind.FILE && it.name == s.name }) }
        // Which cards are open, by source key, kept while the rider goes into a page and back; the first card is open
        // to start with.
        val opened = ExtUi.opened ?: shown.firstOrNull()?.key.orEmpty()
        for (s in shown) key(s.key) {
            SourceCard(hub, s, opened.split('\n').contains(s.key),
                onToggle = { ExtUi.opened = opened.split('\n').filter { it.isNotEmpty() }.let { if (s.key in it) it - s.key else it + s.key }.joinToString("\n") },
                onSource = { open("s:${s.key}") }, onExtension = { open("e:$it") })
        }
        ListGroup(Modifier.padding(top = K.gap3)) {
            row {
                val failed = hub.sources.filter { it.error.isNotEmpty() && hub.blocked(it) == null }.map { iso(it.name) }
                val blocked = hub.sources.filter { hub.blocked(it) != null }.map { iso(it.name) }
                val trouble = listOfNotNull(
                    failed.takeIf { it.isNotEmpty() }?.let { n -> n.joinToString(", ") + if (n.size == 1) T(" failed to update", " לא התעדכן") else T(" failed to update", " לא התעדכנו") },
                    blocked.takeIf { it.isNotEmpty() }?.let { n -> n.joinToString(", ") + if (n.size == 1) T(" is blocked", " חסום") else T(" are blocked", " חסומים") },
                ).joinToString(" · ").ifEmpty { null }
                ListRow(T("Sources", "מקורות"),
                    subtitle = trouble, subtitleMaxLines = 2,
                    subtitleColor = if (trouble != null) K.problem else null,
                    leading = { Icon(Icons.Rounded.Storage, null, tint = K.dim) }, onClick = { open("sources") })
            }
            // The themes, apart from the extensions, once there is one: which is applied, and the list a tap away.
            if (hub.registry.installed.any { it.manifest.isTheme }) row {
                ListRow(T("Themes", "ערכות נושא"), subtitle = appliedName(hub),
                    leading = { Icon(Icons.Rounded.Palette, null, tint = K.dim) }, onClick = { open("themes") })
            }
        }
        // The switches, each a few words, in one group; what they do is behind the group's (i), as in the rest of Settings.
        val useList = hub.useBlockList
        val safe = SafeMode.why(hub.dir) != null
        Column {
            InfoHeader(T("Options", "אפשרויות"), T(
                "Security block list: Kav's signed list of sources and extensions found stealing, spreading malware, pretending to be someone else or abusing a bug in Kav. Security only.\n\n" +
                    "Safe mode: start Kav with every extension off.",
                "רשימת חסימה אבטחתית: רשימה חתומה של Kav, עם מקורות ותוספים שנתפסו בגניבה, בהפצת תוכנה זדונית, בהתחזות או בניצול באג ב-Kav. רק מטעמי אבטחה.\n\n" +
                    "מצב בטוח: הפעלת Kav כשכל התוספים כבויים.",
            ))
            ListGroup {
                row {
                    ToggleRow(T("Security block list", "רשימת חסימה אבטחתית"), useList,
                        { Icon(Icons.Rounded.Shield, null, tint = toggleInk(useList), modifier = Modifier.size(20.dp)) },
                    ) { hub.useBlockList = it; ExtUi.changed() }
                }
                row {
                    // It takes effect as Kav starts, so a change asks to restart Kav.
                    ToggleRow(T("Safe mode", "מצב בטוח"), safe,
                        { Icon(Icons.Rounded.PowerSettingsNew, null, tint = toggleInk(safe), modifier = Modifier.size(20.dp)) },
                    ) { SafeMode.set(hub.dir, it); ExtUi.changed(); restart = true }
                }
                // What third-party means, in full.
                row {
                    ListRow(T("About extensions", "על התוספים"), leading = { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Info, null, tint = K.dim, modifier = Modifier.size(20.dp)) } },
                        onClick = { about = true })
                }
            }
        }
    }
    if (about) ThirdPartyNotice({ about = false })
    if (restart) RestartPrompt { restart = false }
}

// One source, a card: its picture, name and status; tapped, it opens to its extensions, each with its own switch. The
// small button at its end opens the source's own screen.
@Composable
private fun SourceCard(hub: Hub, s: Hub.Source, open: Boolean, onToggle: () -> Unit, onSource: () -> Unit, onExtension: (String) -> Unit) {
    val (status, problem) = sourceStatus(hub, s)
    // Kav's icon only for Kav's own source, by its address: a file is never taken for it by its name.
    val kav = ExtApp.kavMade(s.key)
    // A card with nothing in it doesn't open.
    ExtCard(open && hub.extensions(s).isNotEmpty(), onToggle, { SourceAvatar(imageOf(hub, s.key), s.name, 40.dp, kav = kav) }, s.name, status, problem,
        menu = { KavIconButton(Icons.Rounded.MoreHoriz, T("About ${s.name}", "על ${s.name}"), onSource, size = 36.dp) }) {
        ExtSwitches(hub, s, onExtension)
    }
}

// A source's extensions, each with its switch; tapped, one opens its page.
@Composable
private fun ExtSwitches(hub: Hub, s: Hub.Source, onExtension: (String) -> Unit, themes: Boolean = true, extensions: Boolean = true) {
    val ctx = LocalContext.current
    val all = hub.extensions(s)
    // Nothing to list says nothing: the card's status line says why. Its themes come after its extensions, each with
    // Apply in place of a switch.
    if (extensions) all.filter { !it.manifest.isTheme }.forEach { i ->
        key(i.identity) {
            val trouble = troubleOf(hub, i)
            ExtSwitchRow(i.displayName, trouble ?: rowWords(i.manifest, if (hasSettings(i)) ROW_ROOM_WITH_SETTINGS else ROW_ROOM), trouble != null,
                { ExtTile(hub.registry.iconFile(i.identity), null, i.manifest.contributions.map { it.capability } + i.manifest.adds, 40.dp) },
                hub.active(i), false, { onExtension(i.identity) }, hasSettings(i)) { switchOn(ctx, hub, i.identity, it) }
        }
    }
    // Under the source page's own Themes header (no extensions beside them) a theme's row needn't say it's one.
    if (themes) for (i in all.filter { it.manifest.isTheme }) key(i.identity) {
        ThemeRow(hub, i, troubleOf(hub, i) ?: if (extensions) T("Theme", "ערכת נושא") else null) { onExtension(i.identity) }
    }
}

// A card that opens and closes in place, as Settings' parts do: its header always, what's inside unfolding under it.
@Composable
private fun ExtCard(
    open: Boolean, onToggle: () -> Unit, picture: @Composable () -> Unit, title: String, status: String?, problem: Boolean,
    menu: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onToggle)
                .padding(start = K.gap4, end = K.gap2, top = K.gap3, bottom = K.gap3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            picture()
            Column(Modifier.weight(1f)) {
                // A source's name is its publisher's: it wraps rather than being cut.
                Text(title, style = K.type.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                status?.let { Text(it, style = K.type.subhead, color = if (problem) K.problem else K.textSecondary) }
            }
            menu?.invoke()
        }
        androidx.compose.animation.AnimatedVisibility(open, enter = unfold, exit = fold) {
            Column(Modifier.fillMaxWidth()) {
                Separator(0.dp)
                content()
            }
        }
    }
}

// What's wrong with an installed extension, in a few words; Kav's reason when Kav switched it off.
private fun troubleOf(hub: Hub, i: Registry.Installed): String? = listOfNotNull(
    if (hub.blocked(i) != null) T("Blocked", "חסום") else null,
    if (i.identity in ExtUpdates.updating) T("Updating…", "מתעדכן…") else if (i.identity in ExtUpdates.outdated) T("Needs an update from its maker", "צריך עדכון מהיוצר שלו")
    else if (uk.noammm.kav.ext.MethodHooks.gaps.containsKey(i.identity)) T("Needs an update for this Kav", "צריך עדכון לגרסה הזו של Kav") else null,
    if (i.problem == NativeGuard.CRASHED) T("Made Kav close", "גרם ל-Kav להיסגר") else if (i.problem.isNotEmpty()) engineWords(i.problem).replaceFirstChar { it.uppercase() } else null,
).takeIf { it.isNotEmpty() }?.joinToString(" · ", transform = ::iso)

// Anything on its page's Settings card (ExtensionPage): what it adds (addsRows), its screens (extScreenRows), or settings
// of its own, ones its manifest declares or a settings view its code made.
private fun hasSettings(i: Registry.Installed): Boolean {
    val adds = i.manifest.contributions.any { c ->
        Slots.Kind.entries.any { it.capability == c.capability } || c.capability == Capabilities.PLAN || c.capability in Pipeline.HOOKS
    }
    val decl = runCatching { PageSpecs.of(i.manifest) }.getOrNull()
    val screens = decl != null && (decl.tabs.isNotEmpty() || decl.home || decl.pages.any { it.id != PageSpecs.HOME_PAGE && it.entry && !it.tab }) ||
        (uk.noammm.kav.ext.Screens.themeOf(i) != null && !i.manifest.isTheme)
    return adds || screens || decl?.settings?.isNotEmpty() == true ||
        (ExtNative.ready >= 0 && uk.noammm.kav.ext.Native.settingsScreen(i.identity) != null)
}

// A row of the cards: an extension's mark, name and what it adds (wrapped, so a description or a problem is never cut),
// a settings button while it's on and has settings (it opens the
// extension's page, as the row does), and its switch (a spinner while it's being put on).
@Composable
private fun ExtSwitchRow(
    name: String, line: String, problem: Boolean, tile: @Composable () -> Unit, on: Boolean, busy: Boolean,
    onOpen: (() -> Unit)?, settings: Boolean, onSwitch: (Boolean) -> Unit,
) {
    ListRow(name, subtitle = line, subtitleMaxLines = Int.MAX_VALUE, subtitleColor = if (problem) K.problem else null, leading = tile, onClick = onOpen ?: { onSwitch(!on) }, chevron = false,
        trailing = {
            androidx.compose.animation.AnimatedVisibility(settings && on && onOpen != null,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) + androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) + androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f))) {
                Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen?.invoke() },
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Settings, T("Settings", "הגדרות"), tint = K.dim) }
            }
            androidx.compose.animation.AnimatedContent(busy,
                transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 60)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) },
                label = "switchBusy") { b ->
                if (b) Box(Modifier.size(52.dp, 32.dp), contentAlignment = Alignment.Center) { Spinner(20.dp, track = K.fill, arc = K.accent) }
                else KavSwitch(on, Modifier.clip(K.capsule).clickable(role = androidx.compose.ui.semantics.Role.Switch,
                    onClickLabel = if (on) T("Turn off", "כיבוי") else T("Turn on", "הפעלה")) { onSwitch(!on) })
            }
        })
}

// Something that takes effect as Kav starts (safe mode): restart now, or later.
@Composable
private fun RestartPrompt(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Popup(onDismiss, {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            KavButton(T("Later", "אחר כך"), onDismiss, Modifier.weight(1f), style = ButtonStyle.Secondary)
            KavButton(T("Restart", "הפעלה מחדש"), { restartKav(ctx) }, Modifier.weight(1f))
        }
    }) {
        Text(T("Restart Kav for this to take effect", "יש להפעיל את Kav מחדש כדי שהשינוי ייכנס לתוקף"), style = K.type.title)
    }
}

// Kav started again at once: a fresh task, then this process ends, so everything that's read as Kav starts is read again.
internal fun restartKav(ctx: Context) {
    ctx.startActivity(Intent(ctx, uk.noammm.kav.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

// The sources, a screen of their own: one row each (its own screen a tap away). Adding one (a repository, a suggestion
// or a bundle file) and checking them all are the header's plus and refresh.
@Composable
private fun SourcesScreen(hub: Hub, open: (String) -> Unit) {
    ExtUi.tick
    val first = remember { hub.sources.map { it.key }.toSet() }
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
        if (hub.sources.isEmpty()) NoteCard(T("Add a source with the plus above.", "אפשר להוסיף מקור עם הפלוס למעלה."), Modifier.padding(top = K.gap3))
        else {
            // Worked out here, not inside the rows: ListGroup keeps a row while what it captured is the same, and the hub
            // is the same object after every change.
            val rows = hub.sources.map { s ->
                val (status, problem) = sourceStatus(hub, s)
                SourceRow(s.key, s.name, imageOf(hub, s.key), status, problem)
            }
            ListGroup(Modifier.padding(top = K.gap3).animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)), inset = K.gap4 + 40.dp + K.gap3) {
                for (r in rows) row {
                    key(r.key) {
                        Arriving(r.key in first) {
                            ListRow(r.name, subtitle = r.line, subtitleColor = if (r.problem) K.problem else null,
                                leading = { SourceAvatar(r.picture, r.name, 40.dp, kav = ExtApp.kavMade(r.key)) }, onClick = { open("s:${r.key}") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeModeCard(hub: Hub, onRestart: () -> Unit) {
    ExtUi.tick
    val why = SafeMode.why(hub.dir)
    // It eases in and out with Settings' motion, so nothing under it jumps.
    var kept by remember { mutableStateOf(why) }
    val shown = SafeMode.active || why != null
    if (shown) kept = why
    androidx.compose.animation.AnimatedVisibility(shown, enter = unfold, exit = fold) {
        KavCard {
            Text(T("Safe mode", "מצב בטוח"), style = K.type.body)
            Text(
                when {
                    !SafeMode.active -> T("Kav starts with extensions off next time.", "Kav תיפתח עם תוספים כבויים בפעם הבאה.")
                    kept == "crashes" -> T("Kav closed with an error several times, so Kav started with every extension off.",
                        "Kav נסגרה בשגיאה כמה פעמים, ולכן היא נפתחה כשכל התוספים כבויים.")
                    kept == null -> T("Extensions come back on the next time Kav opens.", "התוספים יחזרו לפעול בפעם הבאה ש-Kav תיפתח.")
                    else -> T("Kav started with every extension off, as you asked.", "Kav נפתחה כשכל התוספים כבויים, כפי שביקשתם.")
                },
                style = K.type.subhead,
            )
            if (kept != null) KavButton(T("Turn extensions back on", "הפעלת התוספים מחדש"), { SafeMode.set(hub.dir, false); ExtUi.changed(); onRestart() },
                Modifier.padding(top = K.gap3), style = ButtonStyle.Secondary)
        }
    }
}

// Each extension's planner the rider switched on, with a tab of its own on the results, and why it doesn't plan when it
// doesn't. Its tab never quietly turns into Kav's.
@Composable
private fun PlannerChoice(hub: Hub) {
    ExtUi.tick
    for (ns in Selections.planners) PlannerCard(hub, ns)
}

@Composable
private fun PlannerCard(hub: Hub, ns: Namespace) {
    val ctx = LocalContext.current
    val installed = hub.registry.installed.firstOrNull { it.namespace == ns }
    val name = installed?.displayName ?: T.ltr(ns.key)
    val why = when {
        SafeMode.active -> T("Off while Kav is in safe mode.", "כבוי כל עוד Kav במצב בטוח.")
        Extensions.registered(ns, Capabilities.PLAN) -> null
        installed == null -> T("Not installed. Searches say so instead of planning.", "לא מותקן. החיפושים יודיעו על כך במקום לתכנן.")
        hub.blocked(installed) != null -> T("Blocked by the security block list.", "חסום ברשימת החסימה האבטחתית.")
        !hub.active(installed) -> T("Turned off.", "כבוי.")
        else -> T("No planner Kav can run, so Kav doesn't ask this extension.", "אין כאן מתכנן ש-Kav יכולה להריץ, ולכן Kav לא פונה לתוסף הזה.")
    }
    KavCard {
        Text(T("Plans trips in its own tab: ${named(name)}", "מתכנן מסלולים בלשונית משלו: ${named(name)}"), style = K.type.body)
        Text(why ?: T("Ready.", "מוכן."), style = K.type.subhead, color = if (why == null) K.realtime else K.problem)
        KavButton(T("Remove its tab", "הסרת הלשונית שלו"), {
            Selections.setPlanner(ExtApp.dir(ctx), ns, false); ExtUi.changed()
        }, Modifier.padding(top = K.gap3), style = ButtonStyle.Secondary)
    }
}

// A page's header card: the centred header with even room round it, then its Details row under a hairline, full width.
@Composable
private fun HeaderCard(top: @Composable ColumnScope.() -> Unit, rows: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().panel(K.rCard)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = K.gap4, vertical = K.gap6), content = top)
        rows()
    }
}

@Composable
private fun SourcePage(hub: Hub, s: Hub.Source, open: (String) -> Unit, back: () -> Unit) {
    ExtUi.tick
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var removing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
        HeaderCard({
            CentredHeader({ SourceAvatar(imageOf(hub, s.key), s.name, 72.dp, kav = ExtApp.kavMade(s.key)) }, s.name, sourceStatus(hub, s).first, kav = ExtApp.kavMade(s.key))
            hub.blocked(s)?.let { FlagLine(T("Blocked: ", "חסום: ") + reasonWords(it.reason), Modifier.padding(top = K.gap4), tint = K.critical) }
            if (s.error.isNotEmpty()) FlagLine(T("Last check: ", "הבדיקה האחרונה: ") + engineWords(s.error), Modifier.padding(top = K.gap4))
        }) {
            // Its address and release, folded away.
            Details {
                if (s.kind == Hub.Kind.GITHUB) DetailFact(T("Address", "כתובת"), T.ltr(s.address))
                s.tag.takeIf { it.isNotEmpty() }?.let { DetailFact(T("Release", "גרסה"), T.ltr(it)) }
                s.updatedAt.takeIf { it > 0 }?.let { DetailFact(T("Last updated", "עודכן לאחרונה"), date(it)) }
            }
        }
        // What the rider came for, straight under the header: its extensions, then its themes.
        val all = hub.extensions(s)
        if (all.any { !it.manifest.isTheme } || all.isEmpty()) {
            SectionHeader(T("Extensions", "תוספים"))
            Column(Modifier.fillMaxWidth().panel(K.rCard)) { ExtSwitches(hub, s, { open("e:$it") }, themes = false) }
        }
        if (all.any { it.manifest.isTheme }) {
            SectionHeader(T("Themes", "ערכות נושא"))
            Column(Modifier.fillMaxWidth().panel(K.rCard)) { ExtSwitches(hub, s, { open("e:$it") }, extensions = false) }
        }
        ListGroup(Modifier.padding(top = K.gap3)) {
            s.page?.let { page ->
                row { ListRow(T("Open in browser", "פתיחה בדפדפן"), leading = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = K.dim) }, onClick = { openLink(ctx, page) }, chevron = false) }
            }
            s.issuesLink.takeIf { it.isNotEmpty() }?.let { link ->
                row { ListRow(T("Report an issue", "דיווח על בעיה"), leading = { Icon(Icons.Rounded.BugReport, null, tint = K.dim) }, onClick = { openLink(ctx, link) }, chevron = false) }
            }
            row { ListRow(T("Remove", "הסרה"), leading = { Icon(Icons.Rounded.Delete, null, tint = K.critical) }, titleColor = K.critical, onClick = { removing = true }, chevron = false) }
        }
    }
    if (removing) RemoveSourceDialog(hub, s, { removing = false }) {
        // Its extensions gone, a tab of theirs leaves the bar.
        scope.launch { io { hub.remove(s.key) }; ExtShown.publish(); io { ExtShown.refresh(ctx) }; ExtUi.changed(); back() }
    }
}

// Something about an extension that waits for the rider, with the one thing to do about it: a card of a mark, a line,
// a shorter one under it and a small button.
@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, line: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().panel(K.rCard).padding(start = K.gap4, end = K.gap3, top = K.gap3, bottom = K.gap3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Icon(icon, null, tint = K.problem, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = K.type.body)
            Text(line, style = K.type.subhead)
        }
        SmallButton(action, false, onAction)
    }
}

// A row's own small action (Install, Update): quiet, so a list of them doesn't shout, and still a button.
@Composable
private fun SmallButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 36.dp).clip(K.capsule).background(K.fill).clickable(enabled = !busy, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = K.gap4),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
    ) {
        if (busy) Spinner(14.dp, track = K.fill, arc = K.accentInk)
        Text(label, style = K.type.subhead, color = K.accentInk, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun ExtensionPage(hub: Hub, i: Registry.Installed) {
    ExtUi.tick
    val ctx = LocalContext.current
    val m = i.manifest
    val source = hub.source(i.source)
    val adds = addsRows(hub, i)
    val screens = extScreenRows(hub, i)
    Column(Modifier.fillMaxWidth().padding(horizontal = K.gap3)) {
        HeaderCard({
            CentredHeader({ ExtTile(hub.registry.iconFile(i.identity), null, if (m.isTheme) listOf(uk.noammm.kav.ext.Looks.CAPABILITY) else m.contributions.map { it.capability } + m.adds, 72.dp) }, i.displayName, fromWords(hub, i).ifEmpty { null },
                kav = ExtApp.kavMade(i.source), version = m.version)
            // Its publisher's own words for what it is, a clear gap under the name; a long one opens in full on a tap.
            if (m.description.isNotEmpty()) Description(iso(m.description.take(2000)), Modifier.padding(top = K.gap4))
            // What's wrong with it, a line each.
            hub.blocked(i)?.let { FlagLine(T("Blocked: ", "חסום: ") + reasonWords(it.reason), Modifier.padding(top = K.gap2), tint = K.critical) }
            // A theme is mostly a look: one that also brings code says so up front, as it can do anything an extension can.
            if (m.isTheme && uk.noammm.kav.ext.Native.declares(m)) FlagLine(T("Runs its own code, with full access", "מריצה קוד משלה, עם גישה מלאה"), Modifier.padding(top = K.gap2))
            if (i.problem.isNotEmpty() && i.problem != NativeGuard.CRASHED) FlagLine(engineWords(i.problem).replaceFirstChar { it.uppercase() }, Modifier.padding(top = K.gap2))
        }) {
            // The technical part, folded away: what its manifest says it can do, its files.
            Details {
                CanDo(ExtUses.of(m))
                DetailFact(T("Installed", "הותקן"), date(i.installedAt))
                KeyFact(T("Files, SHA-256", "הקבצים, SHA-256"), i.sha256)
            }
        }
        // Its switch, right under the header, so an extension that's off says so and turns on here. Not for one that
        // can't run whatever the switch says (blocked, or something wrong with it, which the header names).
        if (hub.blocked(i) == null && i.problem.isEmpty()) {
            if (m.isTheme) PowerCard(i.enabled, T("Use this theme", "שימוש בערכת הנושא")) { applyLook(ctx, hub, if (it) i.identity else null) }
            else PowerCard(i.enabled) { switchOn(ctx, hub, i.identity, it) }
        }
        // Kav turned it off itself, after its code made Kav close twice (NativeGuard): said here, with the way back.
        if (i.problem == NativeGuard.CRASHED) Box(Modifier.padding(top = K.gap3)) {
            Notice(Icons.Rounded.PauseCircle, T("Turned off by Kav", "‏Kav כיבתה את התוסף"),
                T("Made Kav close twice", "גרם ל-Kav להיסגר פעמיים"), T("Turn on", "הפעלה"),
            ) { switchOn(ctx, hub, i.identity, true) }
        }
        // One card for everything the rider sets: what it adds, its screens, then its own settings. Only while it's on: it
        // folds in as it's switched on and away as it's switched off (the values are kept).
        androidx.compose.animation.AnimatedVisibility(hub.active(i), enter = unfold, exit = fold) {
            Column(Modifier.fillMaxWidth()) { ExtSettingsForm(i.identity, T("Settings", "הגדרות")) { adds(); screens() } }
        }
        // The lines its code logged (Kav.log) and its failures: shown once there are any.
        ExtLog.init(hub.dir)
        val log = remember(i.identity, ExtUi.tick) { runCatching { ExtLog.read(i.identity) }.getOrDefault(emptyList()).takeLast(20).reversed() }
        val issues = m.issues.ifEmpty { source?.issuesLink.orEmpty() }
        if (log.isNotEmpty() || issues.isNotEmpty()) ListGroup(Modifier.padding(top = K.gap6)) {
            if (log.isNotEmpty()) row { LogPart(log) }
            if (issues.isNotEmpty()) row { ListRow(T("Report an issue", "דיווח על בעיה"), onClick = { openLink(ctx, issues) }, chevron = false) }
        }
    }
}

// An extension's own switch on its page, a card of its own: the power mark, whether it's on, and the switch, which the
// whole card flips. Off, a line under it says to turn it on, so a page with nothing else on it doesn't look broken.
// A theme's is "Use this theme", with its palette mark: on applies it (and takes any other theme off), off goes back to
// Kav's own look.
@Composable
private fun PowerCard(on: Boolean, theme: String? = null, onChange: (Boolean) -> Unit) {
    ListGroup(Modifier.padding(top = K.gap3).animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f))) {
        row {
            if (theme != null) ToggleRow(theme, on, { Icon(Icons.Rounded.Palette, null, tint = toggleInk(on), modifier = Modifier.size(20.dp)) }, onChange = onChange)
            else ToggleRow(if (on) T("On", "פועל") else T("Off", "כבוי"), on,
                { Icon(Icons.Rounded.PowerSettingsNew, null, tint = toggleInk(on), modifier = Modifier.size(20.dp)) },
                subtitle = if (on) null else T("Turn it on to use it", "הפעילו כדי להשתמש בו"), onChange = onChange)
        }
    }
}

// A publisher's description, in the middle: three lines, and a tap opens the rest in place (and closes it again). A
// small arrow under a cut one shows it opens, and turns as it does.
@Composable
private fun Description(text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    var cut by remember(text) { mutableStateOf(false) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "description")
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(K.rTile))
            .then(if (cut || open) Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button,
                onClickLabel = if (open) T("Show less", "הצגת פחות") else T("Show more", "הצגת עוד")) { open = !open } else Modifier)
            .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = K.type.subhead, color = K.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = if (open) Int.MAX_VALUE else 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, onTextLayout = { if (!open) cut = it.hasVisualOverflow })
        if (cut || open) Icon(Icons.Rounded.ExpandMore, null, tint = K.textTertiary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
    }
}

// What it adds to Kav, a switch each in plain words, as rows of its page's Settings card: its parts on Kav's screens,
// and the planner and route rules the rider chooses (Selections). A rule a search leans on has a second switch under it,
// for showing no routes when it fails rather than routes it didn't judge.
@Composable
private fun addsRows(hub: Hub, i: Registry.Installed): ListGroupScope.() -> Unit {
    ExtUi.tick
    val ctx = LocalContext.current
    val dir = ExtApp.dir(ctx)
    // sub: a switch that belongs to this one and shows only while subShown (it folds in and out under it, with its hairline).
    class Item(val title: String, val on: Boolean, val set: (Boolean) -> Unit) { var sub: Item? = null; var subShown = false }
    val items = ArrayList<Item>()
    // Its parts on Kav's screens (Slots), each on from the start.
    val caps = i.manifest.contributions.map { it.capability }.toSet()
    for ((k, words) in listOf(
        Slots.Kind.HOME to T("Home card", "כרטיס במסך הבית"), Slots.Kind.RESULTS to T("Line under each route", "שורה מתחת לכל מסלול"),
        Slots.Kind.TRIP to T("Part of a trip's page", "חלק בעמוד של נסיעה"), Slots.Kind.STOP to T("Row on stop boards", "שורה בלוחות תחנה"),
    )) if (k.capability in caps) items += Item(words, Slots.Picks.isOn(i.identity, k)) { v -> Slots.Picks.set(dir, i.identity, k, v); ExtShown.publish(); ExtUi.changed() }
    // The rider's trips (TripAccess): each off until the rider allows it here. Allowed during a trip, it's told that
    // trip at once.
    for ((cap, words) in listOf(
        uk.noammm.kav.ext.TripAccess.HISTORY to T("Read your trip history", "קריאת היסטוריית הנסיעות שלכם"),
        uk.noammm.kav.ext.TripAccess.FOLLOW to T("Follow your trips", "מעקב אחרי הנסיעות שלכם"),
    )) if (cap in caps) items += Item(words, uk.noammm.kav.ext.TripAccess.allows(i.identity, cap)) { v ->
        uk.noammm.kav.ext.TripAccess.set(dir, i.identity, cap, v)
        if (v && cap == uk.noammm.kav.ext.TripAccess.FOLLOW) Thread { runCatching { uk.noammm.kav.ext.TripEvents.catchUp(i.identity) } }.start()
        ExtUi.changed()
    }
    // The planner and route rules, each the rider's choice.
    val mine = i.manifest.contributions.filter { it.capability == Capabilities.PLAN || it.capability in Pipeline.HOOKS }
    val r = Selections.rules
    fun rule(c: Manifest.Declared, required: Boolean) = Selections.Rule(i.namespace, c.id, required)
    fun isMine(x: Selections.Rule?, c: Manifest.Declared) = x != null && x.namespace == i.namespace && x.contribution == c.id
    fun save(next: Selections.Rules) { Selections.chooseRules(dir, next); ExtUi.changed() }
    for (c in mine) {
        when (c.capability) {
            Capabilities.PLAN -> items += Item(T("Plan trips", "תכנון נסיעות"), i.namespace in Selections.planners) { v ->
                Selections.setPlanner(dir, i.namespace, v); ExtUi.changed() }
            Pipeline.WALK -> items += Item(T("Time walks", "חישוב זמני הליכה"), isMine(r.walk, c)) { v ->
                save(Selections.Rules(if (v) rule(c, true) else null, r.transfer, r.filters, r.rank, r.annotate)) }
            Pipeline.TRANSFER -> items += Item(T("Judge changes", "בדיקת החלפות"), isMine(r.transfer, c)) { v ->
                save(Selections.Rules(r.walk, if (v) rule(c, true) else null, r.filters, r.rank, r.annotate)) }
            Pipeline.FILTER -> items += Item(T("Filter routes", "סינון מסלולים"), r.filters.any { isMine(it, c) }) { v ->
                save(Selections.Rules(r.walk, r.transfer, r.filters.filterNot { isMine(it, c) } + listOfNotNull(if (v) rule(c, false) else null), r.rank, r.annotate)) }
            Pipeline.RANK -> items += Item(T("Order Recommended", "סידור \"מומלץ\""), isMine(r.rank, c)) { v ->
                save(Selections.Rules(r.walk, r.transfer, r.filters, if (v) rule(c, false) else null, r.annotate)) }
            Pipeline.ANNOTATE -> items += Item(T("Show tags on routes", "הצגת תגיות על מסלולים"), r.annotate.any { isMine(it, c) }) { v ->
                save(Selections.Rules(r.walk, r.transfer, r.filters, r.rank, r.annotate.filterNot { isMine(it, c) } + listOfNotNull(if (v) rule(c, false) else null))) }
        }
        val chosen = when (c.capability) {
            Pipeline.WALK -> r.walk?.takeIf { isMine(it, c) }
            Pipeline.TRANSFER -> r.transfer?.takeIf { isMine(it, c) }
            Pipeline.FILTER -> r.filters.firstOrNull { isMine(it, c) }
            else -> null
        }
        if (c.capability == Pipeline.WALK || c.capability == Pipeline.TRANSFER || c.capability == Pipeline.FILTER) items.lastOrNull()?.let { parent ->
          parent.subShown = chosen != null
          parent.sub = Item(T("Show no routes on a failure", "לא להציג מסלולים כשיש תקלה"), chosen?.required == true) { req ->
            if (chosen == null) return@Item
            val x = Selections.Rule(chosen.namespace, chosen.contribution, req)
            save(when (c.capability) {
                Pipeline.WALK -> Selections.Rules(x, r.transfer, r.filters, r.rank, r.annotate)
                Pipeline.TRANSFER -> Selections.Rules(r.walk, x, r.filters, r.rank, r.annotate)
                else -> Selections.Rules(r.walk, r.transfer, r.filters.map { if (isMine(it, c)) x else it }, r.rank, r.annotate)
            })
          }
        }
    }
    return {
        for (x in items) row {
            Column(Modifier.fillMaxWidth()) {
                SwitchRow(x.title, x.on, onChange = x.set)
                x.sub?.let { sub ->
                    androidx.compose.animation.AnimatedVisibility(x.subShown, enter = unfold, exit = fold) {
                        Column(Modifier.fillMaxWidth()) { Separator(); SwitchRow(sub.title, sub.on, onChange = sub.set) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Confirm(title: String, body: String, yes: String, dismiss: () -> Unit, onYes: () -> Unit) {
    Dialog(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(title, style = K.type.title)
            Text(body, style = K.type.subhead)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(T("Cancel", "ביטול"), dismiss, Modifier.weight(1f), style = ButtonStyle.Secondary)
                KavButton(yes, { dismiss(); onYes() }, Modifier.weight(1f), style = ButtonStyle.Destructive)
            }
        }
    }
}

// Its log, a row that opens: when, and the line its code wrote or which part of it failed.
@Composable
private fun LogPart(log: List<org.json.JSONObject>) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        ListRow(T("Log", "יומן"), onClick = { open = !open }, chevron = false,
            trailing = { Icon(Icons.Rounded.ExpandMore, null, tint = K.textTertiary, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = if (open) 180f else 0f }) })
        androidx.compose.animation.AnimatedVisibility(open, enter = unfold, exit = fold) {
            Column(Modifier.fillMaxWidth().padding(start = K.gap4, end = K.gap4, bottom = K.gap3), verticalArrangement = Arrangement.spacedBy(K.gap2)) {
                for (e in log) {
                    val what = if (e.has("log")) e.optString("log") else T("failed", "נכשל")
                    Text(parts(clock(e.optLong("t")), what), style = K.type.subhead)
                }
            }
        }
    }
}

private fun clock(s: Long): String = java.text.SimpleDateFormat("d MMM HH:mm:ss", T.locale).format(java.util.Date(s * 1000))

// Removing a source takes its extensions with it, and their settings.
@Composable
private fun RemoveSourceDialog(hub: Hub, s: Hub.Source, dismiss: () -> Unit, onRemove: () -> Unit) {
    val from = hub.extensions(s).size
    Dialog(onDismissRequest = dismiss) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap3),
        ) {
            Text(T("Remove ${named(s.name)}?", "להסיר את ${named(s.name)}?"), style = K.type.title)
            if (from > 0) Text(if (from == 1) T("The extension from this source goes too, settings included.", "גם התוסף מהמקור הזה יוסר, כולל ההגדרות.")
                else T("The $from extensions from this source go too, settings included.", "גם $from התוספים מהמקור הזה יוסרו, כולל ההגדרות."), style = K.type.subhead)
            KavButton(T("Remove", "הסרה"), { dismiss(); onRemove() }, Modifier.fillMaxWidth(), style = ButtonStyle.Destructive)
            KavButton(T("Cancel", "ביטול"), dismiss, Modifier.fillMaxWidth(), style = ButtonStyle.Plain)
        }
    }
}

// Kav's bottom sheet in a window of its own: the sheets here open from inside Settings' scrolling page, where a sheet
// drawn in place would have no height to fill. scrolls: the whole sheet scrolls; off for one that keeps its buttons in
// view and scrolls what's above them itself.
@Composable
internal fun Sheet(onDismiss: () -> Unit, scrolls: Boolean = true, content: @Composable ColumnScope.(close: (then: () -> Unit) -> Unit) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) { BottomSheet(onDismiss, scrolls = scrolls, content = content) }
    }
}

// Add a source: one or more GitHub repositories pasted or typed, one of the community list's first few (Community), or a
// bundle file from the phone. Each is read and shown before it's added.
@Composable
private fun AddSourceSheet(hub: Hub, onFile: () -> Unit, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    // The repositories whose add popup is still to come, the first one showing.
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    val parts = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    val parsed = parts.map { it to GitHub.repoOf(it) }
    val bad = parsed.firstOrNull { it.second == null }?.first
    // The kept list at once, then a fresh one when the kept copy is a day old; nothing at all when there's none.
    var listed by remember { mutableStateOf<List<uk.noammm.kav.ext.Community.Suggestion>>(emptyList()) }
    LaunchedEffect(Unit) {
        listed = io { uk.noammm.kav.ext.Community.cached(hub.dir) }.getOrDefault(emptyList())
        io { uk.noammm.kav.ext.Community.refresh(hub.dir, ExtApp.Http) }.onSuccess { listed = it }
    }
    ExtUi.tick
    val suggested = uk.noammm.kav.ext.Community.suggest(listed, hub.sources.map { it.key }.toSet())
    Sheet(onClose) { close ->
        Column(Modifier.fillMaxWidth().padding(K.gap4), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
            SheetHeader(T("Add a source", "הוספת מקור"), { close(onClose) })
            KavField(text, { text = it }, "github.com/owner/repo", Modifier.fillMaxWidth(), keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
            if (bad != null) Text(T("Not a GitHub repository: ", "לא מאגר GitHub: ") + T.ltr(bad.take(60)), style = K.type.subhead, color = K.problem)
            else Text(T("A GitHub repository whose releases carry a Kav bundle.", "מאגר GitHub עם גרסאות שכוללות חבילה של Kav."), style = K.type.subhead, color = K.textTertiary)
            KavButton(T("Add", "הוספה"), {
                queue = parsed.mapNotNull { it.second }
            }, Modifier.fillMaxWidth(), enabled = parts.isNotEmpty() && bad == null && queue.isEmpty())
            // Sources from the community list, the first few not added yet, each a tap from its add popup. They unfold
            // when the list arrives, and fold away once added.
            androidx.compose.animation.AnimatedVisibility(suggested.isNotEmpty(), enter = unfold, exit = fold) {
                Column(Modifier.fillMaxWidth()) {
                    SectionHeader(T("From the community list", "מרשימת הקהילה"))
                    ListGroup(Modifier.animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)), inset = K.gap4 + 40.dp + K.gap3) {
                        for (x in suggested) row {
                            key(x.key) {
                                ListRow(x.name, subtitle = T.ltr("github.com/${x.repo}"), subtitleMaxLines = Int.MAX_VALUE,
                                    leading = { SourceAvatar(uk.noammm.kav.ext.Community.picture(hub.dir, x), x.name, 40.dp, kav = ExtApp.kavMade(x.key)) },
                                    onClick = { if (queue.isEmpty()) queue = listOf(x.repo) }, chevron = false,
                                    trailing = { Icon(Icons.Rounded.Add, T("Add", "הוספה"), tint = K.accentInk, modifier = Modifier.size(20.dp)) })
                            }
                        }
                    }
                }
            }
            ListGroup(Modifier.padding(top = K.gap3)) {
                row {
                    ListRow(T("Add a bundle file", "הוספת קובץ חבילה"), leading = { Icon(Icons.Rounded.FolderOpen, null, tint = K.dim) },
                        onClick = { close { onClose(); onFile() } }, chevron = false)
                }
            }
        }
    }
    queue.firstOrNull()?.let { repo ->
        key(repo) {
            AddSourcePopup(hub, repo, null) { added ->
                queue = queue.drop(1)
                // Added from the field, the sheet's work is done; a suggestion added leaves the others to pick from.
                if (added && queue.isEmpty() && text.isNotBlank()) onClose()
            }
        }
    }
}

// Adding one source, from Add, a link or a bundle file (repo, or file: its name and bytes): a popup that opens at once,
// reads the source while it shows, and then asks the rider, with the source's picture and name on top, one line saying
// it isn't Kav's (or that it's Kav's own), and each of its extensions with what it can do. Nothing is added until the
// rider taps Add; its extensions come in off. done: whether it was added.
@Composable
internal fun AddSourcePopup(hub: Hub?, repo: String?, file: Pair<String, ByteArray>?, done: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var h by remember { mutableStateOf(hub) }
    var p by remember { mutableStateOf<Hub.Preview?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val ready = h ?: withContext(Dispatchers.IO) { ExtApp.hub(ctx) }.also { watchImages(it); h = it }
        io { if (repo != null) ready.preview(repo) else ready.previewFile(file!!.first, file.second) }.onSuccess { p = it }.onFailure { failed = failure(it) }
    }
    val ready = p
    val title = ready?.source?.name ?: if (failed != null) (repo ?: file?.first.orEmpty()) else T("Checking…", "בודקים…")
    val canAdd = ready != null && ready.blocked == null && !ready.already
    Popup({ done(false) }, {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            if (!canAdd) KavButton(T("Close", "סגירה"), { done(false) }, Modifier.weight(1f), style = ButtonStyle.Secondary)
            else {
                KavButton(T("Cancel", "ביטול"), { done(false) }, Modifier.weight(1f), style = ButtonStyle.Secondary)
                KavButton(T("Add", "הוספה"), {
                    busy = true
                    scope.launch {
                        io { h!!.add(ready!!) }.onSuccess { ExtUi.changed(); done(true) }.onFailure { toast(ctx, failure(it)) }
                        busy = false
                    }
                }, Modifier.weight(1f), busy = busy, enabled = !busy)
            }
        }
    }) {
        CentredHeader(
            {
                Box(contentAlignment = Alignment.Center) {
                    // The picture its card will show: Kav's icon for Kav's own source, else the one its bundle brings
                    // (read with the bundle, no request of its own).
                    SourceAvatar(imageOf(h, ready?.source?.key), title, 72.dp, kav = ready?.let { ExtApp.kavMade(it.source.key) } == true, bytes = ready?.bundle?.icon)
                    if (p == null && failed == null) Spinner(80.dp, track = androidx.compose.ui.graphics.Color.Transparent, arc = K.accent)
                }
            },
            title,
            // A file source's name is the bundle's own: under it, only where it came from, and whose it is.
            if (file != null) T("A bundle file", "קובץ חבילה") else ready?.source?.address?.let { iso(T.ltr(it)) } ?: repo?.let { iso(T.ltr("github.com/$it")) },
            kav = ready?.let { ExtApp.kavMade(it.source.key) },
        )
        when {
            failed != null -> Text(T("Kav couldn't read this source. ", "\u200fKav לא הצליחה לקרוא את המקור הזה. ") + failed, style = K.type.subhead, color = K.critical)
            ready == null -> {}
            ready.blocked != null -> Text(T("Kav's security block list names this source (", "רשימת החסימה האבטחתית של Kav כוללת את המקור הזה (") + reasonWords(ready.blocked.reason) + T("). This source can't be added.", "). אי אפשר להוסיף את המקור הזה."),
                style = K.type.subhead, color = K.critical)
            ready.already -> Text(T("You've already added this source.", "כבר הוספתם את המקור הזה."), style = K.type.subhead, color = K.problem,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth())
            else -> {}
        }
    }
}

// A picked file's own name, for a bundle with no name of its own.
private fun fileName(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()

private fun readFile(ctx: Context, uri: Uri): ByteArray {
    val max = uk.noammm.kav.ext.Archive.LIMIT_ARCHIVE
    ctx.contentResolver.openInputStream(uri).use { s ->
        s ?: throw java.io.IOException("couldn't open the file")
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 shl 10)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw TrustError("too large")
        }
        return out.toByteArray()
    }
}

// What PendingExtension holds, as Kav opens: the add popup for a link or a bundle file (straight away, reading it while
// it shows).
@Composable
internal fun ExtensionPrompt() {
    val ctx = LocalContext.current
    val link = PendingExtension.link
    val file = PendingExtension.file
    var picked by remember(file) { mutableStateOf<Pair<String, ByteArray>?>(null) }
    LaunchedEffect(file) {
        if (file == null) return@LaunchedEffect
        io { fileName(ctx, file) to readFile(ctx, file) }.onSuccess { picked = it }.onFailure { toast(ctx, failure(it)); PendingExtension.file = null }
    }
    if (link != null) key(link) { AddSourcePopup(null, link, null) { PendingExtension.link = null } }
    picked?.let { f -> key(f) { AddSourcePopup(null, null, f) { PendingExtension.file = null } } }
}

// Morphe's "Blocked patch source detected": once the block list names something the rider has, Kav says so as it opens,
// until that list has been seen.
internal object BlockNotice {
    // What's blocked now, from the phone alone, each with whether it's a source (true) or an extension; empty when nothing
    // is or the notice for this list was seen.
    fun names(ctx: Context): List<Pair<String, Boolean>> {
        if (!ExtApp.dir(ctx).isDirectory) return emptyList()
        val h = ExtApp.hub(ctx)
        if (!h.useBlockList || h.blockList.sequence < 0) return emptyList()
        val seen = runCatching { java.io.File(h.dir, "block-notice").readText().trim().toLong() }.getOrDefault(-1L)
        if (seen >= h.blockList.sequence) return emptyList()
        return h.sources.filter { h.blocked(it) != null }.map { it.name to true }
    }

    fun seen(ctx: Context) {
        val h = ExtApp.hub(ctx)
        runCatching { java.io.File(h.dir, "block-notice").writeText(h.blockList.sequence.toString()) }
    }
}

@Composable
// A blocked source stays in the list but Kav no longer checks it or installs from it, and what came from it is off
// (Hub.check, Hub.active); a blocked extension is off. Worded for one or several, and for sources or extensions.
internal fun BlockedPrompt(items: List<Pair<String, Boolean>>, onLater: () -> Unit, onReview: () -> Unit) {
    val names = items.map { it.first }
    val source = items.any { it.second }
    val one = items.size == 1
    Dialog(onDismissRequest = onLater) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = Arrangement.spacedBy(K.gap4),
        ) {
            Text(if (source) T("Blocked extension source detected", "זוהה מקור תוספים חסום") else T("Blocked extension detected", "זוהה תוסף חסום"), style = K.type.title)
            Text(
                T("Kav's security block list names ", "רשימת החסימה האבטחתית של Kav כוללת את ") + names.joinToString(", ", transform = ::named) + when {
                    source && one -> T(". Kav no longer uses this source, and what you installed from the source is off. Tap Review to see why and remove the source.",
                        ". \u200fKav כבר לא משתמשת במקור הזה, ומה שהתקנתם מהמקור כבוי. הקישו על \"סקירה\" כדי לראות למה ולהסיר את המקור.")
                    source -> T(". Kav no longer uses them, and what you installed from them is off. Tap Review to see why and remove them.",
                        ". \u200fKav כבר לא משתמשת בהם, ומה שהתקנתם מהם כבוי. הקישו על \"סקירה\" כדי לראות למה ולהסיר אותם.")
                    one -> T(". The extension is off now. Tap Review to see why and remove the extension.", ". התוסף כבוי עכשיו. הקישו על \"סקירה\" כדי לראות למה ולהסיר את התוסף.")
                    else -> T(". They're off now. Tap Review to see why and remove them.", ". הם כבויים עכשיו. הקישו על \"סקירה\" כדי לראות למה ולהסיר אותם.")
                },
                style = K.type.subhead,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                KavButton(T("Later", "אחר כך"), onLater, Modifier.weight(1f), style = ButtonStyle.Secondary)
                KavButton(T("Review", "סקירה"), onReview, Modifier.weight(1f))
            }
        }
    }
}

// Extensions the crash guard switched off as Kav opened (NativeGuard.check): (identity, name), said once in a popup.
internal object ExtCrashes {
    var items by mutableStateOf<List<Pair<String, String>>>(emptyList())
}

// "<name> was turned off: it made Kav close", a line for each; Open goes to the extension's page (the first one's).
@Composable
internal fun CrashedPrompt(items: List<Pair<String, String>>, onOk: () -> Unit, onOpen: (String) -> Unit) {
    Popup(onOk, {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
            KavButton(T("OK", "אישור"), onOk, Modifier.weight(1f), style = ButtonStyle.Secondary)
            KavButton(T("Open", "פתיחה"), { onOpen(items.first().first) }, Modifier.weight(1f))
        }
    }) {
        for ((_, name) in items) Text(
            T("${iso(name)} was turned off after making Kav close", "${iso(name)} כובה אחרי שגרם ל-Kav להיסגר"),
            style = K.type.headline,
        )
    }
}
