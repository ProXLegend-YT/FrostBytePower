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
 * Two mechanisms exist:
 *
 * 1. The raw accelerometer + magnitude-threshold check. This is the ONLY
 *    mechanism that makes the sensitivity setting (Low/Medium/High)
 *    actually mean anything, since it's the only one with an adjustable
 *    threshold. It runs continuously, screen on or off, backed by a
 *    partial wake lock that keeps the CPU (and therefore sensor delivery)
 *    alive while the display is off.
 *
 * 2. TYPE_SIGNIFICANT_MOTION (when the device has it) - a coarse hardware
 *    trigger Android itself uses for things like "lift to wake". It has no
 *    adjustable sensitivity at all and is tuned for a fairly large motion
 *    (e.g. picking the phone up off a table), not a light shake.
 *
 * Bug history: this used to treat significant-motion as the primary,
 * always-on mechanism for screen-off wakes, with the raw accelerometer
 * documented as "only meaningful with the screen on". In practice
 * significant-motion's own fixed trigger point would frequently fire
 * before the raw accelerometer's threshold check got a chance to, which
 * made the High/Medium/Low setting feel like it did nothing for
 * Shake-to-Wake - the user's actual selection was being raced and
 * overridden by an unrelated, non-adjustable sensor. Significant-motion is
 * now only armed as a fallback for devices where the raw accelerometer
 * listener fails to register at all; whenever the accelerometer is
 * available, the user's chosen sensitivity is what decides how hard a
 * shake needs to be, screen on or off.
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
    private val minShakeIntervalMs = 600L

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
        // Only used as a fallback when there's no accelerometer to honor
        // the user's chosen sensitivity with - see class doc.
        if (accelerometer != null) return
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
