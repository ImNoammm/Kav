package uk.noammm.kav

import android.app.Application

// Before any part of Kav connects (the map's tiles, a trip service, an alert): the rider's proxy, if they set one.
class KavApp : Application() {
    override fun onCreate() {
        super.onCreate()
        uk.noammm.kav.data.RiderProxy.install(this)
    }
}
