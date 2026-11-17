package com.godcross.magnifier

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/**
 * Watches the gyroscope so a photo is only taken once the phone has stopped moving.
 * At high magnification even a tiny hand tremor smears the image, so this matters
 * more than any other setting for close-up shots.
 */
class SteadyDetector(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val available: Boolean get() = gyro != null

    /** Smoothed angular speed in rad/s. */
    @Volatile
    var level: Float = 0f
        private set

    @Volatile
    private var stillSince: Long = 0L

    /** Called on the main thread whenever the steady/not-steady state flips. */
    var onSteadyChanged: ((Boolean) -> Unit)? = null
    private var lastReportedSteady: Boolean? = null

    var threshold: Float = 0.035f

    fun start() {
        gyro?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        stillSince = 0L
        lastReportedSteady = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        level = level * 0.75f + magnitude * 0.25f

        val now = SystemClock.elapsedRealtime()
        val steady = level < threshold
        if (steady) {
            if (stillSince == 0L) stillSince = now
        } else {
            stillSince = 0L
        }
        if (steady != lastReportedSteady) {
            lastReportedSteady = steady
            onSteadyChanged?.invoke(steady)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun isSteadyFor(holdMs: Long): Boolean {
        val since = stillSince
        return since != 0L && SystemClock.elapsedRealtime() - since >= holdMs
    }

    /** Suspends until the phone has been still for [holdMs], or [timeoutMs] runs out. */
    suspend fun awaitSteady(timeoutMs: Long, holdMs: Long = 300L): Boolean {
        if (!available) return true
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            if (isSteadyFor(holdMs)) return true
            delay(15)
        }
        return false
    }
}
