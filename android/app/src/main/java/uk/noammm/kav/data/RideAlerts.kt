package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Line alerts (detours, delays, moved stops) from the source the rider picked (Source.ALERTS): Kav's server, the
// rider's own, or Google Maps on the phone. A server's come as one list for all of Israel matched to the ride's line on
// the phone, so no one learns the trip; Google's are asked for each ride, from its boarding stop to where it gets off.
object RideAlerts {
    // category: 3 a change of service, 4 a disruption. label: what it is, "Detour".
    class Alert(val category: Int, val label: String, val ids: List<String>, val source: Source)

    internal fun combined(planned: Alert?, listed: Alert?): Alert? {
        if (planned == null) return listed
        if (listed != null && planned.source != listed.source) return listed
        return Alert(maxOf(planned.category, listed?.category ?: 0), planned.label,
            (planned.ids + listed?.ids.orEmpty()).distinct(), planned.source)
    }

    class Notice(
        val id: String, val category: Int, val label: String, val title: String, val body: String, val html: Boolean,
        val activeFrom: Long, val activeTo: Long, val url: String, val detour: Detour? = null,
    )

    // A detour's stops by their public codes, where the source says: the ones not served, and the ones added.
    class Detour(val skipped: List<Int>, val added: List<Int>)

    // A detour's stops by code, as the operators write them under "תחנות מבוטלות:" (not served) and "תחנה חלופית:"
    // or "תחנה זמנית חלופית:" (added): a code at the start of each line below the heading. Google's notices
    // are read so; a server sends its own (ServerAlerts).
    internal fun detourOf(text: String): Detour? {
        val skipped = LinkedHashSet<Int>()
        val added = LinkedHashSet<Int>()
        var into: MutableSet<Int>? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.contains("מבוטל") && line.contains("תחנ") -> into = skipped
                line.contains("חלופי") && line.contains("תחנ") && !line.first().isDigit() -> into = added
                else -> {
                    val code = Regex("""^(\d{4,6})(?!\d)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    if (code != null && into != null) into.add(code) else into = null
                }
            }
        }
        if (skipped.isEmpty() && added.isEmpty()) return null
        return Detour(skipped.toList(), added.toList())
    }

    // A line's way as the ministry names it, the key every list matches lines by.
    internal fun routeKey(name: String) = name.trim().replace(Regex("\\s+"), " ")

    // Off until the rider asks for them (Prefs.rideAlerts), then from source: the one picked for Kav's language now
    // (Prefs.alertSources), so switching the language brings back the source last picked in it. Until one is picked,
    // Kav's server.
    var enabled by mutableStateOf(false)
    var picked by mutableStateOf(emptyMap<String, Source>())
    var source: Source
        get() = picked[uk.noammm.kav.ui.T.lang.code] ?: Source.KAV
        set(s) { picked = picked + (uk.noammm.kav.ui.T.lang.code to s) }
    // The notices' language (Prefs.alertLanguage): "auto" Kav's own, "en" or "he".
    var language by mutableStateOf("auto")

    // Only a Kav server translates, so alerts from Google stay in Hebrew, as the operators write them.
    fun translates(): Boolean = source == Source.KAV || source == Source.OWN

    fun english(): Boolean = translates() && when (language) {
        "en" -> true
        "he" -> false
        else -> !uk.noammm.kav.ui.T.rtl
    }

    // A ride's line's alert, as far as it's known now: a server's list, or Google's answer for the ride.
    fun of(line: Transit.LineInfo?, ride: Transit.Leg? = null, r: Transit.Resolved? = null): Alert? {
        if (!enabled) return null
        return when (source) {
            Source.GOOGLE -> if (ride != null && r != null) GoogleAlerts.of(ride, r) else null
            else -> ServerAlerts.of(line)
        }
    }

    // Brought up to date while a screen with rides is open: the list, or Google's answers for the rides shown.
    suspend fun refresh(rides: List<Pair<Transit.Leg, Transit.Resolved>> = emptyList()) {
        if (!enabled) return
        when (source) {
            Source.GOOGLE -> GoogleAlerts.refresh(rides)
            else -> ServerAlerts.refresh()
        }
    }

    // The notices behind an alert: kept ones at once, else fetched (each once, the screen's rows together).
    fun cachedNotices(ids: List<String>, from: Source): List<Notice>? = when (from) {
        Source.GOOGLE -> GoogleAlerts.notices(ids)
        else -> ServerAlerts.notices(ids)
    }

    // An opened alert's notices: a server's list asked for again when they're missing. Why one couldn't be had is
    // logged under KavAlerts.
    suspend fun noticesNow(ids: List<String>, from: Source): List<Notice> = try {
        cachedNotices(ids, from) ?: when (from) {
            Source.GOOGLE -> throw java.io.IOException("Google's answer hasn't these notices")
            else -> {
                ServerAlerts.refresh(asked = true)
                ServerAlerts.notices(ids) ?: throw java.io.IOException("the server's list hasn't these notices")
            }
        }
    } catch (e: java.io.IOException) {
        android.util.Log.w(LOG, "notices of $ids: ${e.message}")
        throw e
    }

    private const val LOG = "KavAlerts"

    // Notices in English where the rider reads them so and they came in Hebrew (the operators write them so): Kav's
    // server translates them, or the rider's own when its alerts come from there. Only the notices' public text goes.
    // Each text is kept once translated; a failure leaves the Hebrew.
    private val englishOf = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val HEBREW = Regex("[\u0590-\u05FF]")

    private fun plain(n: Notice) = if (n.html || n.body.contains('<')) n.body.replace(Regex("(?i)<\\s*br\\s*/?>|</\\s*(p|div|li)\\s*>"), "\n")
        .replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&quot;", "\"").replace("&amp;", "&").trim() else n.body

    private fun wanted(notices: List<Notice>) =
        if (!english()) emptyList() else notices.flatMap { listOf(it.title, plain(it)) }.filter { HEBREW.containsMatchIn(it) }.distinct()

    // The notices in the language wanted from what's kept, or null when some still need translating.
    fun inLanguageKept(notices: List<Notice>): List<Notice>? =
        if (wanted(notices).all(englishOf::containsKey)) notices.map(::translated) else null

    suspend fun inLanguage(notices: List<Notice>): List<Notice> {
        val missing = wanted(notices).filterNot(englishOf::containsKey)
        if (missing.isNotEmpty()) try {
            for (part in missing.chunked(20)) {
                val en = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { translate(part) }
                part.zip(en).forEach { (he, it) -> englishOf[he] = it }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w(LOG, "translating: ${e.message}")
        }
        return notices.map(::translated)
    }

    private fun translated(n: Notice): Notice {
        if (!english()) return n
        val title = englishOf[n.title] ?: n.title
        val body = englishOf[plain(n)]
        return Notice(n.id, n.category, n.label, title, body ?: n.body, if (body != null) false else n.html,
            n.activeFrom, n.activeTo, n.url, n.detour)
    }

    private fun translate(texts: List<String>): List<String> {
        val at = if (source == Source.OWN && KavLive.ownServer.isNotEmpty()) KavLive.ownServer else KavLive.KAV_SERVER
        val path = "/live/v1/translate"
        val c = (RiderProxy.open(java.net.URL(at + path)) as java.net.HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 15_000
            requestMethod = "POST"; doOutput = true
            setRequestProperty("User-Agent", "Kav")
            setRequestProperty("Content-Type", "application/json")
            KavLive.sign(this, path, KavLive.keyFor(at))
        }
        try {
            c.outputStream.use { it.write(org.json.JSONObject().put("texts", org.json.JSONArray(texts)).toString().toByteArray()) }
            if (c.responseCode != 200) throw java.io.IOException("translate HTTP ${c.responseCode}")
            val out = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("texts")
            if (out.length() != texts.size) throw java.io.IOException("translate answered ${out.length()} of ${texts.size}")
            return (0 until out.length()).map { out.getString(it) }
        } finally {
            c.disconnect()
        }
    }
}
