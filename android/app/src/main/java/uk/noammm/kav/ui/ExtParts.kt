package uk.noammm.kav.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import uk.noammm.kav.ext.Native
import uk.noammm.kav.ext.Parts
import uk.noammm.kav.ext.SafeMode

// One of Kav's screen parts by name (ext/Parts.kt): the views extensions add before and after it, and its replacement
// when one gives a view. With none, the part is drawn as it always is, and about is never built. of: what the part
// shows, so its views are made again only when that changes. The views sit as far from Kav's own as its cards do from
// each other, and inset as far as the part's cards are; ownGap: the part's content keeps the space above itself (Home's
// cards), so each view keeps the same space above it. surface: what each view sits on, inside its inset (navigation's
// banner, over the map, is on glass as the step dots are).
@Composable
internal fun KavPart(name: String, of: Any?, about: () -> JSONObject, inset: Dp = 0.dp, ownGap: Boolean = false,
    surface: Modifier = Modifier, content: @Composable () -> Unit) {
    val part = rememberPart(name, of, about)
    if (part == null) { content(); return }
    val at = Modifier.padding(start = inset, end = inset, top = if (ownGap) K.gap4 else 0.dp).then(surface)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (ownGap) 0.dp else K.gap4)) {
        for ((p, v) in part.before) PartView(p, v, at)
        part.replaced?.let { (p, v) -> PartView(p, v, at) } ?: content()
        for ((p, v) in part.after) PartView(p, v, at)
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
private fun PartView(p: Parts.Piece, view: android.view.View, modifier: Modifier = Modifier) {
    key(p.identity, p.where, view) {
        AndroidView(factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view }, modifier = modifier.fillMaxWidth())
    }
}
