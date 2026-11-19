package uk.noammm.kav.ext

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display

// Debug builds only: an empty screen that keeps Kav in front while ColdStartTest measures on a phone, on a hidden display,
// since a rider's search always runs with Kav in front (Android gives the app in front the fast cores). It never stays
// on the phone's own screen: it closes if it's there, or if its hidden display goes away (Android would move it over).
class SpeedHold : android.app.Activity() {
    private var mine = -1
    private val watch = object : DisplayManager.DisplayListener {
        override fun onDisplayRemoved(id: Int) { if (id == mine) finish() }
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayChanged(id: Int) { if (display?.displayId == Display.DEFAULT_DISPLAY) finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mine = display?.displayId ?: Display.DEFAULT_DISPLAY
        if (mine == Display.DEFAULT_DISPLAY) { finish(); return }
        getSystemService(DisplayManager::class.java).registerDisplayListener(watch, null)
    }

    override fun onResume() {
        super.onResume()
        if (display?.displayId == Display.DEFAULT_DISPLAY) finish()
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(watch)
        super.onDestroy()
    }
}
