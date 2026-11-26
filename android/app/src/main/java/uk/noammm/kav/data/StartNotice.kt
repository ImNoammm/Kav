package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import uk.noammm.kav.R
import uk.noammm.kav.ui.T

// A message this version brings, said once as Kav opens, to a rider who updated: a fresh install has no update to be
// told about, so it never sees it. Its text is bundled in res/raw/notice.json, in English and Hebrew, under an id; it's
// due until the rider closes it, and then never again for that id. A file that's missing or not whole shows nothing.
// Nothing is asked of the network.
internal object StartNotice {
    class Message(
        val id: String,
        private val en: List<String>,
        private val he: List<String>,
    ) {
        val title get() = T(en[0], he[0])
        val text get() = T(en[1], he[1])
    }

    private val FIELDS = listOf("title", "text")

    // The message in the file when it's whole: an id and every field in English, each Hebrew one falling back to it.
    fun parse(json: String?): Message? = runCatching {
        val o = JSONObject(json ?: return null)
        val id = o.getString("id").trim().ifEmpty { return null }
        val en = o.getJSONObject("en").let { e -> FIELDS.map { e.getString(it).trim().ifEmpty { return null } } }
        val he = o.optJSONObject("he").let { h -> FIELDS.mapIndexed { i, f -> h?.optString(f)?.trim().orEmpty().ifEmpty { en[i] } } }
        Message(id, en, he)
    }.getOrNull()

    fun due(json: String?, closed: Set<String>): Message? = parse(json)?.takeIf { it.id !in closed }

    // At an opening: the message when it's due and Android installed this Kav over an earlier one. On a fresh install
    // it's put down as seen at once, so a later update that brings the same file doesn't show it either.
    fun check(json: String?, closed: Set<String>, updatedInPlace: Boolean, record: (String) -> Unit): Message? {
        val m = due(json, closed) ?: return null
        if (updatedInPlace) return m
        record(m.id)
        return null
    }

    private fun store(ctx: Context) = ctx.getSharedPreferences("notice", Context.MODE_PRIVATE)

    // Small and in the APK, so read as Kav opens, before another popup can take the opening.
    fun notice(ctx: Context): Message? = check(
        runCatching { ctx.resources.openRawResource(R.raw.notice).bufferedReader().use { it.readText() } }.getOrNull(),
        store(ctx).getStringSet("closed", null).orEmpty(),
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { it.lastUpdateTime > it.firstInstallTime } }
            .getOrDefault(false),
    ) { closed(ctx, it) }

    fun closed(ctx: Context, id: String) {
        val s = store(ctx)
        s.edit().putStringSet("closed", s.getStringSet("closed", null).orEmpty() + id).apply()
    }
}
