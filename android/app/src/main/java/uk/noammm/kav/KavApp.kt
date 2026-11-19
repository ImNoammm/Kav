package uk.noammm.kav

import android.app.Application

// Before any part of Kav connects (the map's tiles, a trip service, an alert): the rider's proxy, if they set one.
class KavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        uk.noammm.kav.data.RiderProxy.install(this)
        // Debug builds only: a test block list's address and key first, as ExtApp.start makes the hub at once (since the
        // Home body, ExtShown) and the hub keeps the key it was made with (src/debug, Fixtures.kt).
        if (BuildConfig.DEBUG) runCatching {
            Class.forName("uk.noammm.kav.ext.Fixtures").getMethod("keys", android.content.Context::class.java).invoke(null, this)
        }
        // Extensions: safe mode, the crash count and the rider's picks, only once something was added (ExtApp).
        if (java.io.File(filesDir, "ext").isDirectory) uk.noammm.kav.ext.ExtApp.start(this)
        // Debug builds only: the extension test fixtures, when a test put their file in place (src/debug, Fixtures.kt).
        if (BuildConfig.DEBUG) runCatching {
            Class.forName("uk.noammm.kav.ext.Fixtures").getMethod("install", android.content.Context::class.java).invoke(null, this)
        }
    }
}
