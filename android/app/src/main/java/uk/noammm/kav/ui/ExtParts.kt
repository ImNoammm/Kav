package uk.noammm.kav.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import uk.noammm.kav.ext.Native
import uk.noammm.kav.ext.Parts
import uk.noammm.kav.ext.SafeMode

// One of Kav's screen parts by name (ext/Parts.kt): the views extensions add before and after it, and its replacement
// when one gives a view. With none, the part is drawn as it always is, and about is never built. of: what the part
// shows, so its views are made again only when that changes.
@Composable
internal fun KavPart(name: String, of: Any?, about: () -> JSONObject, content: @Composable () -> Unit) {
    val part = rememberPart(name, of, about)
    if (part == null) { content(); return }
    Column(Modifier.fillMaxWidth()) {
        for ((p, v) in part.before) PartView(p, v)
        part.replaced?.let { (p, v) -> PartView(p, v) } ?: content()
        for ((p, v) in part.after) PartView(p, v)
    }
}

// The views of a part made for what it shows, in order; null when no extension adds to it.
internal class PartViews(
    val before: List<Pair<Parts.Piece, android.view.View>>,
    val replaced: Pair<Parts.Piece, android.view.View>?,
    val after: List<Pair<Parts.Piece, android.view.View>>,
)

@Composable
internal fun rememberPart(name: String, of: Any?, about: () -> JSONObject): PartViews? {
    // ExtNative.ready rises when an extension's code has started, ExtUi.tick when one is switched off or removed.
    val pieces = if (SafeMode.active || ExtNative.ready < 0 || ExtUi.tick < 0) emptyList() else Native.partsOf(name)
    if (pieces.isEmpty()) return null
    val ctx = LocalContext.current
    // Each view made once for what the part shows; a replacement that makes none gives way to the next, then Kav's own.
    return remember(pieces, of) {
        val text = runCatching { about().toString() }.getOrDefault("{}")
        val made = pieces.associateWith { p -> Native.view(p.identity, "part $name ${p.where}") { p.card.create(ctx, text) } }
        fun views(list: List<Parts.Piece>) = list.mapNotNull { p -> made[p]?.let { p to it } }
        PartViews(views(Parts.around(pieces, "before")), views(Parts.replacing(pieces)).firstOrNull(), views(Parts.around(pieces, "after")))
    }
}

// A part that is a list's rows (a stop's board): its views as rows of their own, before and after Kav's, and Kav's rows
// only when none replaces them.
internal fun LazyListScope.partRows(part: PartViews?, where: String) {
    val rows = when (where) { "before" -> part?.before; "after" -> part?.after; else -> listOfNotNull(part?.replaced) } ?: return
    for ((p, v) in rows) item(key = "part-${p.identity}-$where") { PartView(p, v) }
}

@Composable
private fun PartView(p: Parts.Piece, view: android.view.View) {
    key(p.identity, p.where, view) {
        AndroidView(factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxWidth())
    }
}
