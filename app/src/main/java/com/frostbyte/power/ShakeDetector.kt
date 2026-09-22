package com.frostbyte.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import kotlin.math.sqrt

/**
 * Detects a "shake" gesture and fires [onShake].
 *
 * Two mechanisms are used together:
 *
 * 1. TYPE_SIGNIFICANT_MOTION (when the device has it) - this is a genuine
 *    hardware wake-up trigger sensor. Unlike a plain accelerometer, Android
 *    is specifically designed to keep delivering these even while the
 *    screen is off and the device is in Doze, because that's the sensor's
 *    entire purpose (it's what things like "lift to wake" rely on
 *    elsewhere in the OS). This is what actually makes Shake to Wake work
 *    with the screen off - a bare accelerometer listener, even held with a
 *    partial wake lock, is frequently suspended by the platform's sensor
 *    batching/power policy the moment the display turns off, regardless of
 *    the wake lock (confirmed non-functional this way on the user's
 *    device). It's a one-shot trigger: it must be re-armed via
 *    requestTriggerSensor() after every firing.
 *
 * 2. The original raw accelerometer + magnitude-threshold approach is kept
 *    as a fallback for devices that don't expose TYPE_SIGNIFICANT_MOTION,
 *    and remains the only mechanism used while the screen is already on
 *    (where sensor suspension isn't a factor and the adjustable
 *    sensitivity levels are meaningful).
 */
class ShakeDetector(
    context: Context,
    private val onShake: () -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val significantMotion: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

    private var threshold = ShakeSensitivity.MEDIUM.threshold
    private var lowPower = false
    private var registered = false
    private var significantMotionArmed = false
    private var lastShakeTime = 0L
    private val minShakeIntervalMs = 1000L

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            significantMotionArmed = false
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > minShakeIntervalMs) {
                lastShakeTime = now
                onShake()
            }
            // This is a one-shot trigger - re-arm immediately so the next
            // shake is still caught.
            armSignificantMotion()
        }
    }

    private fun armSignificantMotion() {
        val sensor = significantMotion ?: return
        if (significantMotionArmed) return
        significantMotionArmed = sensorManager.requestTriggerSensor(significantMotionListener, sensor)
    }

    private fun disarmSignificantMotion() {
        significantMotion?.let { sensorManager.cancelTriggerSensor(significantMotionListener, it) }
        significantMotionArmed = false
    }

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
        armSignificantMotion()
    }

    fun stop() {
        disarmSignificantMotion()
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
