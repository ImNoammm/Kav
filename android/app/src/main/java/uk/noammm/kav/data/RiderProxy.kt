package uk.noammm.kav.data

import android.content.Context
import android.webkit.HttpAuthHandler
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

// The rider's own proxy, for everything Kav asks for: its server, Google, the planners, the maps and the pages it shows
// (Street View, Rav-Pass, the report form). One ProxySelector for the whole app, put in before
// anything connects (KavApp), reads the setting at each connection, so a change counts from the next request. Off, the
// phone's own choice stands (none, or the Wi-Fi's). The phone's own network (127.x, the home network) is never sent to it.
object RiderProxy {
    enum class Kind(val key: String) { OFF("off"), HTTP("http"), SOCKS("socks") }

    data class Setting(val kind: Kind, val host: String, val port: Int, val user: String, val password: String) {
        val on get() = kind != Kind.OFF && host.isNotBlank() && port in 1..65535
    }

    private val OFF = Setting(Kind.OFF, "", 0, "", "")

    var setting by mutableStateOf(OFF)
        private set

    private var system: ProxySelector? = null
    private var installed = false
    private var overridden = false

    private fun store(ctx: Context) = ctx.applicationContext.getSharedPreferences("proxy", Context.MODE_PRIVATE)

    fun load(ctx: Context): Setting {
        val p = store(ctx)
        return Setting(
            Kind.entries.firstOrNull { it.key == p.getString("kind", null) } ?: Kind.OFF,
            p.getString("host", "").orEmpty(), p.getInt("port", 0), p.getString("user", "").orEmpty(), p.getString("password", "").orEmpty(),
        )
    }

    @Synchronized fun install(ctx: Context) {
        if (installed) return
        installed = true
        setting = load(ctx)
        system = ProxySelector.getDefault()
        ProxySelector.setDefault(Selector)
        java.net.Authenticator.setDefault(Login)
        // The pages' own network is the WebView's: told only once there's a proxy, as asking loads the WebView.
        if (setting.on) pages(ctx)
    }

    fun set(ctx: Context, s: Setting) {
        store(ctx).edit().putString("kind", s.kind.key).putString("host", s.host.trim()).putInt("port", s.port)
            .putString("user", s.user).putString("password", s.password).apply()
        setting = s.copy(host = s.host.trim())
        pages(ctx)
        // Off the main thread, where it's set from Settings: closing a TLS connection writes to it, which Android
        // forbids there (a crash on Android 17).
        kotlin.concurrent.thread(name = "kav-proxy-drop") {
            GoogleSession.dropConnections()
            GovMapTiles.dropConnections()
        }
    }

    private fun local(host: String?): Boolean {
        val h = host?.lowercase()?.trim('[', ']') ?: return true
        if (h == "localhost" || h.endsWith(".local") || h == "::1") return true
        val n = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 } ?: return false
        return n[0] == 127 || n[0] == 10 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31) || (n[0] == 169 && n[1] == 254)
    }

    private object Selector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val s = setting
            if (!s.on || local(uri.host)) return system?.select(uri) ?: listOf(Proxy.NO_PROXY)
            val at = InetSocketAddress.createUnresolved(s.host, s.port)
            // A plain socket (socket://) is asked about by the socket itself: only a SOCKS proxy carries one.
            return when {
                s.kind == Kind.SOCKS -> listOf(Proxy(Proxy.Type.SOCKS, at))
                uri.scheme == "socket" -> listOf(Proxy.NO_PROXY)
                else -> listOf(Proxy(Proxy.Type.HTTP, at))
            }
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) = Unit
    }

    // The proxy's login, for the phone's own connections (HttpURLConnection on an HTTP proxy, any socket on SOCKS5).
    private object Login : java.net.Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val s = setting
            val forProxy = requestorType == RequestorType.PROXY || requestingProtocol?.startsWith("SOCKS") == true
            if (!s.on || !forProxy || s.user.isEmpty()) return null
            return PasswordAuthentication(s.user, s.password.toCharArray())
        }
    }

    // The same login for OkHttp's own clients (Google, the map's tiles), which don't ask java.net's.
    val okHttp = okhttp3.Authenticator { _, response ->
        val s = setting
        if (!s.on || s.user.isEmpty() || response.request.header("Proxy-Authorization") != null) null
        else response.request.newBuilder().header("Proxy-Authorization", okhttp3.Credentials.basic(s.user, s.password)).build()
    }

    // A page's login asked for by the proxy (HTTP only: WebView can't log in to SOCKS). True when it was the proxy's.
    fun answer(handler: HttpAuthHandler, host: String?): Boolean {
        val s = setting
        if (!s.on || s.user.isEmpty() || !host.equals(s.host, ignoreCase = true)) return false
        handler.proceed(s.user, s.password)
        return true
    }

    // Whether the pages Kav shows can be sent through a proxy on this phone's WebView.
    val pagesFollow get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    private fun pages(ctx: Context) {
        if (!pagesFollow) return
        val s = setting
        val run = java.util.concurrent.Executor { it.run() }
        if (s.on) {
            val scheme = if (s.kind == Kind.SOCKS) "socks5" else "http"
            val config = ProxyConfig.Builder().addProxyRule("$scheme://${s.host}:${s.port}")
                .addBypassRule("localhost").addBypassRule("127.0.0.1").build()
            ProxyController.getInstance().setProxyOverride(config, run) {}
            overridden = true
        } else if (overridden) {
            ProxyController.getInstance().clearProxyOverride(run) {}
            overridden = false
        }
    }

    // A connection for url, through the rider's proxy when there is one. Handed the proxy itself, the phone's own HTTP
    // stack tries it alone: left to the ProxySelector, it falls back to a direct connection when the proxy can't be
    // reached, which is just when a rider counting on it would least want one.
    fun open(url: java.net.URL): java.net.URLConnection {
        val s = setting
        if (!s.on || local(url.host)) return url.openConnection()
        val type = if (s.kind == Kind.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return url.openConnection(Proxy(type, InetSocketAddress.createUnresolved(s.host, s.port)))
    }

    // Whether the proxy carries a request now: Kav's website asked through it. Null when it does, else why not.
    fun check(): String? = try {
        val c = open(java.net.URL("https://kavapp.link/")) as java.net.HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "Kav")
        try {
            val code = c.responseCode
            if (code in 200..399) null else "HTTP $code"
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
