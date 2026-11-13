package uk.noammm.kav.ui

import kotlin.math.abs
import kotlin.math.exp

// A continuous, critically damped heading. New sensor readings change its goal without restarting its motion.
internal class HeadingSpring(initial: Float) {
    var value = initial
        private set
    private var speed = 0.0

    fun step(goal: Float, seconds: Double): Boolean {
        val delta = ((goal - value + 540f) % 360f - 180f).toDouble()
        if (abs(delta) < .02 && abs(speed) < .1) {
            value = goal
            speed = 0.0
            return false
        }
        val dt = seconds.coerceIn(0.0, .05)
        val w = 18.0
        val error = -delta
        val c = speed + w * error
        val decay = exp(-w * dt)
        value = (((value + delta + (error + c * dt) * decay) % 360 + 360) % 360).toFloat()
        speed = (speed - w * c * dt) * decay
        return true
    }
}
