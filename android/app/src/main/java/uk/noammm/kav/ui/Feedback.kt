package uk.noammm.kav.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import java.net.URLEncoder
import uk.noammm.kav.data.KavLive
import uk.noammm.kav.data.KavPlan
import uk.noammm.kav.data.Source
import uk.noammm.kav.data.StopNames
import uk.noammm.kav.data.Updates

// "Found a bug?" and "Request a feature?" open GitHub's forms (.github/ISSUE_TEMPLATE) with what Kav knows about itself
// already filled in. GitHub takes a form's text fields from the address, by their ids; the labels come from the form,
// whoever opens it. Never a server's address or anything about the rider.
internal fun feedbackLink(ctx: Context, bug: Boolean): String =
    if (bug) issueLink(
        "bug_report.yml",
        listOf(
            "version" to Updates.installedVersion(ctx),
            "android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "device" to deviceName(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()),
            "setup" to setupLine(KavLive.source, KavPlan.source, T.lang, StopNames.english, uk.noammm.kav.data.Schedules.source),
        ),
    )
    else issueLink("feature_request.yml", listOf("version" to Updates.installedVersion(ctx)))

internal fun issueLink(form: String, fields: List<Pair<String, String>>): String =
    "$REPO_URL/issues/new?template=$form" + fields.filter { it.second.isNotBlank() }.joinToString("") { (id, value) ->
        // Spaces as %20 rather than +: a space to whatever decodes the query.
        "&$id=" + URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

// "Google Pixel 8", "Samsung SM-S918B": the maker once, even where the model already starts with it.
internal fun deviceName(maker: String, model: String): String {
    val m = maker.trim().replaceFirstChar { it.titlecase(java.util.Locale.ROOT) }
    return if (m.isEmpty() || model.startsWith(m, ignoreCase = true)) model.trim() else "$m ${model.trim()}"
}

// In English, for whoever reads the issue: where live times and trips are set to come from, each its own choice.
internal fun setupLine(live: Source, plan: Source, lang: Lang, englishNames: Boolean, timetable: Source = Source.KAV): String {
    fun from(s: Source, server: String, own: String) = when (s) { Source.KAV -> server; Source.OWN -> own; Source.GOOGLE -> "Google" }
    return "Live: ${from(live, "Kav's server", "own server")}. " +
        "Trips: ${from(plan, "Kav's planner", "own server's planner")}. " +
        "Timetable: ${from(timetable, "Kav's week", "own server's week")}. " +
        "Language: ${if (lang == Lang.HE) "Hebrew" else "English"}, stop names in ${if (englishNames) "English" else "Hebrew"}."
}

// #110: GitHub gets what is in a form's address as soon as the page opens, whether the report is sent or not. So the bug
// form asks first: filled in with Kav's version, the phone's model and the setup line, or empty. Closing it opens nothing.
@androidx.compose.runtime.Composable
internal fun BugReportAsk(ctx: Context, dismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxWidth()
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(K.rCard)).background(K.surface1).padding(K.gap5),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(K.gap4),
        ) {
            androidx.compose.material3.Text(T("Fill in details automatically?", "למלא פרטים אוטומטית?"), style = K.type.title)
            androidx.compose.material3.Text(
                T(
                    "Kav can add its version, your Android version, your phone's model and where Kav gets its data from (never an address or anything about you). GitHub receives them as soon as the page opens, even if you don't send the report.",
                    "Kav יכולה להוסיף את הגרסה שלה, גרסת האנדרואיד, דגם הטלפון ומאיפה Kav לוקחת נתונים (אף פעם לא כתובת או משהו עליכם). GitHub מקבל אותם ברגע שהדף נפתח, גם אם לא שולחים את הדיווח.",
                ),
                style = K.type.subhead,
            )
            KavButton(T("Fill in", "למלא"), { dismiss(); openLink(ctx, feedbackLink(ctx, bug = true)) }, androidx.compose.ui.Modifier.fillMaxWidth())
            KavButton(
                T("Empty form", "טופס ריק"), { dismiss(); openLink(ctx, issueLink("bug_report.yml", emptyList())) },
                androidx.compose.ui.Modifier.fillMaxWidth(), style = ButtonStyle.Secondary,
            )
            KavButton(T("Cancel", "ביטול"), dismiss, androidx.compose.ui.Modifier.fillMaxWidth(), style = ButtonStyle.Plain)
        }
    }
}
