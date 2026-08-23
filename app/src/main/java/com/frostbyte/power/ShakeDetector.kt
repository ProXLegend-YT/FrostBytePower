package com.frostbyte.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detects a "shake" gesture from the accelerometer and fires [onShake].
 * Threshold is in m/s^2 above gravity - lower threshold = more sensitive.
 *
 * Battery note: SENSOR_DELAY_GAME (~50Hz) is used normally. Low-power mode
 * drops to SENSOR_DELAY_NORMAL (~5Hz) which is enough for shake detection
 * and cuts accelerometer wakeups roughly 10x. The listener is fully
 * unregistered (not just paused) whenever sensitivity is DISABLED, so there
 * is zero sensor cost when the feature is off.
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

    private var threshold = ShakeSensitivity.MEDIUM.threshold
    private var lowPower = false
    private var registered = false
    private var lastShakeTime = 0L
    private val minShakeIntervalMs = 1000L

    // Most Android sensor implementations suspend delivery to a
    // non-wakeup accelerometer while the screen is off, which is exactly
    // when Shake to Wake needs to detect motion. A low-overhead partial
    // wake lock (CPU stays on, screen stays off) keeps the sensor pipeline
    // alive without waking the display itself.
    private var sensorWakeLock: android.os.PowerManager.WakeLock? = null

    fun updateSensitivity(sensitivity: ShakeSensitivity) {
        threshold = sensitivity.threshold
    }

    fun updateLowPowerMode(enabled: Boolean) {
        if (lowPower == enabled) return
        lowPower = enabled
        if (registered) {
            stop()
            start()
        }
    }

    fun start() {
        if (registered) return
        accelerometer?.let {
            val delay = if (lowPower) SensorManager.SENSOR_DELAY_NORMAL else SensorManager.SENSOR_DELAY_GAME
            sensorManager.registerListener(this, it, delay)
            registered = true

            sensorWakeLock = powerManager.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "FrostBytePower:ShakeSensorWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
        sensorWakeLock?.let { if (it.isHeld) it.release() }
        sensorWakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (threshold <= 0f) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        if (gForce > threshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > minShakeIntervalMs) {
                lastShakeTime = now
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op.
    }
}
