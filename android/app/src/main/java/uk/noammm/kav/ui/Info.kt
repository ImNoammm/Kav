package uk.noammm.kav.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith

// A section's header with its explanation kept behind a small (i) at its end: the text slides open under the header
// in a card, like the Live screen's, when asked for, instead of sitting in grey under every control. Its own height eases
// after the card's, so what's under it follows softly as it opens and closes, the way Trip planning's always did. It looks
// and sits as SectionHeader does; the (i) overlaps the row rather than making it taller.
@Composable
fun InfoHeader(text: String, info: String, modifier: Modifier = Modifier, headline: Boolean = false) {
    var open by rememberSaveable(text) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))) {
        val heading = if (headline) Modifier.padding(bottom = K.gap2) else Modifier.padding(start = K.gap4, top = K.gap6, bottom = K.gap2)
        Row(Modifier.fillMaxWidth().then(heading), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = if (headline) K.type.largeTitle else K.type.header, modifier = Modifier.weight(1f))
            Box(
                Modifier.overlapsHeight().size(40.dp).clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = T("About $text", "על $text")) { open = !open },
                contentAlignment = Alignment.Center,
            ) {
                val tint by androidx.compose.animation.animateColorAsState(if (open) K.accentInk else K.textTertiary, tween(200), label = "infoTint")
                Icon(
                    Icons.Rounded.Info, contentDescription = T("About $text", "על $text"),
                    tint = tint, modifier = Modifier.size(18.dp),
                )
            }
        }
        AnimatedVisibility(
            open,
            enter = expandVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeIn(tween(200, delayMillis = 60)),
            exit = shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)) + fadeOut(tween(120)),
        ) {
            AnimatedContent(
                info, transitionSpec = { fadeIn(tween(200, delayMillis = 60)) togetherWith fadeOut(tween(120)) }, label = "info",
            ) { shown -> NoteCard(shown, Modifier.padding(bottom = K.gap2)) }
        }
    }
}
