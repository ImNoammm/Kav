package uk.noammm.kav.data

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

// Google Maps asked the way one logged-out browser asks it, as Vela Maps does (github.com/PimpinPumpkin/Vela,
// GoogleSession.kt and BrowserHeaders.kt): the Maps page is opened once so Google's own cookies are set, and every
// request after it carries them with Chrome's full set of headers. Plain requests with no cookies got Google's "limited
// view" after about a dozen. The cookies are kept across launches, since Google gives new sessions less.
internal object GoogleSession {
    private const val WARM_URL = "https://www.google.com/maps?hl=iw&gl=il"
    private const val REFERER = "https://www.google.com/maps"
    private const val LANGUAGES = "he-IL,he;q=0.9,en-US;q=0.8,en;q=0.7"
    private const val ACCEPT_DOCUMENT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"

    @Volatile private var store: android.content.SharedPreferences? = null
    @Volatile private var warmed = false

    fun init(ctx: Context) {
        if (store == null) store = ctx.applicationContext.getSharedPreferences("google-session", Context.MODE_PRIVATE)
    }

    private object Jar : CookieJar {
        private val kept = LinkedHashMap<String, Cookie>()
        private var loaded = false

        private fun id(c: Cookie) = "${c.domain}|${c.path}|${c.name}"

        @Synchronized private fun load() {
            if (loaded) return
            loaded = true
            val saved = store?.getString("cookies", null) ?: return
            runCatching {
                val a = JSONArray(saved)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val b = Cookie.Builder().name(o.getString("n")).value(o.getString("v")).path(o.getString("p"))
                        .expiresAt(o.getLong("e"))
                    if (o.optBoolean("h")) b.hostOnlyDomain(o.getString("d")) else b.domain(o.getString("d"))
                    if (o.optBoolean("s")) b.secure()
                    if (o.optBoolean("o")) b.httpOnly()
                    b.build().let { kept[id(it)] = it }
                }
            }
        }

        @Synchronized private fun save() {
            val a = JSONArray()
            for (c in kept.values) if (c.persistent) a.put(
                JSONObject().put("n", c.name).put("v", c.value).put("d", c.domain).put("p", c.path)
                    .put("e", c.expiresAt).put("h", c.hostOnly).put("s", c.secure).put("o", c.httpOnly),
            )
            store?.edit()?.putString("cookies", a.toString())?.apply()
        }

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            load()
            for (c in cookies) kept[id(c)] = c
            save()
        }

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            load()
            val now = System.currentTimeMillis()
            kept.values.removeAll { it.expiresAt < now }
            return kept.values.filter { it.matches(url) }
        }

        @Synchronized fun has(name: String): Boolean {
            load()
            val now = System.currentTimeMillis()
            return kept.values.any { it.name == name && it.expiresAt >= now }
        }
    }

    private val made = lazy {
        OkHttpClient.Builder().cookieJar(Jar).proxyAuthenticator(RiderProxy.okHttp).connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).build()
    }
    private val http by made

    // Connections already open go on as they were: closed when the proxy changes, so the next request takes the new way.
    fun dropConnections() { if (made.isInitialized()) http.connectionPool.evictAll() }

    // Opens the Maps page once a launch, a first visit's way (no referrer), when Google's session cookie isn't kept.
    private fun warm() {
        if (warmed) return
        if (!Jar.has("NID")) runCatching {
            http.newCall(headers(Request.Builder().url(WARM_URL), document = true).build()).execute().use { it.body?.string() }
        }
        warmed = true
    }

    // A Maps data request, as the page's own script makes it. Null when Google answered with anything but 200.
    fun get(url: String): String? {
        warm()
        return http.newCall(headers(Request.Builder().url(url), document = false).build()).execute().use { r ->
            if (r.code != 200) null else r.body?.string()
        }
    }

    // Chrome's headers for its version in the user agent: the client hints have to name the same version.
    private fun headers(b: Request.Builder, document: Boolean): Request.Builder {
        val ua = GoogleRecipe.current.userAgent
        b.header("User-Agent", ua)
        b.header("Accept", if (document) ACCEPT_DOCUMENT else "*/*")
        b.header("Accept-Language", LANGUAGES)
        chromeMajor(ua)?.let { b.header("Sec-CH-UA", secChUaFor(it)) }
        b.header("Sec-CH-UA-Mobile", "?0")
        b.header("Sec-CH-UA-Platform", "\"Windows\"")
        b.header("Sec-Fetch-Dest", if (document) "document" else "empty")
        b.header("Sec-Fetch-Mode", if (document) "navigate" else "cors")
        b.header("Sec-Fetch-Site", if (document) "none" else "same-origin")
        if (document) b.header("Upgrade-Insecure-Requests", "1")
        else {
            // The two hints google.com asks for (Accept-CH), sent on every request after the page.
            b.header("Referer", REFERER)
            b.header("Downlink", "10")
            b.header("RTT", "50")
        }
        return b
    }

    // One budget for every Google request (alerts, how busy): 2.5 s apart, and none for half an hour after a limited
    // view or a refusal, which more requests would only prolong.
    private const val SPACING_MS = 2_500L
    private const val PAUSE_MS = 30 * 60_000L
    private val turn = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastAsk = 0L
    @Volatile private var pausedUntil = 0L

    val paused get() = System.currentTimeMillis() < pausedUntil

    // Runs one request in its turn. Throws when Google is resting, and starts the rest when the request fails.
    suspend fun <T> spaced(request: () -> T): T = turn.withLock {
        if (paused) throw java.io.IOException("Google is resting after a limited view")
        val gap = lastAsk + SPACING_MS - System.currentTimeMillis()
        if (gap > 0) kotlinx.coroutines.delay(gap)
        lastAsk = System.currentTimeMillis()
        try {
            request()
        } catch (e: java.io.IOException) {
            pausedUntil = System.currentTimeMillis() + PAUSE_MS
            throw e
        }
    }

    internal fun chromeMajor(ua: String): Int? = Regex("""Chrome/(\d+)\.""").find(ua)?.groupValues?.get(1)?.toIntOrNull()

    // The Sec-CH-UA desktop Chrome sends for a major version: Chrome derives its made-up brand, that brand's version and
    // the order of the three from the major alone (components/embedder_support/user_agent_utils.cc, as Vela ports it).
    internal fun secChUaFor(major: Int): String {
        val chars = listOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        val greaseVersions = listOf("8", "99", "24")
        val list = listOf(
            "Not${chars[major % chars.size]}A${chars[(major + 1) % chars.size]}Brand" to greaseVersions[major % greaseVersions.size],
            "Chromium" to "$major",
            "Google Chrome" to "$major",
        )
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        val order = orders[major % orders.size]
        val out = arrayOfNulls<Pair<String, String>>(3)
        for (i in list.indices) out[order[i]] = list[i]
        return out.joinToString(", ") { "\"${it!!.first}\";v=\"${it.second}\"" }
    }
}
