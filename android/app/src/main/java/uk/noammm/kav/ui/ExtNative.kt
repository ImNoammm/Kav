package uk.noammm.kav.ui

import android.app.Dialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.json.JSONObject
import uk.noammm.kav.KavModel
import kav.kavapp.api.KavUi
import uk.noammm.kav.ext.Blocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import uk.noammm.kav.ext.ExtApp
import uk.noammm.kav.ext.HomeBody
import uk.noammm.kav.ext.Native
import uk.noammm.kav.ext.PageSpecs

// Native extensions on Kav's screens (ext/Native.kt): their cards and screens, as the Android views their code makes,
// hosted in Kav's own; and Kav's look for them (KavUi): Kav's own components, drawn by Kav in views, so they follow
// Kav's theme, dark mode, direction and text size by themselves.
object ExtNative {
    // The model Kav's screens share, for a block tree's actions (plan to a place, open a trip).
    @Volatile internal var model: KavModel? = null

    fun start() { Native.ui = { identity -> Look(identity) } }

    // Raised once the native extensions have started, for screens that waited for their code (a Home, a tab).
    var ready by mutableIntStateOf(0)
        private set

    fun started() { android.os.Handler(android.os.Looper.getMainLooper()).post { ready++ } }

    private class Look(private val identity: String) : KavUi {
        private fun compose(c: Context, content: @Composable () -> Unit): View = ComposeView(c).apply { setContent(content) }

        override fun button(c: Context, label: String, primary: Boolean, onClick: Runnable) =
            compose(c) { KavButton(label, { onClick.run() }, style = if (primary) ButtonStyle.Primary else ButtonStyle.Secondary) }

        override fun card(c: Context, content: View): View = compose(c) {
            Box(Modifier.fillMaxWidth().panel().padding(K.gap4)) { AndroidView({ (content.parent as? ViewGroup)?.removeView(content); content }) }
        }

        override fun row(c: Context, icon: String?, title: String, subtitle: String?, onClick: Runnable?) = compose(c) {
            ListRow(title, subtitle = subtitle, onClick = onClick?.let { r -> { r.run() } },
                leading = iconOf(icon)?.let { v -> { Icon(v, null, tint = K.dim, modifier = Modifier.size(20.dp)) } })
        }

        override fun switchRow(c: Context, title: String, subtitle: String?, on: Boolean, onChange: KavUi.Changed<Boolean>) = compose(c) {
            var v by remember { mutableStateOf(on) }
            ListRow(title, subtitle = subtitle, trailing = { KavSwitch(v) }, onClick = { v = !v; onChange.changed(v) }, chevron = false)
        }

        override fun textField(c: Context, value: String, placeholder: String, onChange: KavUi.Changed<String>) = compose(c) {
            var v by remember { mutableStateOf(value) }
            KavField(v, { v = it; onChange.changed(it) }, placeholder)
        }

        override fun textField(c: Context, value: String, placeholder: String, multiline: Boolean, action: String?, onChange: KavUi.Changed<String>): View {
          val field = ComposeView(c).also { fields += it }
          // Next: on to the next of Kav's text fields in the extension's own layout (each a view of its own, which
          // Compose's own focus can't leave), never out of it.
          val onNext: (() -> Unit)? = if (action == "next" && !multiline) ({ nextField(field)?.requestFocus() }) else null
          return field.apply { setContent {
            var v by remember { mutableStateOf(value) }
            KavField(v, { v = it; onChange.changed(it) }, placeholder, multiline = multiline, onIme = onNext, ime = when (action) {
                "next" -> androidx.compose.ui.text.input.ImeAction.Next
                "search" -> androidx.compose.ui.text.input.ImeAction.Search
                "go" -> androidx.compose.ui.text.input.ImeAction.Go
                else -> androidx.compose.ui.text.input.ImeAction.Done
            })
          } }
        }

        // The text fields made here, to find the next one after a field's Next.
        private val fields = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

        private fun nextField(from: View): View? {
            fun find(v: View): View? = if (v in fields) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            var child = from
            var parent = from.parent as? ViewGroup
            while (parent != null) {
                for (j in parent.indexOfChild(child) + 1 until parent.childCount) find(parent.getChildAt(j))?.let { return it }
                child = parent
                parent = parent.parent as? ViewGroup
            }
            return null
        }

        override fun iconButton(c: Context, icon: String?, label: String, onClick: Runnable) =
            compose(c) { KavIconButton(iconOf(icon) ?: iconOf("info")!!, label, { onClick.run() }) }

        // One view at a time, each in from the side with Settings' own motion (forward, backward).
        override fun pages(c: Context, first: View): KavUi.Pages {
            val shown = mutableStateOf(first to true)
            val frame = compose(c) {
                androidx.compose.animation.AnimatedContent(shown.value, Modifier.fillMaxSize(),
                    transitionSpec = { if (targetState.second) forward() else backward() }, label = "extPages") { (v, _) ->
                    AndroidView({ (v.parent as? ViewGroup)?.removeView(v); v }, Modifier.fillMaxSize())
                }
            }
            return object : KavUi.Pages {
                override fun view() = frame
                override fun forward(next: View) { shown.value = next to true }
                override fun back(previous: View) { shown.value = previous to false }
            }
        }

        override fun chip(c: Context, label: String, selected: Boolean, onClick: Runnable) =
            compose(c) { KavChip(label, selected, { onClick.run() }) }

        override fun header(c: Context, text: String) = compose(c) { SectionHeader(text) }

        override fun sheet(c: Context, title: String, content: View) {
            window(c) { done ->
                BottomSheet(onDismiss = done) {
                    Text(title, style = K.type.title, modifier = Modifier.padding(horizontal = K.gap4, vertical = K.gap3))
                    AndroidView({ (content.parent as? ViewGroup)?.removeView(content); content }, Modifier.fillMaxWidth().padding(horizontal = K.gap4))
                    Spacer(Modifier.height(K.gap6))
                }
            }
        }

        override fun dialog(c: Context, title: String, text: String, confirm: String, onConfirm: Runnable) {
            window(c) { done ->
                androidx.compose.ui.window.Dialog(onDismissRequest = done) {
                    Column(Modifier.fillMaxWidth().panel(K.rCard, solid = true).padding(K.gap5), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                        Text(title, style = K.type.title)
                        Text(text, style = K.type.body)
                        Row(horizontalArrangement = Arrangement.spacedBy(K.gap2)) {
                            KavButton(T("Cancel", "ביטול"), done, Modifier.weight(1f), style = ButtonStyle.Secondary)
                            KavButton(confirm, { done(); onConfirm.run() }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // A sheet or dialog of Kav's over the screen: a window of its own with Kav's look in it.
        private fun window(c: Context, content: @Composable (done: () -> Unit) -> Unit) {
            val a = c as? ComponentActivity ?: throw IllegalArgumentException("a sheet or dialog needs the screen's context")
            val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar)
            val v = ComposeView(a).apply { setContent { content { d.dismiss() } } }
            d.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            d.window?.decorView?.let { w -> w.setViewTreeLifecycleOwner(a); w.setViewTreeViewModelStoreOwner(a); w.setViewTreeSavedStateRegistryOwner(a) }
            d.show()
        }

        override fun blocks(c: Context, page: String): View {
            val p = Blocks.read(page)
            return compose(c) {
                val m = model ?: return@compose
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
                    if (p.title.isNotEmpty()) Text(p.title, style = K.type.title)
                    for (s in p.sections) {
                        s.title?.let { SectionHeader(it) }
                        SlotBlocks(s.blocks, m, identity, "")
                    }
                }
            }
        }

        override fun color(role: String): Int = when (role) {
            "bg" -> K.bg; "surface" -> K.surface; "surfaceRaised" -> K.surfaceRaised; "fill" -> K.fill; "fillStrong" -> K.fillStrong
            "separator" -> K.separator; "text" -> K.text; "textSecondary" -> K.textSecondary; "textTertiary" -> K.textTertiary
            "surface2" -> K.surface2; "surface3" -> K.surface3; "surface4" -> K.surface4; "borderStrong" -> K.borderStrong
            "sunken" -> K.sunken; "routeIdle" -> K.routeIdle; "problem" -> K.problem; "critical" -> K.critical
            "realtime" -> K.realtime; "accent" -> K.accent
            else -> null
        }?.toArgb() ?: 0

        override fun dp(name: String): Float = when (name) {
            "rCard" -> K.rCard; "rSheet" -> K.rSheet; "rTile" -> K.rTile; "rBadge" -> K.rBadge
            "gap1" -> K.gap1; "gap2" -> K.gap2; "gap3" -> K.gap3; "gap4" -> K.gap4; "gap5" -> K.gap5; "gap6" -> K.gap6; "gap8" -> K.gap8
            else -> null
        }?.value ?: 0f

        override fun textSize(style: String): Float = when (style) {
            "largeTitle" -> K.type.largeTitle; "title" -> K.type.title; "headline" -> K.type.headline; "body" -> K.type.body
            "subhead" -> K.type.subhead; "caption" -> K.type.caption
            else -> null
        }?.fontSize?.value ?: 0f
    }
}

// A native extension's card on one of Kav's screens: the view its code makes for what the screen shows. Nothing when
// its code fails (the crash guard counts it).
@Composable
internal fun NativeCard(model: KavModel, identity: String, on: String, about: JSONObject, top: Dp) {
    ExtNative.model = model
    ExtNative.ready
    val card = Native.card(identity, on) ?: return
    val text = remember(about) { about.toString() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val name = remember(identity) { uk.noammm.kav.ext.ExtApp.hub(ctx).registry.get(identity)?.displayName.orEmpty() }
    val view: @Composable () -> Unit = {
        key(card) { AndroidView(factory = { c -> Native.view(identity, "card $on") { card.create(c, text) } ?: FrameLayout(c) }, modifier = Modifier.fillMaxWidth()) }
    }
    // On Home, one card as Kav's own are: its name as the card's small title, inside it, then what its code made, lying
    // flat in the card.
    if (on == uk.noammm.kav.ext.Slots.Kind.HOME.key) androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(top = top).panel(K.rCard)) {
        if (name.isNotEmpty()) Text(name, style = K.type.caption, color = K.textSecondary,
            modifier = Modifier.padding(start = K.gap4, end = K.gap4, top = K.gap4, bottom = K.gap1))
        androidx.compose.runtime.CompositionLocalProvider(LocalInCard provides true) { view() }
    }
    else Column(Modifier.fillMaxWidth().padding(top = top)) { view() }
}

// A results card's line under one route of a search's results: the view its code makes for that route,
// or nothing when it makes none (or failed: the crash guard counts it).
@Composable
internal fun ResultLine(model: KavModel, identity: String, about: JSONObject) {
    if (uk.noammm.kav.ext.SafeMode.active || ExtNative.ready < 0 || ExtUi.tick < 0 || !Native.isLoaded(identity)) return
    ExtNative.model = model
    val card = Native.card(identity, "results") ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = remember(identity, about) { Native.view(identity, "card results") { card.create(ctx, about.toString()) } } ?: return
    AndroidView(factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxWidth().padding(top = K.gap1))
}

// Whether the chosen Home is a native extension's: then its view once its code has started (Ready), Kav's Home while it
// starts late (HomeBody.BUDGET_MS), and a failure when its code gave none.
internal suspend fun nativeHome(h: ExtHome, app: Context): Boolean {
    if (Native.screen(h.identity, PageSpecs.HOME_PAGE) != null) {
        if (!(h.state is HomeBody.State.Late && h.touched)) h.state = HomeBody.State.Ready
        return true
    }
    val native = withContext(Dispatchers.IO) { runCatching { ExtApp.hub(app).registry.get(h.identity)?.manifest?.let(Native::declares) == true }.getOrDefault(false) }
    if (!native) return false
    if (ExtNative.ready > 0) { h.state = HomeBody.State.Failed("no Home from the extension's code"); return true }
    h.state = HomeBody.State.Waiting
    delay(HomeBody.BUDGET_MS)
    if (h.state is HomeBody.State.Waiting) h.state = HomeBody.State.Late
    return true
}

// A native extension's Home body: the view its code makes. Nothing when its code fails (the crash guard counts it).
@Composable
internal fun NativeHome(model: KavModel, identity: String) {
    ExtNative.model = model
    ExtNative.ready
    val screen = Native.screen(identity, PageSpecs.HOME_PAGE) ?: return
    key(screen) {
        AndroidView(factory = { c -> Native.view(identity, "home") { screen.create(c, "{}") } ?: FrameLayout(c) },
            modifier = Modifier.fillMaxWidth().padding(top = K.gap4))
    }
}

// A native extension's screen, under Kav's header.
@Composable
internal fun NativeScreen(model: KavModel, identity: String, title: String, page: String, args: Map<String, String>, onBack: (() -> Unit)?) {
    ExtNative.model = model
    // Read so a run started again (switched on, updated) draws its own view: each run's screen makes a new one.
    ExtNative.ready
    val screen = Native.screen(identity, page) ?: return
    Column(Modifier.fillMaxSize().background(K.bg)) {
        ScreenHeader(title, back = onBack, large = onBack == null)
        key(screen) {
            AndroidView(factory = { c -> Native.view(identity, "screen $page") { screen.create(c, JSONObject(args).toString()) } ?: FrameLayout(c) },
                modifier = Modifier.fillMaxSize().padding(horizontal = K.gap4))
        }
    }
}
