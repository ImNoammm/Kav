package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uk.noammm.kav.data.RideAlerts.Alert
import uk.noammm.kav.data.RideAlerts.Detour
import uk.noammm.kav.data.RideAlerts.Notice
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

// Line alerts from Kav's server or the rider's own (/live/v1/alerts): Israel's whole list with every notice in it, in
// Kav's language (the server translates the operators' Hebrew). Lines are matched here on the phone, by the ministry's
// name for each way of a line, so the server never learns a trip.
object ServerAlerts {
    private class Board(val lines: Map<String, Alert>, val notices: Map<String, Notice>, val at: Long, val lang: String, val server: String)

    private var board by mutableStateOf<Board?>(null)
    private val asking = AtomicBoolean(false)
    @Volatile private var failedAt = 0L

    private const val FRESH_S = 10 * 60L
    private const val RETRY_S = 2 * 60L
    private const val ASKED_S = 60L
    private const val LOG = "KavAlerts"

    // The server the choice asks: Kav's, or the rider's own once it's set up (none until then).
    private fun server(): String? = when (RideAlerts.source) {
        Source.OWN -> KavLive.ownServer.ifEmpty { null }
        else -> KavLive.KAV_SERVER
    }

    private fun lang() = if (RideAlerts.english()) "en" else "he"

    private fun current(): Board? = board?.takeIf { it.server == server() && it.lang == lang() }

    fun of(line: Transit.LineInfo?): Alert? {
        val name = line?.routeName?.let(RideAlerts::routeKey)?.ifEmpty { null } ?: return null
        return current()?.lines?.get(name)
    }

    // The ones the list has: a server still fetching notices lists an alert before its notice (none yet is as good as
    // not fetched).
    fun notices(ids: List<String>): List<Notice>? {
        val all = current()?.notices ?: return null
        return ids.filter { it.isNotBlank() }.distinct().mapNotNull { all[it] }.ifEmpty { null }
    }

    // The list at most every ten minutes, and again when the language or the server changes. A failure waits two minutes.
    // asked: an alert opened whose notice isn't in the list kept, which asks again after a minute.
    suspend fun refresh(asked: Boolean = false) = withContext(Dispatchers.IO) {
        val source = RideAlerts.source
        val at = server() ?: return@withContext
        val now = System.currentTimeMillis() / 1000
        val lang = lang()
        board?.let { if (it.server == at && it.lang == lang && now - it.at < if (asked) ASKED_S else FRESH_S) return@withContext }
        if (!asked && now - failedAt < RETRY_S || !asking.compareAndSet(false, true)) return@withContext
        try {
            val (lines, notices) = parse(JSONObject(fetch(at, lang)), source)
            board = Board(lines, notices, now, lang, at)
        } catch (e: Exception) {
            failedAt = now
            android.util.Log.w(LOG, "server alerts failed: ${e.message}")
        } finally {
            asking.set(false)
        }
    }

    private fun fetch(at: String, lang: String): String {
        val path = "/live/v1/alerts/$lang"
        val c = (RiderProxy.open(URL(at + path)) as HttpURLConnection).apply {
            connectTimeout = 5_000; readTimeout = 15_000
            setRequestProperty("User-Agent", "Kav")
            setRequestProperty("Accept", "application/json")
            KavLive.sign(this, path, KavLive.keyFor(at))
        }
        try {
            if (c.responseCode != 200) throw java.io.IOException("alerts HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // {"at", "lang", "lines": [{"name", "category", "label", "ids"}], "notices": {id: {"category", "label", "title",
    // "body", "html", "from", "to", "url", "detour": {"skipped", "added"}}}} (server/API.md). A way listed twice keeps
    // the worse status and every alert behind it.
    internal fun parse(o: JSONObject, source: Source = Source.KAV): Pair<Map<String, Alert>, Map<String, Notice>> {
        val lines = HashMap<String, Alert>()
        val list = o.optJSONArray("lines")
        if (list != null) for (i in 0 until list.length()) {
            val a = list.optJSONObject(i) ?: continue
            val k = RideAlerts.routeKey(a.optString("name"))
            if (k.isEmpty()) continue
            val category = a.optInt("category")
            val label = a.optString("label")
            val ids = a.optJSONArray("ids")?.let { l -> (0 until l.length()).map { l.optString(it) } }.orEmpty().filter { it.isNotBlank() }
            val had = lines[k]
            lines[k] = when {
                had == null -> Alert(category, label, ids, source)
                category > had.category -> Alert(category, label, (ids + had.ids).distinct(), source)
                else -> Alert(had.category, had.label, (had.ids + ids).distinct(), source)
            }
        }
        val notices = HashMap<String, Notice>()
        val n = o.optJSONObject("notices")
        if (n != null) for (id in n.keys()) {
            val a = n.optJSONObject(id) ?: continue
            val d = a.optJSONObject("detour")
            fun codes(name: String) = d?.optJSONArray(name)?.let { l -> (0 until l.length()).map { l.optInt(it) }.filter { it > 0 } }.orEmpty()
            val detour = Detour(codes("skipped"), codes("added")).takeIf { it.skipped.isNotEmpty() || it.added.isNotEmpty() }
            notices[id] = Notice(
                id, a.optInt("category"), a.optString("label"), a.optString("title"), a.optString("body"), a.optBoolean("html"),
                a.optLong("from"), a.optLong("to"), a.optString("url"), detour,
            )
        }
        return lines to notices
    }
}
