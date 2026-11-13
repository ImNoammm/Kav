package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import uk.noammm.kav.data.RideAlerts.Alert
import uk.noammm.kav.data.RideAlerts.Notice
import uk.noammm.kav.ui.T
import java.net.URLEncoder

// Line alerts from Google Maps, asked from the phone for each ride shown: Google's transit directions from the ride's
// boarding stop to where it gets off carry the alerts on each line. Only a trip page or navigation asks, at most every
// five minutes a ride, never in the background. Google receives the two stops, and the phone's address.
object GoogleAlerts {
    // What Google said about one ride: its alerts that name the ride's line, at a time.
    private class Answer(val notices: List<Notice>, val at: Long)

    private var answers by mutableStateOf<Map<String, Answer>>(emptyMap())
    private val asking = Mutex()

    private const val FRESH_S = 5 * 60L
    private const val LOG = "KavAlerts"
    // Google cuts an alert's text here.
    private const val CUT = 1000

    private fun key(ride: Transit.Leg, r: Transit.Resolved): String? {
        val number = number(ride, r) ?: return null
        val from = r.stop(ride.fromStop) ?: return null
        val to = r.stop(ride.toStop) ?: return null
        return "$number|${from.code}|${to.code}"
    }

    private fun number(ride: Transit.Leg, r: Transit.Resolved) =
        ride.shortName.ifBlank { r.line(ride.lineId)?.number.orEmpty() }.trim().ifEmpty { null }

    fun of(ride: Transit.Leg, r: Transit.Resolved): Alert? {
        val notices = answers[key(ride, r) ?: return null]?.notices?.ifEmpty { null } ?: return null
        val worst = notices.maxBy { it.category }
        return Alert(worst.category, if (T.rtl) worst.label else englishLabel(worst.category, worst.title + worst.label), notices.map { it.id }, Source.GOOGLE)
    }

    fun notices(ids: List<String>): List<Notice>? {
        val all = answers.values.flatMap { it.notices }.associateBy { it.id }
        return ids.map { all[it] ?: return null }
    }

    // Asks Google about each ride still ahead whose answer is older than five minutes, one after another and spaced
    // out. After a limited view or a failure, Google rests for half an hour (GoogleSession.spaced).
    suspend fun refresh(rides: List<Pair<Transit.Leg, Transit.Resolved>>) = withContext(Dispatchers.IO) {
        if (rides.isEmpty() || !asking.tryLock()) return@withContext
        try {
            val now = System.currentTimeMillis() / 1000
            val wanted = rides.filter { (ride, _) -> ride.arr == 0L || ride.arr > now }
                .mapNotNull { (ride, r) -> key(ride, r)?.let { Triple(it, ride, r) } }
                .distinctBy { it.first }
                .filter { (k, _, _) -> answers[k]?.let { now - it.at >= FRESH_S } ?: true }
            for ((k, ride, r) in wanted) {
                if (GoogleSession.paused) return@withContext
                val notices = try {
                    GoogleSession.spaced { ask(ride, r) }
                } catch (e: java.io.IOException) {
                    if (android.util.Log.isLoggable(LOG, android.util.Log.DEBUG)) android.util.Log.d(LOG, "Google alerts paused: ${e.message}")
                    return@withContext
                } ?: continue
                // On with `adb shell setprop log.tag.KavAlerts DEBUG`: each ride asked and what of it Google named.
                if (android.util.Log.isLoggable(LOG, android.util.Log.DEBUG)) android.util.Log.d(LOG, "google $k: ${notices.map { it.label }}")
                answers = answers + (k to Answer(notices, System.currentTimeMillis() / 1000))
            }
        } finally {
            asking.unlock()
        }
    }

    // Google's alerts for the ride's line, or null when the ride's stops have no position. Throws on a limited view,
    // a refusal or a reply that isn't Google's data.
    private fun ask(ride: Transit.Leg, r: Transit.Resolved): List<Notice>? {
        val from = r.stop(ride.fromStop) ?: return null
        val to = r.stop(ride.toStop) ?: return null
        val a = from.point ?: return null
        val b = to.point ?: return null
        val body = directions(a, b)
        GoogleCrowd.learn(stopIds(body))
        return forRide(body, number(ride, r) ?: return null, from.code, to.code)
    }

    // The way Google asks for a ride: its directions from the boarding stop to where it gets off.
    internal fun directions(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
        val body = GoogleSession.get(url(a, b)) ?: throw java.io.IOException("Google refused the request")
        if (body.contains("limited view") || !body.startsWith(")]}'")) throw java.io.IOException("Google gave a limited view")
        return body
    }

    // Every stop a directions reply names, by its code: Google's own id for it (step[5][0] and step[5][1]: [1] the code,
    // [6] the id), for its page (GoogleCrowd).
    internal fun stopIds(body: String): Map<String, String> {
        val out = HashMap<String, String>()
        val trips = JSONArray(body.substringAfter('\n')).optJSONArray(0)?.optJSONArray(1) ?: return out
        for (t in 0 until trips.length()) {
            val sections = trips.optJSONArray(t)?.optJSONArray(1) ?: continue
            for (s in 0 until sections.length()) {
                val steps = sections.optJSONArray(s)?.optJSONArray(1) ?: continue
                for (k in 0 until steps.length()) {
                    val stops = steps.optJSONArray(k)?.optJSONArray(5) ?: continue
                    for (e in 0..1) stops.optJSONArray(e)?.let { stop ->
                        val code = stop.optString(1).trim()
                        val id = stop.optString(6).trim()
                        if (code.isNotEmpty() && id.startsWith("0x")) out[code] = id
                    }
                }
            }
        }
        return out
    }

    private fun url(a: Pair<Double, Double>, b: Pair<Double, Double>): String {
        val pb = "!1m2!1s${a.first},${a.second}!6e0!1m2!1s${b.first},${b.second}!6e0$DIRECTIONS_TAIL"
        return "https://www.google.com/maps/preview/directions?authuser=0&hl=iw&gl=il&pb=" + URLEncoder.encode(pb, "UTF-8")
    }

    // The ride's line's alerts in a directions reply (Hebrew): from the step that rides the line from the same stop,
    // else any step on a line of that number, else one between the same two stops (a train, whose "number" is a name).
    // Google gives an operator's alerts to all its lines, so only an alert naming the line is kept.
    internal fun forRide(body: String, number: String, fromCode: String, toCode: String): List<Notice> {
        if (body.contains("limited view") || !body.startsWith(")]}'")) throw java.io.IOException("Google gave a limited view")
        val root = JSONArray(body.substringAfter('\n'))
        val steps = transitSteps(root)
        val step = steps.firstOrNull { it.number == number && it.from == fromCode }
            ?: steps.firstOrNull { it.number == number }
            ?: steps.firstOrNull { it.from == fromCode && it.to == toCode }
            ?: return emptyList()
        return step.alerts.filter { names(it, number) }.distinctBy { it.id }
    }

    private class Step(val number: String, val from: String, val to: String, val alerts: List<Notice>)

    // Transit steps: root[0][1][trip][1][section][1][step], with step[0][0] 3. The line number is the [5, [number]]
    // entry of step[0][14], the stops' codes step[5][0][1] and step[5][1][1], the line's alerts step[0][9].
    private fun transitSteps(root: JSONArray): List<Step> {
        val out = ArrayList<Step>()
        val trips = root.optJSONArray(0)?.optJSONArray(1) ?: return out
        for (t in 0 until trips.length()) {
            val sections = trips.optJSONArray(t)?.optJSONArray(1) ?: continue
            for (s in 0 until sections.length()) {
                val steps = sections.optJSONArray(s)?.optJSONArray(1) ?: continue
                for (k in 0 until steps.length()) {
                    val step = steps.optJSONArray(k) ?: continue
                    val head = step.optJSONArray(0) ?: continue
                    if (head.optInt(0) != 3) continue
                    var number = ""
                    head.optJSONArray(14)?.let { e ->
                        for (i in 0 until e.length()) e.optJSONArray(i)?.takeIf { it.optInt(0) == 5 }?.let {
                            number = it.optJSONArray(1)?.optString(0).orEmpty()
                        }
                    }
                    val stops = step.optJSONArray(5)
                    val alerts = head.optJSONArray(9)?.let { l -> (0 until l.length()).mapNotNull { notice(l.optJSONArray(it)) } }.orEmpty()
                    out += Step(
                        number.trim(), stops?.optJSONArray(0)?.optString(1).orEmpty(), stops?.optJSONArray(1)?.optString(1).orEmpty(),
                        alerts,
                    )
                }
            }
        }
        return out
    }

    // An alert block: [0] severity, [1] Google's label ("קווים 1, 4, 65: שינוי מסלול"), [2] the operator's title,
    // [3] its text (cut at 1000 characters), [4] a link, [6] and [7] active from and to ([epoch, zone, ...]), [8] 20 for
    // a transit alert (8 is a turn notice).
    private fun notice(a: JSONArray?): Notice? {
        if (a == null || a.optInt(8) != 20) return null
        val label = a.optString(1).trim()
        val title = a.optString(2).trim()
        val text = a.optString(3)
        val body = if (text.length >= CUT) text.trimEnd() + "…" else text.trim()
        if (label.isEmpty() && title.isEmpty() && body.isEmpty()) return null
        val from = a.optJSONArray(6)?.optLong(0) ?: 0L
        val to = a.optJSONArray(7)?.optLong(0) ?: 0L
        val category = category(label + "\n" + title)
        return Notice(
            "g" + (title + "\n" + text + "\n" + from).hashCode().toUInt().toString(36), category, label, title, body, false,
            from, to, a.optJSONArray(4)?.optString(0).orEmpty(), RideAlerts.detourOf(text),
        )
    }

    // Delays and cancellations disrupt (category 4); a detour or other change is a change of service (3).
    private fun category(words: String) = if (words.contains("עיכוב") || words.contains("ביטול") || words.contains("מבוטל")) 4 else 3

    private fun englishLabel(category: Int, words: String) = when {
        words.contains("מסלול") -> "Detour"
        words.contains("עיכוב") -> "Delays"
        category == 4 -> "Disruption"
        else -> "Service change"
    }

    private val LIST_WORD = Regex("""(?<![א-ת])[לבהומש]{0,2}(?:קו|קוו?ים|קווי|קוי)(?![א-ת])|(?i)\blines?\b""")
    // A line number, not a time or a date ("8:00", "8.11").
    private val LINE = Regex("""\d{1,3}[א-ת]?(?![א-ת\d]|[:.]\d)""")
    // The en dash operators type in notices, as a code so the source holds none.
    private val DASH = Char(0x2013)
    // At most one word before the list: "קווים מושפעים-400/401", "בקוים:".
    private val LEAD = Regex("""^[\s:]*(?:[א-תA-Za-z'"]{2,12}[\s:\-${DASH}]*)?""")
    // Between numbers of one list: commas, slashes, spaces, "ו" ("9 ו-19") or "and"; "-2" after a number is its direction.
    private val BETWEEN = Regex("""^(?:-\d(?!\d))?(?:\s*(?:,|/|&|and(?=\s)|ו-?|ו־|־|\s)\s*)+""")

    // The lines an alert names: the numbers after "קו" or "קווים" (and "line" or "lines") in its label, title or text.
    internal fun namedLines(text: String): Set<String> {
        val out = HashSet<String>()
        for (word in LIST_WORD.findAll(text)) {
            var at = word.range.last + 1
            val lead = LEAD.find(text.substring(at))?.value.orEmpty()
            at += lead.length
            while (at < text.length) {
                val m = LINE.find(text, at)?.takeIf { it.range.first == at } ?: break
                out += m.value
                at = m.range.last + 1
                val gap = BETWEEN.find(text.substring(at)) ?: break
                if (gap.value.isEmpty()) break
                at += gap.value.length
            }
        }
        return out
    }

    internal fun names(n: Notice, number: String) = number in namedLines(n.label + "\n" + n.title + "\n" + n.body)

    // The rest of the directions request the Maps page itself sent (8 Nov 2026), after its two places: leave now,
    // transit, and the reply's parts the page asks for.
    private const val DIRECTIONS_TAIL = "!3m12!1m3!1d13518.827742491887!2d34.961676!3d32.104202699999995!2m3!1f0.0!2f0.0" +
        "!3f0.0!3m2!1i1024!2i768!4f13.1!6m59!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2!20e3!39b1!6m28!32i1!49b1!63m0!66b1" +
        "!85b1!114b1!149b1!206b1!209b1!212b1!215b1!216b1!222b1!223b1!234b1!235b1!246b1!253b1!260b1!262b1!266b1!270b1!271b1" +
        "!273b1!281b1!294b1!302i300!303i100!10b1!12b1!13b1!14b1!16b1!17m1!3e1!20m5!1e3!2e3!5e2!6b1!14b1!46m1!1b0!96b1" +
        "!97m1!2b1!99b1!15m3!1sIWvwasbUMPe70PEP2s_ogA4!7e81!15i10142!20m28!1m6!1m2!1i0!2i0!2m2!1i530!2i768!1m6!1m2!1i974" +
        "!2i0!2m2!1i1024!2i768!1m6!1m2!1i0!2i0!2m2!1i1024!2i20!1m6!1m2!1i0!2i748!2m2!1i1024!2i768!27b1!40i802!47m2!8b1!10e2"
}
