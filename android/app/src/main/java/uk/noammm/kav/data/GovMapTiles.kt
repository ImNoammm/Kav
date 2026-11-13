package uk.noammm.kav.data

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

// GovMap's tile server answers only its own site: a request has to come from www.govmap.gov.il and call itself a browser,
// anything else gets 401 or 403. Requests to that host alone say so; every other request the map makes goes out as
// MapLibre sends it.
object GovMapTiles {
    const val HOST = "basemaps.govmap.gov.il"
    const val HOST_URL = "https://$HOST"
    private const val SITE = "https://www.govmap.gov.il/"
    private const val AGENT = "Mozilla/5.0 (Linux; Android) Kav"

    private var installed = false
    private var client: OkHttpClient? = null

    // The map's open connections closed, when the rider's proxy changes (RiderProxy).
    fun dropConnections() { client?.connectionPool?.evictAll() }

    // MapLibre, set up the first time a map is made, with this client: MapLibre's own takes as many requests a host.
    @Synchronized fun mapLibre(ctx: android.content.Context) {
        MapLibre.getInstance(ctx.applicationContext)
        if (installed) return
        installed = true
        HttpRequestUtil.setOkHttpClient(
            OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply { maxRequestsPerHost = 20 })
                .proxyAuthenticator(RiderProxy.okHttp)
                .addInterceptor { chain ->
                    val r = chain.request()
                    chain.proceed(if (r.url.host == HOST) r.newBuilder().header("Referer", SITE).header("User-Agent", AGENT).build() else r)
                }
                .build().also { client = it },
        )
    }
}
