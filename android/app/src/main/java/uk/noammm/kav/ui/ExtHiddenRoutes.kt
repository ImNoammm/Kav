package uk.noammm.kav.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

// Under a search's routes (or in their place, when none is left): how many routes the rider's own extensions left out
// and which did, with the one tap that shows them this once. A quiet note, not a warning: the rider asked for the rule.
// count and by are Pipeline.hiddenNotice's (each extension's name and how many it left out); reasons: why, as the
// extensions said it, in a small line under; showing: the rider has asked to see them (Pipeline.showOnce), so the note
// only says they're there.
@Composable
internal fun HiddenRoutesNotice(count: Int, by: List<Pair<String, Int>>, reasons: List<String>, showing: Boolean, modifier: Modifier = Modifier, onShow: () -> Unit) {
    val who = when (by.size) {
        1 -> T.iso(by[0].first)
        2 -> T.iso(by[0].first) + T(" and ", " ו-") + T.iso(by[1].first)
        else -> T("${by.size} extensions", "${by.size} תוספים")
    }
    val words = when {
        showing && count == 1 -> T("Showing 1 hidden route", "מוצג גם מסלול אחד שהוסתר")
        showing -> T("Showing $count hidden routes", "מוצגים גם $count מסלולים שהוסתרו")
        count == 1 -> T("1 route hidden by $who", "מסלול אחד הוסתר על ידי $who")
        else -> T("$count routes hidden by $who", "$count מסלולים הוסתרו על ידי $who")
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(K.rTile)).background(K.fill).padding(start = K.gap4, end = K.gap1),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3),
    ) {
        Icon(Icons.Rounded.FilterAlt, null, tint = K.textSecondary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(vertical = K.gap3)) {
            Text(words, style = K.type.subhead)
            // The reasons are the extensions' own words, set apart as theirs.
            if (reasons.isNotEmpty()) Text(reasons.joinToString(" · ") { "\u2068$it\u2069" }, style = K.type.caption, color = K.textSecondary,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (!showing) KavButton(T("Show", "הצגה"), onShow, style = ButtonStyle.Plain)
    }
}
